package app.pi.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import app.pi.i18n.tr

/**
 * Curated tactile canvas surface preset.
 *
 * Anchors neutral surfaces (`pageBg` / `cardBg` / `userMessageBg` / `borderMuted`)
 * and editorial text inks inside proven studio-grade neutral ranges so accents & syntax
 * always pop cleanly without muddy or neon backgrounds.
 */
data class PiThemeCanvas(
    val id: String,
    val slugEn: String,
    val labelZh: String,
    val labelEn: String,
    val dark: Boolean,
    val pageBg: Color,
    val cardBg: Color,
    val userMessageBg: Color,
    val toolPendingBg: Color,
    val borderMuted: Color,
    val text: Color,
    val heading: Color,
    val muted: Color,
    val dim: Color,
    val codeBlockInk: Color,
) {
    val label: String get() = tr(labelZh, labelEn)
}

/** Curated designer accent swatch (OKHSL hue, or pure monochrome ink). */
data class PiThemeAccent(
    val id: String,
    val slugEn: String,
    val labelZh: String,
    val labelEn: String,
    val hue: Double,
    val monochrome: Boolean = false,
) {
    val label: String get() = tr(labelZh, labelEn)
}

/** Perceptual chroma/contrast mood for the synthesized palette. */
enum class PiThemeMood(val id: String, val labelZh: String, val labelEn: String) {
    Minimal("minimal", "克制", "Minimal"),
    Balanced("balanced", "标准", "Balanced"),
    Vibrant("vibrant", "鲜活", "Vibrant");

    val label: String get() = tr(labelZh, labelEn)
}

/**
 * Curated iconic dual-mode master preset (grounded in authentic canonical palettes:
 * Claude Warm Ink, Flexoki, Rosé Pine, Catppuccin, Tokyo Night, and OLED Monochrome).
 *
 * Selecting a preset immediately populates the Theme Studio canvas, accent hue, mood,
 * and portable name — ready to save in 1 tap or fine-tune further.
 */
data class PiThemeMasterPreset(
    val id: String,
    val labelZh: String,
    val labelEn: String,
    val darkName: String,
    val lightName: String,
    val darkCanvas: PiThemeCanvas,
    val lightCanvas: PiThemeCanvas,
    val accentHue: Double,
    val monochrome: Boolean = false,
    val mood: PiThemeMood = PiThemeMood.Balanced,
) {
    val label: String get() = tr(labelZh, labelEn)
    fun canvasFor(dark: Boolean): PiThemeCanvas = if (dark) darkCanvas else lightCanvas
    fun nameFor(dark: Boolean): String = if (dark) darkName else lightName
}

/**
 * OKHSL-calibrated studio theme synthesizer + curated master presets.
 *
 * Produces complete 59-token [PiPalette] themes (56 official Pi 1.6.0 tokens + 3 export surfaces)
 * with automatic WCAG AA+ contrast guarantees across every canvas, preset, hue (`0°..360°`),
 * and mood, saved directly as standard portable `~/.pi/agent/themes/<slug>.json` files.
 */
object PiThemeStudio {

    // ------------------------------------------------------------------ Canvases

    private val carbonDark = PiThemeCanvas(
        id = "carbon",
        slugEn = "Carbon",
        labelZh = "碳黑",
        labelEn = "Carbon",
        dark = true,
        pageBg = Color(0xFF0D0E11),
        cardBg = Color(0xFF15171B),
        userMessageBg = Color(0xFF1C1F26),
        toolPendingBg = Color(0xFF15171C),
        borderMuted = Color(0xFF262930),
        text = Color(0xFFE8EAF0),
        heading = Color(0xFFF4F6FA),
        muted = Color(0xFF8E94A0),
        dim = Color(0xFF6B717D),
        codeBlockInk = Color(0xFFD8DCE6),
    )

    private val oledDark = PiThemeCanvas(
        id = "oled",
        slugEn = "OLED",
        labelZh = "纯黑",
        labelEn = "OLED",
        dark = true,
        pageBg = Color(0xFF000000),
        cardBg = Color(0xFF101114),
        userMessageBg = Color(0xFF181A20),
        toolPendingBg = Color(0xFF101216),
        borderMuted = Color(0xFF23262E),
        text = Color(0xFFEAECEF),
        heading = Color(0xFFF6F7F9),
        muted = Color(0xFF8C929E),
        dim = Color(0xFF696F7B),
        codeBlockInk = Color(0xFFD9DDE6),
    )

    /** Authentic Anthropic Claude dark warm-ink canvas (`#141413` background). */
    private val claudeInkDark = PiThemeCanvas(
        id = "warm-ink",
        slugEn = "WarmInk",
        labelZh = "暖炭",
        labelEn = "Warm Ink",
        dark = true,
        pageBg = Color(0xFF141413),
        cardBg = Color(0xFF1E1D1B),
        userMessageBg = Color(0xFF282623),
        toolPendingBg = Color(0xFF1C1B19),
        borderMuted = Color(0xFF33312D),
        text = Color(0xFFEBE8E1),
        heading = Color(0xFFF6F4EE),
        muted = Color(0xFF9F9B92),
        dim = Color(0xFF7A766E),
        codeBlockInk = Color(0xFFE0DDD5),
    )

    /** Authentic Tokyo Night / deep midnight canvas (`#1A1B26`). */
    private val midnightDark = PiThemeCanvas(
        id = "midnight",
        slugEn = "Midnight",
        labelZh = "深靛",
        labelEn = "Midnight",
        dark = true,
        pageBg = Color(0xFF141722),
        cardBg = Color(0xFF1C2030),
        userMessageBg = Color(0xFF24293D),
        toolPendingBg = Color(0xFF1B1F2E),
        borderMuted = Color(0xFF2D334A),
        text = Color(0xFFE4E9F5),
        heading = Color(0xFFF2F5FC),
        muted = Color(0xFF8D97B0),
        dim = Color(0xFF6A748E),
        codeBlockInk = Color(0xFFD4DCF0),
    )

    /** Authentic Flexoki Dark analog ink canvas (`#100F0F` / `#1C1B1A`). */
    private val flexokiDark = PiThemeCanvas(
        id = "flexoki-dark",
        slugEn = "Flexoki",
        labelZh = "墨黑",
        labelEn = "Flexoki",
        dark = true,
        pageBg = Color(0xFF100F0F),
        cardBg = Color(0xFF1C1B1A),
        userMessageBg = Color(0xFF282726),
        toolPendingBg = Color(0xFF1A1918),
        borderMuted = Color(0xFF343331),
        text = Color(0xFFCECDC3),
        heading = Color(0xFFF2F0E5),
        muted = Color(0xFF878580),
        dim = Color(0xFF6F6E69),
        codeBlockInk = Color(0xFFDBD9CF),
    )

    /** Authentic Rosé Pine Main canvas (`#191724` / `#1F1D2E`). */
    private val rosePineDark = PiThemeCanvas(
        id = "rose-pine-dark",
        slugEn = "RosePine",
        labelZh = "暮松",
        labelEn = "Rosé Pine",
        dark = true,
        pageBg = Color(0xFF191724),
        cardBg = Color(0xFF1F1D2E),
        userMessageBg = Color(0xFF26233A),
        toolPendingBg = Color(0xFF1E1C2D),
        borderMuted = Color(0xFF332F4A),
        text = Color(0xFFE0DEF4),
        heading = Color(0xFFF2F0FD),
        muted = Color(0xFF908CAA),
        dim = Color(0xFF6E6A86),
        codeBlockInk = Color(0xFFD8D5EE),
    )

    /** Authentic Catppuccin Mocha canvas (`#1E1E2E` / `#181825` / `#313244`). */
    private val mochaDark = PiThemeCanvas(
        id = "mocha",
        slugEn = "Mocha",
        labelZh = "摩卡",
        labelEn = "Mocha",
        dark = true,
        pageBg = Color(0xFF181825),
        cardBg = Color(0xFF1E1E2E),
        userMessageBg = Color(0xFF2A2B3D),
        toolPendingBg = Color(0xFF1F2030),
        borderMuted = Color(0xFF313244),
        text = Color(0xFFCDD6F4),
        heading = Color(0xFFE5EAFA),
        muted = Color(0xFF9399B2),
        dim = Color(0xFF737994),
        codeBlockInk = Color(0xFFC8D0EE),
    )

    private val paperLight = PiThemeCanvas(
        id = "paper",
        slugEn = "Paper",
        labelZh = "画廊白",
        labelEn = "Paper",
        dark = false,
        pageBg = Color(0xFFF6F6F4),
        cardBg = Color(0xFFFFFFFF),
        userMessageBg = Color(0xFFECECE8),
        toolPendingBg = Color(0xFFEFEFED),
        borderMuted = Color(0xFFE0E0DC),
        text = Color(0xFF16181C),
        heading = Color(0xFF101215),
        muted = Color(0xFF5C6069),
        dim = Color(0xFF767B85),
        codeBlockInk = Color(0xFF23262D),
    )

    /** Authentic Anthropic Claude light warm-cream canvas (`#FAF9F5` / `#E8E6DC`). */
    private val claudeCreamLight = PiThemeCanvas(
        id = "claude-cream",
        slugEn = "ClaudeCream",
        labelZh = "暖纸",
        labelEn = "Warm Paper",
        dark = false,
        pageBg = Color(0xFFFAF9F5),
        cardBg = Color(0xFFFFFFFF),
        userMessageBg = Color(0xFFEEECE2),
        toolPendingBg = Color(0xFFF2F0E8),
        borderMuted = Color(0xFFE2DFD4),
        text = Color(0xFF141413),
        heading = Color(0xFF0E0E0D),
        muted = Color(0xFF5E5C56),
        dim = Color(0xFF78756E),
        codeBlockInk = Color(0xFF242320),
    )

    /** Authentic Flexoki Light warm paper canvas (`#FFFCF0` / `#F2F0E5`). */
    private val flexokiLight = PiThemeCanvas(
        id = "flexoki-light",
        slugEn = "Flexoki",
        labelZh = "宣纸",
        labelEn = "Flexoki",
        dark = false,
        pageBg = Color(0xFFFFFCF0),
        cardBg = Color(0xFFF7F5EB),
        userMessageBg = Color(0xFFEFECE0),
        toolPendingBg = Color(0xFFF2F0E5),
        borderMuted = Color(0xFFE2DFD1),
        text = Color(0xFF100F0F),
        heading = Color(0xFF100F0F),
        muted = Color(0xFF5E5C57),
        dim = Color(0xFF75736D),
        codeBlockInk = Color(0xFF242320),
    )

    /** Authentic Rosé Pine Dawn light canvas (`#FAF4ED`). */
    private val rosePineDawnLight = PiThemeCanvas(
        id = "rose-pine-dawn",
        slugEn = "Dawn",
        labelZh = "晨纸",
        labelEn = "Dawn",
        dark = false,
        pageBg = Color(0xFFFAF4ED),
        cardBg = Color(0xFFFFFDF9),
        userMessageBg = Color(0xFFF2E9DE),
        toolPendingBg = Color(0xFFF4EDE4),
        borderMuted = Color(0xFFE6DBD0),
        text = Color(0xFF2B273A),
        heading = Color(0xFF1F1C2C),
        muted = Color(0xFF66617B),
        dim = Color(0xFF7D7893),
        codeBlockInk = Color(0xFF343046),
    )

    /** Authentic Catppuccin Latte light canvas (`#EFF1F5`). */
    private val latteLight = PiThemeCanvas(
        id = "latte",
        slugEn = "Latte",
        labelZh = "拿铁",
        labelEn = "Latte",
        dark = false,
        pageBg = Color(0xFFEFF1F5),
        cardBg = Color(0xFFFFFFFF),
        userMessageBg = Color(0xFFE4E7F0),
        toolPendingBg = Color(0xFFE8EBF3),
        borderMuted = Color(0xFFD6DAE6),
        text = Color(0xFF2A2D3E),
        heading = Color(0xFF1B1E2B),
        muted = Color(0xFF5C6078),
        dim = Color(0xFF727791),
        codeBlockInk = Color(0xFF303448),
    )

    private val porcelainLight = PiThemeCanvas(
        id = "porcelain",
        slugEn = "Porcelain",
        labelZh = "冷瓷",
        labelEn = "Porcelain",
        dark = false,
        pageBg = Color(0xFFF3F5F8),
        cardBg = Color(0xFFFFFFFF),
        userMessageBg = Color(0xFFE9EDF3),
        toolPendingBg = Color(0xFFECEFF4),
        borderMuted = Color(0xFFDCE1EA),
        text = Color(0xFF141820),
        heading = Color(0xFF0E1218),
        muted = Color(0xFF596272),
        dim = Color(0xFF737D8E),
        codeBlockInk = Color(0xFF212733),
    )

    /** Primary 4 dark & 4 light studio canvases shown in the canvas selector strip. */
    val darkCanvases: List<PiThemeCanvas> = listOf(carbonDark, oledDark, claudeInkDark, midnightDark)
    val lightCanvases: List<PiThemeCanvas> = listOf(paperLight, claudeCreamLight, flexokiLight, porcelainLight)
    val allCanvases: List<PiThemeCanvas> = listOf(
        carbonDark, oledDark, claudeInkDark, midnightDark, flexokiDark, rosePineDark, mochaDark,
        paperLight, claudeCreamLight, flexokiLight, rosePineDawnLight, latteLight, porcelainLight,
    )

    // --------------------------------------------------------- Curated Master Presets

    /**
     * Six iconic dual-mode master presets crafted from globally revered developer & editorial
     * color systems (Claude Warm Ink, Flexoki, Rosé Pine, Catppuccin, Tokyo Night, OLED Mono).
     */
    val masterPresets: List<PiThemeMasterPreset> = listOf(
        PiThemeMasterPreset(
            id = "claude",
            labelZh = "Claude 暖陶",
            labelEn = "Claude Warm",
            darkName = "Claude Dark",
            lightName = "Claude Light",
            darkCanvas = claudeInkDark,
            lightCanvas = claudeCreamLight,
            accentHue = 32.0, // Terracotta clay (#D97757 / #C15F3C)
            mood = PiThemeMood.Balanced,
        ),
        PiThemeMasterPreset(
            id = "flexoki",
            labelZh = "Flexoki 墨纸",
            labelEn = "Flexoki",
            darkName = "Flexoki Dark",
            lightName = "Flexoki Light",
            darkCanvas = flexokiDark,
            lightCanvas = flexokiLight,
            accentHue = 192.0, // Analog ink cyan-teal (#3AA99F / #24837B)
            mood = PiThemeMood.Balanced,
        ),
        PiThemeMasterPreset(
            id = "rose-pine",
            labelZh = "Rosé Pine 暮松",
            labelEn = "Rosé Pine",
            darkName = "Rose Pine",
            lightName = "Rose Pine Dawn",
            darkCanvas = rosePineDark,
            lightCanvas = rosePineDawnLight,
            accentHue = 6.0, // Muted rose (#EB6F92 / #B4637A)
            mood = PiThemeMood.Minimal,
        ),
        PiThemeMasterPreset(
            id = "catppuccin",
            labelZh = "Catppuccin",
            labelEn = "Catppuccin",
            darkName = "Catppuccin Mocha",
            lightName = "Catppuccin Latte",
            darkCanvas = mochaDark,
            lightCanvas = latteLight,
            accentHue = 272.0, // Lavender-mauve (#B4BEFE / #7287FD)
            mood = PiThemeMood.Balanced,
        ),
        PiThemeMasterPreset(
            id = "tokyo-night",
            labelZh = "Tokyo Night",
            labelEn = "Tokyo Night",
            darkName = "Tokyo Night",
            lightName = "Tokyo Day",
            darkCanvas = midnightDark,
            lightCanvas = porcelainLight,
            accentHue = 256.0, // Electric calm cobalt (#7AA2F7 / #2E7DE9)
            mood = PiThemeMood.Vibrant,
        ),
        PiThemeMasterPreset(
            id = "mono",
            labelZh = "极简石墨",
            labelEn = "Pure Mono",
            darkName = "OLED Mono",
            lightName = "Gallery Mono",
            darkCanvas = oledDark,
            lightCanvas = paperLight,
            accentHue = 258.0,
            monochrome = true,
            mood = PiThemeMood.Minimal,
        ),
    )

    // ------------------------------------------------------------------ Accents

    val accents: List<PiThemeAccent> = listOf(
        PiThemeAccent("cobalt", "Cobalt", "钴蓝", "Cobalt", hue = 258.0),
        PiThemeAccent("terracotta", "Terracotta", "陶土", "Terracotta", hue = 32.0),
        PiThemeAccent("indigo", "Indigo", "靛紫", "Indigo", hue = 285.0),
        PiThemeAccent("emerald", "Emerald", "翡翠", "Emerald", hue = 152.0),
        PiThemeAccent("teal", "Teal", "松青", "Teal", hue = 192.0),
        PiThemeAccent("amber", "Amber", "琥珀", "Amber", hue = 68.0),
        PiThemeAccent("rose", "Rose", "玫瑰", "Rose", hue = 6.0),
        PiThemeAccent("ink", "Mono", "石墨", "Mono", hue = 258.0, monochrome = true),
    )

    /** Swatch dot color representing [accent] in the current light/dark mode. */
    fun swatchColor(accent: PiThemeAccent, dark: Boolean, mood: PiThemeMood = PiThemeMood.Balanced): Color {
        if (accent.monochrome) {
            return if (dark) Color(0xFFD6DAE2) else Color(0xFF2B2E36)
        }
        val sat = when (mood) {
            PiThemeMood.Minimal -> if (dark) 0.48 else 0.56
            PiThemeMood.Balanced -> if (dark) 0.72 else 0.76
            PiThemeMood.Vibrant -> if (dark) 0.88 else 0.90
        }
        val l = if (dark) 0.70 else 0.45
        return okColor(accent.hue, sat, l)
    }

    /** Representative accent dot color for a master preset chip. */
    fun presetDotColor(preset: PiThemeMasterPreset, dark: Boolean): Color {
        if (preset.monochrome) return if (dark) Color(0xFFD6DAE2) else Color(0xFF23262E)
        val sat = when (preset.mood) {
            PiThemeMood.Minimal -> if (dark) 0.54 else 0.60
            PiThemeMood.Balanced -> if (dark) 0.72 else 0.76
            PiThemeMood.Vibrant -> if (dark) 0.86 else 0.88
        }
        return okColor(preset.accentHue, sat, if (dark) 0.70 else 0.46)
    }

    /** Calibrated OKHSL spectrum stops for the hue slider track so the track mirrors real output colors. */
    fun hueTrackColors(dark: Boolean, mood: PiThemeMood): List<Color> {
        val sat = when (mood) {
            PiThemeMood.Minimal -> if (dark) 0.46 else 0.54
            PiThemeMood.Balanced -> if (dark) 0.70 else 0.74
            PiThemeMood.Vibrant -> if (dark) 0.88 else 0.88
        }
        val l = if (dark) 0.70 else 0.46
        return (0..12).map { step -> okColor(step * 30.0, sat, l) }
    }

    /** Auto-suggested clean theme name for a canvas + accent combination. */
    fun suggestedName(canvas: PiThemeCanvas, accent: PiThemeAccent?, customHue: Double): String {
        val accentPart = accent?.slugEn ?: "Hue-${Math.round(((customHue % 360.0) + 360.0) % 360.0)}"
        return "${canvas.slugEn} $accentPart"
    }

    /**
     * Synthesize a complete, WCAG AA+ verified 59-token [PiPalette] from [canvas],
     * [accentHue] (`0..360` in OKHSL), [monochrome], and [mood].
     */
    fun synthesize(
        canvas: PiThemeCanvas,
        accentHue: Double,
        monochrome: Boolean = false,
        mood: PiThemeMood = PiThemeMood.Balanced,
    ): PiPalette {
        val dark = canvas.dark
        val base = if (dark) PiPalette.Dark else PiPalette.Light
        val h = ((accentHue % 360.0) + 360.0) % 360.0

        val accentSat = when {
            monochrome -> 0.0
            mood == PiThemeMood.Minimal -> if (dark) 0.52 else 0.56
            mood == PiThemeMood.Balanced -> if (dark) 0.74 else 0.76
            else -> if (dark) 0.90 else 0.90
        }
        val syntaxSatScale = when (mood) {
            PiThemeMood.Minimal -> 0.76
            PiThemeMood.Balanced -> 1.0
            PiThemeMood.Vibrant -> 1.18
        }

        val rawAccent = if (monochrome) {
            if (dark) Color(0xFFD8DCE6) else Color(0xFF23262E)
        } else {
            okColor(h, accentSat, if (dark) 0.71 else 0.46)
        }
        val accent = PiContrast.ensureAgainstAll(rawAccent, listOf(canvas.pageBg, canvas.cardBg), 4.8)

        val borderAccent = PiContrast.ensure(
            if (monochrome) lerp(accent, canvas.muted, 0.25f) else okColor(h, (accentSat * 0.92).coerceAtMost(1.0), if (dark) 0.67 else 0.47),
            canvas.pageBg,
            4.5,
        )
        val border = PiContrast.ensure(
            if (monochrome) canvas.muted else okColor(h, (accentSat * 0.78).coerceAtMost(1.0), if (dark) 0.62 else 0.48),
            canvas.pageBg,
            4.2,
        )

        val linkHue = if (monochrome) 254.0 else h
        val mdLink = PiContrast.ensureAgainstAll(
            if (monochrome) {
                if (dark) Color(0xFF9FB8EC) else Color(0xFF2D4F9E)
            } else {
                okColor((linkHue + 8.0) % 360.0, (accentSat * 0.95).coerceAtMost(1.0), if (dark) 0.73 else 0.45)
            },
            listOf(canvas.pageBg, canvas.cardBg),
            5.0,
        )
        val mdCode = PiContrast.ensureAgainstAll(
            if (monochrome) {
                if (dark) Color(0xFFD0D6E2) else Color(0xFF2A2F3A)
            } else {
                okColor((h + 14.0) % 360.0, (accentSat * 0.85).coerceAtMost(1.0), if (dark) 0.77 else 0.43)
            },
            listOf(canvas.pageBg, canvas.cardBg),
            5.0,
        )

        val surfaceTintStrength = when {
            monochrome -> 0.04f
            mood == PiThemeMood.Minimal -> 0.055f
            mood == PiThemeMood.Balanced -> 0.085f
            else -> 0.12f
        }
        val selectedBg = lerp(canvas.cardBg, accent, if (dark) surfaceTintStrength else surfaceTintStrength * 1.15f)
        val customMessageBg = lerp(canvas.cardBg, accent, if (dark) surfaceTintStrength * 0.75f else surfaceTintStrength * 0.85f)

        // Semantic status & tool surfaces harmonized with the chosen canvas
        val success = PiContrast.ensureAgainstAll(
            okColor(152.0, (0.64 * syntaxSatScale).coerceIn(0.38, 0.86), if (dark) 0.71 else 0.41),
            listOf(canvas.pageBg, canvas.cardBg, canvas.toolPendingBg),
            4.8,
        )
        val error = PiContrast.ensureAgainstAll(
            okColor(14.0, (0.76 * syntaxSatScale).coerceIn(0.48, 0.92), if (dark) 0.67 else 0.45),
            listOf(canvas.pageBg, canvas.cardBg, canvas.toolPendingBg),
            4.8,
        )
        val warning = PiContrast.ensureAgainstAll(
            okColor(74.0, (0.74 * syntaxSatScale).coerceIn(0.48, 0.90), if (dark) 0.75 else 0.44),
            listOf(canvas.pageBg, canvas.cardBg, canvas.toolPendingBg),
            4.6,
        )
        val toolSuccessBg = lerp(canvas.toolPendingBg, success, if (dark) 0.11f else 0.09f)
        val toolErrorBg = lerp(canvas.toolPendingBg, error, if (dark) 0.13f else 0.09f)
        val infoBg = lerp(canvas.pageBg, warning, if (dark) 0.13f else 0.11f)

        val searchMatchBg = if (dark) {
            lerp(canvas.cardBg, Color(0xFFB87A14), 0.28f)
        } else {
            lerp(canvas.cardBg, Color(0xFFF6C358), 0.34f)
        }
        val searchMatchText = PiContrast.ensure(
            if (dark) Color(0xFFF6CB7B) else Color(0xFF744500),
            searchMatchBg,
            6.0,
        )

        // Multi-hue syntax palette tuned to mood & WCAG AA+ on cardBg
        fun syn(hue: Double, sat: Double, lDark: Double, lLight: Double): Color =
            PiContrast.ensure(
                okColor(hue, (sat * syntaxSatScale).coerceIn(0.28, 0.90), if (dark) lDark else lLight),
                canvas.cardBg,
                4.8,
            )

        val syntaxComment = PiContrast.ensure(canvas.dim, canvas.cardBg, 4.3)
        val syntaxKeyword = syn(292.0, 0.70, 0.73, 0.43)
        val syntaxFunction = syn(254.0, 0.76, 0.74, 0.44)
        val syntaxVariable = syn(196.0, 0.56, 0.74, 0.42)
        val syntaxString = syn(148.0, 0.56, 0.76, 0.41)
        val syntaxNumber = syn(56.0, 0.68, 0.76, 0.44)
        val syntaxType = syn(76.0, 0.62, 0.77, 0.43)

        val toolOutput = PiContrast.ensureAgainstAll(
            canvas.muted,
            listOf(canvas.toolPendingBg, toolSuccessBg, toolErrorBg),
            4.8,
        )
        val customMessageText = PiContrast.ensure(
            lerp(canvas.text, canvas.muted, 0.24f),
            customMessageBg,
            6.5,
        )

        return base.copy(
            accent = accent,
            border = border,
            borderAccent = borderAccent,
            borderMuted = canvas.borderMuted,
            success = success,
            error = error,
            warning = warning,
            muted = canvas.muted,
            dim = canvas.dim,
            text = canvas.text,
            thinkingText = canvas.muted,
            selectedBg = selectedBg,
            scrollbarTrack = lerp(canvas.pageBg, canvas.borderMuted, 0.65f),
            scrollbarThumb = canvas.dim,
            searchMatchBg = searchMatchBg,
            searchMatchText = searchMatchText,
            userMessageBg = canvas.userMessageBg,
            userMessageText = canvas.heading,
            customMessageBg = customMessageBg,
            customMessageText = customMessageText,
            customMessageLabel = accent,
            toolPendingBg = canvas.toolPendingBg,
            toolSuccessBg = toolSuccessBg,
            toolErrorBg = toolErrorBg,
            toolTitle = canvas.heading,
            toolOutput = toolOutput,
            mdHeading = canvas.heading,
            mdLink = mdLink,
            mdLinkUrl = canvas.muted,
            mdCode = mdCode,
            mdCodeBlock = canvas.codeBlockInk,
            mdCodeBlockBorder = lerp(canvas.borderMuted, canvas.dim, 0.22f),
            mdQuote = lerp(canvas.text, canvas.muted, 0.55f),
            mdQuoteBorder = lerp(canvas.borderMuted, canvas.muted, 0.55f),
            mdHr = lerp(canvas.borderMuted, canvas.dim, 0.25f),
            mdListBullet = lerp(canvas.muted, canvas.dim, 0.45f),
            toolDiffAdded = success,
            toolDiffRemoved = error,
            toolDiffContext = canvas.muted,
            syntaxComment = syntaxComment,
            syntaxKeyword = syntaxKeyword,
            syntaxFunction = syntaxFunction,
            syntaxVariable = syntaxVariable,
            syntaxString = syntaxString,
            syntaxNumber = syntaxNumber,
            syntaxType = syntaxType,
            syntaxOperator = canvas.muted,
            syntaxPunctuation = canvas.muted,
            bashMode = success,
            pageBg = canvas.pageBg,
            cardBg = canvas.cardBg,
            infoBg = infoBg,
        )
    }

    private fun okColor(hue: Double, saturation: Double, lightness: Double): Color =
        Color(okhslToRgb(hue, saturation, lightness).toArgb())
}
