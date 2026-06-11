package com.gamma.util;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

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

    // ── dirs from pipeline toon ───────────────────────────────────────────────

    private final Path pollDir;    // dirs.poll  — staging area + CSV date-folders
    private final Path tempDir;    // dirs.temp  — scratch space for extraction
    private final Path backupDir;  // dirs.backup — processed archives go here

    // ── copy_tars section ─────────────────────────────────────────────────────
    private final List<Path> copyTarsBaseDirs; // copy_tars.base_dirs (null if section absent)

    private final boolean dryRun;

    // ── construction ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public TarArranger(Map<String, Object> toon, boolean dryRun) {
        this.dryRun = dryRun;

        Map<String, Object> dirs = ToonHelper.requireSection(toon, "dirs");
        this.pollDir   = Paths.get(ToonHelper.require(dirs, "poll",   "dirs")).toAbsolutePath().normalize();
        this.tempDir   = Paths.get(ToonHelper.require(dirs, "temp",   "dirs")).toAbsolutePath().normalize();
        this.backupDir = Paths.get(ToonHelper.require(dirs, "backup", "dirs")).toAbsolutePath().normalize();

        // copy_tars section is optional — only needed for copyTars()
        Object copyTarsSec = toon.get("copy_tars");
        this.copyTarsBaseDirs = (copyTarsSec instanceof Map)
                ? ToonHelper.parseBaseDirs((Map<String, Object>) copyTarsSec)
                : null;
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
            throw new IllegalStateException(
                    "Pipeline toon is missing 'copy_tars' section — required for copy-tars command.");
        if (dryRun) System.out.println("!!! DRY-RUN MODE — no files will be copied !!!");
        System.out.println("[COPY-TARS] Scanning " + copyTarsBaseDirs.size()
                + " base dir(s) for *.tar.gz ...");

        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        Phaser ph = new Phaser(1);

        for (Path base : copyTarsBaseDirs) {
            if (!Files.exists(base)) {
                System.err.println("[WARN] Base dir not found, skipping: " + base);
                continue;
            }
            VirtualThreadRunner.submit(exec, ph, () -> FileWalker.walk(exec, ph, base,
                    entry -> {
                        if (TarUtil.isTar(entry))
                            VirtualThreadRunner.submit(exec, ph, () -> copyOneTar(entry));
                    },
                    (dir, e) -> System.err.println(
                            "[WARN] Cannot scan dir: " + dir + " — " + e.getMessage())));
        }

        ph.arriveAndAwaitAdvance();
        exec.shutdown();
        System.out.println("[COPY-TARS] Done.");
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
                if (Files.isRegularFile(entry) && TarUtil.isTar(entry)) {
                    total[0]++;
                    VirtualThreadRunner.submit(exec, ph, () -> processArchive(entry));
                }
            }
        }

        ph.arriveAndAwaitAdvance();
        exec.shutdown();
        System.out.printf("[EXTRACT] Done — %d archive(s) processed.%n", total[0]);
    }

    private void processArchive(Path archive) {
        String archiveName = archive.getFileName().toString();
        Path scratchDir = tempDir.resolve(TarUtil.stripTarSuffix(archiveName));

        System.out.printf("[ARCHIVE] %s%n", archiveName);
        try {
            if (dryRun) {
                dryRunPeek(archive, scratchDir);
                return;
            }

            // 1. Extract
            Files.createDirectories(scratchDir);
            int count = TarUtil.extractTar(archive, scratchDir);
            System.out.printf("[EXTRACT] %s → %s (%d file(s))%n", archiveName, scratchDir, count);

            // 2. Arrange CSVs — collect failures so a partial move does not trigger backup/cleanup
            List<IOException> failures = new ArrayList<>();
            try (var walk = Files.walk(scratchDir)) {
                walk.filter(p -> !Files.isDirectory(p) && TarUtil.isCsv(p.getFileName().toString()))
                    .forEach(csv -> {
                        try { arrangeCsv(csv); }
                        catch (IOException e) { failures.add(e); }
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
            TarUtil.deleteTree(scratchDir);
            System.out.printf("[CLEANUP] %s%n", scratchDir);

        } catch (IOException e) {
            System.err.printf("[ERROR] Failed processing %s: %s%n", archiveName, e.getMessage());
        }
    }

    // ── CSV arrangement ────────────────────────────────────────────────────────

    private void arrangeCsv(Path csv) throws IOException {
        String name = csv.getFileName().toString();
        String date = TarUtil.extractDate(name);
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

    // ── dry-run peek ──────────────────────────────────────────────────────────

    private void dryRunPeek(Path archive, Path scratchDir) throws IOException {
        System.out.printf("[DRY-RUN] Would extract: %s → %s%n", archive.getFileName(), scratchDir);
        // Walk tar entries using commons-compress via TarUtil; replicate just enough for reporting
        try (var fi = Files.newInputStream(archive);
             var bi = new java.io.BufferedInputStream(fi);
             var ci = TarUtil.isGzipped(archive)
                     ? new org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(bi) : bi;
             var tar = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(ci)) {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    String fname = Paths.get(entry.getName()).getFileName().toString();
                    if (TarUtil.isCsv(fname))
                        System.out.printf("[DRY-RUN] Would move CSV: %s → %s/%s%n",
                                fname, TarUtil.extractDate(fname), fname);
                }
            }
        }
    }
}
