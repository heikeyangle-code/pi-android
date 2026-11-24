# Pi Android Clean-Slate Overhaul & `v0.2.1` Progress

> **Handoff checkpoint for resuming work** (branch: `main`, `versionCode = 11`, `versionName = "0.2.1"`).
> Remove this document once Phase 5 (`v0.2.1` Release) is complete.

---

## Core Mandates (User-Approved)

1. **Zero backwards-compatibility / zero legacy shims**: No existing users to accommodate. Completely remove old 4-layer `/workspace/pi/workspaces/workspace-1` paths, `/workspace` dual mounts, `CLASSIC` typography preset, legacy migration shims, dead sheets/screens, and all stock Material 3 widgets (`Button`/`OutlinedButton`/`TextButton`/`IconButton`/`Slider`).
2. **Official Pi SDK first**: Strictly align with `@earendil-works/pi-coding-agent` (`1.6.0`). Do not invent non-SDK features.
3. **Explicit user exception**: **Keep and redraw `PiSystemPaletteSheet`** with custom Studio slim-track gradient sliders (already done in Phase 2).
4. **Commit & push frequently**: Push to `main` after each cohesive phase after passing all 4 static guards (`check-nested-comments.py`, `check-android-apis.sh`, `check-i18n.mjs`, `check-startup-perf.mjs`) and `sdk-host` tests when `sdk-host`/extensions change.
5. **Final deliverable**: Publish GitHub Release `v0.2.1` once all phases are done and **all 6 CI jobs pass**, with bilingual (Chinese + English) release notes covering `v0.2.0..HEAD` (`1a93d82..HEAD`) and both signed APKs (`pi-android-0.2.1-sideload28-arm64.apk` and `pi-android-0.2.1-modern36-arm64.apk`).

---

## Completed & Pushed

| Phase | Commit | Summary | CI Status |
|---|---|---|---|
| Pre-overhaul | `cf48c35` | Fixed TailFollow break on card toggle, redid full-screen `MessageTextSelectDialog`, fixed IME carry-over into Settings, fixed extension TUI half-screen/right-shift Canvas offset (`TerminalCellGrid.kt` / `ExtensionSurfaces.kt`), updated mascot app icon (all mipmaps), rebuilt 4 README showcase PNGs (3 dark + 1 light) & updated `README.md` / `README.zh-CN.md`. | **6/6 PASS** (`45432462627`) |
| **Phase 1** | `8070023` | Clean-slate `/root/projects/<name>` layout (default `/root/projects/default`, i.e., `~/projects/default`, external mounts at `/mnt/external`). Purged legacy 4-layer `/workspace/pi/workspaces/workspace-1`, `workspace-N` numbering, `/workspace` dual terminal mount, `guestPathAliases`, and pre-2026 migration shims across 24 files (`-797` net lines). Updated `pi-android-bridge`, rebuilt `host.bundle.mjs` (339/339 `sdk-host` contract + runtime + `import-audit` passed), added `ProjectPathLayoutTest.kt`. | **6/6 PASS** (`45441801109`) |
| **Phase 2** | `f033446` | Unified Studio Design System & 100% purge of stock M3 `Button`/`OutlinedButton`/`TextButton`/`IconButton`/`Slider` across 35 files (`-1079` net lines): added `PiControls.kt` (`PiPrimaryButton`, `PiSecondaryButton`, `PiDestructiveButton`, `PiTextAction`, `PiIconSquareButton`, `PiSheetActionBar`, `PiSwitch`, custom Canvas `PiSlider`, `PiDivider`, `ClearDialogPlatformDim`); fixed Light/Dark surface ladder & `PiTheme.isDark` + 15 M3 Typography slots; deleted `CLASSIC` typography preset & migration shims; fixed monotonic heading scale; fixed `PiSeg` contrast; added `sheetCardBg`/`sheetControlBg` in `PiSheetChrome.kt`; completely redrew `PiSystemPaletteSheet.kt`; folded Terminal/Device/Licenses into `SettingsHome.kt` & deleted `OtherSettingsScreen.kt`, `DiscoverySource.kt`, `PiThemeEditorSheet`, `PiDefaultToolsSheet`, `PiSettingsSheet`, `PiSettingsValueRow`, `PiPillSwitch`, `PiStateNode`; fixed whole-row switches, `PiInfoNote` double inset, `PiLinkCardView` `10sp->12sp`, `0.5.dp` dividers. | **4/6 PASS** (`SDK Host`, `Static safety guards`, `tawcroot`, `JVM unit tests` all green; `APK sideload28` & `APK modern36` in progress in Run `45445092385`) |

---

## Remaining Work (Execute Sequentially, Commit & Push After Each Phase)

### Step 0 — Verify `f033446` APK CI Results
- Check GitHub Actions Run `45445092385` (`APK (sideload28)` & `APK (modern36)`) to confirm 6/6 green.

### Phase 3 — Full Project & Files + Terminal Studio Overhaul → Commit & Push
1. **Project & Files** (`ProjectScreen.kt`, `ProjectFiles.kt`, `ProjectRows.kt`, `ProjectSheets.kt`, `WorkspaceChrome.kt`, `WorkspaceExternalPicker.kt`):
   - Replace bloated 160dp `WorkspaceHeroCard` with interactive `ProjectTopBarSwitcher` (`workspaceName ▾` + trust dot + sandbox/device/changes subtitle) in `PiTopBar`, right actions (`Terminal`, `Add`, `MoreVert`).
   - Pin sticky full-width `WorkspacePathBar` directly below `PiTopBar` (**outside** `LazyColumn`):
     - Always-working `↑` Up button (except at `/`).
     - Always-visible `[/ ▾]` Location Jump pill.
     - `⌂` Home button when browsing outside the current project.
     - Horizontally scrollable breadcrumb pills with edge fade.
     - Right-pinned **`设为 CWD`** (`Use as CWD`) pill for **every** writable directory (both inside Linux container `/root/...` and phone storage `/storage/emulated/0/...`).
   - Build `WorkspaceLocationSheet` (using unified `PiSheet`): real path copy card + 2×3 Studio quick-location tiles (`当前项目`, `容器根 /`, `主目录 /root`, `Pi 配置 ~/.pi/agent`, `临时目录 /tmp`, `手机存储 /storage/emulated/0`) + full real ancestor chain with `CWD` & `当前` badges.
   - Seamless dual-tree browsing (`browsingHostFs` cleanly switches between `guestRootfs` and host `/storage/emulated/0`).
   - Sleek single-row `WorkspaceInlineSearchSortBar` + upgraded icon `ProjectConfigQuickCard`s + honest real paths everywhere (`GuestWorkspacePath.shortenForDisplay(entry.guestPath)`).
   - Retire `WsSheet` (migrate all workspace sheets in `ProjectSheets.kt`, `WorkspaceChrome.kt`, `WorkspaceExternalPicker.kt` to unified `PiSheet` with `imePadding()`), fix double `"Reading directory…"` notice, add dot-folder visibility toggle, and remove main-thread `canonicalFile` / `File.exists()` / `file.length()` calls in composition.
2. **Terminal** (`TerminalScreen.kt`, `TerminalPane.kt`, `TerminalKeyBar.kt`, `PiTerminalManager.kt`, `TerminalSettings.kt`):
   - Align `56.dp` TopBar height + `44.dp` circular buttons.
   - Auto-scroll tab strip to active tab; confirm before closing running tabs; smart short tab title (avoid clipping `root@localho…`).
   - Fix CJK font fallback (do not force JetBrains Mono on Chinese labels), fix pinch-to-zoom accumulator, remove `CTRL/ALT` 300ms double-tap lag, add press feedback + haptics + hold-to-repeat on arrow keys, raise `10.sp` keys in `TerminalKeyBar` to `12.sp`, and fix `TerminalSettings` global persistence.

### Phase 4 — Chat, Sessions, Tree, Extensions & Secondary Settings Polish → Commit & Push
1. **Chat / Sessions / Tree / Blocks / Extensions**:
   - Fix P0 bugs:
     - `SessionsScreen.kt`: fix active-session delete dead dialog.
     - `SessionTreeView.kt` & `ExtensionDialogs.kt`: fix invisible containers/secondary buttons inside `PiSheet` using `PiSheetChromeMetrics.sheetCardBg` / `PiSecondaryButton`.
     - `PiImageViewer.kt`: fix decode-error flash (explicit pending state before decode finishes).
     - `ChatScreen.kt`: ensure thinking pill calls `refreshThinkingLevels()`.
     - `ChatSearch.kt`: add `PiAutoFocus` on search field.
     - `MessageActions.kt`: clarify pre-fill label; `ChatAttachments.kt`: tap-to-preview & clean remove badge; `ChatSessionSheets.kt`: add Logout confirmation dialog & delete dead `SessionGridTile`.
   - Strip all remaining `.shadow()` / `shadowElevation` (`SlashPalette.kt`, `MentionPalette.kt`, `ArgumentPalette.kt`, `ExtensionUiHost.kt`, `ExtensionSurfaces.kt`), stock `CircularProgressIndicator` (`ExtensionPanels.kt`), and frozen static `Refresh` icons (`SessionsScreen.kt`, `SessionTreeView.kt`).
   - Slim `ChatTopBar` (≤3 right icons so title is never crushed on 360dp), consolidate `ChatCommandCenterSheet` + `SessionToolsSheet`, keep `Attach/@` usable mid-stream in `ChatComposer` + raise `10.sp` `ComposerExtensionChip` to `12.sp`, remove duplicate Tree `MiniJumpPill`, delete dead `BLOCKED_REASONS` permission-gate strings (`ToolBlockChrome.kt`), dead `EditorBody` (`ExtensionDialogs.kt`), and `BranchSummaryBlock` tutorial text.
   - Add `LocalHapticFeedback` & copy confirmation toasts where missing; wire `SkillInvocationBlock` to `toolsDefaultExpanded`.
2. **Settings / Models / Resources / Device**:
   - Fix Gestalt 18dp header proximity in `RuntimeEngineScreen.kt` & `ModelsScreen.kt`.
   - Fix invisible `AppUpdateSheet.kt` refresh circle (`sheetControlBg`).
   - Fix `ProviderFormSheet.kt` 6dp button height mismatch (`PiPrimaryButton`/`PiSecondaryButton`) and `ModelsSheets.kt` 3-button narrow-screen overflow.
   - Fix `DeviceCapabilityScreen.kt` horizontal overflow + whole-row switch + Shizuku tap help dialog.
   - Fix `ToolsScreen.kt` whole-row switch + unified search field; `McpServersScreen.kt` empty-state duplicate CTAs; `ProviderConnectSheet` boxed model list; `ResourcesHubScreen` `ExtensionFlagsSheet` & `ResourceDetailSheet` polish; `RuntimeEngineScreen` / `SettingsGroupScreen` / `PackagesScreen` / `ResourceTypeScreen` red destructive tones + bounded stderr/diagnostics scroll; `AppearanceScreen.kt` (`10.sp` -> `12.sp`, custom theme delete touch target & active deletion); `LicensesScreen` selectable text & copy icon.
   - Unify remaining `14sp prose` row titles to `PiType.rowTitle` (`15sp Medium`) and machine values to `monoSmall` (`RuntimeEngineScreen.kt`, `ProjectTrustHost.kt`); remove any unused `hostActions` / `themeNotes` / `pickerSearchHint` scaffolding.

### Phase 5 — Automated Release Workflow + 6/6 CI Verification + Publish `v0.2.1`
1. **Add `.github/workflows/release.yml`** (triggered on `push: tags: ['v*']` and `workflow_dispatch` with `tag` input, `permissions: contents: write`):
   - Builds `libtawcroot.so` + both signed APKs (`sideload28` + `modern36`), verifies signing (`apksigner`) & uncompressed payloads, renames them to `pi-android-<ver>-sideload28-arm64.apk` and `pi-android-<ver>-modern36-arm64.apk`, and uploads/attaches them to the GitHub Release using native `GITHUB_TOKEN`.
   - *Why required*: VM GitHub proxy authenticates `api.github.com` and `github.com`, NOT `uploads.github.com` (HTTP 401 on local `gh release upload`).
2. **Delete `docs/OVERHAUL_PROGRESS.md`** once Phase 3–5 work is complete and ready to tag.
3. **Verify 6/6 CI green** on the final `main` commit (`tawcroot`, `SDK Host`, `Static safety guards`, `JVM unit tests`, `APK (sideload28)`, `APK (modern36)`).
4. **Publish Release `v0.2.1`**:
   - Review `git log 1a93d82..HEAD --oneline`, write final bilingual release notes (Chinese primary + collapsible English section).
   - Create/tag `v0.2.1`, run/monitor `release.yml`, and verify both signed APK assets exist via `gh release view v0.2.1 --json assets,url`.

---

## Local Environment Notes (VM)

- **Static guards** (run before every commit):
  ```bash
  python3 tools/check-nested-comments.py && bash tools/check-android-apis.sh && node tools/check-i18n.mjs && node tools/check-startup-perf.mjs
  ```
- **Rebuilding `host.bundle.mjs` & running `sdk-host` tests** (when touching `sdk-host/**` or `pi-extensions/**`):
  - Pinned `@earendil-works/pi-coding-agent` is cached in gitignored `build/pi-contract/node_modules/`.
  - `esbuild` is installed at `/tmp/pi-esbuild/node_modules/esbuild/lib/main.js` (reinstall to `/tmp/pi-esbuild` if VM resets).
  ```bash
  node tools/sdk-host/build-bundle.mjs --esbuild /tmp/pi-esbuild/node_modules/esbuild/lib/main.js
  node tools/sdk-host/contract.mjs && node tools/sdk-host/test.mjs
  ```
- **Surface polarity inside `PiSheet`**: Always use `PiSheetChromeMetrics.sheetCardBg` for cards/inputs and `PiSheetChromeMetrics.sheetControlBg` for secondary buttons so controls contrast cleanly in both Light and Dark themes.
