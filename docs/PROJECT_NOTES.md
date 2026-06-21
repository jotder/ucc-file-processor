# Project Notes — durable, non-obvious knowledge (Inspect-agent)

> **What this is.** The consolidated, repo-local home for durable project knowledge that isn't
> obvious from the code or git history — gotchas, conventions, key decisions, and a map to the
> authoritative docs. Consolidated from scattered per-user agent memory on **2026-06-19**.
>
> **Where things live:**
> - **This file** = durable cross-cutting knowledge + a pointer map (below).
> - **Authoritative per-topic docs** = `docs/` (see the map). Don't duplicate them here.
> - **Live / in-flight working state** = `SESSION_STATUS.local.md` (gitignored, not here).
> - **Machine-specific + personal-workflow detail** = the gitignored `.claude/skills/*` (local).
>
> Point-in-time claims (file:line, counts, "as of") may drift — **verify against current code** before
> asserting. Update this file when a durable fact changes.

---

## 1. Identity & module map

**Inspect-agent** (formerly *Inspecto* / *UCC File Processor*; repo `C:/sandbox/ucc-file-processor`). Java 26 / Maven
multi-module · embedded **DuckDB** · **TOON** config · OpenCSV. Mainline = `master`; current release line
= `4.x`. Editions = build flavors (see below), **never branches**.

Module dirs were renamed 2026-06-12; **artifactIds were NOT renamed** (hence dir ≠ artifactId):

| Dir | Role | artifactId / jar |
|---|---|---|
| `inspecto/` | engine + control plane (lean core) | `file-processor` / `file-processor.jar` |
| `inspecto-connectors/` | remote connectors (SFTP/FTP/FTPS/DB), all network deps | `file-processor-connectors` |
| `inspecto-agent/` | optional AI assist skills (on `agent-kernel`) | `file-processor-agent` |
| `inspecto-agent-hosted/` | hosted model providers (omitted from air-gapped builds) | `file-processor-agent-hosted` |
| `inspecto-ui/` | Angular SPA (gamma/Fuse template), serves from the engine | — (npm; dev :4204) |

Consumes `agent-kernel` 1.0.0 (1.1.0 available; bump optional, Abstain-only ⇒ no behavior change).

---

## 2. Authoritative docs map (go here first)

| Topic | Doc |
|---|---|
| Production investigation (process/events/metrics/state/`-D` flags/Control API/troubleshooting) | [`ADVANCED_GUIDE.md`](ADVANCED_GUIDE.md) — **living doc** |
| Flow-graph design (IR, lift, validator, executor, registry, T-checklist §14) | [`flow-graph-design.md`](flow-graph-design.md) |
| Live execution of authored flows (`JobType.FLOW`, T32) | [`flow-live-execution-plan.md`](flow-live-execution-plan.md) |
| Data acquisition framework (Phases A–F, connectors, dedup, watermarks) | [`data_acquisition_framework.md`](data_acquisition_framework.md) |
| All TOON config keys | [`configuration.md`](configuration.md) |
| Editions (Personal/Standard/Enterprise = build flavors) | [`EDITIONS.md`](EDITIONS.md) |
| Branch & release policy (versions=branches; merge-forward; SemVer+CC) | [`BRANCHING.md`](BRANCHING.md) |
| Parsing/grammar | [`parsing-options-reference.md`](parsing-options-reference.md), [`delimited-grammar-design.md`](delimited-grammar-design.md) |
| Perf benchmarks & tuning | [`performance.md`](performance.md) |
| Strategy / roadmap / stakeholder decks | [`roadmap/`](roadmap/) |
| Curated index of all current docs | [`INDEX.md`](INDEX.md) |

---

## 3. Key decisions (the "why", not derivable from code)

- **Editions are build flavors, never git branches.** One source tree (`master` = auth-free common core);
  edition-only code in its own Maven module (`inspecto-security` for Standard/Enterprise), assembled via
  `-Pedition-*` profiles + `ServiceLoader` + `-D` flags. A fix lands once in core; all editions inherit it
  at build. Rationale: branches would force perpetual cross-line cherry-picking. → [`EDITIONS.md`](EDITIONS.md).
- **All auth removed from `master`/common core (2026-06-16).** Personal is genuinely auth-free (every
  ControlApi route open; SPA boots to `/dashboard`; no token paste/guards). Standard re-adds auth out-of-band
  via the (not-yet-built) `inspecto-security` module behind an `Authenticator` SPI — OIDC resource-server
  (Nimbus+JWKS) + RBAC/ABAC; Angular uses OIDC Auth-Code+PKCE. **The `-Dassist.write.root` 503 write-gate is
  SEPARATE from auth and stays.**
- **Keep the core lean.** All network deps live in `inspecto-connectors`; hosted-AI SDKs in
  `inspecto-agent-hosted` (physically absent from air-gapped builds). The zero-new-dep rule was retired
  2026-06-13 (logback replaced slf4j-simple, user-approved) — still no gratuitous deps.
- **Flow-graph track is `master`-only** (`feat:` → master; empty merge-forward set; retired lines untouched).

---

## 4. Cross-cutting gotchas (the expensive-to-rediscover ones)

- **TOON schema serialization** — `ConfigCodec.toToon(map)` does **not** emit tabular-array format. A schema
  whose `fields`/`rules` are Java-constructed `List<Map>` round-trips as nested maps, and the TOON parser then
  throws `Array length mismatch: declared N, found 0`. In any test that writes a schema file for TOON loading,
  write the schema as an **inline TOON string** (`fields[N]{name,selector,type}: …`), not via `toToon(schemaMap)`.
  Round-trip only works when the map was originally JToon-decoded (e.g. a `SchemaSelector` loaded from a real
  `.toon`).
- **DuckDB reserved words** — `day` is a keyword: alias it (`run_day`) in SQL; quote `"trigger"` too. Watch for
  these whenever generating SQL with date/trigger columns.
- **`BatchEvent.pipeline()` is the LOWERCASED pipeline name** (`cfg.identity().pipelineName()`). Any name
  matching against it (triggers, `runPipeline`, `pathFor`) must use the lowercased id — tests call
  `runPipeline("up_stream")`, not `"UP_STREAM"`.
- **Synchronous bus + `ingestLock` ⇒ deadlock** — the event bus publishes **synchronously on the publishing
  thread**, and `ingestLock` is held during a cycle. An event-triggered run dispatched **inline** would
  deadlock. Hand off to an off-bus virtual-thread pool (`triggerWorkers`) — same reason `JobService` hands off.
- **`PartitionWriter` requires non-empty partition columns** (it emits `PARTITION_BY (...)`). The unpartitioned
  single-file `COPY` path lives in `PartitionSinkWriter`; the legacy writer is untouched.
- **Flow seed = exactly one `source_store`** in Phase-A live execution (rejects 0 or >1; multi-source merge is
  the `transform.merge` path).

---

## 5. Engine seams & performance (durable; current in `inspecto/`)

- **Single ingestion SPI:** `StreamingFileIngester` (emit-based) is the **only** ingestion SPI. Per-batch the
  framework picks **union** mode (many small files → per-member views `UNION ALL` → one transform/write pass) vs
  **generation** mode (one huge file → bounded flushing). Selector `processing.streaming.large_file_bytes`
  (default 256 MB); generation budget `processing.streaming.flush_records` (default 5,000,000).
- **DuckDB `Appender` ingest** (vs JDBC `executeBatch`) ≈ **75× faster** (1M-row bench ~6.9k → ~510k rows/s).
- **Modularity seams** (behavior-preserving; SQL/`.toon`/on-disk output unchanged): `OutputFormat`
  (enum-as-strategy), `TransformCompiler` (`transformType → ColumnRule`), `BatchIngestStrategy` (Csv/Plugin →
  typed `IngestOutcome`; `BatchProcessor` is a thin coordinator).
- **Auto-derive `duckdb_threads`** — `DuckDbUtil.effectiveWorkerThreads`: `0`=auto `max(1,cores/concurrency)`,
  `>0`=verbatim, `-1`=DuckDB per-core default; single-batch→all cores. Avoids the threads×cores oversubscription
  stall (~+15% tax, widens with cores).
- **Quarantine semantics:** throw → `QUARANTINED_UNREADABLE`; 0 emitted rows → `QUARANTINED_MISMATCH`;
  `SinkFlushException` → fail the batch.
- **`com.gamma.util` CLI cluster** (~11 `main()` tools: `MainApp`, `TarExtractor`, …) sits at low coverage and is
  **kept by decision** (self-contained; `MainApp` is wired into `package.ps1`/ops). Tested engine+control-plane
  is ~86%. Long-term: extract the CLI cluster to its own module. → [`performance.md`](performance.md).

---

## 6. inspecto-ui conventions (for adding panes)

Angular 21 · Material/Tailwind · ag-Grid 35 · Chart.js · AntV G6 5. **Read the `angular-ui` skill before
touching `inspecto-ui/`.** Highlights (full detail there):

- **API clients** in `src/app/inspecto/api/` (barrel `index.ts`): `@Injectable({providedIn:'root'})`,
  `inject(HttpClient)`, `apiUrl('/path')` + `toParams({...})` from `api-base.ts`; interfaces inline in the
  service. **App is auth-free** — no interceptor/guard/token; only `errorInterceptor` (no 401 branch).
- **Feature panes** in `src/app/modules/admin/<feature>/`, **signals + OnPush**. A pane can be reused across
  routes via `ActivatedRoute.snapshot.data` (Cases/Issues = one `ObjectsComponent`).
- **Second "lens" on a pane = `mat-button-toggle-group`, NOT a new nav item** (Flows `flow|combined`, Jobs
  `schedules|reporting`). Factor shared blocks into `<ng-template>` + `*ngTemplateOutlet`.
- **No hardcoded colors** — CI guard `npm run lint:tokens` fails on hex/`rgb()`/`levelClass`-style helpers under
  `inspecto/**` + `modules/admin/**` (allowlist: `chart-tokens.ts`, `status-badge.component.ts`). Status/level
  colors come from `<inspecto-status-badge>` only. `rgba(var(--gamma-…))` is allowed.
- **a11y gate** — `expectNoA11yViolations(el)` (`inspecto/testing/a11y.ts`, axe-core) in component specs; runs in
  CI. Manual WCAG: `docs/ui/accessibility-audit.md`.
- **Shared design system**: `status-badge` / `empty-state` / `skeleton` / `grid` (+ `noRowsOverlay`) /
  `connectivity-banner`. Living gallery at `/design`.
- **ag-Grid gotchas:** (a) action/string cell renderers don't render on first paint with static `rowData` →
  call `refreshCells({force:true, columns:[…]})` on `(firstDataRendered)`/`(rowDataUpdated)`; (b) the shared
  theme MUST be the gamma-token `themeQuartz.withParams(GAMMA_GRID_PARAMS)` (`app/inspecto/grid/index.ts`) — never
  bare `themeQuartz`; (c) off-screen (virtualized) columns aren't in the DOM until you scroll horizontally — set
  `scrollLeft` before asserting in preview.
- **`@if/@else` + `mat-icon` button ⇒ NG8011** (icon won't project). Keep always-on icon buttons outside the
  branch, or make the branch's only root the button.
- **Authenticated file download** — go through `HttpClient` (`responseType:'text'|'blob'`) + `Blob` +
  `createObjectURL` + transient `<a download>`; a plain anchor `href` doesn't carry headers.
- **Live tail** — `visibleInterval(ms)` (`api/auto-refresh.ts`, pauses on hidden tab); hold/resubscribe/unsub;
  `silent` flag avoids loader flash. `DEFAULT_REFRESH_MS=15000`.
- **Connectivity** — `ConnectivityService` (status 0 ⇒ unreachable) + `<inspecto-connectivity-banner>` owns the
  "backend down" UX (don't add per-screen toasts; **503 ≠ backend-down**). Banner host needs
  `:host{display:contents}` so it doesn't steal layout width.
- **Optimistic mutations** — `optimisticMutate({apply,commit,reconcile,rollback,onError})` (`inspecto/api/
  optimistic.ts`); reassign arrays (`rows=[...]`) so the grid re-renders.
- **G6 graph** — reuse `modules/admin/catalog/graph-view.component.ts` (`@Input data`, `@Output nodeClick`);
  nodes are canvas-drawn (not DOM) → verify inspector logic via unit test, not preview clicks. Flow graph data
  via `flow-graph.ts#toFlowG6Data`.
- **Dev**: `npm start` (`ng serve` :4204); `proxy.conf.json` maps `/api` → `:8080`. `.claude/launch.json`
  defines both preview servers.

---

## 7. Related sandboxes (separate repos — pointers only)

- **agent-kernel** (`C:/sandbox/agent-kernel`) — reusable agent lib; **1.1.0 released**; Inspecto on 1.0.0.
- **CVVE** (`C:/sandbox/agentic-doc-validation`) — kernel's 3rd consumer; first real `HumanHandoff` driver.

(Detailed progress for these lives in the per-user agent memory, not in this repo — they are different projects.)
