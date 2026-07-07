package com.gamma.service;

import com.gamma.util.AtomicFiles;
import com.gamma.util.ToonHelper;
import dev.toonformat.jtoon.JToon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One space's live runtime: its {@link SourceService}, the {@link SpaceRoot} that decides where its state lives,
 * and the display metadata from its {@code space.toon} manifest. A single Inspecto server hosts many of these
 * concurrently (see {@code SpaceManager}), each fully isolated.
 *
 * <p>{@link AutoCloseable}: {@link #close()} delegates to the existing drain-first {@link SourceService#close()}.
 * Built (un-started) by {@link SpaceBootstrap#load(SpaceRoot)}; {@code SpaceManager} calls {@link #start()}.
 */
public final class SpaceContext implements AutoCloseable {

    private final SpaceId id;
    private final SpaceRoot root;
    private volatile SpaceManifest manifest;
    private final SourceService service;

    SpaceContext(SpaceId id, SpaceRoot root, SpaceManifest manifest, SourceService service) {
        this.id = id;
        this.root = root;
        this.manifest = manifest;
        this.service = service;
    }

    public SpaceId id() { return id; }

    public SpaceRoot root() { return root; }

    public SpaceManifest manifest() { return manifest; }

    /** Swap the display metadata after a rename ({@link SpaceManager#update}); id/root/service are unchanged. */
    void updateManifest(SpaceManifest updated) { this.manifest = updated; }

    /** This space's engine + control-plane facade (the existing per-instance {@link SourceService}). */
    public SourceService service() { return service; }

    /** Arm this space's poll loop / schedules (delegates to {@link SourceService#start()}). */
    public void start() { service.start(); }

    @Override
    public void close() { service.close(); }

    /**
     * The {@code space.toon} display metadata. The manifest is optional — a space is discovered by its
     * {@code config/} subtree, so a missing or unreadable manifest defaults the display name to the id.
     */
    public record SpaceManifest(String displayName, String description, String createdAt) {

        private static final Logger log = LoggerFactory.getLogger(SpaceContext.class);

        /** Write this manifest to {@code space.toon} at {@code path} (canonical TOON, crash-safe). */
        void write(Path path) throws IOException {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("display_name", displayName);
            m.put("description", description);
            m.put("created_at", createdAt);
            AtomicFiles.write(path, JToon.encode(m).getBytes(StandardCharsets.UTF_8), ".space-");
        }

        /** Read {@code space.toon} at {@code path}, defaulting the display name to {@code fallbackId} when absent. */
        static SpaceManifest read(Path path, String fallbackId) {
            String displayName = fallbackId, description = "", createdAt = "";
            if (Files.exists(path)) {
                try {
                    Map<String, Object> m = ToonHelper.load(path.toString());
                    displayName = ToonHelper.opt(m, "display_name", fallbackId);
                    description = ToonHelper.opt(m, "description", "");
                    createdAt   = ToonHelper.opt(m, "created_at", "");
                } catch (Exception e) {
                    log.warn("Could not read space manifest {} — defaulting: {}", path, e.getMessage());
                }
            }
            return new SpaceManifest(displayName, description, createdAt);
        }
    }
}
