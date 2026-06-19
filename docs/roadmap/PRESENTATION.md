# Inspecto — Stakeholder Presentation (slide content)

*Draft deck content — ~18 slides. Each slide gives a title, on-slide bullets, and speaker notes. Render to PPTX/Google Slides as-is; one `##` heading = one slide.*

**Companion:** [STAKEHOLDER_OVERVIEW.md](STAKEHOLDER_OVERVIEW.md) (full doc) · [ROADMAP.md](ROADMAP.md) (forward plan)
**Suggested length:** 25–30 min + Q&A · **Audience:** mixed exec + technical

---

## Slide 1 — Title

**Inspecto**
*A lean, configuration-driven data ingestion, processing & operations platform*

- Platform overview & forward strategy
- [Presenter] · [Date]

**Speaker notes:** Set the frame: this is where the product is, what it does today, and where it's going. We'll spend the first third on the business case, the middle on what's built, and the last third on the roadmap and the decisions we need from this group.

---

## Slide 2 — The problem

**Every data feed is a project. It shouldn't be.**

- New feed = bespoke parsing code + orchestration + monitoring → cost never falls
- Heavyweight clusters for lightweight "collect → normalize → partition → query" jobs
- Operations is an afterthought — late/malformed/dropped files found downstream; no lineage
- Secure collection (SFTP through a bastion, dedup, gap detection) rebuilt every time
- Authoring is expert-only (config files, cron, SQL)

**Speaker notes:** These five pains are universal for any org running on data feeds — telecom CDRs, financial extracts, partner files, regulatory submissions. The marginal cost of the *next* feed is the real problem. Inspecto attacks all five.

---

## Slide 3 — What Inspecto is

**One config file. One lean process. Operable by design.**

- **Configuration over code** — a feed is a declarative file; binary formats plug in via an SPI, not a fork
- **Self-contained** — the whole engine (incl. its analytical database) is one ~90 MB artifact, zero external runtime services
- **Crash-isolated & idempotent** — interrupted runs are safe to re-process
- **Observable & operable first** — metrics, events, audit, and managed cases built in
- **AI proposes; tested endpoints dispose** — every AI change is a validated, confirm-first draft

**Speaker notes:** The one-liner: onboard arbitrary feeds with a single config, transform & partition them deterministically, and make the result queryable, observable, and operable — from one artifact. The differentiator vs. a big data stack is *leanness with operability*.

---

## Slide 4 — How it works: the M..N multiplexer

**Ingest M files → light transforms → N partitioned outputs**

`discover & collect → parse → transform → partition & write → commit`

- Output partition count is **decoupled** from input file count (the "M..N" property)
- Each batch is isolated & parallel, with markers-last commit + fsync'd log
- Parsing frontends: **delimited grammar**, **fixed-width**, **plugin** (binary/proprietary)
- Stage-2: SQL **joins & aggregations** over the partitioned output (rollups, cross-feed joins)

**Speaker notes:** This is the core engine. The crash-safety story matters to ops buyers: a crash mid-run never leaves a half-visible state. Stage-2 reuses the same scheduling/audit/commit machinery.

---

## Slide 5 — Architecture at a glance

**Framework-free engine + embedded analytical DB + optional layers**

- **Core engine** — Stage-1/2, control plane, scheduler, metrics, audit
- **Connectors** module — SFTP/FTP/FTPS/DB-export (network deps kept out of the core)
- **AI assist** + **hosted-model** modules (omitted from air-gapped builds)
- **Operator web console** (Angular) — served from the engine process
- Everything above Stage-1 is **optional and additive**; SPI seams everywhere

**Speaker notes:** Two deliberate choices: (1) no web framework → small dependency tree → small CVE/attestation surface (a compliance asset); (2) embedded columnar DB instead of a cluster → cluster-class SQL on one node at a fraction of the ops cost. Capability is added by *assembling a module*, not editing the core.

---

## Slide 6 — Capability map (what's built)

**A layered platform, not a single tool**

- **Ingestion & parsing** — delimited / fixed-width / plugin; compression; schemas & rejects
- **Data Acquisition framework** — SFTP/FTP/FTPS/DB-export, dedup, gaps, resilience
- **Control plane** — ~30 REST routes, scheduler, metrics, 3-layer audit
- **Operational Intelligence** — events → alerts → managed issues/cases + RCA
- **Flow-graph platform** — author branching pipelines as graphs
- **AI assist** — 7 skills; natural-language authoring
- **Operator console** — all operational panes live

**Speaker notes:** This is the "we've been busy" slide. Most of this is shipped and hardened across multiple release lines. The newest items (flow-graph, provenance) are in mainline. Next four slides go one level deeper on the differentiators.

---

## Slide 7 — Data Acquisition & File Collection

**Getting files to the engine, reliably and securely**

- Connectors: **SFTP, FTP/FTPS, DB-export**, local — on one SPI
- Secure: **FTPS/TLS**, SSH **host-key pinning**, traversal **through a bastion**
- **Readiness gate** (no partial uploads), reusable **connection profiles** (masked secrets)
- **Fingerprint dedup** (path/metadata/checksum) + **incremental high-watermark**
- **Sequence-gap detection** → managed alert; retry/circuit-breaker/dead-letter/post-actions/rate-limit

**Speaker notes:** This is plumbing every team rebuilds — and we've made it declarative and reusable. The gap-detection-to-managed-case flow is a standout: a missing file in a numbered sequence becomes assignable work, not a silent loss.

---

## Slide 8 — Flow-graph platform

**Author pipelines as branching graphs (NiFi-style)**

- Nodes: source → parse → transform (**filter / validate / route / dedup / split / map / merge**) → sink
- Expresses branching the single-SELECT path can't — *route EMEA/APAC, quarantine rejects, dedup before rollup*
- Flows are **first-class jobs** (cron / on-commit / manual / chained), reported & audited
- **Validated before running** (DAG + node-output contracts) → broken graphs rejected at author time
- Sinks: persistent · materialized (incremental rollup) · logical **view** (queryable over REST)

**Speaker notes:** This is the biggest recent expansion of the engine. It turns Inspecto from a linear multiplexer into a general pipeline authoring tool — while keeping the same job/scheduling/audit fabric. The visual editor for this is shipped.

---

## Slide 9 — Data-plane provenance (lineage)

**See exactly how many records flow along every edge**

- Per-**(node, relationship)** record counts captured for a flow run
- **Conservation invariant** — records-in = records-out at non-amplifying nodes → silent loss/amplification raises a managed alert
- Console paints counts onto the flow graph as a **weighted Sankey** for a chosen run
- **Off by default** — zero overhead until enabled

**Speaker notes:** This is the "trust" feature. Lineage and conservation checks usually require custom instrumentation; here they're a default-capable operational guarantee. It directly answers "which input produced this output, and how many records survived each step?"

---

## Slide 10 — Operational Intelligence

**From raw signal to managed, assignable work**

- **Event engine** — structured stream; search, saved views, CSV export, live tail
- **Alert rules** → managed **alert** objects
- **Issue & case management** — lifecycle state machines, SLAs, comments, attachments
- First-class **object-link graph** (contains / escalated-from / caused-by / related-to) + **RCA templates**
- **Correlation id** links the event, the run, and the case

**Speaker notes:** This is what makes Inspecto an *operations* platform, not just an ETL engine. When something goes wrong, it becomes a tracked, assignable, root-caused case — with the originating event and run linked.

---

## Slide 11 — AI assist (optional)

**Lower the authoring barrier; never the safety bar**

- **7 shipped skills** — config, schedule, SQL, diagnose — all **draft-only / confirm-first**
- "ingest these CDR files daily" → validated config draft; "every weekday 6am after adjustment_etl" → schedule; "revenue per region, last 30 days" → validated SQL
- Generated SQL validated in a **locked-down sandbox**
- **Local model (Ollama)** *or* hosted providers (Anthropic/OpenAI/Gemini); hosted SDKs **physically absent** from air-gapped builds

**Speaker notes:** The product bet: a smarter embedded agent removes UI complexity and raises usability. The safety model is the headline — the agent proposes, tested endpoints dispose, and air-gapped builds can't even reach a cloud.

---

## Slide 12 — Operator console

**One web console for the whole platform**

- Dashboard (recent activity + acquisition summary)
- **Flows** (graph view, combined cross-flow view, visual editor + provenance overlay)
- **Sources/Acquisition** (metrics + connections CRUD)
- **Events**, **Jobs & Schedules** (+ run reporting), **Cases/Issues** (detail, links, RCA)
- Shared design system; accessibility-remediated; served from the engine process

**Speaker notes:** No separate UI deployment — it ships from the same process. Everything an operator needs is here; experts can still drop to config/SQL.

---

## Slide 13 — Editions: build flavors, never forks

**One codebase → Personal / Standard / Enterprise**

| | Personal | Standard | Enterprise (future) |
|---|---|---|---|
| Auth | none | external IAM (OIDC) + RBAC/ABAC | + per-tenant |
| Transport | localhost HTTP | HTTPS | TLS at gateway |
| State | local disk | disk / optional Postgres | shared backends |
| Scheduler | in-JVM | in-JVM | distributed |
| Compliance | — | SOC2/ISO/FedRAMP/HIPAA/PCI | + multi-node |

- An edition = *which modules are assembled* + *which flags are set* — **fixes land once in the common core**

**Speaker notes:** Commercially this is the key slide. There is no per-edition fork to maintain. Security/multi-tenancy/distribution are additive over seams the architecture already leaves open. The core is deliberately auth-free, which keeps Personal frictionless and isolates the security code where it belongs.

---

## Slide 14 — Quality, security & operability posture

**Lean is a feature — especially for regulated buyers**

- **800+ engine tests** + connector/agent/UI suites; connectors tested vs. embedded servers
- **Small SBOM** (framework-free) → fewer CVEs to attest → FedRAMP/SOC 2 asset
- Sandboxed AI SQL · path jailing · secret masking · air-gap builds
- Crash-isolated/idempotent runs · full metrics/events/audit · living ops documentation

**Speaker notes:** The leanness that makes Inspecto easy to run is also a security/compliance advantage: every dependency is something to attest and patch, and we have few. This resonates with Gov/regulated buyers.

---

## Slide 15 — Where we are today

**Mostly shipped; the newest layer is in mainline**

- **Shipped & hardened:** engine, Stage-2, control plane, acquisition framework (A–F), operational intelligence, AI assist, operator console
- **In mainline (next release):** flow-graph platform, data-plane provenance, auth-free common core
- **Planned:** Standard-edition security, object-storage connectors, unified parsing/JSON, authoring polish
- **Future:** Enterprise distributed tier, inline AI UX

**Speaker notes:** Maturity is high. The risk surface for the next release is verifying the flow-graph + provenance end-to-end with real job configs — current verification used synthetic data on a config-less dev backend.

---

## Slide 16 — Roadmap: themes & horizons

**Now → Next → Later, with one clear gating item**

- **Now:** finish/verify flow-graph + provenance; make the edition-realignment release call
- **Next (in order):** **① Standard-edition security (gates revenue)** → ② object-storage connectors → ③ unified parsing/JSON → ④ authoring polish + streaming
- **Later (demand-gated):** Enterprise distributed tier · inline AI UX · multi-step agent graphs · push discovery

**Speaker notes:** The sequence is deliberate: security converts the product to something a regulated buyer can deploy (revenue gate); object storage is the most-requested ingestion gap and lowest risk; parsing breadth widens the market; authoring polish compounds value. Distributed tier is pulled forward only on real demand.

---

## Slide 17 — Decisions we need

**To unblock the next release and the Next horizon**

1. **Release the edition realignment + flow-graph layer?** (go-ahead to commit/release the auth-free core + flows)
2. **Greenlight `inspecto-security` (Standard edition) as the top Next item?** (the revenue gate)
3. **Confirm target IAM(s)** for Standard (Keycloak / WSO2 / Okta / Entra) to scope the resource-server work
4. **Prioritize object-storage backends** (S3 / GCS / Azure / MinIO — which first?)
5. **Finalize console naming** (product is "Inspecto"; reconcile "Inspector" in shipped UI/docs)

**Speaker notes:** These are the concrete asks. (1) and (2) are the big ones — they set the next release and the commercialization path. (3)/(4) scope the work; (5) is a quick cleanup.

---

## Slide 18 — Summary & call to action

**A lean platform that's matured into an operations product — ready to commercialize**

- Onboard any feed with one config; collect securely; author flows visually; operate everything from one console
- Differentiators: **leanness + operability + trust (lineage) + safe AI**
- One codebase, three editions, no forks — commercialization is *additive*, not a rewrite
- **Ask:** approve the next release, greenlight Standard-edition security, confirm IAM + storage priorities

**Speaker notes:** Close on the through-line: we've built a platform that does the whole job — collect, process, observe, operate — while staying lean enough to run anywhere. The path to revenue is a clean, additive security module, not a rebuild. Decisions on the previous slide unblock it.

---

## Backup slides (optional, for Q&A)

### B1 — Representative metrics
Files discovered/downloaded/failed · bytes transferred · fetch durations · active connections · watermark-skipped · sequence gaps · post-actions-failed · per-job success rate, p50/p95.

### B2 — Representative API surface
Pipelines & config · Jobs & schedules (+ reporting) · Sources & acquisition metrics · Connections CRUD · Flows (catalog/graph/combined/authored CRUD/dry-run/views) · Provenance · Events · Alerts · Objects (cases/issues/links/graph/comments/RCA) · Metrics · AI assist.

### B3 — Why not a cluster / a framework?
Embedded columnar DB gives cluster-class SQL on one node for the common collect→normalize→partition→query workload, at a fraction of the ops cost; framework-free keeps the SBOM small (a compliance asset). The Enterprise distributed tier is the opt-in escape hatch when scale truly demands it — the seams are already open.

### B4 — Risk register (top items)
Single-node ceiling (mitigated by the distributed seams) · edition-security scope creep (deliver minimum sellable first) · flow-graph go-live verification · connector breadth vs. core leanness (deps isolated in the connectors module) · naming consistency.
