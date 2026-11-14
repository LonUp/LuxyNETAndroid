package com.example.xraycore

import com.example.data.local.RoutingRuleEntity
import com.example.data.local.ServerProfileEntity
import com.example.data.model.StabilitySettings
import com.example.vpn.SplitTunnelEngine

class LuxyVpnCore(
    splitTunnelEngine: SplitTunnelEngine,
    onTrafficBytes: (rxDelta: Long, txDelta: Long) -> Unit = { _, _ -> },
    onBlockedEvent: (destination: String, ruleName: String) -> Unit = { _, _ -> }
) {
    private val runtime = LuxyCoreRuntime(splitTunnelEngine, onTrafficBytes, onBlockedEvent)

    val version: String get() = XrayConfigCompiler.LUXY_CORE_VERSION
    val socksPort: Int get() = runtime.activeSocksPort
    val isRunning: Boolean get() = runtime.isRunning

    @Volatile
    var lastCompiledConfigJson: String = ""
        private set

    fun startCore(
        node: ServerProfileEntity,
        settings: StabilitySettings,
        rules: List<RoutingRuleEntity>,
        relayNode: ServerProfileEntity? = null
    ): Result<String> {
        val compiledJson = XrayConfigCompiler.compileXrayConfig(node, settings, rules, relayNode)
        lastCompiledConfigJson = compiledJson
        val started = runtime.start(node, settings, rules)
        return if (started) {
            Result.success(compiledJson)
        } else {
            Result.failure(IllegalStateException("Unable to bind SOCKS5 core on 127.0.0.1:${runtime.activeSocksPort}"))
        }
    }

    fun updateRulesAndSettings(
        node: ServerProfileEntity?,
        settings: StabilitySettings,
        rules: List<RoutingRuleEntity>,
        relayNode: ServerProfileEntity? = null
    ) {
        lastCompiledConfigJson = XrayConfigCompiler.compileXrayConfig(node, settings, rules, relayNode)
        runtime.updateRoutingContext(node, settings, rules)
    }

    fun stopCore() {
        runtime.stop()
    }
}
