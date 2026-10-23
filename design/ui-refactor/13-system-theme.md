# 13 · `system` 主题：照 pi 的推导算法做

> 现状：`system` 这个名字在 App 里"可达但不派生"（`PiThemeFiles.kt` 的注释写明了原因：pi 从终端上报的调色板派生，
> 原生看不到终端）。这份文档给出把它做成真的方案。

## 1. pi 到底怎么生成 `system`

源码：`modes/interactive/theme/system-theme.ts`（约 300 行）+ `packages/tui/src/oklab.ts`(233) + `colors.ts` 的 okhsl 辅助。

1. **输入**：终端上报的 `background` 与 ANSI 16 色；三档降级——
   `background + palette` → `background only` → 什么都没有（退回 ANSI 索引）。
2. **色族**：每个 token 属于一个 family（neutral / blue / green / red / yellow / orange / violet / calamine，
   加上 6 个 thinking 族）；family 有 hue、saturation 区间和一个 ANSI slot。
3. **亮度来自对比度曲线**：给定 token 下面那层表面的亮度，曲线给出该 token 需要的 OKLab 亮度
   （深色底近固定亮度；浅色底要求差值随背景变深而增大）；在 OKHSL 里构造，saturation 在黑白附近衰减。
4. **thinking 是一条 7 族渐变**（slate→blue→periwinkle→violet→magenta→red），`thinkingMax` 可选。
5. 测试：`packages/coding-agent/test/system-theme.test.ts` 有金样本。

## 2. Android 上"终端调色板"从哪来

| 方案 | 说明 | 像不像 pi |
|---|---|---|
| **A. App 终端页的 ANSI 16 色**（推荐） | 用户在终端页/设置里能改的那套配色，作为 `system` 的输入；换终端配色 → 整个 App 跟着变，正是 pi 的语义 | **最像**（同一个 recipe、同一个输入源） |
| B. 默认 ANSI + 系统深浅 | 不依赖终端配色，稳定可预期；输入是固定的 pi 默认调色板 + `isSystemInDarkTheme()` | 像（同 recipe，输入固定） |
| C. Material You 取色 | 壁纸取色 | **不像**：pi 明确"状态色不该随壁纸漂移" |

## 3. 移植清单（Kotlin）

| 新增文件 | 内容 | 来源 |
|---|---|---|
| `ui/theme/PiOkhsl.kt` | sRGB↔OKLab↔OKHSL、`okhslColor`、`colorToOkhsl`、`oklabToOkhslLightness` | `tui/src/oklab.ts` + `colors.ts:107-117` |
| `ui/theme/PiSystemTheme.kt` | FAMILIES 表、对比度曲线、三档降级、生成 `PiPalette` | `theme/system-theme.ts` |
| `app/src/test/.../PiSystemThemeTest.kt` | 用 pi 的金样本断言 | `test/system-theme.test.ts` |
| `PiThemeFiles.kt` | `system` 分支改成调用生成器（替换"名字可达"） | 现有文件，改一处 |

约 **600 行移植 + 测试**，不碰界面，不碰协议。

## 4. 验收

- 同一组输入（pi 测试里的 ANSI 调色板）生成的颜色与 pi 的输出在 ±1/255 内一致；
- 三档降级都覆盖；
- 深/浅两套都过对比度断言（pi 的测试里已有）。

## 5. 顺带的收益

`system` 有了真实推导后，自定义主题也能复用同一套对比度曲线做"这套主题的浅色变体"，而不必手写两份 JSON。
