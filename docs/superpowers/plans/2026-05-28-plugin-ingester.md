# Plugin Ingester + Dynamic Partition Keys — Implementation Plan

**Goal:** Allow a custom Java class (same fat JAR) to replace `CsvIngester` for files that emit
multiple event types with different output schemas. Partition keys become explicitly declared in
the schema toon and can include ingester-computed columns.

**Spec:** conversation 2026-05-28 (design confirmed, option A — ingester adds derived columns
directly into the raw DuckDB table alongside event field columns).

---

## New components

| File | Type | Description |
|---|---|---|
| `com/gamma/etl/FileIngester.java` | new interface | Contract for custom ingesters |
| `com/gamma/etl/PartitionDef.java` | new record | One declared partition column; `fromSchema()` factory |

## Modified components

| File | Change |
|---|---|
| `com/gamma/etl/DataTransformer.java` | `sourceTable`/`destTable` params; `PartitionDef.fromSchema()` replaces `partitionKey` block |
| `com/gamma/etl/PartitionWriter.java` | `List<String> partitionColumns` param; dynamic `PARTITION_BY` |
| `com/gamma/etl/LineageCollector.java` | `List<String> partitionColumns` param; dynamic partition path building |
| `com/gamma/etl/PipelineConfig.java` | `ingesterClass`, `segmentSchemas` fields; parse `ingester:` + `segments:` from processing section |
| `com/gamma/inspector/BatchProcessor.java` | Two-path dispatch: CSV (unchanged) vs multi-segment (new) |

---

## Config shape (new)

```yaml
processing:
  ingester: com.acme.MyEventIngester   # absent → CsvIngester path unchanged
  segments:
    CALL: config/source/call_schema.toon
    SMS:  config/source/sms_schema.toon
  # schema_file / schemas[] still used when ingester is absent
  csv_settings: ...
```

```yaml
# per-segment schema toon — replaces single "partitionKey:" line
partitions:
  - column: event_type    # ingester added this column to raw table
    source: EVENT_TYPE
    type: VARCHAR
  - column: year
    source: CALL_DATE     # raw-table column name (pre-mapping)
    type: DATE_YEAR       # → YEAR(COALESCE(TRY_STRPTIME(…)))::VARCHAR  for VARCHAR src
                          # → YEAR(col)::VARCHAR                         for DATE src
  - column: month
    source: CALL_DATE
    type: DATE_MONTH
  - column: day
    source: CALL_DATE
    type: DATE_DAY
```

**Backward compat:** when `partitions[]` is absent, existing `partitionKey:` behaviour is
preserved (synthesised to three DATE_YEAR/MONTH/DAY defs on the same source column).
When both are absent, `year=1900/month=01/day=01` fallback is unchanged.

---

## Multi-segment flow in `BatchProcessor`

```
For each member file:
    ingester.ingest(file, conn, cfg)  →  List<FileIngester.Segment>
    Each segment creates table "raw_{KEY}_f{srcId}" in conn

For each segmentKey in cfg.segmentSchemas (ordered):
    CREATE TABLE "raw_{KEY}" … WHERE false   (schema from first survivor)
    for each survivor that produced this key:
        INSERT INTO "raw_{KEY}" SELECT *, srcId FROM "raw_{KEY}_f{srcId}"

    partDefs  = PartitionDef.fromSchema(segmentSchema)
    partCols  = partDefs.map(PartitionDef::column).toList()

    DataTransformer.materialize(conn, segmentSchema, cfg,
                                sourceTable="raw_{KEY}", destTable="transformed_{KEY}")
    PartitionWriter.write(conn, "transformed_{KEY}",
                          databaseDir + "/" + segmentKey, …, partCols)
    LineageCollector.collect(conn, "transformed_{KEY}", …, partCols)

Aggregate all outputs + lineage for single BatchRow + audit flush.
```

**Quarantine rule:** if `ingester.ingest()` throws IOException → whole file QUARANTINED_UNREADABLE.
If every segment across the file has 0 rows → QUARANTINED_MISMATCH.

---

## Tasks

- [ ] **1** `PartitionDef` record + `fromSchema()` + `PartitionDefTest`
- [ ] **2** `FileIngester` interface
- [ ] **3** `DataTransformer` — `sourceTable`/`destTable` params + PartitionDef loop + test
- [ ] **4** `PartitionWriter` — dynamic `partitionColumns` param + test
- [ ] **5** `LineageCollector` — dynamic `partitionColumns` param + test
- [ ] **6** `PipelineConfig` — `ingesterClass` + `segmentSchemas` + test
- [ ] **7** `BatchProcessor` — two-path dispatch + integration test with stub ingester
