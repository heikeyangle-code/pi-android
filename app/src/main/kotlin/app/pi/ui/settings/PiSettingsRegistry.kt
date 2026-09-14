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
import app.pi.ui.components.EffectiveKind
import app.pi.ui.theme.PiThinkingLevel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The settings registry: the rows this app shows, addressed by the key pi reads
 * from `~/.pi/agent/settings.json` or `.pi/settings.json`.
 *
 * **Not every key pi knows is here, and that is the point.** pi's `Settings`
 * interface is 51 top-level keys (72 leaves when the eight nested groups are
 * expanded). 27 of those rows are read *only* by its interactive TUI, or by nobody
 * at all, so on this platform they are switches that cannot do anything. They are
 * deliberately absent — `docs/settings-review.md` §1.2 lists each one with the pi
 * location that reads it and the reason it is not here. The rule for a new row is:
 * name the reader first. If the reader is pi at startup or per session, say so
 * with [EffectiveKind]; if it is pi and nothing in this app, add it to
 * [PiSettingsCatalog.piOwnedKeys]; if there is no reader, there is no row.
 *
 * Field-level truth is `packages/coding-agent/docs/settings.md` and the
 * `Settings` interface in `core/settings-manager.ts` of pi itself — titles and
 * descriptions here are translations of that text, not inventions, because the
 * spec requires each row to explain itself the way pi's own docs do. User-visible
 * copy never carries a file path, a class name or a section marker.
 *
 * Two shapes are deliberately preserved rather than normalised:
 *
 *  - `theme` may hold the literal string `"lightTheme/darkTheme"` for automatic
 *    light/dark switching. It is one string, never two fields.
 *  - Array settings accept glob syntax plus `!pattern` (exclude), `+path`
 *    (force include) and `-path` (force exclude). The editors keep those
 *    markers verbatim and never rewrite an entry as a plain path.
 *
 * `tools/run-app-pure-checks.sh`'s `settings-audit` enforces the reader rule
 * mechanically: a key with neither an app-side reader nor a `piOwnedKeys` entry
 * fails the build.
 */

/** The six row kinds of docs/pi-android-ui-spec.md §6.2. */
enum class PiRowKind { Switch, Value, Number, Text, List, Action }

/** Whether a list-valued setting is a JSON array or a JSON object map. */
enum class PiValueContainer { Array, Object }

/**
 * One choice of an enumerated (Value) setting, with its wire form.
 *
 * Every remaining Value row stores a JSON string, which is what pi's schema wants
 * for them. pi also has enumerated keys whose values are booleans or numbers
 * (`outputPad` is `0 | 1`, `terminal.hyperlinks` is `boolean | "auto"`); those rows
 * are not in the catalog because nothing on this platform reads them, so there is
 * no scalar-wire case left to carry. Re-adding one needs a flag here first, or the
 * writer would store `"1"` where pi's schema requires `1`.
 */
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
     * A choice is stored as the string pi documents for it, and a value the user
     * typed into a custom field is a string too — which is what pi expects for an
     * open-ended key such as `defaultModel`.
     */
    fun wireElement(wire: String): JsonElement = JsonPrimitive(wire)
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


private val builtinTools = listOf("read", "bash", "powershell", "edit", "write", "grep", "find", "ls")


private val termKeyBarPresets = listOf("esc", "tab", "ctrl", "up", "down", "left", "right", "pipe", "tilde", "slash", "dash")

object PiSettingsCatalog {

    /** Every registered key, in spec §6.4 order. */
    val settings: List<PiSetting> = listOf(

        // ------------------------------------------------------------------
        // 1 模型与推理
        // ------------------------------------------------------------------
        // 这个分组的入口是 App 侧的一页，不是一个 pi 设置：设置 → 模型 把"这台设备上配了
        // 哪些厂商、有哪些模型、哪些已经生效"一次说清（`PiModelInventory`，实现见
        // `PiModelsScreen`）。它是分组的第一行是故意的——在它之前，这个问题在设置页里没有
        // 任何地方能回答，而"导入过的模型看不到"正是用户报的那个问题。
        PiSetting(
            key = "app.models.inventory",
            title = "已导入的模型",
            description = "这台设备上配了哪些厂商、每个厂商下有哪些模型、哪些已经能用、哪些还要重启引擎。",
            kind = PiRowKind.Action,
            group = G_MODEL,
            section = "本机模型",
            aliases = listOf("model", "models", "provider", "模型", "厂商"),
        ),
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
            key = "enabledModels",
            title = "循环模型",
            description = "循环切换时使用的模型，支持通配符。pi 在引擎启动时解析一次，改完要重启引擎才生效。",
            kind = PiRowKind.List,
            group = G_MODEL,
            section = "循环模型",
            // 这一行原先是 `NewSession`，那是错的。pi 把它解析成 `--models` 的作用域：
            // `main.ts:788-791` 在**进程启动**时调 `resolveModelScope(modelPatterns, …)`，
            // 而调用它的 `buildSessionOptions` 在 `main.ts` 里只有 `:797` 一处，也就是说
            // 一个 `pi --mode rpc` 进程只解析一次。所以"新开一个会话"不会让它生效，只有新
            // 进程会（`agent-session.ts:1717` 的循环用的就是这份 `_scopedModels`）。
            effective = EffectiveKind.RestartEngine,
            presets = listOf("claude-*", "gpt-*", "gemini-*", "deepseek-*", "qwen-*"),
            globAware = true,
            depth = 2,
            emptyListLabel = "全部模型",
            aliases = listOf("cycle", "model"),
        ),
        PiSetting(
            key = "app.credentials.apiKey",
            title = "API Key",
            // The old text named the credential file and settings.json on screen. The
            // rule for this pass is that the UI shows what a user can do, not where
            // the bytes go, so it now describes the flow instead.
            description = "填写厂商 API Key。",
            kind = PiRowKind.Action,
            group = G_MODEL,
            section = "凭证",
            aliases = listOf("auth", "login", "key"),
        ),
        PiSetting(
            key = "app.credentials.oauth",
            title = "订阅登录（本应用暂无入口）",
            // 这行原来写的是「点执行会切到 工作区 → 终端，输入 pi 后运行 /login」。终端不是
            // 可用面，那句指引指向一个做不到的动作 —— 和这个仓库一直在修的那类缺陷同形
            // （界面上写着一个不存在的动作）。改成如实说明：本应用只支持填 API Key。
            // 为什么不能在应用里做：pi 的 OAuth 流程整个长在它自己的交互式界面里
            // （`interactive-mode.ts:5485` `handleLoginCommand`），RPC 命令全集
            // （`modes/rpc/rpc-types.ts:20-74`）里没有任何登录命令。
            description = "Anthropic Claude Pro/Max、OpenAI Codex、GitHub Copilot、OpenRouter、" +
                "Kimi Code、xAI、Radius 支持用订阅账号登录，授权在浏览器里完成。" +
                "本应用目前只支持填厂商 API Key，订阅登录还没有入口。",
            kind = PiRowKind.Action,
            group = G_MODEL,
            section = "凭证",
            aliases = listOf("login", "auth", "oauth"),
        ),
        // The half of pi's `/llama` this row can actually do is the endpoint:
        // `models.json` plus a placeholder credential, which is what the form
        // behind this row writes. Loading/unloading models and downloading GGUF
        // is the other half, and the extension refuses to run it outside the TUI
        // (`extensions/llama/index.ts:186-189` returns unless `ctx.mode === "tui"`;
        // `ctx.ui.custom()` is a documented no-op in RPC mode). The description
        // states what this form does and stops there: "there is more in the
        // terminal" is said once, on the settings home's 终端 row
        // (`SettingsHome.kt` 的 `PiTerminalEntryRow`)，不在每个与 TUI 有关的行上重复。
        // 那条注释以前写的是 `WorkbenchScreen.kt` —— 那个屏已经随终端一起退役了。
        PiSetting(
            key = "app.localModels.manage",
            title = "本地模型（llama.cpp）",
            description = "配置 pi 使用的 llama.cpp router 端点（默认 http://127.0.0.1:8080）。",
            kind = PiRowKind.Action,
            group = G_MODEL,
            section = "凭证",
            aliases = listOf("llama", "local"),
        ),

        // ------------------------------------------------------------------
        // 2 消息与网络
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
            key = "transport",
            title = "传输方式",
            description = "支持多种传输的厂商优先使用哪一种。自动会挑当前最合适的一种。",
            kind = PiRowKind.Value,
            group = G_MESSAGES,
            section = "传输",
            defaultValue = str("auto"),
            effective = EffectiveKind.NewSession,
            options = transportOptions,
            aliases = listOf("websocket", "sse"),
        ),
        PiSetting(
            key = "httpIdleTimeoutMs",
            title = "HTTP 空闲超时",
            description = "HTTP 头/体的空闲超时（毫秒），有显式流空闲超时的厂商也用它。设为 0 表示不超时。",
            kind = PiRowKind.Number,
            group = G_MESSAGES,
            section = "网络",
            defaultValue = num(300000),
            effective = EffectiveKind.RestartEngine,
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
            effective = EffectiveKind.NewSession,
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
            effective = EffectiveKind.RestartEngine,
            aliases = listOf("proxy"),
        ),

        // ------------------------------------------------------------------
        // 3 上下文与压缩
        // ------------------------------------------------------------------
        PiSetting(
            key = "compaction.enabled",
            title = "自动压缩",
            description = "开启后 pi 会在上下文接近上限时自动压缩历史，避免请求溢出。改动立刻作用于当前会话。",
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
            effective = EffectiveKind.NewSession,
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
            effective = EffectiveKind.NewSession,
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
            effective = EffectiveKind.NewSession,
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
            effective = EffectiveKind.NewSession,
            defaultValue = num(16384),
            min = 0,
            max = 1000000,
            step = 1024,
            unit = "token",
            aliases = listOf("branch", "summary"),
        ),
        // Implemented: `PiRoot` calls `session.compact()`, i.e. the RPC command
        // `compact` (`rpc-types.ts:44`). The command also takes
        // `customInstructions`, but that needs a text field; the chat palette's
        // `/compact <指令>` already has one (`ChatScreen.kt:341`), so the
        // description names it instead of promising a field this row has not got.
        PiSetting(
            key = "app.compaction.runNow",
            title = "立即压缩",
            description = "对当前会话手动触发一次压缩。想附带一条自定义指令告诉模型压缩时关注什么，" +
                "请在 对话 里输入 /compact <指令>；本行只做不带指令的那种压缩。",
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
            effective = EffectiveKind.NewSession,
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
            effective = EffectiveKind.NewSession,
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
            effective = EffectiveKind.NewSession,
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
            effective = EffectiveKind.NewSession,
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
            effective = EffectiveKind.NewSession,
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
            effective = EffectiveKind.NewSession,
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
            description = "启动时启用的内建工具。建议额外开启 grep、find、ls，否则模型只能靠 bash 跑 rg 和 fd，手机上更慢。",
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
        // `sessionDir` is deliberately absent even though pi has the key
        // (`settings-manager.ts:150`, read at `main.ts:675`). This app pins the
        // session root on the command line *and* in the environment
        // (`PiEngineHost.kt:275` `--session-dir`, `:307`
        // `PI_CODING_AGENT_SESSION_DIR`), and pi's precedence is
        // `--session-dir` > `PI_CODING_AGENT_SESSION_DIR` > the setting
        // (`main.ts:670-676`, `cli/args.ts:431`), so a value written here could
        // never be read — the row was a promise the process could not keep. Making
        // it real means dropping both launch inputs and teaching
        // `PiSessionStore` to list the per-cwd directories as well; see
        // `docs/settings-review.md` §7.5.
        //
        // `app.sessions.import` used to sit here as a signpost to pi's own TUI
        // (`/import` is TUI-only: `interactive-mode.ts:6107-6122`, and the
        // `RpcCommand` union has no import command, `rpc-types.ts:20-74`). It was
        // removed with the same rule that removed 27 TUI-only keys: a row that can
        // only say "go somewhere else" is not a setting. The one sentence that has
        // to exist for discovery lives on the settings home's 终端 row
        // (`SettingsHome.kt`), not once per row here.
        PiSetting(
            key = "app.sessions.resumeLast",
            title = "启动续接最近会话",
            description = "启动 App 时自动切到最近一次会话。",
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
            description = "本地扩展文件或目录的路径。相对路径分别以全局设置目录与项目目录为基准。",
            kind = PiRowKind.List,
            group = G_RESOURCES,
            section = "本地资源",
            defaultValue = list(),
            effective = EffectiveKind.RestartEngine,
            globAware = true,
            depth = 2,
            aliases = listOf("extensions", "reload"),
        ),
        // Read-only on purpose, and an entry point rather than an editor. The
        // array is written by the package manager: `pi install` records a source
        // in `settings.packages` and also resolves, downloads and records what it
        // installed (`core/package-manager.ts`; the app's own screen says the list
        // it shows "is what `pi install` wrote into settings.json's packages",
        // `packages/PiPackagesScreen.kt:309`). Letting a user hand-edit the array
        // here would produce exactly the state that comment warns about — a spec
        // that looks installed but was never fetched — so the row jumps to the
        // package screen (`PiSettingsStack.hostActions`, entry key `packages`)
        // instead of opening `PiListEditorSheet`.
        PiSetting(
            key = "packages",
            title = "资源包",
            description = "已安装的资源包。手改会写出「看起来装了、其实没生效」的条目；" +
                "点这一行打开 资源包管理 页去安装、更新或移除。",
            kind = PiRowKind.List,
            group = G_RESOURCES,
            section = "资源包",
            defaultValue = list(),
            effective = EffectiveKind.RestartEngine,
            readOnly = true,
            globAware = true,
            depth = 3,
            aliases = listOf("install", "packages", "npm", "git"),
        ),
        PiSetting(
            key = "skills",
            title = "技能",
            description = "本地技能文件或目录的路径。每个技能是一个带名称与描述的文件。",
            kind = PiRowKind.List,
            group = G_RESOURCES,
            section = "本地资源",
            defaultValue = list(),
            effective = EffectiveKind.RestartEngine,
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
            effective = EffectiveKind.RestartEngine,
            globAware = true,
            depth = 2,
            aliases = listOf("prompts", "templates", "reload"),
        ),
        PiSetting(
            key = "themes",
            title = "主题",
            description = "本地主题文件或目录的路径。",
            kind = PiRowKind.List,
            group = G_RESOURCES,
            section = "本地资源",
            defaultValue = list(),
            effective = EffectiveKind.RestartEngine,
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
            // `Immediate`, not `Reload`: pi reads this setting only in its own TUI
            // (`interactive-mode.ts:716`, `:4570`) and its RPC `get_commands` lists
            // skill commands unconditionally (`rpc-mode.ts:702-708`), so nothing
            // inside pi would ever change. The command panel this row is about is
            // *this app's*, built by `piCommandPalette`, and it filters on the write
            // (`PiSessionViewModel.onSettingWritten` → `refreshCommands`) — so the
            // row takes effect at once, which is also what pi's TUI does.
            effective = EffectiveKind.Immediate,
            aliases = listOf("skills", "commands"),
        ),
        // `app.contextFiles` used to sit here and is **deleted on purpose**. Its
        // description promised "全局与项目级的上下文文件。它们会被追加进系统提示" and
        // nothing in the tree read the key — a search for `contextFiles` across
        // `app/src/main` and `rpc/src/main` found the registry row and nothing else
        // — so it was an editor for a file list with no writer behind it, the same
        // class of defect §I7 removed elsewhere. pi has no counterpart either: its
        // context files are a *convention* it discovers by fixed name
        // (`resource-loader.ts:72`: `AGENTS.override.md`, `AGENTS.md`, `AGENTS.MD`,
        // `CLAUDE.md`, `CLAUDE.MD`) rather than a setting listing paths, so the
        // presets this row offered were a view of pi's convention, not a control over
        // it. `pi 无对应物`, and the honest form of "the app decides not to do this"
        // is not to offer the switch.

        // ------------------------------------------------------------------
        // 8 外观
        // ------------------------------------------------------------------
        PiSetting(
            key = "theme",
            title = "主题",
            description = "主题名：dark、light 或自定义主题。自动模式可分别指定浅色与深色主题名。",
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
            description = "思考块默认收起成一行「思考 12s」，点击展开。这是本应用的显示偏好，不改 pi 对思考块的隐藏设置。",
            kind = PiRowKind.Switch,
            group = G_APPEARANCE,
            section = "排版",
            defaultValue = bool(true),
            aliases = listOf("thinking", "collapse"),
        ),
        PiSetting(
            key = "images.autoResize",
            title = "图片自动缩放",
            description = "把图片缩到最大 2000x2000，对 @file 附件、read 工具与工具返回的图片都生效。",
            kind = PiRowKind.Switch,
            group = G_APPEARANCE,
            section = "图片",
            effective = EffectiveKind.NewSession,
            defaultValue = bool(true),
            aliases = listOf("images", "resize"),
        ),
        PiSetting(
            key = "images.blockImages",
            title = "屏蔽图片",
            description = "阻止所有图片发送给模型。这是给模型设的闸门：图片仍然可以附加，只是不会随请求发出去。",
            kind = PiRowKind.Switch,
            group = G_APPEARANCE,
            section = "图片",
            effective = EffectiveKind.NewSession,
            defaultValue = bool(false),
            aliases = listOf("images", "block"),
        ),

        // ------------------------------------------------------------------
        // 9 终端与 Shell
        // ------------------------------------------------------------------
        PiSetting(
            key = "shellPath",
            title = "Shell 路径",
            description = "pi 执行自己的命令时使用的 shell，支持开头的 ~ 展开。只影响 pi 执行的命令，" +
                "不影响工作台终端（那里始终是一个普通的交互式 bash）；改动需要新会话才生效。",
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
            description = "pi 执行的每条命令前面都会拼上的前缀，例如 shopt -s expand_aliases 用来打开别名。" +
                "只影响 pi 执行的命令，不影响工作台终端。",
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
            description = "工作台终端页的起始字号，之后可以用双指缩放临时调整。重新进入终端页后生效。" +
                "等宽字体只在这里使用，不影响对话流的正文。",
            kind = PiRowKind.Number,
            group = G_TERMINAL,
            section = "终端显示",
            defaultValue = num(13),
            effective = EffectiveKind.Reload,
            min = 10,
            max = 20,
            step = 1,
            unit = "sp",
            aliases = listOf("font", "terminal"),
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
        // 10 安全与信任
        // ------------------------------------------------------------------
        PiSetting(
            key = "defaultProjectTrust",
            title = "项目信任策略",
            description = "非交互模式下没有已保存的信任决定时的回退行为：询问会忽略项目资源，总是信任会加载并执行它们，从不信任始终忽略。仅全局设置支持。",
            kind = PiRowKind.Value,
            group = G_SECURITY,
            section = "信任",
            defaultValue = str("ask"),
            effective = EffectiveKind.RestartEngine,
            options = trustOptions,
            aliases = listOf("trust", "approve"),
        ),
        // Implemented, and each clause of the description is one RPC command:
        // "中止当前轮次" + "清空队列" are `clear_queue` and `abort`
        // (`rpc-mode.ts:428`, `:433`, reached through
        // `PiEngineSession.stopAndDrainQueue` `:480-486`), and "杀掉全部后台 bash
        // 作业" is `abort_bash` (`rpc-mode.ts:586`), which aborts every running
        // bash command rather than one (`agent-session.ts:3073-3077`).
        PiSetting(
            key = "app.security.emergencyStop",
            title = "紧急停止",
            description = "立刻中止当前轮次、停掉正在运行的 bash 命令并清空队列。会话与文件保持原样，不会回滚已写入的改动。",
            kind = PiRowKind.Action,
            group = G_SECURITY,
            section = "动作",
            dangerous = true,
            aliases = listOf("stop", "abort", "kill"),
        ),

        // ------------------------------------------------------------------
        // 11 设备能力 — moved out of the settings catalog
        // ------------------------------------------------------------------
        // The seven `app.device.*` rows lived here and were the *only* occurrence
        // of those keys: the enforcement and the real switches both read
        // `DeviceCapabilityStore`'s SharedPreferences
        // (`DeviceCapabilityStore.kt:61-96`, reached from the 设备能力 entry row on
        // SettingsHome), so editing a row here wrote a JSON value no code
        // consulted — two screens for one grant, only one connected. The real
        // screen is the single authority now.

        // ------------------------------------------------------------------
        // 12 运行时与诊断
        // ------------------------------------------------------------------
        PiSetting(
            key = "app.runtime.piVersion",
            title = "pi 版本",
            description = "当前内置的 pi 版本。运行时尚未解包或读不到时会写明原因。",
            kind = PiRowKind.Text,
            group = G_RUNTIME,
            section = "版本",
            defaultValue = str("未读取"),
            readOnly = true,
            aliases = listOf("version", "update"),
        ),
        // `app.runtime.checkUpdate` and `app.runtime.rollback` used to sit here and
        // were removed on purpose. pi's own check is a network call the app turns
        // off deliberately (`PiEngineHost.kt:305-306` sets `PI_SKIP_VERSION_CHECK`,
        // because a phone app should not make it behind the user's back), and its
        // replacement, `pi update --self`, is an npm self-update
        // (`package-manager-cli.ts:1033-1068`) that this app's engine cannot use:
        // the engine is an APK asset extracted by revision
        // (`RuntimeProvisioner.extractEngine`, `:505-528`) and re-extracted by
        // `wipe()` (`:122`) whenever that revision changes, so an in-guest update
        // is either overwritten or diverges from the shipped version. pi has no
        // rollback at all (no such subcommand in `cli.ts` /
        // `package-manager-cli.ts`). The read-only 「pi 版本」 row above is what
        // tells the user which version they are on.
        PiSetting(
            key = "app.runtime.nodeVersion",
            title = "Node 版本",
            description = "内置运行时里的 Node 版本。运行时尚未解包或读不到时会写明原因。",
            kind = PiRowKind.Text,
            group = G_RUNTIME,
            section = "运行时",
            defaultValue = str("未读取"),
            readOnly = true,
            aliases = listOf("node", "version"),
        ),
        PiSetting(
            key = "app.runtime.rootfsUsage",
            title = "运行时占用",
            description = "Linux 运行时与包缓存的磁盘占用。运行时尚未解包或读不到时会写明原因。",
            kind = PiRowKind.Text,
            group = G_RUNTIME,
            section = "运行时",
            defaultValue = str("未读取"),
            readOnly = true,
            aliases = listOf("storage", "disk"),
        ),
        PiSetting(
            key = "app.runtime.engineStartup",
            title = "引擎启动耗时",
            description = "上一次引擎从启动进程到能开始响应命令的用时。刚打开 App 就发消息时，" +
                "这段时间就是消息在等待的时间。",
            kind = PiRowKind.Text,
            group = G_RUNTIME,
            section = "运行时",
            defaultValue = str("未读取"),
            readOnly = true,
            aliases = listOf("startup", "engine"),
        ),

        // ------------------------------------------------------------------
        // 进程开关（pi 的启动参数 / 环境变量）
        //
        // 这五行都是 pi 的**进程级**配置，pi 自己的 settings 文档里没有对应的键
        // （`core/settings-manager.ts:106-158` 的 `Settings` 里没有 offline /
        // systemPrompt / appendSystemPrompt / cacheRetention / noContextFiles），
        // 所以键是我们自己的（`app.` 前缀），值由 App 组进 `PiLaunchOptions`
        // （`rpc/PiLaunchOptions.kt`）在启动 `pi --mode rpc` 时传下去 —— 这与 pi
        // 的 CLI 参数一一对应，表的权威副本是 `rpc/PiPreSpawnConfig.kt`：
        //   `--offline`（`cli/args.ts:223`，等价 `PI_OFFLINE=1`，`:433`）
        //   `--system-prompt` / `--append-system-prompt`（`:110` / `:112`，后者可重复）
        //   `PI_CACHE_RETENTION=long`（`packages/ai/src/api/anthropic-messages.ts:57`）
        //   `--no-context-files`（`:194`）
        // 因为进程启动后这些值不再变（值是 App 读 settings 后写进 argv/env 的，
        // 新会话不会重读 argv），`effective` 必须是 `RestartEngine`（界面标签
        // 「需重启引擎」），每一行的说明里写明需要重启引擎。
        // ------------------------------------------------------------------
        PiSetting(
            key = "app.runtime.offline",
            title = "离线模式",
            description = "关闭 pi 启动时的联网动作（更新检查、包更新、安装遥测）。" +
                "开启后模型调用照常联网。改动在重启引擎后生效。",
            kind = PiRowKind.Switch,
            group = G_RUNTIME,
            section = "进程",
            defaultValue = bool(false),
            effective = EffectiveKind.RestartEngine,
            aliases = listOf("offline", "network"),
        ),
        PiSetting(
            key = "app.runtime.systemPrompt",
            title = "自定义系统提示",
            description = "整份替换 pi 自带的系统提示词；留空用 pi 自己的。" +
                "只想补充几条偏好、不想丢掉 pi 原有的提示词时，用下面的「追加系统提示」。" +
                "改动在重启引擎后生效。",
            kind = PiRowKind.Text,
            group = G_RUNTIME,
            section = "进程",
            defaultValue = str(""),
            effective = EffectiveKind.RestartEngine,
            aliases = listOf("system", "prompt"),
        ),
        PiSetting(
            key = "app.runtime.appendSystemPrompt",
            title = "追加系统提示",
            description = "在 pi 自己拼好的系统提示词末尾追加一段文字，不替换它" +
                "（整份替换请用上面的「自定义系统提示」，两者可以同时设置）。" +
                "适合写长期偏好。改动在重启引擎后生效。",
            kind = PiRowKind.Text,
            group = G_RUNTIME,
            section = "进程",
            defaultValue = str(""),
            // Text 的 depth > 1 让编辑器用多行输入框：追加内容通常是几行而不
            // 是一条命令（`PiSettingsEditors.kt` 的 `singleLine = setting.depth <= 1`）。
            depth = 2,
            effective = EffectiveKind.RestartEngine,
            aliases = listOf("system", "prompt", "append"),
        ),
        PiSetting(
            key = "app.runtime.cacheRetention",
            title = "缓存保留策略",
            description = "长保留向厂商申请更长的提示缓存（支持的厂商才有差别）。" +
                "改动在重启引擎后生效。",
            kind = PiRowKind.Value,
            group = G_RUNTIME,
            section = "进程",
            defaultValue = str("short"),
            effective = EffectiveKind.RestartEngine,
            options = choices(
                "short" to "默认",
                "long" to "长保留",
            ),
            aliases = listOf("cache", "retention"),
        ),
        PiSetting(
            key = "app.runtime.noContextFiles",
            title = "不加载上下文文件",
            description = "开启后 pi 不再自动读取工作区里的上下文文件，模型只看到系统提示与你自己写的内容。" +
                "适合某个目录里的说明不该影响模型时。改动在重启引擎后生效。",
            kind = PiRowKind.Switch,
            group = G_RUNTIME,
            section = "进程",
            defaultValue = bool(false),
            effective = EffectiveKind.RestartEngine,
            aliases = listOf("context", "agents"),
        ),
        PiSetting(
            key = "app.runtime.restartEngine",
            title = "重启引擎",
            description = "让上面的进程开关生效。重启会终止正在进行的回合" +
                "（模型调用、工具调用、正在跑的命令都不会恢复），已写入磁盘的会话不会丢失。",
            kind = PiRowKind.Action,
            group = G_RUNTIME,
            section = "进程",
            dangerous = true,
            aliases = listOf("restart", "reload"),
        ),
        // `app.runtime.cleanNpmCache` was removed rather than wired: pi has no
        // cache-clearing command (nothing in `cli.ts` or `package-manager-cli.ts`
        // touches an npm store), and the app has no console that could call
        // `npm cache clean` in the guest — the only surfaces that run guest
        // commands are the engine's argv (`PiEngineHost`) and the PTY terminal.
        // A row whose button cleans nothing is exactly the defect §I2 records.
        // The 「rootfs 占用」 row above still reports how much space is in use.
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
            // The policy changed with this row's wording: the lock is held while the
            // engine starts or a turn runs, and released when idle (`PiEngineLifecyclePolicy`),
            // so "未持有" while the app sits idle is normal — the old text ("最长 6
            // 小时") described the old always-held lock and would now read as a fault.
            description = "持有中表示引擎正在启动或正有回合在跑，此时息屏也能继续；空闲时自动释放以省电。",
            kind = PiRowKind.Text,
            group = G_RUNTIME,
            section = "后台",
            defaultValue = str("未读取"),
            readOnly = true,
            aliases = listOf("wakelock"),
        ),
        // 为线上事故加的入口：界面只显示 "rpc: engine exited with code 1"，而用户没有
        // ADB、看不到 logcat，`PiEngineSession` 捕获的 stderr 又只留在已经死掉的对象里。
        // 这一行把「App 此刻还能读到的一切」汇总成一份纯文本，交给 Download 或系统分享，
        // 而不是塞进对话上下文（`DiagnosticsReport` 的 KDoc 写了为什么）。它是 Action 行
        // 而不是开关：没有值可写，也没有 pi 侧的对应键。引擎已经退出时同样可用 —— 报告里
        // 的引擎部分来自退出瞬间的抓取，不依赖引擎活着。
        PiSetting(
            key = "app.runtime.diagnostics",
            title = "导出诊断报告",
            description = "把引擎最后一次退出的退出码与已捕获的 stderr、运行时与载荷状态、关键路径、" +
                "最近的失败和设备信息汇总成一份纯文本，保存到 Download 或直接分享。" +
                "引擎已经退出时同样可用。报告不会进入对话上下文，敏感值已做脱敏。",
            kind = PiRowKind.Action,
            group = G_RUNTIME,
            section = "诊断",
            aliases = listOf("diagnostics", "report", "log", "stderr", "crash", "日志", "崩溃"),
        ),

        // ------------------------------------------------------------------
        // 13 隐私与关于
        // ------------------------------------------------------------------
        PiSetting(
            key = "enableInstallTelemetry",
            title = "安装遥测",
            description = "发送匿名的安装/更新上报，并在 OpenRouter、NVIDIA NIM、Cloudflare 请求里带上来源标识。关掉之后两者都停，但不影响版本更新检查。",
            kind = PiRowKind.Switch,
            group = G_ABOUT,
            section = "隐私",
            effective = EffectiveKind.NewSession,
            defaultValue = bool(true),
            aliases = listOf("telemetry", "privacy"),
        ),
        // `app.about.changelog` was the other signpost to pi's own TUI: the
        // changelog is behind the built-in `/changelog` (`interactive-mode.ts:3022-3025`)
        // and built-ins are not reachable over RPC (`get_commands` excludes them,
        // `rpc-types.ts:20-74` has no changelog command), so the row could only
        // navigate to the terminal and name the command. It was removed with the
        // TUI-only rows for the same reason as `app.sessions.import` above, and the
        // discovery sentence now lives once on the settings home's 终端 row
        // (`SettingsHome.kt`).
        // Five rows were removed from this group on purpose, because pi has no
        // counterpart for any of them and nothing in the app implements them either:
        //
        //  - `app.about.importFromDesktop` — pi reads its agent dir in place; it has
        //    no import-a-desktop-install command and no archive format for one.
        //  - `app.about.exportConfig` — pi has no config-export command; settings are
        //    plain JSON files at a documented path (`settings-manager.ts:154-181`).
        //  - `app.about.rawSettings` — pi has no raw settings.json editor. Its
        //    `/settings` is a menu of typed editors (`components/settings-selector.ts`),
        //    and this app already has the equivalent per-key editors plus a search
        //    over every registered key (`SettingsSearchScreen`). A second, raw JSON
        //    editor would also be the same foot-gun the `packages` row was changed
        //    for: it lets a user write a file pi's own writers would never produce.
        //  - `app.about.reset` — pi has no reset; the settings menu exposes no
        //    "restore defaults" action (`settings-selector.ts` has no reset id).
        //  - `app.about.licenses` — pi ships LICENSE files but has no licenses
        //    surface, and the app has no license screen or asset. This one is a
        //    judgement call flagged in docs/known-gaps.md §I2: it is a compliance
        //    surface, not a pi behaviour, so it should come back as a real screen if
        //    the app needs one — not as an Action row inside pi's catalog.
    )

    /** Registry lookup by exact dotted key. */
    val byKey: Map<String, PiSetting> = settings.associateBy { it.key }

    /**
     * Keys this App only ever *writes*: the reader is pi itself, so a search for
     * the key name in the app's own source finds nothing and that is correct.
     *
     * The value is the evidence — pi's getter and the first place it is consumed —
     * so a row here can be checked against the pi tree instead of being taken on
     * trust. `tools/run-app-pure-checks.sh` fails when a registered key is neither
     * read by the app (its name appears outside this file) nor listed here, which
     * is the shape of the bug this repository has shipped repeatedly: a switch
     * that is persisted and never consulted (`docs/settings-review.md`).
     */
    val piOwnedKeys: Map<String, String> = mapOf(
        "transport" to "pi 读：settings-manager.ts:831 getTransport → core/sdk.ts:369（建会话时决定传输）",
        "modelThinkingLevels" to "pi 读：settings-manager.ts:808 → core/sdk.ts:218（建会话时的逐模型思考等级）",
        "thinkingBudgets" to "pi 读：settings-manager.ts:1174 → core/sdk.ts:370（建会话时的思考预算）",
        "httpIdleTimeoutMs" to "pi 读：settings-manager.ts:936 → main.ts:851 configureHttpDispatcher（进程启动）",
        "websocketConnectTimeoutMs" to "pi 读：settings-manager.ts:957 → core/sdk.ts:322（建会话时的握手超时）",
        "httpProxy" to "pi 读：main.ts:583 / :850 applyHttpProxySettings(getGlobalSettings().httpProxy)（进程启动）",
        "compaction.reserveTokens" to "pi 读：settings-manager.ts:882 → core/agent-session.ts:540 / :1979 压缩前算预算",
        "compaction.keepRecentTokens" to "pi 读：settings-manager.ts:886 → core/agent-session.ts:540 / :1979 压缩前算保留量",
        "compaction.modelOverrides" to "pi 读：settings-manager.ts:854-880 → core/agent-session.ts:540（按 provider/modelId 覆盖）",
        "branchSummary.reserveTokens" to "pi 读：settings-manager.ts:903 → core/agent-session.ts:3232（分支摘要的预留量）",
        "retry.maxRetries" to "pi 读：settings-manager.ts:927 → core/agent-session.ts:722 / :2918（重试上限）",
        "retry.baseDelayMs" to "pi 读：settings-manager.ts:927 → core/agent-session.ts:722（退避基数）",
        "retry.maxAgentDelayMs" to "pi 读：settings-manager.ts:927 → core/agent-session.ts:2918（单次最大等待）",
        "retry.provider.timeoutMs" to "pi 读：settings-manager.ts:949 → core/sdk.ts:315（厂商请求超时）",
        "retry.provider.maxRetries" to "pi 读：settings-manager.ts:949 → core/sdk.ts:315（厂商层重试次数）",
        "retry.provider.maxRetryDelayMs" to "pi 读：settings-manager.ts:949 → core/sdk.ts:371（厂商要求的长等待上限）",
        "defaultTools" to "pi 读：settings-manager.ts:1319 → core/sdk.ts:257（建会话时的内建工具选择）",
        "shellPath" to "pi 读：settings-manager.ts:993 → core/agent-session.ts:2794 / :3016（跑 bash 时的 shell）",
        "shellCommandPrefix" to "pi 读：settings-manager.ts:1025 → core/agent-session.ts:2793 / :3015（每条命令的前缀）",
        "npmCommand" to "pi 读：settings-manager.ts:1035 → core/package-manager.ts:1748（装包用的 argv）",
        "images.autoResize" to "pi 读：settings-manager.ts:1289 → core/agent-session.ts:522 / :2792（附件与 read 的图片缩放）",
        "images.blockImages" to "pi 读：settings-manager.ts:1302 → core/sdk.ts:271（建会话时是否允许图片进入模型）",
        "enableInstallTelemetry" to "pi 读：settings-manager.ts:1055 → core/telemetry.ts:12（安装上报与厂商归因头）",
    )

    /** The 12 groups of spec §6.4, in order, each with a live summary. */
    val groups: List<PiSettingsGroup> = listOf(
        PiSettingsGroup(G_MODEL, "模型与推理", Icons.Filled.Psychology) { store ->
            val model = PiSettingsCatalog.summaryText(store, "defaultModel")
            val row = byKey["defaultModel"]
            val configured = row != null && row.isExplicit(store)
            val level = PiSettingsCatalog.summaryText(store, "defaultThinkingLevel")
            if (configured) "默认 $model · ◐ $level" else "未配置模型，启动时再选"
        },
        PiSettingsGroup(G_MESSAGES, "消息与网络", Icons.Filled.CompareArrows) { store ->
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
            // Deliberately not derived from a `sessionDir` row: this app pins the
            // session root on pi's command line and in its environment, so there is
            // no setting to read here (see the comment above this group's rows).
            "由本应用固定（与 pi 共用）· 续接：${summaryText(store, "app.sessions.resumeLast")}"
        },
        PiSettingsGroup(G_RESOURCES, "扩展与资源", Icons.Filled.Extension) { store ->
            // 这两个数都是**设置里写了多少条**，不是 pi 加载了多少个：`extensions` 是
            // 一个路径数组（`settings.json`），`packages` 是包来源数组，而 pi 实际加载的
            // 扩展还会来自每个包自己的 `extensions/`、以及工作区 `.pi/extensions`。
            // 原来的写法「N 个扩展」会被读成「pi 加载了 N 个扩展」——设置里写了 0 条不
            // 等于 pi 一个扩展都没加载，那是两件事。所以这里只说设置里的条数。
            val extensionPaths = byKey["extensions"]?.countIn(store) ?: 0
            val packages = byKey["packages"]?.countIn(store) ?: 0
            "设置里 $extensionPaths 条扩展路径 · $packages 个资源包"
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
        PiSettingsGroup(G_SECURITY, "安全与信任", Icons.Filled.Security) { store ->
            "项目信任：${summaryText(store, "defaultProjectTrust")}"
        },
        PiSettingsGroup(G_RUNTIME, "运行时与诊断", Icons.Filled.Memory) { store ->
            // Deliberately not the pi version. `app.runtime.piVersion` is a read-only
            // row supplied by `RuntimeFacts`, not by the store, so reading it here
            // would print the row's fallback and this one line would tell the user
            // 「pi 未安装」 while they are talking to it. The summary states only what
            // the store really holds.
            val keepAlive = summaryText(store, "app.runtime.keepAlive")
            "保活：$keepAlive"
        },
        PiSettingsGroup(G_ABOUT, "隐私与关于", Icons.Filled.Info) { store ->
            "遥测：${summaryText(store, "enableInstallTelemetry")}"
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
