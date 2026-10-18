package io.github.zapretdroid

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.zapretdroid.classic.ClassicBridge
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.SavedStrategies
import io.github.zapretdroid.core.Settings
import io.github.zapretdroid.core.Strategies
import io.github.zapretdroid.core.Updater
import io.github.zapretdroid.service.VpnState
import io.github.zapretdroid.service.VpnStatus
import io.github.zapretdroid.ui.AppRoutesScreen
import io.github.zapretdroid.ui.AppsScreen
import io.github.zapretdroid.ui.AutoHostScreen
import io.github.zapretdroid.ui.CollectScreen
import io.github.zapretdroid.ui.DnsServersScreen
import io.github.zapretdroid.ui.DeepCheckScreen
import io.github.zapretdroid.ui.HomeScreen
import io.github.zapretdroid.ui.HostListEditorScreen
import io.github.zapretdroid.ui.HostListsScreen
import io.github.zapretdroid.ui.LogsScreen
import io.github.zapretdroid.ui.MonitorScreen
import io.github.zapretdroid.ui.SelfCheckScreen
import io.github.zapretdroid.ui.SettingsScreen
import io.github.zapretdroid.ui.TestScreen
import io.github.zapretdroid.ui.UpdateDialog
import io.github.zapretdroid.ui.theme.ZapretTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var connectRequest by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The ByeByeDPI interface was chosen last time: open it instead (it has a way back).
        if (savedInstanceState == null && ClassicBridge.isClassic(this) && !intent.getBooleanExtra(EXTRA_CONNECT, false)) {
            ClassicBridge.open(this)
            finish()
            return
        }
        connectRequest = intent.getBooleanExtra(EXTRA_CONNECT, false)
        setContent {
            ZapretTheme {
                App(
                    connectRequest = connectRequest,
                    onConnectRequestHandled = { connectRequest = false },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppLog.i("app opened")
    }

    override fun onStop() {
        AppLog.i("app went to background")
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CONNECT, false)) connectRequest = true
    }

    companion object {
        const val EXTRA_CONNECT = "connect"
    }
}

private enum class Screen { HOME, SETTINGS, APPS, LOGS, TEST, LISTS, LIST_EDIT, AUTOHOST, SELFCHECK, DEEP, COLLECT, MONITOR, DNS_SERVERS, APP_ROUTES }

@Composable
private fun App(connectRequest: Boolean, onConnectRequestHandled: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var logsReturn by rememberSaveable { mutableStateOf(Screen.HOME) }
    var editingList by rememberSaveable { mutableStateOf<String?>(null) }
    var state by remember {
        mutableStateOf(if (VpnStatus.isServiceRunning(context)) VpnState.RUNNING else VpnState.STOPPED)
    }
    var error by remember { mutableStateOf<String?>(null) }
    var tx by remember { mutableLongStateOf(0L) }
    var rx by remember { mutableLongStateOf(0L) }
    // Bumped when settings change so that the home screen re-reads them.
    var settingsVersion by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                state = VpnStatus.parseState(intent)
                intent.getStringExtra(VpnStatus.EXTRA_ERROR)?.let { error = it }
                tx = intent.getLongExtra(VpnStatus.EXTRA_TX_BYTES, 0)
                rx = intent.getLongExtra(VpnStatus.EXTRA_RX_BYTES, 0)
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(VpnStatus.ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(screen) { AppLog.i("screen: ${screen.name.lowercase()}") }

    // Updates from GitHub releases: checked at most every 12 hours, or from the settings.
    var update by remember { mutableStateOf<Updater.Release?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (!Updater.due(context)) return@LaunchedEffect
        val release = withContext(Dispatchers.IO) {
            runCatching { Updater.check(context) }.onFailure { AppLog.w("update check failed: ${it.message}") }.getOrNull()
        }
        if (release != null && !Updater.isSkipped(context, release.version)) {
            AppLog.i("update available: ${release.version}")
            update = release
        }
    }
    val checkUpdates = {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Updater.check(context) } }
            result.onSuccess { release ->
                if (release != null) update = release
                else Toast.makeText(context, context.getString(R.string.update_latest, BuildConfig.VERSION_NAME), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, context.getString(R.string.update_check_failed, it.message ?: it.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
        }
        Unit
    }

    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(context) == null) {
            AppLog.i("VPN permission granted")
            error = null
            VpnStatus.start(context)
        } else {
            AppLog.w("VPN permission denied by the user")
            error = context.getString(R.string.error_vpn_permission)
        }
    }
    val connect = {
        error = null
        val prepare = VpnService.prepare(context)
        if (prepare == null) {
            VpnStatus.start(context)
        } else {
            AppLog.i("asking for VPN permission")
            vpnPermission.launch(prepare)
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // The VPN works without notifications; the answer only affects the status notification.
        AppLog.i("notification permission ${if (granted) "granted" else "denied"}")
        connect()
    }
    val requestConnect = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            connect()
        }
    }

    LaunchedEffect(connectRequest) {
        if (connectRequest) {
            AppLog.i("connect requested from the quick settings tile")
            onConnectRequestHandled()
            if (state == VpnState.STOPPED) requestConnect()
        }
    }

    val strategyName = remember(settingsVersion) { strategyTitle(context, settings) }
    val strategyArgs = remember(settingsVersion) { settings.strategyArgs }
    val privateDns = remember(settingsVersion, state) { privateDnsServer(context) }

    // A strategy picked on the home screen while connected takes effect after a reconnect.
    var reconnect by remember { mutableStateOf(false) }
    LaunchedEffect(state, reconnect) {
        if (reconnect && state == VpnState.STOPPED) {
            // The engine process exits after a session; give it time before the next one.
            kotlinx.coroutines.delay(1000)
            reconnect = false
            requestConnect()
        }
    }
    val applyStrategy = { change: () -> Unit ->
        change()
        settingsVersion++
        if (state == VpnState.RUNNING || state == VpnState.STARTING) {
            AppLog.i("strategy changed while connected: reconnecting")
            reconnect = true
            VpnStatus.stop(context)
        }
    }

    BackHandler(enabled = screen != Screen.HOME) {
        screen = when (screen) {
            Screen.APPS, Screen.TEST, Screen.LISTS, Screen.AUTOHOST, Screen.SELFCHECK, Screen.DEEP, Screen.COLLECT, Screen.DNS_SERVERS, Screen.APP_ROUTES -> Screen.SETTINGS
            Screen.LIST_EDIT -> Screen.LISTS
            Screen.LOGS -> logsReturn
            Screen.MONITOR -> Screen.HOME
            else -> Screen.HOME
        }
    }
    val openLogs = { from: Screen ->
        logsReturn = from
        screen = Screen.LOGS
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            state = state,
            error = error,
            txBytes = tx,
            rxBytes = rx,
            strategyName = strategyName,
            strategyArgs = strategyArgs,
            privateDns = privateDns,
            onPickPreset = { id ->
                AppLog.i("home: picked preset $id")
                applyStrategy { settings.strategyId = id }
            },
            onPickCustom = { args ->
                AppLog.i("home: picked saved strategy '$args'")
                applyStrategy {
                    settings.customArgs = args
                    settings.strategyId = Strategies.CUSTOM
                    SavedStrategies.remember(context, args)
                }
            },
            onToggle = {
                AppLog.i("power button: ${if (state == VpnState.STOPPED) "connect" else "disconnect"} (state $state)")
                when (state) {
                    VpnState.STOPPED -> requestConnect()
                    VpnState.STARTING, VpnState.RUNNING -> VpnStatus.stop(context)
                    VpnState.STOPPING -> Unit
                }
            },
            onDismissError = { error = null },
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenLogs = { openLogs(Screen.HOME) },
            onOpenMonitor = { screen = Screen.MONITOR },
        )
        Screen.MONITOR -> MonitorScreen(vpnRunning = state == VpnState.RUNNING, onBack = { screen = Screen.HOME })
        Screen.SETTINGS -> SettingsScreen(
            settings = settings,
            vpnActive = state != VpnState.STOPPED,
            onBack = {
                settingsVersion++
                screen = Screen.HOME
            },
            onOpenApps = { screen = Screen.APPS },
            onOpenLogs = { openLogs(Screen.SETTINGS) },
            onOpenTest = { screen = Screen.TEST },
            onOpenLists = { screen = Screen.LISTS },
            onOpenAutoHost = { screen = Screen.AUTOHOST },
            onOpenSelfCheck = { screen = Screen.SELFCHECK },
            onOpenDeep = { screen = Screen.DEEP },
            onOpenCollect = { screen = Screen.COLLECT },
            onOpenDnsServers = { screen = Screen.DNS_SERVERS },
            onOpenAppRoutes = { screen = Screen.APP_ROUTES },
            onOpenClassic = { ClassicBridge.open(context) },
            onCheckUpdates = checkUpdates,
        )
        Screen.APPS -> AppsScreen(
            settings = settings,
            onBack = { screen = Screen.SETTINGS },
        )
        Screen.LOGS -> LogsScreen(onBack = { screen = logsReturn })
        Screen.DNS_SERVERS -> DnsServersScreen(settings = settings, onBack = { screen = Screen.SETTINGS })
        Screen.APP_ROUTES -> AppRoutesScreen(settings = settings, onBack = { screen = Screen.SETTINGS })
        Screen.LISTS -> HostListsScreen(
            onBack = { screen = Screen.SETTINGS },
            onEdit = { name ->
                editingList = name
                screen = Screen.LIST_EDIT
            },
        )
        Screen.LIST_EDIT -> HostListEditorScreen(initialName = editingList, onBack = { screen = Screen.LISTS })
        Screen.SELFCHECK -> SelfCheckScreen(settings = settings, onBack = { screen = Screen.SETTINGS })
        Screen.AUTOHOST -> AutoHostScreen(enabled = settings.autohost, onBack = { screen = Screen.SETTINGS })
        Screen.COLLECT -> CollectScreen(
            settings = settings,
            state = state,
            onStart = {
                if (state == VpnState.STOPPED) {
                    requestConnect()
                } else {
                    reconnect = true
                    VpnStatus.stop(context)
                }
            },
            onStop = { VpnStatus.stop(context) },
            onBack = { screen = Screen.SETTINGS },
        )
        Screen.DEEP -> DeepCheckScreen(
            settings = settings,
            onBack = { screen = Screen.SETTINGS },
            onApplied = {
                settingsVersion++
                Toast.makeText(context, R.string.test_applied, Toast.LENGTH_LONG).show()
            },
        )
        Screen.TEST -> TestScreen(
            settings = settings,
            onBack = { screen = Screen.SETTINGS },
            onApplied = {
                settingsVersion++
                Toast.makeText(context, R.string.test_applied, Toast.LENGTH_LONG).show()
            },
        )
    }
    update?.let { release ->
        UpdateDialog(
            release = release,
            onLater = {
                Updater.skip(context, release.version)
                update = null
            },
            onClose = { update = null },
        )
    }
}

/** The Private DNS server if Android's Private DNS is active on any network, else null. */
@Suppress("DEPRECATION")
private fun privateDnsServer(context: Context): String? = runCatching {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    val manager = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return null
    manager.allNetworks.mapNotNull { manager.getLinkProperties(it) }.firstOrNull { it.isPrivateDnsActive }
        ?.let { it.privateDnsServerName ?: context.getString(R.string.private_dns_auto) }
}.getOrNull()

private fun strategyTitle(context: Context, settings: Settings): String =
    Strategies.byId(settings.strategyId)?.let { context.getString(it.title) }
        ?: context.getString(R.string.strategy_custom)
