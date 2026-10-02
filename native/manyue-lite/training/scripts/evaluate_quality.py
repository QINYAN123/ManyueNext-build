#!/usr/bin/env python3
"""Deterministic chapter-held-out Manyue-Lite quality evaluation."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
import sys
import time
from collections import defaultdict
from pathlib import Path

import numpy as np
from PIL import Image
import torch
from torch.nn import functional as F

from continuous_lite import ContinuousLite, load_manifest_images
from train import MANIFEST, PageCache, jpeg_roundtrip, quantize8


ROOT = Path(__file__).resolve().parents[1]
TRAINING_SCRIPT_DIR = Path(__file__).resolve().parent
SCALES = (1.25, 1.37, 1.5, 1.75, 2.0)
STRENGTHS = (100, 60)
TILE_SIZE = 384
CROP_SIZE = 512
CHANGE_HIST_BINS = 25501


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def positive_half_up(numerator: int, denominator: int) -> int:
    return (numerator + denominator // 2) // denominator


def change_histogram(values_gray: np.ndarray) -> np.ndarray:
    indices = np.floor(np.clip(values_gray, 0.0, 255.0) * 100.0).astype(np.int32)
    return np.bincount(np.minimum(indices, CHANGE_HIST_BINS - 1), minlength=CHANGE_HIST_BINS)


def histogram_percentile(histogram: np.ndarray, fraction: float) -> float:
    count = int(histogram.sum())
    if count == 0:
        return 0.0
    rank = max(1, math.ceil(count * fraction))
    return float(np.searchsorted(np.cumsum(histogram), rank, side="left") / 100.0)


def source_tensor(row: dict) -> torch.Tensor:
    with Image.open(row["absolute_path"]) as image:
        arr = np.asarray(image.convert("RGB"), dtype=np.uint8).copy()
    return torch.from_numpy(arr).permute(2, 0, 1).float().div_(255.0).unsqueeze(0)


def fixed_crops(image: torch.Tensor, size: int = CROP_SIZE) -> list[tuple[str, int, int, torch.Tensor]]:
    height, width = image.shape[-2:]
    crop_h, crop_w = min(size, height), min(size, width)
    positions = [
        ("top_left", 0, 0),
        ("top_right", width - crop_w, 0),
        ("bottom_left", 0, height - crop_h),
        ("bottom_right", width - crop_w, height - crop_h),
        ("center", (width - crop_w) // 2, (height - crop_h) // 2),
    ]
    unique = []
    seen = set()
    for name, x, y in positions:
        if (x, y) in seen:
            continue
        seen.add((x, y))
        unique.append((name, x, y, image[..., y:y + crop_h, x:x + crop_w]))
    return unique


def make_grid(input_h: int, input_w: int, output_h: int, output_w: int,
              x0: int, y0: int, x1: int, y1: int, device: torch.device) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
    dtype = torch.float32
    src_x = (torch.arange(x0, x1, device=device, dtype=dtype) + 0.5) * (input_w / output_w) - 0.5
    src_y = (torch.arange(y0, y1, device=device, dtype=dtype) + 0.5) * (input_h / output_h) - 0.5
    gx = (2.0 * (src_x + 0.5) / input_w - 1.0).view(1, 1, -1).expand(1, y1 - y0, -1)
    gy = (2.0 * (src_y + 0.5) / input_h - 1.0).view(1, -1, 1).expand(1, -1, x1 - x0)
    grid = torch.stack((gx, gy), dim=-1)
    return grid, src_x, src_y


@torch.inference_mode()
def render_tiled(model: ContinuousLite, source: torch.Tensor, output_h: int, output_w: int,
                 strength: float, features: torch.Tensor | None = None, tile_size: int = TILE_SIZE,
                 return_baseline: bool = False, residual_cap: bool = True,
                 residual_cap_slope: float = 0.02) -> torch.Tensor | tuple[torch.Tensor, torch.Tensor]:
    input_h, input_w = source.shape[-2:]
    if features is None:
        features = model.features(source)
    result = torch.empty((1, 3, output_h, output_w), device=source.device, dtype=source.dtype)
    baseline = torch.empty_like(result) if return_baseline else None
    sx = (output_w / input_w - 1.5) / 0.5
    sy = (output_h / input_h - 1.5) / 0.5
    for y0 in range(0, output_h, tile_size):
        y1 = min(output_h, y0 + tile_size)
        for x0 in range(0, output_w, tile_size):
            x1 = min(output_w, x0 + tile_size)
            grid, src_x, src_y = make_grid(input_h, input_w, output_h, output_w, x0, y0, x1, y1, source.device)
            f = F.grid_sample(features, grid, mode="bilinear", padding_mode="border", align_corners=False)
            base = F.grid_sample(source, grid, mode="bicubic", padding_mode="border", align_corners=False)
            phase_x = (src_x - torch.round(src_x)) * 2.0
            phase_y = (src_y - torch.round(src_y)) * 2.0
            cond = torch.empty((1, 4, y1 - y0, x1 - x0), device=source.device, dtype=source.dtype)
            cond[:, 0].fill_(sx)
            cond[:, 1].fill_(sy)
            cond[:, 2] = phase_x.view(1, 1, -1).expand(1, y1 - y0, -1)
            cond[:, 3] = phase_y.view(1, -1, 1).expand(1, -1, x1 - x0)
            residual = model.head_residual(F.relu(model.head_reduce(torch.cat((f, base, cond), dim=1))))
            if residual_cap:
                residual, _ = model.bound_residual(residual, input_w, output_w, residual_cap_slope)
            output = (base + residual * strength).clamp(0.0, 1.0)
            result[..., y0:y1, x0:x1] = output
            if baseline is not None:
                baseline[..., y0:y1, x0:x1] = base.clamp(0.0, 1.0)
    if baseline is None:
        return result
    return result, baseline


def error_record(pred: torch.Tensor, base: torch.Tensor, target: torch.Tensor,
                 *, ignore_border: int = 0, collect_pixel_change: bool = False) -> dict:
    if ignore_border > 0 and target.shape[-2] > 2 * ignore_border and target.shape[-1] > 2 * ignore_border:
        pred = pred[..., ignore_border:-ignore_border, ignore_border:-ignore_border]
        base = base[..., ignore_border:-ignore_border, ignore_border:-ignore_border]
        target = target[..., ignore_border:-ignore_border, ignore_border:-ignore_border]
    delta = pred.float() - target.float()
    base_delta = base.float() - target.float()
    model_mse = float(delta.square().mean().item())
    base_mse = float(base_delta.square().mean().item())
    improvement = 10.0 * math.log10(max(base_mse, 1e-15) / max(model_mse, 1e-15))
    abs_change_gray = delta.abs().flatten().cpu().numpy().astype(np.float32, copy=False) * 255.0
    model_base_delta = (pred.float() - base.float()).abs()
    mb_flat = model_base_delta.reshape(-1)
    mb_i = int(mb_flat.argmax().item())
    h, w = model_base_delta.shape[-2:]
    mb_pixel = mb_i % (h * w)
    mb_coord = {"x": mb_pixel % w, "y": mb_pixel // w, "channel": mb_i // (h * w)}
    target_i = int(delta.abs().reshape(-1).argmax().item())
    target_pixel = target_i % (h * w)
    target_coord = {"x": target_pixel % w, "y": target_pixel // w,
                    "channel": target_i // (h * w)}
    record = {
        "model_mse": model_mse,
        "bicubic_mse": base_mse,
        "delta_db": improvement,
        "elements": int(delta.numel()),
        "bias_by_channel_gray": [float(value * 255.0) for value in delta.mean(dim=(0, 2, 3)).tolist()],
        "abs_change_mean_gray": float(delta.abs().mean().item() * 255.0),
        "abs_change_p95_gray": histogram_percentile(change_histogram(abs_change_gray), 0.95),
        "abs_change_max_gray": float(delta.abs().max().item() * 255.0),
        "ai_vs_bicubic_max_gray": float(mb_flat.max().item() * 255.0),
        "ai_vs_bicubic_p99_gray": histogram_percentile(
            change_histogram(model_base_delta.flatten().cpu().numpy() * 255.0), 0.99),
        "ai_vs_bicubic_max_location": mb_coord,
        "target_error_max_location": target_coord,
        "max_location_coordinate_frame": "local output crop after ignore_border",
    }
    if collect_pixel_change:
        record["abs_change_flat_gray"] = abs_change_gray
    return record


def consistency_record(whole: torch.Tensor, tiled: torch.Tensor, sample_id: str) -> dict:
    difference_gray = (whole.float() - tiled.float()).abs().detach().flatten().cpu().numpy() * 255.0
    return {"sample_id": sample_id, "max_abs_gray": float(difference_gray.max()),
            "mean_abs_gray": float(difference_gray.mean()),
            "p99_abs_gray": histogram_percentile(change_histogram(difference_gray), 0.99),
            "max_abs_float": float(difference_gray.max() / 255.0), "tolerance_float": 0.001}


def summarize(records: list[dict], include_pixel_change: bool = False) -> dict:
    elements = sum(record["elements"] for record in records)
    model_mse = sum(record["model_mse"] * record["elements"] for record in records) / max(1, elements)
    base_mse = sum(record["bicubic_mse"] * record["elements"] for record in records) / max(1, elements)
    deltas = np.asarray([record["delta_db"] for record in records], dtype=np.float64)
    result = {
        "sample_count": len(records),
        "total_elements": elements,
        "model_mse": model_mse,
        "bicubic_mse": base_mse,
        "aggregate_delta_db": 10.0 * math.log10(max(base_mse, 1e-15) / max(model_mse, 1e-15)),
        "sample_delta_db": {
            "mean": float(np.mean(deltas)), "median": float(np.median(deltas)),
            "p10": float(np.quantile(deltas, 0.10)), "min": float(np.min(deltas)), "max": float(np.max(deltas)),
        },
        "improved_samples": int(np.sum(deltas > 0.0)),
        "worst_sample": min(records, key=lambda record: record["delta_db"])["sample_id"],
    }
    if include_pixel_change:
        result["max_abs_change_gray"] = max(record["abs_change_max_gray"] for record in records)
        result["max_abs_change_p95_per_page_gray"] = max(record["abs_change_p95_gray"] for record in records)
        result["max_abs_mean_channel_bias_gray"] = max(
            max(abs(value) for value in record["bias_by_channel_gray"]) for record in records
        )
    result["max_ai_vs_bicubic_gray"] = max(record["ai_vs_bicubic_max_gray"] for record in records)
    result["max_ai_vs_bicubic_p99_gray"] = max(record["ai_vs_bicubic_p99_gray"] for record in records)
    max_change_sample = max(records, key=lambda record: record["ai_vs_bicubic_max_gray"])
    result["max_ai_vs_bicubic_sample"] = {
        "sample_id": max_change_sample["sample_id"],
        "max_location": max_change_sample["ai_vs_bicubic_max_location"],
        "max_gray": max_change_sample["ai_vs_bicubic_max_gray"],
        "p99_gray": max_change_sample["ai_vs_bicubic_p99_gray"],
    }
    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", default="runs/continuous-lite-best.pt")
    parser.add_argument("--output", default="runs/quality-report-test.json")
    parser.add_argument("--split", choices=("validation", "test"), default="test")
    parser.add_argument("--no-residual-cap", action="store_true", help="diagnose the uncapped trained head")
    parser.add_argument("--residual-cap-slope", type=float, default=0.02)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    args.residual_cap = not args.no_residual_cap
    checkpoint = (ROOT / args.checkpoint).resolve()
    output_path = (ROOT / args.output).resolve()
    device = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
    random.seed(20261002)
    np.random.seed(20261002)
    torch.manual_seed(20261002)
    if device.type == "cuda":
        torch.cuda.manual_seed_all(20261002)
        torch.backends.cuda.matmul.allow_tf32 = False
        torch.backends.cudnn.allow_tf32 = False
    torch.set_num_threads(4)
    model = ContinuousLite().to(device).eval()
    state = torch.load(checkpoint, map_location="cpu", weights_only=False)
    model.load_state_dict(state["state_dict"], strict=True)
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    rows = load_manifest_images(MANIFEST, args.split)
    if not rows or (args.split == "test" and {row["episode"] for row in rows}.intersection({1, 2, 3, 4, 5, 6, 7, 8})):
        raise SystemExit(f"{args.split} split is empty or overlaps an earlier split")
    cache = PageCache(rows, max_items=len(rows))
    records: dict[tuple[str, int], list[dict]] = defaultdict(list)
    render_consistency: dict[str, dict] = {}
    clean_pixel_histograms: dict[int, np.ndarray] = {
        strength: np.zeros(CHANGE_HIST_BINS, dtype=np.int64) for strength in STRENGTHS
    }
    started = time.time()
    print(json.dumps({"event": "quality_start", "gpu": torch.cuda.get_device_name(0) if device.type == "cuda" else "CPU",
                      "checkpoint_sha256": sha256(checkpoint), "split": args.split, "pages": len(rows),
                      "episodes": sorted({row["episode"] for row in rows}), "residual_cap": args.residual_cap,
                      "residual_cap_slope": args.residual_cap_slope}), flush=True)

    for row in rows:
        page_id = f"E{row['episode']:02d}P{row['page']:02d}"
        target_page = source_tensor(row).to(device)
        page_features = model.features(target_page)
        for strength in STRENGTHS:
            pred, clean_bicubic = render_tiled(model, target_page, row["height"], row["width"], strength / 100.0,
                                features=page_features, return_baseline=True, residual_cap=args.residual_cap,
                                residual_cap_slope=args.residual_cap_slope)
            record = error_record(pred, clean_bicubic, target_page, collect_pixel_change=True)
            record.update({"sample_id": page_id, "episode": row["episode"], "page": row["page"], "strength": strength})
            records[("clean1x", strength)].append(record)
            clean_pixel_histograms[strength] += change_histogram(record.pop("abs_change_flat_gray"))
            if row is rows[0] and strength == 100:
                with torch.inference_mode():
                    whole = model(target_page, (row["height"], row["width"]), strength=1.0, return_details=True)
                    cap = 0.02 + args.residual_cap_slope * (row["width"] / row["width"] - 1.0)
                    direct = (whole["base"] + whole["residual"].clamp(-cap, cap)).clamp(0.0, 1.0)
                    render_consistency["clean1x_whole_vs_tiled"] = consistency_record(direct, pred, page_id)
        del page_features

        rng = random.Random(20261002 + row["episode"] * 100 + row["page"])
        for crop_name, crop_x, crop_y, target_crop in fixed_crops(target_page):
            for quality in (35, 60, 88):
                degraded = jpeg_roundtrip(target_crop, rng, (quality, quality))
                blurred = F.avg_pool2d(F.pad(degraded, (1, 1, 1, 1), mode="replicate"), 3, stride=1)
                degraded = quantize8(degraded * 0.88 + blurred * 0.12)
                degraded_features = model.features(degraded)
                out_h, out_w = degraded.shape[-2:]
                for strength in STRENGTHS:
                    pred, base = render_tiled(model, degraded, out_h, out_w, strength / 100.0,
                                              features=degraded_features, return_baseline=True, residual_cap=args.residual_cap,
                                              residual_cap_slope=args.residual_cap_slope)
                    record = error_record(pred, base, target_crop, ignore_border=8)
                    record.update({"sample_id": f"{page_id}:{crop_name}:jpeg{quality}", "episode": row["episode"],
                                   "page": row["page"], "crop": crop_name, "crop_x": crop_x, "crop_y": crop_y,
                                   "jpeg_quality": quality, "strength": strength})
                    records[("degraded1x", strength)].append(record)
                del degraded_features, degraded

            for scale in SCALES:
                target_h, target_w = target_crop.shape[-2:]
                input_w = max(32, math.floor(target_w / scale + 0.5))
                input_h = positive_half_up(target_h * input_w, target_w)
                source = quantize8(F.interpolate(target_crop, size=(input_h, input_w), mode="bicubic", align_corners=False))
                features = model.features(source)
                for strength in STRENGTHS:
                    pred, base = render_tiled(model, source, target_h, target_w, strength / 100.0,
                                              features=features, return_baseline=True, residual_cap=args.residual_cap,
                                              residual_cap_slope=args.residual_cap_slope)
                    record = error_record(pred, base, target_crop, ignore_border=8)
                    record.update({"sample_id": f"{page_id}:{crop_name}:{scale:.2f}x", "episode": row["episode"],
                                   "page": row["page"], "crop": crop_name, "crop_x": crop_x, "crop_y": crop_y,
                                   "requested_scale": scale, "actual_scale_x": target_w / input_w,
                                   "actual_scale_y": target_h / input_h, "source_width": input_w,
                                   "source_height": input_h, "target_width": target_w, "target_height": target_h,
                                   "strength": strength})
                    records[(f"{scale:.2f}x", strength)].append(record)
                    if row is rows[0] and crop_name == "bottom_right" and scale == 2.0 and strength == 100:
                        with torch.inference_mode():
                            whole = model(source, (target_h, target_w), strength=1.0, return_details=True)
                            cap = 0.02 + args.residual_cap_slope * (target_w / input_w - 1.0)
                            direct = (whole["base"] + whole["residual"].clamp(-cap, cap)).clamp(0.0, 1.0)
                            render_consistency["2x_512_crop_whole_vs_tiled"] = consistency_record(
                                direct, pred, f"{page_id}:{crop_name}:{scale:.2f}x")
                del source, features
        del target_page
        if device.type == "cuda":
            torch.cuda.empty_cache()
        print(json.dumps({"event": "quality_page", "page": page_id, "elapsed_s": round(time.time() - started, 1)}), flush=True)

    grouped = {}
    for (kind, strength), sample_records in sorted(records.items()):
        grouped[f"{kind}_strength{strength}"] = summarize(sample_records, include_pixel_change=(kind == "clean1x"))
        for sample in sample_records:
            sample.pop("abs_change_flat_gray", None)
    clean_p95 = {}
    for strength, histogram in clean_pixel_histograms.items():
        clean_p95[str(strength)] = {
            "p95_abs_change_gray": histogram_percentile(histogram, 0.95),
            "max_abs_change_gray": max(record["abs_change_max_gray"] for record in records[("clean1x", strength)]),
            "mean_abs_change_gray": sum(record["abs_change_mean_gray"] * record["elements"]
                                          for record in records[("clean1x", strength)]) / max(1, histogram.sum()),
        }

    gate_checks = {}
    for scale in SCALES:
        for strength in STRENGTHS:
            key = f"{scale:.2f}x_strength{strength}"
            gate_checks[f"{key}_non_regression"] = grouped[key]["aggregate_delta_db"] >= 0.0
    for strength in STRENGTHS:
        key = f"degraded1x_strength{strength}"
        gate_checks[f"{key}_improves"] = grouped[key]["aggregate_delta_db"] > 0.0
        gate_checks[f"clean1x_strength{strength}_bias_below_0.5gray"] = grouped[f"clean1x_strength{strength}"]["max_abs_mean_channel_bias_gray"] < 0.5
        gate_checks[f"clean1x_strength{strength}_p95_at_most_3gray"] = clean_p95[str(strength)]["p95_abs_change_gray"] <= 3.0
    gate_checks["clean1x_strength100_max_change_at_most_6gray"] = clean_p95["100"]["max_abs_change_gray"] <= 6.0
    gate_checks["clean1x_strength60_max_change_at_most_4gray"] = clean_p95["60"]["max_abs_change_gray"] <= 4.0
    cap_violations = []
    for (kind, strength), samples in records.items():
        for sample in samples:
            actual_scale = sample.get("actual_scale_x", 1.0)
            limit_gray = (0.02 + args.residual_cap_slope * (actual_scale - 1.0)) * (strength / 100.0) * 255.0
            if sample["ai_vs_bicubic_max_gray"] > limit_gray + 0.03:
                cap_violations.append({"sample_id": sample["sample_id"], "strength": strength,
                                       "observed_gray": sample["ai_vs_bicubic_max_gray"], "limit_gray": limit_gray})
    gate_checks["all_samples_respect_scale_aware_residual_cap"] = not cap_violations
    gate_checks["whole_vs_tiled_consistency_below_0.001_float"] = bool(render_consistency) and all(
        item["max_abs_float"] <= item["tolerance_float"] for item in render_consistency.values())
    report = {
        "schema_version": 1,
        "contract": "manyue-lite-continuous-v1",
        "checkpoint": str(checkpoint),
        "checkpoint_sha256": sha256(checkpoint),
        "training_manifest_sha256": sha256(MANIFEST),
        "license": {"training_source": "Pepper&Carrot official English compiled pages by David Revoy, CC BY 4.0",
                    "training_license_url": manifest["license_url"], "attribution": "David Revoy; episode-specific source pages and credits are listed in data/peppercarrot/manifest.json",
                    "original_training_pages_packaged": False},
        "test_split": {"name": args.split, "whole_chapter_episodes": sorted({row["episode"] for row in rows}),
                       "page_count": len(rows), "split_policy": manifest["split_policy"]},
        "training_configuration": state.get("training_args"),
        "strengths_percent": list(STRENGTHS),
        "metrics": grouped,
        "render_consistency": render_consistency,
        "clean1x_pixel_change": clean_p95,
        "samples": {key: [dict((k, v) for k, v in sample.items() if k != "abs_change_flat_gray") for sample in samples]
                    for key, samples in ((f"{kind}_strength{strength}", items) for (kind, strength), items in records.items())},
        "quality_gate": {"passed": all(gate_checks.values()), "checks": gate_checks,
                         "criteria": "all requested scales/strengths have nonnegative aggregate delta over bicubic; JPEG35/60/88 plus light-blur 1x improves; clean1x bias <0.5 gray, p95 <=3 gray, max <=6 gray at strength100 and <=4 gray at strength60; per-sample AI-vs-bicubic maximum respects scale-aware raw residual cap"},
        "elapsed_s": round(time.time() - started, 1),
        "tile_size": TILE_SIZE,
        "scale_aware_residual_cap": {"enabled": args.residual_cap, "slope": args.residual_cap_slope,
                                     "formula": "limit=0.02+slope*(actual_scale_x-1); clamp raw residual to [-limit,+limit] before strength"},
        "input_quantization": "clamp to [0,1], round to nearest 8-bit RGB, then divide by 255",
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"event": "quality_end", "quality_gate": report["quality_gate"], "output": str(output_path),
                      "elapsed_s": report["elapsed_s"]}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
