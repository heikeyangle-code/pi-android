package app.pi.ui.blocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * This transcript's table of expand/collapse overrides ([RowExpansionStore]).
 *
 * `null` outside the transcript — the workspace's diff viewer draws a `DiffBlock` of its own,
 * and a preview has no transcript at all: [rememberRowExpanded] then keeps every block's old
 * behaviour (`remember(defaultExpanded)`), so a block cannot depend on being in the list.
 *
 * `compositionLocalOf` and not `staticCompositionLocalOf`: the value is a new table whenever
 * the session changes (`ChatScreen`'s `remember(sessionKey)`), and the rows are the only
 * readers, so only they need to be invalidated then.
 */
internal val LocalRowExpansion = compositionLocalOf<RowExpansionStore?> { null }

/**
 * The **stable identity** of the row being composed — `TranscriptItem.key`, the same key the
 * `LazyColumn` is built with and `RowHeightCache` records heights under. It is what
 * [rememberRowExpanded] files the reader's disclosure state under.
 *
 * `staticCompositionLocalOf`: the value is provided once per row and is a constant for as long
 * as that row's composition lives, so there is nothing for read-tracking to save. A *recycled*
 * item slot does change it — the composition then holds another key — and a static local's
 * unconditional invalidation is exactly right there: the whole row is about to be drawn from
 * another block's state, and [rememberRowExpanded] has to re-read the table for the new key.
 */
internal val LocalTranscriptRowKey = staticCompositionLocalOf<String?> { null }

/**
 * The disclosure state of one transcript row: [defaultExpanded]'s semantics, with the value
 * held in the transcript's table instead of in this composition.
 *
 * The `defaultExpanded` key is unchanged — the state is re-read whenever the caller's default
 * moves, which is how the top bar's expand/collapse-all switch still reaches every row, and how
 * the 思考块 preference still resets every thinking row. What changes is where the value lives: a
 * table entry keyed by the row's own key outlives the row leaving the `LazyColumn`'s window, so
 * a card the reader had opened comes back open (see [RowExpansionStore] for the defect, the
 * default-rebase rule and the bound).
 *
 * Without a table and a key — the workspace's diff viewer, a preview, a test — this is the old
 * `remember(defaultExpanded) { mutableStateOf(defaultExpanded) }` exactly.
 */
@Composable
internal fun rememberRowExpanded(defaultExpanded: Boolean): MutableState<Boolean> {
    val store = LocalRowExpansion.current
    val key = LocalTranscriptRowKey.current
    if (store == null || key == null) {
        return remember(defaultExpanded) { mutableStateOf(defaultExpanded) }
    }
    return remember(store, key, defaultExpanded) {
        StoredRowExpanded(store, key, defaultExpanded)
    }
}

/**
 * One row's disclosure, reading the table once (when the row's composition starts, or when its
 * default moves) and writing through to it on every flip.
 *
 * It is a hand-written [MutableState] because the flip itself is the thing that has to reach the
 * table, and the `by`-delegated `var expanded` at all twelve call sites goes through `value`.
 * The composition still observes an ordinary `mutableStateOf` underneath, so a toggle recomposes
 * that row and only that row.
 */
private class StoredRowExpanded(
    private val store: RowExpansionStore,
    private val key: String,
    private val defaultExpanded: Boolean,
) : MutableState<Boolean> {

    private val local = mutableStateOf(store.remembered(key, defaultExpanded) ?: defaultExpanded)

    override var value: Boolean
        get() = local.value
        set(newValue) {
            local.value = newValue
            store.record(key, defaultExpanded, newValue)
        }

    // `MutableState`'s destructuring operators (`var (expanded, setExpanded) = …`). The call
    // sites use `by`, which is `getValue`/`setValue` and rides on [value], but the interface
    // still requires these two.
    override fun component1(): Boolean = value

    override fun component2(): (Boolean) -> Unit = { value = it }
}
