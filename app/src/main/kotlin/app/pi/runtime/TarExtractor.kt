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
 *  - **a link that could not be created is a failure, not a bounce.** Until
 *    2026-11-03 the symlink branch swallowed every exception and tried to `Files.copy`
 *    the target in its place. For Ubuntu's merged-usr layout that copy produces
 *    `bin/` as an empty directory next to the real `usr/bin/bash`, so the tree ends up
 *    without `/bin/bash` while every caller is told the extraction succeeded — the
 *    device report that opened this work ("proot error: '/bin/bash' not found", then
 *    every later boot short-circuited by the stamp). Hard links had the same shape
 *    (source missing → skip silently). Both now throw [IOException] naming the entry,
 *    the link target and the underlying error, and the symlink is verified to *be* a
 *    link after creation. [symlink] and [copyLink] are injectable so a bare-JVM
 *    harness can prove the failure path without a filesystem that refuses links.
 */
class TarExtractor(
    private val destination: File,
    private val onEntry: ((String) -> Unit)? = null,
    /**
     * How a symlink is created. Defaults to `Files.createSymbolicLink`; the parameter
     * exists so the strict-failure behaviour is testable (`runtime-extract-real` passes
     * one that throws, which is what a device refusing `symlink(2)` looks like), and so
     * an Android caller could pass `Os.symlink` for a better errno without touching this
     * class's Android-free contract.
     */
    private val symlink: (link: Path, target: Path) -> Unit = { link, target ->
        Files.createSymbolicLink(link, target)
    },
    /** How a hard link is recreated (a copy on this platform); injectable for the same reason. */
    private val copyLink: (source: Path, target: Path) -> Unit = { source, target ->
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
    },
) {

    private var filesWritten = 0L
    private var bytesWritten = 0L
    private var entriesSeen = 0L
    private var symlinksWritten = 0L
    private var degradedLinks = 0L
    private var danglingLinks = 0L
    /**
     * 建不出来、等着铺成实物的链接：`(链接路径, 目标, tar 条目名)`。
     *
     * **必须等整份档案解完**：Ubuntu 的 tar 第一条就是 `bin -> usr/bin`，而 `usr/bin` 在
     * 那一刻还不存在（它由后面的条目创建）。当初即时回退失败，正是因为目标还没写出来。
     */
    private val pendingMaterialise = mutableListOf<PendingLink>()

    /**
     * 一条没建成的链接：目标、tar 里的目标名、条目名，以及**当初为什么没建成**。
     *
     * 最后一项是诊断用的：真机上"链接建不出来"的原因（`java.nio` 不支持？SELinux？
     * 存储不支持？）决定了该不该走复制，而以前这个异常被 `runCatching` 吞掉、失败页上只剩
     * "复制兜底也失败"——看不出是哪一层的问题。`RuntimeProvisioner` 的两层创建
     * （NIO → `Os.symlink`）会把两层的原因一起带进来。
     */
    private data class PendingLink(
        val target: File,
        val linkName: String,
        val entry: String,
        val reason: String?,
    )
    /** 源文件当时还不存在的硬链接（它可能本身就是一条待铺的链接）。 */
    private val pendingHardLinks = mutableListOf<PendingHardLink>()

    private data class PendingHardLink(
        val target: File,
        val linkName: String,
        val entry: String,
        val mode: Long,
    )

    /**
     * [links] is how many symlinks were actually created. It is the number the
     * integrity gate and the `runtime-extract-real` harness look at: zero links on an
     * Ubuntu base is a broken tree even when every byte was written, and that is the
     * exact shape a silently-skipped link left behind.
     */
    data class Result(
        val files: Long,
        val bytes: Long,
        val entries: Long,
        val links: Long = 0L,
        /**
         * Links the filesystem refused, which were **materialised instead** ([materialiseLink]).
         *
         * Non-zero means "this device cannot create symlinks, and the tree was written
         * without them": usable, but larger on disk, and worth one line on the boot screen
         * (`RuntimeProvisioner` turns it into a warning). Zero on every normal device.
         */
        val degraded: Long = 0L,
        /**
         * 链接建不出来、而它指向的东西**本来也不存在**（官方 base 里的 `etc/rmt ->
         * /usr/sbin/rmt` 就是一条：alternatives 目标没装）。这种路径按"悬空"略过——真机上
         * 一条悬空链接与一个不存在的路径对 guest 是同一个答案（ENOENT），而为了它失败会把
         * 一台本来能用的设备挡在门外。**有害**的缺失（少了 `usr/bin`）不靠这里兜，靠
         * `RuntimeTreeAudit` 的闸门。
         */
        val dangling: Long = 0L,
    )

    fun extractGzip(stream: InputStream): Result {
        destination.mkdirs()
        GZIPInputStream(BufferedInputStream(stream, 1 shl 16)).use { gz ->
            extractTar(gz)
        }
        materialisePendingLinks()
        return Result(
            filesWritten,
            bytesWritten,
            entriesSeen,
            symlinksWritten,
            degradedLinks,
            danglingLinks,
        )
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
                    // A link that could not be created must not become an empty directory.
                    // That is the 2026-11-03 first-install failure: `bin -> usr/bin` failed,
                    // the old fallback copied the *directory node*, `/bin/bash` did not
                    // exist, and the tree was stamped as provisioned anyway.
                    //
                    // Two steps, in this order:
                    //
                    //  1. create the link;
                    //  2. if the filesystem refuses it, **materialise** what it points at —
                    //     a file is copied, a directory is mirrored — so the guest still has
                    //     `/bin/bash`. This is the difference between "we report the failure"
                    //     and "it cannot fail": on a device where `symlink(2)` is denied the
                    //     runtime works, just with copies instead of links, and the boot
                    //     screen says so once (`degraded`).
                    //
                    // If even the copy cannot be written (ENOSPC, a refused chmod) this
                    // throws with the archive entry, the link target and the reason, and
                    // `RuntimeProvisioner` never records the payload as installed.
                    val linkError = runCatching { symlink(target.toPath(), Path.of(linkName)) }
                        .exceptionOrNull()
                    if (linkError == null && isSymlink(target)) {
                        symlinksWritten++
                    } else {
                        if (isSymlink(target)) runCatching { Files.deleteIfExists(target.toPath()) }
                        // Deferred on purpose: the target usually does not exist yet (the
                        // first entry of the Ubuntu base is `bin -> usr/bin`, and `usr/bin`
                        // is created by later entries). [materialisePendingLinks] runs after
                        // the whole archive.
                        pendingMaterialise += PendingLink(
                            target,
                            linkName,
                            rawName,
                            linkError?.let { it.message ?: it.javaClass.simpleName },
                        )
                    }
                    onEntry?.invoke(rawName)
                }
                '1' -> {
                    // Hard link: recreate by copying. Android's storage cannot
                    // create hardlinks across these trees, and a copy preserves
                    // behaviour for every case pi hits (the size cost is why the
                    // build must not ship a dereferenced tree in the first place).
                    //
                    // Strict for the same reason as the symlink branch: a missing source
                    // or a failed copy used to be skipped, so an archive whose hard links
                    // did not land still reported success. `usr/bin/perl5.38.2` and
                    // `usr/bin/uncompress` are the two the Ubuntu base carries, and a
                    // tool that silently does not exist is a support ticket, not a tree.
                    ensureParent(target)
                    unlinkIfPresent(target)
                    val src = resolveSafely(linkName)
                        ?: throw IOException(
                            "tar entry '$rawName' 是硬链接 -> '$linkName'，目标路径越界，拒绝复制",
                        )
                    if (!src.isFile) {
                        // Its source may itself be a link that could not be created and is
                        // waiting for [materialisePendingLinks] (`usr/bin/perl5.38.2` links
                        // to `usr/bin/perl`; a no-symlink device materialises it later).
                        pendingHardLinks += PendingHardLink(target, linkName, rawName, mode)
                        onEntry?.invoke(rawName)
                        continue
                    }
                    try {
                        copyLink(src.toPath(), target.toPath())
                    } catch (error: IOException) {
                        throw IOException(
                            "tar entry '$rawName' 的硬链接 -> '$linkName' 复制失败：" +
                                "${src.path} -> ${target.path}（${error.javaClass.simpleName}: ${error.message}）",
                            error,
                        )
                    }
                    applyMode(target, mode)
                    filesWritten++
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
     * 把这次解包中"建不出来的符号链接"铺成实物，**在整份档案解完之后**。
     *
     * 目标现在一定写出来了（`/usr/bin` 由它自己的条目创建），所以这是唯一能保证成功的时机。
     * 任何一条铺不出来都是失败：那说明复制也写不进去（ENOSPC、权限），而这棵树此后会在
     * `RuntimeTreeAudit` 的闸门上失败——失败页上宁可写"哪条链接、哪个目标、为什么"，也不要
     * 留一棵看起来解完、其实没有 `/bin/bash` 的树。
     */
    private fun materialisePendingLinks() {
        if (pendingMaterialise.isEmpty() && pendingHardLinks.isEmpty()) return

        // Two passes, and the order is the whole correctness argument:
        //
        //  1. links that point at a **file** — `usr/bin/sh -> dash`. Each is materialised at
        //     its own path, so `usr/bin/sh` becomes a real copy of `dash`;
        //  2. links that point at a **directory** — `bin -> usr/bin`, `lib -> usr/lib`. The
        //     mirror of `usr/bin` then contains `sh`, because pass 1 put it there.
        //
        // Tar order does not work, and that is not theory: the Ubuntu base lists
        // `bin -> usr/bin` **first**, so mirroring it before `usr/bin/sh` exists produced a
        // `bin/` without a shell — the exact "looks extracted, has no /bin/sh" shape this
        // change is about. `runtime-extract-real` caught it in CI.
        val (toFiles, toDirectories) = pendingMaterialise.partition { pending ->
            !linkSource(pending.target, pending.linkName).isDirectory
        }
        for (pending in toFiles + toDirectories) {
            try {
                if (materialiseLink(pending.target, pending.linkName)) {
                    degradedLinks++
                } else {
                    danglingLinks++
                }
            } catch (error: IOException) {
                throw IOException(
                    "tar entry '${pending.entry}' 是符号链接 -> '${pending.linkName}'，创建失败" +
                        (pending.reason?.let { "（$it）" } ?: "") +
                        "，复制兜底也失败：${pending.target.path}（${error.message}）",
                    error,
                )
            }
        }

        // Hard links last: their source is a real file by now (possibly created by pass 1).
        for ((target, linkName, entry, mode) in pendingHardLinks) {
            val src = resolveSafely(linkName)
            if (src == null || !src.isFile) {
                throw IOException(
                    "tar entry '$entry' 是硬链接 -> '$linkName'，但目标不存在或不是文件：" +
                        "${src?.path ?: linkName}（${describeNode(src)}）",
                )
            }
            try {
                copyLink(src.toPath(), target.toPath())
            } catch (error: IOException) {
                throw IOException(
                    "tar entry '$entry' 的硬链接 -> '$linkName' 复制失败：" +
                        "${src.path} -> ${target.path}（${error.javaClass.simpleName}: ${error.message}）",
                    error,
                )
            }
            applyMode(target, mode)
            filesWritten++
        }

        pendingMaterialise.clear()
        pendingHardLinks.clear()
    }

    /**
     * 把一条**建不出来**的符号链接铺成实物。
     *
     * 这是"不能失败"那一半：`symlink(2)` 被设备拒绝时，`bin -> usr/bin` 的正确替代不是
     * 一个空 `bin/`，而是把 `usr/bin` 的内容真的放进去（文件复制；目录逐项递归；遇到嵌套
     * 链接就复制它最终指向的东西）。代价是磁盘（`/usr/bin` 约 29 MB、`/usr/lib` 约 62 MB，
     * 只在被拒绝的设备上付），换来的是 `/bin/bash` 真的存在且可执行。
     *
     * 目标越界（`../..` 爬出 rootfs）与目标缺失都抛 [IOException]：那两种不是"设备不支持"，
     * 是载荷有问题，不能靠复制掩盖。
     */
    private fun materialiseLink(link: File, target: String): Boolean {
        val source = linkSource(link, target)
        if (!insideDestination(source)) {
            throw IOException("目标在 rootfs 之外：${source.path}")
        }
        if (!source.exists()) return false
        val failures = mirrorInto(source, link, depth = 0, seen = HashSet())
        if (failures.isNotEmpty()) {
            throw IOException(failures.take(3).joinToString("；"))
        }
        return true
    }

    /** 一条链接的目标，按 **guest 语义**落到宿主路径（`/opt/node/x` 与 `dash` 两种拼法）。 */
    private fun linkSource(link: File, target: String): File =
        if (target.startsWith("/")) {
            File(destination, target.removePrefix("/"))
        } else {
            File(link.parentFile, target)
        }

    /** 复制出来的东西必须仍在 [destination] 里；一条 `../../etc/passwd` 的链接不能被执行。 */
    private fun insideDestination(file: File): Boolean {
        val base = File(canonicalBase)
        val resolved = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return resolved.path == base.path || resolved.path.startsWith(base.path + File.separator)
    }

    /**
     * 把一个节点复制到 [to]，返回失败描述（空 = 全部成功）。
     *
     * [seen] 是目录环的护栏：Ubuntu 的 `/usr/bin/X11 -> .` 就是一条指回自己的链接，没有它
     * 这条递归不会停。深度上限 [MAX_MIRROR_DEPTH] 是第二道。
     */
    private fun mirrorInto(
        source: File,
        to: File,
        depth: Int,
        seen: MutableSet<String>,
    ): List<String> {
        if (depth > MAX_MIRROR_DEPTH) return listOf("${source.path}：嵌套太深")
        if (isSymlink(source)) {
            val target = runCatching { Files.readSymbolicLink(source.toPath()) }.getOrNull()
                ?: return listOf("${source.path}：链接读不出来")
            val resolved = linkSource(source, target.toString())
            // 官方 base 里本来就有悬空链接（`etc/rmt -> /usr/sbin/rmt`）；复制它们没有对象，
            // 而"复制不了的悬空链接"和"原本就悬空的链接"对 guest 是同一个结果。
            if (!resolved.exists()) return emptyList()
            return mirrorInto(resolved, to, depth + 1, seen)
        }
        if (source.isDirectory) {
            val key = runCatching { source.canonicalPath }.getOrDefault(source.path)
            if (!seen.add(key)) return emptyList()
            ensureDirectory(to)
            val children = source.listFiles() ?: return listOf("${source.path}：目录读不出来")
            return children.flatMap { child ->
                mirrorInto(child, File(to, child.name), depth + 1, seen)
            }
        }
        if (!source.isFile) return listOf("${source.path}：不是普通文件")
        return try {
            ensureParent(to)
            unlinkIfPresent(to)
            Files.copy(source.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            bytesWritten += source.length()
            if (source.canExecute()) to.setExecutable(true, false)
            if (source.canRead()) to.setReadable(true, false)
            filesWritten++
            emptyList()
        } catch (error: IOException) {
            listOf("${source.path} -> ${to.path}：${error.javaClass.simpleName}: ${error.message}")
        }
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
        // A metadata record is a long name or a pax attribute, so it is tens of bytes in every
        // archive this app ships: measured, the largest of the ~2 400 such records in the six
        // pinned payloads is **138 bytes**. The bound is what keeps a header a corrupt or
        // hostile archive can carry from reaching `size.toInt()` — past 2 GiB that is negative
        // and anything below it is allocated — so the failure is the named kind the rest of
        // this class reports ([IOException], which `RuntimeProvisioner` wraps with the archive
        // it came from) rather than an unrepresentable allocation.
        if (size > MAX_METADATA_RECORD_BYTES) {
            throw IOException("tar metadata record of $size bytes is out of range")
        }
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
     * ## 只 canonical 父目录，**最后一个分量保持字面**（pi-engine 升级 ELOOP 的根因）
     *
     * 旧实现是 `File(destination, cleaned).canonicalFile`，即把整条路径（含最后那个
     * 分量）都交给 `realpath`。这在**覆盖解压**时是错的：一个软链条目自己的路径上，
     * 往往正躺着上一次解包留下的同名软链 —— `canonicalFile` 会**跟着它走到目标**，
     * 于是新的软链被写在**目标的位置**，而不是条目自己的位置上。
     *
     * 真实后果（pi 1.0.1 → 1.0.3 的原地升级，设备报
     * `failed to unpack pi-engine.tgz: Too many symbolic links encountered`）：
     * 载荷里的 `./node_modules/.bin/yaml -> ../yaml/bin.mjs` 解析到
     * `node_modules/yaml/bin.mjs`，而该文件**就在包根目录**；把 `../yaml/bin.mjs`
     * 写在包根，相对路径展开回去正是它自己 —— 一条自指软链
     * （`yaml/bin.mjs -> ../yaml/bin.mjs`）。紧接着的载荷条目
     * `./node_modules/yaml/bin.mjs` 一调 `canonicalFile` 就抛 `ELOOP`。
     * 载荷里这 9 条 `.bin` 软链在 1.0.1 与 1.0.3 **逐字相同**，所以这不是新载荷
     * 引入的，而是"原地覆盖解压"必然触发的潜伏缺陷。
     *
     * 改成只 canonical 父目录之后：
     *  - 软链条目写在它自己的路径上（`.bin/yaml`），旧链由 [unlinkIfPresent] 先删掉；
     *  - 普通文件写在自己的路径上（`yaml/bin.mjs`），旧的自指链同样先被删掉 ——
     *    也就是说**已经被弄坏的树在下一次成功解包时自愈**；
     *  - 越界检查仍然成立：父目录若被解析到 [destination] 之外（载荷可以放
     *    `bin -> /` 再放 `bin/evil`），这里照样拒绝。最后那个分量不需要 canonical
     *    —— 写之前 [unlinkIfPresent] 永远先把该位置的节点（软链、文件、空目录）
     *    拿掉，从不跟着它打开。
     */
    private fun resolveSafely(rawName: String): File? {
        val cleaned = rawName.removePrefix("./").trimStart('/')
        if (cleaned.isEmpty()) return destination
        val parts = cleaned.split('/')
        if (parts.any { it == ".." }) return null
        val lexical = File(destination, cleaned)
        // A bare filename resolves its parent to the destination itself; only an empty
        // name (handled above) has no parent.
        val parent = lexical.parentFile ?: return null
        val base = canonicalBase
        val canonicalParent = parent.canonicalFile
        if (canonicalParent.path != base && !canonicalParent.path.startsWith(base + File.separator)) {
            return null
        }
        return File(canonicalParent, lexical.name)
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

    private companion object {
        /**
         * Ceiling on one metadata record (`L`/`K`/`x`/`X`/`g`), in bytes. See [readPayload]:
         * the shipped payloads' largest is 138 bytes, so this only ever fires on an archive
         * that is corrupt or not ours.
         */
        const val MAX_METADATA_RECORD_BYTES: Long = 1L shl 20

        /** 复制兜底递归的上限；超过就报失败，而不是无限展开。 */
        const val MAX_MIRROR_DEPTH: Int = 8
    }
}
