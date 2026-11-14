package app.pi.ui.chat

import app.pi.rpc.PiResponses
import app.pi.rpc.PiSourceInfo

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
    PickModel,
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

    /** pi 有这个命令，这个客户端做不了：点一下说明原因（[UNSUPPORTED_BUILTIN_REASONS]），不假装。 */
    Unsupported,
}

/** Palette grouping. Mirrors the `source` field of `RpcSlashCommand`. */
enum class PiCommandSource(val label: String) {
    /** `BUILTIN_SLASH_COMMANDS` in pi (`core/slash-commands.ts:19-43`). */
    Builtin("内置"),

    /** `source: "extension"` — `registerCommand`. */
    Extension("扩展"),

    /** `source: "prompt"` — a `.md` prompt template. */
    Prompt("模板"),

    /** `source: "skill"` — a `SKILL.md`, invoked as `/skill:<name>`. */
    Skill("技能"),
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
) {
    /** What the user types to invoke it. */
    val invocation: String get() = "/$name"
}

/**
 * 一个 pi 内建命令在手机上怎么做。
 *
 * @property zh 面板里显示的中文说明（pi 自己的说明是英文）。
 * @property note 点击后先弹的一句话——用于「跳到设置页」这一类，告诉用户落在哪一页。
 * @property argumentHint 有参数的命令：点击只把 `/name ` 填进输入框等用户补参数，不直接执行。
 */
class ClientBuiltin(
    val action: ChatAction,
    val zh: String,
    val argumentHint: String? = null,
    val note: String? = null,
)

/**
 * pi 的内建命令 → 本客户端的实现。**键就是 pi 的命令名**，表里的顺序也是 pi 的
 * `BUILTIN_SLASH_COMMANDS` 顺序（宿主没送来内建表时，面板按这个顺序兜底）。
 *
 * 这张表只回答「怎么做」；有哪些命令、说明是什么，以 pi（宿主送来的内建表）为准。
 * pi 以后新增的命令不在这里，会作为「不支持」行出现在面板里（说明用 pi 自己的英文），
 * 不会像手抄表那样悄悄缺一个。
 */
val CLIENT_BUILTINS: Map<String, ClientBuiltin> = linkedMapOf(
    "settings" to ClientBuiltin(ChatAction.OpenSettings, "打开设置"),
    "model" to ClientBuiltin(ChatAction.PickModel, "选择模型"),
    "tree" to ClientBuiltin(ChatAction.OpenTree, "浏览会话树（切换分支）"),
    "thinking" to ClientBuiltin(ChatAction.PickThinking, "设置思考等级"),
    "scoped-models" to ClientBuiltin(
        ChatAction.OpenSettings, "选择参与循环切换的模型",
        note = "「循环模型」在 设置 → 模型与推理。",
    ),
    "export" to ClientBuiltin(
        ChatAction.ExportSession,
        "导出会话：不带参数＝HTML 与可导入的会话文件各一份；给名字＝按后缀只写一份",
    ),
    "import" to ClientBuiltin(ChatAction.ImportSession, "从 JSONL 文件导入并恢复会话"),
    "bug" to ClientBuiltin(ChatAction.BugReport, "生成问题报告并复制（可附一句说明）"),
    "copy" to ClientBuiltin(ChatAction.CopyLastAssistant, "复制最后一条回复到剪贴板"),
    "name" to ClientBuiltin(ChatAction.RenameSession, "设置会话显示名称"),
    "session" to ClientBuiltin(ChatAction.SessionStats, "查看会话信息与用量"),
    "fork" to ClientBuiltin(ChatAction.PickFork, "从某条历史消息分叉出新会话"),
    "clone" to ClientBuiltin(ChatAction.CloneSession, "在当前节点复制整个会话"),
    "trust" to ClientBuiltin(
        ChatAction.OpenSettings, "保存项目信任决定",
        note = "项目信任在 设置 → 安全与信任，或工作区里该项目的资源页。",
    ),
    "login" to ClientBuiltin(
        ChatAction.OpenSettings, "配置模型服务商登录",
        note = "登录/凭证在 设置 → 模型与推理 → 凭证。",
    ),
    "logout" to ClientBuiltin(
        ChatAction.OpenSettings, "移除模型服务商登录",
        note = "退出登录在 设置 → 模型与推理 → 凭证。",
    ),
    "new" to ClientBuiltin(ChatAction.NewSession, "开始新会话"),
    "compact" to ClientBuiltin(ChatAction.Compact, "手动压缩会话上下文"),
    "resume" to ClientBuiltin(ChatAction.OpenSessions, "恢复另一个会话"),
    "reload" to ClientBuiltin(ChatAction.ReloadResources, "重新加载扩展、技能、提示词、主题和上下文文件"),
)

/**
 * pi 有、这个客户端做不了的内建命令，各自的实话。不在这里也不在 [CLIENT_BUILTINS] 里的名字
 * （pi 新加的）用 [GENERIC_UNSUPPORTED]。
 */
val UNSUPPORTED_BUILTIN_REASONS: Map<String, String> = mapOf(
    "share" to "分享需要把会话上传到 Radius 或用 gh 建 gist，本应用没有这条路；要把会话交出去请用 /export。",
    "changelog" to "更新日志是终端里的文本面板；本应用的版本变化看应用自己的更新说明。",
    "hotkeys" to "快捷键列表对触屏没有意义。",
    "quit" to "引擎由应用的前台服务管理，没有要退出的终端进程。",
)

const val GENERIC_UNSUPPORTED = "pi 有这个命令，这个版本的应用还没有对应的做法。"

/**
 * 面板里的内建命令行：**pi 的表在前、我们的映射在后**。
 *
 * [wire] 是宿主送来的 pi 内建表（空 = 宿主没读到，退回 [CLIENT_BUILTINS] 的顺序加上
 * [UNSUPPORTED_BUILTIN_REASONS] 的几项）。每个 pi 命令一行：映射里有 → 可执行行；没有 → 不支持行。
 */
fun builtinRows(wire: List<PiResponses.BuiltinCommand>): List<PiSlashCommand> {
    val source = wire.ifEmpty {
        (CLIENT_BUILTINS.keys + UNSUPPORTED_BUILTIN_REASONS.keys).map { PiResponses.BuiltinCommand(it, null, null) }
    }
    return source.map { builtin ->
        val client = CLIENT_BUILTINS[builtin.name]
        if (client != null) {
            PiSlashCommand(
                name = builtin.name,
                description = client.zh,
                source = PiCommandSource.Builtin,
                sourceTag = null,
                action = client.action,
                // 只用我们自己的提示：pi 的提示（如 /model 的 `<provider/model>`）描述的是 pi 终端里
                // 带参数的用法；这里点一下就该直接执行（有提示的行点击只会把名字填进输入框等参数）。
                argumentHint = client.argumentHint,
            )
        } else {
            PiSlashCommand(
                name = builtin.name,
                description = builtin.description,
                source = PiCommandSource.Builtin,
                sourceTag = null,
                action = ChatAction.Unsupported,
            )
        }
    }
}

/** 点了「不支持」行（或手敲了它）时的那句实话。 */
fun unsupportedReason(name: String): String =
    UNSUPPORTED_BUILTIN_REASONS[name.trim().removePrefix("/").lowercase()] ?: GENERIC_UNSUPPORTED

/**
 * Build the palette: pi's `get_commands` result plus pi's built-ins.
 *
 * Deduplication follows pi's own rule rather than a guess. When an extension
 * registers a command whose name is a built-in, pi keeps the **built-in** in
 * its autocomplete and reports a diagnostic
 * (`interactive-mode.ts` `getBuiltInCommandConflictDiagnostics`: "Skipping in
 * autocomplete"); the extension command stays invocable if typed with its
 * suffixed invocation name. So a name that is a built-in wins, and the
 * extension row is dropped only when it would collide *exactly* — a suffixed
 * `name:1` is kept (audit §5.9).
 *
 * The extension rows carry `sourceInfo`, so the tag comes from pi's own scope +
 * origin fields (`core/source-info.ts:3-12`) rather than from a path guess.
 *
 * ## Group order
 *
 * The three `get_commands` groups arrive on the wire in one order and are
 * displayed in another, because those are two genuinely different orders in pi:
 *
 *  - the wire pushes **extension → template → skill** (`rpc-mode.ts:684-708`: the
 *    `for` loops run over `getRegisteredCommands()`, then `promptTemplates`, then
 *    `getSkills().skills`);
 *  - the TUI builds its own autocomplete list as
 *    `[...slashCommands, ...templateCommands, ...extensionCommands,
 *    ...skillCommandList]` (`interactive-mode.ts:727`) — i.e. built-ins,
 *    **templates, extensions**, skills.
 *
 * This palette reproduces the **TUI** order, because it is the surface a pi user
 * sees while typing `/` and the panel exists to be that surface on a phone;
 * `get_commands` is only the transport. [sortedBy] is stable, so rows inside one
 * group keep the order the wire sent them in.
 *
 * @param skillCommandsEnabled pi's `enableSkillCommands` (default true). This is
 *        where that setting actually takes effect in this app: pi reads it only in
 *        its own TUI (`interactive-mode.ts:716`, `:4570`) and its RPC `get_commands`
 *        hands over skill commands unconditionally (`rpc-mode.ts:702-708`), so a
 *        panel built from that answer alone would ignore the switch entirely. pi's
 *        TUI registers skills as commands only when the flag is on; dropping the
 *        `Skill`-sourced entries here is the same rule applied to the panel this app
 *        owns. The skills stay readable by the model either way — the setting is
 *        about the command panel, not about access.
 */
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
        )
    }
    return builtins + fromPi.sortedBy { tuiGroupRank(it.source) }
}

/**
 * Where [source] sits in pi's TUI autocomplete list
 * (`interactive-mode.ts:727`), which is the order this palette displays.
 *
 * Separate from [PiCommandSource]'s declaration order on purpose: that order is
 * the wire's (`rpc-mode.ts:684-708`), and conflating the two is what made the
 * panel's grouping disagree with the TUI it is copying.
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
        "u" -> "用户"
        "p" -> "项目"
        "t" -> "临时"
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
