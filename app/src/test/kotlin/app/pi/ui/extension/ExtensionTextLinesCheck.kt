package app.pi.ui.extension

// A bare-JVM harness for the two predicates that decide how extension output is
// *shaped*, registered in `tools/run-app-pure-checks.sh` as `text-lines`.
//
// What is worth pinning: neither rule is visible to a compiler. A widget box that
// silently starts wrapping loses its columns and no type changes; a select list
// that silently stops opening as a sheet puts a 12-option scroll back inside a
// 330-wide dialog. The thresholds themselves are the design (see the KDoc on
// `ExtensionTextLines.kt`, which cites `06 §2` and the panel math), so the harness
// pins their boundary values as well as their behaviour — moving 9 to 8 or 80 to
// 48 has to be a deliberate edit here, not a drift.

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

fun main() {
    // --- isPreformatted: prose must stay prose ---------------------------------
    check("a plain sentence is not preformatted", isPreformatted("hello world this is a sentence"), false)
    check(
        "a CJK sentence is not preformatted",
        isPreformatted("所有子代理已经完成，等待用户确认下一步行动"),
        false,
    )
    check("an empty line is not preformatted", isPreformatted(""), false)
    check("a single space between words is prose", isPreformatted("job state"), false)

    // --- rule 1: two or more consecutive spaces (column alignment) ------------
    check("a double space marks a table row", isPreformatted("job    state"), true)
    check("a right-aligned numeric column counts", isPreformatted("build 42   ok"), true)
    check("an indented line counts", isPreformatted("  indented block line"), true)
    // Trailing whitespace is padding, not alignment: trimming first is what keeps
    // an editor's trailing spaces from turning every line into a table.
    check("trailing spaces alone do not count", isPreformatted("hello  "), false)
    check("trailing spaces after a real gap still count", isPreformatted("a  b   "), true)

    // --- rule 2: box-drawing characters (the task's exact set, plus the block)
    for (glyph in listOf("─", "│", "┌", "┐", "└", "┘", "├", "┤")) {
        check("box glyph $glyph marks box art", isPreformatted("a${glyph}b"), true)
    }
    check("a full box row counts", isPreformatted("┌──────┬──────┐"), true)
    check("a T-junction outside the named set still counts", isPreformatted("a┼b"), true)
    check("a geometric symbol that is not box drawing does not", isPreformatted("a▲b"), false)

    // --- rule 3: visible width over the threshold ----------------------------
    check(
        "exactly the threshold width is not too wide",
        isPreformatted("x".repeat(PreformatWideColumns)),
        false,
    )
    check(
        "one column past the threshold is too wide",
        isPreformatted("x".repeat(PreformatWideColumns + 1)),
        true,
    )
    // Columns, not characters: 41 CJK glyphs are 82 columns even though the
    // string is only 41 chars long.
    check("40 CJK glyphs = 80 columns, still under", isPreformatted("数".repeat(40)), false)
    check("41 CJK glyphs = 82 columns, over", isPreformatted("数".repeat(41)), true)

    // --- escapes are stripped before any rule runs ---------------------------
    // ESC built from a code so this source carries no invisible byte.
    val esc = (0x1B).toChar().toString()
    val red = esc + "[31m"
    val reset = esc + "[0m"
    check(
        "SGR bytes do not add width",
        isPreformatted(red + "x".repeat(PreformatWideColumns) + reset),
        false,
    )
    check(
        "the visible double space around an escape still counts",
        isPreformatted("a  " + reset + "b"),
        true,
    )
    check(
        "an escape between two visible spaces keeps the gap",
        isPreformatted("a " + reset + " b"),
        true,
    )
    check(
        "colour-only padding around a word is still prose",
        isPreformatted(red + "b" + reset),
        false,
    )
    check("escapes alone are not preformatted", isPreformatted(red + reset), false)

    // --- visibleColumns: the width arithmetic the thresholds rest on ---------
    check("ascii columns", visibleColumns("abc"), 3)
    check("CJK columns are doubled", visibleColumns("中文"), 4)
    check("trailing whitespace is trimmed", visibleColumns("ab  "), 2)
    check("a tab is 4 columns", visibleColumns("a\tb"), 6)
    check("emoji are double-width", visibleColumns("🚀"), 2)
    check("escapes are invisible", visibleColumns(red + reset + "ab"), 2)

    // --- selectNeedsSheet: both thresholds, boundary included ----------------
    check("an empty option list never needs a sheet", selectNeedsSheet(emptyList()), false)
    check("8 short options stay in the dialog", selectNeedsSheet(List(8) { 6 }), false)
    check("9 short options move to a sheet", selectNeedsSheet(List(9) { 6 }), true)
    check(
        "labels exactly at the column fit stay in the dialog",
        selectNeedsSheet(List(3) { SelectSheetWideLabelColumns }),
        false,
    )
    check(
        "one label a column past the fit moves to a sheet",
        selectNeedsSheet(listOf(10, SelectSheetWideLabelColumns + 1, 10)),
        true,
    )
    check(
        "the thresholds themselves are the documented values",
        "$SelectSheetMinOptions/$SelectSheetWideLabelColumns/$PreformatWideColumns",
        "9/36/80",
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
