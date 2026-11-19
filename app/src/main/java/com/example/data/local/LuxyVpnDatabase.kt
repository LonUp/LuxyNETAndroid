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
        NetworkStrategyEntity::class,
        DeletedProfileBackupEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class LuxyVpnDatabase : RoomDatabase() {
    abstract fun serverProfileDao(): ServerProfileDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun routingRuleDao(): RoutingRuleDao
    abstract fun stabilityEventDao(): StabilityEventDao
    abstract fun networkStrategyDao(): NetworkStrategyDao
    abstract fun deletedProfileBackupDao(): DeletedProfileBackupDao
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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN uploadBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN downloadBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN totalBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN expireAtEpochSec INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN profileTitle TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN featureOverrides TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN isEnabledForConnection INTEGER NOT NULL DEFAULT 1")

                db.execSQL("ALTER TABLE server_profiles ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'MANUAL'")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN canonicalFingerprint TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN isEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN healthStatus TEXT NOT NULL DEFAULT 'UNTESTED'")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN lastSuccessAt INTEGER")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN lastAttemptAt INTEGER")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN consecutiveFailures INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN lastErrorCategory TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN lastErrorMessage TEXT")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN lastNetworkFingerprint TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN cooldownUntilMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN countryCode TEXT")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN countryConfidence TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN countrySource TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN countryVerifiedAt INTEGER")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN exitIp TEXT")
                db.execSQL("ALTER TABLE server_profiles ADD COLUMN manualCountryCode TEXT")

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_server_profiles_subscriptionId` ON `server_profiles` (`subscriptionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_server_profiles_sourceType` ON `server_profiles` (`sourceType`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_server_profiles_canonicalFingerprint` ON `server_profiles` (`canonicalFingerprint`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_server_profiles_isEnabled` ON `server_profiles` (`isEnabled`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `deleted_profile_backups` (
                        `id` TEXT NOT NULL,
                        `batchId` TEXT NOT NULL,
                        `deletedAt` INTEGER NOT NULL,
                        `label` TEXT NOT NULL,
                        `profileId` TEXT NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_deleted_profile_backups_batchId` ON `deleted_profile_backups` (`batchId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_deleted_profile_backups_deletedAt` ON `deleted_profile_backups` (`deletedAt`)")

                // Safe ownership classification: never delete or overwrite existing rows,
                // never associate unknown configs with a subscription.
                db.execSQL(
                    """
                    UPDATE server_profiles
                    SET sourceType = 'SUBSCRIPTION'
                    WHERE subscriptionId IS NOT NULL
                      AND subscriptionId IN (SELECT id FROM subscriptions)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE server_profiles
                    SET sourceType = 'MANUAL'
                    WHERE subscriptionId IS NULL
                      AND groupName IN ('Local', 'Imported', '')
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE server_profiles
                    SET sourceType = 'LEGACY_UNCLASSIFIED'
                    WHERE NOT (
                        (subscriptionId IS NOT NULL AND subscriptionId IN (SELECT id FROM subscriptions))
                        OR (subscriptionId IS NULL AND groupName IN ('Local', 'Imported', ''))
                    )
                    """.trimIndent()
                )
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
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
