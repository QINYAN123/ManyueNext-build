#ifndef MANYUE_NCNN_STATUS_H
#define MANYUE_NCNN_STATUS_H

#include <stdio.h>
#include <stdlib.h>

static inline int manyue_ncnn_status_succeeded(int status)
{
    return status == 0;
}

// ncnn reports load, extractor, and Vulkan command failures through integer
// status values. Terminating the worker is deliberate: several upstream
// Real-CUGAN helper callers ignore intermediate return values, so returning
// -1 here could still let a partially populated image reach the encoder.
#define MANYUE_NCNN_CHECK(operation, label) \
    do { \
        const int manyue_ncnn_status = (operation); \
        if (!manyue_ncnn_status_succeeded(manyue_ncnn_status)) { \
            fprintf(stderr, "ncnn %s failed: status=%d at %s:%d\n", \
                    (label), manyue_ncnn_status, __FILE__, __LINE__); \
            exit(EXIT_FAILURE); \
        } \
    } while (0)

#endif  // MANYUE_NCNN_STATUS_H
