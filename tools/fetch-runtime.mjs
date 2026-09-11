#!/usr/bin/env node
/**
 * Assemble pi's Android runtime from pinned upstream artifacts.
 *
 * Two very different kinds of payload, placed differently on purpose
 * (docs/pi-android-app-design.md §5.2, §19.1):
 *
 *   jniLibs/arm64-v8a/*.so   Only what Android itself must execve(): proot and
 *                            its loader. Android 10+ denies execve() on
 *                            app_data_file, and nativeLibraryDir is the one
 *                            location we may write to that is executable — but
 *                            the extractor only unpacks files matching
 *                            `lib*.so`. Hence the rename, and hence the hard
 *                            requirement that each file is PIE with
 *                            `/system/bin/linker64` as its interpreter
 *                            (verified for these exact binaries: see
 *                            verifyElfDisguise below).
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
 *                            Every one of them is named with PAYLOAD_SUFFIX, and
 *                            that suffix must never be `.gz` — read the constant's
 *                            comment before renaming anything here.
 *
 * Build-time only. Uses host `dpkg-deb`/`tar`/`xz`; it never runs on device.
 *
 *   node tools/fetch-runtime.mjs                 # verify + assemble
 *   node tools/fetch-runtime.mjs --resolve-only  # (re)write runtime.lock.json
 */

import { execFileSync } from "node:child_process";
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
 * Ubuntu's arm64 archive. NOT `archive.ubuntu.com`: the primary archive has no
 * arm64 packages at all, and `ports.ubuntu.com` is the one that carries the
 * architecture `ubuntuBase` below is built for.
 */
const UBUNTU_PORTS = "https://ports.ubuntu.com/ubuntu-ports";

/**
 * pi's version. Pinned deliberately: the app's protocol layer is written against
 * this release's RPC surface, and pi has no stability guarantee across minor
 * versions. Bumping this is a decision, not an accident.
 */
const PI_VERSION = "0.85.1";

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
  // savings.
  execFileSync("bash", [
    "-c",
    `tar -cf - -C ${JSON.stringify(stage)} . | gzip -9 > ${JSON.stringify(dst)}`,
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
    execFileSync(
      "npm",
      ["install", "--ignore-scripts", "--omit=dev", "--omit=optional", "--no-audit", "--no-fund", spec],
      { cwd: stage, stdio: "inherit" },
    );
    execFileSync("tar", ["-czf", dst, "-C", stage, "."]);
    const mib = (statSync(dst).size / 1024 / 1024).toFixed(1);
    console.log(`  ${"pi-engine.tgz".padEnd(24)} ${mib.padStart(8)} MiB  ok (pi ${PI_VERSION})`);
    rmSync(stage, { recursive: true, force: true });
  }

  const produced = [
    ...JNI_PAYLOAD.map((i) => join(JNI, i.to)),
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
}

main();
