package app.pi.packages

// A bare-JVM harness for the import's `settings.json` decisions: `ModelSelectionPlan.kt`.
//
// Why this needs a harness: the user's bug report was "导入完了也有 bug", and the defect
// was two unconditional writes — every save set `defaultProvider`/`defaultModel`, and
// replaced `enabledModels` wholesale with only the current provider's entries
// (`PiConfigFiles.kt` original `selectModel`). The visible results: adding one model to
// an existing provider silently switched the default model, and importing a second
// provider wiped the first provider's cycle entries plus any user-written patterns
// (`claude-*`, …). Both failures are *omissions of a condition*, which is exactly the
// class a compiler cannot see and this repository keeps paying for (§M11/§M12,
// `docs/known-gaps.md`). Compose cannot be compiled on this machine, so the decision
// lives in a pure object and is proven here. Registered in
// `tools/run-app-pure-checks.sh` as `model-selection-plan`.
//
// The checks are grouped by the question each one answers:
//
//   1. 没动就不写 —— 勾选没变 ⇒ `enabledModels` 一个字都不写
//   2. 保留谁     —— 本厂条目整组替换；别的厂商与手写 glob 原样活下来
//   3. 删键语义   —— 全部取消勾选 ⇒ 空列表（调用方删键），不是 `[]`
//
// 「设为默认」那一节（`setAsDefault` / `requestedDefaultModelId` ⇒ `writeDefault`）已经
// 删除：那个复选框从来没做出来，两个键现在只由「模型与供应商」清单页的「默认模型」一行写
// （`DefaultModelPairStore`）。死路径按用户裁决删掉，这里不再钉一个不存在的入口。
//
// Android-free: the file under test imports nothing but Kotlin stdlib.

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

fun main() {
    // ------------------------------------------------- 1 没动就不写（B1 主回归）

    val untouched = ModelSelectionPlan.settings(
        // 用户已有的：别的厂商一条、跨厂商 glob 一条、本厂一个限定条目、一个裸 id。
        existingPatterns = listOf(
            "anthropic/claude-3",
            "claude-*",
            "deepseek/deepseek-chat",
            "deepseek-reasoner",
        ),
        providerId = "deepseek",
        checkedIds = listOf("deepseek-chat", "deepseek-reasoner"), // 与保存前一致
        configuredIds = setOf("deepseek-chat", "deepseek-reasoner"),
    )
    check("unchanged selection: enabledModels is not written", untouched.enabledModels, null)

    // 只改了 Base URL 这类与勾选无关的字段：同一个判定路径，仍然什么都不写 ——
    // 这正是保护本厂 glob（`deepseek/*` 不被重写成写死 id）的那一条。
    val urlOnly = ModelSelectionPlan.settings(
        existingPatterns = listOf("deepseek/*"),
        providerId = "deepseek",
        checkedIds = listOf("deepseek-chat"),
        configuredIds = setOf("deepseek-chat"),
    )
    check("a url-only edit leaves a provider glob untouched", urlOnly.enabledModels, null)

    // ------------------------------------- 2 本厂整组替换、别人的条目活下来

    val merged = ModelSelectionPlan.settings(
        existingPatterns = listOf(
            "anthropic/claude-3",   // 别的厂商：必须活
            "claude-*",             // 手写跨厂商 glob：必须活
            "deepseek/deepseek-chat", // 本厂限定条目：被这次勾选替换
            "deepseek-chat",        // 本厂裸 id（用户手写）：必须被收编，否则重复
            "deepseek/*",           // 本厂 glob：动了勾选就退场，被写死的勾选替换
        ),
        providerId = "deepseek",
        checkedIds = listOf("deepseek-chat", "deepseek-reasoner"),
        configuredIds = setOf("deepseek-chat"),
    )
    check(
        "other providers and foreign globs survive a rewrite",
        merged.enabledModels,
        listOf(
            "anthropic/claude-3",
            "claude-*",
            "deepseek/deepseek-chat",
            "deepseek/deepseek-reasoner",
        ),
    )
    check("kept entries stay in front, qualified entries appended", merged.enabledModels?.lastOrNull(),
        "deepseek/deepseek-reasoner")

    // 第一次导入（保存前什么都没配）：全部勾选 → 全部限定名写入。
    val firstImport = ModelSelectionPlan.settings(
        existingPatterns = listOf("anthropic/claude-3"),
        providerId = "ollama",
        checkedIds = listOf("qwen3", "llama3"),
        configuredIds = emptySet(),
    )
    check(
        "a first import adds only its own qualified entries",
        firstImport.enabledModels,
        listOf("anthropic/claude-3", "ollama/qwen3", "ollama/llama3"),
    )

    // ------------------------------------------------------ 3 删键语义

    val uncheckAll = ModelSelectionPlan.settings(
        existingPatterns = listOf("deepseek/deepseek-chat"),
        providerId = "deepseek",
        checkedIds = emptyList(),
        configuredIds = setOf("deepseek-chat"),
    )
    check(
        "unchecking everything yields an empty list (caller removes the key, never writes [])",
        uncheckAll.enabledModels,
        emptyList<String>(),
    )

    // 直接钉合并函数：providerModelIds 覆盖 configured ∪ checked 两侧的裸 id。
    check(
        "merge收编 configured 与 checked 两侧的裸 id",
        ModelSelectionPlan.mergeEnabledModels(
            existing = listOf("gpt-4o", "deepseek-chat", "deepseek-reasoner", "groq/llama"),
            providerId = "deepseek",
            checkedIds = listOf("deepseek-chat"),
            providerModelIds = setOf("deepseek-chat", "deepseek-reasoner"),
        ),
        listOf("gpt-4o", "groq/llama", "deepseek/deepseek-chat"),
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
