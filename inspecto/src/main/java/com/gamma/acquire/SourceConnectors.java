package com.gamma.acquire;

import com.gamma.etl.PipelineConfig;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.ServiceLoader;

/**
 * Resolves the {@link SourceConnector} for a pipeline from its {@code source.connector} scheme.
 *
 * <p>{@code "local"} (and the legacy no-{@code source:}-block case, which defaults to {@code "local"}) is
 * served by the built-in {@link LocalFileSystemConnector}, constructed from {@code dirs.poll}/{@code errors}/
 * {@code quarantine}. Any other scheme is looked up among the {@link SourceConnectorFactory} services on the
 * classpath — so a connector module contributes new protocols without touching the core. An unknown scheme
 * fails fast with a clear message.
 */
public final class SourceConnectors {

    private SourceConnectors() {}

    public static SourceConnector forConfig(PipelineConfig cfg) {
        String scheme = cfg.source().connector();
        if (scheme == null || scheme.isBlank() || scheme.equalsIgnoreCase("local")) {
            Path poll = abs(cfg.dirs().poll());
            Path errors = abs(cfg.dirs().errors());
            Path quarantine = abs(cfg.dirs().quarantine());
            // ready_marker (Phase B) makes the local connector answer readiness natively (READY iff the
            // sibling marker exists); null when no source.stability block ⇒ readiness UNKNOWN as before.
            return new LocalFileSystemConnector(poll, errors, quarantine, cfg.source().stability().readyMarker());
        }
        // Resolve the source.connection binding (if any) against the process-wide registry the service
        // publishes into, so the static poll path can hand a remote connector its host/credentials/base path.
        ConnectionProfile profile = cfg.source().hasConnection()
                ? ConnectionRegistry.find(cfg.source().connection()).orElse(null)
                : null;
        for (SourceConnectorFactory f : ServiceLoader.load(SourceConnectorFactory.class)) {
            if (f.scheme().equalsIgnoreCase(scheme)) return f.create(cfg, profile);
        }
        throw new IllegalArgumentException(
                "No source connector registered for connector '" + scheme + "' (built-in: local). "
                        + "Remote connectors ship in the optional connector module (Data Acquisition roadmap Phase E).");
    }

    private static Path abs(String dir) {
        return Paths.get(dir).toAbsolutePath().normalize();
    }
}
