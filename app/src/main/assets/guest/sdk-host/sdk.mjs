/**
 * SDK 加载器：**先发 host.ready、再异步预加载**，并且优先走 pi 的打包入口。
 *
 * 为什么优先 `dist/bundle/index.js`：包的 `exports["."]` 指向 `dist/index.js`，那是几千个小模块，
 * 在 proot / proroot 下每个文件的 open/stat 都要被拦截翻译，冷启动会慢十余秒。
 * `dist/bundle/index.js` 是同一份官方 SDK 的打包版（导出全部公开符号），加载速度显著更快。
 *
 * 它**不是**上游承诺的入口（`exports` 里只有 `.`），所以：
 *   - 只在「找得到文件」时用，找不到或加载失败就回退到包名；
 *   - tools/sdk-host/contract.mjs 在 CI 里断言它存在且导出我们要用的符号，pi 升级把它改掉会红灯。
 */
import { existsSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const PKG = "@earendil-works/pi-coding-agent";
let cached;
let pending;
let via = "unloaded";

/**
 * 宿主和引擎不在同一棵 node_modules 下（guest 里：宿主 `/opt/pi/sdk-host/`，引擎
 * `/opt/pi/node_modules/@earendil-works/pi-coding-agent`），Node 的 ESM 解析又**不认
 * NODE_PATH**，所以包目录必须由启动方显式给（`--pi-dir`）。只有开发机上包就在祖先
 * node_modules 里时，才会走到末尾的包名回退。
 *
 * 并发调用（如 main.mjs 在 host.ready 后立刻预热 + session.open 紧接着到来）复用同一个 Promise。
 */
export function loadSdk(piDir) {
  if (cached) return Promise.resolve(cached);
  if (pending) return pending;
  pending = (async () => {
    const started = Date.now();
    const tryImport = async (file, label) => {
      if (!existsSync(file)) return false;
      try {
        cached = await import(pathToFileURL(file).href);
        via = label;
        return true;
      } catch (error) {
        process.stderr.write(`[host] ${label} import failed: ${error?.message ?? error}\n`);
        return false;
      }
    };
    try {
      if (piDir) {
        const useBundle = process.env.PI_HOST_NO_BUNDLE !== "1";
        if (useBundle) await tryImport(join(piDir, "dist", "bundle", "index.js"), "bundle");
        if (!cached) await tryImport(join(piDir, "dist", "index.js"), "package");
      }
      if (!cached) {
        cached = await import(PKG);
        via = "package";
      }
      process.stderr.write(`[host] sdk via ${via} in ${Date.now() - started}ms\n`);
      return cached;
    } finally {
      if (!cached) pending = null;
    }
  })();
  return pending;
}

export const getLoadedSdk = () => {
  if (!cached) throw new Error("Official Pi SDK has not finished loading yet");
  return cached;
};

export const sdkVia = () => via;
