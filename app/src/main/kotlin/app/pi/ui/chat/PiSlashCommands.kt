package app.pi.ui.chat

import app.pi.i18n.tr
import app.pi.protocol.PiResponses
import app.pi.protocol.PiSourceInfo

/**
 * 对话页上一个「动作」：命令面板的行、顶栏「⋯」菜单的项**共用这一个枚举和同一个执行函数**
 * （`ChatScreen.perform`）。此前菜单项各自内联一段 lambda、面板另有一个 `pick`，同一件事
 * （新建会话、选模型、导出……）在两处各写一遍，改一处漏另一处。现在两处只负责「摆在哪、叫什么」，
 * 做什么只有一份。
 *
 * What selecting a palette row does.
 *
 * [Prompt] is the only action that goes back to pi as a string: pi dispatches `/name args` itself
 * (extension command → skill → prompt template). Everything else is one of pi's **built-in** commands,
 * which pi's own TUI runs client-side — so each client decides what it means on its own screen.
 * 宿主把 pi 的内建命令表送过来（`catalog.commands.builtins`），这里只维护「手机上怎么做」：
 * [CLIENT_BUILTINS] 里有的就是下面这些动作，没有的显示出来并说明（[ChatAction.Unsupported]）。
 */
enum class ChatAction {
    /** `prompt("/<name> args")` — pi expands/dispatches it. */
    Prompt,

    OpenSettings,
    OpenTerminal,
    PickModel,
    /** pi 的 `app.model.cycleForward`（Ctrl+P）：按「作用域模型」顺序切到下一个。 */
    CycleModel,
    PickThinking,
    OpenTree,
    SessionStats,
    NewSession,
    OpenSessions,
    ReloadResources,
    BugReport,
    PickFork,
    CloneSession,

    /**
     * `/export [path]`. The format follows the argument's extension, the way pi's own TUI does it
     * (`interactive-mode.ts:6062-6066`): `.jsonl` goes to the app-side JSONL writer, anything else to
     * `export_html`.
     */
    ExportSession,
    CopyLastAssistant,
    RenameSession,
    Compact,

    /** `/import`: pi 的 TUI 要一个路径；手机上没有路径可敲，所以开文件选择器（见 ChatScreen）。 */
    ImportSession,

    /** 菜单用：上下文占用与用量面板（输入框的环也开它）。 */
    ContextUsage,

    /** 菜单用：「会话与队列」面板。 */
    ToolsSheet,

    /** 菜单用：查看当前生效的系统提示词（由对话顶栏 ⋯ 命令中心直达）。 */
    ViewSystemPrompt,

    /** pi 有这个命令，这个客户端做不了：点一下说明原因（[UNSUPPORTED_BUILTIN_REASONS]），不假装。 */
    Unsupported,
}

/** Palette grouping. Mirrors the `source` field of `RpcSlashCommand`. */
enum class PiCommandSource {
    /** `BUILTIN_SLASH_COMMANDS` in pi (`core/slash-commands.ts:19-43`). */
    Builtin,

    /** `source: "extension"` — `registerCommand`. */
    Extension,

    /** `source: "prompt"` — a `.md` prompt template. */
    Prompt,

    /** `source: "skill"` — a `SKILL.md`, invoked as `/skill:<name>`. */
    Skill;

    val label: String
        get() = when (this) {
            Builtin -> tr("内置", "Built-in")
            Extension -> tr("扩展", "Extensions")
            Prompt -> tr("模板", "Prompts")
            Skill -> tr("技能", "Skills")
        }
}

/**
 * One row of the `/` palette.
 *
 * [name] is the **invocation name**, not the declared name: pi suffixes
 * colliding extension commands with `:1`, `:2` in load order and sends
 * `command.invocationName` (`runner.ts` `getRegisteredCommands`,
 * `rpc-mode.ts:687`), so `/review:2` is a real name the palette must show
 * verbatim (audit §5.9).
 */
data class PiSlashCommand(
    val name: String,
    val description: String?,
    val source: PiCommandSource,
    /**
     * Provenance tag, **derived** exactly the way pi's own autocomplete derives
     * it (`interactive-mode.ts` `getAutocompleteSourceTag`, `:586-606`): the
     * scope letter `u`/`p`/`t` for user/project/temporary
     * (`core/source-info.ts:3`), plus the npm or git origin when there is one
     * (`u:npm:<name>`, `u:git:<host>/<path>@<ref>`). Null for built-ins, which
     * have no source file.
     *
     * **The letters are pi's convention, not this app's rendering.** The badge
     * shows [sourceTagLabelOf] instead, which spells the scope out in Chinese
     * (用户/项目/临时) and leaves the npm/git remainder verbatim. Do not "fix" the
     * letters here to match the badge, and do not "fix" the badge back to letters:
     * the split is deliberate — derivation is pi's, presentation is the user's.
     */
    val sourceTag: String?,
    val action: ChatAction,
    val argumentHint: String? = null,
    /** 该命令（内置 `/model` `/thinking` `/login` 或扩展命令的 `getArgumentCompletions`）能按已输入的前缀给参数候选。 */
    val completes: Boolean = false,
    /**
     * 是否在面板列表里占一行。pi 有、手机做不了的内建命令（[UNSUPPORTED_BUILTIN_REASONS]）是 false：
     * 一行只会说「不行」的命令放在列表里是噪音，敲全名时才出现并说明原因（`filterPalette`）。
     */
    val listed: Boolean = true,
) {
    /** What the user types to invoke it. */
    val invocation: String get() = "/$name"
}

/**
 * 一个 pi 内建命令在手机上怎么做。
 *
 * @property zh 面板里显示的中文说明（pi 自己的说明是英文）。
 * @property en 英文说明，严格对齐 Pi 1.6.0 `core/slash-commands.ts`。
 * @property argumentHint 兜底参数提示（宿主在线时优先采用官方 `BUILTIN_SLASH_COMMANDS` 的 `argumentHint`）。
 * @property completes 是否支持参数自动补全（对齐官方 `interactive-mode.ts:813-864`）。
 * @property prefillOnTap 点击命令面板行时是否先写入 `/name ` 等待补参数，而非直接打开移动端原生选择器。
 */
class ClientBuiltin(
    val action: ChatAction,
    val zh: String,
    val en: String,
    val argumentHint: String? = null,
    val completes: Boolean = false,
    val prefillOnTap: Boolean = false,
    val noteZh: String? = null,
    val noteEn: String? = null,
) {
    val description: String get() = tr(zh, en)
    val note: String? get() = if (noteZh == null && noteEn == null) null else tr(noteZh.orEmpty(), noteEn.orEmpty())
}

/**
 * pi 的内建命令 → 本客户端的实现。**键就是官方 Pi 1.6.0 `BUILTIN_SLASH_COMMANDS` 的命令名**，
 * 顺序、英文描述与 `argumentHint` 严格对齐 `packages/coding-agent/src/core/slash-commands.ts`。
 */
val CLIENT_BUILTINS: Map<String, ClientBuiltin> = linkedMapOf(
    "settings" to ClientBuiltin(ChatAction.OpenSettings, "打开设置", "Open settings menu"),
    "model" to ClientBuiltin(
        ChatAction.PickModel,
        "选择模型",
        "Select model (opens selector UI)",
        argumentHint = "<provider/model>",
        completes = true,
    ),
    "tree" to ClientBuiltin(ChatAction.OpenTree, "浏览会话树（切换分支）", "Navigate session tree (switch branches)"),
    "thinking" to ClientBuiltin(
        ChatAction.PickThinking,
        "设置思考等级",
        "Set thinking level",
        argumentHint = "<level>",
        completes = true,
    ),
    "scoped-models" to ClientBuiltin(
        ChatAction.OpenSettings,
        "选择参与循环切换的模型",
        "Enable/disable models for model cycling",
    ),
    "export" to ClientBuiltin(
        ChatAction.ExportSession,
        "导出会话（默认 HTML，或指定路径：.html / .jsonl）",
        "Export session (HTML default, or specify path: .html/.jsonl)",
    ),
    "import" to ClientBuiltin(
        ChatAction.ImportSession,
        "从 JSONL 文件导入并恢复会话",
        "Import a JSONL session, preserving its ID and working directory",
    ),
    "bug" to ClientBuiltin(
        ChatAction.BugReport,
        "生成问题报告并复制（可附一句说明）",
        "Report a bug to the Pi developers",
        argumentHint = "<description>",
        prefillOnTap = true,
    ),
    "copy" to ClientBuiltin(
        ChatAction.CopyLastAssistant,
        "复制最后一条回复到剪贴板",
        "Copy last agent message to clipboard",
    ),
    "name" to ClientBuiltin(ChatAction.RenameSession, "设置会话显示名称", "Set session display name"),
    "session" to ClientBuiltin(ChatAction.SessionStats, "查看会话信息与用量", "Show session info and stats"),
    "fork" to ClientBuiltin(
        ChatAction.PickFork,
        "从某条历史消息分叉出新会话",
        "Create a new fork from a previous user message",
    ),
    "clone" to ClientBuiltin(
        ChatAction.CloneSession,
        "在当前节点复制整个会话",
        "Duplicate the current session at the current position",
    ),
    "trust" to ClientBuiltin(
        ChatAction.OpenSettings,
        "保存项目信任决定",
        "Save project trust decision for future sessions",
    ),
    "login" to ClientBuiltin(
        ChatAction.OpenSettings,
        "配置模型服务商登录",
        "Configure provider authentication",
        argumentHint = "<provider>",
        completes = true,
    ),
    "logout" to ClientBuiltin(
        ChatAction.OpenSettings,
        "移除模型服务商登录",
        "Remove provider authentication",
    ),
    "new" to ClientBuiltin(ChatAction.NewSession, "开始新会话", "Start a new session"),
    "compact" to ClientBuiltin(ChatAction.Compact, "手动压缩会话上下文", "Manually compact the session context"),
    "resume" to ClientBuiltin(ChatAction.OpenSessions, "恢复另一个会话", "Resume another session"),
    "reload" to ClientBuiltin(
        ChatAction.ReloadResources,
        "重新加载扩展、技能、提示词、主题和上下文文件",
        "Reload keybindings, extensions, skills, prompts, themes, and context files",
    ),
)

/**
 * pi 有、这个客户端做不了的内建命令，各自的实话。
 */
val UNSUPPORTED_BUILTIN_REASONS: Map<String, Pair<String, String>> = mapOf(
    "share" to (
        "分享需要把会话上传到 Radius 或用 gh 建 gist，本应用没有这条路；要把会话交出去请用 /export。" to
            "Sharing requires Radius or gh gist, which is not available on Android. Use /export instead."
        ),
    "changelog" to (
        "更新日志是终端里的文本面板；本应用的版本变化看应用自己的更新说明。" to
            "Changelog is a terminal-only view; check the app's release notes for changes."
        ),
    "hotkeys" to (
        "快捷键列表对触屏没有意义。" to
            "Desktop keyboard shortcuts do not apply on touchscreens."
        ),
    "quit" to (
        "引擎由应用的前台服务管理，没有要退出的终端进程。" to
            "The engine is managed by the app's foreground service; there is no terminal process to quit."
        ),
)

fun genericUnsupportedReason(): String = tr(
    "pi 有这个命令，这个版本的应用还没有对应的做法。",
    "Pi supports this command, but this version of the app does not implement it yet.",
)

/**
 * 面板里的内建命令行：**pi 的表在前、我们的映射在后**。
 * 保留官方 `BUILTIN_SLASH_COMMANDS` 的 `argumentHint` 与内置补全能力。
 */
fun builtinRows(wire: List<PiResponses.BuiltinCommand>): List<PiSlashCommand> {
    val source = wire.ifEmpty {
        (CLIENT_BUILTINS.keys + UNSUPPORTED_BUILTIN_REASONS.keys).map { name ->
            PiResponses.BuiltinCommand(name, CLIENT_BUILTINS[name]?.en, CLIENT_BUILTINS[name]?.argumentHint)
        }
    }
    return source.map { builtin ->
        val client = CLIENT_BUILTINS[builtin.name]
        if (client != null) {
            PiSlashCommand(
                name = builtin.name,
                description = client.description,
                source = PiCommandSource.Builtin,
                sourceTag = null,
                action = client.action,
                argumentHint = builtin.argumentHint ?: client.argumentHint,
                completes = client.completes,
            )
        } else {
            PiSlashCommand(
                name = builtin.name,
                description = builtin.description,
                source = PiCommandSource.Builtin,
                sourceTag = null,
                action = ChatAction.Unsupported,
                argumentHint = builtin.argumentHint,
                listed = builtin.name !in UNSUPPORTED_BUILTIN_REASONS,
            )
        }
    }
}

/** 点了「不支持」行（或手敲了它）时的那句实话。 */
fun unsupportedReason(name: String): String {
    val pair = UNSUPPORTED_BUILTIN_REASONS[name.trim().removePrefix("/").lowercase()]
    return if (pair != null) tr(pair.first, pair.second) else genericUnsupportedReason()
}

/**
 * 过滤并排序：名字前缀 → 名字包含 → 说明包含（组内保持原顺序，即官方的 内置→模板→扩展→技能）。
 * `listed = false` 的行（已知做不了的内建命令）只在敲全名时出现。
 */
fun filterPalette(commands: List<PiSlashCommand>, query: String): List<PiSlashCommand> {
    val needle = query.trim().removePrefix("/").lowercase()
    if (needle.isEmpty()) return commands.filter { it.listed }
    val visible = commands.filter { it.listed || it.name.lowercase() == needle }
    val byName = visible.filter { it.name.lowercase().startsWith(needle) }
    val byContains = visible.filter { !it.name.lowercase().startsWith(needle) && it.name.lowercase().contains(needle) }
    val byDescription = visible.filter {
        !it.name.lowercase().contains(needle) && it.description.orEmpty().lowercase().contains(needle)
    }
    // 分组头要求同一来源的行相邻：排序之后按官方组序稳定归并。
    return (byName + byContains + byDescription).sortedBy { groupRank(it.source) }
}

private fun groupRank(source: PiCommandSource): Int = when (source) {
    PiCommandSource.Builtin -> 0
    PiCommandSource.Prompt -> 1
    PiCommandSource.Extension -> 2
    PiCommandSource.Skill -> 3
}

/** 输入框里「命令名已写完、正在写参数」的状态：哪条命令、参数写到哪了。 */
data class ArgumentContext(val command: PiSlashCommand, val prefix: String)

/**
 * 草稿是 `/名字 参数…` 且 `名字` 是已知命令时，返回命令和已输入的参数前缀；否则 null。
 * 命令面板只管第一个词（见 `paletteQueryOf`），空白字符之后轮到这里。
 */
fun argumentContextOf(draft: String, commands: List<PiSlashCommand>): ArgumentContext? {
    val text = draft.trimStart()
    if (!text.startsWith("/")) return null
    val sep = text.indexOfFirst { it.isWhitespace() }
    if (sep < 0) return null
    val name = text.substring(1, sep)
    val command = commands.firstOrNull { it.name == name } ?: return null
    return ArgumentContext(command, text.substring(sep + 1))
}

fun piCommandPalette(
    commands: List<PiResponses.SlashCommand>,
    builtins: List<PiSlashCommand> = builtinRows(emptyList()),
    skillCommandsEnabled: Boolean = true,
): List<PiSlashCommand> {
    val taken = builtins.mapTo(HashSet()) { it.name }
    val fromPi = commands.mapNotNull { command ->
        if (command.source == PiResponses.CommandSource.Skill && !skillCommandsEnabled) {
            return@mapNotNull null
        }
        if (!taken.add(command.name)) return@mapNotNull null
        PiSlashCommand(
            name = command.name,
            description = command.description,
            source = when (command.source) {
                PiResponses.CommandSource.Extension -> PiCommandSource.Extension
                PiResponses.CommandSource.Prompt -> PiCommandSource.Prompt
                PiResponses.CommandSource.Skill -> PiCommandSource.Skill
                PiResponses.CommandSource.Unknown -> PiCommandSource.Extension
            },
            sourceTag = sourceTagOf(command.sourceInfo),
            action = ChatAction.Prompt,
            argumentHint = command.argumentHint,
            completes = command.hasCompletions,
        )
    }
    return builtins + fromPi.sortedBy { tuiGroupRank(it.source) }
}

/**
 * Where [source] sits in pi's TUI autocomplete list
 * (`interactive-mode.ts:727`), which is the order this palette displays.
 */
private fun tuiGroupRank(source: PiCommandSource): Int = when (source) {
    PiCommandSource.Builtin -> 0
    PiCommandSource.Prompt -> 1
    PiCommandSource.Extension -> 2
    PiCommandSource.Skill -> 3
}

/**
 * pi's autocomplete provenance tag, reproduced from
 * `interactive-mode.ts` `getAutocompleteSourceTag` (`:586-606`):
 *
 *  - scope `user` → `u`, `project` → `p`, anything else (temporary) → `t`
 *    (`:591`, over `SourceScope` — `core/source-info.ts:3`);
 *  - source `auto`/`local`/`cli` adds nothing;
 *  - `npm:<name>` becomes `u:npm:<name>` (`:598-601`);
 *  - a git URL becomes `u:git:<host>/<path>[@ref]` (`:602-605`).
 *
 * This function is the **derivation** and stays byte-identical to pi's; the
 * *rendering* is [sourceTagLabelOf]'s. The tag is carried at all because
 * `source` alone (`extension`/`prompt`/`skill`) cannot tell a user's own template
 * from one inside an npm package — the distinction pi's own UI makes and the one
 * a user needs when deciding whether to trust a command (audit §5.12). That npm
 * and git remainder is exactly what [sourceTagLabelOf] must not drop.
 */
fun sourceTagOf(info: PiSourceInfo?): String? {
    if (info == null) return null
    val scope = when (info.scope) {
        "user" -> "u"
        "project" -> "p"
        else -> "t"
    }
    val source = info.source?.trim().orEmpty()
    if (source.isEmpty() || source == "auto" || source == "local" || source == "cli") return scope
    if (source.startsWith("npm:")) return "$scope:$source"
    val git = parseGitSource(source) ?: return scope
    return "$scope:git:$git"
}

/**
 * [sourceTagOf]'s display form: the scope letter becomes its Chinese word, every
 * other part of the tag is passed through untouched.
 *
 * ```
 * u                     → 用户
 * p:npm:@acme/pi-tools  → 项目·npm:@acme/pi-tools
 * u:git:github.com/a/b@main → 用户·git:github.com/a/b@main
 * ```
 *
 * The split is deliberate and was ruled on: `u`/`p`/`t` is **pi's** convention
 * (`interactive-mode.ts:591`) and is what the wire and the derivation speak, but
 * a single Latin letter next to 「扩展」/「技能」 read as noise to the user, so the
 * badge spells it out. The npm and git parts are **not** translated and **not**
 * dropped — they are the whole reason the tag exists (「我自己写的模板」 vs
 * 「某个 npm 包里带的」, `:598-605`).
 *
 * An unrecognised tag is returned unchanged rather than guessed at: a future
 * scope letter must show up as itself, not as a plausible-looking Chinese word.
 */
fun sourceTagLabelOf(tag: String): String {
    val scope = when (tag.substringBefore(':')) {
        "u" -> tr("用户", "User")
        "p" -> tr("项目", "Project")
        "t" -> tr("临时", "Temp")
        else -> return tag
    }
    val rest = tag.substringAfter(':', "")
    return if (rest.isEmpty()) scope else "$scope·$rest"
}

/**
 * The `host/path[@ref]` part of a git source, i.e. what pi puts after
 * `:git:` in the tag.
 *
 * This is an **approximation** of pi's `parseGitUrl`
 * (`utils/git.ts:172-215`), which delegates the grammar to `hosted-git-info`
 * and then formats `domain/user/project@committish`. Reproducing that library
 * here would be a dependency for a cosmetic tag, so only the forms a
 * `packages` entry realistically contains are recognised — `https://`, `ssh://`,
 * `git://`, a `git:` prefix, and the `git@host:owner/repo` shorthand — with a
 * trailing `.git` and `#ref` stripped. Anything else returns null and the row
 * falls back to the plain scope tag rather than inventing a provenance.
 */
private fun parseGitSource(rawSource: String): String? {
    val source = rawSource.removePrefix("git:")
    val shorthand = Regex("^git@([^:]+):(.+?)(?:\\.git)?(?:#(.+))?$").find(source)
    val url = Regex("^(?:https?|ssh|git)://([^/]+)/(.+?)(?:\\.git)?(?:#(.+))?$").find(source)
    val match = shorthand ?: url ?: return null
    val (host, path, ref) = match.destructured
    return "$host/$path" + if (ref.isNotEmpty()) "@$ref" else ""
}
