package app.pi.ui.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import app.pi.ui.theme.PiPalette
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import kotlin.math.pow

/**
 * 终端色彩方案：直接跟随 Pi 当前主题（含自定义 `.pi/agent/themes/` 主题文件与 Material You），
 * 并提供高对比、符合语义的 16 色 ANSI 调色板（红/绿/黄/蓝/品红/青始终保持可读性与语义准确）。
 */
data class TerminalPalette(
    val foreground: Color,
    val background: Color,
    val cursor: Color,
    val accent: Color,
    val isDark: Boolean,
    val ansi16: IntArray,
) {
    /** 顶栏与辅助键条的微抬升表面色。 */
    val chromeBg: Color
        get() = if (isDark) lerp(background, Color.White, 0.045f) else lerp(background, Color.Black, 0.03f)

    /** 辅助键默认底色。 */
    val chip: Color
        get() = foreground.copy(alpha = if (isDark) 0.09f else 0.07f)

    /** 辅助键一次激活（armed）底色。 */
    val chipArmed: Color
        get() = accent.copy(alpha = if (isDark) 0.30f else 0.22f)

    /** 辅助键锁定（locked）底色。 */
    val chipLocked: Color
        get() = accent.copy(alpha = if (isDark) 0.48f else 0.34f)

    /** 极细发丝线边框色。 */
    val border: Color
        get() = foreground.copy(alpha = if (isDark) 0.12f else 0.14f)

    /** 把前景色、背景色、光标色与 16 色 ANSI 表同步到 Termux 引擎与当前会话。 */
    fun applyTo(emulator: TerminalEmulator?) {
        val fg = foreground.toArgb()
        val bg = background.toArgb()
        val cur = cursor.toArgb()

        val defaults = TerminalColors.COLOR_SCHEME.mDefaultColors
        for (i in 0 until 16) defaults[i] = ansi16[i]
        defaults[TextStyle.COLOR_INDEX_FOREGROUND] = fg
        defaults[TextStyle.COLOR_INDEX_BACKGROUND] = bg
        defaults[TextStyle.COLOR_INDEX_CURSOR] = cur

        val current = emulator?.mColors?.mCurrentColors ?: return
        for (i in 0 until 16) current[i] = ansi16[i]
        current[TextStyle.COLOR_INDEX_FOREGROUND] = fg
        current[TextStyle.COLOR_INDEX_BACKGROUND] = bg
        current[TextStyle.COLOR_INDEX_CURSOR] = cur
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TerminalPalette) return false
        return foreground == other.foreground &&
            background == other.background &&
            cursor == other.cursor &&
            accent == other.accent &&
            isDark == other.isDark &&
            ansi16.contentEquals(other.ansi16)
    }

    override fun hashCode(): Int {
        var result = foreground.hashCode()
        result = 31 * result + background.hashCode()
        result = 31 * result + cursor.hashCode()
        result = 31 * result + accent.hashCode()
        result = 31 * result + isDark.hashCode()
        result = 31 * result + ansi16.contentHashCode()
        return result
    }

    companion object {
        private const val DARK_PAGE_DIM = 0.28f

        /**
         * Derive a readable, theme-faithful 16-color ANSI table directly from [palette]'s
         * semantic tokens so custom themes (Dracula / Catppuccin / Solarized / Nord / Light)
         * style the built-in terminal cohesively while preserving contrast on [bg].
         */
        private fun deriveAnsi16(palette: PiPalette, bg: Color, dark: Boolean): IntArray {
            fun readable(color: Color): Color =
                app.pi.ui.theme.PiContrast.ensure(color, bg, 3.6)

            fun bright(color: Color): Color = if (dark) {
                lerp(color, Color.White, 0.22f)
            } else {
                lerp(color, Color.Black, 0.18f)
            }

            val black = if (dark) lerp(bg, Color.White, 0.08f) else lerp(palette.text, bg, 0.10f)
            val red = readable(palette.error)
            val green = readable(palette.success)
            val yellow = readable(palette.warning)
            val blue = readable(palette.mdLink)
            val magenta = readable(palette.thinkingHigh)
            val cyan = readable(palette.syntaxVariable)
            val white = if (dark) lerp(palette.text, bg, 0.12f) else readable(palette.muted)

            val brightBlack = readable(palette.dim)
            val brightRed = bright(red)
            val brightGreen = bright(green)
            val brightYellow = bright(yellow)
            val brightBlue = bright(readable(palette.accent))
            val brightMagenta = bright(readable(palette.thinkingXhigh))
            val brightCyan = bright(cyan)
            val brightWhite = if (dark) lerp(palette.text, Color.White, 0.25f) else palette.text

            return intArrayOf(
                black.toArgb(),         // 0 Black
                red.toArgb(),           // 1 Red
                green.toArgb(),         // 2 Green
                yellow.toArgb(),        // 3 Yellow
                blue.toArgb(),          // 4 Blue
                magenta.toArgb(),       // 5 Magenta
                cyan.toArgb(),          // 6 Cyan
                white.toArgb(),         // 7 White
                brightBlack.toArgb(),   // 8 Bright Black
                brightRed.toArgb(),     // 9 Bright Red
                brightGreen.toArgb(),   // 10 Bright Green
                brightYellow.toArgb(),  // 11 Bright Yellow
                brightBlue.toArgb(),    // 12 Bright Blue
                brightMagenta.toArgb(), // 13 Bright Magenta
                brightCyan.toArgb(),    // 14 Bright Cyan
                brightWhite.toArgb(),   // 15 Bright White
            )
        }

        fun of(palette: PiPalette): TerminalPalette {
            val dark = palette.pageBg.isDarkSurface()
            val bg = if (dark) lerp(palette.pageBg, Color.Black, DARK_PAGE_DIM) else palette.pageBg
            return TerminalPalette(
                foreground = palette.text,
                background = bg,
                cursor = palette.accent,
                accent = palette.accent,
                isDark = dark,
                ansi16 = deriveAnsi16(palette, bg, dark),
            )
        }
    }
}

private fun Color.isDarkSurface(): Boolean {
    fun linear(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    val luminance = 0.2126f * linear(red) + 0.7152f * linear(green) + 0.0722f * linear(blue)
    return luminance <= 0.5f
}
