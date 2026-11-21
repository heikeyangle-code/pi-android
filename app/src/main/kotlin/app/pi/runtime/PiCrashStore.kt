package app.pi.runtime

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import app.pi.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Durable crash and process-exit persistence under `<files>/pi/persist/`.
 *
 * Lives in `persist/` (outside the volatile `runtime/` tree) so crash evidence survives both
 * Android process kills and runtime rebuilds, and is included in [app.pi.ui.settings.DiagnosticsReport].
 */
object PiCrashStore {

    private const val MAX_CRASH_LOG_BYTES = 128 * 1024
    private const val MAX_ENGINE_EXIT_CHARS = 32_000
    private val installed = AtomicBoolean(false)

    /**
     * Install a process-wide uncaught exception handler that synchronously flushes crash details
     * to `<files>/pi/persist/android-crashes.log` before delegating to the platform default handler.
     */
    fun installUncaughtExceptionHandler(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val paths = PiPaths(
                    filesDir = appContext.filesDir,
                    nativeLibDir = File(appContext.applicationInfo.nativeLibraryDir),
                )
                recordCrash(paths.crashLogFile(), thread.name, throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Append one crash record and trim the log to [MAX_CRASH_LOG_BYTES], with `fd.sync()`. */
    internal fun recordCrash(logFile: File, threadName: String, throwable: Throwable) {
        val stack = StringWriter().also { sw ->
            PrintWriter(sw).use { pw -> throwable.printStackTrace(pw) }
        }.toString()
        val entry = buildString {
            append("=== ")
            append(timestamp())
            append(" | v")
            append(BuildConfig.VERSION_NAME)
            append(" (")
            append(BuildConfig.VERSION_CODE)
            append(") | SDK ")
            append(Build.VERSION.SDK_INT)
            append(" | thread=")
            append(threadName)
            appendLine(" ===")
            appendLine(stack.trimEnd())
            appendLine()
        }
        appendBounded(logFile, entry, MAX_CRASH_LOG_BYTES)
    }

    /** Persist the engine's latest unexpected exit so diagnostics survive app death. */
    fun recordEngineExit(
        paths: PiPaths,
        engineName: String?,
        exitCode: Int?,
        state: String?,
        stderrTail: String?,
    ) {
        val text = buildString {
            appendLine("time=${timestamp()}")
            appendLine("engine=${engineName ?: "unknown"}")
            appendLine("state=${state ?: "unknown"}")
            appendLine("exitCode=${exitCode?.toString() ?: "unknown"}")
            val cleaned = stderrTail?.trim().orEmpty()
            if (cleaned.isNotEmpty()) {
                appendLine("--- stderr ---")
                appendLine(cleaned.takeLast(MAX_ENGINE_EXIT_CHARS))
            }
        }
        runCatching {
            writeStampAtomically(paths.lastEngineExitFile(), text)
        }
    }

    fun readCrashLog(paths: PiPaths, maxChars: Int = 24_000): String? = runCatching {
        val file = paths.crashLogFile()
        if (!file.isFile) return@runCatching null
        file.readText().trim().takeIf { it.isNotEmpty() }?.takeLast(maxChars)
    }.getOrNull()

    fun readLastEngineExit(paths: PiPaths, maxChars: Int = 20_000): String? = runCatching {
        val file = paths.lastEngineExitFile()
        if (!file.isFile) return@runCatching null
        file.readText().trim().takeIf { it.isNotEmpty() }?.takeLast(maxChars)
    }.getOrNull()

    /**
     * Platform-recorded historical process exits (API 30+): catches LMK kills, native SIGSEGV,
     * ANRs, and FGS timeouts that an in-process Java handler cannot observe.
     */
    fun readHistoricalExitReasons(context: Context, limit: Int = 5): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return@runCatching emptyList()
            val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, limit)
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            infos.map { info ->
                val ts = fmt.format(Date(info.timestamp))
                val reason = reasonName(info.reason)
                val pssMb = info.pss / 1024L
                val desc = info.description?.take(160)?.replace('\n', ' ').orEmpty()
                buildString {
                    append("$ts | $reason (status=${info.status}, pss=${pssMb}MB)")
                    if (desc.isNotEmpty()) append(" — $desc")
                }
            }
        }.getOrDefault(emptyList())
    }

    internal fun appendBounded(file: File, entry: String, maxBytes: Int) {
        file.parentFile?.mkdirs()
        val entryBytes = entry.toByteArray(Charsets.UTF_8)
        val existing = if (file.isFile) runCatching { file.readBytes() }.getOrNull() ?: ByteArray(0) else ByteArray(0)
        val combined = if (existing.size + entryBytes.size <= maxBytes) {
            existing + entryBytes
        } else {
            val keep = (maxBytes - entryBytes.size).coerceAtLeast(0)
            if (keep == 0) {
                entryBytes.copyOfRange(
                    (entryBytes.size - maxBytes).coerceAtLeast(0),
                    entryBytes.size,
                )
            } else {
                existing.copyOfRange(existing.size - keep, existing.size) + entryBytes
            }
        }
        FileOutputStream(file, false).use { out ->
            out.write(combined)
            out.flush()
            runCatching { out.fd.sync() }
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY(LMK)"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "REASON_$reason"
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
}
