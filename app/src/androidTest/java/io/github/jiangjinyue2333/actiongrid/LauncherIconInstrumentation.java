package io.github.jiangjinyue2333.actiongrid;

import android.app.Activity;
import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Switch;
import android.widget.TextView;

/** Runs the real switch and confirmation against the installed package; restores the initial state. */
public final class LauncherIconInstrumentation extends Instrumentation {
    private static final String SETTINGS_CATEGORY = "de.robv.android.xposed.category.MODULE_SETTINGS";
    private MainActivity activity;
    private Switch toggle;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        int resultCode = Activity.RESULT_CANCELED;
        PackageManager pm = getTargetContext().getPackageManager();
        ComponentName alias = new ComponentName(getTargetContext(),
                getTargetContext().getPackageName() + ".LauncherAlias");
        int originalState = pm.getComponentEnabledSetting(alias);
        try {
            // Keep the unrelated cold-start update dialog out of this UI regression.
            ActionGridApp.consumeColdUpdateCheck();
            AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
            getUiAutomation().setServiceInfo(info);
            pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    PackageManager.DONT_KILL_APP);
            openApp(Intent.CATEGORY_LAUNCHER);
            check(launcherCount() == 1, "fresh/default state must have one launcher entry");
            check(!toggle.isChecked() && toggle.isEnabled(), "default switch state");
            runOnMainSync(() -> toggle.performAccessibilityAction(
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
            waitForIdleSync();
            check(toggle.isAccessibilityFocused(), "initial accessibility focus missing");
            check(pm.getComponentEnabledSetting(alias) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    "focus changed state");

            clickToggle();
            check(!toggle.isChecked() && launcherCount() == 1, "hidden before confirmation");
            clickDialogButton("android:id/button2");
            check(!toggle.isChecked() && toggle.isAccessibilityFocused(), "cancel lost state or focus: checked="
                    + toggle.isChecked() + " keyboard=" + toggle.isFocused()
                    + " accessibility=" + toggle.isAccessibilityFocused()
                    + " window=" + toggle.hasWindowFocus());
            check(pm.getComponentEnabledSetting(alias) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    "cancel changed state");
            clickToggle();
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            waitForMainWindow();
            check(!toggle.isChecked() && launcherCount() == 1, "Back confirmed hiding");

            clickToggle();
            clickDialogButton("android:id/button1");
            check(pm.getComponentEnabledSetting(alias) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    "confirmation did not disable alias");
            check(toggle.isChecked() && toggle.isAccessibilityFocused(), "hide lost state or focus");
            check(launcherCount() == 0, "hidden alias still resolves as a launcher");
            check(summary().equals(activity.getString(R.string.launcher_icon_hidden)), "hidden summary");
            check(pm.getComponentEnabledSetting(new ComponentName(getTargetContext(), MainActivity.class))
                    != PackageManager.COMPONENT_ENABLED_STATE_DISABLED, "main activity disabled");
            var launcherEntries = getTargetContext().getSystemService(LauncherApps.class)
                    .getActivityList(getTargetContext().getPackageName(), Process.myUserHandle());
            result.putInt("launcher_entries_while_hidden", launcherEntries.size());

            runOnMainSync(activity::finish);
            waitForIdleSync();
            openApp(SETTINGS_CATEGORY);
            check(toggle.isChecked() && launcherCount() == 0, "reopening reset hidden state");
            check(resolveEntry(Intent.CATEGORY_INFO).getComponent() != null, "INFO recovery missing");
            clickToggle();
            check(!toggle.isChecked() && launcherCount() == 1, "restore failed");
            check(pm.getComponentEnabledSetting(alias) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    "restore state");
            check(summary().equals(activity.getString(R.string.launcher_icon_visible)), "visible summary");
            result.putString("stream", "PASS: desktop-alias launch, default visibility, focus, cancel, Back, hide, "
                    + "module-settings reopening, INFO recovery and restore. "
                    + "Launcher API entries while hidden=" + launcherEntries.size()
                    + "; verify the Xiaomi home screen/app drawer and real screen readers separately.\n");
            resultCode = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(failure));
        } finally {
            pm.setComponentEnabledSetting(alias, originalState, PackageManager.DONT_KILL_APP);
            if (activity != null) runOnMainSync(activity::finish);
        }
        finish(resultCode, result);
    }

    private void openApp(String category) {
        activity = (MainActivity) startActivitySync(resolveEntry(category));
        runOnMainSync(() -> activity.findViewById(R.id.open_about).performClick());
        waitForMainWindow();
        toggle = activity.findViewById(R.id.hide_launcher_icon);
    }

    private Intent resolveEntry(String category) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category)
                .setPackage(getTargetContext().getPackageName());
        var entries = getTargetContext().getPackageManager().queryIntentActivities(
                intent, PackageManager.ResolveInfoFlags.of(0));
        check(entries.size() == 1, "missing/duplicate entry: " + category);
        String expected = Intent.CATEGORY_LAUNCHER.equals(category)
                ? getTargetContext().getPackageName() + ".LauncherAlias" : MainActivity.class.getName();
        check(entries.get(0).activityInfo.name.equals(expected), "wrong entry target");
        return intent.setComponent(new ComponentName(getTargetContext().getPackageName(), expected))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }

    private int launcherCount() {
        return getTargetContext().getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(getTargetContext().getPackageName()),
                PackageManager.ResolveInfoFlags.of(0)).size();
    }

    private void clickToggle() {
        runOnMainSync(() -> toggle.performClick());
        waitForIdleSync();
    }

    private void clickDialogButton(String resourceId) {
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        check(root != null, "confirmation window missing");
        var buttons = root.findAccessibilityNodeInfosByViewId(resourceId);
        check(buttons.size() == 1 && buttons.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK),
                "confirmation button unavailable: " + resourceId);
        waitForMainWindow();
    }

    private void waitForMainWindow() {
        long deadline = SystemClock.uptimeMillis() + 3000;
        while (!activity.hasWindowFocus() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(20);
        }
        waitForIdleSync();
        check(activity.hasWindowFocus(), "main window did not regain focus");
    }

    private String summary() {
        return ((TextView) activity.findViewById(R.id.launcher_icon_summary)).getText().toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
