package app.voyahradio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Background = Color(0xFF0F141C)
internal val Panel = Color(0xFF17202D)
internal val Tile = Color(0xFF192230)
internal val TextInk = Color(0xFFEDF2FA)
internal val Muted = Color(0xFFADBACE)
internal val ErrorInk = Color(0xFFFFBAAA)
internal val Warm = Color(0xFFE1C992)

internal data class Roles(val accent: Color, val onAccent: Color, val selected: Color, val selectedEdge: Color, val edge: Color)
private val graphite = Roles(Color(0xFFB5CFFF), Color(0xFF182842), Color(0xFF253752), Color(0xFF506E9C), Color(0xFF2D3A4E))
private val amber = Roles(Color(0xFFF5CF71), Color(0xFF362400), Color(0xFF4F3913), Color(0xFF785E36), Color(0xFF3F3B38))
private val LocalRadioRoles = staticCompositionLocalOf { graphite }
internal val colors: Roles @Composable get() = LocalRadioRoles.current

@Composable
internal fun RadioPalette(warm: Boolean, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalRadioRoles provides if (warm) amber else graphite, content = content)

@Composable
internal fun RadioText(
    value: String, size: Int, modifier: Modifier = Modifier, ink: Color = TextInk, medium: Boolean = false,
    align: TextAlign = TextAlign.Start, lines: Int = Int.MAX_VALUE,
) {
    Text(value, modifier, style = TextStyle(fontSize = size.sp, color = ink, fontFamily = FontFamily.SansSerif,
        fontWeight = if (medium) FontWeight.Medium else FontWeight.Normal, textAlign = align,
        letterSpacing = 0.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)),
        maxLines = lines, overflow = TextOverflow.Ellipsis)
}

internal fun Modifier.paintPanel(fill: Color, edge: Color, radius: Int): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    return clip(shape).background(fill).then(if (edge == Color.Transparent) Modifier else Modifier.border(1.dp, edge, shape))
}

@Composable
internal fun Mark(kind: RadioGlyph.Kind, side: Int, ink: Color, modifier: Modifier = Modifier) {
    val glyph = remember(kind, ink) { RadioGlyph(kind, ink.toArgb()) }
    Canvas(modifier.size(side.dp)) {
        drawIntoCanvas { glyph.draw(it.nativeCanvas, size.width, size.height, side.dp.toPx()) }
    }
}

@Composable
internal fun Tool(
    symbol: RadioGlyph.Kind, label: String, click: () -> Unit, modifier: Modifier = Modifier.size(48.dp),
    glyph: Int = 25, enabled: Boolean = true, fill: Color = Tile, edge: Color = colors.edge, tint: Color = TextInk,
) {
    Box(modifier.alpha(if (enabled) 1f else 0.38f).paintPanel(fill, edge, 15)
        .clickable(role = Role.Button, enabled = enabled, interactionSource = null, indication = ripple(), onClick = click)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Mark(symbol, glyph, tint)
    }
}
