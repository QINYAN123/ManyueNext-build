#!/usr/bin/env python3
"""Create scratch-only MANYUE_GPU_CHUNK patches for legacy ncnn C++ engines.

This transformer never edits its input. It writes a distinct output source path.
It only adds Vulkan intermediate extraction to the known Real-ESRGAN x2 graph
and the known Real-CUGAN SE stage2 graph; unsupported source/model shapes fail
closed or leave inference on its original path.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys
import textwrap


REALSR_HELPERS = r"""
#include <cstdlib>
#include <fstream>

// MANYUE_GPU_CHUNK helper: only the verified Real-ESRGANv3-anime x2 graph.
static int manyue_gpu_chunk_mode()
{
    const char* value = std::getenv("MANYUE_GPU_CHUNK");
    if (value == 0 || value[0] == '\0')
        return 0;

    char* end = 0;
    const long parsed = std::strtol(value, &end, 10);
    if (end == value || *end != '\0' || (parsed != 1 && parsed != 2 && parsed != 4))
        return 0;

    return static_cast<int>(parsed);
}

static bool manyue_gpu_blob_from_layer(const ncnn::Net& net,
                                       const char* blob_name,
                                       const char* layer_name,
                                       const char* layer_type)
{
    const std::vector<ncnn::Blob>& blobs = net.blobs();
    const std::vector<ncnn::Layer*>& layers = net.layers();

    for (size_t i = 0; i < blobs.size(); i++)
    {
        if (blobs[i].name != blob_name)
            continue;

        const int producer = blobs[i].producer;
        if (producer < 0 || producer >= static_cast<int>(layers.size()) || layers[producer] == 0)
            return false;

        return layers[producer]->name == layer_name && layers[producer]->type == layer_type;
    }

    return false;
}

static bool manyue_gpu_is_realesr_x2_graph(const ncnn::Net& net)
{
    if (net.layers().size() != 41)
        return false;

    // All 16 trunk Conv/PReLU outputs come from the bundled x2.param. The
    // remaining Conv_32/PRelu_33/Conv_34 and pixel-shuffle/residual/resize
    // tail stays in the final extraction.
    static const char* const blobs[] = {"56", "59", "62", "65", "68", "71", "74", "77",
                                        "80", "83", "86", "89", "92", "95", "98", "101"};
    static const char* const layers[] = {"PRelu_1", "PRelu_3", "PRelu_5", "PRelu_7",
                                         "PRelu_9", "PRelu_11", "PRelu_13", "PRelu_15",
                                         "PRelu_17", "PRelu_19", "PRelu_21", "PRelu_23",
                                         "PRelu_25", "PRelu_27", "PRelu_29", "PRelu_31"};

    for (size_t i = 0; i < sizeof(blobs) / sizeof(blobs[0]); i++)
    {
        if (!manyue_gpu_blob_from_layer(net, blobs[i], layers[i], "PReLU"))
            return false;
    }

    return manyue_gpu_blob_from_layer(net, "102", "Conv_32", "Convolution")
        && manyue_gpu_blob_from_layer(net, "104", "PRelu_33", "PReLU")
        && manyue_gpu_blob_from_layer(net, "105", "Conv_34", "Convolution")
        && manyue_gpu_blob_from_layer(net, "106", "DepthToSpace_35", "PixelShuffle")
        && manyue_gpu_blob_from_layer(net, "111", "Resize_37", "Interp")
        && manyue_gpu_blob_from_layer(net, "112", "Add_38", "BinaryOp")
        && manyue_gpu_blob_from_layer(net, "output", "Resize_40", "Interp");
}

static bool manyue_gpu_cancel_requested(const char* path)
{
    if (!path || !path[0])
        return false;
    std::ifstream marker(path, std::ios::binary);
    return marker.good();
}

// Ask ncnn to materialize one existing graph blob, wait for that command
// block, and reset its command buffer. The temporary VkMat handle lives until
// after reset; the same Extractor retains its own blob handle for continuation.
static int manyue_gpu_chunk_boundary(ncnn::Extractor& ex,
                                     ncnn::VkCompute& cmd,
                                     const char* blob_name,
                                     const char* cancel_path,
                                     bool* cancelled)
{
    ncnn::VkMat boundary;
    int ret = ex.extract(blob_name, boundary, cmd);
    if (ret != 0)
        return ret;

    ret = cmd.submit_and_wait();
    if (ret != 0)
        return ret;

    ret = cmd.reset();
    if (ret != 0)
        return ret;

    if (cancelled && manyue_gpu_cancel_requested(cancel_path))
        *cancelled = true;
    return 0;
}
"""

REALCUGAN_HELPERS = r"""
#include <cstdlib>

// MANYUE_GPU_CHUNK helper: only the verified Real-CUGAN SE stage2 graph.
static int manyue_gpu_chunk_mode()
{
    const char* value = std::getenv("MANYUE_GPU_CHUNK");
    if (value == 0 || value[0] == '\0')
        return 0;

    char* end = 0;
    const long parsed = std::strtol(value, &end, 10);
    // Real-CUGAN deliberately keeps its existing 2/4 modes; mode 1 is off.
    if (end == value || *end != '\0' || (parsed != 2 && parsed != 4))
        return 0;

    return static_cast<int>(parsed);
}

static bool manyue_gpu_blob_from_layer(const ncnn::Net& net,
                                       const char* blob_name,
                                       const char* layer_name,
                                       const char* layer_type)
{
    const std::vector<ncnn::Blob>& blobs = net.blobs();
    const std::vector<ncnn::Layer*>& layers = net.layers();

    for (size_t i = 0; i < blobs.size(); i++)
    {
        if (blobs[i].name != blob_name)
            continue;

        const int producer = blobs[i].producer;
        if (producer < 0 || producer >= static_cast<int>(layers.size()) || layers[producer] == 0)
            return false;

        return layers[producer]->name == layer_name && layers[producer]->type == layer_type;
    }

    return false;
}

static bool manyue_gpu_is_cugan_se_graph(const ncnn::Net& net)
{
    if (net.layers().size() != 55)
        return false;

    // Each gate output is after an injected SE input (gap0..gap3). Checking
    // these producers prevents chunking a similarly numbered unrelated graph.
    return manyue_gpu_blob_from_layer(net, "14", "mul_0", "BinaryOp")
        && manyue_gpu_blob_from_layer(net, "34", "mul_2", "BinaryOp")
        && manyue_gpu_blob_from_layer(net, "45", "mul_3", "BinaryOp")
        && manyue_gpu_blob_from_layer(net, "56", "mul_5", "BinaryOp")
        && manyue_gpu_blob_from_layer(net, "gap0", "mean_64", "Pooling")
        && manyue_gpu_blob_from_layer(net, "gap1", "mean_65", "Pooling")
        && manyue_gpu_blob_from_layer(net, "gap2", "mean_66", "Pooling")
        && manyue_gpu_blob_from_layer(net, "gap3", "mean_67", "Pooling")
        && manyue_gpu_blob_from_layer(net, "out0", "add_7", "BinaryOp");
}

static bool manyue_gpu_has_cugan_gap_inputs(const std::vector<std::string>& names)
{
    return names.size() == 4
        && names[0] == "gap0"
        && names[1] == "gap1"
        && names[2] == "gap2"
        && names[3] == "gap3";
}

// Ask ncnn to materialize one existing graph blob, wait for that command
// block, and reset its command buffer. The temporary VkMat handle lives until
// after reset; the same Extractor retains its own blob handle for continuation.
static int manyue_gpu_chunk_boundary(ncnn::Extractor& ex,
                                     ncnn::VkCompute& cmd,
                                     const char* blob_name)
{
    ncnn::VkMat boundary;
    int ret = ex.extract(blob_name, boundary, cmd);
    if (ret != 0)
        return ret;

    ret = cmd.submit_and_wait();
    if (ret != 0)
        return ret;

    return cmd.reset();
}
"""


def replace_indented_line(text: str, old: str, new: str, label: str) -> str:
    pattern = re.compile(r"(?m)^(?P<indent>[ \t]*)" + re.escape(old) + r"$")
    matches = list(pattern.finditer(text))
    if len(matches) != 1:
        raise ValueError(f"{label}: expected exactly one anchor, found {len(matches)}")

    match = matches[0]
    indent = match.group("indent")
    block = textwrap.dedent(new).strip("\n")
    replacement = "\n".join(indent + line if line else "" for line in block.splitlines())
    return text[:match.start()] + replacement + text[match.end():]


def region_bounds(text: str, start_marker: str, end_marker: str, label: str) -> tuple[int, int]:
    start = text.find(start_marker)
    if start < 0:
        raise ValueError(f"{label}: function start anchor not found")
    end = text.find(end_marker, start + len(start_marker))
    if end < 0:
        raise ValueError(f"{label}: function end anchor not found")
    return start, end


def helper_insertion(text: str, constructor_marker: str, helper: str, label: str) -> str:
    if "// MANYUE_GPU_CHUNK helper:" in text:
        raise ValueError(f"{label}: source already contains a MANYUE_GPU_CHUNK helper")
    vector_include = "#include <vector>"
    if text.count(vector_include) != 1:
        raise ValueError(f"{label}: expected one vector include for helper placement")
    text = text.replace(vector_include, vector_include + "\n#include <cstdlib>", 1)
    pos = text.find(constructor_marker)
    if pos < 0:
        raise ValueError(f"{label}: constructor anchor not found")
    helper = helper.replace("#include <cstdlib>\n\n", "", 1)
    return text[:pos] + helper.lstrip() + "\n\n" + text[pos:]


def patch_realesr(text: str) -> tuple[str, int]:
    original = text
    resident_cancel = "const char* cancel_path" in text and "manyue_resident_cancel_requested" in text
    text = helper_insertion(text, "RealSR::RealSR(", REALSR_HELPERS, "RealSR")

    start, end = region_bounds(
        text,
        "int RealSR::process(",
        "\nint RealSR::process_cpu(",
        "RealSR::process",
    )
    region = text[start:end]
    setup_anchor = "{\n"
    opening = region.find(setup_anchor)
    if opening < 0:
        raise ValueError("RealSR::process: opening brace anchor not found")

    setup = r"""
    const int manyue_gpu_chunk_mode_value = manyue_gpu_chunk_mode();
    const bool manyue_gpu_chunk_enabled =
        (manyue_gpu_chunk_mode_value == 1 || manyue_gpu_chunk_mode_value == 2 || manyue_gpu_chunk_mode_value == 4)
        && manyue_gpu_is_realesr_x2_graph(net);
    bool manyue_cancelled = false;
"""
    region = region[:opening + len(setup_anchor)] + setup + region[opening + len(setup_anchor):]

    old = 'MANYUE_NCNN_CHECK(ex.extract(net_output_name.c_str(), out_tile_gpu, cmd), "Extractor::extract");'
    cancel_arg = "cancel_path" if resident_cancel else "0"
    injected = r"""
                    if (manyue_gpu_chunk_enabled)
                    {
                        static const char* const manyue_chunk_1[] =
                            {"56", "59", "62", "65", "68", "71", "74", "77",
                             "80", "83", "86", "89", "92", "95", "98", "101"};
                        static const char* const manyue_chunk_2[] =
                            {"59", "65", "71", "77", "83", "89", "95", "101"};
                        static const char* const manyue_chunk_4[] =
                            {"65", "77", "89", "101"};
                        const char* const* manyue_boundaries =
                            manyue_gpu_chunk_mode_value == 1 ? manyue_chunk_1
                                : manyue_gpu_chunk_mode_value == 2 ? manyue_chunk_2 : manyue_chunk_4;
                        const size_t manyue_boundary_count =
                            manyue_gpu_chunk_mode_value == 1
                                ? sizeof(manyue_chunk_1) / sizeof(manyue_chunk_1[0])
                                : manyue_gpu_chunk_mode_value == 2
                                    ? sizeof(manyue_chunk_2) / sizeof(manyue_chunk_2[0])
                                    : sizeof(manyue_chunk_4) / sizeof(manyue_chunk_4[0]);

                        for (size_t manyue_i = 0; manyue_i < manyue_boundary_count; manyue_i++)
                        {
                            MANYUE_NCNN_CHECK(
                                manyue_gpu_chunk_boundary(ex, cmd, manyue_boundaries[manyue_i], @CANCEL_PATH@, &manyue_cancelled),
                                "MANYUE_GPU_CHUNK intermediate");
                            @CANCEL_GOTO@
                        }
                    }

                    MANYUE_NCNN_CHECK(ex.extract(net_output_name.c_str(), out_tile_gpu, cmd), "Extractor::extract");"""
    injected = injected.replace("@CANCEL_PATH@", cancel_arg)
    injected = injected.replace(
        "@CANCEL_GOTO@",
        "if (manyue_cancelled) goto manyue_process_cleanup;"
        if resident_cancel else "",
    )
    region = replace_indented_line(region, old, injected, "RealSR final Vulkan extraction")

    if resident_cancel:
        download_anchor = (
            "            cmd.record_clone(out_gpu, out, opt);\n\n"
            "            MANYUE_NCNN_CHECK(cmd.submit_and_wait(), \"VkCompute::submit_and_wait\");\n"
        )
        download_replacement = (
            download_anchor
            + "            MANYUE_NCNN_CHECK(cmd.reset(), \"VkCompute::reset\");\n"
            "            if (manyue_gpu_cancel_requested(cancel_path)) {\n"
            "                manyue_cancelled = true;\n"
            "                goto manyue_process_cleanup;\n"
            "            }\n"
        )
        if region.count(download_anchor) != 1:
            raise ValueError("RealSR tile readback cancellation boundary: expected one anchor")
        region = region.replace(download_anchor, download_replacement, 1)

        cleanup_anchor = (
            "    vkdev->reclaim_blob_allocator(blob_vkallocator);\n"
            "    vkdev->reclaim_staging_allocator(staging_vkallocator);\n\n"
            "    return 0;\n"
        )
        cleanup_replacement = (
            "manyue_process_cleanup:\n"
            "    vkdev->reclaim_blob_allocator(blob_vkallocator);\n"
            "    vkdev->reclaim_staging_allocator(staging_vkallocator);\n\n"
            "    return manyue_cancelled ? manyue_resident::kCancelled : 0;\n"
        )
        if region.count(cleanup_anchor) != 1:
            raise ValueError("RealSR Vulkan cancel cleanup tail: expected one anchor")
        region = region.replace(cleanup_anchor, cleanup_replacement, 1)

    text = text[:start] + region + text[end:]
    if resident_cancel:
        required_cancel_sites = (
            "manyue_gpu_chunk_boundary(ex, cmd, manyue_boundaries[manyue_i], cancel_path, &manyue_cancelled)",
            "if (manyue_cancelled) goto manyue_process_cleanup;",
            "MANYUE_NCNN_CHECK(cmd.reset(), \"VkCompute::reset\");",
            "manyue_process_cleanup:",
            "vkdev->reclaim_blob_allocator(blob_vkallocator);",
            "vkdev->reclaim_staging_allocator(staging_vkallocator);",
            "return manyue_cancelled ? manyue_resident::kCancelled : 0;",
        )
        missing = [anchor for anchor in required_cancel_sites if anchor not in text]
        if missing:
            raise ValueError(f"RealSR cooperative cancellation patch incomplete: {missing}")
    elif "manyue_process_cleanup:" in text:
        raise ValueError("RealSR cold CLI unexpectedly received resident cancellation cleanup")
    if text == original:
        raise ValueError("RealSR: no patch applied")
    return text, 1


def patch_realcugan(text: str) -> tuple[str, int]:
    original = text
    text = helper_insertion(text, "RealCUGAN::RealCUGAN(", REALCUGAN_HELPERS, "RealCUGAN")

    start, end = region_bounds(
        text,
        "int RealCUGAN::process_se_stage2(",
        "\nint RealCUGAN::process_se_sync_gap(",
        "RealCUGAN::process_se_stage2",
    )
    region = text[start:end]
    setup_anchor = "{\n"
    opening = region.find(setup_anchor)
    if opening < 0:
        raise ValueError("RealCUGAN::process_se_stage2: opening brace anchor not found")

    setup = r"""
    const int manyue_gpu_chunk_mode_value = manyue_gpu_chunk_mode();
    const bool manyue_gpu_chunk_enabled =
        (manyue_gpu_chunk_mode_value == 2 || manyue_gpu_chunk_mode_value == 4)
        && manyue_gpu_is_cugan_se_graph(net)
        && manyue_gpu_has_cugan_gap_inputs(names);
"""
    region = region[:opening + len(setup_anchor)] + setup + region[opening + len(setup_anchor):]

    old_tta = 'MANYUE_NCNN_CHECK(ex.extract("out0", out_tile_gpu[ti], cmd), "Extractor::extract");'
    old_normal = 'MANYUE_NCNN_CHECK(ex.extract("out0", out_tile_gpu, cmd), "Extractor::extract");'

    def inject(mode_specific: bool, final_line: str) -> str:
        if mode_specific:
            selection = r"""
                        static const char* const manyue_chunk_2[] = {"34"};
                        static const char* const manyue_chunk_4[] = {"14", "34", "45", "56"};
                        const char* const* manyue_boundaries =
                            manyue_gpu_chunk_mode_value == 2 ? manyue_chunk_2 : manyue_chunk_4;
                        const size_t manyue_boundary_count =
                            manyue_gpu_chunk_mode_value == 2
                                ? sizeof(manyue_chunk_2) / sizeof(manyue_chunk_2[0])
                                : sizeof(manyue_chunk_4) / sizeof(manyue_chunk_4[0]);
"""
        else:
            selection = ""
        return r"""
                    if (manyue_gpu_chunk_enabled)
                    {
""" + selection + r"""
                        for (size_t manyue_i = 0; manyue_i < manyue_boundary_count; manyue_i++)
                        {
                            MANYUE_NCNN_CHECK(
                                manyue_gpu_chunk_boundary(ex, cmd, manyue_boundaries[manyue_i]),
                                "MANYUE_GPU_CHUNK intermediate");
                        }
                    }

                    """ + final_line

    # Both branches share the same SE stage2 function but have independent extractors.
    # Apply mode-specific boundary arrays at each matching final extraction.
    tta_injected = inject(True, old_tta)
    normal_injected = inject(True, old_normal)
    region = replace_indented_line(region, old_tta, tta_injected, "CUGAN stage2 TTA final extraction")
    region = replace_indented_line(region, old_normal, normal_injected, "CUGAN stage2 non-TTA final extraction")
    text = text[:start] + region + text[end:]
    if text == original:
        raise ValueError("RealCUGAN: no patch applied")
    return text, 2


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", choices=("realesr", "realcugan"), required=True)
    parser.add_argument("--source", type=Path, required=True, help="fresh patched C++ source path")
    parser.add_argument("--output", type=Path, required=True, help="separate scratch output path; input is never overwritten")
    args = parser.parse_args()

    source = args.source.expanduser().resolve()
    output = args.output.expanduser().resolve()
    if not source.is_file():
        parser.error(f"source does not exist: {source}")
    if source == output:
        parser.error("output must be different from source; in-place patching is refused")
    if output.exists():
        parser.error(f"output already exists; choose a new scratch path: {output}")

    try:
        raw_source = source.read_bytes()
        newline = "\r\n" if b"\r\n" in raw_source else "\n"
        text = raw_source.decode("utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")
        if args.engine == "realesr":
            patched, sites = patch_realesr(text)
        else:
            patched, sites = patch_realcugan(text)
    except (OSError, UnicodeError, ValueError) as exc:
        print(f"patch refused: {exc}", file=sys.stderr)
        return 2

    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="") as stream:
        stream.write(patched.replace("\n", newline))
    print(f"patched {args.engine}: {source} -> {output} ({sites} extraction site(s))")
    print("No build/runtime verification was performed by this helper.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

