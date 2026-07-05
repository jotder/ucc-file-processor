import { MockFlags } from '../mock-flags';
import { json, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';

/**
 * The UI-settings mock domain — small per-space preference documents the backend will later serve
 * for real (the "APIs to fetch and save UI preferences" contract). First document: `geo`
 * (`GET|PUT /settings/geo` → `{tileServerUrl}`), the Phase 4 tile-server config seam.
 */

export const SETTINGS_COLL = 'settings';

const GEO = /\/settings\/geo$/;

export interface GeoSettingsDoc {
    id: string;
    tileServerUrl: string | null;
}

export function settingsHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockStudio) return undefined;
        const { method, url, space } = req;

        if (method === 'GET' && GEO.test(url)) {
            return json(store.get<GeoSettingsDoc>(space, SETTINGS_COLL, 'geo') ?? { id: 'geo', tileServerUrl: null });
        }
        if (method === 'PUT' && GEO.test(url)) {
            const b = (req.body ?? {}) as Partial<GeoSettingsDoc>;
            const doc: GeoSettingsDoc = { id: 'geo', tileServerUrl: b.tileServerUrl?.trim() || null };
            return json(store.put(space, SETTINGS_COLL, doc.id, doc));
        }

        return undefined;
    };
}
