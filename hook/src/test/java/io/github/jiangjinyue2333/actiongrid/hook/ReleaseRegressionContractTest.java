package io.github.jiangjinyue2333.actiongrid.hook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public final class ReleaseRegressionContractTest {
    @Test
    public void dynamicShortcutsPrecedeLiftAndSingleLiftLabelIsConcise() throws IOException {
        String source = source("ActionController.java");
        String addActions = between(source, "private void addActions", "private boolean perform");

        assertTrue(addActions.indexOf("cached.dynamicActions()")
                < addActions.lastIndexOf("ActionIds.LIFT"));
        assertTrue(addActions.contains(": \"托起\";"));
    }

    @Test
    public void successfulPlacementLeavesSpeechToNativeDesktop() throws IOException {
        String source = source("ActionController.java");
        String position = between(source, "bridge.performPlacementAt", "return true;");
        String occupied = between(source, "bridge.performPlacement(active", "return true;");

        assertTrue(position.contains("if (!success)"));
        assertTrue(occupied.contains("if (!success)"));
    }

    @Test
    public void shortcutQueryMergesActivityAndPackageScopeResults() throws IOException {
        String source = source("MiuiHomeBridge.java");
        String loadShortcuts = between(source, "List<DynamicAction> loadDynamicActions",
                "private AppTarget appTarget");

        assertTrue(loadShortcuts.contains("queryShortcuts(launcherApps, target, true)"));
        assertTrue(loadShortcuts.contains("queryShortcuts(launcherApps, target, false)"));
        assertTrue(loadShortcuts.contains("mergeShortcutResults"));
        assertTrue(loadShortcuts.contains("FLAG_MATCH_PINNED"));
        assertFalse(loadShortcuts.contains("shortcuts = queryShortcuts"));
    }

    @Test
    public void folderEditUsesNativeFolderSheetEntry() throws IOException {
        String ids = source("ActionIds.java");
        String controller = source("ActionController.java");
        String bridge = source("MiuiHomeBridge.java");

        assertTrue(ids.contains("EDIT_FOLDER"));
        assertTrue(controller.contains("ActionIds.EDIT_FOLDER"));
        assertTrue(bridge.contains("editFolder(View view)"));
        assertTrue(bridge.contains("showFolderSheet"));
        assertTrue(bridge.contains("FolderSheet"));
    }

    @Test
    public void nonApplicationShortcutsKeepIndependentRemoveAction() throws IOException {
        String bridge = source("MiuiHomeBridge.java");
        String controller = source("ActionController.java");
        String actions = between(bridge, "BoundActions loadBasicActions", "List<DynamicAction>");
        String addActions = between(controller, "private void addActions", "private boolean perform");

        assertTrue(actions.contains("ref.itemType() == 0"));
        assertTrue(addActions.contains("if (cached.canRemove())"));
        assertFalse(addActions.contains("} else if (cached.canRemove())"));
    }

    @Test
    public void folderAfterPlacementResolvesNextChildOrAppendSlot() throws IOException {
        String source = source("MiuiHomeBridge.java");

        assertTrue(source.contains("findFolderGrid"));
        assertTrue(source.contains("FolderPlacementTarget"));
        assertTrue(source.contains("folderNeedsAppend"));
        assertTrue(source.contains("reorderFolderToEnd"));
    }

    @Test
    public void modernOccupiedPlacementUsesNativeDropTargets() throws IOException {
        String source = source("MiuiHomeBridge.java");
        String occupied = between(source, "void performPlacement(",
                "void performPlacementAt(");
        String nativeDrop = between(source, "private boolean performModernPlacement(",
                "private void performPointerPlacement(");

        assertTrue(occupied.contains("prepareModernPlacement("));
        assertTrue(occupied.contains("performModernPlacement("));
        assertTrue(nativeDrop.contains("family != BridgeFamily.MODERN_7X"));
        assertTrue(source.contains("\"autoDrop\""));
        assertTrue(source.contains("\"mAutoDraged\""));
        assertTrue(nativeDrop.contains("\"onDragEnter\""));
        assertTrue(nativeDrop.contains("\"mLastHit\""));
        assertTrue(nativeDrop.contains("\"reorderItems\""));
        assertFalse(nativeDrop.contains("MotionEvent.ACTION_UP"));
    }

    @Test
    public void modernNativeDropCancelsSyntheticLongPressWithoutSecondDrop()
            throws IOException {
        String source = source("MiuiHomeBridge.java");
        String nativeDrop = between(source, "private boolean performModernPlacement(",
                "private void performPointerPlacement(");
        String touchCleanup = between(source, "private void cancelSyntheticTouch(",
                "private void performPointerPlacement(");

        assertTrue(nativeDrop.contains("cancelSyntheticTouch(launcher, drag)"));
        assertTrue(touchCleanup.contains("drag.syntheticTouch()"));
        assertTrue(touchCleanup.contains("MotionEvent.ACTION_CANCEL"));
        assertFalse(nativeDrop.contains("MotionEvent.ACTION_UP"));
        assertFalse(touchCleanup.contains("MotionEvent.ACTION_UP"));
    }

    @Test
    public void modernDesktopSourceUsesAutoDragWhenTargetFolderIsOpen()
            throws IOException {
        String source = source("MiuiHomeBridge.java");
        String dragStart = between(source, "private void startNativeDrag(",
                "private boolean startModernFolderDrag(");
        String modernDrag = between(source, "private boolean startModernFolderDrag(",
                "private void failPlacement(");

        assertTrue(dragStart.contains("startModernFolderDrag("));
        assertTrue(dragStart.contains("needsWorkspaceForModernFolderDrag"));
        assertTrue(dragStart.contains("launcherGetWorkspace.invoke(launcher)"));
        assertTrue(modernDrag.contains("family != BridgeFamily.MODERN_7X"));
        assertTrue(modernDrag.contains("targetContainer <= 0"));
        assertTrue(modernDrag.contains("session.sourceItems().size() != 1"));
        assertTrue(modernDrag.contains("StableItemRef.CONTAINER_DESKTOP"));
        assertTrue(modernDrag.contains("\"autoDrag\""));
        assertTrue(modernDrag.contains("\"onDragStarted\""));
    }

    @Test
    public void legacyOccupiedPlacementKeepsPointerFallback() throws IOException {
        String source = source("MiuiHomeBridge.java");
        String fallback = between(source, "private void performPointerPlacement(",
                "private void startNativeDrag(");

        assertTrue(fallback.contains("MotionEvent.ACTION_MOVE"));
        assertTrue(fallback.contains("finishPlacement("));
    }

    @Test
    public void placementConfirmationUsesStableTargetAndNativeDropResult() throws IOException {
        String source = source("MiuiHomeBridge.java");
        String confirmation = between(source, "private void finishPlacement(",
                "private boolean placementMatches");
        String nativeResult = between(source, "private boolean nativeDropSucceeded",
                "private ViewGroup findFolderGrid");

        assertTrue(source.contains("nativeDropSucceeded"));
        assertTrue(source.contains("isAllDropedSuccess"));
        assertTrue(source.contains("isDropSucceeded"));
        assertTrue(nativeResult.contains(
                "&& Boolean.TRUE.equals(allSucceeded.invoke(drag.dragObject()))"));
        assertTrue(nativeResult.contains("return true;"));
        assertFalse(nativeResult.contains(
                "return Boolean.TRUE.equals(allSucceeded.invoke(drag.dragObject()))"));
        assertFalse(confirmation.contains("target.getTag()"));
        assertTrue(source.contains("Object targetItem = target.getTag()"));
    }

    @Test
    public void placementConfirmationKeepsStableHostForEveryPoll() throws IOException {
        String source = source("MiuiHomeBridge.java");
        String confirmation = between(source, "private void startPlacementConfirmation(",
                "private boolean placementMatches");

        assertTrue(confirmation.contains("host.postDelayed(() -> confirmPlacement("));
        assertFalse(confirmation.contains("anchor.postDelayed(() -> confirmPlacement("));
        assertFalse(confirmation.contains("View anchor = session.anchor()"));
    }

    @Test
    public void optionalPlacementMembersCannotAbortAllActionHooks() throws IOException {
        String source = source("MiuiHomeBridge.java");

        assertFalse(source.contains(
                "workspaceClass.getMethod(\"isIdInCurrentScreen\""));
        assertFalse(source.contains(
                "workspaceClass.getMethod(\"setCurrentScreenById\""));
        assertFalse(source.contains(
                "deviceConfigClass.getMethod(\"getHotseatMaxCount\""));
        assertTrue(source.contains(
                "findOptionalMethod(launcherClass, \"getWorkspace\""));
        assertTrue(source.contains(
                "workspaceClass, \"isIdInCurrentScreen\""));
        assertTrue(source.contains(
                "findOptionalMethod(deviceConfigClass, \"getHotseatMaxCount\""));
    }

    @Test
    public void modernBridgeUsesFolderInfoPresentInResearchApk() throws IOException {
        String source = source("MiuiHomeBridge.java");

        assertTrue(source.contains("? \"com.miui.home.folder.FolderInfo\""));
        assertFalse(source.contains("? \"com.miui.home.model.api.FolderInfo\""));
    }

    @Test
    public void crossScreenDropsRestoreSourceThenTargetPage() throws IOException {
        String source = source("MiuiHomeBridge.java");
        String occupiedDrop = between(source, "void performPlacement(",
                "void performPlacementAt(");
        String emptyDrop = between(source, "void performPlacementAt(",
                "private void startNativeDrag(");
        String dragStart = between(source, "private void startNativeDrag(",
                "private void finishPositionPlacement(");

        assertTrue(occupiedDrop.contains("startNativeDrag("));
        assertTrue(emptyDrop.contains("startNativeDrag("));
        assertTrue(dragStart.contains("workspaceIsIdInCurrentScreen.invoke"));
        assertTrue(dragStart.contains("workspaceSetCurrentScreenById.invoke"));
        assertTrue(dragStart.contains("if (showSourceFirst)"));
        assertTrue(dragStart.contains("if (showTarget)"));
    }

    @Test
    public void handledActionsEmitPlatformClickEvents() throws IOException {
        String controller = source("ActionController.java");
        String iconAction = between(controller, "boolean performAccessibilityAction(",
                "AccessibilityNodeProvider positionProvider(");
        assertTrue(iconAction.contains(
                "host.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)"));

        String provider = source("EmptyPositionAccessibilityProvider.java");
        String virtualAction = between(provider, "public boolean performAction(",
                "boolean dispatchHoverEvent(");
        assertTrue(virtualAction.contains(
                "sendEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)"));
    }

    @Test
    public void virtualGridTilesDesktopAndExposesPartiallyEmptyHotseat() throws IOException {
        String source = source("MiuiHomeBridge.java");
        String desktop = between(source, "private List<PositionTarget> emptyDesktopPositions",
                "private List<PositionTarget> emptyHotseatPosition");
        String hotseat = between(source, "private List<PositionTarget> emptyHotseatPosition",
                "private Object hotseatOwner");
        String availability = between(source, "private boolean positionStillAvailable",
                "private boolean positionPlacementMatches");

        assertTrue(desktop.contains("VirtualGridLayout.cells(columns, rows)"));
        assertTrue(desktop.contains("desktopGeometry("));
        assertTrue(desktop.contains("occupiedView == null ? null : label(occupiedView)"));
        assertTrue(desktop.contains("occupied == null || column >= occupied.length"));
        assertFalse(desktop.contains("if (occupied[column][row] != null)"));
        assertTrue(hotseat.contains("hotseatMaxCount.invoke(null)"));
        assertTrue(hotseat.contains("items.size()"));
        assertFalse(hotseat.contains("items.isEmpty()"));
        assertTrue(availability.contains("target.cellX() == items.size()"));
        assertTrue(source.contains("目标位置已变化，请重新选择位置"));
    }

    @Test
    public void placementConfirmationPollsObservedTargetInsteadOfStaleSource()
            throws IOException {
        String source = source("MiuiHomeBridge.java");
        String confirmation = between(source, "private void finishPositionPlacement(",
                "private void finishPlacement(");

        assertTrue(confirmation.contains("confirmPositionPlacement("));
        assertTrue(confirmation.contains("itemAtPosition(target)"));
        assertTrue(confirmation.contains("PlacementConfirmation.matchesItemAtPosition"));
        assertFalse(confirmation.contains("describeItem(session.sourceItems().get(0))"));
    }

    @Test
    public void emptyShortcutResultsRetryOnlyFromTheBindingWorkflow() throws IOException {
        String source = source("ActionController.java");
        String loading = between(source, "private void requestShortcutLoad(",
                "private BoundActions currentBoundActions(");

        assertTrue(loading.contains("MAX_SHORTCUT_LOAD_ATTEMPTS"));
        assertTrue(loading.contains("view.postDelayed(() -> retryShortcutLoad"));
        assertTrue(loading.contains("pendingShortcutLoads.get(view)"));
    }

    @Test
    public void realHotseatIconsRemainReachableBesideVirtualEmptyPositions()
            throws IOException {
        String source = source("EmptyPositionAccessibilityProvider.java");
        String hover = between(source, "boolean dispatchHoverEvent(", "void reset(");

        assertTrue(hover.contains("isOverActionableChild(event.getX(), event.getY())"));
        assertTrue(hover.indexOf("isOverActionableChild(") < hover.indexOf("hitTest("));
    }

    @Test
    public void hostNodeCreationDoesNotReenterItsOwnProvider() throws IOException {
        String source = source("EmptyPositionAccessibilityProvider.java");

        assertFalse(source.contains("host.createAccessibilityNodeInfo()"));
        assertTrue(source.contains("AccessibilityNodeInfo.obtain(host)"));
    }

    @Test
    public void hyperOs4UsesNativePlacementWithJavaApplicationActions() throws IOException {
        String module = source("ActionGridModule.java");
        String adapter = source("HyperOs4NodeAdapter.java");
        String gesture = source("HyperOs4GestureDispatcher.java");
        String workspace = source("HyperOs4Workspace.java");

        assertFalse(module.contains("onSystemServerStarting"));
        assertTrue(module.contains("HyperOS 4 native profile"));
        assertTrue(module.contains("HyperOs4NodeAdapter.installApplicationActions(this)"));
        assertTrue(module.contains("isNativeCandidate"));
        assertTrue(adapter.contains("android.view.AccessibilityInteractionController"));
        assertTrue(adapter.contains("returnFindNodesResult"));
        assertTrue(adapter.contains("performAccessibilityActionClientThread"));
        assertTrue(adapter.contains("!synthetic && !isCustomAction(action)"));
        assertTrue(adapter.contains("applicationActionsOnly &&"));
        assertTrue(adapter.contains("isApplicationAction(action)"));
        assertTrue(adapter.contains("startShortcut"));
        assertTrue(adapter.contains("startAppDetailsActivity"));
        assertTrue(adapter.contains("ComponentName.unflattenFromString"));
        assertTrue(adapter.contains("Settings.ACTION_APPLICATION_DETAILS_SETTINGS"));
        assertTrue(adapter.contains("applicationActionsOnly && action == ActionIds.UNINSTALL"));
        assertTrue(adapter.contains("ShortcutQuery.FLAG_MATCH_DYNAMIC"));
        assertTrue(adapter.contains("ShortcutQuery.FLAG_MATCH_PINNED"));
        assertTrue(adapter.contains("MAX_SHORTCUT_LOAD_ATTEMPTS"));
        assertFalse(adapter.contains("com.android.server"));
        assertTrue(gesture.contains("activity.dispatchTouchEvent(event)"));
        assertTrue(workspace.contains("resolver.query(FAVORITES_URI"));
        assertTrue(workspace.contains("if (loaded == null)"));
        assertTrue(workspace.contains("return null;"));
        assertTrue(workspace.contains("WORKSPACE_RESOURCE_ID"));
        assertTrue(workspace.contains("icons.size() == 1"));
        assertTrue(workspace.contains("sourceItemId"));
        assertTrue(adapter.contains("dynamicActions(target.item().id())"));
        assertTrue(adapter.contains("dynamicActions(node.item().id())"));
        assertFalse(adapter.contains("dynamicActions(target.nodeId())"));
        assertFalse(adapter.contains("dynamicActions(node.nodeId())"));
        assertFalse(workspace.contains("resolver.insert("));
        assertFalse(workspace.contains("resolver.update("));
        assertFalse(workspace.contains("resolver.delete("));
    }

    @Test
    public void hyperOs4UninstallUsesSystemInstallerForCurrentUser() throws IOException {
        String adapter = source("HyperOs4NodeAdapter.java");
        String uninstall = between(adapter, "private boolean startUninstall",
                "private boolean startDynamicAction");

        assertTrue(uninstall.contains("Intent.ACTION_DELETE"));
        assertTrue(uninstall.contains("Intent.EXTRA_USER"));
        assertTrue(uninstall.contains("Process.myUserHandle()"));
        assertTrue(uninstall.contains("Intent.FLAG_ACTIVITY_NEW_TASK"));
        assertFalse(uninstall.contains("deletePackage"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from);
        assertTrue(from >= 0 && to > from);
        return source.substring(from, to);
    }

    private static String source(String fileName) throws IOException {
        Path local = Path.of("src", "main", "java", "io", "github", "jiangjinyue2333",
                "actiongrid", "hook", fileName);
        Path path = Files.exists(local) ? local : Path.of("hook").resolve(local);
        return Files.readString(path);
    }
}
