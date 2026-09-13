# pi 契约（`tools/pi-contract.mjs`）

## 它解决什么

这个 App **不分叉 pi**。它做的是三件依赖 pi 内部事实的事：

1. **读 pi 写的文件** —— `models.json`、`auth.json`、`models-store.json`、会话 JSONL（还有 `settings.json`、`trust.json`）；
2. **发 pi 的 RPC 命令、解析 pi 的事件** —— `rpc/**` 与 `engine/**`；
3. **把自己的扩展装进 pi** —— `app/src/main/assets/pi-extensions/**`，App 的全部设备能力都在这里。

而载荷里的引擎是**钉住版本**的（`tools/fetch-runtime.mjs` 的 `PI_VERSION`）。于是：**改一个版本号，App 照样编译，但上面三件事所依赖的事实可能已经变了。** 这不是假设 —— 已经发生过两次，都是到了设备上才发现：

- **§M11**：模型能力被静默降级（图片不再发给模型、1M 上下文被读成 128k）；
- **§M12**：`models.json` 里 pi 允许而 App 未建模的键被静默删除。

两次的共同形状是：**App 相信了 pi 的一个语义，而那个语义只写在一句注释的 `file:line` 里，没有任何东西在版本变化时提醒我们。**

CI 今天断言的是"载荷存在"（`ci.yml` 里那个载荷步骤）和"许可证与钉住的载荷一致"。**没有一条断言"App 拿它做的事仍然成立"。** 这个脚本就是那一条。

## 三层，各自能挡什么

| 层 | 做什么 | 能挡住 | 挡不住 |
|---|---|---|---|
| **surface** | 把 App 发出去的 RPC 命令名、它应答的扩展 UI 方法名，逐个到**钉住的引擎**里去找 | 改名、移除 | 语义变化（名字还在、意思变了） |
| **behaviour** | 用**钉住的引擎真跑**：`models.json` 的替换语义、`modelOverrides` 的合并语义 | 语义变化 | 跑不到的分支、只在真机上出现的行为 |
| **extensions** | 把 App 随包的扩展装进钉住的引擎，让它加载 | 扩展 API 变化 | 扩展内部逻辑是否仍正确（那是 harness 与真机的事） |

**为什么 surface 层扫的是 `dist/` 而不是 pi 的源码**：CI 里没有 pi 的源码树，而且真正要紧的正是**随包发出的那个产物**。字符串能活到 `dist/` 里，App 才可能跟它对话。

## 怎么跑

```bash
node tools/pi-contract.mjs                      # 自己装钉住的版本（读 PI_VERSION）
node tools/pi-contract.mjs --pi <packageDir>    # 用已经装好的那份（开发时用这个，省一次下载）
```

CI 里是一个独立 job（`contract`，ubuntu-latest + node 24），缓存键取 `tools/fetch-runtime.mjs` 的哈希 —— 版本一改就重装一次。**每个失败都打印"该回去重读哪个 App 文件"**，因为重点不是"pi 变了"，而是"这是你要重新核对的地方"。

## 什么时候该往这里加一条断言

**判据：App 里凡是"因为 pi 是这么做的，所以我们这么做"的决定，都该有一条。**

不是"引用过 pi"就加 —— 背景性引用（"pi 的这个文件在这里"）不需要。要加的是**决定**：一个 if、一个默认值、一条过滤规则、一次"故意不写"。

现在覆盖的决定（每条在脚本里都写了它是谁的依据）：

| 断言 | 依赖它的 App 代码 |
|---|---|
| 不带 `input` 的声明会变成 text-only | `PiCredentialService.save` 的"只声明 pi 不知道的"规则 |
| 不带 `contextWindow` 的声明会变成 128000 | 同上（"省略等于降级"） |
| `modelOverrides` 变一个字段 | 同上（App 推荐/保留它的理由） |
| `modelOverrides` 保住其它字段 | 同上（否则它与 `models[]` 无从区分） |
| 引擎仍应答 `get_commands`（= 扩展加载成功） | 全部 `android_*` 设备能力 |
| 每个 App 发出的命令名仍存在 | `rpc/Commands.kt` 与它的调用方 |
| 八个扩展 UI 方法名仍存在 | `ui/extension/**` 的对话框与 `onExtensionChrome` |

**还没覆盖、该补的**（写在这里而不是假装没有）：

1. **会话存储的事实**：`--session-dir` 显式传入时是平铺还是按 cwd 分子目录（`core/session-manager.ts:1551-1552`）、会话何时落盘、枚举时看多深。这些正在被 `docs/session-lifecycle.md` 那轮工作查清，查清后**必须补进这里** —— 它们正是"退出再进来会话就没了"那个 bug 的形状。
2. **`models-store.json` 的路径与形状**（`<agentDir>/models-store.json`，`Record<providerId,{models:Model[]}>`）。App 的 `PiModelCatalog` 读它；形状一变，读取器会**静默返回空表**（设计如此：猜比空更糟），所以这条必须有断言。
3. **扩展 API 的形状**：现在只断言"能加载"。真正的形状检查要在扩展里用一个最小例子去用 API（例如注册一个工具并断言它出现在 `get_commands`/工具列表里）。
4. **载荷本身**（`RuntimeProvisioner` 解包出的目录布局：`/opt/pi`、`/opt/node`、proot 二进制名）—— 属于另一个方向（`tools/fetch-runtime.mjs` 与 `RuntimeSelfCheck`），CI 目前只断言摘要存在。

## 版本升级时的正确顺序

1. 改 `tools/fetch-runtime.mjs` 的 `PI_VERSION`（**唯一写版本号的地方**）；
2. 跑 `node tools/pi-contract.mjs` —— 它会把"哪一条事实变了、该回去读哪个文件"直接打出来；
3. 按它指的文件重新核对并改 App（**不要只改注释里的 `file:line`**：那些引用是给后续的人看的，而这里断言的是它们背后的行为）；
4. 许可证资产要重新生成（CI 那条 `Verify the licence assets match the pinned runtime` 会挡住漏做）；
5. 记住换载荷对设备的后果：`runtime-revision.txt` 一变，App 下次启动会 **wipe 并重解包 guest rootfs**；`/root/.pi/agent`（会话、凭证、settings）是 bind 的，会留下。

## 一条纪律

**这个脚本的每一条断言，都必须能在"失败时"说清后果。** 只打印 `expected ["text"], got [...]` 的断言在这个仓库里没有价值 —— 值钱的是后面那句"所以 `PiCredentialService.save` 的规则要重新推导"。加断言时如果写不出那句后果，说明它还不是一条"决定"，先别加。
