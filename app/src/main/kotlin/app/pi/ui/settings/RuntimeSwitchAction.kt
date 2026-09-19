package app.pi.ui.settings

/**
 * 「运行时加速（实验性）」开关写完之后，**接下来该做什么**——这条判定本身。
 *
 * ## 为什么它必须是一个纯对象
 *
 * 用户原话：「只要在开关里点开开关，就自动重启切换。为什么还要退出软件重进呢？」
 * 于是这一行不再只是「记下一个偏好」：写完之后要先清掉探针结论与失败计数、现在就跑一次
 * 门禁、通过了再当场把引擎重启到 proroot，被拒时还要把开关退回写入前的值。这里每一步
 * 都有「做错就安静地做错」的形态（半切换、开关与引擎说的不是同一个运行时、探针跑完
 * 结论被丢掉），而真正能执行它的地方（`PiSessionViewModel`）import 了 Android 与
 * Compose，本机编译不了。所以判定被搬到这里：只吃布尔与枚举、只吐枚举，由
 * `tools/run-app-pure-checks.sh` 的 `runtime-switch` harness 在裸 JVM 上真跑一遍。
 *
 * IO 与重启**不在这里**，它们留在 ViewModel：这个对象不读文件、不起进程、不写偏好。
 *
 * ## 两段判定，以及为什么是两段
 *
 * 1. [onWrite] —— 开关刚写完，第一步。打开 = 先跑探针（结论要等几十秒，见
 *    [Step.Probing]）；关闭 = 直接重启回 proot（没有要测的东西）。
 * 2. [afterProbe] —— 探针的结论落地之后。通过才重启；不通过就**什么都不做**，因为引擎
 *    本来就在 proot 上，重启一次只是白打断它，而「没切过去」的原因由同一份结论
 *    （`RuntimeSelection.Plan.summary` → 状态行）说清楚。
 *
 * ## 全有或全无
 *
 * 这个开关不允许「一半路径走 proroot、一半走 proot」，也不允许一个**说 proroot 而实际
 * 不会切过去**的开关。判定把四个落点分清楚，每一个都只有**一个**答案，而那个答案要么在
 * 引擎身上、要么在紧挨开关的「运行时（实际生效）」行上：
 *
 *  - 探针**通过**且重启成功：引擎在新运行时上跑，缓存里是 PASS，别的启动路径（终端、工具
 *    执行、装包）问的是同一份缓存，答案相同。
 *  - 探针**没通过**：缓存里现在是一条 FAIL，所以**每条启动路径**都回退 proot，引擎不动
 *    （它本来就在 proot 上，重启只是白打断一个回合）。开关保持打开是用户写下的意愿——
 *    这一行的语义从来是「在这台机器上确实可用就用它」——而旁边的「运行时（实际生效）」行
 *    会说 `proot（探针未通过：…）`。这不是被藏起来的回退，正是那一行存在的理由；这条落点
 *    里 proroot 一个路径都没用上。
 *  - 重启被**拒绝**（有回合在跑，`allowInterrupt = false`）：什么都没有改变，引擎仍跑在
 *    它原来的运行时上。[settle] 因此把开关**退回写入前的值**，否则就会出现那个真正不该
 *    出现的状态——开关说 proroot，引擎却在 proot 上（或反过来）。用户看到开关弹回去，
 *    同时收到引擎自己那句拒绝理由。
 *  - 重启**失败**（引擎已经不在，或起来时又死了）：没有引擎在跑，下一次启动会按开关的值
 *    选运行时，所以开关保持用户写的值。这条落点同样没有第二个答案——跑的那个还没出现。
 *
 * ## 探针结论为什么用「上一次写入前」的值来回滚
 *
 * [rollbackValue] 就是 `!nowEnabled`。它不是猜的：这一行唯一能到达的写入路径是
 * `SettingsGroupScreen.toggleRow` 的开关翻转（`PiRowKind.Switch` 没有编辑器表单，也就
 * 没有「恢复默认」那条 `remove` 路径），所以「刚写完的值」与「写入前的值」必然互补。
 * `RuntimeSwitchActionCheck` 把这条互补关系逐值钉住。
 *
 * Android-free：只有 Kotlin stdlib，没有 import。
 */
object RuntimeSwitchAction {

    /**
     * 这一次写入接下来要做的事，同时也是「设置行上该显示什么」的状态。
     *
     * [inFlight] 是给状态行用的：`true` 的那些取值都是**正在发生**的事，状态行要说它
     * 正在发生（探针最长约一分钟），而不是显示上一次的旧结论。[Idle] 表示没有动作在跑，
     * 状态行的值回到 `RuntimeSelection.status()` 的真实读数。
     */
    enum class Step(val inFlight: Boolean) {
        /** 无需动作：状态已经一致，或这次写入已被后来的写入取代。 */
        Idle(false),

        /** 现在跑门禁探针。10~60 秒，在后台线程上跑。 */
        Probing(true),

        /** 现在把引擎重启到 proot。 */
        RestartingToProot(true),

        /** 现在把引擎重启到 proroot。 */
        RestartingToProroot(true),
    }

    /**
     * 引擎重启的三种结论，与 `EngineRestartCoordinator.Outcome` 一一对应。
     *
     * 这里不 import 那个类型：它是 `app.pi.packages` 的，带一整套依赖，而这个对象要在
     * 裸 JVM 上编译。映射写在唯一那个调用点（`PiSessionViewModel`），用的是 `when`，
     * 所以那边多出一种 Outcome 会在编译期就暴露，而不是在这里被悄悄吞掉。
     */
    enum class RestartResult {
        /** 新引擎已经在跑。 */
        Ok,

        /** 什么都没动：引擎仍在运行、仍在**原来的**运行时上。 */
        Refused,

        /** 旧引擎已经停了，新的没能起来（或本来就引擎已死）。 */
        Failed,
    }

    /** 探针在跑时状态行的值。数字与 `ProrootProbe` 的上界同源，写成一句话给用户。 */
    const val PROBING_STATUS = "正在测 proroot 探针（最长约 60 秒）…"

    /** 往 proot 重启时状态行的值。 */
    const val RESTART_TO_PROOT_STATUS = "正在把引擎重启到 proot…"

    /** 往 proroot 重启时状态行的值。 */
    const val RESTART_TO_PROROOT_STATUS = "正在把引擎重启到 proroot…"

    /**
     * 开关刚写完（值是 [nowEnabled]），第一步做什么。
     *
     * 打开：先跑探针——这是这一行自己的承诺（「打开就用 proroot」）里唯一需要测量的一半，
     * 而结论要等几十秒，所以状态行必须立刻说它在跑。
     *
     * 关闭：没有东西可测，运行时由偏好直接决定，立刻重启引擎回到 proot。
     */
    fun onWrite(nowEnabled: Boolean): Step =
        if (nowEnabled) Step.Probing else Step.RestartingToProot

    /**
     * 探针的结论落地之后，还要不要重启引擎。
     *
     * [nowEnabled] 是**此刻**重读的开关值，不是写入时的值：探针要跑几十秒，这期间用户
     * 完全可能把开关又关了。那时候这次写入已经过期，不能再去重启引擎——关掉那次写入
     * 自己的流程已经在做了。
     *
     * @param probePassed 门禁的结论。**只有它**能决定是否用 proroot：不是「文件在」，
     *        也不是「开关开着」——`RuntimeChoice.probeGate` 的三段测量缺一不可。
     */
    fun afterProbe(nowEnabled: Boolean, probePassed: Boolean): Step = when {
        // 过期：开关已经不是这次写入的那个值了。
        !nowEnabled -> Step.Idle
        probePassed -> Step.RestartingToProroot
        // 没通过：引擎本来就在 proot 上，重启是白打断；原因交给状态行与通知。
        else -> Step.Idle
    }

    /**
     * 重启没能改变任何东西时，开关要回到的值——写入前的那个值。
     *
     * 见类 KDoc：这一行的写入只有开关翻转一条路径，所以两个值必然互补。
     */
    fun rollbackValue(nowEnabled: Boolean): Boolean = !nowEnabled

    /**
     * 重启结论落地后，开关最终应该持有的值。
     *
     * 见类 KDoc 的「全有或全无」：只有 [RestartResult.Refused] 会让开关回到写入前
     * （引擎还在原运行时上，开关必须跟它一致）；[RestartResult.Ok] 保持用户写的值（引擎
     * 已经在新运行时上）；[RestartResult.Failed] 也保持——没有引擎在跑，下一次启动会按
     * 开关的值选运行时，此时没有第二个人在说不同的话。
     */
    fun settle(nowEnabled: Boolean, result: RestartResult): Boolean = when (result) {
        RestartResult.Ok -> nowEnabled
        RestartResult.Refused -> rollbackValue(nowEnabled)
        RestartResult.Failed -> nowEnabled
    }

    /**
     * 状态行在这次动作期间显示的句子；[Step.Idle] 时是 null，表示「显示真实读数」。
     *
     * 单独一个函数而不是写在 Composable 里：状态行是用户唯一能看见「它在跑」的地方
     * （探针要几十秒），所以这句话也由 harness 钉住，而不是靠读界面确认。
     */
    fun statusLine(step: Step): String? = when (step) {
        Step.Idle -> null
        Step.Probing -> PROBING_STATUS
        Step.RestartingToProot -> RESTART_TO_PROOT_STATUS
        Step.RestartingToProroot -> RESTART_TO_PROROOT_STATUS
    }
}
