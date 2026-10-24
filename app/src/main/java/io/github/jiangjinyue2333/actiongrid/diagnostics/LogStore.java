package io.github.jiangjinyue2333.actiongrid.diagnostics;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Instant;

public final class LogStore {
    public static final String FILE_NAME = "actiongrid-diagnostics.log";
    private static final String PREFS_NAME = "diagnostics";
    private static final String ENABLED_KEY = "logging_enabled";
    private static final String SESSION_KEY = "logging_session";
    private static final String NATIVE_CONTACT_KEY = "native_contact_time";
    private static final int MAX_SIZE_BYTES = 512 * 1024;
    private static final int MAX_NATIVE_BATCH_LENGTH = 32768;
    private static final int MAX_NATIVE_LINES = 64;
    private static final long CONTACT_PERSIST_INTERVAL_MS = 60_000;
    private static final Object LOCK = new Object();
    private static long lastNativeContact;
    private static long lastPersistedNativeContact;

    private LogStore() {
    }

    public static boolean isEnabled(Context context) {
        synchronized (LOCK) {
            return preferences(context).getBoolean(ENABLED_KEY, false);
        }
    }

    public static void setEnabled(Context context, boolean enabled) {
        synchronized (LOCK) {
            SharedPreferences preferences = preferences(context);
            long previousSession = preferences.getLong(SESSION_KEY, 1);
            long nextSession = Math.max(System.currentTimeMillis(), previousSession + 1);
            boolean cleared = true;
            try {
                store(context).clear();
            } catch (IOException | RuntimeException ignored) {
                cleared = false;
            }
            boolean active = enabled && cleared;
            // A completed toggle must survive process death before accepting another batch.
            boolean persisted = preferences.edit().putBoolean(ENABLED_KEY, active)
                    .putLong(SESSION_KEY, nextSession).remove(NATIVE_CONTACT_KEY).commit();
            if (!persisted && active) {
                active = false;
                preferences.edit().putBoolean(ENABLED_KEY, false).apply();
            }
            lastNativeContact = 0;
            lastPersistedNativeContact = 0;
            if (active) {
                appendLocked(context, "app", "日志记录已开启 session=" + nextSession);
            }
        }
    }

    public static void append(Context context, String source, String message) {
        synchronized (LOCK) {
            if (sessionLocked(context) != 0) {
                appendLocked(context, source, message);
            }
        }
    }

    public static File file(Context context) {
        return store(context).exportFile();
    }

    static ParcelFileDescriptor openExport(Context context) throws FileNotFoundException {
        synchronized (LOCK) {
            if (sessionLocked(context) == 0) {
                throw new FileNotFoundException("Diagnostics logging is disabled");
            }
            File file = store(context).exportFile();
            if (!file.isFile()) {
                throw new FileNotFoundException("No diagnostics file");
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
    }

    public static boolean hasLogs(Context context) {
        synchronized (LOCK) {
            return sessionLocked(context) != 0 && store(context).hasLogs();
        }
    }

    public static File prepareExport(Context context, String summary) throws IOException {
        synchronized (LOCK) {
            try {
                if (sessionLocked(context) == 0 || !store(context).hasLogs()) {
                    throw new IOException("没有可导出的诊断日志");
                }
                return store(context).prepareExport(summary);
            } catch (RuntimeException failure) {
                throw new IOException("无法准备诊断日志", failure);
            }
        }
    }

    public static String nativeStatus(Context context) {
        synchronized (LOCK) {
            if (sessionLocked(context) == 0) {
                return "日志未开启";
            }
            long lastContact = Math.max(lastNativeContact,
                    preferences(context).getLong(NATIVE_CONTACT_KEY, 0));
            return lastContact == 0 ? "等待小米桌面连接"
                    : "上次连接 " + Instant.ofEpochMilli(lastContact) + "（"
                    + Math.max(0, (System.currentTimeMillis() - lastContact) / 1000) + " 秒前）";
        }
    }

    static long nativeState(Context context, boolean recordContact) {
        synchronized (LOCK) {
            long session = sessionLocked(context);
            if (recordContact && session != 0) {
                recordNativeContactLocked(context);
            }
            return session;
        }
    }

    static NativeAppendResult appendNative(Context context, long suppliedSession, String message) {
        synchronized (LOCK) {
            long currentSession = sessionLocked(context);
            if (!acceptsNativeSession(currentSession, suppliedSession)) {
                return new NativeAppendResult(currentSession, 0);
            }
            String batch = formatNativeBatch(message, Instant.now().toString());
            if (batch == null) {
                return new NativeAppendResult(currentSession, 0);
            }
            recordNativeContactLocked(context);
            try {
                store(context).append(batch);
                return new NativeAppendResult(currentSession, suppliedSession);
            } catch (IOException | RuntimeException ignored) {
                // The launcher keeps the unacknowledged batch and retries later.
                return new NativeAppendResult(currentSession, 0);
            }
        }
    }

    static boolean acceptsNativeSession(long currentSession, long suppliedSession) {
        return currentSession > 0 && currentSession == suppliedSession;
    }

    static String formatNativeBatch(String message, String receivedAt) {
        if (message == null || message.isEmpty() || message.length() > MAX_NATIVE_BATCH_LENGTH) {
            return null;
        }
        StringBuilder result = new StringBuilder(message.length() + 1024);
        int start = 0;
        for (int lines = 0; start < message.length() && lines < MAX_NATIVE_LINES; lines++) {
            int end = message.indexOf('\n', start);
            if (end < 0) {
                end = message.length();
            }
            String line = message.substring(start, end);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (!line.isEmpty()) {
                result.append(receivedAt).append(" [native] ")
                        .append(sanitize(line, 2048)).append('\n');
            }
            start = end + 1;
        }
        return start < message.length() || result.length() == 0 ? null : result.toString();
    }

    static String sanitize(String value, int maxLength) {
        String safe = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
        if (safe.length() <= maxLength) {
            return safe;
        }
        String marker = "...[truncated]";
        int end = maxLength - marker.length();
        if (Character.isHighSurrogate(safe.charAt(end - 1))
                && Character.isLowSurrogate(safe.charAt(end))) {
            end--;
        }
        return safe.substring(0, end) + marker;
    }

    private static void appendLocked(Context context, String source, String message) {
        String line = Instant.now() + " [" + Application.getProcessName() + "] "
                + sanitize(source, 48) + ": " + sanitize(message, 2048) + '\n';
        try {
            store(context).append(line);
        } catch (IOException | RuntimeException ignored) {
            // Diagnostics must never affect the app or the hooked launcher.
        }
    }

    private static long sessionLocked(Context context) {
        SharedPreferences preferences = preferences(context);
        return preferences.getBoolean(ENABLED_KEY, false)
                ? preferences.getLong(SESSION_KEY, 1) : 0;
    }

    private static void recordNativeContactLocked(Context context) {
        long now = System.currentTimeMillis();
        lastNativeContact = now;
        if (lastPersistedNativeContact == 0) {
            lastPersistedNativeContact = preferences(context).getLong(NATIVE_CONTACT_KEY, 0);
        }
        if (lastPersistedNativeContact == 0 || now < lastPersistedNativeContact
                || now - lastPersistedNativeContact >= CONTACT_PERSIST_INTERVAL_MS) {
            preferences(context).edit().putLong(NATIVE_CONTACT_KEY, now).apply();
            lastPersistedNativeContact = now;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static RotatingLog store(Context context) {
        return new RotatingLog(context.getFilesDir(), FILE_NAME, MAX_SIZE_BYTES);
    }

    static final class NativeAppendResult {
        final long session;
        final long acknowledgement;

        NativeAppendResult(long session, long acknowledgement) {
            this.session = session;
            this.acknowledgement = acknowledgement;
        }
    }
}
