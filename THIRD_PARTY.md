# Third-party components

| Component | Where | License | Use |
|---|---|---|---|
| [ByeDPI](https://github.com/hufrea/byedpi) | `app/src/main/cpp/engine` (fork, see UPSTREAM.md) | MIT | DPI desync engine (local SOCKS5 proxy), extended with zapret features |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | `app/src/main/jni/hev-socks5-tunnel` (submodule) | MIT | TUN ↔ SOCKS5 (lwIP), with its own submodules: hev-task-system, lwip, yaml, hev-socks5-core |
| [zapret](https://github.com/bol-van/zapret) / [zapret2](https://github.com/bol-van/zapret2) | — | MIT | Design reference; tpws features and fake payloads are planned to be ported |
| [ByeByeDPI](https://github.com/romanvht/ByeByeDPI) | — | GPLv3 | Design reference for the Android integration (process model, VPN setup, default strategy) |
| Material icon "power_settings_new" | `app/src/main/res/drawable/ic_power.xml` | Apache 2.0 | Power button |
