#!/usr/bin/env python3
"""
--auto=udp test for the engine binary. The server answers a flow only after it has seen the
fake of group 1, as a blocked QUIC server answers only when the bypass works: group 0 gets no
reply, the engine moves the flow to group 1 while the client keeps repeating its datagram, and
the reply comes. With --auto-cache a new engine sends the next flow to group 1 at once.

Usage: udp_auto_test.py <engine binary>
"""
import os
import re
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time

FAKE = b"ZDFAKE"


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def server():
    """Answers datagrams of a client address once that address has sent the fake."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 0))
    unlocked = set()

    def serve():
        while True:
            try:
                data, peer = s.recvfrom(2048)
            except OSError:
                return
            if data == FAKE:
                unlocked.add(peer)
            elif peer in unlocked:
                s.sendto(b"pong:" + data, peer)

    threading.Thread(target=serve, daemon=True).start()
    return s, s.getsockname()[1]


class Engine:
    def __init__(self, binary, args):
        self.port = free_port()
        self.log = tempfile.TemporaryFile()
        self.proc = subprocess.Popen([binary, "-i", "127.0.0.1", "-p", str(self.port)] + args,
                                     stdout=self.log, stderr=subprocess.STDOUT)
        for _ in range(50):
            try:
                socket.create_connection(("127.0.0.1", self.port), timeout=0.2).close()
                return
            except OSError:
                time.sleep(0.1)
        raise AssertionError("engine did not start")

    def stop(self):
        self.proc.terminate()
        self.proc.wait(5)
        self.log.seek(0)
        return self.log.read().decode(errors="replace")


def flow(port, dst_port, seconds):
    """A client repeating its datagram every 300 ms, like a QUIC Initial; time of the reply or None."""
    ctrl = socket.create_connection(("127.0.0.1", port), timeout=3)
    ctrl.sendall(b"\x05\x01\x00")
    assert ctrl.recv(2) == b"\x05\x00"
    ctrl.sendall(b"\x05\x03\x00\x01" + socket.inet_aton("0.0.0.0") + struct.pack(">H", 0))
    rep = ctrl.recv(10)
    assert len(rep) == 10 and rep[1] == 0, rep
    relay = ("127.0.0.1", struct.unpack(">H", rep[8:10])[0])
    app = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    app.settimeout(0.3)
    start = time.monotonic()
    header = b"\x00\x00\x00\x01" + socket.inet_aton("127.0.0.1") + struct.pack(">H", dst_port)
    try:
        while time.monotonic() - start < seconds:
            app.sendto(header + b"hello", relay)
            try:
                data, _ = app.recvfrom(2048)
                if data[10:].startswith(b"pong:"):
                    return time.monotonic() - start
            except socket.timeout:
                pass
        return None
    finally:
        app.close()
        ctrl.close()


def main():
    binary = sys.argv[1]
    srv, dst_port = server()
    cache = os.path.join(tempfile.mkdtemp(), "auto.cache")
    strategy = ["--conn-log", "--auto-cache", cache,
                "-s1", "-A", "udp", "-a", "1", "-l", ":" + FAKE.decode()]

    e = Engine(binary, strategy)
    t = flow(e.port, dst_port, 5)
    log = e.stop()
    assert t is not None, "no reply after the switch\n" + log
    assert "block detected (udp)" in log and "trying group 1" in log, log
    assert 0.9 < t < 3, f"reply after {t:.2f} s\n{log}"
    with open(cache) as f:
        text = f.read()
    assert re.search(r"^u 1 \w+ 32 127\.0\.0\.1 32 %d " % dst_port, text, re.M), text
    print(f"udp flow moved to the next group, reply after {t:.2f} s: OK")

    e = Engine(binary, strategy)
    t = flow(e.port, dst_port, 3)
    log = e.stop()
    assert t is not None and t < 0.5, f"reply after {t}\n{log}"
    assert "from the auto cache" in log and "block detected" not in log, log
    print(f"udp flow took the cached group, reply after {t:.2f} s: OK")

    # No group left: the flow stays, the cached entry goes.
    e = Engine(binary, ["--conn-log", "--auto-cache", cache, "-s1", "-A", "udp", "-s2"])
    assert flow(e.port, dst_port, 2.5) is None
    log = e.stop()
    assert "no group left" in log, log
    with open(cache) as f:
        assert not re.search(r"^u ", f.read(), re.M), "the entry that did not work is kept"
    print("udp flow without a working group: OK")
    srv.close()


if __name__ == "__main__":
    main()
