package io.github.jiangjinyue2333.actiongrid.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class RotatingLogTest {
    private static final String NAME = "actiongrid-diagnostics.log";

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void existingSingleFileSurvivesUpgradeAndExportsWithSummary() throws IOException {
        File directory = temporary.newFolder();
        Files.writeString(new File(directory, NAME).toPath(), "旧日志\n");
        RotatingLog log = new RotatingLog(directory, NAME, 128);

        log.append("新日志\n");

        assertTrue(log.hasLogs());
        assertEquals("设备摘要\n旧日志\n新日志\n",
                Files.readString(log.prepareExport("设备摘要").toPath()));
    }

    @Test
    public void rotationRetainsFourFilesAndExportsOldestFirst() throws IOException {
        File directory = temporary.newFolder();
        RotatingLog log = new RotatingLog(directory, NAME, 8);

        for (int number = 1; number <= 5; number++) {
            log.append("line-" + number + "\n");
        }

        assertEquals("line-2\nline-3\nline-4\nline-5\n",
                Files.readString(log.prepareExport("").toPath()));
        assertFalse(new File(directory, NAME + ".4").exists());
        for (int index = 0; index < 4; index++) {
            assertTrue(new File(directory, index == 0 ? NAME : NAME + "." + index).length() <= 8);
        }
    }

    @Test
    public void exactCapacityDoesNotRotateEarly() throws IOException {
        File directory = temporary.newFolder();
        RotatingLog log = new RotatingLog(directory, NAME, 6);

        log.append("ab\n");
        log.append("cd\n");

        assertEquals("ab\ncd\n", Files.readString(new File(directory, NAME).toPath()));
        assertFalse(new File(directory, NAME + ".1").exists());
    }

    @Test
    public void oversizedUtf8EntryEndsAtACompleteCodePoint() throws IOException {
        File directory = temporary.newFolder();
        RotatingLog log = new RotatingLog(directory, NAME, 8);

        log.append("图😀图😀\n");

        byte[] bytes = Files.readAllBytes(new File(directory, NAME).toPath());
        assertEquals(8, bytes.length);
        assertEquals("图😀\n", new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    public void snapshotStaysStableUntilNextExport() throws IOException {
        RotatingLog log = new RotatingLog(temporary.newFolder(), NAME, 64);
        log.append("before\n");
        File snapshot = log.prepareExport("first");

        log.append("after\n");

        assertEquals("first\nbefore\n", Files.readString(snapshot.toPath()));
        assertEquals(snapshot, log.prepareExport("second"));
        assertEquals("second\nbefore\nafter\n", Files.readString(snapshot.toPath()));
    }

    @Test
    public void historyCountsAsLogsWhenActiveFileIsMissing() throws IOException {
        File directory = temporary.newFolder();
        RotatingLog log = new RotatingLog(directory, NAME, 8);
        log.append("line-1\n");
        log.append("line-2\n");
        Files.delete(new File(directory, NAME).toPath());

        assertTrue(log.hasLogs());
        assertEquals("line-1\n", Files.readString(log.prepareExport("").toPath()));
    }

    @Test
    public void clearRemovesActiveHistoryAndBothExportFiles() throws IOException {
        File directory = temporary.newFolder();
        RotatingLog log = new RotatingLog(directory, NAME, 8);
        for (int number = 1; number <= 4; number++) {
            log.append("line-" + number + "\n");
        }
        File snapshot = log.prepareExport("summary");
        File unfinished = new File(snapshot.getParentFile(), NAME + ".tmp");
        Files.writeString(unfinished.toPath(), "unfinished");

        log.clear();

        assertFalse(log.hasLogs());
        assertFalse(snapshot.exists());
        assertFalse(unfinished.exists());
        for (int index = 0; index < 4; index++) {
            assertFalse(new File(directory, index == 0 ? NAME : NAME + "." + index).exists());
        }
        log.append("new\n");
        assertEquals("new\n", Files.readString(log.prepareExport("").toPath()));
    }

    @Test
    public void failedExportPreservesThePreviousSnapshot() throws IOException {
        RotatingLog log = new RotatingLog(temporary.newFolder(), NAME, 64);
        log.append("before\n");
        File snapshot = log.prepareExport("first");
        File blocked = new File(snapshot.getParentFile(), NAME + ".tmp");
        Files.createDirectory(blocked.toPath());
        Files.writeString(new File(blocked, "blocker").toPath(), "keep");
        log.append("after\n");

        assertThrows(IOException.class, () -> log.prepareExport("second"));

        assertEquals("first\nbefore\n", Files.readString(snapshot.toPath()));
    }

    @Test
    public void failedAppendReportsTheStorageError() throws IOException {
        File directory = temporary.newFolder();
        Files.createDirectory(new File(directory, NAME).toPath());
        RotatingLog log = new RotatingLog(directory, NAME, 64);

        assertThrows(IOException.class, () -> log.append("entry\n"));
        assertFalse(log.hasLogs());
    }
}
