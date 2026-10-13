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
        find "$HOSTLIST_DIR" -maxdepth 1 -type f -printf "%f\n" 2>/dev/null | sort
    else
        echo ""
    fi
}

zt_repo_dir() {
    git -C "$ZT_HOME" rev-parse --show-toplevel 2>/dev/null
}

zt_version() {
    local top
    top=$(zt_repo_dir) || { echo "не git-клон"; return; }
    echo "$(git -C "$top" rev-parse --abbrev-ref HEAD 2>/dev/null)@$(git -C "$top" log -1 --format='%h %cd' --date=short 2>/dev/null)"
}
