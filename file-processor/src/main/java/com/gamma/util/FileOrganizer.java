package com.gamma.util;

import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Searches configured base directories for files listed in a CSV manifest and
 * optionally copies each found file into the date-partitioned poll directory.
 *
 * <p>Configuration is read from the {@code search} section of the pipeline {@code .toon} file:
 * <pre>
 *   search:
 *     base_dirs[2]: /mnt/rawdata/feed1, /mnt/rawdata/feed2
 *     csv_input:     GSM_RESUBMISSION_1.csv
 *     log_available: available_files.csv
 *     log_missing:   missing_files.csv
 *     log_error:     error_log.csv
 * </pre>
 *
 * <p>The copy destination is {@code dirs.poll} from the same toon file.
 * Files are placed at {@code dirs.poll/<YYYYMMDD>/<filename>} — the same layout
 * that {@code SourceProcessor} expects to find when it polls the inbox.
 *
 * <p>Two run modes:
 * <ul>
 *   <li>{@link #run()} — search <em>and</em> copy found files into the poll directory</li>
 *   <li>{@link #runSearch()} — search only; log found / missing without copying</li>
 * </ul>
 *
 * <p>Produced log files (paths from {@code search.log_*} keys):
 * <ul>
 *   <li>{@code available_files.csv} — TAB, source, Date, FILENAME, Status, found_path, copied_to_path</li>
 *   <li>{@code missing_files.csv}   — TAB, source, Date, FILENAME</li>
 *   <li>{@code error_log.csv}       — path, error_message</li>
 * </ul>
 */
public class FileOrganizer {

    // ── state ─────────────────────────────────────────────────────────────────

    private final List<Path> baseDirs;
    private final Path       targetDir;     // dirs.poll — where copies land
    private final String     csvInput;
    private final String     logAvailablePath;
    private final String     logMissingPath;
    private final String     logErrorPath;

    private final ConcurrentHashMap<String, List<String[]>> wantedFiles
            = new ConcurrentHashMap<>(100_000);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Phaser           phaser  = new Phaser(1);

    private CSVWriter availableLogger;
    private CSVWriter missingLogger;
    private CSVWriter errorLogger;

    private final AtomicInteger foundCount   = new AtomicInteger(0);
    private final AtomicInteger totalTargets = new AtomicInteger(0);

    private final boolean dryRun;
    private final boolean searchOnly;

    // ── construction ──────────────────────────────────────────────────────────

    /**
     * @param toon       full pipeline toon map (must contain {@code search} and {@code dirs} sections)
     * @param dryRun     simulate without touching the filesystem
     * @param searchOnly search and log only; do not copy files
     */
    public FileOrganizer(Map<String, Object> toon, boolean dryRun, boolean searchOnly)
            throws IOException {
        this.dryRun     = dryRun;
        this.searchOnly = searchOnly;

        Map<String, Object> searchSec = ToonHelper.requireSection(toon, "search");
        this.baseDirs         = ToonHelper.parseBaseDirs(searchSec);
        this.csvInput         = ToonHelper.require(searchSec, "csv_input",     "search");
        this.logAvailablePath = ToonHelper.opt(searchSec, "log_available", "available_files.csv");
        this.logMissingPath   = ToonHelper.opt(searchSec, "log_missing",   "missing_files.csv");
        this.logErrorPath     = ToonHelper.opt(searchSec, "log_error",     "error_log.csv");

        Map<String, Object> dirs = ToonHelper.requireSection(toon, "dirs");
        this.targetDir = Paths.get(ToonHelper.require(dirs, "poll", "dirs")).toAbsolutePath().normalize();

        // Guard: poll dir must not be inside any base dir (would cause the walk to
        // recurse into its own output tree and produce infinite loops or stale copies).
        for (Path base : baseDirs) {
            if (targetDir.startsWith(base))
                throw new IllegalArgumentException(
                        "dirs.poll (" + targetDir + ") must not be nested inside base dir " + base);
        }

        initLoggers();
    }

    // ── public entry points ────────────────────────────────────────────────────

    /** Search base directories and copy matching files into the poll directory by date. */
    public void run() throws Exception {
        if (dryRun) System.out.println("!!! DRY-RUN MODE — no files will be copied !!!");
        loadWantedFiles();
        walkAllBaseDirs();
        phaser.arriveAndAwaitAdvance();
        finalizeReport();
    }

    /** Search base directories and log found / missing files without copying. */
    public void runSearch() throws Exception {
        System.out.println("[SEARCH] Search-only mode — no files will be copied.");
        loadWantedFiles();
        walkAllBaseDirs();
        phaser.arriveAndAwaitAdvance();
        finalizeReport();
    }

    // ── initialisation ─────────────────────────────────────────────────────────

    private void initLoggers() throws IOException {
        String suffix = dryRun ? ".dryrun" : "";
        this.availableLogger = openWriter(logAvailablePath + suffix);
        this.missingLogger   = openWriter(logMissingPath   + suffix);
        this.errorLogger     = openWriter(logErrorPath     + suffix);

        availableLogger.writeNext(new String[]{"TAB", "source", "Date", "FILENAME",
                                               "Status", "found_path", "copied_to_path"});
        missingLogger.writeNext(new String[]{"TAB", "source", "Date", "FILENAME"});
        errorLogger.writeNext(new String[]{"path", "error_message"});
    }

    private static CSVWriter openWriter(String path) throws IOException {
        return new CSVWriter(new FileWriter(path));
    }

    // ── walk + match ───────────────────────────────────────────────────────────

    private void loadWantedFiles() throws Exception {
        try (CSVReader reader = new CSVReader(new FileReader(csvInput))) {
            reader.readNext(); // skip header
            String[] row;
            while ((row = reader.readNext()) != null) {
                if (row.length < 4) continue;
                String filename = row[3];
                if (filename == null || filename.isBlank()) continue;
                wantedFiles.computeIfAbsent(filename,
                        k -> Collections.synchronizedList(new ArrayList<>())).add(row);
                totalTargets.incrementAndGet();
            }
        }
        System.out.println("Loaded " + totalTargets.get() + " records targeting "
                + wantedFiles.size() + " unique filenames.");
    }

    private void walkAllBaseDirs() {
        for (Path dir : baseDirs) {
            if (Files.exists(dir)) {
                VirtualThreadRunner.submit(executor, phaser, () -> walkParallel(dir));
            } else {
                logError(dir.toString(), "Base directory does not exist.");
            }
        }
    }

    private void walkParallel(Path dir) {
        System.out.println("[DIR] Scanning: " + dir);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    VirtualThreadRunner.submit(executor, phaser, () -> walkParallel(entry));
                } else {
                    checkAndProcessFile(entry);
                }
            }
        } catch (IOException e) {
            logError(dir.toString(), e.getMessage());
        }
    }

    private void checkAndProcessFile(Path file) {
        String filename = file.getFileName().toString();
        List<String[]> rows = wantedFiles.remove(filename);
        if (rows == null) return;

        foundCount.incrementAndGet();
        String sourceAbs = file.toAbsolutePath().toString();

        if (searchOnly || dryRun) {
            for (String[] row : rows) {
                String label = dryRun ? "DRY-RUN_FOUND" : "FOUND";
                logAvailable(row[0], row[1], row[2], row[3], label, sourceAbs, "");
            }
        } else {
            for (String[] row : rows)
                VirtualThreadRunner.submit(executor, phaser, () -> performCopy(row, file));
        }
    }

    // ── copy ───────────────────────────────────────────────────────────────────

    private void performCopy(String[] row, Path sourcePath) {
        String tab = row[0], source = row[1], date = row[2], filename = row[3];
        String sourceAbs = sourcePath.toAbsolutePath().toString();
        String targetAbs = "N/A";

        try {
            // Use "obscure" for blank/null dates — consistent with TarInboxPreparer
            String finalDate = (date == null || date.isBlank()
                    || date.equalsIgnoreCase("null")) ? "obscure" : date.trim();

            // Files go directly into dirs.poll/<date>/ — the toon is already source-specific
            Path destDir  = targetDir.resolve(finalDate);
            Path destPath = destDir.resolve(filename);
            targetAbs = destPath.toAbsolutePath().toString();

            Files.createDirectories(destDir);
            Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[COPY] " + sourceAbs + " → " + targetAbs);
            logAvailable(tab, source, date, filename, "SUCCESS", sourceAbs, targetAbs);

        } catch (Exception e) {
            System.err.println("[ERR] Copy failed: " + filename + " (" + e.getMessage() + ")");
            logAvailable(tab, source, date, filename,
                         "ERROR: " + e.getMessage(), sourceAbs, targetAbs);
        }
    }

    // ── logging ────────────────────────────────────────────────────────────────

    private synchronized void logAvailable(String... data) { availableLogger.writeNext(data); }
    private synchronized void logError(String path, String msg) {
        errorLogger.writeNext(new String[]{path, msg});
    }

    private void finalizeReport() throws IOException {
        System.out.println("\nCrawl complete. Logging missing files...");
        wantedFiles.forEach((filename, rows) -> {
            for (String[] row : rows)
                missingLogger.writeNext(new String[]{row[0], row[1], row[2], row[3]});
        });
        executor.shutdown();
        availableLogger.close();
        missingLogger.close();
        errorLogger.close();

        String mode = dryRun ? "DRY-RUN" : (searchOnly ? "SEARCH" : "LIVE");
        System.out.println("\n--- FINAL SUMMARY (" + mode + ") ---");
        System.out.println("Total CSV Records      : " + totalTargets.get());
        System.out.println("Unique Files Found     : " + foundCount.get());
        System.out.println("Records Logged Missing : " + (totalTargets.get() - foundCount.get()));
    }
}
