package app.pi.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import app.pi.ui.components.EffectiveKind
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiThemeEntry
import app.pi.ui.theme.PiThemeScope

/**
 * Level-2 editors for the settings stack (spec §6.1).
 *
 * Everything is a `ModalBottomSheet`, because on a phone a sheet is easier to
 * reach and dismiss than a system dropdown (spec §7.1). The one rule all four
 * editors obey: they hand back the value the user chose, and never normalise it.
 * In particular a list editor writes entries back verbatim, so `!pattern`,
 * `+path` and `-path` markers survive a round trip.
 */

/** Automatic theme mode stores one literal string, so parse it for editing only. */
internal fun splitAutoTheme(raw: String): Pair<String, String>? {
    val slash = raw.indexOf('/')
    if (slash <= 0) return null
    val lightTheme = raw.substring(0, slash).trim()
    val darkTheme = raw.substring(slash + 1).trim()
    if (lightTheme.isEmpty() || darkTheme.isEmpty()) return null
    return lightTheme to darkTheme
}

/**
 * What the effective badge means, plus the action that applies it (spec §6.5).
 * pi has four different timings; a badge without this explanation is just noise.
 */
@Composable
fun PiEffectiveDialog(
    kind: EffectiveKind,
    settingTitle: String,
    onDismiss: () -> Unit,
    onRunAction: (() -> Unit)?,
) {
    if (kind == EffectiveKind.Immediate) return
    val (label, explanation, actionLabel) = when (kind) {
        EffectiveKind.Reload -> Triple(
            "需要重载",
            "改动已保存，重载后生效。",
            "立即重载",
        )

        EffectiveKind.NewSession -> Triple(
            "需要新会话",
            "当前会话不会改变，开一个新会话之后生效。",
            "新建会话",
        )

        EffectiveKind.RestartEngine -> Triple(
            "需要重启引擎",
            "改动已保存，重启引擎后生效。重启会终止正在进行的回合，已写入磁盘的会话不会丢失。",
            "重启引擎",
        )

        EffectiveKind.RestartApp -> Triple(
            "需要重启 App",
            "这个值在 App 启动时读取，必须先结束 App 再启动。会话与文件不会受影响。",
            "重启 App",
        )

        EffectiveKind.Immediate -> return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = { Text("「$settingTitle」$explanation") },
        confirmButton = {
            if (onRunAction != null) {
                TextButton(
                    onClick = {
                        onRunAction()
                        onDismiss()
                    },
                ) { Text(actionLabel) }
            } else {
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        },
        dismissButton = if (onRunAction != null) {
            { TextButton(onClick = onDismiss) { Text("稍后") } }
        } else {
            null
        },
    )
}

/** Enum picker (spec §6.2 ValueRow). One tap, then it closes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiOptionPickerSheet(
    title: String,
    description: String,
    options: List<PiOption>,
    currentWire: String,
    allowCustom: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.screen),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSpacing.unit))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { option ->
                    val selected = option.wire == currentWire
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPick(option.wire)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                option.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            if (option.description != null) {
                                Text(
                                    option.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            option.wire,
                            style = PiTheme.text.monoSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "已选中",
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
            if (allowCustom) {
                Spacer(Modifier.height(PiSpacing.unit))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("自定义值") },
                        textStyle = PiTheme.text.mono,
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            val trimmed = custom.trim()
                            if (trimmed.isNotEmpty()) {
                                onPick(trimmed)
                                onDismiss()
                            }
                        },
                    ) { Text("使用") }
                }
            } else {
                Spacer(Modifier.height(PiSpacing.unit))
            }
            Spacer(Modifier.height(PiSpacing.unit))
        }
    }
}

/**
 * Number editor (spec §6.2 NumberRow): a slider for coarse movement, a text field
 * for an exact value, and a stepper for the small ranges such as the +/-2sp font
 * adjustment. "恢复默认" writes the registry default, which is often null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiNumberEditorSheet(
    setting: PiSetting,
    initial: Int?,
    onSet: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val low = setting.min ?: 0
    val start = initial ?: setting.defaultValue?.intValueOrNull() ?: low
    val high = setting.max ?: (start + 1000).coerceAtLeast(start + 1)
    val step = (setting.step ?: 1).coerceAtLeast(1)
    val bounded = start.coerceIn(low, high)
    val range = low.toFloat()..high.toFloat()
    // Material draws one tick per step; a million-token range would draw
    // hundreds of them, so only quantise when the count stays readable.
    val rawSteps = (((high - low) / step) - 1).coerceAtLeast(0)
    val sliderSteps = if (rawSteps <= 24) rawSteps else 0

    var value by remember(bounded) { mutableStateOf(bounded) }
    var typed by remember(bounded) { mutableStateOf(bounded.toString()) }

    fun update(next: Int) {
        val clamped = next.coerceIn(low, high)
        value = clamped
        typed = clamped.toString()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.screen),
        ) {
            Text(
                setting.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                setting.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSpacing.unit))
            Text(
                value.toString() + (setting.unit?.let { " $it" } ?: ""),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Slider(
                value = value.toFloat(),
                onValueChange = { raw -> update(raw.toInt()) },
                valueRange = range,
                steps = sliderSteps,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { update(value - step) }) { Text("−") }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { text ->
                        typed = text
                        text.trim().toIntOrNull()?.let { parsed -> value = parsed.coerceIn(low, high) }
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("精确值") },
                    textStyle = PiTheme.text.mono,
                )
                TextButton(onClick = { update(value + step) }) { Text("+") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "取值范围 $low 到 $high",
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSpacing.unit))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        onSet(null)
                        onDismiss()
                    },
                ) { Text("恢复默认") }
                TextButton(
                    onClick = {
                        onSet(value)
                        onDismiss()
                    },
                ) { Text("保存") }
            }
            Spacer(Modifier.height(PiSpacing.unit))
        }
    }
}

/**
 * Text editor (spec §6.2 TextRow). Long values such as `externalEditor` need more
 * than one line, so anything deeper than level 1 gets a multi-line field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiTextEditorSheet(
    setting: PiSetting,
    initial: String,
    onSet: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.screen),
        ) {
            Text(
                setting.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                setting.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSpacing.unit))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = setting.depth <= 1,
                readOnly = setting.readOnly,
                enabled = !setting.readOnly,
                label = { Text(setting.key) },
                textStyle = PiTheme.text.mono,
            )
            if (setting.readOnly) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "这一项只读。",
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(PiSpacing.unit))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        onSet("")
                        onDismiss()
                    },
                    enabled = !setting.readOnly,
                ) { Text("清除") }
                TextButton(
                    onClick = {
                        onSet(text)
                        onDismiss()
                    },
                    enabled = !setting.readOnly,
                ) { Text("保存") }
            }
            Spacer(Modifier.height(PiSpacing.unit))
        }
    }
}

/**
 * List editor (spec §6.2 ListRow, "arrays keep their glob syntax").
 *
 * Array settings are edited as raw lines so `!pattern`, `+path` and `-path` are
 * preserved exactly; object settings (the per-model maps) are edited as
 * `key = value` lines, because a phone cannot render a nested JSON tree
 * usefully. Preset chips toggle membership for settings with a known universe,
 * such as the eight built-in tools.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiListEditorSheet(
    setting: PiSetting,
    initialEntries: List<String>,
    onSet: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var entries by remember(initialEntries) { mutableStateOf(initialEntries) }
    var draft by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.screen),
        ) {
            Text(
                setting.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                setting.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            PiInfoNote(
                if (setting.container == PiValueContainer.Object) {
                    "每行一项，写成「键 = 值」，键按精确匹配不认通配符。值以 { 或 [ 开头时按 JSON 解析，" +
                        "例如逐模型压缩覆盖写成 model-id = {\"reserveTokens\": 400000}，其余按字符串/数字/布尔解析。"
                } else {
                    "每行一项，支持 glob 与排除标记：!pattern 排除、+path 强制包含、-path 强制排除。" +
                        "值以 { 或 [ 开头时按 JSON 解析，例如 packages 的对象形式 " +
                        "{\"source\": \"pi-skills\", \"autoload\": false}。"
                },
            )
            Spacer(Modifier.height(8.dp))
            if (setting.presets.isNotEmpty()) {
                Text(
                    "快捷添加",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    setting.presets.forEach { preset ->
                        val selected = entries.contains(preset)
                        Surface(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clickable {
                                    entries = if (selected) {
                                        entries.filter { it != preset }
                                    } else {
                                        entries + preset
                                    }
                                },
                            shape = PiShapes.chip,
                            color = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        ) {
                            Text(
                                preset,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = PiTheme.text.monoSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(PiSpacing.unit))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                entries.forEachIndexed { index, entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = entry,
                            onValueChange = { changed ->
                                entries = entries.toMutableList().also { list -> list[index] = changed }
                            },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = PiTheme.text.mono,
                        )
                        IconButton(
                            onClick = {
                                entries = entries.filterIndexed { position, _ -> position != index }
                            },
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除这一项",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("新增一项") },
                    textStyle = PiTheme.text.mono,
                )
                IconButton(
                    onClick = {
                        val trimmed = draft.trim()
                        if (trimmed.isNotEmpty()) {
                            entries = entries + trimmed
                            draft = ""
                        }
                    },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "添加")
                }
            }
            Spacer(Modifier.height(PiSpacing.unit))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { entries = emptyList() }) { Text("清空") }
                TextButton(
                    onClick = {
                        onSet(entries)
                        onDismiss()
                    },
                ) { Text("保存") }
            }
            Spacer(Modifier.height(PiSpacing.unit))
        }
    }
}

/**
 * Theme picker.
 *
 * `theme` may hold a single name, or the literal `"lightTheme/darkTheme"` pair
 * that turns on automatic light/dark switching. That pair is ONE string in the
 * file, so this editor writes it back as one string: the two name fields only
 * compose the literal, they are never stored separately, and the raw field
 * shows exactly what will be written.
 *
 * [knownThemes] is every theme the app can actually load, not just the ones the
 * `themes` setting names: pi discovers the agent dir's `themes` directory and the
 * project's `.pi/themes` on its own (`resource-loader.ts:872-880`), so limiting
 * the list to the setting would hide the very theme the terminal is using.
 * [notes] are the caveats of the theme currently in effect — a value the app
 * could not honour is stated here instead of being ignored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiThemeEditorSheet(
    currentRaw: String,
    knownThemes: List<PiThemeEntry>,
    notes: List<String> = emptyList(),
    error: String? = null,
    onSet: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val auto = splitAutoTheme(currentRaw)
    var raw by remember(currentRaw) { mutableStateOf(currentRaw) }
    var lightTheme by remember(currentRaw) { mutableStateOf(auto?.first ?: "light") }
    var darkTheme by remember(currentRaw) { mutableStateOf(auto?.second ?: "dark") }
    val entries = buildList {
        add(PiThemeEntry("dark", null, PiThemeScope.Builtin))
        add(PiThemeEntry("light", null, PiThemeScope.Builtin))
        knownThemes.forEach { entry -> if (none { it.name == entry.name }) add(entry) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.screen),
        ) {
            Text(
                "主题",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "自动模式需要分别填写浅色与深色主题名。" +
                    "下面的自定义主题来自 pi 的主题目录与 themes 设置。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                PiInfoNote(error)
            }
            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                PiInfoNote("当前主题有部分颜色无法照搬：\n" + notes.joinToString("\n") { "· $it" })
            }
            Spacer(Modifier.height(PiSpacing.unit))
            Text(
                "单主题",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                entries.forEach { entry ->
                    val selected = currentRaw == entry.name
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSet(entry.name)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Text(
                                entry.path?.let { "${entry.scope.label} · ${it.substringAfterLast('/')}" }
                                    ?: entry.scope.label,
                                style = PiTheme.text.meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "已选中",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(PiSpacing.unit))
            Text(
                "自动模式",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = lightTheme,
                    onValueChange = { lightTheme = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("浅色主题名") },
                    textStyle = PiTheme.text.mono,
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = darkTheme,
                    onValueChange = { darkTheme = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("深色主题名") },
                    textStyle = PiTheme.text.mono,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "保存为 " + lightTheme.trim() + "/" + darkTheme.trim(),
                style = PiTheme.text.monoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSpacing.unit))
            Text(
                "原始值",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = raw,
                onValueChange = { raw = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("theme") },
                textStyle = PiTheme.text.mono,
            )
            Spacer(Modifier.height(PiSpacing.unit))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        val pair = lightTheme.trim() + "/" + darkTheme.trim()
                        if (!pair.startsWith("/") && !pair.endsWith("/")) {
                            onSet(pair)
                            onDismiss()
                        }
                    },
                ) { Text("应用自动模式") }
                TextButton(
                    onClick = {
                        if (raw.isNotBlank()) {
                            onSet(raw.trim())
                            onDismiss()
                        }
                    },
                ) { Text("使用原始值") }
            }
            Spacer(Modifier.height(PiSpacing.unit))
        }
    }
}
