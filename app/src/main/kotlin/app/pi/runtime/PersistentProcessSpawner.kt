package app.pi.runtime

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Spawns guest container processes on a dedicated, non-expiring single thread.
 *
 * ## Why this is required for `tawcroot` (fixes Exit Code 137 / SIGKILL)
 *
 * Upstream `wmww/tawc` (`tawcroot/src/main.c`, `bind_top_level_to_parent`) calls
 * `prctl(PR_SET_PDEATHSIG, SIGKILL)` assuming the launcher parent is a stable,
 * long-lived broker thread/process. On Linux (`man 2 prctl`), `PR_SET_PDEATHSIG`
 * fires when the **parent OS thread (`task_struct`) that called `fork()`** exits —
 * not only when the parent process exits.
 *
 * Calling `ProcessBuilder.start()` directly on `Dispatchers.IO` binds `tawcroot`
 * to a dynamic coroutine worker thread that expires after ~60 s of idleness,
 * causing the kernel to deliver `SIGKILL` (exit code `128 + 9 = 137`) to a
 * healthy running Pi engine or terminal.
 *
 * `Executors.newSingleThreadExecutor` keeps its single core thread alive for the
 * entire lifetime of the app process (matching `dsh-go`'s persistent `dsh-runtime`
 * worker), so `PR_SET_PDEATHSIG` never misfires during normal operation while
 * still reaping `tawcroot` cleanly if the Android app process itself dies.
 *
 * Only `ProcessBuilder.start()` (milliseconds) runs on this thread; callers
 * never block it with `waitFor()` or stream reads.
 */
object PersistentProcessSpawner {

    private val spawner = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pi-process-spawner").apply { isDaemon = true }
    }

    fun start(builder: ProcessBuilder): Process {
        val future = spawner.submit<Process> { builder.start() }
        return try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}
