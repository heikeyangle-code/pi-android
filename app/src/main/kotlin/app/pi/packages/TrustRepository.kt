package app.pi.packages

import app.pi.runtime.PtyLauncher
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Reads and writes pi's `trust.json`, in both places it can exist.
 *
 * ## Which copy is authoritative — this changed, and it matters
 *
 * pi reads `<agentDir>/trust.json` (`trust-manager.ts:212-214`). Since
 * `PiEngineHost` binds the durable agent dir over guest `/root/.pi/agent`
 * (`PiEngineHost.kt:285-294`), the file pi actually reads is
 *
 *   - [engineFile] — `<files>/pi/.pi/agent/trust.json`, the bind source. Durable
 *     across a runtime re-extract (`RuntimeProvisioner.kt:85-91` deletes
 *     `paths.runtime`), and the one the app's own settings store addresses too.
 *   - [rootfsFile] — `<rootfs>/root/.pi/agent/trust.json`, kept in step as a
 *     fallback for a run without the bind. While the engine runs it is **shadowed**,
 *     and a guest command now binds the same directory ([GuestCommand.bindList]), so
 *     nothing reads it in practice.
 *
 * This file previously read and locked the *rootfs* copy first, on the premise that
 * the agent dir was not bound. That premise is gone (`AgentLayout` records it), and
 * keeping the old order would have meant two real defects: a corrupt `engineFile`
 * would never be reported even though pi refuses to start on one, and the advisory
 * lock would sit next to a file pi never locks while pi locked the other one.
 *
 * ## Locking
 *
 * pi guards the store with an advisory lock at `<trust.json>.lock`, created as a
 * **directory** (`proper-lockfile`, `trust-manager.ts:137-176`), retrying 10 times
 * with a 20 ms sleep, and treating an existing lock as stale after its default 10 s
 * (`:140-142`). The app takes the same lock, on [engineFile]'s directory — the same
 * file pi locks — so a concurrent write cannot interleave: pi rewrites the whole file
 * from its in-memory copy, and a lost update here is a lost security decision.
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

    /**
     * `<files>/pi/.pi/agent/trust.json` — the bind source, i.e. **what pi reads**
     * while the engine runs (`PiEngineHost.kt:285-294`).
     */
    val engineFile: File get() = File(layout.agentMirrorDir, TRUST_FILE_NAME)

    /**
     * `<rootfs>/root/.pi/agent/trust.json` — the copy inside the tree
     * `RuntimeProvisioner.wipe()` deletes. Shadowed by the bind; written for
     * compatibility only.
     */
    val rootfsFile: File get() = File(layout.agentTruthDir, TRUST_FILE_NAME)

    /**
     * Where the store was read from. Descriptive only: no caller branches on it today
     * (`PiPackagesHost` surfaces [State.invalid] and the store's contents, not this),
     * and claiming otherwise here would be another "the doc says the UI does it".
     */
    enum class Source { EngineAgentDir, RootfsCopy, Missing }

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
     * The effective store, from the copy pi reads first ([engineFile]).
     *
     * An invalid authoritative copy is reported but **not** repaired here: pi would
     * throw on it at startup, and silently rewriting a file pi refuses to read would
     * hide a real problem. [repairInvalidStore] is the explicit, user-initiated fix.
     */
    fun read(): State {
        if (engineFile.isFile) {
            when (val engine = readFile(engineFile)) {
                is TrustFile.Parse.Ok -> return State(engine.store, Source.EngineAgentDir)
                is TrustFile.Parse.Invalid -> {
                    // The rootfs copy may still be readable; report the authoritative
                    // file's failure as the headline, because that is the one pi trips
                    // over.
                    val rootfs = readFile(rootfsFile)
                    val store = (rootfs as? TrustFile.Parse.Ok)?.store ?: emptyMap()
                    return State(store, Source.EngineAgentDir, invalid = engine)
                }
            }
        }
        if (rootfsFile.isFile) {
            return when (val rootfs = readFile(rootfsFile)) {
                is TrustFile.Parse.Ok -> State(rootfs.store, Source.RootfsCopy)
                is TrustFile.Parse.Invalid -> State(emptyMap(), Source.RootfsCopy, invalid = rootfs)
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
        /** True when the copy pi reads ([engineFile]) was written. */
        val engineWritten: Boolean,
        /** True when the rootfs fallback copy was written too. */
        val rootfsWritten: Boolean,
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
                engineWritten = false,
                rootfsWritten = false,
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
                engineWritten = false,
                rootfsWritten = false,
                message = "trust.json 无法解析，已拒绝写入以免破坏它：${existing.invalid.message}",
            )
        }
        val next = TrustFile.applyUpdates(existing.store, updates)
        val text = TrustFile.serialize(next)

        return try {
            withLock {
                val engineWritten = writeFile(engineFile, text)
                val rootfsWritten = writeFile(rootfsFile, text)
                WriteResult(
                    ok = engineWritten,
                    engineWritten = engineWritten,
                    rootfsWritten = rootfsWritten,
                    message = buildString {
                        append("已写入 ${engineFile.absolutePath}")
                        if (!rootfsWritten) {
                            append("；rootfs 副本写入失败（${rootfsFile.absolutePath}）")
                        }
                    },
                )
            }
        } catch (error: Throwable) {
            WriteResult(
                ok = false,
                engineWritten = false,
                rootfsWritten = false,
                message = "写入 trust.json 失败：${error.message ?: error::class.java.simpleName}",
            )
        }
    }

    /**
     * Archive an unparseable store and start over from the other copy, or from `{}`.
     *
     * This is not a convenience: pi **throws** on an invalid store
     * (`trust-manager.ts:107-121`), so a mangled `trust.json` breaks every pi
     * startup until it is dealt with. It archives the file pi reads ([engineFile]);
     * the rootfs copy is then overwritten with the repaired content so the two do not
     * disagree. The bad file is renamed aside rather than deleted, following the
     * precedent in `PiSettingsFileStore.kt:36-38`.
     */
    fun repairInvalidStore(): WriteResult {
        val state = read()
        val invalid = state.invalid ?: return WriteResult(true, false, false, "trust.json 可解析，无需修复")
        val archived = File(engineFile.parentFile, "$TRUST_FILE_NAME.invalid-${System.currentTimeMillis()}")
        val moved = runCatching {
            engineFile.parentFile?.mkdirs()
            Files.move(engineFile.toPath(), archived.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)
        if (!moved) {
            return WriteResult(false, false, false, "无法移开损坏的 ${engineFile.absolutePath}")
        }
        val text = TrustFile.serialize(state.store)
        val rewritten = writeFile(engineFile, text)
        val copied = runCatching { writeFile(rootfsFile, text) }.getOrDefault(false)
        return WriteResult(
            ok = rewritten,
            engineWritten = rewritten,
            rootfsWritten = copied,
            message = "已把损坏的文件移到 ${archived.absolutePath}，并用另一份内容重建。原始错误：${invalid.message}",
        )
    }

    /**
     * Push the authoritative copy into the rootfs, for the boot path after
     * provisioning.
     *
     * **Kept for compatibility, and it currently has no caller.** Since the engine
     * binds the durable directory over `/root/.pi/agent` (`PiEngineHost.kt:285-294`),
     * the rootfs copy is shadowed and this step is no longer load-bearing; a guest
     * command binds the same directory too ([GuestCommand.bindList]). It is left
     * because it is idempotent and harmless, and removing public API from this layer
     * is a separate decision.
     *
     * Idempotent, and a no-op when the rootfs copy already exists and parses: the
     * engine's copy is the one pi maintains, and overwriting a newer decision with a
     * stale copy would be a silent security regression.
     *
     * @return true when something was published.
     */
    fun publishIntoRootfs(): Boolean {
        if (!layout.paths.rootfs.isDirectory) return false
        val rootfsExists = rootfsFile.isFile
        if (rootfsExists) return false
        if (!engineFile.isFile) return false
        val engine = readFile(engineFile)
        if (engine is TrustFile.Parse.Invalid) return false
        val text = runCatching { engineFile.readText() }.getOrNull() ?: return false
        return writeFile(rootfsFile, text)
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
        // Everything else in the guest maps into the rootfs — with **one** exception
        // that this function is not asked about: the agent dir, which the engine binds
        // from `<files>/pi/.pi/agent` (`PiEngineHost.kt:285-294`). Every caller here
        // passes a path under the workspace (`hasTrustRequiringResources`), so the
        // exception never applies; a future caller that wants an agent-dir path must
        // go through [engineFile] and [rootfsFile] instead of this mapping.
        //
        // In particular `/workspace` alone is NOT the bind — the bind target is
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
        val temp = File(file.parentFile, "${file.name}.tmp-${android.os.Process.myPid()}")
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
        val lockDir = File(engineFile.parentFile, "$TRUST_FILE_NAME.lock")
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
