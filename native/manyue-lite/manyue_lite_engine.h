#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>

namespace manyue_lite {

class Engine {
public:
    explicit Engine(int tile_side = 128);
    ~Engine();

    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    bool initialize(const std::string& model_directory, bool prefer_gpu, std::string& error);
    std::string upscale(std::uint64_t job_id,
                        const std::string& input_path,
                        const std::string& output_path,
                        int target_width,
                        int strength_percent);
    void cancel(std::uint64_t job_id) noexcept;

#if !defined(__ANDROID__)
    // Host-only deterministic cancellation hooks for integration regression tests.
    void set_test_tile_hook(std::function<void()> hook);
    void set_test_encode_progress_hook(std::function<void(int)> hook);
    void set_test_head_input_hook(
        std::function<void(const char*, const float*, int, int, int, std::size_t, int, int)> hook);
#endif

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace manyue_lite
