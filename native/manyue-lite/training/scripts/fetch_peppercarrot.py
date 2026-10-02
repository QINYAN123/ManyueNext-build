#!/usr/bin/env python3
"""Fetch official English compiled Pepper&Carrot comic pages for local training.

The downloaded images stay under this workspace's data directory. They are not
copied into the app or distributed with model weights. See README.md for license
and attribution notes. Chapter-level split prevents page-level leakage.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
import time
import urllib.request
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data" / "peppercarrot"
BASE = "https://www.peppercarrot.com"
CC_BY = "https://creativecommons.org/licenses/by/4.0/"

# These identifiers are official source-directory slugs, not guessed filename
# patterns. Episodes are held out by chapter: train 1-6, validation 7-8, test 9-10.
EPISODES = [
    (1, "Potion of Flight", "ep01_Potion-of-Flight__files.html", "train"),
    (2, "Rainbow Potions", "ep02_Rainbow-potions.html", "train"),
    (3, "The Secret Ingredients", "ep03_The-secret-ingredients__files.html", "train"),
    (4, "Stroke of Genius", "ep04_Stroke-of-genius.html", "train"),
    (5, "Special holiday episode", "ep05_Special-holiday-episode.html", "train"),
    (6, "The Potion Contest", "ep06_The-Potion-Contest.html", "train"),
    (7, "The Wish", "ep07_The-Wish__files.html", "validation"),
    (8, "Pepper's Birthday Party", "ep08_Pepper-s-Birthday-Party.html", "validation"),
    (9, "The Remedy", "ep09_The-Remedy.html", "test"),
    (10, "Summer Special", "ep10_Summer-Special.html", "test"),
]

UA = "Manyue-Lite research data fetcher/1.0 (+https://www.peppercarrot.com/)"
IMG_RE = re.compile(
    r'https://www\.peppercarrot\.com/0_sources/([^\"\'<>]+/hi-res/)'
    r'en_Pepper-and-Carrot_by-David-Revoy_E(\d{2})P(\d{2})\.jpg', re.I
)


def fetch(url: str, timeout: int = 45) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        if response.status != 200:
            raise RuntimeError(f"HTTP {response.status}: {url}")
        return response.read()


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def page_links(source_html: str, ep: int) -> list[tuple[int, str]]:
    """Get only numbered page renderings; omit P00/title, credits and cover."""
    links: dict[int, str] = {}
    # The site wraps each numbered rendering in a figure whose caption is Page N.
    for figure in re.findall(r"<figure\b.*?</figure\s*>", source_html, flags=re.I | re.S):
        caption = re.search(r"<strong>\s*Page\s+(\d+)\s*</strong>", figure, flags=re.I)
        if not caption:
            continue
        page_num = int(caption.group(1))
        for m in IMG_RE.finditer(figure):
            if int(m.group(2)) == ep and int(m.group(3)) == page_num:
                links[page_num] = m.group(0)
                break
    return sorted(links.items())


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--refresh", action="store_true", help="redownload even if the local file exists")
    parser.add_argument("--max-episodes", type=int, default=len(EPISODES))
    args = parser.parse_args()
    DATA.mkdir(parents=True, exist_ok=True)
    manifest: dict = {
        "dataset": "Pepper&Carrot official English compiled comic pages",
        "creator": "David Revoy",
        "license": "Creative Commons Attribution 4.0 International (CC BY 4.0)",
        "license_url": CC_BY,
        "official_attribution_guide": f"{BASE}/en/documentation/120_License_best_practices.html",
        "accessed_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "split_policy": "whole episodes; no pages from one episode cross splits",
        "episodes": [],
        "images": [],
        "redistribution": "source images remain local research/training data; do not package or upload them with model weights",
    }
    selected = EPISODES[: max(0, min(args.max_episodes, len(EPISODES)))]
    for ep, title, source_filename, split in selected:
        source_url = f"{BASE}/en/webcomic-sources/{source_filename}"
        source_bytes = fetch(source_url)
        source_html = source_bytes.decode("utf-8", "replace")
        pages = page_links(source_html, ep)
        if not pages:
            raise RuntimeError(f"No numbered English page links found for episode {ep}: {source_url}")
        episode_dir = DATA / split / f"ep{ep:02d}"
        episode_dir.mkdir(parents=True, exist_ok=True)
        episode_record = {
            "episode": ep,
            "title": title,
            "split": split,
            "source_page": source_url,
            "source_page_sha256": sha256(source_bytes),
            "license": "CC BY 4.0",
            "license_url": CC_BY,
            "attribution": f'"{title}" (Pepper&Carrot episode {ep}) by David Revoy; English compiled pages; see episode-specific source page for translation/proofreading credits.',
            "page_count": len(pages),
            "pages": [],
        }
        for page_num, url in pages:
            path = episode_dir / f"E{ep:02d}P{page_num:02d}.jpg"
            data = path.read_bytes() if path.exists() and not args.refresh else fetch(url)
            if args.refresh or not path.exists():
                path.write_bytes(data)
            digest = sha256(data)
            with Image.open(path) as image:
                image.verify()
            with Image.open(path) as image:
                width, height = image.size
                fmt = image.format
            if fmt != "JPEG":
                raise RuntimeError(f"Expected JPEG, got {fmt}: {path}")
            rel = path.relative_to(ROOT).as_posix()
            item = {
                "episode": ep,
                "page": page_num,
                "split": split,
                "path": rel,
                "source_url": url,
                "sha256": digest,
                "bytes": len(data),
                "width": width,
                "height": height,
                "mode": "RGB on decode; source is JPEG",
                "license": "CC BY 4.0",
            }
            episode_record["pages"].append(item)
            manifest["images"].append(item)
            print(f"ep{ep:02d} page {page_num:02d} {width}x{height} {len(data)/1024/1024:.2f} MiB sha256={digest}")
        manifest["episodes"].append(episode_record)
        print(f"episode {ep}: {len(pages)} pages -> {split}")

    target = DATA / "manifest.json"
    target.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {target}")


if __name__ == "__main__":
    main()
