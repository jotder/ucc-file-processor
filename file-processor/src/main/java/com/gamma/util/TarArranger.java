package com.gamma.util;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pre-ETL utility that handles {@code .tar.gz} archives in two independent steps:
 *
 * <ol>
 *   <li>{@link #copyTars()} — uses the {@code copy_tars} section to scan base directories for
 *       archives and copy them (flat) into the poll directory for extraction.</li>
 *   <li>{@link #extract()} — uses the {@code dirs} section to extract every {@code *.tar.gz}
 *       at the root of the poll directory, arrange extracted CSVs by date, back up the
 *       original archive, and clean up the temp scratch directory.</li>
 * </ol>
 *
 * <p>Required pipeline toon sections:
 * <pre>
 *   # For copy-tars command
 *   copy_tars:
 *     base_dirs[2]: /mnt/rawdata/feed1, /mnt/rawdata/feed2
 *
 *   # For extract command (reuses existing dirs section)
 *   dirs:
 *     poll:   inbox/adjustment   # scanned for .tar.gz; extracted CSVs land here by date
 *     temp:   temp/adjustment    # scratch space; cleaned up after each archive
 *     backup: backup/adjustment  # processed archives are moved here
 * </pre>
 *
 * <p>Date detection: the first {@code YYYYMMDD} token (years 1900–2099) in the filename
 * is used as the date partition folder.  Files without such a token land in {@code obscure/}.
 */
public class TarArranger {

    private static final Pattern DATE_RE = Pattern.compile("((?:19|20)\\d{6})");

    private static final List<String> TAR_SUFFIXES = List.of(".tar.gz", ".tgz", ".tar");
    private static final List<String> CSV_SUFFIXES = List.of(".csv.gz", ".csv");

    // ── dirs from pipeline toon ───────────────────────────────────────────────

    private final Path pollDir;    // dirs.poll  — staging area + CSV date-folders
    private final Path tempDir;    // dirs.temp  — scratch space for extraction
    private final Path backupDir;  // dirs.backup — processed archives go here

    // ── copy_tars section ─────────────────────────────────────────────────────
    private final List<Path> copyTarsBaseDirs; // copy_tars.base_dirs (null if section absent)

    private final boolean dryRun;

    // ── construction ──────────────────────────────────────────────────────────
    public TarArranger(Map<String, Object> toon, boolean dryRun) {
        this.dryRun = dryRun;

        Map<String, Object> dirs = requireSection(toon, "dirs");
        this.pollDir = Paths.get(require(dirs, "poll", "dirs")).toAbsolutePath().normalize();
        this.tempDir = Paths.get(require(dirs, "temp", "dirs")).toAbsolutePath().normalize();
        this.backupDir = Paths.get(require(dirs, "backup", "dirs")).toAbsolutePath().normalize();

        // copy_tars section is optional — only needed for copyTars()
        Object copyTarsSec = toon.get("copy_tars");
        if (copyTarsSec instanceof Map) {
            this.copyTarsBaseDirs = FileOrganizer.parseBaseDirs((Map<String, Object>) copyTarsSec);
        } else {
            this.copyTarsBaseDirs = null;
        }
    }

    // ── operation 1: copy-tars ────────────────────────────────────────────────

    /**
     * Recursively scans every directory in {@code copy_tars.base_dirs} for
     * {@code *.tar.gz} / {@code *.tgz} / {@code *.tar} files and copies each
     * (flat) to the root of {@code dirs.poll}.  Existing files are skipped.
     *
     * <p>Requires a {@code copy_tars} section in the pipeline toon.
     */
    public void copyTars() throws InterruptedException, IOException {
        if (copyTarsBaseDirs == null)
            throw new IllegalStateException("Pipeline toon is missing 'copy_tars' section — required for copy-tars command.");
        if (dryRun)
            System.out.println("!!! DRY-RUN MODE — no files will be copied !!!");
        System.out.println("[COPY-TARS] Scanning " + copyTarsBaseDirs.size() + " base dir(s) for *.tar.gz ...");

        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        Phaser ph = new Phaser(1);

        for (Path base : copyTarsBaseDirs) {
            if (!Files.exists(base)) {
                System.err.println("[WARN] Base dir not found, skipping: " + base);
                continue;
            }
            submit(exec, ph, () -> walkForTars(exec, ph, base));
        }

        ph.arriveAndAwaitAdvance();
        exec.shutdown();
        System.out.println("[COPY-TARS] Done.");
    }

    private void walkForTars(ExecutorService exec, Phaser ph, Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry))
                    submit(exec, ph, () -> walkForTars(exec, ph, entry));
                else if (isTar(entry))
                    submit(exec, ph, () -> copyOneTar(entry));
            }
        } catch (IOException e) {
            System.err.println("[WARN] Cannot scan dir: " + dir + " — " + e.getMessage());
        }
    }

    private void copyOneTar(Path src) {
        String name = src.getFileName().toString();
        Path dest = pollDir.resolve(name);
        try {
            if (dryRun) {
                System.out.printf("[DRY-RUN] Would copy tar: %s → %s%n", src, dest);
                return;
            }
            if (Files.exists(dest)) {
                System.out.println("[SKIP] Already in target: " + dest);
                return;
            }
            Files.createDirectories(pollDir);
            Files.copy(src, dest);
            System.out.printf("[COPY-TAR] %s → %s%n", name, dest);
        } catch (IOException e) {
            System.err.printf("[ERROR] Cannot copy %s: %s%n", src, e.getMessage());
        }
    }

    // ── operation 2: extract ──────────────────────────────────────────────────

    /**
     * Scans the top-level of {@code dirs.poll} for {@code *.tar.gz} archives.
     * For each archive:
     * <ol>
     *   <li>Extract to {@code dirs.temp/<stem>/}</li>
     *   <li>Move extracted CSVs to {@code dirs.poll/<YYYYMMDD>/}</li>
     *   <li>Move archive to {@code dirs.backup/}</li>
     *   <li>Delete the temp extraction directory</li>
     * </ol>
     * Steps 3 and 4 are skipped on any CSV move failure, leaving the archive and
     * temp dir intact for manual inspection.
     */
    public void extract() throws IOException {
        if (dryRun) System.out.println("!!! DRY-RUN MODE — no files will be modified !!!");
        System.out.println("[EXTRACT] Scanning poll dir for *.tar.gz: " + pollDir);

        if (!Files.exists(pollDir)) {
            System.err.println("[WARN] Poll dir does not exist: " + pollDir);
            return;
        }

        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        Phaser ph = new Phaser(1);
        int[] total = {0};

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pollDir)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry) && isTar(entry)) {
                    total[0]++;
                    submit(exec, ph, () -> processArchive(entry));
                }
            }
        }

        ph.arriveAndAwaitAdvance();
        exec.shutdown();
        System.out.printf("[EXTRACT] Done — %d archive(s) processed.%n", total[0]);
    }

    private void processArchive(Path archive) {
        String archiveName = archive.getFileName().toString();
        Path scratchDir = tempDir.resolve(stripTarSuffix(archiveName));

        System.out.printf("[ARCHIVE] %s%n", archiveName);
        try {
            if (dryRun) {
                dryRunPeek(archive, scratchDir);
                return;
            }

            // 1. Extract
            Files.createDirectories(scratchDir);
            int count = extractTar(archive, scratchDir);
            System.out.printf("[EXTRACT] %s → %s (%d file(s))%n", archiveName, scratchDir, count);

            // 2. Arrange CSVs — collect failures so a partial move does not trigger backup/cleanup
            List<IOException> failures = new ArrayList<>();
            try (var walk = Files.walk(scratchDir)) {
                walk.filter(p -> !Files.isDirectory(p) && isCsv(p)).forEach(csv -> {
                    try {
                        arrangeCsv(csv);
                    } catch (IOException e) {
                        failures.add(e);
                    }
                });
            }
            if (!failures.isEmpty()) {
                IOException first = failures.getFirst();
                for (int i = 1; i < failures.size(); i++) first.addSuppressed(failures.get(i));
                throw first;   // leave archive + scratch intact for inspection
            }

            // 3. Backup archive
            Files.createDirectories(backupDir);
            Path backupDest = backupDir.resolve(archiveName);
            Files.move(archive, backupDest, StandardCopyOption.REPLACE_EXISTING);
            System.out.printf("[BACKUP] %s → %s%n", archiveName, backupDest);

            // 4. Cleanup scratch
            cleanupTemp(scratchDir);

        } catch (IOException e) {
            System.err.printf("[ERROR] Failed processing %s: %s%n", archiveName, e.getMessage());
        }
    }

    // ── extraction ─────────────────────────────────────────────────────────────

    private int extractTar(Path archive, Path destDir) throws IOException {
        int count = 0;
        try (InputStream fi = Files.newInputStream(archive);
             InputStream bi = new BufferedInputStream(fi);
             InputStream ci = isGzipped(archive) ? new GzipCompressorInputStream(bi) : bi;
             TarArchiveInputStream tar = new TarArchiveInputStream(ci)) {

            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(entry)) continue;
                Path out = destDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(destDir))
                    throw new IOException("Unsafe path in archive: " + entry.getName());
                if (entry.isDirectory())
                    Files.createDirectories(out);
                else {
                    Files.createDirectories(out.getParent());
                    try (OutputStream os = Files.newOutputStream(out)) {
                        tar.transferTo(os);
                    }
                    count++;
                }
            }
        }
        return count;
    }

    // ── CSV arrangement ────────────────────────────────────────────────────────

    private void arrangeCsv(Path csv) throws IOException {
        String name = csv.getFileName().toString();
        String date = extractDate(name);
        Path destDir = pollDir.resolve(date);
        Path dest = destDir.resolve(name);
        if (Files.exists(dest)) {
            System.out.println("[SKIP] Already exists: " + dest);
            return;
        }
        Files.createDirectories(destDir);
        Files.move(csv, dest, StandardCopyOption.REPLACE_EXISTING);
        System.out.printf("[MOVE] %s → %s/%s%n", name, date, name);
    }

    // ── temp cleanup ──────────────────────────────────────────────────────────

    private void cleanupTemp(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    System.err.printf("[WARN] Could not delete %s: %s%n", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            System.err.printf("[WARN] Cleanup failed for %s: %s%n", dir, e.getMessage());
        }
        System.out.printf("[CLEANUP] %s%n", dir);
    }

    // ── dry-run peek ──────────────────────────────────────────────────────────

    private void dryRunPeek(Path archive, Path scratchDir) throws IOException {
        System.out.printf("[DRY-RUN] Would extract: %s → %s%n", archive.getFileName(), scratchDir);
        try (InputStream fi = Files.newInputStream(archive);
             InputStream bi = new BufferedInputStream(fi);
             InputStream ci = isGzipped(archive) ? new GzipCompressorInputStream(bi) : bi;
             TarArchiveInputStream tar = new TarArchiveInputStream(ci)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!entry.isDirectory() && isCsv(Paths.get(entry.getName()))) {
                    String fname = Paths.get(entry.getName()).getFileName().toString();
                    System.out.printf("[DRY-RUN] Would move CSV: %s → %s/%s%n", fname, extractDate(fname), fname);
                }
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    static String extractDate(String filename) {
        Matcher m = DATE_RE.matcher(filename);
        return m.find() ? m.group(1) : "obscure";
    }

    private static boolean isTar(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return TAR_SUFFIXES.stream().anyMatch(n::endsWith);
    }

    private static boolean isCsv(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return CSV_SUFFIXES.stream().anyMatch(n::endsWith);
    }

    private static boolean isGzipped(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".tar.gz") || n.endsWith(".tgz");
    }

    private static String stripTarSuffix(String name) {
        String low = name.toLowerCase();
        for (String suf : TAR_SUFFIXES)
            if (low.endsWith(suf)) return name.substring(0, name.length() - suf.length());
        return name;
    }

    private static void submit(ExecutorService exec, Phaser ph, Runnable task) {
        ph.register();
        exec.submit(() -> {
            try {
                task.run();
            } finally {
                ph.arriveAndDeregister();
            }
        });
    }


    private static Map<String, Object> requireSection(Map<String, Object> toon, String key) {
        Object val = toon.get(key);
        if (!(val instanceof Map))
            throw new IllegalArgumentException(
                    "Pipeline toon is missing required section '" + key + "'");
        return (Map<String, Object>) val;
    }

    private static String require(Map<String, Object> section, String key, String sectionName) {
        Object val = section.get(key);
        if (val == null || val.toString().isBlank())
            throw new IllegalArgumentException(
                    "Missing required key '" + key + "' in toon section '" + sectionName + "'");
        return val.toString();
    }
}
