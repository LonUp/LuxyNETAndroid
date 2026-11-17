package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.model.ConnectionState
import com.example.i18n.Digits
import com.example.i18n.UiText
import com.example.ui.theme.LocalLuxyColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun LuxyVpnApp(
    viewModel: LuxyVpnViewModel,
    onRequestConnect: () -> Unit,
    onImportFile: () -> Unit,
    permissionPending: Boolean = false
) {
    // Traffic counters update in the service; this screen does not need to redraw for them.
    val stateFlow = remember(viewModel) {
        viewModel.telemetry.map { it.connectionState }.distinctUntilChanged()
    }
    val errorFlow = remember(viewModel) {
        viewModel.telemetry.map { it.lastError }.distinctUntilChanged()
    }
    val state by stateFlow.collectAsStateWithLifecycle(viewModel.telemetry.value.connectionState)
    val error by errorFlow.collectAsStateWithLifecycle(viewModel.telemetry.value.lastError)
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val importing by viewModel.isImporting.collectAsStateWithLifecycle()
    val selecting by viewModel.isSelectingFastestServer.collectAsStateWithLifecycle()
    val notice by viewModel.uiNotice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val language = LocalConfiguration.current.locales[0].language
    val emptyClipboard = stringResource(R.string.minimal_empty_clipboard)

    LaunchedEffect(notice, language) {
        val message = notice ?: return@LaunchedEffect
        snackbar.showSnackbar(UiText.translate(language, message), duration = SnackbarDuration.Long)
        if (viewModel.uiNotice.value == message) viewModel.clearUiNotice()
    }
    LaunchedEffect(error, language) {
        val message = error ?: return@LaunchedEffect
        snackbar.showSnackbar(UiText.translate(language, message), duration = SnackbarDuration.Long)
    }

    MinimalHome(
        state = state,
        serverCount = profiles.size,
        importing = importing,
        selecting = selecting,
        permissionPending = permissionPending,
        snackbar = snackbar,
        onConnect = onRequestConnect,
        onImportClipboard = {
            // Read only in response to a tap, never when opening or resuming the app.
            val text = clipboard.getText()?.text.orEmpty()
            if (text.isBlank()) viewModel.showNotice(emptyClipboard)
            else viewModel.importText(text)
        },
        onImportFile = onImportFile
    )
}

/** One task per action: import a configuration, then connect. No technical settings. */
@Composable
internal fun MinimalHome(
    state: ConnectionState,
    serverCount: Int,
    importing: Boolean,
    selecting: Boolean,
    permissionPending: Boolean,
    snackbar: SnackbarHostState,
    onConnect: () -> Unit,
    onImportClipboard: () -> Unit,
    onImportFile: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val idle = state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR
    val importEnabled = idle && !importing && !selecting && !permissionPending
    val connectEnabled = !importing && !permissionPending &&
        state != ConnectionState.DISCONNECTING && (serverCount > 0 || !idle || selecting)
    val action = stringResource(
        when {
            selecting -> R.string.minimal_cancel
            state == ConnectionState.CONNECTED || state == ConnectionState.DISCONNECTING ->
                R.string.minimal_disconnect
            !idle && state != ConnectionState.DISCONNECTING -> R.string.minimal_cancel
            else -> R.string.minimal_connect
        }
    )
    val status = stringResource(
        when {
            importing -> R.string.minimal_importing
            permissionPending -> R.string.minimal_permission
            selecting -> R.string.minimal_selecting
            state == ConnectionState.CONNECTED -> R.string.minimal_connected
            state == ConnectionState.DISCONNECTING -> R.string.minimal_disconnecting
            !idle -> R.string.minimal_connecting
            state == ConnectionState.ERROR -> R.string.minimal_connection_failed
            else -> R.string.minimal_disconnected
        }
    )
    val language = LocalConfiguration.current.locales[0].language
    val ready = if (serverCount == 0) stringResource(R.string.minimal_import_hint) else {
        stringResource(R.string.minimal_ready, Digits.localize(serverCount.toString(), language))
    }

    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "LuxyNet",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onBackground,
                    fontWeight = FontWeight.SemiBold
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 40.dp)
                ) {
                    Text(
                        text = status,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (state == ConnectionState.CONNECTED) colors.primary else colors.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onConnect,
                        enabled = connectEnabled,
                        modifier = Modifier.size(136.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            contentColor = colors.onPrimary,
                            disabledContainerColor = colors.surfaceContainer,
                            disabledContentColor = colors.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            Icons.Rounded.PowerSettingsNew,
                            contentDescription = action,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = action,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onBackground
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (serverCount > 0) stringResource(R.string.minimal_automatic) else
                            stringResource(R.string.minimal_start),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = ready,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onImportClipboard,
                            enabled = importEnabled,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(
                                text = stringResource(R.string.minimal_import),
                                modifier = Modifier.padding(start = 10.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                        IconButton(
                            onClick = onImportFile,
                            enabled = importEnabled,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                Icons.Rounded.FolderOpen,
                                contentDescription = stringResource(R.string.minimal_import_file)
                            )
                        }
                    }
                }
            }
        }
    }
}
