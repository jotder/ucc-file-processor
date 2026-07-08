package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.SourceConnector;
import com.gamma.acquire.SourceConnectorFactory;
import com.gamma.etl.PipelineConfig;

/**
 * {@link SourceConnectorFactory} for the {@code s3} scheme (ACQ-4) — AWS S3, MinIO, and any S3-compatible
 * store. Discovered via {@code META-INF/services/com.gamma.acquire.SourceConnectorFactory} when this module
 * is on the classpath.
 */
public final class S3ConnectorFactory implements SourceConnectorFactory {

    @Override
    public String scheme() {
        return "s3";
    }

    @Override
    public SourceConnector create(PipelineConfig cfg) {
        return create(cfg, null);
    }

    @Override
    public SourceConnector create(PipelineConfig cfg, ConnectionProfile profile) {
        if (profile == null)
            throw new IllegalArgumentException("s3 source '" + cfg.source().id()
                    + "' requires source.connection to reference a *_connection.toon profile (endpoint/credentials)");
        return new S3Connector(profile);
    }
}
