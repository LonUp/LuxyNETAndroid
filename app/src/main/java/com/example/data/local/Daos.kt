package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerProfileDao {
    @Query("SELECT * FROM server_profiles ORDER BY isPinned DESC, createdAt DESC")
    fun observeAll(): Flow<List<ServerProfileEntity>>

    @Query("SELECT * FROM server_profiles ORDER BY isPinned DESC, createdAt DESC")
    suspend fun getAll(): List<ServerProfileEntity>

    @Query("SELECT * FROM server_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ServerProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ServerProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(profiles: List<ServerProfileEntity>)

    @Query("DELETE FROM server_profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM server_profiles WHERE subscriptionId = :subId")
    suspend fun deleteBySubscriptionId(subId: String)

    @Query("UPDATE server_profiles SET latencyMs = :latencyMs, lastJitterMs = :jitterMs, lastTestedAt = :testedAt WHERE id = :id")
    suspend fun updateLatency(id: String, latencyMs: Int?, jitterMs: Int, testedAt: Long)

    @Query("UPDATE server_profiles SET isPinned = :pinned WHERE id = :id")
    suspend fun updatePinned(id: String, pinned: Boolean)

    @Query("UPDATE server_profiles SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE server_profiles SET isEnabled = :enabled WHERE id IN (:ids)")
    suspend fun setEnabledByIds(ids: List<String>, enabled: Boolean)

    @Query("UPDATE server_profiles SET isEnabled = :enabled, disabledAt = :disabledAt WHERE id = :id")
    suspend fun setEnabledWithTimestamp(id: String, enabled: Boolean, disabledAt: Long?)

    @Query("UPDATE server_profiles SET isEnabled = :enabled, disabledAt = :disabledAt WHERE id IN (:ids)")
    suspend fun setEnabledByIdsWithTimestamp(ids: List<String>, enabled: Boolean, disabledAt: Long?)

    @Query("DELETE FROM server_profiles WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("UPDATE server_profiles SET sourceType = :sourceType, subscriptionId = :subscriptionId, groupName = :groupName WHERE id IN (:ids)")
    suspend fun updateSourceOwnership(ids: List<String>, sourceType: String, subscriptionId: String?, groupName: String)

    @Query("UPDATE server_profiles SET manualCountryCode = :manualCountryCode WHERE id = :id")
    suspend fun updateManualCountry(id: String, manualCountryCode: String?)

    @Query("SELECT * FROM server_profiles WHERE sourceType = :sourceType ORDER BY isPinned DESC, createdAt DESC")
    suspend fun getBySourceType(sourceType: String): List<ServerProfileEntity>

    @Query("SELECT * FROM server_profiles WHERE subscriptionId = :subId")
    suspend fun getBySubscriptionId(subId: String): List<ServerProfileEntity>

    @Query("SELECT COUNT(*) FROM server_profiles WHERE subscriptionId = :subId AND sourceType = 'SUBSCRIPTION'")
    suspend fun countBySubscriptionId(subId: String): Int
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY createdAt DESC")
    suspend fun getAll(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun getById(id: String): SubscriptionEntity?

    @Query("UPDATE subscriptions SET lastStatus = 'Failed', lastError = :error WHERE id = :id AND url = :url")
    suspend fun recordRefreshFailure(id: String, url: String, error: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sub: SubscriptionEntity)

    @Query("UPDATE subscriptions SET nodeCount = :nodeCount WHERE id = :id")
    suspend fun updateNodeCount(id: String, nodeCount: Int)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE subscriptions SET isEnabledForConnection = :enabled WHERE id = :id")
    suspend fun setEnabledForConnection(id: String, enabled: Boolean)
}

@Dao
interface RoutingRuleDao {
    @Query("SELECT * FROM routing_rules ORDER BY priority ASC, name ASC")
    fun observeAll(): Flow<List<RoutingRuleEntity>>

    @Query("SELECT * FROM routing_rules ORDER BY priority ASC, name ASC")
    suspend fun getAll(): List<RoutingRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: RoutingRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rules: List<RoutingRuleEntity>)

    @Query("UPDATE routing_rules SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM routing_rules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM routing_rules WHERE managedKey LIKE 'geo-%'")
    suspend fun deleteManagedGeoRules()
}

@Dao
interface StabilityEventDao {
    @Query("SELECT * FROM stability_events ORDER BY timestamp DESC LIMIT 400")
    fun observeRecent(): Flow<List<StabilityEventEntity>>

    @Insert
    suspend fun insert(event: StabilityEventEntity)

    @Query("DELETE FROM stability_events")
    suspend fun clearAll()
}

@Dao
interface NetworkStrategyDao {
    @Query("SELECT * FROM network_strategies")
    fun observeAll(): Flow<List<NetworkStrategyEntity>>

    @Query("SELECT * FROM network_strategies WHERE networkFingerprint = :fingerprint LIMIT 1")
    suspend fun getByFingerprint(fingerprint: String): NetworkStrategyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(strategy: NetworkStrategyEntity)
}

@Dao
interface DeletedProfileBackupDao {
    @Query("SELECT * FROM deleted_profile_backups ORDER BY deletedAt DESC")
    fun observeAll(): Flow<List<DeletedProfileBackupEntity>>

    @Query("SELECT * FROM deleted_profile_backups ORDER BY deletedAt DESC")
    suspend fun getAll(): List<DeletedProfileBackupEntity>

    @Query("SELECT * FROM deleted_profile_backups WHERE batchId = :batchId")
    suspend fun getByBatchId(batchId: String): List<DeletedProfileBackupEntity>

    @Query("SELECT * FROM deleted_profile_backups WHERE subscriptionId = :subId")
    suspend fun getBySubscriptionId(subId: String): List<DeletedProfileBackupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DeletedProfileBackupEntity>)

    @Query("DELETE FROM deleted_profile_backups WHERE batchId = :batchId")
    suspend fun deleteByBatchId(batchId: String)

    @Query("SELECT * FROM deleted_profile_backups WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<DeletedProfileBackupEntity>

    @Query("DELETE FROM deleted_profile_backups WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM deleted_profile_backups WHERE expiresAt > 0 AND expiresAt <= :nowMs")
    suspend fun deleteExpired(nowMs: Long): Int

    @Query("DELETE FROM deleted_profile_backups WHERE batchId NOT IN (:keepBatchIds)")
    suspend fun deleteBatchesNotIn(keepBatchIds: List<String>)

    @Query("DELETE FROM deleted_profile_backups")
    suspend fun clearAll()
}
