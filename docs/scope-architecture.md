# 全局 / 本项目：pi 的两层配置，与 App 里每个入口的对应

> 依据：pi 的 `docs/configuration.md`、`docs/settings.md`、`docs/mcp.md`、`docs/packages.md`，
> 以及 `dist/core/settings-manager.js`。结论先行，细节在后。

## pi 的模型

- **用户级（全局）**：`<agentDir>/…`（App 里 = guest 的 `~/.pi/agent`）。**所有工作区共用**。
- **项目级**：`<工作区>/.pi/…`，**项目被信任后**才读（`sessionDir` 除外）。
- 同一个键：**项目覆盖全局**（对象递归合并，数组整体替换）。资源数组（`packages` / `extensions` /
  `skills` / `prompts` / `themes`）是**合并**。`SYSTEM.md` / `APPEND_SYSTEM.md`：项目的整份替换全局的。
- **只能设在全局**：`cacheWarming`、`defaultProjectTrust`、`httpProxy`、`selfUpdateCommand`
  （`tools/pi-contract.mjs` 的 `checkGlobalOnlyKeys` 对着文档守这份名单）。
- 上下文文件 `AGENTS.md` 另算：从全局目录、工作目录、**所有父目录**里找，不需要信任。

## 每个入口现在怎么对应

| pi 里的东西 | 全局 | 本项目 | App 里在哪 | 状态 |
|---|---|---|---|---|
| 设置键（`settings.json`） | `<agentDir>/settings.json` | `<工作区>/.pi/settings.json` | 设置 → 各分组页顶部 **全局 \| 本项目** 切换 | **本次新增**。以前只有隐式规则（项目文件里恰好有这个键就写项目，否则写全局），用户既看不见也没法新建覆盖 |
| MCP 服务器 | `<agentDir>/mcp.json` | `<工作区>/.pi/mcp.json` | 设置 → MCP 服务器（页内 全局 \| 本项目；项目页可单独关掉某个全局服务器） | 已有 |
| 扩展包 | `pi install` | `pi install -l` | 设置 → 扩展与资源 → 扩展包与项目信任（装/删时选范围） | 已有 |
| 技能 / 提示模板 / 主题 / 扩展 | `<agentDir>/<kind>/`、包里的清单 | `.pi/<kind>/`、`.agents/…`、`pi install -l` 的包 | 工作区 → 资源：清单与逐条开关都由 pi 自己的包管理器与 `pi config` 逻辑给出（`assets/guest/pi-resources.mjs`）；项目层三态 继承 / + / − | 已有 |
| 系统提示 `SYSTEM.md` / `APPEND_SYSTEM.md` | agentDir | `.pi/` | Pi 文件 | 已有 |
| 项目信任 | `defaultProjectTrust`（仅全局） | 每个项目一条决定 | 扩展包与项目信任页 | 已有 |
| 会话 | 按工作目录分组 | — | 对话列表；点别的工作区的对话会切工作区 | 本次修了芯片不跟随 |

## 设置页的「全局 | 本项目」怎么工作

- 读：永远是 pi 看到的**合并值**。
- 写：显式落在所选层级。选「全局」时若本项目另有同名覆盖，行上标「· 本项目」—— 这就是「我改了为什么没变化」的答案。
- 「本项目」视图只列**能被项目覆盖**的行；全局独有键、动作行、只读读数、`app.*` 都不出现。
- 「恢复默认」在项目视图里删的是**项目层的覆盖**（值回到继承全局），不是删全局。
- 项目文件不存在时：只有用户在项目视图里真正改了某项才会建出 `.pi/settings.json`；删键不会为了删而建文件。
- 代码：`ScopedSettingsStore`（装饰层）、`PiSettingsFileStore.writeIn/removeIn/projectDefines`、
  `PiSettingsScopes`（全局独有名单）。纯逻辑用例在 `PiSettingsStoreCheck`。

## 已知缺口（没有假装做完）

- 资源开关已做（见上表）。包的安装 / 更新 / 移除 / 项目信任仍在 设置 → 扩展与资源 → 扩展包与项目信任；逐条开关只在 工作区 → 资源。pi 自己的 `/settings` 界面只写全局，项目层设置只有手改 `.pi/settings.json`、资源开关、`pi install -l` 与 MCP；App 的「本项目」设置视图是比 pi 多出来的便利，不是 pi 的行为。
- 项目视图里的数组型设置（`enabledModels` 之类）按「覆盖」写整个数组，不是 `+/-` 合并；资源数组不在此列。
- 没编译、没上机跑过。
