package com.example

import android.app.Application
import com.example.core.NetworkProbeEngine
import com.example.core.SubscriptionFetcher
import com.example.data.local.LuxyVpnDatabase
import com.example.data.local.SettingsDataStore
import com.example.data.repository.LuxyVpnRepository

class LuxyVpnApplication : Application() {
    val database: LuxyVpnDatabase by lazy { LuxyVpnDatabase.getInstance(this) }
    val settingsStore: SettingsDataStore by lazy { SettingsDataStore(this) }
    val probeEngine: NetworkProbeEngine by lazy { NetworkProbeEngine() }
    val subscriptionFetcher: SubscriptionFetcher by lazy { SubscriptionFetcher() }

    val repository: LuxyVpnRepository by lazy {
        LuxyVpnRepository(
            serverDao = database.serverProfileDao(),
            subscriptionDao = database.subscriptionDao(),
            ruleDao = database.routingRuleDao(),
            eventDao = database.stabilityEventDao(),
            networkStrategyDao = database.networkStrategyDao(),
            settingsStore = settingsStore,
            probeEngine = probeEngine,
            subscriptionFetcher = subscriptionFetcher
        )
    }
}
