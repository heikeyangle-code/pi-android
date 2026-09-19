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

    // ------------------------------------------------------------------ update
    //
    // `pi update --extensions` / `pi update <来源>`. The sentences below exist
    // because pi's own output cannot answer the two questions an update button
    // raises: it prints one and the same `Updated …` line whether or not anything
    // moved, and there is no "is there a newer version?" query on this path at all.
    // The file:line evidence lives in [PiPackageUpdate]'s KDoc and in the ledgers,
    // **not** on screen — the note above forbids doc paths and design rationale in
    // strings. A screen that showed pi's line as if it meant "something new was
    // installed" is the "the UI says it can, and it cannot" shape this package keeps
    // paying for.

    const val UPDATE_ALL_LABEL = "全部更新"
    const val UPDATE_LABEL = "更新"

    /** What the update command really does, and the question it cannot answer. */
    const val UPDATE_NOTE =
        "更新由 pi 自己执行：非精确版本的 npm 包只在新版本时才重新安装，精确版本会被跳过，" +
            "git 来源会重新检出。无论有没有真的改动，pi 都只回答一句「已更新」，" +
            "它不报告有没有新版本，本应用也查不到。"

    /** Shown instead of the button when nothing installed can be changed. */
    const val UPDATE_NOTHING_TO_UPDATE =
        "没有可更新的包：精确版本的 npm 包与本地路径不会被更新命令改动。"

    /** Per-row reason a package gets no 更新 button. */
    const val UPDATE_SKIP_PINNED = "精确版本：更新会跳过它，换版本请改来源后重新安装。"
    const val UPDATE_SKIP_LOCAL = "本地路径：没有可拉取的内容，更新不会改动它。"

    /** pi's positional `self`/`pi` means pi itself; the app never emits that. */
    const val UPDATE_REFUSED_SELF =
        "「self」与「pi」在 pi 里指更新 pi 自己，那会换掉本应用钉住的引擎版本，所以这里不提供。" +
            "要更新资源包请用「全部更新」，或点某个包的「更新」。"

    // --------------------------------------------------- built in vs installed
    //
    // E7 (`docs/known-gaps.md` §E): the screen must say which rows the app shipped
    // itself and which arrived another way. pi has no such distinction — the evidence
    // (`package-manager.ts:2352-2362`, `:2470-2475`; `pi list` reads only settings'
    // `packages`, `package-manager-cli.ts:970-1002`) lives in [PiBuiltinExtension]'s
    // KDoc and in the ledgers, **not** on screen: the file-level note above forbids doc
    // paths and design rationale in strings.
    //
    // **That distinction is a per-row origin badge now, not a section title.** It used
    // to be two titles — 随 App 提供的扩展（不能用 pi 卸载） / 其他扩展（不是随 App 安装的）
    // — and a title that says 其他 tells the reader the rows under it are the leftovers,
    // while every one of them is an extension pi loads. One section per kind with the
    // origin on the row (`App 自带` / `全局`) keeps every fact and ranks nothing: the
    // category is the title, the provenance is the badge.

    const val EXTENSIONS_TITLE = "扩展"

    /**
     * The one fact the old title carried that a badge cannot: the shipped extensions
     * cannot be removed through pi (`pi list` never shows them, so `pi remove <name>`
     * answers `No matching package found`, `package-manager-cli.ts:959-966`). It stays
     * on screen as the section's note instead of as a title qualifier.
     */
    const val EXTENSIONS_NOTE =
        "「App 自带」的三个扩展是应用自己带进来的，pi 卸载不了它们；" +
            "其余扩展是磁盘上 extensions/ 目录里的，pi 启动时会加载。"

    const val EXTENSIONS_EMPTY =
        "还没有发现任何扩展。要加一个，把 .ts 或 .js 放进 extensions/（或放一个带入口的目录）。"

    const val LIST_SECTION_TITLE = "已安装的资源包"

    const val DISCOVERED_PRESENCE = "pi 会加载它"

    /**
     * 一类资源排在卡里的行数超过 `PiSettingsCollapseAbove` 时，收敛成一条动作：
     * `显示全部 14 个` ↔ `收起`。「主题」实机就是 14 行，整段铺出来会把「已安装的资源包」
     * 整个推到屏外。措辞与设置面同族的那条动作（模型屏的厂商卡）一致：说清**还剩多少**，
     * 不说「更多」。
     */
    fun showAllResources(count: Int): String = "显示全部 $count 个"

    const val COLLAPSE_RESOURCES = "收起"

    fun resourceKind(kind: PiResourceDiscovery.Kind): String = when (kind) {
        PiResourceDiscovery.Kind.Skills -> "技能"
        PiResourceDiscovery.Kind.Prompts -> "提示模板"
        PiResourceDiscovery.Kind.Themes -> "主题"
    }

    /**
     * 一类资源的空态（**不是**「读不到」）。
     *
     * 这一页的读取器是 `PiResourceDiscovery`：目录不在、或目录里没有匹配的文件，都返回空表，
     * 而且两种情况的空表是**同一个答案**。所以这里只能说「还没有发现任何 X」，不能写「读不到」
     * —— 那会是一个这一屏拿不到的读数（07 的「四种读数不许互相冒充」）。句子顺带说清这一类
     * 长什么样、放哪里，因为空态是读者唯一一次需要知道这件事的时候。
     */
    fun resourceEmpty(kind: PiResourceDiscovery.Kind): String = when (kind) {
        PiResourceDiscovery.Kind.Skills ->
            "还没有发现任何技能。一个技能就是 skills/ 下的一个目录，目录里有 SKILL.md。"

        PiResourceDiscovery.Kind.Prompts ->
            "还没有发现任何提示模板。模板是 prompts/ 下的 .md，正文里可以带 \$1、\$@ 这类参数。"

        PiResourceDiscovery.Kind.Themes ->
            "还没有发现任何主题。主题是 themes/ 下的 .json。"
    }

    /**
     * One row's **origin badge**, in two voices: the app's own word (`项目 ` / `全局`) in the
     * UI face, the machine-produced half (`.pi`) in the mono face. Without the mono half the
     * one path-shaped origin on the screen would be drawn in the wrong voice.
     *
     * The spellings are the workspace screen's, verbatim
     * (`ui/screens/WorkspaceResources.kt`: `项目 ` + `.pi`, `全局`, `包 · ` + name):
     * both screens list the same resources, so an origin must have one spelling. This
     * page used to say 「（当前工作区）」 where the workspace screen said 「项目 .pi」 for
     * the same directory.
     */
    data class OriginBadge(val text: String, val mono: String? = null)

    /** The extensions the APK ships. */
    val ORIGIN_SHIPPED = OriginBadge("App 自带")

    /** `<workspace>/.pi/<kind>` — the root pi resolves first. */
    val ORIGIN_PROJECT = OriginBadge("项目 ", ".pi")

    /** The engine's agent dir (`~/.pi/agent/<kind>`). */
    val ORIGIN_GLOBAL = OriginBadge("全局")

    /**
     * 自动发现的那三类资源用哪一枚来源徽标。
     *
     * 包作用域走不到这里：包里的资源由**那一张包卡**承担来源（卡头就是这个包的来源与安装
     * 路径），每一行再印一遍包名正是这一轮删掉的那种重复。所以这一支只是兜底。
     */
    fun resourceOriginBadge(scope: PiResourceDiscovery.Found.Scope): OriginBadge = when (scope) {
        PiResourceDiscovery.Found.Scope.Global -> ORIGIN_GLOBAL
        PiResourceDiscovery.Found.Scope.Project -> ORIGIN_PROJECT
        PiResourceDiscovery.Found.Scope.Package -> OriginBadge("包")
    }

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
