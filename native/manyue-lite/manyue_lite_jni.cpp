#include "manyue_lite_engine.h"

#include <jni.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

namespace {

std::mutex g_registry_mutex;
std::unordered_map<std::uint64_t, std::shared_ptr<manyue_lite::Engine>> g_engines;
std::atomic<std::uint64_t> g_next_handle{1};

class UtfChars {
public:
    UtfChars(JNIEnv* env, jstring value) : env_(env), value_(value) {
        if (value_) chars_ = env_->GetStringUTFChars(value_, nullptr);
    }
    ~UtfChars() {
        if (chars_) env_->ReleaseStringUTFChars(value_, chars_);
    }
    UtfChars(const UtfChars&) = delete;
    UtfChars& operator=(const UtfChars&) = delete;
    bool valid() const { return chars_ != nullptr; }
    std::string str() const { return chars_ ? std::string(chars_) : std::string(); }

private:
    JNIEnv* env_;
    jstring value_;
    const char* chars_ = nullptr;
};

void throw_runtime_exception(JNIEnv* env, const std::string& message) {
    jclass exception = env->FindClass("java/lang/RuntimeException");
    if (exception) env->ThrowNew(exception, message.c_str());
}

std::shared_ptr<manyue_lite::Engine> find_engine(std::uint64_t handle) {
    std::lock_guard<std::mutex> guard(g_registry_mutex);
    const auto found = g_engines.find(handle);
    return found == g_engines.end() ? nullptr : found->second;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_eu_kanade_tachiyomi_ui_reader_manyue_ManyueLiteNative_nativeCreate(
    JNIEnv* env, jobject /* receiver */, jstring model_directory, jboolean prefer_gpu) {
    try {
        UtfChars directory(env, model_directory);
        if (!directory.valid()) throw std::runtime_error("模型目录为空");
        auto engine = std::make_shared<manyue_lite::Engine>(128);
        std::string error;
        if (!engine->initialize(directory.str(), prefer_gpu == JNI_TRUE, error)) {
            throw std::runtime_error(error.empty() ? "轻量模型初始化失败" : error);
        }
        const std::uint64_t handle = g_next_handle.fetch_add(1, std::memory_order_relaxed);
        if (handle == 0 || handle > static_cast<std::uint64_t>(INT64_MAX)) {
            throw std::runtime_error("native句柄空间耗尽");
        }
        {
            std::lock_guard<std::mutex> guard(g_registry_mutex);
            g_engines.emplace(handle, std::move(engine));
        }
        return static_cast<jlong>(handle);
    } catch (const std::exception& failure) {
        throw_runtime_exception(env, failure.what());
    } catch (...) {
        throw_runtime_exception(env, "轻量模型初始化失败");
    }
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_eu_kanade_tachiyomi_ui_reader_manyue_ManyueLiteNative_nativeUpscale(
    JNIEnv* env, jobject /* receiver */, jlong handle, jlong job_id,
    jstring input_path, jstring output_path, jint target_width, jint strength_percent) {
    try {
        UtfChars input(env, input_path);
        UtfChars output(env, output_path);
        if (!input.valid() || !output.valid()) throw std::runtime_error("输入或输出路径为空");
        const auto engine = find_engine(static_cast<std::uint64_t>(handle));
        if (!engine) throw std::runtime_error("轻量native句柄已失效");
        const std::string telemetry = engine->upscale(static_cast<std::uint64_t>(job_id), input.str(), output.str(),
                                                       target_width, strength_percent);
        return env->NewStringUTF(telemetry.c_str());
    } catch (const std::exception& failure) {
        throw_runtime_exception(env, failure.what());
    } catch (...) {
        throw_runtime_exception(env, "轻量图片处理失败");
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_eu_kanade_tachiyomi_ui_reader_manyue_ManyueLiteNative_nativeCancel(
    JNIEnv* /* env */, jobject /* receiver */, jlong handle, jlong job_id) {
    const auto engine = find_engine(static_cast<std::uint64_t>(handle));
    if (engine) engine->cancel(static_cast<std::uint64_t>(job_id));
}

extern "C" JNIEXPORT void JNICALL
Java_eu_kanade_tachiyomi_ui_reader_manyue_ManyueLiteNative_nativeDestroy(
    JNIEnv* /* env */, jobject /* receiver */, jlong handle) {
    std::shared_ptr<manyue_lite::Engine> removed;
    {
        std::lock_guard<std::mutex> guard(g_registry_mutex);
        const auto found = g_engines.find(static_cast<std::uint64_t>(handle));
        if (found == g_engines.end()) return;
        removed = std::move(found->second);
        g_engines.erase(found);
    }
    // The registry never waits for inference. Any in-flight JNI call retains its own shared_ptr.
    removed.reset();
}
