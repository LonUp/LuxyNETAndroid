package com.example

import android.app.Application
import com.example.data.local.LuxyVpnDatabase
import com.example.data.local.SettingsDataStore
import com.example.data.repository.LuxyVpnRepository
import com.example.vpn.TunnelManager
import com.example.xraycore.CoreRoutingAssets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LuxyVpnApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database: LuxyVpnDatabase by lazy { LuxyVpnDatabase.getInstance(this) }
    val settingsDataStore: SettingsDataStore by lazy { SettingsDataStore(this) }
    val repository: LuxyVpnRepository by lazy { LuxyVpnRepository(database, settingsDataStore) }
    val tunnelManager: TunnelManager by lazy { TunnelManager(this, repository) }

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            runCatching { CoreRoutingAssets.directory(this@LuxyVpnApplication) }
        }
    }
}
