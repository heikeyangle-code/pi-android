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

        // 高质感暗色 16 色 ANSI（柔和护眼同时在深底上具备 AA+ 对比度）
        private val DARK_ANSI_16 = intArrayOf(
            0xFF1C1E26.toInt(), // 0 Black
            0xFFF3707A.toInt(), // 1 Red
            0xFF7AD696.toInt(), // 2 Green
            0xFFF2C97D.toInt(), // 3 Yellow
            0xFF72A7F8.toInt(), // 4 Blue
            0xFFC792EA.toInt(), // 5 Magenta
            0xFF6CD4E0.toInt(), // 6 Cyan
            0xFFD4D8E2.toInt(), // 7 White
            0xFF5C6378.toInt(), // 8 Bright Black
            0xFFFF8F98.toInt(), // 9 Bright Red
            0xFF9AE8B1.toInt(), // 10 Bright Green
            0xFFFFDF9B.toInt(), // 11 Bright Yellow
            0xFF94BEFF.toInt(), // 12 Bright Blue
            0xFFDBB2F8.toInt(), // 13 Bright Magenta
            0xFF93E6F0.toInt(), // 14 Bright Cyan
            0xFFF6F8FC.toInt(), // 15 Bright White
        )

        // 高质感浅色 16 色 ANSI（白/纸底上清晰利落）
        private val LIGHT_ANSI_16 = intArrayOf(
            0xFF23262F.toInt(), // 0 Black
            0xFFC93441.toInt(), // 1 Red
            0xFF1E7E45.toInt(), // 2 Green
            0xFFA66B00.toInt(), // 3 Yellow
            0xFF1D62C6.toInt(), // 4 Blue
            0xFF8C3BB8.toInt(), // 5 Magenta
            0xFF0E7A8C.toInt(), // 6 Cyan
            0xFF6B7280.toInt(), // 7 White
            0xFF5A6270.toInt(), // 8 Bright Black
            0xFFDF4452.toInt(), // 9 Bright Red
            0xFF259954.toInt(), // 10 Bright Green
            0xFFC48200.toInt(), // 11 Bright Yellow
            0xFF2B75E6.toInt(), // 12 Bright Blue
            0xFFA64FD4.toInt(), // 13 Bright Magenta
            0xFF1493A8.toInt(), // 14 Bright Cyan
            0xFF111318.toInt(), // 15 Bright White
        )

        fun of(palette: PiPalette): TerminalPalette {
            val dark = palette.pageBg.isDarkSurface()
            val bg = if (dark) lerp(palette.pageBg, Color.Black, DARK_PAGE_DIM) else palette.pageBg
            return TerminalPalette(
                foreground = palette.text,
                background = bg,
                cursor = palette.accent,
                accent = palette.accent,
                isDark = dark,
                ansi16 = if (dark) DARK_ANSI_16 else LIGHT_ANSI_16,
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
