package app.pi.packages

import app.pi.runtime.PtyLauncher
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Reads and writes pi's `trust.json`, in both places it has to exist.
 *
 * ## Two files, one truth
 *
 * [AgentLayout] records the mismatch: pi reads `/root/.pi/agent/trust.json`, which
 * on the host is `<rootfs>/root/.pi/agent/trust.json` — inside the tree that
 * `RuntimeProvisioner.wipe()` deletes on every runtime revision bump
 * (`RuntimeProvisioner.kt:85-91`). The durable copy is `<files>/pi/.pi/agent/`,
 * which pi never reads today.
 *
 * So a write goes to both, and a read prefers the guest copy with the mirror as
 * fallback. [publishIntoRootfs] exists for the boot path: after provisioning, push
 * the durable copy back into the fresh rootfs. Without that step a user's trust
 * decisions would appear to work and then vanish after any app update that ships a
 * new runtime.
 *
 * ## Locking
 *
 * pi guards the store with an advisory lock at `<trust.json>.lock`, created as a
 * **directory** (`proper-lockfile`, `trust-manager.ts:137-176`), retrying 10 times
 * with a 20 ms sleep, and treating an existing lock as stale after its default 10 s
 * (`:140-142`). The app takes the same lock, the same way, so a concurrent write
 * cannot interleave: pi rewrites the whole file from its in-memory copy, and a
 * lost update here is a lost security decision.
 *
 * ## What this deliberately does not do
 *
 * It never auto-trusts anything. `docs/extension-compatibility.md` R4 is explicit:
 * the appearance of `.pi/extensions` is exactly the attacker-controlled case, and
 * resolving it must stay a human decision. Nothing in this file has a code path
 * that writes `true` without an explicit call carrying a user's answer.
 */
class TrustRepository(
    private val layout: AgentLayout,
    /** Used only to canonicalise a path the way pi does; may be null in tests. */
    private val guest: GuestCommand? = null,
) {

    /** `<files>/pi/runtime/rootfs/root/.pi/agent/trust.json` — what pi actually reads. */
    val truthFile: File get() = File(layout.agentTruthDir, TRUST_FILE_NAME)

    /** `<files>/pi/.pi/agent/trust.json` — durable across a runtime re-extract. */
    val mirrorFile: File get() = File(layout.agentMirrorDir, TRUST_FILE_NAME)

    /** Where the store was read from; the UI uses it to say which file is authoritative. */
    enum class Source { GuestTruth, DurableMirror, Missing }

    data class State(
        val store: TrustStore,
        val source: Source,
        /** Non-null when a file exists but is not a valid store. pi *throws* on this. */
        val invalid: TrustFile.Parse.Invalid? = null,
    ) {
        val healthy: Boolean get() = invalid == null
    }

    // ------------------------------------------------------------------ reading

    /**
     * The effective store. An invalid guest copy is reported but **not** repaired
     * here: pi would throw on it at startup, and silently rewriting a file pi
     * refuses to read would hide a real problem. [repairInvalidStore] is the
     * explicit, user-initiated fix.
     */
    fun read(): State {
        if (truthFile.isFile) {
            when (val truth = readFile(truthFile)) {
                is TrustFile.Parse.Ok -> return State(truth.store, Source.GuestTruth)
                is TrustFile.Parse.Invalid -> {
                    // The mirror may still be readable; report the truth file's
                    // failure as the headline, because that is the one pi trips over.
                    val mirror = readFile(mirrorFile)
                    val store = (mirror as? TrustFile.Parse.Ok)?.store ?: emptyMap()
                    return State(store, Source.GuestTruth, invalid = truth)
                }
            }
        }
        if (mirrorFile.isFile) {
            return when (val mirror = readFile(mirrorFile)) {
                is TrustFile.Parse.Ok -> State(mirror.store, Source.DurableMirror)
                is TrustFile.Parse.Invalid -> State(emptyMap(), Source.DurableMirror, invalid = mirror)
            }
        }
        return State(emptyMap(), Source.Missing)
    }

    private fun readFile(file: File): TrustFile.Parse {
        if (!file.isFile) return TrustFile.Parse.Ok(emptyMap())
        val text = runCatching { file.readText() }.getOrElse { error ->
            return TrustFile.Parse.Invalid(
                "Failed to read trust store ${file.absolutePath}: ${error.message ?: error::class.java.simpleName}",
            )
        }
        return TrustFile.parse(text, file.absolutePath)
    }

    /** The saved decision for [canonicalCwd], nearest ancestor wins. */
    fun decisionFor(canonicalCwd: String): Boolean? =
        TrustFile.nearest(read().store, canonicalCwd)?.decision

    // ------------------------------------------------------------------ writing

    data class WriteResult(
        val ok: Boolean,
        val guestWritten: Boolean,
        val mirrorWritten: Boolean,
        val message: String,
    )

    /**
     * Apply one prompt choice. [ProjectTrust.Option.updates] already carries the
     * `null` that deletes an entry, so this is a plain [TrustFile.applyUpdates].
     */
    fun apply(option: ProjectTrust.Option, canonicalCwd: String): WriteResult {
        if (option.updates.isEmpty()) {
            // Session-only: pi stores nothing (`trust-manager.ts:84`, `:93`). The
            // app must not invent a record pi would not have written.
            return WriteResult(
                ok = true,
                guestWritten = false,
                mirrorWritten = false,
                message = "本次会话有效，未写入 trust.json",
            )
        }
        return setMany(option.updates)
    }

    /** `setMany` (`trust-manager.ts:231-244`), under the same lock, to both files. */
    fun setMany(updates: List<Pair<String, Boolean?>>): WriteResult {
        val existing = read()
        if (existing.invalid != null) {
            return WriteResult(
                ok = false,
                guestWritten = false,
                mirrorWritten = false,
                message = "trust.json 无法解析，已拒绝写入以免破坏它：${existing.invalid.message}",
            )
        }
        val next = TrustFile.applyUpdates(existing.store, updates)
        val text = TrustFile.serialize(next)

        return try {
            withLock {
                val guestWritten = writeFile(truthFile, text)
                val mirrorWritten = writeFile(mirrorFile, text)
                WriteResult(
                    ok = guestWritten,
                    guestWritten = guestWritten,
                    mirrorWritten = mirrorWritten,
                    message = buildString {
                        append("已写入 ${truthFile.absolutePath}")
                        if (!mirrorWritten) append("；镜像写入失败（${mirrorFile.absolutePath}）")
                    },
                )
            }
        } catch (error: Throwable) {
            WriteResult(
                ok = false,
                guestWritten = false,
                mirrorWritten = false,
                message = "写入 trust.json 失败：${error.message ?: error::class.java.simpleName}",
            )
        }
    }

    /**
     * Archive an unparseable store and start over from the mirror, or from `{}`.
     *
     * This is not a convenience: pi **throws** on an invalid store
     * (`trust-manager.ts:107-121`), so a mangled `trust.json` breaks every pi
     * startup until it is dealt with. The bad file is renamed aside rather than
     * deleted, following the precedent in `PiSettingsFileStore.kt:36-38`.
     */
    fun repairInvalidStore(): WriteResult {
        val state = read()
        val invalid = state.invalid ?: return WriteResult(true, false, false, "trust.json 可解析，无需修复")
        val archived = File(truthFile.parentFile, "$TRUST_FILE_NAME.invalid-${System.currentTimeMillis()}")
        val moved = runCatching {
            truthFile.parentFile?.mkdirs()
            Files.move(truthFile.toPath(), archived.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)
        if (!moved) {
            return WriteResult(false, false, false, "无法移开损坏的 ${truthFile.absolutePath}")
        }
        val rewritten = writeFile(truthFile, TrustFile.serialize(state.store))
        runCatching { writeFile(mirrorFile, TrustFile.serialize(state.store)) }
        return WriteResult(
            ok = rewritten,
            guestWritten = rewritten,
            mirrorWritten = true,
            message = "已把损坏的文件移到 ${archived.absolutePath}，并用镜像内容重建。原始错误：${invalid.message}",
        )
    }

    /**
     * Push the durable copy into the rootfs, for the boot path after provisioning.
     *
     * Idempotent, and a no-op when the rootfs copy already exists and parses: the
     * guest copy is the one pi maintains, and overwriting a newer decision with a
     * stale mirror would be a silent security regression.
     *
     * @return true when something was published.
     */
    fun publishIntoRootfs(): Boolean {
        if (!layout.paths.rootfs.isDirectory) return false
        val truthExists = truthFile.isFile
        if (truthExists) return false
        if (!mirrorFile.isFile) return false
        val mirror = readFile(mirrorFile)
        if (mirror is TrustFile.Parse.Invalid) return false
        val text = runCatching { mirrorFile.readText() }.getOrNull() ?: return false
        return writeFile(truthFile, text)
    }

    // -------------------------------------------------------- canonicalisation

    /**
     * `canonicalizePath(resolvePath(cwd))` (`trust-manager.ts:40-42`): `realpathSync`
     * when the path exists, and the input unchanged when it does not
     * (`utils/paths.ts:28-34`).
     *
     * This asks the **guest** to resolve it — `readlink -f` inside the same
     * namespace pi runs in — because a host-side realpath of the bind source is not
     * the path pi will hash. When no runtime is available, the literal path is
     * returned, which is pi's own fallback for a path that cannot be resolved.
     */
    fun canonicalizeGuestPath(guestPath: String): String {
        val command = guest ?: return guestPath
        val outcome = command.run(
            guestCommand = "readlink -f ${PtyLauncher.Shell.quote(guestPath)}",
            cwd = "/",
            timeoutMs = 20_000,
        )
        if (!outcome.ok) return guestPath
        val resolved = outcome.stdout.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("/") }
            ?: return guestPath
        return resolved
    }

    // ------------------------------------------------------------------ triggers

    /**
     * `hasTrustRequiringProjectResources` (`trust-manager.ts:185-207`).
     *
     * Two conditions, in pi's order:
     *
     *  1. any of `settings.json`, `extensions`, `skills`, `prompts`, `themes`,
     *     `SYSTEM.md`, `APPEND_SYSTEM.md` under `<cwd>/.pi` (`:190-193`, list at
     *     `:30-38`);
     *  2. `.agents/skills` in `cwd` **or any ancestor**, excluding the user's own
     *     `$HOME/.agents/skills`, which pi treats as a trusted user resource
     *     (`:186-188`, `:195-206`).
     *
     * A bare `.pi` directory is not enough — that is the detail that makes this
     * function necessary rather than a simple `File(".pi").exists()`.
     */
    fun hasTrustRequiringResources(guestCwd: String): Boolean {
        val configDir = "$guestCwd/${ProjectTrust.CONFIG_DIR_NAME}"
        if (ProjectTrust.TRUST_REQUIRING_CONFIG_ENTRIES.any { guestExists("$configDir/$it") }) return true

        // HOME is /root in the guest (`ProotCommand.environment`), so this is
        // /root/.agents/skills — the one directory pi skips.
        val userSkills = "${layout.guestHome}/.agents/skills"
        var current = guestCwd
        while (true) {
            val candidate = "$current/.agents/skills"
            if (candidate != userSkills && guestExists(candidate)) return true
            val parent = TrustFile.posixDirname(current)
            if (parent == current) return false
            current = parent
        }
    }

    /** A guest path mapped to the host file that holds it. See the bind note below. */
    fun hostFileFor(guestPath: String): File {
        val (hostRoot, guestRoot) = layout.workspaceBind()
        if (guestPath == guestRoot || guestPath.startsWith("$guestRoot/")) {
            val rel = guestPath.removePrefix(guestRoot).trimStart('/')
            return if (rel.isEmpty()) File(hostRoot) else File(hostRoot, rel)
        }
        // Everything else in the guest is the rootfs itself. In particular
        // `/workspace` alone is NOT the bind — the bind target is
        // `/workspace/pi/workspaces/workspace-1`, so a path one level up sits in
        // the rootfs. Getting this wrong would make a trigger check look at the
        // app's own filesDir.
        return File(layout.paths.rootfs, guestPath.trimStart('/'))
    }

    private fun guestExists(guestPath: String): Boolean =
        runCatching { hostFileFor(guestPath).exists() }.getOrDefault(false)

    // --------------------------------------------------------------- internals

    /**
     * Atomic replace: write a sibling temp file, then rename. pi uses a bare
     * `writeFileSync`, so a kill mid-write truncates the store and pi then throws
     * on every startup (`trust-manager.ts:133-134`). The app already declined that
     * trade for `settings.json` (`PiSettingsFileStore.kt:33-35`); the same reasoning
     * applies to the file that gates code execution.
     */
    private fun writeFile(file: File, text: String): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp-${ProcessHandle.current().pid()}")
        temp.writeText(text)
        runCatching { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            .onFailure {
                file.writeText(text)
                temp.delete()
            }
        true
    }.getOrDefault(false)

    /**
     * `acquireTrustLockSync`/`withTrustFileLock` (`trust-manager.ts:137-176`): the
     * lock is a directory at `<trust.json>.lock`, and proper-lockfile's default
     * stale window is 10 s.
     */
    private fun <T> withLock(block: () -> T): T {
        val lockDir = File(truthFile.parentFile, "$TRUST_FILE_NAME.lock")
        lockDir.parentFile?.mkdirs()
        var attempt = 0
        while (true) {
            attempt++
            val acquired = runCatching { lockDir.mkdir() }.getOrDefault(false)
            if (acquired) {
                return try {
                    block()
                } finally {
                    runCatching { lockDir.delete() }
                }
            }
            // Stale: a process that died holding it. proper-lockfile checks the
            // lock directory's mtime against a 10 s default and removes it itself;
            // matching that is what keeps pi and the app from deadlocking on each
            // other's leftovers after a crash.
            val age = System.currentTimeMillis() - lockDir.lastModified()
            if (lockDir.isDirectory && age > LOCK_STALE_MS) {
                runCatching { lockDir.delete() }
                continue
            }
            // A *live* lock is never stolen. pi's own behaviour at maxAttempts is to
            // throw (`trust-manager.ts:152-156`), and stealing here would be strictly
            // worse than failing: the app would race pi's rewrite of the whole store
            // and could lose a trust decision.
            if (attempt >= MAX_LOCK_ATTEMPTS) {
                throw IllegalStateException("无法获取 trust.json 的锁（${lockDir.absolutePath}）")
            }
            Thread.sleep(LOCK_RETRY_DELAY_MS)
        }
    }

    companion object {
        const val TRUST_FILE_NAME = "trust.json"

        /** `trust-manager.ts:140-141`: `maxAttempts = 10`, `delayMs = 20`. */
        private const val MAX_LOCK_ATTEMPTS = 10
        private const val LOCK_RETRY_DELAY_MS = 20L

        /** proper-lockfile's default stale window. */
        private const val LOCK_STALE_MS = 10_000L
    }
}
