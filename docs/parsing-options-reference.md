# Parsing Options Reference

> Companion to `docs/Parsing Options Reference.pdf`. The PDF is an excellent **generic DuckDB
> `read_csv` cheat-sheet**; this document (1) **validates and corrects** that material against
> current DuckDB, (2) grounds it in **this engine's actual config + SPI**, and (3) **extends** it
> to every format you need to ingest — delimited, fixed-width, semi-structured, JSON, XML, LDIF,
> and binary (ASN.1, custom).
>
> **Status legend** used throughout:
> `[LIVE]` = exposed by the engine today · `[NATIVE]` = DuckDB can do it, engine doesn't surface it
> yet · `[PLUGIN]` = reachable today via a `StreamingFileIngester` · `[PROPOSED]` = config grammar
> in this doc, not yet implemented.

---

## 1. The unifying model: a parser *frontend* feeding the DuckDB *backend*

Every format you listed converges on the **same** typing/transform/partition backend. Only the
**frontend** (how raw bytes become rows) changes. There are exactly three frontends:

```
                                         ┌──────────────────────────────────────────┐
 delimited / SQL*Plus  ──► read_csv  ──► │                                            │
 JSON / NDJSON         ──► read_json ──► │  raw VARCHAR rows in a DuckDB temp table   │
 fixed-width / LDIF /  ──► read_text ──► │                  │                         │
   simple XML / KV         + SQL regex   │   mapping.rules[] (TransformCompiler)      │
                                         │                  │  typed scalar exprs     │
 ASN.1 / BER-TLV /     ──► StreamingFile │   partitions[]   ▼  (year/month/day/…)     │
   custom binary /         Ingester      │   ──► Hive-partitioned Parquet/CSV + lineage│
   true XML / complex      .emit(record) │                                            │
                                         └──────────────────────────────────────────┘
        FRONTEND (format-specific)                BACKEND (shared, DuckDB-powered)
```

- **DuckDB-native frontends** (`read_csv`, `read_json`, `read_text`/`read_blob`) parse the bytes
  *inside* DuckDB. Fast, vectorized, zero Java parsing. Good for delimited, JSON, and
  "regular-enough-to-regex" text (fixed-width, LDIF, key/value, flat XML).
- **The plugin frontend** (`StreamingFileIngester`) decodes in Java and `emit()`s records. The
  *only* path for genuinely binary/grammar-driven formats — ASN.1/BER-TLV, proprietary CDRs,
  deeply nested XML. See [plugins.md](plugins.md).

**Key consequence:** "use DuckDB as a parser extensively" is the right instinct — push as many
formats as possible onto `read_csv`/`read_json`/`read_text` and reserve the Java plugin for true
binary. The backend (mapping + partition + lineage + audit) is identical regardless of frontend, so
a format's onboarding cost is *only* its frontend.

---

## 2. What the engine exposes **today** vs. raw DuckDB

The native CSV path (`DuckDbCsvIngester.buildReadSpec`) emits a **fixed** `read_csv` call:

```sql
read_csv('<file>',
  columns={'c0':'VARCHAR', 'c1':'VARCHAR', ...},   -- width = maxSelector+1, all VARCHAR
  delim='<csv_settings.delimiter>',
  header=false,
  skip=<skip_header_lines + (has_header?1:0)>,
  ignore_errors=true,
  null_padding=false,
  auto_detect=false,
  store_rejects=true)
```

So the config knobs that actually reach DuckDB today are **only**:

| `csv_settings` key | → `read_csv` param | Status |
|---|---|---|
| `delimiter` | `delim` | `[LIVE]` |
| `has_header` + `skip_header_lines` | `skip` (combined) | `[LIVE]` |
| `engine` (`auto`/`duckdb`/`java`) | selects native vs Java path | `[LIVE]` |
| `skip_junk_lines`, `skip_tail_lines`, `skip_tail_columns` | **Java path only** (route away from native) | `[LIVE]` |
| `date_formats`, `timestamp_formats` | used at **transform** time (`TRY_STRPTIME`), *not* at read time | `[LIVE]` |

Everything else in the PDF — `quote`, `escape`, `comment`, `nullstr`, `strict_mode`,
`decimal_separator`, `sample_size`, `auto_type_candidates`, `names`, `union_by_name`,
`hive_partitioning`, read-time `dateformat`, `encoding`, `compression` — is **`[NATIVE]`**: DuckDB
supports it, but it is **not reachable from config**. Section 5 proposes the grammar to expose them.

> Type detection difference worth internalizing: this engine reads **everything as VARCHAR** and
> types later via `mapping.rules[]` + `TRY_STRPTIME`/`TRY_CAST` (so a bad value becomes `NULL`, never
> a failed batch). DuckDB's own sniffer/`dateformat`/`decimal_separator` are therefore mostly
> redundant here — by design. Read-time typing is only worth exposing for `read_json` (where types
> are structural) and for performance edge cases.

---

## 3. Corrected & extended DuckDB `read_csv` parameter reference

Supersedes the PDF's §1–§5 tables. Verified against DuckDB 1.x (the engine bundles 1.5.2).

### 3.1 Dialect & structure
| Param | Type | Default | Notes |
|---|---|---|---|
| `delim` / `sep` | VARCHAR | `,` | Multi-byte allowed. |
| `header` | BOOL | auto | First non-skipped row = names. |
| `skip` | BIGINT | 0 | Physical lines skipped at top, **before** header detection. |
| `quote` | VARCHAR | `"` | |
| `escape` | VARCHAR | `"` | |
| `comment` | VARCHAR | `''` | Lines starting with prefix dropped. DuckDB ≥ 0.10. |
| `new_line` | VARCHAR | auto | `\n` / `\r` / `\r\n`. |
| `encoding` | VARCHAR | `utf-8` | **Add this** — `utf-16`, `latin-1` common in Oracle/telco dumps. |
| `compression` | VARCHAR | auto | `auto`/`gzip`/`zstd`/`none`. **Add this** — you ingest `.csv.gz`. |
| `max_line_size` | BIGINT | 2000000 | Raise for very wide rows (537-col vouchers, packed records). |
| `parallel` | BOOL | true | |

### 3.2 Schema & types — **the `columns`/`types`/`names` distinction the PDF gets wrong**
| Param | Effect |
|---|---|
| `columns={name:type,…}` | Forces **names + types** and **disables the sniffer entirely**. This is what the engine uses (all `VARCHAR`). |
| `types=[…]` or `{name:type}` | Overrides types **positionally or by name**, keeps sniffing the rest. |
| `names=[…]` | Overrides **names only**. |
| `all_varchar` | BOOL, skip type detection → all VARCHAR. (Equivalent to the engine's hard-coded `columns`.) |
| `auto_detect` | BOOL — master switch for the sniffer. Engine sets `false`. |
| `sample_size` | BIGINT (default 20480; `-1` = whole file). |
| `auto_type_candidates` | LIST of types the sniffer may pick. |

### 3.3 Errors / messy data / footers
| Param | Notes |
|---|---|
| `ignore_errors` | Drop unparseable rows instead of failing the query. Engine sets `true`. |
| `null_padding` | Pad short rows with NULL on the right. Engine sets `false` (short rows → reject). |
| `strict_mode` | BOOL, default `true`; `false` tolerates quote/column drift. |
| `nullstr` / `null` | LIST → SQL NULL, e.g. `['','NULL','NaN','N/A','-']`. |
| `store_rejects` | BOOL — **the engine's real error channel.** Rejected rows land in `reject_errors`/`reject_scans`, drained to `errors/<base>_errors.csv`. **Missing from the PDF; document it.** |
| `rejects_table`, `rejects_scan`, `rejects_limit` | Tune the reject tables. |

### 3.4 Format (read-time typing — mostly redundant in this engine, see §2 note)
`dateformat`, `timestampformat`, `decimal_separator`.

### 3.5 Multi-file
`filename` (inject source path), `hive_partitioning` (dir → columns), `union_by_name` (align by
header across files). Note: this engine already does its own batching + Hive output, so these are
mainly for ad-hoc analyst SQL, not the ingest path.

### 3.6 Diagnostics
`FROM sniff_csv('file')` returns the dialect DuckDB *would* pick — invaluable when authoring a new
profile. Keep the PDF's Recipe C.

---

## 4. Beyond CSV — the other DuckDB-native readers

### 4.1 JSON / NDJSON — semi-structured `[NATIVE]`
```sql
-- newline-delimited JSON (one object per line) → typed columns
FROM read_ndjson('events.jsonl', columns={id:'BIGINT', ts:'TIMESTAMP', payload:'JSON'});
-- nested arrays/objects: read as JSON, then unnest / json_extract in mapping rules
FROM read_json('nested.json', maximum_object_size=104857600);
```
Pairs with `mapping.rules[]` of `transformType: EXPR` using `json_extract(payload,'$.a.b')`,
`UNNEST`, etc. **Frontend = `read_json`; backend unchanged.**

### 4.2 Whole-file readers — the gateway to fixed-width, LDIF, KV, flat XML `[NATIVE]`
```sql
read_text('f.dat')  -- → one row, column `content` VARCHAR (whole file)
read_blob('f.bin')  -- → one row, column `content` BLOB
```
With `read_text` + `string_split(content,'\n')` + `UNNEST` you get one row per physical line, then
`substring`/`regexp_extract` carve fields. This turns DuckDB into a positional/regex parser for any
*line-regular* text format without writing Java (see fixed-width and LDIF profiles below).

---

## 5. The proposed comprehensive `parsing:` grammar `[PROPOSED]`

A single `parsing:` block per source selects a **frontend** and its options. It generalizes today's
`csv_settings` (which remains the `delimited` frontend) and is designed so the existing
`raw.fields[]` / `mapping.rules[]` / `partitions[]` backend is reused verbatim.

```yaml
parsing:
  frontend: delimited        # delimited | fixedwidth | json | text_regex | xml | plugin
  # ── shared options ──────────────────────────────────────────────────────────
  encoding: utf-8            # utf-8 | utf-16 | latin-1
  compression: auto          # auto | gzip | zstd | none

  # ── frontend: delimited (today's csv_settings, plus the [NATIVE] knobs) ──────
  delimited:
    delimiter: ","
    quote: '"'
    escape: '"'
    comment: "#"
    has_header: true
    skip_header_lines: 0
    skip_junk_lines: 0       # adaptive preamble scan (Java path); -1 = unlimited (SQL*Plus)
    skip_tail_lines: 0       # footer drop ("N rows selected.")
    skip_tail_columns: 0
    null_strings: ["", "NULL", "NaN", "N/A"]
    strict: true             # false → tolerate quote/column drift
    engine: auto             # auto | duckdb | java

  # ── frontend: fixedwidth (positional slices over read_text) ──────────────────
  fixedwidth:
    record: line             # line | bytes
    record_length: 0         # for record: bytes (0 = newline-delimited)
    fields[3]{name,start,length}:    # 0-based start, char length
      ACCOUNT_NUMBER, 0,  16
      EVENT_DATE,     16, 20
      AMOUNT,         36, 12
    trim: true

  # ── frontend: json ───────────────────────────────────────────────────────────
  json:
    format: newline          # newline (NDJSON) | array | auto
    records_path: "$"        # JSONPath to the record array, if wrapped
    columns:                 # optional explicit typing; else all JSON
      id: BIGINT
      ts: TIMESTAMP

  # ── frontend: text_regex (one record per line/block, named capture groups) ───
  text_regex:
    record_split: "\n"       # line | "\n\n" (blank-line blocks, e.g. LDIF entries)
    pattern: "^(?P<key>[A-Z_]+): (?P<value>.*)$"
    # OR a positional capture → field map via mapping.rules[] EXPR over regexp_extract

  # ── frontend: plugin (binary / grammar-driven) ───────────────────────────────
  plugin:
    ingester: com.acme.etl.Asn1CdrIngester     # StreamingFileIngester FQCN
    segments:                # multi-event-type output (see plugins.md)
      CALL: config/cdr/call_schema.toon
      SMS:  config/cdr/sms_schema.toon
    ingester_config:         # free-form, plugin-specific (the "grammar" pointer)
      asn1_grammar: config/cdr/ber.asn1
      byte_order: big
      record_tag: "0xA1"
```

**Mapping to reality:** `frontend: delimited` and `frontend: plugin` are **already implemented**
(today's `csv_settings` and `processing.ingester`/`segments`); `fixedwidth`, `json`, `text_regex`
are the new work, and each is a thin frontend that produces VARCHAR rows for the existing backend.
See §7 for the build order.

---

## 6. Per-format profiles (copy-paste starting points)

### 6.1 Standard delimited CSV/TSV `[LIVE]`
```yaml
parsing: { frontend: delimited, delimited: { delimiter: ",", has_header: true, engine: auto } }
```

### 6.2 SQL*Plus / Oracle spool dump `[LIVE]`
Banner + `Enter password:` + `ERROR/ORA-…` preamble + `N rows selected.` footer. See the adaptive
junk-scan in `CsvIngester`.
```yaml
parsing:
  frontend: delimited
  encoding: latin-1                 # [PROPOSED] — Oracle dumps are often not UTF-8
  delimited:
    delimiter: ","
    engine: java                    # skip_* force the Java path anyway
    has_header: true
    skip_junk_lines: -1             # unlimited adaptive preamble scan
    skip_tail_lines: 2              # "N rows selected." + blank
# Oracle DD-MON-YYYY dates handled at transform time:
#   date_formats / timestamp_formats: "%d-%b-%Y %H:%M:%S", "%d-%b-%Y"
```

### 6.3 Fixed-width / fixed-length records `[PROPOSED via read_text]`
No native DuckDB fixed-width reader. Two routes:
- **Native (no Java):** `read_text` → split into lines → `substring(line, start+1, length)` per field.
  ```sql
  SELECT substring(line,1,16)  AS ACCOUNT_NUMBER,
         substring(line,17,20) AS EVENT_DATE,
         substring(line,37,12) AS AMOUNT
  FROM (SELECT UNNEST(string_split(content, chr(10))) AS line FROM read_text('f.dat'))
  WHERE length(line) > 0;
  ```
  The `fixedwidth.fields[]` grammar in §5 compiles to exactly this.
- **Binary fixed-length** (no newlines, `record_length: N`): a `[PLUGIN]` that reads N-byte records
  and `emit()`s slices — trivial `StreamingFileIngester`.

### 6.4 JSON / NDJSON `[NATIVE]`
```yaml
parsing:
  frontend: json
  json: { format: newline, columns: { id: BIGINT, event_ts: TIMESTAMP, body: JSON } }
# mapping.rules[] then uses EXPR: json_extract_string(body,'$.user.id'), etc.
```

### 6.5 XML `[PLUGIN]` (or `text_regex` for *flat* XML)
DuckDB has no core XML reader. For flat, one-element-per-line XML, `text_regex` with
`regexp_extract` works. For real nested XML, write a `StreamingFileIngester` around a StAX/SAX
streaming parser and `emit()` per element — streaming (not DOM) keeps memory bounded on large files.

### 6.6 LDIF `[PROPOSED via text_regex]`
LDIF = blank-line-separated entries; within an entry, `attr: value` lines (and `attr:: base64`).
```yaml
parsing:
  frontend: text_regex
  text_regex:
    record_split: "\n\n"            # one record per LDIF entry (blank-line delimited)
    pattern: "^(?P<attr>[\\w;-]+):: ?(?P<value>.*)$"   # handle ':' and '::' (base64)
# Pivot attr/value to columns via mapping rules, or emit long-format (dn, attr, value).
```
Caveats `text_regex` must handle: **line folding** (continuation lines start with a space) and
**base64** (`::`). If your LDIF uses either heavily, prefer a `[PLUGIN]` that unfolds first.

### 6.7 ASN.1 / BER-TLV (telco CDRs) `[PLUGIN]` — your existing parser
DuckDB **cannot** parse ASN.1. Wrap your existing ASN.1 parser as a `StreamingFileIngester`:
```java
public class Asn1CdrIngester implements StreamingFileIngester {
  public void ingest(File f, RecordSink sink, int srcId, PipelineConfig cfg) throws Exception {
    String grammar = (String) cfg.schemas().ingesterConfig().get("asn1_grammar"); // your .asn1
    try (var decoder = Asn1Decoder.open(f, grammar)) {     // YOUR parser
      sink.define("CALL", List.of("ID","EVENT_TYPE","EVENT_DATE","DURATION"));
      for (var rec : decoder) {
        switch (rec.tag()) {
          case CALL -> sink.emit("CALL", rec.id(), "CALL", rec.date(), rec.duration());
          case SMS  -> sink.emit("SMS",  rec.id(), "SMS",  rec.date());
          case BAD  -> sink.reject(rec.tagName());
          default   -> sink.junk();
        }
      }
    }
  }
}
```
Wire via `parsing.frontend: plugin` (today: `processing.ingester` + `segments`). The **ASN.1
grammar file is plugin config** (`ingester_config.asn1_grammar`) — the framework stays
format-agnostic; DuckDB still does typing/partitioning on the emitted records. This is the
"grammar/config to parse and emit events" you described, and the multi-`segment` mechanism is
literally "emit events" (CALL/SMS/… → separate event tables).

### 6.8 Custom binary (fixed or TLV) `[PLUGIN]`
Same shape as 6.7. Put `record_length`, `byte_order`, field offsets in `ingester_config`; your
ingester reads structs and `emit()`s. The `large_file_bytes`/generation mode (plugins.md §"Execution
modes") keeps TB-scale binaries within bounded memory.

---

## 7. Decision matrix — pick a frontend

| Format | Frontend | Native to DuckDB? | Status |
|---|---|---|---|
| CSV/TSV/pipe, well-formed | `delimited` (engine `auto`/`duckdb`) | yes | `[LIVE]` |
| SQL*Plus / Oracle spool | `delimited` (engine `java`, skip_*) | partial | `[LIVE]` |
| Dirty/“total chaos” text | `delimited` (Java path, null_strings) | yes | `[LIVE]` partial |
| Fixed-width text | `fixedwidth` → `read_text`+substring | via read_text | `[PROPOSED]` |
| Fixed-length binary | `plugin` (record slicer) | no | `[PLUGIN]` |
| JSON / NDJSON | `json` → `read_json`/`read_ndjson` | yes | `[NATIVE]`/`[PROPOSED]` |
| Flat XML / key-value | `text_regex` | via read_text | `[PROPOSED]` |
| Nested XML | `plugin` (StAX streaming) | no | `[PLUGIN]` |
| LDIF | `text_regex` (or `plugin` if folded/base64) | via read_text | `[PROPOSED]` |
| ASN.1 / BER-TLV CDR | `plugin` (your ASN.1 parser) | no | `[PLUGIN]` |
| Proprietary binary | `plugin` | no | `[PLUGIN]` |

**Rule of thumb:** line-regular text → a DuckDB-native frontend (`read_csv`/`read_text`+regex);
structurally-typed text → `read_json`; anything binary or grammar-driven → `StreamingFileIngester`.

---

## 8. Implementation roadmap (to make `[PROPOSED]` real)

Smallest-to-largest, each independently shippable and behavior-preserving for existing CSV sources:

1. **Surface the safe `[NATIVE]` knobs on the existing CSV path** — thread `encoding`, `compression`,
   `quote`, `escape`, `comment`, `null_strings`, `strict` through `DuckDbCsvIngester.buildReadSpec`
   (and the Java `CsvIngester` where applicable). Pure additive; defaults preserve current behavior.
2. **`fixedwidth` frontend** — a new `BatchIngestStrategy`/ingester that compiles `fields[]` offsets
   to the `read_text`+`substring` SQL in §6.3 (text) and a byte-slicer (binary). No new dependency.
3. **`json` frontend** — wrap `read_json`/`read_ndjson` as a frontend; lean on `EXPR` mapping rules
   (`json_extract`) for nesting. No new dependency.
4. **`text_regex` frontend** — `read_text` + `string_split` + `regexp_extract` with named groups;
   covers LDIF and flat XML. Document the fold/base64 caveats; escalate to `[PLUGIN]` when violated.
5. **Adopt the unified `parsing:` block** — make `csv_settings` an alias for `parsing.delimited` and
   `processing.ingester`/`segments` an alias for `parsing.plugin`, so existing toons keep working.

Each step reuses `mapping.rules[]` + `partitions[]` + lineage/audit unchanged — the whole point of
the frontend/backend split.

---

## 9. Corrections to fold back into the PDF

1. Split the conflated **`columns` vs `types` vs `names`** row (§3.2 above).
2. Add **`store_rejects`/`rejects_table`** — it's how errors are actually captured.
3. Add **`encoding`, `compression`, `max_line_size`** — required for `.gz` and non-UTF-8 dumps.
4. Add **`read_json`/`read_ndjson`** and **`read_text`/`read_blob`** sections — they unlock
   everything beyond CSV.
5. Mark **`exclude_prefixes` / `exclude_target_column`** clearly as **app-side, not DuckDB** (and
   note they are not implemented here; the equivalent today is `skip_junk_lines` + a `WHERE`/`EXPR`).
6. State the engine's **read-as-VARCHAR-then-`TRY_STRPTIME`** typing model, so readers know
   `dateformat`/`decimal_separator`/sniffer settings are largely redundant on the ingest path.
