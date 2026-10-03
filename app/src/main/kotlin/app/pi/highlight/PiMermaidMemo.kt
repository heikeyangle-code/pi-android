package app.pi.highlight

/**
 * mermaid 结果的进程级有界缓存：形状照 [PiNodeCodeHighlighter] 那份（访问序 LRU），
 * 只为「滚出去再滚回来别再问 guest」而存在。
 *
 * ## 为什么需要它
 *
 * 高亮器有 128 项 LRU（键 = `(language, code)` 的 SHA-256），所以代码块滚回来**直接
 * 就是彩色**；mermaid 这一侧此前没有任何缓存，`produceState` 一随行销毁重建就重新
 * 发请求 —— 冷引擎下最坏 3 次请求 + ≈1.6 s，用户看到的是「先闪源码、再变成图」，
 * 而 guest 侧把那台布局引擎又跑了一遍。缓存把这件事变成一次 map 查找。
 *
 * ## 键里为什么必须有 mode
 *
 * 同一个 fence 源在 `final` 与 `streaming` 下出的是**两张不同的图**（pi 自己的
 * `markdown.mermaid` 就是按 mode 分岔的，`components/mermaid.ts:62-69`），所以键是
 * `(source, mode)` 这一对。这里把它做成 [put]/[get] 的**两个参数**而不是让调用方拼一个
 * 字符串：调用方无法「只按 source 缓存」——那是这张缓存唯一能出的静默错误（换 mode
 * 之后拿到上一个 mode 的图）。
 *
 * ## 两个上限，都是必须的
 *
 * - [maxEntries]：项数上限。值可能是 [PiMermaidReply.NoArt] 这种**零字节**的定论，
 *   只有字节上限的话它可以无界堆积。
 * - [maxBytes]：字节上限。一份 ASCII 图从几 KB 到几十 KB，只有项数上限的话
 *   64 项也能到几 MB；上限存在，泄漏就不存在。
 *
 * 淘汰是访问序 LRU（`LinkedHashMap(accessOrder = true)`：`get` 把命中项挪到最新端，
 * 逐出从最旧端开始），与高亮器同一套语义。
 *
 * ## 失败不进缓存，是调用方的 [storable] 决定的
 *
 * 这个类不认识「失败」，它只问 [storable]：返回 `false` 的值**不写入，也不动缓存里
 * 已有的任何一项**（一次被拒绝的 put 不能顺手淘汰别的东西）。`PiNodeMermaidRenderer`
 * 传进来的是「不是 [app.pi.ui.render.PiMermaidReply.Unavailable] 才存」，理由写在
 * 那里：`Unavailable` 说的是**引擎**当下的状态（没起来、还在 import、超时），不是
 * 这个 source 的性质，把它缓存住就等于让一次网络抖动永久占据一个「没有图」的结论；
 * 而 `NoArt` 是 `grok-mermaid` 对**这份源码**的定论，正是该被缓存的那种答案。
 *
 * 线程安全：内部的访问序 map **连 `get` 都会改结构**，所以每个入口都在同一个私有锁里。
 * 调用方来自组合线程与 `Dispatchers.Default` 上的 producer。
 *
 * 纯 Kotlin（`java.util.LinkedHashMap` + stdlib，无 Android、无 Compose），所以
 * `PiMermaidMemoCheck` 能在 bare JVM 上编译并执行它。
 */
internal class PiMermaidMemo<M : Any, V : Any>(
    private val maxEntries: Int,
    private val maxBytes: Int,
    private val sizeOf: (V) -> Int,
    private val storable: (V) -> Boolean,
) {

    /** `(source, mode)` 这一对才是键；mode 的类型由使用方给（这里是 `PiMermaidMode`）。 */
    private data class Key<T>(val source: String, val mode: T)

    private class Held<V>(val value: V, val bytes: Int)

    private val lock = Any()

    /** 访问序：迭代顺序 = 最近使用顺序，最旧端是逐出端。 */
    private val entries = LinkedHashMap<Key<M>, Held<V>>(16, 0.75f, true)

    private var bytes = 0

    /** [source] 配 [mode] 的那一项，命中即标记为最近使用；没有就是 `null`。 */
    fun get(source: String, mode: M): V? = synchronized(lock) {
        entries[Key(source, mode)]?.value
    }

    /**
     * 存下一项。
     *
     * 被 [storable] 拒绝的值、以及比整个字节预算还重的值，都**原样返回、不碰缓存**：
     * 前者是失败，后者是一份病态的巨大图——为了它把别人全逐出，下一次滚动就要为所有
     * 图重新问 guest，代价比留着它大得多。
     */
    fun put(source: String, mode: M, value: V) {
        if (maxEntries <= 0 || maxBytes <= 0) return
        if (!storable(value)) return
        val weight = sizeOf(value).coerceAtLeast(0)
        if (weight > maxBytes) return
        synchronized(lock) {
            val key = Key(source, mode)
            // 同一个键重新写入：先把它自己的旧重量扣掉，重量才不会重复计。
            entries.remove(key)?.let { previous -> bytes -= previous.bytes }
            entries[key] = Held(value, weight)
            bytes += weight
            while (entries.size > maxEntries || bytes > maxBytes) {
                val eldest = entries.keys.firstOrNull() ?: break
                val evicted = entries.remove(eldest) ?: break
                bytes -= evicted.bytes
            }
        }
    }

    /** 当前项数；诊断与纯检查用，热路径不读。 */
    fun entryCount(): Int = synchronized(lock) { entries.size }

    /** 当前计费字节数，永不超过 [maxBytes]；诊断与纯检查用。 */
    fun byteCount(): Int = synchronized(lock) { bytes }
}
