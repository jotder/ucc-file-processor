package com.gamma.intelligence.action;

import com.eoiagent.core.ApprovalDecision;
import com.eoiagent.core.ApprovalRequest;
import com.eoiagent.core.ToolCall;
import com.eoiagent.safety.ApprovalHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * Bridges eoiagent's <em>synchronous</em> {@link ApprovalHandler} to Inspecto's <em>asynchronous</em>
 * approvals inbox — AGT-5 P3 (autonomy ladder L2, {@code docs/superpower/embedded-intelligence-plan.md}
 * §6 &amp; §8). eoiagent's {@code DefaultToolRegistry} dispatches every mutating tool through
 * {@code ApprovalHandler.decide(req)} and blocks on it (its {@code CallbackApprovalGate} wraps the
 * call in a bounded timeout, defaulting to 5 minutes, and treats a timeout / thrown handler / null
 * decision as fail-closed). This handler parks that gate thread on a {@link CompletableFuture} while
 * it records a {@link Approval} in the {@link ApprovalStore}; the operator later resolves it through
 * {@code POST /agent/approvals/{id}/decision}, which completes the future and unblocks the gate.
 *
 * <p>Wiring is opt-in: {@link #enabled()} gates both the {@code MUTATING_ACTIONS} feature
 * ({@code InspectoPackConfig}) and whether this handler is supplied to the platform. With the tier
 * off, the mutating belt is hidden, no {@code decide} is ever called, and the inbox stays empty.
 */
public final class AgentApprovals implements ApprovalHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentApprovals.class);

    /** Opt-in kill-switch: the agent's mutating (act) tier is off unless this system property is {@code true}. */
    public static final String ENABLED_FLAG = "intelligence.act.enabled";

    private final ApprovalStore store;
    private final Function<ToolCall, Map<String, Object>> previewer;
    private final Map<String, CompletableFuture<ApprovalDecision>> pending = new ConcurrentHashMap<>();

    /** Default: an empty preview. Slice 2 injects a per-tool previewer (e.g. a component apply diff). */
    public AgentApprovals() {
        this(new ApprovalStore(), call -> Map.of());
    }

    public AgentApprovals(ApprovalStore store, Function<ToolCall, Map<String, Object>> previewer) {
        this.store = store;
        this.previewer = previewer;
    }

    /** Whether the agent's mutating (act) tier is enabled (opt-in) — gates the feature and this handler. */
    public static boolean enabled() {
        return Boolean.getBoolean(ENABLED_FLAG);
    }

    // --- gate side: park the eoiagent dispatch thread until the operator decides -------------------

    @Override
    public ApprovalDecision decide(ApprovalRequest req) {
        ToolCall call = req.call();
        String actor = "agent:" + (call.run() == null ? "unknown" : call.run().value());
        Approval approval = new Approval(UUID.randomUUID().toString(), call.toolName(), actor,
                req.humanSummary(), call.arguments(), safePreview(call), Instant.now());
        CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();
        pending.put(approval.id(), future);
        store.add(approval);
        log.info("Approval requested [{}] for mutating tool '{}' ({})", approval.id(), call.toolName(), actor);
        try {
            return future.get(); // parked until resolve(...); the gate enforces its own timeout around this
        } catch (InterruptedException e) {
            // The gate cancelled us because its timeout elapsed — record the lapse and fail closed.
            Thread.currentThread().interrupt();
            store.transition(approval.id(), Approval.Status.TIMED_OUT, "system", Instant.now());
            return ApprovalDecision.TIMED_OUT;
        } catch (ExecutionException e) {
            return ApprovalDecision.DENIED; // future never completed exceptionally today; fail closed if it ever does
        } finally {
            pending.remove(approval.id());
        }
    }

    private Map<String, Object> safePreview(ToolCall call) {
        try {
            Map<String, Object> p = previewer.apply(call);
            return p == null ? Map.of() : p;
        } catch (RuntimeException e) {
            // A failed preview must never block the request — the operator still decides, without one.
            log.warn("Dry-run preview failed for '{}': {}", call.toolName(), e.getMessage());
            return Map.of("previewError", String.valueOf(e.getMessage()));
        }
    }

    // --- inbox side: SPI-facing reads + the operator's decision -----------------------------------

    /** Recent approvals (pending and decided), newest-first, capped at {@code limit}. */
    public List<Map<String, Object>> recent(int limit) {
        return store.recent(limit).stream().map(Approval::toView).toList();
    }

    public Optional<Map<String, Object>> byId(String id) {
        return store.byId(id).map(Approval::toView);
    }

    /**
     * Record the operator's decision and unblock the parked gate thread. Returns the updated view, or
     * empty when the id is unknown or already decided (the route maps that to 404). The store's guarded
     * transition is the source of truth, so an operator decision racing a gate timeout resolves once.
     */
    public Optional<Map<String, Object>> resolve(String id, boolean approve, String decidedBy) {
        Approval.Status terminal = approve ? Approval.Status.APPROVED : Approval.Status.DENIED;
        Optional<Approval> updated = store.transition(id, terminal, decidedBy, Instant.now());
        if (updated.isEmpty()) {
            return Optional.empty();
        }
        CompletableFuture<ApprovalDecision> future = pending.get(id);
        if (future != null) {
            future.complete(approve ? ApprovalDecision.APPROVED : ApprovalDecision.DENIED);
        }
        return updated.map(Approval::toView);
    }
}
