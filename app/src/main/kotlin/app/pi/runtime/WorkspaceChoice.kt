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

    /**
     * 会话列表里一组的标题：一个 **guest** cwd 该印成什么名字。
     *
     * 会话列表按 pi 记在会话头里的 cwd 分组（`session-manager.ts:33/37`），而那是 guest 写法
     * （本应用的工作区是 `/workspace/pi/workspaces/workspace-1`，终端里自己起的 pi 是 `/root`）。
     * 直接印路径既没人能读，也是全应用唯一会露出内部目录的地方 —— 所以：
     *
     *  - cwd 正是**本应用某个工作区**（`[workspacesRoot]/<name>`，且 `[name]` 通过 [isOwned]）
     *    → 与工作区页印的**同一个**名字（[displayName]，label 优先）。
     *  - **恰好一层**才算：`.../workspace-1/src` 是另一个目录（终端里 `cd` 进去起过 pi），
     *    不是 `workspace-1`，所以照旧取末段。
     *  - 其余（`/root`、别的项目、workspaces 根自己）→ 末段。
     *
     * 为什么这一步是必要的，而不仅仅是好看：这个标题以前把**当前**工作区那一组印成通用词
     * 「工作区」，其余组印目录名，于是同一个工作区在会话页叫「工作区」、在工作区页叫「我的项目」；
     * 而它自己的注释承认这是 `ProjectResources.workspaceName` 的第二份拷贝。现在所有工作区组
     * 用同一套名字，工作区页/切换面板/会话页三处一致。
     *
     * [workspacesRoot] 由调用方给（`GuestWorkspacePath` 的 `GUEST_ROOT` + `ROOT_RELATIVE`），
     * 而不是这里读那个对象：这个文件因此可以单独编译进 harness（注册行只有两个文件）。
     * [labels] 是 `WorkspaceStore.labels` 的读数（目录名 → label，已过滤掉不存在的目录），
     * 缺项就退回目录名 —— 不是错误，只是那个工作区没改过名。
     */
    fun sessionGroupLabel(cwd: String, workspacesRoot: String, labels: Map<String, String>): String {
        val raw = cwd.trim()
        // Only a **missing** cwd gets the sentence. It is not the same as a cwd that is the guest
        // root: pi recorded `/` (a session started at the root of the guest), and answering
        // 「工作目录未记录」 there would be this app inventing a fact about pi's file.
        if (raw.isEmpty()) return "工作目录未记录"
        val clean = raw.trimEnd('/')
        if (clean.isEmpty()) return "/"
        val root = workspacesRoot.trimEnd('/')
        val prefix = "$root/"
        if (clean.startsWith(prefix)) {
            val leaf = clean.removePrefix(prefix)
            if (leaf.isNotEmpty() && !leaf.contains('/') && isOwned(leaf)) {
                return displayName(leaf, labels[leaf])
            }
        }
        return clean.substringAfterLast('/').ifEmpty { clean }
    }

    // ------------------------------------------------------------ 外部工作区

    // `isExternal(hostPath, filesRoot)` was here until 2026-09-23. It answered "is this
    // directory outside the app's own tree" **by position**, and it had no production
    // caller: the decision that actually gets made — "does deleting this workspace delete
    // files?" — is `deleteRemovesFiles(name)` below, and it answers **by name**, because
    // only names this app minted are ours to delete. The function had drifted with the
    // layout too: after the workspace moved into the rootfs, its `filesRoot` argument would
    // have judged every app-owned workspace *external*, which is the direction that loses
    // no data but silently stops deleting. Deleting the function removes both the dead code
    // and the trap; if a position-based question is ever needed again, `PiPaths.workspaces`
    // is the root to compare against, not the files directory.

    /**
     * 设备的哪个存储卷根可以给用户选，以及要不要「所有文件访问」。
     *
     * [primary] 是主共享存储（`Environment.getExternalStorageDirectory()`），[removable] 是可移除卷
     * 的根（`StorageManager.storageVolumes` 里 `isRemovable` 的那些）。**顺序与去重在这里定**：
     * 主存储永远第一（它是用户唯一肯定有的那个），可移除卷按调用方给的顺序跟在后面，重复的根只留
     * 一次 —— 同一张卡在 `storageVolumes` 里出现两次不是没见过的事，而两个一样的根在列表里
     * 就是两行一样的字。
     *
     * 空白的根被丢掉：一个空字符串在界面上是一行点不动的空行。
     */
    fun volumeRoots(primary: String?, removable: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        (listOfNotNull(primary) + removable).forEach { raw ->
            val clean = raw.trim().trimEnd('/')
            if (clean.isNotEmpty()) out += clean
        }
        return out.toList()
    }

    /**
     * 用户点了「就选这个目录」时，**为什么不能选**；null 表示可以选。
     *
     * 每一条拒绝都要给一句原因，因为静默置灰的按钮就是这个仓库反复记账的那种「看起来在跑」。
     * 规则分三档：
     *
     *  - **不是用户内容**：`/`、`/system`、`/data`、`/vendor`、`/apex`、`/proc`、`/sys`、`/dev`、
     *    `/etc`、`/root` 以及 `/storage` 本身。这些要么是 Android 的系统树（选它等于把
     *    `rm -rf` 指向系统），要么根本不是目录。判据按**段**比较，所以 `/datax` 不在其中。
     *  - **必须是某个存储卷里的目录**（[volumeRoots] 的那些根之一，或它们下面）。这样「同一张
     *    卡的两个拼法」不会一边能选一边不能选。
     *  - **必须真的读得到**：[readable] 由调用方探测（`listFiles()` 非 null），因为 Android 上
     *    「有权限」与「这个目录现在能用」是两件事（SD 卡刚拔、卷正在卸载）。空目录是合法工作区，
     *    所以「读得到但里面没东西」不是拒绝的理由。
     */
    fun pickRefusal(path: String, volumes: List<String>, readable: Boolean): String? {
        val clean = path.trim().trimEnd('/').ifEmpty { "/" }
        if (!clean.startsWith("/")) return "只能选设备上的绝对路径。"
        val forbidden = setOf(
            "/", "/system", "/vendor", "/apex", "/proc", "/sys", "/dev", "/etc", "/data", "/root",
            "/storage", "/mnt", "/sbin", "/bin", "/lib", "/usr", "/boot", "/init",
        )
        if (clean in forbidden) {
            return "「$clean」不是用户内容（系统目录或根本不是目录），不能当工作区。"
        }
        val inVolume = volumes.any { volume -> clean == volume || clean.startsWith("$volume/") }
        if (!inVolume) {
            val where = volumes.joinToString("、").ifEmpty { "（这台设备没报告任何存储卷）" }
            return "只能选存储卡或共享存储里的目录（现在能选的是：$where）。"
        }
        if (!readable) return "这个目录现在读不到（可能是存储卡被拔出、正在卸载，或者没有权限），先换一个。"
        return null
    }

    /**
     * 外部工作区在 guest 里的拼法 —— 与内部工作区**同一条规则**（`GuestWorkspacePath.under`：
     * 某个基底镜像在 `/workspace`，工作区就是去掉那个前缀之后剩下的路径）。
     *
     * 基底由调用方给：`PiPaths.workspaceBase`（`<rootfs>/workspace`）。**它不是 files 目录**
     * —— 2026-09-23 工作区搬进 rootfs 时基底跟着换过，而 guest 拼写一个字没变。
     *
     * 这一个薄门存在的唯一理由是**它要能被 harness 执行**：规则本身在 `GuestWorkspacePath`，
     * 这里只把「外部工作区也走同一条规则」这件事记下来，免得有人以为外部目录要另配一个挂载点。
     * 举例：`/storage/emulated/0/Foo` 在 guest 里是 `/workspace/storage/emulated/0/Foo`；
     * pi 的项目设置因此是那个目录里的 `.pi/settings.json`（`PiProjectConfig.root`），
     * 这正是 pi 的语义：项目设置跟着 cwd 走。
     */
    fun guestPathOf(hostPath: String, base: String): String =
        GuestWorkspacePath.under(base, hostPath)

    /**
     * 删一个工作区时，磁盘上的文件会不会一起消失。
     *
     * **只有名字是本应用创建的（`workspace-N`）才删文件**；外部目录永远只是「取消登记」。
     * 这是这条产品决定唯一的判据，所以它在这里有一个名字，由 harness 钉住：一个根据路径长相
     * 临时决定的实现，会在某次重构里把 `rm -rf` 指向用户的相册。
     */
    fun deleteRemovesFiles(name: String): Boolean = isOwned(name)

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
     *  2. 既不是本应用创建的名字（`workspace-N`）也不是已登记的外部目录 → 默认工作区。这一支
     *     在任何目录探测**之前**，所以 `../..` 这类值根本到不了 `look`（见类 KDoc）。
     *  3. 符号链接 → 默认工作区。在「存在吗」之前，因为一个指向目录的链接是存在的。
     *  4. 目录不存在 → 默认工作区，但**默认工作区自己不存在时不是纠正**：那是第一次启动，
     *     每条启动路径都会当场建它（`GuestWorkspacePath.ensureHost`），所以这里只说
     *     「名字就是这个」，不说「被删了」。
     *
     * [externals] 是**已登记的外部工作区路径**（`app.workspace.external` 的读数）。它必须参与
     * 第 2 条，否则用户切到设备目录之后重启一次就会被「不是本应用创建的工作区」踢回默认工作区
     * —— 而那句解释还是错的：那个目录是他自己选的，不是坏值。
     *
     * 只做判定，不写任何东西：写回设置是 [WorkspaceStore.refresh] 的事，而它只在这个答案
     * 与设置不一致时才写。
     */
    fun decide(
        requested: String?,
        defaultName: String,
        // Before `look`, deliberately: callers pass the probe as a **trailing lambda**
        // (`decide(requested, DEFAULT) { ... }`), and a trailing lambda binds to the last
        // parameter whatever its type — a new parameter after `look` silently rebinds every
        // such call site to it.
        externals: Collection<String> = emptySet(),
        look: (String) -> Look,
    ): Current {
        val name = requested?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Chosen(defaultName, null)
        if (!isOwned(name) && name !in externals) {
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
     * `Refused`/`Failed` 是 ViewModel 推的通知，屏幕不拼第二句（见 `ProjectScreen.switchWorkspaceTo`
     * 的 KDoc），所以那两条不在这个表里。
     */
    sealed interface Pick {
        /**
         * 那一行现在读不到（SD 卡拔出、卷正在卸载、权限被撤销、目录被删）：一句话，引擎不动。
         *
         * 这一支必须**最先**判。它在列表里看得见 —— 那正是它存在的理由：一个工作区不见了，用户
         * 需要看到「它还在，只是现在拿不到」，而不是那一行凭空消失（那会让人以为注册丢了）。
         */
        object Unavailable : Pick

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
     * 四条判定，顺序是判定的一部分：**先看那一行现在能不能用**，再看是不是当前工作区。
     *
     * 反过来的话有两处错：读不到的当前工作区会得到「已经在这个工作区里」（而引擎其实跑在别处，
     * 因为启动时已经按回退规则回到默认工作区）；回合正跑着时点当前那一行会得到「会中断正在运行
     * 的回合」这句警告，而那一行根本不会中断任何东西 —— 一句吓人的、而且不真的话。
     */
    fun decidePick(isCurrent: Boolean, turnRunning: Boolean, available: Boolean = true): Pick = when {
        !available -> Pick.Unavailable
        isCurrent -> Pick.AlreadyHere
        turnRunning -> Pick.ConfirmInterrupt
        else -> Pick.Switch
    }
}
