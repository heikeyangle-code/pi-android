#!/usr/bin/env python3
"""Cut the bundled reading face (`PiProseFamily`) from Adobe's Source Han Serif CN VF.

Why this exists
---------------
The app's Markdown body is set in Source Han Serif CN (OFL-1.1). Adobe's official
Simplified-Chinese subset variable font is 25 MB and 31 k glyphs; shipping it as-is
costs ~15 MB deflated in the APK, and a font stored compressed cannot be mmapped by
the platform. A chat transcript needs the 6 763 GB2312 hanzi, ASCII/Latin-1,
punctuation and the full-width forms — about 7.7 k glyphs — so this script produces
two **static** instances of the variable font, subset to that set:

    source_han_serif_cn_text.ttf       wght 450  (body)
    source_han_serif_cn_bold.ttf       wght 700  (headings, **strong**)

~3.0 MB each, no hinting, no vertical forms. A character outside the subset is not
tofu: Compose falls back to the system face for that glyph only (measured on this
repo's own 540 k hanzi of Chinese text: one character outside GB2312).

Why 450 and not 400
-------------------
Light-on-dark text with a modulated serif loses its thin strokes at 16 sp on an OLED;
400 reads spindly there. 450 keeps the serif's contrast and the page's density close to
the 500 the system-face body had before.

The Reserved Font Name
----------------------
Adobe's licence reserves the name **'Source'** (see `assets/licenses/SourceHanSerif-OFL-1.1.txt`).
OFL §3: a *Modified Version* may not use a Reserved Font Name. Instancing and subsetting
make these files Modified Versions, so the `name` table is rewritten (family, full name,
PostScript name, unique id; typographic names dropped) — the copyright notice (name id 0),
the licence (13) and its URL (14) are kept verbatim, and `assert_no_reserved_name` fails the
build if "Source" survives anywhere but the copyright / trademark notices.

Usage
-----
    # Variable/TTF/Subset/SourceHanSerifCN-VF.ttf from Adobe's 02_SourceHanSerif-VF.zip
    # (https://github.com/adobe-fonts/source-han-serif, release 2.003R)
    python3 tools/build-prose-font.py --source /path/to/SourceHanSerifCN-VF.ttf

Needs `pip install fonttools`.
"""
import argparse
import os
import sys

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
DEFAULT_OUT = os.path.join(ROOT, "app", "src", "main", "res", "font")

# (output file, wght, family name, PostScript name)
CUTS = [
    ("source_han_serif_cn_text.ttf", 450, "Pi Reading Serif Text", "PiReadingSerif-Text"),
    ("source_han_serif_cn_bold.ttf", 700, "Pi Reading Serif Bold", "PiReadingSerif-Bold"),
]

# Everyday hanzi that GB2312 lacks (internet / dialect / given-name usage). Filtered at
# build time by "not in GB2312" and "present in the source font".
EXTRA_HANZI = "啰怼囧嘚嫑粿煋妳祂祐翀垚堃琇珺珅珣琤瑄璟玥玙彧旻暕"

VERSION = "2.003R-subset"
CARET = 0x258D  # ▍, the streaming caret (see build())


def gb2312(ch):
    try:
        ch.encode("gb2312")
        return True
    except UnicodeEncodeError:
        return False


def charset(cmap):
    chars = set()
    for hi in range(0xA1, 0xF8):  # GB2312: symbol rows + 6763 hanzi
        for lo in range(0xA1, 0xFF):
            try:
                chars.add(bytes([hi, lo]).decode("gb2312"))
            except UnicodeDecodeError:
                pass
    chars.update(c for c in dict.fromkeys(EXTRA_HANZI) if not gb2312(c) and ord(c) in cmap)
    for cp in list(range(0x20, 0x7F)) + list(range(0xA0, 0x100)):
        chars.add(chr(cp))  # ASCII, Latin-1
    for cp in list(range(0x2010, 0x2028)) + list(range(0x2030, 0x2040)) + [0x20AC, 0x2116, 0x2122]:
        chars.add(chr(cp))  # general punctuation
    for cp in (
        list(range(0x2190, 0x2194))
        + [0x21D2, 0x21D4, 0x2200, 0x2203, 0x2208, 0x2211, 0x221A, 0x221E, 0x2248, 0x2260, 0x2264, 0x2265]
        + [0x2713, 0x2717, 0x25CF, 0x25CB, 0x25A0, 0x25A1, 0x2605, 0x2606, 0x258D]  # 0x258D ▍ = the streaming caret
    ):
        chars.add(chr(cp))  # arrows, maths, check marks, geometric shapes
    chars.update(chr(cp) for cp in range(0x3000, 0x3040))  # CJK symbols and punctuation
    chars.update(chr(cp) for cp in range(0xFF01, 0xFF5F))  # full-width forms
    return {c for c in chars if ord(c) in cmap}


def rename(font, family, postscript):
    name = font["name"]
    for rec in list(name.names):
        if rec.nameID in (16, 17, 21, 22, 25):  # typographic / WWS / variations prefix
            name.removeNames(nameID=rec.nameID)
    for platform, enc, lang in ((3, 1, 0x409), (1, 0, 0)):
        name.setName(family, 1, platform, enc, lang)
        name.setName("Regular", 2, platform, enc, lang)
        name.setName(f"{postscript};{VERSION}", 3, platform, enc, lang)
        name.setName(family, 4, platform, enc, lang)
        name.setName(f"Version {VERSION}", 5, platform, enc, lang)
        name.setName(postscript, 6, platform, enc, lang)
    # keep: 0 copyright, 7 trademark notice, 13 licence, 14 licence URL (verbatim)


def assert_no_reserved_name(font, label):
    for rec in font["name"].names:
        if rec.nameID in (0, 7, 13, 14):
            continue  # the notices OFL requires us to carry
        if "source" in rec.toUnicode().lower():
            raise SystemExit(f"{label}: Reserved Font Name 'Source' left in name id {rec.nameID}: {rec.toUnicode()!r}")


def build(source, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    cmap = TTFont(source).getBestCmap()
    chars = charset(cmap)
    print(f"charset: {len(chars)} code points")
    for filename, wght, family, postscript in CUTS:
        font = instancer.instantiateVariableFont(TTFont(source), {"wght": wght}, inplace=False, updateFontNames=False)
        opts = subset.Options()
        opts.layout_features = ["kern", "locl", "ccmp", "mark", "mkmk", "palt", "halt"]
        opts.hinting = False
        opts.notdef_outline = True
        opts.glyph_names = False
        opts.name_IDs = [0, 1, 2, 3, 4, 5, 6, 7, 13, 14]
        opts.drop_tables += ["DSIG", "BASE", "STAT", "vhea", "vmtx", "VORG"]
        subsetter = subset.Subsetter(opts)
        subsetter.populate(text="".join(chars))
        subsetter.subset(font)
        # The streaming caret ▍ (U+258D). In a CJK face it is a full-width (1 em) block glyph whose
        # ink is only the left 3/8; set at the end of a line it would claim a whole em and wrap
        # onto the next line whenever less than 16 sp was left. Shrink the *advance* to 0.4 em and
        # leave the outline alone: the ink still fits (0.375 em) and the caret takes a caret's room.
        caret = font.getBestCmap()[CARET]
        advance, lsb = font["hmtx"][caret]
        font["hmtx"][caret] = (int(font["head"].unitsPerEm * 0.4), lsb)
        font["OS/2"].usWeightClass = wght
        rename(font, family, postscript)
        assert_no_reserved_name(font, filename)
        path = os.path.join(out_dir, filename)
        font.save(path)
        print(f"{filename}: wght {wght}, {len(font.getGlyphOrder())} glyphs, {os.path.getsize(path) // 1024} KB")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", required=True, help="SourceHanSerifCN-VF.ttf (Adobe, 2.003R)")
    parser.add_argument("--out", default=DEFAULT_OUT, help="output directory (default: app/src/main/res/font)")
    args = parser.parse_args()
    if not os.path.isfile(args.source):
        sys.exit(f"missing source font: {args.source}")
    build(args.source, args.out)
