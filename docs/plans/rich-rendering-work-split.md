# Rich Rendering：子代理分工

权威方案：`docs/plans/rich-rendering-upgrade.md`。子代理遇到不确定的地方先读它，再读当前源码；不要凭旧盘点文档猜。

## 当前只做 Phase 0 + Phase 1

不删除任何旧实现；不改模型、系统提示词、工具定义、RPC、Agent 工作流；不运行 Gradle/Android 构建，也不在本机跑耗资源的纯检查；构建与测试统一走 CI。不 commit/push。

## 分工

### 子代理 A：Phase 0 基线

- 只新增 `docs/rich-rendering-baseline.md` 与 `app/src/test/resources/rich-rendering/**` 夹具。
- 不修改生产代码、不改现有测试预期、不改 `tools/run-app-pure-checks.sh`。
- 记录当前行为和需要真机截图的基线项。

### 子代理 B：Phase 1 富块基础

- 新增 `app/src/main/kotlin/app/pi/ui/render/rich/` 下的：
  - `FenceInfo.kt`
  - `RichBlock.kt`
  - `PiJsonTree.kt`
  - `PiXmlTree.kt`
  - `PiYamlTree.kt`
- 新增对应 `app/src/test/kotlin/app/pi/ui/render/rich/*Check.kt`。
- 不改 `PiMarkdown.kt`、`PiMarkdownComponents.kt`、`AssistantTextBlock.kt`、`PiLatex.kt`、`PiMermaid.kt`、`PiHtml.kt`、`PiCodeHighlight.kt`。
- YAML 若没有安全、无新依赖的实现，先做模型/占位并报告；不要擅自引入 SnakeYAML。

### 子代理 C：Phase 1 表格与任务清单

- 只改 `PiMarkdownComponents.kt`（可新增同包小文件）。
- 表格：解析 `TABLE_SEPARATOR`，按列设置 `TextAlign`；保持列宽/横滑/渐隐/50 行/图片 transformer 不变。
- 任务清单：覆盖 `components.checkbox`，只读勾选图标，不伪造可点击持久化。
- 不改其他渲染文件。

### 子代理 D：Phase 2/3 引擎选型研究

- 只写 `docs/plans/rich-rendering-engine-eval.md`，不改代码。

### 子代理 E：文档与许可证盘点

- 只写 `docs/plans/rich-rendering-doc-corrections.md`，不改旧文档、不改代码。

### 父代理：settle 接线、代码块 chrome、集成与验收

- 已做：`LocalPiMarkdownStreaming`、`PiMarkdownText(streaming=...)`、`AssistantTextBlock` 传 `item.streaming`。
- 待做：代码围栏 `FenceInfo` 接入、文件名/行号/折叠/换行/复制反馈；JSON/XML 树接入 `PiCodeSurface`；把 B/C 的文件接到现有组件；注册纯逻辑检查；CI。
- 合并前运行 `git diff` 自审，确认没有改 Diff/工具卡/流式主路径。

## 验收

- 普通 Markdown、表格、图片、Diff、代码高亮、流式跟随不退步。
- 富渲染失败回退源码。
- 未闭合围栏/公式/JSON 不进富渲染。
- 不新增 WebView（Phase 1）。
- 不删除 `PiLatex`、`PiMermaid`、`PiHtml` 旧路径。
