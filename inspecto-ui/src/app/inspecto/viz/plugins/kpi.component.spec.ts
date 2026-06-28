import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { KpiComponent } from './kpi.component';

function create() {
    TestBed.configureTestingModule({ imports: [KpiComponent], providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(KpiComponent);
    fixture.componentRef.setInput('value', 1234);
    fixture.componentRef.setInput('label', 'Total duration');
    fixture.detectChanges();
    return fixture;
}

describe('KpiComponent', () => {
    it('formats the headline value and starts in standard mode', () => {
        const c = create().componentInstance;
        expect(c.mode()).toBe('standard');
        expect(c.display()).toBe(new Intl.NumberFormat().format(1234));
    });

    it('cycles mini → standard → max in place', () => {
        const c = create().componentInstance;
        c.mode.set('mini');
        c.cycle();
        expect(c.mode()).toBe('standard');
        c.cycle();
        expect(c.mode()).toBe('max');
        c.cycle();
        expect(c.mode()).toBe('mini');
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
