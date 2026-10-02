#include "../manyue_lite_engine.h"
#include "../third_party/stb_image.h"

#include <webp/decode.h>
#include <webp/encode.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iostream>
#include <map>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace fs = std::filesystem;

namespace {

std::string json_escape(const std::string& input);

struct WebpPixels {
    std::uint8_t* pixels = nullptr;
    int width = 0;
    int height = 0;
    ~WebpPixels() { WebPFree(pixels); }
    WebpPixels() = default;
    WebpPixels(const WebpPixels&) = delete;
    WebpPixels& operator=(const WebpPixels&) = delete;
};

struct CompareResult {
    int max_byte_error = 0;
    int max_seam_error = 0;
    double mean_byte_error = 0.0;
    std::uint64_t samples = 0;
    int max_x = 0;
    int max_y = 0;
    int max_channel = 0;
    int max_actual = 0;
    int max_expected = 0;
};

struct TensorCapture {
    int width = 0;
    int height = 0;
    int channels = 0;
    int origin_x = 0;
    int origin_y = 0;
    std::vector<float> planar;
};

struct TensorError {
    double max_feature = 0.0;
    double mean_feature = 0.0;
    double max_base = 0.0;
    double mean_base = 0.0;
    double max_condition = 0.0;
    double mean_condition = 0.0;
};

std::vector<float> read_float_file(const fs::path& path, std::size_t expected_values) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file) throw std::runtime_error("golden file missing: " + path.string());
    const auto length = file.tellg();
    if (length < 0 || static_cast<std::uint64_t>(length) != expected_values * sizeof(float)) {
        throw std::runtime_error("golden file has wrong byte length: " + path.string());
    }
    file.seekg(0, std::ios::beg);
    std::vector<float> values(expected_values);
    if (!file.read(reinterpret_cast<char*>(values.data()), static_cast<std::streamsize>(expected_values * sizeof(float)))) {
        throw std::runtime_error("golden read failed: " + path.string());
    }
    return values;
}

void capture_tensor(TensorCapture& destination, const float* data, int width, int height,
                    int channels, std::size_t cstep, int origin_x, int origin_y) {
    destination.width = width;
    destination.height = height;
    destination.channels = channels;
    destination.origin_x = origin_x;
    destination.origin_y = origin_y;
    destination.planar.resize(static_cast<std::size_t>(channels) * width * height);
    for (int channel = 0; channel < channels; ++channel) {
        const float* source = data + static_cast<std::size_t>(channel) * cstep;
        for (int y = 0; y < height; ++y) {
            std::copy(source + static_cast<std::size_t>(y) * width,
                      source + static_cast<std::size_t>(y + 1) * width,
                      destination.planar.begin() + static_cast<std::size_t>(channel) * width * height +
                          static_cast<std::size_t>(y) * width);
        }
    }
}

TensorError compare_tensor_capture(const TensorCapture& actual, const std::vector<float>& expected,
                                   int full_width, int full_height, int compare_width, int compare_height) {
    if (actual.width <= 0 || actual.height <= 0 || actual.channels <= 0 ||
        expected.size() != static_cast<std::size_t>(actual.channels) * full_width * full_height ||
        actual.origin_x < 0 || actual.origin_y < 0 || actual.origin_x + compare_width > full_width ||
        actual.origin_y + compare_height > full_height || compare_width > actual.width || compare_height > actual.height) {
        throw std::runtime_error("captured tensor or PyTorch golden has an invalid shape");
    }
    TensorError error;
    double feature_sum = 0.0, base_sum = 0.0, condition_sum = 0.0;
    std::uint64_t feature_count = 0, base_count = 0, condition_count = 0;
    for (int channel = 0; channel < actual.channels; ++channel) {
        const bool is_feature = actual.channels == 16 || channel < 16;
        const bool is_base = actual.channels != 16 && channel >= 16 && channel < 19;
        double* maximum = is_feature ? &error.max_feature : is_base ? &error.max_base : &error.max_condition;
        double* sum = is_feature ? &feature_sum : is_base ? &base_sum : &condition_sum;
        std::uint64_t* count = is_feature ? &feature_count : is_base ? &base_count : &condition_count;
        for (int y = 0; y < compare_height; ++y) {
            for (int x = 0; x < compare_width; ++x) {
                const float left = actual.planar[static_cast<std::size_t>(channel) * actual.width * actual.height +
                                                 static_cast<std::size_t>(y) * actual.width + x];
                const float right = expected[static_cast<std::size_t>(channel) * full_width * full_height +
                                             static_cast<std::size_t>(actual.origin_y + y) * full_width +
                                             actual.origin_x + x];
                const double difference = std::abs(static_cast<double>(left) - right);
                *maximum = std::max(*maximum, difference);
                *sum += difference;
                ++*count;
            }
        }
    }
    error.mean_feature = feature_sum / std::max<std::uint64_t>(1, feature_count);
    error.mean_base = base_sum / std::max<std::uint64_t>(1, base_count);
    error.mean_condition = condition_sum / std::max<std::uint64_t>(1, condition_count);
    return error;
}

std::vector<std::uint8_t> decode_webp(const fs::path& path, int expected_width, int expected_height) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file) throw std::runtime_error("native WebP output missing: " + path.string());
    const auto length = file.tellg();
    if (length <= 0) throw std::runtime_error("native WebP output is empty");
    std::vector<std::uint8_t> encoded(static_cast<std::size_t>(length));
    file.seekg(0, std::ios::beg);
    if (!file.read(reinterpret_cast<char*>(encoded.data()), static_cast<std::streamsize>(encoded.size()))) {
        throw std::runtime_error("native WebP output read failed");
    }
    WebpPixels decoded;
    decoded.pixels = WebPDecodeRGBA(encoded.data(), encoded.size(), &decoded.width, &decoded.height);
    if (!decoded.pixels || decoded.width != expected_width || decoded.height != expected_height) {
        throw std::runtime_error("native WebP output dimensions are wrong");
    }
    const std::size_t size = static_cast<std::size_t>(decoded.width) * decoded.height * 4u;
    return std::vector<std::uint8_t>(decoded.pixels, decoded.pixels + size);
}

std::uint8_t quantize(float value) {
    value = std::max(0.0f, std::min(1.0f, value));
    return static_cast<std::uint8_t>(std::floor(value * 255.0f + 0.5f));
}

CompareResult compare_to_golden(const std::vector<std::uint8_t>& actual,
                                const std::vector<float>& expected,
                                int width, int height, int tile_side) {
    CompareResult result;
    long double sum = 0.0;
    const std::size_t plane = static_cast<std::size_t>(width) * height;
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const std::size_t pixel = static_cast<std::size_t>(y) * width + x;
            const bool seam = (x % tile_side) < 2 || (x % tile_side) >= tile_side - 2 ||
                (y % tile_side) < 2 || (y % tile_side) >= tile_side - 2;
            for (int channel = 0; channel < 3; ++channel) {
                const int expected_byte = quantize(expected[static_cast<std::size_t>(channel) * plane + pixel]);
                const int error = std::abs(static_cast<int>(actual[pixel * 4u + channel]) - expected_byte);
                if (error > result.max_byte_error) {
                    result.max_byte_error = error;
                    result.max_x = x;
                    result.max_y = y;
                    result.max_channel = channel;
                    result.max_actual = actual[pixel * 4u + channel];
                    result.max_expected = expected_byte;
                }
                if (seam) result.max_seam_error = std::max(result.max_seam_error, error);
                sum += error;
                ++result.samples;
            }
        }
    }
    result.mean_byte_error = static_cast<double>(sum / std::max<std::uint64_t>(1, result.samples));
    return result;
}

struct Run {
    std::vector<std::uint8_t> pixels;
    std::string telemetry;
};

bool telemetry_backend_is(const std::string& telemetry, const std::string& backend) {
    return telemetry.find("\"backend\":\"" + backend + "\"") != std::string::npos;
}

int telemetry_model_loads(const std::string& telemetry) {
    const std::string key = "\"modelLoads\":";
    const std::size_t start = telemetry.find(key);
    if (start == std::string::npos) return -1;
    const std::size_t value = start + key.size();
    try { return std::stoi(telemetry.substr(value)); }
    catch (...) { return -1; }
}

Run run_page(manyue_lite::Engine& engine, const fs::path& input,
             const fs::path& output_directory, const std::string& name,
             std::uint64_t job_id, int expected_width, int expected_height) {
    Run run;
    const fs::path output_path = output_directory / (name + ".webp");
    run.telemetry = engine.upscale(job_id, input.string(), output_path.string(), expected_width, 100);
    run.pixels = decode_webp(output_path, expected_width, expected_height);
    return run;
}

int compare_images(const std::vector<std::uint8_t>& left, const std::vector<std::uint8_t>& right) {
    if (left.size() != right.size()) throw std::runtime_error("tile output byte lengths differ");
    int maximum = 0;
    for (std::size_t i = 0; i < left.size(); ++i) {
        maximum = std::max(maximum, std::abs(static_cast<int>(left[i]) - static_cast<int>(right[i])));
    }
    return maximum;
}

void verify_alpha_pass_through(const fs::path& source_path, const std::vector<std::uint8_t>& actual) {
    int width = 0;
    int height = 0;
    int components = 0;
    stbi_uc* source = stbi_load(source_path.string().c_str(), &width, &height, &components, 4);
    if (!source) throw std::runtime_error("failed to load source PNG alpha");
    const std::size_t pixels = static_cast<std::size_t>(width) * height;
    for (std::size_t pixel = 0; pixel < pixels; ++pixel) {
        if (actual[pixel * 4u + 3u] != source[pixel * 4u + 3u]) {
            stbi_image_free(source);
            throw std::runtime_error("source alpha changed at 1x output");
        }
    }
    stbi_image_free(source);
}

std::vector<std::uint8_t> decode_rgba(const fs::path& path, int& width, int& height) {
    int components = 0;
    stbi_uc* source = stbi_load(path.string().c_str(), &width, &height, &components, 4);
    if (!source) throw std::runtime_error("failed to decode source image: " + path.string());
    const std::size_t size = static_cast<std::size_t>(width) * height * 4u;
    std::vector<std::uint8_t> pixels(source, source + size);
    stbi_image_free(source);
    return pixels;
}

fs::path make_webp_fixture(const fs::path& source_png, const fs::path& output_directory) {
    int width = 0;
    int height = 0;
    const std::vector<std::uint8_t> pixels = decode_rgba(source_png, width, height);
    std::uint8_t* encoded = nullptr;
    const std::size_t encoded_size = WebPEncodeLosslessRGBA(pixels.data(), width, height, width * 4, &encoded);
    if (encoded_size == 0 || !encoded) throw std::runtime_error("failed to create WebP codec fixture");
    const fs::path path = output_directory / "source-lossless.webp";
    {
        std::ofstream file(path, std::ios::binary);
        if (!file || !file.write(reinterpret_cast<const char*>(encoded), static_cast<std::streamsize>(encoded_size))) {
            WebPFree(encoded);
            throw std::runtime_error("failed to save WebP codec fixture");
        }
    }
    WebPFree(encoded);
    return path;
}

void assert_no_output_or_pending(const fs::path& output_path, std::uint64_t job_id) {
    if (fs::exists(output_path) || fs::exists(output_path.string() + ".manyue-" + std::to_string(job_id) + ".pending")) {
        throw std::runtime_error("cancelled job left a final or pending output file");
    }
}

void expect_cancelled(const std::function<void()>& operation, const std::string& label) {
    bool cancelled = false;
    try {
        operation();
    } catch (const std::exception& failure) {
        cancelled = std::string(failure.what()).find("取消") != std::string::npos;
    }
    if (!cancelled) throw std::runtime_error(label + " cancellation did not abort the job");
}

struct CancelResults {
    bool early = false;
    bool tile = false;
    bool encode_progress = false;
};

CancelResults verify_cancellation(const fs::path& model_directory, const fs::path& source_path,
                                  const fs::path& output_directory) {
    manyue_lite::Engine engine(128);
    std::string error;
    if (!engine.initialize(model_directory.string(), false, error)) {
        throw std::runtime_error("cancel model init failed: " + error);
    }
    CancelResults results;
    const fs::path early_path = output_directory / "early-cancel.webp";
    engine.cancel(19);
    expect_cancelled([&] { (void)engine.upscale(19, source_path.string(), early_path.string(), 257, 100); }, "early");
    assert_no_output_or_pending(early_path, 19);
    results.early = true;

    const fs::path tile_path = output_directory / "tile-cancel.webp";
    bool tile_hook_called = false;
    engine.set_test_tile_hook([&] {
        if (!tile_hook_called) {
            tile_hook_called = true;
            engine.cancel(29);
        }
    });
    expect_cancelled([&] { (void)engine.upscale(29, source_path.string(), tile_path.string(), 352, 100); }, "tile");
    engine.set_test_tile_hook({});
    if (!tile_hook_called) throw std::runtime_error("tile cancellation hook was not reached");
    assert_no_output_or_pending(tile_path, 29);
    results.tile = true;

    const fs::path encode_path = output_directory / "encode-cancel.webp";
    bool progress_hook_called = false;
    engine.set_test_encode_progress_hook([&](int) {
        if (!progress_hook_called) {
            progress_hook_called = true;
            engine.cancel(30);
        }
    });
    expect_cancelled([&] { (void)engine.upscale(30, source_path.string(), encode_path.string(), 514, 100); }, "WebP progress-hook");
    engine.set_test_encode_progress_hook({});
    if (!progress_hook_called) throw std::runtime_error("WebP encoder progress hook was not reached");
    assert_no_output_or_pending(encode_path, 30);
    results.encode_progress = true;
    return results;
}

int target_height(int width) {
    return static_cast<int>((193ll * width + 257 / 2) / 257);
}

int round_half_up(double value) {
    return static_cast<int>(std::floor(value + 0.5));
}

int proportional_target_height(int source_width, int source_height, int target_width) {
    return static_cast<int>((static_cast<std::int64_t>(source_height) * target_width + source_width / 2) / source_width);
}

double telemetry_ms(const std::string& telemetry, const std::string& key) {
    const std::string needle = "\"" + key + "\":";
    const std::size_t at = telemetry.find(needle);
    if (at == std::string::npos) return -1.0;
    const std::size_t start = at + needle.size();
    try { return std::stod(telemetry.substr(start)); }
    catch (...) { return -1.0; }
}

struct ProfileRun {
    double wall_ms = 0.0;
    std::uint64_t output_bytes = 0;
    std::string telemetry;
};

std::string profile_run_json(const ProfileRun& run) {
    std::ostringstream out;
    out << "{\"wallMs\":" << run.wall_ms
        << ",\"outputBytes\":" << run.output_bytes
        << ",\"decodeMs\":" << telemetry_ms(run.telemetry, "decodeMs")
        << ",\"inferMs\":" << telemetry_ms(run.telemetry, "inferMs")
        << ",\"encodeMs\":" << telemetry_ms(run.telemetry, "encodeMs")
        << ",\"telemetry\":\"" << json_escape(run.telemetry) << "\"}";
    return out.str();
}

ProfileRun benchmark_upscale(manyue_lite::Engine& engine, const fs::path& input,
                             const fs::path& output_directory, const std::string& name,
                             std::uint64_t job, int target_width, int source_width, int source_height) {
    ProfileRun run;
    const fs::path path = output_directory / (name + ".webp");
    const auto start = std::chrono::steady_clock::now();
    run.telemetry = engine.upscale(job, input.string(), path.string(), target_width, 100);
    run.wall_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    if (fs::exists(path.string() + ".manyue-" + std::to_string(job) + ".pending")) {
        throw std::runtime_error("benchmark left a pending WebP output");
    }
    if (!telemetry_backend_is(run.telemetry, "vulkan") || telemetry_model_loads(run.telemetry) != 2 ||
        telemetry_ms(run.telemetry, "decodeMs") < 0.0 || telemetry_ms(run.telemetry, "inferMs") < 0.0 ||
        telemetry_ms(run.telemetry, "encodeMs") < 0.0) {
        throw std::runtime_error("benchmark did not use a stable, actual Vulkan backend: " + run.telemetry);
    }
    const int expected_height = proportional_target_height(source_width, source_height, target_width);
    if (!fs::exists(path) || expected_height <= 0) throw std::runtime_error("benchmark output was not published");
    run.output_bytes = fs::file_size(path);
    return run;
}

int run_even_sample_suite(const fs::path& model_directory, const fs::path& source_path,
                          const fs::path& golden_root, const fs::path& output_directory) {
    int source_width = 0, source_height = 0, source_components = 0;
    if (!stbi_info(source_path.string().c_str(), &source_width, &source_height, &source_components) ||
        source_width != 690 || source_height != 985) {
        throw std::runtime_error("phase regression fixture must be exactly 690x985");
    }
    fs::create_directories(output_directory);
    const std::array<std::pair<const char*, double>, 8> scales = {{
        {"1.00x", 1.0}, {"1.25x", 1.25}, {"1.37x", 1.37}, {"1.50x", 1.5},
        {"1.70x", 1.7}, {"1.85x", 1.85}, {"1.90x", 1.9}, {"2.00x", 2.0},
    }};
    const std::array<std::pair<const char*, double>, 4> benchmark_scales = {{
        {"1.00x", 1.0}, {"1.25x", 1.25}, {"1.50x", 1.5}, {"2.00x", 2.0},
    }};
    manyue_lite::Engine cpu128(128), gpu128(128), cpu192(192), gpu192(192);
    std::string error;
    if (!cpu128.initialize(model_directory.string(), false, error) ||
        !gpu128.initialize(model_directory.string(), true, error) ||
        !cpu192.initialize(model_directory.string(), false, error) ||
        !gpu192.initialize(model_directory.string(), true, error)) {
        throw std::runtime_error("690x985 numerical engine initialization failed: " + error);
    }
    std::uint64_t job = 5000;
    int vulkan_executions = 0;
    int max_backend_delta = 0, max_cpu_tile_delta = 0, max_gpu_tile_delta = 0;
    std::vector<std::string> golden_json;
    std::map<std::string, Run> gpu128_runs;
    for (const auto& scale : scales) {
        const int target_width = round_half_up(source_width * scale.second);
        const int target_height = proportional_target_height(source_width, source_height, target_width);
        const std::string label(scale.first);
        const fs::path golden_path = golden_root / ("scale-" + label) / "output-chw.f32";
        const auto expected = read_float_file(golden_path, static_cast<std::size_t>(target_width) * target_height * 3u);
        const Run cpu = run_page(cpu128, source_path, output_directory, "even-cpu128-" + label,
                                 job++, target_width, target_height);
        const Run gpu = run_page(gpu128, source_path, output_directory, "even-gpu128-" + label,
                                 job++, target_width, target_height);
        if (!telemetry_backend_is(cpu.telemetry, "cpu") || !telemetry_backend_is(gpu.telemetry, "vulkan")) {
            throw std::runtime_error("690x985 numerical test did not execute actual Vulkan at " + label +
                                     ": " + gpu.telemetry);
        }
        ++vulkan_executions;
        const CompareResult cpu_error = compare_to_golden(cpu.pixels, expected, target_width, target_height, 128);
        const CompareResult gpu_error = compare_to_golden(gpu.pixels, expected, target_width, target_height, 128);
        const int backend_delta = compare_images(cpu.pixels, gpu.pixels);
        max_backend_delta = std::max(max_backend_delta, backend_delta);
        if (cpu_error.max_byte_error > 2 || gpu_error.max_byte_error > 2 || backend_delta > 2) {
            throw std::runtime_error("690x985 CPU/GPU PyTorch golden mismatch at " + label +
                " cpu=" + std::to_string(cpu_error.max_byte_error) + "/" +
                std::to_string(cpu_error.mean_byte_error) + " gpu=" +
                std::to_string(gpu_error.max_byte_error) + "/" + std::to_string(gpu_error.mean_byte_error) +
                " backend=" + std::to_string(backend_delta));
        }
        gpu128_runs.emplace(label, gpu);
        std::ostringstream item;
        item << "\"" << label << "\":{\"target\":\"" << target_width << 'x' << target_height
             << "\",\"cpuMaxByteError\":" << cpu_error.max_byte_error
             << ",\"cpuMeanByteError\":" << cpu_error.mean_byte_error
             << ",\"gpuMaxByteError\":" << gpu_error.max_byte_error
             << ",\"gpuMeanByteError\":" << gpu_error.mean_byte_error
             << ",\"cpuGpuByteDelta\":" << backend_delta << '}';
        golden_json.push_back(item.str());
    }

    for (const char* label : {"1.37x", "1.50x"}) {
        const double requested_scale = std::string(label) == "1.37x" ? 1.37 : 1.5;
        const int target_width = round_half_up(source_width * requested_scale);
        const int target_height = proportional_target_height(source_width, source_height, target_width);
        const fs::path golden_path = golden_root / (std::string("scale-") + label) / "output-chw.f32";
        const auto expected = read_float_file(golden_path, static_cast<std::size_t>(target_width) * target_height * 3u);
        const Run cpu192_run = run_page(cpu192, source_path, output_directory,
            std::string("even-cpu192-") + label, job++, target_width, target_height);
        const Run gpu192_run = run_page(gpu192, source_path, output_directory,
            std::string("even-gpu192-") + label, job++, target_width, target_height);
        if (!telemetry_backend_is(cpu192_run.telemetry, "cpu") || !telemetry_backend_is(gpu192_run.telemetry, "vulkan")) {
            throw std::runtime_error("690x985 tile-192 GPU was not actually executed");
        }
        ++vulkan_executions;
        const CompareResult cpu192_error = compare_to_golden(cpu192_run.pixels, expected, target_width, target_height, 192);
        const CompareResult gpu192_error = compare_to_golden(gpu192_run.pixels, expected, target_width, target_height, 192);
        const int cpu_delta = compare_images(gpu128_runs.at(label).pixels, cpu192_run.pixels);
        const int gpu_delta = compare_images(gpu128_runs.at(label).pixels, gpu192_run.pixels);
        max_cpu_tile_delta = std::max(max_cpu_tile_delta, cpu_delta);
        max_gpu_tile_delta = std::max(max_gpu_tile_delta, gpu_delta);
        if (cpu192_error.max_byte_error > 2 || gpu192_error.max_byte_error > 2 || cpu_delta > 1 || gpu_delta > 1) {
            throw std::runtime_error("690x985 tile-128/tile-192 mismatch at " + std::string(label));
        }
    }

    // Cold startup and first-page cost are separated from three warm pages per requested scale.
    manyue_lite::Engine benchmark128(128);
    const auto init_start = std::chrono::steady_clock::now();
    if (!benchmark128.initialize(model_directory.string(), true, error)) throw std::runtime_error("benchmark Vulkan init failed: " + error);
    const double benchmark_init_ms = std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - init_start).count();
    const int one_x_width = source_width;
    const ProfileRun first_page = benchmark_upscale(benchmark128, source_path, output_directory,
        "bench128-cold-1.00x", job++, one_x_width, source_width, source_height);
    std::vector<std::pair<std::string, std::vector<ProfileRun>>> warm_scales;
    for (const auto& scale : benchmark_scales) {
        const int target_width = round_half_up(source_width * scale.second);
        std::vector<ProfileRun> runs;
        for (int repeat = 0; repeat < 3; ++repeat) {
            runs.push_back(benchmark_upscale(benchmark128, source_path, output_directory,
                "bench128-warm-" + std::string(scale.first) + "-" + std::to_string(repeat + 1),
                job++, target_width, source_width, source_height));
        }
        warm_scales.emplace_back(scale.first, std::move(runs));
    }
    manyue_lite::Engine benchmark192(192);
    const auto init192_start = std::chrono::steady_clock::now();
    if (!benchmark192.initialize(model_directory.string(), true, error)) throw std::runtime_error("tile-192 benchmark Vulkan init failed: " + error);
    const double benchmark192_init_ms = std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - init192_start).count();
    const int one_point_five_width = round_half_up(source_width * 1.5);
    std::vector<ProfileRun> tile192_warm;
    for (int repeat = 0; repeat < 3; ++repeat) {
        tile192_warm.push_back(benchmark_upscale(benchmark192, source_path, output_directory,
            "bench192-warm-1.50x-" + std::to_string(repeat + 1), job++, one_point_five_width,
            source_width, source_height));
    }

    std::cout << "EVEN_SAMPLE_JSON={\"passed\":true,\"source\":\"690x985\",\"actualVulkanExecutions\":"
              << vulkan_executions << ",\"golden\":{";
    for (std::size_t i = 0; i < golden_json.size(); ++i) {
        if (i) std::cout << ',';
        std::cout << golden_json[i];
    }
    const std::string& gpu_telemetry = gpu128_runs.at("1.00x").telemetry;
    const std::string gpu_name_key = "\"gpuName\":\"";
    const std::size_t gpu_name_start = gpu_telemetry.find(gpu_name_key);
    std::string gpu_name = "unknown";
    if (gpu_name_start != std::string::npos) {
        const std::size_t start = gpu_name_start + gpu_name_key.size();
        const std::size_t end = gpu_telemetry.find('"', start);
        if (end != std::string::npos) gpu_name = gpu_telemetry.substr(start, end - start);
    }
    std::cout << "},\"tile128vs192\":{\"cpuMaxByteDelta\":" << max_cpu_tile_delta
              << ",\"gpuMaxByteDelta\":" << max_gpu_tile_delta << "},\"benchmark\":{\"backend\":\"vulkan\",\"desktopGpuName\":\""
              << json_escape(gpu_name)
              << "\",\"tile128InitWallMs\":" << benchmark_init_ms << ",\"coldFirst1x\":"
              << profile_run_json(first_page) << ",\"warm3ByScale\":{";
    for (std::size_t i = 0; i < warm_scales.size(); ++i) {
        if (i) std::cout << ',';
        std::cout << '"' << warm_scales[i].first << "\":[";
        for (std::size_t j = 0; j < warm_scales[i].second.size(); ++j) {
            if (j) std::cout << ',';
            std::cout << profile_run_json(warm_scales[i].second[j]);
        }
        std::cout << ']';
    }
    std::cout << "},\"tile192InitWallMs\":" << benchmark192_init_ms << ",\"tile192Warm3At1.50x\":[";
    for (std::size_t i = 0; i < tile192_warm.size(); ++i) {
        if (i) std::cout << ',';
        std::cout << profile_run_json(tile192_warm[i]);
    }
    std::cout << "]}}\n";
    return 0;
}

std::string json_escape(const std::string& input) {
    std::string output;
    for (char c : input) {
        if (c == '"' || c == '\\') output.push_back('\\');
        if (c == '\n') output += "\\n";
        else if (c != '\r') output.push_back(c);
    }
    return output;
}

void verify_codec_input(manyue_lite::Engine& engine, const fs::path& input,
                        const fs::path& output_directory, const std::string& tag,
                        const fs::path& alpha_reference, std::uint64_t job_id) {
    const Run run = run_page(engine, input, output_directory, "codec-" + tag, job_id, 257, 193);
    if (!telemetry_backend_is(run.telemetry, "cpu") || telemetry_model_loads(run.telemetry) != 2) {
        throw std::runtime_error("codec test did not use the initialized CPU model: " + run.telemetry);
    }
    verify_alpha_pass_through(alpha_reference, run.pixels);
}

} // namespace

int main(int argc, char** argv) {
    if (argc == 6 && std::string(argv[1]) == "--compare-webp") {
        try {
            const int width = std::stoi(argv[4]);
            const int height = std::stoi(argv[5]);
            const auto left = decode_webp(argv[2], width, height);
            const auto right = decode_webp(argv[3], width, height);
            std::cout << "WEBP_PAIR_JSON={\"width\":" << width << ",\"height\":" << height
                      << ",\"maxRgbaByteDelta\":" << compare_images(left, right)
                      << ",\"leftBytes\":" << fs::file_size(argv[2])
                      << ",\"rightBytes\":" << fs::file_size(argv[3]) << "}\n";
            return 0;
        } catch (const std::exception& failure) {
            std::cerr << "WEBP_PAIR_COMPARE_FAILED: " << failure.what() << '\n';
            return 1;
        }
    }
    if (argc == 6 && std::string(argv[1]) == "--sample") {
        try {
            return run_even_sample_suite(argv[2], argv[3], argv[4], argv[5]);
        } catch (const std::exception& failure) {
            std::cerr << "MANYUE_LITE_EVEN_SAMPLE_FAILED: " << failure.what() << '\n';
            return 1;
        }
    }
    if (argc != 6) {
        std::cerr << "usage: manyue_lite_host_test <model-dir> <source.png> <source.jpg> <golden-root> <output-dir>\n";
        return 2;
    }
    try {
        const fs::path model_directory = argv[1];
        const fs::path source_png = argv[2];
        const fs::path source_jpeg = argv[3];
        const fs::path golden_root = argv[4];
        const fs::path output_directory = argv[5];
        fs::create_directories(output_directory);

        const std::array<std::pair<const char*, int>, 6> scales = {{
            {"1.00x", 257}, {"1.25x", 321}, {"1.37x", 352},
            {"1.50x", 386}, {"1.75x", 450}, {"2.00x", 514},
        }};
        manyue_lite::Engine cpu128(128);
        manyue_lite::Engine cpu192(192);
        manyue_lite::Engine gpu128(128);
        manyue_lite::Engine gpu192(192);
        std::string error;
        if (!cpu128.initialize(model_directory.string(), false, error) ||
            !cpu192.initialize(model_directory.string(), false, error) ||
            !gpu128.initialize(model_directory.string(), true, error) ||
            !gpu192.initialize(model_directory.string(), true, error)) {
            throw std::runtime_error("model init failed: " + error);
        }

        struct ScaleResult {
            const char* tag;
            int width;
            int height;
            CompareResult cpu;
            CompareResult gpu;
            Run cpu_pixels;
            Run gpu_pixels;
        };
        std::vector<ScaleResult> results;
        std::uint64_t job = 100;
        int max_backend_delta = 0;
        int max_cpu_tile_delta = 0;
        int max_gpu_tile_delta = 0;
        int actual_vulkan_executions = 0;
        int cpu_model_loads_after_first_page = -1;
        int gpu_model_loads_after_first_page = -1;
        bool model_load_counts_stable = true;
        TensorCapture first_cpu_head_input;
        TensorCapture first_cpu_features;
        cpu128.set_test_head_input_hook([&](const char* stage, const float* data, int width, int height,
                                             int channels, std::size_t cstep, int origin_x, int origin_y) {
            if (origin_x != 0 || origin_y != 0) return;
            if (std::string(stage) == "features" && first_cpu_features.planar.empty()) {
                capture_tensor(first_cpu_features, data, width, height, channels, cstep, origin_x, origin_y);
            } else if (std::string(stage) == "head-input" && first_cpu_head_input.planar.empty()) {
                capture_tensor(first_cpu_head_input, data, width, height, channels, cstep, origin_x, origin_y);
            }
        });

        for (const auto& scale : scales) {
            const int width = scale.second;
            const int height = target_height(width);
            const fs::path golden_file = golden_root / (std::string("scale-") + scale.first) / "output-chw.f32";
            const auto expected = read_float_file(golden_file, static_cast<std::size_t>(width) * height * 3u);
            ScaleResult result;
            result.tag = scale.first;
            result.width = width;
            result.height = height;
            result.cpu_pixels = run_page(cpu128, source_png, output_directory,
                std::string("cpu128-") + scale.first, job++, width, height);
            if (std::string(scale.first) == "1.00x") {
                cpu128.set_test_head_input_hook({});
                const auto expected_head_input = read_float_file(
                    golden_root / "scale-1.00x" / "head-input-chw.f32",
                    static_cast<std::size_t>(23) * width * height);
                const TensorError head_input_error = compare_tensor_capture(
                    first_cpu_head_input, expected_head_input, width, height,
                    std::min(128, width), std::min(128, height));
                const auto expected_features = read_float_file(
                    golden_root / "png-features-chw.f32",
                    static_cast<std::size_t>(16) * 257u * 193u);
                const TensorError feature_error = compare_tensor_capture(
                    first_cpu_features, expected_features, 257, 193,
                    std::min(128, first_cpu_features.width), std::min(128, first_cpu_features.height));
                std::cout << "HEADINPUT_CAPTURE_JSON={\"featuresMaxAbs\":" << head_input_error.max_feature
                          << ",\"featuresMeanAbs\":" << head_input_error.mean_feature
                          << ",\"baseMaxAbs\":" << head_input_error.max_base
                          << ",\"baseMeanAbs\":" << head_input_error.mean_base
                          << ",\"conditionMaxAbs\":" << head_input_error.max_condition
                          << ",\"conditionMeanAbs\":" << head_input_error.mean_condition
                          << ",\"rawEncoderMaxAbs\":" << feature_error.max_feature
                          << ",\"rawEncoderMeanAbs\":" << feature_error.mean_feature << "}\n";
            }
            result.gpu_pixels = run_page(gpu128, source_png, output_directory,
                std::string("gpu128-") + scale.first, job++, width, height);
            if (!telemetry_backend_is(result.cpu_pixels.telemetry, "cpu") ||
                !telemetry_backend_is(result.gpu_pixels.telemetry, "vulkan")) {
                throw std::runtime_error("requested host backend was not used at " + std::string(scale.first) +
                    ": cpu=" + result.cpu_pixels.telemetry + " gpu=" + result.gpu_pixels.telemetry);
            }
            ++actual_vulkan_executions;
            const int cpu_loads = telemetry_model_loads(result.cpu_pixels.telemetry);
            const int gpu_loads = telemetry_model_loads(result.gpu_pixels.telemetry);
            if (cpu_model_loads_after_first_page < 0) {
                cpu_model_loads_after_first_page = cpu_loads;
                gpu_model_loads_after_first_page = gpu_loads;
            } else if (cpu_loads != cpu_model_loads_after_first_page || gpu_loads != gpu_model_loads_after_first_page) {
                model_load_counts_stable = false;
                throw std::runtime_error("resident engine model load counter increased across warm pages");
            }
            if (cpu_loads < 2 || gpu_loads < 2) {
                throw std::runtime_error("model-load telemetry does not count the loaded trunk and head nets");
            }
            result.cpu = compare_to_golden(result.cpu_pixels.pixels, expected, width, height, 128);
            result.gpu = compare_to_golden(result.gpu_pixels.pixels, expected, width, height, 128);
            max_backend_delta = std::max(max_backend_delta,
                compare_images(result.cpu_pixels.pixels, result.gpu_pixels.pixels));
            if (result.cpu.max_byte_error > 2 || result.gpu.max_byte_error > 2) {
                throw std::runtime_error("CPU/GPU pixel output differs from PyTorch golden by more than 2/255 at " +
                    std::string(scale.first) + " (cpu max/mean=" + std::to_string(result.cpu.max_byte_error) + "/" +
                    std::to_string(result.cpu.mean_byte_error) + ", gpu max/mean=" +
                    std::to_string(result.gpu.max_byte_error) + "/" + std::to_string(result.gpu.mean_byte_error) +
                    ", cpu-vs-gpu=" + std::to_string(compare_images(result.cpu_pixels.pixels, result.gpu_pixels.pixels)) +
                    ", CPU worst rgb" + std::to_string(result.cpu.max_channel) + "@" +
                    std::to_string(result.cpu.max_x) + "," + std::to_string(result.cpu.max_y) + " actual=" +
                    std::to_string(result.cpu.max_actual) + " expected=" + std::to_string(result.cpu.max_expected) + ")");
            }
            if (std::string(scale.first) == "1.37x") {
                Run cpu192_pixels = run_page(cpu192, source_png, output_directory, "cpu192-1.37x", job++, width, height);
                Run gpu192_pixels = run_page(gpu192, source_png, output_directory, "gpu192-1.37x", job++, width, height);
                if (!telemetry_backend_is(cpu192_pixels.telemetry, "cpu") ||
                    !telemetry_backend_is(gpu192_pixels.telemetry, "vulkan")) {
                    throw std::runtime_error("host tile-192 backend selection failed");
                }
                ++actual_vulkan_executions;
                const CompareResult cpu192_error = compare_to_golden(cpu192_pixels.pixels, expected, width, height, 192);
                const CompareResult gpu192_error = compare_to_golden(gpu192_pixels.pixels, expected, width, height, 192);
                if (cpu192_error.max_byte_error > 2 || gpu192_error.max_byte_error > 2) {
                    throw std::runtime_error("tile-192 output differs from golden by more than 2/255");
                }
                max_cpu_tile_delta = compare_images(result.cpu_pixels.pixels, cpu192_pixels.pixels);
                max_gpu_tile_delta = compare_images(result.gpu_pixels.pixels, gpu192_pixels.pixels);
                if (max_cpu_tile_delta > 1 || max_gpu_tile_delta > 1) {
                    throw std::runtime_error("CPU/GPU 128-vs-192 tile boundary delta exceeded 1/255");
                }
            }
            results.push_back(std::move(result));
        }
        const Run& cpu_1x = results.front().cpu_pixels;
        const Run& gpu_1x = results.front().gpu_pixels;
        verify_alpha_pass_through(source_png, cpu_1x.pixels);
        verify_alpha_pass_through(source_png, gpu_1x.pixels);
        if (max_backend_delta > 2) throw std::runtime_error("CPU/GPU byte delta exceeded 2/255");

        const fs::path webp_source = make_webp_fixture(source_png, output_directory);
        manyue_lite::Engine codec_cpu(128);
        if (!codec_cpu.initialize(model_directory.string(), false, error)) {
            throw std::runtime_error("codec model init failed: " + error);
        }
        verify_codec_input(codec_cpu, source_png, output_directory, "png", source_png, job++);
        verify_codec_input(codec_cpu, source_jpeg, output_directory, "jpeg", source_jpeg, job++);
        verify_codec_input(codec_cpu, webp_source, output_directory, "webp", source_png, job++);
        const CancelResults cancellation = verify_cancellation(model_directory, source_png, output_directory);

        if (!model_load_counts_stable) throw std::runtime_error("warm model reuse assertion failed");
        const std::string warm_cpu_telemetry = results.back().cpu_pixels.telemetry;
        const std::string warm_gpu_telemetry = results.back().gpu_pixels.telemetry;
        std::cout << "MANYUE_LITE_HOST_GOLDEN_OK\n"
                  << "RESULT_JSON={\"passed\":true,\"gpuBackend\":\"vulkan\",\"source\":\"257x193\",\"golden\":{";
        for (std::size_t i = 0; i < results.size(); ++i) {
            if (i) std::cout << ',';
            const auto& result = results[i];
            std::cout << '\"' << result.tag << "\":{\"target\":\"" << result.width << 'x' << result.height
                      << "\",\"cpuMaxByteError\":" << result.cpu.max_byte_error
                      << ",\"cpuMeanByteError\":" << result.cpu.mean_byte_error
                      << ",\"cpuSeamMaxByteError\":" << result.cpu.max_seam_error
                      << ",\"gpuMaxByteError\":" << result.gpu.max_byte_error
                      << ",\"gpuMeanByteError\":" << result.gpu.mean_byte_error
                      << ",\"gpuSeamMaxByteError\":" << result.gpu.max_seam_error << '}';
        }
        std::cout << "},\"maxCpu128vs192ByteDelta\":" << max_cpu_tile_delta
                  << ",\"maxGpu128vs192ByteDelta\":" << max_gpu_tile_delta
                  << ",\"maxCpuGpuByteDelta\":" << max_backend_delta
                  << ",\"actualVulkanExecutions\":" << actual_vulkan_executions
                  << ",\"codecs\":{\"png\":\"PASS\",\"jpeg\":\"PASS\",\"webp\":\"PASS\",\"alpha\":\"PASS\"}"
                  << ",\"cancel\":{\"early\":" << (cancellation.early ? "\"PASS\"" : "\"FAIL\"")
                  << ",\"tile\":" << (cancellation.tile ? "\"PASS\"" : "\"FAIL\"")
                  << ",\"webpProgress\":" << (cancellation.encode_progress ? "\"PASS\"" : "\"FAIL\"") << '}'
                  << ",\"warmReuse\":{\"cpuModelLoads\":" << cpu_model_loads_after_first_page
                  << ",\"gpuModelLoads\":" << gpu_model_loads_after_first_page
                  << ",\"loadCountsStableAcrossPages\":" << (model_load_counts_stable ? "true" : "false")
                  << ",\"cpuLastPageTelemetry\":\""
                  << json_escape(warm_cpu_telemetry) << "\",\"gpuLastPageTelemetry\":\""
                  << json_escape(warm_gpu_telemetry) << "\"}}\n";
        std::cout << "cpuLastPageTelemetry=" << warm_cpu_telemetry << '\n'
                  << "gpuLastPageTelemetry=" << warm_gpu_telemetry << '\n'
                  << "maxBackendDelta=" << max_backend_delta << "/255 tile128vs192(cpu,gpu)="
                  << max_cpu_tile_delta << ',' << max_gpu_tile_delta << "/255\n";
        return 0;
    } catch (const std::exception& failure) {
        std::cerr << "MANYUE_LITE_HOST_GOLDEN_FAILED: " << failure.what() << '\n';
        return 1;
    }
}
