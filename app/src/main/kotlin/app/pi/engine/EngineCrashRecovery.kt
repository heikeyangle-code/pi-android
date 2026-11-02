package app.pi.engine

/**
 * 引擎**中途死掉**之后要不要自动重启，以及重启几次就该把控制权交回用户。
 *
 * ## 为什么这件事需要一份判定，而不是"直接弹失败页"
 *
 * 旧行为是：`PiEngineSession` 只要发出 `Stopped`/`Failed`，ViewModel 就把
 * `Boot.Failed` 推到界面上 —— 那是**启动失败**的面板，上面还挂着「重建运行时」。
 * 但"启动时没能准备好运行环境"和"引擎跑着跑着退出了"是两件事：
 *
 *  - 前者（provisioning 失败）只有重建能修，删掉易失树是对的；
 *  - 后者多半是转瞬即逝的：Android 的 LMK 在手机内存紧张时杀掉进程树（客人里跑
 *    `npm run build` 这种正合适），或者一次偶发的段错误。把用户的整屏对话换成一页
 *    "引擎没有在运行 + 重建运行时"，再让他点一个会删掉 guest 里所有安装物的按钮，
 *    既没修好问题，又把他的环境清了 —— 这就是「两个小时一直弹」的那一圈。
 *
 * 所以中途退出先**悄悄重启**，最多 [MAX_AUTO_RESTARTS] 次；只有连续失败才让用户看到
 * 失败页，而且那一页**不再提供**「重建运行时」（`Boot.Failed.rebuildOffered = false`）。
 *
 * ## 为什么不重试"配置错误"
 *
 * pi 有几类退出是**启动参数或扩展**错了，重试只会得到同一个结果：`Unknown option`
 * （`cli/args.ts:242`）、`Failed to load extension`（`main.ts:899-909`）、
 * `@file arguments are not supported`（`main.ts:645`）、`Model not found`
 * （`modes/rpc/rpc-mode.ts:476`），以及 Node 的 `Cannot find module`。这几种第一次就
 * 直接给失败页，省掉两次八百毫秒的空转 —— 而且失败页上的那句话本来就是唯一的线索。
 *
 * 反过来，**信号类**退出（137 SIGKILL、143 SIGTERM、134 OOM 的 `FATAL ERROR`）恰恰是
 * 最该重试的：进程被系统收走时，重启一次通常就好了。
 *
 * 无 import：这份判定由 `tools/run-app-pure-checks.sh` 的 `engine-crash-recovery` 在裸
 * JVM 上跑。
 */
internal object EngineCrashRecovery {

    /** 一次会话里最多替用户自动重启几次。两次足够跨过一次内存抖动。 */
    const val MAX_AUTO_RESTARTS: Int = 2

    /**
     * 两次尝试之间的停顿。
     *
     * 800 ms 的依据是"让系统先把刚被杀的进程树收干净"：立刻 fork 一个 pi 会在同一个
     * 内存压力下再死一次，而用户看到的只是两次 800 ms 的闪烁。它也不能太长 —— 两次
     * 尝试加起来必须明显短于"用户开始怀疑 App 卡住"的时间。
     */
    const val RESTART_DELAY_MS: Long = 800L

    enum class Decision {
        /** 静默重启，不切到失败页。 */
        Restart,

        /** 把失败页给用户，并且**不**提供「重建运行时」。 */
        GiveUp,
    }

    /**
     * @param attempts 这条会话里已经自动重启过的次数（不是总失败次数）。
     * @param exitCode 引擎进程的退出码；`null` 表示没拿到（被信号杀掉、或进程对象没有 code）。
     * @param stderr 引擎的 stderr 尾部（有界捕获）。
     */
    fun decide(attempts: Int, exitCode: Int?, stderr: String?): Decision {
        if (attempts >= MAX_AUTO_RESTARTS) return Decision.GiveUp
        if (isConfigurationFailure(stderr)) return Decision.GiveUp
        return Decision.Restart
    }

    /**
     * 这条 stderr 是不是在说"配置错了，重试无用"。
     *
     * 只匹配 pi 自己（或 Node）真的会打印的句子，逐条来自一次真实运行；宁可漏判
     * （那只是多重启一次），也不要误判（那会把一个能自愈的抖动直接推给用户）。
     */
    fun isConfigurationFailure(stderr: String?): Boolean {
        if (stderr.isNullOrBlank()) return false
        return CONFIGURATION_FAILURES.any { stderr.contains(it) }
    }

    /**
     * 给失败页/诊断报告用的一句话，只说能从 [exitCode] 与 [stderr] 证明的事。
     *
     * 进程被信号杀掉时 `exitCode` 是 `null`，这句话就说"没有退出码"而不是编一个 0。
     */
    fun causeLabel(exitCode: Int?, stderr: String?): String = when {
        !stderr.isNullOrBlank() -> "退出码 ${exitCode ?: "未知"}"
        exitCode == null -> "没有退出码（进程被系统结束）"
        else -> "退出码 $exitCode"
    }

    /** 见 [isConfigurationFailure]：pi/Node 的原始输出，不做大小写折叠（它们是固定的英文）。 */
    private val CONFIGURATION_FAILURES = listOf(
        "Unknown option",
        "Failed to load extension",
        "@file arguments are not supported",
        "Model not found",
        "Cannot find module",
    )
}
