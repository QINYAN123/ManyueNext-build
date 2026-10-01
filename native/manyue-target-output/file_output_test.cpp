#include "manyue_file_output.h"

#include <stdio.h>
#include <string.h>

struct FakeOutput {
    size_t accepted_bytes;
    bool short_write;
    bool flush_failure;
    bool close_failure;
    int flush_calls;
    int close_calls;
    int remove_calls;
};

struct InjectedFileContext {
    manyue_output::CFileOutputContext output;
    bool short_write;
};

static size_t fake_write(void* context, const void*, size_t length) {
    FakeOutput* output = static_cast<FakeOutput*>(context);
    if (output->short_write && length != 0) {
        const size_t accepted = length - 1;
        output->accepted_bytes += accepted;
        return accepted;
    }
    output->accepted_bytes += length;
    return length;
}

static int fake_flush(void* context) {
    FakeOutput* output = static_cast<FakeOutput*>(context);
    ++output->flush_calls;
    return output->flush_failure ? -1 : 0;
}

static int fake_close(void* context) {
    FakeOutput* output = static_cast<FakeOutput*>(context);
    ++output->close_calls;
    return output->close_failure ? -1 : 0;
}

static int fake_remove(void* context, const char* path) {
    FakeOutput* output = static_cast<FakeOutput*>(context);
    if (!path || strcmp(path, "mock-output.png") != 0)
        return -1;
    ++output->remove_calls;
    return 0;
}

static size_t injected_file_write(void* context, const void* bytes, size_t length) {
    InjectedFileContext* injected = static_cast<InjectedFileContext*>(context);
    const size_t written = injected->short_write && length > 0 ? length - 1 : length;
    return fwrite(bytes, 1, written, injected->output.file);
}

static int injected_file_flush(void* context) {
    InjectedFileContext* injected = static_cast<InjectedFileContext*>(context);
    return fflush(injected->output.file);
}

static int injected_file_close(void* context) {
    InjectedFileContext* injected = static_cast<InjectedFileContext*>(context);
    return manyue_output::cfile_close(&injected->output);
}

static int real_remove(void*, const char* path) {
    return path ? remove(path) : -1;
}

static bool run_case(bool short_write, bool flush_failure, bool close_failure,
                     bool encoder_success, bool expected_success) {
    FakeOutput fake = {};
    fake.short_write = short_write;
    fake.flush_failure = flush_failure;
    fake.close_failure = close_failure;

    manyue_output::FileOutputOperations operations;
    operations.context = &fake;
    operations.write = fake_write;
    operations.flush = fake_flush;
    operations.close = fake_close;
    operations.remove = fake_remove;

    manyue_output::CheckedFileOutput output;
    output.begin("mock-output.png", operations);
    const unsigned char bytes[] = {1, 2, 3, 4};
    manyue_output::CheckedFileOutput::stbi_write_callback(
            &output, const_cast<unsigned char*>(bytes), sizeof(bytes));
    const bool succeeded = output.finish(encoder_success);
    if (succeeded != expected_success || fake.flush_calls != 1 ||
        fake.close_calls != 1 || fake.remove_calls != (expected_success ? 0 : 1)) {
        fprintf(stderr, "file output status case failed\n");
        return false;
    }
    if (expected_success && fake.accepted_bytes != sizeof(bytes)) {
        fprintf(stderr, "successful output wrote the wrong number of bytes\n");
        return false;
    }
    return true;
}

static bool run_real_file_case(const char* path, bool short_write,
                               bool expected_success) {
    remove(path);
    InjectedFileContext context = {};
    context.output.file = fopen(path, "wb");
    context.short_write = short_write;
    if (!context.output.file) {
        fprintf(stderr, "could not create injected output file\n");
        return false;
    }

    manyue_output::FileOutputOperations operations;
    operations.context = &context;
    operations.write = injected_file_write;
    operations.flush = injected_file_flush;
    operations.close = injected_file_close;
    operations.remove = real_remove;

    manyue_output::CheckedFileOutput output;
    output.begin(path, operations);
    const unsigned char bytes[] = {1, 2, 3, 4};
    const bool write_ok = output.write_bytes(bytes, sizeof(bytes));
    const bool succeeded = output.finish(write_ok);
    if (succeeded != expected_success) {
        fprintf(stderr, "injected file write returned the wrong status\n");
        remove(path);
        return false;
    }

    FILE* file = fopen(path, "rb");
    if (!expected_success) {
        if (file) {
            fclose(file);
            remove(path);
            fprintf(stderr, "short write left a partial output file behind\n");
            return false;
        }
        return true;
    }

    if (!file) {
        fprintf(stderr, "successful write did not leave an output file\n");
        return false;
    }
    unsigned char actual[sizeof(bytes)] = {};
    const size_t length = fread(actual, 1, sizeof(actual), file);
    const int extra = fgetc(file);
    const int close_status = fclose(file);
    const bool valid = length == sizeof(bytes) && extra == EOF && close_status == 0 &&
                       memcmp(actual, bytes, sizeof(bytes)) == 0;
    remove(path);
    if (!valid)
        fprintf(stderr, "successful write produced unexpected file contents\n");
    return valid;
}

int main(int argc, char** argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: file-output-test <temporary-output-path>\n");
        return 2;
    }
    if (!run_case(false, false, false, true, true) ||
        !run_case(true, false, false, true, false) ||
        !run_case(false, true, false, true, false) ||
        !run_case(false, false, true, true, false) ||
        !run_case(false, false, false, false, false) ||
        !run_real_file_case(argv[1], false, true) ||
        !run_real_file_case(argv[1], true, false))
        return 1;

    puts("checked file output tests passed");
    return 0;
}
