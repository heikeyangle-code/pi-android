# 设备桥 v2：7 个工具、一次往返、两页说明

> 状态：设计已定，代码按本文件改。四块能力（VPN / 输入法 / 投屏 / 自动化规则）已删除，
> 不再出现在下面任何清单里。

## 1. 为什么改

- 26 个工具的描述合计 1.2 万字（≈4k tokens/轮），其中大半是同一个能力的别名：
  `ui` / `ui_dump` / `tap` / `swipe` / `input` / `key` / `screenshot` 七个工具讲的是同一块屏幕；
  `io` / `fs` / `files` / `files_list` / `download` 五个工具讲的是同一批文件。
- 一次“点一下输入框再打字”现在要 3 次工具调用（tap → dump → input），模型在两个调用之间
  看不到反馈，容易点错位置。
- 设备能力屏 1303 行、一卡 15 条 bullet，用户原话是“说明文字太多了”。

## 2. 目标工具面（7 个）

| 工具 | action | 覆盖原来的 |
|---|---|---|
| `android_status` | （无） | `android_status` + `android_bridge_status` + `android_device_state` |
| `android_screen` | `look` / `tap` / `type` / `swipe` / `key` / `wait` / `shot` / `steps` | `android_ui` + `ui_dump` + `tap` + `input` + `key` + `keyevent` + `swipe` + `screenshot` |
| `android_app` | `list` / `launch` / `stop` | `android_app` + `android_stop_app` |
| `android_files` | `dirs` / `list` / `read` / `write` / `download` / `import` / `export` | `android_io` + `android_fs` + `android_download` + `android_files_list` + `android_files` |
| `android_shell` | （无） | `android_shell` |
| `android_comm` | `clipboard` / `notify` / `toast` / `vibrate` / `share` / `open` / `tts` | `android_say` + `android_vibrate` + `android_share` + `android_open` + `android_clipboard` + `android_notify` |
| `android_device` | `battery` / `torch` / `location` / `sensors` / `sensor` | `android_device_state` 的一部分 + `android_torch` |

工具描述每条 ≤ 60 字；`promptSnippet` 每条一行；`promptGuidelines` 只保留 3 条安全规则
（`stop` 会丢未保存内容、`shell` 受策略守卫、不可逆动作会二次确认）。

## 3. 动作即反馈（这次“变强”的核心）

`android_screen` 的 `tap` / `type` / `swipe` / `key` 执行后，扩展**立即**调一次
`POST /app/ui/diff`（该端点本来就记着上一次 dump 的树），把结果压成：

```json
{
  "changed": true,
  "screen": "com.tencent.mm",
  "added": ["发送"], "removed": [], "updated": ["输入框"],
  "text": "1 个节点出现：发送"
}
```

- 没有上一次 dump 时先 `dump` 再 `diff`；
- 前后无变化 → `changed: false`，并在 `text` 里写“界面没变，可能没点中”，模型据此重试；
- 失败的 tap/input 仍然抛错，错误文案保持“中文解释 + 下一步”。

`steps` 是一次调用里连做几步：

```json
{ "action": "steps", "steps": [
  { "action": "tap", "text": "搜索框" },
  { "action": "type", "text": "天气" },
  { "action": "key", "key": "enter" }
] }
```

逐步执行，**遇错即停**，返回每步结果 + 最后一次 diff。上限 8 步。

## 4. 技能（随扩展写出的 `pi-android-device`）

只留 3 个文件，正文合计 ≤ 100 行：

| 文件 | 内容 |
|---|---|
| `SKILL.md` | 25 行以内的索引：`look → act（自带 diff）→ 没变化就重试`，以及“哪组没开、哪个工具就不存在” |
| `references/ui.md` | 8 个 action 的参数、`changed:false` 怎么办、常见坑（NOT_FOUND / DISABLED / 截断 / 密码框） |
| `references/env.md` | 路径表（/workspace、/sdcard、agentDir）、Shell 的 uid 2000 与 Shizuku、边界 |

删掉 `references/tools.md`：工具 schema 就是唯一真源，不再抄第二份。
删掉 `references/paths.md`、`shell.md`、`elevate.md`、`pitfalls.md`：内容并入上面两份。

## 5. 设备能力屏（设备能力页）

- 顶部：4 个状态点（无障碍 / Shizuku / 通知 / 已授权目录），只显示状态，点一下去对应系统设置。
- 两张卡，每张最多 3 条 bullet：
  - **屏幕**（`Accessibility`）：读屏、点按、输入、滑动、按键、截图。一行说明 + 开关 + 「无障碍设置」。
  - **系统与文件**（`Basic` + `Shell`）：文件/剪贴板/通知/分享 一行 + 一个开关；Shell 单独一行、
    红色小字标“危险”，默认关。
- 原来的路径表、Shizuku 安装步骤、边界说明全部收进底部「高级」折叠区。
- 可见文字预算：状态点之外 ≤ 200 字；每个 bullet ≤ 20 字。
