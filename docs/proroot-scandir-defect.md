# proroot 的 `scandir(3)` 让 pi 列不了目录

> 本文取代 `proroot-bind-drop-defect.md`（已删）。那一版把现象读成了"proroot 静默丢掉
> `-b` 绑定"，并据此得出"改不了、不要改代码"。**那个根因判断是错的**，修法也就跟着错了。
> 下面是设备实测 + 二进制符号表 + 本机 Node 实验三方对出来的结论。
>
> 状态：**已定位、已修（Node 侧兜底）、待设备验证**。
> 相关：上游 [issue #25](https://github.com/coderredlab/proroot/issues/25)（诉求仍然成立，但已不是修复路径）。

---

## 1. 一句话结论

proroot 的 **`scandir(3)` hook 对它自己重写的路径返回 `ENOENT`**，而同一条路径上
`opendir(3)` + `readdir(3)` **是好的**。

Node 的 `fs.readdirSync` / `fs.readdir` 恰好是**用 `scandir(3)` 实现的**
（libuv `uv_fs_scandir` → libc `scandir64`），而 `bash` 的 `ls`/`find`/`grep` 用的是
`opendir` + `readdir`。**于是同一个目录：bash 能列，Node 列不了。**

pi 的进程内列目录**全部**走 `fs.readdir*`——`ls` 工具、扩展加载器、技能、主题、提示词、
命令面板、会话列表——所以它们一起坏，而终端里手敲 `ls` 一直是好的。

---

## 2. 设备实测（用户做的对比，把范围钉死了）

判据：**比较「guest 里看到的 inode」与「真实 rootfs 里的 inode」，两者不同的路径，正好就是
`ls` 报 ENOENT 的路径。**

| 路径 | guest inode | rootfs inode | `ls` 工具 |
|---|---|---|---|
| `/`、`/etc`、`/opt`、`/usr`、`/data` | 相同 | 相同 | ✅ |
| `/root`、`/root/.pi` | 相同 | 相同 | ✅ |
| `/workspace`、`/workspace/pi`、`/workspace/pi/workspaces` | 相同 | 相同 | ✅ |
| `/opt/pi/node_modules` | — | — | ✅ |
| `/sdcard`（真实内核挂载） | 不同 | 不同 | ✅ |
| `/tmp` | 2795720 | 2795917 | ❌ ENOENT |
| `/workspace/pi/workspaces/workspace-1`（当时的 cwd） | 4850950 | 4878829 | ❌ ENOENT |
| `/root/.pi/agent` | 4738345 | 4816727 | ❌ ENOENT |
| `/dev/shm` | 存在 | rootfs 里不存在 | ❌ ENOENT |

**触发条件不是"设备号不同"**（`/sdcard` 设备号也不同，但它能列），而是
**proroot 自己做路径重写的那一类挂载点**。

两条旁证，决定了根因的方向：

1. **错误形态是 `Cannot read directory: ENOENT … scandir`**，而路径真不存在时报的是另一种
   ——`Path not found`。说明这些目录在 `ls` 工具视角里 **`stat` 成功、`readdir`（scandir）失败**。
2. **同一批路径上别的工具都正常**：`read` 能读到文件，`grep`/`find` 能列出内容
   （它们走独立二进制 + `opendir`/`getdents`）。

---

## 3. 根因：三个事实叠在一起

### 3.1 libc 侧：proroot 导出了 `scandir`，它的实现是坏的

对仓库里钉住的 v1.2.8 `libproroot-runtime.so`
（sha256 `8c47a0a7db32d84c179ebb5bf3640f655a3181860ece5886ae44d92858730c34`）跑
`readelf --dyn-syms` 实测：

| 符号 | proroot 是否导出 | 该路径在设备上 |
|---|---|---|
| **`opendir`** | **导出** | ✅ 好 |
| **`scandir` / `scandir64`** | **导出** | ❌ **坏** |
| `readdir` / `readdir64` / `closedir` / `fdopendir` / `getdents64` | **不导出**（走 libc） | ✅ 好 |
| `open` / `openat` / `stat` / `stat64` / `readlink` / `realpath` | 导出 | ✅ 好 |

**所以这不是"漏 hook"，是"hook 了但那条实现有 bug"。** 而兜底方案之所以成立，
正是因为 `opendir` 是它自己实现的、路径翻译正确，后面接的 `readdir` 是 libc 的、也好。

### 3.2 Node 侧：`fs.readdir*` 走的正是坏的那条

- `fs.readdirSync` / `fs.readdir` → libuv `uv__fs_scandir()` → libc **`scandir64`**
  （`libuv/src/unix/fs.c`，PLT 调用，不是内联 syscall）。
- 不是 `opendir` 那条路——所以 libc `readdir(3)` 好，不代表 Node 的 `fs.readdir` 好。
  **两个同名的东西，落在两条不同的实现上。**

### 3.3 pi 侧：28 处调用，一个症状

`packages/coding-agent/src` 里 `readdirSync` / `readdir` 共 **28 处**，覆盖：

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

**用户看到的三个症状（`ls` 失败、命令面板缺项、扩展不加载）是同一处根因的三张脸。**

---

## 4. 上一版文档错在哪（避免后人重走）

| 上一版的判断 | 实际 |
|---|---|
| "proroot **静默丢掉 `-b` 绑定**" | 绑定**没丢**：`stat` 成功、bash 能列、inode 只在重写路径上不同。丢的只是 `scandir` 这一条实现。 |
| 分成"症状 A（只有枚举坏）"和"症状 B（整条绑定没生效）"两个根因 | 只有**一个**：`scandir` 在重写路径上失败。所谓"绑定丢了"是把它在 `catch { return [] }` 下游的表现误当成了因。 |
| "要修就得 patch proroot / 上 LD_PRELOAD shim / 物化进 rootfs" | 都不必。**修在 Node 侧，不碰 proroot。** |
| "前提是 shim 能排到 proroot 之前，实测排不到 → 不可行" | 位置问题本身不成立：我们要的正是**它翻译之后**那一层。 |
| "不改代码，维持现状" | 现在有改动更小、不依赖上游的修法。 |

上一版里仍然有效的部分：§6 的环境限制（本机那五个 `.so` 是另一条 lineage、无法本地换版做 A/B）、
§7 提给上游的两条诉求（`PROROOT_VERBOSE` 下打印绑定表、绑定失效时非零退出）。

---

## 5. 修法：Node 侧兜底，只对 proroot 注入

**不动 proroot，不动 pi 的代码。**

### 5.1 模块

`app/src/main/assets/guest/scandir-fix.mjs` → 客机 `/opt/pi/scandir-fix.mjs`。

它把 `fs.readdirSync` / `fs.readdir` 包一层：

```
先照常调用原来的实现
  ├─ 成功                                   → 原样返回
  ├─ 抛 ENOENT 且 statSync 说目录确实存在     → 改用 opendir + read 列一遍
  └─ 其它错误                               → 原样抛出
```

三条设计约束，每条都有理由：

- **必须用 `createRequire` 拿 `node:fs`，不能在 ESM 里 `import "node:fs"`。**
  Node 在第一次 import 时就把具名导出**快照**了，之后再改打不中。本机实测：
  ESM 写法下 `import * as fs` 打不中；CJS 写法下 `import * as fs` 与
  `import { readdir }` **都打中**。
- **只在原来失败时才接管**，所以 `scandir` 正常的环境（proot）里，代码路径与结果与今天**完全一致**。
- **真不存在的目录仍然抛 ENOENT**（`statSync` 也失败 → 不兜），不会把错误吞掉。

### 5.2 注入

只写在 `ProrootCommand.environment()` 里：

```kotlin
if (paths.scandirFix().isFile) {
    put("NODE_OPTIONS", "--import=$SCANDIR_FIX_GUEST_PATH")
}
```

- **只在 proroot 的环境里** → proot 路径连模块都不加载。**proroot 出任何问题的回退就是
  关掉开关，不需要回滚代码。**
- **必须判文件在不在**：`--import` 指到不存在的文件是**硬失败**（node 起不来），不是警告。

### 5.3 落地

`PiEngineHost` 每次 boot 从 assets 写回 `<rootfs>/opt/pi/scandir-fix.mjs`（写临时文件再 rename，
避免半个模块被 `--import` 读到）。写在易失树里，所以显式修复路径删掉它之后下次 boot 会补回来。

**为什么不放进 pi 的载荷**：那样要改 `tools/fetch-runtime.mjs` 与 revision，而这一步只是
一个文件写入。**为什么不放进 agent 目录**：那是绑定路径——修复不能依赖它要修的东西。

### 5.4 它为什么不影响升级

- **pi 的包一个字节没改**：没打补丁、没改 dist、没改 `node_modules`
- 只碰 **Node 内建的 `fs`**，不认任何 pi 的源码行
- 上游发新版 → 新版照样调 `fs.readdir*` → 照样被覆盖
- 升级流程不变：改 `PI_VERSION` → 重跑契约检查 → 重新生成载荷

---

## 6. 验证状态（分清验过与没验）

**已验（本机实测）**

- `readelf --dyn-syms`：proroot 导出 `opendir`/`scandir`/`scandir64`，不导出 `readdir`/`closedir`/`getdents64`。
- Node 实验：`--import` + `createRequire` 的写法对 `import * as fs` 与具名 import **都生效**；
  ESM 写法**不生效**。
- 兜底逻辑：模拟 `scandir` 抛 ENOENT 后，同步/异步两条都正确兜回；`withFileTypes` 仍返回 Dirent；
  真缺目录仍抛 ENOENT；`scandir` 正常时结果与原实现逐条一致。
- pi 侧 28 处调用点全部经 `fs.readdir*`。

**没验（必须在设备上做）**

1. **`opendir` 在那些失败的绑定路径上确实能工作。** 这是整个兜底的前提。
   一行可验（在 app 的终端里跑）：
   ```bash
   node -e 'const fs=require("fs");const d="/workspace/pi/workspaces/workspace-1";
   try{const h=fs.opendirSync(d);let n=0;while(h.readSync())n++;h.closeSync();console.log("opendir OK",n)}catch(e){console.log("opendir FAIL",e.code)};
   try{console.log("readdirSync OK",fs.readdirSync(d).length)}catch(e){console.log("readdirSync FAIL",e.code)}'
   ```
   期望 `opendir OK`（条数>0）+ `readdirSync FAIL ENOENT`。**若两个都 FAIL**，兜底的前提不成立，
   要改成走子进程 `/bin/ls`（那条已实测是好的）。
2. **修完之后 `ls` / 命令面板 / 扩展是否真的恢复。**
3. **`--import` 在这个 proroot 客户机里是否正常加载**（路径可达、Node 版本支持）。

---

## 7. 仍然可能改的地方

- 若前提 1 不成立 → 换成子进程 `/bin/ls` 兜底（同一注入点，只改模块）。
- 若 `--import` 在某些构建下不稳 → 换成 `--require` 或 `module.registerHooks`。
- 上游若哪天修了 `scandir` → 这个模块变成纯 no-op（它先调原实现，成功就返回），**可以原样留着**，
  也可以连同注入一起删掉。
- 终端里手敲 `pi` 的那条路径同样经 `ProrootCommand.environment()`，所以自动覆盖。
