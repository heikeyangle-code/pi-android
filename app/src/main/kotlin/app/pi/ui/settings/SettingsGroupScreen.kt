package app.pi.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.pi.ui.PiTopBar
import app.pi.ui.PiTopBarIcon
import app.pi.ui.components.EffectiveKind
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiThemeEntry
import kotlinx.serialization.json.JsonPrimitive

/**
 * Settings level 1: one group, rendered straight from the registry.
 *
 * Rows are grouped by their `section`, so a long group such as 终端与 Shell keeps
 * "basic vs advanced" legible. The screen owns its editor state, so it is usable
 * on its own, and it accepts a [highlightKey] so a global-search hit can jump to
 * a row and tint it (spec §6.3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsGroupScreen(
    groupId: String,
    store: PiSettingsStore,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    highlightKey: String? = null,
    /**
     * A counter that changes when a row's value has to be read again — **无论那次改动是本 App
     * 自己写的还是外部的**。
     *
     * The rows read `store.read(...)` during composition, and the real store
     * (`PiSettingsFileStore`) is not snapshot state, so a write cannot recompose anything by
     * itself — an edited `settings.json` kept showing the old value until the user left and
     * re-entered the group. Rebuilding the row list on a new epoch re-reads every value.
     *
     * 两个来源都走这个参数，缺一个就会留下一类「调了不管事」：外部改动由 `PiFileWatch`
     * 的 epoch 报（它只覆盖被监视的 pi 文件），本进程自己的写入由 `PiSettingsStack` 的
     * `writeEpoch` 报 —— `app.*` 写的是没被监视的 `app-prefs.json`，只靠前者刷新不了
     * （`PiSettingsStack.kt` 里 `writeEpoch` 的注释是完整的推导）。
     */
    freshness: Int = 0,
    knownThemes: List<PiThemeEntry> = emptyList(),
    themeNotes: List<String> = emptyList(),
    themeError: String? = null,
    onSettingWritten: (String) -> Unit = {},
    onRunAction: ((PiSetting) -> Unit)? = null,
    /**
     * Rows whose work is not a one-shot command but a screen of their own, by key:
     * the credential form and the local-model endpoint form, which are Action rows,
     * plus the read-only `packages` row, which must open the package manager rather
     * than a list editor (see `PiSettingsStack.hostActions`). Such a row opens that
     * screen directly: its description already is the explanation, so an extra
     * 执行/取消 confirmation would be one tap that decides nothing.
     *
     * Empty by default, which leaves every row on the [onRunAction] path — the
     * behaviour every existing caller has.
     */
    hostActions: Map<String, () -> Unit> = emptyMap(),
    /**
     * Values that do not live in the settings store, by key: the read-only
     * 运行时 rows, whose facts come from the runtime tree and the running
     * service (see [RuntimeFacts]). An override is rendered verbatim instead of
     * `display(store value)`, so a row can never fall back to a default that
     * looks like a reading — the host always supplies either the real value or a
     * sentence saying why it could not be read.
     *
     * Empty by default: every other row keeps reading the store.
     */
    valueOverrides: Map<String, String> = emptyMap(),
    /**
     * **Evidence under a read-only row**, by key: the proroot gate's recorded
     * per-phase lines, which the 运行时（实际生效）row shows so that "why is proroot
     * not in use" is answerable without opening the diagnostic report.
     *
     * Same shape and same rule as [valueOverrides]: the host already has the lines
     * (it read the probe cache once, off the main thread, to build the row's value) and
     * hands the finished, bounded string down. This screen only lays it out — it never
     * reads a file, a probe cache or a `.so` digest, and it never runs a probe. A row
     * with no entry renders exactly as before; an entry is a *block* of lines, not a
     * value, so it is a map of its own rather than a [valueOverrides] value (which the
     * row draws with `maxLines = 1`).
     */
    detailOverrides: Map<String, String> = emptyMap(),
    /**
     * The host's engine restart, offered by the badge explanation of a
     * [EffectiveKind.RestartEngine] row ("重启引擎"). Null hides that button, which
     * is right where no engine hook exists: the row's own explanation still says
     * what has to happen.
     *
     * [EffectiveKind.AutoRestartEngine] rows deliberately do **not** use it, even
     * when this is non-null: their write already restarted the engine, so a button
     * would either repeat that or fall through to the generic action channel and
     * answer 「当前不可用」 for a row that is working. See the `when` at the
     * `PiEffectiveDialog` call.
     */
    onRestartEngine: (() -> Unit)? = null,
    /**
     * 顶栏右侧那个搜索图标（v2 `SettingsGroup` 的 `TopBar right`）。`null` 时不画它，
     * 因为一个打不开搜索的入口就是第二处「看起来能点但没反应」。
     */
    onOpenSearch: (() -> Unit)? = null,
) {
    val group = PiSettingsCatalog.group(groupId)
    val sections = remember(groupId, freshness) { buildGroupSections(groupId) }
    val listState = rememberLazyListState()

    var editing by remember { mutableStateOf<PiSetting?>(null) }
    var explaining by remember { mutableStateOf<PiSetting?>(null) }
    var confirming by remember { mutableStateOf<PiSetting?>(null) }

    // 行上的三个动作。抽成 lambda 是因为页面底部还有一处「危险操作」要画同一个
    // `app.security.emergencyStop` 行（v2 的 `SettingsGroup` 把它固定在页尾），
    // 两处必须走同一条路径，否则同一个动作会有两种行为。
    val openRow: (PiSetting) -> Unit = { setting ->
        val hostAction = hostActions[setting.key]
        if (hostAction != null) {
            hostAction()
        } else if (setting.kind == PiRowKind.Action) {
            confirming = setting
        } else if (setting.kind != PiRowKind.Switch && !setting.readOnly) {
            // 只读行（运行时那六行）没有可打开的东西：`PiSettingRow` 早就没给它们 chevron，
            // 但点击一直通着 —— 点「pi 版本」（行上是当时的引擎版本 `0.85.1`）会打开一个空的、禁用的
            // 文本框。判据与 chevron 用同一个 `readOnly`，两处不再分叉
            // （`docs/settings-audit-impl.md` §B4）。
            editing = setting
        }
    }
    val toggleRow: (PiSetting, Boolean) -> Unit = { setting, next ->
        store.write(setting.key, JsonPrimitive(next))
        // Switch rows write in place, so they never reach the editor sheet's
        // callback; a switch the app reads (the thinking toggle, timestamps, tool
        // expansion) would otherwise stay inert.
        onSettingWritten(setting.key)
    }

    // 换一个分组就回到顶部：`listState` 由 `rememberLazyListState()` 持有，而它**不随
    // `groupId` 重置**，所以从长分组（运行时与诊断）退到短分组时列表会停在分组底部之外
    // （`docs/settings-audit-impl.md` §B15）。放在命中跳转之前，让"从搜索跳进来"仍然
    // 滚到命中的那一节。
    LaunchedEffect(groupId) {
        listState.scrollToItem(0)
    }

    LaunchedEffect(highlightKey, groupId) {
        if (highlightKey == null) return@LaunchedEffect
        // 一个分区是一个 LazyColumn item（分区头 + 装行的卡片），所以命中的目标
        // 索引是「包含这一行的那个分区」，不是行在注册表里的序号。
        val index = sections.indexOfFirst { section ->
            section.settings.any { it.key == highlightKey }
        }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    // 同 `SettingsHome`：顶栏从状态栏之下开始，见 `settingsPageTopInset`。
    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(
            title = group?.title ?: "设置",
            onBack = onBack,
            // v2 的分组页顶栏右侧是搜索图标（`phone34`–`phone38` 五台都有）：进了分组
            // 才发现要找的是另一组时，不用先退回首页。
            actions = if (onOpenSearch != null) {
                {
                    PiTopBarIcon(
                        onClick = onOpenSearch,
                        contentDescription = "搜索设置",
                        icon = Icons.Filled.Search,
                    )
                }
            } else {
                null
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            items(sections) { section ->
                PiSettingsSectionHeader(
                    label = section.label,
                    count = "${section.settings.size} 项",
                )
                // v2 的分组容器：圆角 10、surfaceContainerLow 底、组内 1px inset
                // hairline，无描边无阴影（06 §2「分组容器」）。
                PiSettingsCard {
                    section.settings.forEachIndexed { index, setting ->
                        if (index > 0) PiSettingsHairline()
                        SettingSlot(
                            setting = setting,
                            store = store,
                            valueOverrides = valueOverrides,
                            detailOverrides = detailOverrides,
                            highlighted = setting.key == highlightKey,
                            onToggle = toggleRow,
                            onOpen = openRow,
                            onExplain = { explaining = it },
                        )
                    }
                }
            }
            // 危险行固定在分组页末尾（v2 `SettingsGroup` 的结构提案，`phone34`–`phone38`
            // 五台都画了它，标题落款「本版固定在页尾」）。它画的是同一个
            // `app.security.emergencyStop` 行 —— 不新写一行、不新写文案；已经在某一组
            // 里的那一页（安全与信任）不再重复画一遍。
            val danger = PiSettingsCatalog.byKey[DANGER_ROW_KEY]
            val alreadyShown = sections.any { section ->
                section.settings.any { it.key == DANGER_ROW_KEY }
            }
            if (danger != null && !alreadyShown) {
                item {
                    PiSettingsSectionHeader(
                        label = "危险操作",
                        count = "1 项",
                        aside = "本版固定在页尾",
                    )
                    PiSettingsCard {
                        SettingSlot(
                            setting = danger,
                            store = store,
                            valueOverrides = valueOverrides,
                            detailOverrides = detailOverrides,
                            highlighted = danger.key == highlightKey,
                            onToggle = toggleRow,
                            onOpen = openRow,
                            onExplain = { explaining = it },
                        )
                    }
                }
            }
        }
    }

    val openEditor = editing
    if (openEditor != null) {
        PiSettingEditorSheet(
            setting = openEditor,
            store = store,
            // The sheet must start from the value the row showed: the 运行时 rows render a
            // `RuntimeFacts` override, not the store value (`docs/settings-audit-impl.md` §B4).
            valueOverrides = valueOverrides,
            knownThemes = knownThemes,
            themeNotes = themeNotes,
            themeError = themeError,
            onDismiss = { editing = null },
            // Every write is announced, not just the theme: several keys
            // (`hideThinkingBlock`, the `app.appearance.*` rows) are read by the
            // app itself, and this screen is the only place that knows one
            // changed. Without this they would be inert again.
            onWritten = { written -> onSettingWritten(written.key) },
        )
    }

    val openExplanation = explaining
    if (openExplanation != null) {
        val run = onRunAction
        PiEffectiveDialog(
            kind = openExplanation.effective,
            settingTitle = openExplanation.title,
            onDismiss = { explaining = null },
            // The badge's button is "the action that applies this value". For
            // `RestartEngine` that action is the engine restart itself, which the
            // host owns (设置 → 进程 → 重启引擎) — the generic action channel is for
            // `PiRowKind.Action` rows and would answer "not implemented" here.
            //
            // `AutoRestartEngine` deliberately has **no** action: the write already
            // restarted the engine, so a button here would either do it twice or —
            // worse — fall through to the generic channel, whose answer for a
            // non-Action row is 「当前不可用」. Null gives the dialog its 知道了.
            onRunAction = when {
                openExplanation.effective == EffectiveKind.RestartEngine -> onRestartEngine
                openExplanation.effective == EffectiveKind.AutoRestartEngine -> null
                run != null -> { { run(openExplanation) } }
                else -> null
            },
        )
    }

    val openConfirmation = confirming
    if (openConfirmation != null) {
        val run = onRunAction
        PiSettingsDialog(
            onDismissRequest = { confirming = null },
            title = openConfirmation.title,
            // A caller that passes no dispatcher gets the row's own text plus
            // one neutral sentence saying the entry does not work here. It must
            // not describe the host wiring: that is internal, and `PiRoot` — the
            // only production caller — always passes a dispatcher, so this
            // branch is a preview/test path.
            body = if (run == null) {
                openConfirmation.description + "\n\n这个入口当前不可用。"
            } else {
                openConfirmation.description
            },
            confirmationLabel = when {
                run == null -> "知道了"
                openConfirmation.dangerous -> "确认执行"
                else -> "执行"
            },
            onConfirm = {
                run?.invoke(openConfirmation)
                confirming = null
            },
            dismissalLabel = if (run == null) null else "取消",
            onDismissButton = { confirming = null },
        )
    }
}

/**
 * 一行行槽：把 [PiSettingRow] 需要的五个值从 store 与宿主参数里取齐。
 *
 * 存在的理由是分组页有两处画行（分区里的卡片、页尾固定的危险行），两处必须用同一套
 * 取值 —— 尤其是「当前生效值」的 2px accent 条判定，抄一遍就会分叉。
 *
 * 只读行可以再带一块**证据**（[detailOverrides]）：proroot 探针逐阶段的原始判读。
 * 它写在行下面而不是行里 —— 行的值只有一行（`PiSettingRow` 用 `maxLines = 1` 画），
 * 而证据是多行；也不做成一个可点开的二级页，因为「为什么没用上 proroot」正是用户
 * 站在这一页时要回答的问题，多一次点击就是把答案藏起来。文案与截断都由宿主算好
 * （[runtimeDetailOverrides]），这里只负责排版，不读文件、不碰探针。
 */
@Composable
private fun SettingSlot(
    setting: PiSetting,
    store: PiSettingsStore,
    valueOverrides: Map<String, String>,
    detailOverrides: Map<String, String>,
    highlighted: Boolean,
    onToggle: (PiSetting, Boolean) -> Unit,
    onOpen: (PiSetting) -> Unit,
    onExplain: (PiSetting) -> Unit,
) {
    val detail = detailOverrides[setting.key]
    Column {
        PiSettingRow(
            setting = setting,
            valueText = valueOverrides[setting.key] ?: setting.display(setting.current(store)),
            checked = setting.boolIn(store, false),
            current = isCurrentValue(setting, store),
            highlighted = highlighted,
            onToggle = { next -> onToggle(setting, next) },
            onOpen = { onOpen(setting) },
            onExplainEffect = { onExplain(setting) },
        )
        if (!detail.isNullOrBlank()) {
            // 副行字色（`onSurfaceVariant`）而不是正文色：这是元信息，不是第二个值。
            // 左内边距与行一致，让它读起来是这一行的下半部分；底部留同样的行内边距，
            // 一行与下一行之间才不会因为这块文本挤在一起。
            Text(
                detail,
                modifier = Modifier.padding(
                    start = PiSettingsMetrics.rowPaddingHorizontal,
                    end = PiSettingsMetrics.rowPaddingHorizontal,
                    bottom = PiSettingsMetrics.rowPaddingVertical,
                ),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** v2 固定在分组页页尾的那一行（`phone34`–`phone38`）。 */
private const val DANGER_ROW_KEY = "app.security.emergencyStop"

/** 一个分区（v2 的 `Section`）：分区头 + 一组行，是 LazyColumn 的一个 item。 */
private class GroupSection(val label: String, val settings: List<PiSetting>)

private fun buildGroupSections(groupId: String): List<GroupSection> {
    val settings = PiSettingsCatalog.settingsIn(groupId)
        // 三个 settings.json 选择键的编辑器搬进了「模型与供应商」（`ModelProviderScreen`），
        // 分组屏不再渲染这三行 —— 注册表保留条目是为了首页摘要、搜索与 `/scoped-models`
        // 焦点还能按 key 找到；名单只有一个来源（`MODEL_SELECTION_KEYS`），两处共用，
        // 否则搜索会点进一个不存在的行。
        .filterNot { it.key in MODEL_SELECTION_KEYS }
    return PiSettingsCatalog.sectionsIn(groupId).mapNotNull { section ->
        val rows = settings.filter { it.section == section }
        // 过滤后空掉的段连同段头一起去掉：一个没有行的段头就是新的「点了没反应」。
        if (rows.isEmpty()) null else GroupSection(section, rows)
    }
}

/**
 * 这一行是不是「当前生效值」（v2 数据里的 `cur`，画左缘 2px accent 条）。
 *
 * v2 的两处 `cur` 是「默认模型」与「主题」，都是 Value 行，且都是用户自己选过的
 * 那一个值；注册表里没有 `cur` 字段，可用的事实是「这个键在 store 里有显式值」
 * （`isExplicit` = `store.read(key) != null`），也就是它不再等于 pi 的内置默认。
 * 因此判定 = 行型是 Value 且值被显式写过。Switch / Action 行不参与 —— v2 也没给
 * 它们画条。
 *
 * 为什么**不**限制成「每组只画一条」：显式写过的每一行都确实在生效，把其中几条
 * 压掉是为了视觉整齐而撒谎；注册表里也没有 `cur` 这类字段可以抄，加一个宿主传入的
 * `currentKeys` 等于为一个纯视觉的约束新增参数。所以一个分组下出现多条 accent 条
 * 是允许的、也是诚实的。
 */
private fun isCurrentValue(setting: PiSetting, store: PiSettingsStore): Boolean =
    setting.kind == PiRowKind.Value && setting.isExplicit(store)
