package io.github.jiangjinyue2333.actiongrid.hook;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 从列表 adapter 读取微信会话行的真实状态。
 *
 * ⚠️ 早期版本靠反射在 View 上盲猜字段名（isPinned / isTop / mUnread...），真机实测
 * `ConversationFolderItemView` 身上一个状态字段都没有，全程 probe miss。
 * 正解是回到数据源：会话列表 adapter 的 item 才持有状态。
 *
 * 实测（2026-10-20，与屏幕上可见的置顶状态逐行对照）：
 *   未读 = item.field_unReadCount > 0
 *   置顶 = item.field_flag 的 bit62（1L << 62）
 *   ⚠️ field_attrflag 的 0x4000000 不是置顶位，是「是否群聊」（17 条群聊全有、0 条非群聊有），别搞混
 */
final class WeChatProbe {
    private static final String TAG = "ActionGrid-WeChat";

    /** 置顶标志位：field_flag 的 bit62。 */
    private static final long FLAG_PINNED = 1L << 62;

    /** 状态缓存时间。无障碍事件很密，但执行完菜单动作状态会变，不能长期缓存。 */
    private static final long TTL_MILLIS = 1500L;

    private static final Map<View, Cached> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private WeChatProbe() {
    }

    /**
     * @return [isPinned, hasUnread]；无法判断时返回 null（调用方会回退到静态预设）。
     */
    static boolean[] probe(View row) {
        if (row == null) {
            return null;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (CACHE) {
            Cached cached = CACHE.get(row);
            if (cached != null && now - cached.at <= TTL_MILLIS) {
                return new boolean[]{cached.pinned, cached.unread};
            }
        }
        boolean[] state = read(row);
        if (state == null) {
            logMissOnce(row);
            return null;
        }
        CACHE.put(row, new Cached(SystemClock.elapsedRealtime(), state[0], state[1]));
        return state;
    }

    /** 执行完菜单动作后状态已变，立刻丢弃缓存，否则文案会停在旧状态。 */
    static void invalidate(View row) {
        if (row != null) {
            CACHE.remove(row);
        }
    }

    static void invalidateAll() {
        CACHE.clear();
    }

    /**
     * 公众号/服务号这类聚合行：官方长按菜单只有「不显示该聊天/删除该聊天」，
     * 置顶和标为已读/未读都不适用。硬编码用户名 + gh_ 前缀（单个公众号）。
     */
    static boolean isOfficialAccount(View row) {
        Object record = recordOf(row);
        if (record == null) {
            return false;
        }
        Object username = WeChatDumper.fieldValue(record, "field_username");
        if (!(username instanceof String name)) {
            return false;
        }
        return OFFICIAL_USERNAMES.contains(name) || name.startsWith("gh_");
    }

    private static boolean[] read(View row) {
        Object record = recordOf(row);
        if (record == null) {
            return null;
        }
        Object unread = WeChatDumper.fieldValue(record, "field_unReadCount");
        Object flag = WeChatDumper.fieldValue(record, "field_flag");
        if (!(unread instanceof Number) || !(flag instanceof Number)) {
            return null;
        }
        Number unreadCount = (Number) unread;
        Number flagValue = (Number) flag;
        return new boolean[] {
                (flagValue.longValue() & FLAG_PINNED) != 0,
                unreadCount.intValue() > 0
        };
    }

    /** 行 View → 所在列表 → adapter item → 会话记录对象。 */
    private static Object recordOf(View row) {
        View cursor = row;
        while (cursor != null) {
            if (cursor instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) cursor;
                Object adapter = WeChatDumper.findAdapter(group);
                if (adapter != null) {
                    Object record = recordAt(adapter, WeChatDumper.positionOf(group, row));
                    if (record == null) {
                        // 外层（HeaderViewListAdapter）拿不到时，按表头偏移换算到内层。
                        Object wrapped = WeChatDumper.unwrap(adapter);
                        if (wrapped != adapter) {
                            int headers = headerCount(adapter);
                            record = recordAt(wrapped, headers < 0 ? -1 : positionOfView(group, row) - headers);
                        }
                    }
                    if (record != null) {
                        return record;
                    }
                }
            }
            Object parent = cursor.getParent();
            cursor = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static Object recordAt(Object adapter, int position) {
        if (position < 0) {
            return null;
        }
        try {
            java.lang.reflect.Method getItem =
                    adapter.getClass().getMethod("getItem", int.class);
            Object item = getItem.invoke(adapter, position);
            return WeChatDumper.conversationOf(item);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int headerCount(Object adapter) {
        try {
            java.lang.reflect.Method method = adapter.getClass().getMethod("getHeadersCount");
            return ((Number) method.invoke(adapter)).intValue();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int positionOfView(ViewGroup group, View row) {
        return WeChatDumper.positionOf(group, row);
    }

    private static void logMissOnce(View row) {
        String name = row.getClass().getName();
        if (MISSED.add(name)) {
            Log.d(TAG, "probe miss on " + name);
        }
    }

    private static final java.util.Set<String> MISSED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final java.util.Set<String> OFFICIAL_USERNAMES = java.util.Set.of(
            "officialaccounts", "service_officialaccounts", "notifymessage", "foldedgroup",
            "conversationboxservice");

    /** 诊断：把装饰过的行的 username + 屏幕显示文字记一遍，用于对上「折叠的聊天」这类特殊行。 */
    static void logUsernameOnce(View row) {
        Object record = recordOf(row);
        String name = record == null
                ? "(no record)"
                : String.valueOf(WeChatDumper.fieldValue(record, "field_username"));
        String text = rowText(row);
        String combo = name + " | " + text;
        if (UNKNOWN_ROWS.add(combo)) {
            Log.i(TAG, "row text=\"" + text + "\" user=" + name);
        }
    }

    /** 收集行内 TextView 的文字，用来对应听到的文案。 */
    private static String rowText(View row) {
        StringBuilder builder = new StringBuilder();
        collectText(row, builder, 0);
        String text = builder.toString().trim();
        return text.length() > 60 ? text.substring(0, 60) + "…" : text;
    }

    private static void collectText(View view, StringBuilder builder, int depth) {
        if (depth > 6 || builder.length() > 120) {
            return;
        }
        if (view instanceof android.widget.TextView textView) {
            CharSequence value = textView.getText();
            if (value != null && value.length() > 0) {
                if (builder.length() > 0) {
                    builder.append(" / ");
                }
                builder.append(value);
            }
            return;
        }
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                collectText(group.getChildAt(index), builder, depth + 1);
            }
        }
    }

    private static final java.util.Set<String> UNKNOWN_ROWS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private record Cached(long at, boolean pinned, boolean unread) {
    }
}
