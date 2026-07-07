package com.gamma.intelligence.pack;

import com.eoiagent.core.Capability;
import com.eoiagent.core.Role;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.tool.Tool;
import com.gamma.service.SourceService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The read tool belt v1 (plan §3, L0 "read/ground"): {@code glossary_lookup} and {@code docs_search}
 * ground answers in {@code docs/} (the canonical vocabulary + product docs); {@code status_get}
 * grounds them in the live {@link SourceService}. All read-only, evidence-producing, never throw —
 * an expected failure (unknown term, no matches) is an {@code ok=false} {@link ToolResult}.
 */
final class InspectoTools {

    private static final Path DOCS_ROOT = Path.of("docs");
    private static final int DOCS_SEARCH_MAX_HITS = 10;

    private InspectoTools() {
    }

    static List<Tool> tools(SourceService service) {
        return List.of(glossaryLookup(), docsSearch(), statusGet(service));
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

    private static Tool statusGet(SourceService service) {
        ToolSpec spec = new ToolSpec("status_get",
                "Live pipeline status: paused state and committed-batch count, one or all pipelines",
                "{\"type\":\"object\",\"properties\":{\"pipelineId\":{\"type\":\"string\"}}}",
                false, Role.USER, Capability.READ_METADATA);
        return new FunctionTool(spec, call -> {
            String pipelineId = arg(call, "pipelineId");
            List<SourceService.PipelineView> views = service.pipelines();
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
