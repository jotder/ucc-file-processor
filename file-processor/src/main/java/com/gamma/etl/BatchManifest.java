package com.gamma.etl;

import java.util.List;

/**
 * Serializable record of everything a batch produced, used by
 * {@code ura reprocess <batch_id>} to delete outputs/markers and restore members.
 *
 * <p>Plain mutable fields (not a record) for straightforward Gson (de)serialization.
 */
public final class BatchManifest {
    public String batchId;
    public String pipeline;
    public String schemaName;
    public String outputTable;     // null when writing directly to dirs.database
    public String createdAt;
    public List<MemberEntry> members;
    public List<OutputEntry> outputs;
    public List<String>      markers;

    /**
     * @param filename        member file name
     * @param srcId           0-based index within the batch
     * @param originalRelPath member path relative to the poll dir (for restore target)
     * @param backupPath      computed backup destination (where the source was moved)
     * @param status          SUCCESS or a QUARANTINED_* status
     */
    public record MemberEntry(String filename, int srcId, String originalRelPath,
                              String backupPath, String status) {}

    /**
     * @param partition  partition path, e.g. {@code "year=2020/month=04/day=03"}
     * @param outputFile absolute path of the produced output file
     */
    public record OutputEntry(String partition, String outputFile) {}
}
