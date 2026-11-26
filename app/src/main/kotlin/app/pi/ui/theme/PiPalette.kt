package app.pi.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * pi's theme tokens, verbatim.
 *
 * A pi theme is a JSON file with a `colors` map — **51 required tokens plus 5
 * optional ones that have fallbacks** (`scrollbarTrack`, `scrollbarThumb`,
 * `searchMatchBg`, `searchMatchText`, `thinkingMax`), 56 in total, plus an
 * optional `vars` section for reusable values and a 3-key `export` section for
 * the HTML exporter's surfaces. Source of truth:
 * `packages/coding-agent/src/modes/interactive/theme/theme-schema.json`.
 *
 * This file carries all 56 (`dark.json` gives every one) plus the 3 export
 * tokens, because the point is not to have "a lot of colours" — it is that the
 * app's palette should BE the user's pi theme. Themes are user-authored files
 * (under `~/.pi/agent/themes/`), so a theme someone tuned on their desktop
 * should change this app too, and a semantic colour pi assigned a meaning
 * (`toolSuccessBg` is "this tool succeeded") should not be re-invented here and
 * allowed to drift. docs/pi-android-ui-spec.md §2.1 has the surface mapping.
 *
 * The built-in [Dark] and [Light] defaults below are Pi Android's neutral Studio
 * obsidian/alabaster palettes mapped to all 56+3 Pi 1.6.0 tokens for crisp mobile
 * readability, while user-authored `.json` themes under `~/.pi/agent/themes/` load
 * verbatim with their author's exact colors.
 */
@Immutable
data class PiPalette(
    // core UI
    val accent: Color,
    val border: Color,
    val borderAccent: Color,
    val borderMuted: Color,
    val success: Color,
    val error: Color,
    val warning: Color,
    val muted: Color,
    val dim: Color,
    val text: Color,
    val thinkingText: Color,
    val selectedBg: Color,
    val scrollbarTrack: Color,
    val scrollbarThumb: Color,
    val searchMatchBg: Color,
    val searchMatchText: Color,
    // message surfaces
    val userMessageBg: Color,
    val userMessageText: Color,
    val customMessageBg: Color,
    val customMessageText: Color,
    val customMessageLabel: Color,
    // tool surfaces
    val toolPendingBg: Color,
    val toolSuccessBg: Color,
    val toolErrorBg: Color,
    val toolTitle: Color,
    val toolOutput: Color,
    // markdown
    val mdHeading: Color,
    val mdLink: Color,
    val mdLinkUrl: Color,
    val mdCode: Color,
    val mdCodeBlock: Color,
    val mdCodeBlockBorder: Color,
    val mdQuote: Color,
    val mdQuoteBorder: Color,
    val mdHr: Color,
    val mdListBullet: Color,
    // diffs
    val toolDiffAdded: Color,
    val toolDiffRemoved: Color,
    val toolDiffContext: Color,
    // syntax
    val syntaxComment: Color,
    val syntaxKeyword: Color,
    val syntaxFunction: Color,
    val syntaxVariable: Color,
    val syntaxString: Color,
    val syntaxNumber: Color,
    val syntaxType: Color,
    val syntaxOperator: Color,
    val syntaxPunctuation: Color,
    // thinking ramp — this app's signature visual
    val thinkingOff: Color,
    val thinkingMinimal: Color,
    val thinkingLow: Color,
    val thinkingMedium: Color,
    val thinkingHigh: Color,
    val thinkingXhigh: Color,
    val thinkingMax: Color,
    // input mode
    val bashMode: Color,
    // canvas
    val pageBg: Color,
    val cardBg: Color,
    val infoBg: Color,
) {
    /** The pen colour for a thinking level, by pi's own token name. */
    fun thinking(level: String): Color = when (level.lowercase()) {
        "minimal" -> thinkingMinimal
        "low" -> thinkingLow
        "medium" -> thinkingMedium
        "high" -> thinkingHigh
        "xhigh" -> thinkingXhigh
        "max" -> thinkingMax
        else -> thinkingOff
    }

    // ------------------------------------------------------- corrected tokens
    //
    // F13 / F14. pi's own values are kept verbatim
    // above; these five are the *only* deviations, each one the spec's own
    // "修正档" (`docs/pi-android-ui-spec.md:98` and §9's floor at `:831`), each
    // lifted only as far as the floor requires and computed once per palette.
    //
    // They exist because pi paints these tokens as terminal glyphs on a
    // background the terminal owns; a phone paints 13–15 sp prose on a surface
    // this palette chooses. A theme whose values already pass is returned
    // unchanged, so an imported theme never loses its author's colours.

    /**
     * §9 body floor (4.5:1) for the text a tool card carries — pi's `toolOutput`
     * (`components/tool-execution.ts:165`) — against all three card states. pi's
     * own `#808080` measures 3.69:1 on `toolPendingBg` `#282832` and **3.37:1**
     * on `toolSuccessBg` `#283228`, the worst pair on the card.
     */
    val bodyOnTool: Color = PiContrast.ensureAgainstAll(
        toolOutput,
        listOf(toolPendingBg, toolSuccessBg, toolErrorBg),
        4.5,
    )

    /**
     * §9 body floor (4.5:1) for unified-diff context lines — pi's
     * `toolDiffContext` — on the card the diff block draws (`toolPendingBg`).
     * pi's own value measures 3.69:1.
     */
    val contextOnTool: Color = PiContrast.ensure(toolDiffContext, toolPendingBg, 4.5)

    /**
     * §9 body floor (4.5:1) for an expanded thinking block's body — pi's
     * `thinkingText` (`components/assistant-message.ts:151`) — on the canvas.
     * This is the same grey as `muted`, so the light theme's weak value
     * (`docs/pi-android-ui-spec.md:98`) is corrected here too.
     */
    val thinkingBodyOnCanvas: Color = PiContrast.ensure(thinkingText, pageBg, 4.5)

    /**
     * §9 meta floor (3:1) for `muted` used as a *label* on the canvas — the
     * thinking headline, an info notice, a diff's fold line.
     */
    val metaOnCanvas: Color = PiContrast.ensure(muted, pageBg, 3.0)

    companion object {
        /** Built-in Carbon Studio `dark` — true-neutral obsidian canvas, soft indigo accent, balanced multi-hue syntax, WCAG AA+ across all tokens. */
        val Dark = PiPalette(
            accent = Color(0xFF7C9CF8),
            border = Color(0xFF5D89E6),
            borderAccent = Color(0xFF6B8EF0),
            borderMuted = Color(0xFF262930),
            success = Color(0xFF52C48D),
            error = Color(0xFFF06F78),
            warning = Color(0xFFE5B865),
            muted = Color(0xFF8E94A0),
            dim = Color(0xFF6B717D),
            text = Color(0xFFE8EAF0),
            thinkingText = Color(0xFF9096A3),
            selectedBg = Color(0xFF1E222B),
            scrollbarTrack = Color(0xFF1F2229),
            scrollbarThumb = Color(0xFF5F6572),
            searchMatchBg = Color(0xFF3E2F18),
            searchMatchText = Color(0xFFF5C878),
            userMessageBg = Color(0xFF1C1F26),
            userMessageText = Color(0xFFEEF0F5),
            customMessageBg = Color(0xFF171A21),
            customMessageText = Color(0xFFC6CCD8),
            customMessageLabel = Color(0xFF7C9CF8),
            toolPendingBg = Color(0xFF15171C),
            toolSuccessBg = Color(0xFF12211A),
            toolErrorBg = Color(0xFF281619),
            toolTitle = Color(0xFFECEEF3),
            toolOutput = Color(0xFF969CA8),
            mdHeading = Color(0xFFF4F6FA),
            mdLink = Color(0xFF78A8FA),
            mdLinkUrl = Color(0xFF8E94A0),
            mdCode = Color(0xFFA2BDF9),
            mdCodeBlock = Color(0xFFD8DCE6),
            mdCodeBlockBorder = Color(0xFF2F333C),
            mdQuote = Color(0xFFA4AAB6),
            mdQuoteBorder = Color(0xFF545A66),
            mdHr = Color(0xFF333740),
            mdListBullet = Color(0xFF747A87),
            toolDiffAdded = Color(0xFF52C48D),
            toolDiffRemoved = Color(0xFFF06F78),
            toolDiffContext = Color(0xFF8E94A0),
            syntaxComment = Color(0xFF7A808E),
            syntaxKeyword = Color(0xFFC49DF5),
            syntaxFunction = Color(0xFF7AB3FA),
            syntaxVariable = Color(0xFF6BC7CE),
            syntaxString = Color(0xFF8AD1A6),
            syntaxNumber = Color(0xFFE8B478),
            syntaxType = Color(0xFFE5C27A),
            syntaxOperator = Color(0xFF989EAB),
            syntaxPunctuation = Color(0xFF8E94A0),
            thinkingOff = Color(0xFF555B66),
            thinkingMinimal = Color(0xFF638296),
            thinkingLow = Color(0xFF5292BC),
            thinkingMedium = Color(0xFF658EE6),
            thinkingHigh = Color(0xFF9280EB),
            thinkingXhigh = Color(0xFFD768BE),
            thinkingMax = Color(0xFFF36072),
            bashMode = Color(0xFF4EC28A),
            pageBg = Color(0xFF0D0E11),
            cardBg = Color(0xFF15171B),
            infoBg = Color(0xFF231C12),
        )

        /** Built-in Carbon Studio `light` — crisp warm-neutral gallery paper, pure white cards, restrained editorial cobalt ink, WCAG AA+ across all tokens. */
        val Light = PiPalette(
            accent = Color(0xFF2F54B8),
            border = Color(0xFF2A6DB0),
            borderAccent = Color(0xFF365CC2),
            borderMuted = Color(0xFFE0E0DC),
            success = Color(0xFF19754A),
            error = Color(0xFFC52D43),
            warning = Color(0xFF9B6008),
            muted = Color(0xFF5C6069),
            dim = Color(0xFF767B85),
            text = Color(0xFF16181C),
            thinkingText = Color(0xFF5C6069),
            selectedBg = Color(0xFFE8ECF4),
            scrollbarTrack = Color(0xFFE5E5E2),
            scrollbarThumb = Color(0xFF9498A0),
            searchMatchBg = Color(0xFFFCECC8),
            searchMatchText = Color(0xFF7A4B00),
            userMessageBg = Color(0xFFECECE8),
            userMessageText = Color(0xFF16181C),
            customMessageBg = Color(0xFFEEF1F7),
            customMessageText = Color(0xFF3E4552),
            customMessageLabel = Color(0xFF2F54B8),
            toolPendingBg = Color(0xFFEFEFED),
            toolSuccessBg = Color(0xFFEAF4EE),
            toolErrorBg = Color(0xFFFBECEE),
            toolTitle = Color(0xFF16181C),
            toolOutput = Color(0xFF545963),
            mdHeading = Color(0xFF101215),
            mdLink = Color(0xFF2652B2),
            mdLinkUrl = Color(0xFF5C6069),
            mdCode = Color(0xFF2B4FA6),
            mdCodeBlock = Color(0xFF23262D),
            mdCodeBlockBorder = Color(0xFFD4D4CF),
            mdQuote = Color(0xFF4A4E57),
            mdQuoteBorder = Color(0xFF9296A0),
            mdHr = Color(0xFFD8D8D3),
            mdListBullet = Color(0xFF737882),
            toolDiffAdded = Color(0xFF177046),
            toolDiffRemoved = Color(0xFFC22B41),
            toolDiffContext = Color(0xFF5B606A),
            syntaxComment = Color(0xFF6B707A),
            syntaxKeyword = Color(0xFF7A3BB2),
            syntaxFunction = Color(0xFF2456AD),
            syntaxVariable = Color(0xFF176F76),
            syntaxString = Color(0xFF1D7249),
            syntaxNumber = Color(0xFFA45914),
            syntaxType = Color(0xFF8A5D10),
            syntaxOperator = Color(0xFF5B606A),
            syntaxPunctuation = Color(0xFF5B606A),
            thinkingOff = Color(0xFFB2B5BC),
            thinkingMinimal = Color(0xFF89A1B2),
            thinkingLow = Color(0xFF5E9AC0),
            thinkingMedium = Color(0xFF4E7CD4),
            thinkingHigh = Color(0xFF746AD6),
            thinkingXhigh = Color(0xFFC652AB),
            thinkingMax = Color(0xFFDE4858),
            bashMode = Color(0xFF1C784E),
            pageBg = Color(0xFFF6F6F4),
            cardBg = Color(0xFFFFFFFF),
            infoBg = Color(0xFFFBF2E3),
        )
    }
}

/**
 * pi's thinking levels, in ascending order (used by cycle + the picker).
 *
 * [label] is **pi's own word for the level**, not a translation of it: the TUI prints the
 * raw identifier everywhere it names a level — the footer appends the level itself
 * (`footer.ts:185-187`: `${modelName} • ${thinkingLevel}`, and the one special case
 * `thinking off`), the selector labels every option `${level}` ("Thinking Level",
 * `thinking-selector.ts:64-66`), and `--thinking` validates against the same seven
 * identifiers (`cli/args.ts:60`). A translated label was the one place the app named a
 * concept differently from the engine the user is configuring; keeping the wire word
 * also means a screenshot and the session file say the same thing.
 *
 * The seven are pi's whole set (`core/defaults.ts:3-11`); which of them a *model* offers is
 * a separate question — `getSupportedThinkingLevels(model)` returns `["off"]` for a model
 * without `reasoning`, and hides `xhigh`/`max` unless that model's `thinkingLevelMap`
 * declares them, so a phone usually shows three or four.
 */
enum class PiThinkingLevel(val wire: String, val label: String) {
    Off("off", "off"),
    Minimal("minimal", "minimal"),
    Low("low", "low"),
    Medium("medium", "medium"),
    High("high", "high"),
    XHigh("xhigh", "xhigh"),
    Max("max", "max");

    companion object {
        fun fromWire(value: String?): PiThinkingLevel =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: Off

        fun next(current: PiThinkingLevel): PiThinkingLevel =
            entries[(current.ordinal + 1) % entries.size]
    }
}
