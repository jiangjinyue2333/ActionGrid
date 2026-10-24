package io.github.jiangjinyue2333.actiongrid.hook;

import static android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/** Maps only recently observed page indicator actions. Based on fanqiang1995's PR #2. */
final class PageIndicatorOrder {
    private static final long NODE_TTL_MILLIS = 20_000L;
    private static final int MAX_REMEMBERED_NODES = 256;
    private static final Pattern INDICATOR_PATTERN = Pattern.compile(
            "\\s*第\\s*[0-9０-９一二三四五六七八九十百]+\\s*页[\\s,，]*共\\s*"
                    + "[0-9０-９一二三四五六七八九十百]+\\s*页\\s*");

    // Each controller belongs to one ViewRoot/window; node IDs alone are not globally unique.
    private final Map<Object, WindowNodes> windows = new WeakHashMap<>();
    private volatile boolean reversed;

    void setReversed(boolean value) {
        reversed = value;
    }

    synchronized void observe(Object controller, int windowId, long nodeId,
                              boolean indicator, long now) {
        if (controller == null) {
            return;
        }
        if (windowId < 0) {
            windows.remove(controller);
            return;
        }
        WindowNodes window = windows.get(controller);
        if (window != null && window.windowId != windowId) {
            windows.remove(controller);
            window = null;
        }
        if (!indicator) {
            if (window != null) {
                window.nodes.remove(nodeId);
            }
            return;
        }
        if (window == null) {
            window = new WindowNodes(windowId);
            windows.put(controller, window);
        }
        window.nodes.put(nodeId, now);
        if (window.nodes.size() > MAX_REMEMBERED_NODES) {
            window.nodes.remove(window.nodes.keySet().iterator().next());
        }
    }

    /** Returns -1 when the original action should pass through unchanged. */
    synchronized int mapAction(Object controller, long nodeId, int action, long now) {
        if (!reversed || (action != ACTION_SCROLL_FORWARD && action != ACTION_SCROLL_BACKWARD)) {
            return -1;
        }
        WindowNodes window = windows.get(controller);
        Long seenAt = window == null ? null : window.nodes.get(nodeId);
        if (seenAt == null) {
            return -1;
        }
        long age = now - seenAt;
        if (age < 0 || age > NODE_TTL_MILLIS) {
            window.nodes.remove(nodeId);
            return -1;
        }
        return action == ACTION_SCROLL_FORWARD ? ACTION_SCROLL_BACKWARD : ACTION_SCROLL_FORWARD;
    }

    static boolean isIndicator(CharSequence value) {
        return value != null && INDICATOR_PATTERN.matcher(value).matches();
    }

    private static final class WindowNodes {
        final int windowId;
        final Map<Long, Long> nodes = new LinkedHashMap<>(4, 0.75f, true);

        WindowNodes(int windowId) {
            this.windowId = windowId;
        }
    }
}
