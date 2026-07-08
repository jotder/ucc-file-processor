package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.SourceConnector;
import com.gamma.acquire.SourceConnectorFactory;
import com.gamma.etl.PipelineConfig;

/**
 * {@link SourceConnectorFactory} for the {@code azure} scheme (ACQ-4) — Azure Blob Storage (and
 * Azurite/emulator endpoints). Discovered via {@code META-INF/services/com.gamma.acquire.SourceConnectorFactory}
 * when this module is on the classpath.
 */
public final class AzureBlobConnectorFactory implements SourceConnectorFactory {

    @Override
    public String scheme() {
        return "azure";
    }

    @Override
    public SourceConnector create(PipelineConfig cfg) {
        return create(cfg, null);
    }

    @Override
    public SourceConnector create(PipelineConfig cfg, ConnectionProfile profile) {
        if (profile == null)
            throw new IllegalArgumentException("azure source '" + cfg.source().id()
                    + "' requires source.connection to reference a *_connection.toon profile (account/key/container)");
        return new AzureBlobConnector(profile);
    }
}
