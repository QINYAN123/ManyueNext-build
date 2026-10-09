#ifndef MANYUE_FAST_PNG_H
#define MANYUE_FAST_PNG_H

#include "manyue_file_output.h"

#include <limits.h>
#include <stddef.h>
#include <stdint.h>
#include <zlib.h>

namespace manyue_output {
namespace fast_png_detail {

static const size_t kIdatBufferSize = 64u * 1024u;

inline void store_be32(unsigned char* destination, uint32_t value) {
    destination[0] = static_cast<unsigned char>((value >> 24) & 0xffu);
    destination[1] = static_cast<unsigned char>((value >> 16) & 0xffu);
    destination[2] = static_cast<unsigned char>((value >> 8) & 0xffu);
    destination[3] = static_cast<unsigned char>(value & 0xffu);
}

inline uint32_t load_be32(const unsigned char* source) {
    return (static_cast<uint32_t>(source[0]) << 24) |
           (static_cast<uint32_t>(source[1]) << 16) |
           (static_cast<uint32_t>(source[2]) << 8) |
           static_cast<uint32_t>(source[3]);
}

inline bool write_chunk(CheckedFileOutput* output, const char type[4],
                        const unsigned char* data, size_t length) {
    if (!output || length > static_cast<size_t>(UINT32_MAX) ||
        (length != 0 && !data))
        return false;

    unsigned char chunk_header[8];
    store_be32(chunk_header, static_cast<uint32_t>(length));
    for (size_t i = 0; i < 4; ++i)
        chunk_header[4 + i] = static_cast<unsigned char>(type[i]);
    if (!output->write_bytes(chunk_header, sizeof(chunk_header)) ||
        (length != 0 && !output->write_bytes(data, length)))
        return false;

    uLong crc = crc32(0L, Z_NULL, 0);
    crc = crc32(crc, reinterpret_cast<const Bytef*>(type), 4);
    if (length != 0)
        crc = crc32(crc, reinterpret_cast<const Bytef*>(data),
                    static_cast<uInt>(length));
    unsigned char encoded_crc[4];
    store_be32(encoded_crc, static_cast<uint32_t>(crc));
    return output->write_bytes(encoded_crc, sizeof(encoded_crc));
}

class IdatStream {
public:
    IdatStream(CheckedFileOutput* output, z_stream* stream)
        : output_(output), stream_(stream) {}

    bool feed(const unsigned char* bytes, size_t length) {
        if (length != 0 && !bytes)
            return false;

        while (length != 0) {
            const size_t input_length = length > static_cast<size_t>(UINT_MAX)
                    ? static_cast<size_t>(UINT_MAX) : length;
            stream_->next_in = const_cast<Bytef*>(
                    reinterpret_cast<const Bytef*>(bytes));
            stream_->avail_in = static_cast<uInt>(input_length);

            while (stream_->avail_in != 0) {
                stream_->next_out = output_buffer_;
                stream_->avail_out = static_cast<uInt>(sizeof(output_buffer_));
                const int status = deflate(stream_, Z_NO_FLUSH);
                const size_t produced = sizeof(output_buffer_) - stream_->avail_out;
                if (produced != 0 && !write_idat(produced))
                    return false;
                if (status != Z_OK || (produced == 0 && stream_->avail_in != 0))
                    return false;
            }

            bytes += input_length;
            length -= input_length;
        }
        return true;
    }

    bool finish() {
        for (;;) {
            stream_->next_in = Z_NULL;
            stream_->avail_in = 0;
            stream_->next_out = output_buffer_;
            stream_->avail_out = static_cast<uInt>(sizeof(output_buffer_));
            const int status = deflate(stream_, Z_FINISH);
            const size_t produced = sizeof(output_buffer_) - stream_->avail_out;
            if (produced != 0 && !write_idat(produced))
                return false;
            if (status == Z_STREAM_END)
                return true;
            if (status != Z_OK || produced == 0)
                return false;
        }
    }

private:
    bool write_idat(size_t length) {
        return write_chunk(output_, "IDAT", output_buffer_, length);
    }

    CheckedFileOutput* output_;
    z_stream* stream_;
    unsigned char output_buffer_[kIdatBufferSize];
};

}  // namespace fast_png_detail

// Writes an 8-bit RGB/RGBA PNG as a single zlib stream split into bounded
// IDAT chunks. Filter 0 preserves every input channel byte, including RGB
// stored beneath fully transparent alpha. Level 0 asks zlib for stored DEFLATE
// blocks, so there is no Huffman-compression pass or full-frame scratch copy.
// A zero row stride means tightly packed rows. The caller must call finish()
// on CheckedFileOutput with this function's result to retain checked flush,
// close, and partial-file removal behavior.
inline bool write_fast_png(CheckedFileOutput* output, int width, int height,
                           int channels, const unsigned char* pixels,
                           size_t row_stride_bytes = 0) {
    if (!output || width <= 0 || height <= 0 || !pixels ||
        (channels != 3 && channels != 4))
        return false;

    const size_t width_size = static_cast<size_t>(width);
    const size_t channel_count = static_cast<size_t>(channels);
    if (width_size > static_cast<size_t>(-1) / channel_count)
        return false;
    const size_t row_bytes = width_size * channel_count;
    if (row_stride_bytes == 0)
        row_stride_bytes = row_bytes;
    if (row_stride_bytes < row_bytes ||
        static_cast<size_t>(height - 1) >
                (static_cast<size_t>(-1) - row_bytes) / row_stride_bytes)
        return false;

    z_stream stream = {};
    if (deflateInit(&stream, 0) != Z_OK)
        return false;

    bool success = false;
    do {
        static const unsigned char signature[8] = {
            137, 80, 78, 71, 13, 10, 26, 10
        };
        if (!output->write_bytes(signature, sizeof(signature)))
            break;

        unsigned char ihdr[13] = {};
        fast_png_detail::store_be32(ihdr, static_cast<uint32_t>(width));
        fast_png_detail::store_be32(ihdr + 4, static_cast<uint32_t>(height));
        ihdr[8] = 8;  // bit depth
        ihdr[9] = channels == 3 ? 2 : 6;  // truecolor or truecolor with alpha
        if (!fast_png_detail::write_chunk(output, "IHDR", ihdr, sizeof(ihdr)))
            break;

        fast_png_detail::IdatStream idat(output, &stream);
        const unsigned char filter_none = 0;
        bool rows_ok = true;
        for (int y = 0; y < height; ++y) {
            if (!idat.feed(&filter_none, 1) ||
                !idat.feed(pixels + static_cast<size_t>(y) * row_stride_bytes,
                           row_bytes)) {
                rows_ok = false;
                break;
            }
        }
        if (!rows_ok || !idat.finish())
            break;
        if (!fast_png_detail::write_chunk(output, "IEND", 0, 0))
            break;
        success = true;
    } while (false);

    const int end_status = deflateEnd(&stream);
    return success && end_status == Z_OK;
}

}  // namespace manyue_output

#endif  // MANYUE_FAST_PNG_H
