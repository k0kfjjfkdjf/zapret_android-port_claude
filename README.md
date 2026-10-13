# zapret-termux

**Обход DPI на Android без root: [zapret](https://github.com/bol-van/zapret) (tpws) в Termux + proot-distro, с поддержкой UDP.**

> Это ветка `termux-zapret-udp-script`: отдельный проект в репозитории.
> Android-приложение **ZapretDroid** (VpnService + ByeDPI) находится в [основной ветке](https://github.com/k0kfjjfkdjf/zapret_android-port_claude).
> Это два разных подхода к одной задаче.

| | zapret-termux (эта ветка) | ZapretDroid (основная ветка) |
|---|---|---|
| Что это | скрипты + tpws в Termux/proot | готовое Android-приложение |
| Движок | tpws из zapret с UDP-патчем | ByeDPI + hev-socks5-tunnel |
| Перехват трафика | внешний VPN-клиент (SocksTun) → SOCKS5 | встроенный VpnService |
| TCP | разрезы, disorder, oob, tlsrec | плюс фейковые пакеты |
| UDP | фейки с низким TTL: QUIC, голос Discord, STUN | фейки для QUIC |
| Подбор стратегий | `zapret blockcheck` в терминале | в приложении |

## Возможности

- **UDP через SOCKS5 UDP ASSOCIATE**: QUIC/HTTP3, голос Discord, звонки, STUN. Фейковые датаграммы с низким TTL без root, по протоколу (QUIC Initial, IP discovery Discord, STUN).
- **`zapret blockcheck`**: подбор TCP-стратегии в терминале. TLS 1.2 и 1.3 проверяются отдельно, есть проверка большого файла против «заморозки» на 16–20 КБ и режим отладки с архивом логов.
- **`zapret debug`**: лог по этапам (время до первого ответа, повторы IP discovery Discord, время TCP-подключений) и итоговый отчёт.
- **Профили UDP** для Discord и YouTube, **28 TCP-стратегий**, списки доменов из [zapret.cfgs](https://github.com/Snowy-Fluffy/zapret.cfgs).
- **Установка одной командой**; `zapret update` обновляет скрипты из этой ветки, zapret и пересобирает tpws.

## Быстрый старт

В Termux:

```sh
pkg install proot-distro
proot-distro install ubuntu
proot-distro login ubuntu
```

Внутри Ubuntu:

```sh
apt update && apt install -y curl
sh -c "$(curl -fsSL https://raw.githubusercontent.com/k0kfjjfkdjf/zapret_android-port_claude/termux-zapret-udp-script/termux/install.sh)"
zapret start
```

Затем настройте VPN-клиент [SocksTun](https://f-droid.org/packages/hev.sockstun): адрес `127.0.0.1:1080`, «UDP relay over TCP» выключить, режим per-App **без Termux**.

## Документация

Полное руководство: **[termux/README.md](termux/README.md)**. Там установка, настройка SocksTun, все команды, TCP-стратегии и UDP-профили, blockcheck, отладка, обновление, решение проблем, устройство проекта и описание патча tpws.

## Структура

```
termux/
├── install.sh                   установка одной командой
├── zapret                       точка входа
├── lib/                         модули
├── patches/tpws-socks-udp.patch патч tpws (SOCKS5 UDP ASSOCIATE)
├── data/strategies.txt          TCP-стратегии
└── README.md                    документация
```

## Благодарности

[bol-van/zapret](https://github.com/bol-van/zapret) · [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) / [SocksTun](https://github.com/heiher/sockstun) · [Snowy-Fluffy/zapret.cfgs](https://github.com/Snowy-Fluffy/zapret.cfgs) · [Snowy-Fluffy/zapret.installer](https://github.com/Snowy-Fluffy/zapret.installer)
