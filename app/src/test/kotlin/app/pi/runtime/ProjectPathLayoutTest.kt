package app.pi.runtime

import app.pi.bridge.GuestPathMapping
import app.pi.bridge.GuestPathRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProjectPathLayoutTest {

    @Test
    fun `DurableLayout places projectsRoot at root_projects inside rootfs with zero violations`() {
        val base = Files.createTempDirectory("pi-layout-test").toFile()
        try {
            val nativeLib = File(base, "lib").apply { mkdirs() }
            val paths = PiPaths(filesDir = base, nativeLibDir = nativeLib)
            assertTrue(DurableLayout.violations(paths.home, paths.runtime).isEmpty())
            assertEquals(File(paths.rootfs, "root/projects"), paths.projectsRoot)
            assertEquals(File(paths.rootfs, "root/.pi/agent"), paths.agentDir)
            val durable = DurableLayout.durableDirs(paths.home, paths.runtime)
            assertEquals(listOf("projects", "agent", "persist"), durable.map { it.name })
            assertTrue(paths.guestConfigTargets().any { it.relative == "root/projects" && it.directory })
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `GuestWorkspacePath hostToGuest maps rootfs, primary storage, and removable volumes cleanly`() {
        val rootfs = "/data/user/0/app.pi/files/pi/runtime/rootfs"
        assertEquals(
            "/root/projects/default",
            GuestWorkspacePath.hostToGuest(rootfs, "$rootfs/root/projects/default"),
        )
        assertEquals(
            "/root/scratch",
            GuestWorkspacePath.hostToGuest(rootfs, "$rootfs/root/scratch"),
        )
        assertEquals(
            "/",
            GuestWorkspacePath.hostToGuest(rootfs, rootfs),
        )
        assertEquals(
            "/storage/emulated/0/Projects/demo",
            GuestWorkspacePath.hostToGuest(rootfs, "/storage/emulated/0/Projects/demo"),
        )
        assertEquals(
            "/sdcard/demo",
            GuestWorkspacePath.hostToGuest(rootfs, "/sdcard/demo"),
        )
        assertEquals(
            "/mnt/external/storage/1234-5678/repo",
            GuestWorkspacePath.hostToGuest(rootfs, "/storage/1234-5678/repo"),
        )
    }

    @Test
    fun `GuestWorkspacePath shortenForDisplay matches Pi SDK shortenHomePath`() {
        assertEquals("~/projects/default", GuestWorkspacePath.shortenForDisplay("/root/projects/default"))
        assertEquals("~", GuestWorkspacePath.shortenForDisplay("/root"))
        assertEquals("/tmp/build", GuestWorkspacePath.shortenForDisplay("/tmp/build"))
        assertEquals("/storage/emulated/0/Code", GuestWorkspacePath.shortenForDisplay("/storage/emulated/0/Code"))
        assertEquals("/", GuestWorkspacePath.shortenForDisplay("/"))
    }

    @Test
    fun `GuestWorkspacePath relativeToWorkspace strips exact cwd prefix without legacy workspace stripping`() {
        assertEquals(
            "src/Main.kt",
            GuestWorkspacePath.relativeToWorkspace("/root/projects/default/src/Main.kt", "/root/projects/default"),
        )
        assertEquals(
            "",
            GuestWorkspacePath.relativeToWorkspace("/root/projects/default", "/root/projects/default"),
        )
        assertEquals(
            "workspace/notes.md",
            GuestWorkspacePath.relativeToWorkspace("/workspace/notes.md", "/root/projects/default"),
        )
    }

    @Test
    fun `WorkspaceChoice recognizes clean root_projects cwds and rejects legacy numbering`() {
        assertEquals("default", WorkspaceChoice.nameForGuestCwd("/root/projects/default"))
        assertEquals("my-app", WorkspaceChoice.nameForGuestCwd("/root/projects/my-app"))
        assertNull(WorkspaceChoice.nameForGuestCwd("/root/projects/my-app/sub"))
        assertNull(WorkspaceChoice.nameForGuestCwd("/workspace/pi/workspaces/workspace-1"))
        assertEquals("/storage/emulated/0/Code", WorkspaceChoice.nameForGuestCwd("/storage/emulated/0/Code"))
        assertEquals("/storage/ABCD-1234/repo", WorkspaceChoice.nameForGuestCwd("/mnt/external/storage/ABCD-1234/repo"))

        assertTrue(WorkspaceChoice.deleteRemovesFiles("my-app"))
        assertFalse(WorkspaceChoice.deleteRemovesFiles("/storage/emulated/0/Code"))
        assertFalse(WorkspaceChoice.deleteRemovesFiles("/root/scratch"))
        assertEquals("project", WorkspaceChoice.nextDefaultProjectName(listOf("default")))
        assertEquals("project-2", WorkspaceChoice.nextDefaultProjectName(listOf("default", "project")))
    }

    @Test
    fun `GuestPathMapping resolves rootfs projects, agent dir, shared storage, removable volumes, and relative paths`() {
        val roots = GuestPathRoots(
            rootfs = "/data/rootfs",
            agentDir = "/data/rootfs/root/.pi/agent",
            workspaceHost = "/data/rootfs/root/projects/default",
            storage = "/storage/emulated/0",
        )
        assertEquals(
            listOf("/data/rootfs/root/projects/default/chart.png", "/root/projects/default/chart.png"),
            GuestPathMapping.candidates(listOf("/root/projects/default/chart.png"), roots),
        )
        assertEquals(
            listOf("/data/rootfs/root/projects/default/plot.png", "plot.png"),
            GuestPathMapping.candidates(listOf("plot.png"), roots),
        )
        assertEquals(
            listOf("/storage/1234-5678/img.png", "/data/rootfs/mnt/external/storage/1234-5678/img.png"),
            GuestPathMapping.candidates(listOf("/mnt/external/storage/1234-5678/img.png"), roots),
        )
        assertEquals(
            listOf("/storage/emulated/0/DCIM/a.jpg", "/sdcard/DCIM/a.jpg"),
            GuestPathMapping.candidates(listOf("/sdcard/DCIM/a.jpg"), roots),
        )
    }
}
