package app.pi.ui.render.rich

/**
 * The **pure model** behind the CSV/TSV data table (`§九` of
 * `docs/plans/rich-rendering-upgrade.md`).
 *
 * The Compose table lives in `PiCsvTableView.kt`. The split is the same one
 * `PiJsonTree.kt` / `PiJsonTreeView.kt` uses and it is not cosmetic:
 * `tools/run-app-pure-checks.sh` compiles harnesses with `kotlinc` and the Kotlin
 * stdlib only — no `android.jar`, no Compose jar — so a file that imports Compose
 * cannot be compiled (or therefore tested) there. Everything that can be wrong in
 * a silent way (quoting, embedded newlines, CRLF, the row/column bounds) is here
 * and is exercised by `PiCsvTableCheck.kt`; the view only draws
 * [CsvParseResult.Table] and never re-decides any of it.
 *
 * ## The grammar this accepts
 *
 * RFC 4180, strictly, for the field syntax:
 *
 * - fields are separated by `,` ([CsvFormat.Csv]) or a tab ([CsvFormat.Tsv]);
 * - a field may be quoted with `"`; inside quotes a comma, CR, LF and the quote
 *   character itself are ordinary content;
 * - a quote inside a quoted field is written as `""`;
 * - a quote may only appear at the start of a field, and after a closing quote
 *   only a delimiter, a line ending or EOF may follow;
 * - records end with CRLF; a bare LF is accepted as well because most tools emit
 *   it, and a bare CR is accepted for the same reason;
 * - an unterminated quoted field is a parse failure, not a best-effort guess.
 *
 * Two deliberate leniencies, both about *shape* and neither about content:
 *
 * - a **completely empty line** is skipped (a blank line between records is not a
 *   one-cell record); a line containing only whitespace is still a one-cell
 *   record, because that whitespace is data;
 * - a UTF-8 BOM at the start of the source is removed before parsing. It is an
 *   encoding marker, not part of the first cell.
 *
 * Ragged records are accepted (a short row is a short row), because rejecting
 * them would throw away an otherwise readable file. [CsvParseResult.Table.columnCount]
 * is the widest record, and the view pads missing cells with blanks — it does not
 * invent values, and the copy/source view still has every byte the model wrote.
 *
 * ## Why not `split(",")`
 *
 * `§九` says it directly: `split(",")` breaks every quoted comma and every field
 * that contains a newline. This parser is a single linear scan over the source
 * with one bit of state (inside quotes / after the closing quote); it never
 * backtracks and never re-reads a character.
 *
 * ## Bounds (`§十五`, and this section's own numbers)
 *
 * | bound | default | failure |
 * |---|---:|---|
 * | source characters | 256 KiB ([CsvLimits.maxChars]) | [CsvFailure.TooLarge] |
 * | records, header included | 2,000 ([CsvLimits.maxRows]) | [CsvFailure.TooManyRows] |
 * | columns | 100 ([CsvLimits.maxColumns]) | [CsvFailure.TooManyColumns] |
 *
 * Everything over a bound is a [CsvParseResult.Failure] — never a truncated
 * table. A partial table would be a quietly false document, and the plan's rule is
 * "超出限制不崩溃、不冻结，回退源码" (`§十五`).
 */

/** Which delimiter a recognized fence means. */
enum class CsvFormat(val delimiter: Char) {
    /** Comma-separated values (the RFC's own dialect). */
    Csv(','),

    /** Tab-separated values; the same quoting rules, a tab instead of a comma. */
    Tsv('\t'),
}

/**
 * The bounds for one table.
 *
 * Parameters rather than constants so a harness can drive every failure path with
 * tiny values instead of building a 2,000-row payload.
 */
data class CsvLimits(
    /** Maximum source length in `String` characters (the plan's 256 KiB). */
    val maxChars: Int = 256 * 1024,
    /**
     * Maximum number of records **including the header row**. "2,000 行" in the
     * task's own words counts the visible rows, and the header is one of them.
     */
    val maxRows: Int = 2_000,
    /** Maximum number of fields in any one record. */
    val maxColumns: Int = 100,
) {
    init {
        require(maxChars > 0) { "maxChars must be positive" }
        require(maxRows > 0) { "maxRows must be positive" }
        require(maxColumns > 0) { "maxColumns must be positive" }
    }
}

/** Why a CSV/TSV body did not become a table. Every one of these keeps the source. */
enum class CsvFailure {
    /** The fence is still being streamed; a table must not appear mid-block. */
    NotSettled,

    /** Blank source (whitespace only, or nothing but empty lines). */
    Empty,

    /** More than [CsvLimits.maxChars] characters. */
    TooLarge,

    /** More than [CsvLimits.maxRows] records (header included). */
    TooManyRows,

    /** More than [CsvLimits.maxColumns] fields in one record. */
    TooManyColumns,

    /** Not RFC 4180 field syntax (an unquoted/lone quote, a char after a closing quote, an unterminated quote). */
    Malformed,
}

/** The result of parsing one CSV/TSV fence. */
sealed interface CsvParseResult {
    /**
     * A parsed table.
     *
     * The **first record is the header** (`§九` "表头区分"). [rows] are the
     * remaining records in document order; every cell is exactly the field's
     * decoded text (quotes removed, `""` → `"`), so "复制单元格" copies the value
     * the file actually holds.
     */
    data class Table(
        val format: CsvFormat,
        val header: List<String>,
        val rows: List<List<String>>,
        /** The widest record; every row is at most this long. */
        val columnCount: Int,
    ) : CsvParseResult {
        /** Data rows only; the header is not counted. */
        val rowCount: Int get() = rows.size

        /** The cell at [row] (0 = header) and [column], or `""` when the record is short. */
        fun cell(row: Int, column: Int): String {
            val record = if (row == 0) header else rows.getOrNull(row - 1) ?: return ""
            return record.getOrNull(column) ?: ""
        }
    }

    /** Nothing is rendered as a table; the caller keeps the original source view. */
    data class Failure(val reason: CsvFailure) : CsvParseResult
}

/**
 * The CSV/TSV parser.
 *
 * [parse] assumes the caller already decided the fence is `csv`/`tsv` (see
 * [formatForLanguage]); it never sniffs the body, so an ordinary prose or code
 * block that happens to contain commas is never turned into a table (`§二`
 * "只根据明确的语言标识").
 */
object PiCsvTable {
    /** The task's/plan's defaults, in one place. */
    val DEFAULT_LIMITS = CsvLimits()

    /**
     * The language → dialect table, or `null` for anything that is not an
     * explicit `csv`/`tsv` fence.
     *
     * The caller passes [FenceInfo.language], so `csv title="data.csv"` is still
     * recognized by its first token.
     */
    fun formatForLanguage(language: String): CsvFormat? = when (language.lowercase()) {
        "csv" -> CsvFormat.Csv
        "tsv" -> CsvFormat.Tsv
        else -> null
    }

    /**
     * Parse one fence body.
     *
     * @param source the fence body, verbatim.
     * @param format the dialect named by the fence.
     * @param settled whether the producer considers the block complete; a
     *   streaming block keeps the source view (`§十四.1`).
     * @param limits the character/row/column gate; defaults to [DEFAULT_LIMITS].
     */
    fun parse(
        source: String,
        format: CsvFormat = CsvFormat.Csv,
        settled: Boolean = true,
        limits: CsvLimits = DEFAULT_LIMITS,
    ): CsvParseResult {
        if (!settled) return CsvParseResult.Failure(CsvFailure.NotSettled)
        if (source.length > limits.maxChars) return CsvParseResult.Failure(CsvFailure.TooLarge)

        val body = source.removePrefix("\uFEFF")
        if (body.isBlank()) return CsvParseResult.Failure(CsvFailure.Empty)

        return when (val scanned = scan(body, format.delimiter, limits)) {
            is ScanResult.Failure -> CsvParseResult.Failure(scanned.reason)
            is ScanResult.Records -> {
                val records = scanned.records
                if (records.isEmpty()) {
                    CsvParseResult.Failure(CsvFailure.Empty)
                } else {
                    CsvParseResult.Table(
                        format = format,
                        header = records.first(),
                        rows = records.drop(1),
                        columnCount = records.maxOf { it.size },
                    )
                }
            }
        }
    }
}

/** Internal result of the linear scan; the public result is [CsvParseResult]. */
private sealed interface ScanResult {
    data class Records(val records: List<List<String>>) : ScanResult

    data class Failure(val reason: CsvFailure) : ScanResult
}

/**
 * The one-pass state machine.
 *
 * The state is exactly the four variables below; the loop consumes one character
 * per iteration and never looks back:
 *
 * - `inQuotes` — inside a quoted field (all characters are content, including
 *   delimiters, CR and LF; `""` is a literal quote);
 * - `closedQuote` — a closing quote was just read; only a delimiter, a line
 *   ending or EOF is legal next (anything else is [CsvFailure.Malformed]);
 * - `field` — the current field's decoded text;
 * - `row` / `records` — the current record and the records committed so far.
 */
private fun scan(text: String, delimiter: Char, limits: CsvLimits): ScanResult {
    val records = ArrayList<ArrayList<String>>()
    var row = ArrayList<String>()
    val field = StringBuilder()
    var inQuotes = false
    var closedQuote = false

    fun endField() {
        row.add(field.toString())
        field.setLength(0)
        closedQuote = false
    }

    /** Commit the current record, enforcing the two structural bounds. */
    fun commitRecord(): CsvFailure? {
        endField()
        if (row.size > limits.maxColumns) return CsvFailure.TooManyColumns
        records.add(row)
        if (records.size > limits.maxRows) return CsvFailure.TooManyRows
        row = ArrayList()
        return null
    }

    var index = 0
    while (index < text.length) {
        val char = text[index]

        if (inQuotes) {
            when {
                char != '"' -> {
                    field.append(char)
                    index++
                }
                // `""` is the escaped quote; a single `"` closes the field.
                index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index += 2
                }
                else -> {
                    inQuotes = false
                    closedQuote = true
                    index++
                }
            }
            continue
        }

        if (closedQuote) {
            when {
                char == delimiter -> {
                    endField()
                    if (row.size > limits.maxColumns) {
                        return ScanResult.Failure(CsvFailure.TooManyColumns)
                    }
                    index++
                }
                char == '\r' || char == '\n' -> {
                    val failure = commitRecord()
                    if (failure != null) return ScanResult.Failure(failure)
                    index = newlineEnd(text, index)
                }
                else -> return ScanResult.Failure(CsvFailure.Malformed)
            }
            continue
        }

        when {
            char == delimiter -> {
                endField()
                if (row.size > limits.maxColumns) {
                    return ScanResult.Failure(CsvFailure.TooManyColumns)
                }
                index++
            }
            char == '"' -> {
                // RFC 4180: a quote is only legal as the first character of a
                // field. `a"b` is malformed; it is not a literal quote.
                if (field.isNotEmpty()) return ScanResult.Failure(CsvFailure.Malformed)
                inQuotes = true
                index++
            }
            char == '\r' || char == '\n' -> {
                // A completely empty line is skipped; whitespace is data and is
                // not skipped. An empty quoted field (`""`) *is* a record.
                val hasContent = row.isNotEmpty() || field.isNotEmpty() || closedQuote
                val next = newlineEnd(text, index)
                if (hasContent) {
                    val failure = commitRecord()
                    if (failure != null) return ScanResult.Failure(failure)
                } else {
                    field.setLength(0)
                    closedQuote = false
                }
                index = next
            }
            else -> {
                field.append(char)
                index++
            }
        }
    }

    if (inQuotes) return ScanResult.Failure(CsvFailure.Malformed)

    // A file does not have to end with a line ending: commit the pending record.
    if (row.isNotEmpty() || field.isNotEmpty() || closedQuote) {
        val failure = commitRecord()
        if (failure != null) return ScanResult.Failure(failure)
    }

    return ScanResult.Records(records)
}

/** The index just past the line ending that starts at [index] (CRLF counts as one). */
private fun newlineEnd(text: String, index: Int): Int =
    if (text[index] == '\r' && index + 1 < text.length && text[index + 1] == '\n') index + 2
    else index + 1
