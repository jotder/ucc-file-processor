# Reference Phase-2 Engine Semantics — implementation plan

**Status: IN FLIGHT — P0 + P1 + P4 SHIPPED 2026-07-24 (config model + `upsert` engine + Catalog Stream
grouping, all GAUNTLET-green). P2 (`scd2` as-of view) + P3 (compaction + `refresh_seconds` timer)
remain. Multi-session build; plan stays in `superpower/` until the last phase lands.**

_Shipped this shift (P0+P4): the `reference:` block (`load: replace|upsert|scd2` + `key` + `refresh_seconds`)
and the `stream:` membership key on `PipelineConfig`, mirrored in `ConfigSpecs.pipeline()` (enum +
`reference-upsert-requires-key` rule); `MetadataGraphBuilder` now groups pipelines sharing a `stream:` under
one `stream:<logical>` node (collapse model — members keep their own schema/event nodes; a 1:1 Stream is
byte-for-byte unchanged, no `members[]` attr). `load: replace` is the default ⇒ every existing pipeline is
untouched. As-built → `docs/okf/backend/control-plane/onboarding-authoring.md`._
Backlog origin: §3 *Onboarding (Stream/Reference)* — "Reference Phase-2 engine semantics: cache/upsert/SCD
versioning + refresh scheduling, row-level dedup, Stream grouping (GLOSSARY §3/§6-B roadmap)".
GLOSSARY §6-B itself records: *"v1 load semantics are full-replace; the cached/SCD nature above remains
the Phase-2 engine backlog."* This plan turns that roadmap into buildable phases.

## 1. Current state (verified 2026-07-24 — what Phase-2 builds on)

- A **Reference is not a distinct config type**: it's a `PipelineConfig` with `produces: reference`
  (`inspecto-etl/…/PipelineConfig.java:500-514`, default STREAM) or an `EnrichmentConfig.Reference`
  binding (`{name, path|ref, format}`, `EnrichmentConfig.java:78-85`).
- **Load is fully ephemeral**: every enrichment run opens a throwaway scratch DuckDB, `CREATE VIEW`s
  the reference straight off Parquet/CSV, uses it once, deletes the scratch DB
  (`EnrichmentEngine.java:113-123,172`). Zero caching, zero versioning, re-read from disk per run.
- **Write is whole-partition replace everywhere**: `PartitionWriter` = `COPY … OVERWRITE_OR_IGNORE 1`
  + atomic rename to a fixed per-partition filename (`PartitionWriter.java:101-161`). No row identity,
  no MERGE, no upsert on data — the only keyed upsert in the codebase is ledger *metadata*
  (`DbAcquisitionLedger` DELETE-then-INSERT on `(source_id, relative_path)`).
- **Dedup is file-level only**: acquire-layer `DuplicatePolicy` (PATH/METADATA/CHECKSUM/ETAG) +
  `AcquisitionLedger`. No row-level distinct/upsert anywhere in the transform/write path
  (grep-confirmed). The `dbWatermark` incremental-pull seam (`AcquisitionLedger.java:44-62`) is the
  closest "incremental" precedent — acquisition-side only.
- **Stream is strictly 1:1 with a pipeline** (`IdScheme.stream(pipeline)` → `"stream:"+name`,
  `IdScheme.java:35-37`); GLOSSARY §3's "one sub-system = one Stream over many pipelines" grouping is
  named-but-unbuilt.
- **Proven scheduling machinery exists**: `Scheduler.everySeconds` (cancellable `ScheduledFuture`) +
  `Scheduler.cron()` (`CronHandle`), with `EnrichmentService.armSchedule`'s per-name
  cancel-and-rearm bookkeeping (`EnrichmentService.java:86,123-134`) — reusable, just never pointed
  at reference data.

## 2. The core architectural decision (D1): where versioned reference state lives

Three candidates were weighed:

- **(a) Mutable embedded DuckDB store** (a durable `.db` per reference, `DbAcquisitionLedger`-style).
  Rejected: introduces a second class of durable mutable state with single-writer constraints, invisible
  to the Parquet/glob read path every consumer (`DatasetRelation`, `/db/query`, BI) already uses.
- **(b) Append-only Parquet + `__valid_from`/`__valid_to` maintenance**. Rejected: "closing out" a
  superseded row means rewriting old files — append-only in name only.
- **(c) CHOSEN — append-only Parquet, latest-version-wins + periodic compaction** ("SCD as data,
  not storage mutation"): each refresh **appends** rows stamped with system columns; the *current*
  view derives the winner per key at read time; a compaction job periodically rewrites the snapshot
  to bound read amplification. Preserves the immutable-Parquet + atomic-reveal write model wholesale,
  keeps every existing consumer working via plain SQL, and makes time-travel queries free.

### 2.1 System columns & views (design (c) mechanics)

Appended rows carry: `__key_hash` (canonical hash of declared key columns), `__valid_from`
(TIMESTAMP, load instant), `__op` (`upsert` | `delete` — tombstones), `__batch_id`.

- **Current view** (what enrichment and default consumers read):
  `SELECT * EXCLUDE(__…) FROM read_parquet(glob) QUALIFY row_number() OVER (PARTITION BY __key_hash
  ORDER BY __valid_from DESC) = 1` … `WHERE __op != 'delete'` — DuckDB `QUALIFY` is supported.
- **As-of view** (SCD-2 history surface): same window with `WHERE __valid_from <= :asOf`.
- **Compaction** (Phase P3): a maintenance task rewrites the store to just the current-view rows
  (optionally keeping N versions / a history horizon), using `MaterializeTask`'s stage-to-`.tmp` →
  `.stale`-hide → `ATOMIC_MOVE` reveal idiom (`MaterializeTask.java:70-115`). Compaction output IS
  the "cache": the fast path enrichment reads between refreshes.

## 3. Config surface (D2)

Load semantics belong on the **producer** (`produces: reference` pipeline), as a sibling block:

```toon
reference:
  key: [customer_id]        # declared identity — required for upsert|scd2
  load: replace             # replace (default, today's behavior) | upsert | scd2
  refresh_seconds: 0        # 0 = on-collect only (today); >0 arms a re-materialize timer (P3)
```

- `load: replace` stays the default → **Phase-0 is behaviorally inert** (backward compatible; existing
  authored TOON untouched — respects the "renaming breaks authored TOON" residual).
- `ConfigSpec`/`ConfigSafetyValidator` additions: `key` columns must exist in the pipeline schema;
  `upsert|scd2` without `key` = validation ERROR finding (fail closed, onboarding `blocked` chip
  surfaces it for free via the shipped `fieldPath` routing).
- `EnrichmentConfig.Reference` gains an optional `asOf` (consume history) — default current view.
- **Field names must be GLOSSARY-conformant** — confirm exact terms against §3/§6-B before build;
  do not coin synonyms (binding vocabulary rule).

## 4. Row-level dedup

Two distinct semantics, both cheap under design (c):

- **Within-batch dedup** (P1): a `DISTINCT`(-on-key, latest by an optional `order_by` column) fold in
  the Stage-1 projection before `COPY` — pure SQL addition inside `PartitionWriter`'s projection,
  applies to `upsert|scd2` loads (a batch containing the same key twice writes one version).
- **Cross-batch dedup** falls out of latest-version-wins: re-delivered identical rows produce a new
  version whose payload equals the prior — optionally skipped by comparing a row content-hash against
  the current version (`__row_hash` column, cheap equality probe in the append fold).

## 5. Refresh scheduling (P3)

`refresh_seconds > 0` arms a per-reference timer via the existing `Scheduler.everySeconds` +
`armSchedule` cancel-and-rearm pattern — the timer runs **compaction/re-materialization of the
current-view snapshot** (and, for `ref:`-by-name bindings, re-resolution), not a re-pull from the
origin (origin pulls stay the Collector's poll loop — separation of concerns preserved). Deregister
on pipeline unregister, mirroring the shipped `EnrichmentService.unregister` timer-cancel fix.

## 6. Stream grouping (P4 — separable; can ship independently)

Purely a **Catalog model** change, orthogonal to load semantics:

- `PipelineConfig` gains optional `stream: <logical-name>` (default = pipeline name ⇒ today's 1:1
  preserved byte-for-byte).
- `IdScheme.stream(logicalName)`; `MetadataGraphBuilder` groups member pipelines under one `STREAM`
  node (members keep their own pipeline nodes; the grouping key is the GLOSSARY §3 "membership").
- Onboarding UI initially unaffected (the stream picker lists logical names); graph lift + catalog
  tabs get the grouped node for free.
- **Recommendation: build P4 first or in parallel** — smallest, zero coupling to D1, immediately
  visible in the catalog.

## 7. Phasing & verify criteria

| Phase | Scope | Verify |
|---|---|---|
| **P0 ✅** | Config model: `reference:` block + `stream:` key, spec validation, parsing — `load: replace` default, no engine behavior change | ✅ SHIPPED 2026-07-24 — `PipelineConfigReferenceTest` (14) + `ConfigSpecsTest`/`ConfigLoaderTest` (declarative mirror) + column-exists + back-compat all green. Column-exists check enforced parser-side when a schema is resolved (skipped for draft-without-schema). |
| **P1 ✅** | `upsert`: system columns on append, within-batch dedup fold, current-view read in `EnrichmentEngine.referenceReader` | ✅ SHIPPED 2026-07-24 — `BatchIngestStrategy.stampReferenceVersions` (system cols + within-batch dedup + batch-unique file stem ⇒ append) gated on `producesReference() && reference().load()==UPSERT`; `EnrichmentEngine.currentView` (latest-per-`__key_hash`, `__op='delete'` dropped, system cols stripped) on the by-name read. `ReferenceVersionStampTest` (2) + `ReferenceUpsertCurrentViewTest` (1: two batches, changed+unchanged+new+tombstone) green. |
| **P2** | `scd2` history surface: as-of view + `EnrichmentConfig.Reference.asOf`; row content-hash skip | as-of query returns the version valid at t; identical re-delivery adds no version |
| **P3** | Compaction task (MaterializeTask idiom) + `refresh_seconds` timer + cache read-path switch | compacted store = current view exactly; timer cancel-on-unregister test; read amplification bounded |
| **P4 ✅** | Stream grouping (catalog) — separable | ✅ SHIPPED 2026-07-24 — collapse model in `MetadataGraphBuilder`; `MetadataGraphServiceTest` proves shared `stream:` → one node + `members[]`, and 1:1 default keeps label/attrs byte-for-byte (no `members[]`). Applies to STREAM origins only (references keep `ref:<pipeline>`). `/catalog/streams` is a separate per-collector projection, untouched. |

Each phase lands independently GAUNTLET-green (`mvn -o clean test`, full reactor). Estimated 3–4
focused sessions (P0+P4 could be one; P1 one; P2+P3 one to two).

## 8. Non-goals

- Fuzzy/approximate keys (explicit reconciliation non-goal carried over).
- CDC from external systems (the `dbWatermark` incremental pull stays acquisition's concern).
- DuckDB `spatial` or any extension loading (SqlSandbox lockdown stands).
- Cross-space references; per-row ACLs on reference data.
- Migrating existing `produces: reference` stores — `replace` remains valid forever; `upsert|scd2`
  are opt-in per pipeline.

## 9. Open decisions

| # | Decision | Status |
|---|---|---|
| D1 | Versioned-state storage | **Resolved: (c)** append-only + latest-wins + compaction — §2 |
| D2 | Config field names (`reference:`/`key`/`load`/`refresh_seconds`, `stream:`) | **Resolved & shipped (P0)** — confirmed GLOSSARY §3/§6-B conformant (Reference/Stream canonical; `load`/`key` per §3+§5); no synonyms coined |
| D3 | P4 ships separately? | **Resolved: yes** — shipped alongside P0 this shift, own commit; collapse node model (members keep child nodes) |
| D4 | History retention default for `scd2` (keep-forever vs horizon) | Defer to P3; default keep-forever, compaction `history_days` param |
| D5 | How a delete tombstone (`__op='delete'`) enters the store on the ingest path | **Deferred (not in P1).** P1's write path always stamps `__op='upsert'`; the current view merely *honours* an existing `delete` version (drops the key). No source→`__op` mapping convention is defined yet — decide the input signal (reserved column? Decision Rule consequence?) when a real delete-feed use case lands. |
| D6 | Within-batch dedup tie-break when a key appears twice in one batch | **Resolved (P1): arbitrary** (`QUALIFY row_number()=1`, no `ORDER BY`). The plan's optional latest-by-`order_by` column is a later refinement; add it only when a batch can legitimately carry ordered same-key versions. |
