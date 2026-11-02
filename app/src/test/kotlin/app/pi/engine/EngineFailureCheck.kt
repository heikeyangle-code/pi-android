package app.pi.engine

// Bare-JVM harness for `EngineFailure.kt` — 失败页上到底该写什么。
//
// 这一层要防的不是崩溃，而是"所有失败长一个样"：标题一样、解释一样、按钮一样。用户在
// 那种页面上唯一能做的动作是「重建运行时」——它会删掉 guest 里装的 apt/pip/npm 包，却修
// 不好一次被系统回收的进程。所以每一条断言都在问同一个问题：**这一屏是不是说出了真实
// 原因，并且给出了与原因相称的动作**。
//
// 两个方向都会错：
//  - 把"被系统结束"说成"运行环境坏了" → 用户点重建，环境没了，问题还在；
//  - 把"参数写错了"说成"内存不足" → 用户去清内存，而真正的原因（一行 `--flag`）没被提到。
//
// Run it by hand:
//
//   tools/run-app-pure-checks.sh

private var failures = 0
private var checks = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    checks++
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

fun main() {
    // ================================ A. 只有"运行环境没装好"才提供重建
    run {
        val f = EngineFailureClassify.provisioning("运行时解包失败", "tar: unexpected EOF")
        check("A1 分类是 Provisioning", f.kind, EngineFailureKind.Provisioning)
        check("A2 重建按钮在", f.rebuildOffered, true)
        check("A3 标题用宿主给的那一句", f.title, "运行时解包失败")
        check("A4 空标题有兜底", EngineFailureClassify.provisioning("  ", null).title, "运行环境没有装好")
        check("A5 detail 原样带出", f.detail, "tar: unexpected EOF")
    }

    // ================================ B. 切工作区失败：重建不是答案
    run {
        val f = EngineFailureClassify.workspaceSwitch("切换到「b」失败：x", null)
        check("B1 分类是 WorkspaceSwitch", f.kind, EngineFailureKind.WorkspaceSwitch)
        check("B2 没有重建按钮", f.rebuildOffered, false)
        check("B3 标题保留工作区名", f.title, "切换到「b」失败：x")
    }

    // ================================ C. 引擎自己退出：四种原因、四张不同的屏
    run {
        val killed = EngineFailureClassify.exit(137, null, "退出码 137")
        check("C1 SIGKILL 是被系统结束", killed.kind, EngineFailureKind.KilledBySystem)
        check("C2 没有退出码也是被系统结束", EngineFailureClassify.exit(null, "", null).kind, EngineFailureKind.KilledBySystem)
        check("C3 SIGTERM 是被系统结束", EngineFailureClassify.exit(143, null, null).kind, EngineFailureKind.KilledBySystem)
        check("C4 被系统结束不给重建", killed.rebuildOffered, false)

        check("C5 134 是内存耗尽", EngineFailureClassify.exit(134, null, null).kind, EngineFailureKind.OutOfMemory)
        check(
            "C6 V8 的 FATAL ERROR 也是内存耗尽",
            EngineFailureClassify.exit(1, "FATAL ERROR: Reached heap limit Allocation failed", null).kind,
            EngineFailureKind.OutOfMemory,
        )
        check(
            "C7 heap out of memory 也认",
            EngineFailureClassify.exit(1, "FATAL ERROR: Reached heap limit\n<--- JS stacktrace --->", null).kind,
            EngineFailureKind.OutOfMemory,
        )

        check(
            "C8 Unknown option 是配置错",
            EngineFailureClassify.exit(1, "error: Unknown option '--nope'", null).kind,
            EngineFailureKind.Configuration,
        )
        check(
            "C9 扩展加载失败是配置错",
            EngineFailureClassify.exit(1, "Failed to load extension: /x/index.ts", null).kind,
            EngineFailureKind.Configuration,
        )
        check(
            "C10 无证据的退出码 1 是普通崩溃",
            EngineFailureClassify.exit(1, "Segmentation fault", null).kind,
            EngineFailureKind.Crashed,
        )
        // 配置错误优先于内存：一句 `Unknown option` 旁边出现 `FATAL ERROR` 时，用户要改
        // 的是参数，不是内存。
        check(
            "C11 配置错优先于内存",
            EngineFailureClassify.exit(134, "Unknown option '--a'", null).kind,
            EngineFailureKind.Configuration,
        )
        check("C12 引擎退出永远不给重建", EngineFailureClassify.exit(1, null, null).rebuildOffered, false)
    }

    // ================================ D. 六类必须是六张不同的屏
    run {
        val titles = listOf(
            EngineFailureClassify.provisioning("运行时解包失败", null).title,
            EngineFailureClassify.workspaceSwitch("切换工作区失败", null).title,
            EngineFailureClassify.exit(137, null, null).title,
            EngineFailureClassify.exit(134, null, null).title,
            EngineFailureClassify.exit(1, "Unknown option '--a'", null).title,
            EngineFailureClassify.exit(1, null, null).title,
        )
        check("D1 六个标题互不相同", titles.toSet().size, titles.size)
        val explanations = listOf(
            EngineFailureClassify.provisioning("x", null).explanation,
            EngineFailureClassify.workspaceSwitch("x", null).explanation,
            EngineFailureClassify.exit(137, null, null).explanation,
            EngineFailureClassify.exit(134, null, null).explanation,
            EngineFailureClassify.exit(1, "Unknown option '--a'", null).explanation,
            EngineFailureClassify.exit(1, null, null).explanation,
        )
        check("D2 六个解释互不相同", explanations.toSet().size, explanations.size)
        check("D3 解释都不是空话", explanations.none { it.isBlank() }, true)
        // 被系统结束这一类必须**避免**说"运行环境坏了"：那句话会把用户推到重建按钮上。
        check(
            "D4 被系统结束的解释不劝人重建",
            EngineFailureClassify.exit(137, null, null).explanation.contains("重建"),
            false,
        )
    }

    // ================================ E. detail 是唯一能带走的证据
    run {
        check(
            "E1 Detail 原样传给失败页",
            EngineFailureClassify.exit(1, "boom", "退出码 1：boom").detail,
            "退出码 1：boom",
        )
        check("E2 没有证据时是 null", EngineFailureClassify.exit(null, "", null).detail, null)
    }

    println(if (failures == 0) "\nharness: OK ($checks checks)" else "\nharness: FAILED ($failures of $checks)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
