package app.pi.highlight

import app.pi.ui.render.PiMermaidMode
import app.pi.ui.render.PiMermaidReply

/**
 * The mermaid side of the guest seam: pi's own `grok-mermaid`, asked over the same
 * loopback service as the highlighter.
 *
 * Contract, identical in shape to [PiNodeCodeHighlighter]'s:
 *
 *  - **it never throws.** Every failure — engine not running, no token, a timeout, a
 *    malformed reply — is [PiMermaidReply.Unavailable]; a source `grok-mermaid`
 *    refuses to draw is [PiMermaidReply.NoArt]. Both leave the caller drawing the
 *    fence's own source, which is exactly what pi does whenever `render()` returns
 *    `null` (`components/mermaid.ts:75-76`);
 *  - **the two failures are told apart**, because only one of them is worth another
 *    request: the guest imports `grok-mermaid` on the first `/mermaid` call, so a
 *    first-request timeout is expected on a cold engine and the caller retries it a
 *    bounded number of times, while `NoArt` is final;
 *  - **it is reachable only through [PiNodeCodeHighlighter.attach].** There is one
 *    client instance for one engine, and this renderer borrows it rather than
 *    keeping a second token read and a second set of credentials;
 *  - **no queue of its own.** The request is bounded by the client's own socket
 *    timeouts (100 ms connect, 150 ms read) and the caller runs it off the main
 *    thread, so a slow engine costs one frame's worth of patience and then falls
 *    back. Highlighting has a bounded worker pool because a transcript can compose
 *    dozens of blocks at once; a mermaid fence is one block, and paying the
 *    bookkeeping for it would be cost without benefit.
 *
 * Deliberately absent, like its sibling: no timer, no polling, no warm-up, no work
 * until a fence asks. **Nothing here is on the engine's startup path** — this object
 * is only reached from composition, and the guest's `grok-mermaid` is still imported
 * lazily by the first `/mermaid` request. The cold-start cost of that import is
 * absorbed by the caller's bounded retry (`rememberPiMermaidArt`), not by warming the
 * engine up: a warm-up would run an ESM import plus a layout engine's module body
 * during engine start, competing for exactly the CPU and IO that startup latency is
 * made of, and would be paid by every session including the ones that never draw a
 * diagram.
 */
internal object PiNodeMermaidRenderer {

    /**
     * 缓存项数上限。
     *
     * 比高亮器的 128 小：那张表装的是几 KB 的 span 列表，这里装的是一整张 ASCII 图，
     * 64 项已经比任何一屏对话里的 mermaid 围栏都多。零字节的定论（`NoArt`）也占一个
     * 名额，所以项数上限不是摆设——它单独兜住「字节上限管不到」的那一类。
     */
    private const val CACHE_ENTRIES = 64

    /**
     * 缓存字节上限：4 MiB。
     *
     * 与这个 App 其它跨组合缓存的量级一致（`DiffPlanCache` 4 MiB、`ParseCaches` 4/8 MiB，
     * 见 `docs/scroll-perf-items.md` §7），而不是图片缓存那种 32 MiB ——ASCII 图按
     * 2 字节/字符计，4 MiB 就是约 200 万字符的图。**必须有这个上限**：单份图几 KB 到
     * 几十 KB，只按项数计就是几 MB 级的无界增长。
     */
    private const val CACHE_BYTES = 4 * 1024 * 1024

    /**
     * 进程级缓存，所以跨组合存活。
     *
     * 上面那条契约（「失败要能被重试」）在高亮器那边靠 128 项 LRU 兑现，mermaid 这侧
     * 此前什么都没做，于是围栏滚出 `LazyColumn` 的复用池再滚回来就是**重新问一次
     * guest**、让里面那台布局引擎重跑一遍（冷引擎下最坏 3 次请求 + ≈1.6 s，用户看到
     * 「先闪源码、再变成图」）。这里补上的就是这个形状：命中即返回，`Unavailable` 不写回。
     *
     * 它必须在 `object` 上而不是组合里：`remember` 活不过行的销毁，而「重入」正是要修的
     * 那一幕。键是 `(source, mode)`（`PiMermaidMemo` 强制带 mode —— `final` 与 `streaming`
     * 画的是两张不同的图），上限 [CACHE_ENTRIES] 项 / [CACHE_BYTES] 字节，淘汰按访问序
     * LRU，与高亮器同一套。
     *
     * 代价（与高亮器同形，写在这里以免被当成没有）：缓存键里没有引擎身份，所以换了
     * `grok-mermaid` 版本之后，旧版本画出来的图会一直服务到被 LRU 淘汰或进程重启为止。
     * 图是同一份源码的忠实产物，而引擎换代本来就要重启 App 才生效，所以这个代价被接受；
     * 高亮器对 highlight.js 版本也是同样处理。
     */
    private val memo = PiMermaidMemo<PiMermaidMode, PiMermaidReply>(
        maxEntries = CACHE_ENTRIES,
        maxBytes = CACHE_BYTES,
        sizeOf = ::mermaidReplyBytes,
        // 失败不进缓存；NoArt 是定论，进。理由见类 KDoc 与 `PiMermaidMemo`。
        storable = { reply -> reply !is PiMermaidReply.Unavailable },
    )

    /**
     * 同步读一次缓存，`null` = 没问过。给组合用：生产者在 `produceState` 的
     * `initialValue` 里拿它，命中时首帧就是图，不会先闪一次源码。
     *
     * 只读、无副作用（无竞争时只是几次原子操作），所以可以安全地在组合期调用。
     */
    fun cached(source: String, mode: PiMermaidMode): PiMermaidReply? = memo.get(source, mode)

    /**
     * 一个 source 在一个 mode 下的答案：先看缓存，未命中才问 guest，并把**可缓存**的
     * 答案写回去（失败由 [PiMermaidMemo] 的 `storable` 挡掉）。
     *
     * mode 是参数而不是调用方拼出来的东西：键里必须有它（`final` 与 `streaming` 画的
     * 是两张不同的图），而把 mode 放进签名是让调用方**没法忘**的唯一写法。
     */
    fun render(source: String, mode: PiMermaidMode): PiMermaidReply {
        cached(source, mode)?.let { return it }
        val answer = try {
            PiNodeCodeHighlighter.attached()?.mermaid(source) ?: PiMermaidReply.Unavailable
        } catch (error: Throwable) {
            // A `Throwable` here would otherwise surface inside composition; see the
            // class contract above.
            PiMermaidReply.Unavailable
        }
        memo.put(source, mode, answer)
        return answer
    }
}

/**
 * 一份图的计费字节数（估算，够用的那种）。
 *
 * 框线字符（`─│┌`）不是 Latin-1，JVM 里按 UTF-16 存 = 2 字节/字符，所以按
 * `2 × length` 计；纯 ASCII 的 run 实际只占 1 字节/字符，那是这个估计的余量。
 * 每 run 与每行各加一点对象/列表头开销，因为 64 个短 run 的图
 * 和 1 个长 run 的图字节数可以差很远，而只有字符数计费会低估前者。
 *
 * `NoArt` / `Unavailable` 不占数据：前者的重量在项数上限那边算，后者根本不入缓存。
 */
private fun mermaidReplyBytes(reply: PiMermaidReply): Int = when (reply) {
    is PiMermaidReply.Art -> {
        val rowBytes = reply.art.rows.sumOf { row ->
            row.sumOf { run -> run.text.length * 2 + MERMAID_RUN_OVERHEAD_BYTES } +
                MERMAID_ROW_OVERHEAD_BYTES
        }
        rowBytes + reply.art.warnings.sumOf { warning -> warning.length * 2 }
    }
    PiMermaidReply.NoArt, PiMermaidReply.Unavailable -> 0
}

/** 一个 run 对象（字符串头、枚举引用、列表槽位）的估算开销。 */
private const val MERMAID_RUN_OVERHEAD_BYTES = 48

/** 一行 run 列表的估算开销。 */
private const val MERMAID_ROW_OVERHEAD_BYTES = 32
