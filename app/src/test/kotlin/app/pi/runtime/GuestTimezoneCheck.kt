package app.pi.runtime

// A bare-JVM harness for the guest's clock: the `TZ` value `GuestRecipe.timezoneValue` decides,
// the two shapes it is built from, and the entry in the environment map that the two runtimes
// actually hand the guest.
//
// ## Why a harness and not a device
//
// The defect this pins **already shipped and is silent**:
//
//  - `18817a7` set `TZDIR=/system/usr/share/zoneinfo` with `TZ=<device zone id>`. Android's
//    directory of that name holds one packed `tzdata` and **no per-zone file** (measured on the
//    reference device: `tzdata` 429854 B + `tz_version`, no `Asia/`, no `Asia/Shanghai`), so
//    glibc's `getenv("TZDIR") + "/" + name` open failed, `tzset` fell through to the POSIX
//    parser, and `Asia/Shanghai` became "`Asia`, UTC+0". Nothing logged anything: `date` printed
//    a plausible time with a plausible-looking abbreviation. On a UTC+8 phone every C clock in
//    the guest (`date`, `ls -l`, `git log`, `python3`) was eight hours off while Node — pi
//    itself — was right, which reads as "the agent cannot tell the time".
//  - **One guest, two parsers, opposite answers.** glibc reads a *file* (`$TZDIR/<id>`, or the
//    value as given when it starts with `/`); Node's ICU string-matches the value against its
//    own database and never opens anything. So `TZ=:/usr/share/zoneinfo/Asia/Shanghai` fixes
//    `date` and sends pi back to UTC (measured on node v24.19.0: `GMT+0000`,
//    `Intl…resolvedOptions().timeZone === undefined`), and a naked short name with no tzfile
//    does the opposite. Only a harness can hold both sides at once; a device shows one of them.
//  - `TimeZone.getDefault().id` is **device-supplied input on its way into a file path**, and
//    this device already returns ids that must not be joined (`GMT+08:00`).
//
// The parse of the offset strings is not re-implemented here: every case below calls the
// production function, and the POSIX shapes it must avoid (`<08>-8`, `<+05:30>-5:30`) are
// expressed as a rule over its output instead of as a second copy of the sign arithmetic.
//
// Run it by hand:
//
//   tools/run-app-pure-checks.sh

import java.io.File
import java.util.TimeZone

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
    // ------------------------------------------------ the branch table: id -> TZ
    // `(id, does <rootfs>/usr/share/zoneinfo/<id> exist, offset now) -> TZ`.
    check(
        "有时区文件：短名（glibc 与 ICU 都认，DST 精确）",
        GuestRecipe.timezoneValue("Asia/Shanghai", true, 28800),
        "Asia/Shanghai",
    )
    check(
        "没时区文件：退纯偏移，不退短名",
        GuestRecipe.timezoneValue("Asia/Shanghai", false, 28800),
        "<+08>-8",
    )
    check(
        "半小时时区，有时区文件",
        GuestRecipe.timezoneValue("Asia/Kolkata", true, 19800),
        "Asia/Kolkata",
    )
    check(
        "半小时时区，没文件",
        GuestRecipe.timezoneValue("Asia/Kolkata", false, 19800),
        "<+0530>-5:30",
    )
    check(
        "西半球（夏令时 UTC-4），没文件",
        GuestRecipe.timezoneValue("America/New_York", false, -14400),
        "<-04>+4",
    )
    check(
        "三级路径的 IANA id 合法",
        GuestRecipe.timezoneValue("America/Argentina/Buenos_Aires", true, -10800),
        "America/Argentina/Buenos_Aires",
    )
    check(
        "手改偏移的 ROM 给的 id 没有文件可开（GMT+08:00）→ 偏移",
        GuestRecipe.timezoneValue("GMT+08:00", true, 28800),
        "<+08>-8",
    )
    check(
        "时间穿越的 id（../etc/passwd）不许拼进路径",
        GuestRecipe.timezoneValue("../etc/passwd", true, 28800),
        "<+08>-8",
    )
    check(
        "结尾带斜杠的 id 不许拼进路径",
        GuestRecipe.timezoneValue("Asia/Shanghai/", true, 28800),
        "<+08>-8",
    )
    check(
        "空 id 合法地走偏移",
        GuestRecipe.timezoneValue("", true, 28800),
        "<+08>-8",
    )
    check(
        "null（读取失败）走偏移",
        GuestRecipe.timezoneValue(null, true, 28800),
        "<+08>-8",
    )
    check(
        "Etc/GMT-8 是合法 id（IANA 的符号是反的，但名字合法）",
        GuestRecipe.timezoneValue("Etc/GMT-8", true, 28800),
        "Etc/GMT-8",
    )
    check(
        "设备在 UTC 且有时区文件 → 短名",
        GuestRecipe.timezoneValue("UTC", true, 0),
        "UTC",
    )
    check(
        "设备在 UTC 且没文件 → 也退化成 UTC（但不是靠短名）",
        GuestRecipe.timezoneValue("UTC", false, 0),
        "UTC",
    )

    // --------------------------------------------------------------- isSafeZoneId
    for (id in listOf("Asia/Shanghai", "UTC", "Etc/GMT-8", "America/Argentina/Buenos_Aires")) {
        check("合法 id：$id", GuestRecipe.isSafeZoneId(id), true)
    }
    for (id in listOf(
        "",
        "/Asia/Shanghai",
        "Asia/Shanghai/",
        "Asia//Shanghai",
        "Asia/Shanghai/../../../etc/passwd",
        "../etc/passwd",
        "a\\b",
        "GMT+08:00",
        "Asia Shanghai",
        "Asia/Shanghai\u0000x",
        "Asia/Shanghai;rm -rf /",
    )) {
        check("非法 id：${id.replace("\u0000", "\\0")}", GuestRecipe.isSafeZoneId(id), false)
    }
    check("非法 id：null", GuestRecipe.isSafeZoneId(null), false)
    check("非法 id：超长（256）", GuestRecipe.isSafeZoneId("A".repeat(256)), false)
    check("合法 id：255", GuestRecipe.isSafeZoneId("A".repeat(255)), true)

    // ---------------------------------------------------------------- offsetSpec
    check("0 → UTC（不是 <+00>-0）", GuestRecipe.offsetSpec(0), "UTC")
    check("+08:00", GuestRecipe.offsetSpec(28800), "<+08>-8")
    check("+05:30", GuestRecipe.offsetSpec(19800), "<+0530>-5:30")
    check("+05:45", GuestRecipe.offsetSpec(20700), "<+0545>-5:45")
    check("+12:45", GuestRecipe.offsetSpec(45900), "<+1245>-12:45")
    check("-04:00", GuestRecipe.offsetSpec(-14400), "<-04>+4")
    check("-05:00", GuestRecipe.offsetSpec(-18000), "<-05>+5")
    check("-03:30", GuestRecipe.offsetSpec(-12600), "<-0330>+3:30")
    check("带秒的历史偏移 -00:44:30", GuestRecipe.offsetSpec(-2670), "<-004430>+0:44:30")
    check("带秒的历史偏移 +08:05:43", GuestRecipe.offsetSpec(29143), "<+080543>-8:05:43")
    // Integer.MIN_VALUE must not wrap into a positive magnitude (abs() on Int overflows).
    // No device produces an offset remotely like it; the check is that the sign arithmetic
    // cannot be flipped by the one input that used to break `abs()`.
    check(
        "Int.MIN_VALUE 不翻转符号",
        GuestRecipe.offsetSpec(Int.MIN_VALUE).startsWith("<-"),
        true,
    )

    // The two shapes that *look* right and are silently parsed as offset 0 by both parsers.
    val specs = listOf(
        -45900, -20700, -19800, -18000, -14400, -12600, -2670, 0, 2670,
        19800, 20700, 28800, 29143, 45900, 50400,
    ).map { GuestRecipe.offsetSpec(it) }
    check(
        "名字一律带符号（<08>-8 会被解析成偏移 0）",
        specs.filter { it != "UTC" }.all { Regex("^<[+-][0-9]{2,6}>").containsMatchIn(it) },
        true,
    )
    check(
        "名字里不出现冒号（<+05:30>-5:30 会被解析成偏移 0）",
        specs.all { spec -> spec.substringBefore('>').none { it == ':' } },
        true,
    )
    check(
        "永不返回绝对路径（Node 的 ICU 认不出 :/ 与 /x 形式）",
        specs.none { it.startsWith("/") },
        true,
    )
    check(
        "除了 0，都带 POSIX 偏移（本地名 + 符号 + 数字）",
        specs.filter { it != "UTC" }.all { Regex("^<[+-][0-9]{2,6}>[+-][0-9]{1,3}(:[0-9]{2}){0,2}$").matches(it) },
        true,
    )

    // ------------------------------------- the environment map the guest really gets
    // The shipped defect lived in these two entries, so the assertions below are on the map.
    // `TZDIR` must name the guest's own tree — never Android's, and never a path Node's ICU
    // cannot parse.
    val defaultZone = TimeZone.getDefault()
    val root = File(System.getProperty("java.io.tmpdir"), "pi-guest-tz-check")
    root.deleteRecursively()
    try {
        val paths = PiPaths(filesDir = root, nativeLibDir = File(root, "native"))
        // DST-free on purpose: a value the harness asserts must not depend on the day it runs.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))

        val withoutTzdata = GuestRecipe.environment(paths)
        check("环境里有 TZ", withoutTzdata.containsKey("TZ"), true)
        check(
            "TZDIR 是客机自己的 zoneinfo 树",
            withoutTzdata["TZDIR"],
            "/usr/share/zoneinfo",
        )
        check(
            "TZDIR 绝不指向 /system（18817a7 的缺陷）",
            withoutTzdata["TZDIR"]!!.startsWith("/system"),
            false,
        )
        check(
            "没有 tzdata：TZ 是纯偏移",
            withoutTzdata["TZ"],
            "<+08>-8",
        )
        check(
            "TZ 与纯函数同源（这条链只有一份实现）",
            withoutTzdata["TZ"],
            GuestRecipe.timezoneValue("Asia/Shanghai", false, 28800),
        )
        check(
            "TZ 不带冒号（冒号让 ICU 回 UTC）",
            withoutTzdata["TZ"]!!.contains(':'),
            false,
        )

        val zoneFile = File(paths.rootfs, "usr/share/zoneinfo/Asia/Shanghai")
        zoneFile.parentFile.mkdirs()
        zoneFile.writeText("TZif")
        val withTzdata = GuestRecipe.environment(paths)
        check(
            "有 tzdata：TZ 用设备的短名",
            withTzdata["TZ"],
            "Asia/Shanghai",
        )
        check(
            "…且 TZDIR 不变",
            withTzdata["TZDIR"],
            "/usr/share/zoneinfo",
        )

        // A second device zone, so the value cannot be a Shanghai literal: half-hour offset,
        // no DST, and a tzfile that is *not* there.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        val kolkata = GuestRecipe.environment(paths)
        check("换设备时区：没有对应 tzfile 时走偏移", kolkata["TZ"], "<+0530>-5:30")
        File(paths.rootfs, "usr/share/zoneinfo/Asia/Kolkata").writeText("TZif")
        check(
            "换设备时区：有 tzfile 时走短名",
            GuestRecipe.environment(paths)["TZ"],
            "Asia/Kolkata",
        )

        // A device whose id has no file *anywhere* (the manual-offset ROM case) must never be
        // joined onto TZDIR — not even to produce a path that is then missing.
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+08:00"))
        check(
            "手改偏移的设备 id：TZ 是偏移，TZDIR 不动",
            GuestRecipe.environment(paths).let { it["TZ"] to it["TZDIR"] },
            "<+08>-8" to "/usr/share/zoneinfo",
        )
    } finally {
        TimeZone.setDefault(defaultZone)
        root.deleteRecursively()
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
