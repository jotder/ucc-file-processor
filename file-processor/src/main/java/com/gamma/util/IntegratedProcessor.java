package com.gamma.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Integrated Processor for Java 23.
 * Supports Dry-Run mode.
 */
public class IntegratedProcessor {

    private static final Set<String> TAR_SUFFIXES = Set.of(".tar.gz", ".tgz", ".tar");
    private static final Pattern DATE_PATTERN = Pattern.compile("cbs_cdr_adj_(\\d{8})_.*\\.add\\.gz");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    private final Path walkRoot;
    private final Path tempDir;
    private final Path targetBaseDir;
    private final boolean dryRun;
    
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Phaser phaser = new Phaser(1);
    
    private final AtomicInteger archivesProcessed = new AtomicInteger(0);
    private final AtomicInteger filesMoved = new AtomicInteger(0);
    private final AtomicInteger filesSkipped = new AtomicInteger(0);

    public IntegratedProcessor(String walkRoot, String temp, String target, boolean dryRun) throws IOException {
        this.walkRoot = Paths.get(walkRoot).toAbsolutePath();
        this.tempDir = Paths.get(temp).toAbsolutePath();
        this.targetBaseDir = Paths.get(target).toAbsolutePath();
        this.dryRun = dryRun;
        
        if (!dryRun) {
            Files.createDirectories(tempDir);
            Files.createDirectories(targetBaseDir);
        }
    }

    public void run() throws Exception {
        if (dryRun) System.out.println("!!! DRY-RUN MODE ENABLED - No files will be moved or extracted !!!");
        System.out.println("--- Starting Integrated Extract & Move ---");
        submitTask(() -> walkParallel(walkRoot));
        phaser.arriveAndAwaitAdvance();
        executor.shutdown();
        printSummary();
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
            System.err.println("[ERR] Access Denied: " + dir);
        }
    }

    private boolean isWantedTar(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (TAR_SUFFIXES.stream().noneMatch(name::endsWith)) return false;
        for (Path p : walkRoot.relativize(file)) if (p.toString().equals("unknown")) return true;
        return false;
    }

    private void processArchive(Path src) {
        String filename = src.getFileName().toString();
        String stem = stripTarSuffix(filename);
        Path extractTarget = tempDir.resolve(stem);

        try {
            long size = Files.size(src);
            if (isAlreadyDone(extractTarget, size)) {
                System.out.println("[SKIP] Already extracted: " + filename);
                if (!dryRun) scanAndMove(extractTarget);
                return;
            }

            if (dryRun) {
                System.out.println("[DRY-RUN] Would process archive: " + filename);
                peekAndReport(src);
                archivesProcessed.incrementAndGet();
            } else {
                Files.createDirectories(extractTarget);
                System.out.println("[START] Extracting: " + filename);
                int count = extractTar(src, extractTarget);
                archivesProcessed.incrementAndGet();
                writeSentinel(extractTarget, src, size, count);
                scanAndMove(extractTarget);
            }
        } catch (Exception e) {
            System.err.println("[ERR] Error on " + filename + ": " + e.getMessage());
        }
    }

    private void peekAndReport(Path src) throws IOException {
        try (InputStream fi = Files.newInputStream(src);
             InputStream bi = new BufferedInputStream(fi);
             InputStream ci = src.toString().endsWith(".tar") ? bi : new GzipCompressorInputStream(bi);
             TarArchiveInputStream ti = new TarArchiveInputStream(ci)) {
            TarArchiveEntry entry;
            while ((entry = ti.getNextEntry()) != null) {
                String innerName = entry.getName();
                Matcher m = DATE_PATTERN.matcher(innerName);
                if (m.find()) {
                    String date = m.group(1);
                    System.out.println("[DRY-RUN] Would extract and move: " + innerName + " -> " + targetBaseDir.resolve(date));
                    filesMoved.incrementAndGet();
                }
            }
        }
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

    private void scanAndMove(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) scanAndMove(entry);
                else handleExtractedFile(entry);
            }
        } catch (IOException e) { System.err.println("[ERR] Scan error in " + dir); }
    }

    private void handleExtractedFile(Path file) {
        String filename = file.getFileName().toString();
        Matcher matcher = DATE_PATTERN.matcher(filename);
        if (matcher.matches()) {
            String dateStr = matcher.group(1);
            Path finalDir = targetBaseDir.resolve(dateStr);
            Path finalPath = finalDir.resolve(filename);
            try {
                if (Files.exists(finalPath)) {
                    System.out.println("[SKIP] Duplicate in target: " + filename);
                    filesSkipped.incrementAndGet();
                    Files.deleteIfExists(file);
                    return;
                }
                Files.createDirectories(finalDir);
                Files.move(file, finalPath);
                filesMoved.incrementAndGet();
                System.out.println("[MOVE] Extracted " + filename + " -> " + dateStr);
            } catch (IOException e) { System.err.println("[ERR] Move failed: " + filename); }
        }
    }

    private boolean isAlreadyDone(Path target, long currentSize) {
        Path sentinel = target.resolve(".extracted.json");
        if (!Files.exists(sentinel)) return false;
        try {
            Map data = GSON.fromJson(Files.readString(sentinel), Map.class);
            return data != null && ((Double)data.get("src_size")).longValue() == currentSize;
        } catch (Exception e) { return false; }
    }

    private void writeSentinel(Path target, Path src, long size, int count) throws IOException {
        Map<String, Object> data = new HashMap<>();
        data.put("src_size", size);
        data.put("members", count);
        Files.writeString(target.resolve(".extracted.json"), GSON.toJson(data));
    }

    private String stripTarSuffix(String name) {
        String low = name.toLowerCase();
        for (String suf : TAR_SUFFIXES) if (low.endsWith(suf)) return name.substring(0, name.length() - suf.length());
        return name;
    }

    private void printSummary() {
        System.out.println("\n--- Integrated Process Summary (" + (dryRun ? "DRY-RUN" : "LIVE") + ") ---");
        System.out.println("Archives Identified : " + archivesProcessed.get());
        System.out.println("Files to Move       : " + filesMoved.get());
        if (!dryRun) System.out.println("Files Skipped      : " + filesSkipped.get());
        System.out.println("All operations complete.");
    }

    public static void main(String[] args) {
        boolean dry = false;
        List<String> rem = new ArrayList<>();
        for (String a : args) if (a.equalsIgnoreCase("--dry-run")) dry = true; else rem.add(a);
        if (rem.size() < 3) {
            System.err.println("Usage: IntegratedProcessor [--dry-run] <walk_root> <temp_dir> <target_base_dir>");
            return;
        }
        try { new IntegratedProcessor(rem.get(0), rem.get(1), rem.get(2), dry).run(); } catch (Exception e) { e.printStackTrace(); }
    }
}
