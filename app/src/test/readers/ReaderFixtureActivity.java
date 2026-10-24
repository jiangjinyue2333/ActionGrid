package io.github.jiangjinyue2333.actiongrid.readerfixture;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/** Test-only Android adapter. All grid/drag/traversal decisions stay in native_init.cpp. */
public final class ReaderFixtureActivity extends Activity {
    static { System.loadLibrary("reader_fixture"); }
    private static native void nativeInit(Grid view, int width, int height, int top);
    private static native String nativeNode(int id);
    private static native boolean nativeAction(int id, int action);
    private static native void nativeMotion(int action, float x, float y);
    private static native int nativeFocus();
    private static native void nativeReset();
    private static native void nativeWindowFocus(boolean focused);
    private static native void nativeProbeEvent(int type, String text);
    private static native String nativeSelfCheck();
    private Grid grid;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        LinearLayout controls = new LinearLayout(this);
        Button lift = new Button(this); lift.setText("托起 QQ");
        lift.setOnClickListener(v -> command("lift"));
        Button reset = new Button(this); reset.setText("重置");
        reset.setOnClickListener(v -> command("reset"));
        controls.addView(lift); controls.addView(reset);
        page.addView(controls);
        status = new TextView(this);
        status.setText("4 列 6 行：QQ、空位、微信；底栏电话");
        status.setMaxLines(2);
        status.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        page.addView(status, new LinearLayout.LayoutParams(-1, (int) (56 * getResources().getDisplayMetrics().density)));
        grid = new Grid();
        page.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        grid.post(() -> command(intent.getStringExtra("command")));
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (grid != null && grid.ready) nativeWindowFocus(focused);
    }
    private void command(String name) {
        Log.i("AGReaderFixture", "COMMAND " + name + " ready=" + grid.ready);
        if (name == null || !grid.ready) return;
        if (name.equals("lift")) nativeAction(11, 0x6a000001);
        else if (name.equals("cancel")) nativeAction(11, 0x6a000002);
        else if (name.equals("reset")) nativeReset();
        else if (name.equals("focus")) nativeAction(11, 64);
        else if (name.equals("open")) grid.provider.performAction(11, 16, null);
        else if (name.equals("leave")) nativeWindowFocus(false);
        else if (name.equals("home")) {
            nativeWindowFocus(true);
            nativeNode(10); // Confirm the workspace through the production node hook.
        }
        else if (name.equals("dump")) {
            Log.i("AGReaderFixture", "TREE " + nativeNode(10));
        } else if (name.equals("announce") || name.equals("window")) {
            String text = getIntent().getStringExtra("text");
            nativeProbeEvent(name.equals("announce") ? 16384 : 32, text == null ? "系统桌面" : text);
        } else if (name.equals("selfcheck")) {
            status.setText(nativeSelfCheck());
        }
        grid.invalidate();
    }

    public final class Grid extends View {
        private boolean ready;
        private boolean initialCommandRun;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final AccessibilityNodeProvider provider = new AccessibilityNodeProvider() {
            @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
                try {
                    String raw = nativeNode(id);
                    if (raw == null) return null;
                    JSONObject n = new JSONObject(raw);
                    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
                    if (id == HOST_VIEW_ID) {
                        Grid.super.onInitializeAccessibilityNodeInfo(info);
                        info.setSource(Grid.this);
                        info.setClassName("android.view.View");
                        info.setFocusable(false);
                    } else {
                        info.setSource(Grid.this, id);
                        int parent = n.getInt("parent");
                        if (parent == HOST_VIEW_ID) info.setParent(Grid.this);
                        else info.setParent(Grid.this, parent);
                        info.setClassName(n.getString("class"));
                        info.setContentDescription(n.getString("label"));
                        info.setViewIdResourceName(n.getString("resource"));
                    }
                    info.setPackageName(getPackageName());
                    info.setEnabled(true);
                    info.setVisibleToUser(true);
                    info.setFocusable(n.getBoolean("focusable"));
                    info.setClickable(n.getBoolean("clickable"));
                    info.setAccessibilityFocused(n.getBoolean("focused"));
                    info.setImportantForAccessibility(n.getBoolean("important"));
                    JSONArray r = n.getJSONArray("bounds");
                    info.setBoundsInScreen(new Rect(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3)));
                    r = n.getJSONArray("parentBounds");
                    info.setBoundsInParent(new Rect(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3)));
                    JSONArray children = n.getJSONArray("children");
                    for (int i = 0; i < children.length(); i++) info.addChild(Grid.this, children.getInt(i));
                    if (n.getInt("after") != -1) info.setTraversalAfter(Grid.this, n.getInt("after"));
                    if (n.getInt("before") != -1) info.setTraversalBefore(Grid.this, n.getInt("before"));
                    JSONArray actions = n.getJSONArray("actions");
                    for (int i = 0; i < actions.length(); i++) {
                        JSONArray action = actions.getJSONArray(i);
                        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(action.getInt(0), action.getString(1)));
                    }
                    return info;
                } catch (Exception error) { throw new IllegalStateException(error); }
            }
            @Override public boolean performAction(int id, int action, Bundle args) {
                boolean result = nativeAction(id, action);
                Log.i("AGReaderFixture", "ACTION node=" + id + " action=" + action + " result=" + result);
                // The original QQ launch is a boundary mock: use Settings so a
                // real Android window transition can validate return feedback.
                if (result && id == 11 && action == AccessibilityNodeInfo.ACTION_CLICK) {
                    startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
                }
                invalidate(); return result;
            }
            @Override public AccessibilityNodeInfo findFocus(int type) {
                int id = nativeFocus();
                return id == -1 ? null : createAccessibilityNodeInfo(id);
            }
        };
        Grid() {
            super(ReaderFixtureActivity.this);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setFocusable(true); setBackgroundColor(Color.WHITE);
        }
        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            post(() -> {
                int[] location = new int[2]; getLocationOnScreen(location);
                Log.i("AGReaderFixture", "INIT width=" + getWidth() + " height=" + getHeight() + " top=" + location[1]);
                nativeInit(this, getWidth(), getHeight(), location[1]); ready = true;
                if (!initialCommandRun) {
                    initialCommandRun = true;
                    command(getIntent().getStringExtra("command"));
                }
                invalidate();
            });
        }
        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() { return ready ? provider : null; }
        @Override public boolean dispatchHoverEvent(MotionEvent event) {
            nativeMotion(event.getActionMasked(), event.getRawX(), event.getRawY());
            return true;
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                int focused = nativeFocus();
                if (focused != -1) nativeAction(focused, AccessibilityNodeInfo.ACTION_CLICK);
                performClick();
            }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                return nativeAction(nativeFocus(), AccessibilityNodeInfo.ACTION_CLICK);
            }
            return super.onKeyDown(keyCode, event);
        }
        // Called only by production sendEvent/sendAnnouncement ABI mocks.
        public void nativeEvent(int type, int id, String text) {
            Runnable delivery = () -> {
                AccessibilityEvent event = AccessibilityEvent.obtain(type);
                event.setPackageName(getPackageName()); event.setClassName("android.view.View");
                if (id == -1) event.setSource(this); else event.setSource(this, id);
                if (!text.isEmpty()) event.getText().add(text);
                if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) event.setContentChangeTypes(1);
                getParent().requestSendAccessibilityEvent(this, event);
                Log.i("AGReaderFixture", "EVENT type=" + type + " node=" + id + " text=" + text);
                if (type == AccessibilityEvent.TYPE_ANNOUNCEMENT) status.setText(text);
            };
            // Match the native synchronous send boundary. Delaying a focus-clear
            // behind a new focus event changes ViewRoot's virtual focus state.
            if (Looper.myLooper() == Looper.getMainLooper()) delivery.run();
            else post(delivery);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setStrokeWidth(2); paint.setTextSize(28);
            float rowHeight = Math.max(1, (getHeight() - 120) / 6f);
            paint.setColor(Color.GRAY);
            for (int row = 0; row <= 6; row++) canvas.drawLine(0, row * rowHeight, getWidth(), row * rowHeight, paint);
            for (int col = 0; col <= 4; col++) canvas.drawLine(col * getWidth() / 4f, 0, col * getWidth() / 4f, rowHeight * 6, paint);
            paint.setColor(Color.BLACK);
            canvas.drawText("QQ", 20, rowHeight / 2, paint);
            canvas.drawText("微信", getWidth() / 2f + 20, rowHeight / 2, paint);
            canvas.drawText("底栏：电话", 20, getHeight() - 45, paint);
        }
    }
}
