# Maven reactor & modularization (as-built)

How the reactor is shaped, why, and the rules for extracting further modules. Distilled from
`modularization-optimization-plan.md` (completed 2026-07-21, archived in
`../../../archived-documents/plans-archive/`) — that plan's findings sections hold the full evidence
base and the per-item history.

## Reactor shape (2026-07-22, +WS-D engine)

Build order (root `pom.xml`, parent `file-processor-parent`):

| # | Directory | artifactId | Role |
|---|---|---|---|
| 1 | `inspecto-api/` | `file-processor-api` | **Leaf, dependency-free**: only `com.gamma.api.PublicApi`, the stability-contract annotation. Behavior code never lives here. |
| 2 | `inspecto-util/` | `file-processor-util` | **Leaf w.r.t. `com.gamma`** (imports nothing from other core packages): `com.gamma.util` — the DuckDB access point (`DuckDbUtil` + JDBC/summarize/schema helpers), CSV/TOON I/O, file movers/walkers, tar/gzip, bounded history, `DottedPath`. Deps: duckdb_jdbc + opencsv + univocity + commons-compress + commons-lang3 + gson + jtoon + jackson. (The `ura` CLI `MainApp` — the one class in the old `com.gamma.util` that reached into core — was relocated to `com.gamma.inspector.MainApp` so this stays a clean leaf; see playbook rule 6.) |
| 3 | `inspecto-config/` | `file-processor-config` | `com.gamma.config` — spec / io (TOON codec) / safety. Deps: fp-api + jtoon + jackson. |
| 4 | `inspecto-sql/` | `file-processor-sql` | **Foundational leaf**: `com.gamma.sql` — the read-only DuckDB SQL sandbox (`SqlSandbox`/`SqlSandboxPolicy`), `SqlOracle`, `SqlGuard`, `SqlViews`. Deps: fp-api + fp-config + fp-util only (no external deps; DuckDB via `util.DuckDbUtil`). |
| 5 | `inspecto-engine/` | `file-processor-engine` | **The engine cluster (WS-D, 2026-07-22)**: the 15-package SCC below the composition root — `etl`, `event`, `signal`, `query`, `pipeline`, `inspector`, `acquire`, `ingester`, `ops`, `job`, `enrich`, `alert`, `metrics`, `notify`, `catalog` (858 tests). Deps: fp-api/util/config/sql + univocity, duckdb, jtoon, jackson, slf4j, **logback-classic**, commons-compress, gson. Owns `logback.xml` + the `event.EventStoreAppender` + both `META-INF/services` files (`catalog.spi.DescriptionProvider`, `notify.NotificationChannel`). Publishes a **test-jar** (the shared fixture `com.gamma.etl.TestConfigs`). |
| 6 | `inspecto/` | `file-processor` | The core / composition root: `service`, `control`, `report`, `assist`, `exchange`, `expectation`, `intelligence`, `model`; ships the shaded fat JAR. Depends DOWN on fp-api/util/config/sql + **fp-engine** (+ its test-jar in test scope). |
| 7–10 | `inspecto-agent/`, `-agent-hosted/`, `-connectors/`, `-intelligence/` | `file-processor-*` | Siblings; each depends on core (and resolves the leaf + engine modules transitively). |
| (opt) | `inspecto-security/` | `file-processor-security` | Standard-edition only, behind `-Pedition-standard` — not in the default `<modules>`. |

Binding constraints (unchanged by the split): framework-free (JDK HttpServer, manual DI,
ServiceLoader SPI); **one deployable** — modularization is reactor-internal, the fat
`file-processor.jar` is unchanged; editions are build flavors, never branches.

## Version management (M1)

Drift-prone shared external versions live ONCE in the parent `<dependencyManagement>`
(`junit`, `langchain4j`, `eoiagent`, `postgresql`, since S5 `jtoon` + `jackson-databind`, and
since WS-D `duckdb_jdbc` + `univocity-parsers` + `commons-compress` + `gson`); modules declare those
artifacts version-free. `opencsv` stayed pinned in fp-util (single-owner after the split — no drift to
prevent). `logback-classic` is single-owner (fp-engine, pinned 1.5.18). Reactor-internal deps
(`file-processor-api`, `file-processor-util`, `file-processor-config`, `file-processor-sql`,
`file-processor-engine` — the last also managed as a `test-jar` entry) are parent-managed at
`${project.version}`. Single-owner deps stay pinned in their one module. JaCoCo's `coverage` profile lives in the parent (`mvn -Pcoverage test`
instruments every module).

## Build entry points — core is NOT standalone anymore

Since S5 the core depends on reactor siblings, so **a core-alone `mvn` from `inspecto/` only
resolves after a root `mvn install`**. Every entry point builds via the root reactor:

- CI (`.github/workflows/ci.yml`): root `mvn install` / `mvn test` — unaffected.
- `inspecto/package.ps1` step 1: `mvn clean package -pl inspecto -am` **from the repo root**
  (`-am` builds fp-api/fp-config in-pass; the shaded JAR still lands in `inspecto/target/`).
  Same idiom as its Standard-edition step 1c.
- Shade has no include-list → new reactor modules land in the fat JAR automatically.

## Module-extraction playbook (what S5 proved)

1. **Module-level acyclicity ≠ package-import acyclicity.** A package with clean imports can still
   be un-extractable: `config` imported only `PublicApi`, but `PublicApi` lived in core while core
   imported config → module cycle. Always check what the candidate imports *transitively lives in
   core* before declaring it "low-risk". Extract the blocking leaf first (hence fp-api before
   fp-config).
2. **Keep the Java package name unchanged** when moving — core's 32 config-importing files needed
   zero edits; the diff stays pure-rename and history survives (`git mv`).
3. **Surefire's working directory is the module root.** A test that walks a repo/module-relative
   fixture tree cannot move with its class: the shipped-examples round-trip test stayed in core
   (`ShippedExamplesRoundTripTest`) with the `examples/` tree it validates when the rest of
   `ConfigCodecTest` moved to fp-config.
4. **Gate every move on the FULL reactor** (`mvn -o clean test` from root), never a single module —
   that's also the only way to prove a removed sibling dep (S9) really is unused.
5. **Import-anchored grep is NOT enough to prove a package is a leaf.** A class can look dependency-free
   by its `import` lines yet reach into core via a **fully-qualified inline** call
   (`com.gamma.inspector.ReprocessCommand.run(...)` in `MainApp`) or a fully-qualified `{@link}`/`{@code}`
   in javadoc — neither has an `import` line to grep. WS-D's initial `grep 'import com.gamma…'` scan
   cleared `util` as a pure leaf and MISSED both `MainApp`'s core call (a real compile break, caught only
   by the reactor build) and a `com.google.gson.Gson` inline ref (a missing pom dep). Scan with
   `grep -rhoE 'com\.gamma\.[a-z]+'` (matches import AND inline) for internal edges, and
   `grep -rhoE '\b(com|org|dev|…)\.[a-z]+\.[a-z]+'` for external deps before writing the module pom.
6. **One non-leaf class in an otherwise-leaf package → move it to its natural home in core.** `MainApp`
   (the `ura` CLI) was the sole `com.gamma.util` class that dispatches into core (it calls
   `com.gamma.inspector.ReprocessCommand`). It was relocated to `com.gamma.inspector.MainApp` — beside
   `CollectorProcessor` (the fat-jar Main-Class) and `ReprocessCommand`, the package that already holds the
   app/CLI entry points — so `com.gamma.util` moves out whole and fp-util is a genuine leaf with **no split
   package**. Cost: the launcher scripts (`ura.sh`/`ura.bat`/`package.ps1`) + a handful of doc mentions of
   the raw FQN, all mechanical; the eight util classes `MainApp` constructs were already `public`, so no
   visibility widening. (A split package — keeping `MainApp` in core under `com.gamma.util` — is legal on
   the plain classpath and was the zero-churn alternative, but leaves a `util`-named class that isn't a
   utility and blurs the module boundary the split exists to sharpen.)
7. **Over-counting is as wrong as under-counting (the inverse of rule 5).** An import/inline-FQN scan
   that does **not** strip comment/javadoc lines FALSELY reports edges: the old `etl↔service` "blocker"
   was pure `{@link}`/`{@code}` in javadoc, which `javac` ignores. Confirm a suspected edge is real code
   (an `import`, or an FQN outside `//`, `/* */`, `{@link}`, `{@code}`) before treating it as a blocker.
   The corrected scan strips comment lines: `grep -vE '^\s*\*' | grep -vE '\{@(link|code)' | grep -vE '//'`.
8. **Import-clean ≠ build-clean — two things imports never reveal.** (a) **Shared test fixtures.** A test
   helper class in a moved package that is `import`ed by tests that stay behind needs a **test-jar**
   (maven-jar-plugin `test-jar` goal on the producer; `<type>test-jar><scope>test</scope>` on the
   consumer) — Maven does not share test classes across modules. Find these by scanning stay-behind test
   dirs for imports of the moved packages. (b) **Resource-wired classes.** A resource that names a moved
   class by FQN (e.g. `logback.xml`'s `EventStoreAppender` appender) must travel WITH that class, or the
   moved module's own tests that rely on the resource break. Always inventory `META-INF/services/*` and
   `*.xml`/`*.properties` resources for FQNs of moved classes before the move.

## The engine-cluster split (WS-D, shipped 2026-07-22)

The 15-package strongly-connected engine — `etl, event, signal, query, pipeline, inspector, acquire,
ingester, ops, job, enrich, alert, metrics, notify, catalog` — was extracted whole, in ONE move, into
`fp-engine` below core. This corrected the earlier (now-deleted) analysis that had `etl`/`event` gated
on the **M2 `CollectorService` decomposition**: that conclusion counted javadoc `{@link}` references as
compile edges (playbook rule 7). Ground truth confirmed empirically: the SCC was tied to the composition
root by exactly **two `job` edges** — `job→service.Scheduler` (cut by relocating `Scheduler`→`util`) and
`job→report.ReportService` (cut by the `ReportRunner` SPI — `report` now depends **down** on `job`). M2
is maintainability-only, **not** a split blocker.

**§1.7 cycle-breaking prep that made the SCC coherent (all DONE, shipped before the split):**
- ✅ `CronExpression` + `Scheduler` `service` → `util` (the two edges into `service` from scheduling).
- ✅ `StatusStore` (interface) `service` → `etl` — broke the `service ↔ catalog` cycle (impls stay in `service`).
- ✅ `BatchEventBus` `service` → `etl` (beside its `BatchEvent` payload).
- ✅ `ReportRunner` SPI inverts `job→report` — `ReportService implements ReportRunner` (covariant `Object` returns).

**As-built facts (verified by the full reactor build, not just import scans):**
- **Import-clean = build-clean, but only after resolving two things import scans don't show.**
  (1) `com.gamma.etl.TestConfigs` is a test fixture used by ~45 core `control`/`report`/`service` tests;
  Maven does not share test classes across modules, so fp-engine publishes a **test-jar** (maven-jar-plugin
  `test-jar` goal) that core consumes in test scope. (2) `logback.xml` wires the engine-owned
  `event.EventStoreAppender`, so it moved to **engine** resources (co-located with the appender + on
  engine's own test classpath for `EventLogAndAppenderTest`).
- **Dependency repartition:** univocity/duckdb/commons-compress/gson/logback-classic are cluster-only —
  they moved to fp-engine and reach the fat JAR **transitively** through core→fp-engine. Core kept only
  the third-party it uses directly (jackson ×4, slf4j ×23, jtoon ×6) + the leaf modules.
- **Fat JAR unchanged:** shade (in core) has no include-list, so fp-engine's classes/resources bundle
  automatically. Verified in `file-processor-4.0.0-RC1.jar`: `Main-Class: com.gamma.inspector.CollectorProcessor`
  present, `logback.xml` at root, both service files present, engine + third-party classes bundled.

## Intra-engine structure (measured 2026-07-22) — why the sub-splits are NOT mechanical

`fp-engine` is one coarse module. A follow-up analysis (inline-aware, comment-stripped import + FQN
scan, both directions) mapped its internal shape. **The optimistic "trivially available" / "falls out
naturally" claims for the sub-splits were WRONG — the third time this arc under-estimated coupling.**

Layering **as first measured** (top = consumed only by core; bottom = the mutually-cyclic core) —
increment 1 below then reshaped the bottom row:

| Layer | Packages | Note |
|---|---|---|
| Top (in-degree 0 within engine) | `inspector`, `ingester`, `notify`, `alert` | `inspector` holds the fat-jar Main-Class |
| Mid | `catalog` | imported only by `alert` |
| **SCC (10 pkgs, mutually cyclic)** | `etl, event, metrics, pipeline, job, acquire, signal, query, enrich, ops` | inseparable without cycle-breaking |

- **`fp-acquire` below engine was NOT available as first measured.** `acquire` was *inside* the SCC
  (`acquire→etl→pipeline→job→acquire`), so it could not drop below the rest of the engine until the SCC
  was decomposed. (The S5 ③ "falls out naturally" premise is retired.) **Increment 1 changed this** —
  `acquire` fell out of the SCC (see below).
- **The §2.3 three-cluster sub-split (fp-core-etl / fp-ops / fp-catalog) was impossible as specified**,
  because those clusters split packages (`etl`, `event`, `pipeline`, `ops`, `query`) that all lived in
  the *one* 10-package SCC. Increment 1 shrank that SCC but did not (and was not meant to) realize the
  §2.3 clusters exactly; further work is still deliberate cycle-breaking, not a mechanical move.
- **What the map showed as feasible — and increment 1 (2026-07-22) then DID.** The SCC was held
  together substantially by `etl` importing *up* into `event`/`pipeline`/`query`/`signal` via only
  **two files**: `etl.DecisionRuleApplier` (`pipeline.DecisionRules` + `query.ConditionSql`) and
  `etl.BatchAuditWriter` (`event.EventLog` + `signal.Signal` for the `pipeline.batch.*` observability
  tail). Both were cut without touching behavior:
    - `DecisionRuleApplier` → relocated to `com.gamma.pipeline` (its cohesive home with `DecisionRules`);
      all 3 callers (`inspector`/`enrich`/`job`) are higher-layer, so no etl→pipeline edge returns.
    - `BatchAuditWriter` → the inlined Signal build+emit moved to the new `com.gamma.signal.PipelineBatchSignal`,
      wired via an injected `setTerminalBatchSink(Consumer<BatchEvent>)` that `CollectorProcessor` sets to
      `PipelineBatchSignal::emit`. `BatchEvent` already carried every field the Signal needs, so it is a
      pure fan-out split. (One test method moved etl→signal to keep etl-test clean of the up-packages.)
  **Result — the mega-SCC fragmented (verified by re-mapping, full reactor green, 1884 tests):**

  | Before (10-pkg SCC) | After increment 1 |
  |---|---|
  | `etl, event, metrics, pipeline, job, acquire, signal, query, enrich, ops` | `etl` = **foundation leaf** (out-degree 0 in engine) · SCC → **`{pipeline, job, query, enrich}`** + **`{event, metrics}`** · `acquire`, `signal`, `ops`, `catalog` dropped OUT (now simple downward deps on `etl`/`event`) |

  So `acquire` is no longer SCC-trapped (it now imports only `etl`+`event`), and `etl` is cleanly
  extractable as an `fp-etl` module below the rest whenever wanted — **note** etl *test* sources still
  reach up (integration tests importing `enrich`/`job`/`acquire`/`inspector`/`event`); those must be
  relocated before an actual standalone `fp-etl` module, but they don't affect the main-code layering.
  Further fragmentation (breaking `{pipeline,job,query,enrich}` or `{event,metrics}`) is the same class
  of deliberate, deadlock-sensitive design work — do it only if finer module granularity is wanted. The
  coarse `fp-engine` already delivers the acyclic core↔engine boundary, which was the whole point of WS-D.
- **M2 `CollectorService`/`SourceService` decomposition** remains open as maintainability work (it
  reorganizes classes *within* core's `service` package; it is NOT a split blocker).

## Related seams (shipped, documented elsewhere)

- `@PublicApi` freeze of the SPI surface (M3/S8) — `../control-plane/api-stability.md`.
- intelligence↔agent decoupling via the core `ModelSettings` read-side bridge (S9) — agent stays
  the single writer of `assist-settings.properties`; core owns a parallel read-side value type.
- `ControlApi.dispatch` middleware chain (S6), `SpaceManager.closeWithDeadline` (S7) —
  `../control-plane/control-api.md`.
- `PipelineNodeType` is a **reserved** ServiceLoader extension point (C7): built-ins implement it,
  external providers = zero by design; see its javadoc.
