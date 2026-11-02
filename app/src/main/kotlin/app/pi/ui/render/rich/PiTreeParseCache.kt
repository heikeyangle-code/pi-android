package app.pi.ui.render.rich

/**
 * 树视图解析结果的小缓存（JSON / XML 共用）。
 *
 * 为什么需要：`PiJsonTreeView` / `PiXmlTreeView` 在**组合线程**上解析（上限 256 KiB / 2 万节点），
 * 而聊天是 `LazyColumn`——一行滚出屏幕再滚回来就是一次全新的组合，`remember` 帮不上，于是每次
 * 滚回一个带大 JSON 的回答都要重新解析整棵树，滑动时卡一下。结果只依赖
 * （种类, 源码, 是否闭合, 限额），限额是常量，所以按前三项缓存是安全的。
 *
 * 有界：最近用到的 [CAPACITY] 条，超出淘汰最久未用的；键里存的是调用方已有的同一个 `String`
 * 引用（不复制），最坏占用是 [CAPACITY] 份源码 + 解析树。
 */
internal object PiTreeParseCache {
    private const val CAPACITY = 16

    private data class Key(val kind: String, val settled: Boolean, val source: String)

    private val entries = object : LinkedHashMap<Key, Any>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Any>): Boolean =
            size > CAPACITY
    }

    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> peek(kind: String, source: String, settled: Boolean): T? =
        entries[Key(kind, settled, source)] as? T

    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrParse(kind: String, source: String, settled: Boolean, parse: () -> T): T {
        val key = Key(kind, settled, source)
        (entries[key] as? T)?.let { return it }
        return parse().also { entries[key] = it }
    }
}

/**
 * 解析放到后台：命中缓存立刻返回；未命中先返回 null（调用方先显示源码 + 「解析中…」），
 * 在 `Dispatchers.Default` 上解析完再换成树。第一次出现大 JSON/XML 不再卡主线程。
 */
@androidx.compose.runtime.Composable
internal fun <T : Any> rememberTreeParse(
    kind: String,
    source: String,
    settled: Boolean,
    parse: () -> T,
): T? {
    val cached = PiTreeParseCache.peek<T>(kind, source, settled)
    if (cached != null) return cached
    return androidx.compose.runtime.produceState<T?>(initialValue = null, kind, source, settled) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            PiTreeParseCache.getOrParse(kind, source, settled, parse)
        }
    }.value
}
