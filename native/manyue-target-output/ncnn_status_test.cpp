#include "manyue_ncnn_status.h"

#include <stdio.h>
#include <string.h>

static int mocked_ncnn_inference(int status)
{
    return status;
}

int main(int argc, char** argv)
{
    const bool fail = argc == 2 && strcmp(argv[1], "--fail") == 0;
    MANYUE_NCNN_CHECK(mocked_ncnn_inference(fail ? -100 : 0), "mock inference");
    puts("ENCODE_REACHED");
    return 0;
}
