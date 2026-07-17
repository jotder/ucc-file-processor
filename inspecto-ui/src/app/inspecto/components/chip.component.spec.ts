import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ChipComponent } from './chip.component';

@Component({
    standalone: true,
    imports: [ChipComponent],
    template: `
        <inspecto-chip [variant]="variant" [tone]="tone" [removable]="removable" (removed)="onRemoved()">label</inspecto-chip>
    `,
})
class HostComponent {
    variant: 'outline' | 'soft' = 'outline';
    tone: 'neutral' | 'primary' = 'neutral';
    removable = false;
    onRemoved = vi.fn();
}

function create() {
    TestBed.configureTestingModule({ imports: [HostComponent], providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    return fixture;
}

describe('ChipComponent', () => {
    it('renders the projected label in an outline neutral pill by default', () => {
        const fixture = create();
        const pill = fixture.nativeElement.querySelector('span > span, inspecto-chip > span') as HTMLElement;
        expect(fixture.nativeElement.textContent).toContain('label');
        expect(pill.className).toContain('border');
        expect(pill.className).toContain('rounded-full');
    });

    it('uses the primary tint for the soft primary variant', () => {
        const fixture = create();
        fixture.componentInstance.variant = 'soft';
        fixture.componentInstance.tone = 'primary';
        fixture.detectChanges();
        const pill = fixture.nativeElement.querySelector('inspecto-chip > span') as HTMLElement;
        expect(pill.className).toContain('bg-primary-100');
        expect(pill.className).not.toContain('border ');
    });

    it('shows no remove button unless removable, then emits (removed) on click', () => {
        const fixture = create();
        expect(fixture.nativeElement.querySelector('button')).toBeNull();
        fixture.componentInstance.removable = true;
        fixture.detectChanges();
        const btn = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
        expect(btn).not.toBeNull();
        btn.click();
        expect(fixture.componentInstance.onRemoved).toHaveBeenCalled();
    });

    it('has no a11y violations (removable)', async () => {
        const fixture = create();
        fixture.componentInstance.removable = true;
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
