package com.gamma.ops.link;

import java.util.List;

/**
 * Persistence seam for {@link ObjectLink}s — the correlation-graph store of the Operational
 * Intelligence Platform (Phase 4). The counterpart to {@link com.gamma.ops.ObjectStore}, but with the
 * append-only contract of {@link com.gamma.event.EventStore}: links are immutable facts, so there is
 * no {@code update}/{@code delete} — only {@link #add} and reads.
 *
 * <p>Two implementations sit behind it, exactly mirroring the object store: {@link InMemoryLinkStore}
 * (the lean default) and {@link DbLinkStore} (durable JDBC over the bundled DuckDB, or a Postgres URL).
 * The backend follows the same deployment toggle as the object store ({@code -Dobjects.backend});
 * {@link com.gamma.ops.ObjectService} and the Control API depend only on this interface.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>{@link #incident(String)} and {@link #all(int)} return links <b>newest-first</b> (by {@code createdAt}).</li>
 *   <li>Implementations must be thread-safe.</li>
 * </ul>
 *
 * @since 4.5.0
 */
@com.gamma.api.PublicApi(since = "4.5.0")
public interface LinkStore extends AutoCloseable {

    /** Append a link and return it. */
    ObjectLink add(ObjectLink link);

    /** Every link touching {@code objectId} at either end, newest-first (the neighbourhood for traversal). */
    List<ObjectLink> incident(String objectId);

    /** The newest {@code limit} links across the whole store (diagnostics/tests), newest-first. */
    List<ObjectLink> all(int limit);

    /** Release resources (e.g. the DuckDB connection). Idempotent; no-op for in-memory. */
    @Override
    default void close() {}
}
