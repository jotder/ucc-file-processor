package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.CollectorConnector;
import com.gamma.acquire.CollectorConnectorFactory;
import com.gamma.etl.PipelineConfig;

/**
 * {@link CollectorConnectorFactory} for the {@code azure} scheme (ACQ-4) — Azure Blob Storage (and
 * Azurite/emulator endpoints). Discovered via {@code META-INF/services/com.gamma.acquire.CollectorConnectorFactory}
 * when this module is on the classpath.
 */
public final class AzureBlobConnectorFactory implements CollectorConnectorFactory {

    @Override
    public String scheme() {
        return "azure";
    }

    @Override
    public CollectorConnector create(PipelineConfig cfg) {
        return create(cfg, null);
    }

    @Override
    public CollectorConnector create(PipelineConfig cfg, ConnectionProfile profile) {
        if (profile == null)
            throw new IllegalArgumentException("azure source '" + cfg.collector().id()
                    + "' requires source.connection to reference a *_connection.toon profile (account/key/container)");
        return new AzureBlobConnector(profile);
    }
}
