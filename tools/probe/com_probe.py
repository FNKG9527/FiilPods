"""Talk to FIIL Key Max over the Windows SPP COM port.

Sends Mate and GAIA query frames, then reports every byte that comes
back. Pure ASCII output so the calling shell cannot mangle it.
"""
import sys
import time
import serial  # pyserial

PORT = "COM4"
BAUD = 921600

MATE_QUERY_VERSION = bytes([0x00, 0x27, 0x01, 0x00, 0x02, 0x02, 0x00])
GAIA_GET_EARTYPE = bytes([0xFF, 0xF1, 0x00, 0x00, 0x02, 0x34, 0x03, 0x09])

FRAMES = [
    ("MATE_QUERY_VERSION", MATE_QUERY_VERSION),
    ("GAIA_GET_EARTYPE", GAIA_GET_EARTYPE),
]


def probe(ser, name, frame):
    ser.reset_input_buffer()
    ser.write(frame)
    ser.flush()
    print("  TX", name, frame.hex(" "))
    deadline = time.time() + 3.0
    buf = bytearray()
    while time.time() < deadline:
        n = ser.in_waiting
        if n:
            buf += ser.read(n)
        elif buf:
            # give a short grace period for trailing bytes
            time.sleep(0.08)
            if ser.in_waiting == 0:
                break
        else:
            time.sleep(0.02)
    if buf:
        print("  RX", len(buf), "bytes:", bytes(buf).hex(" "))
    else:
        print("  RX <none>")
    return bytes(buf)


def main():
    try:
        ser = serial.Serial(PORT, BAUD, timeout=0.2, write_timeout=3)
    except Exception as exc:
        print("open", PORT, "failed:", type(exc).__name__, exc)
        return 2

    print("opened", PORT, "baud", BAUD)
    try:
        for name, frame in FRAMES:
            probe(ser, name, frame)
            time.sleep(0.3)
    finally:
        ser.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
