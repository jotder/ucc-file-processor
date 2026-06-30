package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * <b>T32 Phase C — durable high-watermark for an incremental flow job.</b> Keyed by {@code (flow, store)},
 * it remembers the largest value of the job's {@code incremental_column} processed so far, so the next run
 * reads only rows past it (append) rather than recomputing the whole {@code source_store}. One small file per
 * key under the jobs audit dir ({@code <flow>__<store>.watermark}); the value is the column's {@code max()}
 * rendered as text and re-applied as a SQL literal. Absent file ⇒ first run ⇒ read everything.
 *
 * <p>Persisted <em>after</em> the branch commit (same stranding-safety rationale as the acquisition ledger):
 * a crash before the watermark advances re-reads the just-written increment next run, which the sink's
 * {@code OVERWRITE_OR_IGNORE} write makes idempotent.
 */
@PublicApi(since = "4.3.0")
public final class PipelineWatermarkStore {

    private static final Logger log = LoggerFactory.getLogger(PipelineWatermarkStore.class);

    private final Path dir;

    public PipelineWatermarkStore(Path dir) {
        this.dir = dir.normalize();
    }

    /** The last watermark for {@code (flow, store)}, if a prior run recorded one. */
    public Optional<String> get(String flow, String store) {
        Path f = fileFor(flow, store);
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            String v = Files.readString(f, StandardCharsets.UTF_8).strip();
            return v.isEmpty() ? Optional.empty() : Optional.of(v);
        } catch (IOException e) {
            log.warn("Could not read flow watermark {}: {}", f, e.getMessage());
            return Optional.empty();
        }
    }

    /** Record the new watermark for {@code (flow, store)} (last write wins). */
    public void put(String flow, String store, String watermark) throws IOException {
        Path f = fileFor(flow, store);
        Files.createDirectories(f.getParent());
        Files.writeString(f, watermark, StandardCharsets.UTF_8);
    }

    private Path fileFor(String flow, String store) {
        return dir.resolve(safe(flow) + "__" + safe(store) + ".watermark");
    }

    private static String safe(String s) {
        return s == null ? "_" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
