package com.gamma.intelligence.policy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Holds the single {@link AutonomyPolicy} document (AGT-5 P4). Unlike the append-only
 * {@code ApprovalStore}, this is one small document rewritten on every edit.
 *
 * <p>With a {@code file}, the policy is durable (a single-object JSON at
 * {@code <assist.write.root>/agent/policy.json}), loaded at construction and rewritten on every
 * {@link #update}, so an operator's autonomy configuration — including an engaged kill switch —
 * survives a restart. Without a file it is in-memory only (dev/tests). All persistence failures
 * degrade to in-memory (log + continue); the current policy is always readable.
 */
public final class AutonomyPolicyStore {

    private static final Logger log = LoggerFactory.getLogger(AutonomyPolicyStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file; // null → in-memory only
    private volatile AutonomyPolicy policy = AutonomyPolicy.defaults();

    public AutonomyPolicyStore() { this(null); }

    public AutonomyPolicyStore(Path file) {
        this.file = file;
        load();
    }

    public synchronized AutonomyPolicy current() {
        return policy;
    }

    /** Replace the policy and persist. Returns the stored value. */
    public synchronized AutonomyPolicy update(AutonomyPolicy next) {
        this.policy = next == null ? AutonomyPolicy.defaults() : next;
        persist();
        return this.policy;
    }

    private void load() {
        if (file == null || !Files.exists(file)) return;
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.isBlank()) return;
            Map<String, Object> record = JSON.readValue(content, new TypeReference<Map<String, Object>>() {});
            this.policy = AutonomyPolicy.fromRecord(record);
            log.info("Loaded autonomy policy from {} (killSwitch={}, {} class(es))",
                    file, policy.killSwitch(), policy.classes().size());
        } catch (IOException | RuntimeException e) {
            log.warn("Could not load autonomy policy from {}: {} — using defaults", file, e.getMessage());
            this.policy = AutonomyPolicy.defaults();
        }
    }

    private void persist() {
        if (file == null) return;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(policy.toRecord()),
                    StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not persist autonomy policy to {}: {}", file, e.getMessage());
        }
    }
}
