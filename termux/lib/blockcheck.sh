# shellcheck shell=bash
# Модуль zapret-termux: blockcheck - подбор стратегий. Подключается из termux/zapret, отдельно не запускается.

# -------------------------------------------------------------------
# blockcheck: подбор TCP-стратегии прямо из терминала
# Каждая стратегия запускается отдельным tpws на свободном порту,
# основной zapret при этом продолжает работать.
# -------------------------------------------------------------------
BC_TIMEOUT=8
BC_OUTDIR="${BC_OUTDIR:-$HOME}"   # куда класть отчёт и отладочный архив
BC_LOG="$BC_OUTDIR/zapret-blockcheck.log"

# адреса из файла: домены (hostlist), IPv4/IPv6 и подсети CIDR (ipset). .gz тоже читается.
# для подсети берётся первый адрес хоста. IP проверяются без проверки сертификата (префикс ip:)
bc_from_file() {
    local f="$1"
    [[ -r "$f" ]] || { echo "Нет файла $f" >&2; return 0; }
    { if [[ "$f" == *.gz ]]; then zcat "$f"; else cat "$f"; fi; } | bc_parse_entries
}

# разбор записей со stdin: домены, IP, подсети -> адреса для проверки
bc_parse_entries() {
    sed -e 's/#.*//' -e 's/\r//' -e 's/^\*\.//' -e 's/:\*$//' \
        | awk 'NF{print $1}' | awk '!seen[tolower($0)]++' | head -n "${BC_LIST_MAX:-40}" \
        | awk '
            /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+\/[0-9]+$/ {
                split($0, a, "/"); split(a[1], o, "."); n=a[2]+0
                ip=((o[1]*256+o[2])*256+o[3])*256+o[4]
                if (n<32) { m=2^(32-n); ip=int(ip/m)*m+1 }
                printf "ip:https://%d.%d.%d.%d/\n", int(ip/16777216)%256, int(ip/65536)%256, int(ip/256)%256, ip%256; next }
            /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$/ { print "ip:https://" $0 "/"; next }
            /:/ { v6=$0; sub("/.*", "", v6); if (v6 ~ /::$/) v6=v6 "1"; print "ip:https://[" v6 "]/"; next }
            /\./ { print "https://" $0 "/" }'
}

bc_targets() {
    local t
    for t in "$@"; do
        case "$t" in
            discord)
                printf '%s\n' \
                    "https://discord.com/" \
                    "https://gateway.discord.gg/" \
                    "https://cdn.discordapp.com/embed/avatars/0.png" \
                    "https://media.discordapp.net/" \
                    "https://discord.gg/" ;;
            discord-full)
                # полный набор: сайт, API, шлюз, CDN, медиа, обновления + большой файл (16-20 КБ "заморозка" у ТСПУ)
                printf '%s\n' \
                    "https://discord.com/" \
                    "https://discord.com/app" \
                    "https://discord.com/api/v9/gateway" \
                    "https://gateway.discord.gg/" \
                    "https://cdn.discordapp.com/embed/avatars/0.png" \
                    "https://media.discordapp.net/" \
                    "https://discordapp.com/" \
                    "https://discord.gg/" \
                    "https://updates.discord.com/" \
                    "https://dl.discordapp.net/" \
                    "big:https://discord.com/api/download?platform=linux&format=tar.gz" ;;
            youtube)
                printf '%s\n' \
                    "https://www.youtube.com/" \
                    "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg" \
                    "https://yt3.ggpht.com/" \
                    "https://redirector.googlevideo.com/report_mapping" ;;
            list:*|discord-list|youtube-list)
                local ln="${t#list:}"; ln="${ln%-list}"
                local lf="$HOSTLIST_DIR/cfgs-$ln.txt"
                if [[ -f "$lf" ]]; then
                    bc_from_file "$lf"
                else
                    echo "Нет списка $lf. Выполните: zapret lists-update" >&2
                fi ;;
            hostlist:*) bc_from_file "$HOSTLIST_DIR/${t#hostlist:}" ;;
            file:*)     bc_from_file "${t#file:}" ;;
            ip:*)       echo "$t" ;;
            http://*|https://*|big:http://*|big:https://*) echo "$t" ;;
            *)
                # IPv4 / IPv6 / подсеть или домен
                if [[ "$t" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+(/[0-9]+)?$ || "$t" =~ ^[0-9a-fA-F:]*:[0-9a-fA-F:]*(/[0-9]+)?$ ]]; then
                    echo "$t" | BC_LIST_MAX=1 bc_parse_entries
                elif [[ "$t" == *.* ]]; then
                    echo "https://$t/"
                fi ;;
        esac
    done
}

# чистая проверка TCP-подключения к хосту из URL (curl 8 считает TLS частью connect,
# поэтому по его таймингам нельзя отличить блокировку по IP от зависшего TLS)
bc_tcp_ok() {
    local u="$1" hp host port
    hp="${u#*://}"; hp="${hp%%/*}"
    if [[ "$hp" == \[*\]* ]]; then host="${hp%%]*}"; host="${host#[}"; port="${hp##*]:}"; [[ "$port" == "$hp" || "$port" == \[* ]] && port=""
    else host="${hp%%:*}"; port="${hp##*:}"; [[ "$port" == "$hp" ]] && port=""; fi
    [[ -z "$port" ]] && { [[ "$u" == https://* ]] && port=443 || port=80; }
    timeout 4 bash -c "exec 3<>/dev/tcp/$host/$port" 2>/dev/null
}

# предупреждение о неработающем DNS: иначе проверка "напрямую" выглядит как блокировка
bc_check_dns() {
    if ! grep -q '^[[:space:]]*nameserver' /etc/resolv.conf 2>/dev/null; then
        echo -e "${YELLOW}В /etc/resolv.conf нет DNS-серверов. Имена могут разрешаться медленно или не разрешаться.${NC}"
        echo "  Исправить: echo 'nameserver 1.1.1.1' > /etc/resolv.conf   (или DNS вашего провайдера)"
    fi
    local t0 t1
    t0=$(date +%s%N)
    if ! timeout 6 getent hosts discord.com >/dev/null 2>&1; then
        echo -e "${YELLOW}DNS не отвечает: getent hosts discord.com не сработал за 6 с.${NC}"
    else
        t1=$(date +%s%N)
        local ms=$(( (t1-t0)/1000000 ))
        [[ $ms -gt 2000 ]] && echo -e "${YELLOW}DNS медленный: разрешение имени заняло ${ms} мс.${NC}"
    fi
}

bc_free_port() {
    local p
    for p in $(seq 1090 1190); do
        [[ "$p" == "$(get_current_port)" ]] && continue
        (exec 3<>/dev/tcp/127.0.0.1/$p) 2>/dev/null || { echo "$p"; return 0; }
    done
    return 1
}

# проверка всех адресов параллельно. $1 - пусто (напрямую) или порт socks
# результат: BC_OK (число успешных), BC_TIME (среднее время успешных), BC_DETAIL
bc_probe_all() {
    local port="$1" label="$2" i=0 url rc out code t
    local -a verbose=() cpids=()
    local ddir=""
    if [[ $BC_DEBUG -eq 1 && -n "$label" ]]; then
        ddir="$BC_DBGDIR/$label"; mkdir -p "$ddir"
        verbose=(-v)
    fi
    local -a proxy=()
    [[ -n "$port" ]] && proxy=(--socks5-hostname "127.0.0.1:$port")
    local dir
    dir=$(mktemp -d)
    for url in "${BC_URLS[@]}"; do
        local -a tlsopt=()
        # большой файл: первые 256 КБ (Range), с переходом по редиректам
        [[ "${BC_BIG[$i]}" == "1" ]] && tlsopt+=(-L -r 0-262143)
        # IP-адрес вместо домена: сертификат не совпадёт, проверяем только прохождение через DPI
        [[ "${BC_IP[$i]}" == "1" ]] && tlsopt+=(-k)
        case "${BC_TLS[$i]}" in
            12) tlsopt+=(--tlsv1.2 --tls-max 1.2) ;;
            13) tlsopt+=(--tlsv1.3) ;;
        esac
        (
            local errf=/dev/null
            [[ -n "$ddir" ]] && errf="$ddir/curl_$i.txt" && echo "URL: $url TLS:${BC_TLS[$i]:-any}" > "$errf"
            out=$(env -u ALL_PROXY -u all_proxy -u HTTPS_PROXY -u https_proxy -u HTTP_PROXY -u http_proxy \
                curl -s "${verbose[@]}" "${tlsopt[@]}" -o /dev/null --connect-timeout 5 --max-time "$BC_TIMEOUT" "${proxy[@]}" \
                -w '%{http_code} %{time_total} %{size_download} %{time_namelookup} %{time_connect} %{time_appconnect}' "$url" 2>>"$errf")
            rc=$?
            [[ -n "$ddir" ]] && echo "RESULT: rc=$rc $out" >> "$errf"
            echo "$rc $out" > "$dir/$i"
        ) &
        cpids+=($!)
        i=$((i+1))
    done
    wait "${cpids[@]}"   # только свои curl: tpws тоже фоновый процесс этой оболочки
    BC_OK=0; BC_DETAIL=""
    local tsum=0
    for ((i=0; i<${#BC_URLS[@]}; i++)); do
        local size ok=0 tdns tcon ttls
        read -r rc code t size tdns tcon ttls < "$dir/$i"
        # этап, на котором оборвалось: dns / tcp / tls / data
        BC_STAGE[$i]=""
        if [[ "$rc" != "0" ]]; then
            if [[ -z "$port" ]] && [[ "${tdns:-0}" == 0* ]] && [[ "${tcon:-0}" == 0.000000 || -z "$tcon" ]] && [[ "$rc" == "28" || "$rc" == "6" ]] && ! [[ "${BC_URLS[$i]}" =~ ://\[?[0-9a-fA-F:.]+\]?/ ]]; then
                BC_STAGE[$i]="dns"
            elif [[ "${tcon:-0}" == "0.000000" ]]; then
                BC_STAGE[$i]="tcp"
            elif [[ "${BC_URLS[$i]}" == https://* && "${ttls:-0}" == "0.000000" ]]; then
                BC_STAGE[$i]="tls"
            else
                BC_STAGE[$i]="data"
            fi
        fi
        if [[ "${BC_BIG[$i]}" == "1" ]]; then
            # большой файл: важен объём (заморозка после 16-20 КБ), а не код завершения
            [[ "${size%.*}" =~ ^[0-9]+$ ]] && (( ${size%.*} >= 200000 )) && ok=1
            [[ $ok -eq 0 && "$rc" == "0" ]] && rc=99
        elif [[ "$rc" == "0" && "$code" != "000" ]]; then
            ok=1
        fi
        if [[ $ok -eq 1 ]]; then
            rc=0
            BC_OK=$((BC_OK+1))
            tsum=$(awk -v a="$tsum" -v b="$t" 'BEGIN{printf "%.3f", a+b}')
            BC_DETAIL+="+"
        else
            BC_DETAIL+="-"
        fi
        BC_RC[$i]="$rc"
    done
    rm -rf "$dir"
    if [[ $BC_OK -gt 0 ]]; then
        BC_TIME=$(awk -v a="$tsum" -v n="$BC_OK" 'BEGIN{printf "%.2f", a/n}')
    else
        BC_TIME="-"
    fi
}

bc_label() {
    local i="$1" sfx=""
    [[ "${BC_BIG[$i]}" == "1" ]] && sfx=" [256KB]"
    case "${BC_TLS[$i]}" in
        12) echo "${BC_URLS[$i]} [TLS1.2]$sfx" ;;
        13) echo "${BC_URLS[$i]} [TLS1.3]$sfx" ;;
        *)  echo "${BC_URLS[$i]}$sfx" ;;
    esac
}

bc_rc_text() {
    # $2 - этап (dns/tcp/tls/data), если известен
    case "$1:$2" in
        28:dns|6:*) echo "DNS: имя не разрешилось (это не DPI: проверьте /etc/resolv.conf)" ; return ;;
        28:tcp) echo "таймаут TCP-подключения (блокировка по IP?)" ; return ;;
        28:tls) echo "таймаут TLS-рукопожатия (типично для DPI)" ; return ;;
        28:data) echo "данные перестали идти (заморозка DPI?)" ; return ;;
    esac
    case "$1" in
        0) echo "ok" ;;
        7) echo "соединение отклонено" ;;
        28) echo "таймаут" ;;
        35|56) echo "обрыв TLS/соединения (типично для DPI)" ;;
        60) echo "подмена сертификата" ;;
        97) echo "SOCKS: ошибка прокси" ;;
        99) echo "большой файл не докачан (заморозка на 16-20 КБ?)" ;;
        *) echo "curl код $1" ;;
    esac
}

# генерация дополнительных кандидатов для режима full
bc_generated() {
    local sp m
    local -a splits=("1" "2" "3" "sniext+1" "host+1" "midsld" "1,midsld" "1,sniext+1,host+1,midsld,endhost-1" "2,sniext+1,midsld")
    local -a mods=("" "--disorder" "--oob" "--disorder --oob" "--tlsrec=sniext+1" "--tlsrec=sniext+1 --disorder" "--disorder --mss=88")
    for sp in "${splits[@]}"; do
        for m in "${mods[@]}"; do
            echo "--filter-tcp=80,443 --split-pos=$sp $m"
        done
    done
    echo "--filter-tcp=80,443 --tlsrec=sniext"
    echo "--filter-tcp=80,443 --tlsrec=sniext+2"
    echo "--filter-tcp=80,443 --tlsrec=midsld"
    echo "--filter-tcp=80,443 --tlsrec=sniext+1 --mss=88"
}

bc_pack_debug() {
    [[ $BC_DEBUG -eq 1 && -d "$BC_DBGDIR" ]] || return 0
    cp "$BC_LOG" "$BC_DBGDIR/summary.log" 2>/dev/null
    local arc="$BC_OUTDIR/zapret-blockcheck-debug.tar.gz"
    tar -czf "$arc" -C "$(dirname "$BC_DBGDIR")" "$(basename "$BC_DBGDIR")" 2>/dev/null
    echo -e "${YELLOW}Архив с логами: $arc ($(du -h "$arc" | cut -f1))${NC}"
    if [[ -d /sdcard/Download && "$BC_OUTDIR" != /sdcard/Download* ]]; then
        cp "$arc" /sdcard/Download/ 2>/dev/null && echo -e "${YELLOW}Копия: /sdcard/Download/$(basename "$arc")${NC}"
    fi
    echo "В логах есть IP-адреса серверов и ваш внешний IP может попасть в ответы. Проверьте перед отправкой."
}

blockcheck_zapret() {
    local mode="quick" apply=0 tlsmode=both
    local -a targets=()
    BC_DEBUG=0
    local a
    for a in "$@"; do
        case "$a" in
            full|quick) mode="$a" ;;
            --apply|-y) apply=1 ;;
            debug|--debug) BC_DEBUG=1 ;;
            tls12|--tls=12) tlsmode=12 ;;
            tls13|--tls=13) tlsmode=13 ;;
            tlsboth|--tls=both) tlsmode=both ;;
            dir=*)     BC_OUTDIR="${a#dir=}" ;;
            timeout=*) BC_TIMEOUT="${a#timeout=}" ;;
            limit=*)   BC_LIST_MAX="${a#limit=}" ;;
            *) targets+=("$a") ;;
        esac
    done
    [[ ${#targets[@]} -eq 0 ]] && targets=(discord youtube)
    mkdir -p "$BC_OUTDIR" 2>/dev/null
    if [[ ! -w "$BC_OUTDIR" ]]; then
        echo -e "${RED}Нет доступа на запись в $BC_OUTDIR${NC}"; return 1
    fi
    BC_LOG="$BC_OUTDIR/zapret-blockcheck.log"
    bc_check_dns

    if ! is_zapret_installed; then
        echo -e "${RED}zapret не установлен.${NC}"; return 1
    fi
    if ! command -v curl &>/dev/null; then
        echo -e "${YELLOW}Нужен curl. Устанавливаю...${NC}"
        check_deps || return 1
    fi

    local -a raw_urls=()
    mapfile -t raw_urls < <(bc_targets "${targets[@]}" | awk '!seen[$0]++')
    BC_URLS=(); BC_TLS=(); BC_BIG=(); BC_IP=(); BC_STAGE=()
    local u big isip
    for u in "${raw_urls[@]}"; do
        big=0; isip=0
        [[ "$u" == big:* ]] && { big=1; u="${u#big:}"; }
        [[ "$u" == ip:* ]] && { isip=1; u="${u#ip:}"; }
        if [[ "$u" == https://* ]]; then
            case "$tlsmode" in
                both) BC_URLS+=("$u" "$u"); BC_TLS+=(12 13); BC_BIG+=($big $big); BC_IP+=($isip $isip) ;;
                *)    BC_URLS+=("$u"); BC_TLS+=("$tlsmode"); BC_BIG+=($big); BC_IP+=($isip) ;;
            esac
        else
            BC_URLS+=("$u"); BC_TLS+=(""); BC_BIG+=($big); BC_IP+=($isip)
        fi
    done
    if [[ ${#BC_URLS[@]} -eq 0 ]]; then
        echo -e "${RED}Нет адресов для проверки. Пример: zapret blockcheck discord youtube example.com${NC}"
        return 1
    fi
    local total=${#BC_URLS[@]}
    local i

    local port
    port=$(bc_free_port) || { echo -e "${RED}Нет свободного порта для теста.${NC}"; return 1; }

    # кандидаты: имя \t параметры
    local -a cand_names=() cand_opts=()
    local n o
    for n in "${STRATEGY_NAMES[@]}"; do
        cand_names+=("$n"); cand_opts+=("${STRATEGIES[$n]}")
    done
    if [[ "$mode" == "full" ]]; then
        local k=1
        while IFS= read -r o; do
            cand_names+=("gen$k"); cand_opts+=("$o"); k=$((k+1))
        done < <(bc_generated)
    fi

    {
        echo "blockcheck $(date '+%F %T') mode=$mode"
        for ((i=0; i<${#BC_URLS[@]}; i++)); do echo "  $(bc_label $i)"; done
    } > "$BC_LOG"

    if [[ $BC_DEBUG -eq 1 ]]; then
        BC_DBGDIR="$BC_OUTDIR/zapret-blockcheck-debug"
        rm -rf "$BC_DBGDIR"; mkdir -p "$BC_DBGDIR"
        {
            echo "=== date";        date
            echo "=== uname";       uname -a
            echo "=== os";          cat /etc/os-release 2>/dev/null | head -3
            echo "=== zapret-termux"; zt_version
            echo "=== tpws";        ls -la "$TPWS_BIN"; git -C "$ZAPRET_DIR" log -1 --format='%h %cd' 2>/dev/null
            echo "=== udp support"; tpws_supports_udp && echo yes || echo no
            echo "=== zapret info"; info_zapret
            echo "=== main cmd";    get_command 2>&1
            echo "=== resolv.conf"; cat /etc/resolv.conf 2>/dev/null
            echo "=== dns"
            local h
            for h in $(printf '%s\n' "${BC_URLS[@]}" | sed -E 's#^https?://([^/:]+).*#\1#' | sort -u); do
                echo "-- $h"; getent ahosts "$h" 2>&1 | awk '{print $1}' | sort -u | head -6
            done
            echo "=== candidates"
            for ((i=0; i<${#cand_names[@]}; i++)); do echo "${cand_names[$i]} | ${cand_opts[$i]}"; done
        } > "$BC_DBGDIR/sysinfo.txt" 2>&1
        echo -e "${YELLOW}Режим отладки: логи в $BC_DBGDIR${NC}"
    fi

    echo -e "${BLUE}Адреса для проверки:${NC}"
    for ((i=0; i<total; i++)); do echo "  $(bc_label $i)"; done
    echo ""
    echo -e "${BLUE}Без zapret (напрямую):${NC}"
    bc_probe_all "" "00_direct"
    # уточнить этап для таймаутов: TCP прошёл -> обрыв на TLS
    for ((i=0; i<total; i++)); do
        if [[ "${BC_STAGE[$i]}" == "tcp" ]] && bc_tcp_ok "${BC_URLS[$i]}"; then
            [[ "${BC_URLS[$i]}" == https://* ]] && BC_STAGE[$i]="tls" || BC_STAGE[$i]="data"
        fi
    done
    for ((i=0; i<total; i++)); do
        printf '  %-62s %s\n' "$(bc_label $i)" "$(bc_rc_text "${BC_RC[$i]}" "${BC_STAGE[$i]}")"
    done | tee -a "$BC_LOG"
    if [[ $BC_OK -eq $total ]]; then
        echo -e "${YELLOW}Все адреса открываются и без zapret. Либо блокировки нет, либо она срабатывает не сразу.${NC}"
    fi
    echo ""
    echo -e "${BLUE}Проверка ${#cand_names[@]} стратегий (порт $port, таймаут ${BC_TIMEOUT}с). Ctrl+C - прервать.${NC}"
    echo -e "${BLUE}+ адрес открылся, - не открылся${NC}"

    local pid="" stop=0
    trap 'stop=1; [[ -n "$pid" ]] && kill $pid 2>/dev/null' INT TERM
    local -a res=()
    local idx=0
    for ((idx=0; idx<${#cand_names[@]}; idx++)); do
        [[ $stop -eq 1 ]] && break
        n="${cand_names[$idx]}"; o="${cand_opts[$idx]}"
        # shellcheck disable=SC2086
        if ! "$TPWS_BIN" --port="$port" --bind-addr=127.0.0.1 --socks $o --dry-run &>/dev/null; then
            printf '[%3d/%d] %-20s %s\n' $((idx+1)) ${#cand_names[@]} "$n" "неверные параметры, пропуск"
            continue
        fi
        local label tlog=/dev/null dbgopt=""
        label=$(printf '%03d_%s' $((idx+1)) "$n")
        if [[ $BC_DEBUG -eq 1 ]]; then
            mkdir -p "$BC_DBGDIR/$label"
            echo "$o" > "$BC_DBGDIR/$label/strategy.txt"
            tlog="$BC_DBGDIR/$label/tpws.log"
            dbgopt="--debug=1"
        fi
        # shellcheck disable=SC2086
        "$TPWS_BIN" --port="$port" --bind-addr=127.0.0.1 --socks $o $dbgopt &>"$tlog" &
        pid=$!
        for i in $(seq 1 30); do
            (exec 3<>/dev/tcp/127.0.0.1/$port) 2>/dev/null && break
            sleep 0.1
        done
        if ! kill -0 $pid 2>/dev/null; then
            printf '[%3d/%d] %-20s %s\n' $((idx+1)) ${#cand_names[@]} "$n" "tpws не запустился"
            pid=""; continue
        fi
        bc_probe_all "$port" "$label"
        kill $pid 2>/dev/null; wait $pid 2>/dev/null; pid=""
        [[ $stop -eq 1 ]] && break
        local color="$RED"
        [[ $BC_OK -gt 0 ]] && color="$YELLOW"
        [[ $BC_OK -eq $total ]] && color="$GREEN"
        printf "[%3d/%d] %-20s ${color}%2d/%d${NC} %s %5ss\n" $((idx+1)) ${#cand_names[@]} "$n" $BC_OK $total "$BC_DETAIL" "$BC_TIME"
        echo "$BC_OK/$total $BC_DETAIL ${BC_TIME}s $n | $o" >> "$BC_LOG"
        res+=("$BC_OK|$BC_TIME|$idx")
    done
    trap - INT TERM

    [[ ${#res[@]} -eq 0 ]] && { echo -e "${RED}Нет результатов.${NC}"; bc_pack_debug; return 1; }

    # сортировка: больше успешных, затем быстрее
    local -a sorted
    mapfile -t sorted < <(printf '%s\n' "${res[@]}" | awk -F'|' '{t=($2=="-")?999:$2; printf "%d|%s|%s\n", $1, t, $3}' | sort -s -t'|' -k1,1nr -k2,2n)

    echo ""
    echo -e "${GREEN}Лучшие стратегии:${NC}"
    local r ok tm id shown=0
    for r in "${sorted[@]}"; do
        IFS='|' read -r ok tm id <<< "$r"
        [[ $ok -eq 0 ]] && break
        shown=$((shown+1))
        printf ' %d) %-20s %d/%d  %ss\n    %s\n' $shown "${cand_names[$id]}" "$ok" "$total" "$tm" "${cand_opts[$id]}"
        [[ $shown -ge 5 ]] && break
    done
    echo -e "${BLUE}Полный отчёт: $BC_LOG${NC}"
    bc_pack_debug
    if [[ $shown -eq 0 ]]; then
        echo -e "${RED}Ни одна стратегия не открыла адреса.${NC}"
        echo "Попробуйте: zapret blockcheck full ${targets[*]}"
        echo "Если адреса не открываются и через zapret, причина может быть не в DPI: блокировка по IP или DNS."
        return 1
    fi

    IFS='|' read -r ok tm id <<< "${sorted[0]}"
    local choose=""
    if [[ $INTERACTIVE -eq 1 ]]; then
        read -p "Применить стратегию номер (1-$shown, Enter - не применять): " choose
        [[ "$choose" =~ ^[0-9]+$ ]] && (( choose>=1 && choose<=shown )) || return 0
        IFS='|' read -r ok tm id <<< "${sorted[$((choose-1))]}"
    elif [[ $apply -eq 0 ]]; then
        echo "Применить лучшую: zapret strategy ${cand_names[$id]}  (или zapret blockcheck ... --apply)"
        [[ "${cand_names[$id]}" == gen* ]] && echo "Это сгенерированная стратегия, --apply сохранит её под именем auto_*"
        return 0
    fi

    local name="${cand_names[$id]}"
    if [[ "$name" == gen* ]]; then
        local k=1
        while [[ -n "${STRATEGIES[auto_$k]+x}" ]]; do k=$((k+1)); done
        name="auto_$k"
        STRATEGIES["$name"]="${cand_opts[$id]}"
        STRATEGY_NAMES+=("$name")
        save_strategies
        echo -e "${GREEN}Сохранена новая стратегия '$name'.${NC}"
    fi
    echo "$name" > "$STRATEGY_FILE"
    echo -e "${GREEN}Текущая стратегия: $name${NC}"
    restart_if_running
    return 0
}
