package app.pi.ui.extension

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin

/**
 * 扩展终端提示音（对应终端 BEL `\x07` / OSC 通知触发的 `ui.bell` 事件）。
 *
 * 使用低延迟短正弦波提示音（65ms、880Hz、带淡入淡出包络）通过 `USAGE_GAME` / `CONTENT_TYPE_SONIFICATION`
 * 播放：
 *  - 跟随系统媒体音量，支持耳机外放，且无需任何音频权限；
 *  - 120ms 节流 + 非堆积并发保护，防止高频 BEL 轰炸导致连续数秒排队响铃；
 *  - 全局共享静音开关 [muted]，覆盖自定义浮层与交互式小组件面板；
 *  - 全部操作在单线程后台执行并包裹 `runCatching`，硬件不可用或处于无声环境时静默降级、绝不阻塞主线程或崩溃。
 */
internal object ExtensionSound {
    private const val SAMPLE_RATE = 24_000
    private const val DURATION_MS = 65
    private const val FREQ_HZ = 880.0
    private const val AMPLITUDE = 0.28
    private const val MIN_INTERVAL_MS = 120L

    var muted: Boolean by mutableStateOf(false)

    private val lastPlayedMs = AtomicLong(0L)
    private val playing = AtomicBoolean(false)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pi-ext-bell").apply { isDaemon = true }
    }

    private val bellPcm: ShortArray by lazy {
        val totalSamples = (SAMPLE_RATE * DURATION_MS) / 1000
        val attackSamples = (SAMPLE_RATE * 6) / 1000
        val releaseSamples = (SAMPLE_RATE * 24) / 1000
        ShortArray(totalSamples) { i ->
            val env = when {
                i < attackSamples -> i.toDouble() / attackSamples.coerceAtLeast(1)
                i > totalSamples - releaseSamples -> ((totalSamples - i).toDouble() / releaseSamples.coerceAtLeast(1)).coerceAtLeast(0.0)
                else -> 1.0
            }
            val wave = sin(2.0 * PI * FREQ_HZ * i / SAMPLE_RATE) * 0.8 +
                sin(2.0 * PI * (FREQ_HZ * 1.5) * i / SAMPLE_RATE) * 0.2
            (wave * env * AMPLITUDE * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    fun toggleMuted() {
        muted = !muted
    }

    fun playBell() {
        if (muted) return
        val now = System.currentTimeMillis()
        val prev = lastPlayedMs.get()
        if (now - prev < MIN_INTERVAL_MS) return
        if (!lastPlayedMs.compareAndSet(prev, now)) return
        if (!playing.compareAndSet(false, true)) return
        runCatching {
            executor.execute {
                try {
                    if (!muted) runCatching { playSync() }
                } finally {
                    playing.set(false)
                }
            }
        }.onFailure {
            playing.set(false)
        }
    }

    private fun playSync() {
        val pcm = bellPcm
        val byteLen = pcm.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(byteLen)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(DURATION_MS.toLong() + 25L)
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }
}
