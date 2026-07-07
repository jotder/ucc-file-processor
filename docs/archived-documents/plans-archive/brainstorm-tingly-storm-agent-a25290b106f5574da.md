# Data Acquisition & ETL Layer Exploration

## Scope & Goal

Map the current design of Inspecto's data acquisition / connectors / ETL layer to enable 
architecture/refactor brainstorm. Deliver: key abstractions + file paths, data-flow trace, 
and pain points / extension limitations.

## Core Abstractions & File Paths

### Framework Layer (inspecto/src/main/java/com/gamma/acquire)

**SourceConnector.java (L35)**
- SPI: pluggable source protocol (local FS, SFTP, FTP, DB export, S3/…)
- Read primitives: open(file) [stream], fetchTo(file, dest) [materialise]
- Lifecycle: discover(ctx) → readiness(file) → [open|fetchTo] → post(file, action)
- Capabilities enum: STREAM, RANDOM_ACCESS, RESUMABLE, DELETE, MOVE, RENAME, TAG, ETAG, VERSIONING
- Built-in: LocalFileSystemConnector reproduces legacy behavior byte-for-byte

**SourceConnectorFactory.java (L16)**
- Factory SPI for remote connectors
- Loaded via ServiceLoader; one factory per scheme (sftp, ftp, ftps, db, …)
- create(config, profile) → connector instance

**AcquisitionLedger.java (L20)**
- SPI: fingerprint repository for dedup (Phase C)
- Content-based dedup: find(sourceId, relPath) → Optional<LedgerEntry>
- File-level watermark: highWatermark(sourceId) → max last_modified seen
- Row-level watermark: dbWatermark(sourceKey) → opaque string (for DB exports)
- recordDbWatermark() called AFTER batch commits (at-least-once semantics)
- Built-in: InMemoryAcquisitionLedger (default); DbAcquisitionLedger (DuckDB/Postgres)

**Other Key Classes**
- DiscoveryContext: include/exclude/depth filters passed to discover()
- RemoteFile: name, relativePath, size, mtime, etag, version, hash
- Readiness enum: READY, NOT_READY, UNKNOWN
- PostAction: retain/delete/move/rename/tag on source file after ingest
- StabilityGate: size/mtime stabilization (readiness fallback)
- CircuitBreaker: temporary failure backoff (internal; no public API)

### Ingest & Transform Layer (inspecto/src/main/java/com/gamma/etl)

**StreamingFileIngester.java (L52)**
- SPI: custom format decoder (binary, proprietary, ASN.1, delimited, …)
- ingest(file, sink, srcId, cfg) emits records via RecordSink.emit()
- Throws IOException → QUARANTINED_UNREADABLE; 0 rows → QUARANTINED_MISMATCH
- Segments declared upfront; can call sink.define() to override column types
- Execution: union-mode (many small) or generation-mode (huge single file)

**PartitionWriter.java (L26)**
- Write materialized table to Hive partitions (year/month/day or custom)
- Staging: write to .staging/workerTag, then atomic reveal per partition
- Reveal: cross-dir move + same-dir rename (atomic on all platforms)
- Parallel reveal when ≥16 partition files; sequential otherwise
- Excludes internal __src_id column via SELECT * EXCLUDE (...)

**BatchIngestStrategy.java (L36)**
- Strategy interface: ingest(batch, cfg) → IngestOutcome
- CsvBatchStrategy: per-file temp table → raw_input (with __src_id) → transform → write
- StreamingPluginBatchStrategy: union-mode or generation-mode per batch size
- Shared tail: writeAndTrace() orchestrates partition write + lineage collection

### Poll & Batch Coordination (inspecto/src/main/java/com/gamma/inspector)

**SourceProcessor.java (L46)**
- Poll cycle entry point: run(cfg, onCommit)
- collect() → RemoteFile candidates (discovery + readiness + dedup filters)
- BatchPlanner.plan() → group by schema + size → Batch[]
- Process batches in parallel (virtual threads + Semaphore permit cap)
- Emits BatchEvent to onCommit consumer after SUCCESS commits

**BatchProcessor.java (L37)**
- Batch processing coordinator: selects strategy, commits, audits
- Commit sequence: DuckLakeRegistrar → manifest write → backup originals → ledger record → markers
- Markers written LAST (idempotent on crash if no markers yet)

**LocalFileSystemConnector.java (L31)**
- Built-in local FS connector (Phase A parity baseline)
- discover() reproduces legacy tree-walk
- Supports ready_marker template (Phase B) for upload-complete detection
- Capabilities: STREAM, RANDOM_ACCESS, RESUMABLE, DELETE, MOVE, RENAME

### Event & Service Layer (inspecto/src/main/java/com/gamma/service)

**BatchEventBus.java (L21)**
- In-process publish/subscribe for BatchEvent (commit events)
- Synchronous fan-out: publish(event) → all listeners
- Single listener throw → logged + skipped
- Listeners run on publishing thread (keep quick or hand off)
- GOTCHA: ingestLock held during publish → sync event-triggered run = deadlock

---

## Implemented Connectors (inspecto-connectors module)

| Connector | File | Scheme | Capabilities |
|-----------|------|--------|--------------|
| DbExportConnector | DbExportConnector.java:71 | db | STREAM-only; row-level watermark |
| SftpConnector | SftpConnector.java:47 | sftp | STREAM, RANDOM_ACCESS, RESUMABLE, DELETE, MOVE, RENAME; SSH tunnel |
| FtpConnector | FtpConnector.java:65 | ftp | Similar to SFTP; commons-net |
| FtpsConnectorFactory | FtpsConnectorFactory.java | ftps | FtpConnector + TLS (explicit/implicit) |
| SshTunnel | SshTunnel.java:30 | (support) | Port forwarding for bastion access |

---

## Data Flow: Acquire → Ingest → Transform → Write → Commit

```
DISCOVER
  SourceProcessor.run()
    ├─ collect(): SourceConnector.discover(ctx) + readiness gate + dedup filter
    └─ List<RemoteFile> candidates

PLAN
  BatchPlanner.plan(candidates) → group by schema + size → Batch[]

INGEST
  For each Batch:
    BatchProcessor.process()
      ├─ CsvBatchStrategy | StreamingPluginBatchStrategy
      ├─ SourceConnector.open(file) or fetchTo(file, dest)
      ├─ StreamingFileIngester.ingest() or CsvIngester
      └─ DuckDB Appender.append(row with __src_id)

TRANSFORM
  TransformCompiler.toRule() applies schema rules → materialized table

WRITE
  PartitionWriter.write()
    ├─ COPY (SELECT * EXCLUDE (__src_id) FROM table) TO .staging
    ├─ PARTITION_BY (year, month, day)
    └─ Atomic reveal: move + same-dir rename

LINEAGE COLLECT
  LineageCollector.collect() → count rows per srcId per partition

COMMIT
  1. DuckLakeRegistrar.register() [optional]
  2. BatchManifest write [required for reprocess]
  3. Backup originals [move inbox → backup dir]
  4. AcquisitionLedger.record() + recordDbWatermark() [after backup]
  5. MarkerManager.write() [LAST; only if not content-based ledger]

EVENT & DOWNSTREAM
  BatchEventBus.publish(BatchEvent)
    └─ EnrichmentService (hand off to triggerWorkers to avoid deadlock)
        └─ FlowJobRunner: execute enrichment flows
```

---

## Pain Points & Limitations

### P1: Synchronous Event Bus + ingestLock Deadlock Risk
**Issue**: BatchEventBus.publish() fires synchronously while ingestLock is held.
Event-triggered run would deadlock.
**Workaround**: Hand off to triggerWorkers virtual-thread pool.
**Refactor Pain**: Future event-driven retry/webhook logic must remember this coupling.
**File**: BatchEventBus.java:38; PROJECT_NOTES.md §4

### P2: Monolithic SourceProcessor.collect() + Discovery Coupling
**Issue**: Include/exclude/depth filters in SourceProcessor. Connectors can't optimize server-side.
**Refactor Pain**: Adding filter dimensions requires SPI changes.
**File**: SourceProcessor.java:88

### P3: PartitionWriter Requires Non-Empty Partition Columns
**Issue**: Emits PARTITION_BY (...) unconditionally. Unpartitioned output in separate class.
**Refactor Pain**: Hard to add "no partition" mode or custom strategies.
**File**: PartitionWriter.java:102

### P4: BatchEvent Lacks Commit-Time Lineage
**Issue**: Carries pipeline + batchId but NOT srcId→partition map.
Subscribers must re-query lineage tables.
**Refactor Pain**: No cross-process lineage propagation.
**File**: BatchEventBus.java:28

### P5: StreamingFileIngester Lacks Progress/Cancellation
**Issue**: ingest() is fire-and-forget. No progress callback or shutdown signal.
**Refactor Pain**: Hard to add real-time progress UI or graceful abort.
**File**: StreamingFileIngester.java:64

### P5b: StreamingFileIngester Cannot Auto-Discover Segments
**Issue**: Segments declared upfront in toon config. No runtime discovery.
**Refactor Pain**: Formats with runtime-determined segments must declare all upfront.
**File**: StreamingFileIngester.java:48

### P6: SourceConnector.Capability Enum is Static
**Issue**: Capabilities fixed per connector. No runtime negotiation.
**Refactor Pain**: Hard to implement profile-dependent capabilities.
**File**: SourceConnector.java:82

### P7: AcquisitionLedger.dbWatermark() Opaque String — No Versioning
**Issue**: Row-level watermarks stored as text. No migration path if query semantics shift.
**Refactor Pain**: Risk of skipping or re-processing rows on query upgrade.
**File**: AcquisitionLedger.java:52

### P8: No Connector-to-Connector Handoff (No Multi-Protocol Pipelines)
**Issue**: One source.connector per pipeline. No way to chain connectors.
**Refactor Pain**: Custom chaining requires wrapping or forking ingest path.
**File**: SourceConnectorFactory.java:22

### P9: DuckDB Per-Batch Temp Database — No Cross-Batch State
**Issue**: Each batch gets own temp DuckDB file. No state carry-forward.
**Refactor Pain**: Advanced use cases (sliding-window, incremental ML) require custom hooks.
**File**: BatchIngestStrategy.java:135

### P10: Manifest + Lineage Written Synchronously During Commit
**Issue**: Written synchronously under batch lock. Large batches cause latency spikes.
**Refactor Pain**: Hard to defer audit I/O to background thread.
**File**: BatchProcessor.java:148

### P11: CircuitBreaker State Not Exposed to Control API
**Issue**: No /api/acquire/health endpoint to query failed connectors.
**Refactor Pain**: Operators can't see connector state without log scraping.
**File**: CircuitBreaker.java (no public API)

### P12: Ledger Record Ordering Race — No Explicit Transaction
**Issue**: Ledger entries recorded LAST. Crash mid-ledger-write breaks dedup.
**Refactor Pain**: No explicit transaction wrapper.
**File**: BatchProcessor.java:109

### P13: SourceProcessor Doesn't Expose Batch Plan Without Running
**Issue**: collect() has side effects (readiness polling). No dry-run API.
**Refactor Pain**: Control API can't offer "preview next poll" without refactoring.
**File**: SourceProcessor.java:88

---

## Seams (Module Boundaries)

- **inspecto ↔ inspecto-connectors**: SourceConnectorFactory SPI + ServiceLoader
- **inspecto ↔ inspecto-ui**: Control API routes (gap: no /api/acquire/* for health/watermarks)
- **inspecto-connectors ↔ inspecto-security**: SecretResolver SPI (planned)

---

## Recommendations for Refactor Brainstorm

### High Priority
1. Decouple event publish from poll cycle (async delivery or explicit scheduling)
2. Push discovery filters into connector (SFTP/FTP glob push-down)
3. Add StreamingFileIngester progress/cancellation callback interface
4. Cross-batch state persistence (flow state table)
5. Explicit ledger transaction wrapper (prevent watermark race)

### Medium Priority
6. Extend BatchEvent with lineage (srcId→partition map)
7. Expose connector health via Control API
8. Multi-protocol pipelines (chain/composite connector)
9. Pluggable partitioning strategies

### Nice-to-Have
10. Watermark versioning in AcquisitionLedger
11. Runtime capability negotiation
12. Batch plan preview API
13. Background audit I/O (defer manifest writes)

