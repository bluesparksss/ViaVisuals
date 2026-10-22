#!/usr/bin/env python3
"""
Armor trims (and copper armor) on clients 1.17 - 1.21.1 through dyed leather armor and one core shader.

A trimmed armor piece is sent as the leather piece of its slot whose dye colour is a key. The leather armor textures
(leather_layer_1/2) are enlarged 16x (pixel-identical to vanilla without the shader) and carry data in the strip no
armor face samples (x 56..64, y 16..32 of the vanilla 64x32 layout), as tiles of 64x32 texels:

  tile 0-6   base material textures (iron, gold, diamond, netherite, chainmail, turtle, copper)
  tile 8-13  trim patterns, three per tile (one in each of R, G, B), value = (palette index + 1) * 28, 0 = none
  tile 14    trim palettes: row = palette (material or its darker variant), column = pattern gray level
  tile 15    the leather overlay, which the shader draws itself (see below)

The shader (rendertype_armor_cutout_no_cull, one self-contained version for 1.17 - 1.21.1) reads the raw dye colour
of the vertex: the high nibbles of R, G, B are a marker triple per base material, the low nibbles carry the palette
row, the pattern and a check. A keyed piece is composed from the tiles; every other piece renders as vanilla. The
leather overlay pass is drawn from a 128x64 copy of the overlay texture, which the shader recognises by its size and
discards, drawing the overlay itself in the base pass of plain leather (vanilla draws it as a second pass at the same
depth, which would show through a keyed piece). Without the shader (1.16, shaderpacks) nothing changes.

Writes resource-pack-src/overlay/assets/minecraft/{textures/models/armor,shaders/core} and
src/main/resources/armor-trims.json (indices and key layout for the runtime).
"""
import json
from pathlib import Path

from PIL import Image

import vanilla_cache

PLUGIN_ROOT = Path(__file__).resolve().parent.parent
OVERLAY = PLUGIN_ROOT / "resource-pack-src" / "overlay" / "assets" / "minecraft"
OUT_JSON = PLUGIN_ROOT / "src" / "main" / "resources" / "armor-trims.json"

SCALE = 16
TILE_W, TILE_H = 64, 32
REGION = (56 * SCALE, 16 * SCALE)  # canvas px of the unused strip

BASES = ["iron", "gold", "diamond", "netherite", "chainmail", "turtle", "copper"]
# marker triples (high nibbles); chosen away from the sixteen dye colours and the default leather brown
MARKERS = [(0xC, 0xC, 0xC), (0xF, 0xB, 0x2), (0x2, 0xC, 0xD), (0x3, 0x2, 0x3), (0x6, 0x6, 0x7), (0x4, 0xC, 0x3),
           (0xD, 0x6, 0x4)]
PATTERNS = ["coast", "dune", "eye", "host", "raiser", "rib", "sentry", "shaper", "silence", "snout", "spire", "tide",
            "vex", "ward", "wayfinder", "wild", "bolt", "flow"]
NO_PATTERN = 31
# palette rows: the materials, then the darker variants used when the trim matches the armor's material
PALETTES = ["amethyst", "copper", "diamond", "emerald", "gold", "iron", "lapis", "netherite", "quartz", "redstone",
            "resin", "diamond_darker", "gold_darker", "iron_darker", "netherite_darker"]
LEVEL_STEP = 28


def tile_origin(index):
    return REGION[0] + (index % 2) * TILE_W, REGION[1] + (index // 2) * TILE_H


def armor_texture(base, layer):
    if base == "copper":
        sub = "humanoid" if layer == 1 else "humanoid_leggings"
        return vanilla_cache.assets("26.3") / "textures" / "entity" / "equipment" / sub / "copper.png"
    return vanilla_cache.assets("1.20.4") / "textures" / "models" / "armor" / f"{base}_layer_{layer}.png"


def pattern_texture(name, layer):
    suffix = "" if layer == 1 else "_leggings"
    return vanilla_cache.assets("1.21.1") / "textures" / "trims" / "models" / "armor" / f"{name}{suffix}.png"


def palette_colors(name):
    root = vanilla_cache.assets("1.21.4") / "textures" / "trims" / "color_palettes"
    img = Image.open(root / f"{name}.png").convert("RGBA")
    return [img.getpixel((x, 0))[:3] for x in range(img.width)]


def key_levels():
    root = vanilla_cache.assets("1.21.4") / "textures" / "trims" / "color_palettes"
    img = Image.open(root / "trim_palette.png").convert("RGBA")
    return [img.getpixel((x, 0))[:3] for x in range(img.width)]


def level_of(pixel, key):
    r, g, b, a = pixel
    if a < 128:
        return 0
    best = min(range(len(key)), key=lambda i: sum((c1 - c2) ** 2 for c1, c2 in zip((r, g, b), key[i])))
    return best + 1


def build_layer(layer):
    leather = Image.open(vanilla_cache.assets("1.16.5") / "textures" / "models" / "armor" / f"leather_layer_{layer}.png").convert("RGBA")
    canvas = leather.resize((leather.width * SCALE, leather.height * SCALE), Image.NEAREST)
    for i, base in enumerate(BASES):
        src = armor_texture(base, layer)
        if not src.exists():
            continue
        canvas.paste(Image.open(src).convert("RGBA").crop((0, 0, TILE_W, TILE_H)), tile_origin(i))
    key = key_levels()
    groups = [Image.new("RGBA", (TILE_W, TILE_H), (0, 0, 0, 255)) for _ in range(6)]
    for p, name in enumerate(PATTERNS):
        img = Image.open(pattern_texture(name, layer)).convert("RGBA")
        group, chan = divmod(p, 3)
        gpx = groups[group].load()
        for y in range(TILE_H):
            for x in range(TILE_W):
                value = level_of(img.getpixel((x, y)), key) * LEVEL_STEP
                px = list(gpx[x, y])
                px[chan] = value
                gpx[x, y] = tuple(px)
    for g, tile in enumerate(groups):
        canvas.paste(tile, tile_origin(8 + g))
    palette = Image.new("RGBA", (TILE_W, TILE_H), (0, 0, 0, 255))
    for row, name in enumerate(PALETTES):
        for col, rgb in enumerate(palette_colors(name)):
            palette.putpixel((col, row), rgb + (255,))
    canvas.paste(palette, tile_origin(14))
    overlay = Image.open(vanilla_cache.assets("1.16.5") / "textures" / "models" / "armor" / f"leather_layer_{layer}_overlay.png").convert("RGBA")
    canvas.paste(overlay, tile_origin(15))
    return canvas, overlay


VSH = """#version 150

#moj_import <light.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightColor;
flat out vec4 dye;
out vec2 texCoord0;
out vec2 texCoord1;
out vec4 normal;

// ViaVisuals: the raw dye colour reaches the fragment shader (armor trim keys), lighting is kept apart from it.
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexDistance = length((ModelViewMat * vec4(Position, 1.0)).xyz);
    vec4 light = texelFetch(Sampler2, UV2 / 16, 0);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color) * light;
    lightColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, vec4(1.0)) * light;
    dye = Color;
    texCoord0 = UV0;
    texCoord1 = UV1;
    normal = ProjMat * ModelViewMat * vec4(Normal, 0.0);
}
"""

FSH = """#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightColor;
flat in vec4 dye;
in vec2 texCoord0;
in vec2 texCoord1;
in vec4 normal;

out vec4 fragColor;

// ViaVisuals armor trims: see scripts/generate_armor_trims.py for the layout of the leather armor textures.
const int BASES = @BASES@;
const ivec3 MARKERS[@BASES@] = ivec3[@BASES@](@MARKERS@);
const ivec2 REGION = ivec2(@RX@, @RY@);
const int SCALE = @SCALE@;
const int NO_PATTERN = @NOPATTERN@;
const int LEVEL_STEP = @STEP@;

ivec2 tile(int index) {
    return REGION + ivec2((index % 2) * 64, (index / 2) * 32);
}

void main() {
    ivec2 size = textureSize(Sampler0, 0);
    if (size == ivec2(128, 64)) {
        // the leather overlay pass: plain leather draws its overlay in the base pass
        discard;
    }
    vec4 color;
    if (size == ivec2(64 * SCALE, 32 * SCALE)) {
        ivec3 c = ivec3(floor(dye.rgb * 255.0 + 0.5));
        ivec3 hi = c >> 4;
        ivec3 lo = c & 15;
        int base = -1;
        for (int i = 0; i < BASES; i++) {
            if (hi == MARKERS[i]) {
                base = i;
            }
        }
        int row = lo.r;
        int pattern = lo.g + ((lo.b & 1) << 4);
        int check = lo.b >> 1;
        bool keyed = base >= 0 && row <= 14 && (pattern < 18 || pattern == NO_PATTERN)
            && check == ((row + 3 * pattern + 5 * base) & 7);
        ivec2 px = ivec2(clamp(floor(texCoord0 * vec2(64.0, 32.0)), vec2(0.0), vec2(63.0, 31.0)));
        if (keyed) {
            vec4 tex = texelFetch(Sampler0, tile(base) + px, 0);
            if (pattern != NO_PATTERN) {
                float v = texelFetch(Sampler0, tile(8 + pattern / 3) + px, 0)[pattern % 3];
                int level = int(floor(v * 255.0 / float(LEVEL_STEP) + 0.5));
                if (level > 0) {
                    tex = vec4(texelFetch(Sampler0, tile(14) + ivec2(level - 1, row), 0).rgb, 1.0);
                }
            }
            color = tex * lightColor * ColorModulator;
        } else {
            color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
            vec4 overlay = texelFetch(Sampler0, tile(15) + px, 0);
            if (overlay.a > 0.5 && color.a > 0.1) {
                color = vec4(overlay.rgb * lightColor.rgb, color.a) * ColorModulator;
            }
        }
    } else {
        color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    }
    if (color.a < 0.1) {
        discard;
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
"""

JSON = """{
    "vertex": "rendertype_armor_cutout_no_cull",
    "fragment": "rendertype_armor_cutout_no_cull",
    "attributes": [
        "Position",
        "Color",
        "UV0",
        "UV1",
        "UV2",
        "Normal"
    ],
    "samplers": [
        { "name": "Sampler0" },
        { "name": "Sampler2" }
    ],
    "uniforms": [
        { "name": "ModelViewMat", "type": "matrix4x4", "count": 16, "values": [ 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0 ] },
        { "name": "ProjMat", "type": "matrix4x4", "count": 16, "values": [ 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0 ] },
        { "name": "ColorModulator", "type": "float", "count": 4, "values": [ 1.0, 1.0, 1.0, 1.0 ] },
        { "name": "Light0_Direction", "type": "float", "count": 3, "values": [0.0, 0.0, 0.0] },
        { "name": "Light1_Direction", "type": "float", "count": 3, "values": [0.0, 0.0, 0.0] },
        { "name": "FogStart", "type": "float", "count": 1, "values": [ 0.0 ] },
        { "name": "FogEnd", "type": "float", "count": 1, "values": [ 1.0 ] },
        { "name": "FogColor", "type": "float", "count": 4, "values": [ 0.0, 0.0, 0.0, 0.0 ] }
    ]
}
"""


SLOTS = ["helmet", "chestplate", "leggings", "boots"]
ICON_CMD_BASE = 950000
ICON_ITEM_NAMES = {"iron": "iron", "gold": "golden", "diamond": "diamond", "netherite": "netherite",
                   "chainmail": "chainmail", "turtle": "turtle", "copper": "copper"}


def icon_cmd(slot, base, row):
    return ICON_CMD_BASE + (SLOTS.index(slot) * len(BASES) + BASES.index(base)) * 16 + row


def build_icons():
    """Item models for trimmed armor on its leather proxy: the base piece with the material-coloured trim sprite."""
    textures = OVERLAY.parent / "viavisuals" / "textures" / "item" / "trim"
    models = OVERLAY.parent / "viavisuals" / "models" / "item" / "trim"
    textures.mkdir(parents=True, exist_ok=True)
    models.mkdir(parents=True, exist_ok=True)
    key = key_levels()
    items_dir = vanilla_cache.assets("1.21.4") / "textures" / "trims" / "items"
    for slot in SLOTS:
        gray = Image.open(items_dir / f"{slot}_trim.png").convert("RGBA")
        for row, name in enumerate(PALETTES):
            colors = palette_colors(name)
            out = Image.new("RGBA", gray.size, (0, 0, 0, 0))
            for y in range(gray.height):
                for x in range(gray.width):
                    r, g, b, a = gray.getpixel((x, y))
                    if a:
                        level = min(range(len(key)), key=lambda i: sum((c1 - c2) ** 2 for c1, c2 in zip((r, g, b), key[i])))
                        out.putpixel((x, y), colors[level] + (255,))
            out.save(textures / f"{slot}_{name}.png")
    for base in BASES:
        if base == "copper":
            for slot in SLOTS:
                Image.open(vanilla_cache.assets("26.3") / "textures" / "item" / f"copper_{slot}.png").save(
                    textures / f"base_copper_{slot}.png")
    entries = []
    for slot in SLOTS:
        for base in BASES:
            if base == "turtle" and slot != "helmet":
                continue
            layer0 = (f"viavisuals:item/trim/base_copper_{slot}" if base == "copper"
                      else f"minecraft:item/{ICON_ITEM_NAMES[base]}_{slot}")
            for row, name in enumerate(PALETTES):
                model = f"trim/{slot}_{base}_{name}"
                (models / f"{slot}_{base}_{name}.json").write_text(json.dumps({
                    "parent": "minecraft:item/generated",
                    "textures": {"layer0": layer0, "layer1": f"viavisuals:item/trim/{slot}_{name}"}}, indent=1), encoding="utf-8")
                entries.append({"proxy": f"LEATHER_{slot.upper()}", "cmd": icon_cmd(slot, base, row),
                                "model": f"viavisuals:item/{model}"})
    (PLUGIN_ROOT / "src" / "main" / "resources" / "armor-trim-icons.json").write_text(
        json.dumps({"icons": entries}, indent=1), encoding="utf-8")
    return len(entries)


def key_color(base, row, pattern):
    """The dye colour (RGB int) that selects this look: markers in the high nibbles, row/pattern/check in the low."""
    b = BASES.index(base)
    check = (row + 3 * pattern + 5 * b) & 7
    lo = (row, pattern & 15, ((pattern >> 4) & 1) | (check << 1))
    hi = MARKERS[b]
    return ((hi[0] << 4 | lo[0]) << 16) | ((hi[1] << 4 | lo[1]) << 8) | (hi[2] << 4 | lo[2])


def build_copper_equipment():
    """Copper armor for clients 1.21.2 - 1.21.8: equipment assets viavisuals:copper (models/equipment before 1.21.4)."""
    ns = OVERLAY.parent / "viavisuals"
    layers = {"layers": {"humanoid": [{"texture": "viavisuals:copper"}], "humanoid_leggings": [{"texture": "viavisuals:copper"}]}}
    for folder in ("equipment", "models/equipment"):
        (ns / folder).mkdir(parents=True, exist_ok=True)
        (ns / folder / "copper.json").write_text(json.dumps(layers, indent=2), encoding="utf-8")
    for sub in ("humanoid", "humanoid_leggings"):
        dst = ns / "textures" / "entity" / "equipment" / sub
        dst.mkdir(parents=True, exist_ok=True)
        Image.open(vanilla_cache.assets("26.3") / "textures" / "entity" / "equipment" / sub / "copper.png").convert("RGBA").save(dst / "copper.png")


def main():
    build_copper_equipment()
    armor = OVERLAY / "textures" / "models" / "armor"
    armor.mkdir(parents=True, exist_ok=True)
    for layer in (1, 2):
        canvas, overlay = build_layer(layer)
        canvas.save(armor / f"leather_layer_{layer}.png")
        overlay.resize((overlay.width * 2, overlay.height * 2), Image.NEAREST).save(armor / f"leather_layer_{layer}_overlay.png")
    shaders = OVERLAY / "shaders" / "core"
    shaders.mkdir(parents=True, exist_ok=True)
    fsh = (FSH.replace("@BASES@", str(len(BASES)))
           .replace("@MARKERS@", ", ".join(f"ivec3({a}, {b}, {c})" for a, b, c in MARKERS))
           .replace("@RX@", str(REGION[0])).replace("@RY@", str(REGION[1])).replace("@SCALE@", str(SCALE))
           .replace("@NOPATTERN@", str(NO_PATTERN)).replace("@STEP@", str(LEVEL_STEP)))
    (shaders / "rendertype_armor_cutout_no_cull.vsh").write_text(VSH, encoding="utf-8")
    (shaders / "rendertype_armor_cutout_no_cull.fsh").write_text(fsh, encoding="utf-8")
    (shaders / "rendertype_armor_cutout_no_cull.json").write_text(JSON, encoding="utf-8")
    icons = build_icons()
    table = {
        "iconCmdBase": ICON_CMD_BASE, "slots": SLOTS,
        "bases": BASES, "patterns": PATTERNS, "noPattern": NO_PATTERN, "palettes": PALETTES,
        "markers": [list(m) for m in MARKERS],
        "examples": {"iron_coast_amethyst": key_color("iron", 0, 0), "copper_plain": key_color("copper", 0, NO_PATTERN)},
    }
    OUT_JSON.write_text(json.dumps(table, indent=1), encoding="utf-8")
    print("wrote armor canvases, shader, %d trim icons and table; example keys" % icons, table["examples"])


if __name__ == "__main__":
    main()
