#!/usr/bin/env python3
"""
Emission companions for the light-emitting block textures, for shaderpacks (Iris / OptiFine):

  <name>_e.png   OptiFine emissive texture (the glowing pixels on black), enabled by optifine/emissive.properties
  <name>_s.png   LabPBR specular map: no smoothness/reflectance, alpha 255 = no emission, 0..254 = emission strength

Vanilla only gives these to a few blocks, so the pack marks the pixels of the backported light sources itself:
froglights (the whole block), the copper torch and the copper lanterns (their bright flame and glass pixels).
Written into resource-pack-src/overlay, next to the font; vanilla clients ignore the extra files.
"""
from pathlib import Path

from PIL import Image

import vanilla_cache

PLUGIN_ROOT = Path(__file__).resolve().parent.parent
ASSETS = vanilla_cache.assets("26.3") / "textures" / "block"
OVERLAY = PLUGIN_ROOT / "resource-pack-src" / "overlay" / "assets"
OUT = OVERLAY / "viavisuals" / "textures" / "block"

# texture -> brightness (max channel) from which a pixel emits; 0 = every visible pixel
TEXTURES = {}
for frog in ("ochre", "verdant", "pearlescent"):
    TEXTURES[f"{frog}_froglight_top"] = 0
    TEXTURES[f"{frog}_froglight_side"] = 0
for lantern in ("copper_lantern", "exposed_copper_lantern", "weathered_copper_lantern", "oxidized_copper_lantern"):
    TEXTURES[lantern] = 200
TEXTURES["copper_torch"] = 200


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    count = 0
    for name, threshold in TEXTURES.items():
        src = ASSETS / f"{name}.png"
        if not src.exists():
            print("missing", name)
            continue
        img = Image.open(src).convert("RGBA")
        emit = Image.new("RGBA", img.size, (0, 0, 0, 0))
        spec = Image.new("RGBA", img.size, (0, 0, 0, 255))
        px, e_px, s_px = img.load(), emit.load(), spec.load()
        for y in range(img.height):
            for x in range(img.width):
                r, g, b, a = px[x, y]
                if a > 0 and max(r, g, b) >= threshold:
                    e_px[x, y] = (r, g, b, a)
                    s_px[x, y] = (0, 0, 0, 254)
        emit.save(OUT / f"{name}_e.png")
        spec.save(OUT / f"{name}_s.png")
        count += 1
    props = OVERLAY / "minecraft" / "optifine"
    props.mkdir(parents=True, exist_ok=True)
    (props / "emissive.properties").write_text("suffix.emissive=_e\n", encoding="utf-8")
    print(f"wrote emission textures for {count} block textures")


if __name__ == "__main__":
    main()
