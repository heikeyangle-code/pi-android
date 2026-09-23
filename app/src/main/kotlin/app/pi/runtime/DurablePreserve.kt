package app.pi.runtime

import java.io.File
import java.io.IOException

/**
 * 「把耐久目录搬出易失树 → 删 → 再搬回来」这三步。
 *
 * ## 它防的是什么
 *
 * [RuntimeProvisioner] 的 `wipe()` 是**唯一**会整棵删 `<files>/pi/runtime` 的地方，也是
 * 唯一能到「用户显式点修复」那条路的动作。今天它安全，因为三个耐久目录都在易失树**之外**
 * （[DurableLayout]）；但 proroot 的绑定缺陷推着我们考虑把工作区和 agent 目录搬进
 * rootfs——那之后 `wipe()` 就会连用户数据一起删。
 *
 * ## 为什么是「先搬走」而不是「删的时候跳过」
 *
 * 递归删除里的跳过判断一旦写错（拼写、符号链接、嵌套），结果是**静默丢数据**；而搬走
 * 失败会在这里**抛异常**，调用方被迫放弃这次删除，数据还在原处，`.preserve` 里可能还留着
 * 一份。**宁可让重建失败，也不能在数据没搬出来的情况下删树** —— [moveOut] 的一半代码
 * 就是这句话。
 *
 * ## 为什么单独成文件
 *
 * 不 import 任何 Android 类，所以 bare-JVM harness 能拿真实目录把整条路径跑一遍，包括
 * 「搬不动就拒绝」那条分支（`RuntimePayloadStateCheck.kt` 的 G 段）。
 *
 * ## 是 rename，不是复制
 *
 * [File.renameTo] 在同一个文件系统里只改目录项，所以搬一个几 GB 的工作区和搬一个空目录
 * 一样快。[DurableLayout] 的耐久目录与 [PRESERVE_DIR] 都在 App 私有目录下，同盘。**跨盘时
 * `renameTo` 返回 false，于是走的是拒绝分支** —— 不会退化成一次静默的半拷贝。
 */
object DurablePreserve {

    /**
     * 搬走时的落脚目录名，位于 `<files>/pi/` 下。
     *
     * 必须在易失树**之外**：它要活过的那一次删除，删的正是 `<files>/pi/runtime`。
     */
    const val PRESERVE_DIR: String = ".preserve"

    /** 一次成功的搬走。[entries] 的每一项是 `落脚位置 to 原位置`。 */
    class Stash internal constructor(val entries: List<Pair<File, File>>) {
        val count: Int get() = entries.size
    }

    /**
     * 把 [durable] 里**确实存在**的目录移进 [preserveDir]，返回落脚清单。
     *
     * 任何一次搬动失败都会**先把已经搬走的放回原位**，然后抛 [IOException]。调用方拿到
     * 异常就必须放弃这次删除。
     *
     * `durable` 为空、或里面一个目录都不存在时，**不创建 [preserveDir]**、直接返回空
     * 清单：这是今天（三个耐久目录都在易失树之外）的路径，它必须与没有这个对象时逐字一致。
     */
    @Throws(IOException::class)
    fun moveOut(durable: List<File>, preserveDir: File): Stash {
        val present = durable.filter { it.isDirectory }
        if (present.isEmpty()) return Stash(emptyList())
        if (!preserveDir.isDirectory && !preserveDir.mkdirs()) {
            throw IOException("无法创建搬走落脚目录 ${preserveDir.path}")
        }
        val entries = mutableListOf<Pair<File, File>>()
        for (dir in present) {
            // 名字带纳秒，所以同一轮里两个同名的耐久目录不会互相覆盖；落脚名字本身没有
            // 语义，恢复时用的是 `entries` 里记下的原位置。
            val stash = File(preserveDir, "${dir.name}-${System.nanoTime()}")
            if (!dir.renameTo(stash)) {
                restore(Stash(entries))
                throw IOException("无法把 ${dir.path} 移出易失树（目标 ${stash.path}）")
            }
            entries += stash to dir
        }
        return Stash(entries)
    }

    /**
     * 把 [stash] 里的每一项搬回原位，返回**没能归位**的原位置（空 = 全部归位）。
     *
     * 有搬不回的项时调用方**不要**清 `.preserve`：那份数据还在里面，是唯一能救回来的地方。
     */
    fun restore(stash: Stash): List<File> = stash.entries.mapNotNull { (stashDir, original) ->
        original.parentFile?.mkdirs()
        if (stashDir.renameTo(original)) null else original
    }
}
