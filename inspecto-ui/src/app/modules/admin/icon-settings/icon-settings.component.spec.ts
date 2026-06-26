import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { FlowsService, IconMapService } from 'app/inspecto/api';
import { NODE_KIND_COLORS } from 'app/inspecto/theme/chart-tokens';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { IconSettingsComponent } from './icon-settings.component';

const TYPES = [
    { type: 'parser.dsv', category: 'PARSE', label: 'DSV', description: '', accepts: [], emits: [], emitsNamedRoutes: false },
];

function make() {
    TestBed.configureTestingModule({
        imports: [IconSettingsComponent],
        providers: [
            provideNoopAnimations(),
            { provide: FlowsService, useValue: { nodeTypes: () => of(TYPES) } },
            { provide: IconMapService, useValue: { get: () => of({ PARSE: { glyph: 'lines', color: NODE_KIND_COLORS.SCHEMA } }), save: (m: unknown) => of(m) } },
            { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn() } },
        ],
    });
    const fixture = TestBed.createComponent(IconSettingsComponent);
    fixture.detectChanges();
    return fixture;
}

describe('IconSettingsComponent', () => {
    it('loads the saved map into the editable draft', () => {
        const c = make().componentInstance;
        expect(c.selectedGlyph('PARSE')).toBe('lines');
        expect(c.hasRule('parser.dsv')).toBe(false); // sub-type inherits until given its own rule
    });

    it('materialises a rule on glyph choice (seeding the inherited colour) and clears on inherit', () => {
        const c = make().componentInstance;
        c.setGlyph('parser.dsv', 'parser.dsv', 'PARSE', 'filter');
        expect(c.hasRule('parser.dsv')).toBe(true);
        expect(c.selectedColor('parser.dsv')).toBe(NODE_KIND_COLORS.SCHEMA); // inherited from PARSE
        c.setColor('parser.dsv', NODE_KIND_COLORS.ENRICHMENT);
        expect(c.selectedColor('parser.dsv')).toBe(NODE_KIND_COLORS.ENRICHMENT);
        c.setGlyph('parser.dsv', 'parser.dsv', 'PARSE', '');
        expect(c.hasRule('parser.dsv')).toBe(false);
    });

    it('ignores recolouring a row that has no glyph rule', () => {
        const c = make().componentInstance;
        c.setColor('sink.file', NODE_KIND_COLORS.ENRICHMENT);
        expect(c.hasRule('sink.file')).toBe(false);
    });

    it('has no a11y violations', async () => {
        const fixture = make();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
