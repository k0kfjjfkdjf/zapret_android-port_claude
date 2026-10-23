**ByeZapret**: обход DPI без root через VpnService (ByeDPI + hev-socks5-tunnel).

**Какой файл скачать**
- `byezapret-*-universal.apk` — подходит для любого устройства;
- `byezapret-*-arm64-v8a.apk` — для большинства современных телефонов (меньше размер);
- `armeabi-v7a` — старые 32-битные телефоны, `x86`/`x86_64` — эмуляторы.

**Важно**
- Это стабильная сборка, приложение `io.github.zapretdroid`. Она ставится отдельно от тестовых
  сборок (`io.github.zapretdroid.debug`, пре-релизы), их можно держать обе; настройки переносятся
  через резервную копию (Настройки → Резервная копия).
- Обновление из приложения предлагает стабильной сборке только стабильные релизы, тестовой —
  только тестовые.
- Обход зависит от провайдера и сети. Если стратегия не помогает — подберите другую в
  приложении (подбор стратегии или глубокая проверка); подробности в `docs/` репозитория.
- Android 7.0+.

Контрольные суммы — в `SHA256SUMS.txt`. Обновление из приложения сверяет SHA-256 с этим
файлом.

---

Stable build of a rootless DPI bypass over VpnService (ByeDPI + hev-socks5-tunnel). Installs as
`io.github.zapretdroid`, next to the test builds (`.debug`). Pick the universal APK if unsure.
Android 7.0+.
