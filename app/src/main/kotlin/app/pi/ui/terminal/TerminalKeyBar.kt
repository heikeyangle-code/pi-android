package app.pi.ui.terminal

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.ui.theme.PiMonoFamily
import com.termux.terminal.KeyHandler

/**
 * 终端修饰键状态：单击进入一次生效（Armed），双击锁定（Locked，连续输入 Ctrl+... 无需反复点按），再点取消。
 */
enum class ModifierState {
    Off,
    Armed,
    Locked,
    ;

    val isActive: Boolean get() = this != Off
}

enum class StickyModifier { Ctrl, Alt }

/**
 * 终端辅助键描述。直接映射到 Android KeyEvent / Termux `KeyHandler` 修饰位，
 * 由原生 `TerminalView` 按当前 `DECCKM` / `modifyOtherKeys` 模式准确编码，绝不硬编码转义串。
 */
data class TerminalBarKey(
    val id: String,
    val label: String,
    val androidKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN,
    val character: Char? = null,
    val longPressChar: Char? = null,
    val longPressLabel: String? = null,
    val sticky: StickyModifier? = null,
    val termuxKeyMod: Int = 0,
    val isPasteAction: Boolean = false,
) {
    fun asLongPress(): TerminalBarKey? = longPressChar?.let { ch ->
        TerminalBarKey(id = "${id}_alt", label = ch.toString(), character = ch)
    }

    companion object {
        val ESC = TerminalBarKey("esc", "ESC", androidKeyCode = KeyEvent.KEYCODE_ESCAPE, longPressChar = '`', longPressLabel = "`")
        val TAB = TerminalBarKey("tab", "TAB", androidKeyCode = KeyEvent.KEYCODE_TAB, longPressChar = '~', longPressLabel = "~")
        val CTRL = TerminalBarKey("ctrl", "CTRL", sticky = StickyModifier.Ctrl)
        val ALT = TerminalBarKey("alt", "ALT", sticky = StickyModifier.Alt)

        val UP = TerminalBarKey("up", "\u2191", androidKeyCode = KeyEvent.KEYCODE_DPAD_UP)
        val DOWN = TerminalBarKey("down", "\u2193", androidKeyCode = KeyEvent.KEYCODE_DPAD_DOWN)
        val LEFT = TerminalBarKey("left", "\u2190", androidKeyCode = KeyEvent.KEYCODE_DPAD_LEFT)
        val RIGHT = TerminalBarKey("right", "\u2192", androidKeyCode = KeyEvent.KEYCODE_DPAD_RIGHT)

        val HOME = TerminalBarKey("home", "HOME", androidKeyCode = KeyEvent.KEYCODE_MOVE_HOME)
        val END = TerminalBarKey("end", "END", androidKeyCode = KeyEvent.KEYCODE_MOVE_END)
        val PGUP = TerminalBarKey("pgup", "PGUP", androidKeyCode = KeyEvent.KEYCODE_PAGE_UP)
        val PGDN = TerminalBarKey("pgdn", "PGDN", androidKeyCode = KeyEvent.KEYCODE_PAGE_DOWN)

        val SLASH = TerminalBarKey("slash", "/", character = '/', longPressChar = '\\', longPressLabel = "\\")
        val DASH = TerminalBarKey("dash", "-", character = '-', longPressChar = '|', longPressLabel = "|")
        val PIPE = TerminalBarKey("pipe", "|", character = '|')
        val TILDE = TerminalBarKey("tilde", "~", character = '~')

        val CTRL_C = TerminalBarKey("ctrl_c", "^C", character = 'c', termuxKeyMod = KeyHandler.KEYMOD_CTRL)
        val CTRL_D = TerminalBarKey("ctrl_d", "^D", character = 'd', termuxKeyMod = KeyHandler.KEYMOD_CTRL)
        val PASTE = TerminalBarKey("paste", "PASTE", isPasteAction = true)

        /** 官方 2×8 满宽均分布局：无需左右滑动，单手拇指覆盖全部高频键，长按支持 `|`, `\`, `~`, `` ` ``。 */
        val defaultRows: List<List<TerminalBarKey>> = listOf(
            listOf(ESC, SLASH, DASH, HOME, UP, END, PGUP, CTRL_C),
            listOf(TAB, CTRL, ALT, LEFT, DOWN, RIGHT, PGDN, PASTE),
        )

        val catalog: List<TerminalBarKey> = listOf(
            ESC, TAB, CTRL, ALT, UP, DOWN, LEFT, RIGHT,
            HOME, END, PGUP, PGDN, SLASH, DASH, PIPE, TILDE, CTRL_C, CTRL_D, PASTE,
        )

        private val indexById: Map<String, TerminalBarKey> = catalog.associateBy { it.id }

        fun byId(id: String): TerminalBarKey? = indexById[id.lowercase()]
    }
}

/**
 * 紧贴软键盘上沿的终端辅助键条：
 * - 两行满宽自适应等分（无需横向滚动找 `^C` 或 `PASTE`）；
 * - 支持双功能键长按（`-`→`|`、`/`→`\`、`TAB`→`~`、`ESC`→`` ` ``），右上角显示微弱副标提示；
 * - `CTRL` / `ALT` 支持单击一次生效、双击锁定，配色跟随 Pi 主题强调色。
 */
@Composable
fun TerminalKeyBar(
    palette: TerminalPalette,
    ctrlState: ModifierState,
    altState: ModifierState,
    onKey: (TerminalBarKey) -> Unit,
    onModifierTap: (StickyModifier) -> Unit,
    onModifierDoubleTap: (StickyModifier) -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
    rows: List<List<TerminalBarKey>> = TerminalBarKey.defaultRows,
) {
    val topHairline = palette.border
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.chromeBg)
            .drawBehind {
                drawLine(
                    color = topHairline,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 0.5.dp.toPx(),
                )
            }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.forEach { key ->
                    val modState = when (key.sticky) {
                        StickyModifier.Ctrl -> ctrlState
                        StickyModifier.Alt -> altState
                        null -> ModifierState.Off
                    }
                    ExtraKeyCap(
                        key = key,
                        palette = palette,
                        modifierState = modState,
                        modifier = Modifier.weight(1f),
                        onTap = {
                            when {
                                key.isPasteAction -> onPaste()
                                key.sticky != null -> onModifierTap(key.sticky)
                                else -> onKey(key)
                            }
                        },
                        onDoubleTap = if (key.sticky != null) {
                            { onModifierDoubleTap(key.sticky) }
                        } else {
                            null
                        },
                        onLongPress = key.asLongPress()?.let { alt -> { onKey(alt) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtraKeyCap(
    key: TerminalBarKey,
    palette: TerminalPalette,
    modifierState: ModifierState,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onDoubleTap: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(7.dp)
    val bg = when (modifierState) {
        ModifierState.Locked -> palette.chipLocked
        ModifierState.Armed -> palette.chipArmed
        ModifierState.Off -> palette.chip
    }
    val borderColor = when (modifierState) {
        ModifierState.Locked -> palette.accent.copy(alpha = 0.85f)
        ModifierState.Armed -> palette.accent.copy(alpha = 0.55f)
        ModifierState.Off -> palette.border
    }
    val textColor = when {
        modifierState.isActive -> palette.accent
        key.id == "ctrl_c" -> ColorFromArgb(palette.ansi16[1])
        else -> palette.foreground.copy(alpha = 0.92f)
    }

    Box(
        modifier = modifier
            .height(34.dp)
            .clip(shape)
            .background(bg, shape)
            .border(
                width = if (modifierState.isActive) 1.dp else 0.5.dp,
                color = borderColor,
                shape = shape,
            )
            .pointerInput(key, onTap, onDoubleTap, onLongPress) {
                detectTapGestures(
                    onTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTap()
                    },
                    onDoubleTap = onDoubleTap?.let { handler ->
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            handler()
                        }
                    },
                    onLongPress = onLongPress?.let { handler ->
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            handler()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val hint = key.longPressLabel
        if (hint != null) {
            Text(
                text = hint,
                color = palette.foreground.copy(alpha = 0.38f),
                fontFamily = PiMonoFamily,
                fontSize = 8.sp,
                lineHeight = 8.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 3.dp),
            )
        }
        Text(
            text = key.label,
            color = textColor,
            fontFamily = PiMonoFamily,
            fontWeight = if (modifierState.isActive) FontWeight.Bold else FontWeight.Medium,
            fontSize = if (key.label.length >= 4) 10.sp else 12.sp,
            maxLines = 1,
        )
    }
}

private fun ColorFromArgb(argb: Int): androidx.compose.ui.graphics.Color =
    androidx.compose.ui.graphics.Color(argb)
