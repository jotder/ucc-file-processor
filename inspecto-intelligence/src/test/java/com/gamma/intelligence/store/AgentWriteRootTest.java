package com.gamma.intelligence.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Unit coverage for the {@code -Dassist.write.root} path resolver shared by the agent stores (M7). */
class AgentWriteRootTest {

    private static final String KEY = "assist.write.root";
    private String saved;

    @BeforeEach
    void save() { saved = System.getProperty(KEY); }

    @AfterEach
    void restore() {
        if (saved == null) System.clearProperty(KEY);
        else System.setProperty(KEY, saved);
    }

    @Test
    void nullWhenNoWriteRootConfigured() {
        System.clearProperty(KEY);
        assertNull(AgentWriteRoot.resolve("approvals.jsonl"),
                "no write root ⇒ null (callers treat null as in-memory-only)");
    }

    @Test
    void nullWhenWriteRootBlank() {
        System.setProperty(KEY, "   ");
        assertNull(AgentWriteRoot.resolve("approvals.jsonl"));
    }

    @Test
    void resolvesUnderAgentSubdirWhenConfigured() {
        System.setProperty(KEY, "/tmp/inspecto-space");
        Path p = AgentWriteRoot.resolve("cases.jsonl");
        assertNotNull(p);
        assertEquals(Path.of("/tmp/inspecto-space", "agent", "cases.jsonl"), p);
    }
}
