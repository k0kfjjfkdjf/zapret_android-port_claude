# shellcheck shell=bash
# Модуль zapret-termux: сборка одной стратегии из нескольких по доменам (zapret combine).
# Подключается из termux/zapret, отдельно не запускается.
#
#   zapret combine discord=split_host_oob youtube=ultimate_universal default=oob [name=my_combo]
#
# Получается одна команда tpws (один процесс): профили разделены --new, у каждого свой фильтр
# --hostlist-domains (или --hostlist для файла). tpws берёт первый подходящий профиль и перевыбирает
# его, когда из ClientHello становится известно имя сайта. default - последний профиль без фильтра.

# наборы доменов. Поддомены tpws учитывает сам (youtube.com покрывает www.youtube.com)
declare -A COMBINE_PRESETS=(
    [discord]="discord.com,discord.gg,discordapp.com,discordapp.net,discord.media,discordcdn.com,discord.dev,discord.gift,discord.new,discordstatus.com,dis.gd"
    [youtube]="youtube.com,youtu.be,ytimg.com,ggpht.com,googlevideo.com,youtubei.googleapis.com,youtube-nocookie.com,yt.be"
    [telegram]="telegram.org,t.me,telegram.me,telesco.pe,tg.dev"
    [twitch]="twitch.tv,ttvnw.net,jtvnw.net,twitchcdn.net"
)

combine_usage() {
    cat <<'EOF'
Использование: zapret combine КОМУ=СТРАТЕГИЯ ... [default=СТРАТЕГИЯ] [name=ИМЯ]
  КОМУ:       набор (discord, youtube, telegram, twitch),
              свои домены через запятую (example.com,site.org),
              list:файл (hostlist из /opt/zapret/ipset или полный путь)
  СТРАТЕГИЯ:  имя из zapret strategies или параметры tpws в кавычках ("--split-pos=1 --oob")
  default=    стратегия для всего остального (последний профиль, без фильтра)
  name=       имя новой стратегии (по умолчанию combo_N)
Пример: zapret combine discord=split_host_oob youtube=ultimate_universal default=oob
EOF
}

# параметры стратегии по имени или как есть
combine_opts_of() {
    local s="$1"
    if [[ "$s" == -* ]]; then echo "$s"
    elif [[ -n "${STRATEGIES[$s]+x}" ]]; then echo "${STRATEGIES[$s]}"
    else return 1; fi
}

combine_strategies() {
    [[ $# -eq 0 ]] && { combine_usage; return 1; }
    local name="" def="" a key val
    local -a keys=() vals=()
    for a in "$@"; do
        [[ "$a" == *=* ]] || { echo -e "${RED}Непонятный аргумент: $a${NC}"; combine_usage; return 1; }
        key="${a%%=*}"; val="${a#*=}"
        case "$key" in
            name)    name="$val" ;;
            default) def="$val" ;;
            *)       keys+=("$key"); vals+=("$val") ;;
        esac
    done
    [[ ${#keys[@]} -eq 0 ]] && { echo -e "${RED}Нужна хотя бы одна пара КОМУ=СТРАТЕГИЯ.${NC}"; return 1; }

    local -a profiles=() report=()
    local i opts filter seg n
    for ((i=0; i<${#keys[@]}; i++)); do
        key="${keys[$i]}"; val="${vals[$i]}"
        if ! opts=$(combine_opts_of "$val"); then
            echo -e "${RED}Нет стратегии '$val'. Список: zapret strategies${NC}"; return 1
        fi
        # у стратегии уже есть свой фильтр по доменам/IP: фильтры сложатся, и разделения не получится
        if [[ "$opts" == *--hostlist* || "$opts" == *--ipset* ]]; then
            echo -e "${RED}Стратегия '$val' уже содержит --hostlist/--ipset (например, мультистратегия). Для '$key' выберите обычную.${NC}"
            return 1
        fi
        if [[ -n "${COMBINE_PRESETS[$key]+x}" ]]; then
            filter="--hostlist-domains=${COMBINE_PRESETS[$key]}"
        elif [[ "$key" == list:* ]]; then
            local f="${key#list:}"
            [[ "$f" == /* ]] || f="$HOSTLIST_DIR/$f"
            [[ -r "$f" ]] || { echo -e "${RED}Нет файла $f${NC}"; return 1; }
            filter="--hostlist=$f"
        elif [[ "$key" =~ ^[A-Za-z0-9.,_-]+$ && "$key" == *.* ]]; then
            filter="--hostlist-domains=$key"
        else
            echo -e "${RED}Не понимаю '$key': ожидается набор (${!COMBINE_PRESETS[*]}), домены через запятую или list:файл.${NC}"
            return 1
        fi
        n=0
        # стратегия может сама состоять из нескольких профилей: фильтр дописывается в каждый
        while IFS= read -r seg; do
            profiles+=("$seg $filter"); n=$((n+1))
        done < <(bc_split_profiles "$opts")
        report+=("$(printf '%-28s -> %s (%d проф.)' "$key" "$val" $n)")
    done
    if [[ -n "$def" ]]; then
        if ! opts=$(combine_opts_of "$def"); then
            echo -e "${RED}Нет стратегии '$def'.${NC}"; return 1
        fi
        while IFS= read -r seg; do profiles+=("$seg"); done < <(bc_split_profiles "$opts")
        report+=("$(printf '%-28s -> %s' "остальное" "$def")")
    fi

    local combined="" p
    for p in "${profiles[@]}"; do
        [[ -n "$combined" ]] && combined+=" --new "
        combined+="$p"
    done
    # shellcheck disable=SC2086
    local out
    if ! out=$("$TPWS_BIN" --port=1 --bind-addr=127.0.0.1 --socks $ZT_UIDOPT $combined --dry-run 2>&1); then
        echo -e "${RED}tpws не принял собранную стратегию:${NC}"
        echo "$out" | grep -vE "version|^$|desync profile|^Running as|summary|^profile [0-9]+ " | tail -3 | sed 's/^/   /'
        return 1
    fi

    if [[ -z "$name" ]]; then
        n=1; while [[ -n "${STRATEGIES[combo_$n]+x}" ]]; do n=$((n+1)); done
        name="combo_$n"
    elif [[ ! "$name" =~ ^[A-Za-z0-9_-]+$ ]]; then
        echo -e "${RED}Имя стратегии: латиница, цифры, _ и -.${NC}"; return 1
    elif [[ -n "${STRATEGIES[$name]+x}" && "$name" != combo_* ]]; then
        echo -e "${RED}Стратегия '$name' уже есть. Выберите другое имя (перезаписывать можно только combo_*).${NC}"; return 1
    fi
    [[ -z "${STRATEGIES[$name]+x}" ]] && STRATEGY_NAMES+=("$name")
    STRATEGIES["$name"]="$combined"
    save_strategies

    echo -e "${GREEN}Стратегия '$name' собрана (один процесс tpws, ${#profiles[@]} профилей):${NC}"
    printf '  %s\n' "${report[@]}"
    [[ -z "$def" ]] && echo -e "${YELLOW}  default не задан: остальной трафик пойдёт без обхода DPI.${NC}"
    echo "Применить: zapret strategy $name    Проверить: zapret blockcheck и выбрать её в списке"
}
