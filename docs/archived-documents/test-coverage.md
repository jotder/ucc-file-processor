# Test Coverage Assessment

Measured with JaCoCo 0.8.13 on **v3.9.0** (2026-06-01). Reproduce with:

```
mvn -Pcoverage test
# reports: inspecto/target/site/jacoco/index.html
#          inspecto-agent/target/site/jacoco/index.html   (and jacoco.csv for parsing)
```

Since v3.9.0 the `coverage` profile is declared in **both** reactor modules, so a single
`mvn -Pcoverage test` instruments the core engine *and* the optional assist agent in one pass.

**466 tests** (346 core, 1 skipped; 120 agent), full reactor ~38 s CPU-only (no Ollama). The
v3.9.0 engine-modularity pass added the 14 `OutputFormatTest` + `TransformCompilerTest` characterizations.

## Summary

| Scope | Line coverage | Branch coverage |
|---|---|---|
| **Core ETL data-path** (`etl` + `inspector` + `ingester`) | **87.4%** (1308/1496) | **76.0%** (512/674) |
| **Core engine** (everything except the `util` CLI tools) | **86.5%** (3857/4458) | 72.2% (1679/2325) |
| **Assist agent** (`file-processor-agent`) | **85.8%** (1170/1364) | 62.9% (628/999) |
| Pre-ETL utilities (`util` CLI tools) | ~5.7% (86/1500) | ~7.9% (51/643) |
| Core *total* (incl. `util`) | 66.2% (3943/5958) | 58.3% (1730/2968) |
| Whole project (core + agent) | 69.8% (5113/7322) | 59.4% (2358/3967) |

The whole-project and core-*total* numbers are misleading: both are dragged down by the pre-ETL
utility CLI tools, which have essentially no automated tests (a conscious, documented tradeoff —
see below). The numbers that matter — the production ETL data-path and the assist agent — are
**~86–87% line**.

## Core engine — well covered

The data-path and control-plane classes are fully or near-fully exercised. Representative high points
(line / branch): `LineageCollector` 100%/100%, `report.*` 98%/77%, `config.spec.*` 99%/—,
`metrics.*` 98%/80%, `ingester.*` 94%/91%, `catalog.*` 95%/72%, `sql.*` 90%/76%,
`enrich.*` 89%/68%, `etl.*` 89%/76%, `control.*` 88%/72%.

The v3.9.0 engine-modularity pass ([design-notes D7](design-notes.md#d7--engine-modularity-pass-behavior-injection-seams--done-v390))
added two byte-exact characterization suites — `OutputFormatTest` and `TransformCompilerTest` — that
pin the new injection seams: `OutputFormat` 100%, `TransformCompiler` 98%, `BatchIngestStrategy`/
`IngestOutcome`/`MemberAudit` 100%, `BatchProcessor` 98%, with the extracted `CsvBatchStrategy` (81%)
and `PluginBatchStrategy` (89%) carrying the same error/quarantine-branch gaps the pre-split
`processCsv`/`processPlugin` methods had (no regression). The data-path edged up to 87.4% line.

## Assist agent — well covered, with intentional model-dependent gaps

The agent module is **85.8% line**. Two classes sit low **by design**, not by oversight:

- `agent.model.OllamaModelProvider` (~31%) and `agent.skill.DocRetriever` (~23%) need a **live Ollama
  model / a populated doc index**, which CPU-only CI deliberately avoids (every assist test runs
  against `FakeModelProvider` + deterministic oracles). These would cover under an opt-in integration
  profile with a local model; their low CI coverage is the expected shape of the abstain-safe design,
  not a quality hole.

`agent.skill.AlertRuleValidator` was raised from 57.6% to **100%** (instr + branch) in the v3.9.0
coverage pass via a dedicated `AlertRuleValidatorTest`.

## Pre-ETL utilities — accepted gap (the `util` CLI tools)

`com.gamma.util`'s standalone tools — `TarExtractor`, `FileOrganizer`, `TarArranger`,
`TarInboxPreparer`, `IntegratedProcessor`, `FileBackup`, `MainApp`, `PartitionSummarizer`,
`FileMoverByDate`, `TarUtil`, `ParquetSummarizer` — are operator-run CLI staging tools, invoked
manually **before** the ETL proper. They are at ~5.7% coverage.

This is a **conscious tradeoff, not an oversight**:

- They are a **self-contained cluster**: they call each other and the in-package helpers (`TarUtil`,
  `ToonHelper`, `VirtualThreadRunner`, `DuckDbUtil`), but **nothing in the v3 engine** (`etl`,
  `inspector`, `service`, `catalog`, `enrich`, `job`, `sql`) references any of them. The only
  entrypoint is `MainApp`, which is wired into `inspecto/package.ps1`, `ura.bat`, `ura.sh`,
  and the operations docs — so they are *shipped and supported*, just decoupled.
- They are lower-risk (operator-supervised, idempotent file moves) and high-churn.

> **Note on the package boundary.** `com.gamma.util` also holds engine *helpers* that are well
> covered and load-bearing — `SchemaExtractor` (`SchemaExtractorMergeTest`), `DuckDbUtil`,
> `SqlBuilder`, `ToonHelper`, `VirtualThreadRunner`, `LogSetup`. The "accepted gap" applies only to
> the standalone CLI tools above, not to these helpers.

**If we invest here, start with `TarExtractor`** (recursive archive walking has real edge cases —
873 instructions at 0%), then the summarizers. Caveat for whoever picks this up: `TarExtractor`
writes its `extract_report.csv` / `extract.log` to the **current working directory**, so a test needs
to redirect or clean those up. A cleaner long-term option is to **extract the CLI cluster into its own
module/JAR**, which would both isolate the coverage accounting and keep the lean core fat-JAR leaner —
but that is a deliberate refactor, not a coverage task.

## How to keep coverage honest

- CI runs `mvn test` on every push (`.github/workflows/ci.yml`). To gate on coverage, add
  `mvn -Pcoverage test` plus a JaCoCo `check` rule scoped to the `etl` / `inspector` / `ingester`
  (and ideally the v3 `catalog` / `config` / `sql` / `service` / `control` / agent `skill`) packages —
  **don't let the untested `util` CLI tools set the bar.** A sensible floor is ~80% line on those
  packages.
- The `coverage` profile is opt-in in both modules, so normal builds stay fast.

---

## Historical record — v1.5.x core-ETL coverage pass

> Raised core ETL from 79%/62% to 88%/76% by closing the gaps below. Previously-weak classes after
> that pass: SchemaSelector 96%/75% (was 0%), CsvIngester 92%/74% (was 67%/37%), DataTransformer
> 97%/85% (was 72%/58%), PartitionWriter 94%/88% (was 91%/25%), MarkerManager 78%/77% (was 64%/50%).
> Tests added: `SchemaSelectorTest`, `CsvIngesterSkipLogicTest`, `DataTransformerTransformTypesTest`,
> `PartitionWriterFormatTest`, `MarkerManagerTest`, plus the shared `TestConfigs` builder. The
> data-path has stayed at ~87% line / ~76% branch through the v3 milestones since.
