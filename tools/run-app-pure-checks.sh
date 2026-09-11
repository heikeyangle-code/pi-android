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
  report="$(java -cp "$out:$LIB_CP" "$main_class" 2>&1)"
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
# pure ANSI stripper, which PiListOutput calls.
run_harness packages \
  app.pi.packages.PackagesPureLogicCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/packages/PackagesPureLogicCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/TrustFile.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiPackageSource.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/ProjectTrust.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiPackageModel.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/PiListOutput.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/packages/ExtensionLifecycle.kt" \
  "$ROOT/rpc/src/main/kotlin/app/pi/rpc/Ansi.kt"

# app.pi.bridge: the A3 guest→host candidate order. Pure string arithmetic, no
# Android and no filesystem — see the file header.
run_harness guest-paths \
  app.pi.bridge.GuestPathMappingCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/bridge/GuestPathMappingCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/bridge/GuestPathMapping.kt"

# app.pi.ui.chat: the pure half of the `@` file-mention completion (trigger
# boundaries, pi's fd argv and shell quoting, pi's scorer and ordering, and what a
# pick inserts). Android-free: it imports only the Kotlin stdlib. Registered late -
# for a while it lived in app/src/test with nothing compiling it, which is the same
# standing every harness here had before this script existed.
run_harness mentions \
  app.pi.ui.chat.PiFileMentionsCheckKt \
  "$ROOT/app/src/test/kotlin/app/pi/ui/chat/PiFileMentionsCheck.kt" \
  "$ROOT/app/src/main/kotlin/app/pi/ui/chat/PiFileMentions.kt"

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
