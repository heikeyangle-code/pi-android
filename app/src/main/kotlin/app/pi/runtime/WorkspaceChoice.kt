package app.pi.runtime

/**
 * 工作区选择里那一半**不需要 Android** 的规则。
 *
 * ## 为什么这些规则要单独住一个文件
 *
 * 它们原来一个都不在这里：`workspace-N` 的名字正则、label 清洗、以及**「当前工作区是谁」的
 * 裁决**都在 `WorkspaceStore` 的私有函数里，而 `WorkspaceStore` 是 Android 对象（它拿
 * `Context` 去读 pi 的 `settings.json`），所以本机那个 bare-JVM harness 一行都执行不到；
 * 屏幕侧的「点了这一行之后做什么」则是 `ProjectScreen` 里 `onPick` 那个 lambda 的直接分支。
 * 两者都不是小事：
 *
 *  - `decide` 决定**引擎在哪个目录里跑**。它错了，进程就活在一个「设置说 A、引擎跑 B」的
 *    状态里 —— `WorkspaceStore` 的 KDoc 把这个状态点名为「绝不能停在那里的状态」。
 *  - `decidePick` 决定**要不要中断一轮正在跑的回合**。用户点一行就杀一次模型调用是可以说
 *    得过去的，但只能是因为用户被问过；这一支错了，问题就不会出现。
 *
 * 两者都是纯的（String / Boolean / 一个探测 lambda），所以它们搬到这里，由
 * `app/src/test/kotlin/app/pi/runtime/WorkspaceChoiceCheck.kt` 逐个钉死。
 *
 * ## 这里不碰文件系统
 *
 * 「这个目录存在吗 / 是符号链接吗」是 [Look]，由调用方探测后传进来。这样做的直接好处是
 * harness 能构造出设备上很难造的状态（一个符号链接、一个被外部删掉的工作区），而间接好处
 * 是**注释里那句「我们只探测自己创建的名字」变成了可执行的性质**：[decide] 只在名字通过
 * [isOwned] 之后才调用那个 lambda。
 */
object WorkspaceChoice {

    /**
     * 本应用创建的工作区目录名：`workspace-N`。
     *
     * 刻意是模式而不是「任何子目录」：`pi/workspaces` 里也可能有用户自己放进去的目录，而
     * 这个名字集合同时是**删除的许可范围**（`WorkspaceStore.previewDelete`/`delete` 都先问
     * 它）。数字位数封顶 6 位，好让 `toInt()` 不会溢出。
     */
    private val OWNED = Regex("^workspace-([1-9][0-9]{0,5})$")

    /** 名字里那个数字的上限，也就是 [nextFreeName] 搜索空间的边界。 */
    const val MAX_NUMBER: Int = 999_999

    /**
     * 显示名的长度上限。
     *
     * 这是一个**渲染**问题而不是数据问题：显示名只出现在一行/一张卡上，超过一行字宽的部分
     * 谁也读不到，而把它截在写入侧意味着列表、切换面板、通知三处印的是同一个名字。
     */
    const val MAX_LABEL_CHARS: Int = 40

    /** 名字是不是本应用创建的工作区（[OWNED]）。 */
    fun isOwned(name: String): Boolean = OWNED.matches(name)

    /**
     * `workspace-7` 里的那个 7；不是我们的名字就是 null。
     *
     * 列表按它排序（`WorkspaceStore.list`），于是「`workspace-10` 排在 `workspace-9` 之后」
     * 由这里保证，而不是每个调用点各自 `substringAfterLast('-').toInt()` 保证 —— 那种写法在
     * 连字符后面不是数字时会抛，而这个名字可以来自设置文件。
     */
    fun number(name: String): Int? = OWNED.find(name)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * [existing] 之外最小的空闲编号，拼成 `workspace-N`；1..[MAX_NUMBER] 全占满时 null。
     *
     * 从 1 开始往上找**第一个空位**，不是「最大编号 + 1」：被删掉的 `workspace-2` 应该被
     * 下一个新建的工作区补上，否则一个列表里删删建建几次之后，编号会长到没人愿意念。
     * 不认识的名字直接忽略 —— 它们不占位，因为 [isOwned] 说不出它们是什么。
     */
    fun nextFreeName(existing: Collection<String>): String? {
        val used = existing
            .mapNotNull { number(it) }
            .toHashSet()
        return (1..MAX_NUMBER).firstOrNull { it !in used }?.let { "workspace-$it" }
    }

    /**
     * 一个 label 的清洗，null 表示「没有 label」。
     *
     * 去控制字符（一个 `\n` 会把一行撑成两行，而列表行高是算好的）、去首尾空白（否则
     * 「abc 」与「abc」是两个不同的名字却印成一样）、截到 [MAX_LABEL_CHARS]。
     * 清完是空的就是 null：调用方把那当成「清掉 label，显示目录名」，而不是一个空名字。
     */
    fun label(raw: String): String? =
        raw.filterNot { it.isISOControl() }.trim().take(MAX_LABEL_CHARS).ifEmpty { null }

    /**
     * 一行、一张卡、一条通知该印什么：label 优先，空白 label 退回目录名。
     *
     * **这是全应用唯一一处「当前工作区叫什么」的算法**。它存在的理由是一次真实的矛盾：
     * ① 卡曾经用「当前会话头部记的 cwd」推名字（`PiProject.workspaceName`），而切换面板用的是
     * 目录名/label，于是同一个工作区在两处印出两个名字（改过 label 之后是 `我的项目` 与
     * `workspace-2`），切完的头一两秒还会印着**上一个**工作区的名字。
     */
    fun displayName(name: String, label: String?): String =
        label?.trim()?.ifEmpty { null } ?: name

    // ---------------------------------------------------------------- 当前工作区

    /**
     * 目录探测的结果，由调用方给出（见类 KDoc）。
     *
     * 两项都要：一个指向目录的符号链接 `isDirectory` 是真的，而它是被拒绝的那一种
     * —— 工作区同时是设备 shell 的写边界和引擎的 cwd，两者都该描述一个本应用拥有的真实目录。
     */
    data class Look(val isDirectory: Boolean, val isSymlink: Boolean)

    /**
     * 「当前工作区是谁」，以及为了得到这个答案所做的纠正。
     *
     * [requested] 是设置文件里写的那个名字（null 表示它什么都没写，或者写的全是空白），
     * [note] 非 null **当且仅当** [name] 不是用户选的那个 —— 这张表由 [decide] 的五种返回
     * 一一对应，调用方要做的就是有 note 就把它印出来。
     */
    sealed interface Current {
        val name: String
        val requested: String?

        /** 非 null 就是要给用户看的那句话；null 表示「就是用户选的那个」。 */
        val note: String?
    }

    /** [Current] 的正常路径：设置里那个名字可用，或者压根没有选择（第一次启动）。 */
    data class Chosen(
        override val name: String,
        override val requested: String?,
    ) : Current {
        override val note: String? get() = null
    }

    /** [Current] 的纠正路径：设置里那个名字不可用，已回到默认工作区，[note] 说明为什么。 */
    data class Fallback(
        override val name: String,
        override val requested: String?,
        override val note: String,
    ) : Current

    /**
     * 裁决：设置里写的 [requested] 该不该被采纳。
     *
     * 四条规则的**顺序**是判定的一部分，不是风格：
     *
     *  1. 没写 / 空白 → 默认工作区，且**不是**一次纠正（第一次启动不该弹一句话）。
     *  2. 不是本应用创建的名字 → 默认工作区。这一支在任何目录探测**之前**，所以
     *     `../..` 这类值根本到不了 `look`（见类 KDoc）。
     *  3. 符号链接 → 默认工作区。在「存在吗」之前，因为一个指向目录的链接是存在的。
     *  4. 目录不存在 → 默认工作区，但**默认工作区自己不存在时不是纠正**：那是第一次启动，
     *     每条启动路径都会当场建它（`GuestWorkspacePath.ensureHost`），所以这里只说
     *     「名字就是这个」，不说「被删了」。
     *
     * 只做判定，不写任何东西：写回设置是 [WorkspaceStore.refresh] 的事，而它只在这个答案
     * 与设置不一致时才写。
     */
    fun decide(requested: String?, defaultName: String, look: (String) -> Look): Current {
        val name = requested?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Chosen(defaultName, null)
        if (!isOwned(name)) {
            return Fallback(
                name = defaultName,
                requested = name,
                note = "设置里的当前工作区「$name」不是本应用创建的工作区，已回到默认工作区「$defaultName」。",
            )
        }
        val probe = look(name)
        if (probe.isSymlink) {
            return Fallback(
                name = defaultName,
                requested = name,
                note = if (name == defaultName) {
                    "默认工作区「$defaultName」是一个符号链接，不是一个真实目录；请把它换成真实目录。"
                } else {
                    "工作区「$name」是一个符号链接，不是一个真实目录，已回到默认工作区「$defaultName」。"
                },
            )
        }
        if (!probe.isDirectory) {
            if (name == defaultName) return Chosen(defaultName, name)
            return Fallback(
                name = defaultName,
                requested = name,
                note = "工作区「$name」的目录不存在（可能被外部删除了），已回到默认工作区「$defaultName」。",
            )
        }
        return Chosen(name, name)
    }

    // ---------------------------------------------------------------- 点了某一行

    /**
     * 用户点了切换面板里的一行之后，屏幕该做什么。
     *
     * 四种答案收成三种：`Refused`/`Failed` 是 ViewModel 推的通知，屏幕不拼第二句
     * （见 `ProjectScreen.switchWorkspaceTo` 的 KDoc）。
     */
    sealed interface Pick {
        /** 点的就是当前工作区：一句话，引擎一下都不碰。 */
        object AlreadyHere : Pick

        /**
         * 别的工作区，但正有一轮在跑：先问一次。
         *
         * pi 的一轮是「一次模型调用 + 一串工具调用」，切工作区要重启进程，也就是把这一轮
         * 从中间掐掉。`switchWorkspace` 默认 `allowInterrupt = true`，所以那个「是」必须
         * 由用户说出来 —— 这一支就是问话的那一格。
         */
        object ConfirmInterrupt : Pick

        /** 直接切。 */
        object Switch : Pick
    }

    /**
     * 三条判定，顺序是判定的一部分：**先看是不是当前工作区**。
     *
     * 反过来的话，回合正跑着时点当前那一行会得到「会中断正在运行的回合」这句警告，而那一行
     * 根本不会中断任何东西 —— 一句吓人的、而且不真的话。
     */
    fun decidePick(isCurrent: Boolean, turnRunning: Boolean): Pick = when {
        isCurrent -> Pick.AlreadyHere
        turnRunning -> Pick.ConfirmInterrupt
        else -> Pick.Switch
    }
}
