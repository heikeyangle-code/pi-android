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

    /**
     * `/export [path]`. The format follows the argument's extension, the way pi's
     * own TUI does it (`interactive-mode.ts:6062-6066`): `.jsonl` goes to the
     * app-side JSONL writer, anything else to `export_html`.
     */
    ExportSession,
    CopyLastAssistant,
    RenameSession,
    SessionStats,
    NewSession,
    Compact,
    OpenSessions,

    /**
     * `/scoped-models`. pi opens its **model-scope selector** for this command
     * (`interactive-mode.ts:2975-2978` → `showModelsSelector()`, `:5024`), which
     * is a different component from the plain `/model` picker
     * (`showModelSelector`, `:4987`) and what it toggles is persisted as
     * `settings.enabledModels` (`settings-manager.ts:1316-1326`).
     *
     * Unlike [TerminalOnly] this has a real destination in the app: the
     * `enabledModels` row is a settings row (`PiSettingsRegistry.kt:355`), so the
     * GUI navigates there and highlights it (`NavRequest.SettingsFocus`). Marking
     * it [TerminalOnly] — as it was — made the palette say 「仅终端」 about a
     * feature the app can actually reach.
     */
    OpenModelScope,

    /**
     * A built-in whose implementation lives in pi's interactive shell and has
     * **no RPC counterpart**: pi exposes no command for it and `prompt()`
     * cannot reach it (built-ins are filtered out of `get_commands` and
     * `prompt` only dispatches extension commands).
     *
     * The GUI must not fake it. What it says instead depends on
     * [PiSlashCommand.appLanding]: when this app has its own way to reach the
     * same outcome, the row names that way; otherwise it says there is no entry
     * here. It must **not** send the user to the terminal tab — the terminal is
     * not a usable surface, so "go there" would name an action that cannot be
     * completed.
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
    /**
     * How this app itself reaches the same outcome, as a **user-visible
     * location** (a screen path, never a file path or a class name). Null when
     * the app has no such way.
     *
     * This exists because a pi built-in can be unreachable over RPC while the app
     * still does the same thing somewhere else. `/trust` is the clearest case:
     * `rpc-types.ts:20-74` has no trust command, yet the app reproduces pi's whole
     * trust decision — same five options, same `trust.json` record — in the
     * packages screen (`packages/ProjectTrust.kt`, `packages/PiProjectTrustPrompt.kt`).
     * `/reload` is the other: restarting the engine re-reads
     * `settings.json` and rescans every resource directory, which is what the
     * user was actually asking for. Before this field, both rows said
     * 「仅终端」, i.e. the palette denied a capability the app already had.
     */
    val appLanding: String? = null,
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
        "model", "选择模型", PiCommandSource.Builtin, null,
        PiCommandAction.PickModel, "<provider/model>",
    ),
    // The description must not promise pi's in-place leaf move: RPC has no such
    // command (`rpc-types.ts:20-74`), and what this app offers from the tree is a
    // fork that writes a new session file.
    PiSlashCommand(
        "tree", "浏览会话树，从某条消息分叉", PiCommandSource.Builtin, null,
        PiCommandAction.OpenTree,
    ),
    PiSlashCommand(
        "thinking", "设置思考等级", PiCommandSource.Builtin, null,
        PiCommandAction.PickThinking, "<level>",
    ),
    PiSlashCommand(
        "scoped-models", "设置循环切换的模型范围", PiCommandSource.Builtin, null,
        PiCommandAction.OpenModelScope,
    ),
    PiSlashCommand(
        "export", "导出会话：默认 HTML，路径以 .jsonl 结尾时写 JSONL", PiCommandSource.Builtin, null,
        PiCommandAction.ExportSession,
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
    // `/trust` has no RPC command, but the app reaches the same outcome itself:
    // pi's five trust options and its `trust.json` record are reproduced in the
    // packages screen (`packages/ProjectTrust.kt` + `packages/PiProjectTrustPrompt.kt`).
    PiSlashCommand(
        "trust", "保存项目信任决定", PiCommandSource.Builtin, null,
        PiCommandAction.TerminalOnly,
        appLanding = "设置 → 扩展 → 扩展包与项目信任",
    ),
    // `/login` and `/logout` are only half-reachable: the app owns an API-key form,
    // while subscription (OAuth) login stays in pi's own interactive shell with no
    // RPC channel at all. The row therefore names the half the app does have and
    // says nothing about the other half — `notifyTerminalOnly` carries the part the
    // palette cannot fit.
    PiSlashCommand(
        "login", "配置 provider 认证（本应用只支持 API Key）", PiCommandSource.Builtin, null,
        PiCommandAction.TerminalOnly, "<provider>",
        appLanding = "设置 → 模型与推理 → 凭证",
    ),
    PiSlashCommand(
        "logout", "移除 provider 认证", PiCommandSource.Builtin, null,
        PiCommandAction.TerminalOnly,
        appLanding = "设置 → 模型与推理 → 凭证",
    ),
    PiSlashCommand("new", "新建会话", PiCommandSource.Builtin, null, PiCommandAction.NewSession),
    PiSlashCommand("compact", "手动压缩会话上下文", PiCommandSource.Builtin, null, PiCommandAction.Compact),
    PiSlashCommand("resume", "切换到另一个会话", PiCommandSource.Builtin, null, PiCommandAction.OpenSessions),
    // pi's `/reload` rebinds the whole runtime in place; there is no RPC command for
    // it (`rpc-types.ts:20-74`). Restarting the engine is not the same mechanism, but
    // it is the same user-visible outcome for what `/reload` is used for here:
    // `pi --mode rpc` is started again, so pi re-reads `settings.json` and rescans
    // extensions, skills, prompts and themes (`core/agent-session-runtime.ts:226-252`
    // → `createRuntime`). The one difference the user can feel — a running turn is
    // interrupted — is what the restart dialog itself states before doing anything.
    PiSlashCommand(
        "reload", "重载扩展、技能、模板、主题与上下文文件", PiCommandSource.Builtin, null,
        PiCommandAction.TerminalOnly,
        appLanding = "设置 → 运行时与诊断 → 进程 → 重启引擎",
    ),
    PiSlashCommand("quit", "退出 pi", PiCommandSource.Builtin, null, PiCommandAction.TerminalOnly),
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
    builtins: List<PiSlashCommand> = PI_BUILTIN_SLASH_COMMANDS,
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
