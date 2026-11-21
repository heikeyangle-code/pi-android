package app.pi.ui.models

import app.pi.i18n.tr
import java.math.BigDecimal
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/*
 * 「模型与供应商」页的**全部判断**，不含 Compose、不含宿主类型：页面只负责把这里算好的东西画出来。
 * 这样每一条规则（连接状态怎么判、哪些供应商排前面、价格怎么写、刷新错误怎么说）都有裸 JVM 的
 * harness 钉着（`ModelsPresentationCheck`），而不是埋在某个 `@Composable` 里靠肉眼。
 *
 * 数据来源都是 SDK 的读数，这里不自己发明状态：
 *  - `configured`  = `ModelRuntime.hasConfiguredAuth`
 *  - `source`      = `getProviderAuthStatus().source`（`runtime` / `stored` / `environment` / 配置里写的）
 *  - `usingOAuth` / `usingSubscription` = `isUsingOAuth` / `isUsingSubscription`
 *  订阅 ≠ 任意 OAuth：只有后者为真且该供应商的 OAuth 是订阅账号，才叫「订阅」。
 */

// ------------------------------------------------------------------ 供应商

/** 一个供应商在页面上的连接状态。只由 SDK 的认证读数决定。 */
enum class Connection {
    Subscription,
    WebLogin,
    Saved,
    Environment,
    Temporary,
    Configured,
    None,
    ;

    val label: String
        get() = when (this) {
            Subscription -> tr("订阅账号", "Subscription")
            WebLogin -> tr("网页登录", "Web Login")
            Saved -> tr("已保存", "Saved")
            Environment -> tr("环境变量", "Environment")
            Temporary -> tr("临时 Key", "Temporary Key")
            Configured -> tr("已连接", "Connected")
            None -> tr("未连接", "Not Connected")
        }

    val connected: Boolean get() = this != None
}

/** 页面用的供应商读数（由 `HostAuth.Provider` 一对一映射，不依赖它）。 */
data class ProviderFacts(
    val id: String,
    val name: String,
    val configured: Boolean,
    val usingOAuth: Boolean,
    val usingSubscription: Boolean,
    val source: String?,
    /** 这家有没有「订阅账号」型的 OAuth 登录方式可选（统一排在顶部「订阅登录」段，登录前后不跳段、不重复）。 */
    val offersSubscription: Boolean,
    val modelCount: Int,
    /** 来自 `models.json`（用户自己加的，或覆盖了内置供应商）。 */
    val custom: Boolean,
    /** 是否支持非订阅型 OAuth 网页登录（如 OpenRouter / Radius）。 */
    val offersWebLogin: Boolean = false,
) {
    val title: String get() = name.ifBlank { id }
}

fun connectionOf(f: ProviderFacts): Connection = when {
    !f.configured -> Connection.None
    f.usingSubscription -> Connection.Subscription
    f.usingOAuth -> Connection.WebLogin
    else -> when (f.source) {
        "stored" -> Connection.Saved
        "environment" -> Connection.Environment
        "runtime" -> Connection.Temporary
        else -> Connection.Configured
    }
}

/**
 * 供应商列表的三段，按使用优先级自上而下排列，且互斥不重复：
 *  1. [subscriptionProviders]：全部支持订阅登录的供应商（已连接的排前面，未连接的排后面，各自按名字排）；
 *  2. [connectedApi]：已连接的非订阅供应商（API Key / 网页登录 / 环境变量 / 自定义）；
 *  3. [otherApi]：其余未连接的 API 供应商（默认折叠，搜索时展开）。
 */
data class ProviderSections(
    val subscriptionProviders: List<ProviderFacts>,
    val connectedApi: List<ProviderFacts>,
    val otherApi: List<ProviderFacts>,
)

private val NAME_ORDER = Comparator<ProviderFacts> { a, b ->
    val byName = a.title.compareTo(b.title, ignoreCase = true)
    if (byName != 0) byName else a.id.compareTo(b.id)
}

private val SUBSCRIPTION_ORDER = compareByDescending<ProviderFacts> { it.configured }.then(NAME_ORDER)

fun sectionProviders(all: List<ProviderFacts>, query: String): ProviderSections {
    val hit = all.filter { matches(query, it.title, it.id) }
    return ProviderSections(
        subscriptionProviders = hit.filter { it.offersSubscription }.sortedWith(SUBSCRIPTION_ORDER),
        connectedApi = hit.filter { !it.offersSubscription && it.configured }.sortedWith(NAME_ORDER),
        otherApi = hit.filter { !it.offersSubscription && !it.configured }.sortedWith(NAME_ORDER),
    )
}

/**
 * 全部空白分隔的词都要命中某个字段（不分大小写、子串）。空查询命中一切。
 * 「sonnet 4」能找到 `claude-sonnet-4-5`，「4 sonnet」同样——顺序不重要。
 */
fun matches(query: String, vararg fields: String): Boolean {
    val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return true
    val haystack = fields.map { it.lowercase() }
    return tokens.all { t -> haystack.any { it.contains(t) } }
}

// ------------------------------------------------------------------ 头像

/** 头像底色的档数；界面把 [avatarSeed] 映射到主题里同样多的色。 */
const val AVATAR_TONES = 6

/** 同一个 id 永远同一档（跨进程、跨语言环境稳定：只用字符码，不用 `hashCode` 的实现细节）。 */
fun avatarSeed(id: String): Int =
    (id.fold(7) { h, c -> h * 31 + c.code } and Int.MAX_VALUE) % AVATAR_TONES

/** 头像上的那个字：名字里第一个字母或数字（大写）；一个都没有就是 `?`。 */
fun avatarInitial(name: String): String =
    name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

// ------------------------------------------------------------------ 模型

/** 页面用的模型读数（由 `PiResponses.ModelInfo` 一对一映射，不依赖它）。 */
data class ModelFacts(
    val provider: String,
    val id: String,
    val name: String,
    val reasoning: Boolean,
    val images: Boolean,
    val contextWindow: Long?,
    /** 美元 / 百万 token。 */
    val inputCost: Double?,
    val outputCost: Double?,
) {
    val title: String get() = name.ifBlank { id }
    fun sameAs(provider: String?, id: String?): Boolean = provider == this.provider && id == this.id
}

/** `200K` / `1M` / `1.5M`；没有或非正数没有标签。 */
fun contextLabel(tokens: Long?): String? {
    if (tokens == null || tokens <= 0) return null
    if (tokens >= 1_000_000) return trimmed(tokens / 1_000_000.0) + "M"
    if (tokens >= 1_000) {
        val k = Math.round(tokens / 1_000.0)
        // 999_500 以上四舍五入会写成「1000K」：那是 1M。
        return if (k >= 1_000) "1M" else "${k}K"
    }
    return tokens.toString()
}

/** `$3 / $15`（输入 / 输出，每百万 token）；任何一项缺失就没有标签。 */
fun priceLabel(input: Double?, output: Double?): String? {
    if (input == null || output == null) return null
    if (!input.isFinite() || !output.isFinite() || input < 0 || output < 0) return null
    return "\$${trimmed(input)} / \$${trimmed(output)}"
}

/** 去掉多余的小数尾巴：`3.0` → `3`，`0.250` → `0.25`，`0.075` 保持。 */
internal fun trimmed(d: Double): String =
    BigDecimal.valueOf(d).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()

/** 模型行上的能力小标签，顺序固定：推理、看图、上下文。 */
fun capabilityTags(m: ModelFacts): List<String> = listOfNotNull(
    if (m.reasoning) tr("推理", "Reasoning") else null,
    if (m.images) tr("看图", "Vision") else null,
    contextLabel(m.contextWindow)?.let { tr("$it 上下文", "$it ctx") },
)

fun searchModels(models: List<ModelFacts>, providerNames: Map<String, String>, query: String): List<ModelFacts> =
    models.filter { matches(query, it.title, it.id, providerNames[it.provider] ?: it.provider) }

data class ModelGroup(val providerId: String, val providerName: String, val models: List<ModelFacts>)

/**
 * 按供应商分组。当前模型所在的供应商排第一，其余按名字；组内保持目录原顺序（SDK 的目录顺序
 * 本来就有意义：新的在前），不重排。
 */
fun groupModels(
    models: List<ModelFacts>,
    providerNames: Map<String, String>,
    currentProvider: String?,
): List<ModelGroup> {
    fun nameOf(id: String) = providerNames[id]?.takeIf { it.isNotBlank() } ?: id
    return models.groupBy { it.provider }
        .map { (id, list) -> ModelGroup(id, nameOf(id), list) }
        .sortedWith(
            compareByDescending<ModelGroup> { it.providerId == currentProvider }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.providerName }
                .thenBy { it.providerId },
        )
}

/** 选中一个模型之后的两条路。SDK 的 `setModel` 默认只改本会话，`persist` 才写进全局默认。 */
enum class UseScope(val persist: Boolean) {
    Session(false),
    Default(true),
    ;

    val label: String
        get() = when (this) {
            Session -> tr("本次会话使用", "Use for this session")
            Default -> tr("设为默认并使用", "Set as default & use")
        }
}

// ------------------------------------------------------------------ 刷新结果

/** `auth.refreshModels` 的结果：SDK 的 `{ aborted, errors: Map<providerId, Error> }`，宿主已摊成对象。 */
data class RefreshSummary(val aborted: Boolean, val errors: Map<String, String>) {
    val ok: Boolean get() = !aborted && errors.isEmpty()
}

fun refreshSummary(result: JsonElement?): RefreshSummary {
    val o = result as? JsonObject ?: return RefreshSummary(aborted = false, errors = emptyMap())
    val errors = (o["errors"] as? JsonObject)?.entries?.associate { (k, v) ->
        k to (v as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    }.orEmpty()
    return RefreshSummary(aborted = (o["aborted"] as? JsonPrimitive)?.booleanOrNull == true, errors = errors)
}

/** 一句话说清刷新结果：全成功 / 被中断 / 哪几家失败（点名，最多三家，其余折成「等 N 家」）。 */
fun RefreshSummary.sentence(providerNames: Map<String, String>): String {
    if (aborted) return tr("刷新被中断，目录可能只更新了一部分。", "Refresh interrupted; the catalog may only be partially updated.")
    if (errors.isEmpty()) return tr("已刷新。", "Refreshed.")
    val names = errors.keys.sorted().map { providerNames[it]?.takeIf { n -> n.isNotBlank() } ?: it }
    val shown = names.take(3).joinToString(tr("、", ", "))
    val rest = if (names.size > 3) tr(" 等 ${names.size} 家", " and ${names.size - 3} more") else ""
    return tr(
        "刷新完成，但 $shown$rest 没有拉成功（已有的模型仍可用）。",
        "Refresh complete, but failed for $shown$rest (existing models remain available).",
    )
}

/** 每家失败的原因，一行一家，给「展开看详情」用。 */
fun RefreshSummary.detail(providerNames: Map<String, String>): List<String> =
    errors.entries.sortedBy { it.key }.map { (id, msg) ->
        val name = providerNames[id]?.takeIf { it.isNotBlank() } ?: id
        if (msg.isEmpty()) name else tr("$name：$msg", "$name: $msg")
    }

// ------------------------------------------------------------------ 自定义供应商

/**
 * 新增 provider 时 id 的状态。
 *
 * SDK 的 `models.json` 里 provider 与内置供应商**同 id = 覆盖它**（改 baseUrl / 请求头 /
 * modelOverrides，不必重新声明模型）——走代理最常见的用法。所以同名不是错误，只是要让人知道
 * 自己在覆盖什么；真正的冲突只有「这个 id 在 models.json 里已经有一份」。
 */
enum class IdCheck { Ok, Empty, AlreadyCustom, OverridesBuiltin }

fun checkNewProviderId(id: String, customIds: Set<String>, knownIds: Set<String>): IdCheck {
    val t = id.trim()
    return when {
        t.isEmpty() -> IdCheck.Empty
        t in customIds -> IdCheck.AlreadyCustom
        t in knownIds -> IdCheck.OverridesBuiltin
        else -> IdCheck.Ok
    }
}

/** 常用的协议（`api`），其余 KnownApi 走自由输入。 */
data class ApiChoice(val api: String, val label: String)

val COMMON_APIS: List<ApiChoice>
    get() = listOf(
        ApiChoice("openai-completions", tr("OpenAI 兼容", "OpenAI Compatible")),
        ApiChoice("openai-responses", "OpenAI Responses"),
        ApiChoice("anthropic-messages", "Anthropic"),
        ApiChoice("google-generative-ai", "Gemini"),
    )
