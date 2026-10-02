package com.audiomix.iptv

import android.content.Context
import io.lantern.sdk.LanternManager
import java.net.InetSocketAddress

/**
 * Small app-scoped wrapper around Lantern's public Android SDK.
 *
 * This first lab integration intentionally uses Lantern's HTTP proxy mode.
 * It does not implement Android VpnService or claim full-device VPN routing.
 */
class LanternController(context: Context) {

    enum class State {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        ERROR
    }

    private val appContext = context.applicationContext

    @Volatile
    var state: State = State.STOPPED
        private set

    @Volatile
    var proxyAddress: InetSocketAddress? = null
        private set

    init {
        LanternManager.setup(appContext, "AudioMixIPTV")
    }

    @Synchronized
    fun start(): InetSocketAddress? {
        if (state == State.RUNNING) return proxyAddress

        state = State.STARTING
        return try {
            val result = LanternManager.startLantern(":8080", true)
            if (result == null) {
                state = State.ERROR
                proxyAddress = null
                null
            } else {
                proxyAddress = result
                state = State.RUNNING
                result
            }
        } catch (t: Throwable) {
            proxyAddress = null
            state = State.ERROR
            null
        }
    }

    @Synchronized
    fun stop() {
        if (state == State.STOPPED) return

        state = State.STOPPING
        try {
            LanternManager.stopLantern()
        } finally {
            proxyAddress = null
            state = State.STOPPED
        }
    }

    fun isRunning(): Boolean = state == State.RUNNING && LanternManager.isRunning()
}
