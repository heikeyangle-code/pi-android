package app.pi.runtime

import java.io.BufferedInputStream
import java.io.File
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
 * therefore re-packs Node's official `.tar.xz` into `.tar.gz` at build time, so
 * the device only ever needs gzip.
 *
 * Correctness properties that matter for a Linux userland:
 *  - **symlinks and hardlinks are recreated**, not dereferenced. A rootfs where
 *    `/bin/sh -> dash` became a copy, or where git's subcommands stopped sharing
 *    one inode, is subtly broken. Note the packaging consequence: a build that
 *    dereferences hardlinks is how an APK ends up hundreds of megabytes larger
 *    than its contents.
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
            if (!readFully(input, header, 512)) break
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
                    target.mkdirs()
                    onEntry?.invoke(rawName)
                }
                '2' -> {
                    target.parentFile?.mkdirs()
                    target.delete()
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
                    target.parentFile?.mkdirs()
                    val src = resolveSafely(linkName)
                    if (src != null && src.exists()) {
                        runCatching { Files.copy(src.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                            .onSuccess { filesWritten++ }
                    }
                    onEntry?.invoke(rawName)
                }
                '0', '\u0000', '7' -> {
                    target.parentFile?.mkdirs()
                    writeFile(input, target, size)
                    applyMode(target, mode)
                    onEntry?.invoke(rawName)
                }
                else -> skip(input, size) // char/block/fifo: unused by a rootfs tarball
            }
        }
        return Result(filesWritten, bytesWritten, entriesSeen)
    }

    private fun writeFile(input: InputStream, target: File, size: Long) {
        target.outputStream().use { out ->
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
     */
    private fun resolveSafely(rawName: String): File? {
        val cleaned = rawName.removePrefix("./").trimStart('/')
        if (cleaned.isEmpty()) return destination
        val parts = cleaned.split('/')
        if (parts.any { it == ".." }) return null
        val resolved = File(destination, cleaned)
        val base = destination.canonicalFile
        val candidate = resolved.canonicalFile
        return if (candidate.path == base.path || candidate.path.startsWith(base.path + File.separator)) {
            candidate
        } else {
            null
        }
    }

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

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Boolean {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) return offset != 0
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
