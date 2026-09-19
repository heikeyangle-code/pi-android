<h1 align="center">pi-android</h1>

<p align="center">
  <strong>真正的 pi，装进你的手机。</strong><br />
  <sub>官方 pi <code>0.85.1</code> · 一个字节没改 · 真 Linux 用户态 · 原生 Android 界面</sub>
</p>

<p align="center">
  <strong>扩展 · 技能 · 工具 · 会话 · 模型 · 主题</strong><br />
  <sub>pi 有什么，这里就有什么 —— 不够用，自己写一个扩展</sub>
</p>

<p align="center">
  <a href="#完整的-pi">完整的 pi</a> ·
  <a href="#pi-的哲学">pi 的哲学</a> ·
  <a href="#产品一览">产品一览</a> ·
  <a href="#装机">装机</a> ·
  <a href="#工程">工程</a> ·
  <a href="./LICENSE">MIT</a>
</p>

---

市面上那些"手机上的 AI Agent"，大多是自己写一个循环、再挂几个工具 —— 看上去像，里子是自己写的。

**我们反过来：把官方 pi 原封不动装进手机。**

pi 的 agent 循环、全部工具、会话文件、压缩与重试、扩展系统、技能、模型与凭证，全部是上游那份代码，跑在手机本地的真 Linux 用户态里（Ubuntu 24.04 + Node 24，proot 起，**不需要 root**）。App 只做一件事：用 pi 自己的 `--mode rpc` 协议驱动它，把它换上手机上该有的样子。

```text
Compose 界面  ←→  pi 的 JSONL-RPC  ←→  pi（官方原版）  ←→  Ubuntu 24.04 + Node 24
```

## 完整的 pi

**不是"支持了哪些功能"，而是"pi 有什么"。**

<table>
<tr>
<td align="center" width="25%"><strong>扩展</strong><br /><sub>TypeScript / npm 包，pi 进程内加载：注册工具、覆盖内建工具、注册模型厂商、自带界面</sub></td>
<td align="center" width="25%"><strong>技能</strong><br /><sub><code>SKILL.md</code> 丢进目录即用，<code>/skill:名字</code> 直接调；Claude / Codex 的技能大多能直接用</sub></td>
<td align="center" width="25%"><strong>全部工具</strong><br /><sub>读 / 写 / 改 / 搜内容 / 找文件 / 列目录 / 跑命令 —— 每一个都有专属卡片</sub></td>
<td align="center" width="25%"><strong>模型与凭证</strong><br /><sub>多厂商、API key / 订阅登录、自定义兼容端点；凭证走 pi 自己的 <code>auth.json</code></sub></td>
</tr>
<tr>
<td align="center"><strong>会话</strong><br /><sub>pi 的会话文件格式；续接、压缩、分支摘要、会话树</sub></td>
<td align="center"><strong>资源包</strong><br /><sub>从 git / npm 装扩展、技能、主题；装、更新、卸载都在手机上</sub></td>
<td align="center"><strong>主题</strong><br /><sub>pi 的主题令牌；写一个 <code>themes/你的.json</code> 就能选</sub></td>
<td align="center"><strong>原版 TUI</strong><br /><sub>终端页里是真正的 pi TUI（真 PTY）：订阅登录、会话导入、只在 TUI 里生效的扩展都在</sub></td>
</tr>
</table>

外加 **Android 这一侧独有的东西**：pi 能伸手碰你的手机 —— 读屏、点按、输入、滑动、截屏、通知、剪贴板、分享、震动、手电、TTS、传感器、位置、应用管理、文件读写、审计日志，35 个端点，全部要显式授权。

## pi 的哲学

pi 的设计是**可组合**：核心给你一个可靠的 agent 循环和一套工具，剩下的能力由扩展长出来。所以我们不去猜"用户还想要什么功能"，也不做一堆半成品的开关 —— **你拿到的是 pi 的完整能力面，缺什么就写一个扩展**。

也因此，这里的每一条能力都对得上 pi 的源码：33 条 RPC 命令全部接入；pi 在 RPC 模式下会发的 9 种扩展界面方法，9 种全部有落点；少数只画终端格子的扩展 API，在终端页的原版 TUI 里照样能用。

## 产品一览

全部真机截图。**这里所有主题，都是这个软件自己写出来的。**

<table>
<tr>
<td width="50%" align="center" valign="top">
  <a href="./docs/screenshots/01-chat-tool-cards.png"><img src="./docs/screenshots/01-chat-tool-cards.png" alt="对话页：流式输出与工具卡" width="300" /></a><br />
  <sub><b>对话</b> —— 流式输出、可折叠的思考块、逐类工具卡，手机上不再是"一坨等宽文本"。</sub>
</td>
<td width="50%" align="center" valign="top">
  <a href="./docs/screenshots/02-tool-cards.png"><img src="./docs/screenshots/02-tool-cards.png" alt="逐类工具卡" width="300" /></a><br />
  <sub><b>工具卡</b> —— 命令 / 读 / 改 / 搜索各有各的画法，截断会说出来，不假装完整。</sub>
</td>
</tr>
<tr>
<td width="50%" align="center" valign="top">
  <a href="./docs/screenshots/03-theme-a.png"><img src="./docs/screenshots/03-theme-a.png" alt="主题 A" width="300" /></a><br />
  <sub><b>主题 A</b> —— 用 pi 的主题令牌写成，整个界面（含代码高亮）跟着换。</sub>
</td>
<td width="50%" align="center" valign="top">
  <a href="./docs/screenshots/04-theme-b.png"><img src="./docs/screenshots/04-theme-b.png" alt="主题 B" width="300" /></a><br />
  <sub><b>主题 B</b> —— 同一个界面，换一份主题文件就是另一种气质。</sub>
</td>
</tr>
<tr>
<td width="50%" align="center" valign="top">
  <a href="./docs/screenshots/05-theme-c.png"><img src="./docs/screenshots/05-theme-c.png" alt="主题 C" width="300" /></a><br />
  <sub><b>主题 C</b> —— 主题文件就是 JSON，改一个颜色即时生效。</sub>
</td>
<td width="50%" align="center" valign="top">
  <a href="./docs/screenshots/06-surfaces.png"><img src="./docs/screenshots/06-surfaces.png" alt="设置、工作区与终端" width="300" /></a><br />
  <sub><b>设置 / 工作区 / 终端</b> —— 设置就是 pi 的 <code>settings.json</code>；工作区能改文件；终端里是原版 pi。</sub>
</td>
</tr>
</table>

## 装机

从 CI 的 artifact 里取 **`pi-android-sideload28`**（推荐）或 **`pi-android-modern36`**，直接安装。两个包固定密钥签名，可以覆盖安装、数据不丢。

想自己编译：

```bash
./gradlew :rpc:test                 # 协议核心：33 条命令 / 全部事件 / 转录投影（不需要设备）
./gradlew :app:assembleRelease      # targetSdk 28（侧载）
```

为什么还留着一个 `targetSdk 28` 的包：Android 10 以后禁止 App 执行自己数据目录里的文件，而 pi 的整套运行时正好在那儿 —— `28` 是 Termux 为此一直钉的沙箱，`36` 的包则让 proot 自带 loader 完成映射。两个包 CI 都出，现代那条路是真机验过的。

## 工程

- **pi 按 SHA-256 钉死**：每个上游（Ubuntu base、Node、ripgrep、fd、git、proot）都写在 `runtime.lock.json` 里，哈希一动构建直接拒绝。
- **40 个纯逻辑 harness 进 CI**：协议编解码、转录折叠、附件预算、图片尺寸算术、设置键审计、缓存有界性…… 不需要手机就能跑。
- **56 条决策台账**：每一条"这里和 pi 不一样"都写清了为什么、代价是什么、被否掉的方案是什么。
- **没验过的不写"已验证"**：只能在真机上验的，列在 `docs/device-verification.md` 里并标明尚未跑。

## 许可

客户端代码 MIT。包内运行时各自保留上游许可，原文随载荷分发（`app/src/main/assets/licenses/`），来源与理由记在 `runtime.lock.json`。
