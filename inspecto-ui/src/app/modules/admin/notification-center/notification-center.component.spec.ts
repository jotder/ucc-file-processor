import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ChannelDelivery, NotificationChannel, NotificationsService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { NotificationCenterComponent } from './notification-center.component';

const CHANNEL: NotificationChannel = {
    id: 'ops_email',
    kind: 'EMAIL',
    target: 'ops@example.com',
    enabled: true,
    createdAt: 1,
};

const DELIVERY: ChannelDelivery = {
    id: 'dlv-1',
    ts: 2,
    channelId: 'ops_email',
    channelKind: 'EMAIL',
    target: 'ops@example.com',
    trigger: 'ALERT_FIRED',
    subject: 'Alert: failed_batches',
    status: 'SENT',
};

async function create(overrides: Partial<Record<keyof NotificationsService, unknown>> = {}) {
    const toastr = { success: vi.fn(), error: vi.fn() };
    const api = {
        // Fresh copy per test — toggle() mutates the row in place, and tests must not share state.
        channels: vi.fn(() => of([{ ...CHANNEL }])),
        deliveries: vi.fn(() => of([DELIVERY])),
        updateChannel: vi.fn((id: string, p: Partial<NotificationChannel>) => of({ ...CHANNEL, ...p })),
        deleteChannel: vi.fn(() => of({})),
        // The embedded Preferences tab loads the grid through the same service.
        preferences: () => of([]),
        ...overrides,
    } as unknown as NotificationsService;
    TestBed.configureTestingModule({
        imports: [NotificationCenterComponent],
        providers: [
            provideNoopAnimations(),
            { provide: NotificationsService, useValue: api },
            { provide: MatDialog, useValue: {} },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: () => Promise.resolve(true) } },
            { provide: ToastrService, useValue: toastr },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents(); // data-table @defer block
    const fixture = TestBed.createComponent(NotificationCenterComponent);
    fixture.detectChanges(); // ngOnInit → loadChannels() + loadDeliveries()
    return { fixture, api, toastr };
}

describe('NotificationCenterComponent', () => {
    it('loads channels and the delivery ledger on init', async () => {
        const { fixture } = await create();
        const c = fixture.componentInstance;
        expect(c.channels).toEqual([{ ...CHANNEL }]);
        expect(c.deliveries).toEqual([DELIVERY]);
    });

    it('toggles a channel optimistically and reconciles with the server result', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        c.toggle(c.channels[0]);
        expect(api.updateChannel).toHaveBeenCalledWith('ops_email', { enabled: false });
        expect(c.channels[0].enabled).toBe(false);
    });

    it('rolls the toggle back when the server call fails', async () => {
        const { fixture, toastr } = await create({
            updateChannel: () => throwError(() => ({ status: 500 })),
        });
        const c = fixture.componentInstance;
        c.toggle(c.channels[0]);
        expect(c.channels[0].enabled).toBe(true); // rolled back
        expect(toastr.error).toHaveBeenCalled();
    });

    it('deletes a channel after the destructive confirm and reloads', async () => {
        const { fixture, api } = await create();
        await fixture.componentInstance.remove(CHANNEL);
        expect(api.deleteChannel).toHaveBeenCalledWith('ops_email');
        expect(api.channels).toHaveBeenCalledTimes(2);
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = await create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
