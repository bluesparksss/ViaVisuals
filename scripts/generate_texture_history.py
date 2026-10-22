#!/usr/bin/env python3
"""
Write resource-pack-src/texture-history.json: for every 26.3 entity texture, the first release from
which every later client renders identical pixels ("changedAt"). Clients older than that render
something different and need a ViaVisuals proxy for visuals using the texture.

Pixel content is matched anywhere in an older version's entity textures, so pure file renames
(e.g. cat/tabby.png -> cat/cat_tabby.png) are not treated as visual changes.

Inputs: official client assets of every listed release (vanilla_cache.py).
"""
import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image

import vanilla_cache

PLUGIN_ROOT = Path(__file__).resolve().parent.parent
OUTPUT = PLUGIN_ROOT / "resource-pack-src" / "texture-history.json"

# (label, cache dir) oldest first; label is the release a client must be at least to match.
VERSIONS = [
    ("1.16", "1.16.5"), ("1.17", "1.17.1"), ("1.18", "1.18.2"), ("1.19", "1.19.2"),
    ("1.19.3", "1.19.3"), ("1.19.4", "1.19.4"), ("1.20", "1.20.1"), ("1.20.2", "1.20.2"),
    ("1.20.3", "1.20.4"), ("1.20.5", "1.20.6"), ("1.21", "1.21.1"), ("1.21.2", "1.21.3"),
    ("1.21.4", "1.21.4"), ("1.21.5", "1.21.5"), ("1.21.6", "1.21.8"), ("1.21.9", "1.21.10"),
    ("1.21.11", "1.21.11"), ("26.1", "26.1.2"), ("26.2", "26.2"),
]
CURRENT = vanilla_cache.assets("26.3") / "textures"
SCOPES = ["entity"]


def pixel_hash(path):
    img = Image.open(path).convert("RGBA")
    arr = np.asarray(img).copy()
    arr[arr[..., 3] == 0] = 0  # ignore colour under fully transparent pixels
    return hashlib.sha1(arr.tobytes() + bytes(str(arr.shape), "ascii")).hexdigest()


def hashes_for(root):
    by_path, all_hashes = {}, set()
    for scope in SCOPES:
        base = root / scope
        if not base.exists():
            continue
        for png in base.rglob("*.png"):
            rel = png.relative_to(root).as_posix()
            h = pixel_hash(png)
            by_path[rel] = h
            all_hashes.add(h)
    return by_path, all_hashes


def main():
    current, _ = hashes_for(CURRENT)
    history = {}
    per_version = []
    for label, ver in VERSIONS:
        root = vanilla_cache.assets(ver) / "textures"
        per_version.append((label, hashes_for(root)[1]))

    for rel, h in current.items():
        changed_at = "26.3"
        for label, hashes in reversed(per_version):
            if h in hashes:
                changed_at = label
            else:
                break
        history["minecraft:" + rel.removesuffix(".png")] = changed_at

    OUTPUT.write_text(json.dumps(dict(sorted(history.items())), indent=1) + "\n", encoding="utf-8")
    newer = sum(1 for v in history.values() if v != "1.16")
    print(f"{len(history)} textures, {newer} changed after 1.16 -> {OUTPUT}")


if __name__ == "__main__":
    main()
