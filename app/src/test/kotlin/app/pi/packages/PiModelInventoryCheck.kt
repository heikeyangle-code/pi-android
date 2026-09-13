package app.pi.packages

// A bare-JVM harness for the 设置 → 模型 list: `PiModelInventory.kt`.
//
// Why this needs a harness of its own: the whole point of that file is that it
// answers "why is the model I imported not in the list?" with one of three
// different sentences, and two of them are *inferences* (`PENDING_RESTART`) or
// *silences* (`UNKNOWN`) rather than things pi tells us. An inference that is
// wrong in the quiet direction is the failure this repository keeps paying for
// (§M11/§M12 in `docs/known-gaps.md`): the screen would say "已经生效" while the
// engine has never read the file. Compose cannot be compiled on this machine, so
// the only way to prove any of it is to keep the decisions in a pure object and
// run them here. Registered in `tools/run-app-pure-checks.sh` as
// `models-inventory`.
//
// The checks are grouped by the question each one answers:
//
//   1. 来源：pi 目录 / 手写申报 / 覆盖   —— 三者必须能分开，也允许同时成立
//   2. 状态：已生效 / 等待重启 / 缺凭证 / 读不到引擎
//   3. 选择：enabledModels 的 glob、默认模型、项目级覆盖
//   4. 坏输入：坏 JSON、缺键、注释、空文件 —— 一律不崩、不猜、不出现路径
//
// Android-free: the files under test import kotlinx.serialization.json only.

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private const val MODELS_JSON = """
{
  "providers": {
    "deepseek": { "baseUrl": "https://api.deepseek.com", "api": "openai-completions" },
    "ollama": {
      "name": "Ollama",
      "baseUrl": "http://localhost:11434/v1",
      "api": "openai-completions",
      "models": [ { "id": "qwen3", "name": "Qwen3" } ],
      "modelOverrides": { "llama3": { "contextWindow": 8192 } }
    }
  }
}
"""

private const val AUTH_JSON = """
{ "deepseek": { "type": "api_key", "key": "sk-x" }, "anthropic": { "type": "oauth", "access": "a", "refresh": "r", "expires": 1 } }
"""

private const val CATALOG_JSON = """
{
  "deepseek": { "models": [ { "id": "deepseek-chat", "name": "DeepSeek Chat" }, { "id": "deepseek-reasoner" } ] },
  "ollama": { "models": [ { "id": "qwen3", "name": "Qwen3 (catalog)" } ] }
}
"""

fun main() {
    // ------------------------------------------------------------------ 1 来源

    val inventory = PiModelInventory.assemble(
        modelsJson = MODELS_JSON,
        authJson = AUTH_JSON,
        catalogJson = CATALOG_JSON,
        selection = PiModelInventory.Selection(enabledPatterns = listOf("deepseek/deepseek-chat")),
        engineModels = listOf(PiModelInventory.EngineModel("deepseek", "deepseek-chat")),
    )

    val deepseek = inventory.providers.first { it.id == "deepseek" }
    val ollama = inventory.providers.first { it.id == "ollama" }

    check(
        "providers come from the files, not from a table",
        inventory.providers.map { it.id },
        listOf("anthropic", "deepseek", "ollama"),
    )
    check(
        "a model pi's catalog has is marked as catalog",
        deepseek.models.first { it.id == "deepseek-chat" }.origins,
        setOf(PiModelInventory.Origin.PI_CATALOG),
    )
    check(
        "a model declared in models.json is marked as declared",
        ollama.models.first { it.id == "qwen3" }.origins,
        setOf(PiModelInventory.Origin.PI_CATALOG, PiModelInventory.Origin.DECLARED),
    )
    check(
        "a modelOverrides key is marked as overridden, not as declared",
        ollama.models.first { it.id == "llama3" }.origins,
        setOf(PiModelInventory.Origin.OVERRIDDEN),
    )
    check(
        "the declared name wins over the catalog name",
        ollama.models.first { it.id == "qwen3" }.name,
        "Qwen3",
    )
    check("the provider block keeps its name", ollama.name, "Ollama")
    check("the provider block keeps its endpoint", ollama.baseUrl, "http://localhost:11434/v1")
    // 这两条问的是"**没有** `models.json` 厂商块"的厂商，而 fixture 里那种厂商是
    // `anthropic`（它只在 `auth.json` 里有凭证）。原来它们断言在 `deepseek` 上——而
    // `deepseek` 在 `MODELS_JSON` 里**有**块（`baseUrl`+`api`），所以 `configured`
    // 本该是 true，断言失败是**测试对象写错**，不是实现错。
    val anthropic = inventory.providers.first { it.id == "anthropic" }
    check("a provider with no block has no name", anthropic.name, null)
    check("a provider with no block is not 'configured'", anthropic.configured, false)
    check("a provider with a block is 'configured'", ollama.configured, true)
    check("a block without a name does not invent one", deepseek.name, null)

    // ------------------------------------------------------------------ 2 状态

    check(
        "a model the engine lists is READY",
        deepseek.models.first { it.id == "deepseek-chat" }.status,
        PiModelInventory.Status.READY,
    )
    check(
        "a credentialed provider the engine does not list is PENDING_RESTART",
        deepseek.models.first { it.id == "deepseek-reasoner" }.status,
        PiModelInventory.Status.PENDING_RESTART,
    )
    check(
        "a provider with no credential is MISSING_CREDENTIAL, not PENDING_RESTART",
        ollama.models.first { it.id == "qwen3" }.status,
        PiModelInventory.Status.MISSING_CREDENTIAL,
    )
    check(
        "an oauth credential counts as a credential (its models are importable)",
        inventory.providers.first { it.id == "anthropic" }.hasCredential,
        true,
    )
    check("the restart banner counts providers, not models", inventory.providersPendingRestart.map { it.id }, listOf("deepseek"))

    val engineUnknown = PiModelInventory.assemble(
        modelsJson = MODELS_JSON,
        authJson = AUTH_JSON,
        catalogJson = CATALOG_JSON,
        engineModels = null,
    )
    check("no engine answer means no status claim", engineUnknown.engineKnown, false)
    check(
        "no engine answer makes every model UNKNOWN",
        engineUnknown.models.map { it.status }.distinct(),
        listOf(PiModelInventory.Status.UNKNOWN),
    )
    check(
        "no engine answer is not the same as an empty engine answer",
        PiModelInventory.assemble(
            modelsJson = MODELS_JSON,
            authJson = AUTH_JSON,
            catalogJson = CATALOG_JSON,
            engineModels = emptyList(),
        ).models.first { it.id == "deepseek-chat" }.status,
        PiModelInventory.Status.PENDING_RESTART,
    )

    // A provider the app has no file evidence for can still be listed by the engine
    // (its credential may be an environment variable). It must not vanish.
    val engineOnly = PiModelInventory.assemble(
        modelsJson = null,
        authJson = null,
        catalogJson = null,
        engineModels = listOf(PiModelInventory.EngineModel("groq", "llama-3.3-70b", "Llama 3.3 70B")),
    )
    check(
        "a provider only the engine knows about is still listed",
        engineOnly.providers.map { it.id to it.models.map { m -> m.id } },
        listOf("groq" to listOf("llama-3.3-70b")),
    )
    check(
        "its name comes from the engine",
        engineOnly.providers.first().models.first().name,
        "Llama 3.3 70B",
    )
    check(
        "it is READY without any file-level credential",
        engineOnly.providers.first().models.first().status,
        PiModelInventory.Status.READY,
    )

    // `apiKey` inside models.json is a credential too (`provider-composer.ts:567-580`).
    val modelsJsonKey = PiModelInventory.assemble(
        modelsJson = """{ "providers": { "x": { "baseUrl": "https://x", "api": "openai-completions", "apiKey": "sk-in-file" } } }""",
        authJson = "{}",
        catalogJson = null,
        engineModels = emptyList(),
    )
    check(
        "a key written in models.json counts as a credential",
        modelsJsonKey.providers.first().hasCredential,
        true,
    )

    // ------------------------------------------------------------------ 3 选择

    val selection = PiModelInventory.Selection(
        defaultProvider = "deepseek",
        defaultModel = "deepseek-chat",
        enabledPatterns = listOf("deepseek/deepseek-chat", "deepseek-reasoner", "qwen*", "OPENROUTER/*big*", "none:notalevel"),
    )
    val chosen = PiModelInventory.assemble(
        modelsJson = MODELS_JSON,
        authJson = AUTH_JSON,
        catalogJson = CATALOG_JSON,
        selection = selection,
        engineModels = emptyList(),
    )
    val deepseekModels = chosen.providers.first { it.id == "deepseek" }.models.associateBy { it.id }
    check("an exact provider/id pattern enables", deepseekModels.getValue("deepseek-chat").enabled, true)
    check("a bare id pattern enables", deepseekModels.getValue("deepseek-reasoner").enabled, true)
    check("a glob against the bare id enables", chosen.providers.first { it.id == "ollama" }.models.first { it.id == "qwen3" }.enabled, true)
    check(
        "a pattern (and the id it is matched against) is case-insensitive",
        PiModelInventory.matches("DEEPSEEK/DeepSeek-Chat", "deepseek", "deepseek-chat"),
        true,
    )
    check("an unmatched model is not enabled", engineOnly.providers.first().models.first().enabled, false)
    check("a colon suffix that is not a thinking level stays in the pattern", PiModelInventory.matches("none:notalevel", "none", "notalevel"), false)
    check("a thinking-level suffix is stripped before matching", PiModelInventory.matches("deepseek-reasoner:high", "deepseek", "deepseek-reasoner"), true)
    check("the default model is marked", deepseekModels.getValue("deepseek-chat").isDefault, true)
    check("another model is not the default", deepseekModels.getValue("deepseek-reasoner").isDefault, false)

    val qualifiedDefault = PiModelInventory.Selection(defaultProvider = null, defaultModel = "deepseek/deepseek-chat")
    val qualifiedInventory = PiModelInventory.assemble(
        modelsJson = MODELS_JSON,
        authJson = AUTH_JSON,
        catalogJson = CATALOG_JSON,
        selection = qualifiedDefault,
        engineModels = emptyList(),
    )
    check(
        "a provider-qualified defaultModel marks the same row",
        qualifiedInventory.models.count { it.isDefault },
        1,
    )

    // Unsupported minimatch syntax must be *reported*, never silently treated as
    // "not enabled": a brace pattern is a real thing to write in settings.json.
    val exotic = PiModelInventory.assemble(
        modelsJson = MODELS_JSON,
        authJson = AUTH_JSON,
        catalogJson = CATALOG_JSON,
        selection = PiModelInventory.Selection(enabledPatterns = listOf("{a,b}-*", "qwen*")),
        engineModels = emptyList(),
    )
    check("an unsupported pattern is listed as unjudged", exotic.unjudgedPatterns, listOf("{a,b}-*"))
    check("a supported glob is not unjudged", PiModelInventory.hasUnsupportedGlobSyntax("qwen*"), false)
    check("a brace pattern is unjudged", PiModelInventory.hasUnsupportedGlobSyntax("{a,b}*"), true)
    check("a character class is unjudged", PiModelInventory.hasUnsupportedGlobSyntax("[ab]*"), true)
    check("an empty pattern matches nothing", PiModelInventory.matches("", "a", "b"), false)
    check("a question mark matches exactly one character", PiModelInventory.matches("qwen?", "ollama", "qwen3"), true)
    check("a question mark does not match two characters", PiModelInventory.matches("qwen?", "ollama", "qwen33"), false)

    // ------------------------------------------------------- 4 settings.json

    val globalSettings = """{ "defaultProvider": "deepseek", "defaultModel": "deepseek-chat", "enabledModels": ["deepseek/*"] }"""
    val projectSettings = """{ "defaultModel": "deepseek-reasoner" }"""
    val merged = PiModelInventory.selection(globalSettings, projectSettings)
    check("the project file overrides a scalar key", merged.defaultModel, "deepseek-reasoner")
    check("an untouched key comes from the global file", merged.defaultProvider, "deepseek")
    check("an untouched array comes from the global file", merged.enabledPatterns, listOf("deepseek/*"))
    check(
        "a project array replaces the global array (pi: arrays are not merged)",
        PiModelInventory.selection(globalSettings, """{ "enabledModels": ["a", "b"] }""").enabledPatterns,
        listOf("a", "b"),
    )
    check(
        "no settings file means no selection",
        PiModelInventory.selection(null, null),
        PiModelInventory.Selection(null, null, emptyList()),
    )
    // The type argument is **not** noise: `check`'s parameters are `Any?`, which gives
    // `emptyList()`'s `T` nothing to be inferred from, and the compiler says so
    // ("cannot infer type for type parameter 'T'. Specify it explicitly."). Spelling
    // the element type is the whole fix, at the two call sites where a bare
    // `emptyList()` is handed to `check` — do not "tidy" it back.
    check(
        "a malformed enabledModels value is empty, not a crash",
        PiModelInventory.selection("""{ "enabledModels": "deepseek/*" }""").enabledPatterns,
        emptyList<String>(),
    )

    // ------------------------------------------------------------- 5 坏输入

    val broken = PiModelInventory.assemble(
        modelsJson = "{ this is not json",
        authJson = "[1,2,3]",
        catalogJson = "null",
        engineModels = emptyList(),
    )
    check("an unparseable models.json is reported", broken.modelsJsonError != null, true)
    check("the report names no file path", broken.modelsJsonError?.contains('/') ?: false, false)
    check("an unparseable auth.json is reported", broken.authJsonError != null, true)
    check("a broken models.json does not invent providers", broken.providers, emptyList<PiModelInventory.Provider>())
    check("a catalog that is not an object is just empty", broken.engineKnown, true)

    val missingKey = PiModelInventory.assemble(
        modelsJson = """{ "something": 1 }""",
        authJson = null,
        catalogJson = null,
        engineModels = emptyList(),
    )
    check("a models.json without `providers` is reported", missingKey.modelsJsonError != null, true)
    check("...and the report names no path", missingKey.modelsJsonError?.contains('/') ?: false, false)

    val withComments = PiModelInventory.assemble(
        modelsJson = """
        {
          // the endpoint I actually use
          "providers": {
            "custom": { "baseUrl": "https://example.test", "api": "openai-completions",
                        "models": [ { "id": "m1" } ] /* and one model */ }
          }
        }
        """,
        authJson = null,
        catalogJson = null,
        engineModels = emptyList(),
    )
    check(
        "comments are legal in models.json (pi strips them before parsing)",
        withComments.providers.first().models.map { it.id },
        listOf("m1"),
    )
    check("comments do not turn into an error", withComments.modelsJsonError, null)

    check(
        "an empty file is silence, not an error",
        PiModelInventory.assemble("", "", "", engineModels = emptyList()).modelsJsonError,
        null,
    )
    check(
        "a model entry with no id is skipped, not invented",
        PiModelInventory.assemble(
            modelsJson = """{ "providers": { "p": { "baseUrl": "u", "api": "a", "models": [ { "name": "x" }, { "id": "" } ] } } }""",
            authJson = null,
            catalogJson = null,
            engineModels = emptyList(),
        ).providers.map { it.id to it.models.map { m -> m.id } },
        listOf("p" to emptyList<String>()),
    )
    check(
        "model order inside a provider is stable",
        PiModelInventory.assemble(
            modelsJson = """{ "providers": { "p": { "baseUrl": "u", "api": "a", "models": [ { "id": "b" }, { "id": "a" } ] } } }""",
            authJson = null,
            catalogJson = null,
            engineModels = emptyList(),
        ).providers.first().models.map { it.id },
        listOf("a", "b"),
    )

    // ------------------------------------------- 6 外部改动的判据（PiFileStamps）

    val dir = java.io.File(System.getProperty("java.io.tmpdir"), "pi-stamps-check-${System.nanoTime()}")
    dir.mkdirs()
    val watched = java.io.File(dir, "settings.json")
    val baseline = PiFileStamps.Baseline(listOf(watched))

    check("an absent file has no fingerprint", PiFileStamps.of(watched), null)
    check("the first consume of an unchanged set is quiet", baseline.consume(), false)
    watched.writeText("{}")
    check("a file appearing counts as a change", baseline.consume(), true)
    check("the same change is not reported twice", baseline.consume(), false)
    watched.writeText("""{"theme":"dark"}""")
    check("a bigger file counts as a change", baseline.consume(), true)
    baseline.consume()
    watched.delete()
    check("a file disappearing counts as a change", baseline.consume(), true)
    baseline.consume()
    baseline.reset()
    check("reset makes the next consume report a change", baseline.consume(), true)
    dir.deleteRecursively()

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
