# pi 引擎启动延迟：全链路代码/本地测量排查

排查对象：录屏里从「用户发出一条消息」到「pi 开始产出」之间那段 ~75 秒。
排查方式：**只读代码 + 在本机（Android 14 手机上的 proot 容器）做测量**。没有操作手机、
没有 Gradle、没有改 `app/src/main/kotlin/**` 或 `rpc/src/**`、没有 git 写操作。

- 分支/工作树：`/root/pi-android` @ `dce3cf0`（工作树有未提交的 M1 修复）
- pi 源码：`/root/pi-src`（0.85.1）
- 实验台：`/tmp/piprobe`（已装好 pi 0.85.1 全部依赖，node v24.19.0，与 App 载荷同版本），
  我自己的隔离副本在 `/tmp/probe/bench`（`node_modules` 软链到 piprobe，避免与并行的
  另一个 agent 互相干扰）

> ⚠️ 测量有效性说明（必读，决定了下面每个数字能说明什么）
>
> 1. 这台手机在测量期间**被另一个 agent 用同一套 pi 载荷并发压测**（`ps` 可见
>    `/tmp/piprobe/A_new`、`A_old`、`A_none` 的 `cli.js --mode rpc` 进程），并且
>    **内存严重吃紧**：`MemTotal 7 424 224 kB`、`MemFree 86–192 MB`、`SwapTotal 8 388 604 kB`
>    而 `SwapFree 4.1–4.6 GB`（≈3.7 GB 已在 zram swap 里）。
>    同一份工作的墙钟时间因此有 **20–50× 的漂移**（例：`/bin/true` 在静默期 ~10 ms，
>    在测量期 247–554 ms）。**所以墙钟数字我一律给 3 次采样并同时给 CPU 时间**，
>    结论只建立在 (a) CPU 时间、(b) 同一次运行内部的占比、(c) 与墙钟无关的结构性计数上。
> 2. 容器本身就是 proot：`/proc/self/status` 的 `TracerPid` 非零，
>    `/proc/self/root -> /data/data/com.dsh.client/files/linux/ubuntu`。
>    **下面所有数字都已经包含「proot 在真机上」的这一层**——这正是它与被测 App 可比的原因，
>    也是「proot 天生慢」不能当结论的原因。
> 3. 因此我把「整进程」而不是「pi 自己的 `PI_TIMING`」当作主要观测量；后者只覆盖启动的一小段
>    （见 §3.1，有 `file:line` 证明）。

---

## 1. 现象与时间线

以下表格逐字照抄任务书（用户逐帧核对过的事实，我没有重新怀疑）：

| 时刻 | 画面 |
|---|---|
| 1–5 s | 顶部状态「就绪」、空态「引擎已就绪」 |
| ≈7 s | 输入框里是「。哈哈哈」 |
| ≈8 s | 用户气泡出现 |
| 9–76 s | 屏幕上只有那一条用户气泡，**pi 没有任何输出** |
| ≈76.7 s | **同一瞬间**出现：`模型 → deepseek-flash deepseek` 行、扩展 `pi-android-permission-gate` 的 `session_start` 通知（「设备审批已启用：10 个…」） |
| 80–85 s | 「思考 450ms」+ 回复正文 |

要解释的那一段：**从 `ProcessBuilder.start()` 到 pi 第一行 stdout 之间约 75 秒。**

### 1.1 先把「75 秒结束在哪个事件上」钉死（这条决定了后面所有解释的方向）

pi 在 RPC 模式下**启动期间不写 stdout**，第一行 stdout 是 `session_start` 里扩展的
`ctx.ui.notify(...)`。证据链（pi 侧，全部有 `file:line`）：

- `modes/rpc/rpc-mode.ts:55` `takeOverStdout()`——`process.stdout.write` 被改道到 stderr，
  真正的 fd 1 留给 RPC JSON（`core/output-guard.ts:45-83`）；
- `modes/rpc/rpc-mode.ts:382` `await rebindSession()` → `modes/rpc/rpc-mode.ts:319` `await session.bindExtensions({...})`；
- `core/agent-session.ts:2468-2491` `bindExtensions()`：先 `_applyExtensionBindings`，
  再 `await this._extensionRunner.emit(this._sessionStartEvent)`（`:2489`），
  再 `extendResourcesFromExtensions`（`:2490`）。
  **`session_start` 是在这里发出来的，不是 `createAgentSessionRuntime` 里**；
- 该 handler 里的 `ctx.ui.notify` 走 RPC 的 `createExtensionUIContext()` → `output()` →
  `writeRawStdout`（`modes/rpc/rpc-mode.ts:136-147`），落到 fd 1。**这就是第一行 stdout**。

App 侧的对应事实：录屏里 76.7 s 同时出现的「模型」行来自 pi 的持久化 `model_change` 条目
（`rpc/src/main/kotlin/app/pi/rpc/Transcript.kt:1633` → `onModelEntry`），而这条记录只有在
App 的 `get_entries` 请求被 pi 回答之后才会进到 transcript
（`app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt:1480-1502` `replayHistory`）。
**「模型」行和 `session_start` 通知同帧出现，正是「pi 刚刚开始服务」这一个瞬间**，
和 `PiEngineSession.handle` 那句注释（`app/src/main/kotlin/app/pi/engine/PiEngineSession.kt:363-374`，
工作树）描述的是同一件事。

我自己的实验也复现了这个形状：**扩展目录为空时，pi 从启动到退出一个字节都不写 stdout**
（`results/trace-none-1.out`、`trace-none-2.out` 均为 **0 字节**）；带扩展时第一次写出的
正是 623 字节的两条 `extension_ui_request` 通知。

**结论：那 ~75 秒 = 「guest 进程从 `execve` 到 pi 的 stdin reader 挂上」这一整段。**
它包含 node 自身启动 + pi 静态模块图加载 + `main()` 的所有分段 + 扩展加载 + `bindExtensions`。

### 1.2 一条必须说明的推理边界：录屏的「就绪」不能证明 spawn 发生在 1 秒

任务书里那条推论（「t=1 s 就显示就绪 ⇒ spawn 在启动后 ≈1 s」）依赖
`engineLabel` 的 `boot !is Boot.Ready -> "启动中"`。核对工作树后有两处需要标注：

- `app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt:818` 在 `attach()` 里设
  `busy = "正在加载会话"`，而 `engineLabel` 的第一分支是 `current.busy != null -> current.busy`
  （`:2345-2354`）。这一行是 **HEAD~1（`2dbe79f`）才引入的**
  （`git log -S '正在加载会话' -- app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt` → `2dbe79f`）。
  在**含有这一行的构建**上，从 attach 到 `get_entries` 被回答的整段（也就是那 75 秒）
  AppBar 应该说「正在加载会话」，不会是「就绪」。
  所以录屏那个构建**早于 `2dbe79f`**——而 `app/build.gradle.kts:45` 里 `versionName = "0.1.0"`，
  与录屏的 `0.1.5-rc1` 也对不上（仓库里没有任何地方写 `0.1.5`，也没有 tag）。
- 另一条同样成立的读法：**录屏开始时 App 早已启动过**。「就绪」只证明 `Boot.Ready` 已经发布，
  不证明它是 1 秒前发布的。若用户是在 App 已经跑起来之后才开始录屏，
  那么 spawn 可能发生在录屏 t≈−60 s，76.7 s 只是「pi 终于服务了」。

这两种读法都**不改变 §1.1 的结论**，但对「App 自己的 pre-spawn 步骤花了多久」影响很大：
如果 spawn 真的在 1 秒内完成，说明那一刻设备很快（`RuntimeSelfCheck` 那种整进程 proot 启动
也进了那 1 秒），那么 pi 之后的 75 秒就**不能**用「设备当时很慢」解释；
反之则完全可以。**这一点我没法从容器里判定，需要设备侧的时间戳**（见 §5 的证伪测量）。

---

## 2. 实测表格

所有命令都在 `/tmp/probe` 下，node 都是 `/tmp/piprobe/node_modules/.../dist/cli.js`
（改用 `/tmp/probe/bench/...` 绝对路径，`node_modules` 指向同一份）。三个被测变体的
`PI_CODING_AGENT_DIR` 都是我自建的干净目录（不是被并发压测的 `/tmp/piprobe/agent`）：

| 变体 | extensions/ 内容 |
|---|---|
| `none` | 空目录（只有 `skills/`、`auth.json`、`models-store.json`） |
| `ts` | App 的三个 TS 扩展原件（`assets/pi-extensions` 拷贝，共 9 个 `.ts`，~117 KB） |
| `js` | 同一份扩展的 esbuild 预编译 `.js` 版本（`.ts` 已移除） |

> 扩展副本的来源：我在 15:15 从 `app/src/main/assets/pi-extensions/` 拷贝，校验过它
> **逐字节等于 git HEAD 的版本**（`cmp`）。工作树里这两个资产文件在 **15:19 / 15:21**
> 被改过（有人正在把 `pi-android-bridge/index.ts` 的 `session_start` 里那句
> `await bridgeHealth()` 改成 `void ... .then()`），**改动不在我的测量里**。
> 顺便说清：那处改动**不会**缩短启动——`bridgeHealth()` 是 loopback HTTP，5 s 超时 + 1 次重试
> （`assets/pi-extensions/pi-android-bridge/client.ts` 的 `request`/`bridgeHealth`），
> 上限 ~10 s；它影响的是「谁先成为第一行 stdout」和「用户的 prompt 会不会排在它后面」，
> 不是那 75 秒的量级。

### 2.0 测量装置

- `hook.mjs`（`node --import` 注入，**不改被测代码**）：在进程内记录
  「第一个写入 fd 1 的字节的时刻」和 `process.cpuUsage()`（user+sys）。
  **CPU 时间是与负载无关的量**；`node --cpu-prof` 的样本按调用帧归因——
  阻塞在 syscall 里的时间会被记到**调用它的那个 JS 函数**上（所以 `internalModuleStat`
  这种"原生绑定"帧的时间就是 syscall 墙钟）。
- `run3.py`：跑三种模式并落盘 JSON——
  `boot`（`node` 空跑）／`graph`（只 `import dist/main.js`，**不执行 `main()`**）／
  `rpc`（完整 `--mode rpc`，stdin=/dev/null）。
- `--trace-event-categories node.module_timer`：拿到**模块加载的逐条计数和耗时**。

命令（`rpc` 模式，三个变体各自一样；`DIR`/`AGENT` 换掉）：

```bash
cd /tmp/probe/bench
PI_TIMING=1 NODE_COMPILE_CACHE=/tmp/probe/results/cc-ts-warm \
PI_CODING_AGENT_DIR=/tmp/probe/agents/<v> PI_SKIP_VERSION_CHECK=1 DEEPSEEK_API_KEY=sk-dummy \
timeout 150 node --cpu-prof --cpu-prof-dir=/tmp/probe/prof/<label> \
  --import /tmp/probe/bench/hook.mjs \
  /tmp/probe/bench/node_modules/@earendil-works/pi-coding-agent/dist/cli.js \
  --mode rpc --session-dir /tmp/probe/agents/<v>/sessions < /dev/null
```

### 2.1 整进程启动（核心表）

「启动完成」= hook 记录的**第一个 fd 1 字节**（`ts`/`js`，因为第一条输出就是
`session_start` 通知）；`none` 不写 stdout，用 **exit_ms**（读完 EOF 后立刻退出，
等价于「reader 挂上」）。`cpu_ms` = 该 node 进程自己的 user+sys。

| 变体 | 运行 | 启动完成（墙钟） | cpu_ms | user / sys |
|---|---|---|---|---|
| none（pi 本体，无扩展） | 1 / 2 / 3 | **34.92 / 35.59 / 29.21 s** | 10 394 / 11 325 / 10 844 | 6.8+3.6 / 7.5+3.9 / 7.0+3.8 |
| js（预编译扩展） | 1 / 2 / 3 | **35.08 / 30.01 / 34.64 s** | 13 759 / 12 182 / 13 039 | 9.5+4.3 / 8.4+3.8 / 8.5+4.5 |
| ts（App 原样 TS 扩展） | 1 / 2 / 3 | **40.55 / 80.66 / 41.02 s** | 12 928 / 16 941 / 15 756 | 8.3+4.6 / 10.1+6.8 / 10.1+5.6 |

- 排序后的中位：none **34.9 s**、js **34.6 s**、ts **41.0 s**；最小：none **29.2 s**、js **30.0 s**、ts **40.6 s**。
- **就算把扩展全部拿掉，这台手机上 pi 自己要 29–36 秒才服务**；扩展全部预编译成 `.js`
  时与「没有扩展」同档（中位 34.6 vs 34.9）。TS 相对 JS：中位 **+6.4 s（+18%）**，
  最小 **+10.5 s（+35%）**。
- 对照：`boot` 模式（node 空跑）exit 17.3 / 104.4 / 256.5 ms，cpu 8.7 / 32.8 / 44.6 ms
  → **node 二进制自身启动在噪声里可以忽略**。
- 对照：`graph` 模式（只加载 `dist/main.js` 的静态图，不跑 `main()`）exit
  **78.8 / 47.2 / （121.8 s 被 kill）**，cpu 15.6 / 11.5 / —。也就是**「只把模块图 import 进来」
  就已经吃掉了整进程启动的绝大部分**（这一项和 rpc 的 CPU 时间同量级甚至更高，
  说明 pre-main 的模块图加载与 `main()` 体量相当，而 `main()` 那部分就是下面 2.2 里的小数字）。

### 2.2 `PI_TIMING` 自己的数字（对比用，不是答案）

同一次运行 stderr 上的 pi 内部分段：

| 变体 | run | `createAgentSessionRuntime` | main **TOTAL** | extensions 块 TOTAL |
|---|---|---|---|---|
| none | 1 / 2 / 3 | 2809 / — / 2809 ms | **6124 / 1448 / 2922 ms** | 269 / 59 / 176 ms（只有 llama.cpp） |
| js | 1 / 2 / 3 | 2467 / 2021 / 2455 ms | **2730 / 2273 / 2546 ms** | 950 / 891 / 913 ms |
| ts | 1 / 2 / 3 | 2893 / 2160 / 3278 ms | **11 324 / 2009 / 3388 ms** | 2942 / 737 / 1016 ms |

ts run3 的扩展明细：

```
pi-android-bridge/index.ts module import: 516ms   factory: 14ms
pi-android-permission-gate.ts module import: 189ms factory: 1ms
pi-highlight/index.ts module import: 270ms        factory: 22ms
```

**关键对比：`main` TOTAL = 2.5–3.4 s，而这一个进程到「开始服务」用了 34.5–80.7 s。**
也就是说 pi 自己的计时器只覆盖了整段启动的 **约 7–10%**。§3.1 用 `file:line` 解释为什么。

### 2.3 墙钟占比归因（同一次运行内部，与绝对速度无关）

对 `*.cpuprofile` 的样本按调用帧的脚本位置分桶（`native/*` = 无 JS 文件的帧，
即 syscall 绑定与 node 内部 helper）：

| 桶 | rpc-none-3（29.4 s） | rpc-js-3（34.8 s） | rpc-ts-3（41.2 s） | rpc-ts-2（81.5 s） |
|---|---|---|---|---|
| **filesystem/syscall 绑定** | **71.4%** | **71.1%** | **68.6%** | **72.5%** |
| node internal（编译/加载器） | 22.8% | 22.5% | 24.4% | 22.1% |
| **jiti（含其内嵌 Babel）** | 1.1% | 1.6% | **2.0%** | 1.4% |
| **App 扩展自己的 JS** | 0 | 0.1% | 0.1% | 0.1% |
| pi 自己的 dist 代码 | 1.3% | 1.3% | 1.2% | 1.1% |
| typebox / pi-tui / pi-ai / undici / yaml / highlight.js / semver …（各自） | ≤0.8% 每个 | ≤0.9% 每个 | ≤1.0% 每个 | ≤0.8% 每个 |

单帧排行（rpc-none-3，29.4 s）：`internalModuleStat 7.46s`、`ModuleWrap 3.44s`、
`lstat 2.34s`、`open 1.23s`、`stat 1.17s`、`read 1.07s`、`fstat 1.01s`、`close 0.58s`、
`compileSourceTextModule 0.54s`、`realpathSync 0.41s`、`readFileUtf8 0.31s`。

**四张表里占比几乎不动（含那张 81 秒的），所以这是结构性的，不是噪声。**
`internalModuleStat`/`lstat`/`open`/`stat`/`fstat`/`read`/`close` 合起来
≈19.4 s / 20.7 s / 22.6 s（分别占 66% / 59% / 55%）——**它们全都是 node 模块解析器
发出的路径探测，经过 proot 的 ptrace 翻译。**

### 2.4 模块加载逐条计数（与墙钟无关的结构性数字）

```bash
timeout 150 node --trace-event-categories node.module_timer \
  --trace-event-file-pattern=/tmp/probe/modtrace.json <cli.js> --mode rpc --session-dir ... < /dev/null
```

| trace | 模块加载次数 | 不同 specifier | 所有 span 之和 |
|---|---|---|---|
| none-1 / none-2 | **969 / 969** | **466 / 466** | 18.97 s / 13.79 s |
| ts-1 / ts-2 | **969 / 969** | **466 / 466** | 12.56 s / 29.80 s |
| js-1 | **969 / 969** | **466 / 466** | 10.52 s |
| （graph 模式单独一次） | 968 | 465 | 7.53 s |

**五个 run 全是 969 / 466，三个变体一模一样。** 这不是巧合，是 §3.2 的结论之一：
App 的扩展走 jiti 自己的加载管线（`vm`/`_compile`），**根本不进 node 的模块加载器**，
所以对 node 侧这 969 次加载毫无影响。969 次里被计到的最慢几条（graph 那次）：
`undici/index.js` 1 081 ms、`yaml` 425 ms、`semver` 314 ms、`jiti/dist/babel.cjs` 196 ms、
`proper-lockfile` 135 ms、`cross-spawn` 103 ms（最外层 32 个模块合计 2.4 s，其余是嵌套，故有重复计数）。

### 2.5 jiti / Babel 的**纯**成本（隔离测量）

```bash
node --import /tmp/probe/bench/hook.mjs /tmp/probe/bench/jiti_probe.mjs <9 个 .ts>
```

| 项 | run1 | run2 |
|---|---|---|
| `await import("jiti/static")`（静态带入 `dist/babel.cjs` 1.7 MB） | **377.9 ms** | **268.6 ms** |
| `transform bridge/index.ts`（46 363 B） | 32.7 ms | 23.8 ms |
| `transform client.ts` / `danger.ts` | 6.9 / 6.6 ms | 7.3 / 7.2 ms |
| `transform permission-gate.ts` | 5.4 ms | 5.4 ms |
| `transform pi-highlight/{index,service,hljs,html-runs,aliases}.ts` | 2.1 / 7.0 / 5.6 / 4.7 / 3.7 ms | 2.1 / 7.2 / 5.6 / 7.1 / 14.8 ms |
| **9 个文件全部转译合计** | **≈74 ms** | **≈84 ms** |
| 该进程总 CPU（含加载 jiti+Babel） | 722.0 ms | 675.8 ms |

**App 扩展的全部 Babel 转译 ≈ 75–85 ms CPU。哪怕按 30× 的 CPU 慢化算也只有 2.5 秒。**

同一条线上还有一个有用的对照（`jiti_dep.mjs`，冷进程里通过 jiti 加载依赖）：

| `jiti.import` 目标 | 第 1 次 | 第 2 次 |
|---|---|---|
| `typebox` | **3 641 ms** | 2.4 ms |
| `@earendil-works/pi-ai`（compat） | **4 173 ms** | 3.2 ms |
| `@earendil-works/pi-coding-agent`（dist/index.js） | **8 771 ms** | 16.7 ms |

第 2 次是缓存命中，说明 jiti 并没有反复重载依赖；而第 1 次之所以在**真实启动里**
看不到（`index.ts module import` 只有 516 ms），是因为 pi 的
`core/extensions/loader.ts:8-22` 已经把这些包**静态 import 过**，node 的模块缓存里有了，
jiti 拿到的就是缓存。**这反过来说明：`PI_TIMING` 报的每个扩展 100–500 ms 主要是
jiti 自己的解析/包装开销，不是转译。**

### 2.6 proot / 进程创建 / `bash -lc` 的代理测量

在这一台手机的容器里（同样是 proot）：

| 命令 | 5 次采样（ms） |
|---|---|
| `/bin/true` | 554 / 247 / 302 / 283 / 545 |
| `bash -c true`（非登录） | 449 / 505 / 249 / 527 / 898 |
| **`bash -lc true`（登录，读 `/etc/profile` + `/etc/profile.d/*.sh`）** | **1368 / 2141 / 3195 / 1983 / 684** |
| `bash --noprofile --norc -c true` | 263 / 957 / 1118 / 962 / 535 |
| `node` 空跑 `bootonly.mjs`，**静默期** | 10.3 / 18.8 |
| `node` 空跑 `bootonly.mjs`，**压测期**（§2.1 的 boot 模式） | 17.3 / 104.4 / 256.5 |

`bash -lc` 相对 `bash -c` 中位多出 **~1.5 s**；这就是 App 用 `-lc` 换来的代价
（`app/src/main/kotlin/app/pi/runtime/PiRuntime.kt:210-211`），
而这些 profile 文件**不是 App 写的**：`RuntimeProvisioner.configureGuest()`
只写 `etc/resolv.conf`、`etc/hosts`、建 `root/.pi/agent`、`workspace`
（`app/src/main/kotlin/app/pi/runtime/RuntimeProvisioner.kt:587-608`），
`fetch-runtime.mjs` 也没有生成 `/etc/profile` 或 `~/.bashrc`
（`grep -n "profile\|bashrc" tools/fetch-runtime.mjs` 无命中），
所以 guest 里就是 ubuntu-base 的原版 `-l` 流程。

### 2.7 静默期参照（父 agent 给的基线，用于对照漂移）

```bash
cd /tmp/piprobe
PI_TIMING=1 NODE_COMPILE_CACHE=/tmp/piprobe/cc PI_CODING_AGENT_DIR=/tmp/piprobe/agent \
  PI_SKIP_VERSION_CHECK=1 DEEPSEEK_API_KEY=sk-dummy timeout 180 \
  node node_modules/@earendil-works/pi-coding-agent/dist/cli.js --mode rpc \
  --session-dir /tmp/piprobe/agent/sessions < /dev/null
```

- `/tmp/piprobe/err.txt`（无扩展）：`none` **TOTAL 1635 ms**；加 TS 扩展 **TOTAL 2586 ms**
  （扩展 module import 1158+429+124 ms）→ 扩展在**静默期**只值 ~950 ms。
- `/tmp/piprobe/err2.txt`（同一份 TS 扩展，`NODE_COMPILE_CACHE` 预热后）：**TOTAL 1246 ms**。

**关键：放大不是均匀施加在整个启动上的。**

| 同一份 ts 工作 | 静默期 | 压测期 |
|---|---|---|
| `main` 的 TOTAL（CPU 型分段：建 runtime、建 session） | 2 586 ms | 3 388 ms（**只差 1.3×**） |
| 进程级启动物理时间 | 未直接测（pre-main 段当时没测） | 40.6 / 41.0 / 80.7 s（§2.1） |
| `/bin/true`（进程创建 + 少量 syscall） | 10.3 / 18.8 ms | 247 / 283 / 302 / 545 / 554 ms（**25–55×**） |

也就是说：**CPU 型的阶段几乎不受影响，被放大几十倍的是「syscall / 路径解析」这一层。**
这本身就是 §3.2 结论的旁证（放大的位置正好落在占比 68.6–72.5% 的那个桶上），
也说明「设备端是本地 10–30 倍」不能当成一个均匀系数套到 main TOTAL 上。

---

## 3. 根因

### 3.1 先证伪「`PI_TIMING` 的 2.6 s 是启动主成本」这个前提

`packages/coding-agent/src/core/timings.ts:16-38`：`resetTimings` 把
`lastTime` 设成 `Date.now()`，而 `time()` 是**第一次被调用时才惰性建命名空间**。
`main.ts` 里第一次 `time("parseArgs")` 已经是在所有静态 import 求值之后，
所以 **PI_TIMING 的 TOTAL 从 `main()` 的第一行才开始计**。
pre-main 的部分（node 启动 + 整个 ESM/CJS 模块图）**一秒都不在 TOTAL 里**。

实测印证：`rpc-ts-3` 的 main TOTAL 3 388 ms，而同一进程 `first_stdout` = 40 831 ms；
`rpc-none-3` main TOTAL 2 922 ms，而进程到 EOF 退出用了 29 208 ms。
**不在计时器里的那 26–37 秒，才是要解释的东西。**

### 3.2 根因

> **这 75 秒不是被 App 的代码或 jiti 的转译吃掉的，而是 guest 进程自己在
> 「node 模块解析 + 源码读取」这一段被吃掉的；这一段在 proot 下每一次路径探测
> 都要走一次 ptrace 往返，而它要做的探测次数是 pi 静态导入图决定的（实测 969 次模块加载
> / 466 个不同 specifier），并且与 App 的扩展无关（三个变体完全相同的 969/466）。**

支撑（全部是上面表里的数字，不是推测）：

1. **占比**：整进程墙钟里 **68.6–72.5% 落在 node 模块加载器发出的文件系统调用上**
   （`internalModuleStat`/`lstat`/`open`/`stat`/`fstat`/`read`/`close`/`readFileUtf8`），
   另有 22–24% 是 node 的源码编译/包装（`ModuleWrap`/`compileSourceTextModule`/`wrapSafe`）。
   两者相加就是 91–96%，**剩下的全部分配给谁都不够解释 75 秒**。
2. **次数**：969 次模块加载在 `none`/`ts`/`js` 下**完全相同**（5 个 run），
   说明这是 pi 自己的静态图（`undici` 80–92 个文件、`yaml` 43–47、`typebox` 49–62、
   `semver` 23–24、`highlight.js` 9–11、`pi-coding-agent/dist` 44–51…），不是 App 加进去的。
3. **jiti 只占 1.1–2.0%**（四张表稳定），App 扩展自己的 JS 只占 **0.03–0.1%**，
   隔离测量里 9 个 TS 文件全部 Babel 转译 ≈ **75–85 ms CPU**。
4. **数量级自洽**：同一台手机、同样的 proot，这份工作的整进程启动我实测就是
   **29–41 秒（一次坏的窗口 81 秒）**。录屏的 75 秒落在这个区间里，
   不需要任何额外的「设备系数」。

**为什么 pi 要加载这么多模块（可归因到 pi 的 `file:line`）：**

- `packages/coding-agent/src/core/model-runtime.ts:39`
  `import * as builtinProviderCatalog from "@earendil-works/pi-ai/providers/all";`
  —— 而 `@earendil-works/pi-ai/dist/providers/all.js` 顶部**静态 import 了全部 30+ 个 provider**
  （amazon-bedrock / anthropic / google / google-vertex / azure-openai-responses / openai /
  deepseek / groq / mistral / …）。**RPC 模式也一样加载**，因为模型目录是启动必需的。
  这就是 `undici`(80+)、`yaml`(43)、`semver`(23) 这些包出现在 RPC 启动图里的原因。
- `packages/coding-agent/src/core/extensions/loader.ts:8-22`：为了 Bun 编译版能把包当
  `virtualModules` 暴露给扩展，这里又**静态 import** 了
  `typebox`、`typebox/compile`、`typebox/value`、`pi-agent-core`、`pi-ai/compat`、
  `pi-ai/oauth`、`pi-ai/providers/all`、`pi-tui`、`pi-coding-agent/index`
  （`loader.ts:2` 还静态 import 了 `jiti/static`）。
  **但在 App 这条路径上 `VIRTUAL_MODULES` 根本用不到**：它只在
  `loader.ts:505-510` 的两个分支被引用——`isBunBinary || isNodeSeaBinary || isBundledNode`
  与 `isTypeScriptSourceRuntime`。App 用的是 npm 的 `dist/*.js`（`isTypeScriptSourceRuntime`
  在 `dist/config.js:60` 由文件扩展名判定为 false），也没设 `PI_BUNDLED_NODE`
  （`config.js:20`；`grep -rn PI_BUNDLED_NODE app/src/main/kotlin` 无命中），
  于是走第三分支 `{ alias: getAliases() }`（`loader.ts:505-510`），
  `VIRTUAL_MODULES` 构造完就被丢掉。**这些 eager import 里有一半（`loader.ts` 那一组）
  在这个配置下是纯支出**；另一半（`model-runtime.ts:39`）是有用的。

### 3.3 逐条结论：任务书点名要正面排除的候选

| 候选 | 结论 | 证据 |
|---|---|---|
| **App 在同一个窗口里并发起了别的 guest 进程/重活** | **否。启动窗口里 guest 进程恰好只有两个：`RuntimeSelfCheck` 一个，然后 pi 一个。** | 全部 spawn 点：`RuntimeSelfCheck.kt:87`（boot 前，1 个进程）、`PiEngineSession.kt:729`（引擎）、`PtyLauncher.kt:263`（终端页，用户触发）、`GuestCommand.kt:118`（`PiMentionSource` 惰性、`PiPackagesHost` 设置页）、`DeviceShell.kt:55`（宿主 `/system/bin/sh`，按需）。 |
| `DeviceBridgeController.start` | **不阻塞、不跑 guest 进程**：`mintToken` + `assetFingerprint`（只哈希 `assets/pi-extensions`，3 目录 10 文件 ~120 KB，`DeviceBridgeController.kt:350-361`）+ 写两个 token 文件 + `HttpServer.start()`（`DeviceBridgeController.kt:112-155`）。失败也不致命。 | 上面这些行；`assetFingerprint` 的 `ASSET_ROOT = "pi-extensions"`（`:51`），不是整个 assets。 |
| `RuntimeSelfCheck` 跑几个 guest 进程、多久 | **1 个**：`ProotCommand.build(guestCommand = "echo pi-runtime-ok", cwd="/")` → 一整个 proot + `bash -lc`（`RuntimeSelfCheck.kt:79-93`，60 s 超时 `:96-100`）。容器里同类进程实测 0.25–0.55 s（`/bin/true`）到 0.4–3.2 s（`bash -lc`）。**每次启动都跑，即使 stamp 未变。** | 同左 |
| `RuntimeFacts` | **只在设置页读取**，`read()` 返回 4 个宿主文件字段，无 guest 进程（`ui/settings/RuntimeFacts.kt:61-75`）。 | 同左 |
| 包列表／`pi list`／`PiPackageService` | **不在启动路径上**：`GuestCommand` 只被 `PiMentionSource`（`@` 提及，惰性，`PiSessionViewModel.kt:392-394`）与 `PiPackagesHost`（设置页，`PiSettingsStack.kt:247`）持有。启动时没有任何 `pi list`。 | 同左 |
| `PiHighlightClient` | 由代码高亮器用（`highlight/PiNodeCodeHighlighter.kt:114`），只在**渲染代码块**时用 guest 命令；启动/空态不触发。 | 同左 |
| `PiMentionSource` | `by lazy`，不进启动路径（`PiSessionViewModel.kt:392`）。 | 同左 |
| reader 端让输出读不到（`JsonlFramer`／`readLoop`／`Dispatchers.IO` 占满） | **否**：`PiEngineSession.start()` 里 reader、stderr drain、wait 各占一个协程（`PiEngineSession.kt:289-309`），默认 `Dispatchers.IO`（并行度 64）；stderr 也被单独 drain。而且录屏的证据是 pi 根本没写 stdout（§1.1，我实测空扩展时 stdout 为 0 字节），不是读得慢。 | 同左 |
| `PiEngineService` + wake lock | `onStartCommand` → `startForegroundWithNotification()` → `acquireWakeLock()`（`service/PiEngineService.kt:51-70`、`:113-120`），6 小时超时，毫秒级，无 guest 进程。**不是候选。** | 同左 |

### 3.4 `ProotCommand.build` 的完整 argv，逐项在 Android 上的开销形状

`app/src/main/kotlin/app/pi/runtime/PiRuntime.kt:185-215`（`baseBinds` 在 `:167-179`）：

| argv 项 | 行 | 结论 |
|---|---|---|
| `--link2symlink` | `:194` | 只在 guest 调 `link/rename` 时增加处理；启动期几乎没有。**不是成本。** 但注意它需要 `PROOT_L2S_DIR` 存在（l2s 的 bind 在 `:200`，env 在 `:229`；`PiPaths.l2s`），否则每次硬链接 ENOENT——与本次无关。 |
| `-b <rootfs>/.l2s:<同路径>` / `-L` / `--kill-on-exit` / `-0` | `:200-202` | 只加路径翻译表项；`-0` 让 proot 改写 uid/gid 相关返回值。等价成本可忽略。 |
| `--rootfs=` / `--cwd=` | `:203-204` | — |
| `-b /dev`、`-b /dev/urandom:/dev/random`、`-b /proc/self/fd:/dev/fd` | `:168-174` | 定长表项。`/dev/urandom↔/dev/random` 只是别名。 |
| `-b /proc` | `:170` | **把宿主 Android 的 `/proc` 直接绑进 guest**。node/glibc 会读 `/proc/self/*`、`/proc/cpuinfo`、`/sys/...`；这些都是**按次**成本，次数不多。它不会按 `/proc` 下的进程数线性放大（proot 的 bind 是路径翻译，不是遍历）。**没有证据说它是主项；也没法在容器里证伪**——需要设备上的逐 syscall 计数。 |
| `-b /sys`、`-b /system`、`-b /apex` | `:171-173` | 同上，定长表项。 |
| `-b <external>:/sdcard`、`:/storage/emulated/0` | `:175-178` | **只在 guest 真的去访问 `/sdcard` 时才付 FUSE 的代价**；启动路径不访问（工作区在内部存储，`:288` 传的是 `Environment.getExternalStorageDirectory()` 仅用于这条 bind）。**排除。** |
| `<guest workspace> -> /workspace/...`（extraBinds） | `PiEngineHost.kt:289-290` | 一个表项。 |
| `<files>/pi/.pi/agent -> <guest agent dir>`（extraBinds） | `:291-299` | 一个表项，但**这是让扩展从 App 资产目录生效的关键 bind**。 |
| `-b <runtime>/tmp:/tmp`（TMPDIR） | `:209` | 一个表项。 |
| **`/bin/bash` + `-lc`** | `:210-211` | **唯一一项有实测增量：登录 shell 要读 guest 的 `/etc/profile` 与 `/etc/profile.d/*.sh`（§2.6：容器里 `bash -lc` 比 `bash -c` 中位多 ~1.5 s）。** 而且这里完全没有必要：`ProotCommand.environment`（`:226-258`）已经把 `HOME`/`PATH`/`TMPDIR`/`LANG`/`TERM` 全部显式设好，`guestCommand` 是 `exec /opt/node/bin/node <cli> ...`（`PiEngineHost.kt:272-280`），不依赖 profile。 |
| `NODE_COMPILE_CACHE=/root/.cache/node-compile` | `:325`，目录在 `:336-338` 建 | **这是 App 唯一一个直接对抗上面那 22–24% 编译开销的手段，但它只在第二次以后有效**：目录在易失 runtime 树里，每次 revision 变更被 `RuntimeProvisioner.wipe()` 删掉（`RuntimeProvisioner.kt:132-138`）。一次全新安装/升级后的**第一次**启动 = 冷缓存，等于没设。 |

### 3.5 归类（任务书要求的四类）

**pi 有（`/root/pi-src`，我们只能读不能改的部分）**

- `core/model-runtime.ts:39` 静态 import 整个 provider 目录 → 30+ provider 与它们的
  SDK 依赖全进启动图（`packages/ai/src/providers/all.*`）。
- `core/extensions/loader.ts:8-22` 的 9 个静态 import + `:59-140` 的 `VIRTUAL_MODULES`
  与 `getAliases()`；`:2` 的 `jiti/static` 静态带入 `dist/babel.cjs`（1.7 MB）。
- `core/extensions/loader.ts:501-511` `createJiti(..., { moduleCache: false, alias: getAliases() })`。
- `modes/rpc/rpc-mode.ts:55, 382, 319` 与 `core/agent-session.ts:2468-2489`
  → **第一行 stdout 在 `bindExtensions` 之后**，所以 App 的「第一次看到输出」天然要等到
  整段启动结束。
- `core/timings.ts:16-38` → `PI_TIMING` 不覆盖 pre-main，这是**本地量化口径偏小的根因**。
- `core/extensions/loader.ts:702-706` `resolveExtensionEntries` **优先 `index.ts` 再 `index.js`**
  → 预编译 `.js` 必须把 `.ts` 从资产树里删掉才生效（不能只加不改）。

**pi 无对应物（是 App 的决定）**

- proot 整条 argv、`--link2symlink`、`-b /proc,/sys,/system,/apex,/sdcard`、
  `/bin/bash -lc`（`PiRuntime.kt:167-179, 185-215`）。
- `NODE_COMPILE_CACHE`（`PiEngineHost.kt:325,336-338`）——pi 侧没有这个概念。
- `RuntimeSelfCheck`（每次启动白跑一个 guest 进程，`RuntimeSelfCheck.kt:79-100`）。
- 扩展资产安装与 token 发布（`DeviceBridgeController.kt:112-155, 264-361`）。
- `ProotCommand.environment` 里 `PI_CODING_AGENT_DIR` 等（`PiRuntime.kt:226-258`、
  `PiEngineHost.kt:301-330`）。

**pi 有但我们够不着（卡在哪）**

- 想砍掉那 71% 就必须让 `model-runtime.ts:39`/`loader.ts:8-22` 在非 Bun 运行时**惰性化**。
  这是 pi 的 dist 代码，App 只能在**打包期**改它（`tools/fetch-runtime.mjs` 生成的
  `<rootfs>/opt/pi`）。我没做这个改动，因为那属于改上游产物，需要一条 RPC 冒烟测试兜底。
- 设备上 proot 的逐 syscall 成本、以及「FBE 存储 / 页缓存未命中 / zram 换出 / 其它 App 抢 CPU」
  在总时间里的份额，**在容器里区分不了**：容器自己的 proot 配置（DSH 的那一套）与被测 App
  的 proot 配置不是同一条命令，而且容器没法伪造 App 的 rootfs。
  需要设备侧数据（§5）。
- `NODE_COMPILE_CACHE` 对 jiti 转译出来的模块是否生效：jiti 用自己的 `vm`/`_compile`
  管线（§2.4 的另一个证据：扩展加载完全不出现在 node 的 969 次计数里），
  所以缓存大概只覆盖宿主 `.js`。这一点我**没有**单独测出干净数字。

**一致或有意的偏离**

- 扩展发现目录：App 把资产装到 `agentDir/extensions`（`DeviceBridgeController.kt:264-320`），
  与 pi 的 `agentDir/extensions` 规则一致（`loader.ts:787-788`）。
- App 把 `agentDir` 绑进 guest（`PiEngineHost.kt:291-299`），使 pi 看到的是 App 的扩展/设置，
  而不是 rootfs 里的 `<rootfs>/root/.pi`——这是**有意偏离**，也正是三个扩展能生效的原因。
- App 自己的注释已经准确记录了 pi 的这条顺序（`assets/pi-extensions/pi-android-permission-gate.ts:215-223`
  引用 `core/agent-session.ts:2468-2491` 与 `modes/rpc/rpc-mode.ts:316`）——**与 pi 源码一致**，
  而且该 handler 已经把「先 notify、网络结果后到」改好了（同段注释），所以 76.7 s 那条通知
  **不是**在等 5 s/3 s 的 loopback 超时。

---

## 4. 一句话回答

**在那 75 秒里**（百分比按我实测的 ts 中位 41.0 s 归一；把它搬到录屏那 75 s 上，等于假设
多出来的 34 s 落在同一个桶里——这正是需要设备数据才能确认的那一步）：

| 归因 | 我实测的量级 | 占 41.0 s |
|---|---|---|
| **pi 自己的启动图 + proot 的 syscall 翻译**（969 次模块加载，其中 ~70% 墙钟花在绑定/syscall 上） | ~30 s | **~73%** |
| **App：未预编译的 TS 扩展走 jiti**（js→ts 的整进程差；其中 jiti+Babel 代码本身只占整进程 1.1–2.0%） | 6.4 s（中位）/ 10.5 s（最小） | **~16%** |
| **App：`bash -lc` 的登录 profile 读取**（容器代理测量） | ~1.5 s | **~4%** |
| **App：`RuntimeSelfCheck` 每次启动白跑的那个 guest 进程**（代理 0.3–3 s） + 其余 pre-spawn 步骤（migrate/stamp/bridge，代码层面为毫秒级） | 0.3–3 s | **~1–7%** |
| **暂时无法归因**：页缓存未命中 / FBE 存储延迟 / zram 换出 / 其它 App 抢 CPU / 录屏构建的 spawn 时刻 | 未测 | — |

- **绝大部分是「必然的」但被环境放大的**：pi 自己的静态模块图（实测 969 次模块加载、
  316–380 个脚本）在 proot 下要付 68.6–72.5% 的 syscall 翻译/文件系统时间 + 22–24% 的
  源码编译时间。这部分与 App 无关（三个变体同为 969/466），但**它的绝对时长完全由环境决定**
  （同一台机器同一份工作：静默期 main 2.6 s，压测期整进程 29–81 s）。
- **App 造成的部分很小**：`bash -lc` 的登录 profile ≈1.5 s（代理测量）；
  `RuntimeSelfCheck` 每次启动一个多余 guest 进程 ≈0.3–3 s；未预编译的 TS 扩展走 jiti
  的纯成本 ≈0.08 s 转译 + 0.3–0.8 s（jiti+Babel 在 profile 中的占比 1.1–2.0%），
  另外 TS 变体相对 JS 变体在整进程上多花 **6.4 s（中位 41.0 vs 34.6）／10.5 s（最小 40.6 vs 30.0）**，
  这部分我**只能归到「jiti 这条路径触发的额外模块解析」**，无法再细分。
- **暂时无法归因的**：那 75 秒里有多少是页缓存未命中 / FBE 存储延迟 / zram 换出 /
  其它 App 抢 CPU，以及录屏那个构建的 spawn 究竟发生在什么时刻（§1.2 的两种读法）。
  没有设备侧数据，我不会把它说成确定。

**因此：除了「启动成功显示得太早」之外，这里没有第二个 App bug；真正吃时间的是 pi 的启动图
在 proot 下的模块解析成本，而 App 侧可动的杠杆只有「把扩展预编译」「别用 `-lc`」
「别每次白跑 self-check」「让第一次启动也能吃到编译缓存」这四条。**

---

## 5. 最小可执行改动 + 证伪测量

### 改动（按性价比排序，都具体到文件与做法）

1. **把扩展预编译成 `.js` 随包发（App 自己的代码，最干净）**
   - 新增 `tools/build-extensions.mjs`（或在 `tools/fetch-runtime.mjs` 里加一步）：
     用 esbuild（仓库里已有 `tools/pi-highlight-check.mjs` 的同类做法）把
     `app/src/main/assets/pi-extensions/**/*.ts` 转成同目录 `.js`，**并从资产树里删掉 `.ts`**
     （`loader.ts:702-706` 优先 `index.ts`，只加 `.js` 无效）。
   - 期望收益：整进程中位 **41.0 → 34.6 s（−6.4 s，−16%）**；最小 **40.6 → 30.0 s（−10.5 s，−26%）**。
   - 风险：`pi-android-permission-gate.ts` 跨目录 import 了
     `./pi-android-bridge/client`（`assets/.../pi-android-permission-gate.ts:49`），
     转译时必须保留相对路径解析；`import type` 必须被消除（Babel/TS 默认行为）。
2. **`PiRuntime.kt:211` 的 `-lc` 改成 `-c`**
   - `ProotCommand.environment`（`:226-258`）已经显式给出 `HOME`/`PATH`/`TMPDIR`/`LANG`/`TERM`，
     `/etc/profile` 对 pi 没有任何作用。删掉 guest 的 profile 读取。
   - 期望收益：代理测量 ~0.4–2.3 s（这台容器里 `bash -lc true` − `bash -c true`）。
   - 必须验证：guest 里 `pi` 的 PATH 与 `PI_CODING_AGENT_DIR` 仍然正确（跑一次真实启动）。
3. **`RuntimeSelfCheck` 只在运行时 revision 变化时跑**
   - 现状：每次 boot 都 spawn 一整个 proot + `bash -lc echo`（`RuntimeSelfCheck.kt:79-100`）。
   - 做法：把 `Outcome.status == Ok` 连同 revision 一起写进 `.stamp` 旁边的小文件；
     `ensureReady` 的快路径（`RuntimeProvisioner.kt:67-76`）已证明 stamp 可比，
     自检结果用同样的方式缓存；revision 变化或文件缺失时照旧跑。
   - 期望收益：每次启动省掉一个 guest 进程启动（0.3–3 s 量级）。
4. **让「升级后的第一次启动」也能吃到编译缓存**
   - 现状：`NODE_COMPILE_CACHE` 指向易失树（`PiEngineHost.kt:325`），
     revision 变更时被 `wipe()` 删（`RuntimeProvisioner.kt:132-138`），
     而录屏正是一次新安装 → **冷缓存**。
   - 做法（二选一）：(a) 在 `ensureReady` 解包完成后、在**后台**跑一次
     `node -e "import('/opt/pi/node_modules/@earendil-works/pi-coding-agent/dist/main.js')"`
     给缓存预热（代价一次，用户看不到）；(b) 在打包期生成缓存随载荷发（guest 路径稳定在
     `/opt/pi/...`，cache key 才成立）。 (a) 更稳，(b) 收益更大但更脆。
   - 期望收益：直击那 22–24% 的编译桶；静默期实测同一份工作 `2586 ms → 1246 ms`（≈2×）。
5. **（可选、侵入、但唯一能动 71% 那桶的）打包期把 pi 的 eager import 惰性化**
   - 目标行：`dist/core/extensions/loader.js` 里由 `loader.ts:8-22` 生成的那 9 个静态 import
     （在 `alias` 分支下 `VIRTUAL_MODULES` 根本不被读）；以及 `dist/core/model-runtime.js`
     对应 `model-runtime.ts:39` 的 provider 目录 import。
   - 做法：在 `tools/fetch-runtime.mjs` 组装 `<rootfs>/opt/pi` 之后加一步改写
     （把静态 import 换成函数内 `await import()`）。**必须有测试**：RPC 模式下
     `get_available_models` 仍返回全部 provider，否则会变成"启动快但没模型"。
   - 期望收益：上限是那 68.6–72.5% 里与 provider 子图相关的部分（`undici`/`yaml`/`semver`/
     `typebox` 等 150+ 个模块）；**具体多少我没有测**（把目录 stub 掉会让 pi 找不到模型并
     `process.exit(1)`，见 `main.ts:909-912`，量不出可比的对照）。
6. **先做诊断（成本最低、信息量最大）**
   - 在 boot 失败/慢的路径上打开 pi 的 `PI_TIMING=1`（现在 App **没有**设它：
     `PiEngineHost.kt:301-330` 的 env 里没有），把 pi 的 stderr 时序块在
     「设置 → 运行时」里显示出来；
   - 在 App 侧记录三个 `SystemClock.elapsedRealtime()`：`ProcessBuilder.start()` 返回、
     第一行 stdout、第一个 `response`（`PiEngineSession.handle`，`PiEngineSession.kt:361-374`）；
   - 需要时用 `ProotCommand.environment` 的 `extra` 加 `NODE_OPTIONS="--cpu-prof-dir=/tmp"`，
     guest 的 `/tmp` 已经绑到宿主（`PiRuntime.kt:209`），profile 可以直接捞出来——
     那就等于把 §2.3 那张表**在设备上重跑一遍**。

### 什么测量能证伪我的结论

- **如果主因是 jiti/Babel**：那么 (a) 预编译 `.js` 的整进程启动应当与 TS **一样慢**——
  实测 js 30.0/34.6/35.1 s vs ts 40.6/41.0/80.7 s，**不成立**；
  (b) profile 里 `jiti+babel` 桶应当 ≫ 5%——实测 **1.1–2.0%**，**不成立**。
- **如果主因是 App 自己的启动步骤**：那么 `none`（无扩展）也应当同样慢——
  实测 none 29.2/34.9/35.6 s、ts 中位 41.0 s，同一量级 → App 的扩展只是次要项，
  pre-spawn 的 App 步骤更不可能值 75 s（它们在 TOTAL 之外但只值秒级，§3.3）。
- **如果主因是 pi 的模块解析在 proot 下被放大**：那么在**设备上**跑
  `node --trace-event-categories node.module_timer`（或 §5.6 的 `--cpu-prof`）应当看到
  ≈969 次模块加载，且其耗时之和能解释「pi 的 `PI_TIMING` TOTAL」与「第一行输出」之间的
  全部差额；并且 `bash -lc`→`bash -c`、self-check 缓存化、扩展预编译三项的收益分别应当
  落在 ~1.5 s / 0.3–3 s / 6.4–10.5 s 的量级。**任何一个环节对不上，我的归因就是错的。**
- **我明确没测的**：设备上 proot 每次 syscall 的成本（容器无法复刻 App 的 proot 配置）；
  录屏构建里 spawn 的真实时刻（§1.2）；`NODE_COMPILE_CACHE` 对 jiti 模块是否生效；
  把 provider 目录惰性化究竟能省多少。这四项都需要设备侧数据，我不会写成结论。

---

### 附：本文用到的辅助脚本（都在 `/tmp/probe`，未进入仓库）

| 文件 | 用途 |
|---|---|
| `hook.mjs` | `node --import` 注入：第一个 fd 1 字节的时刻 + `process.cpuUsage()` |
| `run3.py` | `boot`/`graph`/`rpc` 三种模式的运行器，落盘 JSON（含 profile 分桶） |
| `summarize.py` | 把所有 run 汇总成 §2.1/§2.2 的表 |
| `jiti_probe.mjs` / `jiti_dep.mjs` / `imports_check.mjs` | jiti 装载与逐文件转译成本、依赖是否重载、转译后还剩哪些 import |
| `trace_run.sh` | `node.module_timer` 逐变体采样（§2.4） |
| `batch1.sh` | §2.1/§2.2 的 3 次重复矩阵 |
