package app.pi.packages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pi.runtime.PtyLauncher
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A settings-page entry row, so the settings stack can point at [PiPackagesHost]
 * without knowing anything about packages — the same shape
 * `DeviceCapabilityEntryRow` uses for the device-capability page.
 *
 * The row lives here, next to the screen it opens, so the settings patch is one
 * import and one `item { }` rather than a new file in someone else's package.
 */
@Composable
fun PiPackagesEntryRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = PiSpacing.screen, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    PackageStrings.TITLE,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    PackageStrings.SUBTITLE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "打开",
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The mountable half of the package-management feature.
 *
 * [PiPackagesScreen] is state-driven by design — "everything the user sees is derived
 * from [PiPackagesUiState], which is assembled outside this package" — but nothing in
 * the app assembled that state, so the screen had **no caller** and the install
 * feature did not exist from the user's point of view. This file is that assembly:
 * one composable a settings route can mount, plus the small controller that owns the
 * state and performs the guest calls.
 *
 * It is deliberately self-contained so the settings navigation needs one entry and
 * one branch, not a ViewModel, a service graph and a new state machine:
 *
 * ```
 * import app.pi.packages.PiPackagesHost
 *
 * var packages by remember { mutableStateOf(false) }
 * // in the stack's `when`: packages -> PiPackagesHost(contentPadding, onBack = { packages = false })
 * ```
 *
 * ## What it does NOT do
 *
 *  - It never restarts the engine by itself. The restart is delegated to
 *    [EngineRestartCoordinator], which refuses while a turn is running and requires a
 *    confirmation. When the host is mounted without an engine hook
 *    (`restartEngine == null`) the button says so instead of pretending: the package
 *    command still runs and still reports that a restart is needed — that part is
 *    [PiPackageService.restartRequirement]'s job, not this file's.
 *  - It never writes a trust decision without the user answering
 *    [PiProjectTrustPrompt]. Session-only answers are kept in this class's memory and
 *    are not written to `trust.json`, exactly as pi treats them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiPackagesHost(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    /**
     * The engine's restart, supplied by whoever owns `PiEngineHost`:
     * `{ reason, allowInterrupt -> host.restart(reason, allowInterrupt).asOutcome() }`
     * (see `Restart.asOutcome`). Null keeps the screen honest: no restart button that
     * cannot do anything.
     */
    restartEngine: (suspend (reason: String, allowInterrupt: Boolean) -> EngineRestartCoordinator.Outcome)? = null,
    /** Live turn state from the engine; `{ false }` when the engine is not attached. */
    isTurnRunning: () -> Boolean = { false },
    /** `SettingsManager.getDefaultProjectTrust()`, which coerces to `"ask"`. */
    defaultProjectTrust: String = "ask",
) {
    val context = LocalContext.current
    val layout = remember(context) {
        AgentLayout(
            context = context.applicationContext,
            hostWorkspace = PtyLauncher.workspaceHost(context),
        )
    }
    val guest = remember(layout) { GuestCommand(layout) }
    val service = remember(layout) { PiPackageService(layout, guest) }
    val trustRepository = remember(layout) { TrustRepository(layout, guest) }
    val lifecycle = remember { ExtensionLifecycle() }
    val coordinator = remember(layout, restartEngine) {
        restartEngine?.let { engine ->
            EngineRestartCoordinator(
                lifecycle = lifecycle,
                isTurnRunning = isTurnRunning,
                restartEngine = engine,
            )
        }
    }
    val controller = remember(layout, coordinator) {
        PiPackagesController(layout, service, trustRepository, lifecycle, coordinator, defaultProjectTrust)
    }

    val lifecycleState by lifecycle.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { controller.refresh() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(PackageStrings.TITLE) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        Box(Modifier.fillMaxSize().padding(contentPadding)) {
            PiPackagesScreen(
                state = controller.state(lifecycleState),
                onSpecChange = { controller.spec = it },
                onScopeChange = { controller.chooseScope(it) },
                onInstall = { scope.launch { controller.install() } },
                onRemove = { entry -> scope.launch { controller.remove(entry) } },
                onRefresh = { scope.launch { controller.refresh() } },
                onRestartClick = { controller.requestRestart() },
                onRestartConfirm = { scope.launch { controller.confirmRestart() } },
                onRestartCancel = { controller.cancelRestart() },
                onOpenTrustPrompt = { controller.openTrustPrompt() },
                onTrustChoose = { option -> scope.launch { controller.chooseTrust(option) } },
                onTrustDismiss = { controller.dismissTrustPrompt() },
                onTrustRepair = { scope.launch { controller.repairTrust() } },
            )
        }
    }
}

/**
 * State + actions for [PiPackagesHost].
 *
 * A plain class rather than a ViewModel because it needs no lifecycle of its own:
 * every value it holds is derived from a file or a guest command, and the only
 * in-memory state that is not (the session-only trust answer, the typed spec, the
 * log) is worthless after the screen goes away. Compose's snapshot state is what
 * makes it observable.
 *
 * Every blocking call is a `GuestCommand.run` (a proot process), so all of them go
 * through [Dispatchers.IO]; nothing here touches the main thread beyond assigning
 * snapshot values.
 */
class PiPackagesController(
    private val layout: AgentLayout,
    private val service: PiPackageService,
    private val trustRepository: TrustRepository,
    private val lifecycle: ExtensionLifecycle,
    /** Null when the engine hook was not supplied; the restart button says so. */
    private val coordinator: EngineRestartCoordinator?,
    private val defaultProjectTrust: String,
) {

    var spec by mutableStateOf("")
    var scope by mutableStateOf(PiPackageScope.User)
        private set

    var busy by mutableStateOf(false)
        private set

    private var entries by mutableStateOf<List<PiPackageEntry>>(emptyList())
    private var listRaw by mutableStateOf("")
    private var listUnparsed by mutableStateOf(false)
    private var projectPackagesHidden by mutableStateOf(false)
    private var log by mutableStateOf<List<PiPackagesUiState.LogLine>>(emptyList())
    private var panel by mutableStateOf<PiPackagesUiState.TrustPanel?>(null)
    private var promptVisible by mutableStateOf(false)
    private var trustInvalid by mutableStateOf<String?>(null)

    /** Null means "no answer yet"; `true`/`false` are session-only decisions. */
    private var sessionTrustAnswer by mutableStateOf<Boolean?>(null)

    fun state(lifecycleState: ExtensionLifecycle.State): PiPackagesUiState = PiPackagesUiState(
        installedRoot = layout.agentTruthDir.absolutePath,
        guestWorkspace = layout.guestWorkspace,
        spec = spec,
        scope = scope,
        busy = busy,
        lifecycle = lifecycleState,
        entries = entries,
        listRaw = listRaw,
        listUnparsed = listUnparsed,
        projectPackagesHidden = projectPackagesHidden,
        log = log,
        trust = panel?.copy(promptVisible = promptVisible),
        trustInvalid = trustInvalid,
    )

    // Named `chooseScope`, not `setScope`: `var scope` already generates a
    // `setScope(PiPackageScope)` accessor, and a function with the same name and
    // parameter is a "Platform declaration clash: the following declarations have the
    // same JVM signature" - caught by the Gradle release build (CI, step 9) and
    // invisible to tools/typecheck.sh.
    fun chooseScope(next: PiPackageScope) {
        scope = next
    }

    // ---------------------------------------------------------------- commands

    suspend fun refresh() {
        busy = true
        try {
            val facts = readTrust()
            val listing = io {
                service.list(
                    trust = PiPackageService.TrustPass.None,
                    projectTrusted = facts.trusted,
                )
            }
            entries = listing.entries
            listRaw = listing.raw
            listUnparsed = listing.entries.isEmpty() && listing.raw.isNotBlank()
            projectPackagesHidden = listing.projectPackagesHidden
            record(
                headline = "pi list：${listing.entries.size} 项",
                command = listing.argv.joinToString(" "),
                stdout = listing.raw,
                stderr = listing.stderr,
            )
        } finally {
            busy = false
        }
    }

    suspend fun install() {
        val requested = spec.trim()
        if (requested.isEmpty()) return
        if (!lifecycle.beginInstall("install", requested)) return
        busy = true
        try {
            val facts = readTrust()
            val target = scope
            // `--approve` is pi's "trust project-local files for this one command"
            // (`package-manager-cli.ts:454-462`). It is passed only for a project
            // scope the user has actually trusted: the flag is not a licence to
            // override a refusal.
            val trust = if (target == PiPackageScope.Project && facts.trusted) {
                PiPackageService.TrustPass.Approve
            } else {
                PiPackageService.TrustPass.None
            }
            val done = io { service.install(requested, target, trust) }
            finish(done)
        } finally {
            busy = false
        }
    }

    suspend fun remove(entry: PiPackageEntry) {
        if (!lifecycle.beginInstall("remove", entry.source.raw)) return
        busy = true
        try {
            val facts = readTrust()
            val trust = if (entry.scope == PiPackageScope.Project && facts.trusted) {
                PiPackageService.TrustPass.Approve
            } else {
                PiPackageService.TrustPass.None
            }
            val done = io { service.remove(entry.source.raw, entry.scope, trust) }
            finish(done)
        } finally {
            busy = false
        }
    }

    /**
     * The state machine decides whether this is a question, a wait or nothing:
     * [EngineRestartCoordinator.request] never touches the engine.
     */
    fun requestRestart() {
        val engine = coordinator
        if (engine == null) {
            record(
                headline = "重启未接入：设置页还没有拿到引擎的 restart()",
                command = "",
                stdout = "",
                stderr = "",
            )
            return
        }
        when (engine.request("新安装的资源需要被 pi 重新加载")) {
            ExtensionLifecycle.RequestOutcome.NeedsConfirmation,
            ExtensionLifecycle.RequestOutcome.WaitingForTurn,
            ExtensionLifecycle.RequestOutcome.AlreadyReady,
            ExtensionLifecycle.RequestOutcome.NothingPending,
            ExtensionLifecycle.RequestOutcome.BusyWithPackageCommand,
            -> Unit
        }
    }

    suspend fun confirmRestart() {
        val engine = coordinator ?: return
        busy = true
        try {
            val outcome = engine.confirm("用户确认重启引擎")
            record(
                headline = when (outcome) {
                    is EngineRestartCoordinator.Outcome.Ok -> "引擎已重启"
                    is EngineRestartCoordinator.Outcome.Refused -> outcome.message
                    is EngineRestartCoordinator.Outcome.Failed -> outcome.message
                },
                command = "",
                stdout = "",
                stderr = "",
            )
        } finally {
            busy = false
        }
    }

    fun cancelRestart() {
        lifecycle.cancelRestart()
    }

    // ------------------------------------------------------------------ trust

    fun openTrustPrompt() {
        promptVisible = true
    }

    fun dismissTrustPrompt() {
        // pi maps a dismissed select to "not trusted" (`project-trust.ts:90-95`);
        // the app says "no decision this time" and writes nothing.
        promptVisible = false
    }

    suspend fun chooseTrust(option: ProjectTrust.Option) {
        busy = true
        try {
            val canonical = io { trustRepository.canonicalizeGuestPath(layout.guestWorkspace) }
            val result = io { trustRepository.apply(option, canonical) }
            if (option.sessionOnly) {
                sessionTrustAnswer = option.trusted
                record("本次会话：${option.label}", "", "", "")
            } else {
                sessionTrustAnswer = null
                record("trust.json：${option.label} —— ${result.message}", "", "", "")
            }
            promptVisible = false
        } finally {
            busy = false
        }
        refresh()
    }

    suspend fun repairTrust() {
        busy = true
        try {
            val result = io { trustRepository.repairInvalidStore() }
            record("修复 trust.json：${result.message}", "", "", "")
        } finally {
            busy = false
        }
        refresh()
    }

    // --------------------------------------------------------------- internals

    private class TrustFacts(val trusted: Boolean)

    private suspend fun readTrust(): TrustFacts {
        val canonical = io {
            runCatching { trustRepository.canonicalizeGuestPath(layout.guestWorkspace) }
                .getOrDefault(layout.guestWorkspace)
        }
        val store = io { trustRepository.read() }
        trustInvalid = store.invalid?.message
        val hasResources = io {
            runCatching { trustRepository.hasTrustRequiringResources(layout.guestWorkspace) }
                .getOrDefault(false)
        }
        val decision = io { trustRepository.decisionFor(canonical) }
        val resolution = ProjectTrust.resolve(
            ProjectTrust.Inputs(
                cwd = canonical,
                hasTrustRequiringResources = hasResources,
                savedDecision = decision,
                defaultProjectTrust = defaultProjectTrust,
                // The app *is* the UI pi does not have in RPC mode; that is the whole
                // reason this prompt exists. `ProjectTrust.resolve` still refuses to
                // invent an answer: without `userAnswer` it reports NoUiRefused and the
                // screen shows the prompt.
                hasUI = true,
                userAnswer = sessionTrustAnswer,
            ),
        )
        panel = PiPackagesUiState.TrustPanel(
            decision = decision,
            explanation = resolution.explanation
                ?: if (resolution.trusted) PackageStrings.TRUST_ALREADY_TRUSTED else "",
            promptOptions = ProjectTrust.options(canonical),
            promptVisible = promptVisible,
            skippingResources = hasResources && !resolution.trusted,
            blocksProjectPackages = io { service.projectDeclaresPackages() },
        )
        return TrustFacts(resolution.trusted)
    }

    private fun finish(done: PiPackageService.Done) {
        val headline = when (done) {
            is PiPackageService.Done.Ok -> done.summary
            is PiPackageService.Done.Failed -> "失败：${done.message}"
            is PiPackageService.Done.Refused -> done.summary
            is PiPackageService.Done.TimedOut -> done.summary
            is PiPackageService.Done.NotReady -> done.summary
        }
        record(headline, done.argv.joinToString(" "), done.stdout, done.stderr, done.warnings)
        when (done) {
            is PiPackageService.Done.Ok -> {
                val required = service.restartRequirement(done)
                if (required != null) {
                    lifecycle.installSucceeded(required.changes, required.detail)
                } else {
                    lifecycle.installSucceeded(listOf(done.summary), "")
                }
            }

            is PiPackageService.Done.Failed -> lifecycle.installFailed(done.message)
            is PiPackageService.Done.Refused -> lifecycle.installFailed(done.summary)
            is PiPackageService.Done.TimedOut -> lifecycle.installFailed(done.summary)
            is PiPackageService.Done.NotReady -> lifecycle.installFailed(done.summary)
        }
    }

    private fun record(
        headline: String,
        command: String,
        stdout: String,
        stderr: String,
        warnings: List<String> = emptyList(),
    ) {
        log = (
            listOf(PiPackagesUiState.LogLine(headline, command, stdout, stderr, warnings)) + log
            ).take(MAX_LOG_LINES)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val MAX_LOG_LINES = 20
    }
}
