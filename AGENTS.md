# AGENTS.md — Pi Android 架构与防回退指南

**Pi Android** = 官方 Pi Coding Agent（`@earendil-works/pi-coding-agent` 1.6.0）的原生 Android 前端。

```
Kotlin / Jetpack Compose（原生手机界面 + 富渲染管线）
        │  Typed JSONL 帧协议（seq · epoch · backpressure，见 docs/sdk-host/protocol.md）
        ▼
Node 宿主 sdk-host（app/src/main/assets/guest/sdk-host/host.bundle.mjs）  ← 单文件薄适配层
        │  直接调用
        ▼
官方 Pi SDK（AgentSession / SessionManager / SettingsManager / ModelRuntime / ResourceLoader）
        运行在 App 内置的 PRoot / Proroot / TAWCROOT Linux 容器中（docs/runtime-engines.md）
```

---

## 一、核心原则

1. **官方 SDK 优先，绝不重复造轮子**：凡是 Pi SDK 已提供的能力（会话、树分支 `/tree`、设置、模型/OAuth、资源、扩展、MCP、Codemode、工具渲染、项目信任），一律通过 `sdk-host` 薄命令直调官方 SDK，不在 App 侧重写逻辑或维护并行状态机。
2. **以真实 SDK 源码为唯一事实源**：不凭记忆猜字段名、默认值或行为，动手前直接查阅 `build/pi-contract/node_modules/@earendil-works/pi-coding-agent/dist/`。
3. **单一内置扩展纪律**：App 侧仅保留 `builtin:pi-android-bridge`（通过官方 `DefaultResourceLoader` 的 `builtin: true, replaceable: true` 预编译进 `host.bundle.mjs`，零 jiti / 零 Babel 冷启动开销）。已移除的 `pi-highlight` 与 `pi-android-permission-gate` 不得回流；代码高亮与 Mermaid/MathJax 全部走 Android 原生离线管线。
4. **移动优先审美，官方语义不变**：界面与交互按现代 Material 3 极简高质感设计，不受旧版 RPC 时代的布局或数值束缚；同时严格保持与官方 Pi 桌面端语义、主题与配置目录（`~/.pi/agent/`、`.pi/`）100% 兼容。
5. **不留死代码与 RPC 历史包袱**：接管完成后立即清理旧路径与废弃设置项。

---

## 二、目录地图

- `app/src/main/assets/guest/sdk-host/`：Node 宿主源码（`main.mjs`、`controller.mjs`、`commands/*.mjs`、`ui/`、`state.mjs`）及打包产物 `host.bundle.mjs`（修改任何 `sdk-host/**` 后**必须**执行 `node tools/sdk-host/build-bundle.mjs` 重建）。
- `app/src/main/assets/rich/`：离线富渲染宿主与引擎资源（`mermaid/` Mermaid 12.2.0、`mathjax/` MathJax 4）。
- `app/src/main/kotlin/app/pi/`：
  - `engine/`、`host/`、`runtime/`：三引擎容器启动（PRoot / Proroot / TAWCROOT）、`PiEngineHost`、`PiEngineSession`、JSONL 协议客户端。
  - `bridge/`：`DeviceBridgeController` 与 Android 设备能力（无障碍/通知/剪贴板/位置/相机/Shizuku）。
  - `ui/`：`PiRoot`、`PiSessionViewModel`、`PiMemoryTrimmer`、`chat/`、`blocks/`、`settings/`、`workspace/`、`tui/`、`theme/`。
  - `ui/render/`：自研 GFM Markdown 管线（`PiMarkdown.kt`、`PiMarkdownComponents.kt`、`PiInlineMath.kt`、`PiLatex.kt`、`PiFastCodeHighlighter.kt`、`RowHeightCache.kt`、`rich/`、`rich/svg/`）。
- `protocol/`：纯 Kotlin 协议模型、ANSI/Diff/JSONL/Transcript 及单元测试（`:protocol:test`）。
- `docs/sdk-host/`：`architecture.md`、`protocol.md`、`upgrading.md`；`docs/runtime-engines.md`。

---

## 三、冷启动性能硬门禁（严禁回退）

由 `tools/check-startup-perf.mjs`（12 项静态守卫）与 `tools/sdk-host/test.mjs`（运行态 `import-audit.mjs`）双重把关：

1. **禁止在 `PiApplication.onCreate()` 中初始化任何 `WebView`**：`WebView.enableSlowWholeDocumentDraw()` 统一通过 `PiWebViewBootstrap.ensureWholeDocumentDraw()` 在首次真正需要富渲染 WebView 时懒调用，冷启动保持 0 ms Chromium 初始化成本。
2. **冷启动零 jiti / Babel / undici / `http-dispatcher`**：
   - 仅当用户显式配置了 `httpProxy` 或 `httpIdleTimeoutMs` 时才在 `ext.bound` 后调用 `ensureHttpSettings()`；默认网络下延迟至首次实际请求。
   - 禁止往 `/root/.pi/agent/extensions/` 投放需 jiti 实时编译的内置 `.ts` 扩展。
3. **打包产物与零 RTT 握手**：
   - 修改 `sdk-host/**` 必须重打 `host.bundle.mjs`。
   - `main.mjs` 必须在 `host.ready` 中内嵌 `buildHelloPayload(reg)`、通过 `setImmediate` 预载 SDK、并同步 `process.env.PWD = cfg.cwd`；`PiEngineSession.kt` 必须消费 `readyHello` 跳过串行 `host.hello` 往返。
   - `controller.mjs` 的 `session.open` 必须内嵌初始 `state`（`snapshotOf(this)`）；`PiSessionViewModel.attach()` 必须复用 `stateStore.state.value`。
4. **进程拉起与资产快路径**：
   - `PiEngineHost.kt` 必须保留 `.scandir-fix.stamp` 快路径、`async(Dispatchers.IO)` 并行预处理与 `selection.planDirect()`（禁止套 `/bin/bash -c`）。
   - `DeviceBridgeController.kt` 必须保留 `.pi-android-apk-stamp` 快路径，并把 `usableCapabilities` 快照写入 `device-bridge.json`，让 `builtin:pi-android-bridge` 启动期 0 HTTP 激活工具。
5. **Activity 启动与首屏防抢占**：
   - `MainActivity.onCreate()` 必须在 `setContent {}` **之前**触发 `session.boot()`，且 `boot()` 保留 `bootInFlight` 并发保护。
   - `attach()` 仅在 `_state.value.stats == null` 时补拉 `refreshStats()`，并维持 `delay(400) -> refreshTrustPrompt()`、`delay(800) -> refreshSessions()` 的错峰节奏，不与首屏争抢单线程 Node。

---

## 四、流式输出零抖动与滚动性能硬门禁（严禁回退）

1. **绝不对流式文本做时间节流（Throttle）**：`AssistantTextBlock` 传给 `PiMarkdownText` 的流式文本必须直通，不可加 50ms/100ms 节流——`TailFollow` 依赖每次内容发布的同步锚定来锁定底部，节流会制造高度跳变与跟底抖动。
2. **禁止在 `LazyColumn` 聊天行内使用 `BoxWithConstraints` 或 `SubcomposeLayout`**：子组合布局会导致行高在测量期反复变动引发滚动回弹/闪跳。富组件（如 `PiMermaidView`）一律使用 `LocalConfiguration.current.screenWidthDp` 推导确定性布局尺寸。
3. **保留双路径零闪烁 Markdown 解析与跨滚动缓存**：
   - 短内容与命中 `PiMarkdownDocCache` 的已定稿消息走帧 0 同步路径（计入 `piMarkdownParseBudget`）；长流式回复走 `Channel.CONFLATED` 后台解析并在 `sync -> async` 切换期间通过 `retainedDoc` 保底，绝不闪回原始 `**markdown**` 或塌陷为 0 高度。
   - 保留 `RowHeightCache`（记住已测行高兜底）、`PiMarkdownDocCache`（64 条定稿 AST LRU）、`PiCodeHighlightCache`（128 条语法高亮 LRU）与行内 `withStreamingCursor`（`▍`）。
4. **统一内存裁剪**：新增任何进程级缓存（位图 / AST / 高亮 / 树解析）都**必须**接入 `PiMemoryTrimmer`（`onSessionReplaced` / `onTrimMemory` / `onLowMemory`），防止后台堆膨胀导致 Android LMK 连带杀掉 Node 宿主进程。

---

## 五、富渲染管线硬约束（Mermaid / MathJax / LaTeX / 代码高亮）

1. **WebView 离屏捕获铁律**：
   - 任何新 WebView 创建前必须先调 `PiWebViewBootstrap.ensureWholeDocumentDraw()`。
   - `mermaid/host.html` 与 `mathjax/host.html` 必须携带 `<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0, user-scalable=no">`；`PiMermaidRenderEngine.configure()` 必须锁定 `useWideViewPort = false`、`loadWithOverviewMode = false`、`textZoom = 100`、`setInitialScale(100)`，杜绝 WebView 默认 980px 桌面缩略图缩放导致图缩成角落小点。
   - 位图缩放系数 `cssScale` 必须基于 JS 实测 `window.devicePixelRatio` 计算，并通过 `__piSetStageScaleAndFlush`（双 `requestAnimationFrame`）+ `awaitVisualState` 后抓取；若硬件层被 ROM（MIUI/HyperOS/Samsung）离屏剔除出全透明帧，必须自动触发一次 `LAYER_TYPE_SOFTWARE` 重试，再走 AndroidSVG 兜底。
   - 修改 `mermaid/host.html` 或升级 `mermaid.min.js` 时，必须同步递增 `PiMermaidSvg.ENGINE_VERSION` 令旧缓存失效。
2. **公式与围栏完备性**：
   - `PiMathRenderEngine` 保持 MathJax 4 `linebreaks.inline: false`，确保行内公式不异常断行。
   - `PiInlineMath` 必须持续覆盖行内 `$...$` / `\(...\)` / `\[...\]`、单/多行 `$$...$$` / `\[...\]`（含首行直接写内容）以及独立 `egin{aligned/equation/align/gather/cases/matrix/...}` 环境，同时避开代码块、货币 `$5 and $10` 与 Markdown 表格。
   - `PiFenceSniff` 覆盖 Mermaid 12 全系图种（含 `xychart-beta`、`sankey-beta`、`kanban`、`architecture-beta`、`C4*`、前置 `%%` 注释与 YAML frontmatter）。

---

## 六、UI / Compose 与双语规范

1. **现代 Compose API**：状态收集统一使用 `collectAsStateWithLifecycle()`；方向性图标统一用 `Icons.AutoMirrored.*`；不引入已废弃的 Material 2 或 Accompanist API；全面屏遵循 `enableEdgeToEdge()` + `WindowInsets`。
2. **克制中性视觉**：严禁默认 M3 紫粉配色与 `surfaceTint` 脏紫染色；优先用 `surfaceContainer` 色阶层级与留白区分模块，关键交互接入 `LocalHapticFeedback`。
3. **严格中英双语**：所有面向用户的 UI 字符串必须使用 `tr("中文", "English")` 并保持中英文 XML key 100% 对齐，通过 `node tools/check-i18n.mjs` 检验。

---

## 七、验证清单

| 改动范围 | 验证命令 |
|---|---|
| 修改 `sdk-host/**` 打包 | `node tools/sdk-host/build-bundle.mjs` |
| 宿主契约与运行态套件（326 项 + `import-audit`） | `node tools/sdk-host/contract.mjs && node tools/sdk-host/test.mjs` |
| `:protocol` 核心模型单测 | `./gradlew :protocol:test` |
| Kotlin / Android API / 双语 / 冷启动 12 项守卫 | `python3 tools/check-nested-comments.py && bash tools/check-android-apis.sh && node tools/check-i18n.mjs && node tools/check-startup-perf.mjs` |
| 完整编译、JVM 单测、TAWCROOT 与双签名 APK | GitHub Actions CI 全 6 项（`tawcroot`、`SDK Host`、`Static safety guards`、`JVM unit tests`、`APK modern36`、`APK sideload28`） |

- 直接推送 `main` 分支，推送前先同步 `origin/main`；禁止提交 `session-*.jsonl` 或任何密钥/Token。
- 完成任务汇报时，如实列出实际执行的本地校验与 GitHub Actions 6 项 CI 状态。
