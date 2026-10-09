package app.pi.bridge

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.pi.MainActivity
import app.pi.PiApplication
import app.pi.R

/**
 * 投屏专用的前台服务 —— 只为满足平台的一条硬条件：`getMediaProjection` 之前，本应用
 * 必须有一个 `foregroundServiceType="mediaProjection"` 的前台服务在跑。
 *
 * ## 为什么不是把 mediaProjection 挂到 PiEngineService 上
 *
 * 那样**会把引擎打挂**：声明只是类型上限，Android 14 的规则是——**声明了这个类型又没
 * 有投屏授权时，用该类型 `startForeground` 会抛异常**。而 `PiEngineService` 是随引擎
 * 启动的常驻服务，开机时当然没有投屏授权，等于让开机去撞一条必然失败的检查。所以投屏
 * 要自己的服务，而且只在用户点完系统授权对话框之后才由能力页启动（[start] 的调用点就在
 * 那个授权回调里）。
 *
 * ## 生命周期就是「这一次投屏」
 *
 * 本服务不持有 MediaProjection（那是 [PiScreenCapture] 的），它只让平台的两个条件成立：
 * 服务在跑、类型是 mediaProjection。反过来，服务一死这次投屏也就结束了，所以
 * [onDestroy] 里收一次 `PiScreenCapture.release()` ——「服务在」与「在投屏」是同一个事实
 * 的两半，让它们分开，就一定会出现「前台服务没了，但代码以为自己还在投屏」。
 *
 * 通知是前台服务的义务，也是用户在通知栏能看到的唯一痕迹。它发在 App 既有的低重要度
 * 频道上（[PiApplication.CHANNEL_ENGINE]），不新开频道：新频道要一条新的字符串资源，
 * 而这次改动只碰清单、路由、能力页与这个新服务。文案按 `PiInputMethodService` 的做法
 * 写在代码里，理由同上。
 */
class PiCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 先发布实例再进 onStartCommand：读者可能合法地看到「服务在、前台还没提起来」。
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 类型是 API 34 起才硬校验的，更低版本传 0 表示不带类型（与 PiEngineService 同一写法）。
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        // 不要粘性重启：投屏授权是一次性的，进程被杀之后没有人能替用户再点一次。重启一个
        // 「没有授权、没有投影」的服务，只会给用户留一条骗人的通知。
        return START_NOT_STICKY
    }

    /** 点通知回到 App：停止投屏的那个按钮在「设置 → 设备能力 → 基础」里。 */
    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, PiApplication.CHANNEL_ENGINE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("PI 正在投屏")
            .setContentText("屏幕截图与系统音频捕获进行中；在「设置 → 设备能力」里可以停止。")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        // 服务没了 = 这次投屏结束。先收投影（它可能已经被用户或系统停了，release 幂等），
        // 再清实例 —— 顺序与 PiEngineService 释放 wake lock 的写法一致：看到「没有实例」的
        // 读者，不会同时以为投影还在。
        runCatching { PiScreenCapture.release() }
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        /** 与 PiEngineService 的 1001 错开：两条通知同时在通知栏里时不得互相覆盖。 */
        private const val NOTIFICATION_ID = 1002

        @Volatile
        private var instance: PiCaptureService? = null

        /** 只读观察口，形状同 `DeviceAccessibilityService.isRunning()`。 */
        fun isRunning(): Boolean = instance != null

        /**
         * 拉起前台服务；返回是否成功发出请求。
         *
         * 只能在拿到投屏授权之后调用（Android 14 的顺序要求：先授权，再起这个类型的
         * 前台服务）。后台启动限制会让 `startForegroundService` 抛
         * `IllegalStateException`，这里如实返回 false，由调用方（授权回调，本来就活在
         * 前台界面里）决定怎么报 —— 不吞掉。
         */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, PiCaptureService::class.java))
            true
        }.getOrDefault(false)

        /** 停掉前台服务；投影由 [onDestroy] 顺手收尾。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PiCaptureService::class.java)) }
        }
    }
}
