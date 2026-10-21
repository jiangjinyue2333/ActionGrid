package io.github.jiangjinyue2333.actiongrid;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.jiangjinyue2333.actiongrid.diagnostics.DiagnosticsProvider;
import io.github.jiangjinyue2333.actiongrid.diagnostics.LogStore;
import io.github.jiangjinyue2333.actiongrid.hook.HyperOs4Compatibility;
import io.github.jiangjinyue2333.actiongrid.update.UpdateClient;
import io.github.jiangjinyue2333.actiongrid.update.UpdateDownloader;
import io.github.jiangjinyue2333.actiongrid.update.UpdateManifest;
import io.github.jiangjinyue2333.actiongrid.update.UpdateNotification;
import io.github.jiangjinyue2333.actiongrid.update.UpdateState;
import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;

public final class MainActivity extends Activity implements ActionGridApp.ServiceListener {
    private static final String TARGET_PACKAGE = "com.miui.home";
    private static final String SOURCE_REPOSITORY_URL =
            "https://github.com/jiangjinyue2333/ActionGrid";
    private static final int NOTIFICATION_PERMISSION_REQUEST = 1002;

    private LinearLayout statusPage;
    private LinearLayout aboutPage;
    private TextView moduleStatus;
    private TextView scopeStatus;
    private TextView homeStatus;
    private TextView desktopProcessStatus;
    private TextView statusMessage;
    private TextView aboutStatus;
    private Button openAbout;
    private Button restartHome;
    private Button checkUpdates;
    private Switch loggingSwitch;
    private Switch pageOrderSwitch;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updatePendingState(false);
        }
    };
    private boolean showingAbout;
    private boolean updateReceiverRegistered;
    private UpdateManifest pendingUpdateAfterNotificationPermission;
    private boolean homeRestartInProgress;
    private XposedService service;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        configureActions();
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::handleBack);
        refreshStatus();
        updateIgnoredStatus();
        if (ActionGridApp.consumeColdUpdateCheck()) {
            checkUpdates(false);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        ActionGridApp.addServiceListener(this);
        registerReceiver(updateReceiver,
                new IntentFilter(UpdateDownloader.ACTION_STATE_CHANGED), RECEIVER_NOT_EXPORTED);
        updateReceiverRegistered = true;
        updatePendingState(true);
    }

    @Override
    protected void onStop() {
        ActionGridApp.removeServiceListener(this);
        if (updateReceiverRegistered) {
            unregisterReceiver(updateReceiver);
            updateReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingUpdateAfterNotificationPermission != null
                && UpdateNotification.canNotify(this)) {
            UpdateManifest pending = pendingUpdateAfterNotificationPermission;
            pendingUpdateAfterNotificationPermission = null;
            beginDownload(pending);
        }
    }

    @Override
    protected void onDestroy() {
        backgroundExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onServiceChanged(XposedService value) {
        service = value;
        runOnUiThread(this::refreshStatus);
        if (value != null) {
            PageOrderSettings.pushToModule(value, this);
        }
    }

    private void bindViews() {
        statusPage = findViewById(R.id.status_page);
        aboutPage = findViewById(R.id.about_page);
        moduleStatus = findViewById(R.id.module_status_value);
        scopeStatus = findViewById(R.id.scope_status_value);
        homeStatus = findViewById(R.id.home_status_value);
        desktopProcessStatus = findViewById(R.id.desktop_process_status_value);
        statusMessage = findViewById(R.id.status_message);
        aboutStatus = findViewById(R.id.about_status);
        openAbout = findViewById(R.id.open_about);
        restartHome = findViewById(R.id.restart_home);
        checkUpdates = findViewById(R.id.check_updates);
        loggingSwitch = findViewById(R.id.logging_switch);
        pageOrderSwitch = findViewById(R.id.page_order_switch);
        ((TextView) findViewById(R.id.version_text)).setText(
                getString(R.string.version_format, BuildConfig.VERSION_NAME));
        loggingSwitch.setChecked(LogStore.isEnabled(this));
        pageOrderSwitch.setChecked(PageOrderSettings.isReversed(this));
    }

    private void configureActions() {
        findViewById(R.id.refresh_status).setOnClickListener(view -> refreshStatus());
        restartHome.setOnClickListener(view -> confirmHomeRestart());
        openAbout.setOnClickListener(view -> showAboutPage());
        findViewById(R.id.back_to_status).setOnClickListener(view -> showStatusPage());
        findViewById(R.id.open_source_repository)
                .setOnClickListener(view -> openSourceRepository());
        loggingSwitch.setOnCheckedChangeListener((button, enabled) -> {
            LogStore.setEnabled(this, enabled);
            aboutStatus.setText(enabled ? "已开始记录新日志" : "日志记录已关闭，旧日志已清除");
            findViewById(R.id.send_logs).setEnabled(enabled && LogStore.hasLogs(this));
        });
        pageOrderSwitch.setOnCheckedChangeListener((button, reversed) -> {
            PageOrderSettings.setReversed(this, reversed);
            if (!PageOrderSettings.pushToModule(service, this)) {
                statusMessage.setText(R.string.page_order_sync_failed);
                return;
            }
            statusMessage.setText(reversed
                    ? R.string.page_order_enabled : R.string.page_order_disabled);
        });
        findViewById(R.id.send_logs).setOnClickListener(view -> shareLogs());
        checkUpdates.setOnClickListener(view -> checkUpdates(true));
        findViewById(R.id.restore_update_prompts).setOnClickListener(view -> {
            UpdateState.restorePrompts(this);
            updateIgnoredStatus();
            aboutStatus.setText(R.string.update_prompts_restored);
        });
        findViewById(R.id.send_logs).setEnabled(LogStore.hasLogs(this));
    }

    private void confirmHomeRestart() {
        PackageInfo homeInfo = installedHomeInfo();
        if (isHyperOs4Home(homeInfo) && !isSupportedHyperOs4Home(homeInfo)) {
            statusMessage.setText("当前澎湃 4 桌面版本未验证，已关闭原生动作；重启桌面无法解决此问题");
            return;
        }
        if (homeRestartInProgress) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.restart_home)
                .setMessage(R.string.restart_home_confirmation)
                .setPositiveButton(R.string.restart_home_confirm,
                        (dialog, which) -> restartHome())
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(dialog -> {
                    if (!homeRestartInProgress) {
                        focusForAccessibility(restartHome);
                    }
                })
                .show();
    }

    private void restartHome() {
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(TARGET_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (home.resolveActivity(getPackageManager()) == null) {
            statusMessage.setText(R.string.restart_home_unavailable);
            focusForAccessibility(restartHome);
            return;
        }
        homeRestartInProgress = true;
        restartHome.setEnabled(false);
        statusMessage.setText(R.string.restarting_home);
        LogStore.append(this, "app", "用户已确认重启小米桌面");
        XposedService currentService = service;
        backgroundExecutor.execute(() -> {
            HomeRestarter.Result result = HomeRestarter.requestXiaomiHomeRestart(currentService);
            runOnUiThread(() -> finishHomeRestart(home, result));
        });
    }

    private void finishHomeRestart(Intent home, HomeRestarter.Result result) {
        if (isDestroyed()) {
            return;
        }
        homeRestartInProgress = false;
        restartHome.setEnabled(true);
        if (result != HomeRestarter.Result.REQUESTED) {
            statusMessage.setText(restartFailureMessage(result));
            LogStore.append(this, "app", "重启小米桌面失败 " + result);
            focusForAccessibility(restartHome);
            return;
        }
        statusMessage.setText(R.string.restart_home_success);
        LogStore.append(this, "app", "小米桌面进程已停止并准备重新启动");
        statusMessage.postDelayed(() -> {
            try {
                startActivity(home);
            } catch (RuntimeException exception) {
                statusMessage.setText(R.string.restart_home_failed);
                LogStore.append(this, "app", "重新打开小米桌面失败 "
                        + exception.getClass().getSimpleName());
                focusForAccessibility(restartHome);
            }
        }, 800L);
    }

    private int restartFailureMessage(HomeRestarter.Result result) {
        return switch (result) {
            case SERVICE_UNAVAILABLE -> R.string.restart_home_service_unavailable;
            case API_UNSUPPORTED -> R.string.restart_home_api_unsupported;
            case REMOTE_PREFERENCES_UNAVAILABLE -> R.string.restart_home_remote_unavailable;
            case SCOPE_MISSING -> R.string.restart_home_scope_missing;
            case MODULE_NOT_CURRENT -> R.string.restart_home_module_not_current;
            case WRITE_FAILED, REQUESTED -> R.string.restart_home_failed;
        };
    }

    private void refreshStatus() {
        PackageInfo homeInfo = installedHomeInfo();
        boolean hyperOs4 = isHyperOs4Home(homeInfo);
        String home = installedHomeVersion(homeInfo);
        setStatusRow(R.id.home_status_row, homeStatus, "小米桌面", home);
        String process = launcherProcessStatus(List.of());
        setStatusRow(R.id.desktop_process_status_row, desktopProcessStatus,
                getString(R.string.desktop_process_label), process);
        if (service == null) {
            setStatusRow(R.id.module_status_row, moduleStatus, "模块", "libxposed 服务未连接");
            setStatusRow(R.id.scope_status_row, scopeStatus, "作用域", "无法读取");
            statusMessage.setText(R.string.enable_module_hint);
            return;
        }
        try {
            int api = service.getApiVersion();
            List<HookedTarget> targets = api == 102 ? service.getRunningTargets() : List.of();
            process = launcherProcessStatus(targets);
            setStatusRow(R.id.desktop_process_status_row, desktopProcessStatus,
                    getString(R.string.desktop_process_label), process);
            List<String> scope = service.getScope();
            if (scope.contains("android")) {
                service.removeScope(List.of("android"));
                scope = service.getScope();
            }
            boolean homeScope = scope.contains(TARGET_PACKAGE);
            String scopeText = homeScope ? "仅小米桌面，已启用" : "缺少小米桌面范围";
            String running = runningTargetStatus(targets);
            String module = api == 102
                    ? hyperOs4 ? hyperOs4ActionStatus(homeInfo, homeScope, process)
                    : running
                    : "框架 API " + api + "，需要 API 102";
            setStatusRow(R.id.module_status_row, moduleStatus, "模块", module);
            setStatusRow(R.id.scope_status_row, scopeStatus, "作用域", scopeText);
            statusMessage.setText(api != 102 ? "框架 API 不兼容"
                    : hyperOs4 ? hyperOs4ActionMessage(homeInfo, homeScope, process)
                    : module);
        } catch (RuntimeException exception) {
            setStatusRow(R.id.module_status_row, moduleStatus, "模块", "读取失败");
            statusMessage.setText("读取模块状态失败");
            LogStore.append(this, "app", "读取框架状态失败 " + exception.getClass().getSimpleName());
        }
    }

    private String runningTargetStatus(List<HookedTarget> targets) {
        for (HookedTarget target : targets) {
            if (isTargetProcess(target.getProcessName())) {
                return target.getLoadedVersionCode() == BuildConfig.VERSION_CODE
                        ? "模块已加载" : "小米桌面仍在运行旧版模块";
            }
        }
        return "小米桌面未运行或模块尚未加载";
    }

    private String launcherProcessStatus(List<HookedTarget> targets) {
        if (targets != null) {
            for (HookedTarget target : targets) {
                if (isTargetProcess(target.getProcessName())) {
                    return "运行中";
                }
            }
        }
        try {
            ActivityManager manager = getSystemService(ActivityManager.class);
            if (manager != null) {
                List<ActivityManager.RunningAppProcessInfo> processes =
                        manager.getRunningAppProcesses();
                if (processes != null) {
                    for (ActivityManager.RunningAppProcessInfo process : processes) {
                        if (isTargetProcess(process.processName)) {
                            return "运行中";
                        }
                        if (process.pkgList != null) {
                            for (String packageName : process.pkgList) {
                                if (TARGET_PACKAGE.equals(packageName)) {
                                    return "运行中";
                                }
                            }
                        }
                    }
                }
            }
        } catch (RuntimeException exception) {
            LogStore.append(this, "app", "读取桌面进程状态失败 "
                    + exception.getClass().getSimpleName());
        }
        return "无法确认";
    }

    private String hyperOs4ActionStatus(PackageInfo info, boolean homeScope, String process) {
        if (!homeScope) {
            return "澎湃 4 原生动作：未启用小米桌面作用域";
        }
        if (!isSupportedHyperOs4Home(info)) {
            return "澎湃 4 原生动作：" + HyperOs4Compatibility.profileName(
                    info == null ? 0L : info.getLongVersionCode()) + "不支持";
        }
        String profile = HyperOs4Compatibility.profileName(info.getLongVersionCode());
        return switch (process) {
            case "运行中" -> "澎湃 4 原生动作：" + profile
                    + "，桌面进程运行，等待 native ABI 校验";
            case "未运行" -> "澎湃 4 原生动作：" + profile + "，已启用，等待桌面进程";
            default -> "澎湃 4 原生动作：" + profile
                    + "，已识别，native ABI 状态无法确认";
        };
    }

    private String hyperOs4ActionMessage(PackageInfo info, boolean homeScope, String process) {
        if (!homeScope) {
            return "澎湃 4 原生动作未启用：请勾选小米桌面作用域";
        }
        if (!isSupportedHyperOs4Home(info)) {
            return "当前澎湃 4 桌面版本（"
                    + HyperOs4Compatibility.profileName(
                    info == null ? 0L : info.getLongVersionCode())
                    + "）不在已验证 ABI 范围内，桌面动作已关闭";
        }
        String profile = HyperOs4Compatibility.profileName(info.getLongVersionCode());
        return switch (process) {
            case "运行中" -> "澎湃 4 原生动作（" + profile
                    + "）已识别；native_init 完成 ABI 校验后可用，请再用 TalkBack 测试";
            case "未运行" -> "澎湃 4 原生动作（" + profile + "）已启用；桌面进程启动后可用";
            default -> "澎湃 4 原生动作（" + profile + "）已识别；正在确认 native ABI 状态";
        };
    }

    private static boolean isSupportedHyperOs4Home(PackageInfo info) {
        return isHyperOs4Home(info) && HyperOs4Compatibility.supports(
                Build.VERSION.SDK_INT, info.getLongVersionCode(), false);
    }

    private static boolean isTargetProcess(String processName) {
        return TARGET_PACKAGE.equals(processName)
                || (processName != null && processName.startsWith(TARGET_PACKAGE + ":"));
    }

    private PackageInfo installedHomeInfo() {
        try {
            return getPackageManager().getPackageInfo(
                    TARGET_PACKAGE, PackageManager.PackageInfoFlags.of(0));
        } catch (PackageManager.NameNotFoundException exception) {
            return null;
        }
    }

    private static boolean isHyperOs4Home(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 37 && info != null && info.applicationInfo != null
                && (info.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_HAS_CODE)
                == 0;
    }

    private static String installedHomeVersion(PackageInfo info) {
        if (info == null) {
            return "未安装";
        }
        String family = isHyperOs4Home(info) ? "澎湃 4 原生桌面（"
                + HyperOs4Compatibility.profileName(info.getLongVersionCode()) + "）"
                : info.versionName != null && info.versionName.startsWith("7.")
                ? "7.x Java 桥（自动）" : "4.x/兼容探测（自动）";
        return "版本 " + info.versionName + "，" + family;
    }

    private void setStatusRow(int rowId, TextView valueView, String label, String value) {
        valueView.setText(value);
        findViewById(rowId).setContentDescription(label + "，" + value);
    }

    private void showAboutPage() {
        showingAbout = true;
        statusPage.setVisibility(View.GONE);
        aboutPage.setVisibility(View.VISIBLE);
        updateIgnoredStatus();
        focusForAccessibility(findViewById(R.id.back_to_status));
    }

    private void showStatusPage() {
        showingAbout = false;
        aboutPage.setVisibility(View.GONE);
        statusPage.setVisibility(View.VISIBLE);
        focusForAccessibility(openAbout);
    }

    private void handleBack() {
        if (showingAbout) {
            showStatusPage();
        } else {
            finish();
        }
    }

    @SuppressLint("AccessibilityFocus")
    private static void focusForAccessibility(View view) {
        view.post(() -> {
            view.requestFocus();
            view.performAccessibilityAction(
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
        });
    }

    private void shareLogs() {
        if (!LogStore.hasLogs(this)) {
            aboutStatus.setText(R.string.no_logs);
            return;
        }
        LogStore.append(this, "app", "用户请求发送日志");
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, DiagnosticsProvider.LOG_URI)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(LogStore.FILE_NAME,
                DiagnosticsProvider.LOG_URI));
        Intent chooser = Intent.createChooser(send, getString(R.string.share_logs))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(chooser);
    }

    private void openSourceRepository() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_REPOSITORY_URL)));
        } catch (RuntimeException exception) {
            aboutStatus.setText(R.string.source_repository_unavailable);
        }
    }

    private void checkUpdates(boolean manual) {
        checkUpdates.setEnabled(false);
        if (manual) {
            aboutStatus.setText(R.string.checking_updates);
        }
        backgroundExecutor.execute(() -> {
            try {
                UpdateManifest manifest = UpdateClient.fetch();
                runOnUiThread(() -> handleUpdateResult(manifest, manual));
            } catch (IOException | IllegalArgumentException exception) {
                LogStore.append(this, "app", "更新检查失败 "
                        + exception.getClass().getSimpleName());
                runOnUiThread(() -> {
                    checkUpdates.setEnabled(true);
                    if (manual) {
                        aboutStatus.setText(R.string.update_failed);
                    }
                });
            }
        });
    }

    private void handleUpdateResult(UpdateManifest manifest, boolean manual) {
        checkUpdates.setEnabled(true);
        if (isDestroyed()) {
            return;
        }
        if (manifest.versionCode() <= BuildConfig.VERSION_CODE) {
            if (manual) {
                aboutStatus.setText(R.string.latest_version);
            }
            return;
        }
        if (!manual && UpdateState.ignoredVersion(this) == manifest.versionCode()) {
            return;
        }
        String changelog = manifest.changelog().isEmpty()
                ? "暂无更新说明"
                : "• " + String.join("\n• ", manifest.changelog());
        new AlertDialog.Builder(this)
                .setTitle("ActionGrid 更新")
                .setMessage(getString(R.string.update_available, manifest.versionName(), changelog))
                .setNeutralButton(R.string.update_now,
                        (dialog, which) -> startDownload(manifest))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ignore_version, (dialog, which) -> {
                    UpdateState.ignore(this, manifest.versionCode());
                    updateIgnoredStatus();
                })
                .show();
    }

    private void startDownload(UpdateManifest manifest) {
        if (!UpdateNotification.canNotify(this)) {
            pendingUpdateAfterNotificationPermission = manifest;
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                aboutStatus.setText(R.string.update_notification_permission);
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_REQUEST);
            } else {
                aboutStatus.setText(R.string.update_notifications_disabled);
                try {
                    startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                } catch (RuntimeException exception) {
                    pendingUpdateAfterNotificationPermission = null;
                    focusForAccessibility(checkUpdates);
                }
            }
            return;
        }
        beginDownload(manifest);
    }

    private void beginDownload(UpdateManifest manifest) {
        try {
            UpdateDownloader.enqueue(this, manifest);
            aboutStatus.setText(R.string.downloading_update);
            LogStore.append(this, "app", "用户已开始下载更新");
        } catch (IOException | RuntimeException exception) {
            aboutStatus.setText(R.string.download_failed);
            LogStore.append(this, "app", "开始下载失败 "
                    + exception.getClass().getSimpleName());
        }
    }

    private void updateIgnoredStatus() {
        int ignored = UpdateState.ignoredVersion(this);
        TextView text = findViewById(R.id.ignored_version);
        View restore = findViewById(R.id.restore_update_prompts);
        if (ignored <= 0) {
            text.setVisibility(View.GONE);
            restore.setVisibility(View.GONE);
        } else {
            text.setText(getString(R.string.update_ignored, ignored));
            text.setVisibility(View.VISIBLE);
            restore.setVisibility(View.VISIBLE);
        }
    }

    private void updatePendingState(boolean ensureNotification) {
        String error = UpdateState.consumeError(this);
        if (error != null) {
            aboutStatus.setText(R.string.download_failed);
            LogStore.append(this, "app", "更新校验失败 " + error);
        }
        File verified = UpdateState.verifiedFile(this);
        if (verified == null) {
            return;
        }
        aboutStatus.setText(R.string.update_ready_notification);
        if (ensureNotification) {
            UpdateNotification.showReady(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != NOTIFICATION_PERMISSION_REQUEST) {
            return;
        }
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && pendingUpdateAfterNotificationPermission != null
                && UpdateNotification.canNotify(this)) {
            UpdateManifest pending = pendingUpdateAfterNotificationPermission;
            pendingUpdateAfterNotificationPermission = null;
            beginDownload(pending);
            return;
        }
        if (grantResults.length == 0
                || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            pendingUpdateAfterNotificationPermission = null;
            aboutStatus.setText(R.string.update_notification_permission_denied);
            focusForAccessibility(checkUpdates);
        } else {
            aboutStatus.setText(R.string.update_notifications_disabled);
        }
    }
}
