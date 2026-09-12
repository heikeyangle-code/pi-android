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
    closure and appear in no other downloaded artifact.

`runtime.lock.json` is the pin list; `tools/fetch-runtime.mjs` populates
`build/downloads/`. Run that first, then this script. Nothing is compiled and
Gradle is never invoked.

Output is `app/src/main/assets/licenses/` and is flat on purpose: the settings
screen lists the directory through `manifest.txt`, so adding a licence needs no
Kotlin change.

Usage:
    python3 tools/build-license-assets.py
    python3 tools/build-license-assets.py --fetch-missing   # allow unpkg for npm texts
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
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "licenses")
LOCK = os.path.join(ROOT, "runtime.lock.json")

# npm packages in pi's dependency closure whose licence is not otherwise present
# in a downloaded artifact. Versions are the ones pi 0.85.1 pins in its shipped
# shrinkwrap; a bump must move both the version and the pi version together.
#   tslib       0BSD          (also: `Unlicense` is taken from ripgrep's tarball)
#   lru-cache   BlueOak-1.0.0
#   minimatch   BlueOak-1.0.0
NPM_LICENCE_FILES = [
    ("0BSD.txt", "tslib", "2.8.1", "LICENSE.txt"),
    ("BlueOak-1.0.0.txt", "lru-cache", "11.4.0", "LICENSE.md"),
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
1. 这些程序都是**未修改的上游发行版**。它们以各自上游发布时的原始字节随 App 分发，
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


# --------------------------------------------------------------------------- #
# assembly
# --------------------------------------------------------------------------- #

MANIFEST_HEADER = "# 文件名\t标题\t分区\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--fetch-missing",
        action="store_true",
        help="download the npm licence files listed in NPM_LICENCE_FILES (pinned versions)",
    )
    args = parser.parse_args()

    if not os.path.isfile(LOCK):
        sys.exit("runtime.lock.json not found")
    lock = json.load(open(LOCK))

    stage = tempfile.mkdtemp(prefix="pi-licences-")
    try:
        build(lock, args.fetch_missing, stage)
    finally:
        shutil.rmtree(stage, ignore_errors=True)


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
    with tarfile.open(base, "r:gz") as tar:
        for member in tar.getmembers():
            if member.name.startswith("usr/share/common-licenses/"):
                tar.extract(member, common, filter="data")
            elif re.match(r"usr/share/doc/[^/]+/copyright$", member.name):
                tar.extract(member, docs, filter="data")
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
    seen_copyright: dict[str, str] = {}
    ubuntu_rows: list[tuple[str, str, str]] = []

    def add_copyright(pkg: str, version: str, path: str) -> str:
        """Copy one copyright file, deduplicated by content."""
        digest = sha256(path)
        if digest in seen_copyright:
            return seen_copyright[digest]
        name = f"ubuntu-copyright-{pkg}.txt"
        shutil.copyfile(path, os.path.join(OUT, name))
        seen_copyright[digest] = name
        manifest.append((name, f"{pkg} {version}", "软件包版权与许可"))
        return name

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
    for pkg, version in sorted(base_pkgs):
        if pkg in by_name:
            add_copyright(pkg, version, by_name[pkg])
        else:
            missing_base.append(f"{pkg} {version}")
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

    # ------------------------------------------------------------------ prose
    write(os.path.join(OUT, "about.txt"), STATEMENT)
    manifest.insert(0, ("about.txt", "关于这份清单", "说明"))
    write(os.path.join(OUT, "source-code.txt"), SOURCE_OFFER)
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
    print(f"wrote {len(manifest)} entries + manifest to {os.path.relpath(OUT, ROOT)}")
    print(f"  {len(os.listdir(OUT))} files, {total / 1024:.0f} KiB")
    print(f"  deduplicated {len(seen_copyright)} distinct copyright files")
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
    ("libtalloc", "2.4.3", "GPL-3.0（上游 talloc 声明为 LGPL-3.0）", "Android 原生库（随 App 二进制安装）", "https://www.samba.org/ftp/talloc/"),
    ("libandroid-shmem", "0.7", "BSD-3-Clause", "Android 原生库（随 App 二进制安装）", "https://github.com/termux/libandroid-shmem"),
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
    ("pi 引擎", "0.85.1", "MIT", "Linux 运行时（首次启动解包）", "https://www.npmjs.com/package/@earendil-works/pi-coding-agent"),
    ("pi 引擎的依赖包", "见 pi 0.85.1 的依赖锁定", "MIT / Apache-2.0 / BSD-3-Clause / ISC（全部为宽松许可证）", "Linux 运行时（首次启动解包）", "https://www.npmjs.com/package/@earendil-works/pi-coding-agent"),
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
    # Declared, but deliberately not in this list's scope: it is a test-only dependency
    # and no part of it reaches the APK, so it carries no distribution obligation.
    ("JUnit（仅测试用，不随 App 分发）", "4.13.2", "EPL-1.0", "不随包分发", "https://junit.org/junit4/"),
]


if __name__ == "__main__":
    main()
