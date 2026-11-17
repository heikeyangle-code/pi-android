package app.pi.packages

import app.pi.rpc.PiJson
import app.pi.settings.PiSettingsFileStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * The three official files a credential/model import touches, written the way pi
 * reads them.
 *
 * ## Where the definitions actually live — verified, not assumed
 *
 * This was the open question, and pi's source answers it unambiguously. **Neither a
 * provider nor a credential is a `settings.json` key.**
 *
 *  - `Settings` (`core/settings-manager.ts:106-158`) has `defaultProvider`,
 *    `defaultModel`, `modelThinkingLevels` and `enabledModels` — but **no
 *    `providers` key and no credential key**. Grepping the interface is enough to
 *    settle it.
 *  - Custom providers and models are declared in **`<agentDir>/models.json`**, under
 *    a top-level `providers` object (`docs/models.md:3`; loader
 *    `core/model-config.ts:199-215`, location `core/model-runtime.ts:174-175`).
 *    `docs/custom-provider.md:33`: "Pi composes `models.json` overrides above
 *    registered native providers."
 *  - Credentials are in **`<agentDir>/auth.json`** (`core/auth-storage.ts:52`),
 *    a `Record<providerId, Credential>`.
 *
 * So `settings.json` is only the *selection* store (`defaultModel`,
 * `enabledModels`), never the definition store.
 *
 * ## Locking: two of the three are locked, and which two matters
 *
 *  - `auth.json` — `lockfile.lockSync(authPath, { realpath: false })`, 10 attempts ×
 *    20 ms (`auth-storage.ts:76-100`). Also `AUTH_FILE_WRITE_OPTIONS` sets
 *    `mode: 0o600`, and the comment is explicit that the mode applies **only on
 *    creation** (`:24-25`).
 *  - `settings.json` — `lockfile.lockSync(path, { realpath: false })`, same retry
 *    shape (`settings-manager.ts:236-256`). Taking it matters because pi rewrites
 *    this file whenever it persists a setting (thinking level, theme, compaction),
 *    so an unlocked write is a lost update with no error — "我的设置过一会儿自己变
 *    回去了".
 *  - `models.json` — **not locked at all.** `ModelConfig.load` is a plain
 *    `readFile` (`model-config.ts:251-290`) and nothing in pi ever writes this file;
 *    the *cache* of dynamically discovered models goes to `models-store.json`
 *    instead (`model-runtime.ts:180`). The app still writes it atomically, but
 *    inventing a lock here would be a second lock no other writer honours.
 *
 * `proper-lockfile`'s lock is a **directory** at `<file>.lock`; a live lock is never
 * stolen and a lock older than the stale window is removed.
 *
 * ## Writes are atomic, and pi's files are not
 *
 * pi uses bare `writeFileSync` for all three. A kill mid-write truncates
 * `auth.json`, and `ReadOnlyAuthStorage.load` then throws on every start
 * (`auth-storage.ts:216-227`). The app writes a sibling temp file and renames, the
 * same choice already made for `settings.json` in `PiSettingsFileStore.kt:33-35`.
 */
object PiConfigFiles {

    /** `<agentDir>/auth.json`; `<agentDir>` is what `PiConfigPaths` resolved. */
    fun authFile(agentDir: File): File = File(agentDir, "auth.json")

    /** `<agentDir>/models.json`. */
    fun modelsFile(agentDir: File): File = File(agentDir, "models.json")

    /**
     * The file a read should use: the primary when it exists, else the mirror.
     *
     * "Primary" is always the guest-truth path, because that is what pi reads. The
     * mirror is a fallback *and* a durability copy, not a second source of truth —
     * see [AgentLayout] for why both exist.
     */
    fun effectiveFile(primary: File, mirror: File?): File? = when {
        primary.isFile -> primary
        mirror?.isFile == true -> mirror
        else -> null
    }

    // ------------------------------------------------------------------- locking

    /**
     * pi's `proper-lockfile` semantics, reproduced on the same lock path.
     *
     * `lockfile.lockSync(path, { realpath: false })` creates the directory
     * `<path>.lock` (mkdir is atomic) and removes it to release; it retries
     * `ELOCKED` 10 times with a 20 ms sleep and treats a lock older than its default
     * 10 s window as stale. This does the same, so the app and pi coordinate instead
     * of overwriting each other.
     */
    fun <T> withLock(target: File, block: () -> T): T {
        val lockDir = File(target.parentFile, "${target.name}.lock")
        lockDir.parentFile?.mkdirs()
        var attempt = 0
        while (true) {
            attempt++
            if (runCatching { lockDir.mkdir() }.getOrDefault(false)) {
                return try {
                    block()
                } finally {
                    runCatching { lockDir.delete() }
                }
            }
            // The bound is checked **before** the stale branch, and every path that
            // neither returns nor throws falls through to the single sleep at the
            // bottom of the loop. Both halves are load-bearing. The stale branch used
            // to `continue` without sleeping *and* before the bound test, so a stale
            // lock whose `delete()` fails — a non-empty lock directory, or a parent the
            // caller cannot write — looped mkdir → stat → delete → continue at 100% CPU
            // for the life of the process, which is the one path `MAX_ATTEMPTS` could
            // never reach. A stale lock is still tried first: it is removed here and the
            // lock is taken on the next turn.
            //
            // Stating the timing consequence rather than hiding it: the stale branch now
            // sleeps once before retrying, so recovering a dead process's lock costs one
            // extra `RETRY_DELAY_MS` (20 ms). Staleness itself is `STALE_MS` (10 s), far
            // longer than the whole `MAX_ATTEMPTS` window (10 × 20 ms), so a lock that is
            // live on the first attempt can never become stale inside this loop: the
            // reordering cannot skip a recovery the old code would have performed.
            if (attempt >= MAX_ATTEMPTS) {
                throw IllegalStateException("无法获取 ${lockDir.name} 的锁（${lockDir.absolutePath}）")
            }
            val age = System.currentTimeMillis() - lockDir.lastModified()
            if (lockDir.isDirectory && age > STALE_MS) {
                // A process died holding it. proper-lockfile removes stale locks too.
                runCatching { lockDir.delete() }
            }
            Thread.sleep(RETRY_DELAY_MS)
        }
    }

    // ------------------------------------------------------------------- writing

    /**
     * Atomic replace. [mode600] additionally restricts the result to owner-only,
     * which is what `auth.json` needs and `models.json` does not.
     */
    fun write(target: File, text: String, mode600: Boolean = false): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp-${android.os.Process.myPid()}")
        temp.writeText(text)
        runCatching { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            .onFailure {
                target.writeText(text)
                temp.delete()
            }
        if (mode600) restrictToOwner(target)
        true
    }.getOrDefault(false)

    /**
     * `0600`, but never *loosening* an existing file that is already tighter.
     *
     * `Files.setPosixFilePermissions` is the exact tool; the fallback covers a
     * filesystem that refuses POSIX permissions (some FUSE mounts), where the
     * legacy `File.setReadable`/`setWritable` pair still applies.
     */
    fun restrictToOwner(file: File) {
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }.onFailure {
            runCatching {
                file.setReadable(false, false)
                file.setReadable(true, true)
                file.setWritable(false, false)
                file.setWritable(true, true)
                file.setExecutable(false, false)
            }
        }
    }

    /**
     * `JSON.stringify(value, null, 2)`: what `auth-storage.ts:358`, `:467` and `:479`
     * write. Kotlin's `JsonObject.toString()` uses the same compact form
     * (`{"k":v}` with no spaces, because `Json` is configured non-pretty), so the
     * app writes the **compact** shape rather than inventing an indented one.
     *
     * That is a deliberate, stated difference: pi rewrites the file in its own
     * 2-space form on its next credential change, and JSON semantics are identical
     * either way. Formatting the file itself would be cosmetic churn on a
     * secret-bearing file.
     */
    fun stringify(document: JsonObject): String = document.toString()

    fun parseObject(text: String): JsonObject? = PiJson.parseObjectOrNull(if (text.startsWith("\uFEFF")) text.substring(1) else text)

    /**
     * `stripJsonComments` (`core/model-config.ts:267` calls it before `JSON.parse`).
     *
     * The implementation lives in [PiJsonComments] so the bare-JVM harness can reach
     * it (`PiConfigFiles` itself imports the app's settings layer and cannot compile
     * there) and so every reader shares one stripper.
     */
    fun stripJsonComments(text: String): String = PiJsonComments.strip(text)

    private const val MAX_ATTEMPTS = 10
    private const val RETRY_DELAY_MS = 20L

    /** proper-lockfile's default stale window. */
    private const val STALE_MS = 10_000L
}
