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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.Settings
import io.github.zapretdroid.core.Strategies
import io.github.zapretdroid.service.VpnState
import io.github.zapretdroid.service.VpnStatus
import io.github.zapretdroid.ui.AppsScreen
import io.github.zapretdroid.ui.AutoHostScreen
import io.github.zapretdroid.ui.HomeScreen
import io.github.zapretdroid.ui.HostListEditorScreen
import io.github.zapretdroid.ui.HostListsScreen
import io.github.zapretdroid.ui.LogsScreen
import io.github.zapretdroid.ui.SelfCheckScreen
import io.github.zapretdroid.ui.SettingsScreen
import io.github.zapretdroid.ui.TestScreen
import io.github.zapretdroid.ui.theme.ZapretTheme

class MainActivity : ComponentActivity() {
    private var connectRequest by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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

private enum class Screen { HOME, SETTINGS, APPS, LOGS, TEST, LISTS, LIST_EDIT, AUTOHOST, SELFCHECK }

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

    BackHandler(enabled = screen != Screen.HOME) {
        screen = when (screen) {
            Screen.APPS, Screen.TEST, Screen.LISTS, Screen.AUTOHOST, Screen.SELFCHECK -> Screen.SETTINGS
            Screen.LIST_EDIT -> Screen.LISTS
            Screen.LOGS -> logsReturn
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
        )
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
        )
        Screen.APPS -> AppsScreen(
            settings = settings,
            onBack = { screen = Screen.SETTINGS },
        )
        Screen.LOGS -> LogsScreen(onBack = { screen = logsReturn })
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
        Screen.TEST -> TestScreen(
            settings = settings,
            onBack = { screen = Screen.SETTINGS },
            onApplied = {
                settingsVersion++
                Toast.makeText(context, R.string.test_applied, Toast.LENGTH_LONG).show()
            },
        )
    }
}

private fun strategyTitle(context: Context, settings: Settings): String =
    Strategies.byId(settings.strategyId)?.let { context.getString(it.title) }
        ?: context.getString(R.string.strategy_custom)
