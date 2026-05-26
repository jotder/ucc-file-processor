package com.gamma.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.opencsv.CSVWriter;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Java 23 Program to extract tar/tar.gz files found in 'unknown' directories.
 * Supports Dry-Run mode.
 */
public class TarExtractor {

    private static final Set<String> TAR_SUFFIXES = Set.of(".tar.gz", ".tgz", ".tar");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
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
        this.dryRun = dryRun;
        
        String suffix = dryRun ? ".dryrun" : "";
        this.reportPath = Paths.get("extract_report.csv" + suffix);
        this.logPath = Paths.get("extract.log" + suffix);
        
        if (!dryRun) Files.createDirectories(tempDir);
        this.eventLogger = new PrintWriter(new BufferedWriter(new FileWriter(logPath.toFile(), true)));
    }

    public void run() throws Exception {
        if (dryRun) System.out.println("!!! DRY-RUN MODE ENABLED - No files will be extracted !!!");
        logEvent("run_start", "-", "-", "base=" + baseDir, "temp=" + tempDir);

        submitTask(() -> walkParallel(baseDir));
        phaser.arriveAndAwaitAdvance();
        
        writeReport();
        logEvent("run_end", "-", "-", "found=" + foundCount.get());
        eventLogger.close();
        executor.shutdown();
    }

    private void submitTask(Runnable task) {
        phaser.register();
        executor.submit(() -> {
            try { task.run(); } finally { phaser.arriveAndDeregister(); }
        });
    }

    private void walkParallel(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    submitTask(() -> walkParallel(entry));
                } else if (isWantedTar(entry)) {
                    submitTask(() -> processArchive(entry));
                }
            }
        } catch (IOException e) {
            logEvent("error", dir.toString(), "-", "msg=" + e.getMessage());
        }
    }

    private boolean isWantedTar(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (TAR_SUFFIXES.stream().noneMatch(name::endsWith)) return false;
        for (Path p : baseDir.relativize(file)) {
            if (p.toString().equals("unknown")) return true;
        }
        return false;
    }

    private void processArchive(Path src) {
        foundCount.incrementAndGet();
        String filename = src.getFileName().toString();
        String stem = stripTarSuffix(filename);
        String source = identifySource(src);
        
        if (!seenStems.add(stem)) stem = source + "__" + stem;

        Path target = tempDir.resolve(stem);
        Map<String, Object> row = createReportRow(src, target, filename, stem, source);

        try {
            long size = Files.size(src);
            row.put("bytes", size);

            if (isAlreadyDone(target, size)) {
                row.put("status", "already_done");
                logEvent("skip", filename, target.toString(), "reason=sentinel_match");
                reportRows.add(row);
                return;
            }

            if (dryRun) {
                System.out.println("[DRY-RUN] Would extract: " + filename + " -> " + target);
                int members = peekTar(src);
                row.put("status", "DRY-RUN_OK");
                row.put("members", members);
                logEvent("dry-run", filename, target.toString(), "members=" + members);
            } else {
                Files.createDirectories(target);
                logEvent("start", filename, target.toString(), "bytes=" + size);
                int members = extractTar(src, target);
                row.put("status", "extracted");
                row.put("members", members);
                row.put("finished_at", nowIso());
                writeSentinel(target, src, size, members, (String)row.get("finished_at"));
                logEvent("done", filename, target.toString(), "members=" + members);
            }
        } catch (Exception e) {
            row.put("status", "error");
            row.put("error", e.getMessage());
            logEvent("error", filename, target.toString(), "err=" + e.getMessage());
        }
        reportRows.add(row);
    }

    private int peekTar(Path src) throws IOException {
        int count = 0;
        try (InputStream fi = Files.newInputStream(src);
             InputStream bi = new BufferedInputStream(fi);
             InputStream ci = src.toString().endsWith(".tar") ? bi : new GzipCompressorInputStream(bi);
             TarArchiveInputStream ti = new TarArchiveInputStream(ci)) {
            while (ti.getNextEntry() != null) count++;
        }
        return count;
    }

    private int extractTar(Path src, Path target) throws IOException {
        int count = 0;
        try (InputStream fi = Files.newInputStream(src);
             InputStream bi = new BufferedInputStream(fi);
             InputStream ci = src.toString().endsWith(".tar") ? bi : new GzipCompressorInputStream(bi);
             TarArchiveInputStream ti = new TarArchiveInputStream(ci)) {
            TarArchiveEntry entry;
            while ((entry = ti.getNextEntry()) != null) {
                if (!ti.canReadEntryData(entry)) continue;
                Path entryPath = target.resolve(entry.getName()).normalize();
                if (!entryPath.startsWith(target)) throw new IOException("Unsafe path: " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (OutputStream os = Files.newOutputStream(entryPath)) { ti.transferTo(os); }
                    count++;
                }
            }
        }
        return count;
    }

    private boolean isAlreadyDone(Path target, long currentSize) {
        Path sentinel = target.resolve(".extracted.json");
        if (!Files.exists(sentinel)) return false;
        try {
            Map data = GSON.fromJson(Files.readString(sentinel), Map.class);
            return data != null && ((Double)data.get("src_size")).longValue() == currentSize;
        } catch (Exception e) { return false; }
    }

    private void writeSentinel(Path target, Path src, long size, int members, String at) throws IOException {
        Map<String, Object> data = new HashMap<>();
        data.put("src", src.toAbsolutePath().toString());
        data.put("src_size", size);
        data.put("members", members);
        data.put("extracted_at", at);
        Files.writeString(target.resolve(".extracted.json"), GSON.toJson(data));
    }

    private String identifySource(Path file) {
        Path rel = baseDir.relativize(file);
        List<String> parts = new ArrayList<>();
        rel.forEach(p -> parts.add(p.toString()));
        int idx = parts.indexOf("sources");
        if (idx != -1 && idx + 1 < parts.size()) return parts.get(idx + 1);
        return parts.isEmpty() ? "unknown" : parts.get(0);
    }

    private String stripTarSuffix(String name) {
        String low = name.toLowerCase();
        for (String suf : TAR_SUFFIXES) if (low.endsWith(suf)) return name.substring(0, name.length() - suf.length());
        return name;
    }

    private Map<String, Object> createReportRow(Path src, Path target, String name, String stem, String source) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rel_path", baseDir.relativize(src).toString());
        row.put("source", source);
        row.put("archive", name);
        row.put("stem", stem);
        row.put("src_abs", src.toAbsolutePath().toString());
        row.put("target_abs", target.toAbsolutePath().toString());
        row.put("status", "pending");
        row.put("members", 0);
        row.put("bytes", 0);
        row.put("started_at", nowIso());
        row.put("finished_at", "");
        row.put("error", "");
        return row;
    }

    private String nowIso() { return ISO_FORMATTER.format(Instant.now().atOffset(ZoneOffset.UTC)); }

    private synchronized void logEvent(String status, String archive, String target, String... extras) {
        String line = String.format("%s\t%s\t%s\t%s\t%s", nowIso(), status, archive, target, String.join("\t", extras));
        eventLogger.println(line);
        eventLogger.flush();
        System.out.println(line);
    }

    private void writeReport() throws IOException {
        String[] h = {"rel_path", "source", "archive", "stem", "src_abs", "target_abs", "status", "members", "bytes", "started_at", "finished_at", "error"};
        try (CSVWriter w = new CSVWriter(new FileWriter(reportPath.toFile()))) {
            w.writeNext(h);
            for (Map<String, Object> r : reportRows) {
                String[] l = new String[h.length];
                for (int i = 0; i < h.length; i++) l[i] = String.valueOf(r.getOrDefault(h[i], ""));
                w.writeNext(l);
            }
        }
    }

    public static void main(String[] args) {
        try {
            boolean dry = false;
            List<String> rem = new ArrayList<>();
            for (String a : args) if (a.equalsIgnoreCase("--dry-run")) dry = true; else rem.add(a);
            String b = rem.size() > 0 ? rem.get(0) : ".";
            String t = rem.size() > 1 ? rem.get(1) : "./temp";
            new TarExtractor(b, t, dry).run();
        } catch (Exception e) { e.printStackTrace(); }
    }
}
