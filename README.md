# pi-android

A modern native Android client for the [pi](https://pi.dev) coding agent.

Not a terminal emulator with an Android wrapper, and not a `curl | bash` setup
script. The app drives **pi's own `--mode rpc` protocol** over an app-owned pipe,
and the agent's whole toolchain runs locally on the phone inside a `proot`'d
Ubuntu userland. Nothing is proxied, nothing is translated.

```
Compose UI  →  JSONL-RPC over stdio  →  pi (unmodified)  →  proot + Ubuntu + Node 24
```

## Why it is built this way

**pi is not reimplemented.** Half of pi's capability is its extension ecosystem:
extensions are TypeScript files and npm packages loaded in-process by `jiti`,
able to register tools, override built-ins by name, and register model
providers. A Kotlin rewrite could imitate the *look* of that and never reach the
substance. So the app ships pi as-is and is only a client.

**The runtime is a real glibc Linux, not an embedded JS engine.** npm's
prebuilt binaries for ARM target `linux-arm64` (glibc), not Android's Bionic —
which is why `nodejs-mobile` (frozen at Node 18, below pi's `>= 22.19`
requirement) cannot support the ecosystem. `proot` gives us the userland without
root.

**The UI is a first-class native app.** pi's *semantics* are imported verbatim —
its 53 theme colour tokens, its terminology (session / entry / branch /
compaction / steering / follow-up / thinking level), its state model (tool
pending/success/error, diff colours, the thinking-level colour ramp). pi's
*terminal presentation* is not: the app uses Material 3 surfaces, shapes and
motion, because a phone is not a terminal.

**The one honest escape hatch.** A few extension APIs draw terminal cells
(`ctx.ui.custom`, custom footers, overlays) and are inert outside pi's TUI. The
Workbench tab runs the **original pi TUI in a real PTY**, so those remain
reachable. That is how "100% compatible" is kept honest instead of claimed.

## Layout

| Path | What |
|---|---|
| `rpc/` | Pure Kotlin/JVM. pi's wire protocol: strict-LF framing, all 33 commands, event parsing, transcript projection. No Android dependency, so it is unit-tested with a bare JDK. |
| `app/` | The Android application: Compose UI, runtime provisioning, engine supervisor, device bridge, foreground service. |
| `tools/` | Build-time assembly of the runtime from pinned upstream artifacts. |
| `docs/` | The design documents this is being built against. |

## Building

Requires JDK 17 and an Android SDK with platform 36.

```bash
./gradlew :rpc:test                 # protocol core, no device needed
./gradlew :app:assembleRelease      # targetSdk 28 (sideload, known-good)
./gradlew :app:assembleRelease -Ppi.targetSdk=36   # Play-viable variant
```

### targetSdk is a capability switch

Android 10+ denies `execve()` on files in an app's own data directory — and the
entire pi runtime lives there. `targetSdk 28` is the pre-W^X sandbox that Termux
pins for this reason; the `36` variant instead ships proot's loader through
`jniLibs` (`PROOT_LOADER`) so it can map guest binaries itself. CI builds both so
the modern path is validated on hardware rather than assumed.

### AAPT2 and ARM build hosts

Google publishes AAPT2 for Linux as an **x86_64** binary, so a stock AGP toolchain
cannot assemble an APK on an aarch64 host. There is, however, an aarch64 build in
Debian/Ubuntu's `android-sdk-build-tools`, and AGP will use it if pointed at it:

```bash
apt-get install -y android-sdk-build-tools
./gradlew :app:compileReleaseKotlin \
  -Pandroid.aapt2FromMavenOverride=/usr/lib/android-sdk/build-tools/debian/aapt2
```

Use this for **compile checking on an ARM workstation** — it catches Kotlin and
Compose errors in seconds instead of after a CI round trip. The released artifacts
are still assembled on x86_64 CI, because that is where the toolchain Google
supports actually lives.

Two Kotlin-specific traps that have already cost a CI cycle each:

- **Block comments nest.** Writing `/*` inside a KDoc — easy to do accidentally in
  a glob like `themes/*.json` — opens a *second* comment level, so the KDoc's own
  `*/` closes only the inner one and the rest of the file is swallowed. The
  compiler reports "unclosed comment" at the end of the file.
- **`implementation` vs `api`.** `:rpc` returns `JsonObject` from its public API,
  so its serialization dependency is `api`, not `implementation`.

## Runtime

`tools/fetch-runtime.mjs` assembles two very different payloads:

- **`jniLibs/arm64-v8a/`** — only what Android itself must `execve()`: proot and
  its loader, ~320 KB. These are renamed `lib*.so` because that is the only
  naming the native-library extractor will unpack, and they must be PIE with
  `/system/bin/linker64` as their interpreter; the tool verifies both before
  writing them, because the failure mode otherwise is a bare `ENOENT` at runtime.
- **`assets/runtime/`** — everything that runs *inside* proot (Ubuntu base, Node,
  ripgrep, fd). These never need the exec bit on the Android side.

Every upstream is pinned by SHA-256 in `runtime.lock.json`; the tool refuses to
build if an artifact's hash moves.

## Signing

Release builds are signed with the key in `keystore/pi-sideload.jks`. This is
deliberate, and the trade-off is worth stating plainly:

- CI's generated **debug** keystore differs on every run, so every build would
  refuse to install over the previous one and the user would have to uninstall
  first — every single time. A fixed key makes the app upgrade in place.
- The key **is in the repository**, so anyone who can write to the repository can
  sign an update. That is acceptable for a personal sideload build and **not**
  acceptable for anything distributed through a store.

For a real distribution key, set `PI_KEYSTORE`, `PI_KEYSTORE_PASSWORD`,
`PI_KEY_ALIAS` and `PI_KEY_PASSWORD`; the build prefers them over the committed
key. Switching keys costs one final uninstall, because Android will not replace
an app signed by a different key.

## Licence

MIT for this client. The bundled runtime keeps its own upstream licences —
see `docs/` and the licence texts shipped alongside it.
