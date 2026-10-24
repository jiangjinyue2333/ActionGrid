package io.github.jiangjinyue2333.actiongrid.hook;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Rect;
import android.os.UserHandle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewGroup;
import android.widget.GridView;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class MiuiHomeBridge {
    private static final long PLACEMENT_CONFIRM_TIMEOUT_MILLIS = 3_000L;
    private static final long PLACEMENT_CONFIRM_INTERVAL_MILLIS = 150L;

    private record NativeDrag(
            long downTime,
            Object dragObject,
            Object dragView,
            float touchX,
            float touchY,
            boolean syntheticTouch) {
    }

    private record ModernPlacement(View dropTarget, Object folderAnchorItem) {
    }

    private record NativeDropAccess(Object controller, Method autoDrop, Field autoDragged) {
    }

    private final BridgeFamily family;
    private final Class<?> launcherClass;
    private final Class<?> shortcutIconClass;
    private final Class<?> folderIconClass;
    private final Class<?> shortcutInfoClass;
    private final Class<?> folderInfoClass;
    private final Class<?> itemInfoClass;
    private final Class<?> multiSelectClass;
    private final Class<?> cellLayoutClass;
    private final Class<?> hotseatClass;
    private final Class<?> hotseatScreenContentClass;
    private final Class<?> hotseatListContentClass;
    private final Class<?> uninstallControllerClass;
    private final Method launcherLongClick;
    private final Method monitorGet;
    private final Method monitorContains;
    private final Method monitorCount;
    private final Method monitorItems;
    private final Method shortcutComponent;
    private final Method itemUser;
    private final Method launcherGetWorkspace;
    private final Method workspaceIsIdInCurrentScreen;
    private final Method workspaceSetCurrentScreenById;
    private final Method cellLayoutGetScreenId;
    private final Method cellLayoutMoveDescription;
    private final Method hotseatScreenMoveDescription;
    private final Method hotseatGetList;
    private final Method hotseatMaxCount;
    private final Method uninstallValid;
    private final Method deleteValid;
    private final Method uninstallShow;
    private final Method deleteItems;
    private final Field horizontalCellCountField;
    private final Field verticalCellCountField;
    private final Field occupiedCellsField;
    private final Field cellPaddingLeftField;
    private final Field cellPaddingTopField;
    private final Field cellWidthField;
    private final Field cellHeightField;
    private final Field widthGapField;
    private final Field heightGapField;
    private final Field hotseatScreenOwnerField;
    private final Field hotseatListOwnerField;
    private final Field idField;
    private final Field containerField;
    private final Field screenField;
    private final Field cellXField;
    private final Field cellYField;
    private final Field itemTypeField;
    private final Field spanXField;
    private final Field spanYField;

    private MiuiHomeBridge(ClassLoader loader, BridgeFamily family) throws ReflectiveOperationException {
        this.family = family;
        boolean modern = family == BridgeFamily.MODERN_7X;
        launcherClass = Class.forName(modern
                ? "com.miui.home.launcher.BaseLauncher"
                : "com.miui.home.launcher.Launcher", false, loader);
        shortcutIconClass = Class.forName("com.miui.home.launcher.ShortcutIcon", false, loader);
        folderIconClass = Class.forName(modern
                ? "com.miui.home.folder.FolderIcon"
                : "com.miui.home.launcher.FolderIcon", false, loader);
        shortcutInfoClass = Class.forName("com.miui.home.launcher.ShortcutInfo", false, loader);
        folderInfoClass = Class.forName(modern
                ? "com.miui.home.folder.FolderInfo"
                : "com.miui.home.launcher.FolderInfo", false, loader);
        itemInfoClass = Class.forName(modern
                ? "com.miui.home.model.api.ItemInfo"
                : "com.miui.home.launcher.ItemInfo", false, loader);
        Class<?> workspaceClass = findOptionalClass(
                "com.miui.home.launcher.Workspace", loader);
        Class<?> deviceConfigClass = findOptionalClass(modern
                ? "com.miui.home.common.device.DeviceConfigs"
                : "com.miui.home.launcher.DeviceConfig", loader);
        multiSelectClass = Class.forName(
                "com.miui.home.launcher.multiselect.MultiSelectMonitor", false, loader);
        cellLayoutClass = Class.forName("com.miui.home.launcher.CellLayout", false, loader);
        hotseatClass = Class.forName(
                "com.miui.home.launcher.hotseats.HotSeats", false, loader);
        hotseatScreenContentClass = Class.forName(
                "com.miui.home.launcher.hotseats.HotSeatsScreenViewContent", false, loader);
        hotseatListContentClass = Class.forName(
                "com.miui.home.launcher.hotseats.HotSeatsListContent", false, loader);
        uninstallControllerClass = Class.forName(
                "com.miui.home.launcher.uninstall.UninstallController", false, loader);

        launcherLongClick = launcherClass.getMethod("onLongClick", View.class);
        monitorGet = multiSelectClass.getMethod("getMonitor");
        monitorContains = multiSelectClass.getMethod("contains", Object.class);
        monitorCount = multiSelectClass.getMethod("getSelectCount");
        monitorItems = multiSelectClass.getMethod("getCheckedShortcutInfos");
        shortcutComponent = shortcutInfoClass.getMethod("getComponentName");
        itemUser = itemInfoClass.getMethod("getUser");
        launcherGetWorkspace = findOptionalMethod(launcherClass, "getWorkspace");
        workspaceIsIdInCurrentScreen = findOptionalMethod(
                workspaceClass, "isIdInCurrentScreen", long.class);
        workspaceSetCurrentScreenById = findOptionalMethod(
                workspaceClass, "setCurrentScreenById", long.class);
        cellLayoutGetScreenId = cellLayoutClass.getMethod("getScreenId");
        cellLayoutMoveDescription = cellLayoutClass.getMethod(
                "getItemMoveDescription", int.class, int.class);
        hotseatScreenMoveDescription = hotseatScreenContentClass.getMethod(
                "getItemMoveDescription", int.class);
        hotseatGetList = hotseatClass.getMethod("getHotSeatsList");
        hotseatMaxCount = findOptionalMethod(deviceConfigClass, "getHotseatMaxCount");
        uninstallValid = uninstallControllerClass.getMethod("isUninstallValid", itemInfoClass, launcherClass);
        deleteValid = uninstallControllerClass.getMethod("isDeleteValid", itemInfoClass, launcherClass);
        uninstallShow = modern
                ? uninstallControllerClass.getMethod("showDialog", launcherClass, List.class, String.class)
                : uninstallControllerClass.getMethod("showDialog", launcherClass, List.class);
        deleteItems = modern
                ? uninstallControllerClass.getMethod("deleteWithBoomAnim", launcherClass, List.class,
                boolean.class, String.class)
                : uninstallControllerClass.getMethod("deleteWithBoomAnim", launcherClass, List.class);

        horizontalCellCountField = findField(cellLayoutClass, "mHCells");
        verticalCellCountField = findField(cellLayoutClass, "mVCells");
        occupiedCellsField = findField(cellLayoutClass, "mOccupiedCell");
        cellPaddingLeftField = findOptionalField(cellLayoutClass, "mCellPaddingLeft");
        cellPaddingTopField = findOptionalField(cellLayoutClass, "mCellPaddingTop");
        cellWidthField = findOptionalField(cellLayoutClass, "mCellWidth");
        cellHeightField = findOptionalField(cellLayoutClass, "mCellHeight");
        widthGapField = findOptionalField(cellLayoutClass, "mWidthGap");
        heightGapField = findOptionalField(cellLayoutClass, "mHeightGap");
        hotseatScreenOwnerField = findField(hotseatScreenContentClass, "mHotSeats");
        hotseatListOwnerField = findField(hotseatListContentClass, "mHotSeats");

        idField = itemInfoClass.getField("id");
        containerField = itemInfoClass.getField("container");
        screenField = itemInfoClass.getField("screenId");
        cellXField = itemInfoClass.getField("cellX");
        cellYField = itemInfoClass.getField("cellY");
        itemTypeField = itemInfoClass.getField("itemType");
        spanXField = itemInfoClass.getField("spanX");
        spanYField = itemInfoClass.getField("spanY");
    }

    static MiuiHomeBridge resolve(ClassLoader loader, BridgeFamily family)
            throws ReflectiveOperationException {
        if (family == BridgeFamily.UNSUPPORTED) {
            throw new ClassNotFoundException("Unsupported Xiaomi Home family");
        }
        return new MiuiHomeBridge(loader, family);
    }

    BridgeFamily family() {
        return family;
    }

    List<Class<?>> placementHostClasses() {
        return List.of(cellLayoutClass, hotseatScreenContentClass, hotseatListContentClass);
    }

    boolean isSupportedIcon(View view) {
        return shortcutIconClass.isInstance(view) || folderIconClass.isInstance(view);
    }

    boolean isPlacementHost(View view) {
        return cellLayoutClass.isInstance(view)
                || hotseatScreenContentClass.isInstance(view)
                || hotseatListContentClass.isInstance(view);
    }

    TargetKind targetKind(View view) {
        Object tag = view.getTag();
        if (shortcutIconClass.isInstance(view) && shortcutInfoClass.isInstance(tag)) {
            return TargetKind.SHORTCUT;
        }
        if (folderIconClass.isInstance(view) && folderInfoClass.isInstance(tag)) {
            return TargetKind.FOLDER;
        }
        return TargetKind.UNSUPPORTED;
    }

    StableItemRef describe(View view) {
        return describeItem(view.getTag());
    }

    LiftSession captureLift(View anchor) throws ReflectiveOperationException {
        Object tag = anchor.getTag();
        StableItemRef anchorRef = describeItem(tag);
        if (anchorRef == null || !anchorRef.isSupportedLiftSource()) {
            return null;
        }
        if (folderInfoClass.isInstance(tag)) {
            return anchorRef.isSupportedFolder()
                    ? new LiftSession(List.of(tag), List.of(anchorRef), anchor, false) : null;
        }
        if (!shortcutInfoClass.isInstance(tag) || !anchorRef.isSupportedShortcut()) {
            return null;
        }
        Object monitor = monitorGet.invoke(null);
        boolean selected = (boolean) monitorContains.invoke(monitor, tag);
        int selectedCount = (int) monitorCount.invoke(monitor);
        if (!selected || selectedCount <= 1) {
            return new LiftSession(List.of(tag), List.of(anchorRef), anchor, false);
        }
        Object selectedArray = monitorItems.invoke(monitor);
        int length = selectedArray == null ? 0 : Array.getLength(selectedArray);
        if (length != selectedCount) {
            return null;
        }
        List<Object> items = new ArrayList<>(length);
        List<StableItemRef> refs = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            Object item = Array.get(selectedArray, index);
            StableItemRef ref = describeItem(item);
            if (!shortcutInfoClass.isInstance(item) || ref == null || !ref.isSupportedShortcut()) {
                return null;
            }
            items.add(item);
            refs.add(ref);
        }
        refs.sort(Comparator.naturalOrder());
        List<Object> sortedItems = new ArrayList<>(items.size());
        for (StableItemRef ref : refs) {
            for (Object item : items) {
                StableItemRef candidate = describeItem(item);
                if (candidate != null && candidate.id() == ref.id()) {
                    sortedItems.add(item);
                    break;
                }
            }
        }
        return new LiftSession(sortedItems, refs, anchor, true);
    }

    BoundActions loadBasicActions(View view) {
        StableItemRef ref = describe(view);
        if (ref == null || targetKind(view) != TargetKind.SHORTCUT) {
            return null;
        }
        AppTarget target = appTarget(view.getTag());
        boolean uninstall = false;
        boolean remove = false;
        try {
            Object launcher = findLauncher(view);
            boolean deleteAllowed = launcher != null
                    && (boolean) deleteValid.invoke(null, view.getTag(), launcher);
            uninstall = ref.itemType() == 0 && launcher != null
                    && (boolean) uninstallValid.invoke(null, view.getTag(), launcher);
            remove = deleteAllowed && (ref.itemType() != 0 || !uninstall);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // App info and Shortcut actions remain available even if Xiaomi's delete policy changes.
        }
        return new BoundActions(ref.id(), target, uninstall, remove, target == null, List.of());
    }

    List<DynamicAction> loadDynamicActions(Context context, AppTarget target) {
        if (target == null) {
            return List.of();
        }
        try {
            LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
            if (launcherApps == null) {
                return List.of();
            }
            List<ShortcutInfo> shortcuts = mergeShortcutResults(
                    queryShortcuts(launcherApps, target, true),
                    queryShortcuts(launcherApps, target, false));
            if (shortcuts.isEmpty()) {
                return List.of();
            }
            shortcuts.sort(Comparator
                    .comparing((ShortcutInfo shortcut) -> !shortcut.isDeclaredInManifest())
                    .thenComparingInt(ShortcutInfo::getRank));
            List<DynamicAction> actions = new ArrayList<>(shortcuts.size());
            for (ShortcutInfo shortcut : shortcuts) {
                actions.add(new DynamicAction(shortcut.getShortLabel().toString(),
                        shortcut.getPackage(), shortcut.getId(), target.user()));
            }
            return actions;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private List<ShortcutInfo> mergeShortcutResults(
            List<ShortcutInfo> activityScoped, List<ShortcutInfo> packageScoped) {
        Map<String, ShortcutInfo> merged = new LinkedHashMap<>();
        appendUsableShortcuts(merged, activityScoped);
        appendUsableShortcuts(merged, packageScoped);
        return new ArrayList<>(merged.values());
    }

    private void appendUsableShortcuts(
            Map<String, ShortcutInfo> merged, List<ShortcutInfo> shortcuts) {
        for (ShortcutInfo shortcut : shortcuts) {
            if (!shortcut.isEnabled()
                    || shortcut.getShortLabel() == null
                    || shortcut.getShortLabel().length() == 0) {
                continue;
            }
            String key = shortcut.getPackage() + "\n" + shortcut.getId();
            merged.putIfAbsent(key, shortcut);
        }
    }

    private List<ShortcutInfo> queryShortcuts(
            LauncherApps launcherApps, AppTarget target, boolean activityScoped) {
        try {
            LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery()
                    .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                    .setPackage(target.component().getPackageName());
            if (activityScoped) {
                query.setActivity(target.component());
            }
            List<ShortcutInfo> shortcuts = launcherApps.getShortcuts(query, target.user());
            return shortcuts == null ? List.of() : shortcuts;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private AppTarget appTarget(Object item) {
        try {
            ComponentName component = (ComponentName) shortcutComponent.invoke(item);
            UserHandle user = (UserHandle) itemUser.invoke(item);
            return component == null || user == null ? null : new AppTarget(component, user);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    boolean startAppInfo(View view, AppTarget target) {
        if (target == null) {
            return false;
        }
        try {
            LauncherApps launcherApps = view.getContext().getSystemService(LauncherApps.class);
            if (launcherApps == null) {
                return false;
            }
            launcherApps.startAppDetailsActivity(target.component(), target.user(), null, null);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    boolean startDynamicAction(View view, DynamicAction action) {
        if (action == null) {
            return false;
        }
        try {
            LauncherApps launcherApps = view.getContext().getSystemService(LauncherApps.class);
            if (launcherApps == null) {
                return false;
            }
            launcherApps.startShortcut(action.packageName(), action.shortcutId(),
                    null, null, action.user());
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    boolean uninstall(View view) {
        try {
            Object launcher = findLauncher(view);
            if (launcher == null || !(boolean) uninstallValid.invoke(null, view.getTag(), launcher)) {
                return false;
            }
            List<Object> item = List.of(view.getTag());
            if (family == BridgeFamily.MODERN_7X) {
                uninstallShow.invoke(null, launcher, item, "桌面_无障碍");
            } else {
                uninstallShow.invoke(null, launcher, item);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    boolean remove(View view) {
        try {
            Object launcher = findLauncher(view);
            if (launcher == null || !(boolean) deleteValid.invoke(null, view.getTag(), launcher)) {
                return false;
            }
            List<Object> item = List.of(view.getTag());
            if (family == BridgeFamily.MODERN_7X) {
                deleteItems.invoke(null, launcher, item, true, "桌面_无障碍");
            } else {
                deleteItems.invoke(null, launcher, item);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    boolean editFolder(View view) {
        Object launcher = findLauncher(view);
        Object folder = view.getTag();
        if (launcher == null || !folderInfoClass.isInstance(folder)) {
            return false;
        }
        try {
            ClassLoader loader = launcherClass.getClassLoader();
            if (family == BridgeFamily.MODERN_7X) {
                Class<?> menuClass = Class.forName(
                        "com.miui.home.launcher.shortcuts.SystemShortcutMenuItem", false, loader);
                Method showFolderSheet = findStaticMethod(
                        menuClass, "showFolderSheet", launcher, folder);
                if (showFolderSheet == null) {
                    return false;
                }
                showFolderSheet.invoke(null, launcher, folder);
                return true;
            }
            Class<?> sheetClass = Class.forName(
                    "com.miui.home.launcher.folder.FolderSheet", false, loader);
            Method show = findStaticMethod(sheetClass, "show", launcher, folder);
            if (show == null) {
                return false;
            }
            show.invoke(null, launcher, folder);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    List<PositionTarget> emptyPositions(View host, LiftSession activeSession) {
        if (activeSession == null || activeSession.multiSelect()) {
            return List.of();
        }
        if (cellLayoutClass.isInstance(host)) {
            return emptyDesktopPositions(host);
        }
        if (hotseatScreenContentClass.isInstance(host)
                || hotseatListContentClass.isInstance(host)) {
            return emptyHotseatPosition(host, activeSession);
        }
        return List.of();
    }

    private List<PositionTarget> emptyDesktopPositions(View host) {
        try {
            int columns = horizontalCellCountField.getInt(host);
            int rows = verticalCellCountField.getInt(host);
            View[][] occupied = (View[][]) occupiedCellsField.get(host);
            long screenId = ((Number) cellLayoutGetScreenId.invoke(host)).longValue();
            if (columns <= 0 || rows <= 0
                    || host.getWidth() <= 0 || host.getHeight() <= 0) {
                return List.of();
            }
            List<PositionTarget> positions = new ArrayList<>(columns * rows);
            for (VirtualGridLayout.Cell cell : VirtualGridLayout.cells(columns, rows)) {
                int column = cell.column();
                int row = cell.row();
                View occupiedView = occupied == null || column >= occupied.length
                        || occupied[column] == null || row >= occupied[column].length
                        ? null : occupied[column][row];
                VirtualGridLayout.Geometry geometry = desktopGeometry(
                        host, column, row, columns, rows);
                String emptyDescription;
                try {
                    emptyDescription = (String) cellLayoutMoveDescription.invoke(host, column, row);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    emptyDescription = ItemLocationLabel.emptyDesktop(column, row);
                }
                String description = VirtualGridLayout.description(
                        occupiedView == null ? null : label(occupiedView),
                        emptyDescription, column, row);
                VirtualGridLayout.Bounds bounds = geometry.bounds();
                positions.add(new PositionTarget(cell.virtualId(), host,
                        StableItemRef.CONTAINER_DESKTOP, screenId, column, row,
                        new Rect(bounds.left(), bounds.top(), bounds.right(), bounds.bottom()),
                        geometry.centerX(), geometry.centerY(), occupiedView, description));
            }
            return positions;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return List.of();
        }
    }

    private List<PositionTarget> emptyHotseatPosition(View host, LiftSession activeSession) {
        try {
            if (activeSession.sourceRefs().get(0).container()
                    == StableItemRef.CONTAINER_HOTSEAT || hotseatMaxCount == null) {
                return List.of();
            }
            Object hotseat = hotseatOwner(host);
            if (hotseat == null) {
                return List.of();
            }
            List<?> items = (List<?>) hotseatGetList.invoke(hotseat);
            int capacity = ((Number) hotseatMaxCount.invoke(null)).intValue();
            if (items == null || capacity <= items.size()
                    || host.getWidth() <= 0 || host.getHeight() <= 0) {
                return List.of();
            }
            int position = items.size();
            int insertionColumns = position + 1;
            int column = host.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                    ? 0 : position;
            Rect bounds = gridBounds(host, column, 0, insertionColumns, 1);
            String description = ItemLocationLabel.emptyHotseat(position);
            if (hotseatScreenContentClass.isInstance(host)) {
                try {
                    description = (String) hotseatScreenMoveDescription.invoke(host, position);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Keep the exact fallback text extracted from both supported Xiaomi versions.
                }
            }
            return List.of(new PositionTarget(10_001, host,
                    StableItemRef.CONTAINER_HOTSEAT, 0, position, 0,
                    bounds, Math.round(bounds.exactCenterX()), Math.round(bounds.exactCenterY()),
                    null, description));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return List.of();
        }
    }

    private Object hotseatOwner(View host) {
        ViewParent parent = host.getParent();
        while (parent != null) {
            if (hotseatClass.isInstance(parent)) {
                return parent;
            }
            parent = parent.getParent();
        }
        try {
            return hotseatScreenContentClass.isInstance(host)
                    ? hotseatScreenOwnerField.get(host) : hotseatListOwnerField.get(host);
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    private static Rect gridBounds(
            View host, int column, int row, int columns, int rows) {
        int contentLeft = host.getPaddingLeft();
        int contentTop = host.getPaddingTop();
        int contentRight = Math.max(contentLeft + 1,
                host.getWidth() - host.getPaddingRight());
        int contentBottom = Math.max(contentTop + 1,
                host.getHeight() - host.getPaddingBottom());
        int left = contentLeft + (int) ((long) (contentRight - contentLeft) * column / columns);
        int top = contentTop + (int) ((long) (contentBottom - contentTop) * row / rows);
        int right = contentLeft
                + (int) ((long) (contentRight - contentLeft) * (column + 1) / columns);
        int bottom = contentTop
                + (int) ((long) (contentBottom - contentTop) * (row + 1) / rows);
        return new Rect(left, top, right, bottom);
    }

    private VirtualGridLayout.Geometry desktopGeometry(
            View host, int column, int row, int columns, int rows) {
        try {
            if (cellPaddingLeftField != null && cellPaddingTopField != null
                    && cellWidthField != null && cellHeightField != null
                    && widthGapField != null && heightGapField != null) {
                return VirtualGridLayout.geometry(column, row, columns, rows,
                        host.getWidth(), host.getHeight(),
                        cellPaddingLeftField.getInt(host), cellPaddingTopField.getInt(host),
                        cellWidthField.getInt(host), cellHeightField.getInt(host),
                        widthGapField.getInt(host), heightGapField.getInt(host));
            }
        } catch (IllegalAccessException | RuntimeException ignored) {
            // Fall through to a uniform grid on neighboring launcher versions.
        }
        int width = Math.max(1, host.getWidth());
        int height = Math.max(1, host.getHeight());
        return VirtualGridLayout.geometry(column, row, columns, rows,
                width, height, 0, 0,
                Math.max(1, width / columns), Math.max(1, height / rows), 0, 0);
    }

    void performPlacement(LiftSession session, View target, PlacementAction action,
                          PlacementCallback callback) {
        View anchor = session.anchor();
        StableItemRef targetRef = describe(target);
        Object targetItem = target.getTag();
        if (anchor == null || targetRef == null || !sessionStillValid(session)
                || !PlacementPolicy.actionsFor(session.sourceIds(),
                session.sourceRefs().stream().allMatch(StableItemRef::isSupportedShortcut),
                targetRef, targetKind(target))
                .contains(action)) {
            callback.onComplete(false, "目标位置已变化，请重新选择位置");
            return;
        }
        Object launcherObject = findLauncher(target);
        if (!(launcherObject instanceof Activity launcher)) {
            callback.onComplete(false, "当前桌面版本不支持此操作");
            return;
        }
        ModernPlacement modernPlacement = prepareModernPlacement(target, action, session);
        boolean appendToFolder = action == PlacementAction.MOVE_AFTER
                && folderNeedsAppend(target, session);
        startNativeDrag(launcher, session, anchor, targetRef.container(), targetRef.screenId(), target,
                drag -> {
            if (performModernPlacement(launcher, drag, modernPlacement, session,
                    targetItem, action, callback)) {
                return;
            }
            performPointerPlacement(launcher, drag, session, target, targetItem,
                    action, appendToFolder, callback);
        }, callback);
    }

    void performPlacementAt(LiftSession session, PositionTarget target,
                            PlacementCallback callback) {
        View anchor = session.anchor();
        View host = target.host();
        if (anchor == null || !sessionStillValid(session) || !positionStillAvailable(target)) {
            callback.onComplete(false, "目标位置已变化，请重新选择位置");
            return;
        }
        Object launcherObject = findLauncher(host);
        if (!(launcherObject instanceof Activity launcher)) {
            callback.onComplete(false, "当前桌面版本不支持此操作");
            return;
        }
        startNativeDrag(launcher, session, anchor, target.container(), target.screenId(), host,
                drag -> {
            float[] targetPoint = positionPointInWindow(target, launcher);
            dispatch(launcher, drag.downTime(), SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_MOVE, targetPoint);
            host.postDelayed(() -> finishPositionPlacement(
                    launcher, drag, targetPoint, session, target, callback),
                    family == BridgeFamily.MODERN_7X ? 650L : 350L);
        }, callback);
    }

    private ModernPlacement prepareModernPlacement(
            View target, PlacementAction action, LiftSession session) {
        if (family != BridgeFamily.MODERN_7X) {
            return null;
        }
        if (action == PlacementAction.CREATE_FOLDER) {
            return shortcutIconClass.isInstance(target)
                    ? new ModernPlacement(target, null) : null;
        }
        ViewGroup grid = findFolderGrid(target);
        if (grid == null) {
            return null;
        }
        int targetIndex = folderPosition(grid, target);
        int itemCount = folderItemCount(grid);
        if (targetIndex < 0 || itemCount <= 0) {
            return null;
        }
        int anchorIndex = FolderPlacementTarget.anchorIndex(
                action, targetIndex, itemCount, folderSourceIndex(grid, session));
        Object anchorItem = anchorIndex >= itemCount
                ? null : folderItemAtPosition(grid, anchorIndex);
        return anchorIndex < itemCount && anchorItem == null
                ? null : new ModernPlacement(grid, anchorItem);
    }

    private boolean performModernPlacement(
            Activity launcher, NativeDrag drag, ModernPlacement placement,
            LiftSession session, Object targetItem, PlacementAction action,
            PlacementCallback callback) {
        if (family != BridgeFamily.MODERN_7X || placement == null
                || drag.dragObject() == null) {
            return false;
        }
        View dropTarget = placement.dropTarget();
        NativeDropAccess access = nativeDropAccess(launcher, dropTarget);
        Method acceptDrop = findInstanceMethod(
                dropTarget.getClass(), "acceptDrop", drag.dragObject());
        Method onDragEnter = findInstanceMethod(
                dropTarget.getClass(), "onDragEnter", drag.dragObject());
        Method onDragExit = findInstanceMethod(
                dropTarget.getClass(), "onDragExit", drag.dragObject());
        Field lastHit = null;
        Method reorderItems = null;
        if (action != PlacementAction.CREATE_FOLDER) {
            lastHit = findOptionalField(dropTarget.getClass(), "mLastHit");
            reorderItems = findOptionalDeclaredMethod(dropTarget.getClass(), "reorderItems");
        }
        if (access == null || acceptDrop == null || onDragEnter == null || onDragExit == null
                || (action != PlacementAction.CREATE_FOLDER
                && (lastHit == null || reorderItems == null))) {
            return false;
        }
        try {
            if (!Boolean.TRUE.equals(acceptDrop.invoke(dropTarget, drag.dragObject()))) {
                return false;
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }

        boolean entered = false;
        try {
            entered = true;
            onDragEnter.invoke(dropTarget, drag.dragObject());
            if (action != PlacementAction.CREATE_FOLDER) {
                View anchor = folderAnchorView(
                        (ViewGroup) dropTarget, placement.folderAnchorItem());
                lastHit.set(dropTarget, anchor);
                reorderItems.invoke(dropTarget);
            }
            access.autoDragged().setBoolean(access.controller(), true);
            access.autoDrop().invoke(access.controller(), dropTarget);
            cancelSyntheticTouch(launcher, drag);
            View confirmationHost = launcher.getWindow().getDecorView();
            startPlacementConfirmation(
                    launcher, drag, session, confirmationHost, targetItem, action, callback);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            try {
                access.autoDragged().setBoolean(access.controller(), false);
                if (entered) {
                    onDragExit.invoke(dropTarget, drag.dragObject());
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // cancelNativeDrag below is the final recovery path.
            }
            cancelSyntheticTouch(launcher, drag);
            failPlacement(launcher, callback);
            return true;
        }
    }

    private void cancelSyntheticTouch(Activity launcher, NativeDrag drag) {
        if (!drag.syntheticTouch()) {
            return;
        }
        try {
            dispatch(launcher, drag.downTime(), SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_CANCEL, new float[]{drag.touchX(), drag.touchY()});
        } catch (RuntimeException ignored) {
            // The native drop already completed; touch cleanup must not turn it into a failure.
        }
    }

    private void performPointerPlacement(
            Activity launcher, NativeDrag drag, LiftSession session, View target,
            Object targetItem, PlacementAction action, boolean appendToFolder,
            PlacementCallback callback) {
        float[] targetPoint = targetPointInWindow(target, launcher, action, session);
        View confirmationHost = launcher.getWindow().getDecorView();
        dispatch(launcher, drag.downTime(), SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE, targetPoint);
        if (appendToFolder) {
            confirmationHost.postDelayed(() -> {
                reorderFolderToEnd(target);
                confirmationHost.postDelayed(() -> finishPlacement(
                        launcher, drag, targetPoint, session, targetItem,
                        action, callback),
                        180L);
            }, family == BridgeFamily.MODERN_7X ? 380L : 220L);
        } else {
            confirmationHost.postDelayed(() -> finishPlacement(
                    launcher, drag, targetPoint, session, targetItem,
                    action, callback),
                    family == BridgeFamily.MODERN_7X ? 650L : 350L);
        }
    }

    private void startNativeDrag(
            Activity launcher,
            LiftSession session,
            View anchor,
            long targetContainer,
            long targetScreenId,
            View targetHost,
            Consumer<NativeDrag> onDragStarted,
            PlacementCallback callback) {
        try {
            StableItemRef source = describe(anchor);
            if (source == null || !session.sourceIds().contains(source.id())
                    || !session.sourceItems().contains(anchor.getTag())) {
                failPlacement(launcher, callback);
                return;
            }
            FolderDragTransfer folderTransfer = family == BridgeFamily.MODERN_7X
                    && source.container() > 0 && source.container() != targetContainer
                    && session.sourceItems().size() == 1
                    ? FolderDragTransfer.prepare(launcher, anchor.getTag(), source.container())
                    : null;
            boolean sourceCanChangePage = source != null
                    && source.container() == StableItemRef.CONTAINER_DESKTOP
                    && (targetContainer == StableItemRef.CONTAINER_DESKTOP
                    || targetContainer == StableItemRef.CONTAINER_HOTSEAT);
            boolean canSwitchPages = launcherGetWorkspace != null
                    && workspaceIsIdInCurrentScreen != null
                    && workspaceSetCurrentScreenById != null;
            boolean needsWorkspaceForModernFolderDrag = family == BridgeFamily.MODERN_7X
                    && targetContainer > 0 && session.sourceItems().size() == 1
                    && source != null
                    && source.container() == StableItemRef.CONTAINER_DESKTOP;
            Object workspace = launcherGetWorkspace != null
                    && (needsWorkspaceForModernFolderDrag
                    || (canSwitchPages && (sourceCanChangePage
                    || targetContainer == StableItemRef.CONTAINER_DESKTOP)))
                    ? launcherGetWorkspace.invoke(launcher) : null;
            boolean showSourceFirst = workspace != null && sourceCanChangePage
                    && !(boolean) workspaceIsIdInCurrentScreen.invoke(
                    workspace, source.screenId());
            if (showSourceFirst) {
                workspaceSetCurrentScreenById.invoke(workspace, source.screenId());
            }
            Runnable start = () -> {
                try {
                    long downTime = SystemClock.uptimeMillis();
                    float[] sourcePoint = centerInWindow(anchor, launcher);
                    boolean modernFolderDrag = startModernFolderDrag(
                            launcher, session, anchor, source, targetContainer, workspace);
                    if (!modernFolderDrag) {
                        dispatch(launcher, downTime, downTime,
                                MotionEvent.ACTION_DOWN, sourcePoint);
                        launcherLongClick.invoke(launcher, anchor);
                    }
                    if (folderTransfer != null) {
                        NativeDrag folderDrag = currentNativeDrag(
                                launcher, downTime, sourcePoint, true);
                        try {
                            folderTransfer.onDragStarted(folderDrag.dragObject());
                        } catch (ReflectiveOperationException | RuntimeException exception) {
                            cancelSyntheticTouch(launcher, folderDrag);
                            throw exception;
                        }
                    }
                    boolean showTarget = workspace != null
                            && targetContainer == StableItemRef.CONTAINER_DESKTOP
                            && !(boolean) workspaceIsIdInCurrentScreen.invoke(
                            workspace, targetScreenId);
                    if (showTarget) {
                        workspaceSetCurrentScreenById.invoke(workspace, targetScreenId);
                    }
                    Runnable continueDrag = () -> {
                        try {
                            onDragStarted.accept(currentNativeDrag(
                                    launcher, downTime, sourcePoint, !modernFolderDrag));
                        } catch (RuntimeException exception) {
                            failPlacement(launcher, callback);
                        }
                    };
                    if (showSourceFirst || showTarget) {
                        targetHost.postOnAnimation(continueDrag);
                    } else {
                        continueDrag.run();
                    }
                } catch (ReflectiveOperationException | RuntimeException exception) {
                    failPlacement(launcher, callback);
                }
            };
            if (showSourceFirst) {
                anchor.postOnAnimation(start);
            } else {
                start.run();
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            failPlacement(launcher, callback);
        }
    }

    private boolean startModernFolderDrag(
            Activity launcher,
            LiftSession session,
            View anchor,
            StableItemRef source,
            long targetContainer,
            Object workspace) throws ReflectiveOperationException {
        if (family != BridgeFamily.MODERN_7X || targetContainer <= 0
                || session.sourceItems().size() != 1 || source == null
                || source.container() != StableItemRef.CONTAINER_DESKTOP
                || workspace == null) {
            return false;
        }
        Field controllerField = findField(launcherClass, "mDragController");
        Object controller = controllerField.get(launcher);
        Class<?> dragSourceClass = Class.forName(
                "com.miui.home.dragdrop.DragSource", false, launcher.getClassLoader());
        Object dragSources = Array.newInstance(dragSourceClass, 1);
        Array.set(dragSources, 0, workspace);
        Method autoDrag = findInstanceMethod(controller.getClass(), "autoDrag",
                new View[]{anchor}, dragSources, 0, 1, 0);
        Method onDragStarted = findInstanceMethod(
                workspace.getClass(), "onDragStarted", anchor);
        if (autoDrag == null || onDragStarted == null) {
            return false;
        }
        autoDrag.invoke(controller, new View[]{anchor}, dragSources, 0, 1, 0);
        onDragStarted.invoke(workspace, anchor);
        return true;
    }

    private void failPlacement(Activity launcher, PlacementCallback callback) {
        cancelNativeDrag(launcher);
        callback.onComplete(false, "未能移动");
    }

    private void finishPositionPlacement(Activity launcher, NativeDrag drag, float[] targetPoint,
                                         LiftSession session, PositionTarget target,
                                         PlacementCallback callback) {
        dispatch(launcher, drag.downTime(), SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP, targetPoint);
        long deadline = SystemClock.uptimeMillis() + PLACEMENT_CONFIRM_TIMEOUT_MILLIS;
        target.host().postDelayed(() -> confirmPositionPlacement(
                launcher, drag, session, target, callback, deadline),
                PLACEMENT_CONFIRM_INTERVAL_MILLIS);
    }

    private void confirmPositionPlacement(
            Activity launcher, NativeDrag drag, LiftSession session, PositionTarget target,
            PlacementCallback callback, long deadline) {
        if (positionPlacementMatches(session, target)
                || nativeDropSucceeded(drag, session)) {
            callback.onComplete(true, null);
            return;
        }
        if (SystemClock.uptimeMillis() < deadline) {
            target.host().postDelayed(() -> confirmPositionPlacement(
                    launcher, drag, session, target, callback, deadline),
                    PLACEMENT_CONFIRM_INTERVAL_MILLIS);
            return;
        }
        cancelNativeDrag(launcher);
        callback.onComplete(false, "未能移动");
    }

    private boolean positionStillAvailable(PositionTarget target) {
        try {
            if (target.container() == StableItemRef.CONTAINER_DESKTOP) {
                if (!cellLayoutClass.isInstance(target.host())
                        || ((Number) cellLayoutGetScreenId.invoke(target.host())).longValue()
                        != target.screenId()) {
                    return false;
                }
                View[][] occupied = (View[][]) occupiedCellsField.get(target.host());
                if (occupied == null) {
                    return true;
                }
                if (target.cellX() < 0 || target.cellX() >= occupied.length
                        || occupied[target.cellX()] == null
                        || target.cellY() < 0
                        || target.cellY() >= occupied[target.cellX()].length) {
                    return false;
                }
                return occupied[target.cellX()][target.cellY()] == null;
            }
            if (target.container() == StableItemRef.CONTAINER_HOTSEAT) {
                if (hotseatMaxCount == null) {
                    return false;
                }
                Object hotseat = hotseatOwner(target.host());
                List<?> items = hotseat == null ? null : (List<?>) hotseatGetList.invoke(hotseat);
                int capacity = ((Number) hotseatMaxCount.invoke(null)).intValue();
                return items != null && items.size() < capacity
                        && target.cellX() == items.size();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
        return false;
    }

    private boolean positionPlacementMatches(LiftSession session, PositionTarget target) {
        if (session.sourceItems().size() != 1) {
            return false;
        }
        StableItemRef observed = itemAtPosition(target);
        return PlacementConfirmation.matchesItemAtPosition(
                session.sourceRefs().get(0).id(), observed,
                target.container(), target.screenId(), target.cellX(), target.cellY());
    }

    private StableItemRef itemAtPosition(PositionTarget target) {
        try {
            if (target.container() == StableItemRef.CONTAINER_DESKTOP) {
                if (!cellLayoutClass.isInstance(target.host())) {
                    return null;
                }
                View[][] occupied = (View[][]) occupiedCellsField.get(target.host());
                if (occupied == null
                        || target.cellX() < 0 || target.cellX() >= occupied.length
                        || target.cellY() < 0 || target.cellY() >= occupied[target.cellX()].length) {
                    return null;
                }
                View view = occupied[target.cellX()][target.cellY()];
                return view == null ? null : describe(view);
            }
            if (target.container() == StableItemRef.CONTAINER_HOTSEAT) {
                Object hotseat = hotseatOwner(target.host());
                List<?> items = hotseat == null ? null : (List<?>) hotseatGetList.invoke(hotseat);
                return items == null || target.cellX() < 0 || target.cellX() >= items.size()
                        ? null : describeItem(items.get(target.cellX()));
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private void finishPlacement(Activity launcher, NativeDrag drag, float[] targetPoint,
                                 LiftSession session, Object targetItem,
                                 PlacementAction action,
                                 PlacementCallback callback) {
        dispatch(launcher, drag.downTime(), SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP, targetPoint);
        startPlacementConfirmation(
                launcher, drag, session, launcher.getWindow().getDecorView(),
                targetItem, action, callback);
    }

    private void startPlacementConfirmation(
            Activity launcher, NativeDrag drag, LiftSession session, View host,
            Object targetItem, PlacementAction action, PlacementCallback callback) {
        long deadline = SystemClock.uptimeMillis() + PLACEMENT_CONFIRM_TIMEOUT_MILLIS;
        host.postDelayed(() -> confirmPlacement(
                launcher, drag, session, host, targetItem, action, callback, deadline),
                PLACEMENT_CONFIRM_INTERVAL_MILLIS);
    }

    private void confirmPlacement(
            Activity launcher, NativeDrag drag, LiftSession session, View host,
            Object targetItem,
            PlacementAction action,
            PlacementCallback callback, long deadline) {
        if (placementMatches(session, targetItem, action)
                || nativeDropSucceeded(drag, session)) {
            callback.onComplete(true, null);
            return;
        }
        if (SystemClock.uptimeMillis() < deadline) {
            host.postDelayed(() -> confirmPlacement(
                    launcher, drag, session, host, targetItem, action, callback, deadline),
                    PLACEMENT_CONFIRM_INTERVAL_MILLIS);
            return;
        }
        cancelNativeDrag(launcher);
        callback.onComplete(false, "未能移动");
    }

    private boolean placementMatches(LiftSession session, Object targetItem, PlacementAction action) {
        StableItemRef target = describeItem(targetItem);
        if (target == null) {
            return false;
        }
        List<Object> sourceItems = session.sourceItems();
        List<StableItemRef> currentSources = sourceItems.stream()
                .map(this::describeItem)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
        if (currentSources.size() != sourceItems.size()) {
            return false;
        }
        if (action == PlacementAction.CREATE_FOLDER) {
            return target.container() > 0
                    && currentSources.stream().allMatch(source -> source.container() == target.container());
        }
        if (currentSources.stream().anyMatch(source -> source.container() != target.container())) {
            return false;
        }
        return action == PlacementAction.MOVE_BEFORE
                ? currentSources.get(currentSources.size() - 1).compareTo(target) < 0
                : currentSources.get(0).compareTo(target) > 0;
    }

    private boolean sessionStillValid(LiftSession session) {
        Set<Long> ids = new HashSet<>();
        for (Object source : session.sourceItems()) {
            StableItemRef ref = describeItem(source);
            if (ref == null || !ref.isSupportedLiftSource()) {
                return false;
            }
            ids.add(ref.id());
        }
        return ids.equals(session.sourceIds());
    }

    private void cancelNativeDrag(Object launcher) {
        try {
            Field controllerField = findField(launcherClass, "mDragController");
            Object controller = controllerField.get(launcher);
            Method cancel = controller.getClass().getMethod("cancelDrag");
            cancel.invoke(controller);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The launcher owns final recovery if the native drag already ended.
        }
    }

    private NativeDropAccess nativeDropAccess(Activity launcher, View dropTarget) {
        try {
            Field controllerField = findField(launcherClass, "mDragController");
            Object controller = controllerField.get(launcher);
            Method autoDrop = controller == null ? null : findInstanceMethod(
                    controller.getClass(), "autoDrop", dropTarget);
            Field autoDragged = controller == null ? null : findOptionalField(
                    controller.getClass(), "mAutoDraged");
            return autoDrop == null || autoDragged == null
                    ? null : new NativeDropAccess(controller, autoDrop, autoDragged);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private Object findLauncher(View view) {
        Context context = view.getContext();
        while (context != null) {
            if (launcherClass.isInstance(context)) {
                return context;
            }
            context = context instanceof ContextWrapper wrapper ? wrapper.getBaseContext() : null;
        }
        return null;
    }

    private NativeDrag currentNativeDrag(
            Activity launcher, long downTime, float[] touchPoint, boolean syntheticTouch) {
        try {
            Field controllerField = findField(launcherClass, "mDragController");
            Object controller = controllerField.get(launcher);
            Method currentDragObject = findOptionalDeclaredMethod(
                    controller.getClass(), "getCurrentDragObject");
            if (currentDragObject == null) {
                return new NativeDrag(downTime, null, null,
                        touchPoint[0], touchPoint[1], syntheticTouch);
            }
            Object dragObject = currentDragObject.invoke(controller);
            if (dragObject == null) {
                return new NativeDrag(downTime, null, null,
                        touchPoint[0], touchPoint[1], syntheticTouch);
            }
            Method getDragView = findOptionalDeclaredMethod(
                    dragObject.getClass(), "getDragView");
            Object dragView = getDragView == null ? null : getDragView.invoke(dragObject);
            return new NativeDrag(downTime, dragObject, dragView,
                    touchPoint[0], touchPoint[1], syntheticTouch);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return new NativeDrag(downTime, null, null,
                    touchPoint[0], touchPoint[1], syntheticTouch);
        }
    }

    private boolean nativeDropSucceeded(NativeDrag drag, LiftSession session) {
        if (drag.dragObject() != null) {
            try {
                Method allSucceeded = findOptionalDeclaredMethod(
                        drag.dragObject().getClass(), "isAllDropedSuccess");
                if (allSucceeded != null
                        && Boolean.TRUE.equals(allSucceeded.invoke(drag.dragObject()))) {
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Fall back to the single drag view below on neighboring launcher versions.
            }
        }
        if (session.sourceItems().size() != 1 || drag.dragView() == null) {
            return false;
        }
        try {
            Method isDropSucceeded = findOptionalDeclaredMethod(
                    drag.dragView().getClass(), "isDropSucceeded");
            return isDropSucceeded != null
                    && Boolean.TRUE.equals(isDropSucceeded.invoke(drag.dragView()));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private ViewGroup findFolderGrid(View target) {
        ViewParent parent = target.getParent();
        while (parent instanceof ViewGroup group) {
            String name = group.getClass().getName();
            if (group instanceof GridView && name.contains("Folder")) {
                return group;
            }
            parent = group.getParent();
        }
        return null;
    }

    private int folderItemCount(ViewGroup grid) {
        return grid instanceof GridView gridView ? gridView.getCount() : grid.getChildCount();
    }

    private int folderPosition(ViewGroup grid, View child) {
        if (grid instanceof GridView gridView) {
            int position = gridView.getPositionForView(child);
            if (position >= 0) {
                return position;
            }
        }
        return grid.indexOfChild(child);
    }

    private View folderViewAtPosition(ViewGroup grid, int position) {
        if (position < 0) {
            return null;
        }
        if (grid instanceof GridView gridView) {
            int first = gridView.getFirstVisiblePosition();
            int childIndex = position - first;
            return childIndex >= 0 && childIndex < grid.getChildCount()
                    ? grid.getChildAt(childIndex) : null;
        }
        return position < grid.getChildCount() ? grid.getChildAt(position) : null;
    }

    private Object folderItemAtPosition(ViewGroup grid, int position) {
        if (position < 0 || position >= folderItemCount(grid)) {
            return null;
        }
        if (grid instanceof GridView gridView) {
            return gridView.getItemAtPosition(position);
        }
        View child = folderViewAtPosition(grid, position);
        return child == null ? null : child.getTag();
    }

    private int folderSourceIndex(ViewGroup grid, LiftSession session) {
        for (int index = 0; index < folderItemCount(grid); index++) {
            StableItemRef item = describeItem(folderItemAtPosition(grid, index));
            if (item != null && session.sourceIds().contains(item.id())) {
                return index;
            }
        }
        return -1;
    }

    private View folderAnchorView(ViewGroup grid, Object anchorItem) {
        if (anchorItem == null) {
            return null;
        }
        View anchor = grid.findViewWithTag(anchorItem);
        if (anchor != null) {
            return anchor;
        }
        StableItemRef expected = describeItem(anchorItem);
        for (int index = 0; index < grid.getChildCount(); index++) {
            View child = grid.getChildAt(index);
            StableItemRef observed = describeItem(child.getTag());
            if (expected != null && observed != null && expected.id() == observed.id()) {
                return child;
            }
        }
        View tagHolder = new View(grid.getContext());
        tagHolder.setTag(anchorItem);
        return tagHolder;
    }

    private boolean isSourceView(View view, LiftSession session) {
        StableItemRef item = describeItem(view.getTag());
        return item != null && session.sourceIds().contains(item.id());
    }

    private boolean folderNeedsAppend(View target, LiftSession session) {
        ViewGroup grid = findFolderGrid(target);
        if (grid == null) {
            return false;
        }
        int targetIndex = folderPosition(grid, target);
        int itemCount = folderItemCount(grid);
        if (targetIndex < 0 || itemCount <= 0) {
            return false;
        }
        return FolderPlacementTarget.anchorIndex(
                PlacementAction.MOVE_AFTER, targetIndex, itemCount,
                folderSourceIndex(grid, session)) >= itemCount;
    }

    private void reorderFolderToEnd(View target) {
        ViewGroup grid = findFolderGrid(target);
        if (grid == null) {
            return;
        }
        try {
            Field lastHit = findField(grid.getClass(), "mLastHit");
            Method reorderItems = findOptionalDeclaredMethod(grid.getClass(), "reorderItems");
            if (reorderItems == null) {
                return;
            }
            lastHit.set(grid, null);
            reorderItems.invoke(grid);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The normal pointer-driven reorder remains the fallback on variant builds.
        }
    }

    private float[] centerInWindow(View view, Activity activity) {
        int[] viewLocation = new int[2];
        int[] windowLocation = new int[2];
        view.getLocationOnScreen(viewLocation);
        activity.getWindow().getDecorView().getLocationOnScreen(windowLocation);
        return new float[]{
                viewLocation[0] - windowLocation[0] + view.getWidth() / 2f,
                viewLocation[1] - windowLocation[1] + view.getHeight() / 2f
        };
    }

    private float[] targetPointInWindow(
            View target, Activity activity, PlacementAction action, LiftSession session) {
        ViewGroup grid = findFolderGrid(target);
        if (grid != null) {
            int targetIndex = folderPosition(grid, target);
            int itemCount = folderItemCount(grid);
            int anchorIndex = FolderPlacementTarget.anchorIndex(
                    action, targetIndex, itemCount, folderSourceIndex(grid, session));
            View anchor = folderViewAtPosition(grid, anchorIndex);
            if (anchor != null && !isSourceView(anchor, session)) {
                return centerInWindow(anchor, activity);
            }
            if (anchorIndex < itemCount && anchor != null) {
                return centerInWindow(anchor, activity);
            }
            return centerInWindow(target, activity);
        }
        float[] point = centerInWindow(target, activity);
        if (action == PlacementAction.CREATE_FOLDER) {
            return point;
        }
        boolean rtl = target.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float direction = action == PlacementAction.MOVE_BEFORE ? -1f : 1f;
        if (rtl) {
            direction *= -1f;
        }
        point[0] += direction * Math.max(1f, target.getWidth() * 0.42f);
        return point;
    }

    private float[] positionPointInWindow(PositionTarget target, Activity activity) {
        int[] hostLocation = new int[2];
        int[] windowLocation = new int[2];
        target.host().getLocationOnScreen(hostLocation);
        activity.getWindow().getDecorView().getLocationOnScreen(windowLocation);
        return new float[]{
                hostLocation[0] - windowLocation[0] + target.dropXInParent(),
                hostLocation[1] - windowLocation[1] + target.dropYInParent()
        };
    }

    private static void dispatch(Activity activity, long downTime, long eventTime,
                                 int action, float[] point) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, point[0], point[1], 0);
        try {
            activity.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private StableItemRef describeItem(Object item) {
        if (item == null || !itemInfoClass.isInstance(item)) {
            return null;
        }
        try {
            return new StableItemRef(
                    idField.getLong(item),
                    containerField.getLong(item),
                    screenField.getLong(item),
                    cellXField.getInt(item),
                    cellYField.getInt(item),
                    itemTypeField.getInt(item),
                    spanXField.getInt(item),
                    spanYField.getInt(item));
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Field findOptionalField(Class<?> type, String name) {
        try {
            return findField(type, name);
        } catch (NoSuchFieldException | LinkageError | SecurityException ignored) {
            return null;
        }
    }

    private static Class<?> findOptionalClass(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError | SecurityException ignored) {
            return null;
        }
    }

    private static Method findOptionalDeclaredMethod(
            Class<?> type, String name, Class<?>... parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            } catch (LinkageError | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Method findInstanceMethod(
            Class<?> type, String name, Object... arguments) {
        Class<?> current = type;
        while (current != null) {
            try {
                for (Method method : current.getDeclaredMethods()) {
                    if (!method.getName().equals(name)
                            || Modifier.isStatic(method.getModifiers())
                            || !parametersAccept(method.getParameterTypes(), arguments)) {
                        continue;
                    }
                    method.setAccessible(true);
                    return method;
                }
            } catch (LinkageError | RuntimeException ignored) {
                return null;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Method findStaticMethod(Class<?> type, String name, Object... arguments) {
        Class<?> current = type;
        while (current != null) {
            try {
                for (Method method : current.getDeclaredMethods()) {
                    if (!method.getName().equals(name)
                            || !Modifier.isStatic(method.getModifiers())
                            || !parametersAccept(method.getParameterTypes(), arguments)) {
                        continue;
                    }
                    method.setAccessible(true);
                    return method;
                }
            } catch (LinkageError | RuntimeException ignored) {
                return null;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static boolean parametersAccept(Class<?>[] parameterTypes, Object[] arguments) {
        if (parameterTypes.length != arguments.length) {
            return false;
        }
        for (int index = 0; index < parameterTypes.length; index++) {
            Class<?> parameterType = parameterTypes[index];
            Object argument = arguments[index];
            if (argument == null) {
                if (parameterType.isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (parameterType.isInstance(argument)) {
                continue;
            }
            if (!parameterType.isPrimitive() || !primitiveWrapper(parameterType)
                    .isInstance(argument)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> primitiveWrapper(Class<?> primitiveType) {
        if (primitiveType == boolean.class) {
            return Boolean.class;
        }
        if (primitiveType == byte.class) {
            return Byte.class;
        }
        if (primitiveType == short.class) {
            return Short.class;
        }
        if (primitiveType == int.class) {
            return Integer.class;
        }
        if (primitiveType == long.class) {
            return Long.class;
        }
        if (primitiveType == float.class) {
            return Float.class;
        }
        if (primitiveType == double.class) {
            return Double.class;
        }
        if (primitiveType == char.class) {
            return Character.class;
        }
        return Void.class;
    }

    private static Method findOptionalMethod(
            Class<?> type, String name, Class<?>... parameterTypes) {
        if (type == null) {
            return null;
        }
        try {
            return type.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException | LinkageError | SecurityException ignored) {
            return null;
        }
    }

    static String label(View view) {
        CharSequence description = view.getContentDescription();
        if (description == null || description.length() == 0) {
            return "目标图标";
        }
        String label = description.toString();
        int separator = label.indexOf('，');
        return separator > 0 ? label.substring(0, separator) : label;
    }
}
