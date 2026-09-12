package app.pi.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import app.pi.MainActivity
import app.pi.PiApplication
import app.pi.R

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
 * The engine process itself is spawned by [PiEngineController] and owned by this
 * service, never by an Activity: the transcript must survive the user swiping
 * away from the chat screen.
 */
class PiEngineService : Service() {

    /**
     * `@Volatile` because [companion object] readers ask about it from the UI
     * thread while the service acquires/releases on the main thread; without it a
     * reader could see a stale lock object and report the previous state.
     */
    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Published before `onStartCommand` — the lock is acquired there, so a
        // reader can legitimately see "service running, lock not held yet".
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEngineAndSelf()
                return START_NOT_STICKY
            }
            else -> startForegroundWithNotification()
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        acquireWakeLock()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(runningTasks = 0),
            type,
        )
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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.engine_running_title))
            .setContentText(getString(R.string.engine_running_text, runningTasks))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Re-post with the live task count. */
    fun updateNotification(runningTasks: Int) {
        NotificationManagerCompat.from(this)
            .notify(NOTIFICATION_ID, buildNotification(runningTasks))
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun stopEngineAndSelf() {
        // Engine shutdown is idempotent; the controller tears down proot and the
        // Node process tree.
        PiEngineController.stop()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        // Cleared *after* the lock is released, so a reader that sees no instance
        // can never also believe a lock is still held by it.
        instance = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "app.pi.action.STOP_ENGINE"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "pi:engine"
        private val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        /**
         * The live service, for read-only observation from the UI. Null when it has
         * never started or has been destroyed. Same shape as
         * `DeviceAccessibilityService.instance` / `isRunning()`.
         */
        @Volatile
        private var instance: PiEngineService? = null

        /** Whether the service exists right now. */
        fun isRunning(): Boolean = instance != null

        /**
         * Whether the CPU wake lock is held **right now** — the lock's own
         * `isHeld`, not an inference from the service being alive.
         *
         * The two questions are not the same, and the difference is real:
         *  - the lock is acquired in [onStartCommand] and released in
         *    [stopEngineAndSelf] / [onDestroy], so it is normally held for exactly
         *    the service's lifetime;
         *  - it is acquired **with a 6-hour timeout**, so a service that works
         *    longer than that keeps running with the lock already released by the
         *    framework. In that window [isRunning] is true while this is false;
         *  - between `onCreate` and the acquire, and during teardown, the two can
         *    also disagree for a moment.
         *
         * Because it reads the lock itself, it is never an approximation.
         */
        fun isWakeLockHeld(): Boolean = instance?.wakeLock?.isHeld == true
    }
}
