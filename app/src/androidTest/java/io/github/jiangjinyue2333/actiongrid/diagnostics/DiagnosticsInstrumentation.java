package io.github.jiangjinyue2333.actiongrid.diagnostics;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Uses the installed provider, Android preferences and real file descriptors. */
public final class DiagnosticsInstrumentation extends Instrumentation {
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        int resultCode = Activity.RESULT_CANCELED;
        try {
            LogStore.setEnabled(context, false);
            check(state(context) == 0, "disabled state");
            check(append(context, 1, "disabled\n").getLong("ack") == 0, "disabled batch accepted");
            LogStore.setEnabled(context, true);
            long first = state(context);
            check(first > 0, "enabled epoch");
            check(append(context, first, "first native event\n中文操作\n").getLong("ack") == first,
                    "native batch acknowledgement");
            check(!LogStore.nativeStatus(context).contains("等待"), "native contact missing");
            File export = LogStore.prepareExport(context, "device summary");
            String text;
            try (var input = context.getContentResolver().openInputStream(DiagnosticsProvider.LOG_URI)) {
                check(input != null, "provider file missing");
                text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            check(text.startsWith("device summary") && text.contains("[native] first native event")
                    && text.contains("中文操作"), "export lost native records or summary");
            append(context, first, "after snapshot\n");
            check(!Files.readString(export.toPath()).contains("after snapshot"), "export changed during sharing");
            LogStore.setEnabled(context, false);
            check(!export.exists() && !LogStore.hasLogs(context), "disable did not clear export/history");
            try {
                context.getContentResolver().openFileDescriptor(DiagnosticsProvider.LOG_URI, "r");
                throw new AssertionError("disabled export can still be opened");
            } catch (FileNotFoundException expected) {
                // URI grants never bypass the disabled state.
            }
            LogStore.setEnabled(context, true);
            long second = state(context);
            check(second > first, "enable reused previous epoch");
            check(append(context, first, "stale native event\n").getLong("ack") == 0, "stale batch accepted");
            check(append(context, second, "line\n".repeat(65)).getLong("ack") == 0, "oversized batch accepted");
            append(context, second, "new session event\n");
            for (int i = 0; i < 80; ++i) {
                check(append(context, second, ("event" + i + " ").repeat(180) + "\n").getLong("ack") == second,
                        "batch rejected unexpectedly");
            }
            text = Files.readString(LogStore.prepareExport(context, "new summary").toPath());
            check(text.contains("new session event") && !text.contains("stale native event")
                    && !text.contains("first native event"), "old session returned after toggle");
            result.putString("stream", "PASS: installed diagnostics provider state, native batches, UTF-8, export snapshot, disable and epoch isolation\n");
            resultCode = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(failure));
        } finally {
            LogStore.setEnabled(context, false);
        }
        finish(resultCode, result);
    }

    private static long state(Context context) {
        return context.getContentResolver().call(DiagnosticsProvider.LOG_URI, "state", null, null)
                .getLong("session");
    }

    private static Bundle append(Context context, long session, String message) {
        Bundle extras = new Bundle();
        extras.putLong("session", session);
        extras.putString("message", message);
        return context.getContentResolver().call(DiagnosticsProvider.LOG_URI, "append_native", null, extras);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
