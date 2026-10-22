#!/usr/bin/env python3
"""
Assigns every block added after 1.16 to a client-side carrier and writes:

  src/main/resources/block-catalog.json   runtime: server state -> client state (+ display CMD)
  resource-pack-src/block-pack.json       build:   carrier blockstate variants + display item models

Carriers (Oraxen-style, all states exist on 1.16):
  note      full opaque cubes      -> note_block[instrument,note,powered]  (real note blocks -> one reserved state)
  leaves    full cutout cubes      -> spare <wood>_leaves[distance,persistent] (real leaves -> canonical state)
  tripwire  no-hitbox simple       -> tripwire with powered/disarmed != false/false (real -> false/false)
  display   everything else       -> vanilla structural fallback with matching collision + item display model

Inputs: resource-pack-src/vanilla-blocks.json (tools/vanilla-models/DumpBlocks),
registry-since.json, vanilla 26.3 blockstates/models (vanilla_cache.py).
"""
import itertools
import json
from pathlib import Path

import vanilla_cache

PLUGIN = Path(__file__).resolve().parent.parent
WORKSPACE = PLUGIN.parent
ASSETS = vanilla_cache.assets("26.3")
BLOCKS = json.loads((PLUGIN / "resource-pack-src" / "vanilla-blocks.json").read_text(encoding="utf-8"))
SINCE = json.loads((PLUGIN / "src" / "main" / "resources" / "registry-since.json").read_text(encoding="utf-8"))["blocks"]
OUT_RUNTIME = PLUGIN / "src" / "main" / "resources" / "block-catalog.json"
OUT_PACK = PLUGIN / "resource-pack-src" / "block-pack.json"

EXCLUDED = {"test_block", "test_instance_block", "light"}
# Blocks that generate in ordinary terrain get the scarce carrier slots first (the rest falls back to displays,
# one entity per block per viewer).
COMMON = ["leaf_litter", "wildflowers", "pink_petals", "bush", "firefly_bush", "short_dry_grass", "tall_dry_grass",
          "cactus_flower", "pale_moss_carpet", "pale_moss_block", "pale_hanging_moss", "azalea", "flowering_azalea",
          "azalea_leaves", "flowering_azalea_leaves", "cherry_leaves", "cherry_sapling", "cherry_log", "pale_oak_leaves",
          "pale_oak_log", "pale_oak_sapling", "mangrove_leaves", "mangrove_log", "mangrove_roots", "mangrove_propagule",
          "closed_eyeblossom", "open_eyeblossom", "moss_block", "moss_carpet", "small_dripleaf", "big_dripleaf",
          "big_dripleaf_stem", "hanging_roots", "spore_blossom", "cave_vines", "cave_vines_plant", "glow_lichen",
          "pointed_dripstone", "dripstone_block", "amethyst_cluster", "small_amethyst_bud", "medium_amethyst_bud",
          "large_amethyst_bud", "amethyst_block", "budding_amethyst", "calcite", "tuff", "deepslate", "copper_ore",
          "deepslate_copper_ore", "mud", "packed_mud", "sculk", "sculk_vein", "sculk_catalyst", "sculk_shrieker",
          "sculk_sensor"]
# Tint sources vanilla's BlockColors gives post-1.16 blocks, by model tintindex. Carriers other than leaves have no
# colour handler on a 1.16 client, so the build bakes the plains colour into the textures instead.
TINT = {"leaf_litter": {"0": "dry_foliage"}, "wildflowers": {"1": "grass"}, "pink_petals": {"1": "grass"},
        "bush": {"0": "grass"}, "water_cauldron": {"0": "water"}}
# Leaves blocks tinted by the client's foliage handler: only on carriers whose own handler is foliage too
# (spruce and birch leaves use fixed colours).
FOLIAGE_LEAVES = {"mangrove_leaves"}
FOLIAGE_CARRIERS = {"oak_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves"}
# 1.16 sign type whose colour is closest to a newer wood (the block-entity text renders on the vanilla fallback).
SIGN_CARRIER = {"bamboo": "birch", "cherry": "acacia", "mangrove": "crimson", "pale_oak": "birch"}
SPARE_PROP = "powered"
# Hand-openable carriers whose "powered=true" states are free once real blocks are shown unpowered (the models of
# these blocks ignore the property). Each look takes one whole carrier block.
SPARE_CLASSES = {"TrapDoorBlock": "trapdoor", "WeatheringCopperTrapDoorBlock": "trapdoor",
                 "DoorBlock": "door", "WeatheringCopperDoorBlock": "door", "FenceGateBlock": "gate"}
# warped_* hosts the blank-model "powered=true" states used as invisible carriers; the other woods host visible looks.
BLANK_WOOD = "warped"
WOODS_116 = ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "crimson"]
BLANK = {"trapdoor": f"{BLANK_WOOD}_trapdoor", "door": f"{BLANK_WOOD}_door", "gate": f"{BLANK_WOOD}_fence_gate"}
HIDDEN_MODEL = "viavisuals:block_display/hidden"
# Display blocks that also exist as falling-block entities. A falling block is drawn by the client from a block
# state, so these get a spare leaves state (cutout layer, like the block's own texture) with the block's own model
# ("falling" in the runtime catalog) instead of the raw id, which ViaBackwards turns into a granite wall or nothing.
# They take the leaves slots no block claimed.
FALLING_DISPLAY = {"pointed_dripstone"}
# Display blocks whose fallback has their exact shape and an opaque model (the inflated display covers it and the
# carrier keeps the right collision and sound); everything else gets an invisible carrier.
EXACT_CLASSES = {"StairBlock", "WeatheringCopperStairBlock", "SlabBlock", "WeatheringCopperSlabBlock", "WallBlock",
                 "FenceBlock", "ButtonBlock", "PressurePlateBlock", "CandleCakeBlock",
                 "LayeredCauldronBlock", "LavaCauldronBlock"}
SPARE_SUFFIX = {"trapdoor": "trapdoor", "door": "door", "gate": "fence_gate"}
# Legacy clients scan item overrides linearly per render: spread display models over neutral proxies.
DISPLAY_PROXIES = ["BRICK", "NETHER_BRICK", "FLINT", "CLAY_BALL", "PRISMARINE_SHARD", "PRISMARINE_CRYSTALS",
                   "RABBIT_HIDE", "PHANTOM_MEMBRANE", "GLOWSTONE_DUST", "SUGAR"]
DISPLAY_SHARD = 800
INSTRUMENTS_116 = ["harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar", "chime",
                   "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling"]
NOTE_RESERVED = "instrument=harp,note=0,powered=false"
LEAVES_116 = ["oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves"]
LEAVES_CANONICAL = "distance=7,persistent=true"
WOOD_SOUNDS = ("wood", "bamboo_wood", "cherry_wood", "nether_wood", "stem")

CLASS_FALLBACK = {
    "StairBlock": ("oak_stairs", "cobblestone_stairs"),
    "WeatheringCopperStairBlock": ("oak_stairs", "cobblestone_stairs"),
    "SlabBlock": ("oak_slab", "cobblestone_slab"),
    "WeatheringCopperSlabBlock": ("oak_slab", "cobblestone_slab"),
    "WallBlock": ("cobblestone_wall", "cobblestone_wall"),
    "FenceBlock": ("oak_fence", "nether_brick_fence"),
    "FenceGateBlock": ("oak_fence_gate", "oak_fence_gate"),
    "DoorBlock": ("oak_door", "iron_door"),
    "WeatheringCopperDoorBlock": ("oak_door", "iron_door"),
    "TrapDoorBlock": ("oak_trapdoor", "iron_trapdoor"),
    "WeatheringCopperTrapDoorBlock": ("oak_trapdoor", "iron_trapdoor"),
    "ButtonBlock": ("oak_button", "stone_button"),
    "PressurePlateBlock": ("oak_pressure_plate", "stone_pressure_plate"),
    "StandingSignBlock": ("oak_sign", "oak_sign"),
    "WallSignBlock": ("oak_wall_sign", "oak_wall_sign"),
    "ChainBlock": ("chain", "chain"),
    "WeatheringCopperChainBlock": ("chain", "chain"),
    "IronBarsBlock": ("iron_bars", "iron_bars"),
    "WeatheringCopperBarsBlock": ("iron_bars", "iron_bars"),
    "LanternBlock": ("lantern", "lantern"),
    "WeatheringLanternBlock": ("lantern", "lantern"),
    "LightningRodBlock": ("chain", "chain"),
    "WeatheringLightningRodBlock": ("chain", "chain"),
    "CandleBlock": ("sea_pickle", "sea_pickle"),
    "CandleCakeBlock": ("cake", "cake"),
    "FlowerPotBlock": ("flower_pot", "flower_pot"),
    "CarpetBlock": ("white_carpet", "white_carpet"),
    "TorchBlock": ("torch", "torch"),
    "WallTorchBlock": ("wall_torch", "wall_torch"),
    "LayeredCauldronBlock": ("cauldron", "cauldron"),
    "LavaCauldronBlock": ("cauldron", "cauldron"),
}
# Signs keep the vanilla fallback only: the block-entity text renders on it and a display would hide it.
FALLBACK_ONLY = {"StandingSignBlock", "WallSignBlock"}
# Properties renamed between 26.x and 1.16 fallbacks.
PROP_RENAME = {"candles": "pickles", "axis": "axis"}
BER_NO_MODEL = {"decorated_pot", "copper_chest", "exposed_copper_chest", "weathered_copper_chest", "oxidized_copper_chest",
                "waxed_copper_chest", "waxed_exposed_copper_chest", "waxed_weathered_copper_chest", "waxed_oxidized_copper_chest",
                "piglin_head", "piglin_wall_head"}
# Carrier states whose own model is replaced by an empty one (a display sits on top and the carrier would
# show through or hide it). Real blocks in that state are shown as the visually identical twin instead.
# Opaque full-cube carriers show through (or darken) the display that sits inside them: an invisible,
# light-transparent barrier with the same full-cube collision carries those displays instead.
OPAQUE_CARRIERS = {"stone", "snow[layers=8]"}
HIDDEN_CARRIERS = {
    "grindstone[face=ceiling,facing=north]": "grindstone[face=ceiling,facing=south]",
}
BER_NO_MODEL |= {f"{w}{o}copper_golem_statue" for w in ("", "waxed_") for o in ("", "exposed_", "weathered_", "oxidized_")}


def ber_item(bid, props):
    """Item special model a block-entity-rendered state shows (pose / double-chest half variants)."""
    if "copper_golem_pose" in props:
        return f"{bid}_{props['copper_golem_pose']}"
    if props.get("type") in ("left", "right") and bid.endswith("chest"):
        return f"{bid}_{props['type']}"
    return bid


def state_yaw(props):
    """Entity-style yaw (south 0, west 90, north 180, east 270) for block-entity models without variants."""
    if "facing" in props:
        return {"south": 0, "west": 90, "north": 180, "east": 270}.get(props["facing"], 0)
    if "rotation" in props:
        return int(props["rotation"]) * 22.5
    return 0


def parse_props(key):
    if not key:
        return {}
    return dict(p.split("=", 1) for p in key.split(","))


def blockstate(bid):
    path = ASSETS / "blockstates" / f"{bid}.json"
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def when_matches(when, props):
    if "OR" in when:
        return any(when_matches(w, props) for w in when["OR"])
    if "AND" in when:
        return all(when_matches(w, props) for w in when["AND"])
    for key, val in when.items():
        options = str(val).lower().split("|")
        neg = False
        if options[0].startswith("!"):
            neg = True
            options[0] = options[0][1:]
        hit = props.get(key) in options
        if hit == neg:
            return False
    return True


def visual_for(bs, props):
    """List of {model,x,y,uvlock} rendering this state."""
    if bs is None:
        return []
    if "variants" in bs:
        for key, val in bs["variants"].items():
            want = parse_props(key)
            if all(props.get(k) == v for k, v in want.items()):
                v = val[0] if isinstance(val, list) else val
                return [{k: v[k] for k in ("model", "x", "y", "uvlock") if k in v}]
        return []
    parts = []
    for part in bs["multipart"]:
        if "when" not in part or when_matches(part["when"], props):
            a = part["apply"]
            a = a[0] if isinstance(a, list) else a
            parts.append({k: a[k] for k in ("model", "x", "y", "uvlock") if k in a})
    return parts


def legacy_ok_props(fallback_id, props):
    """Keeps only properties the 1.16 fallback block has, with its value set."""
    fb = BLOCKS.get(fallback_id)
    if fb is None:
        return ""
    out = []
    for name, values in fb["properties"].items():
        src = props.get(name)
        if src is None:
            for new, old in PROP_RENAME.items():
                if old == name and new in props:
                    src = props[new]
        if src is not None and src in values:
            out.append(f"{name}={src}")
    return ",".join(out)


def box_volume(boxes):
    return sum((b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]) for b in boxes)


def voxels(boxes):
    """Occupied cells of a 16^3 grid."""
    cells = set()
    for b in boxes:
        x0, y0, z0 = (int(b[i] * 16) for i in range(3))
        x1, y1, z1 = (max(int(b[i] * 16) + 1, int(round(b[i + 3] * 16))) for i in range(3))
        for x in range(x0, x1):
            for y in range(y0, y1):
                for z in range(z0, z1):
                    cells.add((x, y, z))
    return frozenset(cells)


def fallback_pool():
    """Unique collision shapes of 1.16-native, behaviour-neutral blocks -> one representative state."""
    skip_classes = {"ChestBlock", "BedBlock", "ShulkerBoxBlock", "DoorBlock", "DoublePlantBlock", "FallingBlock",
                    "LiquidBlock", "FireBlock", "NetherPortalBlock", "EndPortalBlock", "PistonHeadBlock", "MovingPistonBlock",
                    "SignBlock", "StandingSignBlock", "WallSignBlock", "BannerBlock", "WallBannerBlock", "SkullBlock",
                    "WallSkullBlock", "BarrierBlock", "StructureVoidBlock", "TripWireBlock", "NoteBlock", "LeavesBlock",
                    "PowderSnowBlock", "ScaffoldingBlock", "CakeBlock", "BubbleColumnBlock", "AirBlock",
                    # client-side physics, climbing or ambient particles
                    "LadderBlock", "VineBlock", "SlimeBlock", "HoneyBlock", "SoulSandBlock", "IceBlock",
                    "HalfTransparentBlock", "WebBlock", "SweetBerryBushBlock", "CactusBlock", "DragonEggBlock",
                    "ChorusPlantBlock", "ChorusFlowerBlock", "EndRodBlock", "TorchBlock", "WallTorchBlock",
                    "RedstoneTorchBlock", "RedstoneWallTorchBlock", "RedStoneWireBlock", "TwistingVinesBlock",
                    "TwistingVinesPlantBlock", "WeepingVinesBlock", "WeepingVinesPlantBlock", "MagmaBlock",
                    "FrostedIceBlock", "CampfireBlock", "RespawnAnchorBlock", "RedStoneOreBlock", "BeehiveBlock"}
    shapes = {}
    for bid, b in BLOCKS.items():
        if bid in SINCE or b["hasBlockEntity"] or b["class"] in skip_classes or b["class"].startswith("Infested"):
            continue
        for st in b["states"]:
            if not st["collision"]:
                continue
            vox = voxels(st["collision"])
            # a waterlogged carrier draws a water cube: only waterlogged server states may use one
            kind = "wet" if "waterlogged=true" in st["props"] else "dry"
            slot = shapes.setdefault(vox, {})
            if kind not in slot:
                slot[kind] = f"{bid}[{st['props']}]" if st["props"] else bid
    return list(shapes.items())


def best_fallback(collision, pool, wet=False, cache={}):
    target = voxels(collision)
    if (target, wet) in cache:
        return cache[(target, wet)]
    best, score = None, -1.0
    for vox, slot in pool:
        s = len(target & vox) / max(1, len(target | vox))
        if s > score:
            best, score = slot, s
            if s >= 0.999:
                break
    state = (best.get("wet") or best.get("dry")) if wet else (best.get("dry") or best.get("wet"))
    cache[(target, wet)] = state
    return state


def invisible_pool():
    """Carriers that draw nothing: (client state, collision cells, waterlogged, spare key or None).

    The blank spare states are "powered=true" states of one trapdoor, door and fence gate type; the client draws
    their (empty) model and keeps their collision, so they stand in for any shape a thin wall or plate fits.
    """
    full = voxels([[0, 0, 0, 1, 1, 1]])
    pool = [("air", frozenset(), False, None), ("barrier", full, False, None),
            ("barrier[waterlogged=true]", full, True, None)]
    for block in BLANK.values():
        for st in BLOCKS[block]["states"]:
            props = parse_props(st["props"])
            if props.get("powered") != "true":
                continue
            key = ",".join(f"{k}={props[k]}" for k in sorted(props))
            pool.append((f"{block}[{key}]", voxels(st["collision"]), props.get("waterlogged") == "true", (block, key)))
    return pool


def best_invisible(collision, wet, pool, cache={}):
    """The invisible carrier whose collision is closest: solid cells it lacks cost double (the server would
    push the player back), cells it adds cost one (the client stops the player early)."""
    target = voxels(collision) if collision else frozenset()
    if (target, wet) in cache:
        return cache[(target, wet)]
    best, score = None, None
    for cand in pool:
        if cand[2] != wet:
            continue
        err = 2 * len(target - cand[1]) + len(cand[1] - target)
        if score is None or err < score:
            best, score = cand, err
    cache[(target, wet)] = best
    return best


def main():
    pool = fallback_pool()
    invis = invisible_pool()
    note_free = [f"instrument={i},note={n},powered={p}" for i in INSTRUMENTS_116 for p in ("false", "true")
                 for n in range(25)]
    note_free.remove(NOTE_RESERVED)
    leaves_free = [(leaf, f"distance={d},persistent={p}") for leaf in LEAVES_116 for d in range(1, 8)
                   for p in ("false", "true") if f"distance={d},persistent={p}" != LEAVES_CANONICAL]
    trip_free = []
    for att, e, n, s, w in itertools.product(("false", "true"), repeat=5):
        for powered, disarmed in (("true", "false"), ("false", "true"), ("true", "true")):
            trip_free.append(f"attached={att},disarmed={disarmed},east={e},north={n},powered={powered},south={s},west={w}")

    pack = {"note_block": {}, "tripwire": {}, "leaves": {}, "displays": {}, "spare": {}}
    runtime = {"noteReserved": NOTE_RESERVED, "leavesCanonical": LEAVES_CANONICAL,
               "leavesCarriers": LEAVES_116, "hidden": HIDDEN_CARRIERS, "spareProp": SPARE_PROP,
               "spareCarriers": sorted(BLANK.values()), "blocks": {}}
    wooden = {kind: [f"{w}_{suffix}" for w in WOODS_116] for kind, suffix in SPARE_SUFFIX.items()}
    metal = {"trapdoor": ["iron_trapdoor"], "door": ["iron_door"], "gate": []}
    spare_by_look = {}  # (kind, per-state visuals) -> carrier block
    visual_slot = {}   # (carrier kind, visual json) -> client state
    display_cmd = {}   # visual json -> cmd
    next_display = 300001
    counts = {"note": 0, "leaves": 0, "tripwire": 0, "display": 0, "fallback": 0}

    order = sorted(SINCE.items(), key=lambda kv: (COMMON.index(kv[0]) if kv[0] in COMMON else len(COMMON), kv[0]))
    for bid, ver in order:
        if bid in EXCLUDED or bid not in BLOCKS:
            continue
        info = BLOCKS[bid]
        bs = blockstate(bid)
        states = info["states"]
        full = all(st["fullCube"] for st in states)
        opaque = all(st["occludes"] for st in states)
        nohit = all(not st["collision"] for st in states)
        # many-variant blocks (multiface, signs) would exhaust the 96 tripwire slots
        distinct_visuals = len({json.dumps(visual_for(bs, parse_props(st["props"])), sort_keys=True) for st in states})
        wood = any(info["sound"].startswith(w) or f".{w}." in info["sound"] for w in WOOD_SOUNDS) or "wood" in info["sound"]
        out_states = {}
        spare_kind = SPARE_CLASSES.get(info["class"])
        spare_carrier = None
        if spare_kind and bid not in BER_NO_MODEL and all(len(visual_for(bs, parse_props(st["props"]))) == 1 for st in states):
            look = (spare_kind, tuple(json.dumps(visual_for(bs, parse_props(st["props"])), sort_keys=True) for st in states))
            spare_carrier = spare_by_look.get(look)
            if spare_carrier is None:
                pools = [wooden[spare_kind]] if wood else [metal[spare_kind], wooden[spare_kind]]
                for pool_list in pools:
                    if pool_list:
                        spare_carrier = pool_list.pop(0)
                        break
                if spare_carrier:
                    spare_by_look[look] = spare_carrier
                    runtime["spareCarriers"].append(spare_carrier)
        for st in states:
            props = parse_props(st["props"])
            vis = visual_for(bs, props)
            simple = len(vis) == 1 and bid not in BER_NO_MODEL
            vis_key = json.dumps(vis, sort_keys=True)
            if bid in BER_NO_MODEL:
                vis_key += "|" + ber_item(bid, props)
            entry = None
            if spare_carrier:
                client = dict(p.split("=", 1) for p in legacy_ok_props(spare_carrier, props).split(",") if p)
                client[SPARE_PROP] = "true"
                key = ",".join(f"{k}={client[k]}" for k in sorted(client))
                pack["spare"].setdefault(spare_carrier, {})[key] = vis[0]
                entry = {"client": f"{spare_carrier}[{key}]"}
                kind = "spare"
            elif full and simple and opaque:
                kind = "note"
            elif full and simple:
                kind = "leaves"
            elif nohit and vis and bid not in BER_NO_MODEL and info["class"] not in CLASS_FALLBACK and distinct_visuals <= 16:
                kind = "tripwire"
            else:
                kind = "display"
            if kind in ("note", "leaves", "tripwire") and entry is None:
                slot = visual_slot.get((kind, vis_key))
                if slot is None:
                    free = {"note": note_free, "leaves": leaves_free, "tripwire": trip_free}[kind]
                    pick = 0 if free else None
                    if kind == "leaves" and bid in FOLIAGE_LEAVES:
                        pick = next((i for i, (leaf, _) in enumerate(free) if leaf in FOLIAGE_CARRIERS), None)
                    if pick is not None:
                        slot = free.pop(pick)
                        visual_slot[(kind, vis_key)] = slot
                        if kind == "note":
                            pack["note_block"][slot] = vis[0]
                        elif kind == "leaves":
                            # the carrier's foliage handler tints every tintindex face: only mangrove leaves want it
                            pack["leaves"].setdefault(slot[0], {})[slot[1]] = vis[0] if bid in FOLIAGE_LEAVES else {
                                "parts": vis, "tint": {"0": "none"}}
                        else:
                            if bid in TINT:
                                pack["tripwire"][slot] = {"parts": vis, "tint": TINT[bid]}
                            else:
                                pack["tripwire"][slot] = vis[0] if len(vis) == 1 else {"parts": vis}
                    else:
                        kind = "display"
                if kind == "note":
                    entry = {"client": f"note_block[{slot}]"}
                elif kind == "leaves":
                    entry = {"client": f"{slot[0]}[{slot[1]}]"}
                elif kind == "tripwire":
                    entry = {"client": f"tripwire[{slot}]"}
            if entry is None:
                cls = info["class"]
                invisible = None
                if bid not in BER_NO_MODEL and cls not in FALLBACK_ONLY and cls not in EXACT_CLASSES and vis:
                    invisible = best_invisible(st["collision"], "waterlogged=true" in st["props"], invis)
                if invisible is not None:
                    fallback = invisible[0]
                    if invisible[3]:
                        pack["spare"].setdefault(invisible[3][0], {})[invisible[3][1]] = {"model": HIDDEN_MODEL}
                elif cls in CLASS_FALLBACK:
                    fb_id = CLASS_FALLBACK[cls][0 if wood else 1]
                    if cls in FALLBACK_ONLY:
                        kind_id = "_wall_sign" if cls == "WallSignBlock" else "_sign"
                        fb_id = SIGN_CARRIER.get(bid.removesuffix(kind_id), "oak") + kind_id
                    fb_props = legacy_ok_props(fb_id, props)
                    fallback = f"{fb_id}[{fb_props}]" if fb_props else fb_id
                elif not st["collision"]:
                    fallback = "air"
                else:
                    fallback = best_fallback(st["collision"], pool, "waterlogged=true" in st["props"])
                entry = {"client": fallback}
                if invisible is not None:
                    entry["invisible"] = True
                if cls in FALLBACK_ONLY:
                    counts["fallback"] += 1
                else:
                    cmd = display_cmd.get(vis_key)
                    if cmd is None and (vis or bid in BER_NO_MODEL):
                        # Legacy clients test a proxy's overrides from the highest custom_model_data down (every value
                        # >= a threshold matches), so the blocks that are processed first (COMMON) take the top of
                        # their shard and are found after one or two checks.
                        idx = next_display - 300001
                        shard, within = divmod(idx, DISPLAY_SHARD)
                        shard = min(shard, len(DISPLAY_PROXIES) - 1)
                        cmd = 300001 + shard * DISPLAY_SHARD + (DISPLAY_SHARD - 1 - within)
                        next_display += 1
                        display_cmd[vis_key] = cmd
                        proxy = DISPLAY_PROXIES[shard]
                        pack["displays"][str(cmd)] = {"parts": vis, "item": ber_item(bid, props) if bid in BER_NO_MODEL else None,
                                                      "proxy": proxy}
                        if bid in TINT:
                            pack["displays"][str(cmd)]["tint"] = TINT[bid]
                    if cmd is not None:
                        entry["display"] = cmd
                        if bid in BER_NO_MODEL:
                            entry["yaw"] = state_yaw(props)
                        entry["proxy"] = pack["displays"][str(cmd)]["proxy"]
                        entry["interact"] = not st["collision"]
                        if fallback in OPAQUE_CARRIERS:
                            entry["client"] = "barrier"
                    kind = "display"
            counts[kind] = counts.get(kind, 0) + 1
            out_states[st["props"]] = entry
        runtime["blocks"][bid] = {"since": ver, "hardness": info["hardness"], "sound": info["sound"],
                                  "light": info["lightEmission"], "states": out_states}

    for bid in FALLING_DISPLAY:
        bs = blockstate(bid)
        looks = {}
        for key, entry in runtime["blocks"][bid]["states"].items():
            vis = visual_for(bs, parse_props(key))
            if len(vis) != 1 or "display" not in entry:
                continue
            vis_key = json.dumps(vis, sort_keys=True)
            if vis_key not in looks:
                leaf, props = leaves_free.pop(0)
                pack["leaves"].setdefault(leaf, {})[props] = {"parts": vis, "tint": {"0": "none"}}
                looks[vis_key] = f"{leaf}[{props}]"
            entry["falling"] = looks[vis_key]

    # a hidden carrier is only safe under states that bring their own display
    bare = sorted({f"{bid}[{k}]" for bid, b in runtime["blocks"].items() for k, e in b["states"].items()
                   if e["client"] in HIDDEN_CARRIERS and "display" not in e})
    if bare:
        raise SystemExit("hidden carrier used by states without a display: " + ", ".join(bare[:8]))
    pack["hidden"] = {}
    for carrier in HIDDEN_CARRIERS:
        block, _, props = carrier.partition("[")
        pack["hidden"].setdefault(block, []).append(props.rstrip("]"))
    OUT_RUNTIME.write_text(json.dumps(runtime, separators=(",", ":")) + "\n", encoding="utf-8")
    OUT_PACK.write_text(json.dumps(pack, indent=1) + "\n", encoding="utf-8")
    print("state assignments:", counts)
    print("spare carriers:", {look[0]: carrier for look, carrier in spare_by_look.items()} if False else sorted(spare_by_look.values()))
    print(f"free: note {len(note_free)}, leaves {len(leaves_free)}, tripwire {len(trip_free)}; displays {len(display_cmd)}")


if __name__ == "__main__":
    main()
