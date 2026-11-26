#include "android_container.h"

#include <android/log.h>

#include <cmath>
#include <cstdint>
#include <exception>
#include <string>

namespace {

constexpr const char* kLogTag = "LiteHtml";
constexpr int kMinBitmapPx = 1;
// 位图尺寸上限：恶意/异常高的布局可能让 document->height() 报出超大值，无封顶会直接
// 申请超大位图压垮内存。单边封顶 + 总字节（ARGB_8888 每像素 4 字节）双重封顶。
constexpr int kMaxBitmapSidePx = 16384;
constexpr int64_t kMaxBitmapBytes = 64LL * 1024 * 1024;

void throwRuntime(JNIEnv* env, const std::string& message) {
    if (env->ExceptionCheck()) {
        return;
    }
    jclass runtime = env->FindClass("java/lang/RuntimeException");
    if (runtime != nullptr) {
        env->ThrowNew(runtime, message.c_str());
    }
}

jobject createBitmap(JNIEnv* env, int widthPx, int heightPx) {
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (bitmapClass == nullptr || configClass == nullptr) {
        return nullptr;
    }
    jfieldID argbField = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject argb = env->GetStaticObjectField(configClass, argbField);
    jmethodID create = env->GetStaticMethodID(
        bitmapClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    return env->CallStaticObjectMethod(bitmapClass, create, widthPx, heightPx, argb);
}

jobject createCanvas(JNIEnv* env, jobject bitmap, float density) {
    jclass canvasClass = env->FindClass("android/graphics/Canvas");
    if (canvasClass == nullptr) {
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(canvasClass, "<init>", "(Landroid/graphics/Bitmap;)V");
    jobject canvas = env->NewObject(canvasClass, ctor, bitmap);
    if (canvas == nullptr) {
        return nullptr;
    }
    jmethodID scale = env->GetMethodID(canvasClass, "scale", "(FF)V");
    env->CallVoidMethod(canvas, scale, density, density);
    return canvas;
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_com_mistbell_tavern_android_render_litehtml_LiteHtmlRenderer_nativeRender(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring html,
    jint widthCssPx,
    jfloat density) {
    if (html == nullptr || widthCssPx <= 0 || density <= 0.f) {
        throwRuntime(env, "litehtml render: html、宽度和 density 必须有效");
        return nullptr;
    }
    const char* utf = env->GetStringUTFChars(html, nullptr);
    if (utf == nullptr) {
        return nullptr;
    }
    const std::string source(utf);
    env->ReleaseStringUTFChars(html, utf);

    try {
        AndroidContainer container(env, widthCssPx, density);
        litehtml::document::ptr document = litehtml::document::createFromString(source.c_str(), &container);
        if (!document) {
            throwRuntime(env, "litehtml 未能解析文档");
            return nullptr;
        }
        document->render(static_cast<litehtml::pixel_t>(widthCssPx));
        const int heightCss = std::max(kMinBitmapPx, static_cast<int>(std::ceil(document->height())));
        const int widthPx = std::max(kMinBitmapPx, static_cast<int>(std::ceil(widthCssPx * density)));
        const int heightPx = std::max(kMinBitmapPx, static_cast<int>(std::ceil(heightCss * density)));
        if (widthPx > kMaxBitmapSidePx || heightPx > kMaxBitmapSidePx ||
            static_cast<int64_t>(widthPx) * heightPx * 4 > kMaxBitmapBytes) {
            throwRuntime(env, "litehtml 位图尺寸超限：单边不超过 16384px 且位图不超过 64MB，已拒绝渲染");
            return nullptr;
        }
        jobject bitmap = createBitmap(env, widthPx, heightPx);
        if (bitmap == nullptr || env->ExceptionCheck()) {
            return nullptr;
        }
        jobject canvas = createCanvas(env, bitmap, density);
        if (canvas == nullptr || env->ExceptionCheck()) {
            return nullptr;
        }
        container.setCanvas(canvas);
        document->draw(reinterpret_cast<litehtml::uint_ptr>(canvas), 0, 0, nullptr);
        return bitmap;
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "render failed: %s", error.what());
        throwRuntime(env, error.what());
        return nullptr;
    }
}
