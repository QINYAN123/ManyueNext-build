#!/usr/bin/env python3
"""Create a resident-session source stage from a fresh patched CLI stage.

This is a source transformer only. It never invokes build or device tooling and
refuses to write generated source files inside the repository.
"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path


HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


def within(path: Path, parent: Path) -> bool:
    try:
        path.resolve().relative_to(parent.resolve())
        return True
    except ValueError:
        return False


def apply_resident_patch(variant: str, output_dir: Path) -> None:
    main_file = output_dir / "main.cpp"
    source = main_file.read_text(encoding="utf-8")

    # Keep the existing one-file CLI intact when -S is absent. Only add a
    # protocol mode and a branch after model loading.
    source = replace_once(
        source,
        "#include <iostream>\n",
        "#include <iostream>\n#include <chrono>\n"
        "#include <fstream>\n"
        "#if !defined(_WIN32)\n#include <sys/stat.h>\n#endif\n"
        '#include "manyue_resident_protocol.h"\n',
        "include resident protocol and monotonic clock",
    )
    task_anchor = "class Task\n{\npublic:\n    int id;\n" if variant == "realcugan" else "class Task {\npublic:\n    int id;\n"
    task_fields = (
        "    uint64_t request_id;\n"
        "    uint64_t request_start_us;\n"
        "    uint64_t decode_us;\n"
        "    uint64_t infer_us;\n"
        "    uint64_t resize_us;\n"
        "    uint64_t encode_us;\n"
    )
    source = replace_once(source, task_anchor, task_anchor + task_fields,
                          "add resident request timing to Task")
    if variant == "realesr":
        source = replace_once(
            source,
            "    path_t outpath;\n",
            "    path_t outpath;\n    std::string cancel_path;\n",
            "add request-scoped RealSR cancellation marker to Task",
        )

    queue_anchor = "TaskQueue toproc;\nTaskQueue tosave;\n"
    globals_block = r'''TaskQueue toproc;
TaskQueue tosave;

static bool resident_session_mode = false;
static int resident_protocol_fd = -1;
static uint64_t resident_init_start_us = 0;
static uint32_t resident_gpu_backend = 0;
static uint32_t resident_tile_size = 0;
static manyue_resident::ResponseQueue resident_responses;

static uint64_t resident_now_us()
{
    return (uint64_t)std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

static void resident_release_input(Task& task);
static void resident_report_task(Task& task, int status);
static void resident_report_task_success(Task& task);
static void resident_report_startup_failure(int status);
static bool resident_cancel_requested(const std::string& path);
'''
    source = replace_once(source, queue_anchor, globals_block,
                          "declare resident protocol state and metrics")

    # Make primitive fields deterministic for both legacy and sentinel tasks.
    field_marker = "    ncnn::Mat outimage;\n"
    if variant == "realesr":
        field_marker += "    ncnn::Mat in;\n"
    source = replace_once(
        source,
        field_marker,
        field_marker + "\n    Task() : id(0), request_id(0), request_start_us(0), "
        "decode_us(0), infer_us(0), resize_us(0), encode_us(0), webp(0), "
        + ("scale(0), " if variant == "realcugan" else "")
        + "target_width(0), target_height(0), resize_failed(false), "
        "resize_applied(false) {}\n",
        "initialize Task fields safely",
    )

    # Inference status failure and final output resize failure are recoverable
    # per request in resident mode. The legacy CLI keeps its original exit path.
    import re

    process_pattern = re.compile(
        r"(?P<indent>^[ \t]*)const int process_status = "
        r"(?P<call>(?:realcugan|realsr)->process\(v\.inimage, v\.outimage\));",
        re.MULTILINE,
    )

    def process_replacement(match: re.Match[str]) -> str:
        indent = match.group("indent")
        process_call = match.group("call")
        cancel_branch = ""
        if variant == "realesr":
            process_call = process_call.replace(
                "v.inimage, v.outimage)",
                "v.inimage, v.outimage, resident_session_mode ? v.cancel_path.c_str() : nullptr)",
                1,
            )
            cancel_branch = (
                f"{indent}if (resident_session_mode && (process_status == manyue_resident::kCancelled || "
                f"(process_status == 0 && resident_cancel_requested(v.cancel_path)))) {{\n"
                f"{indent}    resident_release_input(v);\n"
                f"{indent}    remove(v.outpath.c_str());\n"
                f"{indent}    resident_report_task(v, manyue_resident::kCancelled);\n"
                f"{indent}    continue;\n"
                f"{indent}}}\n"
            )
        return (
            f"{indent}if (resident_session_mode && v.outimage.empty()) {{\n"
            f"{indent}    resident_release_input(v);\n"
            f"{indent}    resident_report_task(v, manyue_resident::kInferenceError);\n"
            f"{indent}    continue;\n"
            f"{indent}}}\n"
            f"{indent}const uint64_t infer_start_us = resident_session_mode ? resident_now_us() : 0;\n"
            f"{indent}const int process_status = {process_call};\n"
            f"{indent}if (resident_session_mode) v.infer_us = resident_now_us() - infer_start_us;\n"
            + cancel_branch
            +
            f"{indent}if (resident_session_mode && process_status != 0) {{\n"
            f"{indent}    resident_release_input(v);\n"
            f"{indent}    resident_report_task(v, manyue_resident::kInferenceError);\n"
            f"{indent}    continue;\n"
            f"{indent}}}"
        )

    source, process_count = process_pattern.subn(process_replacement, source)
    if process_count != (2 if variant == "realcugan" else 1):
        raise RuntimeError(f"{variant}: expected proc inference anchors, found {process_count}")

    resize_pattern = re.compile(
        r"(?P<indent>^[ \t]*)v\.resize_failed = !resize_task_output\(v\);",
        re.MULTILINE,
    )

    def resize_replacement(match: re.Match[str]) -> str:
        indent = match.group("indent")
        cancel_branch = ""
        if variant == "realesr":
            cancel_branch = (
                f"{indent}if (resident_session_mode && resident_cancel_requested(v.cancel_path)) {{\n"
                f"{indent}    resident_release_input(v);\n"
                f"{indent}    remove(v.outpath.c_str());\n"
                f"{indent}    resident_report_task(v, manyue_resident::kCancelled);\n"
                f"{indent}    continue;\n"
                f"{indent}}}\n"
            )
        return (
            f"{indent}const uint64_t resize_start_us = resident_session_mode ? resident_now_us() : 0;\n"
            f"{indent}v.resize_failed = !resize_task_output(v);\n"
            f"{indent}if (resident_session_mode) v.resize_us = resident_now_us() - resize_start_us;\n"
            + cancel_branch
            +
            f"{indent}if (resident_session_mode && v.resize_failed) {{\n"
            f"{indent}    resident_release_input(v);\n"
            f"{indent}    resident_report_task(v, manyue_resident::kResizeError);\n"
            f"{indent}    continue;\n"
            f"{indent}}}"
        )

    source, resize_count = resize_pattern.subn(resize_replacement, source)
    if resize_count != (2 if variant == "realcugan" else 1):
        raise RuntimeError(f"{variant}: expected proc resize anchors, found {resize_count}")

    # Keep the normal encoder branch byte-for-byte. Add per-request reporting
    # around it, including cleanup of failed writes and empty outputs.
    save_name = "void *save(void *args)" if variant == "realesr" else "void* save(void* args)"
    save_start = source.index(save_name)
    main_anchor = ("#if _WIN32\nconst std::wstring& optarg_in"
                   if variant == "realesr" else "#if _WIN32\nint wmain")
    main_start = source.index(main_anchor, save_start)
    save_text = source[save_start:main_start]
    sentinel = "        if (v.id == -233)\n            break;\n"
    save_text = replace_once(
        save_text,
        sentinel,
        sentinel
        + (
            "\n        if (resident_session_mode && resident_cancel_requested(v.cancel_path)) {\n"
            "            resident_release_input(v);\n"
            "            remove(v.outpath.c_str());\n"
            "            resident_report_task(v, manyue_resident::kCancelled);\n"
            "            continue;\n"
            "        }\n"
            if variant == "realesr" else "\n"
        )
        + "        const uint64_t encode_start_us = resident_session_mode ? resident_now_us() : 0;\n",
        f"{variant} time resident output encoding",
    )
    if variant == "realesr":
        resize_failure = (
            '        if (v.resize_failed) {\n'
            '            fprintf(stderr, "target resize failed; worker aborting\\n");\n'
            '            remove(v.outpath.c_str());\n'
            '            exit(EXIT_FAILURE);\n'
            '        }'
        )
        resize_failure_new = (
            '        if (v.resize_failed) {\n'
            '            remove(v.outpath.c_str());\n'
            '            if (resident_session_mode) {\n'
            '                resident_report_task(v, manyue_resident::kResizeError);\n'
            '                continue;\n'
            '            }\n'
            '            fprintf(stderr, "target resize failed; worker aborting\\n");\n'
            '            exit(EXIT_FAILURE);\n'
            '        }'
        )
        failed_anchor = (
            '        } else {\n'
            '#if _WIN32\n'
            '            fwprintf(stderr, L"save result failed: %ls\\n", v.outpath.c_str());\n'
            '#else\n'
            '            fprintf(stderr, "save result failed: %s\\n", v.outpath.c_str());\n'
            '            remove(v.outpath.c_str());\n'
            '            exit(EXIT_FAILURE);\n'
            '#endif\n'
            '        }'
        )
        failed_new = (
            '        } else {\n'
            '            if (resident_session_mode) {\n'
            '                remove(v.outpath.c_str());\n'
            '                resident_report_task(v, manyue_resident::kEncodeError);\n'
            '                continue;\n'
            '            }\n'
            '#if _WIN32\n'
            '            fwprintf(stderr, L"save result failed: %ls\\n", v.outpath.c_str());\n'
            '#else\n'
            '            fprintf(stderr, "save result failed: %s\\n", v.outpath.c_str());\n'
            '            remove(v.outpath.c_str());\n'
            '            exit(EXIT_FAILURE);\n'
            '#endif\n'
            '        }'
        )
    else:
        resize_failure = (
            '        if (v.resize_failed) {\n'
            '            fprintf(stderr, "target resize failed; worker aborting\\n");\n'
            '            remove(v.outpath.c_str());\n'
            '            exit(EXIT_FAILURE);\n'
            '        }'
        )
        resize_failure_new = (
            '        if (v.resize_failed) {\n'
            '            remove(v.outpath.c_str());\n'
            '            if (resident_session_mode) {\n'
            '                resident_report_task(v, manyue_resident::kResizeError);\n'
            '                continue;\n'
            '            }\n'
            '            fprintf(stderr, "target resize failed; worker aborting\\n");\n'
            '            exit(EXIT_FAILURE);\n'
            '        }'
        )
        failed_anchor = (
            '        }\n        else\n        {\n'
            '#if _WIN32\n'
            '            fwprintf(stderr, L"encode image %ls failed\\n", v.outpath.c_str());\n'
            '#else\n'
            '            fprintf(stderr, "encode image %s failed\\n", v.outpath.c_str());\n'
            '            remove(v.outpath.c_str());\n'
            '            exit(EXIT_FAILURE);\n'
            '#endif\n'
            '        }'
        )
        failed_new = (
            '        }\n        else\n        {\n'
            '            if (resident_session_mode) {\n'
            '                remove(v.outpath.c_str());\n'
            '                resident_report_task(v, manyue_resident::kEncodeError);\n'
            '                continue;\n'
            '            }\n'
            '#if _WIN32\n'
            '            fwprintf(stderr, L"encode image %ls failed\\n", v.outpath.c_str());\n'
            '#else\n'
            '            fprintf(stderr, "encode image %s failed\\n", v.outpath.c_str());\n'
            '            remove(v.outpath.c_str());\n'
            '            exit(EXIT_FAILURE);\n'
            '#endif\n'
            '        }'
        )
    save_text = replace_once(save_text, resize_failure, resize_failure_new,
                             f"{variant} recover from session resize failure")
    save_text = replace_once(save_text, failed_anchor, failed_new,
                             f"{variant} return session encoder errors")
    encode_end_anchor = "        if (success) {" if variant == "realesr" else "        if (success)\n        {"
    save_text = replace_once(
        save_text, encode_end_anchor,
        "        if (resident_session_mode) v.encode_us = resident_now_us() - encode_start_us;\n" + encode_end_anchor,
        f"{variant} record encoder duration",
    )
    save_text = replace_once(
        save_text,
        "\n    }\n\n    return 0;\n}",
        "\n        if (resident_session_mode && success)\n            resident_report_task_success(v);\n"
        "    }\n\n    return 0;\n}",
        f"{variant} report successful session output",
    )
    source = source[:save_start] + save_text + source[main_start:]

    if variant == "realesr":
        engine_file = output_dir / "realsr.cpp"
        header_file = output_dir / "realsr.h"
        engine = engine_file.read_text(encoding="utf-8")
        header = header_file.read_text(encoding="utf-8")
        header = replace_once(
            header,
            "    int process(const ncnn::Mat& inimage, ncnn::Mat& outimage) const;\n",
            "    int process(const ncnn::Mat& inimage, ncnn::Mat& outimage, const char* cancel_path = nullptr) const;\n",
            "add optional RealSR cancel marker argument",
        )
        header = replace_once(
            header,
            "    int process_cpu(const ncnn::Mat& inimage, ncnn::Mat& outimage) const;\n",
            "    int process_cpu(const ncnn::Mat& inimage, ncnn::Mat& outimage, const char* cancel_path = nullptr) const;\n",
            "add optional CPU cancel marker argument",
        )
        engine = replace_once(
            engine,
            '#include "manyue_ncnn_status.h"\n',
            '#include "manyue_ncnn_status.h"\n'
            '#include "manyue_resident_protocol.h"\n'
            "#include <fstream>\n",
            "include resident cancel status and marker reader",
        )
        engine = replace_once(
            engine,
            "RealSR::RealSR(int gpuid, bool _tta_mode, int num_threads)\n",
            "static bool manyue_resident_cancel_requested(const char* path)\n"
            "{\n"
            "    if (!path || !path[0])\n"
            "        return false;\n"
            "    std::ifstream marker(path, std::ios::binary);\n"
            "    return marker.good();\n"
            "}\n\n"
            "RealSR::RealSR(int gpuid, bool _tta_mode, int num_threads)\n",
            "add request-scoped RealSR marker helper",
        )
        engine = replace_once(
            engine,
            "int RealSR::process(const ncnn::Mat& inimage, ncnn::Mat& outimage) const\n",
            "int RealSR::process(const ncnn::Mat& inimage, ncnn::Mat& outimage, const char* cancel_path) const\n",
            "add resident marker to RealSR process",
        )
        engine = replace_once(
            engine,
            "int RealSR::process_cpu(const ncnn::Mat& inimage, ncnn::Mat& outimage) const\n",
            "int RealSR::process_cpu(const ncnn::Mat& inimage, ncnn::Mat& outimage, const char* cancel_path) const\n",
            "add resident marker to CPU process",
        )
        engine = replace_once(
            engine,
            "int RealSR::process(const ncnn::Mat& inimage, ncnn::Mat& outimage, const char* cancel_path) const\n{\n",
            "int RealSR::process(const ncnn::Mat& inimage, ncnn::Mat& outimage, const char* cancel_path) const\n{\n"
            "    if (manyue_resident_cancel_requested(cancel_path))\n"
            "        return manyue_resident::kCancelled;\n\n",
            "check for cancellation before dispatch",
        )
        engine = replace_once(
            engine,
            "return process_cpu(inimage, outimage);",
            "return process_cpu(inimage, outimage, cancel_path);",
            "forward marker to CPU fallback",
        )
        cpu_start = engine.index("int RealSR::process_cpu(")
        cpu_region = engine[cpu_start:]
        cpu_loop = "        for (int xi = 0; xi < xtiles; xi++)\n        {\n"
        cpu_region = replace_once(
            cpu_region,
            cpu_loop,
            cpu_loop
            + "            if (manyue_resident_cancel_requested(cancel_path))\n"
            + "                return manyue_resident::kCancelled;\n",
            "poll marker between CPU tiles",
        )
        cpu_return = "    return 0;\n}"
        if cpu_region.count(cpu_return) != 1:
            raise RuntimeError("RealSR CPU cancel patch: expected one process_cpu return")
        cpu_region = cpu_region.replace(
            cpu_return,
            "    if (manyue_resident_cancel_requested(cancel_path))\n"
            "        return manyue_resident::kCancelled;\n"
            "    return 0;\n}",
            1,
        )
        engine = engine[:cpu_start] + cpu_region
        engine_file.write_text(engine, encoding="utf-8", newline="\n")
        header_file.write_text(header, encoding="utf-8", newline="\n")

    # Add resident flag parsing, but leave the legacy path untouched by default.
    if variant == "realesr":
        opt_old = '"i:o:s:c:t:m:g:j:f:w:vxh"'
        opt_new = '"i:o:s:c:t:m:g:j:f:w:Svxh"'
        cases = (
            "        case L'S':\n            resident_session_mode = true;\n            break;\n",
            "            case 'S':\n                resident_session_mode = true;\n                break;\n",
        )
        init_anchor = "    if (target_width <= 0 || scale != 2)"
    else:
        opt_old = '"i:o:n:s:t:c:m:g:j:f:w:vxh"'
        opt_new = '"i:o:n:s:t:c:m:g:j:f:w:Svxh"'
        cases = (
            "        case L'S':\n            resident_session_mode = true;\n            break;\n",
            "        case 'S':\n            resident_session_mode = true;\n            break;\n",
        )
        init_anchor = "    if (target_width <= 0 || scale != 2)"
    if source.count(opt_old) != 2:
        raise RuntimeError(f"{variant}: expected two getopt option strings")
    source = source.replace(opt_old, opt_new)
    source = replace_once(source, "        case L'h':\n" if variant == "realcugan" else "        case L'h':\n",
                          cases[0] + "        case L'h':\n",
                          f"{variant} add wide -S switch")
    if variant == "realesr":
        narrow_anchor = "            case 'h':\n"
    else:
        narrow_anchor = "        case 'h':\n"
    source = replace_once(source, narrow_anchor, cases[1] + narrow_anchor,
                          f"{variant} add -S switch")
    post_parse = r'''    if (resident_session_mode) {
        resident_protocol_fd = manyue_resident::redirect_stdout_to_stderr();
        if (resident_protocol_fd < 0) {
            fprintf(stderr, "resident protocol stdout setup failed\n");
            return -1;
        }
        if (inputpath.empty()) inputpath = PATHSTR("/__manyue_resident_input__.png");
        if (outputpath.empty()) outputpath = PATHSTR("/__manyue_resident_output__.webp");
        if (target_width <= 0) target_width = 1; // Each frame supplies its own width.
        resident_init_start_us = resident_now_us();
    }

    if (target_width <= 0 || scale != 2)'''
    source = replace_once(source, init_anchor,
                          post_parse,
                          f"{variant} initialize session mode after parsing")

    # Session mode uses one persistent proc worker per selected device and one
    # persistent saver. Request processing itself remains serialized.
    gpu_anchor = ("    const int use_gpu_count = (int) gpuid.size();\n"
                  if variant == "realesr" else
                  "    const int use_gpu_count = (int)gpuid.size();\n")
    gpu_new = (gpu_anchor +
               "\n    if (resident_session_mode) {\n"
               "        jobs_proc.assign(use_gpu_count, 1);\n"
               "        jobs_save = 1;\n"
               "    }\n")
    source = replace_once(source, gpu_anchor, gpu_new,
                          f"{variant} keep one persistent worker per device")

    create_gpu_anchor = "    ncnn::create_gpu_instance();\n"
    source = replace_once(source, create_gpu_anchor,
                          "    if (resident_session_mode) resident_init_start_us = resident_now_us();\n"
                          + create_gpu_anchor,
                          f"{variant} start model initialization metric")

    load_failure = (
        '            if (load_status != 0) {\n'
        f'                fprintf(stderr, "{("RealSR" if variant == "realesr" else "RealCUGAN")}::load failed: status=%d\\n", load_status);\n'
        '                return load_status;\n'
        '            }'
    )
    load_failure_new = load_failure.replace(
        "                return load_status;",
        "                if (resident_session_mode) resident_report_startup_failure(manyue_resident::kModelInitError);\n"
        "                return load_status;",
    )
    source = replace_once(source, load_failure, load_failure_new,
                          f"{variant} report model initialization failure")

    # Route session mode around the original batch loader. Its CLI remains in
    # the else branch with the same worker counts and command-line semantics.
    routine_anchor = "        // main routine\n        {\n            // load image"
    if variant == "realesr":
        routine_new = (
            "        if (resident_session_mode) {\n"
            "            run_resident_session(realsr, gpuid, jobs_proc, tilesize,\n"
            "                                 total_jobs_proc, scale, verbose, 0);\n"
            "        } else {\n"
            "        // main routine\n        {\n            // load image"
        )
        cleanup_anchor = (
            "        }\n\n        for (int i = 0; i < use_gpu_count; i++) {\n"
            "            delete realsr[i];"
        )
    else:
        routine_new = (
            "        if (resident_session_mode) {\n"
            "            run_resident_session(realcugan, gpuid, jobs_proc, tilesize,\n"
            "                                 total_jobs_proc, scale, verbose, 0);\n"
            "        } else {\n"
            "        // main routine\n        {\n            // load image"
        )
        cleanup_anchor = (
            "        }\n\n        for (int i=0; i<use_gpu_count; i++)\n"
            "        {\n            delete realcugan[i];"
        )
    source = replace_once(source, routine_anchor, routine_new,
                          f"{variant} branch to persistent session runner")
    source = replace_once(source, cleanup_anchor,
                          cleanup_anchor.replace("\n\n        for", "\n        }\n\n        for"),
                          f"{variant} close session/CLI branch")

    # Generate a variant-specific include that provides robust frame-driven
    # decode, file cleanup, result framing, and worker lifetime management.
    runtime = (HERE / "manyue_resident_session.inc.in").read_text(encoding="utf-8")
    runtime = runtime.replace("@MODEL_TYPE@", "RealSR" if variant == "realesr" else "RealCUGAN")
    runtime = runtime.replace("@MODEL_FIELD@", "realsr" if variant == "realesr" else "realcugan")
    runtime = runtime.replace("@WEBP_FLAG@", "webp" if variant == "realcugan" else "0")
    runtime = runtime.replace(
        "@TASK_SCALE_ASSIGNMENT@",
        "    task->scale = scale;\n" if variant == "realcugan" else "",
    )
    runtime = runtime.replace(
        "@TASK_CANCEL_PATH_ASSIGNMENT@",
        "    task->cancel_path = request.output_path + \".cancel\";\n"
        if variant == "realesr" else "",
    )
    output_image_init = (
        "    if (width > INT_MAX / scale || height > INT_MAX / scale) {\n"
        "        resident_release_input(*task);\n"
        "        return finish(manyue_resident::kInputError);\n"
        "    }\n"
        "    task->outimage = ncnn::Mat(width * scale, height * scale,\n"
        "                              (size_t)channels, channels);\n"
        "    if (task->outimage.empty()) {\n"
        "        resident_release_input(*task);\n"
        "        return finish(manyue_resident::kInputError);\n"
        "    }\n"
        if variant == "realesr" else ""
    )
    runtime = runtime.replace("@OUTIMAGE_INIT@", output_image_init)
    runtime = runtime.replace(
        "    save_params.check_threshold = check_threshold;\n",
        ("    save_params.check_threshold = check_threshold;\n" if variant == "realesr" else ""),
    )
    runtime = runtime.replace(
        "#endif\n",
        "#endif\n",
        1,
    )
    runtime = runtime.replace(
        "static void resident_release_input(Task& task)\n",
        "static void resident_release_input(Task& task)\n",
        1,
    )
    if variant == "realcugan":
        runtime = runtime.replace(
            "    save_params.check_threshold = check_threshold;\n", "", 1)
    include_anchor = ("#if _WIN32\nconst std::wstring& optarg_in"
                      if variant == "realesr" else "#if _WIN32\nint wmain")
    (output_dir / "manyue_resident_session.inc").write_text(
        runtime, encoding="utf-8", newline="\n")
    source = replace_once(source, include_anchor,
                          '#include "manyue_resident_session.inc"\n\n' + include_anchor,
                          f"{variant} add resident session runtime")

    main_file.write_text(source, encoding="utf-8", newline="\n")


def verify_candidate(module: Path, variant: str) -> None:
    engine_name = "realsr.cpp" if variant == "realesr" else "realcugan.cpp"
    main = (module / "main.cpp").read_text(encoding="utf-8")
    engine = (module / engine_name).read_text(encoding="utf-8")
    if engine.count("net.opt.use_fp16_arithmetic = false;") != 1:
        raise RuntimeError(f"{variant} candidate is not the shipped FP32 arithmetic path")
    if "use_fp16_arithmetic = vkdev != nullptr" in engine:
        raise RuntimeError(f"{variant} candidate contains the experimental FP16 scratch change")
    if "MANYUE_PROFILE" in main or "class ProfileSpan" in main:
        raise RuntimeError(f"{variant} candidate unexpectedly contains profiler scratch edits")
    if "manyue_output::save_fast_lossless_webp" not in main:
        raise RuntimeError(f"{variant} lost the fast lossless WebP path")
    if "webp_save_quality95" in main:
        raise RuntimeError(f"{variant} contains the obsolete quality-95 WebP path")
    protocol = (module / "manyue_resident_protocol.h").read_text(encoding="utf-8")
    runtime = (module / "manyue_resident_session.inc").read_text(encoding="utf-8")
    if "@TASK_SCALE_ASSIGNMENT@" in runtime:
        raise RuntimeError(f"{variant} candidate contains an unexpanded Task scale placeholder")
    if "@TASK_CANCEL_PATH_ASSIGNMENT@" in runtime:
        raise RuntimeError(f"{variant} candidate contains an unexpanded cancel-path placeholder")
    if variant == "realcugan" and "task->scale = scale;" not in runtime:
        raise RuntimeError("realcugan candidate lost its per-task scale assignment")
    if variant == "realesr" and "task->scale = scale;" in runtime:
        raise RuntimeError("realesr candidate assigns a Task field it does not have")
    if variant == "realesr" and (
        "task->cancel_path = request.output_path + \".cancel\";" not in runtime or
        "manyue_resident::kCancelled" not in main or
        "const char* cancel_path" not in engine
    ):
        raise RuntimeError("realesr candidate lost request-scoped cooperative cancellation")
    if "case 'S'" not in main or "manyue_resident_protocol.h" not in main or \
            "MNYQ" not in protocol or "MNYR" not in protocol or \
            "run_resident_session" not in runtime:
        raise RuntimeError(f"{variant} resident protocol anchors are missing")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", type=Path, required=True,
                        help="fresh base source directory produced by patch_upstream.py")
    parser.add_argument("--destination", type=Path, required=True,
                        help="new resident source directory outside the repository")
    parser.add_argument("--variant", choices=("realesr", "realcugan"), required=True)
    args = parser.parse_args()
    source_dir = args.source_dir.resolve()
    destination = args.destination.resolve()
    if not source_dir.is_dir() or not (source_dir / "main.cpp").is_file():
        raise FileNotFoundError(f"base source directory is incomplete: {source_dir}")
    if destination == source_dir:
        raise RuntimeError("destination must differ from the source directory")
    if within(destination, REPO_ROOT):
        raise RuntimeError(f"generated resident sources must stay outside the repository: {destination}")
    if destination.exists():
        raise FileExistsError(f"refusing to overwrite existing destination: {destination}")

    source_main = (source_dir / "main.cpp").read_text(encoding="utf-8")
    engine_name = "realsr.cpp" if args.variant == "realesr" else "realcugan.cpp"
    source_engine = (source_dir / engine_name).read_text(encoding="utf-8")
    if "resident_session_mode" in source_main or "MANYUE_GPU_CHUNK helper:" in source_engine:
        raise RuntimeError("resident generator requires a clean output-patched base stage")

    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source_dir, destination)
    shutil.copy2(HERE / "manyue_resident_protocol.h",
                 destination / "manyue_resident_protocol.h")
    apply_resident_patch(args.variant, destination)
    verify_candidate(destination, args.variant)
    print(f"prepared resident-session {args.variant} source: {destination}")


if __name__ == "__main__":
    main()
