#!/usr/bin/env node
/**
 * Static & architectural guard for Pi Android cold-startup speed invariants.
 *
 * Prevents regressions in the startup chain that previously added 1.5-3s of
 * proot cold-start latency:
 *  1. controller.mjs: must NOT unconditionally call ensureHttpSettings() on boot
 *     (which eagerly imports dist/core/http-dispatcher.js + undici); must guard
 *     with hasCustomHttp (httpProxy / httpIdleTimeoutMs).
 *  2. controller.mjs: session.open response must include embedded initial `state`
 *     (`snapshotOf(this)`) so the app does not need an extra round-trip on boot.
 *  3. PiEngineHost.kt: installScandirFix must preserve `.scandir-fix.stamp` fast-path.
 *  4. DeviceBridgeController.kt: installExtensionAssets must preserve `.pi-android-apk-stamp`
 *     fast-path to avoid recursive asset reads + 20+ file SHA-256 hashes on every boot.
 *  5. PiSessionViewModel.kt: startup attach() must NOT unconditionally call refreshStats()
 *     (only fallback when `_state.value.stats == null`), and must stagger non-critical
 *     `refreshTrustPrompt()` / background `refreshSessions()` after `replayHistory()`.
 */
import { readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repo = resolve(fileURLToPath(new URL("..", import.meta.url)));
const failures = [];

function check(name, ok, detail) {
  if (!ok) failures.push(`${name}: ${detail}`);
}

// 1 & 2: controller.mjs
const controllerPath = join(repo, "app/src/main/assets/guest/sdk-host/controller.mjs");
const controller = readFileSync(controllerPath, "utf8");

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
const hostPath = join(repo, "app/src/main/kotlin/app/pi/engine/PiEngineHost.kt");
const hostKt = readFileSync(hostPath, "utf8");
check(
  "PiEngineHost.kt .scandir-fix.stamp fast-path",
  hostKt.includes(".scandir-fix.stamp") && hostKt.includes("apkStamp()"),
  "installScandirFix must preserve .scandir-fix.stamp fast-path",
);

// 4: DeviceBridgeController.kt
const bridgePath = join(repo, "app/src/main/kotlin/app/pi/bridge/DeviceBridgeController.kt");
const bridgeKt = readFileSync(bridgePath, "utf8");
check(
  "DeviceBridgeController.kt .pi-android-apk-stamp fast-path",
  bridgeKt.includes(".pi-android-apk-stamp"),
  "installExtensionAssets must preserve .pi-android-apk-stamp fast-path",
);

// 5: PiSessionViewModel.kt
const vmPath = join(repo, "app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt");
const vmKt = readFileSync(vmPath, "utf8");
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

if (failures.length > 0) {
  console.error("startup-perf check FAILED:");
  for (const f of failures) console.error("  - " + f);
  process.exit(1);
}

console.log("startup-perf: OK (all 6 cold-startup invariants verified)");
