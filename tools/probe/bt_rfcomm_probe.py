"""Probe FIIL Key Max over RFCOMM using the private service UUID.

Windows does not expose Bluetooth RFCOMM sockets through the standard
socket module, so this script reports clearly whether the platform
path is usable before attempting any protocol traffic. Output is pure
ASCII to avoid codepage problems in the calling shell.
"""
import socket
import sys

MAC = "B0:F1:AA:BB:CC:DD"
UUID_FIIL = "d52b47bc-2dea-42c4-b26d-20252fceeeb3"
UUID_SPP = "00001101-0000-1000-8000-00805F9B34FB"

print("python:", sys.version.split()[0])
print("platform:", sys.platform)
print("AF_BLUETOOTH:", getattr(socket, "AF_BLUETOOTH", None))
print("BTPROTO_RFCOMM:", getattr(socket, "BTPROTO_RFCOMM", None))

if not hasattr(socket, "AF_BLUETOOTH"):
    print("RESULT: AF_BLUETOOTH missing; cannot use python rfcomm")
    sys.exit(2)

family = socket.AF_BLUETOOTH
proto = getattr(socket, "BTPROTO_RFCOMM", 3)

for uuid in (UUID_FIIL, UUID_SPP):
    print("--- try", uuid)
    try:
        s = socket.socket(family, socket.SOCK_STREAM, proto)
    except OSError as exc:
        print("socket() failed:", exc)
        break
    try:
        s.settimeout(8.0)
        s.connect((MAC, 0, uuid))
        print("CONNECTED", uuid)
        s.close()
        sys.exit(0)
    except Exception as exc:
        print("connect failed:", type(exc).__name__, exc)
    finally:
        try:
            s.close()
        except Exception:
            pass

print("RESULT: python rfcomm path unusable on this platform")
sys.exit(1)
