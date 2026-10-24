package io.github.jiangjinyue2333.actiongrid.hook;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ActionController {
    private static final int MAX_SHORTCUT_LOAD_ATTEMPTS = 3;
    private static final long SHORTCUT_RETRY_DELAY_MILLIS = 1_000L;

    private final MiuiHomeBridge bridge;
    private final Map<View, BoundActions> boundActions =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, Long> pendingShortcutLoads =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, Integer> shortcutLoadAttempts =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, EmptyPositionAccessibilityProvider> positionProviders =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final ExecutorService shortcutLoader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ActionGrid-shortcuts");
        thread.setDaemon(true);
        return thread;
    });
    private LiftSession session;
    private boolean placementInProgress;
    private boolean moduleLoadRecorded;

    ActionController(MiuiHomeBridge bridge) {
        this.bridge = bridge;
    }

    boolean isSupportedIcon(View view) {
        return bridge.isSupportedIcon(view);
    }

    void onBound(View view) {
        if (!bridge.isSupportedIcon(view)) {
            return;
        }
        recordModuleLoad(view);
        BoundActions previous = boundActions.get(view);
        BoundActions actions = bridge.loadBasicActions(view);
        if (actions == null) {
            boundActions.remove(view);
            pendingShortcutLoads.remove(view);
            shortcutLoadAttempts.remove(view);
        } else {
            boolean sameTarget = previous != null
                    && previous.itemId() == actions.itemId()
                    && java.util.Objects.equals(previous.appTarget(), actions.appTarget());
            if (sameTarget && previous.shortcutsLoaded()) {
                actions = actions.refreshingDynamicActions(previous.dynamicActions());
                shortcutLoadAttempts.remove(view);
            } else if (!sameTarget) {
                shortcutLoadAttempts.remove(view);
            }
            boundActions.put(view, actions);
            requestShortcutLoad(view, actions);
        }
        if (session != null
                && session.sourceIds().contains(actions == null ? Long.MIN_VALUE : actions.itemId())) {
            notifyWindowChanged(view);
        }
    }

    void onInitializeNodeInfo(View host, AccessibilityNodeInfo info) {
        if (!bridge.isSupportedIcon(host)) {
            return;
        }
        addActions(host, info);
    }

    boolean performAccessibilityAction(View host, int action) {
        if (!bridge.isSupportedIcon(host)) {
            return false;
        }
        boolean handled = perform(host, action);
        if (handled) {
            host.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
        }
        return handled;
    }

    AccessibilityNodeProvider positionProvider(
            View host, AccessibilityNodeProvider originalProvider) {
        if (session == null || placementInProgress || !bridge.isPlacementHost(host)) {
            return originalProvider;
        }
        if (originalProvider != null
                && !(originalProvider instanceof EmptyPositionAccessibilityProvider)) {
            return originalProvider;
        }
        synchronized (positionProviders) {
            EmptyPositionAccessibilityProvider existing = positionProviders.get(host);
            if (existing != null) {
                return existing;
            }
            List<PositionTarget> targets = positionTargets(host);
            if (targets.isEmpty()) {
                return originalProvider;
            }
            EmptyPositionAccessibilityProvider created =
                    new EmptyPositionAccessibilityProvider(host, this, targets);
            positionProviders.put(host, created);
            return created;
        }
    }

    boolean dispatchHoverEvent(View host, MotionEvent event) {
        if (session == null || placementInProgress || !bridge.isPlacementHost(host)) {
            return false;
        }
        EmptyPositionAccessibilityProvider provider;
        synchronized (positionProviders) {
            provider = positionProviders.get(host);
            if (provider == null) {
                List<PositionTarget> targets = positionTargets(host);
                if (targets.isEmpty()) {
                    return false;
                }
                provider = new EmptyPositionAccessibilityProvider(host, this, targets);
                positionProviders.put(host, provider);
            }
        }
        return provider.dispatchHoverEvent(event);
    }

    List<PositionTarget> positionTargets(View host) {
        return session == null || placementInProgress
                ? List.of() : bridge.emptyPositions(host, session);
    }

    boolean onInitializePositionNode(
            PositionTarget target, AccessibilityNodeInfo info) {
        View occupied = target.occupiedView();
        if (occupied == null) {
            add(info, ActionIds.CANCEL, "取消托起");
            return true;
        }
        addActions(occupied, info);
        TargetKind kind = bridge.targetKind(occupied);
        return kind == TargetKind.SHORTCUT || kind == TargetKind.FOLDER;
    }

    boolean performPositionTarget(PositionTarget requested, int action) {
        if (session == null || placementInProgress) {
            return true;
        }
        PositionTarget current = positionTargets(requested.host()).stream()
                .filter(target -> target.virtualId() == requested.virtualId()
                        && target.container() == requested.container()
                        && target.screenId() == requested.screenId()
                        && target.cellX() == requested.cellX()
                        && target.cellY() == requested.cellY())
                .findFirst()
                .orElse(null);
        if (current == null || occupantId(current) != occupantId(requested)) {
            announce(requested.host(), "目标位置已变化，请重新选择位置");
            return true;
        }
        if (current.occupiedView() != null) {
            boolean handled = perform(current.occupiedView(), action);
            if (!handled && action == AccessibilityNodeInfo.ACTION_CLICK
                    && bridge.targetKind(current.occupiedView()) == TargetKind.FOLDER) {
                return current.occupiedView().performAccessibilityAction(action, null);
            }
            return handled;
        }
        if (action == ActionIds.CANCEL) {
            cancel(requested.host(), "已取消托起");
            return true;
        }
        if (action != AccessibilityNodeInfo.ACTION_CLICK) {
            return false;
        }
        placementInProgress = true;
        LiftSession active = session;
        bridge.performPlacementAt(active, current, (success, announcement) -> {
            placementInProgress = false;
            session = null;
            clearPositionProviders();
            if (!success) {
                announce(requested.host(), announcement);
            }
            DiagnosticsClient.record(requested.host().getContext(), success
                    ? "空位置放置已确认" : "空位置放置未确认");
            focusForAccessibility(success && active.anchor() != null
                    ? active.anchor() : requested.host());
            notifyWindowChanged(requested.host());
        });
        return true;
    }

    private long occupantId(PositionTarget target) {
        StableItemRef item = target.occupiedView() == null
                ? null : bridge.describe(target.occupiedView());
        return item == null ? Long.MIN_VALUE : item.id();
    }

    private void addActions(View host, AccessibilityNodeInfo info) {
        StableItemRef target = bridge.describe(host);
        TargetKind kind = bridge.targetKind(host);
        if (target == null || kind == TargetKind.UNSUPPORTED) {
            return;
        }
        if (session == null) {
            if (kind == TargetKind.FOLDER && target.isSupportedFolder()) {
                add(info, ActionIds.EDIT_FOLDER, "编辑");
                add(info, ActionIds.LIFT, "托起");
                return;
            }
            if (kind != TargetKind.SHORTCUT || !target.isSupportedShortcut()) {
                return;
            }
            int liftCount = liftCount(host);
            String liftLabel = liftCount > 1
                    ? "托起已选中的 " + liftCount + " 个图标" : "托起";
            BoundActions cached = ensureBoundActions(host, target.id());
            if (cached != null) {
                int count = Math.min(cached.dynamicActions().size(), ActionIds.DYNAMIC_COUNT);
                for (int index = 0; index < count; index++) {
                    add(info, ActionIds.DYNAMIC_BASE + index,
                            cached.dynamicActions().get(index).label());
                }
            }
            add(info, ActionIds.LIFT, liftLabel);
            if (cached == null) {
                return;
            }
            if (cached.appTarget() != null) {
                add(info, ActionIds.APP_INFO, "应用信息");
            }
            if (cached.canUninstall()) {
                add(info, ActionIds.UNINSTALL, "卸载");
            }
            if (cached.canRemove()) {
                add(info, ActionIds.REMOVE, "从桌面移除");
            }
            return;
        }

        String label = MiuiHomeBridge.label(host);
        info.setContentDescription(ItemLocationLabel.occupied(
                label, target.container(), target.cellX(), target.cellY()));
        add(info, ActionIds.CANCEL, "取消托起");
        if (placementInProgress) {
            return;
        }
        Set<PlacementAction> actions = PlacementPolicy.actionsFor(
                session.sourceIds(), canCreateFolder(session), target, kind);
        if (actions.contains(PlacementAction.MOVE_BEFORE)) {
            add(info, ActionIds.MOVE_BEFORE, "移到 " + label + " 前面");
        }
        if (actions.contains(PlacementAction.MOVE_AFTER)) {
            add(info, ActionIds.MOVE_AFTER, "移到 " + label + " 后面");
        }
        if (actions.contains(PlacementAction.CREATE_FOLDER)) {
            add(info, ActionIds.CREATE_FOLDER, "和 " + label + " 创建文件夹");
        }
    }

    private boolean perform(View host, int action) {
        if (action == AccessibilityNodeInfo.ACTION_CLICK && session != null) {
            return performSessionClick(host);
        }
        if (action == ActionIds.LIFT) {
            return lift(host);
        }
        if (action == ActionIds.CANCEL && session != null) {
            cancel(host, "已取消托起");
            return true;
        }
        if (session != null) {
            if (action == ActionIds.MOVE_BEFORE) {
                return place(host, PlacementAction.MOVE_BEFORE);
            }
            if (action == ActionIds.MOVE_AFTER) {
                return place(host, PlacementAction.MOVE_AFTER);
            }
            if (action == ActionIds.CREATE_FOLDER) {
                return place(host, PlacementAction.CREATE_FOLDER);
            }
            return false;
        }
        StableItemRef item = bridge.describe(host);
        if (action == ActionIds.EDIT_FOLDER
                && item != null
                && bridge.targetKind(host) == TargetKind.FOLDER
                && item.isSupportedFolder()) {
            return record(host, "编辑", bridge.editFolder(host));
        }
        BoundActions cached = item == null ? null : ensureBoundActions(host, item.id());
        if (cached == null) {
            return false;
        }
        if (action == ActionIds.APP_INFO) {
            return record(host, "应用信息", bridge.startAppInfo(host, cached.appTarget()));
        }
        if (action == ActionIds.UNINSTALL) {
            return record(host, "卸载", bridge.uninstall(host));
        }
        if (action == ActionIds.REMOVE) {
            return record(host, "从桌面移除", bridge.remove(host));
        }
        int index = action - ActionIds.DYNAMIC_BASE;
        if (index >= 0 && index < cached.dynamicActions().size()
                && index < ActionIds.DYNAMIC_COUNT) {
            DynamicAction shortcut = cached.dynamicActions().get(index);
            return record(host, shortcut.label(), bridge.startDynamicAction(host, shortcut));
        }
        return false;
    }

    private int liftCount(View host) {
        try {
            LiftSession candidate = bridge.captureLift(host);
            return candidate == null ? 1 : candidate.sourceRefs().size();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 1;
        }
    }

    private boolean lift(View host) {
        try {
            LiftSession candidate = bridge.captureLift(host);
            if (candidate == null) {
                announce(host, "当前选择包含不支持的项目");
                return true;
            }
            session = candidate;
            placementInProgress = false;
            // Match System Home's announce_for_drag and drag_multiple_item_name.
            announce(host, candidate.multiSelect()
                    ? "已拖起" + candidate.sourceRefs().size() + "个图标"
                    : "已拖起");
            DiagnosticsClient.record(host.getContext(), candidate.multiSelect()
                    ? "已建立多选托起会话" : "已建立单项托起会话");
            notifyWindowChanged(host);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            announce(host, "当前桌面版本不支持此操作");
            DiagnosticsClient.record(host.getContext(), "托起失败 "
                    + exception.getClass().getSimpleName());
            return true;
        }
    }

    private boolean performSessionClick(View host) {
        StableItemRef target = bridge.describe(host);
        if (target == null) {
            return false;
        }
        if (session.sourceIds().contains(target.id())) {
            announce(host, "这是已托起的图标");
            return true;
        }
        TargetKind kind = bridge.targetKind(host);
        if (kind == TargetKind.FOLDER) {
            return false;
        }
        if (kind == TargetKind.SHORTCUT
                && canCreateFolder(session)
                && (target.container() == StableItemRef.CONTAINER_DESKTOP
                || target.container() == StableItemRef.CONTAINER_HOTSEAT)) {
            return place(host, PlacementAction.CREATE_FOLDER);
        }
        if (kind == TargetKind.SHORTCUT && target.container() > 0) {
            announce(host, "请选择移到 " + MiuiHomeBridge.label(host) + " 前面或后面");
            return true;
        }
        return false;
    }

    private static boolean canCreateFolder(LiftSession active) {
        return active.sourceRefs().stream().allMatch(StableItemRef::isSupportedShortcut);
    }

    private boolean place(View host, PlacementAction action) {
        if (placementInProgress || session == null) {
            return true;
        }
        placementInProgress = true;
        LiftSession active = session;
        bridge.performPlacement(active, host, action, (success, announcement) -> {
            placementInProgress = false;
            session = null;
            clearPositionProviders();
            if (!success) {
                announce(host, announcement);
            }
            DiagnosticsClient.record(host.getContext(), success
                    ? "原生放置已确认 " + action : "原生放置未确认 " + action);
            View focus = success && active.anchor() != null ? active.anchor() : host;
            focusForAccessibility(focus);
            notifyWindowChanged(host);
        });
        return true;
    }

    private void cancel(View host, String announcement) {
        LiftSession active = session;
        session = null;
        placementInProgress = false;
        clearPositionProviders();
        announce(host, announcement);
        DiagnosticsClient.record(host.getContext(), "托起会话已取消");
        View anchor = active == null ? null : active.anchor();
        focusForAccessibility(anchor != null ? anchor : host);
        notifyWindowChanged(host);
    }

    private BoundActions ensureBoundActions(View view, long itemId) {
        BoundActions cached = currentBoundActions(view, itemId);
        if (cached != null) {
            if (!cached.shortcutsLoaded()) {
                requestShortcutLoad(view, cached);
            }
            return cached;
        }
        BoundActions actions = bridge.loadBasicActions(view);
        if (actions == null || actions.itemId() != itemId) {
            return null;
        }
        boundActions.put(view, actions);
        requestShortcutLoad(view, actions);
        return actions;
    }

    private void requestShortcutLoad(View view, BoundActions base) {
        if (base.shortcutsLoaded() || base.appTarget() == null) {
            return;
        }
        synchronized (pendingShortcutLoads) {
            Long pendingId = pendingShortcutLoads.get(view);
            if (pendingId != null && pendingId == base.itemId()) {
                return;
            }
            pendingShortcutLoads.put(view, base.itemId());
        }
        int attempt;
        synchronized (shortcutLoadAttempts) {
            attempt = shortcutLoadAttempts.getOrDefault(view, 0) + 1;
            shortcutLoadAttempts.put(view, attempt);
        }
        Context context = view.getContext().getApplicationContext();
        if (context == null) {
            context = view.getContext();
        }
        Context queryContext = context;
        shortcutLoader.execute(() -> {
            List<DynamicAction> actions = bridge.loadDynamicActions(queryContext, base.appTarget());
            view.post(() -> finishShortcutLoad(view, base, actions, attempt));
        });
    }

    private void finishShortcutLoad(View view, BoundActions requested,
                                    List<DynamicAction> dynamicActions, int attempt) {
        synchronized (pendingShortcutLoads) {
            Long pendingId = pendingShortcutLoads.get(view);
            if (pendingId == null || pendingId != requested.itemId()) {
                return;
            }
        }
        StableItemRef currentItem = bridge.describe(view);
        BoundActions current = currentItem == null
                ? null : currentBoundActions(view, currentItem.id());
        if (current == null || current.itemId() != requested.itemId()
                || !java.util.Objects.equals(current.appTarget(), requested.appTarget())) {
            pendingShortcutLoads.remove(view);
            return;
        }
        if (dynamicActions.isEmpty() && attempt < MAX_SHORTCUT_LOAD_ATTEMPTS) {
            view.postDelayed(() -> retryShortcutLoad(view, requested),
                    SHORTCUT_RETRY_DELAY_MILLIS * attempt);
            return;
        }
        pendingShortcutLoads.remove(view);
        boundActions.put(view, current.withDynamicActions(dynamicActions));
        view.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    private void retryShortcutLoad(View view, BoundActions requested) {
        synchronized (pendingShortcutLoads) {
            Long pendingId = pendingShortcutLoads.get(view);
            if (pendingId == null || pendingId != requested.itemId()) {
                return;
            }
            pendingShortcutLoads.remove(view);
        }
        StableItemRef item = bridge.describe(view);
        BoundActions current = item == null ? null : currentBoundActions(view, item.id());
        if (current != null
                && java.util.Objects.equals(current.appTarget(), requested.appTarget())) {
            requestShortcutLoad(view, current);
        }
    }

    private BoundActions currentBoundActions(View view, long itemId) {
        BoundActions cached = boundActions.get(view);
        return cached != null && cached.itemId() == itemId ? cached : null;
    }

    private void clearPositionProviders() {
        synchronized (positionProviders) {
            for (EmptyPositionAccessibilityProvider provider : positionProviders.values()) {
                provider.reset();
            }
            positionProviders.clear();
        }
    }

    private void recordModuleLoad(View view) {
        if (!moduleLoadRecorded) {
            moduleLoadRecorded = true;
            DiagnosticsClient.record(view.getContext(), "模块已加载 " + bridge.family());
        }
    }

    private boolean record(View host, String event, boolean result) {
        DiagnosticsClient.record(host.getContext(), event + (result ? " 已触发" : " 触发失败"));
        if (!result) {
            announce(host, event + "不可用，请重新定位图标");
        }
        return true;
    }

    private static void add(AccessibilityNodeInfo info, int id, CharSequence label) {
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(id, label));
    }

    private static void announce(View host, CharSequence message) {
        host.announceForAccessibility(message);
    }

    private static void notifyWindowChanged(View host) {
        View root = host.getRootView();
        (root == null ? host : root).sendAccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    @SuppressLint("AccessibilityFocus")
    private static void focusForAccessibility(View view) {
        view.post(() -> {
            view.requestFocus();
            view.performAccessibilityAction(
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
        });
    }
}
