package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerProfileDao {
    @Query("SELECT * FROM server_profiles ORDER BY isPinned DESC, createdAt DESC")
    fun observeAll(): Flow<List<ServerProfileEntity>>

    @Query("SELECT * FROM server_profiles ORDER BY isPinned DESC, createdAt DESC")
    suspend fun getAllOnce(): List<ServerProfileEntity>

    @Query("SELECT * FROM server_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ServerProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ServerProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(profiles: List<ServerProfileEntity>)

    @Update
    suspend fun update(profile: ServerProfileEntity)

    @Query("UPDATE server_profiles SET latencyMs = :latencyMs, lastJitterMs = :jitterMs, lastTestedAt = :testedAt WHERE id = :id")
    suspend fun updateLatency(id: String, latencyMs: Int, jitterMs: Int, testedAt: Long)

    @Query("UPDATE server_profiles SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("DELETE FROM server_profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM server_profiles WHERE subscriptionId = :subscriptionId")
    suspend fun deleteBySubscriptionId(subscriptionId: String)

    @Transaction
    suspend fun replaceSubscriptionNodes(subscriptionId: String, nodes: List<ServerProfileEntity>) {
        deleteBySubscriptionId(subscriptionId)
        upsertAll(nodes)
    }
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions")
    suspend fun getAllOnce(): List<SubscriptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(subscription: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface RoutingRuleDao {
    @Query("SELECT * FROM routing_rules ORDER BY priority ASC, name ASC")
    fun observeAll(): Flow<List<RoutingRuleEntity>>

    @Query("SELECT COUNT(*) FROM routing_rules")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: RoutingRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rules: List<RoutingRuleEntity>)

    @Query("UPDATE routing_rules SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM routing_rules WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface StabilityEventDao {
    @Query("SELECT * FROM stability_events ORDER BY timestamp DESC, id DESC LIMIT 250")
    fun observeRecent(): Flow<List<StabilityEventEntity>>

    @Insert
    suspend fun insert(event: StabilityEventEntity)

    @Query("DELETE FROM stability_events WHERE id NOT IN (SELECT id FROM stability_events ORDER BY timestamp DESC, id DESC LIMIT 300)")
    suspend fun trimOld()

    @Query("DELETE FROM stability_events")
    suspend fun clearAll()
}

@Dao
interface NetworkStrategyDao {
    @Query("SELECT * FROM network_strategies ORDER BY lastSuccessAt DESC")
    fun observeAll(): Flow<List<NetworkStrategyEntity>>

    @Query("SELECT * FROM network_strategies WHERE networkFingerprint = :fingerprint LIMIT 1")
    suspend fun getByFingerprint(fingerprint: String): NetworkStrategyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(strategy: NetworkStrategyEntity)

    @Query("DELETE FROM network_strategies WHERE networkFingerprint = :fingerprint")
    suspend fun deleteByFingerprint(fingerprint: String)

    @Query("DELETE FROM network_strategies")
    suspend fun clearAll()
}
