package com.example.xraycore

import com.example.core.EncryptedConfigVault
import com.example.data.local.NetworkStrategyEntity
import com.example.data.local.ServerProfileEntity
import com.example.data.model.AntiCensorshipPreset
import com.example.data.model.ChainingMode
import com.example.data.model.StabilitySettings
import com.example.data.repository.LuxyVpnRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AdaptiveState(
    val currentStage: Int = 0,
    val stageLabel: String = XrayConfigCompiler.stageSpec(0).title,
    val networkFingerprint: String = "wifi:default",
    val isAdapting: Boolean = false
)

class AdaptiveRoutingEngine(
    private val repository: LuxyVpnRepository
) {
    private val _state = MutableStateFlow(AdaptiveState())
    val state: StateFlow<AdaptiveState> = _state.asStateFlow()

    suspend fun onNetworkChanged(
        transport: String,
        ssidOrCarrier: String = "default",
        experimentalModeEnabled: Boolean = false
    ): Int {
        val fp = "${transport.lowercase().replace(" ", "_")}:${ssidOrCarrier.ifBlank { "default" }}"
        if (!experimentalModeEnabled) {
            _state.value = AdaptiveState(
                currentStage = 0,
                stageLabel = "Standard Xray",
                networkFingerprint = fp,
                isAdapting = false
            )
            return 0
        }
        val saved = repository.getStrategy(fp)
        val stage = saved?.preferredEscalationLevel?.coerceIn(0, 4) ?: 0
        _state.value = AdaptiveState(
            currentStage = stage,
            stageLabel = XrayConfigCompiler.stageSpec(stage).title,
            networkFingerprint = fp,
            isAdapting = false
        )
        return stage
    }

    fun syncWithExperimentalMode(experimentalModeEnabled: Boolean) {
        val fp = _state.value.networkFingerprint
        if (!experimentalModeEnabled) {
            _state.value = AdaptiveState(
                currentStage = 0,
                stageLabel = "Standard Xray",
                networkFingerprint = fp,
                isAdapting = false
            )
        } else {
            val stage = _state.value.currentStage.coerceIn(0, 4)
            _state.value = _state.value.copy(
                currentStage = stage,
                stageLabel = XrayConfigCompiler.stageSpec(stage).title,
                isAdapting = false
            )
        }
    }

    suspend fun escalateStage(settings: StabilitySettings): Pair<Int, StabilitySettings> {
        if (!settings.experimentalModeEnabled) {
            syncWithExperimentalMode(false)
            return 0 to settings.asEffectiveBaseline()
        }
        val current = _state.value.currentStage
        val nextStage = (current + 1) % XrayConfigCompiler.ADAPTIVE_STAGES.size
        return applyStage(nextStage, settings, isStall = true)
    }

    suspend fun setStage(stage: Int, settings: StabilitySettings): Pair<Int, StabilitySettings> {
        if (!settings.experimentalModeEnabled) {
            syncWithExperimentalMode(false)
            return 0 to settings.asEffectiveBaseline()
        }
        return applyStage(stage.coerceIn(0, 4), settings, isStall = false)
    }

    private suspend fun applyStage(
        targetStage: Int,
        baseSettings: StabilitySettings,
        isStall: Boolean
    ): Pair<Int, StabilitySettings> {
        if (!baseSettings.experimentalModeEnabled) {
            syncWithExperimentalMode(false)
            return 0 to baseSettings.asEffectiveBaseline()
        }
        val spec = XrayConfigCompiler.stageSpec(targetStage)
        val fp = _state.value.networkFingerprint
        _state.value = _state.value.copy(isAdapting = true)

        val adjusted = when (targetStage) {
            0 -> XrayConfigCompiler.applyPresetToSettings(baseSettings, AntiCensorshipPreset.DIRECT_LOW_LATENCY)
            1 -> XrayConfigCompiler.applyPresetToSettings(baseSettings, AntiCensorshipPreset.MAX_STEALTH)
                .copy(mixedCaseSni = true, tlsPaddingEnabled = true)
            2 -> XrayConfigCompiler.applyPresetToSettings(baseSettings, AntiCensorshipPreset.CDN_FRONTING)
                .copy(failClosedEch = true)
            3 -> XrayConfigCompiler.applyPresetToSettings(baseSettings, AntiCensorshipPreset.STRICT_DPI_BYPASS)
                .copy(chainingMode = ChainingMode.WARP_TO_PROXY)
            else -> XrayConfigCompiler.applyPresetToSettings(baseSettings, AntiCensorshipPreset.STRICT_DPI_BYPASS)
                .copy(dohEnabled = true, dnsTcpEnabled = true, dnsDotEnabled = true)
        }

        val existing = repository.getStrategy(fp)
        repository.upsertStrategy(
            NetworkStrategyEntity(
                networkFingerprint = fp,
                transportType = fp.substringBefore(':'),
                preferredEscalationLevel = targetStage,
                preferredPreset = spec.presetName.name,
                lastCleanEndpoint = existing?.lastCleanEndpoint.orEmpty(),
                lastSuccessAt = if (!isStall) System.currentTimeMillis() else (existing?.lastSuccessAt ?: 0L),
                successCount = (existing?.successCount ?: 0) + if (!isStall) 1 else 0,
                stallCount = (existing?.stallCount ?: 0) + if (isStall) 1 else 0
            )
        )

        _state.value = _state.value.copy(
            currentStage = targetStage,
            stageLabel = spec.title,
            isAdapting = false
        )
        return targetStage to adjusted
    }

    suspend fun recordHealthyConnection(node: ServerProfileEntity) {
        val fp = _state.value.networkFingerprint
        val existing = repository.getStrategy(fp)
        val stage = _state.value.currentStage
        val endpoint = if (node.isLocked) EncryptedConfigVault.VAULT_REDACTED_VALUE else "${node.address}:${node.port}"
        repository.upsertStrategy(
            NetworkStrategyEntity(
                networkFingerprint = fp,
                transportType = fp.substringBefore(':'),
                preferredEscalationLevel = stage,
                preferredPreset = XrayConfigCompiler.stageSpec(stage).presetName.name,
                lastCleanEndpoint = endpoint,
                lastSuccessAt = System.currentTimeMillis(),
                successCount = (existing?.successCount ?: 0) + 1,
                stallCount = existing?.stallCount ?: 0
            )
        )
    }
}
