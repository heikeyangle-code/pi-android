package app.pi.bridge

import android.os.Process
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Result of one guarded shell invocation, with the backend that produced it.
 *
 * [backend] and [uid] are not decoration: without them a model cannot tell why
 * `pm list packages` worked and `dumpsys battery` did not, and would report a
 * capability the app does not really have.
 */
data class DeviceShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val backend: String,
    val uid: Int,
    val timedOut: Boolean,
) {

    /**
     * True when `stdout` is a **prefix** — it stopped at the shared cap
     * ([AppUidShellBackend.MAX_OUTPUT_BYTES]) while the command kept writing.
     *
     * Derived from the string rather than passed in, and that is the point: this used
     * to be one constructor flag named `truncated` that each backend computed for
     * itself, so the two backends disagreed (the app-uid backend looked only at stdout
     * while both streams are capped) and no caller could tell *which* stream had been
     * cut — a model that saw a complete-looking `stdout` next to `truncated: true` had
     * no way to know it was the stderr that was clipped.
     */
    val stdoutTruncated: Boolean get() = stdout.length >= AppUidShellBackend.MAX_OUTPUT_BYTES

    /** True when `stderr` is a prefix, by the same rule as [stdoutTruncated]. */
    val stderrTruncated: Boolean get() = stderr.length >= AppUidShellBackend.MAX_OUTPUT_BYTES

    /**
     * Either stream was cut. Kept because it is the field existing readers use
     * (`DeviceShell.toJson`, and the extension's `ShellData`), and it is now a
     * definition rather than two backends' separate opinions.
     */
    val truncated: Boolean get() = stdoutTruncated || stderrTruncated
}

/**
 * A shell execution backend. Two ship today:
 *
 *  - [ShizukuShellBackend] — uid 2000 (or 0), the ADB identity, when the user has
 *    Shizuku running and has granted this app permission;
 *  - [AppUidShellBackend] — the app's own uid, always available, much weaker.
 */
interface DeviceShellBackend {
    val id: String
    val label: String
    val available: Boolean
    fun run(command: String, timeoutMs: Int): DeviceShellResult
}

/**
 * `/system/bin/sh` as the app's own uid (`u0_aXXX`), *not* uid 2000.
 *
 * This is the fallback, and it is an honest one: it can read what the app can
 * read and write into the app's own storage plus the public Download collection;
 * it cannot inspect other apps or system state that the app sandbox hides. The
 * response says so on every call.
 */
object AppUidShellBackend : DeviceShellBackend {
    override val id: String = "app-uid"
    override val label: String = "应用自身身份（uid=${Process.myUid()}）"
    override val available: Boolean = true

    /**
     * The output cap, and the drain that enforces it — **shared with the Shizuku
     * backend** (`ShizukuShellBackend.exec`), which used to carry its own copy.
     *
     * That copy is exactly how the U+FFFD bug outlived its fix: the decoder here was
     * corrected to carry a partial multi-byte sequence across reads
     * (`app.pi.rpc.Utf8StreamDecoder`), and the identical-looking reader in
     * `DeviceShizuku.kt` kept decoding each 8 KiB buffer on its own — so the *elevated*
     * backend (the one a user enables precisely because they want the better path)
     * still returned 乱码 for Chinese output, and its `truncated` flag still ignored
     * stderr. One definition is the only version of this that cannot drift again.
     */
    internal const val MAX_OUTPUT_BYTES = 50 * 1024

    override fun run(command: String, timeoutMs: Int): DeviceShellResult {
        val builder = ProcessBuilder("/system/bin/sh", "-c", command)
        builder.directory(File("/"))
        // Do not inherit the app's environment: PATH and TMPDIR are all a shell
        // needs, and passing the rest through would leak app state into stdout.
        val environment = builder.environment()
        environment.clear()
        environment["PATH"] = "/system/bin:/system/xbin:/product/bin"
        environment["TMPDIR"] = System.getProperty("java.io.tmpdir") ?: "/data/local/tmp"
        environment["LANG"] = "C.UTF-8"

        val process = try {
            builder.start()
        } catch (error: Exception) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.ERROR,
                    reason = "无法启动 shell：${error.message}",
                ),
            )
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outReader = Thread { readCapped(process.inputStream, stdout) }
        val errReader = Thread { readCapped(process.errorStream, stderr) }
        outReader.isDaemon = true
        errReader.isDaemon = true
        outReader.start()
        errReader.start()

        val timeout = timeoutMs.coerceIn(500, 60_000).toLong()
        val finished = process.waitFor(timeout, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        outReader.join(500)
        errReader.join(500)

        return DeviceShellResult(
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            exitCode = if (finished) process.exitValue() else -1,
            backend = id,
            uid = Process.myUid(),
            // No `truncated = …` argument: both per-stream flags are derived on
            // `DeviceShellResult` from the strings and the shared cap, so this backend
            // and the Shizuku one cannot state it differently again.
            timedOut = !finished,
        )
    }

    /** See [MAX_OUTPUT_BYTES]: shared with the Shizuku backend, one definition. */
    internal fun readCapped(stream: java.io.InputStream, into: StringBuilder) {
        // **One decoder for the whole stream, not one per read.** Decoding each 8 KiB
        // buffer on its own splits any multi-byte character that straddles a read
        // boundary into two malformed halves, and a `REPLACE` decoder turns each half
        // into U+FFFD — so Chinese output (three bytes per character, and this bridge's
        // usual input on a Chinese device) came back with replacement characters at
        // 8192-byte intervals. `Utf8StreamDecoder` carries the partial sequence into the
        // next read, and `flush()` closes the last one; it is the class the engine's own
        // stdout pump uses for exactly this reason (`PiEngineSession.kt:448`).
        val decoder = app.pi.rpc.Utf8StreamDecoder()
        stream.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = try {
                    input.read(buffer)
                } catch (closed: Exception) {
                    -1
                }
                if (read <= 0) break
                if (into.length < MAX_OUTPUT_BYTES) {
                    into.append(decoder.decode(buffer, read))
                }
            }
        }
        if (into.length < MAX_OUTPUT_BYTES) into.append(decoder.flush())
    }
}

/**
 * The device shell's write boundary, expressed as one question: is this path part
 * of the directory the user handed to pi?
 *
 * ### Why "the workspace is the authorization boundary"
 *
 * The user's rule, made explicit: **whatever directory they picked as the
 * workspace is what they gave the agent.** That choice *is* the authorization, so
 * nothing inside the workspace is the gate's business — not `rm -rf`, not a
 * `chmod 777`, not overwriting a file. A hardcoded list of "sensitive" paths
 * (`DCIM`, `Pictures`, `Android/data`, …) is the wrong shape twice over: it blocks
 * things the user meant to hand over when the workspace happens to *be* that
 * directory, and it says nothing about everything else that happens to be outside
 * the workspace. So the rule is relative, not a list: **inside the workspace the
 * gate does not exist; outside it, the shell does not write.**
 *
 * This is only about *writes*: reading device state (`dumpsys`, `getprop`,
 * `ls /sdcard`) is what the 「Shell」 opt-in authorizes, and stays allowed wherever
 * it points. And it is only about the *shell*: `android_download` / `android_files`
 * are separate, explicitly-confirmed endpoints whose whole purpose is to touch
 * files the user picks elsewhere.
 *
 * The implementation is deliberately not a `File.canonicalPath` call: the command
 * is a string that has not run yet, so the boundary is decided by lexically
 * normalising the path tokens the command *names*.
 */
interface ShellWriteBoundary {
    /** True when [path] is the workspace or inside it. */
    fun contains(path: String): Boolean

    /** The workspace as the device shell has to spell it. */
    fun shellPath(): String?

    /** True when we actually know the workspace; otherwise the rule is skipped. */
    fun isKnown(): Boolean
}

/**
 * The policy guard for the `android_shell` capability (design §23.3).
 *
 * Four refusals, and that is the whole list:
 *
 *  1. **Command substitution** (`$(...)`, backticks) unless 放宽模式 is on. It
 *     smuggles a command past the per-segment validation below.
 *  2. **The hard blocklist** — ten things, matched anywhere in the command text,
 *     each earning its place by "needs root we do not have, so it can only fail"
 *     or "irreversible device damage if it ever ran". See [hardBlocks].
 *  3. **Unknown commands.** An unrecognised head is refused rather than attempted,
 *     because a blocklist over a device with thousands of binaries is unbounded.
 *     The set is the everyday read *and write* vocabulary of a coding agent — the
 *     user's complaint was that it had grown read-mostly by caution.
 *  4. **Writes outside the workspace.** See [ShellWriteBoundary] for why the
 *     workspace is the boundary and why this replaced the old hardcoded path list.
 *
 * The hard blocks are matched against the raw text *including inside quotes*.
 * That is intentional: `xargs mount`, `env dd of=/dev/block/...` and
 * `echo | sh -c "settings put ..."` reach a blocked command through an allowed
 * head, and with a widened whitelist only a text-level scan closes that. The cost
 * is that a command which merely mentions one of the tokens (for example
 * `grep mount /proc/mounts`) is refused too; the denial names the rule, so the
 * model can rephrase rather than guess.
 */
object DeviceShellGuard {

    /** Preferred first: the elevated backend wins when the user has enabled it. */
    fun backends(): List<DeviceShellBackend> = listOf(ShizukuShellBackend, AppUidShellBackend)

    fun active(): DeviceShellBackend = backends().firstOrNull { it.available } ?: AppUidShellBackend

    /** True when commands run as uid 2000 (or 0) instead of the app's own uid. */
    fun hasElevatedBackend(): Boolean = ShizukuShellBackend.available

    /**
     * Heads a read **or write** command may start with, ordered by what it is for
     * so the authorization page can show it as a readable list.
     *
     * What is deliberately *not* here: `sh`/`bash`/`eval`/`source` (they would make
     * the whitelist meaningless — only relaxed mode admits them), `kill`/`killall`
     * (ending device processes is what the separately-confirmed android_stop_app is
     * for, and the app's own engine is one of its own processes), `chown` (needs
     * root, so it could only ever fail), and interpreters a ROM might or might not
     * ship (`python3`, `perl`, `node`).
     */
    val allowedCommands: List<String> = listOf(
        // shell builtins that matter here
        "cd",
        // 设备与系统查询
        "getprop", "dumpsys", "logcat", "pm", "am", "cmd", "settings", "wm",
        "screencap", "input", "getevent",
        // 文件与目录（读写）
        "ls", "cat", "head", "tail", "wc", "stat", "file", "readlink", "realpath", "find",
        "mkdir", "rmdir", "rm", "cp", "mv", "touch", "ln", "chmod", "install",
        "mktemp", "tee", "truncate", "df", "du", "sync",
        // 文本与二进制处理
        "grep", "sort", "uniq", "cut", "tr", "sed", "awk", "xargs", "diff", "cmp", "patch",
        "basename", "dirname", "seq", "expr", "strings", "base64", "xxd", "od", "hexdump",
        "md5sum", "sha1sum", "sha256sum", "cksum",
        // 归档
        "tar", "gzip", "gunzip", "zip", "unzip",
        // 进程与系统信息
        "ps", "top", "free", "uptime", "date", "uname", "id", "whoami", "pwd",
        "env", "printenv", "which", "type", "nproc", "getconf",
        // `echo`/`printf` are how a shell writes a file at all; dropping them (as this
        // list did after the first rewrite) makes `echo x > file` fail the whitelist.
        "echo", "printf",
        "sleep", "true", "false", "test", "[",
        // 网络
        "ip", "ifconfig", "netstat", "ping", "curl", "wget",
        // 数据库文件
        "sqlite3",
    )

    private val allowedHeads: Set<String> = allowedCommands.toSet()

    /**
     * Heads only 放宽模式 admits. They nest execution, so with them the whitelist
     * constrains the *outer* command only — including, deliberately and visibly, the
     * write boundary. That is the cost the UI states before the switch is flipped.
     */
    private val relaxedOnlyHeads: Set<String> =
        setOf("sh", "bash", "dash", "ash", "busybox", "eval", "exec", "source", ".")

    private const val RELAXED_COST =
        "放宽模式允许 \$(…)、反引号与 sh/bash/eval/source 这类嵌套执行：命令白名单与写入边界从此只约束最外层命令，" +
            "嵌套进去的命令不再逐条检查（包括它是否写在工作区内），等于把设备 Shell 的边界交给 Agent 自己把持。"

    /** One hard block: what it is, and which of the two tests earns it a place. */
    private data class HardBlock(val pattern: Regex, val what: String, val why: String)

    /**
     * The irreducible set. Twelve patterns covering the ten things user consent must
     * never reach (mount/umount and pm/cmd-package are each split into their own
     * pattern so the denial can name the exact command).
     *
     * Test A = "needs privilege the app does not have, so it can only fail".
     * Test B = "irreversible device damage if it ever ran".
     *
     * These are the one class the workspace carve-out does **not** cover: the test
     * is the command itself, not where it runs. `rm -rf /workspace/build` is the
     * user's own directory and none of our business; `dd` is not.
     *
     * Honest note for a Shizuku-enabled device: with a uid=2000 backend `setprop`
     * and `settings put` (and possibly `mount` on a userdebug build) would
     * *succeed*, so for those the block is a policy decision rather than test A.
     * The user asked for exactly this list; the wording below says "we forbid it",
     * not "it cannot work".
     *
     * **Case, and the one place this list and its TypeScript mirror differ.** These
     * `Regex`s are case-sensitive (Kotlin's default). The mirror in
     * `assets/pi-extensions/pi-android-bridge/danger.ts` matches eleven of the twelve
     * with the `i` flag, so the gate refuses `MOUNT` / `DD` style spellings that this
     * guard would let through. Left as-is on purpose rather than "aligned" in either
     * direction: this guard is the enforcement, the TS copy is only a "do not ask the
     * user about something that can never be allowed" pre-filter, and a capitalised
     * spelling is not a bypass on a case-sensitive filesystem (`MOUNT` is not a
     * binary) — so adding `IGNORE_CASE` here would buy nothing and refuse prose that
     * merely mentions the token.
     */
    private val hardBlocks: List<HardBlock> = listOf(
        // --- Test A: needs root (or ADB-level privilege) this app does not have ---
        HardBlock(
            Regex("(^|[\\s;&|()])mount(\\s|$)"),
            "mount",
            "挂载/卸载文件系统需要 root（缺少 CAP_SYS_ADMIN），应用身份下只会失败",
        ),
        HardBlock(
            Regex("(^|[\\s;&|()])umount(\\s|$)"),
            "umount",
            "挂载/卸载文件系统需要 root，应用身份下只会失败",
        ),
        HardBlock(Regex("\\bsetenforce\\b"), "setenforce", "修改 SELinux 需要 root"),
        HardBlock(Regex("\\bsetprop\\b"), "setprop", "修改系统属性需要 root 或特权 SELinux 域"),
        HardBlock(
            Regex("\\bsettings\\s+(put|delete|reset)\\b"),
            "settings put/delete/reset",
            "写系统设置需要 WRITE_SECURE_SETTINGS（签名权限）；这一条同时是刻意的策略：授权 Shell 不等于授权改设备设置",
        ),
        HardBlock(Regex("\\bmknod\\b"), "mknod", "创建设备节点需要 CAP_MKNOD"),
        // --- Test B: irreversible damage ---
        HardBlock(Regex("\\bdd\\b"), "dd", "裸写入可以覆盖分区或整盘数据，无法撤销"),
        HardBlock(Regex("\\bmkfs(\\.[a-z0-9]+)?(\\s|$)"), "mkfs", "格式化会销毁文件系统，无法撤销"),
        HardBlock(
            Regex("\\bpm\\s+(clear|uninstall)\\b"),
            "pm clear/uninstall",
            "清除应用数据或卸载应用会丢失用户数据，无法撤销",
        ),
        HardBlock(
            Regex("\\bcmd\\s+package\\s+(clear|uninstall)\\b"),
            "cmd package clear/uninstall",
            "清除应用数据或卸载应用会丢失用户数据，无法撤销",
        ),
        HardBlock(
            Regex("(^|[\\s;&|()])(su|sudo|magisk)(\\s|$)"),
            "su/sudo/magisk",
            "提权：拿到 root 意味着上面每一条都能执行",
        ),
        // No leading \b: there is no word boundary between a space and a slash, so
        // Regex("\\b/dev/block\\b") could never match. The TS mirror of this policy
        // had the same bug; the runtime harness caught it.
        HardBlock(Regex("/dev/block"), "/dev/block", "块设备：写入等于直接改分区，无法撤销"),
    )

    /**
     * @param relaxedShellSyntax the opt-in 放宽模式. Persisted by
     *   [DeviceCapabilityStore] and reported on `/app/health`, so the Kotlin guard
     *   and the TypeScript gate read one switch, not two.
     * @param boundary the workspace. `null` (or an unknown workspace) means the
     *   write rule cannot be applied and is skipped — the class-2 hard blocks are
     *   path-independent and still apply.
     * @return `null` when [command] may run, otherwise the denial to hand back.
     */
    fun inspect(
        command: String,
        relaxedShellSyntax: Boolean = false,
        boundary: ShellWriteBoundary? = null,
    ): DeviceDenial? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return DeviceDenial(DeviceDenial.BAD_REQUEST, "命令为空。")
        }
        if (trimmed.length > 4000) {
            return DeviceDenial(
                code = DeviceDenial.BAD_REQUEST,
                reason = "命令过长（${trimmed.length} 字符，上限 4000）。",
            )
        }
        if (!relaxedShellSyntax) {
            if (trimmed.contains('`')) {
                return substitutionDenial("反引号")
            }
            if (trimmed.contains("\$(")) {
                return substitutionDenial("\$(...)")
            }
        }

        for (block in hardBlocks) {
            if (block.pattern.containsMatchIn(trimmed)) {
                return DeviceDenial(
                    code = DeviceDenial.BLOCKED_BY_POLICY,
                    reason = "被策略拦截：${block.what}（${block.why}）。授权无法放行。",
                    hint = "改用只读查询或其他工具，或告诉用户这步做不到。",
                )
            }
        }

        // Split on shell separators and validate every segment's head token, so
        // `getprop x; rm -rf x` cannot ride on an allowed first head.
        val segments = splitSegments(trimmed)
        if (segments.isEmpty()) {
            return DeviceDenial(DeviceDenial.BAD_REQUEST, "命令为空。")
        }
        val heads = if (relaxedShellSyntax) allowedHeads + relaxedOnlyHeads else allowedHeads
        for (segment in segments) {
            val head = segment.split(Regex("\\s+")).firstOrNull()?.trim().orEmpty()
            val normalized = normalizeHead(head)
            if (normalized.isEmpty() || normalized !in heads) {
                return DeviceDenial(
                    code = DeviceDenial.BLOCKED_BY_POLICY,
                    reason = "命令「$head」不在白名单内，未知命令默认拦截。",
                    hint = "设备 Shell 只跑白名单；git/npm/构建用工作区内置 bash。",
                )
            }
        }

        if (boundary != null && boundary.isKnown()) {
            writeBoundaryDenial(trimmed, boundary)?.let { return it }
        }
        return null
    }

    private fun substitutionDenial(what: String): DeviceDenial = DeviceDenial(
        code = DeviceDenial.BLOCKED_BY_POLICY,
        reason = "命令包含$what 替换，已拦截。",
        hint = "拆成独立命令；嵌套需让用户开「放宽模式」（设置 → 设备能力 → Shell）。",
    )

    // ------------------------------------------------------ write boundary ----

    private data class WriteTarget(val raw: String, val cwd: String, val kind: String)

    private val redirection = Regex("(?:^|[^0-9<>])>>?\\s*([^\\s;&|<>()]+)")

    private fun writeBoundaryDenial(command: String, boundary: ShellWriteBoundary): DeviceDenial? {
        for (target in extractWriteTargets(command)) {
            if (targetAllowed(target, boundary)) continue
            val where = boundary.shellPath() ?: "工作区"
            val platform = if (isAndroidDataPath(target.raw)) {
                "Android 11（API 30）起的分区存储在平台层面也禁止应用写别的应用的 Android/data、Android/obb，" +
                    "写进去只会以 EACCES 失败。"
            } else {
                ""
            }
            return DeviceDenial(
                code = DeviceDenial.BLOCKED_BY_POLICY,
                reason = "要写工作区外：${target.raw}（${target.kind}）。工作区内可写。$platform",
                hint = "在工作区内操作（$where）；或用 android_files（op=\"write\"）。",
            )
        }
        return null
    }

    private fun isAndroidDataPath(raw: String): Boolean =
        raw.contains("/Android/data") || raw.contains("/Android/obb")

    /**
     * Which argument of a write command is the destination. Relative names resolve
     * against the tracked `cd`, so `cd $workspace && rm -rf build` is fine while
     * `cd / && rm -rf sdcard` is not.
     */
    private fun extractWriteTargets(command: String): List<WriteTarget> {
        val out = ArrayList<WriteTarget>()
        var cwd = "/"
        for (segment in splitSegments(command)) {
            for (match in redirection.findAll(segment)) {
                val raw = match.groupValues[1].trim()
                if (raw.isNotEmpty() && raw != "-") out.add(WriteTarget(raw, cwd, "重定向"))
            }
            val tokens = segment.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            val head = normalizeHead(tokens[0])
            val args = tokens.drop(1)
            if (head == "cd") {
                cwd = args.firstOrNull()?.let { canonicalize(resolveAgainst(it, cwd)) } ?: "/"
                continue
            }
            for (raw in destinationsFor(head, args)) out.add(WriteTarget(raw, cwd, head))
        }
        return out
    }

    private fun destinationsFor(head: String, args: List<String>): List<String> {
        val values = { names: List<String> ->
            args.filterIndexed { index, token -> index > 0 && args[index - 1] in names }
        }
        val nonFlags = args.filter { it.isNotEmpty() && !it.startsWith("-") }
        return when (head) {
            // Every file argument is written in place.
            "rm", "rmdir", "mkdir", "truncate", "patch", "tee",
            "mktemp", "gzip", "gunzip",
            -> nonFlags

            // `chmod -R 777 path`: the mode is not a path. It is octal (755) or
            // symbolic (u+x, a=rwx); without dropping it the mode resolves to a path
            // like `/777` and a legitimate chmod inside the workspace is refused.
            // (The guard harness caught exactly that.)
            "chmod" -> nonFlags.filterNot { isModeArgument(it) }

            // `touch -t 202401011200 file`: the timestamp is an option *value*.
            "touch" -> withoutOptionValues(args, setOf("-t", "-d", "-r"))

            // Only the *destination* is written; the sources are reads, and blocking a
            // read outside the workspace would be the gate reaching where it must not:
            // `cp /etc/hosts <workspace>/h` is legitimate.
            "cp", "mv", "install", "ln" -> nonFlags.takeLast(1)
            "dd" -> args.filter { it.contains("of=") }.map { it.substringAfter("of=") }
            // `sed -i s/a/b/ file` — the script is the first non-flag argument and
            // is not a path; without -i, sed does not write at all.
            "sed" -> if (args.any { it == "-i" || it.startsWith("-i") }) nonFlags.drop(1) else emptyList()
            "curl" -> values(listOf("-o", "--output"))
            "wget" -> values(listOf("-O", "--output-document"))
            // `tar -xzf src.tgz -C dest`: the extraction directory is the write. When
            // creating an archive (the `-c` bundle), the archive is one as well.
            "tar" -> values(listOf("-C", "--directory")) +
                (if (args.any { it.startsWith("-") && !it.startsWith("--") && it.contains('c') }) {
                    values(listOf("-f", "--file"))
                } else {
                    emptyList()
                })

            "unzip" -> values(listOf("-d"))
            "zip" -> nonFlags.take(1)
            // find only writes when it is told to. Its *paths* are the leading
            // arguments; from the first flag on it is an expression, and `-name 'x'`
            // is a pattern, not a path (the harness caught that).
            "find" -> if (args.any { it == "-delete" || it == "-exec" || it == "-execdir" }) {
                args.takeWhile { !it.startsWith("-") }.filter { it.isNotEmpty() }
            } else {
                emptyList()
            }

            "sqlite3" -> nonFlags.take(1)
            else -> emptyList()
        }
    }

    private val octalMode = Regex("^[0-7]{3,4}$")
    private val symbolicMode = Regex("^[ugoa]*[+-=][rwxXst]*$")

    private fun isModeArgument(token: String): Boolean =
        octalMode.matches(token) || symbolicMode.matches(token)

    /** Non-flag arguments, skipping the value that follows any of [valueFlags]. */
    private fun withoutOptionValues(args: List<String>, valueFlags: Set<String>): List<String> {
        val out = ArrayList<String>()
        var index = 0
        while (index < args.size) {
            val token = args[index]
            if (token in valueFlags) {
                index += 2
                continue
            }
            if (token.isNotEmpty() && !token.startsWith("-")) out.add(token)
            index += 1
        }
        return out
    }

    private fun targetAllowed(target: WriteTarget, boundary: ShellWriteBoundary): Boolean {
        val raw = target.raw.trim().trim('"', '\'')
        if (raw.isEmpty() || raw == "-" || raw.startsWith("&")) return true
        // A URL is not a filesystem path (`curl -o` aside, which is handled above).
        if (raw.contains("://")) return true
        val combined = resolveAgainst(raw, target.cwd)
        val pinned = staticPrefix(combined)
        if (pinned.isEmpty() && (raw.contains('$') || raw.contains('`'))) {
            // Unresolvable absolute-ish target: fail closed, the message says why.
            return false
        }
        val canonical = canonicalize(if (pinned.isEmpty()) target.cwd else pinned)
        if (canonical.isEmpty()) return true
        return boundary.contains(canonical)
    }

    /** Relative tokens resolve against the shell's tracked `cd`. */
    private fun resolveAgainst(raw: String, cwd: String): String =
        if (raw.startsWith("/")) raw else "$cwd/$raw"

    /**
     * Everything before the first character that could expand to something else: a
     * trailing glob or a variable does not change *where* the write lands, so the
     * static prefix is what gets checked against the workspace. A glob inside the
     * workspace is therefore allowed while a glob on a system directory is not.
     *
     * (The wording above avoids writing a slash-star pair inside this comment on
     * purpose: Kotlin block comments nest, so one would swallow this KDoc's closing
     * marker and report the error at the end of the file. It has bitten this repo
     * more than once, including this very line.)
     */
    private fun staticPrefix(path: String): String {
        val cut = path.indexOfFirst { it == '*' || it == '?' || it == '$' || it == '{' || it == '~' }
        return if (cut >= 0) path.substring(0, cut) else path
    }

    /** Lexical normalisation: the command has not run, so nothing can be stat'd. */
    private fun canonicalize(path: String): String {
        val clean = path.trim().trim('"', '\'')
        if (clean.isEmpty()) return ""
        val parts = ArrayList<String>()
        for (segment in clean.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(segment)
            }
        }
        return "/" + parts.joinToString("/")
    }

    // ------------------------------------------------------------- for the UI ----

    /** Human-readable hard blocklist, shown verbatim on the authorization page. */
    fun blockedSummary(): List<String> = hardBlocks.map { "${it.what} —— ${it.why}" }

    /** The whitelist, rendered as one line so the page can show what *is* allowed. */
    fun allowedSummary(): String = allowedCommands.joinToString("、")

    /** What the write rule now is, and what it deliberately stopped being. */
    fun writeBoundarySummary(): List<String> = listOf(
        "写入边界 = 用户选定的工作区：工作区之内（含它本身就是 DCIM、Pictures、Download、Android/data 之类目录时）一律不拦。",
        "工作区之外的写入会被拒。原先那张写死的 DCIM / Pictures / Android-data 黑名单已经删掉 —— 它既挡了用户本来就交出去的东西，也没说明工作区之外还有什么。",
        "读取不受此限：dumpsys、getprop、ls /sdcard 这类查询指向哪里都可以。",
        "android_download 与 android_files（SAF）是独立端点，各自的确认与授权覆盖它们，不受这条写入边界约束。",
    )

    /** Where the syntax policy stands right now. */
    fun syntaxSummary(relaxedShellSyntax: Boolean): List<String> =
        if (relaxedShellSyntax) {
            listOf("放宽模式：已开启。$RELAXED_COST")
        } else {
            listOf(
                "放宽模式：已关闭（默认）。\$(...) 与反引号会被拒绝；sh/bash/eval/source 也不在白名单里。",
            )
        }

    /** The sentence the UI shows next to the relaxed-mode switch. */
    fun relaxedCost(): String = RELAXED_COST

    private fun splitSegments(command: String): List<String> = command
        .replace("&&", ";")
        .replace("||", ";")
        .replace("|", ";")
        .replace("\n", ";")
        .split(';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    private fun normalizeHead(head: String): String =
        head.substringAfterLast('/').trim('"', '\'')

    // --------------------------------------------------------------- result ----

    /** The backend label for a result, including the privilege it really ran with. */
    private fun labelFor(result: DeviceShellResult): String = when (result.backend) {
        ShizukuShellBackend.id -> if (result.uid == 0) {
            "Shizuku（root，uid=0）"
        } else {
            "Shizuku（ADB 身份，uid=${result.uid}）"
        }

        else -> AppUidShellBackend.label
    }

    /** Renders a result for the model, keeping the backend's real privilege visible. */
    fun toJson(
        result: DeviceShellResult,
        relaxedShellSyntax: Boolean = false,
        boundaryLabel: String? = null,
    ): JSONObject = JSONObject().apply {
        put("stdout", result.stdout)
        put("stderr", result.stderr)
        put("exitCode", result.exitCode)
        put("timedOut", result.timedOut)
        // Three fields, on purpose: `truncated` is the compatibility spelling (either
        // stream, and what existing readers check), and the two per-stream flags are
        // what make the answer *actionable* — "my grep printed nothing" is a different
        // conclusion depending on whether it was stdout that was clipped or stderr.
        put("truncated", result.truncated)
        put("stdoutTruncated", result.stdoutTruncated)
        put("stderrTruncated", result.stderrTruncated)
        put("backend", result.backend)
        put("uid", result.uid)
        put("backendLabel", labelFor(result))
        put(
            "note",
            if (result.backend == ShizukuShellBackend.id) {
                "命令以 ${labelFor(result)} 执行（uid=${result.uid}），这是 Shizuku 提供的 ADB 级身份：" +
                    "可以跑 input / pm / am / settings get / dumpsys，也能读到 shell 能看到的系统状态。" +
                    "读不到其他应用的私有数据（/data/user/0/<package>），那是 Linux uid 的边界。" +
                    "命令白名单、硬性禁用清单与工作区写入边界仍然生效。"
            } else {
                "本机当前没有可用的 Shizuku（未安装、未启动或未授权），命令以应用自身身份（uid=${result.uid}）执行，" +
                    "因此读不到其他应用与系统私有状态，input/pm/am 这类需要特权权限的命令会失败。" +
                    "需要 uid=2000 的能力时请告诉用户去「设置 → 设备能力 → Shell」按提示启用 Shizuku。"
            },
        )
        put(
            "policy",
            "命令白名单与硬性禁用清单仍然生效；放宽模式" + (if (relaxedShellSyntax) "已开启" else "已关闭") +
                (if (boundaryLabel != null) "；写入边界 = $boundaryLabel" else ""),
        )
    }
}
