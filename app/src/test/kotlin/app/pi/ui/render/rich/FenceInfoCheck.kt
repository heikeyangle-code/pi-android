package app.pi.ui.render.rich

// Bare-JVM harness for `FenceInfo.kt` — the fence info-string parser that
// `docs/plans/rich-rendering-upgrade.md` §三.1 requires before any code-block chrome
// can show a file name and before `RichBlock` can classify a fence.
//
// Why it exists: the current `PiCodeLanguage.normalize()` hands the *whole* info
// string to highlight.js, so `typescript title="src/main.ts"` is looked up as a
// language and the block renders uncoloured. Every rule below is a case that a
// hand-written parser gets wrong silently — the block still renders, just with the
// wrong language or no title — so the expectations are written down here instead.
//
// The corruption cases are the interesting half: an unterminated quote, a bare
// `title`, an `=` separated by spaces, a `<script>` token. The requirement is that
// a broken *attribute* may only cost itself; it must never blank a language that
// was already found, and it must never promote a file-name key to the language.
//
// Run it by hand:
//
//   tools/run-app-pure-checks.sh

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private fun parse(raw: String?): FenceInfo = FenceInfo.parse(raw)

fun main() {
    // ======================================================== A. empty / null
    run {
        check("A1 null is no fence", parse(null), FenceInfo.EMPTY)
        check("A2 empty string is no fence", parse(""), FenceInfo.EMPTY)
        check("A3 whitespace is no fence", parse("   \t\n ").language, "")
        check("A4 empty reports isEmpty", parse("").isEmpty, true)
        check("A5 raw is preserved for whitespace", parse("  ").raw, "  ")
    }

    // ======================================================== B. the plain case
    run {
        check("B1 a bare language", parse("kotlin").language, "kotlin")
        check("B2 lower-cased", parse("Kotlin").language, "kotlin")
        check("B3 upper-cased", parse("TS").language, "ts")
        check("B4 surrounding whitespace", parse("  python \n").language, "python")
        check("B5 no title", parse("python").title, null)
        check("B6 no attributes", parse("python").attributes, emptyMap<String, String>())
        check("B7 raw kept verbatim", parse(" ts\t").raw, " ts\t")
        check("B8 c++ is a language", parse("c++").language, "c++")
        check("B9 c# is a language", parse("C#").language, "c#")
        check("B10 objective-c is a language", parse("objective-c").language, "objective-c")
        check("B11 f# is a language", parse("f#").language, "f#")
        check("B12 empty language is not isEmpty", parse("ts").isEmpty, false)
    }

    // ======================================================= C. title spellings
    run {
        val title = parse("""typescript title="src/main.ts"""")
        check("C1 title from title=", title.language, "typescript")
        check("C2 title value", title.title, "src/main.ts")
        check("C3 title kept in attributes", title.attributes["title"], "src/main.ts")

        val single = parse("""python file='scripts/main.py'""")
        check("C4 single-quoted value", single.title, "scripts/main.py")
        check("C5 language with single quote", single.language, "python")

        val spaced = parse("""js title="my notes.md"""")
        check("C6 spaces inside a quoted value", spaced.title, "my notes.md")
        check("C7 spaces do not leak into attributes", spaced.attributes.size, 1)

        val filename = parse("""kotlin filename="MainActivity.kt"""")
        check("C8 filename alias", filename.title, "MainActivity.kt")
        check("C9 filename language", filename.language, "kotlin")

        val file = parse("""ruby file="app.rb"""")
        check("C10 file alias", file.title, "app.rb")

        val precedence = parse("""ts file="a" filename="b"""")
        check("C11 filename wins over file", precedence.title, "b")

        val precedence2 = parse("""ts file="a" title="c"""")
        check("C12 title wins over file", precedence2.title, "c")

        val precedence3 = parse("""ts title="t" filename="b"""")
        check("C13 title wins over filename", precedence3.title, "t")

        val emptyTitle = parse("""ts title="" file="fallback"""")
        check("C14 an empty title does not hide file", emptyTitle.title, "fallback")
        check("C15 empty title kept as an attribute", emptyTitle.attributes["title"], "")

        val duplicate = parse("""ts title="a" title="b"""")
        check("C16 duplicate key: last wins", duplicate.title, "b")

        val caseKey = parse("""ts Title="X"""")
        check("C17 attribute keys are case-insensitive", caseKey.title, "X")
        check("C18 attribute key is lower-cased", caseKey.attributes.containsKey("title"), true)

        val noEquals = parse("""ts title="a"b="c"""")
        check("C19 adjacent attributes without a space", noEquals.title, "a")
        check("C20 adjacent unknown attribute", noEquals.attributes["b"], "c")
    }

    // ======================================================= D. unknown attributes
    run {
        val info = parse("""ts title="a" data-x=1 line-numbers foo="b c"""")
        check("D1 language unaffected", info.language, "ts")
        check("D2 unknown key preserved", info.attributes["data-x"], "1")
        check("D3 a bare flag is not an attribute", info.attributes["line-numbers"], null)
        check("D4 unknown quoted value", info.attributes["foo"], "b c")
        check("D5 exactly the key=value attributes", info.attributes.size, 3)

        val eq = parse("ts a=b=c")
        check("D6 unquoted value may contain =", eq.attributes["a"], "b=c")

        val blank = parse("ts title= label=x")
        check("D7 empty unquoted value", blank.attributes["title"], "")
        check("D8 empty title stays absent", blank.title, null)
        check("D9 parser continues after an empty value", blank.attributes["label"], "x")
    }

    // ======================================================= E. corruption is local
    run {
        val unclosedAfterLanguage = parse("""ts title="unclosed""")
        check("E1 language survives an unterminated quote", unclosedAfterLanguage.language, "ts")
        check("E2 the broken title is dropped", unclosedAfterLanguage.title, null)
        check("E3 no partial attribute is kept", unclosedAfterLanguage.attributes.isEmpty(), true)

        val unclosedLater = parse("""ts title="ok" bad="oops""")
        check("E4 earlier attributes survive", unclosedLater.title, "ok")
        check("E5 the later broken attribute is dropped", unclosedLater.attributes["bad"], null)
        check("E6 language still survives", unclosedLater.language, "ts")

        check(
            "E7 a bare file-name key is not a language",
            parse("title ts").language,
            "ts",
        )
        check(
            "E8 a bare file-name key alone names nothing",
            parse("title").language,
            "",
        )
        check(
            "E9 spaced = degrades the attribute but keeps the language",
            parse("""ts title = "x"""").language,
            "ts",
        )
        check(
            "E10 spaced = is not promoted to title",
            parse("""ts title = "x"""").title,
            null,
        )
        check(
            "E11 a stray quote does not eat the language",
            parse("""ts"x"""").language,
            "ts",
        )
        val stray = parse("""ts "x""")
        check("E12 a stray quoted token is ignored", stray.language, "ts")
        check("E13 no attribute from a stray quote", stray.attributes.isEmpty(), true)
        check(
            "E14 an unterminated quote before the language leaves it empty",
            parse("""bad="x ts"""").language,
            "",
        )
        check(
            "E15 an invalid token is not a language",
            parse("<script> ts").language,
            "ts",
        )
        check(
            "E16 an invalid attribute name is dropped",
            parse("ts <x>=1").attributes.isEmpty(),
            true,
        )
        check(
            "E17 the language before an invalid attribute survives",
            parse("ts <x>=1").language,
            "ts",
        )
    }

    // ======================================================= F. braces and dots
    run {
        check(
            "F1 Pandoc-style braces are separators",
            parse("{.kotlin}").language,
            "kotlin",
        )
        check(
            "F2 braces after a language still parse the attribute",
            parse("""ts {title="a b"}""").title,
            "a b",
        )
        check(
            "F3 a leading dot is dropped from the language",
            parse(".json").language,
            "json",
        )
        check(
            "F4 braces with spaces work",
            parse("{ .python }").language,
            "python",
        )
    }

    // ======================================================= G. no execution
    run {
        val hostile = parse("ts title=\"\$HOME \$(rm -rf /) <script>\"")
        check("G1 values are not expanded", hostile.title, "\$HOME \$(rm -rf /) <script>")
        check("G2 no shell execution changed the language", hostile.language, "ts")

        val escaped = parse("""ts title="a\"b"""")
        check("G3 an escaped quote is decoded", escaped.title, "a\"b")
        val escapedBackslash = parse("""ts title="a\\b"""")
        check("G4 an escaped backslash is decoded", escapedBackslash.title, "a\\b")
        val notAnEscape = parse("""ts title="a\nb"""")
        check("G5 other escapes are kept literal", notAnEscape.title, "a\\nb")
        val html = parse("""ts title="&lt;b&gt;"""")
        check("G6 HTML entities are not decoded", html.title, "&lt;b&gt;")
    }

    // ======================================================= H. length bounds
    run {
        val longLanguage = "x".repeat(60)
        check("H1 an over-long word is not a language", parse(longLanguage).language, "")
        check(
            "H2 an over-long language does not stop later words",
            parse("$longLanguage ts").language,
            "ts",
        )
        val longValue = "x".repeat(2049)
        val dropped = parse("""ts title="$longValue"""")
        check("H3 an over-long value drops the attribute", dropped.title, null)
        check("H4 and does not invent another one", dropped.attributes.isEmpty(), true)
        check("H5 the language still survives", dropped.language, "ts")
        val atLimit = "x".repeat(2048)
        check("H6 the value at the limit is kept", parse("ts title=\"$atLimit\"").title?.length, 2048)
        check("H7 an over-long attribute name is dropped", parse("ts " + "k".repeat(65) + "=v").attributes.isEmpty(), true)
    }

    // ======================================================= I. exact raw text
    run {
        val raw = """javascript title='src/a b.js' foo=bar"""
        val info = parse(raw)
        check("I1 raw is the exact input", info.raw, raw)
        check("I2 language", info.language, "javascript")
        check("I3 title", info.title, "src/a b.js")
        check("I4 unknown attribute", info.attributes["foo"], "bar")
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
