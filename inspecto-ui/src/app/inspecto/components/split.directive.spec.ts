import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { InspectoSplitDirective } from './split.directive';

@Component({
    standalone: true,
    imports: [InspectoSplitDirective],
    template: `
        <div class="flex">
            <aside [style.width.px]="split.width()">left pane</aside>
            <div
                inspectoSplit="test.pane"
                #split="inspectoSplit"
                [min]="100"
                [max]="300"
                [defaultWidth]="200"
                aria-label="Resize test pane"
            ></div>
            <section>right pane</section>
        </div>
    `,
})
class HostComponent {}

function create() {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const handle = (fixture.nativeElement as HTMLElement).querySelector('[inspectoSplit]') as HTMLElement;
    const aside = (fixture.nativeElement as HTMLElement).querySelector('aside') as HTMLElement;
    return { fixture, handle, aside };
}

describe('InspectoSplitDirective', () => {
    beforeEach(() => localStorage.removeItem('inspecto.split.test.pane'));

    it('starts at defaultWidth, carries separator a11y, and drives the bound pane width', async () => {
        const { fixture, handle, aside } = create();
        expect(handle.getAttribute('role')).toBe('separator');
        expect(handle.getAttribute('aria-orientation')).toBe('vertical');
        expect(handle.getAttribute('tabindex')).toBe('0');
        expect(handle.getAttribute('aria-valuenow')).toBe('200');
        expect(handle.getAttribute('aria-valuemin')).toBe('100');
        expect(handle.getAttribute('aria-valuemax')).toBe('300');
        expect(aside.style.width).toBe('200px');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('ArrowRight/ArrowLeft nudge ±16px, clamp at min/max, and persist to localStorage', () => {
        const { fixture, handle, aside } = create();
        handle.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }));
        fixture.detectChanges();
        expect(aside.style.width).toBe('216px');
        expect(localStorage.getItem('inspecto.split.test.pane')).toBe('216');

        for (let i = 0; i < 10; i++) handle.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }));
        fixture.detectChanges();
        expect(aside.style.width).toBe('300px'); // clamped at max

        for (let i = 0; i < 20; i++) handle.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft' }));
        fixture.detectChanges();
        expect(aside.style.width).toBe('100px'); // clamped at min
    });

    it('restores the persisted width in a fresh instance (clamped to the configured range)', () => {
        localStorage.setItem('inspecto.split.test.pane', '250');
        const { aside } = create();
        expect(aside.style.width).toBe('250px');
    });
});
