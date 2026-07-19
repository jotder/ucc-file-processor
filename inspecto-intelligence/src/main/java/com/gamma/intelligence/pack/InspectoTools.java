package com.gamma.intelligence.pack;

import com.eoiagent.core.Capability;
import com.eoiagent.core.Role;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.tool.Tool;
import com.gamma.service.CollectorService;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import com.gamma.signal.Signals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The read tool belt v1 (plan §3, L0 "read/ground"): {@code glossary_lookup} and {@code docs_search}
 * ground answers in {@code docs/} (the canonical vocabulary + product docs); {@code status_get}
 * grounds them in the live {@link CollectorService}. All read-only, evidence-producing, never throw —
 * an expected failure (unknown term, no matches) is an {@code ok=false} {@link ToolResult}.
 */
final class InspectoTools {

    private static final Path DOCS_ROOT = RepoPaths.root().map(r -> r.resolve("docs")).orElse(Path.of("docs"));
    private static final int DOCS_SEARCH_MAX_HITS = 10;

    private InspectoTools() {
    }

    static List<Tool> tools(CollectorService service) {
        return List.of(glossaryLookup(), docsSearch(), statusGet(service),
                signalsQuery(service), signalTimeline(service));
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
