# Engine

A fork of [ByeDPI](https://github.com/hufrea/byedpi) (MIT, see `LICENSE`), imported from
commit `ba532298de7b28cfe854aea83d061369d13ca290` (v0.17.3-38-gba53229). Windows-only files, Docker and packaging files were dropped.

Changes in this fork:

- `engine_reset()` restores global state so that `main()` can run several times in one
  process (the strategy tester starts the engine once per candidate).
- Fix: `--pf` port ranges compared ports in network byte order, so most ranges matched the
  wrong ports.
- zapret position markers in every position argument (`--split`, `--disorder`, `--oob`,
  `--disoob`, `--fake`, `--tlsrec`, `--fake-offset`; for UDP fakes `--fake-offset` takes
  only the number): `method`, `host`, `endhost`, `sld`,
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
- `--udp-proto-fake <proto>:<directory>` loads every file of the directory and sends them in
  turn, a different one for every fake. The app fills `quic_sni/` with QUIC v1 Initials built
  for random allowed SNIs (google, yandex, vk…) with random connection IDs, regenerated at
  every engine start, as zapret-termux's `--udp-fake-quic-sni` does inside tpws.
- `--mss <n>` (as tpws `--mss`): TCP_MAXSEG on outgoing connections, so the server also sends
  small segments (88 gives 76-byte segments on loopback). No root needed.
- `--tlsminor 0` is accepted (zapret-termux generates it; the value set flag already tells it from unset).
- Host lists (`-H`) for UDP and for TCP without SNI (`udphost.c`). Upstream checks host lists
  only on TCP data with a readable SNI or Host, so a UDP flow took the first group without
  regard to `-H` (community strategies with per-service `-H` groups put `--udp-fake` in the
  Discord group in vain). A UDP flow now gets a host name from its first datagram: the SNI of
  a QUIC Initial (v1 and v2, decrypted with the Initial keys of RFC 9001 5.2; when Chrome's
  ClientHello with Kyber continues in the next Initial, the group is chosen again on it), else
  the name the address was resolved from (kept from DNS answers relayed by the engine), else
  `discord.media` for Discord's IP discovery. Groups with `-H` take a UDP flow only when its
  host is known and listed. TCP without SNI or Host (other protocols) is matched by the DNS
  names too; a ClientHello with ECH has an outer SNI (`public_name`), which is what gets
  matched. An address keeps up to four names (CDNs). Only answers to queries relayed on the
  same association count, and with `--names-dns <ip[,ip]>` only those servers' answers (the
  app passes the VPN's DNS). Names are limited to `[a-z0-9._-]`. A QUIC Initial with another
  DCID than the first one is ignored (its keys would differ). After the group changes on the
  second Initial, `--udp-desync` counts from that datagram. `--conn-log` names the host and
  where it came from. Names from DNS need plain DNS: with Android's Private DNS (TLS) or a
  browser's DNS-over-HTTPS there are none.
- UDP associations are not `connect()`ed to their first destination: hev keeps one
  association per UDP socket of an app (full cone), and upstream sent every datagram of it to
  the first destination and dropped replies from others (WebRTC talks to STUN, TURN and media
  servers from one socket). Every datagram now goes to its own destination; every destination
  gets its own group and datagram count (up to 8 per association), statistics stay per
  association.
- `--dns-redirect <ip>=<ip>:<port>`: (addresses compared IPv4-mapped-aware: with a dual-stack bind
  address UDP destinations are ::ffff:a.b.c.d) UDP DNS to the first address (port 53) goes to the second
  (the app's DNS over HTTPS/TLS resolver), untouched by any group; its answers come back with
  the first address as their source and still name addresses for host lists. TCP connections
  to the first address are refused at once (Android's DNS over TLS probe falls back to UDP).
- `--apps <uid,...>`: the group applies only to connections of these Android apps. SOCKS5
  user/password authentication is accepted with any password; a user name of the form `ip:port`
  is the client's own source address (hev-socks5-tunnel with the user name `@src`, patched in
  `app/src/main/jni/hev-patch/`). The host's `owner_uid_hook` (JNI → getConnectionOwnerUid)
  names the uid once per connection or UDP association; `--conn-log` prints it.
- `--ipset`: IPv4-mapped destinations (dual-stack bind address) are matched as IPv4, so the
  IPv4 entries of a list apply on phones too.
- Upstream SOCKS5 of a group (`-C socks5://…`): the CONNECT carries the host name (from the
  client's first data, SNI or Host, else the DNS cache) instead of the address, so the proxy
  resolves it (Tor at the exit). UDP of a group with `-C` is dropped (the proxy carries TCP
  only; apps fall back from QUIC to TCP), unless the group has `--udp-proxy`: then each UDP
  association of the group sets up its own UDP ASSOCIATE with the proxy (non-blocking control
  connection; up to 16 datagrams wait for the relay address, a 0.0.0.0 relay means the proxy's
  own address), datagrams go to the relay with a SOCKS5 UDP header per destination and replies
  from the relay are unwrapped to their real source. Fragments and domain addresses in replies
  are dropped; if the proxy refuses or closes the association, the socket's UDP is dropped and
  the log says why (`udp N: proxy UDP failed: …`).
- Stop before listen: a stop requested while the engine still starts (large host lists) used
  to be lost; `engine_stop_requested` (set by the JNI layer) makes the engine return right
  after `listen`. Inside the app (`ZAPRETDROID_JNI`) no SIGINT/SIGTERM/SIGHUP handlers are
  installed: they outlived the engine in the app process.
- Refusing proxy: when the proxy of a `-C` group refuses a connection (not running), the
  engine logs `group N: proxy ADDR refuses connections` once and counts the repeats of the
  next 10 s (`still refuses connections (K more since the last line)`), instead of a
  `socks send`, a `connect … FAILED` and a `block detected` line for every connection.
