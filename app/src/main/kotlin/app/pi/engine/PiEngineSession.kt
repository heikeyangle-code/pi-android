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
 * Four properties this class is responsible for:
 *
 *  1. **Framing.** stdout is decoded and split by [JsonlFramer], never by a line
 *     reader — pi's records may legally contain U+2028/U+2029.
 *  2. **Correlation.** every command carries an id; the matching `response`
 *     completes a [CompletableDeferred]. `success` inside the response is pi's
 *     verdict and is what callers must check.
 *  3. **Degradation.** an unrecognised event or a newer pi must never blank the
 *     transcript; it surfaces as [PiEvent.Unknown] and is published like any
 *     other event.
 *  4. **Publication.** every mutation of the reducer — the event path in [handle]
 *     and the calls this class makes on the caller's behalf ([prompt],
 *     [echoUserPrompt], [seedHistory]) — goes through [publish], which hands the
 *     UI the rows that actually changed ([TranscriptPublication]). Rebuilding the
 *     whole row list per event is what made a long streaming session progressively
 *     laggier (docs/rendering-review.md F7 / RR-P7).
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
    /**
     * Bumped whenever anything observable about the transcript moved, so Compose
     * can observe it.
     *
     * It moves for **every** non-`response` event, including one whose
     * [TranscriptChange] is [TranscriptChange.None] (`handle`), because a `None`
     * change says "no row changed", not "nothing changed": `agent_start` sets
     * `TranscriptReducer.streaming` and returns `None` (rpc/Transcript.kt:702-705),
     * and a `message_update` carrying only `usage` does the same
     * (rpc/Transcript.kt:859-863). The UI's composer reads that flag, so a
     * revision that moved only for row changes would leave it stale.
     *
     * [publication] is the authoritative form of the same event: it carries this
     * same number **plus** the change. This flow is kept because it is the
     * contract callers already collect (`ui/PiSessionViewModel.kt:672`), and
     * [publish] always writes [publication] first, so `publication.value.revision`
     * is never smaller than what a collector of this flow just observed.
     */
    val revision: StateFlow<Int> = _changes.asStateFlow()

    private val _publication = MutableStateFlow(
        TranscriptPublication(
            revision = 0,
            change = TranscriptChange.None,
            rows = emptyList(),
            changedIndices = emptyList(),
            replaced = true,
            streaming = false,
        ),
    )

    /**
     * The last reducer effect, with the rows it changed. See [publish] for the
     * protocol a consumer has to follow.
     */
    val publication: StateFlow<TranscriptPublication> = _publication.asStateFlow()

    /**
     * The immutable snapshot handed out by the previous [publish].
     *
     * Kept because [changedIndices] is an identity diff against it: the reducer's
     * own `TranscriptChange` reports a single index even when an event rewrote
     * several rows (see [TranscriptPublication]).
     */
    private var publishedRows: List<TranscriptItem> = emptyList()

    val stderr = StringBuilder()

    private var readerJob: Job? = null
    private var stderrJob: Job? = null
    private var waitJob: Job? = null

    enum class EngineState { Starting, Ready, Busy, Stopped, Failed }

    /**
     * One reducer effect, published atomically with the revision it belongs to.
     *
     * ## Why this exists
     *
     * The reducer already computes `TranscriptChange.Appended(index)` /
     * `Updated(index)` so "the UI can update one row instead of recomposing the
     * list" (`rpc/Transcript.kt:546-562`), but `PiEngineSession` used to throw that
     * index away and only bump a counter — so the only thing the app could do on
     * each of pi's streamed deltas was copy the whole list into a fresh `UiState`
     * and recompose the entire screen (docs/rendering-review.md F7 / RR-P7).
     *
     * ## Why [change] alone is not enough
     *
     * `TranscriptChange` reports **one** index per event and several reducer paths
     * deliberately report only the last row they touched:
     *
     *  - `finishStreaming` flips every still-streaming row and returns the last one
     *    it touched (`rpc/Transcript.kt:1748-1773`), reached from `message_end`,
     *    `agent_end` and `agent_settled` (`:678-707`);
     *  - `failTurn` rewrites every pending tool card and returns the last
     *    (`rpc/Transcript.kt:1181-1227`);
     *  - a `tool_execution_end` that also appends a `ToolDiff` reports the appended
     *    index while the tool row changed too — the `TranscriptChange` KDoc says so
     *    (`rpc/Transcript.kt:553-555`, `:1032-1095`).
     *
     * A consumer that trusted `change` alone would leave a stopped turn's tool
     * cards showing "运行中" and drop the error row `failTurn` appends. So
     * [changedIndices] is the engine's own identity diff of the rows against the
     * previous publication, and [replaced] says the list was rebuilt wholesale
     * instead (`seedHistory`: `seedFromHistory` calls `reset()` and still answers
     * `Appended`, `rpc/Transcript.kt:1365-1376`).
     *
     * ## Consumer protocol
     *
     *  - a consumer that has never seen a publication adopts [rows] as its list;
     *  - when [replaced], adopt [rows] and ignore [changedIndices];
     *  - otherwise re-read exactly [changedIndices]: rows below the consumer's own
     *    length are replacements, rows at or past it are appends, in ascending
     *    order (the reducer only ever appends at the tail, so an index is valid in
     *    both the reducer's list and a consumer that is behind it);
     *  - if a consumer's last seen revision is not `revision - 1`, it missed
     *    publications (a `StateFlow` conflates, and the engine also publishes from
     *    its own reader thread), and the only safe move is to adopt [rows] again.
     *
     * [change] is kept because it is the reducer's own word and it is cheap to act
     * on for callers that only need to know the kind of movement.
     */
    data class TranscriptPublication(
        /** Same number as [revision]. */
        val revision: Int,
        /** The reducer's own verdict for the event or call that produced this. */
        val change: TranscriptChange,
        /** The whole row list as of this revision; immutable and safe to hand around. */
        val rows: List<TranscriptItem>,
        /** Ascending row indices whose element identity changed; empty when [replaced]. */
        val changedIndices: List<Int>,
        /** True when [rows] must be adopted wholesale; see the class KDoc. */
        val replaced: Boolean,
        /** `TranscriptReducer.streaming` as of this revision. */
        val streaming: Boolean,
    )

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
        // The transcript is projected here, exactly once, for every event —
        // including `entry_appended`, whose payload really is on the wire
        // (`agent-session.ts:2620` emits `{type:"entry_appended", entry}`, which
        // `modes/json-event.ts` passes through and `TranscriptReducer.onEvent`
        // projects at `rpc/Transcript.kt:768`). A caller must NOT call
        // `transcript.onEntry(entry)` again: it would double the row. Reads of the
        // reducer from outside this class are fine; *writes* are not — they must go
        // through [publish] (via [prompt], [echoUserPrompt], [seedHistory]) or the
        // change index and the row diff are never published (fidelity/rendering
        // review F5).
        val change: TranscriptChange = transcript.onEvent(event)
        // A `response` is pi answering a command; it never projects a row
        // (`TranscriptReducer.onEvent`'s `else -> None`), so it must not wake the
        // UI. Every other event does, even when the change is `None`, because the
        // reducer also moves `streaming` / `thinkingLevel` / `lastUsage`.
        if (change != TranscriptChange.None || event !is PiEvent.Response) {
            publish(change)
        }
        _events.tryEmit(event)
    }

    // ------------------------------------------------------------- publication

    /**
     * Publish one reducer effect: [change], the rows it produced, and the exact
     * rows that moved. The single writer of [revision] and [publication].
     *
     * Why the engine diffs rows itself instead of trusting `change`: see
     * [TranscriptPublication]. The cost is one identity scan plus one reference
     * copy per *row-changing* publication, which is strictly less work than the app
     * used to do per revision — and, because the snapshot is built on the reader
     * thread, the UI stops reading the reducer's mutable list across threads.
     *
     * Ordering is load-bearing: [publication] is written before [revision], so a
     * collector of either sees a revision that [publication] already describes.
     *
     * @param replaced the caller knows the whole list was rebuilt (`seedHistory`).
     */
    private fun publish(change: TranscriptChange, replaced: Boolean = false) {
        val current = transcript.transcript
        val previous = publishedRows
        // A shrink can only be `TranscriptReducer.reset()` (rpc/Transcript.kt:1798),
        // and an empty `previous` is a consumer's first sight of the list: neither
        // can be described by row indices, so both are a wholesale handoff.
        val rolled = replaced || previous.isEmpty() || current.size < previous.size
        val rows = when {
            rolled -> current.toList()
            // `None` means no row was touched — every mutating path in
            // `TranscriptReducer` returns a non-`None` change (checked path by
            // path: `finishStreaming` answers `None` only when it touched nothing,
            // rpc/Transcript.kt:1772) — so the previous snapshot is still current
            // and does not need copying. If a future reducer ever breaks that, the
            // next publication's diff still picks the row up, because the diff is
            // against this same snapshot rather than against `change`.
            change == TranscriptChange.None && current.size == previous.size -> previous
            else -> current.toList()
        }
        val indices = if (rolled) emptyList() else diffIndices(previous, rows)
        publishedRows = rows
        val next = _changes.value + 1
        _publication.value = TranscriptPublication(
            revision = next,
            change = change,
            rows = rows,
            changedIndices = indices,
            replaced = rolled,
            streaming = transcript.streaming,
        )
        _changes.value = next
    }

    /**
     * Row indices whose element identity differs between two lists, plus every row
     * the new list added at the tail.
     *
     * `!==` rather than `!=`: reducer items are immutable data classes and every
     * mutation path builds a new instance (`items[i] = current.copy(...)`), so an
     * unchanged row is the *same object*, while `!=` would also compare a large
     * tool output string for equality.
     */
    private fun diffIndices(
        previous: List<TranscriptItem>,
        rows: List<TranscriptItem>,
    ): List<Int> {
        val shared = minOf(previous.size, rows.size)
        val changed = mutableListOf<Int>()
        for (i in 0 until shared) {
            if (previous[i] !== rows[i]) changed += i
        }
        for (i in shared until rows.size) changed += i
        return changed
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

    /**
     * Send a command built from a fresh correlation id, and await its `response`.
     *
     * The id belongs to this class, not to callers, but a command builder needs
     * one to put in the JSON it returns. Handing the id to the builder keeps both
     * true: [PiEngineApi] writes `PiCommands.getState(id)` and this class keeps
     * allocating and matching ids.
     */
    suspend fun request(build: (String) -> JsonObject, timeoutMs: Long = 120_000): PiEvent.Response =
        request(build(nextId()), timeoutMs)

    // ------------------------------------------------------- typed operations

    /**
     * Send a prompt and mirror it into the local transcript before the wire call.
     *
     * The echo is local because pi's own TUI adds the user's row when
     * `message_start` arrives with `role === "user"`
     * (`modes/interactive/interactive-mode.ts:3223-3227`), and the reducer here
     * appends nothing for a `user` message: it only clears the content-block maps
     * for `assistant` (`rpc/Transcript.kt:669-675`). Without the echo the bubble the
     * user just sent would not exist until the session was reopened and replayed
     * (docs/rendering-review.md F1).
     */
    fun prompt(
        message: String,
        images: List<PiImage> = emptyList(),
        streamingBehavior: app.pi.rpc.StreamingBehavior? = null,
    ) {
        echoUserPrompt(message, images)
        send(PiCommands.prompt(nextId(), message, images, streamingBehavior))
    }

    /**
     * Mirror a message the user sent or queued into the transcript, and publish it.
     *
     * This is the publishing form of `TranscriptReducer.onUserPrompt`, and it exists
     * because the callers that queue text mid-turn do not go through [prompt]: pi's
     * `steer` and `follow_up` are fire-and-forget and carry no echo of their own
     * (pi's TUI only discovers a queued message when pi delivers it), so the app
     * has to add the row itself at the moment it queues the text (F1). Calling
     * `transcript.onUserPrompt` directly still creates the row, but the change is
     * never published — no revision, no [publication] — and a caller that then
     * wants the UI to show it has to rebuild the list by hand.
     */
    fun echoUserPrompt(text: String, images: List<PiImage> = emptyList()) {
        publish(transcript.onUserPrompt(text, images))
    }

    /**
     * Rebuild the whole stream from pi's persisted entries (`get_entries`).
     *
     * `TranscriptReducer.seedFromHistory` resets the reducer first and then still
     * answers `TranscriptChange.Appended(lastIndex)` (`rpc/Transcript.kt:1365-1376`),
     * which describes one appended row — so the publication is marked `replaced` and
     * the caller must adopt [TranscriptPublication.rows] wholesale instead of
     * inserting at that index.
     */
    fun seedHistory(entries: List<JsonObject>) {
        publish(transcript.seedFromHistory(entries), replaced = true)
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
