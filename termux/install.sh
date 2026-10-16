#!/bin/sh
# zapret-termux: установка одной командой
#
# Termux + proot-distro (вы уже root):
#   sh -c "$(curl -fsSL https://raw.githubusercontent.com/k0kfjjfkdjf/zapret_android-port_claude/termux-zapret-udp-script/termux/install.sh)"
# Обычный Linux (Ubuntu, Debian, Arch, Alpine): та же команда, она сама перезапустится через sudo
#
# Переменные (необязательно):
#   ZT_BRANCH  ветка репозитория (по умолчанию termux-zapret-udp-script - ветка проекта zapret-termux)
#   ZT_REPO    адрес репозитория
#   ZT_DEST    куда клонировать (по умолчанию /opt/zapret-termux)
#
# Клонируется только папка termux/ репозитория. Дальше всё обновляется командой: zapret update

set -e

REPO="${ZT_REPO:-https://github.com/k0kfjjfkdjf/zapret_android-port_claude.git}"
BRANCH="${ZT_BRANCH:-termux-zapret-udp-script}"
DEST="${ZT_DEST:-/opt/zapret-termux}"

if [ "$(id -u)" -ne 0 ]; then
    # обычный Linux: перезапуск установщика от root через sudo
    if command -v sudo >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then
        case "$REPO" in
            https://github.com/*)
                raw=$(echo "$REPO" | sed -e 's#^https://github.com/#https://raw.githubusercontent.com/#' -e 's#\.git$##')
                echo "Нужен root, перезапуск через sudo..."
                curl -fsSL "$raw/$BRANCH/termux/install.sh" | sudo env ZT_REPO="$REPO" ZT_BRANCH="$BRANCH" ZT_DEST="$DEST" sh
                exit $? ;;
        esac
    fi
    echo "Нужен root. В proot-distro вы root по умолчанию (proot-distro login ubuntu)," >&2
    echo "на обычном Linux запустите установку через sudo." >&2
    exit 1
fi

need_pkgs=0
for c in git bash curl; do
    command -v "$c" >/dev/null 2>&1 || need_pkgs=1
done
if [ $need_pkgs -eq 1 ] || [ ! -f /etc/ssl/certs/ca-certificates.crt ]; then
    echo "Установка git, bash, curl, ca-certificates..."
    if command -v apt-get >/dev/null 2>&1; then
        apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y git bash curl ca-certificates
    elif command -v pacman >/dev/null 2>&1; then
        # без -Sy (частичное обновление Arch); пустая или старая база - полное -Syu
        pacman -S --needed --noconfirm git bash curl ca-certificates \
            || pacman -Syu --needed --noconfirm git bash curl ca-certificates
    elif command -v apk >/dev/null 2>&1; then
        apk add git bash curl ca-certificates
    else
        echo "Неизвестный пакетный менеджер. Установите git, bash, curl, ca-certificates и повторите." >&2
        exit 1
    fi
fi

if [ -d "$DEST/.git" ]; then
    echo "Обновление $DEST..."
    git -C "$DEST" pull --ff-only
else
    echo "Клонирование $REPO ${BRANCH:+(ветка $BRANCH) }в $DEST..."
    rm -rf "$DEST.new"
    if [ -n "$BRANCH" ]; then set -- -b "$BRANCH"; else set --; fi
    # только папка termux/ (sparse checkout); если git слишком старый - полный клон
    if git clone --depth 1 --filter=blob:none --sparse "$@" "$REPO" "$DEST.new" 2>/dev/null \
        && git -C "$DEST.new" sparse-checkout set termux; then
        :
    else
        rm -rf "$DEST.new"
        git clone --depth 1 "$@" "$REPO" "$DEST.new"
    fi
    if [ ! -f "$DEST.new/termux/zapret" ]; then
        echo "В этой ветке нет termux/zapret. Укажите ветку: ZT_BRANCH=имя_ветки" >&2
        rm -rf "$DEST.new"
        exit 1
    fi
    rm -rf "$DEST"
    mv "$DEST.new" "$DEST"
fi

chmod +x "$DEST/termux/zapret"
# install сам создаст /usr/local/bin/zapret -> $DEST/termux/zapret (в том числе вместо старого однофайлового скрипта)
exec bash "$DEST/termux/zapret" install
