#!/usr/bin/env python3
"""把某个 commit 的 CI 产物（APK）拉到本机，便于在设备上肉眼验证。

本机不能编译（容器就是用户的手机），CI 是唯一的编译器，所以「改完之后到底长什么样」
只能靠 CI 出来的 APK 装上去看。这个脚本把这一步固定下来。

用法：
    python3 design/ui-refactor/tools/fetch-apk.py              # 最新一次成功的 run
    python3 design/ui-refactor/tools/fetch-apk.py 244368b      # 指定 commit 前缀
    python3 design/ui-refactor/tools/fetch-apk.py 244368b modern36

注意：GitHub 的 archive_download_url 会 302 到一个签过名的地址，而那个地址**不接受**
Authorization 头（带了就 401）。所以流程必须是：带 token 拿 302 → 跟着 Location 不带 token 下载。
"""

from __future__ import annotations

import io
import json
import os
import sys
import urllib.request
import zipfile

REPO = "heikeyangle-code/pi-android"
# 不要把 token 写进仓库（GitHub 的 push protection 会直接拒收）。优先读环境变量，
# 其次读仓库外的本地文件。
def _token() -> str:
    tok = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if tok:
        return tok.strip()
    for path in ("/root/.dsh/gh_token", os.path.expanduser("~/.dsh/gh_token")):
        try:
            with open(path, encoding="utf-8") as fh:
                tok = fh.read().strip()
            if tok:
                return tok
        except OSError:
            continue
    raise SystemExit("缺少 token：设 GH_TOKEN，或把它写进 /root/.dsh/gh_token")


TOKEN = _token()
OUT_DIR = "/sdcard/Download"


def api(url: str, raw: bool = False, follow: bool = True):
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": "dsh",
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {TOKEN}",
        },
    )
    if not follow:
        # 手动处理 302：拿 Location，然后不带 Authorization 去取
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *a, **k):  # noqa: D102
                return None

        opener = urllib.request.build_opener(NoRedirect)
        try:
            r = opener.open(req, timeout=60)
            return r
        except urllib.error.HTTPError as e:
            if e.code in (301, 302, 303, 307, 308):
                return e.headers["Location"]
            raise
    r = urllib.request.urlopen(req, timeout=60)
    data = r.read()
    return data if raw else json.loads(data)


def main() -> int:
    sha = sys.argv[1] if len(sys.argv) > 1 else None
    want = sys.argv[2] if len(sys.argv) > 2 else "sideload28"

    runs = api(f"https://api.github.com/repos/{REPO}/actions/runs?per_page=15")["workflow_runs"]
    run = None
    for r in runs:
        if sha and not r["head_sha"].startswith(sha):
            continue
        if not sha and r["conclusion"] != "success":
            continue
        if r["conclusion"] != "success":
            print(f"! {r['head_sha'][:7]} 的结论是 {r['conclusion']}，没有产物")
            return 2
        run = r
        break
    if run is None:
        print("没找到符合条件的 run")
        return 2

    print(f"run {run['head_sha'][:7]} ({run['head_commit']['message'].splitlines()[0][:50]})")
    arts = api(run["artifacts_url"])["artifacts"]
    art = next((a for a in arts if want in a["name"]), None)
    if art is None:
        print("产物列表：", [a["name"] for a in arts])
        return 2
    print(f"artifact {art['name']}  {art['size_in_bytes'] / 1048576:.1f} MB")

    loc = api(art["archive_download_url"], follow=False)
    print("→ 下载（签名地址，不带 Authorization）")
    with urllib.request.urlopen(loc, timeout=600) as r:  # noqa: S310
        blob = r.read()
    z = zipfile.ZipFile(io.BytesIO(blob))
    apks = [n for n in z.namelist() if n.endswith(".apk")]
    print("压缩包内：", z.namelist())
    if not apks:
        return 3
    os.makedirs(OUT_DIR, exist_ok=True)
    dest = os.path.join(OUT_DIR, f"PI-{run['head_sha'][:7]}-{want}.apk")
    with open(dest, "wb") as fh:
        fh.write(z.read(apks[0]))
    print(f"已写出 {dest}  ({os.path.getsize(dest) / 1048576:.1f} MB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
