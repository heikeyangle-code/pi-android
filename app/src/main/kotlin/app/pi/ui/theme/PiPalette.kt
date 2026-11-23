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
 * The defaults below are pi's built-in `dark` and `light` themes, transcribed
 * literally. Semantic tokens (`success`/`error`/`warning`, the thinking ramp,
 * diff, syntax, bashMode) stay faithful to these values even when Android
 * dynamic colour is on — a state colour that drifts with the wallpaper is a
 * state colour you cannot trust.
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

    /**
     * §9 meta floor (3:1) for `dim` painted on a card surface — a branch id, a
     * model-change row. pi's `dim` on `cardBg` measures 2.89:1.
     */
    val metaOnCard: Color = PiContrast.ensure(dim, cardBg, 3.0)

    companion object {
        /** Built-in studio `dark` — deep obsidian-zinc canvas with neutral tonal layering and restrained cobalt accent. */
        val Dark = PiPalette(
            accent = Color(0xFF7AA7F7),
            border = Color(0xFF569CD6),
            borderAccent = Color(0xFF6D9CEB),
            borderMuted = Color(0xFF262B35),
            success = Color(0xFF4EC292),
            error = Color(0xFFF06C75),
            warning = Color(0xFFE4B453),
            muted = Color(0xFF8E99AB),
            dim = Color(0xFF646E7F),
            text = Color(0xFFEDF0F5),
            thinkingText = Color(0xFF929DAE),
            selectedBg = Color(0xFF1D2430),
            scrollbarTrack = Color(0xFF242933),
            scrollbarThumb = Color(0xFF646E7F),
            searchMatchBg = Color(0xFF422F1A),
            searchMatchText = Color(0xFFF3C376),
            userMessageBg = Color(0xFF1A1F29),
            userMessageText = Color(0xFFF2F5FA),
            customMessageBg = Color(0xFF181D27),
            customMessageText = Color(0xFFC5CEDD),
            customMessageLabel = Color(0xFF7AA7F7),
            toolPendingBg = Color(0xFF191D25),
            toolSuccessBg = Color(0xFF14241E),
            toolErrorBg = Color(0xFF2B191D),
            toolTitle = Color(0xFFEEF1F6),
            toolOutput = Color(0xFF96A0B0),
            mdHeading = Color(0xFFF5F7FB),
            mdLink = Color(0xFF70B5EA),
            mdLinkUrl = Color(0xFF8E99AB),
            mdCode = Color(0xFF93B8F7),
            mdCodeBlock = Color(0xFF5EC498),
            mdCodeBlockBorder = Color(0xFF313947),
            mdQuote = Color(0xFFA3AEBF),
            mdQuoteBorder = Color(0xFF5C677A),
            mdHr = Color(0xFF38404F),
            mdListBullet = Color(0xFF737E91),
            toolDiffAdded = Color(0xFF4EC292),
            toolDiffRemoved = Color(0xFFF06C75),
            toolDiffContext = Color(0xFF8E99AB),
            syntaxComment = Color(0xFF758195),
            syntaxKeyword = Color(0xFF70B5EA),
            syntaxFunction = Color(0xFFE7BF6A),
            syntaxVariable = Color(0xFF58C1C7),
            syntaxString = Color(0xFFE6996B),
            syntaxNumber = Color(0xFF5EC498),
            syntaxType = Color(0xFF93B8F7),
            syntaxOperator = Color(0xFF8E99AB),
            syntaxPunctuation = Color(0xFF8E99AB),
            thinkingOff = Color(0xFF545E6E),
            thinkingMinimal = Color(0xFF5D7E95),
            thinkingLow = Color(0xFF4C91B8),
            thinkingMedium = Color(0xFF5C8AE0),
            thinkingHigh = Color(0xFF887CE6),
            thinkingXhigh = Color(0xFFD660BA),
            thinkingMax = Color(0xFFF55C6C),
            bashMode = Color(0xFF49C48E),
            pageBg = Color(0xFF0E1015),
            cardBg = Color(0xFF151820),
            infoBg = Color(0xFF262017),
        )

        /** Built-in studio `light` — crisp warm-alabaster canvas with pure white surfaces and editorial graphite-cobalt ink. */
        val Light = PiPalette(
            accent = Color(0xFF2563EB),
            border = Color(0xFF2B7BB9),
            borderAccent = Color(0xFF3B76F0),
            borderMuted = Color(0xFFE0E4EC),
            success = Color(0xFF1F7F56),
            error = Color(0xFFCC2E46),
            warning = Color(0xFFAD6E16),
            muted = Color(0xFF5F6B7C),
            dim = Color(0xFF8894A4),
            text = Color(0xFF14171F),
            thinkingText = Color(0xFF5F6B7C),
            selectedBg = Color(0xFFEBF0F7),
            scrollbarTrack = Color(0xFFE4E8EF),
            scrollbarThumb = Color(0xFF98A3B3),
            searchMatchBg = Color(0xFFFEF0D5),
            searchMatchText = Color(0xFF9A6700),
            userMessageBg = Color(0xFFEEF1F5),
            userMessageText = Color(0xFF14171F),
            customMessageBg = Color(0xFFF1F4FA),
            customMessageText = Color(0xFF3E4A5E),
            customMessageLabel = Color(0xFF2563EB),
            toolPendingBg = Color(0xFFF0F2F6),
            toolSuccessBg = Color(0xFFECF7F1),
            toolErrorBg = Color(0xFFFDF0F1),
            toolTitle = Color(0xFF161B24),
            toolOutput = Color(0xFF566274),
            mdHeading = Color(0xFF0E131B),
            mdLink = Color(0xFF1E64B8),
            mdLinkUrl = Color(0xFF5F6B7C),
            mdCode = Color(0xFF1E56C4),
            mdCodeBlock = Color(0xFF1F7F56),
            mdCodeBlockBorder = Color(0xFFD2D9E4),
            mdQuote = Color(0xFF4B5668),
            mdQuoteBorder = Color(0xFF94A1B4),
            mdHr = Color(0xFFD8DEE8),
            mdListBullet = Color(0xFF7C889A),
            toolDiffAdded = Color(0xFF1F7F56),
            toolDiffRemoved = Color(0xFFCC2E46),
            toolDiffContext = Color(0xFF637083),
            syntaxComment = Color(0xFF7B8798),
            syntaxKeyword = Color(0xFF1E64B8),
            syntaxFunction = Color(0xFF9A6700),
            syntaxVariable = Color(0xFF1E777E),
            syntaxString = Color(0xFFB05116),
            syntaxNumber = Color(0xFF1F7F56),
            syntaxType = Color(0xFF2563EB),
            syntaxOperator = Color(0xFF637083),
            syntaxPunctuation = Color(0xFF637083),
            thinkingOff = Color(0xFFB8C2CE),
            thinkingMinimal = Color(0xFF94AEBF),
            thinkingLow = Color(0xFF6EA9CC),
            thinkingMedium = Color(0xFF5C8CE0),
            thinkingHigh = Color(0xFF7D74DF),
            thinkingXhigh = Color(0xFFD05CB5),
            thinkingMax = Color(0xFFE64E5C),
            bashMode = Color(0xFF23865B),
            pageBg = Color(0xFFF7F7F8),
            cardBg = Color(0xFFFFFFFF),
            infoBg = Color(0xFFFEF4E5),
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
