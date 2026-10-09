package app.pi.bridge

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 投屏截图 + 系统音频捕获：MediaProjection 的封装，低延迟、增量取帧。
 *
 * ### 为什么用 MediaProjection 而不是无障碍截图
 *
 * 无障碍的 `GLOBAL_ACTION_TAKE_SCREENSHOT`（API 30+）能截图，但拿不到系统音频，
 * 也对「视频/游戏」这类自绘界面按屏幕刷新率更新很吃力。MediaProjection 是系统
 * 唯一同时给出「画面」与「音频回放捕获」的通道，代价是每次都要用户授权一次。
 *
 * ### 低延迟 + 增量（本实现的形状）
 *
 * - `ImageReader` 的 `maxImages = 2`：默认值（通常更大）会积压旧帧、抬高延迟。
 * - 用一个 `OnImageAvailableListener` 在后台 HandlerThread 上**持续**取
 *   `acquireLatestImage()` 并**立刻 close**，永远只留最新一帧。这样既不会积压，
 *   也能真实统计帧率。
 * - 最新帧转成 `Bitmap` 缓存，并算一个**抽样帧差签名**（16×16 网格采样）。
 *   内容没变时 [grabJpeg] 返回 `null`（增量语义），`force = true` 可强制重出。
 *
 * ### 授权流程（本组件自己不起 Activity）
 *
 * 上层先用 [consentIntent] 拿 `createScreenCaptureIntent()`，走
 * `startActivityForResult`；`onActivityResult` 里拿到 `resultCode` + `data` 后调
 * [attach]。本对象不持有 Activity。
 *
 * ### 诚实边界
 *
 * - **系统音频**需要 API 29+（`AudioPlaybackCaptureConfiguration`）以及
 *   `RECORD_AUDIO` 权限；更低版本 [startAudioCapture] 返回 `UNSUPPORTED`，不静默失败。
 * - 音频只捕获「允许回放捕获」的应用（默认排除通话、部分受保护应用），这是系统策略，
 *   不是本组件能绕过的。
 * - Android 14（API 34）+ 还要求先有一个 `foregroundServiceType="mediaProjection"`
 *   的前台服务在跑，`getMediaProjection` 才会成功 —— 那属于 Manifest/通知接线（波2）。
 */
object PiScreenCapture {

    private const val FPS_WINDOW_MS = 2000L
    private const val MAX_IMAGES = 2
    private const val DEFAULT_QUALITY = 70
    private const val SAMPLE_GRID = 16
    private const val SAMPLE_RATE = 44100
    private const val AUDIO_CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO

    private val lock = Any()

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null

    private var width = 0
    private var height = 0
    private var density = 0

    /** 最新一帧（惰性更新）；由监听线程回收/替换，读取方必须在 [lock] 内压缩。 */
    private var latestBitmap: Bitmap? = null
    private var latestSignature = 0
    private var latestGeneration = 0
    private var servedGeneration = 0

    private val frameTimes = ArrayDeque<Long>()
    private var frameCount = 0L
    private var lastFrameAt = 0L

    // ------------------------------------------------------------------ 音频 ----

    private val audioRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null

    @Volatile
    private var audioBytes = 0L

    @Volatile
    private var audioFile: File? = null

    // -------------------------------------------------------------- 对接面 ----

    /** MediaProjection 在 API 21+ 都可用；这里回答「系统有没有这个服务」。 */
    fun available(context: Context): Boolean =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) is MediaProjectionManager

    /**
     * 投屏授权 Intent；拿不到服务时返回 `null`。上层拿到后自己
     * `startActivityForResult` —— 本组件不持有 Activity。
     */
    fun consentIntent(context: Context): Intent? {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return null
        return runCatching { manager.createScreenCaptureIntent() }.getOrNull()
    }

    /** 是否已在投屏（授权已接上且虚拟显示已建立）。 */
    fun isCapturing(): Boolean = projection != null && imageReader != null

    /**
     * 接上授权、建立虚拟显示。传 [Activity.RESULT_OK] 与授权 data。
     *
     * @return 成功时是 [status]（带 `ok = true`），失败时是 `{"ok":false,"error":…}`。
     */
    fun attach(context: Context, resultCode: Int, data: Intent?): JSONObject {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return failure("系统没有投屏服务（MediaProjectionManager 不可用）。")
        if (resultCode != Activity.RESULT_OK || data == null) {
            return failure("用户拒绝了投屏授权（resultCode=$resultCode）。")
        }
        release()

        val projectionResult = runCatching { manager.getMediaProjection(resultCode, data) }
        val newProjection = projectionResult.getOrNull()
            ?: return failure(
                "getMediaProjection 失败：${projectionResult.exceptionOrNull()?.message ?: "授权可能已过期"}" +
                    "（Android 14+ 需先有 mediaProjection 类型的前台服务）。",
            )

        val metrics = context.resources.displayMetrics
        val targetWidth = metrics.widthPixels.coerceAtLeast(1)
        val targetHeight = metrics.heightPixels.coerceAtLeast(1)
        val targetDensity = metrics.densityDpi

        val reader = runCatching {
            ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, MAX_IMAGES)
        }.getOrNull()
        if (reader == null) {
            runCatching { newProjection.stop() }
            return failure("无法创建 ImageReader。")
        }

        val mainHandler = Handler(Looper.getMainLooper())
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                // 授权被用户/系统撤回：只清本地资源，绝不再调 projection.stop()（会递归）。
                synchronized(lock) { detachDisplay() }
                stopAudioInternal()
                projection = null
            }
        }
        // API 34 起必须在 createVirtualDisplay 之前注册回调，否则抛异常。
        runCatching { newProjection.registerCallback(callback, mainHandler) }

        val thread = HandlerThread("pi-screen-capture").apply { start() }
        val handler = Handler(thread.looper)
        reader.setOnImageAvailableListener(imageListener, handler)

        val displayResult = runCatching {
            newProjection.createVirtualDisplay(
                "pi-screen-capture",
                targetWidth,
                targetHeight,
                targetDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            )
        }
        val display = displayResult.getOrNull()
        if (display == null) {
            runCatching { reader.setOnImageAvailableListener(null, null) }
            runCatching { reader.close() }
            thread.quitSafely()
            runCatching { newProjection.unregisterCallback(callback) }
            runCatching { newProjection.stop() }
            return failure("createVirtualDisplay 失败：${displayResult.exceptionOrNull()?.message ?: "系统拒绝"}。")
        }

        projection = newProjection
        projectionCallback = callback
        virtualDisplay = display
        imageReader = reader
        imageThread = thread
        width = targetWidth
        height = targetHeight
        density = targetDensity
        return status().put("ok", true)
    }

    /**
     * 取最新一帧的 JPEG。
     *
     * @param quality JPEG 质量（20–100）。
     * @param force 为 true 时即使画面没有变化也重新编码。
     * @param waitMs 首帧未到时最多等待的毫秒数。
     * @return 变化的帧的 JPEG 字节；画面没变时返回 `null`（增量语义）。
     */
    fun grabJpeg(quality: Int = DEFAULT_QUALITY, force: Boolean = false, waitMs: Long = 800): ByteArray? {
        val deadline = System.currentTimeMillis() + waitMs.coerceAtLeast(0)
        while (true) {
            val ready = synchronized(lock) { latestBitmap != null }
            if (ready) break
            if (System.currentTimeMillis() >= deadline) return null
            runCatching { Thread.sleep(20) }
        }
        return synchronized(lock) {
            val bitmap = latestBitmap ?: return null
            if (!force && latestGeneration == servedGeneration) return null
            servedGeneration = latestGeneration
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(20, 100), out)
            out.toByteArray()
        }
    }

    /**
     * 开始抓系统音频（API 29+）。需要 `RECORD_AUDIO` 权限。
     *
     * @param allowedUsages 允许捕获的音频用途；默认媒体/游戏/未分类。
     * @return 成功时是 `status()` 带 `ok=true`；低版本或权限缺失时 `ok=false` + `code=UNSUPPORTED/NO_PERMISSION`。
     */
    fun startAudioCapture(context: Context, allowedUsages: IntArray? = null): JSONObject {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return JSONObject().put("ok", false).put("code", "UNSUPPORTED")
                .put("error", "系统音频捕获需要 Android 10（API 29）及以上。")
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return JSONObject().put("ok", false).put("code", "NO_PERMISSION")
                .put("error", "缺少 RECORD_AUDIO 权限（需要声明并由用户授予）。")
        }
        val activeProjection = projection
            ?: return JSONObject().put("ok", false).put("code", "BAD_STATE")
                .put("error", "还没有投屏授权，无法抓取系统音频。")

        stopAudioInternal()
        val usages = allowedUsages?.takeIf { it.isNotEmpty() }
            ?: intArrayOf(
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_UNKNOWN,
            )
        val captureConfig = runCatching {
            AudioPlaybackCaptureConfiguration.Builder(activeProjection).apply {
                for (usage in usages) addMatchingUsage(usage)
            }.build()
        }.getOrNull()
            ?: return JSONObject().put("ok", false).put("code", "ERROR")
                .put("error", "AudioPlaybackCaptureConfiguration 构建失败。")

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AUDIO_CHANNEL_MASK)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AUDIO_CHANNEL_MASK, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = if (minBuffer > 0) minBuffer * 2 else 8192
        val record = runCatching {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record?.release() }
            return JSONObject().put("ok", false).put("code", "ERROR")
                .put("error", "AudioRecord 初始化失败（可能被其它录音占用，或设备不支持回放捕获）。")
        }

        val file = File(context.cacheDir, "pi-screen-audio-${System.currentTimeMillis()}.pcm")
        val output = runCatching { FileOutputStream(file) }.getOrNull()
        if (output == null) {
            runCatching { record.release() }
            return JSONObject().put("ok", false).put("code", "ERROR").put("error", "无法创建录音输出文件。")
        }

        audioFile = file
        audioBytes = 0L
        audioRecord = record
        audioRecording.set(true)
        runCatching { record.startRecording() }
        audioThread = Thread({
            val buffer = ByteArray(4096)
            try {
                while (audioRecording.get()) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        audioBytes += read
                    } else if (read < 0) {
                        break
                    }
                }
            } catch (_: Throwable) {
                // 录音被停止/释放：正常退出路径。
            } finally {
                runCatching { output.flush() }
                runCatching { output.close() }
            }
        }, "pi-screen-audio").apply {
            isDaemon = true
            start()
        }
        return status().put("ok", true)
    }

    /** 停止系统音频捕获，返回写入的字节数与 PCM 文件路径。 */
    fun stopAudioCapture(): JSONObject {
        val path = audioFile?.absolutePath
        val bytes = audioBytes
        stopAudioInternal()
        return JSONObject().put("bytes", bytes).put("file", path ?: JSONObject.NULL)
    }

    /** 释放投屏资源（虚拟显示、ImageReader、投影、音频）。可重复调用。 */
    fun release(): JSONObject {
        val activeProjection = projection
        val callback = projectionCallback
        projection = null
        projectionCallback = null
        stopAudioInternal()
        synchronized(lock) { detachDisplay() }
        if (activeProjection != null) {
            if (callback != null) runCatching { activeProjection.unregisterCallback(callback) }
            runCatching { activeProjection.stop() }
        }
        return status()
    }

    /** 给 `/app/health` 用的状态；绝不抛异常。 */
    fun status(): JSONObject = JSONObject().apply {
        val capturing = isCapturing()
        val frames: Int
        val lastAt: Long
        synchronized(lock) {
            frames = frameTimes.size
            lastAt = lastFrameAt
        }
        put("component", "screencapture")
        put("capturing", capturing)
        put("width", width)
        put("height", height)
        put("density", density)
        put("maxImages", MAX_IMAGES)
        put("frameCount", frameCount)
        put("fps", if (capturing) (frames * 1000.0 / FPS_WINDOW_MS * 10).roundToInt() / 10.0 else 0.0)
        put("lastFrameAt", if (lastAt == 0L) JSONObject.NULL else lastAt)
        put("fresh", synchronized(lock) { latestGeneration != servedGeneration })
        put("audioSupported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        put(
            "audio",
            JSONObject()
                .put("recording", audioRecording.get())
                .put("bytes", audioBytes)
                .put("file", audioFile?.absolutePath ?: JSONObject.NULL)
                .put("sampleRate", SAMPLE_RATE)
                .put("channels", 2),
        )
        put(
            "note",
            "投屏截图 + 系统音频捕获；Android 14+ 需先有 mediaProjection 类型的前台服务。" +
                "音频捕获仅覆盖系统允许回放捕获的应用（API 29+ 且需 RECORD_AUDIO）。",
        )
    }

    // ------------------------------------------------------------ 帧监听实现 ----

    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        // 永远只取最新帧、立刻 close：maxImages=2 + latest 是低延迟的关键。
        val image = runCatching { reader.acquireLatestImage() }.getOrNull()
        if (image != null) {
            try {
                val bitmap = image.toArgbBitmap()
                if (bitmap != null) {
                    val signature = signatureOf(bitmap)
                    val now = System.currentTimeMillis()
                    synchronized(lock) {
                        frameCount++
                        lastFrameAt = now
                        frameTimes.addLast(now)
                        while (frameTimes.isNotEmpty() && now - frameTimes.first() > FPS_WINDOW_MS) {
                            frameTimes.removeFirst()
                        }
                        if (signature != latestSignature) {
                            latestSignature = signature
                            latestGeneration++
                            latestBitmap?.recycle()
                            latestBitmap = bitmap
                        } else {
                            bitmap.recycle()
                        }
                    }
                }
            } finally {
                runCatching { image.close() }
            }
        }
    }

    private fun Image.toArgbBitmap(): Bitmap? {
        val plane = planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0) return null
        // RGBA_8888 的 buffer 行可能被 padding 加宽：先按加宽宽度建图再裁掉。
        val paddedWidth = width + (rowStride - pixelStride * width) / pixelStride
        val bitmap = Bitmap.createBitmap(paddedWidth.coerceAtLeast(1), height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        bitmap.copyPixelsFromBuffer(plane.buffer)
        if (paddedWidth == width) return bitmap
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
        bitmap.recycle()
        return cropped
    }

    /** 16×16 网格抽样算一个廉价签名：只用于「画面是否变了」，不做像素级比较。 */
    private fun signatureOf(bitmap: Bitmap): Int {
        var hash = 1
        val stepX = max(1, bitmap.width / SAMPLE_GRID)
        val stepY = max(1, bitmap.height / SAMPLE_GRID)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                hash = hash * 31 + bitmap.getPixel(x, y)
                x += stepX
            }
            y += stepY
        }
        return hash
    }

    private fun detachDisplay() {
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        virtualDisplay = null
        imageReader = null
        runCatching { imageThread?.quitSafely() }
        imageThread = null
        latestBitmap?.recycle()
        latestBitmap = null
        latestSignature = 0
        latestGeneration = 0
        servedGeneration = 0
        frameTimes.clear()
        frameCount = 0L
        lastFrameAt = 0L
        width = 0
        height = 0
        density = 0
    }

    // -------------------------------------------------------------- 音频内部 ----

    private fun stopAudioInternal() {
        audioRecording.set(false)
        val record = audioRecord
        audioRecord = null
        if (record != null) {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
        runCatching { audioThread?.join(500) }
        audioThread = null
    }

    private fun failure(message: String): JSONObject =
        JSONObject().put("ok", false).put("code", "ERROR").put("error", message)
}
