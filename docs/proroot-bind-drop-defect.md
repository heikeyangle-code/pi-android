# proroot 会静默丢掉 `-b` 绑定

> 本文只讲这一条缺陷。范围：proroot 运行时下 `-b` 绑定整条不生效、或被丢弃时
> 没有任何诊断，导致所有"枚举类"功能缺东西或时好时坏。
>
> 状态：**已定位、已提上游、当前决定不改代码**（见 §8 末尾）。
> 相关：上游 [issue #25](https://github.com/coderredlab/proroot/issues/25)。

---

## 1. 一句话结论

proroot 在某些启动里会**静默丢掉 `-b` 绑定**：该绑定对应的 guest 目录上所有操作返回
`ENOENT`，而进程**不打印任何东西、不写任何日志、退出码仍是 0**。于是所有依赖绑定目录的
"枚举类"功能——会话列表、技能、提示模板、主题、扩展、斜杠命令面板、`ls`——会**缺东西或
时好时坏**，而用户与 App 都拿不到任何错误，只能看到一个"数据形状"的故障（东西自己不见了）。

同一台设备上 **proot 路径一切正常**，所以我们一直拿它当基线。

---

## 2. 用户可见症状

- proroot 下斜杠命令面板少条目、技能/主题列表缺项。
- `ls` 有的目录有输出、有的目录直接空；`readdir` 类调用报 `ENOENT`。
- 同一个目录、同一条命令，**重跑一次就好了**——所以很难被当成 bug 报告，容易被读成
  "这台机器上本来就没有那些资源"。
- 切回 proot，症状消失。

发现路径：命令面板缺东西 → 逐层下探到 Node 的 `fs.readdirSync` → 再下探到 libc `scandir`。

---

## 3. 两个症状要分开

两者**根因不同、修法不同**，混在一起会得出错误结论。

### 症状 A —— 只有"枚举"坏，别的都好（设备实测）

在**绑定挂载的目录**里：

| 操作 | 结果 |
|---|---|
| `fs.readdirSync` / `fs.readdir`（含 `withFileTypes`） | **ENOENT** |
| `fs.opendirSync` + `readSync` | 正常 |
| `fs.statSync` | 正常 |
| `/bin/ls` | 正常 |
| 读 / 写 / 搜索 / 执行 | 正常 |
| rootfs 内目录（如 `/opt/pi`） | 正常 |

即：**同一个目录，`opendir` 好、`scandir` 坏**。

> **A 在本机未复现。** 本开发容器本身就是一个 proroot 客户机，实测
> `/root/.pi/agent`、`/sdcard`、`/system`、`/tmp`、`/etc`、`/usr/lib` 六个目录，
> `readdirSync` 与 `opendirSync` **全部正常**且条数一致（见 §4 的探针脚本）。
> 所以 A 只能在出问题的那台设备上验证。

### 症状 B —— 整条绑定没生效（本机受控实测）

同一条 argv 反复启动，有时**某一个（或几个）绑定整条不生效**。此时该目录上
**所有**操作都 `ENOENT`——不只是枚举：

`readdirSync`、`readdirSync(p,{withFileTypes:true})`、`opendirSync`+`readSync`、
`statSync`、`readlinkSync`、`openSync`、`writeFileSync`、`/bin/ls`、`/usr/bin/find`
**全部 ENOENT**；同一进程里其它绑定和 rootfs 路径照常工作（guest 本身是健康的）。

观察到的形态（12 条绑定探针，同一 argv 约 70 次启动）：

```
YYYYYYYYYYYY      ← 全好
YYYYYY......      ← 后 6 条没生效
YYY.........      ← 只有前 3 条生效
............      ← 全部没生效
```

最关键的一次：一条 6 绑定调用出现过 `YYY.........`，几分钟后用**逐字节相同**的 argv
连跑 5 次全是 `YYYYYY......`。所以行为是间歇的，**不由命令行决定**。

**零警告、零日志、退出码 0。** App 无法区分"绑定被丢了"和"这台机器确实没有这个目录"。

放大后果的是下游：pi 自己的扩展加载器、会话/技能/主题列表里有大量
`catch {}`（例如扩展加载器是显式的 `catch { return [] }`），于是"绑定丢了"被呈现成
"**我的技能不见了**""**命令面板少了一半**"。

---

## 4. 机理

### 4.1 调用链（源码级）

- `fs.readdirSync` / `fs.readdir` → libuv `uv__fs_scandir()` → libc **`scandir()`**
  （`libuv/src/unix/fs.c`，**PLT 调用，不是内联 syscall**）。
- `fs.opendirSync` → libuv `uv__fs_opendir()` → `opendir()`。
- Node 侧入口是 `src/node_file.cc` 的 `ReadDir`（用 `uv_fs_scandir`）。

64 位 glibc 上 `scandir` 与 `scandir64` 是 `libc.so.6` 里同一个地址的别名；**libuv 实际
绑定的是 `scandir64`**。本机实测（只让 `scandir64` 返回 `ENOENT`、`scandir` 不动）：

```
readdirSync FAILED ENOENT      ← 证明 readdirSync 走 scandir64
opendirSync 120                ← opendir 路径未受影响
```

### 4.2 本机运行的那份 proroot 到底 hook 了什么（实测符号供给表）

在本 proroot 客户机里用 `dlsym(RTLD_DEFAULT, …)` + `dladdr` 看每个符号由谁提供：

| 符号 | 提供者 |
|---|---|
| `scandir` / `scandir64` / `opendir` | **libproroot-runtime.so** |
| `scandirat` / `scandirat64` | libc.so.6（未 hook） |
| `readdir` / `readdir64` / `closedir` / `fdopendir` | libc.so.6（未 hook） |
| `getdents64`（`getdents` 在 aarch64 不存在） | libc.so.6（未 hook） |
| `alphasort` / `versionsort` / `seekdir` / `telldir` / `rewinddir` / `dirfd` | libc.so.6（未 hook） |

对官方 v1.2.8 的 `libproroot-runtime.so`
（sha256 `8c47a0a7db32d84c179ebb5bf3640f655a3181860ece5886ae44d92858730c34`）解析
`.dynsym` 得到同一结论：共 259 个导出符号，其中**导出 `opendir`/`scandir`/`scandir64`**，
**不导出** `readdir`/`readdir64`/`closedir`/`getdents`。两条 lineage 的 hook 集一致。

**所以这不是"漏 hook `scandir`"，而是它导出了 `scandir` 但那条实现有 bug。**
（这也解释了症状 A 的劈叉：`opendir` 走 proroot 的包装所以好，`scandir` 走它自己那条
路径所以坏。）

### 4.3 一条被推翻的旧假设（避免后人重走）

我们起初按上游 issue #5 的形态推断：这是"内联 `svc` 绕过 PLT"导致路径没被翻译
（#5 的 `renameat2` 就是这样，上游在 v1.2.2 把"扫描所有主 exe"改成默认行为）。
**核对源码后这个假设被推翻**：libuv 调的是 libc 的 `scandir()`，是正常 PLT 调用，
不在内联 `svc` 这一类里。方向应从"路径没被翻译"转向"**proroot 自己的 scandir 实现**"。

---

## 5. 已排除的变量

| 变量 | 处理方式 | 结论 |
|---|---|---|
| 绑定源拼写 `/data/data` vs `/data/user/0` | 受控 A/B：同一目录绑两次，只有拼写不同 | 不是原因。实测只有**绑定源**的拼写会移动"`readdirSync`"和"相对路径解析"这两列；`-r` 的拼写两列都不动（`ProrootCommand.kt:336-350` 有该结论与复现记录） |
| `--static-loader` / `--no-static-loader` | 逐对 A/B | **零差别** |
| 绑定条数 / 绑定顺序 | 6/12 条、多种顺序 | 不是原因 |
| `PROROOT_TMP_DIR` 新旧 | 新建、共享、陈旧 | 不是原因 |
| `-e` 内联 vs 脚本 | 两种写法 | 不是原因 |
| 并发 | 4×共享 tmp / 4×独立 tmp / 单进程，共 **45 次启动** | **零掉绑定**——所以不是并发、也不是配置碰撞 |
| `.proroot-config-*` 堆积 | 共享目录里堆到 25 个 | 不是原因（前缀见 `ProrootConfigSweep.kt:48`） |

### 我们的 argv 已核实正确

- 裸值 `-b /dev` 是 **proot 的** `host == guest` 简写（`GuestRecipe.kt:53-70` 里就写成裸值）；
  只走 proot 路径（`PiRuntime.kt:438-451`）。
- proroot 路径会把它重拼成 `host:guest`：`ProrootCommand.bindArgument()`
  （`ProrootCommand.kt:192-201`）、`GuestRecipe.bindValue()`
  （`GuestRecipe.kt:259-263`）、最终在 `ProrootCommand.kt:361` 以
  `-b "$host:$BIND_SEPARATOR$guest"` 发出（`BIND_SEPARATOR` = `":"`，`GuestRecipe.kt:86`）。
  上游 v1.2.8 的解析器用 `strchr(value, ':')`，没有冒号就整条命令拒绝——所以这一处拼写是
  必须的，不是风格问题。
- 宿主侧统一走 `GuestRecipe.canonicalHost()`（`GuestRecipe.kt:227`）规范化别名。
- 引擎的绑定与 cwd：`PiEngineHost.kt:609-610`（workspace 绑定）、`:681-683`（cwd 与
  `extraBinds`），guest 侧路径由 `GuestWorkspacePath.kt:85` 的
  `DEFAULT_RELATIVE = "pi/workspaces/workspace-1"` 决定。

**所以 argv 不是原因。** 这也与症状 B 的间歇性一致：同一条 argv 有时全好、有时全丢。

---

## 6. 环境限制（为什么很多事只能上机验）

1. **本机运行的五个 `.so` 的 sha256 与任何公开 release 都不符**（另一条 lineage）。
   issue #25 已列出实测值：`libproroot.so` `5745ba3a…`(37496 B)、
   `libproroot-runtime.so` `0903cea4…`(331112 B)、`libproroot-bridge.so` `aba4a3b0…`(20200 B)、
   `libproroot-linker.so` `8f20b1a1…`(79408 B)、
   `libproroot-stub-loader.so` `baeb2df0…`(132408 B)。
   `libproroot-bridge.so` 从 v1.1.5 到 v1.2.8 一直是
   `1c5bc9537a270e8bf8b1c70222813f57b60b828bfb5503ddf8fe37685092de2f`，所以这不是"旧版本"，
   而是**另一条构建 lineage**。
2. **Android 10+ 的 W^X** 不允许从可写目录执行 `.so`（"[Removed execute permission for app
   home directory](https://developer.android.com/about/versions/10/behavior-changes-10)"：
   *"Execution of files from the writable app home directory is a W^X violation. Apps should
   load only the binary code that's embedded within an app's APK file."*）。只有
   `jniLibs` → `nativeLibraryDir` 里的 `.so` 能执行，所以**无法在本机把它换成公开 v1.2.8 做 A/B**。
3. **上游自 2026-06-17 起无维护回应**（最后一次维护者评论与最后一次 commit 都是
   2026-06-17；作者已公开把重心转向 proroom）。#22、#23、#24、#25 全部零回复。
   历史上它很快（#4/#5/#10/#12 都是 1 天内修掉），但那个节奏已经结束。

---

## 7. 已提交上游

- issue：[coderredlab/proroot#25](https://github.com/coderredlab/proroot/issues/25)

**两条诉求**（把"静默"变成"可诊断"）：

1. `PROROOT_VERBOSE` 下**打印解析后的绑定表**。目前 `PROROOT_VERBOSE=1` +
   `PROROOT_LOG_APPEND` 能出 442 行，但 `grep -i bind` **一行都没有**。
2. `-b` 无法生效时要**警告或非零退出**。目前是零输出 + 退出码 0，调用方无从分辨。

**两条确定性的小 bug**（不是间歇的，随时可复现）：

3. **裸值 `-b <host>` 被拒**：`[proroot] bad bind format (expected host:guest): /dev`，退出 1。
   而 README 同时写了 `-b <host>` 与 `-b <host>:<guest>` 两种形式。凡是照 README 写
   `-b /dev`、`-b /proc`、`-b /sys` 的 proot 命令都会失败，"drop-in replacement" 的
   说法在这一类命令行上不成立。（我们已经全线改成 `host:guest`，见 §5。）
4. **`-w` 不能指向绑定目标**：`-w /workspace/... -b <host>:/workspace/...` 报
   `[proroot] chdir workdir failed: … No such file or directory`，子进程退出 **126**。
   原因是启动器在 fork 之前按 `<rootfs>/<cwd>` 解析工作目录，**不看绑定表**。
   我们已经用 `ensureWorkdir()`（`ProrootCommand.kt:272-286`）绕开：只有 cwd 落在某条
   绑定之下、且那条绑定的宿主目录真实存在时才在 rootfs 里补一个替身目录。

---

## 8. 候选修法与副作用

| 方案 | 能修什么 | 副作用 | 结论 |
|---|---|---|---|
| **等上游** | A + B | 对我们零成本；风险是永不到来（§6.3） | 只能并行，不能当方案 |
| **启动哨兵 + 有界重试** | 只缓解 B | 每次启动多一次 probe；失败时多一次完整启动；把"静默降级"变成"重试/报错"=**故意改变用户可见行为** | 代价最小、但非零副作用 |
| **LD_PRELOAD shim 重实现 `scandir`** | 理论上只修 A | **实测前提不成立**，见下 | ❌ 不可行 |
| **物化进 rootfs（不依赖绑定）** | A + B | 产生"两个真相"；同步失败面；rootfs 在 App 私有目录里、**卸载即丢**，直接摧毁"数据不随卸载消失"这条卖点 | 只在愿意重做数据模型时考虑 |
| **换运行时** | A + B | 见下 | 战略选项，非快速修复 |

### 为什么 "LD_PRELOAD shim" 不可行（已实测）

这一条值得单独写下来，因为它看起来是最省事的路：

1. **proroot 的 linker 完全不认 `LD_PRELOAD`。** 在 `libproroot-runtime.so` 与
   `libproroot-linker.so` 里**根本没有 `LD_PRELOAD` 这个字符串**（它们实现的是自己的
   `--preload`）。实验：把 shim 放进 `LD_PRELOAD` 后，构造函数不执行、hook 不触发——
   **库根本没被加载**（尽管该变量确实留在了子进程环境里，`LD_LIBRARY_PATH` 则被 linker 识别）。
2. **退而用可执行的 `DT_NEEDED` 注入可以加载**，但只能落在 proroot 的**下游**：实测
   shim 的 `scandir`/`scandir64` 被调用时，**直接调用者是 `libproroot-runtime.so`**——
   即 proroot 的包装先跑、翻译完路径再链到我们。DT_NEEDED 顺序、`$ORIGIN` RPATH、
   嵌套依赖都试过，**都无法排到 proroot 运行时之前**。
3. 因此：**如果设备的失败发生在 proroot 包装链到下一层之前，shim 永远不会被调用**，
   修不了。这一条只能在出问题的设备上验证。
4. 附带发现：`libproroot-runtime.so` 里有未定义的 `ldso_service_*` 符号，只有 proroot 的
   clean-room linker 提供——**它无法被 glibc 的 `ld.so` 加载**。

### 换运行时的调研结论

| 方案 | 许可 / 状态 | 是否受同一缺陷影响 |
|---|---|---|
| **proot**（我们的基线） | GPL，活跃 | 否（syscall 级拦截）。但**第三方报告 Android 15 收紧 seccomp 后 proot-distro 出现 `Bad system call (SIGSYS)` / `set_robust_list: Function not implemented`**（[dev.to/opassoca](https://dev.to/opassoca/running-native-glibc-debian-binaries-on-android-15-without-proot-1hmo)）→ 换 proot 需按机型验证 |
| **[fake-chroot-ng](https://github.com/sylirre/fake-chroot-ng)** | **Apache-2.0**，C，早期开发，2026-09 仍活跃 | **否**——不用 LD_PRELOAD，而是 seccomp `RET_TRAP`→`SIGSYS` 只拦带路径的 syscall，外加自带 userland ELF loader（因此**能跑 noexec 挂载**，正好绕开 W^X）。已验：Android 15 / kernel 5.15 / rootless / SELinux / 无 userns 下跑通 Alpine、`apk`、`git clone`、Go+cgo、gcc。但 kernel 4.19 设备实测为 939 通过 / 65 失败 / 22 跳过（[issue #1](https://github.com/sylirre/fake-chroot-ng/issues/1)），且没有 Android App 集成 |
| **chroot + Shizuku** | — | Shizuku 的 adb 后端身份是 **shell UID 2000**（只有 root 启动才是 UID 0），无 `CAP_SYS_CHROOT` → **chroot 不可用**，除非真 root |
| **lroot**（另一个 LD_PRELOAD 路径翻译器） | 无 license、0★、无 README | 同属 LD_PRELOAD 类，且已死 |

### 当前决定

**不改代码，维持现状**（用户裁决："保持最新 CI 的版本"）。

理由：症状 B 的根因在闭源的 proroot 里，我们既不能 patch 也不能审计；而唯一能覆盖 pi 内部
的 shim 路线在**测过之后**确认落在 proroot 的下游（§8 上一条），无法保证有效；上游已静默
三个月。与其引入一个"可能没用、并且会改变用户可见行为"的改动，不如把这条缺陷记录清楚、
把上游 issue 挂住。

---

## 9. 如果将来要动手：最小可撤销的落点清单

前提：任何改动都要能"整体撤掉 = 一个 `git revert`"，且**不碰 proot 路径**。

1. **新增 shim 源码**（独立文件，例如 `tools/proroot-scandir-shim/proroot-scandir-shim.c`）：
   用 `opendir`+`readdir`+`closedir` 重新实现 `scandir` 与 `scandir64`，语义对齐 glibc 2.39 的
   `dirent/scandir.c` + `dirent/scandir-tail-common.c`（`.`, `..` 不过滤、`select`/`compar`
   生效、每条目 `malloc` 由调用方 `free`、失败返回 −1 并保留 errno、成功恢复入口 errno）。
   **注意：`-O2` 在 GCC 13.3 上会 ICE（`lra_remat`），用 `-O1`。**
2. **装载点**：**不能**用 `LD_PRELOAD`（§8 已实测无效），只能用可执行的 `DT_NEEDED`。
   落点是**引擎自己的可执行文件**：`<rootfs>/opt/node/bin/node`
   （`RuntimeProvisioner.kt:923` 的 `extractNode()` 把它放到 `/opt/node`；
   `/usr/local/bin/node` 只是指向它的 guest 软链，见 `RuntimeProvisioner.kt:936`）。
   在构建期于 `tools/fetch-runtime.mjs`（node 载荷在 `:1065` 附近登记）额外跑一次
   `patchelf --add-needed <shim> --add-rpath <shim dir>`，并新增一个载荷条目。
3. **只作用于引擎**：shim 必须在**非 proroot** 环境下变成 no-op（例如仅在
   `PROROOT_ROOTFS`/`PROROOT_LIB_PATH` 存在时才接管，否则 `dlsym(RTLD_NEXT)` 委托给
   原生实现），这样 proot 路径逐字节不变。
4. **断言**：给 shim 一个 `PROROOT_SCANDIR_SHIM_OFF=1` 的 A/B 开关与一个
   `PROROOT_SCANDIR_SHIM_TRACE=<file>` 的调用记录开关，便于上机对照。
5. **只能上机验的项**：
   - 症状 A 是否消失（本机复现不了，§3）；
   - proroot 的 `scandir` 包装是否**会走到链到下一层**那一步（§8.2）——shim 只在它走到
     那一层时才有机会；
   - 症状 B（整条绑定丢失）**本方案完全不覆盖**：绑定丢了以后 `opendir` 一样失败。

---

## 附：复现与取证用的最小探针

```js
// 症状 A：同一个目录，readdirSync vs opendirSync
const fs = require('fs');
const dirs = ['/root/.pi/agent', '/sdcard', '/etc', '/tmp'];
for (const d of dirs) {
  let a; try { a = fs.readdirSync(d).length; } catch (e) { a = 'ERR ' + e.code; }
  let b; try { const h = fs.opendirSync(d); let n = 0; while (h.readSync()) n++; h.closeSync(); b = n; }
  catch (e) { b = 'ERR ' + e.code; }
  console.log(d, 'readdirSync=' + a, 'opendirSync=' + b);
}
```

```sh
# 症状 B：把绑定表打印出来对比（当前 proroot 不会打印，所以只能靠 guest 侧探测）
PROROOT_VERBOSE=1 PROROOT_LOG_APPEND=/tmp/proroot.log \
  libproroot.so -r <rootfs> -0 --link2symlink -w / \
  -b <hostdir>/b01:/m01 … -b <hostdir>/b12:/m12 \
  /usr/local/bin/node -e 'const fs=require("fs");let s="";for(let i=1;i<=12;i++){const p="/m"+String(i).padStart(2,"0");try{fs.readdirSync(p);s+="Y"}catch(e){s+="."}}console.log(s)'
```
