#!/usr/bin/env python3
"""Inspect one fixed SR evaluation crop, including capped direct/tiled parity."""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
import torch
from torch.nn import functional as F

from continuous_lite import ContinuousLite, load_manifest_images
from evaluate_quality import fixed_crops, make_grid, render_tiled, source_tensor
from train import MANIFEST, quantize8

ROOT = Path(__file__).resolve().parents[1]


def save_rgb(path: Path, tensor: torch.Tensor, scale: int = 1) -> None:
    arr = tensor.detach().float().clamp(0, 1)[0].permute(1, 2, 0).cpu().numpy()
    image = Image.fromarray(np.rint(arr * 255).astype(np.uint8), "RGB")
    if scale != 1:
        image = image.resize((image.width * scale, image.height * scale), Image.Resampling.NEAREST)
    image.save(path)


def error_summary(pred: torch.Tensor, target: torch.Tensor, base: torch.Tensor | None = None) -> dict:
    d = pred.float() - target.float()
    flat = d.abs().reshape(-1)
    n = min(12, flat.numel())
    vals, inds = torch.topk(flat, n)
    h, w = d.shape[-2:]
    top = []
    for v, i in zip(vals.cpu().tolist(), inds.cpu().tolist()):
        c, y, x = i // (h * w), (i // w) % h, i % w
        top.append({"x": x, "y": y, "channel": c, "abs_gray": v * 255,
                    "target_rgb": target[0, :, y, x].cpu().tolist(),
                    "pred_rgb": pred[0, :, y, x].cpu().tolist()})
    mse = float(d.square().mean().item())
    out = {"mse": mse, "psnr": -10 * math.log10(max(mse, 1e-15)),
            "max_abs_gray": float(flat.max().item() * 255), "mean_abs_gray": float(d.abs().mean().item() * 255),
            "p95_abs_gray": float(torch.quantile(flat, 0.95).item() * 255), "top_pixels": top}
    if base is not None:
        dbase = (pred.float() - base.float()).abs()
        flatbase = dbase.reshape(-1)
        bi = int(flatbase.argmax().item())
        bh, bw = dbase.shape[-2:]
        bp = bi % (bh * bw)
        out["max_abs_vs_bicubic_gray"] = float(flatbase.max().item() * 255)
        out["p99_abs_vs_bicubic_gray"] = float(torch.quantile(flatbase, 0.99).item() * 255)
        out["max_vs_bicubic_location_xy_channel"] = [bp % bw, bp // bw, bi // (bh * bw)]
    return out


def difference_summary(a: torch.Tensor, b: torch.Tensor) -> dict:
    difference = (a.float() - b.float()).abs()
    flat = difference.reshape(-1)
    index = int(flat.argmax().item())
    h, w = difference.shape[-2:]
    pixel = index % (h * w)
    return {"max_abs": float(flat.max().item()), "mean_abs": float(flat.mean().item()),
            "max_location_xyc": [pixel % w, pixel // w, index // (h * w)]}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--checkpoint", default="runs/continuous-lite-best.pt")
    ap.add_argument("--report", default="runs/quality-report-validation-capped.json")
    ap.add_argument("--sample-id", default="E07P02:bottom_right:1.25x")
    ap.add_argument("--output", default="runs/diagnostics/sr-outliers")
    ap.add_argument("--residual-cap-slope", type=float, default=0.08)
    args = ap.parse_args()
    torch.set_num_threads(3)
    torch.backends.cuda.matmul.allow_tf32 = False
    torch.backends.cudnn.allow_tf32 = False
    device = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
    state = torch.load((ROOT / args.checkpoint).resolve(), map_location="cpu", weights_only=False)
    model = ContinuousLite().to(device).eval()
    model.load_state_dict(state["state_dict"], strict=True)
    report = json.loads((ROOT / args.report).resolve().read_text(encoding="utf-8"))
    sample = next(s for values in report["samples"].values() for s in values if s.get("sample_id") == args.sample_id)
    row = next(r for r in load_manifest_images(MANIFEST, report["test_split"]["name"])
               if r["episode"] == sample["episode"] and r["page"] == sample["page"])
    page = source_tensor(row).to(device)
    target_h = target_w = 512
    crops = fixed_crops(page)
    crop_name = sample["crop"]
    _, crop_x, crop_y, target = next(item for item in crops if item[0] == crop_name)
    target_h, target_w = target.shape[-2:]
    scale = float(sample["requested_scale"])
    input_w = max(32, math.floor(target_w / scale + 0.5))
    input_h = (target_h * input_w + target_w // 2) // target_w
    source = quantize8(F.interpolate(target, size=(input_h, input_w), mode="bicubic", align_corners=False))
    outdir = (ROOT / args.output).resolve()
    outdir.mkdir(parents=True, exist_ok=True)
    with torch.inference_mode():
        details = model(source, (target_h, target_w), strength=1.0, return_details=True)
        limit = 0.02 + args.residual_cap_slope * (target_w / input_w - 1.0)
        raw = details["residual"]
        bounded = raw.clamp(-limit, limit)
        base_raw = details["base"]
        base = base_raw.clamp(0, 1)
        whole100 = (base_raw + bounded).clamp(0, 1)
        whole60 = (base_raw + bounded * 0.6).clamp(0, 1)
        tiled100, tiled_base = render_tiled(model, source, target_h, target_w, 1.0, return_baseline=True,
                                             residual_cap=True, residual_cap_slope=args.residual_cap_slope)
        tiled60 = render_tiled(model, source, target_h, target_w, 0.6, residual_cap=True,
                                residual_cap_slope=args.residual_cap_slope)
        # Reconstruct both the previous F.interpolate whole-image route and
        # the exact global-grid route to locate whole-vs-tile numeric drift.
        features = model.features(source)
        grid, src_x, src_y = make_grid(input_h, input_w, target_h, target_w, 0, 0, target_w, target_h, device)
        grid_features = F.grid_sample(features, grid, mode="bilinear", padding_mode="border", align_corners=False)
        grid_base = F.grid_sample(source, grid, mode="bicubic", padding_mode="border", align_corners=False)
        legacy_features = F.interpolate(features, size=(target_h, target_w), mode="bilinear", align_corners=False)
        legacy_base = F.interpolate(source, size=(target_h, target_w), mode="bicubic", align_corners=False)
        conditions, direct_phase = model._conditions(source, target_h, target_w)
        phase_x = (src_x - torch.round(src_x)) * 2.0
        phase_y = (src_y - torch.round(src_y)) * 2.0
        cond = torch.empty_like(conditions)
        cond[:, 0].fill_((target_w / input_w - 1.5) / 0.5)
        cond[:, 1].fill_((target_h / input_h - 1.5) / 0.5)
        cond[:, 2] = phase_x.view(1, 1, -1).expand(1, target_h, target_w)
        cond[:, 3] = phase_y.view(1, -1, 1).expand(1, target_h, target_w)
        limit_for_crop = 0.02 + args.residual_cap_slope * (target_w / input_w - 1.0)
        legacy_out, legacy_raw = model.render_from_features(legacy_features, legacy_base, conditions, 1.0,
                                                            residual_cap=True, residual_limit=limit_for_crop)
        grid_out, grid_raw = model.render_from_features(grid_features, grid_base, cond, 1.0,
                                                         residual_cap=True, residual_limit=limit_for_crop)
        sampling_breakdown = {
            "legacy_f_interpolate_vs_global_grid": difference_summary(legacy_out, grid_out),
            "feature_up": difference_summary(legacy_features, grid_features),
            "bicubic_base": difference_summary(legacy_base, grid_base),
            "phase_condition": difference_summary(conditions, cond),
            "raw_residual": difference_summary(legacy_raw, grid_raw),
            "direct_grid_vs_tiled": difference_summary(grid_out, tiled100),
            "direct_forward_vs_tiled": difference_summary(whole100, tiled100),
            "phase_exact": bool(torch.equal(direct_phase[0], phase_y) and torch.equal(direct_phase[1], phase_x)),
        }
        report_record = sample
        result = {
            "sample_id": args.sample_id, "split": report["test_split"]["name"], "source_page_path": row["path"],
            "crop_xy": [crop_x, crop_y], "target_hw": [target_h, target_w], "input_hw": [input_h, input_w],
            "requested_scale": scale, "actual_scale_xy": [target_w / input_w, target_h / input_h],
            "residual_limit": limit, "residual_cap_slope": args.residual_cap_slope, "report_record": report_record,
            "whole_strength100": error_summary(whole100, target, base), "whole_strength60": error_summary(whole60, target, base),
            "bicubic": error_summary(base, target),
            "whole_vs_tiled_max_abs_float": float((whole100 - tiled100).abs().max().item()),
            "tiled_base_vs_direct_max_abs_float": float((base - tiled_base).abs().max().item()),
            "sampling_breakdown": sampling_breakdown,
            "raw_residual_max_abs_gray": float(raw.abs().max().item() * 255),
            "bounded_residual_max_abs_gray": float(bounded.abs().max().item() * 255),
        }
        files = {}
        for name, tensor in (("low_input", source), ("target", target), ("bicubic", base),
                             ("model_strength100", whole100), ("model_strength60", whole60),
                             ("tiled_strength100", tiled100)):
            path = outdir / f"{args.sample_id.replace(':', '-')}-{name}.png"
            save_rgb(path, tensor, scale=2)
            files[path.name] = str(path)
        abs_err = (whole60 - target).abs().max(dim=1, keepdim=True).values[0, 0]
        heat = np.rint((abs_err.cpu().numpy() * 2.5).clip(0, 1) * 255).astype(np.uint8)
        heat_path = outdir / f"{args.sample_id.replace(':', '-')}-strength60-error-x2_5.png"
        Image.fromarray(heat, "L").resize((target_w * 2, target_h * 2), Image.Resampling.NEAREST).save(heat_path)
        files[heat_path.name] = str(heat_path)
        montage = Image.new("RGB", (target_w * 4, target_h * 2 + 32), "white")
        draw = ImageDraw.Draw(montage)
        for i, label in enumerate(("target", "bicubic", "model 60%", "abs error x2.5")):
            draw.text((i * target_w + 4, 4), label, fill="black")
        images = [Image.open(files[next(name for name in files if name.endswith(f"-{suffix}.png"))]).convert("RGB")
                  for suffix in ("target", "bicubic", "model_strength60", "strength60-error-x2_5")]
        for i, image in enumerate(images):
            montage.paste(image, (i * target_w, 32))
        montage_path = outdir / f"{args.sample_id.replace(':', '-')}-montage.png"
        montage.save(montage_path)
        files[montage_path.name] = str(montage_path)
        result["visual_files"] = files
    json_path = outdir / f"{args.sample_id.replace(':', '-')}-diagnosis.json"
    json_path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: result[k] for k in ("sample_id", "input_hw", "target_hw", "actual_scale_xy", "residual_limit",
                                               "whole_strength100", "whole_strength60", "bicubic",
                                               "whole_vs_tiled_max_abs_float", "tiled_base_vs_direct_max_abs_float",
                                               "raw_residual_max_abs_gray", "bounded_residual_max_abs_gray", "visual_files")}, indent=2))


if __name__ == "__main__":
    main()
