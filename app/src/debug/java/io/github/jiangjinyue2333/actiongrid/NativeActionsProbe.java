package io.github.jiangjinyue2333.actiongrid;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

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
    private String sourceLabel;
    private String pageBefore = "";
    private boolean pageChanged;
    private final Rect sourceBounds = new Rect();
    private final Rect targetBounds = new Rect();

    @Override public void onCreate(Bundle args) {
        arguments = args == null ? Bundle.EMPTY : args;
        requestedMode = arguments.getString("action", "");
        requestedAction = "lift".equals(requestedMode) ? "托起" : requestedMode;
        if ("uninstall".equals(requestedMode)) requestedAction = "卸载";
        if ("move-before".equals(requestedMode)) {
            requestedAction = "移到 " + arguments.getString("target", "") + "前面";
        } else if ("move-after".equals(requestedMode)) {
            requestedAction = "移到 " + arguments.getString("target", "") + "后面";
        }
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = -1;
        try {
            UiAutomation ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            android.accessibilityservice.AccessibilityServiceInfo info = ui.getServiceInfo();
            info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            ui.setServiceInfo(info);
            if (!arguments.containsKey("stay")) {
                getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            SystemClock.sleep(1500);
            if (arguments.containsKey("inspect")) {
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
            if (arguments.containsKey("action") && !executed) {
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
                for (int attempt = 0; attempt < 20; attempt++) {
                    visible.setLength(0);
                    visitRoot(ui);
                    boolean moveAction = isMoveAction();
                    if (arguments.containsKey("place") && !placed && !moveAction) {
                        placeFirstVirtual(ui.getRootInActiveWindow());
                    }
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
                            && (!arguments.containsKey("requireVirtual") || virtualNodes > 0)
                            && (!arguments.containsKey("place") || placementConfirmed)) break;
                    SystemClock.sleep(250);
                }
                output.append("AFTER ").append(visible).append('\n');
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
                if (arguments.containsKey("place") && !placementConfirmed) {
                    throw new AssertionError("Placement was not confirmed in the accessibility tree");
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
            if (!executed && arguments.containsKey("builtin") && !icon.isEmpty()
                    && (icon.contentEquals(String.valueOf(node.getContentDescription()))
                    || icon.contentEquals(String.valueOf(node.getText())))) {
                executed = true;
                output.append("BUILTIN ").append(node.performAction(
                        Integer.parseInt(arguments.getString("builtin")))).append('\n');
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
            String requested = arguments.containsKey("placeRow")
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
            Rect source = new Rect();
            Rect target = new Rect();
            if (!findIconBounds(node, sourceLabel, source)
                    || !findIconBounds(node, appLabel("icon", "iconAlias"), target)) {
                return false;
            }
            int order = source.top != target.top
                    ? Integer.compare(source.top, target.top)
                    : Integer.compare(source.left, target.left);
            return "move-before".equals(requestedMode) ? order < 0 : order > 0;
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

    private boolean findIconBounds(AccessibilityNodeInfo node, String label, Rect result) {
        if (node == null || label.isEmpty()) return false;
        if (isDesktopItem(node)
                && String.valueOf(node.getContentDescription()).startsWith(label)) {
            node.getBoundsInScreen(result);
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (findIconBounds(node.getChild(i), label, result)) return true;
        }
        return false;
    }
}
