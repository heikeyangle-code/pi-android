package app.pi.packages

import java.io.File

/**
 * What pi would find under a resource directory — the **skills, prompts and themes**
 * half of the same question [PiAutoExtensions] answers for extensions.
 *
 * ## Why it exists
 *
 * The packages screen listed two things: the extensions this app ships, and the rows
 * `pi list` reports from `settings.json`'s `packages`. Everything pi discovers **by
 * looking at a directory** was therefore invisible — an extension the model wrote
 * (fixed in [PiAutoExtensions]) and equally a skill, a prompt template or a theme it
 * wrote. Writing a skill and seeing "0 个资源包" on the screen that is supposed to be
 * about resources is the same defect with a different noun.
 *
 * ## pi's rules, per kind
 *
 * `collectResourceFiles` (`core/package-manager.ts:645-653`) dispatches on the kind:
 *
 *  - **skills** — `collectSkillEntries(dir, "pi")` (`:365-395`): the directory's own
 *    entries are scanned and a file counts only when it is literally named
 *    `SKILL.md`, i.e. a skill is `<dir>/<name>/SKILL.md`, one level deep. The skill's
 *    name is the directory that holds it.
 *  - **prompts** and **themes** — `collectFiles(dir, FILE_PATTERNS[kind])`
 *    (`:645-652`), i.e. a recursive walk collecting `FILE_PATTERNS` matches
 *    (`:206-211`): `\.md$` for prompts, `\.json$` for themes.
 *
 * Two of pi's details are deliberately not reproduced, and both can only ever make
 * this reader **list more** than pi loads, never less:
 *
 *  - the per-directory ignore files (`.gitignore`, `.ignore`, `.fdignore`;
 *    `IGNORE_FILE_NAMES` at `:213`) — reading them is a dependency a listing screen
 *    does not otherwise need;
 *  - the recursive walk's symlink handling.
 *
 * Names beginning with `.` and `node_modules` are skipped, as pi skips them.
 */
object PiResourceDiscovery {

    /**
     * The three kinds, each with the directory pi reads it from and the file pattern
     * it collects — pi's own table (`FILE_PATTERNS`, `core/package-manager.ts:206-211`)
     * has exactly these two columns, so this enum is that table rather than a second
     * opinion about it. [suffix] is null for skills, which pi collects by looking for
     * a file *named* `SKILL.md` rather than by suffix.
     */
    enum class Kind(val directory: String, val suffix: String?) {
        Skills("skills", null),
        Prompts("prompts", ".md"),
        Themes("themes", ".json"),
    }

    /** One resource pi would load, named for a person. */
    data class Found(
        val kind: Kind,
        /** The skill directory's name, or the file's path relative to the kind's directory. */
        val name: String,
        /** Which root it was found under, so two roots can be told apart. */
        val scope: Scope,
    ) {
        enum class Scope { Global, Project }
    }

    /**
     * Everything pi would find under [root]'s `skills/`, `prompts/` and `themes/`.
     *
     * [root] is one agent dir (`<agentDir>`), or one project config dir
     * (`<workspace>/.pi`) — the two places pi looks for resources, which is why the
     * caller passes the scope in.
     */
    fun discover(root: File, scope: Found.Scope): List<Found> {
        if (!root.isDirectory) return emptyList()
        return Kind.entries.flatMap { kind ->
            val dir = File(root, kind.directory)
            when (val suffix = kind.suffix) {
                null -> skillNames(dir).map { Found(kind, it, scope) }
                else -> fileNames(dir, suffix).map { Found(kind, it, scope) }
            }
        }
    }

    /**
     * pi's skill layout: `<dir>/<name>/SKILL.md`, one level deep (`:381-385`).
     *
     * A `SKILL.md` sitting directly in the skills directory is not a skill pi would
     * load — it has no owning directory — so it is not listed either.
     */
    private fun skillNames(dir: File): List<String> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") && it.name != "node_modules" }
            .filter { File(it, "SKILL.md").isFile }
            .map { it.name }
            .sorted()
    }

    /**
     * The recursive walk for prompts and themes (`collectFiles`, `FILE_PATTERNS`).
     *
     * The name shown is the path relative to the kind's directory without the
     * suffix, so two files with the same base name in different subdirectories stay
     * distinguishable — pi's own list would show both.
     */
    private fun fileNames(dir: File, suffix: String): List<String> {
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .onEnter { !it.name.startsWith(".") && it.name != "node_modules" }
            .filter { it.isFile && it.name.endsWith(suffix) && !it.name.startsWith(".") }
            .map { it.relativeTo(dir).path.removeSuffix(suffix).replace(File.separatorChar, '/') }
            .sorted()
            .toList()
    }
}
