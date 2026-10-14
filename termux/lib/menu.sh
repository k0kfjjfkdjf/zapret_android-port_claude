# shellcheck shell=bash
# Модуль zapret-termux: меню и справка. Подключается из termux/zapret, отдельно не запускается.

# -------------------------------------------------------------------
# Меню
# -------------------------------------------------------------------
show_menu() {
    INTERACTIVE=1
    while true; do
        clear
        echo -e "${BLUE}══════════════ zapret (tpws SOCKS5) ══════════════${NC}"
        status_zapret
        echo " 1) Запустить"
        echo " 2) Остановить"
        echo " 3) Перезапустить"
        echo " 4) Выбрать стратегию (TCP)"
        echo " 5) Добавить стратегию (TCP)"
        echo " 6) Вкл/выкл UDP"
        echo " 7) Выбрать UDP-профиль"
        echo " 8) Изменить порт"
        echo " 9) Вкл/выкл hostlist"
        echo "10) Выбрать файл hostlist"
        echo "11) Вкл/выкл автозапуск"
        echo "12) Установить / переустановить"
        echo "13) Обновить (скрипты из репозитория + zapret + сборка)"
        echo "14) Удалить"
        echo "15) Отладка (лог + сводка UDP)"
        echo "16) Подобрать стратегию (blockcheck)"
        echo "17) Обновить списки доменов (zapret.cfgs)"
        echo " 0) Выход"
        echo ""
        read -p "Выбор: " ch || { echo; exit 0; }   # EOF: выйти
        case "$ch" in
            1) start_zapret ;;
            2) stop_zapret ;;
            3) restart_zapret ;;
            4) select_strategy ;;
            5) add_strategy ;;
            6) toggle_udp ;;
            7) set_udp_profile ;;
            8) change_port ;;
            9) toggle_hostlist ;;
            10) select_hostlist_file ;;
            11) if grep -q "zapret start" "$BASHRC" 2>/dev/null; then disable_autostart; else enable_autostart; fi ;;
            12) install_zapret ;;
            13) update_zapret ;;
            14) read -p "Точно удалить? (y/n) " -n 1 -r; echo; [[ $REPLY =~ ^[Yy]$ ]] && remove_zapret ;;
            15) debug_zapret ;;
            16) blockcheck_menu; INTERACTIVE=1; continue ;;
            17) update_lists ;;
            0|q) exit 0 ;;
            *) echo -e "${RED}Неверный выбор.${NC}" ;;
        esac
        press_any_key
    done
}

show_help() {
    cat <<HELP
Использование: zapret [команда]

  (без аргументов) | menu      интерактивное меню
  start | stop | restart | status | info
  strategies                   список TCP-стратегий
  strategy <name>              выбрать TCP-стратегию
  set-port <port> [-y]         сменить порт SOCKS
  hostlist [-y]                вкл/выкл hostlist
  hostlist-list | hostlist-check
  hostlist-select <file>
  udp on|off|toggle            UDP через SOCKS5 UDP ASSOCIATE
  udp-profiles                 список UDP-профилей
  udp-profile <name>           выбрать UDP-профиль
  udp-custom "<параметры>"     свой UDP-профиль
  autostart on|off
  install | remove
  update                       обновить скрипты из репозитория, zapret, патч и пересобрать tpws
  version                      версия zapret-termux (ветка@коммит)
  cmd                          показать итоговую команду tpws
  debug                        запустить с логом (Ctrl+C - стоп), показать сводку UDP
  blockcheck                   меню подбора стратегий (цели, режим, TLS, отладка, папка для логов)
  blockcheck [quick|full] [debug|debug=full] [tls12|tls13] [dir=папка] [timeout=N] [limit=N]
             [sample=N top=N] [par=N] [keepdead] [multi]
             [discord|discord-full|youtube] [list:имя] [hostlist:файл] [file:путь] [домен|IP...] [--apply]
                               подобрать TCP-стратегию (по умолчанию: quick discord youtube, TLS1.2 и 1.3)
  lists-update                 скачать списки доменов из Snowy-Fluffy/zapret.cfgs (cfgs-*.txt)
HELP
}
