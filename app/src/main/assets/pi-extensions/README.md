# Built-in Android Extension (`builtin:pi-android-bridge`)

Pi Android bundles a single precompiled built-in extension directly into `app/src/main/assets/guest/sdk-host/host.bundle.mjs` via `node tools/sdk-host/build-bundle.mjs`.

| Source | Purpose |
|---|---|
| `pi-android-bridge/index.ts` | Registers the 6 device bridge tools (`android_status`, `android_screen`, `android_app`, `android_comm`, `android_device`, `android_shell`) over the app's loopback HTTP bridge (`127.0.0.1:3175`) |
| `pi-android-bridge/client.ts` | Reads `device-bridge.json` (with 0-HTTP cold-start `usableCapabilities` snapshot) and performs authenticated loopback HTTP calls with structured error mapping |

## Design Invariants

- **Official SDK integration**: registered through Pi SDK's `DefaultResourceLoader` (`builtin: true, replaceable: true`) so it starts with zero `jiti`/Babel overhead and can be toggled in **Settings → Resources → Extensions** just like Pi's built-in `mcp` / `codemode` extensions.
- **Strict 0-Token footprint when disabled**: when no capability group is active, all `android_*` tools are unmounted and no system prompt section is injected.
- **No synthetic skill files**: tool guidance lives directly inside each tool's native Pi SDK `promptSnippet` / `promptGuidelines` and the dynamic `systemPromptOptions.sections["phone_bridge"]` section; no `SKILL.md` is generated on disk.
