package com.gamma.notify;

import com.gamma.event.Event;
import com.gamma.event.EventLevel;
import com.gamma.event.EventType;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The active set of {@link NotificationRule}s. Ships with built-in defaults that turn the operational
 * failure signals the engine already emits into user-facing alerts for the single {@code appUser}
 * operator — the categories that matter in this product today. (The requirement's Collaboration and
 * Security categories are future: their triggers need the unbuilt collaboration / security modules.)
 *
 * <p>An operator can author additional rules at runtime via the {@code /notifications/rules*} admin CRUD
 * (persisted as {@code notification-rule} components, mirrors {@link ChannelConfig}); {@link #forEvent}
 * checks those <b>ahead of</b> the built-ins, so an authored rule for an already-covered event type
 * effectively overrides the built-in's copy/routing without deleting it from this class.
 *
 * @since 4.4.0
 */
public final class NotificationRules {

    private final List<NotificationRule> rules;
    private final Supplier<List<NotificationRule>> custom;

    public NotificationRules(List<NotificationRule> rules) {
        this(rules, List::of);
    }

    /** Full constructor: built-in rules plus a live view of operator-authored rules, resolved at dispatch
     *  time so authoring takes effect without a restart. */
    public NotificationRules(List<NotificationRule> rules, Supplier<List<NotificationRule>> custom) {
        this.rules = List.copyOf(rules);
        this.custom = custom;
    }

    /** The built-in defaults: operational failures the operator should see. */
    public static NotificationRules defaults() {
        return new NotificationRules(List.of(
                new NotificationRule("builtin-batch-failed", EventType.BATCH_FAILED, null, "pipeline",
                        "Pipeline {{pipeline}} failed",
                        "Batch {{correlationId}} failed: {{message}}",
                        "batch-failed:{{pipeline}}", true),
                new NotificationRule("builtin-file-quarantined", EventType.FILE_QUARANTINED, null, "pipeline",
                        "File quarantined in {{pipeline}}",
                        "{{message}}",
                        "quarantine:{{pipeline}}:{{attributes.file}}", true),
                new NotificationRule("builtin-sequence-gap", EventType.SEQUENCE_GAP, null, "pipeline",
                        "Sequence gap in {{pipeline}}",
                        "Missing {{attributes.expected}} in {{attributes.sequence}}",
                        "gap:{{pipeline}}:{{attributes.expected}}", true),
                new NotificationRule("builtin-alert-fired", EventType.ALERT_FIRED, null, "ops",
                        "Alert: {{attributes.rule}}",
                        "{{message}}",
                        "alert:{{pipeline}}:{{attributes.rule}}", true),
                new NotificationRule("builtin-expectation-failed", EventType.EXPECTATION_FAILED, null, "ops",
                        "Expectation failed: {{attributes.expectation}}",
                        "{{message}}",
                        "expectation:{{attributes.expectation}}", true),
                new NotificationRule("builtin-job-failed", EventType.JOB_FAILED, null, "job",
                        "Job failed",
                        "{{message}}",
                        "job-failed:{{correlationId}}", true),
                new NotificationRule("builtin-report-ready", EventType.REPORT_READY, null, "job",
                        "Report ready: {{attributes.job}}",
                        "{{message}}",
                        "report:{{attributes.job}}:{{attributes.path}}", true),
                new NotificationRule("builtin-sla-breach", EventType.OBJECT_SLA_BREACH, null, "ops",
                        "SLA breach",
                        "{{message}}",
                        "sla:{{correlationId}}", true),
                new NotificationRule("builtin-object-escalated", EventType.OBJECT_ESCALATED, null, "ops",
                        "Incident escalated: {{attributes.objectId}}",
                        "{{message}}",
                        "escalated:{{attributes.objectId}}", true),
                // A provenance conservation breach already opens an ALERT object (both kinds); surface it
                // to the operator's feed too. minLevel WARN catches AMPLIFICATION (WARN) as well as LOSS
                // (ERROR) — matching that the ALERT bridge fires for both.
                new NotificationRule("builtin-conservation-imbalance", EventType.FLOW_CONSERVATION_IMBALANCE,
                        EventLevel.WARN, "ops",
                        "Conservation {{attributes.kind}} in {{pipeline}}",
                        "{{attributes.node}}: {{attributes.recordsIn}} in vs {{attributes.recordsOut}} out",
                        "conservation:{{pipeline}}:{{attributes.node}}", true)));
    }

    /** The first rule that matches {@code e}, if any — operator-authored rules are checked first. */
    public Optional<NotificationRule> forEvent(Event e) {
        for (NotificationRule r : custom.get()) if (r.matches(e)) return Optional.of(r);
        for (NotificationRule r : rules) if (r.matches(e)) return Optional.of(r);
        return Optional.empty();
    }

    /** The built-in rules (diagnostics/tests) — excludes any operator-authored overlay. */
    public List<NotificationRule> rules() {
        return rules;
    }
}
