package app.pi.highlight

import android.content.Context
import app.pi.runtime.PiPaths
import app.pi.ui.render.PiCodeHighlighter
import app.pi.ui.render.PiCodeLanguage
import app.pi.ui.render.PiCodeSpan
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock

/**
 * The app's code highlighter: pi's own highlight.js, asked over loopback.
 *
 * `docs/syntax-highlight-eval.md` measured every Android-side option. Running a
 * JavaScript engine on the phone costs 48–98 ms per 40-line block (Rhino) or
 * drags in a TextMate stack that is not byte-compatible with pi; the same
 * highlight.js 10.7.3 under the guest's Node answers in ~2 ms and produces pi's
 * HTML exactly. So the engine stays where pi already put it, and this class is
 * only the client — see [PiHighlightClient].
 *
 * Contract with the rest of the app:
 *
 *  - it is installed through `LocalPiCodeHighlighter`, and is safe to use before
 *    [attach] (it returns no spans, so code renders in pi's plain block colour);
 *  - **it never throws.** Every failure — engine not started, timeout, malformed
 *    reply, a span outside the code — becomes "no highlighting";
 *  - **the code must be settled.** A fence that is still streaming changes on
 *    every token, and highlighting each intermediate version would be a
 *    cache-miss storm against the engine. The caller debounces
 *    (`rememberPiHighlightedCode`) and this class additionally keys its cache on
 *    the exact text, so a churning caller is merely useless, never harmful;
 *  - it is safe to call from several threads. Work is queued and executed by at
 *    most [MAX_IN_FLIGHT] threads; results are cached under [CACHE_ENTRIES].
 *
 * Deliberately absent: any timer, any polling, any warm-up. Nothing here runs
 * until a code block is actually composed, and nothing runs again once the
 * transcript stops moving.
 */
internal object PiNodeCodeHighlighter : PiCodeHighlighter {

    /**
     * At most two requests in flight, a FIFO queue of at most [QUEUE_LIMIT] more,
     * and anything beyond that is dropped — the block renders plain. A session
     * loaded from disk can compose dozens of blocks at once; letting each one
     * open its own socket would put a burst of connections on the guest's HTTP
     * server at exactly the moment the engine is starting. First-come,
     * first-served is right here because the visible blocks are composed first.
     */
    private const val MAX_IN_FLIGHT = 2
    private const val QUEUE_LIMIT = 64

    /**
     * How long a caller may wait for its turn *plus* the request. The request
     * itself is capped at 100 ms connect + 150 ms read ([PiHighlightClient]); the
     * extra room is only for waiting behind the queue, on a `Dispatchers.Default`
     * thread, never on the frame thread. A cold engine (highlight.js is loaded on
     * its first request) is the slow case this covers.
     */
    private const val CALLER_WAIT_MS = 750L

    /**
     * The cache bound. Keyed by a hash of `(language, code)`, so entries never pin
     * a fence's text; 128 entries is several screens of transcript plus the
     * re-compositions of the same block, which is the whole point of the cache.
     * Span lists are small (a 40-line block is ~100 spans) and anything
     * pathological is not cached at all — see [MAX_CACHED_SPANS].
     */
    private const val CACHE_ENTRIES = 128
    private const val MAX_CACHED_SPANS = 2048

    /**
     * Above these, no request is made. `pi` has no cap because a terminal is
     * synchronous; here a 400-line fence would hold an engine-side highlight for
     * a tenth of a second and could not be painted faster than plain text anyway
     * (the eval doc reaches the same conclusion in §4.3).
     */
    private const val MAX_CODE_CHARS = 64 * 1024
    private const val MAX_CODE_LINES = 400

    @Volatile
    private var client: PiHighlightClient? = null

    private val workers = BoundedWorkers(threads = MAX_IN_FLIGHT, queueLimit = QUEUE_LIMIT)

    private val cacheLock = Any()

    private val cache = object : LinkedHashMap<String, List<PiCodeSpan>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<PiCodeSpan>>): Boolean =
            size > CACHE_ENTRIES
    }

    /**
     * Point the highlighter at the guest. Called from composition with the
     * Activity's context, so it must stay cheap — it only computes file paths.
     *
     * The three candidate files are the host-side views of the two guest paths the
     * extension publishes to: the proot rootfs maps `/root/...` 1:1, and the agent
     * directory is the same tree unless the engine binds it separately.
     */
    fun attach(context: Context) {
        if (client != null) return
        synchronized(this) {
            if (client != null) return
            val app = context.applicationContext ?: context
            val paths = PiPaths(
                filesDir = app.filesDir,
                nativeLibDir = File(app.applicationInfo.nativeLibraryDir),
            )
            client = PiHighlightClient(
                listOf(
                    File(File(paths.rootfs, "root/.pi/agent"), TOKEN_FILE_NAME),
                    File(paths.agentDir, TOKEN_FILE_NAME),
                    File(File(paths.rootfs, "root/.pi"), TOKEN_FILE_NAME),
                ),
            )
        }
    }

    override fun highlight(code: String, language: String?): List<PiCodeSpan> = try {
        resolve(code, language)
    } catch (error: Throwable) {
        // The seam's contract is "never throws": an interrupted wait, a missing
        // digest or a bug anywhere below must still leave the block renderable.
        emptyList()
    }

    private fun resolve(code: String, language: String?): List<PiCodeSpan> {
        if (PiCodeLanguage.isPlaintext(language) || code.isEmpty()) return emptyList()
        val name = language ?: return emptyList()
        if (code.length > MAX_CODE_CHARS || code.count { it == '\n' } > MAX_CODE_LINES) return emptyList()
        val active = client ?: return emptyList()

        val key = cacheKey(name, code)
        synchronized(cacheLock) { cache[key] }?.let { return it }

        val spans = workers.submit({ active.fetch(code, name) }, CALLER_WAIT_MS) ?: return emptyList()
        // Only definitive answers are cached. A failure must be retried the next
        // time the block is composed, otherwise a block asked for while the engine
        // was still starting would stay uncoloured for the life of the entry.
        if (spans.size <= MAX_CACHED_SPANS) {
            synchronized(cacheLock) { cache[key] = spans }
        }
        return spans
    }

    /**
     * `(language, code)` as a digest. Hashing rather than storing the text keeps
     * the cache's memory proportional to the spans it holds instead of to the
     * transcript.
     */
    private fun cacheKey(language: String, code: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(language.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(code.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /** Diagnostics for the settings/diagnostics surface; never used on a hot path. */
    fun lastFailure(): String? = client?.lastFailure

    private companion object {
        const val TOKEN_FILE_NAME = "highlight-bridge.json"
    }
}

/**
 * A tiny fixed-concurrency queue.
 *
 * Written by hand instead of using an executor because the bound has to be
 * *observable*: the caller must learn immediately that its work was dropped
 * (and render plain text) rather than wait in an unbounded queue. Threads are
 * created on the first submission and are daemons, so an app that never shows a
 * code block never creates one.
 */
private class BoundedWorkers(private val threads: Int, private val queueLimit: Int) {

    private class Task(val work: () -> List<PiCodeSpan>?) {
        var result: List<PiCodeSpan>? = null
        var finished: Boolean = false
    }

    private val lock = ReentrantLock()
    private val waiting = lock.newCondition()
    private val completed = lock.newCondition()
    private val queue = ArrayDeque<Task>()
    private var started = false

    /**
     * @return the work's result, or `null` when the queue was full or the caller's
     *   budget ran out. Both mean "render plain"; neither throws.
     */
    fun submit(work: () -> List<PiCodeSpan>?, waitMs: Long): List<PiCodeSpan>? {
        val task = Task(work)
        lock.withLock {
            if (queue.size >= queueLimit) return null
            queue.addLast(task)
            if (!started) {
                started = true
                repeat(threads) { index -> spawn(index) }
            }
            waiting.signalAll()
        }

        val deadline = System.nanoTime() + waitMs * 1_000_000L
        lock.withLock {
            while (!task.finished) {
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0L) return null
                completed.await(remainingMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            return task.result
        }
    }

    private fun spawn(index: Int) {
        Thread({ runWorker() }, "pi-highlight-$index").apply {
            isDaemon = true
            start()
        }
    }

    private fun runWorker() {
        while (true) {
            try {
                val task = lock.withLock {
                    while (queue.isEmpty()) waiting.await()
                    queue.removeFirst()
                }
                val result = runCatching { task.work() }.getOrNull()
                lock.withLock {
                    task.result = result
                    task.finished = true
                    completed.signalAll()
                }
            } catch (error: Throwable) {
                // A wait that is interrupted must not kill the worker: the next
                // submission would then have nobody to run it, and the queue would
                // silently stop draining.
            }
        }
    }
}
