package app.pi.runtime

/**
 * 「是运行时自己拒绝了这次启动，还是被它跑的命令失败了」。
 *
 * 只有前者才该计入失败预算、并用 proot 重试一次；后者是命令自己的错，换运行时也一样会错，
 * 重试只会把一个会写文件的命令跑两遍。判据是**启动器自己的输出**：
 *
 *  - proroot：启动器的消息带 `[proroot]` 前缀，或提到 `libproroot.so`。
 *  - tawcroot：启动器的消息带 `tawcroot:` 前缀（`main.c` 里所有诊断都经 `tawc_io_str("tawcroot: …")`）。
 *
 * 命令自己的输出里碰巧出现这些字样的概率可忽略，而且代价只是多重试一次（proot 上命令会照常跑）。
 */
object EngineRefusal {

    /** 拒绝的那一行（给日志和失败卡片）；不是运行时的拒绝返回 null。 */
    fun of(engine: GuestEngine, text: String): String? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        return when (engine) {
            GuestEngine.Proot -> null
            GuestEngine.Proroot -> {
                val fromLauncher = text.contains("[proroot]") || text.contains("libproroot.so")
                if (fromLauncher) lines.firstOrNull() ?: "proroot 退出码非 0" else null
            }

            GuestEngine.Tawcroot -> lines.firstOrNull { it.startsWith("tawcroot:") }
        }
    }
}
