package app.pi.ui.settings

import androidx.compose.runtime.Composable
import app.pi.ui.theme.PiSystemPalettes
import app.pi.ui.theme.PiThemeEntry
import kotlinx.serialization.json.JsonPrimitive

/**
 * Picks the level-2 editor for a row (spec §6.1).
 *
 * Switch rows are edited in place and action rows are confirmed by the caller, so
 * only the four value-bearing kinds reach this point. Every write goes through
 * [PiSettingsStore.write] with the value the user actually chose — the store, not
 * this layer, is responsible for landing it in `settings.json`.
 *
 * [knownThemes] is passed in rather than derived here: the names pi can actually
 * load come from the file system (the agent dir's `themes` directory and the
 * project's `.pi/themes`), not from the `themes` setting alone, so the caller —
 * which owns the resolved theme — is the one authority for the list.
 */
@Composable
fun PiSettingEditorSheet(
    setting: PiSetting,
    store: PiSettingsStore,
    onDismiss: () -> Unit,
    knownThemes: List<PiThemeEntry> = emptyList(),
    themeNotes: List<String> = emptyList(),
    themeError: String? = null,
    /**
     * Values that do not live in the store, by key: the read-only 运行时 rows, which the
     * host reads from the runtime tree and the foreground service (see `RuntimeFacts`).
     *
     * The row already renders these instead of the store value, so an editor that read the
     * store would show a **different** value from the row the user tapped — the empty sheet
     * over a row reading the engine version of the day (`0.85.1`) that
     * `docs/settings-audit-impl.md` §B4 records. Text rows
     * therefore start from the override, exactly like the row does.
     */
    valueOverrides: Map<String, String> = emptyMap(),
    /**
     * 「选项来自清单」的选择器需要的四件东西，只有「模型与供应商」一屏的默认模型 / 默认厂商
     * 两行会用（默认全关，其余枚举选择器一个像素都不变）。
     *
     * 选项列表与 `presets` **不**在这里再开一个入口：调用方用
     * `setting.copy(options = …)` / `setting.copy(presets = …)` 传进来，于是"这一行为什么
     * 列这些项"只有一处（清单），编辑器不必知道清单的存在。理由：`defaultModel` 注册在
     * `PiSettingsRegistry.kt:403-414` 时 `options` 是空的，选择器里只剩一个「自定义值」输入框，
     * 用户只能手打 —— 而手打一个不存在的模型，pi 不报错、静默退回占位模型
     * （`docs/engine-exit-review.md:55`）。
     */
    pickerSearchHint: String? = null,
    /** 选择器正文里的一行警告（写前提示，例如厂商与模型对不上）；null = 不显示。 */
    pickerWarning: String? = null,
    /** 手输值的写前校验：返回要显示的那句话（此时不落盘）；null = 这个值可以直接用。 */
    pickerCustomCheck: ((String) -> String?)? = null,
    /** 底部「清除 / 跟随默认」动作的文案；null = 没有这个动作。 */
    pickerClearLabel: String? = null,
    onWritten: (PiSetting) -> Unit = {},
) {
    when (setting.kind) {
        PiRowKind.Switch, PiRowKind.Action -> Unit

        PiRowKind.Value -> {
            // 「配色方案」有自己的一层：它是唯一需要**画色块**的选择行，而通用选择器只
            // 有 `✓/○ + 名字 + 说明`。这一支不加第二个机制 —— 写入仍然在这里
            // （`store.write` + `onWritten`），只是把排版交给 [PiSystemPaletteSheet]。
            if (setting.key == PiSystemPalettes.SETTING_KEY) {
                // 三个键（主题 id、底色、饱和度）都在这一页里，但**只在按「应用」时**写，
                // 由这里一次写完再 `onWritten` → `refreshTheme`；拖动滑杆不会碰设置。
                PiSystemPaletteSheet(
                    initialBackground = (store.read(PiSystemPalettes.BACKGROUND_KEY) as? JsonPrimitive)
                        ?.content.orEmpty(),
                    initialSaturation = (store.read(PiSystemPalettes.SATURATION_KEY) as? JsonPrimitive)
                        ?.content ?: PiSystemPalettes.saturationWire(1.0),
                    onApply = { hex, saturationWire ->
                        // 只有按「应用」才写盘并切主题：拖滑杆只改这一页的预览，
                        // 不会顺手把用户原来的 dark/light 选择改掉。
                        store.write(PiSystemPalettes.SETTING_KEY, JsonPrimitive(PiSystemPalettes.CUSTOM_ID))
                        store.write(PiSystemPalettes.BACKGROUND_KEY, JsonPrimitive("#$hex"))
                        store.write(PiSystemPalettes.SATURATION_KEY, JsonPrimitive(saturationWire))
                        store.write("theme", JsonPrimitive("system"))
                        onWritten(setting)
                    },
                    onResetToOfficial = {
                        // pi 的自动对写法（`light/dark`）：跟随手机深浅色，回到官方两套。
                        store.write("theme", JsonPrimitive("light/dark"))
                        onWritten(setting)
                    },
                    onDismiss = onDismiss,
                )
            } else if (setting.key == "theme") {
                PiThemeEditorSheet(
                    currentRaw = setting.textIn(store, "dark"),
                    knownThemes = knownThemes,
                    notes = themeNotes,
                    error = themeError,
                    onSet = { value ->
                        store.write(setting.key, JsonPrimitive(value))
                        onWritten(setting)
                    },
                    onDismiss = onDismiss,
                )
            } else {
                PiOptionPickerSheet(
                    title = setting.title,
                    description = setting.description,
                    options = setting.options,
                    currentWire = setting.textIn(store, ""),
                    allowCustom = setting.allowCustom,
                    onPick = { value ->
                        store.write(setting.key, setting.wireElement(value))
                        onWritten(setting)
                    },
                    onDismiss = onDismiss,
                    searchHint = pickerSearchHint,
                    warning = pickerWarning,
                    customCheck = pickerCustomCheck,
                    clearLabel = pickerClearLabel,
                    // 「清除」= `remove`（pi 的未设置），不是写空串：这一行在磁盘上要么是
                    // 一个字符串，要么不存在，`""` 会被 pi 当成一个模型 ID 去找。
                    onClear = if (pickerClearLabel != null) {
                        {
                            store.remove(setting.key)
                            onWritten(setting)
                        }
                    } else {
                        null
                    },
                )
            }
        }

        PiRowKind.Number -> PiNumberEditorSheet(
            setting = setting,
            initial = store.read(setting.key)?.intValueOrNull(),
            // "Restore the default" **removes the key**; it must never write `JsonNull`.
            // A null in the file is not "unset" to pi — `parseTimeoutSetting` treats it as
            // present-and-unparseable and throws, which stops `pi --mode rpc` from starting
            // when the key is `httpIdleTimeoutMs` (`docs/settings-audit-impl.md` §B1).
            onSet = { value ->
                if (value == null) store.remove(setting.key) else store.write(setting.key, JsonPrimitive(value))
                onWritten(setting)
            },
            onDismiss = onDismiss,
        )

        PiRowKind.Text -> PiTextEditorSheet(
            setting = setting,
            // The row shows this value, so the editor must show the same one.
            initial = valueOverrides[setting.key] ?: setting.textIn(store, ""),
            // "Clear" is also a removal: an empty string is a *value* in the file (`""` is not
            // `undefined`), and every text row here means "unset" when it is empty — for
            // `shellPath` pi's `if (customShellPath)` makes `""` behave as unset, and for the
            // app's own launch knobs `takeIf { it.isNotBlank() }` does the same. Removing it
            // also keeps `isExplicit()` from reporting a value that is not really set.
            onSet = { value ->
                if (value.isEmpty()) store.remove(setting.key) else store.write(setting.key, JsonPrimitive(value))
                onWritten(setting)
            },
            onDismiss = onDismiss,
        )

        PiRowKind.List -> PiListEditorSheet(
            setting = setting,
            initialEntries = setting.editableEntries(store.read(setting.key) ?: setting.defaultValue),
            // pi's own rules for the object-valued rows, applied **before** the write:
            // `compaction.modelOverrides` with a non-integer value makes pi throw on every
            // turn's compaction check (`core/settings-manager.ts:854-880` →
            // `core/agent-session.ts:540`), so saving it silently is the bug
            // (`docs/settings-audit-impl.md` §B5). `validateEntries` returns the sentence to
            // show instead of saving, or null when the lines are acceptable.
            validate = { entries -> setting.validateEntries(entries) },
            // An empty list **removes the key** instead of writing `[]` / `{}`.
            //
            // Writing the empty container is a *value* in pi, and for one row it means the
            // opposite of what the row's label promises (`docs/settings-audit-impl.md` §11):
            //
            //  - `defaultTools`: pi does `configuredDefaultToolNames ?? defaultActiveToolNames`
            //    (`core/sdk.ts:256-262`), so `[]` means **no built-in tool at all**, while the
            //    row is labelled 「默认 read/bash/edit/write」. Clearing it used to disable every
            //    tool while the screen said "default" — the row was lying.
            //  - everything else here treats empty as unset already, so removing is
            //    behaviour-identical and leaves pi's document sparse: `enabledModels`
            //    (`main.ts:788`: `length > 0` else cycle all models), `npmCommand`
            //    (`core/package-manager.ts:1747-1753`: empty → `npm`), the three object rows
            //    (`?.` / `?? {}` lookups), and the app's own `app.terminal.keyBar`
            //    (`TerminalSettings.keyBarOf`: absent *and* empty both mean the default bar).
            //
            // A user who genuinely wants pi's "no built-in tools" reads it from `settings.json`
            // itself in the 「Pi 文件」 screen; the row's description says so.
            onSet = { entries ->
                if (entries.isEmpty()) {
                    store.remove(setting.key)
                } else {
                    store.write(setting.key, setting.elementFromEntries(entries))
                }
                onWritten(setting)
            },
            onDismiss = onDismiss,
        )
    }
}
