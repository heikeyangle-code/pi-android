#!/usr/bin/env python3
"""
Reproducible README asset renderer for Pi for Android.

Uses the repository's own bundled fonts (IBM Plex Sans SC, JetBrains Mono),
offline MathJax 4.1.3, and offline Mermaid 12.2.0 inside headless Chromium
to generate all static WebP galleries and the 16-second chat demo GIF/MP4.

Usage:
  python3 tools/readme-assets/render.py
  python3 tools/readme-assets/render.py --only static
  python3 tools/readme-assets/render.py --only demo --lang zh
"""

import argparse
import functools
import http.server
import os
import shutil
import socketserver
import subprocess
import sys
import tempfile
import threading
from pathlib import Path
from playwright.sync_api import sync_playwright

REPO_ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = REPO_ROOT / "docs" / "assets"

SCENES = [
    ("hero", 1600, 920),
    ("f1-reading", 1600, 840),
    ("f2-models", 1600, 840),
    ("f3-coding", 1600, 840),
    ("f4-ecosystem", 1600, 840),
    ("f5-copilot", 1600, 840),
    ("f6-virtual-tui", 1600, 840),
]

DEMO_WIDTH = 660
DEMO_HEIGHT = 896
DEMO_FPS = 12
DEMO_DURATION_SEC = 16.0


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, format, *args):
        pass


def start_local_server(root: Path):
    handler = functools.partial(QuietHandler, directory=str(root))
    httpd = socketserver.TCPServer(("127.0.0.1", 0), handler)
    port = httpd.server_address[1]
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    return httpd, port


def find_chrome() -> str | None:
    for candidate in ("/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser"):
        if os.path.exists(candidate):
            return candidate
    return None


def encode_webp(png_path: Path, webp_path: Path, quality: int = 90):
    subprocess.run(
        ["cwebp", "-quiet", "-q", str(quality), "-m", "6", str(png_path), "-o", str(webp_path)],
        check=True,
    )


def render_static(browser, base_url: str, langs: list[str], tmp_dir: Path, scene_filter: str | None = None):
    context = browser.new_context(viewport={"width": 1600, "height": 920}, device_scale_factor=1.5)
    page = context.new_page()
    page.on("pageerror", lambda err: print(f"[pageerror] {err}", file=sys.stderr))
    page.goto(f"{base_url}/tools/readme-assets/stage.html", wait_until="networkidle")

    for scene_name, width, height in SCENES:
        if scene_filter and scene_name != scene_filter:
            continue
        page.set_viewport_size({"width": width, "height": height})
        for lang in langs:
            for mode in ("light", "dark"):
                page.evaluate("([s, l, m]) => window.renderScene(s, l, m)", [scene_name, lang, mode])
                page.wait_for_timeout(80)
                png_path = tmp_dir / f"{scene_name}-{lang}-{mode}.png"
                webp_path = OUT_DIR / f"{scene_name}-{lang}-{mode}.webp"
                page.screenshot(path=str(png_path), type="png")
                encode_webp(png_path, webp_path, quality=90)
                size_kb = webp_path.stat().st_size / 1024.0
                print(f"  [static] {webp_path.name:<34} ({size_kb:6.1f} KB)")

    context.close()


def render_demo(browser, base_url: str, langs: list[str], tmp_dir: Path):
    total_frames = int(DEMO_DURATION_SEC * DEMO_FPS)
    for lang in langs:
        frames_dir = tmp_dir / f"frames-{lang}"
        frames_dir.mkdir(parents=True, exist_ok=True)

        context = browser.new_context(
            viewport={"width": DEMO_WIDTH, "height": DEMO_HEIGHT},
            device_scale_factor=1.0,
        )
        page = context.new_page()
        page.on("pageerror", lambda err: print(f"[demo pageerror] {err}", file=sys.stderr))
        page.goto(f"{base_url}/tools/readme-assets/demo.html", wait_until="networkidle")
        page.evaluate("(l) => window.initDemo(l)", lang)

        for frame_idx in range(total_frames):
            t = frame_idx / DEMO_FPS
            page.evaluate("(sec) => window.setDemoTime(sec)", t)
            frame_file = frames_dir / f"frame_{frame_idx:04d}.png"
            page.screenshot(path=str(frame_file), type="png")

        context.close()

        palette_path = tmp_dir / f"palette-{lang}.png"
        gif_path = OUT_DIR / f"demo-chat-{lang}.gif"
        mp4_path = OUT_DIR / f"demo-chat-{lang}.mp4"
        frame_pattern = str(frames_dir / "frame_%04d.png")

        # Two-pass palettegen/paletteuse for sharp typography and compact GIF size
        subprocess.run(
            [
                "ffmpeg", "-y", "-loglevel", "error",
                "-framerate", str(DEMO_FPS), "-i", frame_pattern,
                "-vf", "scale=540:-1:flags=lanczos,palettegen=max_colors=192:stats_mode=diff",
                str(palette_path),
            ],
            check=True,
        )
        subprocess.run(
            [
                "ffmpeg", "-y", "-loglevel", "error",
                "-framerate", str(DEMO_FPS), "-i", frame_pattern,
                "-i", str(palette_path),
                "-lavfi", "scale=540:-1:flags=lanczos[x];[x][1:v]paletteuse=dither=sierra2_4a",
                str(gif_path),
            ],
            check=True,
        )
        subprocess.run(
            [
                "ffmpeg", "-y", "-loglevel", "error",
                "-framerate", str(DEMO_FPS), "-i", frame_pattern,
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20",
                "-movflags", "+faststart",
                str(mp4_path),
            ],
            check=True,
        )
        print(
            f"  [demo]   {gif_path.name:<34} ({gif_path.stat().st_size / 1024:.1f} KB) | "
            f"{mp4_path.name} ({mp4_path.stat().st_size / 1024:.1f} KB)"
        )


def main():
    parser = argparse.ArgumentParser(description="Render Pi for Android README assets")
    parser.add_argument("--only", choices=["all", "static", "demo"], default="all")
    parser.add_argument("--lang", choices=["all", "zh", "en"], default="all")
    parser.add_argument("--scene", default=None, help="Optional single scene name (e.g. hero)")
    args = parser.parse_args()

    langs = ["zh", "en"] if args.lang == "all" else [args.lang]
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    httpd, port = start_local_server(REPO_ROOT)
    base_url = f"http://127.0.0.1:{port}"
    chrome_path = find_chrome()
    tmp_dir = Path(tempfile.mkdtemp(prefix="pi-readme-assets-"))

    try:
        with sync_playwright() as p:
            launch_kwargs = {"headless": True, "args": ["--no-sandbox", "--disable-gpu"]}
            if chrome_path:
                launch_kwargs["executable_path"] = chrome_path
            browser = p.chromium.launch(**launch_kwargs)
            if args.only in ("all", "static"):
                render_static(browser, base_url, langs, tmp_dir, args.scene)
            if args.only in ("all", "demo"):
                render_demo(browser, base_url, langs, tmp_dir)
            browser.close()
    finally:
        httpd.shutdown()
        shutil.rmtree(tmp_dir, ignore_errors=True)

    print("Done rendering README assets into docs/assets/.")


if __name__ == "__main__":
    main()
