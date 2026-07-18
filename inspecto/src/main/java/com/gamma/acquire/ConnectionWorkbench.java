package com.gamma.acquire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The connection-workbench SPI (Data Acquisition — connection profiles): the graded probe / explore /
 * sample verbs behind {@code POST /connections/{id}/probe}, {@code GET /connections/{id}/explore} and
 * {@code GET /connections/{id}/sample}. One instance is bound to one {@link ConnectionProfile} and holds
 * its session for its lifetime (mirrors {@link CollectorConnector}); callers close it when done.
 *
 * <p>The built-in {@link LocalConnectionWorkbench} serves {@code local} profiles; remote connectors opt in
 * via {@link CollectorConnectorFactory#workbench(ConnectionProfile)} (default {@code null} = unsupported,
 * which the probe reports as <em>skipped</em> checks and explore/sample refuse honestly). Orchestration —
 * reachability, secret resolution, check ordering — lives in {@link ConnectionProber}, so an implementation
 * only answers the connector-specific checks and the two browse verbs.
 *
 * <p>Secrets never travel through this surface: a probe reports whether {@code ${…}} references resolve,
 * never their values; sampled rows are preview-only and never persisted.
 */
public interface ConnectionWorkbench extends AutoCloseable {

    /**
     * Run one graded check beyond reachability ({@link ProbeCheck#AUTHENTICATE}/{@link ProbeCheck#READ}/
     * {@link ProbeCheck#WRITE}/{@link ProbeCheck#LIST} — {@link ProbeCheck#REACHABILITY} is answered
     * generically by {@link ConnectionProber} and never passed here). {@code sampleLimit} bounds the LIST
     * check's entry count. A check an implementation cannot answer returns a skipped outcome; a check that
     * was attempted and failed returns {@code ok=false} (or throws {@link AcquisitionException}, which the
     * prober converts).
     */
    CheckOutcome check(ProbeCheck check, int sampleLimit) throws AcquisitionException;

    /** Children of {@code path} (the connection root when blank). Read-only; permission-aware where possible. */
    List<ResourceNode> explore(String path) throws AcquisitionException;

    /** A bounded preview of the resource at {@code path} — first {@code limit} rows / file head. Never persisted. */
    SampleResult sample(String path, int limit) throws AcquisitionException;

    /** Release any held connection/session. The local workbench holds nothing; default is a no-op. */
    @Override
    default void close() throws AcquisitionException {}

    /** The graded probe checks (design §3). Wire names are the lower-case enum names. */
    enum ProbeCheck {
        REACHABILITY, AUTHENTICATE, READ, WRITE, LIST;

        public String wire() { return name().toLowerCase(Locale.ROOT); }

        /** Parse a wire name ({@code "read"}, …); throws {@link IllegalArgumentException} on an unknown one. */
        public static ProbeCheck fromWire(String s) {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        }
    }

    /** The outcome of one {@link ProbeCheck}. {@code skipped} = not attempted / not supported. */
    record CheckOutcome(ProbeCheck check, boolean ok, boolean skipped, String detail, Long latencyMs) {
        public static CheckOutcome ok(ProbeCheck c, String detail) { return new CheckOutcome(c, true, false, detail, null); }
        public static CheckOutcome fail(ProbeCheck c, String detail) { return new CheckOutcome(c, false, false, detail, null); }
        public static CheckOutcome skipped(ProbeCheck c, String detail) { return new CheckOutcome(c, false, true, detail, null); }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("check", check.wire());
            m.put("ok", ok);
            if (skipped) m.put("skipped", true);
            m.put("detail", detail);
            if (latencyMs != null) m.put("latencyMs", latencyMs);
            return m;
        }
    }

    /** One node in the resource-explore tree (files/dirs, or schema/table/column for DB connectors). */
    record ResourceNode(String name, String path, Kind kind, boolean hasChildren,
                        Long sizeBytes, String modifiedAt, Boolean readable, Boolean writable) {

        /** What an explore node is; wire names are the lower-case enum names. */
        public enum Kind { DIR, FILE, BUCKET, SCHEMA, TABLE, COLUMN }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("path", path);
            m.put("kind", kind.name().toLowerCase(Locale.ROOT));
            m.put("hasChildren", hasChildren);
            if (sizeBytes != null) m.put("sizeBytes", sizeBytes);
            if (modifiedAt != null) m.put("modifiedAt", modifiedAt);
            if (readable != null) m.put("readable", readable);
            if (writable != null) m.put("writable", writable);
            return m;
        }

        public static List<Map<String, Object>> toMaps(List<ResourceNode> nodes) {
            List<Map<String, Object>> out = new ArrayList<>(nodes.size());
            for (ResourceNode n : nodes) out.add(n.toMap());
            return out;
        }
    }

    /** A bounded sample extracted from a resource for preview. {@code truncated} = more data exists beyond it. */
    record SampleResult(String path, List<String> columns, List<Map<String, Object>> rows,
                        boolean truncated, String detail) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", path);
            m.put("columns", columns);
            m.put("rows", rows);
            m.put("truncated", truncated);
            if (detail != null) m.put("detail", detail);
            return m;
        }
    }

    /** Thrown when a user-supplied {@code path} escapes the connection's base path (HTTP edge maps it to 403). */
    final class PathEscape extends RuntimeException {
        public PathEscape(String message) { super(message); }
    }

    /** Thrown when a user-supplied {@code path} does not exist under the connection (HTTP edge maps it to 404). */
    final class NoSuchPath extends RuntimeException {
        public NoSuchPath(String message) { super(message); }
    }
}
