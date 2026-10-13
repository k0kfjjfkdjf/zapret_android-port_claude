# shellcheck shell=bash
# Модуль zapret-termux: списки доменов из zapret.cfgs. Подключается из termux/zapret, отдельно не запускается.

# -------------------------------------------------------------------
# Списки доменов из Snowy-Fluffy/zapret.cfgs (скачиваются с GitHub)
# -------------------------------------------------------------------
CFGS_REPO="https://github.com/Snowy-Fluffy/zapret.cfgs"
CFGS_DIR="$ZAPRET_DIR/zapret.cfgs"

update_lists() {
    if ! is_zapret_installed; then
        echo -e "${RED}zapret не установлен.${NC}"; return 1
    fi
    if [[ -d "$CFGS_DIR/.git" ]]; then
        git -C "$CFGS_DIR" pull -q --ff-only || rm -rf "$CFGS_DIR"
    fi
    if [[ ! -d "$CFGS_DIR/.git" ]]; then
        git clone -q --depth 1 "$CFGS_REPO" "$CFGS_DIR" || { echo -e "${RED}Не удалось скачать $CFGS_REPO${NC}"; return 1; }
    fi
    mkdir -p "$HOSTLIST_DIR"
    local f name n=0
    for f in "$CFGS_DIR"/lists/list-*.txt; do
        [[ -f "$f" ]] || continue
        name=$(basename "$f" .txt); name="${name#list-}"
        # формат tpws: домен в строке, поддомены учитываются сами. убираем "*." и ":*", комментарии, пустые строки
        sed -e 's/#.*//' -e 's/\r//' -e 's/^\*\.//' -e 's/:\*$//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' "$f" \
            | grep -v '^$' | awk '!seen[tolower($0)]++' > "$HOSTLIST_DIR/cfgs-$name.txt"
        n=$((n+1))
    done
    echo -e "${GREEN}Обновлено списков: $n (файлы cfgs-*.txt в $HOSTLIST_DIR)${NC}"
    echo "Выбрать список: zapret hostlist-select cfgs-discord.txt, затем zapret hostlist (включить)"
    echo "Проверить стратегии по списку: zapret blockcheck list:discord"
}
