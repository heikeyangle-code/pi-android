# 后台任务：长活不再占满工具调用

> 状态：扩展已随包（`app/src/main/assets/pi-extensions/pi-background/index.ts`），
> 已登记进 `PiBuiltinExtension.SHIPPED`。**真机验收未做**：CI 不编译扩展的 TypeScript，
> 所以这个文件只有设备上真的加载、真的跑完一个长任务才算验证过。

## 要解决的问题

模型的输出只能出现在两次工具调用之间（协议属性）。一个 `bash` 调用阻塞多久，这一轮就
多久没有可以承载文字的位置 —— 用户看到的现象就是「它在等一个十几分钟的命令，期间一句话
都不能说」。

## 做法

不试图让模型边跑边说，而是**把长活挪出工具调用**：

1. `bg_start{command, cwd?}` —— 在 guest 里起一个分离进程，**立刻**返回 job id。这次工具
   调用当场结束，模型自由了（可以继续对话、也可以去干别的）。
2. 进程结束时，扩展用 `pi.sendMessage(..., {triggerTurn: true, deliverAs: "followUp"})`
   往会话里投一条消息：退出码、用时、日志路径、日志末尾 20 行。`triggerTurn` 给模型一个
   回合，于是它会**主动把结果说出来**；`followUp` 保证这条消息在当前排队工作结束后才进入，
   不会切开正在执行的工具调用。

依据都在 pi 自己的文档里：`docs/extensions.md` 的能力表列了
`pi.sendUserMessage()` / `pi.sendMessage()`（「Send user or custom messages」），
`docs/rpc-commands.md:31` 写明扩展命令在 streaming 期间也立即执行、由 `pi.sendMessage()`
自己管理 LLM 交互。

## 工具

| 工具 | 作用 |
|---|---|
| `bg_start` | 起任务，返回 id / pid / 日志路径；不阻塞 |
| `bg_list` | 列出任务（内存句柄 + 磁盘索引，含上一台引擎留下的记录） |
| `bg_status` | 一个任务的状态、退出码、用时、日志末尾 |
| `bg_output` | 读日志末尾（按 pi 自己的 50KB / 2000 行上限截断） |
| `bg_kill` | 整个**进程组** SIGTERM → 3 秒后 SIGKILL |
| `/bg` | 给人看的同一份列表（`ctx.ui.notify`） |

## 状态与落盘

日志与索引在 `<agentDir>/background-jobs/`（`PI_CODING_AGENT_DIR`，app 已钉在 guest 的
`/root/.pi/agent`），不在 `/tmp`：那个位置在易失树之外，引擎重启与运行时重装都不会删掉
用户正在等的日志。`jobs.json` 保留最近 50 条。

**诚实的边界**：进程句柄只活在内存里。引擎重启后 `bg_list` 仍说得出「有哪些任务、退出码是
什么」，但那是**记录**而不是**实时读数** —— 重启时正在跑的任务不会被恢复，扩展会把它们记成
`已结束（信号 engine-restart）`，`bg_status` 也会写明「内存里没有它的句柄」。

## 与设备桥的分工

通知模型（`pi.sendMessage`）和通知手机前的人（`ctx.ui.notify`，需要时再调设备桥的
`android_say`）是两条独立通道，这个扩展两条都用，但都不越界：扩展只管自己的任务，设备能力
仍然只有设备桥提供。

## 怎么验

1. 装包后在对话里让它跑一个明显超过 30 秒的命令（例如 `npm ci` 或 `ffmpeg` 转码）。
2. 关键观察：**工具调用是否当场返回**（不是等命令跑完），以及命令结束时是否**自动出现一条
   汇报消息**。
3. `bg_list` / `bg_status` 应能读到同一条任务的日志；`bg_kill` 应能停掉它**和它的子进程**。
4. 反例也要试一次：跑一个失败的命令（`exit 3`），汇报消息应写出非零退出码与日志末尾。
