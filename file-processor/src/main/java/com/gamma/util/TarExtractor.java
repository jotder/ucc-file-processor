package com.gamma.util;

import com.opencsv.CSVWriter;

import java.io.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Java 23 Program to extract tar/tar.gz files found in 'unknown' directories.
 * Supports Dry-Run mode.
 *
 * <p>Uses {@link TarUtil} for all archive operations and {@link VirtualThreadRunner}
 * for parallel task submission.
 */
public class TarExtractor {

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    private final Path baseDir;
    private final Path tempDir;
    private final Path reportPath;
    private final Path logPath;
    private final boolean dryRun;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Phaser phaser = new Phaser(1);

    private PrintWriter eventLogger;
    private final List<Map<String, Object>> reportRows = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> seenStems = ConcurrentHashMap.newKeySet();
    private final AtomicInteger foundCount = new AtomicInteger(0);

    public TarExtractor(String base, String temp, boolean dryRun) throws IOException {
        this.baseDir = Paths.get(base).toAbsolutePath();
        this.tempDir = Paths.get(temp).toAbsolutePath();
        this.dryRun  = dryRun;

        String suffix = dryRun ? ".dryrun" : "";
        this.reportPath = Paths.get("extract_report.csv" + suffix);
        this.logPath    = Paths.get("extract.log" + suffix);

        if (!dryRun) Files.createDirectories(tempDir);
        this.eventLogger = new PrintWriter(new BufferedWriter(new FileWriter(logPath.toFile(), true)));
    }

    public void run() throws Exception {
        if (dryRun) System.out.println("!!! DRY-RUN MODE ENABLED - No files will be extracted !!!");
        logEvent("run_start", "-", "-", "base=" + baseDir, "temp=" + tempDir);

        VirtualThreadRunner.submit(executor, phaser, () -> FileWalker.walk(executor, phaser, baseDir,
                entry -> {
                    if (isWantedTar(entry))
                        VirtualThreadRunner.submit(executor, phaser, () -> processArchive(entry));
                },
                (dir, e) -> logEvent("error", dir.toString(), "-", "msg=" + e.getMessage())));
        phaser.arriveAndAwaitAdvance();

        writeReport();
        logEvent("run_end", "-", "-", "found=" + foundCount.get());
        eventLogger.close();
        executor.shutdown();
    }

    private boolean isWantedTar(Path file) {
        if (!TarUtil.isTar(file)) return false;
        for (Path p : baseDir.relativize(file)) {
            if (p.toString().equals("unknown")) return true;
        }
        return false;
    }

    private void processArchive(Path src) {
        foundCount.incrementAndGet();
        String filename = src.getFileName().toString();
        String stem     = TarUtil.stripTarSuffix(filename);
        String source   = identifySource(src);

        if (!seenStems.add(stem)) stem = source + "__" + stem;

        Path target = tempDir.resolve(stem);
        Map<String, Object> row = createReportRow(src, target, filename, stem, source);

        try {
            long size = Files.size(src);
            row.put("bytes", size);

            if (TarUtil.isAlreadyExtracted(target, size)) {
                row.put("status", "already_done");
                logEvent("skip", filename, target.toString(), "reason=sentinel_match");
                reportRows.add(row);
                return;
            }

            if (dryRun) {
                System.out.println("[DRY-RUN] Would extract: " + filename + " -> " + target);
                int members = TarUtil.peekTar(src);
                row.put("status", "DRY-RUN_OK");
                row.put("members", members);
                logEvent("dry-run", filename, target.toString(), "members=" + members);
            } else {
                Files.createDirectories(target);
                logEvent("start", filename, target.toString(), "bytes=" + size);
                int members = TarUtil.extractTar(src, target);
                row.put("status", "extracted");
                row.put("members", members);
                String finishedAt = nowIso();
                row.put("finished_at", finishedAt);
                writeSentinel(target, src, size, members, finishedAt);
                logEvent("done", filename, target.toString(), "members=" + members);
            }
        } catch (Exception e) {
            row.put("status", "error");
            row.put("error", e.getMessage());
            logEvent("error", filename, target.toString(), "err=" + e.getMessage());
        }
        reportRows.add(row);
    }

    private void writeSentinel(Path target, Path src, long size, int members, String at)
            throws IOException {
        Map<String, Object> data = new HashMap<>();
        data.put("src",          src.toAbsolutePath().toString());
        data.put("src_size",     size);
        data.put("members",      members);
        data.put("extracted_at", at);
        TarUtil.writeExtractedSentinel(target, data);
    }

    private String identifySource(Path file) {
        Path rel = baseDir.relativize(file);
        List<String> parts = new ArrayList<>();
        rel.forEach(p -> parts.add(p.toString()));
        int idx = parts.indexOf("sources");
        if (idx != -1 && idx + 1 < parts.size()) return parts.get(idx + 1);
        return parts.isEmpty() ? "unknown" : parts.get(0);
    }

    private Map<String, Object> createReportRow(Path src, Path target,
            String name, String stem, String source) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rel_path",   baseDir.relativize(src).toString());
        row.put("source",     source);
        row.put("archive",    name);
        row.put("stem",       stem);
        row.put("src_abs",    src.toAbsolutePath().toString());
        row.put("target_abs", target.toAbsolutePath().toString());
        row.put("status",     "pending");
        row.put("members",    0);
        row.put("bytes",      0);
        row.put("started_at", nowIso());
        row.put("finished_at", "");
        row.put("error",      "");
        return row;
    }

    private String nowIso() {
        return ISO_FORMATTER.format(Instant.now().atOffset(ZoneOffset.UTC));
    }

    private synchronized void logEvent(String status, String archive, String target,
            String... extras) {
        String line = String.format("%s\t%s\t%s\t%s\t%s",
                nowIso(), status, archive, target, String.join("\t", extras));
        eventLogger.println(line);
        eventLogger.flush();
        System.out.println(line);
    }

    private void writeReport() throws IOException {
        String[] h = {"rel_path", "source", "archive", "stem", "src_abs", "target_abs",
                       "status", "members", "bytes", "started_at", "finished_at", "error"};
        try (CSVWriter w = new CSVWriter(new FileWriter(reportPath.toFile()))) {
            w.writeNext(h);
            for (Map<String, Object> r : reportRows) {
                String[] l = new String[h.length];
                for (int i = 0; i < h.length; i++)
                    l[i] = String.valueOf(r.getOrDefault(h[i], ""));
                w.writeNext(l);
            }
        }
    }

    public static void main(String[] args) {
        try {
            boolean dry = false;
            List<String> rem = new ArrayList<>();
            for (String a : args)
                if (a.equalsIgnoreCase("--dry-run")) dry = true;
                else rem.add(a);
            String b = rem.size() > 0 ? rem.get(0) : ".";
            String t = rem.size() > 1 ? rem.get(1) : "./temp";
            new TarExtractor(b, t, dry).run();
        } catch (Exception e) { e.printStackTrace(); }
    }
}
