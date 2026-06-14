package com.gamma.acquire;

import com.gamma.acquire.SourceConnector.Capability;

import java.nio.file.Path;
import java.util.EnumSet;

/**
 * Decides, per file, the cheapest legal way to retrieve it — minimising byte movement.
 *
 * <p>The policy (Data Acquisition roadmap §6 packaging note; the engineering rule the user asked for):
 * <ol>
 *   <li><b>A backup/archive is configured</b> → {@link RetrievalPlan.Mode#FETCH_TO_BACKUP}: write the bytes
 *       <em>once</em>, directly into the backup destination, and read from there. Never download to a temp
 *       dir, read, and then move — that copies every byte twice.</li>
 *   <li><b>No backup, and the connector can stream</b> (and nothing downstream needs a seekable local copy)
 *       → {@link RetrievalPlan.Mode#STREAM}: read straight from the source, zero local bytes. This is the
 *       "don't download-then-read" case.</li>
 *   <li><b>Otherwise</b> (a local copy is required but there is nothing to keep) →
 *       {@link RetrievalPlan.Mode#STAGE_TEMP}: stage in temp and clean up after processing.</li>
 * </ol>
 *
 * <p>Pure function — no I/O, fully unit-testable. <b>Phase A status:</b> this codifies the policy now (so the
 * remote connectors of Phase E implement retrieval correctly from day one); the local run path still reads
 * files in place, so the planner is not yet wired into {@code SourceProcessor}. Wiring lands with the first
 * connector that actually fetches over a network.
 */
public final class RetrievalPlanner {

    private RetrievalPlanner() {}

    /**
     * @param caps           the source connector's capabilities
     * @param backupConfigured whether a backup/archive destination is configured for this pipeline
     * @param backupDest     where the file should ultimately land when {@code backupConfigured} (its
     *                       final relative-path location under the backup root); may be {@code null} otherwise
     * @param tempDir        the staging directory to use when a throwaway local copy is unavoidable
     * @param needsLocalCopy whether downstream requires a seekable local file (e.g. two-pass checksum,
     *                       a format needing random access) — forces materialisation even with no backup
     */
    public static RetrievalPlan plan(EnumSet<Capability> caps,
                                     boolean backupConfigured,
                                     Path backupDest,
                                     Path tempDir,
                                     boolean needsLocalCopy) {
        // 1. Keep a copy anyway? Then write it exactly once, in its final home.
        if (backupConfigured && backupDest != null) {
            return RetrievalPlan.fetchToBackup(backupDest);
        }
        // 2. Nothing to keep and we can read straight from the source → no local bytes at all.
        boolean canStream = caps != null && caps.contains(Capability.STREAM);
        if (canStream && !needsLocalCopy) {
            return RetrievalPlan.stream();
        }
        // 3. Must land locally but there's nothing to retain → throwaway temp copy.
        return RetrievalPlan.stageTemp(tempDir);
    }
}
