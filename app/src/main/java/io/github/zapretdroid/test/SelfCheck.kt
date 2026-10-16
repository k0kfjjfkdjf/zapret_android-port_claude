package io.github.zapretdroid.test

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.Diagnostics
import io.github.zapretdroid.core.FakePayloads
import io.github.zapretdroid.core.HostLists
import io.github.zapretdroid.core.NativeLog
import io.github.zapretdroid.core.Settings
import io.github.zapretdroid.core.Strategies
import io.github.zapretdroid.core.isIpLiteral
import io.github.zapretdroid.service.VpnStatus
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

enum class CheckStatus { OK, WARN, FAIL, INFO }

data class CheckItem(val group: String, val name: String, val status: CheckStatus, val detail: String, val ms: Long)

/**
 * "Function check": a fixed set of checks of the device, the engine and the network, run in
 * one go and written to a report that the user can send to the developer. Local checks run the
 * engine against servers on 127.0.0.1, so they show whether the engine itself works on this
 * device independently of the provider; network checks show what the provider does.
 */
object SelfCheck {
    var running by mutableStateOf(false)
        private set
    var items by mutableStateOf(emptyList<CheckItem>())
        private set
    var current by mutableStateOf("")
        private set
    var report by mutableStateOf<File?>(null)
        private set

    private const val PORT = 10920
    private const val TIMEOUT_MS = 5000
    private const val JOURNAL_LINES = 400

    fun start(context: Context, settings: Settings) {
        if (running) return
        val app = context.applicationContext
        running = true
        items = emptyList()
        report = null
        thread(name = "self-check") {
            val started = System.currentTimeMillis()
            AppLog.i("self-check: started", source = "test")
            try {
                run(app, settings)
            } catch (e: Throwable) {
                AppLog.e("self-check failed", e, source = "test")
                add("self-check", "unexpected error", CheckStatus.FAIL, e.toString(), 0)
            } finally {
                writeReport(app, settings, System.currentTimeMillis() - started)
                current = ""
                running = false
                val counts = items.groupingBy { it.status }.eachCount()
                AppLog.i("self-check: finished, $counts", source = "test")
            }
        }
    }

    private fun add(group: String, name: String, status: CheckStatus, detail: String, ms: Long) {
        val item = CheckItem(group, name, status, detail, ms)
        items = items + item
        val line = "self-check: [${status.name}] $group / $name: $detail"
        if (status == CheckStatus.FAIL || status == CheckStatus.WARN) AppLog.w(line, source = "test") else AppLog.i(line, source = "test")
    }

    /** Runs one check; an exception counts as a failure. */
    private fun check(group: String, name: String, body: () -> Pair<CheckStatus, String>) {
        current = "$group: $name"
        val start = System.currentTimeMillis()
        val (status, detail) = try {
            body()
        } catch (e: Throwable) {
            CheckStatus.FAIL to "${e.javaClass.simpleName}: ${e.message}"
        }
        add(group, name, status, detail, System.currentTimeMillis() - start)
    }

    private fun run(context: Context, settings: Settings) {
        // Engine output (connection log) goes to the journal, whose tail is in the report.
        NativeLog.start(AppLog.file.absolutePath, 8L * 1024 * 1024)
        val fakeDir = FakePayloads.install(context)
        val listDir = HostLists.dir(context)
        device(context)
        files(context, fakeDir)
        engineLocal(fakeDir, listDir)
        presets(fakeDir, listDir)
        network(context, settings, fakeDir, listDir)
    }

    // --- device -----------------------------------------------------------------------------

    private fun device(context: Context) {
        val g = "device"
        check(g, "VPN permission") {
            if (VpnService.prepare(context) == null) CheckStatus.OK to "granted"
            else CheckStatus.WARN to "not granted yet: Android asks on the first connection"
        }
        val ours = VpnStatus.isServiceRunning(context)
        check(g, "ZapretDroid VPN") { CheckStatus.INFO to if (ours) "running" else "stopped" }
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        val caps = network?.let { manager.getNetworkCapabilities(it) }
        val link = network?.let { manager.getLinkProperties(it) }
        check(g, "active network") {
            if (network == null) return@check CheckStatus.FAIL to "no active network"
            val kinds = buildList {
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ethernet")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("vpn")
            }
            val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            (if (validated) CheckStatus.OK else CheckStatus.WARN) to
                "${kinds.joinToString("+").ifEmpty { "other" }}, validated: $validated, dns: ${link?.dnsServers?.joinToString { it.hostAddress ?: "?" }}"
        }
        check(g, "other VPN") {
            val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            when {
                vpn && !ours -> CheckStatus.WARN to "another VPN is active: only one VPN can run at a time"
                else -> CheckStatus.OK to "none"
            }
        }
        check(g, "Private DNS") {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || link == null) return@check CheckStatus.INFO to "not available"
            if (link.isPrivateDnsActive) {
                CheckStatus.WARN to "active (${link.privateDnsServerName ?: "automatic"}): Android sends DNS over TLS on port 853 " +
                    "itself, past the DNS server set in ZapretDroid; if sites do not open, turn it off"
            } else {
                CheckStatus.OK to "off"
            }
        }
        check(g, "IPv6") {
            val v6 = link?.linkAddresses?.any { it.address is Inet6Address && !it.address.isLinkLocalAddress } == true
            CheckStatus.INFO to if (v6) "the network has a global IPv6 address" else "no IPv6 on this network"
        }
        check(g, "battery optimization") {
            val power = context.getSystemService(PowerManager::class.java)
            if (power?.isIgnoringBatteryOptimizations(context.packageName) == true) CheckStatus.OK to "exempt"
            else CheckStatus.WARN to "the app is battery-optimized: some firmware (MIUI, EMUI…) kills the VPN in the background"
        }
        check(g, "notifications") {
            if (Build.VERSION.SDK_INT < 33) return@check CheckStatus.OK to "no permission needed"
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (granted) CheckStatus.OK to "allowed" else CheckStatus.WARN to "denied: the VPN status notification is hidden"
        }
        check(g, "connection owner lookup") {
            CheckStatus.INFO to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "available (Android 10+)" else "not available before Android 10"
        }
    }

    // --- files ------------------------------------------------------------------------------

    private fun files(context: Context, fakeDir: File) {
        val g = "files"
        check(g, "fake payloads") {
            val names = FakePayloads.names(context)
            val bad = names.filter { File(fakeDir, it).length() == 0L }
            if (names.isEmpty() || bad.isNotEmpty()) CheckStatus.FAIL to "${names.size} files, empty: $bad"
            else CheckStatus.OK to "${names.size} files in ${fakeDir.path}"
        }
        check(g, "QUIC fakes with random SNI") {
            val files = File(fakeDir, FakePayloads.QUIC_SNI_DIR).listFiles().orEmpty().filter { it.length() >= 1200 }
            if (files.isEmpty()) CheckStatus.FAIL to "none generated" else CheckStatus.OK to "${files.size} QUIC Initials of ${files.first().length()} B"
        }
        check(g, "host lists") {
            val names = HostLists.names(context)
            CheckStatus.INFO to if (names.isEmpty()) "none" else names.joinToString { "$it (${HostLists.count(context, it)})" }
        }
        check(g, "journal") {
            CheckStatus.INFO to "${AppLog.file.length() / 1024} KB"
        }
    }

    // --- engine against local servers --------------------------------------------------------

    private fun engineLocal(fakeDir: File, listDir: File) {
        val g = "engine (local)"
        check(g, "start and SOCKS5 handshake") {
            TestEngine.with("", PORT, fakeDir, listDir) {
                Socket().use { s ->
                    s.soTimeout = TIMEOUT_MS
                    s.connect(InetSocketAddress("127.0.0.1", PORT), TIMEOUT_MS)
                    s.getOutputStream().write(byteArrayOf(5, 1, 0))
                    val reply = ByteArray(2)
                    s.getInputStream().read(reply)
                    if (reply[0].toInt() == 5 && reply[1].toInt() == 0) CheckStatus.OK to "SOCKS5 without authentication"
                    else CheckStatus.FAIL to "unexpected reply ${reply.joinToString()}"
                }
            }
        }
        // TCP through the engine with a split and disorder: the data must arrive intact.
        check(g, "TCP relay with --split 2 --disorder 1") {
            val payload = ByteArray(4096) { (it * 7).toByte() }
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = TIMEOUT_MS
                var received: ByteArray? = null
                val serverThread = thread {
                    runCatching {
                        server.accept().use { c ->
                            c.soTimeout = TIMEOUT_MS
                            val buf = ByteArray(payload.size)
                            var off = 0
                            while (off < buf.size) {
                                val n = c.getInputStream().read(buf, off, buf.size - off)
                                if (n < 0) break
                                off += n
                            }
                            received = buf.copyOf(off)
                            c.getOutputStream().write(byteArrayOf(1))
                        }
                    }
                }
                TestEngine.with("--split 2 --disorder 1", PORT, fakeDir, listDir) {
                    Socket().use { s ->
                        s.soTimeout = TIMEOUT_MS
                        s.connect(InetSocketAddress("127.0.0.1", PORT), TIMEOUT_MS)
                        socks5Connect(s, server.localPort)
                        s.getOutputStream().write(payload)
                        s.getInputStream().read()
                    }
                }
                serverThread.join(TIMEOUT_MS.toLong())
                if (received?.contentEquals(payload) == true) CheckStatus.OK to "${payload.size} bytes arrived intact"
                else CheckStatus.FAIL to "server got ${received?.size ?: 0} of ${payload.size} bytes or different data"
            }
        }
        // UDP through SOCKS5 UDP ASSOCIATE with protocol fakes: the server must see the fakes
        // (low TTL does not matter on loopback) and then the real request.
        check(g, "UDP relay with 2 STUN fakes") {
            val stunFake = File(fakeDir, "stun.bin").readBytes()
            DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = TIMEOUT_MS
                val seen = mutableListOf<ByteArray>()
                val serverThread = thread {
                    runCatching {
                        val buf = ByteArray(2048)
                        while (true) {
                            val p = DatagramPacket(buf, buf.size)
                            server.receive(p)
                            val d = buf.copyOf(p.length)
                            synchronized(seen) { seen += d }
                            if (d.size == 20 && d[0].toInt() == 0 && d[1].toInt() == 1) {
                                val resp = byteArrayOf(0x01, 0x01, 0, 0) + d.copyOfRange(4, 20)
                                server.send(DatagramPacket(resp, resp.size, p.socketAddress))
                                break
                            }
                        }
                    }
                }
                val strategy = "--proto u --udp-fake 2 --ttl 4 --udp-proto-fake stun:{fake:stun.bin}"
                val probe = TestEngine.with(strategy, PORT, fakeDir, listDir) {
                    UdpProbe.probe(PORT, UdpTarget(UdpTarget.Kind.STUN, "127.0.0.1", server.localPort), InetAddress.getByName("127.0.0.1"), TIMEOUT_MS, sends = 1)
                }
                serverThread.join(TIMEOUT_MS.toLong())
                val datagrams = synchronized(seen) { seen.toList() }
                val fakes = datagrams.dropLast(1).count { it.contentEquals(stunFake) }
                when {
                    !probe.ok -> CheckStatus.FAIL to "no reply through the engine: ${probe.detail}; server saw ${datagrams.size} datagram(s)"
                    fakes == 2 -> CheckStatus.OK to "server saw 2 STUN fakes, then the request; reply came back in ${probe.firstByteMs} ms"
                    else -> CheckStatus.FAIL to "server saw ${datagrams.size} datagram(s), $fakes of them the STUN fake (expected 2)"
                }
            }
        }
    }

    /** SOCKS5 CONNECT to 127.0.0.1:[port] over an open connection to the engine. */
    private fun socks5Connect(s: Socket, port: Int) {
        val out = s.getOutputStream()
        val input = s.getInputStream()
        out.write(byteArrayOf(5, 1, 0))
        val hello = ByteArray(2).also { readFully(input, it) }
        if (hello[1].toInt() != 0) throw IllegalStateException("handshake refused")
        out.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (port shr 8).toByte(), port.toByte()))
        val reply = ByteArray(10).also { readFully(input, it) }
        if (reply[1].toInt() != 0) throw IllegalStateException("CONNECT refused (${reply[1]})")
    }

    private fun readFully(input: java.io.InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = input.read(b, off, b.size - off)
            if (n < 0) throw java.io.EOFException("engine closed the connection")
            off += n
        }
    }

    private fun presets(fakeDir: File, listDir: File) {
        val g = "presets"
        Strategies.presets.forEach { preset ->
            check(g, preset.id) {
                TestEngine.with(preset.args, PORT, fakeDir, listDir, connLog = false) { CheckStatus.OK to "engine starts" }
            }
        }
    }

    // --- network ----------------------------------------------------------------------------

    private fun network(context: Context, settings: Settings, fakeDir: File, listDir: File) {
        val g = "network"
        listOf("1.1.1.1", "8.8.8.8", "77.88.8.8").forEach { ip ->
            check(g, "TCP to $ip:443") {
                val start = System.currentTimeMillis()
                Socket().use { it.connect(InetSocketAddress(ip, 443), TIMEOUT_MS) }
                CheckStatus.OK to "connected in ${System.currentTimeMillis() - start} ms"
            }
        }
        val names = listOf("www.google.com", "www.youtube.com", "discord.com")
        val system = mutableMapOf<String, InetAddress?>()
        names.forEach { host ->
            check(g, "system DNS: $host") {
                val start = System.currentTimeMillis()
                val a = InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }
                system[host] = a
                CheckStatus.OK to "${a?.hostAddress} in ${System.currentTimeMillis() - start} ms"
            }
        }
        // Public resolvers side by side: a provider that intercepts DNS answers NXDOMAIN or a
        // private address for blocked names from some of them.
        val resolved = mutableMapOf<String, InetAddress?>()
        val servers = (listOf("8.8.8.8", "1.1.1.1", "77.88.8.8", "9.9.9.9") + listOfNotNull(settings.dns.trim().takeIf { isIpLiteral(it) })).distinct()
        val clean = mutableListOf<String>()
        val spoofed = mutableListOf<String>()
        servers.forEach { server ->
            check(g, "DNS $server") {
                val parts = mutableListOf<String>()
                var bad = false
                names.forEach { host ->
                    val start = System.currentTimeMillis()
                    val answer = runCatching { DnsClient.resolve(host, InetAddress.getByName(server)).firstOrNull() }
                    val ms = System.currentTimeMillis() - start
                    answer.onSuccess { a ->
                        if (a == null || a.isAnyLocalAddress || a.isLoopbackAddress || a.isSiteLocalAddress) {
                            bad = true
                            parts += "$host=${a?.hostAddress ?: "empty"} (block?)"
                        } else {
                            if (resolved[host] == null) resolved[host] = a
                            parts += "$host=${a.hostAddress} ${ms}ms"
                        }
                    }.onFailure { e ->
                        bad = true
                        val nx = e.message?.contains("code 3") == true
                        parts += "$host: " + if (nx) "NXDOMAIN (the name exists: answer spoofed)" else (e.message ?: e.javaClass.simpleName)
                    }
                }
                if (bad) spoofed += server else clean += server
                (if (bad) CheckStatus.WARN else CheckStatus.OK) to parts.joinToString("; ")
            }
        }
        check(g, "DNS as the tester resolves (${settings.dns})") {
            val notes = names.mapNotNull { TestDns.resolve(it, settings.dns).note }
            if (notes.isEmpty()) CheckStatus.OK to "no spoofing seen" else CheckStatus.WARN to notes.joinToString("; ")
        }
        check(g, "DNS recommendation") {
            val current = settings.dns.trim()
            when {
                current == Settings.SYSTEM_DNS -> CheckStatus.OK to "the network's own DNS is used (${system.values.firstOrNull()?.let { "it resolves" } ?: "?"})"
                current in spoofed -> CheckStatus.WARN to "the DNS in settings ($current) gives spoofed answers here: choose " +
                    (clean.firstOrNull()?.let { "$it or " } ?: "") + "\"system\" in Settings -> DNS"
                spoofed.isNotEmpty() -> CheckStatus.INFO to "spoofed here: ${spoofed.joinToString()}; clean: ${clean.joinToString().ifEmpty { "none, use \"system\"" }}"
                else -> CheckStatus.OK to "all checked resolvers answer correctly"
            }
        }
        val strategy = settings.strategyArgs
        // HTTPS: www.google.com is the control site, the others are often blocked.
        for ((label, args) in listOf("without bypass" to "", "current strategy" to strategy)) {
            runCatching {
                TestEngine.with(args, PORT, fakeDir, listDir) {
                    names.forEach { host ->
                        var https: ProbeResult? = null
                        var chrome: ProbeResult? = null
                        check(g, "HTTPS $host, $label") {
                            val r = SiteProbe.probe(PORT, host, resolved[host] ?: system[host], TIMEOUT_MS)
                            https = r
                            (if (r.ok) CheckStatus.OK else CheckStatus.WARN) to r.summary()
                        }
                        // The same handshake with Chrome's ~1.8 KB ClientHello: browsers and apps
                        // built on Chromium can pass where Android's 517-byte one does not.
                        check(g, "TLS as Chrome $host, $label") {
                            val r = SiteProbe.probe(PORT, host, resolved[host] ?: system[host], TIMEOUT_MS, TlsMode.CHROME)
                            chrome = r
                            (if (r.ok) CheckStatus.OK else CheckStatus.WARN) to r.summary()
                        }
                        check(g, "verdict $host, $label") { verdict(https, chrome) }
                    }
                    check(g, "QUIC www.youtube.com, $label") {
                        val r = UdpProbe.probe(PORT, UdpTarget(UdpTarget.Kind.QUIC, "www.youtube.com", 443), resolved["www.youtube.com"] ?: system["www.youtube.com"], TIMEOUT_MS)
                        (if (r.ok) CheckStatus.OK else CheckStatus.WARN) to r.summary()
                    }
                    TestPlan.stunServers.take(1).forEach { t ->
                        check(g, "STUN ${t.host}, $label") {
                            val addr = InetAddress.getAllByName(t.host).firstOrNull { it is Inet4Address }
                            val r = UdpProbe.probe(PORT, t, addr, TIMEOUT_MS)
                            (if (r.ok) CheckStatus.OK else CheckStatus.WARN) to r.summary()
                        }
                    }
                }
            }.onFailure { add(g, "engine with $label", CheckStatus.FAIL, it.message ?: it.toString(), 0) }
        }
        // UDP with the protocol fakes of the "Discord and calls" preset, whatever is selected.
        runCatching {
            TestEngine.with("--proto u ${Strategies.UDP_FAKES}", PORT, fakeDir, listDir) {
                (TestPlan.stunServers + UdpTarget(UdpTarget.Kind.QUIC, "www.youtube.com", 443)).forEach { t ->
                    check(g, "${t.label}, UDP fakes of the Discord preset") {
                        val addr = resolved[t.host] ?: InetAddress.getAllByName(t.host).firstOrNull { it is Inet4Address }
                        val r = UdpProbe.probe(PORT, t, addr, TIMEOUT_MS)
                        (if (r.ok) CheckStatus.OK else CheckStatus.WARN) to r.summary()
                    }
                }
            }
        }.onFailure { add(g, "engine with UDP fakes", CheckStatus.FAIL, it.message ?: it.toString(), 0) }
        // Discord's avatar CDN: where the 16-20 KB freeze shows up.
        runCatching {
            TestEngine.with(strategy, PORT, fakeDir, listDir) {
                listOf("cdn.discordapp.com/embed/avatars/0.png", "big:discord.com/api/download?platform=linux&format=tar.gz").forEach { spec ->
                    check(g, "HTTPS $spec, current strategy") {
                        // Resolve here: a name left to the engine is resolved inside its event loop.
                        val host = ProbeTarget.parse(spec).host
                        val addr = runCatching { InetAddress.getAllByName(host).firstOrNull { it is Inet4Address } }.getOrNull()
                        val r = SiteProbe.probe(PORT, spec, addr, TIMEOUT_MS)
                        (if (r.ok) CheckStatus.OK else CheckStatus.WARN) to r.summary()
                    }
                }
            }
        }
    }

    /**
     * What the three signs together mean: answered (ByeByeDPI's "available"), loaded
     * (zapret-termux's), and the handshake with a Chrome-sized ClientHello.
     */
    private fun verdict(https: ProbeResult?, chrome: ProbeResult?): Pair<CheckStatus, String> {
        val answered = https?.responded == true
        val loaded = https?.ok == true
        val asChrome = chrome?.ok == true
        val signs = "answered ${mark(answered)}, loaded ${mark(loaded)}, as Chrome ${mark(asChrome)}"
        return when {
            loaded -> CheckStatus.OK to "$signs: works"
            answered -> CheckStatus.WARN to "$signs: the server answers but the data freezes (${https?.bytes?.div(1024)} KB): " +
                "the IP-based freeze after 16-20 KB; splitting does not help, try fakes with an allowed SNI (ZapretDroid features set)"
            asChrome -> CheckStatus.WARN to "$signs: blocked by SNI only for small ClientHellos: browsers and Chromium apps pass, " +
                "Android apps (OkHttp) do not; look for a strategy in the tester with TLS \"Android + Chrome\""
            else -> CheckStatus.WARN to "$signs: the TLS handshake does not get through: another strategy is needed"
        }
    }

    private fun mark(b: Boolean) = if (b) "✓" else "✗"

    // --- report -----------------------------------------------------------------------------

    private fun writeReport(context: Context, settings: Settings, totalMs: Long) {
        runCatching {
            val file = File(AppLog.file.parentFile, "selfcheck.txt")
            val list = items
            file.bufferedWriter().use { out ->
                out.appendLine("ZapretDroid function check, " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + ", ${totalMs / 1000} s")
                Diagnostics.report(context, settings).forEach { out.appendLine(it) }
                val counts = list.groupingBy { it.status }.eachCount()
                out.appendLine("summary: " + CheckStatus.entries.joinToString { "${it.name} ${counts[it] ?: 0}" })
                out.appendLine()
                list.groupBy { it.group }.forEach { (group, checks) ->
                    out.appendLine("== $group ==")
                    checks.forEach { out.appendLine("[${it.status.name}] ${it.name} (${it.ms} ms): ${it.detail}") }
                    out.appendLine()
                }
                out.appendLine("== journal, last $JOURNAL_LINES lines ==")
                AppLog.readTail(AppLog.file).takeLast(JOURNAL_LINES).forEach { out.appendLine(it) }
            }
            report = file
        }.onFailure { AppLog.e("self-check: report not written", it, source = "test") }
    }
}
