package io.github.jiangjinyue2333.actiongrid.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public final class LogStoreProtocolTest {
    private static final String RECEIVED = "2026-10-22T08:00:01Z";

    @Test
    public void nativeBatchPreservesEventTimePidAndSeverity() {
        String original = "2026-10-22T08:00:00Z pid=123 I native_ready capabilities=7";

        assertEquals(RECEIVED + " [native] " + original + "\n",
                LogStore.formatNativeBatch(original, RECEIVED));
    }

    @Test
    public void nativeBatchNormalizesWindowsNewlinesWithoutLosingEntries() {
        assertEquals(RECEIVED + " [native] first\n" + RECEIVED + " [native] second\n",
                LogStore.formatNativeBatch("first\r\nsecond\r\n", RECEIVED));
    }

    @Test
    public void nativeBatchAccepts64LinesAndRejectsAnyExtraLine() {
        String result = LogStore.formatNativeBatch("entry\n".repeat(64), RECEIVED);

        assertEquals(64, result.lines().count());
        assertNull(LogStore.formatNativeBatch("entry\n".repeat(65), RECEIVED));
        assertNull(LogStore.formatNativeBatch("entry\n".repeat(64) + "extra", RECEIVED));
        assertNull(LogStore.formatNativeBatch("entry\n".repeat(64) + "\n", RECEIVED));
    }

    @Test
    public void nativeLineIsTruncatedWithoutBreakingSurrogatePairs() {
        String marker = "...[truncated]";
        String value = "x".repeat(2048 - marker.length() - 1) + "😀" + "尾".repeat(100);

        String batch = LogStore.formatNativeBatch(value, RECEIVED);
        String line = batch.substring((RECEIVED + " [native] ").length()).stripTrailing();

        assertTrue(line.length() <= 2048);
        assertTrue(line.endsWith(marker));
        assertEquals(line, new String(line.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertFalse(line.contains("😀"));
    }

    @Test
    public void oversizedAndEmptyNativeMessagesAreRejected() {
        assertNull(LogStore.formatNativeBatch("x".repeat(32769), RECEIVED));
        assertNull(LogStore.formatNativeBatch(null, RECEIVED));
        assertNull(LogStore.formatNativeBatch("", RECEIVED));
        assertNull(LogStore.formatNativeBatch("\r\n\n", RECEIVED));
        assertTrue(LogStore.formatNativeBatch("x".repeat(32768), RECEIVED) != null);
    }

    @Test
    public void oldAndDisabledSessionsCannotWriteAfterLoggingIsToggled() {
        assertTrue(LogStore.acceptsNativeSession(1, 1));
        assertTrue(LogStore.acceptsNativeSession(1792656000000L, 1792656000000L));
        assertFalse(LogStore.acceptsNativeSession(0, 0));
        assertFalse(LogStore.acceptsNativeSession(0, 1792656000000L));
        assertFalse(LogStore.acceptsNativeSession(1792656000001L, 1792656000000L));
        assertFalse(LogStore.acceptsNativeSession(1792656000001L, -1));
    }

    @Test
    public void javaMessagesCannotInjectExtraLogLines() {
        assertEquals("app source", LogStore.sanitize("app\nsource", 48));
        assertEquals("first  second", LogStore.sanitize("first\r\nsecond", 2048));
    }
}
