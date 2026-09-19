package app.pi.ui.screens

import app.pi.settings.checkPiFileWrite
import java.io.File

/**
 * Which workspace-tree saves must go through pi's own write path instead of the tree's plain
 * overwrite, and what has to be checked first.
 *
 * ## The second writer this removes
 *
 * `WorkspaceFiles.writeText` is a whole-file overwrite: `file.writeText(text)`, no lock, no
 * temp file, no validation. For a user document that is exactly what an editor is for. For
 * any path under `<workspace>/.pi/` it is a **second writer on files pi reads**: 「Pi 文件」
 * (`ui/screens/PiFilesScreen.kt`) writes those same paths through
 * `PiConfigFiles.withLock` + `PiConfigFiles.write` (pi's `proper-lockfile` semantics and a
 * temp-file rename), and checks the content with `checkPiFileWrite` first. Two writers, two
 * rules, one file — and the file is the one pi parses.
 *
 * This object is the missing half of the tree's side: the *decision* about when the tree has
 * to use pi's path, and *what* it has to check, as pure functions. The write itself stays
 * `PiConfigFiles` — there is no second implementation of the lock or the atomic replace.
 *
 * ## The rule, and why it is a prefix rather than the Pi screen's whitelist
 *
 * **Every path under the workspace's own `.pi/` is routed through pi's write path.** The
 * 「Pi 文件」screen's whitelist (`piFilesAccessFor`) answers a different question — "may this
 * app edit this file at all?" — and it is deliberately restrictive (default deny) because
 * that screen's job is to be conservative about pi's files. The workspace tree's job is
 * different: it is a general file editor, and refusing `auth.json` or a session file there
 * would remove a capability the user has today. So this does not inherit the whitelist; it
 * inherits the *write discipline*, for every pi file:
 *
 *  - **validation** where pi parses the file as a document (`settings.json`, `models.json`,
 *    a theme document, any `.json` — `checkPiFileWrite` decides, and its blockers are pi's own
 *    "this makes pi throw" shapes: a `null` `httpIdleTimeoutMs`, a non-integer
 *    `compaction.reserveTokens`, …). A refused save tells the user why and changes nothing.
 *  - **the lock and the atomic replace** for everything else too, including the files pi
 *    rewrites itself (`auth.json`, `models-store.json`, `sessions/`). That is the half that
 *    cannot be done with content checks: it is what keeps a concurrent pi write from
 *    interleaving with the user's save and losing keys.
 *
 * ## Two details worth stating, because they look like inconsistencies
 *
 *  - **The `.pi` segment matches case-insensitively, the document name does not.** A
 *    case-insensitive volume (some external storage providers) would spell the same directory
 *    `.PI`, and routing that through pi's path costs nothing; a file called `SETTINGS.JSON`
 *    is *not* the document pi reads (`settings-manager.ts` reads the exact name), so it gets
 *    no content check — the same case-sensitive name matching `piFilesDocFor` already does.
 *  - **Only the workspace root's `.pi`.** A `.pi` nested deeper (`src/.pi/…`) is not pi's
 *    project directory for this workspace, so it stays an ordinary file.
 *
 * Android-free (`java.io.File` is not even needed here) so the harness
 * `workspace-pi-write` can pin the rule on a bare JVM.
 */
internal object WorkspacePiWrite {

    /** pi's project directory inside the workspace (`PiFilesRootKind.ProjectPi`). */
    const val PI_DIR = ".pi"

    /**
     * The path relative to the `.pi` root (what `checkPiFileWrite` takes), or null when this
     * is not a document under the workspace's own `.pi`.
     *
     * `.pi` itself is a directory, so it has no document path and returns null.
     */
    fun piRelative(relativePath: String): String? {
        val normalized = relativePath.replace('\\', '/').trim().trimStart('/')
        if (normalized.isEmpty()) return null
        val head = normalized.substringBefore('/')
        if (!head.equals(PI_DIR, ignoreCase = true)) return null
        return normalized.substringAfter('/', "").ifEmpty { null }
    }

    /**
     * **The authoritative form of the question**, from the real file rather than from a display
     * path: the `.pi` directory [target] sits under, found from its **canonical** path, or null.
     *
     * Why the file and not the path the caller shows: `WorkspaceViewerTarget.relativePath` is a
     * *display* path, and on one of the open routes it is pi's own absolute/guest path
     * (`/workspace/...`) while [WorkspaceViewerTarget.file] is the real host file. A rule that
     * only read the display string therefore said "not a `.pi` file" and sent the save back to
     * the unprotected overwrite. Reading the file cannot be fooled that way, and `canonicalFile`
     * resolves both `..` and symlinks, so no spelling of a path can escape the decision.
     *
     * The **innermost** `.pi` ancestor wins (`a/.pi/b/.pi/c.json` is relative to the second one),
     * which is the nearest pi root for that file.
     */
    fun piDirOf(target: File): File? = piDirIn(canonical(target)) ?: piDirIn(lexical(target))

    /** The nearest `.pi` ancestor of one spelling of a path. See [piDirOf]. */
    private fun piDirIn(path: File): File? {
        var dir = path.parentFile ?: return null
        while (true) {
            if (dir.name.equals(PI_DIR, ignoreCase = true)) return dir
            dir = dir.parentFile ?: return null
        }
    }

    /**
     * The path of [target] relative to the `.pi` directory it sits under (what
     * `checkPiFileWrite` takes), or null when it is not under one. See [piDirOf].
     */
    fun piRelativeFromFile(target: File): String? =
        relativeUnderPi(canonical(target)) ?: relativeUnderPi(lexical(target))

    /**
     * The path below the nearest `.pi` ancestor of [path], or null.
     *
     * Called twice, with the canonical path and with the lexical one, and the union is the
     * point. pi reads the file it is **given by name**, so a symlink spelled
     * `.pi/settings.json` whose bytes live elsewhere is still the document pi parses — judging
     * only by the canonical target would send that save down the unprotected path. The other
     * direction is the same argument: a link spelled outside `.pi` that resolves *into* it must
     * still be routed. Canonical first because it is the path actually written; lexical second
     * because it is the path pi reads.
     */
    private fun relativeUnderPi(path: File): String? {
        var dir = path.parentFile ?: return null
        while (true) {
            if (dir.name.equals(PI_DIR, ignoreCase = true)) {
                val base = dir.path
                val full = path.path
                val prefix = if (base.endsWith(File.separator)) base else base + File.separator
                if (!full.startsWith(prefix)) return null
                return full.removePrefix(prefix).replace(File.separatorChar, '/').ifEmpty { null }
            }
            dir = dir.parentFile ?: return null
        }
    }

    /**
     * The path with `.` and `..` removed **lexically** (no filesystem access, symlinks
     * unresolved). See [relativeUnderPi] for why both spellings matter.
     */
    private fun lexical(target: File): File = target.absoluteFile.normalize()

    /**
     * True when a save to [target] has to use pi's lock + atomic replace (+ pi's content check
     * when [relativePath] resolves to a document).
     *
     * The real file decides first ([piRelativeFromFile]); [relativePath] is the fallback for
     * callers that only have a workspace-relative path. For every ordinary case — the file tree's
     * own open route — the two agree, so nothing about that path changes.
     */
    fun isPiDocumentPath(relativePath: String, target: File): Boolean =
        piRelativeFor(relativePath, target) != null

    /** The `.pi`-relative path this save is about: the file's, else the display path's. */
    fun piRelativeFor(relativePath: String, target: File): String? =
        piRelativeFromFile(target) ?: piRelative(relativePath)

    /**
     * Creating or renaming a file to [target]: is it a **JSON document** pi would parse, under a
     * `.pi` directory? Used to refuse a creation/rename that never passes the content check.
     *
     * The name test is case-sensitive, like `piFilesDocFor`'s: `SETTINGS.JSON` is not the file
     * `settings-manager.ts` reads. The directory test is [piDirOf], so a nested `.pi` is refused
     * too — an over-approximation, and the safe side of it: the alternative is a document pi
     * reads appearing without any check, and the cost is a refusal with an explanation.
     */
    fun isPiJsonTarget(target: File): Boolean =
        target.name.endsWith(".json") && piDirOf(target) != null

    /**
     * Renaming [source] to [target]: is this a JSON document being **moved into** a `.pi`
     * directory it was not already in?
     *
     * This is the rename half of [isPiJsonTarget], narrowed to what is actually dangerous. A
     * rename inside one `.pi` (`.pi/themes/dark.json` → `.pi/themes/dark2.json`) changes a name,
     * not a fact: the file was already in pi's project directory and its bytes were already
     * there, so it is allowed. What must not happen is a document **arriving** in `.pi` from
     * outside — `notes.json` → `.pi/settings.json` is how an unvalidated file becomes the
     * settings pi reads — and that is refused. A source under a *different* `.pi` counts as
     * arriving too.
     */
    fun isPiJsonImport(source: File, target: File): Boolean {
        if (!isPiJsonTarget(target)) return false
        val targetPi = piDirOf(target) ?: return false
        val sourcePi = piDirOf(source) ?: return true
        return canonical(targetPi).path != canonical(sourcePi).path
    }

    /**
     * What the user is told when a creation or a rename is refused by [isPiJsonTarget].
     *
     * It deliberately does **not** promise a "new file" action on the 「Pi 文件」screen — that
     * screen has none (it edits the whitelisted files it lists). What it does promise is true:
     * existing documents are edited there under the same check, and a document may be edited in
     * the tree (its save is validated).
     */
    fun creationRefusalSentence(): String =
        "不能在 .pi（pi 的项目目录）里新建或改名为 JSON 文档：" +
            "新建出来的是空文件、改名搬的是未经校验的内容，而 pi 会用严格 JSON 解析这些文件 —— " +
            "写坏一个它就读不了（settings.json 尤其）。" +
            "已存在的这类文件可以在这里打开改（保存时会校验），也可以在「Pi 文件」屏改（同一份写前校验）。"

    /**
     * The reason to refuse this save, or null when it may proceed.
     *
     * The check is `checkPiFileWrite`'s — the same function the 「Pi 文件」screen runs before
     * its own writes, so the two surfaces cannot disagree about what pi will reject — and
     * only its `blockers` are refusals (`notices` are advisory and the Pi screen saves
     * anyway).
     *
     * A path outside `.pi/` is never refused: this object's job is pi's files, not the
     * editor's.
     */
    fun problem(relativePath: String, target: File, text: String): String? {
        val rel = piRelativeFor(relativePath, target) ?: return null
        return checkPiFileWrite(rel, text).blockers.firstOrNull()
    }

    /**
     * Whether the file changed under the editor, given the stamp it was opened with.
     *
     * A null [openedStamp] is "no baseline" (the viewer had not read the file yet), and no
     * baseline must not mean "refuse everything" — it means the check cannot run.
     * Otherwise any difference in `(size, mtime)` is a change: it is the same
     * `"<size>:<mtime>"` fingerprint the 「Pi 文件」screen records at open time
     * (`PiFilesScreen.kt`), and it deliberately is not meant to be unique — it only has to
     * notice that something moved.
     */
    fun stampChanged(openedStamp: String?, currentStamp: String): Boolean =
        openedStamp != null && openedStamp != currentStamp

    /**
     * What the user is told when [stampChanged] fired. The save is refused, nothing is
     * written, and the reason names the way out — because the alternative (overwriting) is
     * exactly the lost-key outcome this check exists to prevent.
     */
    fun staleStampSentence(): String =
        "这个文件在打开之后被改过（pi 正在写它，或在别处改过）。" +
            "为避免覆盖掉那次改动，本次没有保存：请重新打开这个文件，把你的改动并回去。"

    /**
     * Whether the write has to end up owner-only (`PiConfigFiles.write`'s `mode600`).
     *
     * `auth.json` is the one pi creates `0600` (`core/auth-storage.ts:106`, `:187`), and
     * `PiConfigFiles` itself writes it with `mode600 = true` everywhere it touches it. The
     * atomic replace renames a *new* file over the old one, so without this a tree save
     * would silently relax those permissions.
     */
    fun restrictToOwner(relativePath: String, target: File): Boolean =
        piRelativeFor(relativePath, target)
            ?.substringBefore('/')
            ?.equals("auth.json", ignoreCase = true) == true

    /**
     * The lexical canonical path, falling back to the absolute one.
     *
     * `canonicalFile` does not require the file to exist (it resolves the longest existing prefix
     * and normalises the rest), which is what lets a not-yet-created target be judged. The
     * fallback keeps a hostile/unreadable path from turning the *decision* into a crash.
     */
    private fun canonical(target: File): File =
        runCatching { target.canonicalFile }.getOrElse { target.absoluteFile }
}
