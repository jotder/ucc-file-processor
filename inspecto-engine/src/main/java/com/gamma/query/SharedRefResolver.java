package com.gamma.query;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The seam by which {@link DatasetRelation} resolves a cross-Space {@code shared/<owner>/<item>} dataset
 * ref to a concrete, grant-checked on-disk snapshot directory — without the query layer depending on the
 * Exchange (the Exchange-backed implementation is installed at the HTTP edge, {@code ControlApi}). It
 * mirrors the process-wide singleton pattern used elsewhere ({@code EventLog.global()},
 * {@code MetricRegistry.global()}): resolution is by the calling thread's Space MDC, so the resolver reads
 * the current consumer Space itself and only needs {@code (owner, item)}.
 *
 * <p>Fail-closed: the default {@link #NONE} resolver (single-tenant, tests, un-wired) resolves nothing, so
 * a {@code shared/} ref is simply unusable until a real resolver is installed and an <em>active</em> grant
 * exists.
 */
public interface SharedRefResolver {

    /**
     * The at-rest snapshot data directory for {@code owner/item} as visible to the calling Space, or empty
     * when there is no active grant, no published snapshot, or sharing is unavailable.
     */
    Optional<Path> resolveSnapshot(String owner, String item);

    /** The fail-closed default: nothing resolves. */
    SharedRefResolver NONE = (owner, item) -> Optional.empty();

    /** Holder for the process-wide resolver (mutable install seam, mirroring the other engine singletons). */
    final class Holder {
        private static volatile SharedRefResolver current = NONE;
        private Holder() {}
    }

    /** Install the process-wide resolver (an Exchange-backed one at server start); {@code null} resets to {@link #NONE}. */
    static void install(SharedRefResolver resolver) {
        Holder.current = resolver == null ? NONE : resolver;
    }

    /** The active process-wide resolver ({@link #NONE} until one is installed). */
    static SharedRefResolver global() {
        return Holder.current;
    }
}
