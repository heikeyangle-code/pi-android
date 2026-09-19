# 截图目录

主 `README.md` 的「界面一览」一节引用这里的 7 个文件（1 张横幅 + 6 张截图）。
**文件名固定**，换图请沿用同名 —— 换掉的图必须仍然满足下表的「必须是同一屏」这类约束。

| 文件 | 该拍什么 |
|---|---|
| `banner-three-themes.jpg` | 横幅：**同一屏内容**的三套主题并排（浅色 / 青绿 / 暗紫）。由下面三张按 `hstack` 合成，不单独拍 |
| `chat-light-tool-cards.jpg` | 浅色主题的对话页：至少一张 `read` 工具卡（读的是图片更好，能看出一张卡里并排画两张图） |
| `chat-dark-tool-cards.jpg` | 暗色主题的对话页：用户消息 + 可折叠的思考块 + 一张 `edit` 卡（带 `+N/−M` 的 diff） |
| `chat-theme-purple.jpg` | 一个主题下的对话页：思考块 + `write`/`edit` 卡 + **一条失败的命令卡**（带退出码）与它后面的重试 |
| `chat-theme-teal.jpg` | **与上一张同一个会话、同一个滚动位置**，只换主题文件 —— 这一对是「主题是一张令牌表」的证明 |
| `workspace-session-changes.jpg` | 工作区：本次会话改过的文件 + 那个文件的 diff |
| `workspace-resources.jpg` | 工作区：「这个目录的资源」展开（技能 / 提示词 / 扩展 / 主题 与它们的来源路径） |

横幅的合成命令（两张同屏图必须**内容一致**，否则横幅会自相矛盾）：

```bash
ffmpeg -y -i chat-light-tool-cards.jpg -i chat-theme-teal.jpg -i chat-dark-tool-cards.jpg \
  -filter_complex "[0][1][2]hstack=inputs=3,scale=1920:-1" -q:v 4 banner-three-themes.jpg
```

## 这些图的来路

截图里的主题**不是应用内置的**，是**使用者自己写的**主题文件（`~/.pi/agent/themes/*.json`，
按 pi 的令牌表写）—— 换图时也请用自己写的主题，别把示例配色说成内置主题。
截图只放本应用自己的界面，不混入设计稿或第三方应用。
图片是设备原始分辨率（1373×3051）的手机截屏，`banner-three-themes.jpg` 是它们的合成件。
