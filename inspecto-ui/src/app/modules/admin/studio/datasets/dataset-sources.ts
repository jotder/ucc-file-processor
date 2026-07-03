/**
 * Mock-first sample sources for the Studio dataset editor's embedded Query Core. Offline, the panel needs
 * rows to infer columns and preview filters; these stand in until the dataset reads from the real Query/DuckDB
 * backend. One representative CDR-style table for the prototype (extend as Studio grows).
 */
export const SAMPLE_SOURCES: Record<string, Record<string, unknown>[]> = {
    cdr: [
        { id: 1001, msisdn: '8801700000001', cell_id: 'CELL-101', tariff: 'premium', duration_s: 320, bytes_used: 5_200_000, cost_usd: 1.8, event_time: '2026-06-24 09:00:00' },
        { id: 1002, msisdn: '8801700000002', cell_id: 'CELL-101', tariff: 'standard', duration_s: 45, bytes_used: 800_000, cost_usd: 0.3, event_time: '2026-06-24 09:01:30' },
        { id: 1003, msisdn: '8801700000003', cell_id: 'CELL-204', tariff: 'premium', duration_s: 512, bytes_used: 9_100_000, cost_usd: 2.6, event_time: '2026-06-24 09:03:11' },
        { id: 1004, msisdn: '8801700000004', cell_id: 'CELL-204', tariff: 'standard', duration_s: 12, bytes_used: 150_000, cost_usd: 0.1, event_time: '2026-06-24 09:05:42' },
        { id: 1005, msisdn: '8801700000005', cell_id: 'CELL-309', tariff: 'premium', duration_s: 287, bytes_used: 6_700_000, cost_usd: 2.1, event_time: '2026-06-24 09:08:09' },
    ],
    // Two sides of a Revenue-Assurance reconciliation — the network/switch record of truth vs. what
    // billing actually rated. Keyed on `id`; the deliberate discrepancies drive the C9 demo:
    //   2003 cost 2.60 vs 2.10 → value break (under-billed) · 2002 0.30 vs 0.31 → within $0.02 tol (clean)
    //   2004 on switch, absent in billing → missing_right (unbilled) · 2006 in billing, absent on switch → missing_left
    switch_cdr: [
        { id: 2001, msisdn: '8801700000001', duration_s: 320, cost_usd: 1.8, event_time: '2026-06-25 09:00:00' },
        { id: 2002, msisdn: '8801700000002', duration_s: 45, cost_usd: 0.3, event_time: '2026-06-25 09:01:30' },
        { id: 2003, msisdn: '8801700000003', duration_s: 512, cost_usd: 2.6, event_time: '2026-06-25 09:03:11' },
        { id: 2004, msisdn: '8801700000004', duration_s: 12, cost_usd: 0.1, event_time: '2026-06-25 09:05:42' },
        { id: 2005, msisdn: '8801700000005', duration_s: 287, cost_usd: 2.1, event_time: '2026-06-25 09:08:09' },
    ],
    billing_cdr: [
        { id: 2001, msisdn: '8801700000001', duration_s: 320, cost_usd: 1.8, event_time: '2026-06-25 09:00:00' },
        { id: 2002, msisdn: '8801700000002', duration_s: 45, cost_usd: 0.31, event_time: '2026-06-25 09:01:30' },
        { id: 2003, msisdn: '8801700000003', duration_s: 512, cost_usd: 2.1, event_time: '2026-06-25 09:03:11' },
        { id: 2005, msisdn: '8801700000005', duration_s: 287, cost_usd: 2.1, event_time: '2026-06-25 09:08:09' },
        { id: 2006, msisdn: '8801700000006', duration_s: 60, cost_usd: 0.5, event_time: '2026-06-25 09:11:00' },
    ],
};

export const SAMPLE_SOURCE_NAMES = Object.keys(SAMPLE_SOURCES);
