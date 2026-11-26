package app.pi.ui.screens

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFilesOperationsTest {

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("pi-ws-ops-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `copyTo copies files and folders cleanly and blocks ancestor cycles`() = withTempDir { root ->
        val srcDir = File(root, "alpha").apply { mkdirs() }
        val nested = File(srcDir, "sub").apply { mkdirs() }
        File(nested, "hello.txt").writeText("hello pi\nline 2\n")

        val destDir = File(root, "beta").apply { mkdirs() }
        val copied = WorkspaceFiles.copyTo(srcDir, destDir).getOrThrow()
        assertEquals(File(destDir, "alpha").canonicalPath, copied.canonicalPath)
        assertEquals("hello pi\nline 2\n", File(copied, "sub/hello.txt").readText())
        assertTrue(srcDir.exists())

        // Second copy to same dest auto-renames instead of clobbering
        val copied2 = WorkspaceFiles.copyTo(srcDir, destDir).getOrThrow()
        assertEquals("alpha copy", copied2.name)
        assertTrue(File(copied2, "sub/hello.txt").isFile)

        // Copying directory into itself or subfolder must fail
        assertTrue(WorkspaceFiles.copyTo(srcDir, srcDir).isFailure)
        assertTrue(WorkspaceFiles.copyTo(srcDir, nested).isFailure)
    }

    @Test
    fun `moveTo moves files and folders and rejects same-dir or child-dir moves`() = withTempDir { root ->
        val dirA = File(root, "a").apply { mkdirs() }
        val dirB = File(root, "b").apply { mkdirs() }
        val note = File(dirA, "note.md").apply { writeText("# Note") }

        assertTrue(WorkspaceFiles.moveTo(note, dirA).isFailure)

        val moved = WorkspaceFiles.moveTo(note, dirB).getOrThrow()
        assertEquals(File(dirB, "note.md").canonicalPath, moved.canonicalPath)
        assertFalse(note.exists())
        assertEquals("# Note", moved.readText())

        // Folder move into its own child must fail
        val child = File(dirB, "deep").apply { mkdirs() }
        assertTrue(WorkspaceFiles.moveTo(dirB, child).isFailure)
    }

    @Test
    fun `zipEntries and unzipTo preserve nested files and empty dirs`() = withTempDir { root ->
        val src = File(root, "project").apply { mkdirs() }
        File(src, "README.md").writeText("Pi Mobile Agent")
        val emptyDir = File(src, "empty").apply { mkdirs() }
        assertTrue(emptyDir.isDirectory)
        val extra = File(root, "notes.txt").apply { writeText("extra note") }

        val zipFile = WorkspaceFiles.zipEntries(listOf(src, extra), root, "bundle").getOrThrow()
        assertTrue(zipFile.isFile)
        assertTrue(zipFile.name.endsWith(".zip"))
        assertTrue(WorkspaceFiles.isZipFile(zipFile))

        val outDir = File(root, "unpacked").apply { mkdirs() }
        val count = WorkspaceFiles.unzipTo(zipFile, outDir).getOrThrow()
        assertTrue(count >= 3)
        assertEquals("Pi Mobile Agent", File(outDir, "project/README.md").readText())
        assertTrue(File(outDir, "project/empty").isDirectory)
        assertEquals("extra note", File(outDir, "notes.txt").readText())
    }

    @Test
    fun `unzipTo blocks Zip-Slip path traversal entries`() = withTempDir { root ->
        val evilZip = File(root, "evil.zip")
        ZipOutputStream(FileOutputStream(evilZip)).use { zos ->
            zos.putNextEntry(ZipEntry("../../escaped.txt"))
            zos.write("pwned".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val outDir = File(root, "safe").apply { mkdirs() }
        val result = WorkspaceFiles.unzipTo(evilZip, outDir)
        assertTrue("Zip-Slip must be rejected", result.isFailure)
        assertFalse(File(root, "escaped.txt").exists())
    }

    @Test
    fun `searchContent finds case-insensitive text hits and skips binaries and ignored dirs`() = withTempDir { root ->
        val src = File(root, "src").apply { mkdirs() }
        File(src, "Main.kt").writeText("fun main() {\n    println(\"Hello PiAgent\")\n}\n")
        val ignored = File(root, ".git").apply { mkdirs() }
        File(ignored, "config").writeText("PiAgent secret")
        File(root, "blob.bin").writeBytes(byteArrayOf(0, 1, 2, 3, 'P'.code.toByte(), 'i'.code.toByte()))

        val hits = WorkspaceFiles.searchContent(root, "piagent", includeIgnored = false)
        assertEquals(1, hits.size)
        assertEquals("src/Main.kt", hits.first().path)
        assertEquals(2, hits.first().lineNumber)
        assertTrue(hits.first().preview.contains("PiAgent"))

        val withIgnored = WorkspaceFiles.searchContent(root, "piagent", includeIgnored = true)
        assertEquals(2, withIgnored.size)
    }

    @Test
    fun `inspectProperties reports file line count and recursive folder counts`() = withTempDir { root ->
        val folder = File(root, "docs").apply { mkdirs() }
        val sub = File(folder, "api").apply { mkdirs() }
        val f1 = File(folder, "a.md").apply { writeText("one\ntwo\nthree") }
        File(sub, "b.md").writeText("four\n")

        val fileProps = WorkspaceFiles.inspectProperties(f1)
        assertFalse(fileProps.isDirectory)
        assertEquals(3, fileProps.lineCount)
        assertEquals(f1.length(), fileProps.sizeBytes)
        assertEquals(3, fileProps.permissions.length)
        assertNotNull(fileProps.hostPath)

        val dirProps = WorkspaceFiles.inspectProperties(folder)
        assertTrue(dirProps.isDirectory)
        assertEquals(2, dirProps.fileCount)
        assertEquals(1, dirProps.dirCount)
        assertEquals(f1.length() + File(sub, "b.md").length(), dirProps.sizeBytes)
        assertFalse(dirProps.statsTruncated)
    }
}
