package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.SourceConnector;
import com.gamma.acquire.SourceConnectorFactory;
import com.gamma.etl.PipelineConfig;

/**
 * {@link SourceConnectorFactory} for the {@code ftp} scheme (Phase E). Discovered via
 * {@code META-INF/services/com.gamma.acquire.SourceConnectorFactory} when this module is on the classpath.
 */
public final class FtpConnectorFactory implements SourceConnectorFactory {

    @Override
    public String scheme() {
        return "ftp";
    }

    @Override
    public SourceConnector create(PipelineConfig cfg) {
        return create(cfg, null);
    }

    @Override
    public SourceConnector create(PipelineConfig cfg, ConnectionProfile profile) {
        if (profile == null)
            throw new IllegalArgumentException("ftp source '" + cfg.source().id()
                    + "' requires source.connection to reference a *_connection.toon profile (host/credentials)");
        return new FtpConnector(profile, cfg.source().stability().readyMarker());
    }
}
