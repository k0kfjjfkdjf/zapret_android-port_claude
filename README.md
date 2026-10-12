# ZapretDroid

Обход DPI на Android **без root**: обычное приложение, которое работает через системный VPN
(`VpnService`). Трафик никуда не уходит — «VPN» локальный, он нужен только чтобы перехватить
соединения и применить к ним техники обхода в духе [zapret](https://github.com/bol-van/zapret).

*English summary below.*

## Как это устроено

```
Приложения ─► VpnService (TUN) ─► hev-socks5-tunnel ─► ByeDPI (SOCKS5 на 127.0.0.1)
                                                        └─► сеть через обычные сокеты
```

- **[ByeDPI](https://github.com/hufrea/byedpi)** — движок десинхронизации: split, disorder, OOB,
  разбиение TLS-записей, фейковые пакеты с низким TTL (через `vmsplice`/`splice`, root не нужен),
  фейковые UDP-датаграммы для QUIC, автоматическое переключение стратегий при сбоях.
- **[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)** — переводит пакеты из TUN
  в SOCKS5-соединения (TCP и UDP).
- Само приложение исключено из VPN, поэтому сокеты движка идут в сеть напрямую.
- VPN-сервис работает в отдельном процессе `:engine`, который завершается после каждой сессии:
  у обеих нативных библиотек глобальное состояние.

### Почему не nfqws

nfqws/nfqws2 из zapret работают с сырыми пакетами через NFQUEUE — это требует root. Без root
доступны только техники уровня сокетов (то, что умеет tpws и ByeDPI). Поэтому часть стратегий
zapret на Android без root не воспроизвести.

## Возможности (v0.1)

- Включение одной кнопкой, плитка в шторке, уведомление с кнопкой остановки.
- Готовые стратегии и своя командная строка ByeDPI.
- DNS-сервер, IPv6.
- Раздельное туннелирование: все приложения / только выбранные / все, кроме выбранных.
- Поддержка «Постоянного VPN» Android (автозапуск при загрузке:
  *Настройки → Сеть → VPN → ZapretDroid → Постоянная VPN*).
- Интерфейс на русском и английском, Material You.
- Android 7.0+ (API 24).

## План

- [ ] Перенос функций tpws в движок: модификации HTTP (hostcase, hostdot, hostpad, methodeol…),
      маркеры позиций (sniext, midsld…), autohostlist, хостлисты (в т.ч. gzip), профили.
- [ ] Fake-нагрузки из zapret2 (QUIC Initial, Discord, STUN…) для `--fake-data`.
- [ ] Встроенный подбор стратегии (аналог `blockcheck`) по списку сайтов.
- [ ] Асинхронный резолвер в движке и mapped DNS (имена доменов вместо IP в SOCKS).
- [ ] Импорт/экспорт настроек, логи в приложении.

## Сборка

```sh
git clone --recursive https://github.com/k0kfjjfkdjf/zapret_android-port_claude.git
cd zapret_android-port_claude
./gradlew assembleDebug
```

Нужны JDK 17, Android SDK (platform 36), NDK 27.2.12479018 и CMake 3.22.1.
APK для всех ABI и универсальный APK появятся в `app/build/outputs/apk/debug/`.
CI (GitHub Actions) собирает их на каждый push — смотрите артефакты сборки.

Подписанный релиз: задайте переменные `ZAPRETDROID_KEYSTORE`, `ZAPRETDROID_KEYSTORE_PASSWORD`,
`ZAPRETDROID_KEY_ALIAS`, `ZAPRETDROID_KEY_PASSWORD` и запустите `./gradlew assembleRelease`.

## English

ZapretDroid is a rootless DPI bypass for Android. It runs a local VPN (`VpnService`) that feeds
traffic through [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) into the
[ByeDPI](https://github.com/hufrea/byedpi) desync engine; nothing leaves the device through a
remote server. Packet-level zapret techniques (nfqws) require root and are out of scope; socket-level
techniques from tpws are planned to be ported into the engine. Build with `./gradlew assembleDebug`
after a recursive clone (JDK 17, SDK 36, NDK 27.2.12479018, CMake 3.22.1).

## Лицензия

GPLv3, см. [LICENSE](LICENSE). Сторонние компоненты — в [THIRD_PARTY.md](THIRD_PARTY.md).
