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
    // F13 / F14 (`docs/rendering-review.md`). pi's own values are kept verbatim
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
        /** pi built-in `dark`. */
        val Dark = PiPalette(
            accent = Color(0xFFA798D7),
            border = Color(0xFF5FA8CC),
            borderAccent = Color(0xFFA08ED5),
            borderMuted = Color(0xFF768186),
            success = Color(0xFF68B78D),
            error = Color(0xFFEA7F81),
            warning = Color(0xFFCD9A22),
            muted = Color(0xFF9DA5A9),
            dim = Color(0xFF7E888E),
            text = Color(0xFFDEE0E1),
            thinkingText = Color(0xFF96A0A4),
            selectedBg = Color(0xFF213B49),
            scrollbarTrack = Color(0xFF484E52),
            scrollbarThumb = Color(0xFF97A0A5),
            searchMatchBg = Color(0xFF4E2F1B),
            searchMatchText = Color(0xFF9DA5A9),
            userMessageBg = Color(0xFF213B49),
            userMessageText = Color(0xFFDEE0E1),
            customMessageBg = Color(0xFF3A3055),
            customMessageText = Color(0xFF9DA5A9),
            customMessageLabel = Color(0xFFA798D7),
            toolPendingBg = Color(0xFF34383A),
            toolSuccessBg = Color(0xFF254131),
            toolErrorBg = Color(0xFF5B282A),
            toolTitle = Color(0xFFDEE0E1),
            toolOutput = Color(0xFF9DA5A9),
            mdHeading = Color(0xFFCD9A22),
            mdLink = Color(0xFF69ADD0),
            mdLinkUrl = Color(0xFF9DA5A9),
            mdCode = Color(0xFFA798D7),
            mdCodeBlock = Color(0xFF68B78D),
            mdCodeBlockBorder = Color(0xFF9DA5A9),
            mdQuote = Color(0xFF9DA5A9),
            mdQuoteBorder = Color(0xFF9DA5A9),
            mdHr = Color(0xFF9DA5A9),
            mdListBullet = Color(0xFFA798D7),
            toolDiffAdded = Color(0xFF68B78D),
            toolDiffRemoved = Color(0xFFEA7F81),
            toolDiffContext = Color(0xFF9DA5A9),
            syntaxComment = Color(0xFF9DA5A9),
            syntaxKeyword = Color(0xFF69ADD0),
            syntaxFunction = Color(0xFFCD9A22),
            syntaxVariable = Color(0xFF5DB3BA),
            syntaxString = Color(0xFFDE8D5A),
            syntaxNumber = Color(0xFF68B78D),
            syntaxType = Color(0xFFA798D7),
            syntaxOperator = Color(0xFF9DA5A9),
            syntaxPunctuation = Color(0xFF9DA5A9),
            thinkingOff = Color(0xFF6C767B),
            thinkingMinimal = Color(0xFF68808D),
            thinkingLow = Color(0xFF5489A4),
            thinkingMedium = Color(0xFF6185CC),
            thinkingHigh = Color(0xFF9776E5),
            thinkingXhigh = Color(0xFFDE54C1),
            thinkingMax = Color(0xFFFE5462),
            bashMode = Color(0xFF5EB286),
            pageBg = Color(0xFF21252C),
            cardBg = Color(0xFF282C34),
            infoBg = Color(0xFF4E2F1B),
        )

        /** pi built-in `light`. */
        val Light = PiPalette(
            accent = Color(0xFF7459B4),
            border = Color(0xFF3D8EB3),
            borderAccent = Color(0xFF8A72CB),
            borderMuted = Color(0xFF9AA2A7),
            success = Color(0xFF337E58),
            error = Color(0xFFC8253D),
            warning = Color(0xFF8F6802),
            muted = Color(0xFF677176),
            dim = Color(0xFF879095),
            text = Color(0xFF3B3F41),
            thinkingText = Color(0xFF7C868C),
            selectedBg = Color(0xFFDFE7EC),
            scrollbarTrack = Color(0xFFE1E3E4),
            scrollbarThumb = Color(0xFF96A0A4),
            searchMatchBg = Color(0xFFEDE3DD),
            searchMatchText = Color(0xFF677176),
            userMessageBg = Color(0xFFDFE7EC),
            userMessageText = Color(0xFF3B3F41),
            customMessageBg = Color(0xFFE6E4EE),
            customMessageText = Color(0xFF677176),
            customMessageLabel = Color(0xFF7459B4),
            toolPendingBg = Color(0xFFE4E5E6),
            toolSuccessBg = Color(0xFFDEE9E1),
            toolErrorBg = Color(0xFFEEE2E1),
            toolTitle = Color(0xFF3B3F41),
            toolOutput = Color(0xFF677176),
            mdHeading = Color(0xFF8F6802),
            mdLink = Color(0xFF2F7899),
            mdLinkUrl = Color(0xFF677176),
            mdCode = Color(0xFF7459B4),
            mdCodeBlock = Color(0xFF337E58),
            mdCodeBlockBorder = Color(0xFF677176),
            mdQuote = Color(0xFF677176),
            mdQuoteBorder = Color(0xFF677176),
            mdHr = Color(0xFF677176),
            mdListBullet = Color(0xFF7459B4),
            toolDiffAdded = Color(0xFF337E58),
            toolDiffRemoved = Color(0xFFC8253D),
            toolDiffContext = Color(0xFF677176),
            syntaxComment = Color(0xFF677176),
            syntaxKeyword = Color(0xFF2F7899),
            syntaxFunction = Color(0xFF8F6802),
            syntaxVariable = Color(0xFF287A81),
            syntaxString = Color(0xFFA45417),
            syntaxNumber = Color(0xFF337E58),
            syntaxType = Color(0xFF7459B4),
            syntaxOperator = Color(0xFF677176),
            syntaxPunctuation = Color(0xFF677176),
            thinkingOff = Color(0xFFC2C8CA),
            thinkingMinimal = Color(0xFFB5C4CB),
            thinkingLow = Color(0xFF9FC2D5),
            thinkingMedium = Color(0xFFA2B7E0),
            thinkingHigh = Color(0xFFB5A5E8),
            thinkingXhigh = Color(0xFFE585CD),
            thinkingMax = Color(0xFFFE7479),
            bashMode = Color(0xFF40976C),
            pageBg = Color(0xFFEFEEEE),
            cardBg = Color(0xFFF7F6F6),
            infoBg = Color(0xFFEDE3DD),
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
