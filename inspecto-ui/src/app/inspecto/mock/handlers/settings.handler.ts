import { MockFlags } from '../mock-flags';
import { json, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';

/**
 * The UI-settings mock domain — small per-space preference documents the backend will later serve
 * for real (the "APIs to fetch and save UI preferences" contract). Documents: `geo`
 * (`GET|PUT /settings/geo` → `{tileServerUrl}`), the Phase 4 tile-server config seam; and `branding`
 * (`GET|PUT /settings/branding` → `{logoDataUrl, caption, footerText}`), per-space product branding.
 */

export const SETTINGS_COLL = 'settings';

const GEO = /\/settings\/geo$/;
const BRANDING = /\/settings\/branding$/;

export interface GeoSettingsDoc {
    id: string;
    tileServerUrl: string | null;
}

/** Per-space branding overrides; each `null` field means "use the shipped default". */
export interface BrandingDoc {
    id: string;
    logoDataUrl: string | null;
    caption: string | null;
    footerText: string | null;
}

const clean = (v: unknown): string | null => (typeof v === 'string' && v.trim() ? v.trim() : null);

/** An explicit `/spaces/<id>/settings/…` call targets that space; otherwise the active space applies. */
const spaceOf = (url: string, active: string): string => url.match(/\/spaces\/([^/]+)\/settings\//)?.[1] ?? active;

export function settingsHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockStudio) return undefined;
        const { method, url } = req;
        const space = spaceOf(url, req.space);

        if (method === 'GET' && GEO.test(url)) {
            return json(store.get<GeoSettingsDoc>(space, SETTINGS_COLL, 'geo') ?? { id: 'geo', tileServerUrl: null });
        }
        if (method === 'PUT' && GEO.test(url)) {
            const b = (req.body ?? {}) as Partial<GeoSettingsDoc>;
            const doc: GeoSettingsDoc = { id: 'geo', tileServerUrl: b.tileServerUrl?.trim() || null };
            return json(store.put(space, SETTINGS_COLL, doc.id, doc));
        }

        if (method === 'GET' && BRANDING.test(url)) {
            return json(
                store.get<BrandingDoc>(space, SETTINGS_COLL, 'branding') ??
                    { id: 'branding', logoDataUrl: null, caption: null, footerText: null },
            );
        }
        if (method === 'PUT' && BRANDING.test(url)) {
            const b = (req.body ?? {}) as Partial<BrandingDoc>;
            const doc: BrandingDoc = {
                id: 'branding',
                logoDataUrl: clean(b.logoDataUrl),
                caption: clean(b.caption),
                footerText: clean(b.footerText),
            };
            return json(store.put(space, SETTINGS_COLL, doc.id, doc));
        }

        return undefined;
    };
}
