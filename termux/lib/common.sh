# shellcheck shell=bash
# Модуль zapret-termux: общие переменные и вспомогательные функции. Подключается из termux/zapret, отдельно не запускается.

# ZT_HOME (папка termux/ в клоне репозитория) задаёт точка входа termux/zapret
ZT_PATCH="$ZT_HOME/patches/tpws-socks-udp.patch"
ZT_STRATEGIES_DATA="$ZT_HOME/data/strategies.txt"
ZAPRET_DIR="/opt/zapret"
TPWS_BIN="$ZAPRET_DIR/tpws/tpws"
STRATEGY_FILE="$HOME/.zapret_strategy"
STRATEGIES_FILE="$HOME/.zapret_strategies"
BASHRC="$HOME/.bashrc"
SCRIPT_PATH="/usr/local/bin/zapret"

PORT_FILE="$HOME/.zapret_port"
HOSTLIST_FILE="$HOME/.zapret_use_hostlist"
HOSTLIST_SELECT_FILE="$HOME/.zapret_selected_hostlist"
HOSTLIST_DIR="$ZAPRET_DIR/ipset"

UDP_FILE="$HOME/.zapret_use_udp"
UDP_PROFILE_FILE="$HOME/.zapret_udp_profile"
UDP_CUSTOM_FILE="$HOME/.zapret_udp_custom"
UDP_SNI_FILE="$HOME/.zapret_udp_sni"          # off/пусто, default, путь к файлу или домены через запятую
ZT_QUIC_SNI_DEFAULT="$ZT_HOME/data/quic-sni.txt"
FAKE_DIR="$ZAPRET_DIR/files/fake"
ZAPRET_REPO="https://github.com/bol-van/zapret.git"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

declare -A STRATEGIES
declare -a STRATEGY_NAMES
INTERACTIVE=0   # 1 — меню, 0 — CLI (без вопросов)

# -------------------------------------------------------------------
# Вспомогательные функции
# -------------------------------------------------------------------
press_any_key() {
    echo ""
    read -n 1 -s -r -p "Нажмите любую клавишу для продолжения..."
    echo ""
}

run_with_sudo() {
    if [[ $EUID -eq 0 ]]; then
        "$@"
    elif command -v sudo &>/dev/null; then
        sudo "$@"
    else
        echo -e "${RED}Ошибка: требуется sudo, но он не установлен.${NC}"
        return 1
    fi
}

can_write_to_dir() {
    local dir="$1"
    if [[ -d "$dir" && -w "$dir" ]] || [[ ! -d "$dir" && -w "$(dirname "$dir")" ]]; then
        return 0
    else
        return 1
    fi
}

is_zapret_installed() {
    [[ -f "$TPWS_BIN" ]]
}

# -------------------------------------------------------------------
# Настройки порта и hostlist
# -------------------------------------------------------------------
get_current_port() {
    if [[ -f "$PORT_FILE" ]]; then
        cat "$PORT_FILE"
    else
        echo "1080" > "$PORT_FILE"
        echo "1080"
    fi
}

set_port() {
    echo "$1" > "$PORT_FILE"
}

get_use_hostlist() {
    if [[ -f "$HOSTLIST_FILE" ]]; then
        cat "$HOSTLIST_FILE"
    else
        echo "0" > "$HOSTLIST_FILE"
        echo "0"
    fi
}

set_use_hostlist() {
    echo "$1" > "$HOSTLIST_FILE"
}

get_selected_hostlist() {
    if [[ -f "$HOSTLIST_SELECT_FILE" ]]; then
        cat "$HOSTLIST_SELECT_FILE"
    else
        echo ""
    fi
}

set_selected_hostlist() {
    echo "$1" > "$HOSTLIST_SELECT_FILE"
}

get_hostlist_files() {
    if [[ -d "$HOSTLIST_DIR" ]]; then
        # без find -printf: его нет в busybox (Alpine)
        local f
        for f in "$HOSTLIST_DIR"/*; do
            [[ -f "$f" ]] && echo "${f##*/}"
        done | sort
    else
        echo ""
    fi
}

zt_repo_dir() {
    git -C "$ZT_HOME" rev-parse --show-toplevel 2>/dev/null
}

# где работаем: proot (Termux + proot-distro) или обычный linux.
# proot перехватывает системные вызовы через ptrace, поэтому у процесса есть трассировщик
zt_platform() {
    local tp
    tp=$(awk '/^TracerPid:/{print $2}' /proc/self/status 2>/dev/null)
    if [[ -n "$tp" && "$tp" != "0" ]] || [[ -d /data/data/com.termux || -f /system/build.prop ]] || uname -r | grep -qi android; then
        echo "proot"
    else
        echo "linux"
    fi
}

# tpws, запущенный от root, сбрасывает права на uid/gid 2147483647. В rootless-контейнере
# (distrobox/podman/docker без root) такого uid нет в user namespace, и tpws падает
# ("setgroups: Invalid argument"). Тогда сбрасываем права на nobody (65534), если он отображён.
zt_uid_opt() {
    [[ $EUID -eq 0 && -r /proc/self/uid_map ]] || return 0
    local f
    for f in /proc/self/uid_map /proc/self/gid_map; do
        awk -v t=2147483647 '{ if (t >= $1 && t < $1 + $3) ok=1 } END { exit !ok }' "$f" || break
        [[ "$f" == */gid_map ]] && return 0   # оба отображены - обычная система
    done
    awk -v t=65534 '{ if (t >= $1 && t < $1 + $3) ok=1 } END { exit !ok }' /proc/self/uid_map \
        && awk -v t=65534 '{ if (t >= $1 && t < $1 + $3) ok=1 } END { exit !ok }' /proc/self/gid_map \
        && echo "--uid=65534:65534"
}
ZT_UIDOPT="$(zt_uid_opt)"

# автозапуск через systemd: на обычном linux с systemd, где мы root
ZT_UNIT="/etc/systemd/system/zapret-termux.service"
zt_use_systemd() {
    [[ "$(zt_platform)" == "linux" && -d /run/systemd/system ]] && command -v systemctl &>/dev/null
}

zt_autostart_on() {
    if zt_use_systemd; then
        systemctl is-enabled zapret-termux &>/dev/null
    else
        grep -q "zapret start" "$BASHRC" 2>/dev/null
    fi
}

# -------------------------------------------------------------------
# Основной процесс tpws: определяется по pid-файлу, а не по pgrep "tpws.*--socks",
# который ловил и временные tpws из blockcheck
# -------------------------------------------------------------------
ZT_PIDFILE="$HOME/.zapret.pid"

# PID основного tpws или пусто. Без pid-файла (процесс запущен старой версией скрипта)
# ищем tpws на текущем порту: у blockcheck порты свои (1090-1190)
zapret_pid() {
    local pid=""
    if [[ -f "$ZT_PIDFILE" ]]; then
        pid=$(tr -cd '0-9' < "$ZT_PIDFILE" 2>/dev/null)
        if [[ -n "$pid" ]] && zt_is_main_tpws "$pid" ""; then
            echo "$pid"; return 0
        fi
    fi
    local port
    port=$(get_current_port)
    for pid in $(pgrep -f -- "--port=$port --bind-addr=127.0.0.1 --socks" 2>/dev/null); do
        zt_is_main_tpws "$pid" "$port" && { echo "$pid"; return 0; }
    done
    return 1
}

# это процесс tpws (argv[0], а не оболочка, в командной строке которой встречается "tpws"),
# в режиме --socks и, если задан $2, на этом порту
zt_is_main_tpws() {
    local pid="$1" port="$2"
    local -a argv
    kill -0 "$pid" 2>/dev/null || return 1
    mapfile -d '' -t argv < "/proc/$pid/cmdline" 2>/dev/null || return 1
    [[ "${argv[0]##*/}" == tpws ]] || return 1
    [[ " ${argv[*]} " == *" --socks "* ]] || return 1
    [[ -z "$port" || "${argv[1]}" == "--port=$port" ]]
}

zapret_running() {
    zapret_pid >/dev/null
}

# работает ли zapret как служба systemd
zt_service_active() {
    zt_use_systemd && systemctl is-active -q zapret-termux 2>/dev/null
}

# остановить zapret на время (update, debug, collect). Печатает состояние для zt_resume:
# 0 - не работал, 1 - запущен вручную, 2 - служба systemd
zt_pause() {
    if zt_service_active; then
        run_with_sudo systemctl stop zapret-termux &>/dev/null
        echo 2
    elif zapret_running; then
        stop_zapret &>/dev/null
        echo 1
    else
        echo 0
    fi
}

zt_resume() {
    case "$1" in
        2) run_with_sudo systemctl start zapret-termux && echo -e "${GREEN}Служба zapret-termux снова запущена.${NC}" ;;
        1) start_zapret ;;
    esac
}

zt_version() {
    local top
    top=$(zt_repo_dir) || { echo "не git-клон"; return; }
    echo "$(git -C "$top" rev-parse --abbrev-ref HEAD 2>/dev/null)@$(git -C "$top" log -1 --format='%h %cd' --date=short 2>/dev/null)"
}
