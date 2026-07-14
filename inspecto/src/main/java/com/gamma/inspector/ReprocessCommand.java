package com.gamma.inspector;

import com.gamma.etl.BatchManifest;
import com.gamma.etl.ManifestStore;
import com.gamma.etl.PipelineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;

/**
 * Implements {@code ura reprocess <pipeline.toon> <batch_id>}: deletes the
 * batch's output files and markers, restores its member files from backup into
 * the inbox, supersedes the manifest, and triggers a fresh poll.
 *
 * <p>Reprocessing is whole-batch only; the original audit rows remain as history.
 */
public final class ReprocessCommand {

    private static final Logger log = LoggerFactory.getLogger(ReprocessCommand.class);

    private ReprocessCommand() {}

    public static void run(String toonPath, String batchId) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(toonPath);
        if (cfg.dirs().manifestsDir() == null)
            throw new IllegalStateException("No manifests dir configured (set dirs.status_dir).");

        BatchManifest m = ManifestStore.read(cfg.dirs().manifestsDir(), batchId);
        log.info("[REPROCESS] {} — {} member(s), {} output(s)",
                batchId, m.members.size(), m.outputs.size());

        // 1. delete outputs
        for (BatchManifest.OutputEntry o : m.outputs) {
            Files.deleteIfExists(Paths.get(o.outputFile()));
        }
        // 2. delete markers
        for (String marker : m.markers) {
            Files.deleteIfExists(Paths.get(marker));
        }
        // 3. restore members from backup into the inbox (original relative path)
        Path poll = Paths.get(cfg.dirs().poll()).toAbsolutePath();
        for (BatchManifest.MemberEntry me : m.members) {
            if (me.backupPath() == null || me.backupPath().isBlank()) continue;
            Path src = Paths.get(me.backupPath());
            if (!Files.exists(src)) {
                log.warn("[REPROCESS] backup missing, cannot restore {} ({})",
                        me.filename(), src);
                continue;
            }
            Path dst = poll.resolve(me.originalRelPath());
            Files.createDirectories(dst.getParent());
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
        // 4. supersede the manifest
        ManifestStore.supersede(cfg.dirs().manifestsDir(), batchId);

        // 5. re-run a normal poll on the restored set (fresh batch id)
        CollectorProcessor.run(cfg);
        log.info("[REPROCESS] {} complete.", batchId);
    }
}
