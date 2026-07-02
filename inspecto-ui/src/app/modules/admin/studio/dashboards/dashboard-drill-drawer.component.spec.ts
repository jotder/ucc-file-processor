import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DashboardDrillDrawerComponent } from './dashboard-drill-drawer.component';

const ROWS = [
    { tariff: 'premium', duration_s: 320 },
    { tariff: 'standard', duration_s: 45 },
];

async function create(rows: Record<string, unknown>[] = ROWS) {
    TestBed.configureTestingModule({
        imports: [DashboardDrillDrawerComponent],
        providers: [provideNoopAnimations(), { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } }],
    });
    await TestBed.compileComponents(); // the data table @defer-loads its SQL editor
    const fixture = TestBed.createComponent(DashboardDrillDrawerComponent);
    fixture.componentRef.setInput('title', 'Total duration');
    fixture.componentRef.setInput('sourceName', 'cdr');
    fixture.componentRef.setInput('rows', rows);
    fixture.detectChanges();
    return fixture;
}

describe('DashboardDrillDrawerComponent', () => {
    it('shows the title and filtered row count', async () => {
        const fixture = await create();
        const text = fixture.nativeElement.textContent as string;
        expect(text).toContain('Total duration');
        expect(text).toContain('2 row(s)');
    });

    it('emits closed on the close button', async () => {
        const fixture = await create();
        const spy = vi.fn();
        fixture.componentInstance.closed.subscribe(spy);
        (fixture.nativeElement.querySelector('button[aria-label="Close drill-through"]') as HTMLButtonElement).click();
        expect(spy).toHaveBeenCalled();
    });

    it('renders with no a11y violations', async () => {
        await expectNoA11yViolations((await create()).nativeElement);
    });
});
