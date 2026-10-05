#ifndef MANYUE_FAST_WEBP_H
#define MANYUE_FAST_WEBP_H

#include <limits.h>
#include <webp/encode.h>
#include "manyue_file_output.h"

namespace manyue_output {

inline int write_fast_webp_chunk(const unsigned char* bytes, size_t size, const WebPPicture* picture) {
    CheckedFileOutput* output = static_cast<CheckedFileOutput*>(picture->custom_ptr);
    return output && output->write_bytes(bytes, size) ? 1 : 0;
}

// Lossless quality=0 controls compression effort, not image quality. Avoid the
// convenience lossless encoder's much slower default settings on every page.
inline int save_fast_lossless_webp(const char* path, int width, int height, int channels,
                                  const unsigned char* pixels) {
    if (!path || !pixels || width <= 0 || height <= 0 || (channels != 3 && channels != 4) ||
        width > INT_MAX / channels || width > WEBP_MAX_DIMENSION || height > WEBP_MAX_DIMENSION) {
        if (path) remove(path);
        return 0;
    }
    WebPConfig config;
    WebPPicture picture;
    if (!WebPConfigInit(&config) || !WebPPictureInit(&picture)) return 0;
    config.lossless = 1;
    config.quality = 0;
    config.method = 0;
    config.exact = 1;
    config.thread_level = 0;
    if (!WebPValidateConfig(&config)) return 0;
    picture.width = width;
    picture.height = height;
    picture.use_argb = 1;
    const int imported = channels == 3
        ? WebPPictureImportRGB(&picture, pixels, width * channels)
        : WebPPictureImportRGBA(&picture, pixels, width * channels);
    if (!imported) {
        WebPPictureFree(&picture);
        remove(path);
        return 0;
    }
    CFileOutputContext context = {0};
    CheckedFileOutput output;
    if (!open_file_output(path, &context, &output)) {
        WebPPictureFree(&picture);
        return 0;
    }
    picture.writer = write_fast_webp_chunk;
    picture.custom_ptr = &output;
    const bool encoded = WebPEncode(&config, &picture) != 0;
    WebPPictureFree(&picture);
    return output.finish(encoded) ? 1 : 0;
}

} // namespace manyue_output
#endif
