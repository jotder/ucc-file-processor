package com.gamma.agent.diagnose;

import com.gamma.agent.kernel.model.ModelProvider;
import com.gamma.agent.kernel.model.ModelRequest;
import com.gamma.agent.kernel.model.ModelRouter;
import com.gamma.agent.kernel.model.ModelTier;
import com.gamma.assist.AssistResult.Citation;
import com.gamma.assist.Diagnosis;
import com.gamma.catalog.MetadataGraphService;
import com.gamma.catalog.MetadataNode;
import com.gamma.catalog.NodeKind;
import com.gamma.etl.BatchEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * The {@link FailureReactor.Diagnoser} the agent wires for event-driven diagnosis (v3.7.0, M7). It
 * always computes the deterministic {@link HeuristicDiagnoser} baseline, then — only when a model is
 * available — enriches the root-cause <em>prose</em> with a language model. The
 * {@link Diagnosis.Severity} stays the heuristic's (deterministic); the model only writes a clearer
 * explanation grounded in the batch's error detail.
 *
 * <h3>Abstain-safe & robust</h3>
 * With no model (CPU-only / air-gapped / assist disabled) this returns the heuristic diagnosis
 * unchanged — zero model I/O, {@link Diagnosis#heuristicOnly()} stays {@code true}. A model error
 * never loses the diagnosis: it falls back to the heuristic. Citations are <em>derived</em> from the
 * catalog (the pipeline's SOURCE node id), so the model can't fabricate a reference. Root-cause is
 * <em>local-best-effort, hosted-recommended</em> — the largest local→hosted quality gap (per the MVP
 * viability review), which is why the deterministic severity is the load-bearing signal.
 */
public final class ModelDiagnoser implements FailureReactor.Diagnoser {

    /** Root-cause is the reasoning-heavier half (per V-5): route it to the MEDIUM (7B) tier. */
    static final ModelTier TIER = ModelTier.MEDIUM;

    private static final String SYSTEM = """
            You are a data-pipeline failure analyst for Inspecto. Given a failed batch's
            details and a heuristic first guess, write ONE short paragraph (2-3 sentences, plain text,
            no markdown) explaining the most likely root cause and the next step an operator should
            take. Be concrete and use only the facts provided — do not invent file names, table names,
            or error codes that are not given.""";

    private final ModelRouter models;
    private final MetadataGraphService catalog;
    private final LongSupplier clock;

    public ModelDiagnoser(ModelRouter models, MetadataGraphService catalog) {
        this(models, catalog, System::currentTimeMillis);
    }

    ModelDiagnoser(ModelRouter models, MetadataGraphService catalog, LongSupplier clock) {
        this.models = models;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Override
    public Diagnosis diagnose(BatchEvent e) {
        long now = clock.getAsLong();
        List<Citation> citations = groundPipeline(e.pipeline());
        Diagnosis heuristic = HeuristicDiagnoser.diagnose(e, now, citations);

        ModelProvider model = (models == null) ? null : models.providerFor(TIER);
        if (model == null || !model.available()) return heuristic;   // abstain — deterministic only

        try {
            String prose = model.generate(ModelRequest.text(TIER, SYSTEM, prompt(e, heuristic))).text().trim();
            if (prose.isBlank()) return heuristic;
            // Model enriches the prose; severity stays the deterministic heuristic's.
            return new Diagnosis(e.batchId(), e.pipeline(), heuristic.severity(), prose,
                    null, /* heuristicOnly */ false, now, citations);
        } catch (RuntimeException modelDown) {
            return heuristic;   // never let a model failure lose the diagnosis
        }
    }

    private String prompt(BatchEvent e, Diagnosis heuristic) {
        return "FAILED BATCH\n"
                + "  pipeline:       " + e.pipeline() + "\n"
                + "  batch id:       " + e.batchId() + "\n"
                + "  error:          " + (e.error() == null ? "(none recorded)" : e.error()) + "\n"
                + "  offending file: " + (e.offendingFile() == null ? "(unknown)" : e.offendingFile()) + "\n"
                + "  rows failed:    " + e.errorRows() + "\n"
                + "  rows written:   " + e.outputRows() + "\n"
                + "  rejected files: " + e.rejectedCount() + "\n\n"
                + "HEURISTIC FIRST GUESS: " + heuristic.rootCause();
    }

    /** Derive a citation to the pipeline's catalog SOURCE node, so the reference can't be fabricated. */
    private List<Citation> groundPipeline(String pipeline) {
        List<Citation> citations = new ArrayList<>();
        if (catalog == null || pipeline == null) return citations;
        for (MetadataNode s : catalog.nodesOfKind(NodeKind.STREAM)) {
            if (pipeline.equals(s.label())) {
                citations.add(new Citation("catalog", s.id()));
                break;
            }
        }
        return citations;
    }
}
