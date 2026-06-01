# v3.x Implementation Plan — sequenced milestones

The strategic "what/why" is in [v3-architecture.md](v3-architecture.md) (assessment +
redesign), [v3-agent-mvp.md](v3-agent-mvp.md) (the assist-agent MVP), and the independent
deep-dive review in [design_analysis.md](design_analysis.md) (concrete M1 / SQL-sandbox /
event-reactor patterns, folded in below). This is the "how / in-what-order" — a finalized,
sequenced task list. Each milestone is independently releasable as a minor version on the
`3.x` branch, mirroring the 2.x cadence (one minor release per milestone: feature → release
commit → annotated tag → next `-SNAPSHOT` → fat-JAR from tag → GH release).

Branch is at **`3.6.0-SNAPSHOT`** (M0 shipped as **v3.0.0**; M1 Metadata Graph as **v3.1.0**;
M2 Smart Config as **v3.2.0**; M3 Assist platform + `explain-entity` as **v3.3.0**; M4
`nl-to-schedule` as **v3.4.0**; M5 `suggest-config` + config safety validator as **v3.5.0**). The
foundation was hardened post-v3.0.0 — concurrency/audit fixes, cruft removal, CI reactor coverage.

> **Status update (this revision):** **both keystones have shipped.** The **Metadata Graph** (data
> keystone) shipped as **v3.1.0** — the `com.gamma.catalog` package, `SchemaExtractor` merge/preserve,
> the `*_meta.toon` loader, and the `/catalog*` API. The **Smart Config** layer (config keystone) has
> now shipped as **v3.2.0** — the declarative `com.gamma.config.spec` model (`ConfigSpec`/`FieldSpec`/
> `CrossFieldRule`/`Finding` + the authored `ConfigSpecs` for pipeline/enrichment/job/schema/meta), the
> pure `com.gamma.config.io` decode→validate pipeline behind a pluggable `ResourceLoader`, the
> canonical `ConfigCodec`, the parse/`prepare` split in `PipelineConfig` (+ `fromMap` on
> `EnrichmentConfig`/`JobConfig`), the O(1) `ConfigRegistry` (replacing the O(n) re-parse scans and
> backing the catalog's `ConfigSource` seam), and the `GET /config/spec/{type}` + draft `POST /validate`
> API. **M3 has now shipped as v3.3.0** — the assist platform goes live: LangChain4j + Ollama behind a
> `ModelProvider` seam (abstain-safe, local-first), a `SkillRegistry`, the read-only `explain-entity`
> skill (grounded on the M1 catalog + Control API reads + `docs/*.md`, with derived citations), the
> `POST /assist/{intent}` route (scope `assist.read`), and the AI `DescriptionProvider` that auto-fills
> blank catalog descriptions (`Provenance.AI`, never overwriting `MANUAL`). All AI deps live in the
> optional `file-processor-agent` module; the core fat-JAR stays zero-AI. Golden tests run CPU-only
> (a deterministic fake model — no Ollama in CI). **M4 has now shipped as v3.4.0** — the first
> write-adjacent skill, `nl-to-schedule` (draft-only): a natural-language request → a validated
> JobConfig draft `{cron, on_pipeline, jobType, humanReadable, nextRuns[], draftToon}`, introducing
> the **generate→validate→repair** oracle pattern (reusing the core cron engine + job spec) and a
> deterministic `CronDescriber`. It stays draft-only (V-9) — `applyVia` is null, no write endpoint;
> the only core touch is one additive `AssistResult.data` structured-payload field (zero new deps).
> **M5 has now shipped as v3.5.0** — `suggest-config` (draft-only): a source sample + partial config →
> validated field suggestions with rationale/confidence, gated by the new **hard-fail config safety
> validator** (`com.gamma.config.safety` — path jail / numeric bounds / output allow-list, security
> guardrail R6; core, zero-dep), additively exposed on `POST /validate` behind an opt-in flag. It
> reuses the M4 `RepairLoop` + `AssistResult.data`; stays draft-only.

## What changed in this revision (the "data keystone")

The hero (`kpi-to-sql`) and even `explain-entity` need to know **what event tables a source
emits** and **what each column means in domain terms**. Today neither exists: the schema model
is `{name, selector, type}` only (no descriptions), and nothing enumerates the emitted event
tables (plugin sources fan out into per-event-type Hive-partitioned trees — `event_type=CALL/
year=…/…` — and Stage-2 reads them via an `input` view). The previous plan buried this as
"P5 `*_meta.toon`" at M4 and consumed it only at M5 — the foundation landed *after* the skills
that stand on it.

**This revision builds a Metadata Graph (the *data keystone*) as a foundational milestone before any
assist skill, and — because it shipped first — numbers it M1 (v3.1.0), with the Smart Config
*config keystone* following as M2 (v3.2.0).** Both are zero-AI-dependency, UI-ready, and consumed by
every skill. The assist skills (explain-entity → … → kpi-to-sql) sit at M3+.

## Decisions locked (carried from the MVP + architecture review)

| # | Decision | Choice |
|---|---|---|
| V-1 | Agent topology | Embedded in-JVM via an `AssistAgent` SPI loaded by `SourceService` |
| V-2 | Model provider | Per-env pluggable; **air-gapped = local-only, enforced by packaging** |
| V-3 | First skill slice | `explain-entity` (read-only) |
| V-4 | Semantic layer | **Shipped as the M1 Metadata Graph:** `description`/`unit`/`classification` columns in the schema `.toon` + a **`*_meta.toon`** (KPI catalog + domain notes), assembled by `MetadataGraphService` into a typed, traversable graph with a lazy operational overlay (`/catalog*`); descriptions ranked manual > AI > deduced via a `DescriptionProvider` SPI (**AI provider shipped at M3 / v3.3.0**) |
| V-5 | Default 7B driver | Qwen2.5-7B-Instruct; 14B for `kpi-to-sql` in prod |
| V-6 | Hosted choice | Pluggable Gemini / Claude / ChatGPT (absent in air-gapped build) |
| V-7 | Assist auth | Separate scoped token tier; no open default |
| V-8 | Hardware profiles | dev-laptop (4GB GPU) · CPU-only (test/CI) · prod (16GB+ GPU) |
| V-9 | Config skills | Draft-only for MVP; CRUD endpoints a fast-follow |
| V-10 | Alerts | Subscribe to existing FAILED events + enrich `BatchEvent`; async hand-off |
| **A-1** | **Config keystone** | **✅ Shipped (v3.2.0): Smart Config (`ConfigSpec` + `ConfigRegistry`) landed before the config-authoring skills** |
| **A-2** | Compatibility | Additive only; `.toon` stays canonical; suite green each step; lean core zero-new-dep |
| **A-3** | **Data keystone** | **Metadata Graph is a foundational milestone before any assist skill — built first, shipped as M1 (v3.1.0)** |
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

### M1 — Metadata Graph (the data keystone) → v3.1.0 ✅ *shipped*
*Makes the platform's emitted data self-describing **and connected**: a typed, traversable graph
linking sources → raw schemas → columns → partitioned event tables → Stage-2 transforms →
KPIs/reports, with operational state (status/lineage/completeness/error) overlaid on every node and
descriptions sourced three ways (**manual > AI > deduced**). Core, zero-new-dependency, API-first,
consumed by every assist skill; the substrate `kpi-to-sql` and `explain-entity` stand on. Addresses
architecture gap G8. Built ahead of the config keystone at the user's direction.*

**Build decisions (confirmed):** **graph-API-only** this milestone (rendering deferred to the Web
UI); **AI descriptions arrive at M3** via the `DescriptionProvider` SPI (this milestone is fully
core / zero-AI — the seam ships with a no-op provider); the graph is **derived + cached (live)** —
structural graph cached & rebuilt on reload, operational overlay fetched lazily per node by reusing
the existing audit reads. New package: `com.gamma.catalog`.

- **T1.1 — Schema description enrichment (in the `.toon`).** ✅ Extended the schema tabular header
  `fields[N]{name,selector,type}` → `…{name,selector,type,description}` (+ optional `unit`,
  `classification`). **Backward-compatible:** JToon parses each row positionally against its own
  header, and the ETL data path reads only `name`/`type` — so 3-column files still parse and the
  extra columns are invisible to ingest (regression-guarded by the unchanged data-path tests).
  Only `SchemaProjection` reads them. **`SchemaExtractor` now merges/preserves** authored prose on
  regeneration (matched by column name, never clobbered; header stays uniform).
- **T1.2 — `*_meta.toon` semantic descriptor** (`SemanticModel`). ✅ Co-located with enrichment
  configs, loaded by the same suffix-scan (`*_meta.toon`). Holds the **cross-table** layer: a
  **KPI catalog** (named KPI → NL definition, grain, inputs, join keys), **domain notes** (units,
  currency, time-zone, "revenue excludes tax"), and table-level descriptions. Refs may be plain or
  fully-prefixed node ids (quoted in inline arrays).
- **T1.3 — `MetadataGraphService`** (core, zero new deps). ✅ Assembles a typed **node/edge graph**
  from a `ConfigSource` seam (pipelines + enrichments + semantic models): node kinds SOURCE,
  RAW_SCHEMA, COLUMN, EVENT_TABLE, TRANSFORMED_TABLE, REFERENCE_TABLE, KPI, REPORT; edges EMITS,
  DECLARES, DESCRIBES, MATERIALIZES, FEEDS, JOINS_INTO, COMPUTED_FROM, USES. Structural graph cached
  & invalidated on each poll cycle (swaps to the M2 `ConfigRegistry` watch later). Operational
  overlay (`CatalogOverlay`) fetched **lazily per node**, reusing `StatusStore`/`EnrichmentService`
  reads (status, cumulative rows/bytes, parsed/error rows, lineage; `NO_DATA` when nothing
  committed). BFS traversal with direction / node-kind / edge-kind filters. Light DuckDB `DESCRIBE`
  verify (A-4) remains an optional follow-up.
- **T1.4 — Catalog API.** ✅ `GET /catalog` (table list), `GET /catalog/tables/{id}` (node +
  overlay + neighbours), `GET /catalog/kpis` (KPI catalog + domain notes), and a traversable
  `GET /catalog/graph?from=&depth=&direction=&kinds=&edgeKinds=&overlay=`. Scoped `assist.read`
  (satisfied by `CONTROL`); feeds both the UI and the agent.
- **T1.5 — `DescriptionProvider` SPI** (`com.gamma.catalog.spi`). ✅ ServiceLoader-discovered seam;
  fills only `NONE`-provenance columns, never overwrites authored prose. Core ships a no-op; the
  `file-processor-agent` module registers an AI-backed provider at M3 with zero core change.
- *Exit:* ✅ every emitted event table is enumerable with domain-described columns; the KPI catalog
  + domain notes load and round-trip; the catalog + graph API serve a sample source end-to-end
  (HTTP-tested); shipped sample schema (`config/events/call_schema.toon`) carries real descriptions;
  full reactor green (239 core + 2 agent), lean core unchanged.

### M2 — Smart Config layer (the config keystone) → v3.2.0 ✅ **shipped**
*Solves G1–G5/G10; everything AI/UI leans on it. Concrete class shapes in
[design_analysis.md §4.A](design_analysis.md). The M1 catalog's `ConfigSource` seam was designed to
slot onto this milestone's `ConfigRegistry` — and now does, with zero `MetadataGraphService` change.*
- **T2.1 ✅** `com.gamma.config.spec`: `ConfigSpec`, `FieldSpec` (type/required/default/enum/
  pattern/uiHint/visibleWhen/**description**), `CrossFieldRule` (a `@JsonIgnore` predicate that
  returns true-when-satisfied; the rule catalog still serialises for UI/LLM), `Finding`,
  `Severity`, `FieldType`, and `RawConfig` dotted-path navigation. Authored `ConfigSpecs` for
  pipeline / enrichment / job / schema / **meta**, encoding today's implicit rules verbatim
  (plugin-ingester⇒segments, engine×skip-tail, threads×duckdb_threads, threads-vs-batch,
  dup-check-retention, transform-or-file, job-type, cron-field-count).
- **T2.2 ✅** `com.gamma.config.io`: pluggable `ResourceLoader` (`FilesystemResourceLoader`
  default; `MapResourceLoader` for tests/REST drafts), `ConfigLoader` with `decode→Map` (no side
  effects) + pure `validate(spec,raw)→List<Finding>`. Split the one disk side-effect out of
  `PipelineConfig.load` into `prepare()`; added public `fromMap` (pure parse) on `PipelineConfig`,
  `EnrichmentConfig`, and `JobConfig` — `load(path)` now delegates (identical behaviour).
- **T2.3 ✅** `ConfigCodec` — canonical, comment-free, strict-decodable `.toon` encode + lenient
  decode (round-trips every shipped sample); JSON wire form via the API's Jackson mapper.
  `.toon` stays canonical + backward-compatible.
- **T2.4 ✅** `ConfigRegistry` keyed by **in-file identity** → O(1) lookups; replaced the O(n)
  re-parse scans in `SourceService.pathFor/configFor/activeRegistry/pipelines/loadConfigs`; resolved
  the discovery-suffix vs in-file-identity mismatch (lookups address pipelines by their declared
  name regardless of filename; duplicates warn). **The catalog's per-cycle `invalidate()` now fires
  off the registry's rebuild callback.** (The run path still uses raw registry paths + re-loads each
  cycle, so a cached config's frozen run-timestamp never affects a run; audit reads resolve by the
  stable status dir + name + persistent commit log.)
- **T2.5 ✅** API: `GET /config/spec/{type}` (scope `assist.read`) + extended `POST /validate`
  accepting an in-memory draft (`{type,config}`) — validates with no file written, returns
  structured `Finding`s; the legacy `{configPath}` form still works (now also returns `findings`).
- *Exit ✅:* `load(path)` delegates to the new pipeline; all prior config/service tests green
  unchanged (regression proof); the codec round-trips every shipped sample; full reactor green
  (**277 core + 2 agent**, +38 new tests), fat-JAR **90.3 MB, zero new deps, 0 AI classes**.

### M3 — Assist platform + first slice `explain-entity` (read-only) → v3.3.0 ✅ *shipped*
- **T3.1 ✅** `file-processor-agent`: **LangChain4j + Ollama** (1.15.1, pinned in the agent POM only)
  behind a `ModelProvider` seam (`com.gamma.agent.model`: `ModelProvider`/`OllamaModelProvider`/
  `FakeModelProvider`/`ModelRouter`/`AssistProfile`/`ModelTier`). **Grammar-constrained output** via
  Ollama's native `format=json`. **Abstain-by-default**: providers contact no model until the assist
  layer is explicitly enabled (`-Dassist.enabled=true`) — so CI/vanilla runs do zero model I/O. Three
  hardware profiles (V-8): `cpu-only`/`dev-laptop`/`production`. Air-gapped packaging omits hosted SDKs
  (verified by `EgressGuardTest`).
- **T3.2 ✅** `SkillRegistry` (intent→`Skill`) + the core seam: `AssistRequest`/`AssistResult` DTOs +
  a default `AssistAgent.assist(...)` + `POST /assist/{intent}` (scope `assist.read`, 503 no-agent /
  404 unknown intent / 503 model-down / 200 OK). `UccAssistAgent` registered via `ServiceLoader`.
- **T3.3 ✅** `explain-entity` skill (7B / MEDIUM tier) — grounds on the M1 catalog
  (`hydrated`/`traverse`/`domain`) + Control API reads + `docs/*.md` (a tiny `DocRetriever`) →
  `{answer, citations[], links[]}`. **Citations are derived from the sources fed to the model**, so a
  local model can't fabricate a reference. Read-only: `applyVia` always null.
- **T3.4 ✅** Golden-test harness, **CPU-only** (a deterministic `FakeModelProvider` injected through
  the seam — no Ollama in CI). Audit trail (`AuditEvent`) — one suggestion event per call. AI
  `DescriptionProvider` (`com.gamma.agent.catalog.AiDescriptionProvider`, SMALL tier) registered via
  `ServiceLoader`, abstain-safe + never-throws, preserves `MANUAL`.
- *Exit (met):* zero write surface; agent answers entity + catalog questions end-to-end; lean core
  stays 0-AI (90 MB fat-JAR, 0 AI classes); full reactor green CPU-only (**282 core + 28 agent**).

### M4 — `nl-to-schedule` (draft-only) → v3.4.0 ✅ *shipped*
- **T4.1 ✅** `NlToScheduleSkill` (`com.gamma.agent.skill`): NL → JSON `{cron, on_pipeline, job_type,
  name}` → a validated **JobConfig draft** surfaced as `{cron, onPipeline, jobType, humanReadable,
  nextRuns[], draftToon, findings[]}`. The validation oracle is **all reuse** — the core zero-dep
  `CronExpression` (`parse`/`next`), `JobConfig.fromMap`, and `ConfigSpecs.job()` + `ConfigLoader.
  validate` — driven by a new **`RepairLoop`** (generate→validate→repair, capped at 3 rounds, the
  verbatim oracle error fed back). A deterministic **`CronDescriber`** produces `humanReadable`;
  `nextRuns[]` is `CronExpression.next` ×5; `draftToon` is `ConfigCodec.toToon`. **Tier routing**
  (V-5/V-8): plain phrasing → SMALL (2–3B), compositional/relative/timezone → MEDIUM (7B).
  `on_pipeline` is grounded against real catalog SOURCE nodes (the node id is the **derived**
  citation — the model can't fabricate a pipeline). The only core change is one additive
  `AssistResult.data` structured-payload field (JDK-only; zero new deps).
- **T4.2 ✅** Golden tests (CPU-only, deterministic `FakeModelProvider`): plain case round-trips its
  `draftToon` back through `JobConfig.fromMap`; compositional case grounds + cites the pipeline;
  invalid model cron is **repaired, not surfaced**; a hallucinated pipeline is rejected by grounding;
  model-unavailable degrades to `unavailable`; tier-routing + `CronDescriber` shapes unit-tested;
  end-to-end `POST /assist/nl-to-schedule` over HTTP (scoped `assist.read`, `applyVia` null, no job
  created — draft-only); one audit suggestion event per call.
- *Exit (met):* the cron-builder-widget replacement works end-to-end; a draft `.toon` is returned for
  the user to save; **draft-only** (no write surface); full reactor green CPU-only (**283 core +
  48 agent**); lean core stays 0-AI (90.3 MB fat-JAR, 0 AI classes, 0 new deps).

### M5 — `suggest-config` (draft-only) + config safety validator → v3.5.0 ✅ *shipped*
- **T5.1 ✅** `SuggestConfigSkill` (`com.gamma.agent.skill`, 7B / MEDIUM): `{configType, sourceSample?,
  partialConfig}` → JSON `{fields:[{name,value,rationale,confidence}]}` merged onto the partial config
  → a validated draft `{fields[], validated, draftToon, findings[], safetyChecked}`. All config types
  via `ConfigSpecs.forType`. The oracle (driven by the M4 `RepairLoop`, cap 3) is `ConfigSpecs`
  validate (structural) **+ the new hard-fail `ConfigSafetyValidator` (security) + a pure type-parse**
  (`JobConfig`/`EnrichmentConfig.fromMap`; pipeline leans on spec+safety since `PipelineConfig.fromMap`
  resolves a schema file off disk). Per-field `rationale`/`confidence` are surfaced because structural
  validity ≠ semantic correctness (confirm-first). Draft-only: `applyVia` null.
- **T5.1a ✅ — `com.gamma.config.safety` (core, zero-dep, security guardrail R6).** `SafetyPolicy`
  (allowed filesystem roots, numeric caps, output format/codec allow-list; `defaultPolicy()` reads
  `-Dassist.safety.roots`, else `user.dir`) + `ConfigSafetyValidator.check(type, raw, policy)` →
  ERROR `Finding`s for: **path jail** (reject UNC, `..` escapes, outside-root, symlink-escape via
  `toRealPath`), **numeric bounds** (threads/duckdb-threads/batch caps; `retention_days` ≥ 1 when
  dedup on), **output allow-list**. Additively surfaced on `POST /validate` behind an opt-in
  `"safety":true` flag (default response unchanged). Does **not** touch the production config-load path.
- **T5.2 ✅** Tests: adversarial `ConfigSafetyValidatorTest` (traversal/UNC/outside-root/symlink/
  out-of-bounds/bad-output all rejected; clean under-root passes); `/validate` safety on/off;
  `SuggestConfigSkillTest` golden (pipeline draft round-trips + is spec-clean; **a model-suggested
  unsafe path is rejected by the gate and repaired, not surfaced**; enrichment grounds + cites a
  catalog node; model-unavailable → graceful); end-to-end `POST /assist/suggest-config` (scoped
  `assist.read`, `applyVia` null, no config written); one audit event per call.
- *Exit (met):* config-form replacement works end-to-end; the safety validator **blocks
  harmful-but-parseable configs**; draft-only; full reactor green CPU-only (**296 core + 55 agent**);
  lean core stays 0-AI (90.3 MB fat-JAR, 0 AI classes, 0 new deps).

### M6 — `kpi-to-sql` (the hero) + SQL sandbox → v3.6.0 ✅ **shipped v3.6.0**
- **T6.1 — `SqlOracle` (locked-down DuckDB).** ✅ Realized as the core **`com.gamma.sql`** package
  (zero-new-dep — DuckDB was already core): **`SqlViews`** (the `reader()`/`ext()` view-builder
  extracted from `EnrichmentEngine`, which now delegates to it — one source of truth, no drift),
  **`SqlGuard`** (lexical/structural allow-list), **`SqlSandbox`**/`SqlSandboxPolicy` (hardened
  ephemeral connection), and **`SqlOracle`** (validate = guard → seal → EXPLAIN + LIMIT 0). Two
  layers: `SqlGuard` rejects `;` multi-statements, DDL/DML, and `read_*`/`write_*`/`*_scan`/`copy`/
  `getenv`/`pragma`/`install`/`load`/`attach`/`query` (comment-smuggling defeated by stripping comments
  before the token scan) — allowing only a single `WITH`/`SELECT` — **because `EXPLAIN` can still
  evaluate smuggled functions during planning**; `SqlSandbox` registers the inputs while file access is
  on, then **`seal()`s** (`enable_external_access=false`, `lock_configuration=true`, extension
  auto-install/auto-load off, memory/threads/timeout caps) before the untrusted candidate ever runs.
- **T6.2 — `kpi-to-sql` skill** ✅ `com.gamma.agent.skill.KpiToSqlSkill` (`tier()=LARGE`):
  `{kpiDescription,targetGrain,catalogRefs[],domainNotes?,sampleRows?}` (the `catalogRefs` resolve
  against the **M2 Data Catalog + KPI catalog** — EVENT_TABLE/TRANSFORMED_TABLE/REFERENCE_TABLE →
  oracle view specs, KPI → prompt grounding) → `{sql,logicExplanation,columnsProduced,chosenJoinKeys,
  kpiInterpretation,validated,sampleRows?,enrichmentConfigSnippet,repaired}`. `RepairLoop` over
  `SqlGuard` + `SqlOracle` (EXPLAIN/LIMIT-0, cap 3). 14B prod / hosted-recommended connected.
  **Confirm-first; surfaces interpretation + chosen join keys + opt-in sample rows** (the oracle proves
  it *runs*, not that it computes the KPI); `columnsProduced` is taken from the oracle's
  `ResultSetMetaData`, not the model's claim. Draft-only (`applyVia` null). M6 deltas: sample rows are
  **opt-in** (default response carries no data-plane values); EVENT_TABLE nodes gained an additive
  `format` attr so the oracle reads them correctly.
- *Exit (met):* KPI-in-domain-terms → validated Stage-2 SQL grounded in the catalog, draft-only; the
  SQL sandbox rejects file/extension/DDL/DML/multi-statement/comment-smuggled queries (adversarially
  tested); full reactor green CPU-only (**323 core / 1 skipped + 68 agent**); lean core stays 0-AI
  (0 AI classes, 0 new deps, `com.gamma.sql` present). Closes architecture gap **G4**.

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
- **Lean core gains zero new deps** (CI-enforced); all AI/hosted deps in `-agent`. (The Metadata
  Graph + semantic layer in M1 are core, zero-dep.)
- **Confirm-first, no autonomous apply**; agent holds no write token.
- **Air-gapped = local-only by packaging**; sample rows never sent to a hosted model.
- The non-negotiable security guardrails in [v3-agent-mvp.md](v3-agent-mvp.md#non-negotiable-security-guardrails).

---

**Net sequence:** M0 foundation ✅ (v3.0.0) → **M1 Metadata Graph (data keystone)** ✅ (v3.1.0) →
**M2 Smart Config (config keystone)** ✅ (v3.2.0) → **M3 assist platform + `explain-entity`** ✅ (v3.3.0;
consumes the catalog; adds the AI `DescriptionProvider`) → **M4 `nl-to-schedule` (draft-only)** ✅
(v3.4.0; the generate→validate→repair oracle on the core cron engine) → **M5 `suggest-config`
(draft-only) + config safety validator** ✅ (v3.5.0; R6 path-jail/bounds/output gate) →
**M6 `kpi-to-sql` (hero) + SQL sandbox** ✅ (v3.6.0; grounds on the catalog/KPI catalog; closes G4) →
**M7 `diagnose-and-alert`** → (M8 reports) → CRUD fast-follow → UI/distributed deferred. One minor
release per milestone on `3.x`, additive, suite-green, lean core preserved.
