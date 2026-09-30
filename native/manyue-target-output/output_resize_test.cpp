#include "manyue_output_resize.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>

using manyue_output::Size;

#define CHECK(condition) do { \
    if (!(condition)) { \
        std::fprintf(stderr, "check failed at %s:%d: %s\n", __FILE__, __LINE__, #condition); \
        std::exit(1); \
    } \
} while (0)

static void test_target_dimensions() {
    Size size = {0, 0};
    CHECK(manyue_output::compute_target_size(690, 1421, 690, &size));
    CHECK(size.width == 690 && size.height == 1421);
    CHECK(manyue_output::compute_target_size(690, 1421, 863, &size));
    CHECK(size.width == 863 && size.height == 1777);
    CHECK(manyue_output::compute_target_size(690, 1421, 1035, &size));
    CHECK(size.width == 1035 && size.height == 2132);
    CHECK(manyue_output::compute_target_size(690, 1421, 1380, &size));
    CHECK(size.width == 1380 && size.height == 2842);
    CHECK(!manyue_output::compute_target_size(690, 1421, 689, &size));
    CHECK(!manyue_output::compute_target_size(690, 1421, 1381, &size));
    CHECK(!manyue_output::compute_target_size(690, 1421, 0, &size));
    CHECK(!manyue_output::compute_target_size(0, 1421, 690, &size));
}

static void test_rgb_area_average() {
    const uint8_t source[] = {
          0,  30,  60,   60,  90, 120,
        120, 150, 180,  180, 210, 240,
    };
    uint8_t destination[] = {0, 0, 0};
    CHECK(manyue_output::resize_area_u8(source, 2, 2, 3, 6,
                                         destination, 1, 1, 3));
    CHECK(destination[0] == 90);
    CHECK(destination[1] == 120);
    CHECK(destination[2] == 150);
}

static void test_rgba_alpha_weighting() {
    const uint8_t source[] = {
        255, 0, 0, 255,
        0, 0, 255, 0,
    };
    uint8_t destination[] = {0, 0, 0, 0};
    CHECK(manyue_output::resize_area_u8(source, 2, 1, 4, 8,
                                         destination, 1, 1, 4));
    CHECK(destination[0] == 255);
    CHECK(destination[1] == 0);
    CHECK(destination[2] == 0);
    CHECK(destination[3] == 128);
}

static void test_identity_and_validation() {
    uint8_t pixel[] = {12, 34, 56};
    const uint8_t original[] = {12, 34, 56};
    CHECK(manyue_output::resize_area_u8(pixel, 1, 1, 3, 3,
                                         pixel, 1, 1, 3));
    for (int i = 0; i < 3; ++i) CHECK(pixel[i] == original[i]);

    CHECK(!manyue_output::resize_area_u8(nullptr, 2, 2, 3, 6,
                                          pixel, 1, 1, 3));
    CHECK(!manyue_output::resize_area_u8(pixel, 2, 2, 3, 5,
                                          pixel, 1, 1, 3));
    CHECK(!manyue_output::resize_area_u8(pixel, 1, 1, 1, 1,
                                          pixel, 1, 1, 1));
    CHECK(!manyue_output::resize_area_u8(pixel, 1, 1, 3, 3,
                                          pixel, 2, 1, 6));
}

int main() {
    test_target_dimensions();
    test_rgb_area_average();
    test_rgba_alpha_weighting();
    test_identity_and_validation();
    std::puts("output resize tests passed");
    return 0;
}
