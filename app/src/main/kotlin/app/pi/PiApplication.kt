package app.pi

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Process-wide entry point.
 *
 * Deliberately thin: the engine is *not* started here. Starting a Node runtime
 * inside `Application.onCreate` would stall the first frame, and on Android the
 * engine belongs to a foreground service, not to whichever process happens to
 * be alive (docs/pi-android-app-design.md §10).
 */
class PiApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ENGINE,
            getString(R.string.notif_channel_engine),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_engine_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ENGINE = "pi.engine"

        lateinit var instance: PiApplication
            private set

        val context: Context get() = instance.applicationContext
    }
}
