package app.pi.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 客户端往外给 UI 层的信号：一条（已过 epoch 过滤的）事件，或「请对账」。 */
sealed interface HostSignal {
    data class Event(val seq: Long, val epoch: Long, val name: String, val data: JsonElement?) : HostSignal

    /** 丢了帧（seq 跳号）或缓冲溢出：调 `session.snapshot` 重新拿完整状态，别再信增量。 */
    data class Resync(val reason: String) : HostSignal
}

/** 宿主的提问，等 [PiHostClient.respond]。 */
data class HostRequest(val id: String, val method: String, val params: JsonObject)

/**
 * 线协议 v1 的客户端（docs/sdk-host/protocol.md）。纯 Kotlin：不碰 Android，也不碰进程，
 * 字节怎么进出由调用方注入，所以能在 bare-JVM harness 里对着录制的真实帧回放。
 *
 * 三条规则 App 必须守，这里替 UI 层守了：
 *  1. **epoch**：丢弃 `epoch < 当前` 的迟到事件，否则上一个会话的尾巴会画进新会话。
 *  2. **seq**：看到跳号就发 [HostSignal.Resync]，由上层去 `session.snapshot`。
 *  3. **配对**：每条命令恰好一个应答；超时、取消、进程退出都要把 pending 清掉，不留悬挂。
 *
 * 线程：[onLine] 由读线程调；[call] 可从任意协程调。内部不阻塞读线程。
 *
 * **先订阅、后开读**：[signals] / [requests] 没有 replay，没有订阅者时发出的信号直接丢。
 * 调用方必须先 collect，再让读线程开始喂 [onLine]（`host.ready` 是第一条事件，别错过）。
 */
class PiHostClient(
    /** 写一行（不含换行；由调用方补 `\n`）。可能抛 [IOException]。调用已被本类串行化。 */
    private val sendLine: (String) -> Unit,
    private val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    eventBuffer: Int = 4096,
) {
    private val nextId = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement?>>()
    private val writeLock = Any()

    @Volatile private var closedCause: HostException? = null

    private val _signals = MutableSharedFlow<HostSignal>(extraBufferCapacity = eventBuffer, onBufferOverflow = BufferOverflow.SUSPEND)
    val signals: SharedFlow<HostSignal> = _signals.asSharedFlow()

    private val _requests = MutableSharedFlow<HostRequest>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)
    val requests: SharedFlow<HostRequest> = _requests.asSharedFlow()

    private val _epoch = MutableStateFlow(0L)
    /** 当前会话代号。`session.bound` 事件与更大的 epoch 都会把它推上去。 */
    val epoch: StateFlow<Long> = _epoch.asStateFlow()

    // 只在读线程里动（onLine 单线程），不需要锁。
    private var lastSeq = 0L
    private var overflowed = false

    /** 被 epoch 规则丢掉的事件数（诊断用）。 */
    @Volatile var staleDropped: Long = 0L
        private set

    /** 无法解析、被忽略的行数（诊断用）。 */
    @Volatile var ignoredLines: Long = 0L
        private set

    /** 喂一行。读线程调用，不阻塞。 */
    fun onLine(line: String) {
        when (val frame = HostFrame.parse(line)) {
            null -> ignoredLines++
            is HostFrame.Res -> {
                val waiter = pending.remove(frame.id) ?: return // 超时/取消后才到的应答：丢
                val error = frame.error
                if (frame.ok || error == null) waiter.complete(frame.data)
                else waiter.completeExceptionally(HostException(error))
            }
            is HostFrame.Req -> {
                if (!_requests.tryEmit(HostRequest(frame.id, frame.method, frame.params))) {
                    // 提问不能悄悄丢：丢了宿主会一直等。让对账路径知道。
                    markOverflow("requests buffer full")
                }
            }
            is HostFrame.Evt -> onEvent(frame)
        }
    }

    private fun onEvent(e: HostFrame.Evt) {
        // 1. seq：在 epoch 过滤**之前**，丢掉的旧 epoch 事件也占序号。
        val expected = lastSeq + 1
        if (e.seq > expected && lastSeq > 0) emit(HostSignal.Resync("seq gap: $lastSeq -> ${e.seq}"))
        if (e.seq <= lastSeq) return // 重复/回退：忽略
        lastSeq = e.seq

        // 2. epoch
        val current = _epoch.value
        if (e.epoch < current) {
            staleDropped++
            return
        }
        if (e.epoch > current) _epoch.value = e.epoch
        emit(HostSignal.Event(e.seq, e.epoch, e.name, e.data))
    }

    private fun emit(signal: HostSignal) {
        if (overflowed && signal !is HostSignal.Resync) {
            // 上次溢出过：先补一条对账信号，再发这条。
            if (_signals.tryEmit(HostSignal.Resync("event buffer overflowed"))) overflowed = false
        }
        if (!_signals.tryEmit(signal)) overflowed = true
    }

    private fun markOverflow(reason: String) {
        overflowed = true
        _signals.tryEmit(HostSignal.Resync(reason)).also { if (it) overflowed = false }
    }

    /**
     * 发一条命令，等应答。
     *
     * @throws HostException `code` 是协议错误码，或 `timeout` / `closed` / `io`。
     */
    suspend fun call(cmd: String, args: JsonObject = EMPTY, timeoutMs: Long? = null): JsonElement? {
        closedCause?.let { throw it }
        val id = "c${nextId.incrementAndGet()}"
        val waiter = CompletableDeferred<JsonElement?>()
        pending[id] = waiter
        try {
            val line = JsonObject(mapOf("id" to JsonPrimitive(id), "cmd" to JsonPrimitive(cmd), "args" to args)).toString()
            try {
                synchronized(writeLock) { sendLine(line) }
            } catch (e: IOException) {
                throw HostException(HostException.IO, e.message ?: "写入宿主失败")
            }
            // 写完才发现已被关闭：failAll 可能赶在 pending 登记之前跑过，补一刀。
            closedCause?.let { throw it }
            val limit = timeoutMs ?: defaultTimeoutMs
            return if (limit <= 0) {
                waiter.await()
            } else {
                try {
                    withTimeout(limit) { waiter.await() }
                } catch (e: TimeoutCancellationException) {
                    throw HostException(HostException.TIMEOUT, "命令 $cmd 超过 ${limit}ms 没有应答")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            pending.remove(id)
        }
    }

    /** 命令返回对象的便捷形式；应答不是对象（或为空）给空对象。 */
    suspend fun callObject(cmd: String, args: JsonObject = EMPTY, timeoutMs: Long? = null): JsonObject =
        call(cmd, args, timeoutMs) as? JsonObject ?: EMPTY

    /** 回答宿主的提问。`cancelled=true` 表示用户取消。迟到的回答宿主会说 `matched:false`，不是错误。 */
    suspend fun respond(requestId: String, value: JsonElement? = null, cancelled: Boolean = false) {
        val args = buildMap<String, JsonElement> {
            put("id", JsonPrimitive(requestId))
            if (cancelled) put("cancelled", JsonPrimitive(true)) else if (value != null) put("value", value)
        }
        call("ui.respond", JsonObject(args))
    }

    /** 进程没了 / 管道断了：所有在等的命令立刻失败，之后的 [call] 直接抛。 */
    fun close(reason: String = "宿主已退出") {
        val cause = HostException(HostException.CLOSED, reason)
        closedCause = cause
        for (id in pending.keys.toList()) pending.remove(id)?.completeExceptionally(cause)
    }

    val isClosed: Boolean get() = closedCause != null

    /** 当前还在等应答的命令数（测试与诊断用）。 */
    val pendingCount: Int get() = pending.size

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        private val EMPTY = JsonObject(emptyMap())
    }
}
