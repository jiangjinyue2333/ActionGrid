// Test-only Android adapter. Include production implementation rather than copying grid logic.
#include "../../main/cpp/native_init.cpp"
#include <map>
#include <sstream>
#include <stdexcept>

namespace {
constexpr const char *kFixtureTag = "AGReaderFixture";
JavaVM *fixtureVm = nullptr;
jobject fixtureView = nullptr;
int fixtureWidth = 0, fixtureHeight = 0, fixtureTop = 0;
int fixtureRealFocus = -1, fixtureRealHover = -1;
int fixtureDispatchRow = -1, fixtureDispatchColumn = -1;
void *fixtureProvider = reinterpret_cast<void *>(1);
struct FixtureAction { int32_t id; std::string label; };
struct FixtureNode {
    std::array<uint8_t, 0x460> raw{}; // Must be first: the production ABI reads these fields.
    std::map<size_t, std::string> strings;
    std::vector<int64_t> children;
    std::vector<FixtureAction> actions;
    Bounds parentBounds{};
    std::string className = "android.view.View";
    bool focusable = false, clickable = false, important = false, focused = false;
    int after = -1, before = -1;
};
struct FixtureEvent { int32_t type; int32_t node = -1; std::string text; };
struct FixtureMotion { int32_t action; float x, y; };
struct DispatchCaptured {};

template<class T> void fixturePut(void *node, size_t offset, T value) {
    std::memcpy(static_cast<uint8_t *>(node) + offset, &value, sizeof(value));
}
std::string fixtureUtf8(const RustRString &value) {
    std::u16string input(reinterpret_cast<const char16_t *>(value.first), value.second);
    std::string result;
    for (char16_t c : input) {
        if (c < 0x80) result += static_cast<char>(c);
        else if (c < 0x800) {
            result += static_cast<char>(0xc0 | (c >> 6));
            result += static_cast<char>(0x80 | (c & 63));
        } else {
            result += static_cast<char>(0xe0 | (c >> 12));
            result += static_cast<char>(0x80 | ((c >> 6) & 63));
            result += static_cast<char>(0x80 | (c & 63));
        }
    }
    return result;
}
void fixtureString(FixtureNode *node, size_t offset, const std::string &text) {
    auto &owned = node->strings[offset]; owned = text;
    fixturePut(node, offset, owned.size());
    fixturePut(node, offset + 8, owned.data());
    fixturePut(node, offset + 16, owned.size());
}
void fixtureChild(FixtureNode *node, int32_t host, int32_t id) {
    node->children.push_back(static_cast<int64_t>(eventSourceKey(host, id)));
    fixturePut(node, 0x30, node->children.capacity());
    fixturePut(node, 0x38, node->children.data());
    fixturePut(node, 0x40, node->children.size());
}
void fixtureEvent(int32_t type, int32_t id, const std::string &text) {
    if (fixtureView == nullptr) return;
    JNIEnv *env = nullptr;
    bool attach = fixtureVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK;
    if (attach && fixtureVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    jclass cls = env->GetObjectClass(fixtureView);
    jmethodID method = env->GetMethodID(cls, "nativeEvent", "(IILjava/lang/String;)V");
    jstring speech = env->NewStringUTF(text.c_str());
    env->CallVoidMethod(fixtureView, method, type, id, speech);
    env->DeleteLocalRef(speech); env->DeleteLocalRef(cls);
    if (attach) fixtureVm->DetachCurrentThread();
}
NodeResult fixtureCreate(void *, int32_t id) {
    auto *node = new FixtureNode();
    int32_t parent = -1;
    Bounds bounds{0, fixtureTop, fixtureWidth, fixtureTop + fixtureHeight};
    const int pageHeight = fixtureHeight - 120;
    const int cellWidth = fixtureWidth / 4;
    const int cellHeight = pageHeight / 6;
    const char *resource = "com.miui.home:id/workspace";
    const char *label = "";
    if (id == -1) { fixtureChild(node, 7, 10); fixtureChild(node, 7, 20); }
    else if (id == 10) {
        bounds.bottom = fixtureTop + pageHeight;
        resource = "com.miui.home:id/screen_content";
        fixtureChild(node, 7, 11); fixtureChild(node, 7, 12);
    } else if (id == 11 || id == 12) {
        parent = 10; resource = "com.miui.home:id/icon_icon"; label = id == 11 ? "QQ" : "微信";
        int col = id == 11 ? 0 : 2;
        bounds = {col * cellWidth, fixtureTop, (col + 1) * cellWidth, fixtureTop + cellHeight};
        node->focusable = node->clickable = node->important = true;
    } else if (id == 20) {
        resource = "com.miui.home:id/hotseat";
        bounds.top = fixtureTop + pageHeight;
        fixtureChild(node, 7, 21);
    } else if (id == 21) {
        parent = 20; resource = "com.miui.home:id/icon_icon"; label = "电话";
        bounds = {0, fixtureTop + pageHeight, cellWidth, fixtureTop + fixtureHeight};
        node->focusable = node->clickable = node->important = true;
    } else { delete node; return {1, nullptr}; }
    node->parentBounds = bounds;
    node->parentBounds.top -= parent == 20 ? fixtureTop + pageHeight : fixtureTop;
    node->parentBounds.bottom -= parent == 20 ? fixtureTop + pageHeight : fixtureTop;
    node->focused = fixtureRealFocus == id;
    if (node->focusable) {
        node->actions.push_back({16, "点击"});
        node->actions.push_back({node->focused ? 128 : 64, node->focused ? "清除无障碍焦点" : "获取无障碍焦点"});
    }
    fixturePut(node, 0x338, eventSourceKey(7, id));
    fixturePut(node, 0x340, eventSourceKey(7, parent));
    fixturePut(node, 0x418, int32_t{8});
    fixturePut(node, kNodeBoundsOffset, bounds);
    fixtureString(node, 0x138, label); fixtureString(node, 0x1b8, resource);
    return {0, node};
}
bool fixtureOriginalAction(void *, int32_t id, int32_t action, void *) {
    if (id != 11 && id != 12 && id != 21) return false;
    if (action == 64) {
        if (fixtureRealFocus == id) return false;
        if (fixtureRealFocus != -1) fixtureEvent(65536, fixtureRealFocus, "");
        fixtureRealFocus = id;
        fixtureEvent(32768, id, ""); return true;
    }
    if (action == 128 && fixtureRealFocus == id) {
        fixtureRealFocus = -1; fixtureEvent(65536, id, ""); return true;
    }
    return action == 16;
}
std::string fixtureQuote(const std::string &value) {
    std::string result = "\"";
    for (unsigned char c : value) {
        if (c == '\\' || c == '"') result += '\\';
        if (c >= 32) result += static_cast<char>(c);
    }
    return result + "\"";
}
void fixtureReset() {
    clearLiftSession(); fixtureRealFocus = -1; fixtureRealHover = -1;
    g_model_move_running.store(false);
    g_visible_screen.store(0); g_page_state_known.store(true); g_total_pages.store(1);
    g_grid_columns.store(4); g_grid_rows.store(6);
    g_launcher_window_focused.store(1);
    g_surface_state.store(static_cast<int32_t>(SurfaceState::kWorkspace));
    g_last_provider.store(fixtureProvider);
    g_pending_surface_announcements.clear();
    g_has_recent_surface_time = false;
    g_snapshot = {}; g_snapshot.ready = true; g_snapshot.generation = 1;
    for (int i = 0; i < 3; ++i) {
        SnapshotFavorite favorite;
        favorite.id = 101 + i;
        favorite.title = i == 0 ? "QQ" : i == 1 ? "微信" : "电话";
        favorite.label = favorite.title;
        favorite.placement = {favorite.id, i == 2 ? -101 : kDesktopContainer, 1, i == 1 ? 2 : 0, 0, 1, 1, 0};
        g_snapshot.favorites.push_back(favorite);
    }
    g_snapshot.pageCounts[1] = 2;
    fixtureEvent(2048, -1, "");
}
void fixtureConfigure() {
    g_capabilities.store(CAP_MOVE_PROFILE | CAP_PRIVATE_BUILD_MATCH | CAP_PRIVATE_SYMBOLS | CAP_FRB_MODEL);
    g_launcher_system_compatible.store(true);
    g_current_frb_move_profile.store(true);
    g_launcher_profile.store(LauncherAbiProfile::kCurrent8565);
    g_node_new = +[]() -> void * { return new FixtureNode(); };
    g_node_drop = +[](void *value) { delete static_cast<FixtureNode *>(value); };
    g_original_create_node = fixtureCreate; g_create_node = onCreateNode;
    g_original_perform_action = fixtureOriginalAction;
    g_rstring_from_utf16 = +[](const uint16_t *value, size_t length) -> RustRString {
        return {reinterpret_cast<uintptr_t>(value), length, 0, 0};
    };
    g_action_new = +[](int32_t id, void *label) -> void * {
        return new FixtureAction{id, fixtureUtf8(*static_cast<RustRString *>(label))};
    };
    g_action_drop = +[](void *action) { delete static_cast<FixtureAction *>(action); };
    g_original_add_action = +[](void *node, void *action) {
        static_cast<FixtureNode *>(node)->actions.push_back(*static_cast<FixtureAction *>(action));
    };
    g_node_add_child = +[](void *node, int32_t host, int32_t id) { fixtureChild(static_cast<FixtureNode *>(node), host, id); };
    g_node_set_source = +[](void *node, int32_t host, int32_t id, int32_t window) {
        fixturePut(node, 0x338, eventSourceKey(host, id)); fixturePut(node, 0x418, window);
    };
    // Match the verified source/parent ABI separately (parent has no window argument).
    g_node_set_parent = +[](void *node, int32_t host, int32_t id) { fixturePut(node, 0x340, eventSourceKey(host, id)); };
    g_node_set_bounds = +[](void *node, uint64_t first, uint64_t second) {
        fixturePut(node, kNodeBoundsOffset, Bounds{static_cast<int32_t>(first), static_cast<int32_t>(first >> 32), static_cast<int32_t>(second), static_cast<int32_t>(second >> 32)});
    };
    g_node_set_parent_bounds = +[](void *node, uint64_t first, uint64_t second) {
        static_cast<FixtureNode *>(node)->parentBounds = {static_cast<int32_t>(first), static_cast<int32_t>(first >> 32), static_cast<int32_t>(second), static_cast<int32_t>(second >> 32)};
    };
    g_node_set_content_description = +[](void *node, void *value) { fixtureString(static_cast<FixtureNode *>(node), 0x138, fixtureUtf8(*static_cast<RustRString *>(value))); };
    g_node_set_view_id = +[](void *node, void *value) { fixtureString(static_cast<FixtureNode *>(node), 0x1b8, fixtureUtf8(*static_cast<RustRString *>(value))); };
    g_node_set_class_name = +[](void *node, void *value) { static_cast<FixtureNode *>(node)->className = fixtureUtf8(*static_cast<RustRString *>(value)); };
    g_node_set_package_name = +[](void *, void *) {};
    g_node_set_visible = +[](void *, bool) {};
    g_node_set_enabled = +[](void *, bool) {};
    g_node_set_focusable = +[](void *node, bool value) { static_cast<FixtureNode *>(node)->focusable = value; };
    g_node_set_clickable = +[](void *node, bool value) { static_cast<FixtureNode *>(node)->clickable = value; };
    g_node_set_important = +[](void *node, bool value) { static_cast<FixtureNode *>(node)->important = value; };
    g_node_set_accessibility_focused = +[](void *node, bool value) { static_cast<FixtureNode *>(node)->focused = value; };
    g_node_set_traversal_after = +[](void *node, int32_t, int32_t id) { static_cast<FixtureNode *>(node)->after = id; };
    g_node_set_traversal_before = +[](void *node, int32_t, int32_t id) { static_cast<FixtureNode *>(node)->before = id; };
    g_event_controller.store(fixtureProvider);
    g_event_new = +[](int32_t type) -> void * { return new FixtureEvent{type}; };
    g_event_drop = +[](void *event) { delete static_cast<FixtureEvent *>(event); };
    g_event_set_source = +[](void *event, int32_t, int32_t, int32_t id) { static_cast<FixtureEvent *>(event)->node = id; };
    g_event_set_package = +[](void *, void *) {};
    g_event_set_event_type = +[](void *event, int32_t type) { static_cast<FixtureEvent *>(event)->type = type; };
    g_event_set_change = +[](void *, int32_t) {};
    g_event_push_text = +[](void *event, void *text) { static_cast<FixtureEvent *>(event)->text = fixtureUtf8(*static_cast<RustRString *>(text)); };
    g_original_send_event = +[](void *, void *event) { auto &e = *static_cast<FixtureEvent *>(event); fixtureEvent(e.type, e.node, e.text); };
    g_motion_action_masked = +[](void *event) { return static_cast<FixtureMotion *>(event)->action; };
    g_motion_raw_x = +[](void *event, int32_t) { return static_cast<FixtureMotion *>(event)->x; };
    g_motion_raw_y = +[](void *event, int32_t) { return static_cast<FixtureMotion *>(event)->y; };
    g_original_launcher_motion = +[](void *, void *event) {
        auto &motion = *static_cast<FixtureMotion *>(event);
        int target = -1;
        if (motion.action != 10) for (int id : {11, 12, 21}) {
            auto result = fixtureCreate(nullptr, id);
            auto bounds = nodeField<Bounds>(result.node, kNodeBoundsOffset);
            g_node_drop(result.node);
            if (motion.x >= bounds.left && motion.x < bounds.right && motion.y >= bounds.top && motion.y < bounds.bottom) target = id;
        }
        if (target == fixtureRealHover) return;
        if (fixtureRealHover != -1) fixtureEvent(256, fixtureRealHover, "");
        fixtureRealHover = target;
        if (target != -1) fixtureEvent(128, target, "");
    };
    g_motion_clone = +[](void *event) -> void * { return new FixtureMotion(*static_cast<FixtureMotion *>(event)); };
    g_motion_drop = +[](void *event) { delete static_cast<FixtureMotion *>(event); };
    g_motion_set_action = +[](void *event, int32_t action) { static_cast<FixtureMotion *>(event)->action = action; };
    g_frb_dispatcher = +[](uint32_t operation, int64_t, const uint8_t *bytes, uint32_t size, uint32_t) {
        // Capture the real production wire request before starting any persistence worker.
        int32_t screen = -1, column = -1, row = -1;
        if (operation == kFrbMoveItemInDatabase && size >= 95) {
            std::memcpy(&screen, bytes + size - 12, 4);
            std::memcpy(&column, bytes + size - 8, 4);
            std::memcpy(&row, bytes + size - 4, 4);
        }
        __android_log_print(ANDROID_LOG_INFO, kFixtureTag, "DISPATCH operation=%u size=%u screen=%d row=%d column=%d", operation, size, screen, row + 1, column + 1);
        fixtureDispatchRow = row; fixtureDispatchColumn = column;
        std::free(const_cast<uint8_t *>(bytes));
        throw DispatchCaptured{};
    };
}
}

extern "C" JNIEXPORT void JNICALL Java_io_github_jiangjinyue2333_actiongrid_readerfixture_ReaderFixtureActivity_nativeInit(JNIEnv *env, jclass, jobject view, jint width, jint height, jint top) {
    env->GetJavaVM(&fixtureVm);
    if (fixtureView) env->DeleteGlobalRef(fixtureView);
    fixtureView = env->NewGlobalRef(view);
    fixtureWidth = width; fixtureHeight = height; fixtureTop = top;
    fixtureConfigure(); fixtureReset();
}
extern "C" JNIEXPORT jstring JNICALL Java_io_github_jiangjinyue2333_actiongrid_readerfixture_ReaderFixtureActivity_nativeNode(JNIEnv *env, jclass, jint id) {
    NodeResult result = onCreateNode(fixtureProvider, id);
    if ((result.absent & 1U) || !result.node) return nullptr;
    auto *node = static_cast<FixtureNode *>(result.node);
    Bounds bounds = nodeField<Bounds>(node, kNodeBoundsOffset);
    Bounds p = node->parentBounds;
    std::ostringstream json;
    json << "{\"id\":" << id << ",\"parent\":" << static_cast<int32_t>(nodeField<int64_t>(node, 0x340) >> 32)
         << ",\"label\":" << fixtureQuote(nodeString(node, 0x138)) << ",\"resource\":" << fixtureQuote(nodeString(node, 0x1b8))
         << ",\"class\":" << fixtureQuote(node->className)
         << ",\"bounds\":[" << bounds.left << ',' << bounds.top << ',' << bounds.right << ',' << bounds.bottom << ']'
         << ",\"parentBounds\":[" << p.left << ',' << p.top << ',' << p.right << ',' << p.bottom << ']'
         << ",\"focusable\":" << (node->focusable ? "true" : "false") << ",\"clickable\":" << (node->clickable ? "true" : "false")
         << ",\"important\":" << (node->important ? "true" : "false") << ",\"focused\":" << (node->focused ? "true" : "false")
         << ",\"after\":" << node->after << ",\"before\":" << node->before << ",\"children\":[";
    const size_t childCount = nodeField<size_t>(node, 0x40);
    const auto *childIds = nodeField<const int64_t *>(node, 0x38);
    for (size_t i = 0; i < childCount; ++i) { if (i) json << ','; json << static_cast<int32_t>(childIds[i] >> 32); }
    json << "],\"actions\":[";
    for (size_t i = 0; i < node->actions.size(); ++i) { if (i) json << ','; json << '[' << node->actions[i].id << ',' << fixtureQuote(node->actions[i].label) << ']'; }
    json << "]}";
    std::string output = json.str(); g_node_drop(node);
    return env->NewStringUTF(output.c_str());
}
extern "C" JNIEXPORT jboolean JNICALL Java_io_github_jiangjinyue2333_actiongrid_readerfixture_ReaderFixtureActivity_nativeAction(JNIEnv *, jclass, jint id, jint action) {
    bool result = false;
    try { result = onPerformAction(fixtureProvider, id, action, nullptr); }
    catch (const DispatchCaptured &) { g_model_move_running.store(false); result = true; }
    __android_log_print(ANDROID_LOG_INFO, kFixtureTag, "NATIVE_ACTION id=%d action=%d result=%d", id, action, result);
    return result;
}
extern "C" JNIEXPORT void JNICALL Java_io_github_jiangjinyue2333_actiongrid_readerfixture_ReaderFixtureActivity_nativeMotion(JNIEnv *, jclass, jint action, jfloat x, jfloat y) {
    FixtureMotion motion{action, x, y}; onLauncherMotion(nullptr, &motion);
}
extern "C" JNIEXPORT jint JNICALL Java_io_github_jiangjinyue2333_actiongrid_readerfixture_ReaderFixtureActivity_nativeFocus(JNIEnv *, jclass) {
    int virtualId = g_virtual_focused_id.load(); return virtualId != -1 ? virtualId : fixtureRealFocus;
}
extern "C" JNIEXPORT void JNICALL Java_io_github_jiangjinyue2333_actiongrid_readerfixture_ReaderFixtureActivity_nativeReset(JNIEnv *, jclass) { fixtureReset(); }
extern "C" JNIEXPORT void JNICALL Java_io_github_jiangjinyue2333_actiongrid_readerfixture_ReaderFixtureActivity_nativeWindowFocus(JNIEnv *, jclass, jboolean focused) { onLauncherWindowFocus(nullptr, focused); }
extern "C" JNIEXPORT void JNICALL Java_io_github_jiangjinyue2333_actiongrid_readerfixture_ReaderFixtureActivity_nativeProbeEvent(JNIEnv *env, jclass, jint type, jstring text) {
    const char *value = env->GetStringUTFChars(text, nullptr);
    if (type == kTypeAnnouncement) sendAnnouncement(-1, value);
    else fixtureEvent(type, -1, value); // Explicit platform-event probe, never a reader simulation.
    env->ReleaseStringUTFChars(text, value);
}
extern "C" JNIEXPORT jstring JNICALL Java_io_github_jiangjinyue2333_actiongrid_readerfixture_ReaderFixtureActivity_nativeSelfCheck(JNIEnv *env, jclass) {
    __android_log_print(ANDROID_LOG_INFO, kFixtureTag, "SELF_CHECK begin");
    jobject savedView = fixtureView; fixtureView = nullptr;
    std::string message = "PASS: production child order, physical last-row dispatch, hotseat exclusion";
    try {
        fixtureReset();
        __android_log_print(ANDROID_LOG_INFO, kFixtureTag, "SELF_CHECK reset complete");
        if (!onPerformAction(fixtureProvider, 11, kLiftAction, nullptr)) throw std::runtime_error("lift rejected");
        __android_log_print(ANDROID_LOG_INFO, kFixtureTag, "SELF_CHECK lift complete");
        NodeResult result = onCreateNode(fixtureProvider, 10);
        if ((result.absent & 1U) || !result.node) throw std::runtime_error("page missing");
        auto *node = static_cast<FixtureNode *>(result.node);
        std::vector<int32_t> ids;
        const size_t childCount = nodeField<size_t>(node, 0x40);
        const auto *childIds = nodeField<const int64_t *>(node, 0x38);
        for (size_t i = 0; i < childCount; ++i) ids.push_back(static_cast<int32_t>(childIds[i] >> 32));
        g_node_drop(node);
        if (ids.size() != 24 || ids[0] != 11 || ids[1] != kVirtualNodeBase + 1 || ids[2] != 12 || ids.back() != kVirtualNodeBase + 23) throw std::runtime_error("production child order mismatch");
        __android_log_print(ANDROID_LOG_INFO, kFixtureTag, "SELF_CHECK child order complete");
        result = onCreateNode(fixtureProvider, 21);
        const std::string dock = nodeString(result.node, 0x138); g_node_drop(result.node);
        if (dock.find("行第") != std::string::npos || dock.find("底栏") == std::string::npos) throw std::runtime_error("hotseat received desktop coordinates");
        fixtureDispatchRow = fixtureDispatchColumn = -1;
        try { onPerformAction(fixtureProvider, kVirtualNodeBase + 23, 16, nullptr); }
        catch (const DispatchCaptured &) { g_model_move_running.store(false); }
        if (fixtureDispatchRow != 5 || fixtureDispatchColumn != 3) throw std::runtime_error("last row dispatch mismatch");
    } catch (const std::exception &error) { message = std::string("FAIL: ") + error.what(); }
    fixtureReset(); fixtureView = savedView;
    __android_log_print(ANDROID_LOG_INFO, kFixtureTag, "SELF_CHECK %s", message.c_str());
    fixtureEvent(2048, -1, "");
    return env->NewStringUTF(message.c_str());
}
