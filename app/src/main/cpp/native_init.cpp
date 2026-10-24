#include "native_abi_profiles.h"
#include "zip_directory.h"
#include "native_diagnostics.h"

#include <android/log.h>
#include <jni.h>
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <dirent.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cctype>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <vector>
#include <utility>
#include <unordered_map>
#include <unordered_set>
#include <thread>
#include <chrono>
#include <limits>
#include <cstdio>
#include <cstdarg>
#include <cmath>
#include <time.h>

namespace {

using namespace actiongrid::abi;

#ifndef ACTIONGRID_VERSION
#define ACTIONGRID_VERSION "development"
#endif

actiongrid::diagnostics::Buffer g_diagnostics;
std::atomic<bool> g_diagnostics_started{false};
std::atomic<bool> g_diagnostics_apis_ready{false};
std::atomic<int> g_diagnostics_node_budget{0};
std::mutex g_diagnostics_wake_mutex;
std::condition_variable g_diagnostics_wake_cv;
bool g_diagnostics_wake = false;
void ensureDiagnosticsWorker();
void logDiagnosticsSnapshot(const char *reason);

void nativeLog(int priority, const char *tag, const char *format, ...) {
    va_list arguments;
    va_start(arguments, format);
    va_list copy;
    va_copy(copy, arguments);
    __android_log_vprint(priority, tag, format, arguments);
    va_end(arguments);
    const int64_t session = g_diagnostics.session();
    if (session != 0) {
        char message[2048];
        vsnprintf(message, sizeof(message), format, copy);
        timespec now{};
        clock_gettime(CLOCK_REALTIME, &now);
        char prefix[96];
        snprintf(prefix, sizeof(prefix), "time=%lld.%03ld pid=%d level=%d ",
                static_cast<long long>(now.tv_sec), now.tv_nsec / 1000000L, getpid(), priority);
        g_diagnostics.append(std::string(prefix) + message, session);
    }
    va_end(copy);
}

void wakeDiagnosticsWorker() {
    {
        std::lock_guard lock(g_diagnostics_wake_mutex);
        g_diagnostics_wake = true;
    }
    g_diagnostics_wake_cv.notify_one();
}

using HookFunType = int (*)(void *function, void *replace, void **backup);
using UnhookFunType = int (*)(void *function);
using NativeOnModuleLoaded = void (*)(const char *name, void *handle);

struct NativeAPIEntries {
    unsigned int version;
    HookFunType hook_func;
    UnhookFunType unhook_func;
};

constexpr uint32_t CAP_LAUNCHER_SEEN = 1U << 0;
constexpr uint32_t CAP_PRIVATE_BUILD_MATCH = 1U << 1;
constexpr uint32_t CAP_PRIVATE_SYMBOLS = 1U << 2;
constexpr uint32_t CAP_PUBLIC_NODE_API = 1U << 3;
constexpr uint32_t CAP_PUBLIC_BUILD_MATCH = 1U << 4;
constexpr uint32_t CAP_NODE_HOOK_INSTALLED = 1U << 5;
constexpr uint32_t CAP_SHORTCUT_APIS = 1U << 6;
constexpr uint32_t CAP_APP_INFO_API = 1U << 7;
constexpr uint32_t CAP_INPUT_API = 1U << 8;
constexpr uint32_t CAP_CUSTOM_ACTION = 1U << 9;
constexpr uint32_t CAP_SELECTION_STATE = 1U << 10;
constexpr uint32_t CAP_FRB_MODEL = 1U << 11;
constexpr uint32_t CAP_PAGE_SCROLL_EVENT = 1U << 12;
constexpr uint32_t CAP_SCENE_ACTIVITY = 1U << 13;
// Movement is a separate capability: safe read-only/direct actions can stay
// available when a launcher revision is structurally unknown.
constexpr uint32_t CAP_MOVE_PROFILE = 1U << 14;

constexpr int32_t kTypeViewScrolled = 4096;
constexpr int32_t kTypeViewClicked = 1;
constexpr int32_t kTypeAnnouncement = 16384;
constexpr int32_t kTypeWindowContentChanged = 2048;
constexpr int32_t kSqliteRow = 100;
constexpr int32_t kSqliteDone = 101;

// HyperOS 4 public ABI build e2ba6d4: verified against libhyper_os_aidl.
constexpr size_t kEventSourceOffset = 0x88;
constexpr size_t kEventWindowOffset = 0xc4;
constexpr size_t kEventTextOffset = 0x38;
constexpr size_t kEventItemCountOffset = 0x98;
constexpr size_t kEventFromIndexOffset = 0x9c;
constexpr size_t kEventToIndexOffset = 0xa0;
constexpr size_t kEventTypeOffset = 0x118;
constexpr uint64_t kFlutterPageAnnouncementSource = 0xffffffff0000000aULL;

constexpr uint32_t kFrbUpdateItemDatabaseBatch = 854U;
constexpr uint32_t kFrbStartLoad = 851U;
// HyperOS 4.01.02.8565 primary dispatcher: single ItemInfo move operation.
constexpr uint32_t kFrbMoveItemInDatabase = 833U;

constexpr char kExpectedLegacyPrivateBuildId[] =
        "401af21eddb88554a568b61ef29fc138";
constexpr char kExpectedLegacyAidlBuildId[] =
        "e05f4ad255225718a2f86dfcc636b871";
constexpr char kExpectedLegacyUiBuildId[] =
        "237f244c1c392a963dce55a2d11068fd";
constexpr char kExpectedPrivateBuildId[] =
        "c3f3fc36f279d12dd70d69f328596457";
constexpr char kExpectedAidlBuildId[] =
        "7de2dd3e204a267ee1ed57c7a54391d2";
// HyperOS 4.0.4 refreshed the Rust Build IDs without changing the verified
// private/AIDL symbol or layout ABI.
constexpr char kExpectedRefreshedPrivateBuildId[] =
        "9768c65ebc28df3b10059df4fae9bd7e";
constexpr char kExpectedRefreshedAidlBuildId[] =
        "0c593771414b630a523e548f33700e83";
constexpr char kExpectedPublicBuildId[] =
        "e2ba6d4fbeb2a03e72cf5f016429d2e4";
constexpr char kExpectedUiBuildId[] =
        "a6c7eeb5fec3383a58b89d43166852b8";
constexpr char kExpectedLauncherDartBuildId[] =
        "4f1bdaed62b77ed2de7d3ebc34e453ca";
constexpr char kExpectedPreviousLauncherDartBuildId[] =
        "4f1bdaedd47beb4ede7d3ebcd292504e";
// The device currently in use ships this libapp revision. Its motion entry
// point and FRB dispatcher payload are verified separately below.
constexpr char kExpectedCurrentLauncherDartBuildId[] =
        "4f1bdaeda5978273de7d3ebca639ae02";
// maybe_trigger_workspace_reload() is an internal, zero-argument entry point.
// Keep every offset behind its libapp_launcher fingerprint and verify its prologue
// before calling it.
constexpr uint32_t kWorkspaceReloadPrologue[] = {
        0xd10343ffU, 0xa90b7bfdU, 0xa90c4ff4U, 0x9102c3fdU};
constexpr char kCreateNodeSymbol[] =
        "_RNvMs3_NtNtCs3DSM27SSrzz_30hyper_os_accessibility_private21accessibility_manager31AccessibilityNodeProvider_traitINtB5_28AccessibilityNodeProvider_TOINtNtNtNtCs9Neji4M1weT_10abi_stable9std_types5boxed7private4RBoxuEE30create_accessibility_node_infoB9_";
constexpr char kProviderPerformActionSymbol[] =
        "_RNvMs3_NtNtCs3DSM27SSrzz_30hyper_os_accessibility_private21accessibility_manager31AccessibilityNodeProvider_traitINtB5_28AccessibilityNodeProvider_TOINtNtNtNtCs9Neji4M1weT_10abi_stable9std_types5boxed7private4RBoxuEE14perform_actionB9_";
constexpr char kProviderFindFocusSymbol[] =
        "_RNvMs3_NtNtCs3DSM27SSrzz_30hyper_os_accessibility_private21accessibility_manager31AccessibilityNodeProvider_traitINtB5_28AccessibilityNodeProvider_TOINtNtNtNtCs9Neji4M1weT_10abi_stable9std_types5boxed7private4RBoxuEE10find_focusB9_";
constexpr char kInteractionPerformActionSymbol[] =
        "_RNvMs_NtCs3DSM27SSrzz_30hyper_os_accessibility_private36accessibility_interaction_controllerNtB4_34AccessibilityInteractionController28perform_accessibility_action";
constexpr char kConnectionPerformActionSymbol[] =
        "_RNvXs5_NtCs3DSM27SSrzz_30hyper_os_accessibility_private24accessibility_controllerNtB5_38AccessibilityInteractionConnectionImplNtNtNtNtNtNtCs2GTLDA1zkMw_13hyper_os_aidl9generated7android4view13accessibility35IAccessibilityInteractionConnection35IAccessibilityInteractionConnection26performAccessibilityAction";
constexpr char kSendEventSymbol[] =
        "_RNvMs_NtCs3DSM27SSrzz_30hyper_os_accessibility_private24accessibility_controllerNtB4_23AccessibilityController32request_send_accessibility_event";

std::atomic<uint32_t> g_capabilities{0};
std::atomic<LauncherAbiProfile> g_launcher_profile{LauncherAbiProfile::kUnknown};
std::atomic<bool> g_launcher_fingerprint_ready{false};
std::atomic<bool> g_launcher_system_compatible{false};
std::atomic<bool> g_current_frb_move_profile{false};
std::atomic<uint32_t> g_node_action_calls{0};
std::atomic<uint32_t> g_custom_action_calls{0};
std::atomic<bool> g_lifted{false};
std::atomic<int32_t> g_lifted_node_id{-1};
std::atomic<int32_t> g_virtual_hovered_id{-1};
std::atomic<int32_t> g_virtual_focused_id{-1};
std::atomic<bool> g_node_hook_attempted{false};
std::atomic<bool> g_private_retry_attempted{false};
std::atomic<bool> g_scene_hook_attempted{false};
std::mutex g_mutex;
const NativeAPIEntries *g_api = nullptr;
using AddActionFn = void (*)(void *node, void *action);
AddActionFn g_original_add_action = nullptr;
using ActionNewFn = void *(*)(int32_t action_id, void *rstring);
using ActionDropFn = void (*)(void *action);
// abi_stable::RString is a 32-byte indirect return value on AArch64.
struct RustRString {
    uintptr_t first;
    uintptr_t second;
    uintptr_t third;
    uintptr_t fourth;
};
using RStringFromUtf16Fn = RustRString (*)(const uint16_t *text, size_t length);
ActionNewFn g_action_new = nullptr;
ActionDropFn g_action_drop = nullptr;
RStringFromUtf16Fn g_rstring_from_utf16 = nullptr;
// The ABI-stable wrapper takes a pointer to its argument envelope as the
// fourth word. Passing the inner Bundle (or nullptr) makes the wrapper
// dereference the wrong address.
using PerformActionFn = bool (*)(void *, int32_t, int32_t, void *);
PerformActionFn g_original_perform_action = nullptr;
using LauncherPerformActionFn = bool (*)(void *, int32_t, int32_t, uintptr_t, uintptr_t);
struct NodeResult { uintptr_t absent; void *node; };
using CreateNodeFn = NodeResult (*)(void *, int32_t);
CreateNodeFn g_create_node = nullptr;
CreateNodeFn g_original_create_node = nullptr;
using FindFocusFn = NodeResult (*)(void *, int32_t);
FindFocusFn g_original_find_focus = nullptr;
using DropFn = void (*)(void *);
DropFn g_node_drop = nullptr;
using NodeNewFn = void *(*)();
using NodeSetStringFn = void (*)(void *, void *);
using NodeSetBoolFn = void (*)(void *, bool);
using NodeSetBoundsFn = void (*)(void *, uint64_t, uint64_t);
using NodeAddChildFn = void (*)(void *, int32_t, int32_t);
using NodeSetSourceFn = void (*)(void *, int32_t, int32_t, int32_t);
using NodeSetParentFn = void (*)(void *, int32_t, int32_t);
using NodeSetTraversalFn = void (*)(void *, int32_t, int32_t);
NodeNewFn g_node_new = nullptr;
NodeSetStringFn g_node_set_content_description = nullptr;
NodeSetStringFn g_node_set_class_name = nullptr;
NodeSetStringFn g_node_set_package_name = nullptr;
NodeSetStringFn g_node_set_view_id = nullptr;
NodeSetBoolFn g_node_set_visible = nullptr;
NodeSetBoolFn g_node_set_enabled = nullptr;
NodeSetBoolFn g_node_set_clickable = nullptr;
NodeSetBoolFn g_node_set_focusable = nullptr;
NodeSetBoundsFn g_node_set_bounds = nullptr;
NodeSetBoundsFn g_node_set_parent_bounds = nullptr;
NodeAddChildFn g_node_add_child = nullptr;
NodeSetSourceFn g_node_set_source = nullptr;
NodeSetParentFn g_node_set_parent = nullptr;
NodeSetTraversalFn g_node_set_traversal_after = nullptr;
NodeSetTraversalFn g_node_set_traversal_before = nullptr;
NodeSetBoolFn g_node_set_important = nullptr;
NodeSetBoolFn g_node_set_accessibility_focused = nullptr;
using EventNewFn = void *(*)(int32_t);
using EventSendFn = void (*)(void *, void *);
using EventIntFn = void (*)(void *, int32_t);
using EventPushTextFn = void (*)(void *, void *);
EventNewFn g_event_new = nullptr;
EventSendFn g_original_send_event = nullptr;
NodeSetSourceFn g_event_set_source = nullptr;
NodeSetStringFn g_event_set_package = nullptr;
EventIntFn g_event_set_change = nullptr;
EventIntFn g_event_set_event_type = nullptr;
EventIntFn g_event_set_item_count = nullptr;
EventIntFn g_event_set_from_index = nullptr;
EventIntFn g_event_set_to_index = nullptr;
EventPushTextFn g_event_push_text = nullptr;
DropFn g_event_drop = nullptr;
std::atomic<void *> g_event_controller{nullptr};
std::mutex g_event_mutex;
std::unordered_set<uint64_t> g_page_source_keys;
std::atomic<int32_t> g_workspace_window_id{-1};
std::atomic<int32_t> g_host_view_id{0};
std::atomic<int32_t> g_window_id{-1};
std::atomic<bool> g_create_hook_attempted{false};
std::atomic<bool> g_find_focus_hook_attempted{false};
using MotionNewFn = void *(*)(int64_t, int64_t, int32_t, float, float, int32_t);
MotionNewFn g_motion_new = nullptr;
using MotionActionFn = int32_t (*)(void *);
using MotionSetActionFn = void (*)(void *, int32_t);
using MotionCloneFn = void *(*)(void *);
using MotionFloatFn = float (*)(void *, int32_t);
MotionActionFn g_motion_action_masked = nullptr;
MotionSetActionFn g_motion_set_action = nullptr;
MotionCloneFn g_motion_clone = nullptr;
DropFn g_motion_drop = nullptr;
MotionFloatFn g_motion_raw_x = nullptr;
MotionFloatFn g_motion_raw_y = nullptr;
using LauncherMotionFn = void (*)(void *, void *);
LauncherMotionFn g_original_launcher_motion = nullptr;
using LauncherFocusFn = void (*)(void *, bool);
using LauncherKeyFn = bool (*)(void *, void *);
using LauncherBackFn = void (*)(void *);
LauncherFocusFn g_original_launcher_focus = nullptr;
LauncherKeyFn g_original_launcher_key = nullptr;
LauncherBackFn g_original_launcher_back = nullptr;
MotionActionFn g_key_code = nullptr;
MotionActionFn g_key_action = nullptr;
std::atomic<bool> g_launcher_lifecycle_hook_attempted{false};
std::atomic<int32_t> g_launcher_window_focused{-1};
std::atomic<bool> g_consume_back_up{false};
std::atomic<int32_t> g_resume_scans{0};
std::atomic<bool> g_focus_callback_active{false};
std::atomic<bool> g_focus_callback_node_observed{false};
std::atomic<bool> g_home_announcement_pending{false};
std::atomic<uint64_t> g_focus_generation{0};
std::atomic<bool> g_launcher_motion_hook_attempted{false};
using InputEventFn = void *(*)(void *);
InputEventFn g_input_event = nullptr;
DropFn g_input_drop = nullptr;
using InjectInputFn = uint64_t (*)(void *, int32_t, int32_t);
InjectInputFn g_inject_input = nullptr;
std::atomic<bool> g_model_move_running{false};
using LauncherCellCountFn = int32_t (*)();
LauncherCellCountFn g_cell_count_x = nullptr;
LauncherCellCountFn g_cell_count_y = nullptr;
std::atomic<int32_t> g_grid_columns{0};
std::atomic<int32_t> g_grid_rows{0};
std::atomic<int32_t> g_visible_screen{0};
std::atomic<int32_t> g_total_pages{0};
std::atomic<bool> g_page_state_known{false};
std::mutex g_page_announcement_mutex;
int32_t g_announced_page = -1;
std::unordered_map<int32_t, int32_t> g_page_item_counts;
std::atomic<int32_t> g_lifted_screen{-1};
std::atomic<int32_t> g_drop_parent_node_id{-1};
std::atomic<int32_t> g_drop_parent_host_id{0};
std::atomic<int32_t> g_drop_parent_window_id{-1};
char g_private_build_id[sizeof(kExpectedPrivateBuildId)] = {};

enum class SurfaceState : int32_t {
    kUnknown = 0,
    kWorkspace = 1,
    kRecents = 2,
    kOther = 3,
};
std::atomic<int32_t> g_surface_state{static_cast<int32_t>(SurfaceState::kUnknown)};
std::mutex g_surface_mutex;
SurfaceState g_surface_candidate = SurfaceState::kUnknown;
int32_t g_surface_candidate_repeats = 0;
std::chrono::steady_clock::time_point g_last_recent_surface;
bool g_has_recent_surface_time = false;
std::atomic<bool> g_surface_poll_started{false};
std::atomic<bool> g_surface_scan_requested{false};
std::mutex g_surface_announcement_mutex;
std::deque<std::string> g_pending_surface_announcements;
std::mutex g_recent_state_mutex;
std::unordered_map<std::string, int32_t> g_recent_card_counts;
std::unordered_map<std::string, int32_t> g_recent_candidate_counts;
std::unordered_map<std::string, int32_t> g_pending_recent_removals;
std::mutex g_recent_swipe_mutex;
bool g_recent_swipe_tracking = false;
float g_recent_swipe_start_y = 0.0f;
std::string g_recent_swipe_name;
int32_t g_recent_candidate_repeats = 0;
bool g_recent_snapshot_initialized = false;
std::atomic<void *> g_last_provider{nullptr};

constexpr char16_t kAppMenuLabel[] = u"应用操作";
constexpr int32_t kLiftAction = 0x6A000001;
constexpr int32_t kCancelAction = 0x6A000002;
constexpr int32_t kMoveBeforeAction = 0x6A000003;
constexpr int32_t kMoveAfterAction = 0x6A000004;
constexpr int32_t kCreateFolderAction = 0x6A000005;
constexpr int32_t kAppInfoAction = 0x6A000006;
constexpr int32_t kUninstallAction = 0x6A000007;
constexpr int32_t kEditFolderAction = 0x6A000009;
constexpr int32_t kRecentAppFlowAction = 0x6A000300;
constexpr int32_t kRecentLockAction = 0x6A000301;
constexpr int32_t kRecentSplitAction = 0x6A000302;
constexpr int32_t kRecentFreeformAction = 0x6A000303;
constexpr int32_t kRecentAppInfoAction = 0x6A000304;
constexpr int32_t kRecentCloseAction = 0x6A000305;
constexpr int32_t kAccessibilityFocusAction = 0x40;
constexpr int32_t kClearAccessibilityFocusAction = 0x80;
constexpr int32_t kDynamicActionBase = 0x6A000100;
constexpr int32_t kDynamicActionCount = 24;
    constexpr int32_t kShortcutMatchDynamic = 1;
    constexpr int32_t kShortcutMatchPinned = 2;
    constexpr int32_t kShortcutMatchManifest = 8;
    constexpr int32_t kShortcutMatchCached = 16;
    constexpr int32_t kShortcutMatchFlags = kShortcutMatchDynamic
            | kShortcutMatchPinned | kShortcutMatchManifest | kShortcutMatchCached;
constexpr char16_t kLiftLabel[] = u"托起";
constexpr char16_t kMultiLiftLabel[] = u"托起已选中的应用";
constexpr char16_t kCancelLabel[] = u"取消托起";
constexpr char16_t kAppInfoLabel[] = u"应用信息";
constexpr char16_t kUninstallLabel[] = u"卸载";
constexpr char16_t kEditFolderLabel[] = u"编辑";
constexpr char16_t kRecentAppFlowLabel[] = u"应用流转";
constexpr char16_t kRecentLockLabel[] = u"锁定任务";
constexpr char16_t kRecentUnlockLabel[] = u"解除锁定";
constexpr char16_t kRecentSplitLabel[] = u"该应用支持分屏";
constexpr char16_t kRecentFreeformLabel[] = u"该应用支持小窗";
constexpr char16_t kRecentAppInfoLabel[] = u"应用详情";
constexpr char16_t kRecentCloseLabel[] = u"关闭";
constexpr char16_t kMoveBeforePrefix[] = u"移到 ";
constexpr char16_t kMoveAfterPrefix[] = u"移到 ";
constexpr char16_t kCreateFolderPrefix[] = u"和 ";
constexpr size_t kNodeBoundsOffset = 0x380;
constexpr int64_t kDesktopContainer = -100;
constexpr int32_t kVirtualNodeBase = 0x6B000001;
constexpr int32_t kVirtualNodeCount = 64;
struct Bounds {
    int32_t left, top, right, bottom;
    int32_t width() const { return right - left; }
    int32_t height() const { return bottom - top; }
    int32_t centerX() const { return (left + right) / 2; }
    int32_t centerY() const { return (top + bottom) / 2; }
};
struct AccessibilityEventSource {
    int32_t host = 0;
    int32_t window = -1;
    int32_t nodeId = -1;
};
struct ObservedNode {
    int32_t id, parent, host, window;
    Bounds bounds;
    std::string resource, label;
    bool selected = false;
    bool icon() const {
        return resource == "com.miui.home:id/icon_icon"
                || resource == "com.miui.home:id/folder";
    }
};

bool nodeBounds(void *provider, int32_t nodeId, Bounds &bounds, std::string *label);
bool isIconNode(void *node);
void readTree(void *provider, int32_t id, std::vector<ObservedNode> &nodes, int depth = 0);
std::mutex g_session_mutex;
ObservedNode g_source{};
std::vector<ObservedNode> g_sources;
std::vector<ObservedNode> g_workspace;
struct Slot {
    Bounds bounds;
    int32_t row;
    int32_t column;
};
std::vector<Slot> g_slots;
std::vector<Slot> g_grid_cells;
int32_t g_grid_screen = -1; // Protected by g_session_mutex.
std::string g_diagnostics_last_grid; // Same session lock as rebuildSlots().
int64_t g_diagnostics_grid_session = 0;

int32_t virtualIdForSlot(const Slot &slot) {
    return kVirtualNodeBase + slot.row * g_grid_columns.load() + slot.column;
}

int32_t slotIndexForVirtualIdLocked(int32_t nodeId) {
    for (size_t index = 0; index < g_slots.size(); ++index) {
        if (virtualIdForSlot(g_slots[index]) == nodeId) return static_cast<int32_t>(index);
    }
    return -1;
}

bool hotseatNodeLocked(const ObservedNode &node) {
    int32_t parent = node.parent;
    for (int depth = 0; parent >= 0 && depth < 20; ++depth) {
        auto found = std::find_if(g_workspace.begin(), g_workspace.end(), [&](const auto &item) {
            return item.id == parent && item.host == node.host;
        });
        if (found == g_workspace.end()) break;
        if (found->resource == "com.miui.home:id/hotseat") return true;
        if (found->parent == parent) break;
        parent = found->parent;
    }
    for (const auto &item : g_workspace) {
        if (item.resource == "com.miui.home:id/hotseat"
                && node.bounds.centerX() >= item.bounds.left && node.bounds.centerX() < item.bounds.right
                && node.bounds.centerY() >= item.bounds.top && node.bounds.centerY() < item.bounds.bottom) return true;
    }
    return false;
}

bool descendsFromLocked(const ObservedNode &item, int32_t ancestor) {
    int32_t parent = item.parent;
    for (int depth = 0; parent >= 0 && depth < 20; ++depth) {
        if (parent == ancestor) return true;
        auto found = std::find_if(g_workspace.begin(), g_workspace.end(), [&](const auto &node) {
            return node.id == parent && node.host == item.host;
        });
        if (found == g_workspace.end() || found->parent == parent) break;
        parent = found->parent;
    }
    return false;
}

struct DynamicShortcut {
    std::string label;
    std::string package;
    std::string id;
    int32_t user = 0;
};
struct FavoritePlacement {
    int64_t id = -1;
    int64_t container = 0;
    int32_t screen = -1;
    int32_t cellX = -1;
    int32_t cellY = -1;
    int32_t spanX = 1;
    int32_t spanY = 1;
    int32_t itemType = 0;
};

std::vector<FavoritePlacement> g_source_placements;

struct PendingPlacement {
    FavoritePlacement item;
    std::string label;
    Bounds bounds;
    bool visible = false;
    size_t observations = 0;
    size_t labelHits = 0;
    ObservedNode lastLabelNode{};
    int32_t lastLabelPage = -1;
};

std::mutex g_placement_mutex;
std::condition_variable g_placement_cv;
std::vector<PendingPlacement> g_pending_placements;
int g_pending_observation_budget = 0;

struct AppTarget {
    std::string package;
    std::string activity;
    std::string spoken;
    int32_t user = 0;
    int64_t favorite = -1;
    FavoritePlacement placement;
    bool shortcutsLoaded = false;
    std::vector<DynamicShortcut> shortcuts;
    bool resolved = false;
    uint64_t snapshotGeneration = 0;
};
std::mutex g_app_mutex;
std::unordered_map<int32_t, AppTarget> g_app_targets;

struct SnapshotFavorite {
    int64_t id = -1;
    std::string title;
    std::string label;
    std::string intent;
    int32_t user = 0;
    FavoritePlacement placement;
};

struct LauncherSnapshot {
    std::vector<SnapshotFavorite> favorites;
    std::unordered_map<int32_t, int32_t> pageCounts;
    uint64_t generation = 0;
    bool ready = false;
};

std::mutex g_snapshot_mutex;
LauncherSnapshot g_snapshot;
std::mutex g_snapshot_request_mutex;
std::condition_variable g_snapshot_request_cv;
bool g_snapshot_refresh_requested = false;
std::chrono::steady_clock::time_point g_snapshot_last_request;
bool g_snapshot_force_requested = false;
std::atomic<bool> g_snapshot_worker_started{false};
std::mutex g_db_path_mutex;
std::string g_cached_db_path;

struct ShortcutLoadRequest {
    int32_t nodeId = -1;
    AppTarget target;
};

std::mutex g_shortcut_queue_mutex;
std::condition_variable g_shortcut_queue_cv;
std::deque<ShortcutLoadRequest> g_shortcut_queue;
std::unordered_set<std::string> g_shortcut_queued;
std::atomic<bool> g_shortcut_worker_started{false};

// HyperOS 4's launcher is a native Rust process. Keep this bridge native too;
// there is no Java VM in that process to attach to. Recent-card actions call
// the launcher's gesture or task APIs directly so no transient menu is shown.
struct alignas(8) RustResult48 { uint8_t data[48]; };
struct RustOptionString {
    uint8_t tag;
    uint8_t padding[7]{};
    RustRString value;
};
struct RustOptionStringView {
    uint8_t tag;
    uint8_t padding[7]{};
    const char *data;
    size_t length;
};
struct RustStringView {
    const char *data;
    size_t length;
};
struct RustVec {
    void *data;
    size_t length;
    size_t capacity;
    void *drop_vtable;
};
struct RustRecentTasksResult {
    uint8_t tag;
    uint8_t padding[7]{};
    RustVec tasks;
};
using LauncherAppsDefaultFn = void *(*)();
using LauncherAppsDropFn = void (*)(void *);
using ShortcutQueryDefaultFn = void *(*)();
using ShortcutQueryDropFn = void (*)(void *);
using ShortcutQuerySetFlagsFn = void (*)(void *, int32_t);
using ShortcutQuerySetPackageFn = void (*)(void *, const RustOptionString *);
using UserHandleNewFn = void *(*)();
using UserHandleSetFn = void (*)(void *, int32_t);
using UserHandleDropFn = void (*)(void *);
using BundleDefaultFn = void *(*)();
using BundleDropFn = void (*)(void *);
using BundleInsertBooleanFn = void (*)(void *, const RustRString *, bool);
using BundleInsertI32Fn = void (*)(void *, const RustRString *, int32_t);
using BundleInsertStringFn = void (*)(void *, const RustRString *, const RustOptionString *);
using ComponentNameNewFn = void *(*)(const char *, size_t, const char *, size_t);
using ComponentNameDropFn = void (*)(void *);
using LauncherAppsGetShortcutsFn = RustResult48 (*)(void *, void *, void *);
using LauncherAppsHasPermissionFn = RustResult48 (*)(void *);
using LauncherAppsStartShortcutFn = RustResult48 (*)(void *, const char *, size_t,
        const char *, size_t, const Bounds *, void *, void *);
using LauncherAppsStartAppInfoFn = RustResult48 (*)(void *, void *, void *, const Bounds *, void *);
using LauncherAppsStartMainActivityFn = RustResult48 (*)(void *, void *, void *, const Bounds *, void *);
using IntentDefaultFn = void *(*)();
using IntentSetActionFn = void (*)(void *, const RustOptionString *);
using IntentSetComponentFn = void (*)(void *, void *);
using IntentSetExtrasFn = void (*)(void *, void *);
using SceneStartActivityFn = RustResult48 (*)(void *, void *, uint64_t, void *, int32_t,
        int32_t, int32_t, void *);
using ShortcutInfoDropFn = void (*)(void *);
using ShortcutInfoGetFlagsFn = int32_t (*)(void *);
using ShortcutInfoGetUserIdFn = int32_t (*)(void *);
using ShortcutInfoGetTextFn = RustOptionStringView (*)(void *);
using TaskGetMaxRecentTasksFn = int32_t (*)();
using TaskGetRecentTasksFn = RustRecentTasksResult (*)(int32_t, int32_t);
using RecentTaskInfoGetTaskIdFn = int32_t (*)(void *);
using RecentTaskInfoGetUserIdFn = int32_t (*)(void *);
using RecentTaskInfoGetRecordNameFn = RustOptionStringView (*)(void *);
using RecentTaskInfoGetActivityFn = void *(*)(void *);
using ComponentNameGetStringFn = RustStringView (*)(void *);
using RecentTaskInfoDropFn = void (*)(void *);
using LauncherStartFreeformFn = bool (*)(void *, int32_t, int32_t, int32_t);
using LauncherLaunchSoscFn = void (*)(int32_t);
using RustDropValueFn = void (*)(void *);
using RuntimeIncStrongFn = void (*)(void *);
using RuntimeGetContextFn = void *(*)(void *);
using ContentResolverNewFn = void *(*)(void *);
using ContentResolverDeleteFn = void (*)(void *);
using ContentResolverCallFn = RustResult48 (*)(void *, const char *, size_t,
        const char *, size_t, const RustOptionStringView *, void *);
struct RustOptionI64 { uint8_t tag; uint8_t padding[7]; int64_t value; };
using BundleGetI64Fn = RustOptionI64 (*)(void *, const char *, size_t);
using BundleInsertI64Fn = void (*)(void *, const RustRString *, int64_t);
using Sqlite3 = struct sqlite3;
using Sqlite3Stmt = struct sqlite3_stmt;
using SqliteOpenV2Fn = int (*)(const char *, Sqlite3 **, int, const char *);
using SqlitePrepareFn = int (*)(Sqlite3 *, const char *, int, Sqlite3Stmt **, const char **);
using SqliteStepFn = int (*)(Sqlite3Stmt *);
using SqliteFinalizeFn = int (*)(Sqlite3Stmt *);
using SqliteColumnTextFn = const unsigned char *(*)(Sqlite3Stmt *, int);
using SqliteColumnIntFn = int (*)(Sqlite3Stmt *, int);
using SqliteColumnInt64Fn = int64_t (*)(Sqlite3Stmt *, int);
using SqliteCloseFn = int (*)(Sqlite3 *);
using SqliteBusyTimeoutFn = int (*)(Sqlite3 *, int);
using SqliteExecFn = int (*)(Sqlite3 *, const char *, int (*)(void *, int, char **, char **),
        void *, char **);
using FrbDispatcherFn = void (*)(uint32_t, int64_t, const uint8_t *, uint32_t, uint32_t);
using LauncherWorkspaceReloadFn = void (*)();
LauncherAppsDefaultFn g_launcher_apps_default = nullptr;
LauncherAppsDropFn g_launcher_apps_drop = nullptr;
ShortcutQueryDefaultFn g_shortcut_query_default = nullptr;
ShortcutQueryDropFn g_shortcut_query_drop = nullptr;
ShortcutQuerySetFlagsFn g_shortcut_query_set_flags = nullptr;
ShortcutQuerySetPackageFn g_shortcut_query_set_package = nullptr;
UserHandleNewFn g_user_handle_new = nullptr;
UserHandleSetFn g_user_handle_set = nullptr;
UserHandleDropFn g_user_handle_drop = nullptr;
BundleDefaultFn g_bundle_default = nullptr;
BundleDropFn g_bundle_drop = nullptr;
BundleInsertBooleanFn g_bundle_insert_boolean = nullptr;
BundleInsertI32Fn g_bundle_insert_i32 = nullptr;
BundleInsertStringFn g_bundle_insert_string = nullptr;
ComponentNameNewFn g_component_name_new = nullptr;
ComponentNameDropFn g_component_name_drop = nullptr;
LauncherAppsGetShortcutsFn g_launcher_apps_get_shortcuts = nullptr;
LauncherAppsHasPermissionFn g_launcher_apps_has_permission = nullptr;
LauncherAppsStartShortcutFn g_launcher_apps_start_shortcut = nullptr;
LauncherAppsStartAppInfoFn g_launcher_apps_start_app_info = nullptr;
LauncherAppsStartMainActivityFn g_launcher_apps_start_main_activity = nullptr;
IntentDefaultFn g_intent_default = nullptr;
IntentSetActionFn g_intent_set_action = nullptr;
IntentSetComponentFn g_intent_set_component = nullptr;
IntentSetExtrasFn g_intent_set_extras = nullptr;
SceneStartActivityFn g_scene_start_activity = nullptr;
SceneStartActivityFn g_original_scene_start_activity = nullptr;
std::atomic<void *> g_scene_instance{nullptr};
ShortcutInfoDropFn g_shortcut_info_drop = nullptr;
ShortcutInfoGetFlagsFn g_shortcut_info_get_flags = nullptr;
ShortcutInfoGetUserIdFn g_shortcut_info_get_user_id = nullptr;
ShortcutInfoGetTextFn g_shortcut_info_get_title = nullptr;
ShortcutInfoGetTextFn g_shortcut_info_get_text = nullptr;
ShortcutInfoGetTextFn g_shortcut_info_get_id = nullptr;
ShortcutInfoGetTextFn g_shortcut_info_get_package = nullptr;
TaskGetMaxRecentTasksFn g_task_get_max_recent_tasks = nullptr;
TaskGetRecentTasksFn g_task_get_recent_tasks = nullptr;
RecentTaskInfoGetTaskIdFn g_recent_task_get_task_id = nullptr;
RecentTaskInfoGetUserIdFn g_recent_task_get_user_id = nullptr;
RecentTaskInfoGetRecordNameFn g_recent_task_get_record_name = nullptr;
RecentTaskInfoGetActivityFn g_recent_task_get_base_activity = nullptr;
ComponentNameGetStringFn g_component_name_get_package = nullptr;
RecentTaskInfoDropFn g_recent_task_drop = nullptr;
LauncherStartFreeformFn g_launcher_start_freeform = nullptr;
LauncherLaunchSoscFn g_launcher_launch_sosc = nullptr;
RustDropValueFn g_rust_vec_drop = nullptr;
RuntimeIncStrongFn g_runtime_inc_strong = nullptr;
RuntimeGetContextFn g_runtime_get_context = nullptr;
ContentResolverNewFn g_content_resolver_new = nullptr;
ContentResolverNewFn g_original_content_resolver_new = nullptr;
ContentResolverDeleteFn g_content_resolver_delete = nullptr;
ContentResolverCallFn g_content_resolver_call = nullptr;
BundleGetI64Fn g_bundle_get_i64 = nullptr;
BundleInsertI64Fn g_bundle_insert_i64 = nullptr;
std::atomic<void *> g_runtime_shared_address{nullptr};
std::atomic<bool> g_db_probe_logged{false};
std::atomic<bool> g_shortcut_error_logged{false};
std::atomic<bool> g_shortcut_probe_logged{false};
std::atomic<bool> g_shortcut_permission_logged{false};
void *findLoadedHandle(const char *libraryBase);
void sendEvent(int32_t nodeId, int32_t type);
void requestSnapshotRefresh(bool force = false);
void queueShortcutLoad(int32_t nodeId, const AppTarget &target);
SqliteOpenV2Fn g_sqlite_open_v2 = nullptr;
SqlitePrepareFn g_sqlite_prepare_v2 = nullptr;
SqliteStepFn g_sqlite_step = nullptr;
SqliteFinalizeFn g_sqlite_finalize = nullptr;
SqliteColumnTextFn g_sqlite_column_text = nullptr;
SqliteColumnIntFn g_sqlite_column_int = nullptr;
SqliteColumnInt64Fn g_sqlite_column_int64 = nullptr;
SqliteCloseFn g_sqlite_close_v2 = nullptr;
SqliteBusyTimeoutFn g_sqlite_busy_timeout = nullptr;
SqliteExecFn g_sqlite_exec = nullptr;
FrbDispatcherFn g_frb_dispatcher = nullptr;
LauncherWorkspaceReloadFn g_trigger_workspace_reload = nullptr;

const char *baseName(const char *path) {
    if (path == nullptr) {
        return "";
    }
    const char *slash = std::strrchr(path, '/');
    return slash == nullptr ? path : slash + 1;
}

void *findLoadedSymbol(const char *libraryBase, const char *symbolName) {
    struct Query {
        const char *library;
        const char *symbol;
        void *address;
    } query{libraryBase, symbolName, nullptr};
    dl_iterate_phdr([](struct dl_phdr_info *info, size_t, void *opaque) {
        auto *query = static_cast<Query *>(opaque);
        if (query->address != nullptr || std::strcmp(baseName(info->dlpi_name), query->library) != 0) {
            return 0;
        }
        const ElfW(Phdr) *dynamic = nullptr;
        for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
            if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) dynamic = &info->dlpi_phdr[i];
        }
        if (dynamic == nullptr) return 0;
        uintptr_t base = static_cast<uintptr_t>(info->dlpi_addr);
        auto *entries = reinterpret_cast<const ElfW(Dyn) *>(base + dynamic->p_vaddr);
        const ElfW(Sym) *symbols = nullptr;
        const char *strings = nullptr;
        uint32_t count = 0;
        for (const ElfW(Dyn) *entry = entries; entry->d_tag != DT_NULL; ++entry) {
            if (entry->d_tag == DT_SYMTAB) symbols = reinterpret_cast<const ElfW(Sym) *>(
                    entry->d_un.d_ptr < base ? base + entry->d_un.d_ptr : entry->d_un.d_ptr);
            else if (entry->d_tag == DT_STRTAB) strings = reinterpret_cast<const char *>(
                    entry->d_un.d_ptr < base ? base + entry->d_un.d_ptr : entry->d_un.d_ptr);
            else if (entry->d_tag == DT_HASH) {
                auto *hash = reinterpret_cast<const uint32_t *>(
                        entry->d_un.d_ptr < base ? base + entry->d_un.d_ptr : entry->d_un.d_ptr);
                count = hash[1];
            }
        }
        if (symbols == nullptr || strings == nullptr || count == 0) return 0;
        for (uint32_t i = 0; i < count; ++i) {
            if (symbols[i].st_name != 0 && std::strcmp(strings + symbols[i].st_name,
                    query->symbol) == 0) {
                query->address = reinterpret_cast<void *>(base + symbols[i].st_value);
                return 1;
            }
        }
        return 0;
    }, &query);
    return query.address;
}

void *findLoadedBase(const char *libraryBase) {
    struct Query {
        const char *library;
        void *base;
    } query{libraryBase, nullptr};
    dl_iterate_phdr([](struct dl_phdr_info *info, size_t, void *opaque) {
        auto *query = static_cast<Query *>(opaque);
        if (query->base == nullptr && std::strcmp(baseName(info->dlpi_name), query->library) == 0) {
            query->base = reinterpret_cast<void *>(info->dlpi_addr);
        }
        return 0;
    }, &query);
    return query.base;
}

void *launcherLibraryBase() {
    void *base = findLoadedBase("libapp_launcher.so");
    if (base != nullptr) return base;
    Dl_info info{};
    return g_frb_dispatcher != nullptr
            && dladdr(reinterpret_cast<void *>(g_frb_dispatcher), &info) != 0
            ? info.dli_fbase : nullptr;
}

bool workspaceReloadPrologueMatches(void *target) {
    if (target == nullptr) return false;
    constexpr size_t kWordCount = sizeof(kWorkspaceReloadPrologue)
            / sizeof(kWorkspaceReloadPrologue[0]);
    uint32_t actual[kWordCount]{};
    std::memcpy(actual, target, sizeof(actual));
    return std::memcmp(actual, kWorkspaceReloadPrologue, sizeof(actual)) == 0;
}

LauncherWorkspaceReloadFn resolveWorkspaceReloadForProfile(
        LauncherAbiProfile profile, const char *buildId) {
    uintptr_t offset = 0;
    switch (profile) {
        case LauncherAbiProfile::kLegacy7653:
            offset = 0x7a1660;
            break;
        case LauncherAbiProfile::kLegacy7722:
            offset = 0x7a2008;
            break;
        case LauncherAbiProfile::kLegacy7748:
            offset = 0x7a35a4;
            break;
        case LauncherAbiProfile::kCurrent8565:
            offset = 0x7aa050;
            break;
        case LauncherAbiProfile::kUnknown:
            return nullptr;
    }
    if (!g_launcher_fingerprint_ready.load(std::memory_order_acquire)) {
        return nullptr;
    }
    void *base = launcherLibraryBase();
    if (base == nullptr) return nullptr;
    void *target = reinterpret_cast<void *>(reinterpret_cast<uintptr_t>(base) + offset);
    if (!workspaceReloadPrologueMatches(target)) {
        nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                "Launcher workspace reload prologue mismatch build=%s offset=0x%zx",
                buildId, static_cast<size_t>(offset));
        return nullptr;
    }
    nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
            "Launcher workspace reload symbol installed target=%p build=%s",
            target, buildId);
    return reinterpret_cast<LauncherWorkspaceReloadFn>(target);
}

void invokeWorkspaceReload() {
    if (g_trigger_workspace_reload == nullptr) {
        nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                "Launcher workspace reload unavailable");
        return;
    }
    g_trigger_workspace_reload();
    nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
            "HyperOS4 workspace reload requested");
}

void resolveGridCounts(LauncherAbiProfile profile) {
    g_cell_count_x = nullptr;
    g_cell_count_y = nullptr;
    if (!g_launcher_fingerprint_ready.load(std::memory_order_acquire)) return;
    uintptr_t offsetX = 0;
    uintptr_t offsetY = 0;
    if (profile == LauncherAbiProfile::kCurrent8565) {
        offsetX = 0x6c9588;
        offsetY = 0x6c9634;
    } else if (profile == LauncherAbiProfile::kLegacy7748) {
        offsetX = 0x6c5a74;
        offsetY = 0x6c5b20;
    } else {
        return;
    }
    auto *base = static_cast<uint8_t *>(launcherLibraryBase());
    if (base == nullptr) return;
    constexpr uint32_t prologue[] = {0xd10083ffU, 0xa9017bfdU, 0x910043fdU};
    if (std::memcmp(base + offsetX, prologue, sizeof(prologue)) != 0
            || std::memcmp(base + offsetY, prologue, sizeof(prologue)) != 0) return;
    g_cell_count_x = reinterpret_cast<LauncherCellCountFn>(base + offsetX);
    g_cell_count_y = reinterpret_cast<LauncherCellCountFn>(base + offsetY);
}

bool readBuildId(const char *moduleName, char *output, size_t outputSize) {
    if (moduleName == nullptr || output == nullptr || outputSize < 2) {
        return false;
    }
    struct Search {
        const char *name;
        char *output;
        size_t outputSize;
        bool found;
    } search{baseName(moduleName), output, outputSize, false};

    dl_iterate_phdr([](struct dl_phdr_info *info, size_t, void *opaque) {
        auto *search = static_cast<Search *>(opaque);
        if (search->found || std::strcmp(baseName(info->dlpi_name), search->name) != 0) {
            return 0;
        }
        for (ElfW(Half) index = 0; index < info->dlpi_phnum; ++index) {
            const ElfW(Phdr) &program = info->dlpi_phdr[index];
            if (program.p_type != PT_NOTE) {
                continue;
            }
            const auto *cursor = reinterpret_cast<const uint8_t *>(info->dlpi_addr
                    + program.p_vaddr);
            const auto *end = cursor + program.p_memsz;
            while (cursor + sizeof(ElfW(Nhdr)) <= end) {
                const auto *note = reinterpret_cast<const ElfW(Nhdr) *>(cursor);
                cursor += sizeof(ElfW(Nhdr));
                const auto *name = cursor;
                cursor += (note->n_namesz + 3U) & ~3U;
                const auto *descriptor = cursor;
                cursor += (note->n_descsz + 3U) & ~3U;
                if (cursor > end || note->n_type != NT_GNU_BUILD_ID
                        || note->n_namesz < 3 || std::memcmp(name, "GNU", 3) != 0) {
                    continue;
                }
                if (note->n_descsz * 2U + 1U > search->outputSize) {
                    return 0;
                }
                static constexpr char hex[] = "0123456789abcdef";
                for (uint32_t byte = 0; byte < note->n_descsz; ++byte) {
                    search->output[byte * 2U] = hex[descriptor[byte] >> 4U];
                    search->output[byte * 2U + 1U] = hex[descriptor[byte] & 0x0fU];
                }
                search->output[note->n_descsz * 2U] = '\0';
                search->found = true;
                return 0;
            }
        }
        return 0;
    }, &search);
    return search.found;
}

bool appendElfRange(std::vector<std::pair<uint64_t, uint64_t>> &ranges,
        uint64_t start, uint64_t size, size_t fileSize) {
    if (size == 0) return true;
    if (start > static_cast<uint64_t>(fileSize)
            || size > static_cast<uint64_t>(fileSize) - start) {
        return false;
    }
    ranges.emplace_back(start, start + size);
    return true;
}

bool fingerprintElfBytes(const std::vector<uint8_t> &bytes, ElfFingerprint &output) {
    output = {};
    if (bytes.size() < sizeof(ElfW(Ehdr))) return false;

    ElfW(Ehdr) header{};
    std::memcpy(&header, bytes.data(), sizeof(header));
    if (header.e_ident[EI_MAG0] != ELFMAG0
            || header.e_ident[EI_MAG1] != ELFMAG1
            || header.e_ident[EI_MAG2] != ELFMAG2
            || header.e_ident[EI_MAG3] != ELFMAG3
            || header.e_ident[EI_CLASS] != ELFCLASS64
            || header.e_ident[EI_DATA] != ELFDATA2LSB
            || header.e_phentsize != sizeof(ElfW(Phdr))) {
        return false;
    }
    const uint64_t phdrBytes = static_cast<uint64_t>(header.e_phnum)
            * static_cast<uint64_t>(header.e_phentsize);
    if (header.e_phoff > bytes.size() || phdrBytes > bytes.size() - header.e_phoff) {
        return false;
    }

    std::vector<ElfW(Phdr)> programs(header.e_phnum);
    for (size_t index = 0; index < programs.size(); ++index) {
        std::memcpy(&programs[index], bytes.data() + header.e_phoff
                + index * sizeof(ElfW(Phdr)), sizeof(ElfW(Phdr)));
    }
    std::vector<std::pair<uint64_t, uint64_t>> excluded;
    for (const ElfW(Phdr) &program : programs) {
        if (program.p_type == PT_NOTE
                && !appendElfRange(excluded, program.p_offset, program.p_filesz,
                        bytes.size())) {
            return false;
        }
    }

    // Some toolchains put .comment/.note.* inside a load segment. Exclude
    // those sections as well; Build ID and compiler metadata are not ABI.
    if (header.e_shoff != 0 && header.e_shnum != 0
            && header.e_shentsize == sizeof(ElfW(Shdr))) {
        const uint64_t sectionBytes = static_cast<uint64_t>(header.e_shnum)
                * static_cast<uint64_t>(header.e_shentsize);
        if (header.e_shoff > bytes.size() || sectionBytes > bytes.size() - header.e_shoff
                || header.e_shstrndx >= header.e_shnum) {
            return false;
        }
        std::vector<ElfW(Shdr)> sections(header.e_shnum);
        for (size_t index = 0; index < sections.size(); ++index) {
            std::memcpy(&sections[index], bytes.data() + header.e_shoff
                    + index * sizeof(ElfW(Shdr)), sizeof(ElfW(Shdr)));
        }
        const ElfW(Shdr) &namesSection = sections[header.e_shstrndx];
        if (namesSection.sh_offset > bytes.size()
                || namesSection.sh_size > bytes.size() - namesSection.sh_offset) {
            return false;
        }
        const char *names = reinterpret_cast<const char *>(
                bytes.data() + namesSection.sh_offset);
        const size_t namesSize = static_cast<size_t>(namesSection.sh_size);
        for (const ElfW(Shdr) &section : sections) {
            if (section.sh_name >= namesSize) return false;
            const char *name = names + section.sh_name;
            const size_t remaining = namesSize - section.sh_name;
            if (std::memchr(name, '\0', remaining) == nullptr) return false;
            if ((std::strncmp(name, ".note.", 6) == 0
                    || std::strcmp(name, ".comment") == 0)
                    && !appendElfRange(excluded, section.sh_offset, section.sh_size,
                            bytes.size())) {
                return false;
            }
        }
    }

    uint64_t first = 14695981039346656037ULL;
    uint64_t second = 1099511628211ULL;
    auto updateByte = [&first, &second](uint8_t value) {
        first = (first ^ value) * 1099511628211ULL;
        second = (second ^ static_cast<uint8_t>(value ^ 0xa5U)) * 1099511628211ULL;
    };
    auto updateWord = [&updateByte](uint64_t value) {
        for (unsigned int shift = 0; shift < 64; shift += 8U) {
            updateByte(static_cast<uint8_t>((value >> shift) & 0xffU));
        }
    };
    updateByte('A');
    updateByte('G');
    updateByte('E');
    updateByte('L');
    updateByte('F');
    updateByte(1U);
    updateWord(static_cast<uint64_t>(header.e_type));
    updateWord(static_cast<uint64_t>(header.e_machine));

    uint64_t loadCount = 0;
    for (const ElfW(Phdr) &program : programs) {
        if (program.p_type != PT_LOAD) continue;
        if (program.p_offset > bytes.size()
                || program.p_filesz > bytes.size() - program.p_offset) {
            return false;
        }
        updateByte(0x4cU);
        updateWord(program.p_offset);
        updateWord(program.p_filesz);
        updateWord(program.p_memsz);
        updateWord(program.p_flags);
        updateWord(program.p_align);
        const uint64_t start = program.p_offset;
        const uint64_t end = start + program.p_filesz;
        uint64_t position = start;
        while (position < end) {
            uint64_t next = end;
            bool skip = false;
            for (const auto &range : excluded) {
                if (position >= range.first && position < range.second) {
                    skip = true;
                    next = std::min(next, range.second);
                } else if (range.first > position && range.first < next) {
                    next = range.first;
                }
            }
            if (next <= position) return false;
            if (!skip) {
                for (uint64_t index = position; index < next; ++index) {
                    updateByte(bytes[static_cast<size_t>(index)]);
                }
            }
            position = next;
        }
        ++loadCount;
    }
    if (loadCount == 0) return false;
    updateWord(loadCount);
    output = {first, second, true};
    return true;
}

uint16_t zipU16(const uint8_t *bytes) {
    return static_cast<uint16_t>(bytes[0])
            | static_cast<uint16_t>(bytes[1]) << 8U;
}

uint32_t zipU32(const uint8_t *bytes) {
    return static_cast<uint32_t>(bytes[0])
            | static_cast<uint32_t>(bytes[1]) << 8U
            | static_cast<uint32_t>(bytes[2]) << 16U
            | static_cast<uint32_t>(bytes[3]) << 24U;
}

bool readZipStoredEntry(const char *zipPath, const char *entryName,
        std::vector<uint8_t> &output) {
    output.clear();
    FILE *file = std::fopen(zipPath, "rb");
    if (file == nullptr || std::fseek(file, 0, SEEK_END) != 0) {
        if (file != nullptr) std::fclose(file);
        return false;
    }
    long length = std::ftell(file);
    if (length < 22) {
        std::fclose(file);
        return false;
    }
    const size_t tailSize = std::min<size_t>(static_cast<size_t>(length), 0xffffU + 22U);
    if (std::fseek(file, length - static_cast<long>(tailSize), SEEK_SET) != 0) {
        std::fclose(file);
        return false;
    }
    std::vector<uint8_t> tail(tailSize);
    if (std::fread(tail.data(), 1, tail.size(), file) != tail.size()) {
        std::fclose(file);
        return false;
    }
    const size_t eocd = actiongrid::zip::endRecordOffset(tail.data(), tail.size());
    if (eocd == tail.size()) {
        std::fclose(file);
        return false;
    }
    const uint32_t centralSize = zipU32(tail.data() + eocd + 12U);
    const uint32_t centralOffset = zipU32(tail.data() + eocd + 16U);
    if (static_cast<uint64_t>(centralOffset) + centralSize
            > static_cast<uint64_t>(length)
            || std::fseek(file, centralOffset, SEEK_SET) != 0) {
        std::fclose(file);
        return false;
    }
    std::vector<uint8_t> central(centralSize);
    if (std::fread(central.data(), 1, central.size(), file) != central.size()) {
        std::fclose(file);
        return false;
    }
    const size_t wantedLength = std::strlen(entryName);
    size_t cursor = 0;
    while (cursor + 46U <= central.size()
            && zipU32(central.data() + cursor) == 0x02014b50U) {
        const uint16_t method = zipU16(central.data() + cursor + 10U);
        const uint32_t compressedSize = zipU32(central.data() + cursor + 20U);
        const uint32_t uncompressedSize = zipU32(central.data() + cursor + 24U);
        const uint16_t nameLength = zipU16(central.data() + cursor + 28U);
        const uint16_t extraLength = zipU16(central.data() + cursor + 30U);
        const uint16_t commentLength = zipU16(central.data() + cursor + 32U);
        const uint32_t localOffset = zipU32(central.data() + cursor + 42U);
        const size_t recordSize = 46U + nameLength + extraLength + commentLength;
        if (cursor + recordSize > central.size()) break;
        const char *name = reinterpret_cast<const char *>(
                central.data() + cursor + 46U);
        if (nameLength == wantedLength
                && std::memcmp(name, entryName, wantedLength) == 0) {
            if (method != 0 || compressedSize != uncompressedSize
                    || std::fseek(file, localOffset, SEEK_SET) != 0) {
                std::fclose(file);
                return false;
            }
            uint8_t localHeader[30]{};
            if (std::fread(localHeader, 1, sizeof(localHeader), file) != sizeof(localHeader)
                    || zipU32(localHeader) != 0x04034b50U) {
                std::fclose(file);
                return false;
            }
            const uint16_t localNameLength = zipU16(localHeader + 26U);
            const uint16_t localExtraLength = zipU16(localHeader + 28U);
            const uint64_t dataOffset = static_cast<uint64_t>(localOffset) + 30U
                    + localNameLength + localExtraLength;
            if (dataOffset + compressedSize > static_cast<uint64_t>(length)
                    || std::fseek(file, static_cast<long>(dataOffset), SEEK_SET) != 0) {
                std::fclose(file);
                return false;
            }
            output.resize(compressedSize);
            const bool read = std::fread(output.data(), 1, output.size(), file)
                    == output.size();
            std::fclose(file);
            return read;
        }
        cursor += recordSize;
    }
    std::fclose(file);
    return false;
}

bool readElfFingerprint(const char *path, ElfFingerprint &output) {
    output = {};
    if (path == nullptr || path[0] == '\0') return false;
    const char *zipMarker = std::strstr(path, "!/");
    std::vector<uint8_t> bytes;
    if (zipMarker != nullptr) {
        const std::string zipPath(path, static_cast<size_t>(zipMarker - path));
        if (!readZipStoredEntry(zipPath.c_str(), zipMarker + 2, bytes)) return false;
        return fingerprintElfBytes(bytes, output);
    }
    FILE *file = std::fopen(path, "rb");
    if (file == nullptr || std::fseek(file, 0, SEEK_END) != 0) {
        if (file != nullptr) std::fclose(file);
        return false;
    }
    long length = std::ftell(file);
    if (length <= 0 || std::fseek(file, 0, SEEK_SET) != 0) {
        std::fclose(file);
        return false;
    }
    bytes.resize(static_cast<size_t>(length));
    const bool read = std::fread(bytes.data(), 1, bytes.size(), file) == bytes.size();
    std::fclose(file);
    return read && fingerprintElfBytes(bytes, output);
}

bool readModuleFingerprint(const char *moduleName, ElfFingerprint &output) {
    output = {};
    if (moduleName == nullptr) return false;
    struct Search {
        const char *name;
        ElfFingerprint *output;
        bool found;
    } search{baseName(moduleName), &output, false};
    dl_iterate_phdr([](struct dl_phdr_info *info, size_t, void *opaque) {
        auto *search = static_cast<Search *>(opaque);
        if (search->found || info->dlpi_name == nullptr
                || std::strcmp(baseName(info->dlpi_name), search->name) != 0) {
            return 0;
        }
        search->found = readElfFingerprint(info->dlpi_name, *search->output);
        return search->found ? 1 : 0;
    }, &search);
    return search.found;
}

void resolvePrivate(const char *name, void *handle);
void onAddAction(void *node, void *action);
void injectIconActions(void *node);
void injectRecentActions(void *node);
bool lockRecentTask(void *provider, int32_t nodeId, const Bounds &bounds);
bool resolveRecentTaskId(void *provider, int32_t nodeId, const std::string &label,
        int32_t &taskId, AppTarget &target);
bool performDirectRecentAction(int32_t actionId, const AppTarget &target, int32_t taskId);
bool startRecentAppFlow(int32_t taskId, const AppTarget &target);
bool closeRecentTask(void *provider, int32_t nodeId);
NodeResult onCreateNode(void *provider, int32_t nodeId);
bool isVirtualNode(int32_t nodeId);
std::string virtualNodeDescription(int32_t nodeId);
void onSendEvent(void *controller, void *event);
void sendEvent(int32_t nodeId, int32_t type);
void sendEvent(const AccessibilityEventSource &source, int32_t type);
AccessibilityEventSource captureEventSource(int32_t nodeId);
AccessibilityEventSource captureDropParentEventSource(int32_t fallbackNodeId);
bool sendAnnouncement(int32_t nodeId, const std::string &text);
bool sendAnnouncementWithSource(int32_t host, int32_t window, int32_t virtualId,
        const std::string &text);
bool announceReliably(int32_t host, int32_t window, int32_t virtualId,
        const std::string &text);
void queueSurfaceAnnouncement(const std::string &text);
void flushSurfaceAnnouncements();
bool isRecentTaskLabel(const std::string &label);
std::string recentCardName(const std::string &label);
void observeSurfaceNode(void *provider, int32_t nodeId, void *node);
void observeSurfaceSample(void *provider, SurfaceState observed,
        const std::vector<ObservedNode> &nodes);
void observeSurfaceTree(void *provider, const std::vector<ObservedNode> &nodes);
void refreshLiftSessionForPage(void *provider);
void handleRecentSwipeMotion(int32_t action, float x, float y);


template<typename T> T nodeField(const void *node, size_t offset) {
    T value{};
    std::memcpy(&value, static_cast<const uint8_t *>(node) + offset, sizeof(value));
    return value;
}

bool labelMatches(const std::string &spoken, const std::string &title) {
    if (spoken.empty() || title.empty()) return false;
    if (spoken == title) return true;
    if (spoken.size() <= title.size() || spoken.compare(0, title.size(), title) != 0) return false;
    unsigned char next = static_cast<unsigned char>(spoken[title.size()]);
    return next <= 0x20 || next == ',' || next == 0xe3 || next == 0xef;
}

bool parsePageIndicator(const std::string &description, int32_t &page, int32_t *totalPages);

void observePendingPlacement(const ObservedNode &node, int32_t page = -1) {
    if (!node.icon()) return;
    std::lock_guard lock(g_placement_mutex);
    if (g_pending_placements.empty()) return;
    if (page < 0) page = g_visible_screen.load(std::memory_order_acquire) + 1;
    bool changed = false;
    for (auto &pending : g_pending_placements) {
        ++pending.observations;
        const bool labelOk = labelMatches(node.label, pending.label);
        const bool pageOk = page == pending.item.screen;
        const bool boundsOk = pending.bounds.width() > 0 && pending.bounds.height() > 0
                && node.bounds.centerX() >= pending.bounds.left
                && node.bounds.centerX() < pending.bounds.right
                && node.bounds.centerY() >= pending.bounds.top
                && node.bounds.centerY() < pending.bounds.bottom;
        if (labelOk) {
            ++pending.labelHits;
            pending.lastLabelNode = node;
            pending.lastLabelPage = page;
        }
        if (!pending.visible && (labelOk || boundsOk)
                && g_pending_observation_budget > 0 && g_diagnostics.session() != 0) {
            --g_pending_observation_budget;
            nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                    "move candidate id=%lld node=%d label=%s page=%d bounds=%d,%d,%d,%d label_ok=%d page_ok=%d bounds_ok=%d",
                    static_cast<long long>(pending.item.id), node.id, node.label.c_str(), page,
                    node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom,
                    labelOk ? 1 : 0, pageOk ? 1 : 0, boundsOk ? 1 : 0);
        }
        if (pending.visible || !labelOk || !pageOk || !boundsOk) continue;
        pending.visible = true;
        changed = true;
    }
    if (changed && std::all_of(g_pending_placements.begin(), g_pending_placements.end(),
            [](const auto &pending) { return pending.visible; })) {
        g_placement_cv.notify_all();
    }
}

void observePendingPlacements(const std::vector<ObservedNode> &nodes) {
    int32_t page = -1;
    for (const auto &node : nodes) {
        if (parsePageIndicator(node.label, page, nullptr)) break;
    }
    for (const auto &node : nodes) observePendingPlacement(node, page);
}

void beginPendingPlacement(const std::vector<PendingPlacement> &expected) {
    std::lock_guard lock(g_placement_mutex);
    g_pending_placements = expected;
    g_pending_observation_budget = 12;
    if (g_diagnostics.session() == 0) return;
    for (const auto &target : expected) {
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "move visibility target id=%lld label=%s page=%d cell=%d,%d bounds=%d,%d,%d,%d",
                static_cast<long long>(target.item.id), target.label.c_str(), target.item.screen,
                target.item.cellX, target.item.cellY,
                target.bounds.left, target.bounds.top, target.bounds.right, target.bounds.bottom);
    }
}

bool waitForPendingPlacementVisibility() {
    std::unique_lock lock(g_placement_mutex);
    const bool visible = g_placement_cv.wait_for(lock, std::chrono::milliseconds(4'500), [] {
        return g_pending_placements.empty() || std::all_of(
                g_pending_placements.begin(), g_pending_placements.end(),
                [](const auto &pending) { return pending.visible; });
    });
    return visible && !g_pending_placements.empty()
            && std::all_of(g_pending_placements.begin(), g_pending_placements.end(),
                    [](const auto &pending) { return pending.visible; });
}

void endPendingPlacement() {
    std::lock_guard lock(g_placement_mutex);
    if (g_diagnostics.session() != 0) {
        for (const auto &pending : g_pending_placements) {
            const auto &last = pending.lastLabelNode;
            nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                    "move visibility result id=%lld visible=%d observations=%zu label_hits=%zu last_node=%d last_page=%d last_bounds=%d,%d,%d,%d",
                    static_cast<long long>(pending.item.id), pending.visible ? 1 : 0,
                    pending.observations, pending.labelHits, last.id, pending.lastLabelPage,
                    last.bounds.left, last.bounds.top, last.bounds.right, last.bounds.bottom);
        }
    }
    g_pending_placements.clear();
    g_placement_cv.notify_all();
}

std::u16string utf16FromUtf8(const std::string &value) {
    std::u16string result;
    for (size_t i = 0; i < value.size();) {
        uint32_t code = static_cast<unsigned char>(value[i++]);
        if (code < 0x80) {
        } else if ((code & 0xe0) == 0xc0 && i < value.size()) {
            code = ((code & 0x1f) << 6) | (static_cast<unsigned char>(value[i++]) & 0x3f);
        } else if ((code & 0xf0) == 0xe0 && i + 1 < value.size()) {
            unsigned char second = static_cast<unsigned char>(value[i++]);
            unsigned char third = static_cast<unsigned char>(value[i++]);
            code = ((code & 0x0f) << 12)
                    | ((second & 0x3f) << 6) | (third & 0x3f);
        } else if ((code & 0xf8) == 0xf0 && i + 2 < value.size()) {
            unsigned char second = static_cast<unsigned char>(value[i++]);
            unsigned char third = static_cast<unsigned char>(value[i++]);
            unsigned char fourth = static_cast<unsigned char>(value[i++]);
            code = ((code & 0x07) << 18)
                    | ((second & 0x3f) << 12) | ((third & 0x3f) << 6)
                    | (fourth & 0x3f);
        } else {
            continue;
        }
        if (code <= 0xffff) result.push_back(static_cast<char16_t>(code));
        else {
            code -= 0x10000;
            result.push_back(static_cast<char16_t>(0xd800U | (code >> 10)));
            result.push_back(static_cast<char16_t>(0xdc00U | (code & 0x3ffU)));
        }
    }
    return result;
}

void dropRustVec(RustVec &value) {
    if (value.drop_vtable != nullptr) {
        auto drop = nodeField<RustDropValueFn>(value.drop_vtable, 0x18);
        if (drop != nullptr) drop(&value);
    }
}

struct DiagnosticsReply {
    bool valid = false;
    int64_t session = 0;
    int64_t ack = 0;
    int32_t error = 0;
};

// Immutable ABI function snapshot: the loader can continue resolving other APIs
// while the worker communicates with the provider on its own thread.
struct DiagnosticsTransport {
    void *runtime;
    ContentResolverNewFn create;
    ContentResolverDeleteFn destroy;
    ContentResolverCallFn call;
    BundleDefaultFn bundleNew;
    BundleDropFn bundleDrop;
    BundleGetI64Fn getI64;
    BundleInsertI64Fn putI64;
    BundleInsertStringFn putString;
    RStringFromUtf16Fn stringNew;

    DiagnosticsReply decode(RustResult48 &result) const {
        DiagnosticsReply reply;
        void *bundle = nullptr;
        if ((result.data[0] & 1U) == 0) {
            if ((result.data[8] & 1U) == 0) bundle = nodeField<void *>(result.data, 16);
        } else if (nodeField<void *>(result.data, 16) != nullptr) {
            reply.error = nodeField<int32_t>(result.data, 8);
            RustVec message = nodeField<RustVec>(result.data, 16);
            dropRustVec(message);
        } else {
            // Verified alternate Option<Bundle> arm in both 7748 and 8565.
            if ((result.data[24] & 1U) == 0) bundle = nodeField<void *>(result.data, 32);
        }
        if (bundle != nullptr) {
            const RustOptionI64 session = getI64(bundle, "session", 7);
            const RustOptionI64 ack = getI64(bundle, "ack", 3);
            reply.valid = (session.tag & 1U) == 0 && session.value >= 0;
            if (reply.valid) reply.session = session.value;
            if ((ack.tag & 1U) == 0) reply.ack = ack.value;
            bundleDrop(bundle);
        }
        return reply;
    }

    DiagnosticsReply request(const actiongrid::diagnostics::Buffer::Batch *batch = nullptr) const {
        void *resolver = create(runtime);
        if (resolver == nullptr) return {};
        void *extras = nullptr;
        if (batch != nullptr) {
            extras = bundleNew();
            if (extras == nullptr) {
                destroy(resolver);
                return {};
            }
            RustRString sessionKey = stringNew(reinterpret_cast<const uint16_t *>(u"session"), 7);
            putI64(extras, &sessionKey, batch->session);
            RustRString messageKey = stringNew(reinterpret_cast<const uint16_t *>(u"message"), 7);
            const std::u16string text = utf16FromUtf8(batch->text);
            RustOptionString message{0, {}, stringNew(
                    reinterpret_cast<const uint16_t *>(text.data()), text.size())};
            // Both insert methods consume their strings. The call only borrows extras.
            putString(extras, &messageKey, &message);
        }
        constexpr char authority[] = "io.github.jiangjinyue2333.actiongrid.diagnostics";
        const char *method = batch == nullptr ? "state" : "append_native";
        RustOptionStringView argument{1, {}, nullptr, 0};
        RustResult48 result = call(resolver, authority, sizeof(authority) - 1,
                method, strlen(method), &argument, extras);
        if (extras != nullptr) bundleDrop(extras);
        destroy(resolver);
        return decode(result);
    }
};

DiagnosticsReply sendDiagnosticsBatch(const DiagnosticsTransport &transport,
        const actiongrid::diagnostics::Buffer::Batch &batch) {
    const DiagnosticsReply reply = transport.request(&batch);
    if (reply.valid) {
        g_diagnostics.setSession(reply.session);
        if (reply.session == batch.session && reply.ack == batch.session) {
            g_diagnostics.acknowledge(batch);
        }
    }
    return reply;
}

void diagnosticsWorker(DiagnosticsTransport transport) {
    uint32_t failures = 0;
    int32_t lastError = 0;
    int64_t describedSession = 0;
    auto lastSnapshot = std::chrono::steady_clock::now();
    for (;;) {
        int delayMs = 30000;
        const DiagnosticsReply state = transport.request();
        if (state.valid) {
            g_diagnostics.setSession(state.session);
            const bool changed = describedSession != state.session;
            if (state.session != 0) {
                const auto now = std::chrono::steady_clock::now();
                if (changed || failures != 0 || now - lastSnapshot >= std::chrono::seconds(30)) {
                    logDiagnosticsSnapshot(changed ? "logging enabled" : failures ? "reconnected" : "heartbeat");
                    if (failures != 0) nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                            "diagnostics recovered failed_calls=%u last_error=%d", failures, lastError);
                    lastSnapshot = now;
                    describedSession = state.session;
                }
                const auto batch = g_diagnostics.batch();
                if (!batch.text.empty()) {
                    const DiagnosticsReply written = sendDiagnosticsBatch(transport, batch);
                    if (written.valid) {
                        if (written.session == batch.session && written.ack == batch.session) {
                            failures = 0;
                        } else if (written.session == batch.session) {
                            ++failures; // Provider reachable, but storage did not accept the batch.
                        } else {
                            failures = 0;
                        }
                    } else {
                        ++failures;
                        lastError = written.error;
                    }
                } else {
                    failures = 0;
                }
                delayMs = g_diagnostics.batch().text.empty() ? 2000 : 500;
            } else {
                failures = 0;
                describedSession = 0;
            }
        } else {
            ++failures;
            lastError = state.error;
        }
        if (failures != 0) delayMs = failures < 3 ? 5000 : 30000;
        std::unique_lock lock(g_diagnostics_wake_mutex);
        // Focus changes can shorten idle/backoff waits, never cause a tight Binder loop.
        g_diagnostics_wake_cv.wait_until(lock, std::chrono::steady_clock::now()
                + std::chrono::milliseconds(500), [] { return false; });
        g_diagnostics_wake_cv.wait_for(lock, std::chrono::milliseconds(delayMs - 500), [] {
            return g_diagnostics_wake;
        });
        g_diagnostics_wake = false;
    }
}

void ensureDiagnosticsWorker() {
    if (!g_diagnostics_apis_ready.load(std::memory_order_acquire)
            || !g_launcher_fingerprint_ready.load(std::memory_order_acquire)
            || !g_launcher_system_compatible.load(std::memory_order_acquire)) return;
    const auto profile = g_launcher_profile.load();
    if (profile != LauncherAbiProfile::kLegacy7748 && profile != LauncherAbiProfile::kCurrent8565) return;
    void *runtime = g_runtime_shared_address.load(std::memory_order_acquire);
    if (runtime == nullptr || g_original_content_resolver_new == nullptr
            || g_content_resolver_delete == nullptr || g_content_resolver_call == nullptr
            || g_bundle_default == nullptr || g_bundle_drop == nullptr || g_bundle_get_i64 == nullptr
            || g_bundle_insert_i64 == nullptr || g_bundle_insert_string == nullptr
            || g_rstring_from_utf16 == nullptr || g_diagnostics_started.exchange(true)) return;
    std::thread(diagnosticsWorker, DiagnosticsTransport{runtime, g_original_content_resolver_new,
            g_content_resolver_delete, g_content_resolver_call, g_bundle_default, g_bundle_drop,
            g_bundle_get_i64, g_bundle_insert_i64, g_bundle_insert_string, g_rstring_from_utf16}).detach();
}

bool rustResultOk(RustResult48 &result, const char *operation) {
    if ((result.data[0] & 1U) == 0) return true;
    RustVec message = nodeField<RustVec>(result.data, 16);
    if (!g_shortcut_error_logged.exchange(true)) nativeLog(ANDROID_LOG_WARN,
            "ActionGridNative", "%s failed code=%d", operation,
            nodeField<int32_t>(result.data, 8));
    dropRustVec(message);
    return false;
}

bool sceneActivityAllowed() {
    return (g_capabilities.load(std::memory_order_acquire) & CAP_SCENE_ACTIVITY) != 0;
}

RustResult48 onSceneStartActivity(void *scene, void *intent, uint64_t requestCode,
        void *options, int32_t flags, int32_t userId, int32_t taskId, void *remoteAnimation) {
    if (scene != nullptr) {
        void *previous = g_scene_instance.exchange(scene, std::memory_order_acq_rel);
        g_capabilities.fetch_or(CAP_SCENE_ACTIVITY, std::memory_order_release);
        if (previous != scene) {
            nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                    "Scene instance captured=%p", scene);
        }
    }
    return g_original_scene_start_activity(scene, intent, requestCode, options, flags,
            userId, taskId, remoteAnimation);
}

std::string copyRustStringView(RustOptionStringView value) {
    return (value.tag & 1U) == 0 && value.data != nullptr && value.length <= 4096
            ? std::string(value.data, value.length) : std::string();
}

void *newUser(int32_t id) {
    if (g_user_handle_new == nullptr || g_user_handle_set == nullptr) return nullptr;
    void *user = g_user_handle_new();
    if (user != nullptr) g_user_handle_set(user, id);
    return user;
}

void *onContentResolverNew(void *runtimeShared) {
    // content_resolver_new takes &Shared<Runtime>; retain the pointee once.
    if (runtimeShared != nullptr && g_runtime_inc_strong != nullptr
            && g_runtime_shared_address.load(std::memory_order_acquire) == nullptr) {
        static std::once_flag once;
        std::call_once(once, [runtimeShared] {
            static void *retainedRuntime = *static_cast<void **>(runtimeShared);
            if (retainedRuntime != nullptr) {
                g_runtime_inc_strong(retainedRuntime);
                g_runtime_shared_address.store(&retainedRuntime, std::memory_order_release);
                nativeLog(ANDROID_LOG_INFO, "ActionGridNative", "Native Runtime captured");
            }
        });
    }
    ensureDiagnosticsWorker();
    return g_original_content_resolver_new(runtimeShared);
}

std::string launcherDatabasePath() {
    {
        std::lock_guard lock(g_db_path_mutex);
        if (!g_cached_db_path.empty() && access(g_cached_db_path.c_str(), R_OK) == 0) {
            return g_cached_db_path;
        }
    }
    // Follow the database the launcher already opened, including its user/grid.
    DIR *directory = opendir("/proc/self/fd");
    if (directory == nullptr) return {};
    std::string result;
    while (dirent *entry = readdir(directory)) {
        if (entry->d_name[0] == '.') continue;
        std::string fd = std::string("/proc/self/fd/") + entry->d_name;
        char path[4096];
        ssize_t length = readlink(fd.c_str(), path, sizeof(path) - 1);
        if (length <= 0) continue;
        path[length] = '\0';
        std::string candidate(path);
        if (candidate.find("/com.miui.home/databases/launcher") != std::string::npos
                && candidate.size() >= 3 && candidate.compare(candidate.size() - 3, 3, ".db") == 0) {
            if (!result.empty() && result != candidate) { result.clear(); break; }
            result = candidate;
        }
    }
    closedir(directory);
    if (!result.empty()) {
        std::lock_guard lock(g_db_path_mutex);
        g_cached_db_path = result;
    }
    return result;
}

void *launcherContext() {
    void *sharedAddress = g_runtime_shared_address.load(std::memory_order_acquire);
    if (sharedAddress == nullptr) return nullptr;
    void *runtime = *static_cast<void **>(sharedAddress);
    return runtime == nullptr || g_runtime_get_context == nullptr
            ? runtime : g_runtime_get_context(runtime);
}

bool readableAddress(const void *address, size_t length) {
    if (address == nullptr || length == 0) return false;
    uintptr_t start = reinterpret_cast<uintptr_t>(address);
    uintptr_t end = start + length;
    if (end < start) return false;
    FILE *maps = std::fopen("/proc/self/maps", "r");
    if (maps == nullptr) return false;
    char line[256];
    bool readable = false;
    while (std::fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long long regionStart = 0;
        unsigned long long regionEnd = 0;
        char permissions[5] = {};
        if (std::sscanf(line, "%llx-%llx %4s", &regionStart, &regionEnd, permissions) == 3
                && permissions[0] == 'r'
                && start >= static_cast<uintptr_t>(regionStart)
                && end <= static_cast<uintptr_t>(regionEnd)) {
            readable = true;
            break;
        }
    }
    std::fclose(maps);
    return readable;
}

std::string eventString(const void *event, size_t offset) {
    uint64_t capacity = nodeField<uint64_t>(event, offset);
    uint64_t pointer = nodeField<uint64_t>(event, offset + 8U)
            & 0x00ffffffffffffffULL;
    uint64_t length = nodeField<uint64_t>(event, offset + 16U);
    if ((capacity >> 63U) != 0 || length == 0 || length > 128 || capacity < length
            || !readableAddress(reinterpret_cast<const void *>(pointer), length)) return {};
    std::string value(length, '\0');
    std::memcpy(value.data(), reinterpret_cast<const void *>(pointer), length);
    return value;
}

bool loadLauncherSnapshot() {
    if (g_sqlite_open_v2 == nullptr || g_sqlite_prepare_v2 == nullptr
            || g_sqlite_step == nullptr || g_sqlite_finalize == nullptr
            || g_sqlite_column_text == nullptr || g_sqlite_column_int == nullptr
            || g_sqlite_column_int64 == nullptr || g_sqlite_close_v2 == nullptr) {
        return false;
    }
    std::string path = launcherDatabasePath();
    if (path.empty()) return false;
    Sqlite3 *db = nullptr;
    if (g_sqlite_open_v2(path.c_str(), &db, 0x10001, nullptr) != 0 || db == nullptr) {
        if (db != nullptr) g_sqlite_close_v2(db);
        return false;
    }
    if (g_sqlite_busy_timeout != nullptr) g_sqlite_busy_timeout(db, 120);
    constexpr char sql[] =
            "SELECT _id,title,intent,profileId,label,container,screen,cellX,cellY,"
            "spanX,spanY,itemType FROM favorites";
    Sqlite3Stmt *query = nullptr;
    LauncherSnapshot loaded;
    int prepareResult = g_sqlite_prepare_v2(db, sql, -1, &query, nullptr);
    if (prepareResult == 0) {
        auto text = [query](int column) -> std::string {
            const unsigned char *value = g_sqlite_column_text(query, column);
            return value == nullptr ? std::string()
                    : std::string(reinterpret_cast<const char *>(value));
        };
        int stepResult = 0;
        while ((stepResult = g_sqlite_step(query)) == kSqliteRow) {
            SnapshotFavorite favorite;
            favorite.id = g_sqlite_column_int64(query, 0);
            favorite.title = text(1);
            favorite.intent = text(2);
            favorite.user = g_sqlite_column_int(query, 3);
            favorite.label = text(4);
            favorite.placement.id = favorite.id;
            favorite.placement.container = g_sqlite_column_int64(query, 5);
            favorite.placement.screen = g_sqlite_column_int(query, 6);
            favorite.placement.cellX = g_sqlite_column_int(query, 7);
            favorite.placement.cellY = g_sqlite_column_int(query, 8);
            favorite.placement.spanX = std::max(1, g_sqlite_column_int(query, 9));
            favorite.placement.spanY = std::max(1, g_sqlite_column_int(query, 10));
            favorite.placement.itemType = g_sqlite_column_int(query, 11);
            if (favorite.placement.container == kDesktopContainer
                    && favorite.placement.screen > 0) {
                ++loaded.pageCounts[favorite.placement.screen];
            }
            loaded.favorites.push_back(std::move(favorite));
        }
        if (stepResult != kSqliteDone) prepareResult = stepResult;
    }
    if (query != nullptr) g_sqlite_finalize(query);
    g_sqlite_close_v2(db);
    if (prepareResult != 0) return false;
    uint64_t generation = 0;
    size_t favoriteCount = 0;
    size_t pageCount = 0;
    std::unordered_map<int32_t, int32_t> pageCounts;
    {
        std::lock_guard lock(g_snapshot_mutex);
        loaded.generation = g_snapshot.generation + 1;
        loaded.ready = true;
        g_snapshot = std::move(loaded);
        generation = g_snapshot.generation;
        favoriteCount = g_snapshot.favorites.size();
        pageCount = g_snapshot.pageCounts.size();
        pageCounts = g_snapshot.pageCounts;
    }
    {
        // Keep the last successful counts available while the next SQLite
        // refresh is in flight.  Accessibility events arrive before the
        // worker can publish a new snapshot during a page transition.
        std::lock_guard lock(g_page_announcement_mutex);
        g_page_item_counts = std::move(pageCounts);
    }
    nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
            "launcher snapshot ready generation=%llu favorites=%zu pages=%zu",
            static_cast<unsigned long long>(generation), favoriteCount, pageCount);
    return true;
}

void snapshotWorkerLoop() {
    for (;;) {
        std::unique_lock lock(g_snapshot_request_mutex);
        g_snapshot_request_cv.wait(lock, [] {
            return g_snapshot_refresh_requested;
        });
        bool force = false;
        // Wait for a short quiet window so a launcher transaction has finished
        // before the database snapshot is read.
        for (;;) {
            force = g_snapshot_force_requested;
            g_snapshot_force_requested = false;
            g_snapshot_refresh_requested = false;
            if (force) break;
            const auto deadline = g_snapshot_last_request
                    + std::chrono::milliseconds(120);
            if (g_snapshot_request_cv.wait_until(lock, deadline, [] {
                        return g_snapshot_refresh_requested;
                    })) {
                continue;
            }
            break;
        }
        lock.unlock();
        if (!loadLauncherSnapshot()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(200));
            requestSnapshotRefresh(true);
        }
    }
}

void ensureSnapshotWorker() {
    if (!g_snapshot_worker_started.exchange(true, std::memory_order_acq_rel)) {
        std::thread(snapshotWorkerLoop).detach();
    }
}

void requestSnapshotRefresh(bool force) {
    if (g_sqlite_open_v2 == nullptr) return;
    ensureSnapshotWorker();
    {
        std::lock_guard lock(g_snapshot_request_mutex);
        const auto now = std::chrono::steady_clock::now();
        // Keep updating the timestamp during a content-change burst. The
        // worker performs the single refresh after the burst settles.
        g_snapshot_last_request = now;
        g_snapshot_force_requested = g_snapshot_force_requested || force;
        g_snapshot_refresh_requested = true;
    }
    g_snapshot_request_cv.notify_one();
}

bool desktopItemCountForPage(int32_t page, int32_t &count) {
    if (page <= 0) return false;
    {
        std::lock_guard lock(g_snapshot_mutex);
        auto found = g_snapshot.pageCounts.find(page);
        if (g_snapshot.ready) {
            count = found == g_snapshot.pageCounts.end() ? 0 : found->second;
            return true;
        }
    }
    if (g_page_state_known.load(std::memory_order_acquire)) {
        std::lock_guard lock(g_page_announcement_mutex);
        auto found = g_page_item_counts.find(page);
        if (found != g_page_item_counts.end()) {
            count = found->second;
            return true;
        }
    }
    requestSnapshotRefresh();
    return false;
}

bool parseAppComponent(const std::string &intent, AppTarget &target) {
    size_t start = intent.find(";component=");
    if (start == std::string::npos) return false;
    start += 11;
    size_t end = intent.find(';', start);
    if (end == std::string::npos) return false;
    std::string component = intent.substr(start, end - start);
    size_t slash = component.find('/');
    if (slash == std::string::npos || slash == 0 || slash + 1 == component.size()) return false;
    // A component contains identifiers, never URI metacharacters or shell text.
    for (unsigned char c : component) {
        if (!(std::isalnum(c) || c == '_' || c == '$' || c == '.' || c == '/')) return false;
    }
    target.package = component.substr(0, slash);
    target.activity = component.substr(slash + 1);
    if (target.activity[0] == '.') target.activity.insert(0, target.package);
    return true;
}

bool sameApp(const AppTarget &a, const AppTarget &b) {
    return a.package == b.package && a.activity == b.activity && a.user == b.user;
}

bool resolveAppTarget(int32_t nodeId, const std::string &spoken, AppTarget &out,
        int32_t screenHint = -1, int32_t itemType = 0) {
    if (spoken.empty()) return false;
    LauncherSnapshot snapshot;
    {
        std::lock_guard lock(g_snapshot_mutex);
        if (!g_snapshot.ready) {
            requestSnapshotRefresh();
            return false;
        }
        snapshot = g_snapshot;
    }
    AppTarget match;
    AppTarget preferred;
    AppTarget fallback;
    bool preferredResolved = false;
    bool fallbackResolved = false;
    bool ambiguous = false;
    size_t matches = 0, preferredMatches = 0;
    for (const SnapshotFavorite &favorite : snapshot.favorites) {
        if (favorite.placement.itemType != itemType) continue;
        if (!labelMatches(spoken, favorite.title)
                && !labelMatches(spoken, favorite.label)) continue;
        ++matches;
        AppTarget candidate;
        // Placement only needs the favorite row. Some launcher rows (notably
        // unread-badged apps) omit a parseable component intent.
        parseAppComponent(favorite.intent, candidate);
        candidate.user = favorite.user;
        candidate.favorite = favorite.id;
        candidate.placement = favorite.placement;
        candidate.spoken = spoken;
        candidate.resolved = true;
        candidate.snapshotGeneration = snapshot.generation;
        if (candidate.placement.screen == screenHint) {
            preferred = candidate;
            preferredResolved = true;
            ++preferredMatches;
        }
        if (!fallbackResolved
                || candidate.placement.screen > fallback.placement.screen) {
            fallback = candidate;
            fallbackResolved = true;
        }
        if (match.resolved && !sameApp(match, candidate)
                && !match.package.empty() && !candidate.package.empty()) {
            ambiguous = true;
            break;
        }
        match = std::move(candidate);
    }
    // Folders have no app component; never guess between same-name favorites.
    if (itemType == 2 && (preferredResolved ? preferredMatches : matches) != 1) return false;
    if (preferredResolved) {
        match = std::move(preferred);
        ambiguous = false;
    } else if (ambiguous && fallbackResolved) {
        match = std::move(fallback);
        ambiguous = false;
    }
    if (!match.resolved || ambiguous) return false;
    {
        std::lock_guard lock(g_app_mutex);
        auto found = g_app_targets.find(nodeId);
        if (found != g_app_targets.end() && found->second.spoken == spoken
                && sameApp(found->second, match)
                && found->second.snapshotGeneration == snapshot.generation) {
            match.shortcuts = found->second.shortcuts;
            match.shortcutsLoaded = found->second.shortcutsLoaded;
        }
    }
    out = std::move(match);
    return true;
}

LauncherAbiProfile launcherAbiProfileForBuildId(const char *buildId) {
    if (buildId == nullptr) return LauncherAbiProfile::kUnknown;
    if (std::strcmp(buildId, kExpectedPreviousLauncherDartBuildId) == 0) {
        return LauncherAbiProfile::kLegacy7653;
    }
    if (std::strcmp(buildId, kExpectedLauncherDartBuildId) == 0) {
        return LauncherAbiProfile::kLegacy7722;
    }
    if (std::strcmp(buildId, kExpectedCurrentLauncherDartBuildId) == 0) {
        return LauncherAbiProfile::kCurrent8565;
    }
    return LauncherAbiProfile::kUnknown;
}

const char *launcherAbiProfileName(LauncherAbiProfile profile) {
    switch (profile) {
        case LauncherAbiProfile::kLegacy7653: return "8.01.02.7653";
        case LauncherAbiProfile::kLegacy7722: return "8.01.02.7722";
        case LauncherAbiProfile::kLegacy7748: return "8.01.02.7748";
        case LauncherAbiProfile::kCurrent8565: return "8.01.02.8565";
        case LauncherAbiProfile::kUnknown: return "unknown";
    }
    return "unknown";
}

bool matchesLauncherDartBuildId(const char *buildId) {
    (void) buildId;
    return g_launcher_fingerprint_ready.load(std::memory_order_acquire);
}

bool matchesLauncherMotionBuildId(const char *buildId) {
    // Motion/freeform offsets were only verified for the two legacy profiles.
    (void) buildId;
    const auto profile = g_launcher_profile.load(std::memory_order_acquire);
    return profile == LauncherAbiProfile::kLegacy7653
            || profile == LauncherAbiProfile::kLegacy7722;
}

bool matchesFrbModelBuildId(const char *buildId) {
    // 8565 uses op 833 for one ItemInfo move; legacy profiles use op 854.
    return matchesLauncherDartBuildId(buildId);
}

LauncherAbiProfile refreshLauncherAbiProfile(char *buildId, size_t buildIdSize) {
    if (buildId != nullptr && buildIdSize > 0) buildId[0] = '\0';
    const LauncherAbiProfile cached = g_launcher_profile.load(std::memory_order_acquire);
    if (cached != LauncherAbiProfile::kUnknown
            && g_launcher_fingerprint_ready.load(std::memory_order_acquire)) {
        if (buildId != nullptr && buildIdSize > 0) {
            readBuildId("libapp.so", buildId, buildIdSize);
        }
        return cached;
    }
    if (buildId != nullptr && buildIdSize > 0) {
        readBuildId("libapp.so", buildId, buildIdSize);
    }
    ElfFingerprint launcherFingerprint{};
    const LauncherAbiProfile profile = readModuleFingerprint(
            "libapp_launcher.so", launcherFingerprint)
            ? launcherAbiProfileForFingerprint(launcherFingerprint)
            : LauncherAbiProfile::kUnknown;
    g_launcher_profile.store(profile, std::memory_order_release);
    g_launcher_fingerprint_ready.store(profile != LauncherAbiProfile::kUnknown,
            std::memory_order_release);
    g_current_frb_move_profile.store(profile == LauncherAbiProfile::kCurrent8565,
            std::memory_order_release);
    g_trigger_workspace_reload = nullptr;
    g_capabilities.fetch_and(~(CAP_FRB_MODEL | CAP_MOVE_PROFILE),
            std::memory_order_release);
    if (profile != LauncherAbiProfile::kUnknown && g_frb_dispatcher != nullptr) {
        g_capabilities.fetch_or(CAP_FRB_MODEL, std::memory_order_release);
    }
    nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
            "Launcher ABI profile=%s build=%s fingerprint=%d frb_model=%d",
            launcherAbiProfileName(profile), buildId == nullptr ? "" : buildId,
            profile != LauncherAbiProfile::kUnknown ? 1 : 0,
            (g_capabilities.load(std::memory_order_acquire) & CAP_FRB_MODEL) != 0 ? 1 : 0);
    return profile;
}

bool profileMatchesSystemAbi(LauncherAbiProfile profile,
        const char *privateId, const char *aidlId, const char *uiId,
        const ElfFingerprint &privateFingerprint,
        const ElfFingerprint &aidlFingerprint,
        const ElfFingerprint &uiFingerprint) {
    (void) privateId;
    (void) aidlId;
    (void) uiId;
    return launcherMatchesSystemAbi(profile, privateFingerprint, aidlFingerprint, uiFingerprint);
}

bool moveActionsReady() {
    constexpr uint32_t kRequired = CAP_MOVE_PROFILE | CAP_PRIVATE_BUILD_MATCH
            | CAP_PRIVATE_SYMBOLS;
    return g_launcher_system_compatible.load(std::memory_order_acquire)
            && (g_capabilities.load(std::memory_order_acquire) & kRequired) == kRequired;
}

bool uninstallLaunchReady() {
    return g_scene_start_activity != nullptr && g_intent_default != nullptr
            && g_intent_set_action != nullptr && g_intent_set_component != nullptr
            && g_intent_set_extras != nullptr && g_component_name_new != nullptr
            && g_component_name_drop != nullptr && g_bundle_default != nullptr
            && g_bundle_drop != nullptr && g_bundle_insert_boolean != nullptr
            && g_bundle_insert_string != nullptr && g_rstring_from_utf16 != nullptr;
}

bool loadFavoritePlacements(const std::vector<int64_t> &ids,
        std::vector<FavoritePlacement> &out) {
    if (ids.empty() || g_sqlite_open_v2 == nullptr || g_sqlite_prepare_v2 == nullptr
            || g_sqlite_step == nullptr || g_sqlite_finalize == nullptr
            || g_sqlite_column_int == nullptr || g_sqlite_column_int64 == nullptr
            || g_sqlite_close_v2 == nullptr) return false;
    std::string path = launcherDatabasePath();
    if (path.empty()) return false;
    Sqlite3 *db = nullptr;
    if (g_sqlite_open_v2(path.c_str(), &db, 0x10001, nullptr) != 0 || db == nullptr) {
        if (db != nullptr) g_sqlite_close_v2(db);
        return false;
    }
    if (g_sqlite_busy_timeout != nullptr) g_sqlite_busy_timeout(db, 80);
    Sqlite3Stmt *query = nullptr;
    constexpr char sql[] =
            "SELECT _id,container,screen,cellX,cellY,spanX,spanY,itemType FROM favorites";
    std::unordered_map<int64_t, FavoritePlacement> found;
    int prepareResult = g_sqlite_prepare_v2(db, sql, -1, &query, nullptr);
    int stepResult = kSqliteDone;
    if (prepareResult == 0) {
        while ((stepResult = g_sqlite_step(query)) == kSqliteRow) {
            int64_t id = g_sqlite_column_int64(query, 0);
            if (std::find(ids.begin(), ids.end(), id) == ids.end()) continue;
            FavoritePlacement placement;
            placement.id = id;
            placement.container = g_sqlite_column_int64(query, 1);
            placement.screen = g_sqlite_column_int(query, 2);
            placement.cellX = g_sqlite_column_int(query, 3);
            placement.cellY = g_sqlite_column_int(query, 4);
            placement.spanX = std::max(1, g_sqlite_column_int(query, 5));
            placement.spanY = std::max(1, g_sqlite_column_int(query, 6));
            placement.itemType = g_sqlite_column_int(query, 7);
            found[id] = placement;
        }
    }
    if (query != nullptr) g_sqlite_finalize(query);
    g_sqlite_close_v2(db);
    if (prepareResult != 0 || stepResult != kSqliteDone
            || found.size() != ids.size()) return false;
    out.clear();
    out.reserve(ids.size());
    for (int64_t id : ids) {
        auto it = found.find(id);
        if (it == found.end()) return false;
        out.push_back(it->second);
    }
    return true;
}

bool loadPagePlacements(int64_t container, int32_t screen,
        std::vector<FavoritePlacement> &out) {
    if (g_sqlite_open_v2 == nullptr || g_sqlite_prepare_v2 == nullptr
            || g_sqlite_step == nullptr || g_sqlite_finalize == nullptr
            || g_sqlite_column_int == nullptr || g_sqlite_column_int64 == nullptr
            || g_sqlite_close_v2 == nullptr) return false;
    std::string path = launcherDatabasePath();
    if (path.empty()) return false;
    Sqlite3 *db = nullptr;
    if (g_sqlite_open_v2(path.c_str(), &db, 0x10001, nullptr) != 0 || db == nullptr) {
        if (db != nullptr) g_sqlite_close_v2(db);
        return false;
    }
    if (g_sqlite_busy_timeout != nullptr) g_sqlite_busy_timeout(db, 80);
    std::string sql = "SELECT _id,container,screen,cellX,cellY,spanX,spanY,itemType "
            "FROM favorites WHERE container=" + std::to_string(container)
            + " AND screen=" + std::to_string(screen);
    Sqlite3Stmt *query = nullptr;
    bool ok = g_sqlite_prepare_v2(db, sql.c_str(), -1, &query, nullptr) == 0;
    int stepResult = kSqliteDone;
    out.clear();
    if (ok) {
        while ((stepResult = g_sqlite_step(query)) == kSqliteRow) {
            FavoritePlacement placement;
            placement.id = g_sqlite_column_int64(query, 0);
            placement.container = g_sqlite_column_int64(query, 1);
            placement.screen = g_sqlite_column_int(query, 2);
            placement.cellX = g_sqlite_column_int(query, 3);
            placement.cellY = g_sqlite_column_int(query, 4);
            placement.spanX = std::max(1, g_sqlite_column_int(query, 5));
            placement.spanY = std::max(1, g_sqlite_column_int(query, 6));
            placement.itemType = g_sqlite_column_int(query, 7);
            out.push_back(placement);
        }
    }
    if (query != nullptr) g_sqlite_finalize(query);
    g_sqlite_close_v2(db);
    return ok && stepResult == kSqliteDone;
}

bool loadNextDesktopScreen(int64_t container, int32_t screen, int32_t &nextScreen,
        std::vector<FavoritePlacement> &nextPage) {
    if (g_sqlite_open_v2 == nullptr || g_sqlite_prepare_v2 == nullptr
            || g_sqlite_step == nullptr || g_sqlite_finalize == nullptr
            || g_sqlite_column_int == nullptr || g_sqlite_close_v2 == nullptr) return false;
    std::string path = launcherDatabasePath();
    if (path.empty()) return false;
    Sqlite3 *db = nullptr;
    if (g_sqlite_open_v2(path.c_str(), &db, 0x10001, nullptr) != 0 || db == nullptr) {
        if (db != nullptr) g_sqlite_close_v2(db);
        return false;
    }
    if (g_sqlite_busy_timeout != nullptr) g_sqlite_busy_timeout(db, 80);
    std::string sql = "SELECT screen FROM favorites WHERE container="
            + std::to_string(container) + " AND screen>" + std::to_string(screen)
            + " GROUP BY screen ORDER BY screen LIMIT 1";
    Sqlite3Stmt *query = nullptr;
    bool found = false;
    int prepareResult = g_sqlite_prepare_v2(db, sql.c_str(), -1, &query, nullptr);
    int stepResult = kSqliteDone;
    if (prepareResult == 0) {
        stepResult = g_sqlite_step(query);
        if (stepResult == kSqliteRow) {
            nextScreen = g_sqlite_column_int(query, 0);
            found = true;
        }
    }
    if (query != nullptr) g_sqlite_finalize(query);
    g_sqlite_close_v2(db);
    if (prepareResult != 0 || (stepResult != kSqliteRow && stepResult != kSqliteDone)) {
        return false;
    }
    if (!found) {
        nextScreen = screen + 1;
        nextPage.clear();
        return true;
    }
    return loadPagePlacements(container, nextScreen, nextPage);
}

struct WireWriter {
    std::vector<uint8_t> bytes;

    void put8(uint8_t value) { bytes.push_back(value); }
    void put32(int32_t value) {
        uint32_t raw = static_cast<uint32_t>(value);
        for (int i = 0; i < 4; ++i) bytes.push_back(static_cast<uint8_t>(raw >> (i * 8)));
    }
    void put64(int64_t value) {
        uint64_t raw = static_cast<uint64_t>(value);
        for (int i = 0; i < 8; ++i) bytes.push_back(static_cast<uint8_t>(raw >> (i * 8)));
    }
    void none() { put8(0); }
    void someI32(int32_t value) { put8(1); put32(value); }
    void someI64(int64_t value) { put8(1); put64(value); }
};

void dispatchWire(uint32_t operation, const std::vector<uint8_t> &bytes) {
    if (g_frb_dispatcher == nullptr || bytes.empty()) return;
    // The FRB dispatcher takes ownership of its byte buffer. Give it a
    // detached allocation instead of a std::vector buffer it would double-free.
    auto *owned = static_cast<uint8_t *>(std::malloc(bytes.size()));
    if (owned == nullptr) return;
    std::memcpy(owned, bytes.data(), bytes.size());
    auto size = static_cast<uint32_t>(bytes.size());
    g_frb_dispatcher(operation, 0, owned, size, size);
}

void appendSparseItemInfo(WireWriter &wire, const FavoritePlacement &source,
        const FavoritePlacement &target) {
    // This is the generated ItemInfo SSE field order from the paired launcher.
    wire.someI64(source.id);             // id
    wire.none();                         // intent
    wire.someI32(source.itemType);       // itemType
    wire.none();                         // user/profile field
    wire.someI32(source.spanX);          // spanX
    wire.someI32(source.spanY);          // spanY
    wire.someI32(target.screen);         // screen
    wire.someI32(target.cellX);          // cellX
    wire.someI32(target.cellY);          // cellY
    wire.someI64(target.container);      // container
    wire.none();                         // rank/order
    wire.none();                         // additional position
    wire.none();                         // additional position
    wire.none();                         // title
    wire.none();                         // label
    // The generated decoder has 35 fields; keep the sparse tail exact.
    wire.none();                         // field 16
    wire.none();                         // field 17
    wire.none();                         // field 18
    wire.none();                         // field 19
    wire.none();                         // field 20
    wire.none();                         // field 21
    wire.none();                         // field 22
    wire.none();                         // field 23
    wire.none();                         // field 24
    wire.none();                         // field 25
    wire.none();                         // field 26
    wire.none();                         // field 27
    wire.none();                         // field 28
    wire.none();                         // field 29
    wire.none();                         // field 30
    wire.none();                         // field 31
    wire.none();                         // field 32
    wire.none();                         // field 33
    wire.none();                         // field 34
    wire.none();                         // field 35
}

void clearLiftSession() {
    const int32_t hovered = g_virtual_hovered_id.exchange(-1, std::memory_order_acq_rel);
    const int32_t focused = g_virtual_focused_id.exchange(-1, std::memory_order_acq_rel);
    const AccessibilityEventSource hoveredSource = captureEventSource(hovered);
    const AccessibilityEventSource focusedSource = captureEventSource(focused);
    if (isVirtualNode(hovered)) sendEvent(hoveredSource, 256);
    if (isVirtualNode(focused)) sendEvent(focusedSource, 65536);
    g_lifted.store(false, std::memory_order_release);
    g_lifted_node_id.store(-1, std::memory_order_release);
    g_lifted_screen.store(-1, std::memory_order_release);
    g_drop_parent_node_id.store(-1, std::memory_order_release);
    g_drop_parent_host_id.store(0, std::memory_order_release);
    g_drop_parent_window_id.store(-1, std::memory_order_release);
    std::lock_guard lock(g_session_mutex);
    g_sources.clear();
    g_source_placements.clear();
    g_workspace.clear();
    g_slots.clear();
    g_grid_cells.clear();
    g_grid_screen = -1;
}

bool cancelLiftSession(void *provider, int32_t nodeId) {
    if (!g_lifted.exchange(false, std::memory_order_acq_rel)) return false;
    const auto parent = captureDropParentEventSource(nodeId);
    int32_t restoreId = -1;
    {
        std::lock_guard lock(g_session_mutex);
        for (const auto &node : g_workspace) {
            if (node.icon() && (restoreId < 0 || node.id == g_source.id)) restoreId = node.id;
        }
    }
    clearLiftSession();
    sendEvent(parent, kTypeWindowContentChanged);
    if (restoreId >= 0 && provider != nullptr && g_original_perform_action != nullptr) {
        uintptr_t arguments[2]{};
        g_original_perform_action(provider, restoreId, kAccessibilityFocusAction, arguments);
    }
    announceReliably(parent.host, parent.window, parent.nodeId, "已取消托起");
    return true;
}

bool cancelLiftSession(int32_t nodeId) {
    // Key/back callbacks do not carry a provider that is valid for this call.
    return cancelLiftSession(nullptr, nodeId);
}

bool placementsMatch(const std::vector<FavoritePlacement> &expected, const char *stage = nullptr) {
    std::vector<int64_t> ids;
    ids.reserve(expected.size());
    for (const auto &item : expected) ids.push_back(item.id);
    std::vector<FavoritePlacement> actual;
    if (!loadFavoritePlacements(ids, actual)) {
        if (stage != nullptr && g_diagnostics.session() != 0) {
            nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                    "move db stage=%s read_failed=1 expected=%zu", stage, expected.size());
        }
        return false;
    }
    bool matched = true;
    for (size_t i = 0; i < expected.size(); ++i) {
        const auto &want = expected[i];
        const auto &got = actual[i];
        if (want.id != got.id || want.container != got.container
                || want.screen != got.screen || want.cellX != got.cellX
                || want.cellY != got.cellY) matched = false;
    }
    if ((stage != nullptr || matched) && g_diagnostics.session() != 0) {
        for (size_t i = 0; i < expected.size(); ++i) {
            const auto &want = expected[i];
            const auto &got = actual[i];
            nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                    "move db stage=%s matched=%d id=%lld want=%lld/%d/%d,%d actual=%lld/%d/%d,%d span=%dx%d",
                    stage == nullptr ? "committed" : stage, matched ? 1 : 0,
                    static_cast<long long>(want.id), static_cast<long long>(want.container),
                    want.screen, want.cellX, want.cellY, static_cast<long long>(got.container),
                    got.screen, got.cellX, got.cellY, got.spanX, got.spanY);
        }
    }
    return matched;
}

bool updateFavoritePlacements(const std::vector<FavoritePlacement> &expected) {
    if (expected.empty() || g_sqlite_open_v2 == nullptr || g_sqlite_exec == nullptr
            || g_sqlite_close_v2 == nullptr) return false;
    std::string path = launcherDatabasePath();
    if (path.empty()) return false;
    Sqlite3 *db = nullptr;
    if (g_sqlite_open_v2(path.c_str(), &db, 0x10006, nullptr) != 0 || db == nullptr) {
        if (db != nullptr) g_sqlite_close_v2(db);
        return false;
    }
    if (g_sqlite_busy_timeout != nullptr) g_sqlite_busy_timeout(db, 120);
    char *error = nullptr;
    bool ok = g_sqlite_exec(db, "BEGIN IMMEDIATE", nullptr, nullptr, &error) == 0;
    for (const auto &item : expected) {
        if (!ok) break;
        std::string sql = "UPDATE favorites SET container=" + std::to_string(item.container)
                + ",screen=" + std::to_string(item.screen)
                + ",cellX=" + std::to_string(item.cellX)
                + ",cellY=" + std::to_string(item.cellY)
                + " WHERE _id=" + std::to_string(item.id);
        ok = g_sqlite_exec(db, sql.c_str(), nullptr, nullptr, &error) == 0;
    }
    if (ok) ok = g_sqlite_exec(db, "COMMIT", nullptr, nullptr, &error) == 0;
    if (!ok) g_sqlite_exec(db, "ROLLBACK", nullptr, nullptr, &error);
    g_sqlite_close_v2(db);
    if (ok) requestSnapshotRefresh(true);
    return ok;
}

bool waitForPlacements(const std::vector<FavoritePlacement> &expected) {
    for (int attempt = 0; attempt < 40; ++attempt) {
        if (placementsMatch(expected, attempt == 0 ? "initial"
                : attempt == 39 ? "timeout" : nullptr)) return true;
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    return false;
}

void loadDynamicShortcuts(AppTarget &target) {
    if (target.package.empty() || g_launcher_apps_get_shortcuts == nullptr
            || g_shortcut_query_default == nullptr || g_shortcut_query_drop == nullptr
            || g_shortcut_query_set_flags == nullptr
            || g_shortcut_query_set_package == nullptr
            || g_rstring_from_utf16 == nullptr || target.shortcutsLoaded) return;
    void *context = launcherContext();
    void *query = g_shortcut_query_default();
    void *user = newUser(target.user);
    if (!g_shortcut_probe_logged.exchange(true)) nativeLog(ANDROID_LOG_INFO,
            "ActionGridNative", "shortcut path context=%p query=%p user=%p get=%p",
            context, query, user, reinterpret_cast<void *>(g_launcher_apps_get_shortcuts));
    if (context == nullptr || query == nullptr || user == nullptr) {
        if (query) g_shortcut_query_drop(query);
        if (user) g_user_handle_drop(user);
        return;
    }
    RustResult48 permission = g_launcher_apps_has_permission == nullptr
            ? RustResult48{} : g_launcher_apps_has_permission(context);
    if (g_launcher_apps_has_permission != nullptr
            && !g_shortcut_permission_logged.exchange(true)) {
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "shortcut permission status=%u value=%u error_code=%d",
                permission.data[0], permission.data[1], nodeField<int32_t>(permission.data, 8));
    }
    auto package = utf16FromUtf8(target.package);
    RustOptionString name{0, {}, g_rstring_from_utf16(
            reinterpret_cast<const uint16_t *>(package.data()), package.size())};
    g_shortcut_query_set_package(query, &name); // consumes name.value
    g_shortcut_query_set_flags(query, kShortcutMatchFlags);
    RustResult48 result = g_launcher_apps_get_shortcuts(context, query, user);
    if (rustResultOk(result, "get shortcuts")) {
        RustVec shortcuts = nodeField<RustVec>(result.data, 8);
        if (shortcuts.length <= 1024 && shortcuts.length <= shortcuts.capacity) {
            auto **items = static_cast<void **>(shortcuts.data);
            for (size_t i = 0; i < shortcuts.length; ++i) {
                void *info = items[i];
                if (info == nullptr) continue;
                std::string label = copyRustStringView(g_shortcut_info_get_title(info));
                if (label.empty() && g_shortcut_info_get_text != nullptr) {
                    label = copyRustStringView(g_shortcut_info_get_text(info));
                }
                std::string id = copyRustStringView(g_shortcut_info_get_id(info));
                std::string pkg = copyRustStringView(g_shortcut_info_get_package(info));
                int32_t userId = g_shortcut_info_get_user_id(info);
                bool enabled = (g_shortcut_info_get_flags(info) & 64) == 0;
                if (enabled && !label.empty() && !id.empty() && pkg == target.package
                        && userId == target.user && target.shortcuts.size() < kDynamicActionCount) {
                    target.shortcuts.push_back({label, pkg, id, userId});
                }
                g_shortcut_info_drop(info);
            }
            shortcuts.length = 0; // pointed-to Unique values were dropped above
            dropRustVec(shortcuts);
            target.shortcutsLoaded = true;
        }
    } else if (target.package == "com.tencent.mm") {
        // ponytail: HyperOS 4 returns -5 for WeChat here, while its launcher
        // database exposes these stable manifest shortcut IDs.
        target.shortcuts = {
                {"收付款", target.package, "launch_type_offline_wallet", target.user},
                {"扫一扫", target.package, "launch_type_scan_qrcode", target.user},
                {"我的二维码", target.package, "launch_type_my_qrcode", target.user},
        };
        target.shortcutsLoaded = true;
    }
    g_user_handle_drop(user);
    g_shortcut_query_drop(query);
}

std::string shortcutRequestKey(const AppTarget &target) {
    return std::to_string(target.favorite) + "\n" + target.package + "\n"
            + std::to_string(target.user);
}

void shortcutWorkerLoop() {
    for (;;) {
        ShortcutLoadRequest request;
        {
            std::unique_lock lock(g_shortcut_queue_mutex);
            g_shortcut_queue_cv.wait(lock, [] {
                return !g_shortcut_queue.empty();
            });
            request = std::move(g_shortcut_queue.front());
            g_shortcut_queue.pop_front();
            g_shortcut_queued.erase(shortcutRequestKey(request.target));
        }
        loadDynamicShortcuts(request.target);
        if (!request.target.shortcutsLoaded) continue;
        std::vector<int32_t> nodes;
        {
            std::lock_guard lock(g_app_mutex);
            for (auto &[nodeId, cached] : g_app_targets) {
                if (cached.favorite == request.target.favorite
                        && cached.package == request.target.package
                        && cached.user == request.target.user) {
                    cached.shortcuts = request.target.shortcuts;
                    cached.shortcutsLoaded = true;
                    nodes.push_back(nodeId);
                }
            }
        }
        for (int32_t nodeId : nodes) sendEvent(nodeId, 2048);
    }
}

void ensureShortcutWorker() {
    if (!g_shortcut_worker_started.exchange(true, std::memory_order_acq_rel)) {
        std::thread(shortcutWorkerLoop).detach();
    }
}

void queueShortcutLoad(int32_t nodeId, const AppTarget &target) {
    if (target.shortcutsLoaded || target.package.empty()
            || g_launcher_apps_get_shortcuts == nullptr) return;
    ensureShortcutWorker();
    std::string key = shortcutRequestKey(target);
    {
        std::lock_guard lock(g_shortcut_queue_mutex);
        if (!g_shortcut_queued.insert(key).second) return;
        g_shortcut_queue.push_back({nodeId, target});
    }
    g_shortcut_queue_cv.notify_one();
}

bool startShortcut(const DynamicShortcut &shortcut) {
    if (sceneActivityAllowed() && shortcut.package == "com.tencent.mm"
            && g_scene_start_activity != nullptr && g_intent_default != nullptr
            && g_intent_set_action != nullptr && g_intent_set_component != nullptr
            && g_component_name_new != nullptr && g_rstring_from_utf16 != nullptr) {
        void *scene = g_scene_instance.load(std::memory_order_acquire);
        if (readableAddress(scene, sizeof(void *))) {
            const char *action = shortcut.id == "launch_type_scan_qrcode"
                    ? "io.github.jiangjinyue2333.actiongrid.WECHAT_SCAN"
                    : shortcut.id == "launch_type_offline_wallet"
                    ? "io.github.jiangjinyue2333.actiongrid.WECHAT_PAY"
                    : "io.github.jiangjinyue2333.actiongrid.WECHAT_QRCODE";
            void *intent = g_intent_default();
            if (intent != nullptr) {
                std::u16string actionText = utf16FromUtf8(action);
                RustOptionString actionName{0, {}, g_rstring_from_utf16(
                        reinterpret_cast<const uint16_t *>(actionText.data()), actionText.size())};
                g_intent_set_action(intent, &actionName);
                constexpr char package[] = "io.github.jiangjinyue2333.actiongrid";
                constexpr char activity[] =
                        "io.github.jiangjinyue2333.actiongrid.WeChatShortcutActivity";
                void *component = g_component_name_new(package, sizeof(package) - 1,
                        activity, sizeof(activity) - 1);
                if (component != nullptr) {
                    g_intent_set_component(intent, component);
                    RustResult48 result = g_scene_start_activity(
                            scene, intent, 1, nullptr, 1, 0, -1, nullptr);
                    nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                            "wechat shortcut activity id=%s result=%d",
                            shortcut.id.c_str(), nodeField<int32_t>(result.data, 8));
                    if (rustResultOk(result, "start shortcut activity")) return true;
                }
            }
        }
    }
    if (g_launcher_apps_start_shortcut == nullptr) return false;
    void *context = launcherContext();
    void *user = newUser(shortcut.user);
    if (context == nullptr || user == nullptr) {
        if (user) g_user_handle_drop(user);
        return false;
    }
    const std::string &package = shortcut.package;
    const std::string &id = shortcut.id;
    RustResult48 result = g_launcher_apps_start_shortcut(context,
            package.data(), package.size(), id.data(), id.size(), nullptr, nullptr, user);
    rustResultOk(result, "start shortcut");
    // ponytail: HyperOS starts the activity but reports a false result bit;
    // the TalkBack action is handled once the call has been made.
    g_user_handle_drop(user);
    return true;
}

bool startAppInfo(const AppTarget &target) {
    if (sceneActivityAllowed() && target.user == 999 && g_scene_start_activity != nullptr
            && g_intent_default != nullptr && g_intent_set_action != nullptr
            && g_intent_set_component != nullptr && g_intent_set_extras != nullptr
            && g_component_name_new != nullptr && g_bundle_default != nullptr
            && g_bundle_drop != nullptr && g_bundle_insert_i32 != nullptr
            && g_bundle_insert_string != nullptr && g_rstring_from_utf16 != nullptr) {
        void *scene = g_scene_instance.load(std::memory_order_acquire);
        if (scene != nullptr && readableAddress(scene, sizeof(void *))) {
            void *intent = g_intent_default();
            void *bundle = g_bundle_default();
            constexpr char kPackage[] = "com.miui.securitycenter";
            constexpr char kActivity[] = "com.miui.appmanager.ApplicationsDetailsActivity";
            void *component = g_component_name_new(kPackage, sizeof(kPackage) - 1,
                    kActivity, sizeof(kActivity) - 1);
            if (intent != nullptr && bundle != nullptr && component != nullptr) {
                auto putString = [bundle](const char *key, const std::string &value) {
                    std::u16string keyText = utf16FromUtf8(key);
                    std::u16string valueText = utf16FromUtf8(value);
                    RustRString keyString = g_rstring_from_utf16(
                            reinterpret_cast<const uint16_t *>(keyText.data()), keyText.size());
                    RustRString valueString = g_rstring_from_utf16(
                            reinterpret_cast<const uint16_t *>(valueText.data()), valueText.size());
                    RustOptionString optionalValue{0, {}, valueString};
                    g_bundle_insert_string(bundle, &keyString, &optionalValue);
                };
                std::u16string actionText = utf16FromUtf8(
                        "miui.intent.action.APP_MANAGER_APPLICATION_DETAIL");
                RustOptionString actionName{0, {}, g_rstring_from_utf16(
                        reinterpret_cast<const uint16_t *>(actionText.data()), actionText.size())};
                putString("package_name", target.package);
                std::u16string userKeyText = utf16FromUtf8("miui.intent.extra.USER_ID");
                RustRString userKey = g_rstring_from_utf16(
                        reinterpret_cast<const uint16_t *>(userKeyText.data()), userKeyText.size());
                g_bundle_insert_i32(bundle, &userKey, target.user);
                g_intent_set_action(intent, &actionName);
                g_intent_set_extras(intent, bundle);
                g_intent_set_component(intent, component);
                RustResult48 result = g_scene_start_activity(
                        scene, intent, 1, nullptr, 1, 0, -1, nullptr);
                nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                        "clone app info package=%s user=%d result=%d", target.package.c_str(),
                        target.user, nodeField<int32_t>(result.data, 8));
                if (rustResultOk(result, "clone app info")) return true;
            } else {
                if (bundle != nullptr) g_bundle_drop(bundle);
                if (component != nullptr) g_component_name_drop(component);
            }
        }
    }
    void *runtime = g_runtime_shared_address.load(std::memory_order_acquire);
    if (runtime == nullptr || g_launcher_apps_start_app_info == nullptr) return false;
    void *component = g_component_name_new(target.package.data(), target.package.size(),
            target.activity.data(), target.activity.size());
    // ponytail: fall back to the primary profile if the MIUI-specific clone
    // details activity is unavailable.
    void *user = newUser(target.user == 999 ? 0 : target.user);
    void *bundle = g_bundle_default();
    bool success = false;
    if (component && user && bundle) {
        Bounds bounds{};
        RustResult48 result = g_launcher_apps_start_app_info(runtime, component, user, &bounds, bundle);
        success = rustResultOk(result, "app info");
    }
    if (bundle) g_bundle_drop(bundle);
    if (user) g_user_handle_drop(user);
    if (component) g_component_name_drop(component);
    return success;
}

bool startUninstall(const AppTarget &target) {
    void *scene = g_scene_instance.load(std::memory_order_acquire);
    if (!sceneActivityAllowed() || scene == nullptr || target.user != 0
            || target.package.empty() || !uninstallLaunchReady()) {
        return false;
    }
    void *intent = g_intent_default();
    void *component = g_component_name_new(
            "io.github.jiangjinyue2333.actiongrid",
            sizeof("io.github.jiangjinyue2333.actiongrid") - 1,
            "io.github.jiangjinyue2333.actiongrid.WeChatShortcutActivity",
            sizeof("io.github.jiangjinyue2333.actiongrid.WeChatShortcutActivity") - 1);
    void *bundle = g_bundle_default();
    void *options = g_bundle_default();
    if (intent == nullptr || component == nullptr || bundle == nullptr || options == nullptr) {
        if (component != nullptr) g_component_name_drop(component);
        if (bundle != nullptr) g_bundle_drop(bundle);
        if (options != nullptr) g_bundle_drop(options);
        return false;
    }
    auto putString = [bundle](const char *key, const std::string &value) {
        std::u16string keyText = utf16FromUtf8(key);
        std::u16string valueText = utf16FromUtf8(value);
        RustRString keyString = g_rstring_from_utf16(
                reinterpret_cast<const uint16_t *>(keyText.data()), keyText.size());
        RustRString valueString = g_rstring_from_utf16(
                reinterpret_cast<const uint16_t *>(valueText.data()), valueText.size());
        RustOptionString optionalValue{0, {}, valueString};
        g_bundle_insert_string(bundle, &keyString, &optionalValue);
    };
    std::u16string actionText = utf16FromUtf8(
            "io.github.jiangjinyue2333.actiongrid.UNINSTALL");
    RustOptionString actionName{0, {}, g_rstring_from_utf16(
            reinterpret_cast<const uint16_t *>(actionText.data()), actionText.size())};
    putString("io.github.jiangjinyue2333.actiongrid.EXTRA_PACKAGE", target.package);
    std::u16string shareIdentityText = utf16FromUtf8(
            "android:activity.shareIdentity");
    RustRString shareIdentityKey = g_rstring_from_utf16(
            reinterpret_cast<const uint16_t *>(shareIdentityText.data()),
            shareIdentityText.size());
    g_bundle_insert_boolean(options, &shareIdentityKey, true);
    g_intent_set_action(intent, &actionName);
    g_intent_set_extras(intent, bundle);
    g_intent_set_component(intent, component);
    // Scene_start_activity's public ABI carries the options Bundle in x7;
    // x3 is the optional launch token and stays empty for this trampoline.
    RustResult48 result = g_scene_start_activity(
            scene, intent, 1, nullptr, 1, 0, -1, options);
    // Scene_start_activity borrows the options Bundle; release it after the call.
    g_bundle_drop(options);
    nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
            "uninstall trampoline package=%s result=%d", target.package.c_str(),
            nodeField<int32_t>(result.data, 8));
    bool success = rustResultOk(result, "start uninstall trampoline");
    // Intent_set_extras takes ownership of the Bundle on the current ABI.
    // Dropping it here double-frees the underlying AParcel.
    // Intent_set_component takes ownership of the ComponentName on this ABI.
    return success;
}

// Layout is verified against the AIDL Build ID before installing either hook.
// Rust String is (capacity, data, length); absent Option<String> has a high bit.
std::string nodeString(const void *node, size_t offset) {
    size_t capacity = nodeField<size_t>(node, offset);
    if ((capacity >> 63U) != 0) return {};
    const char *data = nodeField<const char *>(node, offset + 8);
    size_t length = nodeField<size_t>(node, offset + 16);
    if (data == nullptr || length > 4096 || length > capacity) return {};
    return {data, length};
}

uint64_t eventSourceKey(int32_t host, int32_t virtualId) {
    return static_cast<uint32_t>(host)
            | (static_cast<uint64_t>(static_cast<uint32_t>(virtualId)) << 32U);
}

bool looksLikePageIndicator(const std::string &value) {
    constexpr char kPrefix[] = "第 ";
    constexpr char kMiddle[] = " 页共 ";
    constexpr char kSuffix[] = " 页";
    constexpr size_t kSuffixLength = sizeof(kSuffix) - 1U;
    return value.rfind(kPrefix, 0) == 0
            && value.find(kMiddle, sizeof(kPrefix) - 1U) != std::string::npos
            && value.size() >= kSuffixLength
            && value.compare(value.size() - kSuffixLength, kSuffixLength, kSuffix) == 0;
}

bool parsePageIndicator(const std::string &description, int32_t &page,
        int32_t *totalPages = nullptr) {
    if (!looksLikePageIndicator(description)) return false;
    const char *start = description.c_str() + 4; // UTF-8 "第 "
    char *end = nullptr;
    long parsed = std::strtol(start, &end, 10);
    if (end == start || parsed <= 0 || parsed > std::numeric_limits<int32_t>::max()) {
        return false;
    }
    page = static_cast<int32_t>(parsed);
    if (totalPages != nullptr) {
        constexpr char kMiddle[] = " 页共 ";
        const size_t middle = description.find(kMiddle, 4);
        if (middle == std::string::npos) return false;
        const char *totalStart = description.c_str() + middle + sizeof(kMiddle) - 1U;
        long parsedTotal = std::strtol(totalStart, &end, 10);
        if (end == totalStart || parsedTotal <= 0
                || parsedTotal > std::numeric_limits<int32_t>::max()) return false;
        *totalPages = static_cast<int32_t>(parsedTotal);
    }
    return true;
}

bool rememberVisibleScreen(const std::string &description) {
    int32_t page = 0;
    int32_t totalPages = 0;
    if (!parsePageIndicator(description, page, &totalPages)) return false;
    const int32_t previous = g_visible_screen.load(std::memory_order_acquire);
    const bool changed = !g_page_state_known.load(std::memory_order_acquire)
            || previous != page - 1;
    g_visible_screen.store(page - 1, std::memory_order_release);
    g_total_pages.store(totalPages, std::memory_order_release);
    g_page_state_known.store(true, std::memory_order_release);
    return changed;
}

void rememberPageSource(void *node) {
    if (node == nullptr) return;
    std::string resource = nodeString(node, 0x1b8);
    if (resource != "com.miui.home:id/workspace"
            && resource != "com.miui.home:id/screen_content") return;
    uint64_t source = static_cast<uint64_t>(nodeField<int64_t>(node, 0x338));
    g_workspace_window_id.store(nodeField<int32_t>(node, 0x418),
            std::memory_order_release);
    std::lock_guard lock(g_event_mutex);
    g_page_source_keys.insert(source);
}

bool nodeSelected(const void *node) {
    // AIDL e05f4ad2: set_selected/set_checked share this bitfield with enabled,
    // focusable, etc. Reading the whole byte as a bool misidentifies all icons.
    return node != nullptr && (nodeField<uint32_t>(node, 0x430) & 0x12U) != 0;
}

bool isLiftedSource(int32_t nodeId) {
    if (g_lifted_screen.load() >= 0 && g_lifted_screen.load() != g_visible_screen.load()) return false;
    std::lock_guard lock(g_session_mutex);
    return std::any_of(g_sources.begin(), g_sources.end(),
            [nodeId](const auto &source) { return source.id == nodeId; });
}

ObservedNode describeNode(void *node) {
    int64_t source = nodeField<int64_t>(node, 0x338);
    return {static_cast<int32_t>(source >> 32),
            static_cast<int32_t>(nodeField<int64_t>(node, 0x340) >> 32),
            static_cast<int32_t>(source), nodeField<int32_t>(node, 0x418),
            nodeField<Bounds>(node, kNodeBoundsOffset), nodeString(node, 0x1b8),
            nodeString(node, 0x138), nodeSelected(node)};
}

bool isLiftedSourceId(int32_t id) {
    return std::any_of(g_sources.begin(), g_sources.end(),
            [id](const auto &source) { return source.id == id; });
}

int32_t findCurrentDropParentNode() {
    for (const char *resource : {"com.miui.home:id/screen_content",
            "com.miui.home:id/workspace", "com.miui.home:id/hotseat"}) {
        for (const auto &node : g_workspace) {
            if (node.resource != resource) continue;
            int32_t parent = -1;
            for (const auto &icon : g_workspace) {
                if (!icon.icon() || hotseatNodeLocked(icon) || !descendsFromLocked(icon, node.id)) continue;
                if (parent < 0) parent = icon.parent;
                while (parent != node.id && !descendsFromLocked(icon, parent)) {
                    auto found = std::find_if(g_workspace.begin(), g_workspace.end(), [&](const auto &entry) {
                        return entry.id == parent && entry.host == node.host;
                    });
                    if (found == g_workspace.end() || found->parent < 0 || found->parent == parent) {
                        parent = node.id;
                        break;
                    }
                    parent = found->parent;
                }
            }
            return parent < 0 ? node.id : parent;
        }
    }
    return g_source.id;
}

int32_t findDropParentNode() {
    return findCurrentDropParentNode();
}

void rebuildSlots() {
    const int32_t screen = g_visible_screen.load(std::memory_order_acquire);
    const int32_t previousScreen = g_grid_screen;
    const bool previousGridUsable = previousScreen == screen && !g_grid_cells.empty();
    Bounds page{};
    bool pageFound = false;
    auto usable = [](const Bounds &bounds) {
        return bounds.left >= 0 && bounds.top >= 0
                && bounds.width() > 0 && bounds.height() > 0;
    };
    // screen_content is the exact desktop host on most builds.  7748 can
    // expose only workspace while a page is being rebuilt, so use it as a
    // verified host fallback and clip the hotseat below it.
    for (const char *resource : {"com.miui.home:id/screen_content",
            "com.miui.home:id/workspace"}) {
        for (const auto &node : g_workspace) {
            if (node.resource == resource && usable(node.bounds)) {
                page = node.bounds;
                pageFound = true;
                break;
            }
        }
        if (pageFound) break;
    }
    for (const auto &node : g_workspace) {
        if (node.resource == "com.miui.home:id/hotseat" && usable(node.bounds)
                && node.bounds.top > page.top && node.bounds.top < page.bottom) {
            page.bottom = std::min(page.bottom, node.bounds.top);
        }
    }
    if (!pageFound || !usable(page)) {
        nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                "grid unavailable: page bounds=%d,%d,%d,%d nodes=%zu screen=%d",
                page.left, page.top, page.right, page.bottom, g_workspace.size(), screen);
        if (!previousGridUsable) {
            g_slots.clear();
            g_grid_cells.clear();
            g_grid_screen = -1;
        }
        return;
    }

    int32_t columns = g_cell_count_x == nullptr ? g_grid_columns.load() : g_cell_count_x();
    int32_t rows = g_cell_count_y == nullptr ? g_grid_rows.load() : g_cell_count_y();
    std::vector<ObservedNode> icons;
    for (const auto &node : g_workspace) {
        if (node.icon() && !hotseatNodeLocked(node) && node.bounds.centerX() >= page.left
                && node.bounds.centerX() < page.right && node.bounds.centerY() >= page.top
                && node.bounds.centerY() < page.bottom) icons.push_back(node);
    }
    // A transient getter failure must not invalidate a lifted target.  The
    // last verified dimensions are valid for every page of this launcher.
    if (columns <= 0 && previousGridUsable) columns = g_grid_columns.load();
    if (rows <= 0 && previousGridUsable) rows = g_grid_rows.load();
    // Older profiles lack verified DeviceConfig getters.  On a page with one
    // icon, derive the full physical grid from the host and the icon cell;
    // inferring from icon ordinals would expose no empty cells at all.
    if ((columns <= 0 || rows <= 0) && !icons.empty()) {
        const auto &cell = icons.front().bounds;
        if (columns <= 0 && cell.width() > 0) {
            columns = static_cast<int32_t>(std::lround(
                    static_cast<double>(page.width()) / cell.width()));
        }
        if (rows <= 0 && cell.height() > 0) {
            rows = static_cast<int32_t>(std::lround(
                    static_cast<double>(page.height()) / cell.height()));
        }
        columns = std::clamp(columns, 1, 10);
        rows = std::clamp(rows, 1, 12);
    }
    if (columns <= 0 || rows <= 0 || columns > kVirtualNodeCount
            || rows > kVirtualNodeCount / columns) {
        nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                "grid unavailable: dimensions=%dx%d visible_icons=%zu", columns, rows, icons.size());
        if (!previousGridUsable) {
            g_slots.clear();
            g_grid_cells.clear();
            g_grid_screen = -1;
        }
        return;
    }

    std::vector<SnapshotFavorite> favorites;
    {
        std::lock_guard lock(g_snapshot_mutex);
        for (const auto &favorite : g_snapshot.favorites) {
            if (favorite.placement.container == kDesktopContainer
                    && favorite.placement.screen == screen + 1) {
                favorites.push_back(favorite);
            }
        }
    }
    // Calibrate from known cell indexes, not the ordinal of the occupied rows.
    // A missing column must leave a physical hole in the touch grid.
    auto axis = [&](bool horizontal, int32_t count, int32_t begin, int32_t end) {
        std::vector<std::pair<int32_t, float>> anchors;
        for (const auto &icon : icons) {
            const SnapshotFavorite *match = nullptr;
            for (const auto &favorite : favorites) {
                if (favorite.placement.spanX != 1 || favorite.placement.spanY != 1) continue;
                if (!labelMatches(icon.label, favorite.title)
                        && !labelMatches(icon.label, favorite.label)) continue;
                if (match != nullptr) { match = nullptr; break; }
                match = &favorite;
            }
            if (match == nullptr) continue;
            int32_t index = horizontal ? match->placement.cellX : match->placement.cellY;
            if (index >= 0 && index < count) anchors.emplace_back(index,
                    horizontal ? icon.bounds.centerX() : icon.bounds.centerY());
        }
        float step = static_cast<float>(end - begin) / count;
        float origin = static_cast<float>(begin);
        std::vector<float> steps;
        for (size_t i = 0; i < anchors.size(); ++i) {
            for (size_t j = i + 1; j < anchors.size(); ++j) {
                int32_t distance = anchors[j].first - anchors[i].first;
                if (distance == 0) continue;
                float measured = (anchors[j].second - anchors[i].second) / distance;
                if (measured > step * 0.7f && measured < step * 1.3f) steps.push_back(measured);
            }
        }
        if (!steps.empty()) {
            std::sort(steps.begin(), steps.end());
            step = steps[steps.size() / 2];
            std::vector<float> origins;
            for (const auto &[index, center] : anchors) origins.push_back(center - (index + 0.5f) * step);
            std::sort(origins.begin(), origins.end());
            origin = origins[origins.size() / 2];
        }
        return std::pair<float, float>{origin, step};
    };
    const auto [originX, stepX] = axis(true, columns, page.left, page.right);
    const auto [originY, stepY] = axis(false, rows, page.top, page.bottom);
    std::vector<Slot> newSlots;
    std::vector<Slot> newGridCells;
    for (int32_t row = 0; row < rows; ++row) {
        for (int32_t column = 0; column < columns; ++column) {
            Bounds cell{
                 column == 0 ? page.left : std::clamp(static_cast<int32_t>(std::lround(originX + column * stepX)), page.left, page.right),
                 row == 0 ? page.top : std::clamp(static_cast<int32_t>(std::lround(originY + row * stepY)), page.top, page.bottom),
                 column == columns - 1 ? page.right : std::clamp(static_cast<int32_t>(std::lround(originX + (column + 1) * stepX)), page.left, page.right),
                 row == rows - 1 ? page.bottom : std::clamp(static_cast<int32_t>(std::lround(originY + (row + 1) * stepY)), page.top, page.bottom)};
            if (cell.width() <= 0 || cell.height() <= 0) continue;
            newGridCells.push_back({cell, row, column});
            bool occupied = false;
            for (const auto &favorite : favorites) {
                const auto &item = favorite.placement;
                if (column >= item.cellX && column < item.cellX + item.spanX
                        && row >= item.cellY && row < item.cellY + item.spanY) {
                    occupied = true;
                    break;
                }
            }
            for (const auto &node : icons) {
                if (node.bounds.centerX() >= cell.left && node.bounds.centerX() < cell.right
                        && node.bounds.centerY() >= cell.top && node.bounds.centerY() < cell.bottom) {
                    occupied = true;
                    break;
                }
            }
            if (!occupied) newSlots.push_back({cell, row, column});
        }
    }
    if (newGridCells.empty()) {
        if (!previousGridUsable) {
            g_slots.clear();
            g_grid_cells.clear();
            g_grid_screen = -1;
        }
        return;
    }
    g_grid_columns.store(columns, std::memory_order_release);
    g_grid_rows.store(rows, std::memory_order_release);
    g_grid_cells = std::move(newGridCells);
    g_slots = std::move(newSlots);
    g_grid_screen = screen;
    const int64_t diagnosticSession = g_diagnostics.session();
    if (diagnosticSession != 0) {
        std::string occupancy(static_cast<size_t>(rows * (columns + 1)), 'x');
        for (int row = 0; row < rows; ++row) occupancy[row * (columns + 1) + columns] = '/';
        for (const auto &slot : g_slots) occupancy[slot.row * (columns + 1) + slot.column] = '.';
        const std::string signature = std::to_string(g_grid_screen) + ":" + occupancy;
        if (g_diagnostics_grid_session != diagnosticSession || signature != g_diagnostics_last_grid) {
            g_diagnostics_grid_session = diagnosticSession;
            g_diagnostics_last_grid = signature;
            nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                    "grid screen=%d size=%dx%d page=%d,%d,%d,%d icons=%zu favorites=%zu slots=%zu occupancy(x=occupied,.=free)=%s",
                    g_grid_screen, columns, rows, page.left, page.top, page.right, page.bottom,
                    icons.size(), favorites.size(), g_slots.size(), occupancy.c_str());
        }
    }
}

void readTree(void *provider, int32_t id, std::vector<ObservedNode> &nodes, int depth) {
    if (g_original_create_node == nullptr || g_node_drop == nullptr
            || depth > 20 || nodes.size() >= 512 || isVirtualNode(id)) return;
    for (const auto &node : nodes) if (node.id == id) return;
    NodeResult result = g_original_create_node(provider, id);
    if ((result.absent & 1U) != 0 || result.node == nullptr) return;
    nodes.push_back(describeNode(result.node));
    std::vector<int64_t> children;
    if ((nodeField<uint64_t>(result.node, 0x30) >> 63U) == 0) {
        size_t count = nodeField<size_t>(result.node, 0x40);
        auto *data = nodeField<const int64_t *>(result.node, 0x38);
        if (count > 0 && count <= 512 && data != nullptr) children.assign(data, data + count);
    }
    g_node_drop(result.node);
    for (int64_t child : children) {
        readTree(provider, static_cast<int32_t>(child >> 32), nodes, depth + 1);
    }
}

void refreshLiftSessionForPage(void *provider) {
    if (provider == nullptr || !g_lifted.load(std::memory_order_acquire)
            || g_original_create_node == nullptr) return;
    std::vector<ObservedNode> nodes;
    readTree(provider, -1, nodes);
    if (nodes.empty()) return;
    const size_t nodeCount = nodes.size();
    int32_t oldParent = -1;
    int32_t newParent = -1;
    AccessibilityEventSource oldParentSource;
    AccessibilityEventSource newParentSource;
    bool keepHover = false, keepFocus = false;
    const int32_t oldHover = g_virtual_hovered_id.load();
    const int32_t oldFocus = g_virtual_focused_id.load();
    {
        std::lock_guard lock(g_session_mutex);
        if (!g_lifted.load(std::memory_order_acquire)) return;
        const int32_t oldScreen = g_grid_screen;
        oldParent = g_drop_parent_node_id.load(std::memory_order_acquire);
        oldParentSource = {
                g_drop_parent_host_id.load(std::memory_order_acquire),
                g_drop_parent_window_id.load(std::memory_order_acquire), oldParent};
        g_workspace = std::move(nodes);
        rebuildSlots();
        newParent = findCurrentDropParentNode();
        for (const auto &node : g_workspace) {
            if (node.id == newParent) {
                g_drop_parent_host_id.store(node.host, std::memory_order_release);
                g_drop_parent_window_id.store(node.window, std::memory_order_release);
                break;
            }
        }
        g_drop_parent_node_id.store(newParent, std::memory_order_release);
        newParentSource = {
                g_drop_parent_host_id.load(std::memory_order_acquire),
                g_drop_parent_window_id.load(std::memory_order_acquire), newParent};
        const bool samePage = oldScreen == g_grid_screen
                && oldParentSource.host == newParentSource.host
                && oldParentSource.window == newParentSource.window;
        keepHover = samePage && slotIndexForVirtualIdLocked(oldHover) >= 0;
        keepFocus = samePage && slotIndexForVirtualIdLocked(oldFocus) >= 0;
    }
    const AccessibilityEventSource oldHoverSource{
            oldParentSource.host, oldParentSource.window, oldHover};
    const AccessibilityEventSource oldFocusSource{
            oldParentSource.host, oldParentSource.window, oldFocus};
    int32_t expected = oldHover;
    if (!keepHover && g_virtual_hovered_id.compare_exchange_strong(expected, -1)
            && isVirtualNode(oldHover)) sendEvent(oldHoverSource, 256);
    expected = oldFocus;
    if (!keepFocus && g_virtual_focused_id.compare_exchange_strong(expected, -1)
            && isVirtualNode(oldFocus)) sendEvent(oldFocusSource, 65536);
    if (oldParent != newParent) sendEvent(oldParentSource, kTypeWindowContentChanged);
    sendEvent(newParentSource, kTypeWindowContentChanged);
    nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
            "lift page refreshed nodes=%zu old_parent=%d new_parent=%d page=%d",
            nodeCount, oldParent, newParent,
            g_visible_screen.load(std::memory_order_acquire) + 1);
}

void scheduleLiftSessionRefresh(bool lifted) {
    // The next root accessibility query refreshes the lifted tree while the
    // provider is still owned by that call.  Never retain its raw pointer in
    // a detached thread: the launcher can destroy it during a page switch.
    (void) lifted;
}

bool sendPageAnnouncementWithSource(int32_t host, int32_t window, int32_t virtualId,
        int32_t page, int32_t totalPages, const std::string &text) {
    void *controller = g_event_controller.load(std::memory_order_acquire);
    if (controller == nullptr || text.empty() || g_event_new == nullptr
            || g_event_drop == nullptr || g_original_send_event == nullptr
            || g_event_set_source == nullptr || g_event_push_text == nullptr
            || g_rstring_from_utf16 == nullptr) return false;

    // The native reader accepts a real scroll record, while TalkBack also
    // accepts the announcement record used by older public AIDL builds.  The
    // setters are resolved from the verified public ABI, so use the richer
    // record only when the complete setter set is available.
    const bool scrollRecord = g_event_set_item_count != nullptr
            && g_event_set_from_index != nullptr && g_event_set_to_index != nullptr;
    const int32_t type = scrollRecord ? kTypeViewScrolled : kTypeAnnouncement;
    void *event = g_event_new(type);
    if (event == nullptr) return false;
    if (g_event_set_package != nullptr) {
        RustRString package = g_rstring_from_utf16(
                reinterpret_cast<const uint16_t *>(u"com.miui.home"), 13);
        g_event_set_package(event, &package);
    }
    g_event_set_source(event, host, window, virtualId);
    if (g_event_set_event_type != nullptr) g_event_set_event_type(event, type);
    if (scrollRecord) {
        g_event_set_item_count(event, std::max(1, totalPages));
        g_event_set_from_index(event, std::max(0, page - 1));
        g_event_set_to_index(event, std::max(0, page - 1));
    }
    const std::u16string value = utf16FromUtf8(text);
    RustRString speech = g_rstring_from_utf16(
            reinterpret_cast<const uint16_t *>(value.data()), value.size());
    g_event_push_text(event, &speech);
    g_original_send_event(controller, event);
    g_event_drop(event);
    return true;
}

bool announceConfirmedPage(int32_t page, const AccessibilityEventSource &source) {
    const int32_t surfaceValue = g_surface_state.load(std::memory_order_acquire);
    const SurfaceState surface = static_cast<SurfaceState>(surfaceValue);
    if (surfaceValue != static_cast<int32_t>(SurfaceState::kWorkspace)
            && !g_page_state_known.load(std::memory_order_acquire)) return false;
    int32_t count = 0;
    if (!desktopItemCountForPage(page, count)) return false;
    int32_t previous;
    {
        std::lock_guard lock(g_page_announcement_mutex);
        if (g_announced_page == page) return true;
        previous = g_announced_page;
        g_announced_page = page;
    }
    const int32_t totalPages = g_total_pages.load(std::memory_order_acquire);
    const std::string text = "第 " + std::to_string(page) + " 页共 "
            + std::to_string(count) + " 项";
    const bool sent = sendPageAnnouncementWithSource(source.host, source.window, source.nodeId,
            page, totalPages, text);
    if (!sent) {
        std::lock_guard lock(g_page_announcement_mutex);
        if (g_announced_page == page) g_announced_page = previous;
    }
    nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
            "page speech page=%d items=%d sent=%d source=%d/%d window=%d",
            page, count, sent ? 1 : 0, source.host, source.nodeId, source.window);
    return sent;
}

bool rewritePageScrollEvent(void *event, bool &suppressOriginal) {
    suppressOriginal = false;
    if (event == nullptr) return false;
    int32_t type = nodeField<int32_t>(event, kEventTypeOffset);
    int32_t itemCount = nodeField<int32_t>(event, kEventItemCountOffset);
    int32_t fromIndex = nodeField<int32_t>(event, kEventFromIndexOffset);
    int32_t toIndex = nodeField<int32_t>(event, kEventToIndexOffset);
    uint64_t source = nodeField<uint64_t>(event, kEventSourceOffset);
    const int32_t window = nodeField<int32_t>(event, kEventWindowOffset);
    const int32_t workspaceWindow = g_workspace_window_id.load(std::memory_order_acquire);
    if (workspaceWindow >= 0 && window != workspaceWindow) return false;
    if (type == kTypeAnnouncement) {
        int32_t page = 0;
        std::string originalText = eventString(event, kEventTextOffset);
        int32_t totalPages = 0;
        if (parsePageIndicator(originalText, page, &totalPages)) {
            if (g_launcher_window_focused.load(std::memory_order_acquire) == 0) {
                return false;
            }
            if (workspaceWindow == -1) {
                g_workspace_window_id.store(window, std::memory_order_release);
            }
            const bool lifted = g_lifted.load(std::memory_order_acquire);
            g_visible_screen.store(page - 1, std::memory_order_release);
            g_total_pages.store(totalPages, std::memory_order_release);
            g_page_state_known.store(true, std::memory_order_release);
            if (!announceConfirmedPage(page, {static_cast<int32_t>(source), window,
                    static_cast<int32_t>(source >> 32U)})) return false;
            suppressOriginal = true;
            scheduleLiftSessionRefresh(lifted);
            return true;
        }
    }
    bool sourceMatched = false;
    {
        std::lock_guard lock(g_event_mutex);
        sourceMatched = g_page_source_keys.find(source) != g_page_source_keys.end();
    }
    if (!sourceMatched && (workspaceWindow < 0
            || window != workspaceWindow
            || !g_page_state_known.load(std::memory_order_acquire))) {
        return false;
    }
    if (workspaceWindow == -1) {
        g_workspace_window_id.store(window, std::memory_order_release);
    }
    if (type != kTypeViewScrolled || itemCount <= 0
            || fromIndex < 0 || fromIndex >= itemCount || toIndex < fromIndex) {
        return false;
    }
    const bool lifted = g_lifted.load(std::memory_order_acquire);
    int32_t page = fromIndex + 1;
    g_visible_screen.store(page - 1, std::memory_order_release);
    g_page_state_known.store(true, std::memory_order_release);
    if (!announceConfirmedPage(page, {static_cast<int32_t>(source), window,
            static_cast<int32_t>(source >> 32U)})) return false;
    suppressOriginal = true;
    scheduleLiftSessionRefresh(lifted);
    return true;
}

void onSendEvent(void *controller, void *event) {
    g_event_controller.store(controller, std::memory_order_release);
    if (event != nullptr
            && nodeField<int32_t>(event, kEventTypeOffset) == kTypeWindowContentChanged
            && static_cast<SurfaceState>(g_surface_state.load(std::memory_order_acquire))
                    == SurfaceState::kWorkspace) {
        // Never touch SQLite on the accessibility event thread. The worker
        // coalesces this burst and publishes a new generation asynchronously.
        requestSnapshotRefresh();
    }
    bool suppressOriginal = false;
    if ((g_capabilities.load(std::memory_order_acquire) & CAP_PAGE_SCROLL_EVENT) != 0) {
        rewritePageScrollEvent(event, suppressOriginal);
    }
    if (!suppressOriginal) g_original_send_event(controller, event);
    flushSurfaceAnnouncements();
}


void sendEvent(int32_t nodeId, int32_t type) {
    sendEvent(captureEventSource(nodeId), type);
}

void sendEvent(const AccessibilityEventSource &source, int32_t type) {
    const int32_t nodeId = source.nodeId;
    void *controller = g_event_controller.load(std::memory_order_acquire);
    if (controller == nullptr || g_event_new == nullptr || g_event_drop == nullptr
            || g_original_send_event == nullptr || g_event_set_source == nullptr) return;
    void *event = g_event_new(type);
    if (event == nullptr) return;
    if (g_event_set_package != nullptr && g_rstring_from_utf16 != nullptr) {
        RustRString package = g_rstring_from_utf16(
                reinterpret_cast<const uint16_t *>(u"com.miui.home"), 13);
        g_event_set_package(event, &package);
    }
    g_event_set_source(event, source.host, source.window, nodeId);
    if (isVirtualNode(nodeId) && g_event_push_text != nullptr
            && g_rstring_from_utf16 != nullptr) {
        const std::string description = virtualNodeDescription(nodeId);
        if (!description.empty()) {
            const std::u16string text = utf16FromUtf8(description);
            RustRString value = g_rstring_from_utf16(
                    reinterpret_cast<const uint16_t *>(text.data()), text.size());
            g_event_push_text(event, &value);
        }
    }
    if (type == kTypeWindowContentChanged && g_event_set_change != nullptr) {
        g_event_set_change(event, 1);
    }
    g_original_send_event(controller, event);
    g_event_drop(event);
}

bool sendAnnouncementWithSource(int32_t host, int32_t window, int32_t virtualId,
        const std::string &text) {
    void *controller = g_event_controller.load(std::memory_order_acquire);
    if (controller == nullptr || text.empty() || g_event_new == nullptr
            || g_event_drop == nullptr || g_original_send_event == nullptr
            || g_event_set_source == nullptr || g_event_push_text == nullptr
            || g_rstring_from_utf16 == nullptr) return false;
    void *event = g_event_new(kTypeAnnouncement);
    if (event == nullptr) return false;
    if (g_event_set_package != nullptr) {
        RustRString package = g_rstring_from_utf16(
                reinterpret_cast<const uint16_t *>(u"com.miui.home"), 13);
        g_event_set_package(event, &package);
    }
    g_event_set_source(event, host, window, virtualId);
    if (g_event_set_event_type != nullptr) g_event_set_event_type(event, kTypeAnnouncement);
    std::u16string value = utf16FromUtf8(text);
    RustRString speech = g_rstring_from_utf16(
            reinterpret_cast<const uint16_t *>(value.data()), value.size());
    g_event_push_text(event, &speech);
    g_original_send_event(controller, event);
    g_event_drop(event);
    return true;
}

bool announceReliably(int32_t host, int32_t window, int32_t virtualId,
        const std::string &text) {
    if (text.empty()) return false;
    if (sendAnnouncementWithSource(host, window, virtualId, text)) return true;
    // The accessibility event controller is replaced during Home transitions.
    // Keep the speech request until a synchronous provider/event callback can
    // send it; never retain that callback's raw provider pointer in a worker.
    queueSurfaceAnnouncement(text);
    flushSurfaceAnnouncements();
    return false;
}

bool sendAnnouncement(int32_t nodeId, const std::string &text) {
    const int32_t host = isVirtualNode(nodeId)
            ? g_drop_parent_host_id.load(std::memory_order_acquire)
            : g_host_view_id.load(std::memory_order_acquire);
    const int32_t window = isVirtualNode(nodeId)
            ? g_drop_parent_window_id.load(std::memory_order_acquire)
            : g_window_id.load(std::memory_order_acquire);
    return sendAnnouncementWithSource(host, window, nodeId, text);
}

void queueSurfaceAnnouncement(const std::string &text) {
    if (g_launcher_window_focused.load(std::memory_order_acquire) == 0) return;
    if (text.empty()) return;
    if (g_focus_callback_active.load(std::memory_order_acquire)) {
        std::lock_guard lock(g_surface_announcement_mutex);
        if (g_pending_surface_announcements.empty()
                || g_pending_surface_announcements.back() != text) {
            g_pending_surface_announcements.push_back(text);
        }
        if (text == "系统桌面") g_home_announcement_pending.store(true,
                std::memory_order_release);
        return;
    }
    if (sendAnnouncement(-1, text)) return;
    std::lock_guard lock(g_surface_announcement_mutex);
    if (g_pending_surface_announcements.empty()
            || g_pending_surface_announcements.back() != text) {
        g_pending_surface_announcements.push_back(text);
    }
    if (text == "系统桌面") g_home_announcement_pending.store(true,
            std::memory_order_release);
}

void flushSurfaceAnnouncements() {
    if (g_launcher_window_focused.load(std::memory_order_acquire) == 0) return;
    if (g_focus_callback_active.load(std::memory_order_acquire)) return;
    for (;;) {
        std::string text;
        {
            std::lock_guard lock(g_surface_announcement_mutex);
            if (g_pending_surface_announcements.empty()) return;
            text = g_pending_surface_announcements.front();
            g_pending_surface_announcements.pop_front();
        }
        if (sendAnnouncement(-1, text)) {
            if (text == "系统桌面") g_home_announcement_pending.store(false,
                    std::memory_order_release);
            continue;
        }
        std::lock_guard lock(g_surface_announcement_mutex);
        g_pending_surface_announcements.push_front(std::move(text));
        return;
    }
}

void handleRecentSwipeMotion(int32_t action, float x, float y) {
    if (static_cast<SurfaceState>(g_surface_state.load(std::memory_order_acquire))
            != SurfaceState::kRecents) {
        std::lock_guard lock(g_recent_swipe_mutex);
        g_recent_swipe_tracking = false;
        g_recent_swipe_name.clear();
        return;
    }
    if (action == 0) {
        std::string name;
        void *provider = g_last_provider.load(std::memory_order_acquire);
        if (provider != nullptr && g_original_create_node != nullptr) {
            std::vector<ObservedNode> nodes;
            readTree(provider, -1, nodes);
            for (const auto &node : nodes) {
                if (isRecentTaskLabel(node.label)
                        && x >= node.bounds.left && x < node.bounds.right
                        && y >= node.bounds.top && y < node.bounds.bottom) {
                    name = recentCardName(node.label);
                    break;
                }
            }
        }
        std::lock_guard lock(g_recent_swipe_mutex);
        g_recent_swipe_tracking = !name.empty();
        g_recent_swipe_start_y = y;
        g_recent_swipe_name = std::move(name);
        return;
    }
    if (action == 2 || action == 5) return;
    if (action != 1) return;
    std::string name;
    float startY = 0.0f;
    {
        std::lock_guard lock(g_recent_swipe_mutex);
        if (!g_recent_swipe_tracking) return;
        g_recent_swipe_tracking = false;
        name = std::move(g_recent_swipe_name);
        startY = g_recent_swipe_start_y;
    }
    if (name.empty() || startY - y < 200.0f) return;
    std::lock_guard lock(g_recent_state_mutex);
    if (g_pending_recent_removals.find(name) == g_pending_recent_removals.end()) {
        ++g_pending_recent_removals[name];
    }
}

int32_t virtualNodeAt(float x, float y) {
    std::lock_guard lock(g_session_mutex);
    for (size_t index = 0; index < g_slots.size(); ++index) {
        const Slot &slot = g_slots[index];
        if (x >= slot.bounds.left && x < slot.bounds.right
                && y >= slot.bounds.top && y < slot.bounds.bottom) {
            return virtualIdForSlot(slot);
        }
    }
    return -1;
}

bool updateTouchExplorationTarget(int32_t action, float x, float y) {
    if (!g_lifted.load(std::memory_order_acquire) || !moveActionsReady()) return false;
    constexpr int32_t kActionHoverMove = 7;
    constexpr int32_t kActionHoverEnter = 9;
    constexpr int32_t kActionHoverExit = 10;
    if (action != kActionHoverMove && action != kActionHoverEnter
            && action != kActionHoverExit) return false;
    if (action == kActionHoverExit) {
        const int32_t previous = g_virtual_hovered_id.exchange(-1,
                std::memory_order_acq_rel);
        if (isVirtualNode(previous)) {
            sendEvent(previous, 256);
        }
        // Touch exploration ends before the user's double tap. Keep its target.
        return isVirtualNode(previous);
    }
    const int32_t target = virtualNodeAt(x, y);
    const int32_t previous = g_virtual_hovered_id.exchange(target,
            std::memory_order_acq_rel);
    if (previous != target && isVirtualNode(previous)) sendEvent(previous, 256);
    if (!isVirtualNode(target)) {
        const int32_t focused = g_virtual_focused_id.exchange(-1);
        if (isVirtualNode(focused)) sendEvent(focused, 65536);
        return false;
    }
    if (previous == target) return true;
    const int32_t focused = g_virtual_focused_id.exchange(target);
    if (focused != target && isVirtualNode(focused)) sendEvent(focused, 65536);
    sendEvent(target, 128); // TYPE_VIEW_HOVER_ENTER
    if (focused != target) sendEvent(target, 32768);
    return true;
}

void onLauncherMotion(void *scene, void *event) {
    if (event != nullptr && g_motion_action_masked != nullptr
            && g_motion_raw_x != nullptr && g_motion_raw_y != nullptr) {
        const int32_t action = g_motion_action_masked(event);
        const float x = g_motion_raw_x(event, 0);
        const float y = g_motion_raw_y(event, 0);
        const bool wasVirtual = isVirtualNode(g_virtual_hovered_id.load());
        if (!wasVirtual && (action == 7 || action == 9) && g_lifted.load()
                && moveActionsReady() && isVirtualNode(virtualNodeAt(x, y))) {
            // Clear native hover before announcing the virtual target; forwarding
            // the original hover would let the icon behind the empty cell win.
            if (g_original_launcher_motion != nullptr && g_motion_clone != nullptr
                    && g_motion_drop != nullptr && g_motion_set_action != nullptr) {
                void *exit = g_motion_clone(event);
                if (exit != nullptr) {
                    g_motion_set_action(exit, 10);
                    g_original_launcher_motion(scene, exit);
                    g_motion_drop(exit);
                }
            }
        }
        if (updateTouchExplorationTarget(action, x, y)) return;
        handleRecentSwipeMotion(action, x, y);
    }
    if (g_original_launcher_motion != nullptr) g_original_launcher_motion(scene, event);
}

bool isVirtualNode(int32_t nodeId) {
    return nodeId >= kVirtualNodeBase && nodeId < kVirtualNodeBase + kVirtualNodeCount;
}

AccessibilityEventSource captureEventSource(int32_t nodeId) {
    if (isVirtualNode(nodeId)) {
        return {
                g_drop_parent_host_id.load(std::memory_order_acquire),
                g_drop_parent_window_id.load(std::memory_order_acquire), nodeId};
    }
    return {
            g_host_view_id.load(std::memory_order_acquire),
            g_window_id.load(std::memory_order_acquire), nodeId};
}

AccessibilityEventSource captureDropParentEventSource(int32_t fallbackNodeId) {
    const int32_t parent = g_drop_parent_node_id.load(std::memory_order_acquire);
    if (parent < 0) return captureEventSource(fallbackNodeId);
    return {
            g_drop_parent_host_id.load(std::memory_order_acquire),
            g_drop_parent_window_id.load(std::memory_order_acquire), parent};
}

std::string virtualNodeDescription(int32_t nodeId) {
    if (!isVirtualNode(nodeId)) return {};
    std::lock_guard lock(g_session_mutex);
    const int32_t slot = slotIndexForVirtualIdLocked(nodeId);
    if (slot < 0 || slot >= static_cast<int32_t>(g_slots.size())) return {};
    return "移至第 " + std::to_string(g_slots[slot].row + 1)
            + " 行第 " + std::to_string(g_slots[slot].column + 1) + " 列";
}

uint64_t packBounds(int32_t first, int32_t second) {
    return static_cast<uint32_t>(first)
            | (static_cast<uint64_t>(static_cast<uint32_t>(second)) << 32U);
}

int32_t gridNodeAtCellLocked(int32_t row, int32_t column) {
    for (const auto &node : g_workspace) {
        if (!node.icon() || hotseatNodeLocked(node)) continue;
        for (const auto &cell : g_grid_cells) {
            if (cell.row != row || cell.column != column) continue;
            const int32_t centerX = node.bounds.centerX();
            const int32_t centerY = node.bounds.centerY();
            if (centerX >= cell.bounds.left && centerX < cell.bounds.right
                    && centerY >= cell.bounds.top && centerY < cell.bounds.bottom) {
                return node.id;
            }
        }
    }
    for (const auto &slot : g_slots) {
        if (slot.row == row && slot.column == column) {
            return virtualIdForSlot(slot);
        }
    }
    return -1;
}

int32_t previousGridNodeLocked(int32_t row, int32_t column) {
    int32_t previous = -1;
    for (const auto &cell : g_grid_cells) {
        if (cell.row > row || (cell.row == row && cell.column >= column)) break;
        const int32_t candidate = gridNodeAtCellLocked(cell.row, cell.column);
        if (candidate >= 0) previous = candidate;
    }
    return previous;
}

int32_t nextGridNodeLocked(int32_t row, int32_t column) {
    for (const auto &cell : g_grid_cells) {
        if (cell.row < row || (cell.row == row && cell.column <= column)) continue;
        const int32_t candidate = gridNodeAtCellLocked(cell.row, cell.column);
        if (candidate >= 0) return candidate;
    }
    return -1;
}

void rememberCurrentGridNode(void *node) {
    const ObservedNode current = describeNode(node);
    if (!current.icon()) return;
    std::lock_guard lock(g_session_mutex);
    for (auto &previous : g_workspace) {
        if (previous.resource != current.resource
                || (!labelMatches(previous.label, current.label)
                        && !labelMatches(current.label, previous.label))
                || previous.bounds.centerX() != current.bounds.centerX()
                || previous.bounds.centerY() != current.bounds.centerY()) continue;
        if (g_lifted_screen.load() == g_visible_screen.load()) {
            for (auto &source : g_sources) {
                if (source.id == previous.id) source = current;
            }
            if (g_source.id == previous.id) g_source = current;
        }
        previous = current;
        return;
    }
}

void setTraversalAfterForCell(void *node, int32_t row, int32_t column,
        int32_t fallbackHost) {
    if (node == nullptr || g_node_set_traversal_after == nullptr) return;
    int32_t previous = -1;
    int32_t next = -1;
    int32_t host = fallbackHost;
    int32_t nextHost = fallbackHost;
    {
        std::lock_guard lock(g_session_mutex);
        previous = previousGridNodeLocked(row, column);
        next = nextGridNodeLocked(row, column);
        for (const auto &candidate : g_workspace) {
            if (candidate.id == previous) {
                host = candidate.host;
            }
            if (candidate.id == next) nextHost = candidate.host;
        }
    }
    if (previous >= 0) {
        g_node_set_traversal_after(node, host, previous);
    }
    if (next >= 0 && g_node_set_traversal_before != nullptr) {
        g_node_set_traversal_before(node, nextHost, next);
    }
}

void setTraversalAfterForVirtualNode(void *node, int32_t slot) {
    if (node == nullptr || g_node_set_traversal_after == nullptr) return;
    int32_t row = -1, column = -1;
    {
        std::lock_guard lock(g_session_mutex);
        if (slot < 0 || slot >= static_cast<int32_t>(g_slots.size())) return;
        const Slot &target = g_slots[static_cast<size_t>(slot)];
        row = target.row;
        column = target.column;
    }
    const int32_t host = g_drop_parent_host_id.load(std::memory_order_acquire);
    setTraversalAfterForCell(node, row, column, host);
}

void setTraversalAfterForRealNode(void *node) {
    if (node == nullptr || g_node_set_traversal_after == nullptr) return;
    const Bounds bounds = nodeField<Bounds>(node, kNodeBoundsOffset);
    int32_t row = -1;
    int32_t column = -1;
    {
        std::lock_guard lock(g_session_mutex);
        if (hotseatNodeLocked(describeNode(node))) return;
        for (const auto &cell : g_grid_cells) {
            if (bounds.centerX() >= cell.bounds.left && bounds.centerX() < cell.bounds.right
                    && bounds.centerY() >= cell.bounds.top && bounds.centerY() < cell.bounds.bottom) {
                row = cell.row;
                column = cell.column;
                break;
            }
        }
    }
    if (row < 0 || column < 0) return;
    const int64_t source = nodeField<int64_t>(node, 0x338);
    setTraversalAfterForCell(node, row, column, static_cast<int32_t>(source));
}

void repairFolderBounds(void *node, int32_t sourceId) {
    if (node == nullptr || g_node_set_bounds == nullptr) return;
    const Bounds current = nodeField<Bounds>(node, kNodeBoundsOffset);
    if (current.right > current.left && current.bottom > current.top) return;
    const std::string label = nodeString(node, 0x138);
    Bounds repaired{};
    Bounds parent{};
    int32_t parentId = -1;
    {
        std::lock_guard lock(g_session_mutex);
        for (const auto &candidate : g_workspace) {
            if (candidate.id == sourceId && candidate.resource == "com.miui.home:id/folder"
                    && (label.empty() || candidate.label == label)
                    && candidate.bounds.right > candidate.bounds.left
                    && candidate.bounds.bottom > candidate.bounds.top) {
                repaired = candidate.bounds;
                parentId = candidate.parent;
                break;
            }
        }
        for (const auto &candidate : g_workspace) {
            if (candidate.id == parentId) {
                parent = candidate.bounds;
                break;
            }
        }
    }
    if (repaired.right <= repaired.left || repaired.bottom <= repaired.top) return;
    g_node_set_bounds(node, packBounds(repaired.left, repaired.top),
            packBounds(repaired.right, repaired.bottom));
    if (g_node_set_parent_bounds != nullptr && parent.right > parent.left
            && parent.bottom > parent.top) {
        g_node_set_parent_bounds(node,
                packBounds(repaired.left - parent.left, repaired.top - parent.top),
                packBounds(repaired.right - parent.left, repaired.bottom - parent.top));
    }
}

void setNodeString(NodeSetStringFn setter, void *node, const char16_t *text) {
    if (setter == nullptr || g_rstring_from_utf16 == nullptr || node == nullptr) return;
    RustRString value = g_rstring_from_utf16(
            reinterpret_cast<const uint16_t *>(text), std::char_traits<char16_t>::length(text));
    setter(node, &value);
}

NodeResult makeVirtualNode(int32_t nodeId) {
    NodeResult result{1U, nullptr};
    if (g_node_new == nullptr || !isVirtualNode(nodeId) || !moveActionsReady()) return result;
    void *node = g_node_new();
    if (node == nullptr) return result;
    int32_t slot = -1;
    Bounds cell{};
    Bounds parentBounds{};
    int32_t parentNodeId = -1;
    {
        std::lock_guard lock(g_session_mutex);
        slot = slotIndexForVirtualIdLocked(nodeId);
        if (slot < 0 || slot >= static_cast<int32_t>(g_slots.size())) {
            if (g_node_drop != nullptr) g_node_drop(node);
            return result;
        }
        cell = g_slots[slot].bounds;
        parentNodeId = g_drop_parent_node_id.load(std::memory_order_acquire);
        for (const auto &workspaceNode : g_workspace) {
            if (workspaceNode.id == parentNodeId) {
                parentBounds = workspaceNode.bounds;
                break;
            }
        }
    }
    setNodeString(g_node_set_package_name, node, u"com.miui.home");
    setNodeString(g_node_set_class_name, node, u"android.view.View");
    const std::u16string description = utf16FromUtf8(virtualNodeDescription(nodeId));
    setNodeString(g_node_set_content_description, node, description.c_str());
    if (g_node_set_source != nullptr) {
        const int32_t host = g_drop_parent_host_id.load(std::memory_order_acquire);
        const int32_t window = g_drop_parent_window_id.load(std::memory_order_acquire);
        g_node_set_source(node, host, nodeId, window);
        if (g_node_set_parent != nullptr) g_node_set_parent(node, host, parentNodeId);
    }
    if (g_node_set_bounds != nullptr) {
        g_node_set_bounds(node, packBounds(cell.left, cell.top),
                packBounds(cell.right, cell.bottom));
    }
    if (g_node_set_parent_bounds != nullptr && parentBounds.right > parentBounds.left
            && parentBounds.bottom > parentBounds.top) {
        g_node_set_parent_bounds(node,
                packBounds(cell.left - parentBounds.left, cell.top - parentBounds.top),
                packBounds(cell.right - parentBounds.left, cell.bottom - parentBounds.top));
    }
    if (g_node_set_visible != nullptr) g_node_set_visible(node, true);
    if (g_node_set_enabled != nullptr) g_node_set_enabled(node, true);
    if (g_node_set_clickable != nullptr) g_node_set_clickable(node, true);
    if (g_node_set_focusable != nullptr) g_node_set_focusable(node, true);
    if (g_node_set_important != nullptr) g_node_set_important(node, true);
    if (g_node_set_accessibility_focused != nullptr) {
        g_node_set_accessibility_focused(node,
                g_virtual_focused_id.load(std::memory_order_acquire) == nodeId);
    }
    setTraversalAfterForVirtualNode(node, slot);
    if (g_action_new != nullptr && g_action_drop != nullptr
            && g_rstring_from_utf16 != nullptr && g_original_add_action != nullptr) {
        auto addAction = [node](int32_t actionId, const char16_t *label) {
            RustRString value = g_rstring_from_utf16(
                    reinterpret_cast<const uint16_t *>(label),
                    std::char_traits<char16_t>::length(label));
            void *action = g_action_new(actionId, &value);
            if (action != nullptr) {
                g_original_add_action(node, action);
                g_action_drop(action);
            }
        };
        addAction(16, u"点击");
        if (g_virtual_focused_id.load(std::memory_order_acquire) == nodeId) {
            addAction(kClearAccessibilityFocusAction, u"清除无障碍焦点");
        } else {
            addAction(kAccessibilityFocusAction, u"获取无障碍焦点");
        }
    }
    result.absent = 0;
    result.node = node;
    return result;
}

NodeResult onFindFocus(void *provider, int32_t focusType) {
    constexpr int32_t kAccessibilityFocus = 2;
    if (focusType == kAccessibilityFocus
            && g_lifted.load(std::memory_order_acquire) && moveActionsReady()) {
        int32_t focused = g_virtual_focused_id.load(std::memory_order_acquire);
        if (isVirtualNode(focused)) return makeVirtualNode(focused);
    }
    return g_original_find_focus == nullptr
            ? NodeResult{1U, nullptr} : g_original_find_focus(provider, focusType);
}

void orderGridChildren(void *node) {
    // The verified AIDL Option<Vec<i64>> owns this buffer; reorder its IDs in
    // place so readers using getChild(index) see the same order as touch.
    if ((nodeField<uint64_t>(node, 0x30) >> 63U) != 0) return;
    const size_t count = nodeField<size_t>(node, 0x40);
    auto *children = nodeField<int64_t *>(node, 0x38);
    if (children == nullptr || count == 0 || count > nodeField<size_t>(node, 0x30)
            || count > 512 + kVirtualNodeCount) return;
    std::lock_guard lock(g_session_mutex);
    auto rank = [&](int64_t child) {
        const int32_t id = static_cast<int32_t>(child >> 32U);
        if (isVirtualNode(id)) return id - kVirtualNodeBase;
        int32_t first = kVirtualNodeCount;
        for (const auto &item : g_workspace) {
            if (!item.icon() || hotseatNodeLocked(item)
                    || (item.id != id && !descendsFromLocked(item, id))) continue;
            for (const auto &cell : g_grid_cells) {
                if (item.bounds.centerX() >= cell.bounds.left && item.bounds.centerX() < cell.bounds.right
                        && item.bounds.centerY() >= cell.bounds.top && item.bounds.centerY() < cell.bounds.bottom) {
                    first = std::min(first, cell.row * g_grid_columns.load() + cell.column);
                }
            }
        }
        if (first != kVirtualNodeCount) return first;
        for (const auto &item : g_workspace) {
            if (item.id != id || hotseatNodeLocked(item)) continue;
            for (const auto &cell : g_grid_cells) {
                if (item.bounds.left >= cell.bounds.left && item.bounds.left < cell.bounds.right
                        && item.bounds.top >= cell.bounds.top && item.bounds.top < cell.bounds.bottom) {
                    return cell.row * g_grid_columns.load() + cell.column;
                }
            }
        }
        return first;
    };
    auto *end = std::remove_if(children, children + count, [&](int64_t child) {
        const int32_t id = static_cast<int32_t>(child >> 32U);
        return isVirtualNode(id) && slotIndexForVirtualIdLocked(id) < 0;
    });
    std::stable_sort(children, end, [&](int64_t left, int64_t right) { return rank(left) < rank(right); });
    const size_t length = static_cast<size_t>(std::unique(children, end) - children);
    std::memcpy(static_cast<uint8_t *>(node) + 0x40, &length, sizeof(length));
}

void traceDiagnosticsNode(void *node, int32_t nodeId, uint32_t before) {
    if (g_diagnostics.session() == 0 || !isIconNode(node)) return;
    int budget = g_diagnostics_node_budget.load();
    while (budget > 0) {
        if (!g_diagnostics_node_budget.compare_exchange_weak(budget, budget - 1)) continue;
        const Bounds bounds = nodeField<Bounds>(node, kNodeBoundsOffset);
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "icon actions node=%d focus=%d page=%d bounds=%d,%d,%d,%d added=%u cap=0x%x injectors=%d",
                nodeId, g_launcher_window_focused.load(), g_visible_screen.load() + 1,
                bounds.left, bounds.top, bounds.right, bounds.bottom,
                g_custom_action_calls.load() - before, g_capabilities.load(),
                g_original_add_action && g_action_new && g_action_drop && g_rstring_from_utf16 ? 1 : 0);
        break;
    }
}

NodeResult onCreateNode(void *provider, int32_t nodeId) {
    if (isVirtualNode(nodeId)) {
        return g_lifted.load(std::memory_order_acquire) && moveActionsReady()
                ? makeVirtualNode(nodeId) : NodeResult{1U, nullptr};
    }
    NodeResult result = g_original_create_node == nullptr
            ? NodeResult{1U, nullptr} : g_original_create_node(provider, nodeId);
    if ((result.absent & 1U) != 0 || result.node == nullptr) return result;
    if (g_focus_callback_active.load(std::memory_order_acquire)) {
        g_focus_callback_node_observed.store(true, std::memory_order_release);
    }
    const uint32_t actionsBefore = g_custom_action_calls.load();
    if (g_launcher_window_focused.load(std::memory_order_acquire) == 0) {
        // Home can build/cache these nodes inside its focus callback, before
        // our foreground state changes. Their actions must already be present.
        injectIconActions(result.node);
        injectRecentActions(result.node);
        traceDiagnosticsNode(result.node, nodeId, actionsBefore);
        return result;
    }
    if ((result.absent & 1U) == 0 && result.node != nullptr) {
        int64_t source = nodeField<int64_t>(result.node, 0x338);
        g_host_view_id.store(static_cast<int32_t>(source));
        g_window_id.store(nodeField<int32_t>(result.node, 0x418));
        observeSurfaceNode(provider, nodeId, result.node);
        observePendingPlacement(describeNode(result.node));
        if (nodeId == -1) {
            // A root query is a safe provider lifetime boundary.  Build the
            // surface snapshot here instead of handing this raw provider to a
            // background poller after the accessibility call has returned.
            std::vector<ObservedNode> nodes;
            readTree(provider, -1, nodes);
            observePendingPlacements(nodes);
            if (!nodes.empty()) observeSurfaceTree(provider, nodes);
        }
        const std::string pageDescription = nodeString(result.node, 0x138);
        const bool lifted = g_lifted.load(std::memory_order_acquire);
        const bool hadPage = g_page_state_known.load(std::memory_order_acquire);
        const bool pageChanged = rememberVisibleScreen(pageDescription);
        rememberPageSource(result.node);
        if (pageChanged && hadPage) {
            announceConfirmedPage(g_visible_screen.load() + 1,
                    {static_cast<int32_t>(source), nodeField<int32_t>(result.node, 0x418),
                            static_cast<int32_t>(source >> 32U)});
        }
        if (pageChanged && g_lifted.load(std::memory_order_acquire)) {
            refreshLiftSessionForPage(provider);
        }
        bool knownPage = false;
        if (lifted) {
            std::lock_guard lock(g_session_mutex);
            knownPage = std::any_of(g_workspace.begin(), g_workspace.end(), [&](const auto &item) {
                return item.id == nodeId && (item.resource == "com.miui.home:id/screen_content"
                        || item.resource == "com.miui.home:id/workspace");
            });
        }
        const std::string resource = nodeString(result.node, 0x1b8);
        if (lifted && (resource == "com.miui.home:id/screen_content"
                || resource == "com.miui.home:id/workspace") && !knownPage) {
            refreshLiftSessionForPage(provider);
        }
        if (g_lifted.load(std::memory_order_acquire)) rememberCurrentGridNode(result.node);
        injectIconActions(result.node);
        if (g_lifted.load(std::memory_order_acquire) && isIconNode(result.node)) {
            setTraversalAfterForRealNode(result.node);
        }
        injectRecentActions(result.node);
    }
    if ((result.absent & 1U) == 0 && result.node != nullptr
            && g_lifted.load(std::memory_order_acquire)
            && moveActionsReady()
            && nodeId == g_drop_parent_node_id.load(std::memory_order_acquire)
            && g_node_add_child != nullptr) {
        // HyperOS 4 may allocate a fresh provider node id on every tree read.
        // Bind the virtual children to the currently returned visible page.
        g_drop_parent_host_id.store(static_cast<int32_t>(nodeField<int64_t>(result.node, 0x338)));
        g_drop_parent_window_id.store(nodeField<int32_t>(result.node, 0x418));
        std::vector<int32_t> slotIds;
        {
            std::lock_guard lock(g_session_mutex);
            for (const auto &slot : g_slots) slotIds.push_back(virtualIdForSlot(slot));
        }
        for (int32_t id : slotIds) {
            g_node_add_child(result.node,
                    g_drop_parent_host_id.load(std::memory_order_acquire),
                    id);
        }
        orderGridChildren(result.node);
    }
    flushSurfaceAnnouncements();
    traceDiagnosticsNode(result.node, nodeId, actionsBefore);
    return result;
}

void injectDirectAction(void *node, int32_t id, const char16_t *label) {
    if (node == nullptr || g_original_add_action == nullptr || g_action_new == nullptr
            || g_action_drop == nullptr || g_rstring_from_utf16 == nullptr) {
        return;
    }
    // The public ABI consumes the RString. Keeping this local is intentional:
    // AccessibilityAction_new copies it into its owned StringCharSequence.
    RustRString string = g_rstring_from_utf16(
            reinterpret_cast<const uint16_t *>(label),
            std::char_traits<char16_t>::length(label));
    void *action = g_action_new(id, &string);
    if (action != nullptr) {
        g_original_add_action(node, action);
        g_action_drop(action);
        if (g_custom_action_calls.fetch_add(1, std::memory_order_relaxed) == 0) {
            nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                    "Rust direct action injected id=%d", id);
        }
    } else {
        nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                "Rust direct action constructor returned null id=%d", id);
    }
}

void injectDirectActionText(void *node, int32_t id, const std::u16string &label) {
    if (label.empty() || node == nullptr || g_original_add_action == nullptr
            || g_action_new == nullptr || g_action_drop == nullptr
            || g_rstring_from_utf16 == nullptr) return;
    RustRString string = g_rstring_from_utf16(
            reinterpret_cast<const uint16_t *>(label.data()), label.size());
    void *action = g_action_new(id, &string);
    if (action != nullptr) {
        g_original_add_action(node, action);
        g_action_drop(action);
        g_custom_action_calls.fetch_add(1, std::memory_order_relaxed);
    }
}

AppTarget targetForNode(int32_t nodeId, void *node) {
    AppTarget target;
    if (node == nullptr) return target;
    std::string label = nodeString(node, 0x138);
    const int32_t screenHint = g_visible_screen.load(std::memory_order_acquire) + 1;
    uint64_t generation = 0;
    {
        std::lock_guard lock(g_snapshot_mutex);
        generation = g_snapshot.generation;
    }
    {
        std::lock_guard lock(g_app_mutex);
        auto found = g_app_targets.find(nodeId);
        if (found != g_app_targets.end() && found->second.spoken == label
                && found->second.snapshotGeneration == generation
                && (screenHint < 1 || found->second.placement.screen == screenHint)) {
            target = found->second;
            if (!target.shortcutsLoaded) queueShortcutLoad(nodeId, target);
            return target;
        }
    }
    if (!resolveAppTarget(nodeId, label, target, screenHint)) return target;
    {
        std::lock_guard lock(g_app_mutex);
        g_app_targets[nodeId] = target;
    }
    queueShortcutLoad(nodeId, target);
    return target;
}

bool isIconNode(void *node) {
    if (node == nullptr) return false;
    std::string resource = nodeString(node, 0x1b8);
    if (resource != "com.miui.home:id/icon_icon" && resource != "com.miui.home:id/folder") {
        return false;
    }
    int32_t bounds[4]{};
    std::memcpy(bounds, static_cast<const uint8_t *>(node) + kNodeBoundsOffset,
            sizeof(bounds));
    int32_t width = bounds[2] - bounds[0];
    int32_t height = bounds[3] - bounds[1];
    return bounds[0] >= 0 && bounds[1] >= 0 && width >= 80 && height >= 80
            && width <= 500 && height <= 500;
}

int64_t uptimeMillis() {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return now.tv_sec * 1000LL + now.tv_nsec / 1000000LL;
}

bool injectTouch(int64_t down, int32_t action, float x, float y) {
    void *motion = g_motion_new(down, uptimeMillis(), action, x, y, 0x1002);
    if (motion == nullptr) return false;
    // newWithMotionEvent consumes the MotionEvent allocation.
    void *event = g_input_event(motion);
    if (event == nullptr) return false;
    uint64_t result = g_inject_input(event, 0, -1);
    g_input_drop(event);
    return (result & 1U) != 0;
}

bool nodeBounds(void *provider, int32_t nodeId, Bounds &bounds, std::string *label = nullptr) {
    if (g_create_node == nullptr || g_node_drop == nullptr) return false;
    NodeResult result = g_create_node(provider, nodeId);
    if ((result.absent & 1U) != 0 || result.node == nullptr) return false;
    bounds = nodeField<Bounds>(result.node, kNodeBoundsOffset);
    if (label != nullptr) *label = nodeString(result.node, 0x138);
    g_node_drop(result.node);
    return bounds.left >= 0 && bounds.top >= 0
            && bounds.right > bounds.left && bounds.bottom > bounds.top;
}

bool isFolderNode(void *provider, int32_t nodeId) {
    if (g_create_node == nullptr || g_node_drop == nullptr) return false;
    NodeResult result = g_create_node(provider, nodeId);
    if ((result.absent & 1U) != 0 || result.node == nullptr) return false;
    const bool folder = nodeString(result.node, 0x1b8) == "com.miui.home:id/folder";
    g_node_drop(result.node);
    return folder;
}

bool isDesktopIconNode(void *provider, int32_t nodeId, ObservedNode *observed = nullptr) {
    if (g_create_node == nullptr || g_node_drop == nullptr) return false;
    NodeResult result = g_create_node(provider, nodeId);
    if ((result.absent & 1U) != 0 || result.node == nullptr) return false;
    const std::string resource = nodeString(result.node, 0x1b8);
    const bool icon = resource == "com.miui.home:id/folder" || isIconNode(result.node);
    if (icon && observed != nullptr) *observed = describeNode(result.node);
    g_node_drop(result.node);
    return icon;
}

bool isRecentTaskLabel(const std::string &label) {
    return label.find("未加锁") != std::string::npos
            || label.find("未锁定") != std::string::npos
            || label.find("已加锁") != std::string::npos
            || label.find("已锁定") != std::string::npos;
}

bool isEmptyRecentsLabel(const std::string &label) {
    return label.find("近期没有") != std::string::npos
            || label.find("没有任何内容") != std::string::npos;
}

std::string recentCardName(const std::string &label) {
    const size_t comma = label.find(',');
    return comma == std::string::npos ? label : label.substr(0, comma);
}

std::unordered_map<std::string, int32_t> recentCardCounts(
        const std::vector<ObservedNode> &nodes) {
    std::unordered_map<std::string, int32_t> counts;
    for (const auto &node : nodes) {
        if (!isRecentTaskLabel(node.label)) continue;
        const std::string name = recentCardName(node.label);
        if (!name.empty()) ++counts[name];
    }
    return counts;
}

bool hasRecentSurface(const std::vector<ObservedNode> &nodes) {
    return std::any_of(nodes.begin(), nodes.end(), [](const auto &node) {
        return isRecentTaskLabel(node.label)
                || isEmptyRecentsLabel(node.label)
                || node.resource == "com.miui.home:id/clearAnimView";
    });
}

bool hasWorkspaceSurface(const std::vector<ObservedNode> &nodes) {
    return std::any_of(nodes.begin(), nodes.end(), [](const auto &node) {
        return node.resource == "com.miui.home:id/workspace"
                || node.resource == "com.miui.home:id/screen_content"
                || looksLikePageIndicator(node.label);
    });
}

void clearWorkspacePageContext() {
    {
        std::lock_guard lock(g_page_announcement_mutex);
        g_announced_page = -1;
    }
    {
        std::lock_guard lock(g_event_mutex);
        g_page_source_keys.clear();
    }
    g_workspace_window_id.store(-1, std::memory_order_release);
    g_visible_screen.store(0, std::memory_order_release);
    g_total_pages.store(0, std::memory_order_release);
    g_page_state_known.store(false, std::memory_order_release);
}

void resetRecentSnapshot() {
    std::lock_guard lock(g_recent_state_mutex);
    g_recent_card_counts.clear();
    g_recent_candidate_counts.clear();
    g_recent_candidate_repeats = 0;
    g_recent_snapshot_initialized = false;
}

void rememberRecentRemoval(const std::string &name) {
    if (name.empty()) return;
    std::lock_guard lock(g_recent_state_mutex);
    ++g_pending_recent_removals[name];
}

void acknowledgeRecentRemoval(const std::string &name) {
    std::lock_guard lock(g_recent_state_mutex);
    auto it = g_pending_recent_removals.find(name);
    if (it == g_pending_recent_removals.end()) return;
    if (--it->second <= 0) g_pending_recent_removals.erase(it);
}

std::vector<std::string> takePendingRecentRemovals() {
    std::lock_guard lock(g_recent_state_mutex);
    std::vector<std::string> names;
    if (!g_recent_snapshot_initialized) {
        g_pending_recent_removals.clear();
        return names;
    }
    for (const auto &[name, count] : g_pending_recent_removals) {
        if (g_recent_card_counts.find(name) != g_recent_card_counts.end()) continue;
        for (int32_t index = 0; index < count; ++index) names.push_back(name);
    }
    g_pending_recent_removals.clear();
    return names;
}

void observeRecentSnapshot(void *provider, const std::vector<ObservedNode> &nodes);

void setSurfaceState(SurfaceState next, void *provider) {
    const SurfaceState previous = static_cast<SurfaceState>(
            g_surface_state.exchange(static_cast<int32_t>(next), std::memory_order_acq_rel));
    g_last_provider.store(provider, std::memory_order_release);
    if (next == SurfaceState::kRecents) {
        if (previous != SurfaceState::kRecents) {
            if (g_lifted.load(std::memory_order_acquire)) clearLiftSession();
            clearWorkspacePageContext();
            {
                std::lock_guard lock(g_recent_state_mutex);
                g_pending_recent_removals.clear();
            }
            resetRecentSnapshot();
            queueSurfaceAnnouncement("进入多任务界面");
        }
        return;
    }
    if (next == SurfaceState::kWorkspace) {
        if (previous != SurfaceState::kWorkspace) {
            if (previous == SurfaceState::kRecents) {
                for (const auto &name : takePendingRecentRemovals()) {
                    queueSurfaceAnnouncement(name + "已清除");
                }
            }
            resetRecentSnapshot();
            if (previous == SurfaceState::kRecents || previous == SurfaceState::kOther) {
                queueSurfaceAnnouncement("系统桌面");
            }
        }
        return;
    }
    if (next == SurfaceState::kOther && previous != SurfaceState::kOther) {
        clearWorkspacePageContext();
        resetRecentSnapshot();
    }
}

void onLauncherWindowFocus(void *window, bool focused) {
    if (focused && g_diagnostics.session() != 0) g_diagnostics_node_budget.store(8);
    if (!focused) g_focus_generation.fetch_add(1, std::memory_order_acq_rel);
    // Mark Home focused before its callback rebuilds cached nodes.  Those
    // nodes are otherwise treated as background queries and never identify
    // the workspace surface.  Announcements raised inside the callback are
    // held until the callback returns (or until a later provider query).
    if (focused) {
        g_focus_callback_node_observed.store(false, std::memory_order_release);
        g_focus_callback_active.store(true, std::memory_order_release);
    }
    const int32_t previous = g_launcher_window_focused.exchange(focused ? 1 : 0,
            std::memory_order_acq_rel);
    if (focused && g_original_launcher_focus != nullptr) g_original_launcher_focus(window, true);
    nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
            "window focus %d -> %d lifted=%d page=%d cap=0x%x node_calls=%u injected=%u",
            previous, focused ? 1 : 0, g_lifted.load() ? 1 : 0, g_visible_screen.load() + 1,
            g_capabilities.load(), g_node_action_calls.load(), g_custom_action_calls.load());
    wakeDiagnosticsWorker();
    if (!focused && previous != 0) {
        g_resume_scans.store(0);
        g_consume_back_up.store(false);
        g_last_provider.store(nullptr, std::memory_order_release);
        g_event_controller.store(nullptr, std::memory_order_release);
        endPendingPlacement();
        if (g_lifted.load(std::memory_order_acquire)) {
            const auto parent = captureDropParentEventSource(-1);
            clearLiftSession();
            sendEvent(parent, kTypeWindowContentChanged);
        }
        setSurfaceState(SurfaceState::kOther, g_last_provider.load());
        {
            std::lock_guard lock(g_surface_mutex);
            g_has_recent_surface_time = false;
            g_surface_candidate = SurfaceState::kUnknown;
            g_surface_candidate_repeats = 0;
        }
        {
            std::lock_guard lock(g_surface_announcement_mutex);
            g_pending_surface_announcements.clear();
        }
        g_home_announcement_pending.store(false, std::memory_order_release);
    } else if (focused && previous != 1) {
        g_resume_scans.store(5);
        g_surface_scan_requested.store(true, std::memory_order_release);
    }
    if (focused) {
        g_focus_callback_active.store(false, std::memory_order_release);
        const uint64_t generation = g_focus_generation.fetch_add(1,
                std::memory_order_acq_rel) + 1;
        // If no node query follows a quick app-to-Home return, wait briefly
        // for a recents/workspace sample before speaking the desktop fallback.
        // A normal root query flushes the same pending item first.
        if (previous != 1 && (!g_focus_callback_node_observed.load(
                std::memory_order_acquire)
                || g_home_announcement_pending.load(std::memory_order_acquire))) {
            std::thread([generation] {
                std::this_thread::sleep_for(std::chrono::milliseconds(250));
                if (g_focus_generation.load(std::memory_order_acquire) != generation
                        || g_launcher_window_focused.load(std::memory_order_acquire) != 1) return;
                if (g_home_announcement_pending.load(std::memory_order_acquire)) {
                    flushSurfaceAnnouncements();
                    return;
                }
                const SurfaceState surface = static_cast<SurfaceState>(
                        g_surface_state.load(std::memory_order_acquire));
                if (surface == SurfaceState::kOther || surface == SurfaceState::kUnknown) {
                    queueSurfaceAnnouncement("系统桌面");
                    flushSurfaceAnnouncements();
                }
            }).detach();
        }
    }
    if (!focused && g_original_launcher_focus != nullptr) g_original_launcher_focus(window, false);
}

bool onLauncherKey(void *scene, void *event) {
    if (event != nullptr && g_key_code != nullptr && g_key_action != nullptr
            && g_key_code(event) == 4) {
        const int32_t action = g_key_action(event);
        if (action == 0 && (g_consume_back_up.load() || cancelLiftSession(-1))) {
            g_consume_back_up.store(true);
            return true;
        }
        if (action == 1 && g_consume_back_up.exchange(false)) return true;
        // Some accessibility services deliver only the key-up event.
        if (action == 1 && cancelLiftSession(-1)) return true;
    }
    return g_original_launcher_key != nullptr && g_original_launcher_key(scene, event);
}

void onLauncherBack(void *helper) {
    cancelLiftSession(-1);
    // The launcher owns the gesture callback and must finish its native cleanup.
    if (g_original_launcher_back != nullptr) g_original_launcher_back(helper);
}

void installLauncherLifecycleHooks(LauncherAbiProfile profile) {
    const bool current = profile == LauncherAbiProfile::kCurrent8565;
    if ((!current && profile != LauncherAbiProfile::kLegacy7748)
            || !g_launcher_fingerprint_ready.load() || !g_launcher_system_compatible.load()
            || g_api == nullptr || g_api->hook_func == nullptr) return;
    auto *base = static_cast<uint8_t *>(launcherLibraryBase());
    if (base == nullptr) return;
    if (g_key_code == nullptr) g_key_code = reinterpret_cast<MotionActionFn>(
            findLoadedSymbol("libhyper_os_ui_public.so", "input_KeyEvent_getKeyCode"));
    if (g_key_action == nullptr) g_key_action = reinterpret_cast<MotionActionFn>(
            findLoadedSymbol("libhyper_os_ui_public.so", "input_KeyEvent_getAction"));
    if (g_key_code == nullptr || g_key_action == nullptr
            || g_launcher_lifecycle_hook_attempted.exchange(true)) return;
    constexpr uint32_t focusPrologue[] = {0xd102c3ffU, 0xa9057bfdU, 0xf90033fbU,
            0xa90767faU, 0xa9085ff8U, 0xa90957f6U, 0xa90a4ff4U, 0x910143fdU};
    constexpr uint32_t inputPrologue[] = {0xd10483ffU, 0xa90c7bfdU, 0xa90d6ffcU,
            0xa90e67faU, 0xa90f5ff8U, 0xa91057f6U, 0xa9114ff4U, 0x910303fdU};
    auto install = [&](uintptr_t offset, const uint32_t *prologue, size_t size,
            void *replacement, const char *name) -> void * {
        void *target = base + offset;
        if (std::memcmp(target, prologue, size) != 0) return nullptr;
        void *backup = nullptr;
        const int result = g_api->hook_func(target, replacement, &backup);
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "Launcher %s hook result=%d", name, result);
        return result == 0 ? backup : nullptr;
    };
    g_original_launcher_focus = reinterpret_cast<LauncherFocusFn>(install(
            current ? 0x772360 : 0x757b34, focusPrologue, sizeof(focusPrologue),
            reinterpret_cast<void *>(&onLauncherWindowFocus), "window focus"));
    if (g_key_code != nullptr && g_key_action != nullptr) {
        g_original_launcher_key = reinterpret_cast<LauncherKeyFn>(install(
                current ? 0x97a484 : 0x9736ec, inputPrologue, sizeof(inputPrologue),
                reinterpret_cast<void *>(&onLauncherKey), "key"));
    }
    g_original_launcher_back = reinterpret_cast<LauncherBackFn>(install(
            current ? 0x725c54 : 0x720ffc, inputPrologue, sizeof(inputPrologue),
            reinterpret_cast<void *>(&onLauncherBack), "back gesture"));
}

void observeSurfaceSample(void *provider, SurfaceState observed,
        const std::vector<ObservedNode> &nodes) {
    if (g_launcher_window_focused.load(std::memory_order_acquire) == 0) return;
    if (observed == SurfaceState::kRecents) {
        std::lock_guard lock(g_surface_mutex);
        g_last_recent_surface = std::chrono::steady_clock::now();
        g_has_recent_surface_time = true;
    }
    bool stable = false;
    {
        std::lock_guard lock(g_surface_mutex);
        if (g_surface_candidate == observed) {
            ++g_surface_candidate_repeats;
        } else {
            g_surface_candidate = observed;
            g_surface_candidate_repeats = 1;
        }
        stable = observed == SurfaceState::kRecents
                || observed == SurfaceState::kWorkspace
                || g_surface_candidate_repeats >= 2;
    }
    if (!stable) return;
    const auto current = static_cast<SurfaceState>(
            g_surface_state.load(std::memory_order_acquire));
    if (current != observed) setSurfaceState(observed, provider);
    if (observed == SurfaceState::kRecents && !nodes.empty()) {
        observeRecentSnapshot(provider, nodes);
    }
}

void observeRecentSnapshot(void *provider, const std::vector<ObservedNode> &nodes) {
    if (static_cast<SurfaceState>(g_surface_state.load(std::memory_order_acquire))
            != SurfaceState::kRecents) return;
    const auto current = recentCardCounts(nodes);
    std::vector<std::string> removed;
    {
        std::lock_guard lock(g_recent_state_mutex);
        g_last_provider.store(provider, std::memory_order_release);
        if (!g_recent_snapshot_initialized) {
            g_recent_card_counts = current;
            g_recent_candidate_counts = current;
            g_recent_candidate_repeats = 1;
            g_recent_snapshot_initialized = true;
            return;
        }
        if (current == g_recent_card_counts) {
            g_recent_candidate_counts = current;
            g_recent_candidate_repeats = 1;
            return;
        }
        if (current != g_recent_candidate_counts) {
            g_recent_candidate_counts = current;
            g_recent_candidate_repeats = 1;
            return;
        }
        if (++g_recent_candidate_repeats < 2) return;
        for (const auto &[name, count] : g_recent_card_counts) {
            const int32_t now = current.count(name) == 0 ? 0 : current.at(name);
            for (int32_t index = now; index < count; ++index) removed.push_back(name);
        }
        g_recent_card_counts = current;
        g_recent_candidate_counts = current;
        g_recent_candidate_repeats = 1;
    }
    for (const auto &name : removed) {
        acknowledgeRecentRemoval(name);
        queueSurfaceAnnouncement(name + "已清除");
    }
}

void observeSurfaceTree(void *provider, const std::vector<ObservedNode> &nodes) {
    if (nodes.empty() || g_launcher_window_focused.load(std::memory_order_acquire) == 0) return;
    for (const auto &node : nodes) {
        if (isRecentTaskLabel(node.label)
                || isEmptyRecentsLabel(node.label)
                || node.resource == "com.miui.home:id/clearAnimView"
                || node.resource == "com.miui.home:id/workspace"
                || node.resource == "com.miui.home:id/screen_content"
                || looksLikePageIndicator(node.label)) {
            g_host_view_id.store(node.host, std::memory_order_release);
            g_window_id.store(node.window, std::memory_order_release);
            break;
        }
    }
    if (hasRecentSurface(nodes)) {
        observeSurfaceSample(provider, SurfaceState::kRecents, nodes);
    } else if (hasWorkspaceSurface(nodes)) {
        observeSurfaceSample(provider, SurfaceState::kWorkspace, nodes);
    }
}

void surfacePollLoop() {
    for (;;) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        flushSurfaceAnnouncements();
    }
}

void ensureSurfacePoller() {
    // Surface and announcement work is driven by the synchronous provider
    // hooks.  A background scan cannot prove that its raw provider is alive.
}


void observeSurfaceNode(void *provider, int32_t nodeId, void *node) {
    if (provider == nullptr || node == nullptr
            || g_launcher_window_focused.load(std::memory_order_acquire) == 0) return;
    g_last_provider.store(provider, std::memory_order_release);
    if (nodeId != -1) {
        const std::string resource = nodeString(node, 0x1b8);
        const std::string label = nodeString(node, 0x138);
        if (isRecentTaskLabel(label) || isEmptyRecentsLabel(label)
                || resource == "com.miui.home:id/clearAnimView") {
            {
                std::lock_guard lock(g_surface_mutex);
                g_last_recent_surface = std::chrono::steady_clock::now();
                g_has_recent_surface_time = true;
            }
            observeSurfaceSample(provider, SurfaceState::kRecents, {});
        } else if (resource == "com.miui.home:id/workspace"
                || resource == "com.miui.home:id/screen_content"
                || looksLikePageIndicator(label)) {
            bool inRecentGrace = false;
            {
                std::lock_guard lock(g_surface_mutex);
                inRecentGrace = static_cast<SurfaceState>(
                        g_surface_state.load(std::memory_order_acquire))
                        == SurfaceState::kRecents && g_has_recent_surface_time
                        && std::chrono::steady_clock::now() - g_last_recent_surface
                                < std::chrono::milliseconds(1000);
            }
            if (!inRecentGrace) observeSurfaceSample(provider, SurfaceState::kWorkspace, {});
        }
        return;
    }
    g_surface_scan_requested.store(true, std::memory_order_release);
}

bool recentTaskLocked(const std::string &label) {
    return label.find("未加锁") == std::string::npos
            && label.find("未锁定") == std::string::npos;
}

bool isRecentAction(int32_t actionId) {
    return actionId >= kRecentAppFlowAction && actionId <= kRecentCloseAction;
}

const char16_t *recentActionLabel(int32_t actionId, bool locked) {
    switch (actionId) {
        case kRecentAppFlowAction: return kRecentAppFlowLabel;
        case kRecentLockAction: return locked ? kRecentUnlockLabel : kRecentLockLabel;
        case kRecentSplitAction: return kRecentSplitLabel;
        case kRecentFreeformAction: return kRecentFreeformLabel;
        case kRecentAppInfoAction: return kRecentAppInfoLabel;
        case kRecentCloseAction: return kRecentCloseLabel;
        default: return nullptr;
    }
}

bool callLauncherProviderAction(void *provider, int32_t nodeId, int32_t actionId,
        const std::array<uintptr_t, 2> &arguments) {
    if (provider == nullptr) return false;
    auto *providerWords = static_cast<uintptr_t *>(provider);
    auto *vtable = reinterpret_cast<uintptr_t *>(providerWords[0]);
    auto implementation = reinterpret_cast<void *>(providerWords[1]);
    if (vtable == nullptr || implementation == nullptr || vtable[6] == 0) return false;
    auto performAction = reinterpret_cast<LauncherPerformActionFn>(vtable[6]);
    return performAction(implementation, nodeId, actionId, arguments[0], arguments[1]);
}

std::pair<float, float> recentTouchPoint(void *provider, int32_t nodeId,
        const Bounds &bounds) {
    (void) provider;
    (void) nodeId;
    // The gesture is launched from a detached worker.  Use the bounds already
    // validated on the action thread instead of dereferencing a provider that
    // may have been destroyed by a recents transition.
    return {static_cast<float>(bounds.centerX()), static_cast<float>(bounds.centerY())};
}

bool resolveRecentTaskId(void *provider, int32_t nodeId, const std::string &label,
        int32_t &taskId, AppTarget &target) {
    taskId = -1;
    if (provider == nullptr || !resolveAppTarget(nodeId, label, target)
            || target.package.empty() || g_task_get_recent_tasks == nullptr
            || g_recent_task_get_task_id == nullptr) {
        return false;
    }
    nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
            "recent target node=%d label=%s package=%s activity=%s user=%d",
            nodeId, label.c_str(), target.package.c_str(), target.activity.c_str(), target.user);
    int32_t limit = g_task_get_max_recent_tasks != nullptr
            ? g_task_get_max_recent_tasks() : 20;
    limit = std::clamp(limit, 1, 40);
    RustRecentTasksResult result = g_task_get_recent_tasks(limit, 13);
    if ((result.tag & 1U) != 0 || result.tasks.data == nullptr
            || result.tasks.length > 40) {
        if ((result.tag & 1U) == 0) dropRustVec(result.tasks);
        return false;
    }
    auto **items = static_cast<void **>(result.tasks.data);
    bool found = false;
    int32_t packageTaskId = -1;
    int32_t packageTaskUser = target.user;
    for (size_t index = 0; index < result.tasks.length; ++index) {
        void *info = items[index];
        if (info == nullptr) continue;
        int32_t id = g_recent_task_get_task_id(info);
        int32_t user = g_recent_task_get_user_id != nullptr
                ? g_recent_task_get_user_id(info) : target.user;
        std::string record;
        if (g_recent_task_get_record_name != nullptr) {
            record = copyRustStringView(g_recent_task_get_record_name(info));
        }
        std::string componentPackage;
        if (g_recent_task_get_base_activity != nullptr
                && g_component_name_get_package != nullptr) {
            void *baseActivity = g_recent_task_get_base_activity(info);
            if (baseActivity != nullptr) {
                RustStringView value = g_component_name_get_package(baseActivity);
                if (value.data != nullptr && value.length <= 4096) {
                    componentPackage.assign(value.data, value.length);
                }
            }
        }
        const size_t slash = record.find('/');
        const std::string package = slash == std::string::npos
                ? record : record.substr(0, slash);
        const bool packageMatch = package == target.package
                || record.rfind(target.package, 0) == 0
                || componentPackage == target.package;
        if (packageMatch && packageTaskId < 0) {
            packageTaskId = id;
            packageTaskUser = user;
        }
        if (!found && user == target.user && packageMatch) {
            taskId = id;
            found = id >= 0;
            nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                    "recent task matched node=%d id=%d package=%s base=%s",
                    nodeId, taskId, target.package.c_str(), componentPackage.c_str());
        }
    }
    if (!found && packageTaskId >= 0) {
        taskId = packageTaskId;
        target.user = packageTaskUser;
        found = true;
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "recent task matched by package node=%d id=%d package=%s user=%d",
                nodeId, taskId, target.package.c_str(), target.user);
    }
    // ponytail: the returned vector owns every RecentTaskInfo; dropping an
    // element here makes the vector destructor free it a second time.
    dropRustVec(result.tasks);
    nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
            "recent resolve node=%d found=%d task=%d package=%s",
            nodeId, found ? 1 : 0, taskId, target.package.c_str());
    return found;
}

bool performDirectRecentAction(int32_t actionId, const AppTarget &target, int32_t taskId) {
    if (taskId < 0) return false;
    if (actionId == kRecentSplitAction && g_launcher_launch_sosc != nullptr) {
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "recent split task=%d package=%s", taskId, target.package.c_str());
        g_launcher_launch_sosc(taskId);
        return true;
    }
    if (actionId == kRecentFreeformAction && g_launcher_start_freeform != nullptr
            && g_component_name_new != nullptr) {
        void *component = g_component_name_new(target.package.data(), target.package.size(),
                target.activity.data(), target.activity.size());
        if (component == nullptr) return false;
        // The launcher helper consumes the ComponentName (and drops it).
        bool success = g_launcher_start_freeform(component, taskId, target.user, 1);
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "recent freeform task=%d package=%s success=%d", taskId,
                target.package.c_str(), success ? 1 : 0);
        return success;
    }
    return false;
}

bool startRecentAppFlow(int32_t taskId, const AppTarget &target) {
    if (!sceneActivityAllowed()) return false;
    // ponytail: use the stable activity boundary; the Dart closure ABI is isolate-specific.
    if (g_scene_start_activity == nullptr || g_intent_default == nullptr
            || g_intent_set_component == nullptr || g_component_name_new == nullptr
            || g_bundle_default == nullptr || g_bundle_drop == nullptr
            || g_bundle_insert_i32 == nullptr || g_bundle_insert_string == nullptr
            || g_intent_set_extras == nullptr || taskId < 0
            || target.package.empty() || target.activity.empty()) return false;
    void *scene = g_scene_instance.load(std::memory_order_acquire);
    if (scene == nullptr || !readableAddress(scene, sizeof(void *))) return false;
    void *intent = g_intent_default();
    if (intent == nullptr) return false;
    void *bundle = g_bundle_default();
    if (bundle == nullptr) return false;
    auto putString = [bundle](const char *key, const std::string &value) {
        std::u16string keyText = utf16FromUtf8(key);
        std::u16string valueText = utf16FromUtf8(value);
        RustRString keyString = g_rstring_from_utf16(
                reinterpret_cast<const uint16_t *>(keyText.data()), keyText.size());
        RustRString valueString = g_rstring_from_utf16(
                reinterpret_cast<const uint16_t *>(valueText.data()), valueText.size());
        RustOptionString optionalValue{0, {}, valueString};
        g_bundle_insert_string(bundle, &keyString, &optionalValue);
    };
    if (g_rstring_from_utf16 == nullptr) {
        g_bundle_drop(bundle);
        return false;
    }
    auto putInt = [bundle](const char *key, int32_t value) {
        std::u16string keyText = utf16FromUtf8(key);
        RustRString keyString = g_rstring_from_utf16(
                reinterpret_cast<const uint16_t *>(keyText.data()), keyText.size());
        g_bundle_insert_i32(bundle, &keyString, value);
    };
    putInt("id", taskId);
    putInt("uid", target.user);
    const std::string componentName = target.package + "/" + target.activity;
    putString("ref", componentName);
    putString("packageName", target.package);
    putString("className", target.activity);
    putString("componentName", componentName);
    putString("topPackageName", target.package);
    putString("topClassName", target.activity);
    g_intent_set_extras(intent, bundle);
    constexpr char kPackage[] = "com.milink.service";
    constexpr char kActivity[] = "com.miui.circulate.world.AppCirculateActivity";
    void *component = g_component_name_new(kPackage, sizeof(kPackage) - 1,
            kActivity, sizeof(kActivity) - 1);
    if (component == nullptr) return false;
    g_intent_set_component(intent, component);
    RustResult48 result = g_scene_start_activity(scene, intent, 1, nullptr, 1, 0, -1, nullptr);
    nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
            "recent app flow task=%d package=%s activity=%s result=%d", taskId,
            target.package.c_str(), target.activity.c_str(), nodeField<int32_t>(result.data, 8));
    return rustResultOk(result, "start app flow");
}

bool closeRecentTask(void *provider, int32_t nodeId) {
    Bounds bounds{};
    std::string label;
    if (!nodeBounds(provider, nodeId, bounds, &label) || !isRecentTaskLabel(label)
            || g_motion_new == nullptr || g_input_event == nullptr
            || g_input_drop == nullptr || g_inject_input == nullptr) {
        return false;
    }
    const std::string removalName = recentCardName(label);
    rememberRecentRemoval(removalName);
    std::thread([bounds, nodeId, removalName] {
        const auto point = recentTouchPoint(nullptr, nodeId, bounds);
        const float x = point.first;
        const float startY = point.second;
        const float endY = std::max(1.0f,
                static_cast<float>(bounds.top - std::max(160, bounds.height() / 2)));
        const int64_t down = uptimeMillis();
        const bool downOk = injectTouch(down, 0, x, startY);
        std::this_thread::sleep_for(std::chrono::milliseconds(25));
        const bool moveOk = injectTouch(down, 2, x, (startY + endY) * 0.5f);
        std::this_thread::sleep_for(std::chrono::milliseconds(25));
        const bool upOk = injectTouch(down, 1, x, endY);
        if (!(downOk && moveOk && upOk)) acknowledgeRecentRemoval(removalName);
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "recent close gesture node=%d start=%.0f end=%.0f result=%d/%d/%d",
                nodeId, startY, endY, downOk ? 1 : 0, moveOk ? 1 : 0, upOk ? 1 : 0);
        sendEvent(nodeId, 2048);
    }).detach();
    return true;
}

bool lockRecentTask(void *provider, int32_t nodeId, const Bounds &bounds) {
    if (provider == nullptr || g_motion_new == nullptr || g_input_event == nullptr
            || g_input_drop == nullptr || g_inject_input == nullptr) {
        return false;
    }
    std::thread([bounds, nodeId] {
        const auto point = recentTouchPoint(nullptr, nodeId, bounds);
        const float x = point.first;
        const float startY = point.second;
        const float distance = static_cast<float>(std::max(600, bounds.height() / 2));
        const float endY = std::min(2600.0f, startY + distance);
        if (endY <= startY + 24.0f) return;
        const int64_t down = uptimeMillis();
        const bool downOk = injectTouch(down, 0, x, startY);
        std::this_thread::sleep_for(std::chrono::milliseconds(25));
        const bool moveOk = injectTouch(down, 2, x, (startY + endY) * 0.5f);
        std::this_thread::sleep_for(std::chrono::milliseconds(25));
        const bool upOk = injectTouch(down, 1, x, endY);
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "recent lock gesture node=%d start=%.0f end=%.0f result=%d/%d/%d",
                nodeId, startY, endY, downOk ? 1 : 0, moveOk ? 1 : 0, upOk ? 1 : 0);
        sendEvent(nodeId, 2048);
    }).detach();
    return true;
}

bool buildModelMove(void *provider, int32_t nodeId, int32_t actionId,
        std::vector<FavoritePlacement> &sources,
        std::vector<FavoritePlacement> &expected, WireWriter &wire,
        std::string &failureReason) {
    failureReason = "托起状态已失效，请重新托起";
    std::vector<ObservedNode> selected;
    std::vector<Slot> slots;
    std::vector<FavoritePlacement> cachedSources;
    {
        std::lock_guard lock(g_session_mutex);
        if (!g_lifted.load(std::memory_order_acquire) || g_sources.empty()) {
            nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                    "HyperOS4 move source session missing node=%d action=0x%x lifted=%d sources=%zu slots=%zu",
                    nodeId, actionId, g_lifted.load(std::memory_order_acquire) ? 1 : 0,
                    g_sources.size(), g_slots.size());
            return false;
        }
        selected = g_sources;
        cachedSources = g_source_placements;
        slots = g_slots;
    }
    if (selected.empty()) {
        nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                "HyperOS4 move selected source list empty node=%d action=0x%x", nodeId, actionId);
        return false;
    }

    failureReason = "未能确认托起的图标，请取消后重新托起";
    sources.reserve(selected.size());
    if (cachedSources.size() == selected.size()) {
        bool cacheMatches = true;
        for (size_t index = 0; index < selected.size(); ++index) {
            // Captured in selection order at lift time: favorite IDs and
            // accessibility node IDs belong to different namespaces.
            if (cachedSources[index].id < 0
                    || !workspaceContainer(cachedSources[index].container)) {
                cacheMatches = false;
                break;
            }
        }
        if (cacheMatches) {
            sources = cachedSources;
            nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                    "HyperOS4 move reused lift source placements count=%zu", sources.size());
        }
    }
    if (sources.empty()) {
        for (const auto &node : selected) {
            AppTarget target;
            const int32_t liftedScreen = g_lifted_screen.load(std::memory_order_acquire);
            const int32_t screenHint = liftedScreen >= 0 ? liftedScreen + 1 : -1;
            if (!resolveAppTarget(node.id, node.label, target, screenHint,
                        node.resource == "com.miui.home:id/folder" ? 2 : 0)
                    || target.placement.id < 0
                    || !workspaceContainer(target.placement.container)) {
                LauncherSnapshot snapshot;
                {
                    std::lock_guard lock(g_snapshot_mutex);
                    snapshot = g_snapshot;
                }
                nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                        "HyperOS4 move source unresolved stage=source node=%d action=0x%x label=%s sources=%zu slots=%zu grid=%dx%d screen=%d snapshot_ready=%d generation=%llu",
                        node.id, actionId, node.label.c_str(), selected.size(), slots.size(),
                        g_grid_rows.load(), g_grid_columns.load(), liftedScreen + 1,
                        snapshot.ready ? 1 : 0,
                        static_cast<unsigned long long>(snapshot.generation));
                return false;
            }
            sources.push_back(target.placement);
        }
    }

    failureReason = "目标位置已变化，请重新选择位置";
    FavoritePlacement base = sources.front();
    int32_t startSlot = -1;
    if (isVirtualNode(nodeId)) {
        for (size_t index = 0; index < slots.size(); ++index) {
            if (virtualIdForSlot(slots[index]) == nodeId) {
                startSlot = static_cast<int32_t>(index);
                break;
            }
        }
        if (startSlot < 0) {
            nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                    "HyperOS4 move stale virtual target stage=slot node=%d action=0x%x slots=%zu grid=%dx%d lifted_screen=%d",
                    nodeId, actionId, slots.size(), g_grid_rows.load(), g_grid_columns.load(),
                    g_lifted_screen.load() + 1);
            return false;
        }
        if (startSlot + static_cast<int32_t>(sources.size())
                > static_cast<int32_t>(slots.size())) {
            failureReason = "没有可拖放的位置";
            nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                    "HyperOS4 move insufficient virtual slots stage=slot node=%d action=0x%x start=%d sources=%zu slots=%zu grid=%dx%d",
                    nodeId, actionId, startSlot, sources.size(), slots.size(),
                    g_grid_rows.load(), g_grid_columns.load());
            return false;
        }
        base.container = kDesktopContainer;
        // Accessibility page indexes are zero-based; launcher favorites.screen
        // is one-based (the first desktop page is screen=1).
        base.screen = g_visible_screen.load(std::memory_order_acquire) + 1;
    } else {
        if (provider == nullptr) {
            nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                    "HyperOS4 move target provider missing node=%d action=0x%x", nodeId, actionId);
            return false;
        }
        std::string label;
        Bounds ignored{};
        if (!nodeBounds(provider, nodeId, ignored, &label)) {
            nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                    "HyperOS4 move target node unavailable stage=target node=%d action=0x%x sources=%zu slots=%zu",
                    nodeId, actionId, sources.size(), slots.size());
            return false;
        }
        AppTarget target;
        if (!resolveAppTarget(nodeId, label, target, g_visible_screen.load() + 1,
                    isFolderNode(provider, nodeId) ? 2 : 0)
                || target.placement.id < 0
                || !workspaceContainer(target.placement.container)) {
            nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                    "HyperOS4 move target unresolved stage=target node=%d action=0x%x label=%s sources=%zu slots=%zu grid=%dx%d",
                    nodeId, actionId, label.c_str(), sources.size(), slots.size(),
                    g_grid_rows.load(), g_grid_columns.load());
            return false;
        }
        base = target.placement;
    }

    expected.reserve(sources.size() + 32);
    std::vector<FavoritePlacement> shifted;
    if (startSlot < 0) {
        if (actionId != kMoveBeforeAction && actionId != kMoveAfterAction) {
            nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                    "HyperOS4 move unsupported occupied-target action node=%d action=0x%x",
                    nodeId, actionId);
            return false;
        }
        for (const auto &source : sources) {
            if (source.spanX != 1 || source.spanY != 1) {
                nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                        "HyperOS4 move rejects non-single-cell source id=%lld",
                        static_cast<long long>(source.id));
                return false;
            }
        }
        std::vector<FavoritePlacement> page;
        if (!loadPagePlacements(base.container, base.screen, page)) {
            failureReason = "未能读取目标页，请稍后重试";
            nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                    "HyperOS4 move page load failed stage=reorder node=%d action=0x%x page=%d",
                    nodeId, actionId, base.screen);
            return false;
        }
        std::unordered_set<int64_t> sourceIds;
        for (const auto &source : sources) sourceIds.insert(source.id);
        const int columns = std::max(1, g_grid_columns.load(std::memory_order_relaxed));
        const int rows = std::max(1, g_grid_rows.load(std::memory_order_relaxed));
        const int capacity = rows * columns;
        const int anchorRank = base.cellY * columns + base.cellX;
        if (anchorRank < 0 || anchorRank >= capacity) {
            nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                    "HyperOS4 move anchor outside grid node=%d action=0x%x page=%d cell=%d,%d grid=%dx%d",
                    nodeId, actionId, base.screen, base.cellX, base.cellY, rows, columns);
            return false;
        }

        std::sort(page.begin(), page.end(), [columns](const auto &left, const auto &right) {
            int leftRank = left.cellY * columns + left.cellX;
            int rightRank = right.cellY * columns + right.cellX;
            return leftRank < rightRank;
        });
        std::vector<FavoritePlacement> ordered;
        ordered.reserve(page.size() + sources.size());
        int targetIndex = -1;
        for (const auto &item : page) {
            if (item.spanX != 1 || item.spanY != 1) {
                failureReason = "当前页面暂不支持图标重排，请选择空位";
                nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                        "HyperOS4 move rejects non-single-cell page item id=%lld",
                        static_cast<long long>(item.id));
                return false;
            }
            if (sourceIds.find(item.id) != sourceIds.end()) continue;
            if (item.id == base.id) targetIndex = static_cast<int>(ordered.size());
            ordered.push_back(item);
        }
        if (targetIndex < 0) {
            nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                    "HyperOS4 move anchor item missing stage=reorder node=%d action=0x%x target_id=%lld page_items=%zu",
                    nodeId, actionId, static_cast<long long>(base.id), page.size());
            return false;
        }
        int insertion = targetIndex + (actionId == kMoveAfterAction ? 1 : 0);
        ordered.insert(ordered.begin() + insertion, sources.begin(), sources.end());

        if (static_cast<int>(ordered.size()) > capacity) {
            int32_t nextScreen = -1;
            std::vector<FavoritePlacement> nextPage;
            if (!loadNextDesktopScreen(base.container, base.screen, nextScreen, nextPage)) {
                failureReason = "未能读取下一页，请稍后重试";
                nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                        "HyperOS4 move cannot inspect overflow page=%d",
                        base.screen);
                return false;
            }
            nextPage.erase(std::remove_if(nextPage.begin(), nextPage.end(),
                    [&sourceIds](const auto &item) {
                        return sourceIds.find(item.id) != sourceIds.end();
                    }), nextPage.end());
            if (!nextPage.empty()) {
                failureReason = "当前页面剩余位置不足，请选择其他页面";
                nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                        "HyperOS4 move rejected non-empty overflow page=%d",
                        nextScreen);
                return false;
            }
            int overflowCount = static_cast<int>(ordered.size()) - capacity;
            if (overflowCount > capacity) {
                failureReason = "没有可拖放的位置";
                nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                        "HyperOS4 move overflow exceeds capacity node=%d action=0x%x count=%d capacity=%d",
                        nodeId, actionId, overflowCount, capacity);
                return false;
            }
            for (int index = 0; index < overflowCount; ++index) {
                FavoritePlacement item = ordered[capacity + index];
                item.container = base.container;
                item.screen = nextScreen;
                item.cellX = index % columns;
                item.cellY = index / columns;
                shifted.push_back(item);
            }
            ordered.resize(capacity);
            nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                    "HyperOS4 move overflow count=%d targetPage=%d nextPage=%d",
                    overflowCount, base.screen, nextScreen);
        }
        for (size_t index = 0; index < ordered.size(); ++index) {
            ordered[index].container = base.container;
            ordered[index].screen = base.screen;
            ordered[index].cellX = static_cast<int32_t>(index % columns);
            ordered[index].cellY = static_cast<int32_t>(index / columns);
            shifted.push_back(ordered[index]);
        }
    }
    for (size_t index = 0; index < sources.size(); ++index) {
        FavoritePlacement target = base;
        if (startSlot >= 0) {
            const Slot &slot = slots[static_cast<size_t>(startSlot) + index];
            target.cellX = slot.column;
            target.cellY = slot.row;
        } else {
            auto found = std::find_if(shifted.begin(), shifted.end(),
                    [&sources, index](const auto &item) {
                        return item.id == sources[index].id;
                    });
            if (found == shifted.end()) {
                nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                        "HyperOS4 move source missing from reordered model node=%d action=0x%x source_id=%lld",
                        nodeId, actionId, static_cast<long long>(sources[index].id));
                return false;
            }
            target = *found;
        }
        target.id = sources[index].id;
        target.itemType = sources[index].itemType;
        target.spanX = sources[index].spanX;
        target.spanY = sources[index].spanY;
        if (target.container == kDesktopContainer
                && (target.cellX < 0 || target.cellY < 0 || target.spanX <= 0 || target.spanY <= 0
                || target.cellX > g_grid_columns.load() - target.spanX
                || target.cellY > g_grid_rows.load() - target.spanY)) {
            nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                    "HyperOS4 move target outside grid stage=final node=%d action=0x%x id=%lld page=%d cell=%d,%d span=%dx%d grid=%dx%d",
                    nodeId, actionId, static_cast<long long>(target.id), target.screen,
                    target.cellX, target.cellY, target.spanX, target.spanY,
                    g_grid_rows.load(), g_grid_columns.load());
            return false;
        }
        expected.push_back(target);
    }
    std::unordered_set<int64_t> expectedIds;
    for (const auto &item : expected) expectedIds.insert(item.id);
    for (const auto &item : shifted) {
        if (expectedIds.insert(item.id).second) expected.push_back(item);
    }

    wire.put32(static_cast<int32_t>(expected.size()));
    for (const auto &target : expected) {
        appendSparseItemInfo(wire, target, target);
    }
    if (g_diagnostics.session() != 0) {
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "move plan node=%d action=0x%x sources=%zu targets=%zu page=%d grid=%dx%d bytes=%zu",
                nodeId, actionId, sources.size(), expected.size(), g_visible_screen.load() + 1,
                g_grid_columns.load(), g_grid_rows.load(), wire.bytes.size());
        for (size_t index = 0; index < sources.size(); ++index) {
            const auto &item = sources[index];
            nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                    "move source node=%d label=%s id=%lld container=%lld page=%d cell=%d,%d span=%dx%d",
                    selected[index].id, selected[index].label.c_str(), static_cast<long long>(item.id),
                    static_cast<long long>(item.container), item.screen, item.cellX, item.cellY,
                    item.spanX, item.spanY);
        }
        for (const auto &item : expected) {
            nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                    "move target id=%lld container=%lld page=%d cell=%d,%d span=%dx%d",
                    static_cast<long long>(item.id), static_cast<long long>(item.container),
                    item.screen, item.cellX, item.cellY, item.spanX, item.spanY);
        }
    }
    return !wire.bytes.empty();
}

struct VisiblePlacement {
    FavoritePlacement item;
    std::string label;
    Bounds bounds;
};

bool findVisiblePlacements(const std::vector<ObservedNode> &nodes,
        const std::vector<VisiblePlacement> &expected, ObservedNode &first) {
    if (expected.empty()) return false;
    int32_t page = 0;
    for (const auto &node : nodes) {
        if (parsePageIndicator(node.label, page)) break;
    }
    if (page != expected.front().item.screen) return false;
    std::unordered_set<int32_t> matched;
    for (const auto &target : expected) {
        if (target.item.screen != page || target.bounds.width() <= 0
                || target.bounds.height() <= 0) return false;
        auto found = std::find_if(nodes.begin(), nodes.end(), [&](const auto &node) {
            return node.icon() && matched.count(node.id) == 0
                    && labelMatches(node.label, target.label)
                    && node.bounds.centerX() >= target.bounds.left
                    && node.bounds.centerX() < target.bounds.right
                    && node.bounds.centerY() >= target.bounds.top
                    && node.bounds.centerY() < target.bounds.bottom;
        });
        if (found == nodes.end()) return false;
        if (matched.empty()) first = *found;
        matched.insert(found->id);
    }
    return true;
}

std::string placementAnnouncement(const std::vector<VisiblePlacement> &placed) {
    if (placed.empty()) return {};
    const auto &first = placed.front();
    // System Home's zh-CN announce_to_drop_workspace resource.
    return "已放置在第" + std::to_string(first.item.screen)
            + "页，第" + std::to_string(first.item.cellY + 1)
            + "行，第" + std::to_string(first.item.cellX + 1) + "列";
}

bool performNativePlacement(void *provider, int32_t nodeId, int32_t actionId) {
    uint32_t capabilities = g_capabilities.load(std::memory_order_acquire);
    bool alreadyRunning = g_model_move_running.load(std::memory_order_acquire);
    const bool frbModelReady = g_frb_dispatcher != nullptr
            && (capabilities & CAP_FRB_MODEL) != 0;
    const bool moveProfileReady = (capabilities & CAP_MOVE_PROFILE) != 0;
    const bool privateAbiReady = g_launcher_system_compatible.load(std::memory_order_acquire)
            && (capabilities & (CAP_PRIVATE_BUILD_MATCH
            | CAP_PRIVATE_SYMBOLS)) == (CAP_PRIVATE_BUILD_MATCH | CAP_PRIVATE_SYMBOLS);
    if (actionId == kCreateFolderAction || !moveProfileReady || !frbModelReady
            || !privateAbiReady
            || alreadyRunning) {
        nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                "HyperOS4 placement rejected node=%d action=%d dispatcher=%p move=%d frb=%d private=%d running=%d",
                nodeId, actionId, g_frb_dispatcher,
                moveProfileReady ? 1 : 0, frbModelReady ? 1 : 0,
                privateAbiReady ? 1 : 0,
                alreadyRunning ? 1 : 0);
        if ((!moveProfileReady || !frbModelReady || !privateAbiReady)
                && actionId != kCreateFolderAction) {
            sendAnnouncement(nodeId, "当前系统移动路径不可用");
        }
        return false;
    }
    if (g_model_move_running.exchange(true, std::memory_order_acq_rel)) return false;

    std::vector<FavoritePlacement> sources;
    std::vector<FavoritePlacement> expected;
    WireWriter wire;
    std::string failureReason;
    if (!buildModelMove(provider, nodeId, actionId, sources, expected, wire, failureReason)) {
        g_model_move_running.store(false, std::memory_order_release);
        if (actionId == 0 || actionId == kMoveBeforeAction || actionId == kMoveAfterAction) {
            const AccessibilityEventSource source = captureDropParentEventSource(nodeId);
            announceReliably(source.host, source.window, source.nodeId, failureReason);
        }
        return false;
    }
    if (wire.bytes.empty()) {
        g_model_move_running.store(false, std::memory_order_release);
        const AccessibilityEventSource source = captureDropParentEventSource(nodeId);
        announceReliably(source.host, source.window, source.nodeId,
                "放置失败，已保持原位置");
        return false;
    }
    AccessibilityEventSource eventSource = captureDropParentEventSource(nodeId);
    eventSource.nodeId = -1; // The page node may be replaced by the native reload.
    std::vector<VisiblePlacement> visibleTargets;
    {
        std::lock_guard lock(g_session_mutex);
        if (!g_lifted.load() || g_sources.size() != sources.size()) {
            g_model_move_running.store(false, std::memory_order_release);
            return false;
        }
        for (size_t index = 0; index < sources.size(); ++index) {
            const auto &item = expected[index];
            auto cell = std::find_if(g_grid_cells.begin(), g_grid_cells.end(), [&](const auto &slot) {
                return slot.row == item.cellY && slot.column == item.cellX;
            });
            visibleTargets.push_back({item, g_sources[index].label,
                    cell == g_grid_cells.end() ? Bounds{} : cell->bounds});
        }
    }
    std::vector<PendingPlacement> pendingTargets;
    pendingTargets.reserve(visibleTargets.size());
    for (const auto &target : visibleTargets) {
        pendingTargets.push_back({target.item, target.label, target.bounds, false});
    }
    // Native dispatch may publish fresh nodes before it returns.
    beginPendingPlacement(pendingTargets);
    const bool currentMoveProfile = g_current_frb_move_profile.load(
            std::memory_order_acquire);
    bool directDatabaseCommit = false;
    if (currentMoveProfile) {
        // 8565 op 833 takes one ItemInfo and four destination arguments.
        // Keep this path to one source and an empty slot, without a vector count.
        if (sources.size() == 1 && isVirtualNode(nodeId)
                && actionId == 0 && expected.size() == 1) {
            WireWriter itemWire;
            appendSparseItemInfo(itemWire, sources.front(), expected.front());
            // move_item_in_database also decodes four destination arguments
            // after ItemInfo; omitting them leaves the native model unchanged.
            itemWire.put64(expected.front().container);
            itemWire.put32(expected.front().screen);
            itemWire.put32(expected.front().cellX);
            itemWire.put32(expected.front().cellY);
            dispatchWire(kFrbMoveItemInDatabase, itemWire.bytes);
            nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                    "HyperOS4 move_item_in_database dispatched id=%lld target=%d payload=%zu",
                    static_cast<long long>(sources.front().id), nodeId,
                    itemWire.bytes.size());
        } else {
            // 8565 batch model effects still lack runtime validation. Retain
            // the existing favorites transaction for occupied cells.
            directDatabaseCommit = true;
            nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                    "HyperOS4 occupied reorder using direct favorites transaction count=%zu target=%d",
                    expected.size(), nodeId);
        }
    } else {
        // Older verified profiles use the batch operation and its vector
        // envelope.
        const uint32_t operation = batchMoveOperation(
                g_launcher_profile.load(std::memory_order_acquire));
        if (operation == 0U) {
            endPendingPlacement();
            g_model_move_running.store(false, std::memory_order_release);
            return false;
        }
        dispatchWire(operation, wire.bytes);
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "HyperOS4 model batch dispatched op=%u count=%zu target=%d bytes=%zu",
                operation, expected.size(), nodeId, wire.bytes.size());
    }
    std::thread([expected = std::move(expected), visibleTargets = std::move(visibleTargets),
                 eventSource, directDatabaseCommit] {
        bool committed = directDatabaseCommit
                ? updateFavoritePlacements(expected) && waitForPlacements(expected)
                : waitForPlacements(expected);
        if (committed && g_launcher_window_focused.load(std::memory_order_acquire) == 0) {
            endPendingPlacement();
            nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                    "HyperOS4 model move committed after Home lost focus; reload skipped");
            g_model_move_running.store(false, std::memory_order_release);
            return;
        }
        if (committed) {
            requestSnapshotRefresh(true);
            invokeWorkspaceReload();
            clearLiftSession();
            // Readers need invalidation before they query the nodes we wait for.
            sendEvent(eventSource, kTypeWindowContentChanged);
            const bool visible = waitForPendingPlacementVisibility();
            const bool persisted = placementsMatch(expected, "after_reload");
            const bool focused = g_launcher_window_focused.load(std::memory_order_acquire) != 0;
            if (focused) {
                announceReliably(eventSource.host, eventSource.window, eventSource.nodeId,
                        !persisted ? "桌面刷新后位置未能确认，请检查图标位置后重试"
                        : visible ? placementAnnouncement(visibleTargets)
                                : "位置已保存，但未确认桌面图标刷新，请重新进入桌面检查");
            }
            endPendingPlacement();
            nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                    "HyperOS4 model move committed persisted=%d visible=%d focused=%d",
                    persisted ? 1 : 0, visible ? 1 : 0, focused ? 1 : 0);
        } else {
            const bool focused = g_launcher_window_focused.load(std::memory_order_acquire) != 0;
            if (focused) {
                announceReliably(eventSource.host, eventSource.window, eventSource.nodeId,
                        "未能确认移动，请检查图标位置后重试");
            }
            endPendingPlacement();
            nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                    "HyperOS4 model move verification failed focused=%d", focused ? 1 : 0);
        }
        g_model_move_running.store(false, std::memory_order_release);
    }).detach();
    return true;
}

bool performActionImpl(void *provider, int32_t nodeId, int32_t actionId, void *arguments) {
    if (isVirtualNode(nodeId) && (!g_lifted.load(std::memory_order_acquire)
            || virtualNodeDescription(nodeId).empty())) return false;
    if (actionId == kMoveBeforeAction || actionId == kMoveAfterAction
            || actionId == kCreateFolderAction || (actionId == 16 && isVirtualNode(nodeId))) {
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "placement action received node=%d action=0x%x lifted=%d virtual=%d",
                nodeId, actionId, g_lifted.load(std::memory_order_acquire) ? 1 : 0,
                isVirtualNode(nodeId) ? 1 : 0);
    }
    if (isRecentAction(actionId)) {
        Bounds bounds{};
        std::string label;
        if (!nodeBounds(provider, nodeId, bounds, &label) || !isRecentTaskLabel(label)) {
            return false;
        }
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "recent action node=%d id=0x%x label=%s", nodeId, actionId, label.c_str());
        if (actionId == kRecentCloseAction) return closeRecentTask(provider, nodeId);
        if (actionId == kRecentLockAction) {
            return lockRecentTask(provider, nodeId, bounds);
        }
        if (actionId == kRecentAppInfoAction) {
            AppTarget target;
            int32_t taskId = -1;
            if (!resolveRecentTaskId(provider, nodeId, label, taskId, target)
                    && !resolveAppTarget(nodeId, label, target)) return false;
            bool success = startAppInfo(target);
            if (!success) sendAnnouncement(nodeId, "应用详情不可用，请重新定位任务");
            return success;
        }
        if (actionId == kRecentAppFlowAction) {
            AppTarget target;
            int32_t taskId = -1;
            bool resolved = resolveRecentTaskId(provider, nodeId, label, taskId, target);
            bool success = resolved && startRecentAppFlow(taskId, target);
            if (!success) sendAnnouncement(nodeId, "应用流转不可用，请重新定位任务");
            return success;
        }
        if (actionId == kRecentSplitAction || actionId == kRecentFreeformAction) {
            AppTarget target;
            int32_t taskId = -1;
            if (!resolveRecentTaskId(provider, nodeId, label, taskId, target)) return false;
            bool success = performDirectRecentAction(actionId, target, taskId);
            if (!success) sendAnnouncement(nodeId, "该操作不可用，请重新定位任务");
            return success;
        }
        return false;
    }
    ObservedNode opened;
    if (actionId == 16 && !isVirtualNode(nodeId)
            && !g_lifted.load(std::memory_order_acquire)
            && isDesktopIconNode(provider, nodeId, &opened)) {
        const bool success = g_original_perform_action != nullptr
                && g_original_perform_action(provider, nodeId, actionId, arguments);
        if (success) {
            // Let the platform accessibility service produce the feedback.
            // The module does not synthesize or play its own sound.
            sendEvent(nodeId, kTypeViewClicked);
        }
        return success;
    }
    if (actionId == kLiftAction) {
        if (g_model_move_running.load()) {
            sendAnnouncement(nodeId, "正在放置，请稍候");
            return false;
        }
        std::vector<ObservedNode> nodes;
        readTree(provider, -1, nodes);
        // The page indicator is part of the same accessibility tree as the
        // source icon. Refresh it at lift time so model writes use the page
        // the user is actually touching, even when the normal node hook did
        // not observe the indicator during a swipe.
        bool pageIndicatorSeen = false;
        for (const auto &node : nodes) {
            pageIndicatorSeen = pageIndicatorSeen || looksLikePageIndicator(node.label);
            rememberVisibleScreen(node.label);
        }
        auto source = std::find_if(nodes.begin(), nodes.end(),
                [nodeId](const auto &node) { return node.id == nodeId && node.icon(); });
        if (source != nodes.end()) {
            AppTarget placement;
            if (resolveAppTarget(nodeId, source->label, placement,
                    g_visible_screen.load() + 1,
                    source->resource == "com.miui.home:id/folder" ? 2 : 0)) {
                if (!workspaceContainer(placement.placement.container)) {
                    sendAnnouncement(nodeId, "暂不支持移动文件夹内项目");
                    return false;
                }
                if (placement.placement.container == kDesktopContainer
                        && placement.placement.screen > 0) {
                    // The source placement is the reliable page when the
                    // accessibility page indicator is stale during a swipe.
                    g_visible_screen.store(placement.placement.screen - 1,
                            std::memory_order_release);
                    pageIndicatorSeen = true;
                }
            } else {
                nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                        "HyperOS4 lift source placement unresolved node=%d label=%s",
                        nodeId, source->label.c_str());
            }
        }
        const int32_t liftScreen = g_visible_screen.load(std::memory_order_acquire);
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "Lift session page=%d tree_nodes=%zu indicator=%d", liftScreen + 1,
                nodes.size(), pageIndicatorSeen ? 1 : 0);
        size_t liftedCount = 0;
        {
            std::lock_guard lock(g_session_mutex);
            g_workspace = std::move(nodes);
            auto source = std::find_if(g_workspace.begin(), g_workspace.end(),
                    [nodeId](const auto &node) { return node.id == nodeId && node.icon(); });
            if (source == g_workspace.end()) {
                nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                        "HyperOS4 lift source node missing node=%d tree_nodes=%zu",
                        nodeId, g_workspace.size());
                return false;
            }
            g_source = *source;
            g_sources.clear();
            g_source_placements.clear();
            if (g_source.selected) {
                for (const auto &node : g_workspace) {
                    if (node.icon() && node.selected && node.host == g_source.host
                            && node.window == g_source.window) {
                        g_sources.push_back(node);
                    }
                }
            }
            if (g_sources.empty()) g_sources.push_back(g_source);
            if (std::none_of(g_sources.begin(), g_sources.end(),
                    [nodeId](const auto &node) { return node.id == nodeId; })) {
                g_sources.insert(g_sources.begin(), g_source);
            }
            liftedCount = g_sources.size();
            rebuildSlots();
            const int32_t parent = findDropParentNode();
            g_drop_parent_node_id.store(parent, std::memory_order_release);
            for (const auto &node : g_workspace) {
                if (node.id == parent) {
                    g_drop_parent_host_id.store(node.host, std::memory_order_release);
                    g_drop_parent_window_id.store(node.window, std::memory_order_release);
                    break;
                }
            }
        }
        std::vector<ObservedNode> sourceNodes;
        {
            std::lock_guard lock(g_session_mutex);
            sourceNodes = g_sources;
        }
        std::vector<FavoritePlacement> sourcePlacements;
        sourcePlacements.reserve(sourceNodes.size());
        bool sourcePlacementsReady = !sourceNodes.empty();
        for (const auto &sourceNode : sourceNodes) {
            AppTarget target;
            if (!resolveAppTarget(sourceNode.id, sourceNode.label, target,
                        g_visible_screen.load() + 1,
                        sourceNode.resource == "com.miui.home:id/folder" ? 2 : 0)
                    || target.placement.id < 0
                    || !workspaceContainer(target.placement.container)) {
                sourcePlacementsReady = false;
                nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                        "HyperOS4 lift source placement cache deferred node=%d label=%s count=%zu",
                        sourceNode.id, sourceNode.label.c_str(), sourceNodes.size());
                break;
            }
            sourcePlacements.push_back(target.placement);
        }
        if (sourcePlacementsReady) {
            std::lock_guard lock(g_session_mutex);
            g_source_placements = std::move(sourcePlacements);
        }
        g_lifted_node_id.store(nodeId, std::memory_order_release);
        g_lifted_screen.store(g_visible_screen.load(std::memory_order_acquire),
                std::memory_order_release);
        g_lifted.store(true, std::memory_order_release);
        g_custom_action_calls.fetch_add(1, std::memory_order_relaxed);
        const auto parent = captureDropParentEventSource(nodeId);
        sendEvent(parent, kTypeWindowContentChanged);
        // System Home's announce_for_drag + drag_multiple_item_name resources.
        announceReliably(parent.host, parent.window, parent.nodeId,
                liftedCount == 1 ? "已拖起"
                        : "已拖起" + std::to_string(liftedCount) + "个图标");
        return true;
    }
    if (actionId == kCancelAction) {
        return cancelLiftSession(provider, nodeId);
    }
    if (actionId == kEditFolderAction) {
        if (!isFolderNode(provider, nodeId) || g_original_perform_action == nullptr) {
            return false;
        }
        const bool success = g_original_perform_action(provider, nodeId, 32, arguments);
        if (success) {
            sendEvent(nodeId, kTypeViewClicked);
        } else {
            sendAnnouncement(nodeId, "文件夹编辑不可用，请重新定位文件夹");
        }
        return success;
    }
    if (isVirtualNode(nodeId) && g_lifted.load(std::memory_order_acquire)
            && actionId == kAccessibilityFocusAction) {
        if (virtualNodeDescription(nodeId).empty()) return false;
        int32_t previous = g_virtual_focused_id.exchange(nodeId, std::memory_order_acq_rel);
        if (previous == nodeId) return true;
        if (previous >= kVirtualNodeBase && previous != nodeId) {
            sendEvent(previous, 65536);
        }
        sendEvent(nodeId, 32768);
        return true;
    }
    if (isVirtualNode(nodeId) && g_lifted.load(std::memory_order_acquire)
            && actionId == kClearAccessibilityFocusAction) {
        int32_t expected = nodeId;
        if (!g_virtual_focused_id.compare_exchange_strong(expected, -1,
                std::memory_order_acq_rel)) return false;
        sendEvent(nodeId, 65536);
        return true;
    }
    if (actionId == 16 && isVirtualNode(nodeId)
            && g_lifted.load(std::memory_order_acquire)) {
        return performNativePlacement(provider, nodeId, 0);
    }
    if (actionId == kMoveBeforeAction || actionId == kMoveAfterAction
            || actionId == kCreateFolderAction) {
        return performNativePlacement(provider, nodeId, actionId);
    }
    if (actionId == kAppInfoAction || actionId == kUninstallAction
            || (actionId >= kDynamicActionBase
            && actionId < kDynamicActionBase + kDynamicActionCount)) {
        Bounds ignored{};
        std::string label;
        if (!nodeBounds(provider, nodeId, ignored, &label)) return false;
        AppTarget target;
        const int32_t screenHint = g_visible_screen.load(std::memory_order_acquire) + 1;
        if (!resolveAppTarget(nodeId, label, target, screenHint)) return false;
        if (actionId == kAppInfoAction) {
            bool success = startAppInfo(target);
            if (!success) sendAnnouncement(nodeId, "应用信息不可用，请重新定位图标");
            return success;
        }
        if (actionId == kUninstallAction) {
            bool success = startUninstall(target);
            if (!success) sendAnnouncement(nodeId, "卸载不可用，请重新定位图标");
            return success;
        }
        if (target.shortcuts.empty()) loadDynamicShortcuts(target);
        int index = actionId - kDynamicActionBase;
        if (index < 0 || index >= static_cast<int>(target.shortcuts.size())) return false;
        const DynamicShortcut &shortcut = target.shortcuts[static_cast<size_t>(index)];
        bool success = startShortcut(shortcut);
        if (!success) sendAnnouncement(nodeId, shortcut.label + "不可用，请重新定位图标");
        return success;
    }
    if (isVirtualNode(nodeId)) return false;
    const bool handled = g_original_perform_action != nullptr
            && g_original_perform_action(provider, nodeId, actionId, arguments);
    if (handled && actionId == kAccessibilityFocusAction) {
        const int32_t hovered = g_virtual_hovered_id.exchange(-1);
        const int32_t focused = g_virtual_focused_id.exchange(-1);
        if (isVirtualNode(hovered)) sendEvent(hovered, 256);
        if (isVirtualNode(focused)) sendEvent(focused, 65536);
    }
    return handled;
}

bool onPerformAction(void *provider, int32_t nodeId, int32_t actionId, void *arguments) {
    const bool trace = g_diagnostics.session() != 0
            && (actionId == 16 || actionId >= kLiftAction || isVirtualNode(nodeId));
    if (trace) nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
            "action begin node=%d action=0x%x focus=%d lifted=%d page=%d hovered=%d focused=%d",
            nodeId, actionId, g_launcher_window_focused.load(), g_lifted.load() ? 1 : 0,
            g_visible_screen.load() + 1, g_virtual_hovered_id.load(), g_virtual_focused_id.load());
    const bool handled = performActionImpl(provider, nodeId, actionId, arguments);
    if (trace) nativeLog(handled ? ANDROID_LOG_INFO : ANDROID_LOG_WARN, "ActionGridNative",
            "action end node=%d action=0x%x handled=%d lifted=%d move_pending=%d",
            nodeId, actionId, handled ? 1 : 0, g_lifted.load() ? 1 : 0, g_model_move_running.load() ? 1 : 0);
    return handled;
}

void *findLoadedHandle(const char *libraryBase) {
    struct Query {
        const char *base;
        void *handle;
    } query{libraryBase, nullptr};
    dl_iterate_phdr([](struct dl_phdr_info *info, size_t, void *opaque) {
        auto *query = static_cast<Query *>(opaque);
        if (query->handle == nullptr && std::strcmp(baseName(info->dlpi_name), query->base) == 0
                && info->dlpi_name != nullptr && info->dlpi_name[0] != '\0') {
            query->handle = dlopen(info->dlpi_name, RTLD_NOW | RTLD_NOLOAD);
        }
        return 0;
    }, &query);
    return query.handle;
}

void resolveLauncherApis(void *handle) {
    auto resolve = [handle](const char *symbol) {
        void *address = handle == nullptr ? nullptr : dlsym(handle, symbol);
        return address != nullptr ? address : dlsym(RTLD_DEFAULT, symbol);
    };
#define RESOLVE_NATIVE(field, type, symbol) field = reinterpret_cast<type>(resolve(symbol))
    RESOLVE_NATIVE(g_frb_dispatcher, FrbDispatcherFn, "frb_pde_ffi_dispatcher_primary");
    RESOLVE_NATIVE(g_motion_new, MotionNewFn, "input_MotionEvent_new");
    RESOLVE_NATIVE(g_motion_action_masked, MotionActionFn, "input_MotionEvent_getActionMasked");
    RESOLVE_NATIVE(g_motion_set_action, MotionSetActionFn, "input_MotionEvent_setAction");
    RESOLVE_NATIVE(g_motion_clone, MotionCloneFn, "input_MotionEvent_clone");
    RESOLVE_NATIVE(g_motion_drop, DropFn, "input_MotionEvent_drop");
    RESOLVE_NATIVE(g_motion_raw_x, MotionFloatFn, "input_MotionEvent_getRawX");
    RESOLVE_NATIVE(g_motion_raw_y, MotionFloatFn, "input_MotionEvent_getRawY");
    RESOLVE_NATIVE(g_input_event, InputEventFn, "input_InputEvent_newWithMotionEvent");
    RESOLVE_NATIVE(g_input_drop, DropFn, "input_InputEvent_drop");
    RESOLVE_NATIVE(g_inject_input, InjectInputFn, "input_InputManager_injectInputEvent");
    RESOLVE_NATIVE(g_launcher_apps_default, LauncherAppsDefaultFn, "LauncherApps_default");
    RESOLVE_NATIVE(g_launcher_apps_drop, LauncherAppsDropFn, "LauncherApps_drop");
    RESOLVE_NATIVE(g_shortcut_query_default, ShortcutQueryDefaultFn, "ShortcutQuery_default");
    RESOLVE_NATIVE(g_shortcut_query_drop, ShortcutQueryDropFn, "ShortcutQuery_drop");
    RESOLVE_NATIVE(g_shortcut_query_set_flags, ShortcutQuerySetFlagsFn, "ShortcutQuery_set_query_flags");
    RESOLVE_NATIVE(g_shortcut_query_set_package, ShortcutQuerySetPackageFn, "ShortcutQuery_set_package_name");
    RESOLVE_NATIVE(g_user_handle_new, UserHandleNewFn, "UserHandle_new");
    RESOLVE_NATIVE(g_user_handle_set, UserHandleSetFn, "UserHandle_set_handle");
    RESOLVE_NATIVE(g_user_handle_drop, UserHandleDropFn, "UserHandle_drop");
    RESOLVE_NATIVE(g_bundle_default, BundleDefaultFn, "Bundle_default");
    RESOLVE_NATIVE(g_bundle_drop, BundleDropFn, "Bundle_drop");
    RESOLVE_NATIVE(g_bundle_insert_boolean, BundleInsertBooleanFn, "Bundle_insert_boolean");
    RESOLVE_NATIVE(g_bundle_insert_i32, BundleInsertI32Fn, "Bundle_insert_i32");
    RESOLVE_NATIVE(g_bundle_insert_string, BundleInsertStringFn, "Bundle_insert_string");
    RESOLVE_NATIVE(g_component_name_new, ComponentNameNewFn, "ComponentName_new");
    RESOLVE_NATIVE(g_component_name_drop, ComponentNameDropFn, "ComponentName_drop");
    RESOLVE_NATIVE(g_launcher_apps_get_shortcuts, LauncherAppsGetShortcutsFn, "LauncherApps_get_shortcuts");
    RESOLVE_NATIVE(g_launcher_apps_has_permission, LauncherAppsHasPermissionFn, "LauncherApps_has_shortcut_host_permission");
    RESOLVE_NATIVE(g_launcher_apps_start_shortcut, LauncherAppsStartShortcutFn, "LauncherApps_start_shortcut");
    RESOLVE_NATIVE(g_launcher_apps_start_app_info, LauncherAppsStartAppInfoFn, "LauncherApps_start_app_details_activity");
    RESOLVE_NATIVE(g_launcher_apps_start_main_activity, LauncherAppsStartMainActivityFn, "LauncherApps_start_main_activity");
    RESOLVE_NATIVE(g_intent_default, IntentDefaultFn, "Intent_default");
    RESOLVE_NATIVE(g_intent_set_action, IntentSetActionFn, "Intent_set_action");
    RESOLVE_NATIVE(g_intent_set_component, IntentSetComponentFn, "Intent_set_component");
    RESOLVE_NATIVE(g_intent_set_extras, IntentSetExtrasFn, "Intent_set_extras");
    RESOLVE_NATIVE(g_scene_start_activity, SceneStartActivityFn, "Scene_start_activity");
    RESOLVE_NATIVE(g_key_code, MotionActionFn, "input_KeyEvent_getKeyCode");
    RESOLVE_NATIVE(g_key_action, MotionActionFn, "input_KeyEvent_getAction");
    if (g_scene_start_activity == nullptr) {
        g_scene_start_activity = reinterpret_cast<SceneStartActivityFn>(
                findLoadedSymbol("libhyper_os_ui_public.so", "Scene_start_activity"));
    }
    RESOLVE_NATIVE(g_shortcut_info_drop, ShortcutInfoDropFn, "ShortcutInfo_drop");
    RESOLVE_NATIVE(g_shortcut_info_get_flags, ShortcutInfoGetFlagsFn, "ShortcutInfo_get_flags");
    RESOLVE_NATIVE(g_shortcut_info_get_user_id, ShortcutInfoGetUserIdFn, "ShortcutInfo_get_user_id");
    RESOLVE_NATIVE(g_shortcut_info_get_title, ShortcutInfoGetTextFn, "ShortcutInfo_get_title");
    RESOLVE_NATIVE(g_shortcut_info_get_text, ShortcutInfoGetTextFn, "ShortcutInfo_get_text");
    RESOLVE_NATIVE(g_shortcut_info_get_id, ShortcutInfoGetTextFn, "ShortcutInfo_get_id");
    RESOLVE_NATIVE(g_shortcut_info_get_package, ShortcutInfoGetTextFn, "ShortcutInfo_get_package_name");
    RESOLVE_NATIVE(g_task_get_max_recent_tasks, TaskGetMaxRecentTasksFn,
            "Task_get_max_recent_tasks");
    RESOLVE_NATIVE(g_task_get_recent_tasks, TaskGetRecentTasksFn, "Task_get_recent_tasks");
    RESOLVE_NATIVE(g_recent_task_get_task_id, RecentTaskInfoGetTaskIdFn,
            "RecentTaskInfo_get_task_id");
    RESOLVE_NATIVE(g_recent_task_get_user_id, RecentTaskInfoGetUserIdFn,
            "RecentTaskInfo_get_user_id");
    RESOLVE_NATIVE(g_recent_task_get_record_name, RecentTaskInfoGetRecordNameFn,
            "RecentTaskInfo_get_top_activity_record_name");
    RESOLVE_NATIVE(g_recent_task_get_base_activity, RecentTaskInfoGetActivityFn,
            "RecentTaskInfo_get_base_activity");
    RESOLVE_NATIVE(g_component_name_get_package, ComponentNameGetStringFn,
            "ComponentName_get_package_name");
    RESOLVE_NATIVE(g_recent_task_drop, RecentTaskInfoDropFn, "RecentTaskInfo_drop");
    RESOLVE_NATIVE(g_runtime_inc_strong, RuntimeIncStrongFn, "Runtime_inc_strong");
    RESOLVE_NATIVE(g_runtime_get_context, RuntimeGetContextFn, "Runtime_get_context");
    RESOLVE_NATIVE(g_content_resolver_new, ContentResolverNewFn, "content_resolver_new");
    RESOLVE_NATIVE(g_content_resolver_delete, ContentResolverDeleteFn, "content_resolver_delete");
    RESOLVE_NATIVE(g_content_resolver_call, ContentResolverCallFn, "content_resolver_operation_call");
    RESOLVE_NATIVE(g_bundle_get_i64, BundleGetI64Fn, "Bundle_get_i64");
    RESOLVE_NATIVE(g_bundle_insert_i64, BundleInsertI64Fn, "Bundle_insert_i64");
    RESOLVE_NATIVE(g_sqlite_open_v2, SqliteOpenV2Fn, "sqlite3_open_v2");
    RESOLVE_NATIVE(g_sqlite_prepare_v2, SqlitePrepareFn, "sqlite3_prepare_v2");
    RESOLVE_NATIVE(g_sqlite_step, SqliteStepFn, "sqlite3_step");
    RESOLVE_NATIVE(g_sqlite_finalize, SqliteFinalizeFn, "sqlite3_finalize");
    RESOLVE_NATIVE(g_sqlite_column_text, SqliteColumnTextFn, "sqlite3_column_text");
    RESOLVE_NATIVE(g_sqlite_column_int, SqliteColumnIntFn, "sqlite3_column_int");
    RESOLVE_NATIVE(g_sqlite_column_int64, SqliteColumnInt64Fn, "sqlite3_column_int64");
    RESOLVE_NATIVE(g_sqlite_close_v2, SqliteCloseFn, "sqlite3_close_v2");
    RESOLVE_NATIVE(g_sqlite_busy_timeout, SqliteBusyTimeoutFn, "sqlite3_busy_timeout");
    RESOLVE_NATIVE(g_sqlite_exec, SqliteExecFn, "sqlite3_exec");
#undef RESOLVE_NATIVE
    requestSnapshotRefresh(true);
    nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
            "shortcut relay symbols intent=%p action=%p component=%p scene=%p",
            reinterpret_cast<void *>(g_intent_default),
            reinterpret_cast<void *>(g_intent_set_action),
            reinterpret_cast<void *>(g_intent_set_component),
            reinterpret_cast<void *>(g_scene_start_activity));
    if (g_content_resolver_new != nullptr && g_api != nullptr && g_api->hook_func != nullptr
            && g_original_content_resolver_new == nullptr) {
        void *backup = nullptr;
        int result = g_api->hook_func(reinterpret_cast<void *>(g_content_resolver_new),
                reinterpret_cast<void *>(&onContentResolverNew), &backup);
        if (result == 0 && backup != nullptr) {
            g_original_content_resolver_new = reinterpret_cast<ContentResolverNewFn>(backup);
        } else {
            g_original_content_resolver_new = g_content_resolver_new;
        }
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "Runtime capture hook result=%d", result);
    }
    if (g_scene_start_activity != nullptr && g_api != nullptr && g_api->hook_func != nullptr
            && !g_scene_hook_attempted.exchange(true)) {
        void *backup = nullptr;
        int result = g_api->hook_func(reinterpret_cast<void *>(g_scene_start_activity),
                reinterpret_cast<void *>(&onSceneStartActivity), &backup);
        if (result == 0 && backup != nullptr) {
            g_original_scene_start_activity = reinterpret_cast<SceneStartActivityFn>(backup);
        }
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "Scene start capture hook result=%d target=%p backup=%p",
                result, reinterpret_cast<void *>(g_scene_start_activity), backup);
    }
    bool shortcuts = g_launcher_apps_default && g_shortcut_query_default
            && g_shortcut_query_set_flags && g_shortcut_query_set_package
            && g_launcher_apps_get_shortcuts && g_shortcut_info_drop
            && g_shortcut_info_get_flags && g_shortcut_info_get_user_id
            && g_shortcut_info_get_title && g_shortcut_info_get_id
            && g_shortcut_info_get_package && g_user_handle_new && g_user_handle_set;
    if (shortcuts) g_capabilities.fetch_or(CAP_SHORTCUT_APIS, std::memory_order_release);
    if (g_launcher_apps_start_app_info && g_component_name_new && g_bundle_default
            && g_runtime_inc_strong) g_capabilities.fetch_or(CAP_APP_INFO_API, std::memory_order_release);
    if (g_inject_input != nullptr) g_capabilities.fetch_or(CAP_INPUT_API, std::memory_order_release);
    char dartBuildId[33] = {};
    LauncherAbiProfile profile = refreshLauncherAbiProfile(
            dartBuildId, sizeof(dartBuildId));
    bool motionBuildMatch = matchesLauncherMotionBuildId(dartBuildId);
    bool frbBuildMatch = profile != LauncherAbiProfile::kUnknown
            && matchesFrbModelBuildId(dartBuildId);
    g_launcher_start_freeform = nullptr;
    g_launcher_launch_sosc = nullptr;
    if (motionBuildMatch) {
        void *base = launcherLibraryBase();
        if (base != nullptr) {
            constexpr uintptr_t kLauncherStartFreeformOffset = 0x8f0440;
            constexpr uintptr_t kLauncherLaunchSoscOffset = 0x8f0574;
            g_launcher_start_freeform = reinterpret_cast<LauncherStartFreeformFn>(
                    reinterpret_cast<uintptr_t>(base) + kLauncherStartFreeformOffset);
            g_launcher_launch_sosc = reinterpret_cast<LauncherLaunchSoscFn>(
                    reinterpret_cast<uintptr_t>(base) + kLauncherLaunchSoscOffset);
        }
    }
    const uintptr_t motionOffset = profile == LauncherAbiProfile::kCurrent8565 ? 0xd40fd8
            : profile == LauncherAbiProfile::kLegacy7748 ? 0xd37904 : 0;
    if (motionOffset != 0 && g_launcher_fingerprint_ready.load()
            && !g_launcher_motion_hook_attempted.exchange(true)
            && g_api != nullptr && g_api->hook_func != nullptr
            && g_motion_action_masked != nullptr && g_motion_raw_x != nullptr
            && g_motion_raw_y != nullptr && g_motion_clone != nullptr
            && g_motion_drop != nullptr && g_motion_set_action != nullptr) {
        void *base = launcherLibraryBase();
        void *target = base == nullptr ? nullptr
                : reinterpret_cast<void *>(reinterpret_cast<uintptr_t>(base)
                        + motionOffset);
        constexpr uint32_t prologue[] = {
                0xd103c3ffU, 0xa90c7bfdU, 0xf9006bf5U, 0xa90e4ff4U, 0x910303fdU};
        if (target != nullptr && std::memcmp(target, prologue, sizeof(prologue)) == 0) {
            void *backup = nullptr;
            int result = g_api->hook_func(target,
                    reinterpret_cast<void *>(&onLauncherMotion), &backup);
            if (result == 0 && backup != nullptr) {
                g_original_launcher_motion = reinterpret_cast<LauncherMotionFn>(backup);
            }
            nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                    "Launcher motion hook result=%d target=%p backup=%p build=%s",
                    result, target, backup, dartBuildId);
        }
    }
    g_trigger_workspace_reload = resolveWorkspaceReloadForProfile(profile, dartBuildId);
    resolveGridCounts(profile);
    installLauncherLifecycleHooks(profile);
    g_capabilities.fetch_and(~(CAP_FRB_MODEL | CAP_MOVE_PROFILE),
            std::memory_order_release);
    if (g_frb_dispatcher != nullptr && frbBuildMatch) {
        g_capabilities.fetch_or(CAP_FRB_MODEL, std::memory_order_release);
        if (g_trigger_workspace_reload != nullptr) {
            g_capabilities.fetch_or(CAP_MOVE_PROFILE, std::memory_order_release);
        }
    } else if (g_frb_dispatcher != nullptr) {
        nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                "FRB model ABI disabled: profile=%s libapp Build ID=%s fingerprint=%d",
                launcherAbiProfileName(profile), dartBuildId,
                g_launcher_fingerprint_ready.load(std::memory_order_acquire) ? 1 : 0);
    }
    nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
            "Native LauncherApps APIs profile=%s shortcuts=%d appInfo=%d sqlite=%d frb_model=%d move=%d",
            launcherAbiProfileName(profile),
            shortcuts ? 1 : 0,
            (g_capabilities.load() & CAP_APP_INFO_API) != 0 ? 1 : 0,
            g_sqlite_open_v2 != nullptr ? 1 : 0,
            (g_capabilities.load() & CAP_FRB_MODEL) != 0 ? 1 : 0,
            (g_capabilities.load() & CAP_MOVE_PROFILE) != 0 ? 1 : 0);
    g_diagnostics_apis_ready.store(true, std::memory_order_release);
    ensureDiagnosticsWorker();
}

void resolvePrivate(const char *name, void *handle) {
    char aidlId[33] = {};
    char uiId[33] = {};
    char buildId[sizeof(g_private_build_id)] = {};
    const bool privateBuildIdReady = readBuildId(name, buildId, sizeof(buildId));
    if (!privateBuildIdReady) {
        nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                "Rust accessibility library has no readable Build ID: %s; using ELF fingerprint",
                name);
    }
    LauncherAbiProfile profile = g_launcher_profile.load(std::memory_order_acquire);
    if (profile == LauncherAbiProfile::kUnknown) {
        char dartBuildId[33] = {};
        profile = refreshLauncherAbiProfile(dartBuildId, sizeof(dartBuildId));
    }
    ElfFingerprint privateFingerprint{};
    ElfFingerprint aidlFingerprint{};
    ElfFingerprint uiFingerprint{};
    const bool systemFingerprintReady = readModuleFingerprint(name, privateFingerprint)
            && readModuleFingerprint("libhyper_os_aidl.dylib.so", aidlFingerprint)
            && readModuleFingerprint("libhyper_os_ui_public.so", uiFingerprint);
    readBuildId("libhyper_os_aidl.dylib.so", aidlId, sizeof(aidlId));
    readBuildId("libhyper_os_ui_public.so", uiId, sizeof(uiId));
    std::lock_guard lock(g_mutex);
    std::memcpy(g_private_build_id, buildId, sizeof(g_private_build_id));
    if (!systemFingerprintReady || !knownSystemAbi(
            privateFingerprint, aidlFingerprint, uiFingerprint)) {
        g_launcher_system_compatible.store(false, std::memory_order_release);
        g_capabilities.fetch_and(~(CAP_PRIVATE_BUILD_MATCH | CAP_PRIVATE_SYMBOLS
                | CAP_SELECTION_STATE | CAP_FRB_MODEL | CAP_MOVE_PROFILE),
                std::memory_order_release);
        nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                "Rust accessibility ABI mismatch: profile=%s private=%s aidl=%s ui=%s fingerprint=%d",
                launcherAbiProfileName(profile), buildId, aidlId, uiId,
                systemFingerprintReady ? 1 : 0);
        return;
    }
    const bool paired = profileMatchesSystemAbi(profile, buildId, aidlId, uiId,
            privateFingerprint, aidlFingerprint, uiFingerprint);
    g_launcher_system_compatible.store(paired, std::memory_order_release);
    if (paired) installLauncherLifecycleHooks(profile);
    if (!paired) {
        g_capabilities.fetch_and(~(CAP_FRB_MODEL | CAP_MOVE_PROFILE), std::memory_order_release);
    }
    g_capabilities.fetch_or(CAP_PRIVATE_BUILD_MATCH, std::memory_order_release);
    g_capabilities.fetch_or(CAP_SELECTION_STATE, std::memory_order_release);
    auto resolve = [handle](const char *symbol) {
        void *address = handle == nullptr ? nullptr : dlsym(handle, symbol);
        return address != nullptr ? address : findLoadedSymbol(
                "libhyper_os_accessibility_private.dylib.so", symbol);
    };
    void *sendEvent = resolve(kSendEventSymbol);
    if (g_original_send_event == nullptr && sendEvent != nullptr
            && g_api != nullptr && g_api->hook_func != nullptr) {
        void *backup = nullptr;
        int result = g_api->hook_func(sendEvent,
                reinterpret_cast<void *>(&onSendEvent), &backup);
        if (result == 0 && backup != nullptr) {
            g_original_send_event = reinterpret_cast<EventSendFn>(backup);
        }
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "Rust send event hook result=%d symbol=%p backup=%p",
                result, sendEvent, backup);
    }
    void *createNode = resolve(kCreateNodeSymbol);
    void *providerPerformAction = resolve(kProviderPerformActionSymbol);
    void *providerFindFocus = resolve(kProviderFindFocusSymbol);
    void *interactionPerformAction = resolve(kInteractionPerformActionSymbol);
    void *connectionPerformAction = resolve(kConnectionPerformActionSymbol);
    if (createNode != nullptr && providerPerformAction != nullptr
            && interactionPerformAction != nullptr && connectionPerformAction != nullptr) {
        g_capabilities.fetch_or(CAP_PRIVATE_SYMBOLS, std::memory_order_release);
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
            "Rust ABI symbols resolved for verified ELF fingerprint");
        if (g_original_create_node == nullptr) {
            g_create_node = reinterpret_cast<CreateNodeFn>(createNode);
            if (!g_create_hook_attempted.exchange(true, std::memory_order_acq_rel)
                    && g_api != nullptr && g_api->hook_func != nullptr) {
                void *backup = nullptr;
                int result = g_api->hook_func(createNode,
                        reinterpret_cast<void *>(&onCreateNode), &backup);
                if (result == 0 && backup != nullptr) {
                    g_original_create_node = reinterpret_cast<CreateNodeFn>(backup);
                    g_create_node = g_original_create_node;
                } else {
                    g_original_create_node = reinterpret_cast<CreateNodeFn>(createNode);
                }
                nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                        "Rust create node hook result=%d", result);
            } else {
                g_original_create_node = reinterpret_cast<CreateNodeFn>(createNode);
            }
        }
        if (g_original_perform_action == nullptr && g_api != nullptr) {
            void *backup = nullptr;
            int result = g_api->hook_func(providerPerformAction,
                    reinterpret_cast<void *>(&onPerformAction), &backup);
            if (result == 0) g_original_perform_action = reinterpret_cast<PerformActionFn>(backup);
            nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                    "Rust perform action hook result=%d", result);
        }
        if (providerFindFocus != nullptr && g_original_find_focus == nullptr
                && !g_find_focus_hook_attempted.exchange(true, std::memory_order_acq_rel)
                && g_api != nullptr && g_api->hook_func != nullptr) {
            void *backup = nullptr;
            int result = g_api->hook_func(providerFindFocus,
                    reinterpret_cast<void *>(&onFindFocus), &backup);
            if (result == 0 && backup != nullptr) {
                g_original_find_focus = reinterpret_cast<FindFocusFn>(backup);
            } else {
                g_original_find_focus = reinterpret_cast<FindFocusFn>(providerFindFocus);
            }
            nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                    "Rust provider find focus hook result=%d symbol=%p backup=%p",
                    result, providerFindFocus, backup);
        }
    } else {
        g_capabilities.fetch_and(~CAP_PRIVATE_SYMBOLS, std::memory_order_release);
        nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                "Rust ABI symbols are incomplete; native actions remain hidden");
    }
}

void resolvePublic(void *handle) {
    if (handle == nullptr) {
        return;
    }
    char buildId[sizeof(kExpectedPublicBuildId)] = {};
    readBuildId("libhyper_os_accessibility_public.so", buildId, sizeof(buildId));
    ElfFingerprint fingerprint{};
    if (!readModuleFingerprint("libhyper_os_accessibility_public.so", fingerprint)
            || !fingerprintMatches(fingerprint, kPublicFingerprint)) {
        nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                "Rust public accessibility ELF fingerprint mismatch: build=%s", buildId);
        g_capabilities.fetch_and(~(CAP_PUBLIC_BUILD_MATCH | CAP_PUBLIC_NODE_API
                | CAP_NODE_HOOK_INSTALLED | CAP_PAGE_SCROLL_EVENT), std::memory_order_release);
        return;
    }
    g_capabilities.fetch_or(CAP_PUBLIC_BUILD_MATCH, std::memory_order_release);
    constexpr const char *symbols[] = {
            "AccessibilityAction_new",
            "AccessibilityAction_drop",
            "AccessibilityNodeInfo_new",
            "AccessibilityNodeInfo_add_action",
            "AccessibilityNodeInfo_add_child_with_id",
            "AccessibilityNodeInfo_drop",
            "AccessibilityNodeInfo_set_soruce",
            "AccessibilityNodeInfo_set_bounds_in_screen",
            "AccessibilityNodeInfo_set_class_name",
            "AccessibilityNodeInfo_set_package_name",
            "AccessibilityNodeInfo_set_content_description",
            "AccessibilityNodeInfo_set_view_id_resource_name",
            "AccessibilityNodeInfo_set_visible_to_user",
            "AccessibilityNodeInfo_set_enabled",
            "AccessibilityNodeInfo_set_clickable",
            "AccessibilityNodeInfo_set_focusable",
            "AccessibilityNodeInfo_set_text",
    };
    for (const char *symbol : symbols) {
        if (dlsym(handle, symbol) == nullptr) {
            g_capabilities.fetch_and(~CAP_PUBLIC_NODE_API, std::memory_order_release);
            nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                    "Rust public node symbol missing: %s", symbol);
            return;
        }
    }
    g_capabilities.fetch_or(CAP_PUBLIC_NODE_API, std::memory_order_release);
    void *privateHandle = findLoadedHandle(
            "libhyper_os_accessibility_private.dylib.so");
    resolvePrivate("libhyper_os_accessibility_private.dylib.so", privateHandle);
    g_action_new = reinterpret_cast<ActionNewFn>(dlsym(handle, "AccessibilityAction_new"));
    g_action_drop = reinterpret_cast<ActionDropFn>(dlsym(handle, "AccessibilityAction_drop"));
    g_node_drop = reinterpret_cast<DropFn>(dlsym(handle, "AccessibilityNodeInfo_drop"));
    g_node_new = reinterpret_cast<NodeNewFn>(dlsym(handle, "AccessibilityNodeInfo_new"));
    g_node_add_child = reinterpret_cast<NodeAddChildFn>(
            dlsym(handle, "AccessibilityNodeInfo_add_child_with_id"));
    g_node_set_source = reinterpret_cast<NodeSetSourceFn>(
            dlsym(handle, "AccessibilityNodeInfo_set_soruce"));
    g_node_set_parent = reinterpret_cast<NodeSetParentFn>(
            dlsym(handle, "AccessibilityNodeInfo_set_parent"));
    g_node_set_traversal_after = reinterpret_cast<NodeSetTraversalFn>(
            dlsym(handle, "AccessibilityNodeInfo_set_traversal_after"));
    g_node_set_traversal_before = reinterpret_cast<NodeSetTraversalFn>(
            dlsym(handle, "AccessibilityNodeInfo_set_traversal_before"));
    if (g_node_set_traversal_before == nullptr) {
        g_node_set_traversal_before = reinterpret_cast<NodeSetTraversalFn>(findLoadedSymbol(
                "libhyper_os_aidl.dylib.so",
                "_RNvMs6_NtNtNtNtNtNtCs2GTLDA1zkMw_13hyper_os_aidl3src3ext7android4view13accessibility27accessibility_node_info_extNtNtNtNtNtBf_7android4view13accessibility21AccessibilityNodeInfo21AccessibilityNodeInfo20set_traversal_before"));
    }
    g_node_set_important = reinterpret_cast<NodeSetBoolFn>(
            dlsym(handle, "AccessibilityNodeInfo_set_important_for_accessibility"));
    g_node_set_accessibility_focused = reinterpret_cast<NodeSetBoolFn>(
            dlsym(handle, "AccessibilityNodeInfo_set_accessibility_focused"));
    g_event_new = reinterpret_cast<EventNewFn>(dlsym(handle, "AccessibilityEvent_obtain"));
    g_event_drop = reinterpret_cast<DropFn>(dlsym(handle, "AccessibilityEvent_drop"));
    g_event_set_source = reinterpret_cast<NodeSetSourceFn>(
            dlsym(handle, "AccessibilityEvent_record_set_source"));
    g_event_set_package = reinterpret_cast<NodeSetStringFn>(
            dlsym(handle, "AccessibilityEvent_set_package_name"));
    g_event_set_change = reinterpret_cast<EventIntFn>(
            dlsym(handle, "AccessibilityEvent_set_content_change_type"));
    g_event_set_event_type = reinterpret_cast<EventIntFn>(
            dlsym(handle, "AccessibilityEvent_set_event_type"));
    g_event_set_item_count = reinterpret_cast<EventIntFn>(
            dlsym(handle, "AccessibilityEvent_record_set_item_count"));
    g_event_set_from_index = reinterpret_cast<EventIntFn>(
            dlsym(handle, "AccessibilityEvent_record_set_from_index"));
    g_event_set_to_index = reinterpret_cast<EventIntFn>(
            dlsym(handle, "AccessibilityEvent_record_set_to_index"));
    g_event_push_text = reinterpret_cast<EventPushTextFn>(
            dlsym(handle, "AccessibilityEvent_record_push_text"));
    if (g_original_send_event == nullptr && g_api != nullptr) {
        void *send = dlsym(handle, "AccessibilityController_request_send_accessibility_event");
        void *backup = nullptr;
        int result = send == nullptr ? -1 : g_api->hook_func(send,
                reinterpret_cast<void *>(&onSendEvent), &backup);
        if (result == 0 && backup != nullptr) {
            g_original_send_event = reinterpret_cast<EventSendFn>(backup);
            nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                    "Accessibility public send hook installed");
        }
    }
    const bool announcementReady = g_event_new != nullptr && g_event_drop != nullptr
            && g_event_set_source != nullptr && g_event_push_text != nullptr
            && g_rstring_from_utf16 != nullptr && g_original_send_event != nullptr;
    if (announcementReady) {
        // The older public suite has no scroll-record setters.  It still
        // accepts an announcement event, so keep page rewriting enabled and
        // let sendPageAnnouncementWithSource choose the available record.
        g_capabilities.fetch_or(CAP_PAGE_SCROLL_EVENT, std::memory_order_release);
    } else {
        g_capabilities.fetch_and(~CAP_PAGE_SCROLL_EVENT, std::memory_order_release);
    }
    g_node_set_bounds = reinterpret_cast<NodeSetBoundsFn>(
            dlsym(handle, "AccessibilityNodeInfo_set_bounds_in_screen"));
    g_node_set_parent_bounds = reinterpret_cast<NodeSetBoundsFn>(
            dlsym(handle, "AccessibilityNodeInfo_set_bounds_in_parent"));
    g_node_set_class_name = reinterpret_cast<NodeSetStringFn>(
            dlsym(handle, "AccessibilityNodeInfo_set_class_name"));
    g_node_set_package_name = reinterpret_cast<NodeSetStringFn>(
            dlsym(handle, "AccessibilityNodeInfo_set_package_name"));
    g_node_set_content_description = reinterpret_cast<NodeSetStringFn>(
            dlsym(handle, "AccessibilityNodeInfo_set_content_description"));
    g_node_set_view_id = reinterpret_cast<NodeSetStringFn>(
            dlsym(handle, "AccessibilityNodeInfo_set_view_id_resource_name"));
    g_node_set_visible = reinterpret_cast<NodeSetBoolFn>(
            dlsym(handle, "AccessibilityNodeInfo_set_visible_to_user"));
    g_node_set_enabled = reinterpret_cast<NodeSetBoolFn>(
            dlsym(handle, "AccessibilityNodeInfo_set_enabled"));
    g_node_set_clickable = reinterpret_cast<NodeSetBoolFn>(
            dlsym(handle, "AccessibilityNodeInfo_set_clickable"));
    g_node_set_focusable = reinterpret_cast<NodeSetBoolFn>(
            dlsym(handle, "AccessibilityNodeInfo_set_focusable"));
    constexpr char kRStringFromUtf16[] =
            "_RNvMNtNtCs9Neji4M1weT_10abi_stable9std_types6stringNtB2_7RString10from_utf16";
    void *abiStable = dlopen("libabi_stable.dylib.so", RTLD_NOW | RTLD_NOLOAD);
    g_rstring_from_utf16 = reinterpret_cast<RStringFromUtf16Fn>(
            abiStable == nullptr ? dlsym(RTLD_DEFAULT, kRStringFromUtf16)
                                 : dlsym(abiStable, kRStringFromUtf16));
    if (g_action_new == nullptr || g_action_drop == nullptr || g_rstring_from_utf16 == nullptr) {
        nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                "Rust action constructor ABI is unavailable; custom actions remain hidden");
    }
    nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
            "Rust public node ABI resolved; installing node action hook traversal=%d",
            g_node_set_traversal_after != nullptr ? 1 : 0);
    auto addAction = reinterpret_cast<AddActionFn>(
            dlsym(handle, "AccessibilityNodeInfo_add_action"));
    if (!g_node_hook_attempted.exchange(true, std::memory_order_acq_rel)
            && g_api != nullptr && g_api->hook_func != nullptr && addAction != nullptr) {
        void *backup = nullptr;
        int result = g_api->hook_func(reinterpret_cast<void *>(addAction),
                reinterpret_cast<void *>(&onAddAction), &backup);
        if (result == 0 && backup != nullptr) {
            g_original_add_action = reinterpret_cast<AddActionFn>(backup);
            g_capabilities.fetch_or(CAP_NODE_HOOK_INSTALLED, std::memory_order_release);
            nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "Rust node action hook installed; direct TalkBack action enabled");
        } else {
            nativeLog(ANDROID_LOG_WARN, "ActionGridNative",
                    "Rust node action hook rejected: %d", result);
        }
    }
}

void onAddAction(void *node, void *action) {
    if (g_original_perform_action == nullptr
            && !g_private_retry_attempted.exchange(true, std::memory_order_acq_rel)) {
        void *privateHandle = findLoadedHandle(
                "libhyper_os_accessibility_private.dylib.so");
        resolvePrivate("libhyper_os_accessibility_private.dylib.so", privateHandle);
    }
    uint32_t count = g_node_action_calls.fetch_add(1, std::memory_order_relaxed) + 1U;
    if (count == 1U) {
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "Rust accessibility node action stream observed");
    }
    if (g_original_add_action != nullptr) {
        g_original_add_action(node, action);
    }
}

void injectIconActions(void *node) {
    const std::string resource = node == nullptr ? std::string() : nodeString(node, 0x1b8);
    const bool folder = resource == "com.miui.home:id/folder";
    const bool icon = resource == "com.miui.home:id/icon_icon";
    if (!folder && (!icon || !isIconNode(node))) return;
    if (folder) {
        // HyperOS 4's native launcher may expose FolderIcon as a Button.
        // Keep the desktop item a generic view so TalkBack presents its
        // explicit actions instead of a button role.
        setNodeString(g_node_set_class_name, node, u"android.view.View");
    }
    const int32_t sourceId = static_cast<int32_t>(
            nodeField<int64_t>(node, 0x338) >> 32U);
    if (folder) repairFolderBounds(node, sourceId);
    const bool lifted = g_lifted.load(std::memory_order_acquire);
    const bool liftedSource = isLiftedSource(sourceId);
    const bool moveReady = moveActionsReady();
    const std::string iconLabel = nodeString(node, 0x138);
    if (!lifted || !liftedSource) {
        injectDirectAction(node, kLiftAction,
                nodeSelected(node) ? kMultiLiftLabel : kLiftLabel);
    }
    if (folder && !lifted) {
        injectDirectAction(node, kEditFolderAction, kEditFolderLabel);
    }
    if (lifted) {
        injectDirectAction(node, kCancelAction, kCancelLabel);
        const Bounds bounds = nodeField<Bounds>(node, kNodeBoundsOffset);
        std::string location;
        {
            std::lock_guard lock(g_session_mutex);
            const bool hotseat = hotseatNodeLocked(describeNode(node));
            if (hotseat) location = "，底栏";
            for (const auto &cell : g_grid_cells) {
                if (hotseat) break;
                if (bounds.centerX() >= cell.bounds.left && bounds.centerX() < cell.bounds.right
                        && bounds.centerY() >= cell.bounds.top && bounds.centerY() < cell.bounds.bottom) {
                    location = "，第 " + std::to_string(cell.row + 1)
                            + " 行第 " + std::to_string(cell.column + 1) + " 列";
                    break;
                }
            }
        }
        if (!location.empty()) {
            const auto description = utf16FromUtf8(iconLabel + location);
            setNodeString(g_node_set_content_description, node, description.c_str());
        }
    }
    if (lifted && !liftedSource && moveReady) {
        std::u16string spoken = u"移到 ";
        spoken += utf16FromUtf8(iconLabel);
        std::u16string before = spoken;
        before += u"前面";
        std::u16string after = spoken;
        after += u"后面";
        injectDirectActionText(node, kMoveBeforeAction, before);
        injectDirectActionText(node, kMoveAfterAction, after);
    } else if (!lifted) {
        AppTarget target = targetForNode(sourceId, node);
        if (!target.package.empty() && !target.activity.empty()) {
            injectDirectAction(node, kAppInfoAction, kAppInfoLabel);
            if (target.user == 0 && uninstallLaunchReady()) {
                injectDirectAction(node, kUninstallAction, kUninstallLabel);
            }
            for (size_t index = 0; index < target.shortcuts.size()
                    && index < static_cast<size_t>(kDynamicActionCount); ++index) {
                injectDirectActionText(node,
                        kDynamicActionBase + static_cast<int32_t>(index),
                        utf16FromUtf8(target.shortcuts[index].label));
            }
        }
    }
}

void injectRecentActions(void *node) {
    if (node == nullptr) return;
    const std::string label = nodeString(node, 0x138);
    if (!isRecentTaskLabel(label)) return;
    const bool locked = recentTaskLocked(label);
    // Keep this order in sync with HyperOS 4's long-press menu. "关闭" is
    // last so TalkBack reaches the destructive gesture action after the menu
    // actions using the same upward traversal as the card itself.
    injectDirectAction(node, kRecentAppFlowAction,
            recentActionLabel(kRecentAppFlowAction, locked));
    injectDirectAction(node, kRecentLockAction,
            recentActionLabel(kRecentLockAction, locked));
    injectDirectAction(node, kRecentSplitAction,
            recentActionLabel(kRecentSplitAction, locked));
    injectDirectAction(node, kRecentFreeformAction,
            recentActionLabel(kRecentFreeformAction, locked));
    injectDirectAction(node, kRecentAppInfoAction,
            recentActionLabel(kRecentAppInfoAction, locked));
    injectDirectAction(node, kRecentCloseAction,
            recentActionLabel(kRecentCloseAction, locked));
}

void logDiagnosticsSnapshot(const char *reason) {
    g_diagnostics_node_budget.store(8);
    nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
            "diagnostics %s module=%s profile=%s fingerprint=%d system=%d cap=0x%x focus=%d page=%d grid=%dx%d",
            reason, ACTIONGRID_VERSION, launcherAbiProfileName(g_launcher_profile.load()),
            g_launcher_fingerprint_ready.load() ? 1 : 0, g_launcher_system_compatible.load() ? 1 : 0,
            g_capabilities.load(), g_launcher_window_focused.load(), g_visible_screen.load() + 1,
            g_grid_columns.load(), g_grid_rows.load());
    nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
            "diagnostics hooks node=%d action=%d focus=%d motion=%d event=%d node_calls=%u injected=%u",
            g_original_create_node ? 1 : 0, g_original_perform_action ? 1 : 0,
            g_original_launcher_focus ? 1 : 0, g_original_launcher_motion ? 1 : 0,
            g_original_send_event ? 1 : 0, g_node_action_calls.load(), g_custom_action_calls.load());
    {
        std::lock_guard lock(g_session_mutex);
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "diagnostics grid lifted=%d sources=%zu placements=%zu slots=%zu cells=%zu grid_screen=%d virtual_focus=%d moving=%d",
                g_lifted.load() ? 1 : 0, g_sources.size(), g_source_placements.size(),
                g_slots.size(), g_grid_cells.size(), g_grid_screen, g_virtual_focused_id.load(),
                g_model_move_running.load() ? 1 : 0);
    }
    {
        std::lock_guard lock(g_snapshot_mutex);
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "diagnostics snapshot ready=%d generation=%llu favorites=%zu pages=%zu",
                g_snapshot.ready ? 1 : 0, static_cast<unsigned long long>(g_snapshot.generation),
                g_snapshot.favorites.size(), g_snapshot.pageCounts.size());
    }
}

void onLibraryLoaded(const char *name, void *handle) {
    const char *base = baseName(name);
    if (std::strcmp(base, "libapp_launcher.so") == 0) {
        g_capabilities.fetch_or(CAP_LAUNCHER_SEEN, std::memory_order_release);
        resolveLauncherApis(handle);
        nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
                "Rust launcher loaded; native backend is observing");
    } else if (std::strcmp(base, "libapp.so") == 0) {
        char dartBuildId[33] = {};
        refreshLauncherAbiProfile(dartBuildId, sizeof(dartBuildId));
        void *launcherHandle = findLoadedHandle("libapp_launcher.so");
        if (launcherHandle != nullptr) {
            // libapp may arrive after libapp_launcher; re-run the shared profile
            // setup so delayed loads get the same gates and verified offsets.
            resolveLauncherApis(launcherHandle);
        }
        void *privateHandle = findLoadedHandle(
                "libhyper_os_accessibility_private.dylib.so");
        if (privateHandle != nullptr) {
            resolvePrivate("libhyper_os_accessibility_private.dylib.so", privateHandle);
        }
    } else if (std::strcmp(base, "libhyper_os_accessibility_private.dylib.so") == 0) {
        resolvePrivate(name, handle);
    } else if (std::strcmp(base, "libhyper_os_accessibility_public.so") == 0) {
        resolvePublic(handle);
    } else if (std::strcmp(base, "libhyper_os_aidl.dylib.so") == 0
            || std::strcmp(base, "libhyper_os_ui_public.so") == 0) {
        void *privateHandle = findLoadedHandle(
                "libhyper_os_accessibility_private.dylib.so");
        if (privateHandle != nullptr) {
            resolvePrivate("libhyper_os_accessibility_private.dylib.so", privateHandle);
        }
    }
    ensureDiagnosticsWorker();
}

}  // namespace

extern "C" __attribute__((visibility("default"), used))
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    g_api = entries;
    nativeLog(ANDROID_LOG_INFO, "ActionGridNative",
            "native_init version=%u; Rust actions are capability-gated",
            entries == nullptr ? 0U : entries->version);
    ensureSurfacePoller();
    return onLibraryLoaded;
}

extern "C" jint JNI_OnLoad(JavaVM *vm, void *) {
    (void) vm;
    return JNI_VERSION_1_6;
}

extern "C" __attribute__((visibility("default"), used))
uint32_t actiongrid_native_capabilities() {
    return g_capabilities.load(std::memory_order_acquire);
}

extern "C" __attribute__((visibility("default"), used))
uint32_t actiongrid_native_node_action_calls() {
    return g_node_action_calls.load(std::memory_order_relaxed);
}

extern "C" __attribute__((visibility("default"), used))
uint32_t actiongrid_native_custom_action_calls() {
    return g_custom_action_calls.load(std::memory_order_relaxed);
}

extern "C" __attribute__((visibility("default"), used))
uint32_t actiongrid_native_selected_source_count() {
    std::lock_guard lock(g_session_mutex);
    return static_cast<uint32_t>(g_sources.size());
}

extern "C" __attribute__((visibility("default"), used))
const char *actiongrid_native_private_build_id() {
    return g_private_build_id;
}
