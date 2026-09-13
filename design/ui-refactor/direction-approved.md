# direction-approved.md —— 方向确认（Gate 文件）

## 展示了哪几版

三个方向初稿，真实可交互的单文件 HTML + 截图，一次性摆给用户：

| 版本 | 逻辑 | 定位 | HTML | 截图 |
|---|---|---|---|---|
| 方向 A · 仪表 | 秒数轮盘 #16 · Terminal-Core Soft-Futurism（`date +%S`=55 → 55%20+1=16） | 读数 + 轨道 | `design-demos/direction-a.html` | `design-demos/shots/a-phone1..5.png`、`a-board.png` |
| 方向 B · 直接操作 | 现实参照 · Linear 移动端（已核实真实资料） | 结构被感觉到、每屏一个主操作、属性即内容 | `design-demos/direction-b.html` | `design-demos/shots/b-phone1..5.png`、`b-board.png` |
| 方向 C · 作者性 | 最佳设计师 · Panic Inc.（Cabel Sasser） | 等宽主角、1px 线分层、颜色只做信号 | `design-demos/direction-c.html` | `design-demos/shots/c-phone1..5.png`、`c-board.png` |

对比页（图片内嵌、手机可读）：`review.html`；用户的手机上下载目录里有四个副本
（`pi-UI重构-方向A-仪表.html` / `-方向B-线性.html` / `-方向C-作者性.html` / `pi-UI重构-三方向对比.html`）。

展示方式与时间：2026-09-13，全部完成后一起展示（含每版的 120% 细节与代价分析）。
方向 A 的初稿当时白屏（多 `<script type="text/babel">` 共享全局作用域导致 `Identifier 'useState' has already been declared`），
父代理用 IIFE 包裹每块修好后重新出图，用户看到的是修好的版本。

## 用户的选择原话

> **「B 吧 其他地方的优点也可以稍微移过来一丢丢。」**

- **选定方向 B（现实参照 · Linear 移动端迁移）。**
- 附带授权：把 A / C 的优点**少量**移过来（「一丢丢」），不是合并三版。

## 因此进入执行：方向 B 的 Full pass（v2）

迁移清单（父代理的艺术总监裁决，写进 `04-direction-b-graft.md`）：

1. **从 A 移**：块脚那条 1px **耗时刻度（长度 ∝ log₂ 毫秒）** 并进 B 的执行轨道节点；状态行补上「输出 / 缓存读」读数（**不做** A 的四格仪表盘）。
2. **从 C 移**：状态一律「**字 + 符号 + 颜色**」三重编码，不允许只靠颜色表意；层级用 1px 线与表面阶梯，不用阴影。
   （C 的另一条「一屏只有一项用 accent」**已被用户否掉**：「不要做 C 的那个一屏幕一个什么了，那个不好维护」——不做全局预算，accent 按组件语义正常使用。）
3. **明确不移**：A 的 12 格 bento 与等距立方示意；C 的「零图标」；任何渐变 / 模糊 / 装饰动效。

## 豁免记录

无。本方向经过了完整的三方向门（Phase 1 澄清 → Phase 2 重述 → Phase 3 spec → Phase 3.5 图片判定 → Phase 4 三版并行 → Phase 5 用户选定），未使用任何豁免通道。
