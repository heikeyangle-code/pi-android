package app.pi.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unified-diff parser exists so the UI never sniffs strings: it decides the
 * `+`/`-` symbol column, the line numbers and the file header purely from line
 * kinds (docs/pi-android-ui-spec.md §7.4 `tool-diff`).
 */
class DiffParserTest {

    private val gitDiff = listOf(
        "diff --git a/src/auth.ts b/src/auth.ts",
        "index 3f1a2b4..9c8d7e6 100644",
        "--- a/src/auth.ts",
        "+++ b/src/auth.ts",
        "@@ -12,7 +12,8 @@ export function auth(req) {",
        "   const token = req.headers.authorization",
        "-  const secret = \"hardcoded\"",
        "+  const secret = env.AUTH_SECRET",
        "+  if (!secret) throw new Error(\"missing\")",
        "   return verify(token, secret)",
        " }",
        "@@ -30,3 +31,3 @@ function verify(token, secret) {",
        "   return jwt.verify(token, secret)",
        " }",
    ).joinToString("\n")

    @Test
    fun `parses a git diff into a header hunk plus one hunk per section`() {
        val hunks = parseUnifiedDiff(gitDiff)
        assertEquals(3, hunks.size)
        assertTrue(hunks[0].isFileHeader)
        assertEquals(4, hunks[0].lines.size)
        assertTrue(hunks[0].lines.all { it.kind == DiffLineKind.Header })
        assertEquals("@@ -12,7 +12,8 @@ export function auth(req) {", hunks[1].header)
        assertEquals(12, hunks[1].oldStart)
        assertEquals(7, hunks[1].oldCount)
        assertEquals(12, hunks[1].newStart)
        assertEquals(8, hunks[1].newCount)
        assertEquals("@@ -30,3 +31,3 @@ function verify(token, secret) {", hunks[2].header)
    }

    @Test
    fun `classifies added, removed and context lines`() {
        val hunk = parseUnifiedDiff(gitDiff)[1]
        assertEquals(2, hunk.added)
        assertEquals(1, hunk.removed)
        assertEquals(DiffLineKind.Context, hunk.lines[0].kind)
        assertEquals(DiffLineKind.Removed, hunk.lines[1].kind)
        assertEquals(DiffLineKind.Added, hunk.lines[2].kind)
        assertEquals(DiffLineKind.Added, hunk.lines[3].kind)
        assertEquals(DiffLineKind.Context, hunk.lines[4].kind)
    }

    @Test
    fun `strips the marker from the line text`() {
        val hunk = parseUnifiedDiff(gitDiff)[1]
        assertEquals("  const secret = \"hardcoded\"", hunk.lines[1].text)
        assertEquals("  const secret = env.AUTH_SECRET", hunk.lines[2].text)
        assertEquals("  const token = req.headers.authorization", hunk.lines[0].text)
    }

    @Test
    fun `numbers lines per side, leaving the absent side null`() {
        val hunk = parseUnifiedDiff(gitDiff)[1]
        assertEquals(12, hunk.lines[0].oldLine)
        assertEquals(12, hunk.lines[0].newLine)
        assertEquals(13, hunk.lines[1].oldLine)
        assertNull(hunk.lines[1].newLine)
        assertNull(hunk.lines[2].oldLine)
        assertEquals(13, hunk.lines[2].newLine)
        assertEquals(14, hunk.lines[3].newLine)
        assertEquals(14, hunk.lines[4].oldLine)
        assertEquals(15, hunk.lines[4].newLine)
    }

    @Test
    fun `extracts the path and strips the git prefix`() {
        assertEquals("src/auth.ts", diffPath(gitDiff))
    }

    @Test
    fun `reports no path when the diff has none`() {
        assertNull(diffPath("@@ -1 +1 @@\n-a\n+b"))
    }

    @Test
    fun `treats a new file as a real path, not dev null`() {
        val diff = listOf(
            "diff --git a/new.txt b/new.txt",
            "new file mode 100644",
            "--- /dev/null",
            "+++ b/new.txt",
            "@@ -0,0 +1,2 @@",
            "+one",
            "+two",
        ).joinToString("\n")
        assertEquals("new.txt", diffPath(diff))
        val hunk = parseUnifiedDiff(diff).last()
        assertEquals(2, hunk.added)
        assertEquals(0, hunk.removed)
    }

    @Test
    fun `counts stats across every hunk`() {
        val stats = diffStats(parseUnifiedDiff(gitDiff))
        assertEquals(2, stats.added)
        assertEquals(1, stats.removed)
    }

    @Test
    fun `handles hunk headers without explicit counts`() {
        val hunk = parseUnifiedDiff("@@ -1 +1 @@\n-old\n+new").single()
        assertEquals(1, hunk.oldStart)
        assertEquals(1, hunk.oldCount)
        assertEquals(1, hunk.newStart)
        assertEquals(1, hunk.newCount)
        assertEquals(1, hunk.added)
        assertEquals(1, hunk.removed)
    }

    @Test
    fun `handles several files in one diff`() {
        val diff = listOf(
            "diff --git a/a.txt b/a.txt",
            "--- a/a.txt",
            "+++ b/a.txt",
            "@@ -1 +1 @@",
            "-old",
            "+new",
            "diff --git a/b.txt b/b.txt",
            "--- a/b.txt",
            "+++ b/b.txt",
            "@@ -1 +1 @@",
            "-x",
            "+y",
        ).joinToString("\n")
        val hunks = parseUnifiedDiff(diff)
        assertEquals(4, hunks.size)
        assertTrue(hunks[0].isFileHeader)
        assertTrue(hunks[1].lines.any { it.kind == DiffLineKind.Removed })
        assertTrue(hunks[2].isFileHeader)
        assertTrue(hunks[2].lines.any { it.text.contains("b/b.txt") })
        // The first file's path wins: the UI shows one block per diff.
        assertEquals("a.txt", diffPath(diff))
    }

    @Test
    fun `handles CRLF endings`() {
        val hunks = parseUnifiedDiff("@@ -1 +1 @@\r\n-a\r\n+b\r\n")
        val hunk = hunks.single()
        assertEquals("a", hunk.lines[0].text)
        assertEquals("b", hunk.lines[1].text)
    }

    @Test
    fun `handles the no-newline marker without breaking the counters`() {
        val hunk = parseUnifiedDiff(
            "@@ -1,2 +1,2 @@\n a\n-b\n\\ No newline at end of file\n+c\n",
        ).single()
        assertEquals(4, hunk.lines.size)
        assertEquals(DiffLineKind.Context, hunk.lines[2].kind)
        assertEquals("\\ No newline at end of file", hunk.lines[2].text)
        assertNull(hunk.lines[2].oldLine)
        // The marker advances neither side, so the following line keeps its slot.
        assertEquals(1, hunk.added)
        assertEquals(1, hunk.removed)
    }

    @Test
    fun `a trailing newline does not add a phantom blank line`() {
        val withNewline = parseUnifiedDiff("@@ -1 +1 @@\n-a\n+b\n").single()
        val without = parseUnifiedDiff("@@ -1 +1 @@\n-a\n+b").single()
        assertEquals(without.lines.size, withNewline.lines.size)
        assertEquals(2, withNewline.lines.size)
        assertEquals(1, withNewline.added)
        assertEquals(1, withNewline.removed)
    }

    @Test
    fun `returns nothing for empty input`() {
        assertTrue(parseUnifiedDiff("").isEmpty())
    }

    @Test
    fun `keeps non-diff text as a header hunk instead of dropping it`() {
        val hunks = parseUnifiedDiff("this is not a diff\njust prose")
        val hunk = hunks.single()
        assertTrue(hunk.isFileHeader)
        assertEquals(DiffLineKind.Header, hunk.lines[0].kind)
        assertEquals("just prose", hunk.lines[1].text)
    }

    @Test
    fun `builds a render-ready ToolDiff with parsed stats`() {
        val item = buildToolDiff(
            key = "t1-diff",
            ts = 42L,
            text = gitDiff,
            toolCallId = "t1",
            toolName = "edit",
        )
        assertEquals("src/auth.ts", item.path)
        assertEquals(2, item.added)
        assertEquals(1, item.removed)
        assertEquals("t1-diff", item.key)
        assertEquals(3, item.hunks.size)
        assertEquals(12, item.lineCount)
        assertEquals(false, item.truncated)
    }
}
