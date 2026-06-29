import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideToastr } from 'ngx-toastr';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { NotificationPreferencesComponent } from './notification-preferences.component';

const GRID = [
    { category: 'pipeline', label: 'Pipeline alerts', critical: false, available: true, channels: { inApp: true, email: false } },
    { category: 'security', label: 'Security & passwords', critical: true, available: false, channels: { inApp: true, email: true } },
];

describe('NotificationPreferencesComponent', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({
            imports: [NotificationPreferencesComponent],
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                provideNoopAnimations(),
                provideToastr(),
            ],
        });
    });

    function load(): { fixture: ReturnType<typeof TestBed.createComponent<NotificationPreferencesComponent>> } {
        const fixture = TestBed.createComponent(NotificationPreferencesComponent);
        fixture.detectChanges(); // ngOnInit → preferences()
        const http = TestBed.inject(HttpTestingController);
        http.expectOne((r) => r.url.includes('/notifications/preferences')).flush(GRID);
        fixture.detectChanges();
        return { fixture };
    }

    it('renders the grid with no accessibility violations', async () => {
        const { fixture } = load();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('locks critical category toggles', () => {
        const { fixture } = load();
        const cmp = fixture.componentInstance;
        const security = cmp.rows.controls[1];
        expect(security.get('inApp')?.disabled).toBe(true);
        expect(security.get('email')?.disabled).toBe(true);
        // non-critical row stays editable
        expect(cmp.rows.controls[0].get('inApp')?.disabled).toBe(false);
    });
});
