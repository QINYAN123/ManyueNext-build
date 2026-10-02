#!/usr/bin/env python3
"""Verify the Manyue-Lite source bundle and Android asset SHA-256 list."""
from __future__ import annotations
import hashlib
from pathlib import Path

TRAINING = Path(__file__).resolve().parent
REPO = TRAINING.parents[2]
CHECKSUMS = TRAINING / 'CHECKSUMS.sha256'


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def main() -> None:
    rows = [line.strip() for line in CHECKSUMS.read_text(encoding='utf-8').splitlines() if line.strip()]
    failed = []
    for row in rows:
        expected, relative = row.split('  ', 1)
        path = REPO / relative
        actual = sha256(path) if path.is_file() else 'MISSING'
        if actual != expected:
            failed.append((relative, expected, actual))
    if failed:
        for relative, expected, actual in failed:
            print(f'FAIL {relative}: expected {expected}, got {actual}')
        raise SystemExit(1)
    print(f'OK: {len(rows)} source and model-package files match SHA-256')

if __name__ == '__main__':
    main()
