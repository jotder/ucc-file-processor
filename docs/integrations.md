# Integrations: Remote Sources, DuckLake & Warehouse Query Layer

> Part of the [Inspecto](../inspecto/README.md) documentation. See the [docs index](../inspecto/README.md#documentation).

## Remote source connectors (SFTP / FTP / FTPS)

Inspecto can pull input files from remote SFTP/FTP/FTPS servers instead of a local `dirs.poll` tree. The
connectors live in the **optional `inspecto-connectors` module** (artifact `file-processor-connectors`) so their
network dependencies — sshj (+BouncyCastle) for SFTP, Apache commons-net for FTP/FTPS — never bloat the lean core
JAR. Put that JAR on the classpath and the connectors are discovered automatically via `ServiceLoader`; without
it, only the built-in `local` connector exists. New protocols (S3/GCS/Azure, NFS/SMB) are future connectors that
plug into the same SPI without touching the core engine.

### 1. Define a connection profile (`<name>_connection.toon`)

Reachability and credentials live in a reusable profile, referenced by one or more pipelines. **Secrets are
references, never literals** — `${ENV:VAR}` reads an environment variable, `${SYS:prop}` a JVM system property.

```yaml
connection:
  id: prod_sftp
  connector: sftp                 # sftp | ftp | ftps
  host: sftp.partner.example.com
  port: 22
  base_path: /outbound/cdr        # listing root
  username: inspecto
  password: ${ENV:SFTP_PASSWORD}  # reference; resolved at connect, masked in API output
  options:
    private_key: /etc/inspecto/id_ed25519   # SFTP: switches to public-key auth
    host_key: "SHA256:abc…"                  # SFTP: pin the server key (fingerprint) — reject anything else
    # known_hosts: /etc/inspecto/known_hosts  strict_host_key: true   # alternatives (see below)
    # tls: explicit   tls_trust: all          # FTPS knobs (see below); explicit|implicit
    # active: false   binary: true            # FTP-only knobs (passive + binary default)
  tunnel:                          # optional SSH bastion (SFTP); omit for a direct connection
    host: bastion.example.com
    port: 22
    username: jump
    password: ${ENV:BASTION_PASSWORD}
```

> **No `#` comment lines** in `*_connection.toon` (or any `ConfigCodec` file) — JToon rejects them.

### 2. Bind a pipeline to it

```yaml
source:
  connector: sftp
  connection: prod_sftp
  stability: { ready_marker: "{name}.done" }   # only fetch files the sender has finished writing
  duplicate: { mode: METADATA }                 # don't re-fetch unchanged files
  fetch:     { parallel_fetch: 4, rate_limit: 50MBps }
  retry:     { count: 5, backoff: EXPONENTIAL }
  post_action: { on_success: MOVE, archive_path: archive/yyyy/MM/dd }
```

The full set of `source:` knobs (stability, dedup, integrity, retry, circuit breaker, post-actions, parallel
fetch, rate limit) is documented in [configuration.md](configuration.md#data-acquisition--the-source-block).
Remote files are fetched into the local staging tree and then flow through the *exact same* batch/dedup/backup
engine as local files — nothing downstream special-cases them.

### 3. Verify reachability before a run

```bash
curl -s localhost:8080/connections                  # list loaded profiles (secrets masked)
curl -s localhost:8080/connections/prod_sftp         # one profile
curl -s -X POST localhost:8080/connections/prod_sftp/test   # TCP reachability + latency + secret resolution
```

The Connections pane in the UI lists profiles with a per-row **Test** action over the same endpoints.

### SSH host-key pinning (SFTP)

By default the SFTP client accepts whatever host key the server presents on first connect (convenient, but no
defence against a man-in-the-middle or a silently changed host). Pin it via the profile `options`:

| Option | Effect |
|---|---|
| `host_key` | A key fingerprint (`SHA256:<base64>` or OpenSSH MD5 colon-hex). The presented key must match exactly — best for a single direct host. |
| `known_hosts` | Path to an OpenSSH `known_hosts` file; the host must have an entry. Works across hops (a bastion **and** a target). |
| `strict_host_key: true` | When set and neither of the above is configured, **refuse to connect** rather than silently accept any key. |

Over an SSH tunnel, a single `host_key` fingerprint pins the **target** SFTP server (a fingerprint matches one
host); use `known_hosts` to verify the bastion as well. For a `db`-export profile reached through a tunnel,
`host_key`/`known_hosts` pin the **bastion** (the only SSH hop). With none of these set, the legacy
accept-on-connect behaviour is unchanged — pinning is purely additive.

### FTPS (FTP over TLS)

Set `connector: ftps` (defaults to explicit/`AUTH TLS`), or keep `connector: ftp` and add `options.tls`:

| Option | Values | Effect |
|---|---|---|
| `tls` | `explicit` (FTPES, port 21) · `implicit` (TLS-first, port 990) · `none` | Enables TLS; the control **and** data channels are encrypted (`PBSZ 0` + `PROT P`). |
| `tls_trust` | `all` · *(unset)* | `all` accepts any server certificate (self-signed / internal CA — still encrypted, **not** authenticated). Unset validates against the JVM trust store (the secure default; works for publicly-signed certs out of the box). |

```yaml
connection:
  id: partner_ftps
  connector: ftps                 # explicit AUTH TLS by default
  host: ftps.partner.example.com
  username: inspecto
  password: ${ENV:FTPS_PASSWORD}
  options: { tls_trust: all }     # for a self-signed / internal-CA server
```

### DB-export source (SQL → CSV)

A `connector: db` profile turns a **database query** into an acquired file: the connector runs `options.query`
against a JDBC database and materialises the result set as CSV, which then flows through the normal batch path.
The PostgreSQL driver ships in the connectors module (default target), but the connector is JDBC-generic — any
driver on the classpath works.

```yaml
connection:
  id: cdr_export
  connector: db
  options:
    jdbc_url: jdbc:postgresql://db.example.com:5432/warehouse   # or omit + set host/port/database
    query: "SELECT * FROM cdr WHERE event_date = '{yyyy-MM-dd}'"  # {…} = a date pattern, resolved per run
    export_name: "cdr_{yyyyMMdd}.csv"                              # stable per-slice name ⇒ dedup re-exports once
    # driver: org.postgresql.Driver        # optional explicit driver class
  username: warehouse_ro
  password: ${ENV:WAREHOUSE_PW}
  tunnel: { host: bastion.example.com, username: jump, password: ${ENV:BASTION_PW} }   # optional SSH tunnel to the DB
```

The date-templated `query`/`export_name` give idempotent **per-slice** export (each cycle exports a fresh slice;
the marker/ledger dedup re-runs the same slice only once). It is a `STREAM`-only source — there's no source-side
file to move/delete, so leave `source.post_action` unset.

---

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

