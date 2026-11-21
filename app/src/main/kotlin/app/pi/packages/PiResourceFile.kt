package app.pi.packages

import app.pi.i18n.tr
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

    enum class Kind(val key: String, private val zhLabel: String, private val enLabel: String) {
        Skill("skill", "技能", "Skill"),
        Prompt("prompt", "提示模板", "Prompt Template"),
        Theme("theme", "主题", "Theme"),
        Extension("extension", "扩展", "Extension"),
        Context("context", "上下文文件", "Context File"),
        System("system", "系统提示（替换）", "System Prompt (Override)"),
        AppendSystem("append-system", "系统提示（追加）", "System Prompt (Append)"),
        Unknown("", "文件", "File"),
        ;

        val label: String get() = tr(zhLabel, enLabel)

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
    enum class Creatable(
        val kind: Kind,
        private val zhLabel: String,
        private val enLabel: String,
        private val zhHint: String,
        private val enHint: String,
        val needsName: Boolean,
    ) {
        Skill(Kind.Skill, "技能", "Skill", "小写字母、数字、连字符，如 my-skill", "Lowercase letters, digits, hyphens (e.g. my-skill)", true),
        Prompt(Kind.Prompt, "提示模板", "Prompt Template", "文件名即命令，如 review", "Filename becomes /command (e.g. review)", true),
        Extension(Kind.Extension, "扩展", "Extension", "文件名，如 my-ext（写成 .ts）", "Filename (e.g. my-ext, saved as .ts)", true),
        ;

        val label: String get() = tr(zhLabel, enLabel)
        val nameHint: String get() = tr(zhHint, enHint)
    }

    /** 与 SDK `validateName`（skills.js）一字不差：≤64、`[a-z0-9-]+`、不以连字符开头结尾、无连续连字符。 */
    fun skillNameError(name: String): String? = when {
        name.isEmpty() -> tr("给它起个名字。", "Enter a name.")
        name.length > 64 -> tr("技能名最长 64 个字符。", "Skill name must be at most 64 characters.")
        !name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } -> tr("技能名只能用小写字母、数字和连字符。", "Skill name may only contain lowercase letters, digits, and hyphens.")
        name.startsWith("-") || name.endsWith("-") -> tr("技能名不能以连字符开头或结尾。", "Skill name cannot start or end with a hyphen.")
        name.contains("--") -> tr("技能名不能有连续的连字符。", "Skill name cannot contain consecutive hyphens.")
        else -> null
    }

    /** 提示模板 / 扩展的文件名（提示的文件名就是 `/命令名`，不能有空格与路径分隔符）。 */
    fun plainNameError(name: String): String? = when {
        name.isEmpty() -> tr("给它起个名字。", "Enter a name.")
        name.length > 64 -> tr("名字最长 64 个字符。", "Name must be at most 64 characters.")
        !Regex("^[A-Za-z0-9][A-Za-z0-9_.-]*$").matches(name) || name.endsWith(".") ->
            tr("名字只能用字母、数字、下划线、点和连字符，且以字母或数字开头。", "Name may only contain letters, digits, underscores, dots, and hyphens, starting with a letter or digit.")
        else -> null
    }

    fun nameError(c: Creatable, name: String): String? = if (c == Creatable.Skill) skillNameError(name) else plainNameError(name)

    /** 能新建的上下文 / 系统提示文件。项目里的 `AGENTS.md` 在项目根，`SYSTEM.md` 在 `.pi` 里（宿主定位置）。 */
    val CONTEXT_FILES: List<Pair<String, String>>
        get() = listOf(
            "AGENTS.md" to tr("上下文文件：项目说明、约定、构建与测试命令，每次对话都带给模型", "Context file: project overview, conventions, build/test commands sent on every turn"),
            "CLAUDE.md" to tr("兼容 Claude Code 的项目说明文件，与 AGENTS.md 同级加载", "Claude Code compatible context file, loaded alongside AGENTS.md"),
            "APPEND_SYSTEM.md" to tr("推荐：在默认系统提示末尾安全追加指令，不破坏工具说明", "Recommended: safely append instructions after pi's default system prompt"),
            "SYSTEM.md" to tr("高级：整份替换 pi 的默认系统提示（会覆盖工具清单与默认规则）", "Advanced: replaces pi's entire default system prompt (overrides tool list & rules)"),
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
            notices += tr(
                "缺少 description：pi 不会加载没有 description 的技能（模型靠这句话决定要不要读它）。",
                "Missing description: pi will not load a skill without a description (the model uses it to decide when to read it).",
            )
        } else if (description.length > 1024) {
            notices += tr(
                "description 有 ${description.length} 字，超过 1024，pi 会给警告。",
                "description is ${description.length} chars (over 1024); pi will emit a warning.",
            )
        }
        val name = fm["name"] ?: rel.substringBeforeLast('/', "").substringAfterLast('/')
        if (name.isNotEmpty()) skillNameError(name)?.let { notices += tr("name「$name」：$it", "name \"$name\": $it") }
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
