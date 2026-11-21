/**
 * Preload hook for sdk-host cold-startup import auditing in tools/sdk-host/test.mjs.
 * Records every module resolution matching heavy/deferred modules (`http-dispatcher`,
 * `undici`, `session-selector-search`) to PI_IMPORT_AUDIT_FILE so the test harness can
 * verify they are NEVER loaded during default cold boot (`host.ready` -> `session.open` -> `ext.bound`).
 *
 * In `--no-bundle` fallback mode, Pi's own unbundled `dist/index.js` re-exports
 * `core/http-dispatcher.js`, so we specifically audit imports initiated by `sdk-host/`.
 * In default `bundle` / `--scandir-fix` modes, we audit the entire process.
 */
import { appendFileSync } from "node:fs";
import Module from "node:module";

const auditFile = process.env.PI_IMPORT_AUDIT_FILE;
const noBundle = process.env.PI_HOST_NO_BUNDLE === "1";
if (auditFile && typeof Module.registerHooks === "function") {
  Module.registerHooks({
    resolve(specifier, context, nextResolve) {
      const res = nextResolve(specifier, context);
      const url = res?.url ?? specifier;
      const fromHost = !noBundle || String(context?.parentURL ?? "").includes("/sdk-host/");
      if (fromHost && /http-dispatcher|undici|session-selector-search/.test(url)) {
        try {
          appendFileSync(auditFile, `${url}\n`);
        } catch {
          // ignore
        }
      }
      return res;
    },
  });
}
