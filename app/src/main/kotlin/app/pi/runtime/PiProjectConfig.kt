package app.pi.runtime

import java.io.File

/**
 * pi's **project** config directory — the `<cwd>/.pi` half of pi's layout — in one
 * place, as pure functions.
 *
 * ## Why this exists
 *
 * `docs/pi-sourced-lists.md`'s rule is that the app may keep a table of its own only
 * when it can say why the fact cannot be asked for. This is one of those facts: pi
 * never reports its config directory over RPC, and the RPC settings surface is
 * "files, not schema" (`docs/settings-review.md`). So the name is **transcribed**
 * from the pinned engine:
 *
 *     CONFIG_DIR_NAME = pkg.piConfig?.configDir || ".pi"      (pi/config.ts:504)
 *     piConfig = { "configDir": ".pi" }   (the shipped package.json)
 *
 * and pi joins it onto the working directory in every place it looks for project
 * state — settings (`core/settings-manager.ts:233`), skills (`core/skills.ts:454`),
 * prompt templates (`core/prompt-templates.ts:203`), extensions
 * (`core/extensions/loader.ts:783`), themes (`core/resource-loader.ts:875`),
 * `SYSTEM.md` / `APPEND_SYSTEM.md` (`core/resource-loader.ts:1024`, `:1038`) and
 * `pi install -l`'s project scope (`core/package-manager.ts:931`).
 *
 * Transcribed **once** here, because before this file the literal appeared at seven
 * call sites, and the moment any one of them disagreed with the engine the app would
 * read or write a directory pi never looks at — the silent, confident failure shape
 * this repository keeps paying for (`docs/known-gaps.md` §M11/§M12). The value is
 * pinned against the pinned engine by `tools/pi-contract.mjs`, next to the other
 * "the app reproduces a fact about pi" assertions.
 *
 * ## What it deliberately does not decide
 *
 * The **workspace** is not here. `GuestWorkspacePath` owns that, this object only
 * owns the directory *inside* a workspace that pi reads — so a future change to
 * "which directory is the workspace" moves nothing in this file.
 *
 * Android-free on purpose (`java.io.File` and the stdlib only), so the bare-JVM
 * harness in `app/src/test/kotlin/app/pi/runtime/AgentToolPathsCheck.kt` pins it.
 */
object PiProjectConfig {

    /**
     * pi's `CONFIG_DIR_NAME` for the engine this app ships. See the class KDoc for
     * the two pi lines it is transcribed from; `tools/pi-contract.mjs` fails the
     * build if the pinned engine's `package.json` declares a different one.
     */
    const val DIRECTORY: String = ".pi"

    /** `<workspace>/.pi`, the root of everything pi reads from the project. */
    fun root(workspace: File): File = File(workspace, DIRECTORY)

    /**
     * A path **inside** [root]`(workspace)`, or null when [relative] is not a usable
     * relative path.
     *
     * `File(workspace, relative)` is string concatenation, so `relative` is free to
     * escape: `"../outside"` and `"../../files/pi"` both name a directory outside the
     * workspace and neither throws. Every caller today passes a literal from this
     * repository, so no call site is currently at risk — this function exists so that
     * the next caller (a user-supplied theme name, a file the model named, a
     * per-workspace setting) has one guarded door instead of `File(workspace, "…")`.
     *
     * The rules, each chosen so that the result is always [root]`(workspace)` or
     * below it:
     *
     *  - `..`, `.` and empty segments are **refused**, by segment rather than by
     *    `startsWith` on the raw string, so `"themes/../../outside"` is refused too.
     *  - `\` is treated as `/` (a name that arrives from JSON or from the model may
     *    carry either), which also means `"..\\.."` cannot slip through as one
     *    innocent-looking segment.
     *  - A leading `/` is **not** an escape: it is stripped, and `/etc/passwd` becomes
     *    `<workspace>/.pi/etc/passwd`. That is a path nothing will exist at, which is
     *    the right answer for a caller that passed an absolute path by mistake — the
     *    alternative, joining it as an absolute path, is the one outcome that could
     *    land outside the workspace.
     */
    fun under(workspace: File, relative: String): File? {
        val clean = relative.replace('\\', '/').trim('/')
        if (clean.isEmpty()) return null
        val parts = clean.split('/')
        if (parts.any { it == ".." || it == "." || it.isEmpty() }) return null
        return File(root(workspace), parts.joinToString("/"))
    }

    /** `<workspace>/.pi/themes` — pi's project themes directory. */
    fun themesDir(workspace: File): File = File(root(workspace), "themes")

    /** `<workspace>/.pi/skills` — pi's project skills directory. */
    fun skillsDir(workspace: File): File = File(root(workspace), "skills")

    /** `<workspace>/.pi/prompts` — pi's project prompt-template directory. */
    fun promptsDir(workspace: File): File = File(root(workspace), "prompts")

    /** `<workspace>/.pi/extensions` — pi's project extensions directory. */
    fun extensionsDir(workspace: File): File = File(root(workspace), "extensions")

    /** `<workspace>/.pi/settings.json` — pi's project settings document. */
    fun settingsFile(workspace: File): File = File(root(workspace), "settings.json")
}
