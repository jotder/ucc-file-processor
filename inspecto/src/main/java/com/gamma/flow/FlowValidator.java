package com.gamma.flow;

import com.gamma.api.PublicApi;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structural validation of a {@link FlowGraph} before it is executed or accepted from the UI
 * (doc §14 T14, §13 R5, §12 B7). Unlike {@link com.gamma.etl.ConfigValidator} — which only emits
 * non-fatal warnings about suspicious-but-legal {@code *_pipeline.toon} settings — this validator
 * distinguishes hard {@link Severity#ERROR}s that make a flow <b>unexecutable</b> (a cycle, a
 * dangling edge) from {@link Severity#WARNING}s, so the executor and the future authoring API can
 * <em>reject</em> a broken graph rather than fail mid-run.
 *
 * <p>Checks (all over the IR alone — zero engine coupling):
 * <ul>
 *   <li><b>DAG over {@code data} edges</b> — a cycle in the record-set subgraph is rejected; flows
 *       are DAGs so the topological walk terminates (B7 / D10). Control + split + {@code route:*}
 *       edges are excluded from the cycle check, matching the executor's walk.</li>
 *   <li><b>No same-graph {@code on_commit}</b> (R5) — {@code on_commit} is <em>cross-flow only</em>
 *       (it triggers a downstream flow); an {@code on_commit} edge whose target is a node in this
 *       same graph would be a cycle the data-edge check can't see, so it is rejected here.</li>
 *   <li><b>No dangling endpoints</b> — every edge's {@code from} must be a node in the graph, and so
 *       must its {@code to} <em>unless</em> the edge is {@code on_commit} (whose {@code to} names
 *       another flow, not a local node).</li>
 *   <li><b>No duplicate node ids</b> and <b>at least one entry (trigger) node</b> for a non-empty
 *       graph (nothing can start a flow in which every node has an inbound edge).</li>
 * </ul>
 *
 * <p><b>Deferred to T9 (the node-output contract).</b> Validating that an edge's relationship is one
 * a node type actually <em>emits</em> (and that the target <em>accepts</em>) needs the multi-named-
 * relation output contract that lands with T9/T10; until then illegal-relationship wiring is not
 * checked here. The {@link #validate} structure leaves a clear seam for it.
 */
@PublicApi(since = "4.3.0")
public final class FlowValidator {

    private FlowValidator() {}

    /** Whether an issue blocks execution ({@link #ERROR}) or is merely advisory ({@link #WARNING}). */
    public enum Severity { ERROR, WARNING }

    // ── issue codes (stable identifiers for the UI / tests / audit) ───────────────
    public static final String EMPTY_GRAPH = "EMPTY_GRAPH";
    public static final String DUPLICATE_NODE = "DUPLICATE_NODE";
    public static final String DANGLING_FROM = "DANGLING_FROM";
    public static final String DANGLING_TO = "DANGLING_TO";
    public static final String ON_COMMIT_SAME_GRAPH = "ON_COMMIT_SAME_GRAPH";
    public static final String CYCLE = "CYCLE";
    public static final String NO_ENTRY = "NO_ENTRY";

    /** One validation finding: a {@code severity}, a stable {@code code}, and a human message. */
    public record Issue(Severity severity, String code, String message) {
        public boolean isError() {
            return severity == Severity.ERROR;
        }
    }

    /** The outcome of validating a graph: every issue found, in detection order. */
    public record Result(List<Issue> issues) {
        public Result {
            issues = (issues == null) ? List.of() : List.copyOf(issues);
        }

        /** Only the blocking {@link Severity#ERROR} issues. */
        public List<Issue> errors() {
            return issues.stream().filter(Issue::isError).toList();
        }

        /** Only the advisory {@link Severity#WARNING} issues. */
        public List<Issue> warnings() {
            return issues.stream().filter(i -> i.severity() == Severity.WARNING).toList();
        }

        /** {@code true} when the graph is executable (no errors; warnings are allowed). */
        public boolean ok() {
            return errors().isEmpty();
        }
    }

    /** Validate {@code g}, collecting every issue (does not throw). */
    public static Result validate(FlowGraph g) {
        List<Issue> issues = new ArrayList<>();
        Set<String> ids = checkNodeIdentity(g, issues);

        if (g.nodes().isEmpty()) {
            issues.add(new Issue(Severity.WARNING, EMPTY_GRAPH, "Flow '" + g.name() + "' has no nodes."));
            return new Result(issues);
        }

        checkEdgeEndpoints(g, ids, issues);
        detectDataCycle(g, ids, issues);
        if (g.entryNodes().isEmpty()) {
            issues.add(new Issue(Severity.ERROR, NO_ENTRY,
                    "Flow '" + g.name() + "' has no entry node — every node has an inbound edge, so nothing triggers it."));
        }
        // T9 seam: emit/accept-relationship wiring checks land with the node-output contract.
        return new Result(issues);
    }

    /**
     * Validate {@code g} and throw if it is not executable. The exception message lists every error.
     * Use this on the execution path (authoring/CRUD code prefers {@link #validate} to surface all
     * issues, including warnings, to the user).
     */
    public static void validateOrThrow(FlowGraph g) {
        Result r = validate(g);
        if (!r.ok()) {
            StringBuilder sb = new StringBuilder("Invalid flow '").append(g.name()).append("':");
            for (Issue e : r.errors()) sb.append("\n  - [").append(e.code()).append("] ").append(e.message());
            throw new IllegalArgumentException(sb.toString());
        }
    }

    /** Collect node ids, flagging duplicates; returns the set of (distinct) ids present. */
    private static Set<String> checkNodeIdentity(FlowGraph g, List<Issue> issues) {
        Set<String> ids = new LinkedHashSet<>();
        for (FlowNode n : g.nodes()) {
            if (!ids.add(n.id())) {
                issues.add(new Issue(Severity.ERROR, DUPLICATE_NODE, "Duplicate node id '" + n.id() + "'."));
            }
        }
        return ids;
    }

    /** Every edge endpoint must resolve to a local node — except an {@code on_commit} target (cross-flow). */
    private static void checkEdgeEndpoints(FlowGraph g, Set<String> ids, List<Issue> issues) {
        for (FlowEdge e : g.edges()) {
            if (!ids.contains(e.from())) {
                issues.add(new Issue(Severity.ERROR, DANGLING_FROM,
                        "Edge '" + e.rel() + "' from unknown node '" + e.from() + "'."));
            }
            boolean onCommit = FlowRel.ON_COMMIT.equals(e.rel());
            if (onCommit) {
                // on_commit is cross-flow only: a local target is a hidden cycle (R5).
                if (ids.contains(e.to())) {
                    issues.add(new Issue(Severity.ERROR, ON_COMMIT_SAME_GRAPH,
                            "on_commit edge from '" + e.from() + "' targets node '" + e.to()
                                    + "' in the same flow — on_commit is cross-flow only; link a downstream flow instead."));
                }
            } else if (!ids.contains(e.to())) {
                issues.add(new Issue(Severity.ERROR, DANGLING_TO,
                        "Edge '" + e.rel() + "' from '" + e.from() + "' to unknown node '" + e.to() + "'."));
            }
        }
    }

    /** DFS over the {@code data}-edge subgraph; report the first back-edge as a cycle (§B7). */
    private static void detectDataCycle(FlowGraph g, Set<String> ids, List<Issue> issues) {
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (String id : ids) adj.put(id, new ArrayList<>());
        for (FlowEdge e : g.edges()) {
            if (e.isData() && adj.containsKey(e.from()) && adj.containsKey(e.to())) {
                adj.get(e.from()).add(e.to());
            }
        }
        Set<String> visiting = new HashSet<>();   // on the current DFS stack (gray)
        Set<String> done = new HashSet<>();        // fully explored (black)
        Deque<String> stack = new ArrayDeque<>();
        for (String start : adj.keySet()) {
            if (!done.contains(start) && dfsCycle(start, adj, visiting, done, stack, issues)) {
                return; // first cycle is enough to reject; the user fixes and re-validates
            }
        }
    }

    private static boolean dfsCycle(String u, Map<String, List<String>> adj, Set<String> visiting,
                                    Set<String> done, Deque<String> stack, List<Issue> issues) {
        visiting.add(u);
        stack.addLast(u);
        for (String v : adj.get(u)) {
            if (visiting.contains(v)) {
                List<String> path = new ArrayList<>(stack);
                path.subList(0, path.indexOf(v)).clear();   // trim to the cycle start
                path.add(v);                                  // close the loop
                issues.add(new Issue(Severity.ERROR, CYCLE,
                        "Data-edge cycle (flows must be a DAG — §B7): " + String.join(" -> ", path)));
                return true;
            }
            if (!done.contains(v) && dfsCycle(v, adj, visiting, done, stack, issues)) return true;
        }
        visiting.remove(u);
        stack.removeLast();
        done.add(u);
        return false;
    }
}
