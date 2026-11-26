package app.pi.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.graphics.vector.ImageVector
import app.pi.i18n.PiLocale
import app.pi.i18n.tr
import app.pi.ui.components.EffectiveKind
import app.pi.ui.theme.PiThinkingLevel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** The six row kinds of docs/pi-android-ui-spec.md §6.2. */
enum class PiRowKind { Switch, Value, Number, Text, List, Action }

/** Whether a list-valued setting is a JSON array or a JSON object map. */
enum class PiValueContainer { Array, Object }

/** One choice of an enumerated (Value) setting, with its wire form. */
data class PiOption(
    val wire: String,
    val label: String,
    val description: String? = null,
)

/** Metadata for a single settings key. */
data class PiSetting(
    /** Dotted path as stored in JSON, e.g. `compaction.reserveTokens`. */
    val key: String,
    val title: String,
    val description: String,
    val editorNote: String? = null,
    val kind: PiRowKind,
    val group: String,
    val section: String,
    val defaultValue: JsonElement? = null,
    val effective: EffectiveKind = EffectiveKind.Immediate,
    val options: List<PiOption> = emptyList(),
    val container: PiValueContainer = PiValueContainer.Array,
    val presets: List<String> = emptyList(),
    val presetBaseline: List<String> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
    val step: Int? = null,
    val unit: String? = null,
    val allowCustom: Boolean = false,
    val readOnly: Boolean = false,
    val dangerous: Boolean = false,
    val multiline: Boolean = false,
    val keyLabel: String? = null,
    val emptyListLabel: String = tr("空", "Empty"),
) {
    fun wireElement(wire: String): JsonElement = JsonPrimitive(wire)
}

/** A settings group on SettingsHome. */
class PiSettingsGroup(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val summary: (PiSettingsStore) -> String,
)

internal const val G_MODELS_SCREEN = "models"
private const val G_PROMPTS = "prompts"
private const val G_MESSAGES = "messages"
private const val G_COMPACTION = "compaction"
private const val G_RETRY = "retry"
private const val G_TOOLS = "tools"
private const val G_RESOURCES = "resources"
private const val G_APPEARANCE = "appearance"

private fun str(value: String): JsonElement = JsonPrimitive(value)
private fun bool(value: Boolean): JsonElement = JsonPrimitive(value)
private fun num(value: Int): JsonElement = JsonPrimitive(value)

private fun choices(vararg pairs: Pair<String, String>): List<PiOption> =
    pairs.map { (wire, label) -> PiOption(wire, label) }

private fun buildThinkingLevelOptions(zh: Boolean): List<PiOption> {
    fun t(z: String, e: String) = if (zh) z else e
    val descriptions = mapOf(
        "off" to t("关闭思考推理，直接输出回答", "Disable reasoning; respond directly"),
        "minimal" to t("最少推理（约 1k token 预算）", "Minimal reasoning (~1k token budget)"),
        "low" to t("轻量推理（约 2k token 预算）", "Light reasoning (~2k token budget)"),
        "medium" to t("均衡推理（约 8k token 预算）", "Balanced reasoning (~8k token budget)"),
        "high" to t("深度推理（约 16k token 预算）", "Deep reasoning (~16k token budget)"),
        "xhigh" to t("极深推理（约 32k token 预算，仅部分模型支持）", "Extra-high reasoning (~32k tokens; selected models only)"),
        "max" to t("最大推理深度（仅部分模型支持）", "Maximum reasoning depth (selected models only)"),
    )
    return PiThinkingLevel.entries
        .map { PiOption(it.wire, it.label, descriptions[it.wire]) }
}

private fun buildSettings(zh: Boolean): List<PiSetting> {
    fun t(z: String, e: String): String = if (zh) z else e
    val thinkingOptions = buildThinkingLevelOptions(zh)
    val transportOptions = choices(
        "auto" to t("自动", "Auto"),
        "sse" to "SSE",
        "websocket" to "WebSocket",
        "websocket-cached" to t("WebSocket（缓存）", "WebSocket (cached)"),
    )
    val deliveryOptions = choices(
        "one-at-a-time" to t("逐条", "One at a time"),
        "all" to t("全部", "All"),
    )
    val treeFilterOptions = choices(
        "default" to t("默认（隐藏设置类条目）", "Default (hide setting entries)"),
        "no-tools" to t("无工具结果", "No tool results"),
        "user-only" to t("仅用户消息", "User messages only"),
        "labeled-only" to t("仅书签", "Labeled only"),
        "all" to t("全部", "All"),
    )
    val emptyDefault = t("空", "Empty")

    return listOf(
        // ------------------------------------------------------------------
        // 1 模型推理与缓存（内嵌在 ModelsScreen）
        // ------------------------------------------------------------------
        PiSetting(
            key = "defaultThinkingLevel",
            title = t("默认思考等级", "Default Thinking Level"),
            description = t("新建会话时默认使用的思考等级。", "Default thinking level for new sessions."),
            kind = PiRowKind.Value,
            group = G_MODELS_SCREEN,
            section = t("默认与循环", "Defaults & Cycling"),
            effective = EffectiveKind.NewSession,
            defaultValue = str("medium"),
            options = thinkingOptions,
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "enabledModels",
            title = t("循环模型", "Enabled Models"),
            description = t("循环切换的模型，支持通配符；清空 = 全部模型。", "Model patterns for cycling (supports globs). Empty = all models."),
            kind = PiRowKind.List,
            group = G_MODELS_SCREEN,
            section = t("默认与循环", "Defaults & Cycling"),
            effective = EffectiveKind.Immediate,
            presets = listOf("claude-*", "gpt-*", "gemini-*", "deepseek-*", "qwen-*"),
            emptyListLabel = t("全部模型", "All models"),
        ),
        PiSetting(
            key = "modelThinkingLevels",
            title = t("逐模型思考等级", "Per-Model Thinking Levels"),
            description = t("按模型覆盖思考等级（命中当前模型时立即同步）。清空并保存 = 回到默认。", "Override thinking level per model (applies immediately if matching active model). Clear and save to reset."),
            kind = PiRowKind.List,
            group = G_MODELS_SCREEN,
            section = t("逐模型与预算", "Per-Model & Budgets"),
            effective = EffectiveKind.Immediate,
            container = PiValueContainer.Object,
            emptyListLabel = t("无覆盖", "No overrides"),
        ),
        PiSetting(
            key = "thinkingBudgets",
            title = t("思考预算", "Thinking Budgets"),
            description = t("逐档思考预算，留空 = 厂商默认。", "Custom token budgets for thinking levels. Empty = provider default."),
            kind = PiRowKind.List,
            group = G_MODELS_SCREEN,
            section = t("逐模型与预算", "Per-Model & Budgets"),
            effective = EffectiveKind.Immediate,
            container = PiValueContainer.Object,
            emptyListLabel = t("未设置", "Not set"),
        ),
        PiSetting(
            key = "cacheWarming",
            title = t("缓存预热", "Cache Warming"),
            description = t("空闲时续住提示缓存（会产生额外费用）。", "Cache-warming profile. \"idle\" also warms between runs (costs money)."),
            kind = PiRowKind.Value,
            group = G_MODELS_SCREEN,
            section = t("缓存", "Cache"),
            defaultValue = str("idle"),
            effective = EffectiveKind.Immediate,
            options = listOf(
                PiOption("off", t("关闭", "Off")),
                PiOption("streaming", t("仅流式中", "Streaming only")),
                PiOption("idle", t("含空闲时段（默认）", "Idle + streaming (default)")),
            ),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "notices.cacheMisses",
            title = t("缓存未命中提示", "Cache Miss Notices"),
            description = t("当助手回复产生较大缓存未命中（如切换模型或空闲过期）时弹出诊断提醒。", "Show notices when an assistant response pays for a significant prompt cache miss."),
            kind = PiRowKind.Switch,
            group = G_MODELS_SCREEN,
            section = t("提示与诊断", "Notices & Diagnostics"),
            effective = EffectiveKind.Immediate,
            defaultValue = bool(false),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "notices.cacheWarming",
            title = t("缓存预热费用提示", "Cache Warming Notices"),
            description = t("当后台缓存预热触发计费时提示本次预热开销。", "Show notices when background prompt cache warming incurs usage."),
            kind = PiRowKind.Switch,
            group = G_MODELS_SCREEN,
            section = t("提示与诊断", "Notices & Diagnostics"),
            effective = EffectiveKind.Immediate,
            defaultValue = bool(false),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "notices.compactionCosts",
            title = t("压缩与摘要计费提示", "Compaction Cost Notices"),
            description = t("在会话压缩与分支摘要卡片下方显示实际计费 Token 数与预估费用。", "Show billed tokens and cost under compaction and branch summary cards."),
            kind = PiRowKind.Switch,
            group = G_MODELS_SCREEN,
            section = t("提示与诊断", "Notices & Diagnostics"),
            effective = EffectiveKind.Immediate,
            defaultValue = bool(false),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "notices.reasoningDrops",
            title = t("推理块被丢弃提示", "Dropped Reasoning Notices"),
            description = t("当 Anthropic 或 OpenAI 因签名/前缀变化丢弃历史思考块时弹出提醒。", "Show notices when a provider drops withheld thinking or reasoning blocks."),
            kind = PiRowKind.Switch,
            group = G_MODELS_SCREEN,
            section = t("提示与诊断", "Notices & Diagnostics"),
            effective = EffectiveKind.Immediate,
            defaultValue = bool(false),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "warnings.anthropicExtraUsage",
            title = t("Anthropic 超额计费提醒", "Anthropic Extra Usage Warning"),
            description = t("使用 Anthropic 订阅登录且可能触发付费超额用量时给出提醒。", "Warn when Anthropic subscription auth may use paid extra usage."),
            kind = PiRowKind.Switch,
            group = G_MODELS_SCREEN,
            section = t("缓存", "Cache"),
            effective = EffectiveKind.Immediate,
            defaultValue = bool(true),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "app.runtime.cacheRetention",
            title = t("提示缓存保留时长（PI_CACHE_RETENTION）", "Prompt Cache Retention (PI_CACHE_RETENTION)"),
            description = t("默认短期（约 5 分钟）；开启长缓存可延长至约 1 小时，适合移动端间歇对话。", "Default is short (~5 min); long extends provider prompt caching to ~1 hour for mobile use."),
            kind = PiRowKind.Value,
            group = G_MODELS_SCREEN,
            section = t("缓存", "Cache"),
            defaultValue = str("short"),
            effective = EffectiveKind.RestartEngine,
            options = listOf(
                PiOption("short", t("短期（默认）", "Short (default)")),
                PiOption("long", t("长期（约 1 小时）", "Long (~1 hour)")),
            ),
            emptyListLabel = emptyDefault,
        ),

        // ------------------------------------------------------------------
        // 2 系统提示词与上下文（由 SystemPromptHostScreen 承载，保留搜索条目）
        // ------------------------------------------------------------------
        PiSetting(
            key = "app.prompts.append",
            title = t("追加系统提示（APPEND_SYSTEM.md）", "Append System Prompt (APPEND_SYSTEM.md)"),
            description = t("推荐：在 pi 默认系统提示后安全追加自定义指令，不破坏工具规则。", "Recommended: safely append custom instructions after pi's default system prompt."),
            keyLabel = "~/.pi/agent/APPEND_SYSTEM.md",
            kind = PiRowKind.Text,
            group = G_PROMPTS,
            section = t("系统提示词", "System Prompt"),
            defaultValue = str(""),
            multiline = true,
            effective = EffectiveKind.Reload,
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "app.prompts.system",
            title = t("自定义系统提示（SYSTEM.md）", "Custom System Prompt (SYSTEM.md)"),
            description = t("高级：整份替换 pi 默认提示词（含工具清单与规则）。", "Advanced: replaces pi's entire default system prompt including tool snippets and rules."),
            keyLabel = "~/.pi/agent/SYSTEM.md",
            kind = PiRowKind.Text,
            group = G_PROMPTS,
            section = t("系统提示词", "System Prompt"),
            defaultValue = str(""),
            multiline = true,
            effective = EffectiveKind.Reload,
            emptyListLabel = emptyDefault,
        ),

        // ------------------------------------------------------------------
        // 3 对话与消息
        // ------------------------------------------------------------------
        PiSetting(
            key = "steeringMode",
            title = t("引导模式", "Steering Mode"),
            description = t("流式中发送的消息怎么送达：逐条，或全部一起给。", "Delivery mode for steering messages sent mid-stream: one at a time or all at once."),
            kind = PiRowKind.Value,
            group = G_MESSAGES,
            section = t("会话", "Session"),
            defaultValue = str("one-at-a-time"),
            options = deliveryOptions,
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "followUpMode",
            title = t("后续模式", "Follow-up Mode"),
            description = t("本轮结束后再发的消息怎么送达：逐条，或全部一起给。", "Delivery mode for queued follow-up messages after a turn ends."),
            kind = PiRowKind.Value,
            group = G_MESSAGES,
            section = t("会话", "Session"),
            defaultValue = str("one-at-a-time"),
            options = deliveryOptions,
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "treeFilterMode",
            title = t("会话树默认过滤", "Default Session Tree Filter"),
            description = t("打开会话树时先用哪种过滤；在树里可随时切换。", "Default filter when opening /tree. Can be switched anytime inside the tree."),
            kind = PiRowKind.Value,
            group = G_MESSAGES,
            section = t("会话", "Session"),
            defaultValue = str("default"),
            options = treeFilterOptions,
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "app.sessions.resumeLast",
            title = t("启动续接最近会话", "Resume Last Session on Launch"),
            description = t("启动 App 时自动切到最近一次会话。", "Automatically reopen the most recent session when launching the app."),
            kind = PiRowKind.Switch,
            group = G_MESSAGES,
            section = t("会话", "Session"),
            defaultValue = bool(true),
            effective = EffectiveKind.RestartApp,
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "images.autoResize",
            title = t("图片自动缩放", "Auto-Resize Images"),
            description = t("图片自动缩到最大 2000×2000（附件立即生效，read 工具读图在重载或新会话生效）。", "Auto-resize images to max 2000×2000 (prompt attachments apply immediately; read tool after reload/new session)."),
            kind = PiRowKind.Switch,
            group = G_MESSAGES,
            section = t("图片", "Images"),
            effective = EffectiveKind.Immediate,
            defaultValue = bool(true),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "images.blockImages",
            title = t("屏蔽图片", "Block Images"),
            description = t("不把图片发给模型（附件仍可加）。", "Prevent images from being sent to LLM providers."),
            kind = PiRowKind.Switch,
            group = G_MESSAGES,
            section = t("图片", "Images"),
            effective = EffectiveKind.Immediate,
            defaultValue = bool(false),
            emptyListLabel = emptyDefault,
        ),

        // ------------------------------------------------------------------
        // 4 压缩与上下文窗口
        // ------------------------------------------------------------------
        PiSetting(
            key = "compaction.enabled",
            title = t("自动压缩", "Auto-Compaction"),
            description = t("上下文接近上限时自动压缩历史，避免请求溢出。", "Automatically compact session history when nearing the context window limit."),
            kind = PiRowKind.Switch,
            group = G_COMPACTION,
            section = t("压缩", "Compaction"),
            defaultValue = bool(true),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "compaction.reserveTokens",
            title = t("压缩触发余量", "Compaction Reserve Tokens"),
            description = t("剩余上下文低于它就自动压缩；0 = 用到上限才压缩。", "Trigger compaction when remaining context drops below this token count (0 = at limit)."),
            editorNote = t(
                "pi 1.5.0 起它只决定「什么时候开始压缩」；做摘要时的输出长度用模型自己的输出上限，必要时自动缩短以保证输入加输出放得进上下文。",
                "Since pi 1.5.0 this controls when compaction triggers; summary output uses the model's max output tokens and shrinks automatically if needed.",
            ),
            kind = PiRowKind.Number,
            group = G_COMPACTION,
            section = t("压缩", "Compaction"),
            effective = EffectiveKind.Immediate,
            defaultValue = num(16384),
            min = 0,
            max = 1000000,
            step = 1024,
            unit = "token",
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "compaction.keepRecentTokens",
            title = t("保留最近 token", "Keep Recent Tokens"),
            description = t("最近的多少 token 不参与摘要、原样保留。", "Number of recent tokens kept verbatim during compaction."),
            kind = PiRowKind.Number,
            group = G_COMPACTION,
            section = t("压缩", "Compaction"),
            effective = EffectiveKind.Immediate,
            defaultValue = num(20000),
            min = 0,
            max = 1000000,
            step = 1024,
            unit = "token",
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "compaction.modelOverrides",
            title = t("逐模型覆盖", "Per-Model Overrides"),
            description = t("按厂商/模型覆盖上面两项。", "Per-model overrides keyed by exact \"provider/modelId\" strings."),
            kind = PiRowKind.List,
            group = G_COMPACTION,
            section = t("压缩", "Compaction"),
            effective = EffectiveKind.Immediate,
            container = PiValueContainer.Object,
            emptyListLabel = t("无覆盖", "No overrides"),
        ),
        PiSetting(
            key = "branchSummary.reserveTokens",
            title = t("分支摘要预留 token", "Branch Summary Reserve Tokens"),
            description = t("为分支摘要提示词与模型回复预留的上下文余量。", "Tokens reserved for the branch summary prompt and LLM response."),
            kind = PiRowKind.Number,
            group = G_COMPACTION,
            section = t("压缩", "Compaction"),
            effective = EffectiveKind.Immediate,
            defaultValue = num(16384),
            min = 0,
            max = 1000000,
            step = 1024,
            unit = "token",
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "branchSummary.skipPrompt",
            title = t("切换分支跳过摘要询问", "Skip Branch Summary Prompt"),
            description = t("会话树跳转分支时不弹出摘要确认，直接切换。", "Skip the \"Summarize branch?\" prompt and default to no summary."),
            kind = PiRowKind.Switch,
            group = G_COMPACTION,
            section = t("压缩", "Compaction"),
            defaultValue = bool(false),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "app.compaction.runNow",
            title = t("立即压缩", "Compact Now"),
            description = t("手动压缩当前会话。", "Manually compact the active session context now."),
            kind = PiRowKind.Action,
            group = G_COMPACTION,
            section = t("动作", "Actions"),
            emptyListLabel = emptyDefault,
        ),

        // ------------------------------------------------------------------
        // 5 网络与重试
        // ------------------------------------------------------------------
        PiSetting(
            key = "transport",
            title = t("传输方式", "Transport"),
            description = t("多传输厂商优先用哪一种。", "Preferred transport for providers supporting multiple transports."),
            kind = PiRowKind.Value,
            group = G_RETRY,
            section = t("连接", "Connection"),
            defaultValue = str("auto"),
            effective = EffectiveKind.Immediate,
            options = transportOptions,
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "httpIdleTimeoutMs",
            title = t("HTTP 空闲超时", "HTTP Idle Timeout"),
            description = t("HTTP 头/体的空闲超时（毫秒）；0 = 不超时。", "HTTP header or body idle timeout in milliseconds (0 disables it)."),
            kind = PiRowKind.Number,
            group = G_RETRY,
            section = t("连接", "Connection"),
            defaultValue = num(300000),
            effective = EffectiveKind.Immediate,
            min = 0,
            max = 3600000,
            step = 1000,
            unit = "ms",
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "websocketConnectTimeoutMs",
            title = t("WebSocket 连接超时", "WebSocket Connect Timeout"),
            description = t("握手超时（毫秒，0 不限）。", "WebSocket connect or open handshake timeout in milliseconds (0 disables it)."),
            kind = PiRowKind.Number,
            group = G_RETRY,
            section = t("连接", "Connection"),
            defaultValue = num(15000),
            effective = EffectiveKind.Immediate,
            min = 0,
            max = 300000,
            step = 1000,
            unit = "ms",
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "httpProxy",
            title = t("HTTP 代理", "HTTP Proxy"),
            description = t("代理地址，对 pi 自己发起的请求即时生效。", "Proxy URL applied as HTTP_PROXY and HTTPS_PROXY for Pi-managed HTTP clients."),
            kind = PiRowKind.Text,
            group = G_RETRY,
            section = t("连接", "Connection"),
            defaultValue = str(""),
            effective = EffectiveKind.Immediate,
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "retry.enabled",
            title = t("自动重试", "Auto-Retry"),
            description = t("遇到瞬时错误时自动重试整轮请求。", "Automatically retry failed turns on transient errors."),
            kind = PiRowKind.Switch,
            group = G_RETRY,
            section = t("重试", "Retry"),
            defaultValue = bool(true),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "retry.maxRetries",
            title = t("最大重试次数", "Max Retries"),
            description = t("整轮请求的重试上限。", "Maximum retry attempts per turn."),
            kind = PiRowKind.Number,
            group = G_RETRY,
            section = t("重试", "Retry"),
            effective = EffectiveKind.Immediate,
            defaultValue = num(3),
            min = 0,
            max = 20,
            step = 1,
            unit = t("次", "×"),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "retry.baseDelayMs",
            title = t("基础延迟", "Base Retry Delay"),
            description = t("指数退避的起始延迟，2s、4s、8s 这样增长。", "Exponential backoff base delay in milliseconds (e.g. 2s, 4s, 8s)."),
            kind = PiRowKind.Number,
            group = G_RETRY,
            section = t("重试", "Retry"),
            effective = EffectiveKind.Immediate,
            defaultValue = num(2000),
            min = 0,
            max = 60000,
            step = 100,
            unit = "ms",
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "retry.maxAgentDelayMs",
            title = t("最大重试延迟", "Max Agent Retry Delay"),
            description = t("单次重试等待的上限，默认 60 秒。", "Maximum wait time for a single retry attempt (default 60s)."),
            kind = PiRowKind.Number,
            group = G_RETRY,
            section = t("重试", "Retry"),
            effective = EffectiveKind.Immediate,
            defaultValue = num(60000),
            min = 0,
            max = 600000,
            step = 1000,
            unit = "ms",
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "retry.provider.timeoutMs",
            title = t("Provider 超时", "Provider Timeout"),
            description = t("单次请求超时（留空用默认）。", "SDK or provider request timeout in milliseconds."),
            kind = PiRowKind.Number,
            group = G_RETRY,
            section = t("Provider 重试（高级）", "Provider Retry (Advanced)"),
            effective = EffectiveKind.Immediate,
            min = 0,
            max = 3600000,
            step = 1000,
            unit = "ms",
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "retry.provider.maxRetries",
            title = t("Provider 重试次数", "Provider Max Retries"),
            description = t("SDK 层的重试上限，默认 0；调高可能让限流先被吞掉。", "SDK or provider retry attempts (default 0)."),
            kind = PiRowKind.Number,
            group = G_RETRY,
            section = t("Provider 重试（高级）", "Provider Retry (Advanced)"),
            effective = EffectiveKind.Immediate,
            defaultValue = num(0),
            min = 0,
            max = 20,
            step = 1,
            unit = t("次", "×"),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "retry.provider.maxRetryDelayMs",
            title = t("Provider 最大延迟", "Provider Max Retry Delay"),
            description = t("厂商要求的等待超过这个值就直接失败；0 = 不限制。", "Maximum server-requested delay before failing (0 = unlimited)."),
            kind = PiRowKind.Number,
            group = G_RETRY,
            section = t("Provider 重试（高级）", "Provider Retry (Advanced)"),
            effective = EffectiveKind.Immediate,
            defaultValue = num(60000),
            min = 0,
            max = 3600000,
            step = 1000,
            unit = "ms",
            emptyListLabel = emptyDefault,
        ),

        // ------------------------------------------------------------------
        // 6 工具（由 ToolsScreen 承载：实时工具总开关 + 设为默认 + Codemode + Shell + 显示）
        // ------------------------------------------------------------------
        PiSetting(
            key = "codemode.mode",
            title = t("Codemode 方式", "Codemode Mode"),
            description = t(
                "仅启用 codemode 时生效。on：保留直接工具并附脚本调用方式；only：仅暴露 codemode，其余工具经脚本调用。",
                "Applies when codemode is active. \"on\" keeps direct tools; \"only\" exposes script-callable tools exclusively via codemode.",
            ),
            kind = PiRowKind.Value,
            group = G_TOOLS,
            section = t("工具", "Tools"),
            defaultValue = str("on"),
            effective = EffectiveKind.Reload,
            options = listOf(
                PiOption("on", t("on（默认）", "on (default)")),
                PiOption("only", "only"),
            ),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "codemode.inlineBudget",
            title = t("Codemode 声明预算", "Codemode Inline Budget"),
            description = t(
                "仅启用 codemode 时生效。内联工具声明的估算 token 预算，超出部分由 searchTools() 按需查找。",
                "Estimated tokens available for inline codemode tool declarations (overflow discovered via searchTools()).",
            ),
            kind = PiRowKind.Number,
            group = G_TOOLS,
            section = t("工具", "Tools"),
            defaultValue = num(3000),
            effective = EffectiveKind.Reload,
            min = 0,
            max = 200000,
            step = 100,
            unit = "token",
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "shellPath",
            title = t("Bash 执行器路径（shellPath）", "Shell Path (shellPath)"),
            description = t(
                "自定义容器内 bash 工具使用的 Shell 路径（如 /bin/bash、/bin/sh）；留空则自动探测。",
                "Custom shell executable inside the guest rootfs (e.g. /bin/bash); leave empty to auto-detect.",
            ),
            kind = PiRowKind.Text,
            group = G_TOOLS,
            section = t("工具", "Tools"),
            defaultValue = str(""),
            effective = EffectiveKind.Reload,
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "shellCommandPrefix",
            title = t("Bash 命令前缀", "Shell Command Prefix"),
            description = t(
                "自动拼在每条 bash 工具命令之前，例如启用 shell alias 或注入环境变量。",
                "Prefix prepended to every bash tool command, e.g. to enable shell aliases.",
            ),
            kind = PiRowKind.Text,
            group = G_TOOLS,
            section = t("工具", "Tools"),
            defaultValue = str(""),
            effective = EffectiveKind.Reload,
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "app.tools.expandByDefault",
            title = t("工具输出默认展开", "Expand Tool Output by Default"),
            description = t("工具卡默认展开输出，而不是只显示一行摘要。", "Show expanded tool output by default instead of a collapsed summary."),
            kind = PiRowKind.Switch,
            group = G_TOOLS,
            section = t("工具", "Tools"),
            defaultValue = bool(false),
            emptyListLabel = emptyDefault,
        ),

        // ------------------------------------------------------------------
        // 7 扩展与资源
        // ------------------------------------------------------------------
        PiSetting(
            key = "app.extensions.args",
            title = t("扩展启动参数", "Extension Startup Flags"),
            description = t("按命令行方式传参，例如 --plan。", "CLI-style flags forwarded to extensions on launch, e.g. --plan."),
            kind = PiRowKind.Text,
            group = G_RESOURCES,
            section = t("技能命令与扩展参数", "Skill Commands & Extension Flags"),
            defaultValue = str(""),
            effective = EffectiveKind.RestartEngine,
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "enableSkillCommands",
            title = t("技能命令", "Skill Commands"),
            description = t("技能注册成 /skill:name 命令。", "Register skills as /skill:name commands."),
            kind = PiRowKind.Switch,
            group = G_RESOURCES,
            section = t("技能命令与扩展参数", "Skill Commands & Extension Flags"),
            defaultValue = bool(true),
            effective = EffectiveKind.Immediate,
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "npmCommand",
            title = t("npm 安装命令（npmCommand）", "npm Command (npmCommand)"),
            description = t(
                "自定义安装包命令与参数（如切换国内镜像源或使用 pnpm）；清空恢复默认 npm。",
                "Custom package install command and args (e.g. custom registry or pnpm); clear for default npm.",
            ),
            kind = PiRowKind.List,
            group = G_RESOURCES,
            section = t("包安装与遥测", "Packages & Telemetry"),
            effective = EffectiveKind.Immediate,
            presets = listOf("npm", "--registry=https://registry.npmmirror.com"),
            emptyListLabel = t("默认（npm）", "Default (npm)"),
        ),
        PiSetting(
            key = "enableInstallTelemetry",
            title = t("安装遥测", "Install Telemetry"),
            description = t("执行 pi install/update 时发送匿名安装遥测与来源标头。", "Send anonymous install/update telemetry and attribution headers."),
            kind = PiRowKind.Switch,
            group = G_RESOURCES,
            section = t("包安装与遥测", "Packages & Telemetry"),
            effective = EffectiveKind.Immediate,
            defaultValue = bool(true),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "enableAnalytics",
            title = t("使用分析数据共享", "Analytics Sharing"),
            description = t("可选加入 Pi 匿名使用分析（默认关闭）。", "Opt in to anonymous Pi usage analytics (off by default)."),
            kind = PiRowKind.Switch,
            group = G_RESOURCES,
            section = t("包安装与遥测", "Packages & Telemetry"),
            effective = EffectiveKind.Immediate,
            defaultValue = bool(false),
            emptyListLabel = emptyDefault,
        ),

        // ------------------------------------------------------------------
        // 8 外观
        // ------------------------------------------------------------------
        PiSetting(
            key = "app.appearance.language",
            title = t("界面语言", "Language"),
            description = t(
                "应用显示语言：默认跟随系统，切换后立即生效，无需重启。",
                "App display language. Defaults to system language and applies immediately without restarting.",
            ),
            kind = PiRowKind.Value,
            group = G_APPEARANCE,
            section = t("外观", "Appearance"),
            defaultValue = str("system"),
            options = choices(
                "system" to t("跟随系统", "Follow System"),
                "zh" to "简体中文",
                "en" to "English",
            ),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "theme",
            title = t("主题", "Theme"),
            description = t("固定一个主题，或跟随系统分别选浅色与深色。", "Pick a fixed theme or a light/dark pair that follows system appearance."),
            kind = PiRowKind.Value,
            group = G_APPEARANCE,
            section = t("外观", "Appearance"),
            defaultValue = str("light/dark"),
            allowCustom = true,
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "app.appearance.proseFont",
            title = t("全局字体", "App Font"),
            description = t("统一控制全 App 界面与对话正文，内置真实中英多字重：IBM Plex 黑体、思源宋体、霞鹜文楷，或跟随系统字体。", "Unified UI & chat reading font with native CJK+Latin multi-weight cuts: IBM Plex Sans SC, Source Han Serif, LXGW WenKai, or System."),
            kind = PiRowKind.Value,
            group = G_APPEARANCE,
            section = t("外观", "Appearance"),
            defaultValue = str("sans"),
            options = choices(
                "sans" to t("IBM Plex 黑体（默认 · 4 字重）", "IBM Plex Sans SC (default · 4 weights)"),
                "serif" to t("思源宋体（书卷衬线 · 双字重）", "Source Han Serif (editorial · 2 weights)"),
                "kai" to t("霞鹜文楷（温润楷体 · 双字重）", "LXGW WenKai (humanist kai · 2 weights)"),
                "system" to t("跟随系统（ROM 原生字体）", "System (device native)"),
            ),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "app.appearance.codeFont",
            title = t("代码与终端字体", "Code & Terminal Font"),
            description = t("用于代码块、行内代码、差异视图、内置终端与扩展 TUI（完整多字重与真斜体）。", "Monospace family for code blocks, inline code, diffs, terminal, and TUI overlays."),
            kind = PiRowKind.Value,
            group = G_APPEARANCE,
            section = t("外观", "Appearance"),
            defaultValue = str("jetbrains"),
            options = choices(
                "jetbrains" to t("JetBrains Mono（默认）", "JetBrains Mono (default)"),
                "geist_mono" to t("Geist Mono（几何精密）", "Geist Mono (geometric)"),
            ),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "app.appearance.codeLigatures",
            title = t("代码连字", "Code Ligatures"),
            description = t("在代码块中启用 =>、!=、-> 等编程连字；路径、命令与 Diff 始终保持字面符号。", "Enable coding ligatures (=>, !=, ->) in code blocks while keeping paths and diffs literal."),
            kind = PiRowKind.Switch,
            group = G_APPEARANCE,
            section = t("外观", "Appearance"),
            defaultValue = bool(true),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "app.appearance.fontSize",
            title = t("字号", "Font Size"),
            description = t("正文基准字号（14–18 sp）；标题、代码块、表格与界面按模块化比例自动联动。", "Base prose size (14–18 sp); headings, code, tables, and chrome scale proportionally."),
            kind = PiRowKind.Value,
            group = G_APPEARANCE,
            section = t("外观", "Appearance"),
            defaultValue = str("16"),
            options = choices(
                "14" to "14sp",
                "15" to "15sp",
                "16" to t("16sp（默认）", "16sp (default)"),
                "17" to "17sp",
                "18" to "18sp",
            ),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "app.appearance.typography",
            title = t("阅读密度", "Reading Density"),
            description = t("统一控制行距倍率、段距、列表与消息换人留白，不改变字号大小。", "Harmonizes line height, paragraph rhythm, list gaps, and turn spacing without changing font size."),
            kind = PiRowKind.Value,
            group = G_APPEARANCE,
            section = t("外观", "Appearance"),
            defaultValue = str("comfortable"),
            options = choices(
                "compact" to t("紧凑", "Compact"),
                "comfortable" to t("舒适", "Comfortable"),
                "relaxed" to t("舒展", "Relaxed"),
            ),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "app.appearance.showTimestamps",
            title = t("显示时间戳", "Show Timestamps"),
            description = t("消息带时间、跨天插日期（默认关）。", "Show message timestamps and day dividers (off by default)."),
            kind = PiRowKind.Switch,
            group = G_APPEARANCE,
            section = t("外观", "Appearance"),
            defaultValue = bool(false),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = app.pi.ui.chat.activity.ActivityDisplayMode.SETTING_KEY,
            title = t("工具与过程展示", "Tool & Process Display"),
            description = t("Pi 原版逐条展示工具卡片（对齐官方 Pi）；专注折叠将思考与工具收成单行摘要，正文更聚焦。", "Pi Original shows each tool card individually (official Pi); Compact folds thinking and tools into a single calm summary row."),
            kind = PiRowKind.Value,
            group = G_APPEARANCE,
            section = t("外观", "Appearance"),
            defaultValue = str(app.pi.ui.chat.activity.ActivityDisplayMode.CARDS),
            options = choices(
                app.pi.ui.chat.activity.ActivityDisplayMode.CARDS to t("Pi 原版（默认）", "Pi Original (default)"),
                app.pi.ui.chat.activity.ActivityDisplayMode.COMPACT to t("专注折叠（正文优先）", "Compact (prose first)"),
            ),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "app.appearance.thinkingCollapsedByDefault",
            title = t("思考块默认折叠", "Collapse Thinking Blocks by Default"),
            description = t("思考块默认收起成一行「已思考 12s」，点击展开。", "Show thinking blocks collapsed to a single summary line; tap to expand."),
            kind = PiRowKind.Switch,
            group = G_APPEARANCE,
            section = t("外观", "Appearance"),
            defaultValue = bool(true),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "hideThinkingBlock",
            title = t("隐藏思考块", "Hide Thinking Blocks"),
            description = t("隐藏对话流里的思考块；思考照常进行，只是不显示。", "Hide thinking blocks from the transcript (reasoning still runs)."),
            kind = PiRowKind.Switch,
            group = G_APPEARANCE,
            section = t("外观", "Appearance"),
            defaultValue = bool(false),
            emptyListLabel = emptyDefault,
        ),
        PiSetting(
            key = "markdown.mermaid",
            title = t("Mermaid 图表渲染", "Mermaid Diagram Rendering"),
            description = t("流式中、完成后渲染 Mermaid 代码块，或关闭只显示源码。", "Render Mermaid code blocks during streaming, when finished, or off."),
            kind = PiRowKind.Value,
            group = G_APPEARANCE,
            section = t("外观", "Appearance"),
            defaultValue = str("streaming"),
            options = choices(
                "streaming" to t("流式中实时渲染（默认）", "Streaming (default)"),
                "final" to t("仅完成后渲染", "Final only"),
                "off" to t("关闭（仅显示源码）", "Off (source only)"),
            ),
            emptyListLabel = emptyDefault,
        ),

    )
}

private fun buildGroups(zh: Boolean): List<PiSettingsGroup> {
    fun t(z: String, e: String): String = if (zh) z else e
    return listOf(
        PiSettingsGroup(G_PROMPTS, t("系统提示词与上下文", "System Prompt & Context"), Icons.Filled.Edit) {
            "APPEND_SYSTEM.md · SYSTEM.md · AGENTS.md"
        },
        PiSettingsGroup(G_MESSAGES, t("对话与消息", "Chat & Messages"), Icons.AutoMirrored.Filled.CompareArrows) { store ->
            t(
                "引导：${PiSettingsCatalog.summaryText(store, "steeringMode")} · 后续：${PiSettingsCatalog.summaryText(store, "followUpMode")} · 续接：${PiSettingsCatalog.summaryText(store, "app.sessions.resumeLast")}",
                "Steer: ${PiSettingsCatalog.summaryText(store, "steeringMode")} · Follow-up: ${PiSettingsCatalog.summaryText(store, "followUpMode")} · Resume: ${PiSettingsCatalog.summaryText(store, "app.sessions.resumeLast")}",
            )
        },
        PiSettingsGroup(G_COMPACTION, t("压缩与上下文窗口", "Compaction & Context"), Icons.Filled.Compress) { store ->
            val enabled = PiSettingsCatalog.summaryText(store, "compaction.enabled")
            val reserve = PiSettingsCatalog.summaryText(store, "compaction.reserveTokens")
            val keep = PiSettingsCatalog.summaryText(store, "compaction.keepRecentTokens")
            t("$enabled · 余量 $reserve · 保留 $keep", "$enabled · Reserve $reserve · Keep $keep")
        },
        PiSettingsGroup(G_RETRY, t("网络与重试", "Network & Retry"), Icons.Filled.Refresh) { store ->
            val idle = PiSettingsCatalog.summaryText(store, "httpIdleTimeoutMs")
            val retryOn = PiSettingsCatalog.byKey["retry.enabled"]?.boolIn(store, true) ?: true
            val retryPart = if (retryOn) {
                t("重试 ${PiSettingsCatalog.summaryText(store, "retry.maxRetries")}", "Retry ${PiSettingsCatalog.summaryText(store, "retry.maxRetries")}")
            } else {
                t("重试关", "Retry off")
            }
            "${PiSettingsCatalog.summaryText(store, "transport")} · $idle · $retryPart"
        },
        PiSettingsGroup(G_TOOLS, t("工具", "Tools"), Icons.Filled.Build) { store ->
            val raw = store.read("defaultTools")
            if (raw == null) {
                t("全部工具开关 · Codemode · Shell", "All tool switches · Codemode · Shell")
            } else {
                val entries = (raw as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    .orEmpty()
                val enabled = PiQuickAdd.resolveDefaultTools(entries, PiQuickAdd.builtinToolDefaults)
                if (enabled.isEmpty()) {
                    t("默认工具为空 · Codemode · Shell", "No default tools · Codemode · Shell")
                } else {
                    t("默认 ${enabled.size} 个工具 · Codemode · Shell", "${enabled.size} default tools · Codemode · Shell")
                }
            }
        },
        PiSettingsGroup(G_RESOURCES, t("扩展与资源", "Extensions & Resources"), Icons.Filled.Extension) {
            t("扩展 · 技能 · 提示 · 主题 · 包 · 信任", "Extensions · Skills · Prompts · Themes · Packages · Trust")
        },
        PiSettingsGroup(G_APPEARANCE, t("外观", "Appearance"), Icons.Filled.ColorLens) { store ->
            val themeValue = PiSettingsCatalog.summaryText(store, "theme")
            t("主题 $themeValue · 语言与排版", "Theme $themeValue · Language & Typography")
        },
    )
}

object PiSettingsCatalog {

    private val zhSettings: List<PiSetting> by lazy { buildSettings(zh = true) }
    private val enSettings: List<PiSetting> by lazy { buildSettings(zh = false) }
    private val zhByKey: Map<String, PiSetting> by lazy { zhSettings.associateBy { it.key } }
    private val enByKey: Map<String, PiSetting> by lazy { enSettings.associateBy { it.key } }
    private val zhGroups: List<PiSettingsGroup> by lazy { buildGroups(zh = true) }
    private val enGroups: List<PiSettingsGroup> by lazy { buildGroups(zh = false) }

    /** Every registered key, reactive to current app language. */
    val settings: List<PiSetting> get() = if (PiLocale.isZh) zhSettings else enSettings

    /** Registry lookup by exact dotted key, reactive to current app language. */
    val byKey: Map<String, PiSetting> get() = if (PiLocale.isZh) zhByKey else enByKey

    /** Keys written by App and consumed directly by pi SDK. */
    val piOwnedKeys: Map<String, String> = mapOf(
        "cacheWarming" to "settings-manager.js getCacheWarmingMode -> core/sdk.js CacheWarmer",
        "warnings.anthropicExtraUsage" to "settings-manager.js getWarnings / setWarnings",
        "transport" to "settings-manager.js getTransport -> core/sdk.js",
        "modelThinkingLevels" to "settings-manager.js -> core/sdk.js",
        "thinkingBudgets" to "settings-manager.js -> core/sdk.js",
        "httpIdleTimeoutMs" to "settings-manager.js -> core/http-dispatcher.js",
        "websocketConnectTimeoutMs" to "settings-manager.js -> core/sdk.js",
        "httpProxy" to "settings-manager.js -> core/http-dispatcher.js",
        "compaction.reserveTokens" to "settings-manager.js -> core/agent-session.js",
        "compaction.keepRecentTokens" to "settings-manager.js -> core/agent-session.js",
        "compaction.modelOverrides" to "settings-manager.js -> core/agent-session.js",
        "branchSummary.reserveTokens" to "settings-manager.js -> core/agent-session.js",
        "retry.maxRetries" to "settings-manager.js -> core/agent-session.js",
        "retry.baseDelayMs" to "settings-manager.js -> core/agent-session.js",
        "retry.maxAgentDelayMs" to "settings-manager.js -> core/agent-session.js",
        "retry.provider.timeoutMs" to "settings-manager.js -> core/sdk.js",
        "retry.provider.maxRetries" to "settings-manager.js -> core/sdk.js",
        "retry.provider.maxRetryDelayMs" to "settings-manager.js -> core/sdk.js",
        "defaultThinkingLevel" to "settings-manager.js getDefaultThinkingLevel",
        "enabledModels" to "agent-session.js getEnabledModels",
        "defaultTools" to "settings-manager.js getDefaultTools -> core/sdk.js (written by ToolsScreen)",
        "images.autoResize" to "settings-manager.js -> core/agent-session.js",
        "images.blockImages" to "settings-manager.js -> core/sdk.js",
        "enableInstallTelemetry" to "settings-manager.js -> core/telemetry.js",
        "enableAnalytics" to "settings-manager.js getEnableAnalytics / setEnableAnalytics",
        "codemode.mode" to "extensions/codemode/index.js pi.getSettings().codemode.mode",
        "codemode.inlineBudget" to "extensions/codemode/index.js pi.getSettings().codemode.inlineBudget",
        "shellCommandPrefix" to "settings-manager.js getShellCommandPrefix -> core/tools/bash.js",
    )

    val groups: List<PiSettingsGroup> get() = if (PiLocale.isZh) zhGroups else enGroups

    fun group(id: String): PiSettingsGroup? = groups.firstOrNull { it.id == id }

    fun groupTitle(id: String): String = when (id) {
        G_MODELS_SCREEN -> tr("模型与供应商", "Models & Providers")
        else -> group(id)?.title ?: id
    }

    /** Rows of one group, in registry order. */
    fun settingsIn(groupId: String): List<PiSetting> = settings.filter { it.group == groupId }

    /** Distinct section names of a group, in the order they first appear. */
    fun sectionsIn(groupId: String): List<String> =
        settingsIn(groupId).map { it.section }.distinct()

    /** Human summary of one key, falling back to the row's default. */
    fun summaryText(store: PiSettingsStore, key: String): String {
        val setting = byKey[key] ?: return tr("未设置", "Not set")
        return setting.display(setting.current(store))
    }
}
