package com.example.xraycore

import com.example.data.local.RoutingRuleEntity
import com.example.data.local.ServerProfileEntity
import com.example.data.model.StabilitySettings

/**
 * Compiles the active profile into an Xray config and hands it to a [TunnelBackend].
 *
 * Production ordering: [prepare] → `VpnService.Builder.establish()` → [attachTun].
 * The core cannot start before the TUN descriptor exists, so [prepare] only compiles.
 *
 * Tests may pass a [LuxyCoreRuntime] as `simulated`, in which case [prepare] also starts the
 * in-JVM SOCKS runtime and [attachTun] is a no-op.
 */
class LuxyVpnCore(
    private val backend: TunnelBackend,
    private val simulated: LuxyCoreRuntime? = null
) {
    val version: String get() = XrayConfigCompiler.LUXY_CORE_VERSION
    val socksPort: Int = SOCKS_PORT
    val isRealCore: Boolean get() = simulated == null
    val backendLabel: String get() = if (simulated != null) "simulated" else backend.label

    val isRunning: Boolean
        get() = simulated?.isRunning ?: backend.isRunning

    @Volatile
    var lastCompiledConfigJson: String = ""
        private set

    /**
     * Compiles the profile. With a simulated runtime the SOCKS server starts immediately;
     * with a real core the caller must establish the TUN and call [attachTun].
     */
    fun startCore(
        node: ServerProfileEntity,
        settings: StabilitySettings,
        rules: List<RoutingRuleEntity>,
        relayNode: ServerProfileEntity? = null
    ): Result<String> {
        val compiledJson = try {
            XrayConfigCompiler.compileXrayConfig(node, settings, rules, relayNode)
        } catch (failure: Exception) {
            return Result.failure(failure)
        }
        lastCompiledConfigJson = compiledJson
        val sim = simulated ?: return Result.success(compiledJson)
        return if (sim.start(node, settings, rules)) {
            Result.success(compiledJson)
        } else {
            Result.failure(IllegalStateException("Unable to bind SOCKS5 core on 127.0.0.1:$SOCKS_PORT"))
        }
    }

    /** Starts the real core against an established TUN descriptor. */
    fun attachTun(tunFd: Int): Result<Unit> {
        if (simulated != null) return Result.success(Unit)
        if (lastCompiledConfigJson.isBlank()) {
            return Result.failure(IllegalStateException("No compiled config to start"))
        }
        return backend.start(lastCompiledConfigJson, tunFd)
    }

    /** Recompiles; the running core is restarted by the service, which owns the TUN fd. */
    fun updateRulesAndSettings(
        node: ServerProfileEntity?,
        settings: StabilitySettings,
        rules: List<RoutingRuleEntity>,
        relayNode: ServerProfileEntity? = null
    ): String {
        val compiledJson = XrayConfigCompiler.compileXrayConfig(node, settings, rules, relayNode)
        lastCompiledConfigJson = compiledJson
        simulated?.updateRoutingContext(node, settings, rules)
        return compiledJson
    }

    /** Adopts a config compiled elsewhere (e.g. a reload intent) before restarting. */
    fun applyConfig(configJson: String) {
        lastCompiledConfigJson = configJson
    }

    fun stopCore() {
        if (simulated != null) simulated.stop() else backend.stop()
    }

    fun trafficTotals(): Pair<Long, Long> = simulated?.getTrafficTotals() ?: backend.trafficTotals()

    fun measureThroughTunnel(url: String): Long? =
        if (simulated != null) null else backend.measureThroughTunnel(url)

    companion object {
        const val SOCKS_PORT = 10808
    }
}
