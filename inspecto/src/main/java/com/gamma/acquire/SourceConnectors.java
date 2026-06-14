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
            return new LocalFileSystemConnector(poll, errors, quarantine);
        }
        for (SourceConnectorFactory f : ServiceLoader.load(SourceConnectorFactory.class)) {
            if (f.scheme().equalsIgnoreCase(scheme)) return f.create(cfg);
        }
        throw new IllegalArgumentException(
                "No source connector registered for connector '" + scheme + "' (built-in: local). "
                        + "Remote connectors ship in the optional connector module (Data Acquisition roadmap Phase E).");
    }

    private static Path abs(String dir) {
        return Paths.get(dir).toAbsolutePath().normalize();
    }
}
