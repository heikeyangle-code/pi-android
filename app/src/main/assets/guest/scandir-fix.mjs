// Fallback for hosts where libc `scandir(3)` is broken but `opendir(3)`+`readdir(3)` work.
// Written CJS-first on purpose: importing `node:fs` here would make Node snapshot its ESM
// facade before we patch it, and `import * as fs from "node:fs"` would keep the original.
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const fs = require("node:fs");
const fsp = require("node:fs/promises");

const MARK = Symbol.for("pi.scandirFallback");

// Paths already known to fail: after the first ENOENT-on-an-existing-directory there is no
// reason to pay for the failing call again on that path. Learned once per path per process.
const broken = new Set();
const BROKEN_LIMIT = 512;

function markBroken(path) {
  if (typeof path === "string" && broken.size < BROKEN_LIMIT) broken.add(path);
}

function viaOpendirSync(path, options) {
  const wantTypes = !!(options && typeof options === "object" && options.withFileTypes === true);
  const out = [];
  const handle = fs.opendirSync(path);
  try {
    let entry;
    while ((entry = handle.readSync()) !== null) out.push(wantTypes ? entry : entry.name);
  } finally {
    handle.closeSync();
  }
  return out;
}

async function viaOpendir(path, options) {
  const wantTypes = !!(options && typeof options === "object" && options.withFileTypes === true);
  const out = [];
  const handle = await fsp.opendir(path);
  try {
    // Explicit read() rather than `for await`: the async iterator closes the handle itself
    // when it finishes, and the close() below would then throw ERR_DIR_CLOSED.
    let entry;
    while ((entry = await handle.read()) !== null) out.push(wantTypes ? entry : entry.name);
  } finally {
    await handle.close();
  }
  return out;
}

// Only take over when the original threw ENOENT for a directory that stat() says is there.
function shouldFallback(err, path) {
  if (!err || err.code !== "ENOENT") return false;
  try { return fs.statSync(path).isDirectory(); } catch { return false; }
}

function wrapSync(orig) {
  return function (path, ...rest) {
    if (!broken.has(path)) {
      try { return orig.call(this, path, ...rest); }
      catch (err) {
        if (!shouldFallback(err, path)) throw err;
        markBroken(path);
      }
    }
    return viaOpendirSync(path, rest[0]);
  };
}

function wrapAsync(orig) {
  return function (path, ...rest) {
    if (broken.has(path)) return viaOpendir(path, rest[0]);
    return Promise.resolve(orig.call(this, path, ...rest)).catch((err) => {
      if (!shouldFallback(err, path)) throw err;
      markBroken(path);
      return viaOpendir(path, rest[0]);
    });
  };
}

if (!fs[MARK]) {
  fs[MARK] = true;
  fs.readdirSync = wrapSync(fs.readdirSync);
  fsp.readdir = wrapAsync(fsp.readdir);
  if (fs.promises && fs.promises !== fsp) fs.promises.readdir = fsp.readdir;
}
