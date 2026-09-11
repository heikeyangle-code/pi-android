#!/usr/bin/env bash
# Stage into build/typecheck/extra the dependencies that tools/typecheck.sh
# cannot find in the Gradle cache.
#
# typecheck.sh deliberately never runs Gradle — that is the whole point of it —
# so it can only see the files a previous Gradle run happened to download. The
# markdown renderer is new and only CI has resolved it, so fetch it here.
#
# The Kotlin standard library is the one jar we must NOT stage: the renderer
# asks for 2.4.0, and this project's compiler (Kotlin 2.2.21) cannot read 2.4.0
# metadata. app/build.gradle.kts pins it back down; typecheck.sh gets the
# pinned copy from the Gradle cache. Staging 2.4.0 here would make the local
# check disagree with the real build.
#
#   tools/fetch-typecheck-deps.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXTRA="$ROOT/build/typecheck/extra"
MARKDOWN_VERSION="$(sed -n 's/^markdown = "\(.*\)"/\1/p' "$ROOT/gradle/libs.versions.toml")"
MARKDOWN_JVM_VERSION="0.7.5"
COROUTINES_VERSION="$(sed -n 's/^coroutines = "\(.*\)"/\1/p' "$ROOT/gradle/libs.versions.toml")"
BASE="https://repo1.maven.org/maven2"

if [ -z "$MARKDOWN_VERSION" ]; then
  echo "could not read the markdown version from gradle/libs.versions.toml" >&2
  exit 1
fi

mkdir -p "$EXTRA/aar"
fetch() { # $1 = url, $2 = destination
  if [ -s "$2" ]; then
    echo "have   $(basename "$2")"
    return
  fi
  echo "fetch  $(basename "$2")"
  curl -fsSL --retry 3 -o "$2.part" "$1"
  mv "$2.part" "$2"
}

# Plain jars go straight on the classpath.
fetch "$BASE/org/jetbrains/markdown-jvm/$MARKDOWN_JVM_VERSION/markdown-jvm-$MARKDOWN_JVM_VERSION.jar" \
      "$EXTRA/markdown-jvm-$MARKDOWN_JVM_VERSION.jar"
fetch "$BASE/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/$COROUTINES_VERSION/kotlinx-coroutines-core-jvm-$COROUTINES_VERSION.jar" \
      "$EXTRA/kotlinx-coroutines-core-jvm-$COROUTINES_VERSION.jar"

# AARs are unpacked to their classes.jar, the same layout typecheck.sh builds
# for the artifacts it extracts out of the Gradle cache.
for artifact in multiplatform-markdown-renderer-android \
                multiplatform-markdown-renderer-m3-android; do
  dir="$EXTRA/aar/$artifact"
  if [ -s "$dir/classes.jar" ]; then
    echo "have   $artifact/classes.jar"
    continue
  fi
  echo "fetch  $artifact-$MARKDOWN_VERSION.aar"
  mkdir -p "$dir"
  tmp="$(mktemp -d)"
  curl -fsSL --retry 3 -o "$tmp/$artifact.aar" \
    "$BASE/com/mikepenz/$artifact/$MARKDOWN_VERSION/$artifact-$MARKDOWN_VERSION.aar"
  python3 - "$tmp/$artifact.aar" "$dir/classes.jar" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    open(sys.argv[2], "wb").write(z.read("classes.jar"))
PY
  rm -rf "$tmp"
done

# Shizuku, the ADB-level (uid=2000) shell backend. gradle/libs.versions.toml
# records that `api` transitively brings `aidl` and `shared`, and that
# `provider` is required at runtime for the binder handoff. typecheck.sh never
# resolves POMs, so all four AARs are staged explicitly: a missing one shows up
# as an unresolved import rather than as a resolution error.
SHIZUKU_VERSION="$(sed -n 's/^shizuku = "\(.*\)"/\1/p' "$ROOT/gradle/libs.versions.toml")"
if [ -z "$SHIZUKU_VERSION" ]; then
  echo "could not read the shizuku version from gradle/libs.versions.toml" >&2
  exit 1
fi

for artifact in api aidl shared provider; do
  dir="$EXTRA/aar/shizuku-$artifact"
  if [ -s "$dir/classes.jar" ]; then
    echo "have   shizuku-$artifact/classes.jar"
    continue
  fi
  echo "fetch  shizuku-$artifact-$SHIZUKU_VERSION.aar"
  mkdir -p "$dir"
  tmp="$(mktemp -d)"
  curl -fsSL --retry 3 -o "$tmp/$artifact.aar" \
    "$BASE/dev/rikka/shizuku/$artifact/$SHIZUKU_VERSION/$artifact-$SHIZUKU_VERSION.aar"
  python3 - "$tmp/$artifact.aar" "$dir/classes.jar" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    open(sys.argv[2], "wb").write(z.read("classes.jar"))
PY
  rm -rf "$tmp"
done

# termlib: the terminal emulator (`org.connectbot:termlib`), libvterm over JNI.
# Staged for the same reason as everything else here — typecheck.sh never resolves
# a configuration, and no local Gradle run has ever fetched it, so without this
# every `VTermKey`/`Terminal`/`ModifierManager` reference in `ui/terminal/**`
# reports "unresolved reference" and buries the real diagnostics under ~33 fakes.
# It is also the dependency with a **ceiling** on its version: 0.0.14 and later are
# built with Kotlin 2.3.x, whose metadata (mv=[2,3,0]) this project's 2.2.21
# compiler cannot read. So the version is read from the catalog and must not be
# "helpfully" bumped here.
TERMLIB_VERSION="$(sed -n 's/^termlib = "\(.*\)"/\1/p' "$ROOT/gradle/libs.versions.toml")"
if [ -z "$TERMLIB_VERSION" ]; then
  echo "could not read the termlib version from gradle/libs.versions.toml" >&2
  exit 1
fi

dir="$EXTRA/aar/termlib"
if [ -s "$dir/classes.jar" ]; then
  echo "have   termlib/classes.jar"
else
  echo "fetch  termlib-$TERMLIB_VERSION.aar"
  mkdir -p "$dir"
  tmp="$(mktemp -d)"
  curl -fsSL --retry 3 -o "$tmp/termlib.aar" \
    "$BASE/org/connectbot/termlib/$TERMLIB_VERSION/termlib-$TERMLIB_VERSION.aar"
  python3 - "$tmp/termlib.aar" "$dir/classes.jar" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    open(sys.argv[2], "wb").write(z.read("classes.jar"))
PY
  rm -rf "$tmp"
fi

echo
echo "staged into $EXTRA:"
find "$EXTRA" -name '*.jar' | sed "s|$ROOT/||" | sort
