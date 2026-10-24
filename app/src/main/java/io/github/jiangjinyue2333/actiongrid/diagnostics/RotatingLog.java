package io.github.jiangjinyue2333.actiongrid.diagnostics;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

final class RotatingLog {
    private static final int FILE_COUNT = 4;

    private final File directory;
    private final String name;
    private final int maxSizeBytes;

    RotatingLog(File directory, String name, int maxSizeBytes) {
        if (maxSizeBytes <= 0) {
            throw new IllegalArgumentException("Log size must be positive");
        }
        this.directory = directory;
        this.name = name;
        this.maxSizeBytes = maxSizeBytes;
    }

    File exportFile() {
        return new File(new File(directory, "diagnostics-export"), name);
    }

    boolean hasLogs() {
        for (int index = 0; index < FILE_COUNT; index++) {
            File file = logFile(index);
            if (file.isFile() && file.length() > 0) {
                return true;
            }
        }
        return false;
    }

    void append(String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            return;
        }
        if (bytes.length > maxSizeBytes) {
            int end = maxSizeBytes - 1;
            while (end > 0 && (bytes[end] & 0xc0) == 0x80) {
                end--;
            }
            bytes = Arrays.copyOf(bytes, end + 1);
            bytes[end] = '\n';
        }
        File current = logFile(0);
        if (current.length() > 0 && current.length() + bytes.length > maxSizeBytes) {
            for (int index = FILE_COUNT - 1; index > 0; index--) {
                File previous = logFile(index - 1);
                if (previous.exists()) {
                    Files.move(previous.toPath(), logFile(index).toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        try (FileOutputStream output = new FileOutputStream(current, true)) {
            output.write(bytes);
        }
    }

    File prepareExport(String summary) throws IOException {
        File destination = exportFile();
        Files.createDirectories(destination.getParentFile().toPath());
        File temporary = exportTemporaryFile();
        try {
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                if (summary != null && !summary.isEmpty()) {
                    output.write(summary.getBytes(StandardCharsets.UTF_8));
                    output.write('\n');
                }
                for (int index = FILE_COUNT - 1; index >= 0; index--) {
                    File source = logFile(index);
                    if (source.isFile()) {
                        try (FileInputStream input = new FileInputStream(source)) {
                            input.transferTo(output);
                        }
                    }
                }
                output.getFD().sync();
            }
            Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return destination;
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temporary.toPath());
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    void clear() throws IOException {
        IOException failure = null;
        for (int index = 0; index < FILE_COUNT + 2; index++) {
            File file = index < FILE_COUNT ? logFile(index)
                    : index == FILE_COUNT ? exportFile() : exportTemporaryFile();
            try {
                deleteOrTruncate(file);
            } catch (IOException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private File logFile(int index) {
        return new File(directory, index == 0 ? name : name + "." + index);
    }

    private File exportTemporaryFile() {
        return new File(exportFile().getParentFile(), name + ".tmp");
    }

    private static void deleteOrTruncate(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            try (FileOutputStream ignored = new FileOutputStream(file, false)) {
                // A shared descriptor can keep a file alive until its reader closes it.
            }
        }
    }
}
