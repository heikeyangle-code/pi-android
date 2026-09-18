package app.pi.runtime

/**
 * The on-disk shape of the proroot probe gate's verdict, and its cache key.
 *
 * ## Why the key is what it is
 *
 * The gate answers "does proroot work **on this device, with these bytes, over this
 * guest tree**". Three things can change that answer, and the key has to cover the
 * two that the app can see:
 *
 *  - the **unpacked runtime revision** (`PiPaths.stampFile()`) — a new rootfs, a new
 *    Node, a new `rg`/`fd` binary, or a new pi engine all invalidate a verdict;
 *  - the **digest of the five proroot binaries** — an app update can replace them
 *    without touching the runtime tree at all, and a verdict about the old bytes
 *    says nothing about the new ones. (`docs/proroot-research.md` §3.1 is the
 *    reason this must not be a version string: the bytes on the reference device do
 *    not match any published release, so "v1.2.8" is not an identity.)
 *
 * The third — the device's ROM and kernel — cannot change without a process restart
 * at minimum, and a stale verdict there is precisely what the three-failure fallback
 * covers.
 *
 * ## Why the verdict is a file and not a preference
 *
 * It describes the *runtime tree*, so it lives in the volatile tree next to the
 * thing it describes and is deleted by `RuntimeProvisioner.wipe()` together with it
 * (`PiPaths.prorootProbeCache()`). A SharedPreferences entry would survive a wipe
 * and claim a pass for a tree that no longer exists — the same stale-pass shape the
 * self-check stamp was split out to avoid (`PiPaths.selfCheckStamp()`'s KDoc).
 *
 * Android-free: string in, string out, so the `proroot` harness can pin the key,
 * the round-trip, and the rejection of a stale or malformed cache file.
 */
object ProrootProbeCache {

    /**
     * Bumped when the *meaning* of the stored verdict changes (a rule added to the
     * gate, a field reinterpreted). An old file then reads as "no verdict" instead
     * of being trusted.
     */
    const val VERSION = "v1"

    private const val PASS = "PASS"
    private const val FAIL = "FAIL"

    /** The key a verdict is valid for. */
    fun key(revision: String, digest: String): String = "$VERSION\t${revision.trim()}\t${digest.trim()}"

    data class Cached(
        val key: String,
        val passed: Boolean,
        /** Human lines: the probe evidence. Rendered in the diagnostic report. */
        val detail: List<String>,
    )

    /** The file's text: key line, verdict line, then the evidence. */
    fun render(key: String, passed: Boolean, detail: List<String>): String = buildString {
        appendLine(key)
        appendLine(if (passed) PASS else FAIL)
        detail.forEach { appendLine(it) }
    }

    /**
     * A cached verdict, or null when the file is missing/empty/malformed or was
     * written for a different key.
     *
     * The [expectedKey] comparison is the whole point: a verdict is only an answer
     * to the question it was asked, and this is where a verdict about another
     * revision or another set of binaries stops being one.
     */
    fun parse(text: String?, expectedKey: String): Cached? {
        val lines = text?.lines()?.map { it.trimEnd('\r') } ?: return null
        if (lines.size < 2) return null
        val key = lines[0].trim()
        if (key.isEmpty() || key != expectedKey.trim()) return null
        val passed = when (lines[1].trim()) {
            PASS -> true
            FAIL -> false
            else -> return null
        }
        return Cached(key = key, passed = passed, detail = lines.drop(2).filter { it.isNotBlank() })
    }
}
