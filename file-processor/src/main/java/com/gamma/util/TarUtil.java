package com.gamma.util;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Shared utilities for tar/gzip archives and CSV file identification.
 *
 * <p>These helpers were previously duplicated across {@link TarExtractor},
 * {@link TarArranger}, {@link TarInboxPreparer}, and {@link IntegratedProcessor}.
 * This class is the single canonical home.
 */
public final class TarUtil {

    private static final List<String> TAR_SUFFIXES = List.of(".tar.gz", ".tgz", ".tar");
    private static final List<String> CSV_SUFFIXES = List.of(".csv.gz", ".csv");

    /** YYYYMMDD date pattern covering years 1900–2099. */
    private static final Pattern DATE_RE = Pattern.compile("((?:19|20)\\d{6})");

    private TarUtil() {}

    // ── file-type predicates ──────────────────────────────────────────────────

    /** Returns {@code true} if {@code p}'s filename ends with a recognised tar suffix. */
    public static boolean isTar(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return TAR_SUFFIXES.stream().anyMatch(n::endsWith);
    }

    /** Returns {@code true} if {@code name} ends with a recognised CSV suffix. */
    public static boolean isCsv(String name) {
        String low = name.toLowerCase();
        return CSV_SUFFIXES.stream().anyMatch(low::endsWith);
    }

    /** Returns {@code true} if {@code p} is a gzipped archive ({@code .tar.gz} or {@code .tgz}). */
    public static boolean isGzipped(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".tar.gz") || n.endsWith(".tgz");
    }

    /**
     * Strip the recognised tar suffix from a filename and return the stem.
     * If no suffix matches, the original name is returned unchanged.
     */
    public static String stripTarSuffix(String name) {
        String low = name.toLowerCase();
        for (String suf : TAR_SUFFIXES)
            if (low.endsWith(suf)) return name.substring(0, name.length() - suf.length());
        return name;
    }

    /**
     * Extract the first YYYYMMDD token (years 1900–2099) from a filename.
     * Returns {@code "obscure"} when no date token is found.
     *
     * <p>Examples:
     * <pre>
     *   "adj_export_20200403_v2.csv.gz" → "20200403"
     *   "backup_snapshot.csv"           → "obscure"
     * </pre>
     */
    public static String extractDate(String filename) {
        Matcher m = DATE_RE.matcher(filename);
        return m.find() ? m.group(1) : "obscure";
    }

    // ── archive operations ────────────────────────────────────────────────────

    /**
     * Extract all members of a tar or tar.gz archive to {@code destDir}.
     *
     * <p>Guards against path-traversal attacks: any entry whose resolved path
     * escapes {@code destDir} causes an {@link IOException} to be thrown.
     *
     * @param archive  source archive ({@code .tar}, {@code .tar.gz}, or {@code .tgz})
     * @param destDir  target directory (must already exist or be created by the caller)
     * @return number of regular files extracted
     * @throws IOException on I/O failure or an unsafe path inside the archive
     */
    public static int extractTar(Path archive, Path destDir) throws IOException {
        int count = 0;
        try (InputStream fi  = Files.newInputStream(archive);
             InputStream bi  = new BufferedInputStream(fi);
             InputStream ci  = isGzipped(archive) ? new GzipCompressorInputStream(bi) : bi;
             TarArchiveInputStream tar = new TarArchiveInputStream(ci)) {

            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(entry)) continue;
                Path out = destDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(destDir))
                    throw new IOException("Unsafe path in archive: " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
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

    /**
     * Count the number of entries in a tar/tar.gz archive without extracting them.
     * Useful for dry-run reporting.
     *
     * @return total entry count (directories and files)
     */
    public static int peekTar(Path archive) throws IOException {
        int count = 0;
        try (InputStream fi  = Files.newInputStream(archive);
             InputStream bi  = new BufferedInputStream(fi);
             InputStream ci  = isGzipped(archive) ? new GzipCompressorInputStream(bi) : bi;
             TarArchiveInputStream tar = new TarArchiveInputStream(ci)) {
            while (tar.getNextEntry() != null) count++;
        }
        return count;
    }

    // ── directory cleanup ─────────────────────────────────────────────────────

    /**
     * Recursively delete {@code dir} and all its contents (bottom-up walk).
     *
     * <p>Failures are logged to {@link System#err} but do not propagate — a locked
     * file on Windows (e.g. an active AV scan) is non-fatal; the directory will be
     * cleaned on the next run.
     */
    public static void deleteTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    System.err.printf("[WARN] Could not delete %s: %s%n", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            System.err.printf("[WARN] deleteTree failed for %s: %s%n", dir, e.getMessage());
        }
    }
}
