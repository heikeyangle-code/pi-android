package app.pi.packages

import app.pi.settings.PiSettingsFileStore
import kotlinx.serialization.json.JsonArray
import java.io.File

/**
 * Reads and writes the `packages` arrays of pi's two settings documents.
 *
 * ## Why it goes through the app's settings store
 *
 * `PiSettingsFileStore` already owns the merge rules, the atomic replace and the
 * "a project override is updated where it lives" rule
 * (`PiSettingsFileStore.kt:88-100`), and it is the same writer every other
 * settings screen uses. A second writer over `settings.json` would be a second
 * set of assumptions about the same file — the class of bug that makes a change
 * disappear with no error, which is why this type owns no I/O of its own.
 *
 * ## Reading both documents, not the merged view
 *
 * pi keeps user packages in `~/.pi/agent/settings.json` and project packages in
 * `<cwd>/.pi/settings.json` (`package-manager-cli.ts` `-l`), and `pi list` prints
 * them under two headers. The two arrays are read separately here for that reason:
 * a merged view would report a project package as a user package, and a write
 * would then land in the wrong file.
 *
 * ## Unparseable entries are preserved, never dropped
 *
 * [update] rebuilds the array element by element and only replaces the element it
 * matched; anything [PiPackageFilters.parse] could not read is written back
 * verbatim. Re-serialising "what we understood" would silently delete a shape a
 * newer pi wrote, which is worse than showing nothing.
 */
class PiPackageFilterStore(
    agentDir: File,
    workspace: File,
) {

    private val store = PiSettingsFileStore.forWorkspace(agentDir = agentDir, workspace = workspace)

    data class Entries(
        /** Keyed by source exactly as `pi list` spells it. */
        val user: Map<String, PiPackageFilters.Entry>,
        val project: Map<String, PiPackageFilters.Entry>,
    )

    /** Both scopes, read fresh: the caches are dropped first, because `pi install`
     * may have rewritten the documents since this instance last looked. */
    fun read(): Entries {
        store.invalidate()
        return Entries(user = parse(store.global()), project = parse(store.project()))
    }

    /**
     * Replace one package's entry inside [scope].
     *
     * @return null on success, else a sentence for the log. A missing `packages`
     *         key or a source that is not in that document is reported rather than
     *         written: inventing the entry would make the app the author of a
     *         package pi never installed.
     */
    fun update(
        scope: PiPackageScope,
        source: String,
        transform: (PiPackageFilters.Entry) -> PiPackageFilters.Entry,
    ): String? {
        store.invalidate()
        val document = if (scope == PiPackageScope.Project) store.project() else store.global()
        val array = document["packages"] as? JsonArray
            ?: return "这个设置文档里没有已安装的资源包列表，无法修改过滤规则。"
        var replaced = false
        val next = array.map { element ->
            val entry = PiPackageFilters.parse(element)
            if (!replaced && entry != null && entry.source == source) {
                replaced = true
                PiPackageFilters.toJson(transform(entry))
            } else {
                // Kept verbatim, including shapes we could not parse.
                element
            }
        }
        if (!replaced) return "在当前设置文档里找不到 $source，没有修改。"
        // `PiSettingsFileStore.write` routes a write to the project document
        // whenever *that* document carries the key (`PiSettingsFileStore.kt:91`),
        // which is right for the settings screens (a project override is updated
        // where it lives) but wrong for an explicit-scope write here: with both
        // documents holding a `packages` array, editing a **user** package would
        // write the user array into the project file. Refuse instead of writing
        // the wrong file.
        val projectHasPackages = store.project()["packages"] != null
        if (scope == PiPackageScope.User && projectHasPackages) {
            return "这个项目也有一份自己的资源包列表，App 不在这里改全局列表（改错文件的代价太高）。" +
                "请在项目作用域里改，或用 pi 自己的界面。"
        }
        // The store routes this to the document it just read: global, or the
        // project file when it carries the key (`PiSettingsFileStore.kt:91`).
        store.write("packages", JsonArray(next))
        return null
    }

    private fun parse(document: kotlinx.serialization.json.JsonObject): Map<String, PiPackageFilters.Entry> =
        (document["packages"] as? JsonArray)
            ?.mapNotNull { PiPackageFilters.parse(it) }
            ?.associateBy { it.source }
            .orEmpty()
}
