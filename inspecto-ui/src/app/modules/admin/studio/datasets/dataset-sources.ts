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
};

export const SAMPLE_SOURCE_NAMES = Object.keys(SAMPLE_SOURCES);
