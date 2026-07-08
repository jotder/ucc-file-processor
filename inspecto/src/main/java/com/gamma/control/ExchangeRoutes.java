package com.gamma.control;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.exchange.Exchange;
import com.gamma.exchange.Offer;
import com.gamma.exchange.ShareGrant;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.SpaceContext;
import com.gamma.service.SpaceId;
import com.sun.net.httpserver.HttpExchange;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The Exchange — installation-scope, <b>un-prefixed</b> routes for cross-Space Dataset/Widget sharing
 * (design-of-record {@code docs/superpower/storage-layout-and-sharing-plan.md} §3). Like {@link SpaceRoutes}
 * these address the {@code spaces/_shared/} surface rather than one Space's engine, so they fall through
 * {@code ControlApi.dispatch}'s {@code /spaces/{id}} seam untouched.
 *
 * <pre>
 *   GET  /exchange/offers[?owner=]                 the shareable catalog (metadata only, never rows)
 *   POST /exchange/offers                          owner lists/updates an offer        [canOfferDatasets]
 *   POST /exchange/requests                        consumer requests use               [canRequestShares]
 *   POST /exchange/grants/{id}/{approve|deny|revoke}  owner acts on a grant            [canApproveShares]
 *   GET  /exchange/grants[?space=]                 the grant ledger (shared by/with a Space)
 *   GET  /exchange/datasets/{owner}/{item}[?consumer=]  one item's metadata (+ grant status)
 * </pre>
 *
 * <p>Fail-closed: every route 409s in single-tenant mode (no {@code _shared} dir, no one to share with —
 * mirroring {@link SpaceRoutes#requireMultiSpace}). Reads are open; writes are capability-gated
 * ({@link ApiContext#withCapability}) — a no-op on Personal edition (no {@link Subject}), enforced on
 * Standard. Every mutation emits an {@code EXCHANGE_*} signal (audit rides the central {@code AuditTrail}).
 */
final class ExchangeRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/exchange/offers", (e, m) -> listOffers(api, e));
        api.post("/exchange/offers", ApiContext.withCapability("canOfferDatasets",
                (e, m) -> putOffer(api, e)));
        api.post("/exchange/requests", ApiContext.withCapability("canRequestShares",
                (e, m) -> requestGrant(api, e)));
        api.post("/exchange/grants/([^/]+)/(approve|deny|revoke)", ApiContext.withCapability("canApproveShares",
                (e, m) -> actOnGrant(api, e, ApiContext.name(m), ApiContext.param(m, 2))));
        api.get("/exchange/grants", (e, m) -> listGrants(api, e));
        api.get("/exchange/datasets/([^/]+)/([^/]+)", (e, m) ->
                datasetMeta(api, e, ApiContext.param(m, 1), ApiContext.param(m, 2)));
    }

    // ── offers ─────────────────────────────────────────────────────────────────

    private Object listOffers(ApiContext api, HttpExchange e) {
        Exchange ex = requireExchange(api);
        String owner = ApiContext.query(e, "owner");
        return ex.offers().stream()
                .filter(o -> owner == null || owner.equals(o.owner()))
                .map(Offer::toMap)
                .toList();
    }

    private Object putOffer(ApiContext api, HttpExchange e) throws java.io.IOException {
        Exchange ex = requireExchange(api);
        Map<String, Object> body = api.body(e);
        String kind  = requireKind(body);
        String owner = requireSpace(api, ApiContext.str(body, "owner"), "owner");
        String item  = requireItem(body);
        // The offered component must actually exist in the owner Space's registry (cross-Space read is
        // legitimate here — the Exchange is the one surface that spans Spaces).
        if (!ownerRegistry(api, owner).exists(kind, item))
            throw new ApiException(404, "no " + kind + " '" + item + "' in space '" + owner + "'");

        @SuppressWarnings("unchecked")
        Map<String, Object> resultSet = body.get("resultSet") instanceof Map<?, ?> rs
                ? (Map<String, Object>) rs : Map.of();
        Offer offer = new Offer(kind, item, owner, ApiContext.str(body, "description"),
                resultSet, ApiContext.actor(e), System.currentTimeMillis());
        ex.putOffer(offer);
        signal(e, EventType.EXCHANGE_OFFERED, "offered " + kind + " " + owner + "/" + item,
                owner, null, kind, item);
        return offer.toMap();
    }

    // ── grant lifecycle ──────────────────────────────────────────────────────────

    private Object requestGrant(ApiContext api, HttpExchange e) throws java.io.IOException {
        Exchange ex = requireExchange(api);
        Map<String, Object> body = api.body(e);
        String kind     = requireKind(body);
        String owner    = requireSpace(api, ApiContext.str(body, "owner"), "owner");
        String consumer = requireSpace(api, ApiContext.str(body, "consumer"), "consumer");
        String item     = requireItem(body);
        if (owner.equals(consumer))
            throw new ApiException(400, "a space cannot request a share from itself");
        if (ex.offer(owner, kind, item).isEmpty())
            throw new ApiException(404, "no offer for " + kind + " " + owner + "/" + item);
        try {
            ShareGrant g = ex.request(kind, item, owner, consumer, ApiContext.actor(e),
                    ApiContext.str(body, "purpose"), ApiContext.str(body, "mode"));
            signal(e, EventType.EXCHANGE_REQUESTED, "requested " + kind + " " + owner + "/" + item,
                    owner, consumer, kind, item);
            return g.toMap();
        } catch (IllegalStateException conflict) {
            throw new ApiException(409, conflict.getMessage());
        }
    }

    private Object actOnGrant(ApiContext api, HttpExchange e, String id, String action) {
        Exchange ex = requireExchange(api);
        String actor = ApiContext.actor(e);
        try {
            ShareGrant g = switch (action) {
                case "approve" -> ex.approve(id, actor);
                case "deny"    -> ex.deny(id, actor);
                case "revoke"  -> ex.revoke(id, actor);
                default        -> throw new ApiException(400, "unknown grant action '" + action + "'");
            };
            String type = switch (action) {
                case "approve" -> EventType.EXCHANGE_GRANTED;
                case "deny"    -> EventType.EXCHANGE_DENIED;
                default        -> EventType.EXCHANGE_REVOKED;
            };
            signal(e, type, action + "d " + g.kind() + " " + g.owner() + "/" + g.item(),
                    g.owner(), g.consumer(), g.kind(), g.item());
            return g.toMap();
        } catch (NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        } catch (IllegalStateException conflict) {
            throw new ApiException(409, conflict.getMessage());
        }
    }

    private Object listGrants(ApiContext api, HttpExchange e) {
        Exchange ex = requireExchange(api);
        String space = ApiContext.query(e, "space");
        var grants = (space == null ? ex.grants() : ex.grantsForSpace(space));
        return grants.stream().map(ShareGrant::toMap).toList();
    }

    private Object datasetMeta(ApiContext api, HttpExchange e, String owner, String item) {
        Exchange ex = requireExchange(api);
        Offer offer = ex.offer(owner, "dataset", item)
                .orElseThrow(() -> new ApiException(404, "no offered dataset " + owner + "/" + item));
        Map<String, Object> out = new LinkedHashMap<>(offer.toMap());
        String consumer = ApiContext.query(e, "consumer");
        if (consumer != null)
            ex.grant(ShareGrant.idFor("dataset", item, owner, consumer))
                    .ifPresent(g -> out.put("grant", g.toMap()));
        return out;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** The Exchange for this installation, or a 409 in single-tenant mode (fail-closed). */
    private static Exchange requireExchange(ApiContext api) {
        Exchange ex = Exchange.under(api.spaces().containerRoot());
        if (!ex.enabled())
            throw new ApiException(409, "cross-space sharing needs the multi-space runtime (-Dspaces.root)");
        return ex;
    }

    private static ComponentStore ownerRegistry(ApiContext api, String owner) {
        SpaceContext ctx = api.spaces().space(SpaceId.of(owner))
                .orElseThrow(() -> new ApiException(404, "no such space '" + owner + "'"));
        java.nio.file.Path config = ctx.root().config();
        if (config == null) throw new ApiException(409, "space '" + owner + "' has no registry");
        return new ComponentStore(config.resolve("registry"));
    }

    private static String requireKind(Map<String, Object> body) {
        String kind = ApiContext.str(body, "kind");
        if (!"dataset".equals(kind) && !"widget".equals(kind))
            throw new ApiException(400, "'kind' must be 'dataset' or 'widget'");
        return kind;
    }

    /** Validate a body {@code item} id (component-id charset) — the offered/requested component name. */
    private static String requireItem(Map<String, Object> body) {
        String item = ApiContext.str(body, "item");
        if (item == null || item.contains("..") || !item.matches("[A-Za-z0-9][A-Za-z0-9._-]*"))
            throw new ApiException(400, "'item' must be a valid component id");
        return item;
    }

    /** Validate a space id from the body exists as a hosted Space. */
    private static String requireSpace(ApiContext api, String id, String field) {
        if (id == null || !SpaceId.isValid(id))
            throw new ApiException(400, "'" + field + "' must be a valid space id");
        if (api.spaces().space(SpaceId.of(id)).isEmpty())
            throw new ApiException(404, "no such space '" + id + "'");
        return id;
    }

    /** Emit an {@code EXCHANGE_*} lifecycle signal (the audit trail is recorded centrally by dispatch). */
    private static void signal(HttpExchange e, String type, String message,
                               String owner, String consumer, String kind, String item) {
        Event.Builder b = Event.builder(type).source("exchange").message(message)
                .actor(ApiContext.actor(e)).actorType("user")
                .attr("owner", owner).attr("kind", kind).attr("item", item);
        if (consumer != null) b.attr("consumer", consumer);
        EventLog.current().emit(b);
    }
}
