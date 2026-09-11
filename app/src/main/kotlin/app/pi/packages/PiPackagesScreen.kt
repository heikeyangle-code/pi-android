package app.pi.packages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.pi.ui.theme.PiShapes
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
    /**
     * Host path of the agent directory the **engine** reads: `PiPaths.agentDir`,
     * which `PiEngineHost.kt:285-294` binds over guest `/root/.pi/agent`.
     */
    val engineAgentDir: String = "",
    /**
     * Host path of the rootfs copy, `<rootfs>/root/.pi/agent`. A guest command run
     * through [GuestCommand] reads *this* one, because that proot argv binds only the
     * workspace (`GuestCommand.kt:98-106`).
     */
    val rootfsAgentDir: String = "",
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
     * One shipped extension as the app could verify it. [guestPath] is the guest
     * spelling of the file pi loads, so the user can find it in the terminal tab.
     */
    data class BuiltinRow(
        val extension: PiBuiltinExtension,
        val guestPath: String,
        val presence: PiBuiltinExtension.Presence,
    )

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
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.pageBg)
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Header(state) }
        trust?.let { item { TrustCard(it, state.busy, onOpenTrustPrompt) } }
        state.trustInvalid?.let { item { InvalidTrustCard(it, state.busy, onTrustRepair) } }
        item { LifecycleCard(state, onRestartClick, onRestartConfirm, onRestartCancel) }
        item { InstallCard(state, onSpecChange, onScopeChange, onInstall, onRefresh) }
        item { BuiltinCard(state.builtins) }
        item { ListSectionHeading() }

        if (state.projectPackagesHidden) {
            item {
                Text(
                    text = PackageStrings.PROJECT_PACKAGES_HIDDEN,
                    style = MaterialTheme.typography.bodySmall,
                    color = PiTheme.palette.warning,
                )
            }
        }
        if (state.entries.isEmpty()) {
            item {
                // Three different facts, three different sentences. "Nothing ran" must
                // never read as "nothing is installed" — that is the lie this branch
                // used to tell when the runtime was missing.
                val notReady = state.listNotReady
                Text(
                    text = when {
                        notReady != null -> PackageStrings.LIST_NOT_READY + notReady
                        state.listUnparsed -> PackageStrings.LIST_UNPARSED
                        else -> PackageStrings.NO_PACKAGES
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (notReady != null || state.listUnparsed) palette.warning else palette.muted,
                )
            }
        }
        items(state.entries, key = { "${it.scope.name}:${it.source.raw}" }) { entry ->
            PackageRow(entry, state.busy, onRemove)
        }

        if (state.listUnparsed && state.listRaw.isNotBlank()) {
            item { RawBlock(PackageStrings.STDOUT_TITLE, state.listRaw) }
        }
        items(state.log, key = { it.command + it.headline }) { line -> LogCard(line) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Header(state: PiPackagesUiState) {
    val palette = PiTheme.palette
    Column(Modifier.padding(top = 12.dp)) {
        Text(PackageStrings.TITLE, style = MaterialTheme.typography.titleLarge, color = palette.text)
        Spacer(Modifier.height(4.dp))
        Text(PackageStrings.SUBTITLE, style = MaterialTheme.typography.bodySmall, color = palette.muted)
        if (state.engineAgentDir.isNotBlank() || state.rootfsAgentDir.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            PackageStrings.agentDirs(state.engineAgentDir, state.rootfsAgentDir).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.dim,
                )
            }
        }
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
    val palette = PiTheme.palette
    Surface(color = palette.cardBg, shape = PiShapes.card) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                PackageStrings.BUILTIN_TITLE,
                style = MaterialTheme.typography.titleSmall,
                color = palette.text,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                PackageStrings.BUILTIN_NOTE,
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted,
            )
            rows.forEach { row -> BuiltinRowView(row) }
            Spacer(Modifier.height(10.dp))
            Text(
                PackageStrings.BUILTIN_UNINSTALLABLE,
                style = MaterialTheme.typography.labelSmall,
                color = palette.dim,
            )
        }
    }
}

@Composable
private fun BuiltinRowView(row: PiPackagesUiState.BuiltinRow) {
    val palette = PiTheme.palette
    val presenceColor = when (row.presence) {
        PiBuiltinExtension.Presence.EngineAgentDir -> palette.success
        PiBuiltinExtension.Presence.RootfsCopyOnly -> palette.warning
        PiBuiltinExtension.Presence.Missing -> palette.error
    }
    Spacer(Modifier.height(10.dp))
    Surface(color = palette.infoBg, shape = PiShapes.cardInner) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text(
                row.extension.name,
                style = MaterialTheme.typography.labelLarge,
                color = palette.text,
            )
            val purpose = PackageStrings.builtinPurpose(row.extension.name)
            if (purpose.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(purpose, style = MaterialTheme.typography.labelSmall, color = palette.muted)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                PackageStrings.builtinPresence(row.presence),
                style = MaterialTheme.typography.labelSmall,
                color = presenceColor,
            )
            Spacer(Modifier.height(2.dp))
            Text(row.guestPath, style = MaterialTheme.typography.labelSmall, color = palette.dim)
        }
    }
}

/**
 * The heading over the `pi list` rows, so the two origins are visibly two lists:
 * this one is what `pi install` wrote into `settings.json`'s `packages`, and it is
 * the whole of what `pi list` reports (`package-manager-cli.ts:970-1002`).
 */
@Composable
private fun ListSectionHeading() {
    val palette = PiTheme.palette
    Column(Modifier.fillMaxWidth()) {
        Text(
            PackageStrings.LIST_SECTION_TITLE,
            style = MaterialTheme.typography.titleSmall,
            color = palette.text,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            PackageStrings.LIST_SECTION_NOTE,
            style = MaterialTheme.typography.labelSmall,
            color = palette.muted,
        )
    }
}

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
    Surface(color = palette.cardBg, shape = PiShapes.card) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("项目信任", style = MaterialTheme.typography.titleSmall, color = palette.text)
                Spacer(Modifier.weight(1f))
                Text(decisionText, style = MaterialTheme.typography.labelLarge, color = decisionColor)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = trust.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = if (trust.skippingResources) palette.warning else palette.muted,
            )
            if (trust.blocksProjectPackages) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = PackageStrings.SCOPE_PROJECT_LOCKED,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.warning,
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onOpenPrompt, enabled = !busy) {
                Text("作出信任决定…")
            }
        }
    }
}

@Composable
private fun InvalidTrustCard(message: String, busy: Boolean, onRepair: () -> Unit) {
    val palette = PiTheme.palette
    Surface(color = palette.toolErrorBg, shape = PiShapes.card) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                PackageStrings.TRUST_INVALID_TITLE,
                style = MaterialTheme.typography.titleSmall,
                color = palette.error,
            )
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = palette.text)
            Spacer(Modifier.height(6.dp))
            Text(
                PackageStrings.TRUST_INVALID_NOTE,
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onRepair, enabled = !busy) { Text(PackageStrings.TRUST_INVALID_ACTION) }
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
        Text(
            text = lifecycle.note,
            style = MaterialTheme.typography.bodySmall,
            color = PiTheme.palette.success,
        )
        return
    }

    val palette = PiTheme.palette
    val accent = when (lifecycle) {
        is ExtensionLifecycle.State.NeedsRestart -> palette.warning
        is ExtensionLifecycle.State.AwaitingIdle -> palette.warning
        is ExtensionLifecycle.State.Restarting -> palette.accent
        else -> palette.muted
    }
    Surface(color = palette.cardBg, shape = PiShapes.card) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = when (lifecycle) {
                    is ExtensionLifecycle.State.NeedsRestart -> PackageStrings.RESTART_NEEDED_TITLE
                    is ExtensionLifecycle.State.AwaitingIdle -> PackageStrings.RESTART_WAIT_TURN
                    is ExtensionLifecycle.State.Restarting -> PackageStrings.RESTARTING
                    is ExtensionLifecycle.State.Installing -> PackageStrings.RUNNING
                    else -> lifecycle.label
                },
                style = MaterialTheme.typography.titleSmall,
                color = accent,
            )
            when (lifecycle) {
                is ExtensionLifecycle.State.NeedsRestart -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        lifecycle.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                    )
                    lifecycle.lastError?.let { error ->
                        Spacer(Modifier.height(6.dp))
                        Text(error, style = MaterialTheme.typography.bodySmall, color = palette.error)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = ExtensionLifecycle.ANSWER_HINT,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.dim,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRestartClick) { Text(PackageStrings.RESTART_BUTTON) }
                        TextButton(onClick = onCancel) { Text(PackageStrings.RESTART_STAY) }
                    }
                }

                is ExtensionLifecycle.State.AwaitingIdle -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        lifecycle.turnNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.warning,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Re-checks the turn rather than promising a restart: the
                        // dialog only appears when the engine reports idle.
                        OutlinedButton(onClick = onRestartClick) { Text(PackageStrings.RESTART_BUTTON) }
                        TextButton(onClick = onCancel) { Text(PackageStrings.RESTART_STAY) }
                    }
                }

                is ExtensionLifecycle.State.AwaitingConfirmation -> {
                    // The dialog is rendered by PiPackagesScreen; the card is the
                    // fallback if the dialog was dismissed.
                    Spacer(Modifier.height(6.dp))
                    Text(
                        lifecycle.question,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.text,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onConfirm) { Text(PackageStrings.RESTART_CONFIRM) }
                        TextButton(onClick = onCancel) { Text(PackageStrings.RESTART_STAY) }
                    }
                }

                is ExtensionLifecycle.State.Restarting -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = PackageStrings.RESTARTING_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                    )
                }

                else -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        lifecycle.changesOrEmpty().joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
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
    AlertDialog(
        // Dismissal is a cancel, never an implicit confirm: `onDismissRequest`
        // routes to onCancel, matching pi's own "dismissal means no"
        // (`project-trust.ts:90-95`) and, more importantly, never restarting on a
        // back gesture.
        onDismissRequest = onCancel,
        title = { Text(PackageStrings.RESTART_NEEDED_TITLE, color = palette.text) },
        text = {
            Column {
                Text(question, style = MaterialTheme.typography.bodySmall, color = palette.muted)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "重启是显式的：在你点下确认之前，App 不会重启引擎。",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.dim,
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text(PackageStrings.RESTART_CONFIRM) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(PackageStrings.RESTART_STAY) } },
        containerColor = palette.cardBg,
        titleContentColor = palette.text,
        textContentColor = palette.muted,
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
    Surface(color = palette.cardBg, shape = PiShapes.card) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            OutlinedTextField(
                value = state.spec,
                onValueChange = onSpecChange,
                label = { Text(PackageStrings.SPEC_HINT) },
                singleLine = true,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            // Kept out of the label: a field label that long is clipped on a phone,
            // and the clarification has to be readable rather than truncated.
            Spacer(Modifier.height(4.dp))
            Text(
                text = PackageStrings.SPEC_GIT_UNAVAILABLE,
                style = MaterialTheme.typography.labelSmall,
                color = palette.warning,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Spacer(Modifier.height(6.dp))
                Text(
                    text = PackageStrings.SCOPE_PROJECT_LOCKED,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.dim,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
) {
    val palette = PiTheme.palette
    Surface(color = palette.infoBg, shape = PiShapes.cardInner) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    text = when (entry.source) {
                        is PiPackageSource.Npm -> "npm"
                        is PiPackageSource.Git -> "git"
                        is PiPackageSource.Local -> "本地"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.accent,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (entry.scope == PiPackageScope.User) {
                        PackageStrings.SCOPE_USER
                    } else {
                        PackageStrings.SCOPE_PROJECT
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.source.raw,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.text,
            )
            (entry.source as? PiPackageSource.Npm)?.let { npm ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append("包名 ${npm.name}")
                        npm.version?.let { append("，版本 $it") }
                        append(if (npm.pinned) "（精确版本，pi update --extensions 会跳过）" else "（范围/标签，可被 update 移动）")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.dim,
                )
            }
            (entry.source as? PiPackageSource.Git)?.let { git ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append("仓库 ${git.repo}")
                        append(if (git.ref != null) "，ref ${git.ref}（已钉住）" else "，未钉 ref")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.dim,
                )
            }
            if (entry.filtered) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "settings 里是对象形式：只加载显式列出的资源（可能只加载一部分）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.warning,
                )
            }
            entry.installedPath?.let { path ->
                Spacer(Modifier.height(2.dp))
                Text(path, style = MaterialTheme.typography.labelSmall, color = palette.dim)
            } ?: run {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "pi 没有报告安装路径——它可能尚未下载到磁盘，或该来源没有安装路径。",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.warning,
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onRemove(entry) }, enabled = !busy) {
                Text(PackageStrings.REMOVE_LABEL, color = palette.error)
            }
        }
    }
}

// ------------------------------------------------------------------- raw cards

@Composable
private fun LogCard(line: PiPackagesUiState.LogLine) {
    val palette = PiTheme.palette
    Surface(color = palette.cardBg, shape = PiShapes.card) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(line.headline, style = MaterialTheme.typography.titleSmall, color = palette.text)
            if (line.warnings.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                line.warnings.forEach { warning ->
                    Text(warning, style = MaterialTheme.typography.bodySmall, color = palette.warning)
                }
            }
            if (line.stderr.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                RawBlock(PackageStrings.STDERR_TITLE, line.stderr)
            }
            if (line.stdout.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                RawBlock(PackageStrings.STDOUT_TITLE, line.stdout)
            }
            Spacer(Modifier.height(6.dp))
            RawBlock(PackageStrings.COMMAND_TITLE, line.command)
        }
    }
}

@Composable
private fun RawBlock(title: String, body: String) {
    val palette = PiTheme.palette
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = palette.muted)
        Spacer(Modifier.height(2.dp))
        Surface(color = palette.pageBg, shape = PiShapes.cardInner) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = palette.toolOutput,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            )
        }
    }
}
