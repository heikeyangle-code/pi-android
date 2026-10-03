package app.pi.highlight

import java.io.File

// 一份 bare-JVM 检查，对象是 **mermaid 的进程级缓存策略**（`PiMermaidMemo.kt`）。
//
// 缺陷的形状：mermaid 围栏滚出 `LazyColumn` 的复用池再滚回来，`produceState` 随行销毁重
// 建 ⇒ 重新问一次 guest，冷引擎下最坏 3 次请求 + ≈1.6 s（`PiNodeMermaidRenderer` 现在带
// 的那段说明与 `ui/render/PiMarkdownComponents.kt` 的 `rememberPiMermaidArt`）。修法照高亮器
// 那份：进程级、有界 LRU，键 = `(source, mode)`，失败不进缓存。
//
// 这份检查做两件事：
//
//   1. **真的执行策略本身。** `PiMermaidMemo.kt` 是纯 Kotlin（`java.util.LinkedHashMap`
//      + stdlib），所以本机能编译能跑：命中/未命中、mode 进键、两个上限、逐出顺序、
//      `Unavailable` 不写入且不牵连别的项、同键重写不重复计费。
//   2. **把接线里三件静默出错的事钉成源码文本断言。** 真正的调用点碰 Compose/Android，
//      本机没有 Compose 编译器插件（`tools/typecheck.sh` 只做前端），所以只能读文本——
//      与 `shell-policy-mirror`、`settings-audit` 同一套做法。三件事是：`render` 必须带
//      mode 参数、缓存必须在 `object` 上（不是 `remember`）、`Unavailable` 必须被
//      `storable` 拒绝。
//
// 运行方式（父代理把它注册进 `tools/run-app-pure-checks.sh` 后由 CI 跑；本机手动跑的
// 原始命令与输出见交付报告）：
//
//   java -cp <kotlinc jars> org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
//     -no-stdlib -jvm-target 17 -classpath <stdlib> -d /tmp/out \
//     app/src/test/kotlin/app/pi/highlight/PiMermaidMemoCheck.kt \
//     app/src/main/kotlin/app/pi/highlight/PiMermaidMemo.kt

private val ROOT: File = File(System.getProperty("pi.repo.root") ?: ".")

private const val MERMAID_RENDERER = "app/src/main/kotlin/app/pi/highlight/PiNodeMermaidRenderer.kt"
private const val MARKDOWN_COMPONENTS = "app/src/main/kotlin/app/pi/ui/render/PiMarkdownComponents.kt"

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?, consequence: String) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual\n  → $consequence")
    }
}

private fun sourceOf(relative: String): String? {
    val file = File(ROOT, relative)
    if (!file.isFile) {
        failures++
        println("FAIL 读不到 $relative（-Dpi.repo.root=$ROOT）")
        return null
    }
    return file.readText()
}

// --- 被测策略的替身 ---------------------------------------------------------------
//
// `PiMermaidMemo<M, V>` 对 mode 与答案都是泛型的，所以这里用与生产同形状的替身：
// 一个 mode 枚举（`PiMermaidMode` 的位置）和一个三态答案（`PiMermaidReply` 的位置）。
// 生产接线里那个 `storable = { it !is PiMermaidReply.Unavailable }` 由下面的文本断言钉住；
// 这里钉的是它依赖的**机制**：被拒的值既不入缓存、也不动缓存里已有的项。

private enum class Mode { Off, Final, Streaming }

private sealed interface Reply {
    class Art(val rows: List<String>) : Reply

    object NoArt : Reply

    object Unavailable : Reply
}

/** 权重 = 所有行字符数 ×2（同生产 `mermaidReplyBytes`：框线字按 UTF-16 计）。 */
private fun newMemo(maxEntries: Int, maxBytes: Int) = PiMermaidMemo<Mode, Reply>(
    maxEntries = maxEntries,
    maxBytes = maxBytes,
    sizeOf = { reply -> if (reply is Reply.Art) reply.rows.sumOf { row -> row.length * 2 } else 0 },
    storable = { reply -> reply !is Reply.Unavailable },
)

private fun artOf(chars: Int): Reply.Art = Reply.Art(listOf("x".repeat(chars)))

fun main() {
    // --- 1. 命中 / 未命中 ---------------------------------------------------------
    run {
        val memo = newMemo(maxEntries = 4, maxBytes = 10_000)
        val source = "graph TD; A-->B"
        check(
            "同一 (source, mode) 未写入时是 null",
            memo.get(source, Mode.Streaming),
            null,
            "缓存返回了没写过的值",
        )
        val art = Reply.Art(listOf("┌─┐", "└─┘"))
        memo.put(source, Mode.Streaming, art)
        check(
            "写入后命中同一个值",
            memo.get(source, Mode.Streaming) === art,
            true,
            "缓存没生效，滚回来仍会重问 guest",
        )
        check(
            "同一 mode 的另一个 source 是 miss",
            memo.get("graph TD; A-->C", Mode.Streaming),
            null,
            "键里混进了不该有的东西（比如忽略 source）",
        )
    }

    // --- 2. mode 必须在键里 -------------------------------------------------------
    run {
        val memo = newMemo(maxEntries = 8, maxBytes = 10_000)
        val source = "graph TD; A-->B"
        val streamed = Reply.Art(listOf("streaming-art"))
        val settled = Reply.Art(listOf("final-art"))
        memo.put(source, Mode.Streaming, streamed)
        check(
            "同 source 换 mode 是 miss",
            memo.get(source, Mode.Final),
            null,
            "两个 mode 混成一个键 → 改了设置就拿到上一张图",
        )
        memo.put(source, Mode.Final, settled)
        check("两个 mode 各占一项", memo.entryCount(), 2, "mode 没进键，被后写的覆盖了")
        check(
            "streaming 的那项还在",
            memo.get(source, Mode.Streaming) === streamed,
            true,
            "写另一个 mode 冲掉了本项",
        )
        check(
            "final 的那项是它自己的图",
            memo.get(source, Mode.Final) === settled,
            true,
            "两个 mode 互相覆盖",
        )
    }

    // --- 3. 项数上限与访问序 LRU ---------------------------------------------------
    run {
        val memo = newMemo(maxEntries = 2, maxBytes = 10_000)
        memo.put("a", Mode.Streaming, Reply.NoArt)
        memo.put("b", Mode.Streaming, Reply.NoArt)
        memo.put("c", Mode.Streaming, Reply.NoArt)
        check("项数不超过上限", memo.entryCount(), 2, "项数无界 = 泄漏")
        check("最旧的一项被逐出", memo.get("a", Mode.Streaming), null, "逐出的不是最旧端")
        check(
            "次新与最新都还在",
            listOf(memo.get("b", Mode.Streaming) != null, memo.get("c", Mode.Streaming) != null),
            listOf(true, true),
            "逐出了不该逐的项",
        )

        val touched = newMemo(maxEntries = 2, maxBytes = 10_000)
        touched.put("a", Mode.Streaming, Reply.NoArt)
        touched.put("b", Mode.Streaming, Reply.NoArt)
        touched.get("a", Mode.Streaming) // a 变成最近使用
        touched.put("c", Mode.Streaming, Reply.NoArt)
        check(
            "刚访问过的项不被逐出（访问序，不是插入序）",
            listOf(touched.get("a", Mode.Streaming) != null, touched.get("b", Mode.Streaming)),
            listOf(true, null),
            "淘汰用的是插入序：刚滚回来命中的项会被下一次写入踢掉",
        )
    }

    // --- 4. 字节上限 --------------------------------------------------------------
    run {
        // 每项 100 字符 → 200 字节；预算 500 → 两项 400，第三项进来必须逐出一项。
        val memo = newMemo(maxEntries = 100, maxBytes = 500)
        memo.put("a", Mode.Streaming, artOf(100))
        memo.put("b", Mode.Streaming, artOf(100))
        check("计费正确（两项 400 字节）", memo.byteCount(), 400, "重量算错，上限就是假的")
        memo.put("c", Mode.Streaming, artOf(100))
        check("超预算时逐出最旧", memo.get("a", Mode.Streaming), null, "字节上限没生效")
        check("字节数从不超预算", memo.byteCount(), 400, "计算过冲")
        check("最新一项在", memo.get("c", Mode.Streaming) != null, true, "新项被自己逐出了")
    }

    // --- 5. 比整个预算还重的一项：拒绝，且不牵连别人 --------------------------------
    run {
        val memo = newMemo(maxEntries = 100, maxBytes = 500)
        memo.put("a", Mode.Streaming, artOf(100))
        memo.put("b", Mode.Streaming, artOf(100))
        memo.put("giant", Mode.Streaming, artOf(1000)) // 2000 字节 > 500
        check("超重的一项不入缓存", memo.get("giant", Mode.Streaming), null, "装进了装不下的项")
        check(
            "已有两项没被牵连",
            listOf(memo.get("a", Mode.Streaming) != null, memo.get("b", Mode.Streaming) != null),
            listOf(true, true),
            "一份病态大图把整个缓存清空了：下次滚动要为所有图重问 guest",
        )
        check("字节数仍不超预算", memo.byteCount(), 400, "拒绝路径也改了账")
    }

    // --- 6. 失败不入缓存，也不逐出别人 ---------------------------------------------
    run {
        val memo = newMemo(maxEntries = 2, maxBytes = 10_000)
        memo.put("a", Mode.Streaming, Reply.NoArt)
        memo.put("b", Mode.Streaming, Reply.NoArt)
        memo.put("c", Mode.Streaming, Reply.Unavailable)
        check("Unavailable 不写入", memo.get("c", Mode.Streaming), null, "一次网络抖动被永久缓存成「没有图」")
        check("失败不占名额", memo.entryCount(), 2, "一次失败顺手淘汰了别人")
        check(
            "原有两项都还在",
            listOf(memo.get("a", Mode.Streaming) != null, memo.get("b", Mode.Streaming) != null),
            listOf(true, true),
            "被拒绝的写入动了缓存",
        )
    }

    // --- 7. 零字节的定论仍受项数上限约束 -------------------------------------------
    run {
        val memo = newMemo(maxEntries = 3, maxBytes = 10_000)
        repeat(5) { index -> memo.put("no-art-$index", Mode.Streaming, Reply.NoArt) }
        check("零字节项也守项数上限", memo.entryCount(), 3, "只有字节上限时 NoArt 可以无界堆积")
    }

    // --- 8. 同键重写不重复计费 -----------------------------------------------------
    run {
        val memo = newMemo(maxEntries = 10, maxBytes = 500)
        memo.put("a", Mode.Streaming, artOf(100))
        val smaller = artOf(50)
        memo.put("a", Mode.Streaming, smaller)
        check("同键重写后是新值", memo.get("a", Mode.Streaming) === smaller, true, "重写没生效")
        check("同键重写不重复计费", memo.byteCount(), 100, "重量按两次累加，账虚高会误逐出")
        check("也没有留下旧项", memo.entryCount(), 1, "同一个键存了两份")
    }

    // --- 9. 上限非正 = 不缓存（防御，不炸） ----------------------------------------
    run {
        val noEntries = newMemo(maxEntries = 0, maxBytes = 10_000)
        noEntries.put("a", Mode.Streaming, Reply.NoArt)
        check("项数上限为 0 等于不缓存", noEntries.entryCount(), 0, "配置成 0 却仍在存")

        val noBytes = newMemo(maxEntries = 4, maxBytes = 0)
        noBytes.put("a", Mode.Streaming, artOf(1))
        check("字节上限为 0 等于不缓存", noBytes.entryCount(), 0, "预算为 0 却仍在存")
    }

    // --- 10. 组合线程读 / Default 线程写：两个上限都不破 ---------------------------
    run {
        val memo = newMemo(maxEntries = 8, maxBytes = 4_000)
        val thrown = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (0 until 4).map { worker ->
            Thread {
                try {
                    repeat(500) { round ->
                        val source = "s-${(worker * 500 + round) % 40}"
                        memo.get(source, Mode.Streaming)
                        memo.put(source, Mode.Streaming, artOf((round % 5) * 20))
                    }
                } catch (error: Throwable) {
                    thrown.incrementAndGet()
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        check("并发下不抛异常", thrown.get(), 0, "访问序 map 的 get 会改结构：漏锁就是竞态")
        check("并发后项数仍在上限内", memo.entryCount() <= 8, true, "并发写破了项数上限")
        check("并发后字节数仍在上限内", memo.byteCount() <= 4_000, true, "并发写破了字节上限")
    }

    // --- 11. 接线：源码文本断言（本机编不了 Compose 的那半边） ----------------------
    val renderer = sourceOf(MERMAID_RENDERER)
    val components = sourceOf(MARKDOWN_COMPONENTS)
    if (renderer != null && components != null) {
        check(
            "render 把 mode 当参数（调用方没法只按 source 缓存）",
            renderer.contains("fun render(source: String, mode: PiMermaidMode)"),
            true,
            "mode 没进签名 → 键里可能只剩 source，换设置后拿到上一张图",
        )
        check(
            "缓存挂在 object 上（进程级），不是 remember",
            renderer.contains("private val memo = PiMermaidMemo<PiMermaidMode, PiMermaidReply>("),
            true,
            "缓存活在组合里 → 行一销毁就没了，滚回来照样重问 guest",
        )
        check(
            "Unavailable 被 storable 拒绝",
            renderer.contains("reply !is PiMermaidReply.Unavailable"),
            true,
            "失败被当成答案缓存住：一次引擎没起来就永久显示源码",
        )
        check(
            "Renderer 不引用组合期的 remember",
            renderer.contains("remember("),
            false,
            "缓存被挪进了组合，跨组合存活的前提没了",
        )
        check(
            "组合接线把 mode 传给 render",
            components.contains("PiNodeMermaidRenderer.render(code, mode)"),
            true,
            "producer 没带 mode",
        )
        check(
            "组合首帧先读缓存",
            components.contains("PiNodeMermaidRenderer.cached(code, mode)"),
            true,
            "命中时仍会先闪一次源码",
        )
        check(
            "命中时 producer 直接返回",
            components.contains("if (cached != null) return@produceState"),
            true,
            "命中了还发请求、还等 settle",
        )
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
