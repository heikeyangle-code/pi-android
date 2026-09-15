package app.pi.ui.screens

import app.pi.packages.PiAutoExtensions
import app.pi.packages.PiPackageFilters
import app.pi.packages.PiPackageSource
import app.pi.packages.PiResourceDiscovery
import java.io.File
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 「这个目录的资源」那一段（design 稿 ⑤）的数据面：**四类 × 五种来源 × 四种状态**。
 *
 * ## 规则不是这里定的
 *
 * 每一个目录的收集规则都来自 pi，而本仓库已经把那些规则转录过一遍：
 *
 * | 类 | 规则出处 | 本仓库的转录 |
 * |---|---|---|
 * | 技能 | `<dir>/<name>/SKILL.md`，一层深（`package-manager.ts:365-395`） | `PiResourceDiscovery.skillNames` |
 * | 提示词 / 主题 | 递归收集 `\.md$` / `\.json$`（`FILE_PATTERNS`，`:206-211`） | `PiResourceDiscovery.fileNames` |
 * | 扩展 | `collectAutoExtensionEntries`（`:587-639`） | `PiAutoExtensions` |
 * | 包的资源根 | `<root>/npm/node_modules/<name>`（`:1307`、`:2066-2072`） | `PiResourceDiscovery.packageRoots` |
 *
 * 所以这里**不另造一套规则**：四个目录的收集全部转调那两个读取器，本文件只做 pi 没有
 * 替我们做的那几件事 —— 把来源族标出来、按 pi 的优先级排序、把同名冲突与解析失败判出来。
 *
 * ## 五种来源
 *
 * `项目 .pi` → `.agents` → `全局` → `包 · <名>` → `扩展贡献`，与 pi 的资源加载顺序一致
 * （`interactive-mode.ts:3877`、`:3888-3907`）。前三种是 pi 真正会去走的目录：
 *
 *  - 项目 `.pi`：`<workspace>/.pi`（`AgentLayout.hostProjectConfigDir()`）；
 *  - `.agents`：`<workspace>/.agents`（pi 的 `trust-manager.ts:186-188` 认这个约定目录）；
 *  - 全局：agent dir（`AgentLayout.agentMirrorDir`，engine 把它 bind 到 guest
 *    `/root/.pi/agent`）。
 *
 * 第 4 种来自 `settings.json` 的 `packages` 数组（`package-manager.ts:977-1003`），第 5 种
 * 是**运行中的扩展自己写出去的**文件 —— 见 [EXTENSION_CONTRIBUTED_SKILLS] 的注释：这条路
 * 上只有扩展的产物能被指认，别的都属于它落地的那个目录。
 *
 * ## 为什么不用 `pi list`
 *
 * `pi list` 要么起一个 guest 命令（这一屏的设计前提就是「引擎没起来也能看」），要么在
 * 引擎没起来时什么都拿不到。`settings.json` 的 `packages` 数组是同一个事实的**磁盘**形态，
 * 读它不需要任何进程。
 */
internal enum class WorkspaceResourceKind(val label: String) {
    Skill("技能"),
    Prompt("提示词"),
    Extension("扩展"),
    Theme("主题"),
    ;

    companion object {
        /** design 稿的分段按钮顺序：技能 / 提示词 / 扩展 / 主题。 */
        val order: List<WorkspaceResourceKind> = listOf(Skill, Prompt, Extension, Theme)
    }
}

/** 来源族。顺序就是 pi 的优先级。 */
internal enum class WorkspaceSource(val rank: Int) {
    /** `<workspace>/.pi` —— 优先级最高，同名时它赢。 */
    ProjectPi(0),

    /** `<workspace>/.agents` —— 跨工具约定目录。 */
    Agents(1),

    /** agent dir（guest `/root/.pi/agent`）。 */
    Global(2),

    /**
     * 「扩展贡献」：运行中的扩展自己写出去的那一份。
     *
     * 排在 [Global] 之后：本批能指认的唯一一条扩展产物落在全局 skills 里，所以它实际的
     * 位置就是紧接着全局。放在这里而不是最后，是因为稿子写的是「跟在它实际的来源之后」。
     */
    Extension(3),

    /** 包自己的目录；排最后。 */
    Package(4),
}

/** design 稿的四种状态。正常什么都不加。 */
internal enum class WorkspaceResourceStatus {
    Normal,

    /** 同名冲突（warning）。 */
    Conflict,

    /** 解析失败（error）。 */
    ParseFail,

    /** 被禁用（muted）—— 禁用不是失败。 */
    Disabled,
}

/** 一条资源。 */
internal data class WorkspaceResource(
    val kind: WorkspaceResourceKind,
    val name: String,
    val source: WorkspaceSource,
    val packageName: String?,
    /** 副行（front matter 的 `description`，或扩展的版本）。取不到就是 null。 */
    val description: String?,
    /** 给人看的路径（`.pi/skills/review/SKILL.md` / `~/.pi/agent/skills/…`）。 */
    val displayPath: String,
    /** 磁盘上的真身；查看器打开的就是它。 */
    val file: File,
    val status: WorkspaceResourceStatus = WorkspaceResourceStatus.Normal,
    /** 冲突时撞上的是谁（对方来源的短标签）。 */
    val conflictWith: String? = null,
    /** 解析失败 / 被禁用的原因，直接给用户看。 */
    val reason: String? = null,
) {
    val openable: Boolean get() = file.isFile

    /** 技能与提示词才有「在对话里用」。 */
    val usableInChat: Boolean
        get() = kind == WorkspaceResourceKind.Skill || kind == WorkspaceResourceKind.Prompt

    /** 徽标前缀（文本部分）。 */
    val sourceLabel: String
        get() = when (source) {
            WorkspaceSource.ProjectPi -> "项目 "
            WorkspaceSource.Agents -> ""
            WorkspaceSource.Global -> "全局"
            WorkspaceSource.Package -> "包 · "
            WorkspaceSource.Extension -> "扩展贡献"
        }

    /** 徽标的等宽部分（路径型来源用等宽，抽象来源用文本）。 */
    val sourceMono: String?
        get() = when (source) {
            WorkspaceSource.ProjectPi -> ".pi"
            WorkspaceSource.Agents -> ".agents"
            WorkspaceSource.Global -> null
            WorkspaceSource.Package -> packageName
            WorkspaceSource.Extension -> null
        }

    /** 给冲突文案用的整条来源短标签（`项目 .pi` / `包 · pi-skills`）。 */
    val sourceText: String get() = sourceLabel + (sourceMono ?: "")

    /** `在对话里用` 插进输入框的那个词（pi 的 `/名字`）。 */
    val slashCommand: String get() = "/" + name
}

internal object WorkspaceResourceScan {

    /**
     * 本批能被指认为「扩展贡献」的资源。
     *
     * 设备桥扩展在 `resources_discover` 里把它的技能写到
     * `<agentDir>/skills/pi-android-device/SKILL.md`
     * （`assets/pi-extensions/pi-android-bridge/index.ts:1078-1222`），这是磁盘上**唯一**
     * 带扩展身份的资源。别的扩展用 `pi.registerPrompt(...)` / `registerTheme(...)` 在运行时
     * 注册，pi 不为它们落盘，所以这里点不出第二类 —— 宁可少一条，也不给用户一条编出来的
     * 「扩展贡献」。
     */
    private val EXTENSION_CONTRIBUTED_SKILLS = setOf("pi-android-device")

    /**
     * 扫这个工作区里的四类资源。
     *
     * 全部是磁盘读取，调用方保证跑在 IO 线程。
     *
     * @param workspace 宿主工作区目录。
     * @param configDir `<workspace>/.pi`。
     * @param agentDir engine 读的那个 agent dir。
     */
    fun scan(workspace: File, configDir: File, agentDir: File): List<WorkspaceResource> {
        val raw = buildList {
            addAll(scanRoot(configDir, WorkspaceSource.ProjectPi, ".pi"))
            addAll(scanRoot(File(workspace, ".agents"), WorkspaceSource.Agents, ".agents"))
            addAll(scanRoot(agentDir, WorkspaceSource.Global, "~/.pi/agent"))
            addAll(scanPackages(workspace, configDir, agentDir))
        }
        return resolve(raw)
    }

    // ------------------------------------------------------------ 各来源扫描 ----

    /** 一个「像 agent dir 一样」的根：`skills` / `prompts` / `themes` / `extensions`。 */
    private fun scanRoot(root: File, source: WorkspaceSource, label: String): List<Candidate> {
        if (!root.isDirectory) return emptyList()
        val out = mutableListOf<Candidate>()
        PiResourceDiscovery.discover(root, PiResourceDiscovery.Found.Scope.Project).forEach { found ->
            val kind = kindOf(found.kind) ?: return@forEach
            val file = fileFor(root, found.kind, found.name) ?: return@forEach
            out += Candidate(
                kind = kind,
                name = found.name,
                source = source,
                packageName = null,
                file = file,
                displayPath = "$label/${found.kind.directory}/${file.name}",
                filters = emptyList(),
                autoload = true,
            )
        }
        PiAutoExtensions.discover(File(root, EXTENSIONS)).forEach { found ->
            out += Candidate(
                kind = WorkspaceResourceKind.Extension,
                name = found.name,
                source = source,
                packageName = null,
                file = File(File(root, EXTENSIONS), found.relativePath),
                displayPath = "$label/$EXTENSIONS/${found.relativePath}",
                filters = emptyList(),
                autoload = true,
            )
        }
        return out
    }

    /**
     * 包带进来的资源。
     *
     * 包的清单只有两份：项目 `.pi/settings.json` 与 agent dir 的 `settings.json`
     * （`package-manager.ts:977-1003`）。安装目录按 pi 的布局推（`packageRoots`），推不出来
     * 的（git 包的检出目录名由 pi 自己起）就不列 —— 见 [PiResourceDiscovery.packageRoots]
     * 的注释。
     */
    private fun scanPackages(workspace: File, configDir: File, agentDir: File): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val documents = listOf(
            configDir to File(configDir, "npm"),
            agentDir to File(agentDir, "npm"),
        )
        documents.forEach { (rootDir, npmScopeRoot) ->
            readPackages(File(rootDir, "settings.json")).forEach { entry ->
                val source = PiPackageSource.parse(entry.source)
                val npmName = (source as? PiPackageSource.Npm)?.name
                val packageRoot = PiResourceDiscovery.packageRoots(npmScopeRoot, npmName).firstOrNull()
                    ?: localPackageRoot(workspace, source)
                    ?: return@forEach
                if (!packageRoot.isDirectory) return@forEach
                PiResourceDiscovery.discover(packageRoot, PiResourceDiscovery.Found.Scope.Package)
                    .forEach { found ->
                        val kind = kindOf(found.kind) ?: return@forEach
                        val file = fileFor(packageRoot, found.kind, found.name) ?: return@forEach
                        out += Candidate(
                            kind = kind,
                            name = found.name,
                            source = WorkspaceSource.Package,
                            packageName = source.raw,
                            file = file,
                            displayPath = "${source.raw}/${found.kind.directory}/${file.name}",
                            filters = entry.patterns(found.kind.directory),
                            autoload = entry.autoload != false,
                        )
                    }
                PiAutoExtensions.discover(File(packageRoot, EXTENSIONS)).forEach { found ->
                    out += Candidate(
                        kind = WorkspaceResourceKind.Extension,
                        name = found.name,
                        source = WorkspaceSource.Package,
                        packageName = source.raw,
                        file = File(File(packageRoot, EXTENSIONS), found.relativePath),
                        displayPath = "${source.raw}/$EXTENSIONS/${found.relativePath}",
                        filters = entry.patterns(EXTENSIONS),
                        autoload = entry.autoload != false,
                    )
                }
                // 下一个 entry。
            }
        }
        return out
    }

    /** pi 的 `file:` / 相对路径包：装在工作区里的那个目录。 */
    private fun localPackageRoot(workspace: File, source: PiPackageSource): File? =
        when (source) {
            is PiPackageSource.Local -> {
                val path = source.raw.removePrefix("file:")
                if (path.startsWith("/")) null else File(workspace, path)
            }

            else -> null
        }

    /** `settings.json` 的 `packages` 数组，按 pi 的两种形态解析（`PiPackageFilters.parse`）。 */
    private fun readPackages(file: File): List<PiPackageFilters.Entry> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return emptyList()
            val array = root["packages"] as? JsonArray ?: return emptyList()
            array.mapNotNull { PiPackageFilters.parse(it) }
        }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- 解析 ----

    private data class Candidate(
        val kind: WorkspaceResourceKind,
        val name: String,
        val source: WorkspaceSource,
        val packageName: String?,
        val file: File,
        val displayPath: String,
        /** The package entry's globs for this kind; empty means "no filter". */
        val filters: List<String>,
        val autoload: Boolean,
    )

    private fun kindOf(kind: PiResourceDiscovery.Kind): WorkspaceResourceKind? = when (kind) {
        PiResourceDiscovery.Kind.Skills -> WorkspaceResourceKind.Skill
        PiResourceDiscovery.Kind.Prompts -> WorkspaceResourceKind.Prompt
        PiResourceDiscovery.Kind.Themes -> WorkspaceResourceKind.Theme
    }

    /** `PiResourceDiscovery.Found.name` 是去掉后缀的相对路径；把它还原成磁盘上的文件。 */
    private fun fileFor(root: File, kind: PiResourceDiscovery.Kind, name: String): File? = when (kind) {
        PiResourceDiscovery.Kind.Skills -> File(File(File(root, kind.directory), name), "SKILL.md")
        PiResourceDiscovery.Kind.Prompts -> File(File(root, kind.directory), "$name${kind.suffix}")
        PiResourceDiscovery.Kind.Themes -> File(File(root, kind.directory), "$name${kind.suffix}")
    }

    /**
     * 把候选排成界面上那一列：按来源优先级、判出冲突与解析失败、标出被禁用的。
     *
     * 同名的那两条会挨着出现（排序按来源），这正是稿子要的：「冲突不需要用户自己去两处找」。
     */
    private fun resolve(candidates: List<Candidate>): List<WorkspaceResource> {
        val byName = candidates.groupBy { it.kind to it.name }
        val out = mutableListOf<WorkspaceResource>()
        byName.forEach { (_, group) ->
            val ordered = group.sortedWith(compareBy({ it.source.rank }, { it.displayPath }))
            val winner = ordered.firstOrNull()
            ordered.forEach { candidate ->
                val base = toResource(candidate)
                val resolved = when {
                    !candidate.autoload -> base.copy(
                        status = WorkspaceResourceStatus.Disabled,
                        reason = "包被设成不自动加载（settings.json 里 autoload: false）。",
                    )

                    candidate.filters.isNotEmpty() && !matchesAnyFilter(candidate, candidate.filters) ->
                        base.copy(
                            status = WorkspaceResourceStatus.Disabled,
                            reason = "包对「${candidate.kind.label}」设了筛选，这一条不在其中。",
                        )

                    winner != null && winner !== candidate -> base.copy(
                        status = WorkspaceResourceStatus.Conflict,
                        conflictWith = winner.let { sourceTextOf(it) },
                    )

                    else -> base
                }
                out += resolved
            }
        }
        return out.sortedWith(
            compareBy(
                { WorkspaceResourceKind.order.indexOf(it.kind) },
                { it.source.rank },
                { it.name.lowercase(Locale.US) },
            ),
        )
    }

    private fun sourceTextOf(candidate: Candidate): String = when (candidate.source) {
        WorkspaceSource.ProjectPi -> "项目 .pi"
        WorkspaceSource.Agents -> ".agents"
        WorkspaceSource.Global -> "全局"
        WorkspaceSource.Package -> "包 · ${candidate.packageName.orEmpty()}"
        WorkspaceSource.Extension -> "扩展贡献"
    }

    /** 包筛选的 glob：`*` 不跨 `/`，`**` 跨，`?` 一个字符（pi 用的是同一套 fd 风格 glob）。 */
    private fun matchesAnyFilter(candidate: Candidate, patterns: List<String>): Boolean {
        val subject = candidate.displayPath.removePrefix("包 · ").substringAfter('/', "")
        return patterns.any { pattern ->
            val clean = pattern.trim().removePrefix("!").removePrefix("+").removePrefix("-")
            globMatches(clean, candidate.name) ||
                globMatches(clean, subject) ||
                globMatches(clean, candidate.file.name)
        }
    }

    private fun globMatches(pattern: String, subject: String): Boolean {
        if (pattern.isEmpty()) return false
        val regex = buildString {
            append('^')
            var index = 0
            while (index < pattern.length) {
                val char = pattern[index]
                when {
                    char == '*' && index + 1 < pattern.length && pattern[index + 1] == '*' -> {
                        append(".*")
                        index++
                    }

                    char == '*' -> append("[^/]*")
                    char == '?' -> append("[^/]")
                    char in ".+()^$|{}[]\\" -> append('\\').append(char)
                    else -> append(char)
                }
                index++
            }
            append('$')
        }
        return Regex(regex).matches(subject)
    }

    private fun toResource(candidate: Candidate): WorkspaceResource {
        // 「扩展贡献」是**另一层身份**：设备桥写出去的那个技能，文件躺在全局 skills 里，
        // 但它的来源不是用户，而是那个正在运行的扩展。
        val contributedByExtension = candidate.source == WorkspaceSource.Global &&
            candidate.kind == WorkspaceResourceKind.Skill &&
            candidate.name in EXTENSION_CONTRIBUTED_SKILLS
        val front = readFrontMatter(candidate.file)
        val parseProblem = parseProblem(candidate, front)
        return WorkspaceResource(
            kind = candidate.kind,
            name = candidate.name,
            source = if (contributedByExtension) WorkspaceSource.Extension else candidate.source,
            packageName = candidate.packageName,
            description = front?.description ?: versionOf(candidate.file),
            displayPath = candidate.displayPath,
            file = candidate.file,
            status = if (parseProblem != null) WorkspaceResourceStatus.ParseFail else WorkspaceResourceStatus.Normal,
            reason = parseProblem,
        )
    }

    /**
     * pi 对每一类的合法性要求，判到能判的那一层。
     *
     *  - 技能：`front matter` 里要有 `name`（pi 的技能清单就是这么认的）；
     *  - 提示词：写了 `front matter` 才检查它有没有 `name`；
     *  - 主题：必须是能解析的 JSON；
     *  - 扩展：pi 收集时就已经确认过它是 `.ts`/`.js` 或带入口的目录。
     */
    private fun parseProblem(candidate: Candidate, front: FrontMatter?): String? = when (candidate.kind) {
        WorkspaceResourceKind.Skill ->
            if (front?.name.isNullOrBlank()) "front matter 缺 name" else null

        WorkspaceResourceKind.Prompt ->
            if (front != null && front.name.isNullOrBlank()) "front matter 缺 name" else null

        WorkspaceResourceKind.Theme -> runCatching {
            Json.parseToJsonElement(candidate.file.readText())
        }.fold(onSuccess = { null }, onFailure = { "不是合法的 JSON：" + (it.message ?: "解析失败") })

        WorkspaceResourceKind.Extension -> null
    }

    // ------------------------------------------------------- front matter ----

    private data class FrontMatter(val name: String?, val description: String?)

    /**
     * 读一个文件开头的 `--- … ---` 块里的 `name` / `description`。
     *
     * 只看开头 60 行：front matter 是文件头的一小段，第 60 行之后出现的 `---` 是正文里的
     * 分隔线，不是元数据。没有 front matter 时返回 null —— 调用方据此区分「没写」与
     * 「写了但缺字段」。
     */
    private fun readFrontMatter(file: File): FrontMatter? = runCatching {
        if (!file.isFile) return null
        val head = file.bufferedReader().use { reader ->
            buildList {
                repeat(FRONT_MATTER_SCAN_LINES) {
                    val line = reader.readLine() ?: return@repeat
                    add(line)
                }
            }
        }
        if (head.firstOrNull()?.trim() != "---") return null
        val body = head.drop(1).takeWhile { it.trim() != "---" }
        if (body.isEmpty()) return null
        var name: String? = null
        var description: String? = null
        body.forEach { line ->
            val key = line.substringBefore(':', "").trim().lowercase(Locale.US)
            val value = line.substringAfter(':', "").trim().trim('"', '\'')
            when (key) {
                "name" -> if (name == null) name = value
                "description" -> if (description == null) description = value
            }
        }
        FrontMatter(name, description)
    }.getOrNull()

    /** 一个扩展的版本，来自它自己的 `package.json`；取不到就没有副行。 */
    private fun versionOf(file: File): String? = runCatching {
        val manifest = File(file.parentFile, "package.json")
        if (!manifest.isFile) return null
        val root = Json.parseToJsonElement(manifest.readText()) as? JsonObject ?: return null
        (root["version"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { "版本 $it" }
    }.getOrNull()

    private const val EXTENSIONS = "extensions"

    private const val FRONT_MATTER_SCAN_LINES = 60
}
