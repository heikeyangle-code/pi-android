package app.pi.ui.screens

import app.pi.i18n.tr

/** One file this session touched, as the project screen shows it. */
internal data class ProjectFile(
    /** pi's argument spelling, i.e. the path it was handed — not a host path. */
    val path: String,
    val action: ProjectFileAction,
    /** Wall clock of the newest touch, for ordering. */
    val at: Long,
    /** Lines added, from pi's own diff for this path (`ToolDiff.added`); 0 when it gave none. */
    val added: Int,
    val removed: Int,
) {
    /** pi gave a diff for this path, so a `+N −M` reading is a fact and not a guess. */
    val hasDiff: Boolean get() = added > 0 || removed > 0
}

/**
 * What the session did to a file, in the app's own words.
 *
 * Only `read` / `write` / `edit` get an entry: `grep`, `find` and `ls` take a directory or
 * a pattern rather than a file, so `argString(args, "path")` on them names a *directory*,
 * and listing a directory as a touched file would be wrong. The set matches the tools whose
 * cards the transcript draws as file operations (`blocks/BlockRenderer.kt:99-114`).
 */
internal enum class ProjectFileAction {
    /** `read` — the file went into the model's context. */
    Read,

    /** `write` — pi wrote the whole file. */
    Wrote,

    /** `edit` — pi changed part of it; the diff that follows says how much. */
    Edited,
    ;

    val label: String
        get() = when (this) {
            Read -> tr("读到", "Read")
            Wrote -> tr("写入", "Wrote")
            Edited -> tr("编辑", "Edited")
        }

    companion object {
        fun of(toolName: String): ProjectFileAction? = when (toolName) {
            "read" -> Read
            "write" -> Wrote
            "edit" -> Edited
            else -> null
        }
    }
}
