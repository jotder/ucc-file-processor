package com.gamma.intelligence.pack;

import com.eoiagent.core.Capability;
import com.eoiagent.core.Role;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.tool.Tool;
import com.gamma.intelligence.action.ComponentActions;
import com.gamma.intelligence.action.ControlPlaneClient;
import com.gamma.intelligence.action.OperationalActions;
import com.gamma.intelligence.action.RunbookActions;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.spec.ConfigSpec;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.etl.PipelineConfig;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.PipelineCodec;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.exec.PipelineDryRun;
import com.gamma.service.CollectorService;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import com.gamma.signal.Signals;
import com.gamma.sql.SqlGuard;
import com.gamma.util.BrowsableStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The read tool belt v1 (plan §3, L0 "read/ground"): {@code glossary_lookup} and {@code docs_search}
 * ground answers in {@code docs/} (the canonical vocabulary + product docs); {@code status_get}
 * grounds them in the live {@link CollectorService}. All read-only, evidence-producing, never throw —
 * an expected failure (unknown term, no matches) is an {@code ok=false} {@link ToolResult}.
 *
 * <p>The AGT-5 P1 investigation tier (plan §3 "Analyze") adds four analysis tools on the same
 * pattern: {@code timeline_build} (signals + job runs + component saves, one ordered window),
 * {@code diff_batches} (two batch-ledger entries compared), {@code config_versions_diff}
 * (ComponentStore {@code .history} structural diff) and {@code anomaly_scan} (deterministic
 * z-score/threshold SQL math over a browsable store — the model judges, tools compute).
 */
final class InspectoTools {

    private static final Path DOCS_ROOT = RepoPaths.root().map(r -> r.resolve("docs")).orElse(Path.of("docs"));
    private static final int DOCS_SEARCH_MAX_HITS = 10;

    private InspectoTools() {
    }

    static List<Tool> tools(CollectorService service) {
        return tools(service, defaultComponents(), service::browsableStores);
    }

    /**
     * The full belt with the P1 analysis seams explicit — the component registry ({@code null} when no
     * {@code -Dassist.write.root} is configured; the registry-backed tools degrade honestly) and the
     * DB-backed browse stores. Package-private so tests can substitute seeded stores, mirroring the
     * agent's package-private test constructor.
     */
    static List<Tool> tools(CollectorService service, ComponentStore components,
                            Supplier<List<BrowsableStore>> browseStores) {
        // P3 (L2) act tools are mutating=true. They are always registered but stay hidden and fail
        // closed unless the MUTATING_ACTIONS feature is on (opt-in AgentApprovals.enabled()); the eoiagent
        // ToolRegistry routes each through the approval gate (dry-run → human approve → audited mutation).
        ControlPlaneClient controlPlane = new ControlPlaneClient();
        return List.of(glossaryLookup(), docsSearch(), statusGet(service),
                signalsQuery(service), signalTimeline(service),
                timelineBuild(service, components), diffBatches(service),
                configVersionsDiff(components), anomalyScan(browseStores),
                componentDraft(), pipelineAuthor(), suggestExpectations(browseStores),
                componentApply(controlPlane), componentRollback(controlPlane),
                jobRun(controlPlane), pipelineRerun(controlPlane),
                alertAck(controlPlane), scheduleApply(controlPlane),
                runbookOperator(controlPlane));
    }

    /** The component registry the control routes read — {@code -Dassist.write.root/registry} — or
     *  {@code null} when no write root is configured (same resolution as the maintenance jobs). */
    private static ComponentStore defaultComponents() {
        String wr = System.getProperty("assist.write.root");
        return wr == null || wr.isBlank() ? null : new ComponentStore(Path.of(wr).resolve("registry"));
    }

    private static Tool glossaryLookup() {
        Map<String, String> glossary = GlossaryLoader.load();
        ToolSpec spec = new ToolSpec("glossary_lookup",
                "Look up a canonical Inspecto term's definition (docs/GLOSSARY.md)",
                "{\"type\":\"object\",\"properties\":{\"term\":{\"type\":\"string\"}},\"required\":[\"term\"]}",
                false, Role.USER, Capability.READ_DOCS);
        return new FunctionTool(spec, call -> {
            String term = arg(call, "term");
            if (term == null || term.isBlank()) return error("term is required");
            String needle = term.trim().toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> e : glossary.entrySet()) {
                if (e.getKey().toLowerCase(Locale.ROOT).equals(needle)) {
                    return ok(Map.of("term", e.getKey(), "definition", e.getValue()));
                }
            }
            return error("no canonical definition for '" + term + "'");
        });
    }

    private static Tool docsSearch() {
        ToolSpec spec = new ToolSpec("docs_search",
                "Search the docs/ corpus for lines matching a query, with file + line citations",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}",
                false, Role.USER, Capability.READ_DOCS);
        return new FunctionTool(spec, call -> {
            String query = arg(call, "query");
            if (query == null || query.isBlank()) return error("query is required");
            List<Map<String, Object>> hits = search(query.trim());
            if (hits.isEmpty()) return error("no docs/ matches for '" + query + "'");
            return ok(Map.of("query", query, "hits", hits));
        });
    }

    private static Tool statusGet(CollectorService service) {
        ToolSpec spec = new ToolSpec("status_get",
                "Live pipeline status: paused state and committed-batch count, one or all pipelines",
                "{\"type\":\"object\",\"properties\":{\"pipelineId\":{\"type\":\"string\"}}}",
                false, Role.USER, Capability.READ_METADATA);
        return new FunctionTool(spec, call -> {
            String pipelineId = arg(call, "pipelineId");
            List<CollectorService.PipelineView> views = service.pipelines();
            if (pipelineId == null || pipelineId.isBlank()) {
                return ok(Map.of("pipelines", views.stream()
                        .map(v -> Map.of("name", v.name(), "paused", v.paused(),
                                "committedBatches", v.committedBatches()))
                        .toList()));
            }
            return views.stream()
                    .filter(v -> v.name().equalsIgnoreCase(pipelineId))
                    .findFirst()
                    .<ToolResult>map(v -> ok(Map.of("name", v.name(), "paused", v.paused(),
                            "committedBatches", v.committedBatches())))
                    .orElseGet(() -> error("unknown pipeline: '" + pipelineId + "'"));
        });
    }

    private static final int SIGNALS_DEFAULT_LIMIT = 50;
    private static final int SIGNALS_MAX_LIMIT = 200;
    private static final int SIGNALS_DEFAULT_SINCE_MINUTES = 60;
    private static final int TIMELINE_DEFAULT_SINCE_MINUTES = 24 * 60;
    private static final int TIMELINE_FETCH_LIMIT = 500;

    private static Tool signalsQuery(CollectorService service) {
        ToolSpec spec = new ToolSpec("signals_query",
                "Search the operational signal ledger: recent signals filtered by dotted type "
                        + "(exact or prefix.* glob), time window, severity floor and correlationId",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"type\":{\"type\":\"string\"},"
                        + "\"sinceMinutes\":{\"type\":\"integer\"},"
                        + "\"minSeverity\":{\"type\":\"string\"},"
                        + "\"correlationId\":{\"type\":\"string\"},"
                        + "\"limit\":{\"type\":\"integer\"}}}",
                false, Role.USER, Capability.READ_METADATA);
        return new FunctionTool(spec, call -> {
            Integer sinceMinutes = intArg(call, "sinceMinutes");
            Integer limit = intArg(call, "limit");
            if (sinceMinutes == INVALID_INT) return error("sinceMinutes must be an integer");
            if (limit == INVALID_INT) return error("limit must be an integer");
            Severity minSeverity;
            try {
                minSeverity = severityArg(arg(call, "minSeverity"));
            } catch (IllegalArgumentException e) {
                return error(e.getMessage());
            }
            long sinceMs = System.currentTimeMillis()
                    - (sinceMinutes == null ? SIGNALS_DEFAULT_SINCE_MINUTES : sinceMinutes) * 60_000L;
            int cap = limit == null ? SIGNALS_DEFAULT_LIMIT : Math.min(Math.max(limit, 1), SIGNALS_MAX_LIMIT);
            List<Signal> signals = Signals.query(service.events(), arg(call, "type"), sinceMs, null,
                    minSeverity, arg(call, "correlationId"), cap);
            return ok(Map.of("count", signals.size(),
                    "signals", signals.stream().map(Signal::toMap).toList()));
        });
    }

    private static Tool signalTimeline(CollectorService service) {
        ToolSpec spec = new ToolSpec("signal_timeline",
                "Reconstruct the causal timeline of signals for one correlationId — use for "
                        + "'why did X fail' questions; entries carry causedBy links and citable signalIds",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"correlationId\":{\"type\":\"string\"},"
                        + "\"sinceMinutes\":{\"type\":\"integer\"}},"
                        + "\"required\":[\"correlationId\"]}",
                false, Role.USER, Capability.READ_METADATA);
        return new FunctionTool(spec, call -> {
            String correlationId = arg(call, "correlationId");
            if (correlationId == null || correlationId.isBlank()) return error("correlationId is required");
            Integer sinceMinutes = intArg(call, "sinceMinutes");
            if (sinceMinutes == INVALID_INT) return error("sinceMinutes must be an integer");
            long sinceMs = System.currentTimeMillis()
                    - (sinceMinutes == null ? TIMELINE_DEFAULT_SINCE_MINUTES : sinceMinutes) * 60_000L;
            List<Signal> matched = Signals.query(service.events(), null, sinceMs, null, null,
                    correlationId, TIMELINE_FETCH_LIMIT);
            if (matched.isEmpty()) return error("no signals for correlationId '" + correlationId + "'");
            List<Map<String, Object>> timeline = new ArrayList<>();
            for (Signal s : causationOrder(matched)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("signalId", s.signalId());
                entry.put("at", s.at().toString());
                entry.put("type", s.type());
                entry.put("severity", s.severity().name().toLowerCase(Locale.ROOT));
                entry.put("message", s.message());
                entry.put("causedBy", s.causationId());
                entry.put("payload", s.payload());
                timeline.add(entry);
            }
            return ok(Map.of("correlationId", correlationId, "count", timeline.size(),
                    "timeline", timeline));
        });
    }

    /**
     * Causation-aware order: roots (no {@code causationId}, or one pointing outside the set —
     * "orphans" included, never dropped) sorted oldest-first by timestamp then signalId, each
     * followed depth-first by its causal children in the same sort. Any cycle remnants are appended
     * timestamp-ordered so nothing is ever lost.
     */
    private static List<Signal> causationOrder(List<Signal> signals) {
        List<Signal> sorted = new ArrayList<>(signals);
        sorted.sort(java.util.Comparator.comparing(Signal::at)
                .thenComparing(Signal::signalId, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Signal s : sorted) ids.add(s.signalId());
        Map<String, List<Signal>> children = new LinkedHashMap<>();
        List<Signal> roots = new ArrayList<>();
        for (Signal s : sorted) {
            String cause = s.causationId();
            if (cause != null && ids.contains(cause) && !cause.equals(s.signalId())) {
                children.computeIfAbsent(cause, k -> new ArrayList<>()).add(s);
            } else {
                roots.add(s);
            }
        }
        List<Signal> out = new ArrayList<>(sorted.size());
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.Deque<Signal> stack = new java.util.ArrayDeque<>();
        for (Signal root : roots) {
            stack.push(root);
            while (!stack.isEmpty()) {
                Signal s = stack.pop();
                if (!visited.add(s.signalId())) continue;
                out.add(s);
                List<Signal> kids = children.getOrDefault(s.signalId(), List.of());
                for (int i = kids.size() - 1; i >= 0; i--) stack.push(kids.get(i)); // preserve sort order
            }
        }
        for (Signal s : sorted) {
            if (!visited.contains(s.signalId())) out.add(s); // cycle remnants, timestamp-ordered
        }
        return out;
    }

    // ── AGT-5 P1 slice A: investigation analysis tools ──────────────────────────

    private static final int TIMELINE_BUILD_CAP = 500;
    private static final DateTimeFormatter AUDIT_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DIFF_VALUE_MAX_CHARS = 200;
    private static final int ANOMALY_DEFAULT_LIMIT = 20;
    private static final int ANOMALY_MAX_LIMIT = 200;
    private static final double ANOMALY_DEFAULT_Z_CUTOFF = 3.0;

    /** One merged timeline entry; {@code matchText} is the extra text the optional focus filter scans. */
    private record TimelineEntry(Instant at, String kind, String summary, String ref,
                                 String severity, String matchText) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", at.toString());
            m.put("kind", kind);
            m.put("summary", summary);
            m.put("ref", ref);
            if (severity != null) m.put("severity", severity);
            return m;
        }
    }

    private static Tool timelineBuild(CollectorService service, ComponentStore components) {
        ToolSpec spec = new ToolSpec("timeline_build",
                "Merge everything that happened in a time window — all signals, job runs and component "
                        + "config saves — into one ascending timeline; optional focus text filters entries",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"sinceMinutes\":{\"type\":\"integer\"},"
                        + "\"untilMinutes\":{\"type\":\"integer\"},"
                        + "\"focus\":{\"type\":\"string\"}},"
                        + "\"required\":[\"sinceMinutes\"]}",
                false, Role.USER, Capability.READ_METADATA);
        return new FunctionTool(spec, call -> {
            Integer sinceMinutes = intArg(call, "sinceMinutes");
            if (sinceMinutes == null) return error("sinceMinutes is required");
            if (sinceMinutes == INVALID_INT) return error("sinceMinutes must be an integer");
            Integer untilMinutes = intArg(call, "untilMinutes");
            if (untilMinutes == INVALID_INT) return error("untilMinutes must be an integer");
            long now = System.currentTimeMillis();
            long sinceMs = now - sinceMinutes * 60_000L;
            Long untilMs = untilMinutes == null ? null : now - untilMinutes * 60_000L;
            if (untilMs != null && untilMs <= sinceMs)
                return error("untilMinutes must lie inside the sinceMinutes window");
            List<TimelineEntry> entries = new ArrayList<>();
            List<Signal> signals = Signals.query(service.events(), null, sinceMs, untilMs, null, null,
                    TIMELINE_BUILD_CAP + 1);
            for (Signal s : signals) {
                entries.add(new TimelineEntry(s.at(), "signal",
                        s.type() + (s.message() == null ? "" : ": " + s.message()),
                        s.signalId(), s.severity().name().toLowerCase(Locale.ROOT),
                        s.type() + " " + s.correlationId() + " " + s.payload()));
            }
            addJobRuns(service, sinceMs, untilMs, entries);
            addConfigChanges(components, sinceMs, untilMs, entries);
            String focus = arg(call, "focus");
            if (focus != null && !focus.isBlank()) {
                String needle = focus.trim().toLowerCase(Locale.ROOT);
                entries.removeIf(e -> !(e.summary() + " " + e.ref() + " " + e.matchText())
                        .toLowerCase(Locale.ROOT).contains(needle));
            }
            entries.sort(Comparator.comparing(TimelineEntry::at)
                    .thenComparing(TimelineEntry::ref, Comparator.nullsFirst(Comparator.naturalOrder())));
            boolean truncated = entries.size() > TIMELINE_BUILD_CAP || signals.size() > TIMELINE_BUILD_CAP;
            List<TimelineEntry> capped = entries.size() > TIMELINE_BUILD_CAP
                    ? entries.subList(0, TIMELINE_BUILD_CAP) : entries;
            return ok(Map.of("count", capped.size(), "truncated", truncated,
                    "timeline", capped.stream().map(TimelineEntry::toMap).toList()));
        });
    }

    /** Job runs whose start time falls in the window, as {@code job-run} entries (FAILED → error). */
    private static void addJobRuns(CollectorService service, long sinceMs, Long untilMs,
                                   List<TimelineEntry> entries) {
        service.jobService().ifPresent(js -> {
            for (JobService.JobView job : js.jobs()) {
                for (JobRun r : js.runsFor(job.name())) {
                    Instant at = parseAuditTs(r.startTime());
                    if (at == null || !inWindow(at, sinceMs, untilMs)) continue;
                    boolean failed = "FAILED".equalsIgnoreCase(r.status());
                    entries.add(new TimelineEntry(at, "job-run",
                            "job " + r.job() + " " + String.valueOf(r.status()).toLowerCase(Locale.ROOT)
                                    + " (" + r.durationMs() + " ms)",
                            r.runId(), failed ? "error" : null,
                            r.job() + " " + r.status() + " " + r.message()));
                }
            }
        });
    }

    /** Component saves in the window: the live file's save plus every archived {@code .history} version. */
    private static void addConfigChanges(ComponentStore components, long sinceMs, Long untilMs,
                                         List<TimelineEntry> entries) {
        if (components == null) return;
        List<String> types = new ArrayList<>(ComponentStore.WRITABLE_TYPES);
        java.util.Collections.sort(types);
        for (String type : types) {
            List<ComponentRegistry.Component> comps;
            try {
                comps = components.list(type);
            } catch (RuntimeException e) {
                continue; // a type dir the registry can't scan never blocks the rest
            }
            for (ComponentRegistry.Component c : comps) {
                try {
                    Instant current = Files.getLastModifiedTime(c.path()).toInstant();
                    if (inWindow(current, sinceMs, untilMs)) {
                        entries.add(new TimelineEntry(current, "config-change",
                                c.ref() + " saved (current)", "component:" + c.ref(), null, c.ref()));
                    }
                } catch (IOException ignored) {
                    // a torn-down live file only loses its own entry
                }
                for (ComponentStore.ComponentVersion v : components.versions(type, c.name())) {
                    if (!inWindow(v.savedAt(), sinceMs, untilMs)) continue;
                    entries.add(new TimelineEntry(v.savedAt(), "config-change",
                            c.ref() + " saved (v" + v.version() + ")", "component:" + c.ref(), null, c.ref()));
                }
            }
        }
    }

    private static boolean inWindow(Instant at, long sinceMs, Long untilMs) {
        long t = at.toEpochMilli();
        return t >= sinceMs && (untilMs == null || t <= untilMs);
    }

    /** The audit CSV / run-log timestamp format ({@code yyyy-MM-dd HH:mm:ss}, local time) → Instant. */
    private static Instant parseAuditTs(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s.trim(), AUDIT_TS).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Tool diffBatches(CollectorService service) {
        ToolSpec spec = new ToolSpec("diff_batches",
                "Compare two batch-ledger entries of one pipeline: per-batch status, row count, "
                        + "duration and start time, plus the delta between them",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"pipeline\":{\"type\":\"string\"},"
                        + "\"batchA\":{\"type\":\"string\"},"
                        + "\"batchB\":{\"type\":\"string\"}},"
                        + "\"required\":[\"pipeline\",\"batchA\",\"batchB\"]}",
                false, Role.USER, Capability.READ_METADATA);
        return new FunctionTool(spec, call -> {
            String pipeline = arg(call, "pipeline");
            String batchA = arg(call, "batchA");
            String batchB = arg(call, "batchB");
            if (pipeline == null || pipeline.isBlank()) return error("pipeline is required");
            if (batchA == null || batchA.isBlank()) return error("batchA is required");
            if (batchB == null || batchB.isBlank()) return error("batchB is required");
            Optional<PipelineConfig> cfg = service.configFor(pipeline);
            if (cfg.isEmpty()) return error("unknown pipeline: '" + pipeline + "'");
            List<Map<String, String>> rows = service.statusStore().batches(cfg.get());
            Map<String, String> a = batchRow(rows, batchA);
            if (a == null) return error("batch '" + batchA + "' not found in pipeline '" + pipeline + "'");
            Map<String, String> b = batchRow(rows, batchB);
            if (b == null) return error("batch '" + batchB + "' not found in pipeline '" + pipeline + "'");
            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("rowCount", longDelta(a.get("total_output_rows"), b.get("total_output_rows")));
            delta.put("durationMs", longDelta(a.get("duration_ms"), b.get("duration_ms")));
            String sa = a.get("status");
            String sb = b.get("status");
            delta.put("status", Objects.equals(sa, sb) ? "unchanged" : sa + " -> " + sb);
            return ok(Map.of("pipeline", pipeline,
                    "batchA", batchView(a), "batchB", batchView(b), "delta", delta));
        });
    }

    private static Map<String, String> batchRow(List<Map<String, String>> rows, String batchId) {
        return rows.stream().filter(r -> batchId.equals(r.get("batch_id"))).findFirst().orElse(null);
    }

    /** The comparison view over one raw {@code _batches_} audit row (every CSV cell is a string). */
    private static Map<String, Object> batchView(Map<String, String> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", row.get("status"));
        m.put("rowCount", parseLongOrNull(row.get("total_output_rows")));
        m.put("durationMs", parseLongOrNull(row.get("duration_ms")));
        m.put("startedAt", row.get("start_time"));
        return m;
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code B - A} over two numeric audit cells, or {@code null} when either side is unparseable. */
    private static Long longDelta(String a, String b) {
        Long la = parseLongOrNull(a);
        Long lb = parseLongOrNull(b);
        return la == null || lb == null ? null : lb - la;
    }

    private static Tool configVersionsDiff(ComponentStore components) {
        ToolSpec spec = new ToolSpec("config_versions_diff",
                "Structural diff of two archived versions of a registry component (ComponentStore-backed "
                        + "kinds only — grammar/schema/transform/sink/query/expectation etc.; pipeline "
                        + "configs keep no version history). Defaults to the latest two versions.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"type\":{\"type\":\"string\"},"
                        + "\"id\":{\"type\":\"string\"},"
                        + "\"fromVersion\":{\"type\":\"integer\"},"
                        + "\"toVersion\":{\"type\":\"integer\"}},"
                        + "\"required\":[\"type\",\"id\"]}",
                false, Role.USER, Capability.READ_METADATA);
        return new FunctionTool(spec, call -> {
            if (components == null) return error("component registry unavailable (set -Dassist.write.root)");
            String type = arg(call, "type");
            String id = arg(call, "id");
            if (type == null || type.isBlank()) return error("type is required");
            if (id == null || id.isBlank()) return error("id is required");
            Integer from = intArg(call, "fromVersion");
            if (from == INVALID_INT) return error("fromVersion must be an integer");
            Integer to = intArg(call, "toVersion");
            if (to == INVALID_INT) return error("toVersion must be an integer");
            if ((from == null) != (to == null))
                return error("provide both fromVersion and toVersion, or neither");
            List<ComponentStore.ComponentVersion> versions;
            try {
                versions = components.versions(type, id);   // newest first
            } catch (IllegalArgumentException e) {
                return error(e.getMessage());
            }
            int fromV;
            int toV;
            Map<String, Object> fromContent;
            Map<String, Object> toContent;
            if (from == null) {
                if (versions.size() < 2)
                    return error("fewer than two archived versions for " + type + "/" + id
                            + " (found " + versions.size() + ")");
                toV = versions.get(0).version();
                toContent = versions.get(0).content();
                fromV = versions.get(1).version();
                fromContent = versions.get(1).content();
            } else {
                fromV = from;
                toV = to;
                Optional<Map<String, Object>> f = components.versionContent(type, id, fromV);
                if (f.isEmpty()) return error("version " + fromV + " not found for " + type + "/" + id);
                Optional<Map<String, Object>> t = components.versionContent(type, id, toV);
                if (t.isEmpty()) return error("version " + toV + " not found for " + type + "/" + id);
                fromContent = f.get();
                toContent = t.get();
            }
            Map<String, Object> added = new LinkedHashMap<>();
            List<String> removed = new ArrayList<>();
            Map<String, Object> changed = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : toContent.entrySet()) {
                if (!fromContent.containsKey(e.getKey())) {
                    added.put(e.getKey(), bounded(e.getValue()));
                } else if (!Objects.equals(fromContent.get(e.getKey()), e.getValue())) {
                    Map<String, Object> ch = new LinkedHashMap<>();
                    ch.put("from", bounded(fromContent.get(e.getKey())));
                    ch.put("to", bounded(e.getValue()));
                    changed.put(e.getKey(), ch);
                }
            }
            for (String k : fromContent.keySet()) {
                if (!toContent.containsKey(k)) removed.add(k);
            }
            return ok(Map.of("type", type, "id", id, "fromVersion", fromV, "toVersion", toV,
                    "added", added, "removed", removed, "changed", changed));
        });
    }

    /** Stringified diff value, bounded to {@value #DIFF_VALUE_MAX_CHARS} chars. */
    private static String bounded(Object v) {
        String s = String.valueOf(v);
        return s.length() <= DIFF_VALUE_MAX_CHARS ? s : s.substring(0, DIFF_VALUE_MAX_CHARS) + "…";
    }

    private static Tool anomalyScan(Supplier<List<BrowsableStore>> browseStores) {
        ToolSpec spec = new ToolSpec("anomaly_scan",
                "Deterministic outlier scan over a numeric column of a DB-backed operational store "
                        + "table: z-score (default, cutoff 3.0) or a fixed threshold bound — pure SQL "
                        + "math, no model",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"table\":{\"type\":\"string\"},"
                        + "\"column\":{\"type\":\"string\"},"
                        + "\"method\":{\"type\":\"string\",\"enum\":[\"zscore\",\"threshold\"]},"
                        + "\"threshold\":{\"type\":\"number\"},"
                        + "\"limit\":{\"type\":\"integer\"}},"
                        + "\"required\":[\"table\",\"column\"]}",
                false, Role.USER, Capability.READ_METADATA);
        return new FunctionTool(spec, call -> {
            String table = arg(call, "table");
            String column = arg(call, "column");
            if (table == null || table.isBlank()) return error("table is required");
            if (column == null || column.isBlank()) return error("column is required");
            String method = arg(call, "method");
            method = method == null || method.isBlank() ? "zscore" : method.trim().toLowerCase(Locale.ROOT);
            if (!method.equals("zscore") && !method.equals("threshold"))
                return error("unknown method '" + method + "' (expected zscore|threshold)");
            Double threshold = doubleArg(call, "threshold");
            if (threshold != null && threshold.isNaN()) return error("threshold must be a number");
            if (method.equals("threshold") && threshold == null)
                return error("threshold is required for method=threshold");
            Integer limit = intArg(call, "limit");
            if (limit == INVALID_INT) return error("limit must be an integer");
            int cap = limit == null ? ANOMALY_DEFAULT_LIMIT : Math.min(Math.max(limit, 1), ANOMALY_MAX_LIMIT);
            BrowsableStore store = browseStores.get().stream()
                    .filter(s -> s.browseTables().contains(table)).findFirst().orElse(null);
            if (store == null) return error("unknown table: '" + table + "' (no DB-backed store exposes it)");
            String t = sqlIdent(table);
            String c = sqlIdent(column);
            try {
                String statsSql = "SELECT avg(" + c + ") AS __mean, stddev_pop(" + c + ") AS __sd, "
                        + "count(" + c + ") AS __n FROM " + t;
                List<Finding> findings = SqlGuard.check(statsSql);
                if (!findings.isEmpty()) return error(findings.get(0).message());
                BrowsableStore.Page stats = store.browseQuery(statsSql, 1, 0);
                Map<String, Object> s0 = stats.rows().isEmpty() ? Map.of() : stats.rows().get(0);
                if (!(s0.get("__mean") instanceof Number meanN))
                    return error("column '" + column + "' has no numeric values in table '" + table + "'");
                double mean = meanN.doubleValue();
                double sd = s0.get("__sd") instanceof Number sdN ? sdN.doubleValue() : 0d;
                long scanned = s0.get("__n") instanceof Number n ? n.longValue() : 0L;
                String zExpr = sd > 0 ? "((" + c + " - " + mean + ") / " + sd + ")" : "NULL";
                String where;
                String order;
                if (method.equals("zscore")) {
                    double cutoff = threshold == null ? ANOMALY_DEFAULT_Z_CUTOFF : threshold;
                    if (sd <= 0)
                        return ok(scanResult(table, column, method, mean, sd, scanned, List.of(), false));
                    where = "abs" + zExpr + " > " + cutoff;
                    order = "abs" + zExpr + " DESC";
                } else {
                    where = c + " > " + threshold;
                    order = c + " DESC";
                }
                String outSql = "SELECT *, " + zExpr + " AS __z FROM " + t
                        + " WHERE " + where + " ORDER BY " + order;
                findings = SqlGuard.check(outSql);
                if (!findings.isEmpty()) return error(findings.get(0).message());
                BrowsableStore.Page page = store.browseQuery(outSql, cap, 0);
                String idCol = page.columns().isEmpty() ? null : page.columns().get(0).name();
                List<Map<String, Object>> flagged = new ArrayList<>();
                for (Map<String, Object> row : page.rows()) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("value", row.get(column));
                    if (idCol != null && !idCol.equalsIgnoreCase(column)) f.put("rowIdentifier", row.get(idCol));
                    f.put("zscore", row.get("__z") instanceof Number zn ? zn.doubleValue() : null);
                    flagged.add(f);
                }
                return ok(scanResult(table, column, method, mean, sd, scanned, flagged, page.truncated()));
            } catch (SQLException | RuntimeException e) {
                return error("scan failed: " + e.getMessage());
            }
        });
    }

    private static final int PROFILE_STAT_COLS = 1;

    /**
     * AGT-5 P2 {@code suggest_expectations} (plan §8 "Expectation/Alert-Rule suggestion from
     * profiling"): profile one column of a DB-backed store (row/null/distinct counts + numeric
     * min/max, pure deterministic SQL like {@code anomaly_scan}) and derive candidate data-quality
     * {@code expectation} drafts — a {@code non_null} check when the column was never null, a
     * {@code range} check from the observed bounds when it is fully numeric. The model judges which to
     * keep; a chosen draft goes through {@code component_draft} to validate, then a human applies it.
     * Persists nothing; unknown table/column is an {@code ok=false} result, never a throw.
     */
    private static Tool suggestExpectations(Supplier<List<BrowsableStore>> browseStores) {
        ToolSpec spec = new ToolSpec("suggest_expectations",
                "Profile a column of a DB-backed operational store (row count, null count/fraction, distinct "
                        + "count, numeric min/max) and derive candidate Expectation drafts (non_null when never "
                        + "null; range from observed bounds when numeric). Deterministic SQL, no model. Feed a "
                        + "chosen draft to component_draft, then a human applies it.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"table\":{\"type\":\"string\"},"
                        + "\"column\":{\"type\":\"string\"},"
                        + "\"target\":{\"type\":\"string\"}},"
                        + "\"required\":[\"table\",\"column\"]}",
                false, Role.USER, Capability.READ_METADATA);
        return new FunctionTool(spec, call -> {
            String table = arg(call, "table");
            String column = arg(call, "column");
            if (table == null || table.isBlank()) return error("table is required");
            if (column == null || column.isBlank()) return error("column is required");
            String target = arg(call, "target");
            if (target == null || target.isBlank()) target = table;
            BrowsableStore store = browseStores.get().stream()
                    .filter(s -> s.browseTables().contains(table)).findFirst().orElse(null);
            if (store == null) return error("unknown table: '" + table + "' (no DB-backed store exposes it)");
            String t = sqlIdent(table);
            String c = sqlIdent(column);
            try {
                String profileSql = "SELECT count(*) AS __n, count(" + c + ") AS __nonnull, "
                        + "count(DISTINCT " + c + ") AS __distinct, "
                        + "count(TRY_CAST(" + c + " AS DOUBLE)) AS __numeric, "
                        + "min(TRY_CAST(" + c + " AS DOUBLE)) AS __min, "
                        + "max(TRY_CAST(" + c + " AS DOUBLE)) AS __max FROM " + t;
                List<Finding> findings = SqlGuard.check(profileSql);
                if (!findings.isEmpty()) return error(findings.get(0).message());
                BrowsableStore.Page page = store.browseQuery(profileSql, PROFILE_STAT_COLS, 0);
                Map<String, Object> r0 = page.rows().isEmpty() ? Map.of() : page.rows().get(0);
                long n = asLong(r0.get("__n"));
                long nonNull = asLong(r0.get("__nonnull"));
                long distinct = asLong(r0.get("__distinct"));
                long numeric = asLong(r0.get("__numeric"));
                long nulls = n - nonNull;
                boolean isNumeric = nonNull > 0 && numeric == nonNull;

                Map<String, Object> profile = new LinkedHashMap<>();
                profile.put("rows", n);
                profile.put("nulls", nulls);
                profile.put("nullFraction", n > 0 ? (double) nulls / n : 0d);
                profile.put("distinct", distinct);
                profile.put("numeric", isNumeric);
                if (isNumeric) {
                    profile.put("min", r0.get("__min"));
                    profile.put("max", r0.get("__max"));
                }

                List<Map<String, Object>> suggestions = new ArrayList<>();
                if (n > 0 && nulls == 0) {
                    suggestions.add(expectationDraft(column, target, "non_null",
                            table + "_" + column + "_not_null",
                            column + " was never null across " + n + " rows", null, null));
                }
                if (isNumeric && r0.get("__min") != null && r0.get("__max") != null) {
                    suggestions.add(expectationDraft(column, target, "range",
                            table + "_" + column + "_range",
                            "observed numeric bounds over " + nonNull + " values",
                            String.valueOf(r0.get("__min")), String.valueOf(r0.get("__max"))));
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("table", table);
                result.put("column", column);
                result.put("profile", profile);
                result.put("suggestions", suggestions);
                if (suggestions.isEmpty()) {
                    result.put("note", "no confident suggestion — the column has nulls and is not fully numeric");
                }
                return ok(result);
            } catch (SQLException | RuntimeException e) {
                return error("profile failed: " + e.getMessage());
            }
        });
    }

    /** Build an {@code expectation} config draft (the shape {@code component_draft(kind=expectation)} validates). */
    private static Map<String, Object> expectationDraft(String column, String target, String kind,
                                                        String name, String description,
                                                        String min, String max) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("name", name);
        e.put("description", "Auto-suggested from profiling: " + description);
        e.put("targetType", "pipeline");
        e.put("target", target);
        e.put("column", column);
        e.put("kind", kind);
        if (min != null) e.put("min", min);
        if (max != null) e.put("max", max);
        e.put("severity", "MAJOR");
        e.put("enabled", true);
        return e;
    }

    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * The AGT-5 P2 authoring spine (plan §8 "Author everything (L1)"): validate a proposed component
     * draft against the <em>same</em> structural spec ({@link ConfigSpecs}) plus the hard-fail safety
     * gate ({@link ConfigSafetyValidator}) the control plane enforces on write, and hand every
     * violation back as an anchored {@link Finding} the model can read and repair. This is the
     * "validator repair loop": the agent proposes a config, sees the findings, revises, re-validates —
     * until {@code clean=true}, a draft a human can apply unchanged. It never persists (apply is the
     * L2/P3 gated-action step) and never throws (an unvalidatable kind or malformed body is an
     * {@code ok=false} result, exactly like the read belt).
     */
    private static Tool componentDraft() {
        ToolSpec spec = new ToolSpec("component_draft",
                "Validate a proposed component draft (kind: pipeline|enrichment|job|schema|expectation|"
                        + "alert-rule) against the control plane's structural spec + hard-fail safety gate; "
                        + "returns anchored findings to repair. Does not persist — a clean draft is one a "
                        + "human can apply unchanged.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"kind\":{\"type\":\"string\"},"
                        + "\"config\":{\"type\":\"object\"}},"
                        + "\"required\":[\"kind\",\"config\"]}",
                false, Role.USER, Capability.AUTHOR_PIPELINE);
        return new FunctionTool(spec, call -> {
            String kind = arg(call, "kind");
            if (kind == null || kind.isBlank()) return error("kind is required");
            Map<String, Object> draft = mapArg(call, "config");
            if (draft == null) return error("config is required and must be an object");
            String type = configType(kind);
            ConfigSpec cfgSpec = ConfigSpecs.forType(type);
            if (cfgSpec == null) {
                return error("no structural spec for kind '" + kind
                        + "' (validatable kinds: pipeline, enrichment, job, schema, expectation, alert-rule)");
            }
            List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(cfgSpec, draft));
            // Agent drafts must clear the security boundary before a human ever sees them (plan §6.4):
            // the safety gate is always applied here, not opt-in as on the /validate route.
            findings.addAll(ConfigSafetyValidator.check(type, draft, SafetyPolicy.defaultPolicy()));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("kind", kind);
            result.put("type", type);
            result.put("clean", findings.isEmpty());
            result.put("findings", findings.stream().map(InspectoTools::findingMap).toList());
            result.put("draft", draft);
            return ok(result);
        });
    }

    /** Map a component kind to its {@link ConfigSpecs} config type ({@code alert-rule}→{@code alert}). */
    private static String configType(String kind) {
        String k = kind.trim().toLowerCase(Locale.ROOT);
        return k.equals("alert-rule") ? "alert" : k;
    }

    /**
     * AGT-5 P3 {@code component_apply} (act, L2): promote a validated draft into the live registry
     * (DRAFT→ACTIVE). Mutating — the eoiagent gate dry-runs it, blocks for a human approval in the
     * inbox, and audits the outcome; on approval the tool writes through the same governed control
     * plane a UI caller uses ({@code PUT}/{@code POST /components/*}, {@code If-Match} + WriteGates +
     * {@code actor=agent} audit). Refuses anything the safety gate rejects. See {@link ComponentActions}.
     */
    private static Tool componentApply(ControlPlaneClient controlPlane) {
        ToolSpec spec = new ToolSpec(ComponentActions.TOOL_COMPONENT_APPLY,
                "Apply a validated component draft as the live component (DRAFT→ACTIVE) via the governed "
                        + "control plane. Gated: dry-run diff → human approval → audited write. Refuses drafts "
                        + "that fail the safety gate. Args: type, id, config.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"type\":{\"type\":\"string\"},"
                        + "\"id\":{\"type\":\"string\"},"
                        + "\"config\":{\"type\":\"object\"}},"
                        + "\"required\":[\"type\",\"id\",\"config\"]}",
                true, Role.USER, Capability.EDIT_CONFIG);
        return new FunctionTool(spec, call -> ComponentActions.apply(controlPlane, call, agentSession(call)));
    }

    /**
     * AGT-5 P3 {@code component_rollback} (act, L2): restore an archived component version via the
     * existing {@code POST /components/{type}/{id}/versions/{v}/restore} route (itself a versioned,
     * undoable write). Mutating — same gate + audit path as {@link #componentApply}.
     */
    private static Tool componentRollback(ControlPlaneClient controlPlane) {
        ToolSpec spec = new ToolSpec(ComponentActions.TOOL_COMPONENT_ROLLBACK,
                "Roll a registry component back to an archived version via the governed control plane. "
                        + "Gated: dry-run diff → human approval → audited write. Args: type, id, version.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"type\":{\"type\":\"string\"},"
                        + "\"id\":{\"type\":\"string\"},"
                        + "\"version\":{\"type\":\"integer\"}},"
                        + "\"required\":[\"type\",\"id\",\"version\"]}",
                true, Role.USER, Capability.EDIT_CONFIG);
        return new FunctionTool(spec, call -> ComponentActions.rollback(controlPlane, call, agentSession(call)));
    }

    /**
     * AGT-5 P3 {@code job_run} (act, L2): trigger a job on demand via the governed control plane
     * ({@code POST /jobs/{name}/trigger}). Mutating — the eoiagent gate dry-runs it (the operator sees
     * the target + whether it exists), blocks for approval, and audits the trigger as {@code actor=agent}.
     */
    private static Tool jobRun(ControlPlaneClient controlPlane) {
        ToolSpec spec = new ToolSpec(OperationalActions.TOOL_JOB_RUN,
                "Trigger a job to run now via the governed control plane. Gated: dry-run → human approval "
                        + "→ audited trigger. Args: job (name), params (optional object of string args).",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"job\":{\"type\":\"string\"},"
                        + "\"params\":{\"type\":\"object\"}},"
                        + "\"required\":[\"job\"]}",
                true, Role.USER, Capability.TRIGGER_JOB);
        return new FunctionTool(spec, call -> OperationalActions.jobRun(controlPlane, call, agentSession(call)));
    }

    /**
     * AGT-5 P3 {@code pipeline_rerun} (act, L2): replay a committed batch of a pipeline — the RCA
     * remediation verb — via {@code POST /runs/{pipeline}/reprocess}. Mutating: same gate + audit path.
     */
    private static Tool pipelineRerun(ControlPlaneClient controlPlane) {
        ToolSpec spec = new ToolSpec(OperationalActions.TOOL_PIPELINE_RERUN,
                "Reprocess (replay) a committed batch of a pipeline via the governed control plane. "
                        + "Gated: dry-run → human approval → audited reprocess. Args: pipeline, batchId.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"pipeline\":{\"type\":\"string\"},"
                        + "\"batchId\":{\"type\":\"string\"}},"
                        + "\"required\":[\"pipeline\",\"batchId\"]}",
                true, Role.USER, Capability.RUN_PIPELINE);
        return new FunctionTool(spec, call -> OperationalActions.pipelineRerun(controlPlane, call, agentSession(call)));
    }

    /**
     * AGT-5 P3 {@code alert_ack} (act, L2): acknowledge an operational alert (an Alert-Center object,
     * {@code OPEN→ACKNOWLEDGED}) via {@code POST /objects/{id}/ack}. Mutating: same gate + audit path.
     */
    private static Tool alertAck(ControlPlaneClient controlPlane) {
        ToolSpec spec = new ToolSpec(OperationalActions.TOOL_ALERT_ACK,
                "Acknowledge an operational alert (Alert-Center object: OPEN→ACKNOWLEDGED) via the "
                        + "governed control plane. Gated: dry-run → human approval → audited ack. Args: id.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"id\":{\"type\":\"string\"}},"
                        + "\"required\":[\"id\"]}",
                true, Role.USER, Capability.WRITE_DATASTORE);
        return new FunctionTool(spec, call -> OperationalActions.alertAck(controlPlane, call, agentSession(call)));
    }

    /**
     * AGT-5 P3 {@code schedule_apply} (act, L2): change a job's cron schedule via
     * {@code POST /jobs/{name}/reschedule} (write-root gated server-side). Mutating: same gate + audit path.
     */
    private static Tool scheduleApply(ControlPlaneClient controlPlane) {
        ToolSpec spec = new ToolSpec(OperationalActions.TOOL_SCHEDULE_APPLY,
                "Change a job's cron schedule via the governed control plane. Gated: dry-run → human "
                        + "approval → audited reschedule. Args: job (name), cron (expression).",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"job\":{\"type\":\"string\"},"
                        + "\"cron\":{\"type\":\"string\"}},"
                        + "\"required\":[\"job\",\"cron\"]}",
                true, Role.USER, Capability.EDIT_CONFIG);
        return new FunctionTool(spec, call -> OperationalActions.scheduleApply(controlPlane, call, agentSession(call)));
    }

    /**
     * AGT-5 P3 {@code runbook_operator} (act, L2): execute a named, seeded runbook — a fixed sequence of
     * the existing act tools — as one approval-gated unit. The model picks a runbook name + params; the
     * operator approves the full plan (the preview lists every resolved step); each step then runs
     * post-approval through the same audited control plane. See {@link RunbookActions}.
     */
    private static Tool runbookOperator(ControlPlaneClient controlPlane) {
        ToolSpec spec = new ToolSpec(RunbookActions.TOOL_RUNBOOK_OPERATOR,
                "Execute a named, seeded remediation runbook (a fixed sequence of act tools) as one "
                        + "approval-gated unit via the governed control plane. Gated: dry-run of the full plan "
                        + "→ human approval → audited stepwise execution (halts on first failure). Args: "
                        + "runbook (name), params (object). Available runbooks: " + RunbookActions.catalogSummary(),
                "{\"type\":\"object\",\"properties\":{"
                        + "\"runbook\":{\"type\":\"string\"},"
                        + "\"params\":{\"type\":\"object\"}},"
                        + "\"required\":[\"runbook\"]}",
                true, Role.USER, Capability.EDIT_CONFIG);
        return new FunctionTool(spec, call -> RunbookActions.execute(controlPlane, call, agentSession(call)));
    }

    /** The agent-session token carried as {@code X-Agent-Session} → audited {@code actor=agent:<run>}. */
    private static String agentSession(ToolCall call) {
        return call.run() == null ? "unknown" : call.run().value();
    }

    private static Map<String, Object> findingMap(Finding f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("severity", f.severity().name());
        m.put("fieldPath", f.fieldPath());
        m.put("message", f.message());
        return m;
    }

    /**
     * AGT-5 P2 {@code pipeline_author} (plan §8): parse a proposed authored-flow graph (nodes + edges)
     * and — when the caller supplies sample rows — simulate its {@code transform → sink} subgraph on a
     * throwaway DuckDB, reporting the per-node produced-relation counts and each sink's row count. This
     * is the flow-world "parser test + simulate": the model tests a pipeline draft (the same
     * {@link PipelineDryRun} the editor's dry-run uses) before a human applies it. Persists nothing;
     * a malformed graph or a failing simulate is an {@code ok=false} result, never a throw.
     */
    private static Tool pipelineAuthor() {
        ToolSpec spec = new ToolSpec("pipeline_author",
                "Parse a proposed authored-flow graph {name,nodes,edges} and, given sampleRows (post-parse "
                        + "records), simulate its transform→sink subgraph on a throwaway DuckDB — per-node and "
                        + "per-sink row counts. Tests a pipeline draft before it is applied. Persists nothing.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"flow\":{\"type\":\"object\"},"
                        + "\"sampleRows\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}},"
                        + "\"required\":[\"flow\"]}",
                false, Role.USER, Capability.AUTHOR_PIPELINE);
        return new FunctionTool(spec, call -> {
            Map<String, Object> flow = mapArg(call, "flow");
            if (flow == null) return error("flow is required and must be an object");
            PipelineGraph g;
            try {
                g = PipelineCodec.fromMap(flow);
            } catch (IllegalArgumentException e) {
                return error("invalid flow: " + e.getMessage());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("flow", g.name());
            result.put("nodes", g.nodes().stream()
                    .map(n -> Map.of("id", n.id(), "type", n.type())).toList());

            List<Map<String, Object>> sampleRows = listOfMapsArg(call, "sampleRows");
            if (sampleRows == null || sampleRows.isEmpty()) {
                result.put("simulated", false);
                result.put("note", "graph parsed; provide sampleRows (post-parse records) to simulate the flow");
                return ok(result);
            }
            try {
                PipelineDryRun.Result dr = PipelineDryRun.run(g, sampleRows);
                result.put("simulated", true);
                result.put("seedNode", dr.seedNode());
                result.put("nodeOutputs", dr.nodes().stream().map(n -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("node", n.node());
                    m.put("type", n.type());
                    m.put("relations", n.relations().stream()
                            .map(r -> Map.of("rel", r.rel(), "rowCount", r.rowCount())).toList());
                    return m;
                }).toList());
                result.put("sinks", dr.sinks().stream().map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("node", s.node());
                    m.put("store", s.store());
                    m.put("rowCount", s.rowCount());
                    return m;
                }).toList());
                return ok(result);
            } catch (IllegalArgumentException e) {
                return error(e.getMessage());
            } catch (Exception e) {
                return error("simulate failed: " + e.getMessage());
            }
        });
    }

    private static Map<String, Object> scanResult(String table, String column, String method,
                                                  double mean, double stddev, long scanned,
                                                  List<Map<String, Object>> rows, boolean truncated) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("table", table);
        m.put("column", column);
        m.put("method", method);
        m.put("mean", mean);
        m.put("stddev", stddev);
        m.put("scanned", scanned);
        m.put("flagged", rows.size());
        m.put("truncated", truncated);
        m.put("rows", rows);
        return m;
    }

    /** Double-quote an identifier for server-built SQL (matches {@code BrowsableStore.quoteIdent}). */
    private static String sqlIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    /** Missing → null; a number or numeric string → its double value; anything else → {@code NaN}. */
    private static Double doubleArg(ToolCall call, String key) {
        Map<String, Object> args = call.arguments() == null ? Map.of() : call.arguments();
        Object v = args.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Strict severity parse for tool input: null/blank → no floor; unknown name → thrown (→ error(...)). */
    private static Severity severityArg(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Severity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown severity '" + raw
                    + "' (expected trace|debug|info|warn|error|critical)");
        }
    }

    /** Sentinel returned by {@link #intArg} for a present-but-unparseable integer argument. */
    private static final Integer INVALID_INT = Integer.MIN_VALUE;

    /** Missing → null; a number or numeric string → its int value; anything else → {@link #INVALID_INT}. */
    private static Integer intArg(ToolCall call, String key) {
        Map<String, Object> args = call.arguments() == null ? Map.of() : call.arguments();
        Object v = args.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return INVALID_INT;
        }
    }

    /** Case-insensitive substring search over every {@code .md} file under {@link #DOCS_ROOT}. */
    private static List<Map<String, Object>> search(String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> hits = new ArrayList<>();
        if (!Files.isDirectory(DOCS_ROOT)) return hits;
        try (Stream<Path> paths = Files.walk(DOCS_ROOT)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".md")).toList()) {
                if (hits.size() >= DOCS_SEARCH_MAX_HITS) break;
                List<String> lines;
                try {
                    lines = Files.readAllLines(file);
                } catch (IOException e) {
                    continue; // unreadable file — skip, not fatal to the search
                }
                for (int i = 0; i < lines.size() && hits.size() < DOCS_SEARCH_MAX_HITS; i++) {
                    if (lines.get(i).toLowerCase(Locale.ROOT).contains(needle)) {
                        hits.add(Map.of("file", DOCS_ROOT.relativize(file).toString(),
                                "line", i + 1, "snippet", lines.get(i).trim()));
                    }
                }
            }
        } catch (IOException e) {
            return hits; // partial results rather than a thrown error
        }
        return hits;
    }

    private static String arg(ToolCall call, String key) {
        Map<String, Object> args = call.arguments() == null ? Map.of() : call.arguments();
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** A nested object argument as a {@code Map<String,Object>}; {@code null} when absent or not an object. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapArg(ToolCall call, String key) {
        Map<String, Object> args = call.arguments() == null ? Map.of() : call.arguments();
        Object v = args.get(key);
        return v instanceof Map<?, ?> ? (Map<String, Object>) v : null;
    }

    /** An array-of-objects argument as a {@code List<Map<String,Object>>}; {@code null} when absent or
     *  not a list. Non-map elements are skipped (a best-effort read, not a strict parse). */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMapsArg(ToolCall call, String key) {
        Map<String, Object> args = call.arguments() == null ? Map.of() : call.arguments();
        Object v = args.get(key);
        if (!(v instanceof List<?> list)) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    private static ToolResult ok(Object value) {
        return new ToolResult(true, value, null, Map.of());
    }

    private static ToolResult error(String message) {
        return new ToolResult(false, null, message, Map.of());
    }

    /** A {@link Tool} whose behaviour is a fixed spec plus a function over the call. */
    private record FunctionTool(ToolSpec spec, Function<ToolCall, ToolResult> body) implements Tool {
        @Override
        public ToolResult invoke(ToolCall call) {
            return body.apply(Objects.requireNonNull(call, "call"));
        }
    }
}
