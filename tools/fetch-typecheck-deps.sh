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

echo
echo "staged into $EXTRA:"
find "$EXTRA" -name '*.jar' | sed "s|$ROOT/||" | sort
