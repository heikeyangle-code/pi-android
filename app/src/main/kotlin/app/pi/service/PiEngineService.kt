package app.pi.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import app.pi.MainActivity
import app.pi.PiApplication
import app.pi.R
import app.pi.i18n.PiStrings

/**
 * Keeps a running turn alive.
 *
 * Why this exists: a pi turn is a model call plus an unbounded sequence of tool
 * calls (a test suite, an `npm install`, a compile). With the screen off, the
 * CPU suspends and a 3-minute task becomes an hour of wall-clock — or never
 * finishes. Android 8+ requires a foreground service for that, Android 14+
 * requires the service *type* to be declared and justified, and the wake lock
 * is what stops doze from freezing the engine mid-write.
 *
 * ## What it does *not* own
 *
 * The engine process is spawned by `PiEngineHost`, which the chat ViewModel owns —
 * a child of this app's process, not of this service (the older sentence here,
 * "spawned by [PiEngineController] and owned by this service", described the
 * intended shape and was never true: [PiEngineController] is the wiring point, and
 * until now its `stop()` only set an enum). What the service contributes is the
 * process's *priority*: a foreground service is what stops Android from killing the
 * process tree mid-turn, which is why [reportWork] and [stopIfRunning] keep the
 * service's existence tied to an engine that is actually there.
 *
 * The consequence of that split is stated where it is decided: the notification's
 * 停止 action stops the engine through [PiEngineController]'s registered handler
 * (`PiSessionViewModel`), because the service cannot reach a child it does not own.
 */
class PiEngineService : Service() {

    /**
     * `@Volatile` because [companion object] readers ask about it from the UI
     * thread while the service acquires/releases on the main thread; without it a
     * reader could see a stale lock object and report the previous state.
     */
    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var foregroundStarted: Boolean = false
    private val wakeLockLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Published before `onStartCommand` — the lock is acquired there, so a
        // reader can legitimately see "service running, lock not held yet".
        instance = this
    }

    /**
     * Both outcomes are `START_NOT_STICKY`, and both are decisions of
     * [PiEngineLifecyclePolicy].
     *
     * Ungraceful as it looks, it is the honest one: the engine is a **child process
     * of this app's process** (`PiEngineHost` spawns it), so a service the framework
     * re-creates after the process died has nothing left to keep alive. Answering
     * such a call — `intent == null` — with a notification saying 「PI 正在运行」 and a
     * fresh wake lock is a claim about an agent that does not exist, and the old code
     * did exactly that (`START_STICKY` + `else -> startForegroundWithNotification()`).
     * Starting a *new* engine is the app's job (the chat screen's 重试), not the
     * service's.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (PiEngineLifecyclePolicy.startCommand(intent?.action, systemRestart = intent == null)) {
            PiEngineLifecyclePolicy.StartCommand.StopEngine -> {
                // 若通过 startForegroundService 启动，Android 强制要求在 stopSelf 前完成一次 startForeground，
                // 否则会抛出 ForegroundServiceDidNotStartInTimeException 引发系统 ANR。
                if (!foregroundStarted) {
                    runCatching { startForegroundWithNotification() }
                }
                stopEngineAndSelf()
            }
            PiEngineLifecyclePolicy.StartCommand.StartEngine -> startForegroundWithNotification()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val active = desiredWorkActive
        if (active) acquireWakeLock() else releaseWakeLock()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(runningTasks = if (active) 1 else 0),
            type,
        )
        foregroundStarted = true
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ FGS timeout safety net: always stop foreground cleanly rather than crashing.
        stopServiceOnlyInternal()
    }

    /**
     * The notification is the user's only always-available "stop" affordance, so
     * it carries a real action rather than being a badge.
     */
    private fun buildNotification(runningTasks: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, PiEngineService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, PiApplication.CHANNEL_ENGINE)
            // Status bar notification icons must be monochrome alpha silhouettes; reuse the
            // adaptive icon's monochrome pi glyph layer so the icon stays crisp in the bar.
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(PiStrings.get(R.string.engine_running_title))
            .setContentText(PiStrings.get(R.string.engine_running_text, runningTasks))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, PiStrings.get(R.string.action_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Re-post with the live task count. */
    fun updateNotification(runningTasks: Int) {
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildNotification(runningTasks))
        }
    }

    private fun applyWork(active: Boolean) {
        if (active) acquireWakeLock() else releaseWakeLock()
        updateNotification(if (active) 1 else 0)
    }

    private fun acquireWakeLock() {
        synchronized(wakeLockLock) {
            if (wakeLock?.isHeld == true) return
            val power = getSystemService(PowerManager::class.java) ?: return
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }
    }

    private fun stopEngineAndSelf() {
        PiEngineController.stop()
        stopServiceOnlyInternal()
    }

    /** Stop the foreground service and release WakeLock WITHOUT stopping the engine process. */
    private fun stopServiceOnlyInternal() {
        releaseWakeLock()
        val stopBlock = {
            if (!foregroundStarted) {
                runCatching { startForegroundWithNotification() }
            }
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopBlock()
        } else {
            mainHandler.post(stopBlock)
        }
    }

    private fun releaseWakeLock() {
        synchronized(wakeLockLock) {
            wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
            wakeLock = null
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        // Cleared *after* the lock is released, so a reader that sees no instance
        // can never also believe a lock is still held by it.
        instance = null
        super.onDestroy()
    }

    companion object {
        /**
         * The notification's stop action, owned by the pure policy object so that
         * "what does this action mean" is a decision that can be checked without
         * Android (`tools/run-app-pure-checks.sh`, harness `lifecycle-policy`).
         */
        const val ACTION_STOP = PiEngineLifecyclePolicy.ACTION_STOP

        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "pi:engine"

        /**
         * The framework's cap on one acquisition, not "how long a turn may run".
         *
         * Since the lock is taken per busy period ([applyWork]), this only matters to
         * a single stretch of work longer than six hours (a very long tool run on a
         * phone with the screen off). Past it the framework releases the lock and the
         * app cannot ask for it again until the next busy period starts; that is a
         * known, visible residual — the 设置 row reads the lock's own `isHeld`
         * (`RuntimeFacts`), so it shows 未持有 rather than claiming protection.
         */
        private val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        /**
         * The live service, for read-only observation from the UI. Null when it has
         * never started or has been destroyed. Same shape as
         * `DeviceAccessibilityService.instance` / `isRunning()`.
         */
        @Volatile
        private var instance: PiEngineService? = null

        /** Latest requested WakeLock/work state; applied in `onStartCommand` if reported before start. */
        @Volatile
        private var desiredWorkActive: Boolean = false

        /** Whether the service exists right now. */
        fun isRunning(): Boolean = instance != null

        fun reportWork(active: Boolean) {
            desiredWorkActive = active
            instance?.applyWork(active)
        }

        /** Stop the foreground service + WakeLock ONLY (never kills the engine process). */
        fun stopServiceOnly() {
            desiredWorkActive = false
            instance?.stopServiceOnlyInternal()
        }

        /**
         * Stop the service **and the engine it exists for**, if it is running.
         */
        fun stopIfRunning() {
            desiredWorkActive = false
            instance?.stopEngineAndSelf()
        }

        /**
         * Whether the CPU wake lock is held **right now** — the lock's own
         * `isHeld`, not an inference from the service being alive.
         *
         * The two questions are not the same, and the difference is the whole point
         * of the row that reads this: the lock is held only while something CPU-bound
         * is happening ([PiEngineLifecyclePolicy.shouldHoldWakeLock] via [reportWork]),
         * so "service up, lock not held" is the **normal idle state**, not a fault.
         * Because it reads the lock itself, it is never an approximation.
         */
        fun isWakeLockHeld(): Boolean = instance?.wakeLock?.isHeld == true
    }
}
