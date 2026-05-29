# Integrations: DuckLake & Warehouse Query Layer

> Part of the [UCC File Processor](../file-processor/README.md) documentation. See the [docs index](../file-processor/README.md#documentation).

## DuckLake Integration

DuckLake is a lakehouse format that uses a SQL database (PostgreSQL) as the catalog/metadata store, with data stored as Parquet files. This lets remote clients query the data using standard DuckDB tooling.

### Setup

1. **Enable PostgreSQL** on the server and create a database for the catalog:
   ```sql
   CREATE DATABASE ducklake_db;
   ```

2. **Configure the pipeline** (`<data_source>_pipeline.toon`):
   ```yaml
   output:
     format: PARQUET
     compression: snappy
     ducklake:
       enabled: true
       catalog_url: "postgresql://etl_user:password@localhost:5432/ducklake_db"
       data_path: "/opt/adj-lake"
       schema: <data_source>s
       table: <data_source>_data
   ```

3. **Run the ETL.** After each file is written, SourceProcessor will:
   - `INSTALL ducklake FROM core` (downloads on first run; cached thereafter)
   - `ATTACH` the PostgreSQL catalog
   - Create the schema and table if they do not exist
   - `INSERT INTO` the DuckLake table by reading the just-written Parquet files

   DuckLake registration is **non-fatal** — if it fails (e.g. PostgreSQL unreachable), the file is still marked processed and the failure is logged to stderr. The Parquet output on disk is unaffected.

### Remote access via DBeaver

Each remote user installs the **DuckDB JDBC driver** in DBeaver and connects using the ducklake extension pointed at the same PostgreSQL catalog. Parquet files must be on a path accessible from the client (network share / NFS mount).

```sql
-- In a DBeaver DuckDB connection
INSTALL ducklake FROM core;
LOAD ducklake;
ATTACH 'ducklake:postgresql://user:password@server:5432/ducklake_db'
    AS lake (DATA_PATH '/mnt/adj-lake');

SELECT * FROM lake.<data_source>s.<data_source>_data
WHERE year = '2000' AND month = '01'
LIMIT 100;
```

---

## Warehouse Query Layer — DBeaver via pg_duckdb

Parquet output can be queried directly from DBeaver (or any PostgreSQL client) without loading data into PostgreSQL. The `pg_duckdb` extension embeds DuckDB inside PostgreSQL as a transparent execution engine — users connect with a standard PostgreSQL driver and DuckDB is invisible to them.

```
DBeaver (laptop)  →  PostgreSQL :5432  →  pg_duckdb extension  →  database/**/*.parquet
```

No data is copied into PostgreSQL. PostgreSQL handles only the wire protocol; DuckDB does all I/O and vectorised execution against the Parquet files on disk.

### One-time server setup

**1. Install pg_duckdb on the Linux server**

```bash
# PostgreSQL 16 example — replace version number as needed
apt-get install -y postgresql-16-pgduckdb

# Enable the extension (requires a PostgreSQL restart)
psql -U postgres -c "ALTER SYSTEM SET shared_preload_libraries = 'pg_duckdb';"
sudo systemctl restart postgresql
```

**2. Apply `warehouse_setup.sql`**

```bash
# From the bundle root — substitute your actual data path
export DATA_ROOT=/opt/ura/sandbox
sed "s|DATA_ROOT|${DATA_ROOT}|g" warehouse_setup.sql > warehouse_setup_final.sql
psql -U postgres -d yourdb -f warehouse_setup_final.sql
```

**3. Create login accounts** (edit the commented block at the bottom of the file):

```sql
CREATE USER alice WITH PASSWORD 'changeme' IN ROLE analyst;
CREATE USER bob   WITH PASSWORD 'changeme' IN ROLE <data_source>_analyst;
```

### Views in the `warehouse` schema

| View | Source path | Columns | Partition key |
|---|---|---|---|
| `<data_source>_cdr` | `database/<data_source>/<data_source>_cdr/**` | 537 | EVENT_DATE (extracted from filename) |
| `<data_source>_main` | `database/<data_source>/<data_source>_main/**` | 116 | TRANSACTION_START_DATE |
| `<data_source>_other` | `database/<data_source>/<data_source>_other/**` | 76 | TRANSACTION_START_DATE |
| `<data_source>` | `database/<data_source>/**` | 477 | REVERSAL_DATE |
| `<data_source>_all` | union of all 3 <data_source> views | common cols | — |
| `data_catalog` | partition summary across all tables | — | — |

### Roles

| Role | Access |
|---|---|
| `analyst` | all warehouse views |
| `<data_source>_analyst` | <data_source>_cdr, <data_source>_main, <data_source>_other, <data_source>_all |
| `<data_source>_analyst` | <data_source> only |

### DBeaver connection

Use the standard **PostgreSQL** driver. No special configuration needed.

| Field | Value |
|---|---|
| Host | `your-linux-server` |
| Port | `5432` |
| Database | `yourdb` |
| Driver | PostgreSQL |

Partition pruning is automatic — DuckDB reads only the files that match the `WHERE` predicates:

```sql
-- Check what data has landed across all tables
SELECT * FROM warehouse.data_catalog ORDER BY table_name, year, month, day;

-- Query with partition pruning (reads only year=2020/month=01/day=01 files)
SELECT * FROM warehouse.<data_source>_cdr
WHERE year = 2020 AND month = 1 AND day = 1
LIMIT 100;
```

---

