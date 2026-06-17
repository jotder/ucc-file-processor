package com.gamma.flow.exec;

import com.gamma.flow.FlowNode;
import com.gamma.flow.FlowRel;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T10 — {@link RowShaper}: each row-shaping operator runs as SQL over an embedded-DuckDB input relation and
 * produces the expected named output relations + rows. Covers the predicate split (filter/validate), case
 * vs clone routing, dedup QUALIFY, split UNNEST, projection (map/select/derive), chain-fusion, and merge.
 */
class RowShaperTest {

    private File db;
    private Connection conn;

    @BeforeEach
    void open() throws Exception {
        db = DuckDbUtil.tempDbFile("rs_");
        conn = DuckDbUtil.openConnection(db);
    }

    @AfterEach
    void close() throws Exception {
        if (conn != null) conn.close();
        DuckDbUtil.deleteTempDb(db);
    }

    /** src(id INT, grp VARCHAR, amt INT): rows (1,a,150) (2,b,50) (3,a,200). */
    private void seedSrc() throws SQLException {
        sql("CREATE TABLE src AS SELECT * FROM (VALUES (1,'a',150),(2,'b',50),(3,'a',200)) t(id,grp,amt)");
    }

    private RelationByRel run(FlowNode node) throws SQLException {
        return new RelationByRel(RowShaper.shape(conn, node, "src", node.id()));
    }

    @Test
    void filterSplitsKeptAndDropped() throws Exception {
        seedSrc();
        var out = run(FlowNode.of("f", "transform.filter", Map.of("where", "amt >= 100")));
        assertEquals(List.of(1, 3), ids(out.table(FlowRel.DATA), "id"));
        assertEquals(List.of(2), ids(out.table(FlowRel.DROPPED), "id"));
    }

    @Test
    void validateSplitsValidAndInvalid_nullPredicateGoesInvalid() throws Exception {
        // grp='a' for ids 1,3; NULLIF makes id 2's predicate NULL -> must land in invalid, not data
        seedSrc();
        var out = run(FlowNode.of("v", "transform.validate",
                Map.of("rule", "CASE WHEN grp='b' THEN NULL ELSE grp='a' END")));
        assertEquals(List.of(1, 3), ids(out.table(FlowRel.DATA), "id"));
        assertEquals(List.of(2), ids(out.table(FlowRel.INVALID), "id"));
    }

    @Test
    void routeCaseIsExclusiveWithDefault() throws Exception {
        seedSrc();
        var out = run(FlowNode.of("r", "transform.route", Map.of(
                "mode", "case",
                "branches", List.of(Map.of("key", "agrp", "where", "grp='a'")),
                "default", "other")));
        assertEquals(List.of(1, 3), ids(out.table(FlowRel.route("agrp")), "id"));
        assertEquals(List.of(2), ids(out.table(FlowRel.route("other")), "id"));
    }

    @Test
    void routeCloneAllowsOverlap() throws Exception {
        seedSrc();
        var out = run(FlowNode.of("r", "transform.route", Map.of(
                "mode", "clone",
                "branches", List.of(
                        Map.of("key", "big", "where", "amt >= 100"),
                        Map.of("key", "agrp", "where", "grp='a'")))));
        assertEquals(List.of(1, 3), ids(out.table(FlowRel.route("big")), "id"));
        assertEquals(List.of(1, 3), ids(out.table(FlowRel.route("agrp")), "id"));   // id1,3 appear on both
    }

    @Test
    void dedupKeepsFirstPerKeyByOrder() throws Exception {
        seedSrc();
        var out = run(FlowNode.of("d", "transform.dedup",
                Map.of("keys", List.of("grp"), "order_by", "amt DESC")));
        // grp a -> id3 (amt200), grp b -> id2; the loser id1 is a duplicate
        assertEquals(List.of(2, 3), ids(out.table(FlowRel.DATA), "id"));
        assertEquals(List.of(1), ids(out.table(FlowRel.DUPLICATE), "id"));
    }

    @Test
    void splitUnnestsAListColumn() throws Exception {
        sql("CREATE TABLE src AS SELECT * FROM (VALUES (1, ['x','y']), (2, ['z'])) t(id, tags)");
        var out = run(FlowNode.of("s", "transform.split", Map.of("column", "tags", "as", "tag")));
        assertEquals(3, count(out.table(FlowRel.DATA)));
        assertEquals(List.of(1, 1, 2), ids(out.table(FlowRel.DATA), "id"));
    }

    @Test
    void projectMapSelectDerive() throws Exception {
        seedSrc();
        var map = run(FlowNode.of("m", "transform.map",
                Map.of("columns", List.of(Map.of("name", "id10", "expr", "id*10")))));
        assertEquals(List.of("id10"), columns(map.table(FlowRel.DATA)));

        var sel = run(FlowNode.of("se", "transform.select", Map.of("columns", List.of("id", "amt"))));
        assertEquals(List.of("amt", "id"), columns(sel.table(FlowRel.DATA)).stream().sorted().toList());

        var der = run(FlowNode.of("de", "transform.derive",
                Map.of("columns", List.of(Map.of("name", "amt2", "expr", "amt*2")))));
        assertTrue(columns(der.table(FlowRel.DATA)).containsAll(List.of("id", "grp", "amt", "amt2")));
    }

    @Test
    void fuseFiltersAndProjectionIntoOnePass() throws Exception {
        seedSrc();
        RowShaper.Relation r = RowShaper.fuse(conn, List.of(
                FlowNode.of("f", "transform.filter", Map.of("where", "amt >= 100")),
                FlowNode.of("m", "transform.map", Map.of("columns",
                        List.of(Map.of("name", "id", "expr", "id"), Map.of("name", "amt", "expr", "amt"))))
        ), "src", "chain");
        assertEquals(FlowRel.DATA, r.rel());
        assertEquals(List.of(1, 3), ids(r.table(), "id"));                 // filtered
        assertEquals(List.of("amt", "id"), columns(r.table()).stream().sorted().toList());   // projected
    }

    @Test
    void mergeUnionAndJoin() throws Exception {
        sql("CREATE TABLE a AS SELECT * FROM (VALUES (1,'x'),(2,'y')) t(id,v)");
        sql("CREATE TABLE b AS SELECT * FROM (VALUES (3,'z')) t(id,v)");
        var union = RowShaper.merge(conn, FlowNode.of("u", "transform.merge", Map.of("type", "union")),
                List.of("a", "b"), "u");
        assertEquals(3, count(union.get(0).table()));

        sql("CREATE TABLE l AS SELECT * FROM (VALUES (1,10),(2,20)) t(id,amt)");
        sql("CREATE TABLE rdim AS SELECT * FROM (VALUES (1,'AA')) t(id,code)");
        var join = RowShaper.merge(conn, FlowNode.of("j", "transform.merge",
                        Map.of("type", "inner", "on", List.of("id"))),
                List.of("l", "rdim"), "j");
        assertEquals(1, count(join.get(0).table()));   // only id=1 matches
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Lookup of produced relations by their FlowRel for terse assertions. */
    private record RelationByRel(List<RowShaper.Relation> rels) {
        String table(String rel) {
            return rels.stream().filter(r -> r.rel().equals(rel)).map(RowShaper.Relation::table).findFirst()
                    .orElseThrow(() -> new AssertionError("no relation '" + rel + "' in " + rels));
        }
    }

    private void sql(String s) throws SQLException {
        try (Statement st = conn.createStatement()) { st.execute(s); }
    }

    private int count(String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private List<Integer> ids(String table, String col) throws SQLException {
        List<Integer> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT \"" + col + "\" FROM \"" + table + "\" ORDER BY 1")) {
            while (rs.next()) out.add(rs.getInt(1));
        }
        return out;
    }

    private List<String> columns(String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM \"" + table + "\" LIMIT 0")) {
            var md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) out.add(md.getColumnName(i));
        }
        return out;
    }
}
