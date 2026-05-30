# v3.x Implementation Plan — sequenced milestones

The strategic "what/why" is in [v3-architecture.md](v3-architecture.md) (assessment +
redesign) and [v3-agent-mvp.md](v3-agent-mvp.md) (the assist-agent MVP). This is the
"how / in-what-order" — a finalized, sequenced task list. Each milestone is independently
releasable as a minor version on the `3.x` branch, mirroring the 2.x cadence (one minor
release per milestone: feature → release commit → annotated tag → next `-SNAPSHOT` → fat-JAR
from tag → GH release). Branch is at **`3.0.0-SNAPSHOT`**.

## Decisions locked (carried from the MVP + architecture review)

| # | Decision | Choice |
|---|---|---|
| V-1 | Agent topology | Embedded in-JVM via an `AssistAgent` SPI loaded by `SourceService` |
| V-2 | Model provider | Per-env pluggable; **air-gapped = local-only, enforced by packaging** |
| V-3 | First skill slice | `explain-entity` (read-only) |
| V-4 | Semantic layer | New `*_meta.toon` (a `ConfigSpec`-described type) |
| V-5 | Default 7B driver | Qwen2.5-7B-Instruct; 14B for `kpi-to-sql` in prod |
| V-6 | Hosted choice | Pluggable Gemini / Claude / ChatGPT (absent in air-gapped build) |
| V-7 | Assist auth | Separate scoped token tier; no open default |
| V-8 | Hardware profiles | dev-laptop (4GB GPU) · CPU-only (test/CI) · prod (16GB+ GPU) |
| V-9 | Config skills | Draft-only for MVP; CRUD endpoints a fast-follow |
| V-10 | Alerts | Subscribe to existing FAILED events + enrich `BatchEvent`; async hand-off |
| **A-1** | **Keystone** | **Smart Config (`ConfigSpec`) lands before the config-authoring skills** |
| **A-2** | Compatibility | Additive only; `.toon` stays canonical; suite green each step; lean core zero-new-dep |

## Milestones & tasks

Build order is top-to-bottom. Versions are nominal targets. Each milestone ends green +
tagged + GH-released.

### M0 — Foundation: build restructure + security + doc reconciliation → v3.0.0
*The prerequisite milestone — nothing agent-related ships without it.*
- **T0.1** Restructure to a parent POM + `file-processor-core` module; rework the shade so the
  lean fat-JAR stays zero-new-dep. Keep the existing main classes + assembly working.
- **T0.2** Define the `AssistAgent` SPI (`com.gamma.assist.spi`) in core + an injection point on
  `SourceService` registered **before `start()`** (so an agent can subscribe to the bus).
- **T0.3** Security hardening on `ControlApi`: **scoped tokens** (`control` / `assist.read` /
  `assist.write`), constant-time compare, **remove open-by-default** (fail-closed; require a
  token even in dev).
- **T0.4** Reconcile `architecture.md` + `design-notes.md` with the two-stage reality (A0).
- *Exit:* full suite green CPU-only; lean JAR unchanged in deps; new module skeleton builds.

### M1 — Smart Config layer (the keystone) → v3.1.0
*Solves G1–G5/G10; everything AI/UI leans on it.*
- **T1.1** `com.gamma.config.spec`: `ConfigSpec`, `FieldSpec` (type/required/default/enum/
  constraints/uiHint/visibleWhen/description), `CrossFieldRule`. Author specs for pipeline /
  enrichment / job / schema, encoding today's implicit rules (exactly-one-of, engine×skip_tail,
  threads×duckdb_threads, partitions-tabular).
- **T1.2** `com.gamma.config.io`: pluggable `ResourceLoader` (default filesystem), `parse(spec,
  raw)→Config` (pure), `validate(spec,Config)→List<Finding{severity,fieldPath,message}>` (pure).
  Split disk side effects out of `PipelineConfig.load` into an explicit `prepare(Config)`; add a
  public `fromMap`/builder. Give `EnrichmentConfig`/`JobConfig` the same parse/validate split.
- **T1.3** Schema-aware `.toon` serializer (always-quote colons, never `#`, correct inline-vs-
  tabular) + JSON wire form. `.toon` stays canonical + backward-compatible.
- **T1.4** `ConfigRegistry` keyed by stable id (watch/reload); replace the O(n) re-parse scans in
  `SourceService.pathFor/configFor/activeRegistry`. Fix the discovery-suffix vs in-file-identity
  mismatch.
- **T1.5** API: `GET /config/spec/{type}` (UI/AI read the spec) + `/validate` **body** form
  (validate a draft, no file needed) returning structured findings.
- *Exit:* existing `load(path)` delegates to the new pipeline; all 2.x config tests green; the
  spec round-trips and validates the shipped sample configs.

### M2 — Assist platform + first slice `explain-entity` (read-only) → v3.2.0
- **T2.1** `file-processor-agent` module: LangChain4j + Ollama client; `AssistAgent` SPI impl;
  model router with the **provider seam** (Ollama / hosted) + **grammar-constrained output**;
  air-gapped packaging omits hosted SDKs.
- **T2.2** Skill registry (id, schemas, tier, oracle, tools) + the `POST /assist/{intent}` route
  (scoped `assist.read`), assist manifest concept.
- **T2.3** `explain-entity` skill — RAG over `docs/*.md` + Control API reads → `{answer,
  citations,links}`. 7B tier (never 2B for real Q&A).
- **T2.4** Golden-test harness, **runnable CPU-only** (CI). Profile config bundles (V-8).
- *Exit:* zero write surface; agent answers entity questions end-to-end on local Ollama.

### M3 — `nl-to-schedule` (draft-only) → v3.3.0
- **T3.1** Skill: NL → `{cron,on_pipeline,humanReadable,nextRuns[]}` → JobConfig **draft**
  (validated by M1's parse/validate + cron oracle). Gemma 2B for plain; route compositional/
  relative/timezone to 7B.
- **T3.2** Golden tests incl. compositional cases (the oracle catches invalid cron, not
  semantically-wrong-valid cron → assert `humanReadable`/`nextRuns`).
- *Exit:* the cron-builder-widget replacement demo works; draft `.toon` returned for the user to save.

### M4 — `suggest-config` + semantic `*_meta.toon` → v3.4.0
- **T4.1** `*_meta.toon` type (column business descriptions + KPI catalog + domain notes),
  described by a `ConfigSpec`; loader + registry entry; co-located with enrichment configs.
- **T4.2** `suggest-config` skill — sample + partial config → field suggestions w/ rationale;
  validated by M1 loader + a **hard-fail config safety validator** (path jail, numeric bounds,
  output-DB allow-list). 7B. Draft-only.
- *Exit:* config-form replacement demo; safety validator blocks harmful-but-parseable configs.

### M5 — `kpi-to-sql` (the hero) + SQL sandbox → v3.5.0
- **T5.1** Extract `SqlOracle` from `EnrichmentEngine`'s view-registration; run on a
  **locked-down** DuckDB connection (`enable_external_access=false`, `disabled_filesystems`,
  no auto-extensions, `lock_configuration=true`) + **statement allow-list** (single read-only
  `SELECT`) + memory/threads/timeout caps.
- **T5.2** `kpi-to-sql` skill — `{kpiDescription,targetGrain,metadataRefs,domainNotes}` →
  `{sql,logicExplanation,chosenJoinKeys,kpiInterpretation,validated,sampleRows?,
  enrichmentConfigSnippet}`. EXPLAIN/LIMIT-0 validate→repair (cap 2–3). 14B prod / 7B dev /
  hosted-recommended connected. **Confirm-first; surface interpretation + sample rows.**
- *Exit:* KPI-in-domain-terms → validated Stage-2 SQL, on local models as draft + on hosted as
  high-quality; SQL sandbox rejects disallowed statements (tested).

### M6 — `diagnose-and-alert` (event-driven) → v3.6.0
- **T6.1** Enrich `BatchEvent` with error detail (reason/exception/quarantine context); add a
  non-filtering failure subscriber that **hands off to its own executor** immediately.
- **T6.2** `diagnose-and-alert` skill — event → `{severity,rootCause,alertRuleDraft}` (2B
  classify + 7B/hosted root-cause) and NL → alert rule. Alert-rule shape validated.
- *Exit:* proactive failure diagnosis + drafted alerts; ingest thread never blocked.

### M7 (optional) — `report-sql` / `report-narrative` → v3.7.0
NL → report SQL over the audit/status stores (sandboxed-DuckDB validated, 7B) + report-JSON →
prose narrative (2B, strictly extractive).

### Fast-follow — Config write endpoints (CRUD-from-body)
Promote `nl-to-schedule`/`suggest-config` from draft-only (V-9) to one-click-apply: `POST/PUT
/configs` validate-and-persist via `ResourceLoader` + serializer, confirm-first, audited
(suggested-vs-applied).

### Deferred (v3.x later / v3+)
- **BFF + Web UI** module (renders `ConfigSpec` forms, calls assist intents) — the architecture
  is UI-ready (B3); the UI itself is a parallel track.
- LangGraph4j multi-step graphs (provision → watch → roll back).
- `DbStatusStore` connection pool; `MetricRegistry` non-singleton — for the distributed tier.
- Object storage, distributed/multi-node execution.

## Cross-cutting guardrails (every milestone)
- **Suite green CPU-only** before release (CI has no GPU, V-8).
- **Lean core gains zero new deps** (CI-enforced); all AI/hosted deps in `-agent`.
- **Confirm-first, no autonomous apply**; agent holds no write token.
- **Air-gapped = local-only by packaging**; sample rows never sent to a hosted model.
- The non-negotiable security guardrails in [v3-agent-mvp.md](v3-agent-mvp.md#non-negotiable-security-guardrails).

---

**Net sequence:** M0 foundation (build + security + SPI + doc fix) → **M1 Smart Config keystone**
→ M2 assist platform + `explain-entity` → M3 `nl-to-schedule` → M4 `suggest-config` + `*_meta.toon`
→ M5 `kpi-to-sql` + SQL sandbox → M6 `diagnose-and-alert` → (M7 reports) → CRUD fast-follow →
UI/distributed deferred. One minor release per milestone on `3.x`, additive, suite-green, lean
core preserved.
