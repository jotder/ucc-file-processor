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
        // …a device hopping across it — with a two-hour dwell at 11:00–12:00 (stay-point demo)…
        add('Device IMEI-4411', 'device', 23.726, 90.365, 9);
        add('Device IMEI-4411', 'device', 23.754, 90.395, 10);
        add('Device IMEI-4411', 'device', 23.8103, 90.4125, 11);
        add('Device IMEI-4411', 'device', 23.8104, 90.4126, 12);
        add('Device IMEI-4411', 'device', 23.838, 90.455, 13);
        add('Device IMEI-4411', 'device', 23.866, 90.485, 14);
        // …a second device that MEETS IMEI-4411 during the dwell (co-location demo), then roams…
        add('Device IMEI-9902', 'device', 23.8104, 90.4124, 11); // same spot, same hour
        add('Device IMEI-9902', 'device', 23.8103, 90.4125, 12); // met again (repeated co-location)
        add('Device IMEI-9902', 'device', 22.3569, 91.7832, 16); // Chattogram
        add('Device IMEI-9902', 'device', 24.3745, 88.6042, 20); // Rajshahi
        add('Tower BAD-1', 'tower', null, 90.4, 8);
        add('Tower BAD-2', 'tower', 123.45, 90.4, 8);
        return rows;
    })(),

    // Geo Map Analysis routes example: origin→destination remittance legs between cities
    // (repeat legs fold into weighted routes; one leg has a broken destination → skipped).
    money_moves: (() => {
        const cities: Record<string, [number, number]> = {
            Dhaka: [23.8103, 90.4125], Chattogram: [22.3569, 91.7832], Sylhet: [24.8949, 91.8687],
            Dubai: [25.2048, 55.2708], Singapore: [1.3521, 103.8198], London: [51.5074, -0.1278],
        };
        const legs: [string, string, string, number][] = [
            ['Dhaka', 'Dubai', 'hundi', 9], ['Dhaka', 'Dubai', 'hundi', 11], ['Dhaka', 'Dubai', 'hundi', 14],
            ['Dubai', 'London', 'wire', 12], ['Dubai', 'London', 'wire', 16],
            ['Chattogram', 'Singapore', 'wire', 10], ['Singapore', 'London', 'wire', 15],
            ['Sylhet', 'Dhaka', 'cash', 8], ['Sylhet', 'Dhaka', 'cash', 9], ['Dhaka', 'Chattogram', 'cash', 10],
        ];
        const rows: Record<string, unknown>[] = legs.map(([from, to, channel, hour], i) => ({
            id: `m-${i + 1}`,
            from_city: from, from_lat: cities[from][0], from_lon: cities[from][1],
            to_city: to, to_lat: cities[to][0], to_lon: cities[to][1],
            channel, moved_at: `2026-06-02T${String(hour).padStart(2, '0')}:30:00Z`,
        }));
        rows.push({ id: 'm-bad', from_city: 'Dhaka', from_lat: 23.8103, from_lon: 90.4125, to_city: 'Nowhere', to_lat: null, to_lon: null, channel: 'wire', moved_at: '2026-06-02T20:30:00Z' });
        return rows;
    })(),

    // ═══ Geo Map Analysis case studies CS1–CS5 (docs/superpower/geo-map-case-studies.md) ═══
    // Deterministic generators (LCG-seeded — same data every build) sized to push a specific
    // boundary each: the point cap, impossible travel, weighted global corridors, dwell
    // detection over dense tracks, and cross-border co-location.

    // CS1 — SIM-box farms (STRESS): 3 static farms × 40 SIMs × 12 calls + 350 roaming
    // subscribers × 12 events = 5,640 valid rows (trips GEO_POINT_CAP=5000 → Truncated banner)
    // + 25 leading broken rows (→ Rows-skipped banner). Farms are dwell/co-location goldmines.
    simbox_sweep: (() => {
        const rand = lcg(11);
        const rows: Record<string, unknown>[] = [];
        const at = (min: number): string => new Date(Date.UTC(2026, 5, 3, 0, Math.round(min))).toISOString();
        for (let b = 0; b < 25; b++) {
            rows.push({ id: `sb-bad-${b}`, msisdn: `SIM-BAD-${b}`, role: 'simbox', lat: b % 2 ? null : 999, lon: 90.4, event_time: at(60) });
        }
        const farms: [number, number][] = [[23.7104, 90.4074], [23.7806, 90.2792], [23.8759, 90.3795]];
        farms.forEach((f, fi) => {
            for (let s = 0; s < 40; s++) {
                const lat = f[0] + (rand() - 0.5) * 0.0008;
                const lon = f[1] + (rand() - 0.5) * 0.0008;
                for (let e = 0; e < 12; e++) {
                    rows.push({
                        id: `sb-${rows.length}`, msisdn: `SIM-F${fi + 1}-${String(s + 1).padStart(2, '0')}`,
                        role: 'simbox', lat, lon, event_time: at(360 + e * 90 + rand() * 30),
                    });
                }
            }
        });
        for (let u = 0; u < 350; u++) {
            let lat = 23.6 + rand() * 0.35;
            let lon = 90.25 + rand() * 0.3;
            for (let e = 0; e < 12; e++) {
                lat += (rand() - 0.5) * 0.02;
                lon += (rand() - 0.5) * 0.02;
                rows.push({
                    id: `sb-${rows.length}`, msisdn: `MS-${String(u + 1).padStart(3, '0')}`,
                    role: 'subscriber', lat, lon, event_time: at(300 + e * 100 + rand() * 60),
                });
            }
        }
        return rows;
    })(),

    // CS2 — Impossible travel: 10 account entities logging in around home regions; ACC-007
    // jumps New York 13:00 → Singapore 14:05 (≈15,300 km in 65 min) — the flagged anomaly.
    impossible_travel: (() => {
        const rand = lcg(22);
        const rows: Record<string, unknown>[] = [];
        const homes: [string, number, number][] = [
            ['ACC-001', 51.5074, -0.1278], ['ACC-002', 40.7128, -74.006], ['ACC-003', 23.8103, 90.4125],
            ['ACC-004', 1.3521, 103.8198], ['ACC-005', 25.2048, 55.2708], ['ACC-006', 48.8566, 2.3522],
            ['ACC-008', 35.6762, 139.6503], ['ACC-009', -33.8688, 151.2093], ['ACC-010', 19.076, 72.8777],
        ];
        const at = (h: number, m: number): string => new Date(Date.UTC(2026, 5, 4, h, m)).toISOString();
        for (const [account, lat, lon] of homes) {
            for (let e = 0; e < 12; e++) {
                rows.push({
                    id: `it-${rows.length}`, account, channel: rand() > 0.4 ? 'mobile' : 'web',
                    lat: lat + (rand() - 0.5) * 0.15, lon: lon + (rand() - 0.5) * 0.15,
                    login_time: at(6 + Math.floor(e * 1.4), Math.floor(rand() * 59)),
                });
            }
        }
        // The anomaly: same account, two continents, 65 minutes apart.
        rows.push({ id: 'it-x1', account: 'ACC-007', channel: 'web', lat: 40.7128, lon: -74.006, login_time: at(13, 0) });
        rows.push({ id: 'it-x2', account: 'ACC-007', channel: 'web', lat: 1.3521, lon: 103.8198, login_time: at(14, 5) });
        rows.push({ id: 'it-x3', account: 'ACC-007', channel: 'mobile', lat: 1.3548, lon: 103.822, login_time: at(15, 30) });
        return rows;
    })(),

    // CS3 — Mule corridors: ~900 O/D legs over 18 world cities folding into 24 weighted
    // corridors across 4 channel kinds (weight range 3 → 150) + 5 broken legs.
    mule_corridors: (() => {
        const rand = lcg(33);
        const c: Record<string, [number, number]> = {
            Dhaka: [23.8103, 90.4125], Chattogram: [22.3569, 91.7832], Dubai: [25.2048, 55.2708],
            Singapore: [1.3521, 103.8198], London: [51.5074, -0.1278], 'New York': [40.7128, -74.006],
            Mumbai: [19.076, 72.8777], Karachi: [24.8607, 67.0011], Riyadh: [24.7136, 46.6753],
            'Kuala Lumpur': [3.139, 101.6869], 'Hong Kong': [22.3193, 114.1694], Lagos: [6.5244, 3.3792],
            Nairobi: [-1.2921, 36.8219], Toronto: [43.6532, -79.3832], Sydney: [-33.8688, 151.2093],
            Frankfurt: [50.1109, 8.6821], Istanbul: [41.0082, 28.9784], Doha: [25.2854, 51.531],
        };
        const corridors: [string, string, string, number][] = [
            ['Dhaka', 'Dubai', 'hundi', 150], ['Dhaka', 'Riyadh', 'hundi', 110], ['Dhaka', 'Kuala Lumpur', 'hundi', 60],
            ['Chattogram', 'Singapore', 'wire', 45], ['Dubai', 'London', 'wire', 95], ['Dubai', 'Istanbul', 'crypto', 38],
            ['Mumbai', 'Dubai', 'hundi', 70], ['Karachi', 'Dubai', 'hundi', 48], ['Singapore', 'Hong Kong', 'wire', 42],
            ['Hong Kong', 'London', 'wire', 30], ['London', 'New York', 'wire', 33], ['New York', 'Toronto', 'wire', 25],
            ['Lagos', 'London', 'crypto', 28], ['Nairobi', 'Dubai', 'crypto', 22], ['Doha', 'Frankfurt', 'wire', 18],
            ['Istanbul', 'Frankfurt', 'cash', 15], ['Kuala Lumpur', 'Sydney', 'wire', 12], ['Dhaka', 'Singapore', 'crypto', 20],
            ['Frankfurt', 'London', 'wire', 10], ['Dubai', 'Doha', 'cash', 8], ['Mumbai', 'Singapore', 'wire', 9],
            ['Riyadh', 'Istanbul', 'cash', 6], ['Toronto', 'Sydney', 'crypto', 4], ['Nairobi', 'Lagos', 'cash', 3],
        ];
        const rows: Record<string, unknown>[] = [];
        for (const [from, to, channel, legs] of corridors) {
            for (let l = 0; l < legs; l++) {
                rows.push({
                    id: `mc-${rows.length}`, from_city: from, from_lat: c[from][0], from_lon: c[from][1],
                    to_city: to, to_lat: c[to][0], to_lon: c[to][1], channel,
                    moved_at: new Date(Date.UTC(2026, 5, 1 + Math.floor(rand() * 7), Math.floor(rand() * 24), Math.floor(rand() * 59))).toISOString(),
                });
            }
        }
        for (let b = 0; b < 5; b++) {
            rows.push({ id: `mc-bad-${b}`, from_city: 'Dhaka', from_lat: 23.8103, from_lon: 90.4125, to_city: 'Unknown', to_lat: '', to_lon: '', channel: 'wire', moved_at: '2026-06-05T12:00:00Z' });
        }
        return rows;
    })(),

    // CS4 — Fleet breadcrumbs: 6 trucks × 24 h of 15-minute GPS pings between Bangladeshi
    // depots (≈580 rows). Trucks 2 and 5 take unscheduled ~1 h roadside stops (dwells);
    // every truck returns to its home depot (frequent locations).
    fleet_breadcrumbs: (() => {
        const rand = lcg(44);
        const depots: Record<string, [number, number]> = {
            'Depot Dhaka': [23.8103, 90.4125], 'Depot Comilla': [23.4607, 91.1809],
            'Depot Jessore': [23.1667, 89.2089], 'Depot Bogra': [24.8465, 89.3773],
        };
        const names = Object.keys(depots);
        const rows: Record<string, unknown>[] = [];
        for (let t = 0; t < 6; t++) {
            const truck = `TRK-${String(t + 1).padStart(2, '0')}`;
            const home = depots[names[t % names.length]];
            const away = depots[names[(t + 1) % names.length]];
            // schedule (minutes): home 0-120 · drive 120-360 · away 360-600 · [stop 600-660 for
            // trucks 2/5] · drive back ...-900 · home 900-1440
            const stop: [number, number] = [home[0] * 0.45 + away[0] * 0.55, home[1] * 0.45 + away[1] * 0.55];
            const hasStop = t === 1 || t === 4;
            for (let min = 0; min < 1440; min += 15) {
                let lat: number, lon: number, status = 'moving';
                const lerp = (a: [number, number], b: [number, number], f: number): [number, number] =>
                    [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f];
                if (min < 120) { [lat, lon] = home; status = 'idle'; }
                else if (min < 360) { [lat, lon] = lerp(home, away, (min - 120) / 240); }
                else if (min < 600) { [lat, lon] = away; status = 'idle'; }
                else if (hasStop && min < 660) { [lat, lon] = stop; status = 'idle'; }
                else if (min < 900) { [lat, lon] = lerp(away, home, (min - (hasStop ? 660 : 600)) / (hasStop ? 240 : 300)); }
                else { [lat, lon] = home; status = 'idle'; }
                rows.push({
                    id: `fb-${rows.length}`, truck, status,
                    lat: lat + (rand() - 0.5) * 0.0006, lon: lon + (rand() - 0.5) * 0.0006,
                    ping_time: new Date(Date.UTC(2026, 5, 5, 0, min)).toISOString(),
                });
            }
        }
        return rows;
    })(),

    // CS5 — Border roamers: 12 devices oscillating across the Benapole–Petrapole border strip
    // over 3 days (~720 rows); staged same-time same-spot meetings at the crossing points
    // (co-location) and dense revisits (heatmap hotspots).
    border_roamers: (() => {
        const rand = lcg(55);
        const crossings: [number, number][] = [[23.0435, 88.8940], [23.0821, 88.9152], [23.0119, 88.8731]];
        const rows: Record<string, unknown>[] = [];
        for (let d = 0; d < 12; d++) {
            const imei = `IMEI-B${String(d + 1).padStart(2, '0')}`;
            for (let e = 0; e < 58; e++) {
                const cross = crossings[Math.floor(rand() * crossings.length)];
                const west = rand() > 0.5; // which side of the line this sighting falls on
                const lat = cross[0] + (rand() - 0.5) * 0.02;
                const lon = cross[1] + (west ? -1 : 1) * (0.002 + rand() * 0.03);
                rows.push({
                    id: `br-${rows.length}`, imei, side: lon < cross[1] ? 'india' : 'bangladesh', lat, lon,
                    seen_at: new Date(Date.UTC(2026, 5, 6 + Math.floor(e / 20), Math.floor((e % 20) * 1.2), Math.floor(rand() * 59))).toISOString(),
                });
            }
        }
        // Staged meetings: pairs at the same crossing within minutes (found by co-location).
        const meet = (a: string, b: string, ci: number, day: number, h: number): void => {
            const [lat, lon] = crossings[ci];
            rows.push({ id: `br-${rows.length}`, imei: a, side: 'bangladesh', lat: lat + 0.0002, lon: lon + 0.001, seen_at: new Date(Date.UTC(2026, 5, day, h, 10)).toISOString() });
            rows.push({ id: `br-${rows.length}`, imei: b, side: 'bangladesh', lat: lat + 0.0003, lon: lon + 0.0012, seen_at: new Date(Date.UTC(2026, 5, day, h, 25)).toISOString() });
        };
        meet('IMEI-B01', 'IMEI-B07', 0, 6, 9);
        meet('IMEI-B01', 'IMEI-B07', 0, 7, 9);
        meet('IMEI-B01', 'IMEI-B07', 0, 8, 9);
        meet('IMEI-B03', 'IMEI-B11', 1, 7, 15);
        meet('IMEI-B03', 'IMEI-B11', 1, 8, 15);
        return rows;
    })(),
};

/** Deterministic LCG so the case-study data is identical on every build (no Math.random). */
function lcg(seed: number): () => number {
    let s = seed >>> 0;
    return () => {
        s = (Math.imul(s, 1664525) + 1013904223) >>> 0;
        return s / 4294967296;
    };
}

export const SAMPLE_SOURCE_NAMES = Object.keys(SAMPLE_SOURCES);
