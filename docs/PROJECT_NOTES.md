# Project Notes — durable, non-obvious knowledge

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

**Inspecto** (formerly *UCC File Processor*; repo `C:/sandbox/ucc-file-processor`). Java (core bytecode
`release=24`; agent modules need a **JDK 25+ runtime**; built & bundled on **JDK 26**) / Maven
multi-module · embedded **DuckDB** · **TOON** config · OpenCSV. Mainline = `master`; current release line
= `4.x`. Editions = build flavors (see below), **never branches**.

Module dirs were renamed 2026-06-12; **artifactIds were NOT renamed** (hence dir ≠ artifactId):

Reactor = **13 modules** (build order below; WS-D 2026-07-22 added `inspecto-engine`, then split
`inspecto-etl`, `inspecto-event`, and `inspecto-acquire` out of it the same day, increments 2–4).
Authoritative shape, version management, and the module-extraction playbook:
[`okf/backend/modules/reactor.md`](okf/backend/modules/reactor.md).

| Dir | Role | artifactId / jar |
|---|---|---|
| `inspecto-api/` | dependency-free leaf: the `@PublicApi` annotation | `file-processor-api` |
| `inspecto-util/` | leaf: DuckDB access + CSV/file/tar helpers + `CronExpression` | `file-processor-util` |
| `inspecto-config/` | config spec / codec (TOON) / safety | `file-processor-config` |
| `inspecto-sql/` | sandboxed DuckDB SQL (`SqlSandbox`/`SqlOracle`/`SqlGuard`/`SqlViews`) | `file-processor-sql` |
| `inspecto-etl/` | `com.gamma.etl` — pipeline config + batch ingest (foundation leaf below engine) | `file-processor-etl` |
| `inspecto-event/` | `com.gamma.event`+`metrics` — Operational-Intelligence event store + metrics | `file-processor-event` |
| `inspecto-acquire/` | `com.gamma.acquire` — file/remote acquisition, ledger, stability/gap/retry | `file-processor-acquire` |
| `inspecto-engine/` | the remaining engine cluster (`pipeline`/`job`/`inspector`/… ) below core | `file-processor-engine` |
| `inspecto/` | control plane + composition root (lean core), ships the fat JAR | `file-processor` / `file-processor.jar` |
| `inspecto-connectors/` | remote connectors (SFTP/FTP/FTPS/DB), all network deps | `file-processor-connectors` |
| `inspecto-agent/` | optional AI assist skills (vendored kernel layer + eoiagent transport) | `file-processor-agent` |
| `inspecto-agent-hosted/` | hosted model providers (omitted from air-gapped builds) | `file-processor-agent-hosted` |
| `inspecto-intelligence/` | embedded-intelligence agent (eoiagent-backed) | `file-processor-intelligence` |
| `inspecto-security/` | Standard/Enterprise OIDC auth, `-Pedition-standard` only (not in default `<modules>`) | `file-processor-security` |
| `inspecto-policy/` | Enterprise ABAC policy engine (`AccessDecider` impl), `-Pedition-enterprise` only (= standard + this) | `file-processor-policy` |
| `inspecto-ui/` | Angular SPA (gamma/Fuse template), serves from the engine | — (npm; dev :4204) |

agent-kernel is GONE (discontinued upstream, replaced 2026-07-07): its reasoning layer is vendored at
`inspecto-agent … com/gamma/agent/kernel/**`; model transport is **eoiagent** (`com.eoiagent:*:0.1.0-SNAPSHOT`,
local `.m2` from `C:/sandbox/agent-brainstorm`) — see `docs/superpower/agent-kernel-replacement-plan.md`.

---

## 2. Authoritative docs map (go here first)

| Topic | Doc |
|---|---|
| Production investigation (process/events/metrics/state/`-D` flags/Control API/troubleshooting) | [`ADVANCED_GUIDE.md`](ADVANCED_GUIDE.md) — **living doc** |
| Pipeline-graph design (IR, lift, validator, executor, registry, T-checklist §14) | [`pipeline-graph-design.md`](okf/backend/pipeline-graph/pipeline-graph-design.md) |
| Live execution of authored Pipelines (`JobType.PIPELINE`, T32) | [`live-execution.md`](okf/backend/pipeline-graph/live-execution.md) |
| Data acquisition framework (Phases A–F, connectors, dedup, watermarks) | [`data-acquisition-framework.md`](okf/backend/acquisition/data-acquisition-framework.md) |
| All TOON config keys | [`configuration.md`](okf/backend/config/configuration.md) |
| Editions (Personal/Standard/Enterprise = build flavors) | [`EDITIONS.md`](EDITIONS.md) |
| Branch & release policy (versions=branches; merge-forward; SemVer+CC) | [`BRANCHING.md`](BRANCHING.md) |
| Parsing/grammar | [`parsing-options-reference.md`](okf/backend/config/parsing-options-reference.md), [`delimited-grammar-design.md`](archived-documents/plans-archive/delimited-grammar-design.md) |
| Perf benchmarks & tuning | [`performance.md`](okf/backend/build-run/performance.md) |
| Strategy / roadmap / stakeholder decks | [`roadmap/`](roadmap/) |
| Curated index of all current docs | [`INDEX.md`](INDEX.md) |
| Engineering knowledge bundle (OKF, consolidated 2026-07-07; graphify-indexed) | [`okf/`](okf/index.md) — sections [`frontend/`](okf/frontend/index.md) · [`backend/`](okf/backend/index.md) · [`agentic/`](okf/agentic/index.md) |
| Requirements-of-record + MoSCoW · stakeholder set | [`REQUIREMENTS.md`](REQUIREMENTS.md) · [`stakeholders/`](stakeholders/README.md) |

---

## 3. Key decisions (the "why", not derivable from code)

- **Editions are build flavors, never git branches.** One source tree (`master` = auth-free common core);
  edition-only code in its own Maven module (`inspecto-security` for Standard/Enterprise), assembled via
  `-Pedition-*` profiles + `ServiceLoader` + `-D` flags. A fix lands once in core; all editions inherit it
  at build. Rationale: branches would force perpetual cross-line cherry-picking. → [`EDITIONS.md`](EDITIONS.md).
- **All auth removed from `master`/common core (2026-06-16).** Personal is genuinely auth-free (every
  ControlApi route open; SPA boots to `/dashboard`; no token paste/guards). Standard re-adds auth out-of-band
  via the **`inspecto-security` module (BUILT, W6 2026-07-06** — `OidcAuthenticator` Nimbus+JWKS, `RoleMapper`,
  `KeycloakTokenRelay`; reactor-gated behind the `edition-standard` profile) behind the
  `Authenticator`/`Subject`/`TokenRelay` SPIs (`com.gamma.control`), plus HTTPS (`HttpsServer`) and the BFF
  `/auth/exchange|refresh|logout` routes; Angular uses OIDC Auth-Code+PKCE driven by `bootstrap.features.authMode`
  (no-op on Personal). **The `-Dassist.write.root` 503 write-gate is SEPARATE from auth and stays.**
- **Keep the core lean.** All network deps live in `inspecto-connectors`; hosted-AI SDKs in
  `inspecto-agent-hosted` (physically absent from air-gapped builds). The zero-new-dep rule was retired
  2026-06-13 (logback replaced slf4j-simple, user-approved) — still no gratuitous deps.
- **Flow-graph track is `master`-only** (`feat:` → master; empty merge-forward set; retired lines untouched).
- **Multi-space (multi-project), `master`-only `feat:` track.** One server hosts many isolated **spaces**
  (`-Dspaces.root`, default `./spaces`); each = `spaces/<id>/{config,data,audit,duckdb,flows}` + `space.toon`.
  The ~40-method `@PublicApi` per-instance `CollectorService` is **wrapped, not rewritten**: `SpaceManager` →
  `SpaceContext` → unchanged `CollectorService`. Isolation of the five process-wide singletons (EventLog /
  MetricRegistry `space` label / ConnectionRegistry / StabilityGate / AcquisitionLedgers) is by the **`space`
  SLF4J MDC** (`EventLog.currentSpaceId()`; fallback `"default"` = no MDC = byte-identical single-space). API
  seam: `/spaces/{id}/…` (`ControlApi.dispatch` strips + MDC-binds; un-prefixed → current/default). Space CRUD
  (no restart): `GET/POST /spaces`, `DELETE /spaces/{id}?purge=` (purge = opt-in file removal). **No flat
  fallback** — migrate once via `com.gamma.service.SpaceMigrator`. Editions/auth stay future SPI (no
  `if(edition==)`). → [`configuration.md` §Spaces](okf/backend/config/configuration.md#spaces-multi-project-layout).
  **UI (Stage 7):** `SpacesService` (signals) + a global `spaceInterceptor` rewrite `/api/<p>` →
  `/api/spaces/<id>/<p>` so every feature service stays space-agnostic (no-op single-tenant = byte-identical);
  header space-switcher + `modules/admin/spaces` admin (CRUD + per-space/per-data-source zip export + import
  with dry-run preview + create-from-bundle). The UI tells discover from single-tenant via the additive
  **`GET /spaces/_meta` → `{multiSpace}`** (= `SpaceManager.supportsCrud()`), never by space-list length (a
  fresh discover server returns `[]`). See the `angular-ui` skill §7.

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
- **Per-space `space` MDC must reach EVERY worker thread on the execution path.** Singleton routing reads the
  MDC on the *current* thread, and MDC does NOT cross thread-pool boundaries. Each executor running ingest/commit
  work must `MDC.getCopyOfContextMap()` on the caller + `setContextMap` on the worker + `clear()` in finally —
  `MultiCollectorProcessor.runAll`/`runConfigs` **and** `CollectorProcessor`'s per-batch executor (the batch commit,
  per-batch metrics and event log fire there, not on the poll thread). Miss one and that space's metrics/events
  silently fall back to `"default"`. The `default` space sets NO MDC, so single-space output stays label-free.
- **Hand-authored `.toon` rules (verified live 2026-07-10, `spaces/demo` shakeout):** (1) **No `#` comments
  anywhere** — suffix-scanned loaders (`*_pipeline/_job/_connection/_alert/_queue/…`) strict-reject the file
  ("Multiple primitives at root depth"), and even the lenient registry read mangles comment lines into junk
  keys. Some loaders tolerate them today (template/escalation) — do not rely on it. (2) **Lists need counts**:
  inline `members[1]: operator` or tabular `tiles[3]{widgetId,span}:`; bare `- item` lists fail (exception:
  authored-flow `nodes[n]:` blocks accept `- id:` maps). (3) **Alert rules need an `alert:` wrapper** and
  `severity` ∈ {CRITICAL, INFO, WARNING} — not WARN. (4) **Job-type params are FLAT keys under `job:`**
  (`JobConfig.fromMap` treats unknown keys as params); only `args:`/`bind:` nest. A `params:` wrapper in some
  design-doc sketches is doc-only, not the shipped parser.
- **Authored flows live under `config/flows/` — one dir for both readers (FIXED 2026-07-10):** the UI/HTTP
  authored-pipeline CRUD always wrote `writeRoot()/flows` (= the space's `config/flows/`), but
  `DirSpaceRoot.flowsDir()` pointed `JobService`/the T32 deletion fence at a sibling `spaces/<id>/flows/`, so a
  `type: pipeline` job couldn't resolve a UI-authored flow in multi-space mode. `flowsDir()` now returns
  `config().resolve("flows")`; a top-level `spaces/<id>/flows/` is dead (still tolerated by
  `SpaceLayoutContract` as historical) and new spaces no longer mint it. Regression test:
  `SpaceBootstrapTest.flowJobResolvesAFlowAuthoredUnderConfigFlows`.
- **Pipeline-internal paths resolve against the JVM CWD, NOT the space root.** A pipeline's `schema_file`,
  `grammar`, and `dirs.*` are `Paths.get(...)` in `PipelineConfigParser` with **no rebasing** to `spaces/<id>/`.
  Only the *space discovery* layer (`-Dspaces.root`, `SpaceRoot`) is space-relative. So when configs were moved
  under `spaces/<id>/config/` (`ffbf311`), every in-config path had to be rewritten to repo/bundle-root-relative
  form (`spaces/<id>/config/…`, `spaces/<id>/data/…`) — and the `SpaceMigrator` cannot auto-fix absolute or
  author-relative paths for the same reason. Shipped examples now: `spaces/default` (subscriber + events +
  connections), `spaces/ucc` (voucher; lowercase id `ucc`, display "UCC").

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
  is ~86%. Long-term: extract the CLI cluster to its own module. → [`performance.md`](okf/backend/build-run/performance.md).

---

## 6. inspecto-ui conventions (for adding panes)

Angular 21 · Material/Tailwind · ag-Grid 35 · Chart.js · AntV G6 5. **Read the `angular-ui` skill before
touching `inspecto-ui/`.** Highlights (full detail there):

- **API clients** in `src/app/inspecto/api/` (barrel `index.ts`): `@Injectable({providedIn:'root'})`,
  `inject(HttpClient)`, `apiUrl('/path')` (→ **`/api/v1`** since W7) + `toParams({...})` from `api-base.ts`;
  interfaces inline in the service. Interceptor chain: first-position `v1Interceptor` (shape-guarded envelope
  unwrap), `spaceInterceptor` (space id **after** `/v1`), `errorInterceptor`, and `auth.interceptor` — the
  auth flow is a **no-op on Personal** (OIDC only when `bootstrap.features.authMode` says so, W6d).
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
- **TestBed `{provide: MatDialog, useValue: …}` is silently shadowed** on any pane that imports
  `DataTableComponent` (or anything else importing `MatDialogModule`): the standalone component's
  *standalone injector* re-provides the real `MatDialog` closer than the testing module, so the pane
  injects the real service and `open()` explodes in jsdom (`undefined.push` in material dialog.ts).
  Fix: after `createComponent`, `vi.spyOn(componentInstance['dialog'], 'open').mockReturnValue(...)` —
  spy on the instance the component actually got (see `alerts.component.spec.ts`). Several older specs
  carry the dead-weight provider without noticing because they never call through `open`.
- **Authenticated file download** — go through `HttpClient` (`responseType:'text'|'blob'`) + `Blob` +
  `createObjectURL` + transient `<a download>`; a plain anchor `href` doesn't carry headers.
- **Live tail** — `visibleInterval(ms)` (`api/auto-refresh.ts`, pauses on hidden tab); hold/resubscribe/unsub;
  `silent` flag avoids loader flash. `DEFAULT_REFRESH_MS=15000`.
- **Connectivity** — `ConnectivityService` (status 0 ⇒ unreachable) + `<inspecto-connectivity-banner>` owns the
  "backend down" UX (don't add per-screen toasts; **503 ≠ backend-down**). Banner host needs
  `:host{display:contents}` so it doesn't steal layout width.
- **Mocking** — ONE mock backend: `inspecto/mock/` (framework-free `MockStore`: per-Space, localStorage
  `inspecto.mock.vN` = `MOCK_STORE_KEY`, RefRule 409s, seed packs) behind the single `mockApiInterceptor` — ALL six feature mocks
  absorbed (demo → connections → components → pipelines → ops → jobs handler order = old chain precedence).
  New mock endpoints = new handler there, **never** a new per-feature mock interceptor. 4xx replies must be
  `HttpErrorResponse`s. `simulator.ts` ticks Runs/Events/Alerts lazily per intercepted request (no timers);
  bump `MOCK_STORE_KEY` whenever a seed pack's SHAPE changes or stale localStorage masks the new seeds.
- **Config-attribute forms are schema-driven** — declare `AttributeSpec[]` (tier `required|optional|advanced`,
  `dependsOn`) in `inspecto/component-model` and render with `<inspecto-schema-form>` (demo at `/design`;
  pilot: jobs `job-form.dialog`). Hand-build only bespoke sections (canvases, key/value arrays). `tier`
  (visibility) and `required` (validation) are decoupled — `required?: boolean` defaults from the tier but
  can be set explicitly, e.g. `tier:'required', required:false` for an always-visible optional field
  (`widget-option-attributes.ts`). Duplicate-name guard on create is a local `uniqueNameValidator` attached
  to the id control, skipped entirely when the field is locked on edit (jobs/dataset-editor/
  dashboard-editor/widgets all use this shape).
- **Optimistic mutations** — `optimisticMutate({apply,commit,reconcile,rollback,onError})` (`inspecto/api/
  optimistic.ts`); reassign arrays (`rows=[...]`) so the grid re-renders.
- **G6 graph** — reuse `modules/admin/catalog/graph-view.component.ts` (`@Input data`, `@Output nodeClick`);
  nodes are canvas-drawn (not DOM) → verify inspector logic via unit test, not preview clicks. Flow graph data
  via `flow-graph.ts#toFlowG6Data`.
- **Viz plugins register by side effect** — `import 'app/inspecto/viz/plugins'` runs `registerBuiltinViz()`.
  Admin shell surfaces trigger it transitively; a **guest/shell-less or lazy route that renders widgets must
  import it explicitly** or `getViz(type)` returns undefined and every tile reads "not embeddable" (bit BI-6
  `/share/:token` + BI-8). Reference: `modules/admin/share/share-viewer.component.ts`.
- **Anonymous routes** — add the path prefix to `space.interceptor` `SERVER_GLOBAL` (e.g. `/public`) or the
  active-space rewrite 404s it; the call is token/credential-addressed, not space-scoped.
- **`<inspecto-empty-state>` inputs are `title` + `message`** (not `heading`); `message` is required. Wrong
  input names fail silently (dropped in prod, caught only by a text assertion).
- **BI widget/dashboard content shape** — a `widget` component is `{vizType, datasetId, controls, options}`
  (channel mapping, NOT a raw query spec); a `dashboard` is `{name, tiles:[{widgetId, span}]}`. Anything
  writing these server-side (e.g. `BiTemplates`) must emit this shape or the Studio can't render it.
- **Dev**: `npm start` (`ng serve` :4204); `proxy.conf.json` maps `/api` → `:8080`. `.claude/launch.json`
  defines both preview servers.

---

## 7. Related sandboxes (separate repos — pointers only)

- **agent-kernel** (`C:/sandbox/agent-kernel`) — DISCONTINUED; Inspecto vendored its reasoning layer 2026-07-07.
- **eoiagent** (`C:/sandbox/agent-brainstorm`) — agent platform; Inspecto's model transport. Pinned to the
  released **`0.1.0`** (tag `v0.1.0`, EOI-7a 2026-07-08; trunk now `0.2.0-SNAPSHOT`). Rebuild into local `.m2`
  with `git checkout v0.1.0 && mvn -o clean install` until a registry is chosen (EOI-7b).
- **CVVE** (`C:/sandbox/agentic-doc-validation`) — kernel's 3rd consumer; first real `HumanHandoff` driver.

(Detailed progress for these lives in the per-user agent memory, not in this repo — they are different projects.)
