package app.voyahradio.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material.ripple.createRippleModifierNode
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.math.roundToInt

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

// The screens repeat the pixel rules of the View layouts they replaced, so that both render the same pixels:
// dp sizes round the way View code rounded them, and centring floors the leftover pixel instead of rounding it.

/** A dp size in pixels, rounded like `Math.round(dp * density)` in View code. */
internal fun Density.px(dp: Int): Int = (dp * density).roundToInt()

/** A dp size as the [Dp] of its rounded pixels, so a layout modifier lands on the same pixel as a View. */
@Composable
internal fun viewDp(dp: Int): Dp = with(LocalDensity.current) { px(dp).toDp() }

/** FrameLayout, LinearLayout and TextView centring, which divides the leftover space in integers. */
internal object ViewGravity {
    val Center = Alignment { size, space, _ ->
        IntOffset((space.width - size.width) / 2, (space.height - size.height) / 2)
    }
    val CenterVertically = Alignment.Vertical { size, space -> (space - size) / 2 }
    val CenterHorizontally = Alignment.Horizontal { size, space, _ -> (space - size) / 2 }

    /** LinearLayout gravity CENTER along its orientation. */
    val Centered = object : Arrangement.HorizontalOrVertical {
        override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) =
            centre(totalSize, sizes, outPositions)

        override fun Density.arrange(
            totalSize: Int, sizes: IntArray, layoutDirection: LayoutDirection, outPositions: IntArray,
        ) = centre(totalSize, sizes, outPositions)

        private fun centre(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
            var position = (totalSize - sizes.sum()) / 2
            sizes.forEachIndexed { index, size ->
                outPositions[index] = position
                position += size
            }
        }
    }
}

/**
 * A horizontal LinearLayout whose children all have width 0 and weight 1: each child takes the integer share
 * `remaining / weights left`, so the last child gets the leftover pixels. [gap] is the left margin of every child
 * after the first; [centred] is gravity CENTER_VERTICAL.
 */
@Composable
internal fun WeightRow(modifier: Modifier = Modifier, gap: Int = 0, centred: Boolean = true, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gapPx = px(gap)
        val width = constraints.maxWidth
        var excess = width - gapPx * (measurables.size - 1).coerceAtLeast(0)
        var weights = measurables.size.toFloat()
        val placeables = measurables.map { child ->
            val share = (excess / weights).toInt().coerceAtLeast(0)
            excess -= share
            weights -= 1f
            child.measure(Constraints(share, share, 0, constraints.maxHeight))
        }
        val height = maxOf(placeables.maxOfOrNull { it.height } ?: 0, constraints.minHeight)
        layout(width, height) {
            var x = 0
            placeables.forEach { child ->
                child.place(x, if (centred) (height - child.height) / 2 else 0)
                x += child.width + gapPx
            }
        }
    }
}

/**
 * A GradientDrawable rectangle: corner radius `dp(radius)` and a `dp(1)` stroke drawn on the bounds inset by half
 * the stroke, with the fill drawn in the same inset rectangle.
 */
internal fun Modifier.panel(fill: Color, edge: Color, radius: Int): Modifier = drawBehind { drawPanel(fill, edge, radius) }

internal fun DrawScope.drawPanel(fill: Color, edge: Color, radius: Int) {
    val stroke = if (edge == Color.Transparent) 0f else px(1).toFloat()
    val inset = stroke / 2
    val width = size.width - stroke
    val height = size.height - stroke
    val corner = min(px(radius).toFloat(), min(width, height) * .5f)
    if (fill != Color.Transparent) drawRoundRect(fill, Offset(inset, inset), Size(width, height), CornerRadius(corner))
    if (stroke > 0) drawRoundRect(edge, Offset(inset, inset), Size(width, height), CornerRadius(corner), style = Stroke(stroke))
}

/** A RadioGlyph view: the glyph of [side] dp is centred in the bounds without rounding its position. */
internal fun Modifier.glyph(kind: RadioGlyph.Kind, side: Int, ink: Color): Modifier = drawBehind {
    val glyph = RadioGlyph(kind, ink.toArgb())
    drawIntoCanvas { glyph.draw(it.nativeCanvas, size.width, size.height, side * density) }
}

/** The RippleDrawable colours the View screens used for their touch feedback. */
internal class ViewRipple(private val color: Color, private val alpha: RippleAlpha) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        createRippleModifierNode(interactionSource, true, Dp.Unspecified, { color }, { alpha })

    override fun equals(other: Any?) = other is ViewRipple && other.color == color && other.alpha == alpha
    override fun hashCode() = 31 * color.hashCode() + alpha.hashCode()

    companion object {
        private fun alpha(value: Int) = value / 255f

        /** Home screen feedback on an accent fill: on-accent at 0x55 focused, 0x40 pressed, 0x33 otherwise. */
        fun onAccent(roles: Roles) = ViewRipple(roles.onAccent, RippleAlpha(alpha(0x40), alpha(0x55), alpha(0x33), alpha(0x40)))

        /** Home screen feedback on any other fill: accent at 0x40 focused, 0x44 pressed, 0x22 otherwise. */
        fun accent(roles: Roles) = ViewRipple(roles.accent, RippleAlpha(alpha(0x44), alpha(0x40), alpha(0x22), alpha(0x44)))

        /** Settings feedback: accent at 0x44 in every state. */
        fun settings(roles: Roles) = ViewRipple(roles.accent, RippleAlpha(alpha(0x44), alpha(0x44), alpha(0x44), alpha(0x44)))
    }
}

/**
 * A clickable View with a RippleDrawable background: the ripple is masked by a `dp(radius)` rounded rectangle and
 * drawn under the content, like a background. Disabled controls fade to [disabledAlpha] like `setAlpha`.
 */
@Composable
internal fun Pressable(
    click: () -> Unit, radius: Int, ripple: ViewRipple, modifier: Modifier = Modifier, enabled: Boolean = true,
    description: String? = null, role: Role = Role.Button, disabledAlpha: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val mask = RoundedCornerShape(viewDp(radius))
    Box(modifier.alpha(if (enabled) 1f else disabledAlpha)
        .clickable(source, null, enabled = enabled, role = role, onClick = click)
        .then(if (description == null) Modifier else Modifier.semantics { contentDescription = description }),
        propagateMinConstraints = true) {
        Box(Modifier.matchParentSize().clip(mask).indication(source, ripple))
        content()
    }
}

/** An icon button: a RadioGlyph view filling a clickable frame. */
@Composable
internal fun GlyphButton(
    symbol: RadioGlyph.Kind, label: String, click: () -> Unit, modifier: Modifier, radius: Int, ripple: ViewRipple,
    glyph: Int = 25, enabled: Boolean = true, disabledAlpha: Float = 1f, tint: Color = TextInk,
) {
    Pressable(click, radius, ripple, modifier, enabled, label, disabledAlpha = disabledAlpha) {
        Box(Modifier.glyph(symbol, glyph, tint))
    }
}

/**
 * A TextView with `setIncludeFontPadding(false)` unless [fontPadding]; [align] is its horizontal gravity, which
 * centres each line inside the view's own width.
 */
@Composable
internal fun RadioText(
    value: String, size: Int, modifier: Modifier = Modifier, ink: Color = TextInk, medium: Boolean = false,
    align: TextAlign = TextAlign.Start, lines: Int = Int.MAX_VALUE, fontPadding: Boolean = false,
) {
    Text(value, modifier, style = radioTextStyle(size, ink, medium, align, fontPadding), maxLines = lines,
        overflow = TextOverflow.Ellipsis)
}

internal fun radioTextStyle(size: Int, ink: Color, medium: Boolean = false, align: TextAlign = TextAlign.Start,
                            fontPadding: Boolean = false) =
    TextStyle(fontSize = size.sp, color = ink, fontFamily = FontFamily.SansSerif,
        fontWeight = if (medium) FontWeight.Medium else FontWeight.Normal, textAlign = align, letterSpacing = 0.sp,
        lineBreak = TextViewLineBreak, platformStyle = PlatformTextStyle(includeFontPadding = fontPadding))

/** TextView breaks lines with the high quality strategy and no hyphenation. */
private val TextViewLineBreak = LineBreak(LineBreak.Strategy.HighQuality, LineBreak.Strictness.Default, LineBreak.WordBreak.Default)
