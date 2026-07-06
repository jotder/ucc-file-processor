import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SessionService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { CallbackComponent } from './callback.component';

describe('CallbackComponent (a11y, W6d)', () => {
    const navigate = vi.fn();
    const completeLogin = vi.fn(() => of(true));

    beforeEach(() => {
        navigate.mockClear();
        completeLogin.mockClear();
        TestBed.configureTestingModule({
            imports: [CallbackComponent],
            providers: [
                provideNoopAnimations(),
                { provide: SessionService, useValue: { completeLogin } },
                { provide: Router, useValue: { navigate } },
            ],
        });
    });

    it('redeems the code and routes into the app, with no accessibility violations', async () => {
        const fixture = TestBed.createComponent(CallbackComponent);
        fixture.componentRef.setInput('code', 'the-code');
        fixture.componentRef.setInput('state', 'the-state');
        fixture.detectChanges();
        expect(completeLogin).toHaveBeenCalledWith('the-code', 'the-state');
        expect(navigate).toHaveBeenCalledWith(['/']);
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
