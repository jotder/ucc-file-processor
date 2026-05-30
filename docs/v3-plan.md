# v3.x Implementation Plan — sequenced milestones

The strategic "what/why" is in [v3-architecture.md](v3-architecture.md) (assessment +
redesign), [v3-agent-mvp.md](v3-agent-mvp.md) (the assist-agent MVP), and the independent
deep-dive review in [design_analysis.md](design_analysis.md) (concrete M1 / SQL-sandbox /
event-reactor patterns, folded in below). This is the "how / in-what-order" — a finalized,
sequenced task list. Each milestone is independently releasable as a minor version on the
`3.x` branch, mirroring the 2.x cadence (one minor release per milestone: feature → release
commit → annotated tag → next `-SNAPSHOT` → fat-JAR from tag → GH release).

Branch is at **`3.1.0-SNAPSHOT`** (M0 shipped as **v3.0.0**; the foundation was hardened
post-release — concurrency/audit fixes, cruft removal, CI reactor coverage).

## What changed in this revision (the "data keystone")

The hero (`kpi-to-sql`) and even `explain-entity` need to know **what event tables a source
emits** and **what each column means in domain terms**. Today neither exists: the schema model
is `{name, selector, type}` only (no descriptions), and nothing enumerates the emitted event
tables (plugin sources fan out into per-event-type Hive-partitioned trees — `event_type=CALL/
year=…/…` — and Stage-2 reads them via an `input` view). The previous plan buried this as
"P5 `*_meta.toon`" at M4 and consumed it only at M5 — the foundation landed *after* the skills
that stand on it.

**This revision promotes a Data Catalog + Semantic Schema layer to a new foundational milestone
(M2), immediately after the Smart Config keystone (M1) and before any assist skill.** It is the
*data keystone*, twin to M1's *config keystone*: zero-AI-dependency, UI-ready, and consumed by
every skill. The assist skills (explain-entity → … → kpi-to-sql) renumber down by one.

## Decisions locked (carried from the MVP + architecture review)

| # | Decision | Choice |
|---|---|---|
| V-1 | Agent topology | Embedded in-JVM via an `AssistAgent` SPI loaded by `SourceService` |
| V-2 | Model provider | Per-env pluggable; **air-gapped = local-only, enforced by packaging** |
| V-3 | First skill slice | `explain-entity` (read-only) |
| V-4 | Semantic layer | **Three layers:** column **`description`** in the schema `.toon` + a **`*_meta.toon`** (KPI catalog + domain notes) + an assembled, queryable **Data Catalog** (see A-3/A-4) |
| V-5 | Default 7B driver | Qwen2.5-7B-Instruct; 14B for `kpi-to-sql` in prod |
| V-6 | Hosted choice | Pluggable Gemini / Claude / ChatGPT (absent in air-gapped build) |
| V-7 | Assist auth | Separate scoped token tier; no open default |
| V-8 | Hardware profiles | dev-laptop (4GB GPU) · CPU-only (test/CI) · prod (16GB+ GPU) |
| V-9 | Config skills | Draft-only for MVP; CRUD endpoints a fast-follow |
| V-10 | Alerts | Subscribe to existing FAILED events + enrich `BatchEvent`; async hand-off |
| **A-1** | **Config keystone** | **Smart Config (`ConfigSpec`) lands before the config-authoring skills (M1)** |
| **A-2** | Compatibility | Additive only; `.toon` stays canonical; suite green each step; lean core zero-new-dep |
| **A-3** | **Data keystone** | **Data Catalog + Semantic Schema is a foundational milestone (new M2), after M1 and before any assist skill** |
| **A-4** | Catalog source | **Config-derived** (assembled from schema + enrichment configs) + a **light DuckDB `DESCRIBE` verify** against a `read_parquet` view; not a filesystem scan |

## Milestones & tasks

Build order is top-to-bottom. Versions are nominal targets. Each milestone ends green +
tagged + GH-released.

### M0 — Foundation: build restructure + security + doc reconciliation → v3.0.0 ✅ *shipped*
*The prerequisite milestone — nothing agent-related ships without it.*
- **T0.1** Parent POM + `file-processor` core + optional `file-processor-agent`; shade keeps the
  lean fat-JAR zero-new-dep. ✅
- **T0.2** `AssistAgent` SPI (`com.gamma.assist.spi`) + `SourceService` injection point registered
  **before `start()`** (ServiceLoader discovery). ✅
- **T0.3** `ControlApi` scoped fail-closed tokens (`control` / `assist.read` / `assist.write`),
  constant-time compare, no open-by-default. ✅
- **T0.4** Reconcile `architecture.md` + `design-notes.md` with the two-stage reality. ✅
- **Post-release hardening** (3.1.0-SNAPSHOT): serialize ingest (re-entrancy guard), always-audit
  on commit failure, `registerAgent` race, parent `<pluginManagement>`, CI builds the full reactor.

### M1 — Smart Config layer (the config keystone) → v3.1.0
*Solves G1–G5/G10; everything AI/UI leans on it. Concrete class shapes in
[design_analysis.md §4.A](design_analysis.md).*
- **T1.1** `com.gamma.config.spec`: `ConfigSpec`, `FieldSpec` (type/required/default/enum/
  constraints/uiHint/visibleWhen/**description**), `CrossFieldRule`. Author specs for pipeline /
  enrichment / job / schema / **`*_meta`**, encoding today's implicit rules (exactly-one-of,
  engine×skip_tail, threads×duckdb_threads, partitions-tabular).
- **T1.2** `com.gamma.config.io`: pluggable `ResourceLoader` (filesystem default; a
  `MapResourceLoader` for tests/REST bodies), `decode→Map` (no side effects), `parse(spec,raw)→
  Config` (pure), `validate(spec,raw|Config)→List<Finding{severity,fieldPath,message}>` (pure).
  Split disk side effects out of `PipelineConfig.load` into an explicit `prepare(Config)`; add a
  public `fromMap`/builder. Same parse/validate split for `EnrichmentConfig`/`JobConfig`.
- **T1.3** Schema-aware `.toon` serializer (always-quote colons, never `#`, correct inline-vs-
  tabular) + JSON wire form. `.toon` stays canonical + backward-compatible.
- **T1.4** `ConfigRegistry` keyed by stable id (watch/reload) → O(1) memory lookup; replace the
  O(n) re-parse scans in `SourceService.pathFor/configFor/activeRegistry`. Fix the
  discovery-suffix vs in-file-identity mismatch.
- **T1.5** API: `GET /config/spec/{type}` (UI/AI read the spec) + `/validate` **body** form
  (validate a draft, no file needed) returning structured findings.
- *Exit:* existing `load(path)` delegates to the new pipeline; all 2.x config tests green; the
  spec round-trips and validates the shipped sample configs.

### M2 — Data Catalog + Semantic Schema (the data keystone) → v3.2.0
*NEW. Makes the platform's emitted data self-describing: **what event tables exist** + **what
their columns mean**. Zero-AI, UI-ready, consumed by every assist skill; the substrate
`kpi-to-sql` and `explain-entity` stand on. Addresses architecture gap G8.*
- **T2.1 — Schema description enrichment (in the `.toon`).** Extend the schema field model and
  TOON tabular header from `fields[N]{name,selector,type}` → `fields[N]{name,selector,type,
  description}` (and optional `unit` / `classification`). **Backward-compatible:** JToon is
  header-driven, so existing 3-column files still parse (description ⇒ empty). Carry the field
  through `PipelineConfig`/`SchemaSelector` and the M1 schema `ConfigSpec`. **`SchemaExtractor`
  must merge/preserve** authored descriptions on regeneration (never clobber) — seed new columns
  from heuristics, keep existing prose.
- **T2.2 — `*_meta.toon` semantic descriptor** (a `ConfigSpec`-described type from M1; co-located
  with enrichment configs, loaded by the same suffix-scan; `ConfigRegistry` entry). Holds the
  **cross-table** layer the schema file can't: a **KPI catalog** (named KPI → NL definition,
  grain, inputs, join keys), **domain notes** (units, currency, time-zone, "revenue excludes
  tax"), and table-level descriptions. Draft validation via the M1 loader.
- **T2.3 — `CatalogService`** (core, zero new deps): assemble the **catalog of available event
  tables** across all sources from the `ConfigRegistry` — for each source/schema/segment and each
  Stage-2 enrichment output: `{ tableId, source, eventType, stage(1|2), grain/partitionKeys[],
  columns[{name,type,description,classification?}], producedBy, outputGlob }`, joined with the
  `*_meta.toon` KPI/domain layer. **Config-derived + light verify (A-4):** optionally run a DuckDB
  `DESCRIBE` over a `read_parquet` view to confirm column names/types against produced Parquet
  (skipped when no data yet). Rebuilt on registry reload.
- **T2.4 — Catalog API:** `GET /catalog` (tables: id, source, eventType, grain, stage),
  `GET /catalog/tables/{id}` (columns + descriptions + grain + lineage), `GET /catalog/kpis`
  (KPI catalog + domain notes). Scoped (`assist.read` / `CONTROL`). Feeds both the UI and the agent.
- *Exit:* every emitted event table is enumerable with domain-described columns; the KPI catalog +
  domain notes load, validate, and round-trip; the catalog API serves a sample source end-to-end;
  the shipped sample schemas gain real descriptions.

### M3 — Assist platform + first slice `explain-entity` (read-only) → v3.3.0
- **T3.1** `file-processor-agent`: LangChain4j + Ollama client; `AssistAgent` SPI impl; model
  router with the **provider seam** (Ollama / hosted) + **grammar-constrained output**; air-gapped
  packaging omits hosted SDKs.
- **T3.2** Skill registry (id, schemas, tier, oracle, tools) + `POST /assist/{intent}` route
  (scoped `assist.read`), assist manifest concept.
- **T3.3** `explain-entity` skill — RAG over `docs/*.md` + Control API reads **+ the M2 catalog**
  (so "what events does this source emit / what does this column mean" is answerable) →
  `{answer, citations, links}`. 7B tier (never 2B for real Q&A).
- **T3.4** Golden-test harness, **runnable CPU-only** (CI). Profile config bundles (V-8).
- *Exit:* zero write surface; agent answers entity + catalog questions end-to-end on local Ollama.

### M4 — `nl-to-schedule` (draft-only) → v3.4.0
- **T4.1** Skill: NL → `{cron,on_pipeline,humanReadable,nextRuns[]}` → JobConfig **draft**
  (validated by M1's parse/validate + cron oracle). Gemma 2B for plain; route compositional/
  relative/timezone to 7B.
- **T4.2** Golden tests incl. compositional cases (the oracle catches invalid cron, not
  semantically-wrong-valid cron → assert `humanReadable`/`nextRuns`).
- *Exit:* the cron-builder-widget replacement demo works; draft `.toon` returned for the user to save.

### M5 — `suggest-config` (draft-only) → v3.5.0
- **T5.1** `suggest-config` skill — sample + partial config → field suggestions w/ rationale;
  validated by the M1 loader + a **hard-fail config safety validator** (path jail, numeric bounds,
  output-DB allow-list). 7B. Draft-only. (The `*_meta.toon` it can also draft now lives in M2.)
- *Exit:* config-form replacement demo; safety validator blocks harmful-but-parseable configs.

### M6 — `kpi-to-sql` (the hero) + SQL sandbox → v3.6.0
- **T6.1 — `SqlOracle` (locked-down DuckDB).** Extract from `EnrichmentEngine`'s view-registration.
  Native sandbox: `enable_external_access=false`, `lock_configuration=true`, disable custom/auto
  extension repos. **Plus a lexical/structural allow-list** ([design_analysis.md §4.B](design_analysis.md)):
  reject `;` multi-statements, block `read_*`/`write_*`/`copy`/`getenv`/`pragma`/`install`/`load`/
  `query`/`eval`, allow only a single `WITH`/`SELECT` (no DDL/DML) — because **`EXPLAIN` can still
  evaluate smuggled functions during planning**. Memory/threads/timeout caps.
- **T6.2 — `kpi-to-sql` skill** — `{kpiDescription,targetGrain,catalogRefs[],domainNotes?}` (the
  `catalogRefs` resolve against the **M2 Data Catalog + KPI catalog**) → `{sql,logicExplanation,
  columnsProduced,chosenJoinKeys,kpiInterpretation,validated,sampleRows?,enrichmentConfigSnippet}`.
  EXPLAIN/LIMIT-0 validate→repair (cap 2–3). 14B prod / 7B dev / hosted-recommended connected.
  **Confirm-first; surface interpretation + chosen join keys + sample rows** (the oracle proves it
  *runs*, not that it computes the KPI).
- *Exit:* KPI-in-domain-terms → validated Stage-2 SQL grounded in the catalog, draft on local /
  high-quality on hosted; SQL sandbox rejects disallowed statements (tested).

### M7 — `diagnose-and-alert` (event-driven) → v3.7.0
- **T7.1** Enrich `BatchEvent` with error detail (reason/exception/quarantine context/offending
  file/rows). Add a non-filtering failure subscriber that **immediately hands off to its own
  queue-backed virtual-thread executor** so slow AI diagnosis never throttles the ingest thread
  ([design_analysis.md §4.C](design_analysis.md)).
- **T7.2** `diagnose-and-alert` skill — event → `{severity,rootCause,alertRuleDraft}` (2B classify
  + 7B/hosted root-cause) and NL → alert rule. Alert-rule shape validated.
- *Exit:* proactive failure diagnosis + drafted alerts; ingest thread never blocked.

### M8 (optional) — `report-sql` / `report-narrative` → v3.8.0
NL → report SQL over the audit/status stores (sandboxed-DuckDB validated, 7B) + report-JSON →
prose narrative (2B, strictly extractive).

### Fast-follow — Config write endpoints (CRUD-from-body)
Promote `nl-to-schedule`/`suggest-config` (and `*_meta.toon`/schema-description edits) from
draft-only (V-9) to one-click-apply: `POST/PUT /configs` validate-and-persist via `ResourceLoader`
+ serializer, confirm-first, audited (suggested-vs-applied).

### Deferred (v3.x later / v3+)
- **BFF + Web UI** module (renders `ConfigSpec` forms + a catalog browser, calls assist intents) —
  the architecture is UI-ready (B3); the UI itself is a parallel track.
- LangGraph4j multi-step graphs (provision → watch → roll back).
- `DbStatusStore` connection pool; `MetricRegistry` non-singleton — for the distributed tier.
- Object storage, distributed/multi-node execution.

## Cross-cutting guardrails (every milestone)
- **Suite green CPU-only** before release (CI has no GPU, V-8); CI builds the **full reactor**.
- **Lean core gains zero new deps** (CI-enforced); all AI/hosted deps in `-agent`. (The catalog +
  semantic layer in M2 are core, zero-dep.)
- **Confirm-first, no autonomous apply**; agent holds no write token.
- **Air-gapped = local-only by packaging**; sample rows never sent to a hosted model.
- The non-negotiable security guardrails in [v3-agent-mvp.md](v3-agent-mvp.md#non-negotiable-security-guardrails).

---

**Net sequence:** M0 foundation ✅ → **M1 Smart Config (config keystone)** → **M2 Data Catalog +
Semantic Schema (data keystone)** → M3 assist platform + `explain-entity` → M4 `nl-to-schedule`
→ M5 `suggest-config` → **M6 `kpi-to-sql` + SQL sandbox** → M7 `diagnose-and-alert` → (M8 reports)
→ CRUD fast-follow → UI/distributed deferred. One minor release per milestone on `3.x`, additive,
suite-green, lean core preserved.
