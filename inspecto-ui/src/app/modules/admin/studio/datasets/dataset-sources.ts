/**
 * Mock-first sample sources for the Studio dataset editor's embedded Query Core. Offline, the panel needs
 * rows to infer columns and preview filters; these stand in until the dataset reads from the real Query/DuckDB
 * backend. One representative CDR-style table for the prototype (extend as Studio grows).
 */
export const SAMPLE_SOURCES: Record<string, Record<string, unknown>[]> = {
    cdr: [
        { id: 1001, msisdn: '8801700000001', cell_id: 'CELL-101', tariff: 'premium', duration_s: 320, event_time: '2026-06-24 09:00:00' },
        { id: 1002, msisdn: '8801700000002', cell_id: 'CELL-101', tariff: 'standard', duration_s: 45, event_time: '2026-06-24 09:01:30' },
        { id: 1003, msisdn: '8801700000003', cell_id: 'CELL-204', tariff: 'premium', duration_s: 512, event_time: '2026-06-24 09:03:11' },
        { id: 1004, msisdn: '8801700000004', cell_id: 'CELL-204', tariff: 'standard', duration_s: 12, event_time: '2026-06-24 09:05:42' },
        { id: 1005, msisdn: '8801700000005', cell_id: 'CELL-309', tariff: 'premium', duration_s: 287, event_time: '2026-06-24 09:08:09' },
    ],
};

export const SAMPLE_SOURCE_NAMES = Object.keys(SAMPLE_SOURCES);
