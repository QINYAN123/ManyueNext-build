#include <jni.h>
#include <limits>
#include <new>
#include <vector>

#include "borders.h"

namespace {

void throwIllegalArgument(JNIEnv* env, const char* message)
{
  jclass exceptionClass = env->FindClass("java/lang/IllegalArgumentException");
  if (exceptionClass != nullptr) {
    env->ThrowNew(exceptionClass, message);
  }
}

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_davemorrissey_labs_subscaleview_CropBorders_findCropBorders(
  JNIEnv* env, jobject /* thiz */,
  jbyteArray jpixels, jint width, jint height)
{
  if (jpixels == nullptr || width <= 0 || height <= 0) {
    throwIllegalArgument(env, "RGBA pixels and positive dimensions are required");
    return nullptr;
  }
  const uint64_t pixelCount = static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
  if (pixelCount > static_cast<uint64_t>(std::numeric_limits<jsize>::max()) / 4 ||
      static_cast<uint64_t>(env->GetArrayLength(jpixels)) != pixelCount * 4) {
    throwIllegalArgument(env, "RGBA array length must equal width * height * 4");
    return nullptr;
  }

  // Convert RGBA -> grayscale (single channel) using BT.601 luminance coefficients.
  // borders.cpp expects one byte per pixel.
  std::vector<uint8_t> gray;
  try {
    gray.resize(static_cast<size_t>(pixelCount));
  } catch (const std::bad_alloc&) {
    jclass errorClass = env->FindClass("java/lang/OutOfMemoryError");
    if (errorClass != nullptr) env->ThrowNew(errorClass, "Unable to allocate crop grayscale buffer");
    return nullptr;
  }
  jbyte* rgba = env->GetByteArrayElements(jpixels, nullptr);
  if (rgba == nullptr) return nullptr;
  for (size_t i = 0; i < pixelCount; i++) {
    uint8_t r = (uint8_t)rgba[i * 4 + 0];
    uint8_t g = (uint8_t)rgba[i * 4 + 1];
    uint8_t b = (uint8_t)rgba[i * 4 + 2];
    gray[i] = (uint8_t)((r * 77 + g * 150 + b * 29) >> 8);
  }

  env->ReleaseByteArrayElements(jpixels, rgba, JNI_ABORT);

  Rect rect = findBorders(gray.data(), (uint32_t)width, (uint32_t)height);

  jintArray result = env->NewIntArray(4);
  if (result == nullptr) return nullptr;
  jint buf[4] = {
    (jint)rect.x,
    (jint)rect.y,
    (jint)rect.width,
    (jint)rect.height,
  };
  env->SetIntArrayRegion(result, 0, 4, buf);
  return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_davemorrissey_labs_subscaleview_CropBorders_findCropBordersGray(
  JNIEnv* env, jobject /* thiz */,
  jbyteArray jpixels, jint width, jint height)
{
  if (jpixels == nullptr) {
    throwIllegalArgument(env, "pixels must not be null");
    return nullptr;
  }
  if (width <= 0 || height <= 0) {
    throwIllegalArgument(env, "width and height must be positive");
    return nullptr;
  }

  const uint64_t pixelCount = static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
  if (pixelCount > static_cast<uint64_t>(std::numeric_limits<jsize>::max())) {
    throwIllegalArgument(env, "image dimensions exceed the maximum byte array length");
    return nullptr;
  }

  const jsize length = env->GetArrayLength(jpixels);
  if (static_cast<uint64_t>(length) != pixelCount) {
    throwIllegalArgument(env, "grayscale pixel array length must equal width * height");
    return nullptr;
  }

  jbyte* pixels = env->GetByteArrayElements(jpixels, nullptr);
  if (pixels == nullptr) {
    return nullptr;
  }

  Rect rect = findBorders(
    reinterpret_cast<uint8_t*>(pixels),
    static_cast<uint32_t>(width),
    static_cast<uint32_t>(height));

  env->ReleaseByteArrayElements(jpixels, pixels, JNI_ABORT);

  jintArray result = env->NewIntArray(4);
  if (result == nullptr) {
    return nullptr;
  }
  jint buf[4] = {
    static_cast<jint>(rect.x),
    static_cast<jint>(rect.y),
    static_cast<jint>(rect.width),
    static_cast<jint>(rect.height),
  };
  env->SetIntArrayRegion(result, 0, 4, buf);
  return result;
}
