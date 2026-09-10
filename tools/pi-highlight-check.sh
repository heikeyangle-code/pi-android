#!/usr/bin/env bash
# Verification for the pi-highlight pi extension (not part of the APK).
#
#   bash tools/pi-highlight-check.sh
#
# 1. `tsc --noEmit` against pi's REAL type definitions (typescript 5.9.3, the
#    version pi pins). The scratch tree is rebuilt from scratch so the run is
#    reproducible, and only `pi-highlight/` is included — the other extensions in
#    the same asset directory belong to other changes and must not decide whether
#    this one typechecks. `tools/typecheck.sh` cannot cover this: it is a Kotlin
#    compiler driver and knows nothing about TypeScript.
# 2. `tools/pi-highlight-check.mjs`: loads the extension through jiti the way pi's
#    loader does, then exercises the HTTP service end to end (parity against pi's
#    own renderer, offset sanity, refusals, latency, RSS).
set -euo pipefail

PI=/root/pi-feasibility/node_modules/@earendil-works/pi-coding-agent
ASSETS=/root/pi-android/app/src/main/assets/pi-extensions
SCRATCH=/tmp/pihl-tsc
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

mkdir -p "$SCRATCH/node_modules/@earendil-works" "$SCRATCH/node_modules/@types"
[ -d "$SCRATCH/node_modules/typescript" ] || (cd "$SCRATCH" && npm install --no-audit --no-fund typescript@5.9.3)
ln -sfn "$PI" "$SCRATCH/node_modules/@earendil-works/pi-coding-agent"
ln -sfn "$PI/node_modules/@earendil-works/pi-ai" "$SCRATCH/node_modules/@earendil-works/pi-ai"
ln -sfn "$PI/node_modules/@earendil-works/pi-tui" "$SCRATCH/node_modules/@earendil-works/pi-tui"
ln -sfn "$PI/node_modules/typebox" "$SCRATCH/node_modules/typebox"
ln -sfn "$PI/node_modules/@types/node" "$SCRATCH/node_modules/@types/node"
ln -sfn "$ASSETS" "$SCRATCH/src"

cat > "$SCRATCH/tsconfig.json" <<'JSON'
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2023"],
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "types": ["node"],
    "strict": true,
    "noEmit": true,
    "allowImportingTsExtensions": true,
    "skipLibCheck": true,
    "esModuleInterop": true,
    "isolatedModules": true,
    "forceConsistentCasingInFileNames": true
  },
  "include": ["src/pi-highlight/**/*.ts"]
}
JSON

echo "== tsc --noEmit against pi $PI (pi-highlight only)"
(cd "$SCRATCH" && ./node_modules/.bin/tsc --noEmit -p tsconfig.json) && echo "tsc: OK"

echo
echo "== runtime + parity harness (jiti load, real HTTP, pi's own renderer as oracle)"
node "$HERE/pi-highlight-check.mjs"
