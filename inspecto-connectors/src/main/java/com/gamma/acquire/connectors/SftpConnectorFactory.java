package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.SourceConnector;
import com.gamma.acquire.SourceConnectorFactory;
import com.gamma.etl.PipelineConfig;

/**
 * {@link SourceConnectorFactory} for the {@code sftp} scheme (Phase E). Discovered via
 * {@code META-INF/services/com.gamma.acquire.SourceConnectorFactory} when this module is on the classpath.
 */
public final class SftpConnectorFactory implements SourceConnectorFactory {

    @Override
    public String scheme() {
        return "sftp";
    }

    @Override
    public SourceConnector create(PipelineConfig cfg) {
        return create(cfg, null);
    }

    @Override
    public SourceConnector create(PipelineConfig cfg, ConnectionProfile profile) {
        if (profile == null)
            throw new IllegalArgumentException("sftp source '" + cfg.source().id()
                    + "' requires source.connection to reference a *_connection.toon profile (host/credentials)");
        return new SftpConnector(profile, cfg.source().stability().readyMarker());
    }
}
