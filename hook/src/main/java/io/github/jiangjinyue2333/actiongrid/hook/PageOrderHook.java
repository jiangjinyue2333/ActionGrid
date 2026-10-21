package io.github.jiangjinyue2333.actiongrid.hook;

import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * 页码指示器翻页方向钩子。
 *
 * 独立于「澎湃 4 原生放置」和「旧版 7.x 图标绑定」两条路径安装：
 * 只依赖 AccessibilityInteractionController 的两个客户端线程方法，
 * 因此桌面版本不在已知 profile 列表里时也能工作。
 */
final class PageOrderHook {
    private static final String TAG = "ActionGrid-PageOrder";

    private static boolean installed;

    private PageOrderHook() {
    }

    static synchronized boolean install(XposedModule module) {
        if (installed) {
            return true;
        }
        try {
            Class<?> controllerClass =
                    Class.forName("android.view.AccessibilityInteractionController");
            Class<?> callbackClass = Class.forName(
                    "android.view.accessibility.IAccessibilityInteractionConnectionCallback");
            Method sourceNodeId = AccessibilityNodeInfo.class.getDeclaredMethod("getSourceNodeId");
            sourceNodeId.setAccessible(true);
            PageIndicatorOrder order = new PageIndicatorOrder(module, sourceNodeId);

            Method returnSingleResult = accessible(controllerClass.getDeclaredMethod(
                    "returnFindNodeResult", AccessibilityNodeInfo.class, callbackClass, int.class));
            Method returnListResult = accessible(controllerClass.getDeclaredMethod(
                    "returnFindNodesResult", List.class, callbackClass, int.class));
            Method performActionClientThread = accessible(controllerClass.getDeclaredMethod(
                    "performAccessibilityActionClientThread", long.class, int.class,
                    Bundle.class, int.class, callbackClass, int.class, int.class, long.class));

            module.hook(returnSingleResult)
                    .setId("actiongrid.pageorder.node.single.v1")
                    .intercept(chain -> {
                        Object value = chain.getArg(0);
                        if (value instanceof AccessibilityNodeInfo node) {
                            order.observe(node);
                        }
                        return chain.proceed();
                    });
            module.hook(returnListResult)
                    .setId("actiongrid.pageorder.node.list.v1")
                    .intercept(chain -> {
                        Object value = chain.getArg(0);
                        if (value instanceof List<?> nodes) {
                            for (Object item : nodes) {
                                if (item instanceof AccessibilityNodeInfo node) {
                                    order.observe(node);
                                }
                            }
                        }
                        return chain.proceed();
                    });
            module.hook(performActionClientThread)
                    .setId("actiongrid.pageorder.action.v1")
                    .intercept(chain -> {
                        long nodeId = (long) chain.getArg(0);
                        int action = (int) chain.getArg(1);
                        int mapped = order.mapAction(nodeId, action);
                        if (mapped >= 0) {
                            Object[] forwarded = chain.getArgs().toArray();
                            forwarded[1] = mapped;
                            return chain.proceed(forwarded);
                        }
                        return chain.proceed();
                    });
            installed = true;
            module.log(android.util.Log.INFO, TAG, "page order hooks installed");
            return true;
        } catch (Throwable throwable) {
            if (module != null) {
                module.log(android.util.Log.ERROR, TAG,
                        "page order hooks unavailable: "
                                + throwable.getClass().getSimpleName()
                                + ": " + throwable.getMessage());
            }
            return false;
        }
    }

    private static Method accessible(Method method) {
        method.setAccessible(true);
        return method;
    }

}
