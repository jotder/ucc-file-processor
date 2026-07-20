# Inspecto — User Guide

*Unveil stories from your data.*

Inspecto turns raw source files into clean, queryable data and then helps you monitor, analyze,
and present it. This guide walks the web app screen by screen: how to get around, what each page
is for, what you can do there, and how the pieces connect. Every term is used exactly as defined
in [`GLOSSARY.md`](GLOSSARY.md) — the canonical vocabulary — so if a word here looks deliberate
(Pipeline, Dataset, Expectation, Source, Widget…), it is.

> **How to read this.** Each screen entry has the same shape: **what it is → what you can do →
> what to notice → how it connects** to the rest of the app. If you're brand new, read
> [Getting around](#1-getting-around) and the [Common workflows](#8-common-workflows) at the end;
> then dip into individual screens as you need them.

> **Editions & personas.** Inspecto ships in build *editions* (Personal / Standard / Enterprise),
> and each user views it through a *Lens* (Business / Builder / Ops). An edition can add or remove
> whole screens, so your app may show a subset of what's described here. A Lens never hides a
> screen — it changes emphasis: where the app opens and which actions and toolbars are
> foregrounded. Screens still in active build are called out as such.

### Start here — pick your path

The app serves three kinds of people. Find yourself, and start with your five screens:

- **Business** (analysts, ops managers — *consume and request*). Your Lens opens on **KPI &
  Reports**. Your loop: read a Dashboard → question a number → trace where it came from
  (**Catalog** lineage) → raise a **Requirement** for what's missing → check two systems agree
  in **Reconciliation**. Start at [Business](#2-business).
- **Builder** (data & BI engineers — *author*). Your Lens opens on **Pipelines**. Your loop:
  **Connection → Source → Pipeline → Runs** to get data in and shaped, then **Studio**
  (Query → Widget → Dashboard) to turn it into insight. Start at [Workbench](#41-workbench)
  and [Studio](#42-studio).
- **Ops** (operators, on-call — *monitor*). Your Lens opens on **Events**. Your loop: watch
  **Overview / Processing Status**, confirm in **Events / Runs**, understand with a
  **Diagnosis**, track the fix as an **Incident**. Start at [Operations](#3-operations).

### The data lifecycle (how a file becomes a chart)

Inspecto is **ELT**, not ETL: the *load* is a plain write of parsed rows to Parquet; the heavy
*transforms* happen afterwards, in the lakehouse, producing new tables. So the path from a raw
file to a dashboard tile is a chain of the nouns this guide defines:

```
  Connection ─▶ Source ─▶ Run ⊇ Batch ⊇ File ─▶ Pipeline (parse → shape)
       │            │                                   │
   how to reach  what/when to                     ┌─────▼─────┐
   the system    collect                          │  Table    │  partitioned Parquet (the "load")
                                                   └─────┬─────┘
                                          Transform / cube (runs IN the lakehouse)
                                                   ┌─────▼───────┐
                                                   │ Derived Table│
                                                   └─────┬───────┘
                                    Query (SQL + Parameters) ─▶ Result Set
                                                   ┌─────▼─────┐
                                                   │  Widget   │ ─▶ Dashboard ─▶ Report (scheduled export)
                                                   └───────────┘
```

Read left-to-right it is the Builder's whole job: **Workbench** owns everything up to the Table;
**Studio** owns everything from Query onward; **Catalog** is the map over all of it. Quality gates
(**Expectations**) and routing (**Decision Rules**) attach to the Pipeline; monitoring
(**Metrics → Alert Rules → Alerts**) watches the Runs.

---

## Contents

1. [Getting around](#1-getting-around)
2. [Business](#2-business) — consume, schedule, request
3. [Operations](#3-operations) — watch the system run
4. [Platform](#4-platform) — build and author
   - [4.1 Workbench](#41-workbench) — acquire & process
   - [4.2 Studio](#42-studio) — analyze & present
   - [4.3 Catalog](#43-catalog) — data assets & lineage
5. [Settings](#5-settings)
6. [Assistant](#6-assistant)
7. [Common interface elements](#7-common-interface-elements)
8. [Common workflows](#8-common-workflows)
9. [Vocabulary quick reference](#9-vocabulary-quick-reference)

---

## 1. Getting around

The app has two persistent regions: the **left sidebar** (the main menu) and the **top bar**
(global controls). Everything you open loads in the large content area between them.

### The sidebar

The top of the sidebar is a fixed header, stacked top to bottom:

- **Logo** and the caption *"Unveil stories from your data."*
- **Menu search** — a search box directly beneath the caption that filters the *menu itself*.
  Start typing and the tree collapses to just the pages that match, each shown with a breadcrumb of
  where it lives (e.g. *Pipelines · Platform › Workbench*). Typing a **group** name such as
  `Platform` surfaces every page under it; typing a **page** name such as `Geo` jumps to Geo Map
  Analysis. Press **Enter** to open the first match, **Esc** or the **✕** to clear and restore the
  full menu. This is a quick way to reach a page without expanding groups by hand.

Below the header, the menu is split into two bands by a **horizontal divider**:

- **Above the divider** — *your* custom **business menus**, if any exist in this Space. You create
  these in [Menus (the Menu Builder)](#menus); they're pinned to the top so the things your team
  cares about are the first thing you see.
- **Below the divider** — the fixed **system menus**, always in the same order:
  **Business**, **Operations**, **Platform**, **Settings**, **Assistant**.

Groups (Operations, Platform, and Platform's sub-groups) expand and collapse when clicked. Your
current page stays highlighted.

### The top bar

- **View as (Lens switcher)** — shows and changes your active persona: **Business**, **Builder**,
  or **Ops**. A Lens re-emphasizes the actions and toolbars that matter to that role — for example,
  Builder foregrounds authoring, Ops foregrounds monitoring. It is a **convenience view you switch
  freely**, not a permission boundary (that's a *Role*, a separate security concept), and switching
  it does **not** reload the app. Your Lens also chooses where the app opens: Business lands on
  **KPI & Reports**, Builder on **Pipelines**, Ops on **Events**.
- **Space switcher** — shows the active **Space** and lets you jump to another or create one. A
  Space is a fully isolated project environment: its own Connections, Sources, Pipelines, Datasets,
  dashboards, and custom menus. It appears only when the server hosts more than one Space. Choosing
  a different Space **reloads the whole app** in that context (each screen re-fetches its data), so
  you always see a clean, correctly-scoped view.
- **Notifications bell** — recent Signals delivered to you (Alerts, notable Events). Opens the
  notification history; what you subscribe to is set in **Settings → Notifications**.
- **Search** — a global jump-to palette across *named artifacts* — Pipelines, Sources, Datasets,
  Widgets, Dashboards, Queries. Use this to find *a thing*; use the sidebar menu-search to find *a
  screen*.
- **User menu** — your profile, appearance/preferences, and (in the Standard edition) sign-out.

---

## 2. Business

Business is the **consume-and-request side** of the app — read the numbers, receive them on a
schedule, ask for what's missing, and check that two systems agree. The Business Lens lands here
(on **KPI & Reports**); Builders visit **Requirements** to triage what Business asks for.

**KPI & Reports** — The Business landing page: every **Dashboard** built in Studio, shown as
cards with each one's **Reports** beneath it. A **Report** is a *scheduled delivery* of a
Dashboard — **Schedule export** delivers a rendered copy (**CSV, PDF, or PNG**) to a recipient
list on a cron schedule. Each Report runs as a **Job** behind the scenes, so it also appears
under Runs, and the per-Report actions let you **run now** or **download the latest** output.
Keep the two straight: the Dashboard is the *thing*, the Report is the *delivery*. If the page
is empty, author a Dashboard in Studio first — Studio builds; this page presents and delivers.
**Authoring a KPI tile**: a KPI is a single-number **Measure** with a target/threshold, rendered as
a headline tile — build one in Studio's **Viz Library** by picking a Dataset's Measure (see
**Datasets** in §4.3) as a KPI-type Widget, then drop it onto a Dashboard in the **Dashboard
Builder**. It shows up here once its Dashboard is added to KPI & Reports.

**Requirements** — The intake queue that turns "can we get a churn dashboard?" into a tracked
deliverable. Submit a **Requirement** — a request for a **KPI**, **Report**, **Reconciliation**,
or **Rule** — with a title and description. It starts *submitted*; a Builder triages the queue
and **accepts** or **rejects** it (with a decision note); an accepted Requirement is marked
**delivered** once the thing it asked for exists. Open a row to see the full decision and
delivery history.

**Reconciliation** — Dataset-vs-dataset agreement checking — the revenue-assurance and audit
workhorse. Define a Reconciliation as a left and a right **Dataset**, the key columns to match
on, and per-column **tolerances**; each run compares the two sides and surfaces **Breaks**:
records that are *missing-left*, *missing-right*, or mismatched in value (*value-break*). Work a
Break to resolution with a note — manual resolutions survive later runs, and a Break
**auto-closes** when its key re-matches within tolerance on a subsequent run.

---

## 3. Operations

Operations is the **monitoring side** of the app — where you watch data flow, spot trouble, and
work it to resolution. These screens are read-heavy; authoring lives under Platform. A typical
triage path runs left to right through this group: notice something on the **Overview**, confirm it
in **Events** or **Processing Status**, understand it with a **Diagnosis**, and track the fix as an
**Incident** (grouped into a **Case** if it's part of something larger).

Under pressure, three screens overlap — here's which one answers which question:

| Question | Go to | Why that one |
|---|---|---|
| "Is the system healthy *right now*?" | **Overview** | KPI-style tiles + trends across the whole Space, at a glance |
| "Is anything backing up or failing?" | **Processing Status** | One row per Pipeline: committed vs. quarantined counts + last-batch outcome, side by side |
| "What exactly happened, and when?" | **Events** | The newest-first activity stream — filter to a Pipeline/severity, turn on live-tail |
| "Which files in *this* run succeeded/quarantined/errored?" | **Runs** (Workbench) | Drill Run ⊇ Batch ⊇ File to the individual outcome |

**Overview** — Your at-a-glance health check. It shows operational health tiles and trend
charts: how many Pipelines are running or paused, recent Run outcomes,
committed vs. quarantined volumes, and which Alerts are firing. Each tile fetches independently, so
one slow metric never blanks the page. It reflects the state at load time.

**Processing Status** — A single grid that rolls up *every* Pipeline in the Space: committed record
counts, quarantine counts, and the outcome of each one's most recent batch, side by side. When you
want the one-screen answer to "is anything backing up or failing right now?", this is it — a
cross-pipeline complement to the per-Pipeline detail you reach from Workbench.

**Events** — A newest-first stream of everything happening operationally — files collected, Runs
started and finished, errors, Alerts raised. (Under the hood, Events, Alerts, and Notifications are
three views of one **Signal Ledger** — the same facts, filtered differently — but you don't need
that to read the page.) A toolbar lets you filter by
minimum severity, event type, Pipeline, and free text, cap the result count, and flip on a
**live-tail** toggle that keeps the list updating while the tab is visible. You can export the
matching rows to CSV and save common filter combinations as reusable views. Click a row to see the
full Signal (correlation id, type, severity, payload).

**Audit log** — The immutable **who-did-what** trail: sign-ins, configuration changes, permission
grants, exports. It answers accountability questions and is deliberately separate from **Events**:
Events records *system activity* (Signals), Audit records *human and access actions*. Entries can't
be edited or deleted.

**Diagnoses** — AI-assisted root-cause analysis for things that went wrong. Each diagnostic record
examines a failing Run or Source and proposes a likely cause and a suggested fix, often linking to
(or offering to raise) an **Incident**. Open a row to read the full analysis. The model behind this
is chosen in **Settings → Model Settings**.

**Alerts** — The fired instances of **Alert Rules** — an Alert Rule watches an observability
**Metric** against a threshold, and when it crosses, an Alert appears here with a severity of info, warning, or
critical. The list supports rich filtering (and, in its advanced table mode, ad-hoc SQL over the
rows). The **Alert Rules** section beneath the list is where rules are **authored**: create a
rule (metric, comparator, threshold, window, severity, optional Pipeline scope), edit or delete
it, and **Evaluate now** to sweep the armed rules on demand. Alerts are a common trigger for
raising an **Incident**.

**Incidents** — Tracked operational problems with a lifecycle: **open → in-progress → resolved**.
An Incident can be raised automatically by an Alert or a Diagnosis, or created by hand. Open one to
update its status, add notes, and record the resolution. Incidents are the unit of "something is
wrong and we're on it."

**Cases** — A **Case** groups related Incidents into one larger investigation with a shared
narrative and resolution — useful when several Incidents turn out to be facets of the same
underlying problem. Cases and Incidents share the same working surface (the same list-and-detail
pattern), just at different granularity.

---

## 4. Platform

Platform is the **building side** of the app, organized into three sub-groups: **Workbench** (get
data in and process it), **Studio** (analyze and present it), and **Catalog** (see what data you
have and how it's related).

### 4.1 Workbench

Workbench is where data engineers and builders do their authoring. The natural order is: define a
**Connection** to a remote system, point a **Source** at it to collect files, author a **Pipeline**
that parses and shapes those files, guard quality with **Expectations**, route with **Decision
Rules**, and watch the results as **Runs**. **Jobs**, **Components**, **Enrichment**, and the
Pipeline editor round out the toolkit.

> **Scheduling & Triggers — where "when does it run?" is answered.** Timing is set on four
> different surfaces; this is the whole map:
>
> | What runs | Where you set the timing | Options |
> |---|---|---|
> | A **Source** (collection) | on the Source itself | a polling schedule ("how often to collect") |
> | A **Pipeline** | its **Trigger** | `cron` · `event` · `manual` (run-now) · `on-pipeline` (chain after another Pipeline) |
> | A **Job** (enrichment, maintenance, export) | its **Trigger** | `cron` · `event` · `manual` |
> | A **Report** (scheduled Dashboard export) | its schedule on **KPI & Reports** | `cron` (a Job under the hood) |
>
> All four surface their executions in **Runs**, so wherever a timer lives, the *result* is in one
> place. The engine that owns triggers and starts things is the **Scheduler**.

**Pipelines** — The heart of Inspecto. A Pipeline is a named, authored graph (DAG) of **Steps**
that turns raw source files into clean, partitioned Tables. The list shows each Pipeline's
active/inactive status and last-run time, with per-row actions to **run now** or open **history**.
Opening a Pipeline gives you its detail view (files, audit, batches) and its **editor** — a
NiFi-style visual canvas where you lay out processor nodes (parser, transform, enrichment, sink,
job, sub-pipeline) and connect them. In the editor, click to select, double-click to configure,
drag to move, and **Shift-drag** to draw a connection between nodes; each node has a **Test** button
for validating just that step. Parser nodes open a dedicated configuration dialog with a typed
property sheet and a grid preview of the parsed output; the dialog offers nine format types, but
today only the ones matching the engine's `parsing.frontend` set actually run against real data —
**delimited/DSV**, **fixed-width or line-pattern plain text** (regex-matched named groups), **JSON**
(newline-delimited), and a generic **"other"** (custom Java plugin) for anything else. **ASN.1,
HTML, Parquet, XLSX, and XML** appear in the dialog as staged scaffolding — selecting one won't yet
run against real backend support. Reusable parsers are saved as **Grammar** Components.

**Runs** — The execution history. Every time a Pipeline or Job runs — manually, on a schedule, or
by trigger — it produces one **Run**. Each Run contains one or more **Batches**, and each Batch
contains **Files**, and every level carries its own status. Browse Runs by outcome
(success / failed / in-progress), see start and end times and batch/file counts, and drill into any
Run to see exactly which files succeeded, quarantined, or errored. When something on the Overview
looks off, this is where you confirm what actually happened.

**Jobs** — Reusable, schedulable **Executables** that aren't full ingestion Pipelines: enrichment
runs, maintenance tasks, report generation, and embedded steps. Each Job has a trigger — **cron**,
**event**, or **manual** — and its own run history (open a Job to see past executions with timing
and result). Jobs triggered here also show up in **Runs**.

**Expectations** — Data-quality rules that validate records against a **Schema**: not-null, numeric
range, regex/format, referential integrity, and the like. Author and test Expectations here; at run
time, records that violate them are surfaced in Run audit and can raise Signals or Alerts.
Expectations are one of Inspecto's three distinct rule engines — keep them separate from Alert
Rules (which watch measures) and Decision Rules (which route records).

**Decision Rules** — Business-logic rules that **transform or route** records: evaluate conditions
and produce consequences such as routing an event type to a particular sink, tagging, quarantining,
dropping, emitting a Signal, or triggering another Pipeline. Author and test them here; they're
also expressible as route nodes inside the Pipeline editor.

**Components** — The reusable-building-block registry. Component *Types* — **Grammar**, **Schema**,
**Transform**, **Sink** (and, behind the scenes, rule types) — are the parts Pipelines and Steps are
assembled from. Browse them, preview definitions, see how many places reference each one, and
safe-delete those that are unused. Grammars authored in the Pipeline parser dialog land here.

**Enrichment** — Lookups that widen your data: take an input Dataset, join it against a
reference, and emit a Derived Table. Select an enrichment job to open a detail panel with tabs for
its **Runs** (execution history), **Lineage** (what source/step data flowed through, filterable by
run), and a **Report** (a date-range rollup with percentile statistics).

**Connections** — A **Connection** is a named endpoint plus credentials for reaching a remote
system — one of Database, FTP, FTPS, SFTP, or Local. Connections are shown as cards (not a table);
each is built from a schema-driven form with optional, default-collapsed **SSH tunnel/bastion** and
**proxy** sections, each with its own **Test** button, plus a routing/failover popover. Probe and
sample actions validate connectivity and preview the paths or tables you can reach. One Connection
is reused by many Sources, so you configure credentials once.

**Sources** — A **Source** is a configured collection task bound to one Connection: *what* to
collect (file paths or database queries), *how often*, which filename patterns to match, and the
deduplication policy. Browse Sources in a table, create and edit them, and **run now** to collect on
demand. A Source is the noun (the configured task); the runtime that executes it is just its role.

### 4.2 Studio

Studio is where data becomes insight and presentation — queries, visualizations, dashboards, and
graph/geo investigations. Most Studio panes are Builder-lens authoring surfaces.

**Query Library** — A searchable gallery of saved **Queries**. A Query is reusable executable
knowledge: a Dataset plus a SQL/structured query model plus runtime **Parameters**. Browse and
search cards, open one in the editor to write SQL with `$`-parameters and preview its **Result
Set**, and reuse it to build Widgets. Because a Result Set carries semantic column roles
(dimension / measure / temporal), the same query output can be visualized many ways without
rewriting the query.

**Viz Library** — A searchable **Widget** gallery. A Widget is a configured instance of a
*Visualization Type* bound to a Dataset's result metadata (keep the two distinct: the Type is the
template, the Widget is the configured chart). Cards show a thumbnail, tags, and viz type; open one
to edit it in the builder or drop it onto a Dashboard.

**Dashboard Builder** — Your **Dashboards** and their editor. A Dashboard is a grid of Widget tiles;
the editor lets you add, arrange, resize, and bind Widgets, with quick-filters and drill-through for
exploration. Dashboards are saved per Space.

**Link Analysis** — A graph-investigation studio for exploring entities and the relationships
between them. Pick a graph source (for example the component registry, data lineage, or an entity
projection built from a Dataset), run a query, and the graph renders as interactive nodes and typed
edges you can pan, zoom, and select. A toolbox provides layouts and analysis algorithms — shortest
paths, neighborhoods, centrality, and community detection. A saved investigation is a **Link
Analysis View** you can return to (and, for entity-projection sources, surface as a graph Widget).

<a id="menus"></a>**Menus (Menu Builder)** — Where you customize the sidebar for your team. It's a
two-pane authoring page: an editable menu tree on the left and a **live preview** on the right. The
groups and links you build here become your **custom business menus**, which appear in the sidebar
band **above the divider**, on top of the fixed system menus. This is how a Space tailors navigation
to its own domain without any code change.

**Geo Map Analysis** — A geographic-investigation studio. Choose a geo source (currently a Dataset's
latitude/longitude columns) and a geo query, and explore the results on an **offline** MapLibre map:
search, find-nearby, heatmaps, time-range filtering, and camera control. A saved **Geo View**
remembers the source, query, display options, and camera position. The basemap and geo defaults
come from **Settings → Map Settings**.

### 4.3 Catalog

**Data Catalog** — The map of your data assets and how they connect. Its front door is the two
data-origin tabs: **Streams** (event/fact origins — one feed: a database, a file drop) and
**References** (dimension origins that publish a **Reference Dataset**); an origin is what a
**Connection** plus its **Collectors** populate. Behind them it indexes every **Schema**,
**Table**, **Derived Table**, and **View** in the Space, with search and metadata. A **Lineage**
tab renders the read-only graph — which Stream feeds which Table, which Transform derives which
Derived Table, which Widgets read which Datasets — so you can trace where any piece of data came
from and what depends on it. Click a node for its detail.

**Onboarding a data origin** — *Onboard Stream / Onboard Reference* (the header button on those
tabs, authoring lenses) opens the guided editor: a stage rail — Collection → Parsing → Schema &
Mapping (References: **Keys & Load**) → Enrichment *(Streams, optional)* → Go-live — over a
server-held draft, so you can leave and resume any time (the rows appear in the Catalog as
**Draft** until you go live). Capture one sample and each stage shows *your* data after that step:
parse it, type it (only honestly-cast types are offered), and for Streams optionally join in a
published Reference **by name** with transform SQL — the enrichment then runs after every
committed batch. Go-live flips the pipeline active; an activity glance shows the inbox working.

**Datasets** — The queryable relations themselves, the umbrella over **Table** (a partitioned root
of Parquet files described by a Schema), **Derived Table** (materialized by a Transform or cube),
and **View** (a virtual, logical query). Browse and search Datasets, create and edit their schema
bindings, and link them to Widgets. Datasets are what Queries run against and what most of Studio
consumes. A Dataset also holds its own **Measures** — named aggregate expressions (e.g. `sum(...)`,
`count`) defined once against that Dataset — so **yes, a Measure is reusable**: every Widget or KPI
tile bound to the same Dataset picks from the same Measure list instead of re-typing the aggregate.
Reuse is per-Dataset, not global across Datasets.

---

## 5. Settings

**Settings is a single page.** Instead of a submenu, each configuration area is a **drawer** —
click a drawer's header to expand it and reveal that area's full controls inline; click again to
collapse. Several drawers can be open at once, and each hosts the very same page you'd reach from
its own route (`/config`, `/spaces`, …), just gathered in one place.

**Config** — The active Space's core configuration, edited as **TOON** (Inspecto's canonical config
format). Set the display name and description, toggle multi-space mode, and control audit retention
and the authoring safety gate. Secrets are never shown or round-tripped in the clear — they appear
as references like `${ENV:…}`, and a masked sentinel means "keep the stored value."

**Notifications** — Your personal delivery preferences for the Signal Ledger: which channels
(email, webhook) receive what, and at which severity thresholds. This is the per-user counterpart to
the header notifications bell.

**Spaces** — Multi-space administration — the one genuinely space-aware admin screen. List all
Spaces; create them blank or from a **Space Template** — a shipped vertical blueprint (e.g.
Telecom Revenue Assurance, Fraud Management, Financial Auditing) that seeds the new Space with
ready-made Pipelines, Datasets, and Dashboards; edit and delete them (with an option to purge
data), and export or import a Space
(or a single data source) as a zip, with a **dry-run preview** before importing. This is distinct
from the header **Space switcher**, which only selects and reloads.

**Model Settings** — The AI provider and model behind the **Assistant** and **Diagnoses**: choose
the provider, supply the credential reference, pick per-tier models, and set generation parameters.
If AI features are switched off, the relevant screens show a per-screen "unavailable" notice rather
than an error.

**Processor Icons** — Assign custom icons to processor/component kinds so the Pipeline editor and
Catalog graph read at a glance. Icons use the built-in `heroicons_outline:*`-style names.

**Map Settings** — The basemap and geocoding configuration for **Geo Map Analysis**. Inspecto ships
an offline place-table geocoder (no network required); you can point it at an online geocoder if you
prefer.

**Import & Export** — Move **configuration bundles** between Inspecto instances. A bundle is
metadata only — never data rows — and can include Pipelines, Datasets, Widgets, Dashboards, saved
Link-Analysis and Geo-Map views, Queries, and registry pieces (with secrets masked). On import you
get a per-item preview of what's new, unchanged, or drifted, with an overwrite/skip choice for each.

**Design System** — A live gallery of Inspecto's shared UI patterns with usage examples. It's
primarily a developer reference (also reachable directly at `/design`) rather than an operational
screen, but it's the fastest way to see every component described in
[section 7](#7-common-interface-elements) in one place.

---

## 6. Assistant

The **Assistant** is a dedicated pane for the built-in AI helper. Ask it questions about your
Pipelines, Schemas, or data; have it draft configuration, explain an error, or help author an
Expectation or Alert Rule. Its answers are grounded in your current context — the active Space and
what you're working on. Which model responds is set in **Settings → Model Settings**; if the assist
feature is disabled for your deployment, the pane says so instead of failing.

---

## 7. Common interface elements

These shared elements behave identically everywhere, so learning them once pays off on every
screen. You can see them all live in **Settings → Design System** (`/design`).

**Status badge** — A small colored **pill** conveying state — `RUNNING`, `PAUSED`, `FAILED`,
`HEALTHY`, `QUARANTINED`, and so on. Status is always shown as **text *and* color** together (never
color alone), so it's readable regardless of color vision.

**Data table** — The workhorse grid, and the single component behind nearly every table in the app.
It comes in escalating tiers, so a table shows exactly as much power as its screen needs:
- a plain **read-only** grid (rows, sorting, row actions);
- **standard** — adds a compact toolbar to choose columns, search, and export to CSV;
- adds an always-on **SQL editor** and a visual **filter builder** so you can query the rows right
  there (it runs the SQL in your browser and re-renders the grid);
- adds **"save as rule"**, turning your query into a reusable, parameterized template.

  Sort by clicking a column header (a glyph shows the direction); filter and export from the toolbar
  icons. Recent and favorite SQL queries are remembered per source.

**Empty state** — What a screen shows when there's nothing to display yet: an icon, a short
explanation, and often an action — for example *"No events match the current filters — Clear
filters."* It tells you *why* it's empty and what to do next.

**Skeleton** — The shimmering placeholder shown briefly while data loads, so the layout doesn't jump
when content arrives. It respects reduced-motion preferences.

**Alert / banner** — An inline notice tied to the screen you're on, colored by kind — **info**,
**warning**, **error**, **success**. Typical uses: *"Read-only: no write root configured"* or a
test result. It's announced to assistive tech and is distinct from the app-wide connectivity strip
below.

**Connectivity banner** — An app-wide strip that appears at the top when the backend becomes
unreachable, with a **Retry** button that re-checks the connection. While it's showing, data may be
stale until the connection returns. It's the single, app-level "we're offline" signal — screens
don't each pop their own.

**Confirm dialog** — A modal that guards destructive actions (delete, purge). It states the action
and its consequence before you commit, with clear cancel/confirm buttons; the more dangerous the
action, the more explicit the confirmation.

**Forms** — Inspecto's forms ask **only what's needed now**: typically a name and description at save
time, with advanced sections collapsed until you open them. Validation is inline — errors appear
beneath the offending field, and a duplicate name is flagged as you type rather than after you
submit.

**Charts & graphs** — Dashboards and reports render interactive **charts** (time-series, bar,
heatmap), while Catalog, Link Analysis, and the Pipeline editor render node-link **graphs**. Charts
support hover tooltips; graphs support pan, zoom, hover details, and selection, and (in the editor)
direct editing.

---

## 8. Common workflows

End-to-end paths that tie the screens together. Each references the screens above.

**Bring in a new data feed.**
1. **Platform → Workbench → Connections** — create a Connection to the remote system and **Test** it.
2. **Sources** — add a Source on that Connection: paths/patterns, schedule, dedup policy. **Run now**
   to collect once.
3. **Pipelines** — author a Pipeline that parses and shapes the collected files (configure the
   parser node, **Test** each step).
4. **Runs** — confirm the Run succeeded and inspect file outcomes.
5. **Catalog / Datasets** — verify the resulting Table and its Schema appear, with correct lineage.

**Guard and route data quality.**
1. **Expectations** — author validations against the Schema and test them.
2. **Decision Rules** — route or quarantine records based on conditions.
3. **Alerts** (via Alert Rules) — get notified when a **Metric** crosses a threshold at run time.

**Remediate a quarantined batch.**
1. **Runs** — open the Run and check its quarantine listing (`GET /runs/{name}/quarantine`): which
   inputs were quarantined and why (structural failure, an Expectation violation, or a Decision
   Rule's `quarantine` consequence).
2. Fix the root cause — correct the source file, adjust the Schema/Expectation, or amend the
   Decision Rule condition.
3. Reprocess the batch (`POST /runs/{name}/reprocess`) once the fix is in place — it re-ingests the
   same batch through the corrected configuration. **This is API-only today: there is no UI action
   for it yet**, and it works at whole-batch granularity — there is no separate "replay just the
   quarantined rows" mechanism. Both gaps are open UI/product work, not documented elsewhere.

**Investigate a failure.**
1. **Operations → Overview / Processing Status** — spot the anomaly.
2. **Events** — filter to the affected Pipeline/severity (turn on live-tail if it's ongoing).
3. **Diagnoses** — read the AI root-cause and suggested fix.
4. **Incidents** — raise/track the fix to resolution; group into a **Case** if it's part of a bigger
   pattern.

**Analyze and present.**
1. **Studio → Query Library** — write a Query over a Dataset and preview its Result Set.
2. **Viz Library** — build Widgets from that result.
3. **Dashboard Builder** — arrange Widgets into a Dashboard and share it in the Space.
4. **Link Analysis / Geo Map Analysis** — for relationship or geographic questions, investigate
   directly and save a View.

**Consume, schedule, and request (Business).**
1. **Business → KPI & Reports** — open a Dashboard; **Schedule export** to deliver it (CSV / PDF /
   PNG) to recipients on a cron schedule, or download the latest output.
2. **Requirements** — when something's missing, submit a Requirement (KPI / Report /
   Reconciliation / Rule); a Builder triages it and marks it delivered once built.
3. **Reconciliation** — define a keyed dataset-vs-dataset match with tolerances, run it, and work
   the resulting **Breaks** to resolution.

**Move configuration between instances.**
1. **Settings → Import & Export** — export a metadata bundle (definitions only, secrets masked).
2. On the target instance, import it and use the per-item preview to overwrite or skip.

**Tailor the menus for your team.**
1. **Studio → Menus** — build custom menu groups in the tree editor and check the live preview.
2. They appear in the sidebar **above the divider**, on top of the system menus.

---

## 9. Vocabulary quick reference

Inspecto uses these **canonical terms** exclusively (their common synonyms are deliberately
avoided — see [`GLOSSARY.md`](GLOSSARY.md)):

| Term | Means | Not |
|---|---|---|
| **Pipeline** | A named DAG of Steps that processes source files | ~~Flow~~ |
| **Dataset** | Any queryable relation: Table / Derived Table / View | ~~Data Store~~ |
| **Source** | A configured collection task | ~~Collector~~ (noun) |
| **Connection** | A named remote endpoint + credentials, reused by Sources | |
| **Stream** | A named data origin in the Catalog, populated by a Connection + its Sources | ~~Data Source~~ |
| **Run / Batch / File** | An execution and its nested units, each with a status | |
| **Expectation** | A data-quality rule against a Schema | bare ~~Rule~~ |
| **Alert Rule** | A rule that watches an observability Metric against a threshold | bare ~~Rule~~ |
| **Decision Rule** | A rule that transforms/routes records | bare ~~Rule~~ |
| **Incident / Case** | A tracked problem / a group of related Incidents | ~~Issue~~ |
| **Requirement** | A Business request — KPI / Report / Reconciliation / Rule — tracked to delivery | |
| **Reconciliation / Break** | A keyed Dataset-vs-Dataset comparison / one mismatch it surfaced | |
| **Report** | A scheduled delivery of a rendered Dashboard (CSV/PDF/PNG) to recipients | ~~operational report~~ / ~~Dashboard~~ |
| **Measure** | A BI aggregation | ~~Metric~~ (that's observability) |
| **Metric** | An observability time-series (throughput, error rate, lag) | ~~Measure~~ (that's BI) |
| **KPI** | A single-number Measure with a target, shown as a headline tile | |
| **Visualization Type / Widget** | The template / a configured instance of it | |
| **Query / Result Set** | Reusable executable knowledge / its semantic output | |
| **Lineage / Provenance** | Design-time asset graph / recorded runtime facts | |
| **Space** | A fully isolated project environment | |
| **Lens / Role** | A freely-switched persona view / an assigned authorization | |

---

*See also: [`GLOSSARY.md`](GLOSSARY.md) for exact definitions, [`INDEX.md`](INDEX.md) for the full
doc map, and the in-app **Design System** gallery (`/design`) for a live component reference.*
