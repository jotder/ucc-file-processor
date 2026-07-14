# Demo space — editable sample catalog

One working, **editable** sample of every authorable component kind, over a tiny retail-orders
feed. Everything here is plain TOON on disk: edit a file and restart (or re-save it from the UI —
the same files back the UI editors).

**TOON syntax rules (verified against the live parser — violating them silently drops the config):**

- **No `#` comments, anywhere.** The suffix-scanned loaders reject the whole file; the registry
  reader mangles comment lines into junk keys. This README is the documentation instead.
- **Lists need a count.** Inline scalars: `members[1]: operator` · rows of maps use the tabular
  form: `tiles[3]{widgetId,span}:` followed by indented `value,value` rows. Bare `- item` lists
  do not parse (the one exception: authored flow `nodes[n]:` blocks support `- id:` items).
- **Job-type parameters are flat keys under `job:`** (e.g. `status: SHIPPED` feeds `$status`) —
  not wrapped in a `params:` block. Only `args:` and `bind:` are nested:

  ```
  job:
    name: casefile_export
    type: report
    on_signal: fraud.suspicious-activity
    when: "$signal.findings > 0"
    args:                    # static per-trigger parameter overrides
      format: pdf
    bind:                    # map a parameter to a signal payload field
      event_date: $signal.event_date
  ```

## Run it

```bash
# 1. Build once (if inspecto/target/file-processor-*.jar is missing)
mvn -o clean package -q

# 2. Seed the inbox from the pristine samples (the poll dir is CONSUMED by the engine)
pwsh spaces/demo/data/samples/seed-inbox.ps1        # or: bash spaces/demo/data/samples/seed-inbox.sh

# 3. Serve all spaces (control plane + UI on :8080)
java --enable-native-access=ALL-UNNAMED -Dspaces.root=spaces \
     -cp inspecto/target/file-processor-*.jar com.gamma.control.ControlApi
```

Space-scoped API calls use the path prefix: `GET /spaces/demo/pipelines`, `POST /spaces/demo/bi/query`, …
Sample input lives in `../data/samples/orders/` (committed, pristine — the seed script copies it
into the consumed inbox). Everything under `../data/` except `samples/` is generated and gitignored.

## Catalog

| Kind (canonical) | File(s) | Discovered via | UI surface |
|---|---|---|---|
| Space manifest | `../space.toon` | `-Dspaces.root` scan | Spaces switcher |
| Connection | `connections/demo_local_connection.toon` (offline-runnable), `connections/warehouse_sftp_connection.toon` (reference shape, `${ENV:…}` secret) | `*_connection.toon` | Workbench → Connections |
| Pipeline (Stage-1) | `orders/orders_pipeline.toon` + `orders/orders_schema.toon` (EXPR transform + derived GROSS; gap detection `ORDERS_{yyyyMMdd}`) | `*_pipeline.toon` | Pipelines |
| Enrichment (Stage-2) | `orders/orders_daily_enrich.toon` (on-commit + hourly rollup) | `*_enrich.toon` | Enrichment |
| Semantic catalog | `orders/orders_meta.toon` | `*_meta.toon` | Catalog |
| Alert Rule | `orders/orders_volume_alert.toon` (WARN while < 10 orders loaded) | `*_alert.toon` | Alerts |
| Job: template | `jobs/retention_job_template.toon` + instance `jobs/backup_retention_job.toon` | `*_job_template.toon` / `*_job.toon` | Workbench → Jobs |
| Job: cron maintenance | `jobs/db_maintenance_job.toon` | `*_job.toon` | Workbench → Jobs |
| Job: retention (System Maintenance P1) | `jobs/runlog_retention_job.toon` (`runlog_prune`, 90d; preview via `POST …/trigger?dryRun=true`) | `*_job.toon` | Workbench → Jobs |
| Job: nightly maintenance chain (System Maintenance P3, MNT-13) | `runlog_retention` (cron 03:15 head) → `db_maintenance` → `config_backup` (instance of `jobs/chained_backup_job_template.toon`) → `backup_verify` (`on_signal: maintenance.backup.completed`) → `maintenance_report` — each link guarded `when: "$signal.job == <prev> && $signal.outcome == SUCCESS"`, so a failure halts the chain | `*_job.toon` + `*_job_template.toon` | Workbench → Jobs · System Maintenance → Overview |
| Job: sql.template | `jobs/orders_summary_sql_job.toon` (`$status` param, Run Artifact + `job.dataset.produced` signal) | `*_job.toon` | Workbench → Jobs |
| Job: on-signal | `jobs/orders_summary_followup_job.toon` (`on_signal` + `when` guard; `args`/`bind` shapes above) | `*_job.toon` | Workbench → Jobs |
| Job: authored Pipeline | `jobs/orders_rollup_job.toon` runs `flows/orders_rollup_flow.toon` on each orders commit | `*_job.toon` + `flows/` | Pipelines (authored) |
| Incident queue | `ops/demo_ops_queue.toon` | `*_queue.toon` | Incidents |
| SLA escalation | `ops/sla_escalation.toon` | `*_escalation.toon` | Incidents |
| RCA template | `ops/orders_rca.toon` | `*_rca.toon` | Incidents |
| Dataset | `registry/datasets/orders_dataset.toon`, `…/orders_enriched_dataset.toon` (DAT-5 calculated columns) | `registry/datasets/` | Studio → Datasets |
| Query | `registry/queries/orders_by_region.toon` (SQL + `$minAmount` parameter) | `registry/queries/` | Studio → Query Library |
| Expectation | `registry/expectations/orders_id_non_null.toon`, `…/orders_gross_non_null.toon` | `registry/expectations/` | Expectations |
| Widget | `registry/widgets/orders_kpi_total.toon` (kpi), `…/orders_revenue_by_region.toon` (bar), `…/orders_daily_trend.toon` (line + series) | `registry/widgets/` | Studio → Viz Library / Widget Builder |
| Dashboard | `registry/dashboards/orders_overview.toon` | `registry/dashboards/` | Studio → Dashboard Builder |
| Grammar / Schema / Transform / Sink (reusable parts) | `registry/grammars/pipe_delimited.toon`, `registry/schemas/payments_schema.toon`, `registry/transforms/mask_pii.toon`, `registry/sinks/parquet_sink.toon` | `registry/<type>/` | Studio → Components |
| Requirement | `registry/requirements/orders_kpi_requirement.toon` | `registry/requirements/` | Requirements |
| Saved views | `registry/link-analysis-views/orders_link_view.toon`, `registry/geo-map-views/orders_geo_view.toon` (freeform content) | `registry/<type>/` | Link Analysis / Geo Map studios |
| Reconciliation (DAT-7) | `registry/reconciliations/orders_regional_recon.toon` (3-way anchor: raw vs enriched vs SHIPPED-only rollup, keyed by REGION — the rollup side shows real breaks) | `registry/reconciliations/` | Reconciliation |
| Decision Rule | `registry/decision-rules/orders_high_value_review.toon` (quarantine + tag consequences) | `registry/decision-rules/` | Decision Rules |
| Reference Dataset | `orders/orders_daily_enrich.toon` `references:` block joins `data/ref/region_dim.csv` (seeded from `../data/samples/ref/`) | enrichment `references:` | Catalog → References |
| Tag | `ops/hot_tag.toon` | `*_tag.toon` | Incidents (tags) |
| Tag Rule | `ops/critical_incidents_tagrule.toon` (CRITICAL incidents auto-tag `hot`) | `*_tagrule.toon` | Incidents → Tag Rules |
| Case Rule | `ops/incident_burst_caserule.toon` (2+ CRITICAL incidents in 4h raise a correlated case) | `*_caserule.toon` | Case Manager → Case Rules |

UI saves land in these exact locations: registry kinds → `registry/<type>/<id>.toon` (with
version history in `registry/<type>/.history/`), connections → `<id>_connection.toon`, authored
pipelines → `flows/<name>.toon`.

## Manual test loops

- **Ingest**: seed the inbox (step 2) → within a poll cycle the 3 daily files land as
  Hive-partitioned Parquet under `../data/orders/database/`; the 2 deliberately malformed rows in
  `ORDERS_20260703.csv` are split to `../data/orders/errors/` (structural rejects) while good rows
  still load. Re-run the seed script: the duplicate check skips already-processed files.
- **Alert**: on first boot `orders_low_volume` WARNs (no data). Seed + ingest (34 rows) clears it.
- **Sequence gap**: seed, but delete `ORDERS_20260702.csv` from `../data/inbox/orders/` before the
  poll cycle → a `SEQUENCE_GAP` event/alert fires for the missing day.
- **Jobs chain**: `POST /spaces/demo/jobs/orders_summary/trigger` → the sql.template job writes the
  `orders_summary` dataset + Run Artifact and emits `job.dataset.produced`, which triggers
  `orders_summary_followup` (watch both under `/spaces/demo/jobs/<name>/runs`). Body
  `{"params":{"status":"NEW"}}` overrides the `$status` parameter for one run.
- **BI**: `POST /spaces/demo/bi/query` with
  `{"dataset":"orders_dataset","measures":[{"agg":"sum","field":"gross"}],"groupBy":["region"]}` —
  or just open the demo Dashboard in the UI.

## Operational sample data (incidents / cases / tags in action)

Incidents and cases are runtime objects (DB rows, not TOON) — seed a demo spread through the REAL
routes with `pwsh spaces/demo/data/samples/seed-ops.ps1` (or `bash …/seed-ops.sh`) once the server is
up: 8 incidents across the priority ladder (the shipped tag rule auto-tags the CRITICAL ones `hot`),
2 cases with linked members, and one `incident_burst` case-rule evaluation (a rule-raised case).
Durable across restarts only with `-Dobjects.backend=db`.

## Not represented here (by design)

- **Notification channels (webhook/SMTP)** are JVM flags, not config files:
  `-Dnotify.webhook.url=… -Dnotify.webhook.token=…` / `-Dnotify.smtp.host=… -Dnotify.smtp.from=…
  -Dnotify.smtp.to=…` on the serve command.
- **Runnable feature-by-feature examples** (one folder per feature, batch runner) live in
  `inspecto/examples/` — this space instead shows every kind wired together and editable in one place.
- **Space templates** are server-shipped, not per-space: `spaces/_templates/<id>/` (gallery =
  `GET /spaces/templates`; `POST /spaces {id, template}` clones one with `${SPACE}` rewritten).
