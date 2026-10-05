# pi 1.0.1 → 1.0.3 逐文件对比（升级评审）

判据与做法（可复现）：

```bash
cd /tmp && npm pack @earendil-works/pi-coding-agent@1.0.1 @earendil-works/pi-coding-agent@1.0.3
# 解开两个 tarball；npm 包里只有 dist/，没有 src/，所以用 dist 下每个 .js.map 的
# sourcesContent 把 packages/coding-agent/src 还原出来（244 → 245 个文件），再 diff -r。
# 兄弟包（pi-ai / pi-tui / pi-agent-core / pi-codemode / pi-mcp / chord）同样两个版本各 pack 一次。
# 纯资产（theme/*.json）直接 cmp。
```

结论一句话：**契约面（RPC / 主题 / 设置 / 工具渲染 / CLI）逐字节相同**，真正会让 App 出错的上游变化只有一处 —— Azure provider 改名；其余要动的都是版本字符串与由版本决定的夹具/许可标签。

## 一、`@earendil-works/pi-coding-agent`（主包）

| 文件 | 1.0.1 | 1.0.3 | App 需不需要动 | 要动的话动哪个文件 |
| :--- | :--- | :--- | :--- | :--- |
| `src/modes/rpc/rpc-types.ts`（+ rpc 方法表） | — | **逐字节相同** | 不要 | — |
| `dist/modes/interactive/theme/theme-schema.json`（56 令牌） | — | **逐字节相同** | 不要 | — |
| `dist/modes/interactive/theme/dark.json` / `light.json` | — | **逐字节相同** | 不要（`PiPalette.kt` 不用重抄） | — |
| `src/core/settings-manager.ts`（设置键与默认值） | — | **逐字节相同** | 不要（`PiSettingsRegistry.kt` 键表不用动） | — |
| `src/cli/args.ts`（CLI 参数） | — | **逐字节相同** | 不要 | — |
| `src/core/tools/{bash,read,write,edit,grep,find,ls}.ts`（工具结果文本） | — | **逐字节相同** | 不要 | — |
| `src/core/tools/renderers/{bash,read,write,edit,grep,find,ls}.ts`（卡片口径 / `Elapsed`/`Took`/时长梯子） | — | **逐字节相同** | 不要 | — |
| `src/core/model-resolver.ts` | 默认厂商键 `azure-openai-responses` | 改名成 **`azure`** | **要** | `app/src/main/kotlin/app/pi/ui/settings/PiSettingsRegistry.kt` 的 `providerOptions`（写进 `settings.json` 的 `defaultProvider`，旧 id 新引擎不认，静默回退） |
| `src/core/model-config.ts` | 只有 `samplingParams` | 新增 `samplingParamsByThinkingLevel`（按 thinking level 的 schema） | 需确认（见下） | 目前不用改：`PiModelsMerge` 保留未知键；若要暴露给用户才动 |
| `src/core/provider-composer.ts` | 只合并 `samplingParams` | 新键也按级合并 | 需确认（同上） | 同上 |
| `src/core/bash-executor.ts` | 自己拼 `tmpdir()/pi-bash-*.log` | 改走新 `output-files.ts`（0600、`wx`） | 不要（文件名前缀不变，App 不读临时文件） | — |
| `src/core/tools/output-accumulator.ts` | 自己拼临时路径 | 同上 | 不要 | — |
| **新增** `src/utils/output-files.ts` | 不存在 | 临时输出文件统一入口（0600、`flag:"wx"`） | 不要 | — |
| `src/extensions/codemode/execute.ts` | `image()` 只把图放进结果 | `image()` 另存临时文件，并在图前加 `[Image saved to <path> (…)]` 文本 | 不要（App 不解析 codemode 结果） | — |
| `src/extensions/codemode/tool.ts` | `image()` 描述一行 | 描述补了"也存盘并给出路径" | 不要 | — |
| `src/extensions/mcp/tools.ts` | 自己拼 `pi-mcp-*.ext` 并 `writeFile(mode 0600)` | 走 `writeOutputFile`（0600 + `wx`） | 不要（前缀与扩展名不变） | — |
| `src/modes/interactive/interactive-mode.ts` | — | 新增"安装被替换/删除"提示、认 `ENOTTY`、给 stdin 挂 error handler | 不要（纯 TUI） | — |
| `package.json` | `version 1.0.1`；`engines.node >=22.19.0`；`@earendil-works/*` = `^1.0.1` | `version 1.0.3`；`engines.node` **不变**；`@earendil-works/*` = `^1.0.3`（另有三个 devDependency 同升），其余依赖名与约束逐条相同 | 不要（Node 24.19 不动）；版本由 `PI_VERSION` 管 | `tools/fetch-runtime.mjs` |
| `dist/bundle/**` | — | 重新打包（chunk 名/hash 全变） | 不要（App 不依赖 bundle 文件名） | — |

## 二、兄弟包（都由 `@earendil-works/*` 从 `^1.0.1` 升到 `^1.0.3`）

| 文件 | 1.0.1 → 1.0.3 | App 需不需要动 | 要动的话动哪个文件 |
| :--- | :--- | :--- | :--- |
| `pi-ai/dist/providers/data/*`（官方模型目录） | 仍 42 份、`schemaVersion` 仍 6、内层键仍 `${type}:${id}`；`azure-openai-responses.json` → `azure.json`；条目 1536/59/20 → **1539 chat / 59 image / 23 classifier**；`amazon-bedrock` / `nvidia` / `opencode-go` / `openrouter` / `vercel-ai-gateway` 的条目有增改 | 不要改代码（`PiOfficialCatalog` 按目录遍历）；KDoc 实测数字要改 | `app/src/main/kotlin/app/pi/packages/PiOfficialCatalog.kt` 的 KDoc |
| `pi-ai/dist/models.generated.js` 等 | 模型表增改 | 不要（App 读 `providers/data/**`，不读 `models.generated`） | — |
| `pi-ai` 的 azure provider / api 文件 | `azure-openai-responses.*` → `azure.*`，新增 `api/azure-openai-config.js` | 不要 | — |
| `pi-tui/dist/keybindings.js` | `tui.editor.cursorLineStart/End` 去掉 `ctrl+home`/`ctrl+end`；`tui.altScreen.top/bottom` 改用它们 | 不要（App 不转写 pi-tui 键位、不跑 TUI）；夹具版本字段要升 | `app/src/test/resources/pi-latex-fixtures/cases.json`、`pi-html-fixtures/cases.json` 的 `piTui` |
| `pi-agent-core` / `pi-codemode` / `chord` | 只有 `package.json` 的 `version` | 不要 | — |
| `pi-mcp` | 只有 `package.json` + `CHANGELOG.md` | 不要 | — |

## 三、四类重点变化的结论

- **① 主题令牌**：`theme-schema.json` 与 `dark/light.json` **逐字节相同**，仍是 56 个令牌 —— 无增/删/改名/改值，`PiPalette.kt`、`PiThemeFiles.kt`、`ChromeColor.kt` 都不用动。
- **② RPC 契约**：`rpc-types.ts` 与 rpc 方法表**逐字节相同** —— 无改名/删除/新字段，`rpc/Commands.kt` 不用动。
- **③ 工具渲染口径**：七个工具的实现与 `renderers/*.ts` 全部**逐字节相同**，`Elapsed`/`Took`/时长梯子、`details.truncation` 键、`read`/`grep`/`find`/`ls` 的页脚文本都没变 —— `ToolBlockChrome` / `ToolCardText` / 各 block 不用动。
- **④ settings**：`settings-manager.ts` **逐字节相同**，无增/删/改；`models.json` 侧上游新增 `samplingParamsByThinkingLevel`（1.0.2），App 的读-改-写本来就保留未知键，所以**不必须动**。

## 四、会让 App 读错/显示错的地方（必须先做）

1. **Azure provider 改名**（唯一一处）：`PiSettingsRegistry.kt` 的 `providerOptions` 里 `"azure-openai-responses"` → `"azure"`。
2. **版本字符串**：`tools/fetch-runtime.mjs` 的 `PI_VERSION`、重新生成的 `tools/pi-engine.lock.json`、`README.md` 两处、`docs/known-gaps.md` §B2 复核标题、`app/src/main/assets/licenses/**` 的版本标签、`tools/build-license-assets.py` 的标签清单、三个夹具的版本字段、`PiOfficialCatalog.kt` 的实测数字。
3. **没有别的**：其余上游变化都在 App 够不着的地方（TUI、codemode 内部落盘、临时文件工具函数）。

## 五、可选新特性（等用户定，本轮不做）

- `models.json` 的 `samplingParamsByThinkingLevel`：按思考级别（off/minimal/low/medium/high/xhigh/max）覆盖 `temperature`/`top_p` 等，只对 `openai-completions`/`openai-responses`/`azure-openai-responses` 生效。要做的话是模型编辑器的字段 + 说明，不是"不改会错"。
- pi-ai 目录里新增的 chat/classifier 条目（1536→1539 chat，20→23 classifier）：导入表单自动就有，无需动代码。
- `azure` 现在也服务 Foundry chat-completions 部署（`azure/deepseek-v4-pro`）：若要在导入屏加 Azure 行，需要新的 provider 预设（含 `baseUrl`/`api`/扫描方式），本轮没做。

## 六、不确定 / 未做

- 本机没有重跑 `tools/run-app-pure-checks.sh`（按任务书要求的"便宜检查"只跑了 `pi-contract`、夹具 `--check`、`check-nested-comments.py`）。
- 上游 KDoc 里大量 "pi 1.0.1" 的行号引用是**历史复核记录**，本轮只改了 `docs/known-gaps.md` §B2 的标题与 `PiOfficialCatalog.kt` 的实测数字；其余（如 `AttachmentBudget.kt`、`SessionResume.kt`、`PiExifOrientation.kt`）没有逐条重读。这些文件在 1.0.1→1.0.3 之间是否仍然逐字对应，本轮**没有全量核对**。
- `docs/pi-android-app-design.md:640` 与 `docs/startup-latency.md:355` 的 **provider 列表**里还写着 `azure-openai-responses`，现在应写 `azure`；同一篇 `:636` 的 **API 列表**里的 `azure-openai-responses` 是**对的**（pi 的 `KnownApi` 没改，改的只是 provider id）。按任务纪律没有动这两份文档。
- `app/src/test/resources/pi-html-fixtures/cases.json` 只改了 `piTui` 标签（连同生成器 `tools/HtmlFixtureSpans.java` 的 `PI_TUI` 常量）：它的期望值来自 pi-tui 的 `markdown.js`，而该文件两版**逐字节相同**；要完整重生成需要 markdown-jvm 0.7.9 + kotlin-stdlib 跑 Java 那一步，本轮没做。
- `pi-tool-fixtures/tools.json` 与 `pi-latex-fixtures/cases.json` 是**重跑收集器**得到的：前者只动版本字段，后者除版本字段外还更新了 `$` 语料里指向仓库文档的 `provenance` 行号（文档在本轮之前已经改过，属既有漂移，不是 pi 变化）。

## 七、本轮实际执行与结果

改动（全部是版本/事实更新，没碰别的代理在改的文件）：

| 路径 | 改了什么 |
| :--- | :--- |
| `tools/fetch-runtime.mjs` | `PI_VERSION` 1.0.1 → 1.0.3；补 1.0.1→1.0.3 的分析段 |
| `tools/pi-engine.lock.json` | `node tools/fetch-runtime.mjs --refresh-engine-lock` 真装重生成（147 条；只有 8 个 `@earendil-works/*` 版本变，`@aws-sdk/*` 未漂） |
| `app/src/main/kotlin/app/pi/ui/settings/PiSettingsRegistry.kt` | `providerOptions` 的 `azure-openai-responses` → `azure`（必须改） |
| `app/src/main/kotlin/app/pi/packages/PiOfficialCatalog.kt` | KDoc 实测数字 → 1539/59/23 |
| `README.md` | 两处版本号 |
| `docs/known-gaps.md` | §B2 复核标题 → 1.0.3 |
| `app/src/main/assets/licenses/*`（4 个） | 由 `tools/build-license-assets.py --fetch-missing` 重生成，只版本标签变 |
| `tools/build-license-assets.py` | LICENSE 标签清单补 v1.0.2 / v1.0.3（八个标签 sha256 相同） |
| `app/src/test/resources/pi-tool-fixtures/tools.json` | `pi` 字段 1.0.3 |
| `app/src/test/resources/pi-latex-fixtures/cases.json` | 重跑收集器：`piTui` 1.0.3 + 文档 provenance 行号 |
| `app/src/test/resources/pi-html-fixtures/cases.json`、`tools/HtmlFixtureSpans.java` | `piTui` / `PI_TUI` 1.0.3 |
| `tools/pi-1.0.3-upgrade-review.md` | 本文件 |

验证：

- `node tools/pi-contract.mjs`：`surface` / `theme` / `tables` / `tooltext` / `session` / `catalog` 全 PASS。
- `node tools/collect-tool-fixtures.mjs --check`：17 cases match pi 1.0.3 exactly。
- `node tools/collect-latex-fixtures.mjs --check`：与 pi-tui 1.0.3 输出一致。
- `python3 tools/check-nested-comments.py`：OK（332 个 Kotlin 文件）。
- `behaviour` / `extensions`：见回报（单跑）。
- 未跑 `tools/run-app-pure-checks.sh`（按任务纪律交 CI）。

## 八、收尾：KDoc 里"声称 pi 版本"的引用（CI 抓到的 `image-attachment-budget`）

CI 红在：`AttachmentBudget.kt` 的类 KDoc 写「pin 住的引擎 **1.0.1**」，而
`AttachmentBudgetCheck.kt` 的 1b 组把这句话和 `tools/pi-engine.lock.json` 里的版本对起来 ——
锁文件已经是 1.0.3，于是断言失败（`FAIL AttachmentBudget 的 KDoc 标的正是 lock 里那个版本`）。

修的时候只改"声称版本/口径"的引用，改前逐个 `cmp` 过它们指的上游文件在 1.0.1→1.0.3 之间
**逐字节相同**（所以行号与结论仍然成立）：

| 文件 | 改了什么 | 依据（两版 `cmp` 相同） |
| :--- | :--- | :--- |
| `app/src/main/kotlin/app/pi/ui/screens/AttachmentBudget.kt` | KDoc 的 `1.0.1` → `1.0.3`（两处） | `dist/utils/image-resize-core.js` |
| `app/src/main/kotlin/app/pi/session/SessionResume.kt` | `pi 1.0.1 的 dist/main.js` → `1.0.3`；探针那句保留"1.0.1 实测"并注明 1.0.3 同文件未变 | `dist/main.js`、`dist/core/session-manager.js` |
| `app/src/main/kotlin/app/pi/ui/render/PiHtml.kt` | 两条分支的版本 `1.0.1` → `1.0.3` | pi-tui `dist/components/markdown.js` |
| `app/src/main/kotlin/app/pi/ui/screens/PiExifOrientation.kt` | 期望值来源 `1.0.1` → `1.0.3` | `dist/utils/exif-orientation.js` |
| `app/src/test/kotlin/app/pi/ui/screens/PiExifOrientationCheck.kt` | 三处夹具来源 `1.0.1` → `1.0.3` | 同上 |
| `app/src/test/kotlin/app/pi/ui/screens/AttachmentBudgetCheck.kt` | 说明性注释改成不写死版本（它举的是"KDoc 写死的版本 vs lock"这个失败模式） | 本身不改断言逻辑 |
| `app/src/main/assets/pi-extensions/pi-android-bridge/index.ts` | 两处 `pi 1.0.1` → `1.0.3` | `core/extensions/types.d.ts`、`core/agent-session-runtime.js` |
| `tools/run-app-pure-checks.sh` | html 夹具来源注释 `pi 1.0.1` → `1.0.3` | 同上 |

**故意没改**（历史记录，不是"当前 pin 是什么"的主张）：`PiOfficialCatalog.kt` 里
"1.0.0 与 1.0.1 都复核过 …" 的历次复核清单；`tools/fetch-runtime.mjs` 与
`tools/build-license-assets.py` 的历次升级分析段；`RuntimePayloadStateCheck.kt` 的
"1.0.0 → 1.0.1 实测链路"；`AttachmentBudgetCheck.kt` 的"0.86.1 → 1.0.1 那次漏掉的东西"；
`rpc/.../TranscriptReducerTest.kt` 的 `interactive-mode.ts:3452-3459`（1.0.1）行号引用
（该文件 1.0.3 变了约 +15 行，行号已漂，但那份文件当时正被另一个代理改动，没有并入本轮）。

单跑验收（用 `tools/run-app-pure-checks.sh` 同一套 staged 编译器/库，只编译并运行这一个 harness）：

```
compile errors: 0
...
PASS AttachmentBudget 的 KDoc 标的正是 lock 里那个版本
harness: OK (all checks passed)
```

## 九、升级后真机起不来：`failed to unpack pi-engine.tgz: Too many symbolic links encountered`

**根因在解包器，不在载荷。** `TarExtractor.resolveSafely()` 的旧实现把**整条路径**（含最后那个
分量）交给 `canonicalFile`。覆盖解压时，软链条目自己的路径上正躺着上一次解包留下的同名软链，
`canonicalFile` 跟着它走到目标，于是新软链被写在**目标的位置**：

- 载荷 `./node_modules/.bin/yaml -> ../yaml/bin.mjs` canonical 到 `node_modules/yaml/bin.mjs`
  （**包根**文件）→ 把 `../yaml/bin.mjs` 写在包根，相对展开回去就是它自己 = **自指软链**；
- 紧接着的条目 `./node_modules/yaml/bin.mjs` 一 canonical 就抛 `ELOOP`，
  `RuntimeProvisioner.extractAsset` 把它包成设备上那句话。

证据：
- `app/src/main/kotlin/app/pi/runtime/TarExtractor.kt:322-335`（旧 `resolveSafely` 返回
  `resolved.canonicalFile`）+ `:124-138`（软链分支写在 canonical 之后的 `target`）。
- 用同一份 `pi-engine.tgz` 逐条模仿旧解包逻辑：空目录 **16467 条全过**；在**已解开的树上再解
  一次**，在 `./node_modules/yaml/bin.mjs` 处 `ELOOP`，现场留下
  `node_modules/yaml/bin.mjs -> ../yaml/bin.mjs`（自指）。
- 对照数据：用旧锁 `npm ci --ignore-scripts --omit=dev --omit=optional` 装出 **1.0.1 的树**，
  `node_modules/.bin` 软链**同为 9 条、路径与目标逐字相同**（含 `yaml -> ../yaml/bin.mjs`）
  → **不是新载荷引入的**。`RuntimeProvisioner` 的 digest 计划只重解**变了的那一个**载荷，
  所以只有 pi-engine 被覆盖解压、也只有它报错（git 载荷那 163 条软链同理会坏，只是这轮
  digest 没变所以没重解）。缺陷自 `278d3d1` 起潜伏。

**选定的修法：解包侧（B）** —— `resolveSafely` 只 canonical 父目录、最后一个分量保持字面
（`TarExtractor.kt:303-365`）。理由：
- 它修的是根因，而且**所有载荷共用**这一条路径；
- 越界检查不降级：父目录仍然 canonical 并做包含检查，载荷放 `bin -> /` 再放 `bin/evil`
  照样被拒；最后那个分量本就不需要 canonical —— 写之前 `unlinkIfPresent` 一定先把该位置的
  节点（软链/文件/空目录）拿掉，从不跟着它打开；
- **自愈**：已经被弄坏的树在下一次成功解包时会被修好（旧自指链由 `unlinkIfPresent` 先删）。
- **为什么不选 A（组装侧不发/解引用 `.bin`）**：`.bin` 是 npm 的正常布局（guest 侧
  `node_modules/.bin` 有用途），而 `tools/fetch-runtime.mjs:1033/1047` 的 `dereference: false`
  与 `:860-865` 给 git 载荷写的"发软链省 4 MiB"是同一套有意设计，去掉或解引用会误伤体积与
  语义；更关键的是 A 只盖住这一种形状 —— 任何"目标落在包根"的软链都会再次触发，B 才是把
  这一类关掉。C（两边都做）在 B 修好后是多余。

**回归防线（CI 可验）**：`app/src/test/kotlin/app/pi/runtime/RuntimePayloadStateCheck.kt` 新增
**E11**：用这个文件里已有的 tar 构造器造一条 `node_modules/.bin/yaml -> ../yaml/bin.mjs` 加
真实目标文件，**先**把旧链摆在目标目录（模拟覆盖解压），再解一次，断言不抛错、链接建在自己
路径上、目标是真文件而不是自指链。E11 在旧代码上会因 `ELOOP` 变红，在新代码上绿 —— 也就是
说这条 bug 从此由 CI 挡。

单跑验收（`runtime-payload-state`，用 `tools/run-app-pure-checks.sh` 同一套 staged 编译器/库，
只编译运行这一个 harness）：

```
compile errors: 0
PASS E11 an existing symlink entry is re-extracted: no failure
PASS E11 …the link is recreated at its own path, not at its target
PASS E11 …and the target is a real file, not a self-referential link
PASS E11 …holding the payload's bytes
harness: OK (all checks passed)
```

还需真机验证：
- 从已装 1.0.1 的设备**原地升级**到带修复的版本 → pi-engine 覆盖解压不再报 ELOOP，引擎能起；
- **已经被这轮弄坏的设备**：下一次成功解包自愈；或点「重建运行时」（wipe 能吃掉自指链 —— 用
  Kotlin `File.deleteRecursively` 的等价 Java 复刻验证过）；
- 升级后 `git --version` / `node -v` / `rg --version` / `fd --version` 正常（git 载荷这轮没被
  重解，但值得确认）；
- `node_modules/.bin/pi` 指向的 `dist/bundle/cli.js` 仍是真文件（不是软链）。



