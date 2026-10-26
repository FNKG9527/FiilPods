$ErrorActionPreference = 'Stop'

# Talk to the FIIL Key Max vendor RFCOMM service via WinRT.
# The plain COM port is bound to the standard SPP UUID and stays silent,
# so the vendor UUID must be opened through the WinRT socket API.

$mac = [uint64]0xB0F1A3A87A9D
$svcUuid = [Guid]'d52b47bc-2dea-42c4-b26d-20252fceeeb3'

Add-Type -AssemblyName System.Runtime.WindowsRuntime

$asTaskOp = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})
$asTaskAction = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction'
})[0]

function AwaitOp($op, [Type]$type) {
    $t = $asTaskOp[0].MakeGenericMethod($type)
    $nt = $t.Invoke($null, @($op))
    $nt.Wait(-1) | Out-Null
    $nt.Result
}
function AwaitAction($op) {
    $nt = $asTaskAction.Invoke($null, @($op))
    $nt.Wait(-1) | Out-Null
}

[Windows.Devices.Bluetooth.BluetoothDevice, Windows.Devices.Bluetooth, ContentType = WindowsRuntime] | Out-Null
[Windows.Devices.Bluetooth.Rfcomm.RfcommServiceId, Windows.Devices.Bluetooth, ContentType = WindowsRuntime] | Out-Null
[Windows.Devices.Bluetooth.Rfcomm.RfcommDeviceService, Windows.Devices.Bluetooth, ContentType = WindowsRuntime] | Out-Null
[Windows.Devices.Enumeration.DeviceInformation, Windows.Devices.Enumeration, ContentType = WindowsRuntime] | Out-Null
[Windows.Networking.Sockets.StreamSocket, Windows.Networking.Sockets, ContentType = WindowsRuntime] | Out-Null
[Windows.Storage.Streams.IBuffer, Windows.Storage.Streams, ContentType = WindowsRuntime] | Out-Null

$rfcommId = [Windows.Devices.Bluetooth.Rfcomm.RfcommServiceId]::FromUuid($svcUuid)
$selector = [Windows.Devices.Bluetooth.Rfcomm.RfcommDeviceService]::GetDeviceSelector($rfcommId)
$found = AwaitOp ([Windows.Devices.Enumeration.DeviceInformation]::FindAllAsync($selector, $null)) ([Windows.Devices.Enumeration.DeviceInformationCollection])

$target = $null
foreach ($f in $found) { if ($f.Id -match 'b0:f1:aa:bb:cc:dd') { $target = $f; break } }
if ($null -eq $target) { Write-Output 'RESULT: vendor service not advertised'; exit 3 }

$svc = AwaitOp ([Windows.Devices.Bluetooth.Rfcomm.RfcommDeviceService]::FromIdAsync($target.Id)) ([Windows.Devices.Bluetooth.Rfcomm.RfcommDeviceService])
$sock = New-Object Windows.Networking.Sockets.StreamSocket
try {
    AwaitAction ($sock.ConnectAsync($svc.ConnectionHostName, $svc.ConnectionServiceName))
    Write-Output 'CONNECTED'
}
catch {
    Write-Output ('connect failed: ' + $_.Exception.Message)
    Write-Output 'RESULT: connect failed'
    exit 1
}

$out = $sock.OutputStream
$in = $sock.InputStream

$bufExt = [System.Runtime.InteropServices.WindowsRuntime.WindowsRuntimeBufferExtensions]

function Send-Bytes([byte[]]$bytes) {
    $buf = $bufExt::AsBuffer($bytes, 0, $bytes.Length)
    AwaitAction ($out.WriteAsync($buf))
    AwaitAction ($out.FlushAsync())
}

$readBuf = $bufExt::AsBuffer((New-Object byte[] 512), 0, 512)

function Read-Some([int]$ms) {
    $op = $in.ReadAsync($readBuf, 512, [Windows.Storage.Streams.InputStreamOptions]::Partial)
    $t = $asTaskOp[0].MakeGenericMethod([uint32]).Invoke($null, @($op))
    if (-not $t.Wait($ms)) { return $null }
    $n = [int]$t.Result
    if ($n -le 0) { return $null }
    $arr = New-Object byte[] $n
    $bufExt::CopyToByteArray($readBuf, 0, $arr, 0, $n)
    return $arr
}

function Hex([byte[]]$b) { ($b | ForEach-Object { $_.ToString('x2') }) -join ' ' }

# Mate frame: seq, cid(0x27=39 QUERY), type(1 raw), packinfo(0), cmd, len, payload...
$frames = @(
    @{ n = 'MATE_QUERY_VERSION'; b = [byte[]](0x00, 0x27, 0x01, 0x00, 0x02, 0x02, 0x00) },
    @{ n = 'MATE_BATTERY';       b = [byte[]](0x01, 0x27, 0x01, 0x00, 0x02, 0x01, 0x00) },
    @{ n = 'MATE_MAF_STATUS';    b = [byte[]](0x02, 0x27, 0x01, 0x00, 0x02, 0x0C, 0x00) }
)

foreach ($fr in $frames) {
    Write-Output ('TX ' + $fr.n + ' ' + (Hex $fr.b))
    try { Send-Bytes $fr.b } catch { Write-Output ('  send failed: ' + $_.Exception.Message); break }
    $rx = Read-Some 2500
    if ($rx) { Write-Output ('RX ' + (Hex $rx)) } else { Write-Output 'RX <none>' }
    Start-Sleep -Milliseconds 150
}

$sock.Dispose()
Write-Output 'RESULT: done'
