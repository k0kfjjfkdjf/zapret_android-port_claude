package io.github.zapretdroid.classic.services

import io.github.zapretdroid.classic.data.AppStatus
import io.github.zapretdroid.classic.data.Mode

var appStatus = AppStatus.Halted to Mode.VPN
    private set

fun setStatus(status: AppStatus, mode: Mode) {
    appStatus = status to mode
}
