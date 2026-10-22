"""Official Minecraft client assets for the maintainer scripts, kept in .vanilla-cache/ next to the Gradle build's.

assets(version) returns <cache>/<version>/assets/minecraft when `./gradlew fetchVanillaAssets` already extracted that
client's full assets (the main release and 1.16.5). Any other version is downloaded from Mojang, its client jar is
checked against the SHA-1 in Mojang's version manifest, and assets/minecraft/ is extracted into
<cache>/scripts/<version>/.
"""
import hashlib
import json
import shutil
import urllib.request
import zipfile
from pathlib import Path

CACHE = Path(__file__).resolve().parent.parent / ".vanilla-cache"
MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
PREFIX = "assets/minecraft/"


def _complete(folder):
    marker = folder / ".complete"
    return marker.exists() and PREFIX in marker.read_text(encoding="utf-8").splitlines()[1:]


def _get(url, sha1=None):
    with urllib.request.urlopen(url) as resp:
        data = resp.read()
    if sha1 and hashlib.sha1(data).hexdigest() != sha1:
        raise SystemExit(f"SHA-1 mismatch for {url}")
    return data


def assets(version):
    for folder in (CACHE / version, CACHE / "scripts" / version):
        if _complete(folder):
            return folder / "assets" / "minecraft"
    folder = CACHE / "scripts" / version
    manifest = json.loads(_get(MANIFEST))
    entry = next((v for v in manifest["versions"] if v["id"] == version), None)
    if entry is None:
        raise SystemExit(f"Mojang's version manifest has no Minecraft {version}")
    client = json.loads(_get(entry["url"], entry["sha1"]))["downloads"]["client"]
    print(f"downloading the Minecraft {version} client jar")
    jar = CACHE / "jars" / f"scripts-{version}.jar"
    jar.parent.mkdir(parents=True, exist_ok=True)
    jar.write_bytes(_get(client["url"], client["sha1"]))
    shutil.rmtree(folder, ignore_errors=True)
    with zipfile.ZipFile(jar) as zf:
        for name in zf.namelist():
            if name.startswith(PREFIX) and not name.endswith("/"):
                zf.extract(name, folder)
    jar.unlink()
    (folder / ".complete").write_text(client["sha1"] + "\n" + PREFIX, encoding="utf-8")
    return folder / "assets" / "minecraft"
