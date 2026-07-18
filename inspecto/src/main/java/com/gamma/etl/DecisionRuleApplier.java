package com.gamma.etl;

import com.gamma.event.EventLog;
import com.gamma.pipeline.DecisionRules;
import com.gamma.query.ConditionSql;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applies a subject's enabled Decision Rules to its materialized output table before that table is
 * written — the live-execution wiring of the record-level consequences that
 * {@code /decision-rules/{name}/simulate} previews ({@code docs/okf/backend/control-plane/
 * decision-rules.md}). Three subjects hook in:
 * <ul>
 *   <li><b>pipeline batches</b> — the {@code transformed} table, between {@link DataTransformer} and
 *       {@link PartitionWriter} ({@code BatchIngestStrategy.writeAndTrace}), matched by
 *       {@code targetType: pipeline};</li>
 *   <li><b>{@code sql.template} job runs</b> — the materialized template result before its snapshot
 *       {@code COPY}, matched by {@code targetType: job} + the job's name;</li>
 *   <li><b>Stage-2 enrichment recomputes</b> — the {@code __enriched} table before its partitioned
 *       write, matched by {@code targetType: job} + the enrichment's own name (and any wrapping
 *       job's name), so a rule holds across every recompute trigger.</li>
 * </ul>
 * Rules are loaded per unit of work via {@link DecisionRules#forTarget} (priority order) and each
 * rule's {@code when} tree is compiled to one DuckDB predicate ({@link ConditionSql}), so a rule
 * costs set-based SQL, not a JVM row loop.
 *
 * <p>Consequence semantics over the matched rows (evaluated against the table as later-priority rules
 * see it — an earlier rule's removals are invisible to later rules):
 * <ul>
 *   <li><b>tag</b> — appends the destination to a {@code __tags} VARCHAR column (added on first use;
 *       readers align files by name, so older un-tagged output stays readable).</li>
 *   <li><b>route</b> — matched rows are written to the destination in the subject's own output
 *       discipline (its {@link RouteSink}: Hive-partitioned subdir for pipelines/enrichments,
 *       snapshot Parquet Dataset for jobs), and leave the main output.</li>
 *   <li><b>quarantine</b> — matched rows are copied to
 *       {@code <quarantineRoot>/records/<rule>/<baseName>_records.parquet} (record-level analogue of
 *       {@link com.gamma.inspector.QuarantineManager}'s whole-file quarantine) and leave the main
 *       output.</li>
 *   <li><b>drop</b> — matched rows leave the main output.</li>
 * </ul>
 * Within one rule every consequence sees the <em>same</em> matched set: tags run first (so a
 * routed/quarantined copy carries them), then the copies, then one removal deletes the matched rows
 * if any consequence moves/drops them.
 * Platform consequences (emit-signal, start-job, …) stay with the on-demand {@code /apply} route and
 * are inert here; each applied rule emits one {@code decision-rule.applied} signal onto the space's
 * ledger for observability.
 *
 * <p><b>Routing is not a failure:</b> a rule that errors (e.g. its {@code when} references a column
 * the schema no longer maps) is logged and skipped — it never fails the batch/run.
 */
public final class DecisionRuleApplier {

    private static final Logger log = LoggerFactory.getLogger(DecisionRuleApplier.class);

    private DecisionRuleApplier() {
    }

    /** Routed-row output files + lineage produced while applying rules (empty when no rule matched). */
    public record Result(List<PartitionOutput> outputs, List<LineageRow> lineage) {
        public static final Result NONE = new Result(List.of(), List.of());
    }

    /**
     * Whose output the rules are checking: how rules are matched ({@code targetType} + candidate
     * {@code targetNames}, case-insensitive) and how the {@code decision-rule.applied} signal names
     * the subject and its unit of work.
     */
    public record Subject(String targetType, List<String> targetNames,
                          String subjectKey, String subjectName,
                          String runKey, String runId) {

        /** A live pipeline batch, matched by the authored + normalised pipeline name. */
        public static Subject pipeline(PipelineConfig cfg, String batchId) {
            List<String> names = new ArrayList<>();
            if (cfg.identity().name() != null) names.add(cfg.identity().name());
            if (cfg.identity().pipelineName() != null) names.add(cfg.identity().pipelineName());
            return new Subject("pipeline", List.copyOf(names),
                    "pipeline", cfg.identity().pipelineName(), "batch", batchId);
        }

        /** A job run's materialized output, matched by the job's name. */
        public static Subject job(String jobName, String runId) {
            return new Subject("job", List.of(jobName), "job", jobName, "run", runId);
        }

        /**
         * A Stage-2 enrichment recompute — matched as {@code targetType: job} by the enrichment's
         * own name plus any {@code extraTargets} (the wrapping job's name), so one rule holds
         * across every recompute trigger (job / scheduled service / CLI).
         */
        public static Subject enrichment(String name, List<String> extraTargets, String runId) {
            List<String> names = new ArrayList<>();
            names.add(name);
            if (extraTargets != null)
                for (String t : extraTargets) if (t != null && !t.isBlank()) names.add(t);
            return new Subject("job", List.copyOf(names), "enrichment", name, "run", runId);
        }
    }

    /**
     * Materializes one rule's routed rows ({@code routedTable}) to a destination in the subject's
     * own output discipline. The destination is already reduced to a safe path segment.
     */
    @FunctionalInterface
    public interface RouteSink {
        Result write(Connection conn, String routedTable, String destination) throws Exception;
    }

    /**
     * Apply the current space's enabled Decision Rules for {@code cfg}'s pipeline to {@code table}.
     * No rules ⇒ exact no-op. Never throws: per-rule failures are logged and skipped.
     */
    public static Result apply(Connection conn, String table, PipelineConfig cfg,
                               String dbDir, String baseName, List<String> partCols,
                               String batchId, Map<Integer, String> srcIdToFile) {
        return apply(conn, table, Subject.pipeline(cfg, batchId), cfg.dirs().quarantine(), baseName,
                (c, routedTable, dest) -> {
                    List<PartitionOutput> routedOut = PartitionWriter.write(c, routedTable,
                            Paths.get(dbDir, dest).toString(),
                            cfg.output().format(), cfg.output().compression(), baseName, partCols);
                    return new Result(routedOut, LineageCollector.collect(
                            c, routedTable, batchId, srcIdToFile, routedOut, partCols));
                });
    }

    /**
     * Apply the current space's enabled Decision Rules matching {@code subject} to {@code table} —
     * the general form behind the pipeline overload, shared with the {@code sql.template} job and
     * Stage-2 enrichment hooks. No rules ⇒ exact no-op. Never throws: per-rule failures are logged
     * and skipped.
     */
    public static Result apply(Connection conn, String table, Subject subject,
                               String quarantineRoot, String baseName, RouteSink routeSink) {
        List<Map<String, Object>> rules;
        try {
            rules = DecisionRules.forTarget(subject.targetType(),
                    subject.targetNames().toArray(String[]::new));
        } catch (Exception e) {
            log.warn("[DECISION] could not load decision rules for {} '{}': {}",
                    subject.subjectKey(), subject.subjectName(), e.getMessage());
            return Result.NONE;
        }
        if (rules.isEmpty()) return Result.NONE;

        List<PartitionOutput> outputs = new ArrayList<>();
        List<LineageRow> lineage = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            String name = String.valueOf(rule.get("name"));
            try {
                applyRule(conn, table, subject, rule, name, quarantineRoot, baseName, routeSink,
                        outputs, lineage);
            } catch (Exception e) {
                log.warn("[DECISION] rule '{}' failed on {} — skipped: {}",
                        name, subject.runId(), e.getMessage());
            }
        }
        return new Result(List.copyOf(outputs), List.copyOf(lineage));
    }

    @SuppressWarnings("unchecked")
    private static void applyRule(Connection conn, String table, Subject subject,
                                  Map<String, Object> rule, String ruleName,
                                  String quarantineRoot, String baseName, RouteSink routeSink,
                                  List<PartitionOutput> outputs, List<LineageRow> lineage) throws Exception {
        String pred = ConditionSql.predicate(rule.get("when"));
        long matched = count(conn, "SELECT COUNT(*) FROM \"" + table + "\" WHERE " + pred);
        if (matched == 0) return;

        List<Map<String, Object>> consequences = (List<Map<String, Object>>) (List<?>)
                (rule.get("consequences") instanceof List<?> l ? l : List.of());
        List<String> applied = new ArrayList<>();
        boolean remove = false;
        // Tags first, so a route/quarantine copy of the same matched set carries them.
        for (Map<String, Object> c : consequences) {
            if (!"tag".equals(String.valueOf(c.get("action")))) continue;
            String destination = c.get("destination") == null ? "" : String.valueOf(c.get("destination"));
            if (destination.isBlank()) continue;
            tag(conn, table, pred, destination);
            applied.add("tag:" + destination);
        }
        for (Map<String, Object> c : consequences) {
            String action = String.valueOf(c.get("action"));
            String destination = c.get("destination") == null ? "" : String.valueOf(c.get("destination"));
            switch (action) {
                case "route" -> {
                    String dest = fsName(destination);
                    if (dest.isBlank()) {
                        log.warn("[DECISION] rule '{}' route has no destination — skipped", ruleName);
                        continue;
                    }
                    Result routed = route(conn, table, pred, dest, routeSink);
                    outputs.addAll(routed.outputs());
                    lineage.addAll(routed.lineage());
                    applied.add("route:" + dest);
                    remove = true;
                }
                case "quarantine" -> {
                    quarantine(conn, table, pred, quarantineRoot, ruleName, baseName);
                    applied.add("quarantine");
                    remove = true;
                }
                case "drop" -> {
                    applied.add("drop");
                    remove = true;
                }
                default -> {
                    // tag handled above; platform consequences (emit-signal, start-job, …) run via
                    // /decision-rules/{name}/apply
                }
            }
        }
        if (applied.isEmpty()) return;
        if (remove) {
            try (Statement st = conn.createStatement()) {
                st.execute("DELETE FROM \"" + table + "\" WHERE " + pred);
            }
        }
        log.info("[DECISION] [{}] rule '{}' matched {} row(s): {}",
                subject.runId(), ruleName, matched, applied);
        emitApplied(subject, ruleName, matched, applied);
    }

    // ── consequences ─────────────────────────────────────────────────────────────

    private static void tag(Connection conn, String table, String pred, String tagValue) throws Exception {
        String v = "'" + tagValue.replace("'", "''") + "'";
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE \"" + table + "\" ADD COLUMN IF NOT EXISTS __tags VARCHAR");
            st.execute("UPDATE \"" + table + "\" SET __tags = CASE WHEN __tags IS NULL OR __tags = '' THEN " + v
                    + " ELSE __tags || ',' || " + v + " END WHERE " + pred);
        }
    }

    private static Result route(Connection conn, String table, String pred, String dest,
                                RouteSink routeSink) throws Exception {
        String routed = "__dr_routed";
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS \"" + routed + "\"");
            st.execute("CREATE TABLE \"" + routed + "\" AS SELECT * FROM \"" + table + "\" WHERE " + pred);
        }
        try {
            return routeSink.write(conn, routed, dest);
        } finally {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS \"" + routed + "\"");
            }
        }
    }

    private static void quarantine(Connection conn, String table, String pred, String quarantineRoot,
                                   String ruleName, String baseName) throws Exception {
        Path dir = Paths.get(quarantineRoot, "records", fsName(ruleName));
        Files.createDirectories(dir);
        String file = dir.resolve(fsName(baseName) + "_records.parquet").toString().replace('\\', '/');
        try (Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM \"" + table + "\" WHERE " + pred + ") TO '"
                    + file.replace("'", "''") + "' (FORMAT PARQUET)");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** One ledger signal per applied rule, so run-time routing is observable next to the run itself. */
    private static void emitApplied(Subject subject, String ruleName, long matched, List<String> applied) {
        EventLog el = EventLog.current();
        if (el == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rule", ruleName);
        payload.put(subject.subjectKey(), subject.subjectName());
        if (subject.runId() != null) payload.put(subject.runKey(), subject.runId());
        payload.put("matched", matched);
        payload.put("applied", String.join(",", applied));
        el.emit(new Signal(UUID.randomUUID().toString(), "decision-rule.applied", Instant.now(),
                "decision-rule:" + ruleName, null, Severity.INFO, payload).toEvent());
    }

    /** A destination/rule name reduced to a safe path segment (never escapes its directory). */
    private static String fsName(String s) {
        return s == null ? "" : s.trim().replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^\\.+", "_");
    }

    private static long count(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
