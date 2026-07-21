# `fp-etl` module extraction — plan

**Status: SHIPPED 2026-07-22** (same day as drafted). As-built facts distilled into
`docs/okf/backend/modules/reactor.md` § "fp-etl module extraction (WS-D increment 2)"; open follow-ons
in `docs/BACKLOG.md` §5. Archived here for provenance — not maintained further.

Part of the WS-D reactor-split arc (see `docs/BACKLOG.md` §5 and `docs/okf/backend/modules/reactor.md`),
which are authoritative for the reactor shape/history — this file only covers the one increment below.

## Goal

Extract `com.gamma.etl` out of `inspecto-engine` into its own leaf module (`fp-etl` /
`file-processor-etl`), sitting below the rest of the engine cluster, now that `etl` main-code is a
foundation leaf (out-degree 0 within engine — see increment 1, `c76a9634`).

## Blocker found (2026-07-22, this session)

`etl`'s *test* sources still import up. Scan:
```
grep -rlE "^import com\.gamma\.(event|signal|query|pipeline|inspector|acquire|ingester|ops|job|enrich|alert|metrics|notify|catalog)\b" inspecto-engine/src/test/java/com/gamma/etl
```
→ two files:

1. **`ConfigFromMapTest.java`** — imports `enrich.EnrichmentConfig`, `job.JobConfig`, `util.ToonHelper`.
   Likely shallow: probably just asserts that `etl`'s config-from-map path can carry enrich/job blocks
   through unchanged. Needs a read to confirm, then either trim the assertions to etl-only concerns or
   move the enrich/job-specific assertions out.

2. **`SourceConfigTest.java`** — the real blocker. ~450 lines, 15 `@Test` methods. Despite the
   package/filename, this is **not an etl-config unit test** — it's a full acquire→etl→inspector→event
   integration test: ledgers (`AcquisitionLedgers`, `InMemoryAcquisitionLedger`, `LedgerEntry`),
   checksums, connectors (`CollectorConnector(s)`, `LocalFileSystemConnector`), the actual run path
   (`CollectorProcessor.run()` / `.collectCandidates()`), and event-store assertions
   (`InMemoryEventStore`, `EventLog`, `EventType.SEQUENCE_GAP`) for gap detection. It exercises
   dedup modes, incremental watermarks, stability windows, and gap detection end-to-end — genuinely
   engine-wide behavior, not `etl`-local.

## Decision (confirmed with operator)

**Move `SourceConfigTest.java` whole** to `inspecto-engine`'s test tree (rename package from
`com.gamma.etl` to something under `acquire` or a new `integration` package — TBD at implementation
time, pick whichever reads more naturally next to the acquire/event test suites it's actually
exercising). Do **not** split it into etl-only vs. integration slices — the operator chose the
simpler, less surgical option. Consequence: once extracted, `fp-etl` ships with **no direct test
coverage of its own** beyond whatever `ConfigFromMapTest` covers post-trim. That's an accepted
tradeoff, not an oversight — record it in `reactor.md` once shipped so it isn't mistaken for a gap.

## Steps

1. Read `ConfigFromMapTest.java` fully; decide trim vs. relocate for its enrich/job/util imports.
2. Move `SourceConfigTest.java` to `inspecto-engine/src/test/java/com/gamma/<acquire-or-integration>/`,
   fix its package declaration, resolve the `PipelineConfig`/`PipelineConfigBatchTest.miniSchema()`
   reference (currently same-package convenience — will need an import or visibility check once moved).
3. Re-run the etl test-import grep — must return empty before creating the new module.
4. Stand up `fp-etl` (new leaf module, pom below `inspecto-sql`/`inspecto-etl` per the reactor's
   existing module conventions — see `reactor.md` for the pattern used for `fp-util`/`fp-sql`).
5. Move `com.gamma.etl` main + `ConfigFromMapTest` (post-trim) into it.
6. Apply playbook rules 7–8 from `reactor.md` before declaring done: re-scan for `{@link}`/`{@code}`
   javadoc FQNs (not compile edges, but worth knowing about) and check for resources that name an
   `etl` class by FQN (e.g. `logback.xml` appenders) — WS-D was bitten by exactly this once.
7. Full reactor `mvn -o clean test` (background/long-timeout — ~2.5 min) via `verify-runner`. Baseline
   to match: 1884 tests, 0 failures/errors (count will shift slightly with the test move — confirm net
   test count is unchanged, not just "green").
8. Update `reactor.md` (module count, new leaf, the "etl has no direct test coverage" note),
   `BACKLOG.md` §5 (mark shipped), `PROJECT_NOTES.md` module table.
9. On completion, distill + archive this plan per the doc lifecycle (root `CLAUDE.md`).

## Non-goals

- Not touching `fp-acquire` extraction (separate, listed next in `BACKLOG.md` §5) — only unblocked
  by this, not part of it.
- Not splitting `SourceConfigTest` — explicitly declined by the operator this round.
