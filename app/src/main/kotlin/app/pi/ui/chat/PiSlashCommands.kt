package app.pi.ui.chat

import app.pi.rpc.PiResponses
import app.pi.rpc.PiSourceInfo

/**
 * What selecting a palette row does.
 *
 * `Prompt` is the only action that goes back to pi as a string: pi dispatches
 * `/name args` itself (`agent-session.ts` `prompt()` →
 * `_tryExecuteExtensionCommand`, then `_expandSkillCommand`, then
 * `expandPromptTemplate`). Everything else is a **built-in** interactive
 * command, and pi's own TUI handles those entirely client-side
 * (`interactive-mode.ts` `setupEditorSubmitHandler`): they are not extension
 * commands, are not prompt templates, and are deliberately excluded from
 * `get_commands` (`rpc-mode.ts` "get_commands" builds its list from
 * `extensionRunner.getRegisteredCommands()` + `session.promptTemplates` +
 * skills only). A built-in therefore has exactly two honest outcomes in a GUI:
 * run the app's own implementation of it, or say that only the terminal can.
 */
enum class PiCommandAction {
    /** `prompt("/<name> args")` — pi expands/dispatches it. */
    Prompt,

    /** Built-ins this GUI implements natively. */
    OpenSettings,
    PickModel,
    PickThinking,
    OpenTree,
    PickFork,
    CloneSession,
    ExportHtml,
    CopyLastAssistant,
    RenameSession,
    SessionStats,
    NewSession,
    Compact,
    OpenSessions,

    /**
     * A built-in whose implementation lives in pi's interactive shell and has
     * **no RPC counterpart**: pi exposes no command for it and `prompt()`
     * cannot reach it (built-ins are filtered out of `get_commands` and
     * `prompt` only dispatches extension commands). The GUI must say so and
     * point at the `pi TUI（原版）` terminal tab rather than fake it.
     */
    TerminalOnly,
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
     * Provenance tag built exactly the way pi's own autocomplete builds it
     * (`interactive-mode.ts` `getAutocompleteSourceTag`): `u`/`p`/`t` for
     * user/project/temporary scope, plus the npm or git origin when there is
     * one. Null for built-ins, which have no source file.
     */
    val sourceTag: String?,
    val action: PiCommandAction,
    val argumentHint: String? = null,
) {
    /** What the user types to invoke it. */
    val invocation: String get() = "/$name"
}

/**
 * pi's built-in slash commands, transcribed from `core/slash-commands.ts:19-43`
 * (the same array pi builds its own autocomplete from,
 * `interactive-mode.ts:636`).
 *
 * Order is pi's order and is not sorted on purpose: it is the order the user
 * sees in the original TUI, and a palette that reorders itself would make the
 * two surfaces feel like different products.
 *
 * `/quit` is the one row with no action at all in either column: the engine is
 * owned by the app's foreground service, not by the transcript, so there is
 * nothing for the GUI to quit. It is kept as [PiCommandAction.TerminalOnly] so
 * the user is not left wondering why the command they know is absent.
 */
val PI_BUILTIN_SLASH_COMMANDS: List<PiSlashCommand> = listOf(
    PiSlashCommand("settings", "打开设置菜单", PiCommandSource.Builtin, null, PiCommandAction.OpenSettings),
    PiSlashCommand(
        "model", "选择模型（打开选择器）", PiCommandSource.Builtin, null,
        PiCommandAction.PickModel, "<provider/model>",
    ),
    PiSlashCommand("tree", "浏览会话树（切换分支）", PiCommandSource.Builtin, null, PiCommandAction.OpenTree),
    PiSlashCommand(
        "thinking", "设置思考等级", PiCommandSource.Builtin, null,
        PiCommandAction.PickThinking, "<level>",
    ),
    PiSlashCommand(
        "scoped-models", "启用/禁用 Ctrl+P 循环的模型范围", PiCommandSource.Builtin, null,
        PiCommandAction.TerminalOnly,
    ),
    PiSlashCommand(
        "export", "导出会话（默认 HTML，或指定 .html/.jsonl 路径）", PiCommandSource.Builtin, null,
        PiCommandAction.ExportHtml,
    ),
    PiSlashCommand(
        "import", "从 JSONL 文件导入并恢复会话", PiCommandSource.Builtin, null,
        PiCommandAction.TerminalOnly,
    ),
    PiSlashCommand("share", "将会话分享为私密 GitHub gist", PiCommandSource.Builtin, null, PiCommandAction.TerminalOnly),
    PiSlashCommand("copy", "复制最后一条模型消息", PiCommandSource.Builtin, null, PiCommandAction.CopyLastAssistant),
    PiSlashCommand("name", "设置会话显示名称", PiCommandSource.Builtin, null, PiCommandAction.RenameSession),
    PiSlashCommand("session", "查看会话信息与统计", PiCommandSource.Builtin, null, PiCommandAction.SessionStats),
    PiSlashCommand("changelog", "查看更新日志", PiCommandSource.Builtin, null, PiCommandAction.TerminalOnly),
    PiSlashCommand("hotkeys", "查看全部快捷键", PiCommandSource.Builtin, null, PiCommandAction.TerminalOnly),
    PiSlashCommand("fork", "从某条历史消息创建分支", PiCommandSource.Builtin, null, PiCommandAction.PickFork),
    PiSlashCommand("clone", "在当前节点复制整个会话", PiCommandSource.Builtin, null, PiCommandAction.CloneSession),
    PiSlashCommand("trust", "保存项目信任决定", PiCommandSource.Builtin, null, PiCommandAction.TerminalOnly),
    PiSlashCommand("login", "配置 provider 认证", PiCommandSource.Builtin, null, PiCommandAction.TerminalOnly, "<provider>"),
    PiSlashCommand("logout", "移除 provider 认证", PiCommandSource.Builtin, null, PiCommandAction.TerminalOnly),
    PiSlashCommand("new", "新建会话", PiCommandSource.Builtin, null, PiCommandAction.NewSession),
    PiSlashCommand("compact", "手动压缩会话上下文", PiCommandSource.Builtin, null, PiCommandAction.Compact),
    PiSlashCommand("resume", "切换到另一个会话", PiCommandSource.Builtin, null, PiCommandAction.OpenSessions),
    PiSlashCommand("reload", "重载快捷键、扩展、技能、模板、主题与上下文文件", PiCommandSource.Builtin, null, PiCommandAction.TerminalOnly),
    PiSlashCommand("quit", "退出 pi（本 App 由前台服务托管引擎）", PiCommandSource.Builtin, null, PiCommandAction.TerminalOnly),
)

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
 */
fun piCommandPalette(
    commands: List<PiResponses.SlashCommand>,
    builtins: List<PiSlashCommand> = PI_BUILTIN_SLASH_COMMANDS,
): List<PiSlashCommand> {
    val taken = builtins.mapTo(HashSet()) { it.name }
    val fromPi = commands.mapNotNull { command ->
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
            action = PiCommandAction.Prompt,
        )
    }
    return builtins + fromPi
}

/**
 * pi's autocomplete provenance tag, reproduced from
 * `interactive-mode.ts` `getAutocompleteSourceTag`:
 *
 *  - scope `user` → `u`, `project` → `p`, anything else (temporary) → `t`;
 *  - source `auto`/`local`/`cli` adds nothing;
 *  - `npm:<name>` becomes `u:npm:<name>`;
 *  - a git URL becomes `u:git:<host>/<path>[@ref]`.
 *
 * The tag is shown because `source` alone (`extension`/`prompt`/`skill`) cannot
 * tell a user's own template from one inside an npm package — the distinction
 * pi's own UI makes and the one a user needs when deciding whether to trust a
 * command (audit §5.12).
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
