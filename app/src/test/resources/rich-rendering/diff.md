<!-- @case df-unified | now=source | expect=rich | 期望：复用现有 `DiffBlock` 的行渲染/着色（行号、增删着色、上下文折叠），**不复制** `DiffBlock.kt` 的逻辑。今天它是普通围栏，走 highlight.js 的 `diff` 语法着色（`diff` 是真实语言，见 pi-extensions/pi-highlight/aliases.ts）。 -->
```diff
--- a/src/example/Plan.kt
+++ b/src/example/Plan.kt
@@ -12,6 +12,7 @@ internal class Plan {
     val title: String,
     val phases: List<Phase>,
+    val baselineCommit: String,
 ) {
```

<!-- @case df-git-header | now=source | expect=rich | `diff --git` 头、rename、多个 hunk：今天仍是 highlight.js 的普通着色，没有折叠/行号。升级后必须能识别 git 头并复用 Diff 渲染。 -->
```diff
diff --git a/src/old/name.kt b/src/new/name.kt
similarity index 62%
rename from src/old/name.kt
rename to src/new/name.kt
index 4f2a1c9..b7d0e34 100644
--- a/src/old/name.kt
+++ b/src/new/name.kt
@@ -1,3 +1,4 @@
-// 旧文件
+// 新文件
@@ -40,7 +41,7 @@
-状态：done
+状态：approx
```

<!-- @case df-not-diff | now=source | expect=source | 标签写 `diff`、内容是散文和普通列表项：**解析失败必须回退源码**，不得渲染成一张假 diff。今天它只是被 diff 语法高亮的代码块（不解析语义），所以不会出错；升级后加入真正的 diff 解析，这一条就是防止误判的护栏。 -->
```diff
这不是一个 diff。
- 这一行以减号开头，但它不是删除行。
+ 这一行以加号开头，但它不是新增行。
结论：解析失败时必须原样显示。
```
