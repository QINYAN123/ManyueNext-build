#!/usr/bin/env python3
"""Prepare the pinned RealSR/CUGAN Android CLI sources for Manyue output sizing.

This is intentionally a deterministic source transformer instead of a fork of
the upstream model implementation. It refuses unknown source layouts instead
of silently applying a partial patch.
"""

from __future__ import annotations

import argparse
import re
import shutil
from pathlib import Path


UPSTREAM_COMMIT = "5eb6e3d"
UPSTREAM_MODULES = {
    "realesr": ("RealSR", "realsr-ncnn", "libmanyue_realesr.so"),
    "realcugan": ("RealCUGAN", "realcugan-ncnn", "libmanyue_realcugan.so"),
}


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one source match, found {count}")
    return text.replace(old, new, 1)


def _code_mask(text: str) -> str:
    """Blank comments and string/character literals while preserving offsets."""
    masked = list(text)
    state = "code"
    i = 0
    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ""
        if state == "code":
            if ch == "/" and nxt == "/":
                masked[i] = masked[i + 1] = " "
                state = "line_comment"
                i += 2
                continue
            if ch == "/" and nxt == "*":
                masked[i] = masked[i + 1] = " "
                state = "block_comment"
                i += 2
                continue
            if ch == '"':
                masked[i] = " "
                state = "string"
            elif ch == "'":
                masked[i] = " "
                state = "character"
        elif state in ("line_comment", "block_comment", "string", "character"):
            if ch != "\n":
                masked[i] = " "
            if state == "line_comment" and ch == "\n":
                state = "code"
            elif state == "block_comment" and ch == "*" and nxt == "/":
                masked[i + 1] = " "
                state = "code"
                i += 2
                continue
            elif state in ("string", "character"):
                if ch == "\\":
                    if i + 1 < len(text) and text[i + 1] != "\n":
                        masked[i + 1] = " "
                    i += 2
                    continue
                if (state == "string" and ch == '"') or (state == "character" and ch == "'"):
                    state = "code"
        i += 1
    return "".join(masked)


def wrap_ncnn_status_calls(text: str, label: str) -> tuple[str, dict[str, int]]:
    """Turn ignored ncnn status calls into worker-fatal checks."""
    mask = _code_mask(text)
    pattern = re.compile(r"\b(ex\.(?:input|extract)|cmd\.submit_and_wait)\s*\(")
    matches = list(pattern.finditer(mask))
    counts = {"Extractor::input": 0, "Extractor::extract": 0,
              "VkCompute::submit_and_wait": 0}
    replacements: list[tuple[int, int, str]] = []
    for match in matches:
        api = match.group(1)
        open_paren = mask.find("(", match.start(), match.end())
        depth = 0
        close_paren = -1
        for i in range(open_paren, len(mask)):
            if mask[i] == "(":
                depth += 1
            elif mask[i] == ")":
                depth -= 1
                if depth == 0:
                    close_paren = i
                    break
        if close_paren < 0:
            raise RuntimeError(f"{label}: unbalanced ncnn call at offset {match.start()}")
        after = close_paren + 1
        while after < len(mask) and mask[after].isspace():
            after += 1
        if after >= len(mask) or mask[after] != ";":
            raise RuntimeError(f"{label}: expected standalone ncnn call at offset {match.start()}")
        if api == "cmd.submit_and_wait":
            status_label = "VkCompute::submit_and_wait"
        elif api == "ex.input":
            status_label = "Extractor::input"
        else:
            status_label = "Extractor::extract"
        counts[status_label] += 1
        call = text[match.start():close_paren + 1]
        replacements.append((match.start(), after + 1,
                             f'MANYUE_NCNN_CHECK({call}, "{status_label}");'))

    for start, end, replacement in reversed(replacements):
        text = text[:start] + replacement + text[end:]
    if not matches:
        raise RuntimeError(f"{label}: no ncnn inference status calls were found")
    return text, counts


def patch_source(source_root: Path, destination: Path, variant: str,
                 helper_header: Path, status_header: Path, cmake_template: Path) -> None:
    module, _target, _binary = UPSTREAM_MODULES[variant]
    source_module = source_root / "RealSR-NCNN-Android-CLI" / module / "src" / "main" / "jni"
    if not (source_module / "main.cpp").is_file():
        raise FileNotFoundError(f"No pinned {module} JNI source under {source_module}")
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source_module, destination, dirs_exist_ok=True)
    shutil.copy2(helper_header, destination / "manyue_output_resize.h")
    shutil.copy2(status_header, destination / "manyue_ncnn_status.h")

    main_file = destination / "main.cpp"
    text = main_file.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '#include "filesystem_utils.h"\n#include <opencv2/opencv.hpp>\n'
        '#include <opencv2/core/hal/interface.h>\nusing namespace cv;',
        '#include "filesystem_utils.h"\n#include "manyue_output_resize.h"',
        "remove OpenCV and include output resize helper",
    )
    if variant == "realesr":
        text = replace_once(text, '#include "realsr.h"\n',
                            '#include "realsr.h"\n#include "manyue_ncnn_status.h"\n',
                            "include ncnn status checker")
    else:
        text = replace_once(text, '#include "realcugan.h"\n',
                            '#include "realcugan.h"\n#include "manyue_ncnn_status.h"\n',
                            "include ncnn status checker")
    text = replace_once(text, "#include <stdio.h>\n",
                        "#include <stdio.h>\n#include <stdlib.h>\n#include <iostream>\n",
                        "include standard runtime declarations directly")

    text = text.replace('"i:o:s:c:t:m:g:j:f:vxh"', '"i:o:s:c:t:m:g:j:f:w:vxh"')
    text = text.replace('L"i:o:s:c:t:m:g:j:f:vxh"', 'L"i:o:s:c:t:m:g:j:f:w:vxh"')
    text = text.replace('"i:o:n:s:t:c:m:g:j:f:vxh"', '"i:o:n:s:t:c:m:g:j:f:w:vxh"')
    text = text.replace('L"i:o:n:s:t:c:m:g:j:f:vxh"', 'L"i:o:n:s:t:c:m:g:j:f:w:vxh"')
    for option in ("i:o:s:c:t:m:g:j:f:w:vxh", "i:o:n:s:t:c:m:g:j:f:w:vxh"):
        if option in text and text.count(option) != 2:
            raise RuntimeError(f"getopt option string {option!r} has unexpected count")
    if "-w target-width" not in text:
        lines = text.splitlines(keepends=True)
        usage_lines = [i for i, line in enumerate(lines)
                       if "output image format (jpg/png/webp, default=ext/png)" in line]
        if len(usage_lines) != 1:
            raise RuntimeError(f"add target-width usage: expected one format usage line, found {len(usage_lines)}")
        lines.insert(usage_lines[0] + 1,
                     '    fprintf(stderr, "  -w target-width      output width (source width to 2x source width)\\n");\n')
        text = "".join(lines)

    if variant == "realesr":
        text = replace_once(text, "    int scale = 4;", "    int scale = 4;\n    int target_width = 0;", "declare RealSR target width")
        text = replace_once(text, "    int scale;\n    int jobs_load;", "    int scale;\n    int target_width;\n    int jobs_load;", "add RealSR load target width")
        text = replace_once(text, "int webp;\n//    bool check;", "int webp;\n    int target_width;\n    int target_height;\n    bool resize_failed;\n    bool resize_applied;\n//    bool check;", "add RealSR task target size")
        text = replace_once(text, "case L's':\n            scale = _wtoi(optarg);\n            break;", "case L's':\n            scale = _wtoi(optarg);\n            break;\n        case L'w':\n            target_width = _wtoi(optarg);\n            break;", "parse wide RealSR target width")
        text = replace_once(text, "case 's':\n                scale = atoi(optarg);\n                break;", "case 's':\n                scale = atoi(optarg);\n                break;\n            case 'w':\n                target_width = atoi(optarg);\n                break;", "parse RealSR target width")
        text = replace_once(text, "        if (pixeldata) {\n            Task v;", "        if (pixeldata) {\n            manyue_output::Size target_size;\n            if (!manyue_output::compute_target_size(w, h, ltp->target_width, &target_size)) {\n                fprintf(stderr, \"invalid target width %d for source %dx%d\\n\", ltp->target_width, w, h);\n                free(pixeldata);\n                continue;\n            }\n            Task v;", "validate RealSR per-image target width")
        text = replace_once(text, "            v.id = i;\n            v.inpath = imagepath;", "            v.id = i;\n            v.webp = 0;\n            v.target_width = target_size.width;\n            v.target_height = target_size.height;\n            v.resize_failed = false;\n            v.resize_applied = false;\n            v.inpath = imagepath;", "initialize RealSR task target size")
    else:
        text = replace_once(text, "    int scale = 2;", "    int scale = 2;\n    int target_width = 0;", "declare RealCUGAN target width")
        text = replace_once(text, "    int scale;\n    int jobs_load;", "    int scale;\n    int target_width;\n    int jobs_load;", "add RealCUGAN load target width")
        text = replace_once(text, "    int scale;\n\n    path_t inpath;", "    int scale;\n    int target_width;\n    int target_height;\n    bool resize_failed;\n    bool resize_applied;\n\n    path_t inpath;", "add RealCUGAN task target size")
        text = replace_once(text, "case L's':\n            scale = _wtoi(optarg);\n            break;", "case L's':\n            scale = _wtoi(optarg);\n            break;\n        case L'w':\n            target_width = _wtoi(optarg);\n            break;", "parse wide RealCUGAN target width")
        text = replace_once(text, "case 's':\n            scale = atoi(optarg);\n            break;", "case 's':\n            scale = atoi(optarg);\n            break;\n        case 'w':\n            target_width = atoi(optarg);\n            break;", "parse RealCUGAN target width")
        text = replace_once(text, "        if (pixeldata)\n        {\n            Task v;", "        if (pixeldata)\n        {\n            manyue_output::Size target_size;\n            if (!manyue_output::compute_target_size(w, h, ltp->target_width, &target_size))\n            {\n                fprintf(stderr, \"invalid target width %d for source %dx%d\\n\", ltp->target_width, w, h);\n                free(pixeldata);\n                continue;\n            }\n            Task v;", "validate RealCUGAN per-image target width")
        text = replace_once(text, "            v.id = i;\n            v.webp = webp;\n            v.scale = scale;", "            v.id = i;\n            v.webp = webp;\n            v.scale = scale;\n            v.target_width = target_size.width;\n            v.target_height = target_size.height;\n            v.resize_failed = false;\n            v.resize_applied = false;", "initialize RealCUGAN task target size")

    # The app always requests the fixed 2x model path. -w changes only the
    # output dimensions after inference and cannot lower model compute.
    if variant == "realesr":
        # RealSR's pinned CLI has separate input-path and output-path checks,
        # and its older scale check is commented out.
        anchor = "    if (inputpath.empty()) {\n"
        validation = "    if (target_width <= 0 || scale != 2) {\n        fprintf(stderr, \"-w target-width is required and this output mode expects -s 2\\n\");\n        return -1;\n    }\n\n"
        text = replace_once(text, anchor, validation + anchor, "validate RealSR fixed 2x output mode")
    else:
        anchor = "    if (inputpath.empty() || outputpath.empty())\n"
        validation = "    if (target_width <= 0 || scale != 2)\n    {\n        fprintf(stderr, \"-w target-width is required and this output mode expects -s 2\\n\");\n        return -1;\n    }\n\n"
        text = replace_once(text, anchor, validation + anchor, "validate RealCUGAN fixed 2x output mode")

    # Insert the packed RGB/RGBA area resize directly after inference. At 2x
    # output size it returns without allocating or copying a second image.
    helper = r'''static bool resize_task_output(Task& task)
{
    const int model_width = task.outimage.w;
    const int model_height = task.outimage.h;
    const int channels = task.outimage.elempack;
    if (!task.outimage.data || (channels != 3 && channels != 4) ||
        task.outimage.elemsize != static_cast<size_t>(channels) ||
        task.target_width <= 0 || task.target_height <= 0 ||
        task.target_width > model_width || task.target_height > model_height)
    {
        fprintf(stderr, "target resize rejected: model=%dx%d target=%dx%d channels=%d elem=%zu\n",
                model_width, model_height, task.target_width, task.target_height,
                channels, task.outimage.elemsize);
        return false;
    }

    if (task.target_width == model_width && task.target_height == model_height)
    {
        fprintf(stderr, "target-size source=%dx%d ai=%dx%d output=%dx%d resize=skipped\n",
                task.inimage.w, task.inimage.h, model_width, model_height,
                task.target_width, task.target_height);
        return true;
    }

    ncnn::Mat resized(task.target_width, task.target_height,
                      task.outimage.elemsize, task.outimage.elempack);
    if (resized.empty())
    {
        fprintf(stderr, "target resize allocation failed for %dx%d\n",
                task.target_width, task.target_height);
        return false;
    }

    const size_t source_stride = static_cast<size_t>(model_width) * task.outimage.elemsize;
    const size_t destination_stride = static_cast<size_t>(task.target_width) * resized.elemsize;
    if (!manyue_output::resize_area_u8(
            static_cast<const uint8_t*>(task.outimage.data), model_width, model_height,
            channels, source_stride, static_cast<uint8_t*>(resized.data),
            task.target_width, task.target_height, destination_stride))
    {
        fprintf(stderr, "target resize failed for %dx%d -> %dx%d\n",
                model_width, model_height, task.target_width, task.target_height);
        return false;
    }

    task.outimage = resized;
    task.resize_applied = true;
    fprintf(stderr, "target-size source=%dx%d ai=%dx%d output=%dx%d resize=area\n",
            task.inimage.w, task.inimage.h, model_width, model_height,
            task.target_width, task.target_height);
    return true;
}

'''
    proc_name = "void *proc" if variant == "realesr" else "void* proc"
    proc_start = text.index(proc_name)
    proc_end = text.index("class SaveThreadParams", proc_start)
    proc_text = text[proc_start:proc_end]
    put_count = proc_text.count("tosave.put(v);")
    if put_count != (1 if variant == "realesr" else 2):
        raise RuntimeError(f"unexpected {variant} proc output count: {put_count}")
    proc_text = proc_text.replace(
        "tosave.put(v);",
        "v.resize_failed = !resize_task_output(v);\n        tosave.put(v);",
    )
    text = text[:proc_start] + helper + proc_text + text[proc_end:]

    # Match the former Android WebP output quality for pages that actually get
    # resized, while keeping an untouched native-2x result lossless.
    webp_file = destination / "webp_image.h"
    webp_text = webp_file.read_text(encoding="utf-8")
    if "#include <limits.h>" not in webp_text:
        webp_text = replace_once(webp_text, "#include <stdlib.h>\n",
                                 "#include <stdlib.h>\n#include <limits.h>\n",
                                 "include bounds for WebP quality encoder")
    quality95_writer = r'''#ifndef _WIN32
int webp_save_quality95(const char* filepath, int w, int h, int c,
                        const unsigned char* pixeldata)
{
    if (!filepath || !pixeldata || w <= 0 || h <= 0 ||
        (c != 3 && c != 4) || w > INT_MAX / c)
        return 0;

    unsigned char* output = 0;
    const int stride = w * c;
    const size_t length = c == 3
        ? WebPEncodeRGB(pixeldata, w, h, stride, 95.0f, &output)
        : WebPEncodeRGBA(pixeldata, w, h, stride, 95.0f, &output);
    if (length == 0 || !output)
        return 0;

    FILE* fp = fopen(filepath, "wb");
    if (!fp)
    {
        WebPFree(output);
        return 0;
    }
    const bool write_ok = fwrite(output, 1, length, fp) == length;
    const bool close_ok = fclose(fp) == 0;
    WebPFree(output);
    return write_ok && close_ok ? 1 : 0;
}
#endif

'''
    webp_text = replace_once(webp_text, "#endif // WEBP_IMAGE_H",
                             quality95_writer + "#endif // WEBP_IMAGE_H",
                             "add resized WebP quality-95 writer")
    webp_file.write_text(webp_text, encoding="utf-8", newline="\n")

    # A failed final resize must never be encoded as the full 2x fallback.
    text = replace_once(
        text,
        "        int success = 0;\n",
        "        if (v.resize_failed) {\n            fprintf(stderr, \"target resize failed; output skipped\\n\");\n            continue;\n        }\n\n        int success = 0;\n",
        "skip output after failed resize",
    )
    save_start = text.index("void *save" if variant == "realesr" else "void* save")
    encode_start = text.index('        if (ext != PATHSTR("gif"))', save_start)
    encode_end = text.index("        if (success)", encode_start)
    encoder = r'''        if (ext == PATHSTR("webp") || ext == PATHSTR("WEBP"))
        {
            #if _WIN32
            success = webp_save(v.outpath.c_str(), v.outimage.w, v.outimage.h,
                                v.outimage.elempack,
                                static_cast<const unsigned char*>(v.outimage.data));
            #else
            if (v.resize_applied)
                success = webp_save_quality95(v.outpath.c_str(), v.outimage.w, v.outimage.h,
                                              v.outimage.elempack,
                                              static_cast<const unsigned char*>(v.outimage.data));
            else
                success = webp_save(v.outpath.c_str(), v.outimage.w, v.outimage.h,
                                    v.outimage.elempack,
                                    static_cast<const unsigned char*>(v.outimage.data));
            #endif
        }
        else if (ext == PATHSTR("png") || ext == PATHSTR("PNG"))
        {
#if _WIN32
            success = wic_encode_image(v.outpath.c_str(), v.outimage.w, v.outimage.h,
                                       v.outimage.elempack, v.outimage.data);
#else
            success = stbi_write_png(v.outpath.c_str(), v.outimage.w, v.outimage.h,
                                     v.outimage.elempack, v.outimage.data, 0);
#endif
        }
        else if (ext == PATHSTR("jpg") || ext == PATHSTR("JPG") ||
                 ext == PATHSTR("jpeg") || ext == PATHSTR("JPEG"))
        {
#if _WIN32
            success = wic_encode_jpeg_image(v.outpath.c_str(), v.outimage.w, v.outimage.h,
                                            v.outimage.elempack, v.outimage.data);
#else
            success = stbi_write_jpg(v.outpath.c_str(), v.outimage.w, v.outimage.h,
                                     v.outimage.elempack, v.outimage.data, 100);
#endif
        }
'''
    text = text[:encode_start] + encoder + text[encode_end:]

    # Carry the width request from the CLI into the load workers.
    text = replace_once(text, "            ltp.scale = scale;", "            ltp.scale = scale;\n            ltp.target_width = target_width;", "pass target width to workers")
    main_file.write_text(text, encoding="utf-8", newline="\n")

    model_file = destination / ("realsr.cpp" if variant == "realesr" else "realcugan.cpp")
    model_text = model_file.read_text(encoding="utf-8")
    model_header = "realsr.h" if variant == "realesr" else "realcugan.h"
    model_text = replace_once(model_text, f'#include "{model_header}"\n',
                              f'#include "{model_header}"\n#include "manyue_ncnn_status.h"\n',
                              "include ncnn status checker in model")
    model_text = replace_once(model_text, "    net.load_param(parampath.c_str());",
                              '    MANYUE_NCNN_CHECK(net.load_param(parampath.c_str()), "Net::load_param");',
                              "check Android model parameter load")
    model_text = replace_once(model_text, "    net.load_model(modelpath.c_str());",
                              '    MANYUE_NCNN_CHECK(net.load_model(modelpath.c_str()), "Net::load_model");',
                              "check Android model weight load")
    if variant == "realcugan":
        old_layer_load = "        bicubic_2x->load_param(pd);"
        new_layer_load = '        MANYUE_NCNN_CHECK(bicubic_2x->load_param(pd), "Layer::load_param");'
        if model_text.count(old_layer_load) != 1:
            raise RuntimeError("RealCUGAN: expected one bicubic_2x load_param call")
        model_text = model_text.replace(old_layer_load, new_layer_load, 1)
        for name in ("bicubic_3x", "bicubic_4x"):
            old_call = f"        {name}->load_param(pd);"
            new_call = f'        MANYUE_NCNN_CHECK({name}->load_param(pd), "Layer::load_param");'
            model_text = replace_once(model_text, old_call, new_call,
                                      f"check {name} parameter load")
            old_call = f"        {name}->create_pipeline(net.opt);"
            new_call = f'        MANYUE_NCNN_CHECK({name}->create_pipeline(net.opt), "Layer::create_pipeline");'
            model_text = replace_once(model_text, old_call, new_call,
                                      f"check {name} pipeline creation")
        old_call = "        bicubic_2x->create_pipeline(net.opt);"
        new_call = '        MANYUE_NCNN_CHECK(bicubic_2x->create_pipeline(net.opt), "Layer::create_pipeline");'
        model_text = replace_once(model_text, old_call, new_call,
                                  "check bicubic_2x pipeline creation")
    model_text, status_counts = wrap_ncnn_status_calls(model_text, variant)
    if status_counts["Extractor::input"] == 0 or status_counts["Extractor::extract"] == 0:
        raise RuntimeError(f"{variant}: incomplete Extractor status checks: {status_counts}")
    if status_counts["VkCompute::submit_and_wait"] == 0:
        raise RuntimeError(f"{variant}: missing Vulkan submit status checks")
    model_file.write_text(model_text, encoding="utf-8", newline="\n")
    print(f"{variant}: checked ncnn statuses {status_counts}")

    # Main's own thread calls must also stop the child worker when processing
    # fails; dimensions alone cannot establish that inference succeeded.
    if variant == "realesr":
        text = replace_once(
            text,
            "        realsr->process(v.inimage, v.outimage);",
            '        const int process_status = realsr->process(v.inimage, v.outimage);\n'
            '        if (process_status != 0) {\n'
            '            fprintf(stderr, "RealSR::process failed: status=%d input=%s\\n", process_status, v.inpath.c_str());\n'
            '            exit(EXIT_FAILURE);\n'
            '        }',
            "check RealSR inference status",
        )
        text = replace_once(
            text,
            "            realsr[i]->load(paramfullpath, modelfullpath);",
            '            const int load_status = realsr[i]->load(paramfullpath, modelfullpath);\n'
            '            if (load_status != 0) {\n'
            '                fprintf(stderr, "RealSR::load failed: status=%d\\n", load_status);\n'
            '                return load_status;\n'
            '            }',
            "check RealSR model load status",
        )
    else:
        for old_call, new_call, label in (
            ("            realcugan->process(v.inimage, v.outimage);",
             '            const int process_status = realcugan->process(v.inimage, v.outimage);\n'
             '            if (process_status != 0) {\n'
             '                fprintf(stderr, "RealCUGAN::process failed: status=%d input=%s\\n", process_status, v.inpath.c_str());\n'
             '                exit(EXIT_FAILURE);\n'
             '            }', "check RealCUGAN 1x inference status"),
            ("        realcugan->process(v.inimage, v.outimage);",
             '        const int process_status = realcugan->process(v.inimage, v.outimage);\n'
             '        if (process_status != 0) {\n'
             '            fprintf(stderr, "RealCUGAN::process failed: status=%d input=%s\\n", process_status, v.inpath.c_str());\n'
             '            exit(EXIT_FAILURE);\n'
             '        }', "check RealCUGAN inference status"),
        ):
            text = replace_once(text, old_call, new_call, label)
        text = replace_once(
            text,
            "            realcugan[i]->load(paramfullpath, modelfullpath);",
            '            const int load_status = realcugan[i]->load(paramfullpath, modelfullpath);\n'
            '            if (load_status != 0) {\n'
            '                fprintf(stderr, "RealCUGAN::load failed: status=%d\\n", load_status);\n'
            '                return load_status;\n'
            '            }',
            "check RealCUGAN model load status",
        )
    main_file.write_text(text, encoding="utf-8", newline="\n")

    template = cmake_template.read_text(encoding="utf-8")
    target, _cli_name, output_name = UPSTREAM_MODULES[variant]
    model_source = "realsr.cpp" if variant == "realesr" else "realcugan.cpp"
    cmake_text = (template.replace("@PROJECT_NAME@", target)
                  .replace("@MODEL_SOURCE@", model_source)
                  .replace("@OUTPUT_NAME@", output_name))
    if any(token in cmake_text for token in ("@PROJECT_NAME@", "@MODEL_SOURCE@", "@OUTPUT_NAME@")):
        raise RuntimeError("CMake template contains an unresolved token")
    (destination / "CMakeLists.txt").write_text(cmake_text, encoding="utf-8", newline="\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", type=Path, required=True,
                        help="extracted tumuyan/RealSR-NCNN-Android 1.11.1 source root")
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--variant", choices=sorted(UPSTREAM_MODULES), required=True)
    parser.add_argument("--helper-header", type=Path, required=True)
    parser.add_argument("--status-header", type=Path, required=True)
    parser.add_argument("--cmake-template", type=Path, required=True)
    args = parser.parse_args()
    patch_source(args.source_root.resolve(), args.destination.resolve(), args.variant,
                 args.helper_header.resolve(), args.status_header.resolve(),
                 args.cmake_template.resolve())


if __name__ == "__main__":
    main()
