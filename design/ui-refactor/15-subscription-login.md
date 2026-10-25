# 订阅登录（原生，不经过终端）

> 用户裁定：「订阅登录帮我做成吧，不要去终端」「不影响升级，支持 pi 支持的所有订阅」。

## 结论

**不重写 OAuth，也不加新页面。** 在「模型与供应商」屏顶部加一张「订阅账号」卡，
登录时开一个 `PiSheet`；OAuth 流程本身由 **guest 里钉住的那份 pi** 执行
（`pi-ai` 的 `builtinProviders()` → `auth.oauth.login(interaction)`），App 只做两件事：
画 pi 的提示（URL / 设备码 / 选项 / 输入框），把拿到的 credential 写进 `auth.json`。

三个文件就是全部：

| 文件 | 作用 |
|---|---|
| `app/src/main/assets/guest/pi-oauth.mjs` | guest 侧进程：`builtinProviders()` 过滤 `auth.oauth` 得到清单；跑一次 `login()`；stdin/stdout 一行一个 JSON |
| `packages/PiOAuthHost.kt` | App 侧：投放脚本、`RuntimeSelection` 起 node、收发事件、收进程树、按 pi 版本缓存清单 |
| `ui/settings/SubscriptionLogin.kt` | 卡片 + 登录 sheet（状态机全由 pi 的事件驱动） |

写盘走 `PiCredentialService.setOAuthCredential` → `PiAuthStorage.setOAuth`
（pi 的锁、`0600`、镜像副本、`PendingModelRestart` 标记），与 API Key 同一条路。

## 为什么不是「在 Kotlin 里实现九套 OAuth」

pi 1.3.0 的订阅提供商是九家，各有各的流程：

| provider | pi 的流程 |
|---|---|
| `anthropic` | `select[browser/copy_code]` → PKCE + 回环回调（`127.0.0.1:53692`）或粘贴授权码 |
| `openai` | ChatGPT：PKCE + 回调；**要 `urn:uuid:` 设备 id** |
| `openai-codex` | `select[browser/device_code]` → 回调或设备码 |
| `github-copilot` | 企业域名 `text` → 设备码 → GitHub token **再换** Copilot token |
| `openrouter` | PKCE + 回调 → 换 API key |
| `kimi-coding` | 设备码 |
| `meta` | 设备码 → 身份 token **再换**一次 minted API key |
| `xai` | 设备码 |
| `radius` | `select[browser/device-code]`，网关地址由 provider 配置决定 |

client id / scope / 端点 / 额外 token 交换 / 特殊 header 全是**钉住版本引擎的内部实现**，
不是 API。抄进 Kotlin 就等于把「升级一次引擎就 `invalid_client`」写进 App。

## 「不影响升级」是怎么做到的

1. **清单是运行时读出来的**：`builtinProviders().filter(p => p.auth.oauth)`。pi 升级新增
   订阅提供商（例如 1.3.0 的 `openai`，0.87 时代没有），这一屏自动多一行，App 不用发版。
2. **流程也是运行时跑的**：同一份 `pi-ai` 的 `login()`，App 不参与协议细节。
3. **写盘只依赖 pi 的凭证格式**：`{type:"oauth", access, refresh, expires, …}`，其余键
   （`accountId` / `enterpriseUrl` / `scope`）原样保留。
4. **刷新不需要 App 做**：pi 在下次请求时用自己的 refresh 函数续期
   （`auth/resolve.ts:129-147`）。界面把「过期」与「未登录」分开说 —— 过期且有 refresh
   token 时显示「下次使用会自动续」，而不是骗用户重新登一次。
5. **清单有缓存，但键是 `pi-ai/package.json` 的 version**：升级引擎后自动失效重读；
   平时打开这一屏不再付一次 node 冷启动。

## 交互（没有新页面）

- 卡片在「模型与供应商」屏顶部（设置首页顶栏 → 模型与供应商），每行：
  pi 的订阅名 + 状态（未登录 / 已登录 · 有效期 / 已过期会自动续）+ 「登录」/「退出」。
- 点登录 → `PiSheet`：
  - 设备码：大号代码（17/24 SemiBold 等宽，不新开字号）+ 「在浏览器中打开」+ 轮询等待；
  - 浏览器流程：URL + 输入框（`manual_code` 提示）；
  - `select`：pi 给的选项直接做成按钮（例如「在本机浏览器完成 / 复制授权码」）；
  - `text` / `secret`：输入框（例如 Copilot 的企业域名）。
- **粘贴整条回调地址永远可见**：浏览器停在打不开的 `localhost` 页面是这条路的常见结局，
  退路不能藏。pi 自己的 `parseAuthorizationInput` 会解析整条 URL / `code#state` / 裸 code。
- 成功：写 `auth.json` → 置「等重启」→ 这一屏出现重启卡；重启后订阅厂商的模型进列表。

## 已知边界

- **需要打包好的运行时**：清单与流程都来自 rootfs 里的 `pi-ai`。引擎从没启动过时，
  卡片显示「先启动一次引擎」而不是空列表。
- **回环回调端口**：`anthropic` 用 `127.0.0.1:53692`、`openai` 用 `:1455`、
  `radius` 用 `:1456`（pi 的选择）。proot 共享 loopback，手机浏览器可以直接回到 guest；
  端口被占（另一个登录实例）时粘贴退路仍然可用。
- **`radius` 的网关地址**：来自 provider 配置，不在这一屏填；pi 自己在流程里问（`text`）。
- **退出登录**走与「删除厂商」同一条 `PiCredentialService.remove`：删 `auth.json` 的
  凭证、`models.json` 里同名厂商块、以及指向它的选择键引用（默认模型/循环列表）。
  行为与手动删除一致，没有第二套语义。
