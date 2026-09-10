package app.pi.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Proves, on the actual device, that the runtime can execute guest binaries.
 *
 * This exists because the whole architecture rests on one platform behaviour
 * that cannot be tested anywhere except a phone: Android 10+ refuses `execve()`
 * on files in an app's own data directory, and the pi runtime lives precisely
 * there. The two ways around it are:
 *
 *   - `targetSdk 28` — the pre-W^X sandbox Termux pins. Known good.
 *   - `targetSdk >= 29` — proot's loader is exec'd from `nativeLibraryDir` and
 *     maps the guest binaries itself. Reported to work, not proven here.
 *
 * If the second path silently fails, the symptom is a mysterious `ENOENT` or a
 * hung turn somewhere in the middle of a tool call. So instead of waiting for
 * that, the app runs this check at startup and says what actually happened.
 *
 * The check is deliberately end-to-end: it does not inspect policies or read
 * `/proc`, it simply asks proot to run something in the guest and returns the
 * result.
 */
class RuntimeSelfCheck(private val paths: PiPaths) {

    enum class Status {
        /** proot ran a guest binary and we read its output. The runtime works. */
        Ok,

        /** The runtime has not been unpacked yet. */
        NotProvisioned,

        /**
         * proot itself could not start. Usually SELinux denying `ptrace` (hardened
         * ROMs), a missing loader, or the native library not extracted.
         */
        ProotFailed,

        /**
         * proot started but the guest binary could not be executed — this is the
         * W^X case, and it means this targetSdk cannot host the runtime.
         */
        GuestExecDenied,
    }

    data class Outcome(
        val status: Status,
        val detail: String,
        val exitCode: Int? = null,
        val stdout: String = "",
        val stderr: String = "",
    ) {
        val ok: Boolean get() = status == Status.Ok
    }

    suspend fun run(storage: File? = null): Outcome = withContext(Dispatchers.IO) {
        if (!paths.rootfs.isDirectory || !paths.prootBinary().exists()) {
            return@withContext Outcome(
                Status.NotProvisioned,
                "运行时尚未安装：缺少 ${paths.rootfs.path} 或 proot 二进制",
            )
        }
        if (!paths.prootLoader().exists()) {
            return@withContext Outcome(
                Status.ProotFailed,
                "缺少 proot loader（${paths.prootLoader().name}）——jniLibs 里没有它，" +
                    "targetSdk >= 29 时 proot 无法启动",
            )
        }

        // `echo` from the guest's own coreutils: if this prints, then exec of a
        // guest binary worked, which is the entire question.
        val marker = "pi-runtime-ok"
        val argv = ProotCommand.build(
            paths = paths,
            guestCommand = "echo $marker",
            cwd = "/",
            storage = storage,
        )
        val env = ProotCommand.environment(paths)

        runCatching {
            val process = ProcessBuilder(argv)
                .directory(paths.runtime)
                .also { it.environment().putAll(env) }
                .start()

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching Outcome(
                    Status.ProotFailed,
                    "proot 启动后 60 秒没有返回——通常是 ptrace 被 SELinux 拦下，" +
                        "或 loader 无法执行",
                    stderr = stderr.take(2000),
                )
            }

            when {
                stdout.contains(marker) -> Outcome(
                    Status.Ok,
                    "运行时可用（guest 已执行并返回）",
                    exitCode = process.exitValue(),
                    stdout = stdout.trim(),
                    stderr = stderr.take(2000),
                )

                // proot ran, but the guest binary did not. Distinguish the two
                // causes we can recognise from the message.
                looksLikeExecDenial(stderr) -> Outcome(
                    Status.GuestExecDenied,
                    "proot 启动了，但无法执行 rootfs 内的可执行文件（Android 10+ 的 W^X 限制）。" +
                        "这个 targetSdk 无法承载运行时——需要 targetSdk 28 的构建，或确认 " +
                        "PROOT_LOADER 指向了 nativeLibraryDir。",
                    exitCode = process.exitValue(),
                    stdout = stdout.take(2000),
                    stderr = stderr.take(2000),
                )

                else -> Outcome(
                    Status.ProotFailed,
                    "proot 返回了非预期结果（exit ${process.exitValue()}）",
                    exitCode = process.exitValue(),
                    stdout = stdout.take(2000),
                    stderr = stderr.take(2000),
                )
            }
        }.getOrElse { error ->
            Outcome(
                Status.ProotFailed,
                "无法启动 proot：${error::class.java.simpleName}: ${error.message}",
            )
        }
    }

    private fun looksLikeExecDenial(stderr: String): Boolean {
        val markers = listOf(
            "Permission denied",
            "EACCES",
            "cannot execute",
            "Exec format error",
            "No such file or directory",
        )
        return markers.any { stderr.contains(it, ignoreCase = true) }
    }

    /** Human-readable summary for the settings screen. */
    fun summarize(result: Outcome): String = buildString {
        append(
            when (result.status) {
                Status.Ok -> "✓ "
                Status.NotProvisioned -> "· "
                else -> "✗ "
            },
        )
        append(result.detail)
        if (result.stderr.isNotBlank()) {
            append("\n\n")
            append(result.stderr.lineSequence().take(12).joinToString("\n"))
        }
    }
}
