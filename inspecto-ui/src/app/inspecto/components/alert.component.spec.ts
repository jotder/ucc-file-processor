import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { AlertVariant, InspectoAlertComponent } from './alert.component';

@Component({
    standalone: true,
    imports: [InspectoAlertComponent],
    template: `<inspecto-alert [variant]="variant" [title]="title">Editing is disabled here.</inspecto-alert>`,
})
class HostComponent {
    variant: AlertVariant = 'info';
    title = '';
}

describe('InspectoAlertComponent', () => {
    function create(inputs: Partial<HostComponent> = {}) {
        TestBed.configureTestingModule({
            imports: [HostComponent],
            providers: [provideNoopAnimations()],
        });
        const fixture = TestBed.createComponent(HostComponent);
        Object.assign(fixture.componentInstance, inputs);
        fixture.detectChanges();
        return fixture;
    }

    it('projects the message and hides the title by default', () => {
        const el: HTMLElement = create().nativeElement;
        expect(el.textContent).toContain('Editing is disabled here.');
        expect(el.querySelector('.font-semibold')).toBeNull();
    });

    it('renders the title when provided', () => {
        const el: HTMLElement = create({ title: 'Read-only' }).nativeElement;
        expect(el.querySelector('.font-semibold')?.textContent).toContain('Read-only');
    });

    it('announces info/success politely as a status', () => {
        const el: HTMLElement = create({ variant: 'info' }).nativeElement;
        const banner = el.querySelector('div[role]')!;
        expect(banner.getAttribute('role')).toBe('status');
        expect(banner.getAttribute('aria-live')).toBe('polite');
    });

    it('announces warning/error assertively as an alert', () => {
        const el: HTMLElement = create({ variant: 'error' }).nativeElement;
        const banner = el.querySelector('div[role]')!;
        expect(banner.getAttribute('role')).toBe('alert');
        expect(banner.getAttribute('aria-live')).toBe('assertive');
    });

    for (const variant of ['info', 'warning', 'error', 'success'] as const) {
        it(`has no axe violations for the "${variant}" variant`, async () => {
            const fixture = create({ variant });
            await expectNoA11yViolations(fixture.nativeElement);
        });
    }
});
