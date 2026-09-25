# bxroot：第三个运行时（独立开关）

> 状态：运行时层与开关已接线；制品已进 `jniLibs` 并在 `runtime.lock.json` 里钉住摘要。
> 真机验收（点开开关 → 探针通过 → 引擎切过去并跑一次真实回合）**尚未完成**。

## 它是什么

bxroot 是一个开源（MIT）的容器运行时，与本项目已有的两个运行时不同层：

| | 机制 | 产物 | 开关 |
|---|---|---|---|
| proot | ptrace，每个系统调用停两次 | `libprootloader.so` | 无（默认） |
| proroot | `LD_PRELOAD` + 活体补丁 | `libproroot*.so` ×5 | `app.runtime.proroot` |
| **bxroot** | `LD_PRELOAD` + 活体补丁 | `libbxroot*.so` ×5 | **`app.runtime.bxroot`** |

上游：<https://github.com/qiannianhuanxiang/bxroot>，钉在提交
`5c143864b0f36f42b79808547e39e57da688db68`。

## 为什么是「再加一个开关」而不是「换掉 proroot」

两个运行时回答的是同一个问题（谁把 guest 命令送进 rootfs），但它们的**探针结论与失败
计数是各自的**：一台设备上 bxroot 没过门禁，不该顺手把 proroot 的开关状态也改掉。所以
本实现是**两个互相独立的开关**，而不是一个三选一的单选：

- 两个都关 → proot（默认）
- 只开 bxroot → bxroot（探针通过时）；没通过 → proot
- 只开 proroot → proroot（探针通过时）；没通过 → proot
- 两个都开 → **bxroot 优先**；它没过门禁就完整退回原来的 proroot 路径

`RuntimeSelection.plan()`/`status()` 里 bxroot 先问、proroot 后问；`RuntimeChoice` 的
每条理由都写明是哪一个运行时（`EngineFallback.BxrootSwitchOff` 与 `SwitchOff` 是两个值，
就是为了让状态行说得清在讲谁）。

## 契约（与 proroot 的差异只有名字）

命令行**同形**，所以 `BxrootCommand.build` 直接复用 `ProrootCommand` 的绑定表与
`ensureWorkdir`（两条配方不能漂）：

```
libbxroot.so --link2symlink -0 -r <rootfs> -w <cwd> -b <host>:<guest>… /bin/bash -c <cmd>
```

环境变量**换了前缀**，且只设 DSHA/bxroot 契约里由宿主设置的那四个：

| 变量 | 值 |
|---|---|
| `BXROOT_TMP_DIR` | `<runtime>/bxroot-tmp`（宿主路径） |
| `BXROOT_LINKER_PATH` | `libbxroot-linker.so` |
| `BXROOT_LIB_PATH` | `libbxroot-runtime.so` |
| `BXROOT_STUB_LOADER` | `libbxroot-stub-loader.so` |

`BXROOT_ROOTFS` / `BXROOT_GUEST_EXE` / `BXROOT_WORKDIR` / `BXROOT_FAKEROOT` /
`BXROOT_LINK2SYMLINK` **不设**：契约规定由 launcher 从 argv 派生，两边都设会让「argv
优先」这条约定失效。

`-b` 的两种拼写 bxroot 都收（带冒号走显式 host:guest，不带冒号按 upstream proot 的
`host == guest` 语义），所以本项目沿用 proroot 那一种即可。

`--kill-on-exit` bxroot **支持**而 proroot 拒绝，但本项目**不传**：进程树的寿命已经由
`GuestTreeReaper` 从 launch handle 解析出的 pid 一侧负责，两个 owner 抢一棵树只会得到
「有时在有时不在」的症状。要用它是单独一次改动，带自己的验证。

## 制品与署名

- 五个 `.so` 进 `app/src/main/jniLibs/arm64-v8a/`，摘要写在 `runtime.lock.json`
  （`bxrootLauncher`…`bxrootStubLoader`）。`tools/fetch-runtime.mjs` 的 `stageBxroot()`
  优先保留 jniLibs 里摘要吻合的字节，否则从 `BXROOT_SRC` 指到的源码 `make` 出来并核对；
  两者都没有时明确报错并给出两条修复路径。
- `libbxroot.so` 是**静态 `ET_EXEC`、无 `PT_INTERP`**，Android 10+ 可以从
  `nativeLibraryDir` 直接 execve，不需要 proroot launcher 那种
  `/system/bin/linker64` 伪装（`BXROOT_JNI_PAYLOAD` 里五个 `interpreter: null` 就是这个
  原因）。
- 五个产物的 LOAD 段按 `0x4000`（16KB）对齐，满足 Android 15+ 16KB 页设备的要求。
- MIT 许可与署名：`app/src/main/assets/licenses/bxroot-MIT.txt`。

## 探针

复用 `ProrootProbe` 的三个阶段（裸 syscall 路径翻译、工具链二进制、引擎类二进制），只把
引擎、摘要与缓存文件换成 bxroot 的：摘要按 `RuntimeChoice.BXROOT_REQUIRED_FILES` 计算，
结论写在 `<runtime>/.bxroot-probe`（与 proroot 的 `.proroot-probe` 分开）。

**已知的措辞缺口**：探针与自检里少数面向用户的句子仍然写「proroot」（它们来自 proroot
那一套叙事），bxroot 触发时会显示同一个句子。行为正确，措辞待改。

## 验证状态

已验证（本机，非本 APK）：

- 用 gcc 13.3 从上述提交编译出五个 `.so`；LOAD 段 16KB 对齐
- 在一个嵌套容器里跑通：guest shell、`uid=0` 假身份、读 `/etc/os-release`、跑 python 与
  esbuild
- 同一套诊断在 proot / proroot / bxroot 上逐项对照结果一致（`fork+execv` 761 字节不截断、
  `import soundfile` 不崩、静态二进制绝对路径正常；`faccessat2`/`clone3`/`unshare` 回
  ENOSYS 与 `init_module` 返回成功三者相同 —— 那几条来自外层 App 的 seccomp 策略，不是某一
  个运行时独有）

**未验证**（不能当成已完成）：真机点开开关 → 探针真的通过 → 引擎真的切到 bxroot 并跑完一次
真实回合；装机路径（仍然固定 proot）不受影响这一点也只在代码层面成立。
