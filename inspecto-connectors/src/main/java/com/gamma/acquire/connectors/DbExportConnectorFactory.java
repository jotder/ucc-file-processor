package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.SourceConnector;
import com.gamma.acquire.SourceConnectorFactory;
import com.gamma.etl.PipelineConfig;

/**
 * {@link SourceConnectorFactory} for the {@code db} scheme (the DB-export source). Discovered via
 * {@code META-INF/services/com.gamma.acquire.SourceConnectorFactory} when this module is on the classpath.
 */
public final class DbExportConnectorFactory implements SourceConnectorFactory {

    @Override
    public String scheme() {
        return "db";
    }

    @Override
    public SourceConnector create(PipelineConfig cfg) {
        return create(cfg, null);
    }

    @Override
    public SourceConnector create(PipelineConfig cfg, ConnectionProfile profile) {
        if (profile == null)
            throw new IllegalArgumentException("db source '" + cfg.source().id()
                    + "' requires source.connection to reference a *_connection.toon profile (jdbc_url/query/export_name)");
        return new DbExportConnector(profile);
    }
}
