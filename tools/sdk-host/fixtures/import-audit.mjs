/**
 * Preload hook for sdk-host cold-startup import auditing in tools/sdk-host/test.mjs.
 * Records every module resolution matching heavy/deferred modules (`http-dispatcher`,
 * `undici`, `session-selector-search`) to PI_IMPORT_AUDIT_FILE so the test harness can
 * verify they are NEVER loaded during default cold boot (`host.ready` -> `session.open` -> `ext.bound`).
 */
import { appendFileSync } from "node:fs";
import Module from "node:module";

const auditFile = process.env.PI_IMPORT_AUDIT_FILE;
if (auditFile && typeof Module.registerHooks === "function") {
  Module.registerHooks({
    resolve(specifier, context, nextResolve) {
      const res = nextResolve(specifier, context);
      const url = res?.url ?? specifier;
      if (/http-dispatcher|undici|session-selector-search/.test(url)) {
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
