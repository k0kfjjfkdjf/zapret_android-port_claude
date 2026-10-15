# shellcheck shell=bash
# Модуль zapret-termux: запуск/остановка, статус, автозапуск. Подключается из termux/zapret, отдельно не запускается.

# -------------------------------------------------------------------
# Управление процессом
# -------------------------------------------------------------------
start_zapret() {
    if ! is_zapret_installed; then
        echo -e "${RED}Ошибка: zapret не установлен.${NC}"
        return 1
    fi

    if pgrep -f "tpws.*--socks" > /dev/null; then
        echo -e "${YELLOW}Zapret уже запущен.${NC}"
    else
        local cmd out
        cmd=$(get_command)
        # сначала проверка параметров: при ошибке показываем, что именно не понравилось tpws
        if ! out=$(eval "${cmd/ --daemon/} --dry-run" 2>&1); then
            echo -e "${RED}tpws не принял параметры:${NC}"
            echo "$out" | grep -vE "^(self-built|github) version|^$|we have .* desync profile|^Running as UID|lists summary|splits summary|^profile [0-9]+ " | tail -5 | sed 's/^/   /'
            echo "Команда: zapret cmd. Проверьте стратегию (zapret strategy …) и UDP-профиль (zapret udp-profile …)."
            return 1
        fi
        eval "$cmd &>/dev/null"
        if pgrep -f "tpws.*--socks" > /dev/null; then
            echo -e "${GREEN}Zapret запущен с стратегией '$(get_current_strategy_name)' на порту $(get_current_port)${NC}"
        else
            echo -e "${RED}Ошибка при запуске tpws.${NC}"
            # частая причина: порт занят
            if (exec 3<>/dev/tcp/127.0.0.1/"$(get_current_port)") 2>/dev/null; then
                echo "Порт $(get_current_port) уже занят другой программой. Смените порт: zapret set-port <порт>"
            fi
            return 1
        fi
    fi
}

stop_zapret() {
    if pgrep -f "tpws.*--socks" > /dev/null; then
        pkill -f "tpws.*--socks"
        echo -e "${GREEN}Zapret остановлен.${NC}"
    else
        echo -e "${YELLOW}Zapret не запущен.${NC}"
    fi
}

restart_zapret() {
    stop_zapret
    sleep 1
    start_zapret
}

status_zapret() {
    echo -e "${BLUE}═════════════════════════════════════════════════${NC}"
    if pgrep -f "tpws.*--socks" > /dev/null; then
        local pid
        pid=$(pgrep -f "tpws.*--socks" | head -1)
        echo -e "Статус:             ${GREEN}Запущен${NC} (PID: $pid)"
    else
        echo -e "Статус:             ${RED}Остановлен${NC}"
    fi

    if grep -q "zapret start" "$BASHRC" 2>/dev/null; then
        echo -e "Автозапуск:         ${GREEN}Включён${NC}"
    else
        echo -e "Автозапуск:         ${RED}Выключен${NC}"
    fi

    echo -e "Текущая стратегия:  ${YELLOW}$(get_current_strategy_name)${NC}"
    echo -e "Порт:               ${YELLOW}$(get_current_port)${NC}"
    
    if [[ "$(get_use_hostlist)" == "1" ]]; then
        local sel
        sel=$(get_selected_hostlist)
        if [[ -n "$sel" ]]; then
            echo -e "Hostlist:           ${GREEN}Включён${NC} (файл: $sel)"
        else
            echo -e "Hostlist:           ${YELLOW}Включён, но файл не выбран${NC}"
        fi
    else
        echo -e "Hostlist:           ${RED}Выключен${NC}"
    fi

    if [[ "$(get_use_udp)" == "1" ]]; then
        echo -e "UDP:                ${GREEN}Включён${NC} (профиль: $(get_udp_profile))"
    else
        echo -e "UDP:                ${RED}Выключен${NC}"
    fi
    if ! tpws_supports_udp && is_zapret_installed; then
        echo -e "                    ${YELLOW}tpws без поддержки UDP, нужен zapret update${NC}"
    fi
    echo -e "${BLUE}═════════════════════════════════════════════════${NC}"
}

# -------------------------------------------------------------------
# Новые функции для автоматизации
# -------------------------------------------------------------------
list_strategies() {
    for name in "${STRATEGY_NAMES[@]}"; do
        echo "$name"
    done
}

info_zapret() {
    local running=0
    local pid=""
    if pgrep -f "tpws.*--socks" > /dev/null; then
        running=1
        pid=$(pgrep -f "tpws.*--socks" | head -1)
    fi

    echo "status=$running"
    echo "pid=$pid"
    echo "bind_addr=127.0.0.1"
    echo "port=$(get_current_port)"
    echo "strategy=$(get_current_strategy_name)"
    echo "hostlist=$(get_use_hostlist)"
    echo "hostlist_file=$(get_selected_hostlist)"
    echo "autostart=$(zt_autostart_on && echo "1" || echo "0")"
    echo "platform=$(zt_platform)"
    echo "udp=$(get_use_udp)"
    echo "udp_profile=$(get_udp_profile)"
    echo "udp_supported=$(tpws_supports_udp && echo 1 || echo 0)"
}

list_hostlist_files() {
    local files
    files=$(get_hostlist_files)
    if [[ -z "$files" ]]; then
        echo -e "${YELLOW}No hostlist files found in $HOSTLIST_DIR${NC}" >&2
        return 1
    else
        echo "$files"
        return 0
    fi
}

check_hostlist() {
    local files
    files=$(get_hostlist_files)
    if [[ -z "$files" ]]; then
        echo -e "${RED}No hostlist files found in $HOSTLIST_DIR${NC}"
        return 1
    else
        echo -e "${GREEN}Available hostlist files:${NC}"
        local i=1
        local selected
        selected=$(get_selected_hostlist)
        for f in $files; do
            if [[ "$f" == "$selected" ]]; then
                echo -e "  $i) $f ${GREEN}(current)${NC}"
            else
                echo "  $i) $f"
            fi
            ((i++))
        done
        return 0
    fi
}

select_hostlist_file() {
    local choice="$1"
    if [[ -z "$choice" ]]; then
        if [[ $INTERACTIVE -eq 0 ]]; then
            echo "Error: no hostlist file specified. Use: hostlist-select <filename>" >&2
            return 1
        fi
        echo -e "${BLUE}Available files:${NC}"
        local files
        files=$(get_hostlist_files)
        if [[ -z "$files" ]]; then
            echo -e "${RED}No files in $HOSTLIST_DIR${NC}"
            return 1
        fi
        local i=1
        for f in $files; do
            echo "  $i) $f"
            ((i++))
        done
        read -p "Select file number (0 to cancel): " num
        if [[ "$num" == "0" || -z "$num" ]]; then
            return 0
        fi
        if [[ "$num" =~ ^[0-9]+$ ]]; then
            local file_name
            file_name=$(echo "$files" | sed -n "${num}p")
            if [[ -n "$file_name" ]]; then
                set_selected_hostlist "$file_name"
                echo -e "${GREEN}Selected: $file_name${NC}"
                if pgrep -f "tpws.*--socks" > /dev/null; then
                    if [[ $INTERACTIVE -eq 1 ]]; then
                        read -p "Restart zapret now? (y/n) " -n 1 -r
                        echo
                        if [[ $REPLY =~ ^[Yy]$ ]]; then
                            restart_zapret
                        fi
                    else
                        restart_zapret
                    fi
                fi
                return 0
            fi
        fi
        echo -e "${RED}Invalid selection.${NC}"
        return 1
    else
        # Non‑interactive: select by exact filename
        if [[ -f "$HOSTLIST_DIR/$choice" ]]; then
            set_selected_hostlist "$choice"
            echo -e "${GREEN}Selected: $choice${NC}"
            if pgrep -f "tpws.*--socks" > /dev/null; then
                restart_zapret
            fi
            return 0
        else
            echo -e "${RED}Error: file '$choice' not found in $HOSTLIST_DIR${NC}" >&2
            return 1
        fi
    fi
}

# -------------------------------------------------------------------
# Автозапуск
# -------------------------------------------------------------------
enable_autostart() {
    if zt_use_systemd; then
        # обычный linux: служба systemd (tpws запускается с --daemon, отсюда Type=forking)
        run_with_sudo tee "$ZT_UNIT" >/dev/null <<UNIT
[Unit]
Description=zapret-termux: tpws SOCKS5 proxy
After=network-online.target
Wants=network-online.target

[Service]
Type=forking
Environment=HOME=$HOME
ExecStart=$SCRIPT_PATH start
ExecStop=$SCRIPT_PATH stop

[Install]
WantedBy=multi-user.target
UNIT
        run_with_sudo systemctl daemon-reload && run_with_sudo systemctl enable zapret-termux &>/dev/null \
            && echo -e "${GREEN}Автозапуск включён: служба systemd zapret-termux (настройки из $HOME).${NC}" \
            || { echo -e "${RED}Не удалось включить службу systemd.${NC}"; return 1; }
        return 0
    fi
    if ! grep -q "zapret start" "$BASHRC" 2>/dev/null; then
        echo "" >> "$BASHRC"
        echo "# Автозапуск zapret" >> "$BASHRC"
        echo "if ! pgrep -f 'tpws.*--socks' > /dev/null; then $SCRIPT_PATH start; fi" >> "$BASHRC"
        echo -e "${GREEN}Автозапуск включён.${NC}"
    else
        echo -e "${YELLOW}Автозапуск уже включён.${NC}"
    fi
}

disable_autostart() {
    if zt_use_systemd && [[ -f "$ZT_UNIT" ]]; then
        run_with_sudo systemctl disable zapret-termux &>/dev/null
        run_with_sudo rm -f "$ZT_UNIT"
        run_with_sudo systemctl daemon-reload
        echo -e "${GREEN}Автозапуск отключён (служба systemd удалена).${NC}"
        return 0
    fi
    if grep -q "zapret start" "$BASHRC" 2>/dev/null; then
        sed -i '/# Автозапуск zapret/d' "$BASHRC"
        sed -i '/pgrep.*tpws.*--socks.*zapret start/d' "$BASHRC"
        echo -e "${GREEN}Автозапуск отключён.${NC}"
    else
        echo -e "${YELLOW}Автозапуск не был включён.${NC}"
    fi
}
