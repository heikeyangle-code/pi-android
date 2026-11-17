package app.pi.ui.models

// 裸 JVM harness：「模型与供应商」页的全部判断（连接状态、分区、搜索、分组、价格与上下文文案、
// 刷新结果、id 校验）。页面本身只画这里算好的东西。

import kotlinx.serialization.json.Json

private var failures = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) println("PASS $name") else { failures++; println("FAIL $name $detail") }
}

private fun p(
    id: String, name: String = id, configured: Boolean = false, oauth: Boolean = false, sub: Boolean = false,
    source: String? = null, offersSub: Boolean = false, models: Int = 0, custom: Boolean = false,
) = ProviderFacts(id, name, configured, oauth, sub, source, offersSub, models, custom)

private fun m(
    provider: String, id: String, name: String = id, reasoning: Boolean = false, images: Boolean = false,
    ctx: Long? = null, inCost: Double? = null, outCost: Double? = null,
) = ModelFacts(provider, id, name, reasoning, images, ctx, inCost, outCost)

fun main() {
    // ---- 连接状态：订阅 ≠ 任意 OAuth ----
    check("未配置就是未连接，即使 usingOAuth 残留", connectionOf(p("a", oauth = true, sub = true)) == Connection.None)
    check("订阅账号", connectionOf(p("a", configured = true, oauth = true, sub = true)) == Connection.Subscription)
    check("非订阅型 OAuth 叫网页登录，不叫订阅", connectionOf(p("a", configured = true, oauth = true, sub = false)) == Connection.WebLogin)
    check("凭据来源：stored / environment / runtime",
        connectionOf(p("a", configured = true, source = "stored")) == Connection.Saved &&
            connectionOf(p("a", configured = true, source = "environment")) == Connection.Environment &&
            connectionOf(p("a", configured = true, source = "runtime")) == Connection.Temporary)
    check("配置里写的来源（models.json 的 apiKey）= 已连接", connectionOf(p("a", configured = true, source = "models.json")) == Connection.Configured &&
        connectionOf(p("a", configured = true, source = null)) == Connection.Configured)
    check("connected 只有「未连接」为假", Connection.entries.all { it.connected == (it != Connection.None) })
    check("每个状态有不重复的标签", Connection.entries.map { it.label }.toSet().size == Connection.entries.size)

    // ---- 分区 ----
    val all = listOf(
        p("zai", "Z.ai"), p("anthropic", "Anthropic", offersSub = true), p("openai", "OpenAI", configured = true, source = "stored"),
        p("github-copilot", "GitHub Copilot", offersSub = true), p("groq", "Groq"), p("mine", "", configured = true, custom = true),
    )
    val s = sectionProviders(all, "")
    check("已连接按名字排（空名字用 id）", s.connected.map { it.id } == listOf("mine", "openai"), s.connected.map { it.id }.toString())
    check("订阅入口单独成段、按名字排", s.subscriptionOffers.map { it.id } == listOf("anthropic", "github-copilot"))
    check("其余未连接折在最后", s.others.map { it.id } == listOf("groq", "zai"))
    check("三段不重不漏", (s.connected + s.subscriptionOffers + s.others).map { it.id }.toSet() == all.map { it.id }.toSet() &&
        s.connected.size + s.subscriptionOffers.size + s.others.size == all.size)
    val q = sectionProviders(all, "git")
    check("搜索命中名字或 id，三段同时过滤", q.subscriptionOffers.map { it.id } == listOf("github-copilot") && q.connected.isEmpty() && q.others.isEmpty())
    check("搜索不分大小写", sectionProviders(all, "OPENAI").connected.map { it.id } == listOf("openai"))

    // ---- 搜索词 ----
    check("多个词全部命中才算，顺序不限", matches("sonnet 4", "claude-sonnet-4-5") && matches("4 sonnet", "claude-sonnet-4-5") && !matches("sonnet 9", "claude-sonnet-4-5"))
    check("词可以分别命中不同字段", matches("claude opus", "opus-4", "Claude"))
    check("空查询与纯空白命中一切", matches("", "x") && matches("   ", "x"))

    // ---- 头像 ----
    check("头像档位稳定且在范围内", avatarSeed("anthropic") == avatarSeed("anthropic") && listOf("a", "openai", "智谱", "", "x".repeat(500)).all { avatarSeed(it) in 0 until AVATAR_TONES })
    check("不同 id 会分散到多档", listOf("anthropic", "openai", "google", "groq", "mistral", "xai", "zai", "deepseek").map { avatarSeed(it) }.toSet().size >= 3)
    check("头像字：首个字母/数字大写，跳过符号", avatarInitial("openai") == "O" && avatarInitial("  --z.ai") == "Z" && avatarInitial("智谱") == "智" && avatarInitial("...") == "?" && avatarInitial("") == "?")

    // ---- 上下文与价格文案 ----
    check("上下文：K / M", contextLabel(200_000) == "200K" && contextLabel(128_000) == "128K" && contextLabel(1_000_000) == "1M" && contextLabel(1_500_000) == "1.5M" && contextLabel(2_000_000) == "2M")
    check("上下文：小于 1000 原样；非正数与 null 没有标签", contextLabel(512) == "512" && contextLabel(0) == null && contextLabel(-5) == null && contextLabel(null) == null)
    check("上下文：不会写出「1000K」", contextLabel(999_900) == "1M" && contextLabel(999_400) == "999K", contextLabel(999_900).toString())
    check("价格：整数不带小数点", priceLabel(3.0, 15.0) == "\$3 / \$15", priceLabel(3.0, 15.0).toString())
    check("价格：小数去尾零、保留有效位", priceLabel(0.25, 1.25) == "\$0.25 / \$1.25" && priceLabel(0.075, 0.3) == "\$0.075 / \$0.3" && priceLabel(100.0, 0.5) == "\$100 / \$0.5")
    check("价格：缺一项或非法就没有标签", priceLabel(null, 1.0) == null && priceLabel(1.0, null) == null && priceLabel(-1.0, 1.0) == null && priceLabel(Double.NaN, 1.0) == null)
    check("免费模型显示 $0 / $0（而不是不显示）", priceLabel(0.0, 0.0) == "\$0 / \$0", priceLabel(0.0, 0.0).toString())
    check("能力标签顺序固定", capabilityTags(m("a", "x", reasoning = true, images = true, ctx = 200_000)) == listOf("推理", "看图", "200K 上下文"))
    check("没有能力就没有标签", capabilityTags(m("a", "x")).isEmpty())
    check("模型名为空用 id", m("a", "id1", name = " ").title == "id1" && m("a", "id1", name = "N").title == "N")

    // ---- 模型搜索与分组 ----
    val models = listOf(
        m("openai", "gpt-5", "GPT-5"), m("anthropic", "claude-sonnet-4-5", "Claude Sonnet 4.5"),
        m("anthropic", "claude-opus-4-1", "Claude Opus 4.1"), m("google", "gemini-2.5-pro", "Gemini 2.5 Pro"),
    )
    val names = mapOf("openai" to "OpenAI", "anthropic" to "Anthropic", "google" to "Google")
    check("按供应商名也能搜到", searchModels(models, names, "anthropic").size == 2)
    check("按名字与 id 搜", searchModels(models, names, "sonnet 4.5").map { it.id } == listOf("claude-sonnet-4-5"))
    val groups = groupModels(models, names, currentProvider = "openai")
    check("当前模型的供应商排第一，其余按名字", groups.map { it.providerId } == listOf("openai", "anthropic", "google"), groups.map { it.providerId }.toString())
    check("组内保持目录原顺序", groups.first { it.providerId == "anthropic" }.models.map { it.id } == listOf("claude-sonnet-4-5", "claude-opus-4-1"))
    check("没有当前模型就纯按名字", groupModels(models, names, null).map { it.providerId } == listOf("anthropic", "google", "openai"))
    check("缺名字的供应商用 id", groupModels(listOf(m("zzz", "x")), emptyMap(), null).single().providerName == "zzz")
    check("sameAs 要供应商与 id 都对上", models[0].sameAs("openai", "gpt-5") && !models[0].sameAs("anthropic", "gpt-5") && !models[0].sameAs("openai", null))
    check("两条使用路径：只有「设为默认」写 persist", UseScope.Session.persist.not() && UseScope.Default.persist && UseScope.entries.map { it.label }.toSet().size == 2)

    // ---- 刷新结果（宿主已把 Map 摊成对象）----
    fun parse(s: String) = refreshSummary(Json.parseToJsonElement(s))
    check("全成功", parse("""{"aborted":false,"errors":{}}""").ok && parse("""{"aborted":false,"errors":{}}""").sentence(names) == "已刷新。")
    check("读不到结果按成功算，不乱报错", refreshSummary(null).ok)
    val bad = parse("""{"aborted":false,"errors":{"openai":"fetch failed","google":"401","zzz":""}}""")
    check("失败点名并带原因", !bad.ok && bad.sentence(names).contains("Google") && bad.sentence(names).contains("OpenAI") && bad.detail(names).contains("OpenAI：fetch failed") && bad.detail(names).contains("zzz"), bad.sentence(names))
    check("失败超过三家折成「等 N 家」", parse("""{"aborted":false,"errors":{"a":"x","b":"x","c":"x","d":"x"}}""").sentence(emptyMap()).contains("等 4 家"))
    check("被中断单独一句", parse("""{"aborted":true,"errors":{}}""").sentence(names).contains("中断"))

    // ---- 新增供应商 id ----
    val custom = setOf("mine"); val known = setOf("mine", "anthropic", "openai")
    check("id 为空", checkNewProviderId("  ", custom, known) == IdCheck.Empty)
    check("models.json 里已有 = 冲突", checkNewProviderId("mine", custom, known) == IdCheck.AlreadyCustom)
    check("与内置同名 = 覆盖它，不是错误", checkNewProviderId("anthropic", custom, known) == IdCheck.OverridesBuiltin)
    check("新名字", checkNewProviderId(" my-proxy ", custom, known) == IdCheck.Ok)
    check("id 里可以有斜杠（SDK 按最长已知 id 匹配）", checkNewProviderId("host/path", custom, known) == IdCheck.Ok)
    check("常用协议不重复且都是 KnownApi 字面量", COMMON_APIS.map { it.api }.toSet().size == COMMON_APIS.size &&
        COMMON_APIS.map { it.api }.containsAll(listOf("openai-completions", "anthropic-messages")))

    if (failures > 0) { println("\nharness: FAILED ($failures)"); System.exit(1) }
    println("\nharness: OK (all checks passed)")
}
