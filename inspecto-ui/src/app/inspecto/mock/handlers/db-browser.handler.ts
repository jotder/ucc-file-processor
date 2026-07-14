import { MockFlags } from '../mock-flags';
import { json, MockHandler } from '../mock-http';

/**
 * Offline mock for the raw table browser ({@code /db/catalog}, {@code /db/table}, {@code /db/query}).
 * Serves one business-data store ("orders") plus one live operational group ("ops:objects") so the
 * Data Browser pane — including the operational section — is explorable with no backend. Gated on
 * {@code mockDb}. Regexes are tail-anchored so they match through the {@code /api/v1} prefix (mocks run
 * before the space rewrite).
 */
const CATALOG = /\/db\/catalog$/;
const TABLE = /\/db\/table$/;
const QUERY = /\/db\/query$/;

const STORE_COLUMNS = [
    { name: 'id', type: 'INTEGER', role: 'dimension' },
    { name: 'name', type: 'VARCHAR', role: 'dimension' },
    { name: 'amount', type: 'DOUBLE', role: 'measure' },
];
const STORE_ROWS = [
    { id: 1, name: 'alice', amount: 10.0 },
    { id: 2, name: 'bob', amount: 30.0 },
    { id: 3, name: 'carol', amount: 5.0 },
];

const OPS_COLUMNS = [
    { name: 'id', type: 'VARCHAR', role: 'dimension' },
    { name: 'object_type', type: 'VARCHAR', role: 'dimension' },
    { name: 'title', type: 'VARCHAR', role: 'dimension' },
    { name: 'status', type: 'VARCHAR', role: 'dimension' },
    { name: 'severity', type: 'VARCHAR', role: 'dimension' },
];
const OPS_ROWS = [
    { id: 'inc-1001', object_type: 'INCIDENT', title: 'Late nightly batch', status: 'IDENTIFIED', severity: 'HIGH' },
    { id: 'alt-2002', object_type: 'ALERT', title: 'Schema drift on orders', status: 'OPEN', severity: 'MEDIUM' },
];

function result(columns: unknown[], rows: Record<string, unknown>[]) {
    return { columns, rows, statistics: { rowCount: rows.length, elapsedMs: 1, truncated: false } };
}

function isOps(group: unknown): boolean {
    return typeof group === 'string' && group.startsWith('ops:');
}

export function dbBrowserHandler(flags: MockFlags): MockHandler {
    return (req) => {
        if (!flags.mockDb) return undefined;
        const { method, url, params, body } = req;
        if (method === 'GET' && CATALOG.test(url)) {
            return json({
                groups: [
                    {
                        id: 'stores', label: 'Data Stores', kind: 'parquet',
                        tables: [{ name: 'orders', format: 'PARQUET', dataset: 'orders_ds' }],
                    },
                    {
                        id: 'ops:objects', label: 'Operational · Objects', kind: 'operational',
                        engine: 'duckdb', live: true,
                        tables: [{ name: 'inspecto_ops_objects' }],
                    },
                ],
            });
        }
        if (method === 'GET' && TABLE.test(url)) {
            return isOps(params['group'])
                ? json(result(OPS_COLUMNS, OPS_ROWS))
                : json(result(STORE_COLUMNS, STORE_ROWS));
        }
        if (method === 'POST' && QUERY.test(url)) {
            return isOps((body as { group?: unknown } | null)?.group)
                ? json(result(OPS_COLUMNS, OPS_ROWS))
                : json(result(STORE_COLUMNS, STORE_ROWS));
        }
        return undefined;
    };
}
