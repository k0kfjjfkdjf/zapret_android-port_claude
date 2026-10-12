# Third-party components

| Component | Where | License | Use |
|---|---|---|---|
| [ByeDPI](https://github.com/hufrea/byedpi) | `app/src/main/cpp/engine` (fork, see UPSTREAM.md) | MIT | DPI desync engine (local SOCKS5 proxy), extended with zapret features |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | `app/src/main/jni/hev-socks5-tunnel` (submodule) | MIT | TUN ↔ SOCKS5 (lwIP), with its own submodules: hev-task-system, lwip, yaml, hev-socks5-core |
| [zapret2](https://github.com/bol-van/zapret2) `files/fake` (commit a1adf7b) | `app/src/main/assets/fake` | MIT | Fake packet payloads (QUIC Initial, TLS ClientHello, Discord, STUN, …) |
| [zapret](https://github.com/bol-van/zapret) tpws | `app/src/main/cpp/engine` | MIT | Ported features (see engine/UPSTREAM.md) |
| [ByeByeDPI](https://github.com/romanvht/ByeByeDPI) | — | GPLv3 | Design reference for the Android integration (process model, VPN setup, default strategy) |
| Material icon "power_settings_new" | `app/src/main/res/drawable/ic_power.xml` | Apache 2.0 | Power button |
