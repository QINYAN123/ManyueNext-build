#!/usr/bin/env python3
"""Verify exact bicubic startup and gradient flow after the first optimizer step."""
from __future__ import annotations

import random

import torch
from torch.nn import functional as F

from continuous_lite import ContinuousLite, load_manifest_images
from train import MANIFEST, PageCache, make_batch


def main() -> None:
    if not torch.cuda.is_available():
        raise SystemExit("CUDA is required for the gradient smoke")
    seed = 20261002
    random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)
    torch.set_num_threads(4)
    device = torch.device("cuda:0")
    model = ContinuousLite().to(device)
    print("parameters", sum(p.numel() for p in model.parameters()))

    sample = torch.rand((1, 3, 49, 63), device=device)
    initial = model(sample, (71, 93), strength=1.0)
    baseline = F.interpolate(sample, size=(71, 93), mode="bicubic", align_corners=False)
    if not torch.equal(initial, baseline.clamp(0.0, 1.0)):
        raise AssertionError("zero residual head did not produce the exact clamped bicubic base")

    rows = load_manifest_images(MANIFEST, "train")
    cache = PageCache(rows, max_items=len(rows))
    optimizer = torch.optim.AdamW(model.parameters(), lr=3e-4, weight_decay=1e-6)
    for step, category in enumerate(("degraded1x", "sr_low"), start=1):
        src, target = make_batch(cache, rows, 4, 96, device, random, category)
        optimizer.zero_grad(set_to_none=True)
        out = model(src, (96, 96))
        loss = F.l1_loss(out[..., 8:-8, 8:-8], target[..., 8:-8, 8:-8])
        loss.backward()
        norms = {f"trunk{idx + 1}": float(layer.weight.grad.norm().item()) for idx, layer in enumerate(model.trunk)}
        output_gradient = float(model.head_residual.weight.grad.norm().item())
        if step == 1 and output_gradient <= 0.0:
            raise AssertionError("the first non-identity batch did not update the residual head")
        if step == 2:
            extra = {f"trunk{idx + 1}": float(layer.weight.grad[3:].norm().item()) for idx, layer in enumerate(model.trunk)}
            print({"step": step, "category": category, "loss": float(loss.item()), "output_head_gradient_norm": output_gradient,
                   "layer_gradient_norms": norms,
                   "extra_13_gradient_norms": extra})
            if any(value <= 0.0 for value in norms.values()):
                raise AssertionError(f"a trunk layer has no gradient: {norms}")
            if any(value <= 0.0 for value in extra.values()):
                raise AssertionError(f"an extra 13-channel trunk slice has no gradient: {extra}")
            changed_before = [layer.weight[3:].detach().clone() for layer in model.trunk]
        optimizer.step()
        if step == 2:
            updates = [float((layer.weight[3:] - before).abs().max().item())
                       for layer, before in zip(model.trunk, changed_before)]
            print({"extra_13_max_update": updates})
            if any(value <= 0.0 for value in updates):
                raise AssertionError(f"an extra 13-channel trunk slice did not update: {updates}")
    for image in cache.cache.values():
        image.close()
    print("GRADIENT_SMOKE_OK")


if __name__ == "__main__":
    main()
