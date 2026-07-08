package com.gamma.exchange;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The Exchange ledger + Share Grant lifecycle: offer → request → approve/deny/revoke, all fail-closed. */
class ExchangeTest {

    private static Offer datasetOffer() {
        return new Offer("dataset", "tax_receipts", "finance", "FY26 receipts",
                Map.of("columns", java.util.List.of("amount", "day")), "a.rao@finance.gov", 1L);
    }

    @Test
    void disabledWhenSingleTenant() {
        Exchange ex = Exchange.under(null);
        assertFalse(ex.enabled());
        assertThrows(IllegalStateException.class, ex::offers);
        assertThrows(IllegalStateException.class, () -> ex.putOffer(datasetOffer()));
    }

    @Test
    void offerRoundTrips(@TempDir Path spacesRoot) {
        Exchange ex = Exchange.under(spacesRoot);
        assertTrue(ex.enabled());
        assertTrue(ex.offers().isEmpty());

        ex.putOffer(datasetOffer());
        assertEquals(1, ex.offers().size());
        Optional<Offer> got = ex.offer("finance", "dataset", "tax_receipts");
        assertTrue(got.isPresent());
        assertEquals("FY26 receipts", got.get().description());

        // upsert by (owner,kind,item) — not a duplicate
        ex.putOffer(new Offer("dataset", "tax_receipts", "finance", "updated", Map.of(), "a.rao", 2L));
        assertEquals(1, ex.offers().size());
        assertEquals("updated", ex.offer("finance", "dataset", "tax_receipts").orElseThrow().description());
    }

    @Test
    void grantLifecycleAndFailClosedResolution(@TempDir Path spacesRoot) {
        Exchange ex = Exchange.under(spacesRoot);
        ex.putOffer(datasetOffer());

        // no grant yet ⇒ resolution is fail-closed even though the offer exists
        assertTrue(ex.resolveForConsumer("audit", "finance", "dataset", "tax_receipts").isEmpty());

        ShareGrant g = ex.request("dataset", "tax_receipts", "finance", "audit", "r.gupta@audit.gov", "audit", null);
        assertEquals(ShareGrant.REQUESTED, g.status());
        assertEquals(ShareGrant.SNAPSHOT, g.mode(), "default mode is snapshot");
        // still fail-closed while only requested
        assertTrue(ex.resolveForConsumer("audit", "finance", "dataset", "tax_receipts").isEmpty());

        // re-request is idempotent; requesting again does not duplicate
        assertEquals(g.id(), ex.request("dataset", "tax_receipts", "finance", "audit", "x", "y", null).id());
        assertEquals(1, ex.grants().size());

        // approve ⇒ active ⇒ resolves to metadata
        ShareGrant approved = ex.approve(g.id(), "a.rao@finance.gov");
        assertEquals(ShareGrant.ACTIVE, approved.status());
        assertEquals("a.rao@finance.gov", approved.approvedBy());
        assertTrue(ex.resolveForConsumer("audit", "finance", "dataset", "tax_receipts").isPresent());

        // requesting an already-active grant is a conflict
        assertThrows(IllegalStateException.class,
                () -> ex.request("dataset", "tax_receipts", "finance", "audit", "x", "y", null));

        // revoke ⇒ resolution closes again; re-approving a revoked grant is illegal
        ex.revoke(g.id(), "a.rao@finance.gov");
        assertTrue(ex.resolveForConsumer("audit", "finance", "dataset", "tax_receipts").isEmpty());
        assertThrows(IllegalStateException.class, () -> ex.approve(g.id(), "a.rao@finance.gov"));
    }

    @Test
    void denyKeepsResolutionClosed(@TempDir Path spacesRoot) {
        Exchange ex = Exchange.under(spacesRoot);
        ex.putOffer(datasetOffer());
        ShareGrant g = ex.request("dataset", "tax_receipts", "finance", "audit", "r.gupta", "audit", null);
        ex.deny(g.id(), "a.rao");
        assertEquals(ShareGrant.DENIED, ex.grant(g.id()).orElseThrow().status());
        assertTrue(ex.resolveForConsumer("audit", "finance", "dataset", "tax_receipts").isEmpty());
        // a denied request can be reopened
        assertEquals(ShareGrant.REQUESTED,
                ex.request("dataset", "tax_receipts", "finance", "audit", "r.gupta", "audit", null).status());
    }

    @Test
    void unknownGrantTransitionThrows(@TempDir Path spacesRoot) {
        Exchange ex = Exchange.under(spacesRoot);
        assertThrows(java.util.NoSuchElementException.class, () -> ex.approve("nope", "x"));
    }

    @Test
    void sharedRefParsing() {
        assertEquals(new SharedRef("finance", "tax_receipts"),
                SharedRef.parse("shared/finance/tax_receipts").orElseThrow());
        assertTrue(SharedRef.isShared("shared/x/y"));
        assertFalse(SharedRef.isShared("finance/tax"));
        assertTrue(SharedRef.parse("shared/finance").isEmpty(), "needs owner and item");
        assertTrue(SharedRef.parse("shared/Finance/x").isEmpty(), "owner must match SpaceId charset");
        assertEquals("shared/finance/tax_receipts", new SharedRef("finance", "tax_receipts").ref());
    }
}
