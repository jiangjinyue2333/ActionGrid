package io.github.jiangjinyue2333.actiongrid;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Debug-only device check. Requires an explicit adb am instrument invocation. */
public final class NativeActionsProbe extends Instrumentation {
    private Bundle arguments;
    private String requestedAction;
    private String requestedMode;
    private int visitIconIndex;
    private final StringBuilder output = new StringBuilder();
    private int icons;
    private int directActions;
    private int virtualNodes;
    private boolean nativeMenuVisible;
    private boolean executed;
    private boolean focusedVirtual;
    private boolean placed;
    private boolean placementConfirmed;
    private boolean placementOnly;
    private String sourceLabel;
    private String pageBefore = "";
    private boolean pageChanged;
    private final Rect sourceBounds = new Rect();
    private final Rect targetBounds = new Rect();

    @Override public void onCreate(Bundle args) {
        arguments = args == null ? Bundle.EMPTY : args;
        requestedMode = arguments.getString("action", "");
        placementOnly = "place".equals(requestedMode);
        requestedAction = "lift".equals(requestedMode) ? "托起" : requestedMode;
        if ("uninstall".equals(requestedMode)) requestedAction = "卸载";
        if ("move-before".equals(requestedMode)) {
            requestedAction = "移到 " + arguments.getString("target", "") + "前面";
        } else if ("move-after".equals(requestedMode)) {
            requestedAction = "移到 " + arguments.getString("target", "") + "后面";
        } else if ("folder".equals(requestedMode)) {
            requestedAction = "放进 " + arguments.getString("target", "");
        } else if ("create-folder".equals(requestedMode)) {
            requestedAction = "和 " + arguments.getString("target", "") + " 创建文件夹";
        }
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = -1;
        try {
            UiAutomation ui = getUiAutomation(arguments.containsKey("quietServices") ? 0
                    : UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            android.accessibilityservice.AccessibilityServiceInfo info = ui.getServiceInfo();
            info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            ui.setServiceInfo(info);
            if (arguments.containsKey("quietServices")) {
                SystemClock.sleep(9000);
                finish(code, result);
                return;
            }
            if (!arguments.containsKey("stay")) {
                getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            SystemClock.sleep(1500);
            if (arguments.containsKey("globalBack")) {
                output.append("GLOBAL_BACK ").append(ui.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)).append('\n');
                SystemClock.sleep(750);
            }
            if (arguments.containsKey("inspect")) {
                if (arguments.containsKey("expectDesktop")) {
                    AccessibilityNodeInfo root = ui.getRootInActiveWindow();
                    List<AccessibilityNodeInfo> items = new ArrayList<>();
                    collectOrderedItems(root, items, false);
                    int expected = Integer.parseInt(arguments.getString("expectDesktop"));
                    int actual = items.size();
                    for (AccessibilityNodeInfo item : items) item.recycle();
                    if (root != null) root.recycle();
                    output.append("DESKTOP_ITEMS ").append(actual).append('\n');
                    if (actual != expected) throw new AssertionError(
                            "Desktop items: expected " + expected + ", found " + actual);
                }
                inspect(ui.getRootInActiveWindow());
                AccessibilityNodeInfo focused = ui.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
                output.append("ACCESSIBILITY_FOCUS ").append(focused == null ? "null"
                        : focused.getContentDescription()).append('\n');
                if (focused != null) focused.recycle();
                result.putString("stream", output.toString());
                finish(code, result);
                return;
            }
            visitRoot(ui);
            if (nativeMenuVisible) {
                throw new AssertionError("Native long-press menu action is still exposed");
            }
            if (arguments.containsKey("action") && !executed && !placementOnly) {
                for (int attempt = 0; attempt < 3 && !executed; attempt++) {
                    SystemClock.sleep(300);
                    visitRoot(ui);
                }
                if (!executed) throw new AssertionError("Requested action did not return success");
            }
            // A successful action may replace Home immediately (for example,
            // the package installer confirmation page). In that case the
            // first tree read can contain no Home icons even though the
            // requested action already ran.
            if (!executed && (icons == 0 || directActions == 0)) {
                throw new AssertionError("No direct Home icon actions");
            }
            SystemClock.sleep(250);
            if (arguments.containsKey("action")) {
                StringBuilder visible = new StringBuilder();
                String expected = arguments.getString("expect", "");
                for (int attempt = 0; attempt < (isFolderAction() ? 45 : 20); attempt++) {
                    if (isFolderAction() || isMoveAction()) ui.clearCache();
                    visible.setLength(0);
                    visitRoot(ui);
                    boolean moveAction = isMoveAction();
                    if (arguments.containsKey("place") && !placed && !moveAction) {
                        placeFirstVirtual(ui.getRootInActiveWindow());
                    }
                    if (placementOnly && placed) break;
                    if (placed || moveAction) {
                        placementConfirmed = findPlacedIcon(ui.getRootInActiveWindow());
                    }
                    collectText(ui.getRootInActiveWindow(), visible);
                    if (isPageAction()) {
                        String pageAfter = findPageIndicator(ui.getRootInActiveWindow());
                        pageChanged = !pageAfter.isEmpty() && !pageAfter.equals(pageBefore);
                    }
                    if ((expected.isEmpty() || visible.toString().contains(expected))
                            && (!isPageAction() || pageChanged)
                            && (!isFolderAction() || virtualNodes == 0)
                            && (!arguments.containsKey("requireVirtual") || virtualNodes > 0)
                            && (!arguments.containsKey("place") || placementConfirmed)) break;
                    SystemClock.sleep(250);
                }
                output.append("AFTER ").append(visible).append('\n');
                if (isFolderAction()) {
                    String source = arguments.getString("source", "");
                    String folder = arguments.getString("folderName",
                            arguments.getString("target", ""));
                    if (source.isEmpty()) throw new AssertionError("Folder check requires source label");
                    if (virtualNodes != 0 || !openNamedFolder(ui.getRootInActiveWindow(), folder)) {
                        throw new AssertionError("Folder operation not completed or folder unavailable: " + folder);
                    }
                    StringBuilder contents = new StringBuilder();
                    for (int attempt = 0; attempt < 20; attempt++) {
                        SystemClock.sleep(350);
                        ui.clearCache();
                        AccessibilityNodeInfo grid = findResource(ui.getRootInActiveWindow(),
                                "com.miui.home:id/folder_grid_view");
                        contents.setLength(0);
                        collectText(grid, contents);
                        if (contents.toString().contains(source)) break;
                        if (grid != null && attempt % 3 == 1) swipeFolder(ui, grid);
                    }
                    if (!contents.toString().contains(source)) {
                        throw new AssertionError("Source missing in folder_grid_view: " + source);
                    }
                    output.append("FOLDER_CONTENTS ").append(contents).append('\n');
                    placementConfirmed = true;
                }
                if (isPageAction() && !pageChanged) {
                    throw new AssertionError("Page action returned without changing the page: " + pageBefore);
                }
                if (!expected.isEmpty() && !visible.toString().contains(expected)) {
                    throw new AssertionError("Action returned without expected UI: " + expected);
                }
                if (arguments.containsKey("requireVirtual") && virtualNodes == 0) {
                    throw new AssertionError("No virtual empty position nodes after lift");
                }
                if (arguments.containsKey("focusVirtual")) {
                    AccessibilityNodeInfo focused = ui.findFocus(
                            AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
                    try {
                        String target = arguments.getString("focusTarget", "");
                        if (!focusedVirtual || focused == null
                                || (!target.isEmpty()
                                && !target.contentEquals(String.valueOf(
                                        focused.getContentDescription())))) {
                            throw new AssertionError("Virtual accessibility focus was not retained: "
                                    + target);
                        }
                    } finally {
                        if (focused != null) focused.recycle();
                    }
                }
                if (arguments.containsKey("place") && !placementOnly && !placementConfirmed) {
                    throw new AssertionError("Placement was not confirmed in the accessibility tree");
                }
                if (placementOnly && !placed) {
                    throw new AssertionError("Virtual placement action was not accepted");
                }
            }
            output.append("PASS icons=").append(icons).append(" actions=").append(directActions)
                    .append(" virtual=").append(virtualNodes)
                    .append(" executed=").append(executed)
                    .append(" placementConfirmed=").append(placementConfirmed).append('\n');
        } catch (Throwable error) {
            code = 0;
            output.append("FAIL ").append(error).append('\n');
        }
        result.putString("stream", output.toString());
        finish(code, result);
    }

    private void inspect(AccessibilityNodeInfo node) {
        if (node == null) return;
        try {
            Rect bounds = new Rect();
            Rect parentBounds = new Rect();
            node.getBoundsInScreen(bounds);
            node.getBoundsInParent(parentBounds);
            output.append(node.getViewIdResourceName()).append(" | ")
                    .append(node.getContentDescription()).append(" | ").append(node.getText())
                    .append(" selected=").append(node.isSelected())
                    .append(" checked=").append(node.isChecked())
                    .append(" checkable=").append(node.isCheckable())
                    .append(" accessibilityFocused=").append(node.isAccessibilityFocused())
                    .append(" bounds=").append(bounds.toShortString())
                    .append(" parentBounds=").append(parentBounds.toShortString())
                    .append(" actions=").append(node.getActionList()).append('\n');
            String icon = arguments.getString("icon", "");
            if (!executed && arguments.containsKey("builtin")
                    && ((!icon.isEmpty() && (icon.contentEquals(String.valueOf(node.getContentDescription()))
                    || icon.contentEquals(String.valueOf(node.getText()))))
                    || (arguments.containsKey("resource") && arguments.getString("resource")
                    .equals(node.getViewIdResourceName())))) {
                executed = true;
                int action = Integer.parseInt(arguments.getString("builtin"));
                AccessibilityNodeInfo target = AccessibilityNodeInfo.obtain(node);
                try {
                    if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                        while (!target.isClickable()) {
                            AccessibilityNodeInfo parent = target.getParent();
                            if (parent == null) break;
                            target.recycle();
                            target = parent;
                        }
                    }
                    output.append("BUILTIN ").append(target.performAction(action)).append('\n');
                } finally {
                    target.recycle();
                }
                if (arguments.containsKey("oneShot")) return;
            }
            for (int i = 0; i < node.getChildCount(); ++i) inspect(node.getChild(i));
        } finally {
            node.recycle();
        }
    }

    private void visit(AccessibilityNodeInfo node) {
        if (node == null) return;
        if (isDesktopItem(node)) {
            icons++;
            int ordinal = visitIconIndex++;
            String description = String.valueOf(node.getContentDescription());
            String source = appLabel("source", "sourceAlias");
            boolean sourceMatch = (!source.isEmpty() && source.equals(description))
                    || ordinalMatches("sourceIndex", ordinal);
            if (sourceMatch && sourceBounds.isEmpty()) {
                sourceLabel = source.isEmpty() ? description : source;
                node.getBoundsInScreen(sourceBounds);
            }
            for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
                CharSequence label = action.getLabel();
                if ((action.getId() & 0xff000000) != 0x6a000000) continue;
                if (label == null) continue;
                directActions++;
                output.append(node.getContentDescription()).append(" | ")
                        .append(action.getId()).append(" | ").append(label).append('\n');
                String icon = appLabel("icon", "iconAlias");
                String requested = requestedAction;
                boolean actionMatches = "lift".equals(requestedMode)
                        ? label.toString().startsWith("托起")
                        : "move-before".equals(requestedMode)
                        ? action.getId() == 0x6a000003
                        : "move-after".equals(requestedMode)
                        ? action.getId() == 0x6a000004
                        : label.toString().equals(requested);
                boolean iconMatch = ordinalMatches("iconIndex", ordinal)
                        || (icon.isEmpty() || description.startsWith(icon));
                if (!executed && actionMatches
                        && iconMatch) {
                    if (isMoveAction()) {
                        if (targetBounds.isEmpty()) node.getBoundsInScreen(targetBounds);
                        if (sourceLabel == null) sourceLabel = source.isEmpty() ? description : source;
                    } else {
                        sourceLabel = description;
                        if (sourceBounds.isEmpty()) node.getBoundsInScreen(sourceBounds);
                    }
                    executed = node.performAction(action.getId());
                    output.append("CALLBACK ").append(executed).append('\n');
                    if (arguments.containsKey("oneShot")) return;
                }
            }
        }
        if (node.getContentDescription() != null) {
            String description = node.getContentDescription().toString();
            if (!executed && description.startsWith("第 ")
                    && ("page-next".equals(requestedMode)
                    || "page-previous".equals(requestedMode))) {
                int action = "page-next".equals(requestedMode)
                        ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                pageBefore = description;
                executed = node.performAction(action);
                output.append("CALLBACK ").append(executed).append('\n');
            }
            if (isPositionDescription(description)) {
                virtualNodes++;
                String focusTarget = arguments.getString("focusTarget", "");
                if (arguments.containsKey("focusVirtual") && !focusedVirtual
                        && (focusTarget.isEmpty() || focusTarget.equals(description))) {
                    focusedVirtual = node.performAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS.getId());
                    output.append("FOCUS ").append(focusedVirtual).append(' ').append(description)
                            .append('\n');
                }
            }
            for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
                if (action.getId() == AccessibilityNodeInfo.AccessibilityAction
                        .ACTION_CONTEXT_CLICK.getId()
                        && "应用操作".contentEquals(action.getLabel())) {
                    nativeMenuVisible = true;
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); ++i) visit(node.getChild(i));
    }

    private void visitRoot(UiAutomation ui) {
        visitIconIndex = 0;
        virtualNodes = 0;
        visit(ui.getRootInActiveWindow());
    }

    private boolean isMoveAction() {
        return requestedMode.startsWith("move-") || requestedAction.startsWith("移到 ");
    }

    private boolean isFolderAction() {
        return "folder".equals(requestedMode) || "create-folder".equals(requestedMode);
    }

    private boolean openNamedFolder(AccessibilityNodeInfo node, String label) {
        if (node == null) return false;
        if ("com.miui.home:id/folder".equals(node.getViewIdResourceName())
                && label.contentEquals(String.valueOf(node.getContentDescription()))) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (openNamedFolder(node.getChild(i), label)) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findResource(AccessibilityNodeInfo node, String resource) {
        if (node == null) return null;
        if (resource.equals(node.getViewIdResourceName())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findResource(node.getChild(i), resource);
            if (found != null) return found;
        }
        return null;
    }

    private void swipeFolder(UiAutomation ui, AccessibilityNodeInfo grid) {
        Rect bounds = new Rect();
        grid.getBoundsInScreen(bounds);
        long down = SystemClock.uptimeMillis();
        for (int step = 0; step <= 21; step++) {
            int action = step == 0 ? android.view.MotionEvent.ACTION_DOWN
                    : step == 21 ? android.view.MotionEvent.ACTION_UP : android.view.MotionEvent.ACTION_MOVE;
            float progress = Math.min(step, 20) / 20.0f;
            float y = bounds.bottom - bounds.height() * (0.15f + 0.7f * progress);
            android.view.MotionEvent event = android.view.MotionEvent.obtain(down,
                    SystemClock.uptimeMillis(), action, bounds.centerX(), y, 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try {
                if (!ui.injectInputEvent(event, true)) throw new AssertionError("Folder swipe injection failed");
            } finally {
                event.recycle();
            }
            SystemClock.sleep(20);
        }
    }

    private boolean isPageAction() {
        return "page-next".equals(requestedMode) || "page-previous".equals(requestedMode);
    }

    private static boolean isDesktopItem(AccessibilityNodeInfo node) {
        return "com.miui.home:id/icon_icon".equals(node.getViewIdResourceName())
                || "com.miui.home:id/folder".equals(node.getViewIdResourceName());
    }

    private static boolean isPositionDescription(String description) {
        return description.startsWith("移至第 ") || description.startsWith("放置到第")
                || description.startsWith("桌面，第") || description.startsWith("底栏，第");
    }

    private static String findPageIndicator(AccessibilityNodeInfo node) {
        if (node == null) return "";
        CharSequence description = node.getContentDescription();
        if (description != null && description.toString().startsWith("第 ")) {
            return description.toString();
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String page = findPageIndicator(node.getChild(i));
            if (!page.isEmpty()) return page;
        }
        return "";
    }

    private boolean ordinalMatches(String key, int ordinal) {
        if (!arguments.containsKey(key)) return false;
        String value = arguments.getString(key, null);
        try {
            return ordinal == Integer.parseInt(value);
        } catch (Exception ignored) {
            return ordinal == arguments.getInt(key, -1);
        }
    }

    private String appLabel(String key, String aliasKey) {
        String value = arguments.getString(key, "");
        if (!value.isEmpty()) return value;
        switch (arguments.getString(aliasKey, "")) {
            case "qq": return "QQ";
            case "watermelon": return "汽水音乐";
            case "dog": return "酷狗音乐";
            case "tomato": return "番茄免费小说";
            case "file": return "文件管理";
            default: return "";
        }
    }

    private static void collectText(AccessibilityNodeInfo node, StringBuilder text) {
        if (node == null) return;
        if (node.getText() != null) text.append(node.getText()).append('|');
        if (node.getContentDescription() != null) {
            text.append(node.getContentDescription()).append('|');
        }
        for (int i = 0; i < node.getChildCount(); i++) collectText(node.getChild(i), text);
    }

    private void placeFirstVirtual(AccessibilityNodeInfo node) {
        if (node == null || placed) return;
        CharSequence description = node.getContentDescription();
        if (description != null && isPositionDescription(description.toString())) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            String requested = arguments.containsKey("placeDescription")
                    ? arguments.getString("placeDescription")
                    : arguments.containsKey("placeRow")
                    ? "移至第 " + arguments.getString("placeRow") + " 行第 "
                    + arguments.getString("placeColumn") + " 列" : "";
            if (!sourceBounds.contains(bounds.centerX(), bounds.centerY())
                    && (requested.isEmpty() || requested.contentEquals(description))) {
                targetBounds.set(bounds);
                placed = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                output.append("PLACE ").append(placed).append(' ').append(description).append('\n');
                return;
            }
        }
        for (int i = 0; i < node.getChildCount() && !placed; i++) {
            placeFirstVirtual(node.getChild(i));
        }
    }

    private boolean findPlacedIcon(AccessibilityNodeInfo node) {
        if (node == null || sourceLabel == null) return false;
        if (isMoveAction()) {
            if (virtualNodes != 0) return false;
            AccessibilityNodeInfo folder = findResource(node, "com.miui.home:id/folder_grid_view");
            List<AccessibilityNodeInfo> items = new ArrayList<>();
            collectOrderedItems(folder == null ? node : folder, items, folder != null, true);
            items.sort(Comparator.comparingInt((AccessibilityNodeInfo item) -> {
                Rect bounds = new Rect();
                item.getBoundsInScreen(bounds);
                return bounds.centerY();
            }).thenComparingInt(item -> {
                Rect bounds = new Rect();
                item.getBoundsInScreen(bounds);
                return bounds.centerX();
            }));
            int sourceIndex = -1, targetIndex = -1;
            for (int i = 0; i < items.size(); i++) {
                String label = String.valueOf(items.get(i).getContentDescription());
                if (sourceLabel.equals(label)) sourceIndex = sourceIndex == -1 ? i : -2;
                String targetLabel = appLabel("icon", "iconAlias");
                if (!targetLabel.isEmpty() && (targetLabel.equals(label)
                        || label.startsWith(targetLabel))) {
                    targetIndex = targetIndex == -1 ? i : -2;
                }
                items.get(i).recycle();
            }
            return sourceIndex >= 0 && targetIndex >= 0 && sourceIndex - targetIndex
                    == ("move-before".equals(requestedMode) ? -1 : 1);
        }
        if (targetBounds.isEmpty()) return false;
        if (isDesktopItem(node)
                && sourceLabel.equals(String.valueOf(node.getContentDescription()))) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            return targetBounds.contains(bounds.centerX(), bounds.centerY())
                    && !sourceBounds.contains(bounds.centerX(), bounds.centerY());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (findPlacedIcon(node.getChild(i))) return true;
        }
        return false;
    }

    private static void collectOrderedItems(AccessibilityNodeInfo node,
            List<AccessibilityNodeInfo> items, boolean inGrid) {
        collectOrderedItems(node, items, inGrid, false);
    }

    private static void collectOrderedItems(AccessibilityNodeInfo node,
            List<AccessibilityNodeInfo> items, boolean inGrid, boolean includeHotseat) {
        if (node == null) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        boolean hotseat = "com.miui.home:id/hotseat".equals(node.getViewIdResourceName());
        if ((hotseat && !includeHotseat) || bounds.isEmpty()) return;
        inGrid |= "com.miui.home:id/screen_content".equals(node.getViewIdResourceName())
                || hotseat;
        if (inGrid && isDesktopItem(node) && node.isVisibleToUser()) {
            items.add(AccessibilityNodeInfo.obtain(node));
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            collectOrderedItems(child, items, inGrid, includeHotseat);
            if (child != null) child.recycle();
        }
    }
}
