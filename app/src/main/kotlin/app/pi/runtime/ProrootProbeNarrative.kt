package app.pi.runtime

/**
 * The probe gate's **recorded verdict**, turned into the two things a user reads: the
 * one-line summary on the 运行时（实际生效） row, and the bounded block of raw per-phase
 * lines that belongs to it.
 *
 * ## The defect this closes
 *
 * Those two things used to be one half-sentence and a report export. The row said
 * "已回退 proot：探针未通过" ([EngineFallback.ProbeNotPassed] in [RuntimeChoice.describe])
 * and every other reader — the settings row, the group summary on the settings home,
 * the 实际生效 line of the exported report — had to open the diagnostic report to learn
 * *which* of the gate's two probes refused and *why*. The evidence was already on disk
 * (`ProrootProbeCache.Cached.detail`) and already printed by `DiagnosticsReport`, so the
 * row was showing less than the app knew — the "value that looks like a reading but
 * answers a narrower question" shape this repository keeps removing.
 *
 * ## Where the stage name and the quote come from
 *
 * From the recorded lines, and from nowhere else:
 *
 *  - the **raw syscall** probe's phases (`guestpath=…`, `passwd=…`), whose verdict
 *    tokens come from [ProrootRawProbe]'s guest script and are ranked here by the rule
 *    that same report refuses for — [ProrootRawProbe.LEAKED]/[ProrootRawProbe.MISMATCH]
 *    first (`Report.leaked` outranks everything), then the untranslated phase
 *    (`!Report.translated`), and only if no phase carries a verdict, the recorded
 *    [ProrootRawProbe.FAILURE_HEADER] prose;
 *  - the **rg/fd real-invocation** tier — `GuestToolProbe`'s own `✗ <tool>：<reason>`
 *    line, or its launch-error line when the probe could not run;
 *  - nothing else. There is deliberately no verdict-to-Chinese table here: a second
 *    place that decides what `untranslated` *means* is a second source of truth, and
 *    the recorded prose (which already says it) is in the detail block anyway.
 *
 * When the cache holds a verdict but no evidence, the functions say exactly that
 * instead of inventing a stage. "尚未运行" and "读不到" are answers, not gaps to fill
 * with a plausible default.
 *
 * ## Bounds, and why
 *
 * The row's value is a single ellipsized line, so [summary] is capped
 * ([MAX_SUMMARY_CHARS]) and the per-stage quote is capped before that
 * ([MAX_QUOTE_CHARS]) — the stage label is never what gets truncated away.
 * [boundedDetailLines] caps the block at [MAX_DETAIL_LINES] and appends a sentence
 * naming how many lines were left out and where the whole evidence is; [detailLines]
 * is the unbounded form, and it is what `DiagnosticsReport` prints, so the exported
 * report and the row can never disagree about a line's text — only the row ever drops
 * a tail, and it says so.
 *
 * ## Threading
 *
 * Pure: `String`/`List<String>` in, `String`/`List<String>` out — no file, no process,
 * no Android, no state. Every caller already holds the cached lines, so this adds no
 * work to any IO path; in particular it is **not** called from a settings row's
 * `read(key)`. `PiSettingsStack` runs `RuntimeSelection.status()` (the one call that
 * hashes five `.so` files) once per epoch on `Dispatchers.IO` and hands the finished
 * strings down, exactly as it already did for the single-line sentence.
 */
object ProrootProbeNarrative {

    /**
     * Cap for the summary line the settings row renders with `maxLines = 1`.
     *
     * Chosen from the sentence's own shape: prefix (16) + longest stage label (11) +
     * separator (1) + [MAX_QUOTE_CHARS] + closing bracket (1) = 69. Anything longer
     * would be the quote's fault, and the quote is what gets bounded first.
     */
    const val MAX_SUMMARY_CHARS = 72

    /** Cap for the recorded quote inside the summary — the part that may be cut. */
    const val MAX_QUOTE_CHARS = 40

    /** How many evidence lines the settings row's detail block shows at most. */
    const val MAX_DETAIL_LINES = 6

    /**
     * The stage label for each half of the gate. They name the measurement, not a
     * verdict: "raw syscall" is `ProrootRawProbe`, "rg/fd 真调用" is `GuestToolProbe`
     * invoked through the candidate runtime.
     */
    const val STAGE_RAW = "raw syscall"
    const val STAGE_TOOLS = "rg/fd 真调用"

    /** The sentence [detailLines] gives when the cache has no evidence to show. */
    const val DETAIL_NOT_RUN = "探针尚未运行：没有逐阶段记录。"
    const val DETAIL_NOT_PASSED_WITHOUT_EVIDENCE =
        "读不到逐阶段记录：缓存里只有「未通过」的结论。" +
            "重新打开「运行时加速（实验性）」开关可让探针重测一次。"
    const val DETAIL_PASSED_WITHOUT_EVIDENCE = "缓存里只有「已通过」的结论，没有逐阶段记录。"

    /** The one-line reason, with the failing stage appended when it is known. */
    fun summary(fallback: EngineFallback, probeDetail: List<String>): String {
        // Every other reason is already complete: the switch, the missing files and the
        // failure streak have nothing to add from the probe, and this object has no
        // business restating them.
        if (fallback != EngineFallback.ProbeNotPassed) return RuntimeChoice.describe(fallback)

        // Reuse the base sentence rather than retyping it, so the two forms cannot drift.
        val base = RuntimeChoice.describe(EngineFallback.ProbeNotPassed)
        val stage = failedStage(probeDetail)
            ?: return bounded("$base（缓存里没有逐阶段记录）", MAX_SUMMARY_CHARS)
        return bounded(
            "$base（${stage.label}${ProrootRawProbe.HEADER_SEPARATOR}" +
                "${bounded(stage.quote, MAX_QUOTE_CHARS)}）",
            MAX_SUMMARY_CHARS,
        )
    }

    /** Which half of the gate refused, and the recorded line that says so. */
    data class FailedStage(val label: String, val quote: String)

    /**
     * The failing stage of a recorded verdict, or null when the lines carry no verdict
     * this object recognises (a truncated write, a cache from an older wording).
     *
     * Null is an answer: the callers say "缓存里没有逐阶段记录" rather than guessing.
     */
    fun failedStage(probeDetail: List<String>): FailedStage? {
        val lines = probeDetail.map { it.trim() }.filter { it.isNotEmpty() }

        // The probe never ran: one line, and it names its own cause (a missing perl).
        lines.firstOrNull { it.startsWith(ProrootRawProbe.NOT_RUN_HEADER) }?.let { line ->
            return FailedStage(
                STAGE_RAW,
                line.substringAfter(ProrootRawProbe.HEADER_SEPARATOR).trim(),
            )
        }

        // The phases, exactly as `Report.describe` writes them: "  guestpath=untranslated（errno=2）".
        val phases = lines.mapNotNull { parsePhase(it) }
        // `Report.leaked` outranks `!translated` in the report's own `failure` branch,
        // so a leak is the stage that gets named when both hold.
        val leak = phases.firstOrNull {
            it.verdict == ProrootRawProbe.LEAKED || it.verdict == ProrootRawProbe.MISMATCH
        }
        val untranslated = phases.firstOrNull { it.verdict == ProrootRawProbe.UNTRANSLATED }
        (leak ?: untranslated)?.let { return FailedStage(STAGE_RAW, it.raw) }

        // A refused raw probe whose phases carry no verdict this object knows (for
        // example a planted file that could not be read): quote the probe's own prose.
        lines.firstOrNull { it.startsWith(ProrootRawProbe.FAILURE_HEADER) }?.let { line ->
            return FailedStage(
                STAGE_RAW,
                line.substringAfter(ProrootRawProbe.HEADER_SEPARATOR).trim(),
            )
        }

        // The second half of the gate: the real `rg`/`fd` invocation, or the reason it
        // could not run at all. Both are `GuestToolProbe`'s own lines.
        lines.firstOrNull { line ->
            line.startsWith("✗ ") && GuestToolProbe.TOOLS.any { line.startsWith("✗ $it") }
        }?.let { line -> return FailedStage(STAGE_TOOLS, line.removePrefix("✗ ").trim()) }
        lines.firstOrNull { it.startsWith(TOOLS_LAUNCH_ERROR_HEADER) }?.let { line ->
            return FailedStage(
                STAGE_TOOLS,
                line.substringAfter(ProrootRawProbe.HEADER_SEPARATOR).trim(),
            )
        }
        return null
    }

    /**
     * The evidence lines, verbatim and **unbounded** — this is what the diagnostic
     * report prints, so the export keeps the whole record.
     *
     * When there is no evidence, one line says why: the probe has not run yet, or the
     * cache holds a verdict with no detail. Never an empty block dressed up as a
     * reading, and never a stage this object made up.
     */
    fun detailLines(
        fallback: EngineFallback,
        probePassed: Boolean?,
        probeDetail: List<String>,
    ): List<String> {
        val recorded = probeDetail.map { it.trim() }.filter { it.isNotEmpty() }
        if (recorded.isNotEmpty()) return recorded
        return when {
            fallback == EngineFallback.ProbeNotRun -> listOf(DETAIL_NOT_RUN)
            fallback == EngineFallback.ProbeNotPassed -> listOf(DETAIL_NOT_PASSED_WITHOUT_EVIDENCE)
            probePassed == true -> listOf(DETAIL_PASSED_WITHOUT_EVIDENCE)
            // The switch is off, the files are missing, or proroot is in use: the probe
            // is not the reason, so there is nothing to add under the row.
            else -> emptyList()
        }
    }

    /** [detailLines] joined for a `Text`, one line each. */
    fun detailText(
        fallback: EngineFallback,
        probePassed: Boolean?,
        probeDetail: List<String>,
    ): String = detailLines(fallback, probePassed, probeDetail).joinToString("\n")

    /**
     * The settings row's block: [detailLines], capped, with one extra line that says
     * how many evidence lines were left out and where they all are.
     *
     * `limit` is a parameter so the cap is executed by the harness rather than
     * described by a comment; production passes nothing.
     */
    fun boundedDetailLines(
        fallback: EngineFallback,
        probePassed: Boolean?,
        probeDetail: List<String>,
        limit: Int = MAX_DETAIL_LINES,
    ): List<String> {
        require(limit > 0) { "the detail block must be able to show at least one line" }
        val all = detailLines(fallback, probePassed, probeDetail)
        if (all.size <= limit) return all
        return all.take(limit) + "……还有 ${all.size - limit} 行，导出诊断报告可看全文"
    }

    /** [boundedDetailLines] joined for a `Text`. Empty exactly when there is nothing to add. */
    fun boundedDetailText(
        fallback: EngineFallback,
        probePassed: Boolean?,
        probeDetail: List<String>,
    ): String = boundedDetailLines(fallback, probePassed, probeDetail).joinToString("\n")

    /** One `  <phase>=<verdict>（<detail>）` line, as `Report.describe` writes it. */
    private data class RecordedPhase(val raw: String, val verdict: String)

    private fun parsePhase(line: String): RecordedPhase? {
        val match = PHASE.matchEntire(line) ?: return null
        return RecordedPhase(raw = match.groupValues[0], verdict = match.groupValues[2])
    }

    private val PHASE = Regex("^[a-z]+=([a-z-]+)（(.*)）$")

    /**
     * `GuestToolProbe.Report.describe`'s launch-error line. Its wording lives in that
     * file (which this change does not own), so the pairing is pinned by the
     * `proroot-probe-detail` harness, which builds the line through `describe()` and
     * reads it back through here — a reword fails the harness instead of silently
     * dropping the rg/fd stage from the row.
     */
    private const val TOOLS_LAUNCH_ERROR_HEADER = "工具链探针没能运行"

    /** One line, and never longer than [max] code units; the cut is marked with `…`. */
    private fun bounded(text: String, max: Int): String {
        val clean = text.replace('\n', ' ').replace('\r', ' ').replace(WHITESPACE, " ").trim()
        if (clean.length <= max) return clean
        var end = max - 1
        // Never split a surrogate pair: the recorded lines can hold any tool output.
        if (end > 0 && Character.isHighSurrogate(clean[end - 1])) end--
        return clean.substring(0, end).trimEnd() + "…"
    }

    private val WHITESPACE = Regex("\\s+")
}
