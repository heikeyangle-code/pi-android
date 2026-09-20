# proroot 模式全面排查（四列账：现象 → 根因 → 影响面 → 处置）

> 排查日期：2026-09-20。对象：`app.runtime` 的 proroot 档（`runtime/**` 的 proroot 实现 +
> 开关/探针/回滚 + 两个宿主侧入口）。
>
> **方法与边界（先读）**：
> - 全程**没有编译、没有构建、没有提交**；验证留给 CI。
> - 证据 = 代码 `file:line` + **本机只读实测**。本开发容器**本身就是 DSH App 的 proroot 客户机**
>   （研究 §0），当前 launcher 是 `PID 28089`，其 argv 已在本文多处引用。这是"同机制、不同实现"
>   的外推：凡从它推出的关于 app.pi 的结论都按**外推**看待，我没有在 app.pi 自己的 rootfs 里
>   跑过任何东西。
> - 实测都是只读命令（`cd`/`pwd`/`ls`/`stat`/`readlink`/`tty`/`script`/`id`/`command -v`）。
> - `38690fb4` 正在改 `GuestRecipe.kt` 与 `ProrootCommand.kt` 的绑定/cwd 段；本文**没有改这两个
>   文件**，只对它的在飞修法做边界与性能审查（§1.3）。
> - 未验证项集中在 §13。

---

## 0. 一句话结论

**proroot 模式下绝大多数症状是同一个根因**：**绑定 host 侧的拼写**（`Context.getFilesDir()` 给的
`/data/user/0/...` 是符号链接拼写，内核报 `/data/data/...`），而两个运行时都靠**字符串前缀**
做 host→guest 反向映射。绑定的 host 侧不是内核的拼写 ⇒ guest 的 `getcwd()` 泄漏宿主路径 ⇒
① 一切相对路径 ENOENT；② pi 的**项目信任键**随运行时改变 ⇒ 项目级 skills/prompts/themes/
extensions 与项目 `settings.json` 被当成"未信任项目"整体丢弃。**修这一处，两个症状一起消失。**

**一个症状不属于这条根因**：`android_*`（随包扩展，装在 agent 目录、**全局**、绝对路径）消失 ——
它解释不了（§3），根因**未确认**，已给出设备上三步判别法。

---

## 1. 根因 A：绑定 host 侧拼写（在飞修法中，本文做边界与性能审查）

### 1.1 实测：唯一的差别就是绑定的 host 侧拼写

同一台 proroot 客户机、同一个 shell、连续两条命令（`/dev/shm` 的绑定值是
`/data/user/0/com.dsh.client/cache/shm:/dev/shm`，`/sdcard` 的是
`/storage/emulated/0:/sdcard`）：

```
$ cd /dev/shm                       # 绑定 host 侧是符号链接拼写（非内核拼写）
$ /bin/pwd
/data/data/com.dsh.client/cache/shm     ← getcwd() 泄漏宿主路径
$ ls -d .
ls: cannot access '.': No such file or directory   ← 相对路径解析坏掉
$ stat -c '%n' .
stat: cannot statx '.': No such file or directory
$ pwd -P                            # bash 内建：信 $PWD，所以掩盖了它
/dev/shm

$ cd /sdcard                        # 绑定 host 侧是内核拼写
$ /bin/pwd
/sdcard                             ← 正确
$ ls -d .
.
```

**两条独立结论**：
1. **只有 `-b` 的 host 侧需要规范化**。同一 launcher 的 `-r` 是 `/data/user/0/...`（PID 28089
   的 argv），而 rootfs 内的路径全部正确：
   ```
   readlink -f /etc → /etc    /root → /root    /tmp → /tmp    /dev/fd → /dev/fd
   ```
   → **`-r` 不需要 canonical 化**（proroot 内部处理了 rootfs 前缀）。这条要写进 KDoc，
   免得后人"顺手也 canon 一下"。
2. **bash 内建命令会掩盖这个 bug**：`pwd -P` 打印 `/dev/shm`，而 `/bin/pwd`、`ls`、`stat`
   暴露宿主路径/ENOENT。这解释了"有的地方看着正常、有的一操作就坏"。

另外两条实测（结论 = 核过没问题）：
- 绑定**顺序**语义与 proot 一致（后覆盖前）：`-b /dev` 在前、`-b /dev/urandom:/dev/random`
  在后时，`/dev/random` 的 major:minor 是 `1:9`（= urandom），不是 `1:8`。
- `/dev/fd`（`-b /proc/self/fd:/dev/fd`）在 proroot 下可用；`/sdcard` 可达。

### 1.2 代码侧根因

| 项 | 位置 |
|---|---|
| 唯一裁决点（在飞） | `runtime/GuestRecipe.kt:132-158`（`canonicalHost` / `bindValue`）、`runtime/ProrootCommand.kt:185-189`（`bindArgument`） |
| 拼写的两个来源 | `PiPaths.agentDir`（`Context.getFilesDir()` → `/data/user/0/...`）与 `Context` 的 `storage.path`（`/storage/emulated/0`，已 canonical） |
| 反向映射是字符串前缀匹配（研究 §1.4 解出的配置表 + 上面的实测） | `docs/proroot-research.md:118-124` |
| 受害的绑定点 | workspace、agent dir（`engine/PiEngineHost.kt:616`）、`/tmp`（`GuestRecipe.tmpBind`）——全都是 `/data/user/0/...` 拼写 |

### 1.3 对在飞修法的审查：3 个边界缺口 + 1 个热路径性能问题

在飞实现（工作树）是：

```kotlin
fun canonicalHost(host: String): String {
    if (host.isBlank()) return host
    if (host == PROC || host.startsWith("$PROC/")) return host
    return runCatching { File(host).canonicalPath }.getOrDefault(host)
}
fun bindValue(value: String): String { /* 只在有 ':' 时换 host 侧 */ }
```

方向正确（实测已证明），但要满足"任何情况下都没有 bug"，下面四条要补齐。
**这些改动都落在 `GuestRecipe.kt`/`ProrootCommand.kt` —— 正被 `38690fb4` 写，所以我不动，交给他或由父代指派。**

| # | 边界 | 现状 | 影响 | 修法（唯一裁决点内） |
|---|---|---|---|---|
| A1 | **相对路径** | `File("foo").canonicalPath` 会用 **JVM 的 `user.dir`** 拼出一个宿主绝对路径 | 调用方若传相对绑定值，会被**静默改写成 app 进程 cwd 下的路径**——一个谁都没要过的挂载源 | `if (!host.startsWith("/")) return host`；断言 `canonicalHost("rel/x") == "rel/x"` |
| A2 | **process-relative 符号链接**（`/dev/stdin`、`/dev/stdout`、`/dev/stderr`、`/dev/fd`） | `canonicalPath("/dev/stdin")` → `/proc/<app pid>/fd/0`：**进程相关、每个进程不同**，和 `/proc/self` 是同一类错误 | 今天表里没有这些 host 侧条目（`/proc/self/fd` 已被 `/proc` 规则覆盖），所以**当前无害**；但"唯一裁决点"必须对所有情形成立 | 把排除集从 `/proc` 扩到 `PROC` ∪ {`/dev/fd`,`/dev/stdin`,`/dev/stdout`,`/dev/stderr`}；每条一个断言 |
| A3 | **裸值（`host == guest` 简写）展开** | `ProrootCommand.bindArgument` 对裸值广播 `canonical:canonical` | 若裸值是别名拼写（如裸的 `/data/user/0/x`），**guest 挂载点被一起改写成 `/data/data/x`** —— 简写断言的是"host 与 guest 相同"，不是"可以移动挂载点"。今天的裸值全是 `/dev`/`/proc`/`/sys`/`/system`/`/apex`（canonical），**无害但规则不安全** | 裸值展开为 `canonicalHost(v):v`（只动 host 侧）；断言"guest 侧逐字等于输入" |
| A4 | **热路径**（父代专项要求） | `GuestRecipe.binds()`/`tmpBind()` 每次 spawn 都走 `bindValue` ⇒ 每条目一次 `File.canonicalPath`（≈9 条 + tmp + 2~3 条 extra）；而 `ProrootCommand.build` **先** `ensureWorkdir(..., boundPairs(...))`（`ProrootCommand.kt:314`）**再**自己重新遍历 `GuestRecipe.binds(...)` + `extraBinds`（`:331-338`）⇒ **同一批 canonicalPath 每次 spawn 算两遍** | 每次 guest 启动多 ~20 次 `canonicalPath`（每次几条 syscall）。引擎每轮对话、每次 bash 工具调用都 spawn | ① 在 `GuestRecipe` 里加**进程级 memo**（`ConcurrentHashMap<String,String>`，key = 输入串；判据：canonical 拼写只取决于路径上的符号链接结构，app 进程存活期里 app 数据目录与系统挂载点不会被重新链接）；② `build` 复用 `boundPairs(...)` 的结果，不要重算 |

**一句话代价说明**：修完后，canonical 化的成本从"每次 spawn ~20 次"降为"每个不同路径每进程 1 次"，
启动/每轮对话的增量是 **0 次 syscall**（命中 memo）。

### 1.4 断言清单（交给实现者，落在既有 bare-JVM harness）

`tools/run-app-pure-checks.sh` 的 `proroot` harness（`app/src/test/kotlin/app/pi/runtime/ProrootCheck.kt`）
里逐条加：
1. `bindValue("/proc/self/fd:/dev/fd")` 逐字不变；
2. `bindValue("/proc:/proc")` 逐字不变；
3. `canonicalHost("/dev/stdin")` 不变（A2）；
4. `canonicalHost("rel")` 不变（A1）；
5. `canonicalHost("")` 为空串；
6. 裸值展开后 guest 侧逐字等于输入（A3）；
7. memo：同一输入在进程内只解析一次（给 `GuestRecipe` 注入一个可计数的解析器，断言调用次数）；
8. 多用户/别名的两条：`/data/user/<N>`（N≠0 无符号链接 → 不变）与 `/data/user/0`（→ `/data/data`）
   —— 后半条依赖真机符号链接，harness 里用**注入的解析器**表达，不强依赖本机布局。

---

## 2. 根因 A 的第二个症状：技能 / 命令面板（新报）

| 现象 | 根因 | 影响面 | 处置 |
|---|---|---|---|
| proroot 下"安装的技能啥的都识别不到，命令面板里都没有"；proot 下正常 | **同一个根因 A**：pi 的项目信任键是 `canonicalizePath(resolvePath(cwd))`（`pi-src/core/trust-manager.ts:44-46` 的 `normalizeCwd`，查询在 `:185-207`；决定在 `core/project-trust.ts:48-90`），proroot 下 `cwd` 是宿主拼写 ⇒ 键与 proot 时期（以及 App 写下的）不一致 ⇒ `trust.json` 查不到 ⇒ 无决定 + 无 UI ⇒ **false**（`:86-90`）；而项目级资源只在 trusted 时加载（`core/resource-loader.ts:389-398` 的 `resolveProjectTrust` + `setProjectTrusted`，`core/package-manager.ts:2379`/`:2392` 的 project 半边） | 项目级 `skills`/`prompts`/`themes`/`extensions` 与项目 `settings.json`（含把 skills 注册成 `/` 命令的那个开关）**整体消失** ⇒ 命令面板空。**agent 目录（`~/.pi/agent/skills`）是绝对路径，不受影响** —— 这正是"proot 有、proroot 没有"的判别点 | **不要单独修**：A 修好后 `getcwd()` 回到 guest 拼写，键即恢复稳定，本症状自动消失。现在打补丁只会把"一个根因两个症状"变成"两个补丁两个真相" |

**设备上 10 分钟判别法**（把"引擎没发现"与"App 没显示"分开，父代要求 #3）：
1. `设置 → 扩展与资源` 的「技能」计数 —— 这是 **App 侧宿主扫描**（`ui/settings/PiResourceFacts.kt:1-30` 明说数字来自真实扫描），与运行时无关。有数字 ⇒ 文件在。
2. 命令面板 —— 内容来自 RPC `get_commands`（`ui/chat/PiSlashCommands.kt:344-362`）。空 ⇒ **引擎没加载**（不是 App 没显示）。
3. 终端里分别看两级：`ls /root/.pi/agent/skills`（agent 级 = 全局）与
   `ls <workspace>/.pi/skills`（项目级）。**项目级有、全局也有，但面板空 ⇒ 就是信任键**（根因 A）。

---

## 3. `android_*` 随包扩展全消失 —— 根因未确认（**关键未验证项**）

| 现象 | 根因 | 影响面 | 处置 |
|---|---|---|---|
| 切到 proroot 后 `android_bridge_status` 等 `android_*` 一个都不在；只剩 pi 内建工具 + 一个 MCP | **未确认**。已排除：随包扩展由 `bridge/DeviceBridgeController.kt:282-306` 写进**两份**（`paths.agentDir/extensions` 与 `<rootfs>/root/.pi/agent/extensions`），引擎把 durable 那份绑到 guest `/root/.pi/agent`（`engine/PiEngineHost.kt:616`）并把 `PI_CODING_AGENT_DIR` 钉成它（`:621`）；pi 的**全局**扩展目录是**绝对路径**（`pi-src/core/extensions/loader.ts:770-772` 的 `discoverExtensionsInDir(join(getAgentDir(),"extensions"))`，`core/package-manager.ts:2386` 的 global 半边）⇒ 不该受 cwd/信任影响 ⇒ **A/B 解释不了它** | 三个随包扩展注册的全部工具（设备桥、权限门、高亮）消失 | **未修**（缺设备证据）。候选按可能性：① **扩展模块加载抛错而无人报**：`discoverExtensionsInDir` 的 `catch { return [] }`（`loader.ts:739-741`）会让**整个目录**静默变成 0 个扩展，而 `LoadExtensionsResult.errors` 不进 App —— 就是 `docs/known-gaps.md` §F1「扩展加载失败是静默的」的形状；② 设置写入 bug（另一代理在修）改坏了 `settings.json` 的 `extensions` 键；③ 引擎其实跑在 proot 而 UI 以为在 proroot（与"proot 下正常"矛盾） |

**重要澄清（与症状 #3 的关系）**：`defaultTools` 只决定**内建工具**的初始集合
（`pi-src/core/sdk.ts:255-264`，注释明说 extension/custom tools 默认仍启用）。所以：
- 若用户看到的是"只有 `grep`/`find`/`ls` 这三个内建 + 一个 MCP" ⇒ 那是 `defaultTools` 白名单
  （症状 #3，另一代理在修）；
- 若内建工具**齐全**而只有 `android_*` 没了 ⇒ **不是** #3，是本节的问题。

**设备上三步判别**：
1. 诊断报告里「引擎最后一次退出」的 stderr 搜 `extension`（`PiEngineSession` 抓的 stderr 就在那儿）；
2. `设置 → 扩展` 跑一次 `pi list`，与 `~/.pi/agent/extensions` 的目录列表对照；
3. 在**同一个 guest** 里跑
   `node -e "console.log(require('fs').readdirSync('/root/.pi/agent/extensions'))"`
   —— 空 ⇒ proroot 侧 readdir/绑定问题；不空 ⇒ jiti 加载失败（候选①）。

**建议的最小改法（不属任何在飞批次）**：把 `LoadExtensionsResult.errors` 的**条数与首条原文**
显示到「扩展与资源」屏或诊断报告。这一步单独就能把候选① 从"静默"变成"可见"，且不需要动
`runtime/**`。

---

## 4. 启动参数与绑定（`-w` / `-b` / `ensureWorkdir` / rootfs 内目录）

| 项 | 结论 | 依据 |
|---|---|---|
| `-r <rootfs>` 拼写 | **故意保留**（不 canonical 化）：实测 `-r /data/user/0/...` 下 rootfs 内路径全部正确 | 本机 launcher PID 28089 argv + `readlink -f /etc`→`/etc` 等 |
| `-b` 的 host 侧 | **必须 canonical 化**（根因 A） | §1.1 对照实测 |
| 绑定顺序（后覆盖前） | 核过没问题 | `/dev/random` = `1:9` |
| `ensureWorkdir`（`ProrootCommand.kt:260-271`） | 核过没问题，且**刻意不无条件 `mkdirs()`**：只有"有绑定覆盖 cwd 且该绑定的 host 目录存在"才补；`HOST_MISSING` 不兜底，让启动器大声失败 | 类 KDoc 的 126 实测 |
| rootfs 里的替身目录 | A 修好后它仍存在但**不再被使用**（cwd 落在绑定那一侧）。**不要删**：它是 launcher `chdir` 的落点 | 同上 |
| 未验证 | proroot 对"绑定 host 目录不存在"的行为、`-b` 数量上限、ARG_MAX | 研究 §6.6 仍列未测 |

---

## 5. 环境变量与 PATH

| 项 | 结论 | 依据 |
|---|---|---|
| 两套运行时的环境 | **核过没问题**：`GuestRecipe.environment`（`GuestRecipe.kt:185-235`）是唯一共用环境（`NARB_DISABLE_NATIVE_CACHE` 也在里面），`ProrootCommand.environment`（`ProrootCommand.kt:361-370`）只加 4 个 `PROROOT_*`；两套 builder 各自 `put`，**proot 变量不会漏进 proroot** | 代码 |
| `LD_LIBRARY_PATH` | **故意不设**：它只为 proot 的 `libtalloc.so.2` 别名存在（`ProrootCommand.kt:138-140`） | 代码 |
| `PROROOT_NO_SECCOMP` | **故意不设**（v1.2.8 无读取者），并有 harness 断言环境里不存在它 | `RuntimeChoice.ProrootSeccomp` + `ProrootCommand.NO_SECCOMP_ENV` |
| `PATH` / `HOME` | 共享环境给出 `PATH=/opt/pi/bin:/usr/local/sbin:/usr/local/bin:...`、`HOME=/root` | `GuestRecipe.kt:211-215` |
| 未验证 | rootfs 里是否有二进制依赖 `LD_LIBRARY_PATH`（载荷是自洽闭包，理论不需要） | — |

---

## 6. node / pi 可执行路径

| 项 | 结论 | 依据 |
|---|---|---|
| `/opt/node/bin/node`、`/usr/local/bin/node`、`/usr/local/bin/{rg,fd}` | **核过没问题**：proroot 下绝对 guest 符号链接可解析（研究 §7.3 实测；我在本机复测 `readlink -f /root`、`/etc`、`/tmp`、`/dev/fd` 全对） | 实测 |
| 真正的风险 | 不是符号链接，而是**绑定别名**（§1）——`/usr/local/bin/*` 指向 `/root/.pi/agent/bin/*`，那条绑定 host 侧是 `/data/user/0/...` | `PiPaths.agentBinDir` KDoc |
| 引擎入口选择 | `PiEngineHost.kt` 用 **host 侧** `File(rootfs, ...).isFile` 选 bundle/unpacked 入口，与运行时无关 | 代码 |

---

## 7. 终端页（TTY / PTY / 信号 / 退出码）

| 项 | 结论 | 依据 |
|---|---|---|
| PTY 分配 | **实测可用**：`script -qef -c '…' /dev/null` 在本机 proroot 客户机里正常，`tty` → `/dev/pts/0` | 实测 |
| 退出码透传 | **实测可用**：`script -e` 把 `exit 3` 透传成 `script exit=3`；`bash -c 'exit 126'` → 126 | 实测 |
| `stty size` = `0 0` | 与 proot 相同（pty 无初始 winsize）——`PtyLauncher` 用 `stty rows/cols` 钉住 + `COLUMNS`/`LINES`，**不是 proroot 特有问题** | `PtyLauncher.buildGuestCommand` |
| `script(1)` 能力探测 | **故意走 proot**（`PtyLauncher.kt:300-304` 的 KDoc：一次性、缓存、不该为它启动闭源运行时） | 代码 |
| **缺口：终端启动失败不计数、不重试** | `PtyLauncher.start`（`PtyLauncher.kt:236`）取了 `val selection` 却**从未使用**；于是终端这条路径既不 `recordProrootFailure`（3 连击回退不会因它前进），也没有 proot 重试（引擎 `PiEngineHost.kt:712-745` 有、包命令 `GuestCommand.kt:152-174` 有） | 代码 |
| 信号（Ctrl-C / SIGWINCH） | 未验证。proroot 下是真进程 + 真 pty，理论上比 ptrace 转发更自然，但我没有交互式 pty 可测 | — |

**建议修法（终端）**：用那个已存在的 `selection`：把 `PtySession.spawn` 包进 try/catch，
`prepared.engine == Proroot` 失败时 `selection.recordProrootFailure(...)` 并以
`allowProroot = false` 重新 `prepare` 一次（需要给 `prepare` 加一个该参数）。最小、且与引擎/包命令的三层兜底对齐。

---

## 8. 后台进程、超时、孤儿回收

| 项 | 结论 | 依据 |
|---|---|---|
| 三个"正常停止"点 | **已接线**：`PtySession.close`（`PtySession.kt:125-146`）、引擎停止（`PiEngineHost.kt:225`）、包命令超时（`GuestCommand.kt:267-281`）都 capture + `reapInBackground` | 代码 |
| **缺口：proroot 探针不回收** | `ProrootProbe.runGuest`（`ProrootProbe.kt:392-411`）超时只 `destroyForcibly()`，**没有 arm `ProrootLaunchHandle`、没有 reap**。proroot 没有 `--kill-on-exit`，所以**每次探针超时都漏一棵 guest 树**（`bash` + `rg`/`fd`/`node`），而门禁一轮最多 3 个阶段 | 代码 |
| `.proroot-config-*` | 会自愈：下次启用 `plan()` 时 `sweepProrootConfigs`（`RuntimeSelection.kt:341-366`）按 pid 存活清理，并截到上限 | 代码 |
| 探针之外的 `destroyForcibly()` | `RuntimeSelfCheck.kt:138`、`GuestToolProbe.kt:303` 只跑 **proot**（设计如此，proot 有 `--kill-on-exit`）→ 核过没问题 | 代码 |

**建议修法（探针）**：`ProrootProbe.runGuest` 在 `engine == Proroot` 时按
`RuntimeSelection.plan` 的同一纪律 **arm 一个 `ProrootLaunchHandle`（需要把 token 传进来或在
`ProrootProbe.run` 里逐阶段生成）并在超时后 capture + `reapInBackground`**。

---

## 9. 文件权限与 uid

| 项 | 结论 | 依据 |
|---|---|---|
| fake uid 0 | **实测一致**：`id` → `uid=0(root)`；研究 §1.1 同时记录 `/proc/self/status` 仍是 app uid | 实测 + 研究 |
| `chmod` / `setExecutable` | 全在 host 侧（`RuntimeProvisioner`），与运行时无关 | 代码 |
| `libtalloc` 别名 | 只为 proot 建（`PiPaths.prepareLibraryAliases`），proroot 不读 → 核过没问题 | 代码 |
| 未验证 | proroot 下 `chown` "假装成功"的具体返回；`flock`/`fcntl` 是否透传（研究 §4.4 列为未测） | — |

---

## 10. `/sdcard` 与 `/data` 可达性

| 项 | 结论 | 依据 |
|---|---|---|
| `/sdcard`、`/storage/emulated/0` | **核过没问题**：实测 `readlink -f /sdcard`→`/sdcard`、`cd /sdcard && /bin/pwd`→`/sdcard`、`ls .` OK（它的 host 侧本来就是这个拼写） | 实测 |
| `/data` 下的 app 目录 | 可达，但**拼写会泄漏**（根因 A） | §1.1 |
| **`/dev/shm` 未绑**（研究 §4.1 的 ⚠️ 仍未处理） | DSHA 的 launcher argv 实测有 `-b /data/user/0/com.dsh.client/cache/shm:/dev/shm`（PID 28089），我们的共享表（`GuestRecipe.kt:52-64`）没有。`/dev` 是**整目录**绑定，所以 guest 的 `/dev/shm` 是否存在取决于宿主 Android；缺了它，依赖 POSIX 共享内存的东西（Chromium/Playwright、部分原生扩展）会失败 | 实测 argv + 代码 |
| 处置（`/dev/shm`） | **建议修法**：在共享表加一条 `<runtime>/shm:/dev/shm`（目录由 getter 创建）。**我没有改**：这是两套运行时共享的表，正被 `38690fb4` 改 | — |
| 未验证 | 本机 `/dev` 读不到（`ls /dev` → Permission denied），所以宿主是否有 `/dev/shm` 没测 | — |

---

## 11. 开关的切换与回滚

| 项 | 结论 | 依据 |
|---|---|---|
| 打开 | 立即写偏好 + **清失败计数** + **删探针缓存**（否则"缓存里的失败"会让 proroot 永远禁用，重开看起来没反应） | `RuntimeSelection.setProrootEnabled`（`:90-106`）、`ProrootRetry`、`PiPaths.clearProrootProbeCache` |
| "关了没生效" | 开关关闭时 `plan()` **不碰任何 proroot 形状的东西**（连 5 个 `stat` 都不做）——用户明确要求过 | `RuntimeSelection.kt:189-215` |
| "开了没生效" | 设置行与诊断报告读的是 `status()` 的 `summary`（**实际生效** + 原因），不是开关值；报告还打印档位、探针结论、失败计数 | `RuntimeSelection.kt:269-299`、`DiagnosticsReport.kt:318-345` |
| 回滚到 proot | 只关开关即可；在飞 guest 树由各自停止点回收；`.proroot-config-*` 由下次启用时 sweep | 同上 |
| `<rootfs>/.l2s` 里的存量 | proroot 造的是**真硬链接**，切回 proot 不会被清；proot 的 l2s 只用于自己模拟 → 不会坏。**未验证**：切回后 `.l2s` 的存量增长 | 研究 §5.P1-3 |
| 未验证 | 3 连击强制回退在真机上的触发与告知（代码路径清楚，但"告知用户"只有日志 + 设置行 + 诊断报告三个渠道，`recordProrootFailure` 的注释自认没有 toast 通道） | — |

---

## 12. 启动耗时 / 内存 / 二进制失败面

| 项 | 结论 | 依据 |
|---|---|---|
| 门禁成本 | 首次：3 个真实 proroot 调用（raw + rg/fd + 动态二进制），上限 ~60 s，**结果按"解包 revision + 二进制 digest + seccomp 档"缓存**；命中时**零 guest 调用**（`status()` 明说不跑探针） | `RuntimeSelection.gate`（`:388-427`）、`ProrootProbeCache` |
| 主线程特例 | 终端页从主线程启动 ⇒ 无缓存时**后台起探针、本次回退 proot 并说明**（避免 ANR） | `RuntimeSelection.kt:371-423` |
| 性能新债 | 见 A4：canonicalPath 进了每次 spawn 的热路径 | §1.3 |
| `bad bind format`（裸 `-b`） | 已修（`bindArgument` 展开），且 KDoc 记了二进制的地址与原文 | `ProrootCommand.kt:43-73` |
| `chdir workdir failed` + 126 | 已修（`ensureWorkdir`），KDoc 有完整实测 | `ProrootCommand.kt:75-110` |
| `open config file: No such file or directory` | 已对（`PROROOT_TMP_DIR` 是宿主路径） | `ProrootCommand.kt:344-354` |
| 缺 `.so` | `missingProrootComponents()`（进程内缓存）+ fallback ① 自动回 proot | `PiRuntime.kt:234-253` |
| **误报"raw/inline svc 调用没有被翻译"** | **已闭合**：`ProrootRawProbe.parse` 只认 marker 行、launcher 的 `[proroot] …` 走 `launcherLines` 当证据、`ProrootExecProbe.isLaunchFailure` 判 126/127、`ProrootProbeNarrative` 写人话 | `RuntimeChoice.kt`/`ProrootProbeNarrative.kt` |
| SIGSEGV | 无专门处理；走 `autopsyProrootFailure`（`PiEngineHost.kt:161-196`）。**未验证**真机段错误时的取证输出 | — |
| 我们的耗时/内存数字 | **没有**。报告里所有数字（启动快 5~6 倍、tar +94%…）都是 DSHA 的（研究 §2.3），不是本机、不是 app.pi | — |

---

## 13. 未验证清单（明确）

1. **`android_*` 消失的真根因**（§3）——缺引擎 stderr 与 `get_commands` 响应。
2. **技能在 proot vs proroot 的设备对照**（§2）——判别法是推导，不是实测。
3. **宿主是否有 `/dev/shm`**（§10）——本机 `ls /dev` 被拒。
4. **proroot 探针超时漏进程树的真机观测**（§8）——代码缺口确定，泄漏量未测。
5. **3 连击强制回退的真机触发**（§11）。
6. **app.pi 自己的启动耗时与内存**（§12）——一个数字都没有。
7. **`chown` / `flock` / `fcntl` 在 proroot 下的具体行为**（§9）。
8. **信号（Ctrl-C / SIGWINCH）与交互式 pty**（§7）——本机没有交互式 pty。
9. **所有实测都在 DSHA 的 proroot 客户机里**（launcher PID 28089，argv 与我们的不同），属外推。
10. `-r` 之外没有测 `-b` 到不存在目标、`-b` 数量上限、ARG_MAX（研究 §6.6）。

---

## 14. 我改了什么

**没有改任何源码。** 理由逐条：
- 根因 A 的完美修法（§1.3/§1.4）只能落在 `runtime/GuestRecipe.kt` 与
  `runtime/ProrootCommand.kt` —— `38690fb4` 正在写这两个文件，父代明令"避免两个写者"。
- 症状 B（§2）是 A 的**下游**，单独打补丁会把一个根因拆成两个真相。
- 症状 C（§3）缺设备证据，且候选① 的可观测性修法要动设置屏/诊断，属新的产品面。
- `/dev/shm`（§10）与终端重试（§7）、探针回收（§8）都落在 `runtime/**`。

以上每条都给到了"最小修法 + 断言"，需要我落地哪一条，说一声即可；我会按父代的质量要求做
（**唯一裁决点、每条边界一个断言、热路径零新增 IO**）。

---

## 15. 落地记录（本轮，用户裁决「全部落地」之后）

> 本节覆盖 §14「我改了什么」的旧结论（那时一条都没改）。硬规则不变：本地不编译、不提交、不推送。

### 15.1 对账表：每个差异 → 修没修 → 依据 → 断言

| # | 差异（proot 正常 / proroot 坏） | 修没修 | 依据 `file:line` | 断言在哪 |
|---|---|---|---|---|
| A-1 | 相对路径被 `canonicalHost` 用 JVM `user.dir` 拼成宿主绝对路径 | **已修** | `runtime/GuestRecipe.kt` 的 `canonicalHost` ② 分支 | `ProrootCheck.kt`：`a relative host is never resolved against the JVM cwd` 等 3 条 |
| A-2 | `/dev/stdin|stdout|stderr|fd` 被解析成 `/proc/<app pid>/fd/…`（进程相关） | **已修**（排除集扩成 `/proc` 子树 + 4 个别名） | `GuestRecipe.PROC_ALIASED_HOST_PATHS` + `isProcessRelative` | `ProrootCheck.kt`：4 条 `procfs descriptor alias` + `anything under /proc` |
| A-3 | 裸值展开把 **guest 挂载点**一起搬到 canonical 拼写 | **已修**（`canonicalHost(v):v`） | `runtime/ProrootCommand.kt` `bindArgument` | `ProrootCheck.kt`：`expands a bare value without moving the guest side` + guest 侧逐字相等 |
| A-4 | 热路径：每次 spawn 把同一批 `canonicalPath` 算两遍 | **已修**（进程级 memo + `build` 复用 `boundPairs` 一次计算） | `GuestRecipe.canonicalHosts`/`installCanonicalResolverForTest`；`ProrootCommand.build` 的 `val binds = boundPairs(...)` | `ProrootCheck.kt`：cache-hit 后 size 不变、新 host size+1 |
| A-5 | agent/工作区别名绑定 ⇒ **目录遍历（`readdir`）整体失败**，pi 的 walker 静默吞掉 ⇒ 随包扩展 / 技能 / 提示词 / 主题全消失 | **随 A-1~A-4 一起修**（同一个裁决点）。另：把失败**变可见** | `GuestRecipe.canonicalHost`；pi 侧 `loader.ts:739-741`、`package-manager.ts:326-359`；实测见 §16 | `ProrootCheck.kt` 的别名断言（`proroot emits no bind through the alias`）+ 新 `extension-load-errors` harness |
| B-1 | `/dev/shm` 未绑（参考实现绑了） | **已修**（共享表新增，排在 `-b /dev` 之后） | `GuestRecipe.binds`；`PiPaths.shm`（`PiRuntime.kt`） | `ProrootCheck.kt`：4 条 `/dev/shm` 断言（含顺序） |
| C-1 | 扩展加载失败只有 stderr，App 不显示 | **已修**（条数 + 第一条原文进诊断报告；判别三步法写进报告文案） | 新 `packages/PiExtensionLoadErrors.kt`；`ui/settings/DiagnosticsReport.kt`「扩展发现」一节 | 新 harness `extension-load-errors`（已注册 `tools/run-app-pure-checks.sh`） |
| D-1 | 终端启动失败不计数、不重试（那个 `selection` 取了没用） | **已修**（`IOException` → record + `allowProroot=false` 重 prepare 一次） | `runtime/PtyLauncher.kt` `start`/`spawn`/`prepare(allowProroot=)` | 编译期 + 与引擎/包命令同一纪律（无单测：需要真机 spawn 失败） |
| E-1 | proroot 探针超时只杀直接子进程，漏整棵 guest 树 | **已修**（arm handle → 超时先 capture 再 kill → reap；正常路径删 config 表） | `runtime/ProrootProbe.kt` `runGuest` | 编译期（无单测：需要真机超时） |
| E-2 | `ProrootCheck.kt` 里 `val engineCwd` 在同一作用域声明两次（编译不过） | **已修**（第一处改名 `engineGuestCwd`） | `app/src/test/kotlin/app/pi/runtime/ProrootCheck.kt` | 该 harness 本身 |

**每次 spawn 现在算几次 `canonicalPath`**：进程内第一次遇到某个 host 算 1 次，之后每次 spawn 全部命中原 memo ⇒ **稳态 0 次**（此前每次 spawn 约 20 次，同一批算两遍）。`/dev/shm` 只多一个 `mkdirs()`（目录已存在时一次 `mkdir` 返回 EEXIST，与既有 `tmpBind` 同形）。

### 15.2 发现面（skills / prompts / themes / extensions / 命令面板）逐作用域对账

pi 侧的事实来源：`pi-src/core/package-manager.ts:2365-2400`（作用域与目录）与 `:312-395`（walker）、
`pi-src/core/extensions/loader.ts:712-744`（扩展目录）、`pi-src/core/resource-loader.ts:389-470`（信任门槛）。

| 作用域 | pi 扫它吗 | 我们显示它吗 | proroot 下实测能否看见 | 结论 |
|---|---|---|---|---|
| 全局 agent 目录 `<agentDir>/{extensions,skills,prompts,themes}` | 是（`package-manager.ts:2386`） | 是（`WorkspaceResourceScan.scan` 的 `Global` 根） | **修复前不能**（`readdir` 在别名绑定目录里 ENOENT —— §16 实测；walker 静默吞掉）。修复后随 A 一起恢复 | **已修（随 A）** |
| 项目 `<cwd>/.pi/{…}` | 是（`join(projectBaseDir, kind)`） | 是（`ProjectPi` 根） | 同上（工作区绑定也是别名拼写）。另受信任门槛影响，见下一行 | **已修（随 A）** |
| 项目信任门槛（`hasTrustRequiringProjectResources` → `trust.json`） | 是（`resource-loader.ts:389-398`） | 部分（信任卡在设置里） | 键是 `canonicalizePath(cwd)`；cwd 修复前是宿主拼写 ⇒ 与 proot 时期写下的键不一致 ⇒ 项目半边被丢弃。**随 A 一起恢复** | **已修（随 A）** |
| `.agents/skills`（跨工具约定） | 是（`trust-manager.ts:186-198`） | 是（`Agents` 根） | 同上（项目侧） | **已修（随 A）** |
| `settings.packages` 里的包 | 是（`package-manager.ts:2373` 的 overrides + 包树） | 是（`scanPackages`） | **能**（用户自装的 MCP 扩展就是这一路：按路径解析，不需要列目录） | 核过 |
| `settings.extensions`/`skills`/… 的显式路径列表 | 是（同上 overrides） | 是（资源屏的"额外搜索路径"一节） | **能**（`existsSync` 在别名目录里是 true —— §16 实测） | 核过 |
| `additionalExtensionPaths` / CLI `-e` | 是（`resource-loader.ts:436`、`cliExtensionPaths`） | 是（由设置写入 `settings.json` 的路径列表） | **能**（同上，按路径） | 核过 |
| 扩展自己贡献的（`extension_skill_source_infos` 等） | 是 | 是（`Extension` 来源） | 依赖该扩展能否加载 | 核过 |
| MCP 服务器清单 | **不是 pi 原生的**：`pi-src-0861` 里没有 `mcpServers` 字样 | 不在我们这一侧 | 由用户那个扩展自带；它活着 = 至少一个按路径加载的扩展成功 | 核过（归属用户扩展） |

**结论**：发现面的差异只有一处 —— **别名绑定目录里的目录遍历**；它一次性解释了"随包扩展没了、技能/命令面板空了"，而按路径加载的用户扩展不受影响。A 修好后两者都齐。

### 15.3 仍需要设备/ADB 才能验的（本地做不到，列清楚要什么）

| 项 | 需要什么条件 |
|---|---|
| 修复后 proroot 下 `android_*` 与技能/命令面板真的回来 | 装上 app.pi 的真机（`adb` 或 App 内终端），切到 proroot，看 `get_commands` 与设置里「扩展与资源」的计数；本机没有 app.pi 的 rootfs |
| 终端 spawn 失败后的 proot 重试 | 真机上人为让 proroot 启动失败（例如临时撤掉 `libproroot-runtime.so`） |
| proroot 探针超时的树回收 | 真机 + 一次真的超时（把探针超时改小或让 guest 卡住） |
| `/dev/shm` 是否被 Android 提供 | 宿主 `/dev` 的读取权限（本机 `ls /dev` 被拒） |
| 信号 / SIGWINCH / 交互式 pty | 真机终端 |
| `chown`/`flock`/`fcntl` 在 proroot 下的行为 | 真机 |
| 启动耗时 / 内存的**我们自己的**数字 | 真机 |

---

## 16. 「随包扩展在 proroot 下消失」的定论（本节取代 §3 的"未确认"）

用户补充的事实：`mcp`/`mcpScript` 来自**他自己安装的一个扩展** ⇒ proroot 下**至少有一个扩展成功加载**，
而随包的 `android_*` 全没了。三条候选逐一核清：

**实测（本机 proroot 客户机，别名绑定目录 `/dev/shm`，Node v24.19.0）**：

```
$ node -e "console.log(require('fs').existsSync('/dev/shm'))"     -> true
$ node -e "console.log(require('fs').readdirSync('/dev/shm'))"    -> ENOENT: scandir '/dev/shm'
$ node -e "const d=require('fs').opendirSync('/dev/shm'); ..."    -> 0 项，且不报错
$ node -e "process.chdir('/dev/shm'); require('fs').readdirSync('.')" -> ENOENT
# 对照：同一 guest 里 `/sdcard`（绑定 host 侧 = 内核拼写）四项全部正常
```

**这三条候选的结论**：

1. **位置/机制不同 —— 成立，而且是主因。** 随包扩展是**自动发现目录**：pi 走
   `package-manager.ts:2386` 的 `userDirs.extensions = join(agentDir, "extensions")`，再用
   `collectFiles`（`:312-359`）**列目录**；`loader.ts:712-744` 的 `discoverExtensionsInDir` 同样。
   用户那个扩展是**按路径命名**的（`settings.json` 的 `extensions`/`packages`，`package-manager.ts:2373`
   的 overrides），解析只用 `existsSync`/`stat` —— 而那两项在别名目录里是 **true**（实测）。
   于是：**目录遍历失败 ⇒ 只有"靠列目录发现"的那一半消失**。
2. **模块加载抛错被吞 —— 不是本因。** 不需要它就能解释：失败发生在**列目录**这一层，而
   `collectFiles` 的 `catch { // Ignore errors }`（`:359`）与 `discoverExtensionsInDir` 的
   `catch { return [] }`（`loader.ts:739-741`）把异常吃掉了，连 `LoadExtensionsResult.errors`
   都不会有一条。模块加载失败那一半仍值得可见（见 §15 的 C-1），但它不是这次的原因。
3. **作用域（全局 vs 项目）—— 是叠加项，不是主因。** 项目 `.pi/*` 与 `.agents/*` 也会因为同一个
   别名绑定而列不出来（工作区绑定同样是 `/data/user/0/…` 拼写），所以项目半边**额外**少了；
   但"只剩一个用户扩展"这件事只能由候选 1 解释。

**根因一句话**：`android_*` 不是"没加载"，是 **pi 列不出那个目录**；而列不出来是 §1 的绑定别名问题在
`readdir` 上的表现 —— **proot 没有反向前缀映射，所以 proot 侧一直正常**。A 修好即恢复。
