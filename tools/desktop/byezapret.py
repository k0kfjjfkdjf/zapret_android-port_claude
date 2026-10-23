#!/usr/bin/env python3
"""ByeZapret's engine (ciadpi) on a Linux desktop, with the app's strategy text.

  byezapret.py build                         build the engine (make)
  byezapret.py print  -s strategy.txt        the engine's command line for the strategy
  byezapret.py run    -s strategy.txt        run the engine as a SOCKS5 proxy on 127.0.0.1:1080
  byezapret.py check  -s strategy.txt        does it get YouTube / Discord through? (like the
                                             app's self-check: without bypass, then with it)

The strategy is the text of the app's strategy field: several lines, # comments, the
references {fake:name}, {list:name}, {ipset:telegram}, {proxy} / {proxy:name} (--proxy
name=ip:port), and the script directives @set/$var, @if wifi|mobile|not …/@else/@end and
@block … @end. Groups with --apps {app:…} are Android-only and left out. @try, @fallback, @on
and @include need the app.
"""
import argparse
import os
import re
import shlex
import socket
import ssl
import subprocess
import sys
import tempfile
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ENGINE_DIR = os.path.join(ROOT, "app", "src", "main", "cpp", "engine")
ENGINE = os.path.join(ENGINE_DIR, "ciadpi")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")

TARGETS = [
    "www.google.com",
    "www.youtube.com",
    "youtubei.googleapis.com",
    "discord.com",
    "gateway.discord.gg",
    "cdn.discordapp.com",
    "media.discordapp.net",
]


class StrategyError(Exception):
    pass


# --- strategy text -> engine arguments --------------------------------------------------------

def strip_comment(line):
    """'# …' to the line end, when it starts a word; a '# === … ===' heading ends at its '==='."""
    out, i, quote = [], 0, None
    while i < len(line):
        c = line[i]
        if quote:
            if c == quote:
                quote = None
        elif c in "'\"":
            quote = c
        elif c == "#" and (i == 0 or line[i - 1].isspace()):
            if line.startswith("# ===", i):
                close = line.find("===", i + 5)
                if close >= 0:
                    i = close + 3
                    continue
            break
        out.append(c)
        i += 1
    return "".join(out)


def preprocess(text, network):
    """The script directives the desktop understands; the rest is an error with its line."""
    variables, lines, stack = {}, [], []  # stack: (kind, active, line)
    for no, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        active = all(s[1] for s in stack)
        if line.startswith("@"):
            word, _, rest = line[1:].partition(" ")
            rest = strip_comment(rest).strip()
            if word == "set":
                if active:
                    name, eq, value = rest.partition("=")
                    if not eq or not re.fullmatch(r"[A-Za-z_]\w*", name.strip()):
                        raise StrategyError(f"line {no}: @set name = value")
                    variables[name.strip()] = value.strip()
            elif word == "if":
                cond = rest.split()
                neg = cond[:1] == ["not"]
                cond = cond[1:] if neg else cond
                if not cond or any(c not in ("wifi", "mobile") for c in cond):
                    raise StrategyError(f"line {no}: @if wifi|mobile (on a desktop: --network)")
                stack.append(("if", (network in cond) != neg, no))
            elif word == "else":
                if not stack or stack[-1][0] != "if":
                    raise StrategyError(f"line {no}: @else without @if")
                kind, cond, start = stack.pop()
                stack.append(("else", not cond, start))
            elif word == "block":
                stack.append(("block", True, no))
            elif word == "end":
                if not stack:
                    raise StrategyError(f"line {no}: @end without a block")
                stack.pop()
            elif word in ("restart", "restart-delay", "restart-idle", "restart-limit", "restart-keep"):
                pass
            else:
                raise StrategyError(f"line {no}: @{word} needs the app (the desktop knows @set, @if, @block)")
            continue
        if not active:
            continue
        line = strip_comment(raw)
        line = re.sub(r"\$([A-Za-z_]\w*)", lambda m: variables.get(m.group(1), m.group(0)), line)
        lines.append(line)
    if stack:
        raise StrategyError(f"line {stack[-1][2]}: @{stack[-1][0]} has no @end")
    return " ".join(lines)


def resolve(token, proxies):
    def one(m):
        kind, name = m.group(1), m.group(2) or ""
        if kind == "fake":
            for p in (os.path.join(ASSETS, "fake", name), os.path.join(ASSETS, "fake", name + ".bin")):
                if os.path.isfile(p):
                    return p
            raise StrategyError(f"no fake '{name}' in {os.path.join(ASSETS, 'fake')}")
        if kind == "list":
            for p in (os.path.join(ASSETS, "lists", name), os.path.join(ASSETS, "lists", name + ".txt")):
                if os.path.isfile(p):
                    return p
            raise StrategyError(f"no list '{name}' in {os.path.join(ASSETS, 'lists')}")
        if kind == "ipset":
            if name == "telegram":
                return os.path.join(ASSETS, "telegram-ip.txt")
            raise StrategyError(f"no ipset '{name}' (only telegram)")
        if kind == "proxy":
            if name not in proxies:
                raise StrategyError(f"proxy '{name or 'main'}' unknown: add --proxy {name or 'main'}=ip:port")
            return "socks5://" + proxies[name]
        raise StrategyError(f"{{{kind}:…}} is Android-only")
    return re.sub(r"\{(fake|list|ipset|proxy|app)(?::([^{}\s]+))?\}", one, token)


def groups(args):
    """Splits at the --auto that ends each group (as the engine does)."""
    out, cur, i = [], [], 0
    while i < len(args):
        a = args[i]
        cur.append(a)
        if a in ("-A", "--auto") and i + 1 < len(args):
            cur.append(args[i + 1])
            i += 1
            out.append(cur)
            cur = []
        elif a.startswith("--auto="):
            out.append(cur)
            cur = []
        i += 1
    if cur:
        out.append(cur)
    return out


def engine_args(text, network="wifi", proxies=None):
    proxies = proxies or {}
    tokens = shlex.split(preprocess(text, network))
    kept, dropped = [], 0
    for g in groups(tokens):
        if any(t in ("--apps",) or t.startswith("--apps=") for t in g):
            dropped += 1
            continue
        kept += [resolve(t, proxies) for t in g]
    if dropped:
        print(f"note: {dropped} group(s) with --apps left out (Android-only)", file=sys.stderr)
    for t in ("-i", "--ip", "-p", "--port"):
        if t in kept:
            raise StrategyError(f"{t} is set by this script (--port)")
    return kept


# --- engine -------------------------------------------------------------------------------------

def build():
    subprocess.run(["make", "-C", ENGINE_DIR], check=True)
    print(ENGINE)


def ensure_engine():
    if not os.path.isfile(ENGINE):
        build()


def start(args, port, log):
    ensure_engine()
    proc = subprocess.Popen([ENGINE, "-i", "127.0.0.1", "-p", str(port)] + args, stdout=log, stderr=subprocess.STDOUT)
    for _ in range(50):
        if proc.poll() is not None:
            raise StrategyError(f"the engine exited ({proc.returncode}): see its output above")
        try:
            socket.create_connection(("127.0.0.1", port), 0.2).close()
            return proc
        except OSError:
            time.sleep(0.1)
    proc.kill()
    raise StrategyError("the engine does not listen")


# --- check --------------------------------------------------------------------------------------

def socks_connect(port, host, timeout):
    s = socket.create_connection(("127.0.0.1", port), timeout)
    s.sendall(b"\x05\x01\x00")
    if s.recv(2) != b"\x05\x00":
        raise OSError("SOCKS5 refused")
    h = host.encode()
    s.sendall(b"\x05\x01\x00\x03" + bytes([len(h)]) + h + (443).to_bytes(2, "big"))
    r = s.recv(10)
    if len(r) < 2 or r[1] != 0:
        raise OSError(f"SOCKS5 connect failed ({r[1] if len(r) > 1 else '?'})")
    return s


def probe(host, port=None, timeout=8.0, limit=65536):
    """(verdict, detail): loaded / answered, froze at N KB / failed at tls / connect."""
    t0 = time.time()
    try:
        raw = socks_connect(port, host, timeout) if port else socket.create_connection((host, 443), timeout)
    except OSError as e:
        return "FAIL", f"connect: {e}"
    raw.settimeout(timeout)
    try:
        tls = ssl.create_default_context().wrap_socket(raw, server_hostname=host)
    except (OSError, ssl.SSLError) as e:
        raw.close()
        return "FAIL", f"tls: {e.__class__.__name__}: {e}"[:120]
    got = 0
    try:
        tls.sendall(f"GET / HTTP/1.1\r\nHost: {host}\r\nUser-Agent: Mozilla/5.0\r\nAccept: */*\r\nConnection: close\r\n\r\n".encode())
        while got < limit:
            chunk = tls.recv(16384)
            if not chunk:
                break
            got += len(chunk)
    except (OSError, ssl.SSLError):
        tls.close()
        if got == 0:
            return "FAIL", "no answer after TLS"
        return "WARN", f"answered, froze at {got // 1024} KB (the 16-20 KB DPI freeze?)"
    tls.close()
    return "OK", f"loaded {got // 1024} KB in {time.time() - t0:.1f} s"


def check(args, port, targets):
    print(f"engine: {ENGINE} {' '.join(shlex.quote(a) for a in args)}\n")
    log = tempfile.NamedTemporaryFile("w+", prefix="ciadpi-", suffix=".log", delete=False)
    proc = start(args + ["--conn-log", "-x", "1"], port, log)
    try:
        print(f"{'target':28} {'without bypass':48} with the strategy")
        for host in targets:
            d = probe(host)
            b = probe(host, port)
            print(f"{host:28} {d[0]:4} {d[1][:43]:43} {b[0]:4} {b[1]}")
    finally:
        proc.terminate()
        proc.wait()
    print(f"\nengine log: {log.name}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", choices=["build", "print", "run", "check"])
    p.add_argument("-s", "--strategy", help="strategy file (the app's strategy text); - for stdin")
    p.add_argument("-e", "--args", help="the strategy as one string instead of a file")
    p.add_argument("--network", choices=["wifi", "mobile"], default="wifi", help="for @if (default wifi)")
    p.add_argument("--proxy", action="append", default=[], metavar="NAME=IP:PORT",
                   help="for {proxy:NAME}; main=IP:PORT for {proxy}")
    p.add_argument("--port", type=int, default=1080, help="SOCKS5 port (run), default 1080")
    p.add_argument("--target", action="append", help="host for check (repeatable; default YouTube, Discord)")
    o = p.parse_args()
    if o.command == "build":
        return build()
    if o.strategy:
        text = sys.stdin.read() if o.strategy == "-" else open(o.strategy, encoding="utf-8").read()
    elif o.args:
        text = o.args
    else:
        p.error("give the strategy: -s file or -e '…'")
    proxies = {}
    for spec in o.proxy:
        name, _, addr = spec.partition("=")
        proxies["" if name == "main" else name] = addr
    try:
        args = engine_args(text, o.network, proxies)
        if o.command == "print":
            print(" ".join(shlex.quote(a) for a in args))
        elif o.command == "run":
            ensure_engine()
            print(f"SOCKS5 on 127.0.0.1:{o.port} (Ctrl+C to stop)", file=sys.stderr)
            os.execv(ENGINE, [ENGINE, "-i", "127.0.0.1", "-p", str(o.port), "--conn-log"] + args)
        else:
            check(args, 10900 if o.port == 1080 else o.port, o.target or TARGETS)
    except StrategyError as e:
        sys.exit(f"error: {e}")


if __name__ == "__main__":
    main()
