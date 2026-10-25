package app.pi.ui.settings

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import app.pi.ui.components.EffectiveKind
import app.pi.ui.theme.PiSystemPalettes
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
    /**
     * 编辑 sheet 里的补充说明。行上的 description 只有一行（统一后会被截断），
     * 需要展开讲的规则（例如两份提示词文件的差别）放这里，读的人点开才看见。
     */
    val editorNote: String? = null,
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
    /**
     * 快捷添加的**基线**：值为空（键不存在）时 pi 实际会启用的那一组，chip 加在它之上。
     *
     * 只有 `defaultTools` 用非默认值，因为 pi 的 `defaultTools` 是**完整白名单**而不是增量：
     * 直接写 `["grep"]` 会关掉 read/bash/edit/write（依据见 `PiQuickAdd` 的 KDoc）。除它以外
     * 的列表行，空就是空。
     */
    val presetBaseline: List<String> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
    val step: Int? = null,
    val unit: String? = null,
    /** Value rows whose choices are open-ended (models, providers) allow typing. */
    val allowCustom: Boolean = false,
    val readOnly: Boolean = false,
    val dangerous: Boolean = false,
    /**
     * 文本行的编辑器用**多行**框（`PiTextEditorSheet`），单行框只给「一格值」。
     *
     * 判断标准是「这个值是不是一份文档」，凡是文档都写 `multiline = true`：这一行曾经按
     * 「还能不能再进一层编辑器」去猜行数，于是这两行里的「自定义系统提示」（几千字的整份
     * 系统提示词）落进单行框，粘进去的文本只画得出第一行（换行之后的行落在 `maxLines = 1` 的
     * 布局之外），用户看到的就是「几万字只进去几十字」。
     *
     * `depth` 与 `globAware` 曾经是这里的两个字段（"还能再进一层编辑器" / "编辑器懂 glob"），
     * 全树没有任何读取方 —— 19 行数据在承诺一个没人实现的机制。它们已删除
     * （`docs/settings-audit-impl.md` §B9），`settings-audit` 的规则 8 让这种字段无法再回来：
     * `PiSetting` 的每个属性都必须有 `setting.<属性>` 的读者。
     */
    val multiline: Boolean = false,
    /**
     * 框上方那行小字要显示的东西；null 就是 [key] 本身。
     *
     * 默认显示点号键，因为一行设置要回答的是「我在改哪个键」（`PiSettingsEditors` 的
     * `PiEditorField` 把这句话写在框上沿）。但**不是每一行都是键**：系统提示词那两行是
     * pi 的**文件**（`<agentDir>/SYSTEM.md` / `APPEND_SYSTEM.md`），pi 的 settings 里
     * 根本没有这个键，于是写一个像键的字符串上去只是在指认一个不存在的东西（用户看到的
     * 是「上面写着 app runtime，是不是过时了」）。这两行把它填成真正被写的文件路径，
     * 键名只留作行身份（`PromptFileSlot.settingKey`）。
     *
     * 读者：`PiSettingsEditors`（编辑器 sheet 的框上沿）。
     */
    val keyLabel: String? = null,
    /**
     * 空列表那一行的读数（"无覆盖"、"默认 npm"…）。**这个字段有读者**：`PiSetting.display`
     * 以接收者属性（裸名）读它，6 行用了非默认值。`settings-audit` 的字段检查为它保留了一条
     * 带理由的豁免，而不是把它算成"没读"——把它删掉会静默丢掉那 6 句文案。
     */
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

/**
 * 一个设置分组。分组 id 的语义见 [PiSettingsCatalog.groups]：spec §6.4 那 12 组减去
 * 删掉的「终端与 Shell」组，加上本应用自己的「系统提示词」组，去掉并入「安全与隐私」的
 * 遥测组，一共 **11 组**（`docs/settings-audit-impl.md` §9 记了这次信息架构重排的每一条
 * 搬动理由；「终端与 Shell」那五行按用户裁定删除，图片两行移进「消息与网络」）。
 */
class PiSettingsGroup(
    val id: String,
    val title: String,
    val icon: ImageVector,
    /** Live "current value summary" shown on level 0 (spec §6.1). */
    val summary: (PiSettingsStore) -> String,
)

/**
 * pi 的「模型与推理」分组。**首页不再列它**（用户裁定：删掉那个分组入口）——组里的行
 * 全部搬进了「模型与供应商」一屏；`SettingsHome` 用它把入口过滤掉，行本身留着给搜索、
 * `groupTitle` 与就地编辑器用。
 */
internal const val G_MODEL = "model"
/**
 * 本应用自己的分组（spec §6.4 的 12 组里没有它）。pi 的 settings 里没有系统提示词那两行：
 * pi 只把这两个值做成**文件**（`<agentDir>/SYSTEM.md`、`<agentDir>/APPEND_SYSTEM.md`），
 * 所以 spec 没有它们的落点 —— 这是它们原来挂在「运行时与诊断 → 进程」下的原因。组名就叫
 * 「系统提示词」，组里两行可编辑（各写一个文件）加一行只读读数（现在生效的是哪一份）。
 *
 * 这个 id 与 registry 里那个 `prompts` **键**无关：那条是「提示模板」的路径数组。
 */
private const val G_PROMPTS = "prompts"
private const val G_MESSAGES = "messages"
private const val G_COMPACTION = "compaction"
private const val G_RETRY = "retry"
private const val G_TOOLS = "tools"
private const val G_SESSIONS = "sessions"
private const val G_RESOURCES = "resources"
private const val G_APPEARANCE = "appearance"
private const val G_SECURITY = "security"
private const val G_RUNTIME = "runtime"

private fun str(value: String): JsonElement = JsonPrimitive(value)

private fun bool(value: Boolean): JsonElement = JsonPrimitive(value)

private fun num(value: Int): JsonElement = JsonPrimitive(value)

private fun list(vararg values: String): JsonElement = JsonArray(values.map { JsonPrimitive(it) })

private fun choices(vararg pairs: Pair<String, String>): List<PiOption> =
    pairs.map { (wire, label) -> PiOption(wire, label) }

/**
 * 设置面能选的思考等级。**没有 `off`**：pi 的 `Settings.defaultThinkingLevel` /
 * `modelThinkingLevels` 类型是 `ThinkingLevel`（`packages/ai/src/types.ts:85`：
 * minimal/low/medium/high/xhigh/max），`off` 只存在于**模型级**的
 * `ModelThinkingLevel`（思考档位映射）里。写 `off` 到这两个键 pi 不报错，只是静默
 * 回退到默认值 —— 界面上是一个「选了但不生效」的选项。
 */
private val thinkingLevelOptions: List<PiOption> =
    PiThinkingLevel.entries
        .filter { it != PiThinkingLevel.Off }
        .map { PiOption(it.wire, it.label) }

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
    // 根因：pi 1.0.3 把 Azure 的 provider id 从 `azure-openai-responses` 改名成 `azure`
    //（上游 breaking change）。这里选中的值会进 `settings.json` 的 `defaultProvider`，
    // 旧 id 在新引擎里不再被认，会静默回退到别的 provider —— 所以必须跟着改名。
    "azure" to "Azure OpenAI",
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


/**
 * The names offered as chips on the 内建工具 row: **only the ones that are not already on.**
 *
 * pi's built-in set is eight names (`core/tools/index.ts:95` — the `ToolName` union) and the
 * four it enables by default are `read`, `bash`, `edit`, `write` (`defaultActiveToolNames`).
 * So the chips are the three *optional* ones — `grep`, `find`, `ls` — because a "快捷添加"
 * list that also offered the four defaults would be offering to add what the row already
 * says is on, which is what the user rejected: 「默认不是有 4 个工具了吗？…不就给我弄 3 个
 * 没默认启用的不就完事了吗？」. The defaults themselves are named by the row's
 * `emptyListLabel` and its description, and clearing the list returns to exactly them (the
 * editor deletes the key rather than writing `[]`).
 *
 * **`powershell` is deliberately absent** even though pi knows it: its implementation resolves
 * a PowerShell binary and **throws on anything but Windows**
 * (`utils/shell.ts` `getPowerShellConfig`: "The powershell tool is only available on Windows."),
 * and this app's guest is a Linux rootfs with no `pwsh`. Offering it would let the user pick a
 * tool that fails the first time the model calls it.
 */
private val optionalTools = listOf("grep", "find", "ls")

object PiSettingsCatalog {

    /** Every registered key, in spec §6.4 order. */
    val settings: List<PiSetting> = listOf(

        // 这个分组的入口是 App 侧的一页，不是一个 pi 设置：「模型与供应商」把"这台设备上
        // 配了哪些厂商、有哪些模型、哪些已经生效"一次说清（`PiModelInventory`，实现见
        // `ModelProviderScreen`，入口在设置首页最顶栏）。它是本分组的第一行是故意的——
        // 在它之前，这个问题在设置页里没有
        // 任何地方能回答，而"导入过的模型看不到"正是用户报的那个问题。
        // ------------------------------------------------------------------
        // 1 模型与推理
        // ------------------------------------------------------------------
        // 这四行 Action（已导入的模型 / 本地模型 / API Key / 订阅登录）曾经只是
        // 「去别处」的指针，而 G_MODEL 分组页早被删除、没有任何界面渲染它们：
        // 按用户裁定「注册表里没露出来的，就删掉」。四个目的地都仍然在——
        // 「模型与供应商」一屏（设置首页最顶栏）就是它们的归宿，而且那一屏本身
        // 就有导入、API Key、订阅账号三个入口，不需要注册表再转一道手。
        PiSetting(
            key = "defaultProvider",
            title = "默认厂商",
            description = "启动时使用的厂商，例如 anthropic。",
            kind = PiRowKind.Value,
            group = G_MODEL,
            section = "默认模型",
            effective = EffectiveKind.NewSession,
            options = providerOptions,
            allowCustom = true,
        ),
        PiSetting(
            key = "defaultModel",
            title = "默认模型",
            description = "启动时使用的模型 ID；也可以直接填自定义模型。",
            kind = PiRowKind.Value,
            group = G_MODEL,
            section = "默认模型",
            effective = EffectiveKind.NewSession,
            allowCustom = true,
        ),
        PiSetting(
            key = "defaultThinkingLevel",
            title = "默认思考等级",
            description = "启动时使用的思考等级。",
            kind = PiRowKind.Value,
            group = G_MODEL,
            section = "默认模型",
            effective = EffectiveKind.NewSession,
            // pi 没有这个键时用的就是 "medium"（`core/defaults.ts:3`）：不写默认值，
            // 行上会显示成空，看起来像「没设过 = 没有等级」。
            defaultValue = str("medium"),
            options = thinkingLevelOptions,
        ),
        PiSetting(
            key = "enabledModels",
            title = "循环模型",
            // 「新会话生效」，不是「重启引擎」：pi 的 `resolveModelScope` 在 `createRuntime` 闭包里
            // 跑（`main.ts:788`，调用它的 `main.ts:797` 也在同一个闭包内），而那个闭包**每个新
            // 会话都会重新执行一次**（`agent-session-runtime.ts:226-252` 重建 runtime），所以新
            // 会话就会重新解析这份作用域，不必换进程。（原先这里标 `RestartEngine`，理由只看了
            // "一个进程里调用点只有一处"，漏了闭包本身每会话重跑。）
            description = "循环切换的模型，支持通配符；清空 = 全部模型。",
            kind = PiRowKind.List,
            group = G_MODEL,
            section = "默认模型",
            effective = EffectiveKind.NewSession,
            presets = listOf("claude-*", "gpt-*", "gemini-*", "deepseek-*", "qwen-*"),
            emptyListLabel = "全部模型",
        ),
        PiSetting(
            key = "modelThinkingLevels",
            title = "逐模型思考等级",
            // 合法等级与 `defaultThinkingLevel` 的选项同一份（`PiThinkingLevel`）：写错一个
            // 拼错的等级，pi 不报错，只是这一个模型继续用默认等级（`core/sdk.ts:218` 读的是
            // `Record<"provider/modelId", ThinkingLevel>`，值不在枚举里就落回默认）。
            //
            // 这一行现在由专用编辑器打开（模型点选 + 等级点选，没有文本输入），所以描述里
            // 不再教 `名字 = 值` 的写法，只留"选谁、有哪些等级"。
            description = "按模型覆盖启动思考等级。清空并保存 = 回到默认。",
            kind = PiRowKind.List,
            group = G_MODEL,
            section = "逐模型与预算",
            effective = EffectiveKind.NewSession,
            container = PiValueContainer.Object,
            emptyListLabel = "无覆盖",
        ),
        PiSetting(
            key = "thinkingBudgets",
            title = "思考预算",
            // 合法键只有这四个（`packages/ai/src/types.ts:100-105` 的 `ThinkingBudgets`）；
            // 多写的键 pi 原样透传给厂商，厂商不认识就只是白写一行。值是 token 数，pi 不给
            // 默认值（消费侧 `?? 厂商默认`），所以专用编辑器里"留空 = 用厂商默认"才是正确用法。
            description = "逐档思考预算，留空 = 厂商默认。",
            kind = PiRowKind.List,
            group = G_MODEL,
            section = "逐模型与预算",
            effective = EffectiveKind.NewSession,
            container = PiValueContainer.Object,
            emptyListLabel = "未设置",
        ),
        PiSetting(
            key = "hideThinkingBlock",
            title = "隐藏思考块",
            description = "隐藏对话流里的思考块；思考照常进行，只是不显示。",
            kind = PiRowKind.Switch,
            group = G_MODEL,
            section = "行为",
            defaultValue = bool(false),
        ),
        PiSetting(
            key = "cacheWarming",
            title = "缓存预热",
            description = "空闲时续住提示缓存（花钱）。",
            kind = PiRowKind.Value,
            group = G_MODEL,
            section = "行为",
            defaultValue = str("streaming"),
            // 0.86.0 新键。pi 的取值器是活的（`core/sdk.ts:307-312` 把
            // `() => settingsManager.getCacheWarmingMode()` 交给 CacheWarmer，
            // `core/cache-warmer.ts:184` 起每次决策读一次），但它读的是 SettingsManager
            // 内存里的 globalSettings；外部改 settings.json 要到一次 settings 重读才生效，
            // 而 pi 的 `ctx.reload()` 正是这条路径（`modes/rpc/rpc-mode.ts:341` →
            // `core/resource-loader.ts:387-403` → `core/settings-manager.ts:539` 重读全局设置）。
            // 所以这一行不是 NewSession 也不是 RestartEngine，而是 Reload ——
            // 与 `app.extensions.*` 那两行同一个徽标。
            effective = EffectiveKind.Reload,
            options = listOf(
                PiOption("off", "关闭"),
                PiOption("streaming", "仅流式中（默认）"),
                PiOption("idle", "含空闲时段"),
            ),
        ),
        PiSetting(
            key = "showCacheMissNotices",
            title = "缓存未命中提示",
            description = "提示严重的缓存未命中等诊断。",
            kind = PiRowKind.Switch,
            group = G_MODEL,
            section = "行为",
            defaultValue = bool(false),
        ),
        // ------------------------------------------------------------------
        // 2 系统提示词
        // ------------------------------------------------------------------

        //
        // 这两行是 pi 的**文件**，不是 pi 的 settings 键，也不再是启动参数：pi 的
        // `Settings` 接口（`core/settings-manager.ts:106-158`）里没有 systemPrompt，
        // 它的两种官方形态是命令行参数与文件，而 App 现在用文件 —— 值由
        // `PromptFileSettingsStoreDecorator`（`ui/PromptFileSettingsStore.kt`）直接读写
        // `<agentDir>/SYSTEM.md` 与 `<agentDir>/APPEND_SYSTEM.md`。以前这两行拼成
        // `--system-prompt` / `--append-system-prompt` 发给引擎，那会让 pi 的
        // `resourceLoader` 跳过文件发现（`core/resource-loader.js:475`），于是用户自己写的
        // `SYSTEM.md` 在设置页非空时永远是死的，而且和「Pi 文件」屏里能改的同一份文件打架
        // （`settings/PiFiles.kt:114-115`）。文件形态只有一个真相来源，也照着 pi 自己的
        // 优先级链走：工作区 `.pi/SYSTEM.md`（项目受信任时）> 全局 `SYSTEM.md` > pi 自带。
        //
        // 它们原来挂在「运行时与诊断 → 进程」下面，和离线模式、缓存保留并列 —— 那是
        // 「引擎怎么跑」的位置，而这两行是「模型被交代了什么」。用户报的就是找不到/以为没
        // 存进去，所以独立成组，并且在设置首页排在「模型与推理」之后。
        //
        // 生效时机**不是重启引擎**：文件是每次会话重建资源加载器时读的
        // （`core/agent-session-services.js:63-69` 新建 loader + `reload()`，
        // `core/agent-session-runtime.js:116` 换会话时重建），所以两行的 `effective` 是
        // `NewSession`，行上也没有任何「需重启引擎」的字样。
        //
        // **「替换」比名字听起来大得多，两行的文案必须说出这件事。** `core/system-prompt.js`
        // 的 `buildSystemPromptSections` 是 `if (customPrompt) { preamble = 你的 }
        // else { preamble = 自带; tools = …; rules = …; docs = … }` —— 也就是说
        // **填了 `SYSTEM.md` 会让 pi 不再生成 `tools` / `tools 的使用规则` / `pi 文档指引`
        // 这三个 section**（`core/system-prompt.js:78-96`）。「整份替换」四个字看不出这个分量，
        // 所以那一行点明了它，并把「只是想补几条规矩」指到下面那行（追加不分叉、不影响任何
        // section，`if (appendSystemPrompt) addendum = 你的` 在分支之外）。
        PiSetting(
            // `app.prompts.*` 是这几行的身份（同组下面那行只读读数也在这个命名空间里）。
            // 值不在 App 的设置里，由 `PromptFileSlot` 映射到 pi 的文件。
            key = "app.prompts.system",
            title = "自定义系统提示",
            description = "整份替换 pi 的提示词，要追加用下一行。",
            editorNote = "整份替换：连 pi 的工具清单与使用规则也会被这份替换掉。" +
                "留空并保存 = 删掉这份文件，回到 pi 自带；只想补几条规矩，用「追加系统提示」。",
            // 框上沿写的是**真正被写的文件**，不是点号键：这一行不是 pi 的设置项，
            // 写个像键的名字上去等于指认一个不存在的东西。
            keyLabel = "~/.pi/agent/SYSTEM.md",
            kind = PiRowKind.Text,
            group = G_PROMPTS,
            section = "系统提示词",
            defaultValue = str(""),
            // 几千字的 Markdown：编辑器必须给多行框，且框自己能在 sheet 里滚动
            // （`PiTextEditorSheet` 的 `multiline` 分支）。这一行**曾经**漏掉这个
            // 标记，于是落进单行框，粘进去只剩第一行可见。
            multiline = true,
            effective = EffectiveKind.NewSession,
        ),
        PiSetting(
            key = "app.prompts.append",
            title = "追加系统提示",
            description = "在 pi 的提示词后面追加，不替换。",
            editorNote = "只追加：pi 组装好的提示词一个字都不动，这段接在最后。" +
                "留空并保存 = 删掉追加。",
            keyLabel = "~/.pi/agent/APPEND_SYSTEM.md",
            kind = PiRowKind.Text,
            group = G_PROMPTS,
            section = "系统提示词",
            defaultValue = str(""),
            // 同上一行：追加的通常是几段文字，不是一条命令。
            multiline = true,
            effective = EffectiveKind.NewSession,
        ),
        // 只读读数：pi 会读的那几份提示词文件（**两份名字 × 两个层级**，不是只有 `SYSTEM.md`）。
        // 值由 `PiSettingsStack` 在 IO 上算好、经 `valueOverrides` 传下来（与「已发现的主题」
        // 那四条同一个形状），因为它读的是**文件**而不是 store。
        //
        // 形状与用词都沿用那四条资源读数（`PiResourceFacts`）：`N 份 · 项目（<工作区>/.pi） 1 ·
        // 全局（~/.pi/agent） 1` / `还没有任何提示词文件`，来源词取自 `DiscoverySource`，不另写
        // 一套 —— 它们回答的是同一个问题（"pi 实际会加载什么、从哪来"），读起来必须一致。
        //
        // **这里只报能证明的事实**：哪几份文件存在、在哪一级。App 侧拿不到「这个项目受信任」的
        // 确定答案（`PromptFileFacts` 的 KDoc 写了为什么），所以那条规则写在描述里一次，而不是
        // 猜一个裁决。
        //
        // 与 pi 的两处**有意的差距**（不是偷懒，是 UI 的地板）：pi 把这几份文件的**路径逐行**
        // 列在它 TUI 的 `Context` 小节里（`modes/interactive/interactive-mode.js:1382-1392`），
        // 而且列表为空时**整个小节不出现**。在这里：只读行的值 `maxLines = 1`
        // （`PiSettingsRows.kt:158`），路径写进描述；注册表没有条件隐藏机制，"没有 ⇒ 不显示"
        // 要新造机制。所以零状态借用 App 自己的词（`还没有…`）。
        PiSetting(
            key = "app.prompts.effectiveSource",
            title = "提示词文件",
            description = "pi 会读的提示词文件（工作区优先）。",
            kind = PiRowKind.Text,
            group = G_PROMPTS,
            section = "当前生效",
            defaultValue = str("未读取"),
            readOnly = true,
        ),
        // ------------------------------------------------------------------
        // 3 消息与网络
        // ------------------------------------------------------------------

        PiSetting(
            key = "steeringMode",
            title = "引导模式",
            description = "流式中发送的消息怎么送达：逐条，或全部一起给。",
            kind = PiRowKind.Value,
            group = G_MESSAGES,
            section = "送达",
            defaultValue = str("one-at-a-time"),
            options = deliveryOptions,
        ),
        PiSetting(
            key = "followUpMode",
            title = "后续模式",
            description = "本轮结束后再发的消息怎么送达：逐条，或全部一起给。",
            kind = PiRowKind.Value,
            group = G_MESSAGES,
            section = "送达",
            defaultValue = str("one-at-a-time"),
            options = deliveryOptions,
        ),
        PiSetting(
            key = "transport",
            title = "传输方式",
            description = "多传输厂商优先用哪一种。",
            kind = PiRowKind.Value,
            group = G_MESSAGES,
            section = "网络",
            defaultValue = str("auto"),
            effective = EffectiveKind.NewSession,
            options = transportOptions,
        ),
        PiSetting(
            key = "httpIdleTimeoutMs",
            title = "HTTP 空闲超时",
            description = "HTTP 头/体的空闲超时（毫秒）；0 = 不超时。",
            kind = PiRowKind.Number,
            group = G_MESSAGES,
            section = "网络",
            defaultValue = num(300000),
            effective = EffectiveKind.RestartEngine,
            min = 0,
            max = 3600000,
            step = 1000,
            unit = "ms",
        ),
        PiSetting(
            key = "websocketConnectTimeoutMs",
            title = "WebSocket 连接超时",
            description = "握手超时（毫秒，0 不限）。",
            kind = PiRowKind.Number,
            group = G_MESSAGES,
            section = "网络",
            defaultValue = num(15000),
            effective = EffectiveKind.NewSession,
            min = 0,
            max = 300000,
            step = 1000,
            unit = "ms",
        ),
        PiSetting(
            key = "httpProxy",
            title = "HTTP 代理",
            description = "代理地址，对 pi 自己发起的请求生效。",
            kind = PiRowKind.Text,
            group = G_MESSAGES,
            section = "网络",
            defaultValue = str(""),
            effective = EffectiveKind.RestartEngine,
        ),
        PiSetting(
            key = "images.autoResize",
            title = "图片自动缩放",
            description = "图片自动缩到最大 2000×2000。",
            kind = PiRowKind.Switch,
            group = G_MESSAGES,
            section = "图片",
            effective = EffectiveKind.NewSession,
            defaultValue = bool(true),
        ),
        PiSetting(
            key = "images.blockImages",
            title = "屏蔽图片",
            description = "不把图片发给模型（附件仍可加）。",
            kind = PiRowKind.Switch,
            group = G_MESSAGES,
            section = "图片",
            effective = EffectiveKind.NewSession,
            defaultValue = bool(false),
        ),
        // ------------------------------------------------------------------
        // 4 上下文与压缩
        // ------------------------------------------------------------------

        PiSetting(
            key = "compaction.enabled",
            title = "自动压缩",
            description = "上下文接近上限时自动压缩历史，避免请求溢出。",
            kind = PiRowKind.Switch,
            group = G_COMPACTION,
            section = "压缩",
            defaultValue = bool(true),
        ),
        PiSetting(
            key = "compaction.reserveTokens",
            title = "保留 token",
            description = "为回复预留 token；0 = 无余量。",
            kind = PiRowKind.Number,
            group = G_COMPACTION,
            section = "压缩",
            effective = EffectiveKind.NewSession,
            defaultValue = num(16384),
            min = 0,
            max = 1000000,
            step = 1024,
            unit = "token",
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
        ),
        PiSetting(
            key = "compaction.modelOverrides",
            title = "逐模型覆盖",
            description = "按厂商/模型覆盖上面两项。",
            kind = PiRowKind.List,
            group = G_COMPACTION,
            section = "压缩",
            effective = EffectiveKind.NewSession,
            container = PiValueContainer.Object,
            emptyListLabel = "无覆盖",
        ),
        PiSetting(
            key = "branchSummary.reserveTokens",
            title = "分支摘要保留 token",
            description = "分支摘要预留 token（输出上限 4096）。",
            kind = PiRowKind.Number,
            group = G_COMPACTION,
            section = "压缩",
            effective = EffectiveKind.NewSession,
            defaultValue = num(16384),
            min = 0,
            max = 1000000,
            step = 1024,
            unit = "token",
        ),
        // Implemented: `PiRoot` calls `session.compact()`, i.e. the RPC command
        // `compact` (`rpc-types.ts:44`). The command also takes
        // `customInstructions`, but that needs a text field; the chat palette's
        // `/compact <指令>` already has one (`ChatScreen.kt:341`), so the
        // description names it instead of promising a field this row has not got.
        PiSetting(
            key = "app.compaction.runNow",
            title = "立即压缩",
            description = "手动压缩当前会话。",
            kind = PiRowKind.Action,
            group = G_COMPACTION,
            section = "动作",
        ),
        // ------------------------------------------------------------------
        // 5 重试
        // ------------------------------------------------------------------

        PiSetting(
            key = "retry.enabled",
            title = "自动重试",
            description = "遇到瞬时错误时自动重试整轮请求。",
            kind = PiRowKind.Switch,
            group = G_RETRY,
            section = "重试",
            defaultValue = bool(true),
        ),
        PiSetting(
            key = "retry.maxRetries",
            title = "最大重试次数",
            description = "整轮请求的重试上限。",
            kind = PiRowKind.Number,
            group = G_RETRY,
            section = "重试",
            effective = EffectiveKind.NewSession,
            defaultValue = num(3),
            min = 0,
            max = 20,
            step = 1,
            unit = "次",
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
        ),
        PiSetting(
            key = "retry.maxAgentDelayMs",
            title = "最大重试延迟",
            description = "单次重试等待的上限，默认 60 秒。",
            kind = PiRowKind.Number,
            group = G_RETRY,
            section = "重试",
            effective = EffectiveKind.NewSession,
            defaultValue = num(60000),
            min = 0,
            max = 600000,
            step = 1000,
            unit = "ms",
        ),
        PiSetting(
            key = "retry.provider.timeoutMs",
            title = "Provider 超时",
            description = "单次请求超时（留空用默认）。",
            kind = PiRowKind.Number,
            group = G_RETRY,
            section = "Provider 重试（高级）",
            effective = EffectiveKind.NewSession,
            min = 0,
            max = 3600000,
            step = 1000,
            unit = "ms",
        ),
        PiSetting(
            key = "retry.provider.maxRetries",
            title = "Provider 重试次数",
            description = "SDK 层的重试上限，默认 0；调高可能让限流先被吞掉。",
            kind = PiRowKind.Number,
            group = G_RETRY,
            section = "Provider 重试（高级）",
            effective = EffectiveKind.NewSession,
            defaultValue = num(0),
            min = 0,
            max = 20,
            step = 1,
            unit = "次",
        ),
        PiSetting(
            key = "retry.provider.maxRetryDelayMs",
            title = "Provider 最大延迟",
            description = "厂商要求的等待超过这个值就直接失败；0 = 不限制。",
            kind = PiRowKind.Number,
            group = G_RETRY,
            section = "Provider 重试（高级）",
            effective = EffectiveKind.NewSession,
            defaultValue = num(60000),
            min = 0,
            max = 3600000,
            step = 1000,
            unit = "ms",
        ),
        // ------------------------------------------------------------------
        // 6 工具
        // ------------------------------------------------------------------

        PiSetting(
            key = "defaultTools",
            title = "内建工具",
            // 短。用户对这条的裁定：「写这么多描述文字干鸡巴毛呀？」——默认值是哪 4 个、
            // 快捷添加那 3 个是什么、清空等于什么，三件事说完即可；powershell 为什么不出现
            // 写在 `optionalTools` 的 KDoc 里，`defaultTools: []` 那种极端写法由 `Pi 文件` 屏负责。
            //
            // `presetBaseline` 是这一行的**写入语义**：普通名字的 `defaultTools` 是完整白名单
            // （`core/sdk.ts:264` 只启用列出的），所以 chip 只能加在默认四件套之上 —— 曾经直接
            // 写 `["grep"]`，用户实测到工具全灭（`settings.json` 里只剩 `["grep","find","ls"]`）。
            //
            // 0.99.0 给同一个键加了 `+name`/`-name` 增量语法（`core/settings-manager.ts:234-245`，
            // 关键是 `:236`：列表里一个普通名字都没有时，基线是**默认四件套**；有普通名字时，
            // 基线就是那一串）。所以这一行读回来的值必须先按 `PiQuickAdd.resolveDefaultTools`
            // 解析再展示与写回，不能把原数组当成生效集合 —— `["+codemode"]` 长度 1、生效 5 个。
            // 另：`getDefaultTools()` 在 0.99.2 也变成返回解析后的列表（`:1429-1435`），
            // 0.87.1 的「原样返回」不再成立。见 `PiQuickAdd`。
            description = "默认 read/bash/edit/write。",
            kind = PiRowKind.List,
            group = G_TOOLS,
            section = "工具",
            effective = EffectiveKind.NewSession,
            presets = optionalTools,
            presetBaseline = PiQuickAdd.builtinToolDefaults,
            emptyListLabel = "默认 read/bash/edit/write",
        ),
        PiSetting(
            key = "codemode.mode",
            title = "Codemode 方式",
            description = "on 附调用方式，only 只列 codemode。",
            kind = PiRowKind.Value,
            group = G_TOOLS,
            section = "工具",
            // pi 的默认值：`core/settings-manager.ts` 的 `CodemodeSettings.mode` 文档写明
            // 「Default: `on`」，且 `codemode/index.ts` 的读取用 `?? "on"`。
            defaultValue = str("on"),
            effective = EffectiveKind.NewSession,
            options = listOf(
                PiOption("on", "on（默认）"),
                PiOption("only", "only"),
            ),
        ),
        PiSetting(
            key = "codemode.inlineBudget",
            title = "Codemode 声明预算",
            description = "工具声明可花的估算 token（字符数 / 4）。",
            kind = PiRowKind.Number,
            group = G_TOOLS,
            section = "工具",
            // 「Default: 3000」（`settings-manager.ts` 的 `CodemodeSettings.inlineBudget`）。
            defaultValue = num(3000),
            effective = EffectiveKind.NewSession,
            min = 0,
            max = 200000,
            step = 100,
            unit = "token",
        ),
        // `fullscreenWheelScrollLines`（0.99.0 新键）**故意不在这里**：它只影响原版 pi TUI 在
        // 全屏模式下的鼠标滚轮行数，本应用不发它、界面上也没有鼠标滚轮 —— 与
        // `fullscreenCopyOnSelect`/`fullscreenExitOutput`/`fullscreenScrollbar` 三个键一致。
        // `deviceId`（同批新键）同样不展示：它是 pi 为 ChatGPT 登录生成的安装标识，只写全局设置、
        // 从 bug report 里排除，是 pi 的内部状态而不是一个用户能决定的值。App 的写入路径是
        // 「锁内重读整份文档、只改目标键」，所以它**不会被删掉**（`PiSettingsFileStore.kt`）。
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
            description = "工具卡默认展开输出，而不是只显示一行摘要。",
            kind = PiRowKind.Switch,
            group = G_TOOLS,
            section = "工具",
            defaultValue = bool(false),
        ),
        // ------------------------------------------------------------------
        // 7 会话
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
        // **默认开**（用户裁决）。
        //
        // D31 当年把「打开 App 开始新会话」定为**有意默认**，理由是 pi 自己不传 `-c`/`--resume`
        // 时也是新会话（`main.ts:426-443`），而且"打开就是新对话"对终端用户是熟悉的行为。
        // 这一行的 `effective` 是 `EffectiveKind.RestartApp`：它在**App 启动**时才被读取
        // （`PiSettingsEditors.kt:131-135` 对用户的说法就是「这个值在 App 启动时读取，必须先结束
        // App 再启动」）。于是当年的默认意味着：**每一次 App 级重启之后都在一段新对话里**。
        //
        // 用户已经反复明确要求：换运行时之后必须还在**同一段对话**里（他的证据是两份会话文件：
        // 旧的那份 26 条用户消息、新的那份零上下文开局，时间点正是他换完运行时重启 App 的时刻）。
        // 所以默认翻成开：**打开 App 接回上次那段对话；想要新对话用「＋ 新建会话」。**
        //
        // 运行时的读取必须走注册表这一处默认值（`PiSessionViewModel.resumeLastEnabled()` 用
        // `boolIn`），否则"翻默认值"只会改设置页的显示、运行时仍按关处理。它现在只喂**冷启动**
        // 那条路（`PiLaunchOptions.continueMostRecent` → argv 的 `-c`）：进程内重启（换运行时、
        // 装包、崩溃后重试）由 `--session-id` 钉住，与这一行无关。
        PiSetting(
            key = "app.sessions.resumeLast",
            title = "启动续接最近会话",
            description = "启动 App 时自动切到最近一次会话。",
            kind = PiRowKind.Switch,
            group = G_SESSIONS,
            section = "存储",
            defaultValue = bool(true),
            effective = EffectiveKind.RestartApp,
        ),
        // ------------------------------------------------------------------
        // 8 扩展与资源
        // ------------------------------------------------------------------

        // 这一节回答的是"我到底有什么"：数字来自**真实扫描**（`WorkspaceResourceScan`，项目页
        // 资源段用的同一个），由 `PiResourceFacts.kt` 做纯计算、由 `PiSettingsStack` 在 IO 上
        // 跑并把文本塞进 `valueOverrides`。它不是设置行：四个键都没有写入方，`readOnly = true`。
        //
        // 为什么这一节必须存在：删掉下面那四条"额外搜索路径"之后，如果这一屏只剩开关与资源包，
        // 用户看到的仍然是"空"——而真相是 pi 一直在自动发现 `~/.pi/agent/<kind>`、工作区
        // `.pi/<kind>`、`.agents/<kind>` 与已装包自带的资源。数字是**读数**，不是承诺。
        // 顺序 = pi 自己的加载顺序（`core/package-manager.ts:203-204` 的 `RESOURCE_TYPES`：
        // extensions / skills / prompts / themes；`resource-loader.ts` 的 reload 照它走，
        // extensions `:456` → skills `:468` → prompts `:483` → themes `:502`）。
        // 「扩展包与项目信任」页的四节与分组摘要里的"已发现 …"都是这一个顺序 —— 不按我们自己的
        // 习惯排。

        PiSetting(
            key = "app.resources.discovered.extensions",
            title = "已发现的扩展",
            description = "pi 发现的扩展。",
            kind = PiRowKind.Text,
            group = G_RESOURCES,
            section = "实际发现",
            defaultValue = str("未读取"),
            readOnly = true,
        ),
        PiSetting(
            key = "app.resources.discovered.skills",
            title = "已发现的技能",
            description = "pi 发现的技能；加新技能放一个 SKILL.md。",
            kind = PiRowKind.Text,
            group = G_RESOURCES,
            section = "实际发现",
            defaultValue = str("未读取"),
            readOnly = true,
        ),
        PiSetting(
            key = "app.resources.discovered.prompts",
            title = "已发现的提示模板",
            description = "pi 发现的提示模板；用 /名字 调用，支持 $1。",
            kind = PiRowKind.Text,
            group = G_RESOURCES,
            section = "实际发现",
            defaultValue = str("未读取"),
            readOnly = true,
        ),
        PiSetting(
            key = "app.resources.discovered.themes",
            title = "已发现的主题",
            description = "pi 发现的主题；在「外观」里选。",
            kind = PiRowKind.Text,
            group = G_RESOURCES,
            section = "实际发现",
            defaultValue = str("未读取"),
            readOnly = true,
        ),
        PiSetting(
            key = "app.extensions.args",
            title = "扩展启动参数",
            // The one row here whose value is **not** a pi settings key: pi has no
            // settings key for an extension-registered flag, so this text is kept in
            // the app's own sidecar and passed as arguments at spawn
            // (`ExtensionArgsStore`, `ExtensionFlagArgs`). The description says the
            // shape a user has to write, because the flag *names* belong to whatever
            // extension they installed — pi accepts any `--flag` it does not know and
            // hands it to the extensions that declared it, so there is nothing to pick
            // from a list here (`core/agent-session-services.ts:107-111`).
            description = "按命令行方式传参，例如 --plan。",
            kind = PiRowKind.Text,
            group = G_RESOURCES,
            section = "技能命令与扩展参数",
            defaultValue = str(""),
            effective = EffectiveKind.RestartEngine,
        ),
        PiSetting(
            key = "enableSkillCommands",
            title = "技能命令",
            description = "技能注册成 /skill:name 命令。",
            kind = PiRowKind.Switch,
            group = G_RESOURCES,
            section = "技能命令与扩展参数",
            defaultValue = bool(true),
            // `Immediate`, not `Reload`: pi reads this setting only in its own TUI
            // (`interactive-mode.ts:716`, `:4570`) and its RPC `get_commands` lists
            // skill commands unconditionally (`rpc-mode.ts:702-708`), so nothing
            // inside pi would ever change. The command panel this row is about is
            // *this app's*, built by `piCommandPalette`, and it filters on the write
            // (`PiSessionViewModel.onSettingWritten` → `refreshCommands`) — so the
            // row takes effect at once, which is also what pi's TUI does.
            effective = EffectiveKind.Immediate,
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
            description = "已安装的资源包；手改易失效。",
            kind = PiRowKind.List,
            group = G_RESOURCES,
            section = "资源包",
            defaultValue = list(),
            effective = EffectiveKind.RestartEngine,
            readOnly = true,
        ),
        // 出口：这一节报的是"发现了什么"，而看/改它们的地方是「Pi 文件」屏（`PiFilesScreen`，
        // 本栈已有的层级 `piFiles`，不新造导航）。它同时替掉被删掉的那四条手填路径：想加资源
        // 就是往标准目录里放文件，标准目录本来就自动发现，不需要在设置里登记路径。
        PiSetting(
            key = "app.resources.openFiles",
            title = "查看资源文件",
            description = "打开 Pi 文件查看与编辑资源。",
            kind = PiRowKind.Action,
            group = G_RESOURCES,
            section = "动作",
        ),
        // ------------------------------------------------------------------
        // 9 外观
        // ------------------------------------------------------------------
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

        PiSetting(
            key = "theme",
            title = "主题",
            description = "固定一个主题，或跟随系统分别选浅色与深色。",
            kind = PiRowKind.Value,
            group = G_APPEARANCE,
            section = "外观",
            defaultValue = str("dark"),
            // **`Immediate`，不是 `Reload`。** 这一行原来带「需重载」徽标，而它写下去的那一刻
            // 就生效了：设置栈把写入转给 `PiSessionViewModel.onSettingWritten`，那里第一件事就是
            // `if (key == "theme") { refreshTheme(); return }`（`PiSessionViewModel.kt:1128-1130`）
            // —— `refreshTheme()` 重新 `PiThemeLoader.load` 并发布 `_theme`，`MainActivity.kt:89-93`
            // 收着它构造 `PiTheme(palette = …)`，所以整个界面连同代码高亮（`PiSyntaxToken.color(palette)`，
            // `ui/render/PiCodeHighlight.kt:73-77`）与窗口底色（`MainActivity.kt:83-85`）当场换色。
            // 没有任何东西在等一次"重载"：徽标说「需重载」就是在说假话（`docs/known-gaps.md:701`
            // 记过同一形状 —— 三个进程开关借用 `Reload` 时也是这么被改掉的）。
            //
            // 唯一"晚一点"的是 pi 自己的 TUI（终端页里那个 pi 进程按自己的启动读主题），那不是这一行
            // 承诺的对象，也不是"重载"能解决的 —— 那条路要重开终端。alias 里的 `reload` 留着：
            // 它是 pi 自己的词（pi 的 TUI 用 `/reload` 应用主题），是搜索用的，不是时间承诺。
            allowCustom = true,
        ),
        // 「配色方案」：`system` 主题的**输入**，不是第二份主题。
        //
        // 用户裁定过 B 方案（`system` 要能选 ANSI 调色板），而在这一行之前全树只有注入点
        // `PiSystemPaletteSource`（`ui/theme/PiSystemPalette.kt`），没有任何界面能写它 ——
        // 一个没人能改的设置就是一个死开关，所以这一行补的是"谁写"。
        //
        // 它只换输入（终端 ANSI 16 色 + 底色）：56 个 token 仍由 `deriveSystemPalette`
        // 解出（`system-theme.ts:400` 的移植），明暗也仍由 `terminalAppearance`
        // （`:271-290`）按底色判 —— 所以 `dark` / `light` 与自定义主题文件**不受影响**，
        // 这句话写在选择层的正文里。写入后由 `PiSessionViewModel.refreshTheme` 在
        // loader 之前灌进注入点再重算（`app.appearance.` 前缀本来就会触发那次刷新）。
        //
        // 值落在 `app-prefs.json`（`app.*` 由 `PiSettingsFileStore` 转进侧车文件），
        // 不进 pi 的 `settings.json`：pi 没有这个键，它只影响本 App 的渲染。
        // 键名必须写成**字面量**：`settings-audit`（`PiSettingsAuditCheck.kt` 的
        // `keyPattern`）以及 `tools/pi-contract.mjs` 的 `appKeys` 都是按注册表里
        // `key = ` 后面跟一个引号字符串这一行认行的（**它们不剥注释**），写成常量会让
        // 这一行从清单里消失，架构规则与"每个键都有读者"于是根本看不到它。同一个字符串在
        // `PiSystemPalettes.SETTING_KEY`（读者一侧：`PiSessionViewModel.applySystemPalette`
        // 与 `PiSettingEditorHost` 都用那个常量）—— 两处必须一起改。
        PiSetting(
            key = "app.appearance.systemPalette",
            title = "系统主题",
            description = "自定义 system 主题的底色：其余颜色按 pi 的规则推导。dark / light 与第三方主题不受影响。",
            kind = PiRowKind.Value,
            group = G_APPEARANCE,
            section = "外观",
            defaultValue = str(PiSystemPalettes.DEFAULT_ID),
            // 标签与档位顺序只有一处来源（`PiSystemPalettes.presets`）：行上显示的
            // "当前值"就是 `PiSetting.display` 按这份 options 翻出来的中文名。
            options = PiSystemPalettes.presets.map { PiOption(it.id, it.label) },
        ),
        // `app.appearance.dynamicColor` was deleted here. Android dynamic colour
        // cannot coexist with the rule this app runs on — every colour comes from
        // `PiTheme.palette`, which *is* the resolved pi theme — so the switch
        // could never have been honoured without breaking the theme contract.
        // Colour always comes from the theme file (see `ui/theme/PiThemeFiles.kt`).
        // `app.appearance.fontScaleDelta`（Number ±2）与 `app.appearance.messageDensity`
        // （舒服/紧凑/宽松）原来各占一行，现在被下面这三行取代 —— **两行都从注册表删掉了**，
        // 理由是一条决定不能有两个真相：三行都在说同一件事（对话流排多大、多松），留着旧行
        // 就是两个控件互相盖。旧值不丢：`PiSessionViewModel.readPrefs` 做一次性读时迁移
        // （`typography` 缺席而 `messageDensity` 在 → compact/comfortable/cozy→loose；
        // `fontSize` 缺席而 `fontScaleDelta` 在 → −2/−1→small、0→normal、1→large、2→xlarge），
        // 迁移函数与理由在 `PiTypographyProfile.migratedTypography` / `migratedFontSize`。
        // 删行不等于删能力：旧的 ±2sp 偏移在新行里是四个具名档（小/标准/大/更大），
        // 旧的块间距三档在预设的 `blockSpacing` 里（紧凑 4 / 舒适 8 / 宽松 12）。
        PiSetting(
            key = "app.appearance.typography",
            title = "排版",
            description = "正文、行高、段距与各种字号一起换档；底色不跟。",
            kind = PiRowKind.Value,
            group = G_APPEARANCE,
            section = "外观",
            defaultValue = str("comfortable"),
            options = choices(
                "classic" to "经典",
                "compact" to "紧凑",
                "comfortable" to "舒适",
                "loose" to "宽松",
            ),
        ),
        PiSetting(
            key = "app.appearance.lineHeight",
            title = "行距",
            description = "行高再乘一档：紧/标准/松。",
            kind = PiRowKind.Value,
            group = G_APPEARANCE,
            section = "外观",
            defaultValue = str("normal"),
            options = choices(
                "tight" to "紧",
                "normal" to "标准",
                "loose" to "松",
            ),
        ),
        PiSetting(
            key = "app.appearance.fontSize",
            title = "字号",
            description = "字号偏移：−1 到 +2 sp。",
            kind = PiRowKind.Value,
            group = G_APPEARANCE,
            section = "外观",
            defaultValue = str("normal"),
            options = choices(
                "small" to "小",
                "normal" to "标准",
                "large" to "大",
                "xlarge" to "更大",
            ),
        ),
        PiSetting(
            key = "app.appearance.showTimestamps",
            title = "显示时间戳",
            description = "消息带时间、跨天插日期（默认关）。",
            kind = PiRowKind.Switch,
            group = G_APPEARANCE,
            section = "外观",
            // 默认从 true 改 false（用户裁定）。这里、`UiPrefs.showTimestamps` 和
            // `readPrefs` 的兜底是**同一件事的三处写法**，改一处就是三处不一致。
            defaultValue = bool(false),
        ),
        PiSetting(
            key = "app.appearance.thinkingCollapsedByDefault",
            title = "思考块默认折叠",
            description = "思考块默认收起成一行「思考 12s」，点击展开。",
            kind = PiRowKind.Switch,
            group = G_APPEARANCE,
            section = "外观",
            defaultValue = bool(true),
        ),
        // ------------------------------------------------------------------
        // 10 运行时与诊断
        // ------------------------------------------------------------------

        // The seven `app.device.*` rows lived here and were the *only* occurrence
        // of those keys: the enforcement and the real switches both read
        // `DeviceCapabilityStore`'s SharedPreferences
        // (`DeviceCapabilityStore.kt:61-96`, reached from the 设备能力 entry row on
        // SettingsHome), so editing a row here wrote a JSON value no code
        // consulted — two screens for one grant, only one connected. The real
        // screen is the single authority now.

        PiSetting(
            key = "app.runtime.piVersion",
            title = "pi 版本",
            description = "当前内置的 pi 版本；读不到时会写明原因。",
            kind = PiRowKind.Text,
            group = G_RUNTIME,
            section = "运行时状态",
            defaultValue = str("未读取"),
            readOnly = true,
        ),
        // `app.runtime.checkUpdate` and `app.runtime.rollback` used to sit here and
        // were removed on purpose. pi's own check is a network call the app turns
        // off deliberately (`PiEngineHost.kt:305-306` sets `PI_SKIP_VERSION_CHECK`,
        // because a phone app should not make it behind the user's back), and its
        // replacement, `pi update --self`, is an npm self-update
        // (`package-manager-cli.ts:1033-1068`) that this app's engine cannot use:
        // the engine is an APK payload that `RuntimeProvisioner` extracts over the tree
        // (`extractEngine`) whenever the packaged engine's own digest changes, so an
        // in-guest update is overwritten the next time that payload moves. pi has no
        // rollback at all (no such subcommand in `cli.ts` /
        // `package-manager-cli.ts`). The read-only 「pi 版本」 row above is what
        // tells the user which version they are on.
        PiSetting(
            key = "app.runtime.nodeVersion",
            title = "Node 版本",
            description = "内置运行时里的 Node 版本；读不到时会写明原因。",
            kind = PiRowKind.Text,
            group = G_RUNTIME,
            section = "运行时状态",
            defaultValue = str("未读取"),
            readOnly = true,
        ),
        PiSetting(
            key = "app.runtime.rootfsUsage",
            title = "运行时占用",
            // 口径必须与实现一致（`RuntimeFacts.runtimeUsage`）：它量的是 <files>/pi/runtime
            // **整棵目录** —— 解包出来的 Linux 系统树（里面包含 agent 目录：npm 包、会话文件）、
            // proot 的临时目录、载荷记账文件；不跟随符号链接，按已分配块数计（不是表观大小）。
            // 原来的文案写「与包缓存的磁盘占用」，既没说清是整棵树，也没说会话也在里面。
            description = "运行时目录实际占用（含系统树与临时目录）。",
            kind = PiRowKind.Text,
            group = G_RUNTIME,
            section = "运行时状态",
            defaultValue = str("未读取"),
            readOnly = true,
        ),
        PiSetting(
            key = "app.runtime.engineStartup",
            title = "引擎启动耗时",
            description = "上一次引擎从启动到能响应命令的用时。",
            kind = PiRowKind.Text,
            group = G_RUNTIME,
            section = "运行时状态",
            defaultValue = str("未读取"),
            readOnly = true,
        ),
        PiSetting(
            key = "app.runtime.wakeLock",
            title = "唤醒锁状态",
            // The policy changed with this row's wording: the lock is held while the
            // engine starts or a turn runs, and released when idle (`PiEngineLifecyclePolicy`),
            // so "未持有" while the app sits idle is normal — the old text ("最长 6
            // 小时") described the old always-held lock and would now read as a fault.
            description = "引擎忙时保持唤醒，空闲自动释放。",
            kind = PiRowKind.Text,
            group = G_RUNTIME,
            section = "运行时状态",
            defaultValue = str("未读取"),
            readOnly = true,
        ),

        // ------------------------------------------------------------------
        // 运行时选择（proroot）—— 开关与它的派生读数**同属一节、上下紧挨**：它们是
        // 同一个功能的两半（控件在上、结果紧贴在下）。拆到两节里曾让用户以为下面那行
        // 在说另一件事（「关掉 Pro Root 为什么要写着未开启」）——那一行的值现在也永远
        // 先写「实际在跑的那个运行时」，原因跟在括号里。
        //
        // 这两行是本应用自己的状态，**不写进 pi 的 settings.json**：它决定用哪个二进制
        // 启动 guest，在 pi 存在之前就要定下来，pi 那边没有读者。值由
        // `AppOnlySettingsStore` 拦下，落在 `RuntimePreferences`（SharedPreferences，
        // 与 `DeviceCapabilityStore` 同一种存法）。行仍然放在注册表里，因为
        // 「运行时与诊断」正是用户找它的地方。
        //
        // 文案必须诚实，四件事缺一不可：实验性 / 默认关 / 不可用时自动回退 proot /
        // 装机与维护始终 proot；再加两条已知取舍：闭源不可审计、提速幅度未量化
        // （`docs/proroot-research.md` §2.3 只有 DSHA 自己的一组数字，机型还不同）。
        //
        // `effective = AutoRestartEngine`：这个开关**自己**会重启引擎来换运行时
        // （`AppOnlySettingsStore.write` 清结论 → `PiSessionViewModel.onSettingWritten`
        // → `RuntimeSwitchAction` 的判定），所以徽标是「自动重启引擎」——「需重启引擎」
        // 会让用户以为还要自己动手，而那正是他要修掉的东西（「为什么还要退出软件重进呢」）。
        // ------------------------------------------------------------------
        PiSetting(
            key = "app.runtime.proroot",
            title = "运行时加速（实验性）",
            description = "proroot 跑引擎；会探针并重启（闭源）。",
            kind = PiRowKind.Switch,
            group = G_RUNTIME,
            section = "运行时选择",
            defaultValue = bool(false),
            effective = EffectiveKind.AutoRestartEngine,
        ),
        PiSetting(
            key = "app.runtime.prorootStatus",
            title = "运行时（实际生效）",
            // 值先说「在跑哪个运行时」，原因跟在括号里——这一行答的是「在跑哪个」，不是
            // 「开关在哪一档」；`RuntimeChoice.describe` 的那组句子因此全部改成了这个形状。
            // 值下面还会列出探针逐阶段的原始判读：说明文字必须指向那两块内容，否则用户仍然
            // 不知道「探针未通过」到底卡在哪一步（缺陷原形：开关开着，行上只有半句话，
            // 唯一能看到证据的地方是导出的报告）。
            description = "这次用的运行时；没用上时括号里写原因。",
            kind = PiRowKind.Text,
            group = G_RUNTIME,
            section = "运行时选择",
            defaultValue = str("未读取"),
            readOnly = true,
        ),

        // ------------------------------------------------------------------
        // 进程开关（pi 的启动参数 / 环境变量）
        //
        // 这里的每一行都是 pi 的**进程级**配置，pi 自己的 settings 文档里没有对应的键
        // （`core/settings-manager.ts:110-163` 的 `Settings` 里没有 offline /
        // cacheRetention / noContextFiles / noExtensions / noSkills /
        // noPromptTemplates / noThemes），
        // 所以键是我们自己的（`app.` 前缀），值由 App 组进 `PiLaunchOptions`
        // （`rpc/PiLaunchOptions.kt`）在启动 `pi --mode rpc` 时传下去 —— 这与 pi
        // 的 CLI 参数一一对应，表的权威副本是 `rpc/PiPreSpawnConfig.kt`：
        //   `--offline`（`cli/args.ts:223`，等价 `PI_OFFLINE=1`，`:433`）
        //   `PI_CACHE_RETENTION=long`（`packages/ai/src/api/anthropic-messages.ts:64`）
        //   `--no-context-files`（`:194`）
        //   `--no-extensions` / `--no-skills` / `--no-prompt-templates` / `--no-themes`
        //   （`:169` / `:188` / `:190` / `:192`）
        // 四个 `--no-*` 与 pi **1:1**：pi 只停「发现」，显式 `-e` / `--skill` /
        // `--prompt-template` / `--theme` 仍然生效（`core/resource-loader.ts:452` /
        // `:468` / `:483` / `:502`；帮助文本 `:303`）。**本应用一个显式路径都不传**，
        // 所以打开「停用扩展发现」时随包扩展也一起停。要「只关别人的、留下自带的」
        // 是另一个 app 侧功能，不靠 `-e` 绕过这个旗标。
        // 因为进程启动后这些值不再变（值是 App 读 settings 后写进 argv/env 的，
        // 新会话不会重读 argv），`effective` 必须是 `RestartEngine`（界面标签
        // 「需重启引擎」），每一行的说明里写明需要重启引擎。
        //
        // 系统提示词那两行**不在这里**（它们在 2 提示词组）：它们既不是 pi 的 settings
        // 键，也不再是启动参数 —— 它们是 pi 自己的两个文件，改完新会话就生效
        // （`ui/PromptFileSettingsStore.kt`）。本节仍然是「引擎怎么跑」。
        // ------------------------------------------------------------------
        PiSetting(
            key = "app.runtime.offline",
            title = "离线模式",
            description = "关闭启动时的联网检查、包更新与遥测；模型照常联网。",
            kind = PiRowKind.Switch,
            group = G_RUNTIME,
            section = "进程",
            defaultValue = bool(false),
            effective = EffectiveKind.RestartEngine,
        ),
        PiSetting(
            key = "app.runtime.cacheRetention",
            title = "缓存保留策略",
            description = "向厂商申请更长提示缓存。",
            kind = PiRowKind.Value,
            group = G_RUNTIME,
            section = "进程",
            defaultValue = str("short"),
            effective = EffectiveKind.RestartEngine,
            options = choices(
                "short" to "默认",
                "long" to "长保留",
            ),
        ),
        PiSetting(
            key = "app.runtime.noContextFiles",
            title = "不加载上下文文件",
            description = "不读工作区的上下文文件。",
            kind = PiRowKind.Switch,
            group = G_RUNTIME,
            section = "进程",
            defaultValue = bool(false),
            effective = EffectiveKind.RestartEngine,
        ),
        // The four discovery suppressions. They are here (rather than beside the
        // 扩展与资源 rows they affect) because what they change is *how the engine is
        // launched*, not what a resource list contains — and because the next tap after
        // them is 「重启引擎」, which is the only way they take effect.
        PiSetting(
            key = "app.runtime.noExtensions",
            title = "停用扩展发现",
            description = "连 pi 自带扩展一起停。",
            kind = PiRowKind.Switch,
            group = G_RUNTIME,
            section = "进程",
            defaultValue = bool(false),
            effective = EffectiveKind.RestartEngine,
        ),
        PiSetting(
            key = "app.runtime.noSkills",
            title = "停用技能发现",
            description = "不发现、不加载技能。",
            kind = PiRowKind.Switch,
            group = G_RUNTIME,
            section = "进程",
            defaultValue = bool(false),
            effective = EffectiveKind.RestartEngine,
        ),
        PiSetting(
            key = "app.runtime.noPromptTemplates",
            title = "停用提示模板发现",
            description = "不发现、不加载提示模板。",
            kind = PiRowKind.Switch,
            group = G_RUNTIME,
            section = "进程",
            defaultValue = bool(false),
            effective = EffectiveKind.RestartEngine,
        ),
        PiSetting(
            key = "app.runtime.noThemes",
            title = "停用主题发现",
            description = "不发现、不加载主题。",
            kind = PiRowKind.Switch,
            group = G_RUNTIME,
            section = "进程",
            defaultValue = bool(false),
            effective = EffectiveKind.RestartEngine,
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
            description = "后台继续跑回合（前台服务保活）。",
            kind = PiRowKind.Switch,
            group = G_RUNTIME,
            section = "进程",
            defaultValue = bool(true),
            effective = EffectiveKind.RestartApp,
        ),
        PiSetting(
            key = "app.runtime.restartEngine",
            title = "重启引擎",
            description = "重启让上面的开关生效；会终止进行中的回合。",
            kind = PiRowKind.Action,
            group = G_RUNTIME,
            section = "动作",
            dangerous = true,
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
            description = "导出引擎失败、运行时与设备信息；引擎退出后也能用。",
            kind = PiRowKind.Action,
            group = G_RUNTIME,
            section = "动作",
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
            description = "中止本轮、停命令、清空队列。",
            kind = PiRowKind.Action,
            group = G_RUNTIME,
            section = "动作",
            dangerous = true,
        ),
        // ------------------------------------------------------------------
        // 11 安全与隐私
        // ------------------------------------------------------------------

        PiSetting(
            key = "defaultProjectTrust",
            title = "项目信任策略",
            description = "未决定时：询问、总是信任、或始终忽略项目资源。",
            kind = PiRowKind.Value,
            group = G_SECURITY,
            section = "信任与隐私",
            defaultValue = str("ask"),
            effective = EffectiveKind.RestartEngine,
            options = trustOptions,
        ),

        PiSetting(
            key = "enableInstallTelemetry",
            title = "安装遥测",
            description = "发送匿名安装/更新上报；关掉后两者都停。",
            kind = PiRowKind.Switch,
            group = G_SECURITY,
            section = "信任与隐私",
            effective = EffectiveKind.NewSession,
            defaultValue = bool(true),
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
        //    and this app already has the equivalent per-key editors. A second, raw JSON
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
        "cacheWarming" to "pi 读：settings-manager.ts:955-958 getCacheWarmingMode（取值 settings-manager.ts:77 的 off/streaming/idle）→ core/sdk.ts:307-312（把取值器交给 CacheWarmer，core/cache-warmer.ts:184 起每次决策读一次；settings 重读到内存的路径是 core/resource-loader.ts:403）",
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
        "images.autoResize" to "pi 读：settings-manager.ts:1289 → core/agent-session.ts:522 / :2792（附件与 read 的图片缩放）",
        "images.blockImages" to "pi 读：settings-manager.ts:1302 → core/sdk.ts:271（建会话时是否允许图片进入模型）",
        "enableInstallTelemetry" to "pi 读：settings-manager.ts:1055 → core/telemetry.ts:12（安装上报与厂商归因头）",
        // 0.99.0 新增的 codemode 设置。这一对与前后的区别在于**读取方式**：pi 没有给它们
        // 专门的 getter，内置 codemode 扩展直接读「生效设置」对象
        // （`pi.getSettings().codemode?…`），而 `getSettings()` 本身就是 0.99.2 才加的
        // （`settings-manager.ts:564`）。所以这里引的是字段定义 + 消费者，不是一个取值函数。
        "codemode.mode" to "pi 读：extensions/codemode/index.ts:23 的 pi.getSettings().codemode?.mode（字段 settings-manager.ts:105，取值 on/only 定义在 :101，:176 声明；访问器 getSettings 在 :564）—— 决定 codemode 描述里是否还直接声明 active 的 direct 工具",
        "codemode.inlineBudget" to "pi 读：extensions/codemode/index.ts:27 的 pi.getSettings().codemode?.inlineBudget（字段 settings-manager.ts:107，默认 3000）—— codemode 描述里允许花在工具声明上的估算 token 数",
    )

    /**
     * The app's 11 groups, in order, each with a live summary: spec §6.4's 12 minus
     * 「终端与 Shell」 (its five rows were deleted on purpose — the terminal itself is
     * retired, and `images.*` moved to 消息与网络), plus 提示词 — a group this app needs
     * because the two system-prompt rows are pi's own files
     * (`<agentDir>/SYSTEM.md` / `APPEND_SYSTEM.md`) and have no `settings.json` key to
     * belong to, so the spec has no slot for them. It sits second because that is where
     * a user looks for "what the model is told".
     */
    val groups: List<PiSettingsGroup> = listOf(
        PiSettingsGroup(G_MODEL, "模型与推理", Icons.Filled.Psychology) { store ->
            val model = PiSettingsCatalog.summaryText(store, "defaultModel")
            val row = byKey["defaultModel"]
            val configured = row != null && row.isExplicit(store)
            val level = PiSettingsCatalog.summaryText(store, "defaultThinkingLevel")
            if (configured) "默认 $model · ◐ $level" else "未配置模型，启动时再选"
        },
        PiSettingsGroup(G_PROMPTS, "系统提示词", Icons.Filled.Edit) { store ->
            // 摘要只报**长度**，不报开头：这两项的值是几千字的提示词，截出开头几十
            // 个字既要多一行省略号，又正是用户报的那个 bug 的表象（「只进去几十
            // 字」）。长度是这里唯一说得清、也不会被误读的读数。
            val replaced = byKey["app.prompts.system"]?.textIn(store).orEmpty()
            val appended = byKey["app.prompts.append"]?.textIn(store).orEmpty()
            // 空白等于没设，而这里读到的本来就是**文件**（`PromptFileStore`）：留空会删掉
            // 文件，文件不存在读回 null，于是一个空白值一个字符都不剩。
            val parts = buildList {
                if (replaced.isNotBlank()) add("整份替换 ${replaced.length} 字")
                if (appended.isNotBlank()) add("追加 ${appended.length} 字")
            }
            if (parts.isEmpty()) "用 pi 自带的提示词" else parts.joinToString(" · ")
        },
        PiSettingsGroup(G_MESSAGES, "消息与网络", Icons.Filled.CompareArrows) { store ->
            "引导：${summaryText(store, "steeringMode")} · 后续：${summaryText(store, "followUpMode")}"
        },
        PiSettingsGroup(G_COMPACTION, "上下文与压缩", Icons.Filled.Compress) { store ->
            val enabled = summaryText(store, "compaction.enabled")
            val reserve = summaryText(store, "compaction.reserveTokens")
            val keep = summaryText(store, "compaction.keepRecentTokens")
            "$enabled · $reserve / $keep"
        },
        PiSettingsGroup(G_RETRY, "重试", Icons.Filled.Refresh) { store ->
            val retries = summaryText(store, "retry.maxRetries")
            "${summaryText(store, "retry.enabled")} · $retries 次"
        },
        PiSettingsGroup(G_TOOLS, "工具", Icons.Filled.Build) { store ->
            val raw = store.read("defaultTools")
            if (raw == null) {
                "默认 read·bash·edit·write"
            } else {
                // 数的是 pi 的**生效集合**，不是数组长度：`["+codemode"]` 长度是 1，pi 生效
                // 5 个（默认四件套 + codemode）。pi 在同一份列表上做的就是这件事
                // （`core/settings-manager.ts:234-245`），`PiQuickAdd` 是它的转录。
                val entries = (raw as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    .orEmpty()
                val enabled = PiQuickAdd.resolveDefaultTools(entries, PiQuickAdd.builtinToolDefaults)
                if (enabled.isEmpty()) "一个都不启用" else "${enabled.size} 个已启用"
            }
        },
        PiSettingsGroup(G_SESSIONS, "会话", Icons.Filled.Folder) { store ->
            // Deliberately not derived from a `sessionDir` row: this app pins the
            // session root on pi's command line and in its environment, so there is
            // no setting to read here (see the comment above this group's rows).
            "由本应用固定（与 pi 共用）· 续接：${summaryText(store, "app.sessions.resumeLast")}"
        },
        PiSettingsGroup(G_RESOURCES, "扩展与资源", Icons.Filled.Extension) { store ->
            // 只报**真实存在**的两个数，一个都不编：
            //  - 资源包条数来自 store（`packages`），它确实在设置文件里；
            //  - 已发现的数量来自上一次真实扫描（`PiResourceFactsCache`，写入方是设置栈里那次
            //    IO 扫描）。**没有扫描过就不提这一句** —— 摘要是首页画的，那时可能还没扫过；
            //    写一个恒为 0 的"已发现 0 个"就是拿假读数冒充事实，这一批要删掉的正是这种东西。
            // 手填的"额外路径"四条已从设置页删除，所以这里不再有 `extensions` 计数。
            val packages = byKey["packages"]?.countIn(store) ?: 0
            val discovered = discoveredSummaryLine(PiResourceFactsCache.lastScan())
            if (discovered == null) "$packages 个资源包" else "$packages 个资源包 · $discovered"
        },
        PiSettingsGroup(G_APPEARANCE, "外观", Icons.Filled.ColorLens) { store ->
            val themeValue = summaryText(store, "theme")
            "主题 $themeValue"
        },
        // 「安全与隐私」：信任策略（项目资源能不能被加载/执行）与安装遥测是同一种东西 ——
        // 都是"这个应用/项目能对外做什么"的闸门。原先遥测单独一组（`G_ABOUT` 只有一行却叫
        // 「隐私与关于」），现在并入这里；摘要同时报两项，否则这一组的另一行在首页看不见。
        PiSettingsGroup(G_SECURITY, "安全与隐私", Icons.Filled.Security) { store ->
            "信任：${summaryText(store, "defaultProjectTrust")} · 遥测：${summaryText(store, "enableInstallTelemetry")}"
        },
        PiSettingsGroup(G_RUNTIME, "运行时与诊断", Icons.Filled.Memory) { store ->
            // Deliberately not the pi version. `app.runtime.piVersion` is a read-only
            // row supplied by `RuntimeFacts`, not by the store, so reading it here
            // would print the row's fallback and this one line would tell the user
            // 「pi 未安装」 while they are talking to it. The summary states only what
            // the store really holds.
            //
            // The runtime half *is* the point of DSHA's §9.1 note: the summary must
            // show the engine that is **actually in effect**, not what the switch
            // says, which is why it reads the derived status row (served by
            // `AppOnlySettingsStore` from `RuntimeSelection.status()`) rather than the
            // switch itself. A setting that says "proroot" while every launch falls
            // back to proot is exactly the lying-row shape this app keeps fixing.
            val keepAlive = summaryText(store, "app.runtime.keepAlive")
            val runtime = summaryText(store, "app.runtime.prorootStatus")
            "运行时：$runtime · 保活：$keepAlive"
        },
    )

    fun group(id: String): PiSettingsGroup? = groups.firstOrNull { it.id == id }

    fun groupTitle(id: String): String = group(id)?.title ?: id

    /** Rows of one group, in registry order. */
    fun settingsIn(groupId: String): List<PiSetting> = settings.filter { it.group == groupId }

    /** Distinct section names of a group, in the order they first appear. */
    fun sectionsIn(groupId: String): List<String> =
        settingsIn(groupId).map { it.section }.distinct()

    /** Human summary of one key, falling back to the row's default. */
    fun summaryText(store: PiSettingsStore, key: String): String {
        val setting = byKey[key] ?: return "未设置"
        return setting.display(setting.current(store))
    }
}
