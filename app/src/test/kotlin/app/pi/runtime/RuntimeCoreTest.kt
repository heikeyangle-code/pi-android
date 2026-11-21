package app.pi.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class RuntimeCoreTest {

    @Test
    fun `TarExtractor handles PAX UTF-8 multibyte paths, exec bits, and rejects traversal`() {
        val dir = Files.createTempDirectory("pi-tar-test").toFile()
        try {
            val tarGz = buildTarGz {
                addPaxEntry("目录/你好.txt", "hello utf8\n".toByteArray(), mode = 0b110_100_100)
                addRegularEntry("bin/tool.sh", "#!/bin/sh\necho ok\n".toByteArray(), mode = 0b111_101_101)
                addRegularEntry("../escape.txt", "bad".toByteArray(), mode = 0b110_100_100)
            }
            val result = TarExtractor(dir).extractGzip(ByteArrayInputStream(tarGz))
            assertEquals(2L, result.files)
            val utf8File = File(dir, "目录/你好.txt")
            assertTrue(utf8File.isFile)
            assertEquals("hello utf8\n", utf8File.readText())
            val tool = File(dir, "bin/tool.sh")
            assertTrue(tool.isFile)
            assertTrue(tool.canExecute())
            assertFalse(File(dir.parentFile, "escape.txt").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(expected = IOException::class)
    fun `TarExtractor throws IOException on truncated archive`() {
        val dir = Files.createTempDirectory("pi-tar-trunc").toFile()
        try {
            val tarGz = buildTarGz {
                addRegularEntry("a.txt", "0123456789".toByteArray(), mode = 0b110_100_100)
            }
            // Strip gzip trailer & end-of-archive blocks via raw truncated tar inside gzip.
            val rawTar = ByteArray(512 + 5) // 1 header + partial data
            System.arraycopy("a.txt".toByteArray(), 0, rawTar, 0, 5)
            System.arraycopy("0000644\u0000".toByteArray(), 0, rawTar, 100, 8)
            System.arraycopy("00000000100\u0000".toByteArray(), 0, rawTar, 124, 12)
            rawTar[156] = '0'.code.toByte()
            val gz = ByteArrayOutputStream().also { out ->
                GZIPOutputStream(out).use { it.write(rawTar) }
            }.toByteArray()
            TarExtractor(dir).extractGzip(ByteArrayInputStream(gz))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `DurablePreserve sorts recovery targets numerically and preserves stranded data`() {
        val root = Files.createTempDirectory("pi-preserve-test").toFile()
        try {
            val preserve = File(root, ".preserve").apply { mkdirs() }
            val original = File(root, "agent")
            File(preserve, "agent-99").mkdirs()
            File(preserve, "agent-1000").mkdirs()
            val targets = DurablePreserve.recoveryTargets(preserve, listOf(original))
            assertEquals(listOf("agent-99", "agent-1000"), targets.map { it.first.name })

            // Non-empty original must remain untouched and stash marked stranded.
            original.mkdirs()
            File(original, "keep.txt").writeText("important")
            val recovery = DurablePreserve.recover(listOf(original), preserve)
            assertTrue(recovery.restored.isEmpty())
            assertEquals(2, recovery.stranded.size)
            assertEquals("important", File(original, "keep.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `RuntimeSpaceBudget uses smaller floor for incremental updates`() {
        val smallPayload = 2L * 1024 * 1024 // 2 MiB
        assertEquals(RuntimeSpaceBudget.MIN_REQUIRED_BYTES, RuntimeSpaceBudget.requiredBytes(smallPayload, fullUnpack = true))
        assertEquals(RuntimeSpaceBudget.INCREMENTAL_MIN_REQUIRED_BYTES, RuntimeSpaceBudget.requiredBytes(smallPayload, fullUnpack = false))
        assertNull(RuntimeSpaceBudget.shortfall(100L * 1024 * 1024, smallPayload, fullUnpack = false))
    }

    @Test
    fun `GuestProcessTree parses stat with spaces and parens in comm`() {
        val line = "42 (my (weird) proc) S 10 42 42 0 -1 4194304 1 0 0 0 0 0 0 0 20 0 1 0 987654 0 0"
        assertEquals(10, GuestProcessTree.parseStatPpid(line))
        assertEquals(987654L, GuestProcessTree.parseStatStartTime(line))
        val plan = GuestProcessTree.plan(1, mapOf(2 to 1, 3 to 2, 4 to 1))
        assertEquals(3, plan.first())
        assertEquals(1, plan.last())
    }

    @Test
    fun `PiCrashStore appendBounded retains newest tail within cap`() {
        val dir = Files.createTempDirectory("pi-crash-test").toFile()
        try {
            val log = File(dir, "android-crashes.log")
            PiCrashStore.appendBounded(log, "OLD_ENTRY_1111111111\n", maxBytes = 32)
            PiCrashStore.appendBounded(log, "NEW_ENTRY_2222222222\n", maxBytes = 32)
            val content = log.readText()
            assertTrue(content.toByteArray().size <= 32)
            assertTrue(content.endsWith("NEW_ENTRY_2222222222\n"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `PersistentProcessSpawner executes process cleanly`() {
        val process = PersistentProcessSpawner.start(ProcessBuilder("/bin/sh", "-c", "echo pi-spawner-ok"))
        val out = process.inputStream.bufferedReader().use { it.readText().trim() }
        assertEquals(0, process.waitFor())
        assertEquals("pi-spawner-ok", out)
    }

    // Minimal tar builder for unit tests
    private class TarBuilder {
        private val out = ByteArrayOutputStream()

        fun addPaxEntry(path: String, data: ByteArray, mode: Int) {
            val paxBytes = encodePax("path", path) + encodePax("comment", "trailing-check")
            writeHeader("PaxHeader", paxBytes.size.toLong(), 0b110_100_100, 'x')
            writeData(paxBytes)
            addRegularEntry("placeholder", data, mode)
        }

        fun addRegularEntry(name: String, data: ByteArray, mode: Int) {
            writeHeader(name, data.size.toLong(), mode, '0')
            writeData(data)
        }

        private fun encodePax(key: String, value: String): ByteArray {
            val payload = " $key=$value\n".toByteArray(Charsets.UTF_8)
            var len = payload.size + 1
            while (true) {
                val candidate = "$len".toByteArray(Charsets.US_ASCII).size + payload.size
                if (candidate == len) break
                len = candidate
            }
            return "$len".toByteArray(Charsets.US_ASCII) + payload
        }

        private fun writeHeader(name: String, size: Long, mode: Int, type: Char) {
            val header = ByteArray(512)
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))
            val modeStr = "%07o\u0000".format(mode).toByteArray(Charsets.US_ASCII)
            System.arraycopy(modeStr, 0, header, 100, 8)
            val sizeStr = "%011o\u0000".format(size).toByteArray(Charsets.US_ASCII)
            System.arraycopy(sizeStr, 0, header, 124, 12)
            header[156] = type.code.toByte()
            out.write(header)
        }

        private fun writeData(data: ByteArray) {
            out.write(data)
            val pad = (512 - (data.size % 512)) % 512
            if (pad > 0) out.write(ByteArray(pad))
        }

        fun finish(): ByteArray {
            out.write(ByteArray(1024)) // end-of-archive blocks
            return out.toByteArray()
        }
    }

    private fun buildTarGz(block: TarBuilder.() -> Unit): ByteArray {
        val tar = TarBuilder().apply(block).finish()
        return ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(tar) }
        }.toByteArray()
    }
}
