package app.pi.ui.render.rich

/**
 * What a fenced code block is, according to its **explicit language** and nothing
 * else.
 *
 * Phase 1 of `docs/plans/rich-rendering-upgrade.md` only renders three structured
 * formats — JSON, XML and YAML — and only when the fence names one of them. This
 * enum is the complete vocabulary the renderer dispatches on, including the types
 * later phases will need, so that adding a renderer does not have to widen the
 * type everywhere.
 *
 * [Other], [Mermaid], [Svg] and [Html] are **reserved**: they have a name here so
 * the dispatcher can be written once, but [RichBlock.classify] never returns them
 * in Phase 1 — every fence that is not explicitly `json`/`jsonc`/`xml`/`yaml`/
 * `yml` classifies as [Code] and keeps the existing code-block rendering. In
 * particular, *content* is never sniffed: a fence tagged `text` that happens to
 * contain an object is still [Code], because the plan forbids guessing
 * (`§二` "只根据明确的语言标识 … 不允许把普通代码误识别为图表`, `§七.4` "不要把所有普通文本都猜成 JSON").
 */
enum class RichBlockKind {
    /** The existing code block: unchanged renderer, no structural view. */
    Code,

    /** An explicitly tagged `json` / `jsonc` fence, eligible for [PiJsonTree]. */
    Json,

    /** An explicitly tagged `xml` fence, eligible for [PiXmlTree]. */
    Xml,

    /** An explicitly tagged `yaml` / `yml` fence. Phase 1 has no safe parser; see [PiYamlTree]. */
    Yaml,

    /** Reserved. Phase 1 never classifies into it; keep it for future block kinds. */
    Other,

    /** Reserved for Phase 2 (`§四`); not rendered in Phase 1. */
    Mermaid,

    /** Reserved for Phase 2/4 (`§八`); not rendered in Phase 1. */
    Svg,

    /** Reserved for Phase 4 (`§六`); not rendered in Phase 1. */
    Html,
}

/**
 * The bounds a single fence has to fit before any structured rendering is
 * attempted.
 *
 * The plan's table (`§十五`) gives JSON/XML/YAML a **256 KiB** budget, a tree depth
 * of **64** and **20,000** nodes. [maxChars] is the first of those; the depth and
 * node bounds live with the tree models ([JsonTreeLimits], [XmlTreeLimits]) because
 * they are meaningless for a classifier that does not parse the body.
 *
 * The line bound is this app's own, not the plan's: it keeps a pathological
 * one-character-per-line payload (256 KiB of `a\n`) from reaching layout as
 * 130,000 lines. It is deliberately above any legitimate fence.
 */
data class RichBlockLimits(
    /** Maximum fence body length, in `String` characters. */
    val maxChars: Int = 256 * 1024,
    /** Maximum number of lines (a trailing newline does not add a line). */
    val maxLines: Int = 20_000,
) {
    init {
        require(maxChars > 0) { "maxChars must be positive" }
        require(maxLines > 0) { "maxLines must be positive" }
    }
}

/**
 * The Phase-1 classifier: **language in, kind out**. It does not parse the body
 * and does not look at the content.
 *
 * The decision is a conjunction of four cheap, independent gates; failing any one
 * of them is [RichBlockKind.Code]:
 *
 *  1. **Settled.** A fence the model is still writing must stay source
 *     (`§十四.1`), so `settled = false` always means [RichBlockKind.Code]. This is
 *     the producer's own knowledge ([AssistantText.streaming]'s settled concept);
 *     the classifier does not guess completeness from braces.
 *  2. **Bounded.** Over [RichBlockLimits.maxChars] / [RichBlockLimits.maxLines] is
 *     [RichBlockKind.Code] (`§十五` "超出限制不崩溃 … 回退源码").
 *  3. **Non-empty.** A blank body has no tree to show; it stays [RichBlockKind.Code].
 *  4. **Explicit language.** Only `json`/`jsonc` → [RichBlockKind.Json], `xml` →
 *     [RichBlockKind.Xml], `yaml`/`yml` → [RichBlockKind.Yaml]. Everything else,
 *     including `mermaid`, `svg`, `html`, `diff`, `typescript`, and no language at
 *     all, is [RichBlockKind.Code].
 *
 * A caller that *did* try to parse the body and failed passes `bodyValid = false`,
 * which is the plan's "解析失败必须 Code" rule (`§七.4`). The body is still not
 * parsed here — the tree components own that.
 *
 * All constants and rules live in this file, are pure Kotlin (no Compose, no
 * Android), and run in the bare-JVM harness `RichBlockCheck.kt`.
 */
object RichBlock {
    /** The plan's initial budgets (`§十五`), in one place. */
    val DEFAULT_LIMITS = RichBlockLimits()

    private val JSON_LANGUAGES = setOf("json", "jsonc")
    private val YAML_LANGUAGES = setOf("yaml", "yml")

    /**
     * Classify a fence.
     *
     * @param info the complete `FENCE_LANG` info string (may be `null` or empty).
     *   Parsed with [FenceInfo.parse], so `ts title="src/main.ts"` classifies by
     *   `ts`, not by the whole string.
     * @param source the fence body, verbatim.
     * @param settled whether the producer considers the fence complete. Streaming
     *   fences must pass `false` until the block closes.
     * @param limits the size gate; defaults to [DEFAULT_LIMITS].
     * @param bodyValid `false` when a renderer attempted the body and rejected it;
     *   forces [RichBlockKind.Code].
     */
    fun classify(
        info: String?,
        source: String,
        settled: Boolean,
        limits: RichBlockLimits = DEFAULT_LIMITS,
        bodyValid: Boolean = true,
    ): RichBlockKind = classify(FenceInfo.parse(info), source, settled, limits, bodyValid)

    /**
     * [classify] for an already-parsed fence.
     *
     * Kept as an overload rather than a second pipeline: `PiCodeSurface` has the
     * info string, the tree components have the [FenceInfo], and both must reach
     * the identical decision.
     */
    fun classify(
        fence: FenceInfo,
        source: String,
        settled: Boolean,
        limits: RichBlockLimits = DEFAULT_LIMITS,
        bodyValid: Boolean = true,
    ): RichBlockKind {
        if (!settled || !bodyValid) return RichBlockKind.Code
        if (source.isBlank()) return RichBlockKind.Code
        if (exceedsLimits(source, limits)) return RichBlockKind.Code
        return kindForLanguage(fence.language)
    }

    /**
     * The language → kind table alone, with **no** completeness or size gate.
     *
     * This is the one place the three recognized spellings are written down. It is
     * public because the tree components and the classifier must not disagree, and
     * because a test can pin every entry without constructing a fence.
     */
    fun kindForLanguage(language: String): RichBlockKind = when (language.lowercase()) {
        in JSON_LANGUAGES -> RichBlockKind.Json
        "xml" -> RichBlockKind.Xml
        in YAML_LANGUAGES -> RichBlockKind.Yaml
        else -> RichBlockKind.Code
    }

    /**
     * Whether a kind has a Phase-1 renderer.
     *
     * [RichBlockKind.Yaml] is `true` here even though no safe YAML parser is
     * available in this build: the format is recognized, and the renderer falls
     * back to source with a stated reason ([PiYamlTree.parse] returns
     * [YamlTreeFailure.Unsupported]). Callers that want "can I show a tree *now*"
     * should use this and then ask [PiYamlTree.isSupported].
     */
    fun hasRenderer(kind: RichBlockKind): Boolean = when (kind) {
        RichBlockKind.Code, RichBlockKind.Json, RichBlockKind.Xml, RichBlockKind.Yaml -> true
        RichBlockKind.Other, RichBlockKind.Mermaid, RichBlockKind.Svg, RichBlockKind.Html -> false
    }

    /** The plan's size gate, exposed so the UI can show the same boundary it enforces. */
    fun exceedsLimits(source: String, limits: RichBlockLimits = DEFAULT_LIMITS): Boolean {
        if (source.length > limits.maxChars) return true
        // A trailing newline ends the last line; it does not start an empty one.
        // Counting it would reject a file whose last line is newline-terminated at
        // exactly `maxLines`, which is the normal shape of emitted text.
        var newlines = 0
        for (ch in source) {
            if (ch == '\n') newlines++
        }
        var lines = newlines + 1
        if (source.endsWith("\n")) lines--
        return lines > limits.maxLines
    }
}
