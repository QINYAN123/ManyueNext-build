#include "manyue_output_resize.h"

#include <chrono>
#include <cstdint>
#include <cstdio>
#include <vector>

int main()
{
    // Representative 2x model output for a 690x1421 manga page, resized to
    // the same 1.25x target dimensions used by the application tests.
    const int source_width = 1380;
    const int source_height = 2842;
    const int target_width = 863;
    const int target_height = 1777;
    const int channels = 3;
    const int rounds = 5;

    std::vector<uint8_t> source(static_cast<size_t>(source_width) * source_height * channels);
    std::vector<uint8_t> destination(static_cast<size_t>(target_width) * target_height * channels);
    for (size_t i = 0; i < source.size(); ++i)
        source[i] = static_cast<uint8_t>(i * 37u + 11u);

    const auto start = std::chrono::steady_clock::now();
    for (int i = 0; i < rounds; ++i)
    {
        if (!manyue_output::resize_area_u8(
                source.data(), source_width, source_height, channels,
                static_cast<size_t>(source_width) * channels, destination.data(),
                target_width, target_height, static_cast<size_t>(target_width) * channels))
            return 2;
    }
    const auto stop = std::chrono::steady_clock::now();

    unsigned long long checksum = 0;
    for (uint8_t byte : destination)
        checksum += byte;
    const double milliseconds =
            std::chrono::duration<double, std::milli>(stop - start).count() / rounds;
    std::printf("RGB area resize %dx%d -> %dx%d: %.2f ms/iteration, checksum=%llu\n",
                source_width, source_height, target_width, target_height,
                milliseconds, checksum);
    return 0;
}
