#!/usr/bin/env python3
"""Runs every preset (core/Strategies.kt), tester candidate (test/TestPlan.kt) and community
strategy (assets/strategies/byebyedpi.list) through the
engine's option parser, so that a strategy the app offers can never fail to start."""
import re
import subprocess
import sys

binary, root = sys.argv[1], sys.argv[2]
src = root + "/src/main/java/io/github/zapretdroid/"
fake = root + "/src/main/assets/fake/"

strategies = []
presets = open(src + "core/Strategies.kt").read()
# Kotlin constants used as $NAME inside preset strings.
consts = {m.group(1): "".join(re.findall(r'"([^"]*)"', m.group(2)))
          for m in re.finditer(r'const val (\w+) = ((?:"[^"]*"\s*\+?\s*)+)', presets)}
for block in re.findall(r"Strategy\((.*?)\n        \),", presets, re.S):
    text = "".join(re.findall(r'"([^"]*)"', block)[1:])
    strategies.append(re.sub(r"\$(\w+)", lambda m: consts.get(m.group(1), m.group(0)), text))

plan = open(src + "test/TestPlan.kt").read()
strategies += re.findall(r'^\s*"(-[^"]*)",?\s*$', plan, re.M)
# Generated candidates: keep in sync with the ranges in TestPlan.full.
strategies += ["--fake -1 --ttl %d" % t for t in range(3, 13)]
strategies += ["--disorder 1 --fake -1 --ttl %d --fake-data {fake:tls_clienthello_www_google_com.bin}" % t
               for t in (4, 6, 10)]
# UDP candidates: keep in sync with TestPlan.udp.
u = consts["UDP_FAKES"]
strategies += ["--proto u " + c for c in [
    "--udp-fake 1 --ttl 4", "--udp-fake 3 --ttl 4", "--udp-fake 6 --ttl 4", "--udp-fake 6 --ttl 8", u,
    u.replace("--udp-desync 4", "--udp-desync 4:50"), u.replace("--ttl 4", "--ttl 2"),
    u.replace("--ttl 4", "--ttl 6"), u.replace("--udp-fake 6", "--udp-fake 10")]]

# ByeByeDPI community strategies, with {sni} as the tester substitutes it.
community = root + "/src/main/assets/strategies/byebyedpi.list"
strategies += [l.strip().replace("{sni}", "google.com") for l in open(community)
               if l.strip() and not l.strip().startswith("#")]

failed = 0
for line in strategies:
    args = re.sub(r"\{fake:([^}]+)\}", lambda m: fake + m.group(1), line).split()
    r = subprocess.run([binary, "--parse"] + args, capture_output=True, text=True)
    if r.returncode != 0:
        failed += 1
        print("rejected: %s\n%s" % (line, r.stderr.strip()[:500]))
print("%d strategies checked, %d rejected" % (len(strategies), failed))
sys.exit(1 if failed else 0)
