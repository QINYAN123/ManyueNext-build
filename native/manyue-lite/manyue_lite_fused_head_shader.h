#pragma once

namespace manyue_lite {

// Runtime-compiled by the pinned ncnn Vulkan SDK. The trunk output, source crop,
// 867 head floats, tile metadata, and RGB output remain VkMat storage buffers.
static const char kFusedHeadShader[] = R"glsl(#version 450
layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

layout(binding = 0) readonly buffer FeatureBuffer { float value[]; } feature_buffer;
layout(binding = 1) readonly buffer SourceBuffer { float value[]; } source_buffer;
layout(binding = 2) readonly buffer WeightBuffer { float value[]; } weight_buffer;
layout(binding = 3) readonly buffer ParameterBuffer { float value[]; } parameter_buffer;
layout(binding = 4) writeonly buffer OutputBuffer { float value[]; } output_buffer;

float parameter(int index) { return parameter_buffer.value[index]; }
int integer_parameter(int index) { return int(parameter_buffer.value[index] + 0.5); }

float cubic_weight(float distance) {
    const float a = -0.75;
    float x = abs(distance);
    if (x <= 1.0) return (a + 2.0) * x * x * x - (a + 3.0) * x * x + 1.0;
    if (x < 2.0) return a * x * x * x - 5.0 * a * x * x + 8.0 * a * x - 4.0 * a;
    return 0.0;
}

float feature_value(int channel, int pixel, int channel_stride, int pack) {
    int group = channel / pack;
    int lane = channel - group * pack;
    return feature_buffer.value[(group * channel_stride + pixel) * pack + lane];
}

float sample_feature(int channel, float x, float y) {
    int source_width = integer_parameter(0);
    int source_height = integer_parameter(1);
    int crop_x = integer_parameter(8);
    int crop_y = integer_parameter(9);
    int crop_width = integer_parameter(10);
    int crop_height = integer_parameter(11);
    int x0_global = clamp(int(floor(x)), 0, source_width - 1);
    int y0_global = clamp(int(floor(y)), 0, source_height - 1);
    float clamped_x = clamp(x, 0.0, float(source_width - 1));
    float clamped_y = clamp(y, 0.0, float(source_height - 1));
    x0_global = int(floor(clamped_x));
    y0_global = int(floor(clamped_y));
    int x1_global = min(x0_global + 1, source_width - 1);
    int y1_global = min(y0_global + 1, source_height - 1);
    int x0 = clamp(x0_global - crop_x, 0, crop_width - 1);
    int y0 = clamp(y0_global - crop_y, 0, crop_height - 1);
    int x1 = clamp(x1_global - crop_x, 0, crop_width - 1);
    int y1 = clamp(y1_global - crop_y, 0, crop_height - 1);
    float fx = clamped_x - floor(clamped_x);
    float fy = clamped_y - floor(clamped_y);
    int row_stride = crop_width;
    int channel_stride = integer_parameter(13);
    int pack = integer_parameter(19);
    float top = feature_value(channel, y0 * row_stride + x0, channel_stride, pack) * (1.0 - fx) +
                feature_value(channel, y0 * row_stride + x1, channel_stride, pack) * fx;
    float bottom = feature_value(channel, y1 * row_stride + x0, channel_stride, pack) * (1.0 - fx) +
                   feature_value(channel, y1 * row_stride + x1, channel_stride, pack) * fx;
    return top * (1.0 - fy) + bottom * fy;
}

float sample_base(int channel, float x, float y) {
    int source_width = integer_parameter(0);
    int source_height = integer_parameter(1);
    int crop_x = integer_parameter(8);
    int crop_y = integer_parameter(9);
    int crop_width = integer_parameter(10);
    int crop_height = integer_parameter(11);
    int source_x_base = int(floor(x));
    int source_y_base = int(floor(y));
    int channel_stride = integer_parameter(12);
    float result = 0.0;
    for (int j = -1; j <= 2; ++j) {
        int global_y = clamp(source_y_base + j, 0, source_height - 1);
        int local_y = clamp(global_y - crop_y, 0, crop_height - 1);
        float wy = cubic_weight(y - float(source_y_base + j));
        for (int i = -1; i <= 2; ++i) {
            int global_x = clamp(source_x_base + i, 0, source_width - 1);
            int local_x = clamp(global_x - crop_x, 0, crop_width - 1);
            float wx = cubic_weight(x - float(source_x_base + i));
            int index = channel * channel_stride + local_y * crop_width + local_x;
            result += source_buffer.value[index] * wx * wy;
        }
    }
    return result;
}

void main() {
    int tile_width = integer_parameter(6);
    int tile_height = integer_parameter(7);
    int local_x = int(gl_GlobalInvocationID.x);
    int local_y = int(gl_GlobalInvocationID.y);
    if (local_x >= tile_width || local_y >= tile_height) return;

    int source_width = integer_parameter(0);
    int source_height = integer_parameter(1);
    int output_width = integer_parameter(2);
    int output_height = integer_parameter(3);
    int tile_x = integer_parameter(4);
    int tile_y = integer_parameter(5);
    int output_stride = integer_parameter(14);

    int output_x = tile_x + local_x;
    int output_y = tile_y + local_y;
    float source_x = (float(output_x) + 0.5) * float(source_width) / float(output_width) - 0.5;
    float source_y = (float(output_y) + 0.5) * float(source_height) / float(output_height) - 0.5;

    float model_input[23];
    for (int channel = 0; channel < 16; ++channel) {
        model_input[channel] = sample_feature(channel, source_x, source_y);
    }
    float base[3];
    for (int channel = 0; channel < 3; ++channel) {
        base[channel] = sample_base(channel, source_x, source_y);
        model_input[16 + channel] = base[channel];
    }
    model_input[19] = parameter(16);
    model_input[20] = parameter(17);
    precise float phase_product_x = (float(output_x) + 0.5) * parameter(20);
    precise float phase_product_y = (float(output_y) + 0.5) * parameter(21);
    precise float phase_coordinate_x = phase_product_x - 0.5;
    precise float phase_coordinate_y = phase_product_y - 0.5;
    precise float phase_delta_x = phase_coordinate_x - roundEven(phase_coordinate_x);
    precise float phase_delta_y = phase_coordinate_y - roundEven(phase_coordinate_y);
    model_input[21] = phase_delta_x * 2.0;
    model_input[22] = phase_delta_y * 2.0;

    float hidden[32];
    for (int out_channel = 0; out_channel < 32; ++out_channel) {
        float value = weight_buffer.value[736 + out_channel];
        int row = out_channel * 23;
        for (int channel = 0; channel < 23; ++channel) {
            value += weight_buffer.value[row + channel] * model_input[channel];
        }
        hidden[out_channel] = max(0.0, value);
    }

    int output_index = local_y * tile_width + local_x;
    float strength = parameter(15);
    for (int out_channel = 0; out_channel < 3; ++out_channel) {
        float value = weight_buffer.value[864 + out_channel];
        int row = 768 + out_channel * 32;
        for (int channel = 0; channel < 32; ++channel) {
            value += weight_buffer.value[row + channel] * hidden[channel];
        }
        float limit = 0.02 + 0.02 * (parameter(18) - 1.0);
        float bounded_residual = clamp(value, -limit, limit);
        output_buffer.value[out_channel * output_stride + output_index] = clamp(base[out_channel] + bounded_residual * strength, 0.0, 1.0);
    }
}
)glsl";

} // namespace manyue_lite
