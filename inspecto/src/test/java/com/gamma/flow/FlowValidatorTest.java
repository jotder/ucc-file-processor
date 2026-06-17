package com.gamma.flow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FlowValidator} (T14): the structural gate over the Flow IR — DAG over {@code data} edges,
 * no dangling endpoints, no duplicate ids, no same-graph {@code on_commit}, at least one entry node.
 */
class FlowValidatorTest {

    private static Set<String> codes(FlowValidator.Result r) {
        return r.issues().stream().map(FlowValidator.Issue::code).collect(Collectors.toSet());
    }

    /** acquisition -> parser -> sink, with a control failure edge to a quarantine sink: a valid flow. */
    private static FlowGraph linearValid() {
        return new FlowGraph("good", true,
                List.of(FlowNode.of("acq", "acquisition"),
                        FlowNode.of("p", "parser"),
                        FlowNode.of("sink", "sink.persistent"),
                        FlowNode.of("dead", "sink.persistent")),
                List.of(FlowEdge.data("acq", "p"),
                        FlowEdge.data("p", "sink"),
                        new FlowEdge("p", FlowRel.FAILURE, "dead")));
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
        FlowGraph g = new FlowGraph("ctrl", true,
                List.of(FlowNode.of("a", "parser"), FlowNode.of("b", "parser")),
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
}
