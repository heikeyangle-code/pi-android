# 升级 pi 版本

目标：**升级只动一两个地方**。能做到靠这些：事件原样透传；`sdk.call`/`bus.*` 通用口；契约脚本逐项报告变化。

## 流程

1. 改锁定版本（`tools/fetch-runtime.mjs` 里的 pi 版本号）并重新取运行时，让 `build/pi-contract` 指向新版。
2. `node tools/pi-contract.mjs`（App 侧契约）和 `node tools/sdk-host/contract.mjs`（宿主侧契约）。
   - 宿主契约红 → 输出会列出缺的导出 / 方法。**只改宿主里对应的那一处**（多数是改名）。
   - 「`dist/bundle/index.js` 存在」红 → 上游改了打包布局：宿主会自动回退到 `dist/index.js`，功能不丢但变慢，另开任务找新的快速入口。
3. `node tools/sdk-host/test.mjs`，三种变体（默认 / `--no-bundle` / `--scandir-fix`）。
4. 读新版 `CHANGELOG.md` / `docs/`，只看这几类：
   - **新增 `AgentSessionEvent` 类型**：App 解析器忽略未知类型，不会崩；要展示再加。
   - **新增 `ExtensionUIContext` 方法**：先看 `ui/context.mjs` 是否有；没有，新版扩展调用时会抛错 —— 补一个实现（先做成「通知 App + 降级」）。**这是升级时最容易漏的一项**，测试里的探针扩展要同步加上新方法。
   - **`main.js` 新增的启动职责**（迁移、代理、信任、设置）：对照 `controller.mjs` / `main.mjs` 里列的「宿主替 main() 做的事」，补齐或记进 capabilities.md「已知缺口」。
   - **内置扩展列表变化**（`dist/extensions/index.js` 的 `builtInExtensions`）：同步 `controller.mjs` 的 `builtinExtensions()`。
5. 真机跑 runtimes.md 的验证清单。

## 为什么这样能控制成本

| 升级带来的变化 | 需要改什么 |
|---|---|
| 内部实现变了、bug 修了、新工具、新技能格式 | 什么都不用改（宿主不碰这些） |
| 新事件 | 不改宿主；App 想展示时改 App |
| 新 `AgentSession` / `SettingsManager` 等方法 | 不改宿主，App 用 `sdk.call`；稳定后补具名命令 |
| 方法改名 / 删除 | `contract.mjs` 报出，改宿主一处 |
| UI 上下文新增方法 | 宿主 `ui/context.mjs` 补一处 + 探针扩展 |
| `main()` 新增启动职责 | 宿主补一处，或记缺口 |
