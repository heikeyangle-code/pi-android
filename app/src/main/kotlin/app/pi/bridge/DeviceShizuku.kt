package app.pi.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import moe.shizuku.server.IShizukuService
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Shizuku path to a uid=2000 (ADB) shell — the single biggest capability jump
 * available to this app without root.
 *
 * Why it matters: [AppUidShellBackend] runs as `u0_aXXX`, so `input keyevent`,
 * `pm`/`am` mutations, `settings`, `dumpsys` and anything that walks another
 * app's state either fail with a permission error or return a filtered view.
 * Shizuku runs our command in a process started by the ADB daemon (uid 2000) or
 * by root (uid 0), which is the privilege the design doc's `android_shell`
 * capability was written for (design §21.3 "Shizuku/ADB，uid=2000").
 *
 * ### Two API facts this file is built around, both verified against the artifacts
 *
 *  1. `Shizuku#newProcess` was made **private** in 13.1.5 (the changelog calls it
 *     "prepare to remove `Shizuku#newProcess`"). The *protocol* method is still
 *     there: `moe.shizuku.server.IShizukuService.newProcess(String[], String[], String)`
 *     is a public interface in the `dev.rikka.shizuku:aidl` artifact, and that is
 *     the exact call the private wrapper made (verified with `javap -c` on
 *     `api-13.1.5.aar`: the private method ends in
 *     `invokeinterface IShizukuService.newProcess`). We call the interface, so we
 *     depend on no private API. The officially recommended replacement is a
 *     UserService; we did not use it because a UserService needs its own AIDL
 *     interface, and this project's dependency-free typecheck (`tools/typecheck.sh`)
 *     never runs the AIDL compiler — a route that cannot be typechecked here is a
 *     route that cannot be trusted here.
 *  2. `Shizuku#requestPermission(int)` needs **no Activity**: it asks the Shizuku
 *     server, which shows its own confirmation, and the answer arrives through
 *     `addRequestPermissionResultListener` (README "Request permission"). That is
 *     what makes this implementable without touching MainActivity.
 *
 * Licence: `dev.rikka.shizuku:api` (and `:provider`, `:aidl`, `:shared`) are MIT
 * (the POM's `<licenses>` block). `:provider` declares `minSdkVersion 23`; this
 * app's `minSdk` is 26, so no desugaring is required (13.1.0's changelog only
 * requires it for minSdk 23).
 */
object DeviceShizuku {

    /** The Shizuku manager app. Sui (the Magisk module) is reached through it. */
    const val MANAGER_PACKAGE = "moe.shizuku.manager"

    /** Arbitrary; only echoed back to the result listener. */
    private const val PERMISSION_REQUEST_CODE = 0x7069

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val listenersRegistered = AtomicBoolean(false)

    @Volatile
    private var lastGrantResult: Int? = null

    /** Whether `Shizuku.pingBinder()` is true right now. Never throws. */
    fun binderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** Whether the manager app is installed (package visibility is declared for it). */
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** Shizuku's own version, or -1 when the binder is not there. */
    fun version(): Int = if (!binderAlive()) -1 else runCatching { Shizuku.getVersion() }.getOrDefault(-1)

    /** True for the pre-v11 protocol the API refuses to support (README). */
    fun preV11(): Boolean = if (!binderAlive()) false else runCatching { Shizuku.isPreV11() }.getOrDefault(false)

    /** uid 2000 for an ADB-started Shizuku, 0 when it was started with root. */
    fun uid(): Int = if (!binderAlive()) -1 else runCatching { Shizuku.getUid() }.getOrDefault(-1)

    /** True when this app already holds Shizuku's permission. */
    fun permissionGranted(): Boolean {
        if (!binderAlive()) return false
        return runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)
    }

    /** Everything a shell command needs: binder, permission, protocol version. */
    fun isReady(): Boolean = binderAlive() && !preV11() && permissionGranted()

    /**
     * Ask the user to grant this app Shizuku access. The dialog belongs to the
     * Shizuku app, so there is no Activity result to route through this app.
     *
     * @return `true` when the request was handed to the server. The answer is
     *   observed by polling [permissionGranted] (the authorization page already
     *   re-reads its state every 1.5s) and, when available, by
     *   [addPermissionResultListener].
     */
    fun requestPermission(): Boolean {
        if (!binderAlive()) return false
        registerListeners()
        return runCatching {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            true
        }.getOrDefault(false)
    }

    /** Who to notify when the permission dialog is answered. */
    fun addPermissionResultListener(listener: (Boolean) -> Unit) {
        registerListeners()
        permissionListeners.add(listener)
    }

    private val permissionListeners = java.util.Collections.synchronizedList(mutableListOf<(Boolean) -> Unit>())

    private fun registerListeners() {
        if (!listenersRegistered.compareAndSet(false, true)) return
        runCatching {
            Shizuku.addRequestPermissionResultListener({ _, grantResult ->
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                lastGrantResult = grantResult
                val snapshot = synchronized(permissionListeners) { permissionListeners.toList() }
                for (listener in snapshot) runCatching { listener(granted) }
            }, mainHandler)
        }
    }

    /** A status object for `/app/health` and the authorization page. */
    fun status(context: Context): JSONObject = JSONObject().apply {
        val alive = binderAlive()
        val granted = permissionGranted()
        val id = if (alive) uid() else -1
        put("installed", isInstalled(context))
        put("binderAlive", alive)
        put("preV11", preV11())
        put("permissionGranted", granted)
        put("ready", alive && granted && !preV11())
        put("uid", id)
        put("version", version())
        put("managerPackage", MANAGER_PACKAGE)
        put("backendLabel", ShizukuShellBackend.label)
        put("note", when {
            !alive && !isInstalled(context) ->
                "没有安装 Shizuku。装好并启动它（Android 11+ 可以用系统「无线调试」在手机上直接启动），" +
                    "再回到这里授权，Shell 就会以 ADB 身份（uid=2000）运行。"

            !alive ->
                "Shizuku 已安装但没有在运行。非 root 设备每次重启后都要重新启动 Shizuku" +
                    "（Android 11+ 用系统「无线调试」即可，不需要电脑）。"

            preV11() -> "Shizuku 是 v11 之前的版本，API 不支持。"

            !granted -> "Shizuku 在运行，但本应用还没有获得它的授权。点「请求 Shizuku 授权」即可。"

            id == 0 -> "Shizuku 已就绪，且是以 root（uid=0）启动的：Shell 会拿到完整 root 权限，硬性禁用清单仍然生效。"

            else -> "Shizuku 已就绪：Shell 以 ADB 身份（uid=$id）运行。"
        })
    }

    /**
     * Kept for the diagnostics page: the last permission answer we saw.
     *
     * **当前无调用方**：诊断页读的是 [status] 的 `note`，没有把原始结果码显示出来。
     * 留着是因为"用户点了授权、结果是什么"只有这里能回答。
     */
    fun lastPermissionResult(): Int? = lastGrantResult

    /**
     * Run [command] through Shizuku. Never called unless [isReady] is true —
     * [ShizukuShellBackend.available] gates it.
     */
    fun exec(command: String, timeoutMs: Int): DeviceShellResult {
        if (!isReady()) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.NO_PERMISSION,
                    reason = "Shizuku 不可用（未安装、未运行或未授权），无法以 ADB 身份执行命令。",
                    hint = "请让用户在「设置 → 设备能力 → Shell」里按提示安装/启动并授权 Shizuku，然后重试。",
                ),
            )
        }
        val binder = runCatching { Shizuku.getBinder() }.getOrNull()
            ?: throw DeviceActionException(
                DeviceDenial(DeviceDenial.NO_PERMISSION, "Shizuku 的 binder 已经断开（Shizuku 可能刚刚停止）。"),
            )
        val service = IShizukuService.Stub.asInterface(binder)
        val remote = try {
            service.newProcess(arrayOf("/system/bin/sh", "-c", command), null, "/")
        } catch (error: RemoteException) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.ERROR,
                    reason = "Shizuku 拒绝创建进程：${error.message}",
                    hint = "Shizuku 可能已经停止，请让用户重新启动它。",
                ),
            )
        } ?: throw DeviceActionException(
            DeviceDenial(DeviceDenial.ERROR, "Shizuku 没有返回远程进程（服务端版本可能太旧）。"),
        )

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exitCode = -1
        var timedOut = false
        try {
            // ParcelFileDescriptor.AutoCloseInputStream closes the PFD for us; the
            // remote end writes into it, so the streams must be drained on their
            // own threads or a chatty command deadlocks on a full pipe.
            val outStream = ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)
            val errStream = ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream)
            val outReader = Thread { readCapped(outStream, stdout) }
            val errReader = Thread { readCapped(errStream, stderr) }
            outReader.isDaemon = true
            errReader.isDaemon = true
            outReader.start()
            errReader.start()

            // `waitForTimeout` takes a TimeUnit *name* over the wire and the server
            // side has changed hands between versions, so waiting is done here on a
            // plain `waitFor` with a join deadline — no protocol string to guess.
            var waited = -1
            val waiter = Thread { waited = runCatching { remote.waitFor() }.getOrDefault(-1) }
            waiter.isDaemon = true
            waiter.start()
            waiter.join(timeoutMs.coerceIn(500, 60_000).toLong())
            if (waiter.isAlive) {
                timedOut = true
                runCatching { remote.destroy() }
                waiter.join(TimeUnit.SECONDS.toMillis(2))
            }
            exitCode = if (timedOut) -1 else waited
            outReader.join(500)
            errReader.join(500)
        } catch (error: RemoteException) {
            throw DeviceActionException(
                DeviceDenial(DeviceDenial.ERROR, "读取 Shizuku 远程进程失败：${error.message}"),
            )
        } finally {
            runCatching { remote.destroy() }
        }

        return DeviceShellResult(
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            exitCode = exitCode,
            backend = ShizukuShellBackend.id,
            uid = uid(),
            truncated = stdout.length >= MAX_OUTPUT_BYTES,
            timedOut = timedOut,
        )
    }

    private const val MAX_OUTPUT_BYTES = 50 * 1024

    private fun readCapped(stream: java.io.InputStream, into: StringBuilder) {
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
                    into.append(String(buffer, 0, read, Charsets.UTF_8))
                }
            }
        }
    }
}

/**
 * The elevated backend. `available` is a live probe, not a cached flag, so a
 * Shizuku that stops mid-session degrades the very next command to
 * [AppUidShellBackend] instead of failing with a confusing binder error.
 */
object ShizukuShellBackend : DeviceShellBackend {
    override val id: String = "shizuku"

    override val label: String
        get() = if (!DeviceShizuku.isReady()) {
            "Shizuku（未安装/未运行/未授权）"
        } else if (DeviceShizuku.uid() == 0) {
            "Shizuku（root，uid=0）"
        } else {
            "Shizuku（ADB 身份，uid=${DeviceShizuku.uid()}）"
        }

    override val available: Boolean get() = DeviceShizuku.isReady()

    override fun run(command: String, timeoutMs: Int): DeviceShellResult =
        DeviceShizuku.exec(command, timeoutMs)
}
