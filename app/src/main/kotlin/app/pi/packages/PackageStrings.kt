package app.pi.packages

/**
 * Every Chinese string this layer shows, in one place.
 *
 * ## Three rules about what may appear on screen
 *
 *  - **pi's own words are never translated.** A trust option keeps pi's label
 *    (`Trust parent folder (/workspace)`, `Trust (this session only)`) and a pi
 *    error keeps pi's message, because the user will meet the same strings in pi's
 *    TUI and its docs, and because a translated error cannot be searched for.
 *  - **The app's Chinese is additive, not substitutive.** Where pi is silent — the
 *    project-resource skip — the app supplies the sentence pi did not
 *    (`docs/extension-compatibility.md:574-575`).
 *  - **No documentation, and no unasked-for explanation.** A user-facing string must
 *    not carry a doc path, a file name, a section marker such as the § used in these
 *    ledgers, or a rationale for how the app is built. Those belong in code comments
 *    like this one. A string that explains the design instead of telling the user
 *    what he needs *now* is noise, and noise is a defect: the two-line "git is not in
 *    this runtime, see known-gaps.md §K2" note this file used to hold was removed for
 *    exactly that reason, and a shipped feature is the fix — not a caption about it.
 *
 * Comments are English; the strings are the product surface and are Chinese.
 */
object PackageStrings {

    // ------------------------------------------------------------------ screen

    const val TITLE = "扩展包与项目信任"

    const val INSTALL_LABEL = "安装"
    const val REMOVE_LABEL = "移除"
    const val REFRESH_LABEL = "刷新列表"
    const val CANCEL = "取消"

    /**
     * The install field's label. It lists the three forms that work here.
     *
     * `git:` is back now that the runtime ships git (`RuntimeProvisioner.installGit`).
     * It was absent while git was, and the spelling offered is `git:host/user/repo@ref`
     * rather than `git@host:repo` on purpose: only the `https://` transport is
     * shipped, and the scp-style and `ssh://` spellings would need an ssh client that
     * is not.
     */
    const val SPEC_HINT = "npm:@scope/name@1.0.0、git:github.com/user/repo@v1 或 /绝对路径"

    // The choice is "which projects does this apply to", so that is what the labels
    // say. Naming the settings document told the user where a file lives, which is
    // not the decision he is making, and it is internal vocabulary besides.
    const val SCOPE_USER = "所有项目（全局）"
    const val SCOPE_PROJECT = "仅当前项目"

    const val SCOPE_PROJECT_LOCKED = "项目作用域需要先信任这个项目。"

    const val NO_PACKAGES = "还没有安装任何资源包。"
    /**
     * Prefix for "the list could not be fetched". The **reason** is appended from
     * [PiPackageService.Listing.notReady], which names the actual cause (runtime
     * missing, engine not installed). Stating a generic reason here as well printed
     * the same fact twice on screen.
     */
    const val LIST_NOT_READY_PREFIX = "列表没有取到——"
    const val PROJECT_PACKAGES_HIDDEN =
        "项目声明了资源包，但因为项目未获信任，这次列表没有显示。先处理项目信任，再刷新。"
    const val LIST_UNPARSED = "列表输出无法解析，下方原样显示。"

    const val RUNNING = "正在执行…"

    // --------------------------------------------------- built in vs installed
    //
    // E7 (`docs/known-gaps.md` §E): the screen must say which rows the app shipped
    // itself and which the user installed. pi has no such distinction — the evidence
    // (`package-manager.ts:2352-2362`, `:2470-2475`; `pi list` reads only settings'
    // `packages`, `package-manager-cli.ts:970-1002`) lives in [PiBuiltinExtension]'s
    // KDoc and in the ledgers, **not** on screen: the file-level note above forbids doc
    // paths and design rationale in strings. The built-in rows are marked by the title
    // alone, which is all the user needs.

    const val BUILTIN_TITLE = "随 App 提供的扩展（不能用 pi 卸载）"

    const val LIST_SECTION_TITLE = "已安装的资源包"

    /**
     * Extensions found in the agent's extensions directory that the app did not
     * install. They are not packages, so `pi list` says nothing about them; the fact
     * worth stating is that pi does load them, which is what was invisible.
     */
    const val DISCOVERED_TITLE = "其他扩展（不是随 App 安装的）"

    const val DISCOVERED_PRESENCE = "pi 会加载它"

    /**
     * Skills, prompt templates and themes found on disk. Same reasoning as
     * [DISCOVERED_TITLE]: pi loads them by looking at a directory, so neither the
     * shipped list nor `pi list` mentions them.
     */
    const val RESOURCES_TITLE = "其他资源（不是随 App 安装的）"

    fun resourceKind(kind: PiResourceDiscovery.Kind): String = when (kind) {
        PiResourceDiscovery.Kind.Skills -> "技能"
        PiResourceDiscovery.Kind.Prompts -> "提示模板"
        PiResourceDiscovery.Kind.Themes -> "主题"
    }

    /**
     * Where one scanned resource was found.
     *
     * `Project` says 当前工作区 rather than pi's literal `project` on purpose: every
     * other label on this screen (项目信任, 项目包, 本工作区) says 工作区, and one row
     * calling the same place by pi's wire word while the row above calls it 工作区 is
     * how two names for one thing get established. The scope value itself is still
     * pi's (`settings.json`'s project scope), only the noun is the app's.
     */
    fun resourceScope(scope: PiResourceDiscovery.Found.Scope): String = when (scope) {
        PiResourceDiscovery.Found.Scope.Global -> "（全局）"
        PiResourceDiscovery.Found.Scope.Project -> "（当前工作区）"
        PiResourceDiscovery.Found.Scope.Package -> "（资源包）"
    }

    /**
     * The origin of one scanned resource, package name first when there is one.
     *
     * A package's resources are the only ones whose *owner* matters to the reader:
     * "this theme comes from the package I installed" is the whole reason the row is
     * on screen, and 资源包 alone would not say which one.
     */
    fun resourceOrigin(found: PiResourceDiscovery.Found): String =
        found.packageName?.let { "（资源包：$it）" } ?: resourceScope(found.scope)

    /**
     * The section that shows what each **installed package** carries.
     *
     * Its title and its note both say 本应用自己扫的 because that is the one thing a
     * reader has to know here: `pi list` reports a package's spec and install path
     * and nothing inside it, so everything below is this app walking the same
     * directory pi walks (`core/package-manager.ts:2066-2072`). Presenting it as
     * pi's own report would be a claim about a list nobody received.
     */
    const val PACKAGE_RESOURCES_TITLE = "资源包里的资源（本应用自己扫的）"

    /**
     * The fourth kind a package can carry.
     *
     * Extensions are not a [PiResourceDiscovery.Kind]: pi collects them by a
     * different rule and [PiAutoExtensions] is that reader, so this is the word the
     * package card labels them with.
     */
    const val EXTENSION_KIND = "扩展"
    const val PACKAGE_RESOURCES_NOTE =
        "pi 会把每个包自己的 extensions / skills / prompts / themes 一起加载，但 RPC 里没有" +
            "任何一条命令会列出它们。这一区是本应用按 pi 的目录规则去扫每个包的安装目录得到的，" +
            "不是 pi 的报告 —— 扫到的不一定都已经生效（信任、过滤规则、引擎重启都会影响）。"
    const val PACKAGE_ROOT_UNKNOWN =
        "pi 没有报这个包的安装路径，按布局也推不出来，所以这个包里的资源列不出来。"
    const val PACKAGE_RESOURCES_EMPTY =
        "这个包的安装目录里没有 extensions / skills / prompts / themes。"

    /** One line per shipped extension: what it is for, in the app's words. */
    fun builtinPurpose(name: String): String = when (name) {
        "pi-android-bridge" -> "设备能力工具：读屏、点按、截屏、通知、剪贴板、导出…"
        "pi-android-permission-gate" -> "设备工具的授权确认：调用前先经确认"
        "pi-highlight" -> "代码高亮服务"
        else -> ""
    }

    /** The three presence states of [PiBuiltinExtension.Presence], as sentences. */
    fun builtinPresence(presence: PiBuiltinExtension.Presence): String = when (presence) {
        PiBuiltinExtension.Presence.EngineAgentDir -> "已安装：pi 会加载它"
        PiBuiltinExtension.Presence.RootfsCopyOnly -> "只装在了旧位置：pi 这次不会加载它"
        PiBuiltinExtension.Presence.Missing -> "未安装：对应的能力会缺失"
    }

    const val STDERR_TITLE = "错误输出（原样）"
    const val STDOUT_TITLE = "输出（原样）"
    const val COMMAND_TITLE = "执行的命令"

    const val TRUSTED = "已信任"
    const val UNTRUSTED = "未信任"
    const val NOT_RECORDED = "无记录"

    const val RESTART_NEEDED_TITLE = "需要重启引擎"
    const val RESTART_BUTTON = "重启引擎"
    const val RESTART_CONFIRM = "确认重启"
    const val RESTART_STAY = "暂不重启"
    const val RESTART_WAIT_TURN = "等待回合结束"
    const val RESTARTING = "正在重启引擎…"
    const val RESTARTING_NOTE = "预计 1–3 秒。当前正在跑的回合会被终止，会话内容不会丢失。"

    const val TRUST_ALREADY_TRUSTED =
        "这个项目已获信任，项目本地的扩展、技能、模板与设置会被加载。"

    const val TRUST_INVALID_TITLE = "信任记录无法解析"
    const val TRUST_INVALID_ACTION = "移开损坏文件并重建"
    const val TRUST_INVALID_NOTE =
        "pi 在读取到损坏的信任记录时会拒绝启动，这不是一个可以忽略的警告。" +
            "损坏的文件会被改名保留，不会被删除。"

    const val TRUST_SESSION_ONLY_NOTE =
        "「仅本次」不写入任何文件，只对这次操作生效。要长期生效，请选择会持久化的那一项。"

    /** pi's five choices, explained. Keyed by pi's own label prefix. */
    fun trustSubtitle(label: String): String = when {
        label == "Trust" ->
            // "执行扩展代码" is the load-bearing half of informed consent, and it is
            // what pi's own English line directly above this one says ("execute project
            // extensions", `project-trust.ts:24-26`): dropping it would make the Chinese
            // quieter than the prompt it explains.
            "此后 pi 会加载该项目的扩展、技能与设置，并执行项目里的扩展代码。"
        label.startsWith("Trust parent folder") ->
            "信任上一级目录，并删除本项目自己的条目。" +
                "此后这个父级下的任何项目都会被自动信任——请确认这是你要的粒度。"
        label.startsWith("Trust (this session only)") ->
            "仅本次操作放行，不写记录。"
        label == "Do not trust" ->
            "项目资源被忽略，而且不会再次询问。"
        label.startsWith("Do not trust (this session only)") ->
            "仅本次操作拒绝，下次仍会询问。"
        else -> ""
    }
}
