#include "manyue_neutral_source.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <limits>

#define CHECK(condition) do { \
    if (!(condition)) { \
        std::fprintf(stderr, "check failed at %s:%d: %s\n", __FILE__, __LINE__, #condition); \
        std::exit(1); \
    } \
} while (0)

static void test_exact_rgb_classification() {
    const uint8_t gray[] = {
        12, 12, 12, 200, 200, 200,
        0, 0, 0, 255, 255, 255,
    };
    bool neutral = false;
    CHECK(manyue_neutral::classify_neutral_source(gray, 2, 2, 3, 6, &neutral));
    CHECK(neutral);

    uint8_t accented[sizeof(gray)];
    for (size_t i = 0; i < sizeof(gray); ++i) accented[i] = gray[i];
    accented[sizeof(accented) - 1] = 254;
    CHECK(manyue_neutral::classify_neutral_source(accented, 2, 2, 3, 6, &neutral));
    CHECK(!neutral);
}

static void test_rgba_visibility_and_one_value_accent() {
    const uint8_t transparent_accent_and_visible_gray[] = {
        255, 0, 127, 0,
        91, 91, 91, 255,
    };
    bool neutral = false;
    CHECK(manyue_neutral::classify_neutral_source(
        transparent_accent_and_visible_gray, 2, 1, 4, 8, &neutral));
    CHECK(neutral);

    uint8_t one_value_accent[] = {
        91, 91, 91, 255,
        91, 92, 91, 255,
    };
    CHECK(manyue_neutral::classify_neutral_source(one_value_accent, 2, 1, 4, 8, &neutral));
    CHECK(!neutral);

    const uint8_t fully_transparent[] = {8, 9, 10, 0, 250, 1, 255, 0};
    CHECK(manyue_neutral::classify_neutral_source(fully_transparent, 2, 1, 4, 8, &neutral));
    CHECK(!neutral);
}

static void test_strides_and_border_pixels() {
    const uint8_t padded[] = {
        32, 32, 32, 0xA5, 0xA5,
        70, 70, 70, 0x5A, 0x5A,
    };
    bool neutral = false;
    CHECK(manyue_neutral::classify_neutral_source(padded, 1, 2, 3, 5, &neutral));
    CHECK(neutral);

    uint8_t padded_accent[sizeof(padded)];
    for (size_t i = 0; i < sizeof(padded); ++i) padded_accent[i] = padded[i];
    padded_accent[sizeof(padded_accent) - 1] = 0;
    // Padding is outside the final pixel and must not be treated as color data.
    CHECK(manyue_neutral::classify_neutral_source(padded_accent, 1, 2, 3, 5, &neutral));
    CHECK(neutral);

    uint8_t border_accent[] = {
        32, 32, 32, 0xA5, 0xA5,
        70, 71, 70, 0x5A, 0x5A,
    };
    CHECK(manyue_neutral::classify_neutral_source(border_accent, 1, 2, 3, 5, &neutral));
    CHECK(!neutral);
}

static void test_in_place_luma_and_alpha() {
    uint8_t rgba[] = {
        255, 0, 0, 17,
        0, 255, 0, 0,
        0, 0, 255, 255,
        0xA5, 0xA5, 0xA5, 0xA5,
    };
    CHECK(manyue_neutral::project_neutral_rgb_in_place(rgba, 1, 3, 4, 4));
    CHECK(rgba[0] == 54 && rgba[1] == 54 && rgba[2] == 54 && rgba[3] == 17);
    CHECK(rgba[4] == 182 && rgba[5] == 182 && rgba[6] == 182 && rgba[7] == 0);
    CHECK(rgba[8] == 19 && rgba[9] == 19 && rgba[10] == 19 && rgba[11] == 255);

    uint8_t rgb_with_padding[] = {25, 25, 25, 0xCC, 0xCC};
    CHECK(manyue_neutral::project_neutral_rgb_in_place(rgb_with_padding, 1, 1, 3, 5));
    CHECK(rgb_with_padding[0] == 25 && rgb_with_padding[1] == 25 && rgb_with_padding[2] == 25);
    CHECK(rgb_with_padding[3] == 0xCC && rgb_with_padding[4] == 0xCC);
}

static void test_invalid_geometry_and_overflow() {
    uint8_t pixels[] = {1, 1, 1, 2};
    bool neutral = true;
    CHECK(!manyue_neutral::classify_neutral_source(nullptr, 1, 1, 3, 3, &neutral));
    CHECK(!neutral);
    CHECK(!manyue_neutral::classify_neutral_source(pixels, 0, 1, 3, 3, &neutral));
    CHECK(!manyue_neutral::classify_neutral_source(pixels, 1, 0, 3, 3, &neutral));
    CHECK(!manyue_neutral::classify_neutral_source(pixels, 1, 1, 2, 2, &neutral));
    CHECK(!manyue_neutral::classify_neutral_source(pixels, 1, 1, 3, 2, &neutral));
    CHECK(!manyue_neutral::classify_neutral_source(
        reinterpret_cast<const uint8_t*>(static_cast<uintptr_t>(1)), 1, 2, 3,
        (std::numeric_limits<size_t>::max)(), &neutral));
    CHECK(!manyue_neutral::project_neutral_rgb_in_place(
        reinterpret_cast<uint8_t*>(static_cast<uintptr_t>(1)), 1, 2, 3,
        (std::numeric_limits<size_t>::max)()));
    CHECK(!manyue_neutral::packed_row_bytes(1, 3, nullptr));
}

int main() {
    test_exact_rgb_classification();
    test_rgba_visibility_and_one_value_accent();
    test_strides_and_border_pixels();
    test_in_place_luma_and_alpha();
    test_invalid_geometry_and_overflow();
    std::puts("neutral source tests passed");
    return 0;
}
