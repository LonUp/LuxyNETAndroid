package com.example.core

import com.example.data.model.ErrorCategory

/**
 * Batch-level decisions for bulk proxy retesting.
 *
 * A batch that is dominated by transient or inconclusive probe outcomes says something about the
 * network, not about the configurations. Those outcomes must not be stamped onto stored health.
 */
object ProbeBatchPolicy {
    private const val MIN_COMPLETED_PROBES = 4
    private const val SUPPRESSION_NUMERATOR = 4
    private const val SUPPRESSION_DENOMINATOR = 5

    private fun isTransientProbeFailure(category: ErrorCategory): Boolean = when (category) {
        ErrorCategory.TIMEOUT,
        ErrorCategory.DNS_FAILURE,
        ErrorCategory.NO_INTERNET -> true
        else -> false
    }

    /**
     * @param outcomes one category per completed probe, including [ErrorCategory.NONE] for a
     * successful probe, in any order.
     * @return true when the batch is dominated (at least 80%) by transient network failures and
     * at least four probes completed, meaning failed probes must not update stored health.
     */
    fun shouldSuppressHealthStamp(outcomes: List<ErrorCategory>): Boolean {
        if (outcomes.size < MIN_COMPLETED_PROBES) return false
        val transient = outcomes.count(::isTransientProbeFailure)
        return transient * SUPPRESSION_DENOMINATOR >= outcomes.size * SUPPRESSION_NUMERATOR
    }
}
