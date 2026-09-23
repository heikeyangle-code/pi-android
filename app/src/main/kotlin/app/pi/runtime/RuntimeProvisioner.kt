package app.pi.runtime

import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.roundToInt

/**
 * Unpacks the bundled runtime on first launch, and keeps it in step with the
 * packaged payloads afterwards **without deleting anything the user owns**.
 *
 * Two trees, deliberately separated (docs/pi-android-app-design.md §19.1):
 *  - `<files>/pi` holds user data — sessions, settings, extensions, credentials,
 *    workspaces — and must survive an app update untouched.
 *  - `<files>/pi/runtime` is volatile: it is what a payload change may rewrite.
 *
 * ## Why provisioning is per payload and not one global stamp
 *
 * It used to compare one SHA-256 over every payload with `<files>/pi/runtime/.stamp`
 * and, on any difference, `deleteRecursively()` the whole volatile tree and re-extract
 * all six archives. So *any* single payload change — a pi version bump, a Node bump, a
 * new library in the git closure, a proroot `.so` — destroyed the guest environment
 * the user had built inside that tree: apt/pip packages, `npm -g`, `/usr/local/bin`,
 * edits to `/etc`, and all of `/root` except the bind-mounted `.pi/agent`. The user's
 * words are 「我为什么每次升级完软件？很多东西，很多文件都会没有。哪他妈有这样的软件」.
 *
 * So each payload now carries its own state under the volatile tree,
 * `<files>/pi/runtime/.payloads/<name>.digest` and `<name>.list` ([PiPaths.payloadStateDir]):
 *
 *  - `<name>.digest` — the digest of the bytes this payload was last extracted from;
 *  - `<name>.list` — every **non-directory** path that payload owns, relative to
 *    `paths.runtime`, sorted, one per line.
 *
 * The decision, and its cost. A matching `.stamp` reaches none of this (see the fast path
 * below); these are the cases once it does not match:
 *
 *  - **digest unchanged** — the payload is not touched at all. The whole decision is one
 *    16-byte asset read and one string comparison per payload.
 *  - **digest changed** — the payload is extracted **over** the existing tree (no
 *    `deleteRecursively` of anything the payload does not own), and then only
 *    `old.list − new.list` is deleted ([PayloadPrune.victims]), which is what keeps
 *    upstream-removed files from lingering while leaving every file the user created —
 *    those are in neither list — untouchable.
 *  - **no per-payload state at all** (every install that predates this change) — every
 *    payload is re-extracted over the tree with **no** whole-tree wipe and **no** prune:
 *    there is no old list to prune against, so this transition cannot delete anything.
 *
 * The payload lists are **21,259 paths / 2.0 MiB** of text in total, measured from the
 * pinned archives (`ubuntu-base` 2,758, `node` 4,714, `pi-engine` 13,531, `git` 252,
 * `ripgrep` 2, `fd` 2), against the 121,072,689 bytes (115.5 MiB) of payload archives
 * these binaries ship. On device each is read once per attempt (only on the slow path),
 * parsed once into a `Set`, and used for one set difference — never re-read or re-parsed
 * per payload.
 *
 * ## Why this is faster than what it replaced, not only safer
 *
 *  - a cold start that changes nothing does exactly what it always did: one 16-character
 *    asset read and one `.stamp` comparison, then the same two idempotent repairs. No
 *    digest, no list, no traversal;
 *  - a revision that moved while no *runtime* payload did (a proroot `.so` change, say)
 *    reads twelve small digest files — a few hundred bytes — and extracts nothing;
 *  - a payload that really changed is the only one extracted, over the tree: the archive
 *    is the only thing written. `pi-engine.tgz` is 22,944,170 bytes compressed (measured
 *    on this checkout), where the old path deleted the guest environment and re-wrote
 *    >438 MB of tree ([RuntimeSpaceBudget]'s measurements for the three large payloads
 *    alone).
 *
 * ## The one path that may still delete the tree
 *
 * [ensureReady]'s `rebuild` parameter is the explicit repair decision — a genuinely
 * broken or incomplete tree (a failed boot-time self-check, a missing rootfs, the user
 * asking for a repair) — and it is the **only** caller of [wipe]. It is never derived
 * from "the digest changed": a payload change is a re-extraction over the tree, never a
 * deletion of it. [deleteTreeInsideVolatile] is the structural half of that promise:
 * every recursive delete in this class is checked against [VolatileTree] first, and
 * [PiPaths] asserts at construction that the workspace root, the agent dir and
 * `persist/` are outside the volatile tree.
 *
 * The work is a sequence of named steps so the first launch can show honest
 * progress instead of a spinner: unpacking a rootfs takes tens of seconds on a
 * phone and an unexplained wait reads as a hang. The steps name **only** the work
 * this attempt actually does, and a boot that finds every payload current says so
 * instead of showing extraction steps that never ran.
 */
class RuntimeProvisioner(
    private val paths: PiPaths,
    private val assets: AssetManager,
) {

    data class Step(val label: String, val index: Int, val total: Int) {
        val fraction: Float get() = index.toFloat() / total.toFloat()
    }

    class ProvisioningException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** One payload archive the assembler is expected to place in `assets/runtime/`. */
    private data class Payload(val name: String, val required: Boolean)

    /**
     * What the pre-flight audit found for one payload.
     *
     * Internal rather than private so the diagnostic report can print the same six
     * lines the boot screen shows: the report has to answer "does this APK carry
     * every payload, and how big is each one", and re-deriving that from
     * `AssetManager` in a second place would be a second answer to the same
     * question (see [payloadInventory]).
     */
    internal data class PayloadInfo(
        val asset: String,
        val required: Boolean,
        val bytes: Long,
        val via: String?,
        val error: String?,
    ) {
        val ok: Boolean get() = error == null && bytes > 0L
    }

    /**
     * The pre-flight audit's result as rendered for the screen, one payload per
     * line. Written by [auditPayloads]; read by [payloadReportBlock] when a later
     * step fails, so a failure after the audit still carries the audit with it.
     */
    private var payloadReport: String = ""

    /**
     * What one provisioning attempt actually did.
     *
     * Returned rather than kept in a field because two callers need the same answer
     * about *this* attempt: the boot screen's last step and the boot audit
     * ([BootAudit]), which records on upgrade whether anything was re-extracted. A
     * field would be shared state between concurrent attempts; the return value cannot
     * be.
     */
    data class ProvisionOutcome(
        /** Payload archives re-extracted this attempt, in the order they were unpacked. */
        val reextracted: List<String>,
        /**
         * True when the device had no per-payload state at all, so every payload was
         * extracted over the tree. See the class KDoc: this is the transition that must
         * be impossible to lose anything in, and it prunes nothing.
         */
        val migrated: Boolean,
        /** True when the explicit repair path ran and [wipe] deleted the tree first. */
        val rebuilt: Boolean,
    )

    /**
     * Bring the packed runtime up to date.
     *
     * The decision is per payload and never deletes the tree: for each payload, compare
     * its packaged digest with the one recorded in `.payloads/<name>.digest`; extract
     * over the tree only the ones that differ; prune only the paths the old list owned
     * and the new one does not. See the class KDoc for the full account and for why a
     * payload that did not change costs one small read.
     *
     * @param revision the digest of the per-payload digests, written to the stamp file at
     *        the end. It decides nothing about extraction — that is each payload's own
     *        digest — it records which revision of the payload set this tree holds.
     * @param rebuild the **explicit repair decision**: delete the volatile tree and
     *        extract every payload again. The only caller that may pass true is a caller
     *        that has decided the tree is genuinely broken or incomplete (a failed
     *        boot-time self-check, a missing rootfs, a user asking for a repair). It is
     *        never derived from a digest comparison, and this is the only path that may
     *        delete anything the user could have touched inside the guest.
     * @param onStep progress, one call per named step. The list names only the work this
     *        attempt actually does; when every payload is current it says exactly that.
     */
    suspend fun ensureReady(
        revision: String,
        rebuild: Boolean = false,
        onStep: (Step) -> Unit = {},
    ): Result<ProvisionOutcome> = withContext(Dispatchers.IO) {
        runCatching { provision(revision, rebuild, onStep) }
    }

    private fun provision(revision: String, rebuild: Boolean, onStep: (Step) -> Unit): ProvisionOutcome {
        // ---------------------------------------------------------------- fast path
        // Byte-for-byte the boot this app has always had: read the 16-character
        // revision from the APK and compare it with `<files>/pi/runtime/.stamp`. Equal
        // means "this revision is unpacked" and the answer is the same as it was before
        // per-payload state existed — no digest read, no list read, no traversal, no
        // count, not even a `mkdirs` the old early return did not perform. A cold start
        // that changes nothing must not become more expensive because updates got safer.
        //
        // `rebuild` deliberately bypasses it: a caller that decided the tree is broken
        // must be able to rebuild a tree whose stamp happens to match.
        if (!rebuild && paths.rootfs.isDirectory && isStampCurrent(revision)) {
            return finishCurrent(revision, migration = false, onStep)
        }

        // `<files>/pi` may not exist yet on a first boot, and `usableSpace` on a path
        // that does not exist answers 0 - which [RuntimeSpaceBudget.shortfall] reads as
        // "cannot tell" and would silently skip the check exactly when a fresh install
        // on a full phone is the case worth catching. Creating it here costs nothing:
        // this path is already doing real work.
        paths.home.mkdirs()

        // ---------------------------------------------------------------- slow path
        // Reached only when the stamp differs (a new APK, a repaired stamp, a first
        // install) or when the caller asked for a rebuild. The per-payload digests and
        // lists are read **here**, once, and never on the fast path.
        //
        // Captured before anything creates it: "this device has never had per-payload
        // state" is the migration condition, and reading it after the first payload was
        // recorded would answer false. `stateDir.exists()` is not the same question as
        // "every digest is present" - a crash between two payloads leaves a directory
        // with one digest in it, and that case must re-extract only the rest.
        val migration = !paths.payloadStateDir().exists()
        // A tree without a rootfs is incomplete rather than merely out of date. Treating
        // it as "every payload changed" re-extracts over what is there (nothing is
        // deleted), which repairs it without needing the explicit rebuild.
        val rootfsMissing = !paths.rootfs.isDirectory

        // The three directories [wipe] used to be the only creator of. Creating them on
        // this path (and not on the fast one) keeps the fast path's fixed cost exactly
        // where it was.
        prepareVolatileDirs()

        // Every payload's state and packaged digest is read **once** on this path and the
        // answers are reused: a comparison for the plan, and then — for the payloads that
        // are extracted — the same digest and the packaged list for the prune and for the
        // state write. Nothing is read or parsed twice, and none of it happens at all on
        // the fast path above.
        val stateDigests = PAYLOADS.associateWith { readStateDigest(it) }
        val packagedDigests = PAYLOADS.associateWith { packagedDigest(it) }
        val plan: List<Payload> = when {
            rebuild -> PAYLOADS
            rootfsMissing -> PAYLOADS
            migration -> PAYLOADS
            else -> PAYLOADS.filter { payload ->
                // An APK that carries no engine asset (the assembler always writes one,
                // but a hand-assembled package need not) has nothing to extract for it and
                // can never record a digest, so without this it would re-run this whole
                // slow path on every boot. [extractEngine] already treats a missing engine
                // asset as provisionable. On a first install the branches above still
                // include it, which is what creates `<rootfs>/opt/pi`.
                if (payload.name == ENGINE_ARCHIVE && !assetExists("runtime/$ENGINE_ARCHIVE")) {
                    return@filter false
                }
                stateDigests[payload] != packagedDigests[payload]
            }
        }

        // A revision can move without any *runtime* payload moving: the five proroot
        // `.so` files are payloads too, and Android's native-library extractor installs
        // them outside this tree. That case must say "already current" and record the new
        // revision — not re-extract 110 MiB because a number moved.
        if (plan.isEmpty()) {
            return finishCurrent(revision, migration = false, onStep)
        }

        // The step list is the plan, **in the order the work runs**: a payload that did
        // not change contributes no step, so the segmented bar's total is the work that is
        // really about to happen.
        val steps = buildList {
            add(AUDIT_STEP)
            if (migration && !rebuild && !rootfsMissing) add(MIGRATION_STEP)
            add(PREPARE_STEP)
            if (rebuild) add(REBUILD_STEP)
            plan.forEach { add(stepLabel(it)) }
            add(CONFIGURE_STEP)
            add(FINAL_STEP)
        }
        var index = 0
        fun next(label: String = steps[index]) = onStep(Step(label, index, steps.size))

        // Pre-flight, and deliberately *before* anything destructive: the audit is the
        // step that can prove the APK's payload is unusable, and a package that cannot be
        // read must not destroy a runtime that works. `auditPayloads` throws only for the
        // payloads the unpack needs; see its KDoc for why the engine is reported but not
        // required. Its file-and-size list goes on screen in the step label so the next
        // device failure is self-explanatory.
        next()
        val payloads = auditPayloads()
        onStep(Step(auditLabel(payloads), index, steps.size))
        index++

        // The migration gets its own named step rather than a silent plan of six
        // payloads: it is the one transition whose whole point is that it deletes
        // nothing, and the user should be able to read that on the boot screen.
        if (migration && !rebuild && !rootfsMissing) {
            next(); index++
        }

        // Pre-flight too, and it has to be *here*: after the audit (so the real payload
        // sizes are known) and before the first extraction. Without it, a phone that is
        // out of space loses the runtime that worked, fails half way through the new
        // one, and - because the per-payload digests below are only written after a
        // payload is complete - repeats the whole thing on the next launch. The measured
        // requirement and the sentence the user gets are in [RuntimeSpaceBudget].
        //
        // Only the planned payloads are counted: a payload that will not be extracted
        // does not need room.
        //
        // `usableSpace` on an unreadable path answers 0, and
        // [RuntimeSpaceBudget.shortfall] treats that as "cannot tell" rather than "out
        // of space", so this can never refuse a boot because of a number it could not
        // read.
        next()
        val plannedAssets = plan.mapTo(HashSet()) { "runtime/${it.name}" }
        val payloadBytes = payloads.filter { it.ok && it.asset in plannedAssets }.sumOf { it.bytes }
        val available = paths.home.usableSpace
        RuntimeSpaceBudget.shortfall(available, payloadBytes)?.let { missing ->
            throw ProvisioningException(
                RuntimeSpaceBudget.message(available, payloadBytes) +
                    "\n（本次预检：可用 $available 字节，本次要解的载荷 $payloadBytes 字节，" +
                    "预计至少需要 ${RuntimeSpaceBudget.requiredBytes(payloadBytes)} 字节，" +
                    "还差 $missing 字节）",
            )
        }
        index++

        // The explicit repair path: the only place in this class that deletes the tree,
        // and it is reachable only when the caller passed `rebuild = true`.
        if (rebuild) {
            next(); wipe(); index++
        }

        for (payload in plan) {
            next()
            extractPayloadOver(payload)
            // Order matters: extract over first (the new bytes are in place), then delete
            // only what the old list owned and the new one does not, then record the new
            // state. Recording before pruning would lose the old list.
            //
            // The packaged list is read once here and handed to both steps; the digest was
            // already read for the plan. One asset read per payload per attempt, never two.
            val newList = packagedList(payload)
            prunePayload(payload, newList)
            recordPayloadState(payload, packagedDigests[payload], newList)
            index++
        }

        // The guest's `/etc/group` repair reports through the last step's label (like
        // the stamp below): the runtime is complete either way, and the only thing at
        // stake is whether the terminal still prints coreutils' "cannot find name for
        // group ID" lines.
        next()
        val groupWarning = configureGuest()
        index++

        paths.prepareLibraryAliases()
        // A stamp that could not be written is shown where the user is already looking
        // (the boot screen's step label) instead of failing a boot whose runtime is
        // complete. See [writeStamp].
        val stampWarning = writeStamp(revision)
        val warning = listOfNotNull(groupWarning, stampWarning).firstOrNull()
        onStep(Step(warning ?: steps[index], index, steps.size))

        return ProvisionOutcome(
            reextracted = plan.map { it.name },
            migrated = migration && !rebuild && !rootfsMissing,
            rebuilt = rebuild,
        )
    }

    /**
     * The boot where nothing needs re-extracting.
     *
     * **This is the fast path, and its fixed cost is the one this app always had**: the
     * revision comparison that got us here ([provision]'s first branch reads `.stamp`),
     * the idempotent repairs that used to ride the early return, and — only when the
     * stamp is not already current — one stamp write. It reads no payload digest, no
     * payload list, and walks no directory; the per-payload state is not touched at all
     * unless the stamp differed.
     *
     * It is also the *only* path that can repair two things [provision] did not touch —
     * the agent-dir copy of `rg`/`fd` and the guest's `/etc/group` — so both run here;
     * see [ensureToolsVisible] and [ensureAndroidGroups]. `prepareLibraryAliases`
     * creates `paths.lib` through its own getter, exactly as the early return always
     * did; `paths.tmp` is likewise created by its getter wherever it is used, so neither
     * needs a step of its own here (and adding one would be a new syscall on every cold
     * start).
     *
     * The stamp is rewritten when it does not match, and that is not bookkeeping
     * pedantry: the revision also covers payloads that never enter this tree (the five
     * proroot `.so`, installed by Android's native-library extractor). When one of those
     * changes, no runtime payload did — the honest thing is to say so and to record the
     * new revision, not to re-extract 110 MiB because a number moved.
     *
     * The steps say what happened. "运行时已是最新，无需重解" is the whole answer, and
     * the screen shows it rather than a sequence of extraction steps that never ran.
     */
    private fun finishCurrent(revision: String, migration: Boolean, onStep: (Step) -> Unit): ProvisionOutcome {
        val steps = listOf(CURRENT_STEP, FINAL_STEP)
        onStep(Step(steps[0], 0, steps.size))
        paths.prepareLibraryAliases()
        // The two tools live in *two* directories, and one of them is outside the
        // stamped tree ([PiPaths.agentBinDir]). A boot that does not re-extract is
        // therefore still the only thing that can repair a tool the agent-dir migration
        // moved out of the rootfs — see [ensureToolsVisible]. Cheap: two file-existence
        // probes.
        ensureToolsVisible()
        // Same shape, same reason: a device that already unpacked keeps its rootfs, so a
        // boot that does *not* re-extract is the only path that can repair the guest's
        // `/etc/group` ([ensureAndroidGroups]). Idempotent, and free when there is
        // nothing to add.
        val groupWarning = ensureAndroidGroups()
        val stampWarning = if (isStampCurrent(revision)) null else writeStamp(revision)
        val warning = listOfNotNull(groupWarning, stampWarning).firstOrNull()
        onStep(Step(warning ?: steps[steps.size - 1], steps.size - 1, steps.size))
        return ProvisionOutcome(reextracted = emptyList(), migrated = migration, rebuilt = false)
    }

    // ------------------------------------------------------------------- steps

    /**
     * Create the three directories the volatile tree must have.
     *
     * This used to be a side effect of [wipe], which meant the paths below only existed
     * on a boot that had deleted the tree. `paths.tmp` is proot's `PROOT_TMP_DIR` and
     * `paths.lib` holds the `libtalloc.so.2` alias; both must exist before proot runs,
     * and a boot that re-extracts one payload is exactly the boot that changes what is
     * in them. Calling this on both provisioning paths is what keeps that true now that
     * most boots do not wipe anything.
     */
    private fun prepareVolatileDirs() {
        paths.runtime.mkdirs()
        paths.tmp.mkdirs()
        paths.lib.mkdirs()
    }

    /**
     * The explicit, whole-tree repair.
     *
     * **Reachable only from `ensureReady(rebuild = true)`** — see the class KDoc. This is
     * the old behaviour, kept because a genuinely broken tree has to be recoverable, and
     * it is honest about its cost: everything the user installed inside the guest goes
     * with it. It deletes nothing outside `paths.runtime`.
     *
     * ## The one way it could take user data, and what closes it
     *
     * Everything above holds for today's layout because all three durable directories live
     * *outside* `paths.runtime` ([DurableLayout]). The moment one of them moves **inside**
     * the rootfs — which is what removing the last proroot bind would require — this
     * method becomes a data-loss path.
     *
     * [DurablePreserve] closes it: the durable directories that are inside the rootfs are
     * **moved out** to `<files>/pi/.preserve` first, and moved back afterwards. For today's
     * layout that list is empty ([DurableLayout.durableInsideRootfs]), so this method does
     * exactly what it did before — one recursive delete, then the skeleton — and the
     * harness pins that emptiness.
     *
     * If the data cannot be moved out, this **refuses to delete** and the rebuild fails
     * loudly. That direction is deliberate: a failed repair is recoverable, a silent
     * delete is not.
     */
    private fun wipe() {
        // Only the volatile tree, never `paths.home`. [deleteTreeInsideVolatile] is the
        // structural half of that sentence: it refuses any target outside `paths.runtime`,
        // so a future edit that repoints this call fails loudly instead of deleting a
        // workspace.
        val preserve = File(paths.home, DurablePreserve.PRESERVE_DIR)
        val stash = try {
            DurablePreserve.moveOut(
                DurableLayout.durableInsideRootfs(DurableLayout.durableDirs(paths.home, paths.runtime), paths.rootfs),
                preserve,
            )
        } catch (refused: java.io.IOException) {
            throw ProvisioningException("拒绝重建运行时：${refused.message}", refused)
        }
        var deleted = false
        try {
            deleteTreeInsideVolatile(paths.runtime, "重建运行时（显式修复）")
            deleted = true
        } finally {
            // Same order as before this object existed: the skeleton is recreated only
            // after a *successful* delete, so the refusal path leaves the tree alone.
            if (deleted) prepareVolatileDirs()
            val stranded = DurablePreserve.restore(stash)
            // Anything that could not be put back stays in `.preserve` — that copy is the
            // only place the data still exists, so it is not cleaned up in that case.
            // `delete()`, never `deleteRecursively()`: [DurablePreserve.restore] emptied it,
            // and this file's one recursive delete stays the one in
            // [deleteTreeInsideVolatile] (the harness pins that count).
            if (stranded.isEmpty()) runCatching { preserve.delete() }
        }
    }

    /**
     * Recursively delete [target], but only if it is inside the volatile tree.
     *
     * Every recursive delete in this class goes through here. The check is
     * [VolatileTree.offending] — a pure function a bare-JVM harness pins — and it is the
     * reason "wipe() 之外一个节点都不许删" is a property of the code rather than a
     * comment: the only targets this class ever passes are the volatile root and scratch
     * directories it creates under it, and a target that is not under it throws instead
     * of being deleted.
     */
    private fun deleteTreeInsideVolatile(target: File, why: String) {
        val offenders = VolatileTree.offending(paths.runtime, listOf(target))
        if (offenders.isNotEmpty()) {
            throw ProvisioningException(
                "拒绝删除易失树之外的路径：${offenders.joinToString { it.path }}（$why）。" +
                    "易失树是 ${paths.runtime.path}；耐久目录（工作区、agentDir、persist）" +
                    "永远不在它下面，也永远不在这里被删。",
            )
        }
        // A symlink is refused outright, because `deleteRecursively` follows one while it
        // walks: a link where a directory is expected would delete the *target's* contents,
        // which is the one way a lexical containment check cannot see the escape. Nothing
        // in this app ever replaces one of these directories with a link, so this can only
        // fire on a state somebody else created — and then refusing is the right answer.
        if (runCatching { java.nio.file.Files.isSymbolicLink(target.toPath()) }.getOrDefault(false)) {
            throw ProvisioningException(
                "拒绝删除符号链接指向的目录：${target.path}（$why）：递归删除会跟随链接，" +
                    "删掉链接目标里的内容。",
            )
        }
        target.deleteRecursively()
    }

    // ------------------------------------------------------------ payload state

    /**
     * One payload's extraction, by name.
     *
     * The `when` is exhaustive on purpose and throws on an unknown name: the payload set
     * and the extraction code have to agree, and a seventh payload added to [PAYLOADS]
     * without an arm here is a build-time list that silently does nothing on device.
     */
    private fun extractPayloadOver(payload: Payload) {
        when (payload.name) {
            UBUNTU_BASE -> extractAsset(UBUNTU_BASE, paths.rootfs)
            NODE_ARCHIVE -> extractNode()
            RIPGREP_ARCHIVE -> installTool(RIPGREP_ARCHIVE, "rg")
            FD_ARCHIVE -> installTool(FD_ARCHIVE, "fd")
            GIT_ARCHIVE -> installGit()
            ENGINE_ARCHIVE -> extractEngine()
            else -> throw ProvisioningException("unknown payload ${payload.name}")
        }
    }

    /** The screen's name for each payload's work; only planned payloads get one. */
    private fun stepLabel(payload: Payload): String = when (payload.name) {
        UBUNTU_BASE -> "解压 Ubuntu 用户态"
        NODE_ARCHIVE -> "解压 Node 运行时"
        RIPGREP_ARCHIVE -> "安装 rg"
        FD_ARCHIVE -> "安装 fd"
        GIT_ARCHIVE -> "安装 git"
        ENGINE_ARCHIVE -> "解压 pi 引擎"
        else -> "解压 ${payload.name}"
    }

    /** `<name>.tgz` -> `<name>`, the spelling of the payload-state files in `assets/`. */
    private fun payloadMetaName(payload: Payload): String = payload.name.removeSuffix(PAYLOAD_SUFFIX)

    private fun digestFile(payload: Payload): File =
        File(paths.payloadStateDir(), "${payloadMetaName(payload)}.digest")

    private fun listFile(payload: Payload): File =
        File(paths.payloadStateDir(), "${payloadMetaName(payload)}.list")

    /**
     * The digest of this payload's bytes as this APK carries it, from
     * `assets/runtime-payloads/<name>.digest` (written by `tools/fetch-runtime.mjs`).
     *
     * Null means "this APK does not say" — an asset that is missing, empty or not a
     * 16-hex digest. Null is deliberately *not* an error: the payload is then treated as
     * changed and extracted over the tree, which is always safe (it deletes nothing).
     * That is also what a bare `assembleRelease` without the assembler produces, and
     * there the payload audit fails first, exactly as before.
     */
    private fun packagedDigest(payload: Payload): String? =
        readAssetText("$PAYLOAD_META_DIR/${payloadMetaName(payload)}.digest")
            ?.takeIf { it.length == DIGEST_LENGTH && it.all { ch -> ch.isDigit() || ch in 'a'..'f' } }

    /**
     * The paths this payload owns in this APK, from
     * `assets/runtime-payloads/<name>.list`: relative to `paths.runtime`, sorted, one per
     * line, no leading `./`.
     *
     * Null means "this APK does not say", and the two callers treat that differently and
     * conservatively:
     *
     *  - [prunePayload] deletes **nothing** — with no new list there is no way to tell a
     *    stale file from a file the user made;
     *  - [recordPayloadState] writes no list, so the next attempt prunes nothing either.
     *
     * Both are the safe side of "never delete something outside the lists".
     */
    private fun packagedList(payload: Payload): List<String>? =
        readAssetText("$PAYLOAD_META_DIR/${payloadMetaName(payload)}.list")
            ?.let { PayloadPrune.parseList(it) }

    private fun readAssetText(name: String): String? = runCatching {
        assets.open(name, AssetManager.ACCESS_BUFFER).use { it.readBytes().decodeToString() }
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun readStateDigest(payload: Payload): String? = digestFile(payload)
        .takeIf { it.isFile }
        ?.let { runCatching { it.readText().trim() }.getOrNull() }
        ?.takeIf { it.isNotEmpty() }

    private fun readStateList(payload: Payload): List<String>? = listFile(payload)
        .takeIf { it.isFile }
        ?.let { runCatching { PayloadPrune.parseList(it.readText()) }.getOrNull() }

    /**
     * Delete exactly the paths the previous version of this payload owned and this one
     * does not. Nothing else, ever.
     *
     * Every step of the reasoning is in [PayloadPrune]: the delete set is
     * `old ∩ present − new`, so a file in neither list (the user's own) cannot be in it,
     * a path in both is kept, and only an old-only path is removed. The `present` set is
     * built here, with a check that does **not** follow symlinks, because a dangling
     * `/usr/local/bin/<tool>` is exactly the kind of stale entry this has to clean up and
     * `File.exists()` reports it as absent.
     *
     * `File.delete()`, never `deleteRecursively()`: the lists contain no directories
     * (the build emits files and symlinks only), so a delete can never take a subtree
     * with it. A no-op for any payload with no recorded state — which is why the
     * migration to this format deletes nothing at all.
     *
     * ## The second gate: the path has to *still* be inside the tree
     *
     * A list entry is a legal relative path, but that says nothing about where it points
     * *now*. The tree is writable by the guest (as the app's uid), so any parent directory
     * on the way to an entry can have been replaced with a symlink since the payload was
     * extracted — and `File(runtime, "rootfs/a/b").delete()` would then delete through it,
     * up to `<files>/pi/.pi/agent/b` if that is where the link points. So every victim's
     * **canonical** path (symlinks resolved) is checked with [PayloadPrune.within] before
     * anything is deleted, and a path that now resolves outside the volatile tree is
     * refused. This is the same rule [TarExtractor.resolveSafely] enforces in the other
     * direction (nothing may be *written* outside the destination).
     *
     * @param newList this APK's list for the payload, already read and parsed by the caller.
     */
    private fun prunePayload(payload: Payload, newList: List<String>?) {
        val old = readStateList(payload) ?: return
        val new = newList ?: return
        // An empty *present* list means this APK listed the payload as owning nothing.
        // There is no such payload in [PAYLOADS]; treating it as "prune everything the
        // old list owned" would turn a build-side mistake into deleted files, so an
        // empty list is read as "cannot tell" and nothing is pruned.
        if (new.isEmpty()) return
        val present = old.filterTo(HashSet()) { relative -> existsWithoutFollowing(File(paths.runtime, relative)) }
        val root = runCatching { paths.runtime.canonicalPath }.getOrNull() ?: return
        PayloadPrune.victims(old, new, present).forEach { relative ->
            val target = File(paths.runtime, relative)
            val canonical = runCatching { target.canonicalPath }.getOrNull() ?: return@forEach
            if (!PayloadPrune.within(root, canonical)) return@forEach
            runCatching { target.delete() }
        }
    }

    /** Existence without following a symlink, so a dangling link still counts as present. */
    private fun existsWithoutFollowing(file: File): Boolean =
        file.exists() || runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    /**
     * Record what this payload now is and what it owns, so the next attempt can tell
     * "unchanged" from "changed" and can prune the right set.
     *
     * Written through [writeStampAtomically] for the reason the stamp is: both files are
     * read back and compared, and a process killed mid-write would otherwise leave a
     * short digest that reads as "changed" and re-extracts a payload that was fine — or a
     * short list that makes the *next* prune miss entries. Written **after** the payload
     * is fully extracted: a digest written before the bytes would claim a tree that is
     * not there.
     *
     * @param digest this APK's digest for the payload, already read by the caller (null =
     *        this APK does not say; then no state is written and the next attempt reads it
     *        as changed, which is the safe direction).
     * @param list the packaged list, already read and parsed.
     */
    private fun recordPayloadState(payload: Payload, digest: String?, list: List<String>?) {
        if (digest == null) return
        val dir = paths.payloadStateDir().also { it.mkdirs() }
        writeStampAtomically(File(dir, "${payloadMetaName(payload)}.digest"), digest + "\n")
        if (list == null) return
        writeStampAtomically(
            File(dir, "${payloadMetaName(payload)}.list"),
            PayloadPrune.encodeList(list),
        )
    }

    private fun extractAsset(assetName: String, into: File) {
        val archive = "runtime/$assetName"
        // `AssetManager.open` throws a bare-path `FileNotFoundException` when the
        // asset is not in the APK — but an unpack that dies half way (disk full, a
        // truncated archive, a rejected symlink) can carry a bare path in its
        // message too, and the old single catch turned both into
        // "failed to unpack ubuntu-base.tgz: runtime/ubuntu-base.tgz". That
        // ambiguity is indistinguishable from a device failure and cost a round
        // trip; [openPayload] now names the access layer that failed and the
        // pre-flight audit is appended here, so even a half-way failure arrives
        // with the contents of the package attached.
        val stream = openPayload(archive)
        try {
            stream.use { TarExtractor(into).extractGzip(it) }
        } catch (e: IOException) {
            throw ProvisioningException("failed to unpack $assetName: ${e.message}${payloadReportBlock()}", e)
        }
    }

    /**
     * Open a payload archive from the APK, and if that fails, say what the APK
     * actually contains and which access layer failed.
     *
     * A device reported `failed to unpack ubuntu-base.tgz: runtime/ubuntu-base.tgz`
     * — this class's own message, naming the asset it could not open — on an APK
     * that is 127,195,427 bytes, while the payload archives alone account for
     * ~115 MB of it, so the payload was demonstrably in the package. Two things were
     * wrong with the old code as a diagnostic: it used one access mode, and when that
     * mode failed it said nothing about the state of the APK, so a missing asset, a
     * differently-placed asset and a broken install were indistinguishable.
     *
     * There was in fact a third thing wrong, and it was the cause: the asset was
     * there under a *different name*, because AAPT2 gunzips and renames any asset
     * whose name ends in `.gz`. That is why this method lists the directory it was
     * told to read — the listing is what named the real problem. See
     * [PAYLOAD_SUFFIX].
     *
     * Three layers now, tried in order, each failure recorded separately:
     *
     *  1. `open(ACCESS_STREAMING)` — the normal path; inflates the entry itself.
     *  2. `open(ACCESS_BUFFER)` — some install paths have been observed to serve
     *     one mode and not the other.
     *  3. `openFd()` + `AssetFileDescriptor.createInputStream()` — the classic way
     *     to read an asset, and for a *stored* entry it is a plain file read with
     *     no inflate step. It only works on stored entries (a compressed one
     *     throws "can not be opened as a file descriptor; it is probably
     *     compressed"), which is exactly what `noCompress += "gz"`
     *     in app/build.gradle.kts now guarantees for these suffixes: layer 3 and
     *     that build setting are one fix, not two.
     *
     * If all three fail, the message names each layer's failure (exception class
     * and message, so `FileNotFoundException` and a truncated-entry `IOException`
     * are not the same text) and lists `assets/runtime/` and the asset root as the
     * APK actually serves them.
     */
    private fun openPayload(archive: String): InputStream {
        val failures = mutableListOf<String>()
        var last: IOException? = null

        try {
            return assets.open(archive, AssetManager.ACCESS_STREAMING)
        } catch (e: IOException) {
            last = e
            failures += "1 open(ACCESS_STREAMING) failed: ${describe(e)}"
        }

        try {
            return assets.open(archive, AssetManager.ACCESS_BUFFER)
        } catch (e: IOException) {
            last = e
            failures += "2 open(ACCESS_BUFFER) failed: ${describe(e)}"
        }

        try {
            val fd = assets.openFd(archive)
            // `createInputStream()` hands back a stream over the descriptor. The
            // wrapper closes the AssetFileDescriptor as well as the stream, and
            // makes closing twice (or close-after-close) harmless: a leaked
            // descriptor would be one more way to make a *later* payload fail.
            return object : FilterInputStream(fd.createInputStream()) {
                private var closed = false
                override fun close() {
                    if (closed) return
                    closed = true
                    try {
                        super.close()
                    } finally {
                        runCatching { fd.close() }
                    }
                }
            }
        } catch (e: IOException) {
            last = e
            failures += "3 openFd() failed: ${describe(e)}"
        }

        throw ProvisioningException(
            buildString {
                append("packaged asset unreadable: $archive could not be opened from this APK.\n")
                failures.forEach { append("  ").append(it).append("\n") }
                append("assets/runtime/ contains: ").append(listAssets("runtime")).append("\n")
                append("assets/ root contains: ").append(listAssets("")).append("\n")
                append(payloadReportBlock())
                append(PAYLOAD_HINT)
            },
            last,
        )
    }

    // ------------------------------------------------------------- payload audit

    /**
     * Confirm, before anything is unpacked, that each payload archive is present
     * in the APK *and readable*, and record its size.
     *
     * This is the screen-visible half of the fix for the device report above.
     * Waiting for the unpack to fail tells the user almost nothing: the wipe has
     * already run, and only one asset is named. Auditing all five first turns the
     * next device attempt into a list of what the APK actually contains — file
     * names and sizes, in the step label, and again in any later failure message
     * through [payloadReportBlock].
     *
     * The audit runs before [wipe] on purpose: a package that cannot be read must
     * not destroy a runtime that already works.
     *
     * A missing or unreadable *required* payload throws here (the unpack would fail
     * on it a moment later, after the wipe). `pi-engine.tgz` is reported but not
     * required, mirroring [extractEngine], which treats a package without the
     * engine as provisionable and leaves installing it for later.
     */
    private fun auditPayloads(): List<PayloadInfo> {
        val audited = PAYLOADS.map { inspectPayload(it) }
        payloadReport = audited.joinToString("\n") { it.line() }

        val missing = audited.filter { it.required && !it.ok }
        if (missing.isNotEmpty()) {
            throw ProvisioningException(
                buildString {
                    append("bundled runtime payload unusable: ")
                    append(missing.joinToString(", ") { it.asset })
                    append("\n")
                    append(payloadReport).append("\n")
                    missing.forEach { info ->
                        append("\nreading ").append(info.asset).append(" failed:\n")
                        append(info.error ?: "(no detail)").append("\n")
                    }
                    append("\nassets/runtime/ contains: ").append(listAssets("runtime"))
                    append("\nassets/ root contains: ").append(listAssets(""))
                    append("\n").append(PAYLOAD_HINT)
                },
            )
        }
        return audited
    }

    private fun inspectPayload(payload: Payload): PayloadInfo {
        val asset = "runtime/${payload.name}"
        return try {
            val (bytes, via) = payloadSize(asset)
            PayloadInfo(asset, payload.required, bytes, via, null)
        } catch (e: IOException) {
            PayloadInfo(asset, payload.required, 0L, null, describe(e))
        }
    }

    /**
     * The same six payloads [auditPayloads] checks, measured, **without throwing**
     * and without touching `payloadReport`.
     *
     * The diagnostic report needs this as a fact, not as a gate: the audit's job is
     * to refuse to start when a required payload is unreadable (and to write the
     * failure into `payloadReport` for a later unpack error to carry), while the
     * report's job is to say what is there even when the answer is "not everything".
     * The two share [inspectPayload] and [PAYLOADS] so a seventh payload cannot be
     * added to one and forgotten by the other.
     *
     * Blocking IO: `AssetManager.openFd` or a full stream count per payload. Callers
     * run it off the main thread.
     */
    internal fun payloadInventory(): List<PayloadInfo> = PAYLOADS.map { payload ->
        runCatching { inspectPayload(payload) }.getOrElse { error ->
            PayloadInfo("runtime/${payload.name}", payload.required, 0L, null, describe(error))
        }
    }

    /**
     * Size of one payload in bytes, plus which access path produced the number.
     *
     * `AssetManager.openFd` is tried first: for a *stored* (uncompressed) ZIP
     * entry the length comes straight out of the APK directory without moving a
     * byte, and the `androidResources { noCompress += ... }` block in
     * app/build.gradle.kts is what stores these suffixes. On an APK where AAPT2
     * deflated them, `openFd` throws its "probably compressed"
     * `FileNotFoundException` and the length is counted off the stream instead —
     * through [openPayload], the same chain the unpacker uses, so an unreadable
     * payload is reported with the same per-layer detail and the same directory
     * listings.
     */
    private fun payloadSize(asset: String): Pair<Long, String> {
        val viaFd = runCatching { assets.openFd(asset).use { it.length } }.getOrNull()
        if (viaFd != null && viaFd > 0L) return viaFd to "openFd"
        val counted = openPayload(asset).use { countBytes(it) }
        return counted to "stream"
    }

    private fun countBytes(stream: InputStream): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) return total
            total += read.toLong()
        }
    }

    /** One line per payload for the step label and for [payloadReport]. */
    private fun PayloadInfo.line(): String = buildString {
        append(asset).append("  ")
        if (ok) {
            append(formatBytes(bytes))
            if (via != null) append(" via ").append(via)
        } else {
            append("UNREADABLE")
            if (error != null) append(" (").append(error.lineSequence().first()).append(")")
        }
        if (!required) append(" [optional]")
    }

    /** The same audit as one compact, on-screen line of names and sizes. */
    private fun auditLabel(audited: List<PayloadInfo>): String =
        "校验内置载荷：" + audited.joinToString(" · ") { info ->
            val name = info.asset.removePrefix("runtime/")
            if (info.ok) "$name ${formatBytes(info.bytes)}" else "$name 缺失/不可读"
        }

    /**
     * The audit, for a failure that happens *after* it — an unpack that dies
     * half way, or an `open()` that fails during extraction rather than during the
     * audit. Empty when no audit ran in this provisioning attempt.
     */
    private fun payloadReportBlock(): String =
        if (payloadReport.isBlank()) "" else "\npayload audit:\n$payloadReport\n"

    private fun listAssets(dir: String): String =
        runCatching { assets.list(dir)?.sorted()?.joinToString(", ") }
            .getOrNull()
            ?.ifBlank { "(empty)" }
            ?: "(unlistable)"

    private fun describe(e: Throwable): String =
        "${e::class.java.simpleName}: ${e.message ?: "(no message)"}"

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> {
            val tenths = (bytes / 1048576.0 * 10).roundToInt()
            "${tenths / 10}.${tenths % 10} MiB"
        }
        bytes >= 1024L -> "${bytes / 1024L} KiB"
        else -> "$bytes B"
    }

    /**
     * Node ships as `.tar.xz` upstream and Java cannot decode xz, so
     * `tools/fetch-runtime.mjs` re-packs it as gzip at build time. The archive
     * contains a single top-level `node-vX-linux-arm64/` directory, and the guest
     * expects it at `/opt/node`.
     *
     * ## Why this merges instead of replacing
     *
     * It used to `deleteRecursively()` `<rootfs>/opt/node` and move the new tree into
     * place. That directory is where `npm -g` installs its packages, so a Node bump —
     * which has nothing to do with what the user installed — deleted every globally
     * installed package. Now the new tree is **merged over** the old one: same-named
     * files are overwritten, files that only the user has are left alone, and the stale
     * ones the payload used to ship are removed afterwards by [prunePayload] (from the
     * old `node.list`), which is the only code allowed to delete anything here.
     *
     * [mergeTreeOver] copies rather than renames, so the executable bit has to be
     * re-applied from the source: `File.copyTo` does not carry POSIX permissions, and a
     * `/opt/node/bin/node` without `+x` is a guest whose `node` does not run.
     */
    private fun extractNode() {
        val staging = File(paths.runtime, "node-stage")
        // Scratch, under the volatile tree, created by this method; see
        // [deleteTreeInsideVolatile] for the check that keeps that true.
        deleteTreeInsideVolatile(staging, "node 暂存目录")
        extractAsset(NODE_ARCHIVE, staging)
        val top = staging.listFiles()?.firstOrNull { it.isDirectory }
            ?: throw ProvisioningException("node archive had no top-level directory")
        val target = File(paths.rootfs, "opt/node")
        target.parentFile?.mkdirs()
        mergeTreeOver(top, target)
        deleteTreeInsideVolatile(staging, "node 暂存目录")
        // Expose it on PATH the way the guest expects.
        guestSymlink("/usr/local/bin/node", "/opt/node/bin/node")
        guestSymlink("/usr/local/bin/npm", "/opt/node/bin/npm")
        guestSymlink("/usr/local/bin/npx", "/opt/node/bin/npx")
    }

    /**
     * Merge the tree at [from] into [to]: same-named files are overwritten, missing ones
     * are added, and **nothing under [to] is deleted**.
     *
     * The "nothing is deleted" half is the point. Callers are payload extractions whose
     * destination is a directory the guest also writes to (`/opt/node` is the clearest
     * case: `npm -g`), and the old code's `deleteRecursively()` there is what an update
     * used to cost the user. Removal of payload-owned stale files is [prunePayload]'s
     * job, driven by the payload's own list.
     *
     * Symlinks are recreated as symlinks (a dereferenced `npm -> ../lib/...` copy is a
     * different, larger tree), and the executable bit is re-applied because
     * `File.copyTo` does not carry permissions.
     */
    private fun mergeTreeOver(from: File, to: File) {
        val stack = ArrayDeque<Pair<File, File>>()
        stack.addLast(from to to)
        while (stack.isNotEmpty()) {
            val (source, destination) = stack.removeLast()
            destination.mkdirs()
            for (child in source.listFiles() ?: continue) {
                val target = File(destination, child.name)
                val path = child.toPath()
                if (java.nio.file.Files.isSymbolicLink(path)) {
                    val link = runCatching { java.nio.file.Files.readSymbolicLink(path) }.getOrNull() ?: continue
                    runCatching {
                        if (java.nio.file.Files.isSymbolicLink(target.toPath()) || target.exists()) target.delete()
                        java.nio.file.Files.createSymbolicLink(target.toPath(), link)
                    }
                    continue
                }
                if (child.isDirectory) {
                    stack.addLast(child to target)
                    continue
                }
                if (!child.isFile) continue
                runCatching {
                    child.copyTo(target, overwrite = true)
                    if (child.canExecute()) target.setExecutable(true, false)
                }
            }
        }
    }

    /**
     * ripgrep and fd are hard dependencies of pi's `grep` and `find` tools.
     *
     * pi will try to download them itself, but it **refuses** when
     * `process.platform === "android"` (`utils/tools-manager.ts`). Inside proot
     * the platform is `linux`, so pi would happily fetch them — but the first
     * launch may not have a network, and a tool that silently fails on a plane
     * is worse than one that ships. Both binaries are static musl builds, so
     * they run in the guest regardless of libc.
     *
     * ## git: installed, and exactly how far it reaches (`docs/known-gaps.md` K2)
     *
     * pi's package manager supports four source kinds — npm, git, an explicit URL
     * and a path — and the git one shells out to a bare `git`:
     * `installGit` runs `git clone <repo> <targetDir>` then `git checkout <ref>`
     * (`packages/coding-agent/src/core/package-manager.ts:1850`, `:1852`), and
     * `updateGit` uses `git fetch` / `rev-parse` / `reset --hard`
     * (`:1932-1956`). Each call is spawned with the process environment
     * (`:2604-2611`), so git has to be on the guest's PATH. [installGit] puts it
     * there, which is also what lets the model itself run `git status`, `git diff`,
     * `git log` and `git commit` instead of only being able to read `.git/HEAD`.
     *
     * ### What works
     *
     *  - **`https://` remotes.** `git clone`, `fetch`, `pull` and `push` against
     *    GitHub and friends: the payload carries `libcurl-gnutls.so.4`, its full
     *    transitive library closure, and a CA bundle at
     *    [CA_BUNDLE][ProotCommand.GUEST_CA_BUNDLE], and [ProotCommand.environment]
     *    points `GIT_SSL_CAINFO`/`SSL_CERT_FILE` at that file — necessary because
     *    the pinned base ships no `/etc/ssl` at all, so there is nothing else for
     *    a TLS peer to be validated against. Node is unaffected either way: it
     *    carries its own CA store.
     *  - **Everything local.** init, status, diff, log, commit, add, branch,
     *    checkout, merge, rebase, stash and the rest of the builtins; one real
     *    binary serves them all, and `git-remote-http` serves the HTTPS transport.
     *
     * ### What still does NOT work — a boundary, not a bug
     *
     *  - **`git@host:path` and `ssh://…`.** These are the other half of the source
     *    grammar pi advertises (`packages/coding-agent/README.md:417-422`, resolved
     *    at `src/utils/git.ts:172-199`). They need an **ssh client plus a key or an
     *    agent**, and none of that is shipped: `git-remote-http` links `libssh.so.4`
     *    — which is libcurl's `sftp://` scheme, not git's ssh transport — and there
     *    is no `ssh` binary, no `~/.ssh`, no agent forwarding and no way to answer a
     *    host-key or passphrase prompt. An ssh source therefore fails with
     *    "cannot run ssh: No such file or directory", which is the honest outcome.
     *    The UI should keep saying so rather than implying ssh works.
     *  - **`git commit` needs an identity** before it will do anything:
     *    `user.name` and `user.email` are unset, and the payload does not invent
     *    them — a commit attributed to a made-up author is worse than a clear
     *    "Please tell me who you are". One `git config --global user.email …` fixes
     *    it.
     *  - **Subcommands Ubuntu ships in *other* packages**: `git svn` (`git-svn`),
     *    `git send-email` (`git-email`), `git gui`/`gitk` (`git-gui`, `gitk`),
     *    `git web--browse`'s browsers, and `git instaweb` (no web server). The
     *    payload is the `git` package alone, so those are absent rather than
     *    broken.
     *
     * ### Why the payload looks the way it does
     *
     * It is a pinned set of Ubuntu `.deb`s in `runtime.lock.json` — git plus the 16
     * libraries the base does not ship — re-assembled into a `.tgz` by
     * `tools/fetch-runtime.mjs` (the Debian payload is an `ar` archive the app's
     * [TarExtractor] cannot read, the same reason Node is re-compressed from
     * `.tar.xz` there). Two consequences worth knowing:
     *
     *  - the closure is **computed, not guessed** — 33 sonames reached, 18 already
     *    in the base, 16 new, 0 unresolved — and the build re-checks it, so an
     *    upstream bump that moves a soname fails the build instead of the phone;
     *  - these libraries **do not receive Ubuntu security updates** the way a
     *    desktop install does. Refreshing them means bumping the pins, which is
     *    also why the UI must not promise more than the pins deliver.
     *
     * Reliability under proot is the one thing this cannot settle from here:
     * proot, its hardlink shim and `--link2symlink` sit under every file git
     * writes, and no device measurement of `git commit`/`gc` has been taken yet.
     * The npm source path, which most packages use, is unaffected by all of this.
     */
    private fun installTool(archive: String, binaryName: String) {
        val staging = File(paths.runtime, "${archive.removeSuffix(PAYLOAD_SUFFIX)}-stage")
        // Scratch, under the volatile tree, created by this method; see
        // [deleteTreeInsideVolatile] for the check that keeps that true.
        deleteTreeInsideVolatile(staging, "$binaryName 暂存目录")
        extractAsset(archive, staging)
        val binary = staging.walkTopDown()
            .filter { it.isFile && it.name == binaryName }
            .maxByOrNull { it.length() }
            ?: throw ProvisioningException("$binaryName not found in $archive")

        // Both copies, deliberately — a tool installed into only one of them is
        // invisible to one of the three things that launch a guest process. Read
        // [PiPaths.agentBinDir] before moving either line: the short version is that
        // the engine and package commands bind the durable dir over the guest's
        // `/root/.pi/agent` and the terminal does not, so `agentBinDir` is the copy
        // with the bind and `rootfsAgentBinDir` is the copy without it.
        //
        // 2026-09-23: there is only **one** directory now — the agent dir moved into the
        // rootfs, the bind went away, and `rootfsAgentBinDir()` is defined as
        // `agentBinDir()`. The loop below therefore had two identical iterations; it has
        // one, and this comment stays so the *reason* it used to be two is not lost.
        val dir = paths.agentBinDir()
        dir.mkdirs()
        val dest = File(dir, binaryName)
        binary.copyTo(dest, overwrite = true)
        dest.setExecutable(true, false)
        deleteTreeInsideVolatile(staging, "$binaryName 暂存目录")
        publishTool(binaryName)
    }

    /**
     * Unpack the git payload (see [installTool]'s KDoc for what it contains and how
     * far it reaches).
     *
     * One line, because `git.tgz` is assembled at build time as a tree that already
     * has the guest's own shape — `usr/bin/git`, `usr/lib/git-core/…`,
     * `usr/lib/aarch64-linux-gnu/…`, `etc/ssl/certs/ca-certificates.crt`. So unlike
     * rg and fd there is no binary to hunt for and nothing to relocate: extracting
     * it over the rootfs *is* the install. It also needs no `/usr/local/bin` symlink
     * and no second copy in the agent dir, because `/usr/bin` is already on the PATH
     * [ProotCommand.environment] sets and no bind shadows `/usr` — the trap that
     * [PiPaths.agentBinDir] documents for `/root/.pi/agent` simply does not apply
     * here.
     *
     * The CA bundle rides along in the same archive, and
     * [ProotCommand.environment] is what makes git use it.
     */
    private fun installGit() {
        extractAsset(GIT_ARCHIVE, paths.rootfs)
    }

    /**
     * Re-assert the `/usr/local/bin/<tool>` symlink and make sure the file it points at
     * exists in **both** directories a launch path can resolve it through.
     *
     * ## Why this is not just [installTool] again
     *
     * `PiPaths.agentBinDir` explains the two directories. Two separate things can empty
     * one of them after provisioning has already run, and neither re-runs `installTool`,
     * because `ensureReady` returns early while the stamp is current:
     *
     *  - `PiEngineHost.migrateGuestAgentDir` runs **before** provisioning on every boot
     *    (`PiEngineHost.kt:223`, then `:227`) and *moves* the children of the rootfs
     *    agent dir into the durable one. It skips an entry whose target already exists,
     *    so a complete install is left alone — but an install that predates this fix
     *    has no `agentBinDir` yet, and there its `bin/` is renamed away, taking the
     *    rootfs copy with it. That is the terminal's copy, and only this function puts
     *    it back.
     *  - `wipe()` deletes `rootfsAgentBinDir` wholesale on a revision bump. `installTool`
     *    recreates it on that same boot, so this is the belt to that pair of braces.
     *
     * Copying from whichever copy survived keeps the two in step without needing the
     * asset, which is what makes this callable from the early-return path where nothing
     * has been unpacked yet. `overwrite = false` is the point: this repairs a missing
     * file and must never race a fresh extraction into downgrading one.
     */
    private fun ensureToolsVisible() {
        TOOL_BINARIES.forEach { publishTool(it) }
    }

    /**
     * One tool's guest-visible spelling: `/usr/local/bin/<name>` pointing at the guest
     * path `/root/.pi/agent/bin/<name>`, plus the guarantee that both host directories
     * that guest path can mean actually hold the file.
     *
     * The symlink **must** name the guest path, not a host path — see [guestSymlink].
     */
    private fun publishTool(binaryName: String) {
        // One target, not two identical ones: `rootfsAgentBinDir()` is `agentBinDir()`
        // since the agent dir moved into the rootfs (2026-09-23). `source` and `target`
        // being the same file is exactly why the old two-copy loop could never copy
        // anything here — this is the same behaviour with the redundancy removed.
        val target = File(paths.agentBinDir(), binaryName)
        if (!target.isFile) return
        target.setExecutable(true, false)
        guestSymlink("$GUEST_LOCAL_BIN/$binaryName", "$GUEST_AGENT_BIN/$binaryName")
    }

    /**
     * Guest configuration that Android cannot supply.
     *
     * DNS is the important one: glibc inside proot cannot see Android's
     * per-network resolver, so without a static `resolv.conf` every name lookup
     * fails while the network is otherwise fine — the single most confusing
     * failure mode of a proot'd userland (docs/pi-android-app-design.md §13).
     */
    private fun configureGuest(): String? {
        val etc = File(paths.rootfs, "etc").also { it.mkdirs() }
        File(etc, "resolv.conf").writeText(
            """
            # Written by pi-android. glibc in a proot cannot reach Android's
            # per-network resolver, so nameservers are pinned here.
            nameserver 223.5.5.5
            nameserver 8.8.8.8
            nameserver 1.1.1.1
            """.trimIndent() + "\n",
        )
        File(etc, "hosts").writeText(
            """
            127.0.0.1   localhost
            ::1         localhost ip6-localhost
            """.trimIndent() + "\n",
        )

        // pi's home inside the guest: the same layout a desktop install has, so
        // settings, skills, extensions and themes are interchangeable.
        File(paths.rootfs, "root/.pi/agent").mkdirs()
        File(paths.rootfs, "workspace").mkdirs()

        // The guest's group database, repaired from this process's own supplementary
        // groups. See [ensureAndroidGroups]; the returned warning travels to the boot
        // screen's last step label.
        return ensureAndroidGroups()
    }

    /**
     * Make the guest's `/etc/group` know the gids this process actually has.
     *
     * ## What was wrong
     *
     * A freshly opened terminal printed five lines before the first prompt:
     *
     * ```
     * groups: cannot find name for group ID 3003
     * … 20531 / 50531 / 99909997 / 9997
     * root@localhost:/workspace#
     * ```
     *
     * The printer is **`/etc/bash.bashrc`**, not our code and not proot: Ubuntu's
     * interactive-shell rc runs `case " $(groups) " in *\ admin\ *|*\ sudo\ *)` (the
     * default `bash -i` sources it) to decide whether to print its sudo hint, and that
     * block is guarded only by `$HOME/.sudo_as_admin_successful` and
     * `$HOME/.hushlogin` — neither exists in this rootfs. coreutils' `groups` writes one
     * `cannot find name for group ID N` line to stderr for every gid it cannot resolve
     * through `/etc/group`. It is **not** a proot message: proot fakes uid/gid 0 (`-0`)
     * but does not touch the supplementary list, so the guest inherits the Android app's
     * groups — `inet` (3003), `everybody` (9997) and the per-install dynamic ids Android
     * assigned this install — while the payload's `/etc/group` carries only a stock
     * Ubuntu set (38 lines, none above gid 1000). Filtering the output would have hidden
     * it from the one place that can still show it, and `id`, `groups` and `ls -l` would
     * keep printing the same thing whenever the user ran them by hand.
     *
     * ## Why this is the fix, and what it costs
     *
     * The missing data is a *name* for a numeric id, so the fix writes the name — what a
     * desktop's `groupadd -g <id> <name>` does. Properties, each deliberate:
     *
     *  - **Deterministic**: the ids come from `/proc/self/status`'s `Groups:` line of the
     *    app process, i.e. exactly the numbers the guest sees. No time, no device state,
     *    nothing invented.
     *  - **Append-only and idempotent**: a gid that already has a line is left alone,
     *    name included, so an existing group never changes meaning; when nothing is
     *    missing there is **no write at all**, so repeated boots cannot drift the file.
     *  - **Verified**: the file is re-read and every gid looked up again, because
     *    `appendText` can "succeed" on a full filesystem. An unverified repair is the
     *    failure this is meant to remove.
     *  - **Names are namespaced** (`android-inet`, `android-gid-20531`) so they cannot
     *    collide with a real Ubuntu group — and, the one that matters, none of them is
     *    `sudo` or `admin`, which would flip the `bash.bashrc` branch above and print
     *    Ubuntu's sudo hint into the terminal instead of the errors.
     *
     * **What it affects:** the *names* `groups` / `id` / `ls -l` print for those gids
     * inside the guest, and nothing else. **What it does not:** permissions. proot and
     * Android enforce the numeric uid/gid, so a name grants no access, changes no file's
     * owner, and is invisible outside the guest. It touches no other `/etc` file and not
     * proot's `-0` fiction.
     *
     * Called from both ends of [ensureReady]: after a fresh unpack (the file is new) and
     * on the early-return path, because a device that already unpacked keeps its rootfs
     * and would otherwise need a runtime revision bump to lose the five lines.
     *
     * @return null when nothing needed doing (or the repair verified), otherwise a
     *   one-line warning, which the caller shows rather than swallows.
     */
    private fun ensureAndroidGroups(): String? {
        val file = File(paths.rootfs, "etc/group")
        if (!file.isFile) return null
        val wanted = androidGroupIds()
        if (wanted.isEmpty()) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val present = groupIdsIn(text)
        val missing = wanted.filter { it !in present }
        if (missing.isEmpty()) return null
        val appended = buildString {
            if (text.isNotEmpty() && !text.endsWith("\n")) append('\n')
            missing.forEach { gid -> append(androidGroupName(gid)).append(":x:").append(gid).append(":\n") }
        }
        runCatching { file.appendText(appended) }
        val after = runCatching { file.readText() }.getOrNull().orEmpty()
        val stillMissing = missing.filter { it !in groupIdsIn(after) }
        return if (stillMissing.isEmpty()) {
            null
        } else {
            "guest 的 /etc/group 没能补全（缺 ${stillMissing.joinToString()}）：开终端可能仍看到 groups 报错"
        }
    }

    /** The third field of every `/etc/group` line — the numeric gid. */
    private fun groupIdsIn(text: String): Set<Int> = text.lineSequence()
        .mapNotNull { line -> line.split(':').getOrNull(2)?.trim()?.toIntOrNull() }
        .toSet()

    /**
     * This process's supplementary group ids, from `/proc/self/status`.
     *
     * `/proc` rather than `android.system.Os.getgroups()` or a shell `id -G`: the same
     * kernel data, no permission needed, and it is what the guest inherits through
     * proot. An unreadable file answers "none", which leaves the group database
     * untouched rather than guessing.
     */
    private fun androidGroupIds(): List<Int> {
        val status = runCatching { File("/proc/self/status").readText() }.getOrNull() ?: return emptyList()
        val line = status.lineSequence().firstOrNull { it.startsWith("Groups:") } ?: return emptyList()
        return line.removePrefix("Groups:").trim().split(' ', '\t')
            .mapNotNull { it.toIntOrNull() }
            .distinct()
    }

    /**
     * The name a repaired gid gets: `android-<aid>` for the Android AIDs this app can
     * plausibly hold, `android-gid-<n>` otherwise.
     *
     * The `android-` prefix is load-bearing twice: it makes the origin of the line
     * obvious to anyone reading `/etc/group` in the guest, and it keeps the name from
     * ever being `sudo`/`admin` — see [ensureAndroidGroups].
     */
    private fun androidGroupName(gid: Int): String {
        val aid = ANDROID_AID_GROUPS[gid]
        return if (aid == null) "android-gid-$gid" else "android-$aid"
    }

    private fun extractEngine() {
        // Optional: the engine may be installed on first run instead of shipped.
        val target = File(paths.rootfs, "opt/pi")
        if (!assetExists("runtime/$ENGINE_ARCHIVE")) {
            target.mkdirs()
            return
        }
        extractAsset(ENGINE_ARCHIVE, target)
        // Convenience launcher: `pi` on PATH inside the guest.
        //
        // Same preference order as the engine launch in `PiEngineHost`, for the same
        // reason: the packed entry starts in about a second where the unpacked one
        // takes tens of seconds, and a payload that lost its bundle must only get
        // slower, not stop working. The wrapper is written here, at unpack time, so
        // it can look at what actually arrived.
        //
        // The path written into the script is the **guest** spelling, not the host
        // File's. The whole point of proot is that a guest path is translated on the
        // way in — so a script naming `/data/user/0/<pkg>/files/pi/runtime/rootfs/…`
        // asks the guest for a path that does not exist there (`<rootfs>/data/…`),
        // and `pi` on PATH dies with "Cannot find module". This used to interpolate
        // `cli.path`, which is exactly that host path.
        val guestEngineRoot = "/opt/pi/node_modules/@earendil-works/pi-coding-agent"
        val hostEngineRoot = File(target, "node_modules/@earendil-works/pi-coding-agent")
        val guestCli = if (File(hostEngineRoot, "dist/bundle/cli.js").isFile) {
            "$guestEngineRoot/dist/bundle/cli.js"
        } else {
            "$guestEngineRoot/dist/cli.js"
        }
        if (File(hostEngineRoot, "dist").isDirectory) {
            val bin = File(paths.rootfs, "opt/pi/bin").also { it.mkdirs() }
            val wrapper = File(bin, "pi")
            wrapper.writeText(
                "#!/bin/bash\nexec /opt/node/bin/node $guestCli \"\$@\"\n",
            )
            wrapper.setExecutable(true, false)
            guestSymlink("/usr/local/bin/pi", "/opt/pi/bin/pi")
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Create a symlink **inside the rootfs**, pointing at a **guest** path.
     *
     * The distinction is not cosmetic. proot translates paths on the way in, so a
     * link whose target is a host path (`/data/user/0/...`) is meaningless to the
     * guest: nothing resolves it, and the failure looks like a missing binary.
     * Only symlinks that Android itself follows — the `libtalloc.so.2` alias in
     * [PiPaths.lib], which lives outside the rootfs — may name host paths.
     */
    private fun guestSymlink(guestLink: String, guestTarget: String) {
        val link = File(paths.rootfs, guestLink.removePrefix("/"))
        link.parentFile?.mkdirs()
        if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
        runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Path.of(guestTarget)) }
    }

    private fun assetExists(name: String): Boolean = runCatching {
        assets.open(name).close()
        true
    }.getOrDefault(false)

    private fun stampFile() = paths.stampFile()

    private fun isStampCurrent(revision: String): Boolean {
        val stamp = stampFile()
        return stamp.isFile && stamp.readText().trim() == revision
    }

    /**
     * Write the stamp that says this revision is unpacked. Returns null on success, or
     * the sentence to show when it could not be written.
     *
     * Atomic rather than `writeText` (see [writeStampAtomically]): this file is the
     * only thing that says the runtime is unpacked and it is read back by an equality
     * test, so a kill between the truncate and the write used to leave a short stamp
     * that reads as "not unpacked" — the next launch unpacks the whole runtime again,
     * and on a revision change `wipe()`s the guest tree first.
     *
     * A failure to write it is **reported, not fatal**. The runtime on disk is
     * complete and usable; refusing to boot because the *bookkeeping* failed would
     * turn "storage is full" into "the app will not start", and the honest cost of
     * continuing is that the next launch repeats this work (and says so again). The
     * caller puts the sentence in the last step's label, so it is on screen during
     * the boot that failed to record itself, and the diagnostic report shows the
     * stamp as empty on the next one.
     */
    private fun writeStamp(revision: String): String? =
        if (writeStampAtomically(stampFile(), revision + "\n")) {
            null
        } else {
            "运行时版本戳记写入失败（${stampFile().absolutePath}）：本次解包可用，" +
                "但下次启动会重新解包。请检查存储空间。"
        }

    companion object {
        /**
         * The fallback revision, used only when [packagedRevision] cannot read the
         * assembler's digest.
         *
         * This used to be the whole mechanism, and the comment above it said so: the
         * number was bumped **by hand** and nothing verified it. That arrangement
         * fails in the direction that costs the most. Change a payload and forget to
         * bump it, and every device keeps the tree it already unpacked — the APK
         * installs, the app looks fine, and it is running the previous Node and the
         * previous pi. The build produced nothing, and nothing on the device
         * disagrees with anything else, so nobody finds out. The reverse mistake
         * (bumping it when nothing changed) deletes everything the user installed
         * inside the guest for no reason at all.
         *
         * So the number is derived now: [packagedRevision] reads a digest of the
         * payload bytes that `tools/fetch-runtime.mjs` writes at build time, and a
         * device that unpacked different bytes re-unpacks on its own. Nothing here
         * needs a human to remember anything. Editing this constant therefore has no
         * effect on a real build — which is the point — and it exists for the one
         * build with no assembler behind it (a bare `:app:assembleRelease` over a
         * checkout where `fetch-runtime.mjs` never ran), where the payloads are
         * absent too and provisioning fails on the payload audit before this value is
         * ever compared against a stamp.
         *
         * ## What a revision change costs now
         *
         * Nothing is deleted. The revision only decides whether the fixed cost of a cold
         * start applies: while it matches `.stamp` the boot is exactly what it always
         * was (one 16-character asset read and one string comparison). When it moves,
         * the per-payload digests are read once, only the payloads whose bytes changed
         * are extracted over the tree, and only the paths the previous version of a
         * changed payload owned are pruned. A revision that moves because a proroot
         * `.so` changed extracts nothing at all. The explicit repair path —
         * `ensureReady(rebuild = true)` — is the only thing that still deletes the guest
         * environment, and it exists for a tree that is genuinely broken. Session
         * history, credentials, settings, extensions, workspaces and the guest's own
         * `npm -g`/apt installs are all outside that path's reach unless the user asks.
         */
        const val RUNTIME_REVISION = "2"

        /**
         * Where [packagedRevision] reads the digest from.
         *
         * Beside `assets/runtime/`, not inside it: CI asserts that every entry under
         * that directory is one of the payloads, byte for byte, and a manifest is not
         * a payload. `tools/fetch-runtime.mjs` writes this path; the APK step in
         * `.github/workflows/ci.yml` asserts it survived packaging with a 16-character
         * value, because a rename on one side only would leave every device on the
         * fallback and silently restore the bug this replaced.
         */
        const val REVISION_ASSET = "runtime-revision.txt"

        /** The digest length `tools/fetch-runtime.mjs` writes, in hex characters. */
        private const val REVISION_LENGTH = 16

        /**
         * Where the per-payload metadata lives inside the APK:
         * `assets/runtime-payloads/<name>.digest` and `<name>.list`.
         *
         * A sibling of `assets/runtime/`, not a child: CI asserts that every entry under
         * `assets/runtime/` is one of the payloads byte for byte, and these are text
         * describing the payloads, not payloads. `tools/fetch-runtime.mjs` writes both
         * files; the app reads a payload's pair only after the stamp comparison says
         * this boot has something to decide.
         *
         * [DIGEST_LENGTH] is the same truncation `runtime-revision.txt` has always used:
         * 16 hex characters of a SHA-256, which is what the whole change-detection scheme
         * has rested on since the revision became derived. It detects a changed payload;
         * it is not a signature.
         */
        private const val PAYLOAD_META_DIR = "runtime-payloads"

        /** One payload digest's length in hex characters; see [PAYLOAD_META_DIR]. */
        private const val DIGEST_LENGTH = 16

        // ------------------------------------------------------- boot step labels
        // Named constants rather than literals at the call sites: the same three
        // sentences are asserted by the boot screen's own text and by nothing else, and
        // a step list is the one place a user reads what an update actually did.

        /** Nothing to do: the whole answer of a cold start that changed nothing. */
        private const val CURRENT_STEP = "运行时已是最新，无需重解"

        /** Pre-flight: what this APK actually carries. */
        private const val AUDIT_STEP = "校验内置载荷"

        /** The transition every existing install goes through once. */
        private const val MIGRATION_STEP = "迁移到按载荷更新（只覆盖，不删除）"

        /** The explicit repair path, which is the only one that may delete the tree. */
        private const val REBUILD_STEP = "重建运行时（清空易失树）"

        /** Free-space pre-flight, and the creation of `runtime/`, `tmp/` and `lib/`. */
        private const val PREPARE_STEP = "准备存储"

        /** DNS, the guest home and `/etc/group`. */
        private const val CONFIGURE_STEP = "配置 DNS 与目录"

        /** The last step, replaced by a warning on screen when one has to be shown. */
        private const val FINAL_STEP = "完成"

        /**
         * The revision the packaged payloads carry — a digest of their bytes, or
         * [RUNTIME_REVISION] when the asset is not there.
         *
         * Read from the APK rather than compiled in so it cannot go stale. The read is
         * a few dozen bytes, once per boot and once per restart, which is why it is
         * done here instead of being cached in a field: there is nothing to gain and a
         * stale cache is one more way to miss a payload change.
         */
        fun packagedRevision(assets: AssetManager): String =
            runCatching {
                assets.open(REVISION_ASSET, AssetManager.ACCESS_BUFFER).use {
                    it.readBytes().decodeToString().trim()
                }
            }.getOrNull()
                ?.takeIf { value ->
                    value.length == REVISION_LENGTH && value.all { it.isDigit() || it in 'a'..'f' }
                }
                ?: RUNTIME_REVISION

        /**
         * The suffix every payload archive in `assets/runtime/` carries.
         *
         * **It must not end in `.gz`.** The Android Gradle Plugin gunzips an asset
         * whose *file extension is* `gz` while it merges assets —
         * `com.android.ide.common.resources.AssetItem` decides it with
         * `Files.getFileExtension(name).toLowerCase(Locale.US).equals("gz")` and then
         * renames with `Files.getNameWithoutExtension`, which removes only the final
         * `.gz`. So the assembler writing `ubuntu-base.tar.gz` (28.5 MiB) put
         * `ubuntu-base.tar` (106 MB) in the APK, this class still asked for
         * `runtime/ubuntu-base.tar.gz`, and `AssetManager.open()` answered with its
         * bare-path `FileNotFoundException` — the runtime never provisioned once, on
         * any build, and the device's own report said so:
         *
         *   packaged asset unreadable: runtime/ubuntu-base.tar.gz
         *   assets/runtime/ contains: fd.tar, node.tar, pi-engine.tar, ripgrep.tar, ubuntu-base.tar
         *
         * It is **not** AAPT2: aapt2 2.20-14304508, run directly against a directory
         * laid out like this one, ships `ubuntu-base.tar.gz` and `pi-engine.tgz`
         * under their own names. The rename is one stage earlier and therefore
         * outlives any change to the aapt2 command line. `.tgz` is the same gzip
         * bytes under an extension AGP leaves alone
         * (`Files.getFileExtension("ubuntu-base.tgz")` is `tgz`).
         *
         * The matching half of the fix is `androidResources { noCompress }` in
         * app/build.gradle.kts, which must name this suffix — and the full account,
         * including the commands that established it, is on `PAYLOAD_SUFFIX` in
         * tools/fetch-runtime.mjs.
         *
         * Nothing is recompressed by this: [TarExtractor.extractGzip] reads all six
         * payloads exactly as before.
         */
        private const val PAYLOAD_SUFFIX = ".tgz"

        private const val UBUNTU_BASE = "ubuntu-base$PAYLOAD_SUFFIX"
        private const val NODE_ARCHIVE = "node$PAYLOAD_SUFFIX"
        private const val ENGINE_ARCHIVE = "pi-engine$PAYLOAD_SUFFIX"
        private const val RIPGREP_ARCHIVE = "ripgrep$PAYLOAD_SUFFIX"
        private const val FD_ARCHIVE = "fd$PAYLOAD_SUFFIX"
        private const val GIT_ARCHIVE = "git$PAYLOAD_SUFFIX"

        /**
         * The one guest directory a launcher that does **not** bind the agent dir
         * resolves `/root/.pi/agent` through — see [PiPaths.agentBinDir].
         */
        private const val GUEST_AGENT_BIN = "/root/.pi/agent/bin"

        /** The guest directory `ProotCommand.environment` puts on PATH (PiRuntime.kt). */
        private const val GUEST_LOCAL_BIN = "/usr/local/bin"

        /**
         * The tools [publishTool] keeps visible, named where both the install step and
         * the repair step ([ensureToolsVisible]) read the same list, so a third tool
         * cannot be added to one and forgotten by the other.
         *
         * git is deliberately **not** here. It needs a whole `/usr/lib/git-core` tree
         * and its libraries, and none of that belongs under `/root/.pi/agent`: it goes
         * to the ordinary Debian locations in the rootfs (`/usr/bin/git`,
         * `/usr/lib/git-core`), which are on PATH and are not shadowed by any bind — so
         * it is visible to all three launch paths with no agent-dir involvement at all.
         * See [installGit].
         */
        private val TOOL_BINARIES = listOf("rg", "fd")

        /**
         * Android's own names for the group ids an app can hold, used by
         * [androidGroupName] when it writes a missing gid into the guest's
         * `/etc/group`.
         *
         * Only ids an app process can legitimately appear in — the ones Android grants
         * through the manifest and the storage/permission model: `inet`/`net_raw` and
         * the bandwidth counters (a network-using app), `everybody` (present in every
         * app's group list), the storage ids (an app with `READ/WRITE_EXTERNAL_STORAGE`
         * on older releases), and `log`/`shell`/`cache`/`graphics`/`input`/`audio` for
         * the media and debug cases. Anything else — `20531`, `50531`, `99909997` in the
         * report are Android's dynamic per-install ids — gets a neutral
         * `android-gid-<n>`: naming them after an AID they are not would be a guess
         * written into a system file, and the numbers are what matter.
         *
         * The *names* carry the `android-` prefix at the call site, so this table can
         * never produce `sudo`/`admin` (`ensureAndroidGroups` says why that matters).
         */
        private val ANDROID_AID_GROUPS: Map<Int, String> = mapOf(
            1000 to "system",
            1001 to "radio",
            1002 to "bluetooth",
            1003 to "graphics",
            1004 to "input",
            1005 to "audio",
            1006 to "camera",
            1007 to "log",
            1008 to "compass",
            1009 to "mount",
            1010 to "wifi",
            1011 to "adb",
            1012 to "install",
            1013 to "media",
            1015 to "sdcard_rw",
            1023 to "media_rw",
            1028 to "sdcard_r",
            2000 to "shell",
            2001 to "cache",
            2002 to "diag",
            3001 to "net_bt_admin",
            3002 to "net_bt",
            3003 to "inet",
            3004 to "net_raw",
            3005 to "net_admin",
            3006 to "net_bw_stats",
            3007 to "net_bw_acct",
            9997 to "everybody",
        )

        /**
         * Every payload archive `tools/fetch-runtime.mjs` writes into
         * `app/src/main/assets/runtime/`, in the order the steps consume them.
         *
         * `required = false` only for the engine, mirroring [extractEngine]: the
         * audit must report it, but a package without it is still a package this
         * class can provision from, so it is not a reason to refuse to start.
         *
         * The list is the single place the six names are written down; the
         * companion constants above are what the extracting steps themselves use.
         */
        private val PAYLOADS = listOf(
            Payload(UBUNTU_BASE, required = true),
            Payload(NODE_ARCHIVE, required = true),
            Payload(RIPGREP_ARCHIVE, required = true),
            Payload(FD_ARCHIVE, required = true),
            Payload(GIT_ARCHIVE, required = true),
            Payload(ENGINE_ARCHIVE, required = false),
        )

        /**
         * Why the payloads exist and what their absence means. Appended to every
         * payload failure so the on-screen message carries the explanation with
         * it, instead of requiring a lookup in this file.
         *
         * [PAYLOADS] is the load-bearing list, and the suffix list in
         * app/build.gradle.kts (`tgz`, plus `xz`/`tar` for a future repack) is what
         * keeps all six openable: a compressed asset is the one
         * `AssetManager.openFd` cannot open. That list must name the suffix the
         * assets actually use — see [PAYLOAD_SUFFIX] for what happens otherwise.
         */
        private const val PAYLOAD_HINT =
            "The payload archives are generated at build time by tools/fetch-runtime.mjs " +
                "(app/src/main/assets/runtime/ is not in git); an APK built without that step has " +
                "assets/dexopt and nothing else."
    }
}
