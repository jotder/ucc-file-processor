package com.gamma.job;

import java.util.List;

/**
 * The shape of a Job-produced Dataset — the glossary §6-B <em>Result Set</em> (R7, §10): its ordered
 * columns, each with a SQL type and an analytic {@link Role}. Recorded on a dataset {@link RunArtifact}
 * so the output is immediately describable (and, later, bindable by Widgets / Show-Me, which match on
 * this same shape). Serialized as part of the artifact JSONL.
 */
public record ResultSetMeta(List<Column> columns) {

    /** The analytic role of a column (drives Widget/measure binding). */
    public enum Role { DIMENSION, MEASURE, TEMPORAL }

    public record Column(String name, String sqlType, Role role) {}

    public ResultSetMeta {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
