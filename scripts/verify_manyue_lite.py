#!/usr/bin/env python3
"""Verify the exact ARM64 JNI/model assets required by the Lite reader path."""
from __future__ import annotations
import hashlib
import importlib.util
import math
import re
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/ai/models-Manyue-Lite'
JNI = ROOT / 'app/src/main/jniLibs/arm64-v8a/libmanyue_lite.so'
PIN = ROOT / 'app/src/main/java/eu/kanade/tachiyomi/ui/reader/manyue/ManyueLiteAssets.kt'


def pinned_files():
    source = PIN.read_text(encoding='utf-8')
    native = re.search(r'NATIVE_SHA256\s*=\s*"([0-9a-f]{64})"', source)
    assert native, 'Lite native build has not been verified/pinned'
    model = dict(re.findall(r'"((?:trunk|head)\.(?:param|bin|f32))"\s+to\s+"([0-9a-f]{64})"', source))
    assert set(model) == {'trunk.param', 'trunk.bin', 'head.param', 'head.bin', 'head.f32'}, 'Incomplete model pins'
    return {JNI: native.group(1), **{ASSETS / name: digest for name, digest in model.items()}}


def verify():
    spec = importlib.util.spec_from_file_location('old_native', ROOT / 'scripts/verify_manyue_native.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    for path, digest in pinned_files().items():
        data = path.read_bytes()
        assert hashlib.sha256(data).hexdigest() == digest, f'Changed/unverified asset: {path.name}'
        if path == JNI:
            module.verify_arm64_elf(path, data, require_executable=False)
            for method in ['nativeCreate', 'nativeUpscale', 'nativeCancel', 'nativeDestroy']:
                symbol = 'Java_eu_kanade_tachiyomi_ui_reader_manyue_ManyueLiteNative_' + method
                assert symbol.encode() in data, f'JNI entry missing: {method}'
        elif path.suffix == '.param':
            assert data.splitlines()[0].strip() == b'7767517', 'Invalid NCNN param'
            assert b'in0' in data and b'out0' in data
            # NCNN ParamDict stores integer and float tokens in the same union.
            # Clip's integer token 1=1 would become float bits 0x00000001,
            # silently erasing learned features despite a successful load.
            if path.name == 'trunk.param':
                clips = [line.split() for line in data.splitlines() if line.startswith(b'Clip ')]
                assert len(clips) == 3, 'Unexpected Lite encoder activations'
                assert all(b'0=0.0' in line and b'1=1.0' in line for line in clips), \
                    'NCNN Clip bounds must be explicitly typed as floats'
            elif path.name == 'head.param':
                relus = [line.split() for line in data.splitlines() if line.startswith(b'ReLU ')]
                assert len(relus) == 1 and b'0=0.0' in relus[0], 'NCNN ReLU slope must be a float'
        elif path.name == 'head.f32':
            assert len(data) == 867 * 4, 'Invalid fused head weights'
        print(f'{path.name}: OK ({len(data)} bytes, {digest})')
    # GPU's fused head must use the exact FP32 convolution/bias data of the CPU head.
    head = (ASSETS / 'head.bin').read_bytes()
    assert len(head) == (867 + 2) * 4, 'Unexpected CPU head representation'
    second_tag = 4 + (23 * 32 + 32) * 4
    assert head[:4] == head[second_tag:second_tag + 4] == bytes(4), 'Head weights must be FP32'
    fused = head[4:second_tag] + head[second_tag + 4:]
    assert fused == (ASSETS / 'head.f32').read_bytes(), 'CPU/GPU head weights differ'
    assert all(math.isfinite(value) for value in struct.unpack('<867f', fused)), 'Non-finite head'


if __name__ == '__main__':
    verify()
