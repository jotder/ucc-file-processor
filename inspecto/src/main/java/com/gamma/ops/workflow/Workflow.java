package com.gamma.ops.workflow;

import com.gamma.config.io.ConfigCodec;
import com.gamma.ops.ObjectType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A config-driven state machine for an {@link ObjectType} — the "Workflow Engine" of the Operational
 * Intelligence Platform (Phase 2). It defines the legal states an {@link com.gamma.ops.OperationalObject}
 * may occupy and the {@link Transition}s between them. {@link com.gamma.ops.ObjectService} consults it
 * to validate every lifecycle change before persisting, and records each transition as a Phase-1 event.
 *
 * <p>Per the requirement's "configuration over custom code" principle a workflow may be authored as a
 * {@code *_workflow.toon} (see {@link #load}); {@link #defaultFor(ObjectType)} supplies a sensible
 * built-in so the engine works with zero configuration (Phase 2 ships the {@link ObjectType#ALERT}
 * lifecycle {@code OPEN → ACKNOWLEDGED → RESOLVED}).
 *
 * <p>States are normalised to upper-case, actions to lower-case, so matching is case-insensitive.
 *
 * @since 4.3.0
 */
@com.gamma.api.PublicApi(since = "4.3.0")
public record Workflow(ObjectType objectType, String initialState, Set<Transition> transitions,
                       Set<String> terminalStates) {

    /** One legal move: {@code from} state, via {@code action}, to {@code to} state. */
    public record Transition(String from, String to, String action) {
        public Transition {
            from = norm(from);
            to = norm(to);
            action = action == null ? null : action.trim().toLowerCase(Locale.ROOT);
            if (from == null || to == null || action == null || action.isBlank())
                throw new IllegalArgumentException("transition needs from, to and action");
        }
    }

    public Workflow {
        if (objectType == null) throw new IllegalArgumentException("workflow objectType is required");
        initialState = norm(initialState);
        if (initialState == null) throw new IllegalArgumentException("workflow initialState is required");
        transitions = transitions == null ? Set.of() : Set.copyOf(transitions);
        Set<String> term = new LinkedHashSet<>();
        if (terminalStates != null) for (String s : terminalStates) if (norm(s) != null) term.add(norm(s));
        terminalStates = Set.copyOf(term);
    }

    /** The target state reached from {@code fromState} via {@code action}, if such a transition exists. */
    public Optional<String> apply(String fromState, String action) {
        String from = norm(fromState);
        String act = action == null ? null : action.trim().toLowerCase(Locale.ROOT);
        return transitions.stream()
                .filter(t -> t.from().equals(from) && t.action().equals(act))
                .map(Transition::to)
                .findFirst();
    }

    /** {@code true} when some transition goes from {@code fromState} directly to {@code toState}. */
    public boolean allows(String fromState, String toState) {
        String from = norm(fromState);
        String to = norm(toState);
        return transitions.stream().anyMatch(t -> t.from().equals(from) && t.to().equals(to));
    }

    /** {@code true} when {@code state} is terminal (no further transitions are intended). */
    public boolean isTerminal(String state) {
        return terminalStates.contains(norm(state));
    }

    /** Every state named by the initial state, the terminal set, or any transition endpoint. */
    public Set<String> states() {
        Set<String> all = new LinkedHashSet<>();
        all.add(initialState);
        all.addAll(terminalStates);
        for (Transition t : transitions) { all.add(t.from()); all.add(t.to()); }
        return all;
    }

    // ── built-in defaults ─────────────────────────────────────────────────────────

    /**
     * The built-in workflow for {@code type}.
     * <ul>
     *   <li>{@link ObjectType#ALERT} (Phase 2): {@code OPEN → ACKNOWLEDGED → RESOLVED} (with a direct
     *       {@code OPEN → RESOLVED} for "resolve without acking"); {@code RESOLVED} is terminal.</li>
     *   <li>{@link ObjectType#INCIDENT} (Phase 3): {@code OPEN → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED}
     *       (actions {@code assign}/{@code start}/{@code resolve}/{@code close}); only {@code CLOSED} is
     *       terminal, so a {@code RESOLVED} incident can still be reopened-then-closed by config if desired,
     *       and the SLA clock (which stops at {@code RESOLVED}) is distinct from closure.</li>
     *   <li>{@link ObjectType#CASE} (Phase 4): {@code OPEN → INVESTIGATING → ESCALATED → RESOLVED → CLOSED}
     *       (actions {@code investigate}/{@code escalate}/{@code resolve}/{@code close}, plus a direct
     *       {@code INVESTIGATING → RESOLVED} for "resolve without escalating"); only {@code CLOSED} is
     *       terminal.</li>
     * </ul>
     * The remaining type ({@link ObjectType#TASK}) gets a minimal {@code OPEN → CLOSED} placeholder that a
     * later phase replaces, or that a {@code *_workflow.toon} overrides today.
     */
    public static Workflow defaultFor(ObjectType type) {
        if (type == ObjectType.ALERT) {
            return new Workflow(ObjectType.ALERT, "OPEN",
                    Set.of(new Transition("OPEN", "ACKNOWLEDGED", "ack"),
                            new Transition("ACKNOWLEDGED", "RESOLVED", "resolve"),
                            new Transition("OPEN", "RESOLVED", "resolve")),
                    Set.of("RESOLVED"));
        }
        if (type == ObjectType.INCIDENT) {
            return new Workflow(ObjectType.INCIDENT, "OPEN",
                    Set.of(new Transition("OPEN", "ASSIGNED", "assign"),
                            new Transition("ASSIGNED", "IN_PROGRESS", "start"),
                            new Transition("IN_PROGRESS", "RESOLVED", "resolve"),
                            new Transition("RESOLVED", "CLOSED", "close")),
                    Set.of("CLOSED"));
        }
        if (type == ObjectType.CASE) {
            return new Workflow(ObjectType.CASE, "OPEN",
                    Set.of(new Transition("OPEN", "INVESTIGATING", "investigate"),
                            new Transition("INVESTIGATING", "ESCALATED", "escalate"),
                            new Transition("ESCALATED", "RESOLVED", "resolve"),
                            new Transition("INVESTIGATING", "RESOLVED", "resolve"),
                            new Transition("RESOLVED", "CLOSED", "close")),
                    Set.of("CLOSED"));
        }
        return new Workflow(type, "OPEN",
                Set.of(new Transition("OPEN", "CLOSED", "close")), Set.of("CLOSED"));
    }

    // ── .toon authoring ─────────────────────────────────────────────────────────────

    /** Load a {@code *_workflow.toon} (a {@code workflow { … }} block). */
    @SuppressWarnings("unchecked")
    public static Workflow load(Path path) throws IOException {
        Map<String, Object> root = ConfigCodec.toMap(Files.readString(path));
        Object wf = root.get("workflow");
        if (!(wf instanceof Map)) throw new IllegalArgumentException(path + " has no 'workflow' block");
        return fromMap((Map<String, Object>) wf);
    }

    /** Parse + validate from a decoded {@code workflow { … }} map. */
    @SuppressWarnings("unchecked")
    public static Workflow fromMap(Map<String, Object> wf) {
        if (wf == null) throw new IllegalArgumentException("missing 'workflow' block");
        ObjectType type = ObjectType.of(str(wf.getOrDefault("object_type", wf.get("objectType"))));
        if (type == null) throw new IllegalArgumentException("workflow.object_type is required");
        String initial = str(wf.getOrDefault("initial", wf.get("initial_state")));

        Set<String> terminal = new LinkedHashSet<>();
        Object term = wf.getOrDefault("terminal", wf.get("terminal_states"));
        if (term instanceof List<?> list) for (Object s : list) if (str(s) != null) terminal.add(str(s));

        Set<Transition> transitions = new LinkedHashSet<>();
        Object trs = wf.get("transitions");
        if (trs instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> tm) {
                    Map<String, Object> t = (Map<String, Object>) tm;
                    transitions.add(new Transition(str(t.get("from")), str(t.get("to")), str(t.get("action"))));
                }
            }
        }
        if (transitions.isEmpty()) throw new IllegalArgumentException("workflow needs at least one transition");
        return new Workflow(type, initial, transitions, terminal);
    }

    private static String norm(String s) {
        return (s == null || s.isBlank()) ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
