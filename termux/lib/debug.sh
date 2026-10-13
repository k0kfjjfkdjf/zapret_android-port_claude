# shellcheck shell=bash
# Модуль zapret-termux: отладочный запуск. Подключается из termux/zapret, отдельно не запускается.

# -------------------------------------------------------------------
# Отладка: tpws на переднем плане с логом
# -------------------------------------------------------------------
debug_zapret() {
    local log="$HOME/zapret-debug.log"
    if ! is_zapret_installed; then
        echo -e "${RED}zapret не установлен.${NC}"; return 1
    fi
    local was_running=0
    if pgrep -f "tpws.*--socks" > /dev/null; then
        was_running=1
        pkill -f "tpws.*--socks"; sleep 1
    fi
    local cmd
    cmd=$(get_command)
    cmd="${cmd/ --daemon/} --debug=1"
    echo -e "${BLUE}Отладочный запуск. Повторите проблему (звонок, загрузку), затем нажмите Ctrl+C.${NC}"
    echo -e "${BLUE}Лог: $log${NC}"
    echo ""
    : > "$log"
    eval "$cmd" >> "$log" 2>&1 &
    local tp=$!
    trap 'kill $tp 2>/dev/null' INT TERM
    tail -n +1 -f --pid=$tp "$log" 2>/dev/null | grep --line-buffered -E "udp flow|udp resolv|could not|error|ERROR" &
    local tl=$!
    wait $tp 2>/dev/null
    while kill -0 $tp 2>/dev/null; do wait $tp 2>/dev/null; done
    kill $tl 2>/dev/null; wait $tl 2>/dev/null
    trap - INT TERM
    echo ""
    echo -e "${GREEN}Сводка по UDP-потокам:${NC}"
    grep -E "udp flow end" "$log" | sed -E 's/^udp flow end \([a-z]+\) : //' | sort | uniq -c | sort -rn | head -40
    echo ""
    echo -e "${YELLOW}Потоки с пометкой NO REPLY: пакеты ушли, но ответа не было.${NC}"
    [[ $was_running -eq 1 ]] && start_zapret
    return 0
}
