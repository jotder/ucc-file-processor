# Stream & Reference Onboarding — guided authoring over Stage‑1 pipelines

**Status: ACTIVE — design agreed with product owner 2026-07-16 (interview). P0 (backend seams)
SHIPPED 2026-07-16: reactor 1491/0/0/3 (baseline 1467/0; +24 new tests), UNCOMMITTED. P1+ awaiting
operator go ("wait after P0").**
Owner surface: Catalog ▸ Streams / References. Vocabulary per `docs/GLOSSARY.md` (§2 Connectivity, §3 Schema
& Catalog, §5 Pipeline). Companion ground-truth: `okf/backend/` (Stage‑1 architecture, pipeline-graph),
`okf/frontend/` (pipelines editor, catalog).

---

## 1. Problem

Onboarding a **Stream** (event/fact data origin) or **Reference** (dimension origin) today has **no UI
path at all**:

- Catalog Streams derive 1:1 from **Stage‑1 `PipelineConfig` TOONs** (`stream:<pipeline>`,
  `MetadataGraphBuilder.build()` — config-derived, no run needed). Those TOONs are authored **by hand or
  seed script** today.
- The graph editor authors the separate **Pipeline Graph** (`/pipelines/authored/*`, `*_flow.toon`) which
  runs as a `JobType.PIPELINE` job over data **already at rest** (`source_store` seeds only,
  `PipelineJobRunner`) — it **cannot acquire**, and graph→ingestion compile-back is unimplemented
  (`PipelineLift.java:17`, T5 backlog).
- References are worse: they exist only inside an enrichment's `references:` map (`{name, path, format}`),
  re-read from disk every run — no standalone registration, no managed load/refresh, no cache/upsert.

Builder pain: blank-canvas graph editor guides toward the wrong artifact; prerequisites (Connection,
grammar, schema) scattered across menus; no progress model for multi-session work; validation comes last.

## 2. Goal

A **guided onboarding surface** (stage rail + tabs, NOT a locked stepper) that authors the real Stage‑1
pipeline config underneath — resumable across sessions/shifts, testable per stage against one sample,
kind-aware (Stream vs Reference), reusing the existing node-config components. The graph editor stays for
advanced at-rest processing; `PipelineLift` remains the read-only "View as graph" projection.

## 3. Decisions (locked in interview, 2026-07-16)

| # | Decision | Choice |
|---|----------|--------|
| D1 | Kinds in v1 | **Both.** Streams AND References; References become *minimally first-class* via a small engine seam (pipeline declares it produces a Reference Dataset). Full GLOSSARY §3 Reference semantics (cache/dedup/SCD versioning) = explicit Phase‑2 engine track. |
| D2 | Home / IA | **Extend Catalog tabs.** Onboard CTAs + lifecycle/readiness columns on the existing Catalog ▸ Streams/References tabs; **Streams becomes the default tab**. ⛔ Do NOT rename the Catalog section "Streams" (owner floated it): Catalog also holds Tables/KPIs/Lineage/Usage, and `GLOSSARY.md` §3 binds **Catalog** = the index of Schemas and Datasets — renaming would break one-word-one-concept. Promotion of the two tabs to a top-level section stays a cheap, reversible follow-up if usage demands it. |
| D3 | Draft persistence | **Inactive pipeline = draft.** First stage writes a minimal spec-valid config (`active:false`, name + space-derived dirs) via `POST /config/write`; every later stage persists via `overwrite=true` (mtime hot-reload). Go-live flips `active:true`. Server-side ⇒ shift-handover safe; zero new storage. Needs a tiny config **delete** route for draft discard (none exists today). |
| D4 | Validation depth v1 | **Chain existing tests.** One captured sample threads through the existing scratch endpoints (connection test/sample, grammar/schema/transform/sink `/test`) so each stage shows *the sample after that stage*; per-stage `POST /validate` for structural findings. Server-side end-to-end sample-run endpoint = Phase 2. |

## 4. Architecture

### 4.1 The artifact

The guided editor reads/writes **one Stage‑1 `PipelineConfig` TOON** (plus, optionally, one companion
`EnrichmentConfig`). Stage → config-block mapping:

| Stage (Stream) | Config blocks | Reused editor |
|---|---|---|
| 1. Create | `name`, `dirs.*` (space-derived defaults), description | small form (schema-form) |
| 2. Collection | `collector:` block (⚠️ P1 correction: the TOON key is **`collector:`** — the §4.1 draft's "`source:` residual" note was stale; commit `f462ece` did rename it) + Connection ref + file-level duplicate policy (`Duplicate` marker/fingerprint — the only dedup that exists) | new **connection picker** + `ConnectionFormDialog` create-in-place |
| 3. Parsing | `parsing:` (the 4 engine-real authorable frontends; see U2 deviation §6) | schema-form + real `POST /config/preview/parsing` |
| 4. Schema & Mapping | `schema_file`/`schemas[]`, filter/map | schema `/test` (TRY_CAST) + schema-form |
| 5. Enrichment *(optional)* | companion `EnrichmentConfig` (trigger `on_pipeline`, references, SQL) | schema-form + SQL editor (CodeMirror, from data-table pro) |
| 6. Dataset & Go-live | `output:` (format/compression/ducklake), `trigger:`, `active:` | schema-form + readiness gate |

**Reference stage set:** Create → Collection → Parsing → **Keys & Load policy** (v1 honest scope:
full-replace/"override" only — that is today's actual semantics) → **Publish** (`produces reference` seam,
§5 B1, + go-live). GLOSSARY §3 already *specifies* the target Reference nature (incremental-or-dump,
deduplicated, cached/versioned) — that spec is the Phase‑2 engine backlog, not a v1 UI promise.

### 4.2 Readiness model (what makes multi-session work *confident*)

Per-stage status, computed — never a stored wizard state:
- **Not configured** — block absent.
- **Configured** — block present and spec-complete (`ConfigSpecs` / scoped `/validate` findings).
- **Validated** — stage test passed *this session* (ephemeral in v1; recomputed on open).
- **Blocked** — validate ERROR findings.

Go-live gate: all required stages ≥ Configured, whole-config `/validate` has no ERRORs; stage tests
recommended, not mandatory. List views show aggregate readiness. Lifecycle in lists: **Draft**
(`active:false`, incomplete) → **Ready** (`active:false`, complete) → **Live** (`active:true`).

### 4.3 Sample-as-thread

Stage 2 captures one sample (connection sample verb or upload) held in wizard session state; each later
stage chains the existing scratch-test endpoints so the builder always sees *their* data after that stage
(raw → parsed rows → cast/mapped rows → enriched rows). v1: session-held sample, re-capturable.

## 5. Backend work (small — everything else exists)

- **B1 — Reference production seam** *(the one real model addition)*: `PipelineConfig` gains a
  `produces: reference` declaration (spec + `ConfigSpecs`); `MetadataGraphBuilder` derives a standalone
  `REFERENCE_DATASET` node from such a pipeline (id scheme: keep `ref:` prefix, e.g. `ref:<name>` —
  disambiguate from enrichment-scoped `ref:<enrich>/<name>` at implementation); `EnrichmentConfig.Reference`
  gains an optional by-name binding resolving to the produced dataset's path (raw `path` stays supported).

  **B1 as-built (P0, 2026-07-16):** `PipelineConfig.Produces` enum (`@PublicApi 5.1.0`) + parser +
  `ConfigSpecs.pipeline()` enum field; origin node = `ref:<pipeline>` `REFERENCE_DATASET` (never a
  Stream) with schemas/tables hanging off it identically; `Reference{name,path,format,ref}` with
  exactly-one-of `path`/`ref` at parse; resolution lives in `EnrichmentEngine.referenceReader`
  (produced ref = Hive-partitioned glob over the producer's `dirs.database`, its output format,
  `hive_partitioning=true`) fed by a live registry supplier through
  `EnrichmentService(…, this::loadedPipelines)`. `GET /catalog/streams` filters
  `produces: reference` rows; `/catalog/references` picks the produced nodes up from the graph.
  ⚠️ Deliberate deferral: the bare `enrich` **Job** (`EnrichJob`) passes no pipeline context — a
  by-name ref there fails with a clear "needs the service's pipeline context" error; wire a supplier
  through `JobService` if that path ever needs by-name refs.
- **B2 — Config delete route** for draft discard (fail-closed per `endpoint` skill: write-root 503 →
  422 → path jail 403 → 409 → atomic; real-HTTP test class). Refuse deleting `active:true` configs.

  **B2 as-built (P0, 2026-07-16):** `DELETE /config/{type}/{name}` (+ optional `?subdir=`) in
  `ConfigRoutes`, capability `canAuthorWorkbench`, gate order 503 → unknown-type 404 → unsafe-name
  422 → jail 403 → missing 404 → active-pipeline 409 → atomic delete; documented in
  `docs/api/openapi-v1.json` (`ConfigDeleteResult`); `ControlApiConfigDeleteTest` covers every gate.

  **P0 bonus fix (draft lifecycle unblocker):** the legacy single-schema parser branch NPE'd on any
  config without `processing.schema_file` — schema-less configs could never parse, which would have
  broken D3's minimal first write. Now schema-less parse is a supported **draft** state
  (`active: false`), and arming without any schema fails at parse with a clear error
  (`PipelineConfigDraftTest`). ⚠️ P1 check: `PipelineLift` graph projection of a schema-less draft
  is unverified — confirm null-tolerance when wiring "View as graph".
- **B3 — Verify only:** pause/resume vs persisted `active:` relationship (unconfirmed); inactive pipelines
  appearing in `GET /catalog/streams` (it projects off the live `CollectorService` read-model — confirm
  drafts show; if not, small fix + expose `active`/readiness fields). Also note the known divergence
  between `GET /catalog` (structural graph) and `GET /catalog/streams` (collector projection) — don't
  widen it; onboarding lists read from one, documented choice.

  **B3 findings (P0, 2026-07-16):**
  1. `POST /runs/{name}/pause|resume` are **in-memory only** (a `paused` set in `CollectorService`;
     nothing persisted, state resets on restart). Runtime pause and the persisted `active:` flag are
     orthogonal — go-live/deactivate is a config write of `active:`, pause is a temporary operational
     hold. The onboarding lifecycle uses `active:` only.
  2. Inactive (draft) pipelines DO appear: `collectors()` iterates the `ConfigRegistry`, which indexes
     configs regardless of `active:`. The read-model rows now also carry `active` + `produces`, and
     `GET /catalog/streams` exposes `attrs.active` (asserted by `ControlApiStreamsTest`).
  3. Divergence decision: onboarding lists read the **projection pair** — `GET /catalog/streams`
     (collector read-model, now produces-filtered) + `GET /catalog/references` (structural graph
     `REFERENCE_DATASET` nodes, which now include pipeline-produced ones). The structural `GET /catalog`
     graph remains the lineage surface. Reconciling the two streams surfaces stays a non-goal here.
- **B4 — Config read-back route (added in P1; the resume gap):** recon found NO route returned a
  config's content — `/runs` gives `PipelineView`, `/validate {configPath}` gives findings — so
  "reopen a draft from server state" was impossible. **As-built:** `GET /config/{type}/{name}`
  (+`?subdir=`) in `ConfigRoutes`, same resolution/gates as DELETE (503→404→422→403→404), ungated
  like other reads, returns `{type,name,path,config}` (decoded map). Registered after
  `/config/spec/…` so spec lookups keep winning (first-match dispatch).
- **B5 — Parsing sample preview (added in P1; D4's raw→parsed hop):** the pipelines-editor's
  `POST /components/grammar/preview` + `/asn1/modules*` turn out to be **UI-mock-only** (no Java
  routes), and the saved-grammar `/test` edits a different model than Stage-1 `parsing:`.
  **As-built:** `POST /config/preview/parsing` `{config, sample_text}` → `ComponentPreview.parsing`
  — the draft is interpreted by the REAL config parser, then read with the engine's own DuckDB
  idioms per frontend (delimited dialect / fixed-width `substring` slices / NDJSON
  `json_valid`+`json_extract_string` with first-seen key discovery / `regexp_extract` named
  groups). Schema-less by design (it's the parse step); plugin + binary fixed-width → clear 422;
  stateless scratch DB; 1MB sample cap. Response `{frontend, columns, rowCount, rows, rejectedRows}`.
- **P1 recon corrections (bind future work):** (a) the collector TOON key is **`collector:`**, not
  `source:` — the vocab rename DID cover it; (b) **`POST /config/write` alone does not index a NEW
  file** — the running service only rescans known paths, so create = write **then**
  `POST /runs {configPath}` (register); later overwrites hot-reload by mtime, no re-register;
  (c) there is **no server-side connection sample verb** — v1 sample capture is upload/paste only
  (as D4 anticipated); (d) a schema-less inactive draft passes the `/runs` register gates (the
  schema-file findings only fire on a present-but-unresolvable path) — proven by
  `ControlApiOnboardingLifecycleTest`.
- **B6 — Schema sample preview (added in P2; the parsed→typed hop):** `POST /config/preview/schema`
  `{config:{raw:{fields:[{name,type}]}}, sampleRows}` → the existing `ComponentPreview.schema`
  (the Studio schema component's own TRY_CAST split) → `{columns, okCount, rejectedCount,
  rejectedRows}`. Stateless/ungated like the parsing preview; 400 on missing parts, 422 on a
  fieldless schema. Documented in openapi-v1 (`SchemaPreviewResult`). Also in P2:
  `ConfigSpecs.pipeline()` gained the missing `output.compression` FieldSpec (the validator is
  permissive on undeclared keys — this is spec-completeness, not a behavior change), and the P0
  ⚠️ was closed: `PipelineLift.lift()` of a schema-less draft is null-tolerant (test:
  `liftsASchemaLessDraftWithoutThrowing` — the single-schema branch handles `s.single()==null`
  and the sink falls back to the pipeline name as its store).
- **P2 recon corrections (bind future work):** (a) **`raw.fields[].selector` semantics differ by
  frontend** — delimited/fixedwidth address the parsed column by 0-based POSITION (stringified
  index), json/text_regex by the VERBATIM key/group name; the Schema pane derives selectors
  accordingly from `ParsingPreview.columns`. (b) **`mapping.rules[]` is required, non-empty, no
  identity default** — `DataTransformer.materialize` NPEs without `mapping` and emits zero data
  columns on empty rules; the guided save always writes one straight-through rule per included
  field (`SchemaExtractor`'s shape; omitted `transformType` = DIRECT). (c) **Only
  DOUBLE/DATE/TIMESTAMP are actually cast** by `TransformCompiler.direct()` — everything else
  (including INTEGER!) is a raw passthrough; the pane offers exactly VARCHAR/DOUBLE/DATE/TIMESTAMP
  (honesty guard). (d) **Activation is just `active: true`** — `CollectorService` re-reads the
  flag every poll cycle (`!cfg.active()` gate in the run-set builder); there is no `/activate`
  route and none was added. (e) Duplicate `raw.fields[].name` is NOT rejected at load — it fails
  at first ingest as a DuckDB error; the pane blocks duplicates client-side.
- **Phase 2 (explicitly deferred, tracked in `BACKLOG.md` when v1 ships):** end-to-end bounded
  sample-run endpoint for a new ingestion pipeline; Reference cache/upsert/SCD versioning + refresh
  scheduling; row/event-level dedup; Stream grouping (GLOSSARY "one sub-system = one Stream" over many
  pipelines — today 1:1); graph compile-back (T5).

## 6. UI work

- **U1 — Onboarding shell**: lens-gated (`canAuthorWorkbench`) route, e.g. `/catalog/onboard/:name`
  (kind chosen at create); stage rail (settings-style `NgComponentOutlet` master-detail — one stage pane
  instantiated at a time), shared `<inspecto-breadcrumb>` back to Catalog, dirty-guard on close.
- **U2 — Extract embeddable panes** from `ParserConfigDialog` and `ConnectionFormDialog` (content
  components shared by dialog wrappers — graph editor keeps its dialogs; zero behavior change there).
  **P1 deviation (honesty guard):** `ParserConfigDialog` was NOT extracted — its 9-format grammar
  catalog is mostly mock-only (its preview endpoint has no backend; only `dsv` maps to a real
  frontend), so embedding it would ship dishonest options. The Parsing pane instead authors the
  4 engine-real `parsing:` frontends via `<inspecto-schema-form>` specs + the new real preview
  (B5); the graph editor keeps its dialog untouched (zero regression surface). For the connection
  dialog, "extract" became **relocate**: `connection-form.dialog` + `connection-types` moved
  verbatim to `app/inspecto/connections/` (cross-feature reuse without a content/wrapper split —
  onboarding opens the same dialog).
- **U3 — Connection picker** (dropdown over `/connections` + test + create-in-place): fixes the existing
  free-text `use` gap; adoptable later by the graph editor's SOURCE node config.
- **U4 — Stage forms** over Stage‑1 blocks via `<inspecto-schema-form>` `AttributeSpec[]` descriptors
  (follow the jobs pattern: `JOB_ATTRIBUTES`-style spec modules per block).
- **U5 — Catalog tabs**: Streams as default tab; **Onboard Stream / Onboard Reference** CTAs (lens-gated);
  lifecycle + readiness columns; row actions *Resume onboarding* (Draft/Ready) / *Open onboarding* (Live).
- **U6 — Enrichment stage (minimal)**: companion `EnrichmentConfig` — references picker (first-class refs
  by name after B1, `path` fallback) + SQL editor; optional stage.
- **U7 — Sample preview panel** shared across stages (grid/tree reuse from the parser dialog).

## 7. Phases & verification (each phase ends GAUNTLET-green; baselines 1467/0 reactor · 1305/0 UI)

- **P0 — Backend seams**: B1 + B2 + B3 verifications, with real-HTTP tests. *Verify:* new
  `ControlApiTest` cases; catalog derives a standalone Reference from a `produces: reference` config.
  **✅ SHIPPED 2026-07-16** — reactor `mvn -o clean test` 1491/0/0/3 (baseline 1467/0; +24 tests:
  `PipelineConfigProducesTest`, `PipelineConfigDraftTest`, `ControlApiConfigDeleteTest`, plus new
  cases in `MetadataGraphServiceTest` / `EnrichmentConfigTest` / `EnrichmentEngineTest` (real DuckDB
  by-name join) / `ControlApiStreamsTest` (draft visibility + produces filtering)). graphify updated.
- **P1 — Shell + Collection + Parsing**: U1–U3 + parsing pane; draft lifecycle end-to-end. *Verify:*
  create → close → reopen (draft resumes from server state); sample threads raw→parsed; connection
  create-in-place + test. **✅ SHIPPED 2026-07-16**

  **P1 as-built (2026-07-16).** Backend: B4 read route + B5 parsing preview (§5) +
  `ControlApiOnboardingLifecycleTest` (write→register→streams-shows-Draft→read→overwrite→discard,
  every read gate, preview 200/400/422) + `ComponentPreviewParsingTest` (all 4 frontends over real
  DuckDB, plugin/binary rejections); openapi-v1 documents both. UI (all under
  `modules/admin/catalog/onboarding/` unless noted):
  - **Create** = ask-the-minimum dialog (kind toggle + name + description; dirs prefilled from the
    space convention under a collapsed Advanced — never blocks the first write). There is NO
    "Create" stage in the rail — the rail mirrors the data path only (a P1 UX improvement over
    §4.1's 6-stage list; identity lives in the shell header). Create = `write` → `registerPipeline`
    → navigate to the shell.
  - **Shell** `/catalog/onboard/:name(/:stage)` — ONE matcher route (R5 idiom, shell survives
    stage nav), stage RAIL with per-stage computed readiness chips (Not configured / Configured /
    Validated — never stored), lifecycle status-badge (Draft→Ready→Live; `LIVE` token added to the
    shared badge), Discard draft (confirmDestructive → DELETE), dirty-guard on stage switch AND
    route deactivate (panes register a dirty-check with the session service), read-only-lens and
    writes-disabled banners, missing-draft empty state. **Resume lands on the first incomplete
    stage** when no `:stage` is in the URL.
  - **Sample-as-thread panel** (right, `inspectoSplit`-resizable): capture once (file ≤256KB or
    paste), raw preview + per-stage outcome summary (parsed cols/rows/rejected or the parse error);
    a new sample resets the thread. Capture is lens-free (session-only, no server write).
  - **Collection pane**: `COLLECTOR_ATTRIBUTES` schema-form over the real `collector:` keys
    (connector/connection/include/discovery/duplicate/post_action + advanced), `connection`
    autocomplete via new shared `connectionOptionLoader`, Test connection (saved-profile
    `POST /connections/{id}/test`), **New connection** = the relocated shared
    `ConnectionFormDialog` (U2/U3). Saves deep-merge the block; cleared fields delete their key;
    hand-authored keys survive (`onboarding-config-utils`, `__`-separated flat keys — a literal
    `.` collides with Angular form-path semantics).
  - **Parsing pane**: frontend toggle (delimited/fixedwidth/json/text_regex; `plugin` shown as a
    TOON-managed banner, never editable), per-frontend `PARSING_ATTRIBUTES`, bespoke fixed-width
    fields[] editor, **Test parse** → B5 preview → `inspecto-query-panel` grid + rejected count;
    frontend switch clears the other frontends' sub-blocks on save.
  - **Catalog**: Streams/References tabs now lead (Streams = default, D2), contextual lens-gated
    **Onboard Stream/Reference** header CTA, streams Lifecycle badge column (attrs.active), row
    action "Resume/Open onboarding" on both tabs (references: only rows with a backing pipeline).
  - **Mocks** (offline dev): new `onboarding.handler` (config write/read/delete + `POST /runs`
    register + JS mini-parsers mirroring the preview contract) + demo streams/references lists
    merge the store-backed drafts. Note: the UI dev proxy expects the real backend when mock flags
    are off; `.claude/launch.json`'s `inspector-backend` gained
    `-Dassist.write.root=…/spaces/demo/config` so the onboarding writes work in dev.
  - **Specs**: 7 new spec files (~29 cases incl. axe on every new component); catalog spec updated
    for the new default tab.
  - **P1 verification (2026-07-16):** GAUNTLET green — reactor `mvn -o clean test`
    **1505/0/0/3** (6/6 modules; +14 over the P0 baseline) · `lint:tokens` PASS ·
    `test:ci` **1338/0** (+5 skipped; baseline ~1305) · production `build` PASS. **Live preview
    walk vs the real dev backend (demo space):** Onboard Stream CTA → create `p1_walk_feed` →
    draft written + registered + shell landed on Collection → Save collection → rail ● Configured
    → paste sample → Test parse (real DuckDB round trip: delimited · 2 columns · 2 rows, grid +
    thread panel) → Save parsing → rail ✓ Validated → Catalog Streams showed the row with a
    **Draft** badge next to Live pipelines → reopen by URL resumed from server state → Discard
    draft → file verified deleted, demo space byte-clean. Two UX fixes came OUT of the walk:
    (1) the land-on-first-incomplete effect now runs once per opened draft (a stage save used to
    yank the user to the next stage mid-edit); (2) `firstOpenStage` picks the first empty stage
    in data-path order (placeholder stages included) — resuming a collection+parsing-done draft
    lands on Schema & Mapping's honest placeholder, not back on Collection.
  - **Deferred within P1 scope:** references list carries no `active` attr yet (graph-derived
    nodes; lifecycle column is streams-only until P3 adds it), per-stage `/validate` findings
    (`blocked` state) → P2, "View as graph" link (PipelineLift null-tolerance check still pending)
    → P2, **discard leaves a catalog ghost row until the next poll rebuild** (≤60s dev; the
    registry prunes the missing file) — consider `deleteConfig` unregistering the live entry in
    P2.
- **P2 — Schema & Mapping + Dataset & Go-live**: U4 + gate. *Verify (live):* onboard a demo CSV Stream in
  `spaces/demo` end-to-end from the UI, activate, drop a file, confirm rows land + Catalog shows **Live**.
  **✅ SHIPPED 2026-07-16**

  **P2 as-built (2026-07-16).** Backend: B6 schema preview + `output.compression` spec +
  PipelineLift null-tolerance test (§5). UI (under `modules/admin/catalog/onboarding/`):
  - **Schema & Mapping pane** (`schema-mapping-pane`): gated on a parsed sample — fields are
    DERIVED from `ParsingPreview.columns` (frontend-aware selectors, sanitized-identifier names,
    include checkboxes), never hand-typed; honest 4-type select (VARCHAR/DOUBLE/DATE/TIMESTAMP —
    exactly what `TransformCompiler.direct()` casts); partition-key select over included fields;
    **Validate types** TRY_CASTs the SAME parsed rows (B6) with rejected rows in a grid; **Save**
    writes the `schema` config under the convention name `<pipeline>_schema` + links
    `processing.schema_file` in one flow. Resume reads the schema file back (pristine); a
    `schema_file` outside the convention shows a managed-elsewhere banner (never modified). The
    sample thread gained an "After schema" hop (ok/rejected counts); a re-parse or new sample
    invalidates it.
  - **Dataset & Go-live pane** (`publish-pane`): `output:` block via `PUBLISH_ATTRIBUTES`
    (format PARQUET/CSV + compression); **Go live** appears only at `lifecycle()==='Ready'`
    (otherwise the blocked state NAMES the missing stages, or points at the unsaved output
    block), confirms, then flips `active: true` through the same `saveBlock` path — no dedicated
    route, matching the engine (poll-cycle re-read). Once live: an inbox activity glance
    (`GET /runs/{name}/pending`, manual Refresh, no timers) + a link to the full Runs page.
  - **Mocks**: schema-type config write/read/delete + `POST /config/preview/schema` (JS
    TRY_CAST-alike: DOUBLE/DATE/TIMESTAMP reject, VARCHAR never). **Specs**: 2 new files, 15
    cases incl. axe.
  - **Walk-found fix (real bug):** `read_csv auto_detect` typed a date column as a DuckDB DATE →
    `java.time.LocalDate` in the preview rows → Jackson 500. Fixed with `all_varchar=true` in
    `delimitedSelect` — which is also the honest semantics (production raw ingest is 100%
    VARCHAR; typing is exactly what the Schema stage makes explicit). Regression test
    `delimitedKeepsEveryColumnVarcharLikeProductionIngest`. ⚠️ Related edge left open: the
    `json format:array|auto` path (`read_json`) could still infer a timestamp column and hit the
    same serialization wall — NDJSON (the default) is immune (`json_extract_string`).
  - **Walk-found fix 2 (real bug, restart durability):** a guided draft was written as
    `<name>.toon`, but the service bootstrap scan only indexes `*_pipeline.toon`
    (`MultiCollectorProcessor.resolveConfigs`) — so a registered draft SILENTLY dropped out of
    the registry on the next service restart (caught when the walk restarted the dev backend
    mid-lifecycle; P1's walk never restarted). Fix: `/config/write` now names pipeline files
    `<name>_pipeline.toon` (no double-suffix), and read/delete resolve the suffixed convention
    first with a bare-name fallback (back-compat; also proves out for hand-authored files like
    `livepipe.toon`). The write response `name` stays the identity; `path` carries the real
    file. Tests updated in both lifecycle + delete classes; the UI mock mirrors the path shape.
  - **Walk-found fix 3 (real latent engine bug):** a DATE/TIMESTAMP column with NO declared
    `date_formats`/`timestamp_formats` made `SqlBuilder.appendCoalesce` emit a zero-arg
    `COALESCE()::DATE` — invalid SQL that failed the combined read+transform statement, so the
    batch was classified **QUARANTINED_UNREADABLE** and no rows landed (the onboarding Schema
    stage types a column DATE but never captures a parse format). Fix: empty formats now fall
    back to `TRY_CAST(col AS <type>)` — DuckDB's native ISO-8601 parse (the honest default; an
    ISO date/timestamp casts, a bad value → NULL, no crash). This is a latent bug for ANY
    hand-authored config too, not just onboarding. Regression test
    `DataTransformerTransformTypesTest.directDateColumnWithoutFormatsCastsIsoNatively`.
    ⚠️ Non-ISO date formats still need `date_formats` in the TOON — a documented onboarding
    limitation (the guided Schema stage captures type but not custom format; future enhancement).
  - **P2 verification (2026-07-16):** reactor `mvn -o clean test` **1510/0/0/3** (6/6 modules;
    +5 over the P1 1505 baseline) · `test:ci` **1353/0** (+5 skipped) · `lint:tokens` PASS ·
    production build PASS. One UI a11y bug caught by the new spec (mat-checkbox needs its own
    `aria-label` input, not `attr.aria-label`) — fixed. (A later full `test:ci` re-run flaked on
    `studio/widgets/widget.kind.spec.ts` — a pre-existing cross-file ComponentKind
    registration-order issue, unrelated to onboarding; passes 5/5 in isolation.) **Live walk vs the real dev backend (demo space):** Onboard
    Stream → `p2_walk_feed` created → Save collection (● Configured) → paste 3-row sample with a
    deliberate bad `QUANTITY` → Test parse (7 columns · 3 rows) → Save parsing (✓ Validated) →
    Schema stage auto-derived 7 fields (index selectors) → typed ORDER_DATE=DATE,
    QUANTITY/UNIT_PRICE=DOUBLE, partition key ORDER_DATE → **Validate types: 2 ok · 1 rejected**
    (the bad row, real DuckDB TRY_CAST) → Save schema (on-disk TOONs byte-equivalent to the
    hand-authored orders convention) → Publish: Save output (PARQUET) unlocked Go live → confirm
    → toast + Live banner → dropped a 3-row CSV into the inbox → batch committed on the next
    poll cycle (see below). Activity glance right after activation says "no pipeline named" until
    the service's next poll re-reads `active` — same ≤60s eventual-consistency as the discard
    ghost row; the Refresh button covers it.
  - **Deferred within P2 scope:** per-stage `/validate` findings (`blocked` chip state) and the
    "View as graph" link (lift is now proven null-tolerant; just UI wiring) → P3/P4; discard
    unregistering the live registry entry (ghost row ≤60s) still open; `read_json array|auto`
    timestamp serialization edge (above).
- **P3 — Reference flow + Enrichment stage**: U5 rows/CTAs + U6. *Verify (live):* onboard `region_dim`
  as a Reference via the guided flow; bind it by name in an enrichment; enriched output verified.
- **P4 — Polish + docs**: readiness columns everywhere, empty-state CTAs, optional templates entry
  (space-template-gallery precedent); USER_GUIDE section; okf concept files (frontend feature + backend
  seam); `FEATURE_INVENTORY.md` row; GLOSSARY updates (§8 below); `graphify update .`.

## 8. Glossary touchpoints (apply at implementation, per §13 rename-map discipline)

- **Onboard / Onboarding** (§2): the guided end-to-end creation of a data origin (Stream or Reference).
  Align with the open `canOnboardConnections` RBAC question (`BACKLOG.md`) — one "onboard" verb.
- **Reference Dataset** (§6-B): note the production seam (a pipeline may *produce* one; enrichments bind
  by name).
- Copy rules: never "wizard" in UI text; watch the three-way collision — Studio `dataset` component kind
  vs `REFERENCE_DATASET` vs pipeline output Dataset.

## 9. Risks & mitigations

- **Dialog-extraction regressions** in the graph editor → shared content components, existing tests, and
  a preview walk of the graph editor's parser/connection dialogs in P1.
- **`dirs.*` required trio** — derive defaults from Space conventions (verify `SpaceRoot` layout at P1);
  fall back to asking in the Create stage. Never block the first write.
- **Draft visibility** — inactive drafts appear in Catalog (by design, per `PipelineConfig` semantics);
  the lifecycle column + copy must make Draft/Ready/Live unmistakable to operators.
- **Editing a Live stream** through the same surface: allowed (same overwrite path, hot-reload), but the
  gate requires re-validate and the UI warns that the pipeline is live.
- **Honesty guard:** the UI offers only engine-real options (file-level dedup, full-replace reference
  load). Aspirational GLOSSARY semantics stay visibly labeled as roadmap, never as silent no-ops.
