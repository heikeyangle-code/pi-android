# pi-android 真机验证清单（操作 → 预期 → 判据 → 失败含义）

## 0. 来源与局限（先读这一段）

- **这份清单里的每一条都没有在设备上跑过。** 它由**读代码**写成：每条断言的依据是仓库里的符号 + `docs/known-gaps.md` §C（C1 运行时 / C2 终端 / C3 设备桥 / C4 高亮），不是实测。全部条目状态标 `未验`；跑完之后把状态改成 `通过` / `失败`，**并在失败条目后面写下真实输出**。
- 写这份清单时，App 已经"第一次能聊天"了（`known-gaps` §C1 的收尾条件）。**C1 之外的每一条都仍然未验。**
- **一处文档漂移，先说清楚**：`known-gaps` §C2 与 §E8 把工作区终端描述成"手写的 VT 模拟器（3265 行）"。**当前代码不是**——终端用 ConnectBot 的 libvterm（`org.connectbot:termlib 0.0.13`，`gradle/libs.versions.toml` 的 `termlib` 与 `app/build.gradle.kts` 的 `implementation(libs.termlib.android)`），`ui/terminal/TerminalBridge.kt` 的类注释写明"libvterm owns the emulation (libvterm over JNI)"。所以 §C 清单里"转义序列覆盖率"的问法要改成"**libvterm 0.0.13 覆盖到哪**"，而它的 OSC 支持是这个版本限定的（见 C3、C4 两条）。
- 有些条目是**负向判据**（"不支持才是对的"）：终端 OSC 8 超链接、终端内联图片。它们失败不代表缺陷，**渲染出来才要查**——依据是 `PtyLauncher.Spec.environment()` 显式设了 `PI_HYPERLINKS=0` 与 `PI_IMAGE_PROTOCOL=none`。
- 界面路径用 App 的底部导航写法：**聊天 / 会话 / 工作区 / 设置**；`工作区` 的四个分段是 **终端 · 文件 · Git · 任务**（`ui/screens/WorkbenchScreen.kt` 的 `labels`）。

## 0.1 环境基线（写清单时观察到的事实，不是验证结果）

用宿主侧只读查询读到（`GET /app/device`，即这台手机上的 DSH 桥，**端口 3090**）：

```
model=Xiaomi M2011K2C
android=14 (SDK 34)
battery=83% charging=true
network=wifi
storage_free=4.71 GB total=224.58 GB
memory_free=1.19 GB total=7.08 GB
```

- 这正是设计与分析文档 §2 实测的那台机器（Android 14 / SDK 34 / aarch64），`known-gaps` §C1 的目标平台。
- **内存与存储余量要盯**：`memory_free 1.19 GB` 对 `known-gaps` §B10 记录的"含运行时 APK 约 200 MB"与"常驻 < 500 MB"预算是紧的；`storage_free 4.71 GB` 要放 rootfs + Node + pi（设计文档 §附录 B：`node_modules` 440 MB）。
- **本机到底装没装 `app.pi`，从这条通道判不出来**：应用清单要 ADB/Shizuku（`/app/apps` 回 `APP_LIST_UNAVAILABLE`），`/sdcard/Android/data/app.pi` 对 shell 不可读（scoped storage）。所以 A0 仍然要人确认。
- **不要把这个 App 的桥和 DSH 的桥搞混**：本文里的设备桥是 pi-android 自己的，**端口 3175**（`DeviceBridgeRouter.DEFAULT_PORT`，注释里写明"故意不用 3090"）；DSH 那套在 3090。

## 0.2 路径对照表（host ↔ guest，全部来自代码里的 bind）

`<files>` = `/data/user/0/app.pi/files`（`applicationId = "app.pi"`，`app/build.gradle.kts`）。

| guest 侧 | host 侧 | 依据 |
|---|---|---|
| `/`（rootfs） | `<files>/pi/runtime/rootfs` | `PiPaths.rootfs` + `ProotCommand.build("--rootfs=…")` |
| `/root/.pi/agent`（聊天引擎/包命令） | `<files>/pi/.pi/agent` | `PiEngineHost.bootLocked` 的 `extraBinds`（`agentDir` → `guestAgentDir`） |
| `/root/.pi/agent`（**终端**，没有这个 bind） | `<files>/pi/runtime/rootfs/root/.pi/agent` | `PtyLauncher.prepare` 的 `extraBinds` 只有 workspace；`PiPaths.agentBinDir` 的注释写明这个差别 |
| `/workspace/pi/workspaces/workspace-1`（引擎唯一的 workspace bind） | `<files>/pi/workspaces/workspace-1` | `PiEngineHost.bootLocked` 的 `extraBinds`（`workspace.absolutePath` → `guestPathFor(workspace)`） |
| `/workspace/<rel>`（**映射约定**，不是挂载：模型写 guest 路径、App 反查 host 文件时用它） | `<files>/<rel>` | `PiEngineHost.guestPathFor` + `bridge/GuestPathMapping` |
| `/workspace`（终端） | `<files>/pi/workspaces/workspace-1` | `PtyLauncher.workspaceHost`（`WORKSPACE_RELATIVE`） |
| `/.l2s` | `<files>/pi/runtime/rootfs/.l2s` | `PiPaths.l2s`（同一路径被 bind 回它自己） |
| `/tmp` | `<files>/pi/runtime/tmp` | `PiRuntime.tmp` + `ProotCommand.build` |
| `/sdcard`、`/storage/emulated/0` | 设备的共享存储 | `ProotCommand.baseBinds(storage)` |
| `/root/.pi/device-bridge.json` | `<files>/pi/runtime/rootfs/root/.pi/device-bridge.json` | `DeviceBridgeController.publishTokenFile`（写 rootfs 那一份）+ `GUEST_TOKEN_FILE` |
| `/root/.pi/agent/device-bridge.json` | `<files>/pi/.pi/agent/device-bridge.json` | 同上（写 `paths.agentDir` 那一份） |
| `/root/.pi/highlight-bridge.json` | `<files>/pi/runtime/rootfs/root/.pi/highlight-bridge.json` | `service.ts` 的 `defaultTokenPaths()` 第二个路径 |
| `/root/.pi/agent/highlight-bridge.json`（引擎进程视角） | `<files>/pi/.pi/agent/highlight-bridge.json` | 同上第一个路径 + `PiNodeCodeHighlighter.attach` 的候选列表 |
| 设备桥审计日志（**两个 guest 都看不到**，只有 host 与界面能看到路径） | `<files>/pi/device-bridge-audit.log` | `DeviceBridgeController` 的 `DeviceAuditLog(File(paths.home, …))`；`paths.home` 没有任何 bind |

> 设备壳/终端里能不能看到某个文件，取决于那条路径落在哪个 bind 下：**终端只 bind 了 `/workspace`**。所以"终端里 `cat /root/.pi/agent/settings.json` 却什么都没有"是预期的映射结果，不是文件丢失——这一条本身就是下面 C5 要验的东西。

## 0.3 可以先自动跑的（不用人肉点界面）

**已经自动在跑的（每次 boot 都跑）**

1. **`RuntimeSelfCheck`**：`PiEngineHost.bootLocked` 第 1 步解包之后立刻在 guest 里执行 `echo pi-runtime-ok` 穿过 proot（`runtime/RuntimeSelfCheck.kt`）。成功 → 进入聊天；失败 → `BootScreen` 直接显示 `detail` 与 stderr（`ui/screens/BootScreen.kt`），状态是 `NotProvisioned` / `ProotFailed` / `GuestExecDenied` / `Ok` 四者之一。
   → 所以 **A2 是"看首屏"**，不是"跑命令"。
2. **启动步骤进度**：解包那 9 个标签（校验内置载荷、准备存储、解压 Ubuntu 用户态、解压 Node 运行时、安装 rg/fd、安装 git、配置 DNS 与目录、解压 pi 引擎、完成）由 `RuntimeProvisioner.ensureReady` 的 `steps` 逐条回调给 UI。

**可以在 `工作区 → 终端 → Shell` 标签里整段粘贴的探针**（guest 里有 `/opt/node/bin/node`，所以一律用 node，不假设有 `curl`）：

```bash
# A3 硬链接仓库
ls -ld /.l2s
# A4 DNS
cat /etc/resolv.conf
# A5 载荷与工具
command -v node pi rg fd git script bash; node -v; pi --version
# A6 引擎真的在
ls -l /opt/pi/node_modules/@earendil-works/pi-coding-agent/dist/cli.js
# D0 设备桥自检（这是本文里信息量最大的一条命令）
node -e 'const j=require("/root/.pi/device-bridge.json");fetch("http://127.0.0.1:"+j.port+"/app/health",{headers:{Authorization:"Bearer "+j.token}}).then(r=>r.text()).then(t=>console.log(t.slice(0,600)))'
# E1 高亮服务自检
node -e 'const j=require("/root/.pi/highlight-bridge.json");fetch("http://127.0.0.1:"+j.port+"/health",{headers:{Authorization:"Bearer "+j.token}}).then(r=>r.text()).then(t=>console.log(t.slice(0,300)))'
```

> **A7 的戳看不到**，这是映射的结果不是缺陷：`PiPaths.stampFile()` 是 `<files>/pi/runtime/.stamp`，而 guest 的 `/` 是 `<files>/pi/runtime/rootfs` —— 它是 rootfs 的**父目录**，没有任何 bind 把它暴露给 guest。A7 只能在 host 侧看（`adb shell run-as app.pi ls -l files/pi/runtime/.stamp`，仅 debuggable 构建），或者干脆用"第二次启动快不快"当判据。

---

## A. 运行时与引擎（依据 `known-gaps` §C1）

### A0. 装的是哪个 APK，装了没有
**状态**：未验
**操作**：`adb shell pm list packages | grep app.pi`，或系统设置 → 应用 → 找 "pi"
**预期**：出现 `package:app.pi`
**判据**：包名精确匹配 `app.pi`（`app/build.gradle.kts` 的 `applicationId`）
**失败含义**：装的不是这份构建。后面所有条目都会以奇怪的方式失败（例如端口 3175 上什么都没有），先解决这个再往下走。

### A1. 首次启动的解包进度逐条出现
**状态**：未验
**操作**：清数据后首启（或改 `RuntimeProvisioner.RUNTIME_REVISION` 触发重解包），盯着首屏
**预期**：按 `RuntimeProvisioner.ensureReady` 的 `steps` 顺序出现 9 个标签，「校验内置载荷」后面那条会带上**每个载荷的文件名与字节数**（`auditLabel` / `payloadReportBlock`）
**判据**：9 条都出现且最后是「完成」；载荷审计那一条列出 `ubuntu-base.tgz` / `node.tgz` / `pi-engine.tgz` / `ripgrep.tgz` / `fd.tgz` / `git.tgz` 且大小非 0
**失败含义**：`tools/fetch-runtime.mjs` 没有把载荷放进 `assets/runtime/`，或 APK 打包漏了 asset。审计是**先跑、后 wipe**（`ensureReady` 的注释），所以"载荷读不出来"不会毁掉一个能用的运行时——但这台设备将没有运行时。

### A2. `RuntimeSelfCheck` 通过：guest 二进制真的能执行
**状态**：未验（**每次 boot 自动跑**）
**操作**：正常启动 App，走到能发消息
**预期**：不出现 `BootScreen` 的失败态
**判据**：能进聊天并且能收到一次模型回复（光进聊天不够：self check 在起 pi 之前，见 `PiEngineHost.bootLocked` 第 2 步）
**失败含义**：`runtime/RuntimeSelfCheck.kt` 的四种状态各自指向不同根因——`GuestExecDenied` = Android 10+ 的 W^X（`targetSdk` 或 `PROOT_LOADER` 指向 `nativeLibraryDir` 有问题）；`ProotFailed` = SELinux 拦 `ptrace` / loader 缺失；60 秒不返回 = ptrace 被拦。**不要**把它当成"引擎启动失败"去查 pi。

### A3. `.l2s` 存在（**这一份清单里最关键的一条**）
**状态**：未验
**操作**：`工作区 → 终端 → Shell`，输入 `ls -ld /.l2s`
**预期**：打印一行目录（`d...` 开头）
**判据**：退出码 0 且输出以 `d` 开头
**失败含义**：`PiPaths.l2s`（`runtime/PiRuntime.kt`）没被创建，或 `PROOT_L2S_DIR` 指向别处。proot 的 `link2symlink.c` 用 `open(.., O_DIRECTORY | O_NOFOLLOW)` 打开它，**失败直接当成 guest 的 errno 返回**，proot 自己从不创建它。后果是 guest 里**每一次硬链接**都报 ENOENT：`dpkg` 给每个被替换的文件做备份链接，于是 `apt-get install` 死在第 2 个已安装文件上（当时的报告是 `libssl3t64`），而报错说的是两个明明都在的文件"不存在"。

### A4. `resolv.conf` 写进去了，且是那三台 DNS
**状态**：未验
**操作**：Shell 标签 `cat /etc/resolv.conf`
**预期**：三行 `nameserver`，依次 `223.5.5.5`、`8.8.8.8`、`1.1.1.1`，外加 `# Written by pi-android.` 开头的注释
**判据**：三个 IP 都在
**失败含义**：`RuntimeProvisioner.configureGuest()` 没跑到（它在 `ensureReady` 的「配置 DNS 与目录」那一步）。缺了它，glibc 看不见 Android 的按网络解析器，**所有域名解析都会失败而网络本身是好的**——这是 proot 用户态最迷惑的失败模式（设计文档 §13）。

### A5. guest 里的运行时与工具在 PATH 上，而且**真的能跑**
**状态**：未验
**操作**：Shell 标签
```bash
command -v node pi rg fd git script bash; node -v; pi --version
# 「在 PATH 上」不等于「能跑」：下面这四条是真实调用，不是看文件是否存在
rg --version; fd --version
rg -N '^root' /etc/passwd
fd -H '^passwd$' /etc
```
**预期**：七个 `command -v` 都有输出；`node -v` 是 v24.x；`pi --version` 是 `0.85.1`；`rg --version` / `fd --version` 各打印一行版本；两条搜索各有一行命中（`root:…` 与 `/etc/passwd`）
**判据**：`pi --version` 打印版本号，不是 `command not found`；**`rg --version` 与 `fd --version` 退出码都是 0 且输出非空**；**两条搜索退出码都是 0 且输出非空**。只满足 `command -v` 不算通过
**失败含义**：按缺哪个分头查——`node` 缺 → `RuntimeProvisioner.extractNode` 的符号链接（`/usr/local/bin/node` → `/opt/node/bin/node`）；`pi` 缺 → `extractEngine` 没找到 `dist/cli.js`（载荷里没有引擎）；`git` 缺 → `installGit`；`script` 缺 → 见 C1。
`rg`/`fd` 要分三种形状看：① `command -v` 也没有 → 没装（`installTool`）；② `command -v` 有、`--version` 退出码 127 / `No such file or directory` → **软链悬空**（`/usr/local/bin/<tool>` 指向 guest 的 `/root/.pi/agent/bin/<tool>`，而那一份被 agent 目录的 bind 遮蔽了——`known-gaps` §K2 记录的真实断链）；③ `--version` 好但**搜索**读不到 guest 文件（ENOENT / 输出为空）→ 二进制本身能 exec，坏的是它读到的路径，属于"路径翻译"那一层的问题，不是安装问题。无论哪种，**两份目录都必须在**：`PiPaths.agentBinDir()` 与 `PiPaths.rootfsAgentBinDir()`，缺一份就是"某一条启动路径上静默失效"。
> **这一条有自动版**：`设置 → 运行时与诊断 → 导出诊断报告` 的「工具链自检（真调用）」一节会跑同样的调用并逐条报 ✓/✗（`runtime/GuestToolProbe.kt` + `ui/settings/DiagnosticsReport.kt`，超时 20 秒）。本条目是它的上机对照，仍然要跑——自动版只覆盖终端那条解析路径（不带 agent 目录的 bind）。

### A6. 引擎真的 unpack 了
**状态**：未验
**操作**：Shell 标签 `ls -l /opt/pi/node_modules/@earendil-works/pi-coding-agent/dist/cli.js`
**预期**：文件存在且非 0 字节
**判据**：`ls` 成功
**失败含义**：`PiEngineHost.bootLocked` 第 4 步会因此直接 `Boot.Failed("引擎未安装")`，文案里点名 `tools/fetch-runtime.mjs`。**如果连聊天都能用，这条必然是过的**——它是给"换了引擎版本之后"回归用的。

### A7. 第二次启动不重解包
**状态**：未验
**操作**：杀掉 App 再启动，盯首屏时间
**预期**：几乎立刻进聊天，看不到 9 步解包
**判据**：`RuntimeProvisioner.isStampCurrent(revision)` 命中（`<files>/pi/runtime/.stamp` 里的 revision 与 `RuntimeProvisioner.packagedRevision` 一致）
**失败含义**：stamp 比对失败 → 每次都 wipe+重解包（用户每次启动等几十秒，且 `wipe()` 会删掉整个 `runtime` 树）。这时要查 `packagedRevision` 的输入（`assets/runtime-revision.txt` 与载荷指纹）。

---

## B. apt 与硬链接（§C1 的判据；`known-gaps` 里"修法是 `PiPaths.l2s`"的实证）

### B1. `apt-get install` 能升级**已安装**的包（不需要 `-d` + `dpkg-deb -x` 绕过）
**状态**：未验
**操作**：Shell 标签
```bash
apt-get update && apt-get install -y --reinstall python3
```
（`python3` 只是报告里用的例子；任何 base 里**已经装着**的包都行）
**预期**：正常走到 `Setting up python3 …`，退出码 0
**判据**：没有 `unable to make backup link of './usr/...' before installing new version: No such file or directory`
**失败含义**：`PiPaths.l2s` 那条硬链接修复没生效（见 A3）。**注意区分**：`-d`（只下载）+ `dpkg-deb -x` 手工展开是**绕过**，能成功也不能算这条通过——它验的是 dpkg 的备份链接能不能做。

### B2. 硬链接本身能用（A3/B1 的最小复现）
**状态**：未验
**操作**：Shell 标签
```bash
cd /tmp && echo hi > a && ln a b && ls -li a b && cat b
```
**预期**：`a` 与 `b` 打印**同一个 inode 号**；`cat b` 输出 `hi`
**判据**：两个 `ls -li` 的第一列相同且 `cat` 成功
**失败含义**：同一个根因（`.l2s` / `link2symlink`）。`--link2symlink` 是 proot 在这台设备上做硬链接的唯一手段（`ProotCommand.build` 的第一个 flag）；它坏了则 B1 必坏。**注意**：inode 号相同这一判据只在 link2symlink 的模拟下成立——若设备上文件系统原生支持硬链接，也可能直接通过，那说明修复无关紧要，但 A3 仍应存在。

### B3. 网络真的通（DNS 生效）
**状态**：未验
**操作**：Shell 标签 `apt-get update`（或 `node -e 'fetch("https://registry.npmjs.org/-/ping").then(r=>console.log(r.status))'`）
**预期**：`apt-get update` 打印 `Get:` / `Hit:` 行，不出现 `Temporary failure resolving`
**判据**：至少一个源成功
**失败含义**：A4（resolv.conf）或设备本身无网。`apt-get` 的源是载荷里就有的（ubuntu-base 的 `/etc/apt/sources.list`），除非被 wipe 掉。

### B4. 去掉 `--link2symlink` 的实验（**只能由出包方跑**；六条全过就把 l2s 那一整类隐患销账）

> **proroot 落地之后先看这一句**：下面六条是 **proot 专属**的对照实验，**不要**把它推广到 proroot ——
> proroot 下这个 flag **同样必须带**（去掉即 `link()` `EACCES`，`docs/proroot-research.md` §5.P1-3），
> 区别只是 proot 用它做**符号链模拟**、proroot 给出**真硬链接**（同 inode、写穿透）。
> 也就是说：这次实验最多能把 **proot 侧**的 l2s 隐患销账，proroot 侧要单独验（§J）。
> 反过来，**`PiRuntime.kt`/`ProrootCommand.kt` 里这个 flag 两边都留着**是当前裁决，见 `07-construction-decisions.md` D44。
**状态**：未验 —— **本构建容器跑不了**：`aapt2` 只有 x86-64 版本、容器是 aarch64，打不出 APK，所以这一条要出包方带一个改了 argv 的 **debug 包**上机。
**为什么要试**：`docs/proroot-research.md` §5.P1-3 在本机实测到 **proroot 下的 `--link2symlink` 给的是真硬链接**（同 inode / `nlink=2` / 写穿透），而 proot 给的是 `.l2s` 符号链模拟 —— `--link2symlink` 正是 `.l2s` 那一整类隐患的根源（悬空链、`tar` 撞 ELOOP、dpkg 备份链接 ENOENT；五条待办见 `07` D42）。**但 proroot 与 proot 是两个实现**，那一份实测不能直接推断本 App 用的这一套，只能上机量。
**准备**（只改一处，且只在 debug 包里）：
1. `runtime/PiRuntime.kt` 的 proot argv 去掉 `--link2symlink`（`:210` `argv += "--link2symlink"`）。
2. 同文件的 `PROOT_L2S_DIR`（`:259` `put("PROOT_L2S_DIR", paths.l2s.path)`）与那条把它绑回自身绝对路径的 `-b`（`:216` `argv += listOf("-b", "${paths.l2s.path}:${paths.l2s.path}")`）一并注释掉 —— 这个 store 只为该 flag 存在，留着既没用又会误导下一个人。
3. 打 debug APK。其它什么都不要动；`PiPaths.l2s`（`:110`）的 `mkdirs()` 留着无害（它只是建个空目录）。
**操作**（App `工作区 → 终端 → Shell` 逐条跑。① 之前先清干净探针目录，否则复用会把 `EEXIST` 误读成失败）：
```bash
# ① 硬链接本身
mkdir -p /root/hl && echo hi > /root/hl/a && ln /root/hl/a /root/hl/b && stat -c '%h %i %F' /root/hl/a /root/hl/b
# ② 写穿透
echo two > /root/hl/b && cat /root/hl/a
# ③ 跨文件系统：必须 EINVAL，且**不回退**成拷贝
ln /root/hl/a /sdcard/hl-test
# ④ dpkg 的备份链接（历史上真正炸过的那条）
apt-get install --reinstall -y ca-certificates
# ⑤ npm 的 link+unlink 缓存路径
npm install --prefix /root/.pi/agent/npm --no-audit --no-fund is-number
# ⑥ git 的硬链接与 gc
git init /root/g && cd /root/g && echo x > f && git add . && git commit -m t && git gc
```
**两个计数**（跑前、跑后各一次；`<rootfs>` 见 §0.2 的路径对照表 = `<files>/pi/runtime/rootfs`）：
```bash
ls <rootfs>/.l2s | wc -l
```
**预期**：① `nlink=2`、两个 inode 相同、`%F` 都是 `regular file`；② 打印 `two`；③ 报 `Invalid cross-device link`（EINVAL）**且没有文件被创建**（不回退成拷贝或符号链）；④ 走到 `Setting up ca-certificates …`、退出码 0、**不出现** `unable to make backup link of './usr/...' before installing new version: No such file or directory`；⑤ 退出码 0；⑥ 四条命令退出码都是 0，`git gc` 不报错。
**判据**：六条全过 **且 `.l2s` 计数前后不变**（不再增长 = 这个 flag 的痕迹彻底消失），**且下面那组对照能复现现状** → 去掉 flag，并删掉 `.l2s` 整套管道（argv 那一行、`PROOT_L2S_DIR`、`-b`、`PiPaths.l2s`、以及 A3 这条验单），把 `07` D42 的五条 l2s 待办销账。
**对照**（必做，否则"六条全过"也可能是探针没打中）：把 `--link2symlink` 加回去（`PROOT_L2S_DIR` 与 `-b` 一起恢复），重跑 ① —— 应看到 `nlink=1` 且 `%F` 是 `symbolic link`，也就是现状。
**失败含义**：④ 或 ⑥ 失败（dpkg/git 报 ENOENT、ELOOP 或权限错）→ **保留 flag，实验作废且零损失** —— 对照那一步顺手验证了现状是好的，这一条不产生任何待修项。只有 ③ 失败（跨文件系统时回退成了拷贝）另判：那是"少了安全性"而不是"少了功能"，**不要**算作通过。

---

## C. 终端（依据 `known-gaps` §C2；注意 §0 的漂移说明）

### C1. 终端起得来，且 `script(1)` 在
**状态**：未验
**操作**：工作区 → 终端（默认落点）→ 看首屏
**预期**：出现 bash 提示符；**没有**那句"工作区终端需要 guest 里的 util-linux script(1)…"横幅
**判据**：无横幅 + 能执行 `echo ok`
**失败含义**：`PtyLauncher.probe()` 探测 `script` 失败 → `BANNER_MISSING_SCRIPT`，随后 `MISSING_SCRIPT_FALLBACK` 把终端降级成普通管道，全屏程序（pi TUI）会显示异常。修法是 `ubuntu-base` 载荷里包含 util-linux。顺带验第二条横幅：`-e` 支持（`BANNER_NO_EXIT_STATUS` 出现时，命令退出码不会上报）。

### C2. 真彩（24-bit）能画
**状态**：未验
**操作**：Shell 标签
```bash
printf '\033[38;2;255;100;0mTRUECOLOR\033[0m\n'
```
**预期**：`TRUECOLOR` 显示为橙色（不是近似色、不是转义序列原文）
**判据**：颜色是 `#FF6400` 附近的橙
**失败含义**：`TerminalBridge` 的 libvterm 解析 SGR 38/48 24-bit 失败，或渲染层没取 cell 自己的 RGB。环境侧已经铺好：`PtyLauncher.Spec.environment()` 对 pi TUI 设 `COLORTERM=truecolor`（pi 自己检测不出真彩，因为它从 TERM/TERM_PROGRAM 猜，而父进程是 Android App）。

### C3. OSC 8 超链接：**预期不支持**
**状态**：未验（负向判据）
**操作**：Shell 标签
```bash
printf '\033]8;;https://example.com\033\\example\033]8;;\033\\\n'
```
**预期**：`example` 只是普通文本（可点击才是意外）
**判据**：不出现可点击链接
**失败含义**：**渲染成链接才要查**。`PtyLauncher` 对 pi TUI 显式设 `PI_HYPERLINKS=0`，注释写明原因：termlib 0.0.13 的 OSC 解析只处理 52/133/1337，URL 扫描在更晚的版本里，而那个版本的 Kotlin metadata 这个项目的编译器读不了（见 `gradle/libs.versions.toml` 的 ceiling 注释）。**别把"没有超链接"记成缺陷**；要记录的是"OSC 52（剪贴板）能不能用"——那个库是实现了的。

### C4. `pi TUI` 标签能起来（100% 兜底的前提）
**状态**：未验
**操作**：终端 → 新标签 → `pi TUI`；看到界面后按 `esc` 退出
**预期**：pi 的 TUI 全屏绘制正常，输入框可打字，`esc` 能退
**判据**：不是一片空白/花屏，且能退出
**失败含义**：这条挂了就等于 `known-gaps` §D3"TUI-only 扩展需要走终端标签页"的退路也挂了。按现象分：空白 → C1（script 缺失）或 C2；花屏/尺寸错 → 下面 C6；提示 `PI_TUI_ESC_TIMEOUT` 相关吞键 → `Spec.environment()` 里那个 150ms（pi 在管道下等孤立 ESC 的窗口）需要调。

### C5. `pi TUI` 看到的是**哪一份** agent 目录（高价值、预期会暴露映射问题）
**状态**：未验
**操作**：先在聊天页确认自己**有历史会话**（会话列表里能看到几条）；然后 Shell 标签
```bash
ls -la /root/.pi/agent
ls -la /root/.pi/agent/sessions 2>&1
```
**预期（按代码推导）**：`/root/.pi/agent` 是 **rootfs 那一份**（终端没有 agent-dir 的 bind，见 §0.2），所以：
- **有** `bin/`，也可能有 `auth.json` / `models.json`——`PiCredentialService` 把这两个**两份都写**（`file = authFile(agentTruthDir)`、`mirror = authFile(mirrorAgentDir)`，`AgentLayout` 的类注释把 `agentTruthDir` 读作"rootfs 副本"）；
- **没有** `sessions/`：那是引擎按 `--session-dir /root/.pi/agent/sessions` 写的，只落在 durable 那一份。
**判据**：**聊天页里有会话，而 `ls /root/.pi/agent/sessions` 报不存在**（要更硬的话：host 侧 `adb shell run-as app.pi ls files/pi/.pi/agent` 能看到 `sessions/`，而 guest 这份没有——**仅 debuggable 构建**）
**失败含义**：如果实测如此，那么 `pi TUI` 是在一个**与聊天页不同的 agent 目录**里跑 pi：凭证可能碰巧在（两份都被写过），**会话与引擎写的设置不在**。`PiEngineHost.migrateGuestAgentDir()` 只搬一次（marker 在 durable 侧），`RuntimeProvisioner.wipe()` 之后 rootfs 那份只被 `configureGuest()` 重建为空、再由 `ensureToolsVisible()` 补回 `bin/`——所以两份目录会**越走越远**。修点在 `PtyLauncher.prepare`（补 `layout.agentDirBind()` 那条 bind，与 `PiEngineHost`/`GuestCommand` 对齐），但要同时重新检查 `PiPaths.agentBinDir` 注释里那套"两目录都要有"的约定会不会被 bind 遮蔽。**这是本文里最可能牵出源码改动的一条**；如果实测能看到 `sessions/`，说明我对 `PtyLauncher.prepare` 的 `extraBinds` 读错了，回来改这一条。

### C6. 键盘工具条与 resize
**状态**：未验
**操作**：Shell 标签里按 `esc`/`tab`/`ctrl`（再按 `c`）/四个方向键/`|`；然后点输入框唤起软键盘，再收起
**预期**：每个键都送到 guest（`ctrl`+`c` 能打断 `sleep 100`）；软键盘弹出时终端 resize，收起后不残留半屏
**判据**：`sleep 100` 被 `ctrl+c` 打断；resize 后提示符回到可视区
**失败含义**：工具条 → `ui/terminal/TerminalKeyBar.kt` 的 `TerminalBarKey`（含 sticky ctrl）；resize → `PtyLauncher` 用 `stty rows/cols` + `COLUMNS`/`LINES`（guest 的 pty 报 0 行，见其类注释），另外 `TERMUX_VERSION` **故意不设**，否则 pi 会在高度变化时跳过全量重绘（`packages/tui/src/tui-main-screen.ts`），而这里的高度变化就是软键盘。

### C7. 中文输入与宽字符
**状态**：未验
**操作**：Shell 标签里用输入法打「中文测试」，回车；再 `printf '你好\n'`
**预期**：输入法上屏正常，中文按**双宽**对齐（不叠字、不串行）
**判据**：`echo` 出来的中文占两列
**失败含义**：libvterm 的宽字符/组合字符处理，或 Compose 侧取 cell 宽度的方式。`known-gaps` §C2 把"中文输入法"列为未验证项，这条是它的实体。

---

## D. 设备桥（依据 `known-gaps` §C3）

**共同前提**：设备桥要么由 boot 自动起（`PiEngineHost.bootLocked` 第 3 步），要么在 `设置 → 设备能力 → 设备桥 → 启动设备桥` 手动起。**每条 D 条目开始前先做 D0。**

### D0. 桥在监听，且 `/app/health` 认 token
**状态**：未验
**操作**：
1. `设置 → 设备能力` → 设备桥卡应显示「已监听」+「已监听 127.0.0.1:3175」（或启动按钮变成已监听）；
2. Shell 标签执行 §0.3 里那条 node 自检命令
**预期**：返回 JSON，含 `"service":"pi-android-device-bridge"`、`"version":"2"`、`capabilities` 数组、`accessibilityRunning`、`shellBackends`
**判据**：HTTP 200 且 JSON 里有 `capabilities`
**失败含义**：401 → token 文件读错那一份（两种写法见 §0.2；`DeviceBridgeHttp` 认 `Authorization: Bearer …`）；连不上 → `DeviceBridgeHttp` 的 `socket.bind(127.0.0.1:3175)` 失败（端口被占？`DeviceBridgeController.start` 会返回"启动失败：…"）；`app/health` 404 → 版本不匹配（`BRIDGE_VERSION`）。

### D1. 无障碍服务运行 + 读屏
**状态**：未验
**操作**：设置 → 设备能力 → 无障碍 → 「前往系统设置」启用「pi 设备桥」；回到 App 确认卡片显示「系统无障碍服务：运行中」；然后在聊天里说「读一下当前屏幕」
**预期**：返回控件树文本（带 index 与文本）
**判据**：桥返回的文本里能看到当前界面上的字（例如"设置"）
**失败含义**：`DeviceAccessibilityService.isRunning()` 为 false → 服务没绑上（用户在系统设置里启用了但被 ROM 杀掉）；`/app/ui/dump` 返回 `NO_PERMISSION` + "无障碍服务未运行" → `DeviceBridgeRouter.requireAccessibilityService`；开关开着但服务没跑时，`DeviceCapabilityStore.androidPrecondition` 会给 `NO_PERMISSION` 并给出「前往系统设置」的提示（这条在 §C3.1 刚修过，界面文案应与之一致）。

### D2. 点按 / 滑动 / 输入 / 全局动作
**状态**：未验
**操作**：聊天里依次说「点按文本 X」「向下滑一屏」「在搜索框输入 abc」「按返回」
**预期**：四个动作都在设备上真的发生
**判据**：屏幕内容随之变化（可再用 D1 读屏确认）
**失败含义**：点按/滑动 → `DeviceUiAutomation.tap/swipe`（手势 dispatch）；输入 → `DeviceUiAutomation.input`（`ACTION_SET_TEXT`，API 30+ 有 `submitted` 分支）；返回 → 五个 `GLOBAL_ACTION`。若 dump 能读但点击无效，问题在 `dispatchGesture` 是否被 ROM 允许。

### D3. 截屏（含 API 34 安全窗口限制）
**状态**：未验
**操作**：聊天里说「截个屏」；然后打开一个支付/密码/银行类界面再截一次
**预期**：普通界面返回一张能看的图；安全窗口那次**明确失败并说明原因**
**判据**：普通界面看到图片；安全界面收到"当前界面标记为安全窗口…"这类原因（`ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`，API 34 才有这个常量）
**失败含义**：`DeviceUiAutomation.screenshot` 在 API<30 直接 UNSUPPORTED（本机 SDK 34，所以这条一定走真路径）；失败码映射在 `screenshotFailure`：`NO_ACCESSIBILITY_ACCESS` = 服务没截图权限；`INTERVAL_TIME_SHORT` = 1 秒内截太频。**安全窗口失败是正确行为，不是缺陷。**

### D4. 剪贴板读/写，以及失焦时读限制
**状态**：未验
**操作**：先在别的 App 复制一段文字 → 回到 pi-android 说「读剪贴板」；再说「把 hello 写进剪贴板」，去别处粘贴；最后**切到别的 App** 后（模拟失焦）让 Agent 读一次
**预期**：前台时能读能写；失焦场景读到的是空或明确失败，不是旧值冒充新值
**判据**：写入后在别处粘贴出 `hello`
**失败含义**：`DeviceSystemActions.clipboardGet/Set`。Android 10+ 在应用失焦时**禁止**读剪贴板——这是平台限制（`known-gaps` §C3 明确列为未验证项）。若这里出现"读到上一轮的旧内容"，那是 App 侧的缓存 bug，要查 `DeviceSystemActions`。

### D5. 通知（POST_NOTIFICATIONS）
**状态**：未验
**操作**：聊天里说「发一条通知」；Android 13+ 若无权限应按文案被拒
**预期**：通知出现在通知栏，点了回到会话
**判据**：通知栏有那条通知
**失败含义**：`DeviceSystemActions.notify` 在无 `POST_NOTIFICATIONS` 时抛 `NO_PERMISSION`，文案点名系统设置路径。`设置 → 设备能力 → 基础` 现在会显示"系统通知权限：未授予"并给出「授予通知权限」（§C3.1 修的，正是为了这条）。

### D6. Toast / 震动 / TTS
**状态**：未验
**操作**：聊天里分别说「弹个 toast」「震动一下」「朗读这句话」
**预期**：屏幕短提示、一次震动、语音朗读
**判据**：三者都能感知
**失败含义**：`DeviceSystemActions.toast/vibrate/speak`；`VIBRATE` 是普通权限，声明在 manifest 里（`hasVibratePermission`）；TTS 失败要查系统 TTS 引擎是否存在（`speak` 的 timeout 参数）。

### D7. 打开链接 / 分享（后台启动 Activity）
**状态**：未验
**操作**：**在聊天的前台**说「打开 example.com」，再用「分享这段文字」；然后**把 App 切后台**再让 Agent 做一次（例如从前台服务的通知回来）
**预期**：前台能打开浏览器 / 分享面板；后台那次失败时给的是"Android 10+ 限制后台启动界面…"这类明确原因
**判据**：前台成功；后台失败但**有原因**
**失败含义**：`DeviceSystemActions.open/share` 的 `startActivity` 失败分支（文案里点名 Android 10+ 的后台启动限制）。这条正是 `known-gaps` §C3 的"C3 未验项"之一。

### D8. 应用列表与启动（QUERY_ALL_PACKAGES）
**状态**：未验
**操作**：聊天里说「列出已安装的应用」「打开微信」（或任一用户应用）
**预期**：列表**不止 pi-android 自己**；启动成功
**判据**：列表条数明显大于 1，且包含第三方 App
**失败含义**：`DeviceAppActions.list` 只返回本应用 → manifest 的 `QUERY_ALL_PACKAGES` 没生效（`AndroidManifest.xml` 里声明了它，注释写明是为了 API 30+ 的包可见性）；启动失败 → `DeviceAppActions.launch` 的 launch intent 为空。

### D9. 结束应用：区分用户/系统 + 危险确认
**状态**：未验
**操作**：说「结束微信」→ 首次应弹确认；再试着让它结束一个系统应用（例如"系统界面"/设置）
**预期**：用户应用在确认后真的被杀；系统应用/关键进程被**拒绝**并说明
**判据**：用户应用任务从最近任务消失；系统应用请求收到拒绝原因
**失败含义**：`DeviceAppActions.stop`（`requireExactPackage` + 自身包拒绝 + `criticalPackages` 拒绝 + `FLAG_SYSTEM` 拒绝；**它自己不刷新清单**，找不到包时提示先调 `android_apps`）。`KILL_BACKGROUND_PROCESSES` 已声明，但注释写明 OEM 仍可能拒绝——那种情况必须回"平台拒绝"的原话，而不是报成功。若确认框不出现，问题在 guest 扩展的 permission gate（`assets/pi-extensions/pi-android-permission-gate.ts` 的 `android_stop_app` 属 dangerous），属于"没有确认通道就直接拒绝"那一路。

### D10. SAF 目录授权与文件读写
**状态**：未验
**操作**：`设置 → 设备能力 → 存储 → 授权目录` → 选一个文件夹（例如 Documents）→ 聊天里说「在这个目录里写一个 test.txt，然后读回来」
**预期**：写入成功、读回内容一致；卡片上出现该目录名与「撤销」
**判据**：用系统文件 App 能看到该文件
**失败含义**：`DeviceSafStore.add` 的 `takePersistableUriPermission` 返回 false → 该 provider 不支持持久化（卡片会明确提示"系统没有把这个目录的访问权限持久化…"，**别当成写入失败**）；读写失败 → `DeviceSafStore.read/write` 的路径解析（`rootName/relative/parts`，找不到就报 NOT_FOUND 并点名路径）。

### D11. 导出到公共 Download，以及跨安装读回
**状态**：未验
**操作**：说「把这句话导出到 Download」；确认文件出现在系统文件 App 的 Download；再用另一个 App（例如系统文件管理器）修改/新建一个同名文件，再让 Agent `import` 它
**预期**：导出成功（`/storage/emulated/0/Download/<name>`）；**本应用导出的**文件能读回；**别的应用建的**同名文件在 API 33+ 读不回，并给出"只有本应用导出的文件默认可读"这类原因
**判据**：Export 成功 + 文件在 Download 里；第二次 import 要么成功（平台允许）要么是明确原因，**不能是空内容**
**失败含义**：`DeviceSystemActions.export` 走 MediaStore（API 29+ `IS_PENDING` 两段写）或旧式 `WRITE_EXTERNAL_STORAGE`（API≤28）；`import` 在 API 33+ 受 MediaStore 跨应用读限制——这正是 `known-gaps` §C3 列的"跨安装读取 MediaStore 导出文件"。

### D12. 位置 / 传感器 / 电池 / 手电筒
**状态**：未验
**操作**：`设置 → 设备能力 → 位置·传感器·相机`，按卡片上显示的状态点「授予定位权限」/「授予相机权限」；然后聊天里分别说「我在哪」「有哪些传感器」「电量多少」「开手电筒」
**预期**：定位返回经纬度（或明确说"设备定位未开启/缓存为空"）；传感器列出列表；电池返回百分比；手电筒亮
**判据**：手电筒真的亮；定位**要么有坐标要么有明确原因**（不能静默空）
**失败含义**：定位 → `DeviceSystemActions.location`（无权限时点名 `ACCESS_FINE/COARSE_LOCATION`）；手电筒 → `DeviceSystemActions.torch`，**权限检查先于相机服务**（否则 `setTorchMode` 抛 SecurityException 会被读成"这台设备没有闪光灯"）；"没有带闪光灯的相机"这条只有在**权限已授予**时才可信。

### D13. Shell 后端（app uid；Shizuku 未接时的诚实降级）
**状态**：未验
**操作**：`设置 → 设备能力 → Shell`，看「当前 Shell 后端」那一行；聊天里说「执行 id」；再装 Shizuku（可选）后回来授权再看
**预期（无 Shizuku）**：后端显示的是**应用自身身份（uid=10xxx）**，不是"Shizuku（未安装/未运行/未授权）"；`id` 返回 `uid=10xxx`
**判据**：`id` 的 uid **不是** 2000/0，且卡片上说的后端与之一致
**失败含义**：`DeviceShellGuard.active()` 返回第一个 `available` 的后端（`ShizukuShellBackend` → `AppUidShellBackend`）。**§C3.1 刚修过这里**：之前卡片显示的是 `ShizukuShellBackend.label` 的字面值，Shizuku 没就绪时那句话会被读成"当前后端是 Shizuku"。若又出现不一致，就是这两处又分叉了。装 Shizuku 后：`设置 → 设备能力 → Shell → 请求 Shizuku 授权`，然后 `id` 应变成 `uid=2000`（或 root 时 0，卡片会显示"Shizuku 以 root 运行"的警告）。

### D14. 能力关闭时**有明确原因**（不许静默失败）
**状态**：未验
**操作**：把「Shell」组关掉 → 让它执行 `ls /`；把「存储」关掉 → 让它读文件；把「无障碍」关掉 → 让它读屏
**预期**：三次都是**带 code 的拒绝**（`DISABLED`），并且界面（设置 → 设备能力）里那一组显示「已关闭」
**判据**：错误码是 `DISABLED`，不是超时/空结果
**失败含义**：`DeviceCapabilityStore.check` + `DeviceBridgeRouter.withCapability`。这条是设计文档 §21.4 的硬要求（"被禁能力返回明确原因，不静默失败"）。

### D15. 审计日志真的在写
**状态**：未验
**操作**：做完 D1–D14 后，在 `设置 → 设备能力 → 设备桥` 看"审计日志"路径与最近几行（卡上直接列出最后 5 条；点右上角刷新会更新）
**预期**：有本次操作的行
**判据**：新行的时间戳是刚才
**失败含义**：`DeviceBridgeController` 的 `DeviceAuditLog(File(paths.home, "device-bridge-audit.log"))`，`paths.home` = `<files>/pi` —— 它**不在任何 bind 下**，所以两个 guest 都读不到它，只能看界面或 host（`adb shell run-as app.pi cat files/pi/device-bridge-audit.log`，仅 debuggable）。桥没起来时 `auditLog` 为 null，界面上的"审计日志"路径也不会显示（`auditLogPath()`）。

---

## E. 高亮服务（依据 `known-gaps` §C4）

### E1. 扩展侧 HTTP 服务起来了
**状态**：未验
**操作**：Shell 标签执行 §0.3 的高亮自检命令
**预期**：返回 JSON，`data.service` 是 `pi-android-highlight`、`data.version` 是 `"1"`，并带 `data.port` / `data.engineLoaded` / `data.hljs` / `data.registeredLanguages`
**判据**：HTTP 200 且 `data.service` 正确；`data.engineLoaded` 为 true（`located` 只是"模块找到了"，两者别混）
**失败含义**：`assets/pi-extensions/pi-highlight/service.ts` 没被引擎加载（扩展树没装上 → `DeviceBridgeController.ASSET_VERSION` 的手工版本号没提升，见 `known-gaps` §B12/E6）；或端口被占（`PORT_ATTEMPTS=10`，从 3176 起试）；或 token 文件没写到 App 会读的那几个路径（见 E2）。`data.hljs` 为空说明 highlight.js 本体没从扩展树里找到。

### E2. token 文件在每个候选路径上找得到
**状态**：未验
**操作**：Shell 标签
```bash
ls -l /root/.pi/highlight-bridge.json /root/.pi/agent/highlight-bridge.json 2>&1
```
**预期**：至少一个存在，权限是 `-rw-------`（0600）
**判据**：至少一个 `ls` 成功；内容含 `token` 与 `port`
**失败含义**：`service.ts` 的 `defaultTokenPaths()` 按 `PI_CODING_AGENT_DIR` + `$HOME` 推两个 guest 路径；App 侧 `PiNodeCodeHighlighter.attach` 看**三个** host 路径（rootfs 的 agent 目录、durable 的 agent 目录、rootfs 的 `/root/.pi`）。**两个 guest 路径落成哪些 host 路径取决于绑定**（§0.2），所以"某一侧有、另一侧没有"是正常的。两边都缺 → 引擎没起或扩展没加载。

### E3. 大代码块降级（不请求、不卡）
**状态**：未验
**操作**：让模型输出一个 **> 400 行**（或 > 64 KB）的代码块，再输出一个普通 40 行块
**预期**：两块都正常显示；大块的着色是**朴素**的（无高亮）且**不卡顿**
**判据**：滚动流畅；大块没有半截着色的残留
**失败含义**：`PiNodeCodeHighlighter` 的 `MAX_CODE_CHARS`（64 KB）/ `MAX_CODE_LINES`（400）以外就不发请求，这是设计（pi 在终端里没有上限，因为它是同步的）。若大块**卡住 UI**，那是请求发出去了并占住了主线程——要查 `CALLER_WAIT_MS`（750ms）与 `BoundedWorkers`。

### E4. 引擎没起来时不拖慢代码块
**状态**：未验
**操作**：先让引擎停在未启动/启动中（冷启动那几十秒），期间打开一个含代码块的历史会话
**预期**：代码块以**朴素样式**立刻显示，没有长时间空白
**判据**：首次绘制不含等待；`PiHighlightClient` 的超时（连接 100ms / 读 150ms）之后落到朴素样式
**失败含义**：`PiHighlightClient` 的超时被改大，或 `PiNodeCodeHighlighter` 的"永不抛异常"契约被破坏（它 `catch (Throwable)` 返回空 spans，就是为了这个）。

---

## F. 扩展与包管理

**共同前提**：界面入口是 `设置 → 扩展`（`ui/settings/SettingsHome.kt` 的 `PiPackagesEntryRow`；宿主 `packages/PiPackagesHost.kt`）。安装是**批量任务**不是 pty（`packages/GuestCommand.kt` 的类注释），超时 10 分钟。

### F1. `pi list` 能读、且与引擎看的是**同一个** agent 目录
**状态**：未验
**操作**：`设置 → 扩展` 打开一次（它会跑 `pi list`）
**预期**：列出已安装的内置扩展（至少 `pi-android-bridge`、`pi-android-permission-gate`、`pi-highlight`），不报错
**判据**：列表里能看到这三个内置名
**失败含义**：`GuestCommand.bindList()` 的断言（它 `check` 了 guest 的 agent 目录绑定与引擎一致）——这条断言存在的理由正是历史上"`pi install` 写到引擎不读的目录、打印 `Installed` 却什么都没有"。断言失败会带那句中文说明。

### F2. `npm:` 源能装
**状态**：未验
**操作**：扩展页的安装框输入 `npm:@scope/name`（任一真实小包），安装
**预期**：进度输出、结束时 `Installed npm:…`，列表里出现
**判据**：安装后 `pi list`（或界面刷新）能看到它；**重启引擎后仍然在**
**失败含义**：npm 网络 → B3/E；写入位置 → F1 的绑定断言；`NO_COLOR`/`CI=1` 让 npm 输出可解析（`GuestCommand.ENV`），输出格式变化会让解析失败但安装其实成功——**区分"没装上"与"没解析出来"**。

### F3. `git:` 的 HTTPS 源能装
**状态**：未验
**操作**：安装 `git:https://github.com/<owner>/<repo>`（任一小的公开扩展仓库）
**预期**：`git clone` 成功
**判据**：界面刷新后列表里出现它；**重启引擎后仍然在**（安装落在 durable 那份，见 F1 的绑定断言；终端看不到 durable 目录，别用 `git -C /root/.pi/...` 当判据）
**失败含义**：证书 → `ProotCommand.environment` 的 `GIT_SSL_CAINFO`/`SSL_CERT_FILE` 指向 `/etc/ssl/certs/ca-certificates.crt`（由 `git.tgz` 安装，`RuntimeProvisioner.installGit`）。**这是设计文档里点名的一条**：pinned base 完全没有 `/etc/ssl`，而 libcurl 的默认目录形式（`<hash>.0`）在只有合并 bundle 的情况下找不到任何东西——所以是**显式路径**在起作用，不是"多此一举"。

### F4. `git:` 的 SSH 源**应当失败**
**状态**：未验（负向判据）
**操作**：安装 `git:git@github.com:<owner>/<repo>` 或 `git:ssh://…`
**预期**：失败，并且原因是 `cannot run ssh: No such file or directory`
**判据**：**失败**才对；成功说明载荷里混进了 ssh（那会带来密钥/known_hosts 的意外面）
**失败含义**：**这不算缺陷**（`RuntimeProvisioner.installGit` 的 KDoc 把它写成"边界，不是 bug"）。要盯的是"失败得对不对"：`GuestCommand.ENV` 设了 `GIT_TERMINAL_PROMPT=0` 与 `GIT_SSH_COMMAND=ssh -o BatchMode=yes …`，所以**必须快速失败**，不能挂住 10 分钟——挂住就是超时路径的问题。

### F5. 本地路径源 + 项目信任弹窗
**状态**：未验
**操作**：把工作区或 `/sdcard` 下的一个扩展目录作为路径源安装；再打开一个**未信任**的项目，触发信任询问
**预期**：路径安装成功；信任询问出现且**不会自动信任**（卡片给出"是/否/未决定"，并显示 `trust.json` 里的原值）
**判据**：信任卡出现前不会自动加载项目扩展；拒绝后扩展确实不加载
**失败含义**：`packages/PiProjectTrustPrompt.kt` + `TrustRepository`/`TrustFile`（`known-gaps` §B7 记录的"信任 UI 曾经挂在没人挂载的屏幕上"）。**没有弹窗但有项目扩展被加载**才是缺陷方向。

---

## G. 凭证与模型

**入口**：`设置 → 模型与凭证`（`ui/settings/PiCredentialScreen.kt`，页面自称"选厂商 → 粘 Key → 扫描 → 勾选 → 保存"）。

### G1. 填 Key 后"测试连接"就是模型列表
**状态**：未验
**操作**：选一个厂商，粘真实 Key，点扫描/探测
**预期**：拉到模型列表（`PiCredentialService.probe` 用"列出模型成功"作为连通性的**证据**，而不是 ping）
**判据**：列表非空且包含该厂商的模型名
**失败含义**：`packages/PiCredentialService.kt` 的 `probe`；网络 → B3；Key 无效 → 厂商侧 401（文案应原样显示厂商消息）。**注意**：这里没有独立的 ping 按钮，别去找。

### G2. 保存写进两处 agent 目录
**状态**：未验
**操作**：勾选模型 → 点「保存」
**预期**：`auth.json`（0600）与 `models.json` 都被写；界面显示 `paths()` 的两个目录（`agentTruthDir` 与 `agentMirrorDir`，`AgentLayout` 的类注释说明前者其实是"rootfs 副本"、后者才是引擎读的那份）
**判据**：**两份都在**——终端能看 `/root/.pi/agent/auth.json`（rootfs 那份），host 侧能看 `<files>/pi/.pi/agent/auth.json`（durable 那份）
**失败含义**：`PiCredentialService` 的 `auth()`/`models()` 把 `file` 指向 rootfs、`mirror` 指向 durable，两份都写。只写一份就会在两类症状里露头：只写 rootfs → 重启/重解包后设置"消失"（`RuntimeProvisioner.wipe()` 删掉整个 runtime 树）；只写 durable → 终端里的 `pi TUI` 看不到凭证（配合 C5 看）。

### G3. 重启引擎后新配置生效
**状态**：未验
**操作**：保存后按界面上的「重启引擎」（会先问一次），然后**新开一轮对话**用刚配的模型
**预期**：新模型可用；页面不偷偷重启（`PiPackagesScreen` 的"no silent restart, no silent trust"）
**判据**：重启询问出现过；重启后用新模型能收到回复
**失败含义**：`packages/EngineRestartCoordinator.kt` + `PiEngineHost.restart`。`models.json` 在 `--mode rpc` 下不会被热读（`PiCredentialService` 的类注释点名了这一点），所以**不重启而"没生效"是预期行为**，要验的是重启后生效。

### G4. 移除 provider
**状态**：未验
**操作**：对已保存的 provider 点移除
**预期**：`auth.json` 与 `models.json` 里的对应项都消失，界面不再显示它为已配置
**判据**：重新进入设置页不再是"已保存过 Key"
**失败含义**：`PiCredentialService.remove`。

---

## H. 图片通道

### H1. markdown 里的内嵌图片（guest 路径）
**状态**：未验
**操作**：让 Agent 在执行 `android_download`（`op="write"`）/写文件后，在回复里用 `![x](/workspace/…png)` 或 `/sdcard/…png` 引用它
**预期**：对话里渲染出图片
**判据**：看到图，而不是一个坏图标/空白
**失败含义**：`bridge/GuestImageBytes` 的 guest→host 映射（§0.2 的表就是它的顺序：bind 映射优先于 rootfs）。它只解 pi 的 `read` 接受的那五种格式（jpg/png/gif/webp/bmp），动图只画第一帧；共享存储上的文件按 App 自己的权限读，**读不到时报告失败，不会假装是空图**。

### H2. 远程 URL 图片：**预期不渲染**
**状态**：未验（负向判据）
**操作**：让模型输出 `![x](https://example.com/a.png)`
**预期**：显示为文本/链接，**不发起网络请求、不出图**
**判据**：不出图，且该行以文本/链接形式呈现（更硬的做法：开飞行模式再发一次，观察**没有**任何"加载失败"过程）
**失败含义**：**出图才是缺陷**。`GuestImageBytes` 的类注释写明：pi 自己从不抓图，而在手机 App 里静默请求模型写下的任意 host 会泄露用户 IP 与该会话被打开的事实，还会给模型一个追踪原语。要验的是这条策略没被后来的改动放开。

### H3. 附件图片发送
**状态**：未验
**操作**：聊天输入区的图片按钮 → 选一张手机里的图 → 发送（可无文字）
**预期**：消息里出现缩略图；模型能"看到"图（例如让它描述颜色）
**判据**：模型回答里描述的内容与图相符
**失败含义**：`ui/screens/ChatScreen.kt` 的选择器 → base64 → `PiImage`；模型没看到 → 走 `rpc/**` 的图片通道（设计文档 §10.5），与 H1 的渲染路径**不是同一条**，别混。

### H4. 超限与不支持的格式要**明确失败**
**状态**：未验
**操作**：引用一张 > 8 MB 的图；再引用一个 `.svg` 或 `.tiff`
**预期**：给出失败原因（超限/无法解码），**不是空白图片**
**判据**：能看到原因文本
**失败含义**：`GuestImageBytes.MAX_BYTES`（8 MB，显示上限，不是厂商上限）与解码器（`BitmapFactory`）。这条是"不许静默失败"在图片路径上的落地。

### H5. 单张图片的大小：0.6 → 0.35（D48 ①）
**状态**：未验（纯逻辑侧已由 `image-size` harness 钉住常量与行高算术；这一条看的是**观感**）
**操作**：在对话里发一张**竖屏截图**（1080×2400 之类，用输入区图片按钮发，或让 `read` 一张手机截图）；再发一张 4:3 的横图
**预期**：竖屏截图**整张可见**（`ContentScale.Fit`，不裁剪）且**画面高度 ≈ 屏高 × 0.35**；横图与改动前**逐像素相同**（占满宽度、按自身比例）
**判据**：
- 竖屏截图：画面高度约 0.35 屏（旧值是 0.6）；卡片（`cardBg` 那块）**与图片同高**，图下面**不再有一大截空白**
  ——旧实现 `aspectRatio(...).heightIn(max)` 只压画面不压行，1080×2400 的图会让**行高顶到整整一屏**；
- 竖屏截图在全宽卡片里**左右留边**且居中：这是"整张可见 + 高度不超 0.35 屏"同时成立的形状，**不是缺陷**；
- 横图/方图（自然高度本来就在 cap 以下）与旧版一致；
- **颜色一个字节没变**：卡片 `cardBg`、边框 `hairline` + `borderMuted@0.5`、占位文字 `toolOutput`。
- 顺手修的那处：**工具卡里只有一张图**时，图片上方应有一个 `PiSpacing.tiny` 的间距（与多图网格、与其它块一致）；
  旧代码把调用者的 `modifier` 丢了，单图会贴住上一行。
**失败含义**：`SINGLE_IMAGE_MAX_HEIGHT_FRACTION` 没生效（看行高是否 = 宽度/比例，那说明又回到 `aspectRatio` 定尺寸）；
或单图分支又把 `modifier` 丢了。

### H6. 上滑时图片不再"从底部闪现"、行高不再变化（D48 ②）
**状态**：未验（Compose 在本机编不了，只能上机看）
**操作**：
1. 上下往复滑动，让**同一张没渲染过的**竖屏截图从底部**第一次**进入视口 —— 分别用**慢速**和**快速甩动**各来一次；
2. 让那张图**滑出屏幕再滑回来**（第二次进入组合）；
3. 一次快速甩过 **3~4 张连排**图片（多图网格那种消息）。
**预期**：图片进入时**行高自始至终不变**：一块空的 `cardBg` 卡片在图片解码完成**之前**就已是最终尺寸，
图片是"在已有的框里出现"，**不是**把下面的内容推走
**判据**：
- **慢滑与快甩观感一致**（旧实现只有慢滑看起来正常：慢滑时解码早就完成了）；
- 快速甩动**不卡**、不出现"空白卡片停很久"（并发解码被 `piImageDecodeGate` 限到 2，队列不会超过屏幕上的可见张数）；
- **可量化做法**（不想靠肉眼时）：录屏 60fps，逐帧看图片所在行的**上边界/下边界像素位置** —— 同一张图从进入到渲染完成，边界不动；
- **反面对照 ①**：一张**故意解不出来**的图（例如把 png 截断一半再 base64 塞进消息）显示「图片 N / image/png」占位，
  且**不是**在解码过程中闪一下才出现（`CellImage.Pending` 期间不画标签）；
- **反面对照 ②**：`设置 → 关于 → 诊断报告` 里没有 OOM / 解码失败堆积；连甩 3~4 张图后内存不持续上涨（同时解码数被限住）。
**失败含义**：`naturalImageAspect` 没读到报头 → 退回 4:3 且**不会再变**（观感是"图被套了个 4:3 信箱"，
说明读头失败，查格式支持：只认 png/jpeg/gif/webp/bmp）；行高仍来自解码后位图 → 说明单图尺寸又被写成
`decoded.width / decoded.height`；gate 没接上 → `image-size` harness 的 wiring 检查会先红。

---

## I. 转录渲染窗口（F34；`ui/screens/ChatScreen.kt`）

> 这三条来自 F34 与「大会话打开」这两批实现（`applied (uncommitted)`），**写清单时只读了代码，没有在设备上跑过**（I0 的纯逻辑侧例外：`session-replay-cost` harness 已经跑绿，并且**顺手量出了那个 `readBefore` 的早停 bug**）。三条都是"看起来对但没人看过"的假设，而且失败时用户会直接看到。

### I0. 大会话 / 带图片的会话**能打开**，而且首屏代价不随历史增长
**状态**：未验
**为什么要单列**：这一条修的正是用户报的两个现象 ——「两张图片就进不去聊天历史」和「纯文本的旧会话也要一两秒」。根因是打开会话走 `get_entries`，pi 没有分页（`modes/rpc/rpc-mode.ts:638-648`），整条会话塞在**一条** JSONL 记录里，所以代价是 O(全部历史)，而且**超过 `JsonlFramer.DEFAULT_MAX_RECORD_CHARS`（8 MiB）时整条记录被丢弃** —— 那不是"慢"，是什么都没有。现在打开走 `SessionFileReader.readTail`（读那条会话文件本身，固定字符预算），往上滚走 `readBefore`。**纯逻辑侧已由 `session-replay-cost` harness 钉住**（窗口化读取与全文件读取逐条等价），本条目是它的上机对照。
**操作**：
```bash
# 造一条"图片超标"的会话：在会话里连发两张照片（手机截图各约 2~3 MB），退出聊天，再从会话列表点进去
# 造一条"很长"的会话：pi 里连问 40 轮以上，或直接用一条已有的长会话
```
1. 点进那条**含两张照片**的会话；
2. 点进那条**很长**的会话，数第一帧出现的时间；
3. 在长会话里一路往上滚，或点顶部「加载更早的 N 条」，连点 3 次；
4. 打开**会话树**覆盖层（树/分支那个入口），看它的条目数；
5. 用一条**混合类型**的会话（含 compaction / branch summary / 模型切换 / 思考等级切换 / 图片）逐条扫一遍。
**预期**：① 含两张照片的会话**能打开**，直接看到最后几条（不再空白、不再卡在加载）；② 长会话第一帧很快出现，**而且时间与历史长度无关**（一条 1 MB 的会话和一条 20 MB 的会话，首屏感觉应当差不多）；③ 上滚能逐段把更早的内容接上，不重复、不跳号、不闪；④ 树覆盖层的条目数与 pi 侧（终端里 `/export` 或直接数 `.jsonl` 的非 header 行数）**一致**；⑤ 13 种块都在，没有哪一种因为窗口化读取而消失。
**判据**：① 是关键项 —— 之前是**必然失败**，所以"能看到"就有信息量；② 不要求某个具体毫秒数（本容器量不了手机的引擎往返），要求的是**比较**：给同一条会话加长 10 倍，首屏时间不跟着长 10 倍；③ 顶部「…N 条」计数逐次变小，`reachedStart` 到了以后那一行消失；④⑤ 逐条对照。
**失败含义**：按形状分——
- **①仍然打不开** → `replayHistory` 的磁盘分支没走通（`resolveSessionFile()` 返回 null，或 `readTail` 返回 null / `complete=false`），于是回落到 `replayHistoryOverRpc` 那条老路，而它在 8 MiB 上仍然是丢弃。看日志里的责任符号是 `readTail` / `complete` / `resolveSessionFile`。
- **②仍然随历史变慢** → 窗口预算没生效：`HISTORY_WINDOW_CHARS`（8 MiB）/ `HISTORY_WINDOW_ENTRIES`（4000）在这条会话上等于"整条"，或者 `seedHistory` 之后又做了一次全量投影。
- **③上滚接不上 / 早停** → `readBefore` 的 `reachedStart`：这条改动之前它只看"是否读到字节 0"，**不看这一窗口有没有截留**（`capped` = 条目上限截断、`budgetStopped` = 字符预算截断）。后果正是"往上滚一次就声称没有更早的历史"，而 `session-replay-cost` 的 `mixed` 夹具实测到 14 条只走回 2 条。责任符号：`SessionFileReader.readBefore` 的 `reachedStart`。
- **③重复条目** → 边界偏移算错：`readRange` 的 `firstOffset`（窗口起点必须落在**行首**，否则下一个窗口会再交付一次同一行）。
- **④树覆盖层缺条目** → 那是另一条读路径（`SessionFileReader.readEntries`，流式扫描 + 逐条 `sanitize`），它**故意**在遇到超长行时返回 false 让调用方回落 `get_entries`；返回 false 却被当成"扫完了"就会少条目。
- **⑤某种块消失** → 窗口化读取漏了该类型，或 `Window.complete=false` 时的回落没接上。`session-replay-cost` 的 `mixed` 夹具列了全部 14 种（含一个"更新版 pi 才会有的"未知类型，未知类型必须保留）。

### I1. 点「加载更早」时滚动位置**不动**（前插保锚点）
**状态**：未验
**操作**：打开一个超过 50 行的会话，滚到中部；点列表顶部的**「加载更早的 N 条」**（或一路滚到顶触发自动加载），连点 3 次
**预期**：视口**停在原地**，新加载的行出现在**上方**（要继续上滑才看得到）；没有跳动、没有被拉回顶部、也没有被拉到底部
**判据**：点击前后，屏幕上同一行（记住它的一小段文字）仍在同一高度（±1 行）；三次都成立；顶部那行的「…N 条」数字逐次变小
**失败含义**：`ChatScreen.kt` 的 `renderedItems = visibleItems.takeLast(renderWindow)` 前插后，`LazyListState` 没有按 item key 保住锚点 —— 责任符号是 `renderedItems` / `renderWindow` / `headerRows`。若是**自动加载**路径（`atTop` + `earlierArmed`）在用户仍停在顶部时反复增长窗口，表现是连续跳动或"一直往下掉"，那是 `earlierArmed` 没有在加载后重新武装（它要求 `atTop` 先变回 false）——**不丢数据，但难看**。

### I2. 跳到**窗口外**的行：先扩窗、再滚动（两阶段 `reveal`）
**状态**：未验
**操作**：① 在长会话（> 50 行）里打开搜索，输入一个只出现在**很靠前**（尚未渲染的那一段）的词，点「下一处」；② 再用溢出菜单的「跳到上一条提问」/「跳到下一条提问」，把目标选到窗口外的那一行
**预期**：目标行**先被渲染出来**（窗口自动扩展，顶部「加载更早的 N 条」计数随之变化），**然后**平滑滚到它；最终目标行在视口里
**判据**：目标行真的可见（不是停在原地、不是滚到列表末尾）；搜索那条还要**带 `searchMatchBg` 高亮 + 当前命中的反色加粗**
**失败含义**：`ChatScreen.kt` 的两阶段跳转 —— `reveal(row)`（按 `visibleItems.size - row` 扩 `renderWindow`）与随后消费 `pendingJump` 的 `LaunchedEffect`（`index = row - hiddenCount + headerRows`）。只扩窗不滚 → `pendingJump` 没被消费（effect 的 key 或 `index in 0 until renderedItems.size + headerRows` 这个越界条件不满足）；滚到**错的行** → 索引换算（`hiddenCount` / `headerRows`）错了；高亮错行 → `searchMatches`（全表索引）与 `index = sliceIndex + hiddenCount` 不一致。

---

## J. proroot 可选运行时（D44；`runtime/**` + `ui/settings/**`）

> 这一段来自 D44 的落地（`applied (uncommitted)`）：**代码与纯逻辑测试在构建机上跑过，真机一次都没跑过**。
> 这一节能验的东西比别处多，因为 proroot 的失败模式是**静默**的（`docs/proroot-research.md` §5.P0-2：raw syscall
> 读到的是宿主文件，没有任何报错），所以"看起来能用"不算通过。**顺序别换：先在 J0 关闭状态下确认一切照旧，再开开关。**

**J0. 默认关等于完全没接（回归）**
**状态**：未验
**操作**：装好后**不动**设置，正常聊天、开一次终端、装一次包（`设置 → 扩展`）
**预期**：与引入 proroot 之前**逐字节一致**：引擎、终端、装包都走 proot
**判据**：`设置 → 运行时与诊断` 的摘要行是「运行时：未开启（走 proot） · 保活：…」；
`设置 → 运行时与诊断 → 运行时（实际生效）` = 「未开启（走 proot）」；
导出诊断报告里「运行时选择」段的 `实际生效：Proot`，`proroot 探针：尚未运行`
**失败含义**：`RuntimePreferences` 默认值被改成 true，或 `AppOnlySettingsStore` 把开关写进了 pi 的
`settings.json`（去 guest 里 `cat /root/.pi/agent/settings.json` 看有没有 `app.runtime.proroot` —— **不该有**）。

**J1. 打开开关 → 第一次使用时跑探针 → 只有两条都过才真的走 proroot**
**状态**：未验
**操作**：① 设置 →「运行时加速（实验性）」打开（此时出现「需重启引擎」徽标）；② 回聊天页，用
`设置 →「重启引擎」` 重启；③ 打开一次**终端**（或发一条消息触发引擎启动）；④ 等几秒，再看
`设置 →「运行时（实际生效）」`，⑤ 导出诊断报告
**预期**：第一次真正启动 guest 之前，guest 里会跑一次探针（raw syscall + `rg`/`fd` 真调用），
结果被缓存到 `<files>/pi/runtime/.proroot-probe`
**判据**：
- **走 proroot 的成功形态**：`运行时（实际生效）` = 「proroot 正在使用」；报告「运行时选择」段
  `proroot 探针：已通过（缓存）`，且下面三行形如 `guestpath=translated`、`passwd=translated`；
  「工具链自检（真调用）」那一段写着「本次通过 Proroot 调用」且 rg/fd 两条 ✓
- **探针不过（很可能就是这台机器的结果）**：`运行时（实际生效）` = 「已回退 proot：探针未通过」，
  报告里 `guestpath=untranslated（errno=2）` 或 `passwd=leaked`。**这时不要试图绕过**：
  `untranslated` 意味着 raw/inline svc 调用看不到 guest 文件系统，`leaked` 意味着它**静默读到了宿主文件** ——
  后者是否决级，前者按 D44 的裁决也不允许启用。**探针不过就别开**：关掉开关即可回到 proot
**失败含义**：探针结论不落盘 → `ProrootProbeCache` 的 key（revision + 5 个 `.so` 的 sha256）或
`PiPaths.prorootProbeCache()` 的位置不对；探针每次都重跑 → 缓存没写成功（`<files>/pi/runtime` 权限或 `wipe()` 时序）；
开关打开但没有任何探针痕迹 → `RuntimeSelection.plan` 没被调用（`allowProroot` 传错，或调用点还在直接用 `ProotCommand`）。

**J2. raw syscall 探针**（单独看，因为它可能独立于 rg/fd 失败）
**状态**：未验
**操作**：同上 J1③，或直接在终端里跑（把 `<files>` 换成 `/data/user/0/app.pi/files`）：
```
perl -e 'my $fd = syscall(56, -100, "/etc/passwd", 0, 0); print "raw fd=$fd errno=$!
";'
```
（在 proroot 下打开终端才会走 proroot；proot 终端里跑这条永远成功，因为它用 ptrace 拦所有 syscall）
**预期**：这条**故意**不通过 libc 的 `open` —— 它测的是"绕过 libc 的 syscall 有没有被翻译"
**判据**：**能读到 guest 的 `/etc/passwd`**（例如 `head -1` 是 `root:x:0:0:root:/root:/bin/bash`）
= 翻译成功；**报 ENOENT** = 没翻译（看不到 guest 文件系统，按 D44 不开 proroot）；
**成功但内容是宿主 Android 的** `/etc/passwd`（`/` 结尾是 `/system/bin/sh`、home 不是 `/root`）= **静默越界，否决级**，
立刻关开关并把这个输出贴进 `docs/known-gaps.md` §N1
**失败含义**：探针本身没跑起来（guest 里没有 `perl`？`command -v perl` 确认）—— 那种情况门禁判 **未通过**，
不会静默放行（`ProrootRawProbe.parse` 的 `interpreter` 分支）。

**J3. `rg`/`fd` 在 proroot 下的真调用**
**状态**：未验
**操作**：proroot 引擎起来后，在聊天里让 pi 用 `find`/`grep` 工具各搜一次（或终端里 `rg -N '^root' /etc/passwd`、`fd -H '^passwd$' /etc`）
**预期**：两者都返回结果，且**读的是 guest 的文件**
**判据**：`fd -H . /var/lib/dpkg | head` **非空**（`/var/lib/dpkg` 只在 guest 里有）；
若报 ENOENT 或列空 → **否决级**：`rg`/`fd` 是 **musl 静态** Rust 二进制，proroot 的 `--static-loader`
+ inline `svc` 改写没覆盖到它们，而 pi 的 `find`/`grep` 工具与 `@` 提及**全靠它们**（静默变空）
**失败含义**：`ProrootCommand` 没带 `--static-loader`（或没给 `PROROOT_STUB_LOADER`）→ 静态二进制没兜住；
或探针门禁被绕过（它本该先拦住）。关开关，记账。

**J4. proroot 下停止 = 整棵 guest 树真的没了**（`--kill-on-exit` 的替代）
**状态**：未验
**操作**：proroot 引擎运行中，让引擎起一个长时间命令（聊天里让 pi 跑 `sleep 600 &`，或终端里 `sleep 600 &`），
然后① 在终端页按返回关掉终端 ② 用 `设置 → 重启引擎`；每次之后在**设备 shell**（不是 guest 终端）里看：
```
ps -A | grep -E "proroot|node|sleep" | grep -v grep
```
**预期**：那次启动的 `libproroot.so`、`bash`、`node`、`sleep` 全部消失
**判据**：`ps -A` 里没有**本次**那棵树留下的进程；`ls <files>/pi/runtime/proroot-tmp` 里**没有**刚停止那次的
`.proroot-config-<pid>`（停止时就该被删掉）
**失败含义**：`GuestTreeReaper` 没跑（`PtySession.close` 的 engine 判断 / `GuestCommand` 的超时分支），
或者拿不到 launcher pid（`ProrootLaunchHandle.resolveLauncherPid` 超时 → `lastError`/stderr 里会写
「拿不到 proroot 的 launcher pid」）。**注意**：`Process.pid()` 在本项目不可用，pid 只能来自 proroot 自己的表名，
所以这条一旦失败，症状就是"进程泄漏"而不是"报错"。

**J5. `.proroot-config-*` 的存活清理与上限**
**状态**：未验
**操作**：proroot 下反复重启引擎/开终端 10 次以上，然后
```
ls -la <files>/pi/runtime/proroot-tmp/ | wc -l
```
再制造一次"死文件"：手工造一份 `touch <files>/pi/runtime/proroot-tmp/.proroot-config-999999`（一个不存在的 pid），
然后**再启动一次 proroot**（重启引擎），看它有没有被删
**预期**：每次 proroot 启动前扫一遍：**只删 `/proc/<pid>` 不存在的**；活着的一次都不删；份数不超过 32
**判据**：`.proroot-config-999999` 消失了；同时**正在跑的**引擎/终端那两份仍在；总数 ≤ 32；
`logcat -s PiRuntimeSelection` 里能看到 `清理了 N 份属主进程已不存在的 .proroot-config 文件`，
超过 32 时还能看到那条 cap 日志
**失败含义**：存活判定写成了"除了我全删"（会**拆掉正在工作的 guest**，比不清理更糟）；或上限没生效（`ProrootConfigSweep.DEFAULT_LIMIT`）；
或 `PROROOT_TMP_DIR` 没指到 `<files>/pi/runtime/proroot-tmp`（那清理扫的是别的目录）。

**J6. 三层兜底的第三层：连续 3 次失败后强制回退（并且告知）**
**状态**：未验（**需要一个"能失败的 proroot"**：例如把 `<nativeLibDir>/libproroot-runtime.so` 临时改名 —
`nativeLibraryDir` 是只读的，所以更现实的做法是**在 debug 构建里**让 `RuntimeChoice.decide` 的 `probePassed` 恒为 false，
或在 guest 里把 `PROROOT_TMP_DIR` 指到一个不可写目录）
**操作**：让 proroot 连续启动失败 3 次（例如连开 3 次终端），第 4 次再开
**预期**：前 3 次逐次计数；第 3 次之后**强制 proot**
**判据**：`设置 → 运行时（实际生效）` = 「已回退 proot：连续 3 次启动失败」；
重新**关掉再打开**开关后回到「已回退 proot：探针尚未运行」（计数清零）；
`logcat -s PiRuntimeSelection` 有 3 条 `proroot 启动失败（n/3）` 与那条「强制回退 proot」的 W 级日志
**失败含义**：计数没持久化（`RuntimePreferences.prorootFailures`）→ App 重启后计数归零、永远到不了 3；
或计数在成功启动时没清零（把 `recordProrootSuccess` 漏了）→ 偶发失败累积成永久回退。

**J8. 探针未通过之后，「关掉再打开开关」= 重跑门禁**（D44 修复点的上机判据）
**状态**：未验（需要一个"探针会失败"的环境；最省事的做法是 debug 构建里让 `probePassed` 恒为 false，
或在 guest 里把 `PROROOT_TMP_DIR` 指到不可写目录再试一次）
**操作**：① 让 proroot 的探针判为未通过（状态行显示「已回退 proot：探针未通过」）；
② 到 `设置 →「运行时加速（实验性）」` **关掉开关**，再**打开**；③ 看状态行；④ 重启引擎后再看一次
**预期**：开关重新打开时，缓存的门禁结论（`<files>/pi/runtime/.proroot-probe`）被删掉，下一次 proroot 启动重跑一遍门禁
**判据**：
- 步骤 ③ 状态行从「已回退 proot：探针未通过」变成「已回退 proot：探针尚未运行（首次使用时会自动跑一次）」；
  导出诊断报告里 `proroot 探针：尚未运行`，且 `.proroot-config` 那一段之外能看到探针证据为空
- 步骤 ④ 之后，状态行是**重新测过的结论**（修好了就是「proroot 正在使用」，仍是坏的（例如 raw 层不翻译）就还是未通过，
  但那条未通过必须来自**新跑的一次**）——`logcat -s PiRuntimeSelection` 能看到 `proroot 探针（v1…）：…` 又打了一次
- **反面对照**：只是**重启引擎**（不动开关）时，**不应**重跑探针（报告里仍是「已通过/未通过（缓存）」）——
  这是"正常重启不重跑"的判据，也是这个缓存存在的意义
**失败含义**：`ProrootRetry` 没被调用（`AppOnlySettingsStore` 又直接写了 `RuntimePreferences`）、
或 `clearProrootProbeCache()` 删的不是门禁读写的那个文件（`PiPaths.prorootProbeCache()`）。
注意这条**不能**靠"重装 App"来绕：`nativeLibraryDir` 的 5 个 `.so` 与解包 revision 都没变，
缓存 key 不变，所以只有那条删除能让它重测。

**J7. 引擎/终端/装包**三条都真的走 proroot**（不是只有一条）
**状态**：未验
**操作**：proroot 生效（J1 通过）后，分别：① 发一条消息（引擎）② 开终端跑 `id`
③ `设置 → 扩展` 装一个小包（`pi install npm:…`）
**预期**：三条都走 proroot —— 这正是用户要求的「别让 proroot 只在边角用」
**判据**：每条期间在设备 shell 里 `ps -A | grep libproroot` **都能看到** `libproroot.so`；
若只有终端有、引擎没有 → `PiEngineHost` 还在用 `ProotCommand`（或 `allowProroot=false` 传错了）
**失败含义**：调用点漏改。逐个查 `engine/PiEngineHost.kt`、`runtime/PtyLauncher.kt`、`packages/GuestCommand.kt`
是否都走 `RuntimeSelection.plan(...)`。

---

## Z. 收尾

- 全部条目的初始状态都是 `未验`。跑完把状态改成 `通过` / `失败`，失败的**贴真实输出**，不要只写"不行"。
- **优先级**（真出问题时按这个顺序查）：**A3（`.l2s`）→ A2（self check）→ B1（apt）** 是地基；**D0（`/app/health`）** 一次就能看清设备桥的全部状态；**C5（终端看哪份 agent 目录）** 是本清单里最可能牵出源码改动的一条。
- 跑完 D 组之后，`设置 → 设备能力 → 设备桥` 里的审计尾行应当能对上刚才的操作（D15）；对不上说明审计链路有问题。
- 本文与 `docs/known-gaps.md` §C 的关系：§C 是"哪些没验过"的账，本文是"怎么验、验到什么算过"的执行件。**两者都不是验证结果。**
