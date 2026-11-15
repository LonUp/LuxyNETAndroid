package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.import.ImportSummary
import com.example.ui.LuxyVpnViewModel
import com.example.ui.theme.LocalLuxyColors
import kotlinx.coroutines.launch

/**
 * The 11-add-flow entry point: one sheet for links, subscriptions and pasted lists.
 * QR camera/image adders arrive with the ZXing wiring in P2.
 */
@Composable
fun ImportSheet(
    viewModel: LuxyVpnViewModel,
    onDismiss: () -> Unit,
    initialText: String = ""
) {
    val colors = LocalLuxyColors.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initialText) }
    var running by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<ImportSummary?>(null) }

    LuxySheet(onDismiss = onDismiss) {
        Text(
            text = stringResource(R.string.import_title),
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            color = colors.onSurface
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; summary = null },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            placeholder = {
                Text(
                    text = stringResource(R.string.import_hint),
                    fontSize = 13.sp,
                    color = colors.onSurfaceVariant
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.primary,
                unfocusedBorderColor = colors.outline,
                focusedContainerColor = colors.surfaceVariant,
                unfocusedContainerColor = colors.surfaceVariant,
                focusedTextColor = colors.onSurface,
                unfocusedTextColor = colors.onSurface
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = {
                    clipboard.getText()?.text?.let { text = it; summary = null }
                }
            ) {
                Text(stringResource(R.string.import_from_clipboard), fontSize = 13.sp)
            }
            Button(
                onClick = {
                    running = true
                    scope.launch {
                        summary = viewModel.importDeepLinksAsync(text)
                        running = false
                    }
                },
                enabled = !running && text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.accentInk
                )
            ) {
                Text(
                    if (running) stringResource(R.string.import_running)
                    else stringResource(R.string.import_action)
                )
            }
        }

        summary?.let { result ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (result.profiles > 0) {
                    Text(stringResource(R.string.import_summary_profiles, result.profiles), color = colors.statusConnected)
                }
                if (result.subscriptions > 0) {
                    Text(stringResource(R.string.import_summary_subscriptions, result.subscriptions), color = colors.statusInfo)
                }
                if (result.failures.isNotEmpty()) {
                    Text(stringResource(R.string.import_summary_failures, result.failures.size), color = colors.statusError)
                    result.failures.take(4).forEach { reason ->
                        Text(
                            text = "• $reason",
                            fontSize = 12.sp,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                result.notes.forEach { note ->
                    Text(note, fontSize = 12.sp, color = colors.statusConnecting)
                }
            }
        }

        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.import_close), color = colors.onSurfaceVariant)
        }
    }
}
