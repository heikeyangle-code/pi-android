package app.pi.runtime

/**
 * The on-disk shape of the proroot probe gate's verdict, and its cache key.
 *
 * ## Why the key is what it is
 *
 * The gate answers "does proroot work **on this device, with these bytes, over this
 * guest tree, in this configuration, measured by these stages**". Four things can change
 * that answer, and the key has to cover the three the app can see:
 *
 *  - the **unpacked runtime revision** (`PiPaths.stampFile()`) — a new rootfs, a new
 *    Node, a new `rg`/`fd` binary, or a new pi engine all invalidate a verdict;
 *  - the **digest of the five proroot binaries** — an app update can replace them
 *    without touching the runtime tree at all, and a verdict about the old bytes
 *    says nothing about the new ones. (`docs/proroot-research.md` §3.1 is the
 *    reason this must not be a version string: the bytes on the reference device do
 *    not match any published release, so "v1.2.8" is not an identity.)
 *  - the **seccomp档** the launch ran under ([ProrootSeccomp.tag]) — the two档 answer
 *    different questions (one promises inline-`svc` translation, the other does not),
 *    so a verdict earned under one is not an answer about the other. It is in the key
 *    even though production pins [RuntimeChoice.PROROOT_SECCOMP], because a key that
 *    forgot it would make the档 invisible to the cache — the same defect as a row that
 *    says "proroot" while every launch falls back.
 *
 * The fourth — **which stages the verdict is made of** — cannot go in the key, because
 * the stages are a property of the *build*, not of the device. It is [VERSION] instead:
 * adding a stage changes what "PASS" means, so every earlier file must stop being
 * readable. `v3` is that bump (`ProrootExecProbe`).
 *
 * The last — the device's ROM and kernel — cannot change without a process restart
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
 * the round-trip, the rejection of a stale or malformed cache file, and that the two
 * seccomp档 cannot read each other's verdict.
 */
object ProrootProbeCache {

    /**
     * Bumped when the *meaning* of the stored verdict changes (a rule added to the
     * gate, a field reinterpreted). An old file then reads as "no verdict" instead
     * of being trusted.
     *
     * `v2` is the 档-aware key: every `v1` file was written by a probe whose verdict
     * could not tell the two seccomp configurations apart, so none of them is an
     * answer to a `v2` question — and a `v1` failure would otherwise keep proroot
     * disabled on a device whose real defect has since been fixed in `ProrootCommand`.
     *
     * `v3` is the **dynamic-binary** stage. A `v2` verdict was earned by a probe that
     * never executed the engine's own kind of binary (`ProrootExecProbe`), so it is not an
     * answer to a `v3` question — and the concrete consequence of trusting one is the one
     * this stage was added for: on 2026-09-19 a `v2` PASS was cached on a device where
     * `/opt/node/bin/node` could not be exec'd under proroot and every engine launch died
     * with exit code 126. A `v2` **failure** is equally stale in the other direction: it was
     * earned under a gate that could refuse proroot for a reason this build no longer
     * consults, so re-reading it would keep the switch off for a defect that is fixed.
     * Bumping the version is therefore the whole of the "a new stage invalidates old
     * verdicts" rule — the key needs no new field, because a verdict is only ever an answer
     * to the question *this* build asks.
     */
    const val VERSION = "v3"

    private const val PASS = "PASS"
    private const val FAIL = "FAIL"

    /**
     * The key a verdict is valid for.
     *
     * @param modeTag [ProrootSeccomp.tag] of the configuration the probe ran under.
     */
    fun key(revision: String, digest: String, modeTag: String): String =
        "$VERSION\t${modeTag.trim()}\t${revision.trim()}\t${digest.trim()}"

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
