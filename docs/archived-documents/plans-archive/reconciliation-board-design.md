# Reconciliation Board — N-way aggregate compare tree + drill-to-Breaks

**Status:** design, 2026-07-14 · **Owner ask:** "special tree-table template for comparison and
reconciliation" — dataset picker, unified dimension/measure selection, dimension-order tree, banded Δ%
columns, drill to a detail page with the three record sets; 3-way suggestion; templatise + save/export +
usable as a viz component.
**Related:** `superpower/reviews/reconciliation.md` (C9 as-built) · `superpower/tree-table-design.md` ·
`GLOSSARY.md` §6-B/§7 (Reconciliation, Break, Matrix, Measure, Widget) · `superpower/db-browser-design.md`
(query gate pattern) · `superpower/component-model.md`.

---

## 1. As-built today (what this design extends — not replaces)

**Reconciliation** and **Break** are already canonical (GLOSSARY §7, lifecycle locked with the product
owner 2026-07-03). One concept → one word: this feature **evolves the existing `reconciliation`
component**; it does not introduce a parallel "comparison" concept.

| Layer | As-built | Path |
|---|---|---|
| Model + pure engine | `ReconciliationConfig {leftDataset, rightDataset, keyColumns[], compareColumns[{column, toleranceType: exact\|absolute\|percent, tolerance}], breaks[], lastRunAt}`; `runReconciliation / withinTolerance / mergeBreaks / resolveBreak / summarize` | `inspecto-ui/src/app/inspecto/reconciliation/reconciliation-types.ts` |
| Breaks | `ReconBreak {key, type: missing_left\|missing_right\|value_break, column?, leftValue, rightValue, diff?, status: open\|resolved\|auto_closed, note?}` — **exactly the "only in A / only in B / common-but-different" trichotomy** | same |
| UI | list + create dialog + detail (grouped tree of breaks by type, `viewMode grouped\|flat`, on `inspecto-tree-table`) | `inspecto-ui/src/app/modules/admin/reconciliation/*` |
| Persistence | generic component CRUD `GET/POST/PUT/DELETE /components/reconciliation[/{id}]` — **mock-served; the backend does not accept the kind yet** | `inspecto-ui/.../api/components.service.ts` |
| Execution | client-side over `SAMPLE_SOURCES`; review sheet: *"a backend cutover replaces only `datasetRows()`"* | `superpower/reviews/reconciliation.md` §"mock seams" |
| Backend | **none** (`RequirementRoutes.KINDS` contains `reconciliation` only as a Requirement *category*) | — |

What's missing vs the ask: (a) **aggregate view** — today's detail is record-level breaks only; there is
no dimension-hierarchy tree with rolled-up measures and banded Δ%; (b) **N-way** (2 only); (c) **column
mapping** when the two sides name columns differently; (d) **real execution** (server-side, DuckDB);
(e) **Widget/dashboard embedding, bundle export**.

## 2. Product direction — the lens narrative

The three Lenses (`superpower/frontend-review-and-completion-plan.md` §1) already route this workload:

- **Business** raises a **Requirement of category `reconciliation`** (`RequirementRoutes.KINDS` — exists
  today), consumes the Board, drills to Breaks, resolves/annotates them. Reads only; zero config.
- **Builder** satisfies the Requirement in minutes: pick Datasets → map columns → order dimensions →
  set bands → Save. Ad-hoc first, save optional — same pattern as the data-table SQL editor toggle.
- **Ops** (future, designed-for): scheduled `recon.run` Job; **Alert Rule on breach → Incident** —
  GLOSSARY already anticipates *"Breaks can raise Incidents (future)"*.

Domain fit (UCC): the classic 3-point revenue-assurance chain **Switch/EOI → Mediation → Billing** —
"did every rated event survive to the invoice, within tolerance, by region × product × day?"

## 3. Config model (v2 — additive, back-compat)

The persisted body of the `reconciliation` component grows; every v1 field keeps its meaning. Normalization
on load: `leftDataset/rightDataset` → `datasets[0..1]`.

```jsonc
{
  // v2 — N-way (2..3). Order matters: datasets[0] is the ANCHOR (comparison reference).
  "datasets": ["mediation_daily", "billing_daily", "switch_daily"],   // 2 or 3 ids ("summary tables" = any Dataset, incl. Matrices)
  "labels":   { "mediation_daily": "Mediation", "billing_daily": "Billing" },   // optional display names

  // Key columns = the dimensions. LIST ORDER = TREE HIERARCHY (drag-reorder in UI).
  "keyColumns": ["region", "product", "channel"],

  // Measures = compareColumns, extended. `agg` for the Board rollup; tolerance stays the record-level
  // break truth (unchanged semantics from v1).
  "compareColumns": [
    { "column": "amount", "agg": "sum", "format": "0.00", "toleranceType": "percent", "tolerance": 0.5 },
    { "column": "events", "agg": "sum", "toleranceType": "exact", "tolerance": 0 }
  ],
  "includeRecordCount": true,          // implicit __records COUNT(*) measure (default true)

  // Column mapping — only for sides whose physical names differ from the unified name.
  "columnMap": { "billing_daily": { "amount": "BILLED_AMT", "region": "REGION_CD" } },

  // Optional per-side row filter — ExpressionGuard-checked fragment (no SELECT/aggregates).
  "filters": { "mediation_daily": "record_type <> 'TEST'" },

  // Board display severity (independent knob from tolerance): |Δ%| vs anchor.
  "bands": { "warnPct": 1.0, "breachPct": 2.0, "absEpsilon": 0.005 },

  // v1 carry-over: run state + lifecycle (unchanged)
  "breaks": [], "lastRunAt": null
}
```

**Two knobs, deliberately distinct:** `tolerance` = *what is a Break* (record grain, per column);
`bands` = *how loud an aggregate drift shows* on the Board (`ok < warnPct ≤ warn ≤ breachPct < breach`).
Defaults per the ask: **< 1 % ok (green) · 1–2 % warn (orange) · > 2 % breach (red)**.

**Δ% math (locked):** Δ% = (other − anchor) / |anchor| · 100, computed **from rolled-up sums at each tree
node** — never by averaging child Δ%s. `anchor = 0 && other ≠ 0` → severity *structural* ("new"), no
percentage. `|Δ| ≤ absEpsilon` → ok (float noise guard). Missing side contributes 0 to parent rollups
(COALESCE) and renders as *structural* ("only in \<label\>"). v1 aggs: `sum`, `count` (additive ⇒ client
rollup is exact); `avg/min/max/distinct` deferred (§Non-goals).

## 4. Board UX (`/reconciliation/:id`, draft at `/reconciliation/new`)

```
┌ Mediation ⇄ Billing — Daily                                    [Run] [Save] [Export ▾] ┐
│ ⛁ Datasets: [A Mediation] [B Billing] (+)   ⠿ Dimensions: [Region]›[Product]›[Channel] │
│ Σ Measures: [Amount] [Records]   Bands: ● <1%  ● 1–2%  ● >2%   ▽ Filters (1)           │
├──────────────────────────┬───────────────────────────┬─────────────────────────┬───────┤
│ Dimension                │ Amount    A        B   Δ% │ Records   A      B   Δ% │       │
│ ▣ TOTAL                  │      1.02M    1.01M ●0.9% │        84 512 84 388 ●0.1%      │
│ ▼ EU                     │       412K     405K ●1.7% │        31 200 31 004 ●0.6% │  ⧉  │
│   · Voice                │       201K     200K ●0.3% │        …                   │  ⧉  │
│   · Data                 │       118K     114K ●3.4% │        …                   │  ⧉  │
│   · SMS                  │        93K      91K ●1.6% │        …                   │  ⧉  │
│ ▶ Americas               │       377K     372K ●1.2% │        …                   │  ⧉  │
│ ▶ APAC                   │       231K     230K ●0.4% │        …                   │  ⧉  │
│ ▶ MEA        ⊘ only in A │        2.1K       — ⊘     │           190      — ⊘     │  ⧉  │
└──────────────────────────┴───────────────────────────┴─────────────────────────┴───────┘
```

- **Config strip** (Builder lens; read-only chips for Business): every control is a chip whose "choose"
  icon pops a picker — datasets (dialog listing the space's Datasets incl. Matrices, reusing the
  `reconciliation-form.dialog` / `DatasetsService.list()` pattern), dimensions (unified column list,
  drag-to-reorder = hierarchy order), measures (agg + tolerance + format), bands, filters.
- **Unified column list**: `POST /recon/columns` returns per-dataset columns + auto-matches (normalized
  name + type). Unmatched columns appear greyed with a "map…" affordance that writes `columnMap`.
- **Tree**: `inspecto-tree-table` (`TreeNode.values` per measure/side; host-owned expand). Sibling sort by
  \|Δ%\| desc. Sticky **TOTAL** row (exact even when the grain query truncates — computed by a separate
  no-GROUP-BY query). **"Expand breaches"** expands only subtrees containing warn/breach — the fastest
  path from "something is off" to *where*.
- **Band chips = tone + icon + text** (`✓ / ! / ✕ / ⊘`), status tokens only (no hardcoded colors; passes
  the CI color guard; WCAG 2.2 AA — never color alone). New `bandCell()` renderer beside the existing
  `varianceCell()`.
- **Details column** (`⧉` row action): navigates to the Breaks page carrying dataset ids + the dimension
  path — `/reconciliation/:id/breaks?path=region:EU|product:Data`. Saved recons get shareable deep links;
  a draft passes config via router state and hints "Save to share".
- Compact mode (Δ% chips only), search, CSV export (both built into tree-table), truncation banner
  ("> 5 000 groups — add a filter or drop a dimension").

## 5. Breaks page UX (`/reconciliation/:id/breaks?path=…`)

Context header: recon name · dataset chips · path breadcrumb (`Region = EU › Product = Data`) · count
chips. Body = **three sections** (the ask), each an `inspecto-data-table` (standard tier, paged, CSV):

1. **Only in A** (`missing_right` — anchor has the key, other doesn't) — key columns + A's measures.
2. **Only in B** (`missing_left`) — key columns + B's measures.
3. **Matched but different** (`value_break`) — key columns + per measure `A | B | Δ | Δ%` with band chips.

Rows are served live and paged by `POST /recon/breaks` (scoped by `path`); the **persisted break
lifecycle overlays by `breakId`** — status chip + resolve/re-open with note, exactly the existing v1
semantics (auto-close on re-match, manual resolutions preserved). The shipped grouped-tree view remains as
a display toggle (`three-tables | grouped`), so nothing regresses.

## 6. Three-way reconciliation — recommendation

**Model: anchor (hub-and-spoke).** `datasets[0]` = anchor A (the source of truth — e.g. Mediation);
B and C are each reconciled **against A** with the same 2-way machinery. Rejected alternatives:
*full pairwise* (3 comparisons, 3× cost, no single "truth" to read the board against) and *consensus/
majority-vote* (novel semantics, weak audit story). Anchor keeps every 2-way concept — Break types stay
anchor-relative per pair (breaks gain `side: <datasetId>`; `breakId` = type·key·column·side).

- **Board**: per measure `A | B | Δ%(B) | C | Δ%(C)` — two band chips per measure; compact mode shows
  just the two chips. Row severity = worst of the pair severities.
- **Breaks page**: the three-tables layout generalizes to a **presence-pattern bar** — chips
  `A·B·C ✓✓✓ | ✓✗✓ | ✓✓✗ | ✗✓— …` (the 7 non-empty regions) filtering ONE missing-keys grid with
  per-side presence ticks, plus one value-break table per non-anchor pair (B vs A, C vs A). More elegant
  than 2ᴺ−1 separate tables and it scales to N.
- UCC example: A = Mediation, B = Billing, C = Switch/EOI — "billing leakage" (Δ%B) and "collection gap"
  (Δ%C) on one screen.

## 7. Backend design

New `RouteModule` **`com.gamma.control.ReconRoutes`** + **`com.gamma.query.ReconService`** (SQL builder +
runner). Execution reuses the `QueryExecutor` seam: one ephemeral `SqlSandbox` per call, each side's
`DatasetRelation.relationSql(config, dataRoot, viewStore)` registered as a view (this transparently covers
view-datasets, physical parquet, Matrices, calculated columns, and Exchange `shared/<owner>/<item>` refs).
**All SQL is server-built** from the validated config — no caller SQL, so no `SqlGuard` surface; identifiers
are validated against the datasets' actual columns (422 otherwise); `filters` fragments are
`ExpressionGuard.check`-ed (the `DatasetRelation.withCalculated` precedent).

| Route | Body | Returns | Gates |
|---|---|---|---|
| `POST /recon/columns` | `{datasets:[ids]}` | per-dataset `columns[{name,type}]` + suggested unified matches | 404 unknown dataset |
| `POST /recon/run` | `{id}` **or** `{config}` (draft) | grain rows `{dims…, sides:{a:{m…,rows},b:…}, presence}` + exact `totals` + `summary{matchedKeys, byType, worstBand}` + `truncated` | 503 no write-root (id-mode) → 404 → 422 config/guard → execute |
| `POST /recon/breaks` | `{id\|config, path?, type?, side?, limit, offset}` | `{columns, rows, statistics{rowCount, truncated}}` per set — paged | same |

SQL shape (2-way; 3-way adds a third CTE + FULL JOIN):

```sql
WITH a AS (SELECT region d1, product d2, SUM(amount) m1, COUNT(*) r FROM v_a WHERE <filterA> GROUP BY 1,2),
     b AS (SELECT REGION_CD d1, product d2, SUM(BILLED_AMT) m1, COUNT(*) r FROM v_b GROUP BY 1,2)
SELECT COALESCE(a.d1,b.d1) d1, COALESCE(a.d2,b.d2) d2,
       a.m1 a_m1, a.r a_r, b.m1 b_m1, b.r b_r, a.d1 IS NOT NULL in_a, b.d1 IS NOT NULL in_b
FROM a FULL OUTER JOIN b ON a.d1=b.d1 AND a.d2=b.d2
LIMIT 5001;      -- MAX_LIMIT parity; truncated flag when exceeded
```

Breaks sets: `ANTI JOIN` each way + inner join with the per-column tolerance predicate (SQL port of
`withinTolerance` — unit-tested for parity with the FE function). Guards: `DEFAULT_LIMIT 200 / MAX_LIMIT
5000` clamped, path values bound as parameters.

**Statelessness (locked):** the backend computes; the **lifecycle stays client-side** — the FE merges fresh
breaks via the existing `mergeBreaks` and PUTs the component, honoring the review-sheet contract ("cutover
replaces only `datasetRows()`"). Fresh breaks are capped at MAX_LIMIT; `summary.byType` counts are computed
in SQL and exact regardless of caps.

**Component kind**: add `reconciliation` to `ComponentStore.WRITABLE_TYPES` + `ComponentRegistry.TYPE_BY_DIR`
(`reconciliations/`) — the 2-line addition the FE already assumes. Bundle export: add to
`BundleRoutes.APPLY_ORDER` + `INTEGRITY_KINDS` (refs → datasets), **sanitizing run state (`breaks`,
`lastRunAt`) on export** — a bundle carries config, not operational history.

## 8. Widget, template, export — "use as viz components"

- **Widget**: register `VizPlugin {meta:{type:'reconciliation', label:'Reconciliation', viewKind:'reconciliation'},
  render:{kind:'component', componentKey:…}}` in `viz/plugins/index.ts` — the view-bound pattern
  (`geo-map`/`link-analysis` precedent: no dataset/query controls, excluded from Show-Me, `WidgetConfig.viewId`
  = the reconciliation id). `app-widget-host`/`app-dashboard-tile` then embed the Board **read-only compact**
  (tree + bands, config strip hidden, title links to the full pane) on any Dashboard.
- **Template flow (v1 = duplicate-and-rebind)**: list-page action **Duplicate** → new name + dataset
  rebinding dialog; `columnMap`/dimensions/measures/bands carry over and re-match by unified name. A formal
  parameterized Template type is a non-goal for now.
- **Export/import**: single recon = component JSON (existing component GET/PUT); across environments =
  Metadata Bundle v2 (§7). This is the whole "configuration can be saved and exported" requirement — no
  new machinery.

## 9. Requirements

**Functional** —
FR-1 pick 2–3 Datasets (incl. Matrices) via picker dialog; same Space (Exchange shared refs work via
`DatasetRelation`). FR-2 unified column list with auto-match + manual `columnMap`. FR-3 dimension
selection order = tree hierarchy; drag-reorder. FR-4 measures with `agg` (sum/count v1), format, per-column
tolerance. FR-5 implicit record-count measure (toggle). FR-6 banded Δ% vs anchor (defaults 1 / 2 %,
`absEpsilon`, structural states). FR-7 Board interactions: TOTAL row, expand-breaches, |Δ%| sibling sort,
compact mode, search, CSV, truncation banner. FR-8 details action → Breaks page carrying datasets +
dimension path; deep-linkable once saved. FR-9 Breaks page: three sections (only-A / only-B / different)
paged + lifecycle overlay (resolve/re-open + note; auto-close preserved); grouped-tree toggle retained.
FR-10 3-way anchor mode + presence-pattern bar. FR-11 save / duplicate-rebind / bundle export-import.
FR-12 dashboard embedding as view-bound Widget. FR-13 per-side ExpressionGuard row filters.

**Non-functional** — NFR-1 group cap 5000 + truncated flag; TOTAL exact always. NFR-2 status tokens +
icons, never color alone (AA; CI color guard). NFR-3 offline-only deps. NFR-4 routes envelope-compliant
under `/api/v1`, standard gate order, real-HTTP test class per the endpoint skill. NFR-5 single sandbox
per run; comparison executes in DuckDB, never by shipping both row sets to the client.

**MoSCoW**: M = FR-1..9, NFR-1..5 (2-way board + breaks + backend). S = FR-10..12 (3-way, widget, bundle).
C = FR-13, scheduled `recon.run` Job + Alert-Rule-on-breach → Incident. Proposed REQUIREMENTS.md entry:
**DAT-7 "Reconciliation backend + Board"** (add on approval; Requirement *category* `reconciliation`
already exists for the Business ask).

## 10. Implementation plan

| Phase | Scope | Verify |
|---|---|---|
| **P0 backend spine** — ✅ **shipped 2026-07-14** | kind registration (2 lines) + `ReconRoutes`/`ReconService` (`/recon/columns`, `/recon/run` 2-way, `/recon/breaks`) + SQL-builder unit tests incl. `withinTolerance` parity + real-HTTP `ControlApiReconTest` (all gates) | ✅ reactor 1454/0/0/3 green; `ReconServiceTest` 8 + `ControlApiReconTest` 7 pass |
| **P1 Board UI** — ✅ **shipped 2026-07-14** (`81f624e`) | config strip + `bandCell()` + tree board (TOTAL, expand-breaches, sort, CSV) + draft mode + save + offline mirror `recon-board.ts` | ✅ lint:tokens + test:ci + build |
| **P2 Breaks cutover** — ✅ **shipped 2026-07-14** (`f68b9e9`) | three-tables page + `?path=` scoping + lifecycle overlay; exec seam replaces the `datasetRows` mock | ✅ green |
| **P3 Widget + export** — ✅ **shipped 2026-07-14** (`de343ef`) | view-bound `reconciliation` VizPlugin + tile host; Duplicate-and-rebind; bundle kinds + export run-state sanitize | ✅ green |
| **P4 3-way** — ✅ **shipped 2026-07-14** | anchor model (`datasets` 2–3) in `ReconService` + `ReconRoutes` (`side` param) + per-pair summaries; UI third-dataset picker, per-side Δ% columns, Breaks side toggle | ✅ green |
| **P1 Board UI** | config strip + pickers + `bandCell()` + tree board (TOTAL, expand-breaches, sort, CSV) + draft mode + save; route `:id` = Board; seed a demo recon (two near-identical Matrices with injected diffs) in `spaces/demo` | GAUNTLET; SMOKE `/recon/run` against seeded demo; live board screenshot |
| **P2 Breaks cutover** | three-tables page + `?path=` scoping + lifecycle overlay; `ReconciliationsService.datasetRows()` → `/recon/*` (mock seam removed); existing detail becomes `:id/breaks` | existing recon specs still green; e2e drill Board→Breaks on demo space |
| **P3 Widget + export** | view-bound `reconciliation` VizPlugin + dashboard tile embed; Duplicate flow; bundle kinds + export sanitize | dashboard renders saved recon; bundle export→import round-trip test |
| **P4 3-way** | `datasets[3]` + anchor + per-pair breaks (`side`) + presence-pattern bar + Board C columns | unit tests on presence matrix; 3-dataset demo seed; GAUNTLET |

Each phase lands on `master` per the release-workflow skill (Conventional Commits; no feature branch).

### Vocabulary (GLOSSARY §7 additions when this ships)
- **Anchor** — the reference Dataset of an N-way Reconciliation (`datasets[0]`); all Δ% and Break types
  read relative to it.
- **Band** — the Board's aggregate display severity for \|Δ%\| (**ok / warn / breach**; defaults 1 / 2 %).
  Independent of a compare column's record-level *tolerance*.
- **Reconciliation Board** — pane label for the aggregate dimension-tree view of a Reconciliation (a
  label like *Viz Library*, not a new concept).
- **Presence Pattern** — which sides of an N-way Reconciliation contain a given key (drives the Breaks
  page filter bar in 3-way).
- ⛔ reminders: the compared relations are **Datasets** (a summary one is a **Matrix**, never noun-"cube");
  the aggregations are **Measures**.

### Non-goals (now)
Non-additive aggs (`avg/min/max/distinct`) · N > 3 · cross-Space recon (beyond Exchange refs) ·
parameterized Template type (Duplicate covers it) · scheduled runs + Alert Rule → Incident (designed-for;
`recon.run` Job later) · break-level assignment/queues (Cases already exist for that) · fuzzy key matching.

## 11. Open decisions (recommendations inline)

1. **Nav/lenses** — keep top-level `/reconciliation` pane, `lenses: ['business','builder']`; Board is the
   `:id` default view. *(Recommended; no IA churn.)*
2. **Δ% denominator** — anchor-relative, consistent with `withinTolerance`'s percent rule (left-relative,
   0-left ⇒ exact). *(Recommended.)*
3. **Draft drill state** — router state + "Save to share" hint (vs config-in-URL). *(Recommended: simplest;
   config-in-URL retrofittable.)*
4. **Breaks page default view** — three-tables (the ask) with grouped-tree as toggle, or the reverse?
   *(Recommended: three-tables default.)*
5. **Board polling/refresh** — manual Run only in v1 (no auto-refresh). *(Recommended.)*
