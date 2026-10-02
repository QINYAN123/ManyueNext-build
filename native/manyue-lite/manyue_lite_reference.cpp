#include "manyue_lite_reference.h"

#include <algorithm>
#include <cmath>

namespace manyue_lite {

double round_ties_to_even(double value) {
    const double floor_value = std::floor(value);
    const double fraction = value - floor_value;
    if (fraction < 0.5) return floor_value;
    if (fraction > 0.5) return floor_value + 1.0;
    const double parity = std::fmod(std::fabs(floor_value), 2.0);
    return parity == 0.0 ? floor_value : floor_value + 1.0;
}

SourcePosition source_position(int output_x, int output_y,
                               int source_width, int source_height,
                               int output_width, int output_height) {
    SourcePosition result{};
    result.x = (static_cast<double>(output_x) + 0.5) * source_width / output_width - 0.5;
    result.y = (static_cast<double>(output_y) + 0.5) * source_height / output_height - 0.5;
    return result;
}

SourceCoordinate source_coordinate(int output_x, int output_y,
                                   int source_width, int source_height,
                                   int output_width, int output_height) {
    SourceCoordinate result{};
    const SourcePosition position = source_position(output_x, output_y, source_width, source_height,
                                                    output_width, output_height);
    result.x = position.x;
    result.y = position.y;
    const double ratio_x = static_cast<double>(output_width) / source_width;
    const double ratio_y = static_cast<double>(output_height) / source_height;
    result.scale_x_condition = static_cast<float>((ratio_x - 1.5) / 0.5);
    result.scale_y_condition = static_cast<float>((ratio_y - 1.5) / 0.5);
    // Match the training tensor path: cast the scalar ratio to FP32 first, then
    // perform the product and subtraction as separate FP32 operations. Keep x/y
    // above in double precision because those coordinates drive the CPU sampler.
    const float phase_ratio_x = static_cast<float>(static_cast<double>(source_width) / output_width);
    const float phase_ratio_y = static_cast<float>(static_cast<double>(source_height) / output_height);
    const float phase_product_x = (static_cast<float>(output_x) + 0.5f) * phase_ratio_x;
    const float phase_product_y = (static_cast<float>(output_y) + 0.5f) * phase_ratio_y;
    const float phase_source_x = phase_product_x - 0.5f;
    const float phase_source_y = phase_product_y - 0.5f;
    const float rounded_phase_x = static_cast<float>(round_ties_to_even(phase_source_x));
    const float rounded_phase_y = static_cast<float>(round_ties_to_even(phase_source_y));
    const float phase_delta_x = phase_source_x - rounded_phase_x;
    const float phase_delta_y = phase_source_y - rounded_phase_y;
    result.phase_x = phase_delta_x * 2.0f;
    result.phase_y = phase_delta_y * 2.0f;
    return result;
}

float sample_bilinear_clamped(const float* chw, int width, int height,
                              std::size_t cstep, int channel,
                              double x, double y) {
    x = std::max(0.0, std::min(x, static_cast<double>(width - 1)));
    y = std::max(0.0, std::min(y, static_cast<double>(height - 1)));
    const int x0 = static_cast<int>(std::floor(x));
    const int y0 = static_cast<int>(std::floor(y));
    const int x1 = std::min(x0 + 1, width - 1);
    const int y1 = std::min(y0 + 1, height - 1);
    const float fx = static_cast<float>(x - x0);
    const float fy = static_cast<float>(y - y0);
    const float* plane = chw + static_cast<std::size_t>(channel) * cstep;
    const float top = plane[static_cast<std::size_t>(y0) * width + x0] * (1.0f - fx) +
        plane[static_cast<std::size_t>(y0) * width + x1] * fx;
    const float bottom = plane[static_cast<std::size_t>(y1) * width + x0] * (1.0f - fx) +
        plane[static_cast<std::size_t>(y1) * width + x1] * fx;
    return top * (1.0f - fy) + bottom * fy;
}

static double cubic_weight(double distance) {
    const double x = std::fabs(distance);
    constexpr double a = -0.75;
    if (x <= 1.0) {
        return (a + 2.0) * x * x * x - (a + 3.0) * x * x + 1.0;
    }
    if (x < 2.0) {
        return a * x * x * x - 5.0 * a * x * x + 8.0 * a * x - 4.0 * a;
    }
    return 0.0;
}

float sample_bicubic_clamped(const float* chw, int width, int height,
                             std::size_t cstep, int channel,
                             double x, double y) {
    const int x_base = static_cast<int>(std::floor(x));
    const int y_base = static_cast<int>(std::floor(y));
    const float* plane = chw + static_cast<std::size_t>(channel) * cstep;
    double value = 0.0;
    for (int j = -1; j <= 2; ++j) {
        const int sample_y = std::max(0, std::min(y_base + j, height - 1));
        const double wy = cubic_weight(y - (y_base + j));
        for (int i = -1; i <= 2; ++i) {
            const int sample_x = std::max(0, std::min(x_base + i, width - 1));
            const double wx = cubic_weight(x - (x_base + i));
            value += plane[static_cast<std::size_t>(sample_y) * width + sample_x] * wx * wy;
        }
    }
    return static_cast<float>(value);
}

void fused_head_reference(const float input[23], const float weights[867], float residual[3]) {
    const float* conv1_weights = weights;
    const float* conv1_bias = conv1_weights + 32 * 23;
    const float* conv2_weights = conv1_bias + 32;
    const float* conv2_bias = conv2_weights + 3 * 32;
    float hidden[32];
    for (int output = 0; output < 32; ++output) {
        float value = conv1_bias[output];
        const float* row = conv1_weights + output * 23;
        for (int input_channel = 0; input_channel < 23; ++input_channel) {
            value += row[input_channel] * input[input_channel];
        }
        hidden[output] = std::max(0.0f, value);
    }
    for (int output = 0; output < 3; ++output) {
        float value = conv2_bias[output];
        const float* row = conv2_weights + output * 32;
        for (int input_channel = 0; input_channel < 32; ++input_channel) {
            value += row[input_channel] * hidden[input_channel];
        }
        residual[output] = value;
    }
}

float residual_limit(float actual_scale_x) {
    return 0.02f + 0.02f * (actual_scale_x - 1.0f);
}

float clamp_residual(float residual, float actual_scale_x) {
    const float limit = residual_limit(actual_scale_x);
    return std::max(-limit, std::min(residual, limit));
}

} // namespace manyue_lite
