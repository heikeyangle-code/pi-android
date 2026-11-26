#!/usr/bin/env python3
"""Build GB2312 + everyday-hanzi + Latin subsets for Pi Android's CJK+Latin app fonts.

Generates:
  1. IBM Plex Sans SC (OFL-1.1, 4 real static weights: Regular 400 / Medium 500 / SemiBold 600 / Bold 700)
     -> app/src/main/res/font/plex_sans_sc_{regular,medium,semibold,bold}.ttf
  2. LXGW WenKai (OFL-1.1, 2 real static weights: Regular 400 / Medium 500)
     -> app/src/main/res/font/lxgw_wenkai_{regular,medium}.ttf

OFL §3 compliance:
  Subsetting produces Modified Versions, so Reserved Font Names ("Plex", "LXGW", "霞鹜", "霞鶩",
  "落霞孤鹜", "落霞孤鶩") are stripped from the `name` table (family / full / PostScript / unique ID)
  while preserving copyright (0), trademark (7), license (13), and license URL (14) verbatim.
"""
import argparse
import os
import sys

from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
DEFAULT_OUT = os.path.join(ROOT, "app", "src", "main", "res", "font")

# Everyday hanzi missing from GB2312 (common internet / UI / given-name characters).
EXTRA_HANZI = "啰怼囧嘚嫑粿煋妳祂祐翀垚堃琇珺珅珣琤瑄璟玥玙彧旻暕"
CARET = 0x258D  # ▍ streaming caret

SPECS = [
    # (input filename, output filename, wght, family, postscript, version, reserved_tokens)
    ("IBMPlexSansSC-Regular.ttf", "plex_sans_sc_regular.ttf", 400, "Pi Studio Sans", "PiStudioSans-Regular", "1.1.0-subset", ("plex",)),
    ("IBMPlexSansSC-Medium.ttf", "plex_sans_sc_medium.ttf", 500, "Pi Studio Sans Medium", "PiStudioSans-Medium", "1.1.0-subset", ("plex",)),
    ("IBMPlexSansSC-SemiBold.ttf", "plex_sans_sc_semibold.ttf", 600, "Pi Studio Sans SemiBold", "PiStudioSans-SemiBold", "1.1.0-subset", ("plex",)),
    ("IBMPlexSansSC-Bold.ttf", "plex_sans_sc_bold.ttf", 700, "Pi Studio Sans Bold", "PiStudioSans-Bold", "1.1.0-subset", ("plex",)),
    ("LXGWWenKai-Regular.ttf", "lxgw_wenkai_regular.ttf", 400, "Pi Studio Kai", "PiStudioKai-Regular", "1.530-subset", ("lxgw", "霞鹜", "霞鶩", "落霞孤鹜", "落霞孤鶩")),
    ("LXGWWenKai-Medium.ttf", "lxgw_wenkai_medium.ttf", 500, "Pi Studio Kai Medium", "PiStudioKai-Medium", "1.530-subset", ("lxgw", "霞鹜", "霞鶩", "落霞孤鹜", "落霞孤鶩")),
]


def build_charset(cmap):
    chars = set()
    for hi in range(0xA1, 0xF8):
        for lo in range(0xA1, 0xFF):
            try:
                chars.add(bytes([hi, lo]).decode("gb2312"))
            except UnicodeDecodeError:
                pass
    chars.update(c for c in dict.fromkeys(EXTRA_HANZI) if ord(c) in cmap)
    for cp in list(range(0x20, 0x7F)) + list(range(0xA0, 0x100)):
        chars.add(chr(cp))
    for cp in list(range(0x2010, 0x2028)) + list(range(0x2030, 0x2040)) + [0x20AC, 0x2116, 0x2122]:
        chars.add(chr(cp))
    for cp in (
        list(range(0x2190, 0x2194))
        + [0x21D2, 0x21D4, 0x2200, 0x2203, 0x2208, 0x2211, 0x221A, 0x221E, 0x2248, 0x2260, 0x2264, 0x2265]
        + [0x2713, 0x2717, 0x25CF, 0x25CB, 0x25A0, 0x25A1, 0x2605, 0x2606, 0x258D, 0x25B8, 0x25BE, 0x00D7, 0x00B7]
    ):
        chars.add(chr(cp))
    chars.update(chr(cp) for cp in range(0x3000, 0x3040))
    chars.update(chr(cp) for cp in range(0xFF01, 0xFF5F))
    return {c for c in chars if ord(c) in cmap}


def rename(font, family, postscript, version):
    name = font["name"]
    for rec in list(name.names):
        if rec.nameID in (16, 17, 21, 22, 25):
            name.removeNames(nameID=rec.nameID)
    for platform, enc, lang in ((3, 1, 0x409), (1, 0, 0)):
        name.setName(family, 1, platform, enc, lang)
        name.setName("Regular", 2, platform, enc, lang)
        name.setName(f"{postscript};{version}", 3, platform, enc, lang)
        name.setName(family, 4, platform, enc, lang)
        name.setName(f"Version {version}", 5, platform, enc, lang)
        name.setName(postscript, 6, platform, enc, lang)


def assert_no_reserved_names(font, label, reserved_tokens):
    for rec in font["name"].names:
        if rec.nameID in (0, 7, 13, 14):
            continue
        text = rec.toUnicode()
        lower = text.lower()
        for tok in reserved_tokens:
            if tok in lower or tok in text:
                raise SystemExit(f"{label}: Reserved Font Name {tok!r} survived in name id {rec.nameID}: {text!r}")


def build_all(src_dir, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    for src_name, dst_name, wght, family, postscript, version, reserved in SPECS:
        src_path = os.path.join(src_dir, src_name)
        if not os.path.isfile(src_path):
            sys.exit(f"missing source font: {src_path}")
        font = TTFont(src_path)
        cmap = font.getBestCmap()
        chars = build_charset(cmap)
        opts = subset.Options()
        opts.layout_features = ["kern", "locl", "ccmp", "mark", "mkmk", "palt", "halt", "tnum", "zero"]
        opts.hinting = False
        opts.notdef_outline = True
        opts.glyph_names = False
        opts.name_IDs = [0, 1, 2, 3, 4, 5, 6, 7, 13, 14]
        opts.drop_tables += ["DSIG", "BASE", "STAT", "vhea", "vmtx", "VORG"]
        sub = subset.Subsetter(opts)
        sub.populate(text="".join(chars))
        sub.subset(font)
        if CARET in font.getBestCmap():
            caret_glyph = font.getBestCmap()[CARET]
            advance, lsb = font["hmtx"][caret_glyph]
            font["hmtx"][caret_glyph] = (int(font["head"].unitsPerEm * 0.4), lsb)
        font["OS/2"].usWeightClass = wght
        rename(font, family, postscript, version)
        assert_no_reserved_names(font, dst_name, reserved)
        dst_path = os.path.join(out_dir, dst_name)
        font.save(dst_path)
        print(f"{dst_name:28s} wght={wght} glyphs={len(font.getGlyphOrder())} size={os.path.getsize(dst_path) // 1024} KB")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--src", required=True, help="Directory with IBMPlexSansSC-*.ttf and LXGWWenKai-*.ttf")
    parser.add_argument("--out", default=DEFAULT_OUT, help="Output res/font directory")
    args = parser.parse_args()
    build_all(args.src, args.out)
