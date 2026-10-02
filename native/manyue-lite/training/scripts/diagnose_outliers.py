#!/usr/bin/env python3
"""Compare trained whole-page and tiled renders around held-out outliers."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
import torch
from torch.nn import functional as F

from continuous_lite import ContinuousLite, load_manifest_images
from evaluate_quality import render_tiled, source_tensor
from train import MANIFEST


ROOT = Path(__file__).resolve().parents[1]


def save_rgb(path: Path, tensor: torch.Tensor) -> None:
    rgb = tensor.detach().float().clamp(0, 1)[0].permute(1, 2, 0).cpu().numpy()
    Image.fromarray(np.rint(rgb * 255).astype(np.uint8), "RGB").save(path)


def tensor_stats(a: torch.Tensor, b: torch.Tensor) -> dict:
    d = (a.float() - b.float()).abs()
    flat = d.reshape(-1)
    sample = flat[::max(1, flat.numel() // 1_000_000)]
    n = min(20, flat.numel())
    values, indices = torch.topk(flat, n)
    h, w = d.shape[-2:]
    pixels = []
    for value, index in zip(values.cpu().tolist(), indices.cpu().tolist()):
        c = index // (h * w)
        y = (index // w) % h
        x = index % w
        pixels.append({"x": x, "y": y, "channel": c, "abs": value})
    return {"max": float(flat.max().item()), "mean": float(flat.mean().item()),
            "sampled_p99_99": float(torch.quantile(sample, 0.9999).item()), "top": pixels}


def details_at(model: ContinuousLite, src: torch.Tensor, coords: list[dict]) -> list[dict]:
    result = []
    for item in coords:
        x, y, c = item["x"], item["y"], item["channel"]
        local = src[..., max(0, y - 8):y + 9, max(0, x - 8):x + 9]
        details = model(local, local.shape[-2:], strength=1.0, return_details=True)
        # The crop changes context at its borders; only report the original-page
        # raw values from a separate 1x1 lookup through the already rendered maps.
        result.append({"x": x, "y": y, "channel": c, "local_crop_shape": list(local.shape),
                       "local_raw_residual_center": float(details["residual"][0, c, min(8, y), min(8, x)].item())})
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", default="runs/continuous-lite-best.pt")
    parser.add_argument("--report", default="runs/quality-report-test.json")
    parser.add_argument("--output", default="runs/diagnostics/clean-outlier")
    parser.add_argument("--page", default="E09P02")
    parser.add_argument("--tile-size", type=int, default=384)
    parser.add_argument("--residual-cap-slope", type=float, default=0.02)
    parser.add_argument("--probe-x", type=int, default=723)
    parser.add_argument("--probe-y", type=int, default=1607)
    args = parser.parse_args()
    torch.set_num_threads(3)
    torch.backends.cuda.matmul.allow_tf32 = False
    torch.backends.cudnn.allow_tf32 = False
    device = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
    model = ContinuousLite().to(device).eval()
    state = torch.load((ROOT / args.checkpoint).resolve(), map_location="cpu", weights_only=False)
    model.load_state_dict(state["state_dict"], strict=True)
    report = json.loads((ROOT / args.report).resolve().read_text(encoding="utf-8"))
    rows = load_manifest_images(MANIFEST, "test")
    row = next(r for r in rows if f"E{r['episode']:02d}P{r['page']:02d}" == args.page)
    src = source_tensor(row).to(device)
    h, w = src.shape[-2:]
    outdir = (ROOT / args.output).resolve()
    outdir.mkdir(parents=True, exist_ok=True)
    with torch.inference_mode():
        details = model(src, (h, w), strength=1.0, return_details=True)
        residual_limit = 0.02 + args.residual_cap_slope * (w / w - 1.0)
        bounded_residual = details["residual"].clamp(-residual_limit, residual_limit)
        whole100 = (details["base"] + bounded_residual).clamp(0, 1)
        whole60 = (details["base"] + bounded_residual * 0.6).clamp(0, 1)
        tiled100, tiled_base = render_tiled(model, src, h, w, 1.0, tile_size=args.tile_size,
                                             return_baseline=True, residual_cap=True,
                                             residual_cap_slope=args.residual_cap_slope)
        tiled60 = render_tiled(model, src, h, w, 0.6, tile_size=args.tile_size, residual_cap=True,
                                residual_cap_slope=args.residual_cap_slope)
        same_size_bicubic = F.interpolate(src, size=(h, w), mode="bicubic", align_corners=False).clamp(0, 1)
        direct_residual = bounded_residual
        clean_err100 = (whole100 - details["base"]).abs() * 255
        flat = clean_err100.reshape(-1)
        count = min(20, flat.numel())
        vals, inds = torch.topk(flat, count)
        top = []
        for val, ind in zip(vals.cpu().tolist(), inds.cpu().tolist()):
            c, y, x = ind // (h * w), (ind // w) % h, ind % w
            top.append({"x": x, "y": y, "channel": c, "change_gray": val,
                        "source_rgb": [float(v) for v in src[0, :, y, x].cpu()],
                        "whole_rgb": [float(v) for v in whole100[0, :, y, x].cpu()],
                        "tiled_rgb": [float(v) for v in tiled100[0, :, y, x].cpu()],
                        "capped_residual_rgb": [float(v) for v in direct_residual[0, :, y, x].cpu()],
                        "raw_residual_rgb": [float(v) for v in details["residual"][0, :, y, x].cpu()],
                        "whole_residual_contribution_gray": [float(v * 255) for v in direct_residual[0, :, y, x].cpu()]})

        # For a matched 512-square crop centered on the worst pixel, compare
        # normal whole forward with 128 and 192 output tiles.
        worst = top[0]
        crop_side = min(512, h, w)
        crop_x = min(max(0, worst["x"] - crop_side // 2), w - crop_side)
        crop_y = min(max(0, worst["y"] - crop_side // 2), h - crop_side)
        crop = src[..., crop_y:crop_y + crop_side, crop_x:crop_x + crop_side]
        crop_details = model(crop, crop.shape[-2:], strength=1.0, return_details=True)
        crop_residual = crop_details["residual"].clamp(-residual_limit, residual_limit)
        crop_whole = (crop_details["base"] + crop_residual).clamp(0, 1)
        crop_tile128 = render_tiled(model, crop, crop_side, crop_side, 1.0, tile_size=128,
                                     residual_cap=True, residual_cap_slope=args.residual_cap_slope)
        crop_tile192 = render_tiled(model, crop, crop_side, crop_side, 1.0, tile_size=192,
                                     residual_cap=True, residual_cap_slope=args.residual_cap_slope)
        interior = (slice(None), slice(None), slice(8, -8), slice(8, -8))
        crop_compare = {
            "source_crop_origin_xy": [crop_x, crop_y], "crop_shape_hw": [crop_side, crop_side],
            "whole_vs_tile128_interior": tensor_stats(crop_whole[interior], crop_tile128[interior]),
            "whole_vs_tile192_interior": tensor_stats(crop_whole[interior], crop_tile192[interior]),
        }

        # Render visual evidence for the largest true model change.
        radius = 48
        x0, y0 = max(0, worst["x"] - radius), max(0, worst["y"] - radius)
        x1, y1 = min(w, worst["x"] + radius + 1), min(h, worst["y"] + radius + 1)
        visual_tensors = [src[..., y0:y1, x0:x1], whole100[..., y0:y1, x0:x1],
                          tiled100[..., y0:y1, x0:x1], whole60[..., y0:y1, x0:x1]]
        labels = ["source", "forward_strength100", "tiled_strength100", "forward_strength60"]
        previews = []
        for label, tensor in zip(labels, visual_tensors):
            path = outdir / f"{args.page}-{label}.png"
            save_rgb(path, tensor)
            previews.append(path)
        detail_radius = 32
        dx0, dy0 = max(0, worst["x"] - detail_radius), max(0, worst["y"] - detail_radius)
        dx1, dy1 = min(w, worst["x"] + detail_radius + 1), min(h, worst["y"] + detail_radius + 1)
        source_detail = visual_tensors[0][..., dy0 - y0:dy1 - y0, dx0 - x0:dx1 - x0]
        output_detail = visual_tensors[1][..., dy0 - y0:dy1 - y0, dx0 - x0:dx1 - x0]
        detail_delta = (output_detail - source_detail).abs().max(dim=1, keepdim=True).values
        detail_heat = (detail_delta[0, 0] * 3.0).clamp(0, 1).cpu().numpy()
        panels = []
        for tensor, mode in ((source_detail, "RGB"), (output_detail, "RGB"), (detail_heat, "L")):
            if mode == "RGB":
                arr = np.rint(tensor[0].permute(1, 2, 0).cpu().numpy().clip(0, 1) * 255).astype(np.uint8)
                image = Image.fromarray(arr, "RGB")
            else:
                image = Image.fromarray(np.rint(tensor * 255).astype(np.uint8), "L").convert("RGB")
            panels.append(image.resize((image.width * 8, image.height * 8), Image.Resampling.NEAREST))
        montage = Image.new("RGB", (sum(p.width for p in panels), max(p.height for p in panels) + 24), "white")
        draw = ImageDraw.Draw(montage)
        cursor = 0
        for title, panel in zip(("source x8", "model x8", "abs delta x3"), panels):
            draw.text((cursor + 4, 4), title, fill="black")
            montage.paste(panel, (cursor, 24))
            cursor += panel.width
        montage.save(outdir / f"{args.page}-max-pixel-detail.png")
        px, py = min(max(args.probe_x, 0), w - 1), min(max(args.probe_y, 0), h - 1)
        pr = 32
        qx0, qy0 = max(0, px - pr), max(0, py - pr)
        qx1, qy1 = min(w, px + pr + 1), min(h, py + pr + 1)
        probe_src = src[..., qy0:qy1, qx0:qx1]
        probe_out = whole100[..., qy0:qy1, qx0:qx1]
        probe_delta = (probe_out - probe_src).abs().max(dim=1, keepdim=True).values[0, 0]
        probe_heat = (probe_delta * 3.0).clamp(0, 1).cpu().numpy()
        probe_panels = []
        for tensor, mode in ((probe_src, "RGB"), (probe_out, "RGB"), (probe_heat, "L")):
            if mode == "RGB":
                arr = np.rint(tensor[0].permute(1, 2, 0).cpu().numpy().clip(0, 1) * 255).astype(np.uint8)
                panel = Image.fromarray(arr, "RGB")
            else:
                panel = Image.fromarray(np.rint(tensor * 255).astype(np.uint8), "L").convert("RGB")
            probe_panels.append(panel.resize((panel.width * 8, panel.height * 8), Image.Resampling.NEAREST))
        probe_montage = Image.new("RGB", (sum(p.width for p in probe_panels), max(p.height for p in probe_panels) + 24), "white")
        draw = ImageDraw.Draw(probe_montage)
        cursor = 0
        for title, panel in zip(("suspect source x8", "capped model x8", "abs delta x3"), probe_panels):
            draw.text((cursor + 4, 4), title, fill="black")
            probe_montage.paste(panel, (cursor, 24))
            cursor += panel.width
        probe_path = outdir / f"{args.page}-probe-{px}-{py}.png"
        probe_montage.save(probe_path)
        probe_record = {"x": px, "y": py,
                        "source_rgb": [float(v) for v in src[0, :, py, px].cpu()],
                        "capped_model_rgb_strength100": [float(v) for v in whole100[0, :, py, px].cpu()],
                        "capped_model_rgb_strength60": [float(v) for v in whole60[0, :, py, px].cpu()],
                        "raw_residual_rgb": [float(v) for v in details["residual"][0, :, py, px].cpu()],
                        "applied_residual_rgb": [float(v) for v in bounded_residual[0, :, py, px].cpu()],
                        "source_change_max_gray_strength100": float((whole100[0, :, py, px] - src[0, :, py, px]).abs().max().item() * 255),
                        "visual_file": probe_path.name}
        delta = (whole100[..., y0:y1, x0:x1] - src[..., y0:y1, x0:x1]).abs().max(dim=1, keepdim=True).values[0, 0]
        amp = (delta * 6.0).clamp(0, 1).cpu().numpy()
        Image.fromarray(np.rint(amp * 255).astype(np.uint8), "L").save(outdir / f"{args.page}-error-x6.png")

        summary = {
            "page": args.page, "path": row["path"], "device": str(device), "source_dimensions_wh": [w, h],
            "checkpoint_sha256": report["checkpoint_sha256"], "report_sample": next(s for s in report["samples"]["clean1x_strength100"] if s["sample_id"] == args.page),
            "residual_limit": residual_limit, "residual_cap_slope": args.residual_cap_slope,
            "whole_forward_vs_tiled_strength100": tensor_stats(whole100, tiled100),
            "whole_forward_vs_tiled_strength60": tensor_stats(whole60, tiled60),
            "same_size_bicubic_vs_quantized_source": tensor_stats(same_size_bicubic, src),
            "tiled_bicubic_vs_quantized_source": tensor_stats(tiled_base, src),
            "clean_change_top_pixels": top,
            "previous_uncapped_worst_pixel_probe": probe_record,
            "crop_comparison": crop_compare,
            "visual_files": [p.name for p in previews] + [f"{args.page}-error-x6.png", f"{args.page}-max-pixel-detail.png", probe_path.name],
        }
    (outdir / "diagnosis.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: summary[k] for k in ("page", "path", "whole_forward_vs_tiled_strength100", "whole_forward_vs_tiled_strength60", "same_size_bicubic_vs_quantized_source", "tiled_bicubic_vs_quantized_source", "clean_change_top_pixels", "crop_comparison", "visual_files")}, indent=2))


if __name__ == "__main__":
    main()
