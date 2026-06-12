package com.gamma.catalog;

import java.util.List;

/**
 * The live operational state of a {@link MetadataNode}, projected from the audit stores the
 * platform already writes (batch / file / lineage / commit / enrichment-run records).
 *
 * <p>This is the "operational stuff" the metadata graph overlays onto its structural nodes —
 * status, completeness, error, and lineage. It is fetched <em>lazily</em>, only for the nodes a
 * request actually returns, and is never cached: it must reflect what most recently happened.
 *
 * @param latestStatus     status of the most recent terminal batch/run ({@code SUCCESS} /
 *                         {@code FAILED} / {@code NO_DATA} / {@code UNKNOWN})
 * @param latestRunTime    end time of that batch/run (ISO-8601, or empty)
 * @param totalOutputRows  rows written (completeness, output side)
 * @param totalOutputBytes bytes written
 * @param parsedRows       rows successfully parsed (completeness, input side)
 * @param errorRows        rows rejected / quarantined (error side)
 * @param lastError        the most recent error message, or empty
 * @param lineageRefs      output partition paths / files attributable to this node
 * @param dataProduced     whether any data has been committed for this node yet
 */
public record OperationalOverlay(String latestStatus, String latestRunTime,
                                 long totalOutputRows, long totalOutputBytes,
                                 long parsedRows, long errorRows,
                                 String lastError, List<String> lineageRefs,
                                 boolean dataProduced) {

    /** Overlay for a node with no runtime footprint (config/semantic artifacts, or never run). */
    public static final OperationalOverlay NONE =
            new OperationalOverlay("UNKNOWN", "", 0, 0, 0, 0, "", List.of(), false);

    /** Overlay for a configured-but-never-produced node (structurally present, no data yet). */
    public static final OperationalOverlay NO_DATA =
            new OperationalOverlay("NO_DATA", "", 0, 0, 0, 0, "", List.of(), false);

    public OperationalOverlay {
        latestStatus = latestStatus == null ? "UNKNOWN" : latestStatus;
        latestRunTime = latestRunTime == null ? "" : latestRunTime;
        lastError = lastError == null ? "" : lastError;
        lineageRefs = lineageRefs == null ? List.of() : List.copyOf(lineageRefs);
    }
}
