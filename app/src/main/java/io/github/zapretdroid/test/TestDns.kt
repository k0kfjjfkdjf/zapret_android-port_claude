package io.github.zapretdroid.test

import io.github.zapretdroid.core.DnsMode
import io.github.zapretdroid.core.SecureDns
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

    /** What the tests resolve with: the plain DNS setting, "doh:<provider>" / "dot:<provider>", or "stamp:<name>|<sdns>" (the first DNSCrypt-mode server). */
    fun spec(settings: Settings): String = when (settings.dnsMode) {
        DnsMode.PLAIN -> settings.dns
        DnsMode.DOH -> "doh:${settings.dnsProvider}"
        DnsMode.DOT -> "dot:${settings.dnsProvider}"
        DnsMode.DNSCRYPT -> settings.dnsServers.takeIf { it.isNotEmpty() }?.joinToString(" ", prefix = "stamp:") { "${it.first}|${it.second}" } ?: settings.dns
    }

    private val upstreams = mutableMapOf<String, SecureDns.Upstream>()

    /** Through the encrypted resolver the VPN uses; the system answer if it fails. */
    private fun secure(host: String, spec: String): Answer {
        val mode = if (spec.startsWith("dot:")) DnsMode.DOT else DnsMode.DOH
        val name = spec.substringAfter(':')
        // "stamp:" carries all DNSCrypt-mode servers ("name|sdns" separated by spaces): the first that answers.
        val entries = if (spec.startsWith("stamp:")) name.split(' ').filter { it.contains('|') } else listOf(name)
        var result: Result<java.net.InetAddress?> = Result.failure(java.io.IOException("no server"))
        for (entry in entries) {
            result = runCatching {
                val up = synchronized(upstreams) {
                    upstreams.getOrPut(entry) {
                        if (spec.startsWith("stamp:")) SecureDns.Upstream(SecureDns.fromStamp(entry.substringBefore('|'), entry.substringAfter('|')), mode)
                        else SecureDns.Upstream(SecureDns.provider(entry), mode)
                    }
                }
                val id = kotlin.random.Random.nextInt(0x10000)
                DnsClient.parseResponse(up.exchange(DnsClient.buildQuery(id, host)), id)?.firstOrNull()
            }
            if (result.getOrNull() != null) break
        }
        result.getOrNull()?.let { return Answer(it) }
        val sys = system(host)
        return Answer(sys, "encrypted DNS ($spec) failed for $host: ${result.exceptionOrNull()?.message ?: "no address"}" + (sys?.let { ", system DNS ${it.hostAddress}" } ?: ""))
    }

    fun resolve(host: String, dns: String): Answer {
        if (dns.startsWith("doh:") || dns.startsWith("dot:") || dns.startsWith("stamp:")) return secure(host, dns)
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
