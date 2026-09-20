#!/usr/bin/env node
/**
 * Assemble pi's Android runtime from pinned upstream artifacts.
 *
 * Two very different kinds of payload, placed differently on purpose
 * (docs/pi-android-app-design.md §5.2, §19.1):
 *
 *   jniLibs/arm64-v8a/*.so   Only what Android itself must execve(): proot and
 *                            its loader, plus proroot's five release assets
 *                            (the second, optional container runtime — see
 *                            PROROOT_RELEASE below). Android 10+ denies
 *                            execve() on app_data_file, and nativeLibraryDir is
 *                            the one location we may write to that is executable
 *                            — but the extractor only unpacks files matching
 *                            `lib*.so`. Hence proot's rename from `usr/bin/proot`,
 *                            and hence the hard requirement that each exec'd file
 *                            is PIE with `/system/bin/linker64` as its
 *                            interpreter (verified for these exact binaries: see
 *                            verifyElfDisguise below). proroot needs no rename:
 *                            upstream already names all five `lib*.so`, and the
 *                            names are load-bearing (PROROOT_JNI_PAYLOAD).
 *
 *   assets/runtime/*         Everything that runs *inside* proot: the Ubuntu
 *                            userland, Node, ripgrep, fd, git. These never need
 *                            the exec bit on the Android side, so they stay
 *                            compressed and are unpacked on first launch.
 *                            Ubuntu-base, Node, ripgrep and fd are pass-throughs;
 *                            git.tgz is re-assembled here (see
 *                            assembleGitPayload) because it needs a library
 *                            closure and a generated CA bundle that no single
 *                            upstream tarball provides.
 *
 *   assets/runtime-payloads/ One `<name>.digest` and one `<name>.list` per payload
 *                            (see writePayloadMeta). The app compares a device's
 *                            recorded digest per payload and re-extracts only the
 *                            ones whose bytes changed, instead of deleting the whole
 *                            runtime tree on any change. A sibling of
 *                            `assets/runtime/`, because that directory's CI invariant
 *                            is "every entry is a payload, byte for byte".
 *
 *                            Every one of them is named with PAYLOAD_SUFFIX, and
 *                            that suffix must never be `.gz` — read the constant's
 *                            comment before renaming anything here.
 *
 * Build-time only. Uses host `dpkg-deb`/`tar`/`xz`; it never runs on device.
 *
 *   node tools/fetch-runtime.mjs                 # verify + assemble
 *   node tools/fetch-runtime.mjs --resolve-only  # (re)write runtime.lock.json
 */

import { execFileSync, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  cpSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const CACHE = join(ROOT, "build", "downloads");
const STAGE = join(ROOT, "build", "runtime");
const JNI = join(ROOT, "app", "src", "main", "jniLibs", "arm64-v8a");
const ASSETS = join(ROOT, "app", "src", "main", "assets", "runtime");
/**
 * The per-payload metadata the app reads to decide what to re-extract:
 * `app/src/main/assets/runtime-payloads/<name>.digest` and `<name>.list`.
 *
 * A **sibling** of `assets/runtime/`, never inside it: CI's APK step asserts that every
 * entry under `assets/runtime/` is one of the payloads, byte for byte, and these files
 * are text *about* the payloads, not payloads. Read `writePayloadMeta` for the layout
 * and for why each list holds exactly the paths it holds.
 */
const PAYLOAD_META = join(ROOT, "app", "src", "main", "assets", "runtime-payloads");
const LOCK = join(ROOT, "runtime.lock.json");

const TERMUX = "https://packages.termux.dev/apt/termux-main";

/**
 * The name suffix every payload archive in `assets/runtime/` is written with.
 *
 * ## It must not end in `.gz`
 *
 * **The Android Gradle Plugin gunzips assets whose file extension is `gz` while it
 * merges them.** `com.android.ide.common.resources.AssetItem` decides this with
 *
 *     Files.getFileExtension(name).toLowerCase(Locale.US).equals("gz")
 *
 * and, when it is true, renames the asset with `Files.getNameWithoutExtension` —
 * which strips only the final `.gz`. So writing `ubuntu-base.tar.gz` put
 * `ubuntu-base.tar` in the APK: 106 MB of uncompressed tar where the source file
 * was 28.5 MiB, under a name `RuntimeProvisioner` never asked for. Its constant
 * still said `runtime/ubuntu-base.tar.gz`, all three of `openPayload`'s access
 * layers answered with a bare-path `FileNotFoundException`, and the app could not
 * provision its runtime at all — from the first build. The device report is
 * literally:
 *
 *   packaged asset unreadable: runtime/ubuntu-base.tar.gz
 *   assets/runtime/ contains: fd.tar, node.tar, pi-engine.tar, ripgrep.tar, ubuntu-base.tar
 *
 * and the APK's own table agreed (`unzip -lv`):
 *
 *   106649600  Stored  assets/runtime/ubuntu-base.tar
 *
 * Nothing about the payloads was wrong; the container renamed and expanded them.
 *
 * ## Why the attribution matters, and how it was settled
 *
 * It is **not** AAPT2. aapt2 2.20-14304508 — the exact build AGP 8.13.2 runs — was
 * executed against a directory laid out like this one, and it shipped both
 * `ubuntu-base.tar.gz` and `pi-engine.tgz` under their own names:
 *
 *   $ aapt2 link -o out.apk --manifest AndroidManifest.xml \
 *       --no-compress-regex "(tgz|xz|tar)$" -A assets
 *     Stored  assets/runtime/ubuntu-base.tar.gz   <- name and bytes intact
 *     Stored  assets/runtime/pi-engine.tgz
 *
 * The rename happens one stage earlier, in AGP's asset merge, which is why it
 * survives any change to the aapt2 command line — and why a `.tgz` suffix is the
 * fix: `Files.getFileExtension("ubuntu-base.tgz")` is `tgz`, not `gz`.
 *
 * This is worth the paragraph. The first version of this comment blamed AAPT2, and
 * a reader who checked that claim against aapt2 would have found it false and
 * "fixed" the payload names back. The failure is also silent, remote from its
 * cause, and produced a *smaller* APK (the renamed `.tar` was deflated again), so
 * nothing else would have caught it.
 *
 * ## What this does NOT change
 *
 * The payloads are still gzip streams. `RuntimeProvisioner` hands every one of
 * them to `TarExtractor.extractGzip`, and only the names moved. Nothing is
 * recompressed, so the payload bytes are identical to before.
 *
 * `app/build.gradle.kts`'s `androidResources { noCompress }` must list this
 * suffix, or the assets are deflated and `AssetManager.openFd()` can no longer
 * hand back a file descriptor for a stored entry — which is the third of
 * `RuntimeProvisioner.openPayload`'s three access layers. That switch is separate
 * from the gunzip above: one decides the entry's name and bytes, the other decides
 * whether it is deflated.
 */
const PAYLOAD_SUFFIX = ".tgz";

/**
 * GNU tar flags that make an archive a function of its contents and nothing else.
 *
 * ## Why this is not cosmetic
 *
 * `writeRevision` below hashes every payload this build assembled, and
 * `RuntimeProvisioner` re-unpacks the runtime tree whenever that digest changes —
 * `wipe()` deletes the whole tree, and everything the user installed inside the
 * guest goes with it (apt/pip packages, `npm -g`, `/usr/local/bin`, and all of
 * `/root` except the bind-mounted `.pi/agent`). The digest is derived precisely so
 * that a *payload* change is the only thing that triggers that.
 *
 * A plain `tar` breaks that promise. **A tar header carries the mtime of the file it
 * describes**, so archiving the same tree twice a second apart yields two different
 * archives — measured, and it is why `runtime-revision.txt` came out different on
 * every clean build: `ae0b51b9…`, `376b6bfc…` and `1088976d…` across three
 * consecutive runs. Every release therefore wiped every user's guest environment,
 * which is exactly the failure the derived digest was introduced to remove. The two
 * tar-built payloads were `git.tgz` and `pi-engine.tgz`; `node.tgz` was stable
 * because it is a pipe, not a tar of a staged tree.
 *
 *  - `--mtime=@0`                          every entry gets the epoch, so file mtimes
 *                                          cannot leak in
 *  - `--owner=0 --group=0 --numeric-owner` uid/gid and the user/group *names* are the
 *                                          build host's; normalise both
 *  - `--sort=name`                         a specified archive order, because
 *                                          `tar -C dir .` otherwise walks in readdir
 *                                          order, which is not a specified order
 *
 * The gzip layer needs nothing: GNU tar pipes into it, so gzip sees stdin and records
 * no name and no timestamp. Measured — `gzip -9` over the same stdin is
 * byte-identical run to run, and identical to `gzip -9n`.
 *
 * This changes `runtime-revision.txt` **once**, by construction: the bytes of
 * `git.tgz` and `pi-engine.tgz` change here (their entry mtimes become 0) while no
 * payload *content* does. That one extra wipe is the price of the fix; leaving it
 * unfixed costs a wipe on every future release.
 */
const DETERMINISTIC_TAR = [
  "--mtime=@0",
  "--owner=0",
  "--group=0",
  "--numeric-owner",
  "--sort=name",
];

/**
 * Ubuntu's arm64 archive. NOT `archive.ubuntu.com`: the primary archive has no
 * arm64 packages at all, and `ports.ubuntu.com` is the one that carries the
 * architecture `ubuntuBase` below is built for.
 */
const UBUNTU_PORTS = "https://ports.ubuntu.com/ubuntu-ports";

/**
 * pi's version. Pinned deliberately: the app's protocol layer is written against
 * this release's RPC surface, and pi has no stability guarantee across minor
 * versions. Bumping this is a decision, not an accident.
 *
 * 0.85.1 → **0.86.1**（2026-09-20）。这次不是"改个号"：上游有三处必须有人答话，写在这里，
 * 免得下一个人再从 release notes 里重新推：
 *
 *  1. **0.86.0 对 provider/扩展作者是破坏性改动**：`pi-ai` 的 provider 流输入从 `Context`
 *     换成规范化的 `TranscriptContext`；`ToolCall.arguments`/`ToolResultMessage.details`
 *     被限制为 JSON 兼容值；`user_bash` 改成 **fail-closed**。本仓库自带的三个扩展
 *     （`pi-android-bridge/`、`pi-android-permission-gate.ts`、`pi-highlight/` —— 就是
 *     `app/src/main/assets/pi-extensions/` 下的全部条目，见其 `README.md` 的表）已经逐个
 *     重读过：三个都不定义 provider、都不写 `arguments`/`details`、都没有 `user_bash`
 *     处理器，所以三条破坏性改动都不适用。判据有两半：对 0.86.1 的真类型定义做
 *     `tsc --noEmit` 零错误（`build/extension-check/` 那个 dev harness，`docs/pi-surface-audit-tools.md`
 *     记录了它），以及 `node tools/pi-contract.mjs` 的 `extensions` 组仍能把它们加载进
 *     0.86.1（`docs/pi-contract.md` 的 extensions 层）。
 *  2. **上游现在会在加载打包后的 CLI 之前打开 Node 的持久编译缓存**（0.86.1 原文：
 *     *reducing repeat launch time*）。**核到了具体位置**：这是上游在打包产物里注入的一行
 *     —— 安装好的 0.86.1 包里 `dist/bundle/cli.js` 第 2、4 行是
 *     `import { createRequire, enableCompileCache } from "node:module"` + `enableCompileCache()`。
 *     ⚠️ 但**本仓库启动的首选入口 `dist/bundle/rpc-entry.js` 里没有这一行**（grep 计数 0；
 *     `cli-runtime.js`、`index.js` 也没有），所以这条提升到不了本 App 实际走的那条路；而
 *     `PiEngineHost` 的 `NODE_COMPILE_CACHE` 注释写的是**另一件事**：这个变量在**解包入口**
 *     `dist/cli.js` 上量到过没效果（那时 ~969 次模块加载主导启动），并且明说"打包入口上没重测"、
 *     不该被读成"在打包入口也没效果"。要判断这条是否值得跟进，唯一的诚实实验是
 *     `rpc-entry.js` ± `NODE_COMPILE_CACHE`，读 `PiEngineSession.lastServingMs`。
 *     这条注释不是"已经写明了"，是**新增的、上游与本仓库路径不同的**事实。
 *  3. 新的用户可见面：`/bug`、prompt 缓存预热（`cache_warming_decision` 事件 + 它自己的
 *     设置）、Radius 离线模型目录、`compaction.modelOverrides`（我们已经接了）、
 *     `compat.allowedFallbackModels`、以及 Meta Muse 厂商（`/login meta`、`META_API_KEY`）。
 *     其中只有 prompt 缓存预热的 `cacheWarming` 是本 App **缺**的一个设置键（0.86.1 的
 *     `Settings` 相对 0.85.1 只多了这一个键），其余都有各自的落点或"pi 自己也没有 RPC 面"
 *     的证据，见 `docs/pi-contract.md` 与本轮审计。
 *
 * `tools/pi-contract.mjs`（CI 的 `contract` job）会把这条注释变成检查：它拿这个字符串
 * 指向的引擎去逐条核对命令名、事件类型、扩展 UI 方法、主题值与 `models.json` 语义。
 */
const PI_VERSION = "0.86.1";

/**
 * proroot — the optional second container runtime, and the only artifact here whose
 * bytes nobody outside its author can rebuild.
 *
 * It is **closed source and Proprietary**. Upstream's `LICENSE` permits
 * redistribution of the *unmodified* binaries as part of a complete application
 * package (APK/AAB), forbids modified ones, and adds two obligations this app
 * satisfies: ship the licence notice, and attribute "proroot" in the app
 * description, an about/settings screen, or the third-party notices. Read the
 * README's one-line summary as the whole licence and it looks like a grey area;
 * it is not — see `app/src/main/assets/licenses/proprietary-third-party.txt`, which
 * carries the registration (licence, package locations, digests, why they ship in
 * the APK, known limits) alongside the verbatim text in `proroot-license.txt`.
 * Mechanism and pitfalls: `docs/proroot-research.md`.
 *
 * ## Why the digest is written down twice (here and in `runtime.lock.json`)
 *
 * The `sha256` on each proroot entry below is the value **upstream publishes** for
 * that asset: the SHA-256 block in the release notes, which GitHub's own per-asset
 * metadata independently agrees with. The lock file carries the digest this build
 * pins. Both are checked against the downloaded bytes, and the pair is not
 * redundant: `--resolve-only` rewrites the lock from whatever the bytes on disk
 * happen to be, so a substituted binary would be silently *re-pinned* — while this
 * copy cannot be laundered that way and fails the build instead. Only proroot
 * carries the field, because only proroot has no other way to be checked: with no
 * source to rebuild from, these digests are the entire supply-chain check.
 *
 * ## Bumping the version
 *
 * Edit the tag, the five digests below, the five in the lock, and re-run the probe
 * set in `docs/proroot-research.md` §6.5. The behavioural contract is what stands in
 * for the source audit; a version bump without it is an unreviewed change to a
 * closed binary.
 */
const PROROOT_VERSION = "v1.2.8";
const PROROOT_RELEASE = `https://github.com/coderredlab/proroot/releases/download/${PROROOT_VERSION}`;

/**
 * proroot — the optional second container runtime, and the only artifact here whose
 * bytes nobody outside its author can rebuild.
 *
 * It is **closed source and Proprietary**. Upstream's `LICENSE` permits
 * redistribution of the *unmodified* binaries as part of a complete application
 * package (APK/AAB), forbids modified ones, and adds two obligations this app
 * satisfies: ship the licence notice, and attribute "proroot" in the app
 * description, an about/settings screen, or the third-party notices. Read the
 * README's one-line summary as the whole licence and it looks like a grey area;
 * it is not — see `app/src/main/assets/licenses/proprietary-third-party.txt`, which
 * carries the registration (licence, package locations, digests, why they ship in
 * the APK, known limits) alongside the verbatim text in `proroot-license.txt`.
 * Mechanism and pitfalls: `docs/proroot-research.md`.
 *
 * ## Why the digest is written down twice (here and in `runtime.lock.json`)
 *
 * The `sha256` on each proroot entry below is the value **upstream publishes** for
 * that asset: the SHA-256 block in the release notes, which GitHub's own per-asset
 * metadata independently agrees with. The lock file carries the digest this build
 * pins. Both are checked against the downloaded bytes, and the pair is not
 * redundant: `--resolve-only` rewrites the lock from whatever the bytes on disk
 * happen to be, so a substituted binary would be silently *re-pinned* — while this
 * copy cannot be laundered that way and fails the build instead. Only proroot
 * carries the field, because only proroot has no other way to be checked: with no
 * source to rebuild from, these digests are the entire supply-chain check.
 *
 * ## Bumping the version
 *
 * Edit the tag, the five digests below, the five in the lock, and re-run the probe
 * set in `docs/proroot-research.md` §6.5. The behavioural contract is what stands in
 * for the source audit; a version bump without it is an unreviewed change to a
 * closed binary.
 */
const PROROOT_VERSION = "v1.2.8";
const PROROOT_RELEASE = `https://github.com/coderredlab/proroot/releases/download/${PROROOT_VERSION}`;

/**
 * Pinned upstream artifacts. `sha256: null` means "record on first fetch".
 *
 * The git block below is a **closure, not a list of nice-to-haves**: `git` itself
 * plus every shared library its two real ELF binaries reach that the pinned
 * `ubuntuBase` does not already ship. It was computed rather than guessed, by
 * reading `DT_NEEDED` out of every ELF in the git package and walking it
 * transitively against the packages installed in the pinned base
 * (`var/lib/dpkg/status` says 91 of them). Result: 33 sonames reached, **18**
 * already in the base, **16** new — the packages below — and 0 unresolved. The
 * build re-checks that last number (see `GIT_LIBRARIES`).
 *
 * Two things this closure is easy to get wrong, both measured:
 *
 *  - The `-gnutls` curl. The base ships `libgnutls.so.30` and no OpenSSL-linked
 *    curl, so `libcurl-gnutls.so.4` is the flavour whose OWN dependencies are
 *    already satisfied. The OpenSSL flavour would drag in a second TLS stack.
 *  - Most of these are reachable only through libcurl, and a `DT_NEEDED`
 *    library is loaded at process start whether or not a single symbol from it
 *    is called. `libkrb5`, `libgssapi_krb5`, `liblber`, `librtmp` and `libssh`
 *    are therefore mandatory even though an HTTPS clone uses none of them.
 */
const ARTIFACTS = {
  proot: {
    url: `${TERMUX}/pool/main/p/proot/proot_5.1.107.92_aarch64.deb`,
    kind: "deb",
    why: "user-space syscall translator; no root needed",
  },
  libtalloc: {
    url: `${TERMUX}/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb`,
    kind: "deb",
    why: "proot's only non-Bionic dependency",
  },
  libandroidShmem: {
    url: `${TERMUX}/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb`,
    kind: "deb",
    why: "proot dependency",
  },
  ubuntuBase: {
    url: "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.3/release/ubuntu-base-24.04.3-base-arm64.tar.gz",
    kind: "tar.gz",
    why: "glibc 2.39 userland — the reason npm's linux-arm64 prebuilds work",
  },
  node: {
    url: "https://nodejs.org/dist/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
    kind: "tar.xz",
    why: "pi requires Node >= 22.19; official glibc build",
  },
  ripgrep: {
    url: "https://github.com/BurntSushi/ripgrep/releases/download/15.2.0/ripgrep-15.2.0-aarch64-unknown-linux-musl.tar.gz",
    kind: "tgz",
    why: "pi's `grep` tool spawns rg; static musl so it runs anywhere",
  },
  fd: {
    url: "https://github.com/sharkdp/fd/releases/download/v10.2.0/fd-v10.2.0-aarch64-unknown-linux-musl.tar.gz",
    kind: "tgz",
    why: "pi's `find` tool spawns fd",
  },
  // ---------------------------------------------------------------- git + closure
  // See the file-header note on this block: 16 packages, every one of them either
  // git itself or a library in git's transitive DT_NEEDED closure that the pinned
  // ubuntu-base does not ship. Versions are noble-updates, the same series as the
  // pinned ubuntu-base 24.04.3.
  git: {
    url: `${UBUNTU_PORTS}/pool/main/g/git/git_2.43.0-1ubuntu7.3_arm64.deb`,
    kind: "deb",
    why: "git: pi's `git:` package source, and the model's own status/diff/log/commit",
  },
  caCertificates: {
    url: `${UBUNTU_PORTS}/pool/main/c/ca-certificates/ca-certificates_20260601~24.04.1_all.deb`,
    kind: "deb",
    why: "the only source of a CA bundle: the base ships no /etc/ssl at all, and git's HTTPS transport has nothing to validate a server certificate against without one",
  },
  libcurlGnutls: {
    url: `${UBUNTU_PORTS}/pool/main/c/curl/libcurl3t64-gnutls_8.5.0-2ubuntu10.13_arm64.deb`,
    kind: "deb",
    why: "libcurl-gnutls.so.4 — the HTTPS transport behind git-remote-http",
  },
  libbrotli1: {
    url: `${UBUNTU_PORTS}/pool/main/b/brotli/libbrotli1_1.1.0-2build2_arm64.deb`,
    kind: "deb",
    why: "libcurl closure: libbrotlidec.so.1 + libbrotlicommon.so.1",
  },
  libnghttp2: {
    url: `${UBUNTU_PORTS}/pool/main/n/nghttp2/libnghttp2-14_1.59.0-1ubuntu0.4_arm64.deb`,
    kind: "deb",
    why: "libcurl closure: libnghttp2.so.14, the HTTP/2 framing GitHub answers with",
  },
  libexpat1: {
    url: `${UBUNTU_PORTS}/pool/main/e/expat/libexpat1_2.6.1-2ubuntu0.4_arm64.deb`,
    kind: "deb",
    why: "libcurl closure: libexpat.so.1",
  },
  libpsl5t64: {
    url: `${UBUNTU_PORTS}/pool/main/libp/libpsl/libpsl5t64_0.21.2-1.1build1_arm64.deb`,
    kind: "deb",
    why: "libcurl closure: libpsl.so.5",
  },
  librtmp1: {
    url: `${UBUNTU_PORTS}/pool/main/r/rtmpdump/librtmp1_2.4+20151223.gitfa8646d.1-2build7_arm64.deb`,
    kind: "deb",
    why: "libcurl closure: librtmp.so.1, loaded even though nothing uses RTMP",
  },
  libssh4: {
    url: `${UBUNTU_PORTS}/pool/main/libs/libssh/libssh-4_0.10.6-2ubuntu0.5_arm64.deb`,
    kind: "deb",
    why: "libcurl closure: libssh.so.4. Gives libcurl an sftp:// scheme, NOT git an ssh transport — `ssh://`/`git@host:` still need an ssh client, which is not shipped",
  },
  libsasl2: {
    url: `${UBUNTU_PORTS}/pool/main/c/cyrus-sasl2/libsasl2-2_2.1.28+dfsg1-5ubuntu3.1_arm64.deb`,
    kind: "deb",
    why: "libcurl closure: libsasl2.so.2; its libsasl2-modules-db sibling is left out because no SASL mechanism is reachable over HTTPS",
  },
  libkrb5: {
    url: `${UBUNTU_PORTS}/pool/main/k/krb5/libkrb5-3_1.20.1-6ubuntu2.8_arm64.deb`,
    kind: "deb",
    why: "libcurl closure: libkrb5.so.3",
  },
  libgssapiKrb5: {
    url: `${UBUNTU_PORTS}/pool/main/k/krb5/libgssapi-krb5-2_1.20.1-6ubuntu2.8_arm64.deb`,
    kind: "deb",
    why: "libcurl closure: libgssapi_krb5.so.2",
  },
  libk5crypto3: {
    url: `${UBUNTU_PORTS}/pool/main/k/krb5/libk5crypto3_1.20.1-6ubuntu2.8_arm64.deb`,
    kind: "deb",
    why: "libkrb5 closure: libk5crypto.so.3",
  },
  libkrb5support0: {
    url: `${UBUNTU_PORTS}/pool/main/k/krb5/libkrb5support0_1.20.1-6ubuntu2.8_arm64.deb`,
    kind: "deb",
    why: "libkrb5 closure: libkrb5support.so.0",
  },
  libkeyutils1: {
    url: `${UBUNTU_PORTS}/pool/main/k/keyutils/libkeyutils1_1.6.3-3build1_arm64.deb`,
    kind: "deb",
    why: "libkrb5 closure: libkeyutils.so.1",
  },
  libldap2: {
    url: `${UBUNTU_PORTS}/pool/main/o/openldap/libldap2_2.6.7+dfsg-1~exp1ubuntu8_arm64.deb`,
    kind: "deb",
    why: "libcurl closure: libldap.so.2 + liblber.so.2",
  },
  // ---------------------------------------------------------------- proroot
  // See PROROOT_RELEASE above for the licence, the pinning rule and why `sha256`
  // appears on these five and nowhere else. `kind: "so"` is documentation, not a
  // dispatch: extract() never sees these — step 1 downloads them, step 2b copies
  // them straight into jniLibs. Nothing is wired to them yet; this batch only
  // fetches, verifies and lands them, so an unusable v1.2.8 cannot affect a build.
  prorootLauncher: {
    url: `${PROROOT_RELEASE}/libproroot.so`,
    kind: "so",
    sha256: "a4e74d75b66cdc02b080adfe863dbf9951c3b30610d77beddc95488d5fe5de01",
    why: "proroot's launcher/CLI — the one file Android execve()s, and the parent of every guest process. Closed-source Proprietary; the digest is upstream v1.2.8's published one (see licenses/proprietary-third-party.txt)",
  },
  prorootRuntime: {
    url: `${PROROOT_RELEASE}/libproroot-runtime.so`,
    kind: "so",
    sha256: "8c47a0a7db32d84c179ebb5bf3640f655a3181860ece5886ae44d92858730c34",
    why: "proroot's in-process hook (LD_PRELOAD semantics): path translation, fake id0, /proc synthesis",
  },
  prorootLinker: {
    url: `${PROROOT_RELEASE}/libproroot-linker.so`,
    kind: "so",
    sha256: "51a0ec5bfed00e572a0de09e22d9057e2befc386b78e426613d3e0ab03f4ecee",
    why: "proroot's clean-room ELF interpreter (PT_INTERP) for guest binaries",
  },
  prorootBridge: {
    url: `${PROROOT_RELEASE}/libproroot-bridge.so`,
    kind: "so",
    sha256: "1c5bc9537a270e8bf8b1c70222813f57b60b828bfb5503ddf8fe37685092de2f",
    why: "proroot's entry trampoline (PROROOT_TRAMPOLINE_PATH), the point a guest child process re-enters",
  },
  prorootStubLoader: {
    url: `${PROROOT_RELEASE}/libproroot-stub-loader.so`,
    kind: "so",
    sha256: "06c6624db3bdc45b9ced151cd781df439a37b47731d244b93e9d6a58cd48cde0",
    why: "proroot's static/static-pie and execve routing loader; optional upstream, enabled adaptively when the file is present",
  },
};

/**
 * Where each library package's shared objects are staged from. Every one of these
 * directories is copied **whole** into the payload rather than file by file: a
 * hand-picked file list is one rename away from a rootfs whose git cannot start,
 * and the few extra files these packages carry (`libbrotlienc`, `libexpatw`,
 * libcurl's `.so.3` compat symlink, krb5's `spake.so` preauth plugin) cost well
 * under 200 KiB.
 */
const GIT_LIBRARY_PACKAGES = [
  "libcurlGnutls",
  "libbrotli1",
  "libnghttp2",
  "libexpat1",
  "libpsl5t64",
  "librtmp1",
  "libssh4",
  "libsasl2",
  "libkrb5",
  "libgssapiKrb5",
  "libk5crypto3",
  "libkrb5support0",
  "libkeyutils1",
  "libldap2",
];

/**
 * The 16 sonames the payload must end up carrying — the measured closure, written
 * down so the build can fail loudly instead of shipping a rootfs whose `git` dies
 * with "error while loading shared libraries". Each name is the link the dynamic
 * linker looks for, so it is asserted as a file name, not as a package name.
 */
const GIT_LIBRARIES = [
  "libcurl-gnutls.so.4",
  "libbrotlidec.so.1",
  "libbrotlicommon.so.1",
  "libnghttp2.so.14",
  "libexpat.so.1",
  "libpsl.so.5",
  "librtmp.so.1",
  "libssh.so.4",
  "libsasl2.so.2",
  "libkrb5.so.3",
  "libgssapi_krb5.so.2",
  "libk5crypto.so.3",
  "libkrb5support.so.0",
  "libkeyutils.so.1",
  "libldap.so.2",
  "liblber.so.2",
];

/**
 * `/usr/bin` entry points for the git payload, as symlinks into the exec path.
 *
 * The deb ships `/usr/bin/git` as a **second full copy** of the 4,003,072-byte
 * binary that is already at `/usr/lib/git-core/git` (confirmed against the raw
 * data.tar: 684 regular files, 147 symlinks, and no hardlink entries at all).
 * Shipping the symlink instead of the copy is what keeps ~4 MiB of duplicate
 * binary out of the APK; this is the layout Arch uses, and git resolves its
 * subcommand from `argv[0]`, so the indirection is transparent.
 *
 * `git-shell` and `scalar` are the reverse case: they are genuine second and
 * third binaries in the package, so `/usr/bin` points at the copy that the exec
 * path already carries.
 */
const GIT_USR_BIN = [
  { name: "git", target: "../lib/git-core/git" },
  { name: "git-shell", target: "../lib/git-core/git-shell" },
  { name: "scalar", target: "../lib/git-core/scalar" },
  // Relative to /usr/bin, i.e. the `git` link one line up.
  { name: "git-receive-pack", target: "git" },
  { name: "git-upload-archive", target: "git" },
  { name: "git-upload-pack", target: "git" },
];

/** proot binaries and where they must land, disguised for jniLibs. */
const JNI_PAYLOAD = [
  { from: "usr/bin/proot", to: "libproot.so", interpreter: "/system/bin/linker64" },
  { from: "usr/libexec/proot/loader", to: "libprootloader.so", interpreter: null },
  { from: "usr/lib/libtalloc.so.2.4.3", to: "libtalloc.so", interpreter: null },
  { from: "usr/lib/libandroid-shmem.so", to: "libandroid-shmem.so", interpreter: null },
];

/**
 * proroot's five release assets and where they land: `jniLibs/arm64-v8a/`, **under
 * the names upstream gave them**.
 *
 * ## The names are not cosmetic
 *
 * Nothing here is renamed, unlike `JNI_PAYLOAD` above. proroot discovers the
 * linker, the runtime hook and the stub loader by **fixed name in the directory of
 * `/proc/self/exe`** — i.e. in `nativeLibraryDir` (`docs/proroot-research.md`
 * §5.P1-4). A `to:` that differed from the asset name would not fail this build; it
 * would fail on a phone as a runtime that cannot start, or worse, as a launcher
 * that silently picks up nothing. Upstream already ships every file as `lib*.so`,
 * which is the only shape Android's native-library extractor will unpack, so there
 * was never anything to disguise.
 *
 * ## `interpreter`
 *
 * The same trap `JNI_PAYLOAD` documents: the extractor silently skips a `lib*.so`
 * that is not linked against Android's linker. Only `libproroot.so` is exec'd — it
 * is PIE and carries `/system/bin/linker64` as its `PT_INTERP`. The other four are
 * mapped or `dlopen`ed and carry no `PT_INTERP` at all (`libproroot-bridge.so` is
 * in fact `ET_EXEC`), so `null` restricts them to the ELF/64-bit check that does
 * apply. Requiring PIE of all five made the equivalent check fail on a correct
 * payload once already; see `verifyElfDisguise`.
 */
const PROROOT_JNI_PAYLOAD = [
  { name: "prorootLauncher", to: "libproroot.so", interpreter: "/system/bin/linker64" },
  { name: "prorootRuntime", to: "libproroot-runtime.so", interpreter: null },
  { name: "prorootLinker", to: "libproroot-linker.so", interpreter: null },
  { name: "prorootBridge", to: "libproroot-bridge.so", interpreter: null },
  { name: "prorootStubLoader", to: "libproroot-stub-loader.so", interpreter: null },
];

/**
 * The extractor silently skips a `lib*.so` whose ELF interpreter is not
 * Android's linker, so a wrong build shows up as a mystifying ENOENT at
 * runtime. Fail loudly here instead.
 */
function verifyElfDisguise(path, expectedInterpreter) {
  const buf = readFileSync(path);
  if (buf.subarray(0, 4).toString("binary") !== "\x7fELF") {
    throw new Error(`${path} is not an ELF file`);
  }
  if (buf[4] !== 2) throw new Error(`${path} is not 64-bit`);
  // PIE is only required of the one payload Android actually execs. Its
  // interpreter string marks that file: proot is exec'd through
  // /system/bin/linker64, and Android refuses a non-PIE executable.
  //
  // The other three are mapped, never exec'd, so the rule does not apply to
  // them — and `loader` is in fact ET_EXEC in the upstream Termux package
  // (checked against the real .deb: e_type=2, 18,136 bytes). Requiring PIE of
  // everything made this check fail on a correct payload.
  if (expectedInterpreter === null) return;
  const isPie = (buf[16] | (buf[17] << 8)) === 3;
  if (!isPie) throw new Error(`${path} is not PIE; Android refuses non-PIE executables`);
  const found = buf.includes(Buffer.from(expectedInterpreter + "\0"));
  if (!found) {
    throw new Error(
      `${path} does not use ${expectedInterpreter}; Android's native-library ` +
        `extractor will refuse to unpack it, and the runtime will fail with ENOENT`,
    );
  }
}

function sha256(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function download(url, dest) {
  if (existsSync(dest) && statSync(dest).size > 0) return dest;
  mkdirSync(dirname(dest), { recursive: true });
  process.stdout.write(`  fetching ${url.split("/").pop()} … `);
  execFileSync("curl", ["-sSL", "--fail", "--retry", "3", "-o", dest, url], { stdio: "inherit" });
  process.stdout.write("ok\n");
  return dest;
}

function extract(kind, archive, into) {
  rmSync(into, { recursive: true, force: true });
  mkdirSync(into, { recursive: true });
  if (kind === "deb") {
    execFileSync("dpkg-deb", ["-x", archive, into]);
    return;
  }
  const flags = kind === "tar.xz" ? "-xJf" : "-xzf";
  execFileSync("tar", [flags, archive, "-C", into]);
}

/**
 * Copy a tree, keeping symlinks as symlinks.
 *
 * `verbatimSymlinks` is the load-bearing option and the reason this is a function
 * rather than a `cpSync` call at each site: without it Node rewrites a relative
 * link target as if it were resolved from the copy's parent, and the git exec path
 * is 147 relative links (`git-status -> git`) whose whole meaning is "next to me".
 * `merge` exists because the library packages are copied into one shared directory
 * in sequence, and each copy must add to it rather than replace it.
 */
function copyTree(src, dst, { merge = false } = {}) {
  if (!merge) rmSync(dst, { recursive: true, force: true });
  mkdirSync(dst, { recursive: true });
  cpSync(src, dst, {
    recursive: true,
    dereference: false,
    verbatimSymlinks: true,
    force: true,
  });
}

/**
 * Build the CA bundle the way the `ca-certificates` package's own postinst does —
 * because the package does **not** ship one.
 *
 * This is the one part of the payload that cannot simply be extracted: the deb
 * contains 121 PEM files under `usr/share/ca-certificates/mozilla/` and no
 * `/etc/ssl/certs/ca-certificates.crt` at all. That file is generated on a real
 * system by `update-ca-certificates`, which is a Perl script this payload
 * deliberately does not carry (Perl alone is ~50 MiB installed). So the
 * concatenation happens here instead: the same inputs, in sorted order, one PEM
 * block after another, which is what the generated bundle is.
 *
 * It also does not ship the hashed-symlink form (`/etc/ssl/certs/<hash>.0`) that
 * `openssl rehash` would create. That is exactly why the guest environment pins
 * `GIT_SSL_CAINFO`/`SSL_CERT_FILE` at this file (PiRuntime.kt): the bundle is
 * addressed by name, never looked up through the directory.
 */
function writeCaBundle(src, dst) {
  const dir = join(src, "usr", "share", "ca-certificates", "mozilla");
  const certs = readdirSync(dir)
    .filter((name) => name.endsWith(".crt"))
    .sort();
  if (certs.length === 0) throw new Error(`no .crt files under ${dir}`);
  const parts = certs.map((name) => {
    const body = readFileSync(join(dir, name));
    return body[body.length - 1] === 0x0a ? body : Buffer.concat([body, Buffer.from("\n")]);
  });
  mkdirSync(dirname(dst), { recursive: true });
  writeFileSync(dst, Buffer.concat(parts));
  return { count: certs.length, bytes: statSync(dst).size };
}

/**
 * Assemble `assets/runtime/git.tgz`: git, its exec path, its library closure
 * and a CA bundle, as **one tree that unpacks straight into the rootfs**.
 *
 * The layout is the reason `RuntimeProvisioner` extracts this one archive into
 * `paths.rootfs` rather than hunting for a binary the way it does for rg and fd:
 * every path in here is already the guest's own (`usr/bin/git`,
 * `/usr/lib/git-core`, `/etc/ssl/certs`), so there is nothing to relocate. It is
 * also why git needs no `/usr/local/bin` symlink and no copy in the agent dir —
 * `/usr/bin` is on the guest PATH and no bind shadows it, unlike
 * `/root/.pi/agent` (see `PiPaths.agentBinDir`).
 *
 * Kept whole on purpose: the exec path is a unit, git execs these by name, and a
 * trimmed subset is a new configuration nothing has tested. Excluding the three
 * server-only binaries (`git-daemon`, `git-http-backend`, `git-shell`) would save
 * roughly 1 MiB of the payload and is a decision for whoever needs the bytes.
 */
function assembleGitPayload() {
  const stage = join(STAGE, "git");
  const debStage = join(STAGE, "git-deb");
  const libStage = join(STAGE, "git-libs");
  const caStage = join(STAGE, "git-ca");
  rmSync(stage, { recursive: true, force: true });
  rmSync(libStage, { recursive: true, force: true });
  mkdirSync(stage, { recursive: true });

  console.log("\ngit payload:");
  extract("deb", join(CACHE, ARTIFACTS.git.url.split("/").pop()), debStage);

  // 1. The exec path, entire: 49 real files (the 4 MiB `git` binary, the curl-based
  //    transports, git-shell, scalar) and 139 symlinks to them.
  copyTree(join(debStage, "usr", "lib", "git-core"), join(stage, "usr", "lib", "git-core"));
  // 2. `git init` fills a new repository's hooks/ and info/ from here. Git's
  //    compiled-in default is /usr/share/git-core/templates, so this needs no
  //    GIT_TEMPLATE_DIR.
  copyTree(join(debStage, "usr", "share", "git-core"), join(stage, "usr", "share", "git-core"));
  // 3. /usr/bin entry points as links, never as the deb's duplicate 4 MiB binary.
  mkdirSync(join(stage, "usr", "bin"), { recursive: true });
  for (const { name, target } of GIT_USR_BIN) {
    symlinkSync(target, join(stage, "usr", "bin", name));
  }
  console.log(`  ${"usr/lib/git-core".padEnd(28)} exec path (49 files, 139 symlinks)`);
  console.log(`  ${"usr/bin".padEnd(28)} ${GIT_USR_BIN.length} symlinks (the deb's second 4 MiB copy is left out)`);

  // 4. The library closure, one package directory at a time into one directory.
  const libDir = join(stage, "usr", "lib", "aarch64-linux-gnu");
  for (const name of GIT_LIBRARY_PACKAGES) {
    const pkgStage = join(libStage, name);
    extract("deb", join(CACHE, ARTIFACTS[name].url.split("/").pop()), pkgStage);
    const from = join(pkgStage, "usr", "lib", "aarch64-linux-gnu");
    if (!existsSync(from)) throw new Error(`${name} carries no usr/lib/aarch64-linux-gnu`);
    copyTree(from, libDir, { merge: true });
  }

  // 5. The CA bundle, generated rather than extracted — see writeCaBundle.
  extract("deb", join(CACHE, ARTIFACTS.caCertificates.url.split("/").pop()), caStage);
  const bundle = writeCaBundle(caStage, join(stage, "etc", "ssl", "certs", "ca-certificates.crt"));
  console.log(`  ${"etc/ssl/certs".padEnd(28)} ca-certificates.crt (${bundle.count} roots, ${(bundle.bytes / 1024).toFixed(0)} KiB)`);

  // 6. Verify the payload can actually start, here rather than on a phone. This is
  //    the check that turns "I copied the packages I thought were needed" into
  //    "the closure is present": a missing soname is invisible until `git clone`
  //    dies with "error while loading shared libraries".
  for (const rel of ["usr/bin/git", "usr/lib/git-core/git", "usr/lib/git-core/git-remote-http"]) {
    if (!existsSync(join(stage, rel))) throw new Error(`git payload is missing ${rel}`);
  }
  const absent = GIT_LIBRARIES.filter((lib) => !existsSync(join(libDir, lib)));
  if (absent.length > 0) {
    throw new Error(
      `git payload is missing ${absent.length} of ${GIT_LIBRARIES.length} libraries:\n  ` +
        `${absent.join("\n  ")}\n` +
        `These come from the GIT_LIBRARY_PACKAGES debs; an upstream version bump can move a ` +
        `soname, and the fix is to re-derive the closure rather than to drop the check.`,
    );
  }
  console.log(`  ${"closure".padEnd(28)} ${GIT_LIBRARIES.length}/${GIT_LIBRARIES.length} sonames present`);

  const dst = join(ASSETS, `git${PAYLOAD_SUFFIX}`);
  // `gzip -9` rather than tar's `-z` (gzip -6) to match the node repack below:
  // measured, the difference is 7.26 vs 7.28 MiB, so this is consistency, not
  // savings. DETERMINISTIC_TAR is the load-bearing part — see its comment.
  execFileSync("bash", [
    "-c",
    `tar ${DETERMINISTIC_TAR.join(" ")} -cf - -C ${JSON.stringify(stage)} . | gzip -9 > ${JSON.stringify(dst)}`,
  ]);
  rmSync(stage, { recursive: true, force: true });
  rmSync(libStage, { recursive: true, force: true });
  rmSync(caStage, { recursive: true, force: true });
  const mib = (statSync(dst).size / 1024 / 1024).toFixed(2);
  console.log(`  ${"git.tgz".padEnd(28)} ${mib.padStart(8)} MiB  ok (git 2.43.0 + ${GIT_LIBRARIES.length} libs + CA)`);
}


function main() {
  const resolveOnly = process.argv.includes("--resolve-only");
  const lock = existsSync(LOCK) ? JSON.parse(readFileSync(LOCK, "utf8")) : { artifacts: {} };

  mkdirSync(CACHE, { recursive: true });
  mkdirSync(JNI, { recursive: true });
  mkdirSync(ASSETS, { recursive: true });

  console.log("pi runtime assembly");
  console.log(`  cache   ${CACHE}`);
  console.log(`  jniLibs ${JNI}`);
  console.log(`  assets  ${ASSETS}\n`);

  // 1. Fetch and verify every artifact.
  for (const [name, spec] of Object.entries(ARTIFACTS)) {
    const file = join(CACHE, spec.url.split("/").pop());
    download(spec.url, file);
    const digest = sha256(file);
    // Where upstream publishes a digest for the asset itself, the bytes must match
    // it — checked before anything is recorded, and therefore also under
    // `--resolve-only`. That mode rewrites the lock from the bytes on disk, so
    // without this it would re-pin a substituted binary instead of stopping. See
    // PROROOT_RELEASE.
    if (spec.sha256 && digest !== spec.sha256) {
      throw new Error(
        `sha256 mismatch against upstream's published digest for ${name}\n` +
          `  published ${spec.sha256}\n` +
          `  actual    ${digest}\n` +
          `These are not the bytes the release notes describe. Do not pin them and do ` +
          `not re-run with --resolve-only: re-download, compare by hand, and treat a ` +
          `persistent difference as a supply-chain event.`,
      );
    }
    const pinned = lock.artifacts[name]?.sha256 ?? null;
    if (resolveOnly || pinned === null) {
      lock.artifacts[name] = { url: spec.url, sha256: digest, why: spec.why };
      console.log(`  ${name.padEnd(16)} ${digest.slice(0, 16)}…  (recorded)`);
    } else if (pinned !== digest) {
      throw new Error(
        `sha256 mismatch for ${name}\n  pinned  ${pinned}\n  actual  ${digest}\n` +
          `Upstream moved. Inspect the change, then re-run with --resolve-only.`,
      );
    } else {
      console.log(`  ${name.padEnd(16)} ${digest.slice(0, 16)}…  ok`);
    }
  }

  if (resolveOnly) {
    writeFileSync(LOCK, JSON.stringify(lock, null, "\t") + "\n");
    console.log(`\nwrote ${LOCK}`);
    return;
  }

  // 2. proot + loader + their libs -> jniLibs, disguised as lib*.so.
  const prootStage = join(STAGE, "proot");
  rmSync(prootStage, { recursive: true, force: true });
  for (const name of ["proot", "libtalloc", "libandroidShmem"]) {
    const deb = join(CACHE, ARTIFACTS[name].url.split("/").pop());
    extract("deb", deb, join(prootStage, name));
  }
  console.log("\njniLibs:");
  for (const item of JNI_PAYLOAD) {
    // Termux debs install into `./data/data/com.termux/files/`, and JNI_PAYLOAD's
    // `from` is already relative to that `files/` directory ("usr/bin/proot"),
    // so it must NOT be appended after another "usr". Appending one produced
    // ".../files/usr/usr/bin/proot" and the whole assemble step failed with
    // "missing usr/bin/proot in the proot payloads" — verified by extracting the
    // real deb and listing it:
    //   ./data/data/com.termux/files/usr/bin/proot
    //   ./data/data/com.termux/files/usr/libexec/proot/loader
    //   ./data/data/com.termux/files/usr/lib/libtalloc.so.2.4.3
    //   ./data/data/com.termux/files/usr/lib/libandroid-shmem.so
    const candidates = ["proot", "libtalloc", "libandroidShmem"].map((name) =>
      join(prootStage, name, "data", "data", "com.termux", "files", item.from),
    );
    const src = candidates.find((p) => existsSync(p));
    if (!src) {
      throw new Error(
        `missing ${item.from} in the proot payloads; looked for:\n  ${candidates.join("\n  ")}`,
      );
    }
    const dst = join(JNI, item.to);
    writeFileSync(dst, readFileSync(src));
    verifyElfDisguise(dst, item.interpreter);
    const kib = (statSync(dst).size / 1024).toFixed(1);
    console.log(`  ${item.to.padEnd(24)} ${kib.padStart(8)} KiB  ok`);
  }

  // 2b. proroot's five release assets -> jniLibs, under their own names. There is
  //     nothing to extract: the assets are the files, already downloaded and
  //     verified against both upstream's published digests and the lock in step 1.
  console.log("\njniLibs (proroot):");
  for (const item of PROROOT_JNI_PAYLOAD) {
    const spec = ARTIFACTS[item.name];
    if (!spec) throw new Error(`PROROOT_JNI_PAYLOAD names ${item.name}, which is not in ARTIFACTS`);
    const src = join(CACHE, basename(spec.url));
    if (!existsSync(src)) {
      throw new Error(
        `missing ${basename(spec.url)} in the download cache; step 1 should have ` +
          `fetched ${spec.url}`,
      );
    }
    const dst = join(JNI, item.to);
    writeFileSync(dst, readFileSync(src));
    verifyElfDisguise(dst, item.interpreter);
    const kib = (statSync(dst).size / 1024).toFixed(1);
    console.log(`  ${item.to.padEnd(24)} ${kib.padStart(8)} KiB  ok`);
  }
  console.log(`  (proroot ${PROROOT_VERSION}; not wired into argv/env — nothing executes these yet)`);

  // 3. Userland payloads stay compressed in assets; the app unpacks them on
  //    first launch into <files>/pi/runtime (volatile) and <files>/pi/pi (kept).
  //
  //    Node is re-compressed from .tar.xz to gzip here. Java has no xz
  //    decoder, and shipping one to unpack a single archive at first launch is
  //    not worth the bytes — so the conversion happens at build time instead.
  console.log("\nassets:");
  const assets = [
    ["ubuntuBase", `ubuntu-base${PAYLOAD_SUFFIX}`],
    ["ripgrep", `ripgrep${PAYLOAD_SUFFIX}`],
    ["fd", `fd${PAYLOAD_SUFFIX}`],
  ];
  for (const [name, out] of assets) {
    const src = join(CACHE, ARTIFACTS[name].url.split("/").pop());
    const dst = join(ASSETS, out);
    writeFileSync(dst, readFileSync(src));
    const mib = (statSync(dst).size / 1024 / 1024).toFixed(1);
    console.log(`  ${out.padEnd(24)} ${mib.padStart(8)} MiB  ok`);
  }

  {
    const src = join(CACHE, ARTIFACTS.node.url.split("/").pop());
    const dst = join(ASSETS, `node${PAYLOAD_SUFFIX}`);
    // Pipe rather than a temp tree: tar streams, and a rootfs-shaped extraction
    // here would duplicate ~150 MB of files on the build host for no reason.
    execFileSync("bash", ["-c", `xz -dc ${JSON.stringify(src)} | gzip -9 > ${JSON.stringify(dst)}`]);
    const mib = (statSync(dst).size / 1024 / 1024).toFixed(1);
    console.log(`  ${"node.tgz".padEnd(24)} ${mib.padStart(8)} MiB  ok (xz -> gz)`);
  }

  // 3b. git, re-assembled from its .deb plus its library closure plus a generated
  //     CA bundle. It is not one of the pass-through payloads above because three
  //     different things have to happen to it; see assembleGitPayload.
  assembleGitPayload();

  // 4. pi itself. Installed here so the first launch works offline; installing
  //    it on device would need npm and a network before the app is usable.
  //    --omit=optional is what keeps this ~150 MB instead of ~440 MB: pi's
  //    optional deps are cloud-provider SDKs, and the ones a phone user has
  //    credentials for are all plain HTTP anyway.
  {
    const dst = join(ASSETS, `pi-engine${PAYLOAD_SUFFIX}`);
    const stage = join(STAGE, "engine");
    rmSync(stage, { recursive: true, force: true });
    mkdirSync(stage, { recursive: true });
    const spec = `@earendil-works/pi-coding-agent@${PI_VERSION}`;
    console.log(`\nengine: ${spec}`);
    // Output is captured rather than inherited so the extraction warning below can be
    // seen; it is echoed either way, so the progress a person reads on a terminal is
    // unchanged apart from arriving at the end of the install.
    const npm = spawnSync(
      "npm",
      ["install", "--ignore-scripts", "--omit=dev", "--omit=optional", "--no-audit", "--no-fund", spec],
      { cwd: stage, encoding: "utf8" },
    );
    if (npm.stdout) process.stdout.write(npm.stdout);
    if (npm.stderr) process.stderr.write(npm.stderr);
    if (npm.status !== 0) {
      throw new Error(`npm install ${spec} failed with status ${npm.status}`);
    }
    // npm unpacks 128 packages in parallel and can fail to create an entry while still
    // exiting 0. Measured once in four consecutive builds: a single
    //   npm warn tar TAR_ENTRY_ERROR ENOENT: ... lstat '.../openai/resources/beta'
    // and the payload that run produced was a *different, smaller* engine tree. Nothing
    // else noticed — a changed digest is indistinguishable from a legitimate payload
    // change, and the revision it produced was simply the new one. So a warning here is
    // treated as a truncated payload and fails the build: an engine missing files would
    // otherwise reach a device as "some pi feature does not work".
    if (/TAR_ENTRY_ERROR/.test(npm.stderr ?? "")) {
      throw new Error(
        `npm install ${spec} reported a tar extraction error, so the engine payload ` +
          `would be incomplete. Re-run the build; if it repeats, the npm cache ` +
          `(npm cache verify) or the registry response for one of the 128 packages is bad.`,
      );
    }
    // DETERMINISTIC_TAR: without it this archive carries the mtime of every file
    // `npm install` just wrote, so the same pinned engine produced a different
    // pi-engine.tgz — and a different runtime-revision.txt — on every build.
    execFileSync("tar", [...DETERMINISTIC_TAR, "-czf", dst, "-C", stage, "."]);
    const mib = (statSync(dst).size / 1024 / 1024).toFixed(1);
    console.log(`  ${"pi-engine.tgz".padEnd(24)} ${mib.padStart(8)} MiB  ok (pi ${PI_VERSION})`);
    rmSync(stage, { recursive: true, force: true });
  }

  const produced = [
    ...JNI_PAYLOAD.map((i) => join(JNI, i.to)),
    // proroot is counted here — and therefore in runtime-revision.txt — for the same
    // reason the proot payloads are: this run put these bytes in the APK. That is not
    // free. A revision move makes devices re-unpack the runtime tree, and
    // `RuntimeProvisioner.wipe()` deletes whatever the user installed inside the
    // guest. It is the cost the proot payloads already carry, and leaving proroot out
    // would make the digest describe less than the APK contains.
    //
    // That cost is only bearable because the digest now actually tracks payload
    // *content*: until DETERMINISTIC_TAR was applied to `git.tgz` and
    // `pi-engine.tgz`, it moved on every clean build and the guard never held.
    ...PROROOT_JNI_PAYLOAD.map((i) => join(JNI, i.to)),
    ...assets.map(([, o]) => join(ASSETS, o)),
    join(ASSETS, `node${PAYLOAD_SUFFIX}`),
    join(ASSETS, `git${PAYLOAD_SUFFIX}`),
    join(ASSETS, `pi-engine${PAYLOAD_SUFFIX}`),
  ];
  const total = produced.reduce((sum, p) => sum + statSync(p).size, 0);
  console.log(`\npayload total ${(total / 1024 / 1024).toFixed(1)} MiB`);
  console.log("  (this is what the APK grows by, before compression)\n");

  // The one failure in this pipeline that a build cannot notice: AGP gunzips an
  // asset whose file extension is `gz` while it merges assets, and renames it at
  // the same time (AssetItem.shouldBeUngzipped -> Files.getNameWithoutExtension).
  // The app then asks for a name the APK does not contain and the runtime never
  // provisions — silently, with a *larger* APK as the only clue. `.tgz` exists to
  // stay out of that rule, so a name that falls back into it must fail here.
  const bad = produced
    .map((p) => basename(p))
    .filter((name) => name.split(".").pop().toLowerCase() === "gz");
  if (bad.length > 0) {
    throw new Error(
      `payload name(s) would be gunzipped and renamed by AGP: ${bad.join(", ")}\n` +
        `AGP's AssetItem.shouldBeUngzipped is Files.getFileExtension(name).equals("gz"), ` +
        `and it renames with Files.getNameWithoutExtension — so a file called ` +
        `<x>.tar.gz lands in the APK as <x>.tar, expanded, and RuntimeProvisioner ` +
        `asks for a name that is not there. Rename it to ${PAYLOAD_SUFFIX}.`,
    );
  }

  const payloadDigests = writePayloadMeta([
    // Every path below is relative to `<files>/pi/runtime`, which is the tree the app
    // extracts into: `rootfs/...` for things that land inside the Ubuntu userland,
    // `rootfs/root/.pi/agent/bin/...` for the two tools, and nothing at all for the
    // proroot `.so` files (Android's native-library extractor installs those into
    // `nativeLibraryDir`, outside this tree; the app never prunes from them).
    { name: "ubuntu-base", archive: join(ASSETS, `ubuntu-base${PAYLOAD_SUFFIX}`), prefix: "rootfs" },
    {
      name: "node",
      archive: join(ASSETS, `node${PAYLOAD_SUFFIX}`),
      prefix: "rootfs/opt/node",
      // The archive has one top-level `node-vX-linux-arm64/` directory, which
      // RuntimeProvisioner.extractNode strips by moving that directory to
      // `<rootfs>/opt/node`. The version in the name is why this strips the first
      // component instead of matching a literal.
      stripFirst: true,
      extra: [
        "rootfs/usr/local/bin/node",
        "rootfs/usr/local/bin/npm",
        "rootfs/usr/local/bin/npx",
      ],
    },
    {
      // The archive is a whole distribution tree, but `installTool` installs exactly
      // one binary — the file named `rg` inside it — and writes it to two places. Only
      // the rootfs copy is inside the volatile tree and therefore listed. The durable
      // copy at `<files>/pi/.pi/agent/bin/rg` is deliberately **not** in any list: it
      // lives in the user's own directory, and "never delete anything outside the
      // lists" is precisely what keeps this feature from touching it.
      name: "ripgrep",
      archive: join(ASSETS, `ripgrep${PAYLOAD_SUFFIX}`),
      literal: ["rootfs/root/.pi/agent/bin/rg", "rootfs/usr/local/bin/rg"],
    },
    {
      name: "fd",
      archive: join(ASSETS, `fd${PAYLOAD_SUFFIX}`),
      literal: ["rootfs/root/.pi/agent/bin/fd", "rootfs/usr/local/bin/fd"],
    },
    { name: "git", archive: join(ASSETS, `git${PAYLOAD_SUFFIX}`), prefix: "rootfs" },
    {
      name: "pi-engine",
      archive: join(ASSETS, `pi-engine${PAYLOAD_SUFFIX}`),
      prefix: "rootfs/opt/pi",
      // `bin/pi` is the launcher wrapper `extractEngine` writes by hand, and
      // `/usr/local/bin/pi` is the symlink to it; neither is inside the archive.
      extra: ["rootfs/opt/pi/bin/pi", "rootfs/usr/local/bin/pi"],
    },
    ...PROROOT_JNI_PAYLOAD.map((item) => ({
      name: item.to,
      archive: join(JNI, item.to),
      literal: [],
    })),
  ]);

  writeRevision(payloadDigests);
}

/**
 * Write `assets/runtime-payloads/<name>.digest` and `<name>.list` for every payload, and
 * return the `name digest` lines the revision is derived from.
 *
 * ## Why the app needs these two files per payload
 *
 * `RuntimeProvisioner` used to compare one global digest with `<files>/pi/runtime/.stamp`
 * and, on any difference, delete the whole runtime tree and re-extract all six archives.
 * Any single payload change therefore destroyed everything the user had installed inside
 * the guest. Per-payload state is what replaces that:
 *
 *  - `<name>.digest` is 16 hex characters of the payload's SHA-256. The same truncation
 *    `runtime-revision.txt` has always used, and it detects a changed payload; it is not
 *    a signature. A device whose recorded digest for a payload equals this one does not
 *    touch that payload at all.
 *  - `<name>.list` is every **non-directory** path the payload installs, relative to
 *    `<files>/pi/runtime`, sorted, one per line, no leading `./`. After extracting a
 *    changed payload over the tree, the app deletes exactly `oldList − newList` among
 *    the paths that are still present — so a file in neither list (anything the user
 *    created) can never be in the delete set, and a directory is never deleted (a
 *    directory delete could take files the lists do not own).
 *
 * ## Why the lists are generated here rather than recorded on the device
 *
 * Because the *installed* layout is not the archive's layout: node's top directory is
 * renamed to `opt/node`, ripgrep/fd are hunted by name and installed as one binary,
 * pi-engine gets a launcher wrapper that is not in its archive. Only the assembler knows
 * all of that, and `tar -tzf` on the archive it just wrote is the exact answer for the
 * four payloads whose bytes are copied through.
 */
function writePayloadMeta(payloads) {
  mkdirSync(PAYLOAD_META, { recursive: true });
  const digests = [];
  console.log("\npayload metadata:");
  for (const payload of payloads) {
    if (!existsSync(payload.archive)) {
      throw new Error(`payload metadata: ${payload.archive} is missing`);
    }
    const digest = sha256(payload.archive).slice(0, 16);
    const paths = payload.literal ?? payloadPaths(payload.archive, payload);
    writeFileSync(join(PAYLOAD_META, `${payload.name}.digest`), `${digest}\n`);
    writeFileSync(
      join(PAYLOAD_META, `${payload.name}.list`),
      paths.length === 0 ? "" : `${paths.join("\n")}\n`,
    );
    digests.push(`${payload.name} ${digest}`);
    const kib = (statSync(join(PAYLOAD_META, `${payload.name}.list`)).size / 1024).toFixed(1);
    console.log(
      `  ${(payload.name + ".list").padEnd(28)} ${String(paths.length).padStart(6)} paths ${kib.padStart(9)} KiB`,
    );
  }
  return digests;
}

/**
 * The paths one payload installs into `<files>/pi/runtime`, relative to that tree.
 *
 * Read from the archive with `tar -tzf` rather than by walking the staged tree: the
 * archive *is* what the device receives, so a discrepancy between the two is impossible
 * by construction. Directory entries (`tar` prints them with a trailing `/`) are
 * dropped — they are never deleted on device, see `writePayloadMeta` — and the optional
 * `prefix` / `stripFirst` / `extra` describe the transforms `RuntimeProvisioner` applies
 * when it installs that particular payload.
 */
function payloadPaths(archive, { prefix = "", stripFirst = false, extra = [] } = {}) {
  const out = new Set(extra);
  const listing = execFileSync("tar", ["-tzf", archive], {
    encoding: "utf8",
    maxBuffer: 512 * 1024 * 1024,
  });
  for (const raw of listing.split("\n")) {
    let name = raw.trim().replace(/^\.\//, "");
    if (name.length === 0 || name.endsWith("/")) continue;
    if (stripFirst) {
      const slash = name.indexOf("/");
      if (slash < 0) continue;
      name = name.slice(slash + 1);
      if (name.length === 0) continue;
    }
    out.add(prefix.length > 0 ? `${prefix}/${name}` : name);
  }
  return [...out].sort();
}

/**
 * Record, in `assets/runtime-revision.txt`, the digest **of the per-payload digests** —
 * the value `RuntimeProvisioner.ensureReady` stamps a device with.
 *
 * ## Why this is generated and not a constant someone remembers to bump
 *
 * It used to be `RuntimeProvisioner.RUNTIME_REVISION`, a hand-written string. The
 * failure that arrangement invites is not symmetric with the work it costs:
 *
 *  - change a payload and forget to bump it, and every device keeps the tree it
 *    already unpacked. The new APK installs, the app looks fine, and it is running
 *    the previous Node and the previous pi. The build produced nothing. Nobody
 *    finds out, because nothing on the device disagrees with anything else.
 *  - bump it when nothing changed, and every device re-reads and re-compares state for
 *    nothing.
 *
 * Deriving it removes the first case entirely — the digest changes exactly when the
 * payload bytes do — and makes the second case impossible to do by accident, because
 * a hand edit to the number no longer has any effect. It is written **outside**
 * `assets/runtime/` on purpose: that directory's invariant, checked by CI, is that
 * every entry in it is one of these payloads, byte for byte.
 *
 * ## What it is a digest *of*, and what that decides
 *
 * The input is the sorted `name digest` lines from `writePayloadMeta`, so a rename is a
 * change too — which it is: the app asks for these names. Because the app's fast path
 * compares this one value with `.stamp`, a device whose stamp matches reads no
 * per-payload state at all; the revision is the *cheap* question ("is this the payload
 * set I already have?") and the per-payload digests are the *precise* one, asked only
 * when the answer is no. Adding a payload therefore moves the revision once, and the
 * payloads that did not change are still not re-extracted.
 */
function writeRevision(payloadDigests) {
  const digest = createHash("sha256");
  for (const line of [...payloadDigests].sort()) {
    digest.update(line);
    digest.update("\n");
  }
  const value = digest.digest("hex").slice(0, 16);
  const dst = join(dirname(ASSETS), "runtime-revision.txt");
  writeFileSync(dst, `${value}\n`);
  console.log(`revision ${value}  (${basename(dst)})`);
}

main();
