# proroot 下 pi 列不了目录：`scandir(3)`

> proroot 运行时的一个缺陷，以及本仓库在 Node 侧的修法。
> 状态：**根因已定位、修法已实现并在设备上验证通过**（2026-09-23：原本失败的 5 条路径
> 全部恢复，其余 11 条读数逐条不变）。
> 取代 `proroot-bind-drop-defect.md`（已删：那一版把现象读成"绑定被静默丢掉"，根因是错的）。
> 上游：[coderredlab/proroot#25](https://github.com/coderredlab/proroot/issues/25)。
>
> **2026-09-23 后续（读 §3 之前先看这条）**：§3.1 那 5 条失败路径里，**有 3 条已经不是绑定
> 了** —— `/tmp` 的绑定被删掉，pi 的 agent 目录与工作区搬进了 rootfs（`PiPaths.agentDir` /
> `PiPaths.workspaces`，各自的 KDoc 有理由）。所以现在全表**唯一**的应用私有绑定是
> `<rootfs>/dev-shm → /dev/shm`，而没人列它。本文档剩下的价值是两件：**根因**（坏的是
> proroot 自己导出的 `scandir` 实现，不是"漏 hook"），以及 `scandir-fix.mjs` 这个兜底
> **为什么还留着** —— 它是"先调原实现、失败才接管"，`scandir` 正常时是纯 no-op，而终端的
> `/workspace` 绑定仍在，删它得先上机验。

---

## 1. 结论

proroot 的 **`scandir(3)` hook 对它自己重写的路径返回 `ENOENT`**，而**同一条路径上
`opendir(3)` + `readdir(3)` 是好的**。

Node 的 `fs.readdirSync` / `fs.readdir` 恰好经 libuv `uv_fs_scandir` 落到 **`scandir(3)`**；
`bash` 的 `ls`/`find`/`grep` 走的是 `opendir` + `readdir`。于是**同一个目录，bash 能列、
Node 列不了**。

pi 进程内所有列目录都走 `fs.readdir*`，所以它们一起坏——这不是巧合，是同一个函数。

---

## 2. 根因

### 2.1 libc 侧：proroot 导出了 `scandir`，那条实现是坏的

对钉住的 v1.2.8 `libproroot-runtime.so`
（sha256 `8c47a0a7db32d84c179ebb5bf3640f655a3181860ece5886ae44d92858730c34`）
跑 `readelf --dyn-syms` 实测：

| 符号 | proroot 是否导出 | 设备上的表现 |
|---|---|---|
| **`opendir`** | **导出** | ✅ 好 |
| **`scandir` / `scandir64`** | **导出** | ❌ **坏** |
| `readdir` / `readdir64` / `closedir` / `fdopendir` / `getdents64` | 不导出（走 libc） | ✅ 好 |
| `open` / `openat` / `stat` / `stat64` / `readlink` / `realpath` | 导出 | ✅ 好 |

**不是"漏 hook"，是"hook 了但那条实现有 bug"。** 兜底之所以成立，正因为 `opendir` 是它
自己实现的、路径翻译正确，后面接的 `readdir` 是 libc 的、也好。

### 2.2 Node 侧：`fs.readdir*` 走的正是坏的那条

- `fs.readdirSync` / `fs.readdir` → libuv `uv__fs_scandir()` → libc **`scandir64`**
  （`libuv/src/unix/fs.c`，PLT 调用，不是内联 syscall）。
- **libc 的 `readdir(3)` 好，不代表 Node 的 `fs.readdir` 好**——两个同名的东西落在两条
  不同的实现上。这是整个问题里最容易读错的一处。

### 2.3 pi 侧：28 处调用，一个症状

`packages/coding-agent/src` 里 `readdirSync` / `readdir` 共 **28 处**：

| 功能 | 位置 |
|---|---|
| `ls` 工具 | `core/tools/ls.ts`（`ops.readdir` 默认 `fs.readdir`） |
| 扩展发现 | `core/extensions/loader.ts:732` |
| 技能 | `core/skills.ts:192` |
| 提示词模板 | `core/prompt-templates.ts:146` |
| 主题 / 资源 | `core/resource-loader.ts:913` |
| 包管理 | `core/package-manager.ts`（5 处） |
| 会话列表 | `core/session-manager.ts:640` |
| 迁移 | `migrations.ts:90` |

**用户看到的"`ls` 失败 / 命令面板缺项 / 扩展不加载"是同一处根因的三张脸。**

---

## 3. 症状与范围（设备实测）

### 3.1 失败路径 = proroot 的绑定挂载点

对 16 条路径逐条跑「`ls` 工具 / `bash` 数条数 / `test -d` / `grep` / `find`」：

| 路径 | `ls` | bash 条数 | `test -d` | `grep` | `find` |
|---|---|---|---|---|---|
| `/` | OK | 27 | DIR_OK | OK | OK |
| `/etc` | OK | 92 | DIR_OK | OK | OK |
| `/usr` | OK | 9 | DIR_OK | OK | OK |
| `/opt/pi` | OK | 4 | DIR_OK | OK | OK |
| `/opt/pi/node_modules` | OK | 3 | DIR_OK | OK | OK |
| `/root` | OK | 16 | DIR_OK | OK | OK |
| `/root/.pi` | OK | 5 | DIR_OK | OK | OK |
| **`/root/.pi/agent`** | **FAIL ENOENT** | 17 | DIR_OK | OK | OK |
| **`/root/.pi/agent/extensions`** | **FAIL ENOENT** | 9 | DIR_OK | OK | OK |
| `/workspace` | OK | 3 | DIR_OK | OK | OK |
| `/workspace/pi` | OK | 1 | DIR_OK | OK | OK |
| `/workspace/pi/workspaces` | OK | 1 | DIR_OK | OK | OK |
| **`/tmp`** | **FAIL ENOENT** | 300 | DIR_OK | OK | OK |
| **`/dev/shm`** | **FAIL ENOENT** | 0 | DIR_OK | OK | OK |
| `/sdcard` | OK | 49 | DIR_OK | OK | OK |
| **`/workspace/pi/workspaces/workspace-1`** | **FAIL ENOENT** | 7 | DIR_OK | OK | OK |

四条读法：

1. **失败的 5 条全部是 proroot 的绑定挂载点。** 分界线很准：`/root/.pi` 好而
   `/root/.pi/agent` 坏；`/workspace/pi/workspaces` 好而它下面那一层坏。
2. **`test -d` 全绿** → 目录真存在。是"看不见内容"，不是"目录不在"。
3. **`bash` 数条数在那 5 条上全部成功** → **子进程那条路是好的**。
4. **`grep` / `find` 在全部 16 条上都 OK**（走独立二进制），**`read` 全部 OK**（不走枚举）。

### 3.2 地基：`opendir` 在失败路径上可用，修好后三列逐条相等

同一台设备，修前 / 修后各测一次 `fs.opendirSync` 与 `fs.readdirSync`，右侧是独立的
第三方参照 `/bin/ls`（走 libc 的 `opendir`+`readdir`，从头到尾没坏过）：

| 路径 | `fs.opendirSync` | `fs.readdirSync` 修前 | `fs.readdirSync` 修后 | `/bin/ls` |
|---|---|---|---|---|
| `/root/.pi/agent` | 17 | ERR:ENOENT | **17** | 17 |
| `/root/.pi/agent/extensions` | 9 | ERR:ENOENT | **9** | 9 |
| `/tmp` | 300 | ERR:ENOENT | **300** | 300 |
| `/dev/shm` | 0 | ERR:ENOENT | **0** | 0 |
| `/workspace/pi/workspaces/workspace-1` | 7 | ERR:ENOENT | **7** | 7 |

两条读法：

1. **`opendir` 给出的条目与 `/bin/ls` 逐条一致**——所以换过去得到的是**完整**列表，
   不是残缺列表。这是修法的全部依据。
2. **修后 pi 用的 `readdirSync` 与 `/bin/ls` 逐条相等**——即 **pi 拿到的东西和 bash
   拿到的完全一样**。`/dev/shm` 那一行尤其说明问题：修前它报 ENOENT（pi 以为"目录不存在"），
   修后是 `0`（pi 正确知道"目录存在但为空"）。


---

## 4. 修法

**不动 proroot，也不动 pi 的代码。**

### 4.1 模块

`app/src/main/assets/guest/scandir-fix.mjs` → 客机 `/opt/pi/scandir-fix.mjs`。

把 `fs.readdirSync` / `fs.readdir` 包一层，规则只有两条：

```
先照常调用原来的实现
  ├─ 成功                                   → 原样返回
  ├─ 抛 ENOENT 且 statSync 说目录确实存在     → 记下这条路径，改用 opendir + read 列一遍
  └─ 其它错误                               → 原样抛出
```

四条设计约束，每条都有理由：

- **必须用 `createRequire` 拿 `node:fs`，不能在 ESM 里 `import "node:fs"`。**
  Node 在第一次 import 时就把具名导出**快照**了，之后再改打不中。本机实测：ESM 写法下
  `import * as fs` 打不中；CJS 写法下 `import * as fs` 与 `import { readdir }` **都打中**。
- **只在原来失败时才接管** → `scandir` 正常的环境里，代码路径与结果与改动前**完全一致**。
- **每条路径只白失败一次。** 第一次在某个路径上遇到 `ENOENT`（且 `stat` 说目录存在）就把它
  记进一个有上限（512）的内存集合，之后对该路径**直接走 `opendir`**。没有这一层，连续列
  同一个目录 N 次就是 N 次注定失败的调用。
- **真不存在的目录仍然抛 ENOENT**（`statSync` 也失败 → 不兜），错误不被吞掉。

### 4.2 注入

只有一处，且**只在 proroot 的环境里**（`ProrootCommand.environment()`）：

```kotlin
if (paths.scandirFix().isFile) {
    put("NODE_OPTIONS", "--import=$SCANDIR_FIX_GUEST_PATH")
}
```

- **只在 proroot** → `proot` 路径不设这个变量，连模块都不加载。
- **判文件在不在** → `--import` 指到不存在的文件是**硬失败**（node 起不来），不是警告。

### 4.3 落地

`PiEngineHost` 每次 boot 从 assets 写回 `<rootfs>/opt/pi/scandir-fix.mjs`
（写临时文件再 rename，避免半个模块被 `--import` 读到）。写在易失树里，所以显式修复路径
删掉它之后下次 boot 会补回来。写入失败记在 `PiEngineHost.lastScandirFix` 上。

**为什么不放进 pi 的载荷**：那要改 `tools/fetch-runtime.mjs` 与 revision，而这里只是
一个文件写入。**为什么不放进 agent 目录**：那是绑定路径——修复不能依赖它要修的东西。

---

## 5. 边界

### 5.1 不影响升级

- **pi 的包一个字节没改**：没打补丁、没改 dist、没改 `node_modules`。
- 只碰 **Node 内建的 `fs`**，不认任何 pi 的源码行；上游发新版照样调 `fs.readdir*`，照样被覆盖。
- 升级流程不变：改 `PI_VERSION` → 重跑契约检查 → 重新生成载荷。
- 这与 `Aether` 那条"构建期字符串替换 patch pi 的 dist 源码"是**完全不同的两条路**。

### 5.2 不影响 proot

两层独立保证：

1. **模块根本不会被加载**——`NODE_OPTIONS` 只写在 `ProrootCommand.environment()`，proot 走
   `ProotCommand.environment()`，那个变量不存在。
2. **就算加载了也不触发**——兜底只在原实现失败时接管；proot 下 `scandir` 正常，兜底一次都不执行。

proroot 出任何问题的回退就是**关掉开关**，不需要回滚代码。

### 5.3 性能

| 情况 | 成本 |
|---|---|
| `scandir` 正常的路径 | **零变化**（走原实现） |
| 坏路径，第一次遇到 | 一次失败的 syscall + 一次 `statSync` ≈ 微秒级 |
| 坏路径，之后 | **零额外**（直接 `opendir`，与 `/bin/ls` 同一条路） |

`scandir` 本身就是 `opendir`+`readdir`+分配的封装，两者同量级。

### 5.4 用户可见行为

- **工具卡只有一张，而且是成功的那张。** 那次内部失败被包在同一个函数体里，**不外泄**；
  pi 的工具只被调用一次，拿到的是数组。
- **资源在引擎启动时就被发现好了**，命令面板只是显示那份已建好的列表——所以**第一次打开
  就是完整的**，不需要用户先触发任何失败。
- 兜底本身失败时（例如 `opendir` 在那条路径上也不行），错误照常冒到 pi，
  结果与改动前**一样是一张失败卡**，不会更糟。

---

## 6. 验证状态

**已验**

- `readelf --dyn-syms`：proroot 导出 `opendir`/`scandir`/`scandir64`，不导出
  `readdir`/`closedir`/`getdents64`。
- 设备实测：§3.1 的 16×5 表、§3.2 的 `opendirSync` vs `readdirSync` vs `/bin/ls` 表。
- 本机 Node 实验：`--import` + `createRequire` 的写法对 `import * as fs` 与具名 import
  **都生效**，ESM 写法**不生效**；兜底在模拟 `scandir` 失败后同步/异步都正确接管；
  `withFileTypes` 仍返回 Dirent；真缺目录仍抛 ENOENT；`scandir` 正常时结果与原实现逐条一致；
  记忆生效（第二次调用不再试坏的）。
- `tools/typecheck.sh`：**0 error diagnostics**（`:rpc` 0 / `:app` 0）。
- `build/run-proroot-harness.sh`：唯一失败是既有的
  `both runtimes bind external storage twice`——成因是本容器里 `/storage/emulated/0` 是指向
  `/sdcard` 的符号链接，而 Android 上方向相反；改动前后逐条一致。

**已上机验证（2026-09-23，同一台设备）**

装上带本改动的包、**开着 proroot** 重跑 `docs/proroot-scandir-probe.md` 的普查：

| 路径 | 修前 | 修后 | `bash` 条数（修后） |
|---|---|---|---|
| `/root/.pi/agent` | FAIL ENOENT | **OK** | 17 |
| `/root/.pi/agent/extensions` | FAIL ENOENT | **OK** | 9 |
| `/tmp` | FAIL ENOENT | **OK** | 300 |
| `/dev/shm` | FAIL ENOENT | **OK** | 0 |
| `/workspace/pi/workspaces/workspace-1` | FAIL ENOENT | **OK** | 7 |

- **其余 11 条路径的读数逐条与修前一致**——不是"顺手改了别的"，也没有把本来好的换掉。
- 表 1 为 `NODE_OPTIONS=--import=/opt/pi/scandir-fix.mjs` 且文件存在 → 接线到位。
- 唯一数字变化是 `/opt/pi` 的条数 4 → **5**：多出来的就是本模块自己，
  即"文件确实落在设计的位置"。`read` 四项全部无变化。
- **归因**：修前那一版（只含环境变量与绑定顺序的改动）在同一台设备上**仍然失败**，
  所以这次恢复来自本模块，不是别的改动。

**仍未确认**

- **界面层**：扩展、技能、主题、提示词、命令面板是否完整显示。`ls` 通了是它们的
  必要条件（同一批 `readdirSync` 调用点），但仍应看一眼实际界面。
- 若个别路径上 `opendir` 也不可用（本次未出现），按 §7 第二条加一级兜底。


---

## 7. 可改之处

- **不再需要"每条路径白失败一次"**：可在模块加载时先探测（拿当前工作目录试一次
  `readdirSync`），坏了就全局切换、之后一律直接 `opendir`。**代价是这个探测依赖"所探路径
  恰好是绑定路径"这一假设**——不对就误判成"环境正常"，兜底形同虚设且不报错。当前实现
  不猜：哪条路径真坏，是在它真坏的那一刻才知道的。
- **若 `opendir` 在个别路径上也不行**：兜底可再加一级走子进程 `/bin/ls`
  （§3.1 已证明它在全部 5 条坏路径上成功）。当前实测不需要，所以没加。
- **若 `--import` 在某些构建下不稳**：换 `--require` 或 `module.registerHooks`，注入点不变。
- **上游若修了 `scandir`**：本模块退化为纯 no-op（先调原实现，成功即返回），可原样留着，
  也可连同注入一起删掉。
