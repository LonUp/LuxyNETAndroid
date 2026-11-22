package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.LuxyVpnApplication
import com.example.BuildConfig
import com.example.core.AutomaticServerSelector
import com.example.core.ClientPolicy
import com.example.core.ConfigFingerprint
import com.example.core.ConfigurationHealthTracker
import com.example.core.ImportInput
import com.example.core.ImportTooLargeException
import com.example.core.NetworkFingerprintProvider
import com.example.core.ProbeBatchPolicy
import com.example.core.ProbeOutcome
import com.example.core.ServerCountryResolver
import com.example.core.ServerProbe
import com.example.core.SmartCleanupAnalyzer
import com.example.data.import.ImportSummary
import com.example.data.import.ProfileImporter
import com.example.data.local.ServerProfileEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.model.ConnectionSourceSummary
import com.example.data.model.ConnectionState
import com.example.data.model.DeletedBackupBatch
import com.example.data.model.EffectiveHealthState
import com.example.data.model.HealthPolicy
import com.example.data.model.ManualConfigCounts
import com.example.data.model.SourceType
import com.example.data.model.StabilitySettings
import com.example.data.model.TunnelTelemetry
import com.example.data.model.MeasurementType
import com.example.data.model.SmartCleanupItem
import com.example.data.model.SmartCleanupPlan
import com.example.data.model.TrashEntry
import com.example.data.repository.BackupRepositoryHelper
import com.example.data.repository.BackupRestoreSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class LuxyVpnViewModel internal constructor(
    application: Application,
    private val customProbe: ServerProbe? = null
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, null)

    private val app = application as LuxyVpnApplication
    private val repository = app.repository
    private val tunnelManager = app.tunnelManager
    private val countryDetector = app.countryDetector
    private val serverProbe: ServerProbe get() = customProbe ?: app.serverProbe
    private val importer = ProfileImporter(repository, countryDetector)
    private val operation = Mutex()
    private var selectionJob: Job? = null
    private var retestJob: Job? = null
    private var lastTrashPurgeAtElapsedMs = Long.MIN_VALUE

    val settings: StateFlow<StabilitySettings> = repository.settingsFlow
        .map(ClientPolicy::settings)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ClientPolicy.settings(StabilitySettings()))

    val allProfiles: StateFlow<List<ServerProfileEntity>> = repository.profilesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val profiles: StateFlow<List<ServerProfileEntity>> = allProfiles
        .map { list -> list.filter(ClientPolicy::isSupported) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val subscriptions: StateFlow<List<SubscriptionEntity>> = repository.subscriptionsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val telemetry: StateFlow<TunnelTelemetry> = tunnelManager.telemetry
    val homeTelemetry: StateFlow<TunnelTelemetry> = telemetry
        .map { it.toHomeSnapshot() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, telemetry.value.toHomeSnapshot())

    val selectedCountryCode: StateFlow<String?> = settings
        .map { it.selectedCountryCode }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val smartCleanupPlan: StateFlow<SmartCleanupPlan> = combine(
        allProfiles,
        subscriptions,
        telemetry.map { it.activeNode?.id }.distinctUntilChanged(),
        settings.map { it.dismissedCleanupKeys }.distinctUntilChanged()
    ) { all, subs, activeNodeId, dismissedKeys ->
        SmartCleanupAnalyzer.analyze(
            allProfiles = all,
            subscriptions = subs,
            activeNodeId = activeNodeId,
            currentNetworkFingerprint = NetworkFingerprintProvider.current(app),
            dismissedKeys = dismissedKeys
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, SmartCleanupPlan(emptyList(), 0))

    val trashEntries: StateFlow<List<TrashEntry>> = repository.trashEntriesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val eligibleProfiles: StateFlow<List<ServerProfileEntity>> = combine(
        allProfiles,
        subscriptions,
        settings
    ) { all, subs, currentSettings ->
        val eligible = AutomaticServerSelector.eligibleProfiles(
            allProfiles = all,
            subscriptions = subs,
            manualConfigsEnabled = currentSettings.manualConfigsEnabledForConnection,
            selectedCountryCode = null
        )
        val nowMs = System.currentTimeMillis()
        val networkFingerprint = NetworkFingerprintProvider.current(app)
        eligible.filter {
            HealthPolicy.effectiveHealthState(it, nowMs, networkFingerprint) != EffectiveHealthState.INVALID
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val connectionSources: StateFlow<List<ConnectionSourceSummary>> = combine(
        allProfiles,
        subscriptions,
        settings,
        telemetry
    ) { all, subs, currentSettings, currentTelemetry ->
        val activeNode = currentTelemetry.activeNode
        val isConnected = currentTelemetry.connectionState == ConnectionState.CONNECTED
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000L
        val networkFingerprint = NetworkFingerprintProvider.current(app)

        val subSummaries = subs.map { sub ->
            val owned = all.filter {
                SourceType.parse(it.sourceType) == SourceType.SUBSCRIPTION && it.subscriptionId == sub.id
            }
            val eligible = owned.count {
                it.isEnabled &&
                    ClientPolicy.isSupported(it) &&
                    HealthPolicy.effectiveHealthState(it, nowMs, networkFingerprint) != EffectiveHealthState.INVALID
            }
            val used = sub.uploadBytes.coerceAtLeast(0L) + sub.downloadBytes.coerceAtLeast(0L)
            val expired = sub.expireAtEpochSec > 0L && sub.expireAtEpochSec <= nowSec
            val exhausted = sub.totalBytes > 0L && used >= sub.totalBytes
            val failed = !sub.lastError.isNullOrBlank() || sub.lastStatus.equals("Failed", ignoreCase = true)
            val connectedHere = isConnected && activeNode != null &&
                SourceType.parse(activeNode.sourceType) == SourceType.SUBSCRIPTION &&
                activeNode.subscriptionId == sub.id
            ConnectionSourceSummary(
                id = sub.id,
                isManual = false,
                name = sub.name,
                totalCount = owned.size.takeIf { owned.isNotEmpty() || sub.lastUpdatedAt != null } ?: sub.nodeCount,
                eligibleCount = eligible,
                isEnabledForConnection = sub.isEnabledForConnection,
                isConnected = connectedHere,
                hasError = failed || expired || exhausted,
                subscription = sub
            )
        }

        val manualOwned = all.filter { SourceType.parse(it.sourceType) == SourceType.MANUAL }
        val manualEligible = manualOwned.count {
            it.isEnabled &&
                ClientPolicy.isSupported(it) &&
                HealthPolicy.effectiveHealthState(it, nowMs, networkFingerprint) != EffectiveHealthState.INVALID
        }
        val manualConnected = isConnected && activeNode != null &&
            SourceType.parse(activeNode.sourceType) == SourceType.MANUAL
        val manualSummary = ConnectionSourceSummary(
            id = MANUAL_SOURCE_ID,
            isManual = true,
            name = "Manual Configurations",
            totalCount = manualOwned.size,
            eligibleCount = manualEligible,
            isEnabledForConnection = currentSettings.manualConfigsEnabledForConnection,
            isConnected = manualConnected,
            hasError = false,
            subscription = null
        )

        if (manualOwned.isNotEmpty()) subSummaries + manualSummary else subSummaries
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val manualProfiles: StateFlow<List<ServerProfileEntity>> = allProfiles
        .map { list ->
            list.filter {
                val src = SourceType.parse(it.sourceType)
                src == SourceType.MANUAL || src == SourceType.LEGACY_UNCLASSIFIED
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val manualCounts: StateFlow<ManualConfigCounts> = combine(manualProfiles, smartCleanupPlan) { list, cleanupPlan ->
            val netFp = NetworkFingerprintProvider.current(app)
            val nowMs = System.currentTimeMillis()
            var working = 0
            var failed = 0
            var untested = 0
            var disabled = 0
            var invalid = 0
            var unclassified = 0
            val seenFp = HashSet<String>(list.size)
            var duplicates = 0
            for (p in list) {
                val src = SourceType.parse(p.sourceType)
                if (src == SourceType.LEGACY_UNCLASSIFIED) {
                    unclassified++
                } else {
                    val fp = p.canonicalFingerprint.ifBlank { ConfigFingerprint.compute(p) }
                    if (!seenFp.add(fp)) duplicates++
                }
                when (HealthPolicy.effectiveHealthState(p, nowMs, netFp)) {
                    EffectiveHealthState.WORKING -> working++
                    EffectiveHealthState.FAILED -> failed++
                    EffectiveHealthState.UNTESTED -> untested++
                    EffectiveHealthState.DISABLED -> disabled++
                    EffectiveHealthState.INVALID -> invalid++
                }
            }
            ManualConfigCounts(
                total = list.size,
                working = working,
                failed = failed,
                untested = untested,
                disabled = disabled,
                preferred = list.count { it.isPinned },
                invalid = invalid,
                unclassified = unclassified,
                duplicateCount = duplicates,
                cleanupSuggestionCount = cleanupPlan.groups.sumOf { group ->
                    group.items.count { item ->
                        val sourceType = SourceType.parse(item.profile.sourceType)
                        sourceType == SourceType.MANUAL || sourceType == SourceType.LEGACY_UNCLASSIFIED
                    }
                }
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManualConfigCounts())

    val deletedBackups: StateFlow<List<DeletedBackupBatch>> = repository.deletedBackupsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()
    private val _isSelectingFastestServer = MutableStateFlow(false)
    val isSelectingFastestServer: StateFlow<Boolean> = _isSelectingFastestServer.asStateFlow()
    private val _isRetesting = MutableStateFlow(false)
    val isRetesting: StateFlow<Boolean> = _isRetesting.asStateFlow()
    private val _retestProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val retestProgress: StateFlow<Pair<Int, Int>?> = _retestProgress.asStateFlow()
    private val _sourceChangePendingReconnect = MutableStateFlow(false)
    val sourceChangePendingReconnect: StateFlow<Boolean> = _sourceChangePendingReconnect.asStateFlow()
    private val _lastDeletedBatch = MutableStateFlow<DeletedBackupBatch?>(null)
    val lastDeletedBatch: StateFlow<DeletedBackupBatch?> = _lastDeletedBatch.asStateFlow()
    private val _uiNotice = MutableStateFlow<String?>(null)
    val uiNotice: StateFlow<String?> = _uiNotice.asStateFlow()
    private val _detailsExpanded = MutableStateFlow(false)
    val detailsExpanded: StateFlow<Boolean> = _detailsExpanded.asStateFlow()

    init {
        purgeExpiredTrashIfNeeded()
        viewModelScope.launch {
            telemetry.map { it.connectionState }.distinctUntilChanged().collect { state ->
                if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
                    _sourceChangePendingReconnect.value = false
                    _detailsExpanded.value = false
                    tunnelManager.setDetailsExpanded(false)
                }
            }
        }
    }

    fun clearUiNotice() { _uiNotice.value = null }
    fun clearLastDeletedBatch() { _lastDeletedBatch.value = null }
    fun showNotice(message: String) { _uiNotice.value = message }
    fun purgeExpiredTrashIfNeeded() {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (lastTrashPurgeAtElapsedMs != Long.MIN_VALUE &&
            nowElapsedMs - lastTrashPurgeAtElapsedMs < TRASH_PURGE_INTERVAL_MS
        ) return
        lastTrashPurgeAtElapsedMs = nowElapsedMs
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.purgeExpiredTrash() }
        }
    }
    fun prepareVpnIntent(): Intent? = tunnelManager.prepareVpnIntent()
    fun toggleDetailsExpanded() = setDetailsExpanded(!_detailsExpanded.value)
    fun setDetailsExpanded(expanded: Boolean) {
        _detailsExpanded.value = expanded
        tunnelManager.setDetailsExpanded(expanded)
    }
    fun setAppForegrounded(foregrounded: Boolean) = tunnelManager.setAppForegrounded(foregrounded)
    fun retryVerification() = tunnelManager.retryVerification()

    fun hasEligibleServersForSelectedCountry(): Boolean {
        val country = selectedCountryCode.value
        return eligibleProfiles.value.any { ServerCountryResolver.matches(it, country) }
    }

    fun selectCountry(countryCode: String?) {
        val target = ServerCountryResolver.normalizeCode(countryCode)
        viewModelScope.launch {
            if (!operation.tryLock()) {
                showNotice("Wait for the current operation to finish")
                return@launch
            }
            try {
                val state = telemetry.value.connectionState
                if (state != ConnectionState.DISCONNECTED && state != ConnectionState.ERROR) return@launch
                repository.setSelectedCountry(target)
            } finally {
                operation.unlock()
            }
        }
    }

    fun switchCountryAndReconnect(countryCode: String?) {
        val target = ServerCountryResolver.normalizeCode(countryCode)
        viewModelScope.launch {
            if (_isSelectingFastestServer.value) {
                selectionJob?.cancelAndJoin()
            }
            if (!operation.tryLock()) {
                showNotice("Wait for the current operation to finish")
                return@launch
            }
            var disconnectedForSwitch = false
            try {
                repository.ensureMigrated(countryDetector)
                val currentSettings = repository.getSettings()
                val all = repository.getAllProfiles()
                val subs = repository.getAllSubscriptions()
                val netFp = NetworkFingerprintProvider.current(app)
                val plan = AutomaticServerSelector.buildPlan(
                    allProfiles = all,
                    subscriptions = subs,
                    manualConfigsEnabled = currentSettings.manualConfigsEnabledForConnection,
                    selectedCountryCode = target,
                    previousActiveId = currentSettings.activeProfileId,
                    currentNetworkFingerprint = netFp
                )
                val candidates = when (plan) {
                    is AutomaticServerSelector.SelectionPlan.Candidates -> plan.ordered
                    else -> {
                        showNotice(planFailureMessage(plan))
                        return@launch
                    }
                }
                repository.setSelectedCountry(target)
                val currentState = telemetry.value.connectionState
                if (currentState != ConnectionState.DISCONNECTED && currentState != ConnectionState.ERROR) {
                    tunnelManager.disconnectAndAwait("Switching location")
                    disconnectedForSwitch = true
                }
                val postDisconnectSession = telemetry.value
                _isSelectingFastestServer.value = true
                selectionJob = currentCoroutineContext()[Job]
                selectAndConnect(
                    candidates = candidates,
                    selectedCountry = target,
                    startingSession = postDisconnectSession,
                    failAsError = disconnectedForSwitch
                )
            } catch (cancelled: CancellationException) {
                if (disconnectedForSwitch) {
                    showNotice("Server selection cancelled")
                }
                throw cancelled
            } catch (_: Exception) {
                val msg = "Could not select a server. Try again"
                if (disconnectedForSwitch) tunnelManager.failConnection(msg) else showNotice(msg)
            } finally {
                if (selectionJob == currentCoroutineContext()[Job]) {
                    selectionJob = null
                }
                _isSelectingFastestServer.value = false
                operation.unlock()
            }
        }
    }

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
                repository.ensureMigrated(countryDetector)
                val currentSettings = repository.getSettings()
                val selectedCountry = currentSettings.selectedCountryCode
                val all = repository.getAllProfiles()
                val subs = repository.getAllSubscriptions()
                val netFp = NetworkFingerprintProvider.current(app)
                val plan = AutomaticServerSelector.buildPlan(
                    allProfiles = all,
                    subscriptions = subs,
                    manualConfigsEnabled = currentSettings.manualConfigsEnabledForConnection,
                    selectedCountryCode = selectedCountry,
                    previousActiveId = currentSettings.activeProfileId,
                    currentNetworkFingerprint = netFp
                )
                val candidates = when (plan) {
                    is AutomaticServerSelector.SelectionPlan.Candidates -> plan.ordered
                    else -> {
                        showNotice(planFailureMessage(plan))
                        return@launch
                    }
                }
                selectAndConnect(
                    candidates = candidates,
                    selectedCountry = selectedCountry,
                    startingSession = startingSession,
                    failAsError = false
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showNotice("Could not select a server. Try again")
            } finally {
                if (selectionJob == currentCoroutineContext()[Job]) {
                    selectionJob = null
                }
                _isSelectingFastestServer.value = false
                operation.unlock()
            }
        }
    }

    private suspend fun selectAndConnect(
        candidates: List<ServerProfileEntity>,
        selectedCountry: String?,
        startingSession: TunnelTelemetry,
        failAsError: Boolean
    ) {
        currentCoroutineContext().ensureActive()
        val currentSettings = repository.getSettings()
        if (currentSettings.selectedCountryCode != selectedCountry) {
            val msg = "Selected location changed. Try again"
            if (failAsError) tunnelManager.failConnection(msg) else showNotice(msg)
            return
        }
        val enabledSubIds = repository.getAllSubscriptions()
            .asSequence()
            .filter { it.isEnabledForConnection }
            .map { it.id }
            .toSet()
        val manualEnabled = currentSettings.manualConfigsEnabledForConnection
        val currentById = repository.getAllProfiles().associateBy { it.id }

        // Selector order already prefers a recent success over untested and cooled-down servers.
        // A TCP handshake is not proxy connectivity, so it must not delay or reorder the attempt.
        val eligible = candidates.mapNotNull { profile ->
            currentById[profile.id]?.takeIf { current ->
                current.isEnabled &&
                    ClientPolicy.isSupported(current) &&
                    AutomaticServerSelector.isProfileFromEnabledSource(current, enabledSubIds, manualEnabled) &&
                    ServerCountryResolver.matches(current, selectedCountry)
            }
        }
        if (eligible.isEmpty()) {
            val msg = "Server list changed. Try again"
            if (failAsError) tunnelManager.failConnection(msg) else showNotice(msg)
            return
        }
        currentCoroutineContext().ensureActive()
        if (!sameSession(startingSession, telemetry.value)) return
        _sourceChangePendingReconnect.value = false
        tunnelManager.connectCandidates(eligible, selectedCountry)
    }

    private fun planFailureMessage(plan: AutomaticServerSelector.SelectionPlan): String = when (plan) {
        AutomaticServerSelector.SelectionPlan.EmptyLibrary -> "Import a supported server first"
        AutomaticServerSelector.SelectionPlan.NoSourcesEnabled ->
            "All connection sources are disabled. Enable at least one source in Subscriptions"
        AutomaticServerSelector.SelectionPlan.AllConfigsDisabled ->
            "All configurations in enabled sources are disabled"
        AutomaticServerSelector.SelectionPlan.NoSupportedConfigs ->
            "No supported configurations in enabled sources"
        is AutomaticServerSelector.SelectionPlan.NoServersForCountry ->
            "No servers available for the selected country"
        is AutomaticServerSelector.SelectionPlan.Candidates -> ""
    }

    fun setSubscriptionEnabledForConnection(id: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setSubscriptionEnabledForConnection(id, enabled)
            if (tunnelManager.isTunnelActiveOrConnecting()) {
                _sourceChangePendingReconnect.value = true
            }
        }
    }

    fun setManualConfigsEnabledForConnection(enabled: Boolean) {
        viewModelScope.launch {
            repository.setManualConfigsEnabledForConnection(enabled)
            if (tunnelManager.isTunnelActiveOrConnecting()) {
                _sourceChangePendingReconnect.value = true
            }
        }
    }

    fun setProfileEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setProfileEnabled(id, enabled)
            if (tunnelManager.isTunnelActiveOrConnecting() && telemetry.value.activeNode?.id == id && !enabled) {
                _sourceChangePendingReconnect.value = true
            }
        }
    }
    fun setProfilePreferred(id: String, preferred: Boolean) {
        viewModelScope.launch {
            repository.setPreferred(id, preferred)
        }
    }

    fun setProfilesEnabled(ids: List<String>, enabled: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.setProfilesEnabled(ids, enabled)
            val activeId = telemetry.value.activeNode?.id
            if (tunnelManager.isTunnelActiveOrConnecting() && activeId != null && activeId in ids && !enabled) {
                _sourceChangePendingReconnect.value = true
            }
        }
    }

    fun deleteManualProfiles(ids: List<String>, label: String = "Manual deletion") {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val activeId = telemetry.value.activeNode?.id
            val activeProtected = tunnelManager.isTunnelActiveOrConnecting() && activeId != null && activeId in ids
            val safeIds = if (activeProtected) ids.filter { it != activeId } else ids
            if (safeIds.isEmpty()) {
                showNotice("Disconnect before deleting the active server")
                return@launch
            }
            val batch = repository.deleteProfilesWithBackup(safeIds, label)
            if (batch != null) {
                val msg = if (activeProtected) "Deleted ${batch.count} configuration(s); active server kept"
                else "Deleted ${batch.count} configuration(s)"
                _lastDeletedBatch.value = batch.copy(noticeMessage = msg)
            }
        }
    }
    fun moveCleanupItemsToTrash(items: List<SmartCleanupItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val activeNodeId = if (tunnelManager.isTunnelActiveOrConnecting()) telemetry.value.activeNode?.id else null
            val batch = repository.moveProfilesToTrash(items, "Smart cleanup", activeNodeId) ?: return@launch
            val notice = "Moved ${batch.count} configuration(s) to Trash"
            _lastDeletedBatch.value = batch.copy(noticeMessage = notice)
            showNotice(notice)
        }
    }

    fun dismissCleanupSuggestions(items: List<SmartCleanupItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val validProfileIds = allProfiles.value.mapTo(HashSet<String>()) { it.id }
            repository.updateSettings { current ->
                val nextKeys = current.dismissedCleanupKeys
                    .filterTo(mutableSetOf()) { key ->
                        val profileId = key.split(':').getOrNull(1)
                        profileId != null && profileId in validProfileIds
                    }
                    .apply { addAll(items.map(SmartCleanupAnalyzer::dismissalKey)) }
                current.copy(dismissedCleanupKeys = nextKeys)
            }
            showNotice("Kept ${items.size} configuration(s)")
        }
    }
    fun restoreTrashEntries(entryIds: List<String>) {
        viewModelScope.launch {
            val count = repository.restoreTrashEntries(entryIds)
            showNotice("Restored $count configuration(s)")
        }
    }

    fun permanentlyDeleteTrashEntries(entryIds: List<String>) {
        viewModelScope.launch {
            val count = repository.permanentlyDeleteTrashEntries(entryIds)
            showNotice("Permanently deleted $count configuration(s)")
        }
    }

    fun restoreBackupBatch(batchId: String) {
        viewModelScope.launch {
            val count = repository.restoreBackupBatch(batchId)
            if (_lastDeletedBatch.value?.batchId == batchId) {
                _lastDeletedBatch.value = null
            }
            if (count > 0) {
                showNotice("Restored $count configuration(s)")
            }
        }
    }
    suspend fun exportEncryptedBackup(passphrase: String): Result<String> =
        BackupRepositoryHelper.exportBackup(
            db = app.database,
            dataStore = app.settingsDataStore,
            passphrase = passphrase,
            appVersion = BuildConfig.VERSION_NAME
        )

    suspend fun restoreEncryptedBackup(
        rawBackup: String,
        passphrase: String
    ): Result<BackupRestoreSummary> {
        if (tunnelManager.isTunnelActiveOrConnecting()) {
            return Result.failure(IllegalStateException("Disconnect before restoring a backup"))
        }
        return BackupRepositoryHelper.restoreBackup(
            db = app.database,
            dataStore = app.settingsDataStore,
            rawBackup = rawBackup,
            passphrase = passphrase,
            countryDetector = countryDetector
        )
    }

    fun exportBackupToUri(uri: Uri, passphrase: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val rawBackup = exportEncryptedBackup(passphrase).getOrThrow()
                app.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(rawBackup)
                } ?: error("Could not open backup destination")
                showNotice("Backup exported")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showNotice("Could not export backup")
            }
        }
    }

    fun restoreBackupFromUri(uri: Uri, passphrase: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (tunnelManager.isTunnelActiveOrConnecting()) {
                showNotice("Disconnect before restoring a backup")
                return@launch
            }
            try {
                val rawBackup = app.contentResolver.openInputStream(uri)
                    ?.use(ImportInput::readText)
                    ?: error("Could not open backup file")
                val result = restoreEncryptedBackup(rawBackup, passphrase)
                val summary = result.getOrElse { failure ->
                    showNotice(
                        if (failure.message == "Disconnect before restoring a backup") {
                            "Disconnect before restoring a backup"
                        } else {
                            "Invalid backup file or wrong passphrase"
                        }
                    )
                    return@launch
                }
                showNotice(
                    "Restored ${summary.restoredProfiles} configuration(s) and " +
                        "${summary.restoredSubscriptions} subscription(s)"
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showNotice("Invalid backup file or wrong passphrase")
            }
        }
    }

    fun removeDuplicateManualProfiles() {
        viewModelScope.launch {
            val activeId = if (tunnelManager.isTunnelActiveOrConnecting()) telemetry.value.activeNode?.id else null
            val batch = repository.removeDuplicateManualProfiles(activeId)
            if (batch == null) {
                showNotice("No duplicate configurations found")
            } else {
                _lastDeletedBatch.value = batch.copy(
                    noticeMessage = "Removed ${batch.count} duplicate configuration(s)"
                )
            }
        }
    }

    fun reclassifyLegacyToManual(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.reclassifyLegacyToManual(ids)
            showNotice("Moved ${ids.size} configuration(s) to Manual Configurations")
        }
    }

    fun setManualCountry(id: String, countryCode: String?) {
        viewModelScope.launch {
            repository.setManualCountry(id, countryCode, countryDetector)
        }
    }

    fun detectCountriesForProfiles(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val updated = repository.enrichCountriesWithGeoIp(ids, countryDetector)
            showNotice(
                if (updated > 0) "Updated country estimates for $updated configuration(s)"
                else "No additional country metadata found"
            )
        }
    }

    fun cancelRetest() {
        retestJob?.cancel()
    }

    fun retestProfiles(ids: List<String>) {
        if (ids.isEmpty()) return
        retestJob?.cancel()
        retestJob = viewModelScope.launch {
            _isRetesting.value = true
            _retestProgress.value = 0 to ids.size
            try {
                val idSet = ids.toHashSet()
                val targets = repository.getAllProfiles().filter { it.id in idSet }
                if (targets.isEmpty()) return@launch
                val netFp = NetworkFingerprintProvider.current(app)
                val completed = AtomicInteger(0)
                val nextIdx = AtomicInteger(0)
                val total = targets.size
                val concurrency = minOf(4, total)
                val outcomes = arrayOfNulls<ProbeOutcome>(total)
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        repeat(concurrency) {
                            launch {
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val idx = nextIdx.getAndIncrement()
                                    if (idx >= total) break
                                    outcomes[idx] = serverProbe.probe(targets[idx], verifyExitCountry = true)
                                    _retestProgress.value = completed.incrementAndGet() to total
                                }
                            }
                        }
                    }
                }
                // A batch dominated by transient network failures must not stamp health. Probe each
                // configuration first, then decide once for the whole batch.
                val suppressFailures = ProbeBatchPolicy.shouldSuppressHealthStamp(
                    outcomes.mapNotNull { it?.errorCategory }
                )
                withContext(Dispatchers.IO) {
                    targets.forEachIndexed { idx, profile ->
                        val outcome = outcomes[idx] ?: return@forEachIndexed
                        val updated = when {
                            outcome.success -> ConfigurationHealthTracker.recordSuccess(
                                profile = profile,
                                latencyMs = outcome.latencyMs,
                                networkFingerprint = netFp,
                                exitIp = outcome.exitIp,
                                verifiedCountryCode = outcome.verifiedCountryCode,
                                measurementType = MeasurementType.PROXY_HTTP_204
                            )
                            suppressFailures -> null
                            else -> ConfigurationHealthTracker.recordFailure(
                                profile = profile,
                                category = outcome.errorCategory,
                                safeMessage = outcome.errorMessage,
                                networkFingerprint = netFp,
                                measurementType = MeasurementType.PROXY_HTTP_204
                            )
                        }
                        if (updated != null) repository.updateProfileHealth(updated)
                    }
                }
                showNotice("Finished testing $total configuration(s)")
            } catch (cancelled: CancellationException) {
                showNotice("Health check cancelled")
                throw cancelled
            } finally {
                _isRetesting.value = false
                _retestProgress.value = null
            }
        }
    }

    fun importText(rawInput: String) {
        viewModelScope.launch { importDeepLinksAsync(rawInput) }
    }

    fun importUri(uri: Uri) {
        viewModelScope.launch { importDeepLinkUriAsync(uri) }
    }

    fun importSubscriptionUrl(rawUrl: String, customName: String = "") {
        viewModelScope.launch { importSubscriptionUrlAsync(rawUrl, customName) }
    }

    suspend fun importSubscriptionUrlAsync(
        rawUrl: String,
        customName: String = "",
        groupName: String = "Imported"
    ): ImportSummary = importInput { importer.importSubscriptionUrl(rawUrl, customName, groupName) }

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

    fun refreshSubscription(id: String) = manageSubscription(
        "Subscription updated", "Could not update subscription"
    ) {
        val summary = importer.refreshSubscription(id)
        check(summary.subscriptions == 1)
    }

    fun saveSubscription(id: String, name: String, url: String) = manageSubscription(
        "Subscription saved", "Could not save subscription"
    ) { repository.editSubscription(id, name, url) }

    fun deleteSubscription(id: String) = manageSubscription(
        "Subscription deleted", "Could not delete subscription"
    ) { repository.deleteSubscriptionAndNodes(id) }

    private fun manageSubscription(success: String, failure: String, action: suspend () -> Unit) {
        if (!operation.tryLock()) {
            showNotice("Wait for the current operation to finish")
            return
        }
        if (telemetry.value.connectionState != ConnectionState.DISCONNECTED &&
            telemetry.value.connectionState != ConnectionState.ERROR) {
            operation.unlock()
            showNotice("Disconnect before managing subscriptions")
            return
        }
        _isImporting.value = true
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                action()
                showNotice(success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showNotice(failure)
            } finally {
                _isImporting.value = false
                operation.unlock()
            }
        }
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
            if (summary.profiles > 0 && summary.duplicates == 0 && summary.failures.isEmpty() && summary.notes.isEmpty()) {
                showNotice("Imported ${summary.profiles} server(s)")
            } else {
                showNotice(buildString {
                    if (summary.profiles > 0) append("Imported ${summary.profiles} server(s)")
                    else if (summary.duplicates > 0) append("Skipped ${summary.duplicates} duplicate(s)")
                    else append("Nothing imported")
                    if (summary.profiles > 0 && summary.duplicates > 0) {
                        append(", skipped ${summary.duplicates} duplicate(s)")
                    }
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

    private fun TunnelTelemetry.toHomeSnapshot(): TunnelTelemetry = TunnelTelemetry(
        connectionState = connectionState,
        activeNode = activeNode,
        connectedAtElapsedMs = connectedAtElapsedMs,
        exitIp = exitIp,
        exitCountryCode = exitCountryCode,
        exitCountryConfidence = exitCountryConfidence,
        connectivityVerified = connectivityVerified,
        exitVerified = exitVerified,
        dnsLeakProtected = dnsLeakProtected,
        ipv6LeakProtected = ipv6LeakProtected,
        vpnPostureAvailable = vpnPostureAvailable,
        alwaysOnEnabled = alwaysOnEnabled,
        lockdownEnabled = lockdownEnabled,
        verificationState = verificationState,
        lastError = lastError,
        lastErrorTechnicalDetail = lastErrorTechnicalDetail
    )

    companion object {
        const val MANUAL_SOURCE_ID = "manual"
        private const val TRASH_PURGE_INTERVAL_MS = 60L * 60L * 1000L
    }
}
