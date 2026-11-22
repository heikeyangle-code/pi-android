# Pi Android Bundled Extension

This directory holds the single Android extension precompiled into `host.bundle.mjs` and registered with Pi's official `DefaultResourceLoader` (`builtin: true, replaceable: true`):

| Extension | Purpose |
| :--- | :--- |
| `pi-android-bridge/index.ts` | Android device capability tools (`android_status`, `android_screen`, `android_app`, `android_comm`, `android_device`, `android_shell`) over the app's local loopback bridge |

Because it is registered as an official `builtin:pi-android-bridge` factory inside `sdk-host`, it starts with zero `jiti`/Babel overhead and can be toggled in **Settings → Resources → Extensions** just like Pi's built-in `mcp` / `codemode` extensions.
