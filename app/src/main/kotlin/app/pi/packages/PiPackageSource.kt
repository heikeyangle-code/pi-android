package app.pi.packages

/**
 * What a `pi install` argument means, classified the way pi classifies it.
 *
 * pi's `DefaultPackageManager.parseSource` (`core/package-manager.ts:1446-1471`) is
 * the authority, and this is a transcription of it:
 *
 * ```
 * npm:   -> npm source, spec after the prefix, name/version split by parseNpmSpec
 * local  -> if isLocalPath(source)                       (utils/paths.ts:50-64)
 * git    -> else if parseGitUrl(source) succeeds          (utils/git.ts:172-...)
 * local  -> otherwise, and the *whole string* becomes a path
 * ```
 *
 * That last line is a real pi behaviour worth stating, because it is surprising
 * and the app must not paper over it: `pi install https://example.com/pkg.tar.gz`
 * is **not** a download. `parseGitUrl` requires a git protocol URL or a `git:`
 * prefix, so an ordinary https URL falls through to `local`, and `install()` then
 * throws `Path does not exist: <resolved>` (`package-manager.ts:1018-1023`). The
 * app reports that, in pi's words, instead of inventing "URL downloads are not
 * supported yet".
 *
 * ## This classification is not a gate
 *
 * The app never *rejects* a spec because of this type. pi is the parser; if the
 * two disagree, pi wins and its own error is surfaced verbatim. The type is used
 * for (a) the label on a list row, (b) deciding whether `npm install` will run,
 * and (c) knowing that only npm and git sources have an installed path to report.
 * Pre-flight refusal is limited to input that pi cannot accept at all — see
 * [validate].
 */
sealed interface PiPackageSource {

    /** The argument exactly as the user typed it; this is what settings records. */
    val raw: String

    /**
     * `npm:@scope/name@version`. [spec] is what follows `npm:`; [name] is the
     * package identity pi dedupes on (`package-manager.ts:224-230`, `:1731-1739`).
     */
    data class Npm(
        override val raw: String,
        val spec: String,
        val name: String,
        val version: String?,
        /** True for an exact version (`semver.valid`), false for a range/tag. */
        val pinned: Boolean,
    ) : PiPackageSource

    /**
     * A git source. [repo] is the clone URL pi would pass to `git clone`
     * (`utils/git.ts:116-123`); [ref] is a pinned tag or commit.
     */
    data class Git(
        override val raw: String,
        val repo: String,
        val host: String,
        val path: String,
        val ref: String?,
    ) : PiPackageSource

    /**
     * Anything else: an absolute path, a relative path, a `file:` URL, or a bare
     * name. pi resolves it against the settings file's own directory for storage
     * (`package-manager.ts:1435-1444`) and against the process cwd for install.
     */
    data class Local(override val raw: String) : PiPackageSource

    companion object {

        fun parse(source: String): PiPackageSource {
            val trimmed = source.trim()

            if (trimmed.startsWith(NPM_PREFIX)) {
                val spec = trimmed.substring(NPM_PREFIX.length).trim()
                val name = npmPackageName(spec)
                val version = npmVersionPart(spec)
                return PiPackageSource.Npm(
                    raw = source,
                    spec = spec,
                    name = name,
                    version = version,
                    pinned = isExactNpmVersion(version),
                )
            }

            if (isLocalPath(trimmed)) return Local(source)

            val git = parseGitUrl(trimmed)
            if (git != null) {
                return Git(raw = source, repo = git.repo, host = git.host, path = git.path, ref = git.ref)
            }

            return Local(source)
        }

        /**
         * `utils/paths.ts:50-64`, verbatim including the asymmetry: `github:` is
         * treated as non-local there even though `parseGitUrl` will not accept it
         * either, so `github:x/y` ends up a local path that does not exist. Faithful
         * to pi on purpose.
         */
        fun isLocalPath(value: String): Boolean {
            val trimmed = value.trim()
            return !LOCAL_EXEMPT_PREFIXES.any { trimmed.startsWith(it) }
        }

        private val LOCAL_EXEMPT_PREFIXES = listOf(
            "npm:",
            "git:",
            "github:",
            "http:",
            "https:",
            "ssh:",
        )

        const val NPM_PREFIX = "npm:"

        /**
         * `parseNpmSpec` (`package-manager.ts:1731-1739`).
         *
         * The regex splits `@scope/name@1.2.3` into name `@scope/name`, version
         * `1.2.3`, and leaves an unscoped `name` alone.
         */
        fun npmPackageName(spec: String): String {
            val match = NPM_SPEC.matchEntire(spec) ?: return spec
            return match.groupValues[1].ifEmpty { spec }
        }

        /** The `@version` half, or null. */
        fun npmVersionPart(spec: String): String? {
            val match = NPM_SPEC.matchEntire(spec) ?: return null
            return match.groupValues[2].ifEmpty { null }
        }

        private val NPM_SPEC = Regex("^(@?[^@]+(?:/[^@]+)?)(?:@(.+))?$")

        /**
         * `isExactNpmVersion` is `semver.valid(version) !== null`
         * (`package-manager.ts:59-61`), so this answers "will pi treat the spec as
         * pinned", not "does it look numeric". pi consumes the flag by **skipping**
         * those packages on update (`package-manager.ts:1104`, `:1210`), which is
         * exactly what the list row claims (`PiPackagesScreen`'s 精确版本 / 范围标签
         * label), so a wrong answer here is a wrong sentence on screen.
         *
         * The grammar is node-semver's **strict** FULL shape — the library pi depends
         * on is semver 7.8.5 (`package-lock.json:4223-4234`), whose
         * `FULLPLAIN = v?MAINVERSION PRERELEASE? BUILD?` and whose `valid()` parses
         * with the non-loose regex:
         *
         *  - an optional lowercase `v` (not `=`; that is only in the LOOSE shape);
         *  - three dot-separated numeric components, `0|[1-9]\d*`, each within
         *    `Number.MAX_SAFE_INTEGER`;
         *  - an optional `-prerelease`: dot-separated identifiers, each either
         *    `0|[1-9]\d*` or `\d*[a-zA-Z-][a-zA-Z0-9-]*` (so an all-digit identifier
         *    may not have a leading zero);
         *  - an optional `+build`: dot-separated `[a-zA-Z0-9-]+` identifiers;
         *  - the raw input at most 256 characters (semver checks this before trimming).
         *
         * All of the digit classes are ASCII on purpose: node's `\d` is `0-9`, while
         * Kotlin's [Char.isDigit] is Unicode-aware and would accept other scripts'
         * numerals that semver rejects.
         *
         * `v1.2.3` and `1.2.3-rc.1+build` are valid, so pi pins them (the case this
         * used to get wrong); `1.2`, `^1.2`, `latest`, `01.2.3`, `=1.2.3`, `1.2.3.4`
         * and `1.2.3-` are not, so pi keeps updating them.
         */
        fun isExactNpmVersion(version: String?): Boolean {
            if (version == null) return false
            if (version.length > SEMVER_MAX_LENGTH) return false

            var body = version.trim()
            if (body.startsWith("v")) body = body.substring(1)

            val plus = body.indexOf('+')
            val build = if (plus >= 0) body.substring(plus + 1) else null
            if (plus >= 0) body = body.substring(0, plus)

            val dash = body.indexOf('-')
            val prerelease = if (dash >= 0) body.substring(dash + 1) else null
            if (dash >= 0) body = body.substring(0, dash)

            val core = body.split('.')
            if (core.size != 3) return false
            if (!core.all { isNumericIdentifier(it) }) return false
            if (prerelease != null && !isPrerelease(prerelease)) return false
            if (build != null && !isBuildMetadata(build)) return false
            return true
        }

        /** semver's `NUMERICIDENTIFIER`, `0|[1-9]\d*`, bounded by `MAX_SAFE_INTEGER`. */
        private fun isNumericIdentifier(value: String): Boolean {
            if (value.isEmpty()) return false
            if (value.length > 1 && value[0] == '0') return false
            if (!value.all { isAsciiDigit(it) }) return false
            val number = value.toLongOrNull() ?: return false
            return number <= SEMVER_MAX_SAFE_INTEGER
        }

        /**
         * semver's `PRERELEASE`: one or more dot-separated identifiers, each
         * `0|[1-9]\d*` or `\d*[a-zA-Z-][a-zA-Z0-9-]*`.
         */
        private fun isPrerelease(value: String): Boolean {
            if (value.isEmpty()) return false
            return value.split('.').all { identifier ->
                if (identifier.isEmpty()) return@all false
                if (identifier.all { isAsciiDigit(it) }) {
                    isNumericIdentifier(identifier)
                } else {
                    identifier.all { isIdentifierChar(it) }
                }
            }
        }

        /** semver's `BUILD`: dot-separated `[a-zA-Z0-9-]+` identifiers. */
        private fun isBuildMetadata(value: String): Boolean {
            if (value.isEmpty()) return false
            return value.split('.').all { identifier ->
                identifier.isNotEmpty() && identifier.all { isIdentifierChar(it) }
            }
        }

        /** `\d` in node-semver is ASCII; [Char.isDigit] is not. */
        private fun isAsciiDigit(char: Char): Boolean = char in '0'..'9'

        /** node-semver's `LETTERDASHNUMBER`. */
        private fun isIdentifierChar(char: Char): Boolean =
            isAsciiDigit(char) || (char in 'a'..'z') || (char in 'A'..'Z') || char == '-'

        /** semver's `MAX_LENGTH` (`constants.js`), checked before trimming. */
        private const val SEMVER_MAX_LENGTH = 256

        /** `Number.MAX_SAFE_INTEGER`, which semver rejects components above. */
        private const val SEMVER_MAX_SAFE_INTEGER = 9007199254740991L

        // ------------------------------------------------------------------ git
        //
        // A transcription of the reachable half of `parseGitUrl`
        // (`utils/git.ts:172-...`, `:104-163`). `hostedGitInfo` handles a few extra
        // hosted shorthands (`gist:`, `bitbucket:`); those fall through to local
        // here. That is safe in one direction only — the app must never claim a
        // spec is invalid, so [validate] refuses nothing on account of this.

        fun parseGitUrl(source: String): PiPackageSource.Git? {
            val trimmed = source.trim()
            val hasPrefix = trimmed.startsWith("git:")
            val url = if (hasPrefix) trimmed.substring(4).trim() else trimmed
            if (!hasPrefix && !PROTOCOL_URL.containsMatchIn(url)) return null

            val split = splitRef(url)
            val repo = split.first
            val ref = split.second

            // `git@host:user/repo` survives splitRef as an scp-like string, and pi
            // hands it to hosted-git-info (`utils/git.ts:132-135`, `:186-204`). The
            // clone URL stays as written — `useHttpsPrefix` is false for anything
            // starting with `git@` (`:192-197`).
            SCP_LIKE.matchEntire(repo)?.let { scp ->
                return buildGitSource(repo, scp.groupValues[1], scp.groupValues[2], ref)
            }
            // A protocol URL: host from the authority, path from the pathname
            // (`utils/git.ts:136-148`).
            if (PROTOCOL_URL.containsMatchIn(repo)) {
                val parts = parseUrlParts(repo) ?: return null
                return buildGitSource(repo, parts.first, parts.second, ref)
            }
            // `host/user/repo` shorthand, which only counts as hosted when the host
            // looks like a hostname; pi then prefixes https
            // (`utils/git.ts:149-160`, `:192-197`).
            val slash = repo.indexOf('/')
            if (slash < 0) return null
            val host = repo.substring(0, slash)
            val path = repo.substring(slash + 1)
            if (!host.contains('.') && host != "localhost") return null
            return buildGitSource("https://$repo", host, path, ref)
        }

        private val PROTOCOL_URL = Regex("^(https?|ssh|git)://", RegexOption.IGNORE_CASE)
        private val SCP_LIKE = Regex("^git@([^:]+):(.+)$")

        private fun buildGitSource(repo: String, host: String, rawPath: String, ref: String?): PiPackageSource.Git? {
            if (rawPath.startsWith("/")) return null
            val path = rawPath.removeSuffix(".git").trimStart('/')
            if (host.isEmpty() || path.isEmpty() || path.split('/').size < 2) return null
            if (hasUnsafeGitInstallPart(host, allowSlash = false)) return null
            if (hasUnsafeGitInstallPart(path, allowSlash = true)) return null
            return PiPackageSource.Git(raw = repo, repo = repo, host = host, path = path, ref = ref)
        }

        /** `hasUnsafeGitInstallPart` (`utils/git.ts:84-102`). */
        private fun hasUnsafeGitInstallPart(value: String, allowSlash: Boolean): Boolean {
            val decoded = runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrNull() ?: return true
            for (candidate in listOf(value, decoded)) {
                if (candidate.contains('\u0000') || candidate.contains('\\') || candidate.startsWith("/")) return true
                if (!allowSlash && candidate.contains('/')) return true
                if (candidate.split('/').contains("..")) return true
            }
            return false
        }

        /**
         * `splitRef` (`utils/git.ts:21-74`), all three branches.
         *
         * The `://` branch is the one that is easy to get wrong: the ref lives in
         * the **pathname**, so `ssh://git@github.com/user/repo` has no ref at all —
         * the `@` before `github.com` is user-info, not a ref separator. Reading the
         * raw string with `indexOf('@')` splits that URL into `"/git"` and
         * `"github.com/user/repo"`. pi avoids it with `new URL(...)` (`:36-53`), and
         * so does this.
         */
        private fun splitRef(url: String): Pair<String, String?> {
            SCP_LIKE.matchEntire(url)?.let { scp ->
                val pathWithMaybeRef = scp.groupValues[2]
                val at = pathWithMaybeRef.indexOf('@')
                if (at < 0) return url to null
                val repoPath = pathWithMaybeRef.substring(0, at)
                val ref = pathWithMaybeRef.substring(at + 1)
                if (repoPath.isEmpty() || ref.isEmpty()) return url to null
                return "git@${scp.groupValues[1]}:$repoPath" to ref
            }

            if (url.contains("://")) {
                return runCatching {
                    val uri = java.net.URI(url)
                    val pathname = uri.path?.trimStart('/').orEmpty()
                    val at = pathname.indexOf('@')
                    if (at < 0) return@runCatching url to null
                    val repoPath = pathname.substring(0, at)
                    val ref = pathname.substring(at + 1)
                    if (repoPath.isEmpty() || ref.isEmpty()) return@runCatching url to null
                    // `parsed.pathname = "/" + repoPath` then `parsed.toString()`
                    // minus a trailing slash (`utils/git.ts:45-48`).
                    val rebuilt = java.net.URI(
                        uri.scheme,
                        uri.userInfo,
                        uri.host,
                        uri.port,
                        "/$repoPath",
                        null,
                        null,
                    )
                    rebuilt.toString().removeSuffix("/") to ref
                }.getOrElse { url to null }
            }

            val slash = url.indexOf('/')
            if (slash < 0) return url to null
            val host = url.substring(0, slash)
            val pathWithMaybeRef = url.substring(slash + 1)
            val at = pathWithMaybeRef.indexOf('@')
            if (at < 0) return url to null
            val repoPath = pathWithMaybeRef.substring(0, at)
            val ref = pathWithMaybeRef.substring(at + 1)
            if (repoPath.isEmpty() || ref.isEmpty()) return url to null
            return "$host/$repoPath" to ref
        }

        private fun parseUrlParts(url: String): Pair<String, String>? = runCatching {
            val uri = java.net.URI(url)
            val host = uri.host ?: return null
            host to uri.path.trimStart('/')
        }.getOrNull()

        // -------------------------------------------------------------- validate

        /** A refusal the app makes before spawning anything. */
        data class Rejection(val message: String)

        /**
         * The only pre-flight refusals, each mirroring a pi code path so the app
         * never produces a message pi would not:
         *
         *  - blank → `handlePackageCommand` prints `Missing install source.`
         *    (`package-manager-cli.ts:907-912`).
         *  - leading `-` → pi's option parser swallows it and reports
         *    `Unknown option <x> for "<command>".` (`:492-495`, `:878-883`).
         *
         * Everything else — a URL that is not a git URL, a relative path that does
         * not exist, a package name npm will not resolve — is left to pi, whose
         * message is surfaced verbatim. An install that fails silently is worse
         * than one that refuses; an install the app refuses for its own reasons is
         * worse than both.
         */
        fun validate(command: String, source: String?): Rejection? {
            if (source == null || source.isBlank()) {
                return Rejection("Missing $command source.")
            }
            if (source.trimStart().startsWith("-")) {
                return Rejection("Unknown option ${source.trim()} for \"$command\".")
            }
            return null
        }
    }
}
