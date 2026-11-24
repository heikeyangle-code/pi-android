package app.pi.ui.components

import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.contrastOn
import kotlin.math.roundToInt

/**
 * Canonical Studio UI primitives — flat tonal geometry, 10dp buttons, 0.5dp hairlines,
 * theme-contrast ink, and haptic feedback. Replaces all stock Material 3 buttons & sliders.
 */

private val STUDIO_BUTTON_SHAPE = RoundedCornerShape(10.dp)
private val STUDIO_BUTTON_HEIGHT = 40.dp
private val STUDIO_BUTTON_HEIGHT_COMPACT = 34.dp

@Composable
fun PiPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: Color? = null,
    compact: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val palette = PiTheme.palette
    val haptic = LocalHapticFeedback.current
    val fill = tone ?: palette.accent
    val fg = palette.contrastOn(fill)
    Row(
        modifier = modifier
            .height(if (compact) STUDIO_BUTTON_HEIGHT_COMPACT else STUDIO_BUTTON_HEIGHT)
            .alpha(if (enabled) 1f else 0.42f)
            .clip(STUDIO_BUTTON_SHAPE)
            .background(fill)
            .clickable(enabled = enabled, role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = if (compact) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(16.dp), tint = fg)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PiSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: Color? = null,
    compact: Boolean = false,
    leadingIcon: ImageVector? = null,
    containerColor: Color = PiSheetChromeMetrics.sheetControlBg,
) {
    val palette = PiTheme.palette
    val haptic = LocalHapticFeedback.current
    val fg = tone ?: MaterialTheme.colorScheme.onSurface
    val borderColor = if (tone != null) tone.copy(alpha = 0.42f) else palette.borderMuted.copy(alpha = 0.65f)
    Row(
        modifier = modifier
            .height(if (compact) STUDIO_BUTTON_HEIGHT_COMPACT else STUDIO_BUTTON_HEIGHT)
            .alpha(if (enabled) 1f else 0.42f)
            .clip(STUDIO_BUTTON_SHAPE)
            .background(containerColor)
            .border(PiSpacing.hairlineThin, borderColor, STUDIO_BUTTON_SHAPE)
            .clickable(enabled = enabled, role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = if (compact) 12.dp else 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(16.dp), tint = fg)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PiDestructiveButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
) = PiPrimaryButton(
    label = label,
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    tone = PiTheme.palette.error,
    compact = compact,
)

@Composable
fun PiTextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: Color? = null,
    leadingIcon: ImageVector? = null,
) {
    val fg = tone ?: PiTheme.palette.accent
    Row(
        modifier = modifier
            .height(36.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(15.dp), tint = fg)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PiIconSquareButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    containerColor: Color = Color.Transparent,
    borderColor: Color? = null,
    size: Dp = 36.dp,
    iconSize: Dp = 18.dp,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.42f)
            .clip(shape)
            .background(containerColor)
            .then(
                if (borderColor != null) Modifier.border(PiSpacing.hairlineThin, borderColor, shape)
                else Modifier,
            )
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = tint,
        )
    }
}

@Composable
fun PiSheetActionBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Single canonical Studio switch (`38x22dp`, spring-animated knob, subtle haptic).
 */
@Composable
fun PiSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val accent = PiTheme.palette.accent
    val track by animateColorAsState(
        targetValue = if (checked) accent else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = tween(160),
        label = "piSwitchTrack",
    )
    val border by animateColorAsState(
        targetValue = if (checked) accent else PiTheme.palette.borderMuted.copy(alpha = 0.72f),
        animationSpec = tween(160),
        label = "piSwitchBorder",
    )
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 18.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "piSwitchKnob",
    )
    val knobColor = if (checked) PiTheme.palette.contrastOn(accent) else PiTheme.palette.muted
    val interactive = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = { next ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(next)
            },
        )
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .size(width = 38.dp, height = 22.dp)
            .clip(CircleShape)
            .background(track)
            .border(PiSpacing.hairlineThin, border, CircleShape)
            .then(interactive)
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = knobOffset)
                .size(16.dp)
                .clip(CircleShape)
                .background(knobColor),
        )
    }
}

/**
 * Bespoke Studio slim-track slider (zero Material 3 `Slider`).
 * Supports both linear value tracks (`trackBrush == null`) and full-spectrum gradient tracks
 * (`trackBrush != null`, used in `PiSystemPaletteSheet`).
 */
@Composable
fun PiSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    trackHeight: Dp = 4.dp,
    thumbRadius: Dp = 9.dp,
    trackBrush: Brush? = null,
    activeColor: Color = PiTheme.palette.accent,
    thumbFillColor: Color? = null,
) {
    val palette = PiTheme.palette
    val isDark = PiTheme.isDark
    val density = LocalDensity.current
    val thumbPx = with(density) { thumbRadius.toPx() }
    val trackHeightPx = with(density) { trackHeight.toPx() }
    val start = valueRange.start
    val end = valueRange.endInclusive
    val span = (end - start).coerceAtLeast(1e-6f)
    val fraction = ((value - start) / span).coerceIn(0f, 1f)
    var widthPx by remember { mutableFloatStateOf(0f) }

    val latestOnChange by rememberUpdatedState(onValueChange)
    val latestEnabled by rememberUpdatedState(enabled)

    fun mapOffsetToValue(x: Float): Float {
        val usable = (widthPx - thumbPx * 2f).coerceAtLeast(1f)
        val rawFrac = ((x - thumbPx) / usable).coerceIn(0f, 1f)
        val snappedFrac = if (steps > 0) {
            val segments = steps + 1
            (rawFrac * segments).roundToInt().toFloat() / segments.toFloat()
        } else {
            rawFrac
        }
        return (start + snappedFrac * span).coerceIn(start, end)
    }

    val inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.55f else 0.45f)
    val resolvedThumbFill = thumbFillColor ?: if (trackBrush != null) Color.White else MaterialTheme.colorScheme.onSurface
    val thumbRingColor = if (trackBrush != null) {
        if (isDark) Color.Black.copy(alpha = 0.65f) else Color.Black.copy(alpha = 0.35f)
    } else {
        palette.pageBg
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(start, end, steps) {
                detectTapGestures { offset ->
                    if (latestEnabled) latestOnChange(mapOffsetToValue(offset.x))
                }
            }
            .pointerInput(start, end, steps) {
                detectHorizontalDragGestures { change, _ ->
                    if (!latestEnabled) return@detectHorizontalDragGestures
                    change.consume()
                    latestOnChange(mapOffsetToValue(change.position.x))
                }
            },
    ) {
        val cy = size.height / 2f
        val left = thumbPx
        val right = (size.width - thumbPx).coerceAtLeast(left)
        val trackW = (right - left).coerceAtLeast(1f)
        val corner = CornerRadius(trackHeightPx / 2f, trackHeightPx / 2f)
        val top = cy - trackHeightPx / 2f

        if (trackBrush != null) {
            drawRoundRect(
                brush = trackBrush,
                topLeft = Offset(left, top),
                size = Size(trackW, trackHeightPx),
                cornerRadius = corner,
            )
            drawRoundRect(
                color = palette.borderMuted.copy(alpha = 0.45f),
                topLeft = Offset(left, top),
                size = Size(trackW, trackHeightPx),
                cornerRadius = corner,
                style = Stroke(width = 0.5.dp.toPx()),
            )
        } else {
            drawRoundRect(
                color = inactiveTrackColor,
                topLeft = Offset(left, top),
                size = Size(trackW, trackHeightPx),
                cornerRadius = corner,
            )
            val activeW = (trackW * fraction).coerceAtLeast(0f)
            if (activeW > 0f) {
                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset(left, top),
                    size = Size(activeW, trackHeightPx),
                    cornerRadius = corner,
                )
            }
        }

        val cx = left + trackW * fraction
        drawCircle(
            color = resolvedThumbFill,
            radius = thumbPx,
            center = Offset(cx, cy),
        )
        drawCircle(
            color = thumbRingColor,
            radius = thumbPx,
            center = Offset(cx, cy),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

/** Canonical 0.5dp tonal hairline divider. */
@Composable
fun PiDivider(
    modifier: Modifier = Modifier,
    startInset: Dp = 0.dp,
) {
    HorizontalDivider(
        modifier = modifier.padding(start = startInset),
        thickness = PiSpacing.hairlineThin,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * Synchronously clears Android's platform dialog dim before the first frame so custom scrims
 * never double-stack or flash darker on frame 0.
 */
@Composable
fun ClearDialogPlatformDim() {
    val view = LocalView.current
    remember(view) {
        (view.parent as? DialogWindowProvider)?.window?.let { w ->
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0f)
        }
        true
    }
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { w ->
            if ((w.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0 || w.attributes.dimAmount != 0f) {
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                w.setDimAmount(0f)
            }
        }
    }
}
