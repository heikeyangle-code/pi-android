package app.pi.packages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.pi.ui.settings.PiInfoNote
import app.pi.ui.settings.PiSettingsBadge
import app.pi.ui.settings.PiSettingsCard
import app.pi.ui.settings.PiSettingsCardShape
import app.pi.ui.settings.PiSettingsDialog
import app.pi.ui.settings.PiSettingsHairline
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.PiSettingsSectionHeader
import app.pi.ui.theme.PiMonoFamily
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * The whole install / list / remove / restart / trust surface, as one state-driven
 * screen.
 *
 * It is state-*driven* on purpose: everything the user sees is derived from
 * [PiPackagesUiState], which is assembled outside this package from
 * [PiPackageService], [ExtensionLifecycle] and [TrustRepository]. The owner of
 * The `ui` package wires it up; this file never reaches into the engine, never starts
 * a restart, and never writes a trust record itself. That keeps the one rule that
 * matters — **no silent restart, no silent trust** — a property of the state machine
 * rather than of a button's onClick.
 */
data class PiPackagesUiState(
    /** The workspace whose trust decision is being shown, in guest spelling. */
    val guestWorkspace: String = "",
    val spec: String = "",
    val scope: PiPackageScope = PiPackageScope.User,
    val busy: Boolean = false,
    val lifecycle: ExtensionLifecycle.State = ExtensionLifecycle.State.Idle,
    val entries: List<PiPackageEntry> = emptyList(),
    /**
     * The extensions the APK itself ships, with the presence the app could verify.
     * pi has no "built in" concept at all — the label is the app's, and the UI says
     * so; see [PiBuiltinExtension].
     */
    val builtins: List<BuiltinRow> = emptyList(),
    /**
     * Extensions pi would load from the agent's `extensions/` directory that are
     * **not** the app's own three and **not** packages — typically what the model
     * itself wrote there. They used to be invisible: this screen had only the shipped
     * list and `pi list`'s rows, and this is neither. See [PiAutoExtensions].
     */
    val discovered: List<PiAutoExtensions.Found> = emptyList(),
    /**
     * Skills, prompt templates and themes pi would find by looking at a directory —
     * not packages, so `pi list` says nothing about them either.
     */
    val resources: List<PiResourceDiscovery.Found> = emptyList(),
    /**
     * What each **installed package** carries, read off its install directory by
     * this app.
     *
     * `pi list` reports a package's spec and where it was installed, and nothing
     * about the resources inside it — while pi's loader does resolve a package's own
     * `extensions/`, `skills/`, `prompts/` and `themes/`
     * (`core/package-manager.ts:2066-2072`). So a user who installed an extension as
     * a package saw an empty screen. Everything in here is the app walking the same
     * directory pi walks, which is why the card says so on its face.
     */
    val packageScans: List<PackageScan> = emptyList(),
    /** `pi list`'s raw stdout, shown whenever parsing found nothing. */
    val listRaw: String = "",
    val listUnparsed: Boolean = false,
    /**
     * Non-null when `pi list` **never ran** (runtime or engine missing). Deliberately
     * separate from an empty list: "nothing was executed" and "executed, nothing
     * installed" are different facts and must not share one sentence.
     */
    val listNotReady: String? = null,
    /**
     * `.pi/settings.json` lists packages that this listing could not show, because
     * the project is untrusted. `pi list` exits 0 in that case and prints nothing
     * about them, so the app has to say it.
     */
    val projectPackagesHidden: Boolean = false,
    /** The last command's streams and argv, verbatim, newest first. */
    val log: List<LogLine> = emptyList(),
    /** Null when the project has no trust-requiring resources at all. */
    val trust: TrustPanel? = null,
    /** Non-null when a trust record is invalid and pi would refuse to start. */
    val trustInvalid: String? = null,
) {

    /**
     * One shipped extension as the app could verify it. The presence is what the row
     * shows; the guest path it was checked at is deliberately **not** on screen — it
     * is a fact about our layout that the user has nothing to do with (the file-level
     * rule in `PackageStrings`).
     */
    data class BuiltinRow(
        val extension: PiBuiltinExtension,
        val presence: PiBuiltinExtension.Presence,
    )

    /**
     * One installed package's own resources, as the app could see them on disk.
     *
     * [rootPath] is null when `pi list` reported no install path and the layout
     * could not be derived either (a git package: pi names its checkout directory
     * itself, and guessing would put a wrong path on screen). A null root is shown
     * as such rather than as "no resources".
     */
    data class PackageScan(
        /** `pi list`'s own source spelling — the row's identity. */
        val label: String,
        /** The host directory that was scanned. */
        val rootPath: String?,
        /** Extensions the package's `extensions/` directory holds. */
        val extensions: List<PiAutoExtensions.Found> = emptyList(),
        /** Skills, prompt templates and themes under the package root. */
        val resources: List<PiResourceDiscovery.Found> = emptyList(),
    ) {
        val isEmpty: Boolean get() = extensions.isEmpty() && resources.isEmpty()
    }

    data class LogLine(
        val headline: String,
        val command: String,
        val stdout: String,
        val stderr: String,
        val warnings: List<String> = emptyList(),
    )

    data class TrustPanel(
        /** `True` / `False` / null, exactly as `trust.json` holds it. */
        val decision: Boolean?,
        /** Which branch decided, and what it means for the user. */
        val explanation: String,
        val promptOptions: List<ProjectTrust.Option>,
        val promptVisible: Boolean,
        /** True when project resources are being skipped right now. */
        val skippingResources: Boolean,
        /** True when a project-scope package operation is blocked by this. */
        val blocksProjectPackages: Boolean,
    )

    val needsRestart: Boolean get() = lifecycle is ExtensionLifecycle.State.NeedsRestart
    val awaitingIdle: Boolean get() = lifecycle is ExtensionLifecycle.State.AwaitingIdle
    val awaitingConfirm: Boolean get() = lifecycle is ExtensionLifecycle.State.AwaitingConfirmation
    val restarting: Boolean get() = lifecycle is ExtensionLifecycle.State.Restarting
}

@Composable
fun PiPackagesScreen(
    state: PiPackagesUiState,
    onSpecChange: (String) -> Unit,
    onScopeChange: (PiPackageScope) -> Unit,
    onInstall: () -> Unit,
    onRemove: (PiPackageEntry) -> Unit,
    /**
     * Edit one of the four glob arrays of a package's object form. pi stores them
     * in `settings.json` (`core/settings-manager.ts:95-104`) and reads them when
     * it starts, so the host writes them and reports that a restart is needed.
     */
    onFilterAdd: (PiPackageEntry, String, String) -> Unit,
    onFilterRemove: (PiPackageEntry, String, String) -> Unit,
    onRefresh: () -> Unit,
    onRestartClick: () -> Unit,
    onRestartConfirm: () -> Unit,
    onRestartCancel: () -> Unit,
    onOpenTrustPrompt: () -> Unit,
    onTrustChoose: (ProjectTrust.Option) -> Unit,
    onTrustDismiss: () -> Unit,
    onTrustRepair: () -> Unit,
) {
    if (state.awaitingConfirm) {
        RestartConfirmDialog(
            lifecycle = state.lifecycle,
            onConfirm = onRestartConfirm,
            onCancel = onRestartCancel,
        )
    }
    val trust = state.trust
    if (trust != null && trust.promptVisible) {
        PiProjectTrustPrompt(
            cwd = state.guestWorkspace,
            options = trust.promptOptions,
            explanation = trust.explanation,
            busy = state.busy,
            onChoose = onTrustChoose,
            onDismiss = onTrustDismiss,
        )
    }

    val palette = PiTheme.palette
    // v2 的页面节奏（06 §2）：卡片自带左右 14px 页边（`PiSettingsCard`），分区头自带
    // `marginTop:18px / margin-bottom:7px`，所以整列不再要水平内边距，也不要统一的
    // 10px 行距 —— 那会把分区的层级压平。整页第一块用 14px 顶距代替 v2 的页首留白。
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.pageBg),
    ) {
        trust?.let { panel ->
            item {
                PiSettingsSectionHeader("项目信任")
                TrustCard(panel, state.busy, onOpenTrustPrompt)
            }
        }
        state.trustInvalid?.let { message ->
            item { InvalidTrustCard(message, state.busy, onTrustRepair) }
        }
        item { LifecycleCard(state, onRestartClick, onRestartConfirm, onRestartCancel) }
        item { InstallCard(state, onSpecChange, onScopeChange, onInstall, onRefresh) }

        if (state.builtins.isNotEmpty()) {
            item {
                PiSettingsSectionHeader(PackageStrings.BUILTIN_TITLE)
                BuiltinCard(state.builtins)
            }
        }

        // Extensions that arrived the other way: pi loads them, `pi list` knows
        // nothing about them, and only the filesystem can say they are there. Shown
        // right after the app's own so the two origins stay two lists.
        if (state.discovered.isNotEmpty()) {
            item {
                PiSettingsSectionHeader(PackageStrings.DISCOVERED_TITLE)
                DiscoveredCard(state.discovered)
            }
        }

        // The same question for the other three kinds pi discovers by looking at a
        // directory. A skill or theme written by hand used to be absent from this
        // screen entirely.
        if (state.resources.isNotEmpty()) {
            item {
                PiSettingsSectionHeader(PackageStrings.RESOURCES_TITLE)
                ResourcesCard(state.resources)
            }
        }
        item {
            PiSettingsSectionHeader(
                label = PackageStrings.LIST_SECTION_TITLE,
                count = "${state.entries.size} 项",
            )
            if (state.projectPackagesHidden) {
                PiInfoNote(PackageStrings.PROJECT_PACKAGES_HIDDEN, tone = palette.warning)
            }
            if (state.entries.isEmpty()) {
                // Three different facts, three different sentences. "Nothing ran" must
                // never read as "nothing is installed" — that is the lie this branch
                // used to tell when the runtime was missing.
                val notReady = state.listNotReady
                PiInfoNote(
                    text = when {
                        notReady != null -> PackageStrings.LIST_NOT_READY_PREFIX + notReady
                        state.listUnparsed -> PackageStrings.LIST_UNPARSED
                        else -> PackageStrings.NO_PACKAGES
                    },
                    tone = if (notReady != null || state.listUnparsed) palette.warning else null,
                )
            } else {
                // v2 的分组容器：一张圆角 10 的卡，包与包之间 1px inset hairline。
                PiSettingsCard {
                    state.entries.forEachIndexed { index, entry ->
                        if (index > 0) PiSettingsHairline()
                        PackageRow(entry, state.busy, onRemove, onFilterAdd, onFilterRemove)
                    }
                }
            }
        }

        // 每个**已安装资源包**内部的资源。`pi list` 只报 spec 与安装路径，包里的
        // extensions / skills / prompts / themes 不在任何 RPC 载荷里，所以这一区是
        // 本应用自己扫安装目录的结果 —— 标题和说明都写明这一点（见 PackageStrings）。
        if (state.packageScans.isNotEmpty()) {
            item {
                PiSettingsSectionHeader(
                    label = PackageStrings.PACKAGE_RESOURCES_TITLE,
                    count = "${state.packageScans.size} 个包",
                )
                PiInfoNote(PackageStrings.PACKAGE_RESOURCES_NOTE)
            }
            state.packageScans.forEach { scan ->
                item(key = "pkgscan:${scan.label}") { PackageResourcesCard(scan) }
            }
        }

        if (state.listUnparsed && state.listRaw.isNotBlank()) {
            item {
                // 这一份是页面级的一整块（不是卡内的小块），所以要自己带页边距。
                RawBlock(
                    title = PackageStrings.STDOUT_TITLE,
                    body = state.listRaw,
                    modifier = Modifier.padding(
                        start = PiSettingsMetrics.pageHorizontal,
                        end = PiSettingsMetrics.pageHorizontal,
                        top = PiSettingsMetrics.cardPaddingLoose,
                    ),
                )
            }
        }
        items(state.log, key = { it.command + it.headline }) { line -> LogCard(line) }
        item { Spacer(Modifier.height(PiSettingsMetrics.groupGap)) }
    }
}


// ------------------------------------------------------------- built in block

/**
 * The extensions the app ships, marked as the app's own claim rather than pi's.
 *
 * There is no remove button here *by construction*: pi does not know these are
 * packages (`pi list` never shows them) and `pi remove <name>` would answer
 * `No matching package found` with exit code 1
 * (`package-manager-cli.ts:959-966`). Offering a button for that would be the
 * "the UI says it can, and it cannot" shape this project keeps paying for.
 */
@Composable
private fun BuiltinCard(rows: List<PiPackagesUiState.BuiltinRow>) {
    if (rows.isEmpty()) return
    // 分区名现在由页面的分区头承担（v2 的 Section），卡里只剩行。
    PiSettingsCard {
        rows.forEachIndexed { index, row ->
            if (index > 0) PiSettingsHairline()
            BuiltinRowView(row)
        }
    }
}

/**
 * The extensions found in the agent's `extensions/` directory, listed because pi
 * loads them — the app's own list could not see them, so work the model did itself
 * was invisible on this screen.
 */
@Composable
private fun DiscoveredCard(rows: List<PiAutoExtensions.Found>) {
    // v2 的扩展行：行首状态符号（✓）+ 等宽标题 + 尾部状态词，颜色只是第三层编码
    // （06 §4「扩展/资源状态」）。
    PiSettingsCard {
        rows.forEachIndexed { index, found ->
            if (index > 0) PiSettingsHairline()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                        vertical = PiSettingsMetrics.rowPaddingVertical,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
            ) {
                Text(
                    "✓",
                    style = PiTheme.text.monoSmall,
                    color = PiTheme.palette.success,
                )
                Text(
                    found.name,
                    modifier = Modifier.weight(1f),
                    style = PiTheme.text.mono,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    PackageStrings.DISCOVERED_PRESENCE,
                    style = PiTheme.text.monoSmall,
                    color = PiTheme.palette.success,
                )
            }
        }
    }
}

/**
 * Skills, prompt templates and themes found on disk, grouped by kind.
 *
 * Shown because pi loads them from a directory rather than from `settings.json`, so
 * neither of this screen's other two sources can see them.
 */
@Composable
private fun ResourcesCard(rows: List<PiResourceDiscovery.Found>) {
    val palette = PiTheme.palette
    PiSettingsCard {
        PiResourceDiscovery.Kind.entries.forEach { kind ->
            val ofKind = rows.filter { it.kind == kind }
            if (ofKind.isEmpty()) return@forEach
            // 卡内的种类小标题：与行同左缘，12 灰。
            Text(
                PackageStrings.resourceKind(kind) + "（${ofKind.size}）",
                modifier = Modifier.padding(
                    start = PiSettingsMetrics.rowPaddingHorizontal,
                    end = PiSettingsMetrics.rowPaddingHorizontal,
                    top = PiSettingsMetrics.rowPaddingVertical,
                    bottom = PiSettingsMetrics.supportingGap,
                ),
                style = PiTheme.text.meta,
                color = palette.muted,
            )
            ofKind.forEach { found ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                            vertical = PiSettingsMetrics.supportingGap,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
                ) {
                    Text(
                        found.name,
                        modifier = Modifier.weight(1f),
                        style = PiTheme.text.mono,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        PackageStrings.resourceOrigin(found),
                        style = PiTheme.text.monoSmall,
                        color = palette.muted,
                    )
                }
            }
        }
    }
}

/**
 * One installed package's own resources, as this app could see them.
 *
 * The caption is the point: the reader has to be able to tell this list from `pi
 * list`'s, because pi reports none of it. A package with no discoverable root says
 * so instead of showing an empty list, which would read as "the package is empty".
 */
@Composable
private fun PackageResourcesCard(scan: PiPackagesUiState.PackageScan) {
    val palette = PiTheme.palette
    PiSettingsCard {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(PiSettingsMetrics.cardPaddingLoose),
        ) {
            Text(
                scan.label,
                style = PiTheme.text.mono,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(PiSettingsMetrics.supportingGap))
            Text(
                text = scan.rootPath ?: PackageStrings.PACKAGE_ROOT_UNKNOWN,
                style = PiTheme.text.monoSmall,
                color = if (scan.rootPath == null) palette.warning else palette.dim,
            )
            if (scan.rootPath != null && scan.isEmpty) {
                Spacer(Modifier.height(PiSettingsMetrics.supportingGap))
                Text(
                    PackageStrings.PACKAGE_RESOURCES_EMPTY,
                    style = PiTheme.text.meta,
                    color = palette.muted,
                )
            }
            scan.extensions.forEach { extension ->
                ResourceLine(
                    name = extension.name,
                    origin = PackageStrings.EXTENSION_KIND,
                )
            }
            PiResourceDiscovery.Kind.entries.forEach { kind ->
                scan.resources.filter { it.kind == kind }.forEach { found ->
                    ResourceLine(
                        name = found.name,
                        origin = "${PackageStrings.resourceKind(kind)} " +
                            PackageStrings.resourceOrigin(found),
                    )
                }
            }
        }
    }
}

/** One scanned resource inside a package: name + where it came from. */
@Composable
private fun ResourceLine(name: String, origin: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = PiSettingsMetrics.supportingGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        Text(
            name,
            modifier = Modifier.weight(1f),
            style = PiTheme.text.mono,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            origin,
            style = PiTheme.text.monoSmall,
            color = PiTheme.palette.muted,
        )
    }
}

@Composable
private fun BuiltinRowView(row: PiPackagesUiState.BuiltinRow) {
    val palette = PiTheme.palette
    // 状态三重编码（06 §4）：字 + 符号 + 颜色。presence 是「在哪发现它」，
    // 所以是状态的第三层而不是主信息。
    val (presenceGlyph, presenceColor) = when (row.presence) {
        PiBuiltinExtension.Presence.EngineAgentDir -> "✓" to palette.success
        PiBuiltinExtension.Presence.RootfsCopyOnly -> "◌" to palette.warning
        PiBuiltinExtension.Presence.Missing -> "✗" to palette.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.extension.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val purpose = PackageStrings.builtinPurpose(row.extension.name)
            if (purpose.isNotEmpty()) {
                Text(
                    purpose,
                    modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                    style = PiTheme.text.meta,
                    color = palette.muted,
                )
            }
        }
        Text(
            presenceGlyph,
            style = PiTheme.text.monoSmall,
            color = presenceColor,
        )
        Text(
            PackageStrings.builtinPresence(row.presence),
            style = PiTheme.text.monoSmall,
            color = presenceColor,
        )
    }
}

// 列表区的标题由页面的分区头承担（`PiSettingsSectionHeader`，v2 的 Section）：
// 这一区是 `pi install` 写进 `settings.json` 的 `packages`，也就是 `pi list` 报告的
// 全部内容（`package-manager-cli.ts:970-1002`）。原来的卡内标题与它重复，已删。

// ------------------------------------------------------------------ trust card

@Composable
private fun TrustCard(
    trust: PiPackagesUiState.TrustPanel,
    busy: Boolean,
    onOpenPrompt: () -> Unit,
) {
    val palette = PiTheme.palette
    val decisionText = when (trust.decision) {
        true -> PackageStrings.TRUSTED
        false -> PackageStrings.UNTRUSTED
        null -> PackageStrings.NOT_RECORDED
    }
    val decisionColor = when (trust.decision) {
        true -> palette.success
        false -> palette.error
        null -> palette.warning
    }
    // 状态三重编码（06 §4）：符号 + 字 + 颜色。分区名由页面分区头承担，卡内不重复；
    // 这一行的「值」就是本工作区当前的信任决定。
    val decisionGlyph = when (trust.decision) {
        true -> "✓"
        false -> "⊘"
        null -> "◌"
    }
    PiSettingsCard {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(PiSettingsMetrics.cardPaddingLoose),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.titleGap),
            ) {
                Text(decisionGlyph, style = PiTheme.text.monoSmall, color = decisionColor)
                Text(
                    decisionText,
                    modifier = Modifier.weight(1f),
                    style = PiTheme.text.mono,
                    color = decisionColor,
                )
                OutlinedButton(onClick = onOpenPrompt, enabled = !busy) {
                    Text("作出信任决定…")
                }
            }
            Spacer(Modifier.height(PiSpacing.gutter))
            Text(
                text = trust.explanation,
                style = PiTheme.text.meta,
                color = if (trust.skippingResources) {
                    palette.warning
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (trust.blocksProjectPackages) {
                Spacer(Modifier.height(PiSpacing.gutter))
                Text(
                    text = PackageStrings.SCOPE_PROJECT_LOCKED,
                    style = PiTheme.text.meta,
                    color = palette.warning,
                )
            }
        }
    }
}

@Composable
private fun InvalidTrustCard(message: String, busy: Boolean, onRepair: () -> Unit) {
    val palette = PiTheme.palette
    // v2 的错误块（06 §2）：圆角 10、tool-error 底、1px 状态色描边、左 3px error 条、
    // `padding:10px 12px`。它整块就是「出错了」这件事，所以标题用 error 色 + 状态词。
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = PiSettingsMetrics.pageHorizontal,
                vertical = PiSettingsMetrics.cardPaddingLoose,
            ),
        shape = PiSettingsCardShape,
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(PiSettingsMetrics.hairline, palette.error.copy(alpha = 0.45f)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            Box(
                Modifier
                    .width(PiSpacing.stripe)
                    .fillMaxHeight()
                    .background(palette.error),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                        vertical = PiSettingsMetrics.rowPaddingVertical,
                    ),
            ) {
                Text(
                    PackageStrings.TRUST_INVALID_TITLE,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = palette.error,
                )
                Spacer(Modifier.height(PiSpacing.gutter))
                Text(message, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(PiSpacing.gutter))
                Text(
                    PackageStrings.TRUST_INVALID_NOTE,
                    style = PiTheme.text.meta,
                    color = palette.muted,
                )
                Spacer(Modifier.height(PiSpacing.inner))
                Button(onClick = onRepair, enabled = !busy) { Text(PackageStrings.TRUST_INVALID_ACTION) }
            }
        }
    }
}

// -------------------------------------------------------------- lifecycle card

@Composable
private fun LifecycleCard(
    state: PiPackagesUiState,
    onRestartClick: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val lifecycle = state.lifecycle
    if (lifecycle is ExtensionLifecycle.State.Idle) return
    if (lifecycle is ExtensionLifecycle.State.Ready) {
        // 三重编码：符号 + 字 + 颜色（06 §4「引擎状态行」的 ✓ 就绪）。
        Text(
            text = "✓ " + lifecycle.note,
            style = PiTheme.text.meta,
            color = PiTheme.palette.success,
        )
        return
    }

    val palette = PiTheme.palette
    // 每个状态都是「符号 + 字 + 颜色」三层（06 §4「引擎状态行」「工具卡」的同一套纪律）。
    val (glyph, accent) = when (lifecycle) {
        is ExtensionLifecycle.State.NeedsRestart -> "◌" to palette.warning
        is ExtensionLifecycle.State.AwaitingIdle -> "…" to palette.warning
        is ExtensionLifecycle.State.Restarting -> "…" to palette.accent
        is ExtensionLifecycle.State.Installing -> "…" to palette.warning
        else -> "·" to palette.muted
    }
    val headline = when (lifecycle) {
        is ExtensionLifecycle.State.NeedsRestart -> PackageStrings.RESTART_NEEDED_TITLE
        is ExtensionLifecycle.State.AwaitingIdle -> PackageStrings.RESTART_WAIT_TURN
        is ExtensionLifecycle.State.Restarting -> PackageStrings.RESTARTING
        is ExtensionLifecycle.State.Installing -> PackageStrings.RUNNING
        else -> lifecycle.label
    }
    PiSettingsCard(modifier = Modifier.padding(top = PiSettingsMetrics.cardPaddingLoose)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(PiSettingsMetrics.cardPaddingLoose),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.titleGap),
            ) {
                if (lifecycle is ExtensionLifecycle.State.NeedsRestart) {
                    // 「需要重启引擎」在 v2 里是一枚徽标（06 §2 的徽标表），不是正文行；
                    // 其余是过场状态，用「符号 + 字 + 颜色」的行内三重编码（06 §4）。
                    PiSettingsBadge(
                        label = PackageStrings.RESTART_NEEDED_TITLE,
                        tone = palette.warning,
                        glyph = glyph,
                    )
                } else {
                    Text(glyph, style = PiTheme.text.monoSmall, color = accent)
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.titleSmall,
                        color = accent,
                    )
                }
            }
            when (lifecycle) {
                is ExtensionLifecycle.State.NeedsRestart -> {
                    Spacer(Modifier.height(PiSpacing.gutter))
                    Text(
                        lifecycle.detail,
                        style = PiTheme.text.meta,
                        color = palette.muted,
                    )
                    lifecycle.lastError?.let { error ->
                        Spacer(Modifier.height(PiSpacing.gutter))
                        Text(error, style = PiTheme.text.meta, color = palette.error)
                    }
                    Spacer(Modifier.height(PiSpacing.gutter))
                    Text(
                        text = ExtensionLifecycle.ANSWER_HINT,
                        style = PiTheme.text.meta,
                        color = palette.dim,
                    )
                    Spacer(Modifier.height(PiSpacing.inner))
                    Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline)) {
                        Button(onClick = onRestartClick) { Text(PackageStrings.RESTART_BUTTON) }
                        TextButton(onClick = onCancel) { Text(PackageStrings.RESTART_STAY) }
                    }
                }

                is ExtensionLifecycle.State.AwaitingIdle -> {
                    Spacer(Modifier.height(PiSpacing.gutter))
                    Text(
                        lifecycle.turnNote,
                        style = PiTheme.text.meta,
                        color = palette.warning,
                    )
                    Spacer(Modifier.height(PiSpacing.inner))
                    Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline)) {
                        // Re-checks the turn rather than promising a restart: the
                        // dialog only appears when the engine reports idle.
                        OutlinedButton(onClick = onRestartClick) { Text(PackageStrings.RESTART_BUTTON) }
                        TextButton(onClick = onCancel) { Text(PackageStrings.RESTART_STAY) }
                    }
                }

                is ExtensionLifecycle.State.AwaitingConfirmation -> {
                    // The dialog is rendered by PiPackagesScreen; the card is the
                    // fallback if the dialog was dismissed.
                    Spacer(Modifier.height(PiSpacing.gutter))
                    Text(
                        lifecycle.question,
                        style = PiTheme.text.meta,
                        color = palette.text,
                    )
                    Spacer(Modifier.height(PiSpacing.inner))
                    Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline)) {
                        Button(onClick = onConfirm) { Text(PackageStrings.RESTART_CONFIRM) }
                        TextButton(onClick = onCancel) { Text(PackageStrings.RESTART_STAY) }
                    }
                }

                is ExtensionLifecycle.State.Restarting -> {
                    Spacer(Modifier.height(PiSpacing.gutter))
                    Text(
                        text = PackageStrings.RESTARTING_NOTE,
                        style = PiTheme.text.meta,
                        color = palette.muted,
                    )
                }

                else -> {
                    Spacer(Modifier.height(PiSpacing.gutter))
                    Text(
                        lifecycle.changesOrEmpty().joinToString("\n"),
                        style = PiTheme.text.meta,
                        color = palette.muted,
                    )
                }
            }
        }
    }
}

/** `changes` is only carried by the three pending states; the others have none. */
private fun ExtensionLifecycle.State.changesOrEmpty(): List<String> = when (this) {
    is ExtensionLifecycle.State.NeedsRestart -> changes
    is ExtensionLifecycle.State.AwaitingConfirmation -> changes
    is ExtensionLifecycle.State.AwaitingIdle -> changes
    is ExtensionLifecycle.State.Restarting -> changes
    else -> emptyList()
}

@Composable
private fun RestartConfirmDialog(
    lifecycle: ExtensionLifecycle.State,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = PiTheme.palette
    val question = (lifecycle as? ExtensionLifecycle.State.AwaitingConfirmation)?.question
        ?: return
    // 外壳走 v2 的对话框规格（`06 §2` 的 .b-dlg：330 / 圆角 14 / surf-high / 1px
    // borderMuted，见 `ui/components/PiDialog.kt`）。
    // Dismissal is a cancel, never an implicit confirm: `onDismissRequest`
    // routes to onCancel, matching pi's own "dismissal means no"
    // (`project-trust.ts:90-95`) and, more importantly, never restarting on a
    // back gesture.
    PiSettingsDialog(
        onDismissRequest = onCancel,
        title = PackageStrings.RESTART_NEEDED_TITLE,
        confirmationLabel = PackageStrings.RESTART_CONFIRM,
        onConfirm = onConfirm,
        dismissalLabel = PackageStrings.RESTART_STAY,
        onDismissButton = onCancel,
        content = {
            Column {
                Text(question, style = PiTheme.text.meta, color = palette.muted)
                Spacer(Modifier.height(PiSpacing.inline))
                Text(
                    text = "重启是显式的：在你点下确认之前，App 不会重启引擎。",
                    style = PiTheme.text.meta,
                    color = palette.dim,
                )
            }
        },
    )
}

// ---------------------------------------------------------------- install card

@Composable
private fun InstallCard(
    state: PiPackagesUiState,
    onSpecChange: (String) -> Unit,
    onScopeChange: (PiPackageScope) -> Unit,
    onInstall: () -> Unit,
    onRefresh: () -> Unit,
) {
    val palette = PiTheme.palette
    PiSettingsCard(modifier = Modifier.padding(top = PiSettingsMetrics.cardPaddingLoose)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(PiSettingsMetrics.cardPaddingLoose),
        ) {
            OutlinedTextField(
                value = state.spec,
                onValueChange = onSpecChange,
                label = { Text(PackageStrings.SPEC_HINT) },
                singleLine = true,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(PiSpacing.inline))
            Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline)) {
                PiPackageScope.entries.forEach { scope ->
                    val selected = scope == state.scope
                    OutlinedButton(
                        onClick = { onScopeChange(scope) },
                        enabled = !state.busy,
                    ) {
                        Text(
                            text = if (scope == PiPackageScope.User) {
                                PackageStrings.SCOPE_USER
                            } else {
                                PackageStrings.SCOPE_PROJECT
                            },
                            color = if (selected) palette.accent else palette.muted,
                        )
                    }
                }
            }
            if (state.scope == PiPackageScope.Project) {
                Spacer(Modifier.height(PiSpacing.gutter))
                Text(
                    text = PackageStrings.SCOPE_PROJECT_LOCKED,
                    style = PiTheme.text.meta,
                    color = palette.dim,
                )
            }
            Spacer(Modifier.height(PiSpacing.inner))
            Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline)) {
                Button(onClick = onInstall, enabled = !state.busy && state.spec.isNotBlank()) {
                    Text(if (state.busy) PackageStrings.RUNNING else PackageStrings.INSTALL_LABEL)
                }
                OutlinedButton(onClick = onRefresh, enabled = !state.busy) {
                    Text(PackageStrings.REFRESH_LABEL)
                }
            }
        }
    }
}

// ----------------------------------------------------------------- package row

@Composable
private fun PackageRow(
    entry: PiPackageEntry,
    busy: Boolean,
    onRemove: (PiPackageEntry) -> Unit,
    onFilterAdd: (PiPackageEntry, String, String) -> Unit,
    onFilterRemove: (PiPackageEntry, String, String) -> Unit,
) {
    val palette = PiTheme.palette
    // v2 的行骨架（06 §2「行（Row）」）：`10px 12px`，行首是来源种类（机器值，等宽），
    // 标题是包来源原文（等宽），尾部是作用域；详情、过滤规则与「移除」缩进在同一骨架下。
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                    vertical = PiSettingsMetrics.rowPaddingVertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
        ) {
            Text(
                text = when (entry.source) {
                    is PiPackageSource.Npm -> "npm"
                    is PiPackageSource.Git -> "git"
                    is PiPackageSource.Local -> "本地"
                },
                style = PiTheme.text.monoSmall,
                color = palette.accent,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.source.raw,
                    style = PiTheme.text.mono,
                    color = palette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                (entry.source as? PiPackageSource.Npm)?.let { npm ->
                    Text(
                        modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                        text = buildString {
                            append("包名 ${npm.name}")
                            npm.version?.let { append("，版本 $it") }
                            append(if (npm.pinned) "（精确版本，pi update --extensions 会跳过）" else "（范围/标签，可被 update 移动）")
                        },
                        style = PiTheme.text.meta,
                        color = palette.dim,
                    )
                }
                (entry.source as? PiPackageSource.Git)?.let { git ->
                    Text(
                        modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                        text = buildString {
                            append("仓库 ${git.repo}")
                            append(if (git.ref != null) "，ref ${git.ref}（已钉住）" else "，未钉 ref")
                        },
                        style = PiTheme.text.meta,
                        color = palette.dim,
                    )
                }
            }
            Text(
                text = if (entry.scope == PiPackageScope.User) {
                    PackageStrings.SCOPE_USER
                } else {
                    PackageStrings.SCOPE_PROJECT
                },
                style = PiTheme.text.monoSmall,
                color = palette.muted,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = PiSettingsMetrics.rowPaddingHorizontal,
                    end = PiSettingsMetrics.rowPaddingHorizontal,
                    bottom = PiSettingsMetrics.rowPaddingVertical,
                ),
        ) {
            if (entry.filtered) {
                Text(
                    text = "settings 里是对象形式：只加载显式列出的资源（可能只加载一部分）。",
                    style = PiTheme.text.meta,
                    color = palette.warning,
                )
            }
            FilterEditor(entry, busy, onFilterAdd, onFilterRemove)
            entry.installedPath?.let { path ->
                Spacer(Modifier.height(PiSettingsMetrics.supportingGap))
                Text(path, style = PiTheme.text.monoSmall, color = palette.dim)
            } ?: run {
                Spacer(Modifier.height(PiSettingsMetrics.supportingGap))
                Text(
                    text = "pi 没有报告安装路径——它可能尚未下载到磁盘，或该来源没有安装路径。",
                    style = PiTheme.text.meta,
                    color = palette.warning,
                )
            }
            Spacer(Modifier.height(PiSpacing.inline))
            TextButton(onClick = { onRemove(entry) }, enabled = !busy) {
                Text(PackageStrings.REMOVE_LABEL, color = palette.error)
            }
        }
    }
}

/**
 * The four glob arrays of a package's object form.
 *
 * pi stores them in `settings.json` (`core/settings-manager.ts:95-104`) and reads
 * them when the engine starts; the leading `+`/`-`/`!` decides whether a pattern
 * force-includes, force-excludes or excludes (`core/package-manager.ts:709-716`).
 * The app edits the strings verbatim, so a pattern written in pi's own TUI stays
 * visible and removable rather than being re-derived from a checkbox.
 *
 * Shown read-only until 编辑 is tapped: a package that pi filtered must not look
 * plain here, but four text fields on every package would drown the common case
 * (no filters at all).
 */
@Composable
private fun FilterEditor(
    entry: PiPackageEntry,
    busy: Boolean,
    onAdd: (PiPackageEntry, String, String) -> Unit,
    onRemove: (PiPackageEntry, String, String) -> Unit,
) {
    val palette = PiTheme.palette
    var editing by remember(entry.source.raw) { mutableStateOf(false) }

    Spacer(Modifier.height(PiSpacing.gutter))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("过滤规则", style = PiTheme.text.meta, color = palette.muted)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { editing = !editing }, enabled = !busy) {
            Text(if (editing) "完成" else "编辑", color = palette.accent)
        }
    }

    if (entry.filters.isEmpty() && !editing) {
        Text(
            text = "没有过滤规则：这个资源包里的资源全部加载。",
            style = PiTheme.text.meta,
            color = palette.dim,
        )
        return
    }

    PiPackageFilters.RESOURCE_TYPES.forEach { type ->
        val patterns = entry.filters[type].orEmpty()
        if (patterns.isEmpty() && !editing) return@forEach
        Text(
            text = filterTypeLabel(type),
            style = PiTheme.text.meta,
            color = palette.muted,
        )
        patterns.forEach { pattern ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pattern,
                    style = PiTheme.text.monoSmall,
                    color = palette.text,
                    modifier = Modifier.weight(1f),
                )
                if (editing) {
                    TextButton(onClick = { onRemove(entry, type, pattern) }, enabled = !busy) {
                        Text("删除", color = palette.error)
                    }
                }
            }
        }
        if (editing) {
            FilterAddRow(busy) { pattern -> onAdd(entry, type, pattern) }
        }
    }
}

/** One add field per resource type, so the target array is never ambiguous. */
@Composable
private fun FilterAddRow(busy: Boolean, onAdd: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            modifier = Modifier.weight(1f),
            placeholder = { Text("如 skills/legacy* 或 +src/ext/**") },
        )
        TextButton(
            onClick = {
                onAdd(draft)
                draft = ""
            },
            enabled = !busy && draft.isNotBlank(),
        ) { Text("添加") }
    }
}

/** pi's own resource names (`config-selector.ts:31-36`), labelled the way the settings rows name them. */
private fun filterTypeLabel(type: String): String = when (type) {
    "extensions" -> "扩展"
    "skills" -> "技能"
    "prompts" -> "提示模板"
    "themes" -> "主题"
    else -> type
}

// ------------------------------------------------------------------- raw cards

@Composable
private fun LogCard(line: PiPackagesUiState.LogLine) {
    val palette = PiTheme.palette
    PiSettingsCard(modifier = Modifier.padding(top = PiSpacing.inline)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(PiSettingsMetrics.cardPaddingLoose),
        ) {
            Text(line.headline, style = MaterialTheme.typography.titleSmall, color = palette.text)
            if (line.warnings.isNotEmpty()) {
                Spacer(Modifier.height(PiSpacing.gutter))
                line.warnings.forEach { warning ->
                    Text(warning, style = PiTheme.text.meta, color = palette.warning)
                }
            }
            if (line.stderr.isNotBlank()) {
                Spacer(Modifier.height(PiSpacing.gutter))
                RawBlock(PackageStrings.STDERR_TITLE, line.stderr)
            }
            if (line.stdout.isNotBlank()) {
                Spacer(Modifier.height(PiSpacing.gutter))
                RawBlock(PackageStrings.STDOUT_TITLE, line.stdout)
            }
            Spacer(Modifier.height(PiSpacing.gutter))
            RawBlock(PackageStrings.COMMAND_TITLE, line.command)
        }
    }
}

/**
 * Raw machine text: a command and the stdout/stderr it produced, shown verbatim.
 *
 * Rendered through `PiTheme.text.monoSmall`, i.e. the bundled [PiMonoFamily] rather
 * than `FontFamily.Monospace`: this is the same "two voices" rule the transcript
 * follows, and the system monospace is a per-device face.
 *
 * 容器按 v2 的等宽块（06 §2 的卡片 + 线宽规则）：圆角 10、`surfaceContainerLow` 底、
 * 1px `borderMuted` 描边、内边距 12；块前的那一行小标题是 12 灰。
 */
@Composable
private fun RawBlock(title: String, body: String, modifier: Modifier = Modifier) {
    val palette = PiTheme.palette
    Column(modifier.fillMaxWidth()) {
        Text(title, style = PiTheme.text.meta, color = palette.muted)
        Spacer(Modifier.height(PiSpacing.small))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = PiSettingsCardShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(
                PiSettingsMetrics.hairline,
                MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Text(
                text = body,
                style = PiTheme.text.monoSmall,
                color = palette.toolOutput,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PiSettingsMetrics.cardPadding),
            )
        }
    }
}
