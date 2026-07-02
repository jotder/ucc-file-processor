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

    it('has no axe violations with all tiers expanded', async () => {
        const fixture = create();
        fixture.componentInstance.showOptional.set(true);
        fixture.componentInstance.showAdvanced.set(true);
        fixture.componentInstance.form.get('type')?.setValue('report');
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
