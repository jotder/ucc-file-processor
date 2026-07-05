import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { ConnectivityService } from 'app/inspecto/api/connectivity.service';
import { HealthService } from 'app/inspecto/api/health.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ConnectivityBannerComponent } from './connectivity-banner.component';

function create() {
    TestBed.configureTestingModule({
        imports: [ConnectivityBannerComponent],
        providers: [provideNoopAnimations(), { provide: HealthService, useValue: { health: vi.fn(() => of({ status: 'ok' })) } }],
    });
    const fixture = TestBed.createComponent(ConnectivityBannerComponent);
    fixture.detectChanges();
    return { fixture, conn: TestBed.inject(ConnectivityService) };
}

describe('ConnectivityBannerComponent', () => {
    it('shows a role="alert" banner while the backend is unreachable, hides it when reachable again', () => {
        const { fixture, conn } = create();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('[role="alert"]')).toBeNull();

        conn.reportUnreachable();
        fixture.detectChanges();
        const banner = el.querySelector('[role="alert"]');
        expect(banner).not.toBeNull();
        expect(banner!.textContent).toContain("Can't reach the backend");

        conn.reportReachable();
        fixture.detectChanges();
        expect(el.querySelector('[role="alert"]')).toBeNull();
    });

    it('has no a11y violations while shown', async () => {
        const { fixture, conn } = create();
        conn.reportUnreachable();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
