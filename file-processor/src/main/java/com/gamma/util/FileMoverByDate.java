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

    /** Shared with {@link IntegratedProcessor} — one definition of the CBS delivery pattern. */
    private static final Pattern DATE_PATTERN = IntegratedProcessor.CBS_ADJ_DATE_PATTERN;
    private final Path sourceDir;
    private final Path baseDir;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Phaser phaser = new Phaser(1);
    private final boolean dryRun;

    /** Scans the current working directory (the historical CLI behaviour). */
    public FileMoverByDate(String baseDirPath, boolean dryRun) {
        this(".", baseDirPath, dryRun);
    }

    public FileMoverByDate(String sourceDirPath, String baseDirPath, boolean dryRun) {
        this.sourceDir = Paths.get(sourceDirPath).toAbsolutePath();
        this.baseDir = Paths.get(baseDirPath).toAbsolutePath();
        this.dryRun = dryRun;
    }

    public void run() throws Exception {
        if (!Files.isDirectory(sourceDir))
            throw new IllegalArgumentException("source directory does not exist: " + sourceDir);
        if (dryRun) System.out.println("!!! DRY-RUN MODE ENABLED - No files will be moved !!!");
        System.out.println("--- Starting Pattern-Based File Mover ---");
        System.out.println("Source Dir: " + sourceDir);
        System.out.println("Base Dir  : " + baseDir);

        if (!dryRun) Files.createDirectories(baseDir);

        VirtualThreadRunner.submit(executor, phaser, () -> FileWalker.walk(
                executor, phaser, sourceDir,
                dir -> !dir.toAbsolutePath().startsWith(baseDir),   // never recurse into the target
                this::processFile,
                (dir, e) -> System.err.println("[ERR] Access Denied: " + dir)));
        System.out.println("Processing... please wait.");
        phaser.arriveAndAwaitAdvance();

        executor.shutdown();
        System.out.println("\nOperation completed (" + (dryRun ? "DRY-RUN" : "LIVE") + ").");
    }

    private void processFile(Path file) {
        String filename = file.getFileName().toString();
        Matcher matcher = DATE_PATTERN.matcher(filename);
        
        if (matcher.matches()) {
            String dateStr = matcher.group(1);
            Path targetDateDir = baseDir.resolve(dateStr);
            
            VirtualThreadRunner.submit(executor, phaser, () -> {
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
        String source = ".";
        boolean dry = false;

        List<String> remaining = new ArrayList<>();
        for (String arg : args) {
            if (arg.equalsIgnoreCase("--dry-run")) dry = true;
            else if (arg.startsWith("--")) {
                System.err.println("Unknown flag: " + arg);
                System.err.println("Usage: FileMoverByDate [--dry-run] [target_base_dir [source_dir]]");
                return;
            } else remaining.add(arg);
        }

        if (!remaining.isEmpty()) base = remaining.get(0);
        if (remaining.size() > 1) source = remaining.get(1);

        try {
            new FileMoverByDate(source, base, dry).run();
        } catch (Exception e) { e.printStackTrace(); }
    }
}
