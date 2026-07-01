import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentDef, ComponentsService } from 'app/inspecto/api';
import { ComponentsDataProvider } from './components-data-provider';

function setup(defs: ComponentDef[]) {
    const list = vi.fn(() => of(defs));
    TestBed.configureTestingModule({
        providers: [ComponentsDataProvider, { provide: ComponentsService, useValue: { list } }],
    });
    return { provider: TestBed.inject(ComponentsDataProvider), list };
}

describe('ComponentsDataProvider', () => {
    it("lists a kind's existing components mapped onto the model Component shape", async () => {
        const { provider, list } = setup([
            { type: 'grammar', name: 'cdr_dsv', ref: 'grammar/cdr_dsv', content: { name: 'CDR DSV', parser_type: 'dsv' } },
        ]);
        const comps = await provider.list('grammar');
        expect(list).toHaveBeenCalledWith('grammar');
        expect(comps).toEqual([{ kind: 'grammar', id: 'cdr_dsv', name: 'CDR DSV', config: { name: 'CDR DSV', parser_type: 'dsv' } }]);
    });

    it('falls back to the id when content carries no name', async () => {
        const { provider } = setup([{ type: 'sink', name: 's1', ref: 'sink/s1', content: {} }]);
        const comps = await provider.list('sink');
        expect(comps[0].name).toBe('s1');
    });

    it('rejects columns/preview (no consumer in P2/P3) rather than fabricating output', async () => {
        const { provider } = setup([]);
        await expect(provider.columns({ kind: 'grammar', id: 'x' })).rejects.toThrow(/not implemented/);
        await expect(provider.preview({ kind: 'grammar', id: 'x' })).rejects.toThrow(/not implemented/);
    });
});
