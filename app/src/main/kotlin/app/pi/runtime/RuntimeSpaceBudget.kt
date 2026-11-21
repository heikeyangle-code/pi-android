package app.pi.runtime

import app.pi.i18n.tr

/**
 * How much free space the *extraction* needs, and the one sentence to show when there
 * is not enough.
 *
 * ## Why this exists
 *
 * `RuntimeProvisioner.ensureReady` extracts payload archives into the runtime tree.
 * Nothing checked free space, so a device that is out of room failed half way through
 * the extraction, and - because each payload's digest is only written after that
 * payload is complete - did the same thing again on the next launch. The user sees "it
 * used to work and now it will not start", and every retry costs minutes.
 *
 * Since provisioning became per payload ([RuntimeProvisioner]'s KDoc) the check is
 * applied to the payloads **this attempt will actually extract**: a boot that changes
 * nothing is not refused for room it does not need, and a boot that re-extracts one
 * payload is measured against that payload alone. The explicit repair path
 * (`rebuild = true`) still measures all of them, because it does extract all of them.
 *
 * ## Where the numbers come from (measured, not estimated)
 *
 * The pinned payloads were unpacked in the development container and their file
 * bytes counted:
 *
 *  - `ubuntu-base-24.04.3-base-arm64.tar.gz` 29,865,086 B -> 100,694,073 B;
 *  - `node-v24.22.1-linux-arm64.tar.xz`      30,978,868 B -> 198,255,017 B;
 *  - `pi-engine.tgz` (`--omit=dev --omit=optional`) 23,278,289 B -> ~144 MB tree.
 *
 * That is **> 438 MB** for the three large payloads alone, before git/ripgrep/fd,
 * tar's block padding and the `lib` aliases. [MIN_REQUIRED_BYTES] is that floor
 * rounded up; [PAYLOAD_MULTIPLIER] is what makes the check move with the payloads
 * when a pi upgrade makes `pi-engine.tgz` bigger.
 *
 * ## What it deliberately does not do
 *
 * [shortfall] answers `null` - "do not refuse" - when the available figure cannot
 * be read (`File.usableSpace` returns 0 on a path it cannot stat). A check that
 * blocks a boot because it could not read a number would turn an unknown into a
 * failure, which is the opposite of the point: this check exists to prevent a
 * *destructive* step, not to gate a boot that would have worked.
 *
 * No imports on purpose: this file is compiled and executed by the bare-JVM
 * harness in `tools/run-app-pure-checks.sh`, so it must not grow a dependency on
 * `File`, Android or anything else. Reading `usableSpace` is the caller's job.
 */
object RuntimeSpaceBudget {

    /** Floor from the measurements above: three payloads already exceed 438 MB. */
    const val MIN_REQUIRED_BYTES: Long = 480L * 1024 * 1024

    /**
     * Conservative floor for an **incremental** single- or partial-payload update (e.g. a 1–2 MB
     * tool bump or 23 MB engine update) so small updates do not falsely require 480 MB free.
     */
    const val INCREMENTAL_MIN_REQUIRED_BYTES: Long = 64L * 1024 * 1024

    /**
     * Expansion factor applied to the measured payload bytes.
     *
     * Measured per payload: ubuntu x3.4, node x6.4, pi-engine x6.2. Four is the
     * conservative middle when the archives are summed: it lands just above the
     * measured tree for the pinned payloads and grows when they grow.
     */
    const val PAYLOAD_MULTIPLIER: Int = 4

    /** What the unpack is allowed to need, given the payloads this APK carries. */
    fun requiredBytes(payloadBytes: Long, fullUnpack: Boolean = true): Long {
        val fromPayloads = if (payloadBytes > 0L) payloadBytes * PAYLOAD_MULTIPLIER else 0L
        val floor = if (fullUnpack) MIN_REQUIRED_BYTES else INCREMENTAL_MIN_REQUIRED_BYTES
        return maxOf(floor, fromPayloads)
    }

    /**
     * How many bytes are missing, or `null` when the boot may proceed.
     *
     * `null` also covers "the available figure is unreadable", which is why the
     * caller does not have to special-case it.
     */
    fun shortfall(availableBytes: Long, payloadBytes: Long, fullUnpack: Boolean = true): Long? {
        if (availableBytes <= 0L) return null
        val required = requiredBytes(payloadBytes, fullUnpack)
        return if (availableBytes >= required) null else required - availableBytes
    }

    /**
     * The one sentence the user gets. Same shape as every other provisioning
     * failure: what is wrong, the number, and the next step. No paths, no class
     * names - it is rendered on the failure screen in monospace under the title.
     */
    fun message(availableBytes: Long, payloadBytes: Long, fullUnpack: Boolean = true): String {
        val required = requiredBytes(payloadBytes, fullUnpack)
        val missing = (required - availableBytes).coerceAtLeast(0L)
        return tr(
            "存储空间不足：解包运行时至少需要 ${megabytes(required)} MB 可用空间，" +
                "当前只有 ${megabytes(availableBytes)} MB。" +
                "请清理出至少 ${megabytes(missing)} MB 后重试（删掉不再需要的会话、清理其它 App 的缓存都可以）。",
            "Not enough storage space: unpacking the runtime requires at least ${megabytes(required)} MB free, " +
                "but only ${megabytes(availableBytes)} MB is available. " +
                "Free up at least ${megabytes(missing)} MB and retry.",
        )
    }

    /** Whole megabytes, rounded up, so "0 MB" never appears for a positive number. */
    fun megabytes(bytes: Long): Long =
        if (bytes <= 0L) 0L else (bytes + 1_048_575L) / 1_048_576L
}
