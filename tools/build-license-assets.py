#!/usr/bin/env python3
"""Assemble the in-app open-source licence assets from the *distributed* bytes.

Why this exists
---------------
The APK redistributes third-party software: a Linux userland, Node, git, proot,
a pi engine and a number of JVM libraries. Redistribution carries obligations —
include the licence text, keep the copyright notices, and for the copyleft
programs say where the corresponding source is. Those obligations belong to
*this* app, not to pi, so nothing here mirrors a pi feature.

The texts must not be typed from memory. Every file this script writes is either

  * read out of an upstream artifact that the app actually ships (a `.deb`, the
    Ubuntu base tarball, a Node/ripgrep/fd release tarball), or
  * a canonical text taken verbatim from that same Ubuntu base payload
    (`/usr/share/common-licenses/*`), which is how Ubuntu itself ships them, or
  * a small number of npm package licence files, fetched at a pinned version
    (see NPM_LICENCE_FILES) because they belong to the pi engine's dependency
    closure and appear in no other downloaded artifact, or
  * the OFL-1.1 text of the JetBrains Mono font compiled into the APK, fetched at a
    pinned release tag (see JETBRAINS_MONO_LICENCE_URL) because the canonical OFL is
    in no artifact this app ships — `/usr/share/common-licenses/` carries 17 texts
    and the OFL is not one of them.

`runtime.lock.json` is the pin list; `tools/fetch-runtime.mjs` populates
`build/downloads/`. Run that first, then this script. Nothing is compiled and
Gradle is never invoked.

Output is `app/src/main/assets/licenses/` and is flat on purpose: the settings
screen lists the directory through `manifest.txt`, so adding a licence needs no
Kotlin change.

Usage:
    python3 tools/build-license-assets.py
    python3 tools/build-license-assets.py --fetch-missing   # allow unpkg / GitHub raw for pinned texts
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOWNLOADS = os.path.join(ROOT, "build", "downloads")
NPM_CACHE = os.path.join(DOWNLOADS, "npm-licences")
FONT_CACHE = os.path.join(DOWNLOADS, "font-licences")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "licenses")
LOCK = os.path.join(ROOT, "runtime.lock.json")
ENGINE_PAYLOAD = os.path.join(ROOT, "app", "src", "main", "assets", "runtime", "pi-engine.tgz")


def pinned_constant(name: str) -> str:
    """Read `const <name> = "...";` out of `tools/fetch-runtime.mjs`.

    Every version this script talks about is read from there rather than repeated here,
    so a bump has one place to change and this script cannot silently pin a licence from
    a different release than the artifact it describes.
    """
    fetch_script = os.path.join(ROOT, "tools", "fetch-runtime.mjs")
    try:
        text = open(fetch_script, encoding="utf-8").read()
    except OSError as error:
        sys.exit(f"cannot read {fetch_script}: {error}")
    m = re.search(rf'^const {name} = "([^"]+)";', text, re.M)
    if not m:
        sys.exit(f"{name} not found in tools/fetch-runtime.mjs; the pin must live in exactly one place")
    return m.group(1)


def engine_version() -> str:
    """The pi version the engine payload is built from."""
    return pinned_constant("PI_VERSION")


def proroot_version() -> str:
    """The proroot release tag the five binaries are pinned to."""
    return pinned_constant("PROROOT_VERSION")


def engine_payload_versions(packages: list[str]) -> dict[str, str]:
    """The version each named `@earendil-works/*` package **actually has in the payload**.

    Read out of the archive this app ships (`assets/runtime/pi-engine.tgz`) rather than
    from a fresh `npm install`: the point of the check is that the bytes the APK carries
    and the release a licence notice names are the same one. A stale local payload is not
    hypothetical — it is the state this repository was in when the 0.87.1 audit started:
    `PI_VERSION` said 0.86.1 while `pi-engine.tgz` still held 0.85.1.

    `npm` nests them (`node_modules/@earendil-works/pi-coding-agent/node_modules/…`), so
    each package is matched by the *suffix* `node_modules/<name>/package.json`.
    """
    suffixes = [(name, f"node_modules/{name}/package.json") for name in packages]
    found: dict[str, str] = {}
    with tarfile.open(ENGINE_PAYLOAD, "r:gz") as tar:
        for member in tar:
            if not member.isfile():
                continue
            path = member.name[2:] if member.name.startswith("./") else member.name
            for name, suffix in suffixes:
                if name in found or not path.endswith(suffix):
                    continue
                handle = tar.extractfile(member)
                if handle is None:
                    continue
                found[name] = json.loads(handle.read())["version"]
            if len(found) == len(suffixes):
                break
    return found


def verify_engine_payload_versions() -> None:
    """Refuse to describe a release the shipped payload is not.

    The six `@earendil-works/*` rows are the packages `tools/fetch-runtime.mjs` installs
    and tars; this build only ever reads `PI_VERSION` for them (see
    `PI_ENGINE_NO_LICENCE_TEXT`), so before this check nothing could notice that the
    archive was one release behind the pin. CI runs the fetch step before this script, so
    the only way to hit it is a local run against a stale `assets/runtime/` — which is
    exactly when a wrong version label would otherwise be written, reviewed and shipped.
    """
    packages = [name for name, _version, _licence in PI_ENGINE_NO_LICENCE_TEXT if name.startswith("@earendil-works/")]
    if not os.path.isfile(ENGINE_PAYLOAD):
        sys.exit(
            f"missing {os.path.relpath(ENGINE_PAYLOAD, ROOT)}\n"
            f"  run `node tools/fetch-runtime.mjs` first: this script compares the engine payload's own\n"
            f"  package.json files against PI_VERSION, because a licence notice built from anything else\n"
            f"  describes a release the APK does not carry."
        )
    pinned = engine_version()
    actual = engine_payload_versions(packages)
    for name in packages:
        got = actual.get(name)
        if got != pinned:
            sys.exit(
                f"{name}: PI_VERSION is {pinned}, the payload has {got or 'no package.json'}\n"
                f"  {os.path.relpath(ENGINE_PAYLOAD, ROOT)} is stale. Run `node tools/fetch-runtime.mjs`\n"
                f"  (it installs @earendil-works/pi-coding-agent@{pinned} and re-tars the payload)."
            )

# npm packages in pi's dependency closure whose licence is not otherwise present
# in a downloaded artifact. Versions are the ones pi 0.87.1 pins in its shipped
# shrinkwrap; a bump must move both the version and the pi version together.
# (Checked at 0.87.1: tslib 2.8.1 and lru-cache 11.4.0 are unchanged, so this table
# did not move with the bump.)
#   tslib       0BSD          (also: `Unlicense` is taken from ripgrep's tarball)
#   lru-cache   BlueOak-1.0.0
#   minimatch   BlueOak-1.0.0  (ships its own LICENSE.md, so no entry here)
NPM_LICENCE_FILES = [
    ("0BSD.txt", "tslib", "2.8.1", "LICENSE.txt"),
    ("BlueOak-1.0.0.txt", "lru-cache", "11.4.0", "LICENSE.md"),
]

# JetBrains Mono 2.304 (OFL-1.1) — the machine-language typeface, compiled into the
# APK as `app/src/main/res/font/jetbrains_mono_{regular,bold}.ttf`. It is bundled
# rather than taken from the ROM because the system monospace is a per-device choice
# and the app's diffs size fixed columns to the advance width. Redistributing the
# font is what creates the obligation: the OFL requires the licence text to travel
# with the font. Unlike the GPL/LGPL/Apache/MPL texts there is no copy of the OFL in
# anything the app ships — `/usr/share/common-licenses/` in the pinned Ubuntu base
# carries 17 files and the OFL is not among them — so it is fetched at the pinned
# release tag. Upstream: https://github.com/JetBrains/JetBrainsMono
# The tag's `OFL.txt` is byte-identical to the `OFL.txt` inside
# `JetBrainsMono-2.304.zip`, i.e. the same release the two TTFs were extracted from
# (checked while writing this pin; that is the point of pinning the tag, not master).
JETBRAINS_MONO_VERSION = "2.304"
JETBRAINS_MONO_LICENCE_URL = "https://raw.githubusercontent.com/JetBrains/JetBrainsMono/v{version}/OFL.txt"
JETBRAINS_MONO_LICENCE_SHA256 = "30f0c136e3c88e422d0791acd97238870f9054a9729bc34cf2ff0d4ed8cac4ad"
# Component-named, like the `ubuntu-copyright-<pkg>.txt` rows: the text carries the
# JetBrains copyright line, so it is *this font's* copy of the OFL, not a generic
# `OFL-1.1.txt` that a second OFL font could not reuse without becoming a lie.
JETBRAINS_MONO_LICENCE_OUT = "JetBrainsMono-OFL-1.1.txt"
JETBRAINS_MONO_LICENCE_TITLE = f"JetBrains Mono {JETBRAINS_MONO_VERSION}（OFL-1.1）"

# pi's own licence text, which the published npm tarball **does not contain**: the
# MIT text lives at the monorepo root and `packages/coding-agent` is published
# without a copy, so the engine payload carries a `license: MIT` field and no
# notice — while MIT requires the notice. We therefore ship it ourselves, from the
# upstream tag matching the engine version. Verified: this URL's bytes are
# identical to `/root/pi-src/LICENSE` at pi 0.85.1 (sha256 0457f5bcec3b3b21…); the
# v0.86.1 and v0.87.1 tags' LICENSE files were re-fetched and are **byte-identical**
# (same sha256), so the text we distribute has not changed across either bump.
PI_LICENCE_URL = "https://raw.githubusercontent.com/earendil-works/pi/v{version}/LICENSE"
PI_LICENCE_SHA256 = "0457f5bcec3b3b211605dfb5d1a49042fd638f3686a410fe099c24a25af13c48"

# proroot — the one component in the APK that is neither open source nor a standard
# licence text, and the one whose licence is carried by **no artifact this app ships**:
# its LICENSE lives in the upstream repository, and it is a bespoke proprietary notice
# rather than a text from `/usr/share/common-licenses`. Pinned by release tag + sha256
# exactly like the JetBrains Mono OFL and the pi LICENSE above, cached, fetched only
# under `--fetch-missing`, and a mismatch is a hard stop.
#
# Two things have to be produced for it, and neither may be typed from memory:
#
#   * `proroot-license.txt` — the licence text itself, so the "include this notice in all
#     copies" condition upstream imposes is met by the app rather than by this comment.
#   * `proprietary-third-party.txt` — the registration: package locations, the digests of
#     the five shipped binaries, why they have to ship inside the APK, which licence
#     duties the app discharges, and the known limits.
#
# The checks that keep those honest, and why each is where it is:
#
#   * the version is read out of `tools/fetch-runtime.mjs` (`proroot_version()`), never
#     repeated here, so the notice cannot describe a release other than the pinned one;
#   * the five digests and byte counts are read out of `runtime.lock.json` **and** the
#     downloaded bytes (`artifact()`), never repeated here, so the notice cannot describe
#     bytes other than the ones in the APK. `tools/fetch-runtime.mjs` writes those pins
#     only after checking the bytes against the digests upstream publishes.
#
# Read the README's one-line "License" summary as the whole licence and proroot looks
# like a redistribution grey area. It is not: the repository's LICENSE permits
# redistribution of the *unmodified* binaries as part of a complete application package,
# forbids modified ones, and adds the notice + attribution duties above. Attribution is
# discharged by the component list and this registration both naming "proroot", which is
# the third of the three places upstream accepts.
PROROOT_LICENCE_OUT = "proroot-license.txt"
PROROOT_LICENCE_URL = "https://raw.githubusercontent.com/coderredlab/proroot/{version}/LICENSE"
PROROOT_LICENCE_SHA256 = "0576d93d18783c1dd677c1d412757a20a3098a2585590b760e444201a3ff565e"
PROROOT_CACHE = os.path.join(DOWNLOADS, "proroot-licences")

# The five release assets, in the order the registration lists them. The first field is
# the `runtime.lock.json` artifact name — that is what makes the digests in the notice
# come from the pin and the bytes instead of from this file. The second is the name the
# file lands under in the APK, which must stay exactly as upstream ships it: proroot
# finds the linker, the hook and the stub loader by fixed name in the directory of
# `/proc/self/exe`, i.e. in nativeLibraryDir.
# (key, name in the APK, what it does)
PROROOT_FILES = [
    ("prorootLauncher", "libproroot.so", "启动器（launcher / CLI）—— Android 实际 execve 的就是它，也是所有客户机进程的父进程"),
    ("prorootRuntime", "libproroot-runtime.so", "进程内 hook（LD_PRELOAD 语义）：路径翻译、伪装 id 0、/proc 合成"),
    ("prorootLinker", "libproroot-linker.so", "客户机二进制的 ELF 解释器（PT_INTERP）"),
    ("prorootBridge", "libproroot-bridge.so", "入口 trampoline（PROROOT_TRAMPOLINE_PATH），子进程重新进入的点"),
    ("prorootStubLoader", "libproroot-stub-loader.so", "静态 / static-pie 与 execve 路由加载器（上游可选，文件存在时自适应启用）"),
]

# Packages in the engine payload that **declare** a licence but ship **no licence
# file**. Derived, not guessed: the `pi-engine` step of tools/fetch-runtime.mjs was
# run in an isolated directory —
#     npm install --ignore-scripts --omit=dev --omit=optional \
#       @earendil-works/pi-coding-agent@0.87.1
# — and npm's own installed list (`node_modules/.package-lock.json`) was audited:
# 118 packages installed, 106 shipping a licence file, these 12 not. Every one of
# the 12 still declares a field, so this is "no text", never "no licence"; and for
# the 106 that do ship one, the text was checked to support the declared id (0
# mismatches at 0.86.1). Pinned here because the assets must build without npm;
# re-derive on a version bump (docs/known-gaps.md §L5).
#
# What the 0.86.1 → 0.87.1 bump changed: **only the six `@earendil-works/*`
# versions**. The set itself was re-derived at 0.87.1 (same 118 packages, the same
# 12 without a licence file, and the three `@aws-sdk/*` versions below are still the
# ones 0.87.1's shrinkwrap pins: 3.972.72 / 3.972.77 / 3.997.44). The previous bump
# (0.85.1 → 0.86.1) had moved those three `@aws-sdk/*` versions and swapped
# `@nodable/entities` + `xml-naming` out for `proxy-agent-negotiate`.
#
# The six versions come from `PI_VERSION`, not from this file: they are the packages
# `tools/fetch-runtime.mjs` installs at that exact version, so repeating the number
# here is what left this list describing 0.86.1 after the pin had moved. Keeping them
# derived is only half the guard — `verify_engine_payload_versions()` also reads the
# shipped payload, so a *stale archive* cannot be described with a current label.
PI_ENGINE_NO_LICENCE_TEXT = [
    *((name, engine_version(), "MIT")
      for name in (
          "@earendil-works/pi-coding-agent",
          "@earendil-works/chord",
          "@earendil-works/pi-agent-core",
          "@earendil-works/pi-ai",
          "@earendil-works/pi-telemetry",
          "@earendil-works/pi-tui",
      )),
    ("data-uri-to-buffer", "4.0.1", "MIT"),
    ("proxy-agent-negotiate", "1.1.0", "MIT"),
    ("standardwebhooks", "1.1.1", "MIT"),
    ("@aws-sdk/credential-provider-http", "3.972.72", "Apache-2.0"),
    ("@aws-sdk/credential-provider-login", "3.972.77", "Apache-2.0"),
    ("@aws-sdk/nested-clients", "3.997.44", "Apache-2.0"),
]

# Where the notice for each of those packages can actually be found, and what was
# verified. "No licence file in the package" is not the same as "no notice exists":
# most of these have an upstream file or a sibling in the same payload that carries
# one, and the ones that do not are named as such. Every URL here was fetched while
# writing this table and its sha256 is the bytes that came back, so the claim can be
# re-checked instead of trusted.
#
# (package, version, licence, notice sentence, source url, sha256 or "" when none)
PI_ENGINE_NOTICES = [
    (
        "6 × @earendil-works/* 包",
        engine_version(),
        "MIT",
        f"正文与版权声明见列表里的『pi 引擎 {engine_version()}（MIT）』那一份：这六个包与 pi 引擎出自同一 monorepo，根 LICENSE 即它们的许可文本（已按 v{engine_version()} 标签逐字节核对；该标签的 LICENSE 与 v0.85.1/v0.86.1 逐字节相同，sha256 未变）",
        f"https://raw.githubusercontent.com/earendil-works/pi/v{engine_version()}/LICENSE",
        "0457f5bcec3b3b211605dfb5d1a49042fd638f3686a410fe099c24a25af13c48",
    ),
    (
        "3 × @aws-sdk/* 包",
        "见上表",
        "Apache-2.0",
        "正文见列表里的『Apache-2.0』。AWS SDK 的 LICENSE 就是 Apache-2.0 模板本身，不含逐包版权行；"
        "同一载荷里 19 个 @aws-sdk 包有 16 个带该文件，与上游逐字节相同",
        "https://raw.githubusercontent.com/aws/aws-sdk-js-v3/main/LICENSE",
        "edea91454b811f127fbdea3d86f378f6719bd372ed440abf82b232f6fca06c3d",
    ),
    (
        "standardwebhooks",
        "1.1.1",
        "MIT",
        "版权声明取不到：包内 package.json 声明的是 MIT（npm 元数据同此），而它**不带**任何许可文件；"
        "上游仓库根部的 LICENSE 是 Apache-2.0 模板（c71d239d…，与本仓库 `licenses/apache-2.0.txt` 同源），"
        "那是规范仓库的许可、不是这个包的 MIT 声明，因此这里没有可指向的 MIT 原文",
        "",
        "",
    ),
    (
        "proxy-agent-negotiate",
        "1.1.0",
        "MIT",
        "版权声明取不到：声明的 MIT 与作者（Nathan Rajlich <nathan@tootallnate.net>，取自包内 package.json）可读，"
        "但上游仓库（TooTallNate/proxy-agents）的 LICENSE 在 main/master 以及 packages/negotiate 下均 404，无法确认版权行原文",
        "",
        "",
    ),
    (
        "data-uri-to-buffer",
        "4.0.1",
        "MIT",
        "版权声明取不到：声明的 MIT 与作者（Nathan Rajlich <nathan@tootallnate.net>，取自包内 package.json）可读，"
        "但上游仓库的 LICENSE 在各分支与路径上均 404，无法确认版权行原文",
        "",
        "",
    ),
]


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def artifact(name: str, lock: dict) -> str:
    """Path to a downloaded artifact, or a hard error naming the fix."""
    filename = os.path.basename(lock["artifacts"][name]["url"])
    path = os.path.join(DOWNLOADS, filename)
    if not os.path.isfile(path):
        sys.exit(
            f"missing {filename}\n"
            f"  run `node tools/fetch-runtime.mjs` first: the licence texts are read out of\n"
            f"  the exact artifacts this app ships, never typed from memory."
        )
    pinned = lock["artifacts"][name]["sha256"]
    got = sha256(path)
    if got != pinned:
        sys.exit(f"{filename}\n  pinned {pinned}\n  actual {got}\n  refusing to build assets from an unpinned artifact")
    return path


def run(argv: list[str]) -> None:
    subprocess.run(argv, check=True, capture_output=True)


# --------------------------------------------------------------------------- #
# reading licence material out of the artifacts
# --------------------------------------------------------------------------- #


def unpack_deb(deb: str, into: str) -> None:
    os.makedirs(into, exist_ok=True)
    run(["dpkg-deb", "-x", deb, into])


def deb_control(deb: str) -> dict[str, str]:
    out = subprocess.run(["dpkg-deb", "-f", deb], check=True, capture_output=True, text=True).stdout
    fields: dict[str, str] = {}
    for key in ("Package", "Version", "Homepage"):
        m = re.search(rf"^{key}: (.+)$", out, re.M)
        if m:
            fields[key] = m.group(1).strip()
    return fields


def find_copyright(root: str) -> str | None:
    for dirpath, _dirnames, filenames in os.walk(os.path.join(root, "usr", "share", "doc")):
        for name in filenames:
            if name == "copyright":
                return os.path.join(dirpath, name)
    return None


def dep5_stanzas(text: str) -> dict[str, str]:
    """Every `License: <name>` stanza in a machine-readable copyright file.

    DEP-5 body lines are indented by one space and a blank line is written as
    " ."; both are undone so the extracted text is the licence as published.
    """
    stanzas: dict[str, str] = {}
    lines = text.split("\n")
    i = 0
    while i < len(lines):
        m = re.match(r"^License:\s*(.+?)\s*$", lines[i])
        if not m:
            i += 1
            continue
        name = m.group(1).strip()
        i += 1
        body: list[str] = []
        while i < len(lines) and (lines[i][:1] in (" ", "\t") or lines[i].strip() == ""):
            body.append(lines[i])
            i += 1
        while body and body[-1].strip() == "":
            body.pop()
        cleaned = []
        for line in body:
            if line.strip() == ".":
                cleaned.append("")
            else:
                cleaned.append(line[1:] if line[:1] in (" ", "\t") else line)
        text_out = "\n".join(cleaned).rstrip()
        text_out = de_comment_prefix(text_out)
        if text_out and (name not in stanzas or len(text_out) > len(stanzas[name])):
            stanzas[name] = text_out
    return stanzas


def de_comment_prefix(text: str) -> str:
    """Drop a comment decoration that is not part of the licence.

    Some copyright files quote the licence out of a source header, so every line
    arrives as `* ...` (or `// ...`). When *every* non-empty line carries the same
    decoration it is comment syntax, not text, and the licence reads better
    without it. Mixed bodies are left exactly as published.
    """
    lines = text.split("\n")
    body = [line for line in lines if line.strip()]
    if not body:
        return text
    for prefix in ("* ", "*", "// ", "//", "# "):
        if all(line.lstrip().startswith(prefix) for line in body):
            stripped = []
            for line in lines:
                if not line.strip():
                    stripped.append("")
                    continue
                indent = line[: len(line) - len(line.lstrip())]
                rest = line.lstrip()[len(prefix) :]
                stripped.append((indent + rest).rstrip() if rest else "")
            return "\n".join(stripped).rstrip()
    return text


# --------------------------------------------------------------------------- #
# the two prose assets
# --------------------------------------------------------------------------- #

STATEMENT = """\
关于这份清单
============

本 App 的安装包里含有第三方软件，它们由各自的作者按各自的许可证授权使用。
这份清单把每一个组件、它的版本、它适用的许可证、以及它在 App 里的位置列出来，
并附上完整的许可证原文。

为什么要放在这里
----------------
分发别人的程序时，许可证通常要求我们做到三件事：保留版权声明、附上许可证原文、
并对要求提供源代码的程序说明源代码从哪里可以获得。这份清单就是为此而存在。

需要你知道的两件事
------------------
1. 这些程序都是未修改的上游发行版。它们以各自上游发布时的原始字节随 App 分发，
   我们没有改动过它们的代码，也没有在它们之上做二次开发。

2. 其中的 GPL / LGPL 程序，源代码可以从它们各自的上游发布地址获取，
   地址和版本都逐条写在「组件清单」里。若你需要某一份源代码而在上游找不到，
   可以按「获取源代码」里的方式向我们索取。
"""

SOURCE_OFFER = """\
获取源代码
==========

本 App 分发的 GPL、LGPL 与其他要求提供源代码的程序，全部是未修改的上游发行版。
对应的源代码可以从下面这些上游位置按相同版本取得。

Linux 运行时（Ubuntu 基础系统及其附加软件包）
--------------------------------------------
来源：Ubuntu 24.04.3 (noble) 官方软件源，arm64 / all 架构。
地址：https://ports.ubuntu.com/ubuntu-ports/ 与 https://archive.ubuntu.com/ubuntu/
取法：在任一地址下按 池/源码包名 找到与「组件清单」中版本号相同的源码包
      （.dsc 与 .orig.tar.* 等文件），或使用
      apt-get source <软件包名>=<版本号>
      在 Ubuntu 24.04 环境下获取。

git
---
来源：Ubuntu 24.04.3 (noble-updates)，版本 2.43.0-1ubuntu7.3。
上游：https://git-scm.com/  与  https://git.kernel.org/pub/scm/git/git.git
Ubuntu 源码包：https://ports.ubuntu.com/ubuntu-ports/pool/main/g/git/

proot
-----
来源：Termux 软件源，版本 5.1.107.92。
上游：https://github.com/termux/proot  （标签 v5.1.107.92）
Termux 打包脚本：https://github.com/termux/termux-packages/tree/master/packages/proot

libtalloc
---------
来源：Termux 软件源，版本 2.4.3。
上游：https://talloc.samba.org/   源码：https://www.samba.org/ftp/talloc/

libandroid-shmem
----------------
来源：Termux 软件源，版本 0.7。
上游：https://github.com/termux/libandroid-shmem  （标签 v0.7）

proroot（无源代码可提供）
------------------------
来源：GitHub release，版本 {proroot}。
仓库：https://github.com/coderredlab/proroot
这个组件**闭源**，上游未公开源码，因此无法提供对应的源代码。它的许可证不是
GPL/LGPL 一类要求提供源代码的许可证，而是一份专有条款；原文、包内位置、
sha256 与已知限制写在「随包分发的专有组件」那一份里。

其余随 App 分发的程序
---------------------
Apache-2.0、MIT、BSD、ISC 等宽松许可证不要求提供源代码。它们的许可证原文与版权声明
一并附在本清单中；需要源代码时，按「组件清单」里给出的上游地址获取即可。

联系方式
--------
若以上路径无法取得你需要的源代码，请通过本 App 的发布页面联系我们，
我们会按许可证的要求提供对应的源代码。
"""


def components_text(rows: list[tuple[str, str, str, str, str]]) -> str:
    """rows: (component, version, licence, where it ships, upstream source)."""
    out = [
        "组件清单",
        "========",
        "",
        "每一行：组件 · 版本 · 许可证 · 在 App 里的位置 · 上游发布地址（也是源代码地址）。",
        "",
    ]
    current = None
    for component, version, licence, where, source in rows:
        if where != current:
            current = where
            out.append(f"■ {where}")
            out.append("-" * (len(where) + 2))
        out.append(f"  {component}")
        out.append(f"      版本      {version}")
        out.append(f"      许可证    {licence}")
        out.append(f"      上游      {source}")
        out.append("")
    return "\n".join(out)


def proprietary_registration(
    version: str,
    files: list[tuple[str, str, str, int]],
    licence_sha256: str,
) -> str:
    """The registration for proroot: the one component that is not open source.

    `files` is `(name in the APK, what it does, sha256, bytes)` for each of the five
    release assets. All four come from the pin and the downloaded bytes — see
    `PROROOT_FILES` and the `artifact()` loop that calls this — so the digests in the
    text cannot describe bytes other than the ones in the APK. The prose around them is
    the part that has to be written by hand, and the "已知限制" list is the record of
    what was actually measured, in `docs/proroot-research.md`.

    Why this is generated rather than hand-written like any other notice: the CI job that
    verifies the licence assets rebuilds this whole directory from the pinned artifacts
    and fails on any drift (`.github/workflows/ci.yml`, "Verify the licence assets match
    the pinned runtime"), so a hand-edited notice is a notice that disappears.
    """
    width = max(len(name) for name, *_ in files) + 2
    total = sum(size for *_, size in files)
    out = [
        "随包分发的专有组件",
        "==================",
        "",
        "本 App 的 APK 里有一个组件不是开源的。它有自己的许可证，条款摘录在下面；",
        f"许可原文（不含本文件）逐字收录在「许可证全文」里的 {PROROOT_LICENCE_OUT}。",
        "",
        "这一份登记要说清的是：它是谁、从哪来、包里是哪几个文件、字节是什么、",
        "为什么必须放在包里、本 App 履行了它的哪些义务，以及它有哪些已知限制。",
        "",
        "proroot",
        "-------",
        "",
        "  来源仓库  https://github.com/coderredlab/proroot",
        f"  版本      {version}",
        f"  发行页    https://github.com/coderredlab/proroot/releases/tag/{version}",
        "  许可文件  https://github.com/coderredlab/proroot/blob/main/LICENSE",
        f"            sha256 {licence_sha256}",
        f"            （随包的 {PROROOT_LICENCE_OUT} 是它的逐字副本，同一哈希）",
        "",
        "  它是什么  一个面向 Android 的用户态容器运行时，是 proot 的替代实现。",
        "            本 App 默认使用 proot；proroot 是可选运行时，目前尚未接入启动路径。",
        "",
        "  许可证    Proprietary（专有，闭源）。上游 LICENSE 的核心三条原文：",
        "",
        "                1. The Software may be used without restriction in any application.",
        "                2. Redistribution of modified versions of the Software is not permitted.",
        "                3. Redistribution of the unmodified Software is permitted only as part",
        "                   of a complete application package (e.g., Android APK/AAB).",
        "",
        "            也就是说：**未修改的二进制随完整应用包（APK/AAB）分发是明确允许的**；",
        "            被禁止的是分发修改过的版本。本 App 分发的正是上游 release 的原始二进制，",
        "            没有改名、没有重新链接、没有打补丁（字节校验见下）。",
        "",
        "            需要注意一处容易读错的地方：上游 README 的 License 一节只有一句摘要 ——",
        '            *"Proprietary. Free to use in your projects. Redistribution of modified',
        '            binaries is not permitted."* —— 那句话**不是**完整条款。只看它会以为',
        '            "原样再分发"没有被授权；完整的允许条件写在仓库的 LICENSE 文件里，',
        "            第 3 条明确覆盖了随 APK 分发。",
        "",
        "  本 App 履行的两项义务",
        "            上游 LICENSE 第 4、5 条是随包分发的前提条件，本 App 都做到了：",
        "",
        "              第 4 条  许可声明必须随所有副本一起分发。",
        f"                       → {PROROOT_LICENCE_OUT} 逐字随包，并登记在「许可证全文」。",
        '              第 5 条  使用方必须在「应用说明 / 关于页或设置页 / 第三方许可清单」',
        '                       三者之一中向 "proroot" 署名。',
        "                       → 组件清单里有一条 proroot，本文件也通篇署名，两者都在",
        "                         设置 → 开源许可 里呈现给用户。",
        "",
        "  包内位置  lib/arm64-v8a/ 下五个文件，文件名与上游 release 资产同名：",
        "",
    ]
    for name, role, _digest, _size in files:
        out.append(f"                {name:<{width}}{role}")
    out += [
        "",
        "            它们在 APK 里的路径是 lib/arm64-v8a/，安装后由系统释放到",
        "            nativeLibraryDir（应用私有、可执行）。",
        f"            五个文件合计 {total:,} 字节。文件名必须保持原样：proroot 按固定",
        "            文件名在 nativeLibraryDir 里自动发现 linker / runtime / stub loader，",
        "            改名会让它在设备上起不来。",
        "",
        "  sha256    由本项目的构建脚本（tools/fetch-runtime.mjs）在下载后对实际字节计算，",
        "            并与上游 release 说明中公布的 SHA-256 逐一比对，两者必须一致，",
        "            否则构建失败、不落地；同样由它写进 runtime.lock.json 的 pin 条目。",
        "            本文件里的这五个值就是从那份 pin 与随包的字节直接算出来的。",
        "",
        "            这一点值得解释：本项目的另一个参考实现（DSHA）公开声明它分发的是",
        f"            {version} 的原始二进制，但实测其设备上正在运行的字节与任何公开 release",
        "            都不符（见 docs/proroot-research.md §3.1）。所以哈希不能抄任何一方，",
        "            必须对**我们自己打包的那一份**计算并登记。",
        "",
    ]
    for name, _role, digest, _size in files:
        out.append(f"                {name:<{width}}{digest}")
    out += [
        "",
        "            以上五个值与上游 release 说明里的 SHA-256 区块逐一一致，与 GitHub 为该",
        "            release 资产提供的摘要一致，与上游仓库 arm64-v8a/ 目录下同名文件的字节",
        "            也完全相同 —— 三处互相印证。核对方式可复核：每次构建都会重新下载并重算，",
        "            与上游公布的摘要和 runtime.lock.json 的 pin 同时比对，任一不一致即失败。",
        "",
        "  为什么随包分发（而不是按需下载）",
        "            因为按需下载在 Android 上做不到。Android 10 起的 W^X 策略不允许从",
        "            应用可写目录（filesDir）执行代码：下载到那里的 .so 无法执行，只有放进",
        "            APK 的 jniLibs、由系统提取到 nativeLibraryDir 才能跑。本 App 现有的",
        "            proot 出于同一条理由放在同一个位置。这也正好落在上游 LICENSE 第 3 条",
        "            允许的范围内 —— 作为完整应用包的一部分。",
        "",
        "  为什么它不会影响可用性",
        "            装机与首次解包路径永远走 proot；proroot 只可能作用于「执行命令」",
        "            这一层，而且默认关闭。运行时文件缺失、或自检不通过时自动回退 proot。",
        "",
        "  附：源码不公开",
        "            上游未公开 proroot 的源码（仓库里只有五个二进制、README 和 LICENSE），",
        "            因此无法提供对应的源代码，也无法自行审计或修补。「获取源代码」那一份",
        "            里有对应说明。",
        "",
        "### 已知限制",
        "",
        "  - 上游未公开源码，无法审计。出问题只能等作者修，我们自己改不了。",
        "  - 作者已把开发重心转向另一个项目（proroom），上游明说更新会变慢。",
        "  - 因闭源，Termux 官方仓库拒绝收录（proroot issue #21）。",
        "  - 它靠 LD_PRELOAD 语义的函数拦截 + 加载时改写主可执行文件里的 inline `svc`",
        "    指令做路径翻译，**绕过 libc 的裸 syscall 不被翻译**：实测裸 openat 读到的是",
        "    宿主 Android 的文件，而不是客户机里的同名文件。这是静默失败，不是报错。",
        "  - /proc 的一部分内容是它合成的：/proc/version 的构建者字段、/proc/<pid>/exe、",
        "    /proc/self/maps。任何靠它们判断环境的逻辑在 proroot 下会得到不同答案。",
        "  - 只有 arm64-v8a：没有 32 位对应物。",
        "  - 要求 Android 8.0+ / arm64 / glibc 的 Ubuntu 客户机；本项目三项都满足",
        "    （minSdk 26、仅 arm64-v8a、Ubuntu 24.04 arm64 / glibc 2.39）。",
        "",
        "  这些限制的实测依据与完整清单在 docs/proroot-research.md（§4.3、§5、§6.6）。",
    ]
    return "\n".join(out) + "\n"


# --------------------------------------------------------------------------- #
# assembly
# --------------------------------------------------------------------------- #

MANIFEST_HEADER = "# 文件名\t标题\t分区\n"


def set_output(path: str) -> None:
    """Point `OUT` at the directory `build()` should fill.

    A module-level global because every write in `build()` addresses its destination as
    `os.path.join(OUT, name)`, and the install step below needs to redirect all of them
    at once without threading a second argument through the whole function.
    """
    global OUT
    OUT = path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--fetch-missing",
        action="store_true",
        help="download the pinned licence texts that no artifact carries "
        "(NPM_LICENCE_FILES, the pi LICENSE, the JetBrains Mono OFL, the proroot LICENSE)",
    )
    args = parser.parse_args()

    if not os.path.isfile(LOCK):
        sys.exit("runtime.lock.json not found")
    lock = json.load(open(LOCK))

    # Before anything is written: the engine payload the APK ships has to be the release
    # every engine-derived version string in this file claims (see
    # verify_engine_payload_versions). CI runs the fetch step first, so this only fires on
    # a local run against a stale `assets/runtime/` — which is exactly the case that
    # shipped a 0.86.1 label over a 0.85.1 archive.
    verify_engine_payload_versions()

    # Assemble into scratch space and install only after every check has passed.
    #
    # `build()` writes the directory from scratch, so building in place means a hard
    # stop part-way through — a pin mismatch, a missing artifact, an unpinned download —
    # leaves the committed licence assets deleted and half-rebuilt. That is not
    # hypothetical: the first negative test of the proroot pin did exactly that to this
    # working tree. Those files are reproducible, but a failure that also destroys the
    # previous good output is a failure that hides what it was checking. Nothing is
    # touched here until `build()` has returned.
    final = OUT
    stage = tempfile.mkdtemp(prefix="pi-licences-")
    working = os.path.join(stage, "out")
    set_output(working)
    try:
        build(lock, args.fetch_missing, stage)
        # Only reachable once `build()` returned, i.e. once every pin, artifact and
        # coverage check in it has passed.
        set_output(final)
        shutil.rmtree(final, ignore_errors=True)
        shutil.copytree(working, final)
    finally:
        set_output(final)
        shutil.rmtree(stage, ignore_errors=True)

    print(f"  installed into {os.path.relpath(final, ROOT)}")


def build(lock: dict, fetch_missing: bool, stage: str) -> None:
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT, exist_ok=True)

    manifest: list[tuple[str, str, str]] = []
    notes: list[str] = []

    # ---------------------------------------------------------------- base payload
    base = artifact("ubuntuBase", lock)
    common = os.path.join(stage, "common")
    docs = os.path.join(stage, "docs")
    status_dir = os.path.join(stage, "status")
    for target in (common, docs, status_dir):
        os.makedirs(target, exist_ok=True)
    # Ubuntu's base image points three packages' documentation at another package
    # instead of shipping a second copy: `usr/share/doc/libgcc-s1` and
    # `.../libstdc++6` are symlinks to `gcc-14-base`, and `.../libncursesw6` is a
    # symlink to `libtinfo6` (the same deb confirms it — `libncursesw6` has no
    # copyright of its own and a `-> libtinfo6` doc entry). An earlier version of
    # this script looked only for `usr/share/doc/<pkg>/copyright`, concluded those
    # three packages ship no licence text, and was **wrong**: the text is there, one
    # symlink away. Following the link is what makes all 91 base packages covered.
    doc_links: dict[str, str] = {}
    with tarfile.open(base, "r:gz") as tar:
        for member in tar.getmembers():
            if member.name.startswith("usr/share/common-licenses/"):
                tar.extract(member, common, filter="data")
            elif re.match(r"usr/share/doc/[^/]+/copyright$", member.name):
                tar.extract(member, docs, filter="data")
            elif re.match(r"^usr/share/doc/[^/]+$", member.name) and member.issym():
                doc_links[os.path.basename(member.name)] = os.path.basename(member.linkname)
            elif member.name == "var/lib/dpkg/status":
                tar.extract(member, status_dir, filter="data")

    # Canonical texts, verbatim from the payload's own /usr/share/common-licenses.
    common_licences = {
        "GPL-2.0.txt": "GPL-2",
        "GPL-3.0.txt": "GPL-3",
        "LGPL-2.1.txt": "LGPL-2.1",
        "LGPL-3.0.txt": "LGPL-3",
        "MPL-2.0.txt": "MPL-2.0",
        "Apache-2.0.txt": "Apache-2.0",
    }
    for out_name, src_name in common_licences.items():
        src = os.path.join(common, "usr", "share", "common-licenses", src_name)
        if not os.path.isfile(src):
            notes.append(f"common-licences/{src_name} absent from the base payload")
            continue
        shutil.copyfile(src, os.path.join(OUT, out_name))
        manifest.append((out_name, src_name, "许可证全文"))

    # ------------------------------------------------------------ per-package texts
    # Every deb the app ships a library out of, plus git. The copyright file is the
    # attribution record (DEP-5 for most of them) and, for the permissive licences,
    # also the only place the full text appears in the shipped bytes.
    deb_names = [
        n
        for n, spec in lock["artifacts"].items()
        if spec["url"].endswith(".deb") and n not in ("proot", "libtalloc", "libandroidShmem")
    ]
    stanzas: dict[str, str] = {}
    ubuntu_rows: list[tuple[str, str, str]] = []

    def add_copyright(pkg: str, version: str, path: str, via: str | None = None) -> None:
        """Copy one package's copyright file and give it its own manifest row.

        One file per package, not one file per distinct text. Deduplicating the
        bytes would look tidier, but it makes packages disappear from the list
        whenever two of them happen to share a copyright — which is exactly how the
        `libgssapi-krb5-2` / `libk5crypto3` / `libkrb5support0` family and these
        three symlinked packages went missing the first time. `via` names the
        package a symlinked doc directory resolves to, so the row can say why the
        text it opens is not titled after the package the user searched for.
        """
        name = f"ubuntu-copyright-{pkg}.txt"
        shutil.copyfile(path, os.path.join(OUT, name))
        title = f"{pkg} {version}" + (f"（版权文件由 {via} 提供）" if via else "")
        manifest.append((name, title, "软件包版权与许可"))

    for name in sorted(deb_names):
        deb = artifact(name, lock)
        ctl = deb_control(deb)
        pkg = ctl.get("Package", name)
        version = ctl.get("Version", "?")
        root = os.path.join(stage, "debs", name)
        unpack_deb(deb, root)
        cop = find_copyright(root)
        if cop is None:
            notes.append(f"{pkg} {version}: its package ships no copyright file")
            continue
        text = open(cop, encoding="utf-8", errors="replace").read()
        for key, value in dep5_stanzas(text).items():
            if key not in stanzas or len(value) > len(stanzas[key]):
                stanzas[key] = value
        add_copyright(pkg, version, cop)
        ubuntu_rows.append((pkg, version, ctl.get("Homepage", "")))

    # Ubuntu base packages: the copy inside the payload is the attribution record,
    # so it travels with the app; mirror it into the assets for the same reason.
    base_status = open(os.path.join(status_dir, "var", "lib", "dpkg", "status"), encoding="utf-8", errors="replace").read()
    base_pkgs: list[tuple[str, str]] = []
    for block in base_status.split("\n\n"):
        m = re.search(r"^Package: (.+)$", block, re.M)
        v = re.search(r"^Version: (.+)$", block, re.M)
        if m:
            base_pkgs.append((m.group(1), v.group(1) if v else "?"))
    by_name: dict[str, str] = {}
    for dirpath, _d, filenames in os.walk(os.path.join(docs, "usr", "share", "doc")):
        for fname in filenames:
            if fname == "copyright":
                pkg = os.path.basename(dirpath)
                by_name[pkg] = os.path.join(dirpath, fname)
    missing_base = []
    symlinked: list[str] = []
    for pkg, version in sorted(base_pkgs):
        if pkg in by_name:
            add_copyright(pkg, version, by_name[pkg])
        elif doc_links.get(pkg) in by_name:
            target = doc_links[pkg]
            add_copyright(pkg, version, by_name[target], via=target)
            symlinked.append(f"{pkg}→{target}")
        else:
            missing_base.append(f"{pkg} {version}")
    if symlinked:
        notes.append("base packages covered through a doc symlink: " + ", ".join(symlinked))
    if missing_base:
        notes.append("base packages with no copyright file: " + ", ".join(missing_base))
    lines = [f"{pkg}\t{version}" for pkg, version in sorted(base_pkgs)]
    write(os.path.join(OUT, "ubuntu-packages.txt"), "\n".join(lines) + "\n")
    manifest.append(("ubuntu-packages.txt", f"Ubuntu 软件包清单（{len(base_pkgs)} 个）", "清单"))

    # Permissive texts that appear only inside those copyright files.
    extract = {
        "MIT.txt": ["MIT"],
        "BSD-2-Clause.txt": ["BSD-2-clause", "BSD-2-Clause"],
        "BSD-3-Clause.txt": ["BSD-3-clause", "BSD-3-Clause"],
        "ISC.txt": ["ISC"],
        "Zlib.txt": ["Zlib", "zlib", "ZLIB"],
        "curl.txt": ["curl"],
        "X11.txt": ["X11"],
        "OpenLDAP-2.8.txt": ["OLDAP-2.8", "OpenLDAP-2.8"],
        "BSD-4-Clause-UC.txt": ["BSD-4-clause-UC", "BSD-4-Clause-UC"],
        "FSFULLR.txt": ["FSFULLR"],
        "all-permissive.txt": ["all-permissive"],
    }
    for out_name, keys in extract.items():
        for key in keys:
            if key in stanzas:
                write(os.path.join(OUT, out_name), stanzas[key].rstrip() + "\n")
                manifest.append((out_name, out_name[:-4], "许可证全文"))
                break
        else:
            notes.append(f"no {keys[0]} text found in the shipped copyright files")

    # ------------------------------------------------------------------ tarballs
    # ripgrep carries the Unlicense; it is the only place that text exists here.
    rg = artifact("ripgrep", lock)
    with tarfile.open(rg, "r:gz") as tar:
        for member in tar.getmembers():
            if os.path.basename(member.name) == "UNLICENSE":
                data = tar.extractfile(member)
                assert data is not None
                write(os.path.join(OUT, "Unlicense.txt"), data.read().decode("utf-8"))
                manifest.append(("Unlicense.txt", "Unlicense", "许可证全文"))
                break
        else:
            notes.append("ripgrep payload carries no UNLICENSE file")

    # --------------------------------------------------------------- npm licences
    os.makedirs(NPM_CACHE, exist_ok=True)
    for out_name, pkg, version, filename in NPM_LICENCE_FILES:
        cached = os.path.join(NPM_CACHE, out_name)
        if not os.path.isfile(cached):
            if not fetch_missing:
                notes.append(f"{out_name} not cached; re-run with --fetch-missing")
                continue
            url = f"https://unpkg.com/{pkg}@{version}/{filename}"
            try:
                with urllib.request.urlopen(url, timeout=60) as response:
                    body = response.read()
            except Exception as error:  # noqa: BLE001 - reported, not raised
                notes.append(f"{out_name}: {url} failed ({error})")
                continue
            with open(cached, "wb") as fh:
                fh.write(body)
        shutil.copyfile(cached, os.path.join(OUT, out_name))
        manifest.append((out_name, out_name[:-4], "许可证全文"))

    # ------------------------------------------------------------- pinned texts
    # Three licence texts come from a URL rather than from something the app ships: the
    # bundled font's OFL, pi's LICENSE, and proroot's LICENSE. Each has a pinned sha256
    # beside its URL, and each is cached under `build/downloads/` so a build after the
    # first needs no network.
    #
    # The fetch path checks the pin against what came back. That alone leaves the one
    # hole that matters: a *cached* file that was altered after it was fetched — or
    # written by an older pin — would be copied into the APK unverified, and the licence
    # shipped would not be the licence the pin names. So the bytes are re-checked on
    # every build, at the moment they are installed, and a mismatch is a hard stop. It
    # costs one sha256 over a few KiB.
    def install_pinned_text(cache: str, out_name: str, title: str, pinned: str, missing_note: str) -> None:
        if not os.path.isfile(cache):
            notes.append(missing_note)
            return
        digest = sha256(cache)
        if digest != pinned:
            sys.exit(
                f"{out_name}: the cached licence text does not match its pin\n"
                f"  cache   {cache}\n"
                f"  pinned  {pinned}\n"
                f"  actual  {digest}\n"
                f"  Refusing to ship a licence text that is not the one the pin names. Delete the\n"
                f"  cached file and re-run with --fetch-missing to fetch it again; if the bytes that\n"
                f"  come back still differ from the pin, the upstream text changed and the pin, the\n"
                f"  notice and the component it describes all have to be reviewed together."
            )
        shutil.copyfile(cache, os.path.join(OUT, out_name))
        manifest.append((out_name, title, "许可证全文"))

    # ------------------------------------------------------------- font licences
    # The bundled typeface's obligation. Same shape as the npm and pi texts above:
    # pinned URL + pinned sha256, cached, fetched only under --fetch-missing, and a
    # mismatch is a hard stop rather than a silent substitution.
    os.makedirs(FONT_CACHE, exist_ok=True)
    font_licence = os.path.join(FONT_CACHE, JETBRAINS_MONO_LICENCE_OUT)
    if not os.path.isfile(font_licence) and fetch_missing:
        url = JETBRAINS_MONO_LICENCE_URL.format(version=JETBRAINS_MONO_VERSION)
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                body = response.read()
        except Exception as error:  # noqa: BLE001 - reported, not raised
            notes.append(f"{JETBRAINS_MONO_LICENCE_OUT}: {url} failed ({error})")
            body = None
        if body is not None:
            digest = hashlib.sha256(body).hexdigest()
            if digest != JETBRAINS_MONO_LICENCE_SHA256:
                sys.exit(
                    f"JetBrains Mono {JETBRAINS_MONO_VERSION} OFL text changed\n"
                    f"  pinned  {JETBRAINS_MONO_LICENCE_SHA256}\n"
                    f"  actual  {digest}\n"
                    f"  The TTFs in app/src/main/res/font/ were extracted from the release zip at\n"
                    f"  tag v{JETBRAINS_MONO_VERSION}, so if that tag's OFL.txt no longer matches this pin the\n"
                    f"  font and the licence shipped beside it describe different releases: re-extract\n"
                    f"  the TTFs, then update the pin."
                )
            with open(font_licence, "wb") as fh:
                fh.write(body)
    install_pinned_text(
        font_licence,
        JETBRAINS_MONO_LICENCE_OUT,
        JETBRAINS_MONO_LICENCE_TITLE,
        JETBRAINS_MONO_LICENCE_SHA256,
        f"{JETBRAINS_MONO_LICENCE_OUT} not cached; re-run with --fetch-missing",
    )

    # ------------------------------------- pi's own licence + the packages that lack one
    # pi is the one component whose licence text we must supply ourselves, because
    # upstream publishes the package without it (see PI_LICENCE_URL). The gaps file
    # is the honest record of the packages we redistribute that carry no text: a
    # licence list that silently omitted them would be worse than no list, because it
    # would read as complete.
    version = engine_version()
    pi_licence = os.path.join(NPM_CACHE, f"pi-LICENSE-{version}.txt")
    if not os.path.isfile(pi_licence) and fetch_missing:
        url = PI_LICENCE_URL.format(version=version)
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                body = response.read()
        except Exception as error:  # noqa: BLE001 - reported, not raised
            notes.append(f"pi licence: {url} failed ({error})")
            body = None
        if body is not None:
            digest = hashlib.sha256(body).hexdigest()
            if digest != PI_LICENCE_SHA256:
                sys.exit(
                    f"pi licence text changed at v{version}\n"
                    f"  pinned  {PI_LICENCE_SHA256}\n"
                    f"  actual  {digest}\n"
                    f"  The engine version moved, so the licence text must be re-verified before it\n"
                    f"  ships: read the new LICENSE, confirm it is still the intended licence, then\n"
                    f"  update PI_LICENCE_SHA256. A silent change here is the one failure this pin exists\n"
                    f"  to prevent."
                )
            with open(pi_licence, "wb") as fh:
                fh.write(body)
    install_pinned_text(
        pi_licence,
        "pi-license.txt",
        f"pi 引擎 {version}（MIT）",
        PI_LICENCE_SHA256,
        "pi licence text not cached; re-run with --fetch-missing",
    )

    # -------------------------------------------------------------------- proroot
    # The one component that is not open source, and the only licence text this app
    # ships that comes from no artifact at all. Both of its files are built from pins:
    # the licence from a tag-pinned URL (same shape as the font text above), the
    # registration from the lock's digests plus the downloaded bytes.
    proroot = proroot_version()
    # The component table's row and the pinned tag must be the same release, or the list
    # entry points at a licence for something else.
    listed = [v for component, v, *_ in COMPONENTS if component == "proroot"]
    if listed and listed[0] != proroot:
        sys.exit(
            f"proroot version drift: the component table lists {listed[0]} while "
            f"tools/fetch-runtime.mjs pins {proroot}. A bump must move both, or the list "
            f"entry and the licence it points at describe different releases."
        )
    proroot_files: list[tuple[str, str, str, int]] = []
    for key, name, role in PROROOT_FILES:
        path = artifact(key, lock)  # refuses an unpinned or altered download
        proroot_files.append((name, role, sha256(path), os.path.getsize(path)))

    os.makedirs(PROROOT_CACHE, exist_ok=True)
    proroot_licence = os.path.join(PROROOT_CACHE, PROROOT_LICENCE_OUT)
    if not os.path.isfile(proroot_licence) and fetch_missing:
        url = PROROOT_LICENCE_URL.format(version=proroot)
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                body = response.read()
        except Exception as error:  # noqa: BLE001 - reported, not raised
            notes.append(f"{PROROOT_LICENCE_OUT}: {url} failed ({error})")
            body = None
        if body is not None:
            digest = hashlib.sha256(body).hexdigest()
            if digest != PROROOT_LICENCE_SHA256:
                sys.exit(
                    f"proroot {proroot} LICENSE text changed\n"
                    f"  pinned  {PROROOT_LICENCE_SHA256}\n"
                    f"  actual  {digest}\n"
                    f"  The tag is pinned, so this is not a routine update: read the new\n"
                    f"  LICENSE and confirm it still permits redistributing the unmodified\n"
                    f"  binaries inside an application package, and that the attribution it\n"
                    f"  asks for is still the one this app gives, before moving the pin. A\n"
                    f"  silent change in those terms is the failure this pin exists to stop."
                )
            with open(proroot_licence, "wb") as fh:
                fh.write(body)
    install_pinned_text(
        proroot_licence,
        PROROOT_LICENCE_OUT,
        f"proroot {proroot}（Proprietary）",
        PROROOT_LICENCE_SHA256,
        f"{PROROOT_LICENCE_OUT} not cached; re-run with --fetch-missing",
    )

    # Written unconditionally: it needs the pin and the bytes, never the network, so a
    # build that could not fetch the licence text is still a build that states the terms
    # and the digests — and one fewer file that can silently vanish from the APK.
    write(
        os.path.join(OUT, "proprietary-third-party.txt"),
        proprietary_registration(proroot, proroot_files, PROROOT_LICENCE_SHA256),
    )
    manifest.append(("proprietary-third-party.txt", "随包分发的专有组件（proroot）", "专有组件"))

    gaps = [
        "未随包提供许可文本的 pi 依赖组件",
        "=================================",
        "",
        "下列组件随 App 分发，且各自声明了许可证，但没有随包提供许可证原文。",
        "它们使用的标准许可证原文已经收录在本清单里；版权声明以上游发布为准。",
        "这份文件存在的意义是：不把「没有文本」写成「没有许可证」，也不让清单看起来比实际更完整。",
        "",
    ]
    for pkg, pkg_version, licence in PI_ENGINE_NO_LICENCE_TEXT:
        gaps.append(f"  {pkg}  {pkg_version}  {licence}")
    write(os.path.join(OUT, "pi-engine-licence-gaps.txt"), "\n".join(gaps) + "\n")
    manifest.append(
        (
            "pi-engine-licence-gaps.txt",
            f"未随包提供许可文本的依赖（{len(PI_ENGINE_NO_LICENCE_TEXT)} 个）",
            "说明",
        )
    )

    # The remedy for each gap above, so "we know it is missing" is not where the
    # trail ends. Every sha256 is the bytes that came back from the URL beside it.
    notices = [
        "pi 引擎依赖的许可与版权声明来源",
        "===============================",
        "",
        f"在「未随包提供许可文本的依赖」那一份里列出的 {len(PI_ENGINE_NO_LICENCE_TEXT)} 个组件，包里没有许可文件；但其中大多数能在别处找到声明：",
        "同一 monorepo、同一载荷里的兄弟包、或上游仓库。",
        "下面是逐个核到的位置。每一项的 sha256 都是实际抓到的字节，可以复核，不需要相信这段话。",
        "",
    ]
    for pkg, pkg_version, licence, note, url, digest in PI_ENGINE_NOTICES:
        notices.append(f"  {pkg}  （{pkg_version}，{licence}）")
        notices.append(f"      {note}")
        if url:
            notices.append(f"      来源    {url}")
            notices.append(f"      sha256  {digest}")
        notices.append("")
    write(os.path.join(OUT, "pi-engine-npm-notices.txt"), "\n".join(notices) + "\n")
    manifest.append(
        (
            "pi-engine-npm-notices.txt",
            "pi 引擎依赖的版权声明来源（含 %d 个取不到的）" % sum(1 for row in PI_ENGINE_NOTICES if not row[4]),
            "说明",
        )
    )

    # ------------------------------------------------------------------ prose
    write(os.path.join(OUT, "about.txt"), STATEMENT)
    manifest.insert(0, ("about.txt", "关于这份清单", "说明"))
    write(os.path.join(OUT, "source-code.txt"), SOURCE_OFFER.format(proroot=proroot))
    manifest.insert(1, ("source-code.txt", "获取源代码", "说明"))

    write(os.path.join(OUT, "component-list.txt"), components_text(COMPONENTS))
    manifest.insert(2, ("component-list.txt", "组件清单", "说明"))

    with open(os.path.join(OUT, "manifest.txt"), "w", encoding="utf-8") as fh:
        fh.write(MANIFEST_HEADER)
        for name, title, section in manifest:
            fh.write(f"{name}\t{title}\t{section}\n")

    total = sum(
        os.path.getsize(os.path.join(OUT, f)) for f in os.listdir(OUT) if os.path.isfile(os.path.join(OUT, f))
    )
    # The destination is printed by `main()` after it installs the directory: `OUT`
    # here is the scratch directory this ran in, not where the files end up.
    print(f"wrote {len(manifest)} entries + manifest")
    print(f"  {len(os.listdir(OUT))} files, {total / 1024:.0f} KiB")
    print(f"  {sum(1 for e in manifest if e[2] == '软件包版权与许可')} package copyright files, one per package")
    for note in notes:
        print(f"  note: {note}")


def write(path: str, text: str) -> None:
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


# --------------------------------------------------------------------------- #
# the component table
# --------------------------------------------------------------------------- #
# `where` groups the rows in the in-app list; `source` is the upstream release
# location, which for the copyleft components is also the written offer of source.
COMPONENTS: list[tuple[str, str, str, str, str]] = [
    # Android native libraries
    ("proot", "5.1.107.92", "GPL-2.0", "Android 原生库（随 App 二进制安装）", "https://github.com/termux/proot"),
    ("libtalloc", "2.4.3", "LGPL-3.0（上游 LICENSE 原文；Termux 打包元数据写 GPL-3.0，与上游不符）", "Android 原生库（随 App 二进制安装）", "https://www.samba.org/ftp/talloc/"),
    ("libandroid-shmem", "0.7", "BSD-3-Clause", "Android 原生库（随 App 二进制安装）", "https://github.com/termux/libandroid-shmem"),
    # Not open source, and therefore also the one row here whose licence is not a standard
    # text: the terms are upstream's own LICENSE, shipped verbatim as proroot-license.txt,
    # and the registration — package locations, the five digests, why they ship inside the
    # APK, known limits — is `proprietary-third-party.txt`. The version is checked against
    # `PROROOT_VERSION` in tools/fetch-runtime.mjs while building (see `build`), because a
    # row here and a pin there that disagree would be a list entry pointing at a licence
    # for a different release.
    (
        "proroot",
        "v1.2.8",
        "Proprietary（专有，闭源；许可证原文见「许可证全文」里的 proroot-license.txt，"
        "条款与已知限制见「随包分发的专有组件」）",
        "Android 原生库（随 App 二进制安装）",
        "https://github.com/coderredlab/proroot",
    ),
    # Linux userland
    ("Ubuntu 基础系统", "24.04.3", "各软件包各自的许可证", "Linux 运行时（首次启动解包）", "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.3/release/"),
    ("Ubuntu 附加软件包", "见清单", "各软件包各自的许可证", "Linux 运行时（首次启动解包）", "https://ports.ubuntu.com/ubuntu-ports/"),
    ("Node.js", "24.19.0", "MIT（含其内置依赖的许可证）", "Linux 运行时（首次启动解包）", "https://nodejs.org/dist/v24.19.0/"),
    ("git", "2.43.0", "GPL-2.0", "Linux 运行时（首次启动解包）", "https://git.kernel.org/pub/scm/git/git.git"),
    ("ca-certificates", "20260601", "GPL-2.0+（打包）与 MPL-2.0（证书数据）", "Linux 运行时（首次启动解包）", "https://packages.ubuntu.com/noble/ca-certificates"),
    ("ripgrep", "15.2.0", "Unlicense 或 MIT", "Linux 运行时（首次启动解包）", "https://github.com/BurntSushi/ripgrep"),
    ("fd", "10.2.0", "MIT 或 Apache-2.0", "Linux 运行时（首次启动解包）", "https://github.com/sharkdp/fd"),
    # git's library closure: every shared library the app adds on top of the base
    ("libcurl3t64-gnutls", "8.5.0", "curl（MIT 类）", "Linux 运行时（首次启动解包）", "https://curl.se/"),
    ("libbrotli1", "1.1.0", "MIT", "Linux 运行时（首次启动解包）", "https://github.com/google/brotli"),
    ("libnghttp2-14", "1.59.0", "MIT", "Linux 运行时（首次启动解包）", "https://nghttp2.org/"),
    ("libexpat1", "2.6.1", "MIT", "Linux 运行时（首次启动解包）", "https://libexpat.github.io/"),
    ("libpsl5t64", "0.21.2", "MIT", "Linux 运行时（首次启动解包）", "https://rockdaboot.github.io/libpsl/"),
    ("librtmp1", "2.4", "LGPL-2.1（库）", "Linux 运行时（首次启动解包）", "https://rtmpdump.mplayerhq.hu/"),
    ("libssh-4", "0.10.6", "LGPL-2.1", "Linux 运行时（首次启动解包）", "https://www.libssh.org/"),
    ("libsasl2-2", "2.1.28", "BSD 系列（含 BSD-4-Clause-UC）", "Linux 运行时（首次启动解包）", "https://www.cyrusimap.org/sasl/"),
    ("libkrb5-3 / libgssapi-krb5-2 / libk5crypto3 / libkrb5support0", "1.20.1", "MIT", "Linux 运行时（首次启动解包）", "https://web.mit.edu/kerberos/"),
    ("libkeyutils1", "1.6.3", "LGPL-2.1+ 或 GPL-2.0+", "Linux 运行时（首次启动解包）", "https://people.redhat.com/~dhowells/keyutils/"),
    ("libldap2", "2.6.7", "OpenLDAP-2.8", "Linux 运行时（首次启动解包）", "https://www.openldap.org/"),
    # pi engine and its closure
    # Read from PI_VERSION rather than repeated: this row and the engine payload must
    # name the same release, and a literal here is what left the list describing
    # 0.85.1 after the pin moved to 0.86.1.
    ("pi 引擎", engine_version(), "MIT", "Linux 运行时（首次启动解包）", "https://www.npmjs.com/package/@earendil-works/pi-coding-agent"),
    ("pi 引擎的依赖包", f"见 pi {engine_version()} 的依赖锁定", "MIT / Apache-2.0 / BSD-3-Clause / ISC（全部为宽松许可证）", "Linux 运行时（首次启动解包）", "https://www.npmjs.com/package/@earendil-works/pi-coding-agent"),
    ("highlight.js", "10.7.3", "BSD-3-Clause", "Linux 运行时（首次启动解包）", "https://highlightjs.org/"),
    ("tslib", "2.8.1", "0BSD", "Linux 运行时（首次启动解包）", "https://www.npmjs.com/package/tslib"),
    ("lru-cache / minimatch", "11.4.0 / 10.2.6", "BlueOak-1.0.0", "Linux 运行时（首次启动解包）", "https://www.npmjs.com/package/lru-cache"),
    # JVM side
    (
        "AndroidX / Jetpack Compose（core-ktx、activity-compose、lifecycle、compose-ui、"
        "material3、material-icons-extended、foundation、documentfile、webkit、navigation）",
        "见版本目录",
        "Apache-2.0",
        "编译进 App 代码",
        "https://developer.android.com/jetpack",
    ),
    ("Kotlin 标准库与 kotlinx 库（coroutines、serialization-json）", "见版本目录", "Apache-2.0", "编译进 App 代码", "https://github.com/Kotlin"),
    ("Shizuku API", "13.1.5", "MIT", "编译进 App 代码", "https://github.com/RikkaApps/Shizuku-API"),
    ("termlib", "0.0.13", "Apache-2.0", "编译进 App 代码", "https://github.com/connectbot/termlib"),
    ("libvterm（随 termlib 一起分发）", "见 termlib 内置版本", "MIT", "编译进 App 代码", "https://github.com/neovim/libvterm"),
    ("multiplatform-markdown-renderer-m3", "0.45.0", "Apache-2.0", "编译进 App 代码", "https://github.com/mikepenz/multiplatform-markdown-renderer"),
    ("org.jetbrains:markdown", "0.7.9", "Apache-2.0", "编译进 App 代码", "https://github.com/JetBrains/markdown"),
    ("kotlinx-collections-immutable（随 markdown 渲染器一起分发）", "见版本目录", "Apache-2.0", "编译进 App 代码", "https://github.com/Kotlin/kotlinx.collections.immutable"),
    ("JetBrains Mono", "2.304", "OFL-1.1", "编译进 App 代码（随包打包的等宽字体，Regular + Bold）", "https://github.com/JetBrains/JetBrainsMono"),
    # Declared, but deliberately not in this list's scope: it is a test-only dependency
    # and no part of it reaches the APK, so it carries no distribution obligation.
    ("JUnit（仅测试用，不随 App 分发）", "4.13.2", "EPL-1.0", "不随包分发", "https://junit.org/junit4/"),
]


if __name__ == "__main__":
    main()
