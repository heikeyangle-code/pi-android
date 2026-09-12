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
            accent = Color(0xFF8ABEB7),
            border = Color(0xFF5F87FF),
            borderAccent = Color(0xFF00D7FF),
            borderMuted = Color(0xFF505050),
            success = Color(0xFFB5BD68),
            error = Color(0xFFCC6666),
            warning = Color(0xFFFFFF00),
            muted = Color(0xFF808080),
            dim = Color(0xFF666666),
            text = Color(0xFFD4D4D4),
            thinkingText = Color(0xFF808080),
            selectedBg = Color(0xFF3A3A4A),
            scrollbarTrack = Color(0xFF505050),
            scrollbarThumb = Color(0xFFD4D4D4),
            searchMatchBg = Color(0xFF3A3A4A),
            searchMatchText = Color(0xFFD4D4D4),
            userMessageBg = Color(0xFF343541),
            userMessageText = Color(0xFFD4D4D4),
            customMessageBg = Color(0xFF2D2838),
            customMessageText = Color(0xFFD4D4D4),
            customMessageLabel = Color(0xFF9575CD),
            toolPendingBg = Color(0xFF282832),
            toolSuccessBg = Color(0xFF283228),
            toolErrorBg = Color(0xFF3C2828),
            toolTitle = Color(0xFFD4D4D4),
            toolOutput = Color(0xFF808080),
            mdHeading = Color(0xFFF0C674),
            mdLink = Color(0xFF81A2BE),
            mdLinkUrl = Color(0xFF666666),
            mdCode = Color(0xFF8ABEB7),
            mdCodeBlock = Color(0xFFB5BD68),
            mdCodeBlockBorder = Color(0xFF808080),
            mdQuote = Color(0xFF808080),
            mdQuoteBorder = Color(0xFF808080),
            mdHr = Color(0xFF808080),
            mdListBullet = Color(0xFF8ABEB7),
            toolDiffAdded = Color(0xFFB5BD68),
            toolDiffRemoved = Color(0xFFCC6666),
            toolDiffContext = Color(0xFF808080),
            syntaxComment = Color(0xFF6A9955),
            syntaxKeyword = Color(0xFF569CD6),
            syntaxFunction = Color(0xFFDCDCAA),
            syntaxVariable = Color(0xFF9CDCFE),
            syntaxString = Color(0xFFCE9178),
            syntaxNumber = Color(0xFFB5CEA8),
            syntaxType = Color(0xFF4EC9B0),
            syntaxOperator = Color(0xFFD4D4D4),
            syntaxPunctuation = Color(0xFFD4D4D4),
            thinkingOff = Color(0xFF505050),
            thinkingMinimal = Color(0xFF6E6E6E),
            thinkingLow = Color(0xFF5F87AF),
            thinkingMedium = Color(0xFF81A2BE),
            thinkingHigh = Color(0xFFB294BB),
            thinkingXhigh = Color(0xFFD183E8),
            thinkingMax = Color(0xFFFF5FFF),
            bashMode = Color(0xFFB5BD68),
            pageBg = Color(0xFF18181E),
            cardBg = Color(0xFF1E1E24),
            infoBg = Color(0xFF3C3728),
        )

        /** pi built-in `light`. */
        val Light = PiPalette(
            accent = Color(0xFF5A8080),
            border = Color(0xFF547DA7),
            borderAccent = Color(0xFF5A8080),
            borderMuted = Color(0xFFB0B0B0),
            success = Color(0xFF588458),
            error = Color(0xFFAA5555),
            warning = Color(0xFF9A7326),
            muted = Color(0xFF6C6C6C),
            dim = Color(0xFF767676),
            text = Color(0xFF1F2328),
            thinkingText = Color(0xFF6C6C6C),
            selectedBg = Color(0xFFD0D0E0),
            scrollbarTrack = Color(0xFFB0B0B0),
            scrollbarThumb = Color(0xFF1F2328),
            searchMatchBg = Color(0xFFD0D0E0),
            searchMatchText = Color(0xFF1F2328),
            userMessageBg = Color(0xFFE8E8E8),
            userMessageText = Color(0xFF1F2328),
            customMessageBg = Color(0xFFEDE7F6),
            customMessageText = Color(0xFF1F2328),
            customMessageLabel = Color(0xFF7E57C2),
            toolPendingBg = Color(0xFFE8E8F0),
            toolSuccessBg = Color(0xFFE8F0E8),
            toolErrorBg = Color(0xFFF0E8E8),
            toolTitle = Color(0xFF1F2328),
            toolOutput = Color(0xFF6C6C6C),
            mdHeading = Color(0xFF9A7326),
            mdLink = Color(0xFF547DA7),
            mdLinkUrl = Color(0xFF767676),
            mdCode = Color(0xFF5A8080),
            mdCodeBlock = Color(0xFF588458),
            mdCodeBlockBorder = Color(0xFF6C6C6C),
            mdQuote = Color(0xFF6C6C6C),
            mdQuoteBorder = Color(0xFF6C6C6C),
            mdHr = Color(0xFF6C6C6C),
            mdListBullet = Color(0xFF588458),
            toolDiffAdded = Color(0xFF588458),
            toolDiffRemoved = Color(0xFFAA5555),
            toolDiffContext = Color(0xFF6C6C6C),
            syntaxComment = Color(0xFF008000),
            syntaxKeyword = Color(0xFF0000FF),
            syntaxFunction = Color(0xFF795E26),
            syntaxVariable = Color(0xFF001080),
            syntaxString = Color(0xFFA31515),
            syntaxNumber = Color(0xFF098658),
            syntaxType = Color(0xFF267F99),
            syntaxOperator = Color(0xFF000000),
            syntaxPunctuation = Color(0xFF000000),
            thinkingOff = Color(0xFFB0B0B0),
            thinkingMinimal = Color(0xFF767676),
            thinkingLow = Color(0xFF547DA7),
            thinkingMedium = Color(0xFF5A8080),
            thinkingHigh = Color(0xFF875F87),
            thinkingXhigh = Color(0xFF8B008B),
            thinkingMax = Color(0xFFAF005F),
            bashMode = Color(0xFF588458),
            pageBg = Color(0xFFF8F8F8),
            cardBg = Color(0xFFFFFFFF),
            infoBg = Color(0xFFFFFAE6),
        )
    }
}

/** pi's thinking levels, in ascending order (used by cycle + the picker). */
enum class PiThinkingLevel(val wire: String, val label: String) {
    Off("off", "关闭"),
    Minimal("minimal", "极简"),
    Low("low", "低"),
    Medium("medium", "中"),
    High("high", "高"),
    XHigh("xhigh", "很高"),
    Max("max", "最高");

    companion object {
        fun fromWire(value: String?): PiThinkingLevel =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: Off

        fun next(current: PiThinkingLevel): PiThinkingLevel =
            entries[(current.ordinal + 1) % entries.size]
    }
}
