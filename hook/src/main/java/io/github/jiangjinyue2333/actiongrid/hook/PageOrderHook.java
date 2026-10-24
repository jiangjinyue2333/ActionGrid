package io.github.jiangjinyue2333.actiongrid.hook;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/** PR #2 page indicator direction option, installed only for the Java 7.x launcher. */
final class PageOrderHook implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "ActionGrid-PageOrder";
    private static PageOrderHook installed;

    private final XposedModule module;
    private final Method sourceNodeId;
    private final PageIndicatorOrder order = new PageIndicatorOrder();
    private final SharedPreferences preferences;
    private volatile boolean active;

    private PageOrderHook(XposedModule module, Method sourceNodeId) {
        this.module = module;
        this.sourceNodeId = sourceNodeId;
        preferences = module.getRemotePreferences(PageOrderProtocol.PREFERENCES_GROUP);
        preferences.registerOnSharedPreferenceChangeListener(this);
        updatePreference();
    }

    static synchronized boolean install(XposedModule module) {
        if (installed != null) {
            return true;
        }
        PageOrderHook candidate = null;
        try {
            Class<?> controllerClass =
                    Class.forName("android.view.AccessibilityInteractionController");
            Class<?> callbackClass = Class.forName(
                    "android.view.accessibility.IAccessibilityInteractionConnectionCallback");
            Method sourceNodeId = HookMethodLookup.find(
                    AccessibilityNodeInfo.class, "getSourceNodeId");
            Method singleResult = HookMethodLookup.find(controllerClass,
                    "returnFindNodeResult", AccessibilityNodeInfo.class, callbackClass, int.class);
            Method listResult = HookMethodLookup.find(controllerClass,
                    "returnFindNodesResult", List.class, callbackClass, int.class);
            Method performAction = HookMethodLookup.find(controllerClass,
                    "performAccessibilityActionClientThread", long.class, int.class,
                    Bundle.class, int.class, callbackClass, int.class, int.class, long.class);
            if (sourceNodeId == null || singleResult == null
                    || listResult == null || performAction == null) {
                module.log(Log.WARN, TAG, "Page order unavailable: framework method mismatch");
                return false;
            }
            candidate = new PageOrderHook(module, sourceNodeId);
            PageOrderHook adapter = candidate;
            module.hook(singleResult).setId("actiongrid.pageorder.node.single.v1")
                    .intercept(chain -> {
                        adapter.observe(chain.getThisObject(), chain.getArg(0));
                        return chain.proceed();
                    });
            module.hook(listResult).setId("actiongrid.pageorder.node.list.v1")
                    .intercept(chain -> {
                        adapter.observe(chain.getThisObject(), chain.getArg(0));
                        return chain.proceed();
                    });
            Method prefetchResult = HookMethodLookup.find(controllerClass,
                    "returnPrefetchResult", int.class, List.class, callbackClass);
            if (prefetchResult != null) {
                try {
                    module.hook(prefetchResult).setId("actiongrid.pageorder.node.prefetch.v1")
                            .intercept(chain -> {
                                adapter.observe(chain.getThisObject(), chain.getArg(1));
                                return chain.proceed();
                            });
                } catch (RuntimeException | LinkageError exception) {
                    module.log(Log.WARN, TAG, "Optional page order prefetch hook unavailable");
                }
            }
            module.hook(performAction).setId("actiongrid.pageorder.action.v1")
                    .intercept(chain -> {
                        int mapped = adapter.active ? adapter.order.mapAction(
                                chain.getThisObject(), (long) chain.getArg(0),
                                (int) chain.getArg(1), SystemClock.elapsedRealtime()) : -1;
                        if (mapped >= 0) {
                            Object[] forwarded = chain.getArgs().toArray();
                            forwarded[1] = mapped;
                            return chain.proceed(forwarded);
                        }
                        return chain.proceed();
                    });
            adapter.active = true;
            installed = adapter;
            return true;
        } catch (Throwable throwable) {
            if (candidate != null) {
                try {
                    candidate.preferences.unregisterOnSharedPreferenceChangeListener(candidate);
                } catch (RuntimeException ignored) {
                    // Any partially installed hooks stay inactive.
                }
            }
            module.log(Log.WARN, TAG, "Page order unavailable: "
                    + throwable.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        if (key == null || PageOrderProtocol.KEY_REVERSED.equals(key)) {
            updatePreference();
        }
    }

    private void updatePreference() {
        try {
            order.setReversed(preferences.getBoolean(PageOrderProtocol.KEY_REVERSED, false));
        } catch (RuntimeException exception) {
            order.setReversed(false);
            module.log(Log.WARN, TAG, "Page order preferences unavailable");
        }
    }

    private void observe(Object controller, Object value) {
        if (!active) {
            return;
        }
        if (value instanceof List<?> nodes) {
            for (Object node : nodes) {
                observe(controller, node);
            }
            return;
        }
        if (!(value instanceof AccessibilityNodeInfo node)) {
            return;
        }
        try {
            CharSequence packageName = node.getPackageName();
            boolean indicator = packageName != null && "com.miui.home".contentEquals(packageName)
                    && (node.isScrollable() || node.getActionList().stream().anyMatch(action ->
                    action.getId() == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                            || action.getId() == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
                    && (PageIndicatorOrder.isIndicator(node.getContentDescription())
                    || PageIndicatorOrder.isIndicator(node.getText()));
            long id = ((Number) sourceNodeId.invoke(node)).longValue();
            order.observe(controller, node.getWindowId(), id, indicator,
                    SystemClock.elapsedRealtime());
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Reading a node must never prevent the original accessibility response.
        }
    }
}
