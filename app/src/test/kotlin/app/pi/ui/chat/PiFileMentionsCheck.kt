package app.pi.ui.chat

// A bare-JVM harness for the pure half of the `@` file-mention completion
// (`ui/chat/PiFileMentions.kt`). It is compiled and run by
// `tools/run-app-pure-checks.sh`, which needs this harness registered (see the
// patch list in `docs/known-gaps.md` E4): the closure imports nothing from
// `android.*`, only the Kotlin stdlib.
//
// Why pin this at all: none of these rules can be checked on a device here, and
// every one of them is a place where a plausible-looking implementation differs
// from pi in a way a user sees. The `@` trigger has to fire at exactly the token
// boundaries pi uses (`packages/tui/src/autocomplete.ts:7`), the ordering has to be
// pi's own scorer and tie-break chain (`:702-784`), and a pick has to insert the
// string pi inserts - a file followed by a space, a directory not
// (`:412-429`). A wrong shell quoting rule would be worse than a wrong order: the
// query is user input and it is handed to `bash -lc`.
//
// What it deliberately does not cover: which files fd returns. That is fd's own
// behaviour - the layered ignore files, `--hidden`, the `.git` exclusion - and the
// answer here is "run the same binary with the same argv", not "reimplement it",
// so there is nothing to pin in Kotlin.
//
// Run it by hand:
//
//   tools/run-app-pure-checks.sh

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private const val WS = "/workspace"
private const val WS_PI = "/workspace/pi/workspaces/workspace-1"

private fun values(stdout: String, prefix: String, stderr: String = "", plan: PiFileMentions.Plan? = null): List<String> =
    PiFileMentions
        .items(stdout, stderr, plan ?: PiFileMentions.plan(prefix, WS), prefix)
        .map { it.value }

fun main() {
    // ---------------------------------------------------------------- triggering
    // pi's PATH_DELIMITERS (`autocomplete.ts:7`): space, tab, both quotes and `=`.
    // Outside those, an `@` is just text and must not open a list.
    check("a bare @ opens the list", PiFileMentions.prefixOf("@"), "@")
    check("space starts a new token", PiFileMentions.prefixOf("hi @sr"), "@sr")
    check("tab starts a new token", PiFileMentions.prefixOf("hi\t@sr"), "@sr")
    check("a quote starts a new token", PiFileMentions.prefixOf("hi \"@sr"), "@sr")
    check("= starts a new token", PiFileMentions.prefixOf("x=@sr"), "@sr")
    check("inside a word an @ is not a mention", PiFileMentions.prefixOf("mail@host"), null)
    check("a completed mention followed by text is closed", PiFileMentions.prefixOf("@src/ hi"), null)
    check("the prefix follows the caret to the end of the draft", PiFileMentions.prefixOf("@src/ma"), "@src/ma")

    // pi's quoted form (`autocomplete.ts:74-92`): `@"a b` is one token ...
    check("an unclosed quote after @ is one token", PiFileMentions.prefixOf("x @\"my folder/te"), "@\"my folder/te")
    // ... and a quote that is not a mention is not one.
    check("a plain quoted string is not a mention", PiFileMentions.prefixOf("@a \"b"), null)
    // The app-specific accommodation (decision 2 in PiFileMentions' KDoc): the
    // closing quote is inserted, the caret cannot move inside it, so the closed
    // token stays completable.
    check("a closed quoted mention stays open", PiFileMentions.prefixOf("@\"my folder/\""), "@\"my folder/\"")
    check("a closed quoted mention mid-draft stays open", PiFileMentions.prefixOf("see @\"my folder/\""), "@\"my folder/\"")
    check(
        "a closed quoted path that is not a mention is not one",
        PiFileMentions.prefixOf("say \"hi\""),
        null,
    )

    // ------------------------------------------------------------------- parsing
    check("a plain prefix carries no quotes", PiFileMentions.parse("@src/x"), PiFileMentions.Prefix("src/x", false))
    check(
        "an unclosed quoted prefix keeps no quote in the query",
        PiFileMentions.parse("@\"my folder/x"),
        PiFileMentions.Prefix("my folder/x", true),
    )
    check(
        "a closed quoted prefix strips both quotes",
        PiFileMentions.parse("@\"my folder/\""),
        PiFileMentions.Prefix("my folder/", true),
    )

    // ------------------------------------------------------------------ requests
    // pi's scope split (`autocomplete.ts:526-562`): `@src/au` searches inside
    // `src/` and prefixes the results back with it.
    val scoped = PiFileMentions.plan("@src/au", WS)
    check("a scoped query searches in the subdirectory", scoped.scoped.baseDir, "$WS/src/")
    check("a scoped query leaves the remainder as the pattern", scoped.scoped.query, "au")
    check("a scoped query remembers the display prefix", scoped.scoped.displayBase, "src/")
    check("a query without a slash does not need full-path matching", scoped.scoped.fullPath, false)
    check("the fallback searches the whole workspace", scoped.fallback.baseDir, WS)
    check("the fallback keeps the original query", scoped.fallback.query, "src/au")
    check("a query with a slash is matched against the full path", scoped.fallback.fullPath, true)
    check(
        "the shell picks the branch pi's directory check would",
        scoped.shell.startsWith("if [ -d '$WS/src/' ]; then echo '${PiFileMentions.SCOPE_MARKER}scoped' >&2; "),
        true,
    )

    // Absolute and home-relative queries are pi's own behaviour
    // (`autocomplete.ts:537-543`, `:573-575`). Parity, not a new opening.
    check("an absolute query stays absolute", PiFileMentions.plan("@/etc/", WS).scoped.baseDir, "/etc/")
    check("~/ resolves to the guest home", PiFileMentions.plan("@~/", WS, home = "/root").scoped.baseDir, "/root")
    check(
        "a relative .. is normalised the way path.join does",
        PiFileMentions.plan("@../outside/a", WS_PI).scoped.baseDir,
        "/workspace/pi/workspaces/outside/",
    )
    check("an empty query runs fd with no pattern", PiFileMentions.plan("@", WS).scoped.query, "")

    // The flags are pi's verbatim (`autocomplete.ts:132-161`): fd's own ignore
    // handling is left alone, hidden entries are included, `.git` never is.
    val fd = PiFileMentions.plan("@", WS).shell
    check("fd is the runtime's own binary", fd.contains("'${PiFileMentions.FD}'"), true)
    check("the result cap is pi's", fd.contains("'--max-results' '${PiFileMentions.MAX_RESULTS}'"), true)
    check("fd is not told to ignore ignore-files", fd.contains("--no-ignore"), false)
    check("hidden entries are included", fd.contains("'--hidden'"), true)
    check("git's own directory is excluded", fd.contains("'--exclude' '.git' '--exclude' '.git/*' '--exclude' '.git/**'"), true)
    check("symlinks are followed, as pi does", fd.contains("'--follow'"), true)

    // User input goes through the shell, so it must be quoted. A query holding a
    // quote, a `$` or a backtick must reach fd literally.
    check("a single quote is escaped for bash", PiFileMentions.shellQuote("a'b"), "'a'\\''b'")
    check(
        "a query with a quote and a dollar survives the shell",
        PiFileMentions.plan("@a'b\$c", WS).shell.contains("'a'\\''b\$c'"),
        true,
    )

    // ------------------------------------------------------------------- results
    // pi's scorer and tie-break chain (`autocomplete.ts:702-784`): score first,
    // then depth, then path length, then the path itself.
    val readStdout = "readme.md\nread.txt\nsub/read.txt\nthread.md\n"
    check(
        "a query orders exact matches, then prefix, then substring",
        values(readStdout, "@read"),
        listOf("@read.txt", "@readme.md", "@sub/read.txt", "@thread.md"),
    )
    check(
        "an exact base name outranks a prefix match",
        values(readStdout, "@read.txt"),
        listOf("@read.txt", "@sub/read.txt"),
    )
    // A directory's path arrives from fd with a trailing separator and must still
    // score on its own name (`autocomplete.ts:205-217`).
    check(
        "a directory scores on its name, not on the empty string after the slash",
        values("src/\nsrc.txt\n", "@src"),
        listOf("@src/", "@src.txt"),
    )
    check("the list is capped at pi's 20", values((1..25).joinToString("\n") { "f$it.txt" } + "\n", "@f").size, 20)
    check(
        "an empty query is scored flat, so depth decides",
        values("b.txt\nsub/a.txt\na.md\n", "@"),
        listOf("@a.md", "@b.txt", "@sub/a.txt"),
    )
    // fd is asked to exclude .git and pi filters the lines again
    // (`autocomplete.ts:209-211`); both halves are cheap, so both are kept.
    check(
        "git's directory never reaches the list",
        values(".git\n.git/config\nsrc/.git/x\nreal.txt\n", "@"),
        listOf("@real.txt"),
    )

    // Values and labels: the value carries `@` and quotes, the label is the base
    // name with a slash for a directory (`autocomplete.ts:107-121`, `:801-805`).
    // fd's output is relative to `--base-directory`, which for this scoped query is
    // the directory itself, so the line is `test.txt` and the display prefix is
    // added back by the branch (`autocomplete.ts:790-794`).
    val quoted = PiFileMentions.plan("@\"my folder/te", WS)
    val quotedItems = PiFileMentions.items("test.txt\n", "", quoted, "@\"my folder/te")
    check("a path with a space is quoted", quotedItems.first().value, "@\"my folder/test.txt\"")
    check("the label is the bare name", quotedItems.first().label, "test.txt")
    check("the description is the path pi shows", quotedItems.first().description, "my folder/test.txt")

    // The shell's marker decides which branch ran, and the two branches read the
    // same fd output differently: scoped, fd's path is relative to the directory and
    // the prefix is added; unscoped, fd already printed the workspace-relative path.
    val branchPlan = PiFileMentions.plan("@src/au", WS)
    check(
        "the scoped branch re-applies the directory prefix",
        values("au.txt\n", "@src/au", PiFileMentions.SCOPE_MARKER + "scoped", branchPlan),
        listOf("@src/au.txt"),
    )
    check(
        "the marker is what decides how fd's path is read",
        listOf(
            values("src/au.txt\n", "@src/au", PiFileMentions.SCOPE_MARKER + "scoped", branchPlan),
            values("src/au.txt\n", "@src/au", PiFileMentions.SCOPE_MARKER + "unscoped", branchPlan),
        ),
        listOf(listOf("@src/src/au.txt"), listOf("@src/au.txt")),
    )
    check(
        "a directory candidate keeps its trailing slash in the value",
        values("src/\n", "@src"),
        listOf("@src/"),
    )
    // No candidates is a real answer, and the composer draws nothing for it - pi's
    // autocomplete never shows an empty popup (`autocomplete.ts:305`).
    check("no fd output means no candidates", values("", "@read"), emptyList<String>())

    // ------------------------------------------------------------------ insertion
    val file = PiFileMentions.Item(value = "@readme.md", label = "readme.md", description = "readme.md")
    val dir = PiFileMentions.Item(value = "@src/", label = "src/", description = "src")
    val quotedFile = PiFileMentions.Item(
        value = "@\"my folder/test.txt\"",
        label = "test.txt",
        description = "my folder/test.txt",
    )
    check(
        "a file is followed by a space, as pi inserts it",
        PiFileMentions.apply("@read", "@read", file),
        "@readme.md ",
    )
    check(
        "a directory is not, so completion can continue",
        PiFileMentions.apply("@sr", "@sr", dir),
        "@src/",
    )
    check(
        "the mention is replaced, not appended",
        PiFileMentions.apply("look at @read", "@read", file),
        "look at @readme.md ",
    )
    check(
        "a closed quoted mention is replaced as a whole",
        PiFileMentions.apply("@\"my folder/te", "@\"my folder/te", quotedFile),
        "@\"my folder/test.txt\" ",
    )
    check(
        "a quoted directory keeps completing",
        PiFileMentions.apply("@\"my folder/\"", "@\"my folder/\"", PiFileMentions.Item("@\"my folder/sub/\"", "sub/", "my folder/sub")),
        "@\"my folder/sub/\"",
    )
    check("a prefix that is not there changes nothing", PiFileMentions.apply("@read", "@nope", file), "@read")

    // Path arithmetic borrowed from Node's path.join, which pi calls.
    check("a join keeps a trailing separator", PiFileMentions.posixJoin(WS, "src/"), "$WS/src/")
    check("a join normalises ..", PiFileMentions.posixJoin(WS_PI, "../outside/"), "/workspace/pi/workspaces/outside/")
    check("a join of an empty relative part is the base", PiFileMentions.posixJoin("/root", ""), "/root")

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
