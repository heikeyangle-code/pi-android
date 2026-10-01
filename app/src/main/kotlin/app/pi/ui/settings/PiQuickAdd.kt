package app.pi.ui.settings

/**
 * 「快捷添加」在一份 **基线** 之上的加减法，纯函数（不依赖 Compose，可被 bare-JVM harness 编译）。
 *
 * ## 为什么必须加在基线上，而不是把列表当成完整值
 *
 * pi 的 `defaultTools` 是**完整白名单**，不是「在默认之上追加」：
 *
 *  - `packages/coding-agent/src/core/settings-manager.ts:166` —— `defaultTools?: string[];` 的注释
 *    已改成「`+name`/`-name` entries add to or remove from the inherited selection」；
 *  - `packages/coding-agent/src/core/settings-manager.ts:234-245` `resolveDefaultTools(entries)` ——
 *    **普通名字替换**默认四件套（`:236`
 *    `const tools = plain.length > 0 || entries.length === 0 ? plain : [...DEFAULT_TOOL_NAMES];`
 *    ——「一个普通名字都没有」的列表才从 `DEFAULT_TOOL_NAMES` 出发）；
 *  - `packages/coding-agent/src/core/settings-manager.ts:213` ——
 *    `DEFAULT_TOOL_NAMES = ["read","bash","edit","write"]`；
 *  - `packages/coding-agent/src/core/settings-manager.ts:1429-1435` —— `getDefaultTools()` 返回的是
 *    `resolveDefaultTools(...)`，**不再是存储数组的原文**（0.87.1 的 `:1336-1339` 是原样返回，
 *    那个依据在 0.99.2 已经失效）；
 *  - `packages/coding-agent/src/core/sdk.ts:264-269` —— 生效集合是
 *    `options.tools ?? (options.noTools ? [] : (configuredDefaultToolNames ?? DEFAULT_TOOL_NAMES))`：
 *    设置里写了普通名字就**只**启用列出的那些，一个默认项都不会自动补回来；
 *    `packages/coding-agent/src/core/sdk.ts:65-74` 的字段文档（"When provided, only the listed tool
 *    names are enabled"）与 pi 自己的用例 `packages/coding-agent/test/default-tools-setting.test.ts:58-70`
 *    （`defaultTools: ["grep","find"]` → `getActiveToolNames()` 就是 `["grep","find"]`）钉的是同一条语义。
 *
 * 所以快捷添加直接写 `["grep"]` 等于**关掉** read/bash/edit/write：用户实测到的「工具全灭」
 * 就是这个形状（`~/.pi/agent/settings.json` 里只剩 `["grep","find","ls"]`）。这里因此把快捷
 * 添加定义成「在基线（默认四件套）之上加」，并把编辑器读到的「只有几个可选项」也还原成同一
 * 份并集 —— 那是同一行写错的结果，不是用户手写的一份自定义白名单。
 *
 * ## `+name` / `-name`：0.99.0 起 `defaultTools` 还能写增量
 *
 * pi 0.99.2 的 `settings-manager.ts:215-217` `isToolModifier()` 把 `+`/`-` 开头的条目当**修饰符**：
 * 它们不改写白名单，而是**加在/减在继承来的选择之上**，按列表顺序应用（`:237-243`）。于是
 * `["+codemode"]` 的含义是「默认四件套 + codemode」，`["-bash"]` 是「默认四件套 − bash」
 * （pi 自己的用例 `test/settings-manager.test.ts:650-661`）。
 *
 * App 选择**展开**（[resolveDefaultTools]）而不是原样保留：
 *
 *  - 这个文件历史上的全部问题都出在「列表是完整白名单」与「列表其实是增量」两种读法混用；
 *    展开之后只剩前者，pi 侧 `plain.length > 0` 必然成立（`:236`），语义单一；
 *  - 保留修饰符还会让 App 产出**混合形态**（`["+codemode","grep"]`），那是 pi 里最容易被误读的
 *    形状：`plain = ["grep"]` 替换掉默认四件套，read/bash/edit/write 被静默关掉；
 *  - 展开后 [persist] 的「正好等于基线就塌回空值」规则仍然成立 —— `["+codemode"]` 展开成基线
 *    加一项，不等于基线，不会被误判成「默认」。
 *
 * 空值（键不存在）仍是 pi 的「用默认四件套」：`persist` 把「正好等于基线」的显式列表塌回
 * 空值，落盘仍然走 `store.remove`（`[]` 在 pi 里是**一个内建工具都不要**，与未设置相反）。
 */
object PiQuickAdd {
    /** pi 的默认内建工具集（`core/settings-manager.ts:213` 的 `DEFAULT_TOOL_NAMES`）。 */
    val builtinToolDefaults: List<String> = listOf("read", "bash", "edit", "write")

    /**
     * pi 的 `isToolModifier()`（`core/settings-manager.ts:215-217`）：`+name`/`-name` 是相对
     * **继承来的选择** 的增量，不是一个工具名本身。
     */
    private fun isToolModifier(entry: String): Boolean = entry.startsWith("+") || entry.startsWith("-")

    /**
     * pi 的 `resolveDefaultTools()`（`core/settings-manager.ts:234-245`），逐行同构：普通名字替换
     * [baseline]，全是修饰符的列表从 [baseline] 出发，然后按列表顺序应用 `+`/`-`。
     *
     * 两处差异，都在**调用方**处理、不改这条规则：
     *
     *  - pi 的基准是写死的 `DEFAULT_TOOL_NAMES`（`:213`），这里参数化成 [baseline]：`defaultTools`
     *    行的基线就是默认四件套，`app.terminal.keyBar` 行的基线就是默认按键条，两者语义相同；
     *  - [entries] 为空在 App 里等于「键不存在」= 用默认值（见文件头），所以 [effective] 在调用
     *    本函数**之前**就把空列表直接判成 [baseline]；pi 的 `resolveDefaultTools([])` 按 `:236`
     *    返回 `[]`（显式空列表 = 一个内建工具都不要），这个区别由 [persist] 保留。
     */
    fun resolveDefaultTools(entries: List<String>, baseline: List<String>): List<String> {
        val plain = entries.filter { !isToolModifier(it) }
        val tools = if (plain.isNotEmpty()) plain.toMutableList() else baseline.toMutableList()
        for (entry in entries) {
            if (!isToolModifier(entry)) continue
            val name = entry.substring(1)
            val index = tools.indexOf(name)
            if (entry.startsWith("+") && index == -1 && name.isNotEmpty()) tools.add(name)
            else if (entry.startsWith("-") && index != -1) tools.removeAt(index)
        }
        return tools
    }

    /**
     * 编辑器应当显示的列表（修饰符已展开成普通名字）：
     *
     *  - 没有基线概念的行（[baseline] 为空）→ 原样，绝不碰 `+`/`-`：那是这一行的字面值（例如
     *    `patterns` 类行的 `!pattern`），只有 `defaultTools`/按键条这类行才有修饰符语义；
     *  - 未设置（空）→ 基线本身；
     *  - 只写了可选项（不含任何基线项、不含任何修饰符，且每一项都是本行的 [presets]）→ 基线 ∪ 它：
     *    这正是写错的那份并集，还原出来而不是把用户锁在三个只读工具上；
     *  - 其余一律用 [resolveDefaultTools] 的结果 —— 用户逐行搭起来的白名单（`read/bash`、
     *    `read/powershell/edit/write`…）不能被一个他没做过的动作改写。
     */
    fun effective(entries: List<String>, baseline: List<String>, presets: List<String>): List<String> = when {
        baseline.isEmpty() -> entries
        entries.isEmpty() -> baseline
        else -> {
            val resolved = resolveDefaultTools(entries, baseline)
            // 第三项 `entries.none { isToolModifier(it) }` 是必须的：这一支修的是**历史写坏的
            // 普通名单**（`["grep","find","ls"]`，一个基线项都没有，被 pi 当成完整白名单）。
            // 带修饰符的列表是 pi 0.99 的合法增量，必须原样按 [resolveDefaultTools] 显示，
            // 否则 `["grep","find","+ls"]`（pi 生效 `["grep","find","ls"]`）会被"修"成基线 ∪
            // 这三个 —— 把默认四件套凭空加回来，与 pi 相反的另一种错。
            if (entries.none { isToolModifier(it) } &&
                resolved.none { it in baseline } &&
                resolved.all { it in presets }
            ) {
                baseline + resolved.filter { it !in baseline }.distinct()
            } else {
                resolved
            }
        }
    }

    /** 点一枚 chip：在 [effective] 的结果上加；已经在里面就不动（不产生重复项）。 */
    fun add(entries: List<String>, preset: String, baseline: List<String>, presets: List<String>): List<String> {
        val base = effective(entries, baseline, presets)
        return if (preset in base) base else base + preset
    }

    /** 取消一枚 chip：删掉它；剩下的正好是基线时塌回「未设置」（见 [persist]）。 */
    fun remove(entries: List<String>, preset: String, baseline: List<String>, presets: List<String>): List<String> =
        persist(effective(entries, baseline, presets).filter { it != preset }, baseline)

    /**
     * 落盘前的值：一份**正好等于基线**的显式列表和「未设置」是同一个状态，不写出去。
     *
     * 写出去也不会让 pi 报错，但会让文件里多一份"看起来是自定义、其实是默认"的列表，
     * 下一次打开这一行就再也分不清它是默认还是用户自己写的。
     *
     * 这里也是修饰符**展开成普通名字**的地方（[effective] 之后用户仍可能手输 `+name`，或从
     * 旧版本设置里读进来一份修饰符）：pi 侧看到的是普通名字列表，`plain.length > 0` 成立，
     * 不会再被当成增量叠一次。
     *
     * 唯一的例外是「展开结果为空」：[persist] 的空列表在 App 里表示「删键 = 用默认」，表达不了
     * pi 的 `[]`（一个内建工具都不要）。这种值保留修饰符原文交给 pi 解析（它解析出的也是 `[]`），
     * 而不是塌成默认 —— 否则「全关」会被静默翻成「全开」。
     */
    fun persist(entries: List<String>, baseline: List<String>): List<String> {
        if (baseline.isEmpty()) return entries
        val resolved = resolveDefaultTools(entries, baseline)
        return when {
            resolved.toSet() == baseline.toSet() -> emptyList()
            resolved.isEmpty() -> entries
            else -> resolved
        }
    }
}
