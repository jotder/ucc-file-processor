import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, ConnectionsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { OnboardingShellComponent } from './onboarding-shell.component';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };

function create(params: Record<string, string>, api: Partial<ConfigService> = {}) {
    TestBed.configureTestingModule({
        imports: [OnboardingShellComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap(params)) } },
            {
                provide: ConfigService,
                useValue: { read: () => of({ type: 'pipeline', name: params['name'], path: 'p', config: { name: params['name'] } }), ...api },
            },
            { provide: ConnectionsService, useValue: { list: () => of([]), test: () => of({}) } },
            { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(undefined) }) } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    const fixture = TestBed.createComponent(OnboardingShellComponent);
    fixture.detectChanges();
    return fixture;
}

describe('OnboardingShellComponent', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('loads the draft from the route and renders the stream stage rail', () => {
        const fixture = create({ name: 'orders_feed' });
        const text = fixture.nativeElement.textContent as string;
        expect(text).toContain('orders_feed');
        expect(text).toContain('Collection');
        expect(text).toContain('Parsing');
        expect(text).toContain('Dataset & Go-live');
        expect(text).toContain('Draft');
    });

    it('lands on the first incomplete stage when no :stage is in the URL', () => {
        const fixture = create(
            { name: 'x' },
            {
                read: () =>
                    of({
                        type: 'pipeline', name: 'x', path: 'p',
                        config: { name: 'x', collector: { connector: 'local' } },
                    }),
            },
        );
        expect(fixture.componentInstance.activeStage().id).toBe('parsing');
    });

    it('honours an explicit :stage URL param', () => {
        const fixture = create({ name: 'x', stage: 'parsing' });
        expect(fixture.componentInstance.activeStage().id).toBe('parsing');
    });

    it('shows the not-found state for a 404 draft', () => {
        const fixture = create({ name: 'ghost' }, { read: () => throwError(() => ({ status: 404 })) });
        expect(fixture.nativeElement.textContent).toContain('No pipeline or draft named');
    });

    it('View as graph navigates to the Lineage tab with the stream node id', () => {
        const fixture = create({ name: 'orders_feed' });
        const nav = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        fixture.componentInstance.viewAsGraph();
        expect(nav).toHaveBeenCalledWith(['/catalog'], { queryParams: { tab: 'graph', from: 'stream:orders_feed' } });
    });

    it('View as graph uses the ref: token for a Reference origin', () => {
        const fixture = create(
            { name: 'region_dim' },
            {
                read: () =>
                    of({ type: 'pipeline', name: 'region_dim', path: 'p', config: { name: 'region_dim', produces: 'reference' } }),
            },
        );
        const nav = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        fixture.componentInstance.viewAsGraph();
        expect(nav).toHaveBeenCalledWith(['/catalog'], { queryParams: { tab: 'graph', from: 'ref:region_dim' } });
    });

    it('has no a11y violations', async () => {
        const fixture = create({ name: 'orders_feed' });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
