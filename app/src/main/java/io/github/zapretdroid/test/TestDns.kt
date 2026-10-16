package io.github.zapretdroid.test

import io.github.zapretdroid.core.Settings
import io.github.zapretdroid.core.isIpLiteral
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Name resolution for the tests, matching what the VPN uses: the DNS server from the settings,
 * or the network's own DNS for "system". When the configured server gives an answer that looks
 * spoofed (NXDOMAIN or a private address for a name the system resolves to a public one), the
 * system answer is used and the spoofing is reported, so a test does not fail on DNS alone.
 */
object TestDns {
    class Answer(val address: InetAddress?, val note: String? = null)

    private fun suspicious(a: InetAddress?) = a == null || a.isAnyLocalAddress || a.isLoopbackAddress || a.isSiteLocalAddress

    private fun system(host: String): InetAddress? =
        runCatching { InetAddress.getAllByName(host).firstOrNull { it is Inet4Address } }.getOrNull()

    fun resolve(host: String, dns: String): Answer {
        val server = dns.trim().takeIf { it != Settings.SYSTEM_DNS && isIpLiteral(it) }
            ?: return Answer(system(host))
        val viaServer = runCatching { DnsClient.resolve(host, InetAddress.getByName(server)).firstOrNull() }
        val answer = viaServer.getOrNull()
        if (viaServer.isSuccess && !suspicious(answer)) return Answer(answer)
        val sys = system(host)
        val what = viaServer.exceptionOrNull()?.let { e ->
            if (e.message?.contains("code 3") == true) "NXDOMAIN" else (e.message ?: e.javaClass.simpleName)
        } ?: (answer?.hostAddress ?: "empty answer")
        return when {
            sys != null && !suspicious(sys) && viaServer.exceptionOrNull() !is java.net.SocketTimeoutException ->
                Answer(sys, "DNS $server answers $what for $host, the system DNS ${sys.hostAddress}: spoofed, using the system answer (choose \"system\" or another DNS in the settings)")
            else -> Answer(sys ?: answer, "DNS $server: $what for $host" + (sys?.let { ", system DNS ${it.hostAddress}" } ?: ""))
        }
    }
}
