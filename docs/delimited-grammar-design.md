# Design: Externalized Delimited Grammar + Native-Parser Expansion

> **Status: IMPLEMENTED (4.1) — Phases A–D landed, full reactor green.** Companion to
> [parsing-options-reference.md](parsing-options-reference.md) (broader frontend/backend model). This
> doc is *only* the delimited-text slice. Code: `PipelineConfig.resolveGrammar` + expanded
> `CsvSettings` (A), `DuckDbCsvIngester.readOptions` (B), `DuckDbCsvIngester.filterWhere` +
> `CsvIngester.RowFilter` (C), `BoundaryScanner` + `DuckDbCsvIngester.decideNative` (D). Tests:
> `DelimitedGrammarTest` (+ updated `DuckDbCsvIngesterTest`).
>
> **⚠ TOON list syntax.** JToon does **not** parse YAML-style inline arrays (`["a","b"]` is read as a
> single literal string). Author scalar lists with the tabular form `key[N]: v1, v2` (the same form
> `date_formats[2]: …` and `fields[N]{…}` already use). An empty list = omit the key.

## 1. Goal

**Reduce the custom Java delimited parser's footprint** by (a) moving the per-data-file-type parse
settings out of the pipeline toon into a reusable **grammar file**, and (b) translating the adaptive
"messy file" knobs into **native DuckDB `read_csv` parameters** so more delimited variants (notably
SQL*Plus dumps) are parsed by DuckDB's vectorized reader instead of the line-by-line Java
`CsvIngester`.

The Java parser stays as a correctness fallback. **Nothing that works today may regress.**

## 2. Non-goals

- Not touching fixed-width, JSON, XML, LDIF, ASN.1, or any binary format — those go through the
  Java `StreamingFileIngester` plugin and are out of scope here.
- Not changing the typing/transform/partition backend (`mapping.rules[]`, `partitions[]`,
  `TRY_STRPTIME`, lineage, audit).
- Not exposing the `columns`/`types`/`names` sniffer distinction — columns and their types come from
  the **event-definition toon** (`raw.fields[]`), exactly as today. The grammar never declares columns.

## 3. Decisions (locked)

| # | Decision |
|---|---|
| D1 | **Backward-compatible alias.** A pipeline may use `processing.grammar: <path>` (new) **or** inline `processing.csv_settings:` (today). Existing toons keep working untouched. |
| D2 | **Latest embedded DuckDB only.** No version guards. New `read_csv` options (`strict_mode`, `encoding`, `compression`, …) are passed straight through. |
| D3 | **Columns/types live in the event toon**, not the grammar. One grammar serves one *data-file type*; one-or-more event types reference it via the pipeline. |
| D4 | **Grammar is shared across a pipeline's event types.** A pipeline has exactly one delimited grammar; its `schemas[]` (event types) differ only in columns/types/partitions. |
| D5 | **New capabilities are welcome** (`exclude_prefixes`, `exclude_target_column`, `encoding`, `compression`, `strict_mode`, `null_strings`) and the boundary pre-scan enhancement. |

## 4. Config decomposition (3 files)

```
config/voucher/
  voucher.grammar.toon     ← NEW: HOW to tokenize (the old csv_settings, reusable)
  voucher_537.toon         ← event type (columns + types + partitions) — UNCHANGED
  voucher_116.toon         ← event type — UNCHANGED
  voucher_76.toon          ← event type — UNCHANGED
  voucher_pipeline.toon    ← references grammar + event types
```

### 4.1 Grammar file — `voucher.grammar.toon` (new)
```yaml
# Delimited tokenization grammar for the "voucher" data-file type. No columns here.
delimiter: ","
quote: '"'
escape: '"'
has_header: false
encoding: utf-8                 # [NEW] utf-8 | utf-16 | latin-1
compression: auto               # [NEW] auto | gzip | zstd | none
strict_mode: false              # [NEW, optional] tolerate quote/column drift; omit ⇒ DuckDB default
null_strings[3]: "", "NULL", "NaN"   # [NEW] → read_csv nullstr

# boundary detection — adaptive, resolved to native read_csv params (§6)
skip_header_lines: 0
skip_junk_lines: -1             # adaptive preamble scan; -1 = unlimited
skip_tail_lines: 2              # footer drop ("N rows selected.") — keeps the Java path (§6.3)
skip_tail_columns: 0

# row-level filtering (post-parse, injected as WHERE) — [NEW]
# (use the key[N]: form; omit a list entirely when empty)
exclude_prefixes[3]: "Start", "Stop", "Divider---"   # deny-list (prefix): drop rows matching any
# include_prefixes[1]: "DATA"        # allow-list (anchored prefix LIKE 'x%'): keep rows matching any
# include_regex[1]: "^DATA"          # allow-list (regexp_matches): keep rows matching any
# exclude_regex[1]: "rows selected"  # deny-list (regexp_matches): drop rows matching any
filter_target_column: 0         # 0-based selector index all four filters apply to

# transform-time date parsing (unchanged semantics)
date_formats[2]:      "%d-%b-%Y %H:%M:%S", "%d-%b-%Y"
timestamp_formats[2]: "%d-%b-%Y %H:%M:%S", "%d-%b-%Y"

engine: auto                    # auto | duckdb | java
```

### 4.2 Event toon — `voucher_537.toon` (unchanged)
`partitionKey:` / `partitions[]`, `raw.fields[]{name,selector,type}`, `mapping.rules[]`. As today.

### 4.3 Pipeline toon — one-line reference (mirrors `schema_file:`)
```yaml
processing:
  threads: 3
  duckdb_threads: 0
  file_pattern: "glob:**/*.csv"
  grammar: config/voucher/voucher.grammar.toon     # ← replaces the inline csv_settings block
  schemas[3]{column_count,file_pattern,schema_file,table}:
    76,  "glob:**/*other*", "config/voucher/voucher_76.toon",  voucher_other
    116, "glob:**/*main*",  "config/voucher/voucher_116.toon", voucher_main
    537, "",                "config/voucher/voucher_537.toon", voucher
```

## 5. Backward-compatible alias semantics (D1)

At config load, resolve the effective grammar map as:

1. If `processing.grammar:` is present → load that toon file into a map `G`.
2. If `processing.csv_settings:` is present → map `C`.
3. **Effective = `G` overlaid by `C`** (inline keys win, so a pipeline can override one grammar key
   locally without forking the file). If only one is present, use it. If neither, defaults apply
   (today's behavior).

This keeps every existing pipeline byte-for-byte valid (they have `C`, no `G`) and lets new
pipelines use `G` alone. The loader change is localized to the `csv_settings` block
(`PipelineConfig.load`, lines 341–356): replace `proc.get("csv_settings")` with a
`resolveGrammar(proc)` helper returning the overlaid map; downstream parsing is unchanged.

## 6. The core enhancement: boundary pre-scan → native `read_csv` params

### 6.1 Problem today
`DuckDbCsvIngester.usesDuckDb()` (lines 78–86) routes a pipeline to the **Java** parser whenever any
of `skip_junk_lines` / `skip_tail_lines` / `skip_tail_columns` is non-zero — because native
`read_csv` takes a *static* `skip=N` and cannot do adaptive preamble detection, footer trimming, or
extra-trailing-column tolerance. So every SQL*Plus-style source is stuck on the slow path.

### 6.2 Solution
Add a **cheap boundary pre-scan** that reads only the **head window** (first `K` lines, `K` ≈ the
`skip_junk_lines` cap or a few hundred) and a small **tail window** (last few KB), then resolves the
adaptive knobs into concrete native parameters. The whole-file parse is then native.

| Grammar knob | Resolved by pre-scan into | Native mechanism |
|---|---|---|
| `skip_junk_lines` (adaptive preamble) | integer `N` = index of first real data row (reuse `CsvIngester`'s column-count + echo-header logic, but only over the head window) | `read_csv(skip = skip_header_lines + N + (has_header?1:0))` |
| `skip_tail_columns` (extra trailing cols) | integer `W` = physical column width (from the head window) | declare `columns` of width `W`, **project only the wanted selectors** (selectors already drive the projection in `buildReadSpec`) |
| `include_prefixes` / `include_regex` / `exclude_prefixes` / `exclude_regex` (+ `filter_target_column`) | — (no scan needed) | inject a `WHERE` into the `CREATE TABLE … AS SELECT` (§6.2.1) |
| `skip_tail_lines` (footer) | see §6.3 | mostly free via `ignore_errors`; valid-row tail-drop is the residual case |
| reject rows | — | already native via `store_rejects` → `errors/<base>_errors.csv` |

The generated relation becomes (extending `buildReadSpec`, lines 158–204):
```sql
CREATE TABLE "<t>" AS
SELECT "c4" AS "REVERSAL_DATE", "c0" AS "ACCOUNT_NUMBER", ...
FROM read_csv('<file>',
       columns={'c0':'VARCHAR', ... ,'c<W-1>':'VARCHAR'},   -- W from pre-scan
       delim='<delim>', header=false,
       skip=<resolved N>,                                    -- N from pre-scan
       encoding='<encoding>', compression='<compression>',  -- new pass-throughs
       nullstr=[<null_strings>], strict_mode=<strict_mode>,
       ignore_errors=true, null_padding=false,
       auto_detect=false, store_rejects=true)
WHERE (include + exclude prefix predicate — see §6.2.1) ;
```

### 6.2.1 Row-filter predicate (`include_*` / `exclude_*`)
All four filters target the same column (`filter_target_column`, selector `<t>`) and compile to one
`WHERE` clause. Prefixes use anchored `LIKE 'x%'`; regexes use DuckDB `regexp_matches(col, pat)`
(contains-semantics — anchor with `^…$` for a full match).

- **Include (allow-list)** = match *any* include pattern, prefix **or** regex, OR'd together:
  `("c<t>" LIKE 'A%' OR "c<t>" LIKE 'B%' OR regexp_matches("c<t>", 'r1') OR regexp_matches("c<t>", 'r2'))`
- **Exclude (deny-list)** = drop if it matches *any* exclude pattern; the keep-predicate is the
  negation, AND'd:
  `("c<t>" NOT LIKE 'X%' AND NOT regexp_matches("c<t>", 'rx'))`
- **Both present** → `AND`-combined, so the deny-list wins on overlap (natural precedence):
  `(include-OR) AND (exclude-keep)`
- **None present** → no `WHERE` emitted (current behavior).

Pattern values are SQL-escaped before interpolation; `filter_target_column` is validated against the
declared selectors at config load.

> A handy use for the include filters: a record-type-tagged delimited file where only some line
> prefixes/patterns are relevant to a given event type — keep just those rows without a custom parser.
> Rows dropped by these filters are *filtered*, not *rejected* — they don't appear in `*_errors.csv`.

### 6.3 `skip_tail_lines` — the one nuance
- **Junk footers** (`N rows selected.`, page banners) have the wrong column count → already dropped
  by `ignore_errors=true` (and counted as rejects). **Free.** Covers the common SQL*Plus case.
- **Valid-looking trailing rows** that must be dropped genuinely need a row count. Options:
  (a) detect a footer *pattern* in the tail window and add it to `exclude_prefixes` (cheap, no full
  scan); (b) `QUALIFY row_number() OVER () <= (count(*) - N)` (correct but forces a full materialize).
  Default to (a); fall back to the **Java path** when (b) would be needed and the footer isn't
  pattern-detectable. This is the explicit "can't resolve → Java fallback" seam.

### 6.4 Routing change
`usesDuckDb()` becomes: native when the pre-scan **successfully resolves** all active knobs;
otherwise Java. Concretely:
- `skip_junk_lines != 0` → run pre-scan; if first-data-row found within the head window → native
  with resolved `skip`; else Java.
- `skip_tail_columns != 0` → native (declare width `W`, project selectors).
- `skip_tail_lines != 0` → native if footer is junk or pattern-detectable; else Java.
- any of `include_prefixes` / `include_regex` / `exclude_prefixes` / `exclude_regex` non-empty →
  native (WHERE injection).

Result: the **adaptive feature is preserved**, but the per-row work moves to DuckDB. The Java parser
runs only for the genuinely irreducible cases.

## 7. Feature-preservation guarantee

| Feature | Preserved by |
|---|---|
| Adaptive preamble skip | pre-scan reuses the existing column-count + echo-header logic (CsvIngester lines 139–168) |
| Footer drop | `ignore_errors` (junk) + pattern exclude (valid) + Java fallback |
| Extra trailing column trim | declared width + selector projection |
| Reject CSV (`*_errors.csv`) | native `store_rejects` (already implemented) |
| Date parsing via `date_formats`/`timestamp_formats` | unchanged — still transform-time `TRY_STRPTIME` |
| Multi-event-type (`schemas[]`) | unchanged — grammar is shared, events differ |
| `engine: java` escape hatch | unchanged — forces Java path for any awkward source |

## 8. Implementation plan (phased, each green + behavior-preserving)

> **All four phases are implemented (4.1).** One scope refinement landed during the build: `skip_tail_lines`
> (footer-line dropping) intentionally **stays on the Java path** — making it byte-identical natively
> isn't reliable (junk footers become rejects with different audit counts; valid-row footers need a full
> count; gz tails are expensive). SQL*Plus footers are instead dropped cleanly on the native path via an
> `exclude_regex` (e.g. `"rows selected"`), so the preamble (`skip_junk_lines`) **and** footer both go
> native without `skip_tail_lines`. `decideNative` scans every batch member and falls back to Java
> wholesale if any member's boundaries don't resolve — so nothing regresses.

**Phase A — grammar externalization (mechanical, low risk). ✅**
- `PipelineConfig.load`: add `resolveGrammar(proc)` (load `processing.grammar` toon, overlay inline
  `csv_settings`). Touch only lines 341–356.
- Add `processing.grammar` to `ConfigSpecs` + `ConfigValidator` (warn if both grammar file missing
  and no inline csv_settings).
- Tests: a pipeline with external grammar parses identically to the same inline `csv_settings`
  (golden-equality test); inline-only and grammar-only and overlay all covered.
- Migrate `voucher_pipeline.toon` to a grammar file as the worked example (keep one inline pipeline
  in tests to prove the alias).

**Phase B — new pass-through options (additive).**
- Thread `encoding`, `compression`, `strict_mode`, `null_strings` from the grammar into
  `DuckDbCsvIngester.buildReadSpec` (and ignore/duplicate in `CsvIngester` where meaningful).
  Defaults preserve current behavior.
- Tests: a latin-1 file, a `.gz` file, a file with `NULL`/`NaN` sentinels.

**Phase C — row filters (`include_prefixes`/`include_regex`/`exclude_prefixes`/`exclude_regex` +
`filter_target_column`) (additive WHERE).**
- Build the combined predicate (§6.2.1) with SQL-escaping and inject into both `ingest` and
  `createRawInputView` SQL.
- Validate `filter_target_column` against declared selectors.
- Tests: exclude-prefix-only (Start/Stop/Divider dropped), include-prefix-only (allow-list subset),
  include/exclude **regex** variants (`regexp_matches`), both-together (deny wins on overlap);
  row counts + lineage correct; filtered rows absent from `*_errors.csv`.

**Phase D — boundary pre-scan → native routing (the headline).**
- New `BoundaryScanner` (head + tail window) returning `{skip, physicalWidth, footerPattern?}`.
- Rework `usesDuckDb()` + `buildReadSpec` to consume the scan result.
- Keep Java fallback for unresolved cases.
- Tests: SQL*Plus fixture (banner + password + ORA error + `N rows selected.` footer) now parses on
  the **native** path with identical output to the Java path (parity test); a footer-of-valid-rows
  fixture falls back to Java; benchmark shows the native speedup.

**Risks / mitigations**
- *Pre-scan vs full-file disagreement* (preamble longer than head window) → cap with explicit Java
  fallback; log which path ran.
- *`columns` width when tail columns vary per row* → declare max observed width in head window;
  rows wider still rejected by `ignore_errors` (same as today's too-many-columns rule).
- *Behavior drift* → every phase ships with a Java-vs-native parity test on real fixtures before the
  routing flips.

## 9. Resolved decisions

| # | Decision |
|---|---|
| D6 | **Grammar file naming = `*.grammar.toon`** (e.g. `voucher.grammar.toon`). |
| D7 | **Row filters support both anchored prefix (`LIKE 'x%'`) and regex (`regexp_matches`)** from the start — `include_prefixes`/`exclude_prefixes` + `include_regex`/`exclude_regex`, all sharing `filter_target_column` (§6.2.1). |
| D8 | **Pre-scan head-window `K = max(skip_junk_lines, 1024)` lines.** When `skip_junk_lines = -1` (unlimited), scan the head until the first data row is found, capped by a safety bound (configurable later); not finding one within the window → Java fallback. |
