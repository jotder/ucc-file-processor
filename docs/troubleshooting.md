# Troubleshooting

> Part of the [UCC File Processor](../file-processor/README.md) documentation. See the [docs index](../file-processor/README.md#documentation).

## Troubleshooting

### pg_duckdb: "column X does not exist" when querying a view

**Cause:** `read_parquet()` is a DuckDB table-valued function. PostgreSQL's planner cannot see its column names from the catalog, so a `SELECT *` view causes `column "event_date" does not exist` errors the moment you reference a column in `WHERE`, `GROUP BY`, or `ORDER BY`.

**Fix:** Every column must be explicitly aliased in the view using the `r['colname']` syntax:

```sql
CREATE OR REPLACE VIEW public.<data_source> AS
SELECT
  r['year']::integer           AS "year",
  r['ENTRY_DATE']::date        AS "ENTRY_DATE",
  r['EVENT_DATE']::timestamp   AS "EVENT_DATE",
  r['RECHARGE_CODE']::text     AS "RECHARGE_CODE",
  r['RECHARGE_AMT']::double precision AS "RECHARGE_AMT"
  -- ... all remaining columns
FROM read_parquet('/data/.../<data_source>/**/*.parquet', hive_partitioning := true) r;
```

With 100–500 columns this is impractical to write by hand. Use the included generator script instead:

```bash
# Run on the Linux server (where Parquet files live)
python3 generate_warehouse_views.py

# Apply the generated DDL
psql -U postgres -d yourdb -f warehouse_views_generated.sql
```

The script introspects the live Parquet schema via DuckDB and emits a complete `CREATE OR REPLACE VIEW` for every table with all columns mapped to their correct PostgreSQL types.

---

### All output lands in `year=NULL/month=NULL/day=NULL`

The partition key column value does not match any configured date format. Check the actual values in the source file and add the matching format to `date_formats` / `timestamp_formats` in the pipeline config.

### ORA-28002 files have extra junk lines leaking into data

The `skip_junk_lines` cap is too low. Oracle password-expiry warnings add extra preamble lines. Increase the cap — or set it to `-1` for unlimited scan. The adaptive detector will still find the first real data row correctly.

### Wrong-schema file is not quarantined (lands as SUCCESS with parsed=0)

This was a bug fixed in the current version. The quarantine condition now also catches files where every row has too few columns to exit junk detection (the `junkCandidateRows` counter). Update to the latest JAR if you see this.

### Config error: directory X must not be inside poll directory

The startup validator enforces that `database`, `backup`, `temp`, `errors`, `quarantine`, and `markers` are all siblings of the poll directory — not nested inside it. This prevents the ETL from recursing into its own output, scratch, or marker space. Move the offending directory in the pipeline config.

### JVM crash (EXCEPTION_ACCESS_VIOLATION in VCRUNTIME140.dll)

DuckDB 1.1.1 on Windows has a native AVX2 page-boundary bug triggered by large files. The current code works around this by materialising the transformation into an intermediate table (`CREATE TABLE transformed AS ...`) before `COPY TO`. If the crash recurs after a DuckDB version upgrade, check whether the workaround is still in place.

### DuckLake registration fails silently

Check stderr output for `DuckLake registration failed`. Common causes:
- PostgreSQL not reachable from the ETL server — verify `catalog_url` host/port/credentials
- `ducklake` extension download blocked — server needs outbound internet access on first run (extension is cached after initial download)
- `data_path` does not exist or is not writable

### `.processed` files prevent re-processing

Marker files are stored in `markers/` (not in `inbox/`), mirroring the inbox tree. Delete them to force reprocessing:
```bash
# Delete all markers for one adapter
find markers/<data_source>/ -name "*.processed" -delete

# Delete a single marker (e.g. to reprocess one specific file)
rm markers/<data_source>/20200403/adj_DATE_20200403.csv.gz.processed
```

Stale markers are pruned automatically at each poll start based on `processing.duplicate_check.retention_days`. You only need manual deletion to reprocess files within the retention window.

### Tar extraction: file already exists in target

The `extract` and `prepare-inbox` commands skip CSV files that already exist at the destination path (`[SKIP] Already exists: ...`). This is intentional — re-running after a partial failure is safe. Delete the destination file manually to force re-extraction.
