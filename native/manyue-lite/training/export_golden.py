#!/usr/bin/env python3
"""Export deterministic synthetic FP32 goldens for the Manyue-Lite contract."""
from __future__ import annotations

import json
import hashlib
import argparse
import io
import math
from pathlib import Path
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont
import torch
from torch.nn import functional as F

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / "scripts"))
if not (HERE / "scripts" / "continuous_lite.py").is_file():
    sys.path.insert(0, str(HERE.parents[3] / "light-sr-training" / "scripts"))
from continuous_lite import ContinuousLite


REPO_GOLDEN = HERE / "golden"
SEED = 77191
WIDTH, HEIGHT = 257, 193
SCALES = (1.0, 1.25, 1.37, 1.5, 1.75, 2.0)


def round_positive_half_up(value: float) -> int:
    return math.floor(value + 0.5)


def dimensions_for_scale(width: int, height: int, scale: float) -> tuple[int, int]:
    out_w = round_positive_half_up(width * scale)
    out_h = (height * out_w + width // 2) // width
    return out_h, out_w


def tile_grid(width: int, height: int, size: int, halo: int = 4) -> list[dict[str, int]]:
    tiles = []
    for y in range(0, height, size):
        for x in range(0, width, size):
            w, h = min(size, width - x), min(size, height - y)
            tiles.append({
                "core_x": x, "core_y": y, "core_width": w, "core_height": h,
                "crop_x": max(0, x - halo), "crop_y": max(0, y - halo),
                "crop_right": min(width, x + w + halo), "crop_bottom": min(height, y + h + halo),
                "discard_left": x - max(0, x - halo), "discard_top": y - max(0, y - halo),
            })
    return tiles


def write_chw(path: Path, tensor: torch.Tensor) -> None:
    array = tensor.detach().cpu().contiguous().numpy().astype("<f4", copy=False)
    array.tofile(path)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def make_source() -> Image.Image:
    yy, xx = np.mgrid[0:HEIGHT, 0:WIDTH]
    rgb = np.empty((HEIGHT, WIDTH, 3), dtype=np.uint8)
    rgb[..., 0] = np.clip(241 - xx * 0.16 + yy * 0.04, 0, 255).astype(np.uint8)
    rgb[..., 1] = np.clip(231 - yy * 0.19 + xx * 0.025, 0, 255).astype(np.uint8)
    rgb[..., 2] = np.clip(251 - xx * 0.05 - yy * 0.08, 0, 255).astype(np.uint8)
    image = Image.fromarray(rgb, "RGB").convert("RGBA")
    draw = ImageDraw.Draw(image)
    ink = (28, 32, 40, 255)
    draw.rectangle((9, 9, 247, 183), outline=ink, width=2)
    draw.line((13, 24, 243, 24), fill=ink, width=1)
    draw.line((14, 80, 243, 155), fill=(193, 68, 75, 255), width=1)
    draw.line((17, 152, 240, 53), fill=(35, 101, 140, 255), width=2)
    draw.ellipse((42, 42, 112, 112), outline=ink, width=2)
    draw.ellipse((57, 57, 97, 97), fill=(245, 174, 75, 255), outline=ink, width=1)
    draw.polygon(((142, 118), (175, 48), (211, 118)), fill=(116, 170, 122, 255), outline=ink)
    draw.rectangle((154, 126, 222, 159), fill=(221, 229, 233, 255), outline=ink, width=1)
    draw.line((160, 134, 216, 134), fill=ink, width=1)
    draw.line((160, 143, 208, 143), fill=ink, width=1)
    draw.line((160, 152, 198, 152), fill=ink, width=1)
    try:
        font = ImageFont.truetype("arial.ttf", 13)
    except OSError:
        font = ImageFont.load_default()
    draw.text((17, 28), "A1  fine lines / flat fills", fill=ink, font=font)
    alpha = np.full((HEIGHT, WIDTH), 255, dtype=np.uint8)
    alpha[:, :8] = np.linspace(128, 255, HEIGHT, dtype=np.uint8)[:, None]
    alpha[-5:, :] = 200
    image.putalpha(Image.fromarray(alpha, "L"))
    return image


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--training-root", default=str(HERE), help="self-contained training bundle root")
    parser.add_argument("--checkpoint", help="trained .pt checkpoint; omitted selects deterministic random contract weights")
    parser.add_argument("--output-dir", help="full golden output directory; relative paths resolve under training-root")
    parser.add_argument("--index-out", help="small JSON index to keep with source")
    parser.add_argument("--seed", type=int, default=SEED)
    args = parser.parse_args()
    training_root = Path(args.training_root).resolve()
    checkpoint = None
    if args.checkpoint:
        cp = Path(args.checkpoint)
        checkpoint = (cp if cp.is_absolute() else training_root / cp).resolve()
        if not checkpoint.is_file():
            raise SystemExit(f"checkpoint does not exist: {checkpoint}")
    kind = "trained" if checkpoint else "contract-random"
    out_arg = Path(args.output_dir) if args.output_dir else Path("golden") / f"{kind}-generated"
    out = (out_arg if out_arg.is_absolute() else training_root / out_arg).resolve()
    index_arg = Path(args.index_out) if args.index_out else REPO_GOLDEN / f"{kind}-index.json"
    index_path = (index_arg if index_arg.is_absolute() else training_root / index_arg).resolve()
    out.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(1)
    torch.backends.cuda.matmul.allow_tf32 = False
    torch.backends.cudnn.allow_tf32 = False
    torch.manual_seed(args.seed)
    source_image = make_source()
    source_image.save(out / "source.png")
    source_np = np.asarray(source_image.convert("RGB"), dtype=np.uint8).copy()
    source = torch.from_numpy(source_np).permute(2, 0, 1).float().div_(255.0).unsqueeze(0)
    write_chw(out / "source-chw.f32", source[0])

    rgb_image = source_image.convert("RGB")
    jpeg_stream = io.BytesIO()
    rgb_image.save(jpeg_stream, format="JPEG", quality=95, subsampling=0, optimize=False)
    (out / "source.jpg").write_bytes(jpeg_stream.getvalue())
    with Image.open(out / "source.jpg") as jpeg_image:
        jpeg_np = np.asarray(jpeg_image.convert("RGB"), dtype=np.uint8).copy()
    jpeg_source = torch.from_numpy(jpeg_np).permute(2, 0, 1).float().div_(255.0).unsqueeze(0)
    write_chw(out / "source-jpeg-chw.f32", jpeg_source[0])

    model = ContinuousLite().cpu().eval()
    if checkpoint:
        state = torch.load(checkpoint, map_location="cpu", weights_only=False)
        model.load_state_dict(state["state_dict"], strict=True)
        checkpoint_sha = sha256(checkpoint)
        checkpoint_step = state.get("step")
    else:
        # A deterministic nonzero head exercises residual addition and strength.
        with torch.no_grad():
            torch.manual_seed(args.seed + 1)
            model.head_residual.weight.normal_(0.0, 0.004)
            model.head_residual.bias.normal_(0.0, 0.001)
        state = {"state_dict": model.state_dict(), "seed": args.seed + 1,
                 "contract": "manyue-lite-continuous-v1", "purpose": "random nonzero-head contract golden"}
        torch.save(state, out / "golden-state.pt")
        checkpoint_sha = sha256(out / "golden-state.pt")
        checkpoint_step = None
    sources = {"png": source, "jpeg": jpeg_source}
    for name, tensor in sources.items():
        write_chw(out / f"{name}-features-chw.f32", model.features(tensor)[0])

    with torch.no_grad():
        for scale in SCALES:
            out_h, out_w = dimensions_for_scale(WIDTH, HEIGHT, scale)
            case = out / f"scale-{scale:.2f}x"
            case.mkdir(exist_ok=True)
            for input_name, input_tensor in sources.items():
                details = model(input_tensor, (out_h, out_w), strength=1.0, return_details=True)
                prefix = "" if input_name == "png" else "jpeg-"
                for key, filename in (("output", f"{prefix}output-chw.f32"),
                                      ("base", f"{prefix}base-chw.f32"),
                                      ("residual", f"{prefix}residual-chw.f32"),
                                      ("applied_residual", f"{prefix}applied-residual-chw.f32"),
                                      ("head_input", f"{prefix}head-input-chw.f32")):
                    write_chw(case / filename, details[key][0])
            metadata = {
                "kind": kind,
                "weights": checkpoint.name if checkpoint else "../golden-state.pt",
                "checkpoint_sha256": checkpoint_sha,
                "checkpoint_step": checkpoint_step,
                "seed": args.seed + 1 if not checkpoint else None,
                "contract": "manyue-lite-continuous-v1",
                "architecture": {"encoder": ["3x3:3->16", "Hardtanh[0,1]", "3x3:16->16", "Hardtanh[0,1]", "3x3:16->16", "Hardtanh[0,1]"],
                                  "head": ["1x1:23->32", "ReLU", "1x1:32->3 residual"]},
                "input": {"file": "../source.png", "chw_f32": "../source-chw.f32", "dtype": "little-endian float32",
                          "layout": "RGB CHW", "width": WIDTH, "height": HEIGHT, "source_is_rgba_png": True,
                          "alpha_rule": "input alpha is preserved unchanged by the native bitmap adapter; model golden covers RGB only"},
                "jpeg_input": {"file": "../source.jpg", "chw_f32": "../source-jpeg-chw.f32", "quality": 95,
                               "decoded_rgb_is_the_input_for_jpeg-prefixed_outputs": True},
                "target": {"width": out_w, "height": out_h, "chw_order": "RGB CHW", "dtype": "little-endian float32"},
                "requested_scale": scale,
                "actual_scale": {"x": out_w / WIDTH, "y": out_h / HEIGHT},
                "strength": 100,
                "python_strength_argument": 1.0,
                "sampling": {"feature": "bilinear align_corners=False half-pixel", "base": "bicubic a=-0.75 border extension",
                             "coordinate": "FP32 ratio-first: ((float32(globalOutput)+0.5)*float32(inputSize/outputSize))-0.5; each operation rounds to FP32; no FMA",
                             "phase": "2*(src-round-to-even(src)) in FP32",
                             "residual_cap": "limit=0.02+0.02*(actual_output_width/input_width-1); clamp raw residual before strength",
                             "output": "clamp(base + clamp(raw_residual,-limit,+limit) * strength/100, 0, 1)"},
                "files": {"output": "output-chw.f32", "base": "base-chw.f32", "residual": "residual-chw.f32",
                          "applied_residual": "applied-residual-chw.f32", "jpeg_output": "jpeg-output-chw.f32",
                          "head_input": "head-input-chw.f32", "features": "../png-features-chw.f32"},
                "tile_halo_input_pixels": 4,
                "encoder_tiles_128": tile_grid(WIDTH, HEIGHT, 128),
                "encoder_tiles_192": tile_grid(WIDTH, HEIGHT, 192),
                "output_tiles_128": tile_grid(out_w, out_h, 128, 0),
                "output_tiles_192": tile_grid(out_w, out_h, 192, 0),
            }
            (case / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    files = sorted(path for path in out.rglob("*") if path.is_file())
    index = {"kind": kind, "seed": args.seed + 1 if not checkpoint else None,
             "checkpoint_sha256": checkpoint_sha, "checkpoint_step": checkpoint_step,
             "artifact_directory": out.name,
             "files": {path.relative_to(out).as_posix(): {"bytes": path.stat().st_size, "sha256": sha256(path)} for path in files}}
    index_path.parent.mkdir(parents=True, exist_ok=True)
    index_path.write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"event": "golden_export", "kind": kind, "directory": str(out),
                      "index": str(index_path), "checkpoint_sha256": checkpoint_sha}, ensure_ascii=False))


if __name__ == "__main__":
    main()
