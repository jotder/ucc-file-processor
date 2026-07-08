package com.gamma.service;

import com.gamma.acquire.AcquisitionLedgers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Per-space analogue of {@link ServiceBootstrap#build(String[])}: builds one {@link SpaceContext} by scanning a
 * space's own {@code config/} directory (via {@link SpaceRoot#config()}) instead of CLI args, reusing the same
 * suffix loaders. The returned context is <em>not</em> started — {@code SpaceManager} arms it.
 *
 * <p>Unlike the CLI bootstrap, an empty {@code config/} is allowed (a freshly created space has no data sources
 * yet). Registers the space's own {@link com.gamma.acquire.AcquisitionLedger} so a {@code db}-backed dedup ledger
 * gets its own DuckDB file under the space (the default {@code memory} backend is already per-space isolated).
 */
final class SpaceBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SpaceBootstrap.class);

    private SpaceBootstrap() {}

    static SpaceContext load(SpaceRoot root) throws IOException {
        SpaceId id = SpaceId.of(root.id());
        SpaceContext.SpaceManifest manifest =
                SpaceContext.SpaceManifest.read(root.base().resolve("space.toon"), id.value());

        // Advisory storage-layout-contract check (WARN-only; never blocks boot).
        SpaceLayoutContract.verify(root);

        // Discover the space's configs from its own config/ tree; an empty space is allowed (no exit).
        SourceService service = ServiceBootstrap.buildFrom(root, new String[]{root.config().toString()}, false);

        // The space's own acquisition (dedup) ledger — its own DuckDB file when the backend is `db`; the default
        // memory backend yields an isolated in-memory instance. Keyed by space id (the poll path resolves by MDC).
        String ledgerUrl = System.getProperty("acquire.ledger.db.url", root.acquisitionLedgerDbUrl());
        AcquisitionLedgers.register(id.value(), AcquisitionLedgers.build(ledgerUrl));

        log.info("Space '{}' loaded ({} pipeline(s)) from {}",
                id, service.pipelines().size(), root.config());
        return new SpaceContext(id, root, manifest, service);
    }
}
