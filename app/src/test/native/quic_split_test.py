#!/usr/bin/env python3
"""
--quic-split through the engine: a client's QUIC Initial reaches the server rewritten (same size,
other bytes) with the option, untouched without it, and other datagrams are never touched.
quic_split_initial itself (ClientHello unchanged, tags valid) is checked in test_tpws.c.

Usage: quic_split_test.py <engine binary> <dir of fakes>
"""
import os
import socket
import struct
import subprocess
import sys
import tempfile
import time


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def through_engine(binary, args, datagrams):
    srv = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    srv.bind(("127.0.0.1", 0))
    srv.settimeout(2)
    port = free_port()
    log = tempfile.TemporaryFile()
    proc = subprocess.Popen([binary, "-i", "127.0.0.1", "-p", str(port)] + args, stdout=log, stderr=subprocess.STDOUT)
    try:
        for _ in range(50):
            try:
                socket.create_connection(("127.0.0.1", port), timeout=0.2).close()
                break
            except OSError:
                time.sleep(0.1)
        ctrl = socket.create_connection(("127.0.0.1", port), timeout=3)
        ctrl.sendall(b"\x05\x01\x00")
        assert ctrl.recv(2) == b"\x05\x00"
        ctrl.sendall(b"\x05\x03\x00\x01" + socket.inet_aton("0.0.0.0") + struct.pack(">H", 0))
        rep = ctrl.recv(10)
        relay = ("127.0.0.1", struct.unpack(">H", rep[8:10])[0])
        app = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        header = b"\x00\x00\x00\x01" + socket.inet_aton("127.0.0.1") + struct.pack(">H", srv.getsockname()[1])
        got = []
        for d in datagrams:
            app.sendto(header + d, relay)
            got.append(srv.recvfrom(4096)[0])
        ctrl.close()
        return got
    finally:
        proc.terminate()
        proc.wait(5)
        srv.close()


def main():
    binary, fakes = sys.argv[1], sys.argv[2]
    initial = open(os.path.join(fakes, "quic_initial_www_google_com.bin"), "rb").read()
    other = b"\x40" + os.urandom(40)  # a short-header-like datagram
    plain = through_engine(binary, [], [initial, other])
    assert plain == [initial, other], "the engine changed datagrams without --quic-split"
    split = through_engine(binary, ["--quic-split", "midsld"], [initial, other, initial])
    assert len(split[0]) == len(initial) and split[0] != initial, "Initial not rewritten"
    # Byte 0 is under header protection, whose mask comes from the new ciphertext.
    assert split[0][1:5] == initial[1:5] and split[0][0] & 0xf0 == initial[0] & 0xf0
    assert split[1] == other, "a datagram that is no Initial was changed"
    assert split[2] == split[0], "a repeated Initial was rewritten differently"
    print("quic split through the engine: OK")


if __name__ == "__main__":
    main()
