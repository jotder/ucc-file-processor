-- ============================================================
-- DBeaver / DuckDB native warehouse setup
-- Run once in DBeaver after creating a DuckDB connection.
-- No r['colname'] syntax needed — pure DuckDB, plain SQL.
--
-- Connection: New Connection → DuckDB → /opt/ura/warehouse.duckdb
-- ============================================================

-- ── VOUCHER CDR (537 columns, source: cbs_cdr_vou_*) ─────────────────────────
CREATE OR REPLACE VIEW voucher_cdr AS
SELECT *
FROM read_parquet(
    '/data/gamma/airtel/database/voucher/voucher_cdr/**/*.parquet',
    hive_partitioning := true
);

-- ── VOUCHER MAIN (116 columns, source: *VOU_MAIN*) ───────────────────────────
CREATE OR REPLACE VIEW voucher_main AS
SELECT *
FROM read_parquet(
    '/data/gamma/airtel/database/voucher/voucher_main/**/*.parquet',
    hive_partitioning := true
);

-- ── VOUCHER OTHER (76 columns, source: *VOU_OTHER*) ──────────────────────────
CREATE OR REPLACE VIEW voucher_other AS
SELECT *
FROM read_parquet(
    '/data/gamma/airtel/database/voucher/voucher_other/**/*.parquet',
    hive_partitioning := true
);

-- ── ADJUSTMENT (477 columns, partition key: REVERSAL_DATE) ───────────────────
CREATE OR REPLACE VIEW adjustment AS
SELECT *
FROM read_parquet(
    '/data/gamma/airtel/database/adjustment/**/*.parquet',
    hive_partitioning := true
);

-- ── DATA CATALOG (partition inventory) ───────────────────────────────────────
CREATE OR REPLACE VIEW data_catalog AS
    SELECT 'voucher_cdr'  AS table_name, year, month, day, count(*) AS row_count
    FROM voucher_cdr  GROUP BY year, month, day
UNION ALL
    SELECT 'voucher_main' AS table_name, year, month, day, count(*) AS row_count
    FROM voucher_main GROUP BY year, month, day
UNION ALL
    SELECT 'voucher_other'AS table_name, year, month, day, count(*) AS row_count
    FROM voucher_other GROUP BY year, month, day
UNION ALL
    SELECT 'adjustment'   AS table_name, year, month, day, count(*) AS row_count
    FROM adjustment   GROUP BY year, month, day
ORDER BY table_name, year, month, day;

-- ── VERIFY ────────────────────────────────────────────────────────────────────
-- SELECT * FROM data_catalog;
-- SELECT EVENT_DATE, REMARK, RECHARGE_CODE, BALANCE_TYPE_1,
--        count(*) recharge_count, sum(RECHARGE_AMT) recharge_amount
-- FROM voucher_cdr
-- WHERE ENTRY_DATE = '2019-12-27'
-- GROUP BY EVENT_DATE, REMARK, RECHARGE_CODE, BALANCE_TYPE_1;
