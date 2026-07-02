import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ConnectionProfile, ConnectionsService, LensService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ConnectionsComponent } from './connections.component';

const CONN: ConnectionProfile = { id: 'sftp_edge', connector: 'sftp', host: 'edge.local', port: 22 };

function create(list: ConnectionProfile[] = [CONN]) {
    TestBed.configureTestingModule({
        imports: [ConnectionsComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ConnectionsService, useValue: { list: () => of(list), test: () => of({ reachable: true }) } },
            { provide: MatDialog, useValue: {} },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: () => Promise.resolve(true) } },
            { provide: ToastrService, useValue: { warning: () => undefined, success: () => undefined, error: () => undefined } },
        ],
    });
    const fixture = TestBed.createComponent(ConnectionsComponent);
    fixture.detectChanges(); // runs ngOnInit (list load)
    return fixture;
}

describe('ConnectionsComponent', () => {
    it('loads connections on init', () => {
        const c = create().componentInstance;
        expect(c.connections).toEqual([CONN]);
    });

    it('filters by id/connector/host', () => {
        const c = create().componentInstance;
        c.filterText = 'edge';
        expect(c.visibleConnections).toEqual([CONN]);
        c.filterText = 'nope';
        expect(c.visibleConnections).toEqual([]);
    });

    it('shows New/Edit/Delete in the default (Builder) lens', () => {
        const fixture = create();
        const el = fixture.nativeElement as HTMLElement;
        expect(Array.from(el.querySelectorAll('button')).some((b) => b.textContent?.includes('New connection'))).toBe(true);
        expect(el.querySelector('[aria-label="Edit"]')).not.toBeNull();
        expect(el.querySelector('[aria-label="Delete"]')).not.toBeNull();
    });

    it('hides New/Edit/Delete in the Business (read-only) lens', () => {
        const fixture = create();
        TestBed.inject(LensService).selectLens('business');
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(Array.from(el.querySelectorAll('button')).some((b) => b.textContent?.includes('New connection'))).toBe(false);
        expect(el.querySelector('[aria-label="Edit"]')).toBeNull();
        expect(el.querySelector('[aria-label="Delete"]')).toBeNull();
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
