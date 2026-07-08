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
    void widgetGrantClosureAndCascade(@TempDir Path spacesRoot) {
        Exchange ex = Exchange.under(spacesRoot);
        ex.putOffer(new Offer("dataset", "tax_receipts", "finance", "", Map.of(), "a", 1L));
        ex.putOffer(new Offer("widget", "chart1", "finance", "", Map.of(), "a", 1L, "tax_receipts"));

        // requesting the widget auto-creates the bound dataset grant (both pending)
        ShareGrant wg = ex.request("widget", "chart1", "finance", "audit", "r", "p", null);
        String dgid = ShareGrant.idFor("dataset", "tax_receipts", "finance", "audit");
        assertEquals(ShareGrant.REQUESTED, wg.status());
        assertTrue(ex.grant(dgid).isPresent(), "dataset grant travels with the widget");
        assertFalse(ex.canRenderWidget("audit", "finance", "chart1"), "not renderable while pending");

        // approving the widget activates the pair atomically
        ex.approve(wg.id(), "a");
        assertEquals(ShareGrant.ACTIVE, ex.grant(dgid).orElseThrow().status());
        assertTrue(ex.canRenderWidget("audit", "finance", "chart1"));

        // revoking the dataset grant cascades to the dependent widget grant (fail-closed)
        ex.revoke(dgid, "a");
        assertEquals(ShareGrant.REVOKED, ex.grant(wg.id()).orElseThrow().status());
        assertFalse(ex.canRenderWidget("audit", "finance", "chart1"));
    }

    @Test
    void expiryClosesResolution(@TempDir Path spacesRoot) {
        Exchange ex = Exchange.under(spacesRoot);
        ex.putOffer(datasetOffer());
        ShareGrant g = ex.request("dataset", "tax_receipts", "finance", "audit", "r", "p", null);
        ex.approve(g.id(), "a");
        assertTrue(ex.resolveForConsumer("audit", "finance", "dataset", "tax_receipts").isPresent());

        ex.setExpiry(g.id(), System.currentTimeMillis() - 1000);   // already past
        assertTrue(ex.activeGrant("audit", "finance", "dataset", "tax_receipts").isEmpty(), "expired ⇒ inactive");
        assertTrue(ex.resolveForConsumer("audit", "finance", "dataset", "tax_receipts").isEmpty());

        ex.setExpiry(g.id(), System.currentTimeMillis() + 3_600_000);   // future re-opens
        assertTrue(ex.resolveForConsumer("audit", "finance", "dataset", "tax_receipts").isPresent());
    }

    @Test
    void pinSetAndClear(@TempDir Path spacesRoot) {
        Exchange ex = Exchange.under(spacesRoot);
        ex.putOffer(datasetOffer());
        ShareGrant g = ex.request("dataset", "tax_receipts", "finance", "audit", "r", "p", null);
        ex.approve(g.id(), "a");
        assertNull(ex.grant(g.id()).orElseThrow().pin());
        assertEquals("v123", ex.setPin(g.id(), "v123").pin());
        assertEquals("v123", ex.grant(g.id()).orElseThrow().pin());
        assertNull(ex.setPin(g.id(), null).pin(), "blank/null clears the pin");
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
