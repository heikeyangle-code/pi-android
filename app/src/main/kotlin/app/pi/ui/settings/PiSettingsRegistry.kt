package app.pi.ui.settings

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import app.pi.ui.components.EffectiveKind
import app.pi.ui.theme.PiThinkingLevel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The settings registry: one entry per key pi can read from
 * `~/.pi/agent/settings.json` or `.pi/settings.json`, plus the App-local keys
 * the spec's group list needs that pi itself stores elsewhere or not at all.
 *
 * Field-level truth is `packages/coding-agent/docs/settings.md` and the
 * `Settings` interface in `core/settings-manager.ts` of pi itself — titles and
 * descriptions here are translations of that text, not inventions, because the
 * spec requires each row to explain itself the way pi's own docs do.
 *
 * Two shapes are deliberately preserved rather than normalised:
 *
 *  - `theme` may hold the literal string `"lightTheme/darkTheme"` for automatic
 *    light/dark switching. It is one string, never two fields.
 *  - Array settings accept glob syntax plus `!pattern` (exclude), `+path`
 *    (force include) and `-path` (force exclude). The editors keep those
 *    markers verbatim and never rewrite an entry as a plain path.
 */

/** The six row kinds of docs/pi-android-ui-spec.md §6.2. */
enum class PiRowKind { Switch, Value, Number, Text, List, Action }

/** Whether a list-valued setting is a JSON array or a JSON object map. */
enum class PiValueContainer { Array, Object }

/**
 * One choice of an enumerated (Value) setting, with its wire form.
 *
 * [wireIsScalar] marks choices pi stores as a JSON boolean or number rather than
 * a string: `outputPad` is `0 | 1`, `terminal.hyperlinks` and
 * `terminal.trueColor` are `boolean | "auto"`, and `terminal.images` ends in
 * `false`. Writing `"true"` where pi expects `true` fails its schema, so the
 * distinction has to survive all the way to the store.
 */
data class PiOption(
    val wire: String,
    val label: String,
    val description: String? = null,
    val wireIsScalar: Boolean = false,
)

/** Metadata for a single settings key. */
data class PiSetting(
    /** Dotted path as stored in JSON, e.g. `compaction.reserveTokens`. */
    val key: String,
    val title: String,
    val description: String,
    val kind: PiRowKind,
    val group: String,
    val section: String,
    /** null means pi has no built-in default and the key is simply absent. */
    val defaultValue: JsonElement? = null,
    val effective: EffectiveKind = EffectiveKind.Immediate,
    val options: List<PiOption> = emptyList(),
    val container: PiValueContainer = PiValueContainer.Array,
    /** Known universe for list rows, offered as toggle chips. */
    val presets: List<String> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
    val step: Int? = null,
    val unit: String? = null,
    val globAware: Boolean = false,
    /** Value rows whose choices are open-ended (models, providers) allow typing. */
    val allowCustom: Boolean = false,
    val readOnly: Boolean = false,
    val dangerous: Boolean = false,
    /** Slash commands and alternate spellings that should also find this row. */
    val aliases: List<String> = emptyList(),
    /** 2 or 3 means the row opens a further editor (spec §6.1 L2 / L3). */
    val depth: Int = 1,
    val emptyListLabel: String = "空",
) {
    /**
     * The JSON value to store when [wire] is picked.
     *
     * Known choices carry their own scalar-ness; a value the user typed into a
     * custom field is always a string, which is what pi expects for an open
     * ended key such as `defaultModel`.
     */
    fun wireElement(wire: String): JsonElement {
        val option = options.firstOrNull { it.wire == wire }
        return if (option?.wireIsScalar == true) scalarOf(wire) else JsonPrimitive(wire)
    }
}

/** One of the 14 groups of docs/pi-android-ui-spec.md §6.4. */
class PiSettingsGroup(
    val id: String,
    val title: String,
    val icon: ImageVector,
    /** Live "current value summary" shown on level 0 (spec §6.1). */
    val summary: (PiSettingsStore) -> String,
)

/** A search result: the setting plus the breadcrumb the spec asks us to show. */
data class PiSearchHit(
    val setting: PiSetting,
    val score: Int,
    val groupTitle: String,
    val section: String,
) {
    val breadcrumb: String get() = "$groupTitle · $section"
}

private const val G_MODEL = "model"
private const val G_MESSAGES = "messages"
private const val G_COMPACTION = "compaction"
private const val G_RETRY = "retry"
private const val G_TOOLS = "tools"
private const val G_SESSIONS = "sessions"
private const val G_RESOURCES = "resources"
private const val G_APPEARANCE = "appearance"
private const val G_TERMINAL = "terminal"
private const val G_INTERACTION = "interaction"
private const val G_SECURITY = "security"
private const val G_RUNTIME = "runtime"
private const val G_ABOUT = "about"

private fun str(value: String): JsonElement = JsonPrimitive(value)

private fun bool(value: Boolean): JsonElement = JsonPrimitive(value)

private fun num(value: Int): JsonElement = JsonPrimitive(value)

private fun list(vararg values: String): JsonElement = JsonArray(values.map { JsonPrimitive(it) })

private fun choices(vararg pairs: Pair<String, String>): List<PiOption> =
    pairs.map { (wire, label) -> PiOption(wire, label) }

private val thinkingLevelOptions: List<PiOption> =
    PiThinkingLevel.entries.map { PiOption(it.wire, it.label) }

/**
 * pi's 40 built-in providers (docs/pi-android-app-design.md §10.2), with the
 * ones that are directly reachable from the mainland listed first.
 */
private val providerOptions: List<PiOption> = choices(
    "deepseek" to "DeepSeek",
    "minimax" to "MiniMax",
    "minimax-cn" to "MiniMax（国内）",
    "moonshotai" to "Moonshot AI",
    "moonshotai-cn" to "Moonshot AI（国内）",
    "zai" to "Z.ai",
    "zai-coding-cn" to "Z.ai Coding（国内）",
    "kimi-coding" to "Kimi Coding",
    "qwen-token-plan" to "Qwen Token Plan",
    "qwen-token-plan-cn" to "Qwen Token Plan（国内）",
    "qwen-token-plan-individual" to "Qwen Token Plan（个人）",
    "xiaomi" to "Xiaomi",
    "xiaomi-token-plan-cn" to "Xiaomi Token Plan（国内）",
    "xiaomi-token-plan-ams" to "Xiaomi Token Plan（AMS）",
    "xiaomi-token-plan-sgp" to "Xiaomi Token Plan（SGP）",
    "opencode" to "OpenCode",
    "opencode-go" to "OpenCode Go",
    "anthropic" to "Anthropic",
    "openai" to "OpenAI",
    "google" to "Google",
    "google-vertex" to "Google Vertex",
    "amazon-bedrock" to "Amazon Bedrock",
    "azure-openai-responses" to "Azure OpenAI",
    "openai-codex" to "OpenAI Codex",
    "github-copilot" to "GitHub Copilot",
    "xai" to "xAI",
    "groq" to "Groq",
    "cerebras" to "Cerebras",
    "mistral" to "Mistral",
    "openrouter" to "OpenRouter",
    "vercel-ai-gateway" to "Vercel AI Gateway",
    "together" to "Together",
    "fireworks" to "Fireworks",
    "baseten" to "Baseten",
    "nvidia" to "NVIDIA NIM",
    "huggingface" to "Hugging Face",
    "cloudflare-workers-ai" to "Cloudflare Workers AI",
    "cloudflare-ai-gateway" to "Cloudflare AI Gateway",
    "ant-ling" to "Ant Ling",
    "radius" to "Radius",
    "llama.cpp" to "本地 llama.cpp",
)

private val transportOptions = choices(
    "auto" to "自动",
    "sse" to "SSE",
    "websocket" to "WebSocket",
    "websocket-cached" to "WebSocket（缓存）",
)

private val deliveryOptions = choices(
    "one-at-a-time" to "逐条",
    "all" to "全部",
)

private val trustOptions = choices(
    "ask" to "询问",
    "always" to "总是信任",
    "never" to "从不信任",
)

private val mermaidOptions = choices(
    "off" to "关闭",
    "final" to "完成后",
    "streaming" to "流式中",
)

private val tripleStateOptions = listOf(
    PiOption("auto", "自动"),
    PiOption("true", "强制开启", wireIsScalar = true),
    PiOption("false", "强制关闭", wireIsScalar = true),
)

private val imageProtocolOptions = listOf(
    PiOption("auto", "自动"),
    PiOption("kitty", "Kitty"),
    PiOption("iterm2", "iTerm2"),
    PiOption("false", "关闭", wireIsScalar = true),
)

private val outputPadOptions = listOf(
    PiOption("0", "无内边距", wireIsScalar = true),
    PiOption("1", "有内边距", wireIsScalar = true),
)

private val builtinTools = listOf("read", "bash", "powershell", "edit", "write", "grep", "find", "ls")

private val gesturePresets = listOf("swipe-to-delete", "swipe-to-pin", "double-tap-to-top", "pull-to-refresh")

private val termKeyBarPresets = listOf("esc", "tab", "ctrl", "up", "down", "left", "right", "pipe", "tilde", "slash", "dash")

object PiSettingsCatalog {

    /** Every registered key, in spec §6.4 order. */
    val settings: List<PiSetting> = listOf(

        // ------------------------------------------------------------------
        // 1 模型与推理
        // ------------------------------------------------------------------
        PiSetting(
            key = "defaultProvider",
            title = "默认厂商",
            description = "启动时使用的厂商，例如 anthropic、openai。在 pi 里用 /model 选中模型后按 Ctrl+S 保存。",
            kind = PiRowKind.Value,
            group = G_MODEL,
            section = "默认模型",
            effective = EffectiveKind.NewSession,
            options = providerOptions,
            allowCustom = true,
            aliases = listOf("model", "provider"),
        ),
        PiSetting(
            key = "defaultModel",
            title = "默认模型",
            description = "启动时使用的模型 ID。可用模型来自厂商目录，也可以直接填写自定义模型。",
            kind = PiRowKind.Value,
            group = G_MODEL,
            section = "默认模型",
            effective = EffectiveKind.NewSession,
            allowCustom = true,
            aliases = listOf("model"),
        ),
        PiSetting(
            key = "defaultThinkingLevel",
            title = "默认思考等级",
            description = "启动时的思考等级：off、minimal、low、medium、high、xhigh、max。pi 里用 /thinking 选中后按 Ctrl+S 保存。",
            kind = PiRowKind.Value,
            group = G_MODEL,
            section = "默认模型",
            effective = EffectiveKind.NewSession,
            options = thinkingLevelOptions,
            aliases = listOf("thinking"),
        ),
        PiSetting(
            key = "modelThinkingLevels",
            title = "逐模型思考等级",
            description = "按 \"厂商/模型 ID\" 覆盖启动思考等级。键必须精确匹配，不认通配符。",
            kind = PiRowKind.List,
            group = G_MODEL,
            section = "默认模型",
            effective = EffectiveKind.NewSession,
            container = PiValueContainer.Object,
            depth = 3,
            emptyListLabel = "无覆盖",
            aliases = listOf("thinking", "model-thinking"),
        ),
        PiSetting(
            key = "thinkingBudgets",
            title = "思考预算",
            description = "逐等级的思考 token 预算（minimal/low/medium/high）。Anthropic、Google、Bedrock 原生使用；OpenAI 兼容模型需要模型声明支持。",
            kind = PiRowKind.List,
            group = G_MODEL,
            section = "默认模型",
            effective = EffectiveKind.NewSession,
            container = PiValueContainer.Object,
            depth = 3,
            emptyListLabel = "未设置",
            aliases = listOf("thinking", "budget"),
        ),
        PiSetting(
            key = "hideThinkingBlock",
            title = "隐藏思考块",
            description = "隐藏输出中的思考块。思考仍然发生，只是不显示在对话流里。",
            kind = PiRowKind.Switch,
            group = G_MODEL,
            section = "行为",
            defaultValue = bool(false),
            aliases = listOf("thinking"),
        ),
        PiSetting(
            key = "showCacheMissNotices",
            title = "缓存未命中提示",
            description = "提示严重的提示词缓存未命中、压缩与分支摘要的用量、以及厂商恢复诊断（例如 Anthropic 思考块被丢弃）。",
            kind = PiRowKind.Switch,
            group = G_MODEL,
            section = "行为",
            defaultValue = bool(false),
            aliases = listOf("cache"),
        ),
        PiSetting(
            key = "transport",
            title = "传输方式",
            description = "支持多种传输的厂商优先使用哪一种。自动会挑当前最合适的一种。",
            kind = PiRowKind.Value,
            group = G_MODEL,
            section = "传输",
            defaultValue = str("auto"),
            effective = EffectiveKind.NewSession,
            options = transportOptions,
            aliases = listOf("websocket", "sse"),
        ),
        PiSetting(
            key = "enabledModels",
            title = "循环模型",
            description = "Ctrl+P 循环切换时使用的模型模式，格式与 --models 命令行参数相同，支持通配符。",
            kind = PiRowKind.List,
            group = G_MODEL,
            section = "循环模型",
            effective = EffectiveKind.Immediate,
            presets = listOf("claude-*", "gpt-*", "gemini-*", "deepseek-*", "qwen-*"),
            globAware = true,
            depth = 2,
            emptyListLabel = "全部模型",
            aliases = listOf("cycle", "model"),
        ),
        PiSetting(
            key = "app.credentials.apiKey",
            title = "API Key",
            description = "厂商 API Key 表单。凭证写在 ~/.pi/agent/auth.json（0600），不属于 settings.json。",
            kind = PiRowKind.Action,
            group = G_MODEL,
            section = "凭证",
            aliases = listOf("auth", "login", "key"),
        ),
        PiSetting(
            key = "app.credentials.oauth",
            title = "OAuth 登录",
            description = "Anthropic Claude Pro/Max、OpenAI Codex、GitHub Copilot、OpenRouter、Kimi Code、xAI、Radius 支持 OAuth，登录在系统浏览器里完成。",
            kind = PiRowKind.Action,
            group = G_MODEL,
            section = "凭证",
            aliases = listOf("login", "auth", "oauth"),
        ),
        PiSetting(
            key = "app.localModels.manage",
            title = "本地模型（llama.cpp）",
            description = "连接外部 llama.cpp router（默认 http://127.0.0.1:8080），加载/卸载模型并从 HuggingFace 下载 GGUF。对应 pi 的 /llama。",
            kind = PiRowKind.Action,
            group = G_MODEL,
            section = "凭证",
            aliases = listOf("llama", "local"),
        ),

        // ------------------------------------------------------------------
        // 2 消息与队列
        // ------------------------------------------------------------------
        PiSetting(
            key = "steeringMode",
            title = "穿插模式",
            description = "流式中发送的消息怎么送达：逐条（等当前这条处理完再给下一条）或全部一起给。",
            kind = PiRowKind.Value,
            group = G_MESSAGES,
            section = "送达",
            defaultValue = str("one-at-a-time"),
            options = deliveryOptions,
            aliases = listOf("steer", "queue"),
        ),
        PiSetting(
            key = "followUpMode",
            title = "后续模式",
            description = "排队等到本轮结束的消息怎么送达：逐条或全部一起给。",
            kind = PiRowKind.Value,
            group = G_MESSAGES,
            section = "送达",
            defaultValue = str("one-at-a-time"),
            options = deliveryOptions,
            aliases = listOf("followup", "queue"),
        ),
        PiSetting(
            key = "httpIdleTimeoutMs",
            title = "HTTP 空闲超时",
            description = "HTTP 头/体的空闲超时（毫秒），有显式流空闲超时的厂商也用它。设为 0 表示不超时。",
            kind = PiRowKind.Number,
            group = G_MESSAGES,
            section = "网络",
            defaultValue = num(300000),
            min = 0,
            max = 3600000,
            step = 1000,
            unit = "ms",
            aliases = listOf("timeout"),
        ),
        PiSetting(
            key = "websocketConnectTimeoutMs",
            title = "WebSocket 连接超时",
            description = "WebSocket 建连握手超时（毫秒），只对支持 WebSocket 传输的厂商生效。设为 0 表示不超时。",
            kind = PiRowKind.Number,
            group = G_MESSAGES,
            section = "网络",
            defaultValue = num(15000),
            min = 0,
            max = 300000,
            step = 1000,
            unit = "ms",
            aliases = listOf("websocket", "timeout"),
        ),
        PiSetting(
            key = "httpProxy",
            title = "HTTP 代理",
            description = "代理地址，会作为 HTTP_PROXY 与 HTTPS_PROXY 应用。只对 pi 自己发起的请求生效，仅全局设置支持。",
            kind = PiRowKind.Text,
            group = G_MESSAGES,
            section = "网络",
            defaultValue = str(""),
            effective = EffectiveKind.NewSession,
            aliases = listOf("proxy"),
        ),

        // ------------------------------------------------------------------
        // 3 上下文与压缩
        // ------------------------------------------------------------------
        PiSetting(
            key = "compaction.enabled",
            title = "自动压缩",
            description = "开启后 pi 会在上下文接近上限时自动压缩历史，避免请求溢出。",
            kind = PiRowKind.Switch,
            group = G_COMPACTION,
            section = "压缩",
            defaultValue = bool(true),
            aliases = listOf("compact", "autocompact"),
        ),
        PiSetting(
            key = "compaction.reserveTokens",
            title = "保留 token",
            description = "为模型回复预留的 token 数。设为 0 会完全没有回复余量，并把摘要输出预算也归零。",
            kind = PiRowKind.Number,
            group = G_COMPACTION,
            section = "压缩",
            defaultValue = num(16384),
            min = 0,
            max = 1000000,
            step = 1024,
            unit = "token",
            aliases = listOf("reserveTokens", "compact"),
        ),
        PiSetting(
            key = "compaction.keepRecentTokens",
            title = "保留最近 token",
            description = "最近的多少 token 不参与摘要、原样保留。",
            kind = PiRowKind.Number,
            group = G_COMPACTION,
            section = "压缩",
            defaultValue = num(20000),
            min = 0,
            max = 1000000,
            step = 1024,
            unit = "token",
            aliases = listOf("keepRecentTokens", "compact"),
        ),
        PiSetting(
            key = "compaction.modelOverrides",
            title = "逐模型覆盖",
            description = "按精确的 \"厂商/模型 ID\" 覆盖 reserveTokens 与 keepRecentTokens。模型 ID 可以含斜杠，例如 openrouter/anthropic/claude-sonnet-4。",
            kind = PiRowKind.List,
            group = G_COMPACTION,
            section = "压缩",
            container = PiValueContainer.Object,
            depth = 3,
            emptyListLabel = "无覆盖",
            aliases = listOf("override", "compact"),
        ),
        PiSetting(
            key = "branchSummary.reserveTokens",
            title = "分支摘要保留 token",
            description = "选择分支历史时为提示词与回复预留的 token（输出上限 4096）。",
            kind = PiRowKind.Number,
            group = G_COMPACTION,
            section = "分支摘要",
            defaultValue = num(16384),
            min = 0,
            max = 1000000,
            step = 1024,
            unit = "token",
            aliases = listOf("branch", "summary"),
        ),
        PiSetting(
            key = "branchSummary.skipPrompt",
            title = "跳过「要摘要吗」询问",
            description = "在 /tree 跳转分支时不再询问是否生成分支摘要，默认按「不生成」处理。",
            kind = PiRowKind.Switch,
            group = G_COMPACTION,
            section = "分支摘要",
            defaultValue = bool(false),
            aliases = listOf("branch", "tree"),
        ),
        PiSetting(
            key = "app.compaction.runNow",
            title = "立即压缩",
            description = "对当前会话手动触发一次压缩，可以附带一条自定义指令告诉模型压缩时关注什么。",
            kind = PiRowKind.Action,
            group = G_COMPACTION,
            section = "动作",
            aliases = listOf("compact"),
        ),

        // ------------------------------------------------------------------
        // 4 重试与网络
        // ------------------------------------------------------------------
        PiSetting(
            key = "retry.enabled",
            title = "自动重试",
            description = "遇到瞬时错误时自动重试整轮请求。",
            kind = PiRowKind.Switch,
            group = G_RETRY,
            section = "重试",
            defaultValue = bool(true),
            aliases = listOf("retry", "auto-retry"),
        ),
        PiSetting(
            key = "retry.maxRetries",
            title = "最大重试次数",
            description = "整轮请求最多重试几次。",
            kind = PiRowKind.Number,
            group = G_RETRY,
            section = "重试",
            defaultValue = num(3),
            min = 0,
            max = 20,
            step = 1,
            unit = "次",
            aliases = listOf("retry"),
        ),
        PiSetting(
            key = "retry.baseDelayMs",
            title = "基础延迟",
            description = "指数退避的起始延迟，2s、4s、8s 这样增长。",
            kind = PiRowKind.Number,
            group = G_RETRY,
            section = "重试",
            defaultValue = num(2000),
            min = 0,
            max = 60000,
            step = 100,
            unit = "ms",
            aliases = listOf("retry", "backoff"),
        ),
        PiSetting(
            key = "retry.maxAgentDelayMs",
            title = "最大重试延迟",
            description = "单次重试等待的上限，默认 60 秒。长时间故障时保证仍然可响应。",
            kind = PiRowKind.Number,
            group = G_RETRY,
            section = "重试",
            defaultValue = num(60000),
            min = 0,
            max = 600000,
            step = 1000,
            unit = "ms",
            aliases = listOf("retry", "backoff"),
        ),
        PiSetting(
            key = "retry.provider.timeoutMs",
            title = "Provider 超时",
            description = "Provider/SDK 单次请求的超时（毫秒）。留空表示沿用 SDK 默认值。",
            kind = PiRowKind.Number,
            group = G_RETRY,
            section = "Provider 重试（高级）",
            min = 0,
            max = 3600000,
            step = 1000,
            unit = "ms",
            aliases = listOf("retry", "timeout", "provider"),
        ),
        PiSetting(
            key = "retry.provider.maxRetries",
            title = "Provider 重试次数",
            description = "Provider/SDK 自己的重试次数，默认 0。调高后用量上限一类的错误可能先被 SDK 吞掉并长时间阻塞，除非确有需要否则保持 0。",
            kind = PiRowKind.Number,
            group = G_RETRY,
            section = "Provider 重试（高级）",
            defaultValue = num(0),
            min = 0,
            max = 20,
            step = 1,
            unit = "次",
            aliases = listOf("retry", "provider"),
        ),
        PiSetting(
            key = "retry.provider.maxRetryDelayMs",
            title = "Provider 最大延迟",
            description = "厂商要求的等待超过这个值时直接失败，而不是默默等待。设为 0 表示不限制。",
            kind = PiRowKind.Number,
            group = G_RETRY,
            section = "Provider 重试（高级）",
            defaultValue = num(60000),
            min = 0,
            max = 3600000,
            step = 1000,
            unit = "ms",
            aliases = listOf("retry", "provider"),
        ),

        // ------------------------------------------------------------------
        // 5 工具
        // ------------------------------------------------------------------
        PiSetting(
            key = "defaultTools",
            title = "内建工具",
            description = "启动时启用的内建工具。整表替换而不是合并：项目设置里的数组会覆盖全局数组。扩展与 SDK 自定义工具始终启用。pi 的标准默认是 read、bash、edit、write；App 建议额外开启 grep、find、ls，否则模型只能退回 bash 跑 rg 和 fd，手机上更慢。",
            kind = PiRowKind.List,
            group = G_TOOLS,
            section = "内建工具",
            effective = EffectiveKind.NewSession,
            presets = builtinTools,
            depth = 2,
            emptyListLabel = "默认 read/bash/edit/write",
            aliases = listOf("tools", "builtin"),
        ),
        // Two rows were deleted here rather than wired, because nothing in the
        // app or on pi's wire can honour them:
        //
        //  - `app.tools.bashTimeoutSeconds`: the `bash` RPC command carries only
        //    `command` and `excludeFromContext` (`rpc-types.ts:55`), and pi's own
        //    bash tool takes its timeout as a per-call argument the model chooses
        //    (`core/tools/bash.ts:234`). There is no setting and no field to set.
        //  - `app.tools.outputMaxLines`: pi's truncation limits are compiled
        //    constants, not a setting (`core/tools/truncate.ts:11`,
        //    `DEFAULT_MAX_LINES = 2000`).
        PiSetting(
            key = "app.tools.expandByDefault",
            title = "工具输出默认展开",
            description = "对话流里的工具卡默认展开输出，而不是只显示一行摘要。手机上通常关掉更省事。",
            kind = PiRowKind.Switch,
            group = G_TOOLS,
            section = "展示",
            defaultValue = bool(false),
            aliases = listOf("tools", "expand"),
        ),

        // ------------------------------------------------------------------
        // 6 会话
        // ------------------------------------------------------------------
        PiSetting(
            key = "sessionDir",
            title = "会话目录",
            description = "会话文件的存放目录。优先级是 --session-dir、PI_CODING_AGENT_SESSION_DIR，最后才是这项设置。改动需要重启 App。",
            kind = PiRowKind.Text,
            group = G_SESSIONS,
            section = "存储",
            defaultValue = str(""),
            effective = EffectiveKind.RestartApp,
            aliases = listOf("sessions", "dir"),
        ),
        PiSetting(
            key = "app.sessions.import",
            title = "导入会话",
            description = "从 JSONL 文件导入一个会话到当前会话目录。",
            kind = PiRowKind.Action,
            group = G_SESSIONS,
            section = "动作",
            aliases = listOf("import", "jsonl"),
        ),
        PiSetting(
            key = "app.sessions.exportAll",
            title = "导出全部会话",
            description = "把会话目录里的全部会话导出为 HTML 或 JSONL，便于备份与分享。",
            kind = PiRowKind.Action,
            group = G_SESSIONS,
            section = "动作",
            aliases = listOf("export"),
        ),
        PiSetting(
            key = "app.sessions.cleanupPolicy",
            title = "清理策略",
            description = "App 侧对旧会话文件的清理策略。pi 本身不删除会话，删除动作始终可撤销。",
            kind = PiRowKind.Value,
            group = G_SESSIONS,
            section = "存储",
            defaultValue = str("never"),
            options = choices(
                "never" to "从不自动清理",
                "30d" to "保留 30 天",
                "90d" to "保留 90 天",
                "count" to "只保留最近 500 个",
            ),
            aliases = listOf("cleanup", "prune"),
        ),
        PiSetting(
            key = "app.sessions.resumeLast",
            title = "启动续接最近会话",
            description = "启动 App 时自动切到最近一次会话，对应 pi 的 -c / --continue。默认关闭，避免把「打开就是新会话」变成意外。",
            kind = PiRowKind.Switch,
            group = G_SESSIONS,
            section = "存储",
            defaultValue = bool(false),
            effective = EffectiveKind.RestartApp,
            aliases = listOf("continue", "resume", "last"),
        ),

        // ------------------------------------------------------------------
        // 7 扩展与资源
        // ------------------------------------------------------------------
        PiSetting(
            key = "extensions",
            title = "扩展",
            description = "本地扩展文件或目录的路径。相对路径在全局设置里相对 ~/.pi/agent、在项目设置里相对 .pi。",
            kind = PiRowKind.List,
            group = G_RESOURCES,
            section = "本地资源",
            defaultValue = list(),
            effective = EffectiveKind.Reload,
            globAware = true,
            depth = 2,
            aliases = listOf("extensions", "reload"),
        ),
        PiSetting(
            key = "packages",
            title = "资源包",
            description = "要加载资源的 npm / git 包。字符串形式加载包内全部资源；对象形式可以按 extensions/skills/prompts/themes 过滤，并用 autoload: false 关闭自动加载。",
            kind = PiRowKind.List,
            group = G_RESOURCES,
            section = "资源包",
            defaultValue = list(),
            effective = EffectiveKind.Reload,
            globAware = true,
            depth = 3,
            aliases = listOf("install", "packages", "npm", "git"),
        ),
        PiSetting(
            key = "packages[].autoload",
            title = "资源包自动加载",
            description = "新建资源包条目时的默认 autoload 标志。对象形式的条目设 autoload: false 表示从空开始，只应用显式写出的资源模式。",
            kind = PiRowKind.Switch,
            group = G_RESOURCES,
            section = "资源包",
            defaultValue = bool(true),
            effective = EffectiveKind.Reload,
            aliases = listOf("autoload", "packages"),
        ),
        PiSetting(
            key = "skills",
            title = "技能",
            description = "本地技能文件或目录的路径。每个技能是一个 SKILL.md，pi 按 name/description 规则校验。",
            kind = PiRowKind.List,
            group = G_RESOURCES,
            section = "本地资源",
            defaultValue = list(),
            effective = EffectiveKind.Reload,
            globAware = true,
            depth = 2,
            aliases = listOf("skills", "reload"),
        ),
        PiSetting(
            key = "prompts",
            title = "提示模板",
            description = "本地提示模板路径。模板支持 \$1、\$@ 与 \${1:-default} 形式的参数。",
            kind = PiRowKind.List,
            group = G_RESOURCES,
            section = "本地资源",
            defaultValue = list(),
            effective = EffectiveKind.Reload,
            globAware = true,
            depth = 2,
            aliases = listOf("prompts", "templates", "reload"),
        ),
        PiSetting(
            key = "themes",
            title = "主题",
            description = "本地主题文件或目录的路径。pi 主题是 51 个必需令牌加 5 个可选令牌的 JSON，导入后与桌面共用同一套文件。",
            kind = PiRowKind.List,
            group = G_RESOURCES,
            section = "本地资源",
            defaultValue = list(),
            effective = EffectiveKind.Reload,
            globAware = true,
            depth = 2,
            aliases = listOf("themes", "reload"),
        ),
        PiSetting(
            key = "enableSkillCommands",
            title = "技能命令",
            description = "把技能注册成 /skill:name 斜杠命令。关掉之后技能仍然可以被模型读取，只是不再出现在命令面板里。",
            kind = PiRowKind.Switch,
            group = G_RESOURCES,
            section = "本地资源",
            defaultValue = bool(true),
            effective = EffectiveKind.Reload,
            aliases = listOf("skills", "commands"),
        ),
        PiSetting(
            key = "app.contextFiles",
            title = "上下文文件",
            description = "AGENTS.md 与 SYSTEM.md 的全局版与项目版。它们会被追加进系统提示，改动需要重载。",
            kind = PiRowKind.List,
            group = G_RESOURCES,
            section = "上下文文件",
            effective = EffectiveKind.Reload,
            presets = listOf("~/.pi/agent/AGENTS.md", "~/.pi/agent/SYSTEM.md", ".pi/AGENTS.md", ".pi/SYSTEM.md"),
            depth = 2,
            emptyListLabel = "未发现",
            aliases = listOf("agents", "system", "context", "reload"),
        ),

        // ------------------------------------------------------------------
        // 8 外观
        // ------------------------------------------------------------------
        PiSetting(
            key = "theme",
            title = "主题",
            description = "主题名：dark、light 或自定义主题。自动模式把「浅色主题名/深色主题名」作为一个字面量字符串保存，App 原样往返，不拆成两个字段。",
            kind = PiRowKind.Value,
            group = G_APPEARANCE,
            section = "主题",
            defaultValue = str("dark"),
            effective = EffectiveKind.Reload,
            allowCustom = true,
            aliases = listOf("appearance", "dark", "light", "reload"),
        ),
        // `app.appearance.dynamicColor` was deleted here. Android dynamic colour
        // cannot coexist with the rule this app runs on — every colour comes from
        // `PiTheme.palette`, which *is* the resolved pi theme — so the switch
        // could never have been honoured without breaking the theme contract.
        // Colour always comes from the theme file (see `ui/theme/PiThemeFiles.kt`).
        PiSetting(
            key = "app.appearance.fontScaleDelta",
            title = "字号微调",
            description = "在系统字号基础上再加减 2sp。正文默认 15/23，元信息 11.5/16。",
            kind = PiRowKind.Number,
            group = G_APPEARANCE,
            section = "排版",
            defaultValue = num(0),
            min = -2,
            max = 2,
            step = 1,
            unit = "sp",
            aliases = listOf("font", "size"),
        ),
        PiSetting(
            key = "app.appearance.messageDensity",
            title = "消息密度",
            description = "对话流的块间距与内边距档位。舒适是默认值，紧凑更适合小屏一次看更多内容。",
            kind = PiRowKind.Value,
            group = G_APPEARANCE,
            section = "排版",
            defaultValue = str("comfortable"),
            options = choices(
                "comfortable" to "舒适",
                "compact" to "紧凑",
                "cozy" to "宽松",
            ),
            aliases = listOf("density", "spacing"),
        ),
        PiSetting(
            key = "app.appearance.showTimestamps",
            title = "显示时间戳",
            description = "每轮首次出现处显示一次时间戳；跨天时插入日期分隔。关掉之后对话流只剩内容。",
            kind = PiRowKind.Switch,
            group = G_APPEARANCE,
            section = "排版",
            defaultValue = bool(true),
            aliases = listOf("timestamp", "time"),
        ),
        PiSetting(
            key = "app.appearance.thinkingCollapsedByDefault",
            title = "思考块默认折叠",
            description = "思考块默认收起成一行「思考 12s」，点击展开。与 pi 的 hideThinkingBlock 不同，那只控制显不显示。",
            kind = PiRowKind.Switch,
            group = G_APPEARANCE,
            section = "排版",
            defaultValue = bool(true),
            aliases = listOf("thinking", "collapse"),
        ),
        PiSetting(
            key = "outputPad",
            title = "消息内边距 outputPad",
            description = "用户消息、助手消息与思考块的横向内边距，取值 0 或 1。",
            kind = PiRowKind.Value,
            group = G_APPEARANCE,
            section = "pi 外观",
            defaultValue = num(1),
            options = outputPadOptions,
            aliases = listOf("outputPad", "padding"),
        ),
        PiSetting(
            key = "editorPaddingX",
            title = "编辑器水平内边距",
            description = "输入编辑器的横向内边距，取值 0 到 3。",
            kind = PiRowKind.Number,
            group = G_APPEARANCE,
            section = "pi 外观",
            defaultValue = num(0),
            min = 0,
            max = 3,
            step = 1,
            aliases = listOf("editorPaddingX", "padding"),
        ),
        PiSetting(
            key = "autocompleteMaxVisible",
            title = "补全最大可见项",
            description = "补全下拉最多同时显示几项，取值 3 到 20。",
            kind = PiRowKind.Number,
            group = G_APPEARANCE,
            section = "pi 外观",
            defaultValue = num(5),
            min = 3,
            max = 20,
            step = 1,
            unit = "项",
            aliases = listOf("autocomplete", "completion"),
        ),
        PiSetting(
            key = "markdown.codeBlockIndent",
            title = "代码块缩进",
            description = "代码块使用的缩进字符串，默认两个空格。",
            kind = PiRowKind.Text,
            group = G_APPEARANCE,
            section = "Markdown 与图片",
            defaultValue = str("  "),
            aliases = listOf("codeBlockIndent", "indent"),
        ),
        PiSetting(
            key = "markdown.mermaid",
            title = "Mermaid 渲染",
            description = "Mermaid 图渲染时机：关闭、等到消息完成、或流式中就渲染。流式渲染最费电。",
            kind = PiRowKind.Value,
            group = G_APPEARANCE,
            section = "Markdown 与图片",
            defaultValue = str("streaming"),
            options = mermaidOptions,
            aliases = listOf("mermaid", "diagram"),
        ),
        PiSetting(
            key = "images.autoResize",
            title = "图片自动缩放",
            description = "把图片缩到最大 2000x2000，对 @file 附件、read 工具与工具返回的图片都生效。",
            kind = PiRowKind.Switch,
            group = G_APPEARANCE,
            section = "Markdown 与图片",
            defaultValue = bool(true),
            aliases = listOf("images", "resize"),
        ),
        PiSetting(
            key = "images.blockImages",
            title = "屏蔽图片",
            description = "阻止所有图片发送给模型。开启后附件里的图片入口会禁用并说明原因。",
            kind = PiRowKind.Switch,
            group = G_APPEARANCE,
            section = "Markdown 与图片",
            defaultValue = bool(false),
            aliases = listOf("images", "block"),
        ),
        PiSetting(
            key = "quietStartup",
            title = "隐藏启动信息卡",
            description = "新会话不再显示启动头信息。",
            kind = PiRowKind.Switch,
            group = G_APPEARANCE,
            section = "启动",
            defaultValue = bool(false),
            aliases = listOf("startup", "quiet"),
        ),

        // ------------------------------------------------------------------
        // 9 终端与 Shell
        // ------------------------------------------------------------------
        PiSetting(
            key = "shellPath",
            title = "Shell 路径",
            description = "自定义 shell 路径，支持开头的 ~ 展开。改动需要新会话才生效。",
            kind = PiRowKind.Text,
            group = G_TERMINAL,
            section = "Shell",
            defaultValue = str(""),
            effective = EffectiveKind.NewSession,
            aliases = listOf("shell", "bash"),
        ),
        PiSetting(
            key = "shellCommandPrefix",
            title = "命令前缀",
            description = "每条 bash 命令前面都会拼上的前缀，例如 shopt -s expand_aliases 用来打开别名。",
            kind = PiRowKind.Text,
            group = G_TERMINAL,
            section = "Shell",
            defaultValue = str(""),
            effective = EffectiveKind.NewSession,
            aliases = listOf("shell", "prefix"),
        ),
        PiSetting(
            key = "npmCommand",
            title = "npm 命令",
            description = "npm 包查找与安装使用的 argv 数组，例如 mise exec node@20 -- npm。按进程启动参数逐项填写。",
            kind = PiRowKind.List,
            group = G_TERMINAL,
            section = "Shell",
            defaultValue = list(),
            effective = EffectiveKind.NewSession,
            container = PiValueContainer.Array,
            depth = 2,
            emptyListLabel = "默认 npm",
            aliases = listOf("npm", "packages"),
        ),
        PiSetting(
            key = "app.terminal.fontSize",
            title = "终端字号",
            description = "工作台终端页的字号。终端的等宽字体只在这里使用，不影响对话流的正文。",
            kind = PiRowKind.Number,
            group = G_TERMINAL,
            section = "终端显示",
            defaultValue = num(13),
            min = 10,
            max = 20,
            step = 1,
            unit = "sp",
            aliases = listOf("font", "terminal"),
        ),
        PiSetting(
            key = "app.terminal.cursorStyle",
            title = "光标样式",
            description = "终端光标画法。硬件光标打开时由系统绘制，样式设置不再生效。",
            kind = PiRowKind.Value,
            group = G_TERMINAL,
            section = "终端显示",
            defaultValue = str("block"),
            options = choices(
                "block" to "方块",
                "bar" to "竖线",
                "underline" to "下划线",
            ),
            aliases = listOf("cursor"),
        ),
        PiSetting(
            key = "app.terminal.scrollbackLines",
            title = "滚回行数",
            description = "终端保留的滚回缓冲行数，越大越占内存。",
            kind = PiRowKind.Number,
            group = G_TERMINAL,
            section = "终端显示",
            defaultValue = num(2000),
            min = 500,
            max = 50000,
            step = 500,
            unit = "行",
            aliases = listOf("scrollback"),
        ),
        PiSetting(
            key = "terminal.showImages",
            title = "终端显示图片",
            description = "在支持图片协议的终端里显示内联图片。",
            kind = PiRowKind.Switch,
            group = G_TERMINAL,
            section = "终端能力",
            defaultValue = bool(true),
            aliases = listOf("terminal", "images"),
        ),
        PiSetting(
            key = "terminal.imageWidthCells",
            title = "图片宽度",
            description = "内联图片的首选宽度，单位是终端字符格。",
            kind = PiRowKind.Number,
            group = G_TERMINAL,
            section = "终端能力",
            defaultValue = num(60),
            min = 10,
            max = 300,
            step = 5,
            unit = "格",
            aliases = listOf("imageWidthCells", "images"),
        ),
        PiSetting(
            key = "terminal.clearOnShrink",
            title = "收缩时清屏",
            description = "内容变少时清掉空行。某些终端上会闪屏，所以默认关闭。",
            kind = PiRowKind.Switch,
            group = G_TERMINAL,
            section = "终端能力",
            defaultValue = bool(false),
            aliases = listOf("clearOnShrink", "terminal"),
        ),
        PiSetting(
            key = "terminal.showTerminalProgress",
            title = "终端进度指示",
            description = "用 OSC 9;4 向终端上报任务进度。源码里有、文档里没写的字段，默认关闭。",
            kind = PiRowKind.Switch,
            group = G_TERMINAL,
            section = "终端能力",
            defaultValue = bool(false),
            aliases = listOf("showTerminalProgress", "progress"),
        ),
        PiSetting(
            key = "terminal.hyperlinks",
            title = "OSC 8 超链接",
            description = "覆盖 OSC 8 超链接支持检测：自动、强制开启（true）或强制关闭（false）。高级项，pi 的 /settings 里不提供，这里可以直接改，写回的是 JSON 布尔值。",
            kind = PiRowKind.Value,
            group = G_TERMINAL,
            section = "终端能力（高级）",
            defaultValue = str("auto"),
            options = tripleStateOptions,
            aliases = listOf("hyperlinks", "osc8"),
        ),
        PiSetting(
            key = "terminal.images",
            title = "图片协议",
            description = "覆盖图片协议检测：自动、kitty、iterm2 或关闭（false）。高级项，pi 的 /settings 里不提供，这里可以直接改，关闭时写回 JSON 布尔值 false。",
            kind = PiRowKind.Value,
            group = G_TERMINAL,
            section = "终端能力（高级）",
            defaultValue = str("auto"),
            options = imageProtocolOptions,
            aliases = listOf("images", "kitty", "iterm2"),
        ),
        PiSetting(
            key = "terminal.trueColor",
            title = "真彩色",
            description = "覆盖真彩色支持检测：自动、强制开启（true）或强制关闭（false）。高级项，pi 的 /settings 里不提供，这里可以直接改。",
            kind = PiRowKind.Value,
            group = G_TERMINAL,
            section = "终端能力（高级）",
            defaultValue = str("auto"),
            options = tripleStateOptions,
            aliases = listOf("trueColor", "color"),
        ),
        PiSetting(
            key = "app.terminal.keyBar",
            title = "键盘按键条",
            description = "终端页顶部按键条的按键与顺序，可增删。改动需要重载。",
            kind = PiRowKind.List,
            group = G_TERMINAL,
            section = "按键条",
            effective = EffectiveKind.Reload,
            presets = termKeyBarPresets,
            depth = 2,
            aliases = listOf("keybar", "keys"),
        ),

        // ------------------------------------------------------------------
        // 10 交互
        // ------------------------------------------------------------------
        PiSetting(
            key = "doubleEscapeAction",
            title = "双击返回动作",
            description = "输入框为空时连按两次返回（Esc）触发的动作：打开会话树、分叉新会话、或什么都不做。",
            kind = PiRowKind.Value,
            group = G_INTERACTION,
            section = "键位与手势",
            defaultValue = str("tree"),
            options = choices(
                "tree" to "会话树",
                "fork" to "分叉新会话",
                "none" to "无",
            ),
            aliases = listOf("escape", "doubleEscapeAction"),
        ),
        PiSetting(
            key = "fullscreenCopyOnSelect",
            title = "选中即复制",
            description = "全屏模式下选中文字立刻复制。关闭后选中只保持高亮，再用 Ctrl+X 复制。",
            kind = PiRowKind.Switch,
            group = G_INTERACTION,
            section = "全屏模式",
            defaultValue = bool(true),
            aliases = listOf("copy", "select"),
        ),
        PiSetting(
            key = "fullscreenScrollbar",
            title = "滚动条策略",
            description = "全屏对话的滚动条：自动（滚动时短暂出现）、常驻（固定占一列）或隐藏。常规 TUI 模式下无效。",
            kind = PiRowKind.Value,
            group = G_INTERACTION,
            section = "全屏模式",
            defaultValue = str("auto"),
            options = choices(
                "auto" to "自动",
                "always" to "常驻",
                "hidden" to "隐藏",
            ),
            aliases = listOf("scrollbar"),
        ),
        PiSetting(
            key = "fullscreenExitOutput",
            title = "退出时输出",
            description = "退出全屏时打印最终对话与恢复提示，还是恢复上一屏只打印恢复提示。常规 TUI 模式下无效。",
            kind = PiRowKind.Value,
            group = G_INTERACTION,
            section = "全屏模式",
            defaultValue = str("transcript"),
            options = choices(
                "transcript" to "打印完整对话",
                "resume-hint" to "只打印恢复提示",
            ),
            aliases = listOf("fullscreenExitOutput", "exit"),
        ),
        PiSetting(
            key = "tuiMode",
            title = "TUI 模式",
            description = "交互界面模式：常规，或实验性的全屏。--tui-mode 命令行参数会覆盖这项设置。",
            kind = PiRowKind.Value,
            group = G_INTERACTION,
            section = "全屏模式",
            defaultValue = str("regular"),
            options = choices(
                "regular" to "常规",
                "fullscreen" to "全屏",
            ),
            aliases = listOf("tuiMode", "fullscreen"),
        ),
        PiSetting(
            key = "treeFilterMode",
            title = "会话树过滤器",
            description = "打开 /tree 时默认应用的过滤器：默认、无工具、仅用户、仅标签、全部。",
            kind = PiRowKind.Value,
            group = G_INTERACTION,
            section = "会话树",
            defaultValue = str("default"),
            options = choices(
                "default" to "默认",
                "no-tools" to "无工具",
                "user-only" to "仅用户",
                "labeled-only" to "仅标签",
                "all" to "全部",
            ),
            aliases = listOf("tree", "filter"),
        ),
        PiSetting(
            key = "showHardwareCursor",
            title = "硬件光标",
            description = "TUI 为输入法定位光标时同时把它画出来，方便确认光标位置。",
            kind = PiRowKind.Switch,
            group = G_INTERACTION,
            section = "键位与手势",
            defaultValue = bool(false),
            aliases = listOf("cursor"),
        ),
        PiSetting(
            key = "externalEditor",
            title = "外部编辑器",
            description = "Ctrl+G 打开的外部编辑器命令，优先于 \$VISUAL 与 \$EDITOR。VS Code 记得带 --wait。",
            kind = PiRowKind.Text,
            group = G_INTERACTION,
            section = "编辑器",
            defaultValue = str(""),
            aliases = listOf("editor"),
        ),
        PiSetting(
            key = "app.interaction.searchScope",
            title = "对话内搜索范围",
            description = "对话页搜索是否连带工具输出一起搜索。工具输出往往很长，关掉之后命中更精准。",
            kind = PiRowKind.Value,
            group = G_INTERACTION,
            section = "对话交互",
            defaultValue = str("all"),
            options = choices(
                "all" to "含工具输出",
                "messages" to "只搜消息",
            ),
            aliases = listOf("search"),
        ),
        PiSetting(
            key = "app.interaction.gestures",
            title = "手势开关",
            description = "逐项开关列表滑动删除、右滑置顶、双击回顶、下拉刷新等手势。每个手势都有等价的按钮入口。",
            kind = PiRowKind.List,
            group = G_INTERACTION,
            section = "键位与手势",
            presets = gesturePresets,
            depth = 2,
            emptyListLabel = "全部关闭",
            aliases = listOf("gesture", "swipe"),
        ),
        PiSetting(
            key = "app.interaction.haptics",
            title = "触觉档位",
            description = "触觉反馈强度：全部事件、仅重要事件（审批、错误、完成）、关闭。",
            kind = PiRowKind.Value,
            group = G_INTERACTION,
            section = "反馈",
            defaultValue = str("all"),
            options = choices(
                "all" to "全部",
                "important" to "仅重要",
                "off" to "关闭",
            ),
            aliases = listOf("haptic", "vibrate"),
        ),
        PiSetting(
            key = "app.interaction.soundEffects",
            title = "音效",
            description = "任务完成与需要审批时各播放一个极短的柔和音。默认关闭。",
            kind = PiRowKind.Switch,
            group = G_INTERACTION,
            section = "反馈",
            defaultValue = bool(false),
            aliases = listOf("sound", "audio"),
        ),
        PiSetting(
            key = "app.interaction.motionLevel",
            title = "动效强度",
            description = "完整动效、减少动效、或跟随系统的「移除动画」设置。",
            kind = PiRowKind.Value,
            group = G_INTERACTION,
            section = "反馈",
            defaultValue = str("system"),
            options = choices(
                "full" to "完整",
                "reduced" to "减少",
                "system" to "跟随系统",
            ),
            aliases = listOf("motion", "animation"),
        ),
        PiSetting(
            key = "app.interaction.lowEndDeviceMode",
            title = "低端设备模式",
            description = "降低流式刷新率到 10fps，并在 UI 线程积压时丢弃中间增量。视觉上表现为偶尔跳一段，而不是卡住。",
            kind = PiRowKind.Switch,
            group = G_INTERACTION,
            section = "反馈",
            defaultValue = bool(false),
            aliases = listOf("performance", "streaming"),
        ),
        PiSetting(
            key = "app.interaction.keybindings",
            title = "快捷键",
            description = "键位自定义，对应 keybindings.json。终端页与外接蓝牙键盘都读它，改动需要重载。",
            kind = PiRowKind.List,
            group = G_INTERACTION,
            section = "键位与手势",
            effective = EffectiveKind.Reload,
            depth = 2,
            emptyListLabel = "全部默认",
            aliases = listOf("keybindings", "keys", "shortcuts", "reload"),
        ),

        // ------------------------------------------------------------------
        // 11 安全与信任
        // ------------------------------------------------------------------
        PiSetting(
            key = "defaultProjectTrust",
            title = "项目信任策略",
            description = "非交互模式下没有已保存的信任决定时的回退行为：询问会忽略项目资源，总是信任会加载并执行它们，从不信任始终忽略。仅全局设置支持。",
            kind = PiRowKind.Value,
            group = G_SECURITY,
            section = "信任",
            defaultValue = str("ask"),
            effective = EffectiveKind.NewSession,
            options = trustOptions,
            aliases = listOf("trust", "approve"),
        ),
        PiSetting(
            key = "app.trust.projects",
            title = "项目信任名单",
            description = "已保存信任决定的项目目录，对应 ~/.pi/agent/trust.json。信任一个项目会允许它加载 .pi/settings.json、安装项目资源包并执行项目扩展。",
            kind = PiRowKind.List,
            group = G_SECURITY,
            section = "信任",
            depth = 2,
            emptyListLabel = "无已信任项目",
            aliases = listOf("trust", "projects"),
        ),
        // `app.trust.extensions` was removed: pi has no extension allow-list to
        // mirror. Trust is per project (`projectTrusted` plus `trust.json`,
        // `settings-manager.ts:509-525`), and an extension's permission is that
        // project decision, not a second list — a row here would have been the only
        // occurrence of the key, exactly the dead-switch shape this pass removes.
        PiSetting(
            key = "app.security.dangerThreshold",
            title = "危险操作分级阈值",
            description = "审批分级：每次都问、低风险直接放行、或全部拒绝。终端里的 !command 与扩展的工具调用都受它约束。",
            kind = PiRowKind.Value,
            group = G_SECURITY,
            section = "审批",
            defaultValue = str("ask"),
            options = choices(
                "ask" to "每次都问",
                "allow-safe" to "低风险放行",
                "deny" to "全部拒绝",
            ),
            aliases = listOf("approval", "danger"),
        ),
        PiSetting(
            key = "warnings.anthropicExtraUsage",
            title = "Anthropic 额外用量警告",
            description = "当 Anthropic 订阅凭证可能产生付费额外用量时给出警告。",
            kind = PiRowKind.Switch,
            group = G_SECURITY,
            section = "审批",
            defaultValue = bool(true),
            aliases = listOf("anthropic", "warning"),
        ),
        PiSetting(
            key = "app.security.auditLog",
            title = "审计日志",
            description = "按时间列出每一次工具调用与审批决定，包含命令、路径与结果，用于事后核对 Agent 到底做了什么。",
            kind = PiRowKind.List,
            group = G_SECURITY,
            section = "审计",
            depth = 2,
            emptyListLabel = "无记录",
            aliases = listOf("audit", "log"),
        ),
        PiSetting(
            key = "app.security.emergencyStop",
            title = "紧急停止",
            description = "立刻中止当前轮次、杀掉全部后台 bash 作业并清空队列。会话与文件保持原样，不会回滚已写入的改动。",
            kind = PiRowKind.Action,
            group = G_SECURITY,
            section = "审计",
            dangerous = true,
            aliases = listOf("stop", "abort", "kill"),
        ),

        // ------------------------------------------------------------------
        // 12 设备能力 — moved out of the settings catalog
        // ------------------------------------------------------------------
        // The seven `app.device.*` rows lived here and were the *only* occurrence
        // of those keys: the enforcement and the real switches both read
        // `DeviceCapabilityStore`'s SharedPreferences
        // (`DeviceCapabilityStore.kt:61-96`, reached from the 设备能力 entry row on
        // SettingsHome), so editing a row here wrote a JSON value no code
        // consulted — two screens for one grant, only one connected. The real
        // screen is the single authority now.

        // ------------------------------------------------------------------
        // 13 运行时与诊断
        // ------------------------------------------------------------------
        PiSetting(
            key = "app.runtime.piVersion",
            title = "pi 版本",
            description = "当前内置的 pi 版本。",
            kind = PiRowKind.Text,
            group = G_RUNTIME,
            section = "版本",
            defaultValue = str("未安装"),
            readOnly = true,
            aliases = listOf("version", "update"),
        ),
        PiSetting(
            key = "app.runtime.checkUpdate",
            title = "检查更新",
            description = "检查 pi 是否有新版本，可以回滚到上一版。",
            kind = PiRowKind.Action,
            group = G_RUNTIME,
            section = "版本",
            aliases = listOf("update", "upgrade"),
        ),
        PiSetting(
            key = "app.runtime.rollback",
            title = "回滚到上一版",
            description = "把 pi 退回升级前的版本。用于新版本出问题时快速恢复。",
            kind = PiRowKind.Action,
            group = G_RUNTIME,
            section = "版本",
            aliases = listOf("rollback", "downgrade"),
        ),
        PiSetting(
            key = "app.runtime.nodeVersion",
            title = "Node 版本",
            description = "内置运行时里的 Node 版本。",
            kind = PiRowKind.Text,
            group = G_RUNTIME,
            section = "运行时",
            defaultValue = str("未安装"),
            readOnly = true,
            aliases = listOf("node", "version"),
        ),
        PiSetting(
            key = "app.runtime.rootfsUsage",
            title = "rootfs 占用",
            description = "Ubuntu rootfs 与 apt/npm 缓存的磁盘占用。低于 1 GB 会给出告警并提供清理入口。",
            kind = PiRowKind.Text,
            group = G_RUNTIME,
            section = "运行时",
            defaultValue = str("未知"),
            readOnly = true,
            aliases = listOf("storage", "disk"),
        ),
        PiSetting(
            key = "app.runtime.cleanNpmCache",
            title = "清理 npm 缓存",
            description = "删除 npm 缓存与不再被引用的包文件，释放空间。不会动已安装的扩展。",
            kind = PiRowKind.Action,
            group = G_RUNTIME,
            section = "运行时",
            aliases = listOf("cache", "clean"),
        ),
        PiSetting(
            key = "app.runtime.keepAlive",
            title = "后台保活",
            description = "用前台服务与唤醒锁让 Agent 轮次在后台继续。关闭后引擎仍会启动，但不再有前台服务保命，后台被杀时回合会中断。改动在下次启动 App 时生效。",
            kind = PiRowKind.Switch,
            group = G_RUNTIME,
            section = "后台",
            defaultValue = bool(true),
            effective = EffectiveKind.RestartApp,
            aliases = listOf("keepalive", "wakelock", "background"),
        ),
        PiSetting(
            key = "app.runtime.wakeLock",
            title = "唤醒锁状态",
            description = "当前是否持有唤醒锁，以及前台服务是否在运行。",
            kind = PiRowKind.Value,
            group = G_RUNTIME,
            section = "后台",
            defaultValue = str("unknown"),
            options = choices(
                "unknown" to "未知",
                "held" to "持有中",
                "released" to "未持有",
            ),
            aliases = listOf("wakelock"),
        ),
        PiSetting(
            key = "app.runtime.phantomKillerGuide",
            title = "幻影进程杀手指引",
            description = "国产系统会在息屏后杀掉后台进程，导致长任务中断。这里给出一键跳转 ADB 设置与可复制的命令。",
            kind = PiRowKind.Action,
            group = G_RUNTIME,
            section = "后台",
            aliases = listOf("phantom", "adb", "keepalive"),
        ),
        PiSetting(
            key = "app.runtime.logViewer",
            title = "日志查看器",
            description = "按级别与模块过滤内核日志，也用于导出诊断包。",
            kind = PiRowKind.Action,
            group = G_RUNTIME,
            section = "诊断",
            aliases = listOf("logs", "diagnostics"),
        ),
        PiSetting(
            key = "app.runtime.exportDiagnostics",
            title = "导出诊断包",
            description = "把版本、环境、日志与配置（不含凭证）打包导出，用于反馈问题。",
            kind = PiRowKind.Action,
            group = G_RUNTIME,
            section = "诊断",
            aliases = listOf("diagnostics", "export"),
        ),
        PiSetting(
            key = "app.runtime.safeMode",
            title = "安全模式启动",
            description = "下次启动禁用全部用户扩展，用于排查扩展导致的崩溃。改动需要重启 App 才生效。",
            kind = PiRowKind.Switch,
            group = G_RUNTIME,
            section = "诊断",
            defaultValue = bool(false),
            effective = EffectiveKind.RestartApp,
            aliases = listOf("safe", "extensions"),
        ),

        // ------------------------------------------------------------------
        // 14 隐私与关于
        // ------------------------------------------------------------------
        PiSetting(
            key = "enableInstallTelemetry",
            title = "安装遥测",
            description = "发送匿名的安装/更新上报，并在 OpenRouter、NVIDIA NIM、Cloudflare 请求里带上来源标识。关掉之后两者都停，但不影响版本更新检查。",
            kind = PiRowKind.Switch,
            group = G_ABOUT,
            section = "隐私",
            defaultValue = bool(true),
            aliases = listOf("telemetry", "privacy"),
        ),
        PiSetting(
            key = "enableAnalytics",
            title = "匿名分析",
            description = "选择加入的分析数据共享，默认关闭。打开时会生成一个追踪 ID。",
            kind = PiRowKind.Switch,
            group = G_ABOUT,
            section = "隐私",
            defaultValue = bool(false),
            aliases = listOf("analytics", "privacy"),
        ),
        PiSetting(
            key = "trackingId",
            title = "追踪 ID",
            description = "分析用的追踪标识，在打开匿名分析时生成。只读。",
            kind = PiRowKind.Text,
            group = G_ABOUT,
            section = "隐私",
            readOnly = true,
            aliases = listOf("trackingId", "analytics"),
        ),
        PiSetting(
            key = "lastChangelogVersion",
            title = "上次 changelog 版本",
            description = "pi 记录的上次展示更新日志的版本，用于判断是否需要再展示一次。只读。",
            kind = PiRowKind.Text,
            group = G_ABOUT,
            section = "更新",
            readOnly = true,
            aliases = listOf("changelog", "version"),
        ),
        PiSetting(
            key = "collapseChangelog",
            title = "精简更新日志",
            description = "更新后只显示精简版 changelog，完整内容用 /changelog 查看。",
            kind = PiRowKind.Switch,
            group = G_ABOUT,
            section = "更新",
            defaultValue = bool(false),
            aliases = listOf("changelog"),
        ),
        PiSetting(
            key = "app.about.changelog",
            title = "查看更新日志",
            description = "查看 pi 与 App 的完整更新历史。",
            kind = PiRowKind.Action,
            group = G_ABOUT,
            section = "更新",
            aliases = listOf("changelog"),
        ),
        PiSetting(
            key = "app.about.importFromDesktop",
            title = "从桌面导入 ~/.pi",
            description = "选一个目录或压缩包，导入 AGENTS.md、extensions、skills、prompts、themes 与 settings.json。凭证默认不导入。",
            kind = PiRowKind.Action,
            group = G_ABOUT,
            section = "配置",
            aliases = listOf("import", "migrate"),
        ),
        PiSetting(
            key = "app.about.exportConfig",
            title = "导出配置包",
            description = "把当前配置打包（不含凭证）用于分享或备份。",
            kind = PiRowKind.Action,
            group = G_ABOUT,
            section = "配置",
            aliases = listOf("export", "backup"),
        ),
        PiSetting(
            key = "app.about.rawSettings",
            title = "原始配置",
            description = "直接编辑 settings.json 与 keybindings.json，等宽字体、JSON 校验、错误定位与保存前 diff 预览。",
            kind = PiRowKind.Action,
            group = G_ABOUT,
            section = "配置",
            aliases = listOf("json", "raw", "settings"),
        ),
        PiSetting(
            key = "app.about.reset",
            title = "重置配置",
            description = "分项重置、全部重置，或只重置外观。重置前会显示将要改动的键。",
            kind = PiRowKind.Action,
            group = G_ABOUT,
            section = "配置",
            dangerous = true,
            aliases = listOf("reset", "restore"),
        ),
        PiSetting(
            key = "app.about.licenses",
            title = "开源许可",
            description = "本 App 与内置运行时使用的开源组件许可。",
            kind = PiRowKind.Action,
            group = G_ABOUT,
            section = "关于",
            aliases = listOf("license", "about"),
        ),
    )

    /** Registry lookup by exact dotted key. */
    val byKey: Map<String, PiSetting> = settings.associateBy { it.key }

    /** The 14 groups of spec §6.4, in order, each with a live summary. */
    val groups: List<PiSettingsGroup> = listOf(
        PiSettingsGroup(G_MODEL, "模型与推理", Icons.Filled.Psychology) { store ->
            val model = PiSettingsCatalog.summaryText(store, "defaultModel")
            val row = byKey["defaultModel"]
            val configured = row != null && row.isExplicit(store)
            val level = PiSettingsCatalog.summaryText(store, "defaultThinkingLevel")
            if (configured) "默认 $model · ◐ $level" else "未配置模型，启动时再选"
        },
        PiSettingsGroup(G_MESSAGES, "消息与队列", Icons.Filled.CompareArrows) { store ->
            "穿插：${summaryText(store, "steeringMode")} · 后续：${summaryText(store, "followUpMode")}"
        },
        PiSettingsGroup(G_COMPACTION, "上下文与压缩", Icons.Filled.Compress) { store ->
            val enabled = summaryText(store, "compaction.enabled")
            val reserve = summaryText(store, "compaction.reserveTokens")
            val keep = summaryText(store, "compaction.keepRecentTokens")
            "$enabled · $reserve / $keep"
        },
        PiSettingsGroup(G_RETRY, "重试与网络", Icons.Filled.Refresh) { store ->
            val retries = summaryText(store, "retry.maxRetries")
            "${summaryText(store, "retry.enabled")} · $retries 次"
        },
        PiSettingsGroup(G_TOOLS, "工具", Icons.Filled.Build) { store ->
            val row = byKey["defaultTools"]
            val count = row?.countIn(store) ?: 0
            if (count == 0) "默认 read·bash·edit·write" else "$count 个已启用"
        },
        PiSettingsGroup(G_SESSIONS, "会话", Icons.Filled.Folder) { store ->
            val row = byKey["sessionDir"]
            if (row != null && row.isExplicit(store)) {
                row.display(row.current(store))
            } else {
                "默认 ~/.pi/agent/sessions"
            }
        },
        PiSettingsGroup(G_RESOURCES, "扩展与资源", Icons.Filled.Extension) { store ->
            val extensions = byKey["extensions"]?.countIn(store) ?: 0
            val packages = byKey["packages"]?.countIn(store) ?: 0
            "$extensions 个扩展 · $packages 个资源包"
        },
        PiSettingsGroup(G_APPEARANCE, "外观", Icons.Filled.ColorLens) { store ->
            val themeValue = summaryText(store, "theme")
            "主题 $themeValue"
        },
        PiSettingsGroup(G_TERMINAL, "终端与 Shell", Icons.Filled.Terminal) { store ->
            val row = byKey["shellPath"]
            val shell = if (row != null && row.isExplicit(store)) {
                row.display(row.current(store))
            } else {
                "默认 /bin/bash"
            }
            val size = PiSettingsCatalog.summaryText(store, "app.terminal.fontSize")
            "$shell · 等宽 $size"
        },
        PiSettingsGroup(G_INTERACTION, "交互", Icons.Filled.TouchApp) { store ->
            "双击返回：${summaryText(store, "doubleEscapeAction")}"
        },
        PiSettingsGroup(G_SECURITY, "安全与信任", Icons.Filled.Security) { store ->
            "项目信任：${summaryText(store, "defaultProjectTrust")}"
        },
        PiSettingsGroup(G_RUNTIME, "运行时与诊断", Icons.Filled.Memory) { store ->
            val version = summaryText(store, "app.runtime.piVersion")
            val keepAlive = summaryText(store, "app.runtime.keepAlive")
            "pi $version · 保活：$keepAlive"
        },
        PiSettingsGroup(G_ABOUT, "隐私与关于", Icons.Filled.Info) { store ->
            "遥测：${summaryText(store, "enableInstallTelemetry")} · 分析：${summaryText(store, "enableAnalytics")}"
        },
    )

    fun group(id: String): PiSettingsGroup? = groups.firstOrNull { it.id == id }

    fun groupTitle(id: String): String = group(id)?.title ?: id

    /** Rows of one group, in registry order. */
    fun settingsIn(groupId: String): List<PiSetting> = settings.filter { it.group == groupId }

    /** Distinct section names of a group, in the order they first appear. */
    fun sectionsIn(groupId: String): List<String> =
        settingsIn(groupId).map { it.section }.distinct()

    /**
     * Fuzzy search across title, description and the raw dotted key, so that
     * `reserveTokens` finds `compaction.reserveTokens` (spec §6.3). Slash
     * commands map onto settings too: `/compact` lands on 自动压缩.
     */
    fun search(query: String, limit: Int = 80): List<PiSearchHit> {
        val needle = query.trim().lowercase().removePrefix("/")
        if (needle.isEmpty()) return emptyList()
        return settings
            .mapNotNull { setting ->
                val score = scoreOf(setting, needle) ?: return@mapNotNull null
                PiSearchHit(setting, score, groupTitle(setting.group), setting.section)
            }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun scoreOf(setting: PiSetting, needle: String): Int? {
        val key = setting.key.lowercase()
        val title = setting.title.lowercase()
        val description = setting.description.lowercase()
        var best: Int? = null

        fun keep(value: Int) {
            val current = best
            if (current == null || value > current) best = value
        }

        if (key == needle) keep(1000)
        if (key.substringAfterLast('.') == needle) keep(950)
        for (alias in setting.aliases) {
            val normalized = alias.lowercase().removePrefix("/")
            if (normalized == needle) keep(900)
        }
        if (key.contains(needle)) keep(800 - key.indexOf(needle).coerceAtMost(200))
        if (title.contains(needle)) keep(700 - title.indexOf(needle).coerceAtMost(200))
        for (alias in setting.aliases) {
            val normalized = alias.lowercase().removePrefix("/")
            if (normalized.contains(needle)) keep(600)
        }
        if (description.contains(needle)) keep(400)
        if (best == null && isSubsequence(title, needle)) best = 200
        if (best == null && isSubsequence(key, needle)) best = 150
        return best
    }

    private fun isSubsequence(haystack: String, needle: String): Boolean {
        var index = 0
        for (character in haystack) {
            if (index < needle.length && character == needle[index]) index++
            if (index == needle.length) return true
        }
        return index == needle.length
    }

    /** Human summary of one key, falling back to the row's default. */
    fun summaryText(store: PiSettingsStore, key: String): String {
        val setting = byKey[key] ?: return "未设置"
        return setting.display(setting.current(store))
    }
}
