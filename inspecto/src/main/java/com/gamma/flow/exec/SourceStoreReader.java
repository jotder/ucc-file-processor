package com.gamma.flow.exec;

import com.gamma.api.PublicApi;
import com.gamma.sql.SqlViews;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * <b>T32 Phase A — seed a flow job's {@code source_store} as a DuckDB view.</b> A flow run as a
 * {@link com.gamma.job.JobType#FLOW} job reads data already at rest (the {@code source_store} a node
 * declares, §3.8) rather than re-acquiring it; this helper registers that store's Hive-partitioned
 * dataset as a view the {@link FlowExecutor} can seed from, exactly as the Stage-2
 * {@link com.gamma.enrich.EnrichmentEngine} registers its {@code input} view.
 *
 * <p>The store lives under {@code <dataDir>/<store>}; it is read with the shared {@link SqlViews}
 * idiom so a flow job validates against the same dataset shape a real run produces.
 */
@PublicApi(since = "4.3.0")
public final class SourceStoreReader {

    private SourceStoreReader() {}

    /**
     * Register {@code <dataDir>/<store>/**}{@code /*.<ext>} as a DuckDB view named {@code viewName}.
     *
     * @param conn     the DuckDB connection to register the view on
     * @param viewName the view name (the {@link FlowExecutor} seed table)
     * @param dataDir  the data root under which each store is a sub-directory
     * @param store    the {@code source_store} name to read
     * @param format   {@code "PARQUET"} or {@code "CSV"} (the at-rest store format)
     */
    public static void registerView(Connection conn, String viewName, String dataDir,
                                    String store, String format) throws SQLException {
        String glob = dataDir.replace("\\", "/") + "/" + store + "/**/*." + SqlViews.ext(format);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE VIEW \"" + viewName + "\" AS SELECT * FROM "
                    + SqlViews.reader(format, glob, true));
        }
    }
}
