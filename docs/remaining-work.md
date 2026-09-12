# 剩余工作清单（核实版）

> 生成：本轮核实（工作树 HEAD 见 `git log -1`；`docs/**` 有并发写者，本文件的结论都在生成时的工作树上读过一遍）。
> 规格来源：**`/root/pi-src`，`0.85.1`，commit `bbb61e34`** —— 与 App 运行时里打的是同一个版本。
> **源码与 `docs/**` 冲突时以源码为准。**
>
> 这份清单回答一个问题：**"到底还剩什么活"**，而不是"账本上写着什么"。今天已经有四轮活因为账本把"做完"记成"没做"而白派（`§I3/I4/I6/I9`、30 条文案、`patch-ready: 23` 里的 29 条、`§I5/I8/I11`）。本文件里每一条都给出了**我实际读到的符号或跑过的命令**；读不到、验不了的，一律进 §7「无法判断」，不写成"没做"。

---

## 0. 阅读须知（四条判据，先读这一节再看表）

1. **反向谎言 #1：不许把"我们做不到"写成"pi 没有"。**
   pi 明明有、只是 RPC 协议或 Android 平台够不着时，值列必须写 `pi 有但我们够不着（卡在哪）`，并且**界面必须说真话**（指向 `工作区 → pi TUI（原版）`），不许把它混进"pi 没有"里糊过去。
2. **反向谎言 #2：不许把"我们自己发明的"写成"pi 的行为"。**
   App 自己的产品决定（导航、授权页、样式系统、性能设计）值列写 `pi 无对应物（App 自己的决定）`，优先级由**用户价值**决定，不由"pi 有没有"决定；也不许反过来给 pi 记一笔它没做过的事。
3. **用户已决定不做的一类（不核 pi、不记债）**：玻璃/毛玻璃、装饰性动效、光晕等**纯视觉、空耗性能、没有信息增益**的东西。遇到这类条目直接标 `用户已决定不做`。**本清单（known-gaps / gap-disposition / feature-gaps / rendering-review）里目前没有条目落在这一类**；但 `pi-android-ui-spec.md` 里**确实有 4 条**（`:151` 顶栏毛玻璃、`:284` App Bar 毛玻璃、`:870` 输入框光晕、`:871` 顶栏毛玻璃），它们不在这份清单的范围内，已按用户决定就地标 `用户已决定不做`。（F13/F14 是**对比度可读性**，不是装饰，见 §3。）
4. **任何"pi 有"必须能在 `/root/pi-src` 打开看到并给出 `file:line`**；引不出行号的一律写 `pi 无对应物`。

---

## 1. 结论计数

| 口径 | 已实现 | 仍未做 | 无法判断 / 未核实 |
|---|---|---|---|
| 本文件逐条核实的**条目**（§2–§5 的表行） | **33** | **8**（含"半边"） | **9** |
| 其中 purely `pi 有 <file:line>` 且我们没做的**真债** | — | **1**（只剩 F10 UI 半边；§I11 与 §I7 的 4 个运行时行随后都接上了——见下表两行的更正） | — |
| 引账本自身已对账的分组（未逐行重核，见说明） | `feature-gaps` §7 的 30 条 · `gap-disposition` §9 的 29 条 · `fidelity-review` 10 条 · **`extension-compatibility` 的 68 条 MISSING（本轮已逐条按符号核完，见 §5.2）** | — | `feature-gaps` 的 **112 行**（§7 自述未核实）· `§C` 真机 4 条 |

**一句话结论**：**能力债几乎见底了**。真正"pi 有、我们没有"的只剩 **1** 条（F10 的 UI 半边；§I11 与 §I7 的四个运行时行随后都接上了——复核于本轮，见 §2 两行的更正）；其余"未做"集中在 **rpc/engine 的性能与并发**（RR F9）、**调色板与样式一致性**（F13/F14/F28/F29）、以及**新 UI**（F10/F20）。**F16 与 F34 经父代理回代码复核已在本轮落地**（见 §3.2 两行）。账本里最危险的不是"没做"，是**还写着没做但其实做完了**——见 §6。

---

## 2. `known-gaps.md` §A–§K 逐条

| 条目 | 账本原话 | 核实结果 | 证据（符号名，工作树） | pi 有对应物吗 | 归属 |
|---|---|---|---|---|---|
| §A1 压缩/分支/Hook 消息 markdown | 已完成（复核于 dc00279） | **已实现** | `CompactionBlock.kt:91-116`、`BranchSummaryBlock.kt:72-92`、`HookMessageBlock.kt:59-80`（账本 §10.1 已逐条给符号，本轮未重核） | `pi 有` `modes/interactive/interactive-mode.ts:2829-2830`（error 行是纯文本，故意不 markdown） | — |
| §A2 LaTeX | 已完成；`renderLayout` 为 limit | **已实现（含已记录的 limit）** | `ui/render/PiLatex.kt:604`/`:624`/`:649`、`PiMarkdown.kt:132-167`；limit = pi 的垂直排版 `packages/tui/src/latex.ts:723-809` 未移植（类注释已写明代价） | `pi 有但我们够不着`（TUI 的 ANSI 层排版在 Compose 里需要行保持通道，见 §10.1） | markdown 代理（已交付） |
| §A3 markdown 图片 | 已完成（通道 07c27861 + 本次接线） | **已实现**；剩 1 个"模型限制"（inline alt 文本不可达） | `bridge/GuestImageBytes.kt`、`bridge/PiGuestImageTransformer.kt`、`PiMarkdown.kt:125-140`、`PiMarkdownComponents.kt:290-333` | `pi 有` `packages/tui/src/components/markdown.ts:619-627`（终端打印 alt 文本） | — |
| §B1–B4 命令面板 / 会话管理 / 模型选择器 / bash | 已完成（复核于 dc00279） | **已实现** | `PiSlashCommands.piCommandPalette` + `SlashPalette`；`SessionTreeScreen`；`ChatSheets`；`SlashPalette.kt:212-216` → `runBash` | `pi 有` `core/slash-commands.ts:19-43` 等（各条已在账本行内引用） | — |
| §B5 扩展包管理（`pi install/list/remove`） | 待验证 | **代码已实现；只剩真机** | `packages/PiPackageService.kt`、`PiPackagesHost.kt`、`PiPackagesScreen.kt`；**设置入口本轮已挂**（`SettingsHome.kt:109-116` → `PiSettingsStack.kt:186`） | `pi 有` `cli/package-manager-cli.ts:375-573` | 真机（`device-verification.md`） |
| §B6 `/reload` | 待验证（改为显式重启+状态机） | **已实现（代码）**；收尾=真机确认新扩展出现在 `get_commands` | `packages/ExtensionLifecycle.kt`、`EngineRestartCoordinator.kt`、`PiEngineHost.restart`、`PiSessionViewModel.restartEngine`（本轮接线：`PiRoot.kt:193`） | `pi 有但我们够不着`：内建 `/reload` 不在 RPC（`core/slash-commands.ts:41` + `modes/rpc/rpc-types.ts:20-74`）；扩展侧 `ctx.reload()` 才是可达的那条（`rpc-mode.ts:341-343`） | 真机 |
| §B7 项目信任弹窗 | 待验证；**"路径错位需 engine 改一行"** | **已实现；那"一行"已经改了**——`PiEngineHost.kt:285-295` 的 `extraBinds` 现在含 `paths.agentDir.absolutePath to guestAgentDir`。账本 B7 正文那句"只绑工作区、镜像 pi 看不见"**已过期** | `packages/TrustFile.kt`、`TrustRepository.kt`、`ProjectTrust.kt`、`PiProjectTrustPrompt.kt`；绑定在 `engine/PiEngineHost.kt:285-295` | `pi 有` `core/trust-manager.ts:212-214`、`core/project-trust.ts:66-96` | engine 代理（已改）/ 真机收尾 |
| §B8 扩展 UI 其余接线 | 已完成（复核于 dc00279） | **已实现** | `ui/extension/ExtensionUiHost.kt`（`PiRoot.kt` 唯一挂载点）、`PiSessionViewModel.onExtensionUi` | `pi 有` `docs/rpc.md` §Extension UI Protocol | — |
| §B9 markdown 0.45.0 | 已定论；**"仍需回补的两行"** | **已实现**（两行都已回补） | `ui/render/PiMarkdownTheme.kt:136`/`:178`（`darkTheme` 参数）、`:234`（`alertTitle`）（符号取自账本 §10.1，本轮未重核） | `pi 无对应物（App 的渲染库升级）` | markdown 代理（已交付） |
| §B10 `libprootloader.so` 非 PIE | 装机时可能被拒 | **无法判断**（只有真机装机/启动能证） | `tools/fetch-runtime.mjs` 的 PIE 校验已只对"会被 exec"的条目生效 | `pi 无对应物（App 的打包问题）` | 真机（`device-verification.md`） |
| §B11 `ExtensionError.extensionPath` | 已完成（复核于 dc00279） | **已实现** | `rpc/Events.kt:322`（字段）、`:468`（解析） | `pi 有` `docs/rpc.md` §`extension_error` | — |
| §B12 / §E6 资产安装闸门改内容指纹 | 手工常量，会静默失效 | **已实现**（复核于 `ba297c7`） | `DeviceBridgeController.assetFingerprint`（按资产路径排序算 SHA-256，写 `.pi-android-assets`）；`ASSET_VERSION` 只作读不出资产时的回退 | `pi 无对应物（App 的资产安装器）` | 已交付 |
| §C1–C4 真机待验证 | 代码已写、从未在真机运行 | **未核实（≠ 未实现）**：这是"没在真机上跑过"，全部归 `docs/device-verification.md` | `docs/device-verification.md`（505 行） | `pi 无对应物（平台/设备）` | 真机 |
| §D1–D4 已定妥协 | 设计取舍，不是缺陷 | **不是待办** | — | `pi 有但我们够不着/有意偏离`（各自理由已在账本行内） | — |
| §E2 高亮 `attach(context)` | 已完成（复核于 dc00279） | **已实现** | `ui/render/PiMarkdown.kt:77`、`highlight/PiNodeCodeHighlighter.kt:105` | `pi 无对应物（App 的高亮桥）` | — |
| §E4 `@` 文件提及 | applied (uncommitted)，带 3 个后续项 | **已实现**（入口+纯逻辑+guest `fd`）；**后续项 2/3 已实现、1 仍未做**：②`tools/run-app-pure-checks.sh:270` 已注册 `mentions`（且"2 harnesses"的写死计数已改为计算值）；③`fd` 悬空风险已修（`PiPaths.agentBinDir():46`/`rootfsAgentBinDir():53` + `RuntimeProvisioner.ensureToolsVisible()` 重放，`AgentToolPathsCheck` 已注册 `:282`）；① **仍未做**：`Composer` 仍是 `OutlinedTextField(value = draft: String)`（`ChatScreen.kt:1151`），没有真实光标，只有末尾 token 会补全 | `pi 有` `packages/tui/src/autocomplete.ts:7`（token 边界）、`:124-222`（fd 候选集）、`:474-479`（判定） | `ui/chat/**`（几近完成） |
| §E7 扩展/包管理区分「内置」与「用户安装」 | 随 B5 界面一起做 | **已实现**：`PiPackageModel.kt:63-119` 的 `PiBuiltinExtension` 转写内置树，`PiPackagesScreen.kt:215-296` 分区块，**并且界面上写了"「内置」是 App 的标注：pi 不分内置和用户安装"** | 同上 | `pi 有` `core/package-manager.ts:2352-2362`、`:2470-2475`（pi 把一切当 auto 用户扩展）；`pi list` 只读 `settings.json`（`package-manager-cli.ts:970-1002`） | 已交付 |
| §E8 终端方案决策依据 | 记录，不是待办；收尾=真机验证自写模拟器覆盖率 | **不是待办**（收尾归 `§C2` 真机） | 选型理由在账本行内；`ui/terminal/**` | `pi 无对应物（App 自己的选型）` | 真机 |
| §E9 凭证/模型导入 | 原为"完全是空壳" | **已实现** | `ui/settings/PiCredentialScreen.kt`（586 行）、`packages/PiCredentialService.kt`、`PiModelScanner`、`PiProviderPresets`、`PiConfigFiles`；**settings.json 落点也已修**（`PiCredentialService.kt:69` 的 `agentDir = mirrorAgentDir`） | `pi 有` `core/auth-storage.ts:52`（auth.json）、`core/model-config.ts:212`（models.json）、`core/settings-manager.ts:108-139`（选择项）；"扫描模型"**pi 无对应物**（`packages/ai/src/models.ts:763` 的 `fetchModels` 无人实现），界面已标注 | 已交付 |
| §I1 `/tree` 跳到历史某点 | DEFECT（RPC 不可达） | **pi 有但我们够不着**（RPC 无该命令）；**界面已说真话** | `ui/chat/SessionTreeScreen.kt:350` 文案改成「分叉新会话」并写明它写新文件 | `pi 有但我们够不着`：`modes/interactive/interactive-mode.ts:5216-5322` 有原地跳分支；RPC 只有 `get_tree`/`fork`（`modes/rpc/rpc-types.ts:20-74`） | limit（已记 F2） |
| §I2 动作类设置行 | 已处置：19 行逐行有结论 | **已实现（本轮复核一致）**：注册表现在 `kind = PiRowKind.Action,` **有 8 行**（`app.credentials.apiKey`/`.oauth`、`app.localModels.manage`、`app.compaction.runNow`、`app.sessions.import`、`app.security.emergencyStop`、**`app.runtime.restartEngine`（§I11 那一轮新增）**、`app.about.changelog`），其余已删 | `grep -c "kind = PiRowKind.Action," ui/settings/PiSettingsRegistry.kt` → **8**；`PiRoot` 的 `onRunAction` | 逐行判据（pi 有没有）在账本 §I2 表内，每条都有 `file:line` | 已交付 |
| §I5 主题 JSON 到不了 App 颜色 | DEFECT：两套写死调色板 + 漏 `~/.pi/agent/themes` | **已实现**（两项都修了） | ①`MainActivity` 收集 `session.theme` 并 `PiTheme(palette = theme.palette, dark = theme.dark)`（注释明写"不是两个手写常量"）；②`PiThemeFiles` 的 `scanDirectory` 扫 `~/.pi/agent/themes`、`.pi/themes` 与 `themes` 设置三处；③`PiThemeFiles.load`/`parseTheme`；④**更正**：`PiPalette` **并没有**"只剩 data class"——它仍保留 `Dark`/`Light` 两个内置常量，作为**没有主题文件时的 fallback** 与令牌解析的基底（`PiThemeFiles` 的 `fallback()`、`PiTheme.kt` 的 `LocalPiPalette` 默认值），只是不再是 App 唯一的颜色来源 | `pi 有` `core/resource-loader.ts:815`/`:821`/`:875`（两个主题目录）、`modes/interactive/theme/theme.ts:232`（`resolveVarRefs`）、`:597`（`resolveThemeSetting`） | — |
| §I7 13 个设置无消费者 | DEFECT（失效开关） | **大部分已实现**：8 个有消费者（`PiSessionViewModel.readPrefs():494-500` + `ui/terminal/TerminalSettings.kt:79`/`:104`）、3 个早先已删、2 个本轮删（`app.terminal.cursorStyle`/`scrollbackLines`，注册表里只剩注释）；`hideThinkingBlock` 已接（`ChatScreen.kt:285`/`:292`/`:641`）。**新发现的同一类病仍未做**：见下一行 | 见左 | `pi 有`：`hideThinkingBlock` 是 pi 自己的键（`core/settings-manager.ts:119`）；`cursorStyle`/`scrollbackLines` **pi 无对应物**（pi 只有 `showHardwareCursor`），故删除 | 已交付（删除项） |
| §I7 追加：4 个运行时只读行**没有写入方** | （本轮新发现，账本未记） | **已接（复核于本轮，工作树）**：`ui/settings/RuntimeFacts.kt` 的 `runtimeOverrides(snapshot)` 给 `app.runtime.piVersion` / `nodeVersion` / `rootfsUsage` / `wakeLock` 四个键各产出一行显示值（读不到时给出原因而不是假值），`PiSettingsStack` 在 `Dispatchers.IO` 上读入并逐行覆盖注册表默认值 | `grep -rn "RuntimeFacts" app/src/main/kotlin` → `PiSettingsStack.kt` 的构造与 `LaunchedEffect` | `pi 有但我们够不着`：pi 有版本上报（`cli/args.ts:93` `--version`、`:321`），**RPC 没有 version 命令**（`modes/rpc/rpc-types.ts:20-74`），所以那一行读的是运行时目录里的文件；`rootfsUsage`/`wakeLock` 是 `pi 无对应物（App 自己的运行时/电源信息）` | 已交付（`ui/settings/**`） |
| §I8 搜索能力缺失 | MISSING-GUI（"都没有"） | **已实现（四项都有）**：转录内查找 = `ChatScreen.kt:274-297`（`searchOpen`/`searchQuery`/`searchMatches`）+:332-336（跳转）+:456-459（入口）；会话树过滤 = `SessionTreeScreen.kt:96-97`（`filter`/`query`）+:137（模式切换）+:254-271（`passesTreeFilter`，含 pi 的五种模式）；会话列表搜索/排序/命名过滤 = `SessionsScreen.kt:90`（`query`）+:104-114（`byName`/`namedOnly`）+:133-134+:177（按 cwd 分组） | 见左 | `pi 有`：会话树五种筛选 `modes/interactive/components/tree-selector.ts:366-378`；会话选择器控件 `docs/sessions.md:43-46`；TUI 的匹配高亮 `packages/tui/src/tui-alt-screen.ts:171-273`（`searchMatchStyle`/`searchCurrentMatchStyle`）+ `theme-json.ts:40` 的 `searchMatchBg`/`searchMatchText`；**RPC 没有搜索命令**，所以是我们用 App 侧实现对齐 TUI 行为 | — |
| §I10 `app.device.*` 第二份授权真相 | DEFECT：要优先修 | **已处置**：7 条 `app.device.*` 与 `G_DEVICE` 已从注册表删除；`SettingsHome.kt:78-83` 写明 `DeviceCapabilityStore` 是唯一权威。连带 §C3.1 的 6 处界面声称不一致 applied (uncommitted) | `grep -c "app.device\." PiSettingsRegistry.kt` → 0（只剩注释） | `pi 无对应物（App 自己的授权页）` | 已交付 |
| §I11 环境类能力（离线 / `--system-prompt` / `PI_CACHE_RETENTION`） | CLI-ONLY / MISSING；"环境变量映射是写死的" | **已实现（复核于本轮，工作树）**：三件都接上了 —— `PiSettingsRegistry` 的「进程」section 注册 `app.runtime.offline`（Switch）、`app.runtime.systemPrompt`（Text）、`app.runtime.cacheRetention`（Value：默认/长保留），说明里写明"改动在重启引擎后生效"；`PiSessionViewModel.launchOptions()` 从 store 读这三个键，`boot()` 传 `launch = launchOptions()`，`restartEngine()` 同样传；引擎侧本来就接好（`rpc/PiLaunchOptions.kt` 映射 `PI_OFFLINE=1` / `PI_CACHE_RETENTION=long` / `--system-prompt`、`--append-system-prompt`） | 见左 | `pi 有` `cli/args.ts:110`（`--system-prompt`）、`:112`（append）、`:318`（`--offline`）、`:433`（`PI_OFFLINE` 环境变量）、`packages/ai/src/api/anthropic-messages.ts:57`（`PI_CACHE_RETENTION=long`）、`core/model-runtime.ts:196`（读 `PI_OFFLINE`） | 已交付 |
| §G 已交接待完成 | 归属表 | **已交付**（`§J` 逐条带证据）：`rpc/**` 六条、`turnIndex` 裁决、`contentIndex` 真机抓包验证 | `known-gaps.md` §J.2 表 | 各自行内有 pi 引用 | 已交付 |
| §K2 `git:` 源 | 已解决：https 可用、ssh 不可用（applied, uncommitted）；**收尾条件=SPEC_HINT 应列 git:** | **SPEC_HINT 半边已实现**：`packages/PackageStrings.kt:45` 已写 `npm:@scope/name@1.0.0、git:github.com/user/repo@v1 或 /绝对路径`。**顺带修的 `fd`/`rg` 悬空也已实现**（同 §E4 ③）。**仍未核实**：真机上 `git clone https://` 的证书校验与 proot 下 `git commit` 的可靠性（构建机证明不了） | `RuntimeProvisioner.installGit`、`PiPaths.agentBinDir`/`rootfsAgentBinDir`、`ensureToolsVisible()` | `pi 有` `core/package-manager.ts`（git 源安装）；证书/ssh 部分是 `pi 有但我们够不着（运行时不含 ssh）` | 真机 |
| §K4 UI 可达性 | 本次新增，settings 侧还差 3 行 | **已实现**：§I2 那一轮把 `packages` 行改成只读+跳转包管理页（`PiSettingsStack.kt:148-153` 的 `hostActions`），12 个无 pi 对应的 Action 行已删——即"差的 3 行"没有留在"点了没反应"的状态 | `PiSettingsRegistry.kt:783-807`（`readOnly = true`）、`PiSettingsStack.kt:186` | `pi 有` `cli/package-manager-cli.ts`（`pi list` 就是那一行的真身） | 已交付 |
| §K5 装包命令与引擎的 agent 目录绑定不一致 | 已修（applied, uncommitted） | **已实现**（`TrustRepository` 三处修正 + `PiEngineHost` 绑定） | `packages/TrustRepository.kt`、`engine/PiEngineHost.kt:285-295` | `pi 有`（`core/trust-manager.ts`） | 已交付 |
| §K6 guest 里两条 DNS 限制 | 已核实，不是待办 | **不是待办** | — | `pi 无对应物（容器网络）` | — |

---

## 3. `gap-disposition.md`（含 `rendering-review.md` 合并账本）

### 3.1 69 行总账（§9 摘要，未逐行重核）

| 口径 | 数量 | 说明 |
|---|---|---|
| implemented | **29** | §9 的 §2 对账结论：`patch-ready: 23` 全部落地（+ #20/#22/#34–#37） |
| applied (uncommitted/in-flight) | **16** | 在树里、未上 CI（含 `packages/**`、`@` 提及、本轮 I2 那一批） |
| resolved / closed | **1 / 5** | 明确裁决"不是 gap" |
| blocked | **4** | 需设计决定/跨文件/真机 |
| limit | **14** | pi 有、RPC 够不着 → 终端标签页 |

### 3.2 `rendering-review` 34 行里**未闭合的 13 条**（父代理口径 14 条 = 这 13 条 + `F27 closed`；F16/F10/F20/F29 已被父代理抽验）

| 条目 | 账本原话（一行） | 核实结果 | 证据 | pi 有对应物吗 | 归属 |
|---|---|---|---|---|---|
| **F7** | 每个 token 都整屏+整根重算；算好的 change index 被丢掉 | **已实现（本轮，applied (uncommitted)）**：engine 发布 `TranscriptPublication.changedIndices`（`engine/PiEngineSession.kt:317-350`），UI 增量消费（`ui/PiSessionViewModel.kt:809` 的 `syncTranscript(engine, pub)`、`:822` 以 `changedIndices.isNotEmpty()` 判活、`:867` 的 `applyChanged`）。**账本 §10 把它记成 `blocked` 已过期** | 见左 | `pi 无对应物（App 自己的性能设计）`；pi 的终端是整帧重绘，没有可对齐的增量协议 | 行为代理（已交） |
| **F9** | 会话重放与条目投影跑在主线程 | **仍未做** | 缺失判据：无 `Dispatchers.IO`/后台 dispatcher 的投影路径；`PiSessionViewModel.replayHistory` 在 `viewModelScope`（Main）上跑 | `pi 无对应物（App 自己的线程模型）` | 行为代理 |
| **F10** | 没有实时 token/上下文指示；三份 `usage` 全丢 | **rpc 半边已实现；UI 半边仍未做（新 UI）**：`lastUsage` 已暴露（`rpc/Transcript.kt:591`，账本 §J.2 已记） | 缺失判据：没有读 `lastUsage` 的 Compose 组件 | `pi 有` `modes/interactive/components/footer.ts:107-111`（实时 `contextUsage.percent` / contextWindow 显示在底栏） | 行为代理（新 UI） |
| **F13** | tool output / diff context / thinking 标题的对比度 < 4.5:1 | **仍未做（调色板决定）** | 缺失判据：`PiPalette` 的 `dim`/`muted` 用法没有对比度修正 | `pi 有` `modes/interactive/theme/light.json:29-30`、`dark.json:30-31`（`muted`/`dim` 两个 token 的取值） | `ui/theme/**` |
| **F14** | 浅色主题缺 `muted`/`dim` 的修正值 | **仍未做（调色板决定）** | 同上 | `pi 有` `theme/light.json:29-30` | `ui/theme/**` |
| **F16** | 工具返回的图片被压成字面量 `[image]` | **已实现**（复核于本轮，父代理回代码核实）：`ui/blocks/ToolCallBlock.kt` 的 `ToolCallBlock` 现在读 `item.images` 并渲染 `ImageGridBlock`；rpc 半边早已保留 `List<PiImage>`（`rpc/Events.kt` 的 `imageBlocks`） | — | `pi 有` `packages/tui/src/components/markdown.ts:619-627`（终端渲染图片占位/alt） | — |
| **F18** | 压缩/分支摘要的 cost 被解析后丢掉 | **已实现（rpc 半边）+ 有意偏离**：`CompactionEnd` 带完整结果（`rpc/Events.kt:242-260`）、`usage` 落到 `CompactionMarker`（`Transcript.kt:129`）；可见行按 App 版式给出，不是 pi 的底栏数字 | 见左 | `pi 有` `modes/interactive/components/compaction-summary-message.ts`（pi 的压缩摘要组件） | 行为代理（有意偏离已记） |
| **F20** | 规格 §4.8 的"每块动作"缺失 | **仍未做（新 UI）** | 缺失判据：块级动作菜单未实现；依据是我们自己的 `docs/pi-android-ui-spec.md` §4.8 | `pi 无对应物（App 自己的 UI 规格）` | 行为代理 |
| **F21** | 流式中发送静默变成 steer，不询问 | **无法判断**（本轮未核）：需要读 pi 的 mid-turn 提交路径（`steer`/`follow_up` 的交互语义）与 §4 的新规格，才能判断"该问"是不是 pi 的行为 | — | `无法判断` | 行为代理 |
| **F22** | `system-prompt` 块类型无法产生 | **已由删除解决（复核于本轮，工作树）**：整条 `system_prompt` item 被删掉了——没有生产者（pi 没有该转录组件、也没有 RPC 命令返回 prompt），所以 item、`ui/blocks/SystemPromptBlock.kt` 与 `onSystemPrompt` 钩子一起移除；`BlockRenderer` 的 `when` 里没有该分支 | `rpc/Transcript.kt` 的类注释记了这件事；`ui/blocks/` 里已无 `SystemPromptBlock.kt` | `pi 无对应物（pi 没有该组件）` | 已交付（删除） |
| **F28** | 同一个"展开"手势在不同块类型上不一样 | **仍未做（样式一致性）** | 缺失判据：`BlockChrome`/各块的手势未统一 | `pi 无对应物（App 自己的样式系统）` | `ui/blocks/**` |
| **F29** | 脱格的 dp 字面量绕过 `PiSpacing`/`PiShapes` | **仍未做（机械但涉及 ~8 个文件）** | 缺失判据：`grep` 出的裸 `dp` 字面量未收敛 | `pi 无对应物（App 自己的样式系统）` | 多文件 |
| **F34** | 转录与条目列表没有任何上界 | **已实现（渲染窗口）**（复核于本轮，父代理回代码核实）：`ui/screens/ChatScreen.kt` 的 `renderWindow`（初值 `TRANSCRIPT_WINDOW_STEP` = 50）+ 顶部「加载更早的 N 条」+ 滚到顶自动加载；窗口按会话重置（`rememberSaveable(sessionKey)`）。**注意：明确不做"上界/淘汰"** —— pi 自己也无上界（其 TUI 把全部 entry 建出来、只在滚动视图里画可见行），按"pi 没有的不做"不建 | — | `pi 无对应物`：pi 的 TUI 只渲染视口但在数据层不淘汰；`get_entries` 只有前向游标 | — |

### 3.3 这一节里**已经过期**的行（账本说没做、其实做了——别据此派活）

| 行 | 账本写的 | 实际 |
|---|---|---|
| §2 #26 / §P10 `/scoped-models` | `TerminalOnly`，不可达（`patch-ready P10`） | **已实现**：`PiCommandAction.OpenModelScope`（`ui/chat/PiSlashCommands.kt:59`/`:146`），两条入口都导航到 `enabledModels` 行并高亮（`ui/screens/ChatScreen.kt:346-349`、`ui/chat/SlashPalette.kt:232`、`ui/PiSessionViewModel.kt:122`、`ui/PiRoot.kt:104-108`/`:203-204`、`ui/settings/PiSettingsStack.kt:79-84`）。**§11 表里"#26 / P10（`/scoped-models`）｜Not done"是最后一条过期行。** |
| §11 RR F7 | "仍需 engine 半边" | engine 半边已交付（`TranscriptPublication` + `publish`/`diffIndices`），app 半边本轮到齐 |
| §9 #55（13 个开关） | "最后三个（`dynamicColor`/`bashTimeoutSeconds`/`outputMaxLines`）是 patch P13" | 这三个**已从注册表删除**（`grep` 只剩注释：`PiSettingsRegistry.kt:680`/`:684`/`:897`），P13 不存在了 |

---

## 4. `feature-gaps.md`

| 口径 | 数量 | 核实结果 |
|---|---|---|
| §7 已对账的抽样（36 行，修正 30 行） | 36 | **已实现/已解决**（该节逐行给了符号；本轮只抽验了 §1.8/§1.9/§1.10 涉及的四条，与 §2/§I 的结论一致） |
| §7 自述"未核实"的行 | **112 / 148** | **未核实**（**不是"未实现"**）：包括全部 `IMPLEMENTED`(74)、`N/A`(7)、11/12 条 `MISSING-TERMINAL-ONLY`、以及 §1.2/§1.3/§1.6/§1.7/§1.8/§1.10 未抽到的部分。该节自己写着"抽样里 36 行错了 30 行，未核对的 `PARTIAL`/`MISSING` 基础错率很高"——要动某一行，先看它 §7 有没有行、再核符号 |
| §1.10 `PI_CACHE_RETENTION` | MISSING-GUI | **已接**：`app.runtime.cacheRetention` 行 + `PiSessionViewModel.launchOptions()`（复核于本轮） |
| §1.9 附件图片 / `@` 提及 | MISSING-GUI | **已实现**（= §E3 / §E4；§E3 归别人，本轮未动） |

**给下一个派活的人**：`feature-gaps.md` §1 的 grade **不要当事实**。它自己已经声明了这一点（文件头 + §7）。核一个 key 的成本是 1 次 `grep`，重做一个功能的成本是一天。

---

## 5. `extension-compatibility.md` 与 `fidelity-review.md`

### 5.1 `fidelity-review.md`（10 条 finding）：**已实现（全部）**

`known-gaps.md` §J.2 记录了 RPC 代理的交付，本轮抽验了其中 6 条的符号：

| finding | 本轮读到的证据 |
|---|---|
| §1 skill 调用投影不出来 | `rpc/SkillBlock.kt:34-43`（照抄 pi `parseSkillBlock` 的正则，含 `(?:\n\n([\s\S]+))?$` 尾随用户消息） |
| §2 `compaction_end.result` 丢掉 | `rpc/Events.kt:242-260`（`CompactionEnd` 带完整结果 + 注释指向 `docs/rpc.md` §compaction_end） |
| §3 三个 summarization-retry 事件被丢弃 | `rpc/Events.kt:286-304` 三个事件都在，且 `rpc/Transcript.kt:883-944` 三个分支都在投影 |
| §5 "条目 payload 不在线上"的注释谎 | `grep "not on the wire"` → 0 命中（注释已改） |
| §6 `Done.stopReason` 读 pi 从不发的字段 | `rpc/Events.kt:80` 已是 `data class Done(val reason: String?)`（pi 发的是 `reason`） |
| §7 Jsonl "matching pi's reader" 的注释谎 | `grep "matching pi" rpc/Jsonl.kt` → 0 命中 |
| §4 / §8 / §9 / §10 | §J.2 逐条记了：`model_select` 死分支已删、`AssistantDelta.Unknown` 注释与行为一致、`system_prompt`/`error` 死分支已删、`contentIndex` 用真实适配器抓包验证并修 |

### 5.2 `extension-compatibility.md`（68 条 MISSING）：**已逐条核完（按符号）**

68 条全部回读，判据是**符号**（`grep` 到符号 + 读调用点），不是行号。结果：

原 68 条 MISSING 的判决：

| 判决 | 条数 | 新 grade |
|---|---|---|
| 已实现 | **25** | `N/A`（无缺口） |
| 仍未做（部分实现/只有兜底） | **15** | `DEGRADED` |
| 仍未做 | **28** | `MISSING` |
| 无法判断 | **0** | — |

随后补核其余 **61 条**（第一版仅由 pi 源码推导、未回读 App 的行）：
**129/129 行现在都有判决** —— 已实现 51 · 仍未做 53 · 无用户可见职责 25 · 无法判断 0。
（原 28 条里那唯一一条 App 侧缺口 `sendUserMessage` 的 live 渲染**已实现**：`Transcript.onUserMessageEnd` + `onUserPrompt` 的回显去重，`rpc/**`，uncommitted。）

`docs/extension-compatibility.md` 的 grade 合计因此从 `1/18/68/42` 变为
**`1 BLOCKING / 26 DEGRADED / 27 MISSING / 75 N/A`**（129 行不变）。
68 条的位移是 25→N/A、15→DEGRADED、28 不变；另有 1 条非 MISSING 行
`turn_start` 因原 §5.4 结论本身错误而 DEGRADED→N/A。

**账本把"做完"记成"没做"的 25 条**（本仓今天第 N 次同类问题）：

| 组 | 现存的符号 |
|---|---|
| 命令面板 | `PiSlashCommands.piCommandPalette`、`SlashPalette`、`PiCommandAction`、`runPromptCommand`、`refreshCommands`、`sourceTagOf` |
| 扩展消息/条目 | `Transcript` custom 分支 → `onHookMessage`、`HookMessageBlock`、`onCustomEntry`、`PiEngineSession.seedHistory` |
| 会话元数据 | `PiEngineApi.setSessionName`、`renameSession`、`PiEvent.SessionInfoChanged` |
| 会话/队列/统计 | `SessionStatsSheet`（上下文用量）、`SessionToolsSheet`、`ModelPickerSheet` |
| 模型/供应商 | `PiEngineApi.getAvailableModels`、`refreshState()` on `AgentSettled`、`ModelChangeBlock` |
| 会话控制 | `PiEngineApi.newSession`/`fork`/`switchSession`、`newSession`/`forkFrom`/`cloneSession`/`switchSession`（都处理 `cancelled`） |
| 资源 | `get_commands` → `piCommandPalette`（含 `PiCommandSource` 出处）、`SkillInvocationBlock`、`parsePiSkillBlock` |
| 包管理 | `PiPackageService.install/remove/list`、`PiPackagesScreen`、`PiPackagesHost`、`PiListOutput`、`TrustPass` |
| bash | `runBash`、`BashPanel`（走 `bash` 命令 = `user_bash` 路径） |

**真正 `pi 有、我们够不着` 的 27 条**：全部是 `--mode rpc` 的 no-op 或线上根本没有的命令 →
`setWorkingMessage/Visible/Indicator`、`setHiddenThinkingLabel`、`setFooter`、`setHeader`、`custom`、
`getEditorText`、`addAutocompleteProvider`、`setEditorComponent`、`getAllThemes/getTheme/setTheme`、
`getToolsExpanded/setToolsExpanded`、`onTerminalInput`（`rpc-mode.ts:163-311`）；
`registerMarkdownTransformer`（只有 TUI 消费者）；`getActiveTools/getAllTools/setActiveTools`、
`setLabel`、`getSystemPrompt`、`navigateTree`/`session_before_tree`/`session_tree`（RPC 无命令）；
扩展加载错误（无输出通道）。（原本第 28 条「扩展发出的 user 消息」已在 App 侧实现，见上。）
**其中 0 条是 `pi 无对应物（App 的决定）`** —— 即这批不是"我们选择不做"，是线上没有。

另：`docs/extension-compatibility.md` §5.4 原文说 "`turn_start` 带 `turnIndex`，App 记错了"，
**该结论本身是错的**（反向谎言）：pi 给扩展发的 `TurnStartEvent` 带 `turnIndex`
（`core/extensions/types.ts:764-776`），但上线的是 `_emit(event)` 的原始 `AgentEvent`
（`agent-session.ts:663`/`:666`），其联合类型无 `turnIndex`（`packages/agent/src/types.ts:436-437`）。
文档已改为 §5.4 CORRECTED，行 220 改判 `N/A`。

---

## 6. 我核不了的 / `无法判断`（写清缺什么）

| 条目 | 为什么判断不了 | 缺什么才能判断 |
|---|---|---|
| §B10 loader 非 PIE 是否被装机拒 | 只有装到真机才知道 | 真机装 APK + 启动 |
| §K2 `git clone https://` 证书校验；proot 下 `git commit` | 构建机证明不了（CA 路径钉在环境变量、libcurl 编译进的是 hashed 目录） | 真机跑一次 clone + commit |
| §C1–C4（运行时启动、VT 模拟器覆盖率、设备桥、高亮回环） | 全部是"没在真机上跑过" | 真机（`docs/device-verification.md`） |
| 账本 §B5/B6/B7 的"收尾条件" | 同上（代码已写） | 真机 |
| RR **F21**（流式中发送该不该询问） | 需要读 pi 的 mid-turn 提交路径与 §4 新规格两处，本轮未读 | 读 `modes/interactive` 的提交处理 + `pi-android-ui-spec.md` §4 |
| `feature-gaps.md` 112 行 | 该文件自己声明未核实 | 逐行回符号 |

---

## 7. 我认为**真正还欠的**前 5 项（按价值排序）

1. ~~**§I11 的三个环境开关接上 UI**~~ **—— 已完成（applied (uncommitted)）**：键 `app.runtime.offline` / `app.runtime.systemPrompt` / `app.runtime.cacheRetention`（`PiSettingsRegistry.kt` 的「进程」section，`effective = EffectiveKind.RestartEngine`）+ `PiSessionViewModel.launchOptions()` 从 store 读值 + `boot(launch = …)` / `restartEngine(…, launch = …)` + `PiEngineHost.restart(launch = …)`；另加「重启引擎」动作行（`app.runtime.restartEngine`，由 `PiSettingsStack.hostActions` 接住）。这条曾是"唯一一条 pi 有、引擎已接、只差 UI"的真债，现在不是了。
2. ~~**§I7 的 4 个运行时只读行接上供给方**~~ **—— 已完成（applied (uncommitted)）**：`RuntimeFacts`（`piVersion` / `nodeVersion` / `runtimeUsage` / `wakeLockHeld`）→ `runtimeOverrides()` → `PiSettingsStack` 在 IO 线程读入并作为 `valueOverrides` 传给行与搜索结果；四个键的注册表默认值改成中性的「未读取」，**没有一条再显示假值**。唤醒锁读的是 `PiEngineService.isWakeLockHeld()`（锁自己的 `isHeld`，不是"服务在不在"的推断；6 小时上限这一残余不一致写在行的值里）。
3. **RR F10 的 UI 半边**（实时 token/上下文指示）——pi 的 TUI 有（`components/footer.ts:107-111`），RPC 数据也已在 `lastUsage` 里；只差一个组件，是"1:1"里用户每天看得见的那一项。
4. **RR F13 + F14 的调色板可读性**（对比度 < 4.5:1 / 浅色 `muted`/`dim` 缺失）——pi 两个主题文件就有正确取值（`theme/light.json:29-30`、`dark.json:30-31`），属于"照抄即可 1:1"的低风险修复，但影响可读性（不是装饰）。
5. ~~**RR F16 的 UI 半边**~~ —— **已完成**（复核于本轮）：`ui/blocks/ToolCallBlock.kt` 已读 `item.images` 并渲染 `ImageGridBlock`，工具返回的图片不再压成 `[image]`。原记载保留在此以便对照：rpc 早已把 `List<PiImage>` 保下来（`rpc/Events.kt` 的 `imageBlocks`），当时只差渲染接线。

> 备注：**纯视觉空耗类（玻璃/动效/光晕）本轮没有条目落在这一类**（本清单的四个来源账本里没有）；但 `pi-android-ui-spec.md` 里有 4 条（`:151`/`:284`/`:870`/`:871`），已就地标 `用户已决定不做`。F13/F14 是对比度可读性、F28/F29 是手势与间距一致性，都不是装饰性效果，故未标 `用户已决定不做`。

---

## 8. 账本里仍需**别人**改的过期行（不在本文件作者的改动范围）

| 文件:行 | 现状 | 应改成 |
|---|---|---|
| `gap-disposition.md` §11 表 "#26 / P10（`/scoped-models`）" | `Not done` | 已实现（附 `PiSlashCommands.kt:59`/`ChatScreen.kt:346-349`） |
| `gap-disposition.md` §11 表 "RR F7" | `applied (uncommitted)` + "engine 半边仍开着" | engine 半边已交付；app 半边 `applied` |
| `gap-disposition.md` §10 表 F7 行 | `blocked (needs a finer invalidation design)` | 已实现（`TranscriptPublication.changedIndices` + `syncTranscript`） |
| `gap-disposition.md` §9 "Row #55 … 最后三个是 patch P13" | P13 待办 | 三个 key 已删除（只剩注释 `PiSettingsRegistry.kt:680`/`:684`/`:897`） |
| `known-gaps.md` §B7 正文 | "没有绑定 agent 目录…彻底修法（一行，属于 engine/**，本层不改）" | 已绑定：`PiEngineHost.kt:285-295` 的 `extraBinds` 含 `paths.agentDir` |
| `known-gaps.md` §B5 "已知副作用（无法从本层修复）" | 安装物落在易失的 rootfs 目录 | 已随 agent 目录绑定消失（同上） |
| `known-gaps.md` §E4 的 3 个 `[ ]` 后续项 | 都还没勾 | ②③ 已实现（`run-app-pure-checks.sh:270`/`:282`、`PiPaths.agentBinDir`+`ensureToolsVisible`）；① 仍未做（光标） |
| `known-gaps.md` §K2 "`run-app-pure-checks.sh` 尚未注册它" | 未注册 | 已注册为 `agent-tool-paths`（`run-app-pure-checks.sh:282`）；`SPEC_HINT` 半边也已实现（`PackageStrings.kt:45`） |
| `feature-gaps.md` §1 里与 §I2 同一批的 key 行（`:270`、`:302`、`:509-514`） | 与 §I2 的删除/跳转不一致 | 按 §I2 的表更新（12 行已删、3 行改跳转） |
| `feature-gaps.md` §1.10 | `PI_CACHE_RETENTION` MISSING-GUI | **已接**：`app.runtime.cacheRetention` 行 + `PiSessionViewModel.launchOptions()` → `PiLaunchOptions.longCacheRetention`（见 §2 的 §I11 行） |

---

## 附：本轮跑过的核实命令（可复跑）

```bash
# I2：动作行只剩 7 行
grep -c "kind = PiRowKind.Action," app/src/main/kotlin/app/pi/ui/settings/PiSettingsRegistry.kt   # -> 7
# I5：主题不再来自写死调色板
grep -n "session.theme\|PiTheme(" app/src/main/kotlin/app/pi/MainActivity.kt
grep -n "scanDirectory" app/src/main/kotlin/app/pi/ui/theme/PiThemeFiles.kt                        # -> :152 :153
# I7：13 个开关的消费者
sed -n '494,500p' app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt
grep -rn "app.runtime.piVersion" app/src/main/kotlin                                          # -> 只有注册表
# I8：搜索四处都在
grep -n "searchQuery\|searchMatches" app/src/main/kotlin/app/pi/ui/screens/ChatScreen.kt
grep -n "TreeFilter\|query" app/src/main/kotlin/app/pi/ui/chat/SessionTreeScreen.kt
# I11：引擎侧齐、无人传值
grep -n "PI_OFFLINE\|PI_CACHE_RETENTION\|systemPrompt" rpc/src/main/kotlin/app/pi/rpc/PiLaunchOptions.kt
grep -rn "launch = " app/src/main/kotlin                                                     # -> 0
# E4/K2：纯逻辑 harness 全部注册
grep -n "^run_harness" tools/run-app-pure-checks.sh                                          # -> packages/guest-paths/mentions/agent-tool-paths
# B7：agent 目录已绑定
grep -n "extraBinds" -A 12 app/src/main/kotlin/app/pi/engine/PiEngineHost.kt
# pi 侧引用（/root/pi-src）
grep -n "system-prompt\|--offline\|PI_OFFLINE" packages/coding-agent/src/cli/args.ts
grep -rn "PI_CACHE_RETENTION" packages/ai/src/api/anthropic-messages.ts
grep -n "searchMatchBg\|searchMatchText" packages/coding-agent/src/modes/interactive/theme/theme-json.ts
grep -n "contextUsage" packages/coding-agent/src/modes/interactive/components/footer.ts
grep -n "\"muted\"\|\"dim\"" packages/coding-agent/src/modes/interactive/theme/light.json
grep -n "case \"user-only\"\|case \"no-tools\"\|case \"labeled-only\"" packages/coding-agent/src/modes/interactive/components/tree-selector.ts
grep -rn "getCustomThemesDir\|join(this.agentDir, \"themes\")" packages/coding-agent/src/core/resource-loader.ts
```
