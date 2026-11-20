# 会话管理

会话**列表**由宿主用官方 `SessionManager` 读；App 不再自己扫盘、不再维护索引。会话**内容**（转录、树）仍走各自的宿主命令。

## 1. 会话列表（`/resume` 选择器）

对照官方 `dist/modes/interactive/components/session-selector.js` 与 `session-selector-search.js`。

| 官方行为 | 在哪 | 说明 |
|---|---|---|
| `SessionManager.list(cwd, dir)` / `listAll(dir)`，并发读文件，`onProgress(loaded, total, partial)` | 宿主 `commands/sessionlist.mjs` | 全量读文件是冷加载慢的固有原因，只能靠「读一次、常驻」来改善体感 |
| threaded / recent / relevance 三种排序（Ctrl+S）、仅命名（Ctrl+N）、搜索语法（`re:` / `"短语"` / 模糊） | `filterAndSortSessions`、`parseSearchQuery`、`hasSessionName` | **直接按文件 URL import 官方文件**（它们不在包的公开出口里，先例：`catalog.mjs` 读 `slash-commands.js`），不重写 |
| `buildSessionTree` / `flattenSessionTree`（模块私有） | `sessionlist.mjs` 移植 | 用官方 `SessionSelectorComponent` 真渲染出的行序和前缀做标准答案：`tools/collect-session-list-fixtures.mjs` → `app/src/test/resources/pi-session-list-fixtures/list.json`，`tools/sdk-host/test.mjs`「会话列表对照官方组件」逐行比对，CI 里 `--check` |
| 行文字 = 会话名 else 首条消息，控制字符换成空格；右侧 `cwd 条数 时间` | `app/pi/session/list/SessionListModel.kt` | 同一份 list.json 比对（`session-list-model` harness） |
| 重命名（Ctrl+R）、删除（Ctrl+D，当前会话不可删，先试 `trash` 再 unlink）、改动后重读 | `session.rename` / `session.delete {path, method}` | 长按面板只有这两件事，外加点一行恢复——官方就这三个 |
| 取消在途加载（`cancelLoads`） | `session.list.cancel` | |

### 命令与事件

- `session.list {scope, sortMode, nameFilter, query, refresh, groupByCwd}` → `{scope, rows, total, queryError}`。没有缓存或 `refresh:true` 才读盘，读的过程中发 `sessions.progress {scope, loaded, total, rows?}`；其余只用宿主缓存重算视图（换排序 / 过滤 / 搜索是毫秒级）。缓存按「会话目录 + cwd」作废，改名 / 删除 / `session.open` 换目录都会作废。
- `session.list.cancel` → `{cancelled}`。
- `session.delete {path}` → `{path, method: "trash"|"unlink"}`；当前会话 → `busy`。
- `session.read` 仍在：只读读取别的会话（摘要 / 分支 / 分页条目），转录与会话树用它。

### 与官方有意不同的地方（App 层，不是 pi 的概念）

1. **读一次、常驻，打开不重读。** 官方每次打开选择器都重读磁盘；手机上读一遍要几秒，所以 `PiSessionViewModel` 在**引擎就绪时后台读一次**，之后列表屏直接用手上的行（秒开）。会话文件 / 工作区 / 消息数（到 2 为止）变了，VM 在后台**静默**重读，不依赖列表屏开着。手上有行时任何读取都不出转圈或进度条，读完无声替换；只有「一行都没有、正在读」才显示进度。上次的列表落盘（`filesDir/pi/session-list-cache.json`），冷启动先画它。
2. **按工作区分组。** 官方「全部」范围每行右侧写 cwd；手机上改成每个 cwd 一张可折叠的卡，当前工作区置顶、其余按最近活动降序。列表因此恒为「全部」范围，没有 current / all 开关。`groupByCwd:true` 让宿主在 threaded 视图下**每个目录各自成树**（父会话在别的目录时，子会话在本组里是根，跨组的连线画不出来）；组内规则仍是官方的 build / flatten。
3. 树里的层级画成真线条（depth / isLast / ancestorContinues 取自官方 3 字符前缀的手机版）。

### 缺失工作目录的恢复

会话头记录的 cwd 在这台设备上不存在时，官方 `/resume` 会提示后以 `cwdOverride` 重试（`session-cwd.js` 的 `MissingSessionCwdError`）。宿主 `session.switch` 回 `cwd_missing`（带原因文本），App 弹确认框：「继续打开」（以当前工作区重试）、「分支到此」（`session.forkFrom`）、取消。

## 2. 会话内容（未变）

| 需求 | 宿主命令 |
|---|---|
| 转录（分页 / `since` 增量） | `session.entries` / `session.messages` |
| 会话树、跳转、摘要 | `session.tree` / `session.navigate`（官方 `/tree`） |
| 书签、当前分支、上下文编辑 | `session.label` / `session.path` / `session.contextEdit` |
| 新建 / 打开 / 切换 / 分叉 / 复制 | `session.new` / `session.open` / `session.switch` / `session.fork` / `session.clone` |
| 导入 / 导出 | `session.import` / `session.export` / `session.exportJsonl` |

`SessionFileReader`（转录还原）和 `SessionResume`（引擎重启时认回对话）仍在 Kotlin 侧，由转录加载与重启恢复使用；列表这条线上的旧扫盘层（`PiSessionStore` / `PiSessionIndex` / `SessionFileScan`）已删除。

## 3. 没验证 / 已知局限

- 本机没有真机和真实的大会话库：**加载速度没有实测**；改进来自「后台预读 + 常驻 + 静默刷新」，不是读得更快。
- 所有 Compose 界面（`SessionsScreen.kt` 等）本机未编译，需 CI / 真机看。
- 搜索用 SDK 的 `allMessagesText`（用户 + 助手文本），**不含工具结果**，与桌面 pi 一致。
- `session.delete` 优先移到回收站（`trash` 命令可用时），否则直接 unlink；UI 总是先确认。

## 列表跟手性（2026-11）

- 宿主加载是**共享**的：`refresh:true` 才重起一次读盘；没有缓存时的视图请求直接等在途那次；有缓存就立刻答（重读期间也是，读完才换缓存，不先清）。（App 层优化，非 SDK 概念；SDK 的 `listAll` 只在第 1 个与每 100 个时给部分结果。）
- 新会话 / 切会话：App 先本地把当前行标出来、没有就垫一行置顶（`applyActiveRow`），再对账重读一次；工作区换了才直接重读。
- 磁盘缓存只存默认视图（threaded / 全部），避免切过排序后重启对不上键而白屏；引擎没起来时列表屏不发请求。
