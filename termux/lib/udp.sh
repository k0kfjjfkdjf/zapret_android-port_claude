# shellcheck shell=bash
# Модуль zapret-termux: UDP (SOCKS5 UDP ASSOCIATE). Подключается из termux/zapret, отдельно не запускается.

# -------------------------------------------------------------------
# UDP (SOCKS5 UDP ASSOCIATE, нужен патченый tpws)
# -------------------------------------------------------------------
declare -A UDP_PROFILES
declare -a UDP_PROFILE_NAMES

init_udp_profiles() {
    local q="--udp-fake-quic=$FAKE_DIR/quic_initial_www_google_com.bin"
    local fk="$q --udp-fake-discord=$FAKE_DIR/discord-ip-discovery-with-port.bin --udp-fake-stun=$FAKE_DIR/stun.bin"
    # порты голоса Discord: 19294-19344 и 50000-65535, 3478 - STUN
    local dports="443,3478,19294-19344,50000-65535"
    UDP_PROFILES=(
        ["relay"]=""
        ["discord"]="--udp-desync-ports=$dports --udp-fake-ttl=4 --udp-fake-repeats=6 --udp-desync-count=4 $fk"
        ["discord_low_ttl"]="--udp-desync-ports=$dports --udp-fake-ttl=2 --udp-fake-repeats=8 --udp-desync-count=6 $fk"
        ["discord_quicfake"]="--udp-desync-ports=443,3478,19294-19344,50000-65535 --udp-fake-ttl=4 --udp-fake-repeats=6 --udp-desync-count=3 --udp-fake=$FAKE_DIR/quic_initial_www_google_com.bin $q --udp-fake-discord=$FAKE_DIR/quic_initial_www_google_com.bin --udp-fake-stun=$FAKE_DIR/quic_initial_www_google_com.bin"
        ["discord_high_ttl"]="--udp-desync-ports=$dports --udp-fake-ttl=6 --udp-fake-repeats=6 --udp-desync-count=4 $fk"
        ["quic"]="--udp-desync-ports=443 --udp-fake-ttl=4 --udp-fake-repeats=6 --udp-desync-count=2 $q"
        ["all_ports"]="--udp-fake-ttl=4 --udp-fake-repeats=6 --udp-desync-count=4 $fk"
    )
    UDP_PROFILE_NAMES=("discord" "discord_quicfake" "discord_low_ttl" "discord_high_ttl" "quic" "all_ports" "relay")
    if [[ -f "$UDP_CUSTOM_FILE" ]] && [[ -n "$(cat "$UDP_CUSTOM_FILE")" ]]; then
        UDP_PROFILES["custom"]="$(cat "$UDP_CUSTOM_FILE")"
        UDP_PROFILE_NAMES+=("custom")
    fi
}

get_use_udp() {
    if [[ -f "$UDP_FILE" ]]; then
        cat "$UDP_FILE"
    else
        echo "0"
    fi
}

set_use_udp() {
    echo "$1" > "$UDP_FILE"
}

get_udp_profile() {
    local p="discord"
    [[ -f "$UDP_PROFILE_FILE" ]] && p=$(cat "$UDP_PROFILE_FILE")
    [[ "$p" == "quic_discord" || "$p" == "low_ttl" ]] && p="discord"   # профили старой версии
    [[ -z "${UDP_PROFILES[$p]+x}" ]] && p="discord"
    echo "$p"
}

tpws_supports_udp() {
    [[ -x "$TPWS_BIN" ]] && "$TPWS_BIN" --help 2>&1 | grep -q -- "--socks-udp"
}

restart_if_running() {
    if pgrep -f "tpws.*--socks" > /dev/null; then
        restart_zapret
    fi
}

toggle_udp() {
    if [[ "$(get_use_udp)" == "1" ]]; then
        set_use_udp "0"
        echo -e "${GREEN}UDP выключен.${NC}"
    else
        if ! tpws_supports_udp; then
            echo -e "${RED}Установленный tpws не поддерживает UDP. Выполните: zapret update${NC}" >&2
            return 1
        fi
        set_use_udp "1"
        echo -e "${GREEN}UDP включён (профиль: $(get_udp_profile)).${NC}"
    fi
    restart_if_running
}

set_udp_profile() {
    local name="$1"
    if [[ -z "$name" ]]; then
        if [[ $INTERACTIVE -eq 0 ]]; then
            echo "Error: profile name required. Use: udp-profile <name>" >&2
            return 1
        fi
        echo -e "${BLUE}UDP-профили:${NC}"
        local i=1 cur
        cur=$(get_udp_profile)
        for n in "${UDP_PROFILE_NAMES[@]}"; do
            if [[ "$n" == "$cur" ]]; then
                echo -e "  $i) $n ${GREEN}(текущий)${NC}"
            else
                echo "  $i) $n"
            fi
            echo "       ${UDP_PROFILES[$n]:-(без десинхронизации, только проксирование)}"
            ((i++))
        done
        echo "  $i) задать свои параметры (custom)"
        read -p "Номер (0 для отмены): " num
        [[ "$num" == "0" || -z "$num" ]] && return 0
        if [[ "$num" == "$i" ]]; then
            echo "Пример: --udp-desync-ports=443 --udp-fake-ttl=5 --udp-fake-repeats=4"
            read -p "Параметры: " custom
            custom=$(echo "$custom" | xargs)
            [[ -z "$custom" ]] && { echo -e "${RED}Пусто.${NC}"; return 1; }
            echo "$custom" > "$UDP_CUSTOM_FILE"
            init_udp_profiles
            name="custom"
        elif [[ "$num" =~ ^[0-9]+$ ]] && (( num >= 1 && num < i )); then
            name="${UDP_PROFILE_NAMES[$((num-1))]}"
        else
            echo -e "${RED}Неверный номер.${NC}"
            return 1
        fi
    fi
    if [[ -z "${UDP_PROFILES[$name]+x}" ]]; then
        echo -e "${RED}Нет UDP-профиля '$name'. Доступны: ${UDP_PROFILE_NAMES[*]}${NC}" >&2
        return 1
    fi
    echo "$name" > "$UDP_PROFILE_FILE"
    echo -e "${GREEN}UDP-профиль: $name${NC}"
    [[ "$(get_use_udp)" == "1" ]] && restart_if_running
    return 0
}

set_udp_custom() {
    local params="$*"
    if [[ -z "$params" ]]; then
        echo "Error: use: udp-custom \"--udp-fake-ttl=5 ...\"" >&2
        return 1
    fi
    echo "$params" > "$UDP_CUSTOM_FILE"
    init_udp_profiles
    set_udp_profile custom
}
