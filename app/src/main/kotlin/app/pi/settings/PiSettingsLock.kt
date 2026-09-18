package app.pi.settings

import java.io.File

/**
 * pi's `proper-lockfile` protocol, reproduced for the **settings** documents.
 *
 * ## What pi does, and what this mirrors
 *
 * `FileSettingsStorage.withLock` (`packages/coding-agent/src/core/settings-manager.ts:258-285`) takes
 * `lockfile.lockSync(path, { realpath: false })` around a **read-modify-write of the whole
 * document**, and releases it afterwards. `proper-lockfile` implements the lock as a
 * **directory** at `<path>.lock` (`mkdir` is atomic), refuses to steal a live lock, removes a
 * lock older than its 10 s stale window, and retries `ELOCKED` ten times with a 20 ms sleep
 * (`settings-manager.ts:235-256` — `maxAttempts = 10`, `delayMs = 20`). This does exactly that,
 * so the app and pi coordinate instead of overwriting each other.
 *
 * ## Why this is not `PiConfigFiles.withLock`
 *
 * That one is the same protocol and is already used for `auth.json`/`models.json`/the
 * credential path. The difference is the failure mode, and it is deliberate: it **throws**
 * when the lock cannot be taken, which is right for a credential write (better to refuse than
 * to interleave with pi), while a settings write must never drop what the user just did. So
 * this one is **best effort**: it retries the same ten times, and if the lock still cannot be
 * taken the caller's block runs anyway — reporting that through [withLock]'s `held` flag — and
 * the lost-update window is covered instead by re-reading the document *inside* the lock when
 * it was taken (`PiSettingsFileStore.writeLocked`). Keep the two in step if pi ever changes the
 * protocol: the lock path and the retry parameters are the contract, not this file's shape.
 */
object PiSettingsLock {

    /** `settings-manager.ts:237-238`. */
    private const val MAX_ATTEMPTS = 10

    /** `settings-manager.ts:238`. */
    private const val RETRY_DELAY_MS = 20L

    /** `proper-lockfile`'s default stale window, which pi relies on. */
    private const val STALE_MS = 10_000L

    /**
     * Run [block] under `<target>.lock`, mirroring pi's lock path and retry parameters.
     *
     * [block] receives `true` when the lock is really held (its writes are coordinated with
     * pi and with the app's other writers) and `false` when the lock could not be taken within
     * the retry budget — in which case the caller still runs, because refusing would lose the
     * user's change, and says so honestly through the flag instead of pretending.
     */
    fun <T> withLock(target: File, block: (held: Boolean) -> T): T {
        val lockDir = lockDirectoryFor(target)
        lockDir.parentFile?.mkdirs()
        var attempt = 0
        while (true) {
            attempt++
            if (runCatching { lockDir.mkdir() }.getOrDefault(false)) {
                return try {
                    block(true)
                } finally {
                    runCatching { lockDir.delete() }
                }
            }
            // A process died holding it: proper-lockfile removes stale locks too.
            val age = System.currentTimeMillis() - lockDir.lastModified()
            if (lockDir.isDirectory && age > STALE_MS) {
                runCatching { lockDir.delete() }
                continue
            }
            if (attempt >= MAX_ATTEMPTS) return block(false)
            runCatching { Thread.sleep(RETRY_DELAY_MS) }
        }
    }

    /** The lock directory pi's `proper-lockfile` creates for [target]: `<name>.lock` beside it. */
    fun lockDirectoryFor(target: File): File = File(target.parentFile, "${target.name}.lock")
}
