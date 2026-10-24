package io.github.jiangjinyue2333.actiongrid.hook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

final class HyperOs4NodeAdapter {
    private static final String TAG = "ActionGrid-HyperOS4";
    private static final String TARGET_PACKAGE = "com.miui.home";
    private static final long SESSION_MILLIS = 30_000L;
    private static final int INVALID_VIRTUAL_ID = Integer.MIN_VALUE;
    private static final int MAX_SHORTCUT_LOAD_ATTEMPTS = 3;
    private static final long SHORTCUT_RETRY_MILLIS = 300L;

    private final XposedModule module;
    private final boolean applicationActionsOnly;
    private final Class<?> callbackClass;
    private final Method findByIdClientThread;
    private final Method findFocusClientThread;
    private final Method performActionClientThread;
    private final Method returnSingleResult;
    private final Method returnListResult;
    private final Method returnPrefetchResult;
    private final Method findViewByAccessibilityId;
    private final Method callbackSingleResult;
    private final Method callbackActionResult;
    private final Method nodeSourceId;
    private final Method nodeIsSealed;
    private final Method nodeSetSealed;
    private final Field viewRootField;
    private final Field rootViewField;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object stateLock = new Object();
    private final Set<String> loggedFailures = new HashSet<>();
    private final Set<Method> hoverHooks = new HashSet<>();
    private final Map<View, List<HyperOs4Workspace.DropTarget>> hoverTargets =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final ExecutorService shortcutLoader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ActionGrid-HyperOS4-shortcuts");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Long, List<DynamicAction>> dynamicActions = new HashMap<>();
    private final Set<Long> loadingShortcuts = new HashSet<>();
    private final Map<Long, Integer> shortcutAttempts = new HashMap<>();

    private volatile Object lastController;
    private volatile View rootView;
    private volatile int lastDecoratedNodeCount;
    private volatile int lastDecoratedWindowCount;
    private HyperOs4Workspace workspace;
    private HyperOs4GestureDispatcher gestures;
    private Session session;
    private Map<Integer, HyperOs4Workspace.DropTarget> virtualTargets = Map.of();
    private boolean placementInProgress;
    private int focusedVirtualId = INVALID_VIRTUAL_ID;
    private int hoveredVirtualId = INVALID_VIRTUAL_ID;

    static boolean install(XposedModule module) {
        return install(module, false);
    }

    static boolean installApplicationActions(XposedModule module) {
        return install(module, true);
    }

    private static boolean install(XposedModule module, boolean applicationActionsOnly) {
        try {
            return new HyperOs4NodeAdapter(module, applicationActionsOnly).installHooks();
        } catch (Throwable throwable) {
            Log.e(TAG, "Desktop-process adapter construction failed", throwable);
            module.log(Log.ERROR, TAG, "Desktop-process adapter probe failed: "
                    + throwable.getClass().getSimpleName());
            return false;
        }
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi"})
    private HyperOs4NodeAdapter(XposedModule module, boolean applicationActionsOnly)
            throws ReflectiveOperationException {
        this.module = module;
        this.applicationActionsOnly = applicationActionsOnly;
        Class<?> controllerClass = Class.forName(
                "android.view.AccessibilityInteractionController");
        callbackClass = Class.forName(
                "android.view.accessibility.IAccessibilityInteractionConnectionCallback");
        Class<?> magnificationSpec = Class.forName("android.view.MagnificationSpec");
        Class<?> viewRootClass = Class.forName("android.view.ViewRootImpl");

        findByIdClientThread = accessible(controllerClass.getDeclaredMethod(
                "findAccessibilityNodeInfoByAccessibilityIdClientThread",
                long.class, Region.class, int.class, callbackClass,
                int.class, int.class, long.class, magnificationSpec,
                float[].class, Bundle.class));
        findFocusClientThread = accessible(controllerClass.getDeclaredMethod(
                "findFocusClientThread", long.class, int.class, Region.class,
                int.class, callbackClass, int.class, int.class, long.class,
                magnificationSpec, float[].class));
        performActionClientThread = accessible(controllerClass.getDeclaredMethod(
                "performAccessibilityActionClientThread", long.class, int.class,
                Bundle.class, int.class, callbackClass, int.class, int.class, long.class));
        returnSingleResult = accessible(controllerClass.getDeclaredMethod(
                "returnFindNodeResult", AccessibilityNodeInfo.class, callbackClass, int.class));
        returnListResult = accessible(controllerClass.getDeclaredMethod(
                "returnFindNodesResult", List.class, callbackClass, int.class));
        returnPrefetchResult = accessible(controllerClass.getDeclaredMethod(
                "returnPrefetchResult", int.class, List.class, callbackClass));
        findViewByAccessibilityId = accessible(controllerClass.getDeclaredMethod(
                "findViewByAccessibilityId", int.class));

        callbackSingleResult = callbackClass.getMethod(
                "setFindAccessibilityNodeInfoResult", AccessibilityNodeInfo.class, int.class);
        callbackActionResult = callbackClass.getMethod(
                "setPerformAccessibilityActionResult", boolean.class, int.class);
        nodeSourceId = accessible(AccessibilityNodeInfo.class.getDeclaredMethod(
                "getSourceNodeId"));
        nodeIsSealed = accessible(AccessibilityNodeInfo.class.getDeclaredMethod("isSealed"));
        nodeSetSealed = accessible(AccessibilityNodeInfo.class.getDeclaredMethod(
                "setSealed", boolean.class));
        viewRootField = accessible(controllerClass.getDeclaredField("mViewRootImpl"));
        rootViewField = accessible(viewRootClass.getDeclaredField("mView"));
    }

    private boolean installHooks() {
        List<XposedInterface.HookHandle> installed = new ArrayList<>();
        try {
            installed.add(module.hook(returnSingleResult)
                    .setId("actiongrid.hyperos4.node.single.v2")
                    .intercept(this::returnSingle));
            installed.add(module.hook(returnListResult)
                    .setId("actiongrid.hyperos4.node.list.v2")
                    .intercept(chain -> returnList(chain, 0)));
            installed.add(module.hook(returnPrefetchResult)
                    .setId("actiongrid.hyperos4.node.prefetch.v2")
                    .intercept(chain -> returnList(chain, 1)));
            installed.add(module.hook(findByIdClientThread)
                    .setId("actiongrid.hyperos4.node.synthetic.v2")
                    .intercept(this::findById));
            installed.add(module.hook(findFocusClientThread)
                    .setId("actiongrid.hyperos4.focus.v2")
                    .intercept(this::findFocus));
            installed.add(module.hook(performActionClientThread)
                    .setId("actiongrid.hyperos4.action.v2")
                    .intercept(this::performAction));
            Log.i(TAG, "Launcher-process accessibility hooks installed");
            module.log(Log.INFO, TAG, "Launcher-process accessibility hooks installed");
            return true;
        } catch (Throwable throwable) {
            for (XposedInterface.HookHandle handle : installed) {
                handle.unhook();
            }
            Log.e(TAG, "Hook installation failed", throwable);
            module.log(Log.ERROR, TAG, "Hook installation failed: "
                    + throwable.getClass().getSimpleName());
            return false;
        }
    }

    private Object returnSingle(XposedInterface.Chain chain) throws Throwable {
        Object value = chain.getArg(0);
        if (value instanceof AccessibilityNodeInfo node) {
            try {
                decorate(chain.getThisObject(), new ArrayList<>(List.of(node)), false);
            } catch (Throwable throwable) {
                logFailure("node-single", throwable);
            }
        }
        return chain.proceed();
    }

    private Object returnList(XposedInterface.Chain chain, int listIndex) throws Throwable {
        Object value = chain.getArg(listIndex);
        if (!(value instanceof List<?> raw)) {
            return chain.proceed();
        }
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof AccessibilityNodeInfo node) {
                nodes.add(node);
            }
        }
        try {
            decorate(chain.getThisObject(), nodes, true);
        } catch (Throwable throwable) {
            logFailure("node-list", throwable);
            return chain.proceed();
        }
        Object[] args = chain.getArgs().toArray();
        args[listIndex] = nodes;
        return chain.proceed(args);
    }

    private Object findById(XposedInterface.Chain chain) throws Throwable {
        if (applicationActionsOnly) {
            return chain.proceed();
        }
        long nodeId = (long) chain.getArg(0);
        int virtualId = HyperOs4VirtualNodes.virtualDescendantId(nodeId);
        if (!HyperOs4VirtualNodes.contains(virtualId)) {
            return chain.proceed();
        }
        Object controller = chain.getThisObject();
        Object callback = chain.getArg(3);
        int interactionId = (int) chain.getArg(2);
        lastController = controller;
        main.post(() -> sendSingleResult(callback, interactionId,
                activeSession(-1) == null
                        ? null : syntheticNode(controller, nodeId, virtualId)));
        return null;
    }

    private Object findFocus(XposedInterface.Chain chain) throws Throwable {
        if (applicationActionsOnly) {
            return chain.proceed();
        }
        if ((int) chain.getArg(1) != AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) {
            return chain.proceed();
        }
        int focused;
        synchronized (stateLock) {
            focused = focusedVirtualId;
        }
        if (focused == INVALID_VIRTUAL_ID) {
            return chain.proceed();
        }
        long requestedNodeId = (long) chain.getArg(0);
        synchronized (stateLock) {
            HyperOs4Workspace.DropTarget target = virtualTargets.get(focused);
            if (target == null || HyperOs4VirtualNodes.accessibilityViewId(requestedNodeId)
                    != HyperOs4VirtualNodes.accessibilityViewId(target.parentNodeId())) {
                return chain.proceed();
            }
        }
        Object controller = chain.getThisObject();
        Object callback = chain.getArg(4);
        int interactionId = (int) chain.getArg(3);
        lastController = controller;
        main.post(() -> sendSingleResult(callback, interactionId,
                activeSession(-1) == null
                        ? null : syntheticNode(controller, requestedNodeId, focused)));
        return null;
    }

    private Object performAction(XposedInterface.Chain chain) throws Throwable {
        long nodeId = (long) chain.getArg(0);
        int action = (int) chain.getArg(1);
        int virtualId = HyperOs4VirtualNodes.virtualDescendantId(nodeId);
        boolean synthetic = HyperOs4VirtualNodes.contains(virtualId);
        if (applicationActionsOnly && (isCustomAction(action)
                || (synthetic && action == AccessibilityNodeInfo.ACTION_CLICK))) {
            recordDiagnostic("原生动作 action=0x" + Integer.toHexString(action)
                    + " node=" + nodeId
                    + " synthetic=" + synthetic
                    + " nodes=" + lastDecoratedNodeCount
                    + " windows=" + lastDecoratedWindowCount);
        }
        if (applicationActionsOnly && (synthetic
                || (!isApplicationAction(action) && action != ActionIds.EDIT_FOLDER))) {
            return chain.proceed();
        }
        if (applicationActionsOnly && action == ActionIds.EDIT_FOLDER) {
            // Native Flutter nodes perform this action through the Rust
            // provider; the Java adapter only supplies the fallback action
            // label and folder role.
            return chain.proceed();
        }
        if (!synthetic && !isCustomAction(action)) {
            return chain.proceed();
        }
        Object controller = chain.getThisObject();
        Object callback = chain.getArg(4);
        int interactionId = (int) chain.getArg(3);
        if (applicationActionsOnly && action == ActionIds.UNINSTALL) {
            return chain.proceed();
        }
        lastController = controller;
        main.post(() -> {
            boolean handled = false;
            try {
                handled = synthetic
                        ? handleVirtualAction(controller, virtualId, action)
                        : handleNodeAction(controller, nodeId, action);
            } catch (Throwable throwable) {
                logFailure("action", throwable);
                announce("操作失败，请重新定位图标");
            }
            sendActionResult(callback, interactionId, handled);
        });
        return null;
    }

    private void decorate(Object controller, List<AccessibilityNodeInfo> nodes,
                          boolean appendVirtualNodes) throws Throwable {
        if (nodes.isEmpty()) {
            return;
        }
        View root = rootView(controller);
        if (root == null) {
            return;
        }
        lastController = controller;
        rootView = root;
        ensureWorkspace(root.getContext());

        Map<Integer, List<AccessibilityNodeInfo>> byWindow = new LinkedHashMap<>();
        for (AccessibilityNodeInfo node : nodes) {
            if (TARGET_PACKAGE.contentEquals(node.getPackageName())) {
                byWindow.computeIfAbsent(node.getWindowId(), ignored -> new ArrayList<>()).add(node);
            }
        }
        lastDecoratedNodeCount = nodes.size();
        lastDecoratedWindowCount = byWindow.size();
        for (Map.Entry<Integer, List<AccessibilityNodeInfo>> window : byWindow.entrySet()) {
            decorateWindow(controller, window.getKey(), window.getValue(), nodes,
                    appendVirtualNodes);
        }
    }

    private void decorateWindow(
            Object controller,
            int windowId,
            List<AccessibilityNodeInfo> windowNodes,
            List<AccessibilityNodeInfo> output,
            boolean appendVirtualNodes
    ) throws Throwable {
        Map<AccessibilityNodeInfo, HyperOs4Workspace.ResolvedNode> resolved =
                workspace.observe(windowId, windowNodes);
        Session active = activeSession(windowId);
        for (Map.Entry<AccessibilityNodeInfo, HyperOs4Workspace.ResolvedNode> entry
                : resolved.entrySet()) {
            addNodeActions(entry.getKey(), entry.getValue(), active);
        }
        if (applicationActionsOnly) {
            return;
        }

        Map<Integer, HyperOs4Workspace.DropTarget> targets = new LinkedHashMap<>();
        Map<View, List<HyperOs4Workspace.DropTarget>> byHost = new IdentityHashMap<>();
        Map<AccessibilityNodeInfo, int[]> cellOrder = new IdentityHashMap<>();
        for (Map.Entry<AccessibilityNodeInfo, HyperOs4Workspace.ResolvedNode> entry
                : resolved.entrySet()) {
            StableItemRef item = entry.getValue().item();
            cellOrder.put(entry.getKey(), new int[]{item.cellY(), item.cellX()});
        }
        List<AccessibilityNodeInfo> virtualNodes = new ArrayList<>();
        hoverTargets.clear();
        if (active != null && !placementInProgress && workspace.virtualIdsAvailable()) {
            for (HyperOs4Workspace.DropTarget target
                    : workspace.emptyTargets(windowId, active.source().item().id())) {
                View host = hostFor(controller, target.parentNodeId());
                if (host == null) {
                    continue;
                }
                targets.put(target.virtualId(), target);
                byHost.computeIfAbsent(host, ignored -> new ArrayList<>()).add(target);
                AccessibilityNodeInfo parent = findNode(windowNodes, target.parentNodeId());
                if (parent != null) {
                    mutate(parent, () -> parent.addChild(host, target.virtualId()));
                    if (appendVirtualNodes) {
                        AccessibilityNodeInfo virtual = createVirtualNode(host, target);
                        if (virtual != null) {
                            output.add(virtual);
                            virtualNodes.add(virtual);
                            cellOrder.put(virtual, new int[]{target.cellY(), target.cellX()});
                        }
                    }
                }
            }
        }
        synchronized (stateLock) {
            if (session != null && session.source().windowId() == windowId) {
                virtualTargets = Map.copyOf(targets);
            }
        }
        for (Map.Entry<View, List<HyperOs4Workspace.DropTarget>> entry : byHost.entrySet()) {
            hoverTargets.put(entry.getKey(), List.copyOf(entry.getValue()));
            ensureHoverHook(entry.getKey());
        }
        if (appendVirtualNodes && !virtualNodes.isEmpty()) {
            reorderWindowNodes(output, windowNodes, virtualNodes, cellOrder);
        }
    }

    private static void reorderWindowNodes(
            List<AccessibilityNodeInfo> output,
            List<AccessibilityNodeInfo> windowNodes,
            List<AccessibilityNodeInfo> virtualNodes,
            Map<AccessibilityNodeInfo, int[]> cellOrder
    ) {
        List<AccessibilityNodeInfo> members = new ArrayList<>();
        for (AccessibilityNodeInfo node : windowNodes) {
            if (cellOrder.containsKey(node)) {
                members.add(node);
            }
        }
        members.addAll(virtualNodes);
        List<AccessibilityNodeInfo> ordered = new ArrayList<>(members);
        ordered.sort((left, right) -> {
            int[] a = cellOrder.get(left);
            int[] b = cellOrder.get(right);
            int byRow = Integer.compare(a[0], b[0]);
            return byRow != 0 ? byRow : Integer.compare(a[1], b[1]);
        });
        Set<AccessibilityNodeInfo> memberSet = Collections.newSetFromMap(
                new IdentityHashMap<>());
        memberSet.addAll(members);
        int insertion = -1;
        for (int index = 0; index < output.size(); index++) {
            if (memberSet.contains(output.get(index))) {
                insertion = index;
                break;
            }
        }
        if (insertion < 0) {
            return;
        }
        output.removeIf(memberSet::contains);
        output.addAll(Math.min(insertion, output.size()), ordered);
    }

    private void addNodeActions(
            AccessibilityNodeInfo node,
            HyperOs4Workspace.ResolvedNode target,
            Session active
    ) throws Throwable {
        if (target.item().isSupportedFolder()) {
            mutate(node, () -> node.setClassName(View.class.getName()));
        }
        List<ActionSpec> actions = new ArrayList<>();
        if (!placementInProgress && active == null && target.item().isSupportedFolder()) {
            actions.add(new ActionSpec(ActionIds.EDIT_FOLDER, "编辑"));
        }
        if (!applicationActionsOnly) {
            if (!placementInProgress && active == null && target.item().isSupportedLiftSource()) {
                actions.add(new ActionSpec(ActionIds.LIFT, "托起"));
            } else if (!placementInProgress && active != null) {
                if (target.item().id() == active.source().item().id()) {
                    actions.add(new ActionSpec(ActionIds.CANCEL, "取消托起"));
                } else {
                    Set<PlacementAction> placements = PlacementPolicy.actionsFor(
                            Set.of(active.source().item().id()),
                            active.source().item().isSupportedShortcut(),
                            target.item(), targetKind(target.item()));
                    if (placements.contains(PlacementAction.MOVE_BEFORE)) {
                        actions.add(new ActionSpec(ActionIds.MOVE_BEFORE,
                                "移到 " + target.label() + " 前面"));
                    }
                    if (placements.contains(PlacementAction.MOVE_AFTER)) {
                        actions.add(new ActionSpec(ActionIds.MOVE_AFTER,
                                "移到 " + target.label() + " 后面"));
                    }
                    if (placements.contains(PlacementAction.CREATE_FOLDER)) {
                        actions.add(new ActionSpec(ActionIds.CREATE_FOLDER,
                                "和 " + target.label() + " 创建文件夹"));
                    }
                }
            }
        }
        if (!placementInProgress && active == null && target.item().isSupportedShortcut()) {
            AppTarget appTarget = appTarget(target.favorite());
            if (appTarget != null) {
                actions.add(new ActionSpec(ActionIds.APP_INFO, "应用信息"));
                if (canUninstall(appTarget)) {
                    actions.add(new ActionSpec(ActionIds.UNINSTALL, "卸载"));
                }
                List<DynamicAction> shortcuts = dynamicActions(target.item().id());
                for (int index = 0; index < Math.min(shortcuts.size(), ActionIds.DYNAMIC_COUNT);
                        index++) {
                    actions.add(new ActionSpec(ActionIds.DYNAMIC_BASE + index,
                            shortcuts.get(index).label()));
                }
                requestDynamicActions(target, appTarget);
            }
        }
        if (actions.isEmpty()) {
            return;
        }
        mutate(node, () -> {
            Set<Integer> existing = new HashSet<>();
            for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
                existing.add(action.getId());
            }
            for (ActionSpec action : actions) {
                if (!existing.contains(action.id())) {
                    node.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            action.id(), action.label()));
                }
            }
        });
    }

    private boolean handleNodeAction(Object controller, long nodeId, int action) {
        if (applicationActionsOnly && action != ActionIds.EDIT_FOLDER
                && !isApplicationAction(action)) {
            return false;
        }
        HyperOs4Workspace.ResolvedNode node = workspace == null
                ? null : workspace.resolve(nodeId);
        if (action == ActionIds.EDIT_FOLDER && node != null
                && node.item().isSupportedFolder()) {
            try {
                View folder = hostFor(controller, nodeId);
                return folder != null && folder.performAccessibilityAction(
                        AccessibilityNodeInfo.ACTION_LONG_CLICK, null);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                logFailure("folder-edit", exception);
                return false;
            }
        }
        if (node != null && activeSession(-1) == null) {
            AppTarget appTarget = appTarget(node.favorite());
            if (appTarget != null && action == ActionIds.APP_INFO) {
                return startAppInfo(appTarget);
            }
            if (appTarget != null && action == ActionIds.UNINSTALL) {
                return startUninstall(appTarget);
            }
            int dynamicIndex = action - ActionIds.DYNAMIC_BASE;
            List<DynamicAction> shortcuts = dynamicActions(node.item().id());
            if (appTarget != null && dynamicIndex >= 0
                    && dynamicIndex < shortcuts.size()
                    && dynamicIndex < ActionIds.DYNAMIC_COUNT) {
                return startDynamicAction(shortcuts.get(dynamicIndex));
            }
        }
        if (action == ActionIds.LIFT) {
            if (node == null || !node.item().isSupportedLiftSource()) {
                return false;
            }
            synchronized (stateLock) {
                clearSessionStateLocked(false);
                long screen = node.item().container() == StableItemRef.CONTAINER_DESKTOP
                        ? workspace.desktopScreen(node.windowId())
                        : HyperOs4Compatibility.NO_SCREEN;
                session = new Session(node, screen,
                        SystemClock.elapsedRealtime() + SESSION_MILLIS);
            }
            announce("已拖起");
            sendNodeEvent(controller, node.nodeId(), AccessibilityEvent.TYPE_VIEW_CLICKED, null);
            notifyContentChanged();
            return true;
        }
        if (action == ActionIds.CANCEL) {
            if (activeSession(-1) == null) {
                return false;
            }
            clearSession(true);
            announce("已取消托起");
            notifyContentChanged();
            return true;
        }
        Session active = activeSession(-1);
        if (active == null || node == null || node.windowId() != active.source().windowId()) {
            return false;
        }
        PlacementAction placement = switch (action) {
            case ActionIds.MOVE_BEFORE -> PlacementAction.MOVE_BEFORE;
            case ActionIds.MOVE_AFTER -> PlacementAction.MOVE_AFTER;
            case ActionIds.CREATE_FOLDER -> PlacementAction.CREATE_FOLDER;
            default -> null;
        };
        if (placement == null || !PlacementPolicy.actionsFor(
                Set.of(active.source().item().id()),
                active.source().item().isSupportedShortcut(),
                node.item(), targetKind(node.item())).contains(placement)) {
            return false;
        }
        HyperOs4Workspace.PlacementRelation relation = switch (placement) {
            case MOVE_BEFORE -> HyperOs4Workspace.PlacementRelation.BEFORE;
            case MOVE_AFTER -> HyperOs4Workspace.PlacementRelation.AFTER;
            case CREATE_FOLDER -> HyperOs4Workspace.PlacementRelation.CREATE_FOLDER;
        };
        return beginPlacement(controller, active, node, null, relation);
    }

    private AppTarget appTarget(HyperOs4Compatibility.FavoriteCell favorite) {
        if (favorite == null || favorite.intentUri() == null
                || favorite.intentUri().isBlank()) {
            return null;
        }
        ComponentName component = null;
        try {
            Intent intent = Intent.parseUri(favorite.intentUri(), Intent.URI_INTENT_SCHEME);
            component = intent.getComponent();
        } catch (URISyntaxException | RuntimeException ignored) {
        }
        if (component == null || component.getPackageName().isBlank()) {
            String uri = favorite.intentUri();
            int start = uri.indexOf(";component=");
            if (start >= 0) {
                start += ";component=".length();
                int end = uri.indexOf(';', start);
                if (end > start) {
                    component = ComponentName.unflattenFromString(uri.substring(start, end));
                }
            }
        }
        return component == null || component.getPackageName().isBlank()
                ? null : new AppTarget(component, Process.myUserHandle());
    }

    private List<DynamicAction> dynamicActions(long itemId) {
        synchronized (stateLock) {
            return dynamicActions.getOrDefault(itemId, List.of());
        }
    }

    private void requestDynamicActions(
            HyperOs4Workspace.ResolvedNode node,
            AppTarget target
    ) {
        long itemId = node.item().id();
        int attempt;
        synchronized (stateLock) {
            if (dynamicActions.containsKey(itemId)
                    || !loadingShortcuts.add(itemId)) {
                return;
            }
            attempt = shortcutAttempts.merge(itemId, 1, Integer::sum);
            if (attempt > MAX_SHORTCUT_LOAD_ATTEMPTS) {
                dynamicActions.put(itemId, List.of());
                loadingShortcuts.remove(itemId);
                return;
            }
        }
        shortcutLoader.execute(() -> {
            List<DynamicAction> loaded = queryDynamicActions(target);
            boolean retry = loaded.isEmpty() && attempt < MAX_SHORTCUT_LOAD_ATTEMPTS;
            synchronized (stateLock) {
                loadingShortcuts.remove(itemId);
                if (!retry) {
                    dynamicActions.put(itemId, loaded);
                }
            }
            main.post(this::notifyContentChanged);
            if (retry) {
                main.postDelayed(() -> requestDynamicActions(node, target),
                        SHORTCUT_RETRY_MILLIS);
            }
        });
    }

    private List<DynamicAction> queryDynamicActions(AppTarget target) {
        Context context = rootView == null ? null : rootView.getContext();
        if (context == null) {
            return List.of();
        }
        try {
            LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
            if (launcherApps == null) {
                return List.of();
            }
            Map<String, ShortcutInfo> merged = new LinkedHashMap<>();
            for (boolean activityScoped : new boolean[]{true, false}) {
            LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery()
                        .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                                | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                                | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                        .setPackage(target.component().getPackageName());
                if (activityScoped) {
                    query.setActivity(target.component());
                }
                List<ShortcutInfo> shortcuts = launcherApps.getShortcuts(query, target.user());
                if (shortcuts == null) {
                    continue;
                }
                for (ShortcutInfo shortcut : shortcuts) {
                    if (shortcut.isEnabled() && shortcut.getShortLabel() != null
                            && !shortcut.getShortLabel().toString().isBlank()) {
                        merged.putIfAbsent(shortcut.getPackage() + "\n" + shortcut.getId(), shortcut);
                    }
                }
            }
            List<ShortcutInfo> sorted = new ArrayList<>(merged.values());
            sorted.sort((left, right) -> {
                int manifest = Boolean.compare(!left.isDeclaredInManifest(),
                        !right.isDeclaredInManifest());
                return manifest != 0 ? manifest : Integer.compare(left.getRank(), right.getRank());
            });
            List<DynamicAction> result = new ArrayList<>(sorted.size());
            for (ShortcutInfo shortcut : sorted) {
                result.add(new DynamicAction(shortcut.getShortLabel().toString(),
                        shortcut.getPackage(), shortcut.getId(), target.user()));
            }
            return List.copyOf(result);
        } catch (RuntimeException exception) {
            logFailure("shortcuts-query", exception);
            return List.of();
        }
    }

    private boolean startAppInfo(AppTarget target) {
        Context context = rootView == null ? null : rootView.getContext();
        if (context == null) {
            return false;
        }
        try {
            if (Process.myUserHandle().equals(target.user())) {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + target.component().getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return true;
            }
            LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
            if (launcherApps == null) {
                return false;
            }
            launcherApps.startAppDetailsActivity(target.component(), target.user(), null, null);
            return true;
        } catch (RuntimeException exception) {
            logFailure("app-info", exception);
            return false;
        }
    }

    private boolean canUninstall(AppTarget target) {
        Context context = rootView == null ? null : rootView.getContext();
        if (context == null) {
            return false;
        }
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    target.component().getPackageName(), 0);
            return (info.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                    || (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        } catch (PackageManager.NameNotFoundException | RuntimeException exception) {
            logFailure("uninstall-check", exception);
            return false;
        }
    }

    private boolean startUninstall(AppTarget target) {
        Context context = rootView == null ? null : rootView.getContext();
        if (context == null || !canUninstall(target)) {
            return false;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_DELETE,
                    Uri.parse("package:" + target.component().getPackageName()));
            intent.putExtra(Intent.EXTRA_USER, Process.myUserHandle());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (RuntimeException exception) {
            logFailure("uninstall", exception);
            return false;
        }
    }

    private boolean startDynamicAction(DynamicAction action) {
        Context context = rootView == null ? null : rootView.getContext();
        if (context == null) {
            return false;
        }
        try {
            LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
            if (launcherApps == null) {
                return false;
            }
            launcherApps.startShortcut(action.packageName(), action.shortcutId(),
                    null, null, action.user());
            return true;
        } catch (RuntimeException exception) {
            logFailure("shortcut-start", exception);
            return false;
        }
    }

    private boolean handleVirtualAction(Object controller, int virtualId, int action) {
        HyperOs4Workspace.DropTarget target;
        synchronized (stateLock) {
            target = virtualTargets.get(virtualId);
        }
        if (target == null) {
            return false;
        }
        if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
            int previous;
            synchronized (stateLock) {
                if (focusedVirtualId == virtualId) {
                    return false;
                }
                previous = focusedVirtualId;
                focusedVirtualId = virtualId;
            }
            if (previous != INVALID_VIRTUAL_ID) {
                sendVirtualEvent(controller, previous,
                        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
            }
            sendVirtualEvent(controller, virtualId,
                    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
            return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
            synchronized (stateLock) {
                if (focusedVirtualId != virtualId) {
                    return false;
                }
                focusedVirtualId = INVALID_VIRTUAL_ID;
            }
            sendVirtualEvent(controller, virtualId,
                    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
            return true;
        }
        if (action != AccessibilityNodeInfo.ACTION_CLICK) {
            return false;
        }
        Session active = activeSession(-1);
        if (active == null) {
            return false;
        }
        sendVirtualEvent(controller, virtualId, AccessibilityEvent.TYPE_VIEW_CLICKED);
        return beginPlacement(controller, active, null, target,
                HyperOs4Workspace.PlacementRelation.EMPTY);
    }

    private boolean beginPlacement(
            Object controller,
            Session active,
            HyperOs4Workspace.ResolvedNode occupied,
            HyperOs4Workspace.DropTarget empty,
            HyperOs4Workspace.PlacementRelation relation
    ) {
        View root;
        try {
            root = rootView(controller);
        } catch (ReflectiveOperationException exception) {
            return false;
        }
        Activity activity = root == null ? null : activity(root.getContext());
        if (activity == null || gestures == null) {
            return false;
        }
        synchronized (stateLock) {
            if (session == null || session != active || placementInProgress) {
                return false;
            }
            placementInProgress = true;
        }
        gestures.place(activity, active.source(), occupied, empty, relation,
                (success, announcement) -> {
                    clearSession(true);
                    if (!success) {
                        announce(announcement);
                    }
                    notifyContentChanged();
                });
        return true;
    }

    private Session activeSession(int windowId) {
        synchronized (stateLock) {
            if (session != null && SystemClock.elapsedRealtime() > session.expiresAt()) {
                clearSessionStateLocked(true);
            }
            if (session == null || (windowId >= 0 && session.source().windowId() != windowId)) {
                return null;
            }
            if (windowId >= 0 && session.desktopScreen() != HyperOs4Compatibility.NO_SCREEN) {
                long visible = workspace.desktopScreen(windowId);
                if (visible != HyperOs4Compatibility.NO_SCREEN
                        && visible != session.desktopScreen()) {
                    clearSessionStateLocked(true);
                    return null;
                }
            }
            return session;
        }
    }

    private void clearSession(boolean sendFocusEvents) {
        synchronized (stateLock) {
            clearSessionStateLocked(sendFocusEvents);
        }
    }

    private void clearSessionStateLocked(boolean sendFocusEvents) {
        int focused = focusedVirtualId;
        int hovered = hoveredVirtualId;
        if (sendFocusEvents && lastController != null) {
            if (hovered != INVALID_VIRTUAL_ID) {
                sendVirtualEvent(lastController, hovered, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
            }
            if (focused != INVALID_VIRTUAL_ID) {
                sendVirtualEvent(lastController, focused,
                        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
            }
        }
        session = null;
        placementInProgress = false;
        virtualTargets = Map.of();
        focusedVirtualId = INVALID_VIRTUAL_ID;
        hoveredVirtualId = INVALID_VIRTUAL_ID;
        hoverTargets.clear();
    }

    private AccessibilityNodeInfo syntheticNode(Object controller, long requestedNodeId,
                                                int virtualId) {
        HyperOs4Workspace.DropTarget target;
        synchronized (stateLock) {
            target = virtualTargets.get(virtualId);
        }
        if (target == null || (requestedNodeId != 0L
                && HyperOs4VirtualNodes.accessibilityViewId(requestedNodeId)
                != HyperOs4VirtualNodes.accessibilityViewId(target.parentNodeId()))) {
            return null;
        }
        try {
            View host = hostFor(controller, target.parentNodeId());
            return host == null ? null : createVirtualNode(host, target);
        } catch (ReflectiveOperationException exception) {
            logFailure("synthetic-node", exception);
            return null;
        }
    }

    private AccessibilityNodeInfo createVirtualNode(
            View host, HyperOs4Workspace.DropTarget target) {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        info.setPackageName(TARGET_PACKAGE);
        info.setClassName(View.class.getName());
        info.setSource(host, target.virtualId());
        info.setParent(host,
                HyperOs4VirtualNodes.virtualDescendantId(target.parentNodeId()));
        info.setContentDescription(target.description());
        info.setEnabled(host.isEnabled());
        info.setVisibleToUser(host.isShown());
        info.setFocusable(true);
        info.setScreenReaderFocusable(true);
        info.setClickable(true);
        info.setBoundsInScreen(target.bounds());
        Rect parentBounds = new Rect(target.bounds());
        parentBounds.offset(-target.parentBounds().left, -target.parentBounds().top);
        info.setBoundsInParent(parentBounds);
        info.setCollectionItemInfo(new AccessibilityNodeInfo.CollectionItemInfo.Builder()
                .setColumnIndex(target.cellX())
                .setColumnSpan(1)
                .setRowIndex(target.cellY())
                .setRowSpan(1)
                .setSelected(false)
                .build());
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
        synchronized (stateLock) {
            boolean focused = focusedVirtualId == target.virtualId();
            info.setAccessibilityFocused(focused);
            info.addAction(focused
                    ? AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                    : AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
        }
        return info;
    }

    private void ensureWorkspace(Context context) {
        synchronized (stateLock) {
            if (workspace != null) {
                return;
            }
            workspace = new HyperOs4Workspace(context, this::notifyContentChanged, nodeSourceId);
            gestures = new HyperOs4GestureDispatcher(workspace);
        }
    }

    private synchronized void ensureHoverHook(View host) {
        Method hover = findHoverMethod(host.getClass());
        if (hover == null || !hoverHooks.add(hover)) {
            return;
        }
        try {
            module.hook(hover)
                    .setId("actiongrid.hyperos4.hover." + hover.getDeclaringClass().getName())
                    .intercept(chain -> {
                        if (chain.getThisObject() instanceof View view
                                && dispatchHover(view, (MotionEvent) chain.getArg(0))) {
                            return true;
                        }
                        return chain.proceed();
                    });
        } catch (Throwable exception) {
            hoverHooks.remove(hover);
            logFailure("hover-hook", exception);
        }
    }

    private boolean dispatchHover(View host, MotionEvent event) {
        List<HyperOs4Workspace.DropTarget> targets = hoverTargets.get(host);
        if (targets == null || targets.isEmpty() || activeSession(-1) == null) {
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) {
            int previous;
            synchronized (stateLock) {
                previous = hoveredVirtualId;
                hoveredVirtualId = INVALID_VIRTUAL_ID;
            }
            if (previous != INVALID_VIRTUAL_ID) {
                sendVirtualEvent(lastController, previous, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
            }
            return previous != INVALID_VIRTUAL_ID;
        }
        if (event.getActionMasked() != MotionEvent.ACTION_HOVER_ENTER
                && event.getActionMasked() != MotionEvent.ACTION_HOVER_MOVE) {
            return false;
        }
        int[] location = new int[2];
        host.getLocationOnScreen(location);
        int x = location[0] + Math.round(event.getX());
        int y = location[1] + Math.round(event.getY());
        int next = INVALID_VIRTUAL_ID;
        for (HyperOs4Workspace.DropTarget target : targets) {
            if (target.bounds().contains(x, y)) {
                next = target.virtualId();
                break;
            }
        }
        updateHovered(next);
        return next != INVALID_VIRTUAL_ID;
    }

    private void updateHovered(int next) {
        int previous;
        synchronized (stateLock) {
            if (hoveredVirtualId == next) {
                return;
            }
            previous = hoveredVirtualId;
            hoveredVirtualId = next;
        }
        if (previous != INVALID_VIRTUAL_ID) {
            sendVirtualEvent(lastController, previous, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
        }
        if (next != INVALID_VIRTUAL_ID) {
            sendVirtualEvent(lastController, next, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
        }
    }

    private void sendVirtualEvent(Object controller, int virtualId, int eventType) {
        if (controller == null) {
            return;
        }
        HyperOs4Workspace.DropTarget target;
        synchronized (stateLock) {
            target = virtualTargets.get(virtualId);
        }
        if (target == null) {
            return;
        }
        try {
            View host = hostFor(controller, target.parentNodeId());
            ViewParent parent = host == null ? null : host.getParent();
            if (parent == null) {
                return;
            }
            AccessibilityEvent event = AccessibilityEvent.obtain(eventType);
            event.setPackageName(TARGET_PACKAGE);
            event.setClassName(View.class.getName());
            event.setEnabled(host.isEnabled());
            event.setContentDescription(target.description());
            event.setSource(host, virtualId);
            parent.requestSendAccessibilityEvent(host, event);
        } catch (ReflectiveOperationException exception) {
            logFailure("virtual-event", exception);
        }
    }

    private void sendNodeEvent(Object controller, long nodeId, int eventType,
                               CharSequence description) {
        try {
            View host = hostFor(controller, nodeId);
            ViewParent parent = host == null ? null : host.getParent();
            if (parent == null) {
                return;
            }
            AccessibilityEvent event = AccessibilityEvent.obtain(eventType);
            event.setPackageName(TARGET_PACKAGE);
            event.setClassName(View.class.getName());
            event.setSource(host, HyperOs4VirtualNodes.virtualDescendantId(nodeId));
            if (description != null) {
                event.setContentDescription(description);
            }
            parent.requestSendAccessibilityEvent(host, event);
        } catch (ReflectiveOperationException exception) {
            logFailure("node-event", exception);
        }
    }

    private void notifyContentChanged() {
        View root = rootView;
        if (root != null) {
            root.post(() -> root.sendAccessibilityEvent(
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        }
    }

    private void announce(CharSequence text) {
        View root = rootView;
        if (root != null) {
            root.post(() -> root.announceForAccessibility(text));
        }
    }

    private View rootView(Object controller) throws ReflectiveOperationException {
        Object viewRoot = viewRootField.get(controller);
        return viewRoot == null ? null : (View) rootViewField.get(viewRoot);
    }

    private View hostFor(Object controller, long nodeId) throws ReflectiveOperationException {
        return (View) findViewByAccessibilityId.invoke(controller,
                HyperOs4VirtualNodes.accessibilityViewId(nodeId));
    }

    private long sourceNodeId(AccessibilityNodeInfo node) throws ReflectiveOperationException {
        return (long) nodeSourceId.invoke(node);
    }

    private AccessibilityNodeInfo findNode(List<AccessibilityNodeInfo> nodes, long nodeId)
            throws ReflectiveOperationException {
        for (AccessibilityNodeInfo node : nodes) {
            if (sourceNodeId(node) == nodeId) {
                return node;
            }
        }
        return null;
    }

    private void mutate(AccessibilityNodeInfo node, ThrowingRunnable change) throws Throwable {
        boolean sealed = (boolean) nodeIsSealed.invoke(node);
        if (sealed) {
            nodeSetSealed.invoke(node, false);
        }
        try {
            change.run();
        } finally {
            if (sealed) {
                nodeSetSealed.invoke(node, true);
            }
        }
    }

    private void sendSingleResult(Object callback, int interactionId,
                                  AccessibilityNodeInfo node) {
        try {
            callbackSingleResult.invoke(callback, node, interactionId);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            logFailure("callback-node", exception);
        }
    }

    private void sendActionResult(Object callback, int interactionId, boolean handled) {
        try {
            callbackActionResult.invoke(callback, handled, interactionId);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            logFailure("callback-action", exception);
        }
    }

    private static TargetKind targetKind(StableItemRef item) {
        if (item.isSupportedShortcut()) {
            return TargetKind.SHORTCUT;
        }
        return item.isSupportedFolder() ? TargetKind.FOLDER : TargetKind.UNSUPPORTED;
    }

    private static boolean isCustomAction(int action) {
        return action == ActionIds.LIFT
                || action == ActionIds.CANCEL
                || action == ActionIds.MOVE_BEFORE
                || action == ActionIds.MOVE_AFTER
                || action == ActionIds.CREATE_FOLDER
                || action == ActionIds.APP_INFO
                || action == ActionIds.UNINSTALL
                || action == ActionIds.EDIT_FOLDER
                || (action >= ActionIds.DYNAMIC_BASE
                && action < ActionIds.DYNAMIC_BASE + ActionIds.DYNAMIC_COUNT);
    }

    private static boolean isApplicationAction(int action) {
        return action == ActionIds.APP_INFO
                || action == ActionIds.UNINSTALL
                || (action >= ActionIds.DYNAMIC_BASE
                && action < ActionIds.DYNAMIC_BASE + ActionIds.DYNAMIC_COUNT);
    }

    private static Activity activity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper wrapper) {
            if (current instanceof Activity activity) {
                return activity;
            }
            Context next = wrapper.getBaseContext();
            if (next == current) {
                break;
            }
            current = next;
        }
        return current instanceof Activity activity ? activity : null;
    }

    private static Method findHoverMethod(Class<?> type) {
        Class<?> current = type;
        while (current != null) {
            try {
                return accessible(current.getDeclaredMethod(
                        "dispatchHoverEvent", MotionEvent.class));
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private synchronized void logFailure(String stage, Throwable throwable) {
        Throwable cause = throwable instanceof InvocationTargetException target
                && target.getCause() != null ? target.getCause() : throwable;
        String key = stage + ':' + cause.getClass().getName();
        if (loggedFailures.add(key)) {
            module.log(Log.WARN, TAG, stage + " failed: "
                    + cause.getClass().getSimpleName());
            recordDiagnostic("桌面动作异常 stage=" + stage + " type="
                    + cause.getClass().getSimpleName());
        }
    }

    private void recordDiagnostic(String message) {
        View root = rootView;
        if (root != null && message != null && !message.isEmpty()) {
            Context context = root.getContext();
            main.post(() -> DiagnosticsClient.record(context, message));
        }
    }

    private static <T extends java.lang.reflect.AccessibleObject> T accessible(T value) {
        value.setAccessible(true);
        return value;
    }

    private record Session(
            HyperOs4Workspace.ResolvedNode source,
            long desktopScreen,
            long expiresAt
    ) {
    }

    private record ActionSpec(int id, String label) {
    }

    private interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
