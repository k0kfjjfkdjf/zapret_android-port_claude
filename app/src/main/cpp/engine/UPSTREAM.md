# Engine

A fork of [ByeDPI](https://github.com/hufrea/byedpi) (MIT, see `LICENSE`), imported from
commit `ba532298de7b28cfe854aea83d061369d13ca290` (v0.17.3-38-gba53229). Windows-only files, Docker and packaging files were dropped.

Changes in this fork:

- `engine_reset()` restores global state so that `main()` can run several times in one
  process (the strategy tester starts the engine once per candidate).
- Fix: `--pf` port ranges compared ports in network byte order, so most ranges matched the
  wrong ports.
- zapret position markers in every position argument (`--split`, `--disorder`, `--oob`,
  `--disoob`, `--fake`, `--tlsrec`, `--fake-offset`): `method`, `host`, `endhost`, `sld`,
  `midsld`, `endsld`, `sniext`, with an optional `+N`/`-N` offset (`tpws.c`, ported from
  zapret `tpws/protocol.c`).
- tpws HTTP modifications as `-M` tokens: `hostcase`, `hostspell=XXXX`, `hostdot`, `hosttab`,
  `hostnospace`, `hostpad=N`, `methodspace`, `methodeol`, `unixeol` (ported from
  `tpws/tamper.c`, same order of operations). The original `h`, `d`, `r` flags still work.
- Host tests: `app/src/test/native/run.sh`.
- autohostlist as a debugging aid: `--autohost-log <file>` appends `<time> <host> <reason>`
  for every detected block (torst, redirect, ssl_err, connect). Detection needs an `--auto`
  group; the app adds an empty one at the end when the feature is on.
- Diagnostics go to `engine_log` (stderr by default) instead of stderr directly, and option
  errors are reported there with the offending argument; the app captures them without
  touching the process stdio descriptors.
- `--conn-log`: one line when a connection is set up (host, destination, protocol, group),
  one when it closes (bytes up/down, rounds, duration), one per detected block with the
  group switch, and one per UDP association.
- UDP, ideas from the tpws SOCKS5 UDP patch developed alongside this app: the protocol of a
  flow is recognised on its first datagram (QUIC Initial, Discord IP discovery, STUN) and
  `--udp-proto-fake <quic|discord|stun>:<file>` picks a fake of that protocol;
  `--udp-desync N[:K]` sends fakes before the first N datagrams of a flow and then before
  every K-th; with `--conn-log`, every UDP flow reports datagrams and bytes both ways, fakes,
  the largest gaps, stalls (inbound gaps over 300 ms) and NO REPLY, every 10 s and on close.
- Connection timing with `--conn-log`, after the tpws patch's timing diagnostics: the first
  reply of every UDP flow (time and how many datagrams and fakes went out before it), Discord
  IP discovery repeated without a reply (`RETRY #n after N ms`, the cause of a long
  "Connecting…"), TCP connections that fail (with the error) or take over a second.
