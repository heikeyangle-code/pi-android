package app.pi.packages

// A bare-JVM harness for `PiOfficialCatalog.kt` — pi 官方随包发的模型目录。
// Registered in `tools/run-app-pure-checks.sh` as `official-catalog`.
//
// Why this must be pinned: this reader replaces a hand-maintained provider table, so a
// silent failure here is "the provider list is quietly short" — the exact class of bug
// that made the user ask for the official file in the first place. Every failure path
// (missing file, hash mismatch, unparseable JSON, empty manifest) therefore has to
// produce a **problem line**, not a smaller list, and the field mapping (images from
// `input`, cost in pi's per-million unit, absent-vs-false) is pinned field by field.

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private const val ALPHA = """
{"anthropic-messages":{"claude-x":{"id":"claude-x","name":"Claude X","api":"anthropic-messages",
"provider":"alpha","baseUrl":"https://api.alpha.example","reasoning":true,
"input":["text","image"],"cost":{"input":10,"output":50,"cacheRead":1,"cacheWrite":12.5},
"contextWindow":1000000,"maxTokens":128000}},
"alpha-completions":{"alpha-mini":{"id":"alpha-mini","provider":"alpha",
"api":"alpha-completions","baseUrl":"https://api.alpha.example/v1","input":["text"],
"contextWindow":32000,"maxTokens":4096}}}
"""

private const val BETA = """{"beta-api":{"beta-1":{"id":"beta-1","provider":"beta","name":"Beta One"}}}"""

private fun manifest(entries: Map<String, String>): String =
    """{"schemaVersion":3,"generatedAt":"2026-01-01T00:00:00.000Z","structureHash":"x","files":{""" +
        entries.entries.joinToString(",") { (name, hash) -> "\"$name\":\"$hash\"" } +
        "}}"

fun main() {
    val alphaHash = PiOfficialCatalog.sha256Hex(ALPHA)
    val betaHash = PiOfficialCatalog.sha256Hex(BETA)

    // ---------------------------------------------------------------- happy path
    val files = mapOf("alpha.json" to ALPHA, "beta.json" to BETA)
    val catalog = PiOfficialCatalog.read(
        manifest(mapOf("alpha.json" to alphaHash, "beta.json" to betaHash)),
    ) { name -> files[name] }

    check("both providers are read", catalog.providers.map { it.id }, listOf("alpha", "beta"))
    check("nothing to report", catalog.problems, emptyList<String>())

    val alpha = catalog.providers.first { it.id == "alpha" }
    check("api groups are merged into one provider", alpha.models.map { it.id }, listOf("claude-x", "alpha-mini"))
    val claude = alpha.models.first()
    check("name", claude.name, "Claude X")
    check("api", claude.api, "anthropic-messages")
    check("baseUrl", claude.baseUrl, "https://api.alpha.example")
    check("reasoning", claude.reasoning, true)
    check("input with image means images", claude.acceptsImages, true)
    check("input without image is an explicit no", alpha.models[1].acceptsImages, false)
    check("contextWindow", claude.contextWindow, 1000000L)
    check("maxTokens", claude.maxTokens, 128000L)
    check("cost stays in pi's per-million unit", claude.costInputPerMillion, 10.0)
    check("output cost too", claude.costOutputPerMillion, 50.0)
    check("a model without cost says null, not zero", alpha.models[1].costInputPerMillion, null)

    // A model with no `input` array at all: the vendor said nothing, which is not "no".
    val noInput = PiOfficialCatalog.read(
        manifest(mapOf("gamma.json" to PiOfficialCatalog.sha256Hex("""{"g":{"g-1":{"id":"g-1"}}}"""))),
    ) { """{"g":{"g-1":{"id":"g-1"}}}""" }
    check("absent input stays null", noInput.providers.first().models.first().acceptsImages, null)

    // ------------------------------------------------------------ failure paths
    val mismatched = PiOfficialCatalog.read(
        manifest(mapOf("alpha.json" to alphaHash, "beta.json" to "deadbeef")),
    ) { name -> files[name] }
    check("a hash mismatch drops only that file", mismatched.providers.map { it.id }, listOf("alpha"))
    check("and it is reported", mismatched.problems.size, 1)
    check("with the file named", mismatched.problems.first().startsWith("beta.json"), true)

    val missing = PiOfficialCatalog.read(manifest(mapOf("alpha.json" to alphaHash, "gone.json" to "x"))) { name ->
        files[name]
    }
    check("a missing file drops only that file", missing.providers.map { it.id }, listOf("alpha"))
    check("and it is reported as unreadable", missing.problems.first().contains("读不到"), true)

    // The hash must match for this case to reach the *parser* — otherwise it would be
    // caught one step earlier and this would be testing the wrong branch.
    val junkText = "{not json"
    val junk = PiOfficialCatalog.read(
        manifest(mapOf("alpha.json" to PiOfficialCatalog.sha256Hex(junkText))),
    ) { junkText }
    check("unparseable json drops the file", junk.providers, emptyList<PiOfficialCatalog.Provider>())
    check("and is reported", junk.problems.first().contains("解析失败"), true)

    val noFiles = PiOfficialCatalog.read("""{"schemaVersion":3}""") { null }
    check("a manifest without a files table is a problem, not an empty list", noFiles.problems.size, 1)

    check(
        "a non-JSON manifest is a problem",
        PiOfficialCatalog.read("{oops") { null }.problems.first().contains("解析失败"),
        true,
    )

    // A path separator in a manifest name is refused rather than followed.
    val traversal = PiOfficialCatalog.read(manifest(mapOf("../alpha.json" to alphaHash))) { name -> files[name] }
    check("a name with a path separator is refused", traversal.problems.first().contains("读不到"), true)

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
