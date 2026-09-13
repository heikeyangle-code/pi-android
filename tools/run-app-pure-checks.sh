#!/usr/bin/env bash
# Run the bare-JVM ("pure logic") harnesses under app/src/test/kotlin.
#
# Why this exists: until this script, NOTHING compiled or ran `app/src/test/**`.
# The `protocol` job runs `:rpc:test` only, `:app:assembleRelease` does not
# compile the unit-test source set at all, and `tools/typecheck.sh` compiles
# `app/src/main/kotlin` only. So the two harnesses were hand-run checks, and a
# green build said nothing about either — including
# `bridge/GuestPathMappingCheck.kt`, which pins the candidate *order* of the A3
# guest→host image-path mapping. That order is the one part of the image channel
# with a security argument behind it (see `GuestPathMapping`'s KDoc), so it is
# exactly the kind of thing that must not rot silently.
#
# Why kotlinc and not `:app:testReleaseUnitTest`: neither harness imports a test
# framework. Both are plain `fun main()` with a hand-rolled `check()` counter, and
# both end in `exitProcess(1)` when a check fails. JUnit would discover zero tests
# in them. Running them through Gradle would need a `JavaExec` task (or JUnit
# wiring plus a rewrite of both files) in `app/build.gradle.kts`, which this
# change does not own. So the closures are compiled and executed here instead,
# with the same kotlinc-from-Maven recipe `tools/typecheck.sh` documents in its
# header — minus android.jar, which is NOT needed: both closures are deliberately
# Android-free (kotlin stdlib, kotlinx.serialization and kotlinx.coroutines only,
# plus `java.net.*` from the JDK). If either harness ever starts referencing an
# Android class, this script fails to compile it, loudly, on purpose: that would
# mean the file is no longer pure logic and belongs in a real Android unit test.
#
# Each harness is compiled in ONE kotlinc invocation together with its main
# closure, so the harness sees those declarations as same-module. That matters:
# `GuestPathRoots` and `GuestPathMapping` are `internal`, and this reproduces what
# Gradle does for the unit-test source set via friend paths.
#
#   tools/run-app-pure-checks.sh
#
# Exit status is the verdict, and it is the CI gate:
#   0  both harnesses compiled and printed `harness: OK`
#   1  a compile error, a failed assertion, or a harness that did not report OK
#   2  could not run at all (missing source, no compiler, no network)
#
# Both harnesses are always attempted, so one CI run shows both verdicts.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${PI_PURE_CHECK_WORK:-$ROOT/build/pure-checks}"
MAVEN="https://repo1.maven.org/maven2"
TOML="$ROOT/gradle/libs.versions.toml"

# Versions come from the catalog, never from a literal here: a Kotlin bump in
# gradle/libs.versions.toml must move this script with it, or the compiler and the
# libraries would disagree with the real build.
kotlin_version="$(sed -n 's/^kotlin = "\(.*\)"/\1/p' "$TOML")"
coroutines_version="$(sed -n 's/^coroutines = "\(.*\)"/\1/p' "$TOML")"
serialization_version="$(sed -n 's/^serializationJson = "\(.*\)"/\1/p' "$TOML")"
if [ -z "$kotlin_version" ] || [ -z "$coroutines_version" ] || [ -z "$serialization_version" ]; then
  echo "pure-checks: CANNOT RUN — could not read kotlin/coroutines/serializationJson from $TOML" >&2
  exit 2
fi

mkdir -p "$WORK"

# Per-PID output, as tools/typecheck.sh does: several agents share this machine,
# and a shared output directory lets one run delete another's class files
# mid-compile — which surfaces as spurious "unresolved reference" errors.
OUT_ROOT="$WORK/out/$$"
trap 'rm -rf "$OUT_ROOT"' EXIT

# A no-op when the file is already staged, so a cached $WORK never touches the
# network. Downloads go to *.part and are moved into place only when complete, so
# an interrupted run cannot leave a truncated jar that a later run would trust.
# The PID in the temp name keeps two concurrent runs from writing the same file.
stage() { # $1 = path under repo1.maven.org, $2 = destination file
  if [ -s "$2" ]; then
    return 0
  fi
  echo "fetch  $(basename "$2")"
  curl -fsSL --retry 3 --retry-delay 2 -o "$2.part.$$" "$MAVEN/$1" && mv "$2.part.$$" "$2"
}

# --- 1. the compiler ----------------------------------------------------------
# The recipe is tools/typecheck.sh's: kotlin-compiler-embeddable plus the three
# jars it needs to actually start (stdlib, reflect, script-runtime) and the
# JetBrains annotations the compiler reads. When typecheck.sh has already staged
# this on the machine at hand, reuse that tree rather than re-downloading ~57 MB;
# CI has no such tree and fetches from Maven Central.
STAGED="$ROOT/build/typecheck/kotlinc"
if [ -n "$(ls -1 "$STAGED"/kotlin-compiler-embeddable-*.jar 2>/dev/null)" ]; then
  KOTLINC_DIR="$STAGED"
else
  KOTLINC_DIR="$WORK/kotlinc"
  mkdir -p "$KOTLINC_DIR"
  stage "org/jetbrains/kotlin/kotlin-compiler-embeddable/$kotlin_version/kotlin-compiler-embeddable-$kotlin_version.jar" \
        "$KOTLINC_DIR/kotlin-compiler-embeddable-$kotlin_version.jar" || exit 2
  stage "org/jetbrains/kotlin/kotlin-stdlib/$kotlin_version/kotlin-stdlib-$kotlin_version.jar" \
        "$KOTLINC_DIR/kotlin-stdlib-$kotlin_version.jar" || exit 2
  stage "org/jetbrains/kotlin/kotlin-reflect/$kotlin_version/kotlin-reflect-$kotlin_version.jar" \
        "$KOTLINC_DIR/kotlin-reflect-$kotlin_version.jar" || exit 2
  stage "org/jetbrains/kotlin/kotlin-script-runtime/$kotlin_version/kotlin-script-runtime-$kotlin_version.jar" \
        "$KOTLINC_DIR/kotlin-script-runtime-$kotlin_version.jar" || exit 2
  stage "org/jetbrains/annotations/13.0/annotations-13.0.jar" \
        "$KOTLINC_DIR/annotations-13.0.jar" || exit 2
fi
KOTLINC_CP="$(find -L "$KOTLINC_DIR" -name '*.jar' | tr '\n' ':')"
KOTLINC_CP="${KOTLINC_CP%:}"

# Probe the compiler before trusting any verdict it does not produce. A JVM that
# never launched emits no Kotlin diagnostics at all, and "zero error diagnostics"
# is exactly what this script reads as success — so without this guard a broken
# classpath would print a confident green over a tree that was never compiled.
# (`tools/typecheck.sh` hit that false-OK channel once and paid for it.)
#
# The success string is `info: kotlinc-jvm <version> (JRE ...)`, NOT
# "kotlin-compiler" — that name only ever appears in the jar's filename. Matching
# on the filename here would make the guard reject every working installation.
COMPILER_SAYS="$(java -cp "$KOTLINC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -version 2>&1)"
if [[ "$COMPILER_SAYS" != *kotlinc* ]]; then
  echo "pure-checks: CANNOT RUN — the Kotlin compiler did not start:" >&2
  printf '%s\n' "$COMPILER_SAYS" | head -3 >&2
  exit 2
fi

# --- 2. the libraries the closures need ---------------------------------------
# Not android.jar, and not the Gradle cache: $WORK/lib is staged explicitly so the
# classpath is identical on a developer's machine and on a CI runner.
#
# The annotations jar is the one entry not read off the version catalog: it is
# kotlinx-coroutines-core-jvm's compile-scope dependency (`org.jetbrains:annotations`,
# resolved to 23.0.0 — the highest of the 13.0 that kotlin-stdlib asks for and the
# 23.0.0 coroutines asks for), and the catalog never names it.
LIB_DIR="$WORK/lib"
mkdir -p "$LIB_DIR"
for path in \
  "org/jetbrains/kotlin/kotlin-stdlib/$kotlin_version/kotlin-stdlib-$kotlin_version.jar" \
  "org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/$serialization_version/kotlinx-serialization-json-jvm-$serialization_version.jar" \
  "org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/$serialization_version/kotlinx-serialization-core-jvm-$serialization_version.jar" \
  "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/$coroutines_version/kotlinx-coroutines-core-jvm-$coroutines_version.jar" \
  "org/jetbrains/annotations/23.0.0/annotations-23.0.0.jar" ; do
  stage "$path" "$LIB_DIR/$(basename "$path")" || {
    echo "pure-checks: CANNOT RUN — could not fetch $(basename "$path")" >&2
    exit 2
  }
done
LIB_CP="$(find -L "$LIB_DIR" -name '*.jar' | tr '\n' ':')"
LIB_CP="${LIB_CP%:}"

# The compiler's OWN runtime classpath needs the staged libraries too, not just the
# compiler jars. Kotlin 2.2's embeddable compiler pulls kotlinx-coroutines at run time
# when it builds its IntelliJ environment, and that jar is not inside
# kotlin-compiler-embeddable: the first CI run that got this far died with
#
#   exception: java.lang.NoClassDefFoundError: kotlinx/coroutines/CoroutineScope
#     at ...KotlinCoreApplicationEnvironment.createApplication(...)
#
# `-version` does not catch it, because printing a version never creates the
# environment - which is why the probe above passes and the compile then throws. The
# source classpath (`-classpath "$LIB_CP"` in run_harness) is a separate thing and
# stays as it is.
COMPILER_CP="$KOTLINC_CP:$LIB_CP"

# --- 3. compile and run each harness ------------------------------------------
failed=0
attempted=0
ran=0

run_harness() { # $1 = label, $2 = main class, rest = sources (harness included)
  local label="$1" main_class="$2"; shift 2
  attempted=$((attempted + 1))
  local src
  for src in "$@"; do
    if [ ! -f "$src" ]; then
      echo "pure-checks: CANNOT RUN — missing source $src" >&2
      exit 2
    fi
  done

  local out="$OUT_ROOT/$label"
  mkdir -p "$out"

  # `-no-stdlib` with an explicit -classpath, exactly as documented: the JDK's own
  # bootclasspath stays in play (no `-no-jdk` here — android.jar is not involved
  # and `java.net.URI` must resolve).
  local diag
  diag="$(java -Xmx1100m -Dfile.encoding=UTF-8 -cp "$COMPILER_CP" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -jvm-target 17 -classpath "$LIB_CP" -d "$out" "$@" 2>&1)"

  # The verdict is the diagnostic count, not the exit status: an exit code that is
  # non-zero for reasons other than a real error would otherwise red the build with
  # nothing to show. Anything the regex misses still has to survive the run below,
  # where a missing main class exits non-zero and is caught.
  local errors status
  errors="$(printf '%s\n' "$diag" | grep -cE '\.kt:[0-9]+:[0-9]+: error:')"
  if [ "$errors" -gt 0 ]; then
    printf '%s\n' "$diag" | grep -E '\.kt:[0-9]+:[0-9]+: (error|warning):' | sed "s|$ROOT/||"
    echo "pure-checks: FAILED — $label did not compile: $errors error diagnostic(s)"
    failed=$((failed + 1))
    return 1
  fi

  # A compiler that threw is not a compiler that found nothing. Both reached this script
  # as "0 error diagnostics", and the empty-class guard below then reported "produced no
  # class files", which describes the symptom and hides the cause (it took a second CI run
  # and a stack trace to learn that the compiler's own classpath was missing coroutines).
  if [[ "$diag" == *"NoClassDefFoundError"* || "$diag" == *"exception:"* ]]; then
    printf '%s\n' "$diag" | head -5
    echo "pure-checks: CANNOT RUN — the Kotlin compiler threw instead of compiling ($label)"
    failed=$((failed + 1))
    return 1
  fi

  # An empty output directory is a failure even when the compiler printed nothing that
  # matches an `error:` diagnostic. This is the same false-green channel that
  # `tools/typecheck.sh` had to close for its `:rpc` jar: the run below would then fail
  # with a bare ClassNotFoundException, which says nothing about *why* nothing compiled.
  # Its first CI run failed exactly here, and the cause was invisible - the harnesses had
  # no `package` line, so they landed in the default package under a different FQCN, and
  # the compiler had nothing to complain about.
  if [ -z "$(find "$out" -name '*.class' -print -quit 2>/dev/null)" ]; then
    printf '%s\n' "$diag" | head -20
    echo "pure-checks: FAILED — $label produced no class files" \
         "(0 error diagnostics, but nothing was emitted either)"
    failed=$((failed + 1))
    return 1
  fi

  local report
  # `pi.repo.root` is passed to every harness: the settings audit reads the
  # registry as source text and scans the tree from there, and deriving the root
  # from the working directory would make its verdict depend on where someone
  # happened to run this script from.
  report="$(java -Dpi.repo.root="$ROOT" -cp "$out:$LIB_CP" "$main_class" 2>&1)"
  status=$?
  printf '%s\n' "$report"
  # Both halves are required. The status catches `exitProcess(1)`; the marker
  # catches the opposite lie — a harness that ran nothing and still exited 0,
  # e.g. because a `main` was never found and the JVM said so on stdout.
  if [ "$status" -ne 0 ] || [[ "$report" != *"harness: OK"* ]]; then
    echo "pure-checks: FAILED — $label (exit $status, no 'harness: OK')"
    failed=$((failed + 1))
    return 1
  fi
  echo "pure-checks: OK — $label"
  ran=$((ran + 1))
  return 0
}

# app.pi.packages: TrustFile's trust.json byte format + nearest-ancestor lookup,
# ProjectTrust's resolution order, PiPackageSource classification, `pi list`
# parsing and the restart state machine. Needs kotlinx.serialization (TrustFile)
# and kotlinx.coroutines (ExtensionLifecycle's MutableStateFlow), plus :rpc's
# pure ANSI stripper, which PiListOutput calls — and `PiProjectConfig.kt`, because
# `ProjectTrust.CONFIG_DIR_NAME` reads the app's one transcription of pi's project
# config directory instead of repeating the literal (a harness compile failure if
# that file is ever left out, which is the point).
run_harness packages \
  app.pi.packages.PackagesPureLogicCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/packages/PackagesPureLogicCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/TrustFile.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiPackageSource.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/ProjectTrust.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiPackageModel.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiListOutput.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiPackageFilters.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiModelsMerge.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiModelCatalog.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiResourceDiscovery.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/PiProjectConfig.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/ExtensionLifecycle.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/Ansi.kt"

# app.pi.bridge: the A3 guest→host candidate order. Pure string arithmetic, no
# Android and no filesystem — see the file header.
run_harness guest-paths \
  app.pi.bridge.GuestPathMappingCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/bridge/GuestPathMappingCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/bridge/GuestPathMapping.kt"

# app.pi.bridge: the device shell's policy exists twice — Kotlin enforces it
# (`DeviceShellGuard.hardBlocks`) and the permission gate's `danger.ts` mirrors it so
# an impossible command is refused before the user is asked about it. The two diverged
# once (a block-device pattern with a `\b` before a `/`, which can never match) and
# nothing in the build could notice; this reads both files as source text and compares
# the rules, plus the registered-tool vs danger-level sets. Android-free: java.io.File,
# regex and the stdlib, because the Kotlin guard itself imports android.os.Process.
run_harness shell-policy-mirror \
  app.pi.bridge.ShellPolicyMirrorCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/bridge/ShellPolicyMirrorCheck.kt"

# app.pi.ui.chat: the pure half of the `@` file-mention completion (trigger
# boundaries, pi's fd argv and shell quoting, pi's scorer and ordering, and what a
# pick inserts). Android-free: it imports only the Kotlin stdlib. Registered late -
# for a while it lived in app/src/test with nothing compiling it, which is the same
# standing every harness here had before this script existed.
run_harness mentions \
  app.pi.ui.chat.PiFileMentionsCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/ui/chat/PiFileMentionsCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/chat/PiFileMentions.kt"

# app.pi.runtime: the two invariants that decide whether the guest can see a tool,
# plus the one implementation of the workspace's guest spelling, plus the one
# transcription of pi's **project** config directory. `installTool` writes rg and fd
# into two different host directories because the engine and the package commands bind
# `<files>/pi/.pi/agent` over the guest's `/root/.pi/agent` and the terminal does not,
# so a tool installed into only one of them is invisible to one of the three launch
# paths. That failure is silent (pi's `find` simply stops returning anything), which is
# why it is pinned here rather than left to a device. `PiProjectConfig` is pinned for
# the same reason one layer up: pi reads every project-scoped thing (settings, skills,
# prompts, themes, extensions) out of `<cwd>/.pi`, the app resolves those paths itself
# because the name is not on any RPC channel, and a wrong name is a whole directory the
# app reads and pi never opens. Android-free: `PiRuntime.kt` imports only
# `java.io.File`, and so do `GuestWorkspacePath.kt` and `PiProjectConfig.kt`.
run_harness agent-tool-paths \
  app.pi.runtime.AgentToolPathsCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/runtime/AgentToolPathsCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/PiRuntime.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/GuestWorkspacePath.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/PiProjectConfig.kt"

# app.pi.session: which on-disk files count as sessions, in which of pi's two
# layouts, in what order, and which one `-c` would resume. It exists because the
# reader had exactly one layout wired in (the grouped one) while the engine writes
# the other one (flat, since `--session-dir` is passed): the list came back empty on
# the device and nothing in the build could notice. Android-free — the store uses
# `java.io.File`, kotlinx.serialization (through `:rpc`'s PiJson) and
# kotlinx.coroutines only.
#
# `SessionFileScan.kt` is in the same closure because it is what makes the store's
# 1 MiB scan an actual bound: a session file's lines are messages, one of which can be
# a multi-megabyte tool result or an inline image, and `BufferedReader.readLine()`
# returns such a line in full before any budget can be consulted. The harness drives
# the scanner directly (over-long line dropped, budget bounded, terminator handling)
# and through `list()`.
run_harness sessions \
  app.pi.session.PiSessionStoreCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/session/PiSessionStoreCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/session/PiSessionStore.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/session/SessionFileScan.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/PiJson.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/internal/Json.kt"

# :rpc: `extension_error`'s attribution. pi sends an absolute file path, which may
# not reach user-visible copy, and most packaged extensions are loaded from
# `index.ts` — so the naive "last path segment" names every broken extension
# "index.ts" and attributes nothing. This pins the reduction the transcript row and
# the snackbar share. Android-free and dependency-free: the file under test imports
# nothing at all.
run_harness extension-error-text \
  app.pi.rpc.ExtensionErrorTextCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/rpc/ExtensionErrorTextCheck.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/ExtensionErrorText.kt"

# app.pi.ui.chat: the transcript's follow-the-tail state machine. This is the one
# part of streaming the App can be *made* to prove here. The rules (when does the
# follow pause, when does it resume, where does it have to scroll to reach the
# newest text) used to live in `LaunchedEffect`s reading `LazyListState`, and the
# defect they caused - the follow pausing itself on a frame of layout churn and then
# never coming back - cannot be reproduced without a device, but it reproduces
# exactly against a pure function. Compose cannot be compiled on this machine (no
# Compose compiler plugin in `tools/typecheck.sh`), so extracting the machine was the
# only way to test any of this. Android-free by construction: `TailFollow.kt` imports
# nothing at all.
run_harness tail-follow \
  app.pi.ui.chat.TailFollowCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/ui/chat/TailFollowCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/chat/TailFollow.kt"

# app.pi.packages: 设置 → 模型 的那张清单（`PiModelInventory.kt`）与外部改动检测的判据
# （`PiFileStamps.kt`）。为什么它必须在这里：清单要回答"导入过的模型为什么不在列表里"，而
# 答案是一个**推断**（文件里有 + 有凭证 + 引擎没列 = 要重启），错在安静的那一侧就等于界面
# 说"已经生效"而引擎从未读过那个文件 —— 正是 §M11/§M12 的形状。Compose 编译不了，所以判定
# 必须留在纯对象里。文件本身不碰文件系统（四份文件原文由调用者传进来），只有 `PiFileStamps`
# 用 `java.io.File` 做 mtime/大小判据。
run_harness models-inventory \
  app.pi.packages.PiModelInventoryCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/packages/PiModelInventoryCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiModelInventory.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiJsonComments.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiFileStamps.kt"

# :rpc: the engine's stdout decoding. `PiEngineSession.readLoop` handed each 16 KiB
# read to `String(bytes, 0, read, UTF_8)`, which decodes one read as a complete
# stream - so a CJK character straddling two reads became one replacement character
# per orphaned byte, inside a JSON string, where the record still parses and the
# corruption is indistinguishable from text the model wrote. The harness splits a
# byte stream at every offset (and one byte at a time) and proves the incremental
# decoder is exact; it also pins the naive behaviour it replaces, so this file cannot
# silently stop testing anything. JDK-only: java.nio plus the Kotlin stdlib, and it
# compiles alongside `JsonlFramer`, whose contract it feeds.
run_harness utf8-stream \
  app.pi.rpc.Utf8StreamDecoderCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/rpc/Utf8StreamDecoderCheck.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/Utf8StreamDecoder.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/Jsonl.kt"

# app.pi.ui.settings: the registry audit. `PiSettingsRegistry.kt` is data — 67 rows
# transcribed from pi's settings documents — and nothing ever compared it against
# the rest of the tree, so a row that is persisted and read by nobody could only be
# found by hand. That defect has shipped three times (§I7, §I10, §I11 in
# `docs/known-gaps.md`), which is what makes a check worth more than another careful
# pass. The harness reads the registry as source text (it imports Compose, so it
# cannot be compiled here) and fails on any key that is neither read by this app nor
# declared pi-owned with the pi location that reads it. Android-free: java.io.File,
# regex and the stdlib.
run_harness settings-audit \
  app.pi.ui.settings.PiSettingsAuditCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/ui/settings/PiSettingsAuditCheck.kt"

# :rpc: the pre-spawn surface. `PiLaunchOptions` is pure already, but nothing
# checked that the settings rows in 设置 → 运行时 → 进程 actually reach it, or that
# no exposed knob duplicates a key pi reads from settings.json (the two-truths
# shape of §M12), or that a `RestartEngine` badge is not on a knob the running
# process could still change. The check compiles `PiLaunchOptions.kt` +
# `PiPreSpawnConfig.kt` and reads the registry and the ViewModel as source text
# (both import Compose / Android and cannot be compiled here), the same division
# `settings-audit` uses. Android-free: the two compiled files import nothing at
# all.
run_harness pre-spawn \
  app.pi.rpc.PiPreSpawnCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/rpc/PiPreSpawnCheck.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/PiLaunchOptions.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/PiPreSpawnConfig.kt"

# app.pi.service: the foreground service's lifecycle decisions — what a start
# command means (including the null intent a killed `START_STICKY` service is
# re-created with), whether the CPU must stay awake, whether the service should
# exist at all, and how long a countdown may sleep. They were inline in an Android
# class, where the wrong answer is silent in three different ways: a notification
# about an engine that is gone, a six-hour wake-lock cap that expires inside a turn
# while the CPU stays awake for hours when pi is idle, and a countdown that polls
# five times for every displayed second. `PiEngineLifecyclePolicy.kt` imports
# nothing at all, so its truth tables — and a sweep of the countdown loop over every
# deadline from 1 to 5000 ms — run here; the Android halves it is wired into do not.
run_harness lifecycle-policy \
  app.pi.service.PiEngineLifecyclePolicyCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/service/PiEngineLifecyclePolicyCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/service/PiEngineLifecyclePolicy.kt"

# --- 4. verdict ---------------------------------------------------------------
# The counts are computed, not written down. They were hardcoded once ("2
# harnesses"), and adding a third would have left the message lying about how much
# was actually checked - a small instance of the thing this repository keeps
# finding: a number in prose that nothing updates.
if [ "$failed" -ne 0 ]; then
  echo "pure-checks: FAILED — $failed of $attempted harness(es) failed"
  exit 1
fi
echo "pure-checks: OK — $ran harnesses ran on a bare JVM (no Android SDK, no Gradle)"
exit 0
