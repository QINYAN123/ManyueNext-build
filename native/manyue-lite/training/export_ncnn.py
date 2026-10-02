#!/usr/bin/env python3
"""Export Manyue-Lite FP32 NCNN graphs and the fused 867-float GPU head."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
import struct

import numpy as np
import torch


HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / "scripts"))
if not (HERE / "scripts" / "continuous_lite.py").is_file():
    # Backward-compatible development fallback; archived training is self-contained.
    sys.path.insert(0, str(HERE.parents[3] / "light-sr-training" / "scripts"))
from continuous_lite import CONTRACT_VERSION, ContinuousLite  # noqa: E402


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def as_little_f32(tensor: torch.Tensor) -> np.ndarray:
    return tensor.detach().cpu().contiguous().numpy().astype("<f4", copy=False)


def write_conv_binary(path: Path, layers: list[torch.nn.Conv2d]) -> None:
    with path.open("wb") as stream:
        for layer in layers:
            # NCNN ModelBinFromStdio consumes one float32-tag word before each
            # convolution tensor; tag zero denotes raw FP32. head.f32 is the
            # separate fused shader buffer and deliberately has no tags.
            stream.write(struct.pack("<I", 0))
            as_little_f32(layer.weight).tofile(stream)
            if layer.bias is not None:
                as_little_f32(layer.bias).tofile(stream)


def trunk_param() -> str:
    return "\n".join((
        "7767517",
        "7 7",
        "Input input 0 1 in0",
        "Convolution trunk_conv1 1 1 in0 trunk_conv1_out 0=16 1=3 2=1 3=1 4=1 5=1 6=432 11=3 12=1 13=1 14=1 15=1 16=1 18=0.0",
        "Clip trunk_clip1 1 1 trunk_conv1_out trunk_clip1_out 0=0.0 1=1.0",
        "Convolution trunk_conv2 1 1 trunk_clip1_out trunk_conv2_out 0=16 1=3 2=1 3=1 4=1 5=1 6=2304 11=3 12=1 13=1 14=1 15=1 16=1 18=0.0",
        "Clip trunk_clip2 1 1 trunk_conv2_out trunk_clip2_out 0=0.0 1=1.0",
        "Convolution trunk_conv3 1 1 trunk_clip2_out trunk_conv3_out 0=16 1=3 2=1 3=1 4=1 5=1 6=2304 11=3 12=1 13=1 14=1 15=1 16=1 18=0.0",
        "Clip trunk_clip3 1 1 trunk_conv3_out out0 0=0.0 1=1.0",
        "",
    ))


def head_param() -> str:
    return "\n".join((
        "7767517",
        "4 4",
        "Input input 0 1 in0",
        "Convolution head_reduce 1 1 in0 head_reduce_out 0=32 1=1 2=1 3=1 4=0 5=1 6=736 11=1 12=1 13=1 14=0 15=0 16=0 18=0.0",
        "ReLU head_relu 1 1 head_reduce_out head_relu_out 0=0.0",
        "Convolution head_residual 1 1 head_relu_out out0 0=3 1=1 2=1 3=1 4=0 5=1 6=96 11=1 12=1 13=1 14=0 15=0 16=0 18=0.0",
        "",
    ))


def resolve_under(root: Path, value: str | Path) -> Path:
    path = Path(value)
    return (path if path.is_absolute() else root / path).resolve()


def training_config(checkpoint_state: dict, training_root: Path) -> dict | None:
    if "training_args" in checkpoint_state:
        return checkpoint_state["training_args"]
    log_path = training_root / "runs" / "training-final.jsonl"
    if not log_path.is_file():
        return None
    for line in log_path.read_text(encoding="utf-8").splitlines():
        record = json.loads(line)
        if record.get("event") == "start":
            return record.get("args")
    return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--training-root", default=str(HERE), help="self-contained training bundle root")
    parser.add_argument("--checkpoint", default="runs/continuous-lite-best.pt")
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--quality-report")
    parser.add_argument("--release", action="store_true", help="Require and embed a passing independent quality gate")
    args = parser.parse_args()
    training_root = Path(args.training_root).resolve()
    checkpoint = resolve_under(training_root, args.checkpoint)
    output_dir = resolve_under(training_root, args.output_dir)
    quality_report_path = resolve_under(training_root, args.quality_report) if args.quality_report else None
    quality_report = None
    if not checkpoint.is_file():
        raise SystemExit(f"checkpoint does not exist: {checkpoint}")
    if args.release:
        if quality_report_path is None or not quality_report_path.is_file():
            raise SystemExit("--release requires --quality-report from evaluate_quality.py")
        quality_report = json.loads(quality_report_path.read_text(encoding="utf-8"))
        if not quality_report.get("quality_gate", {}).get("passed", False):
            raise SystemExit("release export blocked: independent quality_gate.passed is false")
        if quality_report.get("checkpoint_sha256") != sha256(checkpoint):
            raise SystemExit("release export blocked: quality report checkpoint SHA does not match")
    output_dir.mkdir(parents=True, exist_ok=True)
    state = torch.load(checkpoint, map_location="cpu", weights_only=False)
    if args.release and state.get("step") is None:
        raise SystemExit("release export blocked: checkpoint is not a trained run")
    model = ContinuousLite().cpu().eval()
    model.load_state_dict(state["state_dict"], strict=True)

    trunk_layers = list(model.trunk)
    head_layers = [model.head_reduce, model.head_residual]
    (output_dir / "trunk.param").write_text(trunk_param(), encoding="ascii", newline="\n")
    (output_dir / "head.param").write_text(head_param(), encoding="ascii", newline="\n")
    write_conv_binary(output_dir / "trunk.bin", trunk_layers)
    write_conv_binary(output_dir / "head.bin", head_layers)
    fused_head = np.concatenate([as_little_f32(layer.weight).reshape(-1) for layer in head_layers[:1]] +
                                [as_little_f32(head_layers[0].bias).reshape(-1),
                                 as_little_f32(head_layers[1].weight).reshape(-1),
                                 as_little_f32(head_layers[1].bias).reshape(-1)])
    fused_head.astype("<f4", copy=False).tofile(output_dir / "head.f32")
    if (output_dir / "head.f32").stat().st_size != 867 * 4:
        raise AssertionError("head.f32 must contain exactly 867 little-endian FP32 values")

    files = {}
    for name in ("trunk.param", "trunk.bin", "head.param", "head.bin", "head.f32"):
        path = output_dir / name
        files[name] = {"bytes": path.stat().st_size, "sha256": sha256(path)}
    manifest_path = training_root / "data" / "peppercarrot" / "manifest.json"
    if not manifest_path.is_file():
        raise SystemExit(f"training data manifest does not exist: {manifest_path}")
    dataset = json.loads(manifest_path.read_text(encoding="utf-8"))
    if args.release and quality_report.get("training_manifest_sha256") != sha256(manifest_path):
        raise SystemExit("release export blocked: quality report data manifest SHA does not match")
    split_counts = {split: sum(item["split"] == split for item in dataset["images"])
                    for split in ("train", "validation", "test")}
    episode_splits = {split: sorted({item["episode"] for item in dataset["images"] if item["split"] == split})
                      for split in split_counts}
    is_trained_checkpoint = state.get("step") is not None
    if is_trained_checkpoint:
        training_info = {
            "checkpoint_sha256": sha256(checkpoint),
            "checkpoint_name": checkpoint.name,
            "checkpoint_step": state.get("step"),
            "checkpoint_embedded_repo_path": "native/manyue-lite/training/runs/continuous-lite-best.pt" if args.release else None,
            "checkpoint_contract": state.get("contract"),
            "configuration": training_config(state, training_root),
            "dataset_manifest_sha256": sha256(manifest_path),
            "dataset": dataset["dataset"],
            "dataset_creator": dataset["creator"],
            "dataset_license": dataset["license"],
            "dataset_license_url": dataset["license_url"],
            "dataset_attribution": "Pepper&Carrot official English compiled pages by David Revoy; episode-specific source pages and translation/proofreading credits are enumerated in data/peppercarrot/manifest.json",
            "chapter_split_policy": dataset["split_policy"],
            "pages_by_split": split_counts,
            "episodes_by_split": episode_splits,
            "original_training_pages_packaged": False,
        }
        training_data_license = "CC BY 4.0 with attribution; original pages are not included in this model package."
    else:
        training_info = {
            "checkpoint_sha256": sha256(checkpoint),
            "checkpoint_name": checkpoint.name,
            "checkpoint_contract": state.get("contract"),
            "weights_origin": state.get("purpose", "deterministic random contract weights"),
            "random_seed": state.get("seed"),
            "trained": False,
        }
        training_data_license = "Not applicable: deterministic synthetic contract fixture, not trained on comic pages."
    quality_summary = None
    if quality_report is not None:
        try:
            quality_report_name = quality_report_path.relative_to(training_root).as_posix()
        except ValueError:
            quality_report_name = quality_report_path.name
        quality_summary = {
            "path": quality_report_name,
            "sha256": sha256(quality_report_path),
            "quality_gate": quality_report["quality_gate"],
        }
    manifest = {
        "schema_version": 1,
        "model_id": "manyue-lite-continuous-v1",
        "contract": CONTRACT_VERSION,
        "purpose": "release" if args.release else "contract-or-development-export",
        "architecture": {
            "encoder": ["Conv3x3 3->16 + Hardtanh[0,1]", "Conv3x3 16->16 + Hardtanh[0,1]", "Conv3x3 16->16 + Hardtanh[0,1]"],
            "head": ["Conv1x1 23->32 + ReLU", "Conv1x1 32->3 raw residual"],
            "channels": 16,
            "hidden": 32,
            "parameters": sum(parameter.numel() for parameter in model.parameters()),
            "input_order": ["features16", "baseRGB3", "sx", "sy", "phaseX", "phaseY"],
            "halo_input_pixels": 4,
            "head_output": "unclamped RGB residual only; caller applies the scale-aware residual cap, adds base, applies strength/100, and clamps",
        },
        "sampling_contract": {
            "feature_resize": "bilinear half-pixel; align_corners=false",
            "base_resize": "bicubic a=-0.75 with source-edge extension",
            "source_coordinate": "FP32 ratio-first: ((float32(global_output)+0.5)*float32(input_size/output_size))-0.5; each operation rounded to FP32; no FMA",
            "condition_scale": "(actual_output_size / input_size - 1.5) / 0.5",
            "condition_phase": "2 * (source_coordinate - round_ties_to_even(source_coordinate)) in FP32",
            "target_dimensions": "target_width=positive-half-up(source_width*requested_scale); target_height=positive-half-up(source_height*target_width/source_width)",
            "residual_cap": "limit=0.02+0.02*(actual_output_width/input_width-1); clamp raw residual to [-limit,+limit] before applying strength",
            "final_output": "clamp(base + clamp(residual,-limit,+limit) * strength_percent/100, 0, 1)",
        },
        "runtime": {"ncnn_param_magic": 7767517, "input_blob": "in0", "output_blob": "out0",
                    "trunk_weight_layout": "float32 OIHW then bias per convolution",
                    "head_weight_layout": "float32 OIHW then bias per convolution",
                    "head_f32_layout": ["conv1 OIHW [32,23,1,1]", "conv1 bias [32]",
                                        "conv2 OIHW [3,32,1,1]", "conv2 bias [3]"],
                    "head_f32_values": 867},
        "training": training_info,
        "license": {
            "weights": "Apache-2.0",
            "weight_license_basis": "Project distribution choice matching the Apache-2.0 source repository; this is not an inference that CC BY 4.0 automatically determines weight licensing.",
            "training_data": training_data_license,
            "training_data_license_url": dataset["license_url"] if is_trained_checkpoint else None,
        },
        "quality_report": quality_summary,
        "files": files,
    }
    (output_dir / "model-manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"event": "ncnn_export", "output": str(output_dir), "checkpoint_sha256": manifest["training"]["checkpoint_sha256"],
                      "step": state.get("step"), "files": files}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
