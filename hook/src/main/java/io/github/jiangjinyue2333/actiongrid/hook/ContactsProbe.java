package io.github.jiangjinyue2333.actiongrid.hook;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Adapter;
import android.widget.ListView;
import android.widget.TextView;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/** 只读探针：打印长按行结构与带列表的弹窗内容（开发期用）。 */
final class ContactsProbe {
    private static final String TAG = "ActionGrid-Probe";
    private static final int MAX_DEPTH = 6;

    private ContactsProbe() {
    }

    static void install(XposedModule module) {
        try {
            Method longClick = View.class.getDeclaredMethod("performLongClick");
            longClick.setAccessible(true);
            module.hook(longClick)
                    .setId("actiongrid.probe.longclick.v4")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            View anchor = (View) chain.getThisObject();
                            log(module, "longClick " + describe(anchor));
                            walk(module, anchor, 1);
                        } catch (Throwable throwable) {
                            log(module, "longClick dump failed " + throwable);
                        }
                        return result;
                    });

            Class<?> impl = Class.forName("android.view.WindowManagerImpl");
            Method addView = impl.getDeclaredMethod(
                    "addView", View.class, ViewGroup.LayoutParams.class);
            addView.setAccessible(true);
            module.hook(addView)
                    .setId("actiongrid.probe.addview.v4")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            if (chain.getArg(0) instanceof View view) {
                                dumpMenus(module, view);
                            }
                        } catch (Throwable throwable) {
                            log(module, "addView dump failed " + throwable);
                        }
                        return result;
                    });
            log(module, "probe installed");
        } catch (Throwable throwable) {
            log(module, "probe failed " + throwable.getClass().getSimpleName());
        }
    }

    private static void dumpMenus(XposedModule module, View view) {
        ListView list = PhoneMenuBridge.findListView(view);
        if (list == null) {
            return;
        }
        Adapter adapter = list.getAdapter();
        if (adapter == null || adapter.getCount() == 0) {
            return;
        }
        log(module, "menu count=" + adapter.getCount() + " window="
                + view.getClass().getSimpleName());
        for (int index = 0; index < adapter.getCount(); index++) {
            Object item = adapter.getItem(index);
            String title = item instanceof android.view.MenuItem menuItem
                    ? String.valueOf(menuItem.getTitle()) : String.valueOf(item);
            log(module, "  item[" + index + "] " + title);
        }
    }

    private static void walk(XposedModule module, View view, int depth) {
        if (view == null || depth > MAX_DEPTH) {
            return;
        }
        String text = view instanceof TextView
                ? String.valueOf(((TextView) view).getText()) : "";
        CharSequence description = view.getContentDescription();
        log(module, indent(depth) + view.getClass().getSimpleName()
                + " text=" + text
                + " desc=" + (description == null ? "" : description)
                + " clickable=" + view.isClickable()
                + " id=" + resourceId(view));
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                walk(module, group.getChildAt(index), depth + 1);
            }
        }
    }

    private static String describe(View view) {
        return view == null ? "null" : view.getClass().getSimpleName() + " id=" + resourceId(view);
    }

    private static String resourceId(View view) {
        int id = view.getId();
        if (id == View.NO_ID) {
            return "-";
        }
        try {
            return view.getResources().getResourceEntryName(id);
        } catch (RuntimeException exception) {
            return "0x" + Integer.toHexString(id);
        }
    }

    private static String indent(int depth) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < depth; index++) {
            builder.append("  ");
        }
        return builder.toString();
    }

    private static void log(XposedModule module, String message) {
        try {
            module.log(Log.INFO, TAG, message);
        } catch (RuntimeException ignored) {
            // 探针日志失败无需处理
        }
    }
}
