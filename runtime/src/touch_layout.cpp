// Drawn touch controls (docs/nvn/ANDROID-PORT.md A4): a fixed layout of round buttons per game, hit-tested by
// platform_android_input.cpp and drawn by the NVN present pass (nvn_present.cpp, tools/shaders/present/overlay.frag)
// over the picture, each with its label.
//
// Super Mario Bros. Wonder, landscape: A (jump, the big one) at the bottom right with B (jump), Y (dash, action) and
// X (action) around it; ZR (roll), R, L, + and - along the top. The left half is the stick (platform_android_input.cpp):
// it appears where the thumb lands, and pushed nearly all the way it also holds Y, so running needs no second thumb.
//
// Persona 5 Royal, landscape: the four face buttons in the Switch diamond at the bottom right (A confirm/talk, B back,
// X menu, Y), L / ZL and R / ZR stacked in the top corners, - and + at the top centre, the d-pad on the left above
// the stick, with a gap (the d-pad's down arrow touched the stick's ring). The left half is the stick; the rest of
// the right half drags the right stick (the camera). The launcher's editor (games/p5r/java: ControlsEditor.java, which
// has a copy of this table) moves any of them, the stick's ring included (NX_TOUCH_LAYOUT).
//
// Games without a layout here keep their own touch handling (Hades: zones around the thumb).
//
// Elsewhere touch_overlay() draws nothing, except with NX_OVERLAY_DEMO=1 (the buttons at rest), to check the look
// on the PC or the Mac with NX_PRESENT_TEST.
#include "touch_layout.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <nx/game.h>

namespace nx::plat {

namespace {

struct Named {
    const char* name;  // for NX_TOUCH_LAYOUT
    TouchButton b;
};

// x, y: 0..1 across the landscape screen; r: a fraction of the screen height.
const Named kWonder[] = {
    {"A", {0.905f, 0.74f, 0.105f, kA, touch_label(kGlyphA)}},
    {"B", {0.775f, 0.86f, 0.085f, kB, touch_label(kGlyphB)}},
    {"Y", {0.775f, 0.60f, 0.085f, kY, touch_label(kGlyphY)}},
    {"X", {0.905f, 0.47f, 0.075f, kX, touch_label(kGlyphX)}},
    {"ZR", {0.955f, 0.25f, 0.055f, kZR, touch_label(kGlyphZ, kGlyphR)}},
    {"R", {0.955f, 0.07f, 0.045f, kR, touch_label(kGlyphR)}},
    {"L", {0.045f, 0.07f, 0.045f, kL, touch_label(kGlyphL)}},
    {"PLUS", {0.545f, 0.07f, 0.040f, kPlus, touch_label(kGlyphPlus)}},
    {"MINUS", {0.455f, 0.07f, 0.040f, kMinus, touch_label(kGlyphMinus)}},
};

const Named kP5R[] = {
    {"A", {0.940f, 0.66f, 0.068f, kA, touch_label(kGlyphA)}},
    {"B", {0.875f, 0.80f, 0.068f, kB, touch_label(kGlyphB)}},
    {"X", {0.875f, 0.52f, 0.068f, kX, touch_label(kGlyphX)}},
    {"Y", {0.810f, 0.66f, 0.068f, kY, touch_label(kGlyphY)}},
    {"R", {0.945f, 0.08f, 0.052f, kR, touch_label(kGlyphR)}},
    {"ZR", {0.945f, 0.24f, 0.052f, kZR, touch_label(kGlyphZ, kGlyphR)}},
    {"L", {0.055f, 0.08f, 0.052f, kL, touch_label(kGlyphL)}},
    {"ZL", {0.055f, 0.24f, 0.052f, kZL, touch_label(kGlyphZ, kGlyphL)}},
    {"MINUS", {0.440f, 0.07f, 0.045f, kMinus, touch_label(kGlyphMinus)}},
    {"PLUS", {0.560f, 0.07f, 0.045f, kPlus, touch_label(kGlyphPlus)}},
    {"UP", {0.135f, 0.31f, 0.048f, kUp, touch_label(kGlyphUp)}},
    {"DOWN", {0.135f, 0.49f, 0.048f, kDown, touch_label(kGlyphDown)}},
    {"LEFT", {0.085f, 0.40f, 0.048f, kLeft, touch_label(kGlyphLeft)}},
    {"RIGHT", {0.185f, 0.40f, 0.048f, kRight, touch_label(kGlyphRight)}},
    {"L3", {0.055f, 0.58f, 0.046f, kStickL, touch_label(kGlyphL, kGlyph3)}},
    {"R3", {0.740f, 0.82f, 0.046f, kStickR, touch_label(kGlyphR, kGlyph3)}},
    {"EDIT", {0.620f, 0.07f, 0.045f, kTouchEdit, touch_label(kGlyphSettings)}},
};

struct Layout {
    const char* game;
    const Named* buttons;
    size_t count;
    bool right_stick;
    uint64_t stick_full;
    float stick_x, stick_y;  // where the stick's ring is drawn at rest (radius kStickRing)
};

const Layout kLayouts[] = {
    {"wonder", kWonder, std::size(kWonder), false, kY, 0.15f, 0.72f},
    {"p5r", kP5R, std::size(kP5R), true, 0, 0.15f, 0.76f},
};

const Layout* layout() {
    static const Layout* l = [] () -> const Layout* {
        for (const auto& k : kLayouts)
            if (std::strcmp(game::kInfo.id, k.game) == 0) return &k;
        return nullptr;
    }();
    return l;
}

struct TouchSettings {
    std::vector<TouchButton> buttons;
    float stick_x = 0.15f, stick_y = 0.76f, stick_r = kStickRing;
    float camera_x = 0.70f, camera_y = 0.45f, camera_r = kStickRing;
    float opacity = 1.0f;
    bool enabled = true, hidden = false, mirrored = false, fixed_sticks = false;
    bool stick_visible = true, camera_visible = true;
};

std::mutex g_settings_mutex;
std::shared_ptr<TouchSettings> g_settings;
std::vector<std::shared_ptr<TouchSettings>> g_old_settings;  // pointers returned to the renderer stay valid after an edit
std::string g_signature;
uint64_t g_last_settings_read = 0;

bool layout_entry(const std::string& all, const char* name, float& x, float& y, float& r) {
    const size_t len = std::strlen(name);
    size_t pos = 0;
    while (pos < all.size()) {
        size_t end = all.find(';', pos);
        if (end == std::string::npos) end = all.size();
        const std::string item = all.substr(pos, end - pos);
        pos = end + 1;
        float ix, iy, ir;
        if (item.size() <= len || item.compare(0, len, name) != 0 || item[len] != ':' ||
            std::sscanf(item.c_str() + len + 1, "%f,%f,%f", &ix, &iy, &ir) != 3)
            continue;
        if (ix < 0 || ix > 1 || iy < 0 || iy > 1 || ir <= 0.01f || ir >= 0.3f) return false;
        x = ix, y = iy, r = ir;
        return true;
    }
    return false;
}

void env_values(std::map<std::string, std::string>& values) {
    for (const char* key : {"NX_TOUCH_LAYOUT", "NX_TOUCH_HIDE_BUTTONS", "NX_TOUCH_FIXED_STICKS", "NX_TOUCH_SHOW",
                            "NX_TOUCH_HIDE", "NX_TOUCH_MIRROR", "NX_TOUCH_SIZE", "NX_TOUCH_OPACITY"}) {
        if (const char* e = std::getenv(key)) values[key] = e;
    }
}

std::string value(const std::map<std::string, std::string>& values, const char* key, const char* fallback = "") {
    auto it = values.find(key);
    return it == values.end() ? fallback : it->second;
}

bool listed(const std::string& names, const char* name) {
    const size_t len = std::strlen(name);
    size_t pos = 0;
    while (pos < names.size()) {
        size_t end = names.find(';', pos);
        if (end == std::string::npos) end = names.size();
        if (end - pos == len && names.compare(pos, len, name) == 0) return true;
        pos = end + 1;
    }
    return false;
}

void refresh_settings() {
    const uint64_t now = monotonic_ns();
    {
        std::lock_guard lock(g_settings_mutex);
        if (g_settings && (now < g_last_settings_read || now - g_last_settings_read < 250'000'000)) return;
        g_last_settings_read = now;
    }

    std::map<std::string, std::string> values;
    env_values(values);
#if defined(__ANDROID__)
    const std::string root = data_root();
    if (!root.empty()) {
        std::ifstream f(root + "/config.txt");
        std::string line;
        while (std::getline(f, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            const size_t eq = line.find('=');
            if (eq == std::string::npos) continue;
            const std::string key = line.substr(0, eq);
            if (key.rfind("NX_TOUCH_", 0) == 0) values[key] = line.substr(eq + 1);
        }
    }
#endif

    std::string signature;
    for (const char* key : {"NX_TOUCH_LAYOUT", "NX_TOUCH_HIDE_BUTTONS", "NX_TOUCH_FIXED_STICKS", "NX_TOUCH_SHOW",
                            "NX_TOUCH_HIDE", "NX_TOUCH_MIRROR", "NX_TOUCH_SIZE", "NX_TOUCH_OPACITY"}) {
        signature += key;
        signature += '=';
        signature += value(values, key);
        signature += '\n';
    }
    std::lock_guard lock(g_settings_mutex);
    if (g_settings && signature == g_signature) return;

    auto out = std::make_shared<TouchSettings>();
    const std::string layout_value = value(values, "NX_TOUCH_LAYOUT");
    const std::string hidden_buttons = value(values, "NX_TOUCH_HIDE_BUTTONS");
    const Layout* l = layout();
    if (l) {
        for (size_t k = 0; k < l->count; k++) {
            out->buttons.push_back(l->buttons[k].b);
            layout_entry(layout_value, l->buttons[k].name, out->buttons.back().x, out->buttons.back().y,
                         out->buttons.back().r);
            out->buttons.back().visible = l->buttons[k].b.button == kTouchEdit ||
                                          !listed(hidden_buttons, l->buttons[k].name);
        }
    }
    int size = std::atoi(value(values, "NX_TOUCH_SIZE", "100").c_str());
    float scale = float(std::clamp(size, 50, 160)) / 100.0f;
    for (auto& b : out->buttons) b.r *= scale;

    out->stick_x = l ? l->stick_x : 0.15f;
    out->stick_y = l ? l->stick_y : 0.72f;
    out->stick_r = kStickRing;
    out->mirrored = value(values, "NX_TOUCH_MIRROR") == "1";
    if (out->mirrored) out->stick_x = 1 - out->stick_x;
    layout_entry(layout_value, "STICK", out->stick_x, out->stick_y, out->stick_r);
    out->camera_x = 0.70f, out->camera_y = 0.45f, out->camera_r = kStickRing;
    layout_entry(layout_value, "RSTICK", out->camera_x, out->camera_y, out->camera_r);
    out->stick_visible = !listed(hidden_buttons, "STICK");
    out->camera_visible = !listed(hidden_buttons, "RSTICK");

    const std::string show = value(values, "NX_TOUCH_SHOW", "1");
    out->enabled = !(show == "0");
    const std::string hide = value(values, "NX_TOUCH_HIDE");
    const std::string opacity = value(values, "NX_TOUCH_OPACITY");
    out->hidden = hide == "1" || show == "2" || show == "hide" || show == "hidden" || opacity == "0";
    out->fixed_sticks = value(values, "NX_TOUCH_FIXED_STICKS") == "1";
    int alpha = std::atoi(opacity.empty() ? "85" : opacity.c_str());
    out->opacity = float(std::clamp(alpha, 10, 100)) / 85.0f;

    if (g_settings) g_old_settings.push_back(g_settings);
    g_settings = std::move(out);
    g_signature = std::move(signature);
}

}  // namespace

// NX_TOUCH_SHOW=0 (launcher: On-screen buttons off): nothing is drawn and touches are ignored.
bool touch_enabled() {
    refresh_settings();
    std::lock_guard lock(g_settings_mutex);
    return g_settings->enabled;
}

// NX_TOUCH_HIDE=1: on-screen controls are invisible (not drawn), but touch inputs continue to register.
bool touch_hidden() {
    refresh_settings();
    std::lock_guard lock(g_settings_mutex);
    return g_settings->hidden;
}

// NX_TOUCH_MIRROR=1 (left-handed): the stick takes the right half of the screen instead of the left.
bool touch_mirrored() {
    refresh_settings();
    std::lock_guard lock(g_settings_mutex);
    return g_settings->mirrored;
}

bool touch_fixed_sticks() {
    refresh_settings();
    std::lock_guard lock(g_settings_mutex);
    return g_settings->fixed_sticks;
}

bool touch_stick_visible() {
    refresh_settings();
    std::lock_guard lock(g_settings_mutex);
    return g_settings->stick_visible;
}

bool touch_camera_stick_visible() {
    refresh_settings();
    std::lock_guard lock(g_settings_mutex);
    return g_settings->camera_visible;
}

// NX_TOUCH_OPACITY (10..100, default 85): the drawn buttons' brightness relative to the original look.
float touch_opacity_scale() {
    refresh_settings();
    std::lock_guard lock(g_settings_mutex);
    return g_settings->opacity;
}

const TouchButton* touch_buttons(size_t& count) {
    refresh_settings();
    std::lock_guard lock(g_settings_mutex);
    count = g_settings->buttons.size();
    return count ? g_settings->buttons.data() : nullptr;
}

bool touch_right_stick() { return layout() && layout()->right_stick; }

// The stick's ring at rest: the layout's place (NX_TOUCH_MIRROR=1: mirrored), or the launcher's (STICK:x,y,r).
void touch_stick_rest(float& x, float& y, float& r) {
    refresh_settings();
    std::lock_guard lock(g_settings_mutex);
    x = g_settings->stick_x, y = g_settings->stick_y, r = g_settings->stick_r;
}

void touch_camera_stick_rest(float& x, float& y, float& r) {
    refresh_settings();
    std::lock_guard lock(g_settings_mutex);
    x = g_settings->camera_x, y = g_settings->camera_y, r = g_settings->camera_r;
}

bool touch_stick_side(float x) {
    float sx, sy, sr;
    touch_stick_rest(sx, sy, sr);
    return sx < 0.5f ? x < 0.5f : x >= 0.5f;
}

uint64_t touch_stick_full_button() { return layout() ? layout()->stick_full : 0; }

uint64_t touch_hit(float x, float y, float aspect) {
    size_t n = 0;
    const TouchButton* b = touch_buttons(n);
    uint64_t best = 0;
    float best_d = 1e9f;
    constexpr uint64_t kDpadMask = kUp | kDown | kLeft | kRight;
    uint64_t dpad_hits = 0;
    for (size_t k = 0; k < n; k++) {
        if (!b[k].visible) continue;
        float d = std::hypot((x - b[k].x) * aspect, y - b[k].y) / b[k].r;
        if ((b[k].button & kDpadMask) && d < 1.45f) dpad_hits |= b[k].button;
        if (d < 1.25f && d < best_d) best = b[k].button, best_d = d;  // a little past the drawn edge still counts
    }
    if ((best & kDpadMask) && dpad_hits) {
        if ((dpad_hits & (kUp | kDown)) == (kUp | kDown)) dpad_hits &= (best & (kUp | kDown)) | kLeft | kRight;
        if ((dpad_hits & (kLeft | kRight)) == (kLeft | kRight)) dpad_hits &= (best & (kLeft | kRight)) | kUp | kDown;
        return dpad_hits;
    }
    return best;
}

#if !defined(__ANDROID__)
size_t touch_overlay(OverlayShape* out, size_t max) {
    static const bool demo = std::getenv("NX_OVERLAY_DEMO") != nullptr;
    if (!demo) return 0;
    size_t n = 0, k = 0;
    const TouchButton* b = touch_buttons(n);
    for (size_t i = 0; i < n && k < max; i++)
        if (b[i].visible) out[k++] = {b[i].x, b[i].y, b[i].r, 0.22f, b[i].label};
    float sx, sy, sr;
    touch_stick_rest(sx, sy, sr);
    if (k < max) out[k++] = {sx, sy, sr, 0.10f};  // where the stick usually goes
    float cx, cy, cr;
    touch_camera_stick_rest(cx, cy, cr);
    if (touch_fixed_sticks() && touch_right_stick() && k < max) out[k++] = {cx, cy, cr, 0.10f};
    return k;
}
#endif

}  // namespace nx::plat
