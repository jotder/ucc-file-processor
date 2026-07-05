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
    // ── Link Analysis example graphs (C5 user testing): four link tables at rising complexity.
    //    Each projects source → target (+ link_type); the default space seeds a dataset + a saved
    //    Link-Analysis view per table so testers one-click load them (default-space.seed.ts). ──
    // 1. Simple — a 6-node star, one link type: the first-contact demo.
    graph_simple: [
        { id: 's-1', source: 'Hub subscriber', target: 'Alice', link_type: 'calls' },
        { id: 's-2', source: 'Hub subscriber', target: 'Bikash', link_type: 'calls' },
        { id: 's-3', source: 'Hub subscriber', target: 'Chen', link_type: 'calls' },
        { id: 's-4', source: 'Hub subscriber', target: 'Divya', link_type: 'calls' },
        { id: 's-5', source: 'Hub subscriber', target: 'Emeka', link_type: 'calls' },
    ],
    // 2. Moderate — a call ring and a call chain joined by ONE bridge, three link types
    //    (11 nodes / 13 links): exercises shortest path, the type filter and two communities.
    graph_moderate: [
        // cluster A: a 4-subscriber call ring sharing one device, paying one account
        { id: 'm-1', source: 'Sub A1', target: 'Sub A2', link_type: 'calls' },
        { id: 'm-2', source: 'Sub A2', target: 'Sub A3', link_type: 'calls' },
        { id: 'm-3', source: 'Sub A3', target: 'Sub A4', link_type: 'calls' },
        { id: 'm-4', source: 'Sub A4', target: 'Sub A1', link_type: 'calls' },
        { id: 'm-5', source: 'Sub A1', target: 'Device IMEI-A', link_type: 'shared_device' },
        { id: 'm-6', source: 'Sub A2', target: 'Device IMEI-A', link_type: 'shared_device' },
        { id: 'm-7', source: 'Sub A3', target: 'Device IMEI-A', link_type: 'shared_device' },
        { id: 'm-8', source: 'Sub A1', target: 'Account ACC-A', link_type: 'payment' },
        // cluster B: a 4-subscriber call chain
        { id: 'm-9', source: 'Sub B1', target: 'Sub B2', link_type: 'calls' },
        { id: 'm-10', source: 'Sub B2', target: 'Sub B3', link_type: 'calls' },
        { id: 'm-11', source: 'Sub B3', target: 'Sub B4', link_type: 'calls' },
        { id: 'm-12', source: 'Sub B4', target: 'Account ACC-B', link_type: 'payment' },
        // the single bridge between the clusters (the shortest-path demo crosses it)
        { id: 'm-13', source: 'Sub A3', target: 'Sub B2', link_type: 'calls' },
    ],
    // 3. Mind map — a "Data Quality" topic tree: 1 root → 5 branches → 14 leaves
    //    (20 nodes / 19 links): exercises the hierarchy layout and Explain node.
    graph_mindmap: [
        { id: 't-1', source: 'Data Quality', target: 'Accuracy', link_type: 'topic' },
        { id: 't-2', source: 'Data Quality', target: 'Completeness', link_type: 'topic' },
        { id: 't-3', source: 'Data Quality', target: 'Timeliness', link_type: 'topic' },
        { id: 't-4', source: 'Data Quality', target: 'Consistency', link_type: 'topic' },
        { id: 't-5', source: 'Data Quality', target: 'Validity', link_type: 'topic' },
        { id: 't-6', source: 'Accuracy', target: 'Golden records', link_type: 'subtopic' },
        { id: 't-7', source: 'Accuracy', target: 'Source-of-truth checks', link_type: 'subtopic' },
        { id: 't-8', source: 'Accuracy', target: 'Sampling audits', link_type: 'subtopic' },
        { id: 't-9', source: 'Completeness', target: 'Mandatory fields', link_type: 'subtopic' },
        { id: 't-10', source: 'Completeness', target: 'Gap detection', link_type: 'subtopic' },
        { id: 't-11', source: 'Completeness', target: 'Late data', link_type: 'subtopic' },
        { id: 't-12', source: 'Timeliness', target: 'SLA windows', link_type: 'subtopic' },
        { id: 't-13', source: 'Timeliness', target: 'Freshness alerts', link_type: 'subtopic' },
        { id: 't-14', source: 'Timeliness', target: 'Backfill policy', link_type: 'subtopic' },
        { id: 't-15', source: 'Consistency', target: 'Cross-system recon', link_type: 'subtopic' },
        { id: 't-16', source: 'Consistency', target: 'Referential checks', link_type: 'subtopic' },
        { id: 't-17', source: 'Validity', target: 'Schema checks', link_type: 'subtopic' },
        { id: 't-18', source: 'Validity', target: 'Range rules', link_type: 'subtopic' },
        { id: 't-19', source: 'Validity', target: 'Pattern rules', link_type: 'subtopic' },
    ],
    // 4. Complex — three fraud rings (ring calls + a shared device + mule-account payments each),
    //    the mules cashing out through one hub, two inter-ring bridges, plus a background chatter
    //    ring (41 nodes / 57 links): exercises centrality and community detection at scale.
    graph_complex: (() => {
        const rows: Record<string, unknown>[] = [];
        const add = (source: string, target: string, link_type: string): void => {
            rows.push({ id: `x-${rows.length + 1}`, source, target, link_type });
        };
        for (const ring of ['R1', 'R2', 'R3']) {
            const subs = Array.from({ length: 8 }, (_, i) => `Sub ${ring}-${i + 1}`);
            subs.forEach((s, i) => add(s, subs[(i + 1) % subs.length], 'calls')); // the call ring
            subs.filter((_, i) => i % 2 === 0).forEach((s) => add(s, `Device IMEI-${ring}`, 'shared_device'));
            add(subs[0], `Account MULE-${ring}`, 'payment');
            add(subs[4], `Account MULE-${ring}`, 'payment');
            add(`Account MULE-${ring}`, 'Account CASHOUT-HUB', 'payment');
        }
        add('Sub R1-3', 'Sub R2-6', 'calls'); // inter-ring bridges
        add('Sub R2-2', 'Sub R3-7', 'calls');
        for (let i = 1; i <= 10; i++) add(`Sub N-${i}`, `Sub N-${(i % 10) + 1}`, 'calls'); // background chatter
        return rows;
    })(),

    // Geo Map Analysis example: cell sites + device sightings with WGS84 coordinates (a Dhaka
    // tower grid, a hopping device, a roaming trail to other cities, and two broken rows the
    // projection must skip — exercising the invalid-coordinate banner).
    cell_sites: (() => {
        const rows: Record<string, unknown>[] = [];
        const add = (site: string, site_type: string, lat: number | null, lon: number | null, hour: number): void => {
            rows.push({
                id: `g-${rows.length + 1}`, site, site_type, lat, lon,
                seen_time: `2026-06-01T${String(hour).padStart(2, '0')}:00:00Z`,
            });
        };
        for (let i = 0; i < 12; i++) {
            add(`Tower DHK-${i + 1}`, 'tower', 23.72 + (i % 4) * 0.045, 90.36 + Math.floor(i / 4) * 0.05, 8);
        }
        for (let i = 0; i < 6; i++) {
            add('Device IMEI-4411', 'device', 23.726 + i * 0.028, 90.365 + i * 0.03, 9 + i);
        }
        add('Device IMEI-9902', 'device', 23.8103, 90.4125, 9); // Dhaka
        add('Device IMEI-9902', 'device', 22.3569, 91.7832, 13); // Chattogram
        add('Device IMEI-9902', 'device', 24.3745, 88.6042, 18); // Rajshahi
        add('Tower BAD-1', 'tower', null, 90.4, 8);
        add('Tower BAD-2', 'tower', 123.45, 90.4, 8);
        return rows;
    })(),
};

export const SAMPLE_SOURCE_NAMES = Object.keys(SAMPLE_SOURCES);
