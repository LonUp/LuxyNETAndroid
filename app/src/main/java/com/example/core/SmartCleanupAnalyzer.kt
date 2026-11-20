package com.example.core

import com.example.data.local.ServerProfileEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.model.CountryConfidence
import com.example.data.model.ErrorCategory
import com.example.data.model.HealthPolicy
import com.example.data.model.HealthStatus
import com.example.data.model.SmartCleanupCategory
import com.example.data.model.SmartCleanupGroup
import com.example.data.model.SmartCleanupItem
import com.example.data.model.SmartCleanupPlan
import com.example.data.model.SourceType

/**
 * Pure, deterministic analysis of the stored library into non-overlapping cleanup suggestions.
 *
 * Suggestions are advisory only:
 *  - a user's active connection is never proposed for removal,
 *  - every profile lands in at most one (highest priority) category,
 *  - only genuine duplicates and structurally invalid configurations are selected by default;
 *    every other category requires explicit review.
 */
object SmartCleanupAnalyzer {

    /** A timeout that keeps repeating for this long without any success is no longer "temporary". */
    private const val TIMEOUT_FAILURE_THRESHOLD = 6

    /** A success within this window proves a timeout streak is not permanent. */
    private const val RECENT_SUCCESS_WINDOW_MS: Long = 24L * 60L * 60L * 1000L

    private val CATEGORY_ORDER = listOf(
        SmartCleanupCategory.DUPLICATES,
        SmartCleanupCategory.INVALID,
        SmartCleanupCategory.ORPHANED,
        SmartCleanupCategory.REPEATED_FAILURES,
        SmartCleanupCategory.EXTENDED_DISABLED,
        SmartCleanupCategory.STALE_HEALTH
    )

    fun dismissalKey(item: SmartCleanupItem): String =
        "${item.category.name}:${item.profile.id}:${item.keeperId.orEmpty()}:${item.profile.consecutiveFailures}:${item.profile.disabledAt ?: 0L}"

    fun analyze(
        allProfiles: List<ServerProfileEntity>,
        subscriptions: List<SubscriptionEntity>,
        activeNodeId: String? = null,
        currentNetworkFingerprint: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        dismissedKeys: Set<String> = emptySet()
    ): SmartCleanupPlan {
        if (allProfiles.isEmpty()) return SmartCleanupPlan(groups = emptyList(), totalSuggested = 0)

        val existingSubIds = subscriptions.mapTo(HashSet()) { it.id }
        val protectedId = activeNodeId?.takeIf { id -> allProfiles.any { it.id == id } }
        val claimed = HashSet<String>(allProfiles.size)
        protectedId?.let { claimed += it }

        val suggestions = LinkedHashMap<SmartCleanupCategory, MutableList<SmartCleanupItem>>()
        for (category in CATEGORY_ORDER) suggestions[category] = mutableListOf()

        collectDuplicates(
            allProfiles = allProfiles,
            activeNodeId = protectedId,
            claimed = claimed,
            out = suggestions.getValue(SmartCleanupCategory.DUPLICATES)
        )
        for (profile in allProfiles) {
            if (!claimed.add(profile.id)) continue
            val item = when {
                isInvalid(profile) -> invalidItem(profile)
                isOrphaned(profile, existingSubIds) -> orphanedItem(profile)
                isRepeatedFailure(profile, nowMs) -> repeatedFailureItem(profile)
                isExtendedDisabled(profile, nowMs) -> extendedDisabledItem(profile)
                isStaleHealth(profile, nowMs, currentNetworkFingerprint) -> staleHealthItem(profile)
                else -> null
            }
            if (item != null) {
                suggestions.getValue(item.category) += item
            } else {
                claimed.remove(profile.id)
            }
        }

        val groups = CATEGORY_ORDER.mapNotNull { category ->
            val items = suggestions.getValue(category)
                .filterNot { dismissalKey(it) in dismissedKeys }
                .sortedWith(
                    compareBy<SmartCleanupItem> { it.profile.name }
                        .thenBy { it.profile.id }
                )
            if (items.isEmpty()) return@mapNotNull null
            SmartCleanupGroup(
                category = category,
                items = items,
                defaultSelected = category == SmartCleanupCategory.DUPLICATES ||
                    category == SmartCleanupCategory.INVALID
            )
        }
        return SmartCleanupPlan(
            groups = groups,
            totalSuggested = groups.sumOf { it.items.size }
        )
    }

    /**
     * Genuine duplicates are scoped: manual configurations only collide with other manual
     * configurations, and a subscription's nodes only collide inside that same subscription.
     * The active node is never suggested; if it belongs to a duplicate set it becomes the keeper.
     */
    private fun collectDuplicates(
        allProfiles: List<ServerProfileEntity>,
        activeNodeId: String? = null,
        claimed: MutableSet<String>,
        out: MutableList<SmartCleanupItem>
    ) {
        val scoped = LinkedHashMap<String, MutableList<ServerProfileEntity>>()
        for (profile in allProfiles) {
            val scope = when (SourceType.parse(profile.sourceType)) {
                SourceType.MANUAL -> "manual"
                SourceType.SUBSCRIPTION -> profile.subscriptionId?.let { "sub:$it" }
                SourceType.LEGACY_UNCLASSIFIED -> null
            } ?: continue
            scoped.getOrPut(scope) { mutableListOf() } += profile
        }
        for (profiles in scoped.values) {
            val byFingerprint = profiles.groupBy { ConfigFingerprint.compute(it) }
            for (group in byFingerprint.values) {
                if (group.size <= 1) continue
                val keeper = group.firstOrNull { it.id == activeNodeId }
                    ?: group.sortedWith(KEEPER_ORDER).first()
                for (profile in group) {
                    if (profile.id == keeper.id) continue
                    if (!claimed.add(profile.id)) continue
                    out += SmartCleanupItem(
                        profile = profile,
                        category = SmartCleanupCategory.DUPLICATES,
                        reason = "Duplicate of ${keeper.name.ifBlank { keeper.id }}",
                        keeperId = keeper.id
                    )
                }
                claimed += keeper.id
            }
        }
    }

    private fun isInvalid(profile: ServerProfileEntity): Boolean =
        !ClientPolicy.isSupported(profile) || HealthStatus.parse(profile.healthStatus) == HealthStatus.INVALID

    private fun isOrphaned(profile: ServerProfileEntity, existingSubIds: Set<String>): Boolean {
        if (SourceType.parse(profile.sourceType) == SourceType.LEGACY_UNCLASSIFIED) return true
        val subId = profile.subscriptionId ?: return false
        return subId !in existingSubIds
    }

    /**
     * A transient timeout is never treated as a permanent failure: timeouts need a materially
     * longer streak and must not have had a recent success, whereas other error categories only
     * need three consecutive failures.
     */
    private fun isRepeatedFailure(profile: ServerProfileEntity, nowMs: Long): Boolean {
        if (profile.consecutiveFailures <= 0) return false
        val category = ErrorCategory.parse(profile.lastErrorCategory)
        return if (category == ErrorCategory.TIMEOUT) {
            val hadRecentSuccess = profile.lastSuccessAt?.let { nowMs - it < RECENT_SUCCESS_WINDOW_MS } == true
            profile.consecutiveFailures >= TIMEOUT_FAILURE_THRESHOLD && !hadRecentSuccess
        } else {
            profile.consecutiveFailures >= 3
        }
    }

    private fun isExtendedDisabled(profile: ServerProfileEntity, nowMs: Long): Boolean {
        if (profile.isEnabled) return false
        val disabledAt = profile.disabledAt ?: return false
        return nowMs - disabledAt >= HealthPolicy.EXTENDED_DISABLED_MS
    }

    private fun isStaleHealth(
        profile: ServerProfileEntity,
        nowMs: Long,
        currentNetworkFingerprint: String?
    ): Boolean {
        if (!HealthPolicy.isHealthStale(profile, nowMs, currentNetworkFingerprint)) return false
        val lastTouch = profile.lastSuccessAt ?: profile.lastAttemptAt ?: profile.createdAt
        return nowMs - lastTouch >= HealthPolicy.STALE_HEALTH_CLEANUP_MS
    }

    private fun invalidItem(profile: ServerProfileEntity) = SmartCleanupItem(
        profile = profile,
        category = SmartCleanupCategory.INVALID,
        reason = if (HealthStatus.parse(profile.healthStatus) == HealthStatus.INVALID) {
            "Configuration is marked invalid"
        } else {
            "Unsupported by this client"
        }
    )

    private fun orphanedItem(profile: ServerProfileEntity): SmartCleanupItem {
        val legacy = SourceType.parse(profile.sourceType) == SourceType.LEGACY_UNCLASSIFIED
        return SmartCleanupItem(
            profile = profile,
            category = SmartCleanupCategory.ORPHANED,
            reason = if (legacy) {
                "Unclassified legacy configuration"
            } else {
                "Subscription ${profile.subscriptionId.orEmpty()} no longer exists"
            }
        )
    }

    private fun repeatedFailureItem(profile: ServerProfileEntity) = SmartCleanupItem(
        profile = profile,
        category = SmartCleanupCategory.REPEATED_FAILURES,
        reason = "Failed ${profile.consecutiveFailures} consecutive times (${profile.lastErrorCategory})"
    )

    private fun extendedDisabledItem(profile: ServerProfileEntity) = SmartCleanupItem(
        profile = profile,
        category = SmartCleanupCategory.EXTENDED_DISABLED,
        reason = "Disabled for more than 30 days"
    )

    private fun staleHealthItem(profile: ServerProfileEntity) = SmartCleanupItem(
        profile = profile,
        category = SmartCleanupCategory.STALE_HEALTH,
        reason = "Health data has been stale for more than 14 days"
    )

    /**
     * Best keeper: active > preferred > working > verified country > enabled > lowest latency > oldest.
     */
    private val KEEPER_ORDER: Comparator<ServerProfileEntity> =
        compareByDescending<ServerProfileEntity> { it.isPinned }
            .thenByDescending { HealthStatus.parse(it.healthStatus) == HealthStatus.WORKING }
            .thenByDescending { CountryConfidence.parse(it.countryConfidence) == CountryConfidence.VERIFIED }
            .thenByDescending { it.isEnabled }
            .thenBy { it.latencyMs ?: Int.MAX_VALUE }
            .thenBy { it.createdAt }
}
