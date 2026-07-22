# Data Acquisition & File Collection Requirements
> *Moved from `docs/data_acquisition_framework.md` (docs consolidation, 2026-07-16).*

> **Status (2026-06-15): the framework is built — Phases A–F shipped on `4.x`.** This document is the original
> *requirement*; the as-built design + phase log live in
> [`docs/archived-documents/superpowers/specs/2026-06-14-data-acquisition-framework-roadmap.md`](../../../archived-documents/superpowers/specs/2026-06-14-data-acquisition-framework-roadmap.md),
> and the operator-facing config/runbook are in
> [`configuration.md`](../config/configuration.md#data-acquisition--the-source-block) and
> [`integrations.md`](../integrations.md#remote-source-connectors-sftp--ftp).
> **Delivered:** the `CollectorConnector` SPI (`com.gamma.acquire`) + local parity (A); readiness/stability gate (B);
> fingerprint ledger + content dedup (C); collection-guarantee knob + sequence-gap→alert (D); **SFTP + FTP**
> connectors in the optional `inspecto-connectors` module + connection profiles + integrity + `.bz2`/`.zip` (E);
> retry/circuit-breaker/dead-letter + source-side post-actions + parallel fetch + rate limit (F).
> **Plus (post-roadmap, 2026-06-15):** the **DB-export source** (`DbExportConnector`, scheme `db`) — runs a SQL
> query against a JDBC database (Postgres driver bundled; JDBC-generic) and materialises the result as CSV, with
> date-templated query/name and an optional SSH tunnel; and the **C4 incremental high-watermark**
> (`source.incremental.watermark: last_modified`) — each scan skips files modified before the source's
> high-watermark (max `last_modified` recorded in the fingerprint ledger), so remote sources spend no fetch
> bandwidth re-collecting history; the **DB-export row-level watermark** (`options.watermark_column` +
> a `:watermark` bind in the query) — resumable incremental export of only rows newer than the last *committed*
> run (advances post-commit ⇒ at-least-once on crash), which the file-level C4 watermark can't do for DB sources;
> and **connector hardening** — **FTPS** (`connector: ftps`, or
> `options.tls: explicit|implicit`; encrypts control + data channels), **strict SSH host-key pinning**
> (`options.host_key`/`known_hosts`/`strict_host_key`), and **FTP/FTPS through an SSH bastion**
> (`tunnel:` + `options.passive_ports` for the passive data range) — so all of SFTP/FTP/FTPS/DB-export can
> traverse a bastion.
> **Shipped 2026-07-08 (ACQ-4/ACQ-7):** the **`s3` connector** — the S3 REST API spoken directly over the JDK
> `HttpClient` with in-tree SigV4 signing (**no AWS SDK**; air-gap + SBOM preserved). One connector covers
> AWS S3, MinIO, and any S3-compatible store (GCS interoperability mode included); path-style addressing.
> Profile: `host`/`port` = endpoint, `username`/`password` = access/secret key (SecretResolver reference),
> `base_path: bucket[/prefix]`, `options.region` (default `us-east-1`), `options.protocol: https|http`.
> Listings carry each object's **ETag onto `RemoteFile.etag`**, so `source.duplicate.mode: etag` skips
> unchanged objects **before** downloading; listed objects are atomic ⇒ readiness is always READY (no
> stabilization pass). MOVE/RENAME = CopyObject+DeleteObject; TAG = PutObjectTagging.
> **NFS/SMB/CIFS = OS-mounted shares** (see §1 note below) — no in-process protocol client, by design.
> **Also shipped 2026-07-08 (ACQ-6):** push/event-driven discovery — **`POST /sources/{id}/notify`**
> (an S3 event notification, upload script, or upstream job triggers an immediate scan; v1 answers
> `202 {runId}` + poll `Location`, gated on `canOperateRuns`, audited as `source.notified`; a spurious
> notify is harmless — the cycle's dedup/stability decide what ingests) and **`source.discovery: watch`**
> (JDK `WatchService` on a local/mounted poll root; debounced ~1s via `-Dservice.watch.quiet.millis`;
> the interval poll loop stays on as the backstop — watch narrows latency, it never carries correctness).
> **Also shipped 2026-07-08 (ACQ-5):** the **`kafka` connector** — a Kafka topic consumed as a Source. Each
> scan cycle drains a partition's unconsumed backlog into a **virtual slice file**
> (`<topic>-p<partition>-<from>-<to>.<ext>`) that flows through the normal batch path — the DB-export
> virtual-file idiom applied to a stream, so **no core-engine change**. Offsets are **not** a broker consumer
> group: the connector `assign()`+`seek()`s and the consumed frontier rides the **ledger watermark** (the
> DB-export machinery), persisted only **after the batch commits** — a crash mid-ingest re-drains the slice
> rather than skips it (at-least-once). Profile: `host`/`port` (or `options.bootstrap_servers`),
> `options.topic` (required), optional `username`/`password` for SASL PLAIN
> (`options.security_protocol`/`sasl_mechanism`), `options.start: earliest|latest` first-run position,
> `options.max_records` per-partition cap, `options.payload: envelope|raw` (envelope = one JSON object per
> record; raw = the value verbatim, for CSV-over-Kafka), `options.export_ext`, and `kafka.*` client
> passthrough. `kafka-clients` (3.9.x, broker-compatible back to 2.1) is confined to `inspecto-connectors`;
> the tests drive the in-jar `MockConsumer`, so the suite needs no broker.
> **Also shipped 2026-07-08 (ACQ-4, second half):** the **`azure` connector** — Azure Blob Storage spoken
> directly over the JDK `HttpClient` with in-tree **SharedKey** signing (**no Azure SDK**; the same
> discipline as the s3 connector). Profile: `host`/`port` = the blob endpoint, `username` = storage
> account, `password` = account key (SecretResolver reference), `base_path: container[/prefix]`,
> `options.protocol: https|http` (http for a LAN Azurite). List Blobs pagination via `NextMarker`;
> listing **Etags feed `RemoteFile.etag`** (ACQ-7 pre-fetch skip); blobs are atomic ⇒ readiness READY;
> Range-resume fetch; MOVE/RENAME = Copy Blob + Delete guarded on `x-ms-copy-status: success` (a pending
> copy never deletes the source); TAG = Set Blob Tags.
> **GCS native** — `connector: gcs` (SDK-free, shipped 2026-07-22): the GCS JSON API + service-account
> OAuth2 (RS256 JWT→bearer on JDK crypto, token cached per scan); Objects:list pagination, `generation` →
> `RemoteFile.version`, TAG = custom object metadata PATCH. Distinct from the S3-interop path above (which
> reaches GCS via HMAC keys). See `connectors.md`.
> **Still future** (this SPI makes each non-disruptive): a presigned-URL / STS credential mode for s3.

**GOAL:**
* **The system guarantees that every eligible data source file is collected exactly once (or according to policy), safely, efficiently, and recoverable regardless of where the file resides**
* **There would be other source connectors (`Kafka` streaming + `DataBase Tables` export shipped; more to come) to create a Data Lake House System**


## 1. Data Source (File) Connectivity
The system shall support collecting files from multiple source types through a pluggable connector architecture.

**Supported Sources**
* **Local File System**
  * `inbox/<data_source>`
* **Remote File Systems**
  * FTP
  * SFTP
  * SSH/SCP
  * SSH Tunneling and Proxy (future)
  * SMB/CIFS — via an **OS-mounted share** (see note)
  * NFS — via an **OS-mounted share** (see note)
* **Database export file**
  * Collect exported file from database table (Postgres) using sql template
  * SSH Tunnel and collect exported file from database (Postgres)
* **Object Storage**
  * Amazon S3 — `connector: s3` (SDK-free, SigV4 in-tree)
  * MinIO — `connector: s3` (`options.protocol: http` for a LAN endpoint)
  * Google Cloud Storage — `connector: gcs` (SDK-free native JSON API + service-account OAuth2); or
    `connector: s3` in GCS interoperability (HMAC) mode
  * Azure Blob Storage — `connector: azure` (SDK-free, SharedKey in-tree; Azurite-compatible)
* **Streaming**
  * Apache Kafka topic — `connector: kafka` (drained per cycle into slice files; offsets in the ledger, no consumer group)

> **Network shares (NFS/SMB/CIFS) — the mounted-share pattern.** The engine deliberately has **no**
> in-process SMB/NFS client, and the config safety validator **rejects UNC paths** (`\\server\share`) at the
> path jail — that is the security boundary, not a gap. To collect from a share: mount it at the OS level
> (Windows `net use X: \\server\share /persistent:yes`, Linux `mount -t nfs|cifs … /mnt/share`), add the
> mount point to the path jail via `-Dassist.safety.roots=<existing roots>;X:\` (a `;`-separated list), and
> point the source's `dirs.poll` (or `base_path`) at the mounted path. The built-in `local` connector then
> handles discovery/stability/dedup identically to a local inbox — credentials, reconnection, and caching
> stay the OS's job, where they are audited and battle-tested.
* **Grouping Data Source**
  * Has different collection point and file types
  * Tagging
  
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
