package app.pi.runtime

// A bare-JVM harness for the unpack's free-space budget
// (`runtime/RuntimeSpaceBudget.kt`), compiled and run by
// `tools/run-app-pure-checks.sh`.
//
// Why a harness and not a device: the defect this pins is a *destructive* one, and
// it is silent. `RuntimeProvisioner.ensureReady` wiped the runtime tree and then
// extracted into it; when the disk was full the user lost the runtime that worked,
// the new one was half written, and the revision stamp was never written - so every
// later launch repeated the wipe. Nothing in the build could notice, because the
// failing path only exists on a device that is out of space.
//
// What is pinned here is only the arithmetic and the wording: when the check must
// refuse, when it must not, and that the sentence carries the numbers the user
// needs. Reading `usableSpace` and calling this from the provisioner are the
// caller's halves.
//
// Run it by hand:
//
//   tools/run-app-pure-checks.sh

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

fun main() {
    val mib = 1024L * 1024L

    // ------------------------------------------------------------ the requirement
    // The floor is what the three large pinned payloads unpack to (> 438 MB), so it
    // must not silently drop below it.
    check("A1 the floor is 480 MiB", RuntimeSpaceBudget.MIN_REQUIRED_BYTES, 480L * mib)
    check("A2 a small payload set still needs the floor", RuntimeSpaceBudget.requiredBytes(1L), 480L * mib)
    check("A3 an unreadable payload total still needs the floor", RuntimeSpaceBudget.requiredBytes(0L), 480L * mib)
    // A payload set that would unpack past the floor must move the requirement: this
    // is what makes a bigger pi-engine.tgz raise the bar instead of being ignored.
    check("A4 200 MiB of payloads asks for 800 MiB", RuntimeSpaceBudget.requiredBytes(200L * mib), 800L * mib)

    // ------------------------------------------------------------- when to refuse
    // The point of the whole object: "cannot read the number" is not "out of space".
    check("B1 an unreadable figure never refuses", RuntimeSpaceBudget.shortfall(0L, 100L * mib), null)
    check("B2 a negative figure never refuses", RuntimeSpaceBudget.shortfall(-1L, 100L * mib), null)
    check("B3 far too little is refused", RuntimeSpaceBudget.shortfall(100L * mib, 100L * mib), 380L * mib)
    check("B4 exactly enough is not refused", RuntimeSpaceBudget.shortfall(480L * mib, 100L * mib), null)
    check("B5 one byte short is refused", RuntimeSpaceBudget.shortfall(480L * mib - 1L, 100L * mib), 1L)
    check("B6 plenty is not refused", RuntimeSpaceBudget.shortfall(2750L * mib, 100L * mib), null)

    // ------------------------------------------------------------------- wording
    // One sentence, no paths, no class names, and the three numbers in it have to be
    // the real ones: how much is needed, how much there is, how much to free.
    val msg = RuntimeSpaceBudget.message(availableBytes = 210L * mib, payloadBytes = 100L * mib)
    check("C1 the requirement is in the sentence", msg.contains("480 MB"), true)
    check("C2 the free space is in the sentence", msg.contains("210 MB"), true)
    check("C3 the shortfall is in the sentence", msg.contains("270 MB"), true)
    check("C4 it says what to do", msg.contains("重试"), true)
    check("C5 it names no file path", msg.contains("/"), false)
    // Rounding up, so a positive remainder can never print as "0 MB".
    check("C6 megabytes round up", RuntimeSpaceBudget.megabytes(1L), 1L)
    check("C7 an exact megabyte is not rounded up", RuntimeSpaceBudget.megabytes(mib), 1L)
    check("C8 zero stays zero", RuntimeSpaceBudget.megabytes(0L), 0L)

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
