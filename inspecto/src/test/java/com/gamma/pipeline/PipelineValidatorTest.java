package com.gamma.pipeline;

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
 * {@link PipelineValidator} (T14): the structural gate over the Flow IR — DAG over {@code data} edges,
 * no dangling endpoints, no duplicate ids, no same-graph {@code on_commit}, at least one entry node.
 */
class PipelineValidatorTest {

    private static Set<String> codes(PipelineValidator.Result r) {
        return r.issues().stream().map(PipelineValidator.Issue::code).collect(Collectors.toSet());
    }

    /** acquisition -> parser -> sink, with the parser's unmatched branch to a quarantine sink: a valid flow. */
    private static PipelineGraph linearValid() {
        return new PipelineGraph("good", true,
                List.of(PipelineNode.of("acq", "acquisition"),
                        PipelineNode.of("p", "parser"),
                        PipelineNode.of("sink", "sink.persistent"),
                        PipelineNode.of("dead", "sink.persistent")),
                List.of(PipelineEdge.data("acq", "p"),
                        PipelineEdge.data("p", "sink"),
                        new PipelineEdge("p", PipelineRel.UNMATCHED, "dead")));   // parser emits unmatched (§contract)
    }

    @Test
    void validLinearGraphHasNoErrors() {
        PipelineValidator.Result r = PipelineValidator.validate(linearValid());
        assertTrue(r.ok(), () -> "expected ok, got " + r.issues());
        assertTrue(r.errors().isEmpty());
        assertDoesNotThrow(() -> PipelineValidator.validateOrThrow(linearValid()));
    }

    @Test
    void detectsDataCycle() {
        PipelineGraph g = new PipelineGraph("loop", true,
                List.of(PipelineNode.of("a", "transform.map"), PipelineNode.of("b", "transform.map")),
                List.of(PipelineEdge.data("a", "b"), PipelineEdge.data("b", "a")));
        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertFalse(r.ok());
        assertTrue(codes(r).contains(PipelineValidator.CYCLE));
        // the message names the offending nodes
        assertTrue(r.errors().stream().anyMatch(i -> i.message().contains("a") && i.message().contains("b")));
    }

    @Test
    void controlEdgesAreExcludedFromTheCycleCheck() {
        // a failure-> b, b failure-> a : a control cycle, NOT a data cycle — must not be flagged CYCLE
        // (acquisition emits failure, per the node-output contract)
        PipelineGraph g = new PipelineGraph("ctrl", true,
                List.of(PipelineNode.of("a", "acquisition"), PipelineNode.of("b", "acquisition")),
                List.of(new PipelineEdge("a", PipelineRel.FAILURE, "b"), new PipelineEdge("b", PipelineRel.FAILURE, "a")));
        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertFalse(codes(r).contains(PipelineValidator.CYCLE));
        // ...but with no inbound-free node, it has no trigger
        assertTrue(codes(r).contains(PipelineValidator.NO_ENTRY));
    }

    @Test
    void rejectsSameGraphOnCommitButAllowsCrossFlowTarget() {
        // on_commit to a LOCAL node -> rejected (cross-flow only)
        PipelineGraph local = new PipelineGraph("f", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("sink", "sink.persistent")),
                List.of(PipelineEdge.data("acq", "sink"), new PipelineEdge("sink", PipelineRel.ON_COMMIT, "acq")));
        assertTrue(codes(PipelineValidator.validate(local)).contains(PipelineValidator.ON_COMMIT_SAME_GRAPH));

        // on_commit to ANOTHER flow (not a local node) -> fine, and not a dangling-to error
        PipelineGraph cross = new PipelineGraph("f", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("sink", "sink.persistent")),
                List.of(PipelineEdge.data("acq", "sink"), new PipelineEdge("sink", PipelineRel.ON_COMMIT, "downstream_flow")));
        PipelineValidator.Result r = PipelineValidator.validate(cross);
        assertTrue(r.ok(), () -> "cross-flow on_commit should be valid, got " + r.issues());
    }

    @Test
    void rejectsDanglingEndpoints() {
        PipelineGraph g = new PipelineGraph("dangle", true,
                List.of(PipelineNode.of("a", "acquisition")),
                List.of(PipelineEdge.data("a", "ghost"), PipelineEdge.data("nobody", "a")));
        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertTrue(codes(r).containsAll(Set.of(PipelineValidator.DANGLING_TO, PipelineValidator.DANGLING_FROM)));
        assertFalse(r.ok());
    }

    @Test
    void flagsDuplicateNodeId() {
        PipelineGraph g = new PipelineGraph("dup", true,
                List.of(PipelineNode.of("a", "acquisition"), PipelineNode.of("a", "parser"), PipelineNode.of("b", "sink.persistent")),
                List.of(PipelineEdge.data("a", "b")));
        assertTrue(codes(PipelineValidator.validate(g)).contains(PipelineValidator.DUPLICATE_NODE));
    }

    @Test
    void emptyGraphIsAWarningNotAnError() {
        PipelineValidator.Result r = PipelineValidator.validate(new PipelineGraph("empty", false, List.of(), List.of()));
        assertTrue(r.ok());                                          // a warning does not block
        assertTrue(codes(r).contains(PipelineValidator.EMPTY_GRAPH));
        assertEquals(1, r.warnings().size());
    }

    @Test
    void validateOrThrowReportsEveryError() {
        PipelineGraph g = new PipelineGraph("bad", true,
                List.of(PipelineNode.of("a", "transform.map"), PipelineNode.of("b", "transform.map")),
                List.of(PipelineEdge.data("a", "b"), PipelineEdge.data("b", "a")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PipelineValidator.validateOrThrow(g));
        assertTrue(ex.getMessage().contains("CYCLE"));
    }

    // ── T9: node-output contract (emit/accept wiring) ─────────────────────────────

    @Test
    void rejectsRelationANodeDoesNotEmit() {
        // transform.map emits only data — an 'invalid' branch is illegal (only validate emits invalid)
        PipelineGraph g = new PipelineGraph("emit", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("m", "transform.map"),
                        PipelineNode.of("x", "sink.persistent")),
                List.of(PipelineEdge.data("acq", "m"), new PipelineEdge("m", PipelineRel.INVALID, "x")));
        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertTrue(codes(r).contains(PipelineValidator.ILLEGAL_EMIT));
        assertFalse(r.ok());
    }

    @Test
    void routeBranchesAreLegalOnlyFromARouter() {
        // a router emits route:* — fine
        PipelineGraph ok = new PipelineGraph("router", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("r", "transform.route"),
                        PipelineNode.of("a", "sink.persistent")),
                List.of(PipelineEdge.data("acq", "r"), new PipelineEdge("r", PipelineRel.route("emea"), "a")));
        assertTrue(PipelineValidator.validate(ok).ok(), () -> "" + PipelineValidator.validate(ok).issues());

        // a plain map does NOT emit named routes
        PipelineGraph bad = new PipelineGraph("router", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("m", "transform.map"),
                        PipelineNode.of("a", "sink.persistent")),
                List.of(PipelineEdge.data("acq", "m"), new PipelineEdge("m", PipelineRel.route("emea"), "a")));
        assertTrue(codes(PipelineValidator.validate(bad)).contains(PipelineValidator.ILLEGAL_EMIT));
    }

    @Test
    void rejectsDataEdgeIntoNodeThatAcceptsNoData() {
        // 'gap' accepts only gap, not data — a data edge into it is illegal
        PipelineGraph g = new PipelineGraph("accept", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("g", "gap")),
                List.of(PipelineEdge.data("acq", "g")));
        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertTrue(codes(r).contains(PipelineValidator.ILLEGAL_ACCEPT));
        // ...but the parser's unmatched control edge into a sink is fine (handler governed by emitter)
        assertTrue(PipelineValidator.validate(linearValid()).ok());
    }

    @Test
    void unregisteredTypeIsAWarningAndItsWiringIsNotChecked() {
        PipelineGraph g = new PipelineGraph("plugin", true,
                List.of(PipelineNode.of("acq", "acquisition"), PipelineNode.of("p", "transform.bespoke-plugin")),
                List.of(new PipelineEdge("acq", PipelineRel.DATA, "p")));
        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertTrue(codes(r).contains(PipelineValidator.UNKNOWN_TYPE));
        assertTrue(r.ok());   // a warning does not block; wiring around the unknown type is skipped
    }

    @Test
    void aRealLiftedPipelineValidatesClean(@TempDir Path dir) throws Exception {
        // the ultimate gate: every edge the legacy lift produces honours the node-output contract
        PipelineConfig cfg = csv(dir, PipelineConfigBatchTest.miniSchema()).load();
        PipelineValidator.Result r = PipelineValidator.validate(PipelineLift.lift(cfg));
        assertTrue(r.ok(), () -> "lifted pipeline should validate clean, got " + r.errors());
    }
}
