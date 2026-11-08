# 全局 / 本项目：pi 的两层配置，与 App 里每个入口的对应

> 依据：pi 的 `docs/configuration.md`、`docs/settings.md`、`docs/mcp.md`、`docs/packages.md`，
> 以及 `dist/core/settings-manager.js`。结论先行，细节在后。

## pi 的模型

- **用户级（全局）**：`<agentDir>/…`（App 里 = guest 的 `~/.pi/agent`）。**所有工作区共用**。
- **项目级**：`<工作区>/.pi/…`，**项目被信任后**才读（`sessionDir` 除外）。
- 同一个键：**项目覆盖全局**（对象递归合并，数组整体替换）。资源数组（`packages` / `extensions` /
  `skills` / `prompts` / `themes`）是**合并**。`SYSTEM.md` / `APPEND_SYSTEM.md`：项目的整份替换全局的。
- **只能设在全局**：`cacheWarming`、`defaultProjectTrust`、`httpProxy`、`selfUpdateCommand`
- 上下文文件 `AGENTS.md` 另算：从全局目录、工作目录、**所有父目录**里找，不需要信任。

## 每个入口现在怎么对应

| pi 里的东西 | 全局 | 本项目 | App 里在哪 | 状态 |
|---|---|---|---|---|
| 设置键（`settings.json`） | `<agentDir>/settings.json` | `<工作区>/.pi/settings.json` | 设置 → 各分组页（只有一层，同 pi 的 `/settings`） | 与 pi 一致：界面只写全局；项目层设置靠手改 `.pi/settings.json` 或 Pi 文件 |
| MCP 服务器 | `<agentDir>/mcp.json` | `<工作区>/.pi/mcp.json` | 设置 → MCP 服务器（页内 全局 \| 本项目；项目页可单独关掉某个全局服务器） | 已有 |
| 扩展包 | `pi install` | `pi install -l` | 设置 → 扩展与资源 → 扩展包与项目信任（装/删时选范围） | 已有 |
| 技能 / 提示模板 / 主题 / 扩展 | `<agentDir>/<kind>/`、包里的清单 | `.pi/<kind>/`、`.agents/…`、`pi install -l` 的包 | 工作区 → 资源：清单与逐条开关都由 pi 自己的包管理器与 `pi config` 逻辑给出（`assets/guest/pi-resources.mjs`）；项目层三态 继承 / + / − | 已有 |
| 系统提示 `SYSTEM.md` / `APPEND_SYSTEM.md` | agentDir | `.pi/` | Pi 文件 | 已有 |
| 项目信任 | `defaultProjectTrust`（仅全局） | 每个项目一条决定 | 扩展包与项目信任页 | 已有 |
| 会话 | 按工作目录分组 | — | 对话列表；点别的工作区的对话会切工作区 | 本次修了芯片不跟随 |

## 设置页为什么没有「本项目」切换

pi 自己的 `/settings` 界面只写全局设置；项目层只有资源开关（`pi config`）、`pi install -l`、MCP、项目信任这几处有专门入口。App 照此：设置页只有一层，不另造「本项目」视图（曾经加过，已撤）。

## 已知缺口（没有假装做完）

- 资源开关已做（见上表）。包的安装 / 更新 / 移除 / 项目信任在 设置 → 扩展与资源 → 扩展包与项目信任；逐条开关在 工作区 → 资源。
- 没编译、没上机跑过。
