package com.gamma.agent.skill;

import com.gamma.config.spec.Finding;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct branch coverage for the {@code diagnose-and-alert} shape oracle. {@code AlertRuleValidator}
 * is a pure, deterministic function, so each rule violation is asserted in isolation here rather than
 * inferred through the skill — the skill test proves the loop wiring, this proves the rule.
 */
class AlertRuleValidatorTest {

    private static final Set<String> PIPES = Set.of("EVENTS", "ORDERS");

    /** A well-formed rule; tests mutate one field at a time off this baseline. */
    private static Map<String, Object> valid() {
        Map<String, Object> r = new HashMap<>();
        r.put("name", "high error rate");
        r.put("metric", "error_rate");
        r.put("comparator", "gt");
        r.put("threshold", 0.05);
        r.put("window", "1h");
        r.put("severity", "WARNING");
        return r;
    }

    private static boolean anchored(List<Finding> fs, String fieldPath) {
        return fs.stream().anyMatch(f -> f.fieldPath().equals(fieldPath));
    }

    @Test
    void wellFormedRuleHasNoFindings() {
        assertTrue(AlertRuleValidator.check(valid(), PIPES).isEmpty());
    }

    @Test
    void nullRuleIsASingleFinding() {
        List<Finding> fs = AlertRuleValidator.check(null, PIPES);
        assertEquals(1, fs.size());
        assertTrue(anchored(fs, "alert"));
    }

    @Test
    void missingNameIsFlagged() {
        Map<String, Object> r = valid();
        r.remove("name");
        assertTrue(anchored(AlertRuleValidator.check(r, PIPES), "alert.name"));
    }

    @Test
    void missingAndUnknownMetricAreFlagged() {
        Map<String, Object> miss = valid();
        miss.remove("metric");
        assertTrue(anchored(AlertRuleValidator.check(miss, PIPES), "alert.metric"));

        Map<String, Object> bad = valid();
        bad.put("metric", "cpu_load");
        assertTrue(anchored(AlertRuleValidator.check(bad, PIPES), "alert.metric"));
    }

    @Test
    void missingAndUnknownComparatorAreFlagged() {
        Map<String, Object> miss = valid();
        miss.remove("comparator");
        assertTrue(anchored(AlertRuleValidator.check(miss, PIPES), "alert.comparator"));

        Map<String, Object> bad = valid();
        bad.put("comparator", "equals");
        assertTrue(anchored(AlertRuleValidator.check(bad, PIPES), "alert.comparator"));
    }

    @Test
    void thresholdMustBePresentNumericAndPositive() {
        Map<String, Object> miss = valid();
        miss.remove("threshold");
        assertTrue(anchored(AlertRuleValidator.check(miss, PIPES), "alert.threshold"));

        Map<String, Object> nonNumeric = valid();
        nonNumeric.put("threshold", "soon");
        assertTrue(anchored(AlertRuleValidator.check(nonNumeric, PIPES), "alert.threshold"));

        Map<String, Object> zero = valid();
        zero.put("threshold", 0);
        assertTrue(anchored(AlertRuleValidator.check(zero, PIPES), "alert.threshold"));
    }

    @Test
    void errorRateThresholdAboveOneIsOutOfRange() {
        Map<String, Object> r = valid();          // metric == error_rate
        r.put("threshold", 5);                     // 5 looks like "5%" but error_rate is a (0,1] fraction
        assertTrue(anchored(AlertRuleValidator.check(r, PIPES), "alert.threshold"));
    }

    @Test
    void numericThresholdAboveOneIsFineForNonFractionMetrics() {
        Map<String, Object> r = valid();
        r.put("metric", "failed_batches");
        r.put("threshold", 20);                    // a count, not a fraction — allowed
        assertTrue(AlertRuleValidator.check(r, PIPES).isEmpty());
    }

    @Test
    void thresholdAcceptsNumericStrings() {
        Map<String, Object> r = valid();
        r.put("threshold", "0.05");                // models often emit numbers as strings
        assertTrue(AlertRuleValidator.check(r, PIPES).isEmpty());
    }

    @Test
    void missingAndMalformedWindowAreFlagged() {
        Map<String, Object> miss = valid();
        miss.remove("window");
        assertTrue(anchored(AlertRuleValidator.check(miss, PIPES), "alert.window"));

        Map<String, Object> bad = valid();
        bad.put("window", "yesterday");
        assertTrue(anchored(AlertRuleValidator.check(bad, PIPES), "alert.window"));
    }

    @Test
    void windowAcceptsDurationsAndBatchCounts() {
        for (String w : List.of("30s", "30m", "1h", "1d", "20b")) {
            Map<String, Object> r = valid();
            r.put("window", w);
            assertTrue(AlertRuleValidator.check(r, PIPES).isEmpty(), "window '" + w + "' should be valid");
        }
    }

    @Test
    void missingAndUnknownSeverityAreFlagged() {
        Map<String, Object> miss = valid();
        miss.remove("severity");
        assertTrue(anchored(AlertRuleValidator.check(miss, PIPES), "alert.severity"));

        Map<String, Object> bad = valid();
        bad.put("severity", "FATAL");
        assertTrue(anchored(AlertRuleValidator.check(bad, PIPES), "alert.severity"));
    }

    @Test
    void severityIsCaseInsensitive() {
        Map<String, Object> r = valid();
        r.put("severity", "warning");
        assertTrue(AlertRuleValidator.check(r, PIPES).isEmpty());
    }

    @Test
    void onPipelineIsGroundedAgainstKnownPipelines() {
        Map<String, Object> known = valid();
        known.put("onPipeline", "EVENTS");
        assertTrue(AlertRuleValidator.check(known, PIPES).isEmpty());

        Map<String, Object> unknown = valid();
        unknown.put("onPipeline", "NOPE");
        assertTrue(anchored(AlertRuleValidator.check(unknown, PIPES), "alert.onPipeline"));
    }

    @Test
    void blankFieldsCountAsMissing() {
        Map<String, Object> r = valid();
        r.put("name", "   ");
        r.put("metric", "null");                   // literal "null" string is treated as absent
        List<Finding> fs = AlertRuleValidator.check(r, PIPES);
        assertTrue(anchored(fs, "alert.name"));
        assertTrue(anchored(fs, "alert.metric"));
    }

    @Test
    void allFindingsAreErrorSeverity() {
        Map<String, Object> r = new HashMap<>();   // empty rule → every required field flagged
        List<Finding> fs = AlertRuleValidator.check(r, PIPES);
        assertFalse(fs.isEmpty());
        assertTrue(fs.stream().allMatch(f -> f.severity() == com.gamma.config.spec.Severity.ERROR));
    }
}
