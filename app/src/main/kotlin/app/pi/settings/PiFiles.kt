package app.pi.settings

import app.pi.packages.PiJsonComments
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 资源文件 / 项目配置**写盘前的 JSON 校验**：文件类型判定与 pi 会抛异常的那些坏值（「Pi 文件」浏览器已删，
 * 这里只留校验，由 `PiResourceFile.check` 与 `WorkspacePiWrite` 共用）。
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

/** 归一化：反斜杠当分隔符、去掉首尾斜杠与前导 `./`。判定与比较都走这个，免得 `./x` 与 `x` 分家。 */
internal fun normalizePiRelative(relativePath: String): String {
    var path = relativePath.replace('\\', '/').trim().trim('/')
    while (path.startsWith("./")) path = path.removePrefix("./").trimStart('/')
    return path
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
