**ByeZapret** (бывший ZapretDroid). Тестовая сборка: обход DPI без root через VpnService (ByeDPI + hev-socks5-tunnel).

**Какой файл скачать**
- `zapretdroid-*-universal.apk` — подходит для любого устройства;
- `zapretdroid-*-arm64-v8a.apk` — для большинства современных телефонов (меньше размер);
- `armeabi-v7a` — старые 32-битные телефоны, `x86`/`x86_64` — эмуляторы.

**Важно**
- Это отладочная (debug) сборка, ставится как отдельное приложение (`io.github.zapretdroid.debug`).
  С версии 0.9.3 тестовые сборки подписаны постоянным ключом из репозитория и обновляются
  поверх друг друга; сборку старше 0.9.3 нужно один раз удалить.
- Обход проверен только на уровне сборки и тестов, не на реальном провайдере. Если стратегия
  не помогает — попробуйте другую в настройках.
- Android 7.0+.

Контрольные суммы — в `SHA256SUMS.txt`.

---

Test build of a rootless DPI bypass over VpnService (ByeDPI + hev-socks5-tunnel). Debug-signed,
installs as `io.github.zapretdroid.debug`. Pick the universal APK if unsure. Android 7.0+.
