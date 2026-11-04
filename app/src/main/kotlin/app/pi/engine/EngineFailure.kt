package app.pi.engine

/**
 * 失败页上要说什么 —— **按真实原因分种类**，而不是所有失败共用一句「引擎没有在运行」。
 *
 * ## 为什么必须分
 *
 * 用户看到的曾经是同一屏：标题「引擎没有在运行」、一句通用的话、按钮「重试 / 重建运行时」。
 * 但那一屏背后至少是三种互不相干的事：
 *
 *  - **运行环境没装好**（解包失败、空间不足、payload 对不上）。只有「重建运行时」能修；
 *  - **引擎跑着跑着自己退出**（被系统回收、内存耗尽、参数/扩展错）。重建**修不好**，
 *    还会删掉 guest 里装的一切 —— 用户点下去，环境没了，问题还在；
 *  - **切工作区失败**（新目录起不来，回滚也起不来）。两个目录共用同一棵运行时树，
 *    重建同样不是答案。
 *
 * 所以这一层把「哪一类 + 一句话解释 + 下一步该做什么」算出来，`BootScreen` 只负责画。
 * 分类不做猜测：每一条判据都是能证明的事实（退出码、stderr 里 pi/Node 的原话）。
 *
 * ## 判据从哪来
 *
 * `EngineExitCause` 已经把 pi 的 stderr 翻成句子，但它是"给用户的一句话"，不是"这是哪
 * 一类"。这里用同一批字符串做**分类**（`Unknown option`、`Failed to load extension`、
 * `heap out of memory`…），并加上退出码：`null`/137/143 是"被信号结束"，134 是
 * `SIGABRT`（V8 放弃时用它）。
 *
 * 无 import：`tools/run-app-pure-checks.sh` 的 `engine-failure` 在裸 JVM 上跑这份判定。
 */
enum class EngineFailureKind {
    /** 运行环境没能准备好（这一种才值得重建）。 */
    Provisioning,

    /** 切换工作区：新目录起不来，回到旧目录也起不来。 */
    WorkspaceSwitch,

    /** 进程被信号结束：LMK、phantom process killer、用户/模型自己 `kill`。 */
    KilledBySystem,

    /** V8 堆耗尽（退出码 134 或 V8 自己的句子）。 */
    OutOfMemory,

    /** 启动参数 / 扩展 / 模型名错了，重试只会得到同样的结果。 */
    Configuration,

    /** 有退出码但没有可归因的原因。 */
    Crashed,
}

/**
 * 失败页要画的全部内容。
 *
 * [detail] 是引擎自己的原话（退出码、stderr 尾部、异常文本），失败页原样显示它：那是
 * 用户把问题发给开发者时唯一有用的东西。`null` 表示没有可归因的字节。
 */
data class EngineFailure(
    val kind: EngineFailureKind,
    val title: String,
    val explanation: String,
    val detail: String?,
    /** 只有 [EngineFailureKind.Provisioning] 为 `true`。 */
    val rebuildOffered: Boolean,
) {
    /**
     * 用户点「重试」时该走哪条路。
     *
     * `Provisioning` 这一类**第一次重试**走 [RetryMode.Repair]（重新解包、不删数据）：
     * 这条失败最常见的原因是树里少了/坏了文件，而普通重试只做"戳记相符 → 什么都不做"的
     * 快路径，点一百次也是同一个结果。修复再失败，回到普通重试；`rebuild` 仍然是用户手动
     * 点的最后手段（它是唯一会删 guest 安装物的动作，不能自动升级到那一步）。
     *
     * 其余几类（引擎自己退出的那四种）必须是 [RetryMode.Plain]：它们和运行环境的完整性
     * 无关，重新解包只会白花几十秒。
     */
    fun retryMode(repairAttempted: Boolean): RetryMode =
        if (kind == EngineFailureKind.Provisioning && !repairAttempted) {
            RetryMode.Repair
        } else {
            RetryMode.Plain
        }
}

/** 「重试」的两个档位，见 [EngineFailure.retryMode]。 */
enum class RetryMode {
    /** 普通重试：戳记相符就是快路径。 */
    Plain,

    /** 不删数据的修复：重新解包全部 payload，再重写戳记。 */
    Repair,
}

object EngineFailureClassify {

    /** 运行环境安装/解包失败。`PiEngineHost.Boot.Failed` 的那一条。 */
    fun provisioning(message: String, detail: String?): EngineFailure = EngineFailure(
        kind = EngineFailureKind.Provisioning,
        title = message.ifBlank { "运行环境没有装好" },
        explanation = "运行环境没有准备好，引擎还没能启动。点「重试」会先做一次**不删数据**的修复：" +
            "把运行时重新解包一遍（guest 里装的 apt/pip/npm 包都还在）。修复也失败时才需要" +
            "「重建运行时」—— 它会清空易失树，只保留工作区、会话、设置和凭证。",
        detail = detail,
        rebuildOffered = true,
    )

    /**
     * 切换工作区失败。
     *
     * 回滚也失败说明**两个**工作区都起不来，而那两棵树共用同一份运行时 —— 重建不是答案，
     * 所以不给那个按钮。
     */
    fun workspaceSwitch(message: String, detail: String?): EngineFailure = EngineFailure(
        kind = EngineFailureKind.WorkspaceSwitch,
        title = message.ifBlank { "切换工作区失败" },
        explanation = "新工作区没能启动，回到原来的工作区也没能启动。工作区设置没有被改掉。" +
            "先重试一次；如果一直失败，把下面的文字发给我。",
        detail = detail,
        rebuildOffered = false,
    )

    /**
     * 引擎自己退出。
     *
     * @param exitCode 进程退出码；`null` = 没有拿到（被信号结束）。
     * @param stderr 引擎 stderr 的有界尾部。
     * @param detail `EngineExitCause.detail` 已经整理过的那一句（可以为 `null`）。
     */
    fun exit(exitCode: Int?, stderr: String?, detail: String?): EngineFailure {
        val text = stderr.orEmpty()
        if (isConfigurationFailure(text)) {
            return EngineFailure(
                kind = EngineFailureKind.Configuration,
                title = "启动参数或扩展出错",
                explanation = "引擎在读启动参数或加载扩展时退出了。这种情况重试不会变好 —— " +
                    "按下面的原因改掉它（扩展启动参数、扩展目录、模型名），再点重试。",
                detail = detail,
                rebuildOffered = false,
            )
        }
        if (isOutOfMemory(exitCode, text)) {
            return EngineFailure(
                kind = EngineFailureKind.OutOfMemory,
                title = "引擎内存耗尽",
                explanation = "引擎的 JavaScript 堆用完了（退出码 134 / V8 的 FATAL ERROR）。" +
                    "一次让它在 guest 里同时跑多个构建、开发服务器或依赖安装最容易这样。" +
                    "少开几个并行任务再重试；已经开始的会话不会丢。",
                detail = detail,
                rebuildOffered = false,
            )
        }
        if (killedBySystem(exitCode)) {
            return EngineFailure(
                kind = EngineFailureKind.KilledBySystem,
                title = "引擎被系统结束了",
                explanation = "不是崩溃，也没有报错：进程是被 Android 直接收走的（没有退出码，" +
                    "或者是 137/143）。手机上最常见的原因是内存紧张时系统回收后台进程树 —— " +
                    "在 guest 里跑 npm/vite 这类构建时尤其容易。App 已经在跑前台服务，" +
                    "但系统仍然可能这么做。",
                detail = detail,
                rebuildOffered = false,
            )
        }
        return EngineFailure(
            kind = EngineFailureKind.Crashed,
            title = "引擎异常退出",
            explanation = "引擎在运行中退出了。可以重新启动后继续 —— 会话、队列和已写出的内容都还在。",
            detail = detail,
            rebuildOffered = false,
        )
    }

    /** 与 [EngineCrashRecovery.isConfigurationFailure] 同一批句子，这里用来分类。 */
    fun isConfigurationFailure(stderr: String?): Boolean = EngineCrashRecovery.isConfigurationFailure(stderr)

    /** `134` 是 V8 放弃时用的 `SIGABRT`；另外两个是 V8 自己的句子。 */
    fun isOutOfMemory(exitCode: Int?, stderr: String?): Boolean {
        if (exitCode != 134 && !stderr.isNullOrBlank()) {
            if (stderr.contains("heap out of memory") || stderr.contains("FATAL ERROR")) return true
        }
        return exitCode == 134
    }

    /**
     * 被信号结束。
     *
     * `null` 就是"没拿到退出码"：`Process.waitFor()` 在信号杀死时通常给 128+signal，
     * 但探针/包装层拿不到时是 `null` —— 两种都说明"不是引擎自己选择退出"。
     */
    fun killedBySystem(exitCode: Int?): Boolean =
        exitCode == null || exitCode == 137 || exitCode == 143
}
