package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CardStyle
import com.example.data.model.ThemePattern
import com.example.ui.theme.LocalIsDarkTheme
import com.example.ui.theme.LocalLuxyColors
import com.example.ui.theme.LocalLuxyMetrics
import com.example.ui.theme.LocalLuxyMotion
import com.example.ui.theme.LocalThemeConfig
import kotlin.math.max

/**
 * Frame around every screen: the reference's three blurred colour blobs, a faint 28 px grid,
 * and the optional dots/checker pattern, all behind scrolling content.
 *
 * Compose has no backdrop blur filter, so the blobs are soft radial gradients rather than a
 * blurred bitmap: same look, no extra render pass.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = LocalLuxyColors.current
    val theme = LocalThemeConfig.current
    val isDark = LocalIsDarkTheme.current
    val motion = LocalLuxyMotion.current
    val fraction by animateFloatAsState(
        targetValue = 1f,
        animationSpec = if (motion) tween(durationMillis = 18_000) else tween(1),
        label = "aurora-drift"
    )
    Box(modifier = modifier.background(colors.background)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (theme.pattern == ThemePattern.AURORA) {
                val drift = if (motion) fraction else 0.5f
                blob(colors.statusConnected, 0.14f, Offset(0.10f, 0.16f + drift * 0.04f), size.minDimension * 0.55f, isDark)
                blob(colors.statusInfo, 0.13f, Offset(0.88f, 0.30f - drift * 0.03f), size.minDimension * 0.50f, isDark)
                blob(colors.primary, 0.16f, Offset(0.55f, 0.92f - drift * 0.05f), size.minDimension * 0.60f, isDark)
            }
            when (theme.pattern) {
                ThemePattern.DOTS -> dots(colors.onBackground.copy(alpha = 0.05f))
                ThemePattern.CHECKER -> checker(colors.onBackground.copy(alpha = 0.035f))
                ThemePattern.AURORA, ThemePattern.NONE -> Unit
            }
            grid(colors.onBackground.copy(alpha = if (isDark) 0.025f else 0.02f))
        }
        content()
    }
}

private fun DrawScope.blob(color: Color, alpha: Float, anchor: Offset, radius: Float, isDark: Boolean) {
    val center = Offset(anchor.x * size.width, anchor.y * size.height)
    val a = if (isDark) alpha else alpha * 0.55f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = a), Color.Transparent),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

private fun DrawScope.dots(color: Color) {
    val step = 22.dp.toPx()
    val r = 1.1.dp.toPx()
    var y = step / 2f
    while (y < size.height) {
        var x = step / 2f
        while (x < size.width) {
            drawCircle(color, r, Offset(x, y))
            x += step
        }
        y += step
    }
}

private fun DrawScope.checker(color: Color) {
    val cell = 18.dp.toPx()
    var rowIndex = 0
    var y = 0f
    while (y < size.height) {
        var columnIndex = 0
        var x = 0f
        while (x < size.width) {
            if ((rowIndex + columnIndex) % 2 == 0) {
                drawRect(color, Offset(x, y), Size(cell, cell))
            }
            columnIndex++
            x += cell
        }
        rowIndex++
        y += cell
    }
}

private fun DrawScope.grid(color: Color) {
    val step = 28.dp.toPx()
    val stroke = 1f
    var x = 0f
    while (x < size.width) {
        drawRect(color, Offset(x, 0f), Size(stroke, size.height))
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawRect(color, Offset(0f, y), Size(size.width, stroke))
        y += step
    }
}

/**
 * Card honouring the theme's card style: filled, outline, shadowed, or matted glass
 * (translucent card + 1 px inner highlight, since Compose cannot blur what is behind it).
 */
@Composable
fun LuxyCard(
    modifier: Modifier = Modifier,
    padding: Dp = LocalLuxyMetrics.current.cardPadding,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalLuxyColors.current
    val metrics = LocalLuxyMetrics.current
    val style = LocalThemeConfig.current.cardStyle
    val shape = RoundedCornerShape(metrics.radiusLg)
    val container = when (style) {
        CardStyle.FILLED, CardStyle.SHADOWED -> colors.surface
        CardStyle.OUTLINE -> Color.Transparent
        CardStyle.GLASS -> colors.surface.copy(alpha = if (LocalIsDarkTheme.current) 0.72f else 0.86f)
    }
    val borderColor = if (style == CardStyle.OUTLINE) colors.line2 else colors.line
    val clickable = if (onClick != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .then(if (style == CardStyle.SHADOWED || style == CardStyle.GLASS) Modifier.shadow(10.dp, shape) else Modifier)
            .clip(shape)
            .background(container)
            .border(BorderStroke(1.dp, borderColor), shape)
            .then(clickable)
    ) {
        // 1 px inner highlight along the top edge, as in the reference cards.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(colors.onSurface.copy(alpha = 0.10f), Color.Transparent)
                    )
                )
        )
        Column(
            modifier = Modifier.padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

/** Sticky glass surface for the top bar and the dock. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(LocalLuxyMetrics.current.radiusPill),
    content: @Composable RowScope.() -> Unit
) {
    val colors = LocalLuxyColors.current
    Row(
        modifier = modifier
            .shadow(12.dp, shape)
            .clip(shape)
            .background(colors.nav)
            .border(BorderStroke(1.dp, colors.line2), shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** Brand row: actions at the start, wordmark + verified shield at the end (RTL-mirrored). */
@Composable
fun LuxyTopBar(
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val colors = LocalLuxyColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // In RTL the first child sits on the physical right, which is where the reference keeps
        // the wordmark; the action cluster therefore comes last.
        Text(
            text = "LuxyNET",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground,
            modifier = Modifier.padding(end = 8.dp)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = trailing
        )
        Spacer(modifier = Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions
        )
    }
}

/** Floating pill dock: four equal columns, 6 dp padding, safe-area aware via the caller. */
@Composable
fun LuxyDock(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        content = content
    )
}

@Composable
fun RowScope.DockItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalLuxyColors.current
    val tint = if (selected) colors.primary else colors.onSurfaceVariant
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(LocalLuxyMetrics.current.radiusPill))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/** Segmented control with an animated raised thumb (reference: inset track + spring thumb). */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String
) {
    val colors = LocalLuxyColors.current
    val metrics = LocalLuxyMetrics.current
    val motion = LocalLuxyMotion.current
    val index = max(0, options.indexOf(selected))
    val shape = RoundedCornerShape(metrics.radiusMd)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceVariant)
            .border(BorderStroke(1.dp, colors.line), shape)
            .padding(3.dp)
    ) {
        val segmentWidth = maxWidth / options.size
        val segmentWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { segmentWidth.toPx() }
        val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current ==
            androidx.compose.ui.unit.LayoutDirection.Rtl
        // Row reverses children in RTL, so the thumb must be mirrored to stay under its segment.
        val thumbIndex = if (isRtl) options.lastIndex - index else index
        val offset by animateFloatAsState(
            targetValue = segmentWidthPx * thumbIndex,
            animationSpec = if (motion) {
                androidx.compose.animation.core.spring(0.6f, 700f, 0.7f)
            } else {
                tween(1)
            },
            label = "segment-thumb"
        )
        Box(
            modifier = Modifier
                .graphicsLayer { translationX = offset }
                .width(segmentWidth)
                .height(metrics.rowHeight - 6.dp)
                .clip(shape)
                .background(colors.surface)
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(metrics.rowHeight - 6.dp)
                        .clip(shape)
                        .clickable { onSelect(option) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label(option),
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) colors.onSurface else colors.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** 36 dp gradient icon tile with a gloss sheen and the reference's subtle 3D tilt. */
@Composable
fun IconTile(
    icon: ImageVector,
    from: Color,
    to: Color,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    contentDescription: String? = null
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                rotationX = 8f
                rotationY = -6f
                cameraDistance = 12f * density
            }
            .clip(shape)
            .background(Brush.linearGradient(listOf(from, to)))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)), shape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.28f), Color.Transparent, Color.Black.copy(alpha = 0.10f))
                    )
                )
        )
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(size * 0.52f)
        )
    }
}

/** Bottom sheet wrapper: 24 dp top corners, 90 % max height, card surface. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LuxySheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalLuxyColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.onSurface,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

/** Press feedback used by the orb and tiles: scale 0.95 while held. */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.95f
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "press-scale"
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Rounded n-gon helper shared by the orb and by future badges. */
internal fun polygonPath(sides: Int, radius: Float, center: Offset, rotationDeg: Float = 0f): Path {
    val path = Path()
    for (i in 0 until sides) {
        val angle = Math.toRadians((360.0 / sides * i - 90.0 + rotationDeg))
        val x = center.x + radius * kotlin.math.cos(angle).toFloat()
        val y = center.y + radius * kotlin.math.sin(angle).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/** Kept for callers that need a plain circular swatch. */
@Composable
fun SwatchDot(color: Color, size: Dp = 12.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)), CircleShape)
    )
}
