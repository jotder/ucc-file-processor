package com.gamma.service;

import com.gamma.config.io.ConfigCodec;
import com.gamma.util.AtomicFiles;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

/**
 * Reads a {@link BundleExporter}-produced bundle zip and unpacks its config files into a space's
 * {@code config/} tree. Pure plumbing — parsing, the zip-slip jail, and the atomic writes; deciding
 * conflicts and making the configs live (register / rebuild) is the caller's job (the HTTP route, which
 * has the {@link CollectorService}).
 *
 * <p>Config entries are written under their bundle path (relative to {@code config/}); the
 * {@code bundle.toon} manifest and any {@code space.toon} are split out and never land in {@code config/}.
 */
public final class BundleImporter {

    /**
     * A parsed bundle: its manifest {@code kind} ({@code datasource} | {@code space}), the full manifest map,
     * the config-file entries (keyed by config-relative path), and the optional {@code space.toon} bytes.
     */
    public record Bundle(String kind, Map<String, Object> manifest,
                         LinkedHashMap<String, byte[]> configEntries, byte[] spaceToon) {}

    private BundleImporter() {}

    /** Parse a bundle zip, validating its {@code bundle.toon} manifest. */
    public static Bundle parse(byte[] zip) throws IOException {
        LinkedHashMap<String, byte[]> all = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var e = zis.getNextEntry(); e != null; e = zis.getNextEntry())
                if (!e.isDirectory()) all.put(e.getName(), zis.readAllBytes());
        }
        byte[] mf = all.remove(BundleExporter.MANIFEST);
        if (mf == null) throw new IllegalArgumentException("not a bundle: missing " + BundleExporter.MANIFEST);
        Map<String, Object> manifest;
        try {
            manifest = ConfigCodec.toMap(new String(mf, StandardCharsets.UTF_8));
        } catch (RuntimeException bad) {
            throw new IllegalArgumentException("invalid " + BundleExporter.MANIFEST + ": " + bad.getMessage(), bad);
        }
        byte[] spaceToon = all.remove(BundleExporter.SPACE_TOON);
        return new Bundle(String.valueOf(manifest.getOrDefault("kind", "")), manifest, all, spaceToon);
    }

    /** The pipeline ids declared in the bundle (lowercased in-file {@code name}) — for conflict detection. */
    public static List<String> pipelineIds(Bundle bundle) {
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : bundle.configEntries().entrySet()) {
            if (!e.getKey().endsWith("_pipeline.toon")) continue;
            Object name = ConfigCodec.toMap(new String(e.getValue(), StandardCharsets.UTF_8)).get("name");
            if (name != null && !name.toString().isBlank()) ids.add(name.toString().toLowerCase());
        }
        return ids;
    }

    /**
     * Write the bundle's config entries under {@code configDir}, jailed against zip-slip (each resolved
     * target must stay within {@code configDir}). Returns the config-relative paths written.
     */
    public static List<String> writeConfig(Bundle bundle, Path configDir) throws IOException {
        Path root = configDir.toAbsolutePath().normalize();
        List<String> written = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : bundle.configEntries().entrySet()) {
            Path target = root.resolve(e.getKey()).normalize();
            if (!target.startsWith(root))
                throw new IllegalArgumentException("bundle entry escapes the config dir: " + e.getKey());
            AtomicFiles.write(target, e.getValue(), ".import-");
            written.add(root.relativize(target).toString().replace('\\', '/'));
        }
        return written;
    }
}
