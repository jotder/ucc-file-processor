import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { AssistService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { AssistDialog } from './assist.dialog';

const DATA = {
    title: 'New schedule',
    intent: 'nl-to-schedule',
    placeholder: 'e.g. every weekday at 6am',
    userText: 'run the rollup every night at 2am',
};

function create() {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [AssistDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: DATA },
            { provide: MatDialogRef, useValue: ref },
            { provide: AssistService, useValue: { run: vi.fn(() => of({})) } },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
        ],
    });
    const fixture = TestBed.createComponent(AssistDialog);
    fixture.detectChanges();
    return { fixture, ref };
}

describe('AssistDialog', () => {
    it('renders the title and seeds the assist panel with the user text', async () => {
        const { fixture } = create();
        await fixture.whenStable();   // ngModel writes the textarea value asynchronously
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        expect(el.querySelector('h2')?.textContent).toContain('New schedule');
        expect(el.querySelector('textarea')?.value).toBe(DATA.userText);
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
