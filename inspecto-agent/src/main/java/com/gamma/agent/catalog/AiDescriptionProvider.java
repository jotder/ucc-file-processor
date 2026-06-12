package com.gamma.agent.catalog;

import com.gamma.agentkernel.model.ModelProvider;
import com.gamma.agentkernel.model.ModelRequest;
import com.gamma.agentkernel.model.ModelTier;
import com.gamma.agentkernel.provider.ollama.OllamaModelProvider;
import com.gamma.catalog.Description;
import com.gamma.catalog.Provenance;
import com.gamma.catalog.spi.DescriptionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The AI-backed {@link DescriptionProvider} (v3.3.0, M3) — the seam through which model-authored
 * prose enters the metadata catalog, contributed by the optional {@code file-processor-agent}
 * module via {@code ServiceLoader} with <b>zero change to core</b>. It uses the small/fast model
 * tier to one-line a column or table that has no description yet.
 *
 * <h3>Authority is never usurped</h3>
 * The graph builder consults providers only for nodes whose description is
 * {@link Provenance#NONE}, and merges results with {@link Description#mergePreferring}, so
 * operator-authored {@code MANUAL} prose always wins. As a second guard, this provider also
 * abstains when handed a non-blank {@code existingDescription}.
 *
 * <h3>Abstain-safe & never throws</h3>
 * With the assist layer disabled or no Ollama configured, {@link ModelProvider#available()} is
 * {@code false} and every call returns {@link Description#EMPTY} <em>without any network I/O</em> —
 * so being discovered in a test/CI process is harmless. Any model error is swallowed into an
 * abstention; the provider never throws (the builder would treat a throw as an abstention anyway,
 * but failing quietly keeps the logs clean).
 */
public final class AiDescriptionProvider implements DescriptionProvider {

    private static final Logger log = LoggerFactory.getLogger(AiDescriptionProvider.class);

    private static final String SYSTEM = """
            You write terse, factual one-sentence descriptions for a data catalog. Output a single
            plain sentence (max ~20 words), no markdown, no quotes, no preamble. Describe the
            business meaning; never invent units or values that aren't implied by the name/type.""";

    private final ModelProvider model;

    /** {@code ServiceLoader} entry point: small-tier provider from the environment (abstain-safe). */
    public AiDescriptionProvider() {
        this(OllamaModelProvider.fromEnvironment().providerFor(ModelTier.SMALL));
    }

    /** Test/embedder entry point: inject the model provider (e.g. a deterministic fake). */
    public AiDescriptionProvider(ModelProvider model) {
        this.model = model;
    }

    @Override
    public String name() {
        return "ollama-describer";
    }

    @Override
    public Description describeColumn(ColumnContext ctx) {
        if (ctx == null) return Description.EMPTY;
        if (isPresent(ctx.existingDescription())) return Description.EMPTY;   // never overwrite authored prose
        if (!model.available()) return Description.EMPTY;                     // abstain — no network
        String prompt = "Describe this column.\nPipeline: " + ctx.pipeline()
                + "\nTable: " + ctx.table()
                + "\nColumn: " + ctx.columnName()
                + "\nType: " + ctx.type();
        return generate(prompt, "column " + ctx.columnName());
    }

    @Override
    public Description describeTable(TableContext ctx) {
        if (ctx == null || !model.available()) return Description.EMPTY;
        String cols = (ctx.columnNames() == null) ? "" : String.join(", ", ctx.columnNames());
        String prompt = "Describe this table.\nTable: " + ctx.label() + "\nColumns: " + cols;
        return generate(prompt, "table " + ctx.tableId());
    }

    private Description generate(String prompt, String what) {
        try {
            String text = clean(model.generate(ModelRequest.text(ModelTier.SMALL, SYSTEM, prompt)).text());
            return text.isBlank() ? Description.EMPTY : new Description(text, Provenance.AI);
        } catch (RuntimeException e) {
            log.debug("describer abstained for {}: {}", what, e.toString());
            return Description.EMPTY;
        }
    }

    /** First line, unwrapped of surrounding quotes, length-capped — keeps catalog prose tidy. */
    private static String clean(String raw) {
        if (raw == null) return "";
        String t = raw.strip();
        int nl = t.indexOf('\n');
        if (nl >= 0) t = t.substring(0, nl).strip();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1).strip();
        return t.length() > 240 ? t.substring(0, 240).strip() : t;
    }

    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }
}
