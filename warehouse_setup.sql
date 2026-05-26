-- ============================================================
-- URA Warehouse Setup
-- Run once on the Linux PostgreSQL server as superuser
--
-- Replace DATA_ROOT with the absolute path to the sandbox root
-- e.g.:  sed -i 's|DATA_ROOT|/opt/ura/sandbox|g' warehouse_setup.sql
-- ============================================================

-- Step 1: enable pg_duckdb (requires shared_preload_libraries restart first)
CREATE EXTENSION IF NOT EXISTS pg_duckdb;

-- Step 2: warehouse schema (all analytics views live here)
CREATE SCHEMA IF NOT EXISTS warehouse;

-- ============================================================
-- IMPORTANT: pg_duckdb column aliasing requirement
--
-- read_parquet() is a DuckDB table-valued function. PostgreSQL's
-- planner cannot see its column names from the catalog, so a plain
-- SELECT * view causes "column X does not exist" errors when you
-- reference columns in WHERE / GROUP BY / ORDER BY.
--
-- The fix: give the TVF an alias (r) and map every column explicitly:
--   r['COLNAME']::pgtype AS "COLNAME"
--
-- With 100-500+ columns this is impractical to write by hand.
-- Use generate_warehouse_views.py to auto-generate the DDL from
-- the live Parquet files on disk, then apply that output instead
-- of the stub views below.
--
-- Quick start:
--   python3 generate_warehouse_views.py          # run on Linux server
--   psql -U postgres -d yourdb \
--        -f warehouse_views_generated.sql        # apply generated DDL
-- ============================================================

-- ============================================================
-- VOUCHER VIEWS
-- Pipeline: VOUCHER_ETL
-- Base path: DATA_ROOT/database/voucher/<table>/year=*/month=*/day=*/*.parquet
-- Sub-tables dispatched by column count of source CSV
--
-- NOTE: The stubs below use the r['col'] pattern for a handful of
-- common columns only. Run generate_warehouse_views.py to produce
-- a full version with all columns mapped.
-- ============================================================

-- 537-column CDR variant  (source glob: cbs_cdr_vou_*)
CREATE OR REPLACE VIEW public.voucher_cdr AS
SELECT
  r['year']::integer            AS "year",
  r['month']::integer           AS "month",
  r['day']::integer             AS "day",
  r['ENTRY_DATE']::date         AS "ENTRY_DATE",
  r['EVENT_DATE']::timestamp    AS "EVENT_DATE",
  r['REVERSAL_DATE']::date      AS "REVERSAL_DATE",
  r['RECHARGE_LOG_ID']::text    AS "RECHARGE_LOG_ID",
  r['RECHARGE_CODE']::text      AS "RECHARGE_CODE",
  r['RECHARGE_AMT']::double precision  AS "RECHARGE_AMT",
  r['ACCT_ID_2']::text          AS "ACCT_ID_2",
  r['SUB_ID']::text             AS "SUB_ID",
  r['REMARK']::text             AS "REMARK",
  r['BALANCE_TYPE_1']::text     AS "BALANCE_TYPE_1"
  -- remaining columns added by generate_warehouse_views.py
FROM read_parquet(
    'DATA_ROOT/database/voucher/voucher_cdr/**/*.parquet',
    hive_partitioning := true
) r;

-- 116-column MAIN variant  (source glob: *VOU_MAIN*)
CREATE OR REPLACE VIEW public.voucher_main AS
SELECT
  r['year']::integer            AS "year",
  r['month']::integer           AS "month",
  r['day']::integer             AS "day",
  r['ENTRY_DATE']::date         AS "ENTRY_DATE",
  r['EVENT_DATE']::timestamp    AS "EVENT_DATE",
  r['REVERSAL_DATE']::date      AS "REVERSAL_DATE",
  r['RECHARGE_CODE']::text      AS "RECHARGE_CODE",
  r['RECHARGE_AMT']::double precision  AS "RECHARGE_AMT",
  r['REMARK']::text             AS "REMARK",
  r['BALANCE_TYPE_1']::text     AS "BALANCE_TYPE_1"
  -- remaining columns added by generate_warehouse_views.py
FROM read_parquet(
    'DATA_ROOT/database/voucher/voucher_main/**/*.parquet',
    hive_partitioning := true
) r;

-- 76-column OTHER variant  (source glob: *VOU_OTHER*)
CREATE OR REPLACE VIEW public.voucher_other AS
SELECT
  r['year']::integer            AS "year",
  r['month']::integer           AS "month",
  r['day']::integer             AS "day",
  r['ENTRY_DATE']::date         AS "ENTRY_DATE",
  r['EVENT_DATE']::timestamp    AS "EVENT_DATE",
  r['REVERSAL_DATE']::date      AS "REVERSAL_DATE",
  r['RECHARGE_CODE']::text      AS "RECHARGE_CODE",
  r['RECHARGE_AMT']::double precision  AS "RECHARGE_AMT",
  r['REMARK']::text             AS "REMARK"
  -- remaining columns added by generate_warehouse_views.py
FROM read_parquet(
    'DATA_ROOT/database/voucher/voucher_other/**/*.parquet',
    hive_partitioning := true
) r;

-- ============================================================
-- ADJUSTMENT VIEW
-- Pipeline: ADJUSTMENT_ETL
-- Base path: DATA_ROOT/database/adjustment/year=*/month=*/day=*/*.parquet
-- Single schema, 477 columns, partitioned by REVERSAL_DATE
-- ============================================================

CREATE OR REPLACE VIEW public.adjustment AS
SELECT
  r['year']::integer            AS "year",
  r['month']::integer           AS "month",
  r['day']::integer             AS "day",
  r['REVERSAL_DATE']::date      AS "REVERSAL_DATE",
  r['ENTRY_DATE']::date         AS "ENTRY_DATE",
  r['ADJUST_LOG_ID']::text      AS "ADJUST_LOG_ID",
  r['ACCT_ID_1']::text          AS "ACCT_ID_1",
  r['CUST_ID']::text            AS "CUST_ID",
  r['ADJUST_AMT']::double precision  AS "ADJUST_AMT",
  r['STATUS']::text             AS "STATUS"
  -- remaining columns added by generate_warehouse_views.py
FROM read_parquet(
    'DATA_ROOT/database/adjustment/**/*.parquet',
    hive_partitioning := true
) r;

-- ============================================================
-- CONVENIENCE: unified voucher view (all 3 variants unioned)
-- Only selects columns that exist in all 3 schemas.
-- Extend the column list once schemas are confirmed stable.
-- ============================================================

CREATE OR REPLACE VIEW warehouse.voucher_all AS
    SELECT 'cdr'   AS source_variant, year, month, day
    FROM warehouse.voucher_cdr
  UNION ALL
    SELECT 'main'  AS source_variant, year, month, day
    FROM warehouse.voucher_main
  UNION ALL
    SELECT 'other' AS source_variant, year, month, day
    FROM warehouse.voucher_other;

-- ============================================================
-- DATA CATALOG VIEW
-- Shows which partitions have landed — useful for checking
-- data availability before running large queries.
-- ============================================================

CREATE OR REPLACE VIEW warehouse.data_catalog AS
SELECT 'voucher_cdr'  AS table_name, year, month, day, COUNT(*) AS row_count
FROM warehouse.voucher_cdr
GROUP BY year, month, day

UNION ALL

SELECT 'voucher_main' AS table_name, year, month, day, COUNT(*) AS row_count
FROM warehouse.voucher_main
GROUP BY year, month, day

UNION ALL

SELECT 'voucher_other' AS table_name, year, month, day, COUNT(*) AS row_count
FROM warehouse.voucher_other
GROUP BY year, month, day

UNION ALL

SELECT 'adjustment'   AS table_name, year, month, day, COUNT(*) AS row_count
FROM warehouse.adjustment
GROUP BY year, month, day

ORDER BY table_name, year, month, day;

-- ============================================================
-- ROLES AND ACCESS CONTROL
-- ============================================================

-- Role: analyst
-- Full read access to everything in warehouse.
-- Assign to most team members.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'analyst') THEN
        CREATE ROLE analyst NOLOGIN;
    END IF;
END $$;

GRANT USAGE ON SCHEMA warehouse TO analyst;
GRANT SELECT ON ALL TABLES IN SCHEMA warehouse TO analyst;
ALTER DEFAULT PRIVILEGES IN SCHEMA warehouse GRANT SELECT ON TABLES TO analyst;

-- Role: voucher_analyst
-- Read access to voucher tables only.
-- Assign to team members who should NOT see adjustment data.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'voucher_analyst') THEN
        CREATE ROLE voucher_analyst NOLOGIN;
    END IF;
END $$;

GRANT USAGE ON SCHEMA warehouse TO voucher_analyst;
GRANT SELECT ON warehouse.voucher_cdr   TO voucher_analyst;
GRANT SELECT ON warehouse.voucher_main  TO voucher_analyst;
GRANT SELECT ON warehouse.voucher_other TO voucher_analyst;
GRANT SELECT ON warehouse.voucher_all   TO voucher_analyst;
GRANT SELECT ON warehouse.data_catalog  TO voucher_analyst;

-- Role: adjustment_analyst
-- Read access to adjustment table only.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'adjustment_analyst') THEN
        CREATE ROLE adjustment_analyst NOLOGIN;
    END IF;
END $$;

GRANT USAGE ON SCHEMA warehouse TO adjustment_analyst;
GRANT SELECT ON warehouse.adjustment   TO adjustment_analyst;
GRANT SELECT ON warehouse.data_catalog TO adjustment_analyst;

-- ============================================================
-- USER ACCOUNTS
-- Create one login user per team member, assign a role.
-- Pattern:
--   CREATE USER alice WITH PASSWORD 'changeme' IN ROLE analyst;
--   CREATE USER bob   WITH PASSWORD 'changeme' IN ROLE voucher_analyst;
-- ============================================================

-- Uncomment and edit for each team member:
-- CREATE USER alice WITH PASSWORD 'changeme' IN ROLE analyst;
-- CREATE USER bob   WITH PASSWORD 'changeme' IN ROLE voucher_analyst;
-- CREATE USER carol WITH PASSWORD 'changeme' IN ROLE adjustment_analyst;

-- ============================================================
-- VERIFICATION QUERIES
-- Run these after setup to confirm everything works.
-- ============================================================

-- Check extension loaded
-- SELECT * FROM pg_extension WHERE extname = 'pg_duckdb';

-- Check catalog (shows partition counts per table)
-- SELECT * FROM warehouse.data_catalog ORDER BY table_name, year, month, day;

-- Spot-check a table (replace year/month/day as appropriate)
-- SELECT * FROM warehouse.voucher_cdr WHERE year = 2020 AND month = 1 AND day = 1 LIMIT 10;
-- SELECT * FROM warehouse.adjustment   WHERE year = 2020 AND month = 1 AND day = 1 LIMIT 10;
