@AGENTS.md

# 工作约定

- 用中文直接、简洁、有出处地回复：先给结论，附真实 SDK 源码或文件路径。
- 严格遵循官方 Pi SDK（`@earendil-works/pi-coding-agent`）：所有官方已有能力都经 `sdk-host` 薄命令直调，不在 App 侧另造轮子或恢复已删除的 RPC 时代补丁。
- 严守性能与体验红线：任何改动都不得破坏冷启动 12 项守卫（`tools/check-startup-perf.mjs` + `import-audit.mjs`）、流式零抖动（`TailFollow` / `RowHeightCache` / 禁止 `LazyColumn` 内 `BoxWithConstraints`）及富渲染稳定性。
- UI / Compose 改动按现代 Material 3 极简克制审美与最佳实践编写（`collectAsStateWithLifecycle`、`Icons.AutoMirrored`、全部可见文案经 `tr("中文", "English")` 双语化）。
- Compose 改动本机不编译，改完先跑全部本地静态守卫与 `sdk-host` 测试，再推送 `main` 由 GitHub Actions CI 完成 6 项全量编译验证，并如实汇报验证结果。
- 直接推送 `main`；推送前同步 `origin/main`，绝不提交密钥或 `session-*.jsonl`。
