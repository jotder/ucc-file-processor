# Plugin Ingester

> Part of the [UCC File Processor](../file-processor/README.md) documentation. See the [docs index](../file-processor/README.md#documentation).

## Plugin Ingester

Use the plugin ingester when:

- The source format is binary, proprietary, or otherwise not parseable by `CsvIngester`.
- A single input file emits **multiple event types** (e.g. CALL + SMS interleaved in one CDR file) that must land in separate output tables with different schemas.
- Partition columns such as `event_type` are **computed by the parser** — they do not exist as raw data columns but are derived from the record structure.

The CSV path is unchanged for all existing sources. Plugin ingester is an opt-in mode activated by adding `processing.ingester:` to the pipeline toon.

### FileIngester interface

Implement `com.gamma.etl.FileIngester` in the same fat JAR and provide a public zero-arg constructor:

```java
package com.acme.etl;

import com.gamma.etl.*;
import java.io.File;
import java.sql.*;
import java.util.List;

public class MyCdrIngester implements FileIngester {

    @Override
    public List<Segment> ingest(File file, Connection conn, int srcId, PipelineConfig cfg)
            throws Exception {

        // 1. Parse the binary/proprietary file in whatever way you need.
        // 2. For each event type, create one DuckDB table named "raw_<KEY>_f<srcId>".
        //    Include payload columns AND any derived partition columns (e.g. EVENT_TYPE).
        //    Do NOT add a __src_id column — the framework adds it when building the union.
        // 3. Return a Segment record for each created table.

        int callCount = 0, smsCount = 0;
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE \"raw_CALL_f" + srcId + "\" " +
                       "(ID VARCHAR, EVENT_TYPE VARCHAR, EVENT_DATE DATE)");
            st.execute("CREATE TABLE \"raw_SMS_f" + srcId + "\" " +
                       "(ID VARCHAR, EVENT_TYPE VARCHAR, EVENT_DATE DATE)");
            // ... parse file, insert rows into the appropriate tables ...
        }
        return List.of(
            new Segment("CALL", "raw_CALL_f" + srcId, new IngestResult(callCount, 0, 0)),
            new Segment("SMS",  "raw_SMS_f"  + srcId, new IngestResult(smsCount,  0, 0))
        );
    }
}
```

**Contract:**

| Rule | Detail |
|---|---|
| Table name | Must be `raw_<KEY>_f<srcId>` — the framework unions per-member tables by this convention |
| Derived columns | Add computed partition columns (e.g. `EVENT_TYPE VARCHAR`) directly into the raw table as extra columns |
| `__src_id` | Do **not** include — the framework appends it when creating the union table |
| Quarantine | Throw `IOException` → `QUARANTINED_UNREADABLE`. Return `parsedRows = 0` for all segments → `QUARANTINED_MISMATCH` |
| Segment keys | Must match the keys declared in `processing.segments:` in the pipeline toon |

### Segment schema toon (`partitions[]`) {#segment-schema-toon-partitions}

Each segment key has its own schema toon. Use the `partitions[N]{...}` tabular list syntax (JToon's array form) instead of the legacy `partitionKey:` shorthand. Each row maps an output partition column to a raw table source column and specifies how to derive the value.

> **JToon list syntax matters.** JToon does **not** parse YAML-style `- key: value` list items. Use the `name[N]{col1,col2,col3}:` tabular form everywhere — the same form `raw.fields[N]{...}` and `mapping.rules[N]{...}` already use. A YAML-style list silently parses as `null`, and `PartitionDef.fromSchema` then falls through to the empty-list branch, so every row lands in the `year=1900/month=01/day=01` sentinel partition. Symptoms: single output file regardless of how many distinct dates you have.

```yaml
# file: config/events/call_schema.toon

partitions[4]{column,source,type}:
  event_type,EVENT_TYPE,VARCHAR    # column in raw_CALL_f<srcId> added by the ingester
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
  ingester: com.acme.etl.MyCdrIngester   # fully-qualified class in the fat JAR
  segments:
    CALL: config/events/call_schema.toon  # key must match Segment.key() from the ingester
    SMS:  config/events/sms_schema.toon
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
| `processing.ingester` | yes | Fully-qualified class name of a `FileIngester` implementation in the fat JAR |
| `processing.segments` | yes (when ingester is set) | Ordered map of segment key → schema toon path; validated at startup |
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
            events_20200403_out.csv
  SMS/
    event_type=SMS/
      year=2020/
        month=04/
          day=03/
            events_20200403_out.csv
```

All lineage (input → output row counts), audit files (`_batches_`, `_lineage_`, `_status_`), and per-batch JSON manifests are written identically to the CSV path — one consolidated `BatchRow` per batch, with the segment keys used as the `schemaLabel`.

### Reference implementation: `TypedRecordIngester`

The repo ships one production-ready ingester, `com.gamma.ingester.TypedRecordIngester`, which handles the common case of **type-tagged text records** — one record per line where the first field selects the segment:

```
CALL,C001,2020-04-03,42
SMS,S001,2020-04-03,+15551234567
CALL,C002,2020-04-04,17
```

For each line, field 0 is matched against the keys of `processing.segments:`. Fields 1..N are mapped positionally to that segment's `raw.fields` list — so the schema's field order must match the input column order. Lines whose prefix isn't in `segments:` are silently skipped (counted as junk candidates).

Behaviour worth knowing:

- All columns stored as `VARCHAR` in DuckDB. `DataTransformer` handles type coercion at transform time via `CAST(... AS VARCHAR)` + `TRY_STRPTIME` — pre-typing would force every plugin to re-implement the same parsing logic.
- The ingester injects a derived `EVENT_TYPE VARCHAR` column into every raw table (populated with the segment key), so schemas can reference `EVENT_TYPE` as a partition source without redeclaring it in `raw.fields`.
- Blank lines and lines starting with `#` are skipped without being counted as errors. Lines with the wrong field count for a known segment are counted into `errorRows` and dropped.
- Field delimiter comes from `processing.csv_settings.delimiter` (default `,`).

Wire it up exactly like a custom ingester:

```yaml
processing:
  ingester: com.gamma.ingester.TypedRecordIngester
  segments:
    CALL: config/events/call_schema.toon
    SMS:  config/events/sms_schema.toon
  csv_settings:
    delimiter: ","
```

See `TypedRecordIngester.java` for the full source — it's deliberately compact (~150 lines) and is the recommended starting point for forking your own typed-record variant.

### Streaming ingester — very large custom files {#streaming-ingester}

The classic `FileIngester` above is **whole-file by construction**: it must build complete DuckDB tables for the *entire* input before returning. For a multi-hundred-GB / TB custom file (binary, proprietary, ASN.1) that means the full decoded dataset lands in heap and/or scratch at once — and unlike CSV, the framework **cannot auto-chunk it** (`processing.chunking` splits on line boundaries; only your decoder knows where an opaque record ends).

`com.gamma.etl.StreamingFileIngester` (since 3.10.0) is the additive answer. Instead of building tables, you **emit records one at a time** into a framework-owned `RecordSink`; the framework owns table creation, transform, partitioned write, lineage, *and* flushes bounded **generations** as it goes — so peak heap and scratch stay bounded regardless of total file size. The classic `FileIngester` is untouched and remains the right choice for modest files.

```java
package com.acme.etl;

import com.gamma.etl.*;
import java.io.File;

public class AsnCdrIngester implements StreamingFileIngester {

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
| `emit` values | Positional, matching the declared/derived column count and order; stored as `VARCHAR` (`null` ⇒ SQL `NULL`). No `__src_id`. |
| Quarantine | Throw (e.g. `IOException`) → `QUARANTINED_UNREADABLE`. Emit zero records across all segments → `QUARANTINED_MISMATCH`. |
| Don't flush yourself | Do **not** create tables, transform, or write output — the framework does, and bounds scratch for you. |
| Precedence | A class implementing both `FileIngester` and `StreamingFileIngester` uses the **streaming** path. |

**Output layout difference.** Each member streams independently and each bounded generation writes its own per-partition file (`<stem>_gNNNNN_out.*`), which coexist in the partition directories — valid Hive layout, the same trade-off the CSV auto-chunker makes. A file small enough to fit one generation produces a single output file, identical to the classic path. The per-generation row budget defaults to 5,000,000 rows (a deliberate internal default; see [design-notes D9](design-notes.md)).

Registration is identical to the classic path — set `processing.ingester` to your FQCN and declare `processing.segments`.

### Plugin author workflow

End-to-end recipe for shipping a custom `FileIngester` to a deployed pipeline:

**1. Set up your project.** Depend on the file-processor fat JAR. The minimal Maven snippet:

```xml
<dependency>
    <groupId>com.gamma.inspector</groupId>
    <artifactId>file-processor</artifactId>
    <version>1.3.0</version>
    <scope>provided</scope>
</dependency>
```

Use `<scope>provided</scope>` because the deployment server already has the fat JAR — you don't want to repackage it into yours.

**2. Implement `FileIngester`.** Two correctness rules above all else:

| Rule | Why |
|---|---|
| Table name is exactly `raw_<KEY>_f<srcId>` | The framework unions members by string match on this convention |
| Do **not** include a `__src_id` column | The framework adds it when building the union table; adding it yourself causes a `Binder Error: duplicate column` |

Everything else is your call: pre-type columns or not, parse with a streaming reader or load fully into memory, use prepared statements or DuckDB's appender API. `TypedRecordIngester` is the conservative baseline — fork it when your format diverges.

**3. Write the segment schema files.** One toon per segment key. Use the JToon `partitions[N]{column,source,type}:` tabular form (see the warning earlier — YAML-style lists silently break partitioning). `raw.fields` describes the data columns your ingester populates; ingester-derived columns (like `EVENT_TYPE`) go in `partitions[]` only.

**4. Test locally before deploying.** Pattern after `TypedRecordIngesterTest`: construct a `PipelineConfig` from an in-test temp pipeline toon, build a `Batch`, and call `BatchProcessor.process(batch, cfg, audit)`. This exercises the full plugin path including `DataTransformer` + `PartitionWriter` against a real DuckDB instance. Smoke-test with rows on **at least two distinct dates** — single-date tests can mask the partition-fan-out bug class.

**5. Package and deploy.**

- `mvn package` produces `your-ingester-x.y.z.jar`
- On the server, put your JAR on the classpath alongside the file-processor JAR. The `run.sh` / `run.bat` wrappers shipped by `package.ps1` use `-jar file-processor.jar`; switch them to `-cp "file-processor.jar:your-ingester-*.jar" com.gamma.inspector.SourceProcessor <pipeline.toon>` (use `;` instead of `:` on Windows).
- Reference your class by FQCN in the pipeline toon: `processing.ingester: com.acme.events.MyIngester`. The framework loads it via `Class.forName(...).getDeclaredConstructor().newInstance()` — the class must be public with a no-arg constructor.

**6. Production health checks.** The framework reports plugin loading at startup:

```
[CONFIG] Plugin ingester: com.acme.events.MyIngester  segments: [CALL, SMS]
```

If you see `Cannot instantiate ingester: ...` with a `ClassNotFoundException`, your JAR isn't on the classpath. If you see it with a `NoSuchMethodException`, you're missing the public no-arg constructor.

Per-file outcomes appear in `<source>_status_<runTimestamp>.csv`:

- `SUCCESS` — at least one segment produced rows; output written
- `QUARANTINED_UNREADABLE` — ingester threw `IOException` (file moved to `dirs.quarantine/unreadable/`)
- `QUARANTINED_MISMATCH` — every segment returned `parsedRows = 0` (file moved to `dirs.quarantine/field_mismatch/`)

---

