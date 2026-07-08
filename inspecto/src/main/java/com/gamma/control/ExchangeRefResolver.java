package com.gamma.control;

import com.gamma.event.EventLog;
import com.gamma.exchange.Exchange;
import com.gamma.exchange.ExchangeSnapshots;
import com.gamma.exchange.ShareGrant;
import com.gamma.query.SharedRefResolver;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The Exchange-backed {@link SharedRefResolver} installed at server start — the bridge that lets
 * {@link com.gamma.query.DatasetRelation} resolve a {@code shared/<owner>/<item>} ref to the owner's
 * published snapshot directory, <b>grant-checked and fail-closed</b>. The calling (consumer) Space is the
 * request's Space MDC ({@link EventLog#currentSpaceId()}), so only {@code (owner, item)} is needed.
 *
 * <p>Returns empty (⇒ the ref is unusable) when: sharing is disabled (single-tenant), there is no active
 * grant for the calling Space, or no snapshot has been published yet — exactly the fail-closed posture the
 * plan requires (§3.3).
 */
final class ExchangeRefResolver implements SharedRefResolver {

    private final SpaceManager spaces;

    ExchangeRefResolver(SpaceManager spaces) {
        this.spaces = spaces;
    }

    @Override
    public Optional<Path> resolveSnapshot(String owner, String item) {
        Exchange ex = Exchange.under(spaces.containerRoot());
        if (!ex.enabled()) return Optional.empty();
        String consumer = EventLog.currentSpaceId();
        Optional<ShareGrant> granted = ex.activeGrant(consumer, owner, "dataset", item);
        if (granted.isEmpty()) return Optional.empty();   // no active grant ⇒ does not resolve, even if files exist
        ShareGrant grant = granted.get();

        // Live mode (S3): read the owner's at-rest Table directory directly, read-only. Zero duplication,
        // always current — the query is a SELECT so nothing can write back.
        if (ShareGrant.LIVE.equals(grant.mode()))
            return ownerTableDir(owner, item);

        // Snapshot mode: the pinned version if the grant pins one (fail-closed if that version is gone),
        // else the live version the current.toon pointer names.
        Path itemDir = ExchangeSnapshots.itemDir(ex.dir(), owner, item);
        if (grant.pin() != null) {
            Path pinned = itemDir.resolve(grant.pin());
            return Files.isDirectory(pinned) ? Optional.of(pinned) : Optional.empty();
        }
        return ExchangeSnapshots.currentDir(itemDir).filter(Files::isDirectory);
    }

    /** The owner Space's at-rest Table directory for {@code item} (live-mode read target). */
    private Optional<Path> ownerTableDir(String owner, String item) {
        return spaces.space(SpaceId.of(owner))
                .map(ctx -> Path.of(ctx.root().dataDir()).resolve(item))
                .filter(Files::isDirectory);
    }
}
