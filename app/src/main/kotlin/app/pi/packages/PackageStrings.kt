package app.pi.packages

/**
 * Every Chinese string this layer shows, in one place.
 *
 * Two rules, both about not laundering pi's behaviour into something friendlier
 * than it is:
 *
 *  - **pi's own words are never translated.** A trust option keeps pi's label
 *    (`Trust parent folder (/workspace)`, `Trust (this session only)`) and a pi
 *    error keeps pi's message, because the user will meet the same strings in pi's
 *    TUI and its docs, and because a translated error cannot be searched for.
 *  - **The app's Chinese is additive, not substitutive.** Where pi is silent — the
 *    project-resource skip — the app supplies the sentence pi did not
 *    (`docs/extension-compatibility.md:574-575`).
 *
 * Comments are English; the strings are the product surface and are Chinese.
 */
object PackageStrings {

    // ------------------------------------------------------------------ screen

    const val TITLE = "扩展包与项目信任"
    const val SUBTITLE = "pi 没有把包管理和 /reload 放进 RPC 协议，所以这些操作由 App 在 guest 里执行 pi 自己的命令行。"

    const val INSTALL_LABEL = "安装"
    const val REMOVE_LABEL = "移除"
    const val REFRESH_LABEL = "刷新列表"
    const val CANCEL = "取消"
    const val CONFIRM = "确定"

    /**
     * The install field's label. It used to advertise `git:github.com/user/repo@v1`
     * as well, which the app cannot honour: `docs/known-gaps.md` §K2 established that
     * the runtime has no git — `runtime.lock.json`'s artifacts are proot / libtalloc /
     * libandroidShmem / ubuntuBase / node / ripgrep / fd, and `RuntimeProvisioner`
     * installs or links none. pi *can* install a git source, so the option is not
     * removed from pi's parser; it just cannot work here yet, and the field says so
     * instead of promising it ([SPEC_GIT_UNAVAILABLE]).
     */
    const val SPEC_HINT = "npm:@scope/name@1.0.0 或 /绝对路径"

    /**
     * The correction, on its own line so it is readable rather than clipped. Two
     * facts, because either alone is misleading: pi supports git sources, and this
     * runtime does not have git to give it.
     */
    const val SPEC_GIT_UNAVAILABLE =
        "git:… 源暂时装不了：pi 支持它，但这个运行时里没有 git（docs/known-gaps.md §K2）。" +
            "要装上得先把它加进 runtime 资产与 provisioner。"

    const val SCOPE_USER = "全局（~/.pi/agent/settings.json）"
    const val SCOPE_PROJECT = "项目（.pi/settings.json）"

    const val SCOPE_PROJECT_LOCKED =
        "项目作用域需要先信任这个项目：pi 会拒绝写入未信任项目的包配置" +
            "（package-manager-cli.ts:936-940）。"

    const val NO_PACKAGES = "pi list 报告没有已安装的资源包。"
    const val LIST_NOT_READY =
        "pi list 根本没有执行（运行时或引擎未就绪），所以这不是「没有包」，而是「没跑成」："
    const val PROJECT_PACKAGES_HIDDEN =
        "注意：项目的 .pi/settings.json 里声明了资源包，但这次列表没有显示它们——" +
            "项目未获信任时 pi 会把整个项目文档当成空的（settings-manager.ts:405-408），" +
            "而 pi list 仍然以退出码 0 结束，什么都不会说。先处理项目信任，再刷新列表。"
    const val LIST_UNPARSED =
        "pi list 的输出无法解析（下方原样显示）。这不会被当成「没有包」——那正是会骗人的地方。"

    const val RUNNING = "正在执行…"
    const val TIMEOUT_NOTE =
        "命令超时已被终止。它可能已经写入了部分文件，请先刷新列表再决定下一步。"

    // --------------------------------------------------- built in vs installed
    //
    // E7 (`docs/known-gaps.md` §E): the screen must say which rows the app shipped
    // itself and which the user installed. pi has no such distinction — see
    // [PiBuiltinExtension] — so every sentence below states where the app's label
    // comes from instead of implying pi reported it.

    const val BUILTIN_TITLE = "随 App 内置的扩展（不能通过 pi 卸载）"

    const val BUILTIN_NOTE =
        "pi 没有「内置扩展」这个概念：<agentDir>/extensions/ 下的一切都被它当成自动发现的用户扩展" +
            "（package-manager.ts:2352-2362、:2470-2475），和用户自己放进去的文件无法区分；" +
            "pi list 也只读 settings.json 的 packages（package-manager-cli.ts:970-1002），" +
            "所以它从来不会列出下面这几个。「内置」这个标注来自 App 自己的资产清单" +
            "（app/src/main/assets/pi-extensions/，由 DeviceBridgeController 拷进 extensions/），" +
            "不是 pi 报告的。"

    const val BUILTIN_UNINSTALLABLE =
        "卸载：pi remove 对这些名字没有用——它们不在 settings.json 的 packages 里，" +
            "pi 会回 No matching package found 并以退出码 1 结束（package-manager-cli.ts:959-966）。" +
            "所以这里不提供移除按钮。顺带一句相反的坑：把文件删掉也不会让 pi 感知到什么，" +
            "而资产安装闸门是内容指纹，标记一致时整棵扩展树都会被跳过（DeviceBridgeController.kt:281-285），" +
            "所以删掉的内置扩展不会自动回来。"

    const val LIST_SECTION_TITLE = "settings.json 里的资源包（pi list 的全部内容）"

    const val LIST_SECTION_NOTE =
        "这些是 pi install 写进 settings.json 的 packages 数组的条目，也是 pi list 唯一会列的东西" +
            "（package-manager-cli.ts:970-1002 读 package-manager.ts:977-1003）。" +
            "它们和上面的内置扩展是两套东西：装包不会替换内置扩展，卸载它们也不影响内置扩展。"

    /** One line per shipped extension: what it is for, in the app's words. */
    fun builtinPurpose(name: String): String = when (name) {
        "pi-android-bridge" -> "设备能力工具（android_* 一整套）：读屏、点按、截屏、通知、剪贴板、导出…"
        "pi-android-permission-gate" -> "设备工具的授权闸门：android_* 调用先经确认；没有 UI 时默认拒绝"
        "pi-highlight" -> "代码高亮服务（渲染层走它）"
        else -> ""
    }

    /** The three presence states of [PiBuiltinExtension.Presence], as sentences. */
    fun builtinPresence(presence: PiBuiltinExtension.Presence): String = when (presence) {
        PiBuiltinExtension.Presence.EngineAgentDir ->
            "已在引擎读的 agent 目录里：pi 会加载它"
        PiBuiltinExtension.Presence.RootfsCopyOnly ->
            "只在 rootfs 副本里：引擎读的是被绑定的那个目录，所以这次 pi 不会加载它"
        PiBuiltinExtension.Presence.Missing ->
            "两个可能的目录里都没有：pi 不会加载它，对应的能力会缺失"
    }

    /**
     * The two agent directories, printed together on purpose.
     *
     * The engine binds `PiPaths.agentDir` over guest `/root/.pi/agent`
     * (`PiEngineHost.kt:285-294`) while [GuestCommand] binds only the workspace
     * (`GuestCommand.kt:98-106`), so a host path printed alone would be wrong for one
     * of the two readers. Naming both is the honest form, and it is what makes the
     * built-in presence states above readable.
     */
    fun agentDirs(engineAgentDir: String, rootfsAgentDir: String): List<String> = listOf(
        "引擎读的 agent 目录（PiEngineHost 绑定到 guest /root/.pi/agent）：$engineAgentDir",
        "rootfs 里的同名目录（没有这条绑定的 guest 命令读写的是它）：$rootfsAgentDir",
    )

    const val STDERR_TITLE = "pi 的 stderr（原样）"
    const val STDOUT_TITLE = "pi 的 stdout（原样）"
    const val COMMAND_TITLE = "实际执行的 guest 命令"

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
        "这个项目已获信任，项目本地的扩展、技能、模板与 settings.json 会被加载。"

    const val TRUST_NO_TRIGGER =
        "这个项目没有任何需要信任的资源（.pi/settings.json、.pi/extensions、.pi/skills、" +
            ".pi/prompts、.pi/themes、.pi/SYSTEM.md、.pi/APPEND_SYSTEM.md、.agents/skills），" +
            "因此 pi 不会询问，也没有决定可写。"

    const val TRUST_FILE_LABEL = "trust.json"

    const val TRUST_INVALID_TITLE = "trust.json 无法解析"
    const val TRUST_INVALID_ACTION = "移开损坏文件并重建"
    const val TRUST_INVALID_NOTE =
        "pi 在读取到非法 trust.json 时会抛错并拒绝启动（trust-manager.ts:107-121），" +
            "所以这不是一个可以忽略的警告。损坏的文件会被改名保留，不会被删除。"

    const val TRUST_SESSION_ONLY_NOTE =
        "「session only」按 pi 自己的语义不写入任何文件（trust-manager.ts:84、:93）：" +
            "只对本次操作生效。需要让 pi 也认账，请选择会持久化的那一项。"

    /** pi's five choices, explained. Keyed by pi's own label prefix. */
    fun trustSubtitle(label: String): String = when {
        label == "Trust" ->
            "写入 { \"<路径>\": true }。此后 pi 会加载该项目的 .pi 资源并执行项目扩展。"
        label.startsWith("Trust parent folder") ->
            "信任上一级目录，并删除本项目自己的条目（pi 的顺序：先写父级，再删本级）。" +
                "之后这个父级下的任何项目都会被自动信任——请确认这是你要的粒度。"
        label.startsWith("Trust (this session only)") ->
            "仅本次操作放行，不写 trust.json。App 会用 --approve 把同样的语义传给 pi 的命令行。"
        label == "Do not trust" ->
            "写入 { \"<路径>\": false }。项目资源被忽略，而且不会再次询问。"
        label.startsWith("Do not trust (this session only)") ->
            "仅本次操作拒绝，不写 trust.json。下次仍会询问。"
        else -> ""
    }

    /** Why the app is showing a prompt at all, in pi's own resolution terms. */
    fun rationale(resolution: ProjectTrust.Resolution, cwd: String, hasTrigger: Boolean): String = when {
        !hasTrigger -> TRUST_NO_TRIGGER
        resolution.trusted -> TRUST_ALREADY_TRUSTED
        else -> resolution.explanation ?: ProjectTrust.skipNote(cwd, resolution.rationale)
    }

    /** One line per install/remove result, for the activity log. */
    fun doneHeadline(done: PiPackageService.Done): String = when (done) {
        is PiPackageService.Done.Ok -> done.summary
        is PiPackageService.Done.Failed -> "失败：${done.message}"
        is PiPackageService.Done.Refused -> "已被 App 拒绝：${done.summary}"
        is PiPackageService.Done.TimedOut -> done.summary
        is PiPackageService.Done.NotReady -> done.summary
    }
}
