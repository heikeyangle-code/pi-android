package app.pi.packages

import app.pi.i18n.tr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 「现在**实际加载**了什么」：宿主 `resources.loaded`（SDK `ResourceLoader` 的 `getExtensions / getSkills /
 * getPrompts / getThemes / getAgentsFiles / getSystemPromptSource`）的类型化读数。
 *
 * 与 [PiResourceCatalog] 互补：那边是「开关清单」（含没开的、按包分组），这边是「生效的那几个」——
 * 带描述、来源、扩展注册了什么、冲突与错误。SDK 的诊断（`ResourceDiagnostic`：warning / error /
 * collision）一条不改写地带过来，界面只负责把它们说成人话。纯逻辑，裸 JVM 可测。
 */
object PiLoadedResources {

    /** SDK `SourceScope`：`user` / `project` / `temporary`。 */
    enum class Scope(val key: String, private val zhLabel: String, private val enLabel: String) {
        User("user", "全局", "Global"),
        Project("project", "本项目", "Project"),
        Temporary("temporary", "本次启动", "This Session"),
        Unknown("", "", ""),
        ;

        val label: String get() = tr(zhLabel, enLabel)

        companion object {
            fun of(raw: String?): Scope = entries.firstOrNull { it.key == raw && raw.isNotEmpty() } ?: Unknown
        }
    }

    /** SDK `SourceOrigin`：来自某个包，还是顶层（目录 / 设置里直接列的）。 */
    enum class Origin { Package, TopLevel, Unknown }

    data class Source(val path: String?, val source: String?, val scope: Scope, val origin: Origin, val baseDir: String?)

    data class Named(val name: String, val description: String?)
    data class Flag(val name: String, val description: String?, val isBoolean: Boolean)
    data class Shortcut(val shortcut: String, val description: String?)

    /** 一个已加载的扩展，以及它**注册了什么**。`hidden` 是 pi 自带、不给用户看的那几个。 */
    data class Extension(
        val path: String,
        val resolvedPath: String,
        val hidden: Boolean,
        val source: Source?,
        val commands: List<Named>,
        val tools: List<Named>,
        val flags: List<Flag>,
        val shortcuts: List<Shortcut>,
        val events: List<String>,
        val replaceable: Boolean = false,
        val toolRenderers: List<String> = emptyList(),
        val messageRenderers: List<String> = emptyList(),
        val entryRenderers: List<String> = emptyList(),
        val hasMarkdownTransformer: Boolean = false,
    ) {
        /** 一个扩展注册的「东西」总数（命令 + 工具 + 参数 + 快捷键 + 渲染器 + Markdown 变换器）。 */
        val registered: Int
            get() = commands.size + tools.size + flags.size + shortcuts.size +
                toolRenderers.size + messageRenderers.size + entryRenderers.size +
                (if (hasMarkdownTransformer) 1 else 0)
    }

    data class Skill(
        val name: String,
        val description: String,
        val filePath: String,
        val baseDir: String,
        /** frontmatter 的 `disable-model-invocation`：模型不会自己调用，只能 `/skill:name` 手动用。 */
        val disableModelInvocation: Boolean,
        val source: Source?,
    )

    /** 正文预览（宿主截到 4000 字）：[size] 是原长，[truncated] 为真表示被截了。 */
    data class Body(val text: String, val truncated: Boolean, val size: Long)

    data class Prompt(
        val name: String,
        val description: String,
        val argumentHint: String?,
        val filePath: String,
        val body: Body,
        val source: Source?,
    )

    data class Theme(val name: String?, val path: String?, val source: Source?)

    data class ContextFile(val path: String, val body: Body)

    enum class DiagKind { Warning, Error, Collision }

    data class Collision(
        val resourceType: String,
        val name: String,
        val winnerPath: String,
        val loserPath: String,
        val winnerSource: String?,
        val loserSource: String?,
    )

    data class Diagnostic(val kind: DiagKind, val message: String, val path: String?, val collision: Collision?)

    data class PathMessage(val path: String, val message: String)

    data class Loaded(
        val extensions: List<Extension>,
        val extensionErrors: List<PathMessage>,
        val extensionWarnings: List<PathMessage>,
        val skills: List<Skill>,
        val skillDiagnostics: List<Diagnostic>,
        val prompts: List<Prompt>,
        val promptDiagnostics: List<Diagnostic>,
        val themes: List<Theme>,
        val themeDiagnostics: List<Diagnostic>,
        val contextFiles: List<ContextFile>,
        val systemPrompt: String?,
        val appendSystemPrompt: List<String>,
        val agentDir: String? = null,
        val cwd: String? = null,
    ) {
        /** 给用户看的扩展：去掉 pi 自带的隐藏项。 */
        val visibleExtensions: List<Extension> get() = extensions.filterNot { it.hidden }

        val diagnostics: List<Diagnostic> get() = skillDiagnostics + promptDiagnostics + themeDiagnostics

        /** 所有问题条数：诊断 + 扩展加载错误 + 扩展警告。 */
        val problemCount: Int get() = diagnostics.size + extensionErrors.size + extensionWarnings.size

        companion object {
            val EMPTY = Loaded(
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                emptyList(), emptyList(), emptyList(), emptyList(), null, emptyList(),
            )
        }
    }

    fun parse(root: JsonObject): Loaded = Loaded(
        extensions = rows(root["extensions"]).map(::extension),
        extensionErrors = rows(root["extensionErrors"]).map(::pathMessage),
        extensionWarnings = rows(root["extensionWarnings"]).map(::pathMessage),
        skills = rows(root["skills"]).map {
            Skill(
                name = str(it["name"]).orEmpty(),
                description = str(it["description"]).orEmpty(),
                filePath = str(it["filePath"]).orEmpty(),
                baseDir = str(it["baseDir"]).orEmpty(),
                disableModelInvocation = bool(it["disableModelInvocation"]),
                source = source(it["source"]),
            )
        },
        skillDiagnostics = rows(root["skillDiagnostics"]).map(::diagnostic),
        prompts = rows(root["prompts"]).map {
            Prompt(
                name = str(it["name"]).orEmpty(),
                description = str(it["description"]).orEmpty(),
                argumentHint = str(it["argumentHint"]),
                filePath = str(it["filePath"]).orEmpty(),
                body = body(it["body"]),
                source = source(it["source"]),
            )
        },
        promptDiagnostics = rows(root["promptDiagnostics"]).map(::diagnostic),
        themes = rows(root["themes"]).map { Theme(str(it["name"]), str(it["path"]), source(it["source"])) },
        themeDiagnostics = rows(root["themeDiagnostics"]).map(::diagnostic),
        contextFiles = rows(root["contextFiles"]).map { ContextFile(str(it["path"]).orEmpty(), body(it["body"])) },
        systemPrompt = str(root["systemPrompt"]),
        appendSystemPrompt = (root["appendSystemPrompt"] as? JsonArray).orEmpty().mapNotNull { str(it) },
        agentDir = str(root["agentDir"]),
        cwd = str(root["cwd"]),
    )

    // ---- 内部 ----

    private fun extension(o: JsonObject) = Extension(
        path = str(o["path"]).orEmpty(),
        resolvedPath = str(o["resolvedPath"]) ?: str(o["path"]).orEmpty(),
        hidden = bool(o["hidden"]),
        source = source(o["source"]),
        commands = rows(o["commands"]).map { Named(str(it["name"]).orEmpty(), str(it["description"])) },
        tools = rows(o["tools"]).map { Named(str(it["name"]).orEmpty(), str(it["description"])) },
        flags = rows(o["flags"]).map { Flag(str(it["name"]).orEmpty(), str(it["description"]), str(it["type"]) == "boolean") },
        shortcuts = rows(o["shortcuts"]).map { Shortcut(str(it["shortcut"]).orEmpty(), str(it["description"])) },
        events = (o["events"] as? JsonArray).orEmpty().mapNotNull { str(it) },
        replaceable = bool(o["replaceable"]),
        toolRenderers = (o["toolRenderers"] as? JsonArray).orEmpty().mapNotNull { str(it) },
        messageRenderers = (o["messageRenderers"] as? JsonArray).orEmpty().mapNotNull { str(it) },
        entryRenderers = (o["entryRenderers"] as? JsonArray).orEmpty().mapNotNull { str(it) },
        hasMarkdownTransformer = bool(o["hasMarkdownTransformer"]),
    )

    private fun diagnostic(o: JsonObject): Diagnostic {
        val kind = when (str(o["type"])) {
            "error" -> DiagKind.Error
            "collision" -> DiagKind.Collision
            else -> DiagKind.Warning
        }
        val c = o["collision"] as? JsonObject
        return Diagnostic(
            kind = kind,
            message = str(o["message"]).orEmpty(),
            path = str(o["path"]),
            collision = c?.let {
                Collision(
                    resourceType = str(it["resourceType"]).orEmpty(),
                    name = str(it["name"]).orEmpty(),
                    winnerPath = str(it["winnerPath"]).orEmpty(),
                    loserPath = str(it["loserPath"]).orEmpty(),
                    winnerSource = str(it["winnerSource"]),
                    loserSource = str(it["loserSource"]),
                )
            },
        )
    }

    private fun pathMessage(o: JsonObject) = PathMessage(str(o["path"]).orEmpty(), str(o["message"]).orEmpty())

    private fun source(e: JsonElement?): Source? {
        val o = e as? JsonObject ?: return null
        return Source(
            path = str(o["path"]),
            source = str(o["source"]),
            scope = Scope.of(str(o["scope"])),
            origin = when (str(o["origin"])) {
                "package" -> Origin.Package
                "top-level" -> Origin.TopLevel
                else -> Origin.Unknown
            },
            baseDir = str(o["baseDir"]),
        )
    }

    private fun body(e: JsonElement?): Body {
        val o = e as? JsonObject ?: return Body("", false, 0)
        return Body(str(o["text"]).orEmpty(), bool(o["truncated"]), (o["size"] as? JsonPrimitive)?.longOrNull ?: 0)
    }

    private fun rows(e: JsonElement?): List<JsonObject> = (e as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun bool(e: JsonElement?): Boolean = (e as? JsonPrimitive)?.booleanOrNull == true
}
