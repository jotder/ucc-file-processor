import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DashboardShareLink, DashboardsService } from './dashboards.service';
import { ShareDashboardDialog } from './share-dashboard.dialog';

const LINK: DashboardShareLink = {
    token: 'tok123',
    url: '/public/dashboards/tok123',
    dashboard: 'cdr_overview',
    expiresAt: '2030-01-01T00:00:00Z',
};

function create(share: () => ReturnType<DashboardsService['share']>) {
    TestBed.configureTestingModule({
        imports: [ShareDashboardDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { id: 'cdr_overview' } },
            { provide: DashboardsService, useValue: { share } },
            { provide: ToastrService, useValue: { success: () => {}, warning: () => {}, error: () => {} } },
        ],
    });
    const f = TestBed.createComponent(ShareDashboardDialog);
    f.detectChanges();
    return { f, c: f.componentInstance };
}

describe('ShareDashboardDialog', () => {
    it('mints a link on open and exposes the /share/{token} viewer URL', () => {
        const { c } = create(() => of(LINK));
        expect(c.loading()).toBe(false);
        expect(c.link()).toEqual(LINK);
        expect(c.shareUrl()).toContain('/share/tok123');
    });

    it('surfaces a disabled notice when sharing is off server-side (503)', () => {
        const { c } = create(() => throwError(() => ({ status: 503 })));
        expect(c.loading()).toBe(false);
        expect(c.link()).toBeNull();
        expect(c.disabledMessage()).toContain('bi.share.secret');
    });

    it('has no a11y violations with a minted link', async () => {
        const { f } = create(() => of(LINK));
        await expectNoA11yViolations(f.nativeElement);
    });
});
