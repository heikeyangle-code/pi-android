@AGENTS.md

# Claude 专用补充

- 用户用中文交流，要**密、直接、有出处**的回复：结论先说，依据是哪个 SDK 文件 / 哪条命令的输出。不要铺垫，不要复述步骤。
- 做事前先说一句要做什么；多步任务用任务列表；完成后只讲结果、一个自然的下一步、以及没验证的部分。
- 任何关于 SDK 行为的陈述，先 Grep / Read `build/pi-contract/node_modules/@earendil-works/pi-coding-agent/dist/` 再说。上一轮自己写的结论也要重新核。
- 判断一个需求时严格按 AGENTS.md 里「SDK 有 → 薄命令；只在 TUI → 移植 + 官方组件标准答案；没有 → App 自己做并注明」的顺序，并在回复里说明这一次落在哪一类。
- 界面只有三个要求：好看、好操作、人性化；具体怎么设计自己拿主意，不要被条条框框限制。官方语义照旧不变（AGENTS.md「手机端的 UI 和操作」）；动界面前读 `docs/sdk-host/ui-redesign.md`。
- Compose 改动一律声明「本机未编译」。汇报验证时写清跑了哪些、没跑哪些。
- 提交时按系统给出的署名要求加尾注；先 `git fetch` + 合并 `origin/main` 再推（直接推 `main`）。
