package com.gamma.intelligence.context;

import com.gamma.service.CollectorService;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The situation frame must be deterministic, budgeted, and honour the WARN+ overlay floor.
 *
 * <p>The default-space {@link CollectorService} rides {@code EventLog.global()} — a JVM-wide ledger
 * other tests emit onto — so seeded overlay signals are future-dated to guarantee they sort into the
 * newest-first overlay window regardless of what else the reactor logged.
 */
class ContextBrokerTest {

    /** Future-dated so these signals dominate the newest-first overlay over any ambient ledger noise. */
    private final Instant base = Instant.now().plusSeconds(3600);

    private static CollectorService seeded(Signal... signals) {
        CollectorService svc = new CollectorService(List.of(), 3600, 1);
        for (Signal s : signals) svc.events().append(s.toEvent());
        return svc;
    }

    private Signal sig(String id, String type, Severity sev, Instant at) {
        return new Signal(id, type, at, sev, Ref.of("pipeline", "p"), Ref.of("pipeline", "p"),
                null, null, null, null, "msg-" + id, Map.of(), 1);
    }

    @Test
    void frameIsDeterministicAndCarriesIdentityFocusAndOverlay() {
        CollectorService svc = seeded(
                sig("s5cb-w1", "pipeline.batch.failed", Severity.ERROR, base),
                sig("s5cb-w2", "job.run.rejected", Severity.WARN, base.plusSeconds(1)));
        ContextBroker broker = new ContextBroker(svc);

        String first = broker.frame("analyst", Map.of("pageId", "incidents"));
        String second = broker.frame("analyst", Map.of("pageId", "incidents"));
        assertEquals(first, second, "same ledger + inputs → byte-identical frame");

        assertTrue(first.startsWith("[SITUATION]"), first);
        assertTrue(first.contains("role=analyst"));
        assertTrue(first.contains("pageId=incidents"));
        assertTrue(first.contains("[s5cb-w1]") && first.contains("[s5cb-w2]"), "both elevated signals cited");
        assertTrue(first.length() <= ContextBroker.FRAME_BUDGET_CHARS);
    }

    @Test
    void overlayExcludesSignalsBelowTheWarnFloor() {
        CollectorService svc = seeded(
                sig("s5cb-info1", "pipeline.batch.committed", Severity.INFO, base),
                sig("s5cb-warn1", "job.run.rejected", Severity.WARN, base.plusSeconds(1)));
        String frame = new ContextBroker(svc).frame("ops", Map.of());

        assertTrue(frame.contains("[s5cb-warn1]"), "WARN signal is in the overlay");
        assertFalse(frame.contains("[s5cb-info1]"), "INFO signal is below the WARN floor");
        assertTrue(frame.contains("focus: none"), "empty page → stable 'none' focus, not a vanished section");
    }

    @Test
    void nullInputsStillRenderEveryStableSection() {
        String frame = new ContextBroker(seeded()).frame(null, null);
        assertTrue(frame.contains("identity: role=unknown"));
        assertTrue(frame.contains("focus: none"));
        assertTrue(frame.contains("signals (recent WARN+, newest first):"), "the overlay section header is always present");
    }
}
