package app.pi.ui.theme

import androidx.compose.ui.graphics.Color
import app.pi.rpc.PiJson
import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reading pi's theme files into the app's own palette.
 *
 * pi's theme loader is `loadThemeFromPath` in
 * `packages/coding-agent/src/modes/interactive/theme/theme.ts:555`, the document
 * schema is `theme-json.ts:18-95`, and the discovery locations are
 * `core/resource-loader.ts:872-880` (the agent dir's `themes` directory and the
 * project's `.pi/themes`) plus whatever the `themes` setting names
 * (`resource-loader.ts:882-902`). This file reproduces those three things and
 * nothing else:
 *
 *  - **Discovery** of the names the picker may offer, each with the file it came
 *    from, so a theme dropped into `~/.pi/agent/themes` is selectable even though
 *    the `themes` setting never mentions it. (pi does the same in
 *    `getCustomThemeInfos`, `theme.ts:449-471`.)
 *  - **Colour resolution**, exactly pi's value grammar: `#rrggbb`, an integer
 *    0-255 (a 256-colour terminal index), `""` (the terminal's default), or a
 *    `vars` reference (`resolveVarRefs`, `theme.ts:228-244`).
 *  - **Optional-token fallbacks** from `withThemeColorFallbacks`
 *    (`theme.ts:251-264`) and the HTML exporter's derived page/card/info
 *    backgrounds (`core/export-html/index.ts:81-105`).
 *
 * What the app cannot honour, it says in [PiResolvedTheme.notes] rather than
 * dropping silently:
 *
 *  - `""` means "use the terminal's foreground/background". A Compose palette has
 *    no terminal, so the built-in theme's value for that token is substituted.
 *  - A 256-colour index is resolved against the standard xterm palette, which is
 *    a guess: the real palette belongs to the terminal app, not to pi.
 *  - A missing required token cannot be invented, so the built-in value is used
 *    and the token is named in the notes (pi would reject the file outright,
 *    `theme-json.ts:103-137`).
 */

/** Where a theme the app can paint itself with came from. */
enum class PiThemeScope(val label: String) {
    /** pi's compiled-in `dark` and `light` (`theme.ts:401-417`). */
    Builtin("内置"),

    /** `~/.pi/agent/themes`, pi's `getCustomThemesDir()`. */
    AgentDir("~/.pi/agent/themes"),

    /** `<cwd>/.pi/themes` — pi's project themes directory. */
    Project(".pi/themes"),

    /** A path or directory named by the `themes` setting. */
    Configured("themes 设置"),
}

/** One selectable theme: its name plus the file backing it (null for built-ins). */
data class PiThemeEntry(
    val name: String,
    val path: String?,
    val scope: PiThemeScope,
)

/** A theme resolved into the app's palette, plus every caveat that applies. */
data class PiResolvedTheme(
    /** The raw `theme` setting, e.g. `dark`, `my-theme`, `light/dark`. */
    val setting: String,
    /** The name actually painted after automatic-pair resolution. */
    val name: String,
    val palette: PiPalette,
    val dark: Boolean,
    val source: PiThemeEntry?,
    /** Things the app could not honour literally, in the user's language. */
    val notes: List<String>,
    /** Non-null when the requested theme could not be used at all. */
    val error: String? = null,
) {
    companion object {
        /** The palette used before the settings file has been read. */
        fun fallback(systemDark: Boolean): PiResolvedTheme {
            val name = if (systemDark) "dark" else "light"
            return PiResolvedTheme(
                setting = name,
                name = name,
                palette = if (systemDark) PiPalette.Dark else PiPalette.Light,
                dark = systemDark,
                source = PiThemeEntry(name, null, PiThemeScope.Builtin),
                notes = emptyList(),
            )
        }
    }
}

object PiThemeLoader {

    /** The 54 `colors` tokens of `theme-schema.json`, required ones first. */
    private val REQUIRED_TOKENS = listOf(
        "accent", "border", "borderAccent", "borderMuted", "success", "error", "warning",
        "muted", "dim", "text", "thinkingText", "selectedBg", "userMessageBg",
        "userMessageText", "customMessageBg", "customMessageText", "customMessageLabel",
        "toolPendingBg", "toolSuccessBg", "toolErrorBg", "toolTitle", "toolOutput",
        "mdHeading", "mdLink", "mdLinkUrl", "mdCode", "mdCodeBlock", "mdCodeBlockBorder",
        "mdQuote", "mdQuoteBorder", "mdHr", "mdListBullet", "toolDiffAdded",
        "toolDiffRemoved", "toolDiffContext", "syntaxComment", "syntaxKeyword",
        "syntaxFunction", "syntaxVariable", "syntaxString", "syntaxNumber", "syntaxType",
        "syntaxOperator", "syntaxPunctuation", "thinkingOff", "thinkingMinimal",
        "thinkingLow", "thinkingMedium", "thinkingHigh", "thinkingXhigh", "bashMode",
    )

    /** `theme.ts:251-264`: optional token, and the token it falls back to. */
    private val OPTIONAL_FALLBACKS = mapOf(
        "scrollbarTrack" to "muted",
        "scrollbarThumb" to "text",
        "thinkingMax" to "thinkingXhigh",
        "searchMatchBg" to "selectedBg",
        "searchMatchText" to "text",
    )

    // ------------------------------------------------------------- discovery

    /**
     * Every theme name the app could offer, in the same order pi's
     * `getAvailableThemesWithPaths` (`theme.ts:426-447`) ends up with.
     *
     * [configured] is the `themes` setting, verbatim. Entries are guest paths, so
     * they are mapped onto the host tree with [hostPathOf]; a path that cannot be
     * mapped is skipped here and reported by [load] when it is the active theme.
     */
    fun discover(
        agentDir: File,
        workspace: File,
        configured: List<String> = emptyList(),
    ): List<PiThemeEntry> {
        val found = LinkedHashMap<String, PiThemeEntry>()
        for (name in listOf("dark", "light")) {
            found.putIfAbsent(name, PiThemeEntry(name, null, PiThemeScope.Builtin))
        }
        scanDirectory(File(agentDir, "themes"), PiThemeScope.AgentDir, found)
        scanDirectory(File(workspace, ".pi/themes"), PiThemeScope.Project, found)
        for (entry in configured) {
            val file = hostPathOf(entry, agentDir, workspace) ?: continue
            if (file.isDirectory) {
                scanDirectory(file, PiThemeScope.Configured, found)
            } else if (file.isFile && file.name.endsWith(".json")) {
                readEntry(file, PiThemeScope.Configured)?.let { found.putIfAbsent(it.name, it) }
            }
        }
        return found.values.sortedBy { it.name }
    }

    private fun scanDirectory(dir: File, scope: PiThemeScope, into: MutableMap<String, PiThemeEntry>) {
        val files = dir.listFiles() ?: return
        for (file in files.sortedBy { it.name }) {
            if (!file.isFile || !file.name.endsWith(".json")) continue
            val entry = readEntry(file, scope) ?: continue
            into.putIfAbsent(entry.name, entry)
        }
    }

    /**
     * pi takes a theme's name from its document (`themeJson.name`,
     * `theme.ts:544-550`) and falls back to the file name only when the document
     * has none; `theme.ts:482-491` also rejects a name containing `/` because the
     * slash belongs to the automatic pair syntax.
     */
    private fun readEntry(file: File, scope: PiThemeScope): PiThemeEntry? {
        val json = readJson(file) ?: return null
        val declared = json.str("name")?.trim()
        val name = declared?.takeIf { it.isNotEmpty() && !it.contains('/') }
            ?: file.nameWithoutExtension
        if (name.contains('/')) return null
        return PiThemeEntry(name, file.absolutePath, scope)
    }

    // ---------------------------------------------------------------- loading

    /** The resolution used when the setting is absent: pi's own default. */
    fun defaultSetting(systemDark: Boolean): String = if (systemDark) "dark" else "light"

    /**
     * Resolve the `theme` setting into a palette.
     *
     * The automatic form `lightTheme/darkTheme` is one literal string in pi
     * (`parseAutoThemeSetting`, `theme.ts:600-617`); the slash selects which half
     * applies, exactly as `resolveThemeSetting` does for the terminal's detected
     * background.
     */
    fun load(
        agentDir: File,
        workspace: File,
        configured: List<String>,
        setting: String?,
        systemDark: Boolean,
    ): PiResolvedTheme {
        val raw = setting?.trim().orEmpty().ifEmpty { defaultSetting(systemDark) }
        val slash = raw.indexOf('/')
        val isPair = slash > 0 && raw.indexOf('/', slash + 1) == -1
        val name = if (isPair) {
            val light = raw.substring(0, slash).trim()
            val dark = raw.substring(slash + 1).trim()
            if (systemDark) dark.ifEmpty { "dark" } else light.ifEmpty { "light" }
        } else {
            raw
        }

        if (name == "dark" || name == "light") {
            val dark = name == "dark"
            return PiResolvedTheme(
                setting = raw,
                name = name,
                palette = if (dark) PiPalette.Dark else PiPalette.Light,
                dark = dark,
                source = PiThemeEntry(name, null, PiThemeScope.Builtin),
                notes = emptyList(),
            )
        }

        val entry = discover(agentDir, workspace, configured).firstOrNull { it.name == name }
        val file = entry?.path?.let(::File)
        if (file == null || !file.isFile) {
            return PiResolvedTheme.fallback(systemDark).copy(
                setting = raw,
                error = if (entry != null) {
                    "主题「$name」的文件不存在：${entry.path}"
                } else {
                    "找不到主题「$name」。" +
                        "pi 从 ~/.pi/agent/themes 与项目 .pi/themes 发现主题，" +
                        "也可以把文件路径写进 themes 设置。"
                },
            )
        }
        val parsed = runCatching { parseTheme(file) }.getOrElse { error ->
            return PiResolvedTheme.fallback(systemDark).copy(
                setting = raw,
                error = "主题「$name」读取失败：${error.message ?: error::class.java.simpleName}",
            )
        }
        return PiResolvedTheme(
            setting = raw,
            name = name,
            palette = parsed.palette,
            dark = parsed.dark,
            source = entry,
            notes = parsed.notes,
        )
    }

    // ---------------------------------------------------------------- parsing

    private class ParsedTheme(
        val palette: PiPalette,
        val dark: Boolean,
        val notes: List<String>,
    )

    private fun parseTheme(file: File): ParsedTheme {
        val json = readJson(file)
            ?: throw IllegalArgumentException("不是合法的 JSON 对象")
        return parseThemeJson(json)
    }

    private fun parseThemeJson(json: JsonObject): ParsedTheme {
        val colors = json["colors"] as? JsonObject
            ?: throw IllegalArgumentException("缺少 colors 映射")
        val vars = json["vars"] as? JsonObject ?: JsonObject(emptyMap())

        // Which built-in palette supplies the values the file cannot express.
        // pi's own exporter picks light/dark from the luminance of
        // `userMessageBg` (`export-html/index.ts:88-105`), so that is the token
        // this app also trusts.
        val probe = resolveRaw(colors["userMessageBg"], vars)
        val isLight = (probe as? RgbColor)?.let { !it.color.isDarkSurface() } ?: true
        val base = if (isLight) PiPalette.Light else PiPalette.Dark

        val notes = mutableListOf<String>()
        val emptyTokens = mutableListOf<String>()
        val indexedTokens = mutableListOf<String>()
        val missingTokens = mutableListOf<String>()
        val brokenTokens = mutableListOf<String>()

        val resolved = HashMap<String, Color>()
        for (token in REQUIRED_TOKENS) {
            val element = colors[token]
            if (element == null) {
                missingTokens += token
                resolved[token] = base.token(token)
                continue
            }
            when (val value = resolveRaw(element, vars)) {
                is RgbColor -> resolved[token] = value.color
                is EmptyColor -> {
                    emptyTokens += token
                    resolved[token] = base.token(token)
                }
                is IndexedColor -> {
                    indexedTokens += token
                    resolved[token] = xterm256(value.index)
                }
                else -> {
                    brokenTokens += token
                    resolved[token] = base.token(token)
                }
            }
        }
        // Optional tokens: take the file's value when it has one, otherwise pi's
        // documented fallback token (`theme.ts:251-264`).
        for ((token, fallback) in OPTIONAL_FALLBACKS) {
            val element = colors[token]
            val value = if (element == null) null else resolveRaw(element, vars)
            resolved[token] = when (value) {
                is RgbColor -> value.color
                is IndexedColor -> {
                    indexedTokens += token
                    xterm256(value.index)
                }
                is EmptyColor -> {
                    emptyTokens += token
                    resolved[fallback] ?: base.token(fallback)
                }
                else -> resolved[fallback] ?: base.token(fallback)
            }
        }

        // The `export` section's three surfaces. It is optional; when it is
        // absent pi derives them from `userMessageBg`
        // (`export-html/index.ts:81-105`), which is reproduced here.
        val export = json["export"] as? JsonObject
        for ((token, derived) in deriveExportSurfaces(resolved.getValue("userMessageBg"))) {
            val element = export?.get(token)
            val value = if (element == null) null else resolveRaw(element, vars)
            resolved[token] = when (value) {
                is RgbColor -> value.color
                is IndexedColor -> xterm256(value.index)
                is EmptyColor -> derived
                else -> derived
            }
        }

        if (missingTokens.isNotEmpty()) {
            notes += "缺少必需令牌：${missingTokens.joinToString("、")}；这些颜色用内置主题的值代替。"
        }
        if (brokenTokens.isNotEmpty()) {
            notes += "无法解析的令牌：${brokenTokens.joinToString("、")}；已用内置主题的值代替。"
        }
        if (emptyTokens.isNotEmpty()) {
            notes += "空值令牌（pi 里表示「用终端默认色」）：${emptyTokens.joinToString("、")}；" +
                "这里用内置主题对应颜色代替，App 没有终端默认色可继承。"
        }
        if (indexedTokens.isNotEmpty()) {
            notes += "256 色索引令牌：${indexedTokens.joinToString("、")}；" +
                "按标准 xterm 调色板换算成 RGB，真实颜色取决于终端自己的调色板。"
        }

        return ParsedTheme(palette = base.withTokens(resolved), dark = !isLight, notes = notes)
    }

    /** The result of resolving one theme colour value. */
    private sealed interface Resolved

    private data class RgbColor(val color: Color) : Resolved

    private object EmptyColor : Resolved

    private data class IndexedColor(val index: Int) : Resolved

    /**
     * pi's value grammar (`resolveVarRefs`, `theme.ts:228-244`): a number is a
     * 256-colour index, `""` is the terminal default, a `#` string is a hex
     * colour, and anything else names a `vars` entry (which may itself be any of
     * those, so resolution recurses and refuses a cycle).
     */
    private fun resolveRaw(element: JsonElement?, vars: JsonObject, seen: Set<String> = emptySet()): Resolved? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString) {
            val text = primitive.content
            if (text.isEmpty()) return EmptyColor
            if (text.startsWith("#")) return parseHex(text)?.let(::RgbColor)
            if (text in seen) return null
            return resolveRaw(vars[text], vars, seen + text)
        }
        // `JsonPrimitive` has no `intOrNull`; its content is the numeric text.
        val index = primitive.content.toIntOrNull() ?: return null
        return if (index in 0..255) IndexedColor(index) else null
    }

    private fun parseHex(text: String): Color? {
        val cleaned = text.removePrefix("#")
        if (cleaned.length != 6) return null
        val value = cleaned.toLongOrNull(16) ?: return null
        return Color(0xFF000000L or value)
    }

    /**
     * The standard xterm 256-colour palette: 16 system colours, the 6x6x6 cube
     * (`[0, 95, 135, 175, 215, 255]`), then the 24-step grey ramp. pi itself
     * delegates the index to the terminal (`fgAnsi`, `theme.ts:198-212`), so this
     * is an approximation and is reported as one.
     */
    private fun xterm256(index: Int): Color {
        val clamped = index.coerceIn(0, 255)
        val system = intArrayOf(
            0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
            0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
        )
        if (clamped < 16) return Color(0xFF000000L or system[clamped].toLong())
        if (clamped < 232) {
            val cube = intArrayOf(0, 95, 135, 175, 215, 255)
            val offset = clamped - 16
            val r = cube[offset / 36]
            val g = cube[(offset % 36) / 6]
            val b = cube[offset % 6]
            return Color(0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong())
        }
        val gray = 8 + (clamped - 232) * 10
        return Color(0xFF000000L or (gray.toLong() shl 16) or (gray.toLong() shl 8) or gray.toLong())
    }

    /**
     * `deriveExportColors` from pi's HTML exporter (`export-html/index.ts:81-105`):
     * light themes keep `userMessageBg` as the card and dim it for the page; dark
     * themes dim it for both and nudge the info surface toward yellow.
     */
    private fun deriveExportSurfaces(userMessageBg: Color): Map<String, Color> {
        val isLight = !userMessageBg.isDarkSurface()
        val page = if (isLight) userMessageBg.scaleRgb(0.96f) else userMessageBg.scaleRgb(0.70f)
        val card = if (isLight) userMessageBg else userMessageBg.scaleRgb(0.85f)
        val info = if (isLight) {
            Color(
                red = ((userMessageBg.red * 255f) + 10f).coerceAtMost(255f) / 255f,
                green = ((userMessageBg.green * 255f) + 5f).coerceAtMost(255f) / 255f,
                blue = ((userMessageBg.blue * 255f) - 20f).coerceAtLeast(0f) / 255f,
            )
        } else {
            Color(
                red = ((userMessageBg.red * 255f) + 20f).coerceAtMost(255f) / 255f,
                green = ((userMessageBg.green * 255f) + 15f).coerceAtMost(255f) / 255f,
                blue = userMessageBg.blue,
            )
        }
        return mapOf("pageBg" to page, "cardBg" to card, "infoBg" to info)
    }

    private fun Color.scaleRgb(factor: Float): Color = Color(
        red = (red * factor).coerceIn(0f, 1f),
        green = (green * factor).coerceIn(0f, 1f),
        blue = (blue * factor).coerceIn(0f, 1f),
    )

    /** Relative luminance, the same test pi's exporter uses (`:68-77`). */
    private fun Color.isDarkSurface(): Boolean {
        fun linear(c: Float): Float =
            if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        val luminance = 0.2126f * linear(red) + 0.7152f * linear(green) + 0.0722f * linear(blue)
        return luminance <= 0.5f
    }

    // ------------------------------------------------------------- host paths

    /**
     * Map one `themes` setting entry onto a host file.
     *
     * The setting holds paths as **pi** sees them (guest spelling, relative to
     * the guest cwd). The two prefixes the app can map without the guest are pi's
     * agent dir and the workspace; anything else is left alone and skipped.
     */
    private fun hostPathOf(entry: String, agentDir: File, workspace: File): File? {
        val path = entry.trim()
        if (path.isEmpty() || path.contains('*') || path.startsWith("!")) return null
        val agentPrefixes = listOf("/root/.pi/agent/", "~/.pi/agent/")
        for (prefix in agentPrefixes) {
            if (path.startsWith(prefix)) return File(agentDir, path.removePrefix(prefix))
        }
        if (path == "/root/.pi/agent" || path == "~/.pi/agent") return agentDir
        if (path.startsWith('/')) return null
        return File(workspace, path.removePrefix("./"))
    }

    private fun readJson(file: File): JsonObject? =
        runCatching { PiJson.parseObjectOrNull(file.readText()) }.getOrNull()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    // ----------------------------------------------------------- token mapping

    private fun PiPalette.token(name: String): Color = tokens().getValue(name)

    private fun PiPalette.withTokens(values: Map<String, Color>): PiPalette = PiPalette(
        accent = values.getValue("accent"),
        border = values.getValue("border"),
        borderAccent = values.getValue("borderAccent"),
        borderMuted = values.getValue("borderMuted"),
        success = values.getValue("success"),
        error = values.getValue("error"),
        warning = values.getValue("warning"),
        muted = values.getValue("muted"),
        dim = values.getValue("dim"),
        text = values.getValue("text"),
        thinkingText = values.getValue("thinkingText"),
        selectedBg = values.getValue("selectedBg"),
        scrollbarTrack = values.getValue("scrollbarTrack"),
        scrollbarThumb = values.getValue("scrollbarThumb"),
        searchMatchBg = values.getValue("searchMatchBg"),
        searchMatchText = values.getValue("searchMatchText"),
        userMessageBg = values.getValue("userMessageBg"),
        userMessageText = values.getValue("userMessageText"),
        customMessageBg = values.getValue("customMessageBg"),
        customMessageText = values.getValue("customMessageText"),
        customMessageLabel = values.getValue("customMessageLabel"),
        toolPendingBg = values.getValue("toolPendingBg"),
        toolSuccessBg = values.getValue("toolSuccessBg"),
        toolErrorBg = values.getValue("toolErrorBg"),
        toolTitle = values.getValue("toolTitle"),
        toolOutput = values.getValue("toolOutput"),
        mdHeading = values.getValue("mdHeading"),
        mdLink = values.getValue("mdLink"),
        mdLinkUrl = values.getValue("mdLinkUrl"),
        mdCode = values.getValue("mdCode"),
        mdCodeBlock = values.getValue("mdCodeBlock"),
        mdCodeBlockBorder = values.getValue("mdCodeBlockBorder"),
        mdQuote = values.getValue("mdQuote"),
        mdQuoteBorder = values.getValue("mdQuoteBorder"),
        mdHr = values.getValue("mdHr"),
        mdListBullet = values.getValue("mdListBullet"),
        toolDiffAdded = values.getValue("toolDiffAdded"),
        toolDiffRemoved = values.getValue("toolDiffRemoved"),
        toolDiffContext = values.getValue("toolDiffContext"),
        syntaxComment = values.getValue("syntaxComment"),
        syntaxKeyword = values.getValue("syntaxKeyword"),
        syntaxFunction = values.getValue("syntaxFunction"),
        syntaxVariable = values.getValue("syntaxVariable"),
        syntaxString = values.getValue("syntaxString"),
        syntaxNumber = values.getValue("syntaxNumber"),
        syntaxType = values.getValue("syntaxType"),
        syntaxOperator = values.getValue("syntaxOperator"),
        syntaxPunctuation = values.getValue("syntaxPunctuation"),
        thinkingOff = values.getValue("thinkingOff"),
        thinkingMinimal = values.getValue("thinkingMinimal"),
        thinkingLow = values.getValue("thinkingLow"),
        thinkingMedium = values.getValue("thinkingMedium"),
        thinkingHigh = values.getValue("thinkingHigh"),
        thinkingXhigh = values.getValue("thinkingXhigh"),
        thinkingMax = values.getValue("thinkingMax"),
        bashMode = values.getValue("bashMode"),
        pageBg = values.getValue("pageBg"),
        cardBg = values.getValue("cardBg"),
        infoBg = values.getValue("infoBg"),
    )

    /** Every token as a map, so a parsed file can override any subset of them. */
    private fun PiPalette.tokens(): Map<String, Color> = mapOf(
        "accent" to accent,
        "border" to border,
        "borderAccent" to borderAccent,
        "borderMuted" to borderMuted,
        "success" to success,
        "error" to error,
        "warning" to warning,
        "muted" to muted,
        "dim" to dim,
        "text" to text,
        "thinkingText" to thinkingText,
        "selectedBg" to selectedBg,
        "scrollbarTrack" to scrollbarTrack,
        "scrollbarThumb" to scrollbarThumb,
        "searchMatchBg" to searchMatchBg,
        "searchMatchText" to searchMatchText,
        "userMessageBg" to userMessageBg,
        "userMessageText" to userMessageText,
        "customMessageBg" to customMessageBg,
        "customMessageText" to customMessageText,
        "customMessageLabel" to customMessageLabel,
        "toolPendingBg" to toolPendingBg,
        "toolSuccessBg" to toolSuccessBg,
        "toolErrorBg" to toolErrorBg,
        "toolTitle" to toolTitle,
        "toolOutput" to toolOutput,
        "mdHeading" to mdHeading,
        "mdLink" to mdLink,
        "mdLinkUrl" to mdLinkUrl,
        "mdCode" to mdCode,
        "mdCodeBlock" to mdCodeBlock,
        "mdCodeBlockBorder" to mdCodeBlockBorder,
        "mdQuote" to mdQuote,
        "mdQuoteBorder" to mdQuoteBorder,
        "mdHr" to mdHr,
        "mdListBullet" to mdListBullet,
        "toolDiffAdded" to toolDiffAdded,
        "toolDiffRemoved" to toolDiffRemoved,
        "toolDiffContext" to toolDiffContext,
        "syntaxComment" to syntaxComment,
        "syntaxKeyword" to syntaxKeyword,
        "syntaxFunction" to syntaxFunction,
        "syntaxVariable" to syntaxVariable,
        "syntaxString" to syntaxString,
        "syntaxNumber" to syntaxNumber,
        "syntaxType" to syntaxType,
        "syntaxOperator" to syntaxOperator,
        "syntaxPunctuation" to syntaxPunctuation,
        "thinkingOff" to thinkingOff,
        "thinkingMinimal" to thinkingMinimal,
        "thinkingLow" to thinkingLow,
        "thinkingMedium" to thinkingMedium,
        "thinkingHigh" to thinkingHigh,
        "thinkingXhigh" to thinkingXhigh,
        "thinkingMax" to thinkingMax,
        "bashMode" to bashMode,
        "pageBg" to pageBg,
        "cardBg" to cardBg,
        "infoBg" to infoBg,
    )
}
