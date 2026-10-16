package app.pi.runtime

// A bare-JVM check of `RuntimeUsage`, the 「运行时占用」 row's measurement, registered in
// `tools/run-app-pure-checks.sh` as `runtime-usage`.
//
// Why this check exists: the row used to be `root.walkTopDown().forEach { if (it.isFile)
// bytes += it.length() }`, and that pair is wrong twice over — it follows symlinks
// (`FileTreeWalk` decides whether to descend with `File.isDirectory()`; `java.io.File`'s own
// contract is that `isDirectory`/`isFile`/`length` follow links) and it measures **apparent**
// size (`st_size`), not allocated blocks. The reported number was therefore several times the
// real disk usage on device, which is what the user saw. `RuntimeUsage` takes both decisions
// away from `java.io.File` and this file pins each of them:
//
//   1. a symlink is neither counted nor descended into — the one that used to duplicate a
//      subtree (and, for a link to an ancestor, walk until the path length limit);
//   2. a directory's own blocks are counted exactly once, and the root is counted even
//      though it is only an "operand" (the `du`-compatible half);
//   3. non-file, non-directory entries (FIFO, socket, device) contribute nothing;
//   4. an entry whose allocated size is unavailable counts as a **gap** the caller can
//      report, never as the apparent size;
//   5. an unlistable directory is counted and its subtree simply absent.
//
// Android-free: `RuntimeUsage` imports `java.io.File` and nothing else, and `lstat` arrives
// as a lambda — that is the whole reason the walk is testable here while the
// `android.system.Os` half lives in `RuntimeFacts`.

import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name${if (detail.isEmpty()) "" else "\n  $detail"}")
    }
}

private fun tempDir(label: String): File =
    Files.createTempDirectory("pi-runtime-usage-$label").toFile()

private fun touch(file: File, text: String = "x"): File {
    file.parentFile?.mkdirs()
    file.writeText(text)
    return file
}

/** `st_blocks` is in 512-byte units; the stub statements are built in whole blocks. */
private fun blocks(count: Long): Long = count * 512L

private fun statDir(file: File, count: Long): Pair<String, RuntimeUsage.Stat> =
    file.path to RuntimeUsage.Stat(blocks(count), isDirectory = true, isRegularFile = false, isSymlink = false)

private fun statFile(file: File, count: Long): Pair<String, RuntimeUsage.Stat> =
    file.path to RuntimeUsage.Stat(blocks(count), isDirectory = false, isRegularFile = true, isSymlink = false)

/** A symlink: it "owns" blocks in the stub, and not one of them may be counted. */
private fun statSymlink(file: File, count: Long): Pair<String, RuntimeUsage.Stat> =
    file.path to RuntimeUsage.Stat(blocks(count), isDirectory = false, isRegularFile = false, isSymlink = true)

/** A stub `lstat` over a fixed map, so the walk's decisions are visible as arithmetic. */
private class FakeLstat(private val entries: Map<String, RuntimeUsage.Stat>) {
    var calls = 0
        private set

    fun stat(path: String): RuntimeUsage.Stat? {
        calls++
        return entries[path]
    }
}

fun main() {
    // ------------------------------------------------ the real tree, real links
    run {
        val root = tempDir("links")
        val real = touch(File(root, "a/real.txt"), "0123456789")
        touch(File(root, "a/nested/deep.txt"))
        // A link to a sibling directory: following it is how `walkTopDown` counted every file
        // under `a` twice.
        Files.createSymbolicLink(File(root, "link-to-a").toPath(), real.parentFile.toPath())
        // A link to an ancestor: following it is how the old code walked in circles until the
        // path length limit, which is what turns a 400 MB tree into tens of GB.
        Files.createSymbolicLink(File(root, "a/loop").toPath(), root.toPath())

        val lstat = FakeLstat(
            mapOf(
                statDir(root, 1),
                statDir(real.parentFile, 1),
                statFile(real, 2),
                statDir(File(root, "a/nested"), 1),
                statFile(File(root, "a/nested/deep.txt"), 3),
                statSymlink(File(root, "link-to-a"), 4),
                statSymlink(File(root, "a/loop"), 4),
            ),
        )
        val result = RuntimeUsage.measure(root, lstat::stat)
        check(
            "symlinks are neither counted nor followed",
            result.bytes == blocks(1 + 1 + 2 + 1 + 3),
            "expected 8 blocks (root, a, real.txt, nested, deep.txt), got ${result.bytes / 512}. " +
                "A followed link adds `a`'s subtree a second time, or walks the loop.",
        )
        check("nothing was left unmeasured", result.unmeasured == 0, "unmeasured=${result.unmeasured}")
        check("nothing was unreadable", result.unreadableDirectories == 0)
        check("the result reports itself complete", result.complete)
    }

    // ------------------------------------------- entries with no allocatable size
    //
    // **Every entry this section asserts about is a real file on disk.** The walk's *shape*
    // (which paths exist to be visited) comes from `File.listFiles()`, and only the "what is
    // this entry and how many blocks does it own" half is stubbed. A path that is only in the
    // stub map is never visited — the first version of this fixture missed that, and the four
    // checks below failed with `unmeasured=0` because the block-less entry they were talking
    // about did not exist to be seen.
    run {
        val root = tempDir("gaps")
        // 10 bytes each on disk: if the implementation fell back to the apparent size for the
        // block-less entry, the total below would be 10 bytes too large.
        val file = touch(File(root, "f.txt"), "0123456789")
        val blockless = touch(File(root, "pipe"), "0123456789")
        touch(File(root, "sock"))
        val lstat = FakeLstat(
            mapOf(
                statDir(root, 1),
                statFile(file, 2),
                // The platform gave no block count for this one.
                blockless.path to RuntimeUsage.Stat(
                    allocatedBytes = null,
                    isDirectory = false,
                    isRegularFile = true,
                    isSymlink = false,
                ),
                // Neither a file nor a directory: owns no data, contributes nothing, and is not
                // a "gap" either.
                File(root, "sock").path to RuntimeUsage.Stat(
                    allocatedBytes = blocks(9),
                    isDirectory = false,
                    isRegularFile = false,
                    isSymlink = false,
                ),
            ),
        )
        val result = RuntimeUsage.measure(root, lstat::stat)
        check(
            "an entry without a block count is a counted gap, not the apparent size",
            result.bytes == blocks(1 + 2) && result.unmeasured == 1,
            "bytes=${result.bytes} unmeasured=${result.unmeasured} — `blocks(3)` is 1536; an " +
                "apparent-size fallback would add the block-less file's 10 bytes",
        )
        check("and the result does not call itself complete", !result.complete)
        check(
            "a socket/FIFO contributes nothing and is not a gap",
            result.unmeasured == 1,
            "unmeasured=${result.unmeasured}",
        )
        check(
            "an entry `lstat` cannot answer for is a gap too",
            RuntimeUsage.measure(root) { path -> if (path == file.path) null else lstat.stat(path) }
                .unmeasured == 2,
            "expected the failed `lstat` and the block-less regular file to be two gaps",
        )
    }

    // -------------------------------------- a directory whose contents cannot be listed
    run {
        val root = tempDir("unreadable")
        // `File.listFiles()` answers null for a path that is not a directory. The stub below
        // reports it as one, which is the shape the walk has to survive: a directory entry
        // whose contents cannot be listed (permissions, a tree that vanished mid-walk).
        val sub = touch(File(root, "sub"))
        val lstat = FakeLstat(
            mapOf(
                statDir(root, 1),
                statDir(sub, 1),
            ),
        )
        val result = RuntimeUsage.measure(root, lstat::stat)
        check(
            "a directory that cannot be listed is counted as such",
            result.unreadableDirectories == 1 && result.bytes == blocks(1 + 1),
            "unreadable=${result.unreadableDirectories} bytes=${result.bytes / 512} blocks",
        )
        check("and the result is honest about it", !result.complete)
    }

    // --------------------------------------------------------- the empty case
    run {
        val root = tempDir("empty")
        val lstat = FakeLstat(mapOf(statDir(root, 0)))
        val result = RuntimeUsage.measure(root, lstat::stat)
        check(
            "an empty directory is 0 bytes and complete",
            result.bytes == 0L && result.complete,
            "bytes=${result.bytes} unreadable=${result.unreadableDirectories} unmeasured=${result.unmeasured}",
        )
        check("the root itself was measured", lstat.calls >= 1)
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) exitProcess(1)
}
