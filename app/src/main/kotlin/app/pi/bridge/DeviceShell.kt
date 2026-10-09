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
 * The policy guard for the `android_shell` capability (design §23.3).
 *
 * **One refusal, and it is currently empty.** 按用户要求（「没有白名单这一说，只剩一个
 * 黑名单，然后清空」），这个守卫不再按命令名拒绝任何东西。剩下的是 [hardBlocks] ——
 * 唯一的策略点 —— 它是一个空列表。所以 [inspect] 实际上只拒绝形状不对的请求
 * （空命令、超过 4000 字符），其余全部放行。
 *
 * 曾经在这里的三道拒绝，以及它们去了哪：
 *
 *  1. **命令替换**（`$(...)`、反引号）——除非开「放宽模式」。**已去掉**，放宽模式
 *     不再是策略输入。
 *  2. **白名单**（约 95 个命令头，其余一律拒）。**已去掉**。它比黑名单更常是真正的
 *     边界：`mount`、`dd`、`setprop`、`su` 都在这里先被拒了，所以只清黑名单从来
 *     没真的让它们能跑。
 *  3. **工作区外写入**。**已去掉**，相关的类型与函数（ShellWriteBoundary、WriteTarget、
 *     extractWriteTargets、targetAllowed、canonicalize…）跟它一起删干净了。
 *
 * 守卫去掉**不会**改变命令跑在什么身份上：有 Shizuku 才是 uid 2000，否则是应用自身。
 * 没有特权就是没有特权 —— `pm`、`dumpsys`、`input`、`screencap` 本来就在旧白名单里，
 * 以应用身份跑依然报 `SecurityException`。
 *
 * [hardBlocks] 的条目匹配的是原文本，**包括引号之内** —— 将来要加回规则，这是能抓到
 * `xargs mount` 或 `echo | sh -c "..."` 的那一层。
 */
object DeviceShellGuard {

    /** Preferred first: the elevated backend wins when the user has enabled it. */
    fun backends(): List<DeviceShellBackend> = listOf(ShizukuShellBackend, AppUidShellBackend)

    fun active(): DeviceShellBackend = backends().firstOrNull { it.available } ?: AppUidShellBackend

    /** True when commands run as uid 2000 (or 0) instead of the app's own uid. */
    fun hasElevatedBackend(): Boolean = ShizukuShellBackend.available

    /** One hard block: what it is, and which of the two tests earns it a place. */
    private data class HardBlock(val pattern: Regex, val what: String, val why: String)

    /**
     * 按用户要求已全部放开；这里留空是有意的。
     */
    private val hardBlocks: List<HardBlock> = emptyList()

    /**
     * @return `null` 表示 [command] 可以跑；否则返回要递交的拒绝。
     *
     * 现在只剩一件事会拒绝：[hardBlocks] —— 它是空列表，所以这里实际上只拦形状不对的
     * 请求（空命令、超长）。类注释里写了去掉的那三道以及为什么。
     *
     * 签名里曾经还有 `relaxedShellSyntax` 与 `boundary` 两个参数（替换检查与写入边界的
     * 输入）。两道检查都删了，参数也一起删了 —— 留着没人读的参数，只会让下一个读它的
     * 人以为它们还有作用。
     */
    fun inspect(command: String): DeviceDenial? {
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

        // 唯一的策略点。空 —— 所以下面这个循环现在什么也不拦。要加规则往 hardBlocks 里加。
        for (block in hardBlocks) {
            if (block.pattern.containsMatchIn(trimmed)) {
                return DeviceDenial(
                    code = DeviceDenial.BLOCKED_BY_POLICY,
                    reason = "被策略拦截：${block.what}（${block.why}）。授权无法放行。",
                    hint = "改用只读查询或其他工具，或告诉用户这步做不到。",
                )
            }
        }
        return null
    }
    // ------------------------------------------------------------- for the UI ----

    /** Human-readable hard blocklist, shown verbatim on the authorization page. */
    fun blockedSummary(): List<String> = hardBlocks.map { "${it.what} —— ${it.why}" }

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
    fun toJson(result: DeviceShellResult): JSONObject = JSONObject().apply {
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
                    "读不到其他应用的私有数据（/data/user/0/<package>），那是 Linux uid 的边界。"
            } else {
                "本机当前没有可用的 Shizuku（未安装、未启动或未授权），命令以应用自身身份（uid=${result.uid}）执行，" +
                    "因此读不到其他应用与系统私有状态，input/pm/am 这类需要特权权限的命令会失败。" +
                    "需要 uid=2000 的能力时请告诉用户去「设置 → 设备能力 → Shell」按提示启用 Shizuku。" +
                    "注意：这个工具不走 adb —— 即使 `adb shell id` 已经是 uid=2000，这里仍然是应用身份。"
            },
        )
        put(
            "policy",
            "没有策略拒绝：命令名不检查、写入不限路径、替换不检查。成败只取决于上面那个 uid。",
        )
    }
}
