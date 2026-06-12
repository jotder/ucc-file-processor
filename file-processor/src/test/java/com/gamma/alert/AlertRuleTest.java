package com.gamma.alert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rule parsing/validation + window/comparator semantics (v4.1, B5). */
class AlertRuleTest {

    private static Map<String, Object> valid() {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "high-error-rate");
        m.put("metric", "error_rate");
        m.put("comparator", "gt");
        m.put("threshold", 0.05);
        m.put("window", "1h");
        m.put("severity", "WARNING");
        m.put("onPipeline", "EVENTS");
        return m;
    }

    @Test
    void parsesAValidRule() {
        AlertRule r = AlertRule.fromMap(valid());
        assertEquals("high-error-rate", r.name());
        assertEquals("error_rate", r.metric());
        assertEquals(0.05, r.threshold());
        assertEquals(Duration.ofHours(1), r.windowDuration());
        assertFalse(r.batchWindow());
        assertEquals("EVENTS", r.onPipeline());
    }

    @Test
    void rejectsBadFields() {
        for (var bad : Map.of(
                "metric", "row_count",
                "comparator", "between",
                "severity", "PANIC",
                "window", "soon").entrySet()) {
            Map<String, Object> m = valid();
            m.put(bad.getKey(), bad.getValue());
            assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(m),
                    "should reject " + bad);
        }
        Map<String, Object> m = valid();
        m.put("threshold", -1);
        assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(m));
    }

    @Test
    void batchWindowAndComparators() {
        Map<String, Object> m = valid();
        m.put("window", "20b");
        m.remove("onPipeline");
        AlertRule r = AlertRule.fromMap(m);
        assertTrue(r.batchWindow());
        assertEquals(20, r.windowBatches());
        assertNull(r.onPipeline(), "absent onPipeline means every pipeline");
        assertTrue(r.breached(0.06));
        assertFalse(r.breached(0.05), "gt is strict");
    }

    @Test
    void loadsTheDiagnoseAndAlertDraftShape(@TempDir Path dir) throws Exception {
        // Exactly what the agent's draftToon emits: ConfigCodec.toToon of a root 'alert' block.
        Path f = dir.resolve("high_errors_alert.toon");
        Files.writeString(f, com.gamma.config.io.ConfigCodec.toToon(Map.of("alert", valid())));
        AlertRule r = AlertRule.load(f);
        assertEquals("high-error-rate", r.name());
        assertEquals("WARNING", r.severity());
    }
}
