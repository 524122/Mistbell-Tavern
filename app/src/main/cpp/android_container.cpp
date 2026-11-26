#include "android_container.h"

#include <algorithm>
#include <cmath>

namespace {

constexpr int kMetricsAscent = 0;
constexpr int kMetricsDescent = 1;
constexpr int kMetricsXHeight = 2;
constexpr int kMetricsZeroWidth = 3;
constexpr int kMetricsCount = 4;
constexpr float kPtToPx = 96.f / 72.f;
constexpr float kDefaultFontPx = 16.f;
constexpr int kDefaultWeight = 400;
constexpr int kViewportHeight = 100000;
constexpr int kColorBits = 8;

jint colorOf(const litehtml::web_color& color) {
    return (static_cast<jint>(color.alpha) << 24) |
        (static_cast<jint>(color.red) << 16) |
        (static_cast<jint>(color.green) << 8) |
        static_cast<jint>(color.blue);
}

}  // namespace

AndroidContainer::AndroidContainer(JNIEnv* env, int widthCssPx, float density)
    : env_(env), widthCssPx_(widthCssPx), density_(density) {}

void AndroidContainer::setCanvas(jobject canvas) { canvas_ = canvas; }

bool AndroidContainer::failed() const { return env_->ExceptionCheck(); }

bool AndroidContainer::ensureApi() {
    const auto ready = [this]() {
        return api_.fontMetrics != nullptr && api_.textWidth != nullptr && api_.drawText != nullptr &&
            api_.fill != nullptr && api_.fillLinear != nullptr && api_.fillRadial != nullptr &&
            api_.fillSweep != nullptr && api_.stroke != nullptr && api_.fillOval != nullptr &&
            api_.clip != nullptr && api_.restore != nullptr && api_.transformText != nullptr;
    };
    if (api_.cls != nullptr) {
        return ready();
    }
    jclass local = env_->FindClass("com/mistbell/tavern/android/render/litehtml/LiteHtmlPaint");
    if (local == nullptr || env_->ExceptionCheck()) {
        env_->ExceptionClear();
        return false;
    }
    api_.cls = reinterpret_cast<jclass>(env_->NewGlobalRef(local));
    env_->DeleteLocalRef(local);
    const auto lookup = [this](const char* name, const char* signature) -> jmethodID {
        if (env_->ExceptionCheck()) {
            env_->ExceptionClear();
            return nullptr;
        }
        jmethodID method = env_->GetStaticMethodID(api_.cls, name, signature);
        if (method == nullptr || env_->ExceptionCheck()) {
            env_->ExceptionDescribe();
            env_->ExceptionClear();
            return nullptr;
        }
        return method;
    };
    api_.fontMetrics = lookup("fontMetrics", "(FIZ)[F");
    api_.textWidth = lookup("textWidth", "(Ljava/lang/String;FIZ)F");
    api_.drawText = lookup("drawText", "(Landroid/graphics/Canvas;Ljava/lang/String;FFFIZI)V");
    api_.fill = lookup("fill", "(Landroid/graphics/Canvas;FFFFFI)V");
    api_.fillLinear = lookup("fillLinear", "(Landroid/graphics/Canvas;FFFFFFFFF[I[F)V");
    api_.fillRadial = lookup("fillRadial", "(Landroid/graphics/Canvas;FFFFFFFF[I[F)V");
    // left, top, right, bottom, radius, cx, cy
    api_.fillSweep = lookup("fillSweep", "(Landroid/graphics/Canvas;FFFFFFF[I[F)V");
    api_.stroke = lookup("stroke", "(Landroid/graphics/Canvas;FFFFFFI)V");
    api_.fillOval = lookup("fillOval", "(Landroid/graphics/Canvas;FFFFI)V");
    api_.clip = lookup("clip", "(Landroid/graphics/Canvas;FFFF)I");
    api_.restore = lookup("restore", "(Landroid/graphics/Canvas;I)V");
    api_.transformText = lookup("transformText", "(Ljava/lang/String;I)Ljava/lang/String;");
    return ready();
}

jobject AndroidContainer::canvasOf(litehtml::uint_ptr hdc) const {
    if (hdc != 0) {
        return reinterpret_cast<jobject>(hdc);
    }
    return canvas_;
}

jint AndroidContainer::argb(const litehtml::web_color& color) { return colorOf(color); }

float AndroidContainer::boxRadius(const litehtml::border_radiuses& radius) {
    return std::max(
        {radius.top_left_x, radius.top_right_x, radius.bottom_right_x, radius.bottom_left_x});
}

litehtml::uint_ptr AndroidContainer::create_font(
    const litehtml::font_description& descr,
    const litehtml::document*,
    litehtml::font_metrics* fm) {
    auto* font = new Font();
    font->size = descr.size > 0 ? descr.size : kDefaultFontPx;
    font->weight = descr.weight > 0 ? descr.weight : kDefaultWeight;
    font->italic = descr.style == litehtml::font_style_italic;
    if (ensureApi()) {
        jfloatArray metrics = reinterpret_cast<jfloatArray>(env_->CallStaticObjectMethod(
            api_.cls, api_.fontMetrics, font->size, font->weight, static_cast<jboolean>(font->italic)));
        if (!failed() && metrics != nullptr) {
            float values[kMetricsCount] = {};
            env_->GetFloatArrayRegion(metrics, 0, kMetricsCount, values);
            env_->DeleteLocalRef(metrics);
            font->ascent = values[kMetricsAscent];
            font->descent = values[kMetricsDescent];
        }
    }
    if (fm != nullptr) {
        fm->font_size = font->size;
        fm->ascent = font->ascent;
        fm->descent = font->descent;
        fm->height = font->ascent + font->descent;
        fm->x_height = font->size * 0.5f;
        fm->ch_width = font->size * 0.5f;
        fm->draw_spaces = true;
        fm->sub_shift = font->size / 5.f;
        fm->super_shift = font->size / 3.f;
    }
    return reinterpret_cast<litehtml::uint_ptr>(font);
}

void AndroidContainer::delete_font(litehtml::uint_ptr hFont) {
    delete reinterpret_cast<Font*>(hFont);
}

litehtml::pixel_t AndroidContainer::text_width(const char* text, litehtml::uint_ptr hFont) {
    if (text == nullptr || text[0] == '\0' || !ensureApi()) {
        return 0;
    }
    const Font* font = reinterpret_cast<const Font*>(hFont);
    if (font == nullptr) {
        return 0;
    }
    jstring jtext = env_->NewStringUTF(text);
    if (jtext == nullptr) {
        env_->ExceptionClear();
        return 0;
    }
    const jfloat width = env_->CallStaticFloatMethod(
        api_.cls, api_.textWidth, jtext, font->size, font->weight, static_cast<jboolean>(font->italic));
    env_->DeleteLocalRef(jtext);
    if (failed()) {
        env_->ExceptionClear();
        return 0;
    }
    return width;
}

void AndroidContainer::draw_text(
    litehtml::uint_ptr hdc,
    const char* text,
    litehtml::uint_ptr hFont,
    litehtml::web_color color,
    const litehtml::position& pos) {
    jobject canvas = canvasOf(hdc);
    const Font* font = reinterpret_cast<const Font*>(hFont);
    if (canvas == nullptr || font == nullptr || text == nullptr || text[0] == '\0' || !ensureApi()) {
        return;
    }
    jstring jtext = env_->NewStringUTF(text);
    if (jtext == nullptr) {
        env_->ExceptionClear();
        return;
    }
    env_->CallStaticVoidMethod(
        api_.cls,
        api_.drawText,
        canvas,
        jtext,
        pos.x,
        pos.y + font->ascent,
        font->size,
        font->weight,
        static_cast<jboolean>(font->italic),
        colorOf(color));
    env_->DeleteLocalRef(jtext);
    if (failed()) {
        env_->ExceptionClear();
    }
}

litehtml::pixel_t AndroidContainer::pt_to_px(float pt) const { return pt * kPtToPx; }

litehtml::pixel_t AndroidContainer::get_default_font_size() const { return kDefaultFontPx; }

const char* AndroidContainer::get_default_font_name() const { return "sans-serif"; }

void AndroidContainer::draw_list_marker(litehtml::uint_ptr hdc, const litehtml::list_marker& marker) {
    jobject canvas = canvasOf(hdc);
    if (canvas == nullptr || !ensureApi()) {
        return;
    }
    const litehtml::position& pos = marker.pos;
    if (marker.marker_type == litehtml::list_style_type_square) {
        env_->CallStaticVoidMethod(
            api_.cls, api_.fill, canvas, pos.x, pos.y, pos.right(), pos.bottom(), 0.f, colorOf(marker.color));
    } else if (marker.marker_type != litehtml::list_style_type_none) {
        env_->CallStaticVoidMethod(
            api_.cls, api_.fillOval, canvas, pos.x, pos.y, pos.right(), pos.bottom(), colorOf(marker.color));
    }
    if (failed()) {
        env_->ExceptionClear();
    }
}

void AndroidContainer::load_image(const char*, const char*, bool) {}

void AndroidContainer::get_image_size(const char*, const char*, litehtml::size& sz) {
    sz.width = 0;
    sz.height = 0;
}

void AndroidContainer::draw_image(
    litehtml::uint_ptr,
    const litehtml::background_layer&,
    const std::string&,
    const std::string&) {}

void AndroidContainer::fillBox(litehtml::uint_ptr hdc, const litehtml::background_layer& layer, jint color) {
    jobject canvas = canvasOf(hdc);
    if (canvas == nullptr || !ensureApi()) {
        return;
    }
    const litehtml::position& box = layer.border_box;
    env_->CallStaticVoidMethod(
        api_.cls,
        api_.fill,
        canvas,
        box.x,
        box.y,
        box.right(),
        box.bottom(),
        boxRadius(layer.border_radius),
        color);
    if (failed()) {
        env_->ExceptionClear();
    }
}

void AndroidContainer::draw_solid_fill(
    litehtml::uint_ptr hdc,
    const litehtml::background_layer& layer,
    const litehtml::web_color& color) {
    if (color.alpha == 0) {
        return;
    }
    fillBox(hdc, layer, colorOf(color));
}

namespace {

struct GradientStops {
    jintArray colors = nullptr;
    jfloatArray stops = nullptr;
};

GradientStops stopsOf(
    JNIEnv* env,
    const std::vector<litehtml::background_layer::color_point>& points) {
    GradientStops out;
    const jsize count = static_cast<jsize>(points.size());
    if (count <= 0) {
        return out;
    }
    out.colors = env->NewIntArray(count);
    out.stops = env->NewFloatArray(count);
    std::vector<jint> colorValues(count);
    std::vector<jfloat> stopValues(count);
    for (jsize i = 0; i < count; ++i) {
        colorValues[i] = colorOf(points[i].color);
        stopValues[i] = points[i].offset;
    }
    env->SetIntArrayRegion(out.colors, 0, count, colorValues.data());
    env->SetFloatArrayRegion(out.stops, 0, count, stopValues.data());
    return out;
}

void releaseStops(JNIEnv* env, GradientStops& stops) {
    if (stops.colors != nullptr) {
        env->DeleteLocalRef(stops.colors);
    }
    if (stops.stops != nullptr) {
        env->DeleteLocalRef(stops.stops);
    }
}

}  // namespace

void AndroidContainer::draw_linear_gradient(
    litehtml::uint_ptr hdc,
    const litehtml::background_layer& layer,
    const litehtml::background_layer::linear_gradient& gradient) {
    jobject canvas = canvasOf(hdc);
    if (canvas == nullptr || gradient.color_points.empty() || !ensureApi()) {
        return;
    }
    GradientStops stops = stopsOf(env_, gradient.color_points);
    const litehtml::position& box = layer.border_box;
    env_->CallStaticVoidMethod(
        api_.cls,
        api_.fillLinear,
        canvas,
        box.x,
        box.y,
        box.right(),
        box.bottom(),
        boxRadius(layer.border_radius),
        gradient.start.x,
        gradient.start.y,
        gradient.end.x,
        gradient.end.y,
        stops.colors,
        stops.stops);
    releaseStops(env_, stops);
    if (failed()) {
        env_->ExceptionClear();
    }
}

void AndroidContainer::draw_radial_gradient(
    litehtml::uint_ptr hdc,
    const litehtml::background_layer& layer,
    const litehtml::background_layer::radial_gradient& gradient) {
    jobject canvas = canvasOf(hdc);
    if (canvas == nullptr || gradient.color_points.empty() || !ensureApi()) {
        return;
    }
    GradientStops stops = stopsOf(env_, gradient.color_points);
    const litehtml::position& box = layer.border_box;
    const float radius = std::max(gradient.radius.x, gradient.radius.y);
    env_->CallStaticVoidMethod(
        api_.cls,
        api_.fillRadial,
        canvas,
        box.x,
        box.y,
        box.right(),
        box.bottom(),
        boxRadius(layer.border_radius),
        gradient.position.x,
        gradient.position.y,
        radius,
        stops.colors,
        stops.stops);
    releaseStops(env_, stops);
    if (failed()) {
        env_->ExceptionClear();
    }
}

void AndroidContainer::draw_conic_gradient(
    litehtml::uint_ptr hdc,
    const litehtml::background_layer& layer,
    const litehtml::background_layer::conic_gradient& gradient) {
    jobject canvas = canvasOf(hdc);
    if (canvas == nullptr || gradient.color_points.empty() || !ensureApi()) {
        return;
    }
    GradientStops stops = stopsOf(env_, gradient.color_points);
    const litehtml::position& box = layer.border_box;
    env_->CallStaticVoidMethod(
        api_.cls,
        api_.fillSweep,
        canvas,
        box.x,
        box.y,
        box.right(),
        box.bottom(),
        boxRadius(layer.border_radius),
        gradient.position.x,
        gradient.position.y,
        stops.colors,
        stops.stops);
    releaseStops(env_, stops);
    if (failed()) {
        env_->ExceptionClear();
    }
}

void AndroidContainer::draw_borders(
    litehtml::uint_ptr hdc,
    const litehtml::borders& borders,
    const litehtml::position& draw_pos,
    bool) {
    jobject canvas = canvasOf(hdc);
    if (canvas == nullptr || !borders.is_visible() || !ensureApi()) {
        return;
    }
    const litehtml::border& edge = borders.top.width > 0 ? borders.top : borders.left;
    if (edge.style == litehtml::border_style_none || edge.style == litehtml::border_style_hidden) {
        return;
    }
    env_->CallStaticVoidMethod(
        api_.cls,
        api_.stroke,
        canvas,
        draw_pos.x,
        draw_pos.y,
        draw_pos.right(),
        draw_pos.bottom(),
        boxRadius(borders.radius),
        edge.width,
        colorOf(edge.color));
    if (failed()) {
        env_->ExceptionClear();
    }
}

void AndroidContainer::set_caption(const char*) {}

void AndroidContainer::set_base_url(const char*) {}

void AndroidContainer::link(const std::shared_ptr<litehtml::document>&, const litehtml::element::ptr&) {}

void AndroidContainer::on_anchor_click(const char*, const litehtml::element::ptr&) {}

void AndroidContainer::on_mouse_event(const litehtml::element::ptr&, litehtml::mouse_event) {}

void AndroidContainer::set_cursor(const char*) {}

void AndroidContainer::transform_text(litehtml::string& text, litehtml::text_transform tt) {
    if (tt == litehtml::text_transform_none || text.empty() || !ensureApi()) {
        return;
    }
    jstring jtext = env_->NewStringUTF(text.c_str());
    if (jtext == nullptr) {
        env_->ExceptionClear();
        return;
    }
    jobject transformed = env_->CallStaticObjectMethod(api_.cls, api_.transformText, jtext, static_cast<jint>(tt));
    env_->DeleteLocalRef(jtext);
    if (failed() || transformed == nullptr) {
        env_->ExceptionClear();
        return;
    }
    const char* utf = env_->GetStringUTFChars(static_cast<jstring>(transformed), nullptr);
    if (utf != nullptr) {
        text.assign(utf);
        env_->ReleaseStringUTFChars(static_cast<jstring>(transformed), utf);
    }
    env_->DeleteLocalRef(transformed);
}

void AndroidContainer::import_css(litehtml::string& text, const litehtml::string&, litehtml::string&) {
    text.clear();
}

void AndroidContainer::set_clip(const litehtml::position& pos, const litehtml::border_radiuses&) {
    jobject canvas = canvas_;
    if (canvas == nullptr || !ensureApi()) {
        return;
    }
    const jint save = env_->CallStaticIntMethod(api_.cls, api_.clip, canvas, pos.x, pos.y, pos.right(), pos.bottom());
    if (failed()) {
        env_->ExceptionClear();
        return;
    }
    clipStack_.push_back(save);
}

void AndroidContainer::del_clip() {
    if (clipStack_.empty() || canvas_ == nullptr || !ensureApi()) {
        return;
    }
    const jint save = clipStack_.back();
    clipStack_.pop_back();
    env_->CallStaticVoidMethod(api_.cls, api_.restore, canvas_, save);
    if (failed()) {
        env_->ExceptionClear();
    }
}

void AndroidContainer::get_viewport(litehtml::position& viewport) const {
    viewport.x = 0;
    viewport.y = 0;
    viewport.width = static_cast<litehtml::pixel_t>(widthCssPx_);
    viewport.height = static_cast<litehtml::pixel_t>(kViewportHeight);
}

litehtml::element::ptr AndroidContainer::create_element(
    const char*,
    const litehtml::string_map&,
    const std::shared_ptr<litehtml::document>&) {
    return nullptr;
}

void AndroidContainer::get_media_features(litehtml::media_features& media) const {
    media.type = litehtml::media_type_screen;
    media.width = static_cast<litehtml::pixel_t>(widthCssPx_);
    media.height = static_cast<litehtml::pixel_t>(kViewportHeight);
    media.device_width = media.width;
    media.device_height = media.height;
    media.color = kColorBits;
    media.color_index = 0;
    media.monochrome = 0;
    media.resolution = 96.f * density_;
}

void AndroidContainer::get_language(litehtml::string& language, litehtml::string& culture) const {
    language = "zh";
    culture = "zh-CN";
}
