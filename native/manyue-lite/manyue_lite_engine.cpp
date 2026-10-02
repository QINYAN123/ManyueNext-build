#include "manyue_lite_engine.h"

#include "manyue_lite_fused_head_shader.h"
#include "manyue_lite_reference.h"

#include <ncnn/command.h>
#include <ncnn/gpu.h>
#include <ncnn/net.h>
#include <ncnn/option.h>
#include <ncnn/pipeline.h>

#include <webp/decode.h>
#include <webp/encode.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <limits>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#ifdef _WIN32
#define NOMINMAX
#include <windows.h>
#endif

#define STBI_ONLY_JPEG
#define STBI_ONLY_PNG
#define STB_IMAGE_IMPLEMENTATION
#include "third_party/stb_image.h"

namespace manyue_lite {
namespace {

constexpr std::uint64_t kMaxPixels = 12'000'000;
constexpr std::uint64_t kMaxEncodedBytes = 64ull * 1024ull * 1024ull;
constexpr int kFeatureChannels = 16;
constexpr int kHeadInputChannels = 23;
constexpr int kHeadWeightCount = 867;
constexpr int kEncoderHalo = 4;

using Clock = std::chrono::steady_clock;

double elapsed_ms(Clock::time_point start, Clock::time_point end = Clock::now()) {
    return std::chrono::duration<double, std::milli>(end - start).count();
}

struct Image {
    int width = 0;
    int height = 0;
    std::uint8_t* rgba = nullptr;
    void (*release)(void*) = nullptr;

    Image() = default;
    Image(const Image&) = delete;
    Image& operator=(const Image&) = delete;
    Image(Image&& other) noexcept { *this = std::move(other); }
    Image& operator=(Image&& other) noexcept {
        if (this == &other) return *this;
        reset();
        width = other.width;
        height = other.height;
        rgba = other.rgba;
        release = other.release;
        other.width = other.height = 0;
        other.rgba = nullptr;
        other.release = nullptr;
        return *this;
    }
    ~Image() { reset(); }
    void reset() noexcept {
        if (rgba && release) release(rgba);
        width = height = 0;
        rgba = nullptr;
        release = nullptr;
    }
};

bool read_prefix(const std::string& path, std::array<unsigned char, 12>& bytes) {
    std::ifstream file(path, std::ios::binary);
    if (!file) return false;
    file.read(reinterpret_cast<char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
    return file.gcount() >= 8;
}

std::uint64_t file_size_or_throw(const std::string& path) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file) throw std::runtime_error("无法打开输入图片");
    const std::streamoff size = file.tellg();
    if (size < 0) throw std::runtime_error("无法读取输入图片大小");
    const auto result = static_cast<std::uint64_t>(size);
    if (result == 0 || result > kMaxEncodedBytes) throw std::runtime_error("输入图片超过64MiB限制");
    return result;
}

void validate_dimensions(int width, int height) {
    if (width <= 0 || height <= 0) throw std::runtime_error("图片尺寸无效");
    const std::uint64_t pixels = static_cast<std::uint64_t>(width) * static_cast<std::uint64_t>(height);
    if (pixels > kMaxPixels) throw std::runtime_error("输入图片超过1200万像素限制");
    if (pixels > std::numeric_limits<std::size_t>::max() / 4u) throw std::runtime_error("图片尺寸溢出");
}

Image decode_image(const std::string& path, std::uint64_t& encoded_bytes) {
    encoded_bytes = file_size_or_throw(path);
    std::array<unsigned char, 12> prefix{};
    if (!read_prefix(path, prefix)) throw std::runtime_error("无法读取图片头");

    Image image;
    const bool is_webp = std::memcmp(prefix.data(), "RIFF", 4) == 0 &&
        std::memcmp(prefix.data() + 8, "WEBP", 4) == 0;
    if (is_webp) {
        std::vector<std::uint8_t> encoded(static_cast<std::size_t>(encoded_bytes));
        {
            std::ifstream file(path, std::ios::binary);
            if (!file || !file.read(reinterpret_cast<char*>(encoded.data()), static_cast<std::streamsize>(encoded.size()))) {
                throw std::runtime_error("读取WebP图片失败");
            }
        }
        if (!WebPGetInfo(encoded.data(), encoded.size(), &image.width, &image.height)) {
            throw std::runtime_error("WebP图片头无效");
        }
        validate_dimensions(image.width, image.height);
        image.rgba = WebPDecodeRGBA(encoded.data(), encoded.size(), &image.width, &image.height);
        image.release = &WebPFree;
        if (!image.rgba) throw std::runtime_error("WebP解码失败");
        encoded.clear();
        encoded.shrink_to_fit();
        return image;
    }

    const bool is_png = prefix[0] == 0x89 && std::memcmp(prefix.data() + 1, "PNG\r\n\x1a\n", 7) == 0;
    const bool is_jpeg = prefix[0] == 0xff && prefix[1] == 0xd8;
    if (!is_png && !is_jpeg) throw std::runtime_error("仅支持PNG、JPEG和WebP图片");

    int source_channels = 0;
    if (!stbi_info(path.c_str(), &image.width, &image.height, &source_channels)) {
        throw std::runtime_error(std::string("图片头无效: ") + stbi_failure_reason());
    }
    validate_dimensions(image.width, image.height);
    image.rgba = stbi_load(path.c_str(), &image.width, &image.height, &source_channels, 4);
    image.release = &stbi_image_free;
    if (!image.rgba) throw std::runtime_error(std::string("PNG/JPEG解码失败: ") + stbi_failure_reason());
    return image;
}

std::string json_escape(const std::string& value) {
    std::string escaped;
    escaped.reserve(value.size() + 8);
    for (unsigned char c : value) {
        switch (c) {
            case '"': escaped += "\\\""; break;
            case '\\': escaped += "\\\\"; break;
            case '\b': escaped += "\\b"; break;
            case '\f': escaped += "\\f"; break;
            case '\n': escaped += "\\n"; break;
            case '\r': escaped += "\\r"; break;
            case '\t': escaped += "\\t"; break;
            default:
                if (c < 0x20) {
                    const char hex[] = "0123456789abcdef";
                    escaped += "\\u00";
                    escaped += hex[(c >> 4) & 0xf];
                    escaped += hex[c & 0xf];
                } else {
                    escaped += static_cast<char>(c);
                }
        }
    }
    return escaped;
}

float byte_to_float(std::uint8_t value) { return static_cast<float>(value) * (1.0f / 255.0f); }

std::uint8_t float_to_byte(float value) {
    value = std::max(0.0f, std::min(value, 1.0f));
    return static_cast<std::uint8_t>(std::floor(value * 255.0f + 0.5f));
}

float sample_alpha_bilinear(const Image& image, double x, double y) {
    x = std::max(0.0, std::min(x, static_cast<double>(image.width - 1)));
    y = std::max(0.0, std::min(y, static_cast<double>(image.height - 1)));
    const int x0 = static_cast<int>(std::floor(x));
    const int y0 = static_cast<int>(std::floor(y));
    const int x1 = std::min(x0 + 1, image.width - 1);
    const int y1 = std::min(y0 + 1, image.height - 1);
    const float fx = static_cast<float>(x - x0);
    const float fy = static_cast<float>(y - y0);
    const auto at = [&image](int px, int py) {
        return byte_to_float(image.rgba[(static_cast<std::size_t>(py) * image.width + px) * 4u + 3u]);
    };
    const float top = at(x0, y0) * (1.0f - fx) + at(x1, y0) * fx;
    const float bottom = at(x0, y1) * (1.0f - fx) + at(x1, y1) * fx;
    return top * (1.0f - fy) + bottom * fy;
}

struct TileCrop {
    int x = 0;
    int y = 0;
    int width = 0;
    int height = 0;
};

TileCrop crop_for_tile(int tile_x, int tile_y, int tile_width, int tile_height,
                       int source_width, int source_height, int output_width, int output_height) {
    const SourceCoordinate first = source_coordinate(tile_x, tile_y, source_width, source_height, output_width, output_height);
    const SourceCoordinate last = source_coordinate(tile_x + tile_width - 1, tile_y + tile_height - 1,
                                                     source_width, source_height, output_width, output_height);
    const double first_x = std::max(0.0, std::min(first.x, static_cast<double>(source_width - 1)));
    const double first_y = std::max(0.0, std::min(first.y, static_cast<double>(source_height - 1)));
    const double last_x = std::max(0.0, std::min(last.x, static_cast<double>(source_width - 1)));
    const double last_y = std::max(0.0, std::min(last.y, static_cast<double>(source_height - 1)));
    const int min_feature_x = static_cast<int>(std::floor(first_x));
    const int min_feature_y = static_cast<int>(std::floor(first_y));
    const int max_feature_x = static_cast<int>(std::floor(last_x)) + 1;
    const int max_feature_y = static_cast<int>(std::floor(last_y)) + 1;

    TileCrop crop;
    crop.x = std::max(0, min_feature_x - kEncoderHalo);
    crop.y = std::max(0, min_feature_y - kEncoderHalo);
    const int end_x = std::min(source_width, max_feature_x + kEncoderHalo + 1);
    const int end_y = std::min(source_height, max_feature_y + kEncoderHalo + 1);
    crop.width = end_x - crop.x;
    crop.height = end_y - crop.y;
    return crop;
}

ncnn::Mat make_source_crop(const Image& source, const TileCrop& crop) {
    ncnn::Mat result(crop.width, crop.height, 3);
    if (result.empty()) throw std::runtime_error("无法分配输入tile");
    float* planes[3] = {
        static_cast<float*>(result.channel(0)),
        static_cast<float*>(result.channel(1)),
        static_cast<float*>(result.channel(2)),
    };
    for (int y = 0; y < crop.height; ++y) {
        const std::uint8_t* row = source.rgba + (static_cast<std::size_t>(crop.y + y) * source.width + crop.x) * 4u;
        for (int x = 0; x < crop.width; ++x) {
            const std::size_t pixel = static_cast<std::size_t>(y) * crop.width + x;
            planes[0][pixel] = byte_to_float(row[static_cast<std::size_t>(x) * 4u]);
            planes[1][pixel] = byte_to_float(row[static_cast<std::size_t>(x) * 4u + 1u]);
            planes[2][pixel] = byte_to_float(row[static_cast<std::size_t>(x) * 4u + 2u]);
        }
    }
    return result;
}

void set_net_option(ncnn::Net& net, bool use_vulkan, ncnn::VkAllocator* blob_allocator,
                    ncnn::VkAllocator* staging_allocator) {
    net.opt.use_vulkan_compute = use_vulkan;
    net.opt.use_fp16_packed = false;
    net.opt.use_fp16_storage = false;
    net.opt.use_fp16_arithmetic = false;
    net.opt.use_bf16_storage = false;
    net.opt.use_packing_layout = false;
    net.opt.use_shader_pack8 = false;
    net.opt.use_int8_storage = false;
    net.opt.use_int8_arithmetic = false;
    net.opt.use_winograd_convolution = false;
    net.opt.use_sgemm_convolution = false;
    net.opt.num_threads = 4;
    if (use_vulkan) {
        net.opt.blob_vkallocator = blob_allocator;
        net.opt.workspace_vkallocator = blob_allocator;
        net.opt.staging_vkallocator = staging_allocator;
    }
}

bool load_net(ncnn::Net& net, const std::string& parameter_path, const std::string& weights_path,
              bool use_vulkan, ncnn::VulkanDevice* device,
              ncnn::VkAllocator* blob_allocator, ncnn::VkAllocator* staging_allocator,
              std::string& error) {
    set_net_option(net, use_vulkan, blob_allocator, staging_allocator);
    if (use_vulkan) net.set_vulkan_device(device);
    const int param_status = net.load_param(parameter_path.c_str());
    if (param_status != 0) {
        error = "模型参数加载失败: " + parameter_path;
        return false;
    }
    const int model_status = net.load_model(weights_path.c_str());
    if (model_status != 0) {
        error = "模型权重加载失败: " + weights_path;
        return false;
    }
    const auto& inputs = net.input_names();
    const auto& outputs = net.output_names();
    if (inputs.size() != 1 || outputs.size() != 1 || std::string(inputs[0]) != "in0" || std::string(outputs[0]) != "out0") {
        error = "轻量模型blob名称必须是in0和out0";
        return false;
    }
    return true;
}

std::mutex g_gpu_lifecycle_mutex;
int g_gpu_instance_users = 0;

bool acquire_gpu_instance() {
    std::lock_guard<std::mutex> guard(g_gpu_lifecycle_mutex);
    if (g_gpu_instance_users == 0) {
        if (ncnn::create_gpu_instance() != 0) return false;
    }
    ++g_gpu_instance_users;
    return true;
}

void release_gpu_instance() {
    std::lock_guard<std::mutex> guard(g_gpu_lifecycle_mutex);
    if (g_gpu_instance_users <= 0) return;
    --g_gpu_instance_users;
    if (g_gpu_instance_users == 0) ncnn::destroy_gpu_instance();
}

bool publish_file_atomically(const std::string& pending, const std::string& destination, std::string& error) {
#ifdef _WIN32
    if (!MoveFileExA(pending.c_str(), destination.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        error = "无法原子替换输出文件";
        return false;
    }
#else
    if (std::rename(pending.c_str(), destination.c_str()) != 0) {
        error = "无法原子替换输出文件";
        return false;
    }
#endif
    return true;
}

struct EncodeContext {
    std::FILE* file = nullptr;
    const std::atomic<std::uint64_t>* highest_cancelled_job = nullptr;
    std::uint64_t job_id = 0;
#if !defined(__ANDROID__)
    std::function<void(int)>* progress_test_hook = nullptr;
#endif
    bool failed = false;
};

int write_webp_chunk(const std::uint8_t* data, std::size_t size, const WebPPicture* picture) {
    auto* context = static_cast<EncodeContext*>(picture->custom_ptr);
    if (!context || context->failed ||
        context->job_id <= context->highest_cancelled_job->load(std::memory_order_acquire)) {
        if (context) context->failed = true;
        return 0;
    }
    if (std::fwrite(data, 1, size, context->file) != size) {
        context->failed = true;
        return 0;
    }
    return 1;
}

int webp_progress_hook(int percent, const WebPPicture* picture) {
    auto* context = static_cast<EncodeContext*>(picture->user_data);
    if (!context || context->failed) return 0;
#if !defined(__ANDROID__)
    if (context->progress_test_hook && *context->progress_test_hook) {
        try {
            (*context->progress_test_hook)(percent);
        } catch (...) {
            context->failed = true;
            return 0;
        }
    }
#else
    (void)percent;
#endif
    if (context->job_id <= context->highest_cancelled_job->load(std::memory_order_acquire)) {
        context->failed = true;
        return 0;
    }
    return 1;
}

void append_backend_json(std::ostringstream& out, const std::string& backend) {
    out << "\"backend\":\"" << json_escape(backend) << "\"";
}

} // namespace

class Engine::Impl {
public:
    explicit Impl(int tile_side) : tile_side_(tile_side) {
        if (tile_side_ != 128 && tile_side_ != 192) tile_side_ = 128;
    }

    ~Impl() { release_gpu(); }

    bool initialize(const std::string& model_directory, bool prefer_gpu, std::string& error) {
        std::lock_guard<std::mutex> inference_guard(inference_mutex_);
        model_directory_ = model_directory;
        if (prefer_gpu && initialize_gpu(error)) {
            backend_ = "vulkan";
            return true;
        }
        const std::string gpu_failure = error;
        error.clear();
        release_gpu();
        trunk_.clear();
        head_.clear();
        if (!initialize_cpu(error)) {
            if (!gpu_failure.empty()) error = gpu_failure + "; CPU fallback failed: " + error;
            return false;
        }
        backend_ = "cpu";
        if (prefer_gpu) gpu_fallback_reason_ = gpu_failure.empty() ? "Vulkan初始化失败" : gpu_failure;
        return true;
    }

    void cancel(std::uint64_t job_id) noexcept {
        std::lock_guard<std::mutex> publication_guard(publication_mutex_);
        std::uint64_t observed = highest_cancelled_job_.load(std::memory_order_relaxed);
        while (observed < job_id &&
               !highest_cancelled_job_.compare_exchange_weak(observed, job_id,
                                                               std::memory_order_release,
                                                               std::memory_order_relaxed)) {}
    }

#if !defined(__ANDROID__)
    void set_test_tile_hook(std::function<void()> hook) {
        std::lock_guard<std::mutex> inference_guard(inference_mutex_);
        test_tile_hook_ = std::move(hook);
    }

    void set_test_encode_progress_hook(std::function<void(int)> hook) {
        std::lock_guard<std::mutex> inference_guard(inference_mutex_);
        test_encode_progress_hook_ = std::move(hook);
    }

    void set_test_head_input_hook(
        std::function<void(const char*, const float*, int, int, int, std::size_t, int, int)> hook) {
        std::lock_guard<std::mutex> inference_guard(inference_mutex_);
        test_head_input_hook_ = std::move(hook);
    }
#endif

    std::string upscale(std::uint64_t job_id, const std::string& input_path,
                        const std::string& output_path, int target_width,
                        int strength_percent) {
        std::lock_guard<std::mutex> inference_guard(inference_mutex_);
        require_not_cancelled(job_id);
        if (!initialized_) throw std::runtime_error("轻量模型未初始化");
        if (job_id == 0) throw std::runtime_error("任务编号无效");
        strength_percent = std::max(0, std::min(strength_percent, 100));

        const Clock::time_point decode_start = Clock::now();
        std::uint64_t encoded_bytes = 0;
        Image source = decode_image(input_path, encoded_bytes);
        const double decode_ms = elapsed_ms(decode_start);
        require_not_cancelled(job_id);

        if (target_width < source.width || target_width > source.width * 2) {
            throw std::runtime_error("目标宽度必须在原图1到2倍之间");
        }
        const std::uint64_t height_numerator = static_cast<std::uint64_t>(source.height) * target_width + source.width / 2u;
        const int output_height = static_cast<int>(height_numerator / static_cast<std::uint64_t>(source.width));
        const std::uint64_t output_pixels = static_cast<std::uint64_t>(target_width) * output_height;
        if (output_height <= 0 || output_pixels > kMaxPixels) throw std::runtime_error("目标图片超过1200万像素限制");
        if (output_pixels > std::numeric_limits<std::size_t>::max() / 4u) throw std::runtime_error("目标图片尺寸溢出");

        std::vector<std::uint8_t> output(static_cast<std::size_t>(output_pixels) * 4u);
        double infer_ms = 0.0;
        int tiles = 0;
        if (!render_all_tiles(source, output, target_width, output_height, strength_percent, job_id,
                              infer_ms, tiles)) {
            if (backend_ != "vulkan") throw std::runtime_error(last_error_);
            const std::string gpu_error = last_error_;
            require_not_cancelled(job_id);
            switch_to_cpu_or_throw(gpu_error);
            std::fill(output.begin(), output.end(), 0);
            infer_ms = 0.0;
            tiles = 0;
            require_not_cancelled(job_id);
            if (!render_all_tiles(source, output, target_width, output_height, strength_percent, job_id,
                                  infer_ms, tiles)) {
                throw std::runtime_error(last_error_);
            }
        }

        const std::uint64_t output_bytes = output.size();
        const std::uint64_t tile_pixels = static_cast<std::uint64_t>(std::min(tile_side_, target_width)) *
            static_cast<std::uint64_t>(std::min(tile_side_, output_height));
        const std::uint64_t max_crop_pixels = static_cast<std::uint64_t>(std::min(source.width, tile_side_ + 2 * kEncoderHalo + 2)) *
            static_cast<std::uint64_t>(std::min(source.height, tile_side_ + 2 * kEncoderHalo + 2));
        const std::uint64_t tile_scratch = max_crop_pixels * (9u + 2u * kFeatureChannels) * sizeof(float) +
            tile_pixels * (kHeadInputChannels + 3u) * sizeof(float) +
            static_cast<std::uint64_t>(kHeadWeightCount) * sizeof(float);
        const std::uint64_t decode_peak = source_bytes(source) + encoded_bytes;
        const std::uint64_t process_peak = source_bytes(source) + output_bytes + tile_scratch;
        const std::uint64_t encode_peak = source_bytes(source) + 2u * output_bytes + tile_scratch;
        const std::uint64_t bounded_working_bytes = std::max(decode_peak, std::max(process_peak, encode_peak));

        const Clock::time_point encode_start = Clock::now();
        std::string encode_error;
        if (!encode_atomic_webp(output, target_width, output_height, output_path, job_id, encode_error)) {
            throw std::runtime_error(encode_error);
        }
        const double encode_ms = elapsed_ms(encode_start);

        std::ostringstream json;
        json << '{';
        append_backend_json(json, backend_);
        json << ",\"modelLoads\":" << model_loads_
             << ",\"tiles\":" << tiles
             << ",\"decodeMs\":" << std::fixed << std::setprecision(3) << decode_ms
             << ",\"inferMs\":" << std::fixed << std::setprecision(3) << infer_ms
             << ",\"encodeMs\":" << std::fixed << std::setprecision(3) << encode_ms
             << ",\"outputWidth\":" << target_width
             << ",\"outputHeight\":" << output_height
             << ",\"boundedWorkingBytes\":" << bounded_working_bytes
             << ",\"allocationScope\":\"estimated host pixel buffers and tile tensors; excludes Vulkan driver pools and private codec scratch\"";
        if (!gpu_fallback_reason_.empty()) {
            json << ",\"gpuFallbackReason\":\"" << json_escape(gpu_fallback_reason_) << '"';
        }
        if (backend_ == "vulkan" && !gpu_name_.empty()) json << ",\"gpuName\":\"" << json_escape(gpu_name_) << '"';
        json << '}';
        return json.str();
    }

private:
    static std::uint64_t source_bytes(const Image& image) {
        return static_cast<std::uint64_t>(image.width) * image.height * 4u;
    }

    void require_not_cancelled(std::uint64_t job_id) const {
        if (job_id == 0 || job_id <= highest_cancelled_job_.load(std::memory_order_acquire)) {
            throw std::runtime_error("任务已取消");
        }
    }

    bool initialize_gpu(std::string& error) {
        if (!acquire_gpu_instance()) {
            error = "Vulkan不可用";
            return false;
        }
        gpu_instance_acquired_ = true;
        const int device_index = ncnn::get_default_gpu_index();
        vkdev_ = ncnn::get_gpu_device(device_index);
        if (!vkdev_ || !vkdev_->is_valid()) {
            error = "未找到可用Vulkan设备";
            return false;
        }
        blob_vkallocator_ = vkdev_->acquire_blob_allocator();
        staging_vkallocator_ = vkdev_->acquire_staging_allocator();
        if (!blob_vkallocator_ || !staging_vkallocator_) {
            error = "Vulkan内存分配器初始化失败";
            return false;
        }

        const std::string trunk_param = model_directory_ + "/trunk.param";
        const std::string trunk_bin = model_directory_ + "/trunk.bin";
        const std::string head_param = model_directory_ + "/head.param";
        const std::string head_bin = model_directory_ + "/head.bin";
        if (!load_net(trunk_, trunk_param, trunk_bin, true, vkdev_, blob_vkallocator_, staging_vkallocator_, error)) return false;
        ++model_loads_;
        if (!load_net(head_, head_param, head_bin, false, nullptr, nullptr, nullptr, error)) return false;
        ++model_loads_;
        if (!load_head_weights(error)) return false;
        if (!create_fused_pipeline(error)) return false;
        if (!upload_head_weights(error)) return false;

        gpu_name_ = vkdev_->info.device_name();
        initialized_ = true;
        return true;
    }

    bool initialize_cpu(std::string& error) {
        const std::string trunk_param = model_directory_ + "/trunk.param";
        const std::string trunk_bin = model_directory_ + "/trunk.bin";
        const std::string head_param = model_directory_ + "/head.param";
        const std::string head_bin = model_directory_ + "/head.bin";
        if (!load_net(trunk_, trunk_param, trunk_bin, false, nullptr, nullptr, nullptr, error)) return false;
        ++model_loads_;
        if (!load_net(head_, head_param, head_bin, false, nullptr, nullptr, nullptr, error)) return false;
        ++model_loads_;
        initialized_ = true;
        return true;
    }

    bool load_head_weights(std::string& error) {
        const std::string path = model_directory_ + "/head.f32";
        std::ifstream file(path, std::ios::binary | std::ios::ate);
        if (!file) {
            error = "缺少GPU融合head权重: " + path;
            return false;
        }
        const std::streamoff length = file.tellg();
        if (length != static_cast<std::streamoff>(kHeadWeightCount * sizeof(float))) {
            error = "head.f32必须恰好包含867个FP32值";
            return false;
        }
        file.seekg(0, std::ios::beg);
        if (!file.read(reinterpret_cast<char*>(head_weights_.data()), length)) {
            error = "读取head.f32失败";
            return false;
        }
        for (float value : head_weights_) {
            if (!std::isfinite(value)) {
                error = "head.f32含有非有限权重";
                return false;
            }
        }
        return true;
    }

    bool create_fused_pipeline(std::string& error) {
        std::vector<std::uint32_t> spirv;
        if (ncnn::compile_spirv_module(kFusedHeadShader, static_cast<int>(sizeof(kFusedHeadShader) - 1), trunk_.opt, spirv) != 0 || spirv.empty()) {
            error = "Vulkan fused-head shader编译失败";
            return false;
        }
        fused_pipeline_.reset(new ncnn::Pipeline(vkdev_));
        fused_pipeline_->set_optimal_local_size_xyz(8, 8, 1);
        const std::vector<ncnn::vk_specialization_type> specializations;
        if (fused_pipeline_->create(spirv.data(), spirv.size() * sizeof(std::uint32_t), specializations) != 0) {
            fused_pipeline_.reset();
            error = "Vulkan fused-head pipeline创建失败";
            return false;
        }
        return true;
    }

    bool upload_head_weights(std::string& error) {
        ncnn::Mat host_weights(kHeadWeightCount);
        if (host_weights.empty()) {
            error = "无法分配head GPU权重上传缓冲区";
            return false;
        }
        std::memcpy(host_weights.data, head_weights_.data(), sizeof(head_weights_));
        ncnn::VkCompute command(vkdev_);
        command.record_upload(host_weights, head_weights_gpu_, trunk_.opt);
        if (command.submit_and_wait() != 0 || head_weights_gpu_.empty()) {
            error = "上传head GPU权重失败";
            return false;
        }
        return true;
    }

    void release_gpu() noexcept {
        initialized_ = false;
        fused_pipeline_.reset();
        head_weights_gpu_.release();
        if (gpu_instance_acquired_) {
            trunk_.clear();
            head_.clear();
            if (blob_vkallocator_ && vkdev_) vkdev_->reclaim_blob_allocator(blob_vkallocator_);
            if (staging_vkallocator_ && vkdev_) vkdev_->reclaim_staging_allocator(staging_vkallocator_);
            blob_vkallocator_ = nullptr;
            staging_vkallocator_ = nullptr;
            vkdev_ = nullptr;
            release_gpu_instance();
            gpu_instance_acquired_ = false;
        }
        gpu_name_.clear();
    }

    void switch_to_cpu_or_throw(const std::string& gpu_error) {
        const std::string previous_model_directory = model_directory_;
        release_gpu();
        trunk_.clear();
        head_.clear();
        model_directory_ = previous_model_directory;
        std::string cpu_error;
        if (!initialize_cpu(cpu_error)) {
            throw std::runtime_error("Vulkan推理失败: " + gpu_error + "; CPU fallback失败: " + cpu_error);
        }
        backend_ = "cpu";
        gpu_fallback_reason_ = gpu_error;
    }

    ncnn::Mat encode_input_for_head(const ncnn::Mat& features, const ncnn::Mat& source_crop,
                                   const TileCrop& crop, int output_x, int output_y,
                                   int tile_width, int tile_height, int source_width, int source_height,
                                   int output_width, int output_height) const {
        ncnn::Mat input(tile_width, tile_height, kHeadInputChannels);
        if (input.empty()) throw std::runtime_error("无法分配fused-head tile输入");
        const double scale_x = (static_cast<double>(output_width) / source_width - 1.5) / 0.5;
        const double scale_y = (static_cast<double>(output_height) / source_height - 1.5) / 0.5;
        for (int y = 0; y < tile_height; ++y) {
            float* rows[kHeadInputChannels];
            for (int channel = 0; channel < kHeadInputChannels; ++channel) {
                rows[channel] = static_cast<float*>(input.channel(channel)) + static_cast<std::size_t>(y) * tile_width;
            }
            for (int x = 0; x < tile_width; ++x) {
                const SourceCoordinate coordinate = source_coordinate(output_x + x, output_y + y,
                                                                       source_width, source_height,
                                                                       output_width, output_height);
                const double local_x = coordinate.x - crop.x;
                const double local_y = coordinate.y - crop.y;
                for (int channel = 0; channel < kFeatureChannels; ++channel) {
                    rows[channel][x] = sample_bilinear_clamped(static_cast<const float*>(features.data),
                                                               features.w, features.h, features.cstep,
                                                               channel, local_x, local_y);
                }
                for (int channel = 0; channel < 3; ++channel) {
                    const float base = sample_bicubic_clamped(static_cast<const float*>(source_crop.data),
                                                              source_crop.w, source_crop.h, source_crop.cstep,
                                                              channel, local_x, local_y);
                    rows[16 + channel][x] = base;
                }
                rows[19][x] = static_cast<float>(scale_x);
                rows[20][x] = static_cast<float>(scale_y);
                rows[21][x] = coordinate.phase_x;
                rows[22][x] = coordinate.phase_y;
            }
        }
        return input;
    }

    bool infer_cpu_tile(const Image& source, const ncnn::Mat& source_crop, const TileCrop& crop,
                        int output_x, int output_y, int tile_width, int tile_height,
                        int output_width, int output_height, int strength_percent,
                        std::uint64_t job_id, std::vector<std::uint8_t>& output) {
        if (job_id <= highest_cancelled_job_.load(std::memory_order_acquire)) {
            last_error_ = "任务已取消";
            return false;
        }
        ncnn::Extractor trunk_extractor = trunk_.create_extractor();
        if (trunk_extractor.input("in0", source_crop) != 0) {
            last_error_ = "CPU encoder输入失败";
            return false;
        }
        ncnn::Mat features;
        if (trunk_extractor.extract("out0", features, 1) != 0 || features.empty() ||
            features.dims != 3 || features.w != crop.width || features.h != crop.height || features.c != kFeatureChannels) {
            last_error_ = "CPU encoder输出形状无效";
            return false;
        }
#if !defined(__ANDROID__)
        if (test_head_input_hook_ && output_x == 0 && output_y == 0) {
            test_head_input_hook_("features", static_cast<const float*>(features.data), features.w, features.h,
                                  features.c, features.cstep, crop.x, crop.y);
        }
#endif
        ncnn::Mat head_input = encode_input_for_head(features, source_crop, crop, output_x, output_y,
                                                     tile_width, tile_height, source.width, source.height,
                                                     output_width, output_height);
#if !defined(__ANDROID__)
        if (test_head_input_hook_) {
            test_head_input_hook_("head-input", static_cast<const float*>(head_input.data), head_input.w,
                                  head_input.h, head_input.c, head_input.cstep, output_x, output_y);
        }
#endif
        if (job_id <= highest_cancelled_job_.load(std::memory_order_acquire)) {
            last_error_ = "任务已取消";
            return false;
        }
        ncnn::Extractor head_extractor = head_.create_extractor();
        if (head_extractor.input("in0", head_input) != 0) {
            last_error_ = "CPU head输入失败";
            return false;
        }
        ncnn::Mat residual;
        if (head_extractor.extract("out0", residual, 1) != 0 || residual.empty() ||
            residual.dims != 3 || residual.w != tile_width || residual.h != tile_height || residual.c != 3) {
            last_error_ = "CPU head输出形状无效";
            return false;
        }
        const float* residual_channels[3] = {
            static_cast<const float*>(residual.channel(0)),
            static_cast<const float*>(residual.channel(1)),
            static_cast<const float*>(residual.channel(2)),
        };
        const float strength = static_cast<float>(strength_percent) * 0.01f;
        for (int y = 0; y < tile_height; ++y) {
            if (job_id <= highest_cancelled_job_.load(std::memory_order_acquire)) {
                last_error_ = "任务已取消";
                return false;
            }
            for (int x = 0; x < tile_width; ++x) {
                const SourcePosition coordinate = source_position(output_x + x, output_y + y,
                                                                   source.width, source.height,
                                                                   output_width, output_height);
                const double local_x = coordinate.x - crop.x;
                const double local_y = coordinate.y - crop.y;
                const std::size_t output_index = (static_cast<std::size_t>(output_y + y) * output_width + output_x + x) * 4u;
                for (int channel = 0; channel < 3; ++channel) {
                    const float base = sample_bicubic_clamped(static_cast<const float*>(source_crop.data),
                                                              source_crop.w, source_crop.h, source_crop.cstep,
                                                              channel, local_x, local_y);
                    const float bounded_residual = clamp_residual(
                        residual_channels[channel][static_cast<std::size_t>(y) * tile_width + x],
                        static_cast<float>(output_width) / source.width);
                    const float value = base + bounded_residual * strength;
                    output[output_index + channel] = float_to_byte(value);
                }
                output[output_index + 3u] = float_to_byte(sample_alpha_bilinear(source, coordinate.x, coordinate.y));
            }
        }
        return true;
    }

    bool infer_gpu_tile(const Image& source, const ncnn::Mat& source_crop, const TileCrop& crop,
                        int output_x, int output_y, int tile_width, int tile_height,
                        int output_width, int output_height, int strength_percent,
                        std::uint64_t job_id, std::vector<std::uint8_t>& output) {
        if (job_id <= highest_cancelled_job_.load(std::memory_order_acquire)) {
            last_error_ = "任务已取消";
            return false;
        }
        ncnn::VkCompute command(vkdev_);
        ncnn::VkMat source_gpu;
        command.record_upload(source_crop, source_gpu, trunk_.opt);
        if (source_gpu.empty() || source_gpu.dims != 3 || source_gpu.w != crop.width ||
            source_gpu.h != crop.height || source_gpu.c * source_gpu.elempack != 3 ||
            source_gpu.elempack != 1 || source_gpu.elembits() != 32) {
            last_error_ = "Vulkan RGB source upload形状或精度无效";
            return false;
        }
        ncnn::Extractor trunk_extractor = trunk_.create_extractor();
        trunk_extractor.set_blob_vkallocator(blob_vkallocator_);
        trunk_extractor.set_workspace_vkallocator(blob_vkallocator_);
        trunk_extractor.set_staging_vkallocator(staging_vkallocator_);
        if (trunk_extractor.input("in0", source_gpu) != 0) {
            last_error_ = "Vulkan encoder输入失败";
            return false;
        }
        ncnn::VkMat features_gpu;
        if (trunk_extractor.extract("out0", features_gpu, command) != 0 || features_gpu.empty() ||
            features_gpu.dims != 3 || features_gpu.w != crop.width || features_gpu.h != crop.height ||
            features_gpu.c * features_gpu.elempack != kFeatureChannels || features_gpu.elembits() != 32) {
            std::ostringstream reason;
            reason << "Vulkan encoder输出形状或精度无效(dims=" << features_gpu.dims
                   << ",w=" << features_gpu.w << ",h=" << features_gpu.h
                   << ",c=" << features_gpu.c << ",pack=" << features_gpu.elempack
                   << ",bits=" << features_gpu.elembits() << ",expected="
                   << crop.width << 'x' << crop.height << 'x' << kFeatureChannels << ")";
            last_error_ = reason.str();
            return false;
        }
        ncnn::Mat parameter_host(22);
        if (parameter_host.empty()) {
            last_error_ = "无法分配Vulkan fused-head参数";
            return false;
        }
        float* parameters = static_cast<float*>(parameter_host.data);
        parameters[0] = static_cast<float>(source.width);
        parameters[1] = static_cast<float>(source.height);
        parameters[2] = static_cast<float>(output_width);
        parameters[3] = static_cast<float>(output_height);
        parameters[4] = static_cast<float>(output_x);
        parameters[5] = static_cast<float>(output_y);
        parameters[6] = static_cast<float>(tile_width);
        parameters[7] = static_cast<float>(tile_height);
        parameters[8] = static_cast<float>(crop.x);
        parameters[9] = static_cast<float>(crop.y);
        parameters[10] = static_cast<float>(crop.width);
        parameters[11] = static_cast<float>(crop.height);
        parameters[12] = static_cast<float>(source_gpu.cstep);
        parameters[13] = static_cast<float>(features_gpu.cstep);
        parameters[14] = static_cast<float>(static_cast<std::size_t>(tile_width) * tile_height);
        parameters[15] = static_cast<float>(strength_percent) * 0.01f;
        parameters[16] = (static_cast<float>(output_width) / source.width - 1.5f) / 0.5f;
        parameters[17] = (static_cast<float>(output_height) / source.height - 1.5f) / 0.5f;
        parameters[18] = static_cast<float>(output_width) / source.width;
        parameters[19] = static_cast<float>(features_gpu.elempack);
        parameters[20] = static_cast<float>(static_cast<double>(source.width) / output_width);
        parameters[21] = static_cast<float>(static_cast<double>(source.height) / output_height);
        ncnn::VkMat parameters_gpu;
        command.record_upload(parameter_host, parameters_gpu, trunk_.opt);

        const int tile_pixels = tile_width * tile_height;
        ncnn::VkMat output_gpu(tile_pixels * 3, sizeof(float), blob_vkallocator_);
        if (output_gpu.empty()) {
            last_error_ = "无法分配Vulkan RGB tile输出";
            return false;
        }
        std::vector<ncnn::VkMat> bindings(5);
        bindings[0] = features_gpu;
        bindings[1] = source_gpu;
        bindings[2] = head_weights_gpu_;
        bindings[3] = parameters_gpu;
        bindings[4] = output_gpu;
        const std::vector<ncnn::vk_constant_type> constants;
        ncnn::VkMat dispatcher;
        dispatcher.w = tile_width;
        dispatcher.h = tile_height;
        dispatcher.c = 1;
        command.record_pipeline(fused_pipeline_.get(), bindings, constants, dispatcher);

        ncnn::Mat output_host;
        command.record_download(output_gpu, output_host, trunk_.opt);
        if (command.submit_and_wait() != 0 || output_host.empty() || output_host.total() != static_cast<std::size_t>(tile_pixels) * 3u) {
            last_error_ = "Vulkan fused-head输出下载失败";
            return false;
        }
        const float* rgb = static_cast<const float*>(output_host.data);
        for (int y = 0; y < tile_height; ++y) {
            if (job_id <= highest_cancelled_job_.load(std::memory_order_acquire)) {
                last_error_ = "任务已取消";
                return false;
            }
            for (int x = 0; x < tile_width; ++x) {
                const SourcePosition coordinate = source_position(output_x + x, output_y + y,
                                                                   source.width, source.height,
                                                                   output_width, output_height);
                const std::size_t tile_index = static_cast<std::size_t>(y) * tile_width + x;
                const std::size_t output_index = (static_cast<std::size_t>(output_y + y) * output_width + output_x + x) * 4u;
                output[output_index] = float_to_byte(rgb[tile_index]);
                output[output_index + 1u] = float_to_byte(rgb[static_cast<std::size_t>(tile_pixels) + tile_index]);
                output[output_index + 2u] = float_to_byte(rgb[static_cast<std::size_t>(tile_pixels) * 2u + tile_index]);
                output[output_index + 3u] = float_to_byte(sample_alpha_bilinear(source, coordinate.x, coordinate.y));
            }
        }
        return true;
    }

    bool render_all_tiles(const Image& source, std::vector<std::uint8_t>& output,
                          int output_width, int output_height, int strength_percent,
                          std::uint64_t job_id, double& infer_ms, int& tiles) {
        last_error_.clear();
        for (int y = 0; y < output_height; y += tile_side_) {
            const int tile_height = std::min(tile_side_, output_height - y);
            for (int x = 0; x < output_width; x += tile_side_) {
                require_not_cancelled(job_id);
                const int tile_width = std::min(tile_side_, output_width - x);
                const TileCrop crop = crop_for_tile(x, y, tile_width, tile_height,
                                                    source.width, source.height, output_width, output_height);
                const Clock::time_point tile_start = Clock::now();
                ncnn::Mat source_crop = make_source_crop(source, crop);
                const bool tile_ok = backend_ == "vulkan"
                    ? infer_gpu_tile(source, source_crop, crop, x, y, tile_width, tile_height,
                                     output_width, output_height, strength_percent, job_id, output)
                    : infer_cpu_tile(source, source_crop, crop, x, y, tile_width, tile_height,
                                     output_width, output_height, strength_percent, job_id, output);
                infer_ms += elapsed_ms(tile_start);
                if (!tile_ok) return false;
                ++tiles;
#if !defined(__ANDROID__)
                if (test_tile_hook_) test_tile_hook_();
#endif
            }
        }
        return true;
    }

    bool encode_atomic_webp(std::vector<std::uint8_t>& rgba, int width, int height, const std::string& output_path,
                            std::uint64_t job_id, std::string& error) {
        if (job_id <= highest_cancelled_job_.load(std::memory_order_acquire)) {
            error = "任务已取消";
            return false;
        }
        const std::string pending_path = output_path + ".manyue-" + std::to_string(job_id) + ".pending";
        std::FILE* file = std::fopen(pending_path.c_str(), "wb");
        if (!file) {
            error = "无法创建临时WebP输出文件";
            return false;
        }
        WebPConfig config;
        WebPPicture picture;
        if (!WebPConfigInit(&config) || !WebPConfigLosslessPreset(&config, 0)) {
            std::fclose(file);
            std::remove(pending_path.c_str());
            error = "初始化WebP lossless配置失败";
            return false;
        }
        config.lossless = 1;
        // Lossless mode uses quality only as the compression effort. Zero is
        // the fastest effort setting; exactness remains controlled separately.
        config.quality = 0.0f;
        config.method = 0;
        config.exact = 1;
        config.thread_level = 0;
        if (!WebPPictureInit(&picture)) {
            std::fclose(file);
            std::remove(pending_path.c_str());
            error = "初始化WebP编码器失败";
            return false;
        }
        picture.use_argb = 1;
        picture.width = width;
        picture.height = height;
        if (!WebPPictureImportRGBA(&picture, rgba.data(), width * 4)) {
            WebPPictureFree(&picture);
            std::fclose(file);
            std::remove(pending_path.c_str());
            error = "准备WebP像素失败";
            return false;
        }

        // The WebP picture now owns its encoder-side pixels, so release the full RGBA staging image
        // before compression to keep peak resident memory bounded.
        std::vector<std::uint8_t>().swap(rgba);
        EncodeContext context;
        context.file = file;
        context.highest_cancelled_job = &highest_cancelled_job_;
        context.job_id = job_id;
#if !defined(__ANDROID__)
        context.progress_test_hook = &test_encode_progress_hook_;
#endif
        picture.writer = &write_webp_chunk;
        picture.custom_ptr = &context;
        picture.progress_hook = &webp_progress_hook;
        picture.user_data = &context;
        const int encoded = WebPEncode(&config, &picture);
        const int webp_error_code = static_cast<int>(picture.error_code);
        WebPPictureFree(&picture);
        const bool file_ok = std::fflush(file) == 0;
        const bool close_ok = std::fclose(file) == 0;
        if (!encoded || context.failed || !file_ok || !close_ok) {
            std::remove(pending_path.c_str());
            error = job_id <= highest_cancelled_job_.load(std::memory_order_acquire)
                ? "任务已取消"
                : "WebP编码或写入失败(code=" + std::to_string(webp_error_code) +
                    ",callbackFailed=" + (context.failed ? "true" : "false") +
                    ",writerOk=" + (file_ok ? "true" : "false") +
                    ",closeOk=" + (close_ok ? "true" : "false") + ")";
            return false;
        }
        {
            // Publication and cancellation have a single linearization order. If cancel wins
            // the mutex, no final file is published; if rename wins, the job is already done.
            std::lock_guard<std::mutex> publication_guard(publication_mutex_);
            if (job_id <= highest_cancelled_job_.load(std::memory_order_acquire)) {
                std::remove(pending_path.c_str());
                error = "任务已取消";
                return false;
            }
            if (!publish_file_atomically(pending_path, output_path, error)) {
                std::remove(pending_path.c_str());
                return false;
            }
        }
        return true;
    }

    int tile_side_;
    std::string model_directory_;
    std::string backend_;
    std::string gpu_name_;
    std::string gpu_fallback_reason_;
    std::string last_error_;
    int model_loads_ = 0;
    bool initialized_ = false;
    bool gpu_instance_acquired_ = false;
    ncnn::Net trunk_;
    ncnn::Net head_;
    ncnn::VulkanDevice* vkdev_ = nullptr;
    ncnn::VkAllocator* blob_vkallocator_ = nullptr;
    ncnn::VkAllocator* staging_vkallocator_ = nullptr;
    std::unique_ptr<ncnn::Pipeline> fused_pipeline_;
    ncnn::VkMat head_weights_gpu_;
    std::array<float, kHeadWeightCount> head_weights_{};
    std::atomic<std::uint64_t> highest_cancelled_job_{0};
    std::mutex publication_mutex_;
    std::mutex inference_mutex_;
#if !defined(__ANDROID__)
    std::function<void()> test_tile_hook_;
    std::function<void(int)> test_encode_progress_hook_;
    std::function<void(const char*, const float*, int, int, int, std::size_t, int, int)> test_head_input_hook_;
#endif
};

Engine::Engine(int tile_side) : impl_(new Impl(tile_side)) {}
Engine::~Engine() = default;

bool Engine::initialize(const std::string& model_directory, bool prefer_gpu, std::string& error) {
    return impl_->initialize(model_directory, prefer_gpu, error);
}

std::string Engine::upscale(std::uint64_t job_id, const std::string& input_path,
                            const std::string& output_path, int target_width,
                            int strength_percent) {
    return impl_->upscale(job_id, input_path, output_path, target_width, strength_percent);
}

void Engine::cancel(std::uint64_t job_id) noexcept { impl_->cancel(job_id); }

#if !defined(__ANDROID__)
void Engine::set_test_tile_hook(std::function<void()> hook) { impl_->set_test_tile_hook(std::move(hook)); }

void Engine::set_test_encode_progress_hook(std::function<void(int)> hook) {
    impl_->set_test_encode_progress_hook(std::move(hook));
}

void Engine::set_test_head_input_hook(
    std::function<void(const char*, const float*, int, int, int, std::size_t, int, int)> hook) {
    impl_->set_test_head_input_hook(std::move(hook));
}
#endif

} // namespace manyue_lite
