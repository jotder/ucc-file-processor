---
metadata:
  document_id: 04-IMPLEMENTATION-STATUS
  title: Implementation Status
  last_updated_date: 2026-06-15
  sources_used:
    - docs/v3-plan.md
    - docs/refactor-blueprint-v4.md
    - docs/assist-agent-improvement-plan.md
    - inspecto-ui/docs/devextreme-migration-plan.md
    - docs/delimited-grammar-design.md
    - docs/parsing-options-reference.md
    - docs/test-coverage.md
    - docs/api-stability.md
    - docs/design-notes.md
    - docs/v2-plan.md
    - docs/v2-roadmap.md
    - inspecto-agent/docs/AGENT_KERNEL_U0_U1_PLAN.md
    - inspecto-agent/docs/AGENT_KERNEL_R1_PLAN.md
    - SESSION_STATUS.local.md
  open_questions:
    - Exact current committed test count (sources report 417 / 436 / 461 / 466 at different commits — see "Test counts" below).
    - Whether the engine-tier ucc_*→inspecto_* rename and the UI design-system pass are committed/pushed (memory marks them uncommitted/unpushed as of 2026-06-13).
  assumptions_made:
    - "Shipped" = the source marks the milestone ✅ done and tagged/released; "In flight" = marked implemented-but-uncommitted in the live handoff or memory.
---

# Implementation Status

Legend: **✅ Built** (shipped/tagged) · **🟡 Partial / in flight** (built but uncommitted, or
partially executed) · **⬜ Not started** (planned/deferred).

## 1. Engine & control plane — ✅ Built

| Capability | Version | Notes |
|---|---|---|
| Stage-1 M..N multiplexer (batch processing, partitioned write, lineage, quarantine, commit log) | 1.x → 2.0 | Batch model + audit CSVs + manifests + reprocess; `CommitLog` fsync ledger (D2, 2.0.0) |
| Nested-record `PipelineConfig` + `@PublicApi` marker | 2.0.0 | The one 2.0 breaking change (D6/M3) |
| Stage-2 enrichment engine + orchestration | 2.1.0–2.3.0 | event/schedule-driven, idempotent, self-chaining |
| Control API (JDK HttpServer) | 2.4.0 | ~30 routes today |
| Observability (Prometheus metrics, structured events) | 2.5.0 | |
| Status DB backend (`DbStatusStore`, DuckDB) | 2.6.0 | Postgres bring-your-own-driver path exists |
| Enrichment run audit over API | 2.7.0 / 2.9.0 | |
| Cron + jobs + reports (`JobService`, `ReportService`) | 2.8.0 | |
| Date-range + percentile report windows | 2.10.0 | p50/p95/p99 |
| Engine-modularity seams (`OutputFormat`/`TransformCompiler`/`BatchIngestStrategy`) (D7) | 3.9.0 | behavior-preserving |
| Large-file handling: scratch off `/tmp`, single-pass streaming, auto-chunking (D8) | 3.10.0 | |
| Streaming plugin SPI for huge custom files (D9) | 3.10.0 | |
| Plugin SPI unified on `StreamingFileIngester` + DuckDB Appender (~75×); size-routed union/generation (D10) | 3.11.0 | **breaking**: removed `FileIngester` |
| Perf pass: `duckdb_threads` auto-derive, parallel inbox scan, multi-member streaming UNION, parallel partition reveal | 3.12.0 | |

## 2. Smart Config & Metadata Graph (the keystones) — ✅ Built

| Milestone | Version | Status |
|---|---|---|
| M1 — Metadata Graph (data keystone): `com.gamma.catalog`, schema description columns, `*_meta.toon`, `/catalog*` | 3.1.0 | ✅ |
| M2 — Smart Config (config keystone): `ConfigSpec`/`FieldSpec`/`CrossFieldRule`/`Finding`, pure `config.io` pipeline, `ConfigCodec`, O(1) `ConfigRegistry`, `GET /config/spec/{type}` + draft `POST /validate` | 3.2.0 | ✅ |

These resolved architecture gaps G1–G5, G8, G10; G4/G6 (unsandboxed SQL) closed at M6.

## 3. Assist agent skill catalog — ✅ Built (all 7 shipped)

| Milestone | Skill(s) | Version |
|---|---|---|
| M3 | Assist platform + `explain-entity` (read-only) | 3.3.0 |
| M4 | `nl-to-schedule` (draft-only) + `RepairLoop` | 3.4.0 |
| M5 | `suggest-config` (draft-only) + config safety validator (R6) | 3.5.0 |
| M6 | `kpi-to-sql` (hero) + locked-down SQL sandbox (`com.gamma.sql`) — closes G4/G6 | 3.6.0 |
| M7 | `diagnose-and-alert` (event-driven) + failure-event seam (`FailureReactor`) | 3.7.0 |
| M8 | `report-sql` + `report-narrative` (operational reporting) | 3.8.0 |

All skills are draft-only / confirm-first; lean core stays 0-AI / 0-new-deps (CI-enforced); golden
tests run CPU-only against a deterministic `FakeModelProvider`.

## 4. v4.0 — agent-kernel migration — ✅ Built (shipped 2026-06-05 as v4.0.0)

- U0 (Java 24→25 bump + golden-eval net) and U1 (depend on the kernel, skills→`Capability`,
  reshape the `com.gamma.assist` SPI) complete; cut as `v4.0.0`.
- Lean core gains **zero** `com.gamma.agentkernel` deps (CI guard); kernel types only in
  `inspecto-agent/`.
- One sanctioned breaking change: `AssistResult.confidence` `String` → `double`.
- The ring-2 `SyncOrchestrator` (`agent-orchestration`) was extracted and is consumed by Inspecto
  (ADR-0009, behavior-preserving).

## 5. v4.1 — control/UI/grammar work — ✅ Built

| Capability | Notes |
|---|---|
| Config-write endpoint (`POST /config/write`) + live pipeline registration (`POST /pipelines`) | gated on `-Dassist.write.root`; fail-closed; jailed paths |
| Per-file in-flight progress (`/pipelines/{name}/pending` `current`) + Files-tab Current-file card | live-only, not persisted |
| `schema_file` pre-flight findings | WARNING at validate/write, ERROR at register |
| Serve operator SPA same-origin (`-Dui.dir`) + dev CORS (`-Dcontrol.cors`) | both off by default |
| Externalized delimited grammar (`processing.grammar` / `*.grammar.toon`) + native SQL\*Plus routing + `encoding`/`compression`/`strict_mode`/`null_strings` pass-through + row filters | `delimited-grammar-design.md` Phases A–D; `skip_tail_lines` intentionally stays on the Java path |

## 6. Assist agent hardening + hosted routing (latest session) — ✅ Built

From `assist-agent-improvement-plan.md` (branch `4.x`, 2026-06-12), "Nothing in this plan remains
open":
- **Workstream R (rebrand):** display-tier + artifact-name rename to **Inspecto** (artifactIds/Java
  packages/repo dir kept).
- **Workstream A (model routing, hosted-first):** `ProviderSettings`, `ModelProviderFactory`, the
  new **`inspecto-agent-hosted`** module (langchain4j Anthropic/OpenAI/Gemini adapters), live
  re-registration, `GET/POST /assist/settings` + `/assist/settings/test`, and the
  `/settings/models` UI screen. Settings persist as `assist-settings.properties` (a pragmatic
  deviation from the `assist.toon` spec idea).
- **Workstream B (hardening):** B1 (shared `SkillInputs`, `AssistTunables`, `TimeoutModelProvider`,
  doc-RAG guard), B2 (`AssistMetrics` audit-sink + `GET /assist/metrics`), B3 (tests), B4 (small
  fixes). **B5** (alert execution engine: `AlertRule`/`AlertService` + `GET /alerts`) shipped in
  the `18d1696`→`30a8d62` alerts arc.

## 7. Operator console (Inspector) — ✅ Built (DevExtreme migration complete)

- The DevExtreme → open-source migration is **complete**: all 6 phases (theming/layout →
  primitives → 17 grids → charts → catalog diagram → teardown) landed; the old DevExtreme app was
  deleted and the gamma-shell app renamed to the canonical UI directory.
- Stack: Angular 21 Material + Tailwind (gamma-analytics template) · ag-Grid Community · Chart.js
  4 · AntV G6 5. Vitest test suite; guarded e2e backend-smoke; single npm CI job; dev serve `:4204`.
- All 9 screens live: dashboard, pipelines, pipeline-detail, jobs, enrichment, catalog, config,
  diagnoses, assist (+ connect).

> Note: the migration-plan document's header still reads "PLANNING ONLY — not scheduled" while its
> body and epilogue record full completion — an internal contradiction (Conflict Report C14). Treat
> the migration as **done**.

## 8. v4.x refactor blueprint (generics/consolidation) — ✅ Built (per the dominant statements)

`refactor-blueprint-v4.md` records **Phases 1–3 implemented (2026-06-11, 417 tests green)** plus a
2026-06-12 post-phase follow-up — i.e. `Csv`, `CsvLedger<T>`, `BoundedHistory<T>`, `FileWalker`,
`LockingRunner`, `ParserSpec` shipped; audit writers consolidated; surgical CLI-tool dedup. Several
planned folds were deliberately **dropped** (types on `@PublicApi`/SPI surfaces) and a monolithic
`BatchOrchestrator.run()` was **rejected** (composable statics instead).

> ⚠️ The same document contains a leftover sentence "Phases 2–3 not started" embedded in a Phase-1
> deviations note — stale and contradicted by the dated "All phases complete" statements
> (Conflict Report C9).

## 9. In flight / uncommitted (per the live handoff + memory) — 🟡 Partial

- **UI design-system pass + code-tier `ucc → inspecto` rename** — `src/app/ucc` → `inspecto`,
  `Ucc*` → `Inspecto*` identifiers, `chart-tokens.ts`, `<inspecto-empty-state>`, the
  `ui-components.md` doc. Memory marks this committed at `d2de37b` (unpushed).
- **Engine-tier `ucc_* → inspecto_*` rename** — metrics/tables/logger renamed with a status-DB
  migration; ~461 Java tests green, live-smoked. Memory marks this **uncommitted**.
- **Directory-tier rebrand** (`file-processor`→`inspecto`, `inspector-ui`→`inspecto-ui`,
  `inspecto-agent`/`-hosted`) — complete in commits `c351143`/`f83d4ec`/`d2de37b`; artifactIds and
  fat-JAR names unchanged. `run-adjustment.bat` deleted as outdated.

## 10. Test counts (snapshots — evolving, not contradictory)

| Source / commit | Count |
|---|---|
| v2 final (`v2-plan.md`) | 185 |
| `refactor-blueprint-v4.md` (Phases 1–3, 2026-06-11) | 417 Java |
| `test-coverage.md` (v3.9.0, 2026-06-01) | 466 (346 core + 120 agent) |
| `v3-plan.md` M8 exit | 436 (332 core + 104 agent) |
| `SESSION_STATUS.local.md` / `assist-agent-improvement-plan.md` (a15c4d4) | 436 Java + 29 Vitest |
| Memory (latest, engine-rename pass, uncommitted) | ~461 Java |

Coverage (v3.9.0): core ETL data-path ~87% line / ~76% branch; assist agent ~86% line; pre-ETL
`util` CLI tools ~5.7% (a conscious, documented tradeoff).

## 11. Not yet built — ⬜

- The broader unified `parsing:` grammar frontends — `fixedwidth`, `json`, `text_regex`, `xml` —
  remain **[PROPOSED]** (only the delimited slice + plugin frontend exist). See Roadmap.
- Config CRUD beyond write/register (full `PUT`/listing route) — partially superseded by the v4.1
  write+register endpoints.
- LangGraph4j multi-step graphs; `DbStatusStore` connection pool; `MetricRegistry` non-singleton;
  object storage; distributed/multi-node — all deferred (Roadmap).
- agent-kernel `1.0` consumer upgrade ("U2") and any kernel-side reshape gated on a 2nd consumer —
  the project consumes 1.0.0; bump to the released 1.1.0 is optional.

## 12. Post-consolidation work (2026-06-14 / 06-15, branch `4.x`) — ✅ Built

Shipped after this snapshot's 2026-06-13 cutoff; on `4.x` (pushed unless noted).

**Operational Intelligence (OI) Platform — managed objects, on the new `com.gamma.ops` engine:**

| Phase | Capability | Notes |
|---|---|---|
| 2 | **Alert Center** — mutable object store (DuckDB) + config-driven workflow engine + `/objects` lifecycle API; fired alerts promoted to managed `ALERT` objects | |
| 3 | **Issue Tracker** — operator-created `ISSUE` lifecycle (`OPEN→…→CLOSED`) + SLA tracking (`dueAt` attribute, idempotent sweep → `OBJECT_SLA_BREACH`) | |
| 4 | **Case Management** — first-class `OBJECT_LINK` graph (`LinkStore`, CONTAINS/CAUSED_BY/…), `CASE` lifecycle, BFS `graph` API; **Evidence** — append-only notes/attachments + `*_rca.toon` RCA templates (apply seeds a comment per section) | own DuckDB files, single-writer |

UI: Cases/Issues list + type-agnostic detail pane (Overview/Graph/Comments/Attachments), create + link
dialogs, G6 graph reuse — all live-verified. Zero new core deps.

**Data Acquisition & File Collection framework (Phases A–F) — ✅ COMPLETE:**

| Phase | Capability |
|---|---|
| A | `SourceConnector` SPI (`com.gamma.acquire`) + `LocalFileSystemConnector` byte-for-byte parity; additive `source:` config |
| B | Readiness/stability gate — never ingest a half-written file (`ready_marker`, size/mtime quiescence) |
| C | Fingerprint ledger + dedup by PATH/METADATA/CHECKSUM + `on_change` policy + `regex:` includes + incremental high-watermark (`source.incremental.watermark`, C4) |
| D | Collection-guarantee knob + sequence-gap detection → `SEQUENCE_GAP` promoted to a managed ALERT object |
| E | First remote connectors **SFTP (sshj) + FTP (commons-net)** in the optional `inspecto-connectors` module; reusable `*_connection.toon` profiles + `/connections` API/UI; integrity check; `.bz2`/`.zip` read path |
| F | Retry/backoff (jitter) + per-source circuit breaker + dead-letter quarantine + source-side post-actions (DELETE/MOVE/RENAME) + parallel multi-session fetch + token-bucket rate limit |

All additive — a `source:`-less pipeline is byte-for-byte unchanged. Network deps isolated in the optional
connector module so the core fat-JAR stays lean. Detail: `docs/data_acquisition_framework.md` (requirement) +
`docs/superpowers/specs/2026-06-14-data-acquisition-framework-roadmap.md` (as-built). Post-roadmap (also shipped):
the **DB-export source** (`connector: db`, SQL→CSV), the **C4 incremental high-watermark**, and **connector
hardening** — **FTPS** (`connector: ftps` / `options.tls`), **strict SSH host-key pinning** (`options.host_key`/
`known_hosts`/`strict_host_key`), and **FTP/FTPS through an SSH bastion** (`tunnel:` + `options.passive_ports`).
Still future-scope: object storage (S3/GCS/Azure), NFS/SMB, and the etag/version watermark dimensions.
