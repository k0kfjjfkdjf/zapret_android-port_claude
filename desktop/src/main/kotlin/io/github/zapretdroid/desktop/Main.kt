@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zapretdroid.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.ArgTemplates
import io.github.zapretdroid.core.EngineCommand
import io.github.zapretdroid.core.PresetFile
import io.github.zapretdroid.test.ProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

fun main() {
    AppLog.console = { _, message -> println(message) }
    AppLog.init(File(Paths.data, "logs"), engineProcess = false)
    AppLog.i("ByeZapret desktop started, assets ${Paths.assets}, engine ${Paths.engine()}")
    application {
        val model = remember { AppModel() }
        Window(
            onCloseRequest = {
                model.engine.stop()
                exitApplication()
            },
            title = "ByeZapret",
            state = rememberWindowState(width = 1080.dp, height = 760.dp),
        ) {
            App(model)
        }
    }
}

enum class Screen(val title: String, val icon: ImageVector) {
    HOME("Главная", Icons.Filled.Home),
    STRATEGY("Стратегия", Icons.Filled.Edit),
    CHECK("Проверка", Icons.Filled.Search),
    LOG("Журнал", Icons.Filled.Menu),
    SETTINGS("Настройки", Icons.Filled.Settings),
}

class AppModel {
    var settings by mutableStateOf(DesktopSettings.load())
        private set
    val engine = EngineRunner()
    val presets = Presets.load()
    var screen by mutableStateOf(Screen.HOME)
    var startError by mutableStateOf<String?>(null)
    var checkRows by mutableStateOf<List<CheckRow>>(emptyList())
    var checking by mutableStateOf(false)
    var checkError by mutableStateOf<String?>(null)

    init {
        ArgTemplates.refs = DesktopRefs { settings.proxies }
    }

    fun update(s: DesktopSettings) {
        settings = s
        runCatching { s.save() }.onFailure { AppLog.w("settings not saved: ${it.message}") }
    }

    val preset: Preset? get() = presets.firstOrNull { it.id == settings.preset }

    /** The strategy in use: the preset's command line, or the user's own text. */
    val strategyText: String get() = preset?.args ?: settings.custom

    fun command(): EngineCommand.Built = buildCommand(strategyText, settings.network)

    fun start() {
        startError = null
        val binary = Paths.engine()
        if (binary == null) {
            startError = "Движок не найден. Соберите его: make -C app/src/main/cpp/engine (или укажите путь в BYEZAPRET_ENGINE)."
            return
        }
        val built = command()
        if (built.errors.isNotEmpty()) {
            startError = "В стратегии ошибки: " + built.errors.joinToString("; ")
            return
        }
        engine.start(binary, settings.port, built.args)
    }

    /** Applies a changed strategy or port to a running engine. */
    fun restartIfRunning() {
        if (engine.state.value is EngineRunner.State.Running) start()
    }
}

private val Blue = Color(0xFF8AB4F8)
private val Good = Color(0xFF66BB6A)
private val Warn = Color(0xFFFFB74D)
private val Bad = Color(0xFFE57373)

@Composable
fun App(model: AppModel) {
    val colors = if (model.settings.dark) {
        darkColorScheme(primary = Blue, secondary = Blue)
    } else {
        lightColorScheme(primary = Color(0xFF1A73E8), secondary = Color(0xFF1A73E8))
    }
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    Spacer(Modifier.height(12.dp))
                    Screen.entries.forEach { s ->
                        NavigationRailItem(
                            selected = model.screen == s,
                            onClick = { model.screen = s },
                            icon = { Icon(s.icon, contentDescription = s.title) },
                            label = { Text(s.title) },
                        )
                    }
                }
                Box(Modifier.fillMaxSize().padding(24.dp)) {
                    when (model.screen) {
                        Screen.HOME -> HomeScreen(model)
                        Screen.STRATEGY -> StrategyScreen(model)
                        Screen.CHECK -> CheckScreen(model)
                        Screen.LOG -> LogScreen(model)
                        Screen.SETTINGS -> SettingsScreen(model)
                    }
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
}

@Composable
private fun Mono(text: String, color: Color = Color.Unspecified) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = color)
}

@Composable
private fun HomeScreen(model: AppModel) {
    val state by model.engine.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val (status, color) = when (val s = state) {
                        is EngineRunner.State.Running -> "Работает · SOCKS5 127.0.0.1:${s.port}" to Good
                        is EngineRunner.State.Failed -> "Остановлен: ${s.message}" to Bad
                        EngineRunner.State.Stopped -> "Остановлен" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(status, style = MaterialTheme.typography.titleLarge, color = color)
                    Text("Стратегия: ${model.preset?.title ?: "своя"}", style = MaterialTheme.typography.bodyMedium)
                    model.startError?.let { Text(it, color = Bad, modifier = Modifier.padding(top = 8.dp)) }
                }
                if (state is EngineRunner.State.Running) {
                    OutlinedButton(onClick = { model.engine.stop() }) { Text("Остановить") }
                } else {
                    Button(onClick = { model.start() }) { Text("Запустить") }
                }
            }
        }

        Text("Стратегия", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                model.presets.forEach { p ->
                    PresetRow(p.title, p.about, model.settings.preset == p.id) {
                        model.update(model.settings.copy(preset = p.id))
                        model.restartIfRunning()
                    }
                }
                PresetRow("Своя", "Текст из раздела «Стратегия»", model.settings.preset == PresetFile.CUSTOM) {
                    model.update(model.settings.copy(preset = PresetFile.CUSTOM))
                    model.restartIfRunning()
                }
            }
        }

        Text("Как пустить программы через обход", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val port = model.settings.port
                Text("Это локальный SOCKS5-прокси: обход получают программы, которым он указан.")
                Text("Firefox: Настройки → Сеть → SOCKS5 127.0.0.1, порт $port, «Отправлять DNS-запросы через прокси».")
                val chromium = "--proxy-server=\"socks5://127.0.0.1:$port\""
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Chromium, Chrome, Discord, другие на Electron — запуск с ключом:")
                        Mono(chromium)
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(chromium)) }) { Text("Копировать") }
                }
                Text(
                    "Голос в Discord и звонки идут по UDP: через прокси Chromium их не пустить, они уходят мимо обхода.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PresetRow(title: String, about: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (about.isNotEmpty()) Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StrategyScreen(model: AppModel) {
    val built = remember(model.strategyText, model.settings.network, model.settings.proxies) { model.command() }
    Column(Modifier.fillMaxSize()) {
        Title("Стратегия")
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                model.preset?.let {
                    Text(
                        "Пресет «${it.title}». Правка текста сделает его вашей стратегией.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = model.strategyText,
                    onValueChange = { model.update(model.settings.copy(preset = PresetFile.CUSTOM, custom = it)) },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    label = { Text("Аргументы ByeDPI, как в приложении: строки, # комментарии, {fake:…}, {proxy:…}, @if, @set") },
                )
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Сеть для @if", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("wifi" to "Wi-Fi / провод", "mobile" to "Мобильная").forEach { (id, label) ->
                        FilterChip(
                            selected = model.settings.network == id,
                            onClick = { model.update(model.settings.copy(network = id)) },
                            label = { Text(label) },
                        )
                    }
                }
                if (built.errors.isNotEmpty()) {
                    Text("Ошибки", style = MaterialTheme.typography.titleSmall, color = Bad)
                    built.errors.forEach { Text(it, color = Bad) }
                }
                if (built.notes.isNotEmpty()) {
                    Text("На заметку", style = MaterialTheme.typography.titleSmall, color = Warn)
                    built.notes.forEach { Text(it, color = Warn) }
                }
                if (built.errors.isEmpty()) {
                    Text("Команда движка", style = MaterialTheme.typography.titleSmall)
                    Card(Modifier.fillMaxWidth()) {
                        SelectionContainer { Box(Modifier.padding(12.dp)) { Mono(built.args.joinToString(" ")) } }
                    }
                    Button(onClick = { model.start() }) {
                        Text(if (model.engine.state.value is EngineRunner.State.Running) "Применить (перезапустить)" else "Запустить с этой стратегией")
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckScreen(model: AppModel) {
    var targets by remember { mutableStateOf(Checker.DEFAULT_TARGETS.joinToString("\n")) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Title("Проверка")
        Text(
            "Каждый адрес открывается дважды: через движок без обхода (сеть как есть) и через текущую стратегию. " +
                "«Замёрзло на N КБ» — типичная блокировка по IP после первых 16–20 КБ.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxSize().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.width(280.dp).fillMaxHeight()) {
                OutlinedTextField(
                    value = targets,
                    onValueChange = { targets = it },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    label = { Text("Адреса, по одному в строке") },
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = !model.checking,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        model.checkError = null
                        val binary = Paths.engine()
                        val built = model.command()
                        when {
                            binary == null -> model.checkError = "Движок не найден: make -C app/src/main/cpp/engine"
                            built.errors.isNotEmpty() -> model.checkError = "В стратегии ошибки: " + built.errors.joinToString("; ")
                            else -> {
                                model.checking = true
                                val list = targets.lines().map { it.trim() }.filter { it.isNotEmpty() }
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        Checker.run(binary, built.args, list, model.engine::append) { model.checkRows = it }
                                    } catch (e: Exception) {
                                        model.checkError = e.message ?: e.toString()
                                    } finally {
                                        model.checking = false
                                    }
                                }
                            }
                        }
                    },
                ) { Text(if (model.checking) "Проверяю…" else "Проверить") }
                model.checkError?.let { Text(it, color = Bad, modifier = Modifier.padding(top = 8.dp)) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text("Адрес", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text("Без обхода", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text("Со стратегией", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    }
                    HorizontalDivider()
                }
                items(model.checkRows) { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(row.host, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        Verdict(row.direct, Modifier.weight(1f))
                        Verdict(row.bypass, Modifier.weight(1f))
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun Verdict(r: ProbeResult?, modifier: Modifier) {
    Column(modifier) {
        when {
            r == null -> Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            r.ok -> Text("✓ загрузилось (${r.bytes / 1024} КБ, ${r.totalMs} мс)", color = Good)
            r.responded -> Text("◐ ответил, но замёрз", color = Warn)
            else -> Text("✗ не открылось", color = Bad)
        }
        if (r != null && !r.ok) Text(r.detail.take(120), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LogScreen(model: AppModel) {
    val lines by model.engine.log.collectAsState()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Журнал движка", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) }) { Text("Копировать") }
            TextButton(onClick = { model.engine.clearLog() }) { Text("Очистить") }
        }
        Card(Modifier.fillMaxSize().padding(top = 12.dp)) {
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(12.dp), state = listState) {
                    items(lines) { line ->
                        val color = when {
                            "block detected" in line || "FAILED" in line || "NO REPLY" in line -> Bad
                            "split cancel" in line -> Warn
                            else -> Color.Unspecified
                        }
                        Mono(line, color)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(model: AppModel) {
    var port by remember { mutableStateOf(model.settings.port.toString()) }
    var newName by remember { mutableStateOf("") }
    var newAddress by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Title("Настройки")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = port,
                onValueChange = { v ->
                    port = v.filter { it.isDigit() }.take(5)
                    port.toIntOrNull()?.takeIf { it in 1..65535 }?.let { model.update(model.settings.copy(port = it)) }
                },
                label = { Text("Порт SOCKS5") },
                singleLine = true,
                modifier = Modifier.width(180.dp),
            )
            Text("Тёмная тема")
            Switch(checked = model.settings.dark, onCheckedChange = { model.update(model.settings.copy(dark = it)) })
        }

        Text("Прокси для {proxy:имя}", style = MaterialTheme.typography.titleMedium)
        Text(
            "Например, termux = 192.168.1.5:1085: тогда {proxy:termux} в стратегии станет -C socks5://192.168.1.5:1085. " +
                "Имя «main» — для {proxy} без имени.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        model.settings.proxies.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Mono("${p.name.ifEmpty { "main" }} = ${p.address}")
                IconButton(onClick = { model.update(model.settings.copy(proxies = model.settings.proxies - p)) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Удалить")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(newName, { newName = it.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.width(160.dp))
            OutlinedTextField(newAddress, { newAddress = it.trim() }, label = { Text("ip:порт") }, singleLine = true, modifier = Modifier.width(220.dp))
            Button(
                enabled = newName.isNotEmpty() && Regex("""^[\w.\-\[\]:]+:\d{1,5}$""").matches(newAddress),
                onClick = {
                    val name = if (newName == "main") "" else newName
                    model.update(model.settings.copy(proxies = model.settings.proxies.filter { it.name != name } + Proxy(name, newAddress)))
                    newName = ""
                    newAddress = ""
                },
            ) { Text("Добавить") }
        }

        Text("Файлы", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Mono("движок: ${Paths.engine()?.absolutePath ?: "не найден — make -C app/src/main/cpp/engine"}")
        }
        Mono("фейки и пресеты: ${Paths.assets.absolutePath}")
        Mono("списки доменов ({list:имя}): ${Paths.listDir.absolutePath}")
        Mono("настройки: ${DesktopSettings.FILE.absolutePath}")
    }
}
