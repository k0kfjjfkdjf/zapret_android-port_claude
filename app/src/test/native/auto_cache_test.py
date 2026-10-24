#!/usr/bin/env python3
"""
--auto-cache test for the engine binary: a server that resets the connection makes --auto move
the address to the next group; the engine saves that, and a new engine started with the same
file takes the address to that group at once. A file written for a strategy with another number
of groups is not loaded.

Usage: auto_cache_test.py <engine binary>
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


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def resetting_server(resets):
    """Accepts, reads the request, answers the first [resets[0]] connections with a TCP reset
    (a blocked site) and the next ones with an HTTP answer (the next group got through)."""
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 0))
    srv.listen(16)

    def serve():
        while True:
            try:
                c, _ = srv.accept()
            except OSError:
                return
            # The whole request: a split one (-s2) comes in parts, and a socket closed with
            # unread data answers with a reset of its own.
            req = b""
            try:
                c.settimeout(2)
                while b"\r\n\r\n" not in req:
                    part = c.recv(4096)
                    if not part:
                        break
                    req += part
            except OSError:
                pass
            if resets[0] > 0:
                resets[0] -= 1
                c.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
            else:
                try:
                    c.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
                except OSError:
                    pass
            c.close()

    threading.Thread(target=serve, daemon=True).start()
    return srv, srv.getsockname()[1]


def request(port, dst_port):
    s = socket.create_connection(("127.0.0.1", port), timeout=3)
    s.sendall(b"\x05\x01\x00")
    assert s.recv(2) == b"\x05\x00"
    s.sendall(b"\x05\x01\x00\x01" + socket.inet_aton("127.0.0.1") + struct.pack(">H", dst_port))
    rep = s.recv(10)
    assert len(rep) == 10 and rep[1] == 0, f"CONNECT refused: {rep!r}"
    s.sendall(b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
    try:
        s.recv(100)
    except OSError:
        pass
    s.close()


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
        self.proc.terminate()  # SIGTERM: the event loop returns, the cache is saved
        self.proc.wait(5)
        self.log.seek(0)
        text = self.log.read().decode(errors="replace")
        log_contract.check(text)
        return text


def groups_used(log):
    return [int(g) for g in re.findall(r"conn \d+: .* -> 127\.0\.0\.1:\d+, .*group (\d+)", log)]


def main():
    binary = sys.argv[1]
    resets = [1]
    srv, dst_port = resetting_server(resets)
    tmp = tempfile.mkdtemp()
    cache = os.path.join(tmp, "auto.cache")
    strategy = ["--conn-log", "--auto-cache", cache, "-s1", "-A", "torst", "-s2"]

    e = Engine(binary, strategy)
    request(e.port, dst_port)
    time.sleep(0.3)
    log = e.stop()
    assert "block detected (torst)" in log and "trying group 1" in log, log
    with open(cache) as f:
        text = f.read()
    assert text.startswith("zdcache 1 2\n"), text
    assert re.search(r"^t 1 \w+ \d+ 127\.0\.0\.1 32 %d " % dst_port, text, re.M), text + log
    assert not os.path.exists(cache + ".tmp")

    e = Engine(binary, strategy)
    request(e.port, dst_port)
    time.sleep(0.3)
    log = e.stop()
    assert "auto cache: 1 address(es) loaded" in log, log
    used = groups_used(log)
    assert used and used[0] == 1, f"expected group 1 from the cache, got {used}\n{log}"
    print("auto cache survives a restart: OK")

    resets[0] = 1
    # Three groups now: the two-group file must not be applied.
    other = ["--conn-log", "--auto-cache", cache, "-s1", "-A", "torst", "-s2", "-A", "torst", "-s3"]
    with open(cache, "w") as f:
        f.write(text)
    e = Engine(binary, other)
    request(e.port, dst_port)
    time.sleep(0.3)
    log = e.stop()
    assert "address(es) loaded" not in log, log
    used = groups_used(log)
    assert used and used[0] == 0, f"expected group 0 with another strategy, got {used}\n{log}"
    print("auto cache of another strategy ignored: OK")

    resets[0] = 1
    # A damaged file is not an error.
    with open(cache, "w") as f:
        f.write("zdcache 1 2\nt 1 zz\nt 99 0 0 127.0.0.1 32 1 0 -\nt 1 0 0 not-an-ip 32 1 0 -\n"
                "t 1 0 0 127.0.0.1 999 1 0 -\nt 1 0 0 127.0.0.1 -5 1 0 -\nx 1 0 0 127.0.0.1 32 1 0 -\n"
                "1 0 0 127.0.0.1 32 1 0 -\n")
    e = Engine(binary, strategy)
    request(e.port, dst_port)
    time.sleep(0.3)
    log = e.stop()
    assert "auto cache: 0 address(es) loaded" in log, log
    print("damaged auto cache lines skipped: OK")

    # Bits of groups that do not exist (the mask said every group was tried, and more) and a bit
    # length that overflowed: the engine still serves and stops (it used to spin for ever).
    resets[0] = 0
    with open(cache, "w") as f:
        f.write("zdcache 1 2\nt 1 ffffffffffffffff 0 127.0.0.1 32 %d %d -\n"
                "t 1 2 0 127.0.0.1 2147483647 %d %d -\n" % (dst_port, int(time.time()), dst_port, int(time.time())))
    e = Engine(binary, strategy)
    request(e.port, dst_port)
    time.sleep(0.3)
    log = e.stop()
    assert "auto cache: 0 address(es) loaded" in log, log
    print("damaged mask and bit length: OK")

    # A block soon after the start (within the save interval) is saved when due, with no other
    # event, before the process is killed (not stopped).
    resets[0] = 1
    with open(cache, "w") as f:
        f.write("zdcache 1 2\n")  # loaded at the start: the next save waits for the interval
    e = Engine(binary, strategy)
    t0 = time.time()
    request(e.port, dst_port)
    deadline = t0 + 20
    saved = False
    while time.time() < deadline:
        try:
            with open(cache) as f:
                if re.search(r"^t 1 \w+ \d+ 127\.0\.0\.1 32 %d " % dst_port, f.read(), re.M):
                    saved = True
                    break
        except FileNotFoundError:
            pass
        time.sleep(0.5)
    e.proc.kill()
    e.proc.wait(5)
    assert saved, "the block was not saved while the engine was idle"
    assert time.time() - t0 > 10, "saved before the interval"
    print(f"pending save done while idle after {time.time() - t0:.0f} s: OK")
    srv.close()


if __name__ == "__main__":
    main()
