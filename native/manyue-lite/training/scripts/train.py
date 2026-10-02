#!/usr/bin/env python3
"""Bounded CUDA training for the experimental Manyue-Lite continuous model."""
from __future__ import annotations

import argparse
from collections import OrderedDict
import io
import json
import math
import random
import time
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter
import torch
from torch.nn import functional as F

from continuous_lite import ContinuousLite, load_manifest_images, psnr_from_mse


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "data" / "peppercarrot" / "manifest.json"


class PageCache:
    def __init__(self, rows: list[dict], max_items: int = 32):
        self.rows = rows
        self.max_items = max_items
        self.cache: OrderedDict[str, Image.Image] = OrderedDict()

    def get(self, row: dict) -> Image.Image:
        key = row["absolute_path"]
        if key in self.cache:
            self.cache.move_to_end(key)
            return self.cache[key]
        im = Image.open(key).convert("RGB")
        self.cache[key] = im
        while len(self.cache) > self.max_items:
            _, old = self.cache.popitem(last=False)
            old.close()
        return im

    def crop(self, row: dict, size: int, rng: random.Random) -> torch.Tensor:
        im = self.get(row)
        w, h = im.size
        if w < size or h < size:
            raise RuntimeError(f"page too small for crop: {row['absolute_path']} {im.size}")
        x = rng.randint(0, w - size)
        y = rng.randint(0, h - size)
        crop = im.crop((x, y, x + size, y + size))
        if rng.random() < 0.5:
            crop = crop.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
        if rng.random() < 0.15:
            crop = crop.transpose(Image.Transpose.FLIP_TOP_BOTTOM)
        if rng.random() < 0.15:
            crop = crop.transpose(Image.Transpose.ROTATE_90)
        arr = np.asarray(crop, dtype=np.uint8).copy()
        crop.close()
        return torch.from_numpy(arr).permute(2, 0, 1).float().div_(255.0)


def jpeg_roundtrip(batch: torch.Tensor, rng: random.Random, qualities: tuple[int, int]) -> torch.Tensor:
    rows = []
    for image in batch.detach().clamp(0, 1).cpu():
        arr = (image.permute(1, 2, 0).numpy() * 255.0 + 0.5).astype(np.uint8)
        im = Image.fromarray(arr, "RGB")
        stream = io.BytesIO()
        im.save(stream, format="JPEG", quality=rng.randint(*qualities), subsampling=2, optimize=False)
        stream.seek(0)
        with Image.open(stream) as decoded:
            rows.append(torch.from_numpy(np.asarray(decoded.convert("RGB"), dtype=np.uint8).copy()).permute(2, 0, 1))
        im.close()
    return torch.stack(rows).to(device=batch.device, dtype=batch.dtype).div_(255.0)


def quantize8(batch: torch.Tensor) -> torch.Tensor:
    """Match the reader's RGB_888 source boundary after synthetic resampling."""
    return batch.clamp(0.0, 1.0).mul(255.0).round().div_(255.0)


def mild_blur_noise(batch: torch.Tensor, rng: random.Random) -> torch.Tensor:
    out = batch
    if rng.random() < 0.75:
        weights = torch.rand((out.shape[0], 1, 1, 1), device=out.device, dtype=out.dtype) * 0.24
        blurred = F.avg_pool2d(F.pad(out, (1, 1, 1, 1), mode="replicate"), 3, stride=1)
        out = out * (1.0 - weights) + blurred * weights
    if rng.random() < 0.35:
        sigma = rng.uniform(0.0006, 0.0035)
        out = out + torch.randn_like(out) * sigma
    return out.clamp(0.0, 1.0)


def make_batch(cache: PageCache, train_rows: list[dict], batch_size: int, crop: int,
               device: torch.device, rng: random.Random, category: str) -> tuple[torch.Tensor, torch.Tensor]:
    target = torch.stack([cache.crop(rng.choice(train_rows), crop, rng) for _ in range(batch_size)]).to(device)
    if category in ("clean1x", "degraded1x"):
        src = target.clone()
    else:
        ratio = rng.uniform(1.05, 1.50) if category == "sr_low" else rng.uniform(1.50, 2.00)
        in_size = max(32, round(crop / ratio))
        src = quantize8(F.interpolate(target, size=(in_size, in_size), mode="bicubic", align_corners=False))
    if category == "degraded1x":
        src = quantize8(mild_blur_noise(jpeg_roundtrip(src, rng, (35, 88)), rng))
    elif category.startswith("sr_"):
        if rng.random() < 0.25:
            src = jpeg_roundtrip(src, rng, (58, 94))
        if rng.random() < 0.35:
            src = quantize8(mild_blur_noise(src, rng))
    return src.clamp_(0.0, 1.0), target


@torch.inference_mode()
def quick_validation(model: ContinuousLite, rows: list[dict], cache: PageCache,
                     device: torch.device, seed: int, crop: int = 160) -> dict:
    rng = random.Random(seed)
    scale_stats: dict[str, list[tuple[float, float]]] = {
        f"{ratio:.2f}x": [] for ratio in (1.25, 1.37, 1.5, 1.75, 2.0)
    }
    clean_abs: list[torch.Tensor] = []
    clean_mses: list[float] = []
    degraded_stats: list[tuple[float, float]] = []
    for row in rows:
        for _ in range(1):
            gt = cache.crop(row, crop, rng).unsqueeze(0).to(device)
            for ratio in (1.25, 1.37, 1.5, 1.75, 2.0):
                n = round(crop / ratio)
                x = quantize8(F.interpolate(gt, size=(n, n), mode="bicubic", align_corners=False))
                pred = model(x, (crop, crop), strength=1.0, residual_cap=False)
                base = F.interpolate(x, size=(crop, crop), mode="bicubic", align_corners=False).clamp(0.0, 1.0)
                scale_stats[f"{ratio:.2f}x"].append((F.mse_loss(pred, gt).item(), F.mse_loss(base, gt).item()))

            clean_src = quantize8(gt)
            clean_pred = model(clean_src, (crop, crop), strength=1.0, residual_cap=False)
            clean_mses.append(F.mse_loss(clean_pred, gt).item())
            clean_abs.append((clean_pred - gt).abs().flatten().cpu())

            degraded = jpeg_roundtrip(gt, rng, (35, 88))
            blurred = F.avg_pool2d(F.pad(degraded, (1, 1, 1, 1), mode="replicate"), 3, stride=1)
            degraded = quantize8(degraded * 0.88 + blurred * 0.12)
            degraded_pred = model(degraded, (crop, crop), strength=1.0, residual_cap=False)
            degraded_base = F.interpolate(degraded, size=(crop, crop), mode="bicubic", align_corners=False).clamp(0.0, 1.0)
            degraded_stats.append((F.mse_loss(degraded_pred, gt).item(), F.mse_loss(degraded_base, gt).item()))

    mean_clean_mse = float(np.mean(clean_mses))
    clean_changes = torch.cat(clean_abs)
    result: dict[str, float | None] = {
        "clean1x_mse": round(mean_clean_mse, 10),
        "clean1x_psnr_db": None if mean_clean_mse <= 1e-15 else round(psnr_from_mse(mean_clean_mse), 4),
        "clean1x_p95_change_gray": round(float(torch.quantile(clean_changes, 0.95).item() * 255.0), 4),
        "clean1x_max_change_gray": round(float(clean_changes.max().item() * 255.0), 4),
    }
    for name, pairs in scale_stats.items():
        deltas = [10.0 * math.log10(max(base, 1e-15) / max(pred, 1e-15)) for pred, base in pairs]
        result[f"{name}_delta_db"] = round(float(np.mean(deltas)), 4)
        result[f"{name}_model_mse"] = round(float(np.mean([pair[0] for pair in pairs])), 10)
        result[f"{name}_bicubic_mse"] = round(float(np.mean([pair[1] for pair in pairs])), 10)
    degraded_deltas = [10.0 * math.log10(max(base, 1e-15) / max(pred, 1e-15)) for pred, base in degraded_stats]
    result["degraded1x_delta_db"] = round(float(np.mean(degraded_deltas)), 4)
    result["degraded1x_model_mse"] = round(float(np.mean([pair[0] for pair in degraded_stats])), 10)
    result["degraded1x_bicubic_mse"] = round(float(np.mean([pair[1] for pair in degraded_stats])), 10)
    return result


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--minutes", type=float, default=18.0)
    ap.add_argument("--max-steps", type=int, default=30000)
    ap.add_argument("--batch-size", type=int, default=8)
    ap.add_argument("--crop", type=int, default=160)
    ap.add_argument("--seed", type=int, default=20261002)
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--val-every", type=int, default=1000)
    ap.add_argument("--checkpoint", default="runs/continuous-lite-best.pt")
    ap.add_argument("--log", default="runs/training-log.jsonl")
    args = ap.parse_args()
    if not torch.cuda.is_available():
        raise SystemExit("CUDA is required for the bounded full run")
    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)
    torch.cuda.manual_seed_all(args.seed)
    torch.set_num_threads(4)
    torch.backends.cudnn.benchmark = True
    torch.backends.cuda.matmul.allow_tf32 = True
    torch.backends.cudnn.allow_tf32 = True
    device = torch.device("cuda:0")
    manifest_path = MANIFEST
    train_rows = load_manifest_images(manifest_path, "train")
    val_rows = load_manifest_images(manifest_path, "validation")
    if not train_rows or not val_rows:
        raise SystemExit("dataset manifest missing train/validation pages; run fetch_peppercarrot.py")
    cache_train, cache_val = PageCache(train_rows, max_items=len(train_rows)), PageCache(val_rows, max_items=len(val_rows))
    model = ContinuousLite().to(device)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-6)
    checkpoint_path = (ROOT / args.checkpoint).resolve()
    checkpoint_path.parent.mkdir(parents=True, exist_ok=True)
    log_path = (ROOT / args.log).resolve()
    log_path.parent.mkdir(parents=True, exist_ok=True)
    start = time.time()
    deadline = start + max(0, args.minutes * 60)
    best_val = float("-inf")
    running_loss = 0.0
    steps = 0
    # Begin with a task that has a nonzero residual target, then retain exactly
    # one clean identity batch per four-step cycle.
    categories = ("degraded1x", "sr_low", "sr_high", "clean1x")
    category_counts = {name: 0 for name in categories}
    with log_path.open("a", encoding="utf-8") as log:
        log.write(json.dumps({"event": "start", "utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "args": vars(args),
                              "gpu": torch.cuda.get_device_name(0), "torch": torch.__version__,
                              "train_pages": len(train_rows), "validation_pages": len(val_rows),
                              "sampling": "one full batch per step; exact four-step category cycle; 25% clean1x"}, ensure_ascii=False) + "\n")
        log.flush()
        while steps < args.max_steps and time.time() < deadline:
            category = categories[steps % len(categories)]
            src, target = make_batch(cache_train, train_rows, args.batch_size, args.crop, device, random, category)
            opt.zero_grad(set_to_none=True)
            details = model(src, (args.crop, args.crop), strength=1.0, return_details=True, residual_cap=False)
            out = details["output"]
            # A 10px border is ignored to avoid teaching padding artifacts.
            margin = min(10, max(0, args.crop // 8))
            p = out[..., margin:args.crop - margin, margin:args.crop - margin]
            t = target[..., margin:args.crop - margin, margin:args.crop - margin]
            mse = (p - t).square().mean()
            l1 = (p - t).abs().mean()
            dxp, dyp = p[..., :, 1:] - p[..., :, :-1], p[..., 1:, :] - p[..., :-1, :]
            dxt, dyt = t[..., :, 1:] - t[..., :, :-1], t[..., 1:, :] - t[..., :-1, :]
            edge = (dxp - dxt).abs().mean() + (dyp - dyt).abs().mean()
            loss = 0.72 * mse + 0.25 * l1 + 0.03 * edge
            if category == "clean1x":
                loss = loss + 0.35 * details["residual"].square().mean()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
            opt.step()
            steps += 1
            category_counts[category] += 1
            running_loss += float(loss.detach())

            if steps <= 2 or steps % 250 == 0:
                avg = running_loss if steps == 1 else running_loss / (2 if steps == 2 else 250)
                if steps == 2 or steps % 250 == 0:
                    running_loss = 0.0
                line = {"event": "train", "step": steps, "elapsed_s": round(time.time() - start, 1),
                        "category": category, "avg_loss": round(avg, 7), "lr": opt.param_groups[0]["lr"]}
                print(json.dumps(line), flush=True)
                log.write(json.dumps(line) + "\n")
                log.flush()

            if steps % args.val_every == 0:
                # Fixed chapter/crop seed keeps checkpoints comparable across steps.
                val = quick_validation(model, val_rows, cache_val, device, args.seed)
                sr_scores = [val[f"{ratio:.2f}x_delta_db"] for ratio in (1.25, 1.37, 1.5, 1.75, 2.0)]
                score = 0.8 * float(np.mean(sr_scores)) + 0.2 * float(val["degraded1x_delta_db"])
                record = {"event": "validation", "step": steps, "elapsed_s": round(time.time() - start, 1),
                          "mean_sr_delta_db": round(float(np.mean(sr_scores)), 4), "selection_score_db": round(score, 4),
                          "category_counts": category_counts, "metrics": val}
                print(json.dumps(record), flush=True)
                log.write(json.dumps(record) + "\n")
                log.flush()
                if score > best_val:
                    best_val = score
                    torch.save({"state_dict": model.state_dict(), "step": steps, "seed": args.seed,
                                "validation": val, "contract": "manyue-lite-continuous-v1"}, checkpoint_path)

        # Always preserve the final state separately from the best heldout checkpoint.
        elapsed = time.time() - start
        final_path = ROOT / "runs" / "continuous-lite-final.pt"
        torch.save({"state_dict": model.state_dict(), "step": steps, "seed": args.seed,
                    "elapsed_s": elapsed, "contract": "manyue-lite-continuous-v1"}, final_path)
        end_record = {"event": "end", "steps": steps, "elapsed_s": round(elapsed, 1),
                      "best_val_selection_score_db": round(best_val, 4), "category_counts": category_counts,
                      "best_checkpoint": str(checkpoint_path),
                      "final_checkpoint": str(final_path)}
        print(json.dumps(end_record), flush=True)
        log.write(json.dumps(end_record) + "\n")
        log.flush()
    print(f"model params: {sum(p.numel() for p in model.parameters())}")


if __name__ == "__main__":
    main()
