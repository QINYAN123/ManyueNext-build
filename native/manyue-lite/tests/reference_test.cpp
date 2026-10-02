#include "../manyue_lite_reference.h"

#include <cmath>
#include <cstdlib>
#include <iostream>
#include <vector>

namespace {

void require_near(double actual, double expected, double tolerance, const char* message) {
    if (std::fabs(actual - expected) > tolerance) {
        std::cerr << "FAIL: " << message << ": got " << actual << ", expected " << expected << '\n';
        std::exit(1);
    }
}

} // namespace

int main() {
    using manyue_lite::sample_bicubic_clamped;
    using manyue_lite::sample_bilinear_clamped;

    require_near(manyue_lite::round_ties_to_even(1.5), 2.0, 0.0, "positive half rounds to even");
    require_near(manyue_lite::round_ties_to_even(2.5), 2.0, 0.0, "positive odd half rounds down");
    require_near(manyue_lite::round_ties_to_even(-1.5), -2.0, 0.0, "negative half rounds to even");
    require_near(manyue_lite::round_ties_to_even(-2.5), -2.0, 0.0, "negative odd half rounds up");

    const auto mapped = manyue_lite::source_coordinate(1, 1, 2, 2, 3, 3);
    require_near(mapped.x, 0.5, 1e-12, "half-pixel x coordinate");
    require_near(mapped.y, 0.5, 1e-12, "half-pixel y coordinate");
    require_near(mapped.scale_x_condition, 0.0, 1e-7, "1.5x x condition");
    require_near(mapped.scale_y_condition, 0.0, 1e-7, "1.5x y condition");
    require_near(mapped.phase_x, 1.0, 1e-7, "x phase uses ties-to-even");
    require_near(mapped.phase_y, 1.0, 1e-7, "y phase uses ties-to-even");
    const auto even_phase = manyue_lite::source_coordinate(25, 0, 690, 985, 1173, 1675);
    require_near(even_phase.x, 14.5, 1e-12, "sampling coordinate remains double half-pixel");
    require_near(even_phase.phase_x, -0.99999809, 1e-6,
                 "690-to-1173 head phase uses FP32 ratio-first recipe");
    require_near(manyue_lite::residual_limit(1.0f), 0.02, 1e-8, "1x residual limit");
    require_near(manyue_lite::residual_limit(2.0f), 0.04, 1e-8, "2x residual limit");
    require_near(manyue_lite::clamp_residual(0.3f, 1.0f), 0.02, 1e-8, "positive residual is capped");
    require_near(manyue_lite::clamp_residual(-0.3f, 2.0f), -0.04, 1e-8, "negative residual is capped by scale");

    // CHW 2x2 plane: 0, 1 / 2, 3.
    const float plane[] = { 0.f, 1.f, 2.f, 3.f };
    require_near(sample_bilinear_clamped(plane, 2, 2, 4, 0, 0.5, 0.5), 1.5, 1e-7,
                 "bilinear interpolation at pixel center");
    require_near(sample_bilinear_clamped(plane, 2, 2, 4, 0, -10.0, 0.0), 0.0, 1e-7,
                 "feature sampling clamps at left edge");
    require_near(sample_bilinear_clamped(plane, 2, 2, 4, 0, 10.0, 1.0), 3.0, 1e-7,
                 "feature sampling clamps at right edge");

    const float constant[] = {
        0.25f, 0.25f,
        0.25f, 0.25f,
    };
    require_near(sample_bicubic_clamped(constant, 2, 2, 4, 0, -0.25, 1.25), 0.25, 1e-6,
                 "bicubic border extension preserves a constant plane");
    float linear_plane[25];
    for (int y = 0; y < 5; ++y) {
        for (int x = 0; x < 5; ++x) linear_plane[y * 5 + x] = static_cast<float>(x + 2 * y);
    }
    require_near(sample_bicubic_clamped(linear_plane, 5, 5, 25, 0, 2.5, 2.5), 7.5, 1e-6,
                 "bicubic interpolation reproduces an interior linear plane");

    float head_weights[867] = {};
    float head_input[23] = {};
    float residual[3] = {};
    head_input[0] = 1.0f;
    head_weights[0] = 2.0f;                // conv1[0, 0]
    head_weights[736] = 1.0f;              // conv1 bias[0]
    head_weights[768] = 3.0f;              // conv2[0, 0]
    manyue_lite::fused_head_reference(head_input, head_weights, residual);
    require_near(residual[0], 9.0, 1e-7, "fused head weight layout and ReLU");
    require_near(residual[1], 0.0, 1e-7, "fused head leaves unused output channels at zero");

    std::cout << "MANYUE_LITE_REFERENCE_OK\n";
    return 0;
}
