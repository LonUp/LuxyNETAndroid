package com.example.xraycore

import com.example.data.local.ServerProfileEntity
import com.example.data.model.StabilitySettings

/** Compiles a supported profile, then starts the native core once the service owns a TUN fd. */
class LuxyVpnCore(private val backend: TunnelBackend) {

    private data class PreparedConfig(val attempt: Long, val json: String)

    @Volatile
    private var preparedConfig: PreparedConfig? = null

    fun startCore(node: ServerProfileEntity, settings: StabilitySettings, attempt: Long): Result<String> {
        preparedConfig = null
        val compiled = try {
            XrayConfigCompiler.compileXrayConfig(node, settings, emptyList())
        } catch (failure: Exception) {
            return Result.failure(failure)
        }
        preparedConfig = PreparedConfig(attempt, compiled)
        return Result.success(compiled)
    }

    fun attachTun(attempt: Long, tunFd: Int): Result<Unit> {
        val prepared = preparedConfig
        if (prepared == null || prepared.attempt != attempt) {
            return Result.failure(IllegalStateException("No current compiled config to start"))
        }
        return backend.start(prepared.json, tunFd, attempt)
    }

    fun stopCore(throughAttempt: Long) = backend.stop(throughAttempt)

}
