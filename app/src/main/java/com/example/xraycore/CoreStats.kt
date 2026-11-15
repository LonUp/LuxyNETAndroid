package com.example.xraycore

/** Cumulative core traffic in bytes. */
data class CoreTraffic(val uplinkBytes: Long = 0L, val downlinkBytes: Long = 0L)

/**
 * Parses `CoreController.queryAllOutboundTrafficStats()` output.
 *
 * Format is a single line of `tag,direction,value;` entries, where direction is
 * `uplink`/`downlink`. Unknown or malformed entries are ignored rather than failing the
 * telemetry loop.
 */
object CoreStatsParser {

    fun parse(raw: String?): CoreTraffic {
        if (raw.isNullOrBlank()) return CoreTraffic()
        var up = 0L
        var down = 0L
        raw.split(';').forEach { entry ->
            val parts = entry.trim().split(',')
            if (parts.size < 3) return@forEach
            val value = parts[2].trim().toLongOrNull() ?: return@forEach
            when (parts[1].trim().lowercase()) {
                "up", "uplink" -> up += value
                "down", "downlink" -> down += value
                // Anything else is not traffic we can attribute; ignore it.
            }
        }
        return CoreTraffic(up, down)
    }
}
