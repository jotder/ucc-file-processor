# Maven reactor & modularization (as-built)

How the reactor is shaped, why, and the rules for extracting further modules. Distilled from
`modularization-optimization-plan.md` (completed 2026-07-21, archived in
`../../../archived-documents/plans-archive/`) — that plan's findings sections hold the full evidence
base and the per-item history.

## Reactor shape (2026-07-21, +WS-D util/sql)

Build order (root `pom.xml`, parent `file-processor-parent`):

| # | Directory | artifactId | Role |
|---|---|---|---|
| 1 | `inspecto-api/` | `file-processor-api` | **Leaf, dependency-free**: only `com.gamma.api.PublicApi`, the stability-contract annotation. Behavior code never lives here. |
| 2 | `inspecto-util/` | `file-processor-util` | **Leaf w.r.t. `com.gamma`** (imports nothing from other core packages): `com.gamma.util` — the DuckDB access point (`DuckDbUtil` + JDBC/summarize/schema helpers), CSV/TOON I/O, file movers/walkers, tar/gzip, bounded history, `DottedPath`. Deps: duckdb_jdbc + opencsv + univocity + commons-compress + commons-lang3 + gson + jtoon + jackson. (The `ura` CLI `MainApp` — the one class in the old `com.gamma.util` that reached into core — was relocated to `com.gamma.inspector.MainApp` so this stays a clean leaf; see playbook rule 6.) |
| 3 | `inspecto-config/` | `file-processor-config` | `com.gamma.config` — spec / io (TOON codec) / safety. Deps: fp-api + jtoon + jackson. |
| 4 | `inspecto-sql/` | `file-processor-sql` | **Foundational leaf**: `com.gamma.sql` — the read-only DuckDB SQL sandbox (`SqlSandbox`/`SqlSandboxPolicy`), `SqlOracle`, `SqlGuard`, `SqlViews`. Deps: fp-api + fp-config + fp-util only (no external deps; DuckDB via `util.DuckDbUtil`). Kept its own leaf, not folded into the (still-blocked) fp-catalog, because consumers span every layer. |
| 5 | `inspecto/` | `file-processor` | The core: engine + control plane, ships the shaded fat JAR. Depends on fp-api + fp-util + fp-config + fp-sql. |
| 6–9 | `inspecto-agent/`, `-agent-hosted/`, `-connectors/`, `-intelligence/` | `file-processor-*` | Siblings; each depends on core (and resolves the leaf modules transitively). |
| (opt) | `inspecto-security/` | `file-processor-security` | Standard-edition only, behind `-Pedition-standard` — not in the default `<modules>`. |

Binding constraints (unchanged by the split): framework-free (JDK HttpServer, manual DI,
ServiceLoader SPI); **one deployable** — modularization is reactor-internal, the fat
`file-processor.jar` is unchanged; editions are build flavors, never branches.

## Version management (M1)

Drift-prone shared external versions live ONCE in the parent `<dependencyManagement>`
(`junit`, `langchain4j`, `eoiagent`, `postgresql`, since S5 `jtoon` + `jackson-databind`, and
since WS-D `duckdb_jdbc` + `univocity-parsers` + `commons-compress` + `gson` — each now shared by
core + fp-util); modules declare those artifacts version-free. `opencsv` stayed pinned in fp-util
(single-owner after the split — no drift to prevent). Reactor-internal deps (`file-processor-api`,
`file-processor-util`, `file-processor-config`) are also parent-managed at `${project.version}`. Single-owner deps stay
pinned in their one module. JaCoCo's `coverage` profile lives in the parent (`mvn -Pcoverage test`
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

## Remaining split work (BACKLOG §5)

Shipped so far: **increment 1 `util`** (fp-util) and **increment 2 `sql`** (fp-sql) — the two
genuinely leaf-extractable packages, each depending only on already-extracted modules.

**§1.7 cycle-breaking prep (DONE — no new module, just relocations that shrink the `service`
god-object's fan-in ahead of the cluster splits):**
- ✅ `CronExpression` `service` → `util` (fp-util): a dependency-free cron primitive (needs only
  `api.PublicApi`); removes the `job`/`pipeline` → `service` edge that existed only for scheduling.
- ✅ `StatusStore` (interface) `service` → `etl`: it was the **sole `catalog` → `service` edge**
  (`CatalogOverlay` → `service.StatusStore`), so relocating it (floor is `etl`, via
  `etl.PipelineConfig`) **broke the `service ↔ catalog` cycle** outright. Its impls
  (`DbStatusStore`/`FileStatusStore`) stay in `service`.
- ✅ `BatchEventBus` `service` → `etl` (beside `etl.BatchEvent`, its payload type): fan-in reduction,
  not a full cycle break. ~18 files repointed; the trigger bus now sits with its payload in `etl` and
  the `service` package no longer owns it. **This drains the §1.7 cycle-breaking prep** — no further
  useful relocations remain before the M2 decomposition.

**`event` and `etl` are NOT leaf-extractable — and the blocker is bigger than BACKLOG implied.** The
original `import`-only recon undersold it; the **inline-aware** full package-edge map (playbook rule 5)
shows they point *up* into the god-object cluster, not just sideways at sql/metrics:
- `com.gamma.event` → `service.CollectorService`, `alert.AlertService`, `signal.Signal`, plus
  `sql`/`metrics`/`etl` — mutually cyclic with `etl`.
- `com.gamma.etl` → `service.CollectorService`/`ConfigRegistry`, `inspector.*` (BatchProcessor,
  FileChunker, QuarantineManager, StreamingPluginBatchStrategy, CollectorProcessor),
  `pipeline.PipelineTrigger`/`DecisionRules`, `query`, `signal`, `acquire`, `ingester` — mutually
  cyclic with `event`.

So `etl`/`event` are **mid-layer**, woven into `service` (the composition-root hub every package
points at and which points back at nearly everything), `inspector`, and `pipeline`. They cannot drop
below core; the real prerequisite is the **M2 `SourceService`/`CollectorService` decomposition** (§2.2
"the split is blocked until the god objects shrink") — `PipelineScheduler` is already out, more remains.
Then the §2.3 clusters (fp-core-etl = `etl`/inspector/pipeline; fp-ops = `ops`/`event`/alert/notify;
fp-catalog = `catalog`/`query`/`sql`) can be split in dependency order, with the §1.7 cycle-breaking
moves first (`BatchEventBus` + `CronExpression` out of `service`; `StatusStore` below `catalog`), and
only then can `fp-acquire` (S5 ③) go below core. **`fp-acquire` is gated on all of that, not on a quick
etl/event pull.** Next cleanly-available leaf after sql: none without cycle-breaking (`expectation` →
`query`; `metrics` → `event`; `signal` → `event`; every other package reaches `service`).

## Related seams (shipped, documented elsewhere)

- `@PublicApi` freeze of the SPI surface (M3/S8) — `../control-plane/api-stability.md`.
- intelligence↔agent decoupling via the core `ModelSettings` read-side bridge (S9) — agent stays
  the single writer of `assist-settings.properties`; core owns a parallel read-side value type.
- `ControlApi.dispatch` middleware chain (S6), `SpaceManager.closeWithDeadline` (S7) —
  `../control-plane/control-api.md`.
- `PipelineNodeType` is a **reserved** ServiceLoader extension point (C7): built-ins implement it,
  external providers = zero by design; see its javadoc.
