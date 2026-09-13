package app.pi.engine

import app.pi.rpc.JsonlFramer
import app.pi.rpc.PiCommands
import app.pi.rpc.PiEvent
import app.pi.rpc.PiEvents
import app.pi.rpc.PiImage
import app.pi.rpc.PiResponses
import app.pi.rpc.TranscriptChange
import app.pi.rpc.TranscriptItem
import app.pi.rpc.TranscriptReducer
import app.pi.rpc.Utf8StreamDecoder
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
 * Five properties this class is responsible for:
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
 *  5. **Ownership.** the reducer and the published snapshot have one owner at a
 *     time, guarded by [transcriptLock]: the reader thread folds events under it
 *     ([handle]), the app's mutations do too ([prompt], [echoUserPrompt]), and
 *     [seedHistory] swaps in a reducer built off the frame thread. Nothing outside
 *     that lock touches the reducer's mutable row list — including the UI, which
 *     reads [TranscriptPublication.rows], a snapshot.
 *
 * This class knows nothing about Android or proot: it is handed already-built
 * argv/env, which is what makes it testable against a plain `node` process.
 */
class PiEngineSession(
    private val process: Process,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    initialTranscript: TranscriptReducer = TranscriptReducer(),
) {

    /**
     * The one lock that owns the reducer **and** the published snapshot.
     *
     * Why it exists: this class folds events into the reducer on the reader thread
     * ([handle], inside [readLoop]'s IO context) while the app calls [prompt],
     * [echoUserPrompt] and [seedHistory] from its own threads — the ViewModel's are
     * `Dispatchers.Main.immediate`. `TranscriptReducer` is a plain state machine
     * over an unsynchronized `MutableList` with no lock of its own, and neither
     * [publishedRows] nor [publish]'s read-modify-write of [revision] was atomic,
     * so two writers could interleave a reset with an append, or two publications
     * could read the same previous snapshot and emit overlapping indices.
     * Everything that touches the reducer or the publication now happens under this
     * monitor.
     *
     * A monitor rather than a coroutine mutex on purpose: [handle] is not a suspend
     * function and must not become one just to take a lock, and every critical
     * section here is short (one event fold plus one identity scan) with no
     * suspension point inside. [publish] is the longest — O(rows) — and it never
     * blocks on I/O.
     */
    private val transcriptLock = Any()

    /**
     * The reducer currently serving this session.
     *
     * Not a `val` any more: [seedHistory] builds a fresh reducer off the frame
     * thread and swaps it in under [transcriptLock], which keeps the expensive
     * projection (a `TranscriptItem` per block, day separators, `summarizeToolArgs`,
     * `parseUnifiedDiff`) out of the frame thread without making the reducer itself
     * concurrent. The new instance is built on one thread and seen by the reader
     * thread only after the swap, so no lock is needed *while* it is being built.
     *
     * `@Volatile` so another thread sees the swap. The reducer's own fields are
     * **not** volatile, so this reference suits reads that tolerate being one
     * publication behind; a read that must be consistent should use
     * [TranscriptPublication.rows] from [publication].
     */
    @Volatile
    private var reducer: TranscriptReducer = initialTranscript

    /**
     * Handed out per [seedHistory] call, in call order, and the highest value that
     * has already taken over (the latter guarded by [transcriptLock]).
     *
     * Making the projection asynchronous introduced an ordering hazard that the
     * synchronous seed did not have: two replays can now finish their projections
     * out of order (a 2000-entry session projects slower than an empty one), so a
     * replay issued *earlier* could take over *after* a later one and leave the
     * previous session's rows on screen while pi is already in the new session.
     * pi answers `get_entries` in request order, and [seedHistory] is called right
     * after its own response, so call order **is** response order: adopting only a
     * strictly newer attempt keeps the take-over monotone.
     */
    private val seedAttempts = AtomicLong(0)
    private var adoptedSeedAttempt = 0L

    /**
     * The reducer currently serving this session. **Reads only.**
     *
     * Mutating through this reference is exactly what [transcriptLock] exists to
     * stop: a direct `onEvent` / `onUserPrompt` / `seedFromHistory` call would race
     * the reader thread, and the change would never be published. The publishing
     * paths are [handle] (reader thread), [prompt], [echoUserPrompt] and
     * [seedHistory].
     */
    val transcript: TranscriptReducer get() = reducer

    private val ids = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<PiEvent.Response>>()
    private val writer: BufferedWriter =
        BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))

    /**
     * The one thread that ever touches [writer] — because the alternative is the
     * frame thread touching a pipe.
     *
     * `send` is called from `PiSessionViewModel` on `Dispatchers.Main.immediate`
     * (`prompt`, `sendFollowUp`, `runPromptCommand`, `send`), and a pipe write is
     * **not** a bounded operation: it blocks once the kernel's ~64 KB buffer is full,
     * i.e. exactly while pi is not reading its stdin. pi does not read stdin until its
     * startup is over (that gap is the reason [probeServing] exists at all), and it
     * also stops draining while it is busy parsing a huge record. So a large command —
     * an attachment travels inline as base64, several megabytes of it — written from
     * the frame thread is a main-thread block, and on a phone with pi's cold start
     * (~20 s under proot, docs/startup-latency.md) that is long enough to be an ANR.
     *
     * One thread preserves the ordering pi's protocol depends on (commands carry ids
     * and pi answers in order), and it moves *both* halves out of the caller:
     * `PiCommands.encode` builds the whole JSON line, which for an attachment is the
     * multi-megabyte string itself.
     */
    private val writeQueue: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pi-stdin").apply { isDaemon = true }
    }

    /** Set once a write has failed; every later [send] answers false without trying. */
    private val writeBroken = AtomicBoolean(false)

    private val _events = MutableSharedFlow<PiEvent>(extraBufferCapacity = 256)
    /** Every event, in order, for screens that need more than the transcript. */
    val events: SharedFlow<PiEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(EngineState.Starting)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _changes = MutableStateFlow(0)
    /**
     * Bumped on every publication, for a consumer that wants nothing but a wake-up.
     *
     * It moves for **every** event that is published, including one whose
     * [TranscriptChange] is [TranscriptChange.None] (`handle`), because a `None`
     * change says "no row changed", not "nothing changed": the `PiEvent.AgentStart`
     * arm of `TranscriptReducer.onEvent` sets `TranscriptReducer.streaming` and
     * returns `None`, and the same holds for `TranscriptReducer.onMessageUpdate`
     * when an update carries only `usage`. The UI's composer reads that flag, so a
     * revision that moved only for row changes would leave it stale. Exactly two
     * kinds of event publish nothing at all: a `response` (pi answering a command,
     * which never projects a row) and F8's throttled `tool_execution_update`
     * (`handle`), so this flow reports no gap for either.
     *
     * **Nothing collects this flow.** Its only consumer, the chat ViewModel, now
     * collects [publication] (its `engine.publication.collect` plus the
     * `syncTranscript(engine, pub)` it calls) — because a bare number cannot say
     * which rows moved, which is the whole point of F7/RR-P7 — and a search over
     * `app/` and `rpc/` finds no other reader of `revision`. It is kept because it is
     * still a valid coarse signal and removing it would be an API change, not a
     * comment change; a new consumer should use [publication]. [publish] always writes
     * [publication] first, so `publication.value.revision` is never smaller than a
     * `revision` a collector of this flow has just observed.
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

    /**
     * What the process exited with, or null while it is running / was never reaped.
     *
     * ## Why it is kept rather than only used
     *
     * [waitJob] needs the number to decide `Stopped` vs `Failed`, and the pending
     * requests get it inside a sentence. Neither of those is readable afterwards:
     * the failing request is consumed by a caller that has already moved on, and
     * the ViewModel drops its reference to this session the moment it publishes
     * `Boot.Failed`. That left the diagnostic report saying the exit code was
     * "not recorded" — for the one failure the whole report exists to explain
     * (`docs/engine-exit-review.md` §0.4).
     *
     * `@Volatile` because [waitJob] writes it on the host's IO scope while the UI
     * thread may read it from the state collector in the same moment the state
     * flips to `Failed`.
     */
    @Volatile
    var lastExitCode: Int? = null
        private set

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
     * list" (the `TranscriptChange` KDoc), but `PiEngineSession` used to throw that
     * index away and only bump a counter — so the only thing the app could do on
     * each of pi's streamed deltas was copy the whole list into a fresh `UiState`
     * and recompose the entire screen (docs/rendering-review.md F7 / RR-P7).
     *
     * ## Why [change] alone is not enough
     *
     * `TranscriptChange` reports **one** index per event and several reducer paths
     * deliberately report only the last row they touched:
     *
     *  - `TranscriptReducer.finishStreaming` flips every still-streaming row and
     *    returns the last one it touched, and the `PiEvent.MessageEnd`,
     *    `PiEvent.AgentEnd` and `PiEvent.AgentSettled` arms of
     *    `TranscriptReducer.onEvent` all reach it;
     *  - `TranscriptReducer.failTurn` rewrites every pending tool card and returns
     *    the last one;
     *  - a `tool_execution_end` that also appends a `ToolDiff` reports the appended
     *    index while the tool row changed too — the `TranscriptChange` KDoc says so
     *    ("one event reports one index"), and `TranscriptReducer.finalizeTool` plus
     *    `TranscriptReducer.appendToolDiff` are where it happens.
     *
     * A consumer that trusted `change` alone would leave a stopped turn's tool
     * cards showing "运行中" and drop the error row `TranscriptReducer.failTurn`
     * appends. So [changedIndices] is the engine's own identity diff of the rows
     * against the previous publication, and [replaced] says the list was rebuilt
     * wholesale instead (`seedHistory`: `TranscriptReducer.seedFromHistory` calls
     * `TranscriptReducer.reset()` and still answers `Appended`).
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
     *    its own reader thread), and the only safe move is to adopt [rows] again;
     *  - **do not branch on [change]** to decide whether there is work. A `None`
     *    change can still carry [changedIndices] (F8's throttled chunk — see
     *    [publish]), so the incremental path has to be keyed on [changedIndices]
     *    being empty, not on the change kind.
     *
     * [change] is kept because it is the reducer's own word and it is cheap to read
     * for a caller that only wants to know the kind of movement — not because it
     * decides what a consumer has to do.
     */
    data class TranscriptPublication(
        /** Same number as [revision]. */
        val revision: Int,
        /**
         * The reducer's own verdict for the event or call that produced this; may
         * be [TranscriptChange.None] while [changedIndices] is not empty (F8).
         */
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
            lastExitCode = code
            _state.value = if (code == 0) EngineState.Stopped else EngineState.Failed
            // Fail every in-flight request rather than letting callers hang.
            //
            // The message carries the engine's own last words, not just the number:
            // the number alone is what the user already sees (`rpc: engine exited
            // with code 1`), and the cause is only ever in the stderr. See
            // [EngineExitCause] for what each line means and
            // `docs/engine-exit-review.md` §2 for the runs that produced them.
            val reason = buildString {
                append("engine exited with code ").append(code)
                val tail = stderrTail()
                if (tail.isNotEmpty()) append("；stderr：").append(tail)
            }
            pending.values.forEach { it.complete(failure(reason)) }
            pending.clear()
        }
    }

    /**
     * The engine's own last words, for the sentence a waiting caller gets.
     *
     * A bounded tail rather than the whole capture: this string ends up inside a
     * snackbar and a failure screen (the full 64 KB is what the diagnostic report is
     * for). Read under the same monitor the stderr drain writes under, because
     * [stderrJob] may be appending its final line while this runs — the process is
     * gone, but the drain reads the pipe to EOF.
     */
    private fun stderrTail(): String = synchronized(stderr) {
        val text = stderr.toString().trim()
        when {
            text.isEmpty() -> ""
            text.length <= EXIT_STDERR_TAIL_CHARS -> text
            else -> "…" + text.takeLast(EXIT_STDERR_TAIL_CHARS)
        }
    }.replace('\n', ' ')

    /**
     * Ask pi for its state, so [state] can leave [EngineState.Starting].
     *
     * ## Why a command and not "wait for the first byte"
     *
     * pi writes nothing on stdout until its startup is over, so a first-byte signal
     * would work today — but it is an accident of pi's current output, not a
     * contract. `get_state` is a contract: pi answers it from its stdin reader, so
     * the reply proves the reader is attached, which is the exact thing the UI needs
     * to be allowed to say 就绪 (`handle`).
     *
     * ## Why a timeout is not a failure
     *
     * The state deliberately stays [EngineState.Starting] when the probe times out:
     * a phone that needs three minutes is slow, not broken, and calling it 就绪 to
     * satisfy a timer is the lie this method exists to remove. A process that
     * actually dies is reported by [waitJob] (`EngineState.Failed`), which is a
     * different answer.
     *
     * Nothing is mutated here: flipping the state is [handle]'s job when the reply
     * arrives, because that is also where `agent_start`/`agent_settled` write it —
     * one place decides what [state] means.
     *
     * It also records how long the wait actually was ([lastServingMs]). That number
     * is the only way to tell "the phone is slow" from "we asked pi for something
     * expensive before it could read its stdin", and it is the user's whole
     * complaint measured rather than described (docs/known-gaps.md §M1).
     */
    fun probeServing(timeoutMs: Long = SERVING_PROBE_TIMEOUT_MS) {
        scope.launch {
            val startedAt = System.nanoTime()
            runCatching {
                request(build = { id -> PiCommands.getState(id) }, timeoutMs = timeoutMs)
            }.onSuccess { response ->
                // Only a real answer is recorded. `request` also answers with a
                // synthetic failure when it times out, and that one means the
                // opposite of "serving" — it is the case this whole method exists to
                // keep visible, so counting it would erase the evidence.
                if (response.success) {
                    lastServingMs = (System.nanoTime() - startedAt) / 1_000_000
                }
            }
        }
    }

    private suspend fun readLoop(input: InputStream) {
        val framer = JsonlFramer()
        // Incremental, because a read boundary is arbitrary and pi's stdout is
        // usually UTF-8 CJK (three bytes per character): decoding each read on its own
        // replaces a character that straddles two reads with one `\uFFFD` per orphaned
        // byte, inside the JSON string, where nothing downstream can tell it from
        // text the model wrote. `Utf8StreamDecoder` carries the partial sequence; its
        // KDoc has the measured baseline and `Utf8StreamDecoderCheck` pins it.
        val decoder = Utf8StreamDecoder()
        val buffer = ByteArray(16 * 1024)
        var dropped = 0L
        withContext(Dispatchers.IO) {
            while (true) {
                val read = try {
                    input.read(buffer)
                } catch (_: Throwable) {
                    -1
                }
                if (read < 0) break
                val records = framer.feed(decoder.decode(buffer, read))
                if (framer.droppedRecords != dropped) {
                    dropped = framer.droppedRecords
                    onRecordDropped()
                }
                for (record in records) handle(record)
            }
        }
        // A partial sequence at EOF is genuinely truncated input, so this is where it
        // becomes a replacement character — and the framer gets one last chance to
        // complete the record that was in flight.
        for (record in framer.feed(decoder.flush())) handle(record)
        for (record in framer.flush()) handle(record)
        if (framer.droppedRecords != dropped) onRecordDropped()
    }

    /**
     * A record larger than [JsonlFramer]'s cap was thrown away by the framer.
     *
     * **Why this is not silent.** The framer counts the loss (`droppedRecords`) but
     * nothing outside `:rpc`'s own unit test reads that number, so before this the
     * drop was invisible: the reader simply had one fewer record and every caller went
     * on waiting. For a *response* that is the worst possible failure mode, because the
     * wait is bounded only by the request's own timeout — 120 s by default
     * ([request]), and the replay path (`PiSessionViewModel.replayHistory` →
     * `get_entries`) has no shorter budget — so opening such a session looks exactly
     * like a hang and then silently does nothing.
     *
     * Every in-flight request is failed with the real reason instead. A dropped record
     * cannot be correlated to the request it belonged to (its id is inside the bytes
     * that were dropped), so the choice is between "answer everything that is waiting
     * with the truth" and "let something wait up to two minutes for an answer that has
     * already been destroyed". The reason names the transport, not the command, because
     * that is what actually happened.
     *
     * Reachable in practice: `get_entries` returns a whole session in **one** record,
     * and a long coding session with large tool outputs (pi's own cap is 50 KB per
     * result) or an inline image can exceed the framer's 8 MiB
     * (`JsonlFramer.DEFAULT_MAX_RECORD_CHARS`).
     */
    private fun onRecordDropped() {
        // User-visible through `PiSessionViewModel.fail` → the transcript's error row and
        // its notice, so it says what happened and what to do, not which object dropped
        // what: "a message from the engine was too large to read" is the whole of it.
        val reason = "引擎发来的一条内容太大，没能读取，这次操作没有完成。请重试。"
        pending.values.forEach { it.complete(failure(reason)) }
        pending.clear()
    }

    private fun handle(record: String) {
        val event = PiEvents.parse(record)
        if (event is PiEvent.Response) {
            // A response is the only proof that pi is *serving* this session. pi can
            // answer a command only after `runRpcMode` has attached its stdin reader,
            // and that is the last thing its startup does: node booting, the resource
            // loader compiling the extensions and `bindExtensions` emitting
            // `session_start` all happen with the pipe already open but unread. Until
            // then the process exists and is healthy while every command written to it
            // sits in the pipe. That gap is what the UI called 就绪 the instant
            // `ProcessBuilder.start()` returned, and under proot on a phone it is tens
            // of seconds (docs/known-gaps.md §M1).
            if (_state.value == EngineState.Starting) _state.value = EngineState.Ready
            pending.remove(event.id)?.complete(event)
        }
        when (event) {
            is PiEvent.AgentStart -> _state.value = EngineState.Busy
            is PiEvent.AgentSettled, is PiEvent.AgentEnd -> _state.value = EngineState.Ready
            else -> Unit
        }
        // The reader thread's critical section: [seedHistory] may be swapping the
        // reducer (and the published snapshot) from the caller's thread while this
        // event arrives, and [publish] is a read-modify-write over [publishedRows]
        // and [revision].
        //
        // **Isolated on purpose.** This is the process's only reader of pi's stdout,
        // and it runs in a `SupervisorJob` child whose failure nobody observes: one
        // exception out of the reducer would end the loop, after which the app never
        // reads another byte of pi's output. pi keeps writing until the ~64 KB pipe
        // buffer is full and then blocks in `write` — so the engine freezes mid-turn
        // (no further event ever arrives, `state` stays `Busy`, the wake lock stays
        // held), and every later `request` waits out its own full timeout because the
        // response that would have completed it is sitting unread in the pipe. That
        // is "the app hangs and nothing ever comes back", produced by one malformed
        // event. `TranscriptReducer.onEvent` documents "never throws", and this is the
        // second line of defence for the day that claim is wrong.
        //
        // `Exception`, not `Throwable`: an `Error` (an OOM while projecting a huge
        // row, a `StackOverflowError` in a deep diff) is a statement about the host,
        // and dressing it up as "one record was skipped" would hide the only evidence
        // of it behind a stalled engine.
        try {
            synchronized(transcriptLock) { foldEvent(event) }
        } catch (_: Exception) {
            // Deliberately swallowed per record: the response above is already
            // completed, the event is still emitted below, and the next record gets a
            // clean run at the reducer.
        }
        // Outside the lock: `tryEmit` touches no reducer state, and keeping it out
        // shortens the section the reader thread holds.
        _events.tryEmit(event)
    }

    /**
     * Project one event into the reducer and publish it. The caller holds
     * [transcriptLock].
     *
     * The transcript is projected here, exactly once, for every event — including
     * `entry_appended`, whose payload really is on the wire (`agent-session.ts:2620`
     * emits `{type:"entry_appended", entry}`, which `modes/json-event.ts` passes
     * through and `TranscriptReducer.onEvent` projects in its
     * `PiEvent.EntryAppended` arm). A caller must NOT call `transcript.onEntry(entry)`
     * again: it would double the row. Reads of the reducer from outside this class
     * are fine; *writes* are not — every one has to go through [publish] (this
     * method, [prompt], [echoUserPrompt] and [seedHistory]), or the change index and
     * the row diff are never published (fidelity/rendering review F5).
     */
    private fun foldEvent(event: PiEvent) {
        val change: TranscriptChange = transcript.onEvent(event)
        // A `response` is pi answering a command; it never projects a row
        // (`TranscriptReducer.onEvent`'s `else -> None`), so it must not wake the
        // UI. Every other event does, even when the change is `None`, because the
        // reducer also moves `streaming` / `thinkingLevel` / `lastUsage`.
        //
        // The one exception is F8, and it is the engine half of that fix: a
        // `tool_execution_update` the reducer's 200 ms window swallowed has already
        // merged its chunk into the stored row (the `items[index] = current.copy(…)`
        // write in `TranscriptReducer.onToolUpdate`) and returns `None` from the
        // throttle on purpose; the contract is stated on `TranscriptChange` itself, in
        // its `[None]` paragraph. Nothing else moved in that call:
        // `TranscriptReducer.onToolUpdate` never touches `streaming` or `lastUsage`,
        // and its only other state is the reducer-private `suppressedToolUpdate`
        // marker, which `TranscriptReducer.finishStreaming` flushes into its own
        // return at the next turn boundary, or `TranscriptReducer.finalizeTool` clears
        // when the card finalises. Publishing it anyway would bump [revision], write a
        // [TranscriptPublication] and wake the UI for exactly the repaint the throttle
        // exists to prevent, so the throttle would buy nothing.
        //
        // The same `None` also covers an update for a tool call this reducer never
        // opened, or one with no `partialText` (the three early returns at the top of
        // `TranscriptReducer.onToolUpdate`): nothing was stored there either, so
        // skipping is just as correct.
        val throttledToolUpdate =
            event is PiEvent.ToolExecutionUpdate && change == TranscriptChange.None
        if (!throttledToolUpdate && (change != TranscriptChange.None || event !is PiEvent.Response)) {
            publish(change)
        }
    }

    // ------------------------------------------------------------- publication

    /**
     * Publish one reducer effect: [change], the rows it produced, and the exact
     * rows that moved. The single writer of [revision] and [publication].
     *
     * Why the engine diffs rows itself instead of trusting `change`: see
     * [TranscriptPublication]. Every publication costs one identity scan of the row
     * list, and only one that really found a changed row pays for a reference copy
     * — so a publication that carries nothing new allocates nothing. The snapshot is
     * built under [transcriptLock] and handed out as an immutable list, so the UI
     * never reads the reducer's mutable row list across threads.
     *
     * **The caller must hold [transcriptLock].** This reads [publishedRows] and
     * [revision] and writes both, so two concurrent calls could publish two
     * revisions with the same number, or diff against a snapshot that no longer
     * belongs to the revision they report.
     *
     * Ordering is load-bearing: [publication] is written before [revision], so a
     * collector of either sees a revision that [publication] already describes.
     *
     * @param replaced the caller knows the whole list was rebuilt (`seedHistory`).
     */
    private fun publish(change: TranscriptChange, replaced: Boolean = false) {
        val current = transcript.transcript
        val previous = publishedRows
        // A shrink can only be `TranscriptReducer.reset()`,
        // and an empty `previous` is a consumer's first sight of the list: neither
        // can be described by row indices, so both are a wholesale handoff.
        val rolled = replaced || previous.isEmpty() || current.size < previous.size
        // `change == None` does **not** mean "no row moved", so it must not be used
        // to reuse the previous snapshot: F8's throttled `tool_execution_update`
        // mutates its row (the `items[index] = current.copy(…)` write in
        // `TranscriptReducer.onToolUpdate`) and returns `None` on purpose; the contract
        // is on `TranscriptChange` itself, in its `[None]` paragraph. A row reused on
        // a size check alone would be a stale row that nothing ever republishes. The
        // rows are therefore compared, not the change trusted.
        //
        // The scan runs against the reducer's live list rather than a copy, so the
        // unchanged case (the common one: a `message_update` that carried only
        // `usage`, `agent_start`, `thinking_level_changed`) allocates nothing.
        val changed = if (rolled) emptyList() else diffIndices(previous, current)
        // `changed` is empty exactly when the size is unchanged and every row is the
        // same object (`diffIndices` reports every row past the old end), so this is
        // the only case in which the previous snapshot is still the current truth.
        val rows = if (rolled || changed.isNotEmpty()) current.toList() else previous
        publishedRows = rows
        val next = _changes.value + 1
        _publication.value = TranscriptPublication(
            revision = next,
            change = change,
            rows = rows,
            changedIndices = changed,
            replaced = rolled,
            streaming = transcript.streaming,
        )
        _changes.value = next
    }

    /**
     * Row indices whose element identity differs between the previous snapshot and
     * the reducer's [current] list, plus every row [current] added at the tail.
     *
     * `current` is passed live, not as a copy: this only compares by index, and
     * comparing first is what lets [publish] avoid a copy when nothing moved.
     *
     * `!==` rather than `!=`: reducer items are immutable data classes and every
     * mutation path builds a new instance (`items[i] = current.copy(...)`), so an
     * unchanged row is the *same object*, while `!=` would also compare a large
     * tool output string for equality.
     */
    private fun diffIndices(
        previous: List<TranscriptItem>,
        current: List<TranscriptItem>,
    ): List<Int> {
        val shared = minOf(previous.size, current.size)
        val changed = mutableListOf<Int>()
        for (i in 0 until shared) {
            if (previous[i] !== current[i]) changed += i
        }
        for (i in shared until current.size) changed += i
        return changed
    }

    // --------------------------------------------------------------- sending

    /**
     * Queue a command for the writer thread; use [request] when the reply matters.
     *
     * @return false when the command could not be handed to the writer at all — the
     *   engine already failed a write, or [close] has shut the queue down. Nothing
     *   here blocks the caller: the write, the flush and the JSON encoding all happen
     *   on [writeQueue] (see its KDoc for why that matters on the frame thread). That
     *   also means a `true` is "queued", not "delivered" — the same distinction the
     *   old KDoc drew for a successful write, since a successful write was never
     *   evidence of a live engine either. Callers that must know whether an engine is
     *   reachable read [state] (driven by the process itself) or, in the app,
     *   `UiState.engine`.
     */
    fun send(command: JsonObject): Boolean {
        if (writeBroken.get()) return false
        return runCatching {
            writeQueue.execute {
                runCatching {
                    writer.write(PiCommands.encode(command))
                    writer.flush()
                }.onFailure { writeBroken.set(true) }
            }
            true
        }.getOrDefault(false)
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
     * for `assistant` (the `PiEvent.MessageStart` arm of `TranscriptReducer.onEvent`).
     * Without the echo the bubble the
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
        synchronized(transcriptLock) {
            publish(transcript.onUserPrompt(text, images))
        }
    }

    /**
     * Rebuild the whole stream from pi's persisted entries (`get_entries`).
     *
     * `TranscriptReducer.seedFromHistory` resets the reducer first (`reset()`) and
     * then still answers `TranscriptChange.Appended(lastIndex)`, which describes one
     * appended row — so the publication is marked `replaced` and the caller must
     * adopt [TranscriptPublication.rows] wholesale instead of inserting at that
     * index.
     *
     * ## Why this is suspend, and why it builds a second reducer
     *
     * The projection is the expensive half of a replay: one row per content block,
     * plus day separators, `summarizeToolArgs` and `parseUnifiedDiff`. The frame
     * thread used to run it inside the caller's coroutine (`Main.immediate`), which
     * is what froze the UI when a long session was opened — the JSON parse was
     * already off the frame thread (the `PiEvents.parse` in [readLoop]), so moving
     * that was moving the cheap half.
     *
     * So the projection runs on [Dispatchers.Default] against a **fresh**
     * [TranscriptReducer], which needs no synchronization at all: it is built on one
     * thread and no other thread can see it until [adoptSeededTranscript] swaps it
     * in. The old design mutated the live reducer from the caller's thread while the
     * reader thread kept folding events into the same instance.
     *
     * ## The window this leaves
     *
     * Events pi sends between the `get_entries` response and the take-over are folded
     * into the *previous* reducer and are then dropped with it. The window is the
     * projection itself (41.84 ms for a 2000-entry session on a desktop JVM, not
     * measured on a phone) and the callers are session switches, forks, clones and
     * attach — points where pi is idle. Before this change the same window was not a
     * dropped update but a data race on the reducer's list, so this is strictly
     * better, not a regression; a lossless version would have to queue the events
     * that arrive during the projection and fold them in after the swap.
     */
    suspend fun seedHistory(entries: List<JsonObject>) {
        val attempt = seedAttempts.incrementAndGet()
        val seeded = withContext(Dispatchers.Default) {
            TranscriptReducer().also { it.seedFromHistory(entries) }
        }
        adoptSeededTranscript(seeded, attempt)
    }

    /**
     * Take over [seeded] as this session's reducer, and publish the whole new row
     * list as a replacement.
     *
     * The swap and the publication are one critical section: a reader-thread event
     * folded between the two would otherwise be published against a snapshot that
     * belongs to the session we are replacing. Two consequences worth knowing:
     *
     *  - [TranscriptChange.None] is published on purpose — the reducer's own answer
     *    for the seed (`Appended(lastIndex)`) describes one row of a list that was
     *    rebuilt wholesale, so `replaced = true` is the honest description and a
     *    consumer must adopt [TranscriptPublication.rows]. Consumers already must
     *    not branch on the change kind;
     *  - the new reducer starts with `streaming = false` (`TranscriptReducer.reset`),
     *    exactly as the old in-place seed did, so a take-over during a live turn
     *    would report the turn as finished. No caller does that today: every
     *    [seedHistory] call follows `switch_session` / `new_session` / `fork` /
     *    `clone` or attach, where pi is idle.
     *
     * An [attempt] that is not newer than the last one adopted is dropped: see
     * [seedAttempts]. Nothing is published for it, so the newest session stays on
     * screen.
     */
    private fun adoptSeededTranscript(seeded: TranscriptReducer, attempt: Long) {
        synchronized(transcriptLock) {
            if (attempt <= adoptedSeedAttempt) return
            adoptedSeedAttempt = attempt
            reducer = seeded
            publish(TranscriptChange.None, replaced = true)
        }
    }

    /**
     * Take every queued message back out of pi **without** touching the turn in
     * flight: pi's `app.message.dequeue` (`alt+up`), wired at
     * `modes/interactive/interactive-mode.ts:2899` and implemented by
     * `restoreQueuedMessagesToEditor()` (`:4387-4406`) — which is
     * `clearAllQueues()` + put the text back in the editor, and only aborts when the
     * caller passes `{ abort: true }`.
     *
     * Everything queued comes back at once, matching pi: `clear_queue` takes no
     * argument and its handler returns the whole `session.clearQueue()`
     * (`rpc-types.ts:26`, `rpc-mode.ts:433-435`, `core/agent-session.ts:1608-1615`),
     * and pi's pending-messages display is a read-only list under a single
     * "edit all queued messages" hint — there is no per-message dequeue to
     * reproduce.
     *
     * The queue text is read from the **`clear_queue` response**, not from
     * `get_state`. `clear_queue` answers with the arrays it just removed
     * (`rpc-mode.ts:433-435` returns `session.clearQueue()`, i.e.
     * `{steering, followUp}` — `agent-session.ts:1608-1615`), while `get_state`
     * carries no queue arrays at all (`RpcSessionState` has only
     * `pendingMessageCount`). Reading them off `get_state` therefore always
     * produced two empty lists: pi emptied its queue and the text was never handed
     * back, so pressing Stop silently destroyed whatever the user had queued.
     */
    suspend fun drainQueue(): List<String> {
        val cleared = request(PiCommands.clearQueue(nextId()))
        val steering = queuedText(cleared, "steering")
        val followUp = queuedText(cleared, "followUp")
        return steering + followUp
    }

    /**
     * pi's Escape: [drainQueue], then abort — and the queue contents come back to
     * the caller so the composer can restore them
     * (docs/pi-android-ui-spec.md §7.2).
     *
     * The abort is unconditional and comes **after** the drain, exactly as
     * `restoreQueuedMessagesToEditor({ abort: true })` orders it
     * (`interactive-mode.ts:4391` then `:4404-4406`): a `clear_queue` that failed
     * must not leave a turn running that the user asked to stop.
     */
    suspend fun stopAndDrainQueue(): List<String> {
        val restored = drainQueue()
        request(PiCommands.abort(nextId()))
        return restored
    }

    private fun queuedText(response: PiEvent.Response, key: String): List<String> {
        val data = response.data as? JsonObject ?: return emptyList()
        val array = data[key] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }

    /**
     * Close pi **after** letting it finish the turn it is in.
     *
     * Why [close] alone is not enough: pi writes a session entry when a message
     * *ends* (`agent-session.ts:669-691` → `SessionManager.appendMessage`), and
     * `_persist` does not create the session file at all until the first assistant
     * message of the session has ended (`session-manager.ts:1029-1052`) — a session
     * whose reply never lands is not on disk, and a later turn's assistant message
     * only reaches the file at its own `message_end`. [close] shuts pi's stdin, and
     * pi treats a closed stdin as "exit now" (`rpc-mode.ts:805-807` calls
     * `shutdown()`, which unsubscribes and exits without waiting for the agent), so
     * a turn that is still streaming is missing from the conversation that is left
     * behind — the second half of "I left the app and the conversation was gone".
     *
     * The fix is pi's own command, not a delay: the `abort` handler awaits
     * `session.abort()`, which awaits `waitForIdle()` (`agent-session.ts:1640-1646`),
     * so its **response** arrives only after the turn has stopped — and an aborted
     * assistant message is persisted like any other `message_end`. `get_state`
     * decides whether that is needed at all, using pi's own flags rather than the
     * local `Busy` state, which flips to ready on `agent_end` while a compaction may
     * still be running (`PiEvent.AgentEnd` and `PiEvent.AgentSettled` both map to
     * `Ready`).
     *
     * Bounded on purpose: this runs while a screen is going away, so a pi that never
     * answers must not hold the teardown forever. Every failure path falls through
     * to [close] — a hard kill is worse than a clean stop, but it is not worse than
     * leaking the process.
     */
    suspend fun closeAfterSettling(timeoutMs: Long = SETTLE_TIMEOUT_MS) {
        // An engine that has already exited has nothing to settle, and asking it
        // anything costs the whole timeout: `request` below would write into a closed
        // pipe (dropped, see [send]) and then wait out `get_state`'s entire budget
        // before falling through to [close] anyway. That path is reachable from the
        // user — the notification's 停止 and the ViewModel's teardown both run it
        // after pi has exited on its own — and it turned an immediately-correct
        // teardown into a 30-second no-op wait on the IO dispatcher.
        //
        // The evidence is the process's own ([waitJob] writes `Stopped`/`Failed` from
        // `waitFor`), not a guess from a flag nobody sets.
        if (alreadyExited()) {
            close()
            return
        }
        runCatching {
            val state = request(PiCommands.getState(nextId()), timeoutMs)
            val snapshot = PiResponses.sessionState(state)
            if (snapshot != null && (snapshot.isStreaming || snapshot.isCompacting)) {
                request(PiCommands.abort(nextId()), timeoutMs)
            }
        }
        close()
    }

    /** True once the process is gone: either the OS says so, or the state machine does. */
    private fun alreadyExited(): Boolean =
        !process.isAlive ||
            _state.value == EngineState.Stopped ||
            _state.value == EngineState.Failed

    fun close() {
        // Close stdin *before* killing the process, and let the writer thread be the
        // one that does it: every queued command is written and flushed first, and a
        // write parked on a full pipe unblocks as soon as the pipe's read end goes
        // away. Bounded, because this runs while a screen or a ViewModel is going
        // away: past [WRITER_DRAIN_TIMEOUT_MS] the destroy below is what frees the
        // writer, and the thread is a daemon.
        runCatching {
            writeQueue.execute { runCatching { writer.close() } }
            writeQueue.shutdown()
            writeQueue.awaitTermination(WRITER_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
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
         * How much of the captured stderr travels in the failure sentence a waiting
         * caller receives.
         *
         * Small on purpose: this text is rendered in a snackbar and on the failure
         * screen, and both are one line tall in practice. The full capture is what
         * the diagnostic report exports.
         */
        private const val EXIT_STDERR_TAIL_CHARS = 400

        /**
         * How long the most recent engine took to answer its first command, in
         * milliseconds, or null while none has.
         *
         * Process-wide rather than per-session because the reader is 设置 →
         * 运行时与诊断, which has no `PiEngineSession` to ask — the same shape as
         * `PiEngineService.isWakeLockHeld()`, which the runtime rows already read for
         * exactly this reason. Written only by [probeServing], which is the boot path
         * every engine goes through (`PiEngineHost.bootLocked`).
         */
        @Volatile
        var lastServingMs: Long? = null
            private set

        /**
         * How long [probeServing] waits for pi's first answer.
         *
         * Generous on purpose, and deliberately longer than [request]'s own default:
         * the probe is measuring a cold start under proot on a phone, which is the
         * slowest path in this app, and a timeout here is not an error — it only
         * leaves the UI saying 启动中 (see [probeServing]).
         */
        private const val SERVING_PROBE_TIMEOUT_MS = 300_000L

        /**
         * How long [closeAfterSettling] waits for pi to answer `get_state` and the
         * follow-up `abort`.
         *
         * Far shorter than [request]'s own default and shorter than any LLM call on
         * purpose: pi's `abort` returns as soon as the turn stops, which is a local
         * state transition plus the write of the aborted message, so the only reason
         * to wait this long is a phone that is out of CPU under proot. Past that the
         * session is closed anyway; the alternative to a bounded wait is an app that
         * cannot be left.
         */
        private const val SETTLE_TIMEOUT_MS = 30_000L

        /**
         * How long [close] waits for the writer thread to finish what it was given.
         *
         * Long enough for a few megabytes of attachment to reach a pi that is reading,
         * short enough that tearing a screen down is never perceptible. Reaching it
         * means a write is parked on a full pipe, and the `process.destroy()` that
         * follows is the thing that ends the wait.
         */
        private const val WRITER_DRAIN_TIMEOUT_MS = 1_000L

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
