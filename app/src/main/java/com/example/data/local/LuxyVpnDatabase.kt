package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        ServerProfileEntity::class,
        SubscriptionEntity::class,
        RoutingRuleEntity::class,
        StabilityEventEntity::class,
        NetworkStrategyEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class LuxyVpnDatabase : RoomDatabase() {
    abstract fun serverProfileDao(): ServerProfileDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun routingRuleDao(): RoutingRuleDao
    abstract fun stabilityEventDao(): StabilityEventDao
    abstract fun networkStrategyDao(): NetworkStrategyDao

    companion object {
        @Volatile
        private var INSTANCE: LuxyVpnDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routing_rules ADD COLUMN managedKey TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_routing_rules_managedKey` ON `routing_rules` (`managedKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stability_events_timestamp` ON `stability_events` (`timestamp`)")
            }
        }

        fun getInstance(context: Context): LuxyVpnDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(appContext: Context): LuxyVpnDatabase {
            lateinit var db: LuxyVpnDatabase
            db = Room.databaseBuilder(appContext, LuxyVpnDatabase::class.java, "luxyvpn.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration(dropAllTables = false)
                .addCallback(object : Callback() {
                    override fun onCreate(sqliteDb: SupportSQLiteDatabase) {
                        super.onCreate(sqliteDb)
                        CoroutineScope(Dispatchers.IO).launch {
                            seedBaselineData(db)
                        }
                    }
                })
                .build()
            return db
        }

        private suspend fun seedBaselineData(db: LuxyVpnDatabase) {
            val rules = listOf(
                RoutingRuleEntity(
                    id = "rule-private-geoip",
                    name = "Private & LAN Networks",
                    matchType = "GEOIP",
                    pattern = "private",
                    action = "DIRECT",
                    isEnabled = true,
                    priority = 10
                ),
                RoutingRuleEntity(
                    id = "rule-rfc1918-10",
                    name = "RFC1918 10.0.0.0/8",
                    matchType = "IP_CIDR",
                    pattern = "10.0.0.0/8",
                    action = "DIRECT",
                    isEnabled = true,
                    priority = 11
                ),
                RoutingRuleEntity(
                    id = "rule-rfc1918-172",
                    name = "RFC1918 172.16.0.0/12",
                    matchType = "IP_CIDR",
                    pattern = "172.16.0.0/12",
                    action = "DIRECT",
                    isEnabled = true,
                    priority = 12
                ),
                RoutingRuleEntity(
                    id = "rule-rfc1918-192",
                    name = "RFC1918 192.168.0.0/16",
                    matchType = "IP_CIDR",
                    pattern = "192.168.0.0/16",
                    action = "DIRECT",
                    isEnabled = true,
                    priority = 13
                )
            )
            db.routingRuleDao().upsertAll(rules)

            db.stabilityEventDao().insert(
                StabilityEventEntity(
                    level = "INFO",
                    category = "TUNNEL",
                    message = "LuxyVPN initialized with LuxyVpnCore 3.0 & baseline LAN bypass rules"
                )
            )
        }
    }
}
