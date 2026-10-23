package io.github.zapretdroid.core

import java.net.InetAddress

private val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")

/** True for numeric IPv4/IPv6 addresses. Host names are rejected, so no DNS lookup happens. */
fun isIpLiteral(value: String): Boolean {
    if (IPV4.matches(value)) return true
    if (!value.contains(':')) return false
    if (!value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return false
    return runCatching { InetAddress.getByName(value) }.isSuccess
}
