# The extensions this app ships

Three entries, copied into the agent dir on first boot (`DeviceBridgeController`):

| entry | what it is |
|---|---|
| `pi-android-bridge/index.ts` | one tool per device capability, over the app's loopback bridge |
| `pi-android-permission-gate.ts` | asks before a device tool runs |
| `pi-highlight/index.ts` | syntax highlighting service |

They ship as **TypeScript sources**, and that is deliberate — do not "optimise" it
into a build step. Three facts for whoever comes next:

1. **pi's loader prefers `index.ts` over `index.js`** (`core/extensions/loader.ts:701-709`).
   A directory holding both would load the `.ts`; renaming or deleting the sources
   without updating `PiPackageModel.SHIPPED` and this table would silently change
   which entry runs.

2. **pi loads them with [jiti]**, not with Node's native type stripping
   (`core/extensions/loader.ts:17`, `:501-513`). So the sources are executed
   through a transform at load time, on the engine's own thread of control, and the
   cost is bounded by what the loader reports — measured on this project at 1–2 %
   of CPU during startup.

3. **Pre-compiling them to `.js` was tried and rejected.** The repository's own A/B
   run could not tell the two apart (TypeScript 18.7 s / 17.6 s against JavaScript
   17.7 s / 18.7 s for engine startup, inside the noise), while the price is a third
   asset tree to keep in sync, a second copy of the `SHIPPED` list, and a build step
   between editing an extension and testing it. If startup time is the goal, the
   packed engine entry is where the seconds are (see `PiEngineHost`'s entry
   candidates).

[jiti]: https://github.com/unjs/jiti
