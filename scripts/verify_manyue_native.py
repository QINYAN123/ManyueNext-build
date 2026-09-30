#!/usr/bin/env python3
"""Fail when the packaged Manyue native runner is missing, damaged, or the wrong ABI."""

from __future__ import annotations

import hashlib
import os
import re
import struct
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "app/src/main/jniLibs/arm64-v8a"
EXPECTED = {
    "libmanyue_realesr.so": "992055bb46ac445411d6ba83450b3bff346d6fa205c696fb85167188e2c4adf0",
    "libmanyue_realcugan.so": "cde254952ac15d0cce94bd3ea72299e1d4c22d7d3b797a1d67cbd12a430daef5",
    "libomp.so": "da75dcbe6026a3e08d01bfe86860159432051b329a84deb5ee042ce9b8e1a302",
    "libncnn.so": "87d150e735157b09aa20f26f5e57f72468c548e7ce98ce407ec50ee7e14a52dd",
}
RUNNERS = {
    "libmanyue_realesr.so",
    "libmanyue_realcugan.so",
}
MODELS = {
    "app/src/main/assets/ai/models-Real-ESRGANv3-anime/x2.bin": (
        1_247_368,
        "548a36f9c3f4ab8da56cd3b13badf23968bee207b396dad14d04b830e5f2ab2d",
    ),
    "app/src/main/assets/ai/models-Real-ESRGANv3-anime/x2.param": (
        3_173,
        "b88ff4f00ebf019a7fdac17fdd45a7fd3665d37509efc5baf2e4da2e24420a04",
    ),
    "app/src/main/assets/ai/models-Real-CUGAN-se/up2x-no-denoise.bin": (
        2_573_648,
        "7f135a712830b16678cb8247b9517308c5f3858fca9b10e1c3ad5ef6e261de0c",
    ),
    "app/src/main/assets/ai/models-Real-CUGAN-se/up2x-no-denoise.param": (
        4_818,
        "91efac7489bf249f092faa3764e3a3d1d31ef290051e39f2afd2138c98ccce30",
    ),
}


def verify(name: str, expected: str) -> None:
    path = NATIVE / name
    data = path.read_bytes()
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected:
        raise SystemExit(f"{name}: SHA-256 mismatch: {actual}")
    verify_arm64_elf(path, data, require_executable=name in RUNNERS)
    if name in RUNNERS and b"-w target-width" not in data:
        raise SystemExit(f"{name}: native target-width protocol is missing")
    print(f"{name}: OK ({len(data)} bytes, {actual})")


def verify_arm64_elf(
    path: Path,
    data: bytes | None = None,
    *,
    require_executable: bool = False,
) -> None:
    data = data if data is not None else path.read_bytes()
    if len(data) < 64:
        raise SystemExit(f"{path.name}: ELF file is truncated")
    if data[:4] != b"\x7fELF" or data[4] != 2:
        raise SystemExit(f"{path.name}: not an ELF64 file")
    machine = struct.unpack_from("<H", data, 18)[0]
    if machine != 183:  # EM_AARCH64
        raise SystemExit(f"{path.name}: expected AArch64, got ELF machine {machine}")
    section_offset = struct.unpack_from("<Q", data, 40)[0]
    section_entry_size, section_count = struct.unpack_from("<HH", data, 58)
    if section_offset + section_entry_size * section_count > len(data):
        raise SystemExit(f"{path.name}: section table extends beyond EOF")
    # NTFS does not expose Linux executable permissions; Git and APK installation preserve
    # the worker's mode separately. Keep enforcing the mode on Linux CI.
    if require_executable and os.name != "nt" and not path.stat().st_mode & 0o111:
        raise SystemExit(f"{path.name}: executable bit is missing")


if __name__ == "__main__":
    runtime = ROOT / "app/src/main/java/eu/kanade/tachiyomi/ui/reader/manyue/ManyueAiRuntime.kt"
    constants = dict(re.findall(r'const val (EXPECTED_\w+_SHA256) = "([0-9a-f]{64})"', runtime.read_text(encoding="utf-8")))
    for name, constant in {
        "libmanyue_realesr.so": "EXPECTED_REAL_ESRGAN_SHA256",
        "libmanyue_realcugan.so": "EXPECTED_REAL_CUGAN_SHA256",
        "libncnn.so": "EXPECTED_NCNN_SHA256",
        "libomp.so": "EXPECTED_LIBOMP_SHA256",
    }.items():
        if constants.get(constant) != EXPECTED[name]:
            raise SystemExit(f"{name}: runtime probe and packaged hash list disagree")
    for filename, digest in EXPECTED.items():
        verify(filename, digest)
    anime4k = NATIVE / "libmanyue_anime4k.so"
    verify_arm64_elf(anime4k, require_executable=True)
    digest = hashlib.sha256(anime4k.read_bytes()).hexdigest()
    print(f"{anime4k.name}: OK ({anime4k.stat().st_size} bytes, ARM64, SHA-256 {digest})")
    for relative, (expected_size, expected_hash) in MODELS.items():
        path = ROOT / relative
        data = path.read_bytes()
        actual = hashlib.sha256(data).hexdigest()
        if len(data) != expected_size:
            raise SystemExit(f"{relative}: size mismatch: {len(data)}")
        if actual != expected_hash:
            raise SystemExit(f"{relative}: SHA-256 mismatch: {actual}")
        print(f"{relative}: OK ({len(data)} bytes, {actual})")
