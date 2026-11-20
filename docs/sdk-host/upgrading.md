# 上游 Pi 升级标准流程（SDK Host 时代）

本流程用于在 `@earendil-works/pi-coding-agent` 发布新版本时，**零遗漏、可复现、分层验收**地完成从内置引擎包 → SDK 宿主层 → 扩展层 → 协议/App 层 → 许可证资产的完整升级。

---

## 一、核心原则

1. **先审计，再改版本号**：绝不盲改 `PI_VERSION`。升级判断以「旧版 vs 新版真实 `dist/` 逐字节/AST 差异 + 契约测试」为依据，不靠猜 changelog。
2. **宿主优先吸收差异**：凡是上游内部路径变动、新增运行时诊断、子进程输出控制、扩展生命周期修复，优先在 `app/src/main/assets/guest/sdk-host/` 消化，保持 App 层协议稳定。
3. **单一版本真源**：`PI_VERSION` 只写在 `tools/fetch-runtime.mjs` 一处；依赖树精确锁定在 `tools/pi-engine.lock.json`（构建用 `npm ci`）。
4. **六道门禁全部通过才算完成**：本地契约与 3 组宿主测试全绿 + 静态守卫全绿 + GitHub Actions 6/6 CI 全绿。

---

## 二、标准六步升级流程

### 第 1 步：检测新版本并生成影响审计报告

```bash
# 快速检查是否有新版本（有更新退出码 2）
node tools/pi-upgrade-audit.mjs --check-only

# 运行完整 9 项接缝审计，输出结构化改动清单
node tools/pi-upgrade-audit.mjs --report build/pi-upgrade-report.md
```

审计脚本会自动把当前锁定版本与目标版本逐项比对，并给每条接缝打出结论（`✅ 无变化` / `🔵 信息变更` / `🟡 建议复核` / `🔴 必须处理`）：

| 接缝 | 自动检查内容 | 对应需要改动的仓库位置 |
|---|---|---|
| 1. 版本/依赖/许可证 | `engines.node` 最低要求、直接依赖变更、无许可文件包清单、根 `LICENSE` SHA-256 | `tools/fetch-runtime.mjs`, `tools/pi-engine.lock.json`, `tools/build-license-assets.py`, `app/src/main/assets/licenses/*` |
| 2. SDK Host 契约 | 运行 `tools/sdk-host/contract.mjs <newPi>`，核对所有公开导出 + 15 条内部 `dist/` 路径 + `pi-ai/dist/models.js` | `app/src/main/assets/guest/sdk-host/*`, `tools/sdk-host/contract.mjs` |
| 3. 设置 Schema | `settings-manager.{d.ts,js}` 增删改键对比 `PiSettingsRegistry.kt` | `PiSettingsRegistry.kt`, `PiSessionViewModel.kt`, `commands/settings.mjs` |
| 4. 主题令牌与配色 | `dark.json` / `light.json` 逐键逐值 diff + `theme.js` 变更 | `PiPalette.kt`, `PiThemeFiles.kt`, `colors.xml`, `ui/theme.mjs` |
| 5. 内建命令与 CLI Flags | `BUILTIN_SLASH_COMMANDS` + `cli/args.js` `--flag` 集合 diff | `PiSlashCommands.kt`, `commands/catalog.mjs`, `PiPreSpawnConfig.kt`, `OpenArgsFactory.kt` |
| 6. 工具、渲染器、图片预算、信任 | `core/tools/*`, `renderers/*`, `image-resize-core.js`, `project-trust.js` | `ReadBlock.kt` 等 8 张工具卡、`ToolOutputParse.kt`, `AttachmentBudget.kt`, `ChatAttachments.kt`, `controller.mjs` |
| 7. Runtime / Session / 事件面 | `AgentSessionRuntime`/`AgentSession`/`SessionManager`/`rpc-types` `.d.ts` 与实现 diff | `controller.mjs`, `commands/session.mjs`, `rpc/Events.kt`, `rpc/Transcript.kt` |
| 8. 官方模型目录与 Provider | `pi-ai/dist/providers/data` `schemaVersion`、provider 增删、`env-api-keys.js` | `commands/model.mjs`, `ui/models/*` |
| 9. TUI 与内置扩展兼容 | `pi-tui/dist/tui.d.ts`, `pi-mcp`, `lovely-mermaid`, `highlight.js` | `ui/surfaces.mjs`, `commands/mcp.mjs`, `app/src/main/assets/pi-extensions/*` |

> 若某项报告 `🟡 建议复核` 或 `🔴 必须处理`，直接对 `build/pi-contract`（旧版）与 `build/pi-upgrade-audit/pi-<new>`（新版）跑 `diff -u` 查看精确代码差异，不要只看 release notes。

---

### 第 2 步：更新版本锁定与依赖锁文件

1. 编辑 `tools/fetch-runtime.mjs`：
   - 更新 `const PI_VERSION = "<新版本>";`
   - 在其上方补充简明的 `旧版本 → 新版本` 审计结论（写明判据：哪些逐字节未变、哪些做了适配）。
2. 刷新确定性依赖锁文件：
   ```bash
   node tools/fetch-runtime.mjs --refresh-engine-lock
   ```
3. 核对并更新许可证资产：
   - 若审计报告第 1 项提示 `PI_ENGINE_NO_LICENCE_TEXT` 或 `NPM_LICENCE_FILES` 版本漂移（如 `standardwebhooks`、`@aws-sdk/*`、`tslib`、`lru-cache`），同步更新 `tools/build-license-assets.py`；
   - 同步更新 `app/src/main/assets/licenses/` 下受影响的 4 个文件（`component-list.txt`、`manifest.txt`、`pi-engine-licence-gaps.txt`、`pi-engine-npm-notices.txt`；若上游 `LICENSE` SHA-256 变更则同时更新 `pi-license.txt`）。

---

### 第 3 步：适配 SDK 宿主层与内置扩展

按审计报告第 2、6、7、9 项逐条落地：

- **宿主层**（`app/src/main/assets/guest/sdk-host/`）：
  - 若 `contract.mjs` 报错：先修宿主引用的内部路径或方法名，再同步更新 `tools/sdk-host/contract.mjs`。
  - 若上游新增 SDK 宿主专用能力（如 1.6.0 的 `AgentSessionRuntime.takeUnreportedDiagnostics()`、`packageCommandOutput: "capture"`），在 `controller.mjs` / `commands/*` 接入，并在 `docs/sdk-host/protocol.md` 登记新增事件/字段。
  - 若 `sdk-host` 新引入了任何上游 `dist/` 内部路径，**必须**同步加入 `tools/sdk-host/contract.mjs` 的 `internals` 表。
- **内置扩展**（`app/src/main/assets/pi-extensions/`）：
  - 重点核查 `pi-android-bridge`（工具注册/生命周期）、`pi-android-permission-gate.ts`、`pi-highlight`（`highlight.js` / `lovely-mermaid` 兼容）。

---

### 第 4 步：适配协议层（`rpc/`）与 Android App 层（`app/`）

按审计报告第 3、4、5、6、7、8 项逐条落地：

- **新设置键**：决定「暴露到 `PiSettingsRegistry.kt`」或「不暴露（仅 TUI/桌面项）」，避免读写丢键。
- **工具结果/详情结构变化**：例如 1.6.0 `read` 将图片不支持提示迁入 `details.imageUnsupportedByModel`，需在 `ToolOutputParse.kt` 与对应 `*Block.kt` 同步展示。
- **主题色表变化**：更新 `PiPalette.kt`、`PiThemeFiles.kt`、`colors.xml`、`ui/theme.mjs`，保持 100% 官方主题兼容（禁止在主题填充色上硬编码 `Color.White`）。
- **思考等级 / 模型限制**：确保 `ThinkingPickerSheet` 与 `ChatAttachments` / `AttachmentBudget.kt` 与上游一致。

---

### 第 5 步：本地全量验证（推送前必须全部通过）

```bash
# 1. 把本地契约测试环境更新到目标版本
rm -rf build/pi-contract && mkdir -p build/pi-contract
(cd build/pi-contract && npm install --ignore-scripts --no-audit --no-fund "@earendil-works/pi-coding-agent@<新版本>")

# 2. 跑 SDK 契约测试（公开导出 + 全部内部路径）
node tools/sdk-host/contract.mjs

# 3. 跑 3 套宿主行为测试（与 CI sdk-host job 完全一致）
node tools/sdk-host/test.mjs
node tools/sdk-host/test.mjs --no-bundle
node tools/sdk-host/test.mjs --scandir-fix

# 4. 跑 Kotlin / Android API 静态守卫（禁止 Java 11+ / API 30+ / 嵌套注释）
bash tools/check-android-apis.sh
python3 tools/check-nested-comments.py
```

若本次在宿主层新增了行为（如新事件、新诊断、新渲染兼容），**必须**在 `tools/sdk-host/test.mjs` 补齐对应断言。

---

### 第 6 步：提交推送并确认 6/6 CI 全绿

推送到 `main` 后，等待 GitHub Actions **全部 6 个 Job 绿色通过**：

1. `Protocol core (JVM unit tests)`（`:rpc:test`）
2. `SDK Host (real Pi SDK contract & tests)`（`contract.mjs` + `test.mjs` ×3）
3. `Static safety guards (Kotlin & Android APIs)`
4. `tawcroot`
5. `APK (sideload28)`（真实执行 `node tools/fetch-runtime.mjs` 按 `pi-engine.lock.json` 打包 `pi-engine.tgz` 并验证签名与 APK 体积）
6. `APK (modern36)`

> 提示：升级完成后可清理临时缓存目录 `rm -rf build/pi-upgrade-audit`（该目录在 `build/` 下已被 `.gitignore` 忽略，不会污染仓库）。
