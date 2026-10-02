#pragma once

#include <cstddef>

namespace manyue_lite {

struct SourceCoordinate {
    double x;
    double y;
    float scale_x_condition;
    float scale_y_condition;
    float phase_x;
    float phase_y;
};

struct SourcePosition {
    double x;
    double y;
};

// Matches PyTorch's align_corners=false half-pixel coordinate and ties-to-even phase.
SourcePosition source_position(int output_x, int output_y,
                               int source_width, int source_height,
                               int output_width, int output_height);
SourceCoordinate source_coordinate(int output_x, int output_y,
                                   int source_width, int source_height,
                                   int output_width, int output_height);
double round_ties_to_even(double value);

// Input and output tensors are planar CHW float32. Coordinates are global source pixels.
float sample_bilinear_clamped(const float* chw, int width, int height,
                              std::size_t cstep, int channel,
                              double x, double y);
float sample_bicubic_clamped(const float* chw, int width, int height,
                             std::size_t cstep, int channel,
                             double x, double y);

// Fixed contract for head.param: 23->32 1x1 ReLU, then 32->3 1x1 residual.
// weights contains conv1 OIHW, conv1 bias, conv2 OIHW, conv2 bias (867 floats total).
void fused_head_reference(const float input[23], const float weights[867], float residual[3]);

// Scale-dependent bound for the raw residual before strength is applied.
float residual_limit(float actual_scale_x);
float clamp_residual(float residual, float actual_scale_x);

} // namespace manyue_lite
