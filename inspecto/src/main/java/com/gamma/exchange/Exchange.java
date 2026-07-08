package com.gamma.exchange;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * The Exchange — the installation-scope, cross-Space sharing surface that lives under
 * {@code spaces/_shared/} (reserved, never itself a Space). It holds two flat ledgers:
 * <ul>
 *   <li>{@code offers.toon} — the Datasets/Widgets each owner has listed as shareable (catalog metadata
 *       only, never rows);</li>
 *   <li>{@code grants.toon} — the {@link ShareGrant} lifecycle ledger tying an owner's offered item to a
 *       consumer Space.</li>
 * </ul>
 *
 * <p>Sharing is per-item, opt-in and grant-mediated: nothing is discoverable across Spaces unless its
 * owner offers it, and nothing is usable without an <em>active</em> grant — {@link #resolveForConsumer}
 * is fail-closed. This class is the pure ledger + lifecycle state machine; the HTTP edge
 * ({@code ExchangeRoutes}) owns capability gating, cross-Space validation, and audit/signal emission.
 *
 * <p><b>Single-tenant fail-closed:</b> with no {@code -Dspaces.root} there is no {@code _shared} dir and
 * no one to share with, so {@link #under(Path)} yields a {@linkplain #enabled() disabled} Exchange whose
 * every operation throws {@link IllegalStateException} — matching the packs-dir "off when unset" posture.
 *
 * <p><b>Concurrency:</b> read-modify-write of the ledgers is serialised on a process-wide lock (one
 * installation has one {@code _shared} dir); reads are lock-free (files are written atomically).
 */
public final class Exchange {

    private static final String OFFERS = "offers";
    private static final String GRANTS = "grants";
    /** Serialises ledger mutations across the per-request {@link Exchange} instances (one _shared dir). */
    private static final Object LOCK = new Object();

    /** {@code spaces/_shared/}, or {@code null} in single-tenant mode (Exchange disabled). */
    private final Path root;

    private Exchange(Path root) {
        this.root = root;
    }

    /** The Exchange under a container root ({@code -Dspaces.root}); a {@code null} root yields a disabled one. */
    public static Exchange under(Path spacesRoot) {
        return new Exchange(spacesRoot == null ? null : spacesRoot.resolve("_shared").normalize());
    }

    /** Whether cross-Space sharing is available (false in single-tenant mode). */
    public boolean enabled() {
        return root != null;
    }

    /** The {@code spaces/_shared/} directory, or {@code null} when disabled. */
    public Path dir() {
        return root;
    }

    private void requireEnabled() {
        if (root == null)
            throw new IllegalStateException("the Exchange is disabled: this server hosts a single space");
    }

    // ── offers ─────────────────────────────────────────────────────────────────

    /** Every listed offer, catalog order (as stored). */
    public List<Offer> offers() {
        requireEnabled();
        return Ledger.read(root.resolve("offers.toon"), OFFERS).stream().map(Offer::fromMap).toList();
    }

    /** The offer for {@code (owner, kind, item)}, if listed. */
    public Optional<Offer> offer(String owner, String kind, String item) {
        String key = owner + "~" + kind + "~" + item;
        return offers().stream().filter(o -> o.key().equals(key)).findFirst();
    }

    /** List or update an offer (upsert by {@code (owner, kind, item)}); returns the stored offer. */
    public Offer putOffer(Offer offer) {
        requireEnabled();
        synchronized (LOCK) {
            List<Offer> kept = new ArrayList<>(offers().stream()
                    .filter(o -> !o.key().equals(offer.key())).toList());
            kept.add(offer);
            Ledger.write(root.resolve("offers.toon"), OFFERS, kept.stream().map(Offer::toMap).toList());
            return offer;
        }
    }

    // ── grants ─────────────────────────────────────────────────────────────────

    /** Every grant in the ledger. */
    public List<ShareGrant> grants() {
        requireEnabled();
        return Ledger.read(root.resolve("grants.toon"), GRANTS).stream().map(ShareGrant::fromMap).toList();
    }

    /** Grants where {@code space} is either the owner or the consumer (its "shared by/with me" view). */
    public List<ShareGrant> grantsForSpace(String space) {
        return grants().stream()
                .filter(g -> space.equals(g.owner()) || space.equals(g.consumer()))
                .toList();
    }

    /** One grant by id. */
    public Optional<ShareGrant> grant(String id) {
        return grants().stream().filter(g -> id.equals(g.id())).findFirst();
    }

    /**
     * Record a consumer's request to use an offered item. Idempotent when a request is already pending;
     * a previously {@code denied}/{@code revoked}/{@code expired} grant is reopened as {@code requested}.
     *
     * @throws IllegalStateException when an {@code active} grant already exists (nothing to request)
     */
    public ShareGrant request(String kind, String item, String owner, String consumer,
                              String requestedBy, String purpose, String mode) {
        requireEnabled();
        synchronized (LOCK) {
            String id = ShareGrant.idFor(kind, item, owner, consumer);
            Optional<ShareGrant> existing = grant(id);
            if (existing.isPresent() && ShareGrant.ACTIVE.equals(existing.get().status()))
                throw new IllegalStateException("an active grant already exists for " + id);
            if (existing.isPresent() && ShareGrant.REQUESTED.equals(existing.get().status()))
                return existing.get();   // idempotent re-request
            ShareGrant grant = new ShareGrant(id, kind, item, owner, consumer,
                    (mode == null || mode.isBlank()) ? ShareGrant.SNAPSHOT : mode,
                    ShareGrant.REQUESTED, requestedBy, System.currentTimeMillis(), purpose,
                    null, 0L, null, null);
            upsert(grant);
            // Widget grant closure (§3.5): its underlying Dataset grant travels with it. Ensure a dataset
            // grant for the same consumer exists (created here as pending; approved atomically with the widget).
            if ("widget".equals(kind)) {
                String ds = boundDataset(owner, item);
                if (ds != null) {
                    String dgid = ShareGrant.idFor("dataset", ds, owner, consumer);
                    boolean livePair = grant(dgid).map(x -> ShareGrant.ACTIVE.equals(x.status())
                            || ShareGrant.REQUESTED.equals(x.status())).orElse(false);
                    if (!livePair)
                        upsert(new ShareGrant(dgid, "dataset", ds, owner, consumer, grant.mode(),
                                ShareGrant.REQUESTED, requestedBy, System.currentTimeMillis(), purpose,
                                null, 0L, null, null));
                }
            }
            return grant;
        }
    }

    /**
     * Owner approves a pending request → {@code active}. A widget approval also activates its bound
     * Dataset grant atomically (the pair travels together — §3.5).
     */
    public ShareGrant approve(String id, String approver) {
        synchronized (LOCK) {
            ShareGrant g = transition(id, ShareGrant.REQUESTED, ShareGrant.ACTIVE, approver, true);
            if ("widget".equals(g.kind())) {
                String ds = boundDataset(g.owner(), g.item());
                if (ds != null) {
                    String dgid = ShareGrant.idFor("dataset", ds, g.owner(), g.consumer());
                    grant(dgid).filter(x -> ShareGrant.REQUESTED.equals(x.status()))
                            .ifPresent(x -> transition(dgid, ShareGrant.REQUESTED, ShareGrant.ACTIVE, approver, true));
                }
            }
            return g;
        }
    }

    /** Owner denies a pending request → {@code denied}. */
    public ShareGrant deny(String id, String approver) {
        return transition(id, ShareGrant.REQUESTED, ShareGrant.DENIED, approver, true);
    }

    /**
     * Owner revokes an active grant → {@code revoked}. Revoking a Dataset grant cascades: every active
     * widget grant that depends on it (same owner+consumer) is revoked too — fail-closed (§3.5).
     */
    public ShareGrant revoke(String id, String actor) {
        synchronized (LOCK) {
            ShareGrant g = transition(id, ShareGrant.ACTIVE, ShareGrant.REVOKED, actor, false);
            if ("dataset".equals(g.kind())) {
                for (ShareGrant w : grants()) {
                    if ("widget".equals(w.kind()) && ShareGrant.ACTIVE.equals(w.status())
                            && g.owner().equals(w.owner()) && g.consumer().equals(w.consumer())
                            && g.item().equals(boundDataset(w.owner(), w.item())))
                        transition(w.id(), ShareGrant.ACTIVE, ShareGrant.REVOKED, actor, false);
                }
            }
            return g;
        }
    }

    /**
     * Whether {@code consumer} may render owner's shared widget {@code item} — fail-closed: both the widget
     * grant <em>and</em> the bound Dataset grant must be active (§3.5). A widget with no bound Dataset offer
     * can never render shared.
     */
    public boolean canRenderWidget(String consumer, String owner, String item) {
        if (activeGrant(consumer, owner, "widget", item).isEmpty()) return false;
        String ds = boundDataset(owner, item);
        return ds != null && activeGrant(consumer, owner, "dataset", ds).isPresent();
    }

    /** The Dataset a widget offer binds ({@link Offer#dataset}), or {@code null} when unknown/unset. */
    private String boundDataset(String owner, String item) {
        return offer(owner, "widget", item).map(Offer::dataset)
                .filter(s -> s != null && !s.isBlank()).orElse(null);
    }

    /**
     * The <em>effective</em> grant for a {@code (kind, item, owner, consumer)} quad — present only when it is
     * {@code active} and not past its {@code expiresAt} (S3 expiry enforcement). The single fail-closed gate
     * every resolution path consults.
     */
    public Optional<ShareGrant> activeGrant(String consumer, String owner, String kind, String item) {
        return grant(ShareGrant.idFor(kind, item, owner, consumer)).filter(Exchange::effectivelyActive);
    }

    /**
     * Resolve an offered item's metadata for a consumer — fail-closed: returns the {@link Offer} only when an
     * effective ({@link #activeGrant active, unexpired}) grant exists. No grant (or a non-active/expired one)
     * ⇒ empty, even if the offer itself exists.
     */
    public Optional<Offer> resolveForConsumer(String consumer, String owner, String kind, String item) {
        return activeGrant(consumer, owner, kind, item).flatMap(g -> offer(owner, kind, item));
    }

    /** Set (or clear, with {@code null}) an active grant's expiry ({@code epoch millis}); owner governance (S3). */
    public ShareGrant setExpiry(String id, Long expiresAt) {
        return mutate(id, g -> new ShareGrant(g.id(), g.kind(), g.item(), g.owner(), g.consumer(),
                g.mode(), g.status(), g.requestedBy(), g.requestedAt(), g.purpose(),
                g.approvedBy(), g.approvedAt(), g.pin(), expiresAt));
    }

    /** Set (or clear, with {@code null}) a grant's version pin — snapshot resolution then serves that version (S3). */
    public ShareGrant setPin(String id, String version) {
        return mutate(id, g -> new ShareGrant(g.id(), g.kind(), g.item(), g.owner(), g.consumer(),
                g.mode(), g.status(), g.requestedBy(), g.requestedAt(), g.purpose(),
                g.approvedBy(), g.approvedAt(), (version == null || version.isBlank()) ? null : version, g.expiresAt()));
    }

    /** True when a grant is active and not past its expiry. */
    private static boolean effectivelyActive(ShareGrant g) {
        return ShareGrant.ACTIVE.equals(g.status())
                && (g.expiresAt() == null || g.expiresAt() > System.currentTimeMillis());
    }

    // ── internals ────────────────────────────────────────────────────────────────

    private ShareGrant transition(String id, String from, String to, String actor, boolean stampApproval) {
        requireEnabled();
        synchronized (LOCK) {
            ShareGrant g = grant(id).orElseThrow(() -> new NoSuchElementException("no such grant '" + id + "'"));
            if (!from.equals(g.status()))
                throw new IllegalStateException(
                        "cannot move grant '" + id + "' from " + g.status() + " to " + to + " (expected " + from + ")");
            ShareGrant next = new ShareGrant(g.id(), g.kind(), g.item(), g.owner(), g.consumer(),
                    g.mode(), to, g.requestedBy(), g.requestedAt(), g.purpose(),
                    stampApproval ? actor : g.approvedBy(),
                    stampApproval ? System.currentTimeMillis() : g.approvedAt(),
                    g.pin(), g.expiresAt());
            upsert(next);
            return next;
        }
    }

    /** Apply {@code fn} to an existing grant and persist the result (used by the field setters). */
    private ShareGrant mutate(String id, java.util.function.UnaryOperator<ShareGrant> fn) {
        requireEnabled();
        synchronized (LOCK) {
            ShareGrant g = grant(id).orElseThrow(() -> new NoSuchElementException("no such grant '" + id + "'"));
            ShareGrant next = fn.apply(g);
            upsert(next);
            return next;
        }
    }

    /** Replace-or-append a grant by id (call under {@link #LOCK}). */
    private void upsert(ShareGrant grant) {
        List<ShareGrant> kept = new ArrayList<>(grants().stream()
                .filter(g -> !g.id().equals(grant.id())).toList());
        kept.add(grant);
        Ledger.write(root.resolve("grants.toon"), GRANTS, kept.stream().map(ShareGrant::toMap).toList());
    }
}
