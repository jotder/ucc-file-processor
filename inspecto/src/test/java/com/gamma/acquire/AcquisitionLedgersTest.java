package com.gamma.acquire;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.gamma.event.EventLog;

import static org.junit.jupiter.api.Assertions.*;

/** The process-wide accessor resolves one {@link AcquisitionLedger} per space ({@link EventLog#currentSpaceId()}). */
class AcquisitionLedgersTest {

    @Test
    void ledgersAreIsolatedPerSpaceMdc() {
        AcquisitionLedger a = new InMemoryAcquisitionLedger();
        AcquisitionLedger b = new InMemoryAcquisitionLedger();
        try {
            MDC.put(EventLog.SPACE_MDC_KEY, "space-a");
            AcquisitionLedgers.use(a);
            MDC.put(EventLog.SPACE_MDC_KEY, "space-b");
            AcquisitionLedgers.use(b);

            MDC.put(EventLog.SPACE_MDC_KEY, "space-a");
            assertSame(a, AcquisitionLedgers.shared(), "space-a sees its own ledger");
            MDC.put(EventLog.SPACE_MDC_KEY, "space-b");
            assertSame(b, AcquisitionLedgers.shared(), "space-b sees its own ledger");

            // A fresh space lazily builds its own (default backend = in-memory), distinct from a and b.
            MDC.put(EventLog.SPACE_MDC_KEY, "space-c");
            AcquisitionLedger c = AcquisitionLedgers.shared();
            assertNotSame(a, c);
            assertNotSame(b, c);
            assertSame(c, AcquisitionLedgers.shared(), "same space resolves to the same lazily-built ledger");
        } finally {
            // Drop the test spaces' entries; never touch the default space other tests share.
            for (String s : new String[]{"space-a", "space-b", "space-c"}) {
                MDC.put(EventLog.SPACE_MDC_KEY, s);
                AcquisitionLedgers.use(null);
            }
            MDC.remove(EventLog.SPACE_MDC_KEY);
        }
    }
}
