package com.gamma.control;

import com.gamma.event.EventLog;
import com.gamma.exchange.Exchange;
import com.gamma.exchange.ExchangeSnapshots;
import com.gamma.query.SharedRefResolver;
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
        if (ex.resolveForConsumer(consumer, owner, "dataset", item).isEmpty())
            return Optional.empty();   // no active grant ⇒ does not resolve, even if files exist
        return ExchangeSnapshots.currentDir(ExchangeSnapshots.itemDir(ex.dir(), owner, item))
                .filter(Files::isDirectory);
    }
}
