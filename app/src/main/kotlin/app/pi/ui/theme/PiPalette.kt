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
        /** Built-in refined `dark` — deep obsidian slate canvas with luminous indigo accent and crisp tonal elevation. */
        val Dark = PiPalette(
            accent = Color(0xFF9D8DF1),
            border = Color(0xFF58A6D6),
            borderAccent = Color(0xFF9D8DF1),
            borderMuted = Color(0xFF333B48),
            success = Color(0xFF56C596),
            error = Color(0xFFF27278),
            warning = Color(0xFFE5B552),
            muted = Color(0xFF95A0B2),
            dim = Color(0xFF6E7A8C),
            text = Color(0xFFEEF1F6),
            thinkingText = Color(0xFF96A2B4),
            selectedBg = Color(0xFF1F2E42),
            scrollbarTrack = Color(0xFF282E38),
            scrollbarThumb = Color(0xFF6E7A8C),
            searchMatchBg = Color(0xFF47311B),
            searchMatchText = Color(0xFFF3C376),
            userMessageBg = Color(0xFF1C2C40),
            userMessageText = Color(0xFFF0F4F9),
            customMessageBg = Color(0xFF231F36),
            customMessageText = Color(0xFFC8C3DE),
            customMessageLabel = Color(0xFFAE9DF7),
            toolPendingBg = Color(0xFF1D212A),
            toolSuccessBg = Color(0xFF162721),
            toolErrorBg = Color(0xFF2F1B1F),
            toolTitle = Color(0xFFEEF1F6),
            toolOutput = Color(0xFF9AA5B6),
            mdHeading = Color(0xFFEAC26B),
            mdLink = Color(0xFF67B8E8),
            mdLinkUrl = Color(0xFF95A0B2),
            mdCode = Color(0xFFB7A6F8),
            mdCodeBlock = Color(0xFF64C79B),
            mdCodeBlockBorder = Color(0xFF495466),
            mdQuote = Color(0xFF9AA6B8),
            mdQuoteBorder = Color(0xFF5E6B80),
            mdHr = Color(0xFF495466),
            mdListBullet = Color(0xFFA795F5),
            toolDiffAdded = Color(0xFF56C596),
            toolDiffRemoved = Color(0xFFF27278),
            toolDiffContext = Color(0xFF95A0B2),
            syntaxComment = Color(0xFF7D8A9E),
            syntaxKeyword = Color(0xFF73B9E8),
            syntaxFunction = Color(0xFFEAC26B),
            syntaxVariable = Color(0xFF5CC4CA),
            syntaxString = Color(0xFFEA9A6A),
            syntaxNumber = Color(0xFF64C79B),
            syntaxType = Color(0xFFB7A6F8),
            syntaxOperator = Color(0xFF95A0B2),
            syntaxPunctuation = Color(0xFF95A0B2),
            thinkingOff = Color(0xFF5C6675),
            thinkingMinimal = Color(0xFF5E8096),
            thinkingLow = Color(0xFF4E93B8),
            thinkingMedium = Color(0xFF5D89DE),
            thinkingHigh = Color(0xFF9B7CF0),
            thinkingXhigh = Color(0xFFE25BC8),
            thinkingMax = Color(0xFFFF5E6C),
            bashMode = Color(0xFF4EC994),
            pageBg = Color(0xFF111318),
            cardBg = Color(0xFF181B22),
            infoBg = Color(0xFF2A2218),
        )

        /** Built-in refined `light` — warm alabaster paper canvas with pure white elevated cards and royal indigo accent. */
        val Light = PiPalette(
            accent = Color(0xFF644AD4),
            border = Color(0xFF2E86B8),
            borderAccent = Color(0xFF7B61DF),
            borderMuted = Color(0xFFCBD5E1),
            success = Color(0xFF248258),
            error = Color(0xFFD03048),
            warning = Color(0xFFB07219),
            muted = Color(0xFF64748B),
            dim = Color(0xFF8896AA),
            text = Color(0xFF1A202C),
            thinkingText = Color(0xFF64748B),
            selectedBg = Color(0xFFE4EEF8),
            scrollbarTrack = Color(0xFFE2E8F0),
            scrollbarThumb = Color(0xFF94A3B8),
            searchMatchBg = Color(0xFFFEF0D5),
            searchMatchText = Color(0xFF9A6700),
            userMessageBg = Color(0xFFE7F0FA),
            userMessageText = Color(0xFF1A202C),
            customMessageBg = Color(0xFFF1EFFB),
            customMessageText = Color(0xFF4A4466),
            customMessageLabel = Color(0xFF644AD4),
            toolPendingBg = Color(0xFFEEF1F6),
            toolSuccessBg = Color(0xFFEAF6EF),
            toolErrorBg = Color(0xFFFDEEEF),
            toolTitle = Color(0xFF1A202C),
            toolOutput = Color(0xFF586578),
            mdHeading = Color(0xFF9A6700),
            mdLink = Color(0xFF256EB0),
            mdLinkUrl = Color(0xFF64748B),
            mdCode = Color(0xFF644AD4),
            mdCodeBlock = Color(0xFF248258),
            mdCodeBlockBorder = Color(0xFF94A3B8),
            mdQuote = Color(0xFF586578),
            mdQuoteBorder = Color(0xFF94A3B8),
            mdHr = Color(0xFFCBD5E1),
            mdListBullet = Color(0xFF644AD4),
            toolDiffAdded = Color(0xFF248258),
            toolDiffRemoved = Color(0xFFD03048),
            toolDiffContext = Color(0xFF64748B),
            syntaxComment = Color(0xFF7B8798),
            syntaxKeyword = Color(0xFF256EB0),
            syntaxFunction = Color(0xFF9A6700),
            syntaxVariable = Color(0xFF227980),
            syntaxString = Color(0xFFB35318),
            syntaxNumber = Color(0xFF248258),
            syntaxType = Color(0xFF644AD4),
            syntaxOperator = Color(0xFF64748B),
            syntaxPunctuation = Color(0xFF64748B),
            thinkingOff = Color(0xFFB8C2CE),
            thinkingMinimal = Color(0xFF9CB4C4),
            thinkingLow = Color(0xFF7EB2D0),
            thinkingMedium = Color(0xFF7B9CE0),
            thinkingHigh = Color(0xFF9B82E8),
            thinkingXhigh = Color(0xFFD964BE),
            thinkingMax = Color(0xFFF05562),
            bashMode = Color(0xFF2A8C62),
            pageBg = Color(0xFFF5F6FA),
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
