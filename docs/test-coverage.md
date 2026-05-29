# Test Coverage Assessment

Measured with JaCoCo 0.8.13. Reproduce with:

```
mvn -Pcoverage test
# report: target/site/jacoco/index.html  (and jacoco.csv for parsing)
```

55 tests, full suite ~6 s.

## Summary

| Scope | Line coverage | Branch coverage |
|---|---|---|
| **Core ETL** (`etl` + `inspector` + `ingester`) | **79.0%** (981/1242) | **62.3%** (353/567) |
| **Pre-ETL utilities** (`util` CLI tools) | **2.0%** (29/1456) | 1.5% (9/603) |
| Whole project | 37.4% | 30.9% |

The whole-project number is misleading: it's dragged down by the pre-ETL
utility CLI tools, which have essentially no automated tests. The number that
matters — the ETL pipeline that runs in production — is **79% line / 62%
branch**, which is solid for an I/O-and-SQL-heavy codebase.

## Core ETL — well covered

Fully or near-fully exercised (the data-path classes):

| Class | Line | Branch |
|---|---|---|
| LineageCollector | 100% | 100% |
| PartitionDef | 100% | 92% |
| Identifiers | 100% | 81% |
| BatchPlanner | 98% | 86% |
| QuarantineManager | 95% | — |
| TypedRecordIngester | 94% | 91% |
| ManifestStore | 93% | — |
| PartitionWriter | 91% | 25% |
| BatchAuditWriter | 90% | 74% |
| BatchProcessor | 89% | 74% |
| ConfigValidator | 87% | 80% |
| ReprocessCommand | 86% | 71% |
| PipelineConfig | 83% | 60% |

## Core ETL — coverage gaps worth closing

Ranked by risk × exposure:

1. **`PartitionWriter` branch coverage 25%** (line 91%). The lines run, but the
   conditional branches don't: PARQUET-vs-CSV format selection, the compression
   clause, and the error path in the staged-file rename loop. A crash mid-rename
   is exactly the kind of partial-failure the commit-ordering fix cares about,
   and it's untested. **Add: a PARQUET-output write test and a forced-rename-
   failure test.**

2. **`CsvIngester` 67% line / 37% branch.** The big one. Untested branches:
   `skip_tail_lines` footer dropping, `skip_tail_columns` phantom-column strip,
   the adaptive junk/echo-line detection (the half-match heuristic), and the
   error-CSV rejection path. These are the trickiest, most bug-prone parts of
   the codebase and the least covered. **Add: characterization tests for each
   skip/junk/tail/echo path** — also a prerequisite for the parseNext()
   refactor recommended in `performance.md`.

3. **`DataTransformer` 72% line / 58% branch.** `CONCAT_DT` and `FILENAME_DATE`
   transform types are under-tested; the DOUBLE/INTEGER partition casts have
   uncovered branches. **Add: one test per transformType.**

4. **`MarkerManager` 64% line / 50% branch.** The new 24h-throttle path and the
   stale-marker deletion + empty-subdir pruning are partially covered.
   **Add: a test that creates an old marker + sentinel and asserts throttle +
   deletion behaviour.**

5. **`SchemaSelector` 0%.** The multi-schema column-count probe and file-pattern
   fast path are completely untested despite being load-bearing for the
   multi-schema pipelines. **Add: a two-schema selection test (pattern hit +
   column-count fallback).**

## Pre-ETL utilities — accepted gap

`com.gamma.util.*` (TarExtractor, FileOrganizer, FileBackup, SchemaExtractor,
ParquetSummarizer, PartitionSummarizer, IntegratedProcessor, etc.) are
operator-run CLI staging tools, invoked manually before the ETL proper. They're
at ~2% coverage. This is a conscious tradeoff, not an oversight: they're
lower-risk (operator-supervised, idempotent file moves) and high-churn. If we
invest here, start with `SchemaExtractor` (it generates the schema/pipeline
toons every source depends on — a bug there poisons everything downstream) and
`TarExtractor` (recursive archive walking has real edge cases).

## How to keep coverage honest

- CI runs `mvn test` on every push (`.github/workflows/ci.yml`). Consider adding
  `mvn -Pcoverage test` + a JaCoCo `check` rule that fails the build if core-ETL
  line coverage drops below, say, 75%. Gate on the `etl`/`inspector`/`ingester`
  packages only — don't let the untested utilities set the bar.
- The `coverage` profile is opt-in so normal builds stay fast.
