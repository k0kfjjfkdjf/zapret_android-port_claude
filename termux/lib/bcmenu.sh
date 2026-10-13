# shellcheck shell=bash
# Модуль zapret-termux: меню подбора стратегий (blockcheck). Подключается из termux/zapret, отдельно не запускается.

BCM_CONF="$HOME/.zapret_blockcheck.conf"        # настройки меню: ключ=значение
BCM_TARGETS_FILE="$HOME/.zapret_blockcheck_targets"  # цели проверки: по одной в строке

bcm_load() {
    BCM_MODE=quick; BCM_TLS=both; BCM_TIMEOUT=8; BCM_LIMIT=40; BCM_DEBUG=0; BCM_DIR="$HOME"
    BCM_SAMPLE=8; BCM_TOP=5; BCM_PAR=12
    if [[ -f "$BCM_CONF" ]]; then
        local k v
        while IFS='=' read -r k v; do
            case "$k" in
                mode) BCM_MODE="$v" ;; tls) BCM_TLS="$v" ;; timeout) BCM_TIMEOUT="$v" ;;
                limit) BCM_LIMIT="$v" ;; debug) BCM_DEBUG="$v" ;; dir) BCM_DIR="$v" ;;
                sample) BCM_SAMPLE="$v" ;; top) BCM_TOP="$v" ;; par) BCM_PAR="$v" ;;
            esac
        done < "$BCM_CONF"
    fi
    BCM_TARGETS=()
    if [[ -f "$BCM_TARGETS_FILE" ]]; then
        mapfile -t BCM_TARGETS < <(grep -v '^[[:space:]]*$' "$BCM_TARGETS_FILE")
    fi
    [[ ${#BCM_TARGETS[@]} -eq 0 ]] && BCM_TARGETS=(discord youtube)
}

bcm_save() {
    printf 'mode=%s\ntls=%s\ntimeout=%s\nlimit=%s\ndebug=%s\ndir=%s\nsample=%s\ntop=%s\npar=%s\n' \
        "$BCM_MODE" "$BCM_TLS" "$BCM_TIMEOUT" "$BCM_LIMIT" "$BCM_DEBUG" "$BCM_DIR" "$BCM_SAMPLE" "$BCM_TOP" "$BCM_PAR" > "$BCM_CONF"
    printf '%s\n' "${BCM_TARGETS[@]}" > "$BCM_TARGETS_FILE"
}

bcm_target_text() {
    case "$1" in
        discord)       echo "Discord (5 адресов)" ;;
        discord-full)  echo "Discord полный (11 адресов + большой файл)" ;;
        youtube)       echo "YouTube (4 адреса)" ;;
        hostlist:*)    echo "список ${1#hostlist:} (до $BCM_LIMIT записей)" ;;
        list:*)        echo "список cfgs-${1#list:}.txt (до $BCM_LIMIT записей)" ;;
        file:*)        echo "файл ${1#file:} (до $BCM_LIMIT записей)" ;;
        *)             echo "$1" ;;
    esac
}

bcm_add_target() {
    local t="$1" x
    for x in "${BCM_TARGETS[@]}"; do [[ "$x" == "$t" ]] && return 0; done
    BCM_TARGETS+=("$t")
}

bcm_show() {
    local n_str=${#STRATEGY_NAMES[@]}
    echo -e "${BLUE}══════════ Подбор стратегий (blockcheck) ══════════${NC}"
    echo "Что проверять:"
    local t
    for t in "${BCM_TARGETS[@]}"; do echo "   • $(bcm_target_text "$t")"; done
    if [[ "$BCM_MODE" == "full" ]]; then
        echo "Режим:            full (ваши $n_str стратегий + ~70 сгенерированных)"
    else
        echo "Режим:            quick (ваши $n_str стратегий)"
    fi
    case "$BCM_TLS" in
        12) echo "TLS:              только 1.2" ;;
        13) echo "TLS:              только 1.3" ;;
        *)  echo "TLS:              1.2 и 1.3 отдельно" ;;
    esac
    echo "Таймаут:          ${BCM_TIMEOUT} с на адрес"
    echo "Лимит из списков: $BCM_LIMIT записей с файла"
    if [[ "$BCM_SAMPLE" -gt 0 ]]; then
        echo "Два этапа:        вкл (выборка $BCM_SAMPLE адресов, затем $BCM_TOP лучших на всём списке)"
    else
        echo "Два этапа:        выкл (все стратегии на всех адресах)"
    fi
    echo "Параллельно:      $BCM_PAR запросов curl"
    case "$BCM_DEBUG" in
        1) echo -e "Отладка:          ${GREEN}компактная${NC} (curl -v только неудачных, лог tpws финалистов)" ;;
        2) echo -e "Отладка:          ${YELLOW}полная${NC} (всё; при больших списках архив будет большим)" ;;
        *) echo "Отладка:          выкл" ;;
    esac
    echo "Папка отчёта:     $BCM_DIR"
    echo -e "${BLUE}════════════════════════════════════════════════════${NC}"
}

bcm_pick_presets() {
    echo " 1) Discord          (сайт, шлюз, CDN, медиа)"
    echo " 2) Discord полный   (11 адресов + большой файл 256 КБ)"
    echo " 3) YouTube"
    read -p "Номера через пробел (0 - отмена): " -a nums
    local n
    for n in "${nums[@]}"; do
        case "$n" in
            1) bcm_add_target discord ;;
            2) bcm_add_target discord-full ;;
            3) bcm_add_target youtube ;;
        esac
    done
}

bcm_pick_hostlists() {
    if [[ ! -d "$HOSTLIST_DIR" ]]; then
        echo -e "${RED}Нет папки $HOSTLIST_DIR${NC}"; return 1
    fi
    local -a files=()
    mapfile -t files < <(find "$HOSTLIST_DIR" -maxdepth 1 -type f \( -name '*.txt' -o -name '*.gz' -o -name '*.lst' \) -printf '%f\n' 2>/dev/null | sort)
    if [[ ${#files[@]} -eq 0 ]]; then
        echo -e "${YELLOW}В $HOSTLIST_DIR нет списков. Скачайте: zapret lists-update${NC}"; return 1
    fi
    echo "Списки в $HOSTLIST_DIR (домены, IP и подсети берутся как есть):"
    local i=1 f cnt
    for f in "${files[@]}"; do
        if [[ "$f" == *.gz ]]; then cnt=$(zcat "$HOSTLIST_DIR/$f" 2>/dev/null | grep -cv '^[[:space:]]*\(#\|$\)')
        else cnt=$(grep -cv '^[[:space:]]*\(#\|$\)' "$HOSTLIST_DIR/$f" 2>/dev/null); fi
        printf ' %2d) %-36s %6s записей\n' $i "$f" "$cnt"
        i=$((i+1))
    done
    read -p "Номера через пробел (0 - отмена): " -a nums
    local n
    for n in "${nums[@]}"; do
        [[ "$n" =~ ^[0-9]+$ ]] && (( n>=1 && n<=${#files[@]} )) && bcm_add_target "hostlist:${files[$((n-1))]}"
    done
}

bcm_pick_file() {
    read -e -p "Путь к файлу (домены/IP/подсети, можно .gz): " f
    [[ -z "$f" ]] && return 0
    f="${f/#\~/$HOME}"
    if [[ -f "$f" ]]; then bcm_add_target "file:$f"; else echo -e "${RED}Нет файла $f${NC}"; fi
}

bcm_enter_manual() {
    echo "Домены, IP или подсети через пробел (например: discord.com 162.159.128.233 104.16.0.0/13):"
    read -r -a items
    local x
    for x in "${items[@]}"; do
        if [[ "$x" == */* && "$x" != http* ]]; then
            # подсеть: через временный однострочный файл, чтобы взять первый адрес
            bcm_add_target "file:$(bcm_inline_file "$x")"
        else
            bcm_add_target "$x"
        fi
    done
}

# подсеть, введённая вручную, сохраняется в ~/.zapret_blockcheck_nets/<имя>
bcm_inline_file() {
    local d="$HOME/.zapret_blockcheck_nets" name
    mkdir -p "$d"
    name=$(echo "$1" | tr '/:' '__')
    echo "$1" > "$d/$name"
    echo "$d/$name"
}

bcm_remove_targets() {
    local i=1 t
    for t in "${BCM_TARGETS[@]}"; do printf ' %2d) %s\n' $i "$(bcm_target_text "$t")"; i=$((i+1)); done
    echo "  a) удалить все"
    read -p "Номера через пробел (0 - отмена): " -a nums
    if [[ "${nums[0]}" == "a" ]]; then BCM_TARGETS=(); return; fi
    local -a keep=() del=" ${nums[*]} "
    for ((i=0; i<${#BCM_TARGETS[@]}; i++)); do
        [[ "$del" == *" $((i+1)) "* ]] || keep+=("${BCM_TARGETS[$i]}")
    done
    BCM_TARGETS=("${keep[@]}")
}

bcm_pick_dir() {
    echo " 1) $HOME (домашняя папка Ubuntu)"
    [[ -d /sdcard ]] && echo " 2) /sdcard/Download/zapret (видно из Android: файловый менеджер, отправка)"
    echo " 3) свой путь"
    read -p "Выбор: " c
    case "$c" in
        1) BCM_DIR="$HOME" ;;
        2) [[ -d /sdcard ]] && BCM_DIR="/sdcard/Download/zapret" ;;
        3) read -e -p "Папка: " d; [[ -n "$d" ]] && BCM_DIR="${d/#\~/$HOME}" ;;
    esac
    if ! mkdir -p "$BCM_DIR" 2>/dev/null || [[ ! -w "$BCM_DIR" ]]; then
        echo -e "${RED}Нет доступа на запись в $BCM_DIR.${NC}"
        [[ "$BCM_DIR" == /sdcard* ]] && echo "Для /sdcard войдите так: proot-distro login ubuntu --bind /sdcard (и выполните termux-setup-storage в Termux)."
        BCM_DIR="$HOME"
    fi
}

bcm_run() {
    if [[ ${#BCM_TARGETS[@]} -eq 0 ]]; then
        echo -e "${RED}Список целей пуст.${NC}"; return 1
    fi
    local -a args=("$BCM_MODE" "dir=$BCM_DIR" "timeout=$BCM_TIMEOUT" "limit=$BCM_LIMIT" "sample=$BCM_SAMPLE" "top=$BCM_TOP" "par=$BCM_PAR")
    case "$BCM_TLS" in 12) args+=(tls12) ;; 13) args+=(tls13) ;; esac
    case "$BCM_DEBUG" in 1) args+=(debug) ;; 2) args+=(debug=full) ;; esac
    args+=("${BCM_TARGETS[@]}")
    blockcheck_zapret "${args[@]}"
}

blockcheck_menu() {
    INTERACTIVE=1
    bcm_load
    while true; do
        clear
        bcm_show
        echo " 1) Добавить набор (Discord, Discord полный, YouTube)"
        echo " 2) Добавить список из $HOSTLIST_DIR (hostlist / ipset)"
        echo " 3) Добавить свой файл"
        echo " 4) Ввести домены / IP / подсети вручную"
        echo " 5) Убрать цели"
        echo " 6) Режим: quick / full"
        echo " 7) Версии TLS"
        echo " 8) Таймаут"
        echo " 9) Лимит записей из списков"
        echo "10) Отладка: выкл / компактная / полная"
        echo "11) Папка для отчёта и логов"
        echo "12) Два этапа: размер выборки и число финалистов"
        echo "13) Параллельность curl"
        echo "14) ▶ Запустить проверку"
        echo " 0) Назад"
        echo ""
        read -p "Выбор: " ch || { echo; bcm_save; return 0; }   # EOF: выйти, а не крутиться
        case "$ch" in
            1) bcm_pick_presets ;;
            2) bcm_pick_hostlists ;;
            3) bcm_pick_file ;;
            4) bcm_enter_manual ;;
            5) bcm_remove_targets ;;
            6) [[ "$BCM_MODE" == "quick" ]] && BCM_MODE=full || BCM_MODE=quick ;;
            7) case "$BCM_TLS" in both) BCM_TLS=12 ;; 12) BCM_TLS=13 ;; *) BCM_TLS=both ;; esac ;;
            8) read -p "Таймаут на адрес, секунд (3-30): " v
               [[ "$v" =~ ^[0-9]+$ ]] && (( v>=3 && v<=30 )) && BCM_TIMEOUT=$v ;;
            9) read -p "Сколько записей брать из каждого списка (1-500): " v
               [[ "$v" =~ ^[0-9]+$ ]] && (( v>=1 && v<=500 )) && BCM_LIMIT=$v ;;
            10) case "$BCM_DEBUG" in 0) BCM_DEBUG=1 ;; 1) BCM_DEBUG=2 ;; *) BCM_DEBUG=0 ;; esac ;;
            11) bcm_pick_dir ;;
            12) read -p "Адресов в выборке первого этапа (0 - выключить два этапа, 3-50): " v
                [[ "$v" =~ ^[0-9]+$ ]] && (( v==0 || (v>=3 && v<=50) )) && BCM_SAMPLE=$v
                if [[ "$BCM_SAMPLE" -gt 0 ]]; then
                    read -p "Сколько лучших стратегий проверять на полном списке (1-20): " v
                    [[ "$v" =~ ^[0-9]+$ ]] && (( v>=1 && v<=20 )) && BCM_TOP=$v
                fi ;;
            13) read -p "Сколько запросов curl одновременно (2-64): " v
                [[ "$v" =~ ^[0-9]+$ ]] && (( v>=2 && v<=64 )) && BCM_PAR=$v ;;
            14) bcm_save; bcm_run; press_any_key ;;
            0|q) bcm_save; return 0 ;;
            *) ;;
        esac
        bcm_save
    done
}
