# 面账：每个用户可见的面，真相从哪来，怎么验收

这份文件回答一个问题：**App 里的每一块，我凭什么说它是对的？**

它不是待办清单，而是**覆盖账**——每一面都必须能填出三格：真相来源（`docs/pi-sourced-lists.md` 的规则）、验收方式（哪份文档 / 哪个 harness / 哪条契约断言 / 哪条设备判据）、未决项。**填不出真相来源的那一格，就是 bug 的温床**（§M11、§M12 都是这么来的）。

## 顶层（4 个目的地）

| 面 | 真相来源 | 验收 | 未决 |
|---|---|---|---|
| **会话列表** `SessionsScreen` | pi 写的目录 `<agentDir>/sessions/`（两种布局）+ `-c` 的语义 | harness `sessions`；`docs/session-lifecycle.md` | 设备上确认"退出再进来会话还在、能打开看到完整对话" |
| **对话** `ChatScreen` + `ui/blocks/**` + `ui/render/**` | pi 的事件流（RPC）+ 渲染规格 | harness `tail-follow`；`docs/rendering-review.md`（F1–F34）、`docs/streaming-review.md` | 流式的跟随/闪烁只有设备能定 |
| **工作区** `WorkbenchScreen`（终端 + 工作区） | pi 的 `bash` 工具路径 / pty | harness `guest-paths`；`docs/terminal-*.md` | 终端与文件面**本轮没有重新审**（见下） |
| **设置** `PiSettingsStack` 等 12 个分组 | pi 的文件（`settings.json`）＋ pi 的 `Settings` 接口（schema 问不到） | harness `settings-audit`（6 条断言）；`docs/settings-review.md` | **已收口**：67 键（41 pi + 26 App），本轮删 43 行；0 个无读者、0 个过期白名单。真机判据 4 条待验 |

## 设置里的 12 个分组

| 分组 | 真相来源 | 验收 | 未决 |
|---|---|---|---|
| 模型与推理 | `models.json` / `auth.json` / `models-store.json` / `settings.json` 的三个选择键 | `docs/models-review.md`（进行中）；契约里 10 个内置厂商的 baseUrl | **"AI 改了文件设置页会不会显示"** — 结论是不会（RPC 无重读通道）；修法（`PiFileStamps`/`PiFileWatch` + 模型清单）在制品中 |
| 消息与网络 / 上下文与压缩 / 重试与网络 | pi 的 `Settings` 键，值读文件 | `settings-audit` + `docs/settings-review.md` | 审计进行中 |
| 工具 | pi 的工具名（`get_available_models`… 不，是 `defaultTools` 与 pi 的工具注册表） | 同上 | 同上 |
| 会话 | `sessionDir` 等键 + 上面的会话目录 | `sessions` + `settings-audit` | 同会话面 |
| 扩展与资源 | **pi 看的目录**（`extensions/`、`skills/`、`prompts/`、`themes/`）＋ `pi list`（`settings.json` 的 `packages`） | harness `packages`（扩展/技能/模板/主题四类发现 + 合并写入）；`docs/pi-sourced-lists.md` | 设备上确认四类都能列出来 |
| 外观 | pi 的主题 JSON（**读文件**）＋ App 自己的外观键 | `settings-audit` | 主题键的 `EffectiveKind` 仍是 `Reload`（App 侧热应用），需确认与 pi 的语义不冲突 |
| 终端与 Shell | pi 的 `shellPath`/`shellCommandPrefix` ＋ 我们的 pty | **本轮未审** | 见下 |
| 安全与信任 | `trust.json`（pi 写）＋ App 的授权策略（`pi 无对应物`） | `docs/known-gaps.md` §I10、`gap-disposition.md` | `app.device.*` 是否仍是"第二份授权真相" |
| 运行时与诊断 | 载荷与 pi 的 stderr（App 读） | `docs/known-gaps.md` §M（本轮）；`RuntimeFacts` | 设备上读「引擎启动耗时」那行 |
| 隐私与关于 | 许可证资产（构建期生成）＋ pi 的遥测/分析键 | CI 的许可证断言；`settings-audit` | 无 |

## 协议层（横跨所有面）

| 面 | 真相来源 | 验收 | 未决 |
|---|---|---|---|
| RPC 命令 / 响应 / 事件 | `modes/rpc/rpc-types.ts` 与 `modes/json-event.ts` | **33 命令 + 9 个扩展 UI 方法 + 26 记录类型 + 12 delta 全部有解析与处理**（`docs/rpc-coverage.md`，230 条 `file:line`） | 两处修复需设备端到端确认（`clear_queue` 的排队消息、回合中选模板/技能） |
| 扩展 UI 子协议 | 同上（8→9 个方法） | `docs/rpc-coverage.md`；契约的命令/方法名断言 | 工具的自定义渲染在 RPC 下无通道（`pi 有但我们够不着`） |

## 本轮**没有**重新审的三块（写明，不含糊）

1. **终端与 Shell**（`ui/terminal/**`、`PtyLauncher`、`PtySession`）——历史上审过（termlib 的能力上限、按键栏、`script(1)` 的 winsize 不可达），但本轮的"真相来源"规则没有重新过一遍：`shellPath`/`shellCommandPrefix` 是否真的接上、按键栏与 pi 的键位是否一致、终端里的 guest 环境与引擎的是否同一套。**判据**：终端里 `echo $SHELL`/`env` 与引擎的 `ProotCommand.environment` 一致；`shellPath` 改了之后新开的终端用它。
2. **工作区与 @ 提及**（`WorkbenchScreen`、`PiMentionSource`、文件选择）——`@` 列出的文件集是否与 pi 的 `@` 一致（pi 有自己的 ignore 规则）、工作区路径映射是否只有一处实现（已知 `guestPathFor` 与 `guestWorkspace` 是两份转写）。
3. **设备能力面**（`bridge/**`、`DeviceCapabilityScreen`、权限门）——`app.device.*` 与真实授权是否仍可能矛盾（§I10 的残留）、`/app/health` 的报告是否与实际可用的能力一致。

## 这份账怎么用

- **加新面时**：先填"真相来源"。填不出来，就先写 `pi 无对应物（App 的决定）` 并说明理由（`docs/pi-sourced-lists.md`）。
- **改 pi 版本时**：跑 `tools/pi-contract.mjs`，它的失败信息会指到该重读的 App 文件；本账里"验收"列写着契约的，就是被钉住的那部分。
- **裁"完美"的标准**：不是每一面都有一份文档，而是**每一面都能回答"我凭什么说它是对的"**。答不出来就是没做完。
