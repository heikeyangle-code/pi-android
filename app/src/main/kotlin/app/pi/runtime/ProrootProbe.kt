package app.pi.runtime

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The proroot **probe gate**: the one thing that decides whether proroot may be used
 * at all, and the cache that keeps it from being re-run on every launch.
 *
 * ## Why a gate exists
 *
 * proroot is closed source and cannot be audited, and its failure mode is silent
 * rather than loud (`docs/proroot-research.md` §5.P0-2). "The files are present" says
 * nothing about whether path translation works on *this* device with *these* guest
 * binaries, so the switch does not mean "prefer proroot": it means "use proroot **if a
 * real invocation proves it works here**" (§6.2 step 5, and §9.6 step 1, which is the
 * research's own recommended order — probes first, launch path second).
 *
 * ## The two probes, and why both
 *
 * 1. [ProrootRawProbe] — a syscall issued outside libc. It covers the layer proroot
 *    reaches by *patching binaries* rather than by intercepting calls, and it is where
 *    a silent host-file read would show up.
 * 2. [GuestToolProbe] run **through proroot** — the real `rg` and `fd` binaries,
 *    invoked for real, searching real guest paths. They are **musl-static** Rust
 *    binaries (`tools/fetch-runtime.mjs:190-196`), so they do not go through the
 *    dynamic linker at all, and their `--version` can look perfect while a guest path
 *    comes back empty. pi's `find`/`grep` tools and the `@` completion depend on
 *    them, so a failure here is a capability that disappears with no error anywhere
 *    (§10.5).
 *
 * Both must pass. A leak fails the gate outright.
 *
 * ## The cache
 *
 * The verdict is keyed by the unpacked runtime revision **and** the digest of the
 * five proroot binaries, and stored in the volatile runtime tree
 * ([ProrootProbeCache], [PiPaths.prorootProbeCache]). It is therefore re-earned
 * whenever either of the two things it is about changes, and it cannot outlive the
 * tree it describes. A **failed** verdict is cached too: the gate is a measurement,
 * not a retry loop, and re-running a probe that just failed on every launch would
 * spend seconds to learn the same thing.
 *
 * Blocking. Every caller is already off the main thread (`PiEngineHost` boots on
 * `Dispatchers.IO`, `PtyLauncher.prepare` runs its own guest probe, `GuestCommand`
 * is a batch path), and the work is bounded by [RAW_TIMEOUT_MS] plus
 * [GuestToolProbe.TIMEOUT_MS].
 */
object ProrootProbe {

    /** Bound for the raw-syscall probe: one interpreter start, no network. */
    const val RAW_TIMEOUT_MS: Long = 20_000L

    /** A gate verdict, cached or freshly measured. */
    data class Verdict(
        val passed: Boolean,
        /** [ProrootProbeCache.key] this verdict is valid for. */
        val key: String,
        /** The evidence lines, in the order they should be read. */
        val detail: List<String>,
        /** True when it came off disk rather than from a run. */
        val cached: Boolean,
        val raw: ProrootRawProbe.Report? = null,
        val tools: GuestToolProbe.Report? = null,
    )

    /**
     * The digest that identifies the proroot binaries an app update may have replaced
     * without touching the runtime tree.
     *
     * File names are hashed in `RuntimeChoice.REQUIRED_FILES` order alongside their
     * bytes, so a rename and a content swap both change the digest, and a missing file
     * cannot collide with an empty one.
     */
    /**
     * Cache of the last digest, keyed by the directory it describes.
     *
     * Why caching is sound rather than an optimisation with a stale-data risk: the five
     * binaries live in `nativeLibraryDir`, which is a property of the **installed APK**.
     * Nothing in the app can write there, and an app update kills this process, so
     * within one process those bytes cannot change — while `plan()` is on the `@`
     * mention-scan path, i.e. it can run several times per turn, and hashing ~600 KB of
     * `.so` each time is pure waste. Keyed by the directory path so two different
     * installs (or a test fixture) cannot share an entry.
     */
    @Volatile
    private var digestCache: Pair<String, String>? = null

    fun digestOf(paths: PiPaths): String {
        val key = paths.nativeLib.path
        digestCache?.let { if (it.first == key) return it.second }
        val digest = computeDigest(paths)
        digestCache = key to digest
        return digest
    }

    private fun computeDigest(paths: PiPaths): String {
        val md = MessageDigest.getInstance("SHA-256")
        RuntimeChoice.REQUIRED_FILES.forEachIndexed { index, name ->
            md.update(name.toByteArray())
            md.update(0)
            val file = paths.prorootComponents()[index]
            if (file.isFile) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        md.update(buffer, 0, read)
                    }
                }
            } else {
                md.update("<missing>".toByteArray())
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** The cache key for a revision and a digest. */
    fun key(revision: String, digest: String): String = ProrootProbeCache.key(revision, digest)

    /** The cached verdict for this revision + digest, or null when there is none. */
    fun cached(paths: PiPaths, revision: String, digest: String): Verdict? {
        val key = key(revision, digest)
        val text = runCatching { paths.prorootProbeCache().readText() }.getOrNull()
        val parsed = ProrootProbeCache.parse(text, key) ?: return null
        return Verdict(passed = parsed.passed, key = key, detail = parsed.detail, cached = true)
    }

    /**
     * Run both probes and write the verdict. Blocking; see the class KDoc.
     *
     * The planted probe file is deleted on the way out in every path — it is the
     * app's own file in the app's own tmp directory.
     */
    fun run(paths: PiPaths, storage: File?, revision: String, digest: String): Verdict {
        val key = key(revision, digest)
        val plantedHost = File(paths.tmp, ProrootRawProbe.PLANTED_NAME)
        val token = UUID.randomUUID().toString()

        val rawOutput = runCatching {
            runGuest(
                paths = paths,
                engine = GuestEngine.Proroot,
                guestCommand = ProrootRawProbe.guestCommand(paths.tmp.path, token),
                storage = storage,
                timeoutMs = RAW_TIMEOUT_MS,
            )
        }.getOrElse { error -> "proroot 启动探针失败：${error::class.java.simpleName}: ${error.message}" }
        runCatching { plantedHost.delete() }

        val raw = ProrootRawProbe.parse(rawOutput)
        val detail = mutableListOf<String>()
        detail += raw.describe()

        var tools: GuestToolProbe.Report? = null
        if (raw.interpreter == null) {
            tools = runCatching { GuestToolProbe.run(paths, storage, GuestEngine.Proroot) }
                .getOrElse { error ->
                    GuestToolProbe.Report(
                        emptyList(),
                        launchError = "proroot 工具链探针抛了异常：${error::class.java.simpleName}: ${error.message}",
                    )
                }
            detail += tools.describe().map { "  $it" }
        }

        val passed = raw.passed && (tools?.ok ?: false)
        val verdict = Verdict(
            passed = passed,
            key = key,
            detail = detail,
            cached = false,
            raw = raw,
            tools = tools,
        )
        // Only a run writes the cache, and it writes failures too: the next launch
        // must not pay for the same measurement.
        runCatching {
            paths.runtime.mkdirs()
            paths.prorootProbeCache().writeText(ProrootProbeCache.render(key, passed, detail))
        }
        // The planted file lives in the guest's /tmp; if translation did not work it
        // may not exist at all, and deleting a file that is not there is fine. This
        // is the app's own file in the app's own tmp directory (`PiPaths.tmp`).
        runCatching { plantedHost.delete() }
        return verdict
    }

    /**
     * One blocking guest command through `engine`, with stderr merged and a hard
     * timeout. Mirrors `GuestToolProbe.run`'s shape on purpose: the two probes must
     * fail the same way, or one of them would report a launch problem as a tool
     * problem.
     */
    private fun runGuest(
        paths: PiPaths,
        engine: GuestEngine,
        guestCommand: String,
        storage: File?,
        timeoutMs: Long,
    ): String {
        val argv = GuestCommandLine.build(paths, engine, guestCommand, cwd = "/", storage = storage)
        val env = GuestCommandLine.environment(paths, engine)
        val process = ProcessBuilder(argv)
            .directory(paths.runtime)
            .redirectErrorStream(true)
            .also { it.environment().putAll(env) }
            .start()
        val finished = runCatching { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!finished) {
            process.destroyForcibly()
            val partial = runCatching { process.inputStream.bufferedReader().use { it.readText() } }
                .getOrDefault("")
            return partial + "\n${ProrootRawProbe.MARKER}\tinterpreter\ttimeout\t" +
                "proroot 探针 ${timeoutMs / 1000} 秒没有返回"
        }
        return runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
    }
}
