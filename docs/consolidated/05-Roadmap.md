---
metadata:
  document_id: 05-ROADMAP
  title: Roadmap
  last_updated_date: 2026-06-13
  sources_used:
    - docs/v3-plan.md
    - docs/v3-agent-mvp.md
    - docs/v2-roadmap.md
    - docs/v2-plan.md
    - docs/v2-backlog.md
    - docs/refactor-blueprint-v4.md
    - docs/parsing-options-reference.md
    - docs/delimited-grammar-design.md
    - inspecto-agent/docs/AGENT_KERNEL_R1_PLAN.md
    - inspecto-agent/docs/assist-agent-improvement-plan.md
    - inspecto-ui/docs/devextreme-migration-plan.md
  open_questions:
    - No calendar-dated future milestones exist in the sources. Deferred items have no committed timeline — estimates below are the only ones the sources provide (and are marked as such).
  assumptions_made:
    - Items the sources tagged "deferred", "fast-follow", "OUT", "[PROPOSED]", or "trigger-gated" are future work; everything marked ✅/shipped is moved to Implementation Status, not here.
---

# Roadmap

> **Cadence convention (established and still in force).** One **minor** release per milestone on
> the active branch: feature → release commit → annotated tag → next `-SNAPSHOT` → fat-JAR from
> tag → GitHub release. This held across the entire 2.x and 3.x lines.
>
> **Timeline note.** The source documents contain **no calendar-dated future milestones.** Future
> items are sequenced and (where the sources give them) effort-estimated, but not date-committed.

## 1. Release history (context for what's next)

| Line | Outcome |
|---|---|
| **1.x** | Core multiplexer, parallel/multi-source execution, README→docs split |
| **2.0 → 2.10** | Two-stage platform: nested config + `@PublicApi`, Stage-2 enrichment, Control API, observability, status DB, jobs/reports, date-range/percentile reports — *"v2.x backlog fully delivered"* |
| **3.0 → 3.8** | M0 foundation → M1 Metadata Graph → M2 Smart Config → M3–M8 the 7-skill assist catalog |
| **3.9 → 3.12** | Engine modularity (D7), large-file handling (D8/D9), plugin-SPI unification (D10), perf pass |
| **4.0** | agent-kernel migration (Java 25 floor; assist on the shared kernel) |
| **4.1** | Config-write + live registration endpoints, in-flight progress, same-origin UI serving, externalized delimited grammar + native SQL\*Plus routing; DevExtreme→open-source UI migration |

The next release line is **4.x continuing** (repo at `4.1.0-SNAPSHOT`).

## 2. Near-term / fast-follow

| Item | Source | Status / note |
|---|---|---|
| **Config CRUD-from-body completion** | v3-plan "Fast-follow"; v3-agent-mvp OUT | Promote draft-only skills to one-click apply. **Largely delivered** in v4.1 (`POST /config/write` + `POST /pipelines` register); a full `PUT`/listing route is the remaining slice. |
| **Surface the safe `[NATIVE]` read_csv knobs on the CSV path** | parsing-options-reference §8 step 1 | `quote`/`escape`/`comment`/`encoding`/`compression`/`null_strings`/`strict` — **substantially delivered** for the delimited grammar in v4.1 (`delimited-grammar-design.md`); reconcile the two docs (Conflict C10). |
| **agent-kernel 1.0.0 → 1.1.0 bump ("U2")** | R1 plan; SESSION_STATUS | Optional — no behaviour change for Inspecto (Abstain-only escalation). |
| **Jail `processing.duckdb.temp_directory` in `ConfigSafetyValidator`** | design-notes D8 follow-up | Operator-trust today; defaults to the already-jailed `dirs.temp`. |

## 3. Unified `parsing:` grammar — the main planned feature arc

A single `parsing:` block selecting a **frontend**, generalizing today's `csv_settings`/plugin
config. The delimited and plugin frontends exist; the rest are **[PROPOSED]**, each a thin frontend
producing VARCHAR rows for the shared backend (`parsing-options-reference.md` §5/§8):

1. **`fixedwidth`** frontend — compile `fields[]` offsets to `read_text` + `substring` (text) and
   a byte-slicer (binary). No new dependency.
2. **`json`** frontend — wrap `read_json`/`read_ndjson`; lean on `EXPR` mapping rules for nesting.
3. **`text_regex`** frontend — `read_text` + `string_split` + `regexp_extract` with named groups;
   covers LDIF and flat XML (caveats: line folding, base64 → escalate to a plugin).
4. **Adopt the unified `parsing:` block** — make `csv_settings` an alias for `parsing.delimited`
   and `processing.ingester`/`segments` an alias for `parsing.plugin`, so existing toons keep
   working.

Nested XML and ASN.1/BER-TLV/proprietary binary stay on the **plugin** frontend by design.

## 4. Deferred (later 4.x / beyond) — explicitly out of near-term scope

| Item | Source | Rationale for deferral |
|---|---|---|
| **BFF + richer Web UI** beyond the operator console | v3-plan; v3-agent-mvp OUT | Architecture is UI-ready (config specs, structured validation, scoped auth); the rich "AI behind every screen" inline UX is a parallel track and effectively needs a GPU on air-gapped nodes |
| **LangGraph4j multi-step agent graphs** (provision → watch → roll back) | v3-plan; v3-agent-mvp OUT | MVP skills are single-shot generate→validate→return |
| **`DbStatusStore` connection pool** | v3-plan; v3-architecture | For the distributed/multi-writer tier only |
| **`MetricRegistry` non-singleton** | v3-plan; v3-architecture G9 | Only if multi-tenant |
| **Object storage (S3/GCS/Azure)** | v2-backlog; v3-agent-mvp | DuckDB speaks it natively, but deferred |
| **Distributed / multi-node execution** | v2-backlog; v3-agent-mvp | Against the single-JVM, crash-isolated ethos; deliberately deferred |
| **Fine-tuning / model training** | v3-agent-mvp OUT | Off-the-shelf instruct models + RAG + grammar-constrained decoding instead |
| **Cross-member / cross-generation / cross-chunk parallelism; Stage-2 streaming** | design-notes D8/D9/D10 follow-ups | Sequential today; each unit already internally multi-threaded |

## 5. agent-kernel library trajectory (separate repo, affects Inspecto as a consumer)

From `AGENT_KERNEL_R1_PLAN.md` — demand-driven by a 2nd consumer (CVVE/CxO), governed by a
rule-of-three:
- Ring-2 companion modules (`agent-kernel-spring`, `agent-store-postgres`, `agent-hitl`,
  `agent-provider-langchain4j`) and async/streaming orchestrator variants — **trigger-gated**.
- A ring-1 reshape pass (incl. resolving `CredibilityTier` to an app-extensible form per ADR-0004)
  and the **`1.0` freeze** — gated on a 2nd consumer green + the API stopping moving.

> ⚠️ These kernel planning docs describe a `0.x`/SNAPSHOT world; project memory indicates the
> kernel has since released **1.1.0** and Inspecto consumes **1.0.0** — i.e. the freeze has
> happened and the R1 plan is stale on this axis (Conflict C5). The forward item for Inspecto is
> the optional **U2** consumer bump (§2).

## 6. UI evolution

- **Angular 22 bump** — Angular 22 is already GA; Material/ag-Grid ship 22-ready lines. Sequence the
  template's `@angular/*` deps with any bump (`devextreme-migration-plan.md` risk register).
- **Drop the DevExtreme-era `vitest.config.ts` `deps.inline` workaround** — done in teardown; any
  residual cleanup is opportunistic.
- The migration plan's effort model (now historical) put the full DevExtreme port at **~2–3 weeks /
  1 engineer**, dominated by grids.

## 7. Closed / superseded plan items (no longer roadmap)

- The **embedded AI operator agent** and **operator Web UI**, listed as "deferred to v3.0+" in the
  v2 backlog, have since **shipped** (assist M3–M8; the Inspector console). Those v2 "Next major"
  wish-list entries are obsolete.
- The **alert execution engine** (`diagnose-and-alert` drafts → armed rules), deferred as a separate
  arc in the assist-improvement plan (B5), has **shipped** (`AlertRule`/`AlertService` + `GET
  /alerts`).
