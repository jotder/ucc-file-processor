# Plugin Ingester

> Part of the [Inspecto](../inspecto/README.md) documentation. See the [docs index](../inspecto/README.md#documentation).

## Plugin Ingester

Use the plugin ingester when:

- The source format is binary, proprietary, or otherwise not parseable by `CsvIngester`.
- A single input file emits **multiple event types** (e.g. CALL + SMS interleaved in one CDR file) that must land in separate output tables with different schemas.
- Partition columns such as `event_type` are **computed by the parser** — they do not exist as raw data columns but are derived from the record structure.

The CSV path is unchanged for all existing sources. Plugin ingester is an opt-in mode activated by adding `processing.ingester:` to the pipeline toon.

### The `StreamingFileIngester` SPI

There is **one** plugin ingestion SPI: `com.gamma.etl.StreamingFileIngester`. You decode the file and **emit records one at a time** into a framework-owned `RecordSink`; the framework owns DuckDB table creation, transform, partitioned write, and lineage. Implement it in the same fat JAR with a public zero-arg constructor:

```java
package com.acme.etl;

import com.gamma.etl.*;
import java.io.File;

public class MyCdrIngester implements StreamingFileIngester {

    @Override
    public void ingest(File file, RecordSink sink, int srcId, PipelineConfig cfg) throws Exception {
        try (var decoder = openYourDecoder(file)) {     // your TLV / binary / record walker
            for (var record : decoder) {
                switch (record.type()) {
                    // emit(segmentKey, values…) — values positional, matching the segment's columns.
                    // Do NOT pass __src_id; the framework adds it.
                    case CALL -> sink.emit("CALL", record.id(), "CALL", record.date());
                    case SMS  -> sink.emit("SMS",  record.id(), "SMS",  record.date());
                    case BAD  -> sink.reject(record.typeKey());   // known type, malformed → errorRows
                    default   -> sink.junk();                     // unknown type, skipped → junkCandidateRows
                }
            }
        }
        // Returning normally signals end-of-file; the framework performs the final flush.
    }
}
```

**Contract:**

| Rule | Detail |
|---|---|
| Columns | Either call `sink.define(key, columns)` once per segment before emitting, **or** rely on the framework deriving columns from that segment's `raw.fields`. You *must* `define` explicitly when a `partitions[]` source column is ingester-derived and not in `raw.fields` (e.g. `EVENT_TYPE`). |
| `emit` values | Positional, matching the declared/derived column count and order; stored as `VARCHAR` (`null` ⇒ SQL `NULL`). Do **not** pass a `__src_id` value — the framework adds that column itself. |
| Quarantine | Throw (e.g. `IOException`) → `QUARANTINED_UNREADABLE`. Emit zero records across all segments → `QUARANTINED_MISMATCH`. |
| Don't flush yourself | Do **not** create DuckDB tables, transform, or write output — the framework does, and bounds scratch for you. |
| Segment keys | Must match the keys declared in `processing.segments:` in the pipeline toon. |

> **Migrating from the old `FileIngester`?** The whole-file `FileIngester` SPI (return `List<Segment>` of pre-built DuckDB tables) was **removed in v3.11.0**. Port to `StreamingFileIngester`: replace table creation + inserts with `sink.emit(...)` calls, drop the `raw_<KEY>_f<srcId>` table bookkeeping and the `__src_id` handling (the framework owns both now), and let counts flow through `emit`/`reject`/`junk` instead of `IngestResult`.

### Execution modes — the framework picks by file size

The same ingester serves both ingestion shapes; the [`StreamingPluginBatchStrategy`](../inspecto/src/main/java/com/gamma/inspector/StreamingPluginBatchStrategy.java) chooses one **per batch** with zero extra I/O (member sizes are already known):

| Mode | When | What it does | Output |
|---|---|---|---|
| **Union** | the batch's largest member is `< processing.streaming.large_file_bytes` (the common many-small-files case) | each member's emitted records accumulate into `raw_<KEY>_f<srcId>`; after all members, each segment's tables are unioned and transformed/written **once** for the whole batch | **consolidated** — one set of partition files per batch |
| **Generation** | the largest member is `>= processing.streaming.large_file_bytes` (a genuinely huge single file) | records flush in bounded **generations**; peak heap and scratch stay bounded regardless of total file size | per-generation files (`<stem>_gNNNNN_out.*`) coexisting in the partition dirs (valid Hive layout) |

Union mode amortises the fixed per-batch cost (one temp DB, one transform, one write) across thousands of small files and consolidates output instead of fragmenting it. Generation mode is the only way to process a multi-hundred-GB / TB file the framework cannot otherwise split (line-based CSV auto-chunking via `processing.chunking` does not apply to opaque records). Both modes conserve row counts and produce byte-identical transform/lineage output for the same input.

Tuning knobs (both optional, under `processing.streaming`):

| Key | Default | Description |
|---|---|---|
| `large_file_bytes` | `268435456` (256 MB) | A batch whose largest member is `>=` this runs in generation mode; smaller batches use union mode. `0` forces union mode always. |
| `flush_records` | `5000000` | Rows per generation flush (generation mode only); bounds scratch per generation. |

To make many-small-files efficient, also raise `processing.batch.max_files` so the planner packs many files into each union batch.

### Segment schema toon (`partitions[]`) {#segment-schema-toon-partitions}

Each segment key has its own schema toon. Use the `partitions[N]{...}` tabular list syntax (JToon's array form) instead of the legacy `partitionKey:` shorthand. Each row maps an output partition column to a raw table source column and specifies how to derive the value.

> **JToon list syntax matters.** JToon does **not** parse YAML-style `- key: value` list items. Use the `name[N]{col1,col2,col3}:` tabular form everywhere — the same form `raw.fields[N]{...}` and `mapping.rules[N]{...}` already use. A YAML-style list silently parses as `null`, and `PartitionDef.fromSchema` then falls through to the empty-list branch, so every row lands in the `year=1900/month=01/day=01` sentinel partition. Symptoms: single output file regardless of how many distinct dates you have.

```yaml
# file: spaces/default/config/events/call_schema.toon

partitions[4]{column,source,type}:
  event_type,EVENT_TYPE,VARCHAR    # column emitted by the ingester (define it on the sink)
  year,EVENT_DATE,DATE_YEAR
  month,EVENT_DATE,DATE_MONTH
  day,EVENT_DATE,DATE_DAY

raw:
  name: call
  format: CSV
  fields[3]{name,selector,type}:
    ID,"0",VARCHAR
    EVENT_TYPE,"1",VARCHAR
    EVENT_DATE,"2",DATE

mapping:
  canonicalName: call
  rawName: call
  rules[3]{targetColumn,sourceExpression,transformType}:
    ID,ID,DIRECT
    EVENT_TYPE,EVENT_TYPE,DIRECT
    EVENT_DATE,EVENT_DATE,DIRECT
```

**`PartitionDef.type` values:**

| Type | SQL generated | Use for |
|---|---|---|
| `VARCHAR` | direct column reference | String columns the ingester computes (e.g. `EVENT_TYPE`) |
| `DOUBLE` | `TRY_CAST(col AS DOUBLE)` | Numeric partition key |
| `INTEGER` | `TRY_CAST(col AS INTEGER)` | Integer partition key |
| `DATE_YEAR` | `YEAR(TRY_STRPTIME(CAST(col AS VARCHAR), fmt))::VARCHAR` | Year component of a date column |
| `DATE_MONTH` | `LPAD(MONTH(…)::VARCHAR, 2, '0')` | Zero-padded month |
| `DATE_DAY` | `LPAD(DAY(…)::VARCHAR, 2, '0')` | Zero-padded day |

The `DATE_*` types cast the source column to `VARCHAR` before parsing, so the same schema toon works whether the raw DuckDB column is already typed `DATE` (plugin path) or is a VARCHAR string (CSV path).

The legacy `partitionKey: COLUMN` shorthand synthesises three `DATE_YEAR` / `DATE_MONTH` / `DATE_DAY` entries automatically and remains fully supported for CSV sources.

### Pipeline config (plugin) {#pipeline-config-plugin}

Replace `schema_file:` with `ingester:` and `segments:`. The `duplicate_check:` block is optional (plugin path does not use `.processed` markers by default).

```yaml
name: EVENTS_ETL
version: 1

dirs:
  poll:       inbox/events
  database:   database/events
  backup:     backup/events
  temp:       temp/events
  errors:     errors/events
  quarantine: quarantine/events
  status_dir: status/events
  log_dir:    logs/events

output:
  format: CSV           # or PARQUET

processing:
  threads: 1
  file_pattern: "glob:**/*.bin"
  ingester: com.acme.etl.MyCdrIngester   # fully-qualified StreamingFileIngester in the fat JAR
  segments:
    CALL: spaces/default/config/events/call_schema.toon  # key must match the segment key the ingester emits
    SMS:  spaces/default/config/events/sms_schema.toon
  streaming:                # optional — mode selection + generation budget
    large_file_bytes: 268435456   # ≥ this (per member) → generation mode; else union mode
    flush_records: 5000000        # rows per generation flush
  batch:
    max_files: 1000         # pack many small files per union batch (raise for the many-small case)
  csv_settings:
    delimiter: ","
    skip_header_lines: 0
    skip_junk_lines: 0
    skip_tail_lines: 0
    date_formats[1]: "%Y-%m-%d"
    timestamp_formats[1]: "%Y-%m-%d"
```

| Key | Required | Description |
|---|---|---|
| `processing.ingester` | yes | Fully-qualified class name of a `StreamingFileIngester` implementation in the fat JAR |
| `processing.segments` | yes (when ingester is set) | Ordered map of segment key → schema toon path; validated at startup |
| `processing.streaming.large_file_bytes` | no | Generation-mode threshold in bytes (default 256 MB); `0` = always union |
| `processing.streaming.flush_records` | no | Rows per generation flush (default 5,000,000) |
| `processing.ingester_config` | no | Free-form map for plugin-specific settings (e.g. `record_length`, `byte_order`). Plugins read it via `cfg.schemas().ingesterConfig().get("key")`. Defaults to empty map. |

> `segments:` must be a non-empty map when `ingester:` is set; a missing or empty map throws `IllegalArgumentException` at startup. Each schema file must exist; a missing file throws `FileNotFoundException`.
>
> **Identifier validation.** At config load, every name that will be interpolated into SQL DDL — `raw.fields[].name`, `mapping.rules[].targetColumn`, `partitions[].column / source`, `partitionKey`, the `schemas[].table` value — is validated against `^[A-Za-z_][A-Za-z0-9_]*$`. Names containing spaces, dots, quotes, hyphens, or SQL operators fail the load with a precise location (e.g. `segment[CALL].raw.fields[].name`). This is a hard fail, not a warning.
>
> **Config sanity warnings.** A separate post-load pass logs SLF4J warnings for suspicious-but-legal patterns: no partitions declared (rows would collapse to the `1900/01/01` sentinel), empty `date_formats`, `retention_days <= 0`, `threads > 1` with `batch.max_files = 1`. Warnings appear at startup; they don't block the run.

### Output layout

Output files are written under `database/<source>/<SEGMENT_KEY>/` and partitioned by the columns declared in that segment's `partitions[]` list. Multiple segments from the same input file land in independent sub-trees:

```
database/events/
  CALL/
    event_type=CALL/
      year=2020/
        month=04/
          day=03/
            events_20200403_out.csv       # union mode: one consolidated file per partition
  SMS/
    event_type=SMS/
      year=2020/
        month=04/
          day=03/
            events_20200403_out.csv
```

In **generation mode** each bounded generation writes its own per-partition file (`<stem>_gNNNNN_out.*`), which coexist in the partition directories — valid Hive layout, the same trade-off the CSV auto-chunker makes. In **union mode** the batch's members are consolidated, so each partition gets a single file (named for the sole member's stem when the batch has one survivor, else the batch id).

All lineage (input → output row counts), audit files (`_batches_`, `_lineage_`, `_status_`), and per-batch JSON manifests are written identically to the CSV path — one consolidated `BatchRow` per batch, with the segment keys used as the `schemaLabel`.

### Reference implementation: `TypedRecordIngester`

The repo ships one production-ready ingester, `com.gamma.ingester.TypedRecordIngester`, which handles the common case of **type-tagged text records** — one record per line where the first field selects the segment:

```
CALL,C001,2020-04-03,42
SMS,S001,2020-04-03,+15551234567
CALL,C002,2020-04-04,17
```

For each line, field 0 is matched against the keys of `processing.segments:`. Fields 1..N are mapped positionally to that segment's `raw.fields` list — so the schema's field order must match the input column order. Lines whose prefix isn't in `segments:` are silently skipped (`sink.junk()`).

Behaviour worth knowing:

- Every emitted value is stored as `VARCHAR` in DuckDB. `DataTransformer` handles type coercion at transform time via `CAST(... AS VARCHAR)` + `TRY_STRPTIME` — pre-typing would force every plugin to re-implement the same parsing logic.
- The ingester `define`s a trailing `EVENT_TYPE` column on every segment (emitted with the segment key), so schemas can reference `EVENT_TYPE` as a partition source without redeclaring it in `raw.fields`.
- Blank lines and lines starting with `#` are skipped without being counted as errors. Lines with the wrong field count for a known segment go through `sink.reject(key)` (counted as `errorRows`) and are dropped.
- Field delimiter comes from `processing.csv_settings.delimiter` (default `,`).

Wire it up exactly like a custom ingester:

```yaml
processing:
  ingester: com.gamma.ingester.TypedRecordIngester
  segments:
    CALL: spaces/default/config/events/call_schema.toon
    SMS:  spaces/default/config/events/sms_schema.toon
  csv_settings:
    delimiter: ","
```

See `TypedRecordIngester.java` for the full source — it's deliberately compact (~120 lines) and is the recommended starting point for forking your own typed-record variant.

### Fixed-length binary records (`FixedWidthRecordIngester`) {#fixed-length-binary-records-fixedwidthrecordingester}

For **binary** fixed-length records (no delimiter, no newlines — each record is exactly *N* bytes), the
repo ships `com.gamma.ingester.FixedWidthRecordIngester`. (Fixed-width **text**, one record per line, is
handled natively by the engine — set `frontend: fixedwidth`; see
[configuration.md](configuration.md#fixed-width-frontend-frontend-fixedwidth). Reach for this plugin only
when records are not newline-delimited.)

It reads `record_length` bytes per record and carves each field by byte `(start,length)`, decoding with
the configured `encoding` and trimming per `trim`. Column **names/types** come from the segment schema's
`raw.fields` (positional); the `ingester_config` supplies only the byte geometry. A trailing partial
record is `reject`ed; an `IOException` quarantines the file as `QUARANTINED_UNREADABLE`; zero records
emitted ⇒ `QUARANTINED_MISMATCH`.

```yaml
processing:
  ingester: com.gamma.ingester.FixedWidthRecordIngester
  segments:
    REC: spaces/default/config/subscriber/subscriber_schema.toon   # exactly one segment
  ingester_config:
    record_length: 40
    encoding: utf-8                                  # optional (default UTF-8)
    trim: both                                       # none | left | right | both (default both)
    fields[4]{name,start,length}:                    # positional to the segment's raw.fields
      ACCOUNT_NUMBER,0,12
      EVENT_DATE,12,10
      PLAN_CODE,22,6
      BALANCE,28,12
```

Because it's a `StreamingFileIngester`, the framework runs it in union mode (many small files) or
generation mode (one huge file ≥ `processing.streaming.large_file_bytes`) automatically — bounded
memory either way.

### Plugin author workflow

End-to-end recipe for shipping a custom `StreamingFileIngester` to a deployed pipeline:

**1. Set up your project.** Depend on the file-processor fat JAR. The minimal Maven snippet:

```xml
<dependency>
    <groupId>com.gamma.inspector</groupId>
    <artifactId>file-processor</artifactId>
    <version>4.0.0</version>
    <scope>provided</scope>
</dependency>
```

Use `<scope>provided</scope>` because the deployment server already has the fat JAR — you don't want to repackage it into yours.

**2. Implement `StreamingFileIngester`.** Decode the file and `emit` records; let the framework own tables, transform, write, and lineage. The fastest path uses no JDBC at all — just `sink.emit(...)`. For derived partition columns not in `raw.fields` (e.g. `EVENT_TYPE`), call `sink.define(key, columns)` once per segment before the first emit. `TypedRecordIngester` is the conservative baseline — fork it when your format diverges.

**3. Write the segment schema files.** One toon per segment key. Use the JToon `partitions[N]{column,source,type}:` tabular form (see the warning earlier — YAML-style lists silently break partitioning). `raw.fields` describes the data columns your ingester emits; ingester-derived columns (like `EVENT_TYPE`) go in `partitions[]` and must be `define`d on the sink.

**4. Test locally before deploying.** Pattern after `TypedRecordIngesterTest` / `BatchProcessorPluginDeepTest`: construct a `PipelineConfig` from an in-test temp pipeline toon, build a `Batch`, and call `BatchProcessor.process(batch, cfg, audit)`. This exercises the full plugin path including `DataTransformer` + `PartitionWriter` against a real DuckDB instance. Smoke-test with rows on **at least two distinct dates** — single-date tests can mask the partition-fan-out bug class — and with **two members sharing a partition** to confirm union consolidation.

**5. Package and deploy.**

- `mvn package` produces `your-ingester-x.y.z.jar`
- On the server, put your JAR on the classpath alongside the file-processor JAR. The `run.sh` / `run.bat` wrappers shipped by `package.ps1` use `-jar file-processor.jar`; switch them to `-cp "file-processor.jar:your-ingester-*.jar" com.gamma.inspector.SourceProcessor <pipeline.toon>` (use `;` instead of `:` on Windows).
- Reference your class by FQCN in the pipeline toon: `processing.ingester: com.acme.events.MyIngester`. The framework loads it via `Class.forName(...).getDeclaredConstructor().newInstance()` — the class must be public with a no-arg constructor.

**6. Production health checks.** The framework reports plugin loading at startup:

```
[CONFIG] Plugin ingester: com.acme.events.MyIngester  segments: [CALL, SMS]
```

If you see `Cannot instantiate streaming ingester: ...` with a `ClassNotFoundException`, your JAR isn't on the classpath. If you see it with a `NoSuchMethodException`, you're missing the public no-arg constructor. A `ClassCastException` means the class doesn't implement `StreamingFileIngester`.

Per-file outcomes appear in `<source>_status_<runTimestamp>.csv`:

- `SUCCESS` — at least one segment produced rows; output written
- `QUARANTINED_UNREADABLE` — ingester threw (file moved to `dirs.quarantine/unreadable/`)
- `QUARANTINED_MISMATCH` — every segment emitted 0 rows (file moved to `dirs.quarantine/field_mismatch/`)

---

