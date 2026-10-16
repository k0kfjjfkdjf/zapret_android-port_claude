# shellcheck shell=bash
# Модуль zapret-termux: отладочный запуск. Подключается из termux/zapret, отдельно не запускается.

# -------------------------------------------------------------------
# Отладка: tpws на переднем плане с логом
# -------------------------------------------------------------------
# отчёт по логу tpws: UDP-потоки, повторы IP discovery Discord, время TCP-подключений
debug_report() {
    local log="$1"
    echo ""
    echo -e "${GREEN}UDP-потоки (время до первого ответа, фейки):${NC}"
    grep -E "udp flow end" "$log" | sed -E 's/^udp flow end \([a-z]+\) : //' | sort | uniq -c | sort -rn | head -40
    local retries noreply
    retries=$(grep -c "ip discovery RETRY" "$log")
    noreply=$(grep -c "NO REPLY" "$log")
    echo ""
    if [[ $retries -gt 0 ]]; then
        echo -e "${YELLOW}Повторов IP discovery Discord: $retries. Первая датаграмма терялась, клиент ждал и повторял.${NC}"
        grep "ip discovery RETRY" "$log" | tail -5
    else
        echo "Повторов IP discovery Discord нет."
    fi
    [[ $noreply -gt 0 ]] && echo -e "${YELLOW}Потоков без ответа (NO REPLY): $noreply. Пакеты ушли, но ответа не было.${NC}"
    local nf
    nf=$(grep -c "fakes_before_first=0" "$log")
    [[ $nf -gt 0 ]] && echo "Потоков без фейков перед первой датаграммой: $nf (порт вне --udp-desync-ports или desync выключен)."
    echo ""
    echo -e "${GREEN}Самые медленные TCP-подключения (resolve+connect):${NC}"
    grep "socks tcp" "$log" | awk '{for(i=1;i<=NF;i++) if($i=="in"){t=$(i+1); sub("ms","",t); print t"\t"$0}}' | sort -rn | head -10 | cut -f2-
    local fails
    fails=$(grep -c "socks tcp FAILED" "$log")
    [[ $fails -gt 0 ]] && echo -e "${YELLOW}Неудачных TCP-подключений: $fails${NC}"
    echo ""
    echo "Полный лог: $log"
}

debug_zapret() {
    local log="$HOME/zapret-debug.log"
    if ! is_zapret_installed; then
        echo -e "${RED}zapret не установлен.${NC}"; return 1
    fi
    local was_running
    was_running=$(zt_pause)   # и служба systemd, и ручной запуск
    local cmd
    cmd=$(get_command)
    cmd="${cmd/ --daemon/} --debug=1"
    echo -e "${BLUE}Отладочный запуск. Повторите проблему (звонок, загрузку), затем нажмите Ctrl+C.${NC}"
    echo -e "${BLUE}Лог: $log${NC}"
    echo ""
    : > "$log"
    # exec: $! - это сам tpws, а не промежуточная оболочка (иначе tpws переживёт Ctrl+C)
    (eval "exec $cmd") >> "$log" 2>&1 &
    local tp=$!
    trap 'kill $tp 2>/dev/null' INT TERM
    tail -n +1 -f --pid=$tp "$log" 2>/dev/null | grep --line-buffered -E "udp flow|RETRY|udp resolv|socks tcp|could not|error|ERROR" &
    local tl=$!
    wait $tp 2>/dev/null
    while kill -0 $tp 2>/dev/null; do wait $tp 2>/dev/null; done
    kill $tl 2>/dev/null; wait $tl 2>/dev/null
    trap - INT TERM
    debug_report "$log"
    zt_resume "$was_running"
    return 0
}
