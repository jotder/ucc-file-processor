package com.gamma.acquire;

import com.gamma.api.PublicApi;
import com.gamma.etl.PipelineConfig;

/**
 * The plugin seam for additional source protocols. A connector is bound to one pipeline's config, so the
 * engine cannot {@code ServiceLoader} a connector instance directly — it loads <em>factories</em> and asks
 * the one whose {@link #scheme()} matches {@code source.connector} to build a connector from the config.
 *
 * <p>Register an implementation by listing it in
 * {@code META-INF/services/com.gamma.acquire.CollectorConnectorFactory} inside the connector module. The lean
 * core ships <b>no</b> factories — only the built-in {@link LocalFileSystemConnector}, resolved directly by
 * {@link CollectorConnectors#forConfig}. Remote connectors (SFTP/FTP/S3/…) live in the optional connector module
 * (roadmap Phase E) so the core's dependency surface stays minimal.
 */
@PublicApi(since = "4.0.0")
public interface CollectorConnectorFactory {

    /** The {@code source.connector} value this factory handles (e.g. {@code "sftp"}, {@code "s3"}). */
    String scheme();

    /** Build a connector bound to {@code cfg}'s {@code source:} block. */
    CollectorConnector create(PipelineConfig cfg);

    /**
     * Build a connector bound to {@code cfg}, given the {@link ConnectionProfile} its {@code source.connection}
     * resolved to (Phase E). A remote connector (SFTP/FTP/…) needs the profile's host / port / credentials /
     * base path; {@code profile} is {@code null} when the pipeline declares no {@code source.connection}. The
     * default ignores the profile and delegates to {@link #create(PipelineConfig)}, so existing factories that
     * read everything from {@code cfg} keep working unchanged.
     */
    default CollectorConnector create(PipelineConfig cfg, ConnectionProfile profile) {
        return create(cfg);
    }

    /**
     * Build a {@link ConnectionWorkbench} bound to a bare {@code profile} — the probe/explore/sample surface
     * behind the connection-workbench routes, which has no pipeline config to offer. The default returns
     * {@code null} (= not supported): the probe reports the graded checks as skipped and explore/sample
     * refuse, so a factory that never opts in loses nothing.
     */
    default ConnectionWorkbench workbench(ConnectionProfile profile) {
        return null;
    }
}
