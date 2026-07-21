# Maven reactor & modularization (as-built)

How the reactor is shaped, why, and the rules for extracting further modules. Distilled from
`modularization-optimization-plan.md` (completed 2026-07-21, archived in
`../../../archived-documents/plans-archive/`) — that plan's findings sections hold the full evidence
base and the per-item history.

## Reactor shape (2026-07-21)

Build order (root `pom.xml`, parent `file-processor-parent`):

| # | Directory | artifactId | Role |
|---|---|---|---|
| 1 | `inspecto-api/` | `file-processor-api` | **Leaf, dependency-free**: only `com.gamma.api.PublicApi`, the stability-contract annotation. Behavior code never lives here. |
| 2 | `inspecto-config/` | `file-processor-config` | `com.gamma.config` — spec / io (TOON codec) / safety. Deps: fp-api + jtoon + jackson. |
| 3 | `inspecto/` | `file-processor` | The core: engine + control plane, ships the shaded fat JAR. Depends on fp-api + fp-config. |
| 4–7 | `inspecto-agent/`, `-agent-hosted/`, `-connectors/`, `-intelligence/` | `file-processor-*` | Siblings; each depends on core (and resolves fp-api/fp-config transitively). |
| (opt) | `inspecto-security/` | `file-processor-security` | Standard-edition only, behind `-Pedition-standard` — not in the default `<modules>`. |

Binding constraints (unchanged by the split): framework-free (JDK HttpServer, manual DI,
ServiceLoader SPI); **one deployable** — modularization is reactor-internal, the fat
`file-processor.jar` is unchanged; editions are build flavors, never branches.

## Version management (M1)

Drift-prone shared external versions live ONCE in the parent `<dependencyManagement>`
(`junit`, `langchain4j`, `eoiagent`, `postgresql`, and since S5 `jtoon` + `jackson-databind`);
modules declare those artifacts version-free. Reactor-internal deps (`file-processor-api`,
`file-processor-config`) are also parent-managed at `${project.version}`. Single-owner deps stay
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

## Remaining split work (BACKLOG §5)

`fp-acquire` is blocked until `etl` + `util` + `event` leave core (= WS-D). Order for that shift:
etl/util/event out first, then fp-acquire, then the C1 tail (fp-catalog / fp-ops / fp-core-etl /
fp-control / fp-host). Prerequisite cycle-breaking moves are listed in the archived plan §1.7
(e.g. `BatchEventBus` + `CronExpression` out of `service`; `StatusStore` below `catalog`).

## Related seams (shipped, documented elsewhere)

- `@PublicApi` freeze of the SPI surface (M3/S8) — `../control-plane/api-stability.md`.
- intelligence↔agent decoupling via the core `ModelSettings` read-side bridge (S9) — agent stays
  the single writer of `assist-settings.properties`; core owns a parallel read-side value type.
- `ControlApi.dispatch` middleware chain (S6), `SpaceManager.closeWithDeadline` (S7) —
  `../control-plane/control-api.md`.
- `PipelineNodeType` is a **reserved** ServiceLoader extension point (C7): built-ins implement it,
  external providers = zero by design; see its javadoc.
