#ifndef MANYUE_FILE_OUTPUT_H
#define MANYUE_FILE_OUTPUT_H

#include <stddef.h>
#include <stdio.h>

namespace manyue_output {

struct FileOutputOperations {
    void* context;
    size_t (*write)(void* context, const void* bytes, size_t length);
    int (*flush)(void* context);
    int (*close)(void* context);
    int (*remove)(void* context, const char* path);
};

class CheckedFileOutput {
public:
    CheckedFileOutput()
        : path_(0), operations_(), write_failed_(false), active_(false) {}

    void begin(const char* path, const FileOutputOperations& operations) {
        path_ = path;
        operations_ = operations;
        write_failed_ = false;
        active_ = true;
    }

    bool write_bytes(const void* bytes, size_t length) {
        if (!active_ || !operations_.write || (length != 0 && !bytes)) {
            write_failed_ = true;
            return false;
        }
        if (write_failed_)
            return false;
        if (operations_.write(operations_.context, bytes, length) != length) {
            write_failed_ = true;
            return false;
        }
        return true;
    }

    void mark_write_failed() {
        write_failed_ = true;
    }

    bool finish(bool encoder_succeeded) {
        if (!active_)
            return false;

        const int flush_status = operations_.flush
                ? operations_.flush(operations_.context) : -1;
        const int close_status = operations_.close
                ? operations_.close(operations_.context) : -1;
        active_ = false;

        const bool succeeded = encoder_succeeded && !write_failed_ &&
                flush_status == 0 && close_status == 0;
        if (!succeeded && operations_.remove)
            operations_.remove(operations_.context, path_);
        return succeeded;
    }

    static void stbi_write_callback(void* context, void* bytes, int length) {
        CheckedFileOutput* output = static_cast<CheckedFileOutput*>(context);
        if (length < 0) {
            output->mark_write_failed();
            return;
        }
        output->write_bytes(bytes, static_cast<size_t>(length));
    }

private:
    const char* path_;
    FileOutputOperations operations_;
    bool write_failed_;
    bool active_;
};

struct CFileOutputContext {
    FILE* file;
};

inline size_t cfile_write(void* context, const void* bytes, size_t length) {
    CFileOutputContext* file = static_cast<CFileOutputContext*>(context);
    return fwrite(bytes, 1, length, file->file);
}

inline int cfile_flush(void* context) {
    CFileOutputContext* file = static_cast<CFileOutputContext*>(context);
    return fflush(file->file);
}

inline int cfile_close(void* context) {
    CFileOutputContext* file = static_cast<CFileOutputContext*>(context);
    FILE* handle = file->file;
    file->file = 0;
    return fclose(handle);
}

inline int cfile_remove(void*, const char* path) {
    return path ? remove(path) : -1;
}

inline bool open_file_output(const char* path, CFileOutputContext* context,
                             CheckedFileOutput* output) {
    if (!path || !context || !output)
        return false;
    context->file = fopen(path, "wb");
    if (!context->file) {
        remove(path);
        return false;
    }

    FileOutputOperations operations;
    operations.context = context;
    operations.write = cfile_write;
    operations.flush = cfile_flush;
    operations.close = cfile_close;
    operations.remove = cfile_remove;
    output->begin(path, operations);
    return true;
}

}  // namespace manyue_output

#endif  // MANYUE_FILE_OUTPUT_H
