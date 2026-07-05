import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SqlCodemirrorComponent } from './sql-codemirror.component';

async function create() {
    TestBed.configureTestingModule({
        imports: [SqlCodemirrorComponent],
        providers: [provideNoopAnimations()],
    });
    await TestBed.compileComponents();
    const fixture = TestBed.createComponent(SqlCodemirrorComponent);
    fixture.componentRef.setInput('value', 'SELECT 1');
    fixture.detectChanges(); // ngAfterViewInit → EditorView
    return fixture;
}

describe('SqlCodemirrorComponent', () => {
    it('mounts a CodeMirror editor seeded with the value input, with a labelled textbox', async () => {
        const fixture = await create();
        const el = fixture.nativeElement as HTMLElement;
        const content = el.querySelector('.cm-content')!;
        expect(content).not.toBeNull();
        expect(content.textContent).toContain('SELECT 1');
        expect(content.getAttribute('aria-label')).toBe('SQL query editor');

        // external value change is pushed into the document
        fixture.componentRef.setInput('value', 'SELECT 2');
        fixture.detectChanges();
        expect(content.textContent).toContain('SELECT 2');
    });

    it('has no a11y violations', async () => {
        const fixture = await create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
