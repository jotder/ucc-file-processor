package com.gamma.ops;

import com.gamma.ops.workflow.Workflow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The Workflow Engine: the built-in ALERT lifecycle, case-insensitive matching, and {@code .toon} authoring. */
class WorkflowTest {

    @Test
    void defaultAlertLifecycle() {
        Workflow wf = Workflow.defaultFor(ObjectType.ALERT);
        assertEquals("OPEN", wf.initialState());
        assertEquals("ACKNOWLEDGED", wf.apply("OPEN", "ack").orElseThrow());
        assertEquals("RESOLVED", wf.apply("ACKNOWLEDGED", "resolve").orElseThrow());
        assertEquals("RESOLVED", wf.apply("OPEN", "resolve").orElseThrow(), "resolve without ack allowed");
        assertTrue(wf.apply("RESOLVED", "ack").isEmpty(), "terminal state has no outgoing transitions");
        assertTrue(wf.apply("OPEN", "bogus").isEmpty());
        assertTrue(wf.isTerminal("RESOLVED"));
        assertFalse(wf.isTerminal("OPEN"));
    }

    @Test
    void matchingIsCaseInsensitive() {
        Workflow wf = Workflow.defaultFor(ObjectType.ALERT);
        assertEquals("ACKNOWLEDGED", wf.apply("open", "ACK").orElseThrow());
        assertTrue(wf.allows("open", "acknowledged"));
        assertFalse(wf.allows("ACKNOWLEDGED", "OPEN"), "no backward transition");
    }

    @Test
    void fromMapParsesAndValidates() {
        Workflow wf = Workflow.fromMap(Map.of(
                "object_type", "ISSUE",
                "initial", "OPEN",
                "terminal", List.of("CLOSED"),
                "transitions", List.of(
                        Map.of("from", "OPEN", "to", "ASSIGNED", "action", "assign"),
                        Map.of("from", "ASSIGNED", "to", "CLOSED", "action", "close"))));
        assertEquals(ObjectType.ISSUE, wf.objectType());
        assertEquals("ASSIGNED", wf.apply("OPEN", "assign").orElseThrow());
        assertTrue(wf.isTerminal("CLOSED"));
        assertThrows(IllegalArgumentException.class, () -> Workflow.fromMap(Map.of("initial", "OPEN")),
                "object_type is required");
    }

    @Test
    void loadFromToonFile(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("issue_workflow.toon");
        Files.writeString(p, """
                workflow:
                  object_type: ISSUE
                  initial: OPEN
                  terminal[1]: "CLOSED"
                  transitions[2]{from,to,action}:
                    OPEN,ASSIGNED,assign
                    ASSIGNED,CLOSED,close
                """);
        Workflow wf = Workflow.load(p);
        assertEquals(ObjectType.ISSUE, wf.objectType());
        assertEquals("OPEN", wf.initialState());
        assertEquals("ASSIGNED", wf.apply("OPEN", "assign").orElseThrow());
        assertEquals("CLOSED", wf.apply("ASSIGNED", "close").orElseThrow());
        assertTrue(wf.isTerminal("CLOSED"));
        assertEquals(2, wf.transitions().size());
    }
}
