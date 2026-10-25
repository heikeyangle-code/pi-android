package app.pi.ui.theme

/**
 * The `system` theme's input, and the one place that turns it into a [PiPalette].
 *
 * pi's `system` theme is not a document on disk: it is *generated* from what the
 * terminal reports about itself (`SYSTEM_THEME_NAME`, `system-theme.ts:32`;
 * `generateSystemThemeColors`, `:400`). This app's surfaces are Compose and it has no
 * terminal to ask, so [PiThemeLoader] still has to hand the derivation a concrete
 * palette. [PiSystemPaletteSource] is that palette and [resolveSystemTheme] is the
 * loader's only way to read it:
 *
 *  - **Default** it holds [defaultAnsiPalette] — pi's own default ANSI 16 on pi's own
 *    default background, the very pair pi falls back to when it has to resolve an
 *    index itself (`BASIC_COLORS`, `packages/tui/src/colors.ts:155-171`). Nothing in
 *    it is invented for Android.
 *  - **User choice** comes from the 「配色方案」 row (`app.appearance.systemPalette`):
 *    [PiSystemPalettes] turns the stored id into one of four inputs, and
 *    `PiSessionViewModel.refreshTheme` writes it here right before the loader runs, so
 *    a write takes effect without touching the loader or the derivation. The loader
 *    still has exactly one way to read colours.
 *
 * **The polarity comes from the palette, not from the device.** pi decides dark vs
 * light from the terminal's own colours ([terminalAppearance], `system-theme.ts:271-290`),
 * and the derivation's lightness curves depend on that answer. With the default palette
 * the background is `#1E1E1E`, so `system` is a **dark** theme even on a light phone:
 * the theme belongs to the terminal, not to the system setting. [PiThemeLoader] lets
 * `dark` follow this answer instead of `systemDark` for exactly that reason — a light
 * system bar over this palette is the white-strip defect `MainActivity.kt` documents.
 *
 * No colour maths lives here. The 56 tokens and the three canvas surfaces come from
 * [deriveSystemPalette], and the polarity from [deriveSystemColors]'s `appearance`, so
 * a later change to the port cannot be silently outrun by a second copy of a rule.
 */

/**
 * The terminal palette the `system` theme is derived from.
 *
 * One mutable value, so the 「配色方案」 row has exactly one thing to write and the
 * loader exactly one thing to read. [palette] is `@Volatile` because that hand-off
 * crosses threads: [PiThemeLoader.load] runs on `Dispatchers.IO` and a settings screen
 * writes on the main thread, so a plain field could hand a reader a half-written
 * reference. A `PiAnsiPalette` is immutable once built, so publishing the *reference*
 * is enough.
 *
 * A palette whose `ansi` is not 16 entries long is allowed: that is pi's "background
 * only" tier (`system-theme.ts:412` treats any other length as "no palette"), and the
 * derivation still paints all 56 tokens.
 */
object PiSystemPaletteSource {
    /** The colours the next `system` resolution derives from. */
    @Volatile
    var palette: PiAnsiPalette = defaultAnsiPalette()

    /**
     * 派生时用的饱和度倍率（pi 的 `deriveSystemPalette(base, saturation)`）。
     *
     * 1.0 = pi 的原值；低于它是「素」，高于它是「鲜艳」。提成单独一个输入而不是塞进
     * [palette]，是因为它不改终端报的颜色、只改推导曲线的松紧 —— 和底色是两个维度。
     */
    @Volatile
    var saturation: Double = 1.0
}

/** A resolved `system` theme: the app palette, plus the polarity it was derived for. */
internal data class PiDerivedSystemTheme(
    val palette: PiPalette,
    val dark: Boolean,
)

/**
 * The last answer, keyed by the input it was derived for.
 *
 * [PiThemeLoader.load] runs again on every system light/dark change and on every
 * edit of the `theme` setting, while a derivation solves 56 tokens by bisection; the
 * memo turns a repeated load into a value comparison. It is keyed by the **input
 * palette's contents** — `PiAnsiPalette.equals` compares `ansi` element by element —
 * so injecting a new palette can never be served a stale palette.
 */
private class PiSystemMemo(
    val input: PiAnsiPalette,
    val saturation: Double,
    val theme: PiDerivedSystemTheme,
)

private object PiSystemMemoSlot {
    @Volatile
    var last: PiSystemMemo? = null
}

/**
 * Derive the `system` theme from [input], or hand back the last answer when [input]
 * is the palette it was derived for.
 *
 * [input] defaults to [PiSystemPaletteSource.palette] so that the loader's call site
 * reads as "the current system theme", not as "whatever this function was given".
 *
 * The two calls below look redundant but are not: [deriveSystemColors] is the only
 * public surface that exposes the **appearance** the derivation picked (the value the
 * lightness curves were solved against), while [deriveSystemPalette] is the only one
 * that maps the 56 tokens onto [PiPalette] together with the exporter's three canvas
 * surfaces. Reading `dark` from the former and the colours from the latter keeps the
 * two halves provably of one derivation; the memo makes them cost one derivation per
 * input rather than one per load.
 */
internal fun resolveSystemTheme(
    input: PiAnsiPalette = PiSystemPaletteSource.palette,
    saturation: Double = PiSystemPaletteSource.saturation,
): PiDerivedSystemTheme {
    PiSystemMemoSlot.last?.let { memo ->
        if (memo.input == input && memo.saturation == saturation) return memo.theme
    }
    val colors = deriveSystemColors(input, saturation)
    val theme = PiDerivedSystemTheme(
        palette = deriveSystemPalette(input, saturation),
        dark = colors.appearance == PiSystemAppearance.DARK,
    )
    PiSystemMemoSlot.last = PiSystemMemo(
        // A defensive copy: `PiAnsiPalette` is a value, but its `ansi` is an `IntArray`,
        // so a caller that edited that array in place instead of assigning a new palette
        // would otherwise compare equal to itself and be served the stale answer.
        input = input.copy(ansi = input.ansi.copyOf()),
        saturation = saturation,
        theme = theme,
    )
    return theme
}

// ============================================================================
// 预置档位：「配色方案」设置行（`app.appearance.systemPalette`）能选的那几档
// ============================================================================

/**
 * 一档可选的终端输入：稳定 id（落盘的就是这个字符串）、中文名、一行说明、底色。
 *
 * ## 为什么只换“输入”，不换 token
 *
 * pi 的 `system` 主题是**推导**出来的：`generateSystemThemeColors`
 * （`system-theme.ts:400`）拿终端报的底色 + ANSI 16 色 + 前景，解出 56 个 token
 * 的明度。所以“让用户选一套配色”唯一诚实的做法是换掉**终端报了什么**，再让同一个
 * 推导跑一遍；要是这里直接写一份 token 表，就成了第二份 pi 主题，与
 * [deriveSystemPalette] 的规则各说各话（本文件顶部的 KDoc 就是为这条不变量写的）。
 *
 * 明暗极性也由此而来，不是界面猜的：`terminalAppearance`
 * （`system-theme.ts:271-290`）从底色与前景的亮度差判深浅，推导的明度曲线跟着它走。
 * 所以「浅色底」这一档会让 `system` 真的变成浅色主题，而不是把深色主题调亮。
 *
 * @property background `0xRRGGBB`，与 [PiAnsiPalette] 同一个约定；界面上的色块读它。
 * @property foreground 终端自己的前景色；null = 终端没报（pi 的默认就是这样）。
 */
data class PiSystemPalettePreset(
    val id: String,
    val label: String,
    val description: String,
    val background: Int,
    val foreground: Int? = null,
) {
    /**
     * 这一档要灌给 [PiSystemPaletteSource] 的输入。
     *
     * `ansi` 取 [DEFAULT_ANSI] 的**拷贝**：它是可变的 `IntArray`，而 [PiAnsiPalette]
     * 只按内容比较 —— 把同一份数组交给多档，任何一处就地改写都会悄悄改掉所有档。
     */
    fun palette(): PiAnsiPalette = PiAnsiPalette(
        background = background,
        ansi = DEFAULT_ANSI.copyOf(),
        foreground = foreground,
    )
}

/**
 * 「配色方案」的全部档位，以及从设置值到推导输入的那一步。
 *
 * 全部使用 pi 自己的 ANSI 16 色（`BASIC_COLORS`，`packages/tui/src/colors.ts:155-171`）：
 * 这几档的差别**只有底色**（浅色档另给前景），所以它们之间不会出现“另一套色相”的
 * 偏差，换档的观感就是同一套 pi 配色在不同终端底色上的推导结果。
 */
object PiSystemPalettes {

    /**
     * 落在 `app-prefs.json` 里的那个键。
     *
     * `app.*` 不是 pi 的设置：`PiSettingsFileStore` 把它写进 agent dir 旁边的
     * app 侧车文件，`settings.json` 里永远不会出现它（`isAppLocalKey`）。
     *
     * 注册表里那一行**必须**写字面量（`settings-audit` 的 `keyPattern` 只认
     * `key = "…",` 这种行），所以这个字符串在仓里有第二处；两处要一起改。
     */
    const val SETTING_KEY = "app.appearance.systemPalette"

    /** 没写过设置时使用的一档：pi 自己的默认（与 [defaultAnsiPalette] 逐值相同）。 */
    const val DEFAULT_ID = "pi-default"

    /** 档位顺序就是界面顺序，第一档是默认。 */
    val presets: List<PiSystemPalettePreset> = listOf(
        PiSystemPalettePreset(
            id = DEFAULT_ID,
            label = "pi 默认（深）",
            description = "pi 自己的 ANSI 16 色与 #1E1E1E 底，与 pi 的默认一致。",
            background = DEFAULT_BACKGROUND,
        ),
        PiSystemPalettePreset(
            id = "light",
            label = "浅色底",
            description = "同一组 ANSI 16 色，底色换成 #F5F5F5，system 因此是浅色主题。",
            background = 0xF5F5F5,
            // 浅色终端报的是深色前景（`SystemThemeInput.foreground`，
            // `system-theme.ts:332-346`）。给成 pi 的默认色：它让 `terminalAppearance`
            // 有真实的输入可判（前景比底色暗 → 浅色），也让正文色沿用“终端自己说的”
            // 那一个，而不是推导出的近似。不给也能判成浅色（退回白/黑对比度比较），
            // 但那样就少了一个真实输入。
            foreground = DEFAULT_BACKGROUND,
        ),
        PiSystemPalettePreset(
            id = "dark-121212",
            label = "深色底（#121212）",
            description = "同一组 ANSI 16 色，底色压到 #121212，比 pi 默认更接近纯黑。",
            background = 0x121212,
        ),
        PiSystemPalettePreset(
            id = "high-contrast",
            label = "高对比（#000000）",
            description = "同一组 ANSI 16 色，底色纯黑；对比度最高，推导会尽量拉开层次。",
            background = 0x000000,
        ),
    )

    /**
     * 设置值 → 一档。
     *
     * 未知 id（手改过 `app-prefs.json`、或以后删掉的档）一律回默认档：设置是一个字符串，
     * 让一个拼错的值把界面变成“没有主题”比回默认更糟。
     */
    fun presetOf(id: String?): PiSystemPalettePreset =
        presets.firstOrNull { it.id == id } ?: presets.first()

    /** 自定义底色那一档的 id：选了它，底色来自 [BACKGROUND_KEY]。 */
    const val CUSTOM_ID = "custom"

    /** 自定义底色的设置键（`#RRGGBB`）。只在 [CUSTOM_ID] 生效。 */
    const val BACKGROUND_KEY = "app.appearance.systemPaletteBg"

    /** 饱和度档的设置键（`soft` / `normal` / `vivid`）。 */
    const val SATURATION_KEY = "app.appearance.systemPaletteSaturation"

    /** 饱和度三档：pi 的推导有一个倍率参数，这里把它变成可选的一维。 */
    val saturations: List<Pair<String, Double>> = listOf(
        "soft" to 0.7,
        "normal" to 1.0,
        "vivid" to 1.4,
    )

    /** 设置值 → 饱和度倍率；未知值一律 1.0（pi 的原值）。 */
    fun saturationOf(wire: String?): Double =
        saturations.firstOrNull { it.first == wire }?.second ?: 1.0

    /** 倍率 → 设置值，界面回显用。 */
    fun saturationWire(value: Double): String =
        (saturations.firstOrNull { it.second == value } ?: saturations[1]).first

    /** 饱和度档的中文名。 */
    fun saturationLabel(wire: String?): String = when (wire) {
        "soft" -> "素"
        "vivid" -> "鲜艳"
        else -> "标准"
    }

    /** `#RRGGBB` / `RRGGBB` → `0xRRGGBB`；不合法返回 null。 */
    fun parseHex(text: String?): Int? {
        val raw = text?.trim()?.removePrefix("#") ?: return null
        if (raw.length != 6 || raw.any { it !in "0123456789abcdefABCDEF" }) return null
        return raw.toLongOrNull(16)?.toInt()
    }

    /** 底色 → 终端前景：亮底给深色前景，暗底让 pi 自己按对比度算（null）。 */
    private fun foregroundFor(background: Int): Int? {
        val r = (background shr 16) and 0xFF
        val g = (background shr 8) and 0xFF
        val b = background and 0xFF
        // Rec. 709 的相对亮度，够用来判"这个底色算浅色吗"；不是色彩管理。
        val luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        return if (luminance > 0.5) DEFAULT_BACKGROUND else null
    }

    /** 一档的底色（自定义档读 [customHex]，不合法时回退到 pi 默认底色）。 */
    fun backgroundOf(id: String?, customHex: String?): Int {
        if (id == CUSTOM_ID) return parseHex(customHex) ?: DEFAULT_BACKGROUND
        return presetOf(id).background
    }

    /**
     * 设置值 → 推导输入。
     *
     * 自定义档的 ANSI 16 色仍然取 pi 的默认值：这一页改的是"终端报了什么底色"，
     * 不是让用户重画 16 色（那需要另一套界面和校验）。底色一换，明暗与 56 个 token
     * 由同一套推导重算。
     */
    fun paletteOf(id: String?, customHex: String? = null): PiAnsiPalette {
        val background = backgroundOf(id, customHex)
        val preset = presets.firstOrNull { it.id == id }
        return PiAnsiPalette(
            background = background,
            ansi = DEFAULT_ANSI.copyOf(),
            foreground = preset?.foreground ?: foregroundFor(background),
        )
    }
}
