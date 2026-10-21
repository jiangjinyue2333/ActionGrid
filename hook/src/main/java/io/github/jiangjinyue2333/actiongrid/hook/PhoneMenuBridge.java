package io.github.jiangjinyue2333.actiongrid.hook;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Adapter;
import android.widget.ListView;
import android.widget.PopupWindow;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 电话 App 的官方菜单桥。
 *
 * MIUI 的上下文菜单最终会把 DecorView 加进 WindowManager，所以在 addView 处拦截：
 * 命中等待中的菜单项 → 直接调用该项自己的动作 → 拒绝添加窗口（菜单不显示、不朗读）。
 * 菜单对象每次都现建，保证绑定的是当前这一行的数据，不做缓存复用。
 */
final class PhoneMenuBridge {
    private static final String TAG = "ActionGrid-Phone";
    private static final long PENDING_MENU_MILLIS = 2_000L;

    /** 诊断开关：平时关闭；排查菜单窗口问题改 true 重编。 */
    private static final boolean DEBUG = false;
    private static final long DISCOVER_WINDOW_MILLIS = 1_500L;
    private static final int MENU_TEXT_LIMIT = 24;
    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private static XposedModule host;
    private static volatile String pendingLabel;
    private static volatile String[] pendingLabels;
    private static volatile boolean discovering;
    private static volatile MenuListener discoveryListener;
    private static volatile long pendingAt;
    private static volatile boolean installed;

    private PhoneMenuBridge() {
    }

    static synchronized void install(XposedModule module) {
        if (installed) {
            return;
        }
        host = module;
        try {
            // 两个口子都要拦：应用可能走 Impl 也可能直接走 Global 加菜单窗口。
            hookAllAddView(module, Class.forName("android.view.WindowManagerImpl"), "impl");
            hookAllAddView(module, Class.forName("android.view.WindowManagerGlobal"), "global");
            // 兜底：部分版本走 PopupWindow.show*
            installPopupHooks(module);
            installed = true;
            log("menu bridge installed");
        } catch (Throwable throwable) {
            log("menu bridge unavailable " + throwable.getClass().getSimpleName()
                    + ": " + throwable.getMessage());
        }
    }

    private static final java.util.Set<String> WINDOW_CLASSES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 拦住该类的全部 addView 重载（第一个参数是 View 的）。 */
    private static void hookAllAddView(XposedModule module, Class<?> cls, String tag) {
        int hooked = 0;
        for (Method method : cls.getDeclaredMethods()) {
            if (!"addView".equals(method.getName())
                    || method.getParameterTypes().length < 2
                    || !View.class.equals(method.getParameterTypes()[0])) {
                continue;
            }
            method.setAccessible(true);
            final String hookId = "actiongrid.phone.window.addview." + tag + "." + hooked++;
            module.hook(method)
                    .setId(hookId)
                    .intercept(PhoneMenuBridge::interceptAddView);
        }
        log("addview hooks " + tag + "=" + hooked);
    }

    private static Object interceptAddView(XposedInterface.Chain chain) throws Throwable {
        Object added = chain.getArg(0);
        if (DEBUG && added instanceof View windowView) {
            String cls = windowView.getClass().getName();
            if (WINDOW_CLASSES.add(cls)) {
                log("window + " + cls);
            }
        }
        if (discovering && added instanceof View discovered) {
            String[] labels = menuLabels(discovered);
            MenuListener listener = discoveryListener;
            discovering = false;
            discoveryListener = null;
            if (listener != null) {
                listener.onMenuLabels(labels);
            }
            log("menu discovered items="
                    + (labels == null ? -1 : labels.length));
            return null;
        }
        String label = pendingLabel;
        if (label == null || SystemClock.elapsedRealtime() - pendingAt
                > PENDING_MENU_MILLIS || !(added instanceof View view)) {
            return chain.proceed();
        }
        // 第一个窗口就消费掉挂起状态，避免误拦后续无关弹窗
        pendingLabel = null;
        boolean handled = false;
        try {
            handled = captureAndInvoke(view, pendingLabels);
        } catch (Throwable throwable) {
            log("menu capture failed " + throwable.getClass().getSimpleName());
        }
        log("menu " + label + " " + (handled ? "invoked" : "not found")
                + " window=" + view.getClass().getSimpleName());
        // 找不到匹配项也不能放行：放行会把长按菜单弹出来。
        return null;
    }

    private static void installPopupHooks(XposedModule module) {
        String[][] targets = {
                {"showAsDropDown", "3"}, {"showAsDropDown", "1"}, {"showAtLocation", "4"}};
        for (String[] target : targets) {
            try {
                Method method = "showAsDropDown".equals(target[0])
                        ? ("3".equals(target[1])
                        ? PopupWindow.class.getDeclaredMethod("showAsDropDown",
                        View.class, int.class, int.class)
                        : PopupWindow.class.getDeclaredMethod("showAsDropDown", View.class))
                        : PopupWindow.class.getDeclaredMethod("showAtLocation",
                        View.class, int.class, int.class, int.class);
                method.setAccessible(true);
                module.hook(method)
                        .setId("actiongrid.phone.popup." + target[0] + target[1])
                        .intercept(chain -> {
                            String label = pendingLabel;
                            if (label == null || SystemClock.elapsedRealtime() - pendingAt
                                    > PENDING_MENU_MILLIS) {
                                return chain.proceed();
                            }
                            pendingLabel = null;
                            boolean handled = false;
                            try {
                                handled = captureAndInvoke(
                                        (PopupWindow) chain.getThisObject(), pendingLabels);
                            } catch (Throwable throwable) {
                                log("popup capture failed "
                                        + throwable.getClass().getSimpleName());
                            }
                            return handled ? null : chain.proceed();
                        });
            } catch (Throwable throwable) {
                log("popup hook " + target[0] + " unavailable");
            }
        }
    }

    interface MenuListener {
        void onMenuLabels(String[] labels);
    }

    /** 静默长按一次，只为读出官方菜单项；菜单不会显示。 */
    static boolean discoverMenu(View row, View origin, MenuListener listener) {
        discoveryListener = listener;
        discovering = true;
        pendingLabel = null;
        boolean clicked = pressForMenu(row, origin, true);
        if (DEBUG) {
            log("discover longpress handled=" + clicked);
        }
        // 菜单可能异步弹出：不能像同步那样立刻收摊，
        // 留出窗口期；超时则回调空标签（去重集会被清，下次可重试）。
        MAIN.postDelayed(() -> {
            if (discovering) {
                discovering = false;
                discoveryListener = null;
                log("discover timeout (no menu window)");
                if (listener != null) {
                    listener.onMenuLabels(null);
                }
            }
        }, DISCOVER_WINDOW_MILLIS);
        return clicked;
    }

    /**
     * 触发消息行的长按菜单。
     * 顺序：行根 → 聚焦点往上找监听器 → 子树 DFS。
     * 头像一律不按（群聊长按头像会往输入框插 @昵称），
     * 图片消息本体允许（用户聚焦的就是它，类名不带 Avatar）。
     */
    static boolean pressForMenu(View row, View origin, boolean allowDfs) {
        if (row.performLongClick()) {
            return true;
        }
        View current = origin;
        for (int i = 0; i < 10 && current != null; i++) {
            if (current != row && pressable(current)) {
                boolean pressed = current.performLongClick();
                if (DEBUG) {
                    log("press via " + current.getClass().getSimpleName()
                            + " -> " + pressed);
                }
                if (pressed) {
                    return true;
                }
            } else if (DEBUG && current != row
                    && current instanceof android.widget.ImageView
                    && !current.hasOnLongClickListeners()) {
                log("press skip img no-listener "
                        + current.getClass().getSimpleName());
            }
            if (current == row) {
                break;
            }
            current = current.getParent() instanceof View parent ? parent : null;
        }
        return allowDfs && dfsPress(row, 0);
    }

    /** 可长按判定：有监听器 + 非头像（图片消息本体算非头像）。 */
    private static boolean pressable(View view) {
        if (!view.hasOnLongClickListeners()) {
            return false;
        }
        return !(view instanceof android.widget.ImageView)
                || !view.getClass().getName().contains("Avatar");
    }

    /** 子树 DFS 找长按监听（跳过 ImageView）。 */
    private static boolean dfsPress(View root, int depth) {
        if (depth > 8 || !(root instanceof ViewGroup group)) {
            return false;
        }
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child == null || child instanceof android.widget.ImageView) {
                continue;
            }
            if (child.hasOnLongClickListeners() && child.performLongClick()) {
                return true;
            }
            if (dfsPress(child, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static String[] menuLabels(View window) {
        ListView list = findListView(window);
        if (list != null) {
            Adapter adapter = list.getAdapter();
            if (adapter != null) {
                java.util.List<String> labels = new java.util.ArrayList<>();
                for (int position = 0; position < adapter.getCount(); position++) {
                    String title = title(adapter.getItem(position));
                    if (title != null && !title.trim().isEmpty()) {
                        labels.add(title);
                    }
                }
                if (!labels.isEmpty()) {
                    return labels.toArray(new String[0]);
                }
            }
        }
        // 微信聊天菜单不是 ListView（MMMenuItem 对话框）：退回文本收集。
        java.util.List<String> texts = new java.util.ArrayList<>();
        collectTexts(window, texts, 0);
        return texts.toArray(new String[0]);
    }

    /** 收集窗口内所有 TextView 的文本（去重保序）。 */
    private static void collectTexts(View view, java.util.List<String> out, int depth) {
        if (view == null || depth > 15 || out.size() >= MENU_TEXT_LIMIT) {
            return;
        }
        if (view instanceof android.widget.TextView holder) {
            CharSequence value = holder.getText();
            String text = value == null ? "" : value.toString().trim();
            if (!text.isEmpty() && !out.contains(text)) {
                out.add(text);
            }
            return;
        }
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                collectTexts(group.getChildAt(index), out, depth + 1);
            }
        }
    }

    /** 触发这一行的官方长按菜单，并把 label 对应的那一项直接执行掉。 */
    static boolean runMenuAction(View row, View origin, String... labels) {
        pendingLabel = labels == null || labels.length == 0 ? "" : labels[0];
        pendingLabels = labels;
        pendingAt = SystemClock.elapsedRealtime();
        boolean clicked = pressForMenu(row, origin, true);
        boolean handled = pendingLabel == null;
        log("long press for " + pendingLabel + " " + clicked + " handled=" + handled);
        return handled;
    }

    private static boolean captureAndInvoke(Object window, String[] labels) {
        View content = window instanceof PopupWindow popup
                ? contentView(popup) : (View) window;
        if (content == null) {
            return false;
        }
        ListView list = findListView(content);
        if (list != null) {
            Adapter adapter = list.getAdapter();
            if (adapter != null) {
                for (int position = 0; position < adapter.getCount(); position++) {
                    Object item = adapter.getItem(position);
                    String title = normalize(title(item));
                    if (title.isEmpty()) {
                        continue;
                    }
                    for (String candidate : labels) {
                        if (title.equals(normalize(candidate))) {
                            return activate(list, adapter, position, item);
                        }
                    }
                }
            }
        }
        // 非 ListView 菜单：按文本找 TextView，点它的可点击祖先。
        for (String candidate : labels) {
            String want = normalize(candidate);
            if (want.isEmpty()) {
                continue;
            }
            View target = findClickableText(content, want, 0);
            if (target != null) {
                log("menu item via text: " + candidate);
                return target.performClick();
            }
        }
        return false;
    }

    /** 找文本匹配的 TextView；本身不可点就往上找可点击祖先。 */
    private static View findClickableText(View view, String want, int depth) {
        if (view == null || depth > 15) {
            return null;
        }
        if (view instanceof android.widget.TextView holder
                && !(holder instanceof android.widget.EditText)) {
            CharSequence value = holder.getText();
            if (value != null && normalize(value.toString()).equals(want)) {
                View current = holder;
                for (int i = 0; i < 5 && current != null; i++) {
                    if (current.isClickable()) {
                        return current;
                    }
                    current = current.getParent() instanceof View parent
                            ? parent : null;
                }
                return holder;
            }
            return null;
        }
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findClickableText(group.getChildAt(index), want, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean activate(ListView list, Adapter adapter, int position, Object item) {
        try {
            Method invoke = item.getClass().getMethod("invoke");
            invoke.setAccessible(true);
            Object result = invoke.invoke(item);
            if (!(result instanceof Boolean) || (Boolean) result) {
                return true;
            }
        } catch (Throwable ignored) {
            // 退回到 ListView 派发
        }
        try {
            return list.performItemClick(null, position, adapter.getItemId(position));
        } catch (Throwable throwable) {
            log("item click failed " + throwable.getClass().getSimpleName());
            return false;
        }
    }

    private static View contentView(PopupWindow window) {
        try {
            Field field = PopupWindow.class.getDeclaredField("mContentView");
            field.setAccessible(true);
            return (View) field.get(window);
        } catch (Throwable throwable) {
            return null;
        }
    }

    static ListView findListView(View view) {
        if (view instanceof ListView list) {
            return list;
        }
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                ListView found = findListView(group.getChildAt(index));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String title(Object item) {
        if (item instanceof android.view.MenuItem menuItem) {
            CharSequence value = menuItem.getTitle();
            return value == null ? null : value.toString();
        }
        return item == null ? null : item.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace(" ", "").replace("\u00A0", "");
    }

    private static void log(String message) {
        if (host == null) {
            return;
        }
        try {
            host.log(Log.INFO, TAG, message);
        } catch (RuntimeException ignored) {
            // 日志失败不影响功能
        }
    }
}
