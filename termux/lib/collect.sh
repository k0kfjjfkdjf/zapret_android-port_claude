# shellcheck shell=bash
# Модуль zapret-termux: сбор доменов и IP приложения для hostlist (zapret collect).
# Подключается из termux/zapret, отдельно не запускается.
#
#   zapret collect discord              запуск со сбором: пользуйтесь только Discord, затем Ctrl+C
#   zapret collect discord log=файл     разобрать готовый лог tpws (--debug=1), например ~/zapret-debug.log
#
# Результат (повторные сборы дополняют списки):
#   /opt/zapret/ipset/collect-ИМЯ.txt       домены - hostlist для --hostlist, blockcheck, combine
#   /opt/zapret/ipset/collect-ИМЯ-ips.txt   IP без имени (TCP по IP, UDP-потоки, голос) - ipset
#   ~/zapret-collect-ИМЯ.txt                отчёт: подключения, ошибки, IP по каждому домену, UDP-потоки

# разбор лога tpws. Выход: строки "D<TAB>домен<TAB>подключений<TAB>ошибок<TAB>ip,ip"
# "I<TAB>ip<TAB>что" для IP без имени и "U<TAB>ip:порт<TAB>протокол<TAB>пакетов_туда<TAB>ответ" для UDP
collect_parse() {
    sed 's/\r//' "$1" | awk '
    function isip(h) { return h ~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$/ || h ~ /:/ }
    function noport(hp) { sub(/:[0-9]+$/, "", hp); gsub(/[\[\]]/, "", hp); return hp }
    function addhost(h, ip, failed) {
        h = tolower(h); sub(/\.$/, "", h)
        if (h == "" || isip(h)) return
        conns[h]++; fails[h] += failed
        if (ip != "" && !((h, ip) in hip)) { hip[h, ip] = 1; ips[h] = ips[h] (ips[h] == "" ? "" : ",") ip }
        if (ip != "") iphost[ip] = h
    }
    # данные в уже открытом соединении: "tampering tcp segment ... to IP:PORT", затем "connection hostname: X"
    # если строки склеились (копия из терминала), имя относится к сегменту, который был до него
    { tpos = 0 }
    /tampering tcp segment with size [0-9]+ to / {
        tpos = index($0, "tampering tcp segment"); prev_ip = last_ip
        t = $0; sub(/.*tampering tcp segment with size [0-9]+ to /, "", t); sub(/ .*/, "", t); last_ip = noport(t)
    }
    /(connection|request) hostname: / {
        h = $0; sub(/.*(connection|request) hostname: /, "", h); sub(/[ \t].*/, "", h); h = tolower(h)
        cip = (tpos > 0 && tpos > index($0, "hostname: ")) ? prev_ip : last_ip
        if (h != "" && !isip(h)) {
            if (!(h in conns)) conns[h] += 0
            if (cip != "" && !((h, cip) in hip)) { hip[h, cip] = 1; ips[h] = ips[h] (ips[h] == "" ? "" : ",") cip }
            if (cip != "") iphost[cip] = h
        }
    }
    /socks tcp (connected|FAILED) : / {
        s = $0; sub(/.*socks tcp (connected|FAILED) : /, "", s)
        n = split(s, a, " ")
        failed = ($0 ~ /socks tcp FAILED/) ? 1 : 0
        if (a[2] == "in") { ip = noport(a[1]); ipconn[ip]++; ipfail[ip] += failed }
        else if (isip(a[1])) { ip = noport(a[2]); ipconn[ip]++; ipfail[ip] += failed }   # имя = IP (Host: IP)
        else { ip = noport(a[2]); if (ip == "unresolved") { ip = ""; unres[tolower(a[1])]++ }; addhost(a[1], ip, failed) }
        next
    }
    /desync profile search for tcp target=/ && /hostname=\047/ {
        t = $0; sub(/.*target=/, "", t); sub(/ .*/, "", t); t = noport(t)
        h = $0; sub(/.*hostname=\047/, "", h); sub(/\047.*/, "", h)
        h = tolower(h)
        if (h != "" && !isip(h)) { name_of[t] = h; snihost[h] = 1; iphost[t] = h }
        next
    }
    /udp resolved / {
        s = $0; sub(/.*udp resolved /, "", s); split(s, a, " ")
        d = tolower(a[1]); ip = a[3]; sub(/\.$/, "", ip)
        if (!isip(d)) { udpdom[d]++; if (ip != "") udpip_name[ip] = d }
        next
    }
    /udp flow end/ {
        s = $0; sub(/.*: -> /, "", s); split(s, a, " ")
        dst = a[1]; proto = a[2]; sub(/proto=/, "", proto)
        out = ""; if (match($0, /out=[0-9]+/)) out = substr($0, RSTART + 4, RLENGTH - 4)
        rep = ($0 ~ /NO REPLY/) ? "нет ответа" : "есть ответ"
        if (match($0, /first_reply=[0-9]+ms/)) rep = "ответ через " substr($0, RSTART + 12, RLENGTH - 12)
        udp[dst] = proto "\t" out "\t" rep
        next
    }
    /udp flow start : -> / {
        s = $0; sub(/.*udp flow start : -> /, "", s); split(s, a, " ")
        dst = a[1]; proto = a[2]; sub(/proto=/, "", proto)
        if (!(dst in udp)) udp[dst] = proto "\t?\tпоток не закрыт"
        next
    }
    END {
        # TCP по IP: имя из ClientHello, если было
        for (ip in ipconn) {
            if (ip in name_of) { h = name_of[ip]; conns[h] += ipconn[ip]; fails[h] += ipfail[ip]
                if (!((h, ip) in hip)) { hip[h, ip] = 1; ips[h] = ips[h] (ips[h] == "" ? "" : ",") ip } }
            else printf "I\t%s\tTCP %d подкл.%s\n", ip, ipconn[ip], ipfail[ip] ? ", ошибок " ipfail[ip] : ""
        }
        for (h in snihost) if (!(h in conns)) conns[h] += 0
        for (d in udpdom) if (!(d in conns)) conns[d] += 0
        for (h in conns) printf "D\t%s\t%d\t%d\t%s\n", h, conns[h], fails[h], ((h in unres) ? "имя не разрешилось" (ips[h] != "" ? ", " : "") : "") ips[h]
        for (dst in udp) {
            ip = noport(dst)
            nm = (ip in udpip_name) ? udpip_name[ip] : ((ip in iphost) ? iphost[ip] : "")
            printf "U\t%s\t%s\t%s\n", dst, udp[dst], nm
            if (nm == "") printf "I\t%s\tUDP\n", ip
        }
    }'
}

collect_save() {
    local name="$1" log="$2"
    local dom_f="$HOSTLIST_DIR/collect-$name.txt" ip_f="$HOSTLIST_DIR/collect-$name-ips.txt"
    local rep="$HOME/zapret-collect-$name.txt" parsed
    parsed=$(collect_parse "$log")
    local nd ni nu
    nd=$(grep -c '^D' <<< "$parsed"); ni=$(grep '^I' <<< "$parsed" | cut -f2 | sort -u | grep -c .); nu=$(grep -c '^U' <<< "$parsed")
    if [[ $nd -eq 0 && $ni -eq 0 ]]; then
        echo -e "${YELLOW}В логе нет подключений. Трафик приложения шёл через tpws? (SocksTun включён, порт $(get_current_port))${NC}"
        return 1
    fi
    mkdir -p "$HOSTLIST_DIR"
    local before_d=0 before_i=0
    [[ -f "$dom_f" ]] && before_d=$(grep -c . "$dom_f")
    [[ -f "$ip_f" ]] && before_i=$(grep -c . "$ip_f")
    # повторный сбор дополняет списки
    { [[ -f "$dom_f" ]] && cat "$dom_f"; grep '^D' <<< "$parsed" | cut -f2; } | grep . | sort -u > "$dom_f.new" && mv "$dom_f.new" "$dom_f"
    { [[ -f "$ip_f" ]] && cat "$ip_f"; grep '^I' <<< "$parsed" | cut -f2; } | grep . | sort -u > "$ip_f.new" && mv "$ip_f.new" "$ip_f"
    # tpws, запущенный от root, сбрасывает права и читает списки уже без root
    chmod 644 "$dom_f" "$ip_f" 2>/dev/null

    {
        echo "zapret collect $name  $(date '+%F %T')  лог: $log"
        echo ""
        echo "Домены (подключений, ошибок, IP):"
        grep '^D' <<< "$parsed" | sort -t$'\t' -k3,3nr | awk -F'\t' '{printf "  %-45s %4d %s %s\n", $2, $3, ($4>0 ? "ошибок " $4 : "        "), $5}'
        echo ""
        echo "IP без имени:"
        grep '^I' <<< "$parsed" | sort -u | awk -F'\t' '{printf "  %-40s %s\n", $2, $3}'
        echo ""
        echo "UDP-потоки (протокол, пакетов туда, ответ):"
        grep '^U' <<< "$parsed" | awk -F'\t' '{printf "  %-28s %-8s %5s  %-18s %s\n", $2, $3, $4, $5, $6}'
    } > "$rep"

    echo ""
    echo -e "${GREEN}Собрано: доменов $nd, IP без имени $ni, UDP-потоков $nu.${NC}"
    grep '^D' <<< "$parsed" | sort -t$'\t' -k3,3nr | head -15 | awk -F'\t' '{printf "  %-45s %4d подкл.%s\n", $2, $3, ($4>0 ? ", ошибок " $4 : "")}'
    [[ $nd -gt 15 ]] && echo "  … и ещё $((nd-15)), полностью в отчёте"
    local nf
    nf=$(grep '^D' <<< "$parsed" | awk -F'\t' '$4>0' | grep -c .)
    [[ $nf -gt 0 ]] && echo -e "${YELLOW}С ошибками подключения: $nf доменов (кандидаты на другую стратегию или блокировку по IP).${NC}"
    echo ""
    echo "Hostlist: $dom_f ($before_d -> $(grep -c . "$dom_f") доменов)"
    echo "Ipset:    $ip_f ($before_i -> $(grep -c . "$ip_f") IP)"
    echo "Отчёт:    $rep"
    echo ""
    echo "Дальше:"
    echo "  zapret blockcheck hostlist:collect-$name.txt          подобрать стратегию для этих доменов"
    echo "  zapret combine list:collect-$name.txt=СТРАТЕГИЯ default=…   применить только к ним"
    echo "  zapret hostlist-select collect-$name.txt               использовать как hostlist"
}

collect_zapret() {
    local name="$1"; shift || true
    if [[ -z "$name" || ! "$name" =~ ^[A-Za-z0-9_-]+$ ]]; then
        echo "Использование: zapret collect ИМЯ [log=файл]   (ИМЯ: латиница, цифры, _ и -)"
        echo "  zapret collect discord              сбор во время работы приложения, стоп - Ctrl+C"
        echo "  zapret collect discord log=~/zapret-debug.log   разобрать готовый лог tpws"
        return 1
    fi
    local a log=""
    for a in "$@"; do
        case "$a" in log=*) log="${a#log=}"; log="${log/#\~/$HOME}" ;; esac
    done
    if [[ -n "$log" ]]; then
        [[ -r "$log" ]] || { echo -e "${RED}Нет лога $log${NC}"; return 1; }
        collect_save "$name" "$log"
        return
    fi

    is_zapret_installed || { echo -e "${RED}zapret не установлен.${NC}"; return 1; }
    log="$HOME/zapret-collect-$name.log"
    local was_running=0
    if pgrep -f "tpws.*--socks" > /dev/null; then
        was_running=1
        pkill -f "tpws.*--socks"; sleep 1
    fi
    local cmd
    cmd=$(get_command)
    cmd="${cmd/ --daemon/} --debug=1"
    echo -e "${BLUE}Сбор доменов '$name'. zapret работает как обычно (текущая стратегия), всё пишется в лог.${NC}"
    echo "1) В SocksTun оставьте в списке приложений только нужное (например, только Discord)."
    echo "2) Пользуйтесь им: откройте разделы, звонок, видео - всё, что должно работать."
    echo "3) Нажмите Ctrl+C. Новые домены появляются ниже по мере подключения."
    echo ""
    : > "$log"
    # exec: $! - это сам tpws, а не промежуточная оболочка (иначе tpws переживёт Ctrl+C)
    (eval "exec $cmd") >> "$log" 2>&1 &
    local tp=$!
    trap 'kill $tp 2>/dev/null' INT TERM
    # новые имена на экран по мере появления. Построчно в bash: mawk читает из трубы блоками
    # и показывал бы строки с большой задержкой
    tail -n +1 -f --pid=$tp "$log" 2>/dev/null | {
        local -A seen=()
        local line h f
        while IFS= read -r line; do
            h=""; f=""
            case "$line" in
                *"socks tcp FAILED : "*)    h="${line##*socks tcp FAILED : }"; h="${h%% *}"; f="  (ошибка)" ;;
                *"socks tcp connected : "*) h="${line##*socks tcp connected : }"; h="${h%% *}" ;;
                *"udp flow start : -> "*)   h="${line##*udp flow start : -> }"; h="udp ${h%% len=*}" ;;
                *"hostname='"*)             h="${line##*hostname=\'}"; h="${h%%\'*}" ;;
            esac
            [[ "$h" =~ ^[0-9.]+:[0-9]+$ ]] && h="${h%:*}"
            [[ -n "$h" && -z "${seen[$h]}" ]] && { seen[$h]=1; echo "  + $h$f"; }
        done
    } &
    local tl=$!
    wait $tp 2>/dev/null
    while kill -0 $tp 2>/dev/null; do wait $tp 2>/dev/null; done
    kill $tl 2>/dev/null; wait $tl 2>/dev/null
    trap - INT TERM
    collect_save "$name" "$log"
    [[ $was_running -eq 1 ]] && start_zapret
    return 0
}
