import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SqlEditorComponent } from './sql-editor.component';

async function create() {
    TestBed.configureTestingModule({
        imports: [SqlEditorComponent],
        providers: [provideNoopAnimations()],
    });
    await TestBed.compileComponents();
    const fixture = TestBed.createComponent(SqlEditorComponent);
    fixture.componentRef.setInput('sql', 'SELECT * FROM "data"');
    fixture.componentRef.setInput('sourceName', 'spec_source');
    fixture.detectChanges();
    return fixture;
}

describe('SqlEditorComponent', () => {
    it('seeds the draft from the sql input; edits emit sqlChange and Run emits the draft', async () => {
        const fixture = await create();
        const c = fixture.componentInstance;
        expect(c.draft()).toBe('SELECT * FROM "data"');

        let changed: string | undefined;
        let ran: string | undefined;
        c.sqlChange.subscribe((v) => (changed = v));
        c.run.subscribe((v) => (ran = v));

        c.onDraft('SELECT 42');
        expect(changed).toBe('SELECT 42');
        c.onRun();
        expect(ran).toBe('SELECT 42');

        // a regenerated sql input resets the local draft
        fixture.componentRef.setInput('sql', 'SELECT 1');
        fixture.detectChanges();
        expect(c.draft()).toBe('SELECT 1');
    });

    it('has no a11y violations', async () => {
        const fixture = await create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
