# Engine-cluster extraction — the real reactor-split unblock (WS-D tail)

**Status:** ✅ SHIPPED & ARCHIVED (2026-07-22). The `fp-engine` extraction landed — reactor is 10
modules, full `mvn -o clean test` green (1884 tests), shaded fat JAR verified. As-built facts + the
extended playbook (rules 7–8, incl. the test-jar + logback.xml build-clean lessons) are distilled into
`docs/okf/backend/modules/reactor.md`; open follow-ons (§2.3 sub-split, fp-acquire) are in `BACKLOG.md`
§5. This file is retained for provenance only — not maintained. (Historical banner below preserved.)

## TL;DR

The entire low/mid-level engine — a **15-package strongly-connected component** — is tied to the
composition root (`service`/`control`/`report`) by **exactly two real compile edges, both from
`com.gamma.job`**:

1. `job → service.Scheduler`   → **RESOLVED (Step 1)** — `Scheduler` relocated to `util`.
2. `job → report.ReportService` → **RESOLVED (Step 2)** — `ReportRunner` SPI (`ReportService` implements it).

**Both edges are now cut; the cluster is import-clean of the composition root** (verified). What remains
is the actual module extraction — a separate, larger, empirically-verified move (see the last section).

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

## Step 2 — invert `job → report.ReportService`  ✅ DONE (ReportRunner SPI)

`ReportJob` calls only two no-arg methods (`statusReport()`, `serviceReport()`) and treats the result
as an opaque `Object` it serialises to JSON; `JobService` merely holds+forwards the reference. So the
edge was purely the **`ReportService` type name** in `job`. Options B (relocate the nested value
records) and D (extract the two methods into a lower class) were **rejected after reading the code**:
`job` never names the value records, and `statusReport()`/`serviceReport()` read `CollectorService`
state (`service.pipelines()` → `CollectorService.PipelineView`, `service.configFor()`,
`service.statusStore()`) — so pulling them down would only *move* the `service` edge, not remove it.
`ReportService` is intrinsically a service-side aggregator.

**Shipped (A):** new interface `com.gamma.job.ReportRunner { Object statusReport(); Object
serviceReport(); }`. `ReportService implements ReportRunner` — its existing typed methods satisfy the
`Object` returns by **covariant override**, so zero method-body changes. `JobService`/`ReportJob` now
name `ReportRunner` (same package, no import) instead of importing `com.gamma.report.ReportService`.
The composition root passes the `ReportService` instance unchanged (widening to `ReportRunner`). Net
direction flip: `report` (core) now depends **down** on `job` (cluster) — correct. Not `@PublicApi`
(internal seam); adding an implemented interface to the `@PublicApi` `ReportService` is compatible, so
no api-stability change.

## Both edges cut — the cluster is now import-clean of the composition root

Re-ran the cluster→outside import scan: **zero** real edges from `C` into `service`/`control`/`report`/
`assist`/`report` (the lone `acquire → "Binary file …StabilityGate.java"` is a grep binary-detection
artifact — its only `com.gamma` import is `com.gamma.event`, in-cluster). The 15-package engine cluster
`C` no longer compile-depends on anything above it except the already-extracted leaves
(`api`/`util`/`config`/`sql`).

## After the two edges: the extraction itself (future, large)

With `C` now import-clean, it can go below `core` as **one `fp-engine` module** first (lowest-risk: one move,
`core`/`service`/`control`/`report`/`assist` depend down onto it), then later be split into the §2.3
clusters (fp-core-etl / fp-ops / fp-catalog) if desired. This is a big, separate, test-guarded move —
do NOT attempt it in the same shift as Steps 1–2. Verify the "only 2 edges" claim empirically at that
point by actually forming the module and letting the reactor prove acyclicity.

## Next-shift decision (before the extraction)

Both prerequisite edge-cuts are shipped. The remaining work — forming the `fp-engine` module — is
large and should be its own shift. Open question for whoever picks it up: **one big `fp-engine` module
first** (recommended, lowest-risk single move) **vs** going straight to the §2.3 three-cluster split
(fp-core-etl / fp-ops / fp-catalog). Verify the "import-clean" claim empirically by actually forming
the module and letting the reactor prove acyclicity (watch for test-scope deps, `META-INF/services`,
resource files, and the shaded-jar assembly — import-clean ≠ build-clean until proven).
