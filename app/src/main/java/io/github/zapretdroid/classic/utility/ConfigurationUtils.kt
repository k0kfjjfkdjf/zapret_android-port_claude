package io.github.zapretdroid.classic.utility

import android.content.Context
import io.github.zapretdroid.classic.core.ByeDpiProxyCmdPreferences
import io.github.zapretdroid.classic.data.Configuration

fun testConfiguration(context: Context, command: String, host: String, port: Int): Configuration {
    val args = ByeDpiProxyCmdPreferences(context, command, host, port).args.toList()
    val (commandHost, commandPort) = args.checkIpAndPortInArgs()
    return Configuration(args, commandHost ?: host, commandPort?.toInt() ?: port)
}
