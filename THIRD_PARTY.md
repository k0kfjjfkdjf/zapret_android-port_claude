# Third-party components

| Component | Where | License | Use |
|---|---|---|---|
| [ByeDPI](https://github.com/hufrea/byedpi) | `app/src/main/cpp/engine` (fork, see UPSTREAM.md) | MIT | DPI desync engine (local SOCKS5 proxy), extended with zapret features |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | `app/src/main/jni/hev-socks5-tunnel` (submodule) | MIT | TUN ↔ SOCKS5 (lwIP), with its own submodules: hev-task-system, lwip, yaml, hev-socks5-core. `src/hev-socks5-session.c` is replaced at build time by `app/src/main/jni/hev-patch/hev-socks5-session.c` (source address as the SOCKS5 user name, for routing by app) |
| [zapret2](https://github.com/bol-van/zapret2) `files/fake` (commit a1adf7b) | `app/src/main/assets/fake` | MIT | Fake packet payloads (QUIC Initial, TLS ClientHello, Discord, STUN, …) |
| [zapret](https://github.com/bol-van/zapret) tpws | `app/src/main/cpp/engine` | MIT | Ported features (see engine/UPSTREAM.md) |
| [ByeByeDPI](https://github.com/romanvht/ByeByeDPI) (commit 96a3c1f) | `app/src/main/assets/strategies/byebyedpi.list`, `app/src/main/assets/sites/*.sites` | GPLv3 | Community strategy list and site lists for the strategy tester; design reference for the Android integration |
| [ByeByeDPI](https://github.com/romanvht/ByeByeDPI) 1.8.2 (commit 4ca350c) | `app/src/main/java/io/github/zapretdroid/classic/`, `app/src/main/res/*/bb_*`, `app/src/main/assets/classic/` | GPLv3 | The ByeByeDPI interface: its activities, settings screens, proxy test and resources, adapted to run on ZapretDroid's VPN service and engine (see `classic/ClassicBridge.kt`) |
| [TweetNaCl](https://tweetnacl.cr.yp.to/) (Bernstein, Lange, Schwabe et al.) | `app/src/main/java/io/github/zapretdroid/core/crypto/NaCl.kt` (X25519 ported to Kotlin) | Public domain | DNSCrypt key exchange |
| [dnscrypt-resolvers](https://github.com/DNSCrypt/dnscrypt-resolvers) `v3/public-resolvers.md` | downloaded at run time (`dnscrypt/`), checked with its minisign key | see the repository (list maintained by Frank Denis) | DNSCrypt and DoH server list |
| [InviZible Pro](https://github.com/Gedsh/InviZible) | design reference only, no code copied | GPLv3 | Server search and filters (DNSCrypt servers screen) |
| [tor-android](https://github.com/guardianproject/tor-android) 0.4.9.14 (Maven Central) | dependency of the Tor module (`tor/`) | BSD-3-Clause (Tor: BSD-3-Clause) | Tor as a library with TorService |
| [IPtProxy](https://github.com/tladesignz/IPtProxy) 5.7.0 (Maven Central) | dependency of the Tor module (`tor/`) | MIT (Lyrebird, Snowflake: BSD-3-Clause) | Bridge transports: obfs4, meek_lite, webtunnel, snowflake |
| Tor Browser default bridges (via InviZible Pro's `bridges_default.lst`) | `tor/src/main/assets/bridges_default.txt` | public bridge lines | Built-in bridges of the Tor module; Snowflake rendezvous and STUN defaults as in InviZible Pro (GPLv3) |
| [HaGeZi DNS blocklists](https://github.com/hagezi/dns-blocklists) (Multi NORMAL, Threat Intelligence mini, NSFW) | downloaded at run time into the app's files (`dnsfilter/`), not in the repository | GPLv3 | DNS blocking of ads and trackers, malware and phishing, adult sites |
| Material icon "power_settings_new" | `app/src/main/res/drawable/ic_power.xml` | Apache 2.0 | Power button |
