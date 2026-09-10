package app.pi.packages

/**
 * The two value types shared by the package service, the list parser and the UI.
 *
 * They live in their own file so that [PiListOutput] — which is pure string
 * handling over `pi list`'s output — does not have to depend on the Android-facing
 * [PiPackageService] and its [GuestCommand]. Keeping the parser free of Android is
 * what makes it checkable off-device, and this parser is exactly the kind of code
 * that silently lies when it is wrong: a mis-parsed `pi list` looks identical to
 * "no packages installed".
 */

/** `install`/`remove` write to one of pi's two settings documents. */
enum class PiPackageScope(
    /** The CLI flag pi uses for this scope: `""` or `-l`. */
    val flag: String,
    /** App-side label; pi's own names are "user settings" / "project settings". */
    val label: String,
) {
    /** `~/.pi/agent/settings.json` — pi's default (`docs/packages.md:43`). */
    User("", "全局（用户）"),

    /** `.pi/settings.json` in the project — `pi install -l`. */
    Project("-l", "项目"),
}

/** One list row, as `pi list` describes it (`package-manager-cli.ts:970-1004`). */
data class PiPackageEntry(
    val source: PiPackageSource,
    val scope: PiPackageScope,
    /** True for the object form in settings (`filtered` in pi's output). */
    val filtered: Boolean,
    /** `pkg.installedPath`, when pi resolved one. */
    val installedPath: String?,
)
