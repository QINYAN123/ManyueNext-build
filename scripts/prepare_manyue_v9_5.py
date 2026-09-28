#!/usr/bin/env python3
"""Extract the v9 baseline and apply the tested v9.1 through v9.5 patch chain."""

from pathlib import Path
import re
import subprocess
import zipfile


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / ".build" / "source"
PATCHES = [
    "manyue_v9.1_cugan_fix.patch",
    "manyue_v9.2_scroll_smooth.patch",
    "manyue_v9.3_ai_swap.patch",
    "manyue_v9.4_scale_render.patch",
    "manyue_v9.5_webtoon_fix.patch",
]

if SOURCE.exists():
    raise SystemExit(".build/source already exists; use a fresh checkout to avoid applying patches twice")

with zipfile.ZipFile(ROOT / "ManyueNext-v9-source.zip") as archive:
    archive.extractall(SOURCE)

for name in PATCHES:
    patch = ROOT / name
    if not patch.is_file():
        raise SystemExit(f"Required patch is missing: {name}")
    text = patch.read_text(encoding="utf-8")
    for relative in re.findall(r"^--- a/([^\t\n]+)", text, re.M):
        source_file = SOURCE / relative
        if source_file.is_file():
            source_file.write_bytes(source_file.read_bytes().replace(b"\r\n", b"\n"))

    normalized = ROOT / ".build" / name
    normalized.write_text(text, encoding="utf-8", newline="\n")
    command = ["git", "apply", "--directory=.build/source"]
    subprocess.run(command + ["--check", str(normalized)], cwd=ROOT, check=True)
    subprocess.run(command + [str(normalized)], cwd=ROOT, check=True)

gradle_file = SOURCE / "app" / "build.gradle.kts"
gradle_text = gradle_file.read_text(encoding="utf-8")
if not re.search(r'versionCode\s*=\s*35\b', gradle_text):
    raise SystemExit("Prepared source does not have v9.5 versionCode 35")
if not re.search(r'versionName\s*=\s*"0\.20\.9"', gradle_text):
    raise SystemExit("Prepared source does not have v9.5 versionName 0.20.9")

print("Manyue v9.5 source prepared at .build/source")
