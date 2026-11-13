package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.RuleAction
import com.example.data.model.RuleMatchType
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

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN cipherSuites TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN finalMask TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN echConfigList TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN echForceQuery TEXT NOT NULL DEFAULT 'none'")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN mldsa65Verify TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN isLocked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN vaultExpiresAtMs INTEGER")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN vaultKeyScheme TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS network_strategies (
                        networkFingerprint TEXT NOT NULL,
                        transportType TEXT NOT NULL,
                        preferredEscalationLevel INTEGER NOT NULL,
                        preferredPreset TEXT NOT NULL,
                        lastCleanEndpoint TEXT NOT NULL,
                        lastSuccessAt INTEGER NOT NULL,
                        successCount INTEGER NOT NULL,
                        stallCount INTEGER NOT NULL,
                        PRIMARY KEY(networkFingerprint)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): LuxyVpnDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LuxyVpnDatabase::class.java,
                    "luxyvpn.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                getInstance(context).routingRuleDao().upsertAll(defaultRoutingRules())
                            }
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
        }

        fun defaultRoutingRules(): List<RoutingRuleEntity> = listOf(
            RoutingRuleEntity(
                id = "rule-rfc1918-loopback",
                name = "Loopback Interface",
                matchType = RuleMatchType.IP_CIDR.name,
                pattern = "127.0.0.0/8",
                action = RuleAction.DIRECT.name,
                isEnabled = true,
                priority = 10
            ),
            RoutingRuleEntity(
                id = "rule-rfc1918-10",
                name = "Private LAN (10.x)",
                matchType = RuleMatchType.IP_CIDR.name,
                pattern = "10.0.0.0/8",
                action = RuleAction.DIRECT.name,
                isEnabled = true,
                priority = 20
            ),
            RoutingRuleEntity(
                id = "rule-rfc1918-172",
                name = "Private LAN (172.16.x)",
                matchType = RuleMatchType.IP_CIDR.name,
                pattern = "172.16.0.0/12",
                action = RuleAction.DIRECT.name,
                isEnabled = true,
                priority = 30
            ),
            RoutingRuleEntity(
                id = "rule-rfc1918-192",
                name = "Local Subnet (192.168.x)",
                matchType = RuleMatchType.IP_CIDR.name,
                pattern = "192.168.0.0/16",
                action = RuleAction.DIRECT.name,
                isEnabled = true,
                priority = 40
            ),
            RoutingRuleEntity(
                id = "rule-local-mdns",
                name = "mDNS / .local Domains",
                matchType = RuleMatchType.DOMAIN_SUFFIX.name,
                pattern = ".local",
                action = RuleAction.DIRECT.name,
                isEnabled = true,
                priority = 50
            ),
            RoutingRuleEntity(
                id = "rule-geoip-private",
                name = "GeoIP Private",
                matchType = RuleMatchType.GEOIP.name,
                pattern = "private",
                action = RuleAction.DIRECT.name,
                isEnabled = true,
                priority = 60
            )
        )
    }
}
