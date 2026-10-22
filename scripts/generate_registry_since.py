#!/usr/bin/env python3
"""
Write src/main/resources/registry-since.json: first release each item/block/entity id exists in,
for everything added after 1.16 (Nether Update content is native for every supported client).

Source: ViaVersion mapping-<ver>.json registries (https://github.com/ViaVersion/Mappings),
cached in .vanilla-cache/via-mappings.
"""
import json
import urllib.request
from pathlib import Path

VERSIONS = [
    "1.16", "1.16.2", "1.17", "1.18", "1.19", "1.19.3", "1.19.4", "1.20", "1.20.2", "1.20.3",
    "1.20.5", "1.21", "1.21.2", "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.9", "1.21.11",
    "26.1", "26.2", "26.3", "26.4",
]
KINDS = ("items", "blocks", "entities", "sounds")
# Renamed ids: ViaBackwards maps them back to the identical old id, so they are not new content.
RENAMED = {"short_grass", "dirt_path", "iron_chain", "turtle_scute"}
BASE_URL = "https://raw.githubusercontent.com/ViaVersion/Mappings/main/mappings"

PLUGIN_ROOT = Path(__file__).resolve().parent.parent
CACHE = PLUGIN_ROOT / ".vanilla-cache" / "via-mappings"
OUTPUT = PLUGIN_ROOT / "src" / "main" / "resources" / "registry-since.json"


def load(ver):
    path = CACHE / f"mapping-{ver}.json"
    if not path.exists():
        CACHE.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(f"{BASE_URL}/mapping-{ver}.json") as resp:
            path.write_bytes(resp.read())
    return json.loads(path.read_text(encoding="utf-8"))


def main():
    since = {kind: {} for kind in KINDS}
    seen = {kind: set() for kind in KINDS}
    for ver in VERSIONS:
        data = load(ver)
        for kind in KINDS:
            ids = set(data[kind])
            if ver != VERSIONS[0]:
                for new_id in sorted(ids - seen[kind]):
                    if new_id not in RENAMED:
                        since[kind].setdefault(new_id, ver)
            seen[kind] = ids
    # 1.16.2 only added piglin_brute (+ its egg); 1.16 clients are still in scope.
    out = {"versions": VERSIONS, **since}
    OUTPUT.write_text(json.dumps(out, indent=1, sort_keys=False) + "\n", encoding="utf-8")
    print({kind: len(since[kind]) for kind in KINDS}, "->", OUTPUT)


if __name__ == "__main__":
    main()
