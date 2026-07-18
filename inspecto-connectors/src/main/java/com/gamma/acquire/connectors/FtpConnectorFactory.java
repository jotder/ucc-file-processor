package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionWorkbench;
import com.gamma.acquire.CollectorConnector;
import com.gamma.acquire.CollectorConnectorFactory;
import com.gamma.etl.PipelineConfig;

/**
 * {@link CollectorConnectorFactory} for the {@code ftp} scheme (Phase E). Discovered via
 * {@code META-INF/services/com.gamma.acquire.CollectorConnectorFactory} when this module is on the classpath.
 */
public final class FtpConnectorFactory implements CollectorConnectorFactory {

    @Override
    public String scheme() {
        return "ftp";
    }

    @Override
    public CollectorConnector create(PipelineConfig cfg) {
        return create(cfg, null);
    }

    @Override
    public CollectorConnector create(PipelineConfig cfg, ConnectionProfile profile) {
        if (profile == null)
            throw new IllegalArgumentException("ftp source '" + cfg.collector().id()
                    + "' requires source.connection to reference a *_connection.toon profile (host/credentials)");
        return new FtpConnector(profile, cfg.collector().stability().readyMarker());
    }

    @Override
    public ConnectionWorkbench workbench(ConnectionProfile profile) {
        return FtpConnector.workbench(profile, TlsMode.NONE);
    }
}
