package com.gamma.ops;

import com.gamma.event.Event;
import com.gamma.event.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Promotes selected append-only domain {@link Event}s into <em>managed</em> {@link OperationalObject}s — the
 * bridge from the Phase-1 Event engine to the Phase-2 Object engine (Data Acquisition roadmap Phase D2).
 *
 * <p>Registered as an {@code EventLog} subscriber by the service tier (where the {@link ObjectService} lives),
 * deliberately <b>not</b> in the lean engine core that emits the event: the core records the fact, this decides
 * whether the fact becomes a tracked, assignable object. Today it promotes
 * {@link EventType#SEQUENCE_GAP} (a missing file in a configured sequence) to an
 * {@link ObjectType#ALERT} object — so a gap shows up in the existing Cases/Issues UI with no new UI work.
 *
 * <h3>Why a bridge and not {@code AlertService}</h3>
 * {@code AlertService} evaluates operator rules over the <em>batches ledger</em> (a fired rule → ALERT object).
 * A sequence gap is not a batch metric and is independent of whether any {@code *_alert.toon} rule is loaded, so
 * it gets its own promotion path here, gated only on the object store being present.
 *
 * <h3>De-duplication</h3>
 * Mirrors {@code AlertService}'s active-object guard: a still-active (non-terminal) ALERT for the same
 * {@code (pipeline, expected key)} suppresses a duplicate, so an operator working one missing hour isn't handed
 * a clone when the gap is re-reported after a process restart. (Within a process, {@code GapTracker} already
 * fires each gap once.) Never throws — it sits behind {@code EventLog.emit}.
 */
public final class EventObjectBridge {

    private static final Logger log = LoggerFactory.getLogger(EventObjectBridge.class);

    /** The {@code attributes.rule} tag stamped on a gap-promoted ALERT (its kind, for the active-guard match). */
    public static final String GAP_RULE = "sequence_gap";

    private final ObjectService objects;

    public EventObjectBridge(ObjectService objects) {
        this.objects = objects;
    }

    /** {@code EventLog} subscriber entry point — promote the event if it is one we manage. Never throws. */
    public void onEvent(Event e) {
        if (e == null || !EventType.SEQUENCE_GAP.equals(e.type())) return;   // cheap filter on the hot emit path
        try {
            promoteGap(e);
        } catch (RuntimeException ex) {
            log.warn("could not promote SEQUENCE_GAP to an ALERT object: {}", ex.getMessage());
        }
    }

    private void promoteGap(Event e) {
        String expected = e.attributes().get("expected");
        if (expected == null || expected.isBlank()) return;
        String pipeline = e.pipeline();

        // Suppress if an active gap ALERT for the same expected key already exists (cross-restart guard).
        boolean active = objects.active(ObjectType.ALERT, pipeline).stream()
                .anyMatch(o -> GAP_RULE.equals(o.attributes().get("rule"))
                        && expected.equals(o.attributes().get("expected")));
        if (active) return;

        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("rule", GAP_RULE);
        attrs.put("expected", expected);
        putIfPresent(attrs, "sequence", e.attributes().get("sequence"));
        putIfPresent(attrs, "unit", e.attributes().get("unit"));
        if (e.eventId() != null) attrs.put("causedByEvent", e.eventId());

        String title = "Missing file in sequence: " + expected + (pipeline != null ? " on " + pipeline : "");
        objects.open(ObjectType.ALERT, title, e.message(), "high", pipeline, attrs);
    }

    private static void putIfPresent(Map<String, String> m, String key, String value) {
        if (value != null && !value.isBlank()) m.put(key, value);
    }
}
