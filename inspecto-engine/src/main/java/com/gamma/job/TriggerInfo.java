package com.gamma.job;

/**
 * How a Run was started: a {@code kind} ({@code schedule} | {@code event} | {@code manual} |
 * {@code catch-up}) and its {@code detail} (the actor for {@code manual:<actor>}, the upstream
 * pipeline for {@code event:<pipeline>}, else blank). Parsed from the recorded trigger string so a
 * Job can branch on how it fired without re-parsing.
 */
public record TriggerInfo(String kind, String detail) {

    /** Parse a recorded trigger string ({@code "manual:alice"}, {@code "event:X"}, {@code "schedule"}). */
    public static TriggerInfo parse(String trigger) {
        if (trigger == null || trigger.isBlank()) return new TriggerInfo("", "");
        int i = trigger.indexOf(':');
        return i < 0 ? new TriggerInfo(trigger, "")
                     : new TriggerInfo(trigger.substring(0, i), trigger.substring(i + 1));
    }
}
