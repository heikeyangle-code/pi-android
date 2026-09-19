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

# app.pi.packages: `pi update`'s argv shape, the refusal of pi's self-update targets
# (`self`/`pi` would replace the pinned engine payload), which sources are updateable at
# all, and the recognition of pi's own result line — the progress line must not be mistaken
# for a result. Pure kotlin stdlib.
run_harness pi-package-update \
  app.pi.packages.PiPackageUpdateCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/packages/PiPackageUpdateCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiPackageUpdate.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiPackageSource.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PackageStrings.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiResourceDiscovery.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiPackageModel.kt"

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
  "$ROOT/app/src/main/kotlin/app/pi/runtime/GuestRecipe.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/RuntimeChoice.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/GuestWorkspacePath.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/PiProjectConfig.kt"

# app.pi.runtime: the rg/fd "does it really run" probe's verdict logic. Existence is
# the wrong question — a dangling `/usr/local/bin/{rg,fd}` and a tool that was never
# installed are the same thing to pi's `find` and the `@` completion (both return
# nothing, silently), which is why the probe runs a real invocation and why the parser
# is the only place the difference becomes visible. This harness feeds it a healthy
# guest, a 127 exit (the dangling-symlink shape), an exit-0-with-no-output shape, a
# missing marker line, a search that finds nothing, CRLF and non-marker noise, and a
# tab inside a detail field — and requires a failure to be reported, never assumed
# away. The command shape is pinned too, because a probe that stops really searching
# would leave the same blind spot the probe was added to close. Android-free:
# `GuestToolProbe.kt` imports only `java.io`/`java.util.concurrent`, and `PiRuntime.kt`
# only `java.io.File`, so `guestCommand()`/`parse()` run here with no device.
run_harness guest-tool-probe \
  app.pi.runtime.GuestToolProbeCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/runtime/GuestToolProbeCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/GuestToolProbe.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/PiRuntime.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/GuestRecipe.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/RuntimeChoice.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/GuestCommandLine.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/ProrootCommand.kt"

# app.pi.runtime: the opt-in proroot runtime's pure logic — the §2.3.1 argv/env mapping
# (including the four proot-only flags proroot *rejects*), the runtime-selection guard
# order and the three-failure boundary, the `.proroot-config-*` liveness/cap rules, the
# guest-tree descendant order and `/proc/<pid>/stat` parsing, the probe-cache key, the
# raw-syscall probe's verdict parser (a leak must never pass), the launch-pid handle
# driven against a real temporary directory, and the shared bind/env recipe both
# builders use. Every file in this closure is `java.io` + stdlib, so a future Android
# import in any of them fails this compile — which is the point, because all of it would
# otherwise be first exercised on a phone.
run_harness proroot \
  app.pi.runtime.ProrootCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/runtime/ProrootCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/PiRuntime.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/GuestRecipe.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/ProrootCommand.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/GuestCommandLine.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/RuntimeChoice.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/ProrootRetry.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/ProrootConfigSweep.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/GuestProcessTree.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/ProrootProbeCache.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/ProrootRawProbe.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/ProrootLaunchHandle.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/ShellQuote.kt"

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
#
# `SessionImport.kt` / `SessionExportNaming.kt` joined it for the same reason one
# layer up: `/import` and `/export` both hinge on **pi's own rules** — which first
# line makes a file a session (`session-manager.ts:551-556`), how an imported copy is
# named when the name is taken (`agent-session-runtime.ts:371-379`), and which
# argument suffix picks which writer (`interactive-mode.ts:6062-6066`) — and none of
# that can be executed while it lives in `PiSessionViewModel`, which imports Android
# and Compose. Both files are Android-free (java.io/kotlinx.serialization only).
run_harness sessions \
  app.pi.session.PiSessionStoreCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/session/PiSessionStoreCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/session/PiSessionStore.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/session/SessionFileScan.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/session/SessionImport.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/session/SessionExportNaming.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/PiJson.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/internal/Json.kt"

# app.pi.session: the open-a-session cost, and the equivalence the new replay source
# rests on. Registered here for the two reasons the other harnesses are: the numbers
# in the change report have to be reproducible (`kotlinc` + a JVM, no device and no
# Node), and **`SessionFileReader` may not become a second source of truth for a
# session** — so the harness asserts that walking a session file backwards in
# bounded windows reproduces the whole file's entries exactly, and that the rows
# projected from that windowed replay equal the rows projected from a single
# whole-session replay. It also prints what actually pushes a `get_entries` response
# past the framer's record cap (inline base64 images) and what the same work costs on
# a tail window instead of on the whole session — both measured against
# `JsonlFramer.DEFAULT_MAX_RECORD_CHARS` rather than a literal, so the two cannot drift.
#
# `SessionFileReader` is Android-free by construction (java.io + kotlinx.serialization
# through `:rpc`'s `PiJson`); if it ever grows an Android import, this compile fails,
# which is the point. `SessionFileScan` is deliberately **not** in this closure: the
# replay reader counts bytes per line, which that scanner cannot report, so it has
# its own LF loop and the two are compared by the `sessions` harness's fixtures.
run_harness session-replay-cost \
  app.pi.session.SessionReplayCostCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/session/SessionReplayCostCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/session/SessionFileReader.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/PiJson.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/Jsonl.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/internal/Json.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/Transcript.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/Events.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/Messages.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/Ansi.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/ExtensionErrorText.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/Responses.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/Commands.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/SessionEntries.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/SkillBlock.kt"

# app.pi.ui.screens: 输入区附件的**体积预算**，也是「这张图能不能加进这条消息」的唯一判定处。
# 为什么它必须在这里：解码/缩放/编码要 `Bitmap`，本机编译不了 `ChatScreen`（Compose），而真正
# 决定一张图能不能发出去的是算术 —— pi 自己的上限（最长边 2000、base64 4.5 MB、质量阶梯
# `[80,85,70,55,40]`、每轮缩 0.75）、由 `JsonlFramer.DEFAULT_MAX_RECORD_CHARS` 反推的**每条
# 消息** base64 预算、以及拒绝那句话要用的每个数字（句子的数字全部来自判定本身，所以文案与
# 判定不可能对不上）。`ChatScreen` 只剩编解码那一薄层。
#
# 三件不能靠读代码保证的事：(1) pi 的常量是**逐值**钉死的 —— 对另一个程序的主张会无声过期；
# (2) e2e：7 张 pi 上限大小的图放得下、第 8 张放不下（pi 自己是严格 `< maxBytes`）；
# (3) 与 `SessionFileReader.DEFAULT_MAX_LINE_CHARS` 的耦合 —— 合法的一行 entry 严格小于合法的
# 单条消息记录（记录外面还有一层信封），所以读会话的行上限必须 ≥ 帧记录上限；这条正是
# 「打开时丢行 → 回落旧路径 → 会话打不开」的复发条件。
#
# Android-free：本文件与 reader 只 import stdlib 与 kotlinx.serialization（经 `:rpc` 的
# `PiJson`）；`AttachmentBudget` 一旦长出 Android import，这里就编译失败，这正是目的。
run_harness image-attachment-budget \
  app.pi.ui.screens.AttachmentBudgetCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/ui/screens/AttachmentBudgetCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/screens/AttachmentBudget.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/session/SessionFileReader.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/Jsonl.kt" \
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

# app.pi.ui.chat: pi 的队列/编辑器合并规则。`restoreQueuedMessagesToEditor`
# (`interactive-mode.ts:4387-4406`) 是 Stop（`{abort:true}`）和队列行「收回」共用的那一步：
# 队列文本在前、输入框里已有的文本在后、空的一半丢掉（`:4397-4400`）。两条路径必须合并得
# 一模一样，而 `ChatScreen.kt` 引入 Compose（本机编译不了），所以规则被抽成纯函数搬到
# `QueueRestore.kt`。Android-free：只用 Kotlin stdlib。
run_harness queue-restore \
  app.pi.ui.chat.QueueRestoreCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/ui/chat/QueueRestoreCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/chat/QueueRestore.kt"

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
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/PiPreSpawnConfig.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/ExtensionFlagArgs.kt"

# app.pi.rpc: the launch arguments an *extension* declared (`pi.registerFlag`) and the user
# types into the app. pi's own parser is the specification — `cli/args.ts:227-241` decides
# what a `--flag` means from the tokens around it — so this harness is that table, line for
# line: the `=` form (first `=` splits, the value is taken verbatim, may be empty), the
# space form (the next token counts only if it does not start with `-`/`@`, and is then
# consumed), a bare flag meaning `true`, last-one-wins, and the shapes pi turns into a
# startup error *before* anything else runs (`-x`, `@file`, `--`, a stray word). It
# deliberately does **not** check registration: that set exists only inside the pi process,
# so a whitelist here could only be a guess.
run_harness extension-flags \
  app.pi.rpc.ExtensionFlagArgsCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/rpc/ExtensionFlagArgsCheck.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/ExtensionFlagArgs.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/PiLaunchOptions.kt"

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

# app.pi.runtime: the unpack's free-space budget. `RuntimeProvisioner.ensureReady`
# deletes the whole runtime tree and then extracts ~440 MB of payloads into it, and
# nothing checked free space: on a phone that is out of room the user lost the
# runtime that worked, the new one was half written, and - because the revision stamp
# is only written at the end - every later launch repeated the wipe. The failing path
# only exists on a device that is out of space, so nothing in the build could notice
# it. The object imports nothing at all; the file read (`File.usableSpace`) and the
# call site stay in the provisioner, which this harness deliberately does not compile.
run_harness runtime-space \
  app.pi.runtime.RuntimeSpaceBudgetCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/runtime/RuntimeSpaceBudgetCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/RuntimeSpaceBudget.kt"

# app.pi.engine: the engine's exit translator. pi exits with code 1 for a handful of
# reasons and prints the reason to stderr; the app captured that stderr and then never
# read it back, so the failure screen showed a title with an empty detail and the
# diagnostic report said the exit code was "not recorded". Every rule in the object is
# a claim about what pi prints, and the harness pins the strings **verbatim from runs
# against the pinned engine** (pi 0.85.1): if pi's wording changes, the rule stops
# matching silently, which is exactly the failure this pins. It also pins the other
# half - an unattributable exit must stay unattributable. No imports either.
run_harness engine-exit-cause \
  app.pi.engine.EngineExitCauseCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/engine/EngineExitCauseCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/engine/EngineExitCause.kt"

# app.pi.ui.blocks: P2-1's per-tool result parser (`docs/capability-gap.md` §4.9).
# Every rule in `ToolOutputParse.kt` is a claim about text pi wrote somewhere else — grep's
# two row shapes (`core/tools/grep.ts:211-212`, empty answer `:256-259`, notice `:303`),
# find's relativised paths (`find.ts:268-273`, `:261`, `:292`), ls's `"/"` directory suffix
# (`ls.ts:121-129`, `:135`, `:155`) and read's three footers (`read.ts:56`, `:64`, `:66`,
# `:73`) — and a wording change on pi's side makes the rule stop matching *silently*: a grep
# result would render as one unrecognised blob and no build would say so. The second half of
# the contract is that a newer engine's result must degrade to the generic card rather than
# blank the transcript, so the harness also feeds every entry point hostile input (empty,
# control characters, an unterminated bracket, megabytes of one line) and asserts that each
# call still returns, that every list respects its cap, and that an unrecognised grep result
# answers null. Android-free: kotlinx.serialization (for the args the protocol delivers) and
# the Kotlin stdlib, plus `java.util.Locale` for pi's one-decimal duration. The eight block
# files themselves import Compose and are therefore not compiled here — only the pure half.
run_harness tool-output-parse \
  app.pi.ui.blocks.ToolOutputParseCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/ui/blocks/ToolOutputParseCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/blocks/ToolOutputParse.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/blocks/ToolCallPart.kt"

# app.pi.ui.blocks: the arithmetic of a transcript image's box — how tall a lone picture
# may be, the shape its own header declares, the pixels that actually get drawn, and how
# many decodes may run at once. Why it has to be pinned here: both defects it answers are
# invisible to every other check in this repository. ① the single image's size is one float
# constant (`SINGLE_IMAGE_MAX_HEIGHT_FRACTION`, moved here out of `ImageGridBlock.kt`)
# applied by arithmetic that no build step evaluates; ② 「往上滑有图片的时候不流畅」 was a
# *row height change* — the cell sized its box from the decoded bitmap, and the fix states
# the height from the payload's own header before anything is decoded, which makes "the same
# picture never moves the row" a property of this file's functions. `ImageSize.kt` imports
# neither Android nor Compose (kotlinx.coroutines' `Semaphore` for the gate, the stdlib for
# the rest — this compile fails if that ever changes, which is the point). The two
# composable call sites cannot be compiled here at all, so the harness also reads them as
# source text and requires every `decodePiImage` call in them to sit inside the shared gate;
# `pi.repo.root` is what that read uses.
run_harness image-size \
  app.pi.ui.blocks.ImageSizeCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/ui/blocks/ImageSizeCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/blocks/ImageSize.kt"

# app.pi.ui: the retained-history budget's measure. `PiSessionViewModel` decides whether
# to keep reading older history from a running character total, and that total is
# accumulated *incrementally* — which is the same number only while the measure is
# additive over a concatenation. The additivity, and what the number *is* (an entry's
# serialised length, so an inline image's base64 counts), are what this pins.
run_harness history-retention \
  app.pi.ui.HistoryRetentionCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/ui/HistoryRetentionCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/HistoryRetention.kt"

# app.pi.ui.chat: the `!` panel's output window. pi bounds its own panel at the tail
# (`modes/interactive/components/bash-execution.js:93-98`, `truncateTail` with
# DEFAULT_MAX_LINES/DEFAULT_MAX_BYTES); this is the same bound on the app's side, and the
# harness pins the three properties that keep a chatty command from growing a String and a
# single Text without a ceiling: the bound holds, the tail survives, a trim is reported.
run_harness bash-output \
  app.pi.ui.chat.BashOutputCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/ui/chat/BashOutputCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/chat/BashOutput.kt"

# app.pi.ui.render: the row-height floor that keeps a re-composed transcript row from
# growing back from zero. The markdown library cannot hand back a parsed state, so the
# fallback is "remember the height this row measured last time and hold it for the first
# frames after a recomposition" - which is only safe while the memory is *bounded* and
# never answers with a stale height. This harness pins exactly those two properties (the
# LRU's capacity and access order, nothing recorded for a zero-height loading frame, an
# evicted key answering null rather than its old value), because an unbounded or
# forgetful cache would trade a visual jump for a memory leak or a wrong row height.
run_harness row-height-cache \
  app.pi.ui.render.RowHeightCacheCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/ui/render/RowHeightCacheCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/render/RowHeightCache.kt"

# app.pi.ui.blocks: the transcript image cache's arithmetic — a byte-bounded LRU whose keys
# are multi-megabyte payloads. Two properties matter and neither is visible in the UI: the
# byte accounting must include the key itself (a cache that forgets what its keys weigh is a
# memory leak with extra steps), and the lookup must never hash the payload — `hashCode()` on
# a 4 MiB string measured 22.8 ms, which is why this is a linear `==` scan rather than a
# `LinkedHashMap`. The harness checks the LRU against an independent reference implementation
# and asserts that `hashCode` is never called. `PiImageCache.kt` itself imports `Bitmap` and
# therefore cannot be compiled here; this pins the class it delegates to.
run_harness pi-image-cache \
  app.pi.ui.blocks.PiImageCacheCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/ui/blocks/PiImageCacheCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/blocks/ImageSize.kt"

# app.pi.ui.blocks: the *text* parse caches — the same bounded-by-bytes discipline as the
# image one, for the work a row repeats whenever it leaves the reuse pool and comes back
# (a diff plan is 3.9–13.2 ms, a coloured `Ansi.strip` 2.9 ms, `tailLines` ~2 ms). A row
# that scrolls out and returns, or a screen switch, must not redo it — but "must not" has
# to hold without growing: every cache is byte-bounded, the byte accounting includes the
# key, a big key is never hashed, and a failed compute is not cached. The incremental line
# count is pinned against the full count over random growths and rewrites, because "faster"
# is only acceptable while it answers the same number.
run_harness text-cache \
  app.pi.ui.blocks.TextCacheCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/ui/blocks/TextCacheCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/blocks/ImageSize.kt"

# app.pi.ui.settings: what the editors are allowed to write into pi's files. pi throws on
# some values (a `null` timeout, a compaction override whose value is not a number) and
# silently ignores others (a line with no `=`), so an editor that only *looks* checked
# turns a typo into a broken engine or a setting that does nothing. This harness pins the
# verdicts themselves — the accepted domains, the bounds message, and the rejection of the
# exact shapes pi refuses — because the Compose sheets that call them cannot be compiled
# here at all.
run_harness settings-validation \
  app.pi.ui.settings.PiSettingsValidationCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/ui/settings/PiSettingsValidationCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/settings/PiSettingsValidation.kt"

# app.pi.ui.settings: what the 「扩展与资源」group reports as *discovered*. The four rows that
# used to list hand-written search paths were removed because pi finds resources by itself —
# so this screen has to state what is actually there, with its sources, and it must never
# invent a reading: a count, "nothing found yet", "could not read: why" and "not read yet"
# are four different answers that must not impersonate each other (an empty directory and an
# unreadable one look identical on disk, which is exactly the kind of lie this pins out).
run_harness settings-resources \
  app.pi.ui.settings.PiResourceFactsCheckKt \
  "$ROOT/app/src/main/kotlin/app/pi/ui/settings/PiResourceFacts.kt" \
  "$ROOT/app/src/test/kotlin/app/pi/ui/settings/PiResourceFactsCheck.kt"

# app.pi.settings: the settings document itself — deleting a key (pi's own "use the
# default": writing `null` makes pi throw), one shared document per file, and the lock and
# temp-file discipline around a write. Every property here answers a defect that shipped:
# a "restore default" that bricked startup, a second store instance that erased the first
# one's key, and a temp name two writers could collide on.
run_harness settings-store \
  app.pi.settings.PiSettingsStoreCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/settings/PiSettingsStoreCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/settings/PiSettingsStore.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/settings/PiSettingsFileStore.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/settings/PiSettingsLock.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/PiProjectConfig.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/SettingsDocument.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/PiJson.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/internal/Json.kt"

# app.pi.settings: the 「Pi 文件」screen's pure logic — the two roots, the writable
# whitelist (default deny), the document classes, and the write-time JSON checks that keep
# a `null`/bad value out of the files pi *throws* on (settings-manager.ts:188-197, :860-877).
# Android-free: java.io.File, kotlinx.serialization and the stdlib; PiJsonComments is the
# same comment stripper models.json is read with. What this pins is the part a screen
# cannot be trusted with: that an unknown name is refused rather than edited, and that
# every "pi would reject this" shape is refused before the write, not after.
run_harness pi-files \
  app.pi.settings.PiFilesCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/settings/PiFilesCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/settings/PiFiles.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiJsonComments.kt"

# app.pi.ui.screens: the workspace tree's pi-file write rule. A save under the workspace's own
# `.pi/` is a write to a file pi reads, and it used to be a whole-file overwrite — a second
# writer next to the 「Pi 文件」screen's locked, atomic, validated one. This pins which save
# takes which path, that the validation is `checkPiFileWrite` (the same function the other
# screen runs), and the stale-stamp refusal.
run_harness workspace-pi-write \
  app.pi.ui.screens.WorkspacePiWriteCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/ui/screens/WorkspacePiWriteCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/screens/WorkspacePiWrite.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/settings/PiFiles.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiJsonComments.kt"

# app.pi.runtime: the workspace lifecycle's own rules, lifted out of the screen and the store
# so they can be pinned — which of the app's directories count as workspaces, how a workspace
# is named and displayed, which workspace the process actually starts in, and what clicking a
# row does (the order of those branches is part of the answer). Every one of these used to be
# a private rule inside `ProjectScreen` or `WorkspaceStore`, and two screens disagreed about
# the current workspace's name; this is the single copy both now call.
run_harness workspace-choice \
  app.pi.runtime.WorkspaceChoiceCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/runtime/WorkspaceChoiceCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/WorkspaceChoice.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/runtime/GuestWorkspacePath.kt"

# app.pi.ui.extension: the notice queue's eviction rule. The host shows one snackbar at a
# time, oldest first, and consumes an entry only after it has been shown — so the head of
# the list is the message on screen. `takeLast` used to evict it, deleting the sentence the
# user was reading and replacing the visible snackbar; this pins "never drop the head".
run_harness notice-queue \
  app.pi.ui.extension.NoticeQueueCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/ui/extension/NoticeQueueCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/extension/NoticeQueue.kt"

# app.pi.ui.chat: the `@` lookup's failure classification. The composer draws nothing for an
# empty candidate list, which is right for "fd matched nothing" and was also the answer for
# a missing runtime / a proot launch failure / a timeout / a killed process. Which is which
# is a decision about `GuestCommand.Outcome`'s fields that nothing else checks — including
# the arm that must stay silent (a non-zero exit is pi's no-candidates).
run_harness mentions-unavailable \
  app.pi.ui.chat.MentionLookupCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/ui/chat/MentionLookupCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/chat/MentionLookup.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/chat/PiFileMentions.kt"

# app.pi.ui.chat: in-session branch navigation (`navigateTree`), which pi's RPC surface
# cannot reach directly — the app drives it through a command its own extension registers,
# so "the prompt call returned" is the only completion signal there is, and the branch that
# has to be *excluded* is the abandoned one (the session file keeps it; only the in-memory
# leaf says which line is live). This pins the landing rules, the summary choice, the
# command arguments, and the outcome classification — including the arms that must not
# rebuild the transcript, because "reset with nothing to show" is its own bug.
run_harness tree-navigation \
  app.pi.ui.chat.PiTreeNavigationCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/ui/chat/PiTreeNavigationCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/chat/PiTreeNavigation.kt"

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
