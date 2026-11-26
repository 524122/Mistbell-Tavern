#pragma once

#include <jni.h>
#include <litehtml.h>

#include <vector>

// litehtml 只排版。本容器把绘制回调交给 LiteHtmlPaint（系统字体，含中文）。
// JNIEnv 只在一次 nativeRender 调用期间有效。
class AndroidContainer : public litehtml::document_container {
public:
    AndroidContainer(JNIEnv* env, int widthCssPx, float density);
    // api_.cls 是全局引用，容器随每次 nativeRender 栈上重建，析构必须释放
    ~AndroidContainer();
    // 持有全局引用与 JNIEnv，不可拷贝：否则拷贝析构会把同一个全局引用删除两次
    AndroidContainer(const AndroidContainer&) = delete;
    AndroidContainer& operator=(const AndroidContainer&) = delete;

    void setCanvas(jobject canvas);

    litehtml::uint_ptr create_font(
        const litehtml::font_description& descr,
        const litehtml::document* doc,
        litehtml::font_metrics* fm) override;
    void delete_font(litehtml::uint_ptr hFont) override;
    litehtml::pixel_t text_width(const char* text, litehtml::uint_ptr hFont) override;
    void draw_text(
        litehtml::uint_ptr hdc,
        const char* text,
        litehtml::uint_ptr hFont,
        litehtml::web_color color,
        const litehtml::position& pos) override;
    litehtml::pixel_t pt_to_px(float pt) const override;
    litehtml::pixel_t get_default_font_size() const override;
    const char* get_default_font_name() const override;
    void draw_list_marker(litehtml::uint_ptr hdc, const litehtml::list_marker& marker) override;
    void load_image(const char* src, const char* baseurl, bool redraw_on_ready) override;
    void get_image_size(const char* src, const char* baseurl, litehtml::size& sz) override;
    void draw_image(
        litehtml::uint_ptr hdc,
        const litehtml::background_layer& layer,
        const std::string& url,
        const std::string& base_url) override;
    void draw_solid_fill(
        litehtml::uint_ptr hdc,
        const litehtml::background_layer& layer,
        const litehtml::web_color& color) override;
    void draw_linear_gradient(
        litehtml::uint_ptr hdc,
        const litehtml::background_layer& layer,
        const litehtml::background_layer::linear_gradient& gradient) override;
    void draw_radial_gradient(
        litehtml::uint_ptr hdc,
        const litehtml::background_layer& layer,
        const litehtml::background_layer::radial_gradient& gradient) override;
    void draw_conic_gradient(
        litehtml::uint_ptr hdc,
        const litehtml::background_layer& layer,
        const litehtml::background_layer::conic_gradient& gradient) override;
    void draw_borders(
        litehtml::uint_ptr hdc,
        const litehtml::borders& borders,
        const litehtml::position& draw_pos,
        bool root) override;
    void set_caption(const char* caption) override;
    void set_base_url(const char* base_url) override;
    void link(const std::shared_ptr<litehtml::document>& doc, const litehtml::element::ptr& el) override;
    void on_anchor_click(const char* url, const litehtml::element::ptr& el) override;
    void on_mouse_event(const litehtml::element::ptr& el, litehtml::mouse_event event) override;
    void set_cursor(const char* cursor) override;
    void transform_text(litehtml::string& text, litehtml::text_transform tt) override;
    void import_css(litehtml::string& text, const litehtml::string& url, litehtml::string& baseurl) override;
    void set_clip(const litehtml::position& pos, const litehtml::border_radiuses& bdr_radius) override;
    void del_clip() override;
    void get_viewport(litehtml::position& viewport) const override;
    litehtml::element::ptr create_element(
        const char* tag_name,
        const litehtml::string_map& attributes,
        const std::shared_ptr<litehtml::document>& doc) override;
    void get_media_features(litehtml::media_features& media) const override;
    void get_language(litehtml::string& language, litehtml::string& culture) const override;

private:
    struct Font {
        float size = 16.f;
        int weight = 400;
        bool italic = false;
        float ascent = 12.f;
        float descent = 4.f;
    };

    struct PaintApi {
        jclass cls = nullptr;
        jmethodID fontMetrics = nullptr;
        jmethodID textWidth = nullptr;
        jmethodID drawText = nullptr;
        jmethodID fill = nullptr;
        jmethodID fillLinear = nullptr;
        jmethodID fillRadial = nullptr;
        jmethodID fillSweep = nullptr;
        jmethodID stroke = nullptr;
        jmethodID fillOval = nullptr;
        jmethodID clip = nullptr;
        jmethodID restore = nullptr;
        jmethodID transformText = nullptr;
    };

    JNIEnv* env_;
    jobject canvas_ = nullptr;
    int widthCssPx_;
    float density_;
    PaintApi api_{};
    std::vector<jint> clipStack_;

    bool ensureApi();
    bool failed() const;
    jobject canvasOf(litehtml::uint_ptr hdc) const;
    void fillBox(litehtml::uint_ptr hdc, const litehtml::background_layer& layer, jint color);
    static jint argb(const litehtml::web_color& color);
    static float boxRadius(const litehtml::border_radiuses& radius);
};
