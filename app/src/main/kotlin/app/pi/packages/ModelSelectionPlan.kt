package app.pi.packages

/**
 * 导入或编辑一个厂商时，`settings.json` 里那三个**选择**键要不要写、写什么。
 *
 * ## 为什么需要这个判定（用户报的「导入完了也有 bug」，B1）
 *
 * 旧的写入路径（`PiConfigFiles.kt` 原 `selectModel`）在每次保存时**无条件**写
 * `defaultProvider` + `defaultModel`，并把 `enabledModels` **整表替换**成只有本厂商条目
 * 的列表（`PiCredentialService.kt:363-370` 调用处）。后果是两条：
 *
 *  1. 给已有厂商**加一个模型**，默认模型被顺手切到这个厂商；
 *  2. 导入**第二个厂商**，第一个厂商的循环条目连同用户手写的 pattern（`claude-*` 等）
 *     一起被冲掉。
 *
 * pi 的语义：`enabledModels` 是 Ctrl+P **循环**的作用域，整体替换是 pi 自己的写法
 * （`core/settings-manager.ts:1325` 直接 `= patterns`），pattern 用 minimatch 同时匹配
 * `provider/modelId` 全名和裸 id（`core/model-resolver.ts:317`）；`defaultProvider`/
 * `defaultModel` 是启动选择（`settings-manager.ts:751`）。**整表替换本身是 1:1 的，
 * 错的是"什么时候该写"** —— 所以这里不发明合并语义去对抗 pi，只回答三个问题：
 * 用户动没动勾选、点没点设为默认、写出去的列表里除了本厂商还剩谁。
 *
 * ## 保留谁、替换谁（[mergeEnabledModels]）
 *
 *  - `providerId/…` 开头：本厂商的条目，**整组替换**成这次勾选的（没被勾的要能删掉，
 *    否则"取消勾选"是假的）；
 *  - 与本厂商某个已知模型 id **完全相等的裸条目**（用户可能手写过裸 id，pi 同样认
 *    裸 id）：同样视为本厂商的，替换掉，否则新旧两条会重复出现在循环里；
 *  - 其余一律**原样保留**：别的厂商的条目、用户手写的跨厂商 glob（`claude-*` 这类，
 *    以及 `github-copilot` 加全配的写法）。glob 无法可靠归因（`deepseek-` 打头的
 *    pattern 既可能是厂商前缀也可能是
 *    模型名前缀），保守方向是留下 —— 最坏情况是循环里多一条重复，而不是丢配置。
 *
 * ## 「没动就不写」
 *
 * 勾选集合与保存前已配置的集合相等 ⇒ `enabledModels` 不写（返回 null）。这不只是省一次
 * 写盘：它保护的是上面那条"glob 原样保留" —— 本厂商如果原本是一条厂商通配 pattern，用户只是
 * 改了 Base URL 再保存，一旦重写就会退化成一串写死的 id。`defaultModel` 同理，只有
 * [settings] 的 `setAsDefault` 为 true 且带了非空 id 才写。
 *
 * 纯逻辑、无 Android 依赖，注册在 `tools/run-app-pure-checks.sh` 的
 * `model-selection-plan` 里 —— Compose 编译不了的机器上，这两条判定只能在这里被证明。
 */
object ModelSelectionPlan {

    /**
     * 这次保存对 `settings.json` 的写入计划。
     *
     * @property writeDefault 是否写 `defaultProvider`/`defaultModel`。
     * @property defaultModelId 要写入的模型 id；[writeDefault] 为 false 时恒为 null。
     * @property enabledModels null = **不碰这个键**；非 null 是写出去的完整列表，
     *          空列表 = 删键（pi 把"没有这个键"当全部模型可循环，`main.ts:789` 只在
     *          `length > 0` 时解析作用域，写 `[]` 语义相同但文件里多一份空数组）。
     */
    data class Settings(
        val writeDefault: Boolean,
        val defaultModelId: String?,
        val enabledModels: List<String>?,
    )

    /**
     * @param existingPatterns 保存前 `settings.json` 里的 `enabledModels` 原样列表。
     * @param providerId 正在保存的厂商 id。
     * @param checkedIds 这次勾选的本厂商模型 id。
     * @param configuredIds 打开表单时的初始勾选集合（用户看到的基准），
     *        与 [checkedIds] 比较得出"动没动" —— **不是** `models.json` 的声明：
     *        官方厂商从不申报，拿声明对账会次次算"动了"。
     * @param setAsDefault 用户是否明确点了「设为默认」。
     * @param requestedDefaultModelId 点了设为默认时要写入的模型 id。
     */
    fun settings(
        existingPatterns: List<String>,
        providerId: String,
        checkedIds: List<String>,
        configuredIds: Set<String>,
        setAsDefault: Boolean,
        requestedDefaultModelId: String?,
    ): Settings {
        val writeDefault = setAsDefault && !requestedDefaultModelId.isNullOrBlank()
        val enabledModels = if (checkedIds.toSet() == configuredIds) {
            null
        } else {
            mergeEnabledModels(
                existing = existingPatterns,
                providerId = providerId,
                checkedIds = checkedIds,
                providerModelIds = configuredIds + checkedIds,
            )
        }
        return Settings(
            writeDefault = writeDefault,
            defaultModelId = requestedDefaultModelId?.takeIf { writeDefault },
            enabledModels = enabledModels,
        )
    }

    /**
     * 本厂商的条目整组替换、其余原样保留。规则与理由见文件头。
     */
    fun mergeEnabledModels(
        existing: List<String>,
        providerId: String,
        checkedIds: List<String>,
        providerModelIds: Set<String>,
    ): List<String> {
        val prefix = "$providerId/"
        val kept = existing.filterNot { it.startsWith(prefix) || it in providerModelIds }
        return kept + checkedIds.map { prefix + it }
    }
}
