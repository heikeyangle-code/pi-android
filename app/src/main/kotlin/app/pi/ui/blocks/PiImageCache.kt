package app.pi.ui.blocks

import android.graphics.Bitmap
import java.security.MessageDigest

/**
 * 进程级转写图片解码缓存（用户附件缩略图、工具截图、图片查看器）。
 *
 * 相比旧版把整段数 MB 的原始 base64 字符串直接塞进 Map Key（单张截图光是 Key 就吃掉 ~5.6 MB，
 * 32 MiB 预算只能装 2~3 张图），新版对长 payload 在调用方所在的 `Dispatchers.IO` 线程计算
 * SHA-256 指纹 + 长度作为 Key（仅 ~80 字节，节省 99.999% Key 内存，同样 32 MiB 预算可多缓 5~10 倍图片）。
 * 短 key（例如 `file:<路径>:<字节数>:<mtime>`，小于 512 字符）直接沿用原串，零哈希开销。
 *
 * 内存回收：由 `PiMemoryTrimmer` 在系统 `onTrimMemory` / `onLowMemory` 时清理，避免后台驻留位图被 LMK 杀进程。
 */
internal object PiImageCache {

    /** 解码位图与键的总字节预算：32 MiB，与 `PiGuestImageTransformer` 对齐。 */
    internal const val MAX_TOTAL_BYTES: Long = 32L * 1024 * 1024

    /** 超过该长度的 payload（即内联 base64 图片）用 SHA-256 指纹替代原文做键。 */
    private const val DIGEST_THRESHOLD = 512

    private data class Key(val fingerprint: String, val width: Int, val height: Int) {
        val bytes: Long get() = fingerprint.length.toLong() * 2L + 48L
    }

    private fun fingerprintOf(payload: String): String {
        if (payload.length < DIGEST_THRESHOLD) return payload
        val md = MessageDigest.getInstance("SHA-256")
        // 分块摘要，避免把 4 MB 字符串整体再拷贝成一份 4 MB ByteArray。
        val chunk = ByteArray(4096)
        var offset = 0
        val len = payload.length
        while (offset < len) {
            val n = minOf(chunk.size, len - offset)
            for (i in 0 until n) {
                chunk[i] = payload[offset + i].code.toByte()
            }
            md.update(chunk, 0, n)
            offset += n
        }
        val digest = md.digest()
        val hex = CharArray(digest.size * 2)
        val alphabet = HEX_DIGITS
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            hex[i * 2] = alphabet[v ushr 4]
            hex[i * 2 + 1] = alphabet[v and 0x0F]
        }
        return "sha256:$len:${String(hex)}"
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    private val lru = ByteBoundedLru<Key, Bitmap>(MAX_TOTAL_BYTES) { key, bitmap ->
        key.bytes + bitmap.allocationByteCount.toLong()
    }

    /** 同步读取缓存（应在 IO 线程调用）。 */
    fun cached(payload: String, width: Int, height: Int): Bitmap? =
        lru.get(Key(fingerprintOf(payload), width, height))

    /** 存入成功解码的位图。 */
    fun store(payload: String, width: Int, height: Int, bitmap: Bitmap) {
        lru.put(Key(fingerprintOf(payload), width, height), bitmap)
    }

    /** 读穿缓存：命中直接返回，未命中执行 [decode] 并只缓存非空结果。只计算一次指纹。 */
    suspend fun readThrough(
        payload: String,
        width: Int,
        height: Int,
        decode: suspend () -> Bitmap?,
    ): Bitmap? {
        val key = Key(fingerprintOf(payload), width, height)
        lru.get(key)?.let { return it }
        val decoded = decode() ?: return null
        lru.put(key, decoded)
        return decoded
    }

    /** 清空所有缓存位图（系统内存紧张时由 [app.pi.ui.PiMemoryTrimmer] 调用）。 */
    fun clear() {
        lru.clear()
    }
}
