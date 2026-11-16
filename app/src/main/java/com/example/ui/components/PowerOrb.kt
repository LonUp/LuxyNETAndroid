package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.model.ConnectionState
import com.example.data.model.OrbShape
import com.example.ui.theme.LocalLuxyColors
import com.example.ui.theme.LocalLuxyMetrics
import com.example.ui.theme.LocalLuxyMotion
import kotlin.math.cos
import kotlin.math.sin

/**
 * The connection cockpit's power orb: 156 dp, radial fill, five visual states, seven shapes.
 *
 * Idle → glowing ring (connecting, rotating accent arc) → green palette swap (connected).
 * The aura is drawn, not a shadow, because a coloured glow needs a gradient, not elevation.
 */
@Composable
fun PowerOrb(
    state: ConnectionState,
    shape: OrbShape,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = LocalLuxyMetrics.current.orbSize
) {
    val colors = LocalLuxyColors.current
    val motion = LocalLuxyMotion.current
    val isConnected = state == ConnectionState.CONNECTED
    val isBusy = state == ConnectionState.CONNECTING ||
        state == ConnectionState.DISCONNECTING ||
        state == ConnectionState.RECONNECTING ||
        state == ConnectionState.FAILOVER
    val isError = state == ConnectionState.ERROR

    val transition = rememberInfiniteTransition(label = "orb")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb-spin"
    )
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb-pulse"
    )
    val spinAngle = if (motion && isBusy) spin else 0f
    val pulseValue = if (motion && isConnected) pulse else 0f

    val ringColor = when {
        isError -> colors.statusError
        isConnected -> colors.statusConnected
        isBusy -> colors.primary
        else -> colors.onSurface.copy(alpha = 0.10f)
    }
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(size)
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val radius = this.size.minDimension / 2f * 0.80f
            val center = Offset(this.size.width / 2f, this.size.height / 2f)

            if (isConnected || isBusy) {
                val auraAlpha = (if (isConnected) 0.20f + 0.10f * pulseValue else 0.16f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(ringColor.copy(alpha = auraAlpha), Color.Transparent),
                        center = center,
                        radius = radius * 1.45f
                    ),
                    radius = radius * 1.45f,
                    center = center
                )
            }

            val path = orbPath(shape, center, radius)
            if (shape == OrbShape.RING) {
                drawPath(path, color = colors.orbOuter.copy(alpha = 0.9f))
            } else {
                drawPath(
                    path = path,
                    brush = Brush.radialGradient(
                        colors = listOf(
                            if (isConnected) colors.orbInner.copy(alpha = 0.95f) else colors.orbInner,
                            colors.orbOuter
                        ),
                        center = Offset(center.x - radius * 0.25f, center.y - radius * 0.35f),
                        radius = radius * 1.7f
                    )
                )
            }

            // Inset top highlight: a soft arc, the Compose equivalent of an inset box-shadow.
            drawArc(
                color = Color.White.copy(alpha = if (isConnected) 0.10f else 0.07f),
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(center.x - radius * 0.92f, center.y - radius * 0.92f),
                size = Size(radius * 1.84f, radius * 1.84f),
                style = Stroke(width = 2.dp.toPx())
            )

            if (shape == OrbShape.RING) {
                drawPath(
                    path = path,
                    color = ringColor.copy(alpha = if (isBusy) 0.55f else 0.35f),
                    style = Stroke(width = radius * 0.22f)
                )
            }

            if (isBusy) {
                rotate(degrees = spinAngle, pivot = center) {
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(center.x - radius * 1.04f, center.y - radius * 1.04f),
                        size = Size(radius * 2.08f, radius * 2.08f),
                        style = Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    )
                }
            } else if (isConnected) {
                drawArc(
                    color = ringColor.copy(alpha = 0.85f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius * 1.02f, center.y - radius * 1.02f),
                    size = Size(radius * 2.04f, radius * 2.04f),
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }
        }

        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = when {
                isError -> colors.statusError
                isConnected -> colors.statusConnected
                else -> colors.onSurface
            }
        )
    }
}

/** Builds the seven orb silhouettes, all inside the same radius so labels stay centred. */
internal fun orbPath(shape: OrbShape, center: Offset, radius: Float): Path {
    val path = Path()
    when (shape) {
        OrbShape.CIRCLE, OrbShape.RING -> path.addOval(
            Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
        )
        OrbShape.SQUIRCLE -> path.addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius),
                androidx.compose.ui.geometry.CornerRadius(radius * 0.72f, radius * 0.72f)
            )
        )
        OrbShape.HEXAGON -> return polygonPath(6, radius, center)
        OrbShape.DIAMOND -> return polygonPath(4, radius * 1.12f, center)
        OrbShape.SHIELD -> {
            path.moveTo(center.x - radius, center.y - radius * 0.72f)
            path.quadraticBezierTo(center.x, center.y - radius * 1.12f, center.x + radius, center.y - radius * 0.72f)
            path.lineTo(center.x + radius, center.y + radius * 0.18f)
            path.quadraticBezierTo(center.x, center.y + radius * 1.28f, center.x - radius, center.y + radius * 0.18f)
            path.close()
        }
        OrbShape.TEARDROP -> {
            path.moveTo(center.x, center.y - radius * 1.05f)
            path.cubicTo(
                center.x + radius * 0.55f, center.y - radius * 0.55f,
                center.x + radius, center.y + radius * 0.15f,
                center.x, center.y + radius
            )
            path.cubicTo(
                center.x - radius, center.y + radius * 0.15f,
                center.x - radius * 0.55f, center.y - radius * 0.55f,
                center.x, center.y - radius * 1.05f
            )
            path.close()
        }
    }
    return path
}

/** Arc helper for the glow ring, kept out of the draw lambda for readability. */
internal fun ringPoint(center: Offset, radius: Float, degrees: Float): Offset {
    val rad = Math.toRadians(degrees.toDouble())
    return Offset(center.x + radius * cos(rad).toFloat(), center.y + radius * sin(rad).toFloat())
}
