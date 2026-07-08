package com.gamma.notify;

import com.gamma.event.Event;
import com.gamma.event.EventType;

import java.util.List;
import java.util.Optional;

/**
 * The active set of {@link NotificationRule}s. Ships with built-in defaults that turn the operational
 * failure signals the engine already emits into user-facing alerts for the single {@code appUser}
 * operator — the categories that matter in this product today. (The requirement's Collaboration and
 * Security categories are future: their triggers need the unbuilt collaboration / security modules.)
 *
 * <p>A TOON {@code notifications} config section that overrides these is a planned follow-on; this class
 * is the seam it will populate. {@link #forEvent(Event)} returns the first matching rule.
 *
 * @since 4.4.0
 */
public final class NotificationRules {

    private final List<NotificationRule> rules;

    public NotificationRules(List<NotificationRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** The built-in defaults: operational failures the operator should see. */
    public static NotificationRules defaults() {
        return new NotificationRules(List.of(
                new NotificationRule(EventType.BATCH_FAILED, null, "pipeline",
                        "Pipeline {{pipeline}} failed",
                        "Batch {{correlationId}} failed: {{message}}",
                        "batch-failed:{{pipeline}}"),
                new NotificationRule(EventType.FILE_QUARANTINED, null, "pipeline",
                        "File quarantined in {{pipeline}}",
                        "{{message}}",
                        "quarantine:{{pipeline}}:{{attributes.file}}"),
                new NotificationRule(EventType.SEQUENCE_GAP, null, "pipeline",
                        "Sequence gap in {{pipeline}}",
                        "Missing {{attributes.expected}} in {{attributes.sequence}}",
                        "gap:{{pipeline}}:{{attributes.expected}}"),
                new NotificationRule(EventType.ALERT_FIRED, null, "ops",
                        "Alert: {{attributes.rule}}",
                        "{{message}}",
                        "alert:{{pipeline}}:{{attributes.rule}}"),
                new NotificationRule(EventType.EXPECTATION_FAILED, null, "ops",
                        "Expectation failed: {{attributes.expectation}}",
                        "{{message}}",
                        "expectation:{{attributes.expectation}}"),
                new NotificationRule(EventType.JOB_FAILED, null, "job",
                        "Job failed",
                        "{{message}}",
                        "job-failed:{{correlationId}}"),
                new NotificationRule(EventType.REPORT_READY, null, "job",
                        "Report ready: {{attributes.job}}",
                        "{{message}}",
                        "report:{{attributes.job}}:{{attributes.path}}"),
                new NotificationRule(EventType.OBJECT_SLA_BREACH, null, "ops",
                        "SLA breach",
                        "{{message}}",
                        "sla:{{correlationId}}")));
    }

    /** The first rule that matches {@code e}, if any. */
    public Optional<NotificationRule> forEvent(Event e) {
        for (NotificationRule r : rules) if (r.matches(e)) return Optional.of(r);
        return Optional.empty();
    }

    /** The configured rules (diagnostics/tests). */
    public List<NotificationRule> rules() {
        return rules;
    }
}
