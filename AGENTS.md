# AGENTS.md — Pi Android 开发指南

**Pi Android** = 官方 Pi Coding Agent（`@earendil-works/pi-coding-agent`）的原生 Android 前端。

```
Kotlin / Compose（手机界面）
        │  JSONL 帧协议（docs/sdk-host/protocol.md）
        ▼
Node 宿主 sdk-host（app/src/main/assets/guest/sdk-host/）   ← 薄适配层
        │  直接调用
        ▼
官方 Pi SDK（AgentSession / SessionManager / SettingsManager / ModelRuntime / …）
        运行在 App 内的 proot / proroot / tawcroot guest 中（docs/runtime-engines.md）
```

## 核心原则

1. **官方 SDK 优先，不重新发明**：凡是 Pi SDK 已提供的能力（会话、设置、模型、认证、资源、扩展、MCP、工具渲染），一律通过 `sdk-host` 薄命令直接调用官方 SDK，不在 App 侧另写一套逻辑或兼容层。
2. **以真实 SDK 源码为唯一基准**：不凭记忆假设行为、字段名或默认值，动手前直接查阅 `build/pi-contract/node_modules/@earendil-works/pi-coding-agent/dist/`。
3. **手机端体验自由发挥，官方语义不变**：界面与交互按手机触屏习惯做到**好看、好操作、人性化**，不受旧版 RPC 时代的布局、文档或 UI 数值束缚。
4. **不留死代码与历史包袱**：接管完成后直接删旧路径。

## 目录结构

- `app/src/main/assets/guest/sdk-host/`：Node 宿主（`controller.mjs`、`commands/*.mjs`、`ui/`、`state.mjs`）。
- `app/src/main/kotlin/app/pi/`：Android 客户端（`host/`、`engine/`、`ui/`、`session/`、`settings/`、`runtime/`、`packages/`）。
- `rpc/`：Pi 协议数据模型、ANSI/Diff/JSONL/Transcript 纯 Kotlin 模块及单元测试（`:rpc:test`）。
- `docs/sdk-host/architecture.md`、`docs/sdk-host/protocol.md`、`docs/runtime-engines.md`：核心架构与协议参考。

## 验证方式

| 改动范围 | 验证命令 |
|---|---|
| 宿主（`sdk-host/**`） | `node tools/sdk-host/contract.mjs` 与 `node tools/sdk-host/test.mjs` |
| `:rpc` 核心模型 | `./gradlew :rpc:test` |
| Kotlin / Android 静态防呆 | `python3 tools/check-nested-comments.py`、`bash tools/check-android-apis.sh` |
| 完整 Kotlin / Compose 编译与打包 | GitHub Actions CI（`apk` `sideload28` + `modern36`） |

- 直接推送 `main` 分支，推送前先 `git fetch` 同步远端；不要提交 `session-*.jsonl` 或密钥文件。
- 汇报验证结果时如实说明跑了哪些本地检查与 CI 任务。
