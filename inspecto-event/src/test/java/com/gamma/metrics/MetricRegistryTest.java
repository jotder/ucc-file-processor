package com.gamma.metrics;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.gamma.event.EventLog;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the zero-dep {@link MetricRegistry} — counter/gauge/histogram
 * recording and Prometheus text exposition (HELP/TYPE lines, labels, buckets).
 */
class MetricRegistryTest {

    @Test
    void counterAccumulatesAndExposesPerLabelSeries() {
        MetricRegistry r = new MetricRegistry();
        r.inc("inspecto_batches_total", "batches", Map.of("pipeline", "a", "status", "SUCCESS"));
        r.inc("inspecto_batches_total", "batches", Map.of("pipeline", "a", "status", "SUCCESS"));
        r.inc("inspecto_batches_total", "batches", Map.of("pipeline", "a", "status", "FAILED"));

        String out = r.scrape();
        assertTrue(out.contains("# TYPE inspecto_batches_total counter"));
        // labels are sorted (pipeline before status); value formatted as an integer
        assertTrue(out.contains("inspecto_batches_total{pipeline=\"a\",status=\"SUCCESS\"} 2"), out);
        assertTrue(out.contains("inspecto_batches_total{pipeline=\"a\",status=\"FAILED\"} 1"), out);
    }

    @Test
    void gaugeHoldsLastValue() {
        MetricRegistry r = new MetricRegistry();
        r.setGauge("inspecto_active_runs", "active", Map.of(), 3);
        r.setGauge("inspecto_active_runs", "active", Map.of(), 1);
        String out = r.scrape();
        assertTrue(out.contains("# TYPE inspecto_active_runs gauge"));
        assertTrue(out.contains("inspecto_active_runs 1"), out);
    }

    @Test
    void histogramEmitsCumulativeBucketsSumAndCount() {
        MetricRegistry r = new MetricRegistry();
        Map<String, String> l = Map.of("pipeline", "a");
        r.observe("inspecto_batch_duration_seconds", "dur", l, 0.2);   // <= 0.25
        r.observe("inspecto_batch_duration_seconds", "dur", l, 3.0);   // <= 5
        String out = r.scrape();
        assertTrue(out.contains("# TYPE inspecto_batch_duration_seconds histogram"));
        assertTrue(out.contains("inspecto_batch_duration_seconds_count{pipeline=\"a\"} 2"), out);
        assertTrue(out.contains("inspecto_batch_duration_seconds_bucket{pipeline=\"a\",le=\"+Inf\"} 2"), out);
        // 0.2 falls in le="0.25"; 3.0 does not → bucket at 0.25 has count 1
        assertTrue(out.contains("inspecto_batch_duration_seconds_bucket{pipeline=\"a\",le=\"0.25\"} 1"), out);
        // both observations are <= 5 → cumulative bucket at le="5" is 2
        assertTrue(out.contains("inspecto_batch_duration_seconds_bucket{pipeline=\"a\",le=\"5\"} 2"), out);
    }

    @Test
    void scrapeRunsRegisteredCollectors() {
        MetricRegistry r = new MetricRegistry();
        r.addCollector(() -> r.setGauge("inspecto_lazy", "lazy", Map.of(), 42));
        assertFalse(r.scrape().contains("inspecto_lazy") && !r.scrape().contains("42"));
        assertTrue(r.scrape().contains("inspecto_lazy 42"), "collector should populate the gauge at scrape time");
    }

    @Test
    void labelValuesAreEscaped() {
        MetricRegistry r = new MetricRegistry();
        r.inc("inspecto_x", "x", Map.of("p", "a\"b\\c"));
        assertTrue(r.scrape().contains("inspecto_x{p=\"a\\\"b\\\\c\"} 1"), r.scrape());
    }

    @Test
    void spaceMdcIsAutoMergedAsALabel() {
        MetricRegistry r = new MetricRegistry();
        MDC.put(EventLog.SPACE_MDC_KEY, "tenant-a");
        try {
            r.inc("inspecto_y", "y", Map.of("pipeline", "p"));
        } finally {
            MDC.remove(EventLog.SPACE_MDC_KEY);
        }
        // space is sorted into the label set (pipeline before space)
        assertTrue(r.scrape().contains("inspecto_y{pipeline=\"p\",space=\"tenant-a\"} 1"), r.scrape());
    }

    @Test
    void noSpaceMdcMeansNoSpaceLabel() {
        MetricRegistry r = new MetricRegistry();
        MDC.remove(EventLog.SPACE_MDC_KEY); // defensive: ensure no leak from another test
        r.inc("inspecto_z", "z", Map.of("pipeline", "p"));
        String out = r.scrape();
        assertTrue(out.contains("inspecto_z{pipeline=\"p\"} 1"), out);
        assertFalse(out.contains("space="), out);
    }
}
