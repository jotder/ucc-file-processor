package com.gamma.util;

import com.opencsv.CSVWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileBackupTest {

    /**
     * Regression: {@code available_files.csv} is written by {@link FileOrganizer}'s default
     * {@link CSVWriter} (quotes fields, leaves '\' literal), and its {@code found_path} column holds
     * an absolute filesystem path. OpenCSV's default CSVReader treats '\' as an escape char and
     * silently strips it from Windows paths ("C:\base\sub\a.csv" -> "C:basesuba.csv"); the corrupted
     * path no longer resolves, so the file is never moved. The RFC4180 reader keeps backslashes.
     *
     * <p>This drives the real {@link FileBackup#run()} end-to-end: it writes a manifest via the same
     * writer the production path uses, then asserts the source file is actually moved into the backup
     * dir. On Windows (where {@code @TempDir} paths contain backslashes) this is a true regression
     * guard; on other OSes it confirms the happy path still works.
     */
    @Test
    void movesFileWhenFoundPathContainsBackslashes(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("base");
        Path sub  = base.resolve("sub");
        Files.createDirectories(sub);
        Path src = sub.resolve("data.csv");
        Files.writeString(src, "id,value\n1,x\n");

        Path backupDir = tmp.resolve("backup");
        Path pollDir   = tmp.resolve("poll");
        Files.createDirectories(pollDir);

        // available_files.csv columns: TAB, source, Date, FILENAME, Status, found_path, copied_to_path
        Path manifest = tmp.resolve("available_files.csv");
        try (CSVWriter w = new CSVWriter(new FileWriter(manifest.toFile()))) {
            w.writeNext(new String[]{"TAB", "source", "Date", "FILENAME",
                                     "Status", "found_path", "copied_to_path"});
            w.writeNext(new String[]{"1", "feed1", "2026-06-09", "data.csv",
                                     "SUCCESS", src.toAbsolutePath().toString(), "N/A"});
        }

        Map<String, Object> toon = Map.of(
                "backup", Map.of(
                        "base_dirs", List.of(base.toString()),
                        "log_available", manifest.toString()),
                "dirs", Map.of(
                        "backup", backupDir.toString(),
                        "poll", pollDir.toString()));

        new FileBackup(toon, false).run();

        Path dest = backupDir.resolve("sub").resolve("data.csv");
        assertTrue(Files.exists(dest),
                "found_path must survive CSV parsing so the file is moved into the backup dir");
        assertFalse(Files.exists(src), "the source file should have been moved, not left in place");
    }
}
