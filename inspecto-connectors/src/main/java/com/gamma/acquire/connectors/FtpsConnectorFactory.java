package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.CollectorConnector;
import com.gamma.acquire.CollectorConnectorFactory;
import com.gamma.etl.PipelineConfig;

/**
 * {@link CollectorConnectorFactory} for the {@code ftps} scheme — FTP over TLS (security hardening). Reuses
 * {@link FtpConnector}, defaulting to {@link TlsMode#EXPLICIT} (FTPES); a profile's {@code options.tls=implicit}
 * still switches to implicit FTPS. Discovered via {@code META-INF/services} when this module is on the classpath.
 */
public final class FtpsConnectorFactory implements CollectorConnectorFactory {

    @Override
    public String scheme() {
        return "ftps";
    }

    @Override
    public CollectorConnector create(PipelineConfig cfg) {
        return create(cfg, null);
    }

    @Override
    public CollectorConnector create(PipelineConfig cfg, ConnectionProfile profile) {
        if (profile == null)
            throw new IllegalArgumentException("ftps source '" + cfg.collector().id()
                    + "' requires source.connection to reference a *_connection.toon profile (host/credentials)");
        return new FtpConnector(profile, cfg.collector().stability().readyMarker(), TlsMode.EXPLICIT);
    }
}
