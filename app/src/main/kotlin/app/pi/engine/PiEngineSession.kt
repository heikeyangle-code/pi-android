package app.pi.engine

import app.pi.rpc.JsonlFramer
import app.pi.rpc.PiCommands
import app.pi.rpc.PiEvent
import app.pi.rpc.PiEvents
import app.pi.rpc.PiImage
import app.pi.rpc.TranscriptChange
import app.pi.rpc.TranscriptReducer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * A conversation with one pi engine process.
 *
 * pi runs as `pi --mode rpc`, speaking LF-delimited JSONL over stdin/stdout. The
 * process is a child of the foreground service, not of an Activity, so a turn
 * keeps running when the user leaves the screen
 * (docs/pi-android-app-design.md §7.1).
 *
 * Three properties this class is responsible for:
 *
 *  1. **Framing.** stdout is decoded and split by [JsonlFramer], never by a line
 *     reader — pi's records may legally contain U+2028/U+2029.
 *  2. **Correlation.** every command carries an id; the matching `response`
 *     completes a [CompletableDeferred]. `success` inside the response is pi's
 *     verdict and is what callers must check.
 *  3. **Degradation.** an unrecognised event or a newer pi must never blank the
 *     transcript; it surfaces as [PiEvent.Unknown] and is published like any
 *     other event.
 *
 * This class knows nothing about Android or proot: it is handed already-built
 * argv/env, which is what makes it testable against a plain `node` process.
 */
class PiEngineSession(
    private val process: Process,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    val transcript: TranscriptReducer = TranscriptReducer(),
) {

    private val ids = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<PiEvent.Response>>()
    private val writer: BufferedWriter =
        BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))

    private val _events = MutableSharedFlow<PiEvent>(extraBufferCapacity = 256)
    /** Every event, in order, for screens that need more than the transcript. */
    val events: SharedFlow<PiEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(EngineState.Starting)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _changes = MutableStateFlow(0)
    /** Bumped whenever the transcript changes, so Compose can observe it. */
    val revision: StateFlow<Int> = _changes.asStateFlow()

    val stderr = StringBuilder()

    private var readerJob: Job? = null
    private var stderrJob: Job? = null
    private var waitJob: Job? = null

    enum class EngineState { Starting, Ready, Busy, Stopped, Failed }

    private fun nextId(): String = "req-${ids.incrementAndGet()}"

    fun start() {
        readerJob = scope.launch { readLoop(process.inputStream) }
        // stderr MUST be drained. pi and Node write warnings there; if nobody
        // reads the pipe, the ~64 KB kernel buffer fills and the engine blocks
        // forever mid-turn — a failure that looks like "the model hung".
        stderrJob = scope.launch(Dispatchers.IO) {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(stderr) {
                        if (stderr.length < MAX_STDERR_CHARS) stderr.appendLine(line)
                    }
                }
            }
        }
        waitJob = scope.launch {
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            _state.value = if (code == 0) EngineState.Stopped else EngineState.Failed
            // Fail every in-flight request rather than letting callers hang.
            pending.values.forEach { it.complete(failure("engine exited with code $code")) }
            pending.clear()
        }
    }

    private suspend fun readLoop(input: InputStream) {
        val framer = JsonlFramer()
        val buffer = ByteArray(16 * 1024)
        withContext(Dispatchers.IO) {
            while (true) {
                val read = try {
                    input.read(buffer)
                } catch (_: Throwable) {
                    -1
                }
                if (read < 0) break
                val records = framer.feed(String(buffer, 0, read, Charsets.UTF_8))
                for (record in records) handle(record)
            }
        }
        for (record in framer.flush()) handle(record)
    }

    private fun handle(record: String) {
        val event = PiEvents.parse(record)
        if (event is PiEvent.Response) {
            pending.remove(event.id)?.complete(event)
        }
        when (event) {
            is PiEvent.AgentStart -> _state.value = EngineState.Busy
            is PiEvent.AgentSettled, is PiEvent.AgentEnd -> _state.value = EngineState.Ready
            else -> Unit
        }
        val change: TranscriptChange = transcript.onEvent(event)
        if (event !is PiEvent.Response || change != TranscriptChange.None) {
            _changes.value = _changes.value + 1
        }
        _events.tryEmit(event)
    }

    // --------------------------------------------------------------- sending

    /** Fire-and-forget command; use [request] when the reply matters. */
    fun send(command: JsonObject) {
        synchronized(writer) {
            writer.write(PiCommands.encode(command))
            writer.flush()
        }
    }

    /** Send and await pi's `response`. */
    suspend fun request(command: JsonObject, timeoutMs: Long = 120_000): PiEvent.Response {
        val id = command["id"]?.toString()?.trim('"') ?: nextId()
        val deferred = CompletableDeferred<PiEvent.Response>()
        pending[id] = deferred
        send(command)
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            pending.remove(id)
            failure("timed out after ${timeoutMs}ms waiting for $id")
        }
    }

    // ------------------------------------------------------- typed operations

    fun prompt(
        message: String,
        images: List<PiImage> = emptyList(),
        streamingBehavior: app.pi.rpc.StreamingBehavior? = null,
    ) {
        transcript.onUserPrompt(message, images)
        _changes.value = _changes.value + 1
        send(PiCommands.prompt(nextId(), message, images, streamingBehavior))
    }

    /**
     * pi's Escape: clear the queue, then abort — and the queue contents come
     * back to the caller so the composer can restore them
     * (docs/pi-android-ui-spec.md §7.2).
     */
    suspend fun stopAndDrainQueue(): List<String> {
        val state = request(PiCommands.getState(nextId()), timeoutMs = 15_000)
        val steering = queuedText(state, "steering")
        val followUp = queuedText(state, "followUp")
        request(PiCommands.clearQueue(nextId()))
        request(PiCommands.abort(nextId()))
        return steering + followUp
    }

    private fun queuedText(response: PiEvent.Response, key: String): List<String> {
        val data = response.data as? JsonObject ?: return emptyList()
        val array = data[key] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }

    fun close() {
        runCatching { writer.close() }
        runCatching { process.destroy() }
        readerJob?.cancel()
        stderrJob?.cancel()
        waitJob?.cancel()
        _state.value = EngineState.Stopped
    }

    private fun failure(message: String): PiEvent.Response =
        PiEvent.Response(id = null, command = null, success = false, error = message, data = null)

    companion object {
        /** Keep diagnostics bounded; the settings screen shows the tail. */
        private const val MAX_STDERR_CHARS = 64 * 1024

        /**
         * Start an engine described by a command line that already includes
         * proot. Kept as a factory so production code passes the proot argv and
         * tests can pass a bare `node`.
         */
        fun spawn(
            argv: List<String>,
            env: Map<String, String>,
            cwd: File,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ): PiEngineSession {
            val builder = ProcessBuilder(argv)
                .directory(cwd)
                .redirectErrorStream(false)
            builder.environment().putAll(env)
            val process = builder.start()
            return PiEngineSession(process, scope).also { it.start() }
        }
    }
}
