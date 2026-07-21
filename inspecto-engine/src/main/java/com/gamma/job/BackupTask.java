package com.gamma.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.acquire.Checksums;
import com.gamma.pipeline.ComponentStore;
import com.gamma.signal.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The System Maintenance backup task family (Phase 2, {@code docs/superpower/system-maintenance-plan.md}):
 * {@code backup} (MNT-5), {@code backup_verify} (MNT-5) and {@code restore} (MNT-6) on the
 * {@code maintenance} Job Type — mirrors the {@link MaterializeTask} one-class-per-complex-task shape.
 *
 * <p><b>Archive format.</b> A plain zip of the source tree (relative paths, forward slashes) plus a
 * {@code backup-manifest.json} entry, and a <b>sidecar</b> {@code <archive>.manifest.json} next to the
 * zip recording per-file SHA-256 hashes ({@link Checksums}) and the SHA-256 of the finished archive
 * itself — so verification never has to trust the archive it is verifying.
 *
 * <p><b>Catalog</b> (MNT-10): each backup appends one row to the {@code maintenance_backups} Dataset —
 * a single-row Parquet per backup in {@code <dataDir>/maintenance_backups/} (readers glob
 * {@code *.parquet}, so rows union across backups) and an idempotent {@code dataset} component
 * registration, exactly the {@link MaterializeTask} idiom. Skipped (with a Run Log note) when no data
 * root / write root is configured.
 *
 * <p><b>Safe by default.</b> {@code backup} previews on dry run; {@code restore} is fail-closed: the
 * sidecar manifest must exist and the archive hash must match before anything is written, extraction is
 * path-jailed under {@code target_dir} (zip-slip), existing files block unless {@code overwrite: true},
 * and every extracted file is re-hashed against the manifest.
 */
final class BackupTask {

    private static final Logger log = LoggerFactory.getLogger(BackupTask.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS");
    private static final String INNER_MANIFEST = "backup-manifest.json";
    private static final String SIDECAR_SUFFIX = ".manifest.json";
    private static final String CATALOG_DATASET = "maintenance_backups";

    private BackupTask() {}

    // ── backup (MNT-5) ───────────────────────────────────────────────────────────

    static JobResult backup(JobConfig cfg, JobContext ctx, boolean dryRun, String dataDir) throws IOException {
        Path dir = Path.of(cfg.require("dir")).normalize();
        Path backupDir = Path.of(cfg.require("backup_dir")).normalize();
        String prefix = cfg.opt("prefix",
                dir.getFileName() == null ? "backup" : dir.getFileName().toString());
        long t0 = System.nanoTime();
        if (!Files.isDirectory(dir)) {
            return JobResult.ok("backup: source directory not present, nothing to do (" + dir + ")", 0L);
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = new ArrayList<>(walk.filter(Files::isRegularFile)
                    .filter(p -> !p.toAbsolutePath().normalize()
                            .startsWith(backupDir.toAbsolutePath()))   // never archive the archive dir
                    .toList());
        }
        files.sort(Comparator.comparing(Path::toString));   // deterministic archive order
        long totalBytes = 0;
        for (Path p : files) totalBytes += Files.size(p);
        if (dryRun) {
            return JobResult.ok("backup[dry-run]: would archive " + files.size() + " file(s), "
                    + totalBytes + " byte(s) from " + dir + " into " + backupDir,
                    (System.nanoTime() - t0) / 1_000_000L);
        }
        Files.createDirectories(backupDir);
        String stamp = LocalDateTime.now().format(STAMP);
        Path zip = backupDir.resolve(prefix + "_" + stamp + ".zip");
        Path tmp = backupDir.resolve(zip.getFileName() + ".tmp");
        List<Map<String, Object>> entries = new ArrayList<>();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp))) {
            for (Path p : files) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", rel);
                entry.put("bytes", Files.size(p));
                entry.put("sha256", Checksums.of(p, "SHA-256"));
                entries.add(entry);
                zos.putNextEntry(new ZipEntry(rel));
                Files.copy(p, zos);
                zos.closeEntry();
            }
            zos.putNextEntry(new ZipEntry(INNER_MANIFEST));
            zos.write(JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(manifest(dir, stamp, entries, totalBytes, null)));
            zos.closeEntry();
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        try {
            Files.move(tmp, zip, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, zip);   // non-atomic FS fallback
        }
        String zipSha = Checksums.of(zip, "SHA-256");
        Path sidecar = backupDir.resolve(zip.getFileName() + SIDECAR_SUFFIX);
        Files.writeString(sidecar, JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(manifest(dir, stamp, entries, totalBytes, zipSha)));
        long zipBytes = Files.size(zip);
        if (ctx != null) {
            ctx.artifacts().file("backup", zip, zipBytes);
            ctx.artifacts().file("backup_manifest", sidecar, Files.size(sidecar));
            ctx.signals().emit("maintenance.backup.completed", Severity.INFO, Map.of(
                    "archive", zip.toString(), "archiveSha256", zipSha,
                    "fileCount", files.size(), "totalBytes", totalBytes));
        }
        catalogRow(ctx, dataDir, stamp, dir, zip, zipSha, files.size(), totalBytes);
        return JobResult.ok("backup: archived " + files.size() + " file(s), " + totalBytes
                + " byte(s) from " + dir + " → " + zip + " (sha256 " + zipSha.substring(0, 12) + "…)",
                (System.nanoTime() - t0) / 1_000_000L);
    }

    private static Map<String, Object> manifest(Path source, String stamp,
                                                List<Map<String, Object>> entries, long totalBytes, String zipSha) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("created", Instant.now().toString());
        m.put("stamp", stamp);
        m.put("source", source.toString());
        if (zipSha != null) m.put("archiveSha256", zipSha);
        m.put("fileCount", entries.size());
        m.put("totalBytes", totalBytes);
        m.put("files", entries);
        return m;
    }

    // ── backup_verify (MNT-5) ────────────────────────────────────────────────────

    /** Read-only: verify the newest archive in {@code backup_dir} (or one named {@code archive}, or
     *  {@code all: true}) against its sidecar manifest — archive hash first, then every entry hash. */
    static JobResult verify(JobConfig cfg, JobContext ctx) throws IOException {
        Path backupDir = Path.of(cfg.require("backup_dir")).normalize();
        String one = cfg.opt("archive", null);
        boolean all = Boolean.parseBoolean(cfg.opt("all", "false"));
        long t0 = System.nanoTime();
        List<Path> targets = new ArrayList<>();
        if (one != null) {
            targets.add(backupDir.resolve(one));
        } else if (Files.isDirectory(backupDir)) {
            try (Stream<Path> s = Files.list(backupDir)) {
                List<Path> zips = new ArrayList<>(s.filter(p -> p.getFileName().toString().endsWith(".zip")).toList());
                zips.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());   // stamp = newest first
                if (!zips.isEmpty()) targets.addAll(all ? zips : zips.subList(0, 1));
            }
        }
        if (targets.isEmpty()) {
            return JobResult.ok("backup_verify: no archive to verify under " + backupDir, 0L);
        }
        List<String> findings = new ArrayList<>();
        int checkedEntries = 0;
        for (Path zip : targets) {
            checkedEntries += verifyOne(zip, findings);
        }
        if (ctx != null) {
            for (String f : findings) ctx.log().error(f, null);
            if (!findings.isEmpty()) {
                ctx.signals().emit("maintenance.backup.verify_failed", Severity.CRITICAL,
                        Map.of("backupDir", backupDir.toString(), "count", findings.size(), "findings", findings));
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (!findings.isEmpty()) {
            return JobResult.failed("backup_verify: " + findings.size() + " finding(s) across "
                    + targets.size() + " archive(s) — first: " + findings.get(0), ms);
        }
        return JobResult.ok("backup_verify: " + targets.size() + " archive(s) OK, "
                + checkedEntries + " file entr(ies) hash-checked", ms);
    }

    /** Verify one archive; appends human-readable findings, returns how many entries were hash-checked. */
    private static int verifyOne(Path zip, List<String> findings) throws IOException {
        if (!Files.isRegularFile(zip)) {
            findings.add("archive missing: " + zip);
            return 0;
        }
        Path sidecar = zip.resolveSibling(zip.getFileName() + SIDECAR_SUFFIX);
        if (!Files.isRegularFile(sidecar)) {
            findings.add("no sidecar manifest for " + zip.getFileName() + " (expected " + sidecar.getFileName() + ")");
            return 0;
        }
        Map<String, Object> manifest = JSON.readValue(Files.readString(sidecar), Map.class);
        String expectedZipSha = String.valueOf(manifest.get("archiveSha256"));
        String actualZipSha = Checksums.of(zip, "SHA-256");
        if (!expectedZipSha.equals(actualZipSha)) {
            findings.add("archive hash mismatch for " + zip.getFileName()
                    + " (expected " + expectedZipSha + ", got " + actualZipSha + ")");
            return 0;   // the archive itself is untrustworthy — entry checks would be meaningless
        }
        int checked = 0;
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (Object o : (List<?>) manifest.getOrDefault("files", List.of())) {
                Map<?, ?> entry = (Map<?, ?>) o;
                String path = String.valueOf(entry.get("path"));
                ZipEntry ze = zf.getEntry(path);
                if (ze == null) {
                    findings.add(zip.getFileName() + ": manifest entry missing from archive: " + path);
                    continue;
                }
                try (InputStream in = zf.getInputStream(ze)) {
                    String sha = sha256(in);
                    if (!sha.equals(String.valueOf(entry.get("sha256")))) {
                        findings.add(zip.getFileName() + ": entry hash mismatch: " + path);
                    }
                }
                checked++;
            }
        }
        return checked;
    }

    // ── restore (MNT-6) ──────────────────────────────────────────────────────────

    static JobResult restore(JobConfig cfg, JobContext ctx, boolean dryRun) throws IOException {
        Path zip = Path.of(cfg.require("archive")).normalize();
        Path target = Path.of(cfg.require("target_dir")).toAbsolutePath().normalize();
        boolean overwrite = Boolean.parseBoolean(cfg.opt("overwrite", "false"));
        long t0 = System.nanoTime();
        // Fail-closed (MNT-6): restore never bypasses validation — the sidecar manifest must exist and
        // the archive hash must match before a single byte is written.
        List<String> preFindings = new ArrayList<>();
        verifyOne(zip, preFindings);
        if (!preFindings.isEmpty()) {
            return JobResult.failed("restore blocked: archive failed validation — " + preFindings.get(0),
                    (System.nanoTime() - t0) / 1_000_000L);
        }
        Map<String, Object> manifest = JSON.readValue(
                Files.readString(zip.resolveSibling(zip.getFileName() + SIDECAR_SUFFIX)), Map.class);
        List<?> fileEntries = (List<?>) manifest.getOrDefault("files", List.of());
        long totalBytes = ((Number) manifest.getOrDefault("totalBytes", 0)).longValue();
        List<String> conflicts = new ArrayList<>();
        for (Object o : fileEntries) {
            String rel = String.valueOf(((Map<?, ?>) o).get("path"));
            Path dest = target.resolve(rel).normalize();
            if (!dest.startsWith(target)) {   // zip-slip jail
                return JobResult.failed("restore blocked: entry escapes target_dir: " + rel,
                        (System.nanoTime() - t0) / 1_000_000L);
            }
            if (Files.exists(dest)) conflicts.add(rel);
        }
        if (dryRun) {
            if (ctx != null) for (String c : conflicts) ctx.log().warn("restore conflict: " + c + " already exists");
            return JobResult.ok("restore[dry-run]: would restore " + fileEntries.size() + " file(s), "
                    + totalBytes + " byte(s) from " + zip.getFileName() + " into " + target
                    + " (" + conflicts.size() + " conflict(s))", (System.nanoTime() - t0) / 1_000_000L);
        }
        if (!conflicts.isEmpty() && !overwrite) {
            if (ctx != null) for (String c : conflicts) ctx.log().warn("restore conflict: " + c + " already exists");
            return JobResult.failed("restore blocked: " + conflicts.size()
                    + " existing file(s) in " + target + " — set overwrite: true to replace (first: "
                    + conflicts.get(0) + ")", (System.nanoTime() - t0) / 1_000_000L);
        }
        int restored = 0;
        List<String> mismatches = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (Object o : fileEntries) {
                Map<?, ?> entry = (Map<?, ?>) o;
                String rel = String.valueOf(entry.get("path"));
                Path dest = target.resolve(rel).normalize();
                Files.createDirectories(dest.getParent());
                try (InputStream in = zf.getInputStream(zf.getEntry(rel))) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                if (!Checksums.of(dest, "SHA-256").equals(String.valueOf(entry.get("sha256")))) {
                    mismatches.add(rel);
                }
                restored++;
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (!mismatches.isEmpty()) {
            return JobResult.failed("restore: " + restored + " file(s) written but " + mismatches.size()
                    + " failed the post-extraction hash check (first: " + mismatches.get(0) + ")", ms);
        }
        if (ctx != null) {
            ctx.signals().emit("maintenance.restore.completed", Severity.INFO, Map.of(
                    "archive", zip.toString(), "target", target.toString(), "fileCount", restored));
        }
        return JobResult.ok("restore: " + restored + " file(s), " + totalBytes + " byte(s) from "
                + zip.getFileName() + " into " + target
                + (conflicts.isEmpty() ? "" : " (" + conflicts.size() + " overwritten)"), ms);
    }

    // ── catalog (MNT-10) ─────────────────────────────────────────────────────────

    /**
     * Append this backup as one single-row Parquet in {@code <dataDir>/maintenance_backups/} (readers
     * glob {@code *.parquet} — rows union across backups) and idempotently register the
     * {@code maintenance_backups} Dataset component, the {@link MaterializeTask} idiom. Best-effort:
     * a missing data/write root or a Parquet failure is noted in the Run Log, never fails the backup.
     */
    private static void catalogRow(JobContext ctx, String dataDir, String stamp, Path source,
                                   Path zip, String zipSha, int fileCount, long totalBytes) {
        String writeRoot = System.getProperty("assist.write.root");
        if (dataDir == null || dataDir.isBlank() || writeRoot == null || writeRoot.isBlank()) {
            if (ctx != null) ctx.log().info("backup catalog skipped (no data root / write root configured)");
            return;
        }
        try {
            Path storeDir = Path.of(dataDir).resolve(CATALOG_DATASET);
            Files.createDirectories(storeDir);
            Path parquet = storeDir.resolve("backup_" + stamp + "_out.parquet");
            com.gamma.util.DuckDbUtil.loadDriver();
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE catalog_row (created VARCHAR, archive VARCHAR, archive_sha256 VARCHAR,"
                            + " source VARCHAR, file_count INTEGER, total_bytes BIGINT)");
                }
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO catalog_row VALUES (?,?,?,?,?,?)")) {
                    ps.setString(1, Instant.now().toString());
                    ps.setString(2, zip.toString());
                    ps.setString(3, zipSha);
                    ps.setString(4, source.toString());
                    ps.setInt(5, fileCount);
                    ps.setLong(6, totalBytes);
                    ps.executeUpdate();
                }
                try (Statement st = conn.createStatement()) {
                    st.execute("COPY catalog_row TO '"
                            + parquet.toAbsolutePath().toString().replace('\\', '/').replace("'", "''")
                            + "' (FORMAT PARQUET)");
                }
            }
            ComponentStore store = new ComponentStore(Path.of(writeRoot).resolve("registry"));
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("name", CATALOG_DATASET);
            content.put("physicalRef", CATALOG_DATASET);
            content.put("description", "System Maintenance backup catalog (one row per backup, MNT-10)");
            store.write("dataset", CATALOG_DATASET, content, false);   // result-stamp write, no version churn
            if (ctx != null) ctx.artifacts().dataset(CATALOG_DATASET, CATALOG_DATASET, null, 1L, null);
        } catch (Exception e) {
            log.warn("backup catalog append failed (backup itself succeeded): {}", e.getMessage());
            if (ctx != null) ctx.log().warn("backup catalog append failed: " + e.getMessage());
        }
    }

    private static String sha256(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
