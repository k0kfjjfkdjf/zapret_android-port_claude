package io.github.zapretdroid.core

/**
 * Strategies sent by other apps (the exported ToggleActivity, for automation): any installed app
 * can send one, without a permission, and the VPN then runs it for all of the user's traffic.
 * Refused are:
 *
 * - file paths (a '/'): `--fake-data /data/…`, `-H /…` would send the app's private files out;
 * - listen options: they would open the proxy to the network or move it;
 * - options that send traffic or DNS elsewhere, or write files: `-C 1.2.3.4:1080` (every
 *   connection through a stranger's proxy, host names included), `--dns-redirect`,
 *   `--names-dns`, `--udp-proxy`, `--conn-ip`, `--protect-path`, `--cache-file`,
 *   `--autohost-log`, `--udp-proto-fake`, `--auto-cache`;
 * - script variables (`$name`), which could spell any of the above after the check.
 *
 * Options are recognised as the engine's getopt_long reads them: a long option by any prefix
 * ("--connect" is --connect-to), with "=value" or not; short options also inside a cluster
 * ("-NC1.2.3.4:1080" is -N and -C). {fake:…}, {list:…}, {proxy:…} (the user's own proxies) and
 * @include of the user's own strategies still work.
 */
object ExternalStrategy {
    private val FORBIDDEN_LONG = listOf(
        "ip", "port", "transparent", "daemon", "pidfile",
        "connect-to", "conn-ip", "dns-redirect", "names-dns", "udp-proxy",
        "protect-path", "cache-file", "autohost-log", "udp-proto-fake", "auto-cache",
    )

    /** Engine options whose full names are prefixes of a forbidden one. */
    private val ALLOWED_EXACT = setOf("auto")

    /** The short forms of the options above. */
    private const val FORBIDDEN_SHORT = "ipEDwCIPyJG"

    /** Short options of the engine that take a value: the rest of a cluster is that value. */
    private const val SHORT_WITH_VALUE = "#/ABCGHIJKLMOPQRTVWabcdefgijlmnopqrstuwxyz"

    fun isSafe(args: List<String>): Boolean = args.none { arg -> '/' in arg || '$' in arg || isForbiddenOption(arg) }

    fun isForbiddenOption(arg: String): Boolean {
        if (arg.startsWith("--")) {
            val name = arg.substring(2).substringBefore('=')
            // getopt_long takes an exact name before a prefix: "--auto" is --auto, not --auto-cache.
            if (name in ALLOWED_EXACT) return false
            return name.isNotEmpty() && FORBIDDEN_LONG.any { it.startsWith(name) }
        }
        if (arg.length < 2 || arg[0] != '-') return false
        for (c in arg.substring(1)) {
            if (c in FORBIDDEN_SHORT) return true
            if (c in SHORT_WITH_VALUE) return false
            if (!c.isLetter()) return false // a value such as "-1", not options
        }
        return false
    }
}
