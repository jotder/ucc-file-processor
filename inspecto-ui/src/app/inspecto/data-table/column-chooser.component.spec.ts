import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ColumnChooserComponent } from './column-chooser.component';

function create() {
    TestBed.configureTestingModule({
        imports: [ColumnChooserComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(ColumnChooserComponent);
    fixture.componentRef.setInput('columns', ['id', 'name', 'status']);
    fixture.componentRef.setInput('selected', ['id']);
    fixture.detectChanges();
    return fixture;
}

describe('ColumnChooserComponent', () => {
    it('toggling a column emits the selection in source-column order', () => {
        const fixture = create();
        const c = fixture.componentInstance;
        let emitted: string[] | undefined;
        c.selectedChange.subscribe((v) => (emitted = v));
        c.toggle('status', true);
        expect(emitted).toEqual(['id', 'status']); // source order, not click order
        c.toggle('id', false); // selected input is still ['id']
        expect(emitted).toEqual([]);
    });

    it('has no a11y violations (labelled icon trigger)', async () => {
        const fixture = create();
        const button = (fixture.nativeElement as HTMLElement).querySelector('button')!;
        expect(button.getAttribute('aria-label')).toBe('Choose columns');
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
