package app.pi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Whether the screen this composable is in is **visible right now**
 * (`STARTED`..`STOPPED`), as a piece of state a `LaunchedEffect` can key on.
 *
 * ## Why it exists
 *
 * A `LaunchedEffect` is cancelled when its composable leaves the *composition*, not
 * when the Activity stops: an app in the background, or a phone with the screen off,
 * keeps every composed effect running. For one-shot work that is harmless, and for a
 * `FileObserver` it is deliberate. For a **polling loop** it is not: the first user
 * of this helper (`DeviceCapabilityScreen`) reads Shizuku through a binder call, the
 * accessibility service, the device bridge's status and an audit file every 1.5 s,
 * and every one of those kept happening while nobody could see the result.
 *
 * ## Where the idiom comes from
 *
 * `ui/settings/PiFileWatch.kt:73-88` already does exactly this — `LocalLifecycleOwner`
 * + a `LifecycleEventObserver` removed in `onDispose` — for its `ON_RESUME`
 * file-stamp comparison. Deliberately the same three APIs, because Compose cannot be
 * compiled on every machine this repository is edited on: the CI build is what
 * verifies this file, so it stays inside an idiom the repository already compiles.
 *
 * ## What it is not
 *
 * Not a gate on *work that must finish* — a write, an install, a turn. Those belong
 * to a scope that outlives the screen (`PiSessionViewModel.teardownScope`), not to
 * visibility. This is only for "refresh what is on screen", which by definition has
 * nobody to refresh for when the screen is gone.
 */
@Composable
fun rememberPiScreenVisible(): Boolean {
    val owner = LocalLifecycleOwner.current
    // Seeded from the current state, not `false`: the first composition of a visible
    // screen must be able to start its loop without waiting for an event.
    var visible by remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> visible = true
                Lifecycle.Event.ON_STOP -> visible = false
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return visible
}
