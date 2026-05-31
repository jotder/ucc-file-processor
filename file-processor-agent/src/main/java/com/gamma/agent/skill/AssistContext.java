package com.gamma.agent.skill;

import com.gamma.agent.model.ModelRouter;
import com.gamma.catalog.MetadataGraphService;
import com.gamma.report.ReportService;
import com.gamma.service.StatusStore;

/**
 * The typed handles a skill is allowed to read (v3.3.0), captured once by the agent in
 * {@code init()} and handed to every skill invocation. Read-only by construction: the M1 metadata
 * {@link #catalog}, the {@link #reports} and {@link #statusStore} control-plane reads, the bundled
 * {@link #docs} corpus, and the {@link #models} router. No write-bearing handle is exposed — the
 * agent proposes, it never disposes.
 */
public record AssistContext(MetadataGraphService catalog,
                            ReportService reports,
                            StatusStore statusStore,
                            DocRetriever docs,
                            ModelRouter models) {
}
