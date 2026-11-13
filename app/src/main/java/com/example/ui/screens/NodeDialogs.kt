package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.AwgProfileParams
import com.example.core.EncryptedConfigVault
import com.example.core.NodeDraftInput
import com.example.core.ProtocolFormKind
import com.example.core.ProtocolGuardrails
import com.example.core.ProxyUriParser
import com.example.core.QrCodeGenerator
import com.example.core.X25519KeyPair
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.model.SecurityType
import com.example.data.model.TransportType
import com.example.ui.components.Formatters
import com.example.xraycore.LuxyVpnCore
import java.util.UUID

@Composable
fun PasteUriDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste Config URI(s)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Paste vless://, vmess://, trojan://, ss://, socks://, hysteria2://, wireguard://, awg://, warp://, masque://, dnstt://, masterdns:// links, Base64 subscriptions, or raw Xray JSON.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("vless://uuid@example.com:443?security=reality#Node") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("paste_uri_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onImport(text)
                    onDismiss()
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag("confirm_paste_uri_button")
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NodeEditorDialog(
    existing: ServerProfileEntity?,
    onDismiss: () -> Unit,
    onSave: (ServerProfileEntity) -> Unit
) {
    if (existing?.isLocked == true) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Locked Encrypted Profile",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            },
            text = {
                Text(
                    text = "This node was imported from a Zero-Knowledge .zsx vault (${existing.vaultKeyScheme.ifBlank { "AES-256-GCM" }}). Endpoint addresses, credentials, SNI, and keys are sealed and cannot be inspected or edited.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        )
        return
    }

    val initialKind = remember(existing) { ProtocolFormKind.fromProfile(existing) }
    val initialAwg = remember(existing) { existing?.let { ProxyUriParser.parseAwgQuery(it.serviceName) } ?: AwgProfileParams() }

    var kind by remember { mutableStateOf(initialKind) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var address by remember { mutableStateOf(existing?.address ?: "") }
    var portText by remember {
        mutableStateOf(
            existing?.port?.toString()
                ?: ProtocolGuardrails.defaultPortFor(initialKind).toString()
        )
    }
    var uuidOrPass by remember { mutableStateOf(existing?.uuidOrPassword ?: "") }
    var encryption by remember {
        mutableStateOf(
            existing?.encryption?.ifBlank { null } ?: when (initialKind) {
                ProtocolFormKind.SHADOWSOCKS -> "aes-256-gcm"
                ProtocolFormKind.VMESS -> "auto"
                ProtocolFormKind.DNSTT, ProtocolFormKind.MASTERDNS -> "udp"
                ProtocolFormKind.WIREGUARD, ProtocolFormKind.AMNEZIA_WG, ProtocolFormKind.MASQUE -> "quic"
                else -> "none"
            }
        )
    }
    var transport by remember {
        mutableStateOf(
            existing?.let { TransportType.fromString(it.transport) }
                ?: ProtocolGuardrails.defaultTransportFor(initialKind)
        )
    }
    var security by remember {
        mutableStateOf(
            existing?.let { SecurityType.fromString(it.security) }
                ?: ProtocolGuardrails.defaultSecurityFor(initialKind)
        )
    }
    var sni by remember { mutableStateOf(existing?.sni ?: "") }
    var alpn by remember { mutableStateOf(existing?.alpn ?: "h2,http/1.1") }
    var fingerprint by remember { mutableStateOf(existing?.fingerprint?.ifBlank { "chrome" } ?: "chrome") }
    var publicKey by remember { mutableStateOf(existing?.publicKey ?: "") }
    var shortId by remember { mutableStateOf(existing?.shortId ?: if (initialKind == ProtocolFormKind.WIREGUARD || initialKind == ProtocolFormKind.AMNEZIA_WG) "0,0,0" else "") }
    var path by remember {
        mutableStateOf(
            existing?.path ?: if (initialKind in setOf(ProtocolFormKind.WIREGUARD, ProtocolFormKind.AMNEZIA_WG, ProtocolFormKind.MASQUE)) "172.16.0.2/32" else ""
        )
    }
    var hostHeader by remember { mutableStateOf(existing?.hostHeader ?: "") }
    var serviceName by remember { mutableStateOf(existing?.serviceName ?: "") }
    var flow by remember { mutableStateOf(existing?.flow ?: "") }
    var wgMtu by remember {
        mutableStateOf(
            if (initialKind in setOf(ProtocolFormKind.WIREGUARD, ProtocolFormKind.AMNEZIA_WG, ProtocolFormKind.MASQUE, ProtocolFormKind.DNSTT, ProtocolFormKind.MASTERDNS)) {
                existing?.flow?.ifBlank { if (initialKind in setOf(ProtocolFormKind.DNSTT, ProtocolFormKind.MASTERDNS)) "1232" else "1420" }
                    ?: if (initialKind in setOf(ProtocolFormKind.DNSTT, ProtocolFormKind.MASTERDNS)) "1232" else "1420"
            } else {
                "1420"
            }
        )
    }
    var allowInsecure by remember { mutableStateOf(existing?.allowInsecure ?: false) }
    var cipherSuites by remember { mutableStateOf(existing?.cipherSuites ?: "") }
    var finalMask by remember { mutableStateOf(existing?.finalMask ?: if (initialKind == ProtocolFormKind.AMNEZIA_WG) "awg" else "") }
    var echConfigList by remember { mutableStateOf(existing?.echConfigList ?: "") }
    var echForceQuery by remember { mutableStateOf(existing?.echForceQuery ?: "none") }
    var mldsa65Verify by remember { mutableStateOf(existing?.mldsa65Verify ?: "") }

    var awgJc by remember { mutableStateOf(initialAwg.jc.toString()) }
    var awgJmin by remember { mutableStateOf(initialAwg.jmin.toString()) }
    var awgJmax by remember { mutableStateOf(initialAwg.jmax.toString()) }
    var awgS1 by remember { mutableStateOf(initialAwg.s1.toString()) }
    var awgS2 by remember { mutableStateOf(initialAwg.s2.toString()) }
    var awgH1 by remember { mutableStateOf(initialAwg.h1.toString()) }
    var awgH2 by remember { mutableStateOf(initialAwg.h2.toString()) }
    var awgH3 by remember { mutableStateOf(initialAwg.h3.toString()) }
    var awgH4 by remember { mutableStateOf(initialAwg.h4.toString()) }

    val spec = remember(kind, transport, security) {
        ProtocolGuardrails.fieldSpecFor(kind, transport, security)
    }
    val effectiveTransport = remember(spec, transport) {
        if (transport in spec.supportedTransports) transport else spec.supportedTransports.first()
    }
    val effectiveSecurity = remember(spec, security) {
        if (security in spec.supportedSecurities) security else spec.supportedSecurities.first()
    }
    val effectiveEncryption = remember(kind, encryption) {
        when (kind) {
            ProtocolFormKind.DNSTT, ProtocolFormKind.MASTERDNS ->
                if (encryption.lowercase() in setOf("udp", "tcp", "dot", "doh")) encryption.lowercase() else "udp"
            ProtocolFormKind.WIREGUARD, ProtocolFormKind.AMNEZIA_WG, ProtocolFormKind.MASQUE ->
                if (encryption.lowercase() in LuxyVpnCore.DATAGRAM_SHAPING_PRESETS) encryption.lowercase() else "quic"
            ProtocolFormKind.SHADOWSOCKS ->
                if (encryption in listOf("aes-256-gcm", "chacha20-ietf-poly1305", "2022-blake3-aes-256-gcm", "aes-128-gcm")) encryption else "aes-256-gcm"
            ProtocolFormKind.VMESS ->
                if (encryption in listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none")) encryption else "auto"
            else -> "none"
        }
    }

    val draft = NodeDraftInput(
        kind = kind,
        name = name,
        address = address,
        port = portText,
        uuidOrPassword = uuidOrPass,
        encryption = effectiveEncryption,
        transport = effectiveTransport,
        security = effectiveSecurity,
        sni = sni,
        alpn = alpn,
        fingerprint = fingerprint,
        publicKey = publicKey,
        shortId = shortId,
        path = path,
        hostHeader = hostHeader,
        serviceName = serviceName,
        flow = flow,
        allowInsecure = if (effectiveSecurity == SecurityType.REALITY) false else allowInsecure,
        cipherSuites = cipherSuites,
        finalMask = finalMask,
        echConfigList = echConfigList,
        mldsa65Verify = mldsa65Verify,
        dnsTransportMode = if (kind == ProtocolFormKind.DNSTT || kind == ProtocolFormKind.MASTERDNS) effectiveEncryption else "udp",
        wgMtu = wgMtu,
        awgJc = awgJc,
        awgJmin = awgJmin,
        awgJmax = awgJmax,
        awgS1 = awgS1,
        awgS2 = awgS2,
        awgH1 = awgH1,
        awgH2 = awgH2,
        awgH3 = awgH3,
        awgH4 = awgH4
    )
    val validation = remember(draft) { ProtocolGuardrails.validateNodeDraft(draft) }
    val errors = validation.errors

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (existing == null) "Configure Proxy Node" else "Edit Proxy Node",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Protocol", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ProtocolFormKind.entries.forEach { p ->
                        FilterChip(
                            selected = kind == p,
                            onClick = {
                                val prevDefaultPort = ProtocolGuardrails.defaultPortFor(kind, effectiveEncryption).toString()
                                kind = p
                                val nextSpec = ProtocolGuardrails.fieldSpecFor(
                                    p,
                                    ProtocolGuardrails.defaultTransportFor(p),
                                    ProtocolGuardrails.defaultSecurityFor(p)
                                )
                                transport = nextSpec.supportedTransports.first()
                                security = nextSpec.supportedSecurities.first()
                                val nextDefaultPort = ProtocolGuardrails.defaultPortFor(p, "udp").toString()
                                if (portText.isBlank() || portText == prevDefaultPort) {
                                    portText = nextDefaultPort
                                }
                                when (p) {
                                    ProtocolFormKind.SHADOWSOCKS -> encryption = "aes-256-gcm"
                                    ProtocolFormKind.VMESS -> encryption = "auto"
                                    ProtocolFormKind.DNSTT, ProtocolFormKind.MASTERDNS -> {
                                        encryption = "udp"
                                        if (wgMtu == "1420") wgMtu = "1232"
                                    }
                                    ProtocolFormKind.WIREGUARD -> {
                                        encryption = "quic"
                                        if (path.isBlank()) path = "172.16.0.2/32"
                                        if (shortId.isBlank()) shortId = "0,0,0"
                                        if (finalMask == "awg") finalMask = ""
                                    }
                                    ProtocolFormKind.AMNEZIA_WG -> {
                                        encryption = "quic"
                                        finalMask = "awg"
                                        if (path.isBlank()) path = "172.16.0.2/32"
                                        if (shortId.isBlank()) shortId = "0,0,0"
                                    }
                                    ProtocolFormKind.MASQUE -> {
                                        encryption = "quic"
                                        if (path.isBlank()) path = "172.16.0.2/32"
                                        if (sni.isBlank()) sni = "consumer-masque.cloudflareclient.com"
                                    }
                                    else -> {
                                        if (finalMask == "awg") finalMask = ""
                                    }
                                }
                            },
                            label = { Text(p.label, maxLines = 1) },
                            modifier = Modifier.testTag("proto_chip_${p.name.lowercase()}")
                        )
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Remark / Node Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("editor_name_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Server Host / IP") },
                        singleLine = true,
                        isError = errors.containsKey("address"),
                        supportingText = errors["address"]?.let { err ->
                            { Text(err, modifier = Modifier.testTag("node_validation_error_address")) }
                        },
                        modifier = Modifier
                            .weight(2f)
                            .testTag("editor_address_input")
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Port") },
                        singleLine = true,
                        isError = errors.containsKey("port"),
                        supportingText = errors["port"]?.let { err ->
                            { Text(err, modifier = Modifier.testTag("node_validation_error_port")) }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("editor_port_input")
                    )
                }

                // Auth Secret / UUID / WireGuard Private Key (only shown for protocols that use it)
                if (spec.showUuid || spec.showPassword || spec.showWireGuardFields || spec.showMasqueFields) {
                    val secretLabel = when {
                        spec.showUuid -> "UUID (RFC 4122)"
                        spec.showWireGuardFields -> "${kind.label} Private Key (32-byte Base64)"
                        spec.showMasqueFields -> "MASQUE Private Key / Auth Token"
                        else -> spec.secretLabel
                    }
                    OutlinedTextField(
                        value = uuidOrPass,
                        onValueChange = { uuidOrPass = it },
                        label = { Text(secretLabel) },
                        singleLine = true,
                        isError = errors.containsKey("uuidOrPassword"),
                        supportingText = errors["uuidOrPassword"]?.let { err ->
                            { Text(err, modifier = Modifier.testTag("node_validation_error_uuid")) }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_secret_input")
                    )
                }

                // Cipher selector for Shadowsocks / VMess
                if (spec.showCipherMethod) {
                    Text(
                        text = if (kind == ProtocolFormKind.SHADOWSOCKS) "AEAD Cipher Method" else "VMess Security",
                        style = MaterialTheme.typography.labelMedium
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        spec.cipherMethodOptions.forEach { method ->
                            FilterChip(
                                selected = effectiveEncryption == method,
                                onClick = { encryption = method },
                                label = { Text(method, maxLines = 1) }
                            )
                        }
                    }
                }

                // Flow selector (VLESS only)
                if (spec.showFlow) {
                    Text("VLESS Flow Control", style = MaterialTheme.typography.labelMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("" to "None", "xtls-rprx-vision" to "xtls-rprx-vision", "xtls-rprx-vision-udp443" to "vision-udp443").forEach { (value, label) ->
                            FilterChip(
                                selected = flow == value,
                                onClick = { flow = value },
                                label = { Text(label, maxLines = 1) },
                                modifier = Modifier.testTag("editor_flow_chip_${label.lowercase()}")
                            )
                        }
                    }
                }

                // Transport selector (shown only when protocol supports multiple transports)
                if (spec.supportedTransports.size > 1) {
                    Text("Transport", style = MaterialTheme.typography.labelMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        spec.supportedTransports.forEach { t ->
                            FilterChip(
                                selected = effectiveTransport == t,
                                onClick = { transport = t },
                                label = { Text(t.label, maxLines = 1) },
                                modifier = Modifier.testTag("editor_transport_chip_${t.name.lowercase()}")
                            )
                        }
                    }
                }

                // Path & Host Header (ONLY for WS, XHTTP, HTTPUpgrade)
                if (spec.showPathAndHost) {
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text("Path (e.g. /ws or /xhttp)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_path_input")
                    )
                    OutlinedTextField(
                        value = hostHeader,
                        onValueChange = { hostHeader = it },
                        label = { Text("Host Header") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_host_input")
                    )
                }

                if (spec.showXhttpNotice) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "XHTTP packet/stream split settings follow Expert Lab XHTTP Mode.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // gRPC Service Name (ONLY for gRPC)
                if (spec.showGrpcServiceName) {
                    OutlinedTextField(
                        value = serviceName,
                        onValueChange = { serviceName = it },
                        label = { Text("gRPC Service Name") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_grpc_service_input")
                    )
                }

                // Security selector (shown only when protocol supports > 1 security mode)
                if (spec.supportedSecurities.size > 1) {
                    Text("Security", style = MaterialTheme.typography.labelMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        spec.supportedSecurities.forEach { s ->
                            FilterChip(
                                selected = effectiveSecurity == s,
                                onClick = { security = s },
                                label = { Text(s.label, maxLines = 1) },
                                modifier = Modifier.testTag("editor_security_chip_${s.name.lowercase()}")
                            )
                        }
                    }
                }

                // SNI (TLS, REALITY, Hysteria2, MASQUE)
                if (spec.showTlsFields || spec.showRealityFields || spec.showHysteriaFields || spec.showMasqueFields) {
                    OutlinedTextField(
                        value = sni,
                        onValueChange = { sni = it },
                        label = {
                            Text(
                                if (spec.showRealityFields) "REALITY Target SNI (required)"
                                else if (spec.showMasqueFields) "MASQUE SNI (e.g. consumer-masque.cloudflareclient.com)"
                                else "SNI / Server Name"
                            )
                        },
                        singleLine = true,
                        isError = errors.containsKey("sni"),
                        supportingText = errors["sni"]?.let { err -> { Text(err) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_sni_input")
                    )
                }

                // uTLS Fingerprint (TLS & REALITY)
                if (spec.showTlsFields || spec.showRealityFields) {
                    Text("uTLS Fingerprint (fp)", style = MaterialTheme.typography.labelMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("chrome", "randomized", "firefox", "safari", "ios", "unsafe").forEach { fpOption ->
                            FilterChip(
                                selected = fingerprint == fpOption,
                                onClick = { fingerprint = fpOption },
                                label = { Text(fpOption, maxLines = 1) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = alpn,
                        onValueChange = { alpn = it },
                        label = { Text("ALPN (e.g. h2,http/1.1)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // TLS-only CipherSuites & ECH (HIDDEN for REALITY!)
                if (spec.showCipherSuitesAndEch) {
                    Text("TLS Cipher Suites (cs)", style = MaterialTheme.typography.labelMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LuxyVpnCore.CIPHER_SUITE_PRESETS.forEach { (label, value) ->
                            FilterChip(
                                selected = cipherSuites == value,
                                onClick = { cipherSuites = value },
                                label = { Text(label, maxLines = 1) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = cipherSuites,
                        onValueChange = { cipherSuites = it },
                        label = { Text("Cipher Suites Override") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_ciphersuites_input")
                    )
                    OutlinedTextField(
                        value = echConfigList,
                        onValueChange = { echConfigList = it },
                        label = { Text("ECH Config List (optional)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_ech_input")
                    )
                    Text("ECH Force Query", style = MaterialTheme.typography.labelMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("none", "half", "full").forEach { mode ->
                            FilterChip(
                                selected = echForceQuery == mode,
                                onClick = { echForceQuery = mode },
                                label = { Text(mode, maxLines = 1) }
                            )
                        }
                    }
                }

                // REALITY-specific fields
                if (spec.showRealityFields) {
                    OutlinedTextField(
                        value = publicKey,
                        onValueChange = { publicKey = it },
                        label = { Text("REALITY Public Key (pbk)") },
                        singleLine = true,
                        isError = errors.containsKey("publicKey"),
                        supportingText = errors["publicKey"]?.let { err -> { Text(err) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_reality_pbk_input")
                    )
                    OutlinedTextField(
                        value = shortId,
                        onValueChange = { shortId = it },
                        label = { Text("REALITY Short ID (sid, even hex)") },
                        singleLine = true,
                        isError = errors.containsKey("shortId"),
                        supportingText = errors["shortId"]?.let { err -> { Text(err) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_reality_sid_input")
                    )
                    OutlinedTextField(
                        value = mldsa65Verify,
                        onValueChange = { mldsa65Verify = it },
                        label = { Text("Post-Quantum mldsa65Verify (pqv, optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // WireGuard / AmneziaWG / MASQUE specific fields
                if (spec.showWireGuardFields || spec.showMasqueFields) {
                    OutlinedTextField(
                        value = publicKey,
                        onValueChange = { publicKey = it },
                        label = { Text("Peer Public Key (Base64)") },
                        singleLine = true,
                        isError = errors.containsKey("publicKey"),
                        supportingText = errors["publicKey"]?.let { err -> { Text(err) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_wg_pubkey_input")
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = path,
                            onValueChange = { path = it },
                            label = { Text("Local Tunnel Address") },
                            singleLine = true,
                            modifier = Modifier.weight(1.5f)
                        )
                        OutlinedTextField(
                            value = wgMtu,
                            onValueChange = { wgMtu = it.filter { ch -> ch.isDigit() } },
                            label = { Text("MTU") },
                            singleLine = true,
                            isError = errors.containsKey("wgMtu"),
                            supportingText = errors["wgMtu"]?.let { err -> { Text(err) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(0.8f)
                        )
                    }
                    if (spec.showWireGuardFields) {
                        OutlinedTextField(
                            value = shortId,
                            onValueChange = { shortId = it },
                            label = { Text("WARP Reserved Bytes (e.g. 0,0,0)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text("Datagram Shaping Preset", style = MaterialTheme.typography.labelMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LuxyVpnCore.DATAGRAM_SHAPING_PRESETS.forEach { preset ->
                            FilterChip(
                                selected = effectiveEncryption == preset,
                                onClick = { encryption = preset },
                                label = { Text(preset.uppercase(), maxLines = 1) }
                            )
                        }
                    }
                }

                // AmneziaWG Junk Padding Parameters
                if (spec.showAwgFields) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("AmneziaWG Junk & Header Padding", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = awgJc,
                            onValueChange = { awgJc = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Jc") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).testTag("editor_awg_jc_input")
                        )
                        OutlinedTextField(
                            value = awgJmin,
                            onValueChange = { awgJmin = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Jmin") },
                            singleLine = true,
                            isError = errors.containsKey("awgJunk"),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).testTag("editor_awg_jmin_input")
                        )
                        OutlinedTextField(
                            value = awgJmax,
                            onValueChange = { awgJmax = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Jmax") },
                            singleLine = true,
                            isError = errors.containsKey("awgJunk"),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).testTag("editor_awg_jmax_input")
                        )
                    }
                    errors["awgJunk"]?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = awgS1,
                            onValueChange = { awgS1 = it.filter { ch -> ch.isDigit() } },
                            label = { Text("S1") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).testTag("editor_awg_s1_input")
                        )
                        OutlinedTextField(
                            value = awgS2,
                            onValueChange = { awgS2 = it.filter { ch -> ch.isDigit() } },
                            label = { Text("S2") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).testTag("editor_awg_s2_input")
                        )
                        OutlinedTextField(
                            value = awgH1,
                            onValueChange = { awgH1 = it.filter { ch -> ch.isDigit() } },
                            label = { Text("H1") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).testTag("editor_awg_h1_input")
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = awgH2,
                            onValueChange = { awgH2 = it.filter { ch -> ch.isDigit() } },
                            label = { Text("H2") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = awgH3,
                            onValueChange = { awgH3 = it.filter { ch -> ch.isDigit() } },
                            label = { Text("H3") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = awgH4,
                            onValueChange = { awgH4 = it.filter { ch -> ch.isDigit() } },
                            label = { Text("H4") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // DNSTT / MasterDNS specific fields
                if (spec.showDnsTunnelFields) {
                    OutlinedTextField(
                        value = hostHeader,
                        onValueChange = { hostHeader = it },
                        label = { Text("Authoritative Tunnel Domain / NS (e.g. t.example.com)") },
                        singleLine = true,
                        isError = errors.containsKey("hostHeader"),
                        supportingText = errors["hostHeader"]?.let { err -> { Text(err) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_dns_domain_input")
                    )
                    OutlinedTextField(
                        value = publicKey,
                        onValueChange = { publicKey = it },
                        label = { Text("DNS Tunnel Public Key (Hex / Base64)") },
                        singleLine = true,
                        isError = errors.containsKey("publicKey"),
                        supportingText = errors["publicKey"]?.let { err -> { Text(err) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_dns_pubkey_input")
                    )
                    OutlinedTextField(
                        value = wgMtu,
                        onValueChange = { wgMtu = it.filter { ch -> ch.isDigit() } },
                        label = { Text("DNS Tunnel MTU (e.g. 1232 or 512)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("DNS Tunnel Transport Mode", style = MaterialTheme.typography.labelMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("udp", "tcp", "dot", "doh").forEach { dnsMode ->
                            FilterChip(
                                selected = effectiveEncryption == dnsMode,
                                onClick = {
                                    encryption = dnsMode
                                    val prevDefault = ProtocolGuardrails.defaultPortFor(kind, effectiveEncryption).toString()
                                    if (portText.isBlank() || portText == prevDefault) {
                                        portText = ProtocolGuardrails.defaultPortFor(kind, dnsMode).toString()
                                    }
                                },
                                label = { Text(dnsMode.uppercase(), maxLines = 1) },
                                modifier = Modifier.testTag("editor_dns_mode_$dnsMode")
                            )
                        }
                    }
                    if (effectiveEncryption == "doh") {
                        OutlinedTextField(
                            value = path,
                            onValueChange = { path = it },
                            label = { Text("DoH Endpoint URL") },
                            singleLine = true,
                            isError = errors.containsKey("path"),
                            supportingText = errors["path"]?.let { err -> { Text(err) } },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (effectiveEncryption == "dot" || effectiveEncryption == "doh") {
                        OutlinedTextField(
                            value = sni,
                            onValueChange = { sni = it },
                            label = { Text("Resolver TLS SNI (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // FinalMask (non-AWG)
                if (spec.showFinalMaskField && !spec.showAwgFields) {
                    Text("FinalMask Obfuscation (fm)", style = MaterialTheme.typography.labelMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LuxyVpnCore.FINAL_MASK_PRESETS.forEach { mask ->
                            val effective = if (mask == "none") "" else mask
                            FilterChip(
                                selected = finalMask == effective,
                                onClick = { finalMask = effective },
                                label = { Text(mask, maxLines = 1) }
                            )
                        }
                    }
                }

                // Allow Insecure TLS (ONLY when TLS / Hysteria2 / MASQUE; hidden on REALITY & plain protocols)
                if (spec.showAllowInsecure) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Allow Insecure TLS",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Switch(
                            checked = allowInsecure,
                            onCheckedChange = { allowInsecure = it },
                            modifier = Modifier.testTag("editor_allow_insecure_switch")
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val isDns = spec.showDnsTunnelFields
                    val isWgLike = spec.showWireGuardFields || spec.showMasqueFields
                    val computedServiceName = when {
                        spec.showAwgFields -> AwgProfileParams(
                            jc = awgJc.toIntOrNull() ?: 4,
                            jmin = awgJmin.toIntOrNull() ?: 40,
                            jmax = awgJmax.toIntOrNull() ?: 70,
                            s1 = awgS1.toIntOrNull() ?: 0,
                            s2 = awgS2.toIntOrNull() ?: 0,
                            h1 = awgH1.toLongOrNull() ?: 1L,
                            h2 = awgH2.toLongOrNull() ?: 2L,
                            h3 = awgH3.toLongOrNull() ?: 3L,
                            h4 = awgH4.toLongOrNull() ?: 4L
                        ).encodeQuery()
                        isDns -> effectiveEncryption
                        spec.showGrpcServiceName -> serviceName.trim()
                        else -> ""
                    }
                    val computedSecurity = when {
                        isDns -> if (effectiveEncryption in setOf("dot", "doh")) SecurityType.TLS else SecurityType.NONE
                        else -> effectiveSecurity
                    }
                    val computedFlow = when {
                        isWgLike || isDns -> wgMtu.trim()
                        spec.showFlow -> flow.trim()
                        else -> ""
                    }
                    val computedSecret = when {
                        isDns -> uuidOrPass.trim().ifBlank { publicKey.trim() }
                        else -> uuidOrPass.trim()
                    }

                    val entity = (existing ?: ServerProfileEntity(
                        id = UUID.randomUUID().toString(),
                        name = "",
                        address = "",
                        port = 443,
                        uuidOrPassword = ""
                    )).copy(
                        name = name.trim().ifBlank { "${address.trim()}:${portText.trim()}" },
                        protocol = kind.underlying.name,
                        address = address.trim(),
                        port = portText.trim().toIntOrNull() ?: 443,
                        uuidOrPassword = computedSecret,
                        encryption = effectiveEncryption,
                        transport = effectiveTransport.name,
                        security = computedSecurity.name,
                        sni = sni.trim(),
                        alpn = alpn.trim().ifBlank { "h2,http/1.1" },
                        fingerprint = fingerprint.trim(),
                        publicKey = publicKey.trim(),
                        shortId = shortId.trim(),
                        path = path.trim(),
                        hostHeader = hostHeader.trim(),
                        serviceName = computedServiceName,
                        flow = computedFlow,
                        allowInsecure = if (spec.showAllowInsecure) allowInsecure else false,
                        cipherSuites = if (spec.showCipherSuitesAndEch) cipherSuites.trim() else "",
                        finalMask = if (spec.showAwgFields) "awg" else finalMask.trim(),
                        echConfigList = if (spec.showCipherSuitesAndEch) echConfigList.trim() else "",
                        echForceQuery = if (spec.showCipherSuitesAndEch) echForceQuery.trim() else "none",
                        mldsa65Verify = if (spec.showRealityFields) mldsa65Verify.trim() else "",
                        rawUri = ""
                    )
                    onSave(ProtocolGuardrails.sanitizeProfileForSave(entity))
                    onDismiss()
                },
                enabled = validation.isValid,
                modifier = Modifier.testTag("save_node_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ShareNodeQrDialog(
    profile: ServerProfileEntity,
    onDismiss: () -> Unit,
    onCopyUri: (String) -> Unit
) {
    if (profile.isLocked) {
        val expiryText = profile.vaultExpiresAtMs?.let { Formatters.formatDateTime(it) } ?: "No expiration"
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = profile.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Zero-Knowledge Vault Profile — plaintext URI & QR export are permanently disabled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Scheme: ${profile.vaultKeyScheme.ifBlank { "AES-256-GCM" }}", style = MaterialTheme.typography.labelMedium)
                    Text("Expiry: $expiryText", style = MaterialTheme.typography.labelMedium)
                    Text("Endpoint: ${EncryptedConfigVault.REDACTED_VALUE}", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        )
        return
    }

    val shareUri = remember(profile) { ProxyUriParser.toShareUri(profile) }
    val qrBitmap = remember(shareUri) { QrCodeGenerator.generateQrBitmap(shareUri, 512) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = profile.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (qrBitmap != null) {
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "QR code for ${profile.name}",
                            modifier = Modifier
                                .size(210.dp)
                                .padding(10.dp)
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = shareUri,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCopyUri(shareUri)
                    onDismiss()
                }
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Copy URI")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubscriptionManagerDialog(
    subscriptions: List<SubscriptionEntity>,
    isSyncing: Boolean,
    topNLimit: Int = 50,
    onSetTopNLimit: (Int) -> Unit = {},
    onDismiss: () -> Unit,
    onAddSubscription: (name: String, url: String, intervalHours: Int) -> Unit,
    onSyncOne: (String) -> Unit,
    onSyncAll: () -> Unit,
    onDelete: (String) -> Unit
) {
    var subName by remember { mutableStateOf("") }
    var subUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subscriptions", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 490.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isSyncing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Text(
                    text = "Lazy Top-N Optimizer (keeps highest-ranked profiles per feed)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(25 to "Top 25", 50 to "Top 50", 100 to "Top 100", 0 to "All").forEach { (limit, label) ->
                        FilterChip(
                            selected = topNLimit == limit,
                            onClick = { onSetTopNLimit(limit) },
                            label = { Text(label, maxLines = 1) }
                        )
                    }
                }

                OutlinedTextField(
                    value = subName,
                    onValueChange = { subName = it },
                    label = { Text("Subscription Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("sub_name_input")
                )
                OutlinedTextField(
                    value = subUrl,
                    onValueChange = { subUrl = it },
                    label = { Text("https:// Subscription URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("sub_url_input")
                )
                Button(
                    onClick = {
                        onAddSubscription(subName.ifBlank { "Subscription" }, subUrl.trim(), 12)
                        subName = ""
                        subUrl = ""
                    },
                    enabled = subUrl.isNotBlank() && !isSyncing,
                    modifier = Modifier.fillMaxWidth().testTag("add_sub_button")
                ) {
                    Text("Add & Sync Subscription", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                if (subscriptions.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Active Feeds (${subscriptions.size})",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        OutlinedButton(onClick = onSyncAll, enabled = !isSyncing) {
                            Text("Sync All")
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 190.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(subscriptions, key = { it.id }) { sub ->
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .wrapContentHeight()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = sub.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${sub.lastStatus} • ${Formatters.formatDateTime(sub.lastUpdatedAt)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = { onSyncOne(sub.id) },
                                        enabled = !isSyncing,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = "Sync subscription",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDelete(sub.id) },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Delete,
                                            contentDescription = "Delete subscription",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VaultImportDialog(
    onDismiss: () -> Unit,
    onUnlockVault: (payload: String, passphrase: String?, privateKeyB64: String?) -> Unit,
    onCopyGeneratedPublicKey: (String) -> Unit
) {
    var payload by remember { mutableStateOf("") }
    var useX25519 by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var privateKeyB64 by remember { mutableStateOf("") }
    var generatedPair by remember { mutableStateOf<X25519KeyPair?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.VpnKey,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Unlock Encrypted Vault (.zsx)",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Paste a luxy-zsx://, kage://, or JSON .zsx vault. Imported nodes stay locked so credentials & IPs remain redacted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = payload,
                    onValueChange = { payload = it },
                    label = { Text("Vault Payload (.zsx / .kage)") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().testTag("vault_import_payload_input")
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !useX25519,
                        onClick = { useX25519 = false },
                        label = { Text("Passphrase (AES-GCM)", maxLines = 1) }
                    )
                    FilterChip(
                        selected = useX25519,
                        onClick = { useX25519 = true },
                        label = { Text("X25519 Private Key", maxLines = 1) }
                    )
                }
                if (!useX25519) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Vault Passphrase") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("vault_passphrase_input")
                    )
                } else {
                    OutlinedTextField(
                        value = privateKeyB64,
                        onValueChange = { privateKeyB64 = it },
                        label = { Text("Recipient X25519 Private Key (Base64)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("vault_privkey_input")
                    )
                    OutlinedButton(
                        onClick = {
                            val pair = EncryptedConfigVault.generateX25519KeyPair()
                            generatedPair = pair
                            privateKeyB64 = pair.privateKeyBase64
                            onCopyGeneratedPublicKey(pair.publicKeyBase64)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Generate X25519 Keypair & Copy Public Key", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    generatedPair?.let { pair ->
                        Text(
                            text = "Public Key (share with sender): ${pair.publicKeyBase64}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onUnlockVault(
                        payload,
                        if (useX25519) null else passphrase,
                        if (useX25519) privateKeyB64 else null
                    )
                },
                enabled = payload.isNotBlank() && ((!useX25519 && passphrase.isNotBlank()) || (useX25519 && privateKeyB64.isNotBlank())),
                modifier = Modifier.testTag("confirm_unlock_vault_button")
            ) {
                Text("Unlock & Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VaultExportDialog(
    profile: ServerProfileEntity,
    onExport: (passphrase: String?, recipientPubKey: String?, ttlHours: Int?) -> Result<String>,
    onCopyVault: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var useX25519 by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var recipientPubKey by remember { mutableStateOf("") }
    var ttlHours by remember { mutableStateOf<Int?>(null) }
    var exportedEnvelope by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Locked Vault (.zsx)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Sealing '${profile.name}' with AES-256-GCM + AAD expiry binding.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !useX25519,
                        onClick = { useX25519 = false },
                        label = { Text("Passphrase", maxLines = 1) }
                    )
                    FilterChip(
                        selected = useX25519,
                        onClick = { useX25519 = true },
                        label = { Text("X25519 PubKey", maxLines = 1) }
                    )
                }
                if (!useX25519) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Passphrase") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = recipientPubKey,
                        onValueChange = { recipientPubKey = it },
                        label = { Text("Recipient X25519 Public Key (Base64)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text("Expiration", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(null to "Never", 24 to "24h", 168 to "7 Days", 720 to "30 Days").forEach { (hrs, label) ->
                        FilterChip(
                            selected = ttlHours == hrs,
                            onClick = { ttlHours = hrs },
                            label = { Text(label, maxLines = 1) }
                        )
                    }
                }
                Button(
                    onClick = {
                        val res = onExport(
                            if (useX25519) null else passphrase,
                            if (useX25519) recipientPubKey else null,
                            ttlHours
                        )
                        res.onSuccess {
                            exportedEnvelope = it
                            errorMsg = null
                        }.onFailure {
                            errorMsg = it.localizedMessage ?: "Export failed"
                        }
                    },
                    enabled = (!useX25519 && passphrase.isNotBlank()) || (useX25519 && recipientPubKey.isNotBlank()),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Seal .zsx Vault")
                }
                errorMsg?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                exportedEnvelope?.let { env ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = env,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            val env = exportedEnvelope
            if (env != null) {
                Button(onClick = {
                    onCopyVault(env)
                    onDismiss()
                }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy .zsx")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (exportedEnvelope != null) {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}
