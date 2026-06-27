import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { QueryConditionGroupComponent } from './query-condition-group.component';
import { Condition, ColumnMeta, emptyGroup } from './query-types';

const COLS: ColumnMeta[] = [
    { name: 'a', type: 'number' },
    { name: 'b', type: 'string' },
];

function create() {
    TestBed.configureTestingModule({
        imports: [QueryConditionGroupComponent],
        providers: [provideNoopAnimations()],
    });
    const f = TestBed.createComponent(QueryConditionGroupComponent);
    f.componentInstance.group = emptyGroup('AND');
    f.componentInstance.columns = COLS;
    f.componentInstance.root = true;
    f.detectChanges();
    return f;
}

describe('QueryConditionGroupComponent', () => {
    it('adds a condition seeded with the first column', () => {
        const c = create().componentInstance;
        c.addCondition();
        expect(c.group.items.length).toBe(1);
        expect((c.group.items[0] as Condition).field).toBe('a');
    });

    it('adds and removes a nested group', () => {
        const c = create().componentInstance;
        c.addGroup();
        expect(c.group.items[0].kind).toBe('group');
        c.removeAt(0);
        expect(c.group.items.length).toBe(0);
    });

    it('resets the operator + values when the field type no longer supports them', () => {
        const c = create().componentInstance;
        c.addCondition();
        const cond = c.group.items[0] as Condition;
        cond.operator = 'between';
        cond.value = '1';
        cond.value2 = '2';
        c.onFieldChange(cond, 'b'); // string has no 'between'
        expect(cond.operator).toBe('=');
        expect(cond.value).toBe('');
        expect(cond.value2).toBe('');
    });

    it('has no a11y violations', async () => {
        const f = create();
        f.componentInstance.addCondition();
        f.detectChanges();
        await expectNoA11yViolations(f.nativeElement);
    });
});
