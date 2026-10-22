#!/usr/bin/env python3
"""
Generate src/main/resources/custom-items.json: one proxy + CustomModelData per item added after 1.16.

Inputs:
  src/main/resources/registry-since.json   (generate_registry_since.py)
  tools/decompile-mc/cache/<ver>/src        (tools/decompile-mc/decompile.py) for Items.java + assets

Every listed item is a real vanilla item on the server; identity is the server Material, so no
server-side tagging is needed. Clients older than `since` get `proxy` + `cmd`.
"""
import json
import math
import re
import sys
from pathlib import Path

PLUGIN_ROOT = Path(__file__).resolve().parent.parent
WORKSPACE = PLUGIN_ROOT.parent
SINCE_FILE = PLUGIN_ROOT / "src" / "main" / "resources" / "registry-since.json"
OUTPUT = PLUGIN_ROOT / "src" / "main" / "resources" / "custom-items.json"
SOURCE_VERSIONS = ["26.3", "26.1.2"]

EXCLUDED = {
    "test_block", "test_instance_block",
    # renamed to *_pottery_sherd in 1.20
    "pottery_shard_archer", "pottery_shard_arms_up", "pottery_shard_prize", "pottery_shard_skull",
}

DYES = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
        "cyan", "purple", "blue", "brown", "green", "red", "black"]

# Proxies whose client behaviour matches the item. Everything else is PAPER.
EXPLICIT_PROXY = {
    "mace": "WARPED_FUNGUS_ON_A_STICK",
    "brush": "WARPED_FUNGUS_ON_A_STICK",
    "wolf_armor": "WARPED_FUNGUS_ON_A_STICK",
    "wind_charge": "CARROT_ON_A_STICK",
    "copper_sword": "STONE_SWORD",
    "copper_pickaxe": "STONE_PICKAXE",
    "copper_axe": "STONE_AXE",
    "copper_shovel": "STONE_SHOVEL",
    "copper_hoe": "STONE_HOE",
    "copper_helmet": "LEATHER_HELMET",
    "copper_chestplate": "LEATHER_CHESTPLATE",
    "copper_leggings": "LEATHER_LEGGINGS",
    "copper_boots": "LEATHER_BOOTS",
    "copper_horse_armor": "LEATHER_HORSE_ARMOR",
    "netherite_horse_armor": "LEATHER_HORSE_ARMOR",
    "recovery_compass": "COMPASS",
    "glow_berries": "COOKIE",
    "ominous_bottle": "HONEY_BOTTLE",
    "blue_egg": "EGG",
    "brown_egg": "EGG",
}

# Leather tint for leather-proxied armor (worn layer on legacy clients).
ARMOR_TINT = {
    "copper_helmet": 0xB4684D, "copper_chestplate": 0xB4684D, "copper_leggings": 0xB4684D,
    "copper_boots": 0xB4684D, "copper_horse_armor": 0xB4684D, "netherite_horse_armor": 0x4D494D,
}

CMD_BASE = {"PAPER": 30001}
CMD_OTHER_BASE = 40001
SPEAR_CMD_BASE = 50000
SPEAR_CMD_STRIDE = 200


def find_source(rel):
    for ver in SOURCE_VERSIONS:
        path = WORKSPACE / "tools" / "decompile-mc" / "cache" / ver / "src" / rel
        if path.exists():
            return path
    return None


def parse_items_java():
    path = find_source("net/minecraft/world/item/Items.java")
    if path is None:
        sys.exit("Items.java not found; run tools/decompile-mc/decompile.py first")
    src = path.read_text(encoding="utf-8")
    bodies = {}
    pattern = r'public static final Item \w+ = register\w*\(\s*(?:"([a-z0-9_]+)"|ItemIds\.(\w+))(.*?)\);\n'
    for m in re.finditer(pattern, src, re.S):
        item_id = m.group(1) or m.group(2).lower()
        bodies[item_id] = " ".join(m.group(3).split())
    return bodies


def spear_params(body):
    m = re.search(r"spear\(ToolMaterial\.\w+,\s*([\d.]+)F,\s*([\d.]+)F,\s*([\d.]+)F,\s*([\d.]+)F,\s*([\d.]+)F,"
                  r"\s*([\d.]+)F,\s*([\d.]+)F,\s*([\d.]+)F,\s*([\d.]+)F\)", body)
    if not m:
        return None
    vals = [float(v) for v in m.groups()]
    attack_duration, dmg_mult, delay, dismount_t, _, knockback_t, _, damage_t, _ = vals
    return {
        "attackDurationTicks": int(attack_duration * 20),
        "delayTicks": int(delay * 20),
        "dismountTicks": int(dismount_t * 20),
        "knockbackTicks": int(knockback_t * 20),
        "damageTicks": int(damage_t * 20),
    }


# --- vanilla SpearAnimations (client/model/effects/SpearAnimations.java), right arm ---

def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def progress(t, a, b):
    if b == a:
        return 1.0 if t >= b else 0.0
    return clamp((t - a) / (b - a), 0.0, 1.0)


def ease_in_out_back(x):
    c1 = 1.70158
    c2 = c1 * 1.525
    if x < 0.5:
        return ((2 * x) ** 2 * ((c2 + 1) * 2 * x - c2)) / 2
    return ((2 * x - 2) ** 2 * ((c2 + 1) * (x * 2 - 2) + c2) + 2) / 2


def ease_out_back(x):
    c1 = 1.70158
    c3 = c1 + 1
    return 1 + c3 * (x - 1) ** 3 + c1 * (x - 1) ** 2


def ease_out_cubic(x):
    return 1 - (1 - x) ** 3


def ease_in_out_elastic(x):
    c5 = (2 * math.pi) / 4.5
    if x == 0 or x == 1:
        return x
    if x < 0.5:
        return -(2 ** (20 * x - 10) * math.sin((20 * x - 11.125) * c5)) / 2
    return (2 ** (-20 * x + 10) * math.sin((20 * x - 11.125) * c5)) / 2 + 1


def ease_out_circ(x):
    return math.sqrt(1 - (x - 1) ** 2)


def ease_in_circ(x):
    return 1 - math.sqrt(1 - x ** 2)


def use_params(sp, t):
    finish_raise = sp["delayTicks"]
    finish_sway = sp["dismountTicks"] + finish_raise
    start_sway = finish_sway - 20
    finish_lower = sp["knockbackTicks"] + finish_raise
    start_lower = finish_lower - 40
    finish_raise_back = sp["damageTicks"] + finish_raise
    raise_p = progress(t, 0, finish_raise)
    p = {
        "raise": raise_p,
        "raiseStart": progress(raise_p, 0, 0.5),
        "raiseMiddle": progress(raise_p, 0.5, 0.8),
        "raiseEnd": progress(raise_p, 0.8, 1.0),
        "sway": progress(t, start_sway, finish_sway),
        "lower": ease_out_cubic(ease_in_out_elastic(progress(t - 20, start_lower, finish_lower))),
        "raiseBack": progress(t, finish_raise_back - 5, finish_raise_back),
    }
    p["swayIntensity"] = 2 * ease_out_circ(p["sway"]) - 2 * ease_in_circ(p["raiseBack"])
    p["swaySlow"] = math.sin(t * 19 * math.pi / 180) * p["swayIntensity"]
    p["swayFast"] = math.sin(t * 30 * math.pi / 180) * p["swayIntensity"]
    return p


def first_person_pose(sp, t):
    """Translation (blocks) + list of (axis, degrees, pivot) applied after the arm transform."""
    p = use_params(sp, t)
    tx = p["raise"] * 0.15 + p["raiseEnd"] * -0.05 + p["sway"] * -0.1 + p["swaySlow"] * 0.005
    ty = p["raise"] * -0.075 + p["raiseMiddle"] * 0.075 + p["swayFast"] * 0.01
    tz = p["raiseStart"] * 0.05 + p["raiseEnd"] * -0.05 + p["swaySlow"] * 0.005
    rx = -65 * ease_in_out_back(p["raise"]) - 35 * p["lower"] + 100 * p["raiseBack"] - 0.5 * p["swayFast"]
    ry = -(-90 * progress(p["raise"], 0.5, 0.55) + 90 * p["sway"] + 2 * p["swaySlow"])
    return {"t": [tx, ty, tz], "rot": [["x", rx, [0, 0.1, 0]], ["y", ry, [0.15, 0, 0]]]}


def third_person_pose(sp, t):
    p = use_params(sp, t)
    raise_mod = 1 - ease_out_back(1 - p["raise"])
    forward = 0.38
    tz = -forward * (raise_mod - p["raiseBack"])
    rx = -(70 * (p["raise"] - p["raiseBack"]))
    ry = 90 * (p["raise"] - p["sway"])
    arm_rx = (-40 * p["raiseStart"] + 30 * p["raiseMiddle"] - 20 * p["raiseEnd"] + 20 * p["lower"]
              + 10 * p["raiseBack"] + 0.6 * p["swaySlow"] * p["swayIntensity"])
    arm_ry = -p["swayFast"] * p["swayIntensity"]
    arm_rz = -p["swaySlow"] * p["swayIntensity"] * 0.5
    return {"t": [0, 0, tz], "rot": [["x", rx, [0, -0.03125, 0.125]], ["y", ry, [0, 0, 0.125]]],
            "armX": arm_rx, "armY": arm_ry, "armZ": arm_rz}


# --- 4x4 affine helpers (column vectors, PoseStack order: M = M * op) ---

def mat_identity():
    return [[1.0 if i == j else 0.0 for j in range(4)] for i in range(4)]


def mat_mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def mat_translate(x, y, z):
    m = mat_identity()
    m[0][3], m[1][3], m[2][3] = x, y, z
    return m


def mat_rot(axis, deg):
    r = math.radians(deg)
    c, s = math.cos(r), math.sin(r)
    m = mat_identity()
    if axis == "x":
        m[1][1], m[1][2], m[2][1], m[2][2] = c, -s, s, c
    elif axis == "y":
        m[0][0], m[0][2], m[2][0], m[2][2] = c, s, -s, c
    else:
        m[0][0], m[0][1], m[1][0], m[1][1] = c, -s, s, c
    return m


def mat_inverse_rigid(m):
    r = [[m[j][i] for j in range(3)] for i in range(3)]
    t = [-sum(r[i][k] * m[k][3] for k in range(3)) for i in range(3)]
    out = mat_identity()
    for i in range(3):
        for j in range(3):
            out[i][j] = r[i][j]
        out[i][3] = t[i]
    return out


def rotate_around(axis, deg, pivot):
    return mat_mul(mat_mul(mat_translate(*pivot), mat_rot(axis, deg)),
                   mat_translate(-pivot[0], -pivot[1], -pivot[2]))


def pose_matrix(pose):
    m = mat_translate(*pose["t"])
    for axis, deg, pivot in pose["rot"]:
        m = mat_mul(m, rotate_around(axis, deg, pivot))
    return m


def display_matrix(tr):
    """ItemTransform.apply without the trailing scale/-0.5 (rigid part only)."""
    t = tr.get("translation", [0, 0, 0])
    r = tr.get("rotation", [0, 0, 0])
    m = mat_translate(t[0] / 16, t[1] / 16, t[2] / 16)
    for axis, deg in zip("xyz", r):
        m = mat_mul(m, mat_rot(axis, deg))
    return m


def decompose_display(m, scale):
    """Inverse of display_matrix: rotationXYZ euler (degrees) + translation in 1/16 units."""
    sy = clamp(m[0][2], -1.0, 1.0)
    ry = math.degrees(math.asin(sy))
    if abs(sy) < 0.99999:
        rx = math.degrees(math.atan2(-m[1][2], m[2][2]))
        rz = math.degrees(math.atan2(-m[0][1], m[0][0]))
    else:
        rx = math.degrees(math.atan2(m[2][1], m[1][1]))
        rz = 0.0
    tr = [clamp(m[i][3] * 16, -80, 80) for i in range(3)]
    return {"rotation": [round(rx, 3), round(ry, 3), round(rz, 3)],
            "translation": [round(v, 4) for v in tr], "scale": scale}


def arm_rotation(x_deg, y_deg, z_deg):
    """ModelPart.translateAndRotate rotation: rotationZYX(z, y, x) = Rz * Ry * Rx."""
    return mat_mul(mat_mul(mat_rot("z", z_deg), mat_rot("y", y_deg)), mat_rot("x", x_deg))


# ItemInHandLayer after translateToHand (right arm, adult).
HAND = mat_mul(mat_mul(mat_rot("x", -90), mat_rot("y", 180)), mat_translate(1 / 16, 2 / 16, -10 / 16))
# HumanoidModel.poseBlockingArm with a level head: what a legacy client shows while "blocking".
LEGACY_BLOCK_ARM = arm_rotation(math.degrees(-0.9424779), -30.0, 0.0)


def frame_display(base_display, fp, tp):
    """Display transforms that make a legacy blocking pose render the vanilla spear-use pose."""
    out = {}
    fpr = base_display["firstperson_righthand"]
    m = mat_mul(pose_matrix(fp), display_matrix(fpr))
    out["firstperson_righthand"] = decompose_display(m, fpr.get("scale", [1, 1, 1]))

    tpr = base_display["thirdperson_righthand"]
    van_arm = arm_rotation(math.degrees(-math.pi / 2 + 0.8) + tp["armX"],
                           math.degrees(-0.1) + tp["armY"], tp["armZ"])
    corr = mat_mul(mat_inverse_rigid(HAND), mat_mul(mat_mul(mat_inverse_rigid(LEGACY_BLOCK_ARM), van_arm), HAND))
    m = mat_mul(mat_mul(corr, pose_matrix(tp)), display_matrix(tpr))
    out["thirdperson_righthand"] = decompose_display(m, tpr.get("scale", [1, 1, 1]))

    for hand in ("firstperson", "thirdperson"):
        right = out[hand + "_righthand"]
        rot = right["rotation"]
        out[hand + "_lefthand"] = {"rotation": [rot[0], -rot[1], -rot[2]],
                                   "translation": list(right["translation"]), "scale": right["scale"]}
    return out


def spear_frames(sp, base_display):
    """Samples the use animation per tick and collapses ticks with near-identical poses."""
    end = sp["damageTicks"] + sp["delayTicks"]
    frames, timeline = [], []
    last = None
    for t in range(0, end + 1):
        fp = first_person_pose(sp, t)
        tp = third_person_pose(sp, t)
        sig = [round(v, 3) for v in fp["t"]] + [round(r[1], 1) for r in fp["rot"]] \
            + [round(tp["t"][2], 3)] + [round(r[1], 1) for r in tp["rot"]] + [round(tp["armX"], 1)]
        if last is None or any(abs(a - b) > tol for a, b, tol in zip(
                sig, last, [0.012] * 3 + [3.5] * 2 + [0.02] + [4.0] * 2 + [4.0])):
            frames.append(frame_display(base_display, fp, tp))
            last = sig
        timeline.append(len(frames) - 1)
    return frames, timeline


def main():
    since = json.loads(SINCE_FILE.read_text(encoding="utf-8"))["items"]
    spear_base_display = json.loads(
        find_source("assets/minecraft/models/item/spear_in_hand.json").read_text(encoding="utf-8"))["display"]
    bodies = parse_items_java()
    next_cmd = dict(CMD_BASE)
    other_cmd = CMD_OTHER_BASE
    spear_idx = 0
    items = []

    for item_id, ver in since.items():
        if item_id in EXCLUDED:
            continue
        if find_source(f"assets/minecraft/items/{item_id}.json") is None:
            print(f"skip {item_id} ({ver}): no vanilla item asset in {SOURCE_VERSIONS}")
            continue
        body = bodies.get(item_id, "")
        entry = {"id": item_id, "since": ver}

        if item_id.endswith("_spear"):
            sp = spear_params(body)
            frames, timeline = spear_frames(sp, spear_base_display)
            base = SPEAR_CMD_BASE + spear_idx * SPEAR_CMD_STRIDE
            spear_idx += 1
            entry.update({"proxy": "CARROT_ON_A_STICK", "cmd": base, "spear": {
                **sp,
                "inHandCmd": base + 1,
                "frameCmdBase": base + 10,
                "frames": frames,
                "timeline": timeline,
            }})
            if len(frames) > SPEAR_CMD_STRIDE - 10:
                sys.exit(f"{item_id}: {len(frames)} frames exceed the CMD stride")
        else:
            proxy = EXPLICIT_PROXY.get(item_id, "PAPER")
            if proxy in next_cmd:
                cmd = next_cmd[proxy]
                next_cmd[proxy] += 1
            else:
                cmd = other_cmd
                other_cmd += 1
            entry.update({"proxy": proxy, "cmd": cmd})
            if item_id in ARMOR_TINT:
                entry["tint"] = ARMOR_TINT[item_id]
        if item_id.endswith("_spawn_egg"):
            entry["spawnEgg"] = True
        items.append(entry)

    # Pre-1.21.5 spawn eggs share two-tone textures; 1.21.5 gave each egg a unique sprite.
    # Eggs older clients already know get the new per-mob sprites from these entries.
    for egg in sorted(p.stem for p in (find_source("assets/minecraft/items") or Path()).glob("*_spawn_egg.json")):
        if egg in since:
            continue
        items.append({"id": egg, "since": "1.21.5", "proxy": "PAPER",
                      "cmd": next_cmd["PAPER"], "spawnEgg": True, "retexture": True})
        next_cmd["PAPER"] += 1

    OUTPUT.write_text(json.dumps({"items": items}, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"wrote {len(items)} items ({spear_idx} spears) -> {OUTPUT}")


if __name__ == "__main__":
    main()
