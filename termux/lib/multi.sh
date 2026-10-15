# shellcheck shell=bash
# Модуль zapret-termux: мультистратегия (экспериментально). Подключается из termux/zapret.
#
# Идея: одна основная стратегия открывает большинство адресов, а адреса, которые она не открывает,
# отдаются другим стратегиям через отдельные профили tpws (--new) с фильтрами --hostlist (домены)
# и --ipset (IP). tpws выбирает первый подходящий профиль и перевыбирает его, когда из ClientHello
# становится известно имя хоста, поэтому фильтр по доменам работает и при подключении по IP.
# В одном профиле --hostlist и --ipset работают как "И", поэтому домены и IP - разные профили.
#
# Алгоритм - жадное покрытие множества:
#   1) непокрытые основной стратегией адреса U берутся из уже готовых результатов подбора
#      (если основную на полном списке не проверяли - она проверяется)
#   2) стратегии, у которых уже есть результат на полном списке, заново не проверяются;
#      остальные проверяются только на U
#   3) пока U не пуст: берём стратегию, которая открывает больше всего адресов из U
#   4) сборка: профили-дополнения со своими списками, в конце основная стратегия без фильтра
#   5) итоговая мультистратегия проверяется заново на полном списке

MULTI_DIR_NAME="multi"

# хост из URL: https://host:port/path -> host, https://[v6]/ -> v6
bc_url_host() {
    local h="${1#*://}"
    h="${h%%/*}"
    if [[ "$h" == \[*\]* ]]; then h="${h%%]*}"; h="${h#[}"; else h="${h%%:*}"; fi
    echo "$h"
}

bc_is_ip() {
    [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ || "$1" == *:* ]]
}

# открылся ли raw-адрес номер $2 по строке результатов $1 (+/- по развёрнутым проверкам)
bc_raw_ok() {
    local det="$1" r="$2" off="${BC_RAWOFF[$r]}" n="${BC_RAWN[$r]}"
    [[ "${det:$off:$n}" != *-* && -n "${det:$off:$n}" ]]
}

# разбить параметры стратегии на профили по --new
bc_split_profiles() {
    echo "$1" | sed 's/[[:space:]]--new[[:space:]]/\n/g' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | grep -v '^$'
}

bc_multi() {
    local main_id="$1"
    local main_name="${cand_names[$main_id]}" main_opts="${cand_opts[$main_id]}"
    echo ""
    echo -e "${BLUE}══════ Мультистратегия (экспериментально) ══════${NC}"
    echo "Основная стратегия: $main_name"

    # стратегии с готовым результатом на полном списке (последний основной раунд и повторные проходы)
    local -a res_keep=("${res[@]}") known=()
    local k
    bc_expand "$tlsmode" "${raw_urls[@]}"
    local full_total=${#BC_URLS[@]}
    for k in "${BC_LAST_IDS[@]}"; do
        [[ ${#BC_DETAILS[$k]} -eq $full_total ]] && known+=("$k")
    done
    local -a full_off=("${BC_RAWOFF[@]}") full_n=("${BC_RAWN[@]}")

    # 1) основная на полном списке: из готовых результатов или новой проверкой
    if [[ " ${known[*]} " != *" $main_id "* ]]; then
        BC_ROUND=M1
        res=()
        bc_round "$main_id"
        [[ $stop -eq 1 ]] && return 1
    else
        echo "Результаты основной и ещё $(( ${#known[@]}-1 )) стратегий на полном списке уже есть, повторно их не проверяю."
    fi
    local main_det="${BC_DETAILS[$main_id]}" r
    local -a unc=() unc_raw=() main_ok=()
    for ((r=0; r<${#raw_urls[@]}; r++)); do
        if bc_raw_ok "$main_det" "$r"; then main_ok+=("${raw_urls[$r]}"); else unc+=("${raw_urls[$r]}"); unc_raw+=("$r"); fi
    done
    echo "Основная открывает ${#main_ok[@]} из ${#raw_urls[@]} адресов."
    if [[ ${#unc[@]} -eq 0 ]]; then
        echo -e "${GREEN}Основная стратегия открывает всё, мультистратегия не нужна.${NC}"
        res=("${res_keep[@]}")
        return 2
    fi

    # 2) остальные стратегии на непокрытых адресах: uok["стратегия|номер_в_unc"]=1, если открыла
    local -A uok=()
    local -a others=() totest=()
    local u
    for ((k=0; k<${#cand_names[@]}; k++)); do
        [[ $k -eq $main_id ]] && continue
        others+=("$k")
        if [[ " ${known[*]} " == *" $k "* ]]; then
            # готовый результат на полном списке: смещения полного списка
            local det="${BC_DETAILS[$k]}"
            for ((u=0; u<${#unc_raw[@]}; u++)); do
                r=${unc_raw[$u]}
                [[ "${det:${full_off[$r]}:${full_n[$r]}}" != *-* ]] && uok["$k|$u"]=1
            done
        else
            totest+=("$k")
        fi
    done
    bc_expand "$tlsmode" "${unc[@]}"
    if [[ ${#totest[@]} -gt 0 ]]; then
        echo ""
        echo -e "${BLUE}Проверка ${#totest[@]} стратегий на ${#unc[@]} адресах, которые не открыла основная (${#BC_URLS[@]} проверок на стратегию).${NC}"
        BC_ROUND=M2
        bc_round "${totest[@]}"
        [[ $stop -eq 1 ]] && return 1
        for k in "${BC_LAST_IDS[@]}"; do
            for ((u=0; u<${#unc[@]}; u++)); do bc_raw_ok "${BC_DETAILS[$k]}" "$u" && uok["$k|$u"]=1; done
        done
    fi

    # 3) жадное покрытие
    local -A left=()
    for ((r=0; r<${#unc[@]}; r++)); do left[$r]=1; done
    local -a pick_ids=() pick_sets=()
    while [[ ${#left[@]} -gt 0 ]]; do
        local best=-1 best_cnt=0 best_set="" id cnt set
        for id in "${others[@]}"; do
            cnt=0; set=""
            for r in "${!left[@]}"; do
                if [[ -n "${uok["$id|$r"]}" ]]; then cnt=$((cnt+1)); set+="$r "; fi
            done
            if [[ $cnt -gt $best_cnt ]]; then best=$id; best_cnt=$cnt; best_set="$set"; fi
        done
        [[ $best -lt 0 ]] && break
        pick_ids+=("$best"); pick_sets+=("$best_set")
        for r in $best_set; do unset "left[$r]"; done
    done

    if [[ ${#pick_ids[@]} -eq 0 ]]; then
        echo -e "${YELLOW}Ни одна другая стратегия не открывает адреса, которые не открыла основная. Мультистратегия ничего не добавит.${NC}"
        res=("${res_keep[@]}")
        return 2
    fi

    # 4) сборка
    local n=1
    while [[ -n "${STRATEGIES[multi_$n]+x}" ]]; do n=$((n+1)); done
    local name="multi_$n" mdir="$HOSTLIST_DIR/$MULTI_DIR_NAME"
    mkdir -p "$mdir"
    # tpws, запущенный от root, сбрасывает привилегии и читает списки уже без root
    chmod 755 "$mdir" 2>/dev/null
    rm -f "$mdir/${name}"_*
    local -a profiles=() report=()
    local i seg hosts ips hf ipf h
    for ((i=0; i<${#pick_ids[@]}; i++)); do
        id="${pick_ids[$i]}"
        hosts=""; ips=""
        for r in ${pick_sets[$i]}; do
            local u="${unc[$r]#big:}"; u="${u#ip:}"
            h=$(bc_url_host "$u")
            if bc_is_ip "$h"; then ips+="$h"$'\n'; else hosts+="$h"$'\n'; fi
        done
        hf="$mdir/${name}_$((i+1))_${cand_names[$id]}_hosts.txt"
        ipf="$mdir/${name}_$((i+1))_${cand_names[$id]}_ips.txt"
        while IFS= read -r seg; do
            if [[ -n "$hosts" ]]; then printf '%s' "$hosts" | sort -u > "$hf"; profiles+=("$seg --hostlist=$hf"); fi
            if [[ -n "$ips" ]];   then printf '%s' "$ips"   | sort -u > "$ipf"; profiles+=("$seg --ipset=$ipf"); fi
        done < <(bc_split_profiles "${cand_opts[$id]}")
        report+=("$(printf '%-22s +%d адр.: %s' "${cand_names[$id]}" "$(echo ${pick_sets[$i]} | wc -w)" "$(printf '%s' "$hosts$ips" | tr '\n' ' ' | cut -c1-80)")")
    done
    chmod 644 "$mdir/${name}"_* 2>/dev/null
    # основная - последней и без фильтра: ловит всё, что не попало в профили выше
    profiles+=("$main_opts")
    local combined="" p
    for p in "${profiles[@]}"; do
        [[ -n "$combined" ]] && combined+=" --new "
        combined+="$p"
    done
    # shellcheck disable=SC2086
    if ! "$TPWS_BIN" --port="$port" --bind-addr=127.0.0.1 --socks $combined --dry-run &>/dev/null; then
        echo -e "${RED}tpws не принял собранную мультистратегию. Это ошибка сборки, пришлите отчёт.${NC}"
        echo "$combined" >> "$BC_LOG"
        return 1
    fi

    # 5) проверка итоговой стратегии на полном списке
    cand_names+=("$name"); cand_opts+=("$combined")
    local mid=$(( ${#cand_names[@]} - 1 ))
    bc_expand "$tlsmode" "${raw_urls[@]}"
    echo ""
    echo -e "${BLUE}Проверка собранной мультистратегии на полном списке:${NC}"
    BC_ROUND=M3
    res=()
    bc_round "$mid"
    local m_ok=$BC_OK m_total=${#BC_URLS[@]} main_total_ok
    bc_report_by_address >/dev/null   # дополнить отчёт по адресам проверками мультистратегии
    main_total_ok=$(echo -n "$main_det" | tr -cd '+' | wc -c)

    echo ""
    echo -e "${GREEN}Мультистратегия $name:${NC}"
    printf '  основная  %-22s (остальные адреса)\n' "$main_name"
    for p in "${report[@]}"; do echo "  дополнение $p"; done
    local left_n=${#left[@]}
    [[ $left_n -gt 0 ]] && echo -e "  ${YELLOW}не открывает никто: $left_n адр.${NC}"
    echo "  Проверок пройдено: основная $main_total_ok/$m_total -> мультистратегия $m_ok/$m_total"
    echo "  Списки: $mdir/${name}_*"
    {
        echo "multi $name: main=$main_name ok=$main_total_ok/$m_total -> $m_ok/$m_total"
        echo "multi $name | $combined"
    } >> "$BC_LOG"

    if [[ $m_ok -le $main_total_ok ]]; then
        echo -e "${YELLOW}Мультистратегия не лучше основной (повторная проверка не подтвердила прирост). Не сохраняю.${NC}"
        res=("${res_keep[@]}")
        return 2
    fi

    STRATEGIES["$name"]="$combined"
    STRATEGY_NAMES+=("$name")
    save_strategies
    echo -e "${GREEN}Сохранена стратегия '$name'.${NC}"
    local yn=""
    if [[ $INTERACTIVE -eq 1 ]]; then
        read -p "Применить $name сейчас? (y/n) " -n 1 -r yn; echo
    elif [[ $apply -eq 1 ]]; then
        yn=y
    else
        echo "Применить: zapret strategy $name"
    fi
    if [[ "$yn" =~ ^[Yy]$ ]]; then
        echo "$name" > "$STRATEGY_FILE"
        echo -e "${GREEN}Текущая стратегия: $name${NC}"
        restart_if_running
    fi
    return 0
}
