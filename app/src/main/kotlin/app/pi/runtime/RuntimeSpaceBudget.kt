package app.pi.runtime

/**
 * How much free space the *unpack* needs, and the one sentence to show when there
 * is not enough.
 *
 * ## Why this exists
 *
 * `RuntimeProvisioner.ensureReady` deletes the whole runtime tree (`wipe()`) and
 * then extracts six payload archives into it. Nothing checked free space, so a
 * device that is out of room loses the runtime it already had, fails half way
 * through the new one, and - because the revision stamp is only written at the very
 * end - does the same thing again on the next launch. The user sees "it used to
 * work and now it will not start", and every retry costs minutes.
 *
 * ## Where the numbers come from (measured, not estimated)
 *
 * The pinned payloads were unpacked in the development container and their file
 * bytes counted:
 *
 *  - `ubuntu-base-24.04.3-base-arm64.tar.gz` 29,865,086 B -> 100,694,073 B;
 *  - `node-v24.19.0-linux-arm64.tar.xz`      30,553,480 B -> 194,612,147 B;
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
     * Expansion factor applied to the measured payload bytes.
     *
     * Measured per payload: ubuntu x3.4, node x6.4, pi-engine x6.2. Four is the
     * conservative middle when the archives are summed: it lands just above the
     * measured tree for the pinned payloads and grows when they grow.
     */
    const val PAYLOAD_MULTIPLIER: Int = 4

    /** What the unpack is allowed to need, given the payloads this APK carries. */
    fun requiredBytes(payloadBytes: Long): Long {
        val fromPayloads = if (payloadBytes > 0L) payloadBytes * PAYLOAD_MULTIPLIER else 0L
        return maxOf(MIN_REQUIRED_BYTES, fromPayloads)
    }

    /**
     * How many bytes are missing, or `null` when the boot may proceed.
     *
     * `null` also covers "the available figure is unreadable", which is why the
     * caller does not have to special-case it.
     */
    fun shortfall(availableBytes: Long, payloadBytes: Long): Long? {
        if (availableBytes <= 0L) return null
        val required = requiredBytes(payloadBytes)
        return if (availableBytes >= required) null else required - availableBytes
    }

    /**
     * The one sentence the user gets. Same shape as every other provisioning
     * failure: what is wrong, the number, and the next step. No paths, no class
     * names - it is rendered on the failure screen in monospace under the title.
     */
    fun message(availableBytes: Long, payloadBytes: Long): String {
        val required = requiredBytes(payloadBytes)
        val missing = (required - availableBytes).coerceAtLeast(0L)
        return "存储空间不足：解包运行时至少需要 ${megabytes(required)} MB 可用空间，" +
            "当前只有 ${megabytes(availableBytes)} MB。" +
            "请清理出至少 ${megabytes(missing)} MB 后重试（删掉不再需要的会话、清理其它 App 的缓存都可以）。"
    }

    /** Whole megabytes, rounded up, so "0 MB" never appears for a positive number. */
    fun megabytes(bytes: Long): Long =
        if (bytes <= 0L) 0L else (bytes + 1_048_575L) / 1_048_576L
}
