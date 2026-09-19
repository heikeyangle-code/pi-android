package app.pi.settings

import app.pi.packages.PiJsonComments
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 「Pi 文件」屏的纯逻辑：两个根、可写档的判定、文件类型、以及写盘前的 JSON 校验。
 *
 * ## 为什么是这一套规则，而不是「除了凭据都能改」
 *
 * `docs/settings-audit-pi-gap.md` §6.1 把 pi 的每一个文件按「格式 / 权限 / pi 是否加锁 /
 * **pi 运行期会不会重写**」盘过一遍，结论就是这里的白名单：**只有 pi 会读、且 pi 自己
 * 不会重写的「文档类」文件才可写**，其余一律只读。默认是**拒绝**（不在白名单里的路径
 * 全部只读），不是允许 —— 一个未知文件名不该因为没人想到它而变成可写。
 *
 * 三个典型：
 *
 *  - `auth.json` —— pi 每次凭据变更整份重写，且按 `0600` 创建（`core/auth-storage.ts:106`、
 *    `:187`、`:24-25`）。手改会丢掉 revision 校验那一层语义，所以要改去「API Key」。
 *  - `sessions/` 里的会话文件 —— pi 在 `message_end` 持续 append，压缩或分叉时整份重写
 *    （`core/session-manager.ts:1035`、`:1054`、`:1652-1657`）。手改会被覆盖，还会让它
 *    自己的索引与 `-c` 的 mtime 排序读错。
 *  - `models-store.json` —— pi 的动态模型缓存，`refresh()` 会重写
 *    （`core/models-store.ts:132`、`:143`）。
 *
 * ## 为什么写盘前的校验是纯逻辑
 *
 * 因为 pi 对这几个键的坏值是**抛异常**，不是「回退到默认值」，而两个抛点分别在
 * 进程启动与每次请求上：
 *
 *  - `httpIdleTimeoutMs` / `websocketConnectTimeoutMs`：`null` 会抛
 *    （`core/settings-manager.ts:188-197` 的 `parseTimeoutSetting`：`value !== undefined`
 *    对 `null` 成立），而这个 getter 在 `main.ts:851`（启动）与 `core/sdk.ts:316`
 *    （每次请求）都被调 —— 写坏一个值，引擎起不来。
 *  - `compaction.reserveTokens` / `keepRecentTokens`：`null` 或非「非负安全整数」会抛
 *    （`:860-864`），压缩判定每个回合都跑。
 *  - `compaction.modelOverrides[model]`：不是对象会抛（`:868-872`）。
 *
 * 所以校验分两档：[PiFilesCheck.blockers] 是「写下去 pi 会抛 / 会整份忽略这个文件」，
 * 界面必须**拦住保存**；[PiFilesCheck.notices] 只是提示（例如 `models.json` 里有注释，
 * pi 会剥掉再读，`core/model-config.ts:267`）。
 *
 * Android-free：只有 `java.io.File`、kotlinx.serialization 与 stdlib，所以
 * `app/src/test/kotlin/app/pi/settings/PiFilesCheck.kt` 能在裸 JVM 上跑。
 */

/** pi 的两个根。`agent` 是 pi 真正读的那一份（`PI_CODING_AGENT_DIR`）。 */
enum class PiFilesRootKind {
    /** `getAgentDir()` —— 宿主是 `PiPaths.agentDir`，guest 里挂在 `/root/.pi/agent`。 */
    AgentDir,

    /** `getAgentDir()` 旁边的项目目录：`<cwd>/.pi`（`PiProjectConfig.root`）。 */
    ProjectPi,
}

/**
 * 一个根，连同它给用户看的名字。
 *
 * `label` 只说人话，不带路径：屏幕上那行字不该出现 `~/.pi/agent` 这种机器拼法（与
 * 设置页的文案规则一致）。真正的位置在副行里以「相对这个根」的形式出现。
 */
data class PiFilesRoot(
    val kind: PiFilesRootKind,
    val label: String,
    val file: File,
) {
    val exists: Boolean get() = file.isDirectory
}

/**
 * 两个根，顺序固定。
 *
 * [agentDir] 与 [projectPi] 由调用方解析：宿主 `PiPaths.agentDir` 与
 * `PiProjectConfig.root(workspace)` —— 本文件**不**自己拼这两个路径，因为它们是这个仓库
 * 各自唯一的转录点（`app/src/test/kotlin/app/pi/runtime/AgentToolPathsCheck.kt` 钉着它们）。
 */
fun piFilesRoots(agentDir: File, projectPi: File): List<PiFilesRoot> = listOf(
    PiFilesRoot(PiFilesRootKind.AgentDir, "agent 目录", agentDir),
    PiFilesRoot(PiFilesRootKind.ProjectPi, "项目 .pi", projectPi),
)

/** 这一行/这个文件能不能改。判定见文件头。 */
enum class PiFilesAccess { Writable, ReadOnly }

/**
 * 可写的**根级文件名**：pi 会读、且 pi 自己从不重写的文档。
 *
 * 上下文的五个候选名是 pi 的发现顺序（`core/resource-loader.ts:72`：
 * `AGENTS.override.md`、`AGENTS.md`、`AGENTS.MD`、`CLAUDE.md`、`CLAUDE.MD`）；
 * `SYSTEM.md` / `APPEND_SYSTEM.md` 是系统提示的两份文件形态（`:1024`、`:1038`）。
 */
private val WRITABLE_ROOT_FILES: Set<String> = setOf(
    "settings.json",
    "models.json",
    "AGENTS.override.md",
    "AGENTS.md",
    "AGENTS.MD",
    "CLAUDE.md",
    "CLAUDE.MD",
    "SYSTEM.md",
    "APPEND_SYSTEM.md",
)

/** 可写的**目录**（根下一层）：`extensions`/`skills`/`prompts`/`themes`，见 pi 的默认扫描目录。 */
private val WRITABLE_DIRS: Set<String> = setOf("extensions", "skills", "prompts", "themes")

/** 归一化：反斜杠当分隔符、去掉首尾斜杠与前导 `./`。判定与比较都走这个，免得 `./x` 与 `x` 分家。 */
internal fun normalizePiRelative(relativePath: String): String {
    var path = relativePath.replace('\\', '/').trim().trim('/')
    while (path.startsWith("./")) path = path.removePrefix("./").trimStart('/')
    return path
}

/**
 * `proper-lockfile` 的锁是**目录** `<target>.lock`（`core/settings-manager.ts:243`），
 * 不是内容。它随时出现/消失，所以既不该被当文件打开，也不该被写。
 */
fun isPiLockArtifact(name: String): Boolean = name.endsWith(".lock")

/**
 * 路径 → 可写档。**默认只读**。
 *
 * @param relativePath 相对某个根的路径，`/` 分隔。带 `..` 的一律只读（它不是这个根里的
 *   东西，本屏也不该有机会写到外面去）。
 */
fun piFilesAccessFor(relativePath: String): PiFilesAccess {
    val path = normalizePiRelative(relativePath)
    if (path.isEmpty()) return PiFilesAccess.ReadOnly
    val segments = path.split('/')
    if (segments.any { it == ".." || it == "." || it.isEmpty() }) return PiFilesAccess.ReadOnly
    val name = segments.last()
    if (isPiLockArtifact(name)) return PiFilesAccess.ReadOnly
    if (segments.size == 1) {
        return if (name in WRITABLE_ROOT_FILES) PiFilesAccess.Writable else PiFilesAccess.ReadOnly
    }
    val head = segments.first()
    if (head !in WRITABLE_DIRS) return PiFilesAccess.ReadOnly
    // 只有 `.json` 是 pi 会加载的主题（`core/resource-loader.ts` 只收 `*.json`）；往主题
    // 目录里塞别的后缀，pi 不会读它，所以也不该由这一屏来写。
    if (head == "themes" && !name.endsWith(".json")) return PiFilesAccess.ReadOnly
    return PiFilesAccess.Writable
}

/**
 * 目录能不能改。只有白名单里的那四个根级目录算可写，其余（`sessions`/`npm`/`bin`/`tools`…）
 * 连同它们的子目录一律只读。
 *
 * 单独一个函数而不是对目录调 [piFilesAccessFor]：目录没有文件名，用文件的那条规则会得到
 * 一个「因为它是目录所以不可写」的假答案（例如 `themes` 自己不带 `.json` 后缀）。
 */
fun piFilesDirIsWritable(relativePath: String): Boolean {
    val path = normalizePiRelative(relativePath)
    if (path.isEmpty()) return false
    val segments = path.split('/')
    if (segments.any { it == ".." || it == "." || it.isEmpty() }) return false
    return segments.first() in WRITABLE_DIRS
}

/**
 * 只读时给用户的一句原因。分类与 `docs/settings-audit-pi-gap.md` §6.3 的「绝不手改」
 * 表一一对应；没被点名的路径落到最后那句通用解释。
 */
fun piFilesReadOnlyReason(relativePath: String): String {
    val path = normalizePiRelative(relativePath)
    val name = path.substringAfterLast('/')
    if (isPiLockArtifact(name)) {
        return "这是 pi 的锁目录，不是内容：pi 或本应用正在写同一个文件时它会短暂存在，写完就删。"
    }
    val head = path.substringBefore('/', "")
    return when {
        name == "auth.json" ->
            "这是凭据文件：pi 每次改 Key 都会整份重写它，而且按只有本人可读的方式创建。" +
                "要改请回上一屏用「API Key」。"
        name == "models-store.json" ->
            "这是 pi 自己写的模型缓存，刷新时整份重写，手改没有意义。"
        name == "trust.json" ->
            "项目信任决定的唯一真相是本应用的项目页与 pi 自己的信任流程；手改这里会和它们矛盾。"
        name == "keybindings.json" ->
            "这一份只影响在终端里运行的原版 pi TUI，不影响本应用的界面，本轮没有把它放进可写白名单。"
        head == "sessions" ->
            "会话文件是 pi 边跑边追加的（压缩或分叉时整份重写），手改会被覆盖，也会让会话列表读错。"
        head == "npm" ->
            "这是 pi 安装的包，安装与更新会覆盖它；要增删请用「资源包」。"
        head == "bin" ->
            "这是 pi 自己下载并覆盖的二进制（rg、fd）。"
        else ->
            "只读：可写白名单只包含 pi 会读、而 pi 自己不会重写的文档 —— " +
                "settings.json、models.json、AGENTS.md、SYSTEM.md、APPEND_SYSTEM.md、" +
                "以及 themes/、skills/、prompts/、extensions/ 里的文件。"
    }
}

/** 这个文件按 pi 的读法属于哪一类 —— 决定编辑器怎么校验、以及写前的提示。 */
enum class PiFilesDoc {
    /** `<root>/settings.json`：pi 的全局/项目设置文档。 */
    Settings,

    /** `<root>/models.json`：厂商与模型定义。 */
    Models,

    /** `themes/` 下的 `.json`：主题。 */
    Theme,

    /** `.md`：上下文文件与系统提示。 */
    Markdown,

    /** `skills/` 下的文件。 */
    Skill,

    /** `prompts/` 下的文件。 */
    Prompt,

    /** `extensions/` 下的文件。 */
    Extension,

    /** 其它 `.json`。 */
    Json,

    /** 其它文本。 */
    Text,
}

/** 路径 → 文档类别。目录判定在 `.md` 之前：`skills/x/SKILL.md` 是技能，不是普通 markdown。 */
fun piFilesDocFor(relativePath: String): PiFilesDoc {
    val path = normalizePiRelative(relativePath)
    val segments = path.split('/').filter { it.isNotEmpty() }
    val name = segments.lastOrNull().orEmpty()
    val head = segments.firstOrNull().orEmpty()
    return when {
        name == "settings.json" -> PiFilesDoc.Settings
        name == "models.json" -> PiFilesDoc.Models
        head == "themes" && name.endsWith(".json") -> PiFilesDoc.Theme
        head == "skills" -> PiFilesDoc.Skill
        head == "prompts" -> PiFilesDoc.Prompt
        head == "extensions" -> PiFilesDoc.Extension
        name.endsWith(".md") -> PiFilesDoc.Markdown
        name.endsWith(".json") -> PiFilesDoc.Json
        else -> PiFilesDoc.Text
    }
}

/**
 * 写盘前的判定结果。
 *
 * [blockers] 非空时界面必须拒绝保存：这些是 pi 会抛异常或会整份忽略文件的情况，
 * 写下去等于把一个能用的配置变成不能用的。[notices] 只是提示，保存照旧。
 */
data class PiFilesCheck(
    val blockers: List<String> = emptyList(),
    val notices: List<String> = emptyList(),
) {
    val ok: Boolean get() = blockers.isEmpty()

    companion object {
        val Ok = PiFilesCheck()
    }
}

// pi 读 settings.json 用严格的 `JSON.parse`（`core/settings-manager.ts:419`），
// models.json 是「先剥注释再严格解析」（`core/model-config.ts:267`）。两个选项都是
// `Json` 的默认值，这里只写出来当文档：闸门在严格这一侧，不在宽松那一侧。
private val strictJson = Json

/**
 * 写盘前的校验。`relativePath` 决定规则（[piFilesDocFor]），`text` 是用户正要写下去的内容。
 */
fun checkPiFileWrite(relativePath: String, text: String): PiFilesCheck = when (piFilesDocFor(relativePath)) {
    PiFilesDoc.Settings -> checkSettings(text)
    PiFilesDoc.Models -> checkModels(text)
    PiFilesDoc.Theme -> checkTheme(text)
    PiFilesDoc.Json -> checkPlainJson(text)
    PiFilesDoc.Extension -> PiFilesCheck(
        notices = listOf(
            "扩展是代码：写错了 pi 会在加载时报错，本应用不做语法校验，也不会拦你保存。",
        ),
    )
    PiFilesDoc.Markdown, PiFilesDoc.Skill, PiFilesDoc.Prompt, PiFilesDoc.Text -> PiFilesCheck.Ok
}

// ------------------------------------------------------------------ settings.json

/** pi 用 `JSON.parse`（严格）读它，没有注释、没有尾逗号（`core/settings-manager.ts:419`）。 */
private fun checkSettings(text: String): PiFilesCheck {
    val parsed = runCatching { strictJson.parseToJsonElement(text) }.getOrNull()
        ?: return PiFilesCheck(
            blockers = listOf("这不是合法的 JSON（pi 用严格 JSON 读它，注释和尾逗号都不允许）。"),
        )
    val root = parsed as? JsonObject
        ?: return PiFilesCheck(blockers = listOf("设置文档的顶层必须是一个 JSON 对象。"))

    val blockers = mutableListOf<String>()
    val nullHint = "pi 对 `null` 会直接抛异常，不是「用默认值」。要恢复默认就把这个键删掉。"

    for (key in listOf("httpIdleTimeoutMs", "websocketConnectTimeoutMs")) {
        val value = root[key] ?: continue
        if (value is JsonNull) {
            blockers += "$key 是 null。$nullHint"
            continue
        }
        val primitive = value as? JsonPrimitive
        if (primitive == null) {
            blockers += "$key 必须是数字（毫秒），或字符串 \"disabled\" 表示不超时。"
            continue
        }
        if (primitive.isString) {
            if (primitive.content != "disabled") {
                blockers += "$key 的字符串取值只有 \"disabled\"（= 不超时）一种；其它字符串 pi 会抛异常。"
            }
            continue
        }
        val number = primitive.content.toLongOrNull()
        if (number == null || number < 0 || primitive.content.contains('.') || primitive.content.contains('e')) {
            blockers += "$key 必须是非负整数毫秒（pi 会向下取整；负数或非法值会抛异常）。"
        }
    }

    val compaction = root["compaction"] ?: return PiFilesCheck(blockers)
    if (compaction is JsonNull) {
        blockers += "compaction 是 null。$nullHint"
        return PiFilesCheck(blockers)
    }
    val compactionObject = compaction as? JsonObject
        ?: return PiFilesCheck(blockers + "compaction 必须是一个 JSON 对象。")

    for (key in listOf("reserveTokens", "keepRecentTokens")) {
        val value = compactionObject[key] ?: continue
        val problem = nonNegativeIntegerProblem(value, "compaction.$key")
        if (problem != null) blockers += problem
    }

    val overrides = compactionObject["modelOverrides"] ?: return PiFilesCheck(blockers)
    if (overrides is JsonNull) {
        blockers += "compaction.modelOverrides 是 null。$nullHint"
        return PiFilesCheck(blockers)
    }
    val overridesObject = overrides as? JsonObject
        ?: return PiFilesCheck(blockers + "compaction.modelOverrides 必须是一个对象（键是 \"厂商/模型 ID\"）。")
    for ((model, entry) in overridesObject) {
        if (entry is JsonNull || entry !is JsonObject) {
            blockers += "compaction.modelOverrides[\"$model\"] 必须是一个对象；pi 读到这里不是对象就抛异常。"
            continue
        }
        for (key in listOf("reserveTokens", "keepRecentTokens")) {
            val value = entry[key] ?: continue
            val problem = nonNegativeIntegerProblem(value, "compaction.modelOverrides[\"$model\"].$key")
            if (problem != null) blockers += problem
        }
    }
    return PiFilesCheck(blockers)
}

/** `undefined`（键不存在）才好；`null` 与其它类型都会让 pi 的 getter 抛（`:860-877`）。 */
private fun nonNegativeIntegerProblem(value: JsonElement, label: String): String? = when {
    value is JsonNull -> "$label 是 null。pi 对 `null` 会直接抛异常，不是「用默认值」；要恢复默认就删掉这个键。"
    value !is JsonPrimitive || value.isString -> "$label 必须是非负整数。"
    value.content.contains('.') || value.content.contains('e') -> "$label 必须是非负整数（不能有小数或指数）。"
    (value.content.toLongOrNull() ?: -1L) < 0L -> "$label 不能是负数。"
    else -> null
}

// ------------------------------------------------------------------ models.json

/**
 * pi 读它时**先剥注释**再 `JSON.parse`（`core/model-config.ts:267`），然后按
 * `ModelsConfigSchema` 校验（`:199-215`）：顶层必须有 `providers`，每个厂商的
 * `models[]` 每项必须有非空的 `id`，`oauth` 只能是字面量 `"radius"`。整份文档任何一处
 * 不合 schema，pi 就报 `Invalid models.json schema` 并**整份忽略**（`:275-282`）。
 */
private fun checkModels(text: String): PiFilesCheck {
    val notices = mutableListOf<String>()
    val stripped = PiJsonComments.strip(text)
    if (stripped != text) {
        notices += "文件里有注释：pi 会先剥掉注释再读（本应用也一样），注释不会生效，但也不会被丢掉。"
    }
    val parsed = runCatching { strictJson.parseToJsonElement(stripped) }.getOrNull()
        ?: return PiFilesCheck(
            blockers = listOf("剥掉注释之后这不是合法的 JSON，pi 会整份忽略这个文件。"),
            notices = notices,
        )
    val root = parsed as? JsonObject
        ?: return PiFilesCheck(
            blockers = listOf("顶层必须是一个 JSON 对象（`{ \"providers\": { … } }`）。"),
            notices = notices,
        )
    val providersElement = root["providers"]
        ?: return PiFilesCheck(
            blockers = listOf("缺少 `providers`：pi 的 schema 要求它是必填项，缺了会整份忽略这个文件。"),
            notices = notices,
        )
    val providers = providersElement as? JsonObject
        ?: return PiFilesCheck(
            blockers = listOf("`providers` 必须是一个对象（键是厂商 id）。"),
            notices = notices,
        )

    val blockers = mutableListOf<String>()
    for ((providerId, providerElement) in providers) {
        val provider = providerElement as? JsonObject
        if (provider == null) {
            blockers += "厂商 `$providerId` 必须是一个对象。"
            continue
        }
        provider["oauth"]?.let { oauth ->
            val literal = (oauth as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (literal != "radius") {
                blockers += "厂商 `$providerId` 的 `oauth` 在 models.json 里只能是字符串 \"radius\"；" +
                    "其它取值会让 pi 整份忽略这个文件。"
            }
        }
        val models = provider["models"]
        if (models != null) {
            val array = models as? JsonArray
            if (array == null) {
                blockers += "厂商 `$providerId` 的 `models` 必须是一个数组。"
            } else {
                array.forEachIndexed { index, entry ->
                    val model = entry as? JsonObject
                    if (model == null) {
                        blockers += "厂商 `$providerId` 的 models[$index] 必须是一个对象。"
                        return@forEachIndexed
                    }
                    val id = (model["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (id.isNullOrEmpty()) {
                        blockers += "厂商 `$providerId` 的 models[$index] 缺少非空的字符串 `id`。" +
                            "`id` 是模型定义里唯一必填的字段。"
                    }
                }
            }
        }
        val overrides = provider["modelOverrides"]
        if (overrides != null) {
            val overridesObject = overrides as? JsonObject
            if (overridesObject == null) {
                blockers += "厂商 `$providerId` 的 `modelOverrides` 必须是一个对象。"
            } else {
                for ((modelId, entry) in overridesObject) {
                    if (entry !is JsonObject) {
                        blockers += "厂商 `$providerId` 的 modelOverrides[\"$modelId\"] 必须是一个对象。"
                    }
                }
            }
        }
    }
    return PiFilesCheck(blockers, notices)
}

// ------------------------------------------------------------------ themes 下的 json

/**
 * 主题的硬规则，都在 pi 的 schema 与校验里：
 *
 *  - `name` 是**必填**的非空字符串，且不能含 `/`（`modes/interactive/theme/theme-json.ts:20`、
 *    `:140-143`；`/` 是自动浅/深色配对的保留字符）。
 *  - `colors` 必需且非空（`:22`）。
 *  - pi 还会要求 `colors` 里的**每一个必需色都不缺**，缺一个就整份拒绝并列出缺的名字
 *    （`validateThemeJson`，`:103-137`）。这一条本应用**不复制颜色清单** —— 那份清单属于
 *    pi 的 schema，抄一份就是第二个会漂移的真相；缺色的具体名字由 pi 自己报，本应用在
 *    主题选择器里也另有说明（`PiThemeFiles` 的 notes）。
 */
private fun checkTheme(text: String): PiFilesCheck {
    val blockers = mutableListOf<String>()
    val parsed = runCatching { strictJson.parseToJsonElement(text) }.getOrNull()
        ?: return PiFilesCheck(blockers + "这不是合法的 JSON。")
    val root = parsed as? JsonObject
        ?: return PiFilesCheck(blockers + "主题文件的顶层必须是一个对象。")

    val name = (root["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (name.isNullOrEmpty()) {
        blockers += "主题文件必须有一个非空的字符串 `name` —— pi 的主题 schema 把它列为必填。"
    } else if (name.contains('/')) {
        blockers += "主题名不能含 `/`：那是自动浅色/深色配对的保留字符，pi 会直接拒绝这个主题。"
    }

    val colors = root["colors"]
    if (colors !is JsonObject || colors.isEmpty()) {
        blockers += "主题文件必须有一个非空的 `colors` 对象 —— pi 靠它判断这是不是一个主题。"
    }
    val notices = if (blockers.isEmpty()) {
        listOf("本应用只检查 `name` 与 `colors`；pi 还会要求 `colors` 里的必需色一个不缺，缺了它会拒绝这个主题并列出缺的名字。")
    } else {
        emptyList()
    }
    return PiFilesCheck(blockers, notices)
}

/**
 * 其它 `.json`：只要求能解析，且顶层是对象或数组。
 *
 * 「顶层是对象或数组」不是洁癖：`Json.parseToJsonElement` 会把一个**没有引号的裸词**
 * 当成字符串收下（`nope` → `JsonPrimitive("nope")`），而 pi 用的 `JSON.parse("nope")`
 * 是抛的。只看「解析成功」会给出一个与 pi 不同的答案，所以这里把标量顶层一并挡掉 ——
 * pi 相关的几份 `.json`（settings / models / 主题）顶层本来也都是对象。
 */
private fun checkPlainJson(text: String): PiFilesCheck {
    val parsed = runCatching { strictJson.parseToJsonElement(text) }.getOrNull()
        ?: return PiFilesCheck(blockers = listOf("这不是合法的 JSON。"))
    if (parsed !is JsonObject && parsed !is JsonArray) {
        return PiFilesCheck(blockers = listOf("顶层必须是一个 JSON 对象或数组。"))
    }
    return PiFilesCheck.Ok
}

// ------------------------------------------------------------------ 路径

/**
 * [file] 相对 [root] 的路径，`/` 分隔；不在 [root] 里就是 null。
 *
 * 用逐段比较而不是 `startsWith`：`/a/bc` 不该被当成 `/a/b` 的子路径，而
 * `File(workspace, "../outside")` 这种拼法必须在**这一层**就被挡住（`PiProjectConfig.under`
 * 的 KDoc 说的是同一件事）。
 */
fun piFilesRelativePath(root: File, file: File): String? {
    val rootPath = root.absoluteFile.normalize().path
    val filePath = file.absoluteFile.normalize().path
    if (filePath == rootPath) return ""
    val prefix = if (rootPath.endsWith(File.separator)) rootPath else rootPath + File.separator
    if (!filePath.startsWith(prefix)) return null
    return filePath.removePrefix(prefix).replace(File.separatorChar, '/')
}

/** 面包屑：根的名字 + 当前相对路径。空相对路径就是根自己。 */
fun piFilesBreadcrumb(rootLabel: String, relativePath: String): String {
    val path = normalizePiRelative(relativePath)
    return if (path.isEmpty()) rootLabel else "$rootLabel / $path"
}

/** 父目录的相对路径；根下面一层返回空串。 */
fun piFilesParent(relativePath: String): String {
    val path = normalizePiRelative(relativePath)
    val index = path.lastIndexOf('/')
    return if (index <= 0) "" else path.substring(0, index)
}

/** 子目录的相对路径。 */
fun piFilesChild(relativePath: String, name: String): String {
    val path = normalizePiRelative(relativePath)
    return if (path.isEmpty()) name else "$path/$name"
}
