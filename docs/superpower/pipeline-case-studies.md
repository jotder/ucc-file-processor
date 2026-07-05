# Pipelines — case-study pack (CS1–CS5)

Five boundary-pushing, self-contained authored pipelines for the Pipelines editor (`/pipelines`),
seeded per space by `inspecto-ui/src/app/inspecto/mock/seeds/pipeline-case-studies.seed.ts` (wired
into `default-space.seed.ts`, store key `inspecto.mock.v10`). Each ships with its **data source**
(an acquisition Source entry on the Sources page, bound to a seeded Connection) and, where parsing
is involved, **reusable grammars** covering every exotic parser format. Invariants are pinned by
`modules/admin/pipelines/pipeline-case-studies.spec.ts`, so future edits can't silently defuse what
a case study exists to exercise. Mirrors the Geo Map pack
([`geo-map-case-studies.md`](geo-map-case-studies.md)).

| # | Pipeline | Source → Connection | The boundary it pushes |
|---|---|---|---|
| CS1 | `mediation_backbone` (active) | `sftp_cdr_asn1` → `cdr_sftp_prod` (+ stream + warehouse) | **Canvas scale**: 19+ nodes, 3 collectors, 4 named `route:` branches fanning back into one aggregate, 3 sinks |
| CS2 | `fraud_velocity_stream` (active) | `kafka_sim_swaps` → kafka topic | **Clone-mode route** (same rows teed to real-time triage AND archive), CRITICAL alert, upsert sink with key columns |
| CS3 | `audit_recon_feeds` (draft) | `s3_switch_dumps` → `s3_archive` | **Three disconnected components** on one canvas (two independent legs + an unwired draft island); `:watermark` extract + `gap` alert |
| CS4 | `format_gauntlet` (draft) | `local_dropzone` → local (no connection) | **Every parser format**: extension-routed into XLSX/XML/HTML/Parquet/fixed-width TXT grammars, every `unmatched` wired, 5-way fan-in |
| CS5 | `deadletter_torture` (active) | `ftp_legacy_gold` → `legacy_ftp_down` | **Every control relation** (`failure`/`gap`/`unmatched`/`dropped`/`invalid`) on a 9-stage chain, dead-letter table, two alert severities |

## CS1 — Mediation backbone *(revenue assurance, scale test)*

Three collectors (SFTP ASN.1 drops, a Kafka CDR event bus, a watermarked roaming extract) parse and
normalize into a dedup gate, split four ways by service type (`route:voice/sms/data/roaming`), rate
per service, and fan back into one revenue rollup feeding a Parquet lake, a warehouse upsert and a
CRITICAL revenue-drop alert; both parsers quarantine their `unmatched` rows.
**Drive it:** open on the canvas — this is the layout/zoom/edge-label stress case. Double-click the
ASN.1 parser (module picker pre-bound to `cdr_3gpp_ts32297` via `grammar/cdr_asn1_ber`), try
run-to-here at `dedup`, and follow one `route:` label end-to-end.

## CS2 — Fraud velocity stream *(fraud management, streaming shapes)*

A SIM-swap event stream parsed as NDJSON, velocity derived per event, then **cloned** — the same
rows flow to a high-risk filter → impossible-travel alert → `fraud_candidates` upsert, AND to
5-minute window aggregation → Parquet archive.
**Drive it:** the `clone` node is the teaching moment (route `mode: clone` vs CS1's `case`) — both
branch labels are `route:*` but the semantics differ. Check the upsert sink's `key_columns`.

## CS3 — Audit reconciliation feeds *(financial audit, disconnected authoring)*

Two legs that never touch: an S3 switch-dump leg (ASN.1 → normalize → `switch_cdr` replace-load)
and a watermarked billing extract (with a `gap` alert when the watermark stalls) → `billing_cdr`.
Plus a deliberately **unwired 2-node draft island** (a legacy mainframe leg pending sign-off, bound
to the down FTP connection). Loads the two sides the seeded `switch_vs_billing` reconciliation
compares.
**Drive it:** three disconnected subgraphs on one canvas — layout, selection and activation
validation must all stay sane. The draft island is what "work in progress" looks like.

## CS4 — Format gauntlet *(parser coverage)*

One mixed dropzone routed by file extension into five parsers, each bound to a reusable grammar:
regulatory **XLSX** (sheet + skip_rows), invoice **XML** (record_xpath), scraped tariff **HTML**
(table_selector), **Parquet** re-ingest (column projection) and mainframe **fixed-width TXT**
(record_length 128). All five `unmatched` branches hit one reject pile; all five successes fan into
one union normalize → filter → gzip CSV.
**Drive it:** double-click each parser — the config dialog must open the right format sheet from
the bound grammar. Together with CS1's ASN.1 and CS2's NDJSON the pack covers **all** parser
formats the dialog offers.

## CS5 — Dead-letter torture *(operability, failure paths)*

A 9-stage gold-feed chain deliberately hung off the **known-down** `legacy_ftp_down` connection,
with every control relation wired somewhere: collector `failure`+`gap` → feed-health alert
(WARNING), parser `unmatched` → quarantine CSV, `failure` → dead-letter alert (CRITICAL), record
`invalid` → dead-letter table, filter `dropped` → sample sink, and even the final sink's `failure`
alerting.
**Drive it:** this is the control-edge rendering stress (7 relation kinds on one canvas) and the
"what does good error handling look like" reference. The connection workbench shows the FTP source
unreachable — which is the point.

## Testing & verification

- **Unit invariants:** `pipeline-case-studies.spec.ts` — structural sanity for all five (unique
  ids, resolvable edges, every `use:` reference resolving to a seeded connection/grammar), full
  parser-format coverage, plus the per-CS boundary each exists for (node/branch/fan-in counts,
  clone mode, component count 3, unmatched wiring, control-relation superset, chain depth).
- **Look & feel:** every pipeline is one click on `/pipelines`; parser dialogs must open their
  bound grammar's format sheet; CS3 must render three islands; edge labels must stay legible on CS1.
- Regenerating: the pipelines are code — edit `pipeline-case-studies.seed.ts`, keep the spec green
  (it is the contract for what each case study must keep exercising), and bump `MOCK_STORE_KEY`.
