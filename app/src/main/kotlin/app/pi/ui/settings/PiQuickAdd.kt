package app.pi.ui.settings

/**
 * 「快捷添加」在一份 **基线** 之上的加减法，纯函数（不依赖 Compose，可被 bare-JVM harness 编译）。
 *
 * ## 为什么必须加在基线上，而不是把列表当成完整值
 *
 * pi（0.86.1，`/root/pi-src-0861`）的 `defaultTools` 是**完整白名单**，不是「在默认之上追加」：
 *
 *  - `packages/coding-agent/src/core/settings-manager.ts:144` ——
 *    `defaultTools?: string[]; // Initial built-in tool selection`；
 *  - `packages/coding-agent/src/core/settings-manager.ts:1336-1339` —— `getDefaultTools()` 把这份
 *    数组原样返回，**不与**默认四件套合并；
 *  - `packages/coding-agent/src/core/sdk.ts:258-264` —— `defaultActiveToolNames = ["read", "bash", "edit", "write"]`，
 *    生效集合是 `options.tools ?? (options.noTools ? [] : (configuredDefaultToolNames ?? defaultActiveToolNames))`：
 *    设置里写了什么就**只**启用什么，一个默认项都不会自动补回来；
 *  - `packages/coding-agent/src/core/sdk.ts:66-74` 的字段文档（"When provided, only the listed tool
 *    names are enabled"）与 pi 自己的用例
 *    `packages/coding-agent/test/default-tools-setting.test.ts:58-67`（`defaultTools: ["grep", "find"]`
 *    → `getActiveToolNames()` 就是 `["grep", "find"]`）钉的是同一条语义。
 *
 * 所以快捷添加直接写 `["grep"]` 等于**关掉** read/bash/edit/write：用户实测到的「工具全灭」
 * 就是这个形状（`~/.pi/agent/settings.json` 里只剩 `["grep","find","ls"]`）。这里因此把快捷
 * 添加定义成「在基线（默认四件套）之上加」，并把编辑器读到的「只有几个可选项」也还原成同一
 * 份并集 —— 那是同一行写错的结果，不是用户手写的一份自定义白名单。
 *
 * 空值（键不存在）仍是 pi 的「用默认四件套」：`persist` 把「正好等于基线」的显式列表塌回
 * 空值，落盘仍然走 `store.remove`（`[]` 在 pi 里是**一个内建工具都不要**，与未设置相反）。
 */
object PiQuickAdd {
    /** pi 的默认内建工具集（`core/sdk.ts:258`）。 */
    val builtinToolDefaults: List<String> = listOf("read", "bash", "edit", "write")

    /**
     * 编辑器应当显示的列表：
     *
     *  - 未设置（空）→ 基线本身；
     *  - 只写了可选项（不含任何基线项，且每一项都是本行的 [presets]）→ 基线 ∪ 它：这正是
     *    写错的那份并集，还原出来而不是把用户锁在三个只读工具上；
     *  - 其余一律原样 —— 用户逐行搭起来的白名单（`read/bash`、`read/powershell/edit/write`…）
     *    不能被一个他没做过的动作改写。
     */
    fun effective(entries: List<String>, baseline: List<String>, presets: List<String>): List<String> = when {
        baseline.isEmpty() -> entries
        entries.isEmpty() -> baseline
        entries.none { it in baseline } && entries.all { it in presets } ->
            baseline + entries.filter { it !in baseline }.distinct()
        else -> entries
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
     */
    fun persist(entries: List<String>, baseline: List<String>): List<String> =
        if (baseline.isNotEmpty() && entries.toSet() == baseline.toSet()) emptyList() else entries
}
