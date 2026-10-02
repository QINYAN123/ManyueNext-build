"""Tiny variable-resolution CNN and its fixed Manyue-Lite tensor contract."""
from __future__ import annotations

from pathlib import Path
import json
import math

import torch
from torch import nn
from torch.nn import functional as F


CHANNELS = 16
HIDDEN = 32
CONTRACT_VERSION = "manyue-lite-continuous-v1"
RESIDUAL_CAP_INTERCEPT = 0.02
RESIDUAL_CAP_SLOPE = 0.02


class ContinuousLite(nn.Module):
    """3x3 low-res trunk and 1x1 implicit render head.

    Inputs and outputs are RGB NCHW float32/float in [0,1]. The returned raw
    residual is un-clipped; callers apply clamp(base + residual * strength).
    Feature interpolation and bicubic base use align_corners=False. PyTorch's
    bicubic implementation uses a=-0.75 and border extension at the edge.
    """

    def __init__(self) -> None:
        super().__init__()
        self.trunk = nn.ModuleList([
            nn.Conv2d(3, CHANNELS, 3, padding=1, bias=True),
            nn.Conv2d(CHANNELS, CHANNELS, 3, padding=1, bias=True),
            nn.Conv2d(CHANNELS, CHANNELS, 3, padding=1, bias=True),
        ])
        self.head_reduce = nn.Conv2d(23, HIDDEN, 1, bias=True)
        self.head_residual = nn.Conv2d(HIDDEN, 3, 1, bias=True)
        self.reset_identity()

    def reset_identity(self) -> None:
        """Initialize a trainable trunk while the zero head starts at bicubic."""
        with torch.no_grad():
            for layer in self.trunk:
                # Random positive features keep channels 3..15 and every trunk
                # stage active through Hardtanh. The final head is zero, so these
                # features cannot change the exact bicubic identity at startup.
                nn.init.kaiming_normal_(layer.weight, mode="fan_in", nonlinearity="relu")
                layer.bias.fill_(0.08)
            nn.init.kaiming_normal_(self.head_reduce.weight, mode="fan_in", nonlinearity="relu")
            self.head_reduce.bias.fill_(0.08)
            self.head_residual.weight.zero_()
            self.head_residual.bias.zero_()

    @staticmethod
    def _conditions(src: torch.Tensor, out_h: int, out_w: int) -> tuple[torch.Tensor, torch.Tensor]:
        b, _, in_h, in_w = src.shape
        dev, dtype = src.device, src.dtype
        ratio_x, ratio_y = out_w / in_w, out_h / in_h
        sx = torch.full((b, 1, out_h, out_w), (ratio_x - 1.5) / 0.5, device=dev, dtype=dtype)
        sy = torch.full((b, 1, out_h, out_w), (ratio_y - 1.5) / 0.5, device=dev, dtype=dtype)
        x = (torch.arange(out_w, device=dev, dtype=dtype) + 0.5) * (in_w / out_w) - 0.5
        y = (torch.arange(out_h, device=dev, dtype=dtype) + 0.5) * (in_h / out_h) - 0.5
        phase_x = (x - torch.round(x)) * 2.0  # torch.round uses tie-to-even.
        phase_y = (y - torch.round(y)) * 2.0
        px = phase_x.view(1, 1, 1, out_w).expand(b, 1, out_h, out_w)
        py = phase_y.view(1, 1, out_h, 1).expand(b, 1, out_h, out_w)
        return torch.cat([sx, sy, px, py], dim=1), (phase_y, phase_x)

    def features(self, src: torch.Tensor) -> torch.Tensor:
        x = src
        for conv in self.trunk:
            x = F.hardtanh(conv(x), min_val=0.0, max_val=1.0)
        return x

    def render_from_features(
        self,
        features: torch.Tensor,
        base: torch.Tensor,
        cond: torch.Tensor,
        strength: float | torch.Tensor = 1.0,
        *,
        residual_cap: bool = True,
        residual_limit: float | None = None,
    ) -> tuple[torch.Tensor, torch.Tensor]:
        head_input = torch.cat([features, base, cond], dim=1)
        raw_residual = self.head_residual(F.relu(self.head_reduce(head_input)))
        applied_residual = raw_residual
        if residual_cap:
            if residual_limit is None:
                raise ValueError("residual_limit is required when residual_cap is enabled")
            applied_residual = raw_residual.clamp(-residual_limit, residual_limit)
        output = torch.clamp(base + applied_residual * strength, 0.0, 1.0)
        return output, raw_residual

    @staticmethod
    def residual_limit(input_width: int, output_width: int, slope: float = RESIDUAL_CAP_SLOPE) -> float:
        return RESIDUAL_CAP_INTERCEPT + slope * (output_width / input_width - 1.0)

    @staticmethod
    def bound_residual(raw_residual: torch.Tensor, input_width: int, output_width: int,
                       slope: float = RESIDUAL_CAP_SLOPE) -> tuple[torch.Tensor, float]:
        limit = ContinuousLite.residual_limit(input_width, output_width, slope)
        return raw_residual.clamp(-limit, limit), limit

    def forward(
        self,
        src: torch.Tensor,
        output_size: tuple[int, int] | None = None,
        strength: float | torch.Tensor = 1.0,
        *,
        return_details: bool = False,
        residual_cap: bool = True,
        residual_cap_slope: float = RESIDUAL_CAP_SLOPE,
    ):
        if src.ndim != 4 or src.shape[1] != 3:
            raise ValueError(f"expected RGB NCHW input, got {tuple(src.shape)}")
        out_h, out_w = output_size or (src.shape[-2], src.shape[-1])
        in_h, in_w = src.shape[-2:]
        f = self.features(src)
        src_x = (torch.arange(out_w, device=src.device, dtype=torch.float32) + 0.5) * (in_w / out_w) - 0.5
        src_y = (torch.arange(out_h, device=src.device, dtype=torch.float32) + 0.5) * (in_h / out_h) - 0.5
        gx = (2.0 * (src_x + 0.5) / in_w - 1.0).view(1, 1, -1).expand(src.shape[0], out_h, out_w)
        gy = (2.0 * (src_y + 0.5) / in_h - 1.0).view(1, -1, 1).expand(src.shape[0], out_h, out_w)
        grid = torch.stack((gx, gy), dim=-1)
        f_up = F.grid_sample(f, grid, mode="bilinear", padding_mode="border", align_corners=False)
        base = F.grid_sample(src, grid, mode="bicubic", padding_mode="border", align_corners=False)
        cond, phase = self._conditions(src, out_h, out_w)
        residual_limit = self.residual_limit(src.shape[-1], out_w, residual_cap_slope) if residual_cap else None
        output, raw_residual = self.render_from_features(f_up, base, cond, strength,
                                                          residual_cap=residual_cap, residual_limit=residual_limit)
        if return_details:
            applied_residual = raw_residual.clamp(-residual_limit, residual_limit) if residual_cap else raw_residual
            return {"output": output, "residual": raw_residual, "base": base,
                    "applied_residual": applied_residual, "residual_limit": residual_limit,
                    "features": f, "features_up": f_up, "head_input": torch.cat([f_up, base, cond], dim=1),
                    "phase_yx": phase}
        return output


def load_manifest_images(manifest_path: Path, split: str) -> list[dict]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    root = manifest_path.parents[2]
    rows = [r for r in manifest["images"] if r["split"] == split]
    for row in rows:
        row["absolute_path"] = str(root / Path(row["path"]))
    return rows


def psnr_from_mse(mse: float) -> float:
    if mse <= 0:
        return float("inf")
    return -10.0 * math.log10(mse)
