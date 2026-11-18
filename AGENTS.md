# AGENTS.md — Pi Android 怎么干活

给在这个仓库里写代码的 AI 和人。读完这一页就该知道：这个项目是什么、第一原则是什么、一个需求来了怎么判断做不做、怎么证明做对了。

## 这个项目是什么

**Pi Android** = 官方 pi coding agent（`@earendil-works/pi-coding-agent`）的手机前端。

```
Kotlin / Compose（手机界面）
        │  我们自己定义的帧协议（docs/sdk-host/protocol.md）
        ▼
Node 宿主 sdk-host（app/src/main/assets/guest/sdk-host/）   ← 只做适配
        │  直接调用
        ▼
官方 pi SDK（AgentSession / SessionManager / SettingsManager / …）
        运行在 App 内的 proot / proroot guest 里（docs/sdk-host/runtimes.md）
```

- 旧做法是起 `pi --mode rpc` 说 RPC 协议。**已废弃**：RPC 模式给扩展的 UI 有 17 处桩，协议形状限制太多。现在 App 起自己的 Node 宿主，宿主用 SDK 建会话。设计全部在 `docs/sdk-host/`，从 `docs/sdk-host/README.md` 读起。
- Kotlin 侧：`app/src/main/kotlin/app/pi/`（`host/` 是宿主客户端与命令，`engine/` 是会话封装，`ui/` 是界面，`session/`、`settings/`、`runtime/`、`packages/` 是各块的纯逻辑）；`rpc/` 模块是 pi 数据结构（消息、会话条目、事件）的 Kotlin 模型。
- 宿主：`controller.mjs`（会话运行时、单点重绑）、`commands/*.mjs`（命令表）、`ui/`（扩展 UI 对象）、`state.mjs`（持状态并主动推送）。
- 基线：分支 `feat/sdk-host`，pi 1.5.0。

## 第一原则：官方 SDK 优先，不重新发明

**凡是 pi 已经有的功能，App 不自己实现，只接过来。** 手机只改「怎么操作、怎么显示」，**不改官方语义**：什么时候问摘要、哪些条目隐藏、跳转后叶子在哪、哪些文件被忽略，都以官方为准。

遇到一个需求，按这个顺序判断，不要跳步：

1. **SDK 有公开方法 / 事件 / 设置吗？** 有 → 宿主加一条薄命令（`commands/*.mjs`）原样调用，结果字段原样带回，Kotlin 只发命令、画结果。
   例：会话树跳转 = `AgentSession.navigateTree`；打标签 = `sessionManager.appendLabelChange`；新建跨项目会话 = `switchSession(不存在的路径, {cwdOverride})`。
2. **SDK 只在终端界面代码里实现（`interactive-mode`、`tree-selector` 等），没有可调用的 API？** → 对着源码**移植**，并满足三条：
   - 用**官方组件自己**生成标准答案，Kotlin 逐行比对（样板：`tools/collect-tree-fixtures.mjs` → `app/src/test/resources/pi-tree-fixtures/tree.json` → `TreeModelCheck.kt`）；
   - 代码注释写清对照的是哪个 SDK 文件、哪条规则；
   - CI 里加 `--check`，引擎升级让规则变了就先红。
3. **SDK 里根本没有这个概念（手机文件管理、纯界面）？** → App 自己做，但在注释里**明说它不是 pi 的概念**，并且凡是能和 pi 对齐的子规则仍然对齐（例：项目页文件树只默认收起 pi 的 find / `@` 补全也排除的 `.git`、`node_modules`，并留一行可见的「已收起 N 项」，不是自创的隐藏规则）。

**如果为了迁就某个实现而不得不做变通（先生成再删、过滤官方写出的文件、绕开 SDK 的方法），说明设计错了，重构那个实现，不要加变通。** 这条是用血换来的：「切项目后会话列表多出空会话」最初就是用「预写壳文件再过滤」糊过去的，正确做法是用官方的懒落盘路径。

## 手机端的 UI 和操作（App 层自己负责的部分）

宿主和 SDK 管「做什么、规则是什么」；**怎么在手机上看、怎么操作，是 App 层的活。** 官方语义不动：用户在官方终端里能做的事、看到的信息、触发的结果，手机上一样都不能少、不能变。变的只有手指怎么点、屏幕怎么排。

对界面只有三个要求，具体怎么设计由做的人自己的审美决定，不在这里规定：

- **好看**：不简陋，不像调试面板或「能点的日志」。
- **好操作**：单手顺手，手指够得到、不误触，常用的事步数少。
- **人性化**：空、忙、失败的时候也有人话和下一步；长任务看得见进度、能取消；不把终端的样子原样搬过来。

工程上的几条硬约束（和审美无关，是为了不出错）：

1. 界面只投影宿主推的 `state`，不自己攒状态再对账，也不复制 pi 的规则；
2. 官方有中止 / 取消的方法就接上（例：`abortBranchSummary`、`abortCompaction`）；
3. 入口跟着宿主报的能力走，不看版本号；
4. 遵守 `tools/check-ui-consistency.sh`（CI 会跑）：弹层用 `ui/components/PiSheet.kt`，样式取自 `PiTheme`，不堆裸尺寸字面量。
5. 动界面前读 `docs/sdk-host/ui-redesign.md`；能出截图就自己看一眼，出不了就说明并列出需要真机看的屏。

## 不要靠记忆

SDK 的行为、字段名、默认值、边界条件，**一律读源码确认，再动手，再验证**。

- SDK 源码在 `build/pi-contract/node_modules/@earendil-works/pi-coding-agent/dist/`（没有就 `node tools/fetch-runtime.mjs` 或 `node tools/pi-contract.mjs` 取引擎）。用 Grep / Read 看，不要凭印象写。
- 写在代码注释和文档里的 SDK 引用要带文件名；引用的行号会过期，规则本身比行号重要。
- 「我记得 pi 是这样」不是依据。一次曾经出过的错：凭印象写 pi 隐藏点文件，实际 pi 的文件发现用 `--hidden`，只排除 `.git`。
- 自己上一轮写的东西同样不算依据，要重新对照。

## 诚实汇报

- **Compose 代码在本机（云容器）编译不了**（没有 Android / Compose 依赖）。改了 `ui/` 下的 Compose，必须明说「没编译过」，列出具体改了哪些文件，让 CI / 本机构建来验。
- 说「验证过」就要说是**怎么验证的、跑了哪些、哪些没跑**。没跑完的 harness 不能说全过。
- 没验证的路径（例如没有模型就测不了的真实对话流程）要列出来，不要含糊。

## 怎么验证

| 改了什么 | 跑什么 |
|---|---|
| 宿主（`sdk-host/**`） | `node tools/sdk-host/test.mjs`（真宿主 + 真 SDK）、`node tools/sdk-host/contract.mjs` |
| 依赖了 SDK 的某个接口 / 设置键 | `node tools/pi-contract.mjs`（对固定版本的引擎逐项断言；升级 pi 先跑它） |
| Kotlin 纯逻辑（无 Android 依赖） | `bash tools/run-app-pure-checks.sh`（87 个 harness，约 20 分钟；单个跑法见脚本里的 `run_harness`） |
| 会话树模型 | `node tools/collect-tree-fixtures.mjs --pi <引擎目录> --check`，再跑 `tree-model` harness |
| 工具输出解析 | `node tools/collect-tool-fixtures.mjs --check --pi <引擎目录>` |
| 界面一致性 / Android API | `bash tools/check-ui-consistency.sh`、`bash tools/check-android-apis.sh` |
| 任何 Compose | 本机编译不了，靠 CI / `./gradlew`；改完做括号配平、引用残留搜索（Grep 旧符号）这类静态检查，并如实说明 |

纯逻辑尽量写成**没有 Android、没有协程的纯函数**，这样才能在没有手机的机器上真跑（样板：`session/tree/TreeModel.kt`）。长会话要用显式栈遍历，不要递归（几千个条目会爆栈）。

## 常见任务的做法

**加一个 pi 设置项**
1. 在 `ui/settings/PiSettingsRegistry.kt` 声明 `PiSetting`（key 和合法值取自 `settings-manager.js` 的 getter，默认值与 SDK 一致）；
2. 如果之前在 `tools/pi-contract.mjs` 的 `UNEXPOSED_SETTINGS_DECISIONS` 里，删掉那一条；
3. 跑 `node tools/pi-contract.mjs` 和 `settings-audit` harness。

**加一条宿主命令**
1. `commands/*.mjs` 里 `reg.add("域.动作", …)`，只调 SDK，返回 SDK 的结果字段，不要自己加工语义；
2. `host/HostCommands.kt`（或对应 `Host*.kt`）加客户端方法；
3. 更新 `docs/sdk-host/protocol.md` / `capabilities.md`；
4. `tools/sdk-host/test.mjs` 里用真 SDK 加用例（样板：「会话树导航结果字段」那一段，造会话文件、真调 `navigateTree`）。

**接一个还没接的官方能力**
先看 `docs/sdk-host/capabilities.md`：状态是「已接 / 通用口 / 待接 / 不适用」。「通用口」说明 `sdk.call` / `sdk.get` 已经能调到，先评估要不要补具名命令。接完把状态改回表里。

**手机化一个终端交互**
语义不动，操作和显示换成触屏习惯。样板（会话树）：
- 官方的快捷键 → 点击 + 底部面板；
- 官方的字符连线 `├ └ │ ─` → 真线条，层级数取自模型的 3 字符前缀；
- 官方的「Escape 回到树并保持选中」→ 关闭面板 / 摘要被中止后回到同一条；
- 官方的「输入框非空不覆盖」→ `ComposerFill.onlyIfEmpty`。

## 约定

- 代码注释、文档用中文，英文标识符照旧；注释写「为什么」和「对照的官方位置」，不写「做了什么」的流水账。
- 不留死代码、不留「以后再删」的兼容层。接管完成就删旧路径。
- 不为了通过检查而改检查；检查红了先搞清楚是代码错还是检查过时，过时就按官方重新生成标准答案。
- 提交信息写清楚**对照了什么、验证了什么**；一次提交一件事。推送前先 `git fetch` 并合并 `origin/feat/sdk-host`。
- 不要把会话文件（`session-*.jsonl`，已在 `.gitignore`）、密钥、`keystore/` 提交进去。
- 不使用子代理替自己做对照和验证；对照 SDK 源码这件事要自己读。

## 已知缺口（动手前先看，别重复发现）

- 工具调用路径缩成 `~/` 用的是固定的 guest 家目录 `/root`（`AgentLayout.GUEST_HOME`）。
- 跨项目新会话不支持 `parentSession`（宿主明确拒绝，不静默忽略）。
- 项目页文件树只排除 `.git` / `node_modules`，不读 `.gitignore`（pi 的补全会读，要读需要 `fd`）。
- `ProjectScreen.kt` 约 3000 行，可在构建稳定后拆文件。
- 全量能力缺口见 `docs/sdk-host/capabilities.md` 末尾。
