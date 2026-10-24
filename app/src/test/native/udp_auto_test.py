#!/usr/bin/env python3
"""
--auto=udp test for the engine binary, with real QUIC Initials (www.google.com, facebook.com).
The server answers a flow only after it has seen the fake of the next group, as a blocked QUIC
server answers only when the bypass works: the first group gets no reply, the engine moves the
flow to the next udp group while the client keeps repeating its Initial, and the reply comes.
With --auto-cache a new engine sends the next flow to that group at once.

Group order is kept as for TCP: an excluded host, a group sent through a proxy and one-way UDP
of an unknown protocol never move, and a cached group never takes a flow whose own group is
another one (a proxy group's QUIC must stay dropped even when the address is shared).

Usage: udp_auto_test.py <engine binary> <dir of fakes>
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

import log_contract

FAKE = b"ZDFAKE"


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class Server:
    """Answers datagrams of a client address once that address has sent the fake; counts what came."""

    def __init__(self):
        self.s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.s.bind(("127.0.0.1", 0))
        self.port = self.s.getsockname()[1]
        self.unlocked = set()
        self.fakes = 0
        self.datagrams = 0
        threading.Thread(target=self.serve, daemon=True).start()

    def serve(self):
        while True:
            try:
                data, peer = self.s.recvfrom(4096)
            except OSError:
                return
            if data == FAKE:
                self.fakes += 1
                self.unlocked.add(peer)
            else:
                self.datagrams += 1
                if peer in self.unlocked:
                    self.s.sendto(b"pong", peer)

    def reset(self):
        self.unlocked.clear()
        self.fakes = 0
        self.datagrams = 0

    def close(self):
        self.s.close()


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
        text = self.log.read().decode(errors="replace")
        log_contract.check(text)
        return text


def flow(port, dst_port, payload, seconds):
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
            app.sendto(header + payload, relay)
            try:
                data, _ = app.recvfrom(2048)
                if data[10:] == b"pong":
                    return time.monotonic() - start
            except socket.timeout:
                pass
        return None
    finally:
        app.close()
        ctrl.close()
        time.sleep(0.1)


def main():
    binary, fakes = sys.argv[1], sys.argv[2]
    google = open(os.path.join(fakes, "quic_initial_www_google_com.bin"), "rb").read()
    facebook = open(os.path.join(fakes, "quic_initial_facebook_com.bin"), "rb").read()
    srv = Server()
    cache = os.path.join(tempfile.mkdtemp(), "auto.cache")
    fake = ["-a", "1", "-l", ":" + FAKE.decode()]
    strategy = ["--conn-log", "--auto-cache", cache, "-s1", "-A", "udp"] + fake

    e = Engine(binary, strategy)
    t = flow(e.port, srv.port, facebook, 5)
    log = e.stop()
    assert t is not None, "no reply after the switch\n" + log
    assert "block detected (udp) on facebook.com" in log and "trying group 1" in log, log
    assert 0.9 < t < 3, f"reply after {t:.2f} s\n{log}"
    with open(cache) as f:
        text = f.read()
    assert re.search(r"^u 1 \w+ 32 127\.0\.0\.1 32 %d \d+ facebook\.com$" % srv.port, text, re.M), text
    print(f"udp flow moved to the next group, reply after {t:.2f} s: OK")

    srv.reset()
    e = Engine(binary, strategy)
    t = flow(e.port, srv.port, facebook, 3)
    log = e.stop()
    assert t is not None and t < 0.5, f"reply after {t}\n{log}"
    assert "from the auto cache" in log and "block detected" not in log, log
    print(f"udp flow took the cached group, reply after {t:.2f} s: OK")

    # No group left: the flow stays, the cached entry goes.
    srv.reset()
    e = Engine(binary, ["--conn-log", "--auto-cache", cache, "-s1", "-A", "udp", "-s2"])
    assert flow(e.port, srv.port, facebook, 2.5) is None
    log = e.stop()
    assert "no group left" in log, log
    with open(cache) as f:
        assert not re.search(r"^u ", f.read(), re.M), "the entry that did not work is kept"
    print("udp flow without a working group: OK")

    # An excluded host (its own group without desync, closed by --auto=none) never moves.
    srv.reset()
    excl = ["--conn-log", "-H", ":www.google.com", "--auto=none", "-s1", "-A", "udp"] + fake
    e = Engine(binary, excl)
    assert flow(e.port, srv.port, google, 2.5) is None
    log = e.stop()
    assert "block detected" not in log and srv.fakes == 0, log
    print("excluded host stays excluded: OK")

    # One-way UDP of an unknown protocol is no block.
    srv.reset()
    e = Engine(binary, strategy)
    assert flow(e.port, srv.port, b"\x80" + os.urandom(60), 2.5) is None
    log = e.stop()
    assert "block detected" not in log and srv.fakes == 0, log
    print("one-way udp is not a block: OK")

    # A host sent through a proxy (its UDP dropped) on an address whose other host moved to an
    # auto group: the cache does not give it that group, its QUIC stays dropped.
    srv.reset()
    os.remove(cache)
    proxy = ["--conn-log", "--auto-cache", cache, "-H", ":www.google.com", "-C", "socks5://127.0.0.1:1",
             "--auto=none", "-s1", "-A", "udp"] + fake
    e = Engine(binary, proxy)
    assert flow(e.port, srv.port, facebook, 5) is not None
    log = e.stop()
    assert "trying group 2" in log, log
    srv.reset()
    e = Engine(binary, proxy)
    flow(e.port, srv.port, google, 1)
    log = e.stop()
    assert srv.datagrams == 0 and srv.fakes == 0, f"proxy host's QUIC reached the server\n{log}"
    assert "from the auto cache" not in log, log
    print("cached group does not take a proxy host's flow: OK")
    srv.close()


if __name__ == "__main__":
    main()
