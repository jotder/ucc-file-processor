package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionWorkbench;
import com.gamma.acquire.CollectorConnector;
import com.gamma.acquire.CollectorConnectorFactory;
import com.gamma.etl.PipelineConfig;

/**
 * {@link CollectorConnectorFactory} for the {@code db} scheme (the DB-export source). Discovered via
 * {@code META-INF/services/com.gamma.acquire.CollectorConnectorFactory} when this module is on the classpath.
 */
public final class DbExportConnectorFactory implements CollectorConnectorFactory {

    @Override
    public String scheme() {
        return "db";
    }

    @Override
    public CollectorConnector create(PipelineConfig cfg) {
        return create(cfg, null);
    }

    @Override
    public CollectorConnector create(PipelineConfig cfg, ConnectionProfile profile) {
        if (profile == null)
            throw new IllegalArgumentException("db source '" + cfg.collector().id()
                    + "' requires source.connection to reference a *_connection.toon profile (jdbc_url/query/export_name)");
        return new DbExportConnector(profile);
    }

    @Override
    public ConnectionWorkbench workbench(ConnectionProfile profile) {
        return new DbConnectionWorkbench(profile);
    }
}
