# v3.0 — Embedded Assist Agent: MVP Wish List

> Planning artifact for the first milestone of the 3.x line. Branch: `3.x`.
> Design-only — no code, no version bump yet. Counterpart to [v2-plan.md](v2-plan.md).
> **Status: reviewed.** Three independent review passes (codebase-feasibility,
> offline-model viability, security) have been folded in — see
> [Review-cycle findings](#review-cycle-findings-what-changed) at the end.

## The thesis

The agent is **not a chatbox bolted onto the side**. It is an *ambient assist layer
behind every screen*: small, task-scoped, schema-constrained skills that turn a
sentence (or just the current screen context) into a **validated, ready-to-apply
result**. The product bet:

> **Smarter use of the agent removes UI components and raises usability.**
> A cron-builder widget becomes "every weekday 6am after adjustment_etl".
> A 30-field config form becomes "ingest these CDR files, daily" + accept/edit.
> A SQL editor becomes "revenue per region, last 30 days" → validated SQL.

The hero capability: **describe a business KPI in domain terms + point at the
metadata, and the agent writes the Stage-2 transformation SQL/logic** — validated
against the real engine before the user ever sees it.

## Design principles (non-negotiable for the MVP)

1. **Separate optional module, loaded in-process.** The agent lives in its own module
   (`file-processor-agent`, LangChain4j + Ollama client) so the lean ingest/enrichment
   fat-JAR stays zero-new-dep. When the module is on the classpath *and* the assist layer
   is enabled, `SourceService` loads it **in-JVM** via a small SPI (`ServiceLoader` /
   reflection — precedent: `BatchProcessor` already does `Class.forName(...).newInstance()`
   for ingesters) for typed access — see P1. Module isolation (deps) and in-process
   execution (access) are not in tension: the deps ship only in the agent artifact, and
   the **air-gapped build ships without any hosted-model SDK** (see principle 5).
2. **The agent proposes; tested endpoints dispose.** State-changing suggestions are
   *drafts*. Where a validated Control API write endpoint exists (trigger/pause/resume/
   reprocess/job-trigger) the suggestion carries `applyVia` → that endpoint, applied with
   the **human's** credential, confirm-first. Where no write endpoint exists yet
   (creating/updating pipeline/enrichment/job *configs* — these are loaded from disk at
   startup today, there is **no create-config endpoint**), the skill is **draft-only**:
   it returns a validated `.toon` the user saves. The agent never holds a write token and
   never auto-applies.
3. **Validate before surfacing — but "valid" ≠ "correct".** Generated artifacts pass a
   deterministic oracle (config parser / sandboxed DuckDB) + repair loop before the user
   sees them. This makes the agent **crash-safe and parse-safe** — malformed JSON,
   unparseable configs, and SQL that won't plan become invisible internal retries. It
   does **not** make it *semantically* safe: SQL that EXPLAINs clean can still join on the
   wrong key or double-count; a config that parses can still point at the wrong path. So
   the oracle is paired with **confirm-first + surfacing the model's chosen interpretation**
   (join keys, KPI definition, grain, sample rows) so a human reviews the semantics the
   oracle can't.
4. **Local-first, tiered, environment-pluggable models.** Narrow intents → small fast
   model. Judgement/SQL → 7–14B. The model *provider* is a per-environment deployment
   choice: a connected deployment may select a hosted model (Gemini / Claude / ChatGPT);
   an **on-premise / air-gapped deployment MUST run fully local (Ollama)**. Every skill
   must therefore be satisfiable on a local model — hosted is an optional capability
   upgrade, never a hard dependency.
5. **Local-only is guaranteed by packaging, not config.** The air-gapped artifact ships
   **without** hosted SDKs on the classpath, so the router *cannot construct* a hosted
   provider — the offline guarantee is physical, not a flag someone can flip. **Sample
   data rows never leave the box for a hosted model even in connected mode** — hosted
   calls get schema + metadata + the question, redacted; data-plane content stays local.
6. **Testable like everything else.** Each skill has input + output schemas → golden
   tests and assertions, same discipline as the 2.x suite.

## MVP scope — IN / OUT

**IN (this milestone):**
- The assist *platform*: Assist API, skill/intent registry, model tiering + provider
  seam, the validation oracles (incl. the **locked-down SQL sandbox** — see P4/security),
  a lightweight metadata/semantic descriptor (P5).
- A starter **skill catalog** of 5 skills (below): `explain-entity`, `nl-to-schedule`,
  `suggest-config`, `kpi-to-sql` (hero), `diagnose-and-alert` (incl. event-driven, V-10).
- The new **`BatchEventBus` failure-event seam** + async hand-off (V-10).
- The **build restructure** to a two-module project + agent SPI.
- The **non-negotiable security guardrails** (SQL sandbox, statement allow-list, scoped
  assist token, config safety-validator, audit trail).
- Golden-test harness for skills, **runnable CPU-only** (CI has no GPU, V-8).

**OUT (deferred to later 3.x / v3.x):**
- LangGraph4j multi-step graphs (provision → watch → roll back). MVP skills are
  single-shot generate→validate→return.
- Full Web UI. MVP targets the Assist API; the rich "behind every screen" UI is a
  parallel track.
- Autonomous actions without confirm. Everything write-bearing is confirm-first, draft-only
  where no endpoint exists.
- **Create/update-config write endpoints** (V-9: config skills are **draft-only** for MVP;
  endpoints are a fast-follow that would upgrade `nl-to-schedule`/`suggest-config` to
  one-click apply).
- `report-sql` / `report-narrative` (B2) — optional 6th skill, not required for MVP.
- Fine-tuning / training. Off-the-shelf instruct models + RAG + few-shot + grammar-
  constrained decoding.
- Object storage, distributed/multi-node execution (already v3.0+ deferred by design).

## Decisions locked

| # | Decision | Choice | Rationale |
|---|---|---|---|
| V-1 | Deployment topology | **Embedded in-JVM** (agent loaded in-process by `SourceService` via an SPI) | Typed access to `reports()`, `enrichmentService()`, `jobService()`, `statusStore()`, `eventBus()` (all already exposed on `SourceService`); deps stay isolated in the optional module |
| V-2 | Model provider | **Per-environment pluggable; air-gapped = local-only, enforced by packaging** | Connected sites may use hosted; on-prem/offline runs all-local Ollama; every skill must work on local models |
| V-3 | First vertical slice | **`explain-entity`** (read-only) | Zero write risk, exercises RAG + Control API reads end-to-end |
| V-4 | Semantic layer | **The M1 Metadata Graph (v3.1.0) — ✅ implemented:** schema-`.toon` `description`/`unit`/`classification` columns + `*_meta.toon` (KPI catalog + domain notes) assembled by `MetadataGraphService` into a typed, traversable graph with a lazy operational overlay, served at `/catalog*`; descriptions ranked **manual > AI > deduced** via a `DescriptionProvider` SPI (the **AI** provider lands here at M3) (see [v3-plan.md](v3-plan.md)) | `*_meta.toon` mirrors `*_enrich.toon` co-location + suffix-scan; the schema columns are additive (header-driven `.toon`, invisible to the data path); built core / zero-AI, the agent only adds the AI describer |
| V-5 | Default 7B driver model | **Qwen2.5-7B-Instruct**; **promote to Qwen2.5-14B for `kpi-to-sql`** where hardware allows | Stronger code/SQL + JSON adherence than Llama-3.1-8B; 14B materially better on joins/windows |
| V-6 | Hosted provider choice (connected mode) | **Pluggable: Gemini / Claude / ChatGPT** behind one provider seam | Per-deployment selection; SDK absent from air-gapped artifact (V-2/principle 5) |
| V-7 | Assist API auth | **Separate scoped token tier** (`assist.read` / `assist.write`), distinct from the Control write token; **no open/unauthenticated mode** | Today the Control API has one shared token and runs open-with-a-warning if unset — too coarse and too permissive for an LLM-driven surface |
| V-8 | Hardware targets | **Three profiles: dev-laptop (4GB GPU), CPU-only (testing), production (16GB+ GPU)** — model tier auto-selected per profile | Must run end-to-end on a 4GB laptop GPU and CPU-only for dev/test; full inline experience targets prod 16GB+ |
| V-9 | Config-generating skills | **Draft-only for MVP** (`nl-to-schedule`/`suggest-config` return a validated `.toon` the user saves) | Lower risk, faster, consistent with confirm-first; create/update-config write endpoints are a fast-follow |
| V-10 | Event-driven alerts (C1) | **In MVP: subscribe to existing FAILED events + enrich `BatchEvent` with error detail + async hand-off** | The bus already emits FAILED terminal events (consumers filter them); the seam is enrichment + a non-filtering subscriber, not a new channel |

## Platform pieces (the minimum plumbing)

### P1 — Assist API + in-JVM agent SPI
**The agent runs in-process inside `SourceService`**, loaded via a small SPI when the
optional module is present. `SourceService` already exposes the typed handles the agent
needs — `reports()`, `enrichmentService()`, `jobService()`, `statusStore()`, `eventBus()`
— and `ControlApi` already holds the `service` reference, so injecting the agent is an
added wiring point (registered **before `start()`** so it can subscribe to the bus).
`POST /assist/{intent}` is added to the existing JDK-`HttpServer` route table (a flat
list of `get/post(method, regex, auth, handler)` records — adding a route is one line).
Body `{ screenContext, partialInput, userText? }` → `{ suggestions[], rationale,
confidence, validated, applyVia? }`. Each screen ships a small **assist manifest**
declaring which intents apply and what context it can supply.

### P2 — Skill / intent registry
Each skill is self-contained: `id`, bound screen/context, system prompt + few-shot
examples, **input schema**, **output schema**, allowed tools (read vs write), declared
**model tier**, and its validation oracle. Adding a skill = a registry entry + tests; no
platform change. This is the "lots of prompts/skills" surface the end user benefits from.

### P3 — Model tiering over Ollama, with a provider seam
- **Small/fast tier:** narrative, severity-classify, extract-fields, short summaries.
  Default **Gemma-2-2B**; consider **Qwen2.5-3B** for anything needing structure.
- **7–14B tier:** judgement/SQL/explain. Default **Qwen2.5-7B-Instruct**; **14B for
  `kpi-to-sql`**.
- **Provider seam:** each tier resolves local (Ollama) or hosted (Gemini/Claude/ChatGPT)
  by config; the air-gapped artifact omits hosted SDKs so local-only is physical (V-2).
- **Grammar-constrained / structured output is mandatory** for every skill (Ollama
  `format` + JSON schema) — this is what makes even the 2B tier shape-reliable; without
  it small-model JSON is unreliable.

### P4 — Validation oracles (the safety net)
| Generative task | Oracle |
|---|---|
| Config / job drafts (`.toon`, JobConfig) | `PipelineConfig.load()` / `EnrichmentConfig.load()` / `JobConfig.load()` — they throw specific, machine-usable messages naming the offending key → repair-loop. **Plus** a new hard-fail **config safety validator** (below). |
| **Transformation / report SQL** | A **locked-down, ephemeral DuckDB connection per validation** (not the production one): it registers the relevant Parquet/CSV partitions + references as views — *exactly the `EnrichmentEngine` view-setup pattern* — then `EXPLAIN` / `LIMIT 0`. |

**Correction from review:** there is no resident, schema-loaded DuckDB connection to
"borrow" — DuckDB is used per-operation against ephemeral temp DBs, and the only
long-lived connection (`DbStatusStore`) holds *audit* tables, not data schemas. So the
SQL oracle must **spin up its own connection and register views from the real partitions**
each time (extract a `SqlOracle` helper from `EnrichmentEngine`'s logic).

**The SQL oracle is also the security boundary** and must be sandboxed (see
[Security guardrails](#non-negotiable-security-guardrails)): `EXPLAIN`/`LIMIT 0` validates
the *plan*, it does **not** neutralize `COPY … TO`, `read_csv('/etc/…')`, `ATTACH`,
`INSTALL`/`LOAD`. The sandbox connection sets `enable_external_access=false`,
`disabled_filesystems=…`, `autoinstall/autoload_known_extensions=false`,
`lock_configuration=true`, plus a statement allow-list (single read-only `SELECT` only)
and memory/threads/timeout caps.

### P5 — Metadata / semantic descriptor: a new `*_meta.toon` (locked, V-4)
> **Promoted, expanded & ✅ implemented (see [v3-plan.md](v3-plan.md) M1).** P5 is no longer a
> single late file — it became the **Metadata Graph** data keystone (**M1, v3.1.0**, built ahead of
> Smart Config), in three layers: `description`/`unit`/`classification` columns in the schema `.toon`,
> this **`*_meta.toon`** (KPI catalog + domain notes), and a typed, traversable graph assembled by
> `MetadataGraphService` (with a lazy operational overlay) served at `/catalog*`. The content below
> describes the `*_meta.toon` layer.

A lightweight, additive descriptor co-located with the enrichment config (loaded by the
same suffix-scan), grounding generation for the SQL skills:
- **table/column metadata** — names, types, and a one-line *business description* per
  column. **Net-new:** the existing schema model is `{name, selector, type}` only — there
  is no description field anywhere today, and `SchemaExtractor` is a CLI, not a queryable
  service. P5 builds this (parse `*_schema.toon` / `DESCRIBE` a `read_parquet` view for
  types, + the new description field).
- **KPI catalog** — named KPIs with a natural-language definition, grain, and inputs.
- **domain notes** — units, currency, time-zone, "revenue excludes tax", join keys.
Without it the SQL generator is guessing; with it + the DuckDB oracle it is translating.

### P6 — Build restructure (prerequisite, surfaced by review)
Today: single flat `file-processor/pom.xml` + one shaded fat-JAR. The optional in-JVM
module needs: a **parent POM + two modules**, an **agent SPI interface** in the core
module, **`ServiceLoader`/reflection** load (precedent exists), an **injection point** on
`SourceService`/`ControlApi` (before `start()`), and **shade changes** so the lean JAR
stays dep-free while the agent module's SPI registration survives. This is "restructure
the build," not "add a module."

## The MVP skill catalog (the wish list)

Ordered by build sequence. Each notes the **UI it replaces** — the usability thesis.
Model tiers reflect the viability review (Gemma 2B was over-assigned in the first draft).

### A1 — `explain-entity`  *(read-only, ship first, lowest risk)* — ✅ **shipped v3.3.0 (M3)**
> Realized as `com.gamma.agent.skill.ExplainEntitySkill` (MEDIUM/7B tier) behind the `POST
> /assist/explain-entity` route. Grounds on the M1 catalog + Control API reads + a tiny `docs/*.md`
> retriever; citations are derived from the fed sources (not parsed from the model). Platform pieces
> P1 (Assist API + in-JVM SPI), P2 (skill registry + `ModelProvider`/`ModelRouter`/profile seam +
> grammar-constrained JSON), and the AI `DescriptionProvider` (V-4) shipped with it. Hosted seam
> deferred (M3 is local-only Ollama by packaging). Golden tests run CPU-only via a deterministic fake.
- **Does:** on any entity screen (pipeline, batch, enrichment run, report, error), pull
  the entity via the Control API + relevant docs and explain it / answer "why is this
  slow / what changed".
- **In:** `{ entityType, id, question? }`. **Out:** `{ answer, citations[], links[] }`.
- **Oracle:** none (read-only synthesis). **Model:** **7B** (do **not** route real
  questions to 2B — 2B only for trivial one-line restatements).
- **Replaces:** drilling across multiple report/lineage/audit screens to assemble an answer.

### A2 — `nl-to-schedule`  *(narrow, the clearest "less UI" demo — draft-only)* — ✅ **shipped v3.4.0 (M4)**
> Realized as `com.gamma.agent.skill.NlToScheduleSkill` behind `POST /assist/nl-to-schedule`.
> Introduces the **generate→validate→repair** oracle (a new `RepairLoop`) over the reused core
> `CronExpression` + `JobConfig.fromMap` + `ConfigSpecs.job()`, a deterministic `CronDescriber`, and
> the additive `AssistResult.data` structured payload. Tiered (V-5/V-8): plain→SMALL, compositional/
> relative/timezone→MEDIUM. `on_pipeline` is grounded against real catalog SOURCE nodes (citation
> derived, not parsed). **Draft-only (V-9):** `applyVia` null, no write endpoint — the user saves the
> returned `draftToon`. Golden tests run CPU-only via a deterministic fake.
- **Does:** "every weekday 6am after adjustment_etl" → `{ cron, on_pipeline,
  humanReadable, nextRuns[] }` → a JobConfig draft the user saves.
- **In:** `{ userText, knownPipelines[] }`. **Out:** JobConfig draft + preview.
- **Oracle:** parse the JobConfig + compute next-run times. **Model:** **Gemma 2B for
  plain cases; route compositional/relative/timezone phrasing to 7B** (the cron oracle
  catches *invalid* cron but not *semantically-wrong-but-valid* cron — so golden tests +
  `humanReadable`/`nextRuns[]` confirm are essential).
- **Replaces:** the cron-builder widget entirely.

### A3 — `suggest-config`  *(high value — draft-only)*
- **Does:** given a source sample + partial config + docs, suggest field values with
  rationale; validate via the loader + safety validator.
- **In:** `{ sourceSample, partialConfig, configType }`.
  **Out:** `{ fields:[{name, value, rationale, confidence}], validated, draftToon }`.
- **Oracle:** config loader + **hard-fail safety validator** (path jail, numeric bounds,
  output-DB allow-list) — because a config can *parse* yet be harmful. **Model:** 7B.
  (Caveat: the model can invent plausible-but-wrong values — delimiters, date formats —
  that still parse; confidence is unreliable, so confirm-first.)
- **Replaces:** hunting through configuration.md + filling a long config form by hand.

### B1 — `kpi-to-sql`  *(the hero — Stage-2 transformation logic from a KPI)*
- **Does:** describe a business KPI in domain terms; the agent writes the Stage-2
  enrichment **transformation SQL/logic** (join/aggregate/derive) against the P5 metadata.
- **In:** `{ kpiDescription, targetGrain, metadataRefs[], domainNotes? }`.
- **Out:** `{ sql, logicExplanation, columnsProduced[], chosenJoinKeys[],
  kpiInterpretation, validated, sampleRows?, enrichmentConfigSnippet }`.
- **Oracle:** **sandboxed DuckDB** EXPLAIN/LIMIT-0 against the real partitions; feed the
  verbatim engine error back on failure (cap **2–3 repair rounds**, then fall back to
  best-effort + "needs review").
- **Model:** **Qwen2.5-14B** local where hardware allows; **hosted-recommended in
  connected mode**.
- **Reality (review):** viable as a **confirm-first draft generator, not "trusted SQL".**
  EXPLAIN proves it *runs*, not that it computes the KPI — wrong join key, double-counting,
  COUNT-vs-COUNT-DISTINCT, window/multi-grain errors all pass the oracle. Mitigate by
  forcing the KPI-catalog definition into the prompt and **surfacing `kpiInterpretation`
  + `chosenJoinKeys` + sample rows** so a human checks the semantics. This is the largest
  local→hosted quality gap.
- **Replaces:** hand-writing transformation SQL / a visual transform builder.

### B2 — `report-sql` / `report-narrative`  *(stretch / optional 6th skill)*
- **Does:** NL → report SQL over the audit/status stores (sandboxed-DuckDB validated),
  and/or turn a report JSON into a short plain-language narrative.
- **Oracle:** sandboxed DuckDB for SQL; none for narrative. **Model:** 7B for SQL,
  **2B** for narrative (strictly extractive — narrate given numbers, never *compute*).
- **Replaces:** a report-builder UI / manual reading of dense report JSON.

### C1 — `diagnose-and-alert`  *(in MVP, V-10; introduces event-driven assist + a new seam)*
- **Does:** two modes — (a) **event-driven**: on a failure event → classify severity +
  draft root-cause + suggested alert; (b) NL → alert rule ("warn when error rate > 5%").
- **In/Out:** event or `{ userText }` → `{ severity, rootCause, alertRuleDraft }`.
- **Oracle:** validate the alert-rule shape. **Model:** 2B classify + **7B root-cause
  (hosted-recommended — local gives generic diagnoses)**.
- **New seam (locked, V-10) — lighter than first thought:** re-assessment of the code
  shows `BatchEventBus` **already emits terminal events for FAILED batches too** (every
  consumer just filters `if (!"SUCCESS") return;`). So the agent can subscribe and react
  to failures *today* — the MVP work is (a) **enriching `BatchEvent` with error detail**
  (reason/exception/quarantine context) for useful root-cause, and (b) the agent listener
  **handing off to its own executor immediately** (listeners run synchronously on the
  ingest thread — precedent: `EnrichmentService`/`JobService` already do this). No new
  event channel needed.
- **Replaces:** manual log triage + hand-built alert configuration.

## What's possible: online vs offline capability matrix

The honest per-skill picture (from the viability review). "Offline" = local Ollama only
(air-gapped); "Online uplift" = what a hosted model (Gemini/Claude/ChatGPT) adds.

| Skill | Offline viability (local) | Local tier | Online uplift | Verdict |
|---|---|---|---|---|
| `explain-entity` | **High** (simple), Medium (multi-hop "why") | 7B | Moderate — better multi-hop synthesis + citation discipline | Ship local; read-only bounds risk |
| `nl-to-schedule` | **High** (plain), Medium (compositional) | 2B→7B | Low–moderate — nails relative/timezone phrasing | Ship local; route hard phrasing to 7B |
| `suggest-config` | **Medium-High** | 7B | Moderate — better format/delimiter inference, calibration | Ship local; confirm-first |
| `kpi-to-sql` *(hero)* | **Medium** (draft + confirm) | 14B | **High** — largest gap; far better joins/windows/multi-grain | Local = best-effort draft; **hosted-recommended** connected |
| `report-sql` | **Medium-High** | 7B | Moderate — complex analytical/window queries | Ship local |
| `report-narrative` | **High** | 2B | Negligible | Local is fine |
| `diagnose-and-alert` | classify **Medium**, root-cause **Low-Medium** | 2B + 7B | **High** for root-cause | Classify+rule local; **root-cause hosted-recommended** |

**Bottom line:** the offline story is **real and shippable** — narrow/read-only/narrative
skills are solidly local, and `kpi-to-sql` is viable offline *because the DuckDB oracle +
P5 semantic layer turn guessing into constrained translation*. The two reasoning-heavy
skills (`kpi-to-sql`, root-cause) are where local visibly trails hosted → label them
**hosted-recommended, local-best-effort**. The validate-loop guards *structure*, not
*semantics*, so confirm-first is mandatory in **both** modes (a hosted wrong join is still
wrong).

### Hardware floor (air-gapped, to be *usable* not just runnable)
- **2B tier:** ~2GB — runs on CPU or any small GPU; comfortably inline.
- **7B (Qwen2.5-7B Q4):** ~5–6GB weights. **Floor 8GB VRAM** (12–16GB comfortable). CPU-only
  works but ~10–30s latency → fine for "author this" skills, too slow for inline.
- **14B (kpi-to-sql, Q4):** ~9–10GB weights. **Floor 12GB VRAM** (16GB comfortable);
  CPU-only impractical for interactive use.
- **The "ambient/inline behind every screen" promise effectively requires a GPU** on
  air-gapped nodes. CPU-only deployments: inline-feel limited to 2B skills; `kpi-to-sql`/
  root-cause become "expect seconds-to-minutes" (each repair round is another full
  inference). Recommended air-gapped baseline for the full experience: one **16GB-VRAM
  GPU** per assist-enabled node (14B + 2B side by side).

### Deployment profiles (locked, V-8) — the agent auto-selects tiers per profile
| Profile | Hardware | Tier mapping | Experience |
|---|---|---|---|
| **dev-laptop** | ~4GB GPU | 2B on GPU; 7B **offloaded to CPU/partial** (4GB can't hold a 7B Q4, ~5–6GB); skip 14B | Inline 2B skills snappy; `kpi-to-sql` on CPU-spilled 7B = slow but functional for dev |
| **cpu-only** *(testing — must work)* | no GPU | 2B (CPU) for inline; 7B (CPU) for SQL/explain as explicit async "author this" | Correctness-complete for the test suite; latency intentionally not a goal here |
| **production** | 16GB+ GPU | 2B + Qwen2.5-7B; **14B for `kpi-to-sql`** | Full ambient inline experience |

Implications baked into the design: (a) every skill must pass its golden tests **CPU-only**
(so CI runs the suite without a GPU); (b) the tier→model→provider resolution is config so a
profile is a config bundle, not a code path; (c) `kpi-to-sql`'s 14B is a *production*
upgrade — dev/CPU fall back to 7B and lean harder on confirm-first + the repair loop.

## How each skill reduces a UI component (the usability thesis)

| Old UI component | Replaced by | Skill |
|---|---|---|
| Cron-builder widget | one sentence → validated schedule | `nl-to-schedule` |
| Long config form + docs hunt | sample → pre-filled fields + rationale | `suggest-config` |
| SQL editor / transform builder | KPI in business terms → validated SQL | `kpi-to-sql` |
| Report-builder / raw JSON reading | NL query → report, or auto-narrative | `report-sql` |
| Multi-screen drill-down to diagnose | ask in place → synthesized answer | `explain-entity` |
| Manual log triage + alert config | event → drafted diagnosis + alert rule | `diagnose-and-alert` |

The pattern: **the screen keeps the data view; the agent absorbs the *authoring/diagnostic*
controls.** Less form, more intent.

## Non-negotiable security guardrails

From the security review — these are *enforced controls*, not aspirations. Three are
load-bearing (R1/R3/R4):

1. **Locked-down sandbox DuckDB connection for all agent SQL** — separate from production:
   `enable_external_access=false`, `disabled_filesystems`, `autoinstall/autoload_known_
   extensions=false`, `lock_configuration=true`, memory/threads/timeout caps. EXPLAIN is a
   *plan* check, **not** a security boundary. *(R1, R8)*
2. **SQL statement allow-list** — single read-only `SELECT` only; reject DDL/DML/`COPY`/
   `ATTACH`/`INSTALL`/`LOAD`/`PRAGMA`/`SET`/multi-statement/comment-smuggling. *(R1)*
3. **All ingested data is untrusted, never instructions** — structural prompt/data
   separation; the deterministic oracle + allow-list is the real prompt-injection backstop,
   not the prompt wording. *(R2)*
4. **Fail-closed egress by packaging** — air-gapped artifact ships without hosted SDKs;
   **sample rows never leave for a hosted model**; hosted requires explicit per-skill
   opt-in + an egress audit log; redact logs/exceptions before any hosted call. *(R3, R7)*
5. **Separate scoped assist token** (`assist.read`/`assist.write`), distinct from the
   Control write token; **no open/unauthenticated mode**; fix the underlying token compare
   to constant-time. *(R4)*
6. **No autonomous apply, ever (MVP)** — agent holds no write token; apply uses the
   human's credential through the existing endpoint; confirm UI shows the **exact** SQL/
   config diff and what it touches (tables, output DB, paths). *(R5)*
7. **Hard-fail config safety validator** — path jail (poll/output dirs under allowed
   roots; reject absolute escapes, `..`, UNC, symlinks), numeric bounds (threads, batch
   caps), output-DB allow-list. Advisory `ConfigValidator` warnings become **blocking** for
   agent drafts. *(R6)*
8. **Full audit trail** — distinguish agent-*suggested* from human-*applied*, with model/
   tier, input-context hash, oracle result, approver, applied diff. *(R5)*
9. **Dependency isolation enforced in CI** — the lean ETL JAR gains **zero** new deps; the
   agent module is CVE-scanned with an SBOM; vet/disable any LangChain4j/SDK telemetry. *(R7)*

## Testability plan

- **Per-skill golden tests:** fixed `(context) → expected structured output` with
  assertions (cron correctness incl. compositional cases, config parses + passes safety
  validator, SQL plans clean in the sandbox, fields present).
- **Oracle tests:** invalid generations must be caught/repaired, not surfaced; disallowed
  SQL (COPY/ATTACH/file-read) must be rejected by the allow-list.
- **Egress tests:** in local-only packaging, constructing a hosted provider must fail;
  sample rows must never appear in a hosted-bound payload.
- **Model-tier swap tests:** a skill must pass its assertions on its declared tier;
  re-tiering is a config change validated by re-running its golden set.
- Mirrors the 2.x discipline: every milestone green before release.

## Phasing within the MVP

0. **P6 build restructure + P1 SPI** — parent POM, two modules, agent SPI + injection
   point, shade rework, scoped assist token (V-7). *(Prerequisite — nothing ships without it.)*
1. **Platform + A1 (first vertical slice)** — ✅ **shipped v3.3.0 (M3).** Assist API, skill registry,
   Ollama wiring with the `ModelProvider` provider seam + grammar-constrained output, the read-only
   path; shipped **`explain-entity`** + the AI `DescriptionProvider`. No state-change surface.
2. **A2** — ✅ **shipped v3.4.0 (M4).** `nl-to-schedule` (draft-only); introduced the config-parser/
   cron oracle + the generate→validate→repair `RepairLoop` + the `applyVia`/draft distinction (the
   hard-fail config *safety* validator — path jail/numeric bounds/output-DB allow-list — lands with
   A3/`suggest-config`).
3. **A3 + P5** — `suggest-config` + the `*_meta.toon` semantic descriptor + config safety
   validator.
4. **B1** — `kpi-to-sql`, the hero, on the **sandboxed** DuckDB oracle + P5 (14B in prod /
   7B on dev+CPU / hosted-recommended connected).
5. **C1 + failure-event seam** — add the `BatchEventBus` error-event publication + async
   hand-off, then `diagnose-and-alert` (event-driven + NL alert rules). *(In MVP, V-10.)*
6. **B2 (optional)** — `report-sql` + `report-narrative` if time allows.

## Review-cycle findings (what changed)

Three independent reviews materially sharpened this plan:

**Codebase-feasibility review — corrected load-bearing assumptions:**
- No borrowable resident DuckDB connection holding data schemas → the SQL oracle must spin
  up an **ephemeral, view-registering connection** per validation (the `EnrichmentEngine`
  pattern). *(P4 rewritten.)*
- No `description`/business-metadata field exists in the schema model; `SchemaExtractor` is
  a CLI, not a service → **P5 is net-new**, not "sourced from existing." *(P5 rewritten.)*
- No create/update-config write endpoints exist → `nl-to-schedule`/`suggest-config` are
  **draft-only** today. *(Principle 2 + skills updated; config-write endpoints listed OUT/
  fast-follow.)*
- `BatchEventBus` carries only commit events (no error stream) and runs listeners on the
  ingest thread → C1 needs a **new failure-event seam + async hand-off**. *(C1 updated.)*
- Single flat pom + single fat-JAR → the in-JVM module is a **build restructure**. *(New P6.)*
- Confirmed sound: ControlApi route table is cleanly extensible; SourceService exposes the
  needed typed services; config loaders throw usable errors; the Stage-2 SQL execution
  model is exactly what `kpi-to-sql` targets.

**Offline-model viability review:**
- Gemma 2B was **over-assigned** → `explain-entity` real Q&A and compositional
  `nl-to-schedule` moved to 7B; **Qwen2.5** chosen across tiers; **14B** for `kpi-to-sql`.
- **Grammar-constrained output is mandatory** (makes 2B shape-reliable).
- Validate-loop guards **structure, not semantics** → principle 3 reworded; confirm-first +
  surfaced interpretation added to `kpi-to-sql`.
- Hardware floor + the "inline ≈ needs a GPU on air-gapped nodes" caveat documented.
- `kpi-to-sql` and root-cause labeled **hosted-recommended, local-best-effort**.

**Security review — promoted three doc *principles* into enforced controls:**
- The DuckDB oracle is currently a plain JDBC connection with full file/extension access →
  **sandbox + statement allow-list** (R1) is now non-negotiable.
- "Air-gapped = local-only" must be **packaging-enforced**, sample rows never sent hosted
  (R3).
- Auth needs a **scoped assist token tier** and must stop defaulting to open (R4). Plus the
  config safety-validator, audit trail, and CI dep-isolation.

## Open questions — none remaining

All planning decisions are resolved (V-1 … V-10). The last three (GPU/CPU profiles,
config-apply, failure-event seam) are locked above. The plan is ready to convert into a
sequenced build plan (a `v3-plan.md`, mirroring `v2-plan.md`) when you want to start.

Implementation-time choices that don't need deciding now (sensible defaults assumed):
exact SPI shape, JSON-schema library for structured output, and the alert-rule storage
format — these fall out of the build and don't change scope.

---

**Net MVP:** an optional, in-JVM `file-processor-agent` module exposing a scoped **Assist
API** backed by a **skill registry** over **Ollama (Gemma-2-2B + Qwen2.5-7B/14B, tiered)**
with a pluggable hosted seam (**Gemini/Claude/ChatGPT**, absent in air-gapped builds), a
**sandboxed** DuckDB SQL oracle + config parser/safety-validator, and a `*_meta.toon`
**semantic layer** — shipping **5 starter skills** (`explain-entity`, `nl-to-schedule`,
`suggest-config`, `kpi-to-sql`, `diagnose-and-alert` incl. the new failure-event seam),
with `report-sql`/`report-narrative` as an optional 6th. The hero is **KPI-in-domain-terms
→ validated Stage-2 transformation SQL**, confirm-first. Runs across three hardware
profiles (4GB dev laptop / CPU-only test / 16GB+ prod). Offline is real for the
narrow/read-only skills and viable-as-draft for the SQL skills; hosted is a quality upgrade
for the two reasoning-heavy skills, never a dependency. Everything proposes into validated
paths; nothing autonomous; everything testable.
