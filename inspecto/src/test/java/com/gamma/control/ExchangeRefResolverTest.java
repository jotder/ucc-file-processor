package com.gamma.control;

import com.gamma.event.EventLog;
import com.gamma.exchange.Exchange;
import com.gamma.exchange.ExchangeSnapshots;
import com.gamma.exchange.ExchangeSnapshots.SnapshotMeta;
import com.gamma.exchange.ExchangeSnapshotWriter;
import com.gamma.exchange.Offer;
import com.gamma.exchange.ShareGrant;
import com.gamma.metrics.MetricRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.SharedRefResolver;
import com.gamma.service.SpaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S3 delivery-mode routing through the production {@link ExchangeRefResolver}: a {@code live} grant reads
 * the owner's at-rest Table dir; a {@code snapshot} grant reads the published version (honouring a version
 * pin); an expired grant resolves nothing.
 */
class ExchangeRefResolverTest {

    private static String unix(Path p) { return p.toString().replace('\\', '/'); }

    /** Seed finance (Table + dataset component) and two empty consumer spaces so discover boots all three. */
    private void seed(Path spacesRoot) throws Exception {
        Path registry = spacesRoot.resolve("finance").resolve("config").resolve("registry");
        Path table = spacesRoot.resolve("finance").resolve("data").resolve("tax_receipts");
        Files.createDirectories(table);
        Files.createDirectories(spacesRoot.resolve("audit").resolve("config"));
        Files.createDirectories(spacesRoot.resolve("review").resolve("config"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("COPY (SELECT * FROM (VALUES (1,'a'),(2,'b')) t(amount,label)) TO "
                    + "'" + unix(table.resolve("part.parquet")) + "' (FORMAT PARQUET)");
        }
        new ComponentStore(registry).write("dataset", "tax_receipts", Map.of("physicalRef", "tax_receipts"));
    }

    private SnapshotMeta publish(Path spacesRoot, Exchange ex) throws Exception {
        return ExchangeSnapshotWriter.publish(ex.dir(), "finance",
                spacesRoot.resolve("finance").resolve("config").resolve("registry"),
                spacesRoot.resolve("finance").resolve("data"),
                spacesRoot.resolve("finance").resolve("views"), "tax_receipts");
    }

    @Test
    void liveExpiryAndPinRouting(@TempDir Path spacesRoot) throws Exception {
        seed(spacesRoot);
        SpaceManager spaces = SpaceManager.discover(spacesRoot);
        try {
            Exchange ex = Exchange.under(spacesRoot);
            ex.putOffer(new Offer("dataset", "tax_receipts", "finance", "", Map.of(), "a", 1L));
            SharedRefResolver.install(new ExchangeRefResolver(spaces));
            SharedRefResolver r = SharedRefResolver.global();
            Path itemDir = ExchangeSnapshots.itemDir(ex.dir(), "finance", "tax_receipts");

            // ── LIVE grant (consumer audit) resolves to the owner's Table dir; expiry closes it ──
            ShareGrant live = ex.request("dataset", "tax_receipts", "finance", "audit", "r", "p", ShareGrant.LIVE);
            ex.approve(live.id(), "a");
            MDC.put(EventLog.SPACE_MDC_KEY, "audit");
            assertEquals(spacesRoot.resolve("finance").resolve("data").resolve("tax_receipts").normalize(),
                    r.resolveSnapshot("finance", "tax_receipts").orElseThrow().normalize(),
                    "live mode reads the owner's Table dir");
            ex.setExpiry(live.id(), System.currentTimeMillis() - 1000);
            assertTrue(r.resolveSnapshot("finance", "tax_receipts").isEmpty(), "expired grant resolves nothing");

            // ── SNAPSHOT grant (consumer review): tracks current, honours a pin ──
            ShareGrant snap = ex.request("dataset", "tax_receipts", "finance", "review", "r", "p", ShareGrant.SNAPSHOT);
            ex.approve(snap.id(), "a");
            SnapshotMeta v1 = publish(spacesRoot, ex);
            MDC.put(EventLog.SPACE_MDC_KEY, "review");
            assertEquals(itemDir.resolve(v1.version()).normalize(),
                    r.resolveSnapshot("finance", "tax_receipts").orElseThrow().normalize(),
                    "snapshot mode tracks current.toon");

            ex.setPin(snap.id(), v1.version());
            Thread.sleep(5);                                  // ensure a distinct version millis
            SnapshotMeta v2 = publish(spacesRoot, ex);        // current is now v2
            assertNotEquals(v1.version(), v2.version());
            assertEquals(itemDir.resolve(v1.version()).normalize(),
                    r.resolveSnapshot("finance", "tax_receipts").orElseThrow().normalize(),
                    "a pinned grant stays on its version through a refresh");

            ex.setPin(snap.id(), null);
            assertEquals(itemDir.resolve(v2.version()).normalize(),
                    r.resolveSnapshot("finance", "tax_receipts").orElseThrow().normalize(),
                    "clearing the pin tracks current again");
        } finally {
            MDC.remove(EventLog.SPACE_MDC_KEY);
            SharedRefResolver.install(null);
            spaces.close();
            MetricRegistry.global().reset();
        }
    }
}
