# sdk-host 测试与工具

宿主源码在 `app/src/main/assets/guest/sdk-host/`（会被打进 App 并复制到 guest 的 `/opt/pi/sdk-host/`）。
**设计、协议、迁移任务单全部在 `docs/sdk-host/`，从 `docs/sdk-host/README.md` 读起。**

| 文件 | 作用 |
|---|---|
| `test.mjs` | 起真宿主 + 真 pi SDK + 探针扩展，按 App 的方式驱动，56 项检查 |
| `contract.mjs` | 宿主依赖的 SDK 导出/方法逐项断言（升级 pi 时先跑它） |
| `client.mjs` | 「App 侧最小实现」：帧配对、事件等待。写 Kotlin 客户端时的行为参照 |
| `fixtures/probe-extension.mjs` | 探针扩展：把 `ExtensionUIContext` 每一面都碰一遍 |

```bash
# 需要 build/pi-contract（node tools/fetch-runtime.mjs 之类先取好引擎）
node tools/sdk-host/contract.mjs
node tools/sdk-host/test.mjs                 # 默认：SDK 走 dist/bundle/index.js
node tools/sdk-host/test.mjs --no-bundle     # 回退路径：走 dist/index.js
node tools/sdk-host/test.mjs --scandir-fix   # 模拟 proroot 的 scandir 补丁
node tools/sdk-host/test.mjs --pi <目录>      # 指定别的 pi 包目录
```
