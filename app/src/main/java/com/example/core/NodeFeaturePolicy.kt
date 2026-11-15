package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.model.FeatureGate
import com.example.data.model.NodeFeatureOverrides
import com.example.data.model.ProxyProtocol
import com.example.data.model.SecurityType
import com.example.data.model.StabilitySettings
import com.example.data.model.TransportType
import com.example.data.model.TriState

/**
 * Projects global settings through one profile's overrides and the compatibility matrix.
 * An empty override document is not quite identity: a FORBIDDEN feature is forced off even
 * when the global switch is on (MUX on XHTTP, fragment on WireGuard, ECH on REALITY).
 */
object NodeFeaturePolicy {

    fun apply(settings: StabilitySettings, node: ServerProfileEntity?): StabilitySettings {
        if (node == null) return settings
        val overrides = NodeFeatureOverrides.decode(node.featureOverrides)
        val kind = ProtocolGuardrails.protocolFormKindFromProfile(node)
        val matrix = ProtocolGuardrails.featureMatrix(
            kind = kind,
            transport = TransportType.parse(node.transport),
            security = SecurityType.parse(node.security),
            flow = node.flow,
            experimentalModeEnabled = settings.experimentalModeEnabled
        )
        val experimental = settings.experimentalModeEnabled
        fun enabled(choice: TriState, global: Boolean, gate: FeatureGate): Boolean {
            if (gate == FeatureGate.FORBIDDEN) return false
            if (gate == FeatureGate.EXPERIMENTAL && !experimental) return false
            return choice.resolve(global)
        }
        val fragmentOn = enabled(overrides.fragment, settings.tlsFragmentEnabled, matrix.fragment)
        val tfo = enabled(overrides.tcpFastOpen, settings.tcpFastOpen, matrix.tcpFastOpen) && !fragmentOn
        return settings.copy(
            muxEnabled = enabled(overrides.mux, settings.muxEnabled, matrix.mux),
            muxConcurrency = overrides.muxConcurrency.takeIf { it in 1..128 } ?: settings.muxConcurrency,
            tlsFragmentEnabled = fragmentOn,
            fragmentPackets = overrides.fragmentPackets.ifBlank { settings.fragmentPackets },
            fragmentLength = overrides.fragmentLength.ifBlank { settings.fragmentLength },
            fragmentInterval = overrides.fragmentInterval.ifBlank { settings.fragmentInterval },
            fragmentMaxSplit = overrides.fragmentMaxSplit.ifBlank { settings.fragmentMaxSplit },
            fragmentHost = overrides.fragmentHost.ifBlank { settings.fragmentHost },
            realityFragmentEnabled = enabled(
                overrides.realityFragment,
                settings.realityFragmentEnabled,
                matrix.realityFragment
            ),
            echEnabled = enabled(overrides.ech, settings.echEnabled, matrix.ech),
            echForceQuery = overrides.echForceQuery.ifBlank { settings.echForceQuery },
            failClosedEch = enabled(overrides.failClosedEch, settings.failClosedEch, matrix.ech),
            noiseEnabled = enabled(overrides.noise, settings.noiseEnabled, matrix.noise),
            noiseType = overrides.noiseType.ifBlank { settings.noiseType },
            noisePacketSize = overrides.noisePacketSize.ifBlank { settings.noisePacketSize },
            noiseDelayMs = overrides.noiseDelayMs.ifBlank { settings.noiseDelayMs },
            tlsPaddingEnabled = enabled(overrides.tlsPadding, settings.tlsPaddingEnabled, matrix.tlsPadding),
            tcpFastOpen = tfo,
            allowUnencryptedVless = enabled(
                overrides.allowUnencryptedVless,
                settings.allowUnencryptedVless,
                matrix.allowUnencrypted
            ),
            mixedCaseSni = enabled(overrides.mixedCaseSni, settings.mixedCaseSni, matrix.mixedCaseSni)
        )
    }

    fun echForcedOff(node: ServerProfileEntity): Boolean {
        if (NodeFeatureOverrides.decode(node.featureOverrides).ech == TriState.OFF) return true
        val kind = ProtocolGuardrails.protocolFormKindFromProfile(node)
        val gate = ProtocolGuardrails.featureMatrix(
            kind = kind,
            transport = TransportType.parse(node.transport),
            security = SecurityType.parse(node.security),
            flow = node.flow,
            experimentalModeEnabled = true
        ).ech
        return gate == FeatureGate.FORBIDDEN
    }

    fun isCompiledProtocol(node: ServerProfileEntity): Boolean =
        ProtocolGuardrails.isCompiledByCore(ProxyProtocol.parse(node.protocol))
}
