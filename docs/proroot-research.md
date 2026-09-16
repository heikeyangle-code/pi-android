# proroot 调研报告：机制、可行性、落地设计与坑

> 调研对象：`proroot`（[coderredlab/proroot](https://github.com/coderredlab/proroot)），以及本机正在运行它的参考实现 **DSHA**（`com.dsh.client`，公开仓库 [DSH-APP/DSHA](https://github.com/DSH-APP/DSHA)，MIT）。
>
> **证据分级**（沿用 `docs/pi-android-app-design.md` 的约定）：
> `[实测]` 本机真机命令输出 · `[文件]` 本仓库 file:line · `[DSHA]` DSHA 公开文档/本机落盘代码 · `[上游]` proroot 项目官方页面 · `[推断]` 未验证的判断
>
> **本报告的独特价值**：本机本身就是一个 proroot 客户机（`com.dsh.client` 的 Ubuntu 客户机，`/root` 就是它的 `/root`），所以机制部分几乎全部是**在这里直接实测出来的**，而不是读文档推出来的。凡是与上游文档冲突的地方，以实测为准并标注。
>
> 实测环境：Xiaomi M2011K2C / Android 14 (SDK 34) / aarch64（`docs/pi-android-app-design.md:4`），app uid `10288`，`CapEff=0`，SELinux `u:r:untrusted_app:s0:...`。

---

## 0. 摘要（先看这一节）

**六个结论：**

1. **proroot 是纯用户态方案**，没有补丁内核、没有 LKM、没有 ptrace、没有 chroot/mount namespace。它靠「PLT/libc 层拦截 + 加载时改写主可执行文件里的 inline `svc` 指令 + 少量 syscall 的 seccomp 兜底」实现路径翻译。`[实测]`
2. **本机 `/proc/version` 里的 `proroot@localhost` 是 proroot 自己合成的字符串**，不是内核构建者。用户原先"内核被 proroot 打过补丁"的前提**不成立**。`[实测]` + `[上游]`
3. **proroot 的翻译有确定性缺口**：绕过 libc 的 **raw syscall 完全不被翻译**——实测 raw `openat("/etc/passwd")` 读到的是**宿主 Android 的** `/etc/passwd`。但 `git` / `node` / `dpkg` / `apt` 这些走 libc 的工具**全部正确**。这是"必须验证、但不必否决"的一类风险。`[实测]`
4. **`--link2symlink` 仍然必须带**（去掉后 `link()` 直接 EACCES），**但在本机 proroot 下它给出的是真硬链接**（同 inode、nlink=2、写穿透），不是 proot 那种 `.l2s` 符号链模拟。这解释了此前所有矛盾，也是本项目最值钱的一条。`[实测]` + `[DSHA]`
5. **公开 benchmark 只有 DSHA 自己的一组**（vivo V2352A / Android 14：关键项合计 **+58%**、tar **+94%**、stat 密集 **+82%**，另有"启动快 5~6 倍"的说法）。**上游 proroot 至今没有任何 benchmark 数字。**`[DSHA]`
6. **它不能"完美"落地，但能"安全地可选落地"**：argv/env 需要重写一小段（我们现在的 `-L` / `--kill-on-exit` / `--rootfs=` / `--cwd=` proroot **全部拒收**），并且必须照抄 DSHA 的**三层兜底 + 装机路径永远 proot**。

**建议：先做「可行性 + 实测」这一步，不要先接启动路径。** 理由与最小形态见 §9。

---

## 1. proroot 到底是什么机制

### 1.1 结论：纯用户态，三层结构

**不是**补丁内核、**不是** LKM（像 KernelSU 那一路）、**不是** ptrace（proot 的做法）。证据链：

| 证据 | 命令 / 出处 | 结果 |
|---|---|---|
| 无 ptrace | `grep TracerPid /proc/22918/status /proc/22921/status` | 全部 `TracerPid: 0` `[实测]` |
| 无 chroot、无 namespace 切换 | `ls -l /proc/self/root` → `/`；`head -1 /proc/self/mountinfo` → `253:9 / / ro,relatime` | 进程根就是真实 Android 根 `[实测]` |
| 无真实权限 | `/proc/self/status` → `Uid: 10288`、`CapPrm/CapEff/CapBnd: 0000000000000000`、`/proc/self/attr/current` → `u:r:untrusted_app:s0:c32,c257,c512,c768` | 普通 App 身份 `[实测]` |
| 无 user namespace | `unshare -Ur id` → `unshare: unshare failed: Invalid argument` | Android 禁 unprivileged userns `[实测]` |
| `id` 却是 root | `id` → `uid=0(root) gid=0(root)` | **uid/gid 是伪造的**（`-0` / `fake_id0`）`[实测]` |
| 不是内核补丁 | `/sys/module` 为空；机制上也不需要（见 1.2） | `[实测]` |

`id` 返回 0 而 `/proc/self/status` 返回 10288，这两件事同时成立，只有一种解释：**`getuid()` 这类 libc 调用被进程内拦截**，而 `/proc/self/status` 的内容（至少 `Uid:` 字段）没有被改写。这与 1.2 的机制一致。`[推断]`

### 1.2 三层拦截机制

**第 1 层（主力）：函数调用层拦截。** 每个 guest 进程都被这样启动（`[实测]` `/proc/self/cmdline`、`/proc/22921/cmdline`）：

```
libproroot-bridge.so  libproroot-linker.so  --argv0 <exe>  --preload libproroot-runtime.so  <guest 可执行文件> [args…]
```

- `libproroot-linker.so` 是 guest ELF 的 **ELF interpreter**（`PT_INTERP`），负责把它自己的 ld.so / libc 依赖解析起来（`/proc/self/maps` 里 linker 与 guest 的 `ld-linux-aarch64.so.1`、`libc.so.6` 同时映射）。上游称它为 "clean-room linker"。`[上游]` `[实测]`
- `libproroot-runtime.so` 通过 `--preload` 注入，是 **LD_PRELOAD 语义的 hook**，做路径翻译、fake id0、`/proc` 合成。`[实测]`
- `libproroot-bridge.so` 是进入点 / trampoline（DSHA 把它作为 `PROROOT_TRAMPOLINE_PATH` 传下去）。`[实测]`

**实测证据（这是本节的核心实验）**：用 `ctypes` 直接发 raw syscall，绕过 libc 包装：

```
syscall 56 (openat, AT_FDCWD, "/etc/hostname")  -> ret=-1 errno=2 ENOENT   ← 未翻译
libc      open("/etc/hostname")                 -> "localhost.localdomain" ← 已翻译
syscall 56 (openat, AT_FDCWD, "/etc/passwd")    -> ret=6 (成功)            ← 读到宿主的文件
libc      open("/etc/passwd")                   -> guest 的 /etc/passwd
```

（`openat` 的编号 56、`linkat` 37、`renameat2` 276 均为 arm64 `asm-generic` 编号；本机 `/usr/include` 无对应头文件，编号由行为交叉验证：`syscall(172)`=getpid 可用、`syscall(56)` 语义为 openat。）

**第 2 层：加载时改写二进制里的 inline `svc`。** 上游发行说明写得很直白（v1.2.2）：

> uv (`aarch64-unknown-linux-gnu`, Rust) issues `renameat2` (nr 276) through an inline `svc` inside its own `.text` rather than libc's PLT wrapper, so the previous main-exe scan whitelist left those sites unpatched. **The kernel saw the raw guest path and returned ENOENT.** … `hook_patch_filter.c`: every main exe is scanned by default — uv, cargo, rustc, go, esbuild, and similar static-svc emitters all get their inline syscall instructions patched now.
> —— <https://github.com/coderredlab/proroot/releases/tag/v1.2.2>

这条官方说明**逐字印证了我在 1.2 节的实测**（raw syscall → ENOENT），并且说明第 2 层就是补这个洞：**扫描并改写主可执行文件里的 `svc` 指令**。同一版本还说明它连 libc 的 inline `svc` 也改写（`getuid`/`geteuid`/`getgid` 等 uid/gid 家族）。

**第 3 层：少量 syscall 的 seccomp 兜底。** v1.2.4 发行说明：

> Adds narrow path/exec seccomp fallback filters with handler-PC allowlisting … Expands static-loader trampoline coverage for `getcwd`, `execve`, `execveat`, `fchmodat`, `fchmodat2`, `rt_sigaction`, `prctl`, **`faccessat2`**, generic syscall wrappers, and **seccomp fallback paths**.
> —— <https://github.com/coderredlab/proroot/releases/tag/v1.2.4>

v1.2.3 另有一条针对小米的特例：`faccessat2` → `faccessat` seccomp fallback **(HyperOS / One UI)**（<https://github.com/coderredlab/proroot/releases/tag/v1.2.3>）。本机正是 Xiaomi/HyperOS。

**实测印证**：本机存在一个每进程刷新的诊断文件 `/tmp/proroot-sigsys-last.txt`，内容为

```
[proroot-hook] SIGSYS trapped syscall=439 pc=72c666e6a8 x0=ffffffffffffff9c pid=16379
```

`439` = arm64 `faccessat2`，`x0=ffffffffffffff9c` = `-100` = `AT_FDCWD`。**`SIGSYS trapped` 就是 `SECCOMP_RET_TRAP` 的直接指纹**：proroot 用 seccomp 把 `faccessat2` 变成信号，在自己的 SIGSYS handler 里处理。`[实测]`

> 注意一个**未能确定**的点：DSHA 给子进程显式设了 `PROROOT_NO_SECCOMP=1`（见 §8.1），语义未文档化。我在 7 次嵌套运行里用不同取值跑同样的三项探针（绝对 `link`、dirfd `linkat`、raw `openat`），**raw `openat` 在所有配置下都返回 ENOENT**，即这一层对 `openat` 从不生效；`=1`/`=0` 的对比因探针残留状态而**失效，不能下结论**。语义：**未找到**。

### 1.3 五个 `.so` 的分工

| 文件 | 角色 | 依据 |
|---|---|---|
| `libproroot.so` | **launcher / CLI**（proot 兼容命令行）。DSHA 里它同时是**常驻父进程**：`pid 22918`（`PPid` = App 进程 `18508`）→ `node 22921` → `bash 6051`，所有 guest 进程都是它的后代 | `[实测]` `/proc/22918/status`、`/proc/22918/environ` |
| `libproroot-bridge.so` | 入口 trampoline（`PROROOT_TRAMPOLINE_PATH`） | `[实测]` cmdline |
| `libproroot-linker.so` | clean-room linker（`PT_INTERP`）+ 依赖解析（`$ORIGIN`/RPATH、deps pool） | `[实测]` maps；`[上游]` v1.2.6/v1.2.3 |
| `libproroot-runtime.so` | runtime hook（`--preload` / `PROROOT_LIB_PATH`）：PLT 拦截、inline `svc` 改写、路径翻译、fake id0、`/proc` 合成 | `[实测]` maps + env |
| `libproroot-stub-loader.so` | **可选**（`--static-loader`，默认 adaptive：文件存在就启用）：静态/static-pie 与 `execve`/`execveat` 路由，让子进程保持 trampoline | `[上游]` README/v1.2.4 |

**launcher 的完整 CLI（实测，权威）**——直接跑 launcher 拿到它自己打印的 usage：

```
$ <nativeLibraryDir>/libproroot.so --help
[proroot] unknown option: --help
Usage: libproroot.so [-r rootfs] [-0] [--link2symlink] [--static-loader|--no-static-loader] [-b host:guest] [-w workdir] command [args...]
```

（获取方式见 §9.5；这条 usage 是**本报告最重要的实测输出之一**，因为它直接判了我们现在 argv 的死刑，见 §4.3。）

### 1.4 launcher 的配置如何传给子进程

`libproroot.so` 在 `PROROOT_TMP_DIR` 下生成一份**定长只读配置**并 mmap 给子进程：

- 文件名 `.proroot-config-<launcher pid>`，**固定 274736 字节**，mode 600；子进程通过 `PROROOT_CFG_FD=<该路径>` 拿到。`[实测]`
- 内容是一个「根目录 + cwd + 绑定对」的定长表。实测解出（`[实测]`）：`/data/data/com.dsh.client/files/linux/ubuntu`、`/root`，然后是 DSHA 的绑定对，以及 **proroot 自己追加的隐式项**：
  - `/dev/stdin → /proc/self/fd/0`、`/dev/stdout → /proc/self/fd/1`、`/dev/stderr → /proc/self/fd/2`
  - `/vendor → /vendor`
  - `/dev/dri → <rootfs>/tmp/.proroot-empty-dri`（**一个空目录，用来遮住宿主的 /dev/dri**）
  - `/proc/bus/pci/devices → <rootfs>/tmp/.proroot-proc-bus-pci-devices`（**一个空文件，遮住 PCI 信息**）
- 尾部还有一段 flag 区（`0x10, 1, 1, 1`）与 stub-loader 路径。`[实测]`
- **配置会累积且永不清理**：本机实测已有 **11 份** `.proroot-config-*`，最早 `pid 2821`（Sep 10），最新 `22918`（Sep 16）。`[实测]`

---

## 2. 它比 proot 快在哪、快多少

### 2.1 原理上避开了什么

proot 的代价是 **ptrace 逐 syscall 停世界**：每个 syscall 都要 2 次上下文切换 + 寄存器读写 + 逐路径解析，而且 tracer 是被跟踪进程之外的另一个进程。proroot 把成本拆掉：

| proot | proroot |
|---|---|
| 每个 syscall 都 ptrace 停下 | **绝大多数路径操作在进程内的函数调用边界完成**，不进内核、无 tracer `[实测]`/`[推断]` |
| tracer 是外部进程 | 同进程内（`TracerPid: 0`，全进程树无 tracer）`[实测]` |
| 静态/raw syscall 也拦得住 | **拦不住**；改为**加载时改写主可执行文件的 inline `svc`** 来补 `[上游]` v1.2.2 |
| — | 少量 glibc 无包装的 syscall 用 **seccomp `RET_TRAP` + SIGSYS handler** 兜底 `[上游]` v1.2.4 + `[实测]` SIGSYS 文件 |

一句话：**它把"每个 syscall 都付钱"换成了"只有我们认识的路径调用付钱"**。`[推断]`

### 2.2 公开数字

| 来源 | 有没有数字 |
|---|---|
| proroot 官方 README | **没有**。只有 "zero ptrace overhead" 的口号 <https://github.com/coderredlab/proroot> |
| proroot Releases（v1.2.2 → v1.2.8） | **没有**。只有 smoke 通过项（`PLAYWRIGHT_CHROMIUM_SCREENSHOT_OK` 之类）与二进制 SHA-256 |
| Hacker News「Show HN: Proroot – Zero-overhead proot replacement for Android」 | **没有**。2 分、1 条实质评论（提问者恰好问到 `/proc/self/exe` 与 `statfs` 这类 `[实测]` 缺口），**作者回复 `[dead]`（已删除）** <https://solid-hn-islands.netlify.app/stories/47699575> |
| GeekNews 转载（原文为作者自述，泰/韩/印尼等多语） | **没有数字**。但有关键机制自述，见下 <https://th.news.hada.io/topic?id=28342> |

作者自述（GeekNews 转载的原文，值得引用，因为它解释了为什么没有数字——当时还在早期）：

> 目标是不用 root 也能做 path translation 与 environment isolation，同时**减少 overhead by doing syscall interception in-process**。目前用 **`LD_PRELOAD` 与 binary patching 的混合方法**。… **目前只支持 arm64**。… CLI flags 仍用 `-r`, `-w`, `--link2symlink`。

> 这条自述与 §1.2 的实测完全吻合：`LD_PRELOAD`（第 1 层）+ binary patching（第 2 层），并且从第一天就是 **arm64-only**。

### 2.3 唯一一组真实数字（来自 DSHA，不是上游）

DSHA 的第三方声明（[THIRD_PARTY_NOTICES.md](https://raw.githubusercontent.com/DSH-APP/DSHA/master/THIRD_PARTY_NOTICES.md)）写：

> 用途：**默认**的容器运行时（v1.1.6 起）。用 LD_PRELOAD + 二进制补丁做进程内路径翻译，没有 ptrace 的上下文切换开销，**真机实测启动快 5~6 倍**

DSHA README 另一处的分项实测（经父代理解码：**vivo V2352A / Android 14**）：**关键项合计 +58%、tar 打包 +94%、stat 密集 +82%**。

**使用这些数字时必须带上三个限定**：① 它们是 **DSHA 自己的测量**，不是上游、不是第三方复现；② 机型是 **vivo V2352A / Android 14**，与本机（Xiaomi M2011K2C）不同；③ 我们**没有**在本机复现过其中任何一个（§9.5 给出复现方法）。

**结论：对"快多少"这个问题，可引用的上限就是 DSHA 那一组；上游与第三方数据"未找到"。要在本机拿到数字，只有自己测（§9.5）。**

---

## 3. 依赖与前置条件

| 项 | 结论 | 依据 |
|---|---|---|
| 需要 root 吗 | **不需要**。实测运行身份 `uid=10288`、`CapEff=0`、`untrusted_app` | `[实测]` |
| 需要补丁内核 / LKM 吗 | **不需要**。机制上只用 libc 拦截 + 二进制改写 + 标准 seccomp，全部非特权可用 | `[实测]` `[推断]` |
| 需要 user namespace 吗 | **不需要，而且用不了**：`unshare -Ur` 在本机直接 `Invalid argument`（Android 禁 unprivileged userns）。这反过来说明**路径翻译是 Android 上唯一可行的路线** | `[实测]` |
| Android 版本 | **Android 8.0+（API 26）** | `[上游]` README |
| ABI | **arm64-v8a only**。注意：DSHA 的 proot 时代还带 `libprootloader32.so`（32 位 loader），proroot **没有 32 位对应物** → 32 位 guest 支持丢失 | `[上游]` README；`[文件]` `docs/pi-android-app-design.md:94` |
| rootfs | **glibc 的 Ubuntu arm64 rootfs**（musl 不在其列） | `[上游]` README |
| 验证过的组合 | Node 22.22.2 + npm 10.9.7、Python 3.12.3、Git 2.43.0、Chromium/Playwright、XFCE4+TigerVNC、curl、OpenSSL 3.0；Samsung Galaxy Flip（A16）、Lenovo Tab（A15） | `[上游]` README |
| 能不能由普通 APK 自带并启用 | **能**，而且**只能**这样：Android 10+ W^X 不允许从 `filesDir` 执行；必须放进 `jniLibs` → `nativeLibraryDir` | `[DSHA]` THIRD_PARTY_NOTICES「为什么随包分发而不是按需下载」；与 `[文件]` `PiRuntime.kt:120-123`（我们放 `libproot.so` 的同一条理由）|
| 5 个 `.so` 是谁的二进制 | **proroot 项目发布的产物**（GitHub Releases，5 个资产 + SHA-256）。DSHA 只是把它们放进 `jniLibs/arm64-v8a/` 并改成 `libproroot-*.so` 命名（`lib` 前缀 + `.so` 后缀是 Android 提取到 `nativeLibraryDir` 的硬要求） | `[上游]` README/releases；`[DSHA]` 同上 |
| SELinux 配合 | **不需要额外放行**。实测在 `untrusted_app` 下正常运行；`/sys/fs/selinux/enforce` 读不到（`Permission denied`），但这不影响 proroot | `[实测]` |
| 不可用时什么行为 | **上游 README 未写**。DSHA 的做法（可作为我们的规范）见 §9.2：文件缺失 → 自动降回 proot；连续 3 次启动失败 → 强制切回 proot 并告知用户 | `[DSHA]` |
| 许可证 | **Proprietary**：`"Free to use in your projects. Redistribution of modified binaries is not permitted."` 源码不公开 | `[上游]` README/License |
| 上游维护状态 | 作者已把重心转向 **proroom**（独立 App），README 明说 "expect slower updates"；最新 release **v1.2.8（2026-06-17）** | `[上游]` README + releases.atom |
| 被下游接纳情况 | **因闭源被 Termux 官方仓库拒收**（proroot issue #21） | `[DSHA]` THIRD_PARTY_NOTICES「已知限制」|

### 3.1 ⚠️ 本机运行的二进制**不是**任何公开 release

这是本次调研**最出乎意料**的一条实测结果。DSHA 公开声明它分发的是 **v1.2.8 的原始二进制**，并给出了 5 个 SHA-256；但**本机正在运行的 5 个二进制的 SHA-256 与 v1.2.8 以及 v1.2.2–v1.2.7.1 的公开值全部不符**：

| 文件 | 本机实测（通过 raw `openat` 精确路径读取后 sha256） | DSHA 声明 / v1.2.8 上游 |
|---|---|---|
| `libproroot.so` | `5745ba3afaf3a74e57eb638cf40f3e627b7c3a683e55f3a2e88f373b68117b62` (37496 B) | `a4e74d75b66cdc02…` |
| `libproroot-runtime.so` | `0903cea45424eccc71e00339c9124932b587694d49fc7c6ddbfc05e3fe10a966` (331112 B) | `8c47a0a7db32d84c…` |
| `libproroot-bridge.so` | `aba4a3b070feedbfa939bae72d598f7c2caf412a7c4aaf49193adfb2e7c76e89` (20200 B) | `1c5bc9537a270e8b…` |
| `libproroot-linker.so` | `8f20b1a155f029dc038d66485f18d036b0cf8be4275aebf76fe6702e91dd4de0` (79408 B) | `51a0ec5bfed00e572…` |
| `libproroot-stub-loader.so` | `baeb2df0f6fccbc4cb8b9b2af114686f1e638f5eb8e4ad6d91a897a45b853c31` (132408 B) | `06c6624db3bdc45b9…` |

**加强证据**：`libproroot-bridge.so` 在**所有**公开 release（v1.1.5 → v1.2.8）里 SHA-256 都是 `1c5bc953…`（v1.2.2/v1.2.3 的发行说明明确写 "unchanged"）。本机却是 `aba4a3b0…` → 说明本机跑的是**与公开 release 不同的构建谱系**（更新的私有/合作构建），不是"某个旧版本"。`[实测]` + `[上游]`

**含义（对方案影响很大）：**
- 本报告里所有 `[实测]` 的行为（真硬链接、dirfd `linkat` EACCES、`/proc/version` 合成、SIGSYS 诊断文件名），都属于**这个未公开的构建**；公开 v1.2.8 未必逐条相同。
- **"照 DSHA 的 sha256 登记"这句话在本机是假的**：DSHA 公开文档与它自己设备上跑的字节不一致。→ 我们必须**对自己打包的那一份**算 sha256 并登记，而不是抄上游或抄 DSHA（§9.4）。
- 反过来说：如果我们要 pin 版本，就应该 **pin 我们能拿到、能验证的那一份（上游 release）**，然后**在本机实测它**，不要假设它与 DSHA 当前行为一致。

`[实测]` 获取方式（本报告用到的关键命令）：

```bash
# 从 guest 内读 /data/app 下真实二进制并算 sha256（raw syscall 绕过翻译，精确路径无需列目录）
python3 - <<'EOF'
import ctypes,hashlib
L="/data/app/~~7vfz0i13aur0en3kcOYJxg==/com.dsh.client-DAVtkQRYmswAZke0vwyBbA==/lib/arm64"
libc=ctypes.CDLL(None,use_errno=True); libc.syscall.restype=ctypes.c_long
for n in ["libproroot.so","libproroot-runtime.so","libproroot-bridge.so","libproroot-linker.so","libproroot-stub-loader.so"]:
    fd=libc.syscall(ctypes.c_long(56),ctypes.c_int(-100),ctypes.c_char_p((L+"/"+n).encode()),ctypes.c_int(0),ctypes.c_int(0))
    h=hashlib.sha256()
    while True:
        b=ctypes.create_string_buffer(1<<20)
        k=libc.syscall(ctypes.c_long(63),ctypes.c_int(fd),b,ctypes.c_int(1<<20))
        if k<=0: break
        h.update(b.raw[:k])
    libc.syscall(ctypes.c_long(57),ctypes.c_int(fd)); print(n,h.hexdigest())
EOF
```

---

## 4. 能不能"完美落到" app.pi 上

**一句话答案：不能"完美"（有 4 处硬冲突 + 3 类语义分叉），但能"干净地可选落地"。**

### 4.1 好消息：我们的绑定配方本来就是从 DSHA 抄的，几乎同构

`[文件]` `app/src/main/kotlin/app/pi/runtime/PiRuntime.kt:176-234` 的 `ProotCommand`，与 DSHA launcher 的实测 argv（`[实测]` `/proc/22918/cmdline`）逐项对比：

| 绑定点 | 我们 `PiRuntime.kt` | DSHA proroot | 一致？ |
|---|---|---|---|
| `-b /dev` | :182 | 有 | ✅ |
| `-b /dev/urandom:/dev/random` | :183 | 有 | ✅ |
| `-b /proc` / `-b /sys` / `-b /system` / `-b /apex` | :184-185 | 有 | ✅ |
| `-b /proc/self/fd:/dev/fd` | :186 | 有（且 proroot ≥v1.2.4 会**自动**加 `/dev/stdin|stdout|stderr`） | ✅（可能变冗余，未知） |
| `-b <storage>:/sdcard` / `:/storage/emulated/0` | :188-189 | 有 | ✅ |
| `-0` | :214 | 有 | ✅ |
| `--link2symlink` | :206 | 有 | ✅ |
| 可写 `/tmp` | :221（`-b <runtime>/tmp:/tmp`） | **没有**；DSHA 用 rootfs 内的 `/tmp` | ⚠️ 差异（不是冲突） |
| `-b <rootfs>/.l2s:<rootfs>/.l2s` | :212 | **没有** | ⚠️ 见 §5.4 |
| `/dev/shm` | 未绑 | `-b <cache>/shm:/dev/shm` | ⚠️ 我们可能缺 |

`[文件]` `docs/pi-android-app-design.md:79-111` 记的 §2.3「直接抄」配方，实测**就是这份绑定表**——但**环境变量部分已过时**（见 §4.3 与 §10.4）。

### 4.2 硬冲突：我们现在的 4 个 flag，proroot 全部拒收

`[实测]`，逐个跑 launcher：

```
$ libproroot.so -L              -> [proroot] unknown option: -L
$ libproroot.so --kill-on-exit  -> [proroot] unknown option: --kill-on-exit
$ libproroot.so --rootfs=/x     -> [proroot] unknown option: --rootfs=/x
$ libproroot.so --cwd=/x        -> [proroot] unknown option: --cwd=/x
Usage: libproroot.so [-r rootfs] [-0] [--link2symlink] [--static-loader|--no-static-loader] [-b host:guest] [-w workdir] command [args...]
```

而我们 `[文件]` `PiRuntime.kt:212-216` 正在用 `-L`、`--kill-on-exit`、`--rootfs=`、`--cwd=` 四个。**所以"换个二进制就能跑"是不成立的：它会立刻以 usage 退出（不是静默降级）。**

（`-L` 是 proot 的"绝对符号链接按 guest 语义解析"。§7.3 实测显示 proroot **不需要** `-L`：guest 绝对路径的符号链接在 proroot 下能正常解析。`--kill-on-exit` 则是 proot 的进程树清理开关，proroot 没有对应物 → 见 §5.7。）

### 4.3 启动参数逐项映射（照这个改）

**argv**

| 现在（`PiRuntime.kt`） | proroot 写法 | 动作 |
|---|---|---|
| `paths.prootBinary()` = `libproot.so` | `libproroot.so` | 换文件 |
| `--link2symlink`（:206） | 同名，**保留**（§7.2 实测必须带） | 不变 |
| `-b <l2s>:<l2s>`（:212） | proroot 自动用 `<rootfs>/.l2s`，DSHA 不传 | **可以删**（保守起见留也无害，待实测） |
| `-L`（:214） | **不存在** | **必须删** |
| `--kill-on-exit`（:214） | **不存在** | **必须删，并自己实现收尾**（§5.7） |
| `-0`（:214） | `-0` | 不变 |
| `--rootfs=<x>`（:215） | `-r <x>` | 改写法 |
| `--cwd=<x>`（:216） | `-w <x>` | 改写法 |
| `/bin/bash` + `-c` + cmd（:222-232） | 同 | 不变 |
| 其余 `-b` | 同 | 不变 |

**env**（`[文件]` `PiRuntime.kt:246-276`）

| 现在 | proroot | 动作 |
|---|---|---|
| `PROOT_LOADER=<nativeLibDir>/libprootloader.so` | **不认**。proroot 从 `/proc/self/exe` 目录自动发现 4 个同目录 `.so`；也可显式 `PROROOT_LINKER_PATH` / `PROROOT_LIB_PATH` / `PROROOT_STUB_LOADER`（DSHA 三个都显式传，`[实测]` `/proc/22918/environ`） | **删**，或换 `PROROOT_*` |
| `PROOT_TMP_DIR` | `PROROOT_TMP_DIR`（**必须是宿主可写路径**，见下方实测坑） | **改名** |
| `PROOT_L2S_DIR` | **不认**。proroot 的锚点固定在 `<rootfs>/.l2s`（实测 `/data/…/linux/ubuntu/.l2s`，7520 项，而 DSHA 根本没设这个变量） | **删**（目录本身保留，见 §5.4） |
| `LD_LIBRARY_PATH=<runtime>/lib:<nativeLibDir>`（为 `libtalloc.so.2` 别名） | **不需要**（proroot 不依赖 talloc）；继续设会把宿主路径注入 guest 的 `LD_LIBRARY_PATH` | **改**：只给 guest 合理的值 |
| `HOME/TMPDIR/TERM/LANG/PATH/GIT_SSL_CAINFO/SSL_CERT_FILE` | 同 | 不变 |
| （未设） | `PROROOT_NO_SECCOMP=1`（DSHA 设了） | 待定，见 §8.1 |

> `[实测]` **一个容易摔的坑**：launcher 是**宿主侧**进程（raw `execve` 后 proroot 的 hook 已经不在），所以 `PROROOT_TMP_DIR` 必须是**宿主真实路径**。我第一次传 `/tmp/proroot-probe-tmp`（guest 路径）→ `[proroot] open config file: No such file or directory`，退出码 1。改成 `<rootfs>/tmp/proroot-probe-tmp`（宿主真实路径）后正常。
> `[文件]` 我们的 `PROOT_TMP_DIR = paths.tmp.path` 是 **JVM/宿主侧**的 `File.absolutePath`，本来就是宿主路径，**这点我们已经是对的**。

**客户机根**：仍是 `<files>/pi/runtime/rootfs`（`[文件]` `PiRuntime.kt:76`、`:215`），通过 `-r` 传入。DSHA 的根是 `files/linux/ubuntu`，我们的是 `files/pi/runtime/rootfs` —— 布局约定不同但**语义完全对应**（rootfs 是 volatile、用户数据在 `home`），不影响 proroot。

### 4.4 会变 / 不会变 / 无法确定

**✅ 不会变（有依据）**

- 客户机根位置、volatile/durable 划分（我们自己的目录约定，与翻译层无关）。
- `-b host:guest`、`-0`、`-r`、`-w`、`--link2symlink`、`--static-loader` 这些语法（`[实测]` usage 行）。
- 网络不隔离（proot 亦然；`[文件]` 设计文档 §2.2 实测 `curl 127.0.0.1:3090` 可直连）。
- "`id`=root 是假的"这一认知（proroot 也一样，且 fake root 还会影响 socket peer 凭据：`[上游]` v1.2.5「`SO_PEERCRED` socketpair probe in `-0` mode: `os_getuid=0`, `peer_uid=0`」）。
- 主流工具链的路径翻译**正确**：`[实测]` `git hash-object /etc/passwd` = guest 的 blob 哈希 `aed16daa…`（host 会是 `4d4a1877…`）；`node` 读 `/etc/hostname`(22B)/`/var/lib/dpkg/status`(460539B)/`/usr/lib/os-release`(400B) 全部 OK；`dpkg -l` 460 行、`apt list --installed` 456 行。
- `PROOT_L2S_DIR` 那套"必须绑到相同宿主绝对路径"的做法：我们**已经一致**（`[文件]` `PiRuntime.kt:212` 与 `:249`），且与 `[DSHA]` AGENTS.md 的 trap 条目一致。

**⚠️ 会变**

1. **`/proc/version` 是合成的**。`[实测]` raw `openat("/proc/version")` → `EACCES(13)`（SELinux 真禁 `untrusted_app` 读），libc `open` → `Linux version 5.4.274-qgki-g76ac0b265f44 (proroot@localhost) #1 SMP PREEMPT Thu Dec 11 18:21:30 UTC 2025 f2fs-hash:6ee821ead5`。`[上游]` v1.2.3 明确写「`/proc/version` synthesize」。而 `os.uname().release` 给出真实的 `5.4.274-qgki-g76ac0b265f44`，`os.uname().version` 给出 `#1 SMP PREEMPT … f2fs-hash:…` → **proroot 用 uname 的真字段 + 自造的 builder 字段拼出 `/proc/version`**。`[推断]`
   → 任何"读 `/proc/version` 判断内核/环境"的逻辑（**包括我们自己的诊断页**）在两套机制下会给出不同答案。
2. **`/proc/self/exe` 被伪造**。`[上游]` README 的 `PROROOT_GUEST_EXE` 就写着 "Guest path for `/proc/self/exe` emulation"；v1.2.8 修过 `__readlink_chk`/`__realpath_chk` 绕过 PLT 导致**泄漏宿主 launcher 路径**的问题。`[实测]` 更直接：`readlink /proc/22918/exe`（读**别的**进程的 exe）返回的是**读取者自己**的 guest exe 路径（`/usr/bin/readlink`），完全不是被读进程的路径 → **`/proc/<pid>/exe` 在 proroot 下不可信**。
3. **`/proc/self/maps` 内容被改写**。`[上游]` v1.2.3「hook 的 `/proc/self/maps` 直接 walk」、v1.2.8 崩溃转储写 maps。`[实测]` `cat /proc/self/maps` 显示 `/usr/bin/cat`、`/usr/lib/aarch64-linux-gnu/libc.so.6` 等 **guest 路径**，而真实映射的是 `<rootfs>/usr/bin/cat`。
4. **`/dev/dri` 与 `/proc/bus/pci/devices` 被自动遮罩**，`/vendor` 被自动加入。`[实测]` 见 §1.4。proot 不会做这些。
5. **`/proc` 的其余部分是宿主真 `/proc`**（与某些 proot 配置不同）：`/proc/mounts`、`/proc/self/mountinfo`、`/proc/cpuinfo`、`/proc/self/status` 都是真的。`[实测]`
6. **32 位 guest 支持消失**（arm64-only）。`[上游]`
7. **静态/raw-syscall 程序的处理方式完全不同**（需要 stub loader；且未覆盖时会**读到宿主文件**而非报错）。`[实测]` + `[上游]`
8. **`PROOT_*` 环境变量全部失效**；反过来 proroot 会**自动**加 `/dev/stdin|stdout|stderr` 映射（v1.2.4），我们的显式 `-b /proc/self/fd:/dev/fd` 可能变冗余甚至冲突（**未知**）。

**❓ 无法确定（必须实测）**

- `-b` 到不存在的目标、`-b` 数量上限、ARG_MAX 行为。
- proroot 是否要求 `<rootfs>/.l2s` **预先存在**（proot 要求，我们为它写了长篇 KDoc：`[文件]` `PiRuntime.kt:81-110`）。DSHA 不传这个 bind 也能跑，说明 proroot 至少不像 proot 那样依赖它。
- `--kill-on-exit` 的替代品（没有）与孤儿进程回收行为。
- PTY 语义：proroot 在真进程里 fork/exec，PTY 应当是**真 PTY**（比 ptrace 转发更自然），但我们 `[文件]` `PtyLauncher.kt:100-104` 的 `script(1)` 探测与退出码探测**必须在 proroot 下重跑**；`[上游]` v1.2.4 提到为"stuck shell 调查"加了 bash 的 `clone`/`wait` 诊断过滤器，说明这块历史上有过问题。
- 文件锁（`fcntl`/`flock`）是否透传（理论上真文件系统上的真锁，应当没问题）。
- **嵌套**（proroot 里再跑 proot，或反之）——未测。
- `PROROOT_NO_SECCOMP` 的语义（§1.2 末）。

---

## 5. 坑清单（按严重度）

### 🔴 P0-1 分发授权与可审计性（法律/供应链）

`[上游]` LICENSE：`Proprietary. Free to use in your projects. Redistribution of modified binaries is not permitted.` 源码不公开（作者："still stabilizing… don't want to publish something half-broken"）。最新 v1.2.8（2026-06-17），作者已转向 proroom 并声明 "expect slower updates"。`[DSHA]` 承认它"因闭源被 Termux 官方仓库拒收（proroot issue #21）"、"无法审计，出问题只能等作者修"。

**规避**：见 §9.4 的工程判据。要点：① 未修改的原样再分发**是 DSHA 的判断**（它写"允许在项目中使用，禁止分发**修改过**的二进制"），但这**不是一条明确的再分发授权**，属于灰区；② 所以要么取得作者书面许可，要么**不随包**（改为用户显式同意的按需获取——但那会撞上 W^X，见 §9.4）；③ 无论哪条路，都必须**自己算 sha256 并登记**（§3.1 证明不能抄）。

### 🔴 P0-2 raw/inline syscall 不被翻译 → 静默读到宿主文件

`[实测]`：

```
raw syscall(56=openat, AT_FDCWD, "/etc/hostname") -> ENOENT      # 未翻译
raw syscall(56=openat, AT_FDCWD, "/etc/passwd")   -> fd=6 成功   # 读到宿主 Android 的 /etc/passwd
libc open("/etc/hostname")                        -> localhost.localdomain   # 已翻译
```

这不是"报错"，而是**静默地把 guest 路径当宿主路径**。`[上游]` v1.2.2 的原始描述正是 "The kernel saw the raw guest path and returned ENOENT"。

**为什么它没有立刻毁掉 DSHA**：v1.2.2 起对**主可执行文件**做 inline `svc` 扫描改写（uv/cargo/rustc/go/esbuild 都在覆盖列表里），所以大多数动态链接程序被兜住了。

**残留风险**：扫描是启发式的（模式匹配 `svc` 指令），可能漏编码；静态二进制在 `--static-loader` 被关掉时没有兜底。**规避**：不要关 `--static-loader`（默认 adaptive，只要 stub loader 同目录就开）；对敏感路径做探针；把这条写进"proroot 模式"的已知限制告知用户。

**⚠️ 对本项目特别重要**：pi 的 `find`/`grep` 工具与 `@` 文件提及依赖 **`rg`/`fd`（Rust）**，而 Rust 正是 v1.2.2 点名的 "inline `svc` emitters" 一类。**本机 DSHA 客户机里没有 `rg`/`fd`**（`command -v rg fd` 无输出；`/usr/local/bin` 只有 corepack/dsh/node/npm/npx/playwright），所以**我没能实测它们**（§9.5 给出必须在 pi 自己的 rootfs 里跑的探针）。

### 🟠 P1-1 `PROROOT_NO_SECCOMP=1`：参考实现关掉了兜底层，语义不明

`[实测]` DSHA 的子进程环境里有 `PROROOT_NO_SECCOMP=1`（`/proc/22921/environ`），而上游 README 的环境变量表**完全没有这个变量**（只有 `PROROOT_VERBOSE`/`GUEST_EXE`/`TMP_DIR`/`STUB_LOADER`/`LOG_APPEND`）。

**含义（推断）**：它很可能关掉的是第 3 层 seccomp 兜底——而第 3 层恰好是**唯一能救 raw/dirfd-relative syscall 的层**。如果这个推断成立，那么 DSHA 目前运行在"没有兜底"的配置下，这正好解释了它自己代码里那些 raw syscall 都拿不到翻译（§8.2）。**语义：未找到**；我做的 `=1`/`=0` 对照实验因探针残留而失效，**不能下结论**。

**规避**：我们**不要**照抄这个变量（保持默认）；改为在自检里分别跑一次"raw syscall 探针"和"libc 探针"，把结果记进诊断。

### 🟠 P1-2 `/proc` 三件套被伪造/改写 → 两种机制行为分叉

`/proc/version` 合成（§4.4-1）、`/proc/self/exe` 伪造（§4.4-2）、`/proc/self/maps` 改写（§4.4-3）、`/dev/dri` 与 `/proc/bus/pci/devices` 遮罩（§4.4-4）。

**规避**：`[文件]` 我们的 `DiagnosticsReport.kt:198-199` 现在列 proot binary/loader 路径。切到 proroot 后要**按引擎分别报告**，并且**不要**用 `/proc/<pid>/exe` 做进程身份判定（§9.3 trap#6）。

### 🟠 P1-3 `--link2symlink` 的真实语义（本报告最重要的更正）

`[实测]`，用 §9.5 的嵌套方法在**两个配置**下跑同一段探针：

| 配置 | `os.link(dir/a, dir/b)` | 结果 |
|---|---|---|
| **带** `--link2symlink` | 成功 | `nlink=2`、**`st_ino` 相同 (2770494)**、`islink=False`、**写 `b` 之后读 `a` 得到新内容** → **真硬链接**；`/.l2s` 计数**不变** |
| **不带** `--link2symlink` | 失败 | `EACCES`（干净重跑，非残留） |

三条独立结论：

1. **`--link2symlink` 在本机 proroot 下是必需的**——去掉它 `link()` 直接 `EACCES`。这与 `[DSHA]` AGENTS.md「已知 trap」第 3 条一致：**app 私有目录禁 `link(2)`（SELinux），proot 必须带 `--link2symlink`**。
2. **但带上它，proroot 给出的是真硬链接，不是 `.l2s` 符号链模拟**。这与 proot 完全不同（proot 的 `link2symlink.c` 是把 `link()` 变成符号链）。所以 `[DSHA]` 文档里"proot 下必须带这个 flag"与"proroot 下硬链接真的可行"**并不矛盾**：**flag 两边都要带，但一边模拟、一边真给**。
3. **`/.l2s` 里的 7520 项是历史残留**。本机 `/.l2s` 同时存在两种命名——proot 风格 `.l2s.<名>.<hash>.tmp0001` 与 proroot 风格 `.l2s.<uuid>0001 → /.l2s/.l2s.<uuid>.0001`（末 4 位 = 模拟的 nlink）——但**我做过的每一次 link 操作（同目录、跨目录、nlink=3、250 字符长名、跨文件系统、dirfd 相对）都没有新增条目**，最新的条目是 **Sep 15 14:45**，而当前 proroot 进程起于 Sep 16 11:00。`[实测]`
   → `/.l2s` 的条目来自 proot 时代和/或早期行为；`[DSHA]` 的写入侧治理（一律 rename，不再产生新链）已经生效。

**`dirfd` 相对 `linkat` 是坏的**（附带发现）：`os.link("a","c", src_dir_fd=fd, dst_dir_fd=fd)` 以及 `linkat(dirfd → AT_FDCWD)` 都返回 **`EACCES`**，而同一目录下用绝对路径就成功；同一批测试里 `statat`/`openat`/`mkdirat`/`symlinkat`/`unlinkat` 用 dirfd **都正常**。**所以缺口精确地落在 `linkat` 的 dirfd 相对形式上**。`[实测]` 这与 `[上游]` v1.2.8 的修复条目（"relative-dirfd `unlinkat` bypassed the link2symlink path"）是同一类薄弱环节，说明这块代码路径一直不稳。

### 🟠 P1-4 exec 权限与 W^X（我们已经在做，但要注意命名）

5 个 `.so` **必须**在 `nativeLibraryDir`；`[DSHA]` 明确写"Android 10+ 的 W^X 策略不允许从应用可写目录（`filesDir`）执行代码"。这与 `[文件]` `PiRuntime.kt:120`（"The one location Android lets us execute from"）完全同因。

**注意**：jniLibs 只放行 `lib*.so`，而 proroot 的**自动发现**是按**固定文件名**在 `/proc/self/exe` 的目录里找 `libproroot-runtime.so` / `-linker.so` / `-bridge.so` / `-stub-loader.so`。→ **改名会破坏自动发现**；要么保持原名，要么像 DSHA 那样显式传 `PROROOT_LINKER_PATH` / `PROROOT_LIB_PATH` / `PROROOT_STUB_LOADER`（`[实测]` DSHA 三个都传了）。**建议显式传，不依赖自动发现。**

### 🟠 P1-5 进程树与退出：`--kill-on-exit` 没有对应物

`[实测]` proroot 的 launcher 是**常驻父进程**（`22918` 是 `node 22921` 的父进程，也是整个 guest 树的根）。

- `[文件]` `PiRuntime.kt:214` 的 `--kill-on-exit` 是 proot 的开关，proroot **拒收**（§4.2）→ 我们失去"杀 launcher 就回收整棵树"的能力。
- `[文件]` 我们现在的收尾是 `process.destroyForcibly()`（`RuntimeSelfCheck.kt:117`、`GuestCommand.kt:145`、`DeviceShell.kt:89`）——**只杀直接子进程**，在 proot 下靠 `--kill-on-exit` 补全，在 proroot 下会**泄漏整棵 guest 树**。
- `[DSHA]` 的处理方式有两层：① pid 文件（`echo $$ > /root/.dsha-web.pid` 再 `exec node`，exec 不换 pid）；② 专门的 `WebProcSel` 纯逻辑类，**"认得出 dsh 进程、绝不误杀 proot/proroot"**（杀到容器启动器 = 环境连 App 一起带走），并有 JUnit 锁死这条不变式。
- 附带：每次启动都会在 `PROROOT_TMP_DIR` 留一份 **274736 字节**的 `.proroot-config-<pid>`，**永不清理**（本机实测 11 份）。

**规避**：proroot 模式下必须自己实现"按自己启动时登记的进程组/pid 前缀回收 guest 树"，绝不做端口反查（§9.3 trap#1），并定期清理 `.proroot-config-*`。

### 🟠 P1-6 升级/降级路径与"两套机制并存"的行为分叉

同一个 `<rootfs>` 在两套机制下，`.l2s` 命名、`/proc/*` 内容、`PROOT_*` vs `PROROOT_*`、32 位支持、静态二进制处理**全都不同**。用户一拨开关，pi 的会话/扩展缓存可能带着上一套机制的假设。

**规避（两案权衡）**：
- **A 案（推荐，省磁盘）**：共用同一个 rootfs，但**切换引擎时强制跑一次自检 + 一次 `npm ci`/`pnpm install --frozen-lockfile` 校验**，并把"当前引擎"写进会话元数据。
- **B 案（最干净，贵）**：rootfs 按引擎分目录（`runtime/rootfs` vs `runtime/rootfs-proroot`）。彻底无分叉，但**磁盘翻倍**（须重算 `RuntimeSpaceBudget.kt`），且用户切换后要重新装一次扩展。

### 🟡 P2-1 HyperOS / One UI 特例

`[上游]` v1.2.3「`faccessat2` → `faccessat` seccomp fallback **(HyperOS / One UI)**」；v1.2.2「`clone3` (nr 435) ENOSYS trampoline unblocked `curl` on Xiaomi 10S / HyperOS Android 13」。本机是 Xiaomi（`[文件]` 设计文档:4）。
→ **版本下限建议 ≥ v1.2.3，最好 v1.2.8。**

### 🟡 P2-2 与 glibc 版本耦合

`[上游]` v1.2.7「Adds **adaptive glibc internal syscall patching** for compatible runtime layout changes」，并 hook 了 `mkstemp64`/`mkostemp64`/`mkstemps64`/`mkostemps64`；v1.2.8 又补了 `__readlink_chk`/`__realpath_chk`（"hidden symbols that bypassed PLT interposition"）。
→ 这类修补**依赖 glibc 的内部布局**。我们的 rootfs 是 **Ubuntu 24.04.3 / glibc 2.39**（`[文件]` 设计文档:69）。上游 tested-with 里有 Python 3.12/Ubuntu，但**没写 glibc 2.39**。**必须实测**，并在 pin 版本时把"实测过的 glibc 版本"一起记下来。

### 🟡 P2-3 权限与安全：它**不会**让 pi 提权（正向结论）

- `CapEff`/`CapBnd` = 0、SELinux = `untrusted_app`、`/sys/fs/selinux/enforce` 读不到、`id`=0 纯属伪造。`[实测]`
- `[实测]` `PROROOT_ESCAPE_FD=3` 是一个**只读 dirfd 指向真实 `/`**（`/proc/22921/fd/3 -> /`）。这是 proroot 解析宿主路径的内部机制，**但它受同样的 app uid 权限约束**：我从 guest 里沿别名 + `..` 只能走到 `/data/data/com.dsh.client`，再往上就是真实的 `Permission denied`。
→ **结论：proroot 既不给也不夺权限。**真正的风险是**认知风险**：guest 内程序会**相信自己是 root**（`chown` 假装成功、`/proc/version` 是假的），从而做出错误判断。`[文件]` `PiRuntime.kt:168-169` 已经写明"`-0` 是 fiction，`chown` appears to succeed and does nothing"——这条认知我们已有。

### 🟡 P2-4 本机二进制 ≠ 任何公开 release（可审计性缺口）

见 §3.1。→ **不能抄上游或 DSHA 的 sha256，必须对自己打包的字节算。**

---

## 6. 结论：值不值得做

### 6.1 值不值得

**值得做「可选」，但按当前信息不建议作为默认，也不建议在验证前接进启动路径。**

支持做的理由：
1. **技术上确实能落**，而且参考实现**就在同一台设备上跑着**（DSHA 的进程树、`/proc`、二进制都在本机可查）——这是最强的可行性证据。
2. 我们现有绑定配方**本来就从 DSHA 抄的**，`ProotCommand` 只需改 4 个 flag + 3 个 env（§4.3）。
3. **对 l2s 那一整类历史问题，proroot 是该问题的"解药"而非"新病"**：本机实测它给真硬链接（§5.P1-3），而 proot 给符号链模拟。我们为 l2s 写了 `flatten-l2s.py` 级别的治理代码（`[文件]` `/root/.dsh/flatten-l2s.py`）。

谨慎的理由：
1. **闭源专有 + 上游重心转移 + 被 Termux 官方拒收**（§3）。
2. **引入一类新的静默失败**（raw syscall → 读到宿主文件，§5.P0-2），而我们的 `rg`/`fd` 恰好是 Rust（未能在本机实测）。
3. **`/proc` 全面伪造**（§5.P1-2），我们现有诊断逻辑要按引擎分叉。
4. **性能收益没有本机数字**（§2）。如果 pi 的热路径主要不是 syscall，收益可能远小于 DSHA 那组数字。

### 6.2 最小可用形态（MVS）——七步

1. **只加一个设置项** `app.runtime.engine`：取值 `proot`（默认）/ `proroot`；放在 `[文件]` `PiSettingsRegistry.kt:171` 的 `G_RUNTIME`（"运行时与诊断"）分组（组定义在 `:1488`）。默认**关**（按用户要求）。语义："打开＝在可用时走 proroot；不可用自动回退 proot 并说明原因"。
2. **argv/env 双构造**：把 `[文件]` `PiRuntime.kt:176` 的 `object ProotCommand` 拆成 `ProotCommand` 与 `ProrootCommand`，**共享** `baseBinds()`（`:179-191`）。两者差异严格按 §4.3 的表。
3. **装机路径永远 proot**（照抄 `[DSHA]`）：解压、安装、六步一律 proot；proroot 只作用于"执行命令"这一层。→ 这条极大缩小了风险面。
4. **三层兜底**（照抄 `[DSHA]`）：① 运行时文件缺失 → 自动降回 proot；② 连续 3 次启动失败 → 强制切回 proot **并告知用户**；③ 装机路径永远 proot。
5. **可用性探测 = 一次带超时的真实自检**，而不是"文件存在"：
   - 不要相信"文件在就等于能用"。跑一次 `ProrootCommand` 的 `/bin/sh -c 'echo PROROOT_OK'`，超时 8s；成功才把设置真正置为生效，失败则回退并把**具体原因**写进诊断。
   - 结果写 `<runtime>/.selfcheck-proroot`（对应现有 `.selfcheck`，`[文件]` `PiRuntime.kt:153`），随 `wipe()` 一起失效。
   - **不要**用 `libproroot.so --help` 做探测：`[实测]` 它把 `--help` 当成未知选项并**返回非零**。
6. **诊断分引擎**：`[文件]` `DiagnosticsReport.kt:198-199` 现在列 proot binary/loader；加 proroot 三件套 + **当前实际生效的引擎** + 一层"raw syscall 探针"结果（§9.5）。
7. **告知用户**：设置项 subtitle 写清"实验性：使用第三方闭源运行时；不可用时自动回退 proot"，并在 `[文件]` `docs/pi-android-app-design.md` 的已知限制里登记。

### 6.3 代价与风险

| 维度 | 代价 |
|---|---|
| 包体 | 5 个 `.so` 合计 **约 600 KB**（`[实测]`：37496+331120+20200+79408+132408 = 600,624 B） |
| 测试面 | 每个 pi 功能要在两个引擎下各跑一遍（至少：引擎启动、PTY、工具链、`npm`/`pnpm` 安装、会话读写、备份） |
| 维护 | 跟随上游 1.2.x 修 bug；上游更新变慢；无法自行修（闭源） |
| 法律 | 未修改原样再分发的授权是灰区（§9.4） |
| 磁盘 | A 案无额外开销；B 案 rootfs 翻倍 |

### 6.4 如果做，先做哪一步

**先做「可行性 + 实测」，不要先接启动路径**（与父代判断一致）。具体顺序：

1. 在本机把 §9.5 的**五组探针**跑完（尤其 raw-syscall 探针 + `rg`/`fd` 探针 + PTY/退出码探针），拿到结果再决定。
2. 若探针通过：按 MVS 第 1–5 步做"影子模式"——设置项存在、能探测、能自检、能回退，但**执行命令仍走 proot**；然后在 debug 构建里手工把开关指向 proroot 跑一遍全量回归。
3. 若全绿，再把 proroot 作为可选执行路径放出去；**永不做默认**（与 DSHA 相反，因为 DSHA 是自研自用、我们是通用 App）。

### 6.5 验证方法（本机确切命令）

**① 拿到 launcher 的权威 CLI**（不需要任何额外文件；用 raw `execve` 绕过 proroot 的 exec 拦截）：

```bash
python3 - <<'EOF'
import os, ctypes, time
L="/data/app/~~7vfz0i13aur0en3kcOYJxg==/com.dsh.client-DAVtkQRYmswAZke0vwyBbA==/lib/arm64/libproroot.so"
r,w=os.pipe(); pid=os.fork()
if pid==0:
    os.close(r); os.dup2(w,1); os.dup2(w,2)
    libc=ctypes.CDLL(None)
    a=[b"libproroot.so", b"--help"]; e=[b"PATH=/usr/bin"]
    A=(ctypes.c_char_p*3)(*a,None); E=(ctypes.c_char_p*2)(*e,None)
    libc.syscall(ctypes.c_long(221), ctypes.c_char_p(L.encode()), A, E)  # raw execve
    os._exit(127)
os.close(w); time.sleep(1.5)
print(os.read(r,8192).decode()); os.waitpid(pid,0)
EOF
```
> 为什么必须 raw `execve`：正常 exec 会被 proroot 的 linker 接管，得到 `deps: cannot find libdl.so` + `proroot-ldso: failure rc=2`。`[实测]`

**② raw syscall 泄漏探针**（我们最关心的风险）：

```bash
python3 - <<'EOF'
import ctypes
libc=ctypes.CDLL(None,use_errno=True); libc.syscall.restype=ctypes.c_long
def raw(p):
    ctypes.set_errno(0)
    fd=libc.syscall(ctypes.c_long(56),ctypes.c_int(-100),ctypes.c_char_p(p.encode()),ctypes.c_int(0),ctypes.c_int(0))
    return fd, ctypes.get_errno()
print("raw openat /etc/hostname ->", raw("/etc/hostname"))
print("libc open  /etc/hostname ->", open("/etc/hostname").read().strip())
EOF
```
判定：**proroot 下 raw 应返回 `(-1, 2/ENOENT)`**（未翻译）。**若返回有效 fd，说明 raw syscall 也被翻译了（更好）；若返回 fd 且内容是宿主内容，就是泄漏。**

**③ `rg` / `fd`（Rust）是否被绕过**——必须在 **pi 自己的 rootfs** 里跑（本机 DSHA 客户机没有 `rg`/`fd`）：

```bash
# 参照物：guest 的 /etc/passwd 哈希与宿主不同
sha1sum /etc/passwd                     # guest 值
# 让 rg/fd 自己去读，看它读的是哪一份
rg -N '^root' /etc/passwd
fd -H '^passwd$' /etc
fd -H . /var/lib/dpkg | head            # /var/lib/dpkg 只在 guest 存在
node -e "console.log(require('fs').readFileSync('/etc/passwd','utf8').slice(0,40))"
```
判定：`fd` 能列出 `/var/lib/dpkg`、`rg` 能匹配 guest 的 `/etc/passwd` → 未被绕过。**若 `fd . /var/lib/dpkg` 报 ENOENT 或列空 → 否决级风险**（Rust 的 inline `svc` 没被 patch 到）。

**④ `--link2symlink` 有/无对照**（本机实测过的形态；关键是**每次跑前清干净探针目录**，否则第二次会得到 `EEXIST` 假象）：

```bash
rm -rf /tmp/hlprobe && mkdir -p /tmp/hlprobe && echo AAA > /tmp/hlprobe/a
python3 - <<'EOF'
import os,errno
d='/tmp/hlprobe'
try:
    os.link(d+'/a',d+'/b')
    sa,sb=os.stat(d+'/a'),os.stat(d+'/b')
    print("link OK  same_ino",sa.st_ino==sb.st_ino,"nlink",sa.st_nlink,"islink",os.path.islink(d+'/b'))
    open(d+'/b','wb').write(b'BBB')
    print("write-through -> a =",open(d+'/a','rb').read())
except OSError as e: print("link FAIL",errno.errorcode.get(e.errno))
fd=os.open(d,os.O_RDONLY)
try:
    os.link('a','c',src_dir_fd=fd,dst_dir_fd=fd); print("dirfd link OK")
except OSError as e: print("dirfd link FAIL",errno.errorcode.get(e.errno))
EOF
ls -f /.l2s | wc -l     # 前后对比：不变 = 没有产生 l2s 锚点
```
**本机已在 proroot 下实测结果**：带 flag → `same_ino True / nlink 2 / write-through`；不带 flag → `EACCES`；`dirfd link` → `EACCES`；`/.l2s` 计数不变。

**⑤ 性能对照（自己测，唯一能回答"快多少"的方法）**：

```bash
# 分别用 proot 与 proroot 各跑一遍，记 real/user/sys
time /bin/bash -c 'for i in $(seq 1 200);  do /bin/true; done'          # fork/exec 密集
time /bin/bash -c 'for i in $(seq 1 2000); do stat -c%s /etc/hostname >/dev/null; done'  # 路径解析密集
time /bin/bash -c 'tar cf /dev/null -C /usr/share/doc . 2>/dev/null'    # tar 遍历（对应 DSHA 的 +94%）
```
（`[DSHA]` 的分项口径就是 "tar 打包 / stat 密集 / 关键项合计"，所以这三条可与它的数字对照。）

**⑥ 无 ptrace 复核**（随时可做的健康检查）：

```bash
for p in /proc/[0-9]*; do [ -r $p/status ] && awk -v P=$p '/^TracerPid:/{if($2!=0)print P,$0}' $p/status; done
```
应无输出。

### 6.6 未找到 / 无法确定（不猜）

- 上游或第三方对 proroot 的**任何 benchmark 数字**：**未找到**。
- `PROROOT_NO_SECCOMP` 的语义：**未找到**（`=1/=0` 对照实验因探针残留失效）。
- 公开 release 与 DSHA 声明 sha256 的一致性：**一致**（都是 v1.2.8 的 5 个值）；但**与 DSHA 本机实际运行的字节不一致**（§3.1）。
- `-b` 到不存在目标的错误行为、`-b` 数量上限：**未测**。
- proroot 是否要求 `<rootfs>/.l2s` 预先存在：**未测**（间接证据：DSHA 不传该 bind 也能跑）。
- PTY/信号/退出码在两套机制下的逐项对比：**未测**（由 `[文件]` `PtyLauncher.kt:243-257` 的探测框架可以在本机跑）。
- `rg`/`fd` 是否被 raw-syscall 绕过：**本机无法测**（客户机里没有这两个二进制）。
- proroot 里嵌套 proot：**未测**。

---

## 7. 同类项目怎么做：DSHA 的 proroot/proot 双运行时（文档为证）

DSHA 的公开仓库 [DSH-APP/DSHA](https://github.com/DSH-APP/DSHA)（MIT）有完整文档，它要做的**正是用户想要的那个形态**。以下每条都引原文。

### 7.1 双运行时与开关

`[DSHA]` [THIRD_PARTY_NOTICES.md](https://raw.githubusercontent.com/DSH-APP/DSHA/master/THIRD_PARTY_NOTICES.md)：

> - **默认启用**（v1.1.6 起），可在「配置」页取消勾选改用传统 proot
> - 不参与装机路径（解压、安装六步一律用 proot），**只影响「执行命令」这一层**
> - 运行时文件缺失时自动降回 proot
> - **连续 3 次启动失败会强制切回 proot 并告知用户**
> - 因此最坏情况是这一层退回 proot，不会导致环境不可用 —— **这是敢把闭源组件设为默认的前提：它不可用时系统自动绕过它**

→ 这就是我们在 §9.2 要照抄的**三层兜底**。注意它比用户原始需求（"默认关"）更进一步：**DSHA 把它做成了默认开**。我们按用户要求做默认关即可，架构照抄。

### 7.2 为什么随包分发（而不是按需下载）

`[DSHA]` 同上：

> Android 10+ 的 W^X 策略不允许从应用可写目录（`filesDir`）执行代码。下载到 `filesDir` 的 `.so` 无法执行，只有放进 APK 的 `jniLibs`、由系统提取到 `nativeLibraryDir` 才能跑。现有的 `libproot.so` 同理。

→ **这条直接把"按需下载"这条路堵死了**（也因此把 P0-1 的法律问题变成了"要么随包、要么不做"）。与 `[文件]` `PiRuntime.kt:120` 完全一致。

### 7.3 DSHA 的 `--link2symlink` 与相对链接（与我的实测互相印证）

`[DSHA]` [AGENTS.md](https://raw.githubusercontent.com/DSH-APP/DSHA/master/AGENTS.md)「已知 trap」：

> - **app 私有目录禁 `link(2)`**（SELinux），**proot 必须带 `--link2symlink`**。
> - `PROOT_L2S_DIR` 在 rootfs 内的 `.l2s`，**必须把该目录绑定到相同的宿主绝对路径**；否则 dpkg 安装时对硬链接执行 chown/stat 会报文件不存在。

第二条我们**已经一致**（`[文件]` `PiRuntime.kt:212` 的 `-b <l2s>:<l2s>` + `:249` 的 `PROOT_L2S_DIR`）——这是本次对照里"已处理"的一条。

第一条与 §5.P1-3 的实测**完全吻合**（去掉 flag → `EACCES`），而"proroot 下硬链接真的可行"则由我的 inode/写穿透实验坐实。

**相对链接**：`[DSHA]` `/root/.dsha-backup-plugin-graph.py:218`（本机落盘代码）：

> `# 相对链接在暂存树及最终树中都成立，proroot 不必解析宿主不可见的 guest 绝对路径。`
> `link.symlink_to(os.path.relpath(plugin_stage / '.deps' / target, link.parent), target_is_directory=True)`

→ **实测结论**：`[实测]` 在 proroot 下，**guest 绝对路径的符号链接也能正常解析**：

```
abs_link: read=b'DATA'  target=/tmp/symprobe/tgt2    ← 绝对 guest 目标，成功
rel_link: read=b'DATA'  target=tgt2                  ← 相对目标，成功
```

所以 DSHA 这条注释针对的**不是"能不能解析"，而是"备份/暂存树遍历时不必去解析"**（配合 AGENTS.md 的维护契约："旧 L2S 文件链接按 guest 根与宿主别名映射读取内容，拒绝循环、越界和外部挂载"）。**在 Termux proot 下这条坑是存在的**（proot 不带 `-L` 时 guest 绝对软链会按宿主解析失败——这正是我们 `[文件]` `PiRuntime.kt:213-214` 要传 `-L` 的原因）；**在 proroot 下不存在**（实测能解析）。

→ **对我们（`PiPaths` 里大量 guest 绝对路径绑定与软链，如 `/usr/local/bin/rg → /root/.pi/agent/bin/rg` 这类）的含义**：换 proroot **不会**让这些链接变坏（甚至比 proot 更省心，因为不需要 `-L`）；但**如果将来要写备份/迁移**，应该照 DSHA 做**相对链接 + 显式拒绝越界/循环**，而不是依赖引擎去解析。

### 7.4 许可与 sha256 登记方式（我们要照这个格式）

`[DSHA]` THIRD_PARTY_NOTICES 的做法值得**逐字模仿**，它每个组件都写：**来源仓库 + 版本 + 许可原文引用 + 包内位置 + sha256 + 用途 + 为什么随包 + 用户可控性 + 已知限制**。proroot 那一条的原文：

> ## proroot
> - 来源：https://github.com/coderredlab/proroot（v1.2.8）
> - 许可：Proprietary。README 原文：*"Free to use in your projects. Redistribution of modified binaries is not permitted."* —— 允许在项目中使用，禁止分发**修改过**的二进制
> - 在包内的位置：`lib/arm64-v8a/libproroot.so`、`libproroot-runtime.so`、`libproroot-linker.so`、`libproroot-stub-loader.so`、`libproroot-bridge.so`
> - 分发的是官方 release 的**原始二进制**，未作任何修改，sha256 与上游 release notes 一致：〔5 个值〕
> - ### 已知限制
>   - 上游未公开源码，无法审计，出问题只能等作者修
>   - 作者已将开发重心转向另一个项目（proroom），更新频率会下降
>   - 因闭源，Termux 官方仓库拒绝收录（见 proroot issue #21）

**⚠️ 但 §3.1 实测证明：它声明的 sha256 与它本机跑的字节不一致。** 所以我们的登记必须**以我们自己打包的字节为准**。

### 7.5 DSHA 的实测数字

见 §2.3（+58% / tar +94% / stat +82% / 启动快 5~6 倍；vivo V2352A / Android 14）。

---

## 8. DSHA 自己的做法与坑（代码为证）

DSHA **没有对外文档专门讲 proroot**（本机 `/root/.dsh/**`、5 个内置插件源码、`.dsha-*.py`、`dsh-bin/*`、`dsha-*` 安装标记**全部翻过**，提 proroot 的只有下面两处代码 + 一批环境变量）。所以它的 proroot 知识**全在代码注释与环境变量里**。

### 8.1 环境变量的真相（本机实测）

`[实测]` `/proc/22918/environ`（launcher）与 `/proc/22921/environ`（node 子进程）：

| 变量 | 值 | 谁设 | 上游文档？ |
|---|---|---|---|
| `PROROOT_TMP_DIR` | `/data/user/0/com.dsh.client/files/linux/tmp` | launcher | ✅ 有 |
| `PROROOT_LINKER_PATH` | `<nativeLibDir>/libproroot-linker.so` | launcher | ❌ 无 |
| `PROROOT_LIB_PATH` | `<nativeLibDir>/libproroot-runtime.so` | launcher | ❌ 无 |
| `PROROOT_STUB_LOADER` | `<nativeLibDir>/libproroot-stub-loader.so` | launcher | ✅ 有 |
| `PROROOT_TRAMPOLINE_PATH` | `<nativeLibDir>/libproroot-bridge.so` | 子进程 | ❌ 无 |
| `PROROOT_CFG_FD` | `/data/user/0/…/tmp/.proroot-config-22918`（**是路径不是 fd**） | 子进程 | ❌ 无 |
| `PROROOT_ROOTFS` | `/data/data/com.dsh.client/files/linux/ubuntu` | 子进程 | ❌ 无 |
| `PROROOT_ESCAPE_FD` | `3`（`/proc/22921/fd/3 -> /`，只读指向真实根） | 子进程 | ❌ 无 |
| `PROROOT_GUEST_EXE` | `/usr/local/bin/node` | 子进程 | ✅ 有 |
| `PROROOT_SIGSYS_LOG_HOST_PATH` | `<rootfs>/tmp/proroot-sigsys-last.txt` | 子进程 | ❌ 无 |
| **`PROROOT_NO_SECCOMP`** | **`1`** | 子进程 | ❌ **无，语义未找到** |
| `PROOT_*` | **一个都没有** | — | DSHA **当前完全不用 Termux proot** |

→ 结论：**DSHA 现在 100% 跑 proroot**，且它依赖了 **7 个上游未文档化的内部环境变量**（`PROROOT_LINKER_PATH`/`LIB_PATH`/`TRAMPOLINE_PATH`/`CFG_FD`/`ROOTFS`/`ESCAPE_FD`/`SIGSYS_LOG_HOST_PATH`/`NO_SECCOMP`）。这本身是**维护风险**：这些名字随时可能在没有文档的情况下变。

→ 也说明 `[文件]` `docs/pi-android-app-design.md:79-111` 记的 §2.3「直接抄」配方**已经过时**：它记的是 `PROOT_TMP_DIR`/`PROOT_LOADER`/`PROOT_LOADER_32`/`PROOT_L2S_DIR` 与 `libproot.so`/`libprootloader.so`/`libprootloader32.so`，那是 DSHA 用 **Termux proot** 时期的进程（那份文档写于 Sep 14，测的是 `PID 6754`；今天同一 App 跑的是 proroot）。**§10.4 给出该文档需要改的三处。**

### 8.2 坑一：`dsha-runtime-fs/index.js` 的 rename 发布（并核它是否一直走降级分支）

`[DSHA]` `/root/.dsh/node_modules/dsha-runtime-fs/index.js`（3594 B，本机落盘）第一行：

> `/** 用独立临时副本与 renameat2 发布完整文件，保留 link 的源文件和 EEXIST 语义。 */`

实现要点（照原文）：

- `koffi.load(null)` 取 `renameat2` 包装函数；取不到就**手写 syscall 号 —— 注释写明 `{ arm64: 276, x64: 316 }`**，走 `syscall(number, …)`。
- 发布流程：`copyFile(source, temporary, COPYFILE_EXCL)` → `fsync` → `renameNoReplace(temporary, target)`。
- 失败分支原文：

```js
try { await renameNoReplace(temporary, target); }
catch (error) {
  if (!privateSession || !['ENOSYS', 'EINVAL', 'EOPNOTSUPP'].includes(error.code)) throw error;
  await lockedSessionPublish(temporary, target);   // target + '.dsha-publish.lock' + tryLockExclusive，10s deadline
}
```

**核实父代假设（"很可能一直走锁降级分支"）→ 实测结论：不是降级，是直接失败。**

- `[实测]` 在 proroot 下用 guest 路径发 raw `renameat2`：

  ```
  raw renameat2(276, AT_FDCWD, "/tmp/symprobe/tmpnew", AT_FDCWD, "/tmp/symprobe/renamed", RENAME_NOREPLACE)
      -> (-1, errno=2 ENOENT)      ← 未翻译，内核看的是宿主路径
  libc os.rename(...)              -> OK   ← libc 层被 proroot 翻译
  ```
- 因为返回的是 **`ENOENT`**，而白名单只认 `ENOSYS`/`EINVAL`/`EOPNOTSUPP`，且只有在 `privateSession === true` 时才降级 → **`kernelPublish` 会直接 `throw` 一个 `ENOENT: 无法原子发布文件`**，**根本不会走 `lockedSessionPublish`**。`[实测]`+`[推断]`（调用方在不可读的上层代码里，理论上可能另接 ENOENT 处理；但那条降级分支按代码是**不可达**的）。
- `ENOSYS` 只有在 `koffi.load(null)` / 函数查找失败时才抛（"当前系统没有 renameat2"），在有 koffi 的 Android 上不会发生。

→ **教训（我们该采纳的）**：**在 proroot 里不要用 raw syscall 号 + `koffi`/`syscall` 去做文件发布**——那一层不翻译。**改用两条 libc 调用：`open(tmp, O_CREAT|O_EXCL)` + `rename()`**（`[实测]` libc `os.rename` 正常）。同理，**任何"为了原子性而走 raw syscall"的写法，在 proroot 下都要重新评估**。

→ 对父代问题的直接回答：**在 Termux proot 下同样会踩**。proot 只拦截 libc 调用，**raw syscall 一样绕过**（proot 靠 ptrace 拦所有 syscall，所以实际上 proot **能**拦 raw syscall——这正是 proot"慢但全"的原因；而 proroot"快但不全"）。所以两边都不该用 syscall 号，**统一用 libc 的 `open`+`rename` 最稳**。`[推断]`（proot 的 ptrace 覆盖面是它的设计原理，本机无 proot 可实测）

### 8.3 坑二：相对链接（`dsha-backup-plugin-graph.py:218`）

原文与实测结论见 §7.3。**判定：这条与我们"无关但值得学"**——我们的绑定/软链在 proroot 下不会坏（实测绝对软链能解析），但**备份/迁移代码**应当学它：用 `os.path.relpath` 造相对链接，并显式拒绝越界/循环。

### 8.4 DSHA 在本机留下的其它可观测痕迹（坑的物证）

| 痕迹 | 含义 | 依据 |
|---|---|---|
| `/.l2s` 7520 项，两种命名（proot 的 `.tmp0001` + proroot 的 `.l2s.<uuid>0001`） | 历史存量；写入侧治理（一律 rename）后**不再新增** | `[实测]` |
| `flatten-l2s.log`：`flattened=0 dangling=0 skipped=0 orphans=0` | 摊平脚本跑过但没找到要摊的（因为 `root=/root/.dsh` 里已经没有了） | `[实测]` `[文件]` 日志 |
| `/tmp/.proroot-empty-dri/`、`/tmp/.proroot-proc-bus-pci-devices` | proroot 的 `/dev/dri`、`/proc/bus/pci/devices` 遮罩物 | `[实测]` |
| `/tmp/proroot-sigsys-last.txt`（每次被 trap 就刷新） | seccomp 兜底在工作的物证 | `[实测]` |
| 11 份 `.proroot-config-*`（Sep 10 → Sep 16） | 每次启动留一份 274736 B，**无清理** | `[实测]` |
| `repair-builtin.log` 全是"内置插件注册均已就绪，无需改动" | 自愈路径长期空转（没有出错，也没修到东西） | `[实测]` |
| `/etc/profile.d/dsha-runtime-env.sh` 里 `NARB_DISABLE_NATIVE_CACHE` **写了两遍** | 粘贴/合并的重复，无害但说明是"知道重要才重复" | `[实测]` |

---

## 9. 给 app.pi 的最好方案（照 DSHA 的形态）

### 9.1 设置项长什么样

- **键**：`app.runtime.engine`（建议；与现有 `app.runtime.*` 命名空间一致）。`[文件]` `PiRuntime.kt` 与 `PiSettingsRegistry.kt` 里已有 `app.runtime.keepAlive`、`app.runtime.systemPrompt`、`app.runtime.piVersion` 等键，命名可循。
- **分组**：`G_RUNTIME`（"运行时与诊断"，`[文件]` `PiSettingsRegistry.kt:171` 定义、`:1488` 组定义与 summary）。
- **形态**：单选（`proot` / `proroot`）优于开关——因为将来可能再添 `proot-low`（对应 DSHA 的 low 兼容版）。若坚持开关，则 `engine.enabled=false`（默认）语义同 `proot`。
- **默认**：**关 / `proot`**（按用户要求）。
- **标题**：`运行时加速（实验性）`；**副标题**（`row` 的描述）必须说清三件事：① 用第三方闭源运行时；② 不可用时自动回退 proot；③ 需要重启引擎生效。
- **summary**（等级 0 列表上那一行，`[文件]` `PiSettingsRegistry.kt:1488-1496` 的写法）：显示**实际生效**的引擎，而不是设置值——例如 `proroot（已回退：缺少运行时文件）`。这一条很重要：DSHA 的 AGENTS.md 反复强调"设置里写了什么"≠"实际生效什么"（`[文件]` `:1490-1493` 就有同款注释）。

### 9.2 三层兜底（照抄 DSHA，这是敢做这个开关的前提）

1. **运行时文件缺失 → 自动降回 proot**（不打扰用户，只在诊断里记一行）。
2. **连续 3 次启动失败 → 强制切回 proot 并告知用户**（`[DSHA]` 原文）。计数要持久化；成功一次即清零。
3. **装机路径永远 proot**：解压、安装六步（`[文件]` `RuntimeProvisioner.kt`）、`RuntimeSelfCheck` 的探针、`wipe()` 后的首启——**一律 proot**。proroot 只影响"执行命令"这一层。

再加一层**我们的**（DSHA 没有的）：4. **自检不通过 → 不落地设置**（§6.2 第 5 步）。因为我们的用户面比 DSHA 宽得多。

### 9.3 DSHA 那 10 条 trap × app.pi 现状（逐条）

| # | DSHA 的 trap | 我们在 app.pi 的现状 | 判定 |
|---|---|---|---|
| 1 | **停止靠 pid 文件，不靠端口反查**（`/proc/net/tcp` 非 root 读不到、`/proc` 有 hidepid；`echo $$ > pid文件` 再 `exec node`） | 我们**持有 `Process` 句柄**（`[文件]` `RuntimeSelfCheck.kt:117`、`GuestCommand.kt:145`、`DeviceShell.kt:89` 都是 `process.destroyForcibly()`），从不需要端口反查 → **第 1 条的前提在我们这里不成立** | **不适用（已规避）**，但见 #6 |
| 2 | **停止先写哨兵** `/root/.dsha-stopped`（否则看门狗"秒复活"） | 我们没有 guest 内看门狗/自复活脚本（`[实测]` grep 全仓无 sentinel） | **不适用** |
| 3 | **app 私有目录禁 `link(2)`，proot 必须带 `--link2symlink`** | 我们**已经带**（`[文件]` `PiRuntime.kt:206`）✅。**新增结论**（实测）：proroot 下这个 flag **也必须带**（去掉即 EACCES），但给的是**真硬链接** | **已处理**（且两套机制都要带，见 §5.P1-3） |
| 4 | **`PROOT_L2S_DIR` 必须在 rootfs 内，并把该目录绑到相同宿主绝对路径**（否则 dpkg 对硬链接 chown/stat 报文件不存在） | 我们**完全一致**：`[文件]` `PiRuntime.kt:110`（`File(rootfs, ".l2s")`）+ `:212`（`-b <l2s>:<l2s>`）+ `:249`（`PROOT_L2S_DIR`），而且 `:81-110` 的 KDoc 已经把这条坑写透（引了 `link2symlink.c` 的 `open_l2s_directory()`） | **已处理，且比 DSHA 文档更详细**。切 proroot 时 **`PROOT_L2S_DIR` 这个变量要删**（proroot 不认），但**目录本身保留**（proroot 固定用 `<rootfs>/.l2s`，实测） |
| 5 | **`NARB_DISABLE_NATIVE_CACHE=1`**：原生扩展缓存的默认 `link+unlink` 在 `--link2symlink` 下**首次会悬空** | `[实测]` 全仓 grep **零命中** → **未处理**。我们在客户机里跑 Node + pi，且 pi 的扩展生态会装原生扩展 | **未处理，建议采纳**（§10.3） |
| 6 | **`WebProcSel`：认得出 dsh 进程，绝不误杀 proot/proroot**（"杀到容器启动器 = 环境连 App 一起带走"） | 我们持有句柄所以不会误杀**别人的**进程；但**反过来有个更严重的缺口**：`destroyForcibly()` 只杀直接子进程，而 proroot **没有 `--kill-on-exit`**（`[实测]` 拒收）→ **会泄漏整棵 guest 树** | **部分未处理：必须补"guest 树回收"**（§5.P1-5） |
| 7 | **备份/迁移**：`.l2s` 挂载占位遍历前精确排除；旧 L2S 链按 guest 根 + 宿主别名映射读取；拒绝循环/越界；热数据是符号链接而 `tar` 只存链接 → 需解引用快照 | `[文件]` 我们有 `WorkspaceStore.kt`/`TarExtractor.kt`，但**没查到**针对 `.l2s` 的排除与解引用快照逻辑 | **未处理（若我们做备份）**。注意：如果 §10.1 的"真硬链接"结论成立，这个风险的**性质变了**——不再有 `.l2s` 悬空链，但**硬链接的 tar 语义仍是"存链接"** |
| 8 | **DocumentsProvider**：guest 绝对软链按 rootfs 解析；**删除链接不递归目标** | `[文件]` `GuestWorkspacePath.kt` 在做路径映射；删除链接是否递归需另查 | **待核**（不在本次范围） |
| 9 | **维护脚本预先选择 proot 执行**；"目录存在探针 ≠ 完整遍历/归档可用"；**不能在写入结果未知后自动改通道重放** | `[文件]` 我们的 `RuntimeSelfCheck.kt` 是"跑一次真命令"，方向正确；但**没有**维护任务的概念 | **不适用/部分适配**：若将来加维护任务，采纳"预选 proot + 不自动重放" |
| 10 | **link2symlink 一整条叙事**：写入侧改 rename 发布（`dsha-runtime-fs`）、插件依赖用相对链接、`.l2s` 摊平脚本 | 我们**没有**这三样（我们不是 link 发布模型） | **不适用（但 §10 有独立收益）** |

### 9.4 许可登记与 sha256 pin（工程判据，非法律意见）

**能不能打包？** 工程判据如下（**灰区，需人拍板**）：

- 上游许可原文只写了 `"Free to use in your projects. Redistribution of modified binaries is not permitted."` → **明确禁止的是"分发修改过的二进制"**；对"未修改的原样再分发"**既没允许也没禁止**（`[DSHA]` 把"允许使用"读成"允许原样随包分发"，见 §7.4）。
- **风险等级**：中。它是 Propretary 且**上游明确被 Termux 官方仓库拒收**（issue #21），说明同类项目对它的授权判定并不一致。
- **建议（保守优先）**：
  1. **首选**：写一封邮件给作者，取得"允许在你的应用中随包分发未修改的 v1.2.x 二进制"的书面许可，把回信存档进 `assets/licenses/`。
  2. **次选**：`runtime.lock.json` 里 pin 版本 + sha256，`THIRD_PARTY_NOTICES` 里如实登记"许可状态未明/仅依 README 语义"。
  3. **不可行**：按需下载 → 被 W^X 堵死（§7.2）。

**要登记什么（照 DSHA 的字段，加我们自己的规矩）**：

`[文件]` 我们的许可资产体系是 **flat + `manifest.txt` 索引**：`app/src/main/assets/licenses/manifest.txt` 每行 `文件名<TAB>标题<TAB>分区`，现有分区为 `说明` / `许可证全文` / `软件包版权与许可`（`[实测]` 136 行、分区计数 `{'说明':5,'许可证全文':22,'软件包版权与许可':107}`）。`[文件]` `LicensesScreen.kt:51` 的 KDoc 明说"只通过 manifest 列目录，**加一个许可不需要改 Kotlin**"；`[文件]` `tools/build-license-assets.py` 的 docstring 规定**文本不得凭记忆手写**，必须来自实际随包的 artifact。

→ 因此 proroot 的登记应当：

1. **不要**塞进"许可证全文"分区（它没有开源许可全文可放）。
2. 在 `app/src/main/assets/licenses/component-list.txt` 的 **"■ Android 原生库（随 App 二进制安装）"** 段（`[实测]` 该段现在列 `proot 5.1.107.92 / GPL-2.0`、`libtalloc 2.4.3`、`libandroid-shmem 0.7`，格式为 `组件名 + 版本/许可证/上游` 三行缩进）**新增一条 `proroot`**，字段：`版本 v1.2.x` / `许可证 Proprietary（未修改二进制随包分发；上游未授予明确再分发条款）` / `上游 https://github.com/coderredlab/proroot`。
3. 在 `manifest.txt` 里**新增一个分区名**（例如 `专有组件`）指向一个新文件 `proprietary-third-party.txt`，内容含：包内位置（`lib/arm64-v8a/libproroot*.so` 5 个）、**我们自己算的 5 个 sha256**（§3.1 的教训：**不要抄上游/DSHA**）、retrieval 日期、上游 release tag、以及三条"已知限制"（无法审计 / 上游重心转移 / 被 Termux 拒收）。
4. **`runtime.lock.json` 加 pin 条目**。`[文件]` 现有结构是 `{"artifacts": {"proot": {url, sha256, why}, …}}`（`[实测]`），所以照抄：`"proroot": {"url": "<release tarball 或 5 个 asset 的 url>", "sha256": "<逐文件>", "why": "更快的用户态运行时；可选，默认关"}`。注意 pypi/`build-license-assets.py` 的规矩是**文本/二进制都从实际随包的 artifact 生成**，所以 proroot 的 sha256 应由 `tools/fetch-runtime.mjs` 下载后**计算并写入 lock**（而不是先写死再下载）。
5. **写进许可页的"已知限制"**：照 `[DSHA]` 三条 + 我们的两条：`raw syscall 不被翻译（可能读到宿主文件）`、`/proc/version 与 /proc/<pid>/exe 被伪造`。

### 9.5 维护风险与 pin 策略

**源码不公开 + 作者转做 proroom** 对我们"pin 一个版本、可复现构建、CI 里能验证"的惯例意味着：

1. **不可复现构建**：我们无法从源码重建，所以"可复现"只能退化成**"字节可验证"** —— pin 住 5 个 sha256，并在 CI 里**下载后校验**（`tools/fetch-runtime.mjs` 的既有模式：`ARTIFACTS` 里带 `url` + lock 里带 `sha256`，`[文件]` `tools/fetch-runtime.mjs:165-178`）。
2. **升级即引入未审计变更**：每次升版本都要**重跑 §9.5 的五组探针**，把结果记进一个 `proroot-verified.json`（glibc 版本、Android 版本、探针结果、sha256）。这相当于用**行为契约**替代**源码审计**。
3. **要有"退出能力"**：因为无法自行修 bug，架构上必须保证**随时能只改一个设置就退回 proot**（MVS 第 1、2、4 步保证这一点）。**这正是 DSHA 三层兜底的真实用意。**
4. **不要依赖未文档化的内部环境变量**（§8.1 列了 7 个）。我们只用 README 文档化过的：`PROROOT_TMP_DIR`、`PROROOT_STUB_LOADER`，加 `PROROOT_LINKER_PATH`/`PROROOT_LIB_PATH`（为了不依赖自动发现、避免改名陷阱 —— 这一对是内部变量，**要么用、要么保持固定文件名**，二选一，别两头落空）。

### 9.6 建议的执行顺序（先做哪一步）

**第 0 步（本次调研已完成）**：机制、可行性、风险、参考实现形态 —— 即本报告。
**第 1 步（建议下一步只做这个）**：**可行性 + 实测**，不碰启动路径。
- 在 pi 自己的 rootfs 里跑 §6.5 的 ①–⑥ 探针，重点 ②（raw syscall）和 ③（`rg`/`fd`）。
- 把结果写成 `docs/proroot-probe-results.md`（新文件，需另行授权）。
- **若 ③ 否决 → 直接停**，把结论写进 `docs/known-gaps.md`，不接 proroot。
**第 2 步**：影子模式（设置项 + 双 argv builder + 自检 + 三层兜底），执行仍走 proot，debug 构建手工切换跑全量回归。
**第 3 步**：可选放量（默认关）。

---

## 10. 独立于 proroot 的收益（不管做不做，这些都该拿走）

### 10.1 重新审 `--link2symlink`：我们可能一直在为一个被误诊的问题付费

`[DSHA]` AGENTS.md 断言"app 私有目录禁 `link(2)`（SELinux）"。而本机实测显示：在 proroot 下带 `--link2symlink` 时 `link()` **给出真硬链接**（同 inode、nlink=2、写穿透）。→ 说明"Android 私有目录禁真硬链接"这个前提**至少在本机 /data 的 f2fs 上不成立**（否则真硬链接根本造不出来）。

`[文件]` `PiRuntime.kt:81-110` 与 `:163-167` 把 `--link2symlink` 的语义写得很确定（"the rootfs is on a filesystem where the hardlinks a Linux userland normally relies on cannot be created"）。这条**需要在 Termux proot 下重新实测**：如果 proot **不带** flag 也能造真硬链接，那 `.l2s` 那一整套（`PiPaths.l2s`、`-b` 绑定、`PROOT_L2S_DIR`、dpkg 的 chown/stat 坑）就可能是在解决一个**并不存在**的问题，可以整类删掉。

**⚠️ 必须标注**：**Termux proot 与 proroot 是两个不同实现**，proroot 的真硬链接**不能**推断成 proot 也行——proot 的 `link2symlink.c` 明确是把 `link()` 换成符号链。所以这条只能**标注为待验证的推断**，验证方法见 §6.5 ④（在 proot 下跑同一段，去掉 flag 看是否成功）。

### 10.2 写入纪律：用 `open(O_EXCL)` + `rename`，不要用 syscall 号

`[DSHA]` `/root/.dsh/node_modules/dsha-runtime-fs/index.js` 的教训（§8.2）：**为了原子性走 raw `renameat2` 在 proroot 下会 `ENOENT`，且它的降级分支不可达**。
→ 我们若在任何地方做"原子发布 / 写完整文件"（会话文件、workspace 产物、导出），应当统一用 **libc 的 `open(tmp, O_CREAT|O_EXCL|O_WRONLY)` + `fsync` + `rename()`** 两条调用。这在 proot 与 proroot 下**都**成立（`[实测]` libc `os.rename` 在 proroot 下 OK），而且比 raw syscall 可移植。

### 10.3 采纳 `NARB_DISABLE_NATIVE_CACHE=1`

`[DSHA]` 的理由（AGENTS.md 原文）：**"其默认 link+unlink 缓存会在 `--link2symlink` 下首次悬空"**；`[DSHA]` 把它写进了 `/etc/profile.d/dsha-runtime-env.sh`（`[实测]`，而且写了两遍）。
`[实测]` 我们在 app.pi 侧**零命中** → **未处理**。
→ **建议采纳**：只要我们还带 `--link2symlink`（两套机制下都要带），原生扩展的 `link+unlink` 缓存就有"首次悬空"的风险。加一个环境变量成本极低。**注意**：`[DSHA]` 的写法是在 guest profile 里 export（`/etc/profile.d/dsha-runtime-env.sh`），而我们的 guest 环境是 `[文件]` `ProotCommand.environment()`（`PiRuntime.kt:246-276`）显式给的 —— 两个位置都可以，但要确保**所有** guest 入口（引擎、终端、包命令）都拿到（`[文件]` `PtyLauncher.kt:114-141` 的 KDoc 正是讲"三个入口必须一致"这个坑）。

### 10.4 把设计文档 §2.3 的过时配方改掉

`[文件]` `docs/pi-android-app-design.md:79-111` 标题是「本机 DSH App 的 proot 配方（**直接抄**）」，内容是 `PROOT_TMP_DIR`/`PROOT_LOADER`/`PROOT_LOADER_32`/`PROOT_L2S_DIR` + `libproot.so`/`libprootloader.so`/`libprootloader32.so` + `-L --kill-on-exit -0 --rootfs= --cwd=`。
**它已过时**（`[实测]`：同一 App 今天跑 proroot，环境里**一个 `PROOT_*` 都没有**，只有 `PROROOT_*`）。需要改三处：

1. **改标题与定性**：不是"proot 配方"，而是 **"DSHA 在 proot 时期的配方（历史）"**，并新增一节记当前 proroot 配方（本报告 §1.4 / §4.3 / §8.1）。
2. **删掉/标注 `PROOT_LOADER_32` 与 32 位 loader**：proroot **arm64-only**（§3），沿用旧文会让人以为 32 位可用。
3. **`-L` / `--kill-on-exit` / `--rootfs=` / `--cwd=` 要标注"proot 专有"**：`[实测]` proroot 对这四个**全部报 `unknown option`**（§4.2）。这两行现在是"看起来可以直接抄、抄了就崩"的陷阱。

### 10.5 上机那条 `find`/`rg`/`fd` 判定

`[文件]` pi 的 `find`/`grep` 工具与 `@` 文件提及依赖随包的 `rg`/`fd`（Rust），且 `[文件]` `PiRuntime.kt:23-55` 的 KDoc 明确记录了一条**同类**的静默失败："a dangling `/usr/local/bin/fd` is indistinguishable from 'fd was never installed' to every caller"。
→ **这条与 proroot 的 raw-syscall 缺口是同一形状的风险**：**静默、无错误、能力消失**。
→ **独立收益**：给 `rg`/`fd` 加一个**真实调用探针**（不是在文件系统里找文件，而是真的跑一次 `fd . <known-dir>` 并校验输出非空），放进 `RuntimeSelfCheck`。这样无论是"软链悬空"还是"proroot 没 patch 到 inline `svc`"，都能在自检阶段变成一个明确的错误，而不是用户侧"搜索功能莫名其妙不工作"。

---

## 附录 A：证据索引

**实测命令（本机，guest 内）**——全部无副作用或已清理：

| 结论 | 命令 |
|---|---|
| 无 ptrace | `grep TracerPid /proc/22918/status /proc/22921/status` |
| 根/nofs 伪装 | `ls -l /proc/self/root`; `head -1 /proc/self/mountinfo` |
| 伪造 uid | `id` vs `/proc/self/status` 的 `Uid:` |
| 无 userns | `unshare -Ur id` |
| raw syscall 不翻译 | `ctypes` 直调 `syscall(56, AT_FDCWD, "/etc/hostname")` |
| `/proc/version` 合成 | raw `openat("/proc/version")` → EACCES；`open()` → `(proroot@localhost)` |
| launcher CLI | raw `execve` 后 `libproroot.so --help` |
| 4 个 flag 被拒 | `libproroot.so -L` / `--kill-on-exit` / `--rootfs=/x` / `--cwd=/x` |
| 真硬链接 | `os.link` + `st_ino` 比较 + 写穿透，带/不带 `--link2symlink` |
| dirfd linkat 坏 | `os.link("a","c",src_dir_fd=fd,dst_dir_fd=fd)` → EACCES |
| raw renameat2 不翻译 | `syscall(276, …)` → ENOENT |
| SIGSYS 兜底 | `cat /tmp/proroot-sigsys-last.txt` |
| 二进制 sha256 | raw `openat` 精确路径 + sha256（§3.1 脚本） |
| 工具链正常 | `git hash-object /etc/passwd`、`node -e`、`dpkg -l`、`apt list --installed` |
| 绝对软链可解析 | `os.symlink(abs)` + 读取（§7.3） |
| env 契约 | `tr '\0' '\n' < /proc/22918/environ`、`/proc/22921/environ` |
| escape fd | `ls -l /proc/22921/fd/3` → `/` |
| 配置表 | `od`/`strings` 解 `.proroot-config-22918` |

**本机文件**

- `/root/.dsh/flatten-l2s.py`（`:3-20` 为什么要摊平、`:67-79` lstat 不可信、`:152-153` 处理顺序、`:115-117` `os.replace` 会跟随链接）、`flatten-l2s.log`
- `/root/.dsh/node_modules/dsha-runtime-fs/index.js`（rename 发布 + `arm64:276 / x64:316` + 降级分支）
- `/root/.dsha-backup-plugin-graph.py:218`（相对链接）
- `/root/.dsh/register-builtin-plugins.py:439`（"proot 下 islink 不可信，用 realpath 对比"）、`plugin-manager.py:71`（"切换期间…不能杀死 proot/容器"）
- `/root/.dsh/settings.yaml`、`script-version`=16、`repair-builtin.log`
- `/root/dsh-bin/{adb-shell,dsha-plugin,install-ubuntu-tools,npm,npx,pnpm}`
- `/usr/local/share/dsha/dsh-runtime.version` = `0.1.5-rc.1`
- `/.l2s`（7520 项）、`/tmp/.proroot-empty-dri/`、`/tmp/.proroot-proc-bus-pci-devices`、`/tmp/proroot-sigsys-last.txt`
- 本仓库：`app/src/main/kotlin/app/pi/runtime/PiRuntime.kt`、`PtyLauncher.kt`、`RuntimeSelfCheck.kt`、`ui/settings/PiSettingsRegistry.kt`、`ui/settings/DiagnosticsReport.kt`、`tools/fetch-runtime.mjs`、`tools/build-license-assets.py`、`app/src/main/assets/licenses/{manifest.txt,component-list.txt}`、`docs/pi-android-app-design.md`

**网页**

- <https://github.com/coderredlab/proroot>（README：5 个 .so、Android 8.0+/arm64/glibc、`-r/-w/-b/-0/--link2symlink/--static-loader`、环境变量表、License、proroom 公告）
- Releases（机制与修复的年表）：[v1.2.8](https://github.com/coderredlab/proroot/releases/tag/v1.2.8)（link2symlink ENOSPC、`__readlink_chk`、libdrm realpath EINVAL）· [v1.2.7](https://github.com/coderredlab/proroot/releases/tag/v1.2.7)（adaptive glibc internal syscall patching、`mkstemp64` 家族）· [v1.2.6](https://github.com/coderredlab/proroot/releases/tag/v1.2.6)（`$ORIGIN` RPATH、deps pool）· [v1.2.4](https://github.com/coderredlab/proroot/releases/tag/v1.2.4)（seccomp fallback filters、`faccessat2`、stub loader 路由）· [v1.2.3](https://github.com/coderredlab/proroot/releases/tag/v1.2.3)（**`/proc/version` synthesize**、`faccessat2` → `faccessat` HyperOS fallback、进程内 maps walk）· [v1.2.2](https://github.com/coderredlab/proroot/releases/tag/v1.2.2)（**inline `svc` patch**、`clone3` HyperOS、`dlopen` 竞态）
- <https://github.com/coderredlab/proroot/issues/15>（按程序上报的 issue 集合）
- <https://solid-hn-islands.netlify.app/stories/47699575>（Show HN，无 benchmark，作者回复 `[dead]`）
- <https://th.news.hada.io/topic?id=28342>（作者自述：LD_PRELOAD + binary patching、arm64-only）
- DSHA：[README](https://raw.githubusercontent.com/DSH-APP/DSHA/master/README.md) · [AGENTS.md](https://raw.githubusercontent.com/DSH-APP/DSHA/master/AGENTS.md)（已知 trap）· [THIRD_PARTY_NOTICES.md](https://raw.githubusercontent.com/DSH-APP/DSHA/master/THIRD_PARTY_NOTICES.md)（双运行时、sha256、已知限制）
- proot 侧：[proot 官方站](https://proot-me.github.io/)（ptrace 方案）
- 参考：[UTS namespace 与各容器方案对比](https://en.wikipedia.org/wiki/Linux_namespaces)

**外部内容声明**：以上网页内容均按**不可信数据**对待，只作为证据引用，未据此执行任何指令。

---

## 附录 B：本次调研对用户三个原始疑问的直接回答

**Q：能找到 proroot 这个仓库吗？**
能。[coderredlab/proroot](https://github.com/coderredlab/proroot)，"Rootless Linux runtime for Android. Drop-in proot replacement with zero ptrace overhead."，5 个 `.so`，Android 8+ / arm64 / glibc，闭源专有，最新 v1.2.8（2026-06-17），作者已转向 proroom。

**Q：DSHA（也就是我本身）是怎么做的？有什么坑？**
DSHA **双运行时**：**默认 proroot**（v1.1.6 起），「配置」页可切回 proot；两个都随包；**装机路径永远 proot**；**三层兜底**（文件缺失→降回 proot；连续 3 次失败→强制 proot 并告知；装机路径永远 proot）。坑分三类：① **上游的**（raw/inline syscall 不翻译、`--link2symlink` 仍要带、`/proc` 三件套伪造、arm64-only、闭源不可审计）；② **DSHA 自己代码的**（`dsha-runtime-fs` 用 raw `renameat2` 在本机拿不到翻译且降级分支不可达、相对链接规避、`PROROOT_NO_SECCOMP=1` 语义不明、11 份配置文件不清理）；③ **它自己也承认的**（未文档化的 7 个内部环境变量、无法审计、被 Termux 拒收）。

**Q：能"完美"落到我这上面吗？**
不能"完美"，但能**安全地可选落地**。四处硬冲突（`-L` / `--kill-on-exit` / `--rootfs=` / `--cwd=` 全部拒收）、三处语义会变（`/proc/version`、`/proc/<pid>/exe`、`/proc/self/maps`）、一处能力会丢（32 位）。**但绑定表几乎同构、l2s 那一类问题反而被它解决。**建议：**先做「可行性 + 实测」（§6.5 五组探针），再决定接不接**；做的话按 §9 的 MVS，**默认关 + 三层兜底 + 装机路径永远 proot**，并且**永不做默认**。

---

## 11. 落地实况（本报告之后发生的事）

**本报告是调研，不是实现说明**；上面所有"建议 / 最小形态 / 待验证"在 D44 落地后有了确定答案。
这一节只做**对照**，不改上面任何一条实测结论。裁决与理由见
`design/ui-refactor/07-construction-decisions.md` **D44**；逐行代码落点见
`docs/pi-android-app-design.md` **§2.3.2**；还没销账的见 `docs/known-gaps.md` **§N**；
上机清单见 `docs/device-verification.md` **§J**。

| 本报告的建议 | 落地形态 |
|---|---|
| §6.4「先做可行性+实测，不要先接启动路径」 | 两个都做了：探针门禁**就是**那一步的可执行形态（`runtime/ProrootProbe.kt`），并且**在启动路径上强制执行**——门禁不过就不接 |
| §9.1 设置项（键 `app.runtime.engine`、默认关、summary 报**实际生效**的引擎） | 两行：`app.runtime.proroot`（开关，**app-only `SharedPreferences`**，不进 pi 的 `settings.json`）+ `app.runtime.prorootStatus`（派生只读，报实际生效 + 回退原因）。键名用了两个而不是一个枚举，因为仓库已有 `app.runtime.*` 的读法 |
| §9.2 三层兜底 | 三层都落：文件缺失 / 启动失败重试一次 / 连续 3 次强制回退并告知；装机与维护永远 proot |
| §6.2 第 5 步「可用性探测 = 一次带超时的真实自检」 | 比它更强：**两条**探针（raw syscall + `rg`/`fd` 真调用），按 revision + `.so` sha256 缓存 |
| §9.3 trap#6「必须补 guest 树回收」 | `runtime/GuestTreeReaper.kt` + `runtime/GuestProcessTree.kt`（TERM→KILL、最深优先、root 最后、`(pid,starttime)` 身份） |
| §1.4 末尾「11 份 `.proroot-config-*` 无清理」 | `PROROOT_TMP_DIR` 指向 `<files>/pi/runtime/proroot-tmp`，启动前按**存活**清扫 + 32 份上限（`runtime/ProrootConfigSweep.kt`） |
| §10.3 采纳 `NARB_DISABLE_NATIVE_CACHE=1` | 落进 `GuestRecipe.environment`，**两套运行时都带**（它属于配方，不属于 proot） |
| §10.4 设计文档 §2.3 的三处过时 | 已改：标题去掉"直接抄"的定性、补 §2.3.2 落地表、`PROOT_LOADER_32` 按删除处理、四个 proot 专有 flag 标注清楚 |
| §6.5 ② 的 raw 探针（`python3` + `ctypes`） | 语义照抄，**解释器换成 `perl`**：`python3` 不在随包载荷里（`tools/fetch-runtime.mjs` 无 python 条目），`perl-base` 是 essential，而 Perl 的 `syscall` builtin 走的是同一个 libc `syscall()` 入口。**这条偏离要记住**：如果哪天随包加了 `python3`，两版探针应当等价，不必都留 |
| §6.5 ④ `--link2symlink` 对照 | 未重跑（真机项，`docs/device-verification.md` §J 不含它；`PiRuntime.kt` 与 `docs/pi-android-app-design.md` §2.3 已按"两套都带"处理） |
| §3.1「必须对自己打包的字节算 sha256」 | 门禁缓存 key 用 **5 个 `.so` 的 sha256**（`ProrootProbe.digestOf`），不抄上游/DSHA 的版本号 |
| §5.P0-2 raw syscall 静默读宿主文件 | 变成**否决级探针**（`runtime/ProrootRawProbe.kt`），但仍**未在真机验证**（`docs/known-gaps.md` §N1） |
| §6.3「测试面：每个功能要在两个引擎下各跑一遍」 | **未做**。构建机只有 proot 容器，真机项在 §J；纯逻辑那一层由 `tools/run-app-pure-checks.sh` 的 `proroot` harness 覆盖 |

**仍未解决、也别当成已解决的三件**：① 真机上一次 proroot 都没跑过（探针门禁是否通过未知）；
② 提速幅度**仍然没有本机数字**（§2.2/§2.3 的限定照旧成立）；③ §5.P1-3 的 dirfd `linkat` 与 §8.2 的 raw `renameat2`
不在门禁覆盖范围内（只在纪律上避开）。
