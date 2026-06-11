# v4.x Refactoring Blueprint — Generics, Functional Injection, Class Consolidation

> **Status: Phase 1 implemented** (2026-06-11, all 417 tests green): `Csv`, `CsvLedger<T>`,
> `BoundedHistory<T>`, `FileWalker` shipped; the three audit writers consolidated
> (`JobAuditWriter` deleted, folded into `JobService`); both RFC4180 read loops and both
> manifest readers unified on `Csv`; all five recursive walkers on `FileWalker`;
> `FilesystemResourceLoader`/`MapResourceLoader` folded into `ResourceLoader` static factories.
>
> **Phase 2 implemented** (2026-06-11, all 417 tests green): shared ingest-tail seams on
> `BatchIngestStrategy` (`writeAndTrace` ×4 sites, `partitionColumns` ×3, `consolidatedBaseName`
> ×3, `unionAll` ×2) and `ParserSpec` (selector derivation + error-file path) shared by both CSV
> ingesters. **Phase-2 deviations:** a monolithic `BatchOrchestrator.run()` was rejected — the
> strategies' six internal paths place the write step differently (inside the sink in generation
> mode, per chunk in chunked mode), so the shared tail was extracted as composable statics instead.
> The temp-db try/catch wrapper stays per-site: each site's FAILED outcome depends on per-site
> partial accumulator state, and a functional wrapper would force holder-array ceremony into the
> engine's most correctness-critical code. Also: §3.1's selector-hoisting was found already
> implemented in `CsvIngester` (pre-existing).
>
> **Phase 3 implemented** (2026-06-11, all 417 tests green): `LockingRunner` (blocking +
> skip-if-busy modes) replaces the hand-rolled per-name lock maps in `JobService` and
> `EnrichmentService`; `CsvIngester.RowFilter` reuses per-ingest `Matcher`s instead of
> allocating one per row × pattern; `stripExtensions` uses static compiled `Pattern`s.
> **Phase-3 deviation:** `SourceService` was dropped from the run-loop unification — it uses one
> *global* `ingestLock` (a plain cross-entrypoint mutex with no record/skip pattern), so the
> blueprint's three-service claim was wrong; only the two per-name services share the mechanics.
> §3.1's appender batching was not pursued: the DuckDB `Appender` API is inherently per-cell, and
> the selector hoist (already present) removed the measured cost. **All phases complete.**
> **Phase-1 deviations:** the §4 nested-type folds for #8 (inspector micro-types),
> #11 (catalog records) and #12 (job records) were **dropped** — on inspection those types sit on
> `@PublicApi`/SPI surfaces (`Description` is in the `DescriptionProvider` SPI signature,
> `JobRun` is returned by `JobService`, `IngestOutcome`/`MemberAudit` are in the strategy SPI),
> so nesting them violates the API-stability invariant below. Phases 2–3 not started. Grounded in a code audit of `file-processor/src/main/java`
> on branch `4.x` (~128 classes). Constraints honored throughout: **core stays zero-new-dependency**
> (no Spring, no DI framework — JDK functional interfaces only), `@PublicApi` surfaces and on-disk
> audit/CSV formats are **byte-compatible**, and the v3.9/v3.11 seams (`OutputFormat`,
> `TransformCompiler`, `BatchIngestStrategy`, `StreamingFileIngester`) are built on, not replaced.

---

## 1. Architectural summary — before vs. after

| | Before | After |
|---|---|---|
| Class count | ~128 | ~105 (−23) |
| Audit CSV writing | 3 near-identical writers (`BatchAuditWriter`, `EnrichmentAuditWriter`, `JobAuditWriter`), each with its own quoting helper and header-on-create boilerplate | 1 generic `CsvLedger<T>` + per-row-type codec functions |
| Audit CSV reading | The same 20-line RFC4180 header-map loop copy-pasted in `EnrichmentAuditReader`, `FileStatusStore`, `FileOrganizer`, `FileBackup` | 1 static `Csv` utility |
| Run loops | 3 services (`SourceService`, `EnrichmentService`, `JobService`) each hand-roll lock → execute → audit → metrics → unlock | 1 generic `LockingRunner` with injected `Task<C,R>` / `Consumer<R>` hooks |
| Batch orchestration | `CsvBatchStrategy` (443 LOC) and `StreamingPluginBatchStrategy` (309 LOC) duplicate the temp-db → ingest → transform → partition → lineage tail | shared `BatchOrchestrator` tail; strategies shrink to their genuinely different ingest heads |
| `com.gamma.util` | 17 classes; 6 copies of the same parallel directory walk, 2 copies of the same hardcoded date regex, 8 copies of dry-run banners, two pairs of near-duplicate commands | ~9 classes; one `FileWalker` (behavior injected via `Consumer<Path>` / `Predicate<Path>`), one tar command class, one CSV utility |
| Hot ingest loop | per-cell appends with bounds-branch per cell, per-row `Matcher` allocation in filters | precomputed selector mapping, reused matchers (5–15% est. ingest gain on the Java-parser path) |

**Shape of the change:** the engine keeps its existing layering (etl ← inspector ← service ← control)
but the *cross-cutting mechanics* — CSV ledgers, locking run loops, parallel walks, table naming —
move into small generic/functional components that each layer parameterizes instead of re-implementing.

---

## 2. Refactored code templates

### 2.1 `CsvLedger<T>` — one generic append-only CSV ledger (replaces 3 writers)

Evidence: identical `q()` quoting helper and header-on-first-create boilerplate in
`etl/BatchAuditWriter.java:104–159`, `enrich/EnrichmentAuditWriter.java:103–136`,
`job/JobAuditWriter.java:33–50`. Each also reopens a `FileWriter` per append.

```java
/** Append-only, header-bearing CSV ledger. Row type T is mapped by an injected codec. */
public final class CsvLedger<T> {
    private final Path file;
    private final String header;
    private final Function<T, String[]> codec;   // injected row mapping — the ONLY per-type code

    public CsvLedger(Path file, String header, Function<T, String[]> codec) {
        this.file = file; this.header = header; this.codec = codec;
    }

    public synchronized void append(T row) throws IOException {
        boolean fresh = !Files.exists(file);
        Files.createDirectories(file.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(file, UTF_8, CREATE, APPEND)) {
            if (fresh) { w.write(header); w.newLine(); }
            w.write(String.join(",", quoteAll(codec.apply(row)))); w.newLine();
        }
    }

    private static String[] quoteAll(String[] cells) {           // the one canonical q()
        for (int i = 0; i < cells.length; i++)
            cells[i] = cells[i] == null ? "" : '"' + cells[i].replace('"', '\'') + '"';
        return cells;
    }
}
```

Usage — the three writers become declarations, not classes:

```java
// in BatchAuditWriter (now a thin facade) — exact same columns/order as today
private final CsvLedger<BatchRow> batches = new CsvLedger<>(batchesCsv, BATCH_HEADER,
    b -> new String[]{ b.batchId(), String.valueOf(b.members()), ..., b.error() });
```

`CommitLog` stays separate — its fsync-per-line durability contract (`FileChannel.force(true)`,
`etl/CommitLog.java:107`) is a different guarantee and must not be merged into a buffered ledger.

### 2.2 `Csv` — one RFC4180 read utility (kills 4 copy-pasted parse loops)

Evidence: byte-identical header-map loops at `enrich/EnrichmentAuditReader.java:77–86`,
`service/FileStatusStore.java:118–127`, plus reader-builder boilerplate in
`util/FileOrganizer.java:149` and `util/FileBackup.java:82`. This loop is also where the two
backslash-preservation bug fixes (`9bfe10f`, `b5c200f`) had to be applied twice — the strongest
argument for a single copy.

```java
public final class Csv {
    private Csv() {}

    /** Read a header-bearing CSV into header→value maps (RFC4180: backslashes preserved). */
    public static List<Map<String, String>> readRows(Path file) throws IOException { ... }

    /** Streaming variant — inject the row consumer instead of materializing the list. */
    public static void forEachRow(Path file, Consumer<Map<String, String>> rowSink) throws IOException { ... }
}
```

### 2.3 `LockingRunner` — one run loop, behavior injected (3 services converge)

Evidence: the lock → execute → record → metrics → unlock sequence at `job/JobService.java:141–174`,
`service/EnrichmentService.java:116–167`, `service/SourceService.java:286–317`.

```java
@FunctionalInterface
public interface Task<C, R> { R run(C ctx) throws Exception; }

/** Per-name serialized execution with injected work, failure mapping, and result recording. */
public final class LockingRunner {
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <C, R> R run(String name, C ctx,
                        Task<C, R> task,                    // the actual work
                        Function<Exception, R> onFailure,   // how this domain represents failure
                        Consumer<R> record) {               // audit + metrics, composed by caller
        ReentrantLock lock = locks.computeIfAbsent(name, k -> new ReentrantLock());
        lock.lock();
        try {
            R result;
            try { result = task.run(ctx); }
            catch (Exception e) { result = onFailure.apply(e); }
            record.accept(result);
            return result;
        } finally { lock.unlock(); }
    }
}
```

Each service keeps its domain types — only the mechanics unify:

```java
// EnrichmentService
runner.run(job.name(), scope,
    s -> engine.recompute(job, s),
    e -> RunRow.failed(job.name(), trigger, e),
    r -> { auditWriter.append(r); metrics.recordEnrichment(r); });
```

Companion micro-utility, same motivation (duplicated `Deque` + `synchronized` blocks at
`job/JobService.java:182–186, 211–229`):

```java
public final class BoundedHistory<T> {
    private final Deque<T> items = new ArrayDeque<>();
    private final int max;
    public BoundedHistory(int max) { this.max = max; }
    public synchronized void add(T t) { items.addFirst(t); while (items.size() > max) items.removeLast(); }
    public synchronized List<T> all() { return List.copyOf(items); }
    public synchronized Optional<T> latest() { return Optional.ofNullable(items.peekFirst()); }
}
```

### 2.4 `BatchOrchestrator` — unify the strategy tails, inject the heads

Evidence: `inspector/CsvBatchStrategy.java:65–67, 121–129, 142–158` vs.
`inspector/StreamingPluginBatchStrategy.java:105–107, 265–267, 270–286` — identical
temp-db lifecycle, `configure(conn, cfg)`, transform → partition-write → lineage tail.
The *heads* (how rows get into DuckDB) genuinely differ; everything after is one code path.

```java
/** What a strategy must produce: per-segment staged tables, ready to transform. */
@FunctionalInterface
interface StagedIngest {
    /** Ingest batch members into staging table(s); return segment-key → staged table name. */
    Map<String, String> stage(Connection conn, Batch batch, PipelineConfig cfg) throws Exception;
}

final class BatchOrchestrator {
    /** The shared tail both strategies currently duplicate. */
    IngestOutcome run(Batch batch, PipelineConfig cfg, StagedIngest head) throws Exception {
        try (Connection conn = openTempDb(cfg)) {                 // one lifecycle
            configure(conn, cfg);
            Map<String, String> staged = head.stage(conn, batch, cfg);   // injected difference
            List<PartitionOutput> outputs = new ArrayList<>();
            for (var e : staged.entrySet()) {
                String transformed = transformer.apply(conn, e.getValue(), cfg, e.getKey());
                outputs.addAll(partitionWriter.write(conn, transformed, cfg, e.getKey()));
            }
            return lineage.collect(conn, batch, outputs, cfg);    // one lineage/audit path
        }
    }
}
```

`CsvBatchStrategy` and `StreamingPluginBatchStrategy` remain (the `BatchIngestStrategy` SPI is
public) but shrink to their `StagedIngest` lambdas plus head-specific quarantine logic —
roughly −230 duplicated lines. Alongside this, lift the scattered table-name literals
(`"raw_input"`, `"transformed"`, `"raw_f"+srcId`, `"__src_id"` — `CsvBatchStrategy:91,123,142`,
`DuckDbRecordSink:41,120`, `DataTransformer:93`, `PartitionWriter:74`) into one `TableNames`
constants holder. A full `TableNaming` strategy interface is **not** warranted — nothing varies.

### 2.5 `ParserSpec` — shared parse-plan for the two CSV ingesters

Evidence: `etl/CsvIngester.java:83–94` and `etl/DuckDbCsvIngester.java:193–199, 274–276` duplicate
selector-index parsing, max-selector computation, skip-line math, and error-file path construction.

```java
/** Everything both ingesters derive from (file, schema, cfg) — computed once per ingest. */
record ParserSpec(int[] selectorIdx, int maxSelector, int skipLines, Path errorFile) {
    static ParserSpec from(Path file, Map<String, Object> schema, PipelineConfig cfg) { ... }
}
```

### 2.6 `FileWalker` — one parallel walk, behavior injected (6 copies → 1)

Evidence: the recursive `walkParallel` + `Phaser` + virtual-thread shape repeated in
`util/FileOrganizer.java:176–189`, `TarArranger.java:99–110`, `TarExtractor.java:71–83`,
`TarInboxPreparer.java:120–125`, `IntegratedProcessor.java:64–76`, `FileMoverByDate.java:56–71`.
`VirtualThreadRunner` already centralized the submit mechanics; this finishes the job.

```java
public final class FileWalker {
    private FileWalker() {}

    /** Walk root recursively on virtual threads; apply action to every file matching filter. */
    public static void walk(Path root, Predicate<Path> filter, Consumer<Path> action) {
        ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
        Phaser phaser = new Phaser(1);
        walkDir(root, filter, action, ex, phaser);
        phaser.arriveAndAwaitAdvance();
        ex.shutdown();
    }
    // walkDir: directory → resubmit, file → filter/action; IOException → log + count, never abort
}
```

The util commands become *parameterizations*: `FileOrganizer` is
`walk(base, wanted::matches, this::copyToInbox)`; `TarArranger`'s scan is
`walk(base, TarUtil::isTarGz, this::copyOneTar)`. Date extraction standardizes on
`TarUtil.extractDate()` — deleting the `cbs_cdr_adj_(\d{8})_` regex duplicated verbatim in
`FileMoverByDate:22` and `IntegratedProcessor:26`.

---

## 3. Performance — heavy-processing paths

The DuckDB-native path (v3.11 Appender, ~75× over row inserts) is already fast; these target the
**Java-parser fallback** (messy files) and the audit paths.

1. **Per-row append loop** (`CsvIngester.java:234–239`): the per-cell
   `appender.append(idx < row.length ? row[idx] : "")` branch runs ~rows × columns times
   (120M+ iterations on a 10M-row file). Precompute the selector mapping into a reusable
   `String[] out` buffer filled by one tight loop, then hand the whole row to the sink.
   Pair with §2.5 so the mapping is built exactly once per ingest. *Est. 5–15% on this path.*
2. **Row-filter matcher allocation** (`CsvIngester.java:289–325`): `r.matcher(v).find()` allocates
   a `Matcher` per row per pattern. Batches are single-threaded per ingest → hold reusable
   `Matcher` instances in the (per-ingest) `RowFilter` and `reset(v)` them.
3. **Audit append I/O** (all three writers): each append opens a fresh `FileWriter`
   (open → write → close per row). With `CsvLedger` keeping a `BufferedWriter` open per run
   (flush per append; durability stays with `CommitLog`'s fsync), batch-heavy runs stop paying
   one file-open syscall pair per audit row.
4. **Filename normalization** (`CsvIngester.java:278`): chained `replaceAll` regexes per file —
   fine per-file, but hoist the compiled `Pattern`s to statics so they're not recompiled.
5. **Non-goals:** don't genericize `PartitionWriter`/`IngestOutcome` with type parameters
   (`<P extends PartitionOutput>` etc.) — single implementations, no variance, pure ceremony.
   Generics earn their place only where two+ real instantiations exist (ledger, history, runner).

---

## 4. Class reduction list

| # | Action | Classes before | After | Net |
|---|---|---|---|---|
| 1 | `BatchAuditWriter` + `EnrichmentAuditWriter` + `JobAuditWriter` → `CsvLedger<T>` (writers become thin facades; `JobAuditWriter` folds into `JobService` entirely) | 3 | 2 + 1 generic | −1, ~−150 LOC |
| 2 | RFC4180 read loops (4 copies) → `Csv` utility | 0 new dupes | +1 | ~−60 LOC |
| 3 | `IntegratedProcessor` + `FileMoverByDate` → absorbed by `TarExtractor` + `FileOrganizer` with injected `Predicate<Path>` filters (they are 95% identical to their siblings; only the filename filter differs) | 2 | 0 | −2 |
| 4 | `TarArranger` + `TarInboxPreparer` + `TarExtractor` → one `TarCommands` class (three static entry points; `copy-tars`/`extract`/`prepare-inbox` CLI contract unchanged) | 3 | 1 | −2 |
| 5 | 6 × `walkParallel` copies → `FileWalker` (absorbs `VirtualThreadRunner`) | 1 | 1 | ~−180 LOC |
| 6 | `LockingRunner` + `BoundedHistory<T>` replace hand-rolled loops in 3 services | 0 | +2 | ~−100 LOC |
| 7 | `BatchOrchestrator` + `StagedIngest` extract the shared strategy tail | 0 | +2 | ~−230 LOC |
| 8 | Inspector micro-types (`MemberAudit`, `SinkFlushException`, `IngestOutcome`) → nested types of `BatchIngestStrategy` (package-private surface) | 3 | 0 (nested) | −3 |
| 9 | `ParserSpec` shared by both CSV ingesters | 0 | +1 | ~−100 LOC |
| 10 | `config.io`: `FilesystemResourceLoader` + `MapResourceLoader` (1 KB each) → static factories on `ResourceLoader` (`ResourceLoader.filesystem()`, `.ofMap(map)`) returning lambdas — it's a one-method interface | 2 | 0 | −2 |
| 11 | `catalog`: `Provenance`, `ConfigSource`, `Description` (≤2 KB value types used only within the graph build) → nested records of `SemanticModel` | 3 | 0 (nested) | −3 |
| 12 | `job`: `IngestJob`/`EnrichJob`/`ReportJob`/`MaintenanceJob` stay (genuine `Job` polymorphism) but `JobRun`/`JobResult` (0–1 KB) nest into `Job` | 2 | 0 (nested) | −2 |
| 13 | `util.DryRun` banner helper folded into `MainApp` (8 inline copies → 1 call) | 0 | 0 | ~−20 LOC |

**Net: ~128 → ~105 classes, ~−1,200 duplicated LOC**, with every eliminated class either folded
into its only consumer or replaced by a generic it now parameterizes.

---

## 5. Sequencing & safety

1. **Phase 1 (pure mechanics, zero behavior change):** `Csv`, `CsvLedger`, `FileWalker`,
   `BoundedHistory`, nested-type folds (#2, #5, #6-history, #8, #10–#12). Golden-file tests:
   byte-compare audit CSVs before/after.
2. **Phase 2 (engine):** `ParserSpec`, then `BatchOrchestrator` behind the existing
   `BatchIngestStrategy` SPI. The live-smoke + Vitest/e2e suites and the `ControlApi` tests
   already cover the observable surface.
3. **Phase 3 (services + perf):** `LockingRunner`, hot-loop fixes (§3.1–3.2) with a JMH-style
   before/after on a representative messy-CSV fixture.
4. **Invariants:** on-disk CSV column order/quoting unchanged; `CommitLog` fsync semantics
   untouched; `@PublicApi` types keep signatures (facades delegate); `.toon` config surface
   unchanged; core adds **no dependency**.
