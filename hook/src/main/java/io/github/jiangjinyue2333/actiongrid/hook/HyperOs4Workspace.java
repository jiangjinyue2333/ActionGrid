package io.github.jiangjinyue2333.actiongrid.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class HyperOs4Workspace {
    private static final Uri FAVORITES_URI = Uri.parse(
            "content://com.miui.home.launcher.settings/favorites");
    private static final String[] PROJECTION = {
            "_id", "title", "itemType", "container", "screen",
            "cellX", "cellY", "spanX", "spanY", "intent"
    };
    private static final String ICON_RESOURCE_ID = "com.miui.home:id/icon_icon";
    private static final String FOLDER_RESOURCE_ID = "com.miui.home:id/folder";
    private static final String SCREEN_RESOURCE_ID = "com.miui.home:id/screen_content";
    private static final String WORKSPACE_RESOURCE_ID = "com.miui.home:id/workspace";
    private static final String HOTSEAT_RESOURCE_ID = "com.miui.home:id/hotseat";
    private static final long NODE_MAX_AGE_MILLIS = 30_000L;
    private static final long FAVORITES_REFRESH_THROTTLE_MILLIS = 300L;

    interface Listener {
        void onWorkspaceChanged();
    }

    enum GridKind {
        DESKTOP,
        HOTSEAT
    }

    enum PlacementRelation {
        EMPTY,
        BEFORE,
        AFTER,
        CREATE_FOLDER
    }

    record ResolvedNode(
            int windowId,
            long nodeId,
            String uniqueId,
            Rect bounds,
            String label,
            StableItemRef item,
            GridKind gridKind,
            long observedAt,
            HyperOs4Compatibility.FavoriteCell favorite
    ) {
        ResolvedNode {
            bounds = new Rect(bounds);
        }
    }

    record DropTarget(
            int virtualId,
            long parentNodeId,
            GridKind gridKind,
            long container,
            long screenId,
            int cellX,
            int cellY,
            Rect bounds,
            Rect parentBounds,
            String description
    ) {
        DropTarget {
            bounds = new Rect(bounds);
            parentBounds = new Rect(parentBounds);
        }

        int centerX() {
            return bounds.centerX();
        }

        int centerY() {
            return bounds.centerY();
        }
    }

    private final Context context;
    private final Listener listener;
    private final Method getSourceNodeId;
    private final HandlerThread loaderThread = new HandlerThread("ActionGrid-favorites");
    private final Handler loaderHandler;
    private final Map<Integer, WindowState> windows = new HashMap<>();
    private volatile List<HyperOs4Compatibility.FavoriteCell> favorites = List.of();
    private volatile boolean virtualIdCollision;
    private volatile long lastFavoritesRefreshRequest;

    HyperOs4Workspace(Context context, Listener listener, Method getSourceNodeId) {
        this.context = context;
        this.listener = listener;
        this.getSourceNodeId = getSourceNodeId;
        loaderThread.start();
        loaderHandler = new Handler(loaderThread.getLooper());
        context.getContentResolver().registerContentObserver(
                FAVORITES_URI, true, new ContentObserver(loaderHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        reload();
                    }
                });
        loaderHandler.post(this::reload);
    }

    synchronized Map<AccessibilityNodeInfo, ResolvedNode> observe(
            int windowId,
            List<AccessibilityNodeInfo> nodes
    ) throws ReflectiveOperationException {
        long now = SystemClock.elapsedRealtime();
        if (now - lastFavoritesRefreshRequest >= FAVORITES_REFRESH_THROTTLE_MILLIS) {
            lastFavoritesRefreshRequest = now;
            loaderHandler.post(this::reload);
        }
        WindowState window = windows.computeIfAbsent(windowId, ignored -> new WindowState());
        boolean desktopRootSeen = false;
        int displayWidth = context.getResources().getDisplayMetrics().widthPixels;
        int displayHeight = context.getResources().getDisplayMetrics().heightPixels;

        for (AccessibilityNodeInfo node : nodes) {
            if (!isLauncherNode(node)) {
                continue;
            }
            AccessibilityNodeInfo.CollectionInfo collection = node.getCollectionInfo();
            if (collection == null) {
                continue;
            }
            Rect bounds = bounds(node);
            GridKind kind = classifyGrid(collection, bounds, displayWidth, displayHeight);
            if (kind == null) {
                continue;
            }
            long nodeId = sourceNodeId(node);
            window.grids.put(nodeId, new Grid(nodeId, kind, bounds,
                    collection.getColumnCount(), collection.getRowCount(), now));
            desktopRootSeen |= kind == GridKind.DESKTOP;
        }
        inferGrids(window, nodes, now, displayWidth, displayHeight);
        if (desktopRootSeen) {
            window.nodes.values().removeIf(node -> node.kind() == GridKind.DESKTOP);
        }

        for (AccessibilityNodeInfo node : nodes) {
            if (!isLauncherNode(node)) {
                continue;
            }
            AccessibilityNodeInfo.CollectionItemInfo itemInfo = node.getCollectionItemInfo();
            Rect bounds = bounds(node);
            Grid grid = containingGrid(window, bounds, now);
            if (grid == null) {
                continue;
            }
            int cellX;
            int cellY;
            if (itemInfo != null) {
                if (itemInfo.getColumnSpan() != 1 || itemInfo.getRowSpan() != 1) {
                    continue;
                }
                cellX = itemInfo.getColumnIndex();
                cellY = itemInfo.getRowIndex();
            } else {
                // Some Flutter/Rust nodes expose the grid shape without item metadata.
                if (node.getCollectionInfo() != null || !isIconNode(node)) {
                    continue;
                }
                cellX = HyperOs4Compatibility.cellIndex(bounds.centerX(), grid.bounds().left,
                        grid.bounds().width(), grid.columns());
                cellY = HyperOs4Compatibility.cellIndex(bounds.centerY(), grid.bounds().top,
                        grid.bounds().height(), grid.rows());
            }
            if (cellX < 0 || cellY < 0 || cellX >= grid.columns() || cellY >= grid.rows()) {
                continue;
            }
            long nodeId = sourceNodeId(node);
            if (HyperOs4VirtualNodes.contains(
                    HyperOs4VirtualNodes.virtualDescendantId(nodeId))) {
                virtualIdCollision = true;
            }
            window.nodes.put(nodeId, new RawNode(nodeId, node.getUniqueId(), bounds,
                    label(node), cellX, cellY,
                    grid.kind(), now));
        }

        purge(window, now);
        window.desktopScreen = resolveScreen(window, now);
        Map<AccessibilityNodeInfo, ResolvedNode> resolved = new IdentityHashMap<>();
        for (AccessibilityNodeInfo node : nodes) {
            if (!isLauncherNode(node)) {
                continue;
            }
            long nodeId = sourceNodeId(node);
            ResolvedNode match = resolve(windowId, window, nodeId, now);
            if (match != null) {
                resolved.put(node, match);
            }
        }
        return resolved;
    }

    private void inferGrids(
            WindowState window,
            List<AccessibilityNodeInfo> nodes,
            long observedAt,
            int displayWidth,
            int displayHeight
    ) throws ReflectiveOperationException {
        List<ObservedIcon> desktop = new ArrayList<>();
        List<ObservedIcon> hotseat = new ArrayList<>();
        for (AccessibilityNodeInfo node : nodes) {
            if (!isLauncherNode(node) || !isIconNode(node)) {
                continue;
            }
            Rect bounds = bounds(node);
            if (bounds.isEmpty()) {
                continue;
            }
            ObservedIcon icon = new ObservedIcon(sourceNodeId(node), bounds);
            if (isInsideHost(node, nodes, HOTSEAT_RESOURCE_ID)) {
                hotseat.add(icon);
            } else if (isInsideDesktopHost(node, nodes)
                    || bounds.centerY() < displayHeight * 4 / 5) {
                desktop.add(icon);
            }
        }
        addInferredGrid(window, nodes, desktop, GridKind.DESKTOP, observedAt,
                displayWidth, displayHeight);
        addInferredGrid(window, nodes, hotseat, GridKind.HOTSEAT, observedAt,
                displayWidth, displayHeight);
    }

    private void addInferredGrid(
            WindowState window,
            List<AccessibilityNodeInfo> nodes,
            List<ObservedIcon> icons,
            GridKind kind,
            long observedAt,
            int displayWidth,
            int displayHeight
    ) throws ReflectiveOperationException {
        if (icons.isEmpty()) {
            return;
        }
        AccessibilityNodeInfo host = findHostNode(nodes, icons, kind);
        Rect hostBounds = host == null ? new Rect() : bounds(host);
        if (icons.size() == 1 && hostBounds.isEmpty()) {
            return;
        }
        if (kind == GridKind.DESKTOP) {
            for (AccessibilityNodeInfo node : nodes) {
                if (!HOTSEAT_RESOURCE_ID.equals(node.getViewIdResourceName())) {
                    continue;
                }
                Rect hotseatBounds = bounds(node);
                if (!hotseatBounds.isEmpty()
                        && hotseatBounds.top > hostBounds.top
                        && hotseatBounds.top < hostBounds.bottom) {
                    hostBounds.bottom = Math.min(hostBounds.bottom, hotseatBounds.top);
                    break;
                }
            }
        }
        if (icons.size() == 1 && (hostBounds.width() <= 0 || hostBounds.height() <= 0)) {
            return;
        }
        if (icons.size() == 1) {
            ObservedIcon icon = icons.get(0);
            int minRows = kind == GridKind.DESKTOP ? 4 : 1;
            int columns = inferGridCount(hostBounds.width(), icon.bounds().width(), 3, 10);
            int rows = kind == GridKind.DESKTOP
                    ? inferGridCount(hostBounds.height(), icon.bounds().height(), minRows, 12) : 1;
            if (columns <= 0 || rows <= 0) {
                return;
            }
            long hostId = sourceNodeId(host);
            window.grids.put(hostId, new Grid(hostId, kind, hostBounds,
                    columns, rows, observedAt));
            return;
        }
        List<Integer> xCenters = clusterCenters(icons, true);
        List<Integer> yCenters = clusterCenters(icons, false);
        int minRows = kind == GridKind.DESKTOP ? 4 : 1;
        if (xCenters.size() < 3 || yCenters.size() < minRows) {
            return;
        }
        if (xCenters.size() > 10 || yCenters.size() > 12) {
            return;
        }
        int xGap = medianGap(xCenters, medianDimension(icons, true));
        int yGap = medianGap(yCenters, medianDimension(icons, false));
        if (xGap <= 0 || yGap <= 0) {
            return;
        }
        Rect gridBounds = hostBounds.isEmpty()
                ? new Rect(
                Math.max(0, xCenters.get(0) - xGap / 2),
                Math.max(0, yCenters.get(0) - yGap / 2),
                Math.min(displayWidth, xCenters.get(xCenters.size() - 1) + xGap / 2),
                Math.min(displayHeight, yCenters.get(yCenters.size() - 1) + yGap / 2))
                : new Rect(
                Math.max(hostBounds.left, xCenters.get(0) - xGap / 2),
                Math.max(hostBounds.top, yCenters.get(0) - yGap / 2),
                Math.min(hostBounds.right, xCenters.get(xCenters.size() - 1) + xGap / 2),
                Math.min(hostBounds.bottom, yCenters.get(yCenters.size() - 1) + yGap / 2));
        if (gridBounds.width() <= 0 || gridBounds.height() <= 0) {
            return;
        }
        long hostId = host == null ? icons.get(0).nodeId() : sourceNodeId(host);
        window.grids.put(hostId, new Grid(hostId, kind, gridBounds,
                xCenters.size(), yCenters.size(), observedAt));
    }

    private static int inferGridCount(int extent, int cellSize, int minimum, int maximum) {
        if (extent <= 0 || cellSize <= 0) {
            return 0;
        }
        return Math.max(minimum, Math.min(maximum,
                Math.round((float) extent / cellSize)));
    }

    private static List<Integer> clusterCenters(List<ObservedIcon> icons, boolean horizontal) {
        List<Integer> values = new ArrayList<>(icons.size());
        for (ObservedIcon icon : icons) {
            values.add(horizontal ? icon.bounds().centerX() : icon.bounds().centerY());
        }
        values.sort(Integer::compareTo);
        int tolerance = Math.max(12, medianDimension(icons, horizontal) / 4);
        List<Integer> centers = new ArrayList<>();
        int sum = 0;
        int count = 0;
        int previous = Integer.MIN_VALUE;
        for (int value : values) {
            if (count > 0 && value - previous > tolerance) {
                centers.add(sum / count);
                sum = 0;
                count = 0;
            }
            sum += value;
            count++;
            previous = value;
        }
        if (count > 0) {
            centers.add(sum / count);
        }
        return centers;
    }

    private static int medianDimension(List<ObservedIcon> icons, boolean horizontal) {
        List<Integer> sizes = new ArrayList<>(icons.size());
        for (ObservedIcon icon : icons) {
            sizes.add(horizontal ? icon.bounds().width() : icon.bounds().height());
        }
        sizes.sort(Integer::compareTo);
        return sizes.get(sizes.size() / 2);
    }

    private static int medianGap(List<Integer> centers, int fallback) {
        if (centers.size() < 2) {
            return fallback;
        }
        List<Integer> gaps = new ArrayList<>(centers.size() - 1);
        for (int index = 1; index < centers.size(); index++) {
            gaps.add(centers.get(index) - centers.get(index - 1));
        }
        gaps.sort(Integer::compareTo);
        return gaps.get(gaps.size() / 2);
    }

    private AccessibilityNodeInfo findHostNode(
            List<AccessibilityNodeInfo> nodes,
            List<ObservedIcon> icons,
            GridKind kind
    ) throws ReflectiveOperationException {
        String primary = kind == GridKind.HOTSEAT ? HOTSEAT_RESOURCE_ID : SCREEN_RESOURCE_ID;
        int centerX = icons.get(0).bounds().centerX();
        int centerY = icons.get(0).bounds().centerY();
        for (AccessibilityNodeInfo node : nodes) {
            String resourceId = node.getViewIdResourceName();
            if (!(primary.equals(resourceId)
                    || (kind == GridKind.DESKTOP && WORKSPACE_RESOURCE_ID.equals(resourceId)))) {
                continue;
            }
            Rect bounds = bounds(node);
            if (bounds.contains(centerX, centerY)) {
                return node;
            }
        }
        return null;
    }

    private static boolean isInsideDesktopHost(
            AccessibilityNodeInfo node,
            List<AccessibilityNodeInfo> nodes
    ) {
        return isInsideHost(node, nodes, SCREEN_RESOURCE_ID)
                || isInsideHost(node, nodes, WORKSPACE_RESOURCE_ID);
    }

    private static boolean isInsideHost(
            AccessibilityNodeInfo node,
            List<AccessibilityNodeInfo> nodes,
            String resourceId
    ) {
        Rect iconBounds = bounds(node);
        for (AccessibilityNodeInfo host : nodes) {
            if (!resourceId.equals(host.getViewIdResourceName())) {
                continue;
            }
            Rect hostBounds = bounds(host);
            if (!hostBounds.isEmpty() && hostBounds.contains(
                    iconBounds.centerX(), iconBounds.centerY())) {
                return true;
            }
        }
        return false;
    }

    synchronized ResolvedNode resolve(int windowId, long nodeId) {
        WindowState window = windows.get(windowId);
        return window == null ? null
                : resolve(windowId, window, nodeId, SystemClock.elapsedRealtime());
    }

    synchronized ResolvedNode resolve(long nodeId) {
        long now = SystemClock.elapsedRealtime();
        for (Map.Entry<Integer, WindowState> entry : windows.entrySet()) {
            ResolvedNode resolved = resolve(entry.getKey(), entry.getValue(), nodeId, now);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    synchronized long desktopScreen(int windowId) {
        WindowState window = windows.get(windowId);
        return window == null ? HyperOs4Compatibility.NO_SCREEN : window.desktopScreen;
    }

    synchronized List<DropTarget> emptyTargets(int windowId, long sourceItemId) {
        WindowState window = windows.get(windowId);
        if (window == null) {
            return List.of();
        }
        long now = SystemClock.elapsedRealtime();
        purge(window, now);
        List<DropTarget> targets = new ArrayList<>();
        Grid desktop = latestGrid(window, GridKind.DESKTOP, now);
        HyperOs4Compatibility.FavoriteCell source = favorite(sourceItemId);
        long desktopScreen = window.desktopScreen;
        if (desktopScreen == HyperOs4Compatibility.NO_SCREEN && source != null
                && source.container() == StableItemRef.CONTAINER_DESKTOP) {
            desktopScreen = source.screenId();
        }
        if (desktop != null && desktopScreen != HyperOs4Compatibility.NO_SCREEN) {
            boolean[][] occupied = occupied(desktop.columns(), desktop.rows(),
                    StableItemRef.CONTAINER_DESKTOP, desktopScreen);
            for (int row = 0; row < desktop.rows(); row++) {
                for (int column = 0; column < desktop.columns(); column++) {
                    if (!occupied[column][row]) {
                        targets.add(target(desktop,
                                StableItemRef.CONTAINER_DESKTOP, desktopScreen,
                                column, row, "桌面，第" + (row + 1) + "行第"
                                        + (column + 1) + "列，空白"));
                    }
                }
            }
        }

        Grid hotseat = latestGrid(window, GridKind.HOTSEAT, now);
        if (hotseat != null) {
            boolean[][] occupied = occupied(hotseat.columns(), hotseat.rows(),
                    StableItemRef.CONTAINER_HOTSEAT, HyperOs4Compatibility.NO_SCREEN);
            for (int column = 0; column < hotseat.columns(); column++) {
                if (!occupied[column][0]) {
                    targets.add(target(hotseat,
                            StableItemRef.CONTAINER_HOTSEAT, column,
                            column, 0, "底栏，第" + (column + 1) + "位，空白"));
                }
            }
        }
        return List.copyOf(targets);
    }

    boolean virtualIdsAvailable() {
        return !virtualIdCollision;
    }

    HyperOs4Compatibility.FavoriteCell favorite(long itemId) {
        for (HyperOs4Compatibility.FavoriteCell favorite : favorites) {
            if (favorite.id() == itemId) {
                return favorite;
            }
        }
        return null;
    }

    boolean confirmPlacement(
            long sourceItemId,
            long targetItemId,
            DropTarget target,
            PlacementRelation relation
    ) {
        reloadNow();
        HyperOs4Compatibility.FavoriteCell source = favorite(sourceItemId);
        if (source == null) {
            return false;
        }
        if (relation == PlacementRelation.EMPTY) {
            return source.container() == target.container()
                    && source.cellX() == target.cellX()
                    && source.cellY() == target.cellY()
                    && (target.container() == StableItemRef.CONTAINER_HOTSEAT
                    || source.screenId() == target.screenId());
        }
        HyperOs4Compatibility.FavoriteCell occupiedTarget = favorite(targetItemId);
        if (occupiedTarget == null) {
            return false;
        }
        if (relation == PlacementRelation.CREATE_FOLDER) {
            return source.container() > 0 && source.container() == occupiedTarget.container();
        }
        if (source.container() != occupiedTarget.container()
                || (source.container() != StableItemRef.CONTAINER_HOTSEAT
                && source.screenId() != occupiedTarget.screenId())) {
            return false;
        }
        List<HyperOs4Compatibility.FavoriteCell> ordered = favorites.stream()
                .filter(item -> item.container() == source.container())
                .filter(item -> source.container() == StableItemRef.CONTAINER_HOTSEAT
                        || item.screenId() == source.screenId())
                .filter(HyperOs4Compatibility.FavoriteCell::movable)
                .sorted(Comparator.comparingInt(HyperOs4Compatibility.FavoriteCell::cellY)
                        .thenComparingInt(HyperOs4Compatibility.FavoriteCell::cellX))
                .toList();
        int sourceIndex = indexOf(ordered, sourceItemId);
        int targetIndex = indexOf(ordered, targetItemId);
        return relation == PlacementRelation.BEFORE
                ? sourceIndex >= 0 && sourceIndex + 1 == targetIndex
                : targetIndex >= 0 && targetIndex + 1 == sourceIndex;
    }

    void reloadNow() {
        List<HyperOs4Compatibility.FavoriteCell> loaded = queryFavorites();
        if (loaded == null) {
            return;
        }
        if (!loaded.equals(favorites)) {
            favorites = loaded;
            listener.onWorkspaceChanged();
        }
    }

    private void reload() {
        reloadNow();
    }

    private List<HyperOs4Compatibility.FavoriteCell> queryFavorites() {
        List<HyperOs4Compatibility.FavoriteCell> loaded = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(FAVORITES_URI, PROJECTION,
                null, null, null)) {
            if (cursor == null) {
                return null;
            }
            int id = cursor.getColumnIndexOrThrow("_id");
            int title = cursor.getColumnIndexOrThrow("title");
            int itemType = cursor.getColumnIndexOrThrow("itemType");
            int container = cursor.getColumnIndexOrThrow("container");
            int screen = cursor.getColumnIndexOrThrow("screen");
            int cellX = cursor.getColumnIndexOrThrow("cellX");
            int cellY = cursor.getColumnIndexOrThrow("cellY");
            int spanX = cursor.getColumnIndexOrThrow("spanX");
            int spanY = cursor.getColumnIndexOrThrow("spanY");
            int intent = cursor.getColumnIndex("intent");
            while (cursor.moveToNext()) {
                loaded.add(new HyperOs4Compatibility.FavoriteCell(
                        cursor.getLong(id), cursor.getString(title),
                        cursor.getLong(container), cursor.getLong(screen),
                        cursor.getInt(cellX), cursor.getInt(cellY), cursor.getInt(itemType),
                        Math.max(1, cursor.getInt(spanX)), Math.max(1, cursor.getInt(spanY)),
                        intent < 0 || cursor.isNull(intent) ? null : cursor.getString(intent)));
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return List.copyOf(loaded);
    }

    private ResolvedNode resolve(
            int windowId,
            WindowState window,
            long nodeId,
            long now
    ) {
        RawNode raw = window.nodes.get(nodeId);
        if (raw == null || now - raw.observedAt() > NODE_MAX_AGE_MILLIS) {
            return null;
        }
        HyperOs4Compatibility.FavoriteCell match = null;
        for (HyperOs4Compatibility.FavoriteCell favorite : favorites) {
            boolean samePosition = raw.kind() == GridKind.DESKTOP
                    ? favorite.container() == StableItemRef.CONTAINER_DESKTOP
                    && favorite.screenId() == window.desktopScreen
                    && favorite.cellX() == raw.cellX() && favorite.cellY() == raw.cellY()
                    : favorite.container() == StableItemRef.CONTAINER_HOTSEAT
                    && favorite.cellX() == raw.cellX();
            if (samePosition && favorite.movable()
                    && HyperOs4Compatibility.titleMatches(raw.label(), favorite.title())) {
                if (match != null) {
                    return null;
                }
                match = favorite;
            }
        }
        return match == null ? null : new ResolvedNode(windowId, nodeId, raw.uniqueId(),
                raw.bounds(), raw.label(), match.stableRef(), raw.kind(), raw.observedAt(), match);
    }

    private long resolveScreen(WindowState window, long now) {
        List<HyperOs4Compatibility.SemanticCell> cells = window.nodes.values().stream()
                .filter(node -> node.kind() == GridKind.DESKTOP)
                .filter(node -> now - node.observedAt() <= NODE_MAX_AGE_MILLIS)
                .map(node -> new HyperOs4Compatibility.SemanticCell(
                        node.label(), node.cellX(), node.cellY()))
                .toList();
        return HyperOs4Compatibility.resolveDesktopScreen(favorites, cells);
    }

    private boolean[][] occupied(int columns, int rows, long container, long screen) {
        boolean[][] result = new boolean[columns][rows];
        for (HyperOs4Compatibility.FavoriteCell favorite : favorites) {
            if (favorite.container() != container
                    || (container != StableItemRef.CONTAINER_HOTSEAT
                    && favorite.screenId() != screen)) {
                continue;
            }
            for (int x = favorite.cellX(); x < favorite.cellX() + favorite.spanX(); x++) {
                for (int y = favorite.cellY(); y < favorite.cellY() + favorite.spanY(); y++) {
                    if (x >= 0 && x < columns && y >= 0 && y < rows) {
                        result[x][y] = true;
                    }
                }
            }
        }
        return result;
    }

    private static DropTarget target(
            Grid grid,
            long container,
            long screen,
            int cellX,
            int cellY,
            String description
    ) {
        int left = grid.bounds().left
                + cellX * grid.bounds().width() / grid.columns();
        int top = grid.bounds().top
                + cellY * grid.bounds().height() / grid.rows();
        int right = grid.bounds().left
                + (cellX + 1) * grid.bounds().width() / grid.columns();
        int bottom = grid.bounds().top
                + (cellY + 1) * grid.bounds().height() / grid.rows();
        return new DropTarget(HyperOs4VirtualNodes.id(grid.kind(), cellX, cellY),
                grid.nodeId(), grid.kind(), container, screen, cellX, cellY,
                new Rect(left, top, right, bottom), grid.bounds(), description);
    }

    private static int indexOf(List<HyperOs4Compatibility.FavoriteCell> items, long id) {
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).id() == id) {
                return index;
            }
        }
        return -1;
    }

    private static GridKind classifyGrid(
            AccessibilityNodeInfo.CollectionInfo collection,
            Rect bounds,
            int displayWidth,
            int displayHeight
    ) {
        if (collection.getColumnCount() >= 3 && collection.getColumnCount() <= 10
                && collection.getRowCount() >= 4 && collection.getRowCount() <= 12
                && !bounds.isEmpty()
                && bounds.width() >= displayWidth * 2 / 3
                && bounds.height() >= displayHeight / 2) {
            return GridKind.DESKTOP;
        }
        if (collection.getRowCount() == 1 && collection.getColumnCount() >= 3
                && collection.getColumnCount() <= 10 && !bounds.isEmpty()
                && bounds.centerY() > displayHeight * 2 / 3) {
            return GridKind.HOTSEAT;
        }
        return null;
    }

    private static Grid containingGrid(WindowState window, Rect bounds, long now) {
        Grid best = null;
        int bestArea = Integer.MAX_VALUE;
        for (Grid grid : window.grids.values()) {
            if (now - grid.observedAt() > NODE_MAX_AGE_MILLIS
                    || !grid.bounds().contains(bounds.centerX(), bounds.centerY())) {
                continue;
            }
            int area = grid.bounds().width() * grid.bounds().height();
            if (area < bestArea) {
                best = grid;
                bestArea = area;
            }
        }
        return best;
    }

    private static Grid latestGrid(WindowState window, GridKind kind, long now) {
        return window.grids.values().stream()
                .filter(grid -> grid.kind() == kind)
                .filter(grid -> now - grid.observedAt() <= NODE_MAX_AGE_MILLIS)
                .max(Comparator.comparingLong(Grid::observedAt))
                .orElse(null);
    }

    private static void purge(WindowState window, long now) {
        window.nodes.values().removeIf(node -> now - node.observedAt() > NODE_MAX_AGE_MILLIS);
        window.grids.values().removeIf(grid -> now - grid.observedAt() > NODE_MAX_AGE_MILLIS);
    }

    private static boolean isLauncherNode(AccessibilityNodeInfo node) {
        return "com.miui.home".contentEquals(node.getPackageName());
    }

    private static boolean isIconNode(AccessibilityNodeInfo node) {
        String resourceId = node.getViewIdResourceName();
        return ICON_RESOURCE_ID.equals(resourceId) || FOLDER_RESOURCE_ID.equals(resourceId);
    }

    private long sourceNodeId(AccessibilityNodeInfo node) throws ReflectiveOperationException {
        return (long) getSourceNodeId.invoke(node);
    }

    private static Rect bounds(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return bounds;
    }

    private static String label(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        return text != null && !text.toString().isBlank()
                ? text.toString() : description == null ? "" : description.toString();
    }

    private static final class WindowState {
        final Map<Long, Grid> grids = new LinkedHashMap<>();
        final Map<Long, RawNode> nodes = new LinkedHashMap<>();
        long desktopScreen = HyperOs4Compatibility.NO_SCREEN;
    }

    private record Grid(
            long nodeId,
            GridKind kind,
            Rect bounds,
            int columns,
            int rows,
            long observedAt
    ) {
        Grid {
            bounds = new Rect(bounds);
        }
    }

    private record ObservedIcon(long nodeId, Rect bounds) {
        ObservedIcon {
            bounds = new Rect(bounds);
        }
    }

    private record RawNode(
            long nodeId,
            String uniqueId,
            Rect bounds,
            String label,
            int cellX,
            int cellY,
            GridKind kind,
            long observedAt
    ) {
        RawNode {
            bounds = new Rect(bounds);
        }
    }
}
