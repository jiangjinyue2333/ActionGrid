package io.github.jiangjinyue2333.actiongrid.hook;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedModule;

/**
 * 页码指示器上下滑翻页方向控制。
 *
 * 焦点落在页码指示器上时，TalkBack 的上下滑会以 ACTION_SCROLL_FORWARD /
 * ACTION_SCROLL_BACKWARD 进入 AccessibilityInteractionController。
 * 「倒序」只需把这两个动作互换，其他动作一律放行。
 */
final class PageIndicatorOrder implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "ActionGrid-PageOrder";
    private static final long NODE_TTL_MILLIS = 20_000L;
    private static final int MAX_REMEMBERED_NODES = 256;
    private static final long LOG_INTERVAL_MILLIS = 500L;
    private static final Pattern INDICATOR_PATTERN = Pattern.compile(
            "第\\s*[0-9０-９一二三四五六七八九十百]+\\s*页[\\s,，]*共\\s*"
                    + "[0-9０-９一二三四五六七八九十百]+\\s*页");

    private final XposedModule module;
    private final Method sourceNodeId;
    private final Map<Long, SeenNode> seenNodes =
            new LinkedHashMap<Long, SeenNode>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, SeenNode> eldest) {
                    return size() > MAX_REMEMBERED_NODES;
                }
            };

    private volatile boolean reversed;
    private long lastLogAt;
    private String lastLog = "";

    PageIndicatorOrder(XposedModule module, Method sourceNodeId) {
        this.module = module;
        this.sourceNodeId = sourceNodeId;
        try {
            SharedPreferences preferences =
                    module.getRemotePreferences(PageOrderProtocol.PREFERENCES_GROUP);
            reversed = preferences.getBoolean(PageOrderProtocol.KEY_REVERSED, false);
            preferences.registerOnSharedPreferenceChangeListener(this);
            log("preferences attached reversed=" + reversed);
        } catch (RuntimeException exception) {
            log("preferences unavailable " + exception.getClass().getSimpleName());
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        if (!PageOrderProtocol.KEY_REVERSED.equals(key)) {
            return;
        }
        reversed = preferences.getBoolean(PageOrderProtocol.KEY_REVERSED, false);
        log("reversed=" + reversed);
    }

    void observe(AccessibilityNodeInfo node) {
        if (node == null) {
            return;
        }
        try {
            CharSequence description = node.getContentDescription();
            String descriptionText = description == null ? "" : description.toString();
            if (descriptionText.indexOf('页') >= 0 && isIndicator(descriptionText)) {
                remember(node, descriptionText);
                return;
            }
            CharSequence text = node.getText();
            String textText = text == null ? "" : text.toString();
            if (textText.indexOf('页') >= 0 && isIndicator(textText)) {
                remember(node, textText);
            }
        } catch (Throwable ignored) {
            // 节点读取失败不应影响无障碍链路
        }
    }

    /**
     * @return 需要替换成的动作；-1 表示原样放行。
     */
    int mapAction(long nodeId, int action) {
        if (action != AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                && action != AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            return -1;
        }
        SeenNode seen;
        synchronized (seenNodes) {
            seen = seenNodes.get(nodeId);
        }
        long now = SystemClock.elapsedRealtime();
        boolean indicator = seen != null && now - seen.at <= NODE_TTL_MILLIS;
        int mapped = -1;
        if (indicator && reversed) {
            mapped = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
        }
        logThrottled("scroll node=" + nodeId + " action=" + action
                + " indicator=" + indicator + " reversed=" + reversed
                + " mapped=" + mapped + " label=" + (seen == null ? "" : seen.label));
        return mapped;
    }

    boolean isReversed() {
        return reversed;
    }

    private void remember(AccessibilityNodeInfo node, String label) {
        try {
            long id = ((Number) sourceNodeId.invoke(node)).longValue();
            long now = SystemClock.elapsedRealtime();
            synchronized (seenNodes) {
                seenNodes.put(id, new SeenNode(now, label));
            }
        } catch (Throwable ignored) {
            // 反射取值失败时放弃记录，保持原行为
        }
    }

    static boolean isIndicator(String value) {
        return INDICATOR_PATTERN.matcher(value).find();
    }

    private void log(String message) {
        if (module == null) {
            return;
        }
        try {
            module.log(android.util.Log.INFO, TAG, message);
        } catch (RuntimeException ignored) {
            // 日志失败不影响功能
        }
    }

    private void logThrottled(String message) {
        long now = SystemClock.elapsedRealtime();
        if (message.equals(lastLog) && now - lastLogAt < LOG_INTERVAL_MILLIS) {
            return;
        }
        lastLog = message;
        lastLogAt = now;
        log(message);
    }

    private static final class SeenNode {
        final long at;
        final String label;

        SeenNode(long at, String label) {
            this.at = at;
            this.label = label;
        }
    }
}
