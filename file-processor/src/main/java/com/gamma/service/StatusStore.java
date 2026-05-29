package com.gamma.service;

import com.gamma.etl.PipelineConfig;

import java.util.Set;

/**
 * Abstraction over the service's view of run state. Introduced here (M1) with a
 * file-backed implementation reading the existing audit artifacts; M5 swaps in a
 * database-backed implementation behind this same seam, so the API/observability
 * layers built on it don't change.
 *
 * <p>M1 needs only the recovery query (which batches already committed). The read
 * surface (list runs / batches / files / lineage / quarantine) grows here as the
 * Control API (M3) requires it.
 */
public interface StatusStore {

    /**
     * The set of batch ids previously committed for {@code cfg}'s pipeline — used at
     * service startup to report what already finished. Empty when status is disabled
     * or nothing has run yet.
     */
    Set<String> committedBatches(PipelineConfig cfg);
}
