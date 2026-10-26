using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Devices.Enumeration;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

namespace FiilProbe
{
    /// <summary>One parsed Mate frame.</summary>
    internal sealed class Frame
    {
        public int Seq;
        public int Cid;
        public int Type;
        public int LenField;
        public int Cmd = -1;
        public byte[] Payload = Array.Empty<byte>();

        public int At(int i) => i >= 0 && i < Payload.Length ? Payload[i] : -1;

        public override string ToString() =>
            $"seq={Seq} cid={Cid} type={Type} cmd={(Cmd < 0 ? "-" : Cmd.ToString())} payload={BitConverter.ToString(Payload)}";
    }

    /// <summary>
    /// Real-device regression harness for the FIIL Mate protocol (F045~F053).
    ///
    /// Frames are parsed with the same length rules as pods/FiilMate.kt:
    ///   long form  [seq][cid][type][0][lenField][cmd][payloadLen][payload]
    ///              lenField >= 2, total = lenField + 5, payloadLen = lenField - 2
    ///   short form [seq][cid][type][0][lenField][payload]
    ///              lenField <= 1, total = 5 + lenField
    ///
    /// SET commands are acknowledged on cid=40 (NOTIFY) rather than on the SET echo,
    /// so write verification waits for the notify frame before re-reading.
    /// </summary>
    internal static class Program
    {
        private const string AddressHint = "b0:f1:a3:a8:7a:9d";
        private const string VendorUuid = "d52b47bc-2dea-42c4-b26d-20252fceeeb3";

        private const int SET_PARAM = 1, SET_EQ = 32, SET_LOW_LATENCY = 37,
                          QUERY = 39, NOTIFY = 40, SET_MAF = 44, SET_SPATIAL = 50;

        private static readonly ConcurrentQueue<byte> Inbox = new();
        private static readonly AutoResetEvent Signal = new(false);

        private static int _pass, _fail;
        private static readonly List<string> Failures = new();

        private static async Task<int> Main(string[] args)
        {
            var mode = args.Length > 0 ? args[0] : "regress";
            if (mode == "probe") await Probe();
            else if (mode == "status") await Status();
            else if (mode == "raw") await Raw();
            else await Regress();

            Console.WriteLine();
            Console.WriteLine($"RESULT: pass={_pass} fail={_fail}");
            foreach (var f in Failures) Console.WriteLine("  FAIL " + f);
            return 0;
        }

        // ===================== regression =====================

        private static async Task Regress()
        {
            var socket = await Connect();
            if (socket == null) return;
            StartReader(socket);
            await Task.Delay(2500);
            Drain(600);

            var unlock = await Ask(socket, "UNLOCK", Mate(QUERY, 0xFF, new byte[] { 0x02, 0x34 }), 3000);
            Check("setUnlock 握手", First(unlock, QUERY) is { } u && u.At(0) == 0xFF, Describe(unlock));

            var ver = await Ask(socket, "VERSION", Mate(QUERY, 2), 2500);
            var vf = First(ver, QUERY);
            Check("版本查询", vf != null && vf.Payload.Length >= 4,
                  vf == null ? "无回包" : $"固件 {vf.At(2)}.{vf.At(3)}.{vf.At(4)}.{vf.At(5)}");
            Drain(300);

            // ---- MAF: write, let the state machine settle, then read back ----
            Console.WriteLine();
            Console.WriteLine("### MAF 五态（cid=44 写 / cid=40 通知 / cid=39 回读）");
            var mafNames = new[] { "关闭", "降噪", "开放", "兼听", "风中" };
            for (var m = 0; m < 5; m++)
            {
                var ack = await Ask(socket, $"MAF_SET[{m}]", Mate(SET_MAF, 0, new[] { (byte)m }), 1800);
                var notify = First(ack, NOTIFY);
                var echo = First(ack, SET_MAF);

                // The headset answers the read-back from its *current* mode, which lags
                // the write by the time it takes the audio DSP to switch. Poll until the
                // value lands or the budget runs out rather than sampling once.
                int got = -1;
                for (var attempt = 0; attempt < 6 && got != m; attempt++)
                {
                    await Task.Delay(400);
                    got = await ReadMaf(socket);
                }

                Check($"MAF={m} {mafNames[m]}", got == m,
                      $"notify={(notify?.At(0).ToString() ?? "-")} echo={(echo?.At(0).ToString() ?? "-")} 回读={got}");
                await Task.Delay(200);
            }

            // ---- cinema ----
            Console.WriteLine();
            Console.WriteLine("### 影院模式（cid=50）");
            foreach (var on in new[] { 1, 0 })
            {
                await Ask(socket, $"CINEMA_SET[{on}]", Mate(SET_SPATIAL, 0, new[] { (byte)on }), 1600);
                var got = await ReadSimple(socket, "CINEMA_GET", Mate(QUERY, 24), QUERY);
                Check($"影院={on}", got == on, $"回读={got}");
                await Task.Delay(250);
            }

            // ---- low latency ----
            Console.WriteLine();
            Console.WriteLine("### 低延迟（Mate 二态 0/1）");
            foreach (var lv in new[] { 1, 0 })
            {
                await Ask(socket, $"LATENCY_SET[{lv}]", Mate(SET_LOW_LATENCY, 0, new[] { (byte)lv }), 1600);
                var got = await ReadSimple(socket, "LATENCY_GET", Mate(QUERY, 8), QUERY);
                Check($"低延迟={lv}", got == lv, $"回读={got}");
                await Task.Delay(250);
            }

            // ---- EQ ----
            Console.WriteLine();
            Console.WriteLine("### EQ 读写（cid=32 写 / cid=39 cmd=4 读）");
            var eq = First(await Ask(socket, "EQ_READ", Mate(QUERY, 4), 2500), QUERY);
            if (eq == null) Check("EQ 读取", false, "无回包");
            else
            {
                var reserved = eq.At(0);
                var type = eq.At(1);
                var bands = eq.Payload.Skip(2).ToArray();
                Console.WriteLine($"  reserved={reserved} type={type} 段数={bands.Length}");
                Check("EQ 读取", bands.Length >= 3, $"段数={bands.Length}");

                // Round-trip: flip the first band both ways, echoing back the
                // reserved byte exactly as the app does (F046StyleFragment.s0).
                foreach (var delta in new[] { +4, -4 })
                {
                    var probe = bands.ToArray();
                    probe[0] = (byte)Math.Clamp(bands[0] + delta, 0, 255);
                    var wf = EqWrite(reserved, 15, probe);
                    var wack = await Ask(socket, $"EQ_WRITE[{delta:+#;-#}]", wf, 2200);
                    var readBack = First(await Ask(socket, "EQ_READBACK", Mate(QUERY, 4), 2500), QUERY);
                    var gotBand = readBack == null ? -1 : readBack.At(2);
                    Check($"EQ 写入 {bands[0]}->{probe[0]}", gotBand == probe[0],
                          $"回读={gotBand} ack={Describe(wack)}");
                    await Task.Delay(200);
                }

                // restore original
                await Ask(socket, "EQ_RESTORE", EqWrite(reserved, type, bands), 2200);
                var fin = First(await Ask(socket, "EQ_VERIFY", Mate(QUERY, 4), 2500), QUERY);
                Check("EQ 还原", fin != null && fin.At(2) == bands[0],
                      $"原={bands[0]} 现={(fin == null ? -1 : fin.At(2))}");
            }

            // ---- multipoint ----
            // Write goes to cid=1 cmd=1 (h.setChangLan); cmd=128 is only the read-back
            // query (c.java getChangLanStatus, -128 & 0xFF). Using 128 to write is silently
            // swallowed — the echo comes back but the state never changes.
            //
            // Turning multipoint OFF tears down the extra RFCOMM link, which on this unit
            // also drops the link we are talking over. So the read-back has to survive a
            // reconnect; that is real device behaviour, not a harness bug.
            Console.WriteLine();
            Console.WriteLine("### 蓝牙畅连（写 cid=1 cmd=1 / 读 cid=39 cmd=128）");
            var mpNow = await ReadSimple(socket, "MP_BEFORE", Mate(QUERY, 128), QUERY);
            Console.WriteLine($"  当前={mpNow}");
            var target = mpNow == 1 ? 0 : 1;
            var mpAck = await Ask(socket, $"MP_SET[{target}]", Mate(SET_PARAM, 1, new[] { (byte)target }), 1800);
            Console.WriteLine($"  回包 {Describe(mpAck)}");
            await Task.Delay(1500);

            int mpAfter;
            var mpProbe = await Ask(socket, "MP_AFTER", Mate(QUERY, 128), 1200);
            if (mpProbe == null)
            {
                Console.WriteLine("  链路已被耳机断开，重连后回读");
                try { socket.Dispose(); } catch { }
                await Task.Delay(3000);
                socket = await Connect();
                if (socket == null) { Check("畅连回读", false, "重连失败"); mpAfter = -1; }
                else
                {
                    StartReader(socket);
                    await Task.Delay(2000);
                    Drain(500);
                    await Ask(socket, "UNLOCK3", Mate(QUERY, 0xFF, new byte[] { 0x02, 0x34 }), 3000);
                    mpAfter = await ReadSimple(socket, "MP_AFTER2", Mate(QUERY, 128), QUERY);
                }
            }
            else mpAfter = First(mpProbe, QUERY)?.At(0) ?? -1;

            Check($"畅连 {mpNow}->{target}", mpAfter == target, $"回读={mpAfter}");

            // Never leave the headset with multipoint disabled.
            if (mpAfter != -1 && mpAfter != mpNow)
            {
                await Ask(socket, $"MP_RESTORE[{mpNow}]", Mate(SET_PARAM, 1, new[] { (byte)mpNow }), 1500);
                await Task.Delay(1200);
            }

            // ---- reconnect ----
            Console.WriteLine();
            Console.WriteLine("### 断连重连闭环");
            try { socket.Dispose(); } catch { }
            Console.WriteLine("  已断开，等待耳机释放 RFCOMM");
            await Task.Delay(3000);

            var s2 = await Connect();
            if (s2 == null) Check("重连", false, "重连失败");
            else
            {
                StartReader(s2);
                await Task.Delay(2000);
                Drain(500);
                var u2 = First(await Ask(s2, "UNLOCK2", Mate(QUERY, 0xFF, new byte[] { 0x02, 0x34 }), 3000), QUERY);
                Check("重连后解锁", u2 != null && u2.At(0) == 0xFF, u2?.ToString() ?? "无回包");
                var v2 = First(await Ask(s2, "VERSION2", Mate(QUERY, 2), 2500), QUERY);
                Check("重连后状态回读", v2 != null && v2.Payload.Length >= 4, v2?.ToString() ?? "无回包");
                var b2 = First(await Ask(s2, "BATTERY2", Mate(QUERY, 1), 2500), QUERY);
                Check("重连后电量", b2 != null && b2.Payload.Length >= 3, $"电量={b2?.At(2)}");
                try { s2.Dispose(); } catch { }
            }
        }

        private static async Task Probe()
        {
            var socket = await Connect();
            if (socket == null) return;
            StartReader(socket);
            await Task.Delay(2500);
            Drain(600);

            await Ask(socket, "UNLOCK", Mate(QUERY, 0xFF, new byte[] { 0x02, 0x34 }), 3000);

            var queries = new (string Name, int Cmd)[]
            {
                ("VERSION", 2), ("BATTERY", 1), ("EQ", 4), ("MAF", 12),
                ("LATENCY", 8), ("MULTIPOINT", 128), ("CODEC", 144), ("SPATIAL", 24),
            };
            foreach (var (name, cmd) in queries)
            {
                var f = First(await Ask(socket, name, Mate(QUERY, cmd), 2500), QUERY);
                Check(name, f != null, f == null ? "无回包" : f.ToString());
                await Task.Delay(150);
            }
            try { socket.Dispose(); } catch { }
        }

        // ---- helpers that read the current value of one query ----

        private static async Task<int> ReadMaf(StreamSocket socket)
        {
            var f = First(await Ask(socket, "MAF_GET", Mate(QUERY, 12), 2200), QUERY);
            return f?.At(0) ?? -1;
        }

        private static async Task<int> ReadSimple(StreamSocket socket, string label, byte[] frame, int cid)
        {
            var f = First(await Ask(socket, label, frame, 2200), cid);
            return f?.At(0) ?? -1;
        }

        /// <summary>First frame in the burst with the given cid (frames arrive concatenated).</summary>
        private static Frame First(byte[] burst, int cid)
        {
            if (burst == null) return null;
            foreach (var f in Split(burst))
                if (f.Cid == cid) return f;
            return null;
        }

        private static string Describe(byte[] burst) =>
            burst == null ? "(none)" : string.Join(" | ", Split(burst).Select(f => f.ToString()));

        /// <summary>
        /// Split a concatenated byte burst into Mate frames using FiilMate.kt's length rules.
        ///
        /// The type byte differs by direction — requests carry 0x01, responses 0x02 — so it
        /// must NOT be used as a frame marker. Synchronisation uses the fixed reserved byte
        /// at offset 3 (always 0x00) plus a sane length field, and resyncs by one byte on
        /// any inconsistency so a truncated or interleaved burst cannot desync the rest.
        /// </summary>
        private static List<Frame> Split(byte[] buf)
        {
            var list = new List<Frame>();
            var pos = 0;
            while (pos + 5 <= buf.Length)
            {
                var lenField = buf[pos + 4];
                var cid = buf[pos + 1];
                var reserved = buf[pos + 3];

                // Frame start: reserved byte zero and a plausible command-class byte.
                if (reserved != 0x00 || cid == 0x00 || cid > 0x3F)
                {
                    pos++;
                    continue;
                }

                if (lenField >= 2)
                {
                    var total = lenField + 5;
                    if (pos + total > buf.Length) break;
                    var payloadLen = lenField - 2;
                    list.Add(new Frame
                    {
                        Seq = buf[pos], Cid = cid, Type = buf[pos + 2], LenField = lenField,
                        Cmd = buf[pos + 5],
                        Payload = buf.Skip(pos + 7).Take(payloadLen).ToArray(),
                    });
                    pos += total;
                    continue;
                }

                var t2 = 5 + lenField;
                if (pos + t2 > buf.Length) break;
                list.Add(new Frame
                {
                    Seq = buf[pos], Cid = cid, Type = buf[pos + 2], LenField = lenField,
                    Payload = buf.Skip(pos + 5).Take(lenField).ToArray(),
                });
                pos += t2;
            }
            return list;
        }

        // ===================== frame builders =====================

        /// <summary>
        /// Mate frame, byte-identical to FiilMate.buildFrame: commandId 0 means
        /// "no sub-command" and uses the 5-byte short form.
        /// </summary>
        private static byte[] Mate(int cid, int cmd, byte[] payload = null)
        {
            payload ??= Array.Empty<byte>();
            if (cmd == 0)
                return new byte[] { 0x00, (byte)cid, 0x01, 0x00, (byte)payload.Length }
                       .Concat(payload).ToArray();

            return new byte[] { 0x00, (byte)cid, 0x01, 0x00,
                                (byte)(payload.Length + 2), (byte)(cmd & 0xFF), (byte)payload.Length }
                   .Concat(payload).ToArray();
        }

        /// <summary>
        /// EQ write, mirroring F046StyleFragment.s0(): the app mutates the buffer it
        /// read back and re-sends it whole, so payload[0] carries the headset's own
        /// reserved byte rather than a literal 0.
        /// </summary>
        private static byte[] EqWrite(int reserved, int type, byte[] bands)
        {
            var payload = new List<byte> { (byte)reserved, (byte)type };
            payload.AddRange(bands);
            return new byte[] { 0x00, SET_EQ, 0x01, 0x00, (byte)payload.Count }
                   .Concat(payload).ToArray();
        }

        // ===================== status =====================

        /// <summary>
        /// Read the real link state. A successful socket open only means the local
        /// stack has a path; it says nothing about the headset still being on the
        /// far end. ConnectionStatus on the RFCOMM service is the authoritative bit.
        /// </summary>
        private static async Task Status()
        {
            var serviceId = RfcommServiceId.FromUuid(Guid.Parse(VendorUuid));
            var found = await DeviceInformation.FindAllAsync(RfcommDeviceService.GetDeviceSelector(serviceId));
            Console.WriteLine($"RFCOMM 服务条目: {found.Count}");
            foreach (var info in found)
            {
                Console.WriteLine($"  - {info.Name}");
                Console.WriteLine($"    id   = {info.Id}");
                Console.WriteLine($"    kind = {info.Kind}  paired={info.Pairing?.IsPaired}");
                try
                {
                    var svc = await RfcommDeviceService.FromIdAsync(info.Id);
                    if (svc == null) { Console.WriteLine("    service=null"); continue; }
                    Console.WriteLine($"    host = {svc.ConnectionHostName}  port = {svc.ConnectionServiceName}");
                    Console.WriteLine($"    status = {svc.Device.ConnectionStatus}");
                    Console.WriteLine($"    device = {svc.Device.Name} ({svc.Device.DeviceId})");
                }
                catch (Exception ex)
                {
                    Console.WriteLine("    FromId failed: " + ex.Message);
                }
            }

            // The standard SPP service is the one Windows exposes as a COM port;
            // it tells us whether the headset is holding an SPP link at all.
            var spp = RfcommServiceId.SerialPort;
            var sppFound = await DeviceInformation.FindAllAsync(RfcommDeviceService.GetDeviceSelector(spp));
            Console.WriteLine();
            Console.WriteLine($"标准 SPP 服务条目: {sppFound.Count}");
            foreach (var info in sppFound)
            {
                try
                {
                    var svc = await RfcommDeviceService.FromIdAsync(info.Id);
                    Console.WriteLine($"  - {svc.ConnectionHostName} / {svc.ConnectionServiceName}  status={svc.Device.ConnectionStatus}");
                }
                catch (Exception ex)
                {
                    Console.WriteLine("  - FromId failed: " + ex.Message);
                }
            }
        }

        // ===================== raw =====================

        /// <summary>
        /// Byte-level diagnostic: connect, say nothing for a while, then send a single
        /// known-good frame and print every raw byte that comes back. Used to tell
        /// "headset is silent" apart from "we are misparsing what it sends".
        /// </summary>
        private static async Task Raw()
        {
            var socket = await Connect();
            if (socket == null) return;
            StartReader(socket);

            Console.WriteLine("listen 4s (headset may push unsolicited frames) ...");
            var idle = Collect(4000);
            Console.WriteLine(idle.Length == 0
                ? "  (silence)"
                : $"  raw {idle.Length}B: {BitConverter.ToString(idle)}");

            var unlock = Mate(QUERY, 0xFF, new byte[] { 0x02, 0x34 });
            Console.WriteLine($"\nTX unlock {Hex(unlock)}  (waiting 4s for any byte)");
            try
            {
                var writer = new DataWriter(socket.OutputStream) { ByteOrder = ByteOrder.LittleEndian };
                writer.WriteBytes(unlock);
                await writer.StoreAsync();
                await writer.FlushAsync();
                writer.DetachStream();
            }
            catch (Exception ex)
            {
                Console.WriteLine("  send failed: " + ex.GetType().Name + " " + ex.Message);
            }

            var resp = Collect(4000);
            Console.WriteLine(resp.Length == 0
                ? "  (silence — headset accepted the write but never answered)"
                : $"  raw {resp.Length}B: {BitConverter.ToString(resp)}");

            // Second try: some firmwares only answer the very first frame of a fresh
            // session; retrying distinguishes a lost ack from a dead link.
            var ver = Mate(QUERY, 2);
            Console.WriteLine($"\nTX version {Hex(ver)}");
            try
            {
                var writer = new DataWriter(socket.OutputStream) { ByteOrder = ByteOrder.LittleEndian };
                writer.WriteBytes(ver);
                await writer.StoreAsync();
                await writer.FlushAsync();
                writer.DetachStream();
            }
            catch (Exception ex)
            {
                Console.WriteLine("  send failed: " + ex.GetType().Name + " " + ex.Message);
            }
            var resp2 = Collect(3000);
            Console.WriteLine(resp2.Length == 0
                ? "  (silence)"
                : $"  raw {resp2.Length}B: {BitConverter.ToString(resp2)}");

            try { socket.Dispose(); } catch { }
        }

        // ===================== transport =====================

        private static async Task<StreamSocket> Connect()
        {
            var serviceId = RfcommServiceId.FromUuid(Guid.Parse(VendorUuid));
            var found = await DeviceInformation.FindAllAsync(RfcommDeviceService.GetDeviceSelector(serviceId));
            var target = found.FirstOrDefault(
                i => i.Id.IndexOf(AddressHint, StringComparison.OrdinalIgnoreCase) >= 0) ?? found.FirstOrDefault();
            if (target == null) { Console.WriteLine("no service"); return null; }

            var svc = await RfcommDeviceService.FromIdAsync(target.Id);
            var socket = new StreamSocket();
            try
            {
                await socket.ConnectAsync(svc.ConnectionHostName, svc.ConnectionServiceName);
                Console.WriteLine("CONNECTED");
                return socket;
            }
            catch (Exception ex)
            {
                Console.WriteLine("connect failed: " + ex.Message);
                return null;
            }
        }

        private static void StartReader(StreamSocket socket)
        {
            _ = Task.Run(async () =>
            {
                try
                {
                    var reader = new DataReader(socket.InputStream) { InputStreamOptions = InputStreamOptions.Partial };
                    while (true)
                    {
                        uint n = await reader.LoadAsync(512);
                        if (n == 0) break;
                        var buf = new byte[n];
                        reader.ReadBytes(buf);
                        foreach (var b in buf) Inbox.Enqueue(b);
                        Signal.Set();
                    }
                }
                catch { }
            });
        }

        private static async Task<byte[]> Ask(StreamSocket socket, string label, byte[] frame, int windowMs)
        {
            DrainQuiet();
            try
            {
                var writer = new DataWriter(socket.OutputStream) { ByteOrder = ByteOrder.LittleEndian };
                writer.WriteBytes(frame);
                await writer.StoreAsync();
                await writer.FlushAsync();
                writer.DetachStream();
            }
            catch (Exception ex)
            {
                Console.WriteLine($"  TX {label,-18} send failed: {ex.GetType().Name}");
                return null;
            }

            var got = Collect(windowMs);
            Console.WriteLine($"  TX {label,-18} {Hex(frame)}");
            if (got.Length > 0) Console.WriteLine($"     RX {Describe(got)}");
            return got.Length == 0 ? null : got;
        }

        private static void Drain(int windowMs)
        {
            var got = Collect(windowMs);
            if (got.Length > 0) Console.WriteLine($"  after-connect << {Describe(got)}");
        }

        private static void DrainQuiet()
        {
            while (Inbox.TryDequeue(out _)) { }
            Signal.Reset();
        }

        private static byte[] Collect(int windowMs)
        {
            var stopAt = DateTime.UtcNow.AddMilliseconds(windowMs);
            var got = new List<byte>();
            while (true)
            {
                var left = (int)(stopAt - DateTime.UtcNow).TotalMilliseconds;
                if (left <= 0) break;
                Signal.WaitOne(Math.Min(left, 100));
                while (Inbox.TryDequeue(out var b)) got.Add(b);
            }
            while (Inbox.TryDequeue(out var b)) got.Add(b);
            return got.ToArray();
        }

        private static void Check(string what, bool ok, string detail)
        {
            if (ok) { _pass++; Console.WriteLine($"    PASS {what}"); }
            else { _fail++; Failures.Add($"{what} ({detail})"); Console.WriteLine($"    FAIL {what}  <- {detail}"); }
        }

        private static string Hex(byte[] b) =>
            string.Join(" ", b.Select(x => x.ToString("x2", CultureInfo.InvariantCulture)));
    }
}
