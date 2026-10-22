#!/usr/bin/env python3
"""
Locator bar font for clients that have no locator bar (below 1.21.6).

Writes, from the 26.3 client assets:
  resource-pack-src/overlay/assets/viavisuals/font/locator.json          the font (bitmap + space providers)
  resource-pack-src/overlay/assets/viavisuals/textures/font/locator/*.png  the glyph sprites
  src/main/resources/locator-font.json                                    glyph table, spacer chars, waypoint styles

How the bar is drawn: the action bar line sits at (guiHeight - 72) with the glyph top at +7 - ascent, so a
negative ascent pushes a sprite down to the experience bar row (guiHeight - 29). Horizontal placement uses space
providers with power-of-two advances, so every x is reachable with a short run of spacer characters and GUI scale
does not matter. Sprites are 8-bit RGBA, the background is the 9-sliced 12x5 sprite stretched to the 182 px bar.
"""
import json
import shutil
from pathlib import Path

from PIL import Image

import vanilla_cache

PLUGIN_ROOT = Path(__file__).resolve().parent.parent
ASSETS = vanilla_cache.assets("26.3")
SPRITES = ASSETS / "textures" / "gui" / "sprites" / "hud"
OVERLAY = PLUGIN_ROOT / "resource-pack-src" / "overlay" / "assets" / "viavisuals"
OUT_JSON = PLUGIN_ROOT / "src" / "main" / "resources" / "locator-font.json"

LINE_TOP = 72  # action bar text top: guiHeight - 68 - 4
BAR_TOP = 29  # experience / locator bar top: guiHeight - 29
BAR_WIDTH = 182
FIRST_GLYPH = 0xE000
SPACER_BASE = 0xE100
DIGIT_BASE = 0xE030
DIGIT_UP_BASE = 0xE040  # one pixel higher
DIGIT_DOWN_BASE = 0xE050  # one pixel lower
POWERS = [1, 2, 4, 8, 16, 32, 64, 128, 256, 512]


def ascent_for(top_from_bottom):
    """Ascent that puts a glyph's top at guiHeight - top_from_bottom (glyph top = line top + 7 - ascent)."""
    return 7 - (LINE_TOP - top_from_bottom)


def nine_slice_background():
    src = Image.open(SPRITES / "locator_bar_background.png").convert("RGBA")
    left, right = 5, 5
    out = Image.new("RGBA", (BAR_WIDTH, src.height))
    mid_src = src.width - left - right
    mid_dst = BAR_WIDTH - left - right
    for y in range(src.height):
        for x in range(BAR_WIDTH):
            if x < left:
                sx = x
            elif x >= BAR_WIDTH - right:
                sx = src.width - (BAR_WIDTH - x)
            else:
                sx = left + (x - left) * mid_src // mid_dst
            out.putpixel((x, y), src.getpixel((sx, y)))
    return out


def trimmed_width(img):
    """Width the font engine counts: up to the last column with a visible pixel."""
    px = img.load()
    for x in range(img.width - 1, -1, -1):
        if any(px[x, y][3] > 0 for y in range(img.height)):
            return x + 1
    return 0


def frames(path, height):
    img = Image.open(path).convert("RGBA")
    return [img.crop((0, i * height, img.width, (i + 1) * height)) for i in range(img.height // height)]


ASCII = ASSETS / "textures" / "font" / "ascii.png"
DIGIT_OUTLINE_BASE = 0xE060  # black copies left/right of the level number
# Draw order. Clients below 1.19 stitch every glyph of a font onto shared pages and sort the quads of a page far to
# near (distance from the screen's top-left corner), ignoring the string order. A glyph that must be drawn first
# gets a transparent tail below its sprite: the quad is taller, its centre lower, so it counts as farther.
BAR_TAIL = 600
OUTLINE_TAIL = 200


def column(images, tail=0):
    """Stack equally wide sprites vertically, each cell {height + tail} rows high, sprite at the top."""
    width = images[0].width
    height = images[0].height + tail
    out = Image.new("RGBA", (width, height * len(images)), (255, 255, 255, 0))
    for row, img in enumerate(images):
        out.paste(img, (0, row * height))
    return out, height


def main():
    tex_dir = OVERLAY / "textures" / "font" / "locator"
    if tex_dir.exists():
        shutil.rmtree(tex_dir)
    tex_dir.mkdir(parents=True)
    (OVERLAY / "font").mkdir(parents=True, exist_ok=True)

    glyphs = {}
    providers = []
    code = FIRST_GLYPH

    def provide(name, images, top_from_bottom, chars, tail=0):
        atlas, cell_h = column(images, tail)
        atlas.save(tex_dir / f"{name}.png")
        providers.append({"type": "bitmap", "file": f"viavisuals:font/locator/{name}.png",
                          "ascent": ascent_for(top_from_bottom), "height": cell_h, "chars": chars})

    def glyph(name, char, img):
        glyphs[name] = {"char": char, "width": img.width, "height": img.height, "advance": trimmed_width(img) + 1}

    bg = nine_slice_background()
    provide("background", [bg], BAR_TOP, [chr(code)], BAR_TAIL)
    glyph("background", chr(code), bg)
    code += 1
    dots = []
    chars = []
    for dot in ["bowtie", "default_0", "default_1", "default_2", "default_3"]:
        img = Image.open(SPRITES / "locator_bar_dot" / f"{dot}.png").convert("RGBA")
        glyph(f"dot_{dot}", chr(code), img)
        dots.append(img)
        chars.append(chr(code))
        code += 1
    provide("dots", dots, BAR_TOP + 2, chars)
    meta = json.loads((SPRITES / "locator_bar_arrow_up.png.mcmeta").read_text())["animation"]
    arrow_h = meta["height"]
    anim = [{"index": f["index"], "time": f["time"]} for f in meta["frames"]]
    for name, off in (("arrow_up", BAR_TOP + 6), ("arrow_down", BAR_TOP - 6)):
        imgs = frames(SPRITES / f"locator_bar_{name}.png", arrow_h)
        chars = []
        for i, frame in enumerate(imgs):
            glyph(f"{name}_{i}", chr(code), frame)
            chars.append(chr(code))
            code += 1
        provide(name, imgs, off, chars)

    # experience level digits: the vanilla ascii.png cells of 0-9, at the row of the level number (guiHeight - 35);
    # the outline of the number is the same digits in black, left/right and one pixel up/down, drawn before the number
    ascii_img = Image.open(ASCII).convert("RGBA")
    digit_imgs = [ascii_img.crop((d * 8, 3 * 8, d * 8 + 8, 3 * 8 + 8)) for d in range(10)]
    for name, base, top, tail in (("digits", DIGIT_BASE, 35, 0), ("digits_side", DIGIT_OUTLINE_BASE, 35, OUTLINE_TAIL),
                                  ("digits_up", DIGIT_UP_BASE, 36, OUTLINE_TAIL), ("digits_down", DIGIT_DOWN_BASE, 34, OUTLINE_TAIL)):
        provide(name, digit_imgs, top, [chr(base + d) for d in range(10)], tail)

    # spacers: one character per power of two in both directions. The space provider only exists from 1.19, so
    # they are bitmap glyphs: the advance is (int)(0.5 + width * scale) + 1 with scale = height / cell height, so a
    # transparent 1 px row whose last column holds an alpha-1 pixel advances width + 1, and a negative height
    # (scale -1) makes the advance negative (width k + 2 gives -k).
    spacers = {}
    for i, power in enumerate(POWERS):
        for sign, code in ((1, SPACER_BASE + i), (-1, SPACER_BASE + 16 + i)):
            char = chr(code)
            width = max(1, power - 1) if sign > 0 else power + 2
            img = Image.new("RGBA", (width, 1), (255, 255, 255, 0))
            if sign < 0 or power >= 2:
                img.putpixel((width - 1, 0), (255, 255, 255, 1))
            name = f"space_{'p' if sign > 0 else 'n'}{power}"
            img.save(tex_dir / f"{name}.png")
            providers.append({"type": "bitmap", "file": f"viavisuals:font/locator/{name}.png",
                              "ascent": 0 if sign > 0 else -1, "height": 1 if sign > 0 else -1, "chars": [char]})
            spacers[str(sign * power)] = char

    (OVERLAY / "font" / "locator.json").write_text(json.dumps({"providers": providers}, indent=1), encoding="utf-8")

    styles = {}
    for style in (ASSETS / "waypoint_style").glob("*.json"):
        data = json.loads(style.read_text())
        styles[style.stem] = {"near": data.get("near_distance", 128), "far": data.get("far_distance", 332),
                              "sprites": [s.split(":")[-1] for s in data["sprites"]]}
    table = {
        "glyphs": glyphs,
        "digits": {"base": DIGIT_BASE, "side": DIGIT_OUTLINE_BASE, "up": DIGIT_UP_BASE, "down": DIGIT_DOWN_BASE, "advance": 6},
        "spacers": spacers,
        "styles": styles,
        "arrowAnimation": anim,
        "layout": {"barWidth": BAR_WIDTH, "dotSize": 9, "arrowWidth": 7},
    }
    OUT_JSON.write_text(json.dumps(table, indent=1, ensure_ascii=True), encoding="utf-8")
    print(f"wrote {len(glyphs)} glyphs, {len(styles)} styles")


if __name__ == "__main__":
    main()
