#!/usr/bin/env python3
"""
UDP association test for the engine binary: a datagram that cannot be sent (here a broadcast
address without SO_BROADCAST, EACCES) must be dropped like any lost datagram, and must not end
the association: later datagrams of the same app socket to other destinations still go through.

Usage: udp_assoc_test.py <engine binary> [engine args...]
"""
import socket
import struct
import subprocess
import sys
import time


def free_port(kind=socket.SOCK_STREAM):
    s = socket.socket(socket.AF_INET, kind)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def socks5_udp_associate(port):
    ctrl = socket.create_connection(("127.0.0.1", port), timeout=3)
    ctrl.sendall(b"\x05\x01\x00")
    assert ctrl.recv(2) == b"\x05\x00", "greeting refused"
    ctrl.sendall(b"\x05\x03\x00\x01" + socket.inet_aton("0.0.0.0") + struct.pack(">H", 0))
    rep = ctrl.recv(10)
    assert len(rep) == 10 and rep[1] == 0, f"UDP ASSOCIATE refused: {rep!r}"
    relay = (socket.inet_ntoa(rep[4:8]), struct.unpack(">H", rep[8:10])[0])
    if relay[0] == "0.0.0.0":
        relay = ("127.0.0.1", relay[1])
    return ctrl, relay


def wrap(dst_ip, dst_port, payload):
    return b"\x00\x00\x00\x01" + socket.inet_aton(dst_ip) + struct.pack(">H", dst_port) + payload


def main():
    engine = sys.argv[1]
    extra = sys.argv[2:]
    port = free_port()
    echo = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    echo.bind(("127.0.0.1", 0))
    echo.settimeout(0.2)
    echo_port = echo.getsockname()[1]

    proc = subprocess.Popen([engine, "-i", "127.0.0.1", "-p", str(port)] + extra,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        for _ in range(50):
            try:
                socket.create_connection(("127.0.0.1", port), timeout=0.2).close()
                break
            except OSError:
                time.sleep(0.1)
        ctrl, relay = socks5_udp_associate(port)
        app = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        app.settimeout(2)

        def roundtrip(tag):
            app.sendto(wrap("127.0.0.1", echo_port, tag), relay)
            deadline = time.time() + 2
            while time.time() < deadline:
                try:
                    data, src = echo.recvfrom(2048)
                except socket.timeout:
                    continue
                if not data.endswith(tag):
                    continue  # a fake (--udp-fake) before the real datagram
                echo.sendto(data, src)
                break
            else:
                return False
            try:
                data, _ = app.recvfrom(2048)
            except socket.timeout:
                return False
            return data.endswith(tag)

        assert roundtrip(b"first"), "no echo before the failing datagram"
        # A destination the engine cannot send to (EACCES: broadcast without SO_BROADCAST).
        app.sendto(wrap("255.255.255.255", 9, b"lost"), relay)
        time.sleep(0.3)
        ok = roundtrip(b"second")
        ctrl.close()
        print("udp association survives an unsendable datagram:", "OK" if ok else "FAILED")
        return 0 if ok else 1
    finally:
        proc.terminate()
        try:
            out, _ = proc.communicate(timeout=3)
        except subprocess.TimeoutExpired:
            proc.kill()
            out, _ = proc.communicate()
        text = out.decode(errors="replace")
        if "ERROR: AddressSanitizer" in text or "runtime error:" in text:
            print(text)
            return 1


if __name__ == "__main__":
    sys.exit(main() or 0)
