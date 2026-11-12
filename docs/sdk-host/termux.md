# 第二个系统：Termux（暂缓，方案已定）

状态：**暂缓**。已落地的只有：官方 bootstrap 的固定（`runtime.lock.json` 的 `termuxBootstrap`，2026.08.30，32.7 MB，sha256 `7e92f4c4…`）、`fetch-runtime.mjs` 的下载项、解包器 `runtime/os/TermuxBootstrap.kt`（对真实 zip 的 harness 22 项通过）。其余等「SDK 补全 + UI 重做」之后再做。

## 为什么不是 proot

官方 Termux 包把 `/data/data/com.termux/files/usr` 写死在文件里（`bash` 的 RUNPATH、shebang、apt/dpkg 配置、deb 的数据路径）。App 是 `app.pi`，创建不了别人的 `/data/data/com.termux`。让进程「看到」这个路径，无 root 只有 proot 类翻译；但它有 ptrace 开销，且 Android 15 更严的 seccomp 会拦 glibc/proot 依赖的 `set_robust_list` 一类调用（见 [这篇](https://dev.to/opassoca/running-native-glibc-debian-binaries-on-android-15-without-proot-1hmo)）。所以只当兜底，不作主方案。

## 调研结论（没有现成方案）

- [termux-generator](https://github.com/robertkirkman/termux-generator)：改包名重新编译，得自己长期托管 apt 源，不再是官方 Termux。
- [AutoPie#6](https://github.com/cryptrr/AutoPie/issues/6)：同一问题，「自定义包名 + 官方源」至今没人做成；提过重打包 deb 路径、改 termux-exec shebang，均无测试结果。

## 方案：原生 + 长度不变的前缀替换 + dpkg 包装

1. **前缀替换**：把文件内容里的 `com.termux`（10 字节）原地换成 `app.pi////`（10 字节）→ `/data/data/app.pi////files/usr`。多余斜杠等同一个斜杠，指向我们自己的 `/data/data/app.pi/files/usr`；字节数不变，ELF 偏移/字符串表/shebang 不错位。
2. **首次解压**：官方 bootstrap 共 3472 个文件，698 个含该路径（338 ELF、360 文本），无 UTF‑16 变体；符号链接目标由 `SYMLINKS.txt` 提供，创建时同样替换。
3. **之后的 `pkg install`**：官方 deb 数据路径是完整的 `/data/data/com.termux/files/usr/…`，dpkg 从 `/` 解包。用 apt 配置 `Dir::Bin::dpkg` 指向我们的包装脚本 `libexec/pi/dpkg-relocate`（不属于任何 Termux 包，升级 dpkg/apt 不会覆盖）：解包前把 deb 的数据目录改名到 `data/data/app.pi/…`，并对文件内容（含 postinst 等脚本）做同样的 10 字节替换。
4. **启动**：直接起原生进程（无 proot），`PREFIX/HOME/PATH/LD_PRELOAD(termux-exec)` 用干净路径；要求 targetSdk 28（项目现状）。
5. **共享数据平面**：没有 bind，Termux 看不到 Ubuntu 的 `/workspace`；工作区与 pi 目录用**宿主绝对路径**访问。切换系统时用 SDK 的 `SessionManager.open(path, dir, cwdOverride)`（已核对 1.5）换 cwd 续会话；项目信任按 cwd 记，每个系统首次会再问一次。
6. **自检**：首次启动跑一遍，明确报告「linker 认不认 RUNPATH 里的 `////`」「`pkg install` 链路」——这两点在 x86 开发机上无法验证，只能上机。

## 已知风险

- 压缩包/`.jar`/`.pyc` 里嵌着前缀的包替换不到。
- 引擎要在 Termux 里跑，需要 `pkg install nodejs`（bionic Node）；引擎代码 `/opt/pi` 与 Ubuntu 共用同一份 JS。
- 兜底：proot 映射前缀（Android 15+ 可能起不来）。
