package com.gamma.util;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
        Map<String, Object> config = ToonHelper.load(toonConfigPath);
        Map<String, Object> dirs   = ToonHelper.requireSection(config, "dirs");

        this.sourceDir = Paths.get(ToonHelper.require(dirs, "poll",   "dirs")).toAbsolutePath();
        this.tempDir   = Paths.get(ToonHelper.require(dirs, "temp",   "dirs")).toAbsolutePath();
        this.backupDir = Paths.get(ToonHelper.require(dirs, "backup", "dirs")).toAbsolutePath();
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
        Map<String, Path> check = Map.of(
                "temp",   tempDir.normalize(),
                "backup", backupDir.normalize());
        check.forEach((key, dir) -> {
            if (dir.startsWith(src))
                throw new IllegalArgumentException(String.format(
                        "Config error in %s: dirs.%s (%s) must be outside the source/poll directory (%s)",
                        configPath, key, dir, src));
        });
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
                if (Files.isRegularFile(entry) && TarUtil.isTar(entry))
                    VirtualThreadRunner.submit(executor, phaser, () -> processArchive(entry));
            }
        }

        // Wait for all virtual-thread tasks to complete
        phaser.arriveAndAwaitAdvance();
        executor.shutdown();
        printSummary();
    }

    // ── per-archive processing ─────────────────────────────────────────────────

    /**
     * Full lifecycle for one archive: extract → arrange CSVs → backup original → cleanup temp.
     */
    private void processArchive(Path archivePath) {
        String archiveName = archivePath.getFileName().toString();
        Path extractDir = tempDir.resolve(TarUtil.stripTarSuffix(archiveName));

        System.out.printf("[ARCHIVE] %s%n", archiveName);

        try {
            // ── step 1: extract to temp ───────────────────────────────────────
            if (dryRun) {
                System.out.printf("[DRY-RUN] Would extract: %s → %s%n", archiveName, extractDir);
                peekAndReport(archivePath);
            } else {
                Files.createDirectories(extractDir);
                int count = TarUtil.extractTar(archivePath, extractDir);
                archivesProcessed.incrementAndGet();
                System.out.printf("[EXTRACT] %s → %s (%d file(s))%n", archiveName, extractDir, count);

                // ── step 2: walk extracted tree and move CSVs into sourceDir ─
                arrangeCsvs(extractDir);

                // ── step 3: move original archive to backup ───────────────────
                backupArchive(archivePath, archiveName);

                // ── step 4: delete temp extraction directory ──────────────────
                TarUtil.deleteTree(extractDir);
                System.out.printf("[CLEANUP] %s%n", extractDir);
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
             InputStream ci = TarUtil.isGzipped(src)
                     ? new org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(bi) : bi;
             org.apache.commons.compress.archivers.tar.TarArchiveInputStream ti =
                     new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(ci)) {

            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            while ((entry = ti.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    String fname = Paths.get(entry.getName()).getFileName().toString();
                    if (TarUtil.isCsv(fname))
                        System.out.printf("[DRY-RUN] Would move CSV: %s → %s/%s%n",
                                fname,
                                sourceDir.resolve(TarUtil.extractDate(fname)),
                                fname);
                }
            }
        }
    }

    // ── step 2 helper: arrange extracted CSVs by date ────────────────────────

    /**
     * Recursively walks {@code extractDir}, finds every {@code *.csv} / {@code *.csv.gz},
     * and moves each one to {@code sourceDir/<date>/<filename>}.
     */
    private void arrangeCsvs(Path extractDir) throws IOException {
        // Failures are collected rather than swallowed so that a partial move causes
        // processArchive() to abort — preventing backupArchive() and cleanup from
        // running and leaving the archive and temp dir intact for a manual retry.
        List<IOException> failures = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(extractDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> TarUtil.isCsv(p.getFileName().toString()))
                .forEach(csvFile -> {
                    String filename = csvFile.getFileName().toString();
                    String date = TarUtil.extractDate(filename);
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
                first.addSuppressed(new IOException(
                        (failures.size() - 1) + " further move failure(s) suppressed"));
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

        if (rem.isEmpty()) {
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
