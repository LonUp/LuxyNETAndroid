package com.example

import android.app.Application
import com.example.data.local.LuxyVpnDatabase
import com.example.data.local.SettingsDataStore
import com.example.data.repository.LuxyVpnRepository
import com.example.vpn.TunnelManager

class LuxyVpnApplication : Application() {
    val database: LuxyVpnDatabase by lazy { LuxyVpnDatabase.getInstance(this) }
    val settingsDataStore: SettingsDataStore by lazy { SettingsDataStore(this) }
    val repository: LuxyVpnRepository by lazy { LuxyVpnRepository(database, settingsDataStore) }
    val tunnelManager: TunnelManager by lazy { TunnelManager(this, repository) }
}
