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

## Возможности (v0.5)

- Включение одной кнопкой, плитка в шторке, уведомление с кнопкой остановки.
- Готовые стратегии и своя командная строка движка.
- **Из zapret:**
  - фейковые нагрузки из zapret2 (QUIC Initial, TLS ClientHello, Discord, STUN…) —
    `--fake-data {fake:имя}`, кнопка «Вставить фейковую нагрузку», пресеты «TCP + QUIC/UDP»
    и «Discord и звонки»;
  - подбор стратегии в духе `blockcheck`: перебор стратегий на выбранных сайтах (в том числе
    59 стратегий сообщества ByeByeDPI и списки сайтов: видеосерверы YouTube, Telegram,
    Discord, соцсети, Cloudflare), по каждому сайту — этап, на котором случился сбой, тайминги,
    статус HTTP, обрезка или зависание ответа, редирект на страницу блокировки; результат
    применяется одним нажатием;
  - маркеры позиций tpws: `method`, `host`, `endhost`, `sld`, `midsld`, `endsld`, `sniext`
    (`--split midsld`, `--tlsrec sniext+1`…);
  - модификации HTTP из tpws в `-M`: `hostcase`, `hostspell=`, `hostdot`, `hosttab`,
    `hostnospace`, `hostpad=N`, `methodspace`, `methodeol`, `unixeol`;
  - списки хостов в формате zapret (импорт, gzip, загрузка по ссылке) и область применения:
    все сайты / только список / все, кроме списка; `{list:имя}` в аргументах;
  - autohostlist как отладочная функция: запись доменов, где блокировка видна даже со
    стратегией, и добавление их в список.
- Журнал всего приложения в одном файле: действия в интерфейсе, изменения настроек, сессии
  VPN, смены сети, подбор стратегии, вывод движка и туннеля, точная команда запуска, ошибки
  со стеком и падения. Источник каждой строки (`ui`, `vpn`, `test`, `byedpi`, `hev`), фильтры,
  отправка/сохранение; старые записи обрезаются, остаются последние ~2 МБ.
- DNS-сервер, IPv6, раздельное туннелирование по приложениям.
- Поддержка «Постоянного VPN» Android (автозапуск при загрузке:
  *Настройки → Сеть → VPN → ZapretDroid → Постоянная VPN*).
- Интерфейс на русском и английском, Material You. Android 7.0+ (API 24).

Движок — форк ByeDPI в `app/src/main/cpp/engine` (изменения перечислены в
[UPSTREAM.md](app/src/main/cpp/engine/UPSTREAM.md)).

## План

- [ ] Проверка QUIC/UDP в подборе стратегии.
- [ ] Асинхронный резолвер в движке и mapped DNS (имена доменов вместо IP в SOCKS).
- [ ] Импорт/экспорт настроек.

## Тесты

- `app/src/test/native/run.sh` — нативные тесты движка на хосте (ASan/UBSan) и проверка, что
  каждый пресет и кандидат подбора принимается движком.
- `./gradlew testDebugUnitTest` — unit-тесты Kotlin.

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
remote server. Packet-level zapret techniques (nfqws) require root and are out of scope; tpws position
markers and HTTP modifications, zapret2 fake payloads, zapret-style host lists, autohostlist
and a blockcheck-like strategy finder are included. Build with `./gradlew assembleDebug`
after a recursive clone (JDK 17, SDK 36, NDK 27.2.12479018, CMake 3.22.1).

## Лицензия

GPLv3, см. [LICENSE](LICENSE). Сторонние компоненты — в [THIRD_PARTY.md](THIRD_PARTY.md).
