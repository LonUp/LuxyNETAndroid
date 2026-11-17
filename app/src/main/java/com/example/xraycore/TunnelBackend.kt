package com.example.xraycore

import android.content.Context

/** Native lifecycle seam; attempt IDs keep delayed teardown from stopping a newer core. */
interface TunnelBackend {

    /** Starts a specific attempt against an established VPN descriptor, never a proxy-only fallback. */
    fun start(configJson: String, tunFd: Int, attempt: Long): Result<Unit>

    /** Stops only this or an older attempt; delayed teardown must not stop a newer core. */
    fun stop(throughAttempt: Long)


}

class XrayBackend(private val context: Context, private val onStopped: (Long) -> Unit) : TunnelBackend {


    override fun start(configJson: String, tunFd: Int, attempt: Long): Result<Unit> =
        XrayEngine.start(context, configJson, tunFd, attempt, onStopped)

    override fun stop(throughAttempt: Long) = XrayEngine.stop(throughAttempt)



}
