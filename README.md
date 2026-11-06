<p align="center">
  <img src="./brand/pi-readme-icon.svg" alt="PI" width="104" />
</p>

<h1 align="center">PI</h1>

<p align="center">
  <strong>手机上的 pi coding agent。本机运行，不要 root，不要 Termux。</strong>
</p>

<p align="center">
  Android 8.0+ · arm64 · MIT · 扩展生态和电脑上<b>完全一样</b>
</p>

<p align="center">
  <a href="#亮点">亮点</a> ·
  <a href="#界面">界面</a> ·
  <a href="#安装">安装</a> ·
  <a href="#常见问题">常见问题</a> ·
  <a href="#不做什么">不做什么</a> ·
  <a href="#技术细节">技术细节</a>
</p>

<p align="center">
  <a href="./docs/screenshots/banner-three-themes.jpg"><img src="./docs/screenshots/banner-three-themes.jpg" alt="同一场对话，三套主题" width="100%" /></a>
</p>

---

PI 是 [pi](https://github.com/earendil-works/pi) 的安卓客户端：把 pi 整个装进手机，
打开就能让它读代码、改文件、跑命令。引擎是官方那一份，一行没改；
App 做的是**把终端里的一切画成手机上顺手的样子**。

会话、文件、命令、凭证都留在本机，只有模型请求会发到**你自己配置的**服务。

## 亮点

- **开箱即用**　Ubuntu、Node、git、ripgrep、fd 全在 APK 里，第一次打开就能干活，不用先装别的 App。
- **不需要 root**　Linux 用户态跑在 proot 里，普通手机直接用。
- **扩展和电脑上一样**　扩展、技能、提示词、主题都是普通文件。电脑上能装的，这里也能装。
- **让 AI 自己装、自己写**　对它说一句「找个能联网搜索的扩展装上」，它自己搜、装、验证，立刻生效。
- **工具结果是卡片**　每次调用一行：耗时、行数、成败；`edit` 直接带 diff，`read` 读进来的图画在卡里。
- **思考可折叠**　要看再展开，不要看就一行。
- **生成中也能发消息**　消息排队，按 pi 自己的规则合并；停下时排队的内容回到输入框，不丢草稿。
- **会话树**　分叉、回到任一节点继续，会话可导出。
- **工作区是你手机上的任意目录**　内置文件浏览与编辑器：查找、撤销、行号，改动过的文件和 diff 收在一处。
- **模型随便配**　内置厂商预设，自定义供应商（OpenAI 兼容）填地址和 Key，Ollama / llama.cpp / vLLM 都行。
- **主题是一张 JSON**　换主题，界面、代码高亮、强调色一起换。
- **引擎在后台活着**　前台服务加唤醒锁，长任务切到别的 App 也不会被杀。
- **碰得到这台手机**（可选，默认全关）　读屏、点按、文件、剪贴板、通知，逐项授权，随时撤销。

## 为什么不一样

别的手机 agent 把能力焊死在 App 里，想要什么等作者发版。这里**没有「手机版功能」**：
pi 的可扩展性全建立在文件上——扩展是一个 `.ts`，技能是一个 `SKILL.md`，主题是一张 JSON。
引擎和约定都是同一份，所以电脑上有的，手机上就有；写那个扩展的人还可以是 AI 自己。

> 「换一套暗紫色的主题，晚上看着舒服点」
> 「写个技能：每次改完代码跑一遍测试，失败了把出错那几行贴出来」
> 「这个扩展的提示词太啰嗦了，精简一下再装上」

生态里已有一万多个扩展——[awesome-pi-coding-agent](https://github.com/shaftoe/awesome-pi-coding-agent)
收录 10430 个扩展、389 个主题：多 agent 编排、长期记忆、联网搜索、权限与安全、LSP 代码智能……都能直接装。

## 界面

pi 的 33 条 RPC 命令全部接入，9 种扩展界面方法每一种都有落点。

<table>
<tr>
<td width="50%" valign="top" align="center">
  <a href="./docs/screenshots/chat-light-tool-cards.jpg"><img src="./docs/screenshots/chat-light-tool-cards.jpg" alt="浅色主题：read 工具卡里并排画着读进来的两张图" width="280" /></a><br />
  <sub><b>读进来的图，就画在卡里。</b></sub>
</td>
<td width="50%" valign="top" align="center">
  <a href="./docs/screenshots/chat-dark-tool-cards.jpg"><img src="./docs/screenshots/chat-dark-tool-cards.jpg" alt="暗色主题：带 +5/−4 diff 的 edit 卡" width="280" /></a><br />
  <sub><b>改了哪几行，一目了然。</b></sub>
</td>
</tr>
<tr>
<td valign="top" align="center">
  <a href="./docs/screenshots/chat-theme-slate.jpg"><img src="./docs/screenshots/chat-theme-slate.jpg" alt="深青主题" width="280" /></a><br />
  <sub><b>同一场对话……</b></sub>
</td>
<td valign="top" align="center">
  <a href="./docs/screenshots/chat-theme-purple.jpg"><img src="./docs/screenshots/chat-theme-purple.jpg" alt="暗紫主题" width="280" /></a><br />
  <sub><b>……只换了主题文件。</b></sub>
</td>
</tr>
<tr>
<td valign="top" align="center">
  <a href="./docs/screenshots/workspace-session-changes.jpg"><img src="./docs/screenshots/workspace-session-changes.jpg" alt="工作区：本次会话改过的文件与 diff" width="280" /></a><br />
  <sub><b>这次会话动了哪儿。</b></sub>
</td>
<td valign="top" align="center">
  <a href="./docs/screenshots/workspace-resources.jpg"><img src="./docs/screenshots/workspace-resources.jpg" alt="工作区：技能、提示词、扩展、主题及其真实路径" width="280" /></a><br />
  <sub><b>这个目录里有什么。</b></sub>
</td>
</tr>
</table>

pi 自带的四个扩展也直接可用：`mcp`（连 MCP 服务器）、`codemode`（沙箱里并行调工具，纯 WASM，arm64 直接跑）、
`tool-search`（工具多时按需声明）、`llama.cpp`（本地/局域网模型）。

## 安装

**要求**

| | |
| :--- | :--- |
| 系统 | Android 8.0（API 26）及以上，**仅 arm64** |
| 空间 | APK 约 136 MB；首次启动解包约 64 MiB 运行时，之后只在载荷变化时重解 |
| 网络 | 调模型、装扩展要联网 |
| 模型 | **自备**：App 不含任何模型权重，也不代售 |

**三步上手**

1. 下载 APK 安装（见下），打开等它解包完。
2. 设置 → 模型与供应商：选一个厂商粘上 Key；不在列表里就用「自定义供应商」填地址和 Key。
3. 选一个工作区目录，开聊。

**下载**　目前没有正式 release、也没有商店版本，从 CI 产物取，或[自己编译](#技术细节)。有两个变体：

| 变体 | targetSdk | 什么时候用 |
| :--- | :--- | :--- |
| `…-sideload28-arm64.apk` | 28 | **默认选这个。** 系统权限最少，行为最可预期 |
| `…-modern36-arm64.apk` | 36 | 面向新系统行为变更，**还没在真机上验完**；只在 28 装不上或行为异常时试 |

两个包同一套密钥签名，可互相覆盖安装，升级不丢数据。

## 常见问题

**要 root 吗？要 Termux 吗？**　都不要。运行时全在 APK 里。

**数据放哪？**　会话、文件、命令、凭证都在本机。App 不另存凭证，写的就是 pi 自己的 `auth.json`、`models.json`、`settings.json`。

**能用本地模型吗？**　App 本身不做离线推理，但能连局域网里的 Ollama / llama.cpp / vLLM。

**扩展安全吗？**　扩展就是你自己的代码，在 guest 里能做 pi 能做的一切，包括读你的凭证文件。装之前问一句它是干什么的，跟在电脑上装 npm 包是同一件事。

**装多了会慢吗？**　会。pi 启动时要加载每个扩展，发 TypeScript 源码的尤其贵，实测单个最多能吃掉 9 秒。想快就少装或按需停用。

**手机能力默认开着吗？**　全关。读屏、点按、设备 shell 各自授权；要更高身份（root / Shizuku）得设备本身支持，并去「设置 → 设备能力授权」里开。这一层是个普通的 pi 扩展（[`pi-android-bridge`](app/src/main/assets/pi-extensions/pi-android-bridge/)），公开 API 写的，没有私有通道，你可以读、改、照着写自己的。

## 不做什么

- 不做离线推理，不含模型权重。
- 不替你挑扩展：生态里那一万多个，要你说一句让它装，或自己写。
- 不 fork 引擎、不打补丁、不在中间加转换层。
- 不压缩体积：APK 大，因为里面是一整个 Linux 用户态加 Node。

## 技术细节

<details>
<summary><b>架构</b>：引擎 · 协议 · 运行时 · 配置</summary>

**引擎。** pi 以官方 npm 包的形式随 APK 分发，App 用 pi 自己的 `--mode rpc` 驱动它（JSONL over stdio），不 fork、不打补丁、不在中间做转换层。会话内核、工具、扩展加载器、provider 工厂全部是上游那一份。

**协议。** 引擎之外是一个独立的纯 Kotlin/JVM 协议核心（[`rpc/`](rpc/) 模块，不带 Android 依赖）：33 条命令、9 种扩展界面方法、按 pi 的规则分帧（只按 `\n` 切、容忍 `U+2028`、增量 UTF-8 解码）。不插手机、不装模拟器就能跑完整套协议测试。

**运行时。** 工具执行的是这台手机上的真 Linux 用户态：

| | |
| :--- | :--- |
| **Ubuntu 24.04 base** | glibc 2.39——npm 的 `linux-arm64` 预编译件能直接跑 |
| **Node.js 24.19** | 官方 glibc 构建，`node` / `npm` 都是原生的 |
| **git · ripgrep · fd · CA 证书** | pi 的 `grep`、`find`、git 工具真正调用的那几个二进制 |
| **proot**（Termux 构建） | 用户态系统调用翻译层，所以**不需要 root** |

每个上游按 SHA-256 锁死，哈希一变构建直接失败。工作区可以是你手机上的任意目录。

**配置。** 写下去的都是 pi 自己的配置文件——`models.json`、`auth.json`、`settings.json`、`mcp.json`。App 不另存凭证，也不自己维护一套模型名单：pi 怎么读，这里就怎么读。13 条 provider 预设里，10 条的 `baseUrl` 与 `api` 逐条照抄 pi 的 provider 工厂（填错会注册成功、第一条消息才失败，正是导入页要挡掉的那类错）。

</details>

<details>
<summary><b>pi 升级</b>：只换版本号，三道检查兜底</summary>

上游 pi 平均不到五天发一个版本，这个仓库的应对方式是：**不改引擎，只换版本号。**

版本号只写在**一处**——`tools/fetch-runtime.mjs` 的 `PI_VERSION`。构建时按它取包、校验、打进载荷。上游加了新模型、新工具、新的会话行为，升版本号就有。

为了让这件事不悄悄出错，仓库里有三道检查：

- **`tools/pi-contract.mjs`**（CI 的独立 job）：把 App 依赖的每一件 pi 事实逐条对着**钉住的那个版本**核对——RPC 命令名、扩展界面方法、provider 预设、内置主题的每个色值、`models.json` 的替换与合并语义、工具结果文本、会话条目面、以及官方模型目录的格式。上游改名、删东西或改语义，**构建直接失败**，而不是发出去以后才发现。每条失败都会打印「该回去重读哪个 App 文件」。
- **diff 工具产物的字节**：判据是「字节相等」而不是「名字还在」——名字还在、语义变了，是这个仓库踩过两次的坑（`docs/known-gaps.md` §M11 / §M12）。
- **全部载荷按 SHA-256 锁定**：Linux 运行时、git 闭包、ripgrep/fd、proroot 的五个二进制记在 [`runtime.lock.json`](runtime.lock.json)；设备逐个载荷比对，只重解包真的变了的那个。

当前钉的是 **pi 1.1.0**。

</details>

<details>
<summary><b>自己编译</b></summary>

```bash
./gradlew :rpc:test                    # 协议核心，不需要设备
./gradlew :app:assembleRelease         # targetSdk 28（侧载）
./gradlew :app:assembleRelease -Ppi.targetSdk=36
```

构建会拉取并校验钉住的上游载荷（[`tools/fetch-runtime.mjs`](tools/fetch-runtime.mjs)），产出 APK。

其他常用的：

```bash
node tools/pi-contract.mjs             # 对着钉住的引擎核对全部契约
node tools/fetch-runtime.mjs           # 只组装运行时载荷
bash tools/run-app-pure-checks.sh      # 纯逻辑 harness（不需要设备）
```

`docs/` 下有更细的东西：[pi 契约](docs/pi-contract.md)、[RPC 覆盖](docs/rpc-coverage.md)、[界面规格](docs/pi-android-ui-spec.md)。

</details>

---

## 致谢与许可

这个项目站在几个成熟上游之上，它们各自保留自己的许可与版权：

| 项目 | 它是什么 |
| :--- | :--- |
| **[pi](https://github.com/earendil-works/pi)** | 上游 coding agent，`@earendil-works/pi-coding-agent` **1.1.0**（MIT） |
| **[Ubuntu Base 24.04](https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.3/release/)** | glibc 用户态 |
| **[Node.js 24.19](https://nodejs.org/en/download)** | 官方 glibc 构建 |
| **[proot](https://proot-me.github.io/)**（Termux 构建） | 用户态系统调用翻译层 |
| **[ripgrep](https://github.com/BurntSushi/ripgrep)** · **[fd](https://github.com/sharkdp/fd)** · git · CA 证书 | pi 的工具真正调用的那几个二进制 |

App 代码 MIT。包内的 pi 引擎同样是 MIT。第三方运行时与组件各自保留上游许可，原文随包分发：

- [pi 许可原文](app/src/main/assets/licenses/pi-license.txt)
- [第三方组件清单](app/src/main/assets/licenses/component-list.txt)
- [未随包提供许可文本的依赖](app/src/main/assets/licenses/pi-engine-licence-gaps.txt)
