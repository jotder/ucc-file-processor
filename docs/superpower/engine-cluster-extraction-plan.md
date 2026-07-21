# Engine-cluster extraction — the real reactor-split unblock (WS-D tail)

**Status:** IN FLIGHT (2026-07-21). Supersedes the `etl`/`event` blocker analysis in
`docs/okf/backend/modules/reactor.md` §1.7 remaining-work, which was **wrong** (see below).
When this ships: distill into `reactor.md`, move open items to `BACKLOG.md` §5, archive this file.

## TL;DR

The entire low/mid-level engine — a **15-package strongly-connected component** — is tied to the
composition root (`service`/`control`/`report`) by **exactly two real compile edges, both from
`com.gamma.job`**:

1. `job → service.Scheduler`   → **RESOLVED (Step 1, done)** — `Scheduler` relocated to `util`.
2. `job → report.ReportService` → needs a small **dependency inversion** (Step 2, pending decision).

Resolve those two and the whole cluster can drop below `core` as a module (or cluster of modules).
This is a *far* smaller lever than the "decompose the `CollectorService` god-object" work the roadmap
assumed — and that god-object work (M2) turns out to be **maintainability-only**, not a split blocker.

## Why the old analysis was wrong

`reactor.md` claimed `etl`/`event` were "woven up into `service`/`inspector`/`pipeline`/`alert`/`signal`"
and gated on the **M2 `CollectorService` decomposition**. That conclusion came from recon that
**counted javadoc `{@link}`/`{@code}` references as compile edges**. They are not — `javac` ignores
them. This is the *inverse* of playbook rule 5: rule 5 warns about **missing** inline FQNs; here the
error was **over-counting** doc references.

Ground truth: `etl → service` has **zero** compile edges (only two `{@link}` in `PipelineConfig`
javadoc + one code comment in `PipelineConfigParser`). Decomposing `CollectorService` reorganizes
classes *within* `com.gamma.service`; it does nothing for `etl`'s ability to leave `core`.

**Method that gives the truth:** `import` statements + inline-FQN *in code only* (exclude lines that
are comments or contain `{@link`/`{@code`). Package-count greps that include comment bodies mislead.

> **Playbook rule 7 (this shift):** an import-anchored *or* inline-`com.gamma.x` count that does not
> strip comment/javadoc lines will FALSELY report edges. Always confirm a suspected cross-package edge
> is real code (import line, or FQN outside `//`, `/* */`, `{@link}`, `{@code}`) before treating it as
> a split blocker. The `service↔etl` "blocker" in the old reactor.md was pure javadoc.

## The strongly-connected engine cluster `C`

`C = { etl, event, signal, query, pipeline, inspector, acquire, ingester, ops, job, enrich, alert,
metrics, notify, catalog }` — all mutually reachable, so they must move together (or the SCC be
broken, which is not worth it: the intra-`C` cycles are the fp-core-etl / fp-ops / fp-catalog cluster
members that were always destined to share module space per reactor.md §2.3).

Boundary decisions:
- **`catalog` is IN `C`.** It imports `etl`/`enrich` (into `C`) and is imported by `alert` (`alert →
  catalog.ConfigSource`); bidirectional ⇒ part of the SCC. So `alert → catalog` is **intra-cluster**,
  not a blocker.
- **`report` stays in `core`.** `report.ReportService` imports `service.CollectorService` +
  `service.EnrichmentService`, and `CollectorService` holds a `ReportService` field — `report ↔
  service` is a tight bidirectional cycle ⇒ `report` belongs with the composition root.
- **`assist` stays in `core`.** The only `alert → assist` reference is a javadoc `{@link
  ...Diagnosis}`; no cluster package imports `assist`.

### The only real edges leaving `C` (blockers)

| Edge | Files | Resolution |
|---|---|---|
| `job → service.Scheduler` | `JobService.java` (field/ctors) | **Step 1** — relocate `Scheduler` → `util` (it depends only on `util.CronExpression`; not `@PublicApi`). |
| `job → report.ReportService` | `JobService` (holds+forwards), `ReportJob` (calls `statusReport()`, `serviceReport()`) | **Step 2** — invert (below). |

(Grep may report `acquire → "Binary file ... StabilityGate.java"` — a grep binary-detection artifact,
verified NOT a real edge.)

## Step 1 — `Scheduler` `service` → `util`  (mechanical; same playbook as `CronExpression`)

`git mv` `Scheduler.java` + `SchedulerTest.java` into `com.gamma.util`; drop the now-same-package
`import com.gamma.util.CronExpression`; repoint `com.gamma.service.Scheduler` → `com.gamma.util.Scheduler`
across the reactor (real importers: `job` = JobService + 3 tests); add `import com.gamma.util.Scheduler`
to the service-package simple-name users (`CollectorService`, `EnrichmentService`, `EnrichmentServiceTest`).
`PipelineTrigger.Scheduler` is an **unrelated nested enum** — untouched.

**Dep surfaced:** `Scheduler` uses slf4j; `inspecto-util` had no slf4j dep (no prior util class logged).
Added `org.slf4j:slf4j-api` — parent-managed (`slf4j.version` = 2.0.17, the logback-compatible pin),
version-free in both `inspecto-util` and `inspecto` (M1 shared-dep convention). Gate: full reactor
`mvn -o clean test`.

## Step 2 — invert `job → report.ReportService`  (design decision needed)

`ReportJob` calls only two no-arg methods: `statusReport()` → `StatusReport`, `serviceReport()` →
`ServiceReport`. `JobService` merely holds the reference and hands it to `ReportJob`. **But** the
return types (`StatusReport`/`ServiceReport`) are declared **inside `report.ReportService`** (a
`@PublicApi` class fused to `service`), and `ReportJob` treats the result as an opaque `Object` it
serializes to JSON / delivers. Options:

- **(A) SPI returning a neutral artifact.** Define `ReportRunner` (in `job` or a low SPI package) with
  `Object run(String scope)` (or a small neutral record). `ReportService` implements it; the
  composition root injects it into `JobService`. `ReportJob` already treats the value as `Object` →
  minimal churn, no report types leak into `job`. **Recommended.**
- **(B) Relocate the report *value* types** (`StatusReport`/`ServiceReport`/…) down into `C`, leaving
  the fused `ReportService` orchestrator in `core`. Larger surface; `@PublicApi` return types move
  (FQN change → api-stability update + sibling repoint). More invasive.
- **(C) Move `ReportJob` out of `job`** into `core` and register it via the Job descriptor SPI, so
  `job` never names `ReportService`. Changes the job-pack wiring; needs a look at `JobConfig`/descriptor
  discovery.

## After the two edges: the extraction itself (future, large)

With `C` detached, it can go below `core` as **one `fp-engine` module** first (lowest-risk: one move,
`core`/`service`/`control`/`report`/`assist` depend down onto it), then later be split into the §2.3
clusters (fp-core-etl / fp-ops / fp-catalog) if desired. This is a big, separate, test-guarded move —
do NOT attempt it in the same shift as Steps 1–2. Verify the "only 2 edges" claim empirically at that
point by actually forming the module and letting the reactor prove acyclicity.

## Open question for the operator

Step 2 approach — **(A) `ReportRunner` SPI** (recommended, minimal) vs (B) relocate value types vs
(C) move `ReportJob`. Pick before implementing.
