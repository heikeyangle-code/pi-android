#!/usr/bin/env bash
# 从源码构建 tawcroot（wmww/tawc，MIT），产出 `libtawcroot.so`。
#
# 产物是什么：静态、非 PIE 的 arm64 可执行文件（ET_EXEC），借 `.so` 的名字让 Android 的
# jniLib 提取器把它解到 `nativeLibraryDir` —— 与 proot 的做法相同（提取器只认 `lib*.so`，
# 不校验 ELF 类型）。App 侧见 `PiRuntime.tawcrootBinary()`。
#
# 不下载二进制：钉死一个 tag，从源码构建，可审计。没有这个文件时 App 照常工作 —— 选
# tawcroot 会被判「缺组件」并回退 proot（`RuntimeChoice` / `docs/runtime-engines.md`）。
#
# 用法：
#   tools/build-tawcroot.sh [输出目录]
#   输出目录默认 app/src/main/jniLibs/arm64-v8a。
#
# NDK：优先 `ANDROID_NDK_HOME`；没设就在 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 的
# `ndk/` 下挑版本最高的那个（与上游 `tawcroot/build.sh` 的查找方式一致）。
#
# ## 为什么把这些写死在这里（上游 build.sh 的坑）
#
# 上游 `tawcroot/build.sh` 是 **TAWC 这个 app 自己的构建脚本**，不是独立工程：
#   1. 它不接“输出目录”，而是把产物 stage 到 `<TAWC 仓库>/app/src/main/jniLibs/<abi>/
#      libtawcroot.so`；
#   2. 不传 `--abi=` 时默认构建**宿主机**（glibc），不是 Android；
#   3. 它 source `<TAWC>/scripts/lib/deps.sh` 并 `dep_ensure cleat`（联网 clone cleat）。
# 所以这里必须 clone 整棵仓库、显式传 `--abi=aarch64`，再去它 stage 的位置取文件 ——
# 在 `tawcroot/` 目录里 `find -name tawcroot` 是找不到的（第一次就是这么红的）。
set -euo pipefail

TAWC_REPO="https://github.com/wmww/tawc"
TAWC_REF="${TAWC_REF:-v6}"   # 钉死的 tag；换版本时改这里，并在真机上重跑探针
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/app/src/main/jniLibs/arm64-v8a}"

# ---------------------------------------------------------------- NDK
if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "${ANDROID_NDK_HOME:-}" ]; then
  for base in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "/usr/local/lib/android/sdk"; do
    [ -n "$base" ] && [ -d "$base/ndk" ] || continue
    ANDROID_NDK_HOME="$(find "$base/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n1)"
    [ -n "$ANDROID_NDK_HOME" ] && break
  done
fi
if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "需要 NDK：设 ANDROID_NDK_HOME，或让 \$ANDROID_HOME/ndk/ 下有一个 NDK（推荐 27.2.12479018）" >&2
  exit 2
fi
export ANDROID_NDK_HOME
echo "NDK: $ANDROID_NDK_HOME"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
git clone --depth 1 --branch "$TAWC_REF" "$TAWC_REPO" "$WORK/tawc"
echo "tawc @ $TAWC_REF = $(git -C "$WORK/tawc" rev-parse HEAD)"

# 上游脚本按 $REPO_DIR/app/src/main/jniLibs/<abi>/ 放产物；`--abi=aarch64` 是必须的
# （不传就是宿主机构建）。
( cd "$WORK/tawc/tawcroot" && bash ./build.sh --abi=aarch64 )

BIN="$WORK/tawc/app/src/main/jniLibs/arm64-v8a/libtawcroot.so"
if [ ! -f "$BIN" ]; then
  # 上游改了 stage 位置时给一条能直接读懂的失败，而不是“文件不存在”。
  echo "上游没有把产物放到预期位置：$BIN" >&2
  find "$WORK/tawc/app" -name 'libtawcroot.so' -o -name 'tawcroot' 2>/dev/null | head -5 >&2
  exit 1
fi

# 静态、非 PIE：动态链接的二进制在 Android 上找不到 ld.so；PIE 会破坏 seccomp 里写死的
# 返回地址（见上游 notes/tawcroot/sigsys-handler.md）。
if ! file "$BIN" | grep -q 'ELF 64-bit.*ARM aarch64'; then
  echo "产物架构不对：$(file "$BIN")" >&2
  exit 1
fi
if file "$BIN" | grep -q 'dynamically linked'; then
  echo "产物是动态链接，Android 上起不来：$(file "$BIN")" >&2
  exit 1
fi
if readelf -h "$BIN" | grep -q 'Type:[[:space:]]*DYN'; then
  echo "产物是 PIE（ET_DYN），seccomp 的固定返回地址会失效：$(readelf -h "$BIN" | grep Type:)" >&2
  exit 1
fi

mkdir -p "$OUT"
install -m 0755 "$BIN" "$OUT/libtawcroot.so"
echo "已写入 $OUT/libtawcroot.so ($(stat -c %s "$OUT/libtawcroot.so") 字节)"
