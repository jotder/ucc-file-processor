package com.gamma.util;

import com.opencsv.CSVReader;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Moves original source files to {@code dirs.backup} after a successful search or copy run.
 *
 * <p>Configuration is read from the {@code backup} section of the pipeline {@code .toon} file:
 * <pre>
 *   # Exclusive section for the backup command
 *   backup:
 *     base_dirs[2]: /mnt/rawdata/feed1, /mnt/rawdata/feed2
 *     log_available: available_files.csv
 *
 *   # Destination (reuses existing dirs section)
 *   dirs:
 *     backup: backup/adjustment
 * </pre>
 *
 * <p>Reads {@code backup.log_available} (the {@code available_files.csv} produced by
 * {@link FileOrganizer}) and moves the original at {@code found_path} (column 5) to
 * {@code dirs.backup}, preserving the file's relative path under its matching
 * {@code backup.base_dirs} entry.  Files not under any base dir are placed flat in
 * {@code dirs.backup} (with a warning).
 */
public class FileBackup {

    private static final int STATUS_COL     = 4;
    private static final int FOUND_PATH_COL = 5;

    // ── state ─────────────────────────────────────────────────────────────────

    private final List<Path> baseDirs;
    private final Path       backupDir;
    private final Path       pollDir;
    private final String     logAvailablePath;
    private final boolean    dryRun;

    private final AtomicInteger movedCount = new AtomicInteger(0);
    private final AtomicInteger skipCount  = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);

    // ── construction ──────────────────────────────────────────────────────────

    /**
     * @param toon   full pipeline toon map (must contain {@code backup} and {@code dirs} sections)
     * @param dryRun simulate without touching the filesystem
     */
    public FileBackup(Map<String, Object> toon, boolean dryRun) {
        this.dryRun = dryRun;

        Map<String, Object> backupSec = ToonHelper.requireSection(toon, "backup");
        this.baseDirs         = ToonHelper.parseBaseDirs(backupSec);
        this.logAvailablePath = ToonHelper.opt(backupSec, "log_available", "available_files.csv");

        Map<String, Object> dirs = ToonHelper.requireSection(toon, "dirs");
        this.backupDir = Paths.get(ToonHelper.require(dirs, "backup", "dirs")).toAbsolutePath().normalize();
        this.pollDir   = Paths.get(ToonHelper.require(dirs, "poll",   "dirs")).toAbsolutePath().normalize();
    }

    // ── public entry point ────────────────────────────────────────────────────

    public void run() throws Exception {
        if (dryRun) System.out.println("!!! DRY-RUN MODE — no files will be moved !!!");
        System.out.println("[BACKUP] Reading file list: " + logAvailablePath);

        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        Phaser          ph   = new Phaser(1);

        try (CSVReader reader = Csv.reader(new FileReader(logAvailablePath))) {
            reader.readNext(); // skip header

            String[] row;
            while ((row = reader.readNext()) != null) {
                if (row.length <= FOUND_PATH_COL) continue;

                String foundPath = row[FOUND_PATH_COL];
                if (foundPath == null || foundPath.isBlank() || foundPath.equals("N/A")) continue;

                String status = row.length > STATUS_COL ? row[STATUS_COL] : "";
                if (status.startsWith("ERROR")) continue;

                Path src = Paths.get(foundPath).toAbsolutePath().normalize();
                VirtualThreadRunner.submit(exec, ph, () -> backupFile(src));
            }
        }

        ph.arriveAndAwaitAdvance();
        exec.shutdown();
        System.out.printf("%n[BACKUP] Done — moved: %d  skipped: %d  errors: %d%n",
                movedCount.get(), skipCount.get(), errorCount.get());

        if (!dryRun) {
            System.out.println("[BACKUP] Pruning empty subdirs from poll dir: " + pollDir);
            cleanupEmptyDirs(pollDir);
        }
    }

    // ── empty-dir cleanup ──────────────────────────────────────────────────────

    /**
     * Deletes empty subdirectories under {@code root} using a bottom-up walk.
     * Non-empty directories are silently skipped; the root itself is never removed.
     *
     * @param root the directory tree to prune (typically {@code dirs.poll})
     */
    private void cleanupEmptyDirs(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder())
                .filter(Files::isDirectory)
                .filter(p -> !p.equals(root))
                .forEach(dir -> {
                    try {
                        Files.delete(dir);
                        System.out.printf("[RMDIR]  Removed empty dir: %s%n", dir);
                    } catch (DirectoryNotEmptyException ignored) {
                    } catch (IOException e) {
                        System.err.printf("[WARN] Could not remove dir %s: %s%n",
                                dir, e.getMessage());
                    }
                });
        } catch (IOException e) {
            System.err.printf("[WARN] Could not walk poll dir for cleanup: %s%n", e.getMessage());
        }
    }

    // ── per-file backup ────────────────────────────────────────────────────────

    private void backupFile(Path src) {
        if (!Files.exists(src)) {
            System.err.println("[WARN] Source not found, skipping: " + src);
            skipCount.incrementAndGet();
            return;
        }

        Path relPath = relativeToBase(src);
        Path dest    = backupDir.resolve(relPath);

        try {
            if (dryRun) {
                System.out.printf("[DRY-RUN] Would move: %s → %s%n", src, dest);
                movedCount.incrementAndGet();
                return;
            }
            if (Files.exists(dest)) {
                System.out.printf("[SKIP] Already in backup: %s%n", dest);
                skipCount.incrementAndGet();
                return;
            }
            Files.createDirectories(dest.getParent());
            Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
            System.out.printf("[BACKUP] %s → %s%n", src.getFileName(), dest);
            movedCount.incrementAndGet();
        } catch (IOException e) {
            System.err.printf("[ERROR] Cannot backup %s: %s%n", src, e.getMessage());
            errorCount.incrementAndGet();
        }
    }

    private Path relativeToBase(Path src) {
        Path bestBase = null;
        int  bestLen  = -1;
        for (Path base : baseDirs) {
            if (src.startsWith(base) && base.getNameCount() > bestLen) {
                bestBase = base;
                bestLen  = base.getNameCount();
            }
        }
        if (bestBase != null) return bestBase.relativize(src);
        System.err.printf("[WARN] %s is not under any base_dir; using filename only.%n", src);
        return src.getFileName();
    }
}
