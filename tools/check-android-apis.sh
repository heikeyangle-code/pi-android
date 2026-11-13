#!/usr/bin/env bash
# Android 的 `java.nio.file` 只提供到 API 26 的那一套；JDK 17 + compileSdk 37 让
# `Path.of` / `Files.readString` 这类 Java 11 方法**编译通过**，真机上却是
# `NoSuchMethodError: No static method of(...)`。0.1.7 的「运行时解包失败」就是它：
# vivo X200s 上 `java.nio.file.Path` 来自 core-oj.jar，没有 `of`。
#
# 这条检查只认「确定在 Android 运行库里没有」的几个方法，全部是编译期看不出、
# 运行到那行才炸的。minSdk 是 26，所以这些 API 30/33 的都不是"以后再说"。
set -uo pipefail
cd "$(dirname "$0")/.."

fails=0
scan() { # $1 = 扩展正则, $2 = 替代写法
  local pattern="$1" instead="$2" hits
  # 注释里的名字（`// Path.of(...)`、` * Path.of(...)`）不算使用：它们在 KDoc 里
  # 解释的往往正是"不要用这个"。
  hits="$(grep -rnE --include='*.kt' -- "$pattern" app/src/main rpc/src/main 2>/dev/null \
    | grep -vE ':[0-9]+:[[:space:]]*(//|\*|/\*)' || true)"
  if [ -n "$hits" ]; then
    printf '%s\n' "$hits" | sed 's/^/  /'
    echo "  → $instead"
    fails=$((fails + 1))
  fi
}

scan 'Path\.of\('                     'Path.of 是 API 33+；用 Paths.get(...)（API 26）'
scan 'Files\.(readString|writeString)\(' 'Java 11 的方法；Android 上没有，用流/File 自己读'
scan '\b(List|Set|Map)\.of\('         'java.util 的 of 是 API 30+；用 listOf/setOf/mapOf'
scan '\.readAllBytes\(\)'             'Java 9 的方法；用 Kotlin 的 readBytes()'
scan 'Optional[^;]*\.isEmpty\(\)'     'Optional.isEmpty 是 API 33+；用 !isPresent'

if [ "$fails" -gt 0 ]; then
  echo "android-api: FAILED ($fails 类)"
  exit 1
fi
echo "android-api: OK — 没有用到 Android 运行库没有的 Java 11+ 方法"
