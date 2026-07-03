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
    // ── Fraud-Management template (W5): scored usage events; risk_score > 0.8 drives the high-risk demo ──
    fraud_events: [
        { id: 3001, msisdn: '8801700000011', event_type: 'call', dest_country: 'LV', duration_s: 1810, risk_score: 0.94, event_time: '2026-06-26 02:11:00' },
        { id: 3002, msisdn: '8801700000011', event_type: 'call', dest_country: 'LV', duration_s: 1795, risk_score: 0.91, event_time: '2026-06-26 02:44:00' },
        { id: 3003, msisdn: '8801700000012', event_type: 'call', dest_country: 'GB', duration_s: 120, risk_score: 0.12, event_time: '2026-06-26 09:05:00' },
        { id: 3004, msisdn: '8801700000013', event_type: 'sms', dest_country: 'BD', duration_s: 0, risk_score: 0.05, event_time: '2026-06-26 09:30:00' },
        { id: 3005, msisdn: '8801700000014', event_type: 'call', dest_country: 'SL', duration_s: 2400, risk_score: 0.88, event_time: '2026-06-26 03:02:00' },
        { id: 3006, msisdn: '8801700000015', event_type: 'data', dest_country: 'BD', duration_s: 0, risk_score: 0.02, event_time: '2026-06-26 10:15:00' },
        { id: 3007, msisdn: '8801700000011', event_type: 'call', dest_country: 'LV', duration_s: 1750, risk_score: 0.96, event_time: '2026-06-26 03:17:00' },
        { id: 3008, msisdn: '8801700000016', event_type: 'call', dest_country: 'BD', duration_s: 300, risk_score: 0.2, event_time: '2026-06-26 11:40:00' },
    ],
    // ── Financial-Audit template (W5): GL postings vs. bank payments, keyed on id.
    //    Breaks: 4003 amount 1200.00 vs 1250.00 → value break · 4004 posted, never paid → missing_right
    //    4006 paid, never posted → missing_left · 4002 differs by 0.005 → clean under the $0.01 tolerance ──
    gl_entries: [
        { id: 4001, account: 'revenue', entry_type: 'credit', amount_usd: 5000.0, posted_by: 'sap_batch', posting_date: '2026-06-20 18:00:00' },
        { id: 4002, account: 'receivables', entry_type: 'debit', amount_usd: 320.5, posted_by: 'sap_batch', posting_date: '2026-06-21 18:00:00' },
        { id: 4003, account: 'revenue', entry_type: 'credit', amount_usd: 1200.0, posted_by: 'manual', posting_date: '2026-06-22 10:12:00' },
        { id: 4004, account: 'expenses', entry_type: 'debit', amount_usd: 87.25, posted_by: 'sap_batch', posting_date: '2026-06-23 18:00:00' },
        { id: 4005, account: 'revenue', entry_type: 'credit', amount_usd: 940.1, posted_by: 'sap_batch', posting_date: '2026-06-24 18:00:00' },
    ],
    payments: [
        { id: 4001, account: 'revenue', amount_usd: 5000.0, method: 'wire', value_date: '2026-06-20 18:30:00' },
        { id: 4002, account: 'receivables', amount_usd: 320.505, method: 'ach', value_date: '2026-06-21 19:00:00' },
        { id: 4003, account: 'revenue', amount_usd: 1250.0, method: 'wire', value_date: '2026-06-22 11:00:00' },
        { id: 4005, account: 'revenue', amount_usd: 940.1, method: 'card', value_date: '2026-06-24 18:20:00' },
        { id: 4006, account: 'suspense', amount_usd: 410.0, method: 'wire', value_date: '2026-06-25 09:00:00' },
    ],
    // ── Link-Analysis template (W5): an entity/link table pair (community 3 is the seeded ring candidate) ──
    entities: [
        { id: 'sub-01', label: 'Subscriber 8801700000011', entity_type: 'subscriber', risk_score: 0.9, community: 3 },
        { id: 'sub-02', label: 'Subscriber 8801700000014', entity_type: 'subscriber', risk_score: 0.85, community: 3 },
        { id: 'dev-01', label: 'IMEI 356938035643809', entity_type: 'device', risk_score: 0.7, community: 3 },
        { id: 'acc-01', label: 'Account A-1002', entity_type: 'account', risk_score: 0.2, community: 1 },
        { id: 'sub-03', label: 'Subscriber 8801700000012', entity_type: 'subscriber', risk_score: 0.1, community: 1 },
        { id: 'acc-02', label: 'Account A-1003', entity_type: 'account', risk_score: 0.6, community: 3 },
    ],
    links: [
        { id: 'l-01', source: 'sub-01', target: 'dev-01', link_type: 'shared_device', weight: 5, first_seen: '2026-06-10 08:00:00' },
        { id: 'l-02', source: 'sub-02', target: 'dev-01', link_type: 'shared_device', weight: 4, first_seen: '2026-06-12 21:00:00' },
        { id: 'l-03', source: 'sub-01', target: 'sub-02', link_type: 'calls', weight: 17, first_seen: '2026-06-01 12:00:00' },
        { id: 'l-04', source: 'sub-01', target: 'acc-02', link_type: 'payment', weight: 2, first_seen: '2026-06-15 16:00:00' },
        { id: 'l-05', source: 'sub-03', target: 'acc-01', link_type: 'payment', weight: 1, first_seen: '2026-06-18 10:00:00' },
    ],
};

export const SAMPLE_SOURCE_NAMES = Object.keys(SAMPLE_SOURCES);
