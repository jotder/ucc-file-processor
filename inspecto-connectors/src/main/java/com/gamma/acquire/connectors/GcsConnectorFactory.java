package com.gamma.acquire.connectors;

import com.gamma.acquire.CollectorConnector;
import com.gamma.acquire.CollectorConnectorFactory;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionWorkbench;
import com.gamma.etl.PipelineConfig;

/**
 * {@link CollectorConnectorFactory} for the {@code gcs} scheme (ACQ-4) — native Google Cloud Storage over the
 * JSON API with service-account OAuth 2.0. Discovered via
 * {@code META-INF/services/com.gamma.acquire.CollectorConnectorFactory} when this module is on the classpath.
 *
 * <p>(GCS is also reachable through the {@code s3} scheme in interoperability mode with HMAC keys; this factory
 * is the native path, taking a service-account key instead.)
 */
public final class GcsConnectorFactory implements CollectorConnectorFactory {

    @Override
    public String scheme() {
        return "gcs";
    }

    @Override
    public CollectorConnector create(PipelineConfig cfg) {
        return create(cfg, null);
    }

    @Override
    public CollectorConnector create(PipelineConfig cfg, ConnectionProfile profile) {
        if (profile == null)
            throw new IllegalArgumentException("gcs source '" + cfg.collector().id()
                    + "' requires source.connection to reference a *_connection.toon profile (bucket + service-account key)");
        return new GcsConnector(profile);
    }

    @Override
    public ConnectionWorkbench workbench(ConnectionProfile profile) {
        return GcsConnector.workbench(profile);
    }
}
