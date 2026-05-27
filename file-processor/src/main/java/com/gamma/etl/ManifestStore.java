package com.gamma.etl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Reads and writes {@link BatchManifest} JSON files under a manifests directory.
 * One file per batch: {@code <manifestsDir>/<batchId>.json}.
 */
public final class ManifestStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ManifestStore() {}

    /** Write {@code manifest} to {@code <manifestsDir>/<batchId>.json}. */
    public static void write(String manifestsDir, BatchManifest manifest) throws IOException {
        Path dir = Paths.get(manifestsDir);
        Files.createDirectories(dir);
        Path file = dir.resolve(manifest.batchId + ".json");
        Files.writeString(file, GSON.toJson(manifest), StandardCharsets.UTF_8);
    }

    /** Read the manifest for {@code batchId}. Throws if missing. */
    public static BatchManifest read(String manifestsDir, String batchId) throws IOException {
        Path file = Paths.get(manifestsDir, batchId + ".json");
        if (!Files.exists(file))
            throw new IOException("Manifest not found for batch " + batchId + ": " + file);
        return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), BatchManifest.class);
    }

    /** Rename {@code <batchId>.json} to {@code <batchId>.json.superseded}. */
    public static void supersede(String manifestsDir, String batchId) throws IOException {
        Path file = Paths.get(manifestsDir, batchId + ".json");
        if (Files.exists(file))
            Files.move(file, file.resolveSibling(batchId + ".json.superseded"),
                    StandardCopyOption.REPLACE_EXISTING);
    }
}
