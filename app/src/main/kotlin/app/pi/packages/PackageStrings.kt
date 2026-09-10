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

    const val SPEC_HINT = "npm:@scope/name@1.0.0 / git:github.com/user/repo@v1 / /绝对路径"

    const val SCOPE_USER = "全局（~/.pi/agent/settings.json）"
    const val SCOPE_PROJECT = "项目（.pi/settings.json）"

    const val SCOPE_PROJECT_LOCKED =
        "项目作用域需要先信任这个项目：pi 会拒绝写入未信任项目的包配置" +
            "（package-manager-cli.ts:936-940）。"

    const val NO_PACKAGES = "pi list 报告没有已安装的资源包。"
    const val PROJECT_PACKAGES_HIDDEN =
        "注意：项目的 .pi/settings.json 里声明了资源包，但这次列表没有显示它们——" +
            "项目未获信任时 pi 会把整个项目文档当成空的（settings-manager.ts:405-408），" +
            "而 pi list 仍然以退出码 0 结束，什么都不会说。先处理项目信任，再刷新列表。"
    const val LIST_UNPARSED =
        "pi list 的输出无法解析（下方原样显示）。这不会被当成「没有包」——那正是会骗人的地方。"

    const val RUNNING = "正在执行…"
    const val TIMEOUT_NOTE =
        "命令超时已被终止。它可能已经写入了部分文件，请先刷新列表再决定下一步。"

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
