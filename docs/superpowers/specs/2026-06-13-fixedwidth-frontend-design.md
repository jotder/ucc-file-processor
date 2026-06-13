# Spec: Fixed-Width File Onboarding (`.toon`-configured `fixedwidth` frontend)

> **Date:** 2026-06-13
> **Status:** Design / ready-for-planning (implementation prompt)
> **Branch:** `4.x`
> **Companion:** [parsing-options-reference.md](../../parsing-options-reference.md) §5/§6.3/§8
> (where `fixedwidth` is `[PROPOSED]`), [delimited-grammar-design.md](../../delimited-grammar-design.md)
> (the shipped 4.1 grammar model this builds on).
>
> This document is a **self-contained implementation prompt**: hand it to an engineer or a coding
> agent and it produces the feature. It is grounded in the real Inspecto seams; confirm the cited
> classes before coding.

---

## ROLE & OBJECTIVE

Implement a new **`fixedwidth` parsing frontend** in the Inspecto repo
(`C:\sandbox\ucc-file-processor`, branch `4.x`, Java 25+, embedded DuckDB) so a fixed-width **text**
file *or* a fixed-length **binary** file can be onboarded with `.toon` configuration — **reusing the
existing typing/transform/partition/lineage backend verbatim**. This is build-order step 2 of
`parsing-options-reference.md` §8.

## CONTEXT — ground truth (confirm by reading the cited files; do not re-derive)

- **Frontend/backend split** (`parsing-options-reference.md` §1): every format converges on the same
  DuckDB-powered backend — `mapping.rules[]` → `partitions[]` → Hive-partitioned Parquet/CSV +
  lineage/audit. Only the *frontend* (bytes → VARCHAR staging rows) changes. Everything is read as
  VARCHAR and typed later via `mapping.rules[]` + `TRY_STRPTIME`/`TRY_CAST` (bad value → NULL, never
  a failed batch).
- **Engine seam:** `com.gamma.inspector.BatchIngestStrategy` (`CsvBatchStrategy` /
  `StreamingPluginBatchStrategy`); `BatchProcessor` selects the strategy by config (no ingester →
  CSV; ingester set → streaming plugin).
- **Native CSV ingest** (the pattern to mirror): `com.gamma.etl.DuckDbCsvIngester` — `buildReadSpec`,
  `createRawInputView` (builds `raw_input` as a **lazy view** so the single transform materializes
  once, peak scratch ~1×). Java fallback: `CsvIngester`.
- **Backend — DO NOT change its behavior or emitted SQL:** `DataTransformer` + `TransformCompiler`,
  `PartitionWriter`, `LineageCollector`. They consume a `raw_input` relation with positional VARCHAR
  columns `c0..cN` and the `raw.fields[].selector` indexes into them.
- **Config:** `com.gamma.etl.PipelineConfig` (+ nested `Processing`/`CsvSettings`; `resolveGrammar`
  already overlays a `processing.grammar` file with inline keys — 4.1; `fromMap`/`prepare` split).
  Specs: `com.gamma.config.spec.ConfigSpecs`/`FieldSpec`/`CrossFieldRule`/`Finding`; canonical
  `ConfigCodec`.
- **Plugin SPI** (for the binary case): `com.gamma.etl.StreamingFileIngester` + `RecordSink`;
  reference impl `com.gamma.ingester.TypedRecordIngester` (~120 lines — mirror its shape).
- **Decision D3** (`delimited-grammar-design.md`): *columns/types live in the event toon, not the
  grammar; the grammar defines tokenization.* Fixed-width tokenization = slice boundaries → those
  `(start,length)` offsets belong in the **grammar**; names/types stay in the event schema.

## INVARIANTS (hard constraints)

1. **Zero new dependency** in the lean core (DuckDB is already bundled — use it; no new jar).
2. **Additive only** — every existing CSV/plugin `.toon` parses and produces **byte-identical**
   output (no `frontend`/`fixedwidth` keys ⇒ today's CSV path, unchanged).
3. **`.toon` stays canonical**; `@PublicApi` + on-disk output byte-stable.
4. **TDD**, each task green; ship Java-vs-reference **parity tests**; full reactor green **CPU-only**.
5. Run with `--enable-native-access=ALL-UNNAMED`. **Never stage `inspecto/pom.xml` or
   `run-adjustment.bat`. No commit/push without an explicit ask.**

## DESIGN (implement these decisions)

### Config shape

Add a `frontend` discriminator + a `fixedwidth` block (placeable inline under `processing` **or** in
a reusable `*.grammar.toon`, exactly like the 4.1 delimited grammar). Slice index *i* → staging
column `c<i>` → referenced by `raw.fields[].selector: i`. `name` in the grammar is
**optional/cosmetic** (used only for validation messages).

```toon
# subscriber.grammar.toon — fixed-width tokenization grammar (reusable across event types)
frontend: fixedwidth          # delimited (default) | fixedwidth | plugin
encoding: utf-8               # utf-8 | utf-16 | latin-1
compression: auto             # auto | gzip | none
fixedwidth:
  record: line                # line (newline-delimited text) | bytes (fixed-length binary)
  record_length: 0            # REQUIRED when record: bytes; ignored for line
  trim: both                  # none | left | right | both
  min_record_length: 48       # drop lines shorter than this (blanks/footers); default = max(start+length)
  fields[3]{name,start,length}:   # start is 0-based, length in chars (bytes for record: bytes)
    ACCOUNT_NUMBER, 0,  16
    EVENT_DATE,     16, 20
    AMOUNT,         36, 12
```

```toon
# subscriber_main.toon — event schema (columns/types/mapping live here, unchanged shape)
partitionKey: EVENT_DATE
raw:
  fields[3]{name,selector,type}:    # selector i = slice i from the grammar
    ACCOUNT_NUMBER, 0, VARCHAR
    EVENT_DATE,     1, DATE
    AMOUNT,         2, DOUBLE
mapping:
  rules[3]{targetColumn,sourceExpression,transformType}:
    ACCOUNT_NUMBER, ACCOUNT_NUMBER, DIRECT
    EVENT_DATE,     EVENT_DATE,     DIRECT
    AMOUNT,         AMOUNT,         DIRECT
```

```toon
# subscriber_pipeline.toon
processing:
  threads: 3
  file_pattern: "glob:**/*.dat"
  grammar:     config/subscriber/subscriber.grammar.toon
  schema_file: config/subscriber/subscriber_main.toon
  date_formats[1]: "%d-%b-%Y %H:%M:%S"
```

### Native text mechanism (`record: line`) — recommended: streaming via `read_csv`, not `read_text`

Read each physical line into a single VARCHAR column using a separator byte that cannot occur in the
source (US `\x1F` = `chr(31)`), then carve fields with `substring`. This **streams** (gz-aware,
parallel, reject-capturing — all the existing `read_csv` machinery) and is strictly better than the
doc's `read_text` + `UNNEST(string_split(...))` whole-file approach, which materializes the entire
file in one VARCHAR cell (only acceptable for small files — keep it as the documented fallback).
Build `raw_input` as a **lazy view** (mirror `createRawInputView`):

```sql
CREATE VIEW raw_input AS
SELECT trim(substring("line",  1, 16)) AS c0,   -- config start 0-based; DuckDB substring is 1-based → start+1
       trim(substring("line", 17, 20)) AS c1,
       trim(substring("line", 37, 12)) AS c2
FROM read_csv('<file>', columns={'line':'VARCHAR'}, sep=chr(31), quote='', escape='',
              header=false, skip=<skip_header_lines>, ignore_errors=true,
              auto_detect=false, compression='<compression>')
WHERE length("line") >= <min_record_length>;   -- drops blanks/footers/short lines
```

The `trim` variant is chosen by config (`trim`/`rtrim`/`ltrim`/none). The existing
`DataTransformer.materialize` then runs `CREATE TABLE transformed AS SELECT <mapping rules> FROM
raw_input` **unchanged**.

### Binary mechanism (`record: bytes`, `record_length: N`)

Ship `com.gamma.ingester.FixedWidthRecordIngester implements StreamingFileIngester`: read
`record_length` bytes per record from a buffered stream, decode each `(start,length)` slice with
`encoding`, `sink.emit(<segment>, c0, c1, …)` (positional VARCHAR; never pass `__src_id`). Partial
trailing record → `sink.reject(...)`; `IOException` → throw (⇒ `QUARANTINED_UNREADABLE`); zero
records ⇒ `QUARANTINED_MISMATCH`. It rides generation/union mode + bounded memory **for free** via
the framework.

### Routing (`BatchProcessor`)

`processing.ingester` set → `StreamingPluginBatchStrategy` (today). Else if `frontend == fixedwidth`:
`record: line` → new native fixed-width strategy/ingester; `record: bytes` → auto-route to the
built-in `FixedWidthRecordIngester` (so it inherits generation/union + bounded scratch). Else →
`CsvBatchStrategy` (today).

## TASKS (TDD — each green + behavior-preserving)

- **T1 — Config + spec.** Extend `PipelineConfig.Processing` with `frontend` + a `FixedWidth` nested
  record (`record`, `recordLength`, `trim`, `minRecordLength`, `slices[]`); thread through
  `resolveGrammar`; add to `ConfigSpecs.pipeline()` with `CrossFieldRule`s: `record: bytes` ⇒
  `record_length > 0`; each slice `start ≥ 0` & `length ≥ 1`; **slice count == `raw.fields[]` count**
  per event schema; `frontend: fixedwidth` incompatible with `delimiter`/`skip_tail_columns` (ERROR)
  and warns on overlapping slices. **Tests:** good/bad configs; `ConfigCodec` round-trip;
  backward-compat (no `frontend` → delimited, byte-identical).
- **T2 — Native text strategy.** Implement the `read_csv`+`substring` lazy-`raw_input` view
  (off-by-one: `substring(line, start+1, length)`). Reuse `DataTransformer`/`PartitionWriter`/
  `LineageCollector` untouched. **Tests:** **parity** vs an equivalent delimited config producing
  identical typed Parquet; ≥2 distinct dates → ≥2 partitions (the single-date masking trap); `trim`
  variants; short/blank/footer dropped; `.gz`; `latin-1`; row-conservation + lineage counts.
- **T3 — Binary ingester + routing.** `FixedWidthRecordIngester` + `BatchProcessor` routing.
  **Tests:** identical output to the text variant on equivalent data; partial-trailing-record
  handling; unreadable → quarantine; a large fixture confirms generation-mode (bounded memory).
- **T4 — Docs + worked example.** Mark `fixedwidth` `[LIVE]` in `parsing-options-reference.md`
  (§5/§6.3/§7); add the section to `configuration.md` and a note to `plugins.md`; commit the
  worked-example toons + a sample `.dat` under `config/subscriber/`.
- **T5 — (stretch)** `create-schema --fixedwidth`: infer column *types* from sliced sample values
  (offsets remain hand-authored — they can't be reliably inferred).

## ACCEPTANCE CRITERIA

A fixed-width `.dat` onboards with the worked-example `.toon` set and produces the **same**
Hive-partitioned typed Parquet as the equivalent CSV config (proven by a parity test). Full reactor
green CPU-only; **zero new deps**; every pre-existing CSV/plugin config byte-identical;
`--enable-native-access=ALL-UNNAMED`.

## OUT OF SCOPE

The other proposed frontends (`json`, `text_regex`, nested XML); adopting the unified `parsing:`
block (§8 step 5); changing any backend-emitted SQL or on-disk format for delimited/plugin paths.

## KEY ENGINEERING CHOICES (rationale — adjust before planning if needed)

1. **`read_csv`-single-column over the doc's `read_text`** — `read_text` loads the whole file into
   one VARCHAR cell (won't bound memory on large fixed-width files); the `read_csv` line trick
   streams and reuses gz/skip/reject handling. `read_text` kept only as the small-file fallback.
2. **Offsets in the grammar, names/types in the event schema** — reconciles the `[PROPOSED]` grammar
   with shipped decision D3 ("the grammar never declares columns") and keeps schema authoring
   identical to CSV (`selector` = slice index).
3. **Binary case routes to a `StreamingFileIngester`** rather than native SQL — matches the doc and
   inherits bounded-memory generation mode for free.
