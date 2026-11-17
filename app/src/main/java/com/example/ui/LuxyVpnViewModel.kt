package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.LuxyVpnApplication
import com.example.core.ClientPolicy
import com.example.core.FastestServerSelector
import com.example.core.ImportInput
import com.example.core.ImportTooLargeException
import com.example.data.import.ImportSummary
import com.example.data.import.ProfileImporter
import com.example.data.local.ServerProfileEntity
import com.example.data.model.ConnectionState
import com.example.data.model.StabilitySettings
import com.example.data.model.TunnelTelemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

class LuxyVpnViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LuxyVpnApplication
    private val repository = app.repository
    private val tunnelManager = app.tunnelManager
    private val importer = ProfileImporter(repository)
    private val selector = FastestServerSelector()
    private val operation = Mutex()
    private var selectionJob: Job? = null

    val settings: StateFlow<StabilitySettings> = repository.settingsFlow
        .map(ClientPolicy::settings)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClientPolicy.settings(StabilitySettings()))
    val profiles: StateFlow<List<ServerProfileEntity>> = repository.profilesFlow
        .map { list -> list.filter(ClientPolicy::isSupported) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val telemetry: StateFlow<TunnelTelemetry> = tunnelManager.telemetry

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()
    private val _isSelectingFastestServer = MutableStateFlow(false)
    val isSelectingFastestServer: StateFlow<Boolean> = _isSelectingFastestServer.asStateFlow()
    private val _uiNotice = MutableStateFlow<String?>(null)
    val uiNotice: StateFlow<String?> = _uiNotice.asStateFlow()

    fun clearUiNotice() { _uiNotice.value = null }
    fun showNotice(message: String) { _uiNotice.value = message }
    fun prepareVpnIntent(): Intent? = tunnelManager.prepareVpnIntent()

    /** The caller requests VPN permission first. A second press cancels or disconnects. */
    fun toggleConnect() {
        if (_isSelectingFastestServer.value) {
            selectionJob?.cancel()
            showNotice("Server selection cancelled")
            return
        }
        when (telemetry.value.connectionState) {
            ConnectionState.CONNECTED, ConnectionState.CONNECTING,
            ConnectionState.RECONNECTING, ConnectionState.FAILOVER -> {
                tunnelManager.disconnect("User disconnected tunnel")
                return
            }
            ConnectionState.DISCONNECTING -> return
            else -> Unit
        }
        if (!operation.tryLock()) return
        _isSelectingFastestServer.value = true
        val startingSession = telemetry.value
        selectionJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val previousId = repository.getSettings().activeProfileId
                val candidates = repository.getAllProfiles().filter(ClientPolicy::isSupported)
                    .sortedWith(compareByDescending<ServerProfileEntity> { it.id == previousId }
                        .thenBy { it.latencyMs ?: Int.MAX_VALUE })
                if (candidates.isEmpty()) {
                    showNotice("Import a supported server first")
                    return@launch
                }
                val ranked = selector.rank(candidates)
                if (ranked.isEmpty()) {
                    showNotice("No servers responded. Check your network and try again")
                    return@launch
                }
                currentCoroutineContext().ensureActive()
                val currentById = repository.getAllProfiles().associateBy { it.id }
                val eligible = ranked.mapNotNull { measured ->
                    currentById[measured.profile.id]?.takeIf {
                        it == measured.profile && ClientPolicy.isSupported(it)
                    }
                }
                if (eligible.isEmpty()) {
                    showNotice("Server list changed. Try again")
                    return@launch
                }
                currentCoroutineContext().ensureActive()
                if (!sameSession(startingSession, telemetry.value)) return@launch
                tunnelManager.connectCandidates(eligible)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showNotice("Could not select a server. Try again")
            } finally {
                _isSelectingFastestServer.value = false
                operation.unlock()
            }
        }
    }

    fun importText(rawInput: String) {
        viewModelScope.launch { importDeepLinksAsync(rawInput) }
    }

    fun importUri(uri: Uri) {
        viewModelScope.launch { importDeepLinkUriAsync(uri) }
    }

    suspend fun importDeepLinksAsync(rawInput: String, groupName: String = "Imported"): ImportSummary =
        importInput { importer.importText(rawInput, groupName) }


    suspend fun importDeepLinkUriAsync(uri: Uri): ImportSummary = importInput {
        if (uri.scheme.equals("content", true) || uri.scheme.equals("file", true)) {
            val text = withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)?.use(ImportInput::readText)
            }
            if (text.isNullOrBlank()) ImportSummary(failures = listOf("Could not read the imported file"))
            else importer.importText(text)
        } else importer.importText(uri.toString())
    }

    private suspend fun importInput(block: suspend () -> ImportSummary): ImportSummary {
        if (!operation.tryLock()) {
            return ImportSummary(failures = listOf("Wait for the current operation to finish"))
                .also { showNotice(it.failures.single()) }
        }
        try {
            if (telemetry.value.connectionState !in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)) {
                return ImportSummary(failures = listOf("Disconnect before importing"))
                    .also { showNotice(it.failures.single()) }
            }
            _isImporting.value = true
            val summary = try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ImportTooLargeException) {
                ImportSummary(failures = listOf(ImportInput.TOO_LARGE))
            } catch (_: Exception) {
                ImportSummary(failures = listOf("Could not read or save the imported configuration"))
            }
            if (summary.profiles > 0 && summary.failures.isEmpty() && summary.notes.isEmpty()) {
                // The ready count already confirms success; do not cover the import controls.
                clearUiNotice()
            } else {
                showNotice(buildString {
                    if (summary.profiles > 0) append("Imported ${summary.profiles} server(s)")
                    else append("Nothing imported")
                    if (summary.failures.isNotEmpty()) {
                        append(". Rejected ${summary.failures.size}: ${summary.failures.first()}")
                    }
                    if (summary.notes.isNotEmpty()) append(". Ignored unsafe or app-managed configuration directives")
                })
            }
            return summary
        } finally {
            _isImporting.value = false
            operation.unlock()
        }
    }

    private fun sameSession(before: TunnelTelemetry, after: TunnelTelemetry): Boolean =
        before.connectionState == after.connectionState &&
            before.activeNode?.id == after.activeNode?.id &&
            before.connectedAtElapsedMs == after.connectedAtElapsedMs
}
