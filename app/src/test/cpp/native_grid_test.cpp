// Executes the production native grid and event code on Android; system ABI calls
// are captured at the public function-pointer boundary, not reimplemented.
#include "../../main/cpp/native_init.cpp"

#include <iostream>
#include <stdexcept>
#include <array>

namespace {
void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

void prepareGrid() {
    g_capabilities.store(CAP_MOVE_PROFILE | CAP_PRIVATE_BUILD_MATCH | CAP_PRIVATE_SYMBOLS);
    g_launcher_system_compatible.store(true);
    g_grid_columns.store(4);
    g_grid_rows.store(6);
    g_cell_count_x = nullptr;
    g_cell_count_y = nullptr;
    g_visible_screen.store(2);
    g_lifted_screen.store(3);
    g_lifted.store(true);
    g_virtual_hovered_id.store(-1);
    g_virtual_focused_id.store(-1);
    g_sources.clear();
    g_source_placements.clear();
    g_app_targets.clear();
    g_snapshot = {};
    g_model_move_running.store(false);
    g_sqlite_open_v2 = nullptr;
    g_create_node = nullptr;
    g_node_drop = nullptr;
    g_workspace = {
        {10, -1, 1, 2, {0, 0, 400, 600}, "com.miui.home:id/screen_content", ""},
        {11, 10, 1, 2, {0, 0, 100, 100}, "com.miui.home:id/icon_icon", "QQ"},
        {12, 10, 1, 2, {200, 300, 300, 400}, "com.miui.home:id/icon_icon", "时空领主"},
        {13, 10, 1, 2, {300, 500, 400, 600}, "com.miui.home:id/icon_icon", "便捷下载"}
    };
    g_drop_parent_node_id.store(10);
    g_drop_parent_host_id.store(1);
    g_drop_parent_window_id.store(2);
    rebuildSlots();
}

void folderPlacementConfirmationMatchesHomeDatabaseRelations() {
    const std::vector<FavoritePlacement> createdFolder{
            {10, 300, -1, 0, 0, 1, 1, 0},
            {11, 300, -1, 1, 0, 1, 1, 0}};
    require(folderPlacementConfirmed(createdFolder, 10, 11, false),
            "new folder rows with one shared container were not accepted");
    const std::vector<FavoritePlacement> existingFolder{
            {10, 241, -1, 2, 0, 1, 1, 0},
            {241, kDesktopContainer, 3, 3, 3, 1, 1, 2}};
    require(folderPlacementConfirmed(existingFolder, 10, 241, true),
            "source row moved into an existing folder was not accepted");
    const std::vector<FavoritePlacement> stillOnDesktop{
            {10, kDesktopContainer, 3, 2, 0, 1, 1, 0},
            {11, kDesktopContainer, 3, 3, 0, 1, 1, 0}};
    require(!folderPlacementConfirmed(stillOnDesktop, 10, 11, false),
            "unchanged desktop rows were mistaken for a folder operation");
}

void workspaceRecoveryUsesVisiblePageIcons() {
    LauncherSnapshot snapshot;
    snapshot.ready = true;
    snapshot.totalPages = 3;
    snapshot.pageCounts = {{1, 1}, {3, 3}, {5, 1}};
    for (const FavoritePlacement &item : std::vector<FavoritePlacement>{
            {1, kDesktopContainer, 1, 0, 0, 1, 1, 0},
            {2, kDesktopContainer, 3, 0, 0, 1, 1, 0},
            {3, kDesktopContainer, 3, 1, 0, 1, 1, 2},
            {4, kDesktopContainer, 3, 0, 2, 2, 2, 4},
            {5, -101, 3, 0, 0, 1, 1, 0},
            {6, 3, -1, 0, 0, 1, 1, 0}}) {
        SnapshotFavorite favorite;
        favorite.placement = item;
        snapshot.favorites.push_back(favorite);
    }
    std::vector<ObservedNode> nodes{
        {1, -1, 7, 8, {0, 0, 400, 700}, "com.miui.home:id/workspace", ""},
        {2, 1, 7, 8, {0, 0, 400, 600}, "com.miui.home:id/screen_content", ""},
        {3, 2, 7, 8, {0, 0, 100, 100}, "com.miui.home:id/icon_icon", "图标"},
        {4, 1, 7, 8, {100, 600, 300, 650}, "", "第 2 页共 3 页"},
        {5, 10, 7, 8, {0, 650, 100, 700}, "com.miui.home:id/icon_icon", "底栏"},
        {6, 1, 7, 8, {400, 0, 400, 600}, "com.miui.home:id/screen_content", ""},
        {7, 6, 7, 8, {400, 0, 400, 100}, "com.miui.home:id/icon_icon", "离屏"},
        {10, 1, 7, 8, {0, 650, 400, 700}, "com.miui.home:id/hotseat", ""}};
    WorkspaceIconCoverage coverage;
    require(workspaceIconCoverage(nodes, snapshot, coverage)
                    && coverage.page == 2 && coverage.expected == 2 && coverage.observed == 1,
            "missing current-page icon was hidden by dock, widget, folder member, or sparse screen number");
    require(workspaceFocusTarget(nodes, coverage, 7) == 3,
            "offscreen focus was kept after changing pages");
    require(workspaceFocusTarget(nodes, coverage, 3) < 0
                    && workspaceFocusTarget(nodes, coverage, 4) < 0
                    && workspaceFocusTarget(nodes, coverage, 5) < 0,
            "page recovery steals an existing visible icon, indicator, or dock focus");
    nodes[1].bounds.right = 399;
    nodes[5].bounds = {399, 0, 400, 600};
    nodes[6].bounds = {399, 0, 400, 100};
    require(workspaceIconCoverage(nodes, snapshot, coverage) && coverage.observed == 1
                    && workspaceFocusTarget(nodes, coverage, 7) == 3,
            "one-pixel page rounding prevents recovery or retains the adjacent page's focus");
    nodes.insert(nodes.begin(), {11, 1, 7, 8, {-400, 0, 0, 600}, "com.miui.home:id/screen_content", ""});
    require(workspaceIconCoverage(nodes, snapshot, coverage) && workspaceFocusTarget(nodes, coverage, 7) == 3,
            "an equally wide offscreen page replaces the confirmed current page");
    nodes.erase(nodes.begin());
    WorkspaceRecoveryAttempt attempt;
    require(attempt.take(coverage, 1) && !attempt.take(coverage, 1),
            "one missing tree triggers unbounded semantics resets");
    nodes.push_back({8, 2, 7, 8, {100, 0, 200, 100}, "com.miui.home:id/folder", "文件夹"});
    require(workspaceIconCoverage(nodes, snapshot, coverage) && !attempt.take(coverage, 1),
            "healthy tree resets the engine or counts the widget as a missing icon");
    nodes.pop_back();
    require(workspaceIconCoverage(nodes, snapshot, coverage) && attempt.take(coverage, 1),
            "a later loss on the same page cannot recover");
    nodes[3].label = "第 1 页共 3 页";
    nodes.erase(nodes.begin() + 2);
    require(workspaceIconCoverage(nodes, snapshot, coverage) && coverage.expected == 1
                    && attempt.take(coverage, 1), "first page is excluded from semantics recovery");
    nodes[1].bounds.left = 100;
    require(!workspaceIconCoverage(nodes, snapshot, coverage), "animated partial page triggers recovery");
    nodes[1].bounds.left = 0;
    nodes.push_back({9, 1, 7, 8, {0, 0, 400, 600}, "com.miui.home:id/folder_grid_view", ""});
    require(!workspaceIconCoverage(nodes, snapshot, coverage), "open folder is mistaken for missing desktop icons");
}

void sparseItemInfoKeepsTheVerifiedWireLayout() {
    const FavoritePlacement source{0x0102030405060708LL, kDesktopContainer, 4,
            2, 1, 2, 3, 0};
    const FavoritePlacement target{-1, 300, -1, 4, 5, 1, 1, 2};
    WireWriter wire;
    appendSparseItemInfo(wire, source, target);
    // Fixed bytes from the 35-field ABI: the remaining 25 option tags are None.
    const std::array<uint8_t, 75> expected{
        1, 8, 7, 6, 5, 4, 3, 2, 1,
        0, 1, 0, 0, 0, 0, 0,
        1, 2, 0, 0, 0, 1, 3, 0, 0, 0,
        1, 0xff, 0xff, 0xff, 0xff,
        1, 4, 0, 0, 0, 1, 5, 0, 0, 0,
        1, 0x2c, 1, 0, 0, 0, 0, 0, 0};
    require(wire.bytes.size() == expected.size()
                    && std::equal(expected.begin(), expected.end(), wire.bytes.begin()),
            "ordinary ItemInfo no longer matches the verified 75-byte sparse layout");
}

void folderItemInfoEncodesUtf8TitleAndLabel() {
    const FavoritePlacement folder{-1, kDesktopContainer, 3, 2, 1, 1, 1, 2};
    const std::string title = u8"工具📁";
    const std::array<uint8_t, 10> utf8{0xe5, 0xb7, 0xa5, 0xe5, 0x85, 0xb7,
            0xf0, 0x9f, 0x93, 0x81};
    WireWriter plain, named;
    appendSparseItemInfo(plain, folder, folder);
    appendSparseItemInfo(named, folder, folder, &title);
    require(named.bytes.size() == 103
                    && std::equal(plain.bytes.begin(), plain.bytes.begin() + 53,
                            named.bytes.begin()),
            "folder strings shifted the preceding thirteen fields or wrong total length");
    for (const size_t offset : {size_t{53}, size_t{68}}) {
        require(named.bytes[offset] == 1 && named.bytes[offset + 1] == 10
                        && named.bytes[offset + 2] == 0 && named.bytes[offset + 3] == 0
                        && named.bytes[offset + 4] == 0
                        && std::equal(utf8.begin(), utf8.end(), named.bytes.begin() + offset + 5),
                "title/label option or UTF-8 byte length differs from the native string decoder");
    }
    require(std::all_of(named.bytes.begin() + 83, named.bytes.end(),
                    [](uint8_t byte) { return byte == 0; }),
            "folder ItemInfo did not preserve the twenty trailing None fields");
    const std::string empty;
    WireWriter emptyNamed;
    appendSparseItemInfo(emptyNamed, folder, folder, &empty);
    require(emptyNamed.bytes.size() == 83 && emptyNamed.bytes[53] == 1
                    && emptyNamed.bytes[58] == 1
                    && std::all_of(emptyNamed.bytes.begin() + 54, emptyNamed.bytes.begin() + 58,
                            [](uint8_t byte) { return byte == 0; })
                    && std::all_of(emptyNamed.bytes.begin() + 59, emptyNamed.bytes.end(),
                            [](uint8_t byte) { return byte == 0; }),
            "Some(empty string) was confused with None or shifted the sparse tail");
}

std::vector<ObservedNode> prepareFolderIdentity() {
    g_snapshot = {};
    g_snapshot.ready = true;
    auto favorite = [](int64_t id, int64_t container, const char *label, int32_t rank) {
        SnapshotFavorite item;
        item.id = id;
        item.title = label;
        item.placement = {id, container, container > 0 ? -1 : 1, rank, 0, 1, 1, 0};
        return item;
    };
    g_snapshot.favorites = {
        favorite(101, 200, "Gemini", 0), favorite(102, 200, "相册", 1),
        favorite(201, 300, "Gemini", 0), favorite(202, 300, "短信", 1),
        favorite(301, kDesktopContainer, "Gemini", 0),
        favorite(302, kDesktopContainer, "设置", 1)};
    return {
        {1, -1, 7, 8, {0, 0, 400, 600}, "", ""},
        {20, 1, 7, 8, {50, 100, 350, 500}, "com.miui.home:id/folder_grid_view", ""},
        {21, 20, 7, 8, {50, 100, 350, 200}, "", ""},
        {30, 21, 7, 8, {50, 100, 150, 200}, "com.miui.home:id/icon_icon", "Gemini"},
        {31, 21, 7, 8, {150, 100, 250, 200}, "com.miui.home:id/icon_icon", "相册"}};
}

void folderIdentityUsesVisibleMembersToDisambiguate() {
    const auto nodes = prepareFolderIdentity();
    FavoritePlacement selected;
    require(resolveFolderItem(nodes, "Gemini", 0, selected)
                    && selected.id == 101 && selected.container == 200,
            "same-name source resolved to the desktop or the wrong folder");
    require(resolveFolderItem(nodes, "Gemini", 200, selected) && selected.id == 101,
            "confirmed folder identity was lost while resolving its member");
    require(!resolveFolderItem(nodes, "Gemini", 300, selected),
            "explicit container bypassed the open folder's visible member check");
    g_snapshot.favorites[3].title = "相册";
    require(!resolveFolderItem(nodes, "Gemini", 0, selected),
            "identical visible contents guessed between two folders");
    auto duplicate = g_snapshot.favorites.front();
    duplicate.id = duplicate.placement.id = 103;
    duplicate.placement.cellX = 2;
    g_snapshot.favorites.push_back(duplicate);
    require(!resolveFolderItem(nodes, "Gemini", 200, selected),
            "same-name members inside one folder guessed a favorite identity");
}

void folderIdentityIgnoresIconsOutsideTheOpenGrid() {
    auto nodes = prepareFolderIdentity();
    nodes.push_back({40, 1, 7, 8, {0, 500, 100, 600}, "com.miui.home:id/icon_icon", "设置"});
    nodes.push_back({41, 1, 7, 8, {100, 500, 200, 600}, "com.miui.home:id/icon_icon", "Gemini"});
    FavoritePlacement selected;
    require(resolveFolderItem(nodes, "Gemini", 0, selected) && selected.id == 101,
            "background desktop icons were treated as members of the open folder");
    nodes.erase(std::remove_if(nodes.begin(), nodes.end(),
            [](const auto &node) { return node.id == 30; }), nodes.end());
    require(!resolveFolderItem(nodes, "Gemini", 0, selected),
            "a source visible only outside the folder selected a hidden folder member");
    nodes.erase(std::remove_if(nodes.begin(), nodes.end(),
            [](const auto &node) { return node.id == 20; }), nodes.end());
    require(!resolveFolderItem(nodes, "相册", 0, selected),
            "a closed folder was accepted using stale descendants");
}

uint64_t injectedInputResult = 0;
int inputDrops = 0;
void inputInjectionDecodesNativeResult() {
    const auto motionNew = g_motion_new_with_display;
    const auto inputEvent = g_input_event;
    const auto inputDrop = g_input_drop;
    const auto injectInput = g_inject_input;
    g_motion_new_with_display = +[](int64_t, int64_t, int32_t, int32_t,
            int32_t, int32_t, int32_t, int32_t, float, float, float, float,
            float, float) -> void * { return reinterpret_cast<void *>(1); };
    g_input_event = +[](void *motion) -> void * { return motion; };
    g_input_drop = +[](void *) { ++inputDrops; };
    g_inject_input = +[](void *, int32_t, int32_t) { return injectedInputResult; };
    inputDrops = 0;
    injectedInputResult = 0x100; // Home's Result::Ok(true): tag 0, payload bit 8.
    const bool accepted = injectTouch(1, 0, 10, 20);
    injectedInputResult = 0;
    const bool rejected = injectTouch(1, 2, 20, 30);
    injectedInputResult = (uint64_t{7} << 32) | 1;
    const bool error = injectTouch(1, 1, 20, 30);
    g_motion_new_with_display = motionNew;
    g_input_event = inputEvent;
    g_input_drop = inputDrop;
    g_inject_input = injectInput;
    require(accepted, "Home Ok(true) was rejected, truncating the drag after DOWN");
    require(!rejected && !error, "failed input injection was accepted");
    require(inputDrops == 3, "input event ownership was not released");
}

std::vector<int32_t> folderInputActions;
int32_t cancelFolderInputAt = -1;

void folderRefreshInputStopsAfterCancellation() {
    struct Cleanup {
        MotionNewWithDisplayFn motionNew = g_motion_new_with_display;
        InputEventFn inputEvent = g_input_event;
        DropFn inputDrop = g_input_drop;
        InjectInputFn injectInput = g_inject_input;
        int32_t focused = g_launcher_window_focused.load();
        ~Cleanup() {
            g_motion_new_with_display = motionNew;
            g_input_event = inputEvent;
            g_input_drop = inputDrop;
            g_inject_input = injectInput;
            g_launcher_window_focused.store(focused);
            g_folder_refresh_active.store(false);
            g_folder_refresh_scroll_requested.store(false);
        }
    } cleanup;
    g_motion_new_with_display = +[](int64_t, int64_t, int32_t action, int32_t,
            int32_t, int32_t, int32_t, int32_t, float, float, float, float,
            float, float) -> void * {
        return reinterpret_cast<void *>(static_cast<uintptr_t>(action + 1));
    };
    g_input_event = +[](void *motion) -> void * { return motion; };
    g_input_drop = +[](void *) {};
    g_inject_input = +[](void *event, int32_t, int32_t) -> uint64_t {
        const auto action = static_cast<int32_t>(reinterpret_cast<uintptr_t>(event)) - 1;
        folderInputActions.push_back(action);
        if (action == cancelFolderInputAt) {
            g_folder_refresh_active.store(false);
            g_folder_refresh_result.store(-1);
        }
        return 0x100;
    };
    const Bounds grid{50, 100, 350, 500};
    folderInputActions.clear();
    g_launcher_window_focused.store(-1);
    require(!beginFolderRefresh() && !closeRefreshingFolder(grid) && folderInputActions.empty(),
            "unknown launcher foreground allowed a folder-closing touch");
    g_launcher_window_focused.store(1);
    require(beginFolderRefresh(), "foreground folder refresh did not start");
    g_folder_refresh_result.store(-1);
    require(!closeRefreshingFolder(grid) && folderInputActions.empty(),
            "a cancelled refresh was rearmed before closing its folder");
    require(beginFolderRefresh(), "second folder refresh did not start");
    g_focus_generation.fetch_add(1);
    require(!closeRefreshingFolder(grid) && folderInputActions.empty(),
            "returning to Home revived an old folder refresh");
    require(beginFolderRefresh(), "folder close refresh did not start");
    cancelFolderInputAt = 0;
    require(!closeRefreshingFolder(grid) && folderInputActions == std::vector<int32_t>({0, 3}),
            "cancelled folder close injected UP instead of cancelling its DOWN");
    require(beginFolderRefresh(), "folder scroll refresh did not start");
    folderInputActions.clear();
    cancelFolderInputAt = 2;
    g_folder_refresh_scroll_bounds = grid;
    g_folder_refresh_scroll_requested.store(true);
    require(!scrollRefreshingFolder() && folderInputActions == std::vector<int32_t>({0, 2, 3}),
            "folder scroll continued after refresh cancellation");
    require(beginFolderRefresh(), "normal folder close refresh did not start");
    folderInputActions.clear();
    cancelFolderInputAt = -1;
    require(closeRefreshingFolder(grid) && folderInputActions == std::vector<int32_t>({0, 1}),
            "an active foreground refresh could not close the folder");
}

void movedOutIconRequiresItsConfirmedDesktopCell() {
    prepareGrid();
    g_lifted.store(false);
    g_lifted_folder_container.store(0);
    g_snapshot.ready = true;
    g_snapshot.pageCounts = {{1, 1}, {2, 1}, {4, 1}, {5, 1}};
    std::vector<ObservedNode> nodes{
        {10, -1, 7, 8, {0, 0, 400, 600}, "com.miui.home:id/screen_content", ""},
        {80, 10, 7, 8, {0, 600, 400, 620}, "", "第 3 页共 4 页"},
        {11, 10, 7, 8, {0, 0, 100, 100}, "com.miui.home:id/icon_icon", "相册"},
        {12, 10, 7, 9, {100, 0, 200, 100}, "com.miui.home:id/icon_icon", "相册"},
        {20, -1, 7, 8, {0, 600, 400, 700}, "com.miui.home:id/hotseat", ""},
        {13, 20, 7, 8, {100, 0, 200, 100}, "com.miui.home:id/icon_icon", "相册"},
        {14, 999, 7, 8, {100, 0, 200, 100}, "com.miui.home:id/icon_icon", "相册"},
        {15, 10, 7, 8, {100, 0, 200, 100}, "com.miui.home:id/icon_icon", "相册"}};
    FavoritePlacement placed{101, kDesktopContainer, 4, 1, 0, 1, 1, 0};
    require(refreshedDesktopItem(nodes, placed, "相册") == 15,
            "move-out focus chose a same-name icon outside the confirmed page cell");
    nodes.pop_back();
    require(refreshedDesktopItem(nodes, placed, "相册") < 0,
            "background, orphan, dock or wrong-cell icon falsely confirmed move-out");
    nodes.push_back({15, 10, 7, 8, {100, 0, 200, 100}, "com.miui.home:id/icon_icon", "相册"});
    nodes.push_back({16, 10, 7, 8, {100, 0, 200, 100}, "com.miui.home:id/icon_icon", "相册"});
    require(refreshedDesktopItem(nodes, placed, "相册") < 0,
            "ambiguous same-cell icons were accepted as the moved item");
    nodes.pop_back();
    nodes[1].label = "第 2 页共 4 页";
    require(refreshedDesktopItem(nodes, placed, "相册") < 0,
            "another visible page falsely confirmed the saved destination");
    nodes[1].label = "第 3 页共 4 页";
    placed.container = -101;
    require(refreshedDesktopItem(nodes, placed, "相册") < 0,
            "dock container was accepted as a desktop destination");
    placed.container = kDesktopContainer;
    nodes[1].label.clear();
    require(refreshedDesktopItem(nodes, placed, "相册") < 0,
            "stale global page state replaced the refreshed tree's page evidence");
}

void folderOrderRequiresAdjacentUniqueMembers() {
    auto nodes = prepareFolderIdentity();
    const auto grid = nodes[1];
    require(adjacentFolderSource(nodes, grid, "Gemini", "相册", false) == 30
                    && adjacentFolderSource(nodes, grid, "Gemini", "相册", true) < 0,
            "adjacent folder members did not preserve the requested direction");
    nodes[4].bounds = {250, 100, 350, 200};
    nodes.push_back({32, 21, 7, 8, {150, 100, 250, 200}, "com.miui.home:id/icon_icon", "短信"});
    require(adjacentFolderSource(nodes, grid, "Gemini", "相册", false) < 0,
            "being somewhere before the target was mistaken for adjacent placement");
    nodes[3].bounds = {50, 200, 150, 300};
    require(adjacentFolderSource(nodes, grid, "Gemini", "相册", true) == 30,
            "adjacency across a folder row boundary was rejected");
    nodes.push_back({33, 21, 7, 9, {150, 200, 250, 300}, "com.miui.home:id/icon_icon", "Gemini"});
    require(adjacentFolderSource(nodes, grid, "Gemini", "相册", true) == 30,
            "a background window was included in the folder's member order");
    nodes.back().window = 8;
    require(adjacentFolderSource(nodes, grid, "Gemini", "相册", true) < 0,
            "duplicate member labels guessed the reordered favorite");
}

struct TraversalCall {
    int32_t host;
    int32_t previous;
    int32_t window;
};
TraversalCall traversalCall{};

using TestNode = std::array<uint8_t, 0x440>;
TestNode returnedNode{};

template<typename T> void putField(TestNode &node, size_t offset, T value) {
    std::memcpy(node.data() + offset, &value, sizeof(value));
}

void putString(TestNode &node, size_t offset, const char *value) {
    putField(node, offset, std::strlen(value));
    putField(node, offset + 8, value);
    putField(node, offset + 16, std::strlen(value));
}

TestNode testNode(int32_t id, int32_t parent, Bounds bounds,
        const char *resource, const char *label) {
    TestNode node{};
    putField(node, 0x338, (static_cast<uint64_t>(static_cast<uint32_t>(id)) << 32U) | 7U);
    putField(node, 0x340, (static_cast<uint64_t>(static_cast<uint32_t>(parent)) << 32U) | 7U);
    putField(node, 0x418, int32_t{8});
    putField(node, kNodeBoundsOffset, bounds);
    putString(node, 0x1b8, resource);
    putString(node, 0x138, label);
    return node;
}

struct CapturedEvent {
    int32_t type;
    int32_t host = 0, window = -1, node = -1;
    int32_t itemCount = -1, fromIndex = -1, toIndex = -1;
    std::u16string text;
};
std::vector<CapturedEvent> events;
// Capture only Android ABI boundaries; the hooks and state machine are production code.
struct AccessibilityCapture {
    AccessibilityCapture() {
        events.clear();
        g_pending_surface_announcements.clear();
        g_event_controller.store(reinterpret_cast<void *>(1));
        g_event_new = +[](int32_t type) -> void * { return new CapturedEvent{type}; };
        g_event_drop = +[](void *event) { delete static_cast<CapturedEvent *>(event); };
        g_event_set_source = +[](void *event, int32_t host, int32_t window, int32_t node) {
            auto &captured = *static_cast<CapturedEvent *>(event);
            captured.host = host; captured.window = window; captured.node = node;
        };
        g_rstring_from_utf16 = +[](const uint16_t *text, size_t size) -> RustRString {
            return {reinterpret_cast<uintptr_t>(text), size, 0, 0};
        };
        g_event_push_text = +[](void *event, void *string) {
            auto &value = *static_cast<RustRString *>(string);
            static_cast<CapturedEvent *>(event)->text.assign(
                    reinterpret_cast<const char16_t *>(value.first), value.second);
        };
        g_original_send_event = +[](void *, void *event) {
            events.push_back(*static_cast<CapturedEvent *>(event));
        };
        g_original_create_node = +[](void *, int32_t) -> NodeResult { return {0, returnedNode.data()}; };
        g_create_node = g_original_create_node;
        g_node_drop = +[](void *) {};
        g_surface_state.store(static_cast<int32_t>(SurfaceState::kWorkspace));
        g_has_recent_surface_time = false;
        g_sqlite_open_v2 = nullptr;
        g_lifted.store(false);
        g_last_provider.store(nullptr);
        g_announced_page = -1;
        g_launcher_window_focused.store(-1);
        g_workspace_host_id.store(-1);
        g_workspace_window_id.store(-1);
        g_page_source_keys.clear();
        g_consume_back_up.store(false);
    }
    ~AccessibilityCapture() {
        g_event_controller.store(nullptr);
        g_event_new = nullptr; g_event_drop = nullptr;
        g_event_set_source = nullptr; g_rstring_from_utf16 = nullptr;
        g_event_push_text = nullptr; g_original_send_event = nullptr;
        g_original_create_node = nullptr; g_create_node = nullptr; g_node_drop = nullptr;
        g_original_perform_action = nullptr; g_node_add_child = nullptr;
        g_node_set_traversal_after = nullptr;
        g_node_set_traversal_before = nullptr;
        g_node_set_content_description = nullptr;
        g_key_code = nullptr; g_key_action = nullptr;
        g_original_launcher_focus = nullptr; g_original_launcher_key = nullptr;
        g_original_launcher_back = nullptr;
    }
};

void singleIconPageBuildsPhysicalGrid() {
    AccessibilityCapture capture;
    g_grid_columns.store(0);
    g_grid_rows.store(0);
    g_visible_screen.store(0);
    g_snapshot = {};
    g_workspace = {
        {10, -1, 1, 2, {0, 0, 400, 600}, "com.miui.home:id/workspace", ""},
        {11, 10, 1, 2, {0, 0, 100, 100}, "com.miui.home:id/icon_icon", "QQ"}
    };
    rebuildSlots();
    require(g_grid_columns.load() == 4 && g_grid_rows.load() == 6
                    && g_grid_cells.size() == 24 && g_slots.size() == 23,
            "a single icon page did not expose the complete physical grid");
    require(virtualNodeAt(150, 50) >= 0,
            "a single icon page did not expose a touchable empty cell");
}

void missingPageBoundsKeepConfirmedGrid() {
    AccessibilityCapture capture;
    prepareGrid();
    const size_t oldSlotCount = g_slots.size();
    const size_t oldCellCount = g_grid_cells.size();
    const int32_t oldTarget = virtualNodeAt(150, 50);
    g_workspace = {
        {11, 10, 1, 2, {0, 0, 100, 100}, "com.miui.home:id/icon_icon", "QQ"}
    };
    rebuildSlots();
    require(g_slots.size() == oldSlotCount && g_grid_cells.size() == oldCellCount
                    && virtualNodeAt(150, 50) == oldTarget,
            "a transient host-bounds loss invalidated the confirmed virtual target");
}

void incompleteNodeTreeKeepsDatabaseCellsOccupied() {
    AccessibilityCapture capture;
    prepareGrid();
    g_snapshot.ready = true;
    g_snapshot.favorites.clear();
    for (int32_t row = 0; row < 6; ++row) {
        for (int32_t column = 0; column < 4; ++column) {
            SnapshotFavorite favorite;
            favorite.id = 100 + row * 4 + column;
            favorite.placement = {favorite.id, kDesktopContainer, 3,
                    column, row, 1, 1, 0};
            g_snapshot.favorites.push_back(favorite);
        }
    }
    rebuildSlots();
    require(g_slots.empty() && !isVirtualNode(virtualNodeAt(150, 50)),
            "incomplete accessibility tree exposed occupied desktop cells as empty");
}

void sparsePageGridKeepsHiddenFavoritesOccupied() {
    AccessibilityCapture capture;
    prepareGrid();
    g_visible_screen.store(3);
    g_snapshot.ready = true;
    g_snapshot.totalPages = 7;
    g_snapshot.pageCounts = {{1, 1}, {2, 1}, {3, 1}, {5, 4},
            {6, 1}, {7, 1}, {8, 1}};
    SnapshotFavorite hidden;
    hidden.id = 101;
    hidden.placement = {101, kDesktopContainer, 5, 1, 0, 1, 1, 0};
    SnapshotFavorite previousPage;
    previousPage.id = 102;
    previousPage.placement = {102, kDesktopContainer, 3, 1, 1, 1, 1, 0};
    g_snapshot.favorites = {hidden, previousPage};
    rebuildSlots();
    require(!isVirtualNode(virtualNodeAt(150, 50)),
            "sparse database screen exposed a hidden occupied cell as empty");
    require(isVirtualNode(virtualNodeAt(150, 150)) && g_slots.size() == 20,
            "sparse page grid borrowed occupancy from another database screen");
}

void liftedSourceCellIsAvailableForDrop() {
    AccessibilityCapture capture;
    prepareGrid();
    g_lifted_screen.store(g_visible_screen.load());
    g_sources = {g_workspace[1]};
    SnapshotFavorite favorite;
    favorite.id = 101;
    favorite.placement = {101, kDesktopContainer, 3, 0, 0, 1, 1, 0};
    g_snapshot.favorites = {favorite};
    g_source_placements = {favorite.placement};
    rebuildSlots();
    require(isVirtualNode(virtualNodeAt(50, 50)),
            "the lifted icon still occupied its original desktop cell");
    g_visible_screen.store(3);
    g_snapshot.favorites.clear();
    rebuildSlots();
    require(!isVirtualNode(virtualNodeAt(50, 50)),
            "a reused node ID on another page was mistaken for the lifted source");
}

void pageAnnouncementUsesTalkBackAnnouncementRecord() {
    AccessibilityCapture capture;
    g_snapshot.ready = true;
    g_snapshot.pageCounts = {{2, 7}};
    g_page_state_known.store(true);
    g_visible_screen.store(0);
    g_total_pages.store(3);
    require(announceConfirmedPage(2, {10, 8, 30}) && events.size() == 1
                    && events.back().type == kTypeAnnouncement,
            "page speech did not use the TalkBack announcement record");
}

void sparseLauncherScreensUseVisualPageOrdinals() {
    AccessibilityCapture capture;
    g_snapshot = {};
    g_snapshot.ready = true;
    g_snapshot.pageCounts = {{1, 16}, {2, 24}, {3, 18}, {5, 17},
            {6, 14}, {7, 11}, {8, 18}};
    g_total_pages.store(7);
    g_page_state_known.store(true);
    int32_t count = -1;
    require(desktopItemCountForPage(4, count) && count == 17,
            "database screen gaps were announced as an empty visual page");
}

void restoreNativeEventController() {
    static TestNode windowEvent{};
    putField(windowEvent, kEventTypeOffset, int32_t{32});
    const auto original = g_original_send_event;
    g_original_send_event = +[](void *, void *event) {
        if (event != windowEvent.data()) events.push_back(*static_cast<CapturedEvent *>(event));
    };
    onSendEvent(reinterpret_cast<void *>(1), windowEvent.data());
    g_original_send_event = original;
}

void captureTraversalAfter(void *, int32_t host, int32_t previous) {
    traversalCall = {host, previous, -1};
}

void traversalAfterKeepsEmptyCellsInPhysicalOrder() {
    g_workspace = {
        {10, -1, 7, 8, {0, 0, 300, 100}, "com.miui.home:id/screen_content", ""},
        {11, 10, 7, 8, {0, 0, 100, 100}, "com.miui.home:id/icon_icon", "ChatGPT"},
        {12, 10, 7, 8, {200, 0, 300, 100}, "com.miui.home:id/icon_icon", "酷安"}
    };
    g_grid_cells = {
        {{0, 0, 100, 100}, 0, 0},
        {{100, 0, 200, 100}, 0, 1},
        {{200, 0, 300, 100}, 0, 2}
    };
    g_slots = {{{100, 0, 200, 100}, 0, 1}};
    g_drop_parent_host_id.store(7);
    g_drop_parent_window_id.store(8);
    g_node_set_traversal_after = captureTraversalAfter;
    std::array<uint8_t, 0x440> realNode{};
    const Bounds realBounds{200, 0, 300, 100};
    const int64_t source = (static_cast<int64_t>(12) << 32U) | 7U;
    std::memcpy(realNode.data() + kNodeBoundsOffset, &realBounds, sizeof(realBounds));
    std::memcpy(realNode.data() + 0x338, &source, sizeof(source));
    setTraversalAfterForRealNode(realNode.data());
    require(traversalCall.previous == (kVirtualNodeBase + 1)
                    && traversalCall.host == 7,
            "real icon skipped the physical empty column");
    setTraversalAfterForVirtualNode(realNode.data(), 0);
    require(traversalCall.previous == 11,
            "empty cell was not linked after the preceding real icon");
    g_node_set_traversal_after = nullptr;
}

void traversalUsesCurrentRealIconIdentity() {
    AccessibilityCapture capture;
    prepareGrid();
    // The launcher rebuilt its node IDs while the lifted grid retained the old tree.
    returnedNode = testNode(111, 100, {0, 0, 100, 100}, "com.miui.home:id/icon_icon", "QQ");
    g_node_set_traversal_after = captureTraversalAfter;
    g_original_create_node = +[](void *, int32_t) -> NodeResult { return {0, returnedNode.data()}; };
    onCreateNode(reinterpret_cast<void *>(1), 111);
    setTraversalAfterForVirtualNode(returnedNode.data(), 0);
    require(traversalCall.previous == 111 && traversalCall.host == 7,
            "empty column follows a stale icon ID, separating the virtual grid from real icons");
}

TraversalCall beforeCall{};

void mixedGridOverridesNativeIconOnlyTraversal() {
    AccessibilityCapture capture;
    prepareGrid();
    g_workspace = {
        {10, -1, 7, 8, {0, 0, 300, 100}, "com.miui.home:id/screen_content", ""},
        {11, 10, 7, 8, {0, 0, 100, 100}, "com.miui.home:id/icon_icon", "QQ"},
        {12, 10, 7, 8, {200, 0, 300, 100}, "com.miui.home:id/icon_icon", "微信"}
    };
    g_grid_columns.store(3); g_grid_rows.store(1);
    g_drop_parent_host_id.store(7); g_drop_parent_window_id.store(8);
    rebuildSlots();
    g_node_set_traversal_after = captureTraversalAfter;
    g_node_set_traversal_before = +[](void *, int32_t host, int32_t next) {
        beforeCall = {host, next, -1};
    };
    g_original_create_node = +[](void *, int32_t) -> NodeResult { return {0, returnedNode.data()}; };
    returnedNode = testNode(111, 10, {0, 0, 100, 100}, "com.miui.home:id/icon_icon", "QQ");
    beforeCall = {7, 12, -1}; // Native icon traversal originally skips the empty column.
    onCreateNode(reinterpret_cast<void *>(1), 111);
    require(beforeCall.previous == (kVirtualNodeBase + 1), "QQ still skips directly to WeChat");
    returnedNode = testNode(112, 10, {200, 0, 300, 100}, "com.miui.home:id/icon_icon", "微信");
    onCreateNode(reinterpret_cast<void *>(1), 112);
    require(traversalCall.previous == (kVirtualNodeBase + 1), "reverse traversal skips the empty column");
    setTraversalAfterForVirtualNode(returnedNode.data(), 0);
    require(traversalCall.previous == 111 && beforeCall.previous == 112,
            "empty column is not between the current real QQ and WeChat nodes");
}

void pageIndicatorAnnouncesBeforeDelayedFlutterEvent() {
    AccessibilityCapture capture;
    g_visible_screen.store(0);
    g_page_state_known.store(true);
    g_total_pages.store(3);
    g_snapshot.ready = true;
    g_snapshot.pageCounts = {{1, 4}, {2, 2}};
    returnedNode = testNode(30, -1, {0, 600, 400, 650}, "", "第 2 页共 3 页");
    onCreateNode(reinterpret_cast<void *>(1), 30);
    require(events.size() == 1 && events.front().text == u"第 2 页共 2 项",
            "confirmed page indicator still waits for the delayed Flutter announcement");
    onCreateNode(reinterpret_cast<void *>(1), 30);
    require(events.size() == 1, "repeated node queries repeat the page announcement");
    TestNode delayed{};
    putField(delayed, kEventTypeOffset, kTypeAnnouncement);
    putField(delayed, kEventSourceOffset, kFlutterPageAnnouncementSource);
    putField(delayed, kEventWindowOffset, int32_t{8});
    putString(delayed, kEventTextOffset, "第 2 页共 3 页");
    bool suppressed = false;
    require(rewritePageScrollEvent(delayed.data(), suppressed) && suppressed && events.size() == 1,
            "late Flutter page event duplicates the immediate announcement");
    putField(delayed, kEventTypeOffset, kTypeViewScrolled);
    const uint64_t workspaceSource = (uint64_t{10} << 32U) | 7U;
    g_page_source_keys.insert(workspaceSource);
    putField(delayed, kEventSourceOffset, workspaceSource);
    putField(delayed, kEventItemCountOffset, int32_t{3});
    putField(delayed, kEventFromIndexOffset, int32_t{1});
    putField(delayed, kEventToIndexOffset, int32_t{1});
    require(rewritePageScrollEvent(delayed.data(), suppressed) && suppressed && events.size() == 1,
            "native scroll event duplicates the confirmed page announcement");
}

void emptyAndReversePagesSpeakWithoutPredictions() {
    AccessibilityCapture capture;
    g_visible_screen.store(0); g_page_state_known.store(true); g_total_pages.store(3);
    g_snapshot.ready = true; g_snapshot.pageCounts = {{1, 4}, {2, 2}};
    returnedNode = testNode(30, -1, {0, 600, 400, 650}, "", "第 3 页共 3 页");
    onCreateNode(reinterpret_cast<void *>(1), 30);
    require(events.size() == 1 && events.back().text == u"第 3 页共 0 项",
            "empty page waits forever for an absent database count");
    returnedNode = testNode(30, -1, {0, 600, 400, 650}, "", "第 2 页共 3 页");
    onCreateNode(reinterpret_cast<void *>(1), 30);
    returnedNode = testNode(30, -1, {0, 600, 400, 650}, "", "第 3 页共 3 页");
    onCreateNode(reinterpret_cast<void *>(1), 30);
    require(events.size() == 3 && events[1].text == u"第 2 页共 2 项",
            "back-and-forth paging was incorrectly deduplicated");
}

void pageSpeechFailurePreservesNativeFallback() {
    AccessibilityCapture capture;
    g_snapshot.ready = false;
    TestNode delayed{};
    putField(delayed, kEventTypeOffset, kTypeAnnouncement);
    putField(delayed, kEventSourceOffset, kFlutterPageAnnouncementSource);
    putField(delayed, kEventWindowOffset, int32_t{8});
    putString(delayed, kEventTextOffset, "第 2 页共 3 页");
    bool suppressed = true;
    require(!rewritePageScrollEvent(delayed.data(), suppressed) && !suppressed && events.empty(),
            "missing count suppressed the launcher's native speech");
    g_snapshot.ready = true; g_snapshot.pageCounts = {{2, 2}};
    g_event_controller.store(nullptr);
    require(!rewritePageScrollEvent(delayed.data(), suppressed) && !suppressed,
            "failed speech delivery suppressed native fallback");
    g_event_controller.store(reinterpret_cast<void *>(1));
    require(rewritePageScrollEvent(delayed.data(), suppressed) && suppressed && events.size() == 1,
            "failed delivery poisoned later page announcements");
}

std::u16string pageNodeDescription;

void pageNodeKeepsDefaultDescriptionAndTotalPages() {
    AccessibilityCapture capture;
    g_snapshot.ready = true;
    g_snapshot.pageCounts = {{2, 11}};
    g_page_state_known.store(true);
    g_visible_screen.store(0);
    g_node_set_content_description = +[](void *, void *string) {
        auto &value = *static_cast<RustRString *>(string);
        pageNodeDescription.assign(reinterpret_cast<const char16_t *>(value.first), value.second);
    };
    pageNodeDescription.clear();
    returnedNode = testNode(30, -1, {0, 600, 400, 650}, "", "第 2 页共 8 页");
    onCreateNode(reinterpret_cast<void *>(1), 30);
    require(pageNodeDescription.empty()
                    && nodeString(returnedNode.data(), 0x138) == "第 2 页共 8 页",
            "page indicator's default description was overwritten by page-turn speech");
    require(g_total_pages.load() == 8 && g_visible_screen.load() == 1,
            "rewriting the spoken count corrupted internal page navigation");
    g_launcher_window_focused.store(0);
    pageNodeDescription.clear();
    returnedNode = testNode(30, -1, {0, 600, 400, 650}, "", "第 3 页共 8 页");
    onCreateNode(reinterpret_cast<void *>(1), 30);
    require(pageNodeDescription.empty(),
            "nodes cached during Home's focus callback lost their default description");
    g_snapshot.ready = false;
    pageNodeDescription.clear();
    onCreateNode(reinterpret_cast<void *>(1), 30);
    require(pageNodeDescription.empty(), "missing snapshot invented an empty-page count");
}

void pageSpeechHandlesWorkspaceAnnouncementSources() {
    AccessibilityCapture capture;
    g_snapshot.ready = true;
    g_snapshot.pageCounts = {{2, 11}};
    g_workspace_window_id.store(8);
    TestNode event{};
    putField(event, kEventTypeOffset, kTypeAnnouncement);
    putField(event, kEventSourceOffset, (uint64_t{30} << 32U) | 7U);
    // Recorded public ABI: window is at 0xc4; 0x90 is unrelated record state.
    putField(event, 0x90, int32_t{-1});
    putField(event, 0xc4, int32_t{8});
    putString(event, kEventTextOffset, "\n第 2 页共 8 页\n");
    putField(event, 0x38, size_t{64}); // Rust String capacity may exceed its length.
    bool suppressed = false;
    require(rewritePageScrollEvent(event.data(), suppressed) && suppressed
                    && events.size() == 1 && events.back().text == u"第 2 页共 11 项",
            "native page announcement from a real workspace node bypasses item-count speech");
    putField(event, 0xc4, int32_t{9});
    require(!rewritePageScrollEvent(event.data(), suppressed) && !suppressed,
            "page speech from another window was intercepted");
    putField(event, 0xc4, int32_t{8});
    putString(event, kEventTextOffset, "已打开文件夹");
    require(!rewritePageScrollEvent(event.data(), suppressed) && !suppressed,
            "unrelated native speech was suppressed");
}

void pageSpeechInterceptsAnnouncementDuringFocusCallback() {
    AccessibilityCapture capture;
    g_snapshot.ready = true;
    g_snapshot.pageCounts = {{2, 11}};
    g_page_state_known.store(true);
    g_total_pages.store(8);
    g_visible_screen.store(0);
    g_workspace_host_id.store(7);
    g_workspace_window_id.store(8);
    g_launcher_window_focused.store(0);

    TestNode event{};
    putField(event, kEventTypeOffset, kTypeAnnouncement);
    putField(event, kEventSourceOffset, (uint64_t{30} << 32U) | 7U);
    putField(event, kEventWindowOffset, int32_t{8});
    putString(event, kEventTextOffset, "第 2 页共 8 页");
    bool suppressed = false;
    require(rewritePageScrollEvent(event.data(), suppressed) && suppressed
                    && events.size() == 1 && events.back().text == u"第 2 页共 11 项",
            "focus callback allowed the raw page-count announcement through");
}

void pageSpeechSurvivesNativeWorkspaceSourceRebuild() {
    AccessibilityCapture capture;
    g_snapshot.ready = true;
    g_snapshot.pageCounts = {{2, 11}, {3, 17}};
    g_page_state_known.store(true);
    g_total_pages.store(8);
    g_visible_screen.store(0);
    g_workspace_host_id.store(7);
    g_workspace_window_id.store(8);
    g_launcher_window_focused.store(1);

    TestNode event{};
    putField(event, kEventTypeOffset, kTypeViewScrolled);
    putField(event, kEventSourceOffset, (uint64_t{30} << 32U) | 7U);
    putField(event, kEventWindowOffset, int32_t{8});
    putField(event, kEventItemCountOffset, int32_t{8});
    putField(event, kEventFromIndexOffset, int32_t{1});
    putField(event, kEventToIndexOffset, int32_t{1});
    bool suppressed = false;
    require(rewritePageScrollEvent(event.data(), suppressed) && suppressed
                    && events.size() == 1 && events.back().text == u"第 2 页共 11 项",
            "the first native page event was not rewritten");

    // The native suite rebuilds the workspace provider and changes both the
    // source node and window.  The host identity remains the same.
    putField(event, kEventSourceOffset, (uint64_t{31} << 32U) | 7U);
    putField(event, kEventWindowOffset, int32_t{9});
    putField(event, kEventFromIndexOffset, int32_t{2});
    putField(event, kEventToIndexOffset, int32_t{2});
    require(rewritePageScrollEvent(event.data(), suppressed) && suppressed
                    && events.size() == 2 && events.back().text == u"第 3 页共 17 项"
                    && g_workspace_window_id.load() == 9,
            "a rebuilt native workspace source fell back to the launcher's page speech");
}

void announcedPageRebuildsAReusedGridHost() {
    AccessibilityCapture capture;
    prepareGrid();
    g_snapshot.ready = true;
    g_snapshot.pageCounts = {{4, 1}};
    SnapshotFavorite folder;
    folder.id = 101;
    folder.placement = {101, kDesktopContainer, 4, 2, 3, 1, 1, 2};
    g_snapshot.favorites = {folder};
    g_virtual_focused_id.store(virtualNodeAt(150, 50));
    TestNode event{};
    putField(event, kEventTypeOffset, kTypeAnnouncement);
    putField(event, kEventSourceOffset, kFlutterPageAnnouncementSource);
    putField(event, kEventWindowOffset, int32_t{8});
    putString(event, kEventTextOffset, "第 4 页共 8 页");
    bool suppressed = false;
    require(rewritePageScrollEvent(event.data(), suppressed) && suppressed,
            "page event did not confirm the new page");
    returnedNode = testNode(10, -1, {0, 0, 400, 600}, "com.miui.home:id/screen_content", "");
    g_node_drop = +[](void *) {};
    onCreateNode(reinterpret_cast<void *>(1), 10);
    require(g_grid_screen == 3 && g_slots.size() == 23,
            "reused page host retained the previous page's empty cells");
    require(g_virtual_focused_id.load() == -1,
            "page change retained a virtual focus from the old page");
}

void moveUsesCachedFavoriteWhenSnapshotResolutionFails() {
    prepareGrid();
    g_visible_screen.store(3);
    g_lifted_screen.store(3);
    g_sources = {{71, 20, 7, 8, {200, 100, 300, 200},
            "com.miui.home:id/folder", "旅行交通"}};
    g_source_placements.clear();
    AppTarget cached;
    cached.spoken = "旅行交通";
    cached.favorite = 101;
    cached.placement = {101, kDesktopContainer, 4, 2, 3, 1, 1, 2};
    g_app_targets[71] = cached;
    g_snapshot.ready = false;
    g_slots = {{{100, 0, 200, 100}, 0, 1}};

    std::vector<FavoritePlacement> sources;
    std::vector<FavoritePlacement> expected;
    WireWriter wire;
    std::string failureReason;
    require(buildModelMove(nullptr, kVirtualNodeBase + 1, 0,
                    sources, expected, wire, failureReason),
            "a cached folder favorite was discarded when the snapshot resolver was unavailable");
    require(sources.size() == 1 && sources.front().id == 101
                    && expected.size() == 1 && expected.front().screen == 4,
            "cached folder favorite did not preserve its stable database identity");
}

void pendingPlacementRequiresTheDestinationPage() {
    prepareGrid();
    struct Cleanup { ~Cleanup() { endPendingPlacement(); } } cleanup;
    beginPendingPlacement({{{101, kDesktopContainer, 3, 1, 0, 1, 1, 0},
            "QQ", {100, 0, 200, 100}, false}});
    const ObservedNode icon{91, 10, 1, 2, {100, 0, 200, 100},
            "com.miui.home:id/icon_icon", "QQ"};
    g_visible_screen.store(1);
    observePendingPlacement(icon);
    require(!g_pending_placements.front().visible,
            "a matching icon on another page falsely confirms the move");
    g_visible_screen.store(2);
    observePendingPlacement(icon);
    require(g_pending_placements.front().visible, "destination icon did not confirm the move");
}

int placementFocusCalls = 0;
void backCancellationRestoresTheSourceOnTheNextQuery() {
    AccessibilityCapture capture;
    prepareGrid();
    g_lifted_screen.store(g_visible_screen.load());
    g_source = g_workspace[1];
    g_sources = {g_source};
    g_source_placements = {{101, kDesktopContainer, 3, 0, 0, 1, 1, 0}};
    placementFocusCalls = 0;
    g_original_perform_action = +[](void *, int32_t id, int32_t action, void *arguments) {
        require(id == 92 && action == kAccessibilityFocusAction && arguments != nullptr
                        && static_cast<uintptr_t *>(arguments)[0] == 1,
                "Back focused the wrong source or used an invalid Bundle");
        ++placementFocusCalls;
        return true;
    };
    require(cancelLiftSession(11), "Back did not cancel the lift");
    returnedNode = testNode(92, 10, {0, 0, 100, 100}, "com.miui.home:id/icon_icon", "QQ");
    onCreateNode(reinterpret_cast<void *>(1), 92);
    require(placementFocusCalls == 1 && g_pending_placements.empty(),
            "Back lost the source focus or left a stale restore request");
}

void placedIconReceivesFocusOnceAfterLiftCleanup() {
    AccessibilityCapture capture;
    prepareGrid();
    struct Cleanup { ~Cleanup() { endPendingPlacement(); } } cleanup;
    beginPendingPlacement({{{101, kDesktopContainer, 3, 1, 0, 1, 1, 0},
            "QQ", {100, 0, 200, 100}, false}});
    placementFocusCalls = 0;
    g_original_perform_action = +[](void *, int32_t id, int32_t action, void *arguments) {
        require(id == 92 && action == kAccessibilityFocusAction,
                "placement focused an unrelated node");
        require(arguments != nullptr && static_cast<uintptr_t *>(arguments)[0] == 1
                        && static_cast<uintptr_t *>(arguments)[1] == 0,
                "focus action would drop an invalid native Bundle");
        ++placementFocusCalls;
        return true;
    };
    returnedNode = testNode(91, 10, {100, 0, 200, 100}, "com.miui.home:id/icon_icon", "QQ");
    onCreateNode(reinterpret_cast<void *>(1), 91);
    require(placementFocusCalls == 0, "focus moved before lift cleanup");
    clearLiftSession();
    returnedNode = testNode(92, 10, {100, 0, 200, 100}, "com.miui.home:id/icon_icon", "QQ");
    g_pending_focus_not_before = std::chrono::steady_clock::now() + std::chrono::seconds(1);
    onCreateNode(reinterpret_cast<void *>(1), 92);
    require(placementFocusCalls == 0, "focus raced the reader's removal recovery");
    g_pending_focus_not_before = {};
    onCreateNode(reinterpret_cast<void *>(1), 92);
    require(placementFocusCalls == 1, "placed icon did not regain accessibility focus");
    onCreateNode(reinterpret_cast<void *>(1), 92);
    require(placementFocusCalls == 1, "repeated node reads stole focus again");
}

void cancelledSwipeKeepsConfirmedPage() {
    AccessibilityCapture capture;
    g_visible_screen.store(0);
    g_page_state_known.store(true);
    g_total_pages.store(3);
    g_snapshot.ready = true;
    g_snapshot.pageCounts = {{1, 4}, {2, 2}};
    struct Motion { int32_t action; float x, y; } event{0, 500, 300};
    g_motion_action_masked = +[](void *value) { return static_cast<Motion *>(value)->action; };
    g_motion_raw_x = +[](void *value, int32_t) { return static_cast<Motion *>(value)->x; };
    g_motion_raw_y = +[](void *value, int32_t) { return static_cast<Motion *>(value)->y; };
    onLauncherMotion(nullptr, &event);
    event = {2, 200, 300};
    onLauncherMotion(nullptr, &event);
    event.action = 3;
    onLauncherMotion(nullptr, &event);
    require(g_visible_screen.load() == 0 && events.empty(),
            "cancelled swipe announces and commits a page that was never reached");
}

int forwardedFocus = 0, forwardedKeys = 0, forwardedBacks = 0;
struct TestKey { int32_t code, action; };

void everyReturnHomeAnnouncesOnce() {
    AccessibilityCapture capture;
    returnedNode = testNode(10, -1, {0, 0, 400, 600}, "com.miui.home:id/screen_content", "");
    forwardedFocus = 0;
    g_original_launcher_focus = +[](void *, bool focused) {
        ++forwardedFocus;
        if (focused) restoreNativeEventController();
    };
    auto *window = reinterpret_cast<void *>(1);
    // Back and Home both restore the launcher's window focus. Its cached tree persists in apps.
    for (int iteration = 0; iteration < 10; ++iteration) {
        onLauncherWindowFocus(window, false);
        observeSurfaceNode(window, 10, returnedNode.data());
        require(events.size() == static_cast<size_t>(iteration), "background cached tree announced Home");
        onLauncherWindowFocus(window, true);
        onCreateNode(window, 10);
        onLauncherWindowFocus(window, true);
        onCreateNode(window, 10);
        require(events.size() == static_cast<size_t>(iteration + 1)
                        && events.back().text == u"系统桌面",
                "repeated app-to-Home return did not announce exactly once");
    }
    require(forwardedFocus == 30, "native focus lifecycle was swallowed");
}

void quickReturnHomeWithoutNodeQueryAnnounces() {
    AccessibilityCapture capture;
    forwardedFocus = 0;
    g_original_launcher_focus = +[](void *, bool focused) {
        ++forwardedFocus;
        if (focused) restoreNativeEventController();
    };
    auto *window = reinterpret_cast<void *>(1);
    onLauncherWindowFocus(window, false);
    onLauncherWindowFocus(window, true);
    std::this_thread::sleep_for(std::chrono::milliseconds(350));
    require(std::count_if(events.begin(), events.end(), [](const auto &event) {
        return event.type == kTypeAnnouncement && event.text == u"系统桌面";
    }) == 1, "quick app-to-Home return without a node query missed Home speech");
}

void backGestureCancelsLiftAndKeepsNativeCleanup() {
    AccessibilityCapture capture;
    prepareGrid();
    g_virtual_focused_id.store((kVirtualNodeBase + 1));
    forwardedBacks = 0;
    g_original_launcher_back = +[](void *) { ++forwardedBacks; };
    onLauncherBack(reinterpret_cast<void *>(1));
    require(!g_lifted.load() && g_slots.empty() && g_grid_cells.empty()
                    && g_virtual_focused_id.load() == -1,
            "system Back gesture left the lifted grid active");
    require(forwardedBacks == 1, "native gesture completion was swallowed");
    require(std::count_if(events.begin(), events.end(), [](const auto &event) {
        return event.text == u"已取消托起";
    }) == 1, "Back gesture must announce cancellation once");
}

void backKeyCancelsOnlyTheLiftedSession() {
    AccessibilityCapture capture;
    prepareGrid();
    forwardedKeys = 0;
    g_key_code = +[](void *event) { return static_cast<TestKey *>(event)->code; };
    g_key_action = +[](void *event) { return static_cast<TestKey *>(event)->action; };
    g_original_launcher_key = +[](void *, void *) { ++forwardedKeys; return false; };
    TestKey down{4, 0}, up{4, 1}, other{66, 0};
    require(onLauncherKey(nullptr, &down) && onLauncherKey(nullptr, &up),
            "Back key did not consume both halves of lift cancellation");
    require(!g_lifted.load() && g_slots.empty() && forwardedKeys == 0,
            "Back key left virtual grid active or also navigated out");
    require(!onLauncherKey(nullptr, &other) && !onLauncherKey(nullptr, &down)
                    && !onLauncherKey(nullptr, &up) && forwardedKeys == 3,
            "normal keys were swallowed after leaving drag mode");
}

void leavingHomeCancelsGridAndRejectsStaleNodes() {
    AccessibilityCapture capture;
    prepareGrid();
    g_virtual_focused_id.store((kVirtualNodeBase + 1));
    onLauncherWindowFocus(reinterpret_cast<void *>(1), false);
    require(!g_lifted.load() && g_slots.empty() && g_virtual_focused_id.load() == -1,
            "opening another app left a virtual grid active");
    returnedNode = testNode(10, -1, {0, 0, 400, 600}, "com.miui.home:id/screen_content", "");
    const size_t count = events.size();
    for (int i = 0; i < 10; ++i) onCreateNode(reinterpret_cast<void *>(1), 10);
    require(events.size() == count
                    && g_surface_state.load() == static_cast<int32_t>(SurfaceState::kOther),
            "cached background node queries reactivated the desktop");
}

void launcherDimensionsOverrideOldSeventhRow() {
    prepareGrid();
    g_grid_rows.store(7);
    g_cell_count_x = +[]() { return 4; };
    g_cell_count_y = +[]() { return 6; };
    rebuildSlots();
    require(g_grid_rows.load() == 6 && g_grid_cells.size() == 24,
            "actual launcher row count was ignored");
    require(std::none_of(g_slots.begin(), g_slots.end(), [](const auto &slot) {
        return slot.row >= 6;
    }), "a seventh-row drop would make the icon disappear");
}

void widgetCellsAndTouchGapsAreHandled() {
    prepareGrid();
    SnapshotFavorite widget;
    widget.placement = {102, kDesktopContainer, 3, 1, 1, 2, 2, 4};
    g_snapshot.favorites = {widget};
    g_snapshot.ready = true;
    rebuildSlots();
    require(!isVirtualNode(virtualNodeAt(150, 150)), "widget cell exposed as empty");
    require(!isVirtualNode(virtualNodeAt(250, 250)), "widget span ignored");
    require(isVirtualNode(virtualNodeAt(199, 50)), "gap before next column is not touchable");
    require(!isVirtualNode(virtualNodeAt(400, 50)), "outside right edge is touchable");
}

struct TestMotion { int32_t action; float x, y; };
std::vector<int32_t> nativeMotionActions;
int droppedMotions = 0;

void touchHookUsesEventArgumentAndHandsBackOccupiedCells() {
    AccessibilityCapture capture;
    prepareGrid();
    nativeMotionActions.clear();
    droppedMotions = 0;
    g_motion_action_masked = +[](void *event) { return static_cast<TestMotion *>(event)->action & 255; };
    g_motion_raw_x = +[](void *event, int32_t) { return static_cast<TestMotion *>(event)->x; };
    g_motion_raw_y = +[](void *event, int32_t) { return static_cast<TestMotion *>(event)->y; };
    g_motion_clone = +[](void *event) -> void * { return new TestMotion(*static_cast<TestMotion *>(event)); };
    g_motion_set_action = +[](void *event, int32_t action) { static_cast<TestMotion *>(event)->action = action; };
    g_motion_drop = +[](void *event) { ++droppedMotions; delete static_cast<TestMotion *>(event); };
    g_original_launcher_motion = +[](void *, void *event) {
        const int32_t action = static_cast<TestMotion *>(event)->action;
        nativeMotionActions.push_back(action);
        if (action == 10) require(g_virtual_focused_id.load() == -1,
                "native hover exit arrived after virtual focus and could steal it");
    };
    TestMotion event{9, 150, 50};
    onLauncherMotion(reinterpret_cast<void *>(1), &event);
    require(nativeMotionActions == std::vector<int32_t>{10}, "empty touch forwarded to native icon");
    require(event.action == 9 && droppedMotions == 1, "caller event was mutated or clone leaked");
    require(g_virtual_focused_id.load() == -1, "hover stole the reader's focus");
    require(events.size() == 1 && events.front().type == 128,
            "hover announced the cell twice before the reader requested focus");
    require(onPerformAction(reinterpret_cast<void *>(1), virtualNodeAt(150, 50),
            kAccessibilityFocusAction, nullptr), "reader could not focus the empty cell");
    require(std::count_if(events.begin(), events.end(), [](const auto &e) {
        return e.type == 32768;
    }) == 1, "reader focus did not announce exactly once");
    event = {7, 50, 50};
    onLauncherMotion(reinterpret_cast<void *>(1), &event);
    require(nativeMotionActions == std::vector<int32_t>({10, 7}), "occupied icon did not receive hover");
    g_original_perform_action = +[](void *, int32_t, int32_t, void *) { return true; };
    onPerformAction(reinterpret_cast<void *>(1), 11, kAccessibilityFocusAction, nullptr);
    require(g_virtual_focused_id.load() == -1, "virtual focus remained after the reader focused an icon");
    g_original_launcher_motion = nullptr;
}

void changedCountsDoNotProveIconVisible() {
    prepareGrid();
    const VisiblePlacement target{{101, kDesktopContainer, 3, 1, 0, 1, 1, 0},
            "美团外卖", {100, 0, 200, 100}};
    g_workspace.push_back({20, -1, 1, 2, {0, 600, 400, 650},
            "", "第 3 页共 4 页"});
    g_snapshot.pageCounts = {{3, 11}, {4, 10}};
    ObservedNode moved{};
    require(!findVisiblePlacements(g_workspace, {target}, moved),
            "updated page count was mistaken for a visible moved icon");
    g_workspace.push_back({91, 10, 1, 2, {100, 0, 200, 100},
            "com.miui.home:id/icon_icon", "美团外卖"});
    require(findVisiblePlacements(g_workspace, {target}, moved) && moved.id == 91,
            "newly allocated icon node was not found on destination page");
    g_workspace.back().bounds = {100, 100, 200, 200};
    require(!findVisiblePlacements(g_workspace, {target}, moved), "wrong row was accepted");
    require(placementAnnouncement({target}) == "已放置在第3页，第1行，第2列",
            "drop feedback differs from the launcher's announce_to_drop_workspace resource");
}

void sparseGridKeepsEmptyColumnsAndRows() {
    prepareGrid();
    auto empty = std::find_if(g_slots.begin(), g_slots.end(), [](const auto &slot) {
        return slot.row == 0 && slot.column == 1;
    });
    require(empty != g_slots.end(), "empty row 1 column 2 must exist");
    require(empty->bounds.left == 100 && empty->bounds.right == 200,
            "empty column was compressed out of the physical grid");
    require(std::none_of(g_slots.begin(), g_slots.end(), [](const auto &slot) {
        return slot.bounds.right > 400 || slot.bounds.bottom > 600;
    }), "grid exposes a drop outside the visible page");
    require(std::any_of(g_slots.begin(), g_slots.end(), [](const auto &slot) {
        return slot.row == 1 && slot.bounds.top == 100;
    }), "empty row was compressed out of the physical grid");
}

void touchingEmptyCellReadsItsCoordinates() {
    prepareGrid();
    updateTouchExplorationTarget(9, 150, 50);
    require(isVirtualNode(g_virtual_hovered_id.load()),
            "touching empty row 1 column 2 did not find a virtual node");
    require(virtualNodeDescription(g_virtual_hovered_id.load()) == "移至第 1 行第 2 列",
            "empty cell does not use the requested coordinate prompt");
}

void liftingFingerKeepsDoubleTapTarget() {
    AccessibilityCapture capture;
    prepareGrid();
    g_slots = {{{100, 0, 200, 100}, 0, 1}};
    updateTouchExplorationTarget(9, 150, 50);
    onPerformAction(reinterpret_cast<void *>(1), virtualNodeAt(150, 50),
            kAccessibilityFocusAction, nullptr);
    require(g_virtual_focused_id.load() == (kVirtualNodeBase + 1), "empty cell did not focus");
    updateTouchExplorationTarget(10, 150, 50);
    require(g_virtual_hovered_id.load() == -1, "hover did not end");
    require(g_virtual_focused_id.load() == (kVirtualNodeBase + 1),
            "lifting finger cleared the accessibility target before double tap");
    updateTouchExplorationTarget(9, 50, 50);
    g_original_perform_action = +[](void *, int32_t, int32_t, void *) { return true; };
    onPerformAction(reinterpret_cast<void *>(1), 11, kAccessibilityFocusAction, nullptr);
    require(g_virtual_focused_id.load() == -1,
            "touching an icon again left the old virtual focus active");
}

void recycledNodeOnAnotherPageIsNotTheLiftedSource() {
    prepareGrid();
    g_sources = {g_workspace[1]};
    require(!isLiftedSource(g_workspace[1].id), "page 3 icon ID confused with page 4 source");
    g_visible_screen.store(3);
    require(isLiftedSource(g_workspace[1].id), "source lost on original page");
}

struct CapturedDispatch {};
std::vector<uint8_t> dispatched;
uint32_t expectedOperation = 833;
void captureDispatch(uint32_t operation, int64_t, const uint8_t *bytes, uint32_t size, uint32_t) {
    require(operation == expectedOperation, "wrong move operation");
    dispatched.assign(bytes, bytes + size);
    std::free(const_cast<uint8_t *>(bytes));
    endPendingPlacement();
    throw CapturedDispatch{}; // Stop before the asynchronous database confirmation.
}

void moveRequestIncludesDestinationArguments() {
    prepareGrid();
    expectedOperation = 833;
    SnapshotFavorite source;
    source.id = 101;
    source.title = "美团外卖";
    source.placement = {101, kDesktopContainer, 4, 2, 1, 1, 1, 0};
    g_snapshot.favorites = {source};
    g_snapshot.ready = true;
    g_sources = {{90, 20, 1, 2, {200, 100, 300, 200},
            "com.miui.home:id/icon_icon", "美团外卖"}};
    g_slots = {{{100, 0, 200, 100}, 0, 1}};
    g_frb_dispatcher = captureDispatch;
    g_capabilities.fetch_or(CAP_FRB_MODEL);
    g_current_frb_move_profile.store(true);
    try {
        performNativePlacement(nullptr, (kVirtualNodeBase + 1), 0);
        throw std::runtime_error("move was not dispatched");
    } catch (const CapturedDispatch &) {
        g_model_move_running.store(false);
    }
    WireWriter expected;
    FavoritePlacement target = source.placement;
    target.screen = 3;
    target.cellX = 1;
    target.cellY = 0;
    appendSparseItemInfo(expected, source.placement, target);
    expected.put64(kDesktopContainer);
    expected.put32(3);
    expected.put32(1);
    expected.put32(0);
    require(dispatched == expected.bytes,
            "op 833 is missing container, screen, cellX and cellY after ItemInfo");
    g_slots.front().row = 6;
    std::vector<FavoritePlacement> sources, targets;
    WireWriter invalid;
    std::string failureReason;
    require(!buildModelMove(nullptr, (kVirtualNodeBase + 1), 0, sources, targets, invalid, failureReason),
            "stale out-of-grid drop was accepted");
    g_frb_dispatcher = nullptr;
}

std::vector<std::array<int64_t, 8>> pageRows;
int queryRow = -1;

void occupiedPlacementMovesDisplacedIconsInNativeBatch() {
    prepareGrid();
    SnapshotFavorite source;
    source.id = 101;
    source.title = "美团外卖";
    source.placement = {101, kDesktopContainer, 4, 2, 1, 1, 1, 0};
    SnapshotFavorite anchor;
    anchor.id = 102;
    anchor.title = "QQ";
    anchor.placement = {102, kDesktopContainer, 3, 0, 0, 1, 1, 0};
    g_snapshot.favorites = {source, anchor};
    g_snapshot.ready = true;
    g_sources = {{90, 20, 1, 2, {200, 100, 300, 200},
            "com.miui.home:id/icon_icon", "美团外卖"}};
    g_create_node = +[](void *, int32_t id) -> NodeResult {
        require(id == 11, "unexpected placement anchor");
        static uint8_t node[0x440]{};
        const char *label = "QQ";
        const size_t size = 2;
        const Bounds bounds{0, 0, 100, 100};
        std::memcpy(node + 0x138, &size, sizeof(size));
        std::memcpy(node + 0x140, &label, sizeof(label));
        std::memcpy(node + 0x148, &size, sizeof(size));
        std::memcpy(node + kNodeBoundsOffset, &bounds, sizeof(bounds));
        return {0, node};
    };
    g_node_drop = +[](void *) {};
    pageRows = {{102, kDesktopContainer, 3, 0, 0, 1, 1, 0},
                {103, kDesktopContainer, 3, 1, 0, 1, 1, 0}};
    g_cached_db_path = "/dev/null";
    g_sqlite_open_v2 = +[](const char *, Sqlite3 **db, int, const char *) {
        *db = reinterpret_cast<Sqlite3 *>(1);
        return 0;
    };
    g_sqlite_prepare_v2 = +[](Sqlite3 *, const char *sql, int, Sqlite3Stmt **query, const char **) {
        require(std::string(sql).find("WHERE container=-100 AND screen=3") != std::string::npos,
                "reorder read the wrong page");
        queryRow = -1;
        *query = reinterpret_cast<Sqlite3Stmt *>(1);
        return 0;
    };
    g_sqlite_step = +[](Sqlite3Stmt *) {
        return ++queryRow < static_cast<int>(pageRows.size()) ? kSqliteRow : kSqliteDone;
    };
    g_sqlite_column_int64 = +[](Sqlite3Stmt *, int column) { return pageRows.at(queryRow).at(column); };
    g_sqlite_column_int = +[](Sqlite3Stmt *, int column) { return static_cast<int>(pageRows.at(queryRow).at(column)); };
    g_sqlite_finalize = +[](Sqlite3Stmt *) { return 0; };
    g_sqlite_close_v2 = +[](Sqlite3 *) { return 0; };
    expectedOperation = 855;
    g_frb_dispatcher = captureDispatch;
    g_capabilities.fetch_or(CAP_FRB_MODEL);
    g_current_frb_move_profile.store(false);
    g_launcher_profile.store(LauncherAbiProfile::kLegacy7748);
    try {
        performNativePlacement(reinterpret_cast<void *>(1), 11, kMoveBeforeAction);
        throw std::runtime_error("occupied placement was not dispatched");
    } catch (const CapturedDispatch &) {
        g_model_move_running.store(false);
    }
    require(dispatched.size() == 4 + 3 * 75 && dispatched.front() == 3,
            "batch omitted displaced icons, leaving overlapping favorites");
    for (size_t index = 0; index < 3; ++index) {
        int64_t id = 0;
        int32_t screen = 0, column = -1;
        const uint8_t *item = dispatched.data() + 4 + index * 75;
        std::memcpy(&id, item + 1, sizeof(id));
        std::memcpy(&screen, item + 27, sizeof(screen));
        std::memcpy(&column, item + 32, sizeof(column));
        require(id == 101 + static_cast<int64_t>(index) && screen == 3
                && column == static_cast<int32_t>(index), "batch reorder coordinates are wrong");
    }
    g_frb_dispatcher = nullptr;
    g_sqlite_open_v2 = nullptr;
    g_create_node = nullptr;
    g_node_drop = nullptr;
    g_cached_db_path.clear();
}

void checkPlacementAfterReload(bool positionChanged, bool nodesArriveDuringDispatch = false) {
    AccessibilityCapture capture;
    prepareGrid();
    const bool workerWasStarted = g_snapshot_worker_started.exchange(true);
    struct Cleanup {
        bool workerWasStarted;
        ~Cleanup() {
            while (g_model_move_running.load()) std::this_thread::sleep_for(std::chrono::milliseconds(10));
            g_snapshot_worker_started.store(workerWasStarted);
            g_frb_dispatcher = nullptr;
            g_trigger_workspace_reload = nullptr;
            g_sqlite_open_v2 = nullptr;
            g_cached_db_path.clear();
            endPendingPlacement();
        }
    } cleanup{workerWasStarted};
    g_sources = {{90, 20, 1, 2, {200, 100, 300, 200},
            "com.miui.home:id/icon_icon", "美团外卖"}};
    g_source_placements = {{101, kDesktopContainer, 4, 2, 1, 1, 1, 0}};
    g_capabilities.fetch_or(CAP_FRB_MODEL);
    g_current_frb_move_profile.store(false);
    g_launcher_profile.store(LauncherAbiProfile::kLegacy7748);
    g_launcher_window_focused.store(1);
    g_frb_dispatcher = +[](uint32_t, int64_t, const uint8_t *bytes, uint32_t, uint32_t) {
        std::free(const_cast<uint8_t *>(bytes));
        onCreateNode(reinterpret_cast<void *>(1), 91);
    };
    g_trigger_workspace_reload = positionChanged ? +[]() { pageRows[0][3] = 0; } : +[]() {};
    pageRows = {{101, kDesktopContainer, 3, 1, 0, 1, 1, 0}};
    g_cached_db_path = "/dev/null";
    g_sqlite_open_v2 = +[](const char *, Sqlite3 **db, int, const char *) {
        *db = reinterpret_cast<Sqlite3 *>(1); return 0;
    };
    g_sqlite_prepare_v2 = +[](Sqlite3 *, const char *, int, Sqlite3Stmt **query, const char **) {
        queryRow = -1; *query = reinterpret_cast<Sqlite3Stmt *>(1); return 0;
    };
    g_sqlite_step = +[](Sqlite3Stmt *) {
        return ++queryRow < static_cast<int>(pageRows.size()) ? kSqliteRow : kSqliteDone;
    };
    g_sqlite_column_int64 = +[](Sqlite3Stmt *, int column) { return pageRows.at(queryRow).at(column); };
    g_sqlite_column_int = +[](Sqlite3Stmt *, int column) { return static_cast<int>(pageRows.at(queryRow).at(column)); };
    g_sqlite_finalize = +[](Sqlite3Stmt *) { return 0; };
    g_sqlite_close_v2 = +[](Sqlite3 *) { return 0; };
    returnedNode = testNode(91, 10, {100, 0, 200, 100}, "com.miui.home:id/icon_icon", "美团外卖");
    g_original_send_event = nodesArriveDuringDispatch ? +[](void *, void *event) {
        events.push_back(*static_cast<CapturedEvent *>(event));
    } : +[](void *, void *event) {
        const auto &value = *static_cast<CapturedEvent *>(event);
        events.push_back(value);
        // A reader invalidates its cached tree only after content changed.
        if (value.type == kTypeWindowContentChanged) onCreateNode(reinterpret_cast<void *>(1), 91);
    };
    require(performNativePlacement(reinterpret_cast<void *>(1), kVirtualNodeBase + 1, 0),
            "empty-cell move was rejected");
    while (g_model_move_running.load()) std::this_thread::sleep_for(std::chrono::milliseconds(10));
    const bool announcedSuccess = std::any_of(events.begin(), events.end(), [](const auto &event) {
        return event.text == u"已放置在第3页，第1行，第2列";
    });
    require(announcedSuccess == !positionChanged,
            "move announcement disagrees with the final database and reader nodes");
    if (positionChanged) {
        require(std::any_of(events.begin(), events.end(), [](const auto &event) {
            return event.text == u"桌面刷新后位置未能确认，请检查图标位置后重试";
        }), "reload overwrote the destination but the user was told the position was saved");
    }
}

void placementRefreshNotifiesReaderBeforeVisibilityWait() {
    checkPlacementAfterReload(false);
}

void placementRechecksDatabaseAfterReload() {
    checkPlacementAfterReload(true);
}

void placementKeepsNodesObservedDuringDispatch() {
    checkPlacementAfterReload(false, true);
}

void cancelRemovesVirtualGridAndFocus() {
    AccessibilityCapture capture;
    prepareGrid();
    g_source = g_workspace[1];
    placementFocusCalls = 0;
    g_original_perform_action = +[](void *, int32_t id, int32_t action, void *arguments) {
        require(id == 11 && action == kAccessibilityFocusAction && arguments != nullptr
                        && static_cast<uintptr_t *>(arguments)[0] == 1
                        && static_cast<uintptr_t *>(arguments)[1] == 0,
                "cancel did not restore the source with a native None Bundle");
        ++placementFocusCalls;
        return true;
    };
    updateTouchExplorationTarget(9, 150, 50);
    require(onPerformAction(reinterpret_cast<void *>(1), 10, kCancelAction, nullptr), "cancel failed");
    require(!g_lifted.load() && g_slots.empty() && g_grid_cells.empty()
            && g_virtual_focused_id.load() == -1 && placementFocusCalls == 1,
            "cancel left stale grid or focus");
}

std::vector<int64_t> childIds;
std::unordered_map<int32_t, TestNode> readerTree;

void setChildren(TestNode &node, const std::vector<int64_t> &children) {
    putField(node, 0x30, children.size());
    putField(node, 0x38, children.data());
    putField(node, 0x40, children.size());
}

bool acceptFolderFocus = false, cancelDuringFolderFocus = false;
int folderFocusCalls = 0;

void folderRefreshWaitsForAcceptedFocus() {
    AccessibilityCapture capture;
    struct Cleanup { ~Cleanup() { g_folder_refresh_active.store(false); } } cleanup;
    g_launcher_window_focused.store(1);
    g_folder_refresh_querying.store(false);
    readerTree.clear();
    readerTree.emplace(-1, testNode(10, -1, {0, 0, 400, 600},
            "com.miui.home:id/folder_grid_view", ""));
    readerTree.emplace(11, testNode(11, 10, {0, 0, 100, 100}, "com.miui.home:id/icon_icon", "Gemini"));
    readerTree.emplace(12, testNode(12, 10, {100, 0, 200, 100}, "com.miui.home:id/icon_icon", "相册"));
    childIds = {(int64_t{11} << 32) | 7, (int64_t{12} << 32) | 7};
    setChildren(readerTree.at(-1), childIds);
    g_original_create_node = +[](void *, int32_t id) -> NodeResult {
        const auto found = readerTree.find(id);
        return found == readerTree.end() ? NodeResult{1, nullptr} : NodeResult{0, found->second.data()};
    };
    g_original_perform_action = +[](void *, int32_t nodeId, int32_t action, void *) {
        require(nodeId == 11 && action == kAccessibilityFocusAction,
                "folder refresh focused the wrong member");
        ++folderFocusCalls;
        if (cancelDuringFolderFocus) {
            g_folder_refresh_active.store(false);
            g_folder_refresh_result.store(-1);
        }
        return acceptFolderFocus;
    };
    require(beginFolderRefresh(), "folder focus refresh did not start");
    g_folder_refresh_stage = 2;
    g_folder_refresh_source = "Gemini";
    g_folder_refresh_target = "相册";
    g_folder_refresh_after = false;
    folderFocusCalls = 0;
    acceptFolderFocus = false;
    cancelDuringFolderFocus = false;
    advanceFolderRefresh(reinterpret_cast<void *>(1));
    require(folderFocusCalls == 1 && g_folder_refresh_result.load() == 0
                    && g_folder_refresh_stage == 2,
            "rejected focus action was reported as a completed refresh");
    acceptFolderFocus = true;
    cancelDuringFolderFocus = true;
    advanceFolderRefresh(reinterpret_cast<void *>(1));
    require(folderFocusCalls == 2 && g_folder_refresh_result.load() == -1,
            "focus callback overwrote refresh cancellation with success");
    require(beginFolderRefresh(), "next folder focus refresh did not start");
    g_folder_refresh_stage = 2;
    cancelDuringFolderFocus = false;
    advanceFolderRefresh(reinterpret_cast<void *>(1));
    require(folderFocusCalls == 3 && g_folder_refresh_result.load() == 1
                    && !g_folder_refresh_active.load(),
            "accepted focus action did not complete the refreshed folder order");
}

std::vector<int32_t> capturedActions;

struct ActionCapture {
    ActionCapture() {
        capturedActions.clear();
        g_action_new = +[](int32_t id, void *) -> void * { return new int32_t(id); };
        g_action_drop = +[](void *action) { delete static_cast<int32_t *>(action); };
        g_original_add_action = +[](void *, void *action) {
            capturedActions.push_back(*static_cast<int32_t *>(action));
        };
    }
    ~ActionCapture() {
        g_action_new = nullptr;
        g_action_drop = nullptr;
        g_original_add_action = nullptr;
    }
};

void returningHomeKeepsActionsOnNodesBuiltDuringFocusCallback() {
    AccessibilityCapture capture;
    ActionCapture actions;
    prepareGrid();
    g_lifted.store(false);
    SnapshotFavorite app;
    app.id = 101; app.title = "QQ";
    app.intent = "#Intent;component=com.tencent.mobileqq/.MainActivity;end";
    app.placement = {101, kDesktopContainer, 3, 0, 0, 1, 1, 0};
    g_snapshot.ready = true;
    g_snapshot.favorites = {app};
    returnedNode = testNode(11, 10, {0, 0, 100, 100}, "com.miui.home:id/icon_icon", "QQ");
    g_original_launcher_focus = +[](void *window, bool focused) {
        if (focused) onCreateNode(window, 11);
    };
    for (int iteration = 0; iteration < 10; ++iteration) {
        onLauncherWindowFocus(reinterpret_cast<void *>(1), false);
        capturedActions.clear();
        onLauncherWindowFocus(reinterpret_cast<void *>(1), true);
        require(std::find(capturedActions.begin(), capturedActions.end(), kLiftAction)
                        != capturedActions.end(),
                "returning Home cached an icon without any module actions");
        require(std::find(capturedActions.begin(), capturedActions.end(), kAppInfoAction)
                        != capturedActions.end(),
                "returning Home lost the app details action");
    }
}

void checkDropKeepsTheFavoriteCapturedAtLift(bool folder, bool resourceTitle = false) {
    AccessibilityCapture capture;
    prepareGrid();
    g_lifted.store(false);
    g_visible_screen.store(3);
    SnapshotFavorite app;
    app.id = 101; app.title = folder ? "旅行交通" : "美团外卖";
    if (resourceTitle) app.title = "com.miui.home:string/default_folder_title_tools";
    app.placement = {101, kDesktopContainer, 4, 0, 0, 1, 1, folder ? 2 : 0};
    g_snapshot.ready = true;
    g_snapshot.favorites = {app};
    if (resourceTitle) {
        auto other = app;
        other.id = other.placement.id = 102;
        other.placement.cellX = 3;
        g_snapshot.favorites.push_back(other);
    }
    readerTree.clear();
    readerTree.emplace(-1, testNode(10, -1, {0, 0, 400, 600},
            "com.miui.home:id/screen_content", "第 4 页共 4 页"));
    readerTree.emplace(90, testNode(90, 10, {0, 0, 100, 100},
            folder ? "com.miui.home:id/folder" : "com.miui.home:id/icon_icon",
            resourceTitle ? "系统工具" : app.title.c_str()));
    childIds = {(int64_t{90} << 32) | 7};
    setChildren(readerTree.at(-1), childIds);
    g_original_create_node = +[](void *, int32_t id) -> NodeResult {
        auto found = readerTree.find(id);
        return found == readerTree.end() ? NodeResult{1, nullptr}
                : NodeResult{0, found->second.data()};
    };
    g_node_drop = +[](void *) {};
    require(onPerformAction(reinterpret_cast<void *>(1), 90, kLiftAction, nullptr),
            "single application could not be lifted");
    require(g_source_placements.size() == 1 && g_source_placements[0].id == 101,
            "lift did not capture the database favorite identity");
    if (resourceTitle) {
        const Bounds bounds{0, 0, 100, 100};
        AppTarget target;
        require(!resolveAppTarget(90, "系统工具", target, 3, 2, &bounds),
                "resource folder used the grid from another page");
        require(!resolveAppTarget(90, "系统工具", target, 4, 2),
                "resource folder resolved without physical bounds");
        auto duplicate = app;
        duplicate.id = duplicate.placement.id = 103;
        g_snapshot.favorites.push_back(duplicate);
        require(!resolveAppTarget(90, "系统工具", target, 4, 2, &bounds),
                "ambiguous folder records selected an arbitrary favorite");
        g_snapshot.favorites.pop_back();
    }
    // A refreshed app label must not change the identity of an already lifted item.
    g_snapshot.favorites[0].title = "外卖";
    ++g_snapshot.generation;
    if (folder) {
        readerTree.at(-1) = testNode(10, -1, {0, 0, 400, 600},
                "com.miui.home:id/screen_content", "第 3 页共 4 页");
        g_visible_screen.store(2);
    }
    refreshLiftSessionForPage(reinterpret_cast<void *>(1));
    const int32_t destination = virtualNodeAt(150, 50);
    require(isVirtualNode(destination), "the destination is not an empty cell");
    expectedOperation = 855;
    g_frb_dispatcher = captureDispatch;
    g_capabilities.fetch_or(CAP_FRB_MODEL);
    g_current_frb_move_profile.store(false);
    g_launcher_profile.store(LauncherAbiProfile::kLegacy7748);
    bool dispatchedMove = false;
    try {
        onPerformAction(reinterpret_cast<void *>(1), destination, 16, nullptr);
    } catch (const CapturedDispatch &) {
        dispatchedMove = true;
    }
    g_frb_dispatcher = nullptr;
    g_model_move_running.store(false);
    require(dispatchedMove,
            "double tapping an empty cell discarded the captured favorite and reported no space");
    WireWriter expected;
    auto target = app.placement;
    target.cellX = 1;
    if (folder) target.screen = 3;
    expected.put32(1);
    appendSparseItemInfo(expected, target, target);
    require(dispatched == expected.bytes, "7748 move did not preserve the lifted favorite and destination");
}

void singleAppDropKeepsTheFavoriteCapturedAtLift() {
    checkDropKeepsTheFavoriteCapturedAtLift(false);
}

void folderDropKeepsTheFavoriteAcrossPages() {
    checkDropKeepsTheFavoriteCapturedAtLift(true);
}

void localizedFolderDropMatchesItsPhysicalCell() {
    checkDropKeepsTheFavoriteCapturedAtLift(true, true);
}

void unresolvedSourceIsNotReportedAsNoSpace() {
    AccessibilityCapture capture;
    prepareGrid();
    g_snapshot.ready = true;
    g_sources = {g_workspace[1]};
    g_capabilities.fetch_or(CAP_FRB_MODEL);
    g_frb_dispatcher = captureDispatch;
    const bool moved = onPerformAction(nullptr, virtualNodeAt(150, 50), 16, nullptr);
    g_frb_dispatcher = nullptr;
    require(!moved && g_lifted.load(), "unresolved source changed the layout or cleared the lift");
    require(std::none_of(events.begin(), events.end(), [](const auto &event) {
        return event.text == u"没有可拖放的位置";
    }), "source lookup failure was incorrectly announced as a full desktop");
    require(std::any_of(events.begin(), events.end(), [](const auto &event) {
        return event.type == kTypeAnnouncement && !event.text.empty();
    }), "source lookup failure was silent");
}

void childOrderMatchesTouchOrderWithoutTraversalHints() {
    AccessibilityCapture capture;
    prepareGrid();
    g_workspace.resize(3);
    g_workspace[2] = {12, 10, 1, 2, {200, 0, 300, 100},
            "com.miui.home:id/icon_icon", "微信"};
    rebuildSlots();
    returnedNode = testNode(10, -1, {0, 0, 400, 600}, "com.miui.home:id/screen_content", "");
    childIds = {(int64_t{11} << 32) | 7, (int64_t{12} << 32) | 7};
    setChildren(returnedNode, childIds);
    g_node_add_child = +[](void *node, int32_t host, int32_t child) {
        childIds.push_back((int64_t{child} << 32) | static_cast<uint32_t>(host));
        setChildren(*static_cast<TestNode *>(node), childIds);
    };
    onCreateNode(reinterpret_cast<void *>(1), 10);
    g_node_add_child = nullptr;
    require(childIds.size() > 2 && static_cast<int32_t>(childIds[0] >> 32) == 11
                    && static_cast<int32_t>(childIds[1] >> 32) == virtualNodeAt(150, 50)
                    && static_cast<int32_t>(childIds[2] >> 32) == 12,
            "reader child order is QQ, WeChat, empty cells instead of physical touch order");
}

void staleFocusStillExposesLiftedGrid() {
    AccessibilityCapture capture;
    prepareGrid();
    g_launcher_window_focused.store(0);
    returnedNode = testNode(10, -1, {0, 0, 400, 600},
            "com.miui.home:id/screen_content", "");
    childIds = {(int64_t{11} << 32) | 7};
    setChildren(returnedNode, childIds);
    g_node_add_child = +[](void *node, int32_t host, int32_t child) {
        childIds.push_back((int64_t{child} << 32) | static_cast<uint32_t>(host));
        setChildren(*static_cast<TestNode *>(node), childIds);
    };
    onCreateNode(reinterpret_cast<void *>(1), 10);
    g_node_add_child = nullptr;
    require(std::any_of(childIds.begin(), childIds.end(), [](int64_t child) {
        return isVirtualNode(static_cast<int32_t>(child >> 32));
    }), "stale focus state hid the lifted virtual grid");
}

void liftedSourceDoesNotCompeteWithItsEmptyCell() {
    AccessibilityCapture capture;
    prepareGrid();
    g_lifted_screen.store(g_visible_screen.load());
    g_sources = {g_workspace[1]};
    rebuildSlots();
    returnedNode = testNode(10, -1, {0, 0, 400, 600},
            "com.miui.home:id/screen_content", "");
    childIds = {(int64_t{g_workspace[1].id} << 32) | 7,
            (int64_t{virtualNodeAt(50, 50)} << 32) | 7,
            (int64_t{virtualNodeAt(150, 50)} << 32) | 7};
    setChildren(returnedNode, childIds);
    orderGridChildren(returnedNode.data());
    require(nodeField<size_t>(returnedNode.data(), 0x40) == 2
                    && static_cast<int32_t>(childIds[0] >> 32) == virtualNodeAt(50, 50),
            "lifted source still competes with the vacated cell in reader traversal");
}

void cancelledVirtualNodesNeverReachLauncher() {
    AccessibilityCapture capture;
    prepareGrid();
    const int32_t empty = virtualNodeAt(150, 50);
    clearLiftSession();
    g_original_create_node = +[](void *, int32_t id) -> NodeResult {
        require(!isVirtualNode(id), "cancelled virtual node query reached launcher provider");
        return {1, nullptr};
    };
    g_original_perform_action = +[](void *, int32_t id, int32_t, void *) {
        require(!isVirtualNode(id), "stale virtual action reached launcher provider");
        return false;
    };
    const auto result = onCreateNode(reinterpret_cast<void *>(1), empty);
    require(result.absent == 1, "cancelled cell remained accessible");
    require(!onPerformAction(reinterpret_cast<void *>(1), empty, kClearAccessibilityFocusAction, nullptr),
            "cancelled cell accepted an action");
    g_original_perform_action = nullptr;
}

void absentNativeNodeNeverDereferencesPayload() {
    AccessibilityCapture capture;
    g_original_create_node = +[](void *, int32_t) -> NodeResult {
        // Rust None does not initialize the payload register.
        return {1, reinterpret_cast<void *>(1)};
    };
    require(onCreateNode(reinterpret_cast<void *>(1), 99).absent == 1,
            "absent node result was not preserved");
}

void liftingIconUsesLauncherFeedback() {
    AccessibilityCapture capture;
    prepareGrid();
    g_node_drop = +[](void *) {};
    g_snapshot.ready = true;
    g_lifted.store(false);
    readerTree.clear();
    readerTree.emplace(-1, testNode(10, -1, {0, 0, 400, 600}, "com.miui.home:id/screen_content", ""));
    readerTree.emplace(11, testNode(11, 10, {0, 0, 100, 100}, "com.miui.home:id/icon_icon", "QQ"));
    childIds = {(int64_t{11} << 32) | 7};
    setChildren(readerTree.at(-1), childIds);
    g_original_create_node = +[](void *, int32_t id) -> NodeResult {
        auto found = readerTree.find(id);
        return found == readerTree.end() ? NodeResult{1, nullptr} : NodeResult{0, found->second.data()};
    };
    require(onPerformAction(reinterpret_cast<void *>(1), 11, kLiftAction, nullptr), "lift failed");
    require(std::count_if(events.begin(), events.end(), [](const auto &event) {
        return event.type == kTypeAnnouncement && event.text == u"已拖起";
    }) == 1, "successful lift must speak the launcher's single-item drag feedback once");
    require(std::none_of(events.begin(), events.end(), [](const auto &event) {
        return event.text.find(u"已托起") != std::u16string::npos;
    }), "lift still includes the module's old custom success wording");

    clearLiftSession();
    events.clear();
    readerTree.emplace(12, testNode(12, 10, {200, 0, 300, 100}, "com.miui.home:id/icon_icon", "微信"));
    putField(readerTree.at(11), 0x430, uint32_t{0x2});
    putField(readerTree.at(12), 0x430, uint32_t{0x2});
    childIds.push_back((int64_t{12} << 32) | 7);
    setChildren(readerTree.at(-1), childIds);
    require(onPerformAction(reinterpret_cast<void *>(1), 11, kLiftAction, nullptr), "multi-lift failed");
    require(std::count_if(events.begin(), events.end(), [](const auto &event) {
        return event.type == kTypeAnnouncement && event.text == u"已拖起2个图标";
    }) == 1, "multi-lift must use the launcher's plural drag feedback");
}

void announcementFallbackFlushesAfterControllerRecovery() {
    AccessibilityCapture capture;
    g_launcher_window_focused.store(1);
    g_event_controller.store(nullptr);
    require(!announceReliably(7, 8, 10, "已拖起"),
            "announcement unexpectedly reported delivery without an event controller");
    require(events.empty(), "announcement fallback emitted while the event controller was absent");
    g_event_controller.store(reinterpret_cast<void *>(1));
    flushSurfaceAnnouncements();
    require(std::count_if(events.begin(), events.end(), [](const auto &event) {
        return event.type == kTypeAnnouncement && event.text == u"已拖起";
    }) == 1, "queued lift announcement was not flushed after controller recovery");
}

void realFocusReplacesVirtualDoubleTapTarget() {
    AccessibilityCapture capture;
    prepareGrid();
    updateTouchExplorationTarget(9, 150, 50);
    onPerformAction(reinterpret_cast<void *>(1), virtualNodeAt(150, 50),
            kAccessibilityFocusAction, nullptr);
    g_original_perform_action = +[](void *, int32_t, int32_t, void *) { return true; };
    require(onPerformAction(reinterpret_cast<void *>(1), 11, kAccessibilityFocusAction, nullptr),
            "real icon did not receive accessibility focus");
    g_original_perform_action = nullptr;
    require(g_virtual_focused_id.load() == -1,
            "swiping to QQ leaves double tap and findFocus pointing at an old empty cell");
}

void gridRefreshKeepsCellIdentityAndFocus() {
    AccessibilityCapture capture;
    prepareGrid();
    const int32_t target = virtualNodeAt(250, 150);
    g_workspace.push_back({14, 10, 1, 2, {100, 0, 200, 100}, "com.miui.home:id/icon_icon", "新增图标"});
    rebuildSlots();
    require(virtualNodeAt(250, 150) == target,
            "adding an earlier icon silently changes the destination of a cached empty-cell ID");
}

void samePageRefreshPreservesDoubleTapTarget() {
    AccessibilityCapture capture;
    prepareGrid();
    g_node_drop = +[](void *) {};
    g_drop_parent_host_id.store(7);
    g_drop_parent_window_id.store(8);
    readerTree.clear();
    readerTree.emplace(-1, testNode(10, -1, {0, 0, 400, 600}, "com.miui.home:id/screen_content", ""));
    readerTree.emplace(11, testNode(11, 10, {0, 0, 100, 100}, "com.miui.home:id/icon_icon", "QQ"));
    childIds = {(int64_t{11} << 32) | 7};
    setChildren(readerTree.at(-1), childIds);
    g_original_create_node = +[](void *, int32_t id) -> NodeResult {
        auto found = readerTree.find(id);
        return found == readerTree.end() ? NodeResult{1, nullptr} : NodeResult{0, found->second.data()};
    };
    updateTouchExplorationTarget(9, 150, 50);
    onPerformAction(reinterpret_cast<void *>(1), virtualNodeAt(150, 50),
            kAccessibilityFocusAction, nullptr);
    const int32_t target = g_virtual_focused_id.load();
    refreshLiftSessionForPage(reinterpret_cast<void *>(1));
    require(g_virtual_focused_id.load() == target,
            "same-page node refresh clears the empty cell before the user's double tap");
}

void dockDoesNotBecomeTheLastDesktopRow() {
    AccessibilityCapture capture;
    prepareGrid();
    g_workspace[0].bounds.bottom = 700;
    g_workspace.push_back({20, -1, 1, 2, {0, 600, 400, 700}, "com.miui.home:id/hotseat", ""});
    g_workspace.push_back({21, 20, 1, 2, {0, 600, 100, 700}, "com.miui.home:id/icon_icon", "电话"});
    rebuildSlots();
    require(virtualNodeAt(150, 650) == -1, "dock is exposed as an empty desktop cell");
    require(std::all_of(g_grid_cells.begin(), g_grid_cells.end(), [](const auto &cell) {
        return cell.bounds.bottom <= 600;
    }), "desktop row geometry includes the dock");
    require(gridNodeAtCellLocked(5, 0) != 21, "dock icon joined the desktop traversal");
}

void nestedIconsAndEmptyCellsShareOneParent() {
    prepareGrid();
    g_workspace.push_back({30, 10, 1, 2, {0, 0, 400, 600}, "", ""});
    for (auto &node : g_workspace) if (node.icon()) node.parent = 30;
    require(findCurrentDropParentNode() == 30,
            "empty cells are siblings of the whole icon group instead of individual icons");
}

void wrapperBoundsDoNotMoveTheirIconsAfterEmptyRows() {
    AccessibilityCapture capture;
    prepareGrid();
    g_workspace.resize(3);
    g_workspace[1].parent = 20;
    g_workspace[2] = {12, 30, 1, 2, {200, 0, 300, 100}, "com.miui.home:id/icon_icon", "微信"};
    g_workspace.push_back({20, 10, 1, 2, {0, 0, 400, 600}, "", ""});
    g_workspace.push_back({30, 10, 1, 2, {0, 0, 400, 600}, "", ""});
    rebuildSlots();
    returnedNode = testNode(10, -1, {0, 0, 400, 600}, "com.miui.home:id/screen_content", "");
    childIds = {(int64_t{20} << 32) | 7, (int64_t{30} << 32) | 7};
    setChildren(returnedNode, childIds);
    g_node_add_child = +[](void *node, int32_t host, int32_t child) {
        childIds.push_back((int64_t{child} << 32) | static_cast<uint32_t>(host));
        setChildren(*static_cast<TestNode *>(node), childIds);
    };
    require(findCurrentDropParentNode() == 10, "separate icon wrappers lost their common page parent");
    onCreateNode(reinterpret_cast<void *>(1), 10);
    require(static_cast<int32_t>(childIds[0] >> 32) == 20
                    && static_cast<int32_t>(childIds[1] >> 32) == virtualNodeAt(150, 50)
                    && static_cast<int32_t>(childIds[2] >> 32) == 30,
            "wrapper bounds reordered its icon away from its actual cell");
}

void homeAnnouncementWaitsForNativeFocusCallback() {
    AccessibilityCapture capture;
    returnedNode = testNode(10, -1, {0, 0, 400, 600}, "com.miui.home:id/screen_content", "");
    onLauncherWindowFocus(reinterpret_cast<void *>(1), false);
    g_original_launcher_focus = +[](void *, bool focused) {
        if (!focused) return;
        restoreNativeEventController();
        onCreateNode(reinterpret_cast<void *>(1), 10);
        require(events.empty(), "Home announcement raced ahead of native focus completion");
    };
    onLauncherWindowFocus(reinterpret_cast<void *>(1), true);
    onCreateNode(reinterpret_cast<void *>(1), 10);
    require(events.size() == 1 && events.front().text == u"系统桌面",
            "confirmed return failed to announce Home once");
}

void openingAppSeparatesRepeatedHomeAnnouncements() {
    AccessibilityCapture capture;
    returnedNode = testNode(11, 10, {0, 0, 100, 100}, "com.miui.home:id/icon_icon", "QQ");
    g_original_perform_action = +[](void *, int32_t, int32_t, void *) { return true; };
    require(onPerformAction(reinterpret_cast<void *>(1), 11, 16, nullptr), "app click was rejected");
    require(std::count_if(events.begin(), events.end(), [](const auto &event) {
        return event.type == kTypeViewClicked && event.node == 11;
    }) == 1, "successful app click did not emit the platform click event");
    require(std::none_of(events.begin(), events.end(), [](const auto &event) {
        return event.type == kTypeAnnouncement && event.text == u"正在打开 QQ";
    }), "native app click still emitted a module-owned opening announcement");
}

void liftingDockIconKeepsTheVisibleDesktopPage() {
    AccessibilityCapture capture;
    prepareGrid();
    g_lifted.store(false);
    g_visible_screen.store(3);
    g_node_drop = +[](void *) {};
    g_snapshot.ready = true;
    SnapshotFavorite phone;
    phone.id = 101; phone.title = "电话";
    // Hotseat screen is an item rank, not a desktop page number.
    phone.placement = {101, -101, 2, 2, 0, 1, 1, 0};
    g_snapshot.favorites = {phone};
    readerTree.clear();
    readerTree.emplace(-1, testNode(10, -1, {0, 0, 400, 600}, "com.miui.home:id/screen_content", "第 4 页共 4 页"));
    readerTree.emplace(11, testNode(11, 20, {200, 600, 300, 700}, "com.miui.home:id/icon_icon", "电话"));
    childIds = {(int64_t{11} << 32) | 7};
    setChildren(readerTree.at(-1), childIds);
    g_original_create_node = +[](void *, int32_t id) -> NodeResult {
        auto found = readerTree.find(id);
        return found == readerTree.end() ? NodeResult{1, nullptr} : NodeResult{0, found->second.data()};
    };
    require(onPerformAction(reinterpret_cast<void *>(1), 11, kLiftAction, nullptr), "dock lift failed");
    require(g_visible_screen.load() == 3 && g_lifted_screen.load() == 3,
            "dock rank was mistaken for the destination desktop page");
}
}

#include "native_diagnostics_test.h"

int main() {
    int failures = 0;
    const std::pair<const char *, void (*)()> tests[] = {
        {"input injection decodes native Result<bool>", inputInjectionDecodesNativeResult},
        {"folder gesture confirmation matches Home database relations",
                folderPlacementConfirmationMatchesHomeDatabaseRelations},
        {"workspace semantics recovery detects only missing current-page icons", workspaceRecoveryUsesVisiblePageIcons},
        {"sparse ItemInfo preserves the verified 75-byte wire layout", sparseItemInfoKeepsTheVerifiedWireLayout},
        {"folder ItemInfo encodes UTF-8 title/label and all 35 fields", folderItemInfoEncodesUtf8TitleAndLabel},
        {"folder identity uses visible members to disambiguate", folderIdentityUsesVisibleMembersToDisambiguate},
        {"folder identity ignores icons outside the open grid", folderIdentityIgnoresIconsOutsideTheOpenGrid},
        {"folder refresh stops touch input after cancellation", folderRefreshInputStopsAfterCancellation},
        {"move-out focus requires the saved desktop page and cell", movedOutIconRequiresItsConfirmedDesktopCell},
        {"folder order requires adjacent unique members", folderOrderRequiresAdjacentUniqueMembers},
        {"folder refresh confirms only an accepted focus action", folderRefreshWaitsForAcceptedFocus},
        {"single-icon page builds a complete physical grid", singleIconPageBuildsPhysicalGrid},
        {"missing page bounds preserve the confirmed grid", missingPageBoundsKeepConfirmedGrid},
        {"incomplete node tree keeps database cells occupied", incompleteNodeTreeKeepsDatabaseCellsOccupied},
        {"sparse page grid keeps hidden favorites occupied", sparsePageGridKeepsHiddenFavoritesOccupied},
        {"lifted source cell is available for drop", liftedSourceCellIsAvailableForDrop},
        {"page announcement uses the TalkBack announcement record", pageAnnouncementUsesTalkBackAnnouncementRecord},
        {"sparse launcher screens use visual page ordinals", sparseLauncherScreensUseVisualPageOrdinals},
        {"sparse grid keeps physical coordinates", sparseGridKeepsEmptyColumnsAndRows},
        {"traversal order keeps physical empty cells", traversalAfterKeepsEmptyCellsInPhysicalOrder},
        {"touch empty cell reads coordinates", touchingEmptyCellReadsItsCoordinates},
        {"hover exit preserves double tap target", liftingFingerKeepsDoubleTapTarget},
        {"actual six-row layout has no phantom seventh row", launcherDimensionsOverrideOldSeventhRow},
        {"widget spans and cell touch boundaries", widgetCellsAndTouchGapsAreHandled},
        {"native hover handoff and event ABI", touchHookUsesEventArgumentAndHandsBackOccupiedCells},
        {"cross-page counts require actual icon node and location", changedCountsDoNotProveIconVisible},
        {"move request includes full destination", moveRequestIncludesDestinationArguments},
        {"occupied placement batches every displaced icon", occupiedPlacementMovesDisplacedIconsInNativeBatch},
        {"recycled node ID across pages keeps target actions", recycledNodeOnAnotherPageIsNotTheLiftedSource},
        {"cancel restores normal accessibility", cancelRemovesVirtualGridAndFocus},
        {"rebuilt icon IDs stay in the mixed traversal", traversalUsesCurrentRealIconIdentity},
        {"QQ - empty column - WeChat traversal in both directions", mixedGridOverridesNativeIconOnlyTraversal},
        {"confirmed page speaks before delayed Flutter event without duplicates", pageIndicatorAnnouncesBeforeDelayedFlutterEvent},
        {"empty and reverse pages speak without prediction", emptyAndReversePagesSpeakWithoutPredictions},
        {"page speech failure preserves native fallback", pageSpeechFailurePreservesNativeFallback},
        {"page indicator keeps its default description", pageNodeKeepsDefaultDescriptionAndTotalPages},
        {"page speech accepts actual workspace node sources", pageSpeechHandlesWorkspaceAnnouncementSources},
        {"page speech survives a transient focus loss", pageSpeechInterceptsAnnouncementDuringFocusCallback},
        {"page speech survives native workspace source rebuild", pageSpeechSurvivesNativeWorkspaceSourceRebuild},
        {"announced page rebuilds a reused grid host", announcedPageRebuildsAReusedGridHost},
        {"move reuses cached favorite after source resolution failure", moveUsesCachedFavoriteWhenSnapshotResolutionFails},
        {"move visibility requires the destination page", pendingPlacementRequiresTheDestinationPage},
        {"placed icon regains accessibility focus once", placedIconReceivesFocusOnceAfterLiftCleanup},
        {"Back restores source focus on the next live query", backCancellationRestoresTheSourceOnTheNextQuery},
        {"move refresh notifies reader before waiting for its nodes", placementRefreshNotifiesReaderBeforeVisibilityWait},
        {"move rechecks persisted coordinates after reload", placementRechecksDatabaseAfterReload},
        {"move retains nodes observed during native dispatch", placementKeepsNodesObservedDuringDispatch},
        {"cancelled swipe keeps confirmed page", cancelledSwipeKeepsConfirmedPage},
        {"ten returns to Home announce ten times", everyReturnHomeAnnouncesOnce},
        {"system Back gesture cancels lifted grid", backGestureCancelsLiftAndKeepsNativeCleanup},
        {"Back key cancels only the lifted session", backKeyCancelsOnlyTheLiftedSession},
        {"leaving Home cancels grid and rejects stale nodes", leavingHomeCancelsGridAndRejectsStaleNodes},
        {"reader child order matches physical touch order", childOrderMatchesTouchOrderWithoutTraversalHints},
        {"lifted source does not compete with its empty cell", liftedSourceDoesNotCompeteWithItsEmptyCell},
        {"stale focus keeps lifted grid visible", staleFocusStillExposesLiftedGrid},
        {"cancelled virtual IDs never reach native launcher", cancelledVirtualNodesNeverReachLauncher},
        {"single and multiple lift use launcher speech", liftingIconUsesLauncherFeedback},
        {"quick Home return without node query announces once", quickReturnHomeWithoutNodeQueryAnnounces},
        {"announcement fallback survives controller replacement", announcementFallbackFlushesAfterControllerRecovery},
        {"real focus clears old empty-cell target", realFocusReplacesVirtualDoubleTapTarget},
        {"empty cell IDs survive occupancy changes", gridRefreshKeepsCellIdentityAndFocus},
        {"same-page refresh preserves empty-cell focus", samePageRefreshPreservesDoubleTapTarget},
        {"dock stays outside desktop rows and traversal", dockDoesNotBecomeTheLastDesktopRow},
        {"nested icons share their parent with empty cells", nestedIconsAndEmptyCellsShareOneParent},
        {"wrapper bounds preserve descendant icon order", wrapperBoundsDoNotMoveTheirIconsAfterEmptyRows},
        {"Home speech waits for native focus completion", homeAnnouncementWaitsForNativeFocusCallback},
        {"app opening separates repeated Home announcements", openingAppSeparatesRepeatedHomeAnnouncements},
        {"lifting dock item keeps actual desktop page", liftingDockIconKeepsTheVisibleDesktopPage},
        {"returning Home keeps actions during native focus callback", returningHomeKeepsActionsOnNodesBuiltDuringFocusCallback},
        {"single app drop preserves captured favorite across snapshot refresh", singleAppDropKeepsTheFavoriteCapturedAtLift},
        {"folder drop preserves captured favorite across pages", folderDropKeepsTheFavoriteAcrossPages},
        {"localized folder favorite matches the physical cell", localizedFolderDropMatchesItsPhysicalCell},
        {"source lookup failure is not a full desktop", unresolvedSourceIsNotReportedAsNoSpace},
        {"absent node has no readable payload", absentNativeNodeNeverDereferencesPayload},
        {"diagnostics disable and epoch isolation", diagnosticsBufferRespectsEpochs},
        {"diagnostics overflow and retry retain newest records", diagnosticsBufferBoundsAndRetries},
        {"diagnostics UTF-8 and Binder batch limits", diagnosticsBufferPreservesUtf8AndBounds},
        {"native logger through ContentResolver retries and acknowledgements", diagnosticsTransportRetriesAndRejectsStaleEpochs},
        {"native ContentResolver result ownership", diagnosticsResultOwnership},
        {"Home actions and empty-cell failures enter diagnostics", diagnosticsRecordsHomeAndPlacementFailures},
        {"move diagnostics distinguish model persistence and reader visibility", diagnosticsRecordsPlacementEvidence},
        {"move candidate diagnostics stay bounded", diagnosticsBoundsPlacementCandidates}
    };
    for (const auto &[name, test] : tests) {
        try {
            test();
            std::cout << "PASS: " << name << '\n';
        } catch (const std::exception &error) {
            ++failures;
            std::cerr << "FAIL: " << name << ": " << error.what() << '\n';
        }
    }
    return failures == 0 ? 0 : 1;
}
