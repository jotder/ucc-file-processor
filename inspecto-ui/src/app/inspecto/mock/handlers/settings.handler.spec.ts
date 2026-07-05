import { describe, expect, it } from 'vitest';
import { MockFlags } from '../mock-flags';
import { MockStore } from '../mock-store';
import { settingsHandler } from './settings.handler';

const flags = { mockStudio: true } as MockFlags;

function req(method: string, url: string, body: unknown = null) {
    return { method, url, body, params: {}, space: 's1' };
}

describe('settingsHandler', () => {
    it('GET /settings/geo returns the default (no tile server) before any save', () => {
        const res = settingsHandler(flags)(req('GET', '/settings/geo'), new MockStore());
        expect(res?.body).toEqual({ id: 'geo', tileServerUrl: null });
    });

    it('PUT round-trips the tile server URL and blanks fold to null', () => {
        const store = new MockStore();
        const handle = settingsHandler(flags);
        const url = 'https://tiles.example.com/{z}/{x}/{y}.png';
        handle(req('PUT', '/settings/geo', { tileServerUrl: url }), store);
        expect(handle(req('GET', '/settings/geo'), store)?.body).toEqual({ id: 'geo', tileServerUrl: url });
        handle(req('PUT', '/settings/geo', { tileServerUrl: '   ' }), store);
        expect(handle(req('GET', '/settings/geo'), store)?.body).toEqual({ id: 'geo', tileServerUrl: null });
    });

    it('stays out of the way when the studio mock is off', () => {
        const res = settingsHandler({ mockStudio: false } as MockFlags)(req('GET', '/settings/geo'), new MockStore());
        expect(res).toBeUndefined();
    });
});
