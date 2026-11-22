#!/usr/bin/env node
/**
 * Static & architectural guard for Pi Android cold-startup speed invariants.
 *
 * Prevents regressions in the startup chain from tapping the App icon to AI ready:
 *  1. controller.mjs: lazy http-dispatcher (only when custom httpProxy / httpIdleTimeoutMs).
 *  2. controller.mjs: session.open embeds initial `state` (`snapshotOf(this)`).
 *  3. PiEngineHost.kt: `.scandir-fix.stamp` fast-path + parallel pre-spawn + `planDirect` (no `/bin/bash -c`).
 *  4. DeviceBridgeController.kt: `.pi-android-apk-stamp` fast-path + `usableCapabilities` snapshot in `device-bridge.json`.
 *  5. PiSessionViewModel.kt: zero-RTT `stateStore` attach, deduplicated `refreshStats()`, staggered background tasks, `bootInFlight` guard.
 *  6. MainActivity.kt: early `session.boot()` in `onCreate()` before `setContent {}`.
 *  7. sdk-host: `host.bundle.mjs` single-file bundle exists, `main.mjs` embeds hello in `host.ready` + preloads SDK + updates `PWD`, `PiEngineSession.kt` consumes `readyHello`.
 */
import { existsSync, readFileSync, statSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repo = resolve(fileURLToPath(new URL("..", import.meta.url)));
const failures = [];

function check(name, ok, detail) {
  if (!ok) failures.push(`${name}: ${detail}`);
}

// 1 & 2: controller.mjs
const controller = readFileSync(join(repo, "app/src/main/assets/guest/sdk-host/controller.mjs"), "utf8");
check(
  "controller.mjs lazy http-dispatcher",
  controller.includes("hasCustomHttp") &&
    controller.includes("if (hasCustomHttp)") &&
    !/this\.pushState\("extensions"\);\s*setImmediate\(\(\)\s*=>\s*void\s+this\.ensureHttpSettings\(\)\)/.test(controller),
  "ensureHttpSettings() must only run on startup when custom httpProxy or httpIdleTimeoutMs is configured",
);
check(
  "controller.mjs session.open embedded state",
  controller.includes("const initialState = snapshotOf(this)") &&
    /state:\s*initialState/.test(controller),
  "session.open response must embed initial state snapshot",
);

// 3: PiEngineHost.kt
const hostKt = readFileSync(join(repo, "app/src/main/kotlin/app/pi/engine/PiEngineHost.kt"), "utf8");
check(
  "PiEngineHost.kt .scandir-fix.stamp fast-path",
  hostKt.includes(".scandir-fix.stamp") && hostKt.includes("apkStamp()"),
  "installScandirFix must preserve .scandir-fix.stamp fast-path",
);
check(
  "PiEngineHost.kt direct node exec & parallel pre-spawn",
  hostKt.includes("selection.planDirect(") && hostKt.includes("async(Dispatchers.IO)"),
  "PiEngineHost must parallelize pre-spawn IO and use planDirect() without /bin/bash -c",
);

// 4: DeviceBridgeController.kt
const bridgeKt = readFileSync(join(repo, "app/src/main/kotlin/app/pi/bridge/DeviceBridgeController.kt"), "utf8");
check(
  "DeviceBridgeController.kt .pi-android-apk-stamp & usableCapabilities",
  bridgeKt.includes(".pi-android-apk-stamp") && bridgeKt.includes("usableCapabilities"),
  "DeviceBridgeController must keep .pi-android-apk-stamp fast-path and write usableCapabilities into device-bridge.json",
);

// 5: PiSessionViewModel.kt
const vmKt = readFileSync(join(repo, "app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt"), "utf8");
check(
  "PiSessionViewModel.kt deduplicated startup refreshStats",
  vmKt.includes("wasEngineState != PiEngineSession.EngineState.Busy &&\n                    _state.value.stats == null"),
  "attach() must only call refreshStats() when _state.value.stats == null",
);
check(
  "PiSessionViewModel.kt staggered startup background tasks",
  /delay\(400\)[\s\S]{0,120}refreshTrustPrompt\(\)[\s\S]{0,120}delay\(800\)[\s\S]{0,160}refreshSessions\(\)/.test(vmKt),
  "attach() must keep staggered delays before refreshTrustPrompt() and background refreshSessions()",
);
check(
  "PiSessionViewModel.kt zero-RTT stateStore attach & bootInFlight guard",
  vmKt.includes("val initialHostState = engine.commands.stateStore.state.value") &&
    vmKt.includes("private var bootInFlight: Boolean = false"),
  "attach() must reuse stateStore from session.open and boot() must guard concurrent calls with bootInFlight",
);

// 6: MainActivity.kt early boot
const mainActKt = readFileSync(join(repo, "app/src/main/kotlin/app/pi/MainActivity.kt"), "utf8");
const bootPos = mainActKt.indexOf("session.boot()");
const setContentPos = mainActKt.indexOf("setContent {");
check(
  "MainActivity.kt early boot before setContent",
  bootPos > 0 && setContentPos > 0 && bootPos < setContentPos,
  "MainActivity.onCreate() must call session.boot() before setContent {}",
);

// 7: sdk-host bundle, embedded host.ready hello, SDK preload, PWD sync & PiEngineSession readyHello
const bundlePath = join(repo, "app/src/main/assets/guest/sdk-host/host.bundle.mjs");
check(
  "sdk-host/host.bundle.mjs present",
  existsSync(bundlePath) && statSync(bundlePath).size > 50_000,
  "host.bundle.mjs must be built and checked into assets/guest/sdk-host/",
);
const mainMjs = readFileSync(join(repo, "app/src/main/assets/guest/sdk-host/main.mjs"), "utf8");
check(
  "sdk-host/main.mjs embedded hello, SDK preload & PWD sync",
  mainMjs.includes("...hostCmds.buildHelloPayload(reg)") &&
    mainMjs.includes("loadSdk(cfg.piDir)") &&
    mainMjs.includes("process.env.PWD = cfg.cwd"),
  "main.mjs must embed hello payload in host.ready, preload SDK via setImmediate, and keep process.env.PWD synced",
);
const sessionKt = readFileSync(join(repo, "app/src/main/kotlin/app/pi/engine/PiEngineSession.kt"), "utf8");
check(
  "PiEngineSession.kt consumes readyHello from host.ready",
  sessionKt.includes("private val readyHello") && sessionKt.includes("HostHello::fromJsonOrNull"),
  "PiEngineSession must consume embedded HostHello from host.ready to skip serial host.hello RTT",
);

if (failures.length > 0) {
  console.error("startup-perf check FAILED:");
  for (const f of failures) console.error("  - " + f);
  process.exit(1);
}
console.log("startup-perf: OK (all 12 cold-startup invariants verified)");
