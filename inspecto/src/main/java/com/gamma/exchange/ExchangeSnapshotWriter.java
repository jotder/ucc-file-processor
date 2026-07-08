package com.gamma.exchange;

import com.gamma.exchange.ExchangeSnapshots.SnapshotMeta;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.ResultSetDescriptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes a versioned snapshot of an owner Space's Dataset into the Exchange — the S2 "no reprocessing"
 * data path. It resolves the owner's own Dataset ({@link DatasetRelation}, over the owner's local data
 * root — never a shared ref), {@code COPY}s it to a fresh
 * {@code <_shared>/exchange/<owner>/<item>/<version>/snapshot.parquet}, captures its Result Set metadata,
 * then flips {@code current.toon} to the new version ({@link ExchangeSnapshots}). Reuses the
 * stage-and-atomic-reveal discipline of {@code MaterializeTask}, but keyed by {@code <version>/} dirs so
 * old snapshots stay readable until the pointer moves; older versions are pruned to bound disk.
 */
public final class ExchangeSnapshotWriter {

    /** Version directories kept beyond the live one (bounds disk across refreshes). */
    private static final int KEEP_VERSIONS = 2;

    private ExchangeSnapshotWriter() {}

    /**
     * Refresh {@code item}'s snapshot from the owner Space's current data. Returns the new freshness.
     *
     * @param sharedDir     the {@code spaces/_shared/} dir (the Exchange root)
     * @param owner         the owning Space id
     * @param ownerRegistry the owner Space's component registry root ({@code <space>/config/registry})
     * @param ownerDataRoot the owner Space's data root (where its at-rest Parquet lives)
     * @param ownerViews    the owner Space's ViewStore root ({@code <space>/views})
     * @param item          the offered Dataset component id
     */
    public static SnapshotMeta publish(Path sharedDir, String owner, Path ownerRegistry,
                                       Path ownerDataRoot, Path ownerViews, String item) throws Exception {
        Map<String, Object> dataset = new ComponentStore(ownerRegistry).get("dataset", item)
                .map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new IllegalArgumentException("no dataset '" + item + "' to snapshot"));
        // The owner's own dataset, resolved against the owner's local data (SharedRefResolver is not involved).
        String relationSql = DatasetRelation.relationSql(dataset, ownerDataRoot, new ViewStore(ownerViews));

        Path itemDir = ExchangeSnapshots.itemDir(sharedDir, owner, item);
        String version = "v" + System.currentTimeMillis();
        Path verDir = itemDir.resolve(version);
        Files.createDirectories(verDir);
        Path tmp = verDir.resolve("snapshot.parquet.tmp");
        Path finalFile = verDir.resolve("snapshot.parquet");

        long rows;
        List<Map<String, Object>> columns;
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = conn.createStatement()) {
            st.execute("CREATE VIEW __src AS " + relationSql);
            st.execute("COPY (SELECT * FROM __src) TO " + sqlStr(unix(tmp)) + " (FORMAT PARQUET)");
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM read_parquet(" + sqlStr(unix(tmp)) + ")")) {
                rs.next();
                rows = rs.getLong(1);
            }
            columns = describe(st, unix(tmp));
        }
        Files.move(tmp, finalFile, StandardCopyOption.ATOMIC_MOVE);

        SnapshotMeta meta = new SnapshotMeta(version, rows, Instant.now().toString(), columns);
        ExchangeSnapshots.writeCurrent(itemDir, meta);        // atomic reveal
        ExchangeSnapshots.prune(itemDir, version, KEEP_VERSIONS);
        return meta;
    }

    /** Result Set columns (name, coarse type, analytic role) read from the just-written snapshot's schema. */
    private static List<Map<String, Object>> describe(Statement st, String parquetGlob) throws Exception {
        List<String> names = new ArrayList<>();
        List<Integer> types = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT * FROM read_parquet(" + sqlStr(parquetGlob) + ") LIMIT 0")) {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                names.add(md.getColumnLabel(i));
                types.add(md.getColumnType(i));
            }
        }
        List<Map<String, Object>> cols = new ArrayList<>();
        for (ResultSetDescriptor.Column c : ResultSetDescriptor.describe(names, types, List.of())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("type", c.type());
            m.put("role", c.role());
            cols.add(m);
        }
        return cols;
    }

    private static String unix(Path p) {
        return p.toString().replace('\\', '/');
    }

    private static String sqlStr(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
