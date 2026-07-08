package com.gamma.query;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates one scalar Measure over a Dataset (BI-5 measure alerts): parses {@code count} /
 * {@code sum(amount)}-style measure text, compiles it with {@link MeasureCompiler} (validated
 * identifiers only), resolves the dataset like {@code /bi/query} does, and runs it in the ephemeral
 * DuckDB sandbox. Returns empty — never throws — when the value cannot be computed (no write root,
 * unknown dataset, SQL failure): an alert sweep must degrade, not disturb ingest.
 *
 * <p>Roots are suppliers because both are wiring-time-unknown (the write root is a {@code -D}
 * property that tests set per-case; the data root is per-space).
 */
public final class DatasetMeasureProbe {

    private static final Logger log = LoggerFactory.getLogger(DatasetMeasureProbe.class);
    private static final Pattern MEASURE = Pattern.compile(
            "(count)|(count|countDistinct|sum|avg|min|max)\\(([A-Za-z_][A-Za-z0-9_]*)\\)");

    private final Supplier<Path> writeRoot;
    private final Supplier<Path> dataRoot;

    public DatasetMeasureProbe(Supplier<Path> writeRoot, Supplier<Path> dataRoot) {
        this.writeRoot = writeRoot;
        this.dataRoot = dataRoot;
    }

    /** Parse-check a measure expression ({@code count} or {@code agg(field)}); used by rule validation. */
    public static boolean validMeasure(String text) {
        return text != null && MEASURE.matcher(text.trim()).matches();
    }

    /** The measure's current value over the dataset, or empty when it cannot be computed (see class doc). */
    public OptionalDouble value(String datasetId, String measureText) {
        try {
            Path root = writeRoot.get();
            if (root == null) {
                log.debug("measure probe: no write root — cannot resolve dataset '{}'", datasetId);
                return OptionalDouble.empty();
            }
            Matcher m = MEASURE.matcher(measureText.trim());
            if (!m.matches()) return OptionalDouble.empty();
            MeasureCompiler.Measure measure = m.group(1) != null
                    ? new MeasureCompiler.Measure("count", null)
                    : new MeasureCompiler.Measure(m.group(2), m.group(3));

            MeasureCompiler.Spec spec = new MeasureCompiler.Spec(
                    datasetId, List.of(measure), List.of(), List.of(), List.of(), 1);
            String sql = MeasureCompiler.compile(spec);

            ComponentStore store = new ComponentStore(root.resolve("registry"));
            Map<String, Object> dataset = store.get("dataset", datasetId)
                    .map(ComponentRegistry.Component::content).orElse(null);
            if (dataset == null) {
                log.warn("measure probe: unknown dataset '{}'", datasetId);
                return OptionalDouble.empty();
            }
            String relationSql = DatasetRelation.relationSql(dataset, dataRoot.get(),
                    new ViewStore(root.resolve("views")));

            QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(
                    datasetId, relationSql, sql, 1, 0, List.of(), List.of()));
            if (r.rows().isEmpty()) return OptionalDouble.empty();
            Object v = r.rows().get(0).get(measure.id());
            return v instanceof Number n ? OptionalDouble.of(n.doubleValue()) : OptionalDouble.empty();
        } catch (Exception e) {
            log.warn("measure probe failed for {} over dataset '{}': {}", measureText, datasetId, e.getMessage());
            return OptionalDouble.empty();
        }
    }
}
