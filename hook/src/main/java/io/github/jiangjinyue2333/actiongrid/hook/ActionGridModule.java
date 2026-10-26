package io.github.jiangjinyue2333.actiongrid.hook;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

public final class ActionGridModule extends XposedModule {
    private static final String TARGET_PACKAGE = "com.miui.home";
    private static final String TAG = "ActionGrid";

    private boolean targetProcess;
    private boolean packageAccepted;
    private final Set<String> loggedFailures = new HashSet<>();
    private SharedPreferences restartPreferences;
    private SharedPreferences.OnSharedPreferenceChangeListener restartListener;
    private final ThreadLocal<Integer> systemClickCount = ThreadLocal.withInitial(() -> 0);

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        targetProcess = TARGET_PACKAGE.equals(param.getProcessName()) && !param.isSystemServer();
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
        if (targetProcess && TARGET_PACKAGE.equals(param.getPackageName())) {
            packageAccepted = true;
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        Log.i(TAG, "onPackageReady package=" + param.getPackageName()
                + " accepted=" + packageAccepted);
        if (!packageAccepted || !TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        try {
            ApplicationInfo application = param.getApplicationInfo();
            boolean hasCode = application != null
                    && (application.flags & ApplicationInfo.FLAG_HAS_CODE) != 0;
            long version = applicationVersion(application);
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
            if (PageOrderProtocol.supports(version, hasCode)) {
                boolean pageOrder = PageOrderHook.install(this);
                log(pageOrder ? Log.INFO : Log.WARN, TAG,
                        "HyperOS 3 page order hooks " + (pageOrder ? "installed" : "unavailable"));
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

        Method playSound = View.class.getMethod("playSoundEffect", int.class);
        hook(playSound).setId("actiongrid.legacy.sound.v1").intercept(chain -> {
            if ((int) chain.getArg(0) == SoundEffectConstants.CLICK) {
                systemClickCount.set(systemClickCount.get() + 1);
            }
            return chain.proceed();
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
                int soundBefore = systemClickCount.get();
                Object result = chain.proceed();
                if ((int) chain.getArg(0) == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                        && result instanceof Boolean handled && handled
                        && bridge.isSupportedIcon(view)) {
                    if (systemClickCount.get() == soundBefore) {
                        view.playSoundEffect(SoundEffectConstants.CLICK);
                    }
                    view.sendAccessibilityEvent(
                            android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
                }
                return result;
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
            Method hover = HookMethodLookup.find(hostClass, "dispatchHoverEvent", MotionEvent.class);
            if (hover != null) {
                hoverMethods.add(hover);
            } else {
                log(Log.WARN, TAG, "Optional hover hook unavailable: " + hostClass.getName());
            }
        }
        for (Method hoverMethod : hoverMethods) {
            try {
                hook(hoverMethod).setId("actiongrid.legacy.hover."
                        + hoverMethod.getDeclaringClass().getName()).intercept(chain -> {
                    if (chain.getThisObject() instanceof View view && bridge.isPlacementHost(view)) {
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
            } catch (RuntimeException | LinkageError exception) {
                logHookFailure("hover-install", exception);
            }
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

    private synchronized void logHookFailure(String stage, Throwable exception) {
        String key = stage + ':' + exception.getClass().getName();
        if (loggedFailures.add(key)) {
            log(Log.WARN, TAG, stage + " failed: " + exception.getClass().getSimpleName());
        }
    }
}
