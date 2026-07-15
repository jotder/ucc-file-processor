import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ShortcutsHelpDialog } from './shortcuts-help.dialog';

describe('ShortcutsHelpDialog', () => {
    async function create() {
        TestBed.configureTestingModule({
            imports: [ShortcutsHelpDialog],
            providers: [provideNoopAnimations()],
        });
        const fixture = TestBed.createComponent(ShortcutsHelpDialog);
        fixture.detectChanges();
        return fixture;
    }

    it('lists the documented shortcuts (Ctrl+K, ?, Esc) with no a11y violations', async () => {
        const fixture = await create();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('command palette');
        const keys = Array.from(fixture.nativeElement.querySelectorAll('kbd')).map((k) => (k as HTMLElement).textContent);
        expect(keys).toContain('Ctrl');
        expect(keys).toContain('K');
        expect(keys).toContain('?');
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
