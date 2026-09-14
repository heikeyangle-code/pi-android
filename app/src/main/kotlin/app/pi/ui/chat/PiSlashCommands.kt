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

    /**
     * Built-ins this GUI implements natively.
     *
     * Three values used to sit at the head of this group and one at the tail:
     * `OpenSettings`, `PickModel` and `PickThinking` (deleted with their
     * `/settings`, `/model` and `/thinking` rows, [PI_BUILTIN_SLASH_COMMANDS] §B)
     * and `OpenModelScope` (deleted with `/scoped-models`, §C). Every one of them
     * opened a surface the user already has one gesture away, so the palette entry
     * was a duplicate door — the surfaces themselves are untouched.
     *
     * `TerminalOnly` is gone too. It marked a built-in with no RPC path whose
     * `appLanding` named the Settings row that does the job; the last five rows
     * carrying it were deleted under the same ruling, which left the value with no
     * reachable state at all.
     */
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
     * `/import <path.jsonl>`. pi's TUI asks for a path and hands it to
     * `runtimeHost.importFromJsonl` (`interactive-mode.ts:6107-6119`); a phone has
     * no path to type, so this opens the document picker and the ViewModel does the
     * rest (`PiSessionViewModel.importSession`). It has a real destination in this
     * app: `switch_session` already takes a session file path (`rpc-types.ts:61`),
     * so the picked file is copied into the session directory and adopted.
     */
    ImportSession,
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
    val action: PiCommandAction,
    val argumentHint: String? = null,
) {
    /** What the user types to invoke it. */
    val invocation: String get() = "/$name"
}

/**
 * pi's built-in slash commands, transcribed from `core/slash-commands.ts:20-42`
 * (the same array pi builds its own autocomplete from,
 * `interactive-mode.ts:636`).
 *
 * Order is pi's order and is not sorted on purpose: it is the order the user
 * sees in the original TUI, and a palette that reorders itself would make the
 * two surfaces feel like different products. The list is a **subsequence** of
 * pi's array: twelve rows are deliberately absent.
 *
 * ## The twelve rows that are not here
 *
 * Three groups, three reasons. Only group A is about a missing ability; the other
 * two are about the palette not being a second copy of the screens around it.
 *
 * ### A. Four abilities this platform cannot deliver at all
 *
 * `share` (`slash-commands.ts:27`), `changelog` (`:31`), `hotkeys` (`:32`) and
 * `quit` (`:42`) were rows whose tap could only produce a warning — there was no
 * landing and no RPC path. Each named an ability this platform has no path to:
 *
 *  - `share` is a secret GitHub gist written through the `gh` CLI
 *    (`interactive-mode.ts:3002`); there is no RPC command for it and no `gh` on
 *    Android. What the user actually wants — getting the session out of the app —
 *    is `export` plus the app's own share sheet (`ui/chat/SessionExportDelivery.kt`).
 *  - `changelog` (`:3022`) and `hotkeys` (`:3027`) render a TUI text panel:
 *    changelog entries come from pi's own update check, and a shortcut list has
 *    no meaning on a touch screen.
 *  - `quit` (`:3099`) shuts down pi's interactive process. Here the engine is
 *    owned by the app's foreground service, not by the transcript, so there is
 *    nothing for the GUI to quit.
 *
 * ### B. Three abilities the app already offers one gesture away
 *
 * `settings` (`:20`), `model` (`:21`) and `thinking` (`:23`) all work; they are
 * gone because each was a second door to something the screen behind the palette
 * already offers, and duplicate doors are what made the panel unreadable:
 *
 *  - `settings` → the bottom bar's 「设置」 destination (`PiRoot.kt`, `PiDestination.Settings`);
 *  - `model` → the top bar's model chip, which opens the same selector
 *    (`ChatScreen.kt`, `ModelChip` → `ChatSheet.Model`);
 *  - `thinking` → the composer's `◐` chip, which cycles the level and opens the
 *    same picker through the overflow menu.
 *
 * ### C. Five whose destination is a Settings row
 *
 * `scoped-models` (`:24`), `trust` (`:35`), `login` (`:36`), `logout` (`:37`) and
 * `reload` (`:41`) are gone under the same ruling, stated more strictly: a
 * palette row may not be a door into Settings. Tapping one of them never
 * navigated — it only pushed a notice naming the Settings row that does the job —
 * so the row was literally telling the user to go and do it themselves. The
 * outcomes remain reachable in Settings (项目信任策略 / 凭证 / 重启引擎 / 循环模型), and
 * the deletion takes `PiCommandAction.TerminalOnly` with it: with no row carrying
 * a destination, the action had no reachable state left.
 *
 * This is a **palette** decision, not a capability decision. pi still has all
 * twelve commands and this app still reaches the outcomes; what is gone is the
 * duplicate row — and the names are kept in [PI_UNLISTED_BUILTIN_COMMANDS] so a
 * user who types one by hand gets a true sentence instead of "no such command".
 * See `T7` in the delivery notes.
 */
val PI_BUILTIN_SLASH_COMMANDS: List<PiSlashCommand> = listOf(
    // pi: "Navigate session tree (switch branches)" — translated literally. The
    // earlier wording added 「从某条消息分叉」, which is what *this app's* tree
    // screen offers (a fork that writes a new session file) and not what pi's
    // string says. What this row promises is pi's: navigation of the session
    // tree. The mechanism behind it is the app's, and it is not promised here.
    PiSlashCommand(
        "tree", "浏览会话树（切换分支）", PiCommandSource.Builtin, null,
        PiCommandAction.OpenTree,
    ),
    // pi: "Enable/disable models for Ctrl+P cycling". The mobile wording names the
    // setting row instead of the keybinding — `Ctrl+P` does not exist here and the
    // toggle that the command addresses is the same one, `enabledModels`
    // (`settings-manager.ts:1316-1326`, row at `PiSettingsRegistry.kt:339`) — but
    // both halves of pi's promise stay: it is a list of models that are *enabled
    // or disabled*, and it is the set that cycling uses.
    //
    // Group C: not listed, because its destination *is* a settings row and this
    // palette must not be a set of doors into Settings. `enabledModels` is
    // reachable at 设置 → 模型与推理 → 循环模型.
    PiSlashCommand(
        "export", "导出会话：默认 HTML，路径以 .jsonl 结尾时写 JSONL", PiCommandSource.Builtin, null,
        PiCommandAction.ExportSession,
    ),
    // No `argumentHint`: a hint suppresses the palette tap's "run it" behaviour
    // (`ChatScreen.pick` is only reached when `argumentHint == null`), and there is
    // no path for a phone user to type — the tap *is* the command.
    PiSlashCommand(
        "import", "从 JSONL 文件导入并恢复会话", PiCommandSource.Builtin, null,
        PiCommandAction.ImportSession,
    ),
    // pi: "Copy last agent message to clipboard". 回复, not 消息: pi copies the last
    // *agent* message, and this app's own wording for that object elsewhere is
    // 「最后一条回复」 (the overflow row, `ChatScreen.kt`), so the two entries for
    // the same action read the same.
    PiSlashCommand("copy", "复制最后一条回复到剪贴板", PiCommandSource.Builtin, null, PiCommandAction.CopyLastAssistant),
    PiSlashCommand("name", "设置会话显示名称", PiCommandSource.Builtin, null, PiCommandAction.RenameSession),
    PiSlashCommand("session", "查看会话信息与统计", PiCommandSource.Builtin, null, PiCommandAction.SessionStats),
    PiSlashCommand("fork", "从某条历史消息创建分支", PiCommandSource.Builtin, null, PiCommandAction.PickFork),
    PiSlashCommand("clone", "在当前节点复制整个会话", PiCommandSource.Builtin, null, PiCommandAction.CloneSession),
    // `/trust`, `/login`, `/logout` and `/reload` used to sit here as rows whose
    // tap only produced a notice pointing at a Settings row. Group C: gone. The
    // notice never navigated, so the palette was telling the user to go and do it
    // themselves. The outcomes are still reachable, in Settings:
    // 项目信任策略 (安全与信任), 凭证 (模型与推理), 重启引擎 (运行时与诊断).
    PiSlashCommand("new", "新建会话", PiCommandSource.Builtin, null, PiCommandAction.NewSession),
    PiSlashCommand("compact", "手动压缩会话上下文", PiCommandSource.Builtin, null, PiCommandAction.Compact),
    PiSlashCommand("resume", "切换到另一个会话", PiCommandSource.Builtin, null, PiCommandAction.OpenSessions),
)

/**
 * The other twelve of pi's twenty-three built-ins: the ones this palette does
 * **not** list, and the sentence the app should give a user who types one by
 * hand.
 *
 * `PI_BUILTIN_SLASH_COMMANDS` names eleven and this map names twelve; together
 * they are exactly `core/slash-commands.ts:20-42`. Both directions matter: the
 * eleven are rows, and these twelve are the ones a user can still type because
 * they remember them from pi. Without this table the app would answer `/trust`
 * with the "that is not a command" message, which is false — pi has it.
 *
 * ## How `ChatScreen` uses it (agreed contract)
 *
 * The `ComposerRoute.Unknown` branch in `ChatScreen.kt` used to tell the user to
 * drop the leading `/` and send the text as prose. That is the right answer only
 * for a genuine typo, and it is exactly wrong for a name pi implements. The branch
 * now looks a name up first and only falls through when there is no entry:
 *
 * ```kotlin
 * is ComposerRoute.Unknown -> piCommandWithoutEntry(route.name)
 *     ?.let { session.notifyUser(it) }
 *     ?: session.notifyUnknownCommand(route.name)
 * ```
 *
 * **`piCommandWithoutEntry` is a second copy of this table**, private to
 * `ChatScreen.kt`. It was written in parallel with this one and the two must not
 * both survive: they are the same twelve names and the same nine sentences, so the
 * first time pi's built-in list changes they will disagree silently. This is the
 * copy to keep — it lives beside [PI_BUILTIN_SLASH_COMMANDS], whose names are the
 * complement, and the two together are checkable against
 * `core/slash-commands.ts:20-42`. The caller should become:
 *
 * ```kotlin
 * is ComposerRoute.Unknown -> unlistedBuiltinHint(route.name)
 *     ?.let { session.notifyUser(it) }
 *     ?: session.notifyUnknownCommand(route.name)
 * ```
 *
 * The map is public and immutable, and [unlistedBuiltinHint] is the lookup entry
 * point; both are stable. The three entries that name a surface do so because the
 * user ruled that the *palette* should not be a second door to a screen that is
 * already on the chat page — they are not navigation targets, so nothing here
 * needs to be wired to a `NavRequest`. The other nine say only that pi has the
 * command and this app has no entry for it: **do not** turn them into Settings
 * paths. That is precisely the shape that was deleted (a row that tells the user
 * to go and configure something themselves), and re-adding it here would put it
 * back on a different surface.
 */
val PI_UNLISTED_BUILTIN_COMMANDS: Map<String, String> = mapOf(
    // Group B in [PI_BUILTIN_SLASH_COMMANDS]: the ability has a direct surface on
    // this screen, so the sentence names it instead of denying it.
    "settings" to "pi 有 /settings；本应用用底栏的「设置」进入，命令面板不再单列。",
    "model" to "pi 有 /model；本应用用顶栏的模型按钮打开选择器，命令面板不再单列。",
    "thinking" to "pi 有 /thinking；本应用用输入框的 ◐ 切换思考等级，命令面板不再单列。",
    // Group C: deleted so the palette stops pointing at Settings. The sentence
    // states that honestly and stops there — no path, no "go and do it".
    "scoped-models" to "pi 有 /scoped-models；本应用没有对应入口。",
    "trust" to "pi 有 /trust；本应用没有对应入口。",
    "login" to "pi 有 /login；本应用没有对应入口。",
    "logout" to "pi 有 /logout；本应用没有对应入口。",
    "reload" to "pi 有 /reload；本应用没有对应入口。",
    // Group A: this platform cannot deliver the ability at all.
    "share" to "pi 有 /share；本应用没有对应入口。",
    "changelog" to "pi 有 /changelog；本应用没有对应入口。",
    "hotkeys" to "pi 有 /hotkeys；本应用没有对应入口。",
    "quit" to "pi 有 /quit；本应用没有对应入口。",
)

/**
 * The sentence for a pi built-in this palette does not list, or null when [name]
 * is not one of pi's unlisted built-ins.
 *
 * [name] is what was typed after the `/`, with the slash already removed —
 * `ComposerRoute.Unknown.name` is exactly that. A leading `/` is tolerated anyway
 * so a caller cannot get it wrong, and the match is case-insensitive because this
 * is a courtesy message and never a dispatch: `/Trust` deserves the same true
 * sentence as `/trust` rather than "no such command".
 */
fun unlistedBuiltinHint(name: String): String? =
    PI_UNLISTED_BUILTIN_COMMANDS[name.trim().removePrefix("/").lowercase()]

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
