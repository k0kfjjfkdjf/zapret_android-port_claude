#!/usr/bin/env python3
"""Runs every preset (core/Strategies.kt) and tester candidate (test/TestPlan.kt) through the
engine's option parser, so that a strategy the app offers can never fail to start."""
import re
import subprocess
import sys

binary, root = sys.argv[1], sys.argv[2]
src = root + "/src/main/java/io/github/zapretdroid/"
fake = root + "/src/main/assets/fake/"

strategies = []
presets = open(src + "core/Strategies.kt").read()
for block in re.findall(r"Strategy\((.*?)\n        \),", presets, re.S):
    strategies.append("".join(re.findall(r'"([^"]*)"', block)[1:]))

plan = open(src + "test/TestPlan.kt").read()
strategies += re.findall(r'^\s*"(-[^"]*)",?\s*$', plan, re.M)
# Generated candidates: keep in sync with the ranges in TestPlan.full.
strategies += ["--fake -1 --ttl %d" % t for t in range(3, 13)]
strategies += ["--disorder 1 --fake -1 --ttl %d --fake-data {fake:tls_clienthello_www_google_com.bin}" % t
               for t in (4, 6, 10)]

failed = 0
for line in strategies:
    args = re.sub(r"\{fake:([^}]+)\}", lambda m: fake + m.group(1), line).split()
    r = subprocess.run([binary, "--parse"] + args, capture_output=True, text=True)
    if r.returncode != 0:
        failed += 1
        print("rejected: %s\n%s" % (line, r.stderr.strip()[:500]))
print("%d strategies checked, %d rejected" % (len(strategies), failed))
sys.exit(1 if failed else 0)
