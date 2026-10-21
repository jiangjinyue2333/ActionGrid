package io.github.jiangjinyue2333.actiongrid.hook;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Adapter;
import android.widget.ListView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 微信会话行结构诊断（一次性）。
 *
 * WeChatProbe 之前靠字段名盲猜置顶/未读状态，命中率无从验证。
 * 这个类在第一次遇到会话行时，把真实结构全量打到 logcat：
 *   - View 类继承链
 *   - View 自身字段与取值
 *   - View 持有的非 View 对象（递归一层）字段与取值
 *   - 向上找到的列表 Adapter 及其前若干个 item 的类与字段
 *
 * 拿到真实结构后，probe 才能改成「读 adapter item」而不是猜字段名。
 */
final class WeChatDumper {
    private static final String TAG = "ActionGrid-WxDump";
    private static final int MAX_FIELDS = 120;
    private static final int MAX_LIST_ROWS = 30;
    private static final int MAX_VALUE_CHARS = 96;
    private static final java.util.Set<String> DUMPED_CLASSES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private WeChatDumper() {
    }

    /** 每个行类只 dump 一次：会话行、各类消息行各拿一份结构。 */
    static void dumpOnce(View row) {
        if (row == null) {
            return;
        }
        String cls = row.getClass().getName();
        if (!DUMPED_CLASSES.add(cls)) {
            return;
        }
        // 首次触发时数据可能尚未绑定，延迟取表。
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> runDump(row, cls), 700L);
    }

    private static void runDump(View row, String cls) {
        try {
            Log.i(TAG, "===== dump " + cls + " =====");
            dumpView(row);
            dumpHolders(row);
            dumpAdapter(row);
            Log.i(TAG, "===== dump complete =====");
        } catch (Throwable throwable) {
            Log.e(TAG, "dump failed: " + throwable, throwable);
            DUMPED_CLASSES.remove(cls);
        }
    }

    private static void dumpView(View row) {
        List<String> chain = new ArrayList<>();
        for (Class<?> type = row.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            chain.add(type.getName());
        }
        Log.i(TAG, "view chain: " + String.join(" <- ", chain));
        Log.i(TAG, "view id=" + safeId(row) + " clickable=" + row.isClickable()
                + " enabled=" + row.isEnabled()
                + " childCount=" + (row instanceof ViewGroup ? ((ViewGroup) row).getChildCount() : -1));
        dumpFields("view", row, row.getClass(), MAX_FIELDS);
    }

    /** View 持有的非 View / 非 Context 对象，递归一层看里面有没有状态位。 */
    private static void dumpHolders(View row) {
        for (Class<?> type = row.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Class<?> fieldType = field.getType();
                if (View.class.isAssignableFrom(fieldType)
                        || CharSequence.class.isAssignableFrom(fieldType)
                        || Number.class.isAssignableFrom(fieldType)
                        || fieldType.isPrimitive()
                        || fieldType.getName().startsWith("android.")
                        || fieldType.getName().startsWith("java.")) {
                    continue;
                }
                Object value = readField(field, row);
                if (value == null) {
                    continue;
                }
                Log.i(TAG, "holder " + type.getSimpleName() + "." + field.getName()
                        + " (" + value.getClass().getName() + ")");
                dumpFields("holder." + field.getName(), value, value.getClass(), MAX_FIELDS);
            }
        }
    }

    private static int dumpAdapter(View row) {
        View cursor = row;
        Object adapter = null;
        ViewGroup group = null;
        int position = -1;
        while (cursor != null) {
            if (cursor instanceof ViewGroup) {
                group = (ViewGroup) cursor;
                Object found = findAdapter(group);
                if (found != null) {
                    adapter = found;
                    position = positionOf(group, row);
                    break;
                }
            }
            Object parent = cursor.getParent();
            cursor = parent instanceof View ? (View) parent : null;
        }
        if (adapter == null) {
            Log.i(TAG, "adapter: not found");
            return 0;
        }
        Log.i(TAG, "adapter " + adapter.getClass().getName()
                + " container=" + (group == null ? "?" : group.getClass().getName())
                + " position=" + position);
        try {
            Method getCount = adapter.getClass().getMethod("getCount");
            int count = ((Number) getCount.invoke(adapter)).intValue();
            Log.i(TAG, "adapter count=" + count);
            int rows = dumpList(adapter, count, position);
            if (rows == 0) {
                // 外层取不到时才拆开：HeaderViewListAdapter 内层是全库（count 大得多），
                // 下标与屏幕上的列表不对齐，只能当兜底。
                Object wrapped = unwrap(adapter);
                if (wrapped != adapter) {
                    Method innerCount = wrapped.getClass().getMethod("getCount");
                    int inner = ((Number) innerCount.invoke(wrapped)).intValue();
                    Log.i(TAG, "fallback to wrapped " + wrapped.getClass().getName()
                            + " count=" + inner);
                    rows = dumpList(wrapped, inner, -1);
                }
            }
            return rows;
        } catch (Throwable throwable) {
            Log.i(TAG, "adapter introspect failed: " + throwable);
            return 0;
        }
    }

    /** HeaderViewListAdapter 会把下标整体后移，取真正存会话数据的那份 adapter。 */
    static Object unwrap(Object adapter) {
        for (String name : new String[]{"mAdapter", "mWrappedAdapter"}) {
            for (Class<?> type = adapter.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    Object inner = field.get(adapter);
                    if (inner != null && inner != adapter) {
                        return inner;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return adapter;
    }

    /**
     * 全表对照表：置顶/未读到底存在哪个位，靠这张表与屏幕状态对比得出，不再猜。
     * 表头：idx | username | unReadCount | attrflag | flag | parentRef
     */
    private static int dumpList(Object adapter, int count, int position) {
        Method getItem;
        try {
            getItem = adapter.getClass().getMethod("getItem", int.class);
        } catch (Throwable throwable) {
            return 0;
        }
        Log.i(TAG, "--- LIST TABLE start (position=" + position + ") ---");
        int printed = 0;
        for (int index = 0; index < count && printed < MAX_LIST_ROWS; index++) {
            Object item;
            try {
                item = getItem.invoke(adapter, index);
            } catch (Throwable ignored) {
                continue;
            }
            if (item == null) {
                continue;
            }
            Object record = conversationOf(item);
            if (record == null) {
                if (printed == 0 && index < 8) {
                    Log.i(TAG, "skip[" + index + "] " + item.getClass().getName());
                }
                continue;
            }
            printed++;
            Log.i(TAG, "ROW " + pad(index, 3)
                    + (index == position ? "*" : " ")
                    + " user=" + shortText(fieldValue(record, "field_username"))
                    + " unRead=" + fieldValue(record, "field_unReadCount")
                    + " attrflag=" + fieldValue(record, "field_attrflag")
                    + " flag=" + fieldValue(record, "field_flag")
                    + " parentRef=\"" + shortText(fieldValue(record, "field_parentRef")) + "\"");
        }
        Log.i(TAG, "--- LIST TABLE end (rows=" + printed + ") ---");
        return printed;
    }

    /** item 可能是包装类，真正带 field_* 的对象在里面（如 storage.k4 -> j2）。 */
    /** k4 本身就是会话记录（继承自 storage 记录类），先查自身继承链，再考虑包装对象。 */
    static Object conversationOf(Object item) {
        if (item == null) {
            return null;
        }
        if (declaresUnread(item.getClass())) {
            return item;
        }
        for (Class<?> type = item.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getName().length() != 2) {
                    continue;
                }
                Object inner = readField(field, item);
                if (inner == null) {
                    continue;
                }
                if (declaresUnread(inner.getClass())) {
                    return inner;
                }
            }
        }
        return null;
    }

    private static boolean declaresUnread(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                current.getDeclaredField("field_unReadCount");
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    static Object fieldValue(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                return readField(field, target);
            } catch (Throwable ignored) {
            }
        }
        return "?";
    }

    private static String shortText(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return text.length() > 28 ? text.substring(0, 28) + "…" : text;
    }

    private static String pad(int value, int width) {
        String text = String.valueOf(value);
        StringBuilder builder = new StringBuilder();
        for (int i = text.length(); i < width; i++) {
            builder.append(' ');
        }
        return builder.append(text).toString();
    }

    static Object findAdapter(View view) {
        if (view instanceof ListView list) {
            return list.getAdapter();
        }
        try {
            Method method = view.getClass().getMethod("getAdapter");
            return method.invoke(view);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 递归找包含该行的 AdapterView，返回行在其 adapter 中的下标。 */
    static int positionOf(ViewGroup group, View row) {
        try {
            Method method = group.getClass().getMethod("getPositionForView", View.class);
            return ((Number) method.invoke(group, row)).intValue();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void dumpFields(String prefix, Object target, Class<?> type, int max) {
        int printed = 0;
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (printed >= max) {
                    Log.i(TAG, prefix + " …字段过多，已截断");
                    return;
                }
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                printed++;
                String access = Modifier.isPublic(field.getModifiers()) ? "public " : "";
                Log.i(TAG, prefix + "." + current.getSimpleName() + "." + field.getName()
                        + " : " + access + field.getType().getName()
                        + " = " + summarize(readField(field, target)));
            }
        }
    }

    private static Object readField(Field field, Object target) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return "<unreadable>";
        }
    }

    private static String summarize(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence sequence) {
            String text = sequence.toString();
            return "str\"" + (text.length() > MAX_VALUE_CHARS
                    ? text.substring(0, MAX_VALUE_CHARS) + "…" : text) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean
                || value instanceof Character || value.getClass().isEnum()) {
            return String.valueOf(value);
        }
        if (value instanceof android.content.Context
                || value instanceof View
                || value instanceof java.util.Map
                || value instanceof java.util.Collection) {
            return "<" + value.getClass().getSimpleName() + ">";
        }
        return "<" + value.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(value)) + ">";
    }

    private static String safeId(View row) {
        try {
            return String.valueOf(row.getId());
        } catch (Throwable ignored) {
            return "?";
        }
    }
}
