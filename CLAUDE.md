@AGENTS.md

# 工作约定

- 用中文直接、简洁、有出处地回复：先给结论，附真实 SDK 源码或文件路径。
- 优先调用官方 Pi SDK，不在 App 侧重写已有能力；UI 与交互按手机端最佳体验自由重构，不背离官方语义。
- Compose 改动本机不编译，靠 GitHub Actions CI 编译打包验证，并如实说明验证状态。
- 直接推送 `main`；推送前同步 `origin/main`。
