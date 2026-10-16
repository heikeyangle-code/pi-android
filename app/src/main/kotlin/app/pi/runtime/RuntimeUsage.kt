package app.pi.runtime

import java.io.File

/**
 * A directory tree's **allocated** size in bytes, counted without following symlinks.
 *
 * ## Why this exists instead of `walkTopDown` + `File.length()`
 *
 * That pair is wrong in two independent ways, and both inflate the number — which is what
 * the 「运行时占用」 row was reporting:
 *
 *  1. **It follows symlinks.** `FileTreeWalk` decides whether to descend with
 *     `java.io.File.isDirectory()` (verified in `kotlin-stdlib`'s bytecode:
 *     `FileTreeWalk$FileTreeWalkIterator.gotoNext()` calls `File.isDirectory`, and the
 *     iterator's constructor calls `isDirectory`/`isFile` on the start), and `File`'s own
 *     contract is that those follow links. So every directory a link points at is walked
 *     *again*, a link to an ancestor is walked until the path length limit stops it, and a
 *     link to something outside the tree drags that whole subtree into the total. `File.length()`
 *     follows too.
 *  2. **It measures apparent size.** `File.length()` is `st_size`, which counts a sparse
 *     file's holes as if they were allocated. The disk usage of a tree is `st_blocks * 512`,
 *     the same number `du` reports.
 *
 * This object takes the decision away from `java.io.File`: every entry is classified by its
 * **own** link (`lstat`), so a symlink is neither counted nor descended into, and the size
 * is the allocated blocks. The `lstat` is injected ([Stat]) because only Android can ask for
 * `st_blocks` ([android.system.Os.lstat] + `StructStat.st_blocks`; `java.nio`'s
 * `PosixFileAttributes` does not carry blocks) — which is also what lets the bare-JVM
 * harness execute the whole walk with a stub.
 *
 * ## The one entry that *is* followed
 *
 * [measure]'s `root` is an operand, not something the tree contains: `du`-style, a directory
 * named on the command line is measured and descended into even when reached through a
 * symlink. Every child below it is strictly `lstat`, no follow.
 *
 * ## Cost
 *
 * One `lstat` per entry plus one `listFiles` per directory — the same order as the
 * `walkTopDown` + `isFile` + `length` it replaces (three syscalls per entry there, one
 * here). The settings screen already runs it off the main thread (`RuntimeFacts` is read on
 * `Dispatchers.IO`), and it is not cached: the number has to be true when the screen is
 * opened, and the tree it measures only changes during provisioning.
 */
object RuntimeUsage {

    /**
     * What [android.system.Os.lstat] says about one path — about the **link itself**.
     *
     * @param allocatedBytes `st_blocks * 512`, or null when the platform could not give it.
     *        Null is never replaced by the apparent size ([java.io.File.length]): a number
     *        that is quietly a different number is worse than a counted gap.
     */
    data class Stat(
        val allocatedBytes: Long?,
        val isDirectory: Boolean,
        val isRegularFile: Boolean,
        val isSymlink: Boolean,
    )

    /**
     * @param bytes the summed allocated size of the tree.
     * @param unmeasured entries whose allocated size could not be read (`lstat` failed, or
     *        the platform gave no block count). They contribute 0 and the caller must say so
     *        rather than present the total as complete.
     * @param unreadableDirectories directories whose contents could not be listed.
     */
    data class Result(val bytes: Long, val unmeasured: Int, val unreadableDirectories: Int) {
        val complete: Boolean get() = unmeasured == 0 && unreadableDirectories == 0
    }

    fun measure(root: File, stat: (String) -> Stat?): Result {
        var bytes = 0L
        var unmeasured = 0
        var unreadable = 0

        fun add(entry: Stat?) {
            if (entry == null) {
                unmeasured++
                return
            }
            val allocated = entry.allocatedBytes
            if (allocated == null) unmeasured++ else bytes += allocated
        }

        fun walk(directory: File) {
            val children = directory.listFiles()
            if (children == null) {
                unreadable++
                return
            }
            for (child in children) {
                val entry = stat(child.path)
                if (entry == null) {
                    unmeasured++
                    continue
                }
                // A symlink is neither its own few bytes nor its target's contents: it owns no
                // file data, and following it is the defect this object exists to remove.
                if (entry.isSymlink) continue
                if (entry.isDirectory) {
                    add(entry)
                    walk(child)
                } else if (entry.isRegularFile) {
                    add(entry)
                }
                // Anything else (FIFO, socket, device node) owns no file data.
            }
        }

        add(stat(root.path))
        walk(root)
        return Result(bytes, unmeasured, unreadable)
    }
}
