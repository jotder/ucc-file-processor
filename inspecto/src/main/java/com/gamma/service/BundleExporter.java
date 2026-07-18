package com.gamma.service;

import com.gamma.config.io.ConfigCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packages a space's TOON config as a portable zip archive (config + metadata only — no ingested
 * data; that is roadmap). Two granularities, both producing a {@code bundle.toon} manifest at the zip
 * root alongside config-relative file entries:
 * <ul>
 *   <li>{@link #exportDataSource} — one data source's {@link DataSourceBundle} (pipeline + connection +
 *       schemas + jobs).</li>
 *   <li>{@link #exportSpace} — the whole {@code config/} tree plus the space's {@code space.toon}.</li>
 * </ul>
 *
 * <p>Config files are stored under their path <b>relative to {@code config/}</b> (so import unpacks them
 * straight back into a target space's config tree); {@code space.toon} rides at the zip root. A referenced
 * schema/grammar that lives <i>outside</i> the config dir (an absolute path) is stored by its filename and
 * a warning logged — the same limitation the migrator documents for absolute references.
 */
public final class BundleExporter {

    private static final Logger log = LoggerFactory.getLogger(BundleExporter.class);

    /** Bumped if the bundle layout changes incompatibly; import checks it. */
    public static final int SCHEMA_VERSION = 1;
    /** The manifest entry name at the zip root. */
    public static final String MANIFEST = "bundle.toon";
    /** The whole-space manifest entry name for the space's own manifest. */
    public static final String SPACE_TOON = "space.toon";

    private BundleExporter() {}

    /** Zip one data source's bundle: its config files (relative to {@code configDir}) + a {@code bundle.toon}. */
    public static byte[] exportDataSource(DataSourceBundle bundle, Path configDir, String spaceId) throws IOException {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        List<Map<String, Object>> artifacts = new ArrayList<>();
        for (Path f : bundle.files()) {
            String entry = entryName(configDir, f);
            entries.put(entry, Files.readAllBytes(f));
            artifacts.add(artifact(entry, kindOf(f)));
        }
        Map<String, Object> manifest = manifest("datasource", spaceId, artifacts);
        manifest.put("data_source", bundle.id());
        return zip(entries, manifest);
    }

    /** Zip a whole space: every file under {@code configDir} (relative) + {@code space.toon} + a {@code bundle.toon}. */
    public static byte[] exportSpace(Path configDir, Path spaceToon, String spaceId) throws IOException {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        List<Map<String, Object>> artifacts = new ArrayList<>();
        if (Files.isDirectory(configDir)) {
            try (Stream<Path> s = Files.walk(configDir)) {
                for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
                    String entry = configDir.relativize(f).toString().replace('\\', '/');
                    entries.put(entry, Files.readAllBytes(f));
                    artifacts.add(artifact(entry, kindOf(f)));
                }
            }
        }
        if (spaceToon != null && Files.isRegularFile(spaceToon)) {
            entries.put(SPACE_TOON, Files.readAllBytes(spaceToon));
            artifacts.add(artifact(SPACE_TOON, "space"));
        }
        return zip(entries, manifest("space", spaceId, artifacts));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> manifest(String kind, String spaceId, List<Map<String, Object>> artifacts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("schema_version", SCHEMA_VERSION);
        m.put("source_space", spaceId);
        m.put("created_at", Instant.now().toString());
        m.put("artifacts", artifacts);
        return m;
    }

    private static Map<String, Object> artifact(String path, String kind) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("path", path);
        a.put("kind", kind);
        return a;
    }

    /** A config file's zip entry: its path relative to {@code configDir}, or its bare filename if it lives outside. */
    private static String entryName(Path configDir, Path file) {
        Path abs = file.toAbsolutePath().normalize();
        Path root = configDir.toAbsolutePath().normalize();
        if (abs.startsWith(root)) return root.relativize(abs).toString().replace('\\', '/');
        log.warn("bundle file {} is outside the space config dir {} — storing by filename", file, configDir);
        return file.getFileName().toString();
    }

    /** Classify a config file by its filename suffix (informational, for the manifest artifact list). */
    private static String kindOf(Path file) {
        String n = file.getFileName().toString();
        if (n.endsWith("_pipeline.toon"))    return "pipeline";
        if (n.endsWith("_connection.toon"))  return "connection";
        if (n.endsWith("_job.toon"))         return "job";
        if (n.endsWith("_meta.toon"))        return "meta";
        if (n.endsWith("_enrich.toon"))      return "enrich";
        if (n.endsWith("_rca.toon"))         return "rca";
        if (n.endsWith(".grammar.toon"))     return "grammar";
        if (n.endsWith("_schema.toon"))      return "schema";
        return "other";
    }

    private static byte[] zip(Map<String, byte[]> entries, Map<String, Object> manifest) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            put(zos, MANIFEST, ConfigCodec.toToon(manifest).getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> e : entries.entrySet()) put(zos, e.getKey(), e.getValue());
        }
        return bos.toByteArray();
    }

    private static void put(ZipOutputStream zos, String name, byte[] bytes) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(bytes);
        zos.closeEntry();
    }
}
