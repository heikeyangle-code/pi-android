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

// Both wrapped functions take exactly two parameters (`(path, options)`), so they are spelled
// out rather than collected with `...rest`: the rest form allocates a fresh array on every
// single call, including every call on a path that was never broken.
function wrapSync(orig) {
  return function (path, options) {
    if (!broken.has(path)) {
      try { return orig.call(this, path, options); }
      catch (err) {
        if (!shouldFallback(err, path)) throw err;
        markBroken(path);
      }
    }
    return viaOpendirSync(path, options);
  };
}

function wrapAsync(orig) {
  return function (path, options) {
    if (broken.has(path)) return viaOpendir(path, options);
    return Promise.resolve(orig.call(this, path, options)).catch((err) => {
      if (!shouldFallback(err, path)) throw err;
      markBroken(path);
      return viaOpendir(path, options);
    });
  };
}

// The callback form `fs.readdir(path[, options], callback)` is a **different function object**
// from `fsp.readdir`, so patching the promise API left it uncovered — while it reaches the
// kernel through the same `scandir(3)` and therefore has the same defect. Its own wrapper,
// because the optional middle argument has to be shifted and because the answer has to be
// delivered the way Node delivers it: asynchronously, as `(err, entries)`.
//
// `recursive: true` is deliberately **not** taken over. `viaOpendir` lists one directory, so
// answering a recursive request with a flat listing would replace a loud ENOENT with quietly
// wrong data. Nothing in pi asks for it (checked in `pi-coding-agent/src`), and an extension
// that does keeps the original error rather than a listing it will misread.
function wrapCallback(orig) {
  return function (path, options, callback) {
    if (typeof options === "function") {
      callback = options;
      options = undefined;
    }
    // Any other shape (no callback at all) is the original's to reject: it owns the
    // `ERR_INVALID_CALLBACK`/`ERR_INVALID_ARG_TYPE` sentence the caller expects.
    if (typeof callback !== "function") return orig.apply(this, arguments);
    const recursive = !!(options && typeof options === "object" && options.recursive === true);
    if (broken.has(path) && !recursive) {
      viaOpendir(path, options).then((entries) => callback(null, entries), (err) => callback(err));
      return;
    }
    return orig.call(this, path, options, (err, entries) => {
      if (!err || recursive || !shouldFallback(err, path)) return callback(err, entries);
      markBroken(path);
      viaOpendir(path, options).then((e) => callback(null, e), (e2) => callback(e2));
    });
  };
}

if (!fs[MARK]) {
  fs[MARK] = true;
  fs.readdirSync = wrapSync(fs.readdirSync);
  fsp.readdir = wrapAsync(fsp.readdir);
  if (fs.promises && fs.promises !== fsp) fs.promises.readdir = fsp.readdir;
  fs.readdir = wrapCallback(fs.readdir);
}
