#ifndef MANYUE_NEUTRAL_SOURCE_H
#define MANYUE_NEUTRAL_SOURCE_H

// Small packed-RGB helpers for preserving grayscale intent in Real-ESRGAN
// output. All operations are allocation-free and validate the accessible row
// geometry before reading or writing pixels.

#include <cstddef>
#include <cstdint>

namespace manyue_neutral {

inline bool packed_row_bytes(int width, int channels, size_t* row_bytes) {
    if (!row_bytes || width <= 0 || (channels != 3 && channels != 4)) {
        return false;
    }
    const size_t width_size = static_cast<size_t>(width);
    const size_t channel_size = static_cast<size_t>(channels);
    if (width_size > static_cast<size_t>(-1) / channel_size) {
        return false;
    }
    *row_bytes = width_size * channel_size;
    return true;
}

inline bool valid_packed_rows(const uint8_t* pixels, int width, int height,
                              int channels, size_t stride, size_t* row_bytes) {
    size_t bytes = 0;
    if (!pixels || height <= 0 || !packed_row_bytes(width, channels, &bytes) ||
        stride < bytes) {
        return false;
    }

    const size_t preceding_rows = static_cast<size_t>(height - 1);
    const size_t max_size = static_cast<size_t>(-1);
    if (preceding_rows > (max_size - bytes) / stride) {
        return false;
    }
    if (row_bytes) *row_bytes = bytes;
    return true;
}

// Returns false for malformed geometry. For RGBA, transparent pixels do not
// influence classification and at least one visible pixel is required.
inline bool classify_neutral_source(const uint8_t* pixels, int width,
                                    int height, int channels, size_t stride,
                                    bool* is_neutral) {
    if (!is_neutral) return false;
    *is_neutral = false;

    size_t row_bytes = 0;
    if (!valid_packed_rows(pixels, width, height, channels, stride, &row_bytes)) {
        return false;
    }
    (void)row_bytes;

    bool has_visible_pixel = channels == 3;
    bool all_pixels_neutral = true;
    for (int y = 0; y < height; ++y) {
        const uint8_t* row = pixels + static_cast<size_t>(y) * stride;
        for (int x = 0; x < width; ++x) {
            const uint8_t* pixel = row + static_cast<size_t>(x) * channels;
            if (channels == 4 && pixel[3] == 0) continue;
            has_visible_pixel = true;
            if (pixel[0] != pixel[1] || pixel[1] != pixel[2]) {
                all_pixels_neutral = false;
            }
        }
    }

    *is_neutral = has_visible_pixel && all_pixels_neutral;
    return true;
}

// Apply fixed integer luma in-place, copying the result to R, G, and B while
// leaving an RGBA alpha byte untouched.
inline bool project_neutral_rgb_in_place(uint8_t* pixels, int width,
                                         int height, int channels,
                                         size_t stride) {
    size_t row_bytes = 0;
    if (!valid_packed_rows(pixels, width, height, channels, stride, &row_bytes)) {
        return false;
    }
    (void)row_bytes;

    for (int y = 0; y < height; ++y) {
        uint8_t* row = pixels + static_cast<size_t>(y) * stride;
        for (int x = 0; x < width; ++x) {
            uint8_t* pixel = row + static_cast<size_t>(x) * channels;
            const uint32_t luma = (54u * pixel[0] + 183u * pixel[1] +
                                   19u * pixel[2] + 128u) >> 8;
            pixel[0] = pixel[1] = pixel[2] = static_cast<uint8_t>(luma);
        }
    }
    return true;
}

}  // namespace manyue_neutral

#endif  // MANYUE_NEUTRAL_SOURCE_H
