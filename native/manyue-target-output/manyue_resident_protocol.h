#pragma once

// Experimental stdin/stdout framing for the isolated resident legacy runner.
// Legacy stdout text is redirected to stderr; protocol uses a saved descriptor.

#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <string>
#include <queue>
#include <mutex>
#include <condition_variable>

#if !defined(_WIN32)
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <unistd.h>
#endif

namespace manyue_resident {

static const uint32_t kMaxPathBytes = 4096;
static const uint32_t kMaxPayloadBytes = 20 + 2 * kMaxPathBytes;
static const uint32_t kResponsePayloadBytes = 92;

enum ResponseKind : uint32_t {
    kReady = 0,
    kResult = 1,
    kFatal = 2,
};

enum Status : int32_t {
    kOk = 0,
    kProtocolError = -1,
    kBadRequest = -2,
    kInputError = -10,
    kTargetSizeError = -11,
    kOutputPrepareError = -12,
    kInferenceError = -20,
    kResizeError = -21,
    kEncodeError = -30,
    kOutputInvalid = -31,
    kModelInitError = -40,
};

struct Request {
    uint64_t id;
    uint32_t target_width;
    std::string input_path;
    std::string output_path;
    uint64_t start_us;
};

struct Response {
    uint32_t kind;
    int32_t status;
    uint64_t id;
    uint64_t init_us;
    uint64_t decode_us;
    uint64_t infer_us;
    uint64_t resize_us;
    uint64_t encode_us;
    uint64_t total_us;
    uint64_t output_bytes;
    uint32_t output_width;
    uint32_t output_height;
    uint32_t gpu_backend;
    uint32_t tile_size;
    uint32_t model_loads;
};

class ResponseQueue {
public:
    void put(const Response& response) {
        std::lock_guard<std::mutex> guard(mutex_);
        items_.push(response);
        condition_.notify_one();
    }

    Response get() {
        std::unique_lock<std::mutex> lock(mutex_);
        condition_.wait(lock, [this] { return !items_.empty(); });
        Response result = items_.front();
        items_.pop();
        return result;
    }

private:
    std::mutex mutex_;
    std::condition_variable condition_;
    std::queue<Response> items_;
};

inline uint32_t read_u32_le(const unsigned char* bytes) {
    return (uint32_t)bytes[0] | ((uint32_t)bytes[1] << 8) |
           ((uint32_t)bytes[2] << 16) | ((uint32_t)bytes[3] << 24);
}

inline uint64_t read_u64_le(const unsigned char* bytes) {
    uint64_t value = 0;
    for (int i = 7; i >= 0; --i)
        value = (value << 8) | bytes[i];
    return value;
}

inline void write_u32_le(unsigned char* bytes, uint32_t value) {
    for (int i = 0; i < 4; ++i) {
        bytes[i] = (unsigned char)(value & 0xffu);
        value >>= 8;
    }
}

inline void write_u64_le(unsigned char* bytes, uint64_t value) {
    for (int i = 0; i < 8; ++i) {
        bytes[i] = (unsigned char)(value & 0xffu);
        value >>= 8;
    }
}

#if !defined(_WIN32)

inline bool read_exact(int fd, unsigned char* bytes, size_t length,
                       size_t* received, bool allow_clean_eof) {
    *received = 0;
    while (*received < length) {
        const ssize_t count = read(fd, bytes + *received, length - *received);
        if (count == 0) {
            return allow_clean_eof && *received == 0;
        }
        if (count < 0) {
            if (errno == EINTR)
                continue;
            return false;
        }
        *received += (size_t)count;
    }
    return true;
}

inline bool write_exact(int fd, const unsigned char* bytes, size_t length) {
    size_t written = 0;
    while (written < length) {
        const ssize_t count = write(fd, bytes + written, length - written);
        if (count < 0) {
            if (errno == EINTR)
                continue;
            return false;
        }
        if (count == 0)
            return false;
        written += (size_t)count;
    }
    return true;
}

// Returns 0 for clean EOF at a frame boundary, 1 for a parsed frame, 2 for a
// fully consumed but invalid request, and -1 for framing/transport failure.
inline int read_request(int fd, Request* request, Response* error) {
    memset(error, 0, sizeof(*error));
    unsigned char header[8];
    size_t received = 0;
    if (!read_exact(fd, header, sizeof(header), &received, true))
        return -1;
    if (received == 0)
        return 0;
    if (memcmp(header, "MNYQ", 4) != 0)
        return -1;

    const uint32_t payload_size = read_u32_le(header + 4);
    if (payload_size > kMaxPayloadBytes)
        return -1;
    std::string payload(payload_size, '\0');
    if (payload_size != 0 &&
        !read_exact(fd, reinterpret_cast<unsigned char*>(&payload[0]),
                    payload_size, &received, false))
        return -1;
    if (payload_size < 20) {
        error->kind = kResult;
        error->status = kBadRequest;
        error->id = payload_size >= 8
            ? read_u64_le(reinterpret_cast<const unsigned char*>(payload.data())) : 0;
        return 2;
    }

    const unsigned char* bytes =
        reinterpret_cast<const unsigned char*>(payload.data());
    const uint64_t id = read_u64_le(bytes);
    const uint32_t target_width = read_u32_le(bytes + 8);
    const uint32_t input_size = read_u32_le(bytes + 12);
    const uint32_t output_size = read_u32_le(bytes + 16);
    const uint64_t expected = 20ull + input_size + output_size;
    bool valid = id > 0 && id <= 0x7fffffffffffffffull &&
                 target_width > 0 && target_width <= 0x7fffffffu &&
                 input_size > 0 && input_size <= kMaxPathBytes &&
                 output_size > 0 && output_size <= kMaxPathBytes &&
                 expected == payload_size;
    if (valid) {
        const char* input = payload.data() + 20;
        const char* output = input + input_size;
        valid = memchr(input, '\0', input_size) == NULL &&
                memchr(output, '\0', output_size) == NULL;
        if (valid) {
            request->input_path.assign(input, input_size);
            request->output_path.assign(output, output_size);
        }
    }
    if (!valid) {
        error->kind = kResult;
        error->status = kBadRequest;
        error->id = id;
        return 2;
    }
    request->id = id;
    request->target_width = target_width;
    request->start_us = 0;
    return 1;
}

inline bool write_response(int fd, const Response& response) {
    unsigned char frame[8 + kResponsePayloadBytes];
    memcpy(frame, "MNYR", 4);
    write_u32_le(frame + 4, kResponsePayloadBytes);
    unsigned char* payload = frame + 8;
    write_u32_le(payload, response.kind);
    write_u32_le(payload + 4, (uint32_t)response.status);
    write_u64_le(payload + 8, response.id);
    write_u64_le(payload + 16, response.init_us);
    write_u64_le(payload + 24, response.decode_us);
    write_u64_le(payload + 32, response.infer_us);
    write_u64_le(payload + 40, response.resize_us);
    write_u64_le(payload + 48, response.encode_us);
    write_u64_le(payload + 56, response.total_us);
    write_u64_le(payload + 64, response.output_bytes);
    write_u32_le(payload + 72, response.output_width);
    write_u32_le(payload + 76, response.output_height);
    write_u32_le(payload + 80, response.gpu_backend);
    write_u32_le(payload + 84, response.tile_size);
    write_u32_le(payload + 88, response.model_loads);
    return write_exact(fd, frame, sizeof(frame));
}

inline int redirect_stdout_to_stderr() {
    fflush(stdout);
    const int protocol_fd = dup(STDOUT_FILENO);
    if (protocol_fd < 0)
        return -1;
    if (dup2(STDERR_FILENO, STDOUT_FILENO) < 0) {
        close(protocol_fd);
        return -1;
    }
    signal(SIGPIPE, SIG_IGN);
    return protocol_fd;
}

#else

inline int read_request(int, Request*, Response*) { return -1; }
inline bool write_response(int, const Response&) { return false; }
inline int redirect_stdout_to_stderr() { return -1; }

#endif

} // namespace manyue_resident
