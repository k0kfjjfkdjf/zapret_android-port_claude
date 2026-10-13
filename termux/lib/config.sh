# shellcheck shell=bash
# Модуль zapret-termux: порт и hostlist. Подключается из termux/zapret, отдельно не запускается.

# -------------------------------------------------------------------
# Конфигурация порта и hostlist (с поддержкой -y)
# -------------------------------------------------------------------
change_port() {
    local new_port="$1"
    local force="$2"
    if [[ -z "$new_port" ]]; then
        if [[ $INTERACTIVE -eq 0 ]]; then
            echo "Error: port number required. Use: set-port <port> [-y]" >&2
            return 1
        fi
        echo -e "${BLUE}Текущий порт: $(get_current_port)${NC}"
        read -p "Введите новый номер порта (1025-65535): " new_port
    fi

    if [[ "$new_port" =~ ^[0-9]+$ ]] && (( new_port >= 1025 && new_port <= 65535 )); then
        set_port "$new_port"
        echo -e "${GREEN}Порт изменён на $new_port${NC}"
        if pgrep -f "tpws.*--socks" > /dev/null; then
            if [[ "$force" == "-y" || "$force" == "--yes" ]]; then
                restart_zapret
            elif [[ $INTERACTIVE -eq 1 ]]; then
                read -p "Перезапустить zapret сейчас? (y/n) " -n 1 -r
                echo
                if [[ $REPLY =~ ^[Yy]$ ]]; then
                    restart_zapret
                fi
            else
                echo -e "${YELLOW}Порт изменён. Для применения перезапустите zapret (restart) или используйте -y.${NC}"
            fi
        fi
    else
        echo -e "${RED}Неверный порт. Допустимы значения от 1025 до 65535.${NC}"
        return 1
    fi
}

toggle_hostlist() {
    local force="$1"
    local current
    current=$(get_use_hostlist)

    if [[ "$current" == "1" ]]; then
        set_use_hostlist "0"
        echo -e "${GREEN}Hostlist disabled.${NC}"
        # Перезапускаем только если процесс запущен и мы в неинтерактивном режиме
        if pgrep -f "tpws.*--socks" > /dev/null; then
            if [[ "$force" == "-y" || "$force" == "--yes" ]] || [[ $INTERACTIVE -eq 0 ]]; then
                restart_zapret
            else
                read -p "Restart zapret now? (y/n) " -n 1 -r
                echo
                if [[ $REPLY =~ ^[Yy]$ ]]; then
                    restart_zapret
                fi
            fi
        fi
    else
        # Включаем hostlist
        local files
        files=$(get_hostlist_files)
        if [[ -z "$files" ]]; then
            echo -e "${RED}Error: no hostlist files found in $HOSTLIST_DIR${NC}" >&2
            return 1
        fi

        # Если нет выбранного файла, берём первый
        local selected
        selected=$(get_selected_hostlist)
        if [[ -z "$selected" || ! -f "$HOSTLIST_DIR/$selected" ]]; then
            selected=$(echo "$files" | head -1)
            set_selected_hostlist "$selected"
            echo -e "${YELLOW}No specific file selected, using: $selected${NC}"
        fi

        set_use_hostlist "1"
        echo -e "${GREEN}Hostlist enabled.${NC}"

        if pgrep -f "tpws.*--socks" > /dev/null; then
            if [[ "$force" == "-y" || "$force" == "--yes" ]] || [[ $INTERACTIVE -eq 0 ]]; then
                restart_zapret
            else
                read -p "Restart zapret now? (y/n) " -n 1 -r
                echo
                if [[ $REPLY =~ ^[Yy]$ ]]; then
                    restart_zapret
                fi
            fi
        fi
    fi
}
