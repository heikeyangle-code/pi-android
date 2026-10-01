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
//
// 0.99.2 的 fixture 不再是 0.87.1 的形状。真形状（report-B §1.1）是：manifest
// `schemaVersion: 6`、外层按 api 分组后内层键是 `<type>:<id>`、条目自带 `type`。
// 三条 fixture 各自钉住一个旧代码会漏掉的失败：
//   * ALPHA 的 `image:claude-image-1` —— image-only 条目，不得出现在 chat 列表里；
//   * MIXED 的 `image:shared-1` 在 `chat:shared-1` **之前** —— 旧代码只按 id 去重，
//     会把图片模型留下，所以这条钉的是"光靠 putIfAbsent 不够"；
//   * TYPESAFE 整份只有一个 `classifier:jev-latest`（0.99.2 的真文件）—— 旧代码会
//     凭空多出一个可导入的 `typesafe` 厂商，并把 `jev-latest` 当成 chat 模型。

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

// 0.99.2 `deepseek.json` 的形状，两个 api 分组 + 一条 image-only 条目
// （image 条目没有 `reasoning` / `maxTokens`，正是 ImageModel 的样子）。
// 键名 `chat:` / `image:` 来自 `packages/ai/scripts/generate-models.ts:3506-3511`，
// 条目里的 `type` 来自 `:3443`（chat）与 `:3445-3456`（image / classifier）。
private const val ALPHA = """
{"anthropic-messages":{"chat:claude-x":{"id":"claude-x","name":"Claude X","api":"anthropic-messages",
"provider":"alpha","baseUrl":"https://api.alpha.example","reasoning":true,
"input":["text","image"],"cost":{"input":10,"output":50,"cacheRead":1,"cacheWrite":12.5},
"contextWindow":1000000,"maxTokens":128000,"type":"chat"},
"image:claude-image-1":{"id":"claude-image-1","name":"Claude Image","api":"anthropic-messages",
"provider":"alpha","baseUrl":"https://api.alpha.example","input":["text"],
"cost":{"input":3,"output":15},"type":"image"}},
"alpha-completions":{"chat:alpha-mini":{"id":"alpha-mini","provider":"alpha",
"api":"alpha-completions","baseUrl":"https://api.alpha.example/v1","input":["text"],
"contextWindow":32000,"maxTokens":4096,"type":"chat"}}}
"""

// 同一个 id 在两种类型下各一条，image 在前：`Object.fromEntries` 的键序只能保证
// `chat:` 排在 `image:` 前面（`generate-models.ts:3468-3476`），而这里 image 在前。
private const val MIXED = """
{"mixed-api":{"image:shared-1":{"id":"shared-1","name":"Shared Image","api":"mixed-api",
"provider":"mixed","baseUrl":"https://api.mixed.example","input":["text"],"type":"image"},
"chat:shared-1":{"id":"shared-1","name":"Shared Chat","api":"mixed-api","provider":"mixed",
"baseUrl":"https://api.mixed.example","reasoning":true,"input":["text"],
"contextWindow":64000,"maxTokens":8192,"type":"chat"}}}
"""

// 0.99.2 `…/pi-ai/dist/providers/data/typesafe.json` 的逐字形状：整份没有 chat 条目。
private const val TYPESAFE = """
{"typesafe-system-one":{"classifier:jev-latest":{"type":"classifier","id":"jev-latest","name":"Jev",
"api":"typesafe-system-one","provider":"typesafe","baseUrl":"https://api.typesafe.ai/v1/",
"input":["text"],"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0},"contextWindow":64000}}}
"""

// 清单本体。`schemaVersion` 默认就用 App 认的那个值（那是"正常"清单）；
// 传别的值或 null 用来造"格式又变了"和"版本字段缺席"两种清单。
private fun manifest(
    entries: Map<String, String>,
    schemaVersion: Int? = PiOfficialCatalog.MODEL_DATA_SCHEMA_VERSION,
): String =
    "{" + (schemaVersion?.let { "\"schemaVersion\":$it," } ?: "") +
        "\"generatedAt\":\"2026-01-01T00:00:00.000Z\",\"structureHash\":\"x\",\"files\":{" +
        entries.entries.joinToString(",") { (name, hash) -> "\"$name\":\"$hash\"" } +
        "}}"

fun main() {
    val alphaHash = PiOfficialCatalog.sha256Hex(ALPHA)
    val mixedHash = PiOfficialCatalog.sha256Hex(MIXED)
    val typesafeHash = PiOfficialCatalog.sha256Hex(TYPESAFE)

    // ---------------------------------------------------------------- happy path
    val files = mapOf("alpha.json" to ALPHA, "mixed.json" to MIXED, "typesafe.json" to TYPESAFE)
    val catalog = PiOfficialCatalog.read(
        manifest(
            mapOf("alpha.json" to alphaHash, "mixed.json" to mixedHash, "typesafe.json" to typesafeHash),
        ),
    ) { name -> files[name] }

    check(
        "只有 chat 的厂商被读出，classifier-only 的文件不是一个厂商",
        catalog.providers.map { it.id },
        listOf("alpha", "mixed"),
    )
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
    check("chat 条目的 type 是 chat", claude.type, "chat")

    // 这三条钉住"image / classifier 不得进 chat 列表"：改回 0.99.2 之前的读法（不看
    // `type`、只按 id 去重）时，前两条会红。
    check("image-only 条目不在 chat 列表里", alpha.models.any { it.id == "claude-image-1" }, false)
    check(
        "classifier-only 的 id 不在任何 chat 列表里",
        catalog.providers.flatMap { it.models }.any { it.id == "jev-latest" },
        false,
    )

    // 同一个 id 两条不同类型、image 在前：旧代码的 putIfAbsent 会把 `Shared Image` 留下
    // （name/contextWindow 取自 image 条目），所以 user-visible 的 name 就是那条判据。
    val mixed = catalog.providers.first { it.id == "mixed" }
    check("同 id 两种类型时留下的是 chat 条目", mixed.models.map { it.id to it.name }, listOf("shared-1" to "Shared Chat"))
    check("and it keeps the chat entry's own metadata", mixed.models.first().contextWindow, 64000L)
    check("and its type", mixed.models.first().type, "chat")

    // A model with no `input` array at all: the vendor said nothing, which is not "no".
    // 同时：没有 `type` 字段的条目按 chat 处理（pi `utils/model-operations.ts:17-19`），
    // 不能被过滤掉 —— 那是 0.87.1 形状的条目在 0.99.2 目录里仍然合法的那一半。
    val noInput = PiOfficialCatalog.read(
        manifest(mapOf("gamma.json" to PiOfficialCatalog.sha256Hex("""{"g":{"g-1":{"id":"g-1"}}}"""))),
    ) { """{"g":{"g-1":{"id":"g-1"}}}""" }
    check("absent input stays null", noInput.providers.first().models.first().acceptsImages, null)
    check("没有 type 字段的条目仍然是 chat", noInput.providers.first().models.first().type, "chat")

    // ------------------------------------------------- schemaVersion 这条断言
    // 版本不等 = 一句话进 problems，但目录仍然按已知规则尽力解析（升一次 pi 不该让整张
    // 表凭空消失；把这句话显示出来就等于"格式又变了"从静默变成可见）。
    val oldSchema = PiOfficialCatalog.read(
        manifest(mapOf("alpha.json" to alphaHash), schemaVersion = 3),
    ) { name -> files[name] }
    check("认不出的 schemaVersion 会报告", oldSchema.problems.size, 1)
    check(
        "那句话里两个版本号都在",
        oldSchema.problems.first().contains("schemaVersion") &&
            oldSchema.problems.first().contains("3") &&
            oldSchema.problems.first().contains("6"),
        true,
    )
    check("但目录仍然被读出来", oldSchema.providers.map { it.id }, listOf("alpha"))

    val noVersion = PiOfficialCatalog.read(
        manifest(mapOf("alpha.json" to alphaHash), schemaVersion = null),
    ) { name -> files[name] }
    check("schemaVersion 缺席也会报告", noVersion.problems.first().contains("schemaVersion"), true)
    check("and the catalog is read anyway", noVersion.providers.map { it.id }, listOf("alpha"))

    // ------------------------------------------------------------ failure paths
    val mismatched = PiOfficialCatalog.read(
        manifest(mapOf("alpha.json" to alphaHash, "mixed.json" to "deadbeef")),
    ) { name -> files[name] }
    check("a hash mismatch drops only that file", mismatched.providers.map { it.id }, listOf("alpha"))
    check("and it is reported", mismatched.problems.size, 1)
    check("with the file named", mismatched.problems.first().startsWith("mixed.json"), true)

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

    val noFiles = PiOfficialCatalog.read("""{"schemaVersion":${PiOfficialCatalog.MODEL_DATA_SCHEMA_VERSION}}""") { null }
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
