What you are describing is no longer just a "file reader." It is becoming a **Data Acquisition Framework** or **File Collection Engine**.

---

# Data Acquisition & File Collection Requirements

  * GOAL: The system guarantees that every eligible source file is collected exactly once (or according to policy), safely, efficiently, and recoverably regardless of where the file resides.


## 1. Source Connectivity
The system shall support collecting files from multiple source types through a pluggable connector architecture.

**Supported Sources**
* **Local File System**
  * `inbox/<data_source>`
* **Remote File Systems**
  * FTP
  * SFTP
  * SSH/SCP
  * SMB/CIFS (future)
  * NFS (future)
* **Object Storage**
  * Amazon S3
  * Google Cloud Storage
  * Azure Blob Storage (future)
  * MinIO (future)

**Extensibility**
New protocols shall be introduced through connector plugins without modifying the core engine.

---

## 2. File Discovery
The system shall identify candidate files available for processing.

**Capabilities**
* **New File Detection:** Detect newly arrived files.
  * Methods: directory listing, timestamp comparison, object version comparison, event notification (future).
* **Incremental Discovery:** Collect only files newer than the previous successful scan.
  * Maintain: `last_scan_timestamp`, `last_processed_marker`, `high_watermark`.
* **Pattern Filtering:** Support `*.csv`, `*.gz`, `CDR_*.dat`, regex patterns, and exclude patterns.
* **Recursive Scanning:** Support configurable depth (e.g., `inbox/*`, `inbox/*/*`).

---

## 3. File Stability Detection
*One of the biggest production problems. The system shall avoid processing partially copied files.*

**Stability Checks**
* **Size Stabilization:** File size remains unchanged for N consecutive checks (e.g., 60 sec apart × 2 checks).
* **Last Modified Stabilization:** Ignore files modified within a `stability_window` (e.g., 5 minutes).
* **Temporary File Exclusion:** Ignore `*.tmp`, `*.partial`, `*.filepart`, `.~lock.*`.
* **Remote Upload Detection:** Connector-specific mechanisms.
  * SFTP: upload completed marker
  * S3: object finalized
  * GCS: generation finalized

---

## 4. Duplicate Prevention
The system shall prevent reprocessing.

**Marker Repository**
Maintain fingerprints. Example: `source_id`, `file_path`, `file_name`, `size`, `checksum`, `last_modified`, `processed_timestamp`, `status`.

**Duplicate Detection Modes**
* **Path Based:** same path
* **Metadata Based:** same filename, same size, same timestamp
* **Checksum Based:** MD5, SHA-256, ETag, CRC32

**Configurable Policy**
Example: `SKIP`, `REPROCESS`, `VERSION`, `FAIL`.

---

## 5. File Change Detection
*Sometimes files get replaced. The system shall detect changes after discovery.*

**Detect**
* **Modified Files:** Same filename, different checksum.
* **Re-uploaded Files:** File removed and uploaded again.
* **Object Version Changes:** For S3/GCS.

**Policies**
`IGNORE`, `REPROCESS`, `ALERT`, `ARCHIVE_OLD_VERSION`.

---

## 6. Collection Guarantees
*This is extremely important.*

**Modes**
* **Best Effort:** Process whatever is found.
* **At Least Once:** No files missed. Duplicates possible.
* **Exactly Once (Logical):** No file missed. No duplicate processing. Marker repository required.

**Gap Detection**
Identify missing files.
* Example Expected: `CDR_2026061301`, `CDR_2026061302`, `CDR_2026061303`
* Example Actual: `CDR_2026061301`, `CDR_2026061303`
* Raise: `missing sequence alert`

---

## 7. File Retrieval
The system shall retrieve discovered files.

**Retrieval Modes**
* **Direct Streaming:** Process without local copy. Suitable for S3, GCS, FTP.
* **Stage Then Process:** remote → local staging → processing.
* **Chunked Download:** Large files. Support resume.

**Compression Support**
Automatically handle: `.gz`, `.zip`, `.tar.gz`, `.bz2`.

---

## 8. Parallel Fetching
Improve acquisition throughput.

**Capabilities**
* **Multi-threaded Download:** Configurable (e.g., `max_parallel_fetch=20`).
* **Connector Parallelism:** Per source (e.g., FTP: 5, S3: 50, GCS: 30).
* **Multipart Download:** Support where applicable (e.g., S3 multipart, GCS parallel composite download).
* **Rate Limiting:** Prevent source overload.

---

## 9. Post Processing Actions
After successful processing.

**Supported Actions**
* **Retain:** Leave source unchanged.
* **Delete:** Remove source file. Only if supported.
* **Move:** Move to backup/archive (e.g., `archive/yyyy/MM/dd/`).
* **Rename:** e.g., `processed_<filename>`.
* **Tag:** Object storage metadata tagging.

**Unsupported Action Handling**
Example: Delete requested on read-only source.
Policy: `FAIL`, `WARN_AND_CONTINUE`, `IGNORE`.

---

## 10. Failure Handling
The acquisition layer shall tolerate transient failures.

* **Connectivity Failures:** Handle connection refused, timeout, authentication failure, DNS failure, server unavailable.
* **Retry Policies:** Configurable (e.g., `retry_count=5`, `backoff=EXPONENTIAL`, `initial_delay=30s`, `max_delay=15m`).
* **Resume Support:** Continue interrupted downloads.
* **Circuit Breaker:** Temporarily stop polling failing sources.
* **Dead Letter Handling:** Persist acquisition failures (e.g., `FAILED_FETCH`, `FAILED_DELETE`, `FAILED_MOVE`, `CORRUPT_DOWNLOAD`).

---

## 11. Integrity Verification
Ensure retrieved files are valid.

**Verification Methods**
* **Size Validation:** Source size equals local size.
* **Checksum Validation:** MD5, SHA-256, ETag, CRC32.
* **Optional Content Validation:** e.g., CSV header validation, ASN.1 decode probe, Parquet footer validation.

---

## 12. Observability
Operational visibility.

**Metrics**
`files_discovered`, `files_downloaded`, `files_processed`, `duplicates_skipped`, `downloads_failed`, `bytes_transferred`, `average_fetch_time`, `active_connections`.

**Audit Trail**
Capture lifecycle: `DISCOVERED`, `STABLE`, `FETCHED`, `VALIDATED`, `PROCESSED`, `ARCHIVED`, `FAILED`.

**Alerting**
Generate alerts for: repeated failures, missing expected files, prolonged source inactivity, excessive duplicates, connectivity degradation.

---

## 13. Configuration Example

```yaml
source:
  id: CDR_SFTP
  connector: SFTP
  path: /cdr/outbox
  include: "*.dat"
  stability_window: 300s
  duplicate_policy: CHECKSUM
  processing_guarantee: EXACTLY_ONCE
  fetch_mode: STAGE
  parallel_fetch: 10
  post_action: MOVE
  archive_path: archive/yyyy/MM/dd
  retry:
    count: 5
    strategy: EXPONENTIAL
  integrity:
    checksum: SHA256
  gap_detection:
    enabled: true
```

---

## Design Philosophy
Instead of building a "directory poller," define this component as a Data Acquisition Engine with five responsibilities:
1. **Discover** eligible files.
2. **Determine** readiness (stable and complete).
3. **Guarantee** collection semantics (exactly-once, gap detection).
4. **Retrieve** and validate data efficiently.
5. **Finalize** and audit every file lifecycle event.

This framing will help your agent generate requirements that scale from today's `inbox/<source>` folders to tomorrow's multi-protocol, high-volume enterprise ingestion platform without redesigning the core architecture.
