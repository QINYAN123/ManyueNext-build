#!/usr/bin/env python3
"""Generate the isolated pad=1 Winograd43 input-shader candidate.

The input is pinned to the pristine v93 source snapshot. The generated shader
adds specialization constant id 7 (`manyue_pad1`, default 0); the host-side
profile must set it to 1 only for the guarded buffer VkMat path.
"""

from __future__ import annotations

import hashlib
import re
from pathlib import Path


HERE = Path(__file__).resolve().parent
SOURCE = (
    HERE.parent
    / "native-profile-v93-20261018"
    / "pinned-source"
    / "src"
    / "layer"
    / "vulkan"
    / "shader"
    / "convolution_pack4_3x3s1d1_winograd43_transform_input.comp"
)
OUTPUT = (
    HERE
    / "pad-shader"
    / "convolution_pack4_3x3s1d1_winograd43_transform_input.comp"
)

EXPECTED_SOURCE_SHA256 = "c19767a5bb854d15f8764411165a78146d6ada4a19fa216d937376c8e6a17adb"
EXPECTED_MATH_TAIL_SHA256 = "b441ae8295c5be286242c64ea66bfc5b4a2912d6a2a19f2a9c7af46efef4ca6f"

CONST_ANCHOR = (
    "layout (constant_id = shape_constant_id_offset + 6) "
    "const int block_y = 0;\n"
)
LOAD_ANCHOR = (
    "#if NCNN_image_shader\n"
    "    afpvec4 v00 = image3d_ld4(bottom_blob, ivec3(sx + 0, sy + 0, gz));\n"
)
MATH_ANCHOR = "#define sq2 1.41421356237\n"
SPECIALIZATION = (
    "layout (constant_id = shape_constant_id_offset + 7) "
    "const int manyue_pad1 = 0;\n"
)


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def require_once(text: str, anchor: str, label: str) -> int:
    count = text.count(anchor)
    if count != 1:
        raise AssertionError(f"expected exactly one {label} anchor, found {count}")
    return text.index(anchor)


def expected_names() -> list[str]:
    return [f"v{row}{col}" for row in range(6) for col in range(6)]


def make_pad1_loads() -> str:
    lines = [
        "        int sx_pad1 = gx * 4 - 1;",
        "        int sy_pad1 = gy * 4 - 1;",
        "        int v_offset_pad1_0 = gz * psc(cstep) + sy_pad1 * psc(w) + sx_pad1;",
        "        ivec4 v_offset_pad1 = v_offset_pad1_0 + ivec4(0, 1, 2, 3) * psc(w);",
        "        ivec2 v_offset_pad1_45 = v_offset_pad1_0 + ivec2(4, 5) * psc(w);",
        "        bool pad1_interior = sx_pad1 >= 0 && sy_pad1 >= 0 && sx_pad1 + 5 < psc(w) && sy_pad1 + 5 < psc(h);",
        "",
        "        if (pad1_interior)",
        "        {",
    ]

    row_offsets = ["v_offset_pad1.r", "v_offset_pad1.g", "v_offset_pad1.b", "v_offset_pad1.a", "v_offset_pad1_45.x", "v_offset_pad1_45.y"]
    for row in range(6):
        for col in range(6):
            lines.append(
                f"            v{row}{col} = buffer_ld4(bottom_blob_data, {row_offsets[row]} + {col});"
            )

    lines.extend(["        }", "        else", "        {"])
    for row in range(6):
        for col in range(6):
            x = f"sx_pad1 + {col}"
            y = f"sy_pad1 + {row}"
            offset = f"{row_offsets[row]} + {col}"
            condition = (
                f"{x} >= 0 && {y} >= 0 && "
                f"{x} < psc(w) && {y} < psc(h)"
            )
            lines.append(
                f"            if ({condition})"
            )
            lines.extend(
                [
                    "            {",
                    f"                v{row}{col} = buffer_ld4(bottom_blob_data, {offset});",
                    "            }",
                    "            else",
                    "            {",
                    f"                v{row}{col} = afpvec4(0.f);",
                    "            }",
                ]
            )
    lines.extend(["        }"])
    return "\n".join(lines)


def generate() -> str:
    source = SOURCE.read_text(encoding="utf-8")
    if sha256(source) != EXPECTED_SOURCE_SHA256:
        raise AssertionError("pinned shader does not match the expected pristine source hash")

    const_pos = require_once(source, CONST_ANCHOR, "block_y specialization")
    if SPECIALIZATION in source:
        raise AssertionError("specialization id 7 already exists in the pinned source")

    load_start = require_once(source, LOAD_ANCHOR, "6x6 input-load")
    math_start = require_once(source, MATH_ANCHOR, "Winograd math")
    if math_start <= load_start:
        raise AssertionError("unexpected shader section order")

    math_tail = source[math_start:]
    if sha256(math_tail) != EXPECTED_MATH_TAIL_SHA256:
        raise AssertionError("pinned Winograd math/store tail hash mismatch")

    input_region = source[load_start:math_start]
    else_anchor = "#else\n"
    if input_region.count(else_anchor) != 1 or not input_region.endswith("#endif\n\n"):
        raise AssertionError("unexpected image/buffer load branch layout")
    else_pos = input_region.index(else_anchor)
    image_branch = input_region[: else_pos + len(else_anchor)]
    original_buffer = input_region[else_pos + len(else_anchor) : -len("#endif\n\n")]

    parsed_loads: dict[str, str] = {}
    other_lines: list[str] = []
    for line in original_buffer.splitlines():
        match = re.fullmatch(r"    afpvec4 (v[0-5][0-5]) = (.+);", line)
        if match:
            name, expression = match.groups()
            if name in parsed_loads:
                raise AssertionError(f"duplicate original load for {name}")
            parsed_loads[name] = expression
        else:
            other_lines.append(line)

    names = expected_names()
    if list(parsed_loads) != names:
        raise AssertionError("original buffer branch must contain the ordered 36 v00-v55 loads")
    expected_offsets = [
        "    int v_offset_0 = gz * psc(cstep) + sy * psc(w) + sx;",
        "    ivec4 v_offset = v_offset_0 + ivec4(0, 1, 2, 3) * psc(w);",
        "    ivec2 v_offset45 = v_offset_0 + ivec2(4, 5) * psc(w);",
    ]
    if other_lines[:3] != expected_offsets or any(line.strip() for line in other_lines[3:]):
        raise AssertionError("original buffer branch offset anchors changed")

    declarations = "\n".join(f"    afpvec4 {name};" for name in names)
    default_path = [
        "        int v_offset_0 = gz * psc(cstep) + sy * psc(w) + sx;",
        "        ivec4 v_offset = v_offset_0 + ivec4(0, 1, 2, 3) * psc(w);",
        "        ivec2 v_offset45 = v_offset_0 + ivec2(4, 5) * psc(w);",
    ]
    for name in names:
        default_path.append(f"        {name} = {parsed_loads[name]};")

    buffer_branch = [
        declarations,
        "",
        "    if (manyue_pad1 == 1)",
        "    {",
        make_pad1_loads(),
        "    }",
        "    else",
        "    {",
        "\n".join(default_path),
        "    }",
    ]
    new_input_region = image_branch + "\n".join(buffer_branch) + "\n#endif\n\n"

    with_specialization = source[: const_pos + len(CONST_ANCHOR)] + SPECIALIZATION + source[const_pos + len(CONST_ANCHOR) :]
    load_start = require_once(with_specialization, LOAD_ANCHOR, "copied 6x6 image-load")
    math_start = require_once(with_specialization, MATH_ANCHOR, "copied Winograd math")
    candidate = with_specialization[:load_start] + new_input_region + with_specialization[math_start:]

    candidate_math_start = require_once(candidate, MATH_ANCHOR, "generated Winograd math")
    if sha256(candidate[candidate_math_start:]) != EXPECTED_MATH_TAIL_SHA256:
        raise AssertionError("generated shader changed the Winograd math/store tail")
    candidate_load_start = require_once(candidate, LOAD_ANCHOR, "generated 6x6 image-load")
    candidate_load_region = candidate[candidate_load_start:candidate_math_start]
    candidate_else_pos = candidate_load_region.index(else_anchor)
    if candidate_load_region[: candidate_else_pos + len(else_anchor)] != image_branch:
        raise AssertionError("generated shader changed the original image-load branch")
    if not candidate.startswith(with_specialization[:load_start]):
        raise AssertionError("unexpected change before the input-load block")
    if candidate.count(SPECIALIZATION) != 1:
        raise AssertionError("generated shader must contain exactly one id-7 specialization")
    if candidate.count("if (manyue_pad1 == 1)") != 1:
        raise AssertionError("generated shader must contain exactly one pad=1 branch")
    if candidate.count("buffer_ld4(bottom_blob_data,") != 36 + 36 + 36:
        raise AssertionError("expected 36 original, 36 interior, and 36 guarded loads")
    pad1_loads = make_pad1_loads()
    if pad1_loads.count("buffer_ld4(bottom_blob_data,") != 72:
        raise AssertionError("pad=1 branch must contain 36 fast and 36 edge loads")
    if pad1_loads.count("afpvec4(0.f)") != 36:
        raise AssertionError("pad=1 edge branch must zero all out-of-bounds elements")

    return candidate


def main() -> None:
    candidate = generate()
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(candidate, encoding="utf-8", newline="\n")
    written = OUTPUT.read_text(encoding="utf-8")
    if written != candidate:
        raise AssertionError("generated shader did not round-trip exactly")
    print(f"generated: {OUTPUT}")
    print(f"math_tail_sha256: {EXPECTED_MATH_TAIL_SHA256}")
    print(f"shader_sha256: {sha256(candidate)}")


if __name__ == "__main__":
    main()
