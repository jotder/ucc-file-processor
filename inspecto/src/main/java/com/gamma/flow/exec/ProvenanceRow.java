package com.gamma.flow.exec;

import com.gamma.api.PublicApi;

/**
 * <b>T21 — one cell of the data-plane provenance matrix.</b> A {@link FlowExecutor.ProvenanceCollector}
 * reports one of these per <em>(node, outgoing relationship)</em> as a flow run walks the graph (§11.1):
 * "node {@code nodeId} emitted {@code rowCount} records on relationship {@code rel} during run {@code batchId}
 * of flow {@code flowId}." The structure plane (the {@link com.gamma.flow.FlowGraph} edges) supplies the
 * topology; these rows are the quantities painted onto it. Keyed by {@code (flowId, batchId)} — the same
 * {@code batchId} the run publishes as its {@code BatchEvent} correlation id.
 *
 * @param flowId   the authored flow id
 * @param batchId  the run identity (the correlation key shared with the event store)
 * @param nodeId   the node that emitted the records
 * @param rel      the outgoing relationship (data / dropped / invalid / duplicate / route:&lt;key&gt;)
 * @param rowCount the number of records emitted on {@code rel}
 * @param runTs    ISO-8601 timestamp of the run
 */
@PublicApi(since = "4.3.0")
public record ProvenanceRow(String flowId, String batchId, String nodeId, String rel,
                            long rowCount, String runTs) {}
