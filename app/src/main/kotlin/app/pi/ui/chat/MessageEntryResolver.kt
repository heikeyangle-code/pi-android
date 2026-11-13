package app.pi.ui.chat

/**
 * 把对话页上的一行消息对回 pi 会话里的**条目 id**。
 *
 * 为什么需要：标签 / 从此处继续 / 从上下文隐藏 这些 SDK 操作都按条目 id 做事，而对话页的行键
 * 有两种来路——重放历史的行，键就是 pi 的条目 id（一个条目拆成多块时是 `id`、`id-1`、`id-2`…）；
 * 本次运行里实时生成的行，键是合成的 `user-<毫秒>-<序号>`，pi 根本不认识。所以：
 *
 *  1. 先当 id 查（原样，再去掉末尾的 `-<数字>` 块序号）——命中就是它；
 *  2. 否则按角色 + 正文，从**最新的**条目往回找（同一句话说过两遍时，用户长按的几乎总是后一条）。
 *
 * 纯逻辑、不碰 JSON：调用方把原始条目折成 [EntryText] 再来问，所以能被裸 JVM 的 harness 单独验证。
 */
data class EntryText(val id: String, val role: String, val texts: List<String>)

object MessageEntryResolver {

    fun resolve(entries: List<EntryText>, key: String, role: String, text: String): String? {
        val byId = entries.associateBy { it.id }
        for (candidate in idCandidates(key)) {
            val hit = byId[candidate]
            if (hit != null && hit.role == role) return hit.id
        }
        if (text.isEmpty()) return null
        return entries.lastOrNull { it.role == role && it.texts.any { block -> block == text } }?.id
            ?: entries.lastOrNull { it.role == role && it.texts.joinToString("\n") == text }?.id
    }

    private fun idCandidates(key: String): List<String> {
        val stripped = key.replace(BLOCK_SUFFIX, "")
        return if (stripped == key) listOf(key) else listOf(key, stripped)
    }

    private val BLOCK_SUFFIX = Regex("-\\d+$")
}
