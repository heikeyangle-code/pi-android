package app.pi.runtime

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream

/**
 * Minimal, strict tar reader.
 *
 * Why hand-rolled: the runtime ships an Ubuntu rootfs and a Node tree, and there
 * is no tar in the Android platform we can rely on. `java.util.zip` gives us
 * gzip but not tar, and pulling in a megabyte of Apache Commons Compress to
 * unpack two archives at first launch is not a trade worth making.
 *
 * Why gzip and not xz: Java has no xz decoder. `tools/fetch-runtime.mjs`
 * therefore re-packs Node's official `.tar.xz` as gzip at build time, so the
 * device only ever needs gzip. The payloads are named `.tgz` for a reason that has
 * nothing to do with their contents — see `PAYLOAD_SUFFIX` there, and
 * `RuntimeProvisioner`'s.
 *
 * Correctness properties that matter for a Linux userland:
 *  - **symlinks and hardlinks are recreated**, not dereferenced. A rootfs where
 *    `/bin/sh -> dash` became a copy is subtly broken, and so is the git payload:
 *    139 of its `/usr/lib/git-core` entries are symlinks to the one `git` binary,
 *    which is what makes the exec path cost one binary instead of a hundred and
 *    fifty. Note the packaging consequence: a build that dereferences links is how
 *    an APK ends up hundreds of megabytes larger than its contents.
 *  - **the executable bit is applied** from the tar mode. Without it nothing in
 *    the userland runs.
 *  - **PAX and GNU long-name records are honoured**; Ubuntu's rootfs does
 *    contain paths longer than the 100-byte ustar field.
 *  - **entries may not escape the destination.** A `..` component or an absolute
 *    path is rejected rather than followed — the archives are third-party
 *    downloadables and this is the one place a mistake is unrecoverable.
 */
class TarExtractor(
    private val destination: File,
    private val onEntry: ((String) -> Unit)? = null,
) {

    private var filesWritten = 0L
    private var bytesWritten = 0L
    private var entriesSeen = 0L

    data class Result(val files: Long, val bytes: Long, val entries: Long)

    fun extractGzip(stream: InputStream): Result {
        destination.mkdirs()
        GZIPInputStream(BufferedInputStream(stream, 1 shl 16)).use { gz ->
            extractTar(gz)
        }
        return Result(filesWritten, bytesWritten, entriesSeen)
    }

    private fun extractTar(input: InputStream): Result {
        val header = ByteArray(512)
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null

        while (true) {
            if (!readFully(input, header, 512)) {
                // The stream ran out where a header should start. A well-formed archive
                // ends with a zero block (the check below), so reaching here means the
                // payload is truncated — and this is the one truncation that used to
                // look like success: `readFully` reported "no bytes at all" the same way
                // it reported the end of the data, so an archive cut exactly on a
                // 512-byte boundary extracted a **partial rootfs** and then had its
                // provisioning stamp written, i.e. a device that boots into a
                // half-extracted runtime with nothing on screen saying so.
                //
                // `IOException` rather than `IllegalStateException` so
                // `RuntimeProvisioner.extractAsset` wraps it with the payload audit and
                // names the archive.
                throw IOException("tar payload ended before its end-of-archive marker（档案被截断？）")
            }
            if (header.all { it == 0.toByte() }) break // end-of-archive marker

            val nameRaw = header.string(0, 100)
            val mode = header.octal(100, 8)
            val size = header.octal(124, 12)
            val typeFlag = header[156].toInt().toChar()
            val linkNameRaw = header.string(157, 100)
            val prefix = header.string(345, 155)

            entriesSeen++

            when (typeFlag) {
                // Metadata records: consume the payload and remember it.
                'L' -> { longName = readPayload(input, size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n'); continue }
                'K' -> { longLink = readPayload(input, size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n'); continue }
                'x', 'X' -> {
                    val records = readPayload(input, size).toString(Charsets.UTF_8)
                    parsePax(records).let { pax ->
                        pax["path"]?.let { paxPath = it }
                        pax["linkpath"]?.let { paxLink = it }
                    }
                    continue
                }
                'g' -> { readPayload(input, size); continue } // global pax header
            }

            val rawName = longName ?: paxPath ?: if (prefix.isNotEmpty()) "$prefix/$nameRaw" else nameRaw
            val linkName = longLink ?: paxLink ?: linkNameRaw
            longName = null; longLink = null; paxPath = null; paxLink = null

            val target = resolveSafely(rawName) ?: run {
                // Refused: skip the payload but keep the stream aligned.
                skip(input, size)
                continue
            }

            when (typeFlag) {
                '5' -> {
                    ensureDirectory(target)
                    onEntry?.invoke(rawName)
                }
                '2' -> {
                    ensureParent(target)
                    unlinkIfPresent(target)
                    runCatching { Files.createSymbolicLink(target.toPath(), Path.of(linkName)) }
                        .onFailure {
                            // Some Android filesystems refuse symlinks; a copy is a
                            // worse but working fallback, and in practice this only
                            // ever happens for non-executable data.
                            if (!linkName.startsWith("/")) {
                                val src = File(target.parentFile, linkName)
                                if (src.exists()) runCatching { Files.copy(src.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                            }
                        }
                    onEntry?.invoke(rawName)
                }
                '1' -> {
                    // Hard link: recreate by copying. Android's storage cannot
                    // create hardlinks across these trees, and a copy preserves
                    // behaviour for every case pi hits (the size cost is why the
                    // build must not ship a dereferenced tree in the first place).
                    ensureParent(target)
                    unlinkIfPresent(target)
                    val src = resolveSafely(linkName)
                    if (src != null && src.exists()) {
                        runCatching { Files.copy(src.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                            .onSuccess { filesWritten++ }
                    }
                    onEntry?.invoke(rawName)
                }
                '0', '\u0000', '7' -> {
                    ensureParent(target)
                    unlinkIfPresent(target)
                    writeFile(input, target, size)
                    applyMode(target, mode)
                    onEntry?.invoke(rawName)
                }
                else -> skip(input, size) // char/block/fifo: unused by a rootfs tarball
            }
        }
        return Result(filesWritten, bytesWritten, entriesSeen)
    }

    /**
     * Make [dir] a directory, clearing whatever **non-directory** sits in its place.
     *
     * ## Why this is not `dir.mkdirs()`
     *
     * Extraction is an *overwrite*: a changed payload is unpacked onto the tree the
     * previous one left, and only afterwards does `RuntimeProvisioner.prunePayload`
     * delete what the old payload owned and the new one does not. That ordering is
     * deliberate (the prune needs the new list, which is read from the APK either way),
     * but it means the extraction itself has to survive a tree that no longer matches:
     * if a path is a **directory** in the new payload and a regular file — or a symlink
     * whose target is gone — in the old one, `mkdirs()` returns `false` **without
     * creating anything**, and the very next write fails with a bare
     * `open failed: ENOENT` against a file whose parent "should" be there.
     *
     * That is not hypothetical. A device showed exactly this on every overwrite
     * install:
     *
     *     failed to unpack pi-engine.tgz: <…>/dist/bundle/cli.js: open failed: ENOENT
     *
     * — `dist/bundle` could not be created, so `cli.js` had nowhere to go. Retrying
     * could not help (`prunePayload` runs *after* a successful extraction, so the node
     * in the way is never reached), and the only way out was 「重建运行时」, i.e. wiping
     * the guest and everything the user had installed in it. Replaying the same two
     * payloads onto a *clean* tree extracted all 14941 files without one failure —
     * the archive was never at fault.
     *
     * Clearing is deliberately non-recursive and never follows a link: this can remove
     * a symlink, a regular file, or an **empty** directory. A non-empty directory is
     * left alone — the payload lists own no directories (`PayloadPrune`'s KDoc), so
     * anything under one is the user's, and the write that follows will fail with the
     * node named rather than quietly deleting their files.
     */
    private fun ensureDirectory(dir: File) {
        if (dir.isDirectory && !isSymlink(dir)) return
        unlinkIfPresent(dir)
        dir.mkdirs()
    }

    /** [ensureDirectory] for every component between [destination] and [target]'s parent. */
    private fun ensureParent(target: File) {
        val parent = target.parentFile ?: return
        val relative = parent.path.removePrefix(canonicalBase).trimStart(File.separatorChar)
        if (relative.isEmpty()) return
        var current = File(canonicalBase)
        for (part in relative.split(File.separatorChar)) {
            if (part.isEmpty()) continue
            current = File(current, part)
            ensureDirectory(current)
        }
    }

    /**
     * Remove whatever occupies [path], if it is something this extractor may remove:
     * a symlink (never its target), a regular file, or an empty directory.
     */
    private fun unlinkIfPresent(path: File) {
        if (isSymlink(path)) {
            runCatching { Files.deleteIfExists(path.toPath()) }
            return
        }
        if (!path.exists()) return
        // `delete()` on a directory succeeds only when it is empty; a non-empty one is
        // left in place on purpose (see [ensureDirectory]).
        path.delete()
    }

    private fun isSymlink(path: File): Boolean =
        runCatching { Files.isSymbolicLink(path.toPath()) }.getOrDefault(false)

    /**
     * What is actually at [path], for the failure message. "Nothing is here" and "a
     * dangling symlink is here" produce the same `ENOENT`, and only one of them means
     * the archive is wrong.
     */
    private fun describeNode(path: File?): String {
        if (path == null) return "无"
        if (isSymlink(path)) {
            val link = runCatching { Files.readSymbolicLink(path.toPath()) }.getOrNull()
            return if (path.exists()) "符号链接 -> $link（目标存在）" else "符号链接 -> $link（**目标不存在**）"
        }
        return when {
            path.isDirectory -> "目录"
            path.exists() -> "普通文件"
            else -> "不存在"
        }
    }

    private fun writeFile(input: InputStream, target: File, size: Long) {
        val stream = try {
            target.outputStream()
        } catch (e: IOException) {
            // The only failure left here is one this class refused to repair (a
            // non-empty directory in the way, a read-only tree, a full disk), so name
            // the node instead of leaving a bare path next to `ENOENT` — that message
            // cannot be told apart from "the archive lacks this file", which is what
            // cost a device round trip the last time.
            throw IOException(
                "${e.message}（目标节点：${describeNode(target)}；父目录：${describeNode(target.parentFile)}）",
                e,
            )
        }
        stream.use { out ->
            val buffer = ByteArray(1 shl 16)
            var remaining = size
            while (remaining > 0) {
                val want = minOf(remaining, buffer.size.toLong()).toInt()
                val read = input.read(buffer, 0, want)
                if (read < 0) throw IllegalStateException("truncated tar entry at ${target.path}")
                out.write(buffer, 0, read)
                remaining -= read
                bytesWritten += read
            }
        }
        filesWritten++
        // Padding to the next 512-byte boundary.
        skip(input, pad512(size))
    }

    private fun readPayload(input: InputStream, size: Long): ByteArray {
        val data = ByteArray(size.toInt())
        if (!readFully(input, data, data.size)) throw IllegalStateException("truncated tar metadata")
        skip(input, pad512(size))
        return data
    }

    private fun skip(input: InputStream, count: Long) {
        var remaining = count
        val scratch = ByteArray(8192)
        while (remaining > 0) {
            val want = minOf(remaining, scratch.size.toLong()).toInt()
            val read = input.read(scratch, 0, want)
            if (read < 0) return
            remaining -= read
        }
    }

    /**
     * Reject anything that would land outside [destination].
     *
     * Both absolute paths and `..` traversal are refused. The archives are
     * downloaded from the network and unpacked with the app's own privileges, so
     * this is a boundary, not a formality.
     *
     * The destination's own canonical path is computed **once** ([canonicalBase]) and
     * the candidate's per entry. Recomputing the base was pure waste — it is a
     * constant — and it is not free: `getCanonicalFile()` resolves every component
     * with `realpath`, measured at 27 µs per call, and a rootfs payload is tens of
     * thousands of entries (20 000 entries × 2 calls = 557 ms in isolation, on top of
     * an 8–25 s extraction).
     *
     * The per-entry `canonicalFile` on the candidate stays: it is what refuses an
     * entry whose path goes *through a symlink this same extraction just created* (a
     * tar can put `bin -> /` before `bin/evil`), and a purely lexical check cannot see
     * that. Removing it would be a security regression dressed as an optimisation.
     */
    private fun resolveSafely(rawName: String): File? {
        val cleaned = rawName.removePrefix("./").trimStart('/')
        if (cleaned.isEmpty()) return destination
        val parts = cleaned.split('/')
        if (parts.any { it == ".." }) return null
        val resolved = File(destination, cleaned)
        val base = canonicalBase
        val candidate = resolved.canonicalFile
        return if (candidate.path == base || candidate.path.startsWith(base + File.separator)) {
            candidate
        } else {
            null
        }
    }

    /**
     * [destination]'s canonical path, computed once per extractor.
     *
     * `by lazy` rather than a constructor field: the caller creates the destination and
     * `extractGzip` calls `mkdirs()` on it before the first entry, so the first use is
     * always after it exists — and an extractor that never reaches an entry never pays
     * for it.
     */
    private val canonicalBase: String by lazy { destination.canonicalFile.path }

    private fun applyMode(file: File, mode: Long) {
        // Only the executable bits are meaningful for us: Android cannot carry
        // setuid/setgid across to the guest anyway.
        val owner = (mode and 0b000_000_100) != 0L // 0100
        val group = (mode and 0b000_000_010) != 0L
        val other = (mode and 0b000_000_001) != 0L
        if (owner || group || other) {
            file.setExecutable(true, false)
        }
        val readable = (mode and 0b100_000_000) != 0L
        if (readable) file.setReadable(true, false)
    }

    private fun parsePax(records: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var cursor = 0
        while (cursor < records.length) {
            val space = records.indexOf(' ', cursor)
            if (space < 0) break
            val length = records.substring(cursor, space).toIntOrNull() ?: break
            if (length <= 0 || cursor + length > records.length) break
            val record = records.substring(cursor, cursor + length)
            val eq = record.indexOf('=')
            if (eq > 0) {
                val key = record.substring(space + 1 - cursor, eq)
                val value = record.substring(eq + 1).trimEnd('\n', '\u0000')
                out[key] = value
            }
            cursor += length
        }
        return out
    }

    /**
     * Fill [buffer] with exactly [length] bytes; `false` means the stream ended short.
     *
     * The old answer was `offset != 0` on EOF, i.e. "a partial read is a success and no
     * bytes at all is the end of the data" — backwards for a tar header, and it is what
     * made a block-boundary truncation look like a clean archive. The caller now treats
     * `false` as truncation, and the reused `header` array is never read half-filled.
     */
    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Boolean {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    private fun pad512(size: Long): Long = (512 - (size % 512)) % 512

    private fun ByteArray.string(offset: Int, length: Int): String {
        var end = offset
        val limit = minOf(offset + length, size)
        while (end < limit && this[end] != 0.toByte()) end++
        return String(this, offset, end - offset, Charsets.UTF_8)
    }

    private fun ByteArray.octal(offset: Int, length: Int): Long {
        var value = 0L
        var seen = false
        val limit = minOf(offset + length, size)
        for (i in offset until limit) {
            val c = this[i].toInt()
            if (c == 0 || c == ' '.code) {
                if (seen) break else continue
            }
            if (c < '0'.code || c > '7'.code) break
            seen = true
            value = value * 8 + (c - '0'.code)
        }
        return value
    }
}
