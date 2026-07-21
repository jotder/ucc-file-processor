package com.gamma.intelligence.store;

import java.nio.file.Path;

/**
 * Resolves the durable path for an agent-scoped store under {@code -Dassist.write.root} (M7). Extracted
 * from the ~6 identical copies of this idiom that were scattered across {@code InspectoIntelligenceAgent}'s
 * store factories and {@code RunbookRunStore.fromWriteRoot}.
 */
public final class AgentWriteRoot {

    private AgentWriteRoot() {}

    /**
     * {@code <assist.write.root>/agent/<file>}, or {@code null} when no write root is configured. Every
     * agent store treats a {@code null} path as in-memory-only (dev/tests), so callers pass this straight
     * into a store's {@code (Path)} constructor without a branch of their own.
     */
    public static Path resolve(String file) {
        String wr = System.getProperty("assist.write.root");
        if (wr == null || wr.isBlank()) return null;
        return Path.of(wr).resolve("agent").resolve(file);
    }
}
