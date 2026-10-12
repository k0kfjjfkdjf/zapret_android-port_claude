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
import io.github.zapretdroid.core.Settings
import io.github.zapretdroid.core.Strategies
import io.github.zapretdroid.service.VpnState
import io.github.zapretdroid.service.VpnStatus
import io.github.zapretdroid.ui.AppsScreen
import io.github.zapretdroid.ui.HomeScreen
import io.github.zapretdroid.ui.SettingsScreen
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CONNECT, false)) connectRequest = true
    }

    companion object {
        const val EXTRA_CONNECT = "connect"
    }
}

private enum class Screen { HOME, SETTINGS, APPS }

@Composable
private fun App(connectRequest: Boolean, onConnectRequestHandled: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
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

    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(context) == null) {
            error = null
            VpnStatus.start(context)
        } else {
            error = context.getString(R.string.error_vpn_permission)
        }
    }
    val connect = {
        error = null
        val prepare = VpnService.prepare(context)
        if (prepare == null) VpnStatus.start(context) else vpnPermission.launch(prepare)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // The VPN works without notifications; the answer only affects the status notification.
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
            onConnectRequestHandled()
            if (state == VpnState.STOPPED) requestConnect()
        }
    }

    val strategyName = remember(settingsVersion) { strategyTitle(context, settings) }

    BackHandler(enabled = screen != Screen.HOME) {
        screen = if (screen == Screen.APPS) Screen.SETTINGS else Screen.HOME
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            state = state,
            error = error,
            txBytes = tx,
            rxBytes = rx,
            strategyName = strategyName,
            onToggle = {
                when (state) {
                    VpnState.STOPPED -> requestConnect()
                    VpnState.STARTING, VpnState.RUNNING -> VpnStatus.stop(context)
                    VpnState.STOPPING -> Unit
                }
            },
            onDismissError = { error = null },
            onOpenSettings = { screen = Screen.SETTINGS },
        )
        Screen.SETTINGS -> SettingsScreen(
            settings = settings,
            vpnActive = state != VpnState.STOPPED,
            onBack = {
                settingsVersion++
                screen = Screen.HOME
            },
            onOpenApps = { screen = Screen.APPS },
        )
        Screen.APPS -> AppsScreen(
            settings = settings,
            onBack = { screen = Screen.SETTINGS },
        )
    }
}

private fun strategyTitle(context: Context, settings: Settings): String =
    Strategies.byId(settings.strategyId)?.let { context.getString(it.title) }
        ?: context.getString(R.string.strategy_custom)
