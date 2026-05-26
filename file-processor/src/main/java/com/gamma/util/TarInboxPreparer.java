package com.gamma.util;

import dev.toonformat.jtoon.JToon;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Prepares the ETL inbox from raw {@code .tar.gz} delivery archives.
 *
 * <p>For each {@code .tar.gz} found in {@code sourceDir}:
 * <ol>
 *   <li>Extract all members to a per-archive sub-directory under {@code tempDir}</li>
 *   <li>Walk the extracted tree for {@code *.csv} and {@code *.csv.gz} files</li>
 *   <li>Move each CSV file into {@code sourceDir/<date>/} where {@code <date>} is
 *       the first {@code YYYYMMDD} token found in the filename, or {@code "obscure"}</li>
 *   <li>Move the original archive to {@code backupDir}</li>
 *   <li>Delete the temporary extraction directory</li>
 * </ol>
 *
 * <p>After running, the source directory contains the usual date-partitioned layout
 * that the ETL pipeline's {@code pollInbox()} expects.
 *
 * <p>Configuration is read from a pipeline {@code .toon} file.  The following
 * {@code dirs} keys are used:
 * <pre>
 *   dirs:
 *     poll:   inbox/adjustment   # scanned for .tar.gz and target for arranged CSVs
 *     temp:   temp/adjustment    # scratch space; cleaned up after each archive
 *     backup: backup/adjustment  # original .tar.gz files are moved here
 * </pre>
 *
 * <p>Supports {@code --dry-run} mode: all actions are logged but no files are moved.
 *
 * <p>Uses Java 23 virtual threads + {@link Phaser} for parallel archive processing,
 * matching the style of {@link TarExtractor} and {@link IntegratedProcessor}.
 */
public class TarInboxPreparer {

    // ── constants ─────────────────────────────────────────────────────────────

    /** Recognised archive suffixes (only .tar.gz / .tgz are gzip-compressed). */
    private static final List<String> TAR_SUFFIXES = List.of(".tar.gz", ".tgz", ".tar");

    /** CSV file suffixes to pick out of the extracted tree. */
    private static final List<String> CSV_SUFFIXES = List.of(".csv.gz", ".csv");

    /**
     * Date pattern: first 8-digit sequence starting with 19xx or 20xx in a filename.
     * Covers years 1900–2099 and avoids matching arbitrary 8-digit numbers.
     * Example: "adj_export_20200403_v2.csv.gz" → "20200403"
     */
    private static final Pattern DATE_RE = Pattern.compile("((?:19|20)\\d{6})");

    // ── instance state ────────────────────────────────────────────────────────

    private final Path sourceDir;   // inbox root; CSVs land here after extraction
    private final Path tempDir;     // scratch space; cleaned up after each archive
    private final Path backupDir;   // archives are moved here after processing

    private final boolean dryRun;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Phaser phaser = new Phaser(1);

    private final AtomicInteger archivesProcessed = new AtomicInteger();
    private final AtomicInteger csvsMoved         = new AtomicInteger();
    private final AtomicInteger csvsSkipped       = new AtomicInteger();
    private final AtomicInteger archivesBacked    = new AtomicInteger();

    // ── construction ──────────────────────────────────────────────────────────

    /**
     * Loads configuration from {@code toonConfigPath} and resolves all directories.
     *
     * <p>Required {@code dirs} keys in the toon file:
     * <ul>
     *   <li>{@code poll}   — scanned for {@code .tar.gz} files; CSVs are arranged here</li>
     *   <li>{@code temp}   — scratch space for extraction (created if absent)</li>
     *   <li>{@code backup} — destination for processed archives (created if absent)</li>
     * </ul>
     *
     * @param toonConfigPath path to the pipeline {@code .toon} file
     * @param dryRun         when {@code true}, log intended actions without modifying files
     */
    public TarInboxPreparer(String toonConfigPath, boolean dryRun) throws IOException {
        File configFile = new File(toonConfigPath);
        if (!configFile.exists())
            throw new FileNotFoundException("Pipeline config not found: " + toonConfigPath);

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>)
                JToon.decode(Files.readString(configFile.toPath(), StandardCharsets.UTF_8));

        @SuppressWarnings("unchecked")
        Map<String, Object> dirs = (Map<String, Object>) config.get("dirs");
        if (dirs == null)
            throw new IllegalArgumentException("Missing 'dirs' section in " + toonConfigPath);

        this.sourceDir = requireDir(dirs, "poll",   toonConfigPath);
        this.tempDir   = requireDir(dirs, "temp",   toonConfigPath);
        this.backupDir = requireDir(dirs, "backup", toonConfigPath);
        this.dryRun    = dryRun;

        validateOutsideSource(toonConfigPath);

        if (!dryRun) {
            Files.createDirectories(this.tempDir);
            Files.createDirectories(this.backupDir);
        }
    }

    /**
     * Ensures {@code tempDir} and {@code backupDir} are not nested under {@code sourceDir}.
     * Putting scratch or archive dirs inside the poll directory would cause the ETL to
     * pick them up as input files on the next run.
     */
    private void validateOutsideSource(String configPath) {
        Path src = sourceDir.normalize();
        Map<String, Path> check = Map.of("temp", tempDir.normalize(), "backup", backupDir.normalize());
        check.forEach((key, dir) -> {
            if (dir.startsWith(src))
                throw new IllegalArgumentException(String.format(
                        "Config error in %s: dirs.%s (%s) must be outside the source/poll directory (%s)",
                        configPath, key, dir, src));
        });
    }

    /** Reads a required string key from the dirs map and converts it to an absolute Path. */
    private static Path requireDir(Map<String, Object> dirs, String key, String configPath) {
        Object val = dirs.get(key);
        if (val == null || val.toString().isBlank())
            throw new IllegalArgumentException(
                    "Missing required dirs." + key + " in config: " + configPath);
        return Paths.get(val.toString()).toAbsolutePath();
    }

    // ── public entry point ────────────────────────────────────────────────────

    public void run() throws Exception {
        if (dryRun) System.out.println("!!! DRY-RUN MODE — no files will be modified !!!");

        System.out.println("[START] TarInboxPreparer");
        System.out.println("  source : " + sourceDir);
        System.out.println("  temp   : " + tempDir);
        System.out.println("  backup : " + backupDir);

        // Walk the top level of sourceDir for archive files only.
        // Sub-directories (date buckets already placed by a previous run) are skipped.
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sourceDir)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry) && isTarGz(entry))
                    submitTask(() -> processArchive(entry));
            }
        }

        // Wait for all virtual-thread tasks to complete
        phaser.arriveAndAwaitAdvance();
        executor.shutdown();
        printSummary();
    }

    // ── task submission ───────────────────────────────────────────────────────

    private void submitTask(Runnable task) {
        phaser.register();
        executor.submit(() -> {
            try { task.run(); }
            finally { phaser.arriveAndDeregister(); }
        });
    }

    // ── per-archive processing ─────────────────────────────────────────────────

    /**
     * Full lifecycle for one archive: extract → arrange CSVs → backup original → cleanup temp.
     */
    private void processArchive(Path archivePath) {
        String archiveName = archivePath.getFileName().toString();
        Path extractDir = tempDir.resolve(stripTarSuffix(archiveName));

        System.out.printf("[ARCHIVE] %s%n", archiveName);

        try {
            // ── step 1: extract to temp ───────────────────────────────────────
            if (dryRun) {
                System.out.printf("[DRY-RUN] Would extract: %s → %s%n", archiveName, extractDir);
                peekAndReport(archivePath);
            } else {
                Files.createDirectories(extractDir);
                int count = extractTar(archivePath, extractDir);
                archivesProcessed.incrementAndGet();
                System.out.printf("[EXTRACT] %s → %s (%d file(s))%n", archiveName, extractDir, count);

                // ── step 2: walk extracted tree and move CSVs into sourceDir ─
                arrangeCsvs(extractDir);

                // ── step 3: move original archive to backup ───────────────────
                backupArchive(archivePath, archiveName);

                // ── step 4: delete temp extraction directory ──────────────────
                cleanupTemp(extractDir);
            }

        } catch (Exception e) {
            System.err.printf("[ERR] Failed processing %s: %s%n", archiveName, e.getMessage());
            e.printStackTrace();
        }
    }

    // ── step 1 helpers: extraction ────────────────────────────────────────────

    /**
     * Dry-run only: peeks inside the archive and reports what would be extracted.
     */
    private void peekAndReport(Path src) throws IOException {
        try (InputStream fi = Files.newInputStream(src);
             InputStream bi = new BufferedInputStream(fi);
             InputStream ci = isGzipped(src) ? new GzipCompressorInputStream(bi) : bi;
             TarArchiveInputStream ti = new TarArchiveInputStream(ci)) {

            TarArchiveEntry entry;
            while ((entry = ti.getNextEntry()) != null) {
                if (!entry.isDirectory() && isCsv(entry.getName()))
                    System.out.printf("[DRY-RUN] Would move CSV: %s → %s/%s%n",
                            entry.getName(),
                            sourceDir.resolve(extractDate(Paths.get(entry.getName()).getFileName().toString())),
                            Paths.get(entry.getName()).getFileName());
            }
        }
    }

    /**
     * Extracts all members of a {@code .tar.gz} (or plain {@code .tar}) to {@code target}.
     * Path traversal attacks are blocked: any entry whose resolved path escapes {@code target}
     * throws an {@link IOException}.
     *
     * @return number of regular files extracted
     */
    private int extractTar(Path src, Path target) throws IOException {
        int count = 0;
        try (InputStream fi = Files.newInputStream(src);
             InputStream bi = new BufferedInputStream(fi);
             InputStream ci = isGzipped(src) ? new GzipCompressorInputStream(bi) : bi;
             TarArchiveInputStream ti = new TarArchiveInputStream(ci)) {

            TarArchiveEntry entry;
            while ((entry = ti.getNextEntry()) != null) {
                if (!ti.canReadEntryData(entry)) continue;

                // Resolve and validate against path-traversal
                Path entryPath = target.resolve(entry.getName()).normalize();
                if (!entryPath.startsWith(target))
                    throw new IOException("Unsafe path in archive: " + entry.getName());

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (OutputStream os = Files.newOutputStream(entryPath)) {
                        ti.transferTo(os);
                    }
                    count++;
                }
            }
        }
        return count;
    }

    // ── step 2 helper: arrange extracted CSVs by date ────────────────────────

    /**
     * Recursively walks {@code extractDir}, finds every {@code *.csv} / {@code *.csv.gz},
     * and moves each one to {@code sourceDir/<date>/<filename>}.
     */
    private void arrangeCsvs(Path extractDir) throws IOException {
        // Failures are collected rather than swallowed so that a partial move causes
        // processArchive() to abort — preventing backupArchive() and cleanupTemp() from
        // running and leaving the archive and temp dir intact for a manual retry.
        List<IOException> failures = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(extractDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> isCsv(p.getFileName().toString()))
                .forEach(csvFile -> {
                    String filename = csvFile.getFileName().toString();
                    String date = extractDate(filename);
                    Path targetDir  = sourceDir.resolve(date);
                    Path targetFile = targetDir.resolve(filename);

                    try {
                        if (Files.exists(targetFile)) {
                            System.out.printf("[SKIP] Already exists: %s/%s%n", date, filename);
                            csvsSkipped.incrementAndGet();
                            return;
                        }
                        Files.createDirectories(targetDir);
                        Files.move(csvFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                        csvsMoved.incrementAndGet();
                        System.out.printf("[MOVE] %s → %s/%s%n", filename, date, filename);
                    } catch (IOException e) {
                        System.err.printf("[ERR] Could not move %s: %s%n", filename, e.getMessage());
                        failures.add(e);
                    }
                });
        }

        if (!failures.isEmpty()) {
            IOException first = failures.get(0);
            if (failures.size() > 1)
                first.addSuppressed(new IOException((failures.size() - 1) + " further move failure(s) suppressed"));
            throw first;
        }
    }

    // ── step 3 helper: backup original archive ────────────────────────────────

    /**
     * Moves the processed archive to {@code backupDir}, replacing any older copy.
     */
    private void backupArchive(Path archivePath, String archiveName) throws IOException {
        Path dst = backupDir.resolve(archiveName);
        Files.move(archivePath, dst, StandardCopyOption.REPLACE_EXISTING);
        archivesBacked.incrementAndGet();
        System.out.printf("[BACKUP] %s → %s%n", archiveName, dst);
    }

    // ── step 4 helper: delete temp extraction directory ───────────────────────

    /**
     * Recursively deletes {@code dir} and all its contents.
     * Called after all CSVs have been moved out, so only empty directories
     * (and any non-CSV members the archive may have contained) remain.
     */
    private void cleanupTemp(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        // Log but don't abort — a locked file (e.g. AV scan on Windows) is
                        // non-fatal; the temp directory will be cleaned up on the next run.
                        System.err.printf("[WARN] Could not delete %s: %s%n", p, e.getMessage());
                    }
                });
        }
        System.out.printf("[CLEANUP] %s%n", dir);
    }

    // ── date extraction ───────────────────────────────────────────────────────

    /**
     * Extracts the first {@code YYYYMMDD}-style date token from a filename.
     * Recognises years 1900–2099 ({@code (19|20)\d{6}}).
     * Returns {@code "obscure"} when no date token is found.
     *
     * <p>Examples:
     * <pre>
     *   "adj_export_20200403_v2.csv.gz" → "20200403"
     *   "feed_2019_11_15.csv"           → "20191115"  (only if written as 20191115)
     *   "backup_snapshot.csv"           → "obscure"
     * </pre>
     */
    static String extractDate(String filename) {
        Matcher m = DATE_RE.matcher(filename);
        return m.find() ? m.group(1) : "obscure";
    }

    // ── file-type helpers ─────────────────────────────────────────────────────

    private static boolean isTarGz(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return TAR_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    private static boolean isCsv(String name) {
        String low = name.toLowerCase();
        return CSV_SUFFIXES.stream().anyMatch(low::endsWith);
    }

    private static boolean isGzipped(Path src) {
        String name = src.getFileName().toString().toLowerCase();
        return name.endsWith(".tar.gz") || name.endsWith(".tgz");
    }

    private static String stripTarSuffix(String name) {
        String low = name.toLowerCase();
        for (String suf : TAR_SUFFIXES)
            if (low.endsWith(suf)) return name.substring(0, name.length() - suf.length());
        return name;
    }

    // ── summary ───────────────────────────────────────────────────────────────

    private void printSummary() {
        System.out.println("\n--- TarInboxPreparer Summary (" + (dryRun ? "DRY-RUN" : "LIVE") + ") ---");
        System.out.println("Archives extracted : " + archivesProcessed.get());
        System.out.println("CSV files moved    : " + csvsMoved.get());
        System.out.println("CSV files skipped  : " + csvsSkipped.get());
        System.out.println("Archives backed up : " + archivesBacked.get());
    }

    // ── main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        boolean dry = false;
        List<String> rem = new ArrayList<>();
        for (String a : args)
            if (a.equalsIgnoreCase("--dry-run")) dry = true;
            else rem.add(a);

        if (rem.size() < 1) {
            System.err.println("Usage: TarInboxPreparer [--dry-run] <pipeline.toon>");
            System.exit(1);
        }
        try {
            new TarInboxPreparer(rem.get(0), dry).run();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
