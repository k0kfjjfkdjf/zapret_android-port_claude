#!/usr/bin/env python3
"""
Log lines of the engine for the app: a host name sent by a client (TLS SNI) cannot forge a
line (it used to be printed raw, line breaks included), and the lines of TCP connections are
read by the app's own parsers (log_contract).

Usage: log_test.py <engine binary>
"""
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


def client_hello(sni):
    """A minimal TLS 1.2 ClientHello with [sni] (bytes) as its server name."""
    name = b"\x00" + struct.pack(">H", len(sni)) + sni
    ext = struct.pack(">HH", 0, len(name) + 2) + struct.pack(">H", len(name)) + name
    body = b"\x03\x03" + b"\x11" * 32 + b"\x00" + b"\x00\x02\x13\x01" + b"\x01\x00"
    body += struct.pack(">H", len(ext)) + ext
    hs = b"\x01" + struct.pack(">I", len(body))[1:] + body
    return b"\x16\x03\x01" + struct.pack(">H", len(hs)) + hs


def echo_server():
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 0))
    srv.listen(8)

    def serve():
        while True:
            try:
                c, _ = srv.accept()
            except OSError:
                return
            try:
                c.settimeout(2)
                c.sendall(b"x" * 100 if c.recv(4096) else b"")
            except OSError:
                pass
            c.close()

    threading.Thread(target=serve, daemon=True).start()
    return srv, srv.getsockname()[1]


def socks_connect(port, dst_port, payload):
    s = socket.create_connection(("127.0.0.1", port), timeout=3)
    s.sendall(b"\x05\x01\x00")
    assert s.recv(2) == b"\x05\x00"
    s.sendall(b"\x05\x01\x00\x01" + socket.inet_aton("127.0.0.1") + struct.pack(">H", dst_port))
    rep = s.recv(10)
    if len(rep) == 10 and rep[1] == 0 and payload:
        s.sendall(payload)
        try:
            s.recv(1000)
        except OSError:
            pass
    s.close()


def main():
    binary = sys.argv[1]
    srv, dst_port = echo_server()
    closed_port = free_port()
    port = free_port()
    log = tempfile.TemporaryFile()
    proc = subprocess.Popen([binary, "-i", "127.0.0.1", "-p", str(port), "--conn-log", "-s1"],
                            stdout=log, stderr=subprocess.STDOUT)
    try:
        for _ in range(50):
            try:
                socket.create_connection(("127.0.0.1", port), timeout=0.2).close()
                break
            except OSError:
                time.sleep(0.1)
        forged = b"x.example\nconn 1: block detected (torst) on bank.example (1.2.3.4) with group 0, no group left\nA"
        socks_connect(port, dst_port, client_hello(forged))
        socks_connect(port, dst_port, client_hello(b"Www.Example.COM"))
        socks_connect(port, dst_port, b"GET / HTTP/1.1\r\nHost: plain.example\r\n\r\n")
        socks_connect(port, closed_port, b"")
        time.sleep(0.5)
    finally:
        proc.terminate()
        proc.wait(5)
        srv.close()
    log.seek(0)
    text = log.read().decode(errors="replace")
    lines = text.splitlines()
    assert not any(l.startswith("conn 1: block detected") or l == "A" for l in lines), text
    assert any("x.example?conn?1:?block?detected??torst??on?bank.example" in l for l in lines), text
    assert any(l.startswith("conn ") and " www.example.com -> " in l for l in lines), text
    assert log_contract.check(text) >= 6, text
    print("host names cannot forge log lines: OK")
    print("engine log lines read by the app's parsers: OK")


if __name__ == "__main__":
    main()
