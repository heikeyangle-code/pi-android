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
 *                            userland, Node, ripgrep, fd. These never need the
 *                            exec bit on the Android side, so they stay
 *                            compressed and are unpacked on first launch.
 *
 * Build-time only. Uses host `dpkg-deb`/`tar`/`xz`; it never runs on device.
 *
 *   node tools/fetch-runtime.mjs                 # verify + assemble
 *   node tools/fetch-runtime.mjs --resolve-only  # (re)write runtime.lock.json
 */

import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, rmSync, writeFileSync, existsSync, statSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const CACHE = join(ROOT, "build", "downloads");
const STAGE = join(ROOT, "build", "runtime");
const JNI = join(ROOT, "app", "src", "main", "jniLibs", "arm64-v8a");
const ASSETS = join(ROOT, "app", "src", "main", "assets", "runtime");
const LOCK = join(ROOT, "runtime.lock.json");

const TERMUX = "https://packages.termux.dev/apt/termux-main";

/**
 * pi's version. Pinned deliberately: the app's protocol layer is written against
 * this release's RPC surface, and pi has no stability guarantee across minor
 * versions. Bumping this is a decision, not an accident.
 */
const PI_VERSION = "0.85.1";

/** Pinned upstream artifacts. `sha256: null` means "record on first resolve". */
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
};

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
  //    Node is re-compressed from .tar.xz to .tar.gz here. Java has no xz
  //    decoder, and shipping one to unpack a single archive at first launch is
  //    not worth the bytes — so the conversion happens at build time instead.
  console.log("\nassets:");
  const assets = [
    ["ubuntuBase", "ubuntu-base.tar.gz"],
    ["ripgrep", "ripgrep.tar.gz"],
    ["fd", "fd.tar.gz"],
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
    const dst = join(ASSETS, "node.tar.gz");
    // Pipe rather than a temp tree: tar streams, and a rootfs-shaped extraction
    // here would duplicate ~150 MB of files on the build host for no reason.
    execFileSync("bash", ["-c", `xz -dc ${JSON.stringify(src)} | gzip -9 > ${JSON.stringify(dst)}`]);
    const mib = (statSync(dst).size / 1024 / 1024).toFixed(1);
    console.log(`  ${"node.tar.gz".padEnd(24)} ${mib.padStart(8)} MiB  ok (xz -> gz)`);
  }

  // 4. pi itself. Installed here so the first launch works offline; installing
  //    it on device would need npm and a network before the app is usable.
  //    --omit=optional is what keeps this ~150 MB instead of ~440 MB: pi's
  //    optional deps are cloud-provider SDKs, and the ones a phone user has
  //    credentials for are all plain HTTP anyway.
  {
    const dst = join(ASSETS, "pi-engine.tar.gz");
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
    console.log(`  ${"pi-engine.tar.gz".padEnd(24)} ${mib.padStart(8)} MiB  ok (pi ${PI_VERSION})`);
    rmSync(stage, { recursive: true, force: true });
  }

  const produced = [
    ...JNI_PAYLOAD.map((i) => join(JNI, i.to)),
    ...assets.map(([, o]) => join(ASSETS, o)),
    join(ASSETS, "node.tar.gz"),
    join(ASSETS, "pi-engine.tar.gz"),
  ];
  const total = produced.reduce((sum, p) => sum + statSync(p).size, 0);
  console.log(`\npayload total ${(total / 1024 / 1024).toFixed(1)} MiB`);
  console.log("  (this is what the APK grows by, before compression)\n");
}

main();
