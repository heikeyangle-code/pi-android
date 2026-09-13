# 引擎以 code 1 退出：成因、App 的断裂点、加固

来源：用户一手现象（"一聊天就卡死；退出重进点之前的对话也卡死；有时候提示软件停止运行；**有时候直接提示 `rpc: engine exited with code 1`**"），设备 Xiaomi M2011K2C / Android 14 / memory_free ≈ 1.21 GB / storage_free ≈ 2.75 GB / battery 26%。

本文把两件事分开写，因为它们的证据强度完全不同：

- **验过**：在开发容器里用**钉住的 pi 0.85.1 真跑**得到的退出码与 stderr（第 2、3 节，每个数字都有实验记录），以及推导空间预算用的字节数（第 6 节，实测解包）。
- **未验**：设备上独有的东西（proot 自身失败、被系统杀死、扩展树被写坏），第 3.3、9 节写明判据和怎么取证，**不当作结论**。

pi 侧行号相对 `/root/pi-src`（0.85.1，commit `bbb61e34`，与 `tools/fetch-runtime.mjs` 的 `PI_VERSION` 一致）；App 侧行号是本文写作时的快照，同仓多 agent 并发改动，`file:line` 只保证写作时成立。

---

## 0. 结论（先说结果）

1. **`rpc: engine exited with code 1` 这句话是 App 自己拼的**，不是 pi 打印的：`PiEngineSession` 的 `waitJob` 在 `process.waitFor()` 得到非 0 后，把所有在途 `request` 用 `failure("engine exited with code N")` 完成，`PiEngineApi.kt:24` 的 `PiRpcException` 把这个 reason 拼成 `"${command ?: "rpc"}: $reason"`。它的含义只有两条：**pi 的退出码是 1，而且当时有一条命令在等**（通常是 attach 时的 `get_state`/`get_entries`，或回合中的刷新）。
2. **pi 以 1 退出的成因，实测只有两类**（第 3 节）：**(a) 扩展加载失败**，**(b) 启动参数被 pi 判为错误**；再加一类运行期原因：**(c) 未捕获异常 / 未处理的 promise rejection**（Node 的默认行为就是退出码 1）。`settings.json` / `models.json` / `auth.json` / `trust.json` 坏掉、会话 JSONL 损坏、`--session-dir` 不存在或不可写、`PI_OFFLINE` / `PI_CACHE_RETENTION` 取值怪 —— **全部不会让 pi 退出 1**（有实验，见第 4 节）。OOM 是 SIGABRT（134），不是 1。
3. **设备上最可能的两条**：① `<agentDir>/extensions/` 里三个随包扩展（bridge / permission-gate / highlight）**有一次没被完整复制**就被 pi 加载（存储紧张时复制中断、或指纹标记先于内容写成），于是启动即 `Failed to load extension` → 1；② 用户在设备上装过某个扩展，它 import 一个 phone 上没有的模块（我们自己的桥历史上就 import 过 `highlight.js` / `pi-ai` 的重入口，见 §M9），pi 加载失败 → 1。两者的 stderr 都是**一句话就能认出来**的。
4. **退出码从来没有被记录**：`PiEngineSession` 把它丢进了一句只给在途请求看的字符串里，`DiagnosticsReport` 自述 `exitCode` 恒为 null。本轮已把退出码与 stderr 尾部落进引擎状态，并接到失败屏与诊断报告（第 7 节）。
5. **没有任何自动重启引擎的路径**（第 5 节）：`boot()` 只有两个调用者（组合首帧一次、失败屏的「重试」按钮），`restart()` 一律要用户确认；不存在"退出→重启→再退出"的循环。用户感到的"反复重启"是**每次手动重试都要重新解包/冷启动**。
6. **解包没有任何可用空间检查**（第 6 节）。实测解包后的运行时树 ≥ 438 MB（rootfs 100.7 MB + Node 194.6 MB + pi 144 MB），而 `wipe()` 在解包**之前**执行：空间不足时先删掉旧的、再解到一半失败，`stamp` 不写 ⇒ 下一次启动重新 wipe、再失败。2.75 GB 本身**够**（大约是所需空间的 6 倍），所以存储不是 code 1 的成因，但这个检查的缺失把"存储紧张"变成了"再也起不来"。本轮已加预检。

---

## 1. 复现方法与口径

- 环境：开发容器（x86_64 Linux，node `v24.19.0`），引擎是 npm 装的 **pi 0.85.1**（`/tmp/piprobe/node_modules/@earendil-works/pi-coding-agent`），入口 `dist/cli.js`——与设备上 `PiEngineHost` 用的是同一个文件相对路径（`/opt/pi/node_modules/@earendil-works/pi-coding-agent/dist/cli.js`）。
- 每个用例一个**独立 `PI_CODING_AGENT_DIR`**，命令一律 `pi --mode rpc --session-dir <该目录>/sessions`，进程起来后写一条 `{"id":"r1","type":"get_state"}` 到 stdin，收到应答就让 stdin EOF（pi 正常退出 0）。因此"退出码 0 + 有应答"= 这条配置不影响启动。
- 记录项：退出码、stderr（尾部）、是否收到应答、耗时。
- 空 agentDir 基线的实测形状：**2 秒左右应答，退出码 0**。所以下面凡是"1 秒多到 3 秒就退出 1"的用例，都是在**pi 还没挂上 stdin 读线程之前**就死了——这正好解释了用户看到的现象为什么总是伴随"卡死"：发起 `get_state` 的那次 `request` 一直在等，直到 `waitJob` 把它失败掉。

---

## 2. 实验记录（原始观测）

### 2.1 参数错误

| 用例 | 退出码 | stderr（原文） |
|---|---|---|
| 基线 `--mode rpc --session-dir …` | 0 | （空） |
| 未知短选项 `-zz` | **1** | `Error: Unknown option: -zz` |
| 未知长选项 `--bogus-flag` | **1** | `Error: Unknown option: --bogus-flag` |
| `--mode rpcx`（`--mode` 的值不合法） | **不退出** | （空）——被静默忽略，落到 print 模式读 stdin |
| `--mode rpc` + `@file` | **1** | `Error: @file arguments are not supported in RPC mode` |
| `--system-prompt` 写在**最后一个参数**（没有值） | **1** | `Error: Unknown option: --system-prompt`（不是"requires a value"：`args.ts` 只在 `i+1 < args.length` 时认这个选项，否则落进未知长选项分支） |
| `--model bogus/bogus` | **1** | `Error: Model "bogus/bogus" not found. Use --list-models to see available models.` |
| 设置里写 `defaultProvider/defaultModel` 指向不存在的模型 | 0 | （空，模型退回占位模型，`contextWindow: 0`） |
| `settings.json` 里写 `enabledModels` 指向不存在的模型 | 0 | `Warning: No models match pattern "…"` |
| `PI_OFFLINE=1 PI_CACHE_RETENTION=long PI_SKIP_VERSION_CHECK=1` | 0 | （空） |
| `PI_OFFLINE=0 PI_CACHE_RETENTION=forever` | 0 | （空） |

### 2.2 配置文件坏掉

| 用例 | 退出码 | stderr |
|---|---|---|
| `settings.json` = `{`（坏 JSON） | 0 | `Warning: Invalid settings file …/settings.json: Expected property name or '}' in JSON at position 1` |
| `settings.json` 字段类型错（`reserveTokens: "big"`、`retry.enabled: "yes"`） | 0 | （空） |
| `settings.json` 里塞 App 自有键（`app.runtime.engineStartup` / `app.sessions.resumeLast` / `app.credentials.apiKey`） | 0 | 仅有上面那句模型 pattern 警告 |
| `settings.json` 里写 `systemPrompt` / `appendSystemPrompt` | 0 | （空） |
| `models.json` = `{` | 0 | （空） |
| `models.json` 里 `"deepseek": {}`（空厂商块 —— 按源码应当抛错） | 0 | （空） |
| `models.json` 里模型只有 `{"id":"m1"}`、厂商没有 baseUrl（应当抛 `baseUrl is required`） | 0 | （空） |
| `models.json` 里 `contextWindow: "big"`（类型错） | 0 | （空） |
| `auth.json` = `{` | 0 | （空） |
| `trust.json` = `{` | 0 | （空） |
| `trust.json` = `[1,2,3]`（类型错） | 0 | （空） |

**这一栏的结论比"没退出"更重要**：`settings.json` 的错误是**警告**（`core/settings-diagnostics.ts:4-9` 把 `drainErrors()` 的每一条都标成 `type: "warning"`），而 `models.json` 的错误是**静默**的（`ModelConfig` 存下 `error` 字符串，`getError()` 只在交互模式的 `/reload` 里被打印一次，`main.ts` 的启动诊断里没有它）。所以"坏配置"在设备上表现为**模型消失 / 能力降级**，而不是 code 1。§M11/§M12 讨论的就是这一类的后果。

### 2.3 会话文件与目录

| 用例 | 退出码 | 结果 |
|---|---|---|
| `--session-dir <不存在的多级路径>` | 0 | pi 自己 `mkdir -p`，应答里的 `sessionFile` 就在那里 |
| `--session-dir` 指向一个**普通文件** | 0 | pi 应答里给出 `<那个文件>/<session>.jsonl` —— 引擎照常服务，**落盘时才失败**（会话看起来正常，退出后消失） |
| `--session <损坏的 JSONL>`（第二行是坏 JSON） | 0 | 正常打开，`sessionId` 读到了，`messageCount: 0`（坏行被跳过） |
| `switch_session` 指向损坏的 JSONL | 0 | `{"success":true,"data":{"cancelled":false}}` |

**会话 JSONL 损坏不会让 pi 退出**（这一点和任务清单里的假设不同，实验为准）。它损坏的是**App 的读取器**（`PiSessionStore`/`SessionFileScan`），那是另一条线。

### 2.4 扩展加载失败 —— 这是 code 1 的头号来源

`<agentDir>/extensions/` 下每次只放一个文件：

| 扩展的毛病 | 退出码 | stderr（原文，已截断路径） |
|---|---|---|
| TS 语法错（`const x = ;`） | **1** | `Error: Failed to load extension "…/broken.ts": Failed to load extension: ParseError: Unexpected token …` + `Hint: Start without extensions using "pi -ne".` |
| `import "highlight.js"`（模块不存在） | **1** | `Error: Failed to load extension "…/needs-hl.ts": Failed to load extension: Cannot find module 'highlight.js'` |
| 工厂函数抛错 | **1** | `Error: Failed to load extension "…/throws.ts": Failed to load extension: factory boom` |
| 没有导出合法工厂 | **1** | `Error: Failed to load extension "…/nodefault.ts": Extension does not export a valid factory function: …` |
| 合法扩展 | 0 | 正常应答 |

机制（pi 侧）：`main.ts:897-906` 只在 `runtime.diagnostics` 里存在 `type: "error"` 时 `process.exit(1)`；扩展加载错误正是这样一条 —— `main.ts:781-783` 把 `resourceLoader.getExtensions().errors` 逐条变成 `Failed to load extension "<path>": <error>`，类型 `error`。stderr 里那句 `Failed to load extension` 就是判据。

### 2.5 未捕获异常 / 未处理的 rejection —— 运行期也会 1

| 用例（扩展里 `setTimeout` 400 ms 后出事） | 退出码 | stderr |
|---|---|---|
| `throw new Error("late boom")` | **1** | 完整 JS 栈 + `Node.js v24.19.0` |
| `Promise.reject(new Error("unhandled rejection"))` | **1** | 完整栈（Node 默认 `--unhandled-rejections=throw`） |
| 死循环分配内存（`--max-old-space-size=24`） | `SIGABRT`（134） | `FATAL ERROR: Reached heap limit Allocation failed - JavaScript heap out of memory` |

**注意 1 与 134 的区别**：RPC 模式**没有** `uncaughtException` 处理器（那个 `uncaughtCrash` 只在交互模式的 `InteractiveMode` 里），所以任何一条从扩展里逃出来的异常都会让引擎在**回合中途**以 1 死掉——用户看到的就是"刚开始回复消息直接卡死 + 有时候提示 `rpc: engine exited with code 1`"。OOM 则是 134/`-6`，不是 1。

### 2.6 大记录：App 侧那条 `get_entries` 天花板（"点之前的对话就卡死"）

`JsonlFramer.DEFAULT_MAX_RECORD_CHARS = 8 * 1024 * 1024`（`rpc/src/main/kotlin/app/pi/rpc/Jsonl.kt:109`）：一条超过 8 MiB 的记录会被**丢弃**并计入 `droppedRecords`。

实测：把一条 9,437,619 字节的会话文件（一条 assistant 消息里 9 MiB 文本）交给 `pi --mode rpc --session …`，`get_entries` 的应答是**一条 9,437,727 字符的记录**（一次读取、一行，实测 `maxLine = 9437727`）。也就是说：**会话文件超过 ~8 MiB，App 的 `replayHistory` 那条 `get_entries` 应答必然被丢**。修复前它表现为"点之前的对话直接卡死"（那次 `request` 一直等到 120 s 超时，然后什么都不显示）；上一轮 `PiEngineSession` 的改动把丢弃变成"立刻失败并说明记录超限"，但**这条会话仍然打不开**——这是本轮**未修**的一条，见第 9 节。

### 2.7 proot / 宿主侧的结局（未验，只给判据）

`proot` 自身失败时也是退出码 1，stderr 形如 `proot error: …`（例如 loader 初始化失败、`ptrace` 失败、bind 目标不存在）。开发容器里没有那套 aarch64 rootfs，**本轮没能复现**，因此不作为结论；它和扩展失败的区别就在 stderr 的第一行。被系统 LMK 杀死是信号（`proot` 会以 137 收场），不是 1。

---

## 3. "pi 以 1 退出"的成因表

`file:line` 是 `/root/pi-src/packages/coding-agent` 下的 pi 源码；"实验"一列指第 2 节的观测。

| # | 成因 | pi 的 `file:line` / 实验 | 在我们这里触发它的条件 | 现在 App 会怎么表现 |
|---|---|---|---|---|
| 1 | **扩展加载失败**（语法/模块缺失/工厂抛错/没有工厂） | `main.ts:897-906`（error 诊断 ⇒ `exit(1)`）、`main.ts:781-783`（构造那条诊断）；实验 2.4 | `<agentDir>/extensions/` 里三个随包扩展（bridge / permission-gate / highlight）复制不完整；用户/agent 自己装进 `extensions/` 的扩展 import 了手机上没有的模块（§M9 的同类）；`pi` 版本换了而扩展没跟着换 | 启动即死。`probeServing` 的 `get_state` 还挂在管道里 → `waitJob` 把它失败 → 界面弹 `rpc: engine exited with code 1`，随后失败屏「引擎没有在运行」+「重试」。**stderr 里那句 `Failed to load extension` 之前没人看得到**（本轮已接到失败屏与诊断报告） |
| 2 | **未知启动选项**（长选项） | `core/agent-session-services.ts:113-123` 产出 `Unknown option(s): --x`（`type: "error"`）→ `main.ts:897-906` `exit(1)`；实验 2.1 | 只有一种：**载荷里的 pi 换成了不认某个选项的版本**，而 App 仍按 `PiLaunchOptions` 拼 `--system-prompt` / `--append-system-prompt` / `--no-context-files`。这正是 `docs/pi-contract.md` 要挡的"版本一变、事实就变" | 同上（启动即死）。App 侧唯一的线索是 stderr 的 `Unknown option` |
| 3 | **未知短选项 / `parseArgs` 的 error 诊断** | `main.ts:606-612`（`exit(1)`）、`cli/args.ts:232-234`；实验 2.1（`-zz`） | App 不产生短选项；`claude`… 之类由扩展注册的短标志不在启动参数里 | 同上 |
| 4 | **`--mode rpc` 带了 `@file` 参数** | `main.ts:634-637`；实验 2.1 | App 不传位置参数。**风险点**：将来若有人把用户输入拼进 argv | 启动即死，stderr 明说 |
| 5 | **`--model` 显式指定了不存在的模型** | `main.ts` 的 `findInitialModel` → `console.error(...) + process.exit(1)`（`dist/cli.js` 内联，源码 `cli.ts` 的 `resolveCliModel` 调用点）；实验 2.1 | App **不传** `--model`（模型是 pi 的 `settings.json` + 凭证决定的），所以这条今天够不着；`settings.json` 里的 defaultModel 写错只会静默退回占位模型 | 不会触发 |
| 6 | **`--api-key` 没配 `--model`** | `main.ts:686-695` 产出 error 诊断 → `exit(1)` | App 不传 `--api-key` | 不会触发 |
| 7 | **`--fork` / `--session-id` 与其它旗标冲突、或 id 非法** | `main.ts:318-352`（`validateForkFlags` / `validateSessionIdFlags`，多处 `exit(1)`） | App 用 RPC 的 `fork`/`switch_session`，不传这些旗标 | 不会触发 |
| 8 | **`--tui-mode` / `--use-theme` / `--name` 的值非法** | `cli/args.ts`（diagnostics error）→ `main.ts:606-612` `exit(1)`；`main.ts:663-668`（`--name` 空） | App 不传这些旗标 | 不会触发 |
| 9 | **没有任何可用模型**（`session.model` 为空） | `main.ts:909-912` `exit(1)` + `No models available.` | 需要连占位模型都没有的情况；本环境下**没有复现出**（没有凭证时仍给占位模型）。列在这里是因为它是源码里唯一与"模型"有关的 `exit(1)` | 若真发生：启动即死，stderr 是 `No models available.` |
| 10 | **未捕获异常**（扩展的异步回调、工具实现） | RPC 模式没有 `uncaughtException` 处理器（`uncaughtCrash` 只在 `InteractiveMode`，`modes/interactive/interactive-mode.ts`），Node 默认退出码 1；实验 2.5 | 任何一个扩展的异步路径抛错；我们自己的 bridge/highlight/gate 都在回合里跑 | **回合中途**死 → 在途命令被失败为 `rpc: engine exited with code 1`，失败屏出现 |
| 11 | **未处理的 promise rejection** | 同上（Node 默认 `--unhandled-rejections=throw`）；实验 2.5 | 同上 | 同上 |
| 12 | **pi 自身的 `writeRawStdout` 失败** | `core/output-guard.ts` 的 `void rawStdoutWriteTail.catch(() => process.exit(1))` | stdout 管道被关闭（App 死了？）——App 活着时不会 | 不会触发（App 只有在 `close()` 里关） |
| 13 | **proot 自身失败** | 不在 pi 里；**未验**（2.7） | proot/loader 缺失、bind 源不存在、/proc 不可用、内核拒绝 ptrace | 启动即死；stderr 第一行是 `proot error:`。**这条与 1/2 的区别只在 stderr** |
| 14 | **会话 JSONL 损坏 / `--session-dir` 异常** | 实验 2.3：**都不会退出** | —— | 不是 code 1 的成因。（`--session-dir` 指向文件时会"看起来正常、落盘时才失败"） |
| 15 | **坏 settings / models / auth / trust** | 实验 2.2：**都不会退出**（settings 是 warning，models 是静默） | —— | 不是 code 1 的成因；后果是模型消失/能力降级（§M11/§M12） |
| 16 | **内存不足** | V8 `FATAL ERROR … heap out of memory` → SIGABRT（**134**）；实验 2.5 | 手机可用内存 1.21 GB + 大会话 + 图片 | 消息里是 `engine exited with code 134`（或 `-6`），**不是 1**；诊断报告里能看出是 OOM |
| 17 | **`PI_OFFLINE` / `PI_CACHE_RETENTION` 取值怪** | 实验 2.1：都不退出 | —— | 不是 code 1 的成因。唯一的坑（已写在 `PiLaunchOptions` 的 KDoc 里）：`PI_OFFLINE` 必须**省略**，写成 `0` 也会被 `model-runtime.ts` 当成"禁用模型网络" |

**判据一句话**：拿到 stderr 就能把 1、2、10/11、13 分开，这四类覆盖了"1"的全部已知来源。**所以"把 stderr 留下来"比"猜原因"重要得多**——本轮把它接到了失败屏（`Boot.Failed.detail`）与诊断报告。

---

## 4. App 侧断裂点：引擎死后哪里还会等

| # | 位置 | 现在会怎样 | 判定 |
|---|---|---|---|
| 1 | `PiEngineSession.request` 的 `pending` | `waitJob` 在 `waitFor()` 返回后把**所有**在途请求用 `failure(...)` 完成（`PiEngineSession.kt` 的 `waitJob`） | **不永等**（这是上一轮修好的）。但 `close()` 只 `pending.…` 不动：`close()` 之后仍在等的那条会一直等到自己的超时（默认 120 s，慢命令 600 s）。UI 不阻塞（死亡分支已经把界面切到失败屏），但协程会挂在那里 |
| 2 | `probeServing` 的 300 s 超时 | **超时不算失败**，状态留在 `Starting` | 如果 pi 活着但一直不读 stdin（扩展的 `session_start` 钩子里 `await` 一个不回话的 HTTP，§M6 已把这个钩子改成不阻塞，但用户装的扩展不受我们控制），界面会**一直显示「引擎正在启动」** = 用户嘴里的卡死。**没有暴露给用户的超时后动作** |
| 3 | `PiSessionViewModel.replayHistory` 的 `get_entries` | 120 s 超时；记录被 framer 丢弃时（实验 2.6）等满 120 s 然后什么都不显示（`runCatching{}.getOrNull() ?: return`） | **本轮之前是"彻底静默"**；上一轮改成"记录超限时立刻失败"，但 `replayHistory` 仍然吞掉这个失败（`?: return`）→ 用户看到的是**空对话**而不是错误。见第 9 节 |
| 4 | `PiEngineHost.boot/restart/shutdown` 的 `lifecycleLock`（进程级 `Mutex`） | 没有任何超时：解包/冷启动要几分钟时，点「重启」或切设置页的协程会**排在锁后面等完整个 boot** | 真"无限等待"（等的是别人，不是超时）。界面靠 `engineTransition` 显示"正在重启"，但用户点的那次操作没有上限 |
| 5 | `EngineRestartCoordinator.confirm` → `restartEngine` | 同上，等待时间取决于 boot | 同上；**不会**自动重试（第 5 节） |
| 6 | `DeviceUiAutomation.dispatch` 的 `suspendCancellableCoroutine` | 等 `GestureResultCallback`；无障碍服务被杀时回调可能永远不来 | 真"无限等待"。它会把整个工具调用/回合挂住（pi 侧还在等工具结果）。没有超时。**本轮未修**（要动无障碍面，风险与收益都要单独评） |
| 7 | 对话框倒计时 | 有 `timeout` 的对话框才起计时，到点用 pi 的默认值回答 | 不漏（`armDialogTimer` / `settleDialog` 成对） |
| 8 | `stderr` 的可达性 | `stderr` 有 64 KB 上限、每行排空（必须排空，否则 64 KB 管道满会卡死引擎——这条已对）；但**没有任何调用者把它读出来** | 本轮修：退出码 + stderr 尾部进引擎状态，再进失败屏与诊断报告 |
| 9 | 退出码 | `waitJob` 只用它判断 `Stopped`/`Failed`，**从不保存** | 本轮修（`lastExitCode`） |

---

## 5. 会不会反复自动重启？——不会

调用 `boot()` / `restart()` / `start()` 的路径（全仓 grep 的结果）：

| 入口 | 触发条件 | 会不会自己再触发 |
|---|---|---|
| `PiRoot.kt` 的 `LaunchedEffect(Unit) { session.boot() }` | 组合首帧一次 | 不会。Activity 声明了 `configChanges=…`，旋转/深色模式不重建；进程被杀后重建是系统行为 |
| 失败屏的「重试」按钮（`ChatScreen.kt` → `session.boot()`） | **用户点击** | 不会。每次点击都是"用户接受一次冷启动" |
| `session.restartEngine(reason, allow)` | 设置页的「重启引擎」、装完包后的确认（`EngineRestartCoordinator`，`ExtensionLifecycle` 状态机） | **不会**：`restartStarted()` 只在 `AwaitingConfirmation` 放行，装包后最多停在 `NeedsRestart` 等用户；`restartFailed()` 不会回到"再试一次" |
| `PiEngineHost.boot` 内部 `publish(session)` 硬关旧引擎 | boot 成功时 | 不适用 |
| `PiEngineSession.start()` | 由 `spawn` 调一次 | —— |
| `PiEngineController.stop()` → `host.shutdown()`（通知的「停止」、`onCleared`） | 用户动作/页面销毁 | 停止之后没有任何东西会把它拉起来 |

**没有任何退避/上限的问题，因为没有任何自动重启。** 用户感到的"反复重启"是**手动重试的代价**：每次重试都可能重新走 `wipe()` + 解包（存储不足时必然失败），观感即"卡死 + 反复重启"。因此第 6 节的预检是这条观感的正面解法。

---

## 6. 载荷与存储：解包需要多少空间、不足时现在会怎样

### 6.1 实测（本容器，钉住的载荷）

| 载荷 | 归档字节 | 解包后（文件字节） | `du -sh` |
|---|---|---|---|
| `ubuntu-base-24.04.3-base-arm64.tar.gz` | 29,865,086（28.5 MiB） | **100,694,073** | 105 MB |
| `node-v24.19.0-linux-arm64.tar.xz` | 30,553,480（29.1 MiB） | **194,612,147** | 201 MB |
| `pi-engine.tgz`（`npm install --omit=dev --omit=optional` 后 `tar -czf`） | 23,278,289（22.2 MiB） | **≈144 MB**（`node_modules` 树） | 144 MB |
| `ripgrep` / `fd` / `git`（含库闭包与 CA） | 2.0 + 1.5 + ≈4 MB | 约 5–10 万字节级 + 数十 MB | —— |

**结论：解包后的运行时树 ≥ 438 MB**（100.7 + 194.6 + 144），加 tar 的块对齐、`lib` 别名与 stamp 后按 **480 MiB** 做预算是合理的下界。用户设备的 2.75 GB 可用空间**够**（约 6 倍），所以**存储不是 code 1 的成因**；`pi-engine.tgz` 的字节数随 pi 版本与 `--omit=optional` 的策略变化，其余两项在换载荷时会一起变，所以预算必须写在代码里而不是写在文档里（见 7.2）。

### 6.2 空间不足时**现在**会怎样（改之前）

`RuntimeProvisioner.ensureReady`（`app/src/main/kotlin/app/pi/runtime/RuntimeProvisioner.kt:72-136`）的顺序是：stamp 命中就直接返回 → 否则 **先 `wipe()`**，再依次解压 ubuntu / node / rg / fd / git / engine，最后才写 stamp。所以：

1. 空间不足时 `TarExtractor` 在中途拿到 `ENOSPC` → `ProvisioningException` → `Boot.Failed("运行时解包失败", …)`；
2. **旧的运行时已经被删掉**，新的只解了一半；
3. **stamp 没有写**（它是最后一步），所以下一次启动会再 `wipe()` 一次，再失败一次 —— 循环，每次都要几分钟；
4. 全流程**没有任何可用空间检查**（`usableSpace`/`freeSpace`/`StatFs` 在 `app/src/main/kotlin` 里曾零命中）。

这一段正是"以前能用、现在一启动就 code 1"最像的成因**之一**（另一条是扩展树被写坏，第 3 节 #1）。但它不是"code 1"本身：解包失败发生在 spawn 之前，pi 根本没起来 ⇒ 界面是**解包失败**，不是 `rpc: engine exited with code 1`。**所以设备上要区分这两种现象**（第 8 节的取证协议第一条就是这件事）。

### 6.3 revision 变化会不会清掉运行时？

会，而且这是设计：`RuntimeProvisioner.wipe()` 删 `<files>/pi/runtime`，`runtime-revision.txt` 一变（载荷字节变）下一次启动就重解包；`<files>/pi/.pi/agent`（会话、凭证、settings、扩展）是 bind 的、**在 wiped 树之外**，会留下。同时 `PiEngineHost.migrateGuestAgentDir()` 在 provisioning **之前**把 rootfs 侧的 agent 目录搬出来。所以"载荷 revision 变了"清不掉用户数据，但它**必然触发一次重新解包**，也就必然吃掉 6.1 那 438 MB。设备上的判据：设置 → 运行时与诊断里的「已解包的 revision（stamp）」与「APK 内的 revision」两行是否相同；或导出诊断报告看这两行。

---

## 7. 本轮已修（改动小、每处可讲清）

> 本机**未编译**（用户明令：这台"开发机"就是用户的手机），由 CI 编译验证。

### 7.1 退出码与 stderr 落进引擎状态，并进失败屏

- `app/src/main/kotlin/app/pi/engine/PiEngineSession.kt`：新增 `lastExitCode`（`:258`，`@Volatile` + `private set`，与已有的 `lastServingMs` 同形但按会话保存），`waitJob` 在拿到退出码后写入（`:358`）；在途请求的失败文案追加 stderr 尾部（`:364-372`，新增 `stderrTail()` `:386`，400 字符上限），于是那句 `rpc: engine exited with code 1` 自己就带上了原因。
- `app/src/main/kotlin/app/pi/engine/EngineExitCause.kt`（新，纯对象，无 import）：把 `(退出码, stderr)` 归约成**一句话原因 + 下一步**，覆盖第 3 节里可判别的几类（扩展加载失败 / 未知选项 / 参数形状 / 内存不足 / proot 报错）；认不出来时给"没有写下原因"的诚实句子，并把引擎自己那一行作为证据附上。
- `app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt`：死亡分支原来写 `Boot.Failed(…, null)`（detail 恒为空），现在（`:1040-1082`）先把退出事实存进 `lastEngineExit`（`:1051`），再把 `EngineExitCause.detail(engine.lastExitCode, engine.stderr)` 作为 detail（`:1080`）—— 失败屏本来就渲染 `boot.detail`（`BootScreen.kt:129-133`），所以用户直接看到原因，正文还写着「如果反复失败，把这个提示连同它下面的文字发给我」。
- `ui/settings/DiagnosticsReport.kt` 的 `EngineDiagnostics.exitCode` 之前自述恒为 null；现在由 `PiSessionViewModel.engineDiagnostics()`（`:853`）交付，`recentFailures()`（`:865`）给报告的失败一节，两者在 `PiRoot.kt:253-254` 接到设置栈上（此前那两个参数只有默认值，报告里那两节永远是"还没有记录"）。

### 7.1b 让 `app.runtime.diagnostics` 这一行真的可用（本轮 CI 红的那半步）

`PiSettingsRegistry.kt:1253` 注册了 `app.runtime.diagnostics`，而 `PiSettingsAuditCheck.kt:115-121` 的规则是"每个注册键的字面量必须出现在注册表以外某个源文件里" —— 当时它只出现在注册表里，于是 `settings-audit` 红了。本轮补齐：

- `ui/settings/PiSettingsStack.kt:266`：`hostActions` 增加 `"app.runtime.diagnostics" to { diagnostics = true }`（字面量就在这里，审计靠它判定"有人读"）。
- `ui/settings/PiSettingsStack.kt:311`：新增该分支渲染 `DiagnosticsScreen`，`engineDiagnostics` / `recentFailures` 两个 lambda 直接传下去（`:253-254` 由 `PiRoot` 接上，见 7.1）；返回键链也纳入这一屏。
- 这一行本身是给线上事故用的：报告能导出时引擎已经死了，所以它读的必须是**打开那一屏那一刻**的最新抓取（两个参数因此是 lambda 而不是值）。

### 7.2 解包前的可用空间预检

- `app/src/main/kotlin/app/pi/runtime/RuntimeSpaceBudget.kt`（新，纯对象，无 import）：`requiredBytes(payloadBytes)` = `max(480 MiB, 载荷字节 × 4)`；`shortfall(availableBytes, payloadBytes)` 在数字读不到（≤0）时返回 null（**永远不因为读不到而拒绝启动**）；`message(...)` 给出一句话 + 下一步。
- `RuntimeProvisioner.ensureReady`（`RuntimeProvisioner.kt:112-140`）：在 **`auditPayloads()` 之后、`wipe()` 之前**用 `paths.home.usableSpace` 比对（`:134`），不足时抛 `ProvisioningException(RuntimeSpaceBudget.message(...))`。位置是关键：**预检必须在销毁动作之前**，否则就是"先删掉能用的，再告诉你没空间"。先 `mkdirs()` 再读（`:133`）：`usableSpace` 对不存在的路径答 0，而 0 在 `shortfall` 里是"读不到"，首次启动（`<files>/pi` 还没建）会静默跳过检查——那恰好是最需要它的时刻。报错经 `Boot.Failed` 到失败屏，文案形如「存储空间不足：解包运行时至少需要 480 MB 可用空间，当前只有 210 MB。请清理出至少 270 MB 后重试。」

### 7.3 纯逻辑登记（本机不跑）

- `app/src/test/kotlin/app/pi/runtime/RuntimeSpaceBudgetCheck.kt`（新）与 `app/src/test/kotlin/app/pi/engine/EngineExitCauseCheck.kt`（新）：分别钉住预算的三条边界（读不到空间不拒绝、刚好够不拒绝、差 1 字节要拒绝以及文案里的三个数字）与 `EngineExitCause` 的每一条判据 —— 判据字符串**逐字取自第 2 节的实验输出**（pi 0.85.1 的 stderr 原文），并钉住它的另一半：认不出来时必须什么都不说。
- `tools/run-app-pure-checks.sh`：新增两个 `run_harness` 块（`runtime-space`、`engine-exit-cause`）。

---

## 8. 真机取证协议（用户点什么、看什么、发回什么）

**要开的开关**：设置 → 运行时与诊断里，能开的诊断项都打开（尤其是"引擎启动耗时"与"导出诊断报告"这两个入口）；另外把「后台保活/前台服务」保持默认开启，它决定冷启动期间 CPU 不被冻结。

**第一步：先分清是哪一种失败**（这决定了后面看什么）

1. 清掉界面，重新打开 App，**不要**点任何对话。
2. 看 AppBar 副标题与空态：
   - 停在「启动中」超过 3 分钟 → 是 2.x 的断点 2（引擎活着但不读 stdin），**不是** code 1。
   - 出现解包步骤（"解压 Ubuntu 用户态/解压 Node 运行时/…"）并失败 → 第 6.2 节那条，**不是** code 1。
   - 出现失败屏「引擎没有在运行」→ 看它下面的小字（本轮之后这里会写原因），继续第 3 步。
3. 打开 **设置 → 运行时与诊断 → 导出诊断报告**，先「保存到 Download」，再「分享」发回。报告第一节就是引擎最后一次退出的**状态、退出码、stderr 尾部**（本轮之后退出码不再恒为 null），后面是 revision（已解包 vs APK 内）、六个载荷逐个的大小、关键路径是否存在、可用存储与内存。

**第二步：如果 stderr 里出现这些字样，直接照结论走**（本节与第 2 节一一对应）

| stderr 关键字 | 含义 | 下一步 |
|---|---|---|
| `Failed to load extension` | 有一个扩展没加载起来，pi 因此退出 1 | 到工作区终端里看一眼 `<agentDir>/extensions/`（诊断报告里给了 agentDir 的实际路径），把最近装的那个移走再启动；随包三个扩展的重新安装由 App 启动时自动做（内容指纹标记） |
| `Unknown option` | 载荷里的 pi 与 App 拼的启动参数对不上 | 重装 App（重装才会换回配套载荷）；把这一行原文发回 |
| `FATAL ERROR: … heap out of memory` | 内存不足（退出码会是 134 而不是 1） | 减少同时打开的会话/图片 |
| `proot error:` | Linux 兼容层失败（不是 pi） | 把 stderr 第一行发回；这一行决定是不是 proot/rootfs 的锅 |

**第三步：只有在 stderr 为空时才需要"设备侧继续取证"**（退出码 1 但什么都没打印，最可能是 2.7 的 proot，或某个扩展的异常在 pi 的 stdout 被 take over 之后才打印）。此时：
- 用工作区终端跑 `cd /root/.pi/agent && node /opt/pi/node_modules/@earendil-works/pi-coding-agent/dist/cli.js --mode rpc --session-dir /root/.pi/agent/sessions < /dev/null`，把**终端里打印的东西**整段发回（这条命令不加扩展、不带 `--no-context-files` 等 App 拼的后缀，用来二分"是 pi 本身还是我们拼的参数"）；
- 再加 `-ne`（不带扩展）跑一次：如果 `-ne` 能起来，就坐实第 3 节 #1；
- 把这两次的输出与诊断报告一起发回。

---

## 9. 未修 / 未验证（写明，不当作已解决）

1. **`get_entries` 的大记录天花板没有解**（实验 2.6）。`get_entries` 没有 `limit`/`offset`，`since` 只能"从某个 entry 之后"，而**第一页永远是全部**（`modes/rpc/rpc-mode.ts:638-649`），所以 App 侧无法靠分页把单条记录压到 8 MiB 之下。可选方向：① 加大 `JsonlFramer` 上限（代价是内存：一条 32 MiB 记录在手机上就是数百 MB 堆，可能把 OOM 从"打不开"变成"闪退"，**不建议**单改一个常量）；② `get_tree` + 按需 `get_entries(since=…)` 逐段重建（第一页仍可能超限）；③ 上游给 `get_entries` 加 `limit`。**本轮的处置只有一条**：这一类的失败不再静默（上一轮 `PiEngineSession` 会把在途请求失败掉）。`replayHistory` 仍然吞掉失败（`PiSessionViewModel.kt` 的 `runCatching{}.getOrNull() ?: return`），所以用户看到的是空对话——**这是下一轮该做的第一件事**（至少在失败时给一句"这个会话太大，当前版本打不开"）。
2. **`--session-dir` 指向普通文件**时 pi 会"照常服务、落盘才失败"（实验 2.3）。App 的路径是自己 `mkdirs` 的，正常够不着；列为观察。
3. **proot 自身的退出码 1 未在真机/本机复现**（2.7）。判据是 stderr 的 `proot error:`。
4. **`DeviceUiAutomation.dispatch` 的无超时等待**（第 4 节 #6）未修。
5. **`lifecycleLock` 的无超时等待**（第 4 节 #4/#5）未修：它是"排队等别人"，不是死锁；要加超时得先决定"等到一半放弃"对 boot 的语义，属于设计决定。
6. **扩展复制中断后仍被加载**：`DeviceBridgeController.installExtensionAssets` 在 `copyAssetTree` 返回**之后**才写指纹标记，所以中途抛错不会留下"半棵树 + 已标记"的状态；但**复制成功而内容不完整**（磁盘满导致个别文件 0 字节）它看不出来。判据：`<agentDir>/extensions/` 里出现 0 字节文件。**本轮未加检查**（加一条"每个随包扩展文件非空且尺寸匹配"是下一轮的小活）。

---

## 10. 上 CI 最可能出错的点

1. **两个新 harness 的编译闭包**：`RuntimeSpaceBudget.kt` 与 `EngineExitCause.kt` 必须**import 为空**（纯 Kotlin）——一旦有人给它们加 `java.io.File`（比如让它自己去读 `usableSpace`）或 Android 类型，`tools/run-app-pure-checks.sh` 会**大声失败**（这是设计）。同理，`PiSessionViewModel` 里读空间的那行必须留在 ViewModel/Provisioner 侧。
2. **`RuntimeProvisioner` 的插入点**：预检必须在 `auditPayloads()` 之后、`wipe()` 之前。若有人把它挪到 `wipe()` 之后，CI 不会红，但"先删掉能用的再拒绝启动"的行为就回来了——这条只能靠 code review。
3. **`PiEngineSession` 的并发改动**：本仓同时有多个 agent 在改 `PiEngineSession.kt`（writer 线程、丢记录、`waitJob`），`lastExitCode` 与失败文案是加在 `waitJob` 里的两行；合并冲突会让 CI 红在 `:app:assembleRelease`，而**行为**上的冲突（比如把 stderr 加到失败文案时把 `MAX_STDERR_CHARS` 的截断丢了）不会红。
4. **`EngineExitCause` 的判据字符串**：它逐字匹配 pi 的 stderr（`Failed to load extension` / `Unknown option` / `Not found` / `heap out of memory` / `proot error`）。pi 换版本时这些字符串可能变，届时 harness 仍会绿（它钉的是我们的转写，不是 pi）——**真正该加的是 `tools/pi-contract.mjs` 里的一条**：断言随包 pi 的 `dist/` 里仍存在这五个字样。本轮没加（属于契约面，另开一行更清楚）。
5. **`android.jar` 之外的新依赖**：两个新文件与 harness 只用 Kotlin stdlib；若实现里用到 `kotlin.math` 之外的第三方，harness 的 `LIB_CP` 不够，会以"unresolved reference"红在 CI。
6. **注册了一行却没人读 = `settings-audit` 直接红**（本轮真的红过一次）：`PiSettingsRegistry` 里每个键的字面量必须出现在**注册表以外**某个源文件里（`PiSettingsAuditCheck.kt:115-121`）。`app.*` 不允许声明成 pi-owned（规则 2），所以 Action 行必须自己在别的文件里有处理器——`app.runtime.diagnostics` 就是这样被抓住的。以后加设置行时，注册与处理器要么同一次提交，要么 CI 会红。
7. **未跑的检查**：按用户要求，本机**没有**跑 `tools/typecheck.sh`、`tools/run-app-pure-checks.sh`、Gradle。CI 是唯一的编译器。本机只跑了 `bash -n tools/run-app-pure-checks.sh`、`python3 tools/check-nested-comments.py` 与括号配平粗检。
