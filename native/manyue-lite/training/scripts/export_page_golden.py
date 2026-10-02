#!/usr/bin/env python3
"""Export a CPU FP32 cap-contract golden from one checksum-pinned comic page crop.

The generated crop is an evaluation fixture and is not included in the source archive.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import math
from pathlib import Path
import sys

import numpy as np
from PIL import Image
import torch

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
from continuous_lite import ContinuousLite


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def write_chw(path: Path, tensor: torch.Tensor) -> None:
    tensor.detach().cpu().contiguous().numpy().astype('<f4', copy=False).tofile(path)


def positive_half_up(value: float) -> int:
    return math.floor(value + 0.5)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('--training-root', default=str(ROOT))
    ap.add_argument('--data-root', help='root containing data/peppercarrot images; defaults to training-root')
    ap.add_argument('--checkpoint', default='runs/continuous-lite-best.pt')
    ap.add_argument('--page-id', default='E09P02', help='manifest page id, for example E09P02')
    ap.add_argument('--split', default='test', choices=('train', 'validation', 'test'))
    ap.add_argument('--width', type=int, default=690)
    ap.add_argument('--height', type=int, default=985)
    ap.add_argument('--x', type=int)
    ap.add_argument('--y', type=int)
    ap.add_argument('--scales', type=float, nargs='+', default=[1.0, 1.25, 1.37, 1.5, 1.7, 1.85, 1.9, 2.0])
    ap.add_argument('--output-dir', required=True)
    args = ap.parse_args()
    training_root = Path(args.training_root).resolve()
    data_root = Path(args.data_root).resolve() if args.data_root else training_root
    manifest_path = training_root / 'data' / 'peppercarrot' / 'manifest.json'
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    page = next((p for p in manifest['images']
                 if f"E{p['episode']:02d}P{p['page']:02d}" == args.page_id and p['split'] == args.split), None)
    if page is None:
        raise SystemExit(f"{args.page_id} is not in split {args.split}")
    image_path = data_root / page['path']
    if not image_path.is_file():
        raise SystemExit(f"source image is missing: {image_path}; fetch the manifest dataset first")
    out = Path(args.output_dir)
    if not out.is_absolute():
        out = training_root / out
    out.mkdir(parents=True, exist_ok=True)
    with Image.open(image_path) as image:
        image = image.convert('RGB')
        page_w, page_h = image.size
        x = args.x if args.x is not None else max(0, (page_w - args.width) // 2)
        y = args.y if args.y is not None else max(0, (page_h - args.height) // 2)
        if args.width <= 0 or args.height <= 0 or x < 0 or y < 0 or x + args.width > page_w or y + args.height > page_h:
            raise SystemExit(f'crop {(x, y, args.width, args.height)} exceeds source page {page_w}x{page_h}')
        crop = image.crop((x, y, x + args.width, y + args.height))
        crop.save(out / 'source.png', optimize=False)
        arr = np.asarray(crop, dtype=np.uint8).copy()
    src = torch.from_numpy(arr).permute(2, 0, 1).float().div_(255.0).unsqueeze(0)
    write_chw(out / 'source-chw.f32', src[0])
    checkpoint = Path(args.checkpoint)
    checkpoint = (checkpoint if checkpoint.is_absolute() else training_root / checkpoint).resolve()
    state = torch.load(checkpoint, map_location='cpu', weights_only=False)
    model = ContinuousLite().cpu().eval()
    model.load_state_dict(state['state_dict'], strict=True)
    torch.set_num_threads(4)
    files = {}
    for scale in args.scales:
        out_w = positive_half_up(args.width * scale)
        out_h = (args.height * out_w + args.width // 2) // args.width
        details = model(src, (out_h, out_w), strength=1.0, return_details=True)
        case = out / f'scale-{scale:.2f}x'
        case.mkdir(exist_ok=True)
        output_path = case / 'output-chw.f32'
        write_chw(output_path, details['output'][0])
        metadata = {
            'fixture_kind': 'held-out-page-crop-native-regression',
            'page_id': args.page_id, 'split': args.split, 'source_page_sha256': page['sha256'],
            'crop_xywh': [x, y, args.width, args.height],
            'source': {'file': '../source.png', 'chw_f32': '../source-chw.f32', 'width': args.width, 'height': args.height,
                       'layout': 'RGB CHW', 'dtype': 'little-endian float32', 'values_from_png_rgb8': True},
            'target': {'width': out_w, 'height': out_h, 'chw_f32': 'output-chw.f32', 'layout': 'RGB CHW',
                       'dtype': 'little-endian float32'},
            'requested_scale': scale,
            'actual_scale': {'x': out_w / args.width, 'y': out_h / args.height},
            'strength': 100, 'checkpoint_sha256': sha256(checkpoint), 'checkpoint_step': state.get('step'),
            'tf32': False, 'device': 'CPU',
            'coordinate_phase': 'FP32 ratio-first: ((float32(global_output)+0.5)*float32(input_size/output_size))-0.5; each operation rounded to FP32; no FMA; round-to-even for phase',
            'residual_cap': 'limit=0.02+0.02*(actual_output_width/input_width-1); clamp raw residual before strength',
            'output_rule': 'clamp(base + clamp(raw_residual,-limit,+limit) * strength/100, 0, 1)',
            'alpha': 'RGB fixture; no alpha channel',
        }
        (case / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n', encoding='utf-8')
        files[output_path.relative_to(out).as_posix()] = {'bytes': output_path.stat().st_size, 'sha256': sha256(output_path)}
    info = {'fixture': '690x985 centered crop from E09P02 held-out page', 'page_sha256': page['sha256'],
            'crop_xywh': [x, y, args.width, args.height], 'checkpoint_sha256': sha256(checkpoint),
            'checkpoint_step': state.get('step'), 'source_png_sha256': sha256(out / 'source.png'),
            'source_chw_sha256': sha256(out / 'source-chw.f32'), 'files': files}
    (out / 'golden-info.json').write_text(json.dumps(info, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'event': 'page_golden_export', 'directory': str(out), 'page': args.page_id,
                      'source_size': [args.width, args.height], 'crop': [x, y], 'outputs': files}, ensure_ascii=False))

if __name__ == '__main__':
    main()
