package app.pi.rpc

/**
 * Pure-JVM locale switch for `:rpc` user-facing strings (date separators,
 * error/compaction/branch notices, session entry summaries, flag errors).
 *
 * Defaults to Chinese (`true`) so bare-JVM `:rpc:test` assertions stay unchanged;
 * `:app` syncs this flag immediately inside `PiLocale.init()` and whenever the
 * user switches language in Settings → Appearance → Language.
 */
object RpcLocale {
    @Volatile
    var isChinese: Boolean = true

    fun tr(zh: String, en: String): String = if (isChinese) zh else en
}
