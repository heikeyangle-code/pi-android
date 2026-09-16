package app.pi.ui.blocks

/**
 * The two value types that describe a tool card's argument line, in pi's own terms.
 *
 * They live in a file of their own **because they are not UI code**: no Compose import, no
 * `Color`, no Android — just pi's token *names* and the runs that carry them. Keeping them
 * here is what lets `tools/run-app-pure-checks.sh`'s `tool-output-parse` harness compile
 * `ToolOutputParse.kt` on a bare JVM: that harness checks the parse of a tool result, and the
 * parse now produces runs of this shape, so the types have to be compilable without the
 * Compose jars the rest of `ToolBlockChrome.kt` needs.
 *
 * (Before this file existed the two declarations sat in `ToolBlockChrome.kt`, and adding them
 * to the harness closure instead would have dragged every Compose import of that file — and the
 * whole tool-card chrome — into a harness whose point is that it needs nothing but a JDK.)
 */

/**
 * Which of pi's own theme tokens paints one run of a tool card's argument line.
 *
 * A run is described by **pi's token name**, not by a `Color`: the token is the fact the
 * source states, and `ToolHeader` is the single place that resolves it against the current
 * palette. That is the same split `ui/render/PiSyntaxToken` uses for code, and it is what
 * lets each block below quote pi's own `theme.fg(...)` call and nothing else.
 */
internal enum class ToolCallToken {
    /** `toolTitle` — pi's token for a tool's **name**, and for a shell card's whole `$ cmd` line. */
    ToolTitle,

    /** `accent` — pi's token for a path (`renderToolPath`) and for a search pattern. */
    Accent,

    /** `toolOutput` — pi's token for the parts of a call line that are not the subject. */
    ToolOutput,

    /** `warning` — pi's token for a `read` call's `:start-end` range. */
    Warning,

    /** `muted` — pi's token for a shell card's ` (timeout Ns)` suffix. */
    Muted,

    /**
     * No token at all: pi prints these characters with **no `fg` call**, so they carry the
     * terminal's default foreground. The phone's equivalent of that default is `text`
     * (`pi-android-ui-spec.md` §2.1 maps pi's `text` onto `OnSurface`).
     *
     * The one user is the generic fallback card, whose call line pi builds as
     * `theme.fg("toolTitle", bold(toolName))` + the raw JSON arguments with no colour
     * (`components/tool-execution.js:274-278` → `:315-322`).
     */
    Uncoloured,
}

/**
 * One `theme.fg(token, text)` of a call line, in pi's own order.
 *
 * [bold] exists for the one formula in pi that bolds more than the tool name: a shell card's
 * whole line is `theme.fg("toolTitle", theme.bold(`${prompt} ${command}`))`
 * (`core/tools/renderers/bash.js:31`), so its command is bold for the same reason the name is.
 */
internal data class ToolCallPart(
    val text: String,
    val token: ToolCallToken,
    val bold: Boolean = false,
)
