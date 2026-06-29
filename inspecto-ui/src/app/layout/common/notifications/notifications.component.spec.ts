import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { NotificationBellComponent } from './notifications.component';

describe('NotificationBellComponent', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({
            imports: [NotificationBellComponent],
            providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
        });
    });

    function flushInitialLoad(): void {
        const http = TestBed.inject(HttpTestingController);
        for (const req of http.match(() => true)) {
            req.flush(req.request.url.includes('unread-count') ? { count: 0 } : []);
        }
    }

    it('renders the bell with no accessibility violations', async () => {
        const fixture = TestBed.createComponent(NotificationBellComponent);
        fixture.detectChanges(); // ngOnInit → refresh()
        flushInitialLoad();
        fixture.detectChanges();

        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows the unread count in the bell aria-label', () => {
        const fixture = TestBed.createComponent(NotificationBellComponent);
        const cmp = fixture.componentInstance;
        fixture.detectChanges();
        const http = TestBed.inject(HttpTestingController);
        for (const req of http.match(() => true)) {
            req.flush(req.request.url.includes('unread-count') ? { count: 3 } : []);
        }
        fixture.detectChanges();

        expect(cmp.ariaLabel()).toBe('Notifications, 3 unread');
        expect(cmp.badge()).toBe('3');
    });
});
