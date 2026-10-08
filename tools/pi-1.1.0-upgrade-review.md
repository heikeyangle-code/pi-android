# pi 1.0.3 → 1.1.0 逐文件对比（升级评审）

判据与做法（可复现）：

```bash
cd /tmp && for v in 1.0.3 1.0.4 1.1.0; do
  mkdir -p pack-$v && (cd pack-$v && npm pack @earendil-works/pi-coding-agent@$v)
done
# 解开 tarball；npm 包里只有 dist/，所以用 dist 下每个 .js.map 的 sourcesContent 把
# packages/coding-agent/src 还原出来（245 → 246 个文件），再 diff -r。
# 兄弟包（pi-ai / pi-tui / pi-agent-core / pi-codemode / pi-mcp / chord）同样各 pack 一次。
# 纯资产（theme/*.json、docs/*.md）直接 cmp。
```

**主判据是仓库自己的契约检查，不是人眼逐文件看**：把 1.1.0 装出来，然后

```bash
node tools/pi-contract.mjs --pi <1.1.0 包目录>            # 九组
node tools/pi-contract.mjs --pi <1.1.0 包目录> --only=<组>  # surface|theme|tables|tooltext|session|catalog|bundled|behaviour|extensions
```

**九组在 1.1.0 上全 PASS**（见第七节）。所以结论一句话：
**契约面（RPC / 主题 / 设置键 / 工具结果文本 / 会话条目 / 模型目录形状 / models.json 语义 /
扩展加载 / 打包的 rpc 入口）没有一处改名、删除或改变语义**；App 侧要动的全是版本字符串与
由版本决定的夹具/许可标签，另有两处**可选**新信号。

## 一、`@earendil-works/pi-coding-agent`（主包，46 个文件有差异）

| 文件 | 1.0.3 → 1.1.0 | App 需不需要动 | 要动的话动哪个文件 |
| :--- | :--- | :--- | :--- |
| `modes/rpc/rpc-types.ts` | **逐字节相同** | 不要 | — |
| `modes/interactive/theme/theme-schema.json` + `{dark,light}.json` | **逐字节相同**（51 必需 + 5 可选 / 59 实体，值与名都不变） | 不要 | — |
| `core/settings-manager.ts` | 设置键一个没增删；`outputPad` 注释改了；工具表解析抽成 `applyToolModifiers` / `getToolListError`，**语义逐条相同** | 不要 | — |
| `core/tools/{bash,read,write,edit,grep,find,ls}.ts`（结果文本 / 页脚） | **逐字节相同**（`read.ts` 只多了 codemode 用的 `outputSchema` / `structuredContent`） | 不要 | — |
| `core/tools/renderers/bash.ts` | `Took` 改成优先用记录下来的 `durationMs`（1.1.0 新字段），没有它才回落到自己的钟 | 不要（见第五节①：**可选**改成用 `durationMs`） | — |
| `core/tools/renderers/edit.ts` | 多了 `outputPad` 内边距 | 不要 | — |
| `core/extensions/types.ts` | 新增 `durationMs`（工具执行耗时）、`outputPad`、`ToolLoadout.getPromptGuidelines()`、`agent_settled.aborted` | 不要（见第五节①） | — |
| `core/agent-session.ts` | 把 `durationMs` 透给扩展事件；`agent_settled` 多 `aborted`；其余是内部整理 | 不要 | — |
| `cli/args.ts` | `--tools`/`--exclude-tools` 支持 `*` 通配与 `+name`/`-name`（并校验不能混用），新增 `--no-mcp` | **不要**：App 不传这两个开关，工具选择走设置键 `defaultTools`（那个的语义没变） | — |
| `utils/syntax-highlight.ts` | formatter 改成按行调用（修「多行字符串/注释只有第一行有颜色」） | 不要（那是 pi 自己的 TUI 高亮器；App 的高亮走 `pi-highlight` 扩展 + 自己的作用域映射） | — |
| `utils/ansi.ts` | 新增 `splitIncompleteAnsiSuffix`，修「分片流把转义序列切断」 | 不要（App 的 `rpc/…/Ansi.kt` 作用在完整结果/整行上） | — |
| `utils/image-resize{,-core,-worker}.ts` | worker 回包打类型标签（`node --watch` 下 Node 会往同一通道发自己的消息） | 不要（App 不跑 `--watch`；2000×2000 / 4.5 MiB / q80 的口径由 behaviour 组复验过） | `ui/screens/AttachmentBudget.kt` 的**行号**要跟着走（+10，见第三节） |
| `modes/interactive/**`、`program-status-reporter.ts`（新增）、`utils/clipboard.ts`、`theme.ts` 的 `subst` | TUI 专属 | 不要（App 用 RPC，没有 TTY） | — |
| `package.json` | 七个 `@earendil-works/*` `^1.0.3`→`^1.1.0`；`engines.node` 仍 `>=22.19.0`；其余依赖一行未改 | 不要（Node 24.19 载荷不动） | `tools/fetch-runtime.mjs` 的 `PI_VERSION` |

## 二、兄弟包

| 文件 | 1.0.3 → 1.1.0 | App 需不需要动 |
| :--- | :--- | :--- |
| `pi-ai/dist/providers/data/**` | 文件集合不变（仍 42 份 + `.manifest.json`、`schemaVersion` 仍 6）；14 份 provider 的数据变了；条目 1539/59/23 → **1605 chat / 61 image / 26 classifier** | 读取器不要动；`packages/PiOfficialCatalog.kt` 的 KDoc 实测数字要改 |
| `pi-tui` | `dist/components/markdown.js` 与 `src/components/markdown.ts` **都逐字节相同**；变的是 box/text/terminal/tui-alt-screen 与新增 `program-status`（OSC 7501） | 不要（`PiHtml.kt` 的行号仍成立，只改版本标签） |
| `pi-agent-core` | 新增单调钟的 `durationMs`（工具执行耗时） | 不要（见第五节①） |
| `pi-codemode` / `pi-mcp` / `chord` / `pi-telemetry` | 版本号 + 各自内部修复，App 不读 | 不要 |

## 三、必须跟着改的地方（全部是版本/事实）

1. `tools/fetch-runtime.mjs`：`PI_VERSION` → `1.1.0`，并补 1.0.3→1.1.0 的分析段。
2. `tools/pi-engine.lock.json`：`node tools/fetch-runtime.mjs --refresh-engine-lock` 真装重生成（147 条；
   只有八个 `@earendil-works/*` + `@babel/runtime` 7.29.7→7.29.10 动，`@aws-sdk/*` 未漂 —— 所以
   `tools/build-license-assets.py` 里那三个手写字面量本轮不用改）。
3. `app/src/main/assets/licenses/*`（4 个）：`python3 tools/build-license-assets.py --fetch-missing`
   重生成，只版本标签变（`v1.0.4` / `v1.1.0` 标签的根 `LICENSE` 与之前八个标签 sha256 逐字节相同）。
4. 夹具：`pi-tool-fixtures/tools.json`（重跑收集器，17 例只换版本字段）、
   `pi-latex-fixtures/cases.json`（重跑收集器，432+37+8+114+222 条只换版本字段）、
   `pi-html-fixtures/cases.json` + `tools/HtmlFixtureSpans.java`（只换 `piTui` / `PI_TUI` 标签；
   markdown.js 逐字节相同，期望值不动）。
5. `packages/PiOfficialCatalog.kt`：KDoc 实测数字 → 1605/61/26。
6. `ui/screens/AttachmentBudget.kt`：KDoc 里的行号**整体 +10**（`image-resize-core.ts` 在开头多了
   worker 回包的类型标签），`image-resize.ts` 与 `agent-session.ts` 的引用按 1.1.0 重算
   （`:85-110`→`:84-109`、`:105-109`→`:104-108`、`:1890-1910`→`:1937-1959`、`:1900`→`:1947`）。
   该文件的 1b 断言（KDoc 里的版本 vs lock 里的版本）由 `ui/screens/AttachmentBudgetCheck.kt` 钉住。
7. 只换版本标签、引用的上游文件逐字节未变（逐个 `cmp` 过）：`ui/render/PiHtml.kt`（pi-tui markdown）、
   `ui/screens/PiExifOrientation.kt` 与它的 check（`exif-orientation.js`）、
   `session/SessionResume.kt`（该文件 `dist/main.js` 变了，但引用那一段仍在 `:344-351`，已复核）、
   `assets/pi-extensions/pi-android-bridge/index.ts`（`types.d.ts:1481`→`:1494`、
   `resource-loader.js:353`→`:355`）、`tools/run-app-pure-checks.sh`、`README.md`、`docs/known-gaps.md`。
8. `docs/known-gaps.md` §B2 与本仓那笔「大文件行号是旧版本」的既有欠账：补上 1.1.0 的复核结论
   （`session-manager.ts` / `agent-session-runtime.ts` / `rpc-mode.ts` / `docs/sessions.md` /
   `docs/session-format.md` 仍逐字节未变；`agent-session.ts` 变了，引它的行号仍未重算）。

## 四、会让 App 读错/显示错的地方

**没有。** 这一轮九组契约在 1.1.0 上全 PASS —— 没有改名、没有删除、没有语义变化，
`PiPalette.kt`、`PiSettingsRegistry.kt`、`rpc/Commands.kt`、各工具 block 一行都不用动。

## 五、可选新特性 —— 两条**都已落地**（跟着本批推送的第二笔，App 侧）

判据是"不改不会错，改了必须比现在准"，所以两条都只换**事实来源**，不新增界面、不新增计时器。

1. **准确耗时（`durationMs`）**
   - `rpc`：`PiEvent.ToolExecutionEnd` 多一个 `durationMs`（`o.long("durationMs")`，缺席为 null）；
     `ToolCall` 多一个同名字段，`elapsedMs` 从 `endedAt - ts` 改成
     **`durationMs ?: (endedAt - ts)`** —— 正是 pi 1.1.0 自己的口径
     （`core/tools/renderers/bash.ts:99-110`：记录值优先，"it is monotonic and survives reloads"）。
   - 两个入口都取它：**实时**走 `tool_execution_end.durationMs`；**重放**走会话文件里
     `toolResult` 消息的同名键（pi-agent-core 的 `createToolResultMessage` 会写它），
     所以**重开会话后读数不变**。旧会话（pi < 1.1.0 写的）没有这个键，回落墙钟 ——
     与 1.0.3 时代逐字一致。
   - 差别不是舍入：墙钟把工具**之外**的时间也算进去（排队、前后两次模型往返、被中止前的等待）。
2. **`agent_settled.aborted`**
   - `PiEvent.AgentSettled` 从 `data object` 变成 `data class AgentSettled(val aborted: Boolean)`，
     四个使用点跟着改（`Transcript` / `PiEngineSession` / `PiSessionViewModel` 的 `when` 已用 `is`）。
   - 在转录里**只当兜底**：正常中止路径由被中止的 assistant 消息先到达
     （`pi-agent-core` 的 `agent-loop.ts:143` 在那里结束这一轮），`failTurn` 早已写上「回合已中止」
     并把挂着的卡收掉；兜底由 `turnFailureReported`（`agent_start` 与 `reset()` 清）挡住重复，
     **正常路径一行都不多**。它补的是"会话说这轮被中止、而转录里没有任何一行说过"那一种：
     没有它，一次中止会被显示成正常结束，挂着的卡还会一直转。
   - 需要说明：pi 自己的 TUI **不消费**这个字段（它的 `agent_settled` 分支只查 shutdown），
     上游加它是给集成方用的（1.1.0 changelog 的原话），所以这里没有"上游怎么写我就怎么写"
     可抄；App 沿用**已有那条**中止行（同一个 `failTurn`、同一句话），不是新措辞。
3. 新模型（Claude Haiku 5.5、GPT-6 Luna 分类器、llama.cpp 原生分类模型等）：导入表按目录遍历，
   自动就有，不需要动代码。

## 六、不确定 / 未做

- 本仓 KDoc 里大量 `file:line` 引用**指向在 1.1.0 里改过的上游文件**，行号会漂。按文件统计，
  引用数：`interactive-mode.ts` 447、`agent-session.ts` 386、`types.ts` 323、`main.ts` 265、
  `settings-manager.ts` 255、`args.ts` 171…… 合计约 2700 处（`docs/` 1526、`design/` 265，其余在
  Kotlin KDoc）。**本轮没有重算它们**：这是一笔 `docs/known-gaps.md:124` 已经登记过的既有欠账，
  还它的正确做法是「用 difflib 把旧行号映射到新行号，只改被引内容逐字节相同的那一段，
  其余逐条重读并改结论」—— 属于独立一笔，不属于版本升级。
- 本机没有跑 `tools/run-app-pure-checks.sh` 全量（按纪律交 CI）；改动涉及的单个 harness
  （`runtime-payload-state`、`image-attachment-budget`）在真机/CI 上兜底。
- `:rpc` 的单元测试**本机跑过**（本机没有 Gradle 发行版，所以用 `build/pure-checks` 里 staged 的
  kotlinc + Maven 上的 junit 4.13.2 直接编译并跑）：`rpc/src/{main,test}/kotlin` 编译 0 error，
  12 个测试类 **254 条全过** —— 其中第五节那两条新特性的 8 条是新增的
  （`TranscriptReducerTest` 3 条：记录值优先 / 旧会话回落墙钟 / 重放取会话文件里的值；
  `EventsTest` 2 条：`durationMs` 解析与缺席为 null、`aborted` 解析；
  `FidelityFixesTest` 3 条：正常中止只报一次行、没有消息时的兜底会补一行并收卡、未中止什么都不加）。
- `tools/pi-contract.mjs` 的 `behaviour` 组会用真引擎起进程，本轮跑过一遍（PASS）；
  它不覆盖「RPC 里 `durationMs` 真的到了事件里」这一条 —— 那是第五节①的验收内容，
  要真机/真引擎抓一次事件流才能钉。

## 七、本轮实际执行与结果

改动：`tools/fetch-runtime.mjs`（PI_VERSION + 分析段）、`tools/pi-engine.lock.json`（重生成）、
`app/src/main/assets/licenses/*`（4 个，重生成）、三个夹具 + `HtmlFixtureSpans.java`（标签）、
`PiOfficialCatalog.kt`（数字）、`AttachmentBudget.kt`（行号 + 版本）、`PiHtml.kt`、
`PiExifOrientation.kt` + check、`SessionResume.kt`、`pi-android-bridge/index.ts`、
`run-app-pure-checks.sh`、`README.md`、`docs/known-gaps.md`、本文件。

验证：

- `node tools/pi-contract.mjs --pi <1.1.0>`：surface / theme / tables / tooltext / session / catalog /
  bundled / behaviour / extensions **九组全 PASS**。
- `node tools/collect-tool-fixtures.mjs --pi <1.1.0> --check`：17 例与 1.1.0 逐字相同。
- `node tools/collect-latex-fixtures.mjs --pi-tui <1.1.0 的 pi-tui> --check`：与 pi-tui 1.1.0 输出一致。
- `python3 tools/check-nested-comments.py`：OK。
- `python3 tools/build-license-assets.py`（不带 `--fetch-missing`）：清单与 1.1.0 载荷一致。
