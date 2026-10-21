package io.github.jiangjinyue2333.actiongrid.hook;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

public final class ActionGridModule extends XposedModule {
    private static final String LAUNCHER_PACKAGE = "com.miui.home";
    private static final String PHONE_PACKAGE = "com.android.contacts";
    private static final String SMS_PACKAGE = "com.android.mms";
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final String TAG = "ActionGrid";

    private String targetPackage;
    private boolean targetProcess;
    private boolean packageAccepted;
    private final Set<String> loggedFailures = new HashSet<>();
    private SharedPreferences restartPreferences;
    private SharedPreferences.OnSharedPreferenceChangeListener restartListener;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        String process = param.getProcessName();
        targetPackage = LAUNCHER_PACKAGE.equals(process) ? LAUNCHER_PACKAGE
                : PHONE_PACKAGE.equals(process) ? PHONE_PACKAGE
                : SMS_PACKAGE.equals(process) ? SMS_PACKAGE
                : WECHAT_PACKAGE.equals(process) ? WECHAT_PACKAGE : null;
        targetProcess = targetPackage != null && !param.isSystemServer();
        Log.i(TAG, "onModuleLoaded process=" + param.getProcessName()
                + " target=" + targetProcess);
        if (targetProcess) {
            listenForRestartRequests();
        }
    }

    private void listenForRestartRequests() {
        try {
            restartPreferences = getRemotePreferences(HomeRestartProtocol.PREFERENCES_GROUP);
            restartListener = (preferences, key) -> {
                if (!HomeRestartProtocol.REQUEST_NONCE.equals(key)) {
                    return;
                }
                log(Log.INFO, TAG, "Restart requested by module app");
                Process.killProcess(Process.myPid());
            };
            restartPreferences.registerOnSharedPreferenceChangeListener(restartListener);
        } catch (RuntimeException exception) {
            log(Log.WARN, TAG, "Restart listener unavailable: "
                    + exception.getClass().getSimpleName());
        }
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        Log.i(TAG, "onPackageLoaded package=" + param.getPackageName()
                + " targetProcess=" + targetProcess);
        if (targetProcess && targetPackage != null
                && targetPackage.equals(param.getPackageName())) {
            packageAccepted = true;
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        Log.i(TAG, "onPackageReady package=" + param.getPackageName()
                + " accepted=" + packageAccepted);
        if (!packageAccepted || targetPackage == null
                || !targetPackage.equals(param.getPackageName())) {
            return;
        }
        if (WECHAT_PACKAGE.equals(targetPackage)) {
            boolean installed = PhoneCallLogAdapter.install(this, param.getClassLoader());
            log(installed ? Log.INFO : Log.ERROR, TAG,
                    "WeChat adapter " + (installed ? "installed" : "unavailable"));
            return;
        }
        if (SMS_PACKAGE.equals(targetPackage)) {
            boolean installed = PhoneCallLogAdapter.install(this, param.getClassLoader());
            log(installed ? Log.INFO : Log.ERROR, TAG,
                    "SMS adapter " + (installed ? "installed" : "unavailable"));
            return;
        }
        if (PHONE_PACKAGE.equals(targetPackage)) {
            boolean installed = PhoneCallLogAdapter.install(this, param.getClassLoader());
            log(installed ? Log.INFO : Log.ERROR, TAG,
                    "Phone call log adapter " + (installed ? "installed" : "unavailable"));
            return;
        }
        try {
            ApplicationInfo application = param.getApplicationInfo();
            boolean hasCode = application != null
                    && (application.flags & ApplicationInfo.FLAG_HAS_CODE) != 0;
            long version = applicationVersion(application);
            // 页码指示器翻页方向独立于两条整理路径，先装，避免后续 profile 不匹配时一起失效
            boolean pageOrder = PageOrderHook.install(this);
            log(pageOrder ? Log.INFO : Log.WARN, TAG,
                    "Page order hooks " + (pageOrder ? "installed" : "unavailable"));
            boolean nativeCandidate = HyperOs4Compatibility.isNativeCandidate(
                    Build.VERSION.SDK_INT, version, hasCode);
            if (nativeCandidate) {
                if (!HyperOs4Compatibility.supports(Build.VERSION.SDK_INT, version, hasCode)) {
                    log(Log.WARN, TAG, "HyperOS 4 native profile unsupported; legacy hooks disabled"
                            + " version=" + version);
                    return;
                }
                // Native placement is preferred, but Java icon actions remain the fallback.
                Log.i(TAG, "HyperOS4 native placement path selected"
                        + " sdk=" + Build.VERSION.SDK_INT
                        + " version=" + version + " hasCode=" + hasCode);
                boolean installed = HyperOs4NodeAdapter.installApplicationActions(this);
                log(installed ? Log.INFO : Log.ERROR, TAG,
                        "HyperOS 4 native profile " + HyperOs4Compatibility.profileName(version)
                                + "; Java application actions "
                                + (installed ? "installed" : "unavailable"));
                return;
            }
            CompatibilityReport report = CapabilityProbe.probe(param.getClassLoader());
            log(Log.INFO, TAG, "Probe " + report.summary());
            if (!report.availableCapabilities().contains(Capability.ICON_BINDING)) {
                return;
            }
            MiuiHomeBridge bridge = MiuiHomeBridge.resolve(
                    param.getClassLoader(), report.bridgeFamily());
            ActionController controller = new ActionController(bridge);
            installIconHooks(controller, bridge);
        } catch (Throwable throwable) {
            Log.e(TAG, "Initialization failed", throwable);
            log(Log.ERROR, TAG, "Initialization failed: "
                    + throwable.getClass().getSimpleName());
        }
    }

    private void installIconHooks(ActionController controller, MiuiHomeBridge bridge)
            throws NoSuchMethodException {
        Method setTag = View.class.getMethod("setTag", Object.class);
        hook(setTag).setId("actiongrid.legacy.bind.v1").intercept(chain -> {
            Object result = chain.proceed();
            if (chain.getThisObject() instanceof View view && controller.isSupportedIcon(view)) {
                view.post(() -> runSafely("bind", () -> controller.onBound(view)));
            }
            return result;
        });

        Method attached = View.class.getDeclaredMethod("onAttachedToWindow");
        attached.setAccessible(true);
        hook(attached).setId("actiongrid.legacy.attach.v1").intercept(chain -> {
            Object result = chain.proceed();
            if (chain.getThisObject() instanceof View view && controller.isSupportedIcon(view)) {
                view.post(() -> runSafely("attach", () -> controller.onBound(view)));
            }
            return result;
        });

        Method createNode = View.class.getMethod("createAccessibilityNodeInfo");
        hook(createNode).setId("actiongrid.legacy.node.v1").intercept(chain -> {
            Object result = chain.proceed();
            if (chain.getThisObject() instanceof View view
                    && result instanceof AccessibilityNodeInfo info) {
                runSafely("node", () -> controller.onInitializeNodeInfo(view, info));
            }
            return result;
        });

        Method performAction = View.class.getMethod(
                "performAccessibilityAction", int.class, android.os.Bundle.class);
        hook(performAction).setId("actiongrid.legacy.action.v1").intercept(chain -> {
            if (chain.getThisObject() instanceof View view) {
                try {
                    if (controller.performAccessibilityAction(view, (int) chain.getArg(0))) {
                        return true;
                    }
                } catch (RuntimeException exception) {
                    logHookFailure("action", exception);
                }
            }
            return chain.proceed();
        });

        Method getProvider = View.class.getMethod("getAccessibilityNodeProvider");
        hook(getProvider).setId("actiongrid.legacy.provider.v1").intercept(chain -> {
            Object result = chain.proceed();
            if (chain.getThisObject() instanceof View view) {
                try {
                    return controller.positionProvider(view,
                            result instanceof AccessibilityNodeProvider provider ? provider : null);
                } catch (RuntimeException exception) {
                    logHookFailure("provider", exception);
                }
            }
            return result;
        });

        Set<Method> hoverMethods = new HashSet<>();
        for (Class<?> hostClass : bridge.placementHostClasses()) {
            hoverMethods.add(hostClass.getMethod("dispatchHoverEvent", MotionEvent.class));
        }
        for (Method hoverMethod : hoverMethods) {
            hook(hoverMethod).setId("actiongrid.legacy.hover."
                    + hoverMethod.getDeclaringClass().getName()).intercept(chain -> {
                if (chain.getThisObject() instanceof View view) {
                    try {
                        if (controller.dispatchHoverEvent(
                                view, (MotionEvent) chain.getArg(0))) {
                            return true;
                        }
                    } catch (RuntimeException exception) {
                        logHookFailure("hover", exception);
                    }
                }
                return chain.proceed();
            });
        }
    }

    private void runSafely(String stage, Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException exception) {
            logHookFailure(stage, exception);
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private static long applicationVersion(ApplicationInfo application) {
        if (application == null) {
            return 0L;
        }
        try {
            return ((Number) ApplicationInfo.class.getMethod(
                    "getLongVersionCode").invoke(application)).longValue();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            try {
                var field = ApplicationInfo.class.getDeclaredField("versionCode");
                field.setAccessible(true);
                return ((Number) field.get(application)).longValue();
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                return 0L;
            }
        }
    }

    private synchronized void logHookFailure(String stage, RuntimeException exception) {
        String key = stage + ':' + exception.getClass().getName();
        if (loggedFailures.add(key)) {
            log(Log.WARN, TAG, stage + " failed: " + exception.getClass().getSimpleName());
        }
    }
}
