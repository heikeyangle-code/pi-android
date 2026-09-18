package app.pi.runtime

import kotlin.system.exitProcess

// `WorkspaceChoice.kt` 的 bare-JVM harness —— 注册在 `tools/run-app-pure-checks.sh` 里叫
// `workspace-choice`。
//
// 为什么这值得钉：`WorkspaceChoice.decide` 是**唯一**决定「引擎在哪个目录里跑」的规则。
// 它错了，进程就活在 `WorkspaceStore` 的 KDoc 点名的那个状态里 —— 设置说 A、引擎跑 B —— 而
// 这个状态在界面上看起来完全正常（工作区列表照常列出 A 和 B，卡上印着一个名字）。规则原来
// 长在 `WorkspaceStore.resolve` 里，那个对象拿 `Context` 读 pi 的 `settings.json`，所以本机
// 一行都执行不到；抽出来之后，下面五条分支（没写 / 不是我们的名字 / 符号链接 / 目录没了 /
// 正常）每一条都能在这里造出来，包括设备上很难造的两条（符号链接、被外部删掉的工作区）。
//
// 第二组同样重要但更容易被忽略：`decidePick` 决定**要不要中断一轮正在跑的回合**。它错了不会
// 报错，只会让「点当前工作区」这件事看起来像要杀一次模型调用（顺序反了），或者让一次真会
// 杀回合的切换不再问用户（少一支）。两种都不留下痕迹。
//
// 这里不碰文件系统：`Look` 是调用方探测后传进来的，所以「我们只探测自己创建的名字」这句
// KDoc 里的保证在下面被当成一个**计数**来断言（不是我们的名字 → 探测 lambda 一次都不许被调）。
//
// 不在这里、也不可能在这里的：切换之后引擎是不是真的在新 cwd 里跑（要真机 + proot 绑定）、
// 新会话是不是真的落在新工作区（要 pi 起一次）、软键盘/返回键在面板里的行为（要 Compose）。
// 那几条是 D54 的真机判据。

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

/** 一次「不许抛」的调用：规则面对垃圾输入时该给一个答案，而不是把异常扔给启动路径。 */
private fun survives(name: String, block: () -> Any?) {
    val outcome = try {
        block()
        "returned"
    } catch (error: Throwable) {
        "threw ${error::class.java.simpleName}: ${error.message}"
    }
    check(name, outcome, "returned")
}

fun main() {
    nameChecks()
    nextFreeChecks()
    labelChecks()
    displayNameChecks()
    decideChecks()
    lookOnlyForOursChecks()
    pickChecks()

    if (failures != 0) {
        println("workspace-choice: FAILED - $failures check(s)")
        exitProcess(1)
    }
    println("harness: OK (all checks passed)")
}

// ------------------------------------------------------------------ 名字 ----

/**
 * `workspace-N` 的边界。这个判据同时是**删除的许可范围**（`previewDelete`/`delete` 先问它），
 * 所以「比想象中宽松」的一侧要付的代价不是一句文案，是一个被递归删掉的目录。
 */
private fun nameChecks() {
    check("workspace-1 is ours", WorkspaceChoice.isOwned("workspace-1"), true)
    check("workspace-999999 is ours", WorkspaceChoice.isOwned("workspace-999999"), true)

    // 不合法的一侧：0 开头、前导 0、超长、不是数字、大小写、前后有空白、空串。
    check("workspace-0 is not ours", WorkspaceChoice.isOwned("workspace-0"), false)
    check("a leading zero is not ours", WorkspaceChoice.isOwned("workspace-01"), false)
    check("seven digits is not ours", WorkspaceChoice.isOwned("workspace-1000000"), false)
    check("a non-numeric suffix is not ours", WorkspaceChoice.isOwned("workspace-x"), false)
    check("uppercase is not ours", WorkspaceChoice.isOwned("Workspace-1"), false)
    check("a prefix is not ours", WorkspaceChoice.isOwned("my-workspace-1"), false)
    check("trailing space is not ours", WorkspaceChoice.isOwned("workspace-1 "), false)
    check("the empty name is not ours", WorkspaceChoice.isOwned(""), false)
    check("the bare prefix is not ours", WorkspaceChoice.isOwned("workspace-"), false)
    // 一个路径不是名字：`../..` 必须在这里就被拒（`decide` 的探测永远不会看到它）。
    check("a path is not ours", WorkspaceChoice.isOwned("../workspace-1"), false)
    check("a plain directory name is not ours", WorkspaceChoice.isOwned("projects"), false)

    check("number reads the digit run", WorkspaceChoice.number("workspace-42"), 42)
    check("number of a foreign name is null", WorkspaceChoice.number("workspace-01"), null)
    check("number of a path is null", WorkspaceChoice.number("../workspace-1"), null)
    survives("number on garbage does not throw") { WorkspaceChoice.number("workspace-99999999999999999999") }
    check(
        "an over-long digit run is refused rather than overflowing",
        WorkspaceChoice.number("workspace-99999999999999999999"),
        null,
    )
}

/**
 * 新建时的编号分配：补空位而不是追加到最大编号后面。
 *
 * `WorkspaceStore.create` 直接用它的答案建目录，所以「返回一个已占用的名字」等于静默失败
 * （`mkdirs()` 在已存在的目录上是 true，于是「新建」看起来成功、什么都没建）。
 */
private fun nextFreeChecks() {
    check("nothing exists: the first name is workspace-1", WorkspaceChoice.nextFreeName(emptyList()), "workspace-1")
    check(
        "a gap is filled rather than appended to the end",
        WorkspaceChoice.nextFreeName(listOf("workspace-1", "workspace-3", "workspace-4")),
        "workspace-2",
    )
    check(
        "a foreign name does not occupy a number",
        WorkspaceChoice.nextFreeName(listOf("workspace-1", "projects", "workspace-01")),
        "workspace-2",
    )
    check(
        "the first free number wins regardless of the order it arrives in",
        WorkspaceChoice.nextFreeName(listOf("workspace-9", "workspace-2")),
        "workspace-1",
    )
    check("a duplicate name is one occupied number", WorkspaceChoice.nextFreeName(listOf("workspace-1", "workspace-1")), "workspace-2")

    // 上限：1..MAX_NUMBER 全占满时没有答案，而不是回一个越界的名字。这个分支在设备上永远
    // 到不了，所以它只能在这里被钉住 —— 而它一旦回错，`create` 会去建 workspace-1000000，
    // 那个名字 `isOwned` 不认，于是它从列表里消失：一个建了却看不见的工作区。
    val full = (1..WorkspaceChoice.MAX_NUMBER).map { "workspace-$it" }
    check("a full range has no free name", WorkspaceChoice.nextFreeName(full), null)
    check("MAX_NUMBER is six digits of headroom", WorkspaceChoice.MAX_NUMBER, 999_999)
    val almostFull = full.filterNot { it == "workspace-${WorkspaceChoice.MAX_NUMBER}" }
    check(
        "the last free number is still reachable",
        WorkspaceChoice.nextFreeName(almostFull),
        "workspace-${WorkspaceChoice.MAX_NUMBER}",
    )
}

/**
 * label 清洗。每一条都对应一种「印出来就不对」的形状：控制字符把一行撑成两行、首尾空白让
 * 两个不同的名字印成一样、超长名字在行里被省略号吃掉一半。
 */
private fun labelChecks() {
    check("a plain label survives", WorkspaceChoice.label("我的项目"), "我的项目")
    check("control characters are dropped", WorkspaceChoice.label("a\nb\tc"), "abc")
    check("surrounding whitespace is trimmed", WorkspaceChoice.label("  abc  "), "abc")
    check("a space-only label is no label", WorkspaceChoice.label("   "), null)
    check("an empty label is no label", WorkspaceChoice.label(""), null)

    val long = "x".repeat(WorkspaceChoice.MAX_LABEL_CHARS + 25)
    check("an over-long label is cut to the cap", WorkspaceChoice.label(long)?.length, WorkspaceChoice.MAX_LABEL_CHARS)
    check(
        "the cap is applied after trimming",
        WorkspaceChoice.label("  " + "y".repeat(WorkspaceChoice.MAX_LABEL_CHARS))?.length,
        WorkspaceChoice.MAX_LABEL_CHARS,
    )
    check(
        "a label of exactly the cap is kept whole",
        WorkspaceChoice.label("z".repeat(WorkspaceChoice.MAX_LABEL_CHARS)),
        "z".repeat(WorkspaceChoice.MAX_LABEL_CHARS),
    )
    check("the cap is 40", WorkspaceChoice.MAX_LABEL_CHARS, 40)
    survives("label on a lone surrogate does not throw") { WorkspaceChoice.label("\uD83D") }
}

/**
 * ① 卡/列表行/通知上印的那个名字，全应用**唯一**的算法。
 *
 * 这一组的存在理由是一次真实的矛盾：卡曾经用会话 cwd 推名字，改过显示名的工作区在卡上印
 * 目录名、在切换面板上印 label。所以这里要钉的是「label 优先，且永远不印一个空名字」。
 */
private fun displayNameChecks() {
    check("no label: the directory name is the name", WorkspaceChoice.displayName("workspace-2", null), "workspace-2")
    check("a label wins", WorkspaceChoice.displayName("workspace-2", "我的项目"), "我的项目")
    check("a blank label falls back to the name", WorkspaceChoice.displayName("workspace-2", ""), "workspace-2")
    check("a space-only label falls back to the name", WorkspaceChoice.displayName("workspace-2", "   "), "workspace-2")
    check("a label equal to the name is just the name", WorkspaceChoice.displayName("workspace-2", "workspace-2"), "workspace-2")
    check("the name is never blank when the label is", WorkspaceChoice.displayName("workspace-1", " "), "workspace-1")
}

// ------------------------------------------------------------ 当前工作区 ----

private val DEFAULT = "workspace-1"

/** 探测结果的两个极端，用来让每条分支只差一个布尔。 */
private val REAL = WorkspaceChoice.Look(isDirectory = true, isSymlink = false)
private val GONE = WorkspaceChoice.Look(isDirectory = false, isSymlink = false)
private val LINK = WorkspaceChoice.Look(isDirectory = true, isSymlink = true)

/**
 * `decide` 的五条分支，逐条对应它 KDoc 里的四条规则。
 *
 * 断言分两层：**结构**（name / requested / note 是否为 null）和**那句话本身**。句子也要钉 ——
 * 它是用户唯一能读到的解释，而它是由这条规则生成的，「回退发生了但说不出为什么」和「没回退
 * 却弹了一句话」都是要靠这张表才能发现的错。
 */
private fun decideChecks() {
    // ① 什么都没写：默认工作区，而且不弹话（第一次启动不该解释一个用户没做过的选择）。
    val unset = WorkspaceChoice.decide(null, DEFAULT) { REAL }
    check("no stored choice names the default", unset.name, DEFAULT)
    check("no stored choice asked for nothing", unset.requested, null)
    check("no stored choice is not a correction", unset.note, null)
    check("no stored choice is not a fallback", unset is WorkspaceChoice.Chosen, true)

    // 空白与 null 同一条路：写进去的一串空格不是一次选择。
    check("a blank stored choice is no choice", WorkspaceChoice.decide("   ", DEFAULT) { REAL }.requested, null)
    check("a blank stored choice does not explain itself", WorkspaceChoice.decide("   ", DEFAULT) { REAL }.note, null)
    check("a value with padding is trimmed", WorkspaceChoice.decide("  workspace-2  ", DEFAULT) { REAL }.name, "workspace-2")

    // ② 不是本应用的名字：回默认，并且说清楚。这一支在任何探测之前。
    val foreign = WorkspaceChoice.decide("projects", DEFAULT) { REAL }
    check("a foreign name falls back to the default", foreign.name, DEFAULT)
    check("a foreign name is reported as asked-for", foreign.requested, "projects")
    check(
        "a foreign name explains itself in one sentence",
        foreign.note,
        "设置里的当前工作区「projects」不是本应用创建的工作区，已回到默认工作区「workspace-1」。",
    )
    check("a path is refused the same way", WorkspaceChoice.decide("../..", DEFAULT) { REAL }.name, DEFAULT)

    // ③ 符号链接：即使它指向一个真实目录（isDirectory = true）也要拒。
    val linked = WorkspaceChoice.decide("workspace-2", DEFAULT) { LINK }
    check("a symlink falls back to the default", linked.name, DEFAULT)
    check(
        "a symlink explains itself in one sentence",
        linked.note,
        "工作区「workspace-2」是一个符号链接，不是一个真实目录，已回到默认工作区「workspace-1」。",
    )
    val linkedDefault = WorkspaceChoice.decide(DEFAULT, DEFAULT) { LINK }
    check("the default itself being a symlink also falls back", linkedDefault.name, DEFAULT)
    check(
        "and it says which one is the link",
        linkedDefault.note,
        "默认工作区「workspace-1」是一个符号链接，不是一个真实目录；请把它换成真实目录。",
    )

    // ④ 目录没了：非默认名字要回退并解释；默认名字不存在**不是**回退（第一次启动）。
    val vanished = WorkspaceChoice.decide("workspace-2", DEFAULT) { GONE }
    check("a deleted workspace falls back to the default", vanished.name, DEFAULT)
    check("a deleted workspace is reported as asked-for", vanished.requested, "workspace-2")
    check(
        "a deleted workspace explains itself in one sentence",
        vanished.note,
        "工作区「workspace-2」的目录不存在（可能被外部删除了），已回到默认工作区「workspace-1」。",
    )
    val unseenDefault = WorkspaceChoice.decide(DEFAULT, DEFAULT) { GONE }
    check("a missing default is still the default", unseenDefault.name, DEFAULT)
    check("a missing default keeps the asked-for name", unseenDefault.requested, DEFAULT)
    check("a missing default is not a correction", unseenDefault.note, null)
    check("a missing default is a Chosen", unseenDefault is WorkspaceChoice.Chosen, true)

    // ⑤ 正常路径：就是它，没有话要说。
    val ok = WorkspaceChoice.decide("workspace-3", DEFAULT) { REAL }
    check("a usable choice is adopted", ok.name, "workspace-3")
    check("a usable choice keeps the asked-for name", ok.requested, "workspace-3")
    check("a usable choice explains nothing", ok.note, null)
    check("a usable choice asks nothing of the caller", ok is WorkspaceChoice.Chosen, true)

    // 结果与那句话的关系，分成三条**真的**不变量来断言。它们比任何一条单独的断言都重要，
    // 因为调用方就是靠它们决定「要不要把这句话印出来」：
    //   ① 有 note ⇒ 答案就是默认工作区。回退只回退到默认，不会回退到「剩下随便哪个」。
    //   ② 设置里什么都没写 ⇒ 没有 note。第一次启动不该解释一个用户没做过的选择。
    //   ③ 有 note 就没别的约束；没 note 而设置里写了名字 ⇒ 采纳的就是那个名字。
    //
    // 「note 非 null ⟺ name != requested」是最先想写的那一条，而它是**错的**：默认工作区自己
    // 是符号链接时，答案不动（没有别处可去）但用户必须被告知。第一次跑这个 harness 时它报了
    // 三处不符（null、空串、默认是链接），这正是这三条分开写的原因。
    val cases = listOf<Pair<String?, (String) -> WorkspaceChoice.Look>>(
        null to { REAL },
        "" to { REAL },
        "   " to { REAL },
        "projects" to { REAL },
        "../.." to { REAL },
        "workspace-2" to { LINK },
        DEFAULT to { LINK },
        "workspace-2" to { GONE },
        DEFAULT to { GONE },
        "workspace-3" to { REAL },
        DEFAULT to { REAL },
    )
    var noteNotOnDefault = 0
    var noteWithoutAChoice = 0
    var quietAdoptionDisagrees = 0
    for ((requested, look) in cases) {
        val answer = WorkspaceChoice.decide(requested, DEFAULT, look)
        val present = requested?.trim()?.takeIf { it.isNotEmpty() }
        if (answer.note != null && answer.name != DEFAULT) noteNotOnDefault++
        if (present == null && answer.note != null) noteWithoutAChoice++
        if (answer.note == null && present != null && answer.name != present) quietAdoptionDisagrees++
    }
    check("a correction always lands on the default workspace", noteNotOnDefault, 0)
    check("nothing stored never produces an explanation", noteWithoutAChoice, 0)
    check("a quiet answer adopts exactly what was asked for", quietAdoptionDisagrees, 0)
}

/**
 * 「我们只探测自己创建的名字」：`decide` 只在名字通过 `isOwned` 之后才调用那个 lambda。
 *
 * 用一个**计数器**而不是注释来保证它。理由不是干净：`WorkspaceStore.resolve` 传进去的
 * lambda 会对 `File(root, name)` 做 `isDirectory`/`isSymbolicLink`，而 `name` 直接来自
 * `settings.json`。一个 `../..` 那样的值被探测一次不会出事（读操作），但「界外的东西从来没被
 * 读过」是这个仓库更愿意守住的性质，而它现在是一条可执行的断言。
 */
private fun lookOnlyForOursChecks() {
    var calls = 0
    val counting: (String) -> WorkspaceChoice.Look = { calls++; REAL }

    WorkspaceChoice.decide(null, DEFAULT, counting)
    check("no stored choice probes nothing", calls, 0)

    WorkspaceChoice.decide("  ", DEFAULT, counting)
    check("a blank stored choice probes nothing", calls, 0)

    check("a foreign name still answers", WorkspaceChoice.decide("projects", DEFAULT, counting).name, DEFAULT)
    check("a foreign name is not probed", calls, 0)

    check("a path is not probed", WorkspaceChoice.decide("../..", DEFAULT, counting).name, DEFAULT)
    check("a path left the probe count alone", calls, 0)

    WorkspaceChoice.decide("workspace-2", DEFAULT, counting)
    check("a name of ours is probed once", calls, 1)
}

// ---------------------------------------------------------- 点了某一行 ----

/**
 * 用户点一下之后屏幕该做什么。四条输入（当前/不是当前 × 有没有回合在跑）对应三种答案。
 *
 * `ConfirmInterrupt` 是唯一会挡住用户的那一支，而它必须在**不是当前工作区**的那一半上：顺序
 * 反了就会出现「点当前这一行，被问『切换会中断正在运行的回合』」—— 一句吓人的、而且不真的话。
 */
private fun pickChecks() {
    check(
        "the current row and an idle engine: nothing to do",
        WorkspaceChoice.decidePick(isCurrent = true, turnRunning = false),
        WorkspaceChoice.Pick.AlreadyHere,
    )
    check(
        "the current row while a turn runs is still nothing to do",
        WorkspaceChoice.decidePick(isCurrent = true, turnRunning = true),
        WorkspaceChoice.Pick.AlreadyHere,
    )
    check(
        "another row and an idle engine: switch",
        WorkspaceChoice.decidePick(isCurrent = false, turnRunning = false),
        WorkspaceChoice.Pick.Switch,
    )
    check(
        "another row while a turn runs: ask first",
        WorkspaceChoice.decidePick(isCurrent = false, turnRunning = true),
        WorkspaceChoice.Pick.ConfirmInterrupt,
    )

    // 顺序本身：只要有回合在跑，唯一能返回 AlreadyHere 的输入就是「点的就是当前工作区」。
    var wrong = 0
    for (current in listOf(true, false)) {
        for (running in listOf(true, false)) {
            val pick = WorkspaceChoice.decidePick(current, running)
            val ok = when {
                current -> pick == WorkspaceChoice.Pick.AlreadyHere
                running -> pick == WorkspaceChoice.Pick.ConfirmInterrupt
                else -> pick == WorkspaceChoice.Pick.Switch
            }
            if (!ok) wrong++
        }
    }
    check("all four inputs agree with the rule", wrong, 0)
}
