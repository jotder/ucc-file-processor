import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { AttributeSpec } from 'app/inspecto/component-model';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { InspectoSchemaFormComponent } from './schema-form.component';

const SPECS: AttributeSpec[] = [
    { key: 'name', label: 'Name', type: 'identifier', tier: 'required' },
    {
        key: 'type', label: 'Type', type: 'select', tier: 'required',
        options: [{ value: 'enrich', label: 'Enrich' }, { value: 'report', label: 'Report' }],
        default: 'enrich',
    },
    { key: 'cron', label: 'Cron', type: 'string', tier: 'optional', dependsOn: { key: 'type', equals: 'report' } },
    { key: 'enabled', label: 'Enabled', type: 'boolean', tier: 'optional', default: true },
    { key: 'threads', label: 'Threads', type: 'number', tier: 'advanced', default: 4, min: 1, max: 64 },
];

describe('InspectoSchemaFormComponent', () => {
    function create(specs: AttributeSpec[] = SPECS, initial?: Record<string, unknown>) {
        TestBed.configureTestingModule({
            imports: [InspectoSchemaFormComponent],
            providers: [provideNoopAnimations()],
        });
        const fixture = TestBed.createComponent(InspectoSchemaFormComponent);
        fixture.componentInstance.specs = specs;
        if (initial) fixture.componentInstance.initial = initial;
        fixture.detectChanges();
        return fixture;
    }

    it('shows required tier; collapses optional; hides advanced behind the gear', () => {
        const fixture = create();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Name');
        expect(text).toContain('Optional settings (2)');
        expect(text).not.toContain('Enabled'); // optional collapsed
        expect(text).not.toContain('Threads'); // advanced hidden

        fixture.componentInstance.showOptional.set(true);
        fixture.componentInstance.showAdvanced.set(true);
        fixture.detectChanges();
        const expanded = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(expanded).toContain('Enabled');
        expect(expanded).toContain('Threads');
    });

    it('shows a dependsOn attribute only when its controller matches, and excludes it from value()', () => {
        const fixture = create();
        fixture.componentInstance.showOptional.set(true);
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Cron');
        expect(fixture.componentInstance.form.get('cron')?.disabled).toBe(true);
        expect(Object.keys(fixture.componentInstance.value())).not.toContain('cron');

        fixture.componentInstance.form.get('type')?.setValue('report');
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Cron');
        expect(fixture.componentInstance.form.get('cron')?.enabled).toBe(true);
        expect(Object.keys(fixture.componentInstance.value())).toContain('cron');
    });

    it('validate() fails on a missing required value and marks controls touched', () => {
        const fixture = create();
        expect(fixture.componentInstance.validate()).toBe(false);
        expect(fixture.componentInstance.form.get('name')?.touched).toBe(true);

        fixture.componentInstance.form.get('name')?.setValue('daily_kpi');
        expect(fixture.componentInstance.validate()).toBe(true);
    });

    it('applies declared defaults and patches initial values over them', () => {
        const fixture = create(SPECS, { name: 'weekly', type: 'report' });
        const v = fixture.componentInstance.form.getRawValue();
        expect(v['name']).toBe('weekly');
        expect(v['type']).toBe('report');
        expect(v['enabled']).toBe(true); // default preserved
        expect(v['threads']).toBe(4);
    });

    it('autocomplete loads suggestions via optionLoaders and narrows them by the typed text', async () => {
        const specs: AttributeSpec[] = [
            { key: 'kind', label: 'Kind', type: 'select', tier: 'required', default: 'a', options: [{ value: 'a', label: 'A' }] },
            { key: 'target', label: 'Target', type: 'autocomplete', tier: 'required' },
        ];
        const fixture = create(specs);
        const c = fixture.componentInstance;
        c.optionLoaders = {
            // The loader sees the sibling values (here: kind) and returns the suggestion list.
            target: (v) => (v['kind'] === 'a' ? [{ value: 'cdr_ingest', label: 'cdr_ingest' }, { value: 'events_daily', label: 'events_daily' }] : []),
        };

        c.loadOptionsFor(specs[1]);
        await Promise.resolve(); // loader resolution
        expect(c.filteredOptions(specs[1]).map((o) => o.value)).toEqual(['cdr_ingest', 'events_daily']);

        c.form.get('target')?.setValue('cdr'); // typing narrows, value stays free text
        expect(c.filteredOptions(specs[1]).map((o) => o.value)).toEqual(['cdr_ingest']);
        c.form.get('target')?.setValue('anything_else');
        expect(c.validate()).toBe(true); // suggestions assist — they never constrain
    });

    it('emits submitted on native form submission (Enter in a field) and reports dirtiness', () => {
        const fixture = create();
        let submits = 0;
        fixture.componentInstance.submitted.subscribe(() => submits++);

        expect(fixture.componentInstance.isDirty()).toBe(false);
        fixture.componentInstance.form.get('name')?.setValue('daily_kpi');
        fixture.componentInstance.form.get('name')?.markAsDirty();
        expect(fixture.componentInstance.isDirty()).toBe(true);

        const form = (fixture.nativeElement as HTMLElement).querySelector('form')!;
        form.dispatchEvent(new Event('submit'));
        expect(submits).toBe(1);
    });

    it('has no axe violations with all tiers expanded', async () => {
        const fixture = create();
        fixture.componentInstance.showOptional.set(true);
        fixture.componentInstance.showAdvanced.set(true);
        fixture.componentInstance.form.get('type')?.setValue('report');
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
