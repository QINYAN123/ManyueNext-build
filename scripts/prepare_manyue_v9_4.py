#!/usr/bin/env python3
"""Extract the v9 baseline and apply the tested v9.1 through v9.4 patch chain."""
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / '.build/source'
if SOURCE.exists():
    raise SystemExit('.build/source exists; use a fresh checkout to avoid patching twice')
with zipfile.ZipFile(ROOT / 'ManyueNext-v9-source.zip') as archive:
    archive.extractall(SOURCE)
patches = [
    'manyue_v9.1_cugan_fix.patch', 'manyue_v9.2_scroll_smooth.patch',
    'manyue_v9.3_ai_swap.patch', 'manyue_v9.4_scale_render.patch',
]
for name in patches:
    patch = ROOT / name
    text = patch.read_text(encoding='utf-8')
    for relative in re.findall(r'^--- a/([^\t\n]+)', text, re.M):
        file = SOURCE / relative
        if file.is_file():
            file.write_bytes(file.read_bytes().replace(b'\r\n', b'\n'))
    normalized = ROOT / '.build' / name
    normalized.write_text(text, encoding='utf-8', newline='\n')
    args = ['git', 'apply', '--directory=.build/source']
    subprocess.run(args + ['--check', str(normalized)], cwd=ROOT, check=True)
    subprocess.run(args + [str(normalized)], cwd=ROOT, check=True)
print('Manyue v9.4 source prepared at .build/source')
