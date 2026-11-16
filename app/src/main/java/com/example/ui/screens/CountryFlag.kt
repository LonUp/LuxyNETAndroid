package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Small drawn flags. Unknown codes draw nothing rather than a broken glyph or letter tile. */
object FlagArt {
    fun canDraw(code: String): Boolean = code.uppercase() in drawn

    private val drawn = setOf(
        "IR", "DE", "FR", "IT", "BE", "NL", "RU", "UA", "PL", "AT", "ES", "HU", "BG", "AM",
        "IE", "RO", "US", "GB", "AE", "TR", "JP", "KR", "CN", "SG", "CA", "AU", "SE", "FI",
        "NO", "DK", "CH", "IN", "BR"
    )
}

@Composable
fun HomeCountryFlag(
    code: String,
    modifier: Modifier = Modifier,
    width: Dp = 18.dp,
    height: Dp = 12.dp
) {
    if (!FlagArt.canDraw(code)) return
    Canvas(
        modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(2.dp))
    ) {
        drawFlag(code.uppercase())
    }
}

private fun DrawScope.drawFlag(code: String) {
    when (code) {
        "IR" -> bands(true, 0xFF239F40, 0xFFFFFFFF, 0xFFDA0000)
        "DE" -> bands(true, 0xFF000000, 0xFFDD0000, 0xFFFFCC00)
        "RU" -> bands(true, 0xFFFFFFFF, 0xFF0039A6, 0xFFD52B1E)
        "NL" -> bands(true, 0xFFAE1C28, 0xFFFFFFFF, 0xFF21468B)
        "UA" -> bands(true, 0xFF005BBB, 0xFFFFD500)
        "PL" -> bands(true, 0xFFFFFFFF, 0xFFDC143C)
        "AT" -> bands(true, 0xFFED2939, 0xFFFFFFFF, 0xFFED2939)
        "HU" -> bands(true, 0xFFCE2939, 0xFFFFFFFF, 0xFF477050)
        "BG" -> bands(true, 0xFFFFFFFF, 0xFF00966E, 0xFFD62612)
        "AM" -> bands(true, 0xFFD90012, 0xFF0033A0, 0xFFF2A800)
        "ES" -> bands(true, 0xFFAA151B, 0xFFF1BF00, 0xFFAA151B, weights = listOf(1f, 2f, 1f))
        "FR" -> bands(false, 0xFF0055A4, 0xFFFFFFFF, 0xFFEF4135)
        "IT" -> bands(false, 0xFF009246, 0xFFFFFFFF, 0xFFCE2B37)
        "BE" -> bands(false, 0xFF000000, 0xFFFAE042, 0xFFED2939)
        "IE" -> bands(false, 0xFF169B62, 0xFFFFFFFF, 0xFFFF883E)
        "RO" -> bands(false, 0xFF002B7F, 0xFFFCD116, 0xFFCE1126)
        "AE" -> {
            bands(true, 0xFF00732F, 0xFFFFFFFF, 0xFF000000)
            drawRect(Color(0xFFFF0000), size = Size(size.width * 0.28f, size.height))
        }
        "US" -> drawUnitedStates()
        "GB" -> drawUnion()
        "JP" -> {
            drawRect(Color.White)
            drawCircle(Color(0xFFBC002D), radius = size.minDimension * 0.28f, center = center)
        }
        "KR" -> {
            drawRect(Color.White)
            drawCircle(Color(0xFFCD2E3A), radius = size.minDimension * 0.28f, center = center)
            drawCircle(Color(0xFF0047A0), radius = size.minDimension * 0.16f, center = Offset(center.x + size.minDimension * 0.08f, center.y))
        }
        "CN" -> {
            drawRect(Color(0xFFDE2910))
            drawCircle(Color(0xFFFFDE00), radius = size.minDimension * 0.16f, center = Offset(size.width * 0.22f, size.height * 0.32f))
        }
        "SG" -> {
            drawRect(Color(0xFFEF3340), size = Size(size.width, size.height / 2f))
            drawRect(Color.White, topLeft = Offset(0f, size.height / 2f), size = Size(size.width, size.height / 2f))
        }
        "CA" -> {
            bands(false, 0xFFFF0000, 0xFFFFFFFF, 0xFFFF0000)
        }
        "AU" -> {
            drawRect(Color(0xFF012169))
            drawCircle(Color.White, radius = size.minDimension * 0.08f, center = Offset(size.width * 0.72f, size.height * 0.62f))
        }
        "CH" -> {
            drawRect(Color(0xFFDA020E))
            val t = size.minDimension * 0.18f
            drawRect(Color.White, topLeft = Offset(center.x - t / 2f, center.y - t * 1.6f), size = Size(t, t * 3.2f))
            drawRect(Color.White, topLeft = Offset(center.x - t * 1.6f, center.y - t / 2f), size = Size(t * 3.2f, t))
        }
        "IN" -> {
            bands(true, 0xFFFF9933, 0xFFFFFFFF, 0xFF138808)
            drawCircle(Color(0xFF000080), radius = size.minDimension * 0.12f, center = center)
        }
        "BR" -> {
            drawRect(Color(0xFF009C3B))
            drawCircle(Color(0xFFFFDF00), radius = size.minDimension * 0.32f, center = center)
            drawCircle(Color(0xFF002776), radius = size.minDimension * 0.16f, center = center)
        }
        "TR" -> {
            drawRect(Color(0xFFE30A17))
            drawCircle(Color.White, radius = size.minDimension * 0.28f, center = Offset(center.x - size.width * 0.06f, center.y))
            drawCircle(Color(0xFFE30A17), radius = size.minDimension * 0.22f, center = Offset(center.x, center.y))
        }
        "SE" -> nordic(0xFF006AA7, 0xFFFECC00)
        "FI" -> nordic(0xFFFFFFFF, 0xFF003580)
        "NO" -> {
            nordic(0xFFBA0C2F, 0xFFFFFFFF)
            nordicCross(0xFF00205B, 0.06f)
        }
        "DK" -> nordic(0xFFC60C30, 0xFFFFFFFF)
        else -> drawRect(Color(0xFF2C2824))
    }
}

private fun DrawScope.bands(horizontal: Boolean, vararg colors: Long, weights: List<Float> = emptyList()) {
    val parts = if (weights.size == colors.size) weights else List(colors.size) { 1f }
    val total = parts.sum()
    var cursor = 0f
    colors.forEachIndexed { index, color ->
        val share = parts[index] / total
        if (horizontal) {
            val h = size.height * share
            drawRect(Color(color), topLeft = Offset(0f, cursor), size = Size(size.width, h))
            cursor += h
        } else {
            val w = size.width * share
            drawRect(Color(color), topLeft = Offset(cursor, 0f), size = Size(w, size.height))
            cursor += w
        }
    }
}

private fun DrawScope.nordic(field: Long, cross: Long) {
    drawRect(Color(field))
    nordicCross(cross, 0.16f)
}

private fun DrawScope.nordicCross(color: Long, thickness: Float) {
    val t = size.minDimension * thickness
    val x = size.width * 0.32f
    drawRect(Color(color), topLeft = Offset(x - t / 2f, 0f), size = Size(t, size.height))
    drawRect(Color(color), topLeft = Offset(0f, center.y - t / 2f), size = Size(size.width, t))
}

private fun DrawScope.drawUnitedStates() {
    drawRect(Color(0xFFBF0A30))
    val stripe = size.height / 13f
    for (i in 0 until 13 step 2) {
        drawRect(Color.White, topLeft = Offset(0f, i * stripe), size = Size(size.width, stripe))
    }
    drawRect(Color(0xFF002868), size = Size(size.width * 0.4f, stripe * 7f))
}

private fun DrawScope.drawUnion() {
    drawRect(Color(0xFF012169))
    val t = size.minDimension * 0.16f
    drawRect(Color.White, topLeft = Offset(0f, center.y - t), size = Size(size.width, t * 2f))
    drawRect(Color.White, topLeft = Offset(center.x * 0.7f - t, 0f), size = Size(t * 2f, size.height))
    drawRect(Color(0xFFC8102E), topLeft = Offset(0f, center.y - t / 2f), size = Size(size.width, t))
    drawRect(Color(0xFFC8102E), topLeft = Offset(center.x * 0.7f - t / 2f, 0f), size = Size(t, size.height))

}
