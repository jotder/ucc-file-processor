import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { BAR_PLUGIN } from 'app/inspecto/viz/plugins';
import { ControlValues, VizField } from 'app/inspecto/viz';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ExploreControlsComponent } from './explore-controls.component';

const FIELDS: VizField[] = [
    { name: 'tariff', type: 'string', role: 'dimension' },
    { name: 'duration_s', type: 'number', role: 'measure' },
];

function create(values: ControlValues = {}) {
    TestBed.configureTestingModule({ imports: [ExploreControlsComponent], providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(ExploreControlsComponent);
    fixture.componentRef.setInput('plugin', BAR_PLUGIN);
    fixture.componentRef.setInput('fields', FIELDS);
    fixture.componentRef.setInput('values', values);
    fixture.detectChanges();
    return fixture;
}

describe('ExploreControlsComponent', () => {
    it('only offers fields whose role the channel accepts', () => {
        const c = create().componentInstance;
        // x accepts temporal|dimension → tariff only; y accepts measure → duration_s only
        expect(c.fieldsFor(BAR_PLUGIN.controls[0]).map((f) => f.name)).toEqual(['tariff']);
        expect(c.fieldsFor(BAR_PLUGIN.controls[1]).map((f) => f.name)).toEqual(['duration_s']);
    });

    it('emits an updated mapping when a field is chosen', () => {
        const fixture = create();
        const c = fixture.componentInstance;
        let emitted: ControlValues | undefined;
        c.valuesChange.subscribe((v) => (emitted = v));
        c.onField(BAR_PLUGIN.controls[0], 'tariff');
        expect(emitted?.x).toEqual([{ field: 'tariff', agg: undefined }]);
    });

    it('emits an aggregation change on a measure channel', () => {
        const fixture = create({ y: [{ field: 'duration_s', agg: 'sum' }] });
        const c = fixture.componentInstance;
        let emitted: ControlValues | undefined;
        c.valuesChange.subscribe((v) => (emitted = v));
        c.onAgg('y', 'avg');
        expect(emitted?.y).toEqual([{ field: 'duration_s', agg: 'avg' }]);
    });

    it('renders with no a11y violations', async () => {
        await expectNoA11yViolations(create().nativeElement);
    });
});
