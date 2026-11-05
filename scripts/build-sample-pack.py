#!/usr/bin/env python3
"""Builds the sample's offline bundle from a DSL app directory (the engine sample's facetheory demo).

    scripts/build-sample-pack.py [<app dir>] [<out .a8pack dir>]

Defaults: ../app8-android-sdk/sample/src/main/assets/facetheory →
sample/src/main/assets/App8OfflinePackages/facetheory.a8pack. The result is an
`app8.offline.screen` bundle (docs/OFFLINE_BUNDLE_FORMAT.md) carrying the manifest, the
stylesheet, the component templates, every screen, the datasources and the media. Images over
300 KB are downscaled with `sips` (macOS) when it is available, to keep the repository small.
"""
import base64
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT.parent / "app8-android-sdk/sample/src/main/assets/facetheory"
OUT = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "sample/src/main/assets/App8OfflinePackages/facetheory.a8pack"
APP_ID = "facetheory-demo"
VERSION = "v1"


def load(path: pathlib.Path):
    return json.loads(path.read_text())


def main() -> None:
    if OUT.exists():
        shutil.rmtree(OUT)
    (OUT / "assets").mkdir(parents=True)

    screens = {p.stem: {"version": VERSION, "data": load(p)} for p in sorted((SRC / "screens").glob("*.json"))}
    payload = {
        "app": load(SRC / "app.json"),
        # Each `styles` entry is one stylesheet (a JSON array of styles), as /sdk/v1 serves them.
        "styles": [load(SRC / "styles.json")],
        "components": [load(p) for p in sorted((SRC / "components").glob("*.json"))],
        "screens": screens,
        "datasources": {f"datasources/{p.stem}": load(p) for p in sorted((SRC / "datasources").glob("*.json"))},
        "localizations": {"defaultLocale": "en", "locales": {}},
    }

    assets = []
    for media in sorted((SRC / "media").iterdir()):
        if media.name.startswith("."):
            continue
        target = OUT / "assets" / media.name
        shutil.copyfile(media, target)
        if target.stat().st_size > 300_000 and shutil.which("sips"):
            scaled = target.with_suffix(".scaled" + target.suffix)
            subprocess.run(["sips", "-Z", "800", "-s", "formatOptions", "normal", str(target), "--out", str(scaled)], check=True, capture_output=True)
            if scaled.stat().st_size < target.stat().st_size:
                scaled.replace(target)
            else:
                scaled.unlink()
        data = target.read_bytes()
        mime = "image/png" if media.suffix == ".png" else "image/jpeg"
        assets.append({
            "id": media.stem,
            "name": media.stem,
            "filename": media.name,
            "kind": "image",
            "mimeType": mime,
            "sizeBytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
            "path": f"assets/{media.name}",
        })

    manifest = {
        "type": "app8.offline.screen",
        "name": "Facetheory",
        "version": VERSION,
        "dslVersion": "1.0",
        "bundleFormat": 1,
        "appId": APP_ID,
        "key": "launch",
        "createdAt": "2026-11-05T00:00:00Z",
        "content": base64.b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode(),
        "assets": assets,
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=1) + "\n")
    print(f"{OUT}: {len(screens)} screens, {len(assets)} assets")


if __name__ == "__main__":
    main()
