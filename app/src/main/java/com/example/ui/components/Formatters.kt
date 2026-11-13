package com.example.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.data.model.ProxyProtocol
import com.example.ui.theme.LocalTunnelStatusColors
import com.example.ui.theme.ProtoDnstt
import com.example.ui.theme.ProtoHysteria
import com.example.ui.theme.ProtoMasque
import com.example.ui.theme.ProtoMasterDns
import com.example.ui.theme.ProtoShadowsocks
import com.example.ui.theme.ProtoSocks
import com.example.ui.theme.ProtoTrojan
import com.example.ui.theme.ProtoVless
import com.example.ui.theme.ProtoVmess
import com.example.ui.theme.ProtoWireGuard
import com.example.ui.theme.TunnelStatusColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

object Formatters {

    fun formatSpeed(bytesPerSec: Long): String {
        val bps = max(0L, bytesPerSec)
        return when {
            bps < 1024L -> "$bps B/s"
            bps < 1024L * 1024L -> String.format(Locale.US, "%.1f KB/s", bps / 1024.0)
            else -> String.format(Locale.US, "%.2f MB/s", bps / (1024.0 * 1024.0))
        }
    }

    fun formatBytes(bytes: Long): String {
        val b = max(0L, bytes)
        return when {
            b < 1024L -> "$b B"
            b < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
            b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", b / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun formatDuration(sinceMs: Long?, nowMs: Long = System.currentTimeMillis()): String {
        if (sinceMs == null) return "00:00:00"
        val totalSec = ((nowMs - sinceMs) / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)
    }

    fun formatTime(timestampMs: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
        return sdf.format(Date(timestampMs))
    }

    fun formatDateTime(timestampMs: Long?): String {
        if (timestampMs == null) return "Never"
        val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.US)
        return sdf.format(Date(timestampMs))
    }

    @Composable
    fun latencyColor(latencyMs: Int?): Color {
        val status = LocalTunnelStatusColors.current
        return when {
            latencyMs == null -> MaterialTheme.colorScheme.onSurfaceVariant
            latencyMs < 0 -> status.error
            latencyMs <= 120 -> status.connected
            latencyMs <= 260 -> status.connecting
            else -> status.error
        }
    }

    fun latencyColor(latencyMs: Int?, statusColors: TunnelStatusColors, neutralColor: Color): Color = when {
        latencyMs == null -> neutralColor
        latencyMs < 0 -> statusColors.error
        latencyMs <= 120 -> statusColors.connected
        latencyMs <= 260 -> statusColors.connecting
        else -> statusColors.error
    }

    fun latencyLabel(latencyMs: Int?): String = when {
        latencyMs == null -> "Untested"
        latencyMs < 0 -> "Timeout"
        else -> "$latencyMs ms"
    }

    fun protocolColor(protocol: ProxyProtocol): Color = when (protocol) {
        ProxyProtocol.VLESS -> ProtoVless
        ProxyProtocol.VMESS -> ProtoVmess
        ProxyProtocol.TROJAN -> ProtoTrojan
        ProxyProtocol.SHADOWSOCKS -> ProtoShadowsocks
        ProxyProtocol.HYSTERIA2 -> ProtoHysteria
        ProxyProtocol.SOCKS -> ProtoSocks
        ProxyProtocol.WIREGUARD -> ProtoWireGuard
        ProxyProtocol.MASQUE -> ProtoMasque
        ProxyProtocol.DNSTT -> ProtoDnstt
        ProxyProtocol.MASTERDNS -> ProtoMasterDns
    }
}
