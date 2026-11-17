package app.pi.packages

import app.pi.settings.PiFilesCheck
import app.pi.settings.checkPiFileWrite
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 资源文件（宿主 `resources.file.*`）：技能、提示模板、主题、扩展、上下文文件与系统提示。
 *
 * pi 的资源就是文件，位置是 SDK 定死的；宿主按同一张位置表判定「能不能改」（白名单，默认拒绝），
 * 这里只负责：把应答读成类型、给「写下去 pi 会怎样」的预检、以及新建时的名字规则（与 SDK 同款）。
 * 纯逻辑，裸 JVM 可测。
 */
object PiResourceFile {

    enum class Kind(val key: String, val label: String) {
        Skill("skill", "技能"),
        Prompt("prompt", "提示模板"),
        Theme("theme", "主题"),
        Extension("extension", "扩展"),
        Context("context", "上下文文件"),
        System("system", "系统提示（替换）"),
        AppendSystem("append-system", "系统提示（追加）"),
        Unknown("", "文件"),
        ;

        companion object {
            fun of(raw: String?): Kind = entries.firstOrNull { it.key == raw && raw.isNotEmpty() } ?: Unknown
        }
    }

    /** 打开编辑器时读到的状态。[size] / [mtimeMs] 是指纹：保存时带回去，宿主发现文件被别处改过就拒绝。 */
    data class Doc(
        val path: String,
        val kind: Kind,
        val scope: PiLoadedResources.Scope,
        val rel: String,
        val text: String,
        val size: Long,
        val mtimeMs: Long,
        val writable: Boolean,
        val reason: String?,
    )

    data class Stamp(val size: Long, val mtimeMs: Long)

    fun parseDoc(o: JsonObject): Doc = Doc(
        path = str(o["path"]).orEmpty(),
        kind = Kind.of(str(o["kind"])),
        scope = when (str(o["scope"])) {
            "user" -> PiLoadedResources.Scope.User
            "project" -> PiLoadedResources.Scope.Project
            else -> PiLoadedResources.Scope.Unknown
        },
        rel = str(o["rel"]).orEmpty(),
        text = str(o["text"]).orEmpty(),
        size = long(o["size"]),
        mtimeMs = long(o["mtimeMs"]),
        writable = (o["writable"] as? JsonPrimitive)?.booleanOrNull == true,
        reason = str(o["reason"]),
    )

    fun parseStamp(o: JsonObject): Stamp = Stamp(long(o["size"]), long(o["mtimeMs"]))

    // ------------------------------------------------------------------ 新建

    /** 能新建的东西。主题不在里面：必需色一二十个，从零写没有意义——改现有的。 */
    enum class Creatable(val kind: Kind, val label: String, val nameHint: String, val needsName: Boolean) {
        Skill(Kind.Skill, "技能", "小写字母、数字、连字符，如 my-skill", true),
        Prompt(Kind.Prompt, "提示模板", "文件名即命令，如 review", true),
        Extension(Kind.Extension, "扩展", "文件名，如 my-ext（写成 .ts）", true),
    }

    /** 与 SDK `validateName`（skills.js）一字不差：≤64、`[a-z0-9-]+`、不以连字符开头结尾、无连续连字符。 */
    fun skillNameError(name: String): String? = when {
        name.isEmpty() -> "给它起个名字。"
        name.length > 64 -> "技能名最长 64 个字符。"
        !name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } -> "技能名只能用小写字母、数字和连字符。"
        name.startsWith("-") || name.endsWith("-") -> "技能名不能以连字符开头或结尾。"
        name.contains("--") -> "技能名不能有连续的连字符。"
        else -> null
    }

    /** 提示模板 / 扩展的文件名（提示的文件名就是 `/命令名`，不能有空格与路径分隔符）。 */
    fun plainNameError(name: String): String? = when {
        name.isEmpty() -> "给它起个名字。"
        name.length > 64 -> "名字最长 64 个字符。"
        !Regex("^[A-Za-z0-9][A-Za-z0-9_.-]*$").matches(name) || name.endsWith(".") ->
            "名字只能用字母、数字、下划线、点和连字符，且以字母或数字开头。"
        else -> null
    }

    fun nameError(c: Creatable, name: String): String? = if (c == Creatable.Skill) skillNameError(name) else plainNameError(name)

    /** 能新建的上下文 / 系统提示文件。项目里的 `AGENTS.md` 在项目根，`SYSTEM.md` 在 `.pi` 里（宿主定位置）。 */
    val CONTEXT_FILES: List<Pair<String, String>> = listOf(
        "AGENTS.md" to "上下文文件：项目说明、约定、构建与测试命令，每次对话都带给模型",
        "SYSTEM.md" to "替换 pi 的默认系统提示（慎用）",
        "APPEND_SYSTEM.md" to "在默认系统提示末尾追加几句",
    )

    // ------------------------------------------------------------------ 预检

    /**
     * 写下去 pi 会怎样。[PiFilesCheck.blockers] 非空 = 保存不可用（会让 pi 抛异常或整份忽略）；
     * [PiFilesCheck.notices] 只是提醒。规则：
     *  - 主题 / 扩展沿用 `checkPiFileWrite`（主题必须是带 `name` 与 `colors` 的 JSON）；
     *  - `SKILL.md`：SDK 对没有 description 的技能**整个不加载**（skills.js `loadSkillFromFile`），
     *    name 不合规只是警告但照样加载。所以缺 description 给强提醒，name 不合规给提醒。
     */
    fun check(kind: Kind, rel: String, text: String): PiFilesCheck = when (kind) {
        Kind.Theme -> checkPiFileWrite("themes/$rel", text)
        Kind.Extension -> checkPiFileWrite("extensions/$rel", text)
        Kind.Skill -> if (rel.substringAfterLast('/') == "SKILL.md") checkSkill(rel, text) else PiFilesCheck.Ok
        Kind.Prompt, Kind.Context, Kind.System, Kind.AppendSystem, Kind.Unknown -> PiFilesCheck.Ok
    }

    private fun checkSkill(rel: String, text: String): PiFilesCheck {
        val fm = frontmatter(text)
        val notices = mutableListOf<String>()
        val description = fm["description"]
        if (description.isNullOrBlank()) {
            notices += "缺少 description：pi 不会加载没有 description 的技能（模型靠这句话决定要不要读它）。"
        } else if (description.length > 1024) {
            notices += "description 有 ${description.length} 字，超过 1024，pi 会给警告。"
        }
        val name = fm["name"] ?: rel.substringBeforeLast('/', "").substringAfterLast('/')
        if (name.isNotEmpty()) skillNameError(name)?.let { notices += "name「$name」：$it" }
        return PiFilesCheck(notices = notices)
    }

    /** 够用的 frontmatter 读法：首行 `---` 到下一个 `---` 之间的 `key: value`（值去引号）。 */
    fun frontmatter(text: String): Map<String, String> {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "---") return emptyMap()
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return emptyMap()
        return lines.subList(1, end + 1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0 || line.first().isWhitespace()) return@mapNotNull null
            line.substring(0, i).trim() to line.substring(i + 1).trim().removeSurrounding("\"").removeSurrounding("'")
        }.toMap()
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun long(e: JsonElement?): Long = (e as? JsonPrimitive)?.longOrNull ?: 0L
}
