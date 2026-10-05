#include "manyue_fast_webp.h"
#include <webp/decode.h>
#include <fstream>
#include <iterator>
#include <vector>
#include <stdexcept>
#include <string>

static void require(bool ok, const char* message) {
    if (!ok) throw std::runtime_error(message);
}

int main(int argc, char** argv) {
    try {
        require(argc == 2, "output prefix required");
        for (int channels : {3, 4}) {
            const int width = 137, height = 91;
            std::vector<unsigned char> pixels(width * height * channels);
            for (size_t i = 0; i < pixels.size(); ++i) pixels[i] = (i * 79 + i / 23) % 256;
            // Preserve RGB even behind fully transparent pixels (WebP exact=1).
            if (channels == 4) for (int i = 0; i < width * height; i += 7) pixels[i * 4 + 3] = 0;
            const std::string path = std::string(argv[1]) + std::to_string(channels) + ".webp";
            require(manyue_output::save_fast_lossless_webp(path.c_str(), width, height, channels, pixels.data()) == 1,
                    "encoding failed");
            std::ifstream input(path, std::ios::binary);
            const std::vector<unsigned char> bytes((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
            input.close();
            int decoded_w = 0, decoded_h = 0;
            unsigned char* decoded = WebPDecodeRGBA(bytes.data(), bytes.size(), &decoded_w, &decoded_h);
            require(decoded && decoded_w == width && decoded_h == height, "decode dimensions wrong");
            bool equal = true;
            for (int i = 0; i < width * height; ++i) {
                for (int c = 0; c < channels; ++c) equal &= decoded[i * 4 + c] == pixels[i * channels + c];
                if (channels == 3) equal &= decoded[i * 4 + 3] == 255;
            }
            WebPFree(decoded);
            require(equal, "lossless RGB or alpha mismatch");
            require(manyue_output::save_fast_lossless_webp(path.c_str(), 0, height, channels, pixels.data()) == 0,
                    "invalid image returned success");
            require(std::ifstream(path).fail(), "failed output remains");
            const std::string bad_path = std::string(argv[1]) + "-missing/child.webp";
            require(manyue_output::save_fast_lossless_webp(bad_path.c_str(), width, height, channels, pixels.data()) == 0,
                    "unwritable path returned success");
        }
        const std::string boundary = std::string(argv[1]) + "-boundary.webp";
        std::vector<unsigned char> strip(WEBP_MAX_DIMENSION * 4u, 127);
        require(manyue_output::save_fast_lossless_webp(boundary.c_str(), 1, WEBP_MAX_DIMENSION, 4, strip.data()) == 1,
                "WebP max valid height rejected");
        require(manyue_output::save_fast_lossless_webp(boundary.c_str(), 1, WEBP_MAX_DIMENSION + 1, 4, strip.data()) == 0,
                "WebP invalid height accepted");
        require(std::ifstream(boundary).fail(), "oversize WebP output remains");
        require(manyue_output::save_fast_lossless_webp(boundary.c_str(), WEBP_MAX_DIMENSION + 1, 1, 4, strip.data()) == 0,
                "WebP invalid width accepted");
        puts("fast lossless WebP tests passed: RGB, RGBA including transparent RGB, invalid input, write failure, axis limits");
        return 0;
    } catch (const std::exception& e) {
        fprintf(stderr, "%s\n", e.what());
        return 1;
    }
}
