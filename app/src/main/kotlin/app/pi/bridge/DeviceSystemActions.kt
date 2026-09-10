package app.pi.bridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Everything in the 「基础」, 「存储」 and 「位置 · 传感器 · 相机」 groups.
 *
 * Two rules shape this file:
 *
 *  1. **A missing Android permission is reported, never swallowed.** Several of
 *     these need a manifest permission the current manifest does not declare yet
 *     (VIBRATE, location). The failure therefore names the exact permission and
 *     what the user or the app has to do, because "什么都没发生" is the failure
 *     mode the design doc singles out (design §21.4).
 *  2. **Anything that leaves the sandbox is reversible or reported.** Export goes
 *     to the public Download collection through MediaStore so the user can see and
 *     delete it, and the reply carries the resulting location.
 */
object DeviceSystemActions {

    private const val NOTIFICATION_CHANNEL_ID = "pi.device.agent"
    private const val NOTIFICATION_CHANNEL_NAME = "Agent 设备通知"

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    // ------------------------------------------------------------ clipboard ----

    fun clipboardGet(context: Context): JSONObject {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: throw DeviceActionException(DeviceDenial(DeviceDenial.UNSUPPORTED, "系统没有剪贴板服务。"))
        if (!manager.hasPrimaryClip()) {
            return JSONObject().put("text", "").put("empty", true)
        }
        val clip = manager.primaryClip
        val item = clip?.getItemAt(0)
        val text = item?.coerceToText(context)?.toString().orEmpty()
        // Android 10+ hides the clipboard from background apps: the clip is
        // readable only while this app is the focused one. Say so instead of
        // returning a silent empty string.
        if (text.isEmpty() && manager.hasPrimaryClip()) {
            return JSONObject().put("text", "").put("empty", true).put(
                "note",
                "剪贴板当前内容无法读取（Android 10 起仅允许前台应用读取剪贴板，或内容不是文本）。",
            )
        }
        return JSONObject().put("text", text).put("empty", text.isEmpty())
    }

    fun clipboardSet(context: Context, text: String): JSONObject {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: throw DeviceActionException(DeviceDenial(DeviceDenial.UNSUPPORTED, "系统没有剪贴板服务。"))
        mainHandler.post { manager.setPrimaryClip(ClipData.newPlainText("pi", text)) }
        return JSONObject().put("chars", text.length)
    }

    // --------------------------------------------------------- notifications ----

    fun notify(context: Context, title: String, text: String, id: Int): JSONObject {
        if (!DeviceCapabilityStore.get(context).hasNotificationPermission()) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.NO_PERMISSION,
                    reason = "系统未授予通知权限（POST_NOTIFICATIONS），通知发不出去。",
                    hint = "请让用户在系统设置 → 应用 → pi-android → 通知 中允许通知，然后重试。",
                ),
            )
        }
        ensureChannel(context)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (launch != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        NotificationManagerCompat.from(context).notify(id.coerceIn(1, 100000), builder.build())
        return JSONObject().put("id", id).put("channel", NOTIFICATION_CHANNEL_ID)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    // ------------------------------------------------------------------ toast ----

    fun toast(context: Context, text: String, long: Boolean): JSONObject {
        val duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        val appContext = context.applicationContext
        mainHandler.post { Toast.makeText(appContext, text, duration).show() }
        return JSONObject().put("duration", if (long) "long" else "short")
    }

    // ---------------------------------------------------------------- vibrate ----

    fun vibrate(context: Context, milliseconds: Int, pattern: List<Long>?): JSONObject {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.VIBRATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.NO_PERMISSION,
                    reason = "应用没有 VIBRATE 权限，无法震动。" +
                        "（AndroidManifest.xml 需要添加 <uses-permission android:name=\"android.permission.VIBRATE\" />）",
                    hint = "请让用户或维护者在清单中加入 VIBRATE 权限后重新构建安装。",
                ),
            )
        }
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            ?: throw DeviceActionException(DeviceDenial(DeviceDenial.UNSUPPORTED, "系统没有振动器。"))
        if (!vibrator.hasVibrator()) {
            throw DeviceActionException(DeviceDenial(DeviceDenial.UNSUPPORTED, "这台设备没有振动马达。"))
        }
        val ok = if (pattern.isNullOrEmpty()) {
            val ms = milliseconds.coerceIn(10, 10_000)
            vibrator.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            true
        } else {
            val waveform = pattern.map { it.coerceIn(0L, 10_000L) }.toLongArray()
            vibrator.vibrate(VibrationEffect.createWaveform(waveform, -1))
            true
        }
        return JSONObject().put("ok", ok)
    }

    // ------------------------------------------------------------------ share ----

    fun share(context: Context, text: String, subject: String?, url: String?): JSONObject {
        val body = when {
            url == null -> text
            text.isEmpty() -> url
            else -> "$text\n$url"
        }
        if (body.isEmpty()) {
            throw DeviceActionException(DeviceDenial(DeviceDenial.BAD_REQUEST, "分享内容为空。"))
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
            if (!subject.isNullOrEmpty()) putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        return launch(context, Intent.createChooser(send, subject ?: "分享"), "分享")
    }

    // ------------------------------------------------------------------- open ----

    fun open(context: Context, url: String): JSONObject {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
            ?: throw DeviceActionException(DeviceDenial(DeviceDenial.BAD_REQUEST, "无法解析的 URL：$url"))
        if (uri.scheme.isNullOrEmpty()) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.BAD_REQUEST,
                    reason = "URL 缺少 scheme（例如 https://）。",
                    hint = "请补全成 https://… 或 mailto:… 这类完整地址。",
                ),
            )
        }
        return launch(context, Intent(Intent.ACTION_VIEW, uri), "打开链接")
    }

    /**
     * Android 10+ restricts starting an activity from the background. The app
     * normally has a foreground service running, which is what makes this work in
     * practice; when the OEM still refuses, the caller gets a precise reason
     * instead of a silent no-op.
     */
    private fun launch(context: Context, intent: Intent, what: String): JSONObject {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            JSONObject().put("ok", true).put("action", what)
        } catch (error: Exception) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.UNSUPPORTED,
                    reason = "$what 失败：${error::class.java.simpleName}: ${error.message}",
                    hint = "Android 10+ 限制后台启动界面。请让用户把 pi-android 切到前台后重试，或确认设备上有能处理该意图的应用。",
                ),
            )
        }
    }

    // -------------------------------------------------------------- location ----

    fun location(context: Context): JSONObject {
        if (!DeviceCapabilityStore.get(context).hasLocationPermission()) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.NO_PERMISSION,
                    reason = "应用没有定位权限（ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION）。",
                    hint = "请让用户在系统设置 → 应用 → pi-android → 权限 中授予定位权限；" +
                        "维护者还需要在 AndroidManifest.xml 中声明这两个权限。",
                ),
            )
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: throw DeviceActionException(DeviceDenial(DeviceDenial.UNSUPPORTED, "系统没有定位服务。"))
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var best: Location? = null
        var enabledAny = false
        for (provider in providers) {
            val enabled = runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
            if (enabled) enabledAny = true
            val last = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (best == null || last.time > best.time) best = last
        }
        val fix = best
        if (fix == null) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.NOT_FOUND,
                    reason = if (enabledAny) {
                        "还没有可用的定位结果（缓存为空）。"
                    } else {
                        "设备的定位服务未开启。"
                    },
                    hint = "请让用户打开系统定位开关，并先在地图类应用中获取一次定位，然后重试。",
                ),
            )
        }
        return JSONObject().apply {
            put("latitude", fix.latitude)
            put("longitude", fix.longitude)
            put("accuracyMeters", fix.accuracy.toDouble())
            put("provider", fix.provider)
            put("timestamp", fix.time)
            put("ageSeconds", ((System.currentTimeMillis() - fix.time) / 1000).coerceAtLeast(0))
            put("stale", System.currentTimeMillis() - fix.time > 5 * 60 * 1000)
        }
    }

    // --------------------------------------------------------------- sensors ----

    fun sensors(context: Context): JSONObject {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: throw DeviceActionException(DeviceDenial(DeviceDenial.UNSUPPORTED, "系统没有传感器服务。"))
        val list = manager.getSensorList(Sensor.TYPE_ALL)
        val array = JSONArray()
        for (sensor in list) {
            array.put(
                JSONObject().apply {
                    put("name", sensor.name)
                    put("type", sensor.type)
                    put("typeName", sensorTypeName(sensor.type))
                    put("vendor", sensor.vendor)
                    put("maxRange", sensor.maximumRange.toDouble())
                    put("resolution", sensor.resolution.toDouble())
                    put("power", sensor.power.toDouble())
                },
            )
        }
        return JSONObject().apply {
            put("count", list.size)
            put("sensors", array)
        }
    }

    fun sensorSample(context: Context, typeName: String, type: Int, timeoutMs: Int): JSONObject {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: throw DeviceActionException(DeviceDenial(DeviceDenial.UNSUPPORTED, "系统没有传感器服务。"))
        val resolved = if (typeName.isNotEmpty()) {
            manager.getSensorList(Sensor.TYPE_ALL).firstOrNull {
                sensorTypeName(it.type).equals(typeName, ignoreCase = true) ||
                    it.name.equals(typeName, ignoreCase = true)
            }
        } else if (type > 0) {
            manager.getDefaultSensor(type)
        } else {
            null
        }
        val sensor = resolved ?: throw DeviceActionException(
            DeviceDenial(
                code = DeviceDenial.NOT_FOUND,
                reason = "找不到传感器「$typeName」。",
                hint = "请先调用 android_sensors 列出可用的 typeName。",
            ),
        )

        val latch = CountDownLatch(1)
        val values = AtomicReference<FloatArray?>(null)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                values.set(event.values.copyOf())
                latch.countDown()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        if (!registered) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.ERROR,
                    reason = "无法注册传感器监听器。",
                ),
            )
        }
        try {
            val wait = timeoutMs.coerceIn(200, 5000).toLong()
            if (!latch.await(wait, TimeUnit.MILLISECONDS)) {
                throw DeviceActionException(
                    DeviceDenial(
                        code = DeviceDenial.NOT_FOUND,
                        reason = "在 ${wait}ms 内没有收到「${sensor.name}」的数据。",
                        hint = "该传感器可能不可用，或采样很慢；可以加大 timeoutMs 后重试。",
                    ),
                )
            }
        } finally {
            manager.unregisterListener(listener)
        }
        val read = values.get() ?: FloatArray(0)
        val array = JSONArray()
        for (value in read) array.put(value.toDouble())
        return JSONObject().apply {
            put("name", sensor.name)
            put("typeName", sensorTypeName(sensor.type))
            put("values", array)
            put("units", sensorUnits(sensor.type))
        }
    }

    private fun sensorTypeName(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "accelerometer"
        Sensor.TYPE_GYROSCOPE -> "gyroscope"
        Sensor.TYPE_MAGNETIC_FIELD -> "magnetic_field"
        Sensor.TYPE_LIGHT -> "light"
        Sensor.TYPE_PROXIMITY -> "proximity"
        Sensor.TYPE_PRESSURE -> "pressure"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "relative_humidity"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "ambient_temperature"
        Sensor.TYPE_GRAVITY -> "gravity"
        Sensor.TYPE_LINEAR_ACCELERATION -> "linear_acceleration"
        Sensor.TYPE_ROTATION_VECTOR -> "rotation_vector"
        Sensor.TYPE_STEP_COUNTER -> "step_counter"
        Sensor.TYPE_STEP_DETECTOR -> "step_detector"
        Sensor.TYPE_HEART_RATE -> "heart_rate"
        Sensor.TYPE_SIGNIFICANT_MOTION -> "significant_motion"
        else -> "type_$type"
    }

    private fun sensorUnits(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION -> "m/s^2"
        Sensor.TYPE_GYROSCOPE -> "rad/s"
        Sensor.TYPE_MAGNETIC_FIELD -> "uT"
        Sensor.TYPE_LIGHT -> "lx"
        Sensor.TYPE_PROXIMITY -> "cm"
        Sensor.TYPE_PRESSURE -> "hPa"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "%"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "°C"
        Sensor.TYPE_HEART_RATE -> "bpm"
        else -> ""
    }

    // ---------------------------------------------------------------- battery ----

    fun battery(context: Context): JSONObject {
        val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: throw DeviceActionException(DeviceDenial(DeviceDenial.ERROR, "无法读取电池状态广播。"))
        val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0)
        val temperature = intent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0)
        return JSONObject().apply {
            put("percent", percent)
            put("status", batteryStatus(status))
            put("plugged", plugged != 0)
            put("temperatureC", temperature / 10.0)
        }
    }

    private fun batteryStatus(status: Int): String = when (status) {
        android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        android.os.BatteryManager.BATTERY_STATUS_FULL -> "full"
        android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        else -> "unknown"
    }

    // ------------------------------------------------------------------ torch ----

    fun torch(context: Context, on: Boolean): JSONObject {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: throw DeviceActionException(DeviceDenial(DeviceDenial.UNSUPPORTED, "系统没有相机服务。"))
        val cameraId = try {
            manager.cameraIdList.firstOrNull { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (error: CameraAccessException) {
            null
        } ?: throw DeviceActionException(
            DeviceDenial(
                code = DeviceDenial.UNSUPPORTED,
                reason = "没有找到带闪光灯的相机（或缺少相机权限）。",
                hint = "维护者可在 AndroidManifest.xml 中声明 CAMERA 权限后重试。",
            ),
        )
        return try {
            manager.setTorchMode(cameraId, on)
            JSONObject().put("on", on).put("cameraId", cameraId)
        } catch (error: CameraAccessException) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.ERROR,
                    reason = "手电筒切换失败：${error.reason}",
                    hint = "请让用户确认没有其他应用正在使用相机。",
                ),
            )
        } catch (error: SecurityException) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.NO_PERMISSION,
                    reason = "应用没有相机权限，无法控制手电筒。",
                    hint = "维护者需要在 AndroidManifest.xml 中声明 CAMERA 权限。",
                ),
            )
        }
    }

    // -------------------------------------------------------------------- tts ----

    /**
     * Speak [text] and wait (off the main thread) until it finishes.
     *
     * Why every `TextToSpeech` call is posted to the main looper: the engine's
     * constructor and its methods assume a thread with a `Looper`, and the bridge
     * serves requests from a plain thread-pool thread that has none. Blocking the
     * main thread on the result is not an option either, so the work is posted and
     * the waiting happens here.
     */
    fun speak(
        context: Context,
        text: String,
        languageTag: String,
        rate: Double,
        pitch: Double,
        timeoutMs: Int,
    ): JSONObject {
        if (text.isBlank()) {
            throw DeviceActionException(DeviceDenial(DeviceDenial.BAD_REQUEST, "朗读文本为空。"))
        }
        val status = AtomicInteger(TextToSpeech.ERROR)
        val engineRef = AtomicReference<TextToSpeech?>(null)
        val languageAvailable = AtomicInteger(TextToSpeech.LANG_AVAILABLE)
        val initLatch = CountDownLatch(1)

        mainHandler.post {
            runCatching {
                TextToSpeech(context.applicationContext) { code ->
                    status.set(code)
                    initLatch.countDown()
                }
            }.onSuccess { engine -> engineRef.set(engine) }
                .onFailure { initLatch.countDown() }
        }

        if (!initLatch.await(15, TimeUnit.SECONDS)) {
            shutdownOnMain(engineRef.get())
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.UNSUPPORTED,
                    reason = "TTS 引擎初始化超时（15 秒）。",
                    hint = "这台设备可能没有安装语音合成引擎。",
                ),
            )
        }
        val engine = engineRef.get()
        if (engine == null || status.get() != TextToSpeech.SUCCESS) {
            shutdownOnMain(engine)
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.UNSUPPORTED,
                    reason = "TTS 引擎初始化失败（状态 ${status.get()}）。",
                    hint = "请让用户在系统设置中安装并选择一个语音合成引擎。",
                ),
            )
        }

        val locale = if (languageTag.isBlank()) Locale.getDefault() else Locale.forLanguageTag(languageTag)
        mainHandler.post {
            runCatching {
                languageAvailable.set(engine.isLanguageAvailable(locale))
            }
        }
        // The language probe is a binder call; give it a moment without blocking the
        // main thread.
        Thread.sleep(60)
        val available = languageAvailable.get()
        if (available == TextToSpeech.LANG_MISSING_DATA || available == TextToSpeech.LANG_NOT_SUPPORTED) {
            shutdownOnMain(engine)
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.UNSUPPORTED,
                    reason = "TTS 引擎不支持语言「${locale.toLanguageTag()}」。",
                    hint = "请改用支持的语言，或让用户安装对应的语音数据包。",
                ),
            )
        }

        val done = CountDownLatch(1)
        val queued = AtomicInteger(TextToSpeech.ERROR)
        mainHandler.post {
            runCatching {
                engine.setLanguage(locale)
                engine.setSpeechRate(rate.coerceIn(0.1, 3.0).toFloat())
                engine.setPitch(pitch.coerceIn(0.1, 3.0).toFloat())
                engine.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            done.countDown()
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            done.countDown()
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            done.countDown()
                        }
                    },
                )
                val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID) }
                queued.set(engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID))
            }.onFailure { done.countDown() }
        }

        // Wait for the queued call above to land before judging it.
        var waitedForQueue = 0
        while (queued.get() == TextToSpeech.ERROR && waitedForQueue < 2000) {
            Thread.sleep(20)
            waitedForQueue += 20
        }
        if (queued.get() != TextToSpeech.SUCCESS) {
            shutdownOnMain(engine)
            throw DeviceActionException(
                DeviceDenial(code = DeviceDenial.ERROR, reason = "TTS 引擎拒绝了这次朗读请求。"),
            )
        }

        val wait = timeoutMs.coerceIn(1000, 60_000).toLong()
        val finished = done.await(wait, TimeUnit.MILLISECONDS)
        // The engine is a heavy resource; release it once speech has finished (or
        // once the caller's patience has run out).
        if (finished) {
            shutdownOnMain(engine)
        } else {
            mainHandler.postDelayed({ runCatching { engine.shutdown() } }, 1500L)
        }
        return JSONObject().apply {
            put("spoken", true)
            put("finished", finished)
            put("language", locale.toLanguageTag())
            put("chars", text.length)
        }
    }

    private fun shutdownOnMain(engine: TextToSpeech?) {
        if (engine == null) return
        if (Looper.myLooper() === Looper.getMainLooper()) {
            runCatching { engine.shutdown() }
        } else {
            mainHandler.post { runCatching { engine.shutdown() } }
        }
    }

    private const val UTTERANCE_ID = "pi-android-tts"

    // --------------------------------------------------------------- storage ----

    /** Public Download, sanitised name, MediaStore on API 29+, legacy path below. */
    fun export(
        context: Context,
        name: String,
        text: String?,
        base64: String?,
        mimeType: String,
    ): JSONObject {
        val safeName = sanitizeName(name)
        val bytes: ByteArray = when {
            base64 != null -> runCatching { android.util.Base64.decode(base64, android.util.Base64.DEFAULT) }
                .getOrElse {
                    throw DeviceActionException(
                        DeviceDenial(DeviceDenial.BAD_REQUEST, "base64 内容无法解码：${it.message}"),
                    )
                }
            text != null -> text.toByteArray(Charsets.UTF_8)
            else -> throw DeviceActionException(
                DeviceDenial(DeviceDenial.BAD_REQUEST, "导出需要 content（文本）或 base64（二进制）之一。"),
            )
        }
        if (bytes.isEmpty()) {
            throw DeviceActionException(DeviceDenial(DeviceDenial.BAD_REQUEST, "导出内容为空。"))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values)
            if (uri == null) {
                throw DeviceActionException(
                    DeviceDenial(
                        code = DeviceDenial.ERROR,
                        reason = "无法在 Download 中创建文件「$safeName」。",
                        hint = "请让用户确认设备存储空间充足且未处于「工作资料」受限目录。",
                    ),
                )
            }
            try {
                resolver.openOutputStream(uri)?.use { stream -> stream.write(bytes) }
                    ?: throw DeviceActionException(DeviceDenial(DeviceDenial.ERROR, "无法打开输出流。"))
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (error: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                if (error is DeviceActionException) throw error
                throw DeviceActionException(
                    DeviceDenial(
                        code = DeviceDenial.ERROR,
                        reason = "写入 Download 失败：${error::class.java.simpleName}: ${error.message}",
                    ),
                )
            }
            return JSONObject().apply {
                put("uri", uri.toString())
                put("displayName", safeName)
                put("location", "Download")
                put("bytes", bytes.size)
                put("mimeType", mimeType)
            }
        }

        // API < 29: the public directory needs WRITE_EXTERNAL_STORAGE, which the
        // app does not declare. Fail with the exact requirement.
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val target = File(directory, safeName)
        return try {
            directory.mkdirs()
            target.writeBytes(bytes)
            JSONObject().apply {
                put("uri", Uri.fromFile(target).toString())
                put("displayName", safeName)
                put("location", "Download")
                put("bytes", bytes.size)
                put("mimeType", mimeType)
                put("legacyPath", true)
            }
        } catch (error: Exception) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.NO_PERMISSION,
                    reason = "在 Android ${Build.VERSION.RELEASE} 上写入公共 Download 需要存储权限，当前未授予。",
                    hint = "请让用户授予存储权限，或维护者在清单中声明 WRITE_EXTERNAL_STORAGE。",
                ),
            )
        }
    }

    fun import(context: Context, name: String, maxBytes: Int): JSONObject {
        val safeName = sanitizeName(name)
        val limit = maxBytes.coerceIn(1024, 4 * 1024 * 1024)
        val bytes: ByteArray = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
            var stream: java.io.InputStream? = null
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(safeName),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        id,
                    )
                    stream = resolver.openInputStream(uri)
                }
            }
            val input = stream ?: throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.NOT_FOUND,
                    reason = "在 Download 中找不到应用可读取的文件「$safeName」。",
                    hint = "只有本应用导出的文件默认可读；其他应用的文件需要用户先授予存储访问。",
                ),
            )
            input.use { it.readBytes().let { data -> if (data.size > limit) data.copyOf(limit) else data } }
        } else {
            val target = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), safeName)
            if (!target.isFile) {
                throw DeviceActionException(
                    DeviceDenial(DeviceDenial.NOT_FOUND, "在 Download 中找不到「$safeName」。"),
                )
            }
            target.readBytes().let { if (it.size > limit) it.copyOf(limit) else it }
        }

        val isText = bytes.all { byte ->
            val value = byte.toInt() and 0xFF
            value == 9 || value == 10 || value == 13 || value in 32..126 || value >= 128
        }
        return JSONObject().apply {
            put("displayName", safeName)
            put("bytes", bytes.size)
            put("truncated", bytes.size >= limit)
            if (isText) {
                put("text", String(bytes, Charsets.UTF_8))
            } else {
                put("base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            }
        }
    }

    /** No path separators, no traversal, no leading dot, bounded length. */
    private fun sanitizeName(name: String): String {
        val cleaned = name.trim().replace('\\', '/').substringAfterLast('/')
            .replace("..", "_")
            .trim()
        val safe = if (cleaned.isEmpty() || cleaned.startsWith(".")) "pi-export-${System.currentTimeMillis()}.txt" else cleaned
        return if (safe.length <= 120) safe else safe.take(120)
    }

    /** Exposed for the diagnostics card: where export lands. */
    fun downloadDirectoryLabel(): String = "Download（公共目录，用户可见）"

    /** Audio route hint used by the TTS endpoint on some ROMs. */
    @Suppress("unused")
    private fun audioManager(context: Context): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
}
