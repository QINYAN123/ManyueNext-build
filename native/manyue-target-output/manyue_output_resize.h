#ifndef MANYUE_OUTPUT_RESIZE_H
#define MANYUE_OUTPUT_RESIZE_H

// Small dependency-free helpers for the final, output-only resize. The AI
// model still runs at 2x; this code only downsamples its packed RGB/RGBA bytes
// to the requested output dimensions before encoding.

#include <cstddef>
#include <cstdint>
#include <climits>
#include <cmath>

namespace manyue_output {

struct Size {
    int width;
    int height;
};

inline bool compute_target_size(int source_width, int source_height,
                                int target_width, Size* target) {
    if (!target || source_width <= 0 || source_height <= 0 || target_width <= 0) {
        return false;
    }

    const int64_t min_width = source_width;
    const int64_t max_width = static_cast<int64_t>(source_width) * 2;
    if (target_width < min_width || static_cast<int64_t>(target_width) > max_width) {
        return false;
    }

    // Positive half-up rounding, matching Math.round for the Kotlin caller.
    const int64_t numerator = static_cast<int64_t>(source_height) * target_width
                            + source_width / 2;
    const int64_t target_height = numerator / source_width;
    if (target_height <= 0 || target_height > INT_MAX) {
        return false;
    }

    target->width = target_width;
    target->height = static_cast<int>(target_height);
    return true;
}

inline bool resize_area_u8(const uint8_t* source,
                           int source_width, int source_height,
                           int channels, size_t source_stride,
                           uint8_t* destination,
                           int target_width, int target_height,
                           size_t destination_stride) {
    if (!source || !destination || source_width <= 0 || source_height <= 0 ||
        target_width <= 0 || target_height <= 0 ||
        target_width > source_width || target_height > source_height ||
        (channels != 3 && channels != 4)) {
        return false;
    }

    if (static_cast<size_t>(source_width) > SIZE_MAX / static_cast<size_t>(channels) ||
        static_cast<size_t>(target_width) > SIZE_MAX / static_cast<size_t>(channels)) {
        return false;
    }
    const size_t source_row_bytes = static_cast<size_t>(source_width) * channels;
    const size_t target_row_bytes = static_cast<size_t>(target_width) * channels;
    if (source_stride < source_row_bytes || destination_stride < target_row_bytes) {
        return false;
    }

    // The caller skips allocation and copying when the dimensions already
    // match. This guard also makes this function safe when passed the same
    // buffer for an identity operation.
    if (source_width == target_width && source_height == target_height) {
        return true;
    }

    const double scale_x = static_cast<double>(source_width) / target_width;
    const double scale_y = static_cast<double>(source_height) / target_height;

    for (int out_y = 0; out_y < target_height; ++out_y) {
        const double y0 = out_y * scale_y;
        const double y1 = (out_y + 1) * scale_y;
        const int first_y = static_cast<int>(std::floor(y0));
        int end_y = static_cast<int>(std::ceil(y1));
        const int clipped_end_y = end_y < source_height ? end_y : source_height;

        uint8_t* out_row = destination + static_cast<size_t>(out_y) * destination_stride;
        for (int out_x = 0; out_x < target_width; ++out_x) {
            const double x0 = out_x * scale_x;
            const double x1 = (out_x + 1) * scale_x;
            const int first_x = static_cast<int>(std::floor(x0));
            int end_x = static_cast<int>(std::ceil(x1));
            const int clipped_end_x = end_x < source_width ? end_x : source_width;

            double total_weight = 0.0;
            double channel_sum[3] = {0.0, 0.0, 0.0};
            double alpha_sum = 0.0;

            for (int in_y = first_y; in_y < clipped_end_y; ++in_y) {
                const double overlap_y =
                    (y1 < in_y + 1.0 ? y1 : in_y + 1.0) -
                    (y0 > in_y ? y0 : static_cast<double>(in_y));
                if (overlap_y <= 0.0) continue;

                const uint8_t* in_row = source + static_cast<size_t>(in_y) * source_stride;
                for (int in_x = first_x; in_x < clipped_end_x; ++in_x) {
                    const double overlap_x =
                        (x1 < in_x + 1.0 ? x1 : in_x + 1.0) -
                        (x0 > in_x ? x0 : static_cast<double>(in_x));
                    if (overlap_x <= 0.0) continue;

                    const double weight = overlap_x * overlap_y;
                    const uint8_t* pixel = in_row + static_cast<size_t>(in_x) * channels;
                    total_weight += weight;
                    if (channels == 4) {
                        const double alpha = pixel[3];
                        alpha_sum += alpha * weight;
                        for (int channel = 0; channel < 3; ++channel) {
                            channel_sum[channel] += pixel[channel] * alpha * weight;
                        }
                    } else {
                        for (int channel = 0; channel < 3; ++channel) {
                            channel_sum[channel] += pixel[channel] * weight;
                        }
                    }
                }
            }

            if (total_weight <= 0.0) return false;
            uint8_t* output = out_row + static_cast<size_t>(out_x) * channels;
            if (channels == 4) {
                output[3] = static_cast<uint8_t>(std::floor(alpha_sum / total_weight + 0.5));
                if (alpha_sum > 0.0) {
                    for (int channel = 0; channel < 3; ++channel) {
                        const double value = channel_sum[channel] / alpha_sum;
                        output[channel] = static_cast<uint8_t>(std::floor(value + 0.5));
                    }
                } else {
                    output[0] = output[1] = output[2] = 0;
                }
            } else {
                for (int channel = 0; channel < 3; ++channel) {
                    const double value = channel_sum[channel] / total_weight;
                    output[channel] = static_cast<uint8_t>(std::floor(value + 0.5));
                }
            }
        }
    }

    return true;
}

}  // namespace manyue_output

#endif  // MANYUE_OUTPUT_RESIZE_H
