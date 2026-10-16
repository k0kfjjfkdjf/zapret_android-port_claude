# shellcheck shell=bash
# Модуль zapret-termux: установка, сборка tpws, обновление. Подключается из termux/zapret, отдельно не запускается.

# -------------------------------------------------------------------
# Установка / Удаление / Обновление
# -------------------------------------------------------------------
remove_zapret() {
    echo -e "${YELLOW}Удаление zapret из $ZAPRET_DIR...${NC}"
    stop_zapret &>/dev/null
    disable_autostart &>/dev/null

    if [[ -d "$ZAPRET_DIR" ]]; then
        if can_write_to_dir "$ZAPRET_DIR"; then
            rm -rf "$ZAPRET_DIR"
        else
            run_with_sudo rm -rf "$ZAPRET_DIR" || {
                echo -e "${RED}Не удалось удалить $ZAPRET_DIR.${NC}"
                return 1
            }
        fi
        echo -e "${GREEN}Zapret удалён.${NC}"
    else
        echo -e "${YELLOW}Zapret не установлен.${NC}"
    fi
}

# -------------------------------------------------------------------
# Патч tpws: SOCKS5 UDP ASSOCIATE + UDP-десинхронизация без root
# (файл patches/tpws-socks-udp.patch)
# -------------------------------------------------------------------
apply_udp_patch() {
    local tmp="$ZT_PATCH"
    if [[ ! -f "$tmp" ]]; then
        echo -e "${RED}Нет файла патча $tmp${NC}"
        return 1
    fi
    if git -C "$ZAPRET_DIR" apply --check "$tmp" 2>/dev/null; then
        git -C "$ZAPRET_DIR" apply "$tmp"
    elif git -C "$ZAPRET_DIR" apply --3way "$tmp" 2>/dev/null; then
        echo -e "${YELLOW}Патч применён с 3-way слиянием (upstream изменился).${NC}"
    else
        echo -e "${RED}Патч UDP не накладывается на текущую версию zapret. Будет собран tpws без UDP.${NC}"
        return 1
    fi
    echo -e "${GREEN}Патч UDP применён.${NC}"
}

build_tpws() {
    echo -e "${GREEN}Сборка tpws...${NC}"
    make -C "$ZAPRET_DIR/tpws" clean &>/dev/null
    if ! make -C "$ZAPRET_DIR/tpws" -j"$(nproc 2>/dev/null || echo 2)"; then
        echo -e "${RED}Ошибка сборки tpws.${NC}"
        return 1
    fi
    echo -e "${GREEN}tpws собран: $TPWS_BIN${NC}"
    tpws_supports_udp && echo -e "${GREEN}Поддержка UDP: есть${NC}" || echo -e "${YELLOW}Поддержка UDP: нет${NC}"
}

check_deps() {
    # tpws нужны компилятор, make, git, zlib и libcap (netfilter не нужен — это для nfqws)
    # учитываем голый rootfs proot-distro: там может не быть procps (pgrep/pkill) и ca-certificates
    local pm="" missing=()
    if command -v apt-get &>/dev/null; then pm="apt"
    elif command -v pacman &>/dev/null; then pm="pacman"
    elif command -v apk &>/dev/null; then pm="apk"
    fi

    case "$pm" in
        apt)
            local p
            for p in git make gcc libc6-dev zlib1g-dev libcap-dev procps ca-certificates gzip curl; do
                dpkg -s "$p" &>/dev/null || missing+=("$p")
            done ;;
        pacman)
            local p
            for p in git make gcc zlib libcap procps-ng ca-certificates gzip curl; do
                pacman -Q "$p" &>/dev/null || missing+=("$p")
            done ;;
        apk)
            local p
            for p in git make gcc musl-dev zlib-dev libcap-dev procps ca-certificates gzip coreutils bash curl; do
                apk info -e "$p" &>/dev/null || missing+=("$p")
            done ;;
        *)
            local c
            for c in git make gcc pgrep pkill base64 gunzip curl; do
                command -v "$c" &>/dev/null || missing+=("$c")
            done
            if [[ ${#missing[@]} -gt 0 ]]; then
                echo -e "${RED}Неизвестный пакетный менеджер. Установите вручную: ${missing[*]} + zlib и libcap (dev)${NC}"
                return 1
            fi
            return 0 ;;
    esac

    [[ ${#missing[@]} -eq 0 ]] && return 0

    echo -e "${YELLOW}Отсутствуют пакеты: ${missing[*]}${NC}"
    if [[ $INTERACTIVE -eq 1 ]]; then
        read -p "Установить их через $pm? (y/n) " -n 1 -r
        echo
        [[ $REPLY =~ ^[Yy]$ ]] || { echo -e "${RED}Установка прервана.${NC}"; return 1; }
    fi
    case "$pm" in
        apt)    run_with_sudo apt-get update && DEBIAN_FRONTEND=noninteractive run_with_sudo apt-get install -y "${missing[@]}" ;;
        # без -Sy: на Arch это частичное обновление. Если база пакетов пустая или устарела - полное -Syu
        pacman) run_with_sudo pacman -S --needed --noconfirm "${missing[@]}" \
                    || run_with_sudo pacman -Syu --needed --noconfirm "${missing[@]}" ;;
        apk)    run_with_sudo apk add "${missing[@]}" ;;
    esac || {
        echo -e "${RED}Ошибка установки зависимостей.${NC}"
        return 1
    }
}

install_self() {
    # /usr/local/bin/zapret -> termux/zapret в клоне репозитория (заменяет и старый однофайловый скрипт)
    local target="$ZT_HOME/zapret"
    [[ "$(readlink -f "$SCRIPT_PATH" 2>/dev/null)" == "$(readlink -f "$target")" ]] && return 0
    if can_write_to_dir "$(dirname "$SCRIPT_PATH")"; then
        ln -sfn "$target" "$SCRIPT_PATH.new" && mv -Tf "$SCRIPT_PATH.new" "$SCRIPT_PATH"
    else
        run_with_sudo ln -sfn "$target" "$SCRIPT_PATH.new" && run_with_sudo mv -Tf "$SCRIPT_PATH.new" "$SCRIPT_PATH"
    fi
    echo -e "${GREEN}Команда zapret -> $target${NC}"
}

# обновление самого zapret-termux из git. При изменениях перезапускает update уже новой версией.
self_update() {
    [[ -n "$ZT_NO_SELF_UPDATE" ]] && return 0
    local top before after
    top=$(zt_repo_dir) || { echo -e "${YELLOW}zapret-termux установлен не из git, самообновление пропущено.${NC}"; return 0; }
    before=$(git -C "$top" rev-parse HEAD)
    echo -e "${GREEN}Обновление zapret-termux ($(zt_version))...${NC}"
    if ! git -C "$top" pull -q --ff-only; then
        echo -e "${YELLOW}Не удалось обновить zapret-termux (сеть или локальные изменения). Продолжаю с текущей версией.${NC}"
        return 0
    fi
    after=$(git -C "$top" rev-parse HEAD)
    if [[ "$before" != "$after" ]]; then
        echo -e "${GREEN}zapret-termux обновлён:${NC}"
        git -C "$top" log --oneline "$before..$after" | head -10
        exec env ZT_NO_SELF_UPDATE=1 bash "$ZT_HOME/zapret" update
    fi
    echo "zapret-termux уже свежий."
}

# служба, созданная старой версией (без PIDFile), пересоздаётся с текущими настройками
zt_refresh_unit() {
    [[ -f "$ZT_UNIT" ]] && zt_use_systemd || return 0
    grep -q '^PIDFile=' "$ZT_UNIT" && return 0
    enable_autostart >/dev/null && echo -e "${GREEN}Служба zapret-termux обновлена (PIDFile).${NC}"
}

install_zapret() {
    echo -e "${BLUE}Установка zapret в $ZAPRET_DIR${NC}"

    if ! can_write_to_dir "/opt"; then
        echo -e "${RED}Нет прав на запись в /opt. Выполните скрипт с sudo или от root.${NC}"
        return 1
    fi

    echo -e "${YELLOW}Проверка зависимостей...${NC}"
    check_deps || return 1

    # клонируем во временную папку, чтобы при ошибке сети не потерять рабочую установку
    local tmpdir="$ZAPRET_DIR.new"
    rm -rf "$tmpdir"
    echo -e "${GREEN}Клонирование репозитория zapret...${NC}"
    git clone --depth 1 "$ZAPRET_REPO" "$tmpdir" || {
        rm -rf "$tmpdir"
        echo -e "${RED}Ошибка клонирования. Текущая установка не тронута.${NC}"
        return 1
    }
    # переустановка: останавливаем zapret, но автозапуск не трогаем (remove_zapret его отключал)
    local was_running
    was_running=$(zt_pause)
    if [[ -d "$ZAPRET_DIR" ]]; then
        if can_write_to_dir "$ZAPRET_DIR"; then rm -rf "$ZAPRET_DIR"; else run_with_sudo rm -rf "$ZAPRET_DIR"; fi
    fi
    mv "$tmpdir" "$ZAPRET_DIR" || return 1

    apply_udp_patch
    build_tpws || return 1
    install_self
    zt_refresh_unit

    load_strategies
    if tpws_supports_udp && [[ ! -f "$UDP_FILE" ]]; then
        set_use_udp "1"
        echo -e "${GREEN}UDP включён по умолчанию (профиль: $(get_udp_profile)).${NC}"
    fi
    if [[ "$was_running" != "0" ]]; then
        zt_resume "$was_running"
        echo -e "${GREEN}Переустановка завершена.${NC}"
    else
        echo -e "${GREEN}Установка завершена. Запуск: zapret start${NC}"
    fi
}

update_zapret() {
    self_update
    if ! is_zapret_installed || [[ ! -d "$ZAPRET_DIR/.git" ]]; then
        echo -e "${YELLOW}zapret не установлен, выполняю установку.${NC}"
        install_zapret
        return
    fi
    check_deps || return 1
    echo -e "${GREEN}Обновление репозитория...${NC}"
    # откатываем прошлый патч, иначе pull упрётся в локальные изменения
    git -C "$ZAPRET_DIR" checkout -- . &>/dev/null
    git -C "$ZAPRET_DIR" clean -fdq tpws &>/dev/null
    git -C "$ZAPRET_DIR" pull --ff-only || {
        echo -e "${RED}Ошибка git pull.${NC}"
        return 1
    }
    apply_udp_patch
    local was_running
    was_running=$(zt_pause)
    build_tpws || { zt_resume "$was_running"; return 1; }
    install_self
    zt_refresh_unit
    zt_resume "$was_running"
    return 0
}
