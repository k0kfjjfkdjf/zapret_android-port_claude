# zapret-termux

Обход DPI на Android **без root** с помощью [zapret](https://github.com/bol-van/zapret) (tpws) в Termux + proot-distro.
Работает и для TCP (сайты, YouTube, Discord), и для **UDP** (QUIC/HTTP3, голос Discord, STUN), благодаря собственному патчу tpws.

> Это отдельный от Android-приложения ZapretDroid инструмент: он живёт в папке `termux/` и не зависит от остального репозитория.

## Содержание

- [Как это работает](#как-это-работает)
- [Что понадобится](#что-понадобится)
- [Установка](#установка)
- [Настройка SocksTun](#настройка-sockstun)
- [Команды](#команды)
- [TCP-стратегии](#tcp-стратегии)
- [UDP и профили](#udp-и-профили)
- [Подбор стратегии: blockcheck](#подбор-стратегии-blockcheck)
- [Отладка](#отладка)
- [Списки доменов](#списки-доменов)
- [Обновление](#обновление)
- [Если что-то не работает](#если-что-то-не-работает)
- [Ограничения](#ограничения)
- [Устройство проекта](#устройство-проекта)
- [Патч tpws](#патч-tpws)
- [Благодарности и лицензии](#благодарности-и-лицензии)

## Как это работает

```
Приложения (YouTube, Discord, браузер)
        │  весь их трафик
        ▼
VPN-клиент без root (SocksTun / hev-socks5-tunnel)
        │  SOCKS5: TCP CONNECT и UDP ASSOCIATE
        ▼
tpws на 127.0.0.1:1080  ── Termux + proot-distro (Ubuntu)
        │  TCP: разрезает ClientHello/HTTP-запрос (split, disorder, oob, tlsrec)
        │  UDP: перед первыми пакетами шлёт фейки с низким TTL
        ▼
Интернет (обычные сокеты, без root)
```

- **tpws** — прозрачный прокси из zapret. В режиме `--socks` он работает как обычный SOCKS-сервер, а права root ему не нужны.
- **VPN-клиент** перехватывает трафик выбранных приложений и отдаёт его в tpws. Сам Termux в VPN попадать не должен, иначе трафик tpws уйдёт по кругу.
- **UDP без root.** Разрезать UDP-датаграмму, как TCP-поток, нельзя: получатель увидит две испорченные датаграммы. Поэтому для UDP используется другой приём. Перед настоящими пакетами tpws отправляет фейковые с низким TTL (`setsockopt(IP_TTL)` доступен без привилегий). DPI видит фейк, а до сервера он не доходит.

## Что понадобится

- Android, root не нужен.
- [Termux](https://f-droid.org/packages/com.termux/) и в нём `proot-distro` с Ubuntu (Debian, Arch и Alpine тоже поддерживаются скриптом).
- VPN-клиент, который умеет SOCKS5 **с UDP ASSOCIATE** и исключение приложений. Проверен [SocksTun](https://f-droid.org/packages/hev.sockstun). Подойдут v2rayNG и NekoBox (Xray / sing-box). Клиенты на `tun2socks + udpgw` (например, SocksDroid) **не подойдут** для UDP.

## Установка

В Termux:

```sh
pkg install proot-distro
proot-distro install ubuntu
proot-distro login ubuntu
```

Внутри Ubuntu (вы root, sudo не нужен):

```sh
apt update && apt install -y curl
sh -c "$(curl -fsSL https://raw.githubusercontent.com/k0kfjjfkdjf/zapret_android-port_claude/claude/zapret-android-no-root-632xp0/termux/install.sh)"
zapret start
```

Установщик:

1. ставит `git`, `curl` и `ca-certificates`;
2. клонирует **только папку `termux/`** этого репозитория в `/opt/zapret-termux`;
3. запускает `zapret install`: скачивает zapret в `/opt/zapret`, накладывает патч, собирает tpws (1–3 минуты на телефоне), создаёт команду `zapret` (`/usr/local/bin/zapret` → `/opt/zapret-termux/termux/zapret`).

Переменные установщика:

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `ZT_BRANCH` | основная ветка репозитория | ставить из другой ветки |
| `ZT_REPO` | этот репозиторий | ставить из форка |
| `ZT_DEST` | `/opt/zapret-termux` | куда клонировать |

Пример установки из ветки:

```sh
ZT_BRANCH=termux-zapret-udp-script sh -c "$(curl -fsSL https://raw.githubusercontent.com/k0kfjjfkdjf/zapret_android-port_claude/termux-zapret-udp-script/termux/install.sh)"
```

Если раньше стоял старый однофайловый скрипт `zapret`, установщик заменит его. Настройки (`~/.zapret_*`) сохранятся.

**Автозапуск:** `zapret autostart on` добавит запуск в `~/.bashrc` внутри Ubuntu. zapret будет подниматься при входе в `proot-distro login ubuntu`.

## Настройка SocksTun

| Параметр | Значение |
|---|---|
| Socks Address / Port | `127.0.0.1` / `1080` (или ваш порт из `zapret status`) |
| UDP relay over TCP | **выключить**: это протокол hev-socks5-server, tpws его не понимает |
| Socks UDP Address | пусто |
| Remote DNS | можно включить: домены в UDP-пакетах tpws разрешит сам |
| Global | **выключить**. Режим per-App, отметить нужные приложения, **Termux не отмечать** |

В режиме Global SocksTun исключает из VPN только себя. Тогда исходящий трафик tpws из Termux снова попадает в VPN и уходит в петлю.

## Команды

`zapret` без аргументов открывает меню. Все действия доступны и командами:

| Команда | Что делает |
|---|---|
| `zapret start` / `stop` / `restart` | запуск и остановка |
| `zapret status` / `info` | состояние; `info` выдаёт машиночитаемый вид |
| `zapret cmd` | итоговая командная строка tpws |
| `zapret strategies` / `strategy <имя>` | список TCP-стратегий / выбрать стратегию |
| `zapret set-port <порт> [-y]` | сменить порт SOCKS (`-y` — сразу перезапустить) |
| `zapret udp on\|off` | UDP через SOCKS5 UDP ASSOCIATE |
| `zapret udp-profiles` / `udp-profile <имя>` | UDP-профили |
| `zapret udp-custom "<параметры>"` | свой UDP-профиль |
| `zapret hostlist [-y]` | включить или выключить hostlist |
| `zapret hostlist-list` / `hostlist-select <файл>` | списки доменов |
| `zapret lists-update` | скачать списки из zapret.cfgs |
| `zapret blockcheck …` | [подбор стратегии](#подбор-стратегии-blockcheck) |
| `zapret debug` | [отладочный запуск](#отладка) |
| `zapret autostart on\|off` | автозапуск через `~/.bashrc` |
| `zapret update` | [обновление](#обновление) |
| `zapret version` | версия zapret-termux (`ветка@коммит дата`) |
| `zapret install` / `remove` | переустановить или удалить zapret |

## TCP-стратегии

Стратегия — это набор параметров tpws, например `--filter-tcp=80,443 --split-pos=1,midsld --disorder`.
Стандартный набор лежит в [`data/strategies.txt`](data/strategies.txt) в формате `имя|параметры`. Ваши стратегии хранятся в `~/.zapret_strategies`. При обновлении новые стратегии из `data/strategies.txt` добавляются туда автоматически, ваши не перезаписываются.

Основные приёмы tpws:

| Параметр | Смысл |
|---|---|
| `--split-pos=1,midsld,sniext+1,…` | разрезать запрос на TCP-сегменты в этих местах (маркеры: `method`, `host`, `endhost`, `sld`, `midsld`, `endsld`, `sniext`) |
| `--disorder` | отправить вторую часть раньше первой |
| `--oob` | добавить out-of-band байт |
| `--tlsrec=sniext+1` | разбить ClientHello на две TLS-записи |
| `--mss=88` | заставить сервер отвечать мелкими сегментами (медленно, но иногда помогает) |
| `--hostcase`, `--methodeol` | трюки для HTTP |

Свою стратегию можно добавить через меню (пункт 5) или сохранить лучшую из `blockcheck`.

## UDP и профили

UDP нужен для QUIC (HTTP/3 у YouTube и Cloudflare), голоса Discord, звонков и STUN. Включается командой `zapret udp on`.

Профиль задаёт, к каким портам применять фейки и какие именно:

| Профиль | Порты | Фейки | Для чего |
|---|---|---|---|
| `discord` (по умолчанию) | 443, 3478, 19294–19344, 50000–65535 | по протоколу: QUIC, IP discovery Discord, STUN; TTL 4 ×6, первые 4 пакета | Discord, YouTube |
| `discord_quicfake` | те же | QUIC Initial google для всех протоколов, первые 3 пакета | как в стратегиях zapret.cfgs |
| `discord_low_ttl` | те же | TTL 2 ×8, первые 6 пакетов | если DPI близко |
| `discord_high_ttl` | те же | TTL 6 | если до DPI много хопов |
| `quic` | 443 | QUIC | только HTTP/3 |
| `all_ports` | все | по протоколу | звонки в других мессенджерах |
| `relay` | — | без фейков | только проксирование UDP |
| `custom` | свои | свои | `zapret udp-custom "..."` |

tpws сам определяет протокол по первому пакету потока (QUIC Initial, IP discovery Discord, STUN) и берёт для него свой фейк из `/opt/zapret/files/fake/`.

**Как подобрать TTL.** Фейк должен дойти до DPI провайдера, но не до сервера. Обычно подходит 3–6. Если не работает, попробуйте `discord_low_ttl` или `discord_high_ttl`.

## Подбор стратегии: blockcheck

```sh
zapret blockcheck                          # ваши стратегии, Discord + YouTube
zapret blockcheck full discord             # плюс ~70 сгенерированных комбинаций
zapret blockcheck list:discord             # домены из списка (после zapret lists-update)
zapret blockcheck tls13 example.com        # свой домен, только TLS 1.3
zapret blockcheck full discord --apply     # сразу применить лучшую
```

Как это работает:

1. Каждая стратегия запускается **во втором tpws на свободном порту**: основной zapret продолжает работать.
2. Через неё curl открывает адреса. HTTPS проверяется отдельно с **TLS 1.2 и TLS 1.3**: DPI часто режет только одну версию.
3. Сначала адреса проверяются без zapret, и показывается тип ошибки:
   - **таймаут или обрыв TLS** — типичный признак DPI, стратегии могут помочь;
   - **ошибка DNS или отклонённое соединение** — блокировка по DNS или IP, tpws её не обходит;
   - **подмена сертификата** — провайдер вмешивается в HTTPS.
4. В конце выводится топ-5 по числу открытых адресов и времени. Выбранную стратегию можно применить, сгенерированная сохраняется как `auto_N`.

Отчёт пишется в `~/zapret-blockcheck.log`.

## Отладка

**`zapret debug`** запускает tpws в терминале с логом `~/zapret-debug.log`. Повторите проблему (звонок, загрузку) и нажмите Ctrl+C. Вы получите сводку по UDP-потокам:

```
      1 -> 66.22.1.2:19300 proto=discord out=812 pkts/98304 bytes in=790 pkts/91200 bytes
      1 -> 1.2.3.4:3478 proto=stun out=4 pkts/80 bytes in=0 pkts/0 bytes  <-- NO REPLY
```

`NO REPLY` означает, что пакеты ушли, а ответа не было. Значит, их режут где-то после tpws.

**`zapret blockcheck debug …`** сохраняет для каждой стратегии лог tpws, подробный вывод curl по каждому адресу и сведения о системе и DNS. Всё упаковывается в `~/zapret-blockcheck-debug.tar.gz`, копия кладётся в `/sdcard/Download/`, если эта папка доступна. Этот архив удобно прикладывать к сообщению о проблеме. В нём есть IP-адреса, просмотрите его перед отправкой.

## Списки доменов

`zapret lists-update` скачивает [Snowy-Fluffy/zapret.cfgs](https://github.com/Snowy-Fluffy/zapret.cfgs) в `/opt/zapret/zapret.cfgs` и переводит его списки в формат tpws: `/opt/zapret/ipset/cfgs-*.txt`, где `*.` и `:*` убраны, а дубли удалены. Списки скачиваются с GitHub у вас на телефоне и в этот репозиторий не копируются.

```sh
zapret hostlist-select cfgs-discord.txt
zapret hostlist            # включить: стратегия применяется только к доменам из списка
```

## Обновление

```sh
zapret update        # или пункт 13 в меню
```

1. `git pull` самого zapret-termux. Если пришли изменения, показывается список коммитов, и `update` **перезапускается уже новой версией**.
2. Обновляется zapret (bol-van/zapret), прошлый патч откатывается.
3. Патч из `patches/` накладывается заново, tpws пересобирается.
4. Если zapret был запущен, он запускается снова. Настройки сохраняются.

Если вы ставили из ветки, а её потом слили и удалили, переустановите из основной ветки (см. [Установка](#установка)).

## Если что-то не работает

| Симптом | Что проверить |
|---|---|
| `404:: command not found` при установке через curl | скачалась страница 404: не та ветка или репозиторий приватный |
| Сайты не грузятся вообще | Termux исключён из VPN? Порт в SocksTun совпадает с `zapret status`? |
| Часть сайтов не грузится, YouTube работает | `zapret blockcheck` для этих доменов, смена стратегии |
| Сайт грузится «со скрипом» | браузер пробует QUIC: включите UDP или отключите QUIC в `chrome://flags` |
| UDP не работает совсем | в SocksTun выключен «UDP relay over TCP»? В `zapret debug` есть строки `udp flow`? |
| Голос Discord в одну сторону | профиль `discord`, затем `discord_low_ttl`; сводка `zapret debug` |
| После смены настроек ничего не изменилось | перезапустите VPN-клиент: он держит старые соединения |
| Ошибка сборки tpws | `zapret install` заново; при ошибке патча tpws соберётся без UDP |

## Ограничения

- **Нет фейкового TLS для TCP.** Самые сильные стратегии nfqws (fake ClientHello, `fooling=badseq`, `seqovl`) требуют сырых пакетов, то есть root. tpws умеет только разрезы и disorder. Без root фейки для TCP делает ByeDPI: он встроен в приложение ZapretDroid из этого репозитория.
- Блокировки по IP и DNS tpws не обходит, `blockcheck` их распознаёт.
- tpws не подключается к локальным и частным адресам: это встроенная защита.
- IPv6 в UDP-патче реализован, но на реальной сети не проверялся.

## Устройство проекта

```
termux/
├── install.sh                   установка одной командой
├── zapret                       точка входа: подключает модули и разбирает команды
├── lib/
│   ├── common.sh                пути, цвета, порт и hostlist
│   ├── udp.sh                   UDP-профили
│   ├── strategies.sh            TCP-стратегии, загрузка data/strategies.txt
│   ├── process.sh               запуск/остановка, статус, info, автозапуск
│   ├── config.sh                смена порта, hostlist
│   ├── build.sh                 зависимости, сборка tpws, патч, install/update/self-update
│   ├── lists.sh                 списки из zapret.cfgs
│   ├── blockcheck.sh            подбор стратегий
│   ├── debug.sh                 отладочный запуск
│   └── menu.sh                  меню и справка
├── patches/tpws-socks-udp.patch патч к bol-van/zapret
└── data/strategies.txt          стратегии по умолчанию
```

Файлы на телефоне:

| Путь | Что там |
|---|---|
| `/opt/zapret-termux` | клон этой папки (git) |
| `/opt/zapret` | zapret с наложенным патчем и собранным `tpws/tpws` |
| `/usr/local/bin/zapret` | ссылка на `termux/zapret` |
| `~/.zapret_*` | настройки: порт, стратегия, UDP, hostlist |
| `~/zapret-debug.log`, `~/zapret-blockcheck*.log` | логи |

## Патч tpws

[`patches/tpws-socks-udp.patch`](patches/tpws-socks-udp.patch) добавляет в tpws (режим `--socks`):

- **SOCKS5 UDP ASSOCIATE** (RFC 1928). Relay-сокет на адресе, по которому клиент подключился к tpws. Связка живёт, пока открыто TCP-соединение управления, или до таймаута простоя.
- **Фейки с низким TTL** перед первыми N датаграммами каждого потока. Отдельные фейки для QUIC Initial, IP discovery Discord и STUN.
- **Асинхронное разрешение доменов** в UDP-заголовках (ATYP=domain, режим Remote DNS в hev-socks5-tunnel). Работает через резолвер tpws, пакеты буферизуются до ответа DNS, результаты кэшируются на 5 минут.
- **Статистика по потокам** в логе (`--debug=1`): `udp flow start` / `udp flow end … NO REPLY`.

Новые опции tpws:

| Опция | По умолчанию | Смысл |
|---|---|---|
| `--socks-udp` | выкл. | включить UDP ASSOCIATE (нужен `--socks`) |
| `--udp-timeout=<сек>` | 60 | таймаут простоя связки, 0 — без таймаута |
| `--udp-fake-ttl=<ttl>` / `--udp-fake-ttl6=<hops>` | — | TTL фейков; включает UDP-десинхронизацию |
| `--udp-fake-repeats=<N>` | 1 | сколько фейков перед каждым пакетом |
| `--udp-desync-count=<N>` | 2 | к скольким первым пакетам потока применять |
| `--udp-desync-ports=<список>` | все | порты назначения, например `443,50000-65535` |
| `--udp-fake=<файл>` | 64 нулевых байта | фейк для неизвестного UDP |
| `--udp-fake-quic=<файл>` / `--udp-fake-discord=<файл>` / `--udp-fake-stun=<файл>` | `--udp-fake` | фейки по протоколу |

Разработка патча: склонируйте bol-van/zapret, примените патч (`git apply`), внесите изменения в `tpws/` и пересоздайте его командой `git add -N tpws/udp.c tpws/udp.h && git diff -- 'tpws/*.c' 'tpws/*.h' > tpws-socks-udp.patch`.

## Благодарности и лицензии

- [bol-van/zapret](https://github.com/bol-van/zapret) — tpws и фейковые пакеты (`files/fake/`), MIT. Патч распространяется на тех же условиях, что и tpws.
- [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) / [SocksTun](https://github.com/heiher/sockstun) — VPN-клиент.
- [Snowy-Fluffy/zapret.cfgs](https://github.com/Snowy-Fluffy/zapret.cfgs) — списки доменов и идеи для стратегий (скачиваются на устройстве, не входят в репозиторий).
- [Snowy-Fluffy/zapret.installer](https://github.com/Snowy-Fluffy/zapret.installer) — идея проверки стратегий по спискам и TLS 1.2/1.3.
