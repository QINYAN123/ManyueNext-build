#include "manyue_fast_png.h"

#include <algorithm>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

static void require(bool ok, const char* message) {
    if (!ok)
        throw std::runtime_error(message);
}

struct ParsedPng {
    int width;
    int height;
    int channels;
    size_t max_idat_size;
    size_t idat_count;
    std::vector<unsigned char> decoded_rows;
};

static ParsedPng decode_and_check_png(const std::string& path) {
    std::ifstream file(path.c_str(), std::ios::binary);
    require(static_cast<bool>(file), "PNG fixture could not be opened");
    const std::vector<unsigned char> bytes(
            (std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
    static const unsigned char signature[8] = {137, 80, 78, 71, 13, 10, 26, 10};
    require(bytes.size() >= sizeof(signature) &&
            std::equal(signature, signature + sizeof(signature), bytes.begin()),
            "PNG signature is invalid");

    ParsedPng result = {};
    std::vector<unsigned char> compressed;
    bool saw_ihdr = false;
    bool saw_iend = false;
    size_t offset = sizeof(signature);
    while (offset + 12 <= bytes.size()) {
        const uint32_t chunk_size = manyue_output::fast_png_detail::load_be32(
                bytes.data() + offset);
        require(static_cast<size_t>(chunk_size) <= bytes.size() - offset - 12,
                "PNG chunk extends beyond the fixture");
        const unsigned char* type = bytes.data() + offset + 4;
        const unsigned char* data = bytes.data() + offset + 8;
        const uint32_t stored_crc = manyue_output::fast_png_detail::load_be32(
                data + chunk_size);
        uLong computed_crc = crc32(0L, Z_NULL, 0);
        computed_crc = crc32(computed_crc, type, 4);
        if (chunk_size != 0)
            computed_crc = crc32(computed_crc, data, static_cast<uInt>(chunk_size));
        require(static_cast<uint32_t>(computed_crc) == stored_crc,
                "PNG chunk CRC mismatch");

        if (type[0] == 'I' && type[1] == 'H' && type[2] == 'D' && type[3] == 'R') {
            require(!saw_ihdr && chunk_size == 13, "PNG IHDR is missing or malformed");
            result.width = static_cast<int>(manyue_output::fast_png_detail::load_be32(data));
            result.height = static_cast<int>(manyue_output::fast_png_detail::load_be32(data + 4));
            require(data[8] == 8 && data[10] == 0 && data[11] == 0 && data[12] == 0,
                    "PNG uses an unexpected depth, compression, filter, or interlace mode");
            result.channels = data[9] == 2 ? 3 : data[9] == 6 ? 4 : 0;
            require(result.channels != 0, "PNG color type is not RGB or RGBA");
            saw_ihdr = true;
        } else if (type[0] == 'I' && type[1] == 'D' && type[2] == 'A' && type[3] == 'T') {
            require(saw_ihdr && !saw_iend, "PNG IDAT is out of order");
            require(chunk_size <= 64u * 1024u, "PNG IDAT exceeds the bounded output buffer");
            result.max_idat_size = std::max(result.max_idat_size,
                                             static_cast<size_t>(chunk_size));
            ++result.idat_count;
            compressed.insert(compressed.end(), data, data + chunk_size);
        } else if (type[0] == 'I' && type[1] == 'E' && type[2] == 'N' && type[3] == 'D') {
            require(chunk_size == 0 && saw_ihdr, "PNG IEND is malformed");
            saw_iend = true;
            offset += 12;
            break;
        } else {
            require(false, "unexpected ancillary or critical PNG chunk");
        }
        offset += static_cast<size_t>(chunk_size) + 12;
    }
    require(saw_ihdr && saw_iend && offset == bytes.size(), "PNG chunk sequence is incomplete");
    require(result.width > 0 && result.height > 0, "PNG dimensions are invalid");

    const size_t row_bytes = static_cast<size_t>(result.width) * result.channels;
    result.decoded_rows.resize((row_bytes + 1) * static_cast<size_t>(result.height));
    uLongf decoded_size = static_cast<uLongf>(result.decoded_rows.size());
    const int inflate_status = uncompress(result.decoded_rows.data(), &decoded_size,
                                          compressed.data(),
                                          static_cast<uLong>(compressed.size()));
    require(inflate_status == Z_OK && decoded_size == result.decoded_rows.size(),
            "IDAT zlib stream could not be decoded");
    return result;
}

static std::vector<unsigned char> make_pixels(int width, int height, int channels) {
    std::vector<unsigned char> pixels(
            static_cast<size_t>(width) * height * channels);
    for (size_t i = 0; i < pixels.size(); ++i)
        pixels[i] = static_cast<unsigned char>((i * 79u + i / 23u + 17u) & 0xffu);
    if (channels == 4) {
        for (size_t i = 0; i < static_cast<size_t>(width) * height; i += 5) {
            pixels[i * 4 + 3] = 0;
            // Keep nonzero RGB under alpha zero to detect accidental premultiplication.
            pixels[i * 4 + 0] = 0x13;
            pixels[i * 4 + 1] = 0x7b;
            pixels[i * 4 + 2] = 0xe1;
        }
    }
    return pixels;
}

static void write_fixture(const std::string& path, int width, int height, int channels,
                          const std::vector<unsigned char>& pixels,
                          size_t requested_stride = 0) {
    manyue_output::CFileOutputContext context = {};
    manyue_output::CheckedFileOutput output;
    require(manyue_output::open_file_output(path.c_str(), &context, &output),
            "PNG fixture output could not be opened");
    const size_t stride = requested_stride == 0
            ? static_cast<size_t>(width) * channels : requested_stride;
    const bool encoded = manyue_output::write_fast_png(
            &output, width, height, channels, pixels.data(), stride);
    require(output.finish(encoded), "PNG fixture encode/write/flush/close failed");

    const ParsedPng parsed = decode_and_check_png(path);
    require(parsed.width == width && parsed.height == height && parsed.channels == channels,
            "PNG IHDR does not match fixture dimensions or channels");
    const size_t row_bytes = static_cast<size_t>(width) * channels;
    require(parsed.decoded_rows.size() == (row_bytes + 1) * static_cast<size_t>(height),
            "decoded scanline size is wrong");
    for (int y = 0; y < height; ++y) {
        const size_t decoded_offset = static_cast<size_t>(y) * (row_bytes + 1);
        require(parsed.decoded_rows[decoded_offset] == 0,
                "scanline does not use filter type 0");
        const size_t source_offset = static_cast<size_t>(y) * stride;
        require(std::equal(pixels.begin() + source_offset,
                           pixels.begin() + source_offset + row_bytes,
                           parsed.decoded_rows.begin() + decoded_offset + 1),
                "decoded PNG pixels differ from source bytes");
    }
}

struct FailingOutput {
    size_t write_calls;
    size_t fail_call;
    int flush_calls;
    int close_calls;
    int remove_calls;
};

static size_t failing_write(void* context, const void*, size_t length) {
    FailingOutput* output = static_cast<FailingOutput*>(context);
    const size_t call = output->write_calls++;
    return call == output->fail_call && length != 0 ? length - 1 : length;
}

static int record_flush(void* context) {
    ++static_cast<FailingOutput*>(context)->flush_calls;
    return 0;
}

static int record_close(void* context) {
    ++static_cast<FailingOutput*>(context)->close_calls;
    return 0;
}

static int record_remove(void* context, const char*) {
    ++static_cast<FailingOutput*>(context)->remove_calls;
    return 0;
}

static void check_invalid_inputs_and_short_write(const std::string& prefix) {
    const unsigned char pixel[4] = {1, 2, 3, 4};
    const int invalid_cases[][3] = {{0, 1, 4}, {1, -1, 4}, {1, 1, 2}};
    for (size_t i = 0; i < sizeof(invalid_cases) / sizeof(invalid_cases[0]); ++i) {
        const std::string path = prefix + "-invalid-" + std::to_string(i) + ".png";
        manyue_output::CFileOutputContext context = {};
        manyue_output::CheckedFileOutput output;
        require(manyue_output::open_file_output(path.c_str(), &context, &output),
                "invalid-input test output could not be opened");
        const bool encoded = manyue_output::write_fast_png(
                &output, invalid_cases[i][0], invalid_cases[i][1],
                invalid_cases[i][2], pixel);
        require(!encoded && !output.finish(encoded), "invalid dimensions/channels were accepted");
        require(std::ifstream(path.c_str()).fail(), "invalid PNG output was not removed");
    }

    const std::string stride_path = prefix + "-invalid-stride.png";
    manyue_output::CFileOutputContext stride_context = {};
    manyue_output::CheckedFileOutput stride_output;
    require(manyue_output::open_file_output(stride_path.c_str(), &stride_context, &stride_output),
            "invalid-stride test output could not be opened");
    const bool stride_encoded = manyue_output::write_fast_png(
            &stride_output, 1, 1, 4, pixel, 3);
    require(!stride_encoded && !stride_output.finish(stride_encoded),
            "short row stride was accepted");
    require(std::ifstream(stride_path.c_str()).fail(), "invalid-stride output was not removed");

    FailingOutput fail = {};
    fail.fail_call = 2;
    manyue_output::FileOutputOperations operations = {};
    operations.context = &fail;
    operations.write = failing_write;
    operations.flush = record_flush;
    operations.close = record_close;
    operations.remove = record_remove;
    manyue_output::CheckedFileOutput checked;
    checked.begin("injected-short-write.png", operations);
    const std::vector<unsigned char> pixels = make_pixels(2, 2, 3);
    const bool fail_encoded = manyue_output::write_fast_png(
            &checked, 2, 2, 3, pixels.data());
    require(!fail_encoded && !checked.finish(fail_encoded),
            "injected short write was accepted");
    require(fail.write_calls > fail.fail_call && fail.flush_calls == 1 &&
            fail.close_calls == 1 && fail.remove_calls == 1,
            "short-write path skipped flush, close, or partial-file removal");
}

int main(int argc, char** argv) {
    try {
        require(argc == 2, "output prefix required");
        const std::string prefix(argv[1]);

        const int rgb_width = 137;
        const int rgb_height = 91;
        const std::vector<unsigned char> rgb = make_pixels(rgb_width, rgb_height, 3);
        write_fixture(prefix + "-rgb.png", rgb_width, rgb_height, 3, rgb);

        const int rgba_width = 23;
        const int rgba_height = 17;
        const std::vector<unsigned char> rgba = make_pixels(rgba_width, rgba_height, 4);
        write_fixture(prefix + "-rgba-transparent-rgb.png",
                      rgba_width, rgba_height, 4, rgba);

        const int wide_width = 70001;
        const int wide_height = 1;
        const std::vector<unsigned char> wide = make_pixels(wide_width, wide_height, 3);
        write_fixture(prefix + "-wide-row.png", wide_width, wide_height, 3, wide);
        const ParsedPng wide_parsed = decode_and_check_png(prefix + "-wide-row.png");
        require(wide_parsed.idat_count > 1,
                "wide row did not exercise multiple bounded IDAT chunks");

        check_invalid_inputs_and_short_write(prefix);
        puts("fast PNG tests passed: exact RGB/RGBA, transparent RGB, >65535-byte row, "
             "bounded IDAT chunks, invalid dimensions, short-write cleanup");
        return 0;
    } catch (const std::exception& error) {
        fprintf(stderr, "%s\n", error.what());
        return 1;
    }
}
