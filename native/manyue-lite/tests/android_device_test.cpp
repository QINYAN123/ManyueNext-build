#include "manyue_lite_engine.h"
#include "stb_image.h"

#include <webp/decode.h>
#include <webp/encode.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <exception>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <map>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace fs = std::filesystem;
using Clock = std::chrono::steady_clock;

namespace {

struct Args {
    std::string model_dir;
    std::string input;
    std::string output_dir;
    std::string backend = "both";
    std::string tile = "both";
    std::string scales = "1,1.25,1.37,1.5,2";
    int warm_repeats = 3;
    int cancel_delay_ms = 30;
    double cancel_scale = 2.0;
    bool verify_alpha = false;
    bool cancel_probe = false;
    bool keep_outputs = false;
};

struct RgbaImage {
    std::vector<std::uint8_t> pixels;
    int width = 0;
    int height = 0;
};

std::uint64_t g_next_job_id = 1;

std::string json_escape(const std::string& value) {
    std::ostringstream escaped;
    for (unsigned char c : value) {
        switch (c) {
            case '"': escaped << "\\\""; break;
            case '\\': escaped << "\\\\"; break;
            case '\b': escaped << "\\b"; break;
            case '\f': escaped << "\\f"; break;
            case '\n': escaped << "\\n"; break;
            case '\r': escaped << "\\r"; break;
            case '\t': escaped << "\\t"; break;
            default:
                if (c < 0x20) {
                    escaped << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                            << static_cast<int>(c) << std::dec;
                } else {
                    escaped << static_cast<char>(c);
                }
        }
    }
    return escaped.str();
}

void emit(const std::string& json) {
    std::cout << "DEVICE_BENCH_JSON=" << json << '\n' << std::flush;
}

void usage() {
    std::cout <<
        "lite_device_test --model-dir DIR --input IMAGE --output-dir DIR [options]\n"
        "  --backend cpu|vulkan|both       default both; actual telemetry is checked\n"
        "  --tile 128|192|256|384|both     default both\n"
        "  --scales 1,1.25,1.37,1.5,2      target width ratios; default shown\n"
        "  --warm-repeats N                extra same-scale calls after first; default 3\n"
        "  --verify-alpha                  compare source/output alpha at 1x\n"
        "  --cancel-probe                  early and delayed in-flight cancel probes\n"
        "  --cancel-delay-ms N             default 30\n"
        "  --cancel-scale N                delayed cancel target ratio; default 2\n"
        "  --keep-outputs                  retain encoded WebP outputs\n";
}

std::string value_after(int& index, int argc, char** argv, const std::string& option) {
    if (index + 1 >= argc) throw std::runtime_error("missing value for " + option);
    return argv[++index];
}

Args parse_args(int argc, char** argv) {
    Args args;
    for (int i = 1; i < argc; ++i) {
        const std::string option = argv[i];
        if (option == "--help" || option == "-h") {
            usage();
            std::exit(0);
        } else if (option == "--model-dir") {
            args.model_dir = value_after(i, argc, argv, option);
        } else if (option == "--input") {
            args.input = value_after(i, argc, argv, option);
        } else if (option == "--output-dir") {
            args.output_dir = value_after(i, argc, argv, option);
        } else if (option == "--backend") {
            args.backend = value_after(i, argc, argv, option);
        } else if (option == "--tile") {
            args.tile = value_after(i, argc, argv, option);
        } else if (option == "--scales") {
            args.scales = value_after(i, argc, argv, option);
        } else if (option == "--warm-repeats") {
            args.warm_repeats = std::stoi(value_after(i, argc, argv, option));
        } else if (option == "--cancel-delay-ms") {
            args.cancel_delay_ms = std::stoi(value_after(i, argc, argv, option));
        } else if (option == "--cancel-scale") {
            args.cancel_scale = std::stod(value_after(i, argc, argv, option));
        } else if (option == "--verify-alpha") {
            args.verify_alpha = true;
        } else if (option == "--cancel-probe") {
            args.cancel_probe = true;
        } else if (option == "--keep-outputs") {
            args.keep_outputs = true;
        } else {
            throw std::runtime_error("unknown option: " + option);
        }
    }
    if (args.model_dir.empty() || args.input.empty() || args.output_dir.empty()) {
        throw std::runtime_error("--model-dir, --input and --output-dir are required");
    }
    if (args.backend != "cpu" && args.backend != "vulkan" && args.backend != "both") {
        throw std::runtime_error("--backend must be cpu, vulkan or both");
    }
    if (args.tile != "128" && args.tile != "192" && args.tile != "256" && args.tile != "384" && args.tile != "both") {
        throw std::runtime_error("--tile must be 128, 192, 256, 384 or both");
    }
    if (args.warm_repeats < 0 || args.warm_repeats > 20 || args.cancel_delay_ms < 0 ||
        args.cancel_delay_ms > 60000 || args.cancel_scale < 1.0 || args.cancel_scale > 2.0) {
        throw std::runtime_error("warm repeat or cancel option is outside the supported range");
    }
    return args;
}

std::vector<double> parse_scales(const std::string& text) {
    std::vector<double> scales;
    std::istringstream list(text);
    std::string item;
    while (std::getline(list, item, ',')) {
        if (item.empty()) continue;
        const double scale = std::stod(item);
        if (!std::isfinite(scale) || scale < 1.0 || scale > 2.0) {
            throw std::runtime_error("each scale must be in the range 1.0 through 2.0");
        }
        scales.push_back(scale);
    }
    if (scales.empty()) throw std::runtime_error("--scales did not contain a ratio");
    return scales;
}

std::vector<std::uint8_t> read_file(const fs::path& path) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file) throw std::runtime_error("cannot open file: " + path.string());
    const std::streamoff length = file.tellg();
    if (length <= 0) throw std::runtime_error("empty file: " + path.string());
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(length));
    file.seekg(0, std::ios::beg);
    if (!file.read(reinterpret_cast<char*>(bytes.data()), length)) {
        throw std::runtime_error("cannot read file: " + path.string());
    }
    return bytes;
}

std::pair<int, int> image_dimensions(const fs::path& path) {
    const std::string extension = path.extension().string();
    if (extension == ".webp" || extension == ".WEBP") {
        const auto bytes = read_file(path);
        int width = 0;
        int height = 0;
        if (!WebPGetInfo(bytes.data(), bytes.size(), &width, &height)) {
            throw std::runtime_error("WebP header could not be decoded: " + path.string());
        }
        return {width, height};
    }
    int width = 0;
    int height = 0;
    int components = 0;
    if (!stbi_info(path.string().c_str(), &width, &height, &components)) {
        throw std::runtime_error("PNG/JPEG header could not be decoded: " + path.string());
    }
    return {width, height};
}

RgbaImage decode_webp(const fs::path& path) {
    if (path.extension() == ".png") {
        RgbaImage output;
        int components = 0;
        stbi_uc* decoded = stbi_load(path.string().c_str(), &output.width, &output.height, &components, 4);
        if (!decoded) throw std::runtime_error("failed to decode generated PNG: " + path.string());
        output.pixels.assign(decoded, decoded + static_cast<std::size_t>(output.width) * output.height * 4u);
        stbi_image_free(decoded);
        return output;
    }
    const auto encoded = read_file(path);
    RgbaImage output;
    std::uint8_t* decoded = WebPDecodeRGBA(encoded.data(), encoded.size(), &output.width, &output.height);
    if (!decoded) throw std::runtime_error("failed to decode generated WebP: " + path.string());
    const std::size_t bytes = static_cast<std::size_t>(output.width) * output.height * 4u;
    output.pixels.assign(decoded, decoded + bytes);
    WebPFree(decoded);
    return output;
}

std::vector<std::uint8_t> decode_source_rgba(const fs::path& path, int width, int height) {
    int actual_width = 0;
    int actual_height = 0;
    int components = 0;
    stbi_uc* decoded = stbi_load(path.string().c_str(), &actual_width, &actual_height, &components, 4);
    if (!decoded || actual_width != width || actual_height != height) {
        if (decoded) stbi_image_free(decoded);
        throw std::runtime_error("alpha fixture must be PNG/JPEG with matching dimensions");
    }
    const std::size_t bytes = static_cast<std::size_t>(width) * height * 4u;
    std::vector<std::uint8_t> rgba(decoded, decoded + bytes);
    stbi_image_free(decoded);
    return rgba;
}

int compare_rgba(const std::vector<std::uint8_t>& left, const std::vector<std::uint8_t>& right) {
    if (left.size() != right.size()) throw std::runtime_error("RGBA images have different lengths");
    int maximum = 0;
    for (std::size_t i = 0; i < left.size(); ++i) {
        maximum = std::max(maximum, std::abs(static_cast<int>(left[i]) - static_cast<int>(right[i])));
    }
    return maximum;
}

int compare_alpha(const std::vector<std::uint8_t>& source, const std::vector<std::uint8_t>& output) {
    if (source.size() != output.size()) throw std::runtime_error("alpha output has an unexpected size");
    int maximum = 0;
    bool translucent = false;
    for (std::size_t pixel = 0; pixel < source.size() / 4u; ++pixel) {
        const int expected = source[pixel * 4u + 3u];
        const int actual = output[pixel * 4u + 3u];
        maximum = std::max(maximum, std::abs(expected - actual));
        translucent = translucent || expected != 255;
    }
    if (!translucent) throw std::runtime_error("alpha test image has no translucent pixels");
    return maximum;
}

int target_width_for(int source_width, double scale) {
    return static_cast<int>(std::floor(static_cast<double>(source_width) * scale + 0.5));
}

int target_height_for(int source_width, int source_height, int target_width) {
    return static_cast<int>((static_cast<std::int64_t>(source_height) * target_width + source_width / 2) / source_width);
}

std::string scale_label(double scale) {
    std::ostringstream label;
    label << std::fixed << std::setprecision(2) << scale << 'x';
    return label.str();
}

std::string actual_backend(const std::string& telemetry) {
    const std::string marker = "\"backend\":\"";
    const std::size_t start = telemetry.find(marker);
    if (start == std::string::npos) return "unknown";
    const std::size_t value = start + marker.size();
    const std::size_t end = telemetry.find('"', value);
    if (end == std::string::npos) return "unknown";
    return telemetry.substr(value, end - value);
}

std::vector<std::string> backends_for(const std::string& requested) {
    if (requested == "both") return {"cpu", "vulkan"};
    return {requested};
}

std::vector<int> tiles_for(const std::string& requested) {
    if (requested == "both") return {128, 192};
    return {std::stoi(requested)};
}

bool backend_matches(const std::string& requested, const std::string& actual) {
    return (requested == "vulkan" && actual == "vulkan") || (requested == "cpu" && actual == "cpu");
}

bool pending_file_exists(const fs::path& output_path, std::uint64_t job_id) {
    return fs::exists(output_path.string() + ".manyue-" + std::to_string(job_id) + ".pending");
}

bool run_cancel_probe_for(const Args& args, const std::string& requested_backend,
                          const fs::path& output_directory, int source_width, int source_height) {
    manyue_lite::Engine engine(args.tile == "both" ? 128 : std::stoi(args.tile));
    std::string init_error;
    const auto init_start = Clock::now();
    if (!engine.initialize(args.model_dir, requested_backend == "vulkan", init_error)) {
        emit("{\"event\":\"cancel_init_error\",\"requestedBackend\":\"" + requested_backend +
             "\",\"message\":\"" + json_escape(init_error) + "\"}");
        return false;
    }
    const int target_width = target_width_for(source_width, args.cancel_scale);
    const int target_height = target_height_for(source_width, source_height, target_width);
    const double init_ms = std::chrono::duration<double, std::milli>(Clock::now() - init_start).count();

    const fs::path warm_path = output_directory / ("cancel-warm-" + requested_backend + ".webp");
    const std::string warm_telemetry = engine.upscale(g_next_job_id++, args.input, warm_path.string(), source_width, 60);
    const std::string actual = actual_backend(warm_telemetry);
    fs::remove(warm_path);

    const std::uint64_t early_job = g_next_job_id++;
    const fs::path early_path = output_directory / ("cancel-early-" + requested_backend + ".webp");
    engine.cancel(early_job);
    bool early_cancelled = false;
    std::string early_error;
    try {
        (void)engine.upscale(early_job, args.input, early_path.string(), target_width, 100);
    } catch (const std::exception& failure) {
        early_error = failure.what();
        early_cancelled = early_error.find("取消") != std::string::npos ||
                          early_error.find("cancel") != std::string::npos;
    }
    const bool early_clean = !fs::exists(early_path) && !pending_file_exists(early_path, early_job);
    if (!args.keep_outputs) fs::remove(early_path);

    const std::uint64_t inflight_job = g_next_job_id++;
    const fs::path inflight_path = output_directory / ("cancel-inflight-" + requested_backend + ".webp");
    std::atomic<bool> started{false};
    std::atomic<bool> finished{false};
    bool inflight_cancelled = false;
    std::string inflight_error;
    std::string inflight_telemetry;
    std::thread worker([&] {
        started.store(true, std::memory_order_release);
        try {
            inflight_telemetry = engine.upscale(inflight_job, args.input, inflight_path.string(), target_width, 100);
        } catch (const std::exception& failure) {
            inflight_error = failure.what();
            inflight_cancelled = inflight_error.find("取消") != std::string::npos ||
                                 inflight_error.find("cancel") != std::string::npos;
        }
        finished.store(true, std::memory_order_release);
    });
    while (!started.load(std::memory_order_acquire)) std::this_thread::yield();
    std::this_thread::sleep_for(std::chrono::milliseconds(args.cancel_delay_ms));
    const bool completed_before_request = finished.load(std::memory_order_acquire);
    engine.cancel(inflight_job);
    worker.join();
    const bool inflight_clean = !fs::exists(inflight_path) && !pending_file_exists(inflight_path, inflight_job);
    if (!args.keep_outputs) fs::remove(inflight_path);
    const bool passed = early_cancelled && early_clean && !completed_before_request &&
                        inflight_cancelled && inflight_clean && backend_matches(requested_backend, actual);
    std::ostringstream event;
    event << "{\"event\":\"cancel_probe\",\"requestedBackend\":\"" << requested_backend
          << "\",\"actualBackend\":\"" << actual << "\",\"backendMatched\":"
          << (backend_matches(requested_backend, actual) ? "true" : "false")
          << ",\"backendVerifiedByWarmup\":true"
          << ",\"tile\":128,\"target\":\"" << target_width << 'x' << target_height
          << "\",\"engineInitMs\":" << init_ms << ",\"cancelDelayMs\":" << args.cancel_delay_ms
          << ",\"earlyCancelled\":" << (early_cancelled ? "true" : "false")
          << ",\"earlyOutputClean\":" << (early_clean ? "true" : "false")
          << ",\"inflightCancelled\":" << (inflight_cancelled ? "true" : "false")
          << ",\"completedBeforeCancelRequest\":" << (completed_before_request ? "true" : "false")
          << ",\"inflightOutputClean\":" << (inflight_clean ? "true" : "false")
          << ",\"passed\":" << (passed ? "true" : "false")
          << ",\"earlyError\":\"" << json_escape(early_error)
          << "\",\"inflightError\":\"" << json_escape(inflight_error) << "\"}"
          ;
    emit(event.str());
    return passed;
}

bool run_cancel_probe(const Args& args, const std::vector<std::string>& backends,
                      const fs::path& output_directory, int source_width, int source_height) {
    bool passed = true;
    for (const std::string& backend : backends) {
        passed = run_cancel_probe_for(args, backend, output_directory, source_width, source_height) && passed;
    }
    return passed;
}

bool run_benchmarks(const Args& args, const std::vector<std::string>& backends,
                    const std::vector<int>& tiles, const std::vector<double>& scales,
                    const fs::path& output_directory, int source_width, int source_height) {
    bool passed = true;
    int run_count = 0;
    int max_cpu128_delta = 0;
    std::map<std::string, RgbaImage> cpu128_reference;

    for (const std::string& requested_backend : backends) {
        for (const int tile : tiles) {
            manyue_lite::Engine engine(tile);
            std::string init_error;
            const auto init_start = Clock::now();
            if (!engine.initialize(args.model_dir, requested_backend == "vulkan", init_error)) {
                emit("{\"event\":\"engine_init_error\",\"requestedBackend\":\"" + requested_backend +
                     "\",\"tile\":" + std::to_string(tile) + ",\"message\":\"" + json_escape(init_error) + "\"}");
                passed = false;
                continue;
            }
            const double init_ms = std::chrono::duration<double, std::milli>(Clock::now() - init_start).count();
            std::ostringstream init_event;
            init_event << "{\"event\":\"engine_ready\",\"requestedBackend\":\"" << requested_backend
                       << "\",\"tile\":" << tile << ",\"engineInitMs\":" << init_ms << "}";
            emit(init_event.str());

            for (std::size_t scale_index = 0; scale_index < scales.size(); ++scale_index) {
                const double scale = scales[scale_index];
                const std::string label = scale_label(scale);
                const int target_width = target_width_for(source_width, scale);
                const int target_height = target_height_for(source_width, source_height, target_width);
                for (int repeat = 0; repeat <= args.warm_repeats; ++repeat) {
                    const std::uint64_t job_id = g_next_job_id++;
                    const std::string tag = requested_backend + "-tile" + std::to_string(tile) + "-" +
                        label + (repeat == 0 ? "-first" : "-warm" + std::to_string(repeat));
                    const std::string output_extension = target_width > WEBP_MAX_DIMENSION || target_height > WEBP_MAX_DIMENSION
                        ? ".png" : ".webp";
                    const fs::path output_path = output_directory / (tag + output_extension);
                    fs::remove(output_path);
                    const auto started = Clock::now();
                    std::string telemetry;
                    std::string failure_message;
                    bool succeeded = false;
                    try {
                        telemetry = engine.upscale(job_id, args.input, output_path.string(), target_width, 100);
                        succeeded = true;
                    } catch (const std::exception& failure) {
                        failure_message = failure.what();
                    }
                    const double wall_ms = std::chrono::duration<double, std::milli>(Clock::now() - started).count();
                    const std::string actual = actual_backend(telemetry);
                    const bool matched = backend_matches(requested_backend, actual);
                    bool output_ok = succeeded && fs::exists(output_path) && !pending_file_exists(output_path, job_id);
                    std::uintmax_t output_bytes = 0;
                    int cpu128_delta = -1;
                    bool alpha_ok = true;
                    std::string validation_error;
                    if (output_ok) {
                        try {
                            output_bytes = fs::file_size(output_path);
                            const auto dimensions = image_dimensions(output_path);
                            if (dimensions.first != target_width || dimensions.second != target_height) {
                                throw std::runtime_error("Encoded target dimensions differ from the requested output");
                            }
                            if (repeat == 0 && requested_backend == "cpu" &&
                                (tile == 128 || telemetry.find("\"outputTile\":128") != std::string::npos)) {
                                cpu128_reference[label] = decode_webp(output_path);
                                cpu128_delta = 0;
                            } else if (repeat == 0) {
                                const auto baseline = cpu128_reference.find(label);
                                if (baseline != cpu128_reference.end()) {
                                    const RgbaImage actual_pixels = decode_webp(output_path);
                                    cpu128_delta = compare_rgba(baseline->second.pixels, actual_pixels.pixels);
                                    max_cpu128_delta = std::max(max_cpu128_delta, cpu128_delta);
                                }
                            }
                            if (args.verify_alpha && scale_index == 0 && repeat == 0) {
                                const std::vector<std::uint8_t> source = decode_source_rgba(args.input, source_width, source_height);
                                const RgbaImage decoded_output = decode_webp(output_path);
                                const int alpha_delta = compare_alpha(source, decoded_output.pixels);
                                alpha_ok = alpha_delta == 0;
                                if (!alpha_ok) validation_error = "alpha max byte delta=" + std::to_string(alpha_delta);
                            }
                        } catch (const std::exception& failure) {
                            output_ok = false;
                            validation_error = failure.what();
                        }
                    } else if (succeeded) {
                        validation_error = "output or pending-file state is invalid";
                    }
                    const bool run_passed = succeeded && output_ok && matched && alpha_ok &&
                        (cpu128_delta < 0 || cpu128_delta <= 1);
                    passed = passed && run_passed && (cpu128_delta < 0 || cpu128_delta <= 1);
                    ++run_count;

                    std::ostringstream event;
                    event << "{\"event\":\"run\",\"requestedBackend\":\"" << requested_backend
                          << "\",\"actualBackend\":\"" << actual << "\",\"backendMatched\":"
                          << (matched ? "true" : "false") << ",\"tile\":" << tile
                          << ",\"scale\":\"" << label << "\",\"targetWidth\":" << target_width
                          << ",\"targetHeight\":" << target_height
                          << ",\"pass\":" << (scale_index == 0 && repeat == 0 ? "\"firstAfterInit\"" :
                                (repeat == 0 ? "\"firstAtScale\"" : "\"warmRepeat\""))
                          << ",\"repeat\":" << repeat << ",\"engineInitMs\":" << init_ms
                          << ",\"wallMs\":" << wall_ms << ",\"outputBytes\":" << output_bytes
                          << ",\"outputVerified\":" << (output_ok ? "true" : "false")
                          << ",\"alphaVerified\":" << (alpha_ok ? "true" : "false")
                          << ",\"cpu128RgbaMaxDelta\":";
                    if (cpu128_delta < 0) event << "null"; else event << cpu128_delta;
                    event << ",\"passed\":" << (run_passed ? "true" : "false")
                          << ",\"error\":\"" << json_escape(failure_message.empty() ? validation_error : failure_message)
                          << "\",\"telemetry\":";
                    if (telemetry.empty()) event << "null"; else event << telemetry;
                    event << '}';
                    emit(event.str());

                    if (!args.keep_outputs) fs::remove(output_path);
                }
            }
        }
    }

    std::ostringstream summary;
    summary << "{\"event\":\"summary\",\"passed\":" << (passed ? "true" : "false")
            << ",\"sourceWidth\":" << source_width << ",\"sourceHeight\":" << source_height
            << ",\"requestedBackends\":[";
    for (std::size_t i = 0; i < backends.size(); ++i) {
        if (i) summary << ',';
        summary << '"' << backends[i] << '"';
    }
    summary << "],\"tiles\":[";
    for (std::size_t i = 0; i < tiles.size(); ++i) {
        if (i) summary << ',';
        summary << tiles[i];
    }
    summary << "],\"scales\":[";
    for (std::size_t i = 0; i < scales.size(); ++i) {
        if (i) summary << ',';
        summary << scales[i];
    }
    summary << "],\"warmRepeatsPerScale\":" << args.warm_repeats
            << ",\"runs\":" << run_count << ",\"maxRgbaDeltaVsCpu128\":" << max_cpu128_delta << '}';
    emit(summary.str());
    return passed;
}

bool run_cancel_probes(const Args& args, const std::vector<std::string>& backends,
                       const fs::path& output_directory, int source_width, int source_height) {
    return run_cancel_probe(args, backends, output_directory, source_width, source_height);
}

} // namespace

int main(int argc, char** argv) {
    try {
        const Args args = parse_args(argc, argv);
        const fs::path model_dir(args.model_dir);
        const fs::path input_path(args.input);
        const fs::path output_dir(args.output_dir);
        if (!fs::exists(model_dir) || !fs::exists(input_path)) {
            throw std::runtime_error("model directory or input file does not exist");
        }
        fs::create_directories(output_dir);
        const auto dimensions = image_dimensions(input_path);
        const int source_width = dimensions.first;
        const int source_height = dimensions.second;
        if (source_width <= 0 || source_height <= 0 ||
            static_cast<std::int64_t>(source_width) * source_height > 12000000) {
            throw std::runtime_error("source image dimensions exceed Lite engine limits");
        }

        const std::vector<std::string> backends = backends_for(args.backend);
        if (args.cancel_probe) {
            const bool passed = run_cancel_probes(args, backends, output_dir, source_width, source_height);
            emit(std::string("{\"event\":\"cancel_summary\",\"passed\":") + (passed ? "true}" : "false}"));
            return passed ? 0 : 4;
        }
        const std::vector<int> tiles = tiles_for(args.tile);
        const std::vector<double> scales = parse_scales(args.scales);
        const bool passed = run_benchmarks(args, backends, tiles, scales, output_dir,
                                           source_width, source_height);
        return passed ? 0 : 3;
    } catch (const std::exception& failure) {
        emit("{\"event\":\"fatal\",\"passed\":false,\"message\":\"" + json_escape(failure.what()) + "\"}");
        return 2;
    }
}
