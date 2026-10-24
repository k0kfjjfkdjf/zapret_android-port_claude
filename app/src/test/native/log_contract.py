"""
The engine's log lines are read by the app (core/.../ConnMonitor.kt, ScriptEvents.kt) with
regular expressions. This module takes those expressions from the Kotlin sources themselves and
checks real lines of the engine against them, so that a change of a line's format on one side
fails the native tests instead of silently turning off @on rules or the connection monitor.
"""
import os
import re

_ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "..")
_KOTLIN = os.path.join(_ROOT, "core", "src", "main", "kotlin", "io", "github", "zapretdroid", "core")


def _regexes(name):
    with open(os.path.join(_KOTLIN, name)) as f:
        src = f.read()
    return {k: re.compile(v) for k, v in re.findall(r'private val (\w+) = Regex\("""(.*?)"""\)', src)}


MONITOR = _regexes("ConnMonitor.kt")
EVENTS = _regexes("ScriptEvents.kt")

# Kind of engine line -> the expressions of the app that must read it.
_KINDS = [
    (re.compile(r"^(conn|udp) \d+: block detected"), [MONITOR["BLOCK"], EVENTS["BLOCK"]]),
    (re.compile(r"^conn \d+ closed: "), [MONITOR["CLOSED"], EVENTS["CLOSED"]]),
    (re.compile(r"^conn \d+: \S+ -> "), [MONITOR["CONN"]]),
    (re.compile(r"^udp \d+: -> \S+:\d+, "), [MONITOR["UDP_START"]]),
    (re.compile(r"^udp \d+: first reply after"), [MONITOR["UDP_REPLY"]]),
    (re.compile(r"^udp \d+ closed: "), [MONITOR["UDP_CLOSED"]]),
    (re.compile(r"^tcp \d+: connect to \S+ FAILED"), [MONITOR["TCP_FAILED"], EVENTS["CONNFAIL"]]),
]


def check(log):
    """Every engine line of a kind the app reads matches the app's expressions; their count."""
    checked = 0
    for line in log.splitlines():
        for kind, parsers in _KINDS:
            if kind.search(line):
                for p in parsers:
                    assert p.search(line), f"the app's {p.pattern!r} does not read the engine's line:\n{line}"
                checked += 1
    return checked
