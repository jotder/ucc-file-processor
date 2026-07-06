import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SessionService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SignInComponent } from './sign-in.component';

describe('SignInComponent (a11y, W6d)', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({
            imports: [SignInComponent],
            providers: [
                provideNoopAnimations(),
                { provide: SessionService, useValue: { loginRequired: () => true, beginLogin: vi.fn() } },
                { provide: Router, useValue: { navigate: vi.fn() } },
            ],
        });
    });

    it('renders the sign-in card with no accessibility violations', async () => {
        const fixture = TestBed.createComponent(SignInComponent);
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('h1')?.textContent).toContain('Sign in');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('starts the login redirect on click', () => {
        const fixture = TestBed.createComponent(SignInComponent);
        fixture.detectChanges();
        const session = TestBed.inject(SessionService);
        fixture.nativeElement.querySelector('button').click();
        expect(session.beginLogin).toHaveBeenCalledOnce();
    });
});
