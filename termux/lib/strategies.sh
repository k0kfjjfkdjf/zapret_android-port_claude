# shellcheck shell=bash
# Модуль zapret-termux: TCP-стратегии. Подключается из termux/zapret, отдельно не запускается.

# -------------------------------------------------------------------
# Управление стратегиями
# -------------------------------------------------------------------
# имя|параметры. Новые стратегии из этого списка добавляются и в уже существующий ~/.zapret_strategies
# список по умолчанию лежит в data/strategies.txt (имя|параметры)
default_strategy_lines() {
    [[ -f "$ZT_STRATEGIES_DATA" ]] || return 0
    grep -v '^[[:space:]]*#' "$ZT_STRATEGIES_DATA" | grep '|'
}

create_default_strategies() {
    STRATEGIES=()
    STRATEGY_NAMES=()
    local line
    while IFS= read -r line; do
        STRATEGIES["${line%%|*}"]="${line#*|}"
        STRATEGY_NAMES+=("${line%%|*}")
    done < <(default_strategy_lines)
}

merge_default_strategies() {
    local line name added=0
    while IFS= read -r line; do
        name="${line%%|*}"
        if [[ -z "${STRATEGIES[$name]+x}" ]]; then
            STRATEGIES["$name"]="${line#*|}"
            STRATEGY_NAMES+=("$name")
            added=1
        fi
    done < <(default_strategy_lines)
    [[ $added -eq 1 ]] && save_strategies
}

save_strategies() {
    > "$STRATEGIES_FILE"
    for name in "${STRATEGY_NAMES[@]}"; do
        echo "$name|${STRATEGIES[$name]}" >> "$STRATEGIES_FILE"
    done
}

load_strategies() {
    STRATEGIES=()
    STRATEGY_NAMES=()
    if [[ -f "$STRATEGIES_FILE" ]]; then
        while IFS='|' read -r name params; do
            name=$(echo "$name" | xargs)
            params=$(echo "$params" | xargs)
            if [[ -n "$name" && -n "$params" ]]; then
                STRATEGIES["$name"]="$params"
                STRATEGY_NAMES+=("$name")
            fi
        done < "$STRATEGIES_FILE"
        merge_default_strategies
    else
        create_default_strategies
        save_strategies
    fi
}

get_current_strategy_name() {
    if [[ -f "$STRATEGY_FILE" ]]; then
        cat "$STRATEGY_FILE"
    else
        echo "${STRATEGY_NAMES[0]:-default}" > "$STRATEGY_FILE"
        cat "$STRATEGY_FILE"
    fi
}

get_command() {
    local strategy
    strategy=$(get_current_strategy_name)
    if [[ -z "${STRATEGIES[$strategy]}" ]]; then
        strategy="${STRATEGY_NAMES[0]:-default}"
        echo "$strategy" > "$STRATEGY_FILE"
    fi

    local port
    port=$(get_current_port)
    local cmd="$TPWS_BIN --port=$port --bind-addr=127.0.0.1 --socks $ZT_UIDOPT ${STRATEGIES[$strategy]} --daemon"

    if [[ "$(get_use_udp)" == "1" ]]; then
        if tpws_supports_udp; then
            cmd="$cmd --socks-udp ${UDP_PROFILES[$(get_udp_profile)]}"
        else
            >&2 echo -e "${YELLOW}Warning: UDP включён, но tpws собран без поддержки UDP (zapret update)${NC}"
        fi
    fi

    if [[ "$(get_use_hostlist)" == "1" ]]; then
        local selected
        selected=$(get_selected_hostlist)
        if [[ -n "$selected" && -f "$HOSTLIST_DIR/$selected" ]]; then
            cmd="$cmd --hostlist=$HOSTLIST_DIR/$selected"
        else
            # Если выбранного файла нет, попробуем взять первый попавшийся
            local first_file
            first_file=$(get_hostlist_files | head -1)
            if [[ -n "$first_file" ]]; then
                set_selected_hostlist "$first_file"
                cmd="$cmd --hostlist=$HOSTLIST_DIR/$first_file"
            else
                # Нет ни одного файла — просто игнорируем hostlist
                >&2 echo -e "${YELLOW}Warning: hostlist enabled but no files found in $HOSTLIST_DIR${NC}"
            fi
        fi
    fi

    echo "$cmd"
}

# -------------------------------------------------------------------
# Меню выбора и создания стратегий
# -------------------------------------------------------------------
add_strategy() {
    echo -e "${BLUE}Добавление новой стратегии${NC}"
    read -p "Введите имя стратегии: " new_name
    new_name=$(echo "$new_name" | xargs)
    
    if [[ -z "$new_name" ]]; then
        echo -e "${RED}Имя не может быть пустым.${NC}"
        return 1
    fi
    if [[ -n "${STRATEGIES[$new_name]}" ]]; then
        echo -e "${RED}Стратегия с таким именем уже существует.${NC}"
        return 1
    fi

    echo "Введите параметры для tpws (например: --filter-tcp=80,443 --split-pos=1,midsld --disorder)"
    read -p "Параметры: " new_params
    new_params=$(echo "$new_params" | xargs)
    
    if [[ -z "$new_params" ]]; then
        echo -e "${RED}Параметры не могут быть пустыми.${NC}"
        return 1
    fi

    STRATEGIES["$new_name"]="$new_params"
    STRATEGY_NAMES+=("$new_name")
    save_strategies
    echo -e "${GREEN}Стратегия '$new_name' добавлена.${NC}"
}

select_strategy() {
    echo -e "${BLUE}Доступные стратегии:${NC}"
    local i=1
    for name in "${STRATEGY_NAMES[@]}"; do
        echo "  $i) $name"
        ((i++))
    done
    echo ""
    read -p "Выберите номер стратегии (0 для отмены): " num
    if [[ "$num" == "0" || -z "$num" ]]; then
        return 0
    fi

    if [[ "$num" =~ ^[0-9]+$ ]] && (( num >= 1 && num <= ${#STRATEGY_NAMES[@]} )); then
        local selected="${STRATEGY_NAMES[$((num-1))]}"
        echo "$selected" > "$STRATEGY_FILE"
        echo -e "${GREEN}Текущая стратегия изменена на '$selected'${NC}"
        if pgrep -f "tpws.*--socks" > /dev/null; then
            restart_zapret
        fi
    else
        echo -e "${RED}Неверный номер.${NC}"
    fi
}
