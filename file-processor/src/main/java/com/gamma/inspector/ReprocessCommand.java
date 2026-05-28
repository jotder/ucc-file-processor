package com.gamma.inspector;

import com.gamma.etl.BatchManifest;
import com.gamma.etl.ManifestStore;
import com.gamma.etl.PipelineConfig;

import java.nio.file.*;

/**
 * Implements {@code ura reprocess <pipeline.toon> <batch_id>}: deletes the
 * batch's output files and markers, restores its member files from backup into
 * the inbox, supersedes the manifest, and triggers a fresh poll.
 *
 * <p>Reprocessing is whole-batch only; the original audit rows remain as history.
 */
public final class ReprocessCommand {

    private ReprocessCommand() {}

    public static void run(String toonPath, String batchId) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(toonPath);
        if (cfg.manifestsDir == null)
            throw new IllegalStateException("No manifests dir configured (set dirs.status_dir).");

        BatchManifest m = ManifestStore.read(cfg.manifestsDir, batchId);
        System.out.printf("[REPROCESS] %s — %d member(s), %d output(s)%n",
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
        Path poll = Paths.get(cfg.pollDir).toAbsolutePath();
        for (BatchManifest.MemberEntry me : m.members) {
            if (me.backupPath() == null || me.backupPath().isBlank()) continue;
            Path src = Paths.get(me.backupPath());
            if (!Files.exists(src)) {
                System.err.printf("[REPROCESS] WARN: backup missing, cannot restore %s (%s)%n",
                        me.filename(), src);
                continue;
            }
            Path dst = poll.resolve(me.originalRelPath());
            Files.createDirectories(dst.getParent());
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
        // 4. supersede the manifest
        ManifestStore.supersede(cfg.manifestsDir, batchId);

        // 5. re-run a normal poll on the restored set (fresh batch id)
        SourceProcessor.run(cfg);
        System.out.printf("[REPROCESS] %s complete.%n", batchId);
    }
}
