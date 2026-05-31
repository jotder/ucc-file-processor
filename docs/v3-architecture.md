# v3.0 Architecture — Assessment & Redesign

> Planning artifact for the 3.x line. Branch: `3.x`. Design-only.
> Companion to [v3-agent-mvp.md](v3-agent-mvp.md) (the assist-agent MVP) and
> [v3-plan.md](v3-plan.md) (the sequenced build). Grounded in a code-level review of the
> actual `2.11.0-SNAPSHOT` tree — where the older [architecture.md](architecture.md) /
> [design-notes.md](design-notes.md) disagree with the code, the **code is truth** (see A0).

---

# Part A — Current architecture assessment

## A0. Doc-vs-code drift (fix first)
`architecture.md` and `design-notes.md` still describe the 1.x/2.0 "M..N multiplexer" and
**list joins/aggregation as non-goals**. That is stale: the 2.x line shipped a full
**Stage-2 enrichment engine** (`com.gamma.enrich`) doing exactly those joins/aggregations,
plus a service/API/observability control plane. Reconciling these primary docs is a 3.x
task (tracked in the plan). Below reflects the real tree.

## A1. System shape
A single Maven module (`com.gamma.inspector:file-processor`, Java 24, DuckDB embedded) with
two data stages under one control plane:

- **Stage-1 ingest** (`com.gamma.etl` + `com.gamma.inspector`): poll → `BatchPlanner` →
  per-batch isolated temp DuckDB → ingest (`DuckDbCsvIngester`/`CsvIngester` or a reflective
  `FileIngester` plugin) → `DataTransformer` → `PartitionWriter` (Hive-partitioned, atomic
  rename) → markers/quarantine → `CommitLog` (fsync) + audit CSVs → **emits `BatchEvent`**.
- **Stage-2 enrichment** (`com.gamma.enrich`): `EnrichmentEngine` on its own ephemeral temp
  DuckDB → register reference + `input` views over Stage-1 partitions → run `transform` SQL →
  idempotent partitioned write → `EnrichmentAuditWriter`. Orchestrated by `EnrichmentService`
  (event- and schedule-driven, per-job lock, self-chaining via the bus).
- **Control plane** (`com.gamma.service`/`.control`/`.report`/`.job`/`.metrics`):
  `SourceService` (the hub), `BatchEventBus` (sync pub/sub), `Scheduler` (cron + fixed-delay),
  `ControlApi` (JDK `HttpServer`, ~25 routes, Jackson, bearer auth), `MetricsService` +
  `MetricRegistry` (dependency-free Prometheus), `JobService`/`JobConfig`, `ReportService`,
  `StatusStore` (`FileStatusStore` | `DbStatusStore`).

## A2. Existing extension seams (the good news for 3.x)
- **`BatchEventBus` pub/sub** — the natural integration point; enrichment, metrics, and jobs
  all subscribe. A new reactive consumer (e.g. the agent's failure-diagnoser) is a `subscribe`.
- **`ControlApi` route table** — a flat list of `Route(method, regex, auth, handler)`; adding
  `/assist/*` or `/config/*` routes is a one-line change each.
- **`SourceService` is the typed hub** — already exposes `reports()`, `enrichmentService()`,
  `jobService()`, `statusStore()`, `eventBus()`, `pipelines()`, `configFor()`. A clean
  injection point for an in-JVM agent and a BFF.
- **`FileIngester` SPI**, **`StatusStore` seam**, **CSV-engine seam**, **`Scheduler.cron`**,
  **`MetricRegistry` collectors**, and **declarative `triggers` chaining** are all live
  extension points.

## A3. Strengths to preserve
- Zero-dependency-discipline + a clean lean fat-JAR.
- Idempotent, crash-safe commit ordering (markers last; fsync'd commit log).
- Virtual-thread fan-out with bounded pressure; per-batch DuckDB isolation.
- A real observability + audit story (metrics, status store, run/lineage audit, reports).
- Engine-neutral `StatusStore` (DuckDB now, Postgres-ready).

## A4. Gaps & coupling that block "AI behind every screen" + a UI
Distilled from the code review; these set the redesign agenda.

| # | Gap (code-grounded) | Why it blocks AI/UI |
|---|---|---|
| G1 | **No machine-readable config schema.** Field names, types, required-ness, defaults, enums, and cross-field rules live only as imperative `load()` code + prose in `configuration.md`. | An AI can't reliably generate/validate configs; a UI can't auto-render forms. **The central gap.** **✅ Resolved by M2 `ConfigSpec`/`ConfigSpecs` (v3.2.0).** |
| G2 | **Config parsing is welded to file I/O + side effects.** `PipelineConfig` has a *private* constructor, a file-only `load(path)` that reads referenced files and calls `Files.createDirectories`. Even tests must write a temp `.toon`. | No way to validate a draft from an API body / DB; no programmatic build. **✅ Resolved by M2: `fromMap`/`prepare` split + `ResourceLoader`/`MapResourceLoader` (v3.2.0).** |
| G3 | **Validation is split & partly non-fatal & file-bound.** `Identifiers` (fatal) + `ConfigValidator` (SLF4J warnings, `List<String>`) + inline `require`. `/validate` needs a path on disk. | No structured, field-located, severity-tagged result for an AI repair-loop or a UI's inline errors. **✅ Resolved by M2: `Finding{severity,fieldPath,message}` + draft `/validate` (v3.2.0).** |
| G4 | **No config registry / stable identity.** "Registry" = a `List<Path>`; discovery keys on filename suffix but identity keys on the in-file `name`; `pathFor(name)` **re-parses every file** each call (O(n) per lookup, several full parses per poll). | A UI/BFF needs stable IDs + a listing surface, not an O(n) disk re-scan. **✅ Resolved by M2 `ConfigRegistry` (in-file-identity key, O(1) lookups) (v3.2.0).** |
| G5 | **TOON footguns** must be "known": quote any value containing `:` (Windows paths/JDBC URLs), **no `#` comments** (a shipped sample violates this), map-vs-tabular array choice is load-bearing. | An AI/UI emitting raw `.toon` will trip these; needs a schema-aware serializer. **✅ Resolved by M2 `ConfigCodec` (canonical, comment-free, strict-decodable encode) (v3.2.0).** |
| G6 | **SQL runs on an unsandboxed DuckDB connection** with full filesystem/extension access; no resident schema-loaded connection to reuse. | Agent-generated SQL needs a separate locked-down oracle (see v3-agent-mvp security). |
| G7 | **Coarse security:** one shared bearer token, **open-by-default with a warning**, non-constant-time compare. | An LLM-driven surface needs scoped tokens + fail-closed. |
| G8 | **No semantic/business metadata** (schema model is `{name,selector,type}` only) **and no catalog of emitted event tables**. | NL→SQL has nothing to ground on. *(**Resolved** by the M1 Metadata Graph (v3.1.0): schema `description`/`unit`/`classification` columns + `*_meta.toon` KPI catalog + `MetadataGraphService` — a typed, traversable graph of sources → schemas → columns → event tables → transforms → KPIs/reports with a lazy operational overlay, served at `/catalog*`.)* |
| G9 | **`SourceService` is a god-object** (registry + status sync + pause state + store selection + recovery) and `MetricRegistry.global()` is a process-wide singleton. | Harder to embed, test in isolation, or serve multi-tenant from a BFF. |
| G10 | **No config write path.** All configs are disk-loaded at startup/poll; `ControlApi` has read + lifecycle routes but no create/update-config. | Agent config skills are draft-only until write endpoints exist. |

**Correction to an earlier assumption:** the bus **already emits FAILED terminal events**
(consumers filter non-SUCCESS), so the alert seam is *enrich the event + subscribe without
filtering*, not a new channel.

---

# Part B — Fresh 3.x redesign

## B0. Design goals
1. **Make config a first-class, machine-readable model** that simultaneously powers the
   loader, an AI generator, and a UI form-renderer from one source of truth. *(The keystone —
   everything else leans on it.)*
2. **Embed the assist agent in-JVM** behind a clean SPI (per [v3-agent-mvp.md](v3-agent-mvp.md)).
3. **Make the system UI/BFF-ready** — stable identities, a registry, structured validation,
   config CRUD-from-body, and a descriptor endpoint a UI can render.
4. **Harden the boundaries** an LLM touches (SQL sandbox, scoped auth, fail-closed egress).
5. **Preserve the invariants** — zero-dep lean core, `.toon` as the canonical on-disk format,
   idempotent crash-safe commits, the engine itself. Changes are **additive**; the 2.x test
   suite stays green at each step.

## B1. The Smart Config model *(keystone — solves G1–G5, G10)*
Introduce a declarative **ConfigSpec** layer — one source of truth consumed by three clients
(the loader, the AI, the UI).

**The descriptor model** (new package, e.g. `com.gamma.config.spec`):
- `ConfigSpec` — one per config type (pipeline / enrichment / job / schema), an ordered list
  of `FieldSpec` + a list of `CrossFieldRule`.
- `FieldSpec` — `path` (dotted), `label`, `description`, `type` (string/int/long/bool/enum/
  list/map/nested/**filepath/glob/cron/sql**), `required`, `default`, `enumValues` (each with
  a description), `constraints` (min/max/pattern), `uiHint` (widget), `visibleWhen`
  (conditional), `examples`.
- `CrossFieldRule` — predicate + severity + message + field locators (encodes today's implicit
  rules: *exactly-one-of* `schema_file`|`schemas[]`|`ingester`+`segments`; `engine=duckdb`
  incompatible with `skip_tail_columns>0`; `threads×duckdb_threads ≤ cores`; partitions-must-be-
  tabular-list).

**The refactored config pipeline** (solves G2/G3) — split the welded `load(path)` into pure,
composable stages:
```
source (file | API body | DB row)
   → ResourceLoader            (pluggable; default = filesystem; resolves referenced files)
   → JToon.decode → raw Map
   → parse(spec, raw) → Config  (PURE — no disk, no side effects)
   → validate(spec, Config) → List<Finding{severity, fieldPath, message}>   (PURE, structured)
   → [prepare(Config)]          (explicit: createDirectories etc., ONLY when actually running)
```
- `PipelineConfig` gains a public `fromMap`/builder; disk side effects move out of parse into
  `prepare`. `EnrichmentConfig`/`JobConfig` (already records) get the same `parse`/`validate`
  split so a hand-built instance is validated identically to a loaded one.
- `validate(Config)` runs `Identifiers` (error) + `ConfigValidator` (warning) + cross-field
  rules and returns **structured findings** (severity + field path) — the same result feeds an
  AI repair-loop and a UI's inline field errors. `/validate` gains a **body** form (validate a
  draft string/JSON, no file required).

**Schema-aware serializer** (solves G5): a single writer that always quotes colon-bearing
values, never emits `#`, and chooses inline-vs-tabular arrays correctly — so neither the AI nor
the UI can produce a malformed `.toon`. `.toon` stays the canonical persisted format
(backward-compatible); JSON is the wire form for the API/UI.

**ConfigRegistry** (solves G4): an in-memory registry keyed by **stable id** (explicit
`id`/`name`, decoupled from filename), built once and refreshed on file-watch or explicit
reload — replacing the per-call O(n) re-parse scans. Backs config listing, lookup, and (later)
CRUD. Also fixes the discovery/identity mismatch (the `events_daily_kpi.toon` that doesn't match
the `_enrich.toon` suffix is a concrete symptom).

> **Why this is the keystone:** the same `ConfigSpec` makes `kpi-to-sql`/`suggest-config`
> reliable (the AI generates *to the spec* and validates against structured findings) **and**
> lets a future UI auto-render every config form from `/config/spec/{type}` with zero
> duplicated field knowledge. One model, three consumers.

## B2. Assist-agent integration (summary; full detail in v3-agent-mvp.md)
- **In-JVM via an `AssistAgent` SPI** loaded by `SourceService` (before `start()` so it can
  subscribe to the bus), deps isolated in the optional `file-processor-agent` module.
- **Assist API** (`/assist/{intent}`) on the existing route table; **scoped assist token**.
- **`SqlOracle`** extracted from `EnrichmentEngine`'s view-registration logic, run on a
  **locked-down** DuckDB connection (no external access, statement allow-list) — the validator
  for `kpi-to-sql`/`report-sql`.
- **Metadata Graph** (the **data keystone**, M1 / v3.1.0 — **implemented**, see [v3-plan.md](v3-plan.md)) =
  schema-`.toon` `description`/`unit`/`classification` columns + a new **`*_meta.toon`** (KPI
  catalog + domain notes), assembled by **`MetadataGraphService`** into a typed, traversable graph
  (sources → raw schemas → columns → emitted event tables → Stage-2 transforms → KPIs/reports) with
  a **lazy operational overlay** (status/lineage/completeness/error reused from the audit reads) and
  a **`DescriptionProvider` SPI** (manual > AI > deduced; **the AI provider shipped at M3 / v3.3.0**
  as `com.gamma.agent.catalog.AiDescriptionProvider`). Served at `/catalog`,
  `/catalog/tables/{id}`, `/catalog/kpis`, `/catalog/graph`. This is what `kpi-to-sql` and
  `explain-entity` ground on.
- **Failure-diagnoser** subscribes to the (enriched) FAILED `BatchEvent`s, hands off async.

## B3. UI / BFF readiness *(architecture is UI-ready even though the UI ships later)*
The redesign exposes exactly what a thin BFF / Web UI needs — no UI is built in MVP, but the
seams are:
- **`GET /config/spec/{type}`** — the `ConfigSpec` as JSON → a UI renders forms generically;
  the agent reads the same to constrain generation.
- **Config CRUD-from-body** — `GET/POST/PUT /configs[...]` validate-and-persist a draft via the
  `ResourceLoader` + serializer (the write path of G10; promotes agent config skills from
  draft-only to one-click-apply — sequenced as a fast-follow).
- **Registry/listing** — `GET /configs` with stable ids + status (backed by `ConfigRegistry`).
- **Assist manifest per screen** — each screen declares the intents + context it supplies.
- **Scoped tokens** (`assist.read`/`assist.write`/control) + fail-closed (no open default).
- **Stable JSON DTOs** — `ReportService`/audit DTOs are already clean JSON; keep them stable
  as the BFF contract.

This is the "smarter agent reduces UI components" thesis made structural: the UI becomes a thin
renderer of specs + a caller of assist intents, not a hand-built form for every config field.

## B4. Architectural changes — inventory (where applicable)
| Change | Scope | Driver | When |
|---|---|---|---|
| **Multi-module build** (parent POM + `-core` + optional `-agent`) + shade rework | Build | in-JVM agent dep isolation | MVP prerequisite (P6) |
| **Smart Config layer** (`ConfigSpec`/`ConfigSpecs`, `ConfigLoader` parse/validate split, `ResourceLoader`, `ConfigCodec` serializer, `fromMap`/`prepare`) | `com.gamma.config.spec`/`.io`, `PipelineConfig`/`EnrichmentConfig`/`JobConfig` | G1–G3, G5 | **M2 / v3.2.0 — the config keystone ✅ shipped** |
| **`ConfigRegistry`** (in-file-identity id, rebuild-on-cycle, O(1) lookups) | `com.gamma.service` | G4 | **M2 / v3.2.0 ✅ shipped** |
| **Structured validation findings + `/validate` body form + `GET /config/spec/{type}`** | `ConfigLoader`→`Finding`, `ControlApi` | G3 | **M2 / v3.2.0 ✅ shipped** |
| **Assist platform + `explain-entity`** (`ModelProvider`/`ModelRouter`/`AssistProfile` seam over LangChain4j+Ollama, `SkillRegistry`, `ExplainEntitySkill`, `AiDescriptionProvider`, `AssistRequest`/`AssistResult` DTOs + `AssistAgent.assist` + `POST /assist/{intent}`) | core seam in `com.gamma.assist`/`ControlApi`; all AI in `com.gamma.agent`(`.model`/`.skill`/`.catalog`) | V-1/V-3/V-4 | **M3 / v3.3.0 ✅ shipped (local-only; lean core stays 0-AI)** |
| **`SqlOracle` + locked-down DuckDB connection** | extract from `EnrichmentEngine`; `com.gamma.config`/agent | G6 | with `kpi-to-sql` |
| **Security hardening** (scoped tokens, constant-time compare, no open default) | `ControlApi` | G7 | MVP |
| **Enrich `BatchEvent` with error detail + non-filtering subscriber** | `etl`/`service` | C1 alerts | MVP |
| **Metadata Graph** (schema `description`/`unit`/`classification` columns + `*_meta.toon` + `MetadataGraphService` + `CatalogOverlay` + `DescriptionProvider` SPI + `/catalog*` API) | new `com.gamma.catalog`(`.spi`), `SchemaExtractor`, `SchemaSelector`, `SourceService`, `ControlApi` | G8 | **M1 / v3.1.0 — the data keystone ✅ implemented (built ahead of the config keystone)** |
| **`SourceService` decomposition** (extract `ConfigRegistry`, separate status-sync) | `com.gamma.service` | G9 | opportunistic, low-risk slices |
| **Config write endpoints** (CRUD-from-body) | `ControlApi` | G10 | fast-follow after MVP |
| **`MetricRegistry` non-singleton option** | `com.gamma.metrics` | G9 | deferred (only if multi-tenant) |
| `DbStatusStore` single-connection → pool | `com.gamma.service` | distributed future | deferred to v3.x distributed track |

## B5. Target module & package layout
```
file-processor-parent (pom)
├── file-processor-core         ← the engine + control plane (stays zero-new-dep, lean fat-JAR)
│   ├── com.gamma.etl / inspector / enrich          (unchanged engines)
│   ├── com.gamma.config.spec                        (NEW: ConfigSpec, FieldSpec, rules)
│   ├── com.gamma.config.io                          (NEW: ResourceLoader, serializer, parse/validate)
│   ├── com.gamma.service (+ ConfigRegistry, AssistAgent SPI), .control (+/assist,/config,/configs)
│   ├── com.gamma.report / job / metrics / api
│   └── com.gamma.assist.spi                          (NEW: the agent interface core depends on)
└── file-processor-agent        ← OPTIONAL: LangChain4j + Ollama + (connected) hosted SDK
    └── com.gamma.agent          (implements AssistAgent SPI; skills, model router, SqlOracle use)
        — air-gapped build omits hosted SDKs (local-only guaranteed by packaging)
```
A future `file-processor-bff` / Web-UI module is a parallel track; the core's API surface
(B3) is designed so it can be thin.

## B6. Preserved invariants (non-goals for the redesign)
- **The engines don't change** — Stage-1 multiplexer + Stage-2 enrichment semantics, partition
  layout, commit ordering, idempotency all stand.
- **`.toon` stays the canonical on-disk config** — the smart-config layer reads/writes it
  backward-compatibly; JSON is only the API/UI wire form.
- **Lean core stays zero-new-dep** — all AI/hosted deps live in `-agent`; CI enforces it.
- **Confirm-first, propose-don't-dispose** — no autonomous writes; agent holds no write token.
- **On-disk output format** unchanged and forward-compatible.

## B7. Migration & compatibility
- **Additive, suite-green-at-each-step** (the 2.x discipline). The config refactor lands behind
  the existing `load(path)` (it delegates to `ResourceLoader → parse → validate → prepare`), so
  existing callers and `.toon` files keep working unchanged.
- **No breaking change to `@PublicApi`** in 3.0's first milestones; if `PipelineConfig` gains a
  public builder/`fromMap`, that's additive. A breaking surface change (if any) is called out
  per the [api-stability policy](api-stability.md) and reserved for a deliberate bump.
- **Doc reconciliation** — `architecture.md`/`design-notes.md` updated to reflect the two-stage
  reality (A0) as part of the first milestone.

---

**Net:** 3.x keeps the proven engines and lean core, and adds three structural things: a
**machine-readable Smart Config model** (one source of truth for loader + AI + UI), an
**in-JVM assist agent** behind a clean SPI with hardened boundaries, and a **UI/BFF-ready API
surface** (config specs, structured validation, registry, scoped auth). The keystone is Smart
Config — it's what makes both "AI that writes valid configs/SQL" and "a UI that auto-renders
forms" fall out of the same model, delivering the "smarter agent, fewer UI components" thesis.
