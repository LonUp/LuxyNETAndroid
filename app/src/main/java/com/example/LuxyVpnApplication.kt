package com.example

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.example.data.local.LuxyVpnDatabase
import com.example.data.local.SettingsDataStore
import com.example.data.repository.LuxyVpnRepository
import com.example.vpn.TunnelManager

class LuxyVpnApplication : Application() {

    val database: LuxyVpnDatabase by lazy { LuxyVpnDatabase.getInstance(this) }
    val settingsDataStore: SettingsDataStore by lazy { SettingsDataStore(this) }
    val repository: LuxyVpnRepository by lazy { LuxyVpnRepository(database, settingsDataStore) }
    val tunnelManager: TunnelManager by lazy { TunnelManager(this, repository) }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Home-screen widgets cannot observe Compose state; push the connection state into the
        // RemoteViews from the one place that owns it. Cheap when no widget is placed.
        applicationScope.launch {
            tunnelManager.telemetry
                .map { it.connectionState }
                .distinctUntilChanged()
                .collect { state -> LuxyWidgetProvider.updateAll(this@LuxyVpnApplication, state) }
        }
    }
}
