package com.example.ui.screens

import android.content.ClipData
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.core.EncryptedConfigVault
import com.example.core.NodeDraftInput
import com.example.core.ProtocolGuardrails
import com.example.data.model.NodeFeatureOverrides
import com.example.data.model.TriState
import com.example.i18n.tr
import com.example.core.ProxyUriParser
import com.example.core.QrCodeGenerator
import com.example.core.RawXrayJsonSanitizer
import com.example.core.VaultImportResult
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.model.ProtocolFormKind
import com.example.data.model.SecurityType
import com.example.data.model.StabilitySettings
import com.example.data.model.TransportType
import com.example.ui.theme.LocalLuxyColors
import com.example.ui.theme.MonoFontFamily

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddEditNodeDialog(
    initialNode: ServerProfileEntity?,
    experimentalModeEnabled: Boolean = false,
    globalAllowUnencryptedVless: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (ServerProfileEntity) -> Unit
) {
    val colors = LocalLuxyColors.current
    val initialKind = ProtocolGuardrails.protocolFormKindFromProfile(initialNode)
    val initialAwg = initialNode?.serviceName?.takeIf { it.isNotBlank() }?.let { ProxyUriParser.parseAwgQuery(it) }

    var draft by remember {
        mutableStateOf(
            if (initialNode == null) {
                NodeDraftInput()
            } else {
                NodeDraftInput(
                    kind = initialKind,
                    name = initialNode.name,
                    address = initialNode.address,
                    port = initialNode.port.toString(),
                    uuidOrPassword = initialNode.uuidOrPassword,
                    encryption = initialNode.encryption,
                    transport = TransportType.parse(initialNode.transport),
                    security = SecurityType.parse(initialNode.security),
                    sni = initialNode.sni,
                    alpn = initialNode.alpn,
                    fingerprint = initialNode.fingerprint,
                    publicKey = initialNode.publicKey,
                    shortId = initialNode.shortId,
                    path = initialNode.path,
                    hostHeader = initialNode.hostHeader,
                    serviceName = initialNode.serviceName,
                    flow = initialNode.flow,
                    allowInsecure = initialNode.allowInsecure,
                    cipherSuites = initialNode.cipherSuites,
                    finalMask = initialNode.finalMask,
                    echConfigList = initialNode.echConfigList,
                    mldsa65Verify = initialNode.mldsa65Verify,
                    wgMtu = initialNode.flow,
                    awgJc = (initialAwg?.jc ?: 4).toString(),
                    awgJmin = (initialAwg?.jmin ?: 40).toString(),
                    awgJmax = (initialAwg?.jmax ?: 70).toString(),
                    awgS1 = (initialAwg?.s1 ?: 0).toString(),
                    awgS2 = (initialAwg?.s2 ?: 0).toString(),
                    awgH1 = (initialAwg?.h1 ?: 1).toString(),
                    awgH2 = (initialAwg?.h2 ?: 2).toString(),
                    awgH3 = (initialAwg?.h3 ?: 3).toString(),
                    awgH4 = (initialAwg?.h4 ?: 4).toString(),
                    features = NodeFeatureOverrides.decode(initialNode.featureOverrides)
                )
            }
        )
    }
    var validationErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val spec = ProtocolGuardrails.fieldSpecFor(
        kind = draft.kind,
        requestedTransport = draft.transport,
        requestedSecurity = draft.security,
        experimentalModeEnabled = experimentalModeEnabled
    )
    val unencryptedVlessEnabled = experimentalModeEnabled &&
        draft.features.allowUnencryptedVless.resolve(globalAllowUnencryptedVless)
    val vlessEncryptionEnabled = draft.kind == ProtocolFormKind.VLESS &&
        ProtocolGuardrails.hasVlessEncryption(draft.encryption)
    val visibleSecurities = if (draft.kind == ProtocolFormKind.VLESS &&
        !unencryptedVlessEnabled && !vlessEncryptionEnabled
    ) {
        spec.supportedSecurities.filter { it != SecurityType.NONE }
    } else {
        spec.supportedSecurities
    }

    val featureMatrix = ProtocolGuardrails.featureMatrix(
        kind = draft.kind,
        transport = draft.transport,
        security = draft.security,
        flow = draft.flow,
        experimentalModeEnabled = experimentalModeEnabled
    )
    val availableKinds = remember(experimentalModeEnabled, initialKind) {
        ProtocolFormKind.entries.filter { kind ->
            ProtocolGuardrails.isCompiledByCore(kind.underlying) &&
                (experimentalModeEnabled || ProtocolGuardrails.isStandardProtocolKind(kind) || kind == initialKind)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(
                text = if (initialNode == null) "Add Server Profile" else "Edit Server Profile",
                color = colors.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(tr("Protocol"), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    availableKinds.forEach { k ->
                        FilterChip(
                            selected = draft.kind == k,
                            onClick = {
                                draft = draft.copy(
                                    kind = k,
                                    transport = ProtocolGuardrails.defaultTransportFor(k),
                                    security = ProtocolGuardrails.defaultSecurityFor(k),
                                    port = if (initialNode == null) {
                                        ProtocolGuardrails.defaultPortFor(k).toString()
                                    } else {
                                        draft.port
                                    }
                                )
                                validationErrors = emptyMap()
                            },
                            label = { Text(tr(k.label), fontSize = 11.sp) }
                        )
                    }
                }
                if (!ProtocolGuardrails.isCompiledByCore(draft.kind.underlying)) {
                    Text(
                        tr("This server type is not supported by Xray. Choose a supported protocol to convert it."),
                        color = colors.statusError,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text(tr("Profile Name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft.address,
                        onValueChange = { draft = draft.copy(address = it) },
                        label = { Text(tr("Server / IP")) },
                        isError = validationErrors.containsKey("address"),
                        supportingText = validationErrors["address"]?.let { { Text(tr(it)) } },
                        singleLine = true,
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = draft.port,
                        onValueChange = { draft = draft.copy(port = it.filter { c -> c.isDigit() }) },
                        label = { Text(tr("Port")) },
                        isError = validationErrors.containsKey("port"),
                        supportingText = validationErrors["port"]?.let { { Text(tr(it)) } },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (spec.showUuid || spec.showPassword || spec.showWireGuardFields || spec.showMasqueFields) {
                    OutlinedTextField(
                        value = draft.uuidOrPassword,
                        onValueChange = { draft = draft.copy(uuidOrPassword = it) },
                        label = {
                            Text(
                                when {
                                    spec.showWireGuardFields -> "Private Key (Base64)"
                                    spec.showMasqueFields -> "MASQUE Private Key / Auth"
                                    spec.showUuid -> "UUID"
                                    else -> spec.secretLabel
                                }
                            )
                        },
                        isError = validationErrors.containsKey("uuidOrPassword"),
                        supportingText = validationErrors["uuidOrPassword"]?.let { { Text(tr(it)) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (spec.showCipherMethod) {
                    Text(tr("Cipher Method"), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        spec.cipherMethodOptions.forEach { method ->
                            FilterChip(
                                selected = draft.encryption == method,
                                onClick = { draft = draft.copy(encryption = method) },
                                label = { Text(method, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                if (spec.showEncryption && draft.kind == com.example.data.model.ProtocolFormKind.VLESS) {
                    Text(tr("Encryption"), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        spec.encryptionOptions.forEach { method ->
                            val mlkemMethod = method == "mlkem768x25519plus"
                            FilterChip(
                                selected = if (mlkemMethod) vlessEncryptionEnabled else draft.encryption.ifBlank { "none" } == method,
                                onClick = {
                                    draft = when {
                                        mlkemMethod && !vlessEncryptionEnabled ->
                                            draft.copy(encryption = "$method.xorpub.0rtt.")
                                        mlkemMethod -> draft
                                        else -> draft.copy(encryption = method)
                                    }
                                    validationErrors = validationErrors - "encryption"
                                },
                                label = { Text(method, fontSize = 11.sp) }
                            )
                        }
                    }
                    if (experimentalModeEnabled) {
                        OutlinedTextField(
                            value = draft.encryption,
                            onValueChange = {
                                draft = draft.copy(encryption = it.trim())
                                validationErrors = validationErrors - "encryption"
                            },
                            label = { Text(tr("Encryption value")) },
                            supportingText = validationErrors["encryption"]?.let { { Text(tr(it)) } },
                            isError = validationErrors.containsKey("encryption"),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (spec.supportedTransports.size > 1) {
                    Text(tr("Transport"), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        spec.supportedTransports.forEach { tr ->
                            FilterChip(
                                selected = draft.transport == tr,
                                onClick = { draft = draft.copy(transport = tr) },
                                label = { Text(tr(tr.label), fontSize = 11.sp) }
                            )
                        }
                    }
                }

                if (visibleSecurities.size > 1) {
                    Text(tr("Security"), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        visibleSecurities.forEach { sec ->
                            FilterChip(
                                selected = draft.security == sec,
                                onClick = { draft = draft.copy(security = sec) },
                                label = { Text(tr(sec.label), fontSize = 11.sp) }
                            )
                        }
                    }
                }
                validationErrors["security"]?.let { Text(tr(it), color = colors.statusError, fontSize = 11.sp) }

                if (featureMatrix.flowAllowed) {
                    Text(tr("Flow"), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("" to tr("None"), "xtls-rprx-vision" to "xtls-rprx-vision").forEach { (value, label) ->
                            FilterChip(
                                selected = draft.flow == value,
                                onClick = {
                                    draft = draft.copy(
                                        flow = value,
                                        features = if (value.isNotBlank()) draft.features.copy(mux = TriState.OFF) else draft.features
                                    )
                                },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                    }
                    if (validationErrors["flow"] != null) {
                        Text(tr(validationErrors["flow"].orEmpty()), color = colors.statusError, fontSize = 11.sp)
                    }
                }

                if (spec.showTlsFields || spec.showRealityFields) {
                    Text(tr("Fingerprint"), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val fps = listOf("chrome", "firefox", "safari", "ios", "android", "edge", "randomized", "qq") +
                            if (experimentalModeEnabled) listOf("unsafe") else emptyList()
                        fps.forEach { fp ->
                            FilterChip(
                                selected = draft.fingerprint == fp,
                                onClick = { draft = draft.copy(fingerprint = fp) },
                                label = { Text(fp, fontSize = 11.sp) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = draft.alpn,
                        onValueChange = { draft = draft.copy(alpn = it) },
                        label = { Text(tr("ALPN (comma-separated)")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (spec.showPathAndHost) {
                    OutlinedTextField(
                        value = draft.hostHeader,
                        onValueChange = { draft = draft.copy(hostHeader = it) },
                        label = { Text(tr("Host Header")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draft.path,
                        onValueChange = { draft = draft.copy(path = it) },
                        label = { Text(tr("Path (e.g. /ws)")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (spec.showGrpcServiceName) {
                    OutlinedTextField(
                        value = draft.serviceName,
                        onValueChange = { draft = draft.copy(serviceName = it) },
                        label = { Text(tr("gRPC serviceName")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (spec.showTlsFields || spec.showRealityFields || spec.showMasqueFields) {
                    OutlinedTextField(
                        value = draft.sni,
                        onValueChange = { draft = draft.copy(sni = it) },
                        label = { Text(tr("SNI Domain")) },
                        isError = validationErrors.containsKey("sni"),
                        supportingText = validationErrors["sni"]?.let { { Text(tr(it)) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (spec.showRealityFields || spec.showWireGuardFields || spec.showMasqueFields) {
                    OutlinedTextField(
                        value = draft.publicKey,
                        onValueChange = { draft = draft.copy(publicKey = it) },
                        label = { Text(if (spec.showRealityFields) "REALITY Public Key (pbk)" else "Peer / Tunnel Public Key") },
                        isError = validationErrors.containsKey("publicKey"),
                        supportingText = validationErrors["publicKey"]?.let { { Text(tr(it)) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (spec.showRealityFields) {
                    OutlinedTextField(
                        value = draft.shortId,
                        onValueChange = { draft = draft.copy(shortId = it) },
                        label = { Text(tr("REALITY Short ID (sid)")) },
                        isError = validationErrors.containsKey("shortId"),
                        supportingText = validationErrors["shortId"]?.let { { Text(tr(it)) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (experimentalModeEnabled) {
                        OutlinedTextField(
                            value = draft.mldsa65Verify,
                            onValueChange = { draft = draft.copy(mldsa65Verify = it) },
                            label = { Text(tr("ML-DSA-65 Verify Key (pqv, optional)")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (spec.showCipherSuitesAndEch) {
                    OutlinedTextField(
                        value = draft.cipherSuites,
                        onValueChange = { draft = draft.copy(cipherSuites = it) },
                        label = { Text(tr("Custom CipherSuites (optional)")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draft.echConfigList,
                        onValueChange = { draft = draft.copy(echConfigList = it) },
                        label = { Text(tr("ECH ConfigList (optional)")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (spec.showFinalMaskField) {
                    OutlinedTextField(
                        value = draft.finalMask,
                        onValueChange = { draft = draft.copy(finalMask = it) },
                        label = { Text(tr("FinalMask / Framing (fm, optional)")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }


                if (spec.showAwgFields) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.awgJc,
                            onValueChange = { draft = draft.copy(awgJc = it.filter { c -> c.isDigit() }) },
                            label = { Text(tr("Jc")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = draft.awgJmin,
                            onValueChange = { draft = draft.copy(awgJmin = it.filter { c -> c.isDigit() }) },
                            label = { Text(tr("Jmin")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = draft.awgJmax,
                            onValueChange = { draft = draft.copy(awgJmax = it.filter { c -> c.isDigit() }) },
                            label = { Text(tr("Jmax")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    validationErrors["awgJunk"]?.let { err ->
                        Text(err, color = colors.statusError, style = MaterialTheme.typography.bodySmall)
                    }
                }

                ProfileFeatureSection(
                    features = draft.features,
                    matrix = featureMatrix,
                    experimentalModeEnabled = experimentalModeEnabled,
                    onChange = { draft = draft.copy(features = it) }
                )

                if (spec.showAllowInsecure) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tr("Allow Insecure Certificate"), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                        Switch(checked = draft.allowInsecure, onCheckedChange = { draft = draft.copy(allowInsecure = it) })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = ProtocolGuardrails.isCompiledByCore(draft.kind.underlying),
                onClick = {
                    val coerced = if (draft.kind == com.example.data.model.ProtocolFormKind.VLESS &&
                        !experimentalModeEnabled && draft.encryption != "none"
                    ) {
                        draft.copy(encryption = "none")
                    } else draft
                    val res = ProtocolGuardrails.validateNodeDraft(
                        coerced,
                        experimentalModeEnabled,
                        unencryptedVlessEnabled
                    )
                    if (!res.isValid) {
                        validationErrors = res.errors
                        return@Button
                    }
                    val awgQuery = if (draft.kind == ProtocolFormKind.AMNEZIA_WG) {
                        "jc=${draft.awgJc.toIntOrNull() ?: 4}&jmin=${draft.awgJmin.toIntOrNull() ?: 40}&jmax=${draft.awgJmax.toIntOrNull() ?: 70}&s1=${draft.awgS1.toIntOrNull() ?: 0}&s2=${draft.awgS2.toIntOrNull() ?: 0}&h1=${draft.awgH1.toIntOrNull() ?: 1}&h2=${draft.awgH2.toIntOrNull() ?: 2}&h3=${draft.awgH3.toIntOrNull() ?: 3}&h4=${draft.awgH4.toIntOrNull() ?: 4}"
                    } else ""
                    val entity = (initialNode ?: ServerProfileEntity()).copy(
                        name = coerced.name.trim().ifEmpty { "${coerced.address.trim()}:${coerced.port}" },
                        protocol = draft.kind.underlying.name,
                        address = draft.address.trim(),
                        port = draft.port.toIntOrNull() ?: 443,
                        uuidOrPassword = draft.uuidOrPassword.trim(),
                        encryption = coerced.encryption.ifBlank { "none" },
                        transport = draft.transport.name,
                        security = draft.security.name,
                        sni = draft.sni.trim(),
                        alpn = draft.alpn.trim(),
                        fingerprint = draft.fingerprint.trim(),
                        publicKey = draft.publicKey.trim(),
                        shortId = draft.shortId.trim(),
                        path = draft.path.trim(),
                        hostHeader = draft.hostHeader.trim(),
                        serviceName = if (draft.kind == ProtocolFormKind.AMNEZIA_WG) awgQuery else draft.serviceName.trim(),
                        flow = if (coerced.kind == ProtocolFormKind.WIREGUARD || coerced.kind == ProtocolFormKind.AMNEZIA_WG || coerced.kind == ProtocolFormKind.MASQUE) {
                            coerced.wgMtu.trim()
                        } else if (featureMatrix.flowAllowed) {
                            coerced.flow.trim()
                        } else {
                            ""
                        },
                        allowInsecure = draft.allowInsecure,
                        cipherSuites = draft.cipherSuites.trim(),
                        finalMask = if (draft.kind == ProtocolFormKind.AMNEZIA_WG) "awg" else draft.finalMask.trim(),
                        echConfigList = draft.echConfigList.trim(),
                        echForceQuery = draft.features.echForceQuery.ifBlank { initialNode?.echForceQuery ?: "none" },
                        mldsa65Verify = draft.mldsa65Verify.trim(),
                        featureOverrides = coerced.features.encode(),
                        rawUri = ""
                    )
                    onSave(entity)
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)
            ) {
                Text(tr("Save Profile"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
        }
    )
}

@Composable
fun BatchImportDialog(
    onDismiss: () -> Unit,
    onImport: (rawInput: String, groupName: String) -> Unit
) {
    val colors = LocalLuxyColors.current
    var rawText by remember { mutableStateOf("") }
    var groupName by remember { mutableStateOf("Imported") }
    var previewStripped by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(rawText) {
        previewStripped = if (RawXrayJsonSanitizer.looksLikeJsonConfig(rawText)) {
            withContext(Dispatchers.Default) {
                RawXrayJsonSanitizer.sanitizeRawXrayJson(rawText).strippedDirectives
            }
        } else {
            emptyList()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text(tr("Import URIs / Base64 / Xray JSON"), color = colors.onSurface) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(tr("Paste Xray-compatible links, subscription URLs, Base64 lists, or Xray JSON. Unsupported outbounds are not imported."),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text(tr("Group Name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = rawText,
                    onValueChange = { rawText = it },
                    label = { Text(tr("URIs or JSON Payload")) },
                    minLines = 5,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                if (previewStripped.isNotEmpty()) {
                    Text(tr("Sanitizer will strip: ${previewStripped.joinToString(", ")}"),
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        color = colors.statusConnecting
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onImport(rawText, groupName.ifBlank { "Imported" }) },
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)
            ) {
                Text(tr("Import"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } }
    )
}

@Composable
fun SubscriptionsDialog(
    subscriptions: List<SubscriptionEntity>,
    settings: StabilitySettings,
    onSaveSubscription: (SubscriptionEntity) -> Unit,
    onRefreshSubscription: (SubscriptionEntity) -> Unit,
    onDeleteSubscription: (SubscriptionEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalLuxyColors.current
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text(tr("Subscriptions Manager"), color = colors.onSurface) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(tr("UA: ${settings.subscriptionUserAgent} • Top-${settings.subscriptionTopNLimit} auto-ranking"),
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(tr("Subscription Name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(tr("HTTPS URL")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (url.isNotBlank()) {
                            onSaveSubscription(
                                SubscriptionEntity(
                                    name = name.trim().ifEmpty { "Subscription" },
                                    url = url.trim()
                                )
                            )
                            name = ""
                            url = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)
                ) {
                    Text(tr("Add & Sync Subscription"))
                }

                subscriptions.forEach { sub ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                        shape = RoundedCornerShape(10.dp),
                        color = colors.surfaceVariant
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(sub.name, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                            Text(sub.lastStatus, fontFamily = MonoFontFamily, fontSize = 11.sp, color = colors.onSurfaceVariant)
                            sub.lastError?.let { err ->
                                Text(err, fontSize = 11.sp, color = colors.statusError)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onRefreshSubscription(sub) }) { Text(tr("Sync")) }
                                TextButton(onClick = { onDeleteSubscription(sub) }) {
                                    Text(tr("Delete"), color = colors.statusError)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Done")) } }
    )
}

@Composable
fun EncryptedVaultDialog(
    profiles: List<ServerProfileEntity>,
    onImportVault: suspend (payload: String, passphrase: String?, privKey: String?) -> VaultImportResult,
    onExportVault: suspend (
        exportNodes: List<ServerProfileEntity>,
        useX25519: Boolean,
        passphrase: String,
        recipientPubKey: String,
        expiresAtMs: Long?
    ) -> String,
    onDismiss: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var modeTab by remember { mutableStateOf(0) } // 0 = Import, 1 = Export
    var payloadInput by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var recipientPubKey by remember { mutableStateOf("") }
    var privateKeyInput by remember { mutableStateOf("") }
    var useX25519 by remember { mutableStateOf(false) }
    var expiryHours by remember { mutableStateOf("") }
    var exportedText by remember { mutableStateOf("") }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text(tr("Zero-Knowledge .zsx Vault"), color = colors.onSurface) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = modeTab == 0, onClick = { modeTab = 0 }, label = { Text(tr("Import Vault")) })
                    FilterChip(selected = modeTab == 1, onClick = { modeTab = 1 }, label = { Text(tr("Export Vault")) })
                }

                if (modeTab == 0) {
                    OutlinedTextField(
                        value = payloadInput,
                        onValueChange = { payloadInput = it },
                        label = { Text(tr("luxy-zsx:// or kage:// payload")) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text(tr("Passphrase (PBKDF2)")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = privateKeyInput,
                        onValueChange = { privateKeyInput = it },
                        label = { Text(tr("OR X25519 Private Key (Base64)")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        enabled = !isBusy,
                        onClick = {
                            scope.launch {
                                isBusy = true
                                val res = onImportVault(
                                    payloadInput,
                                    passphrase.ifBlank { null },
                                    privateKeyInput.ifBlank { null }
                                )
                                statusMsg = when (res) {
                                    is VaultImportResult.Success -> "Unlocked ${res.nodes.size} locked node(s)"
                                    is VaultImportResult.Expired -> "Vault expired"
                                    is VaultImportResult.Error -> res.reason
                                }
                                isBusy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)
                    ) {
                        Text(if (isBusy) "Unlocking…" else "Unlock & Import Locked Nodes")
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !useX25519, onClick = { useX25519 = false }, label = { Text(tr("PBKDF2 Passphrase")) })
                        FilterChip(selected = useX25519, onClick = { useX25519 = true }, label = { Text(tr("X25519 Sealed")) })
                    }
                    if (!useX25519) {
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text(tr("Vault Passphrase")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val kp = withContext(Dispatchers.Default) {
                                        EncryptedConfigVault.generateX25519KeyPair()
                                    }
                                    recipientPubKey = kp.publicKeyBase64
                                    privateKeyInput = kp.privateKeyBase64
                                }
                            },
                            border = BorderStroke(1.dp, colors.outline)
                        ) {
                            Text(tr("Generate Test X25519 Keypair"), fontSize = 12.sp)
                        }
                        OutlinedTextField(
                            value = recipientPubKey,
                            onValueChange = { recipientPubKey = it },
                            label = { Text(tr("Recipient Public Key (Base64)")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OutlinedTextField(
                        value = expiryHours,
                        onValueChange = { expiryHours = it.filter { c -> c.isDigit() } },
                        label = { Text(tr("Optional Expiry (hours)")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        enabled = !isBusy,
                        onClick = {
                            val exportNodes = profiles.filter { !it.isLocked }
                            if (exportNodes.isEmpty()) {
                                statusMsg = "No unlocked profiles available to export"
                                return@Button
                            }
                            val expMs = expiryHours.toLongOrNull()?.takeIf { it > 0 }?.let {
                                System.currentTimeMillis() + it * 3_600_000L
                            }
                            scope.launch {
                                isBusy = true
                                try {
                                    exportedText = onExportVault(exportNodes, useX25519, passphrase, recipientPubKey, expMs)
                                    statusMsg = "Sealed ${exportNodes.size} node(s)"
                                } catch (e: Exception) {
                                    statusMsg = e.message ?: "Export error"
                                } finally {
                                    isBusy = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)
                    ) {
                        Text(if (isBusy) "Sealing…" else "Generate Sealed .zsx Payload")
                    }
                    if (exportedText.isNotEmpty()) {
                        OutlinedTextField(
                            value = exportedText,
                            onValueChange = {},
                            readOnly = true,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("LuxyVPN Vault", exportedText)))
                                }
                            },
                            border = BorderStroke(1.dp, colors.outline)
                        ) {
                            Text(tr("Copy Vault Payload"))
                        }
                    }
                }

                statusMsg?.let { msg ->
                    Text(tr(msg), style = MaterialTheme.typography.bodySmall, color = colors.primary)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close")) } }
    )
}

@Composable
fun QrShareDialog(
    node: ServerProfileEntity,
    onDismiss: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val uri = remember(node) { ProxyUriParser.toShareUri(node) }
    var qrBitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        qrBitmap = withContext(Dispatchers.Default) {
            QrCodeGenerator.generateQrBitmap(uri, 480)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text(tr("Share ${node.name}"), color = colors.onSurface) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                qrBitmap?.let { bmp ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, colors.outline)
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = tr("Share QR Code"),
                            modifier = Modifier.size(210.dp).padding(8.dp)
                        )
                    }
                }
                Text(
                    text = uri,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 3
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("LuxyVPN URI", uri)))
                        }
                    },
                    border = BorderStroke(1.dp, colors.outline)
                ) {
                    Text(tr("Copy Share URI"))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Done")) } }
    )
}
