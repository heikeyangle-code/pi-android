# 会话管理：整块按官方 SDK 重做

状态：宿主侧已做完并有测试（110 条）；Kotlin/UI 侧**还没动**，本文是它的施工图。
没编译 Gradle、没上机。

## 1. 现状审计：这一整块是为 RPC 的缺口写的

RPC 没有「列会话」「读别的会话」「树导航」，所以 App 自己补了一层（约 2800 行 Kotlin，`app/pi/session/*`）：

| 文件 | 行数 | 为什么存在 | 换成什么 |
|---|---|---|---|
| `PiSessionStore.kt` | 1048 | 扫目录、解析 JSONL 出列表行；合并「同 id 两份文件」 | `session.list` / `session.search`（`SessionManager.list/listAll`，宿主已接） |
| `SessionFileReader.kt` | 838 | 自己解析会话文件还原转录、树 | `session.entries`（分页/`since`）、`session.tree`、`session.read`（只读预览别的会话） |
| `SessionFileScan.kt` / `PiSessionIndex.kt` | 154 / 302 | 摘要索引落盘缓存 | 宿主 SDK 自己扫；手机上慢的话在宿主侧加缓存，不在 Kotlin 里 |
| `SessionResume.kt` | 246 | `--session-id` 找不到时**用同一个 id 新建文件**，一段对话劈成两份（见该文件 KDoc） | `session.open {session:{file}}` 直接按**路径**打开；这个 bug 类整个消失 |
| `ui/chat/SessionTreeScreen.kt` | 1179 | 树页，靠 `pi-android-bridge` 扩展绕路导航 | `session.tree` + `session.navigate`（官方 `/tree`） |
| `ui/screens/SessionsScreen.kt` | 1351 | 会话列表页 | 新「会话库」，见 §3 |

结论：**这块要推翻，不是迁移。** 迁移会把「同 id 合并」「前缀判定」这些只为 RPC 缺陷存在的复杂度原样带过去。

## 2. SDK 覆盖对照（逐项）

| SDK 能力（`SessionManager` / `AgentSession`） | 宿主命令 | 状态 |
|---|---|---|
| `list` / `listAll` | `session.list`（默认不带 `allMessagesText`，`text:true` 才带） | 已接 |
| 全文搜索（`SessionInfo.allMessagesText`） | `session.search` | **本轮新增** |
| `open(path)` 只读预览别的会话 | `session.read`（`summary`/`branch`/`entries` 分页） | **本轮新增** |
| `appendSessionInfo`（给任意会话改名） | `session.rename`（当前会话走 `setSessionName`） | **本轮新增** |
| 删会话 | `session.delete`（只许会话目录内、不许删当前） | **本轮新增**（SDK 无此 API，宿主只做 unlink） |
| `appendLabelChange` / `getLabel`（书签） | `session.label`；树节点自带 `label` | **本轮新增** |
| `getBranch`（叶到根） | `session.path` | **本轮新增** |
| `appendContextEdit`（隐藏/替换更早条目对模型的可见内容） | `session.contextEdit` | **本轮新增** |
| `getTree` / `getEntries` / `navigateTree` | `session.tree/entries/navigate` | 已接 |
| `newSession/switchSession/fork/clone` | `session.new/switch/fork/clone` | 已接 |
| `importFromJsonl` / `exportToHtml` | `session.import/export` | 已接 |
| `compact`、自动压缩、重试 | `session.compact/autoCompaction/retry` | 已接 |
| `parentSessionPath`（分叉谱系） | 在 `session.list` 行里 | 已接（UI 未用） |
| `list` 的 `onProgress` / `signal` | — | **未接**：几百个会话时列表该流式出；需要「进度事件 + 取消」，下一步加 |
| `findById`、`forkFrom`（跨 cwd 分叉）、`continueRecent` | `session.open` 的 `session.id/continue` 已覆盖前两种；`forkFrom` 未接 | 部分 |
| `branchWithSummary` | 经 `session.navigate {summarize}` | 已接（间接） |
| `UsageEntry`（`appendUsage`） | 进 `session.entries`/`stats`；会话级成本汇总 UI 未做 | 数据有，UI 无 |

**没有伪装完成的部分**：`list` 进度流、`forkFrom`、成本汇总 UI。

## 3. UI 方案（按我的审美；只做 SDK 给得出的）

### 3.1 会话库（取代 `SessionsScreen`）
- **搜索优先**：顶部一个搜索框，输入即 `session.search`（防抖 250ms），结果行带命中片段高亮。空态才显示分组列表。
- 列表按「今天 / 昨天 / 本周 / 更早」分组；每行：名字（没有就取 `firstMessage` 首行）、条数、相对时间、
  分叉来源小标（`parentSessionPath` → 「分叉自 xxx」，点它跳到父会话）。
- **点一行 = 只读预览**（底部半屏，`session.read` 摘要 + 最近分支的前几条），预览里按钮：「打开」（`session.switch`）、
  「重命名」、「导出 HTML」、「删除」。**不为了看一眼就切会话**。
- 长按多选 → 批量删除（逐个 `session.delete`，当前会话灰掉并说明原因）。
- 当前会话置顶、带一个实心点。打开另一个会话**不重启引擎进程**（`session.switch`，epoch +1，旧尾巴被丢）。

### 3.2 会话树（取代 `SessionTreeScreen`）
- 数据只来自 `session.tree`；当前叶到根的路径用 `session.path` 高亮；同一父下的兄弟节点折叠成「3 个分支」可展开。
- 节点按类型有不同的轻量外观：用户消息（文字摘要）、助手（模型+是否含工具调用）、压缩、分支摘要、模型/思考变更、
  标签。**默认过滤成「只看用户消息 + 有标签的」**，因为长会话里工具结果会淹没结构；右上角切到「全部」。
- 点节点 → 底部操作条：「跳到这里」（`session.navigate`，弹出选项：是否摘要被离开的分支、自定义摘要指示语、给目标打标签）、
  「从这里分叉」（`session.fork`）、「加/改标签」（`session.label`）、「从模型上下文隐藏」（`session.contextEdit`，
  明确写「只影响模型看到的，不删记录」）。
- 标签就是书签：树顶有「书签」入口，列出所有带标签的节点，一点即跳。

### 3.3 转录加载与恢复
- 进会话：用 `state`（宿主持有）+ `session.entries` 分页向上翻（`since` 做增量），**Kotlin 不再解析会话文件**。
- 恢复：App 重启后 `session.open {session:{file: 上次路径}}`；没有「按 id 认」这一步，也就没有劈成两份的路径。

## 4. 删除清单与顺序（每步可独立合并）
1. 新会话库页 + 树页接宿主命令，旧页保留但不再入口（可回退）。
2. 转录加载改 `session.entries`；`SessionFileReader` 的调用点清零。
3. 重启恢复改按路径；`SessionResume` / `--session-id` 渲染清零。
4. 删 `PiSessionStore` / `SessionFileScan` / `PiSessionIndex` / `SessionFileReader` / `SessionResume` 与它们的 harness
   （`sessions`、`session-resume`、`session-identity`、`session-replay-cost`、`session-entry-detail` 各自确认被什么新测试覆盖后再删）。
5. 删 `pi-android-bridge` 里只为树导航存在的部分。

## 5. 自审
- **删旧 harness 前必须先看它们钉的是不是「真行为」**：`session-replay-cost`、`session-entry-detail` 钉的可能是展示逻辑而不是扫盘，
  不能一刀切。
- **手机上 `listAll` 的耗时没测过**；平铺目录里几百个文件时，宿主逐个读头部。要么加进度流（§2 未接项），要么宿主侧缓存摘要。
- **`session.delete` 是 unlink，没有回收站**。UI 必须二次确认；考虑先移到 `sessions/.trash` 保留 7 天（未做）。
- **`session.contextEdit` 的 UI 说明要非常清楚**，否则用户会以为是删除。
- **预览用 `SessionManager.open` 读整个文件**，超大会话会慢；`mode:"entries"` 分页只是少传，不是少读。
- 搜索用的是 SDK 的 `allMessagesText`（用户+助手文本）；**不含工具结果**，这与桌面 pi 一致，但要在 UI 上写明。
