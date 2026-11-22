package app.pi.protocol

/**
 * Pure-JVM locale switch for `:protocol` user-facing strings (date separators,
 * error/compaction/branch notices, session entry summaries, flag errors).
 *
 * Defaults to Chinese (`true`) so bare-JVM `:protocol:test` assertions stay unchanged;
 * `:app` syncs this flag immediately inside `PiLocale.init()` and whenever the
 * user switches language in Settings → Appearance → Language.
 */
object ProtocolLocale {
    @Volatile
    var isChinese: Boolean = true

    fun tr(zh: String, en: String): String = if (isChinese) zh else en
}
