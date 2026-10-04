package app.pi.ui.chat

/**
 * 会话列表那一行「**来自〈父会话名〉**」的判定 —— 纯字符串算术，一个 import 都没有。
 *
 * ## 为什么单独一个文件
 *
 * 它要能被 `tools/run-app-pure-checks.sh` 编译（本机没有 Android、没有 Compose），所以它
 * **不许** import `android.*` / `androidx.*` / `app.pi.ui.*` 里的任何 Compose 东西。这就是它
 * 不写在 `SessionsScreen.kt` 里的原因 —— 那一屏是 Compose，本机编译不了，判定也就无从被跑
 * 到。它也**不依赖 `PiSessionStore.Summary`**：那个类型住在 `session` 目录（本机 harness 同样
 * 不编译它），而这条判定要的只有两个字段，投影成 [SessionFileRow] 就够了 —— 代价是它
 * **看不见会话 `id`**，好处也正是这一点：`list()` 里同一个 `id` 两行（劈开的两半，
 * [PiSessionStore.mergeSameIdRow] 的 `PrefixRelation.Disjoint`）对它没有任何影响（见下）。
 *
 * ## 这条关系是 pi 自己记的，不是本应用推出来的
 *
 * 有父会话的会话，它的**文件头**里多一个字段 `parentSession`，值是原会话文件的路径：
 *
 * ```json
 * {"type":"session","version":3,"id":"uuid","timestamp":"…","cwd":"…",
 *  "parentSession":"/path/to/original/session.jsonl"}
 * ```
 *
 * （`docs/session-format.md:72-76`；写它的代码是 `SessionManager.createBranchedSession`
 * `core/session-manager.ts:1681` 与 `SessionManager.forkFrom` `:1854` 与 `NewSessionOptions` 那条路
 * `:1069`；头字段声明在 `:49`，读它的地方是 `:857`。）
 * pi 说这个字段是**三种**来源共用的：`/fork`、`/clone`、`newSession({ parentSession })` ——
 * 所以这里只说「来自谁」，**不说**「是被分叉还是被复制的」：文件里没有区分它们的东西，编一个
 * 出来就是自己发明语义。
 *
 * pi 自己**怎么显示**这条关系：它的会话选择器按这个字段把会话拼成一棵树，用 `├─`/`└─` 缩进
 * 挂在父会话下面（`modes/interactive/components/session-selector.ts:206-231` 建树、`:262-276`
 * 摊平、`:531-537` 画前缀），而**只在** Threaded 排序且搜索框为空时这么做（`:380-386`）——
 * 换成 Recent/Fuzzy 或一搜索，就是一张平表，父子关系一点都不显示。本应用的会话列表是平表
 * （按 cwd 分组、组内按活动时间），所以用一行小字说同一件事实。
 *
 * ## 为什么按文件名找
 *
 * `parentSession` 是 **guest 路径**（pi 在客机里解析它），而列表里每一行的 `File` 是主机路径，
 * 两边的目录前缀不同（`PiSessionViewModel` 的 `guestSessionPath` / `hostSessionFile` 做前缀
 * 替换）。文件名是两边逐字相同的那一段 —— 会话文件名由 pi 自己生成
 * `<文件时间戳>_<会话 id>.jsonl`（`session-manager.ts:1670-1673`），所以只比末段：不去猜路径、
 * 不去反解目录。
 *
 * ### 一个 `id` 两行，与这条判定无关（2026-10-04 追加）
 *
 * `PiSessionStore.list()` 现在按 pi 的做法**一个 `.jsonl` 一行**，同一个 `id` 可以留下两行 ——
 * 当两份文件**互不为前缀**时（[PiSessionStore.mergeSameIdRow] 的 `PrefixRelation.Disjoint`），
 * 也就是「一段对话被劈成两个文件」那个形态；互为前缀的旧快照仍并成一行。
 *
 * **这个歧义到不了这里，因为这条判定的键是文件名，不是 `id`。** 两层理由：
 *
 *  1. [SessionFileRow] 里根本没有 `id` —— 它只有 `(文件名, 显示名)`。这条判定**看不见** `id`，
 *     所以 `id` 上的相撞在它这里不存在；把 `id` 加进来只会在一条正在改的规则上再加一个依赖。
 *  2. 被劈开的两半是**两个不同的文件**（pi 的名字是 `<文件时间戳>_<id>.jsonl`，
 *     `session-manager.ts:1670-1673`，同一个 id 的两个文件必然时间戳不同 ⇒ 文件名不同），
 *     所以一个 `parentSession`（它记的是一条**路径**）最多命中其中**一个**文件 —— 正是写下它的
 *     那一半。这也是这条判定比「按 `id` 找」更强的地方：按 `id` 找才会在两半之间二选一。
 *
 * 唯一的同名情形是**同一个文件名出现在两个目录布局里**（pi 默认的分组目录 + 另一份拷贝），
 * 那时按 [rows] 的顺序取**第一个** —— 确定、无随机，且 `PiSessionStore.list()` 已经先按会话
 * 身份做过合并。见 `SessionForkLabelCheck` 的 5.1 与 6.x。
 *
 * 返回 null 的两种情形都是**正常的**，调用方各有各的说法，但**都不许**拿文件名当会话名：
 *
 *  - 这一行没有父（`parentSession` 缺席或空）；
 *  - 有父，但父不在 [rows] 里 —— 父被删了、在磁盘上改了名、或者落在
 *    `PiSessionStore.list(limit)` 之外。这时只能说「这是一条有父的会话」。
 */
data class SessionFileRow(val fileName: String, val displayName: String)

/**
 * [parentSession] 指向的那个会话的显示名，取不到时 null（见文件头的两条情形）。
 *
 * @param parentSession pi 写在子会话文件头里的 `parentSession`，原样传进来。
 * @param rows 会话列表里每一行的「文件名 → 显示名」。
 */
fun forkParentNameOf(parentSession: String?, rows: List<SessionFileRow>): String? {
    val recorded = parentSession?.takeIf { it.isNotBlank() } ?: return null
    val fileName = recorded.substringAfterLast('/')
    if (fileName.isEmpty()) return null
    for (row in rows) {
        if (row.fileName == fileName) return row.displayName
    }
    return null
}
