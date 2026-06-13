---
metadata:
  document_id: 08-DECISIONS
  title: Architecture Decision Log
  last_updated_date: 2026-06-13
  sources_used:
    - inspecto-agent/docs/adr/adr-0001-framework-agnostic-zero-dep-core.md
    - inspecto-agent/docs/adr/adr-0002-own-repo-three-rings-semver.md
    - inspecto-agent/docs/adr/adr-0003-capability-tool-orchestrate-compute.md
    - inspecto-agent/docs/adr/adr-0004-evidence-credibility-tier.md
    - inspecto-agent/docs/adr/adr-0005-confidence-escalation-pluggable-rungs.md
    - inspecto-agent/docs/adr/adr-0006-grounding-guard.md
    - inspecto-agent/docs/adr/adr-0007-java25-floor-github-packages.md
    - inspecto-agent/docs/adr/adr-0008-audit-keys-not-data-plane.md
    - inspecto-agent/docs/adr/adr-0009-sync-orchestrator.md
    - inspecto-agent/docs/adr/README.md
    - docs/design-notes.md
    - docs/api-stability.md
    - docs/v2-plan.md
    - docs/v2-backlog.md
    - docs/v3-architecture.md
    - docs/v3-agent-mvp.md
    - docs/v3-plan.md
    - docs/refactor-blueprint-v4.md
    - docs/assist-agent-improvement-plan.md
    - inspecto-ui/docs/devextreme-migration-plan.md
  open_questions:
    - "K-ADR-04: CredibilityTier final shape (enum+escape-hatch vs app-extensible interface) at the kernel 1.0 freeze is not recorded in these project-side mirrors."
    - "P-08: Console name (Inspector vs Inspecto Console) — proposed, not decided."
  assumptions_made:
    - The 9 ADRs are the agent-kernel's founding+R1 set (immutable once Accepted); the remaining entries are reconstructed from locked-decision tables, design notes, and stability/migration docs across the project.
---

# Architecture Decision Log

Two decision streams: **K-ADR-xx** — the formal `agent-kernel` ADR set (immutable once Accepted;
mirrored from the kernel repo into `inspecto-agent/docs/adr/`); **P-xx** — Inspecto platform/engine
decisions reconstructed from locked-decision tables and design notes.

---

## Part A — agent-kernel ADRs (K-ADR-0001 … 0009, all Accepted)

ADRs 0001–0008 are the founding set (2026-06-04, accepted before the kernel repo existed); 0009 was
added at R1 (2026-06-05). ADRs are immutable once Accepted; supersede via a new ADR.

### K-ADR-0001 — Framework-agnostic, zero-dependency core
- **Decision:** Ring-1 `agent-kernel-core` depends on the **JDK only** (test-scope JUnit excepted);
  all integration points are kernel-owned interfaces (`ModelProvider`, `Retriever`, `AuditSink`,
  `Tool`, `Capability`, `AgentContext`); adapters live in ring-2/ring-3; a **CI guard fails the
  build** on any compile/runtime dep.
- **Rejected:** core depends on `langchain4j-core` (+ logging/JSON) — high coupling, forces an AI
  SDK on the lean ETL host, version-skew risk.
- **Why:** the core must embed identically in three unrelated stacks; zero-dep *is* the value
  proposition. **Consequence:** Inspecto's lean ETL core carries no AI/kernel deps (CI-enforced).

### K-ADR-0002 — Own repo, three rings, independent SemVer
- **Decision:** a separate `agent-kernel` repo (`com.gamma.agentkernel`, independent SemVer); rings
  1 (pure core) / 2 (opt-in companions) / 3 (per-app bindings that never travel); library-level
  reuse via a pinned jar; rule-of-three admission; `0.x`/SNAPSHOT until a 2nd consumer, then `1.0`.
- **Rejected:** keep agent code in the UCC repo and extract "later" (API shaped by one consumer);
  monorepo (couples deploy lifecycles).
- **Note:** its sync-orchestrator-deferral facet is **superseded by K-ADR-0009**.

### K-ADR-0003 — Capability → Tool; LLM orchestrates & narrates, code computes & validates
- **Decision:** two levels — `Capability` (declarative, versioned, dispatched; carries
  tier/threshold/timeout/required-context/allowed-tools, orchestrates + phrases) over `Tool`
  (deterministic, **transport-free** in-process Java, returns `ToolResult` with `Evidence`). The
  LLM picks/narrates, never invents a figure no tool returned; **no MCP/ADK dep in ring-1** (remote
  transport deferred to a future ring-2 adapter).
- **Rejected:** a flat `Skill` mixing model calls + computation; adopting MCP in core now.
- **Consequence:** Inspecto's former flat `Skill`s became `Capability`s.

### K-ADR-0004 — Unified `Evidence` + `CredibilityTier` provenance
- **Decision:** one ring-1 record `Evidence(value, CredibilityTier, tierLabel, sourceRef,
  confidence, observedAt)`; `CredibilityTier` = enum (`AUTHORITATIVE…ASSUMPTION`) **+ a `tierLabel`
  String escape hatch** for `0.x`; reconciliation logic stays app-side.
- **Rejected:** keep a thin `Citation` (no tiers); a fully app-extensible sealed interface from day
  one (premature per rule-of-three).
- **Open:** promotion to an app-extensible interface was to be revisited at the kernel `1.0` freeze.

### K-ADR-0005 — Confidence-driven escalation with pluggable terminal rungs
- **Decision:** ring-1 `EscalationPolicy` runs attempt → `ConfidenceEstimator` → if below the
  capability threshold, walk an ordered sealed `EscalationRung` list (`BumpModelTier`,
  `HumanHandoff`, `Abstain`). Per-app rung lists differ.
- **Rejected:** hard-code tier-bump escalation (unusable for HITL/abstain consumers).
- **Consequence:** **Inspecto wires only `Abstain`** (single-tier; no HITL).

### K-ADR-0006 — `GroundingGuard` (every narrated claim traceable to evidence)
- **Decision:** a deterministic post-hoc `GroundingGuard.check(narration, allowed Evidence)` →
  `Verdict(grounded, ungrounded[])`; a pure function, **no model call**; host decides the
  consequence (suppress/repair/downgrade/abstain).
- **Rejected:** trust the system prompt to forbid unsourced claims (unenforceable, untestable).
- **Consequence:** generalizes Inspecto's `NarrativeGuard`.

### K-ADR-0007 — Java 25 bytecode floor; GitHub Packages + Actions release
- **Decision:** target **Java 25** (`maven.compiler.release=25`, current LTS); every consumer JVM
  ≥25; **UCC moves 24→25** as the first U0 task; publish to GitHub Packages via GitHub Actions
  (build/test on PR, deploy on tag `v*`).
- **Rejected:** floor at Java 17 or 21 (below an actual consumer's baseline for no live reason).
- **Consequence:** this is the source of the project-wide Java 25 floor; any doc saying "Java 24"
  describes the pre-bump state only (Conflict C3).

### K-ADR-0008 — Audit carries keys & summaries only, never data-plane values
- **Decision:** `AgentEvent` variants carry identifiers, counts, durations, tiers, token usage,
  confidence, provenance refs — **never** record contents, `Evidence.value`, or raw prompt/output
  text. Every sink upholds it.
- **Rejected:** full payloads for richer debugging (PII/secrets/cross-tenant leak; compliance
  burden). **Revisit: never.**

### K-ADR-0009 — Synchronous orchestrator in a ring-2 module
- **Decision:** ship `SyncOrchestrator` in a pure ring-2 `agent-orchestration` module (depends only
  on `agent-kernel-core`): resolve `capabilityId` → run via `EscalationPolicy` + `ConfidenceEstimator`
  → audit exactly one keys-only `AgentCompleted`. **No ring-1 change.** Supersedes K-ADR-0002's
  sync-orchestrator deferral (sync case only; async/streaming still deferred).
- **Rejected:** leave it hand-rolled per app; put the orchestrator in ring-1.
- **Consequence:** Inspecto consumes it and deleted its hand-rolled dispatch (behavior-preserving;
  `[ASSIST]` log kept via an `AuditSink` decorator).

---

## Part B — Inspecto platform & engine decisions (P-xx)

### Build & dependency discipline
- **P-01 — Lean, zero-new-dependency fat core.** All AI/hosted/heavy deps live in optional modules
  (`inspecto-agent`, `inspecto-agent-hosted`); CI fails on a new core dep. *Standing invariant.*
- **P-02 — JDK `HttpServer` over a web framework.** The control plane uses the JDK built-in
  `HttpServer` + Jackson. *Rejected:* Javalin (drags Jetty/Kotlin into the fat JAR), Spring.
- **P-03 — Hand-rolled metrics, not Micrometer.** A small `MetricRegistry` + Prometheus text.
  *Rejected:* Micrometer (lean-JAR rationale).
- **P-04 — Hand-rolled `CronExpression`, not Quartz.** *Rejected:* Quartz (decided with the user, to
  keep the fat JAR lean).
- **P-05 — DuckDB as the default status-DB engine.** Already bundled → no new dep; Postgres is a
  bring-your-own-driver path reserved for a future distributed/multi-writer tier. *Revised at v2 M5
  from an earlier Postgres-recommended framing.*
- **P-06 — One minor release per milestone**, additive, suite-green at each step.

### API stability
- **P-07 — `@PublicApi` marker + policy, no `module-info.java`.** Annotation marks the stable
  surface (plugin/embedder/service types). *Rejected:* a JPMS module descriptor over a fat shaded
  JAR with automatic-module deps (high-risk for little gain). `.toon` config + on-disk output are
  stable user-facing contracts (additive, documented changes only).
- **P-08 — Sanctioned breaking changes only at a major.** `FileIngester` removed in 3.11.0 (SPI
  unified on `StreamingFileIngester`, pre-wide-adoption); `AssistResult.confidence` `String`→`double`
  in 4.0. The 2.0 `PipelineConfig` flat→nested-record move (D6) was the one 2.0 break.

### Engine design
- **P-09 — M..N multiplexer with stateless per-record Stage-1; cross-record work is Stage-2.** The
  Stage-1 non-goals (no joins/aggregation/state) are deliberate — they keep batches embarrassingly
  parallel and crash-isolated. *(design-notes "Scope".)*
- **P-10 — Virtual-thread fan-out bounded by a semaphore (D1, v1.5.0)** + per-batch DuckDB
  connection (no pooling — worth it for crash isolation) + `duckdb_threads=0` auto-derive (v3.12.0).
- **P-11 — Durable fsync'd `CommitLog` (D2, 2.0.0)** as the single grep-able "did this batch finish"
  ledger; markers written **last** in commit ordering.
- **P-12 — Behavior-injection seams (D7, v3.9.0):** `OutputFormat`/`TransformCompiler`/
  `BatchIngestStrategy`. *Chosen over* a typed-config-core teardown (would break the `.toon`/embedding
  contract for marginal internal gain).
- **P-13 — Large-file handling (D8/D9/D10):** scratch off `/tmp` (default `dirs.temp`), single-pass
  streaming via lazy `read_csv` views, auto-chunking; a single `StreamingFileIngester` SPI with
  size-routed union/generation modes; DuckDB **Appender** ingest (~75× over JDBC `executeBatch`).
- **P-14 — Batch-processing locked decisions (2026-05-27 spec):** count-matrix lineage (not
  per-record); batch cap by count OR bytes; one schema per batch; rejected member → quarantine, batch
  proceeds; `ura reprocess <batch_id>`; defaults `max_files=1` = zero behaviour change. *Rejected as
  YAGNI:* per-record lineage, mixed-schema batches, automatic orphan-output GC, single-member
  reprocess.

### 3.x redesign (the keystones)
- **P-15 — Smart Config is the keystone (G1–G5/G10).** A declarative `ConfigSpec` consumed by three
  clients (loader, AI, UI); pure parse→validate→prepare; `ResourceLoader` SPI; structured `Finding`s;
  canonical `ConfigCodec`; O(1) `ConfigRegistry` keyed by in-file identity.
- **P-16 — Metadata Graph built *first* (data keystone, M1) despite the config keystone being the
  conceptual cornerstone** — every assist skill grounds on it; built at the user's direction.
- **P-17 — Embedded in-JVM agent via the `AssistAgent` SPI** (loaded by `SourceService` before
  `start()`). *Chosen over* an out-of-process agent — typed access to services, deps isolated.
- **P-18 — Agent proposes; tested endpoints dispose; draft-only where no write endpoint exists
  (V-9).** Validate-before-surfacing with a deterministic oracle + repair loop, but "valid ≠ correct"
  → confirm-first + surfaced interpretation.
- **P-19 — Local-only by packaging, not config (V-2).** The air-gapped artifact omits hosted SDKs;
  sample rows never leave for a hosted model.
- **P-20 — Scoped, fail-closed auth (G7/V-7).** `control`/`assist.read`/`assist.write`,
  constant-time compare, no open-by-default.
- **P-21 — Locked-down SQL oracle (G6/R1, M6):** `SqlGuard` lexical allow-list run **before** any
  `EXPLAIN` (planning can evaluate smuggled functions) + `SqlSandbox` register-then-`seal()`. EXPLAIN
  is a plan check, not a security boundary.
- **P-22 — Hard-fail config safety validator (R6, M5):** path jail / numeric bounds / output
  allow-list; opt-in on `POST /validate`; does not touch the production config-load path.
- **P-23 — Non-blocking failure reactor (V-10, M7):** enrich `BatchEvent` + subscribe + immediately
  hand off to a bounded-queue daemon virtual-thread executor, so AI never throttles ingest. *Chosen
  over* a new event channel (the bus already emits FAILED terminal events).
- **P-24 — Default models (V-5/V-8):** Qwen2.5-7B default, 14B for `kpi-to-sql` in prod; three
  hardware profiles (dev-laptop / cpu-only / production); grammar-constrained JSON mandatory.

### v4.x refactor (generics/consolidation)
- **P-25 — Consolidate cross-cutting mechanics into small generic/functional components**
  (`CsvLedger<T>`, `Csv`, `FileWalker`, `LockingRunner`, `BoundedHistory<T>`, `ParserSpec`), JDK
  functional interfaces only (no Spring/DI), `@PublicApi`/on-disk formats byte-compatible.
  *Deviations:* a monolithic `BatchOrchestrator.run()` was **rejected** (composable statics
  instead); nested-type folds that would sit on `@PublicApi`/SPI surfaces were **dropped**; wholesale
  CLI-tool merges **revised to surgical dedup**.

### Parsing
- **P-26 — Frontend/backend split; reserve the Java plugin for true binary.** Push as many formats
  as possible onto DuckDB-native readers; the shared backend (mapping/partition/lineage/audit) is
  format-agnostic.
- **P-27 — Externalize the delimited grammar (`*.grammar.toon`) + route SQL\*Plus dumps native
  (4.1).** Backward-compatible alias (`grammar` overlaid by inline `csv_settings`). *Refinement:*
  `skip_tail_lines` (footer-line dropping) intentionally **stays on the Java path**; SQL\*Plus
  footers are dropped natively via an `exclude_regex`.

### Rebrand & UI
- **P-28 — Rebrand UCC File Processor → Inspecto in tiers.** Do display-tier + artifact
  names/descriptions; **keep** Maven artifactIds (`file-processor`), Java packages (`com.gamma.*`),
  and (initially) the repo dir, renaming dirs in dedicated later commits. New code uses
  Inspecto-neutral naming (`INSPECTO_*` env with `UCC_*` fallback).
- **P-29 — Operator UI: adopt the gamma-analytics house template (Option C).** Angular Material 21
  + Tailwind + ag-Grid Community + Chart.js + AntV G6, to match the product-line look & feel.
  *Rejected:* Option A (PrimeNG — lowest integration surface but not the product look); Option B
  (Material + ag-Grid + ngx-charts — more theming/layout glue). *Driver:* DevExtreme is commercial;
  every target is MIT/Apache; the grid feature surface used (search/paging/selection) is free in
  ag-Grid Community — no paid tier reintroduced. Like-for-like port (defer redesign).
- **P-30 — Assist settings persistence.** Shipped as `assist-settings.properties` (a pragmatic
  deviation from the `assist.toon` spec idea); API keys referenced by env-var name, never stored.
  A central intent-ID registry was **skipped deliberately** (churn > value).

---

## Supersession & contradiction notes
- K-ADR-0009 supersedes K-ADR-0002's sync-orchestrator-deferral facet (per `adr/README.md`).
- The v3-plan milestone numbering (M1 = Metadata Graph) supersedes the `design_analysis.md` mermaid
  sketch (M1 = Smart Config) — the doc flags this itself.
- The kernel ADRs' `0.x`/"1.0-not-yet-frozen" framing is stale vs the kernel's actual 1.x release
  (Conflict C5).
