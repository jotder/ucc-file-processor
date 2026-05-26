package com.gamma.util;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java 23 Program to move files based on date patterns in their names.
 * Supports Dry-Run mode.
 */
public class FileMoverByDate {

    private static final Pattern DATE_PATTERN = Pattern.compile("cbs_cdr_adj_(\\d{8})_.*\\.add\\.gz");
    private final Path baseDir;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Phaser phaser = new Phaser(1);
    private final boolean dryRun;

    public FileMoverByDate(String baseDirPath, boolean dryRun) {
        this.baseDir = Paths.get(baseDirPath).toAbsolutePath();
        this.dryRun = dryRun;
    }

    public void run() throws Exception {
        if (dryRun) System.out.println("!!! DRY-RUN MODE ENABLED - No files will be moved !!!");
        System.out.println("--- Starting Pattern-Based File Mover ---");
        System.out.println("Source Dir: " + Paths.get(".").toAbsolutePath());
        System.out.println("Base Dir  : " + baseDir);

        if (!dryRun) Files.createDirectories(baseDir);

        submitTask(() -> walkParallel(Paths.get(".")));
        System.out.println("Processing... please wait.");
        phaser.arriveAndAwaitAdvance();
        
        executor.shutdown();
        System.out.println("\nOperation completed (" + (dryRun ? "DRY-RUN" : "LIVE") + ").");
    }

    private void submitTask(Runnable task) {
        phaser.register();
        executor.submit(() -> {
            try { task.run(); } finally { phaser.arriveAndDeregister(); }
        });
    }

    private void walkParallel(Path dir) {
        Path absDir = dir.toAbsolutePath();
        if (absDir.startsWith(baseDir)) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    submitTask(() -> walkParallel(entry));
                } else {
                    processFile(entry);
                }
            }
        } catch (IOException e) {
            System.err.println("[ERR] Access Denied: " + dir);
        }
    }

    private void processFile(Path file) {
        String filename = file.getFileName().toString();
        Matcher matcher = DATE_PATTERN.matcher(filename);
        
        if (matcher.matches()) {
            String dateStr = matcher.group(1);
            Path targetDateDir = baseDir.resolve(dateStr);
            
            submitTask(() -> {
                try {
                    Path targetFile = targetDateDir.resolve(filename);
                    if (Files.exists(targetFile)) {
                        System.out.println("[SKIP] Already exists: " + filename);
                        return;
                    }
                    
                    if (dryRun) {
                        System.out.println("[DRY-RUN] Would move: " + filename + " -> " + targetDateDir);
                    } else {
                        Files.createDirectories(targetDateDir);
                        System.out.println("[MOVE] " + filename + " -> " + targetDateDir);
                        Files.move(file, targetFile);
                    }
                } catch (IOException e) {
                    System.err.println("[FAIL] Move error: " + filename + " -> " + e.getMessage());
                }
            });
        }
    }

    public static void main(String[] args) {
        String base = "../adj_org/";
        boolean dry = false;
        
        List<String> remaining = new ArrayList<>();
        for (String arg : args) {
            if (arg.equalsIgnoreCase("--dry-run")) dry = true;
            else remaining.add(arg);
        }
        
        if (!remaining.isEmpty()) base = remaining.get(0);

        try {
            new FileMoverByDate(base, dry).run();
        } catch (Exception e) { e.printStackTrace(); }
    }
}
