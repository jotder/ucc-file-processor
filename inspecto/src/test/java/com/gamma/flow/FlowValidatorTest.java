package com.gamma.flow;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.gamma.etl.TestConfigs.csv;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FlowValidator} (T14): the structural gate over the Flow IR — DAG over {@code data} edges,
 * no dangling endpoints, no duplicate ids, no same-graph {@code on_commit}, at least one entry node.
 */
class FlowValidatorTest {

    private static Set<String> codes(FlowValidator.Result r) {
        return r.issues().stream().map(FlowValidator.Issue::code).collect(Collectors.toSet());
    }

    /** acquisition -> parser -> sink, with the parser's unmatched branch to a quarantine sink: a valid flow. */
    private static FlowGraph linearValid() {
        return new FlowGraph("good", true,
                List.of(FlowNode.of("acq", "acquisition"),
                        FlowNode.of("p", "parser"),
                        FlowNode.of("sink", "sink.persistent"),
                        FlowNode.of("dead", "sink.persistent")),
                List.of(FlowEdge.data("acq", "p"),
                        FlowEdge.data("p", "sink"),
                        new FlowEdge("p", FlowRel.UNMATCHED, "dead")));   // parser emits unmatched (§contract)
    }

    @Test
    void validLinearGraphHasNoErrors() {
        FlowValidator.Result r = FlowValidator.validate(linearValid());
        assertTrue(r.ok(), () -> "expected ok, got " + r.issues());
        assertTrue(r.errors().isEmpty());
        assertDoesNotThrow(() -> FlowValidator.validateOrThrow(linearValid()));
    }

    @Test
    void detectsDataCycle() {
        FlowGraph g = new FlowGraph("loop", true,
                List.of(FlowNode.of("a", "transform.map"), FlowNode.of("b", "transform.map")),
                List.of(FlowEdge.data("a", "b"), FlowEdge.data("b", "a")));
        FlowValidator.Result r = FlowValidator.validate(g);
        assertFalse(r.ok());
        assertTrue(codes(r).contains(FlowValidator.CYCLE));
        // the message names the offending nodes
        assertTrue(r.errors().stream().anyMatch(i -> i.message().contains("a") && i.message().contains("b")));
    }

    @Test
    void controlEdgesAreExcludedFromTheCycleCheck() {
        // a failure-> b, b failure-> a : a control cycle, NOT a data cycle — must not be flagged CYCLE
        // (acquisition emits failure, per the node-output contract)
        FlowGraph g = new FlowGraph("ctrl", true,
                List.of(FlowNode.of("a", "acquisition"), FlowNode.of("b", "acquisition")),
                List.of(new FlowEdge("a", FlowRel.FAILURE, "b"), new FlowEdge("b", FlowRel.FAILURE, "a")));
        FlowValidator.Result r = FlowValidator.validate(g);
        assertFalse(codes(r).contains(FlowValidator.CYCLE));
        // ...but with no inbound-free node, it has no trigger
        assertTrue(codes(r).contains(FlowValidator.NO_ENTRY));
    }

    @Test
    void rejectsSameGraphOnCommitButAllowsCrossFlowTarget() {
        // on_commit to a LOCAL node -> rejected (cross-flow only)
        FlowGraph local = new FlowGraph("f", true,
                List.of(FlowNode.of("acq", "acquisition"), FlowNode.of("sink", "sink.persistent")),
                List.of(FlowEdge.data("acq", "sink"), new FlowEdge("sink", FlowRel.ON_COMMIT, "acq")));
        assertTrue(codes(FlowValidator.validate(local)).contains(FlowValidator.ON_COMMIT_SAME_GRAPH));

        // on_commit to ANOTHER flow (not a local node) -> fine, and not a dangling-to error
        FlowGraph cross = new FlowGraph("f", true,
                List.of(FlowNode.of("acq", "acquisition"), FlowNode.of("sink", "sink.persistent")),
                List.of(FlowEdge.data("acq", "sink"), new FlowEdge("sink", FlowRel.ON_COMMIT, "downstream_flow")));
        FlowValidator.Result r = FlowValidator.validate(cross);
        assertTrue(r.ok(), () -> "cross-flow on_commit should be valid, got " + r.issues());
    }

    @Test
    void rejectsDanglingEndpoints() {
        FlowGraph g = new FlowGraph("dangle", true,
                List.of(FlowNode.of("a", "acquisition")),
                List.of(FlowEdge.data("a", "ghost"), FlowEdge.data("nobody", "a")));
        FlowValidator.Result r = FlowValidator.validate(g);
        assertTrue(codes(r).containsAll(Set.of(FlowValidator.DANGLING_TO, FlowValidator.DANGLING_FROM)));
        assertFalse(r.ok());
    }

    @Test
    void flagsDuplicateNodeId() {
        FlowGraph g = new FlowGraph("dup", true,
                List.of(FlowNode.of("a", "acquisition"), FlowNode.of("a", "parser"), FlowNode.of("b", "sink.persistent")),
                List.of(FlowEdge.data("a", "b")));
        assertTrue(codes(FlowValidator.validate(g)).contains(FlowValidator.DUPLICATE_NODE));
    }

    @Test
    void emptyGraphIsAWarningNotAnError() {
        FlowValidator.Result r = FlowValidator.validate(new FlowGraph("empty", false, List.of(), List.of()));
        assertTrue(r.ok());                                          // a warning does not block
        assertTrue(codes(r).contains(FlowValidator.EMPTY_GRAPH));
        assertEquals(1, r.warnings().size());
    }

    @Test
    void validateOrThrowReportsEveryError() {
        FlowGraph g = new FlowGraph("bad", true,
                List.of(FlowNode.of("a", "transform.map"), FlowNode.of("b", "transform.map")),
                List.of(FlowEdge.data("a", "b"), FlowEdge.data("b", "a")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> FlowValidator.validateOrThrow(g));
        assertTrue(ex.getMessage().contains("CYCLE"));
    }

    // ── T9: node-output contract (emit/accept wiring) ─────────────────────────────

    @Test
    void rejectsRelationANodeDoesNotEmit() {
        // transform.map emits only data — an 'invalid' branch is illegal (only validate emits invalid)
        FlowGraph g = new FlowGraph("emit", true,
                List.of(FlowNode.of("acq", "acquisition"), FlowNode.of("m", "transform.map"),
                        FlowNode.of("x", "sink.persistent")),
                List.of(FlowEdge.data("acq", "m"), new FlowEdge("m", FlowRel.INVALID, "x")));
        FlowValidator.Result r = FlowValidator.validate(g);
        assertTrue(codes(r).contains(FlowValidator.ILLEGAL_EMIT));
        assertFalse(r.ok());
    }

    @Test
    void routeBranchesAreLegalOnlyFromARouter() {
        // a router emits route:* — fine
        FlowGraph ok = new FlowGraph("router", true,
                List.of(FlowNode.of("acq", "acquisition"), FlowNode.of("r", "transform.route"),
                        FlowNode.of("a", "sink.persistent")),
                List.of(FlowEdge.data("acq", "r"), new FlowEdge("r", FlowRel.route("emea"), "a")));
        assertTrue(FlowValidator.validate(ok).ok(), () -> "" + FlowValidator.validate(ok).issues());

        // a plain map does NOT emit named routes
        FlowGraph bad = new FlowGraph("router", true,
                List.of(FlowNode.of("acq", "acquisition"), FlowNode.of("m", "transform.map"),
                        FlowNode.of("a", "sink.persistent")),
                List.of(FlowEdge.data("acq", "m"), new FlowEdge("m", FlowRel.route("emea"), "a")));
        assertTrue(codes(FlowValidator.validate(bad)).contains(FlowValidator.ILLEGAL_EMIT));
    }

    @Test
    void rejectsDataEdgeIntoNodeThatAcceptsNoData() {
        // 'gap' accepts only gap, not data — a data edge into it is illegal
        FlowGraph g = new FlowGraph("accept", true,
                List.of(FlowNode.of("acq", "acquisition"), FlowNode.of("g", "gap")),
                List.of(FlowEdge.data("acq", "g")));
        FlowValidator.Result r = FlowValidator.validate(g);
        assertTrue(codes(r).contains(FlowValidator.ILLEGAL_ACCEPT));
        // ...but the parser's unmatched control edge into a sink is fine (handler governed by emitter)
        assertTrue(FlowValidator.validate(linearValid()).ok());
    }

    @Test
    void unregisteredTypeIsAWarningAndItsWiringIsNotChecked() {
        FlowGraph g = new FlowGraph("plugin", true,
                List.of(FlowNode.of("acq", "acquisition"), FlowNode.of("p", "transform.bespoke-plugin")),
                List.of(new FlowEdge("acq", FlowRel.DATA, "p")));
        FlowValidator.Result r = FlowValidator.validate(g);
        assertTrue(codes(r).contains(FlowValidator.UNKNOWN_TYPE));
        assertTrue(r.ok());   // a warning does not block; wiring around the unknown type is skipped
    }

    @Test
    void aRealLiftedPipelineValidatesClean(@TempDir Path dir) throws Exception {
        // the ultimate gate: every edge the legacy lift produces honours the node-output contract
        PipelineConfig cfg = csv(dir, PipelineConfigBatchTest.miniSchema()).load();
        FlowValidator.Result r = FlowValidator.validate(PipelineLift.lift(cfg));
        assertTrue(r.ok(), () -> "lifted pipeline should validate clean, got " + r.errors());
    }
}
