import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { emptyGroup } from 'app/inspecto/query';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { RuleSaveDialog } from './rule-save.dialog';
import { RulesService } from './rules.service';

function create() {
    const close = vi.fn();
    const save = vi.fn((r: { id: string }) => of(r));
    TestBed.configureTestingModule({
        imports: [RuleSaveDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close } },
            {
                provide: MAT_DIALOG_DATA,
                useValue: {
                    model: { projection: '*', where: emptyGroup(), sqlOverride: null },
                    sql: 'SELECT *\nFROM "alerts"',
                    sourceName: 'alerts',
                    params: [{ name: 'levelValue', field: 'level', operator: '=', value: 'ERROR' }],
                    paramSql: 'SELECT *\nFROM "alerts"\nWHERE "level" = :levelValue',
                },
            },
            { provide: RulesService, useValue: { save, list: () => of([{ id: 'existing_rule' }]) } },
            { provide: ToastrService, useValue: { success: () => {}, error: () => {} } },
        ],
    });
    const f = TestBed.createComponent(RuleSaveDialog);
    f.detectChanges();
    return { f, c: f.componentInstance, close, save };
}

describe('RuleSaveDialog', () => {
    it('does not save without a valid id', () => {
        const { c, save } = create();
        c.save();
        expect(save).not.toHaveBeenCalled();
    });

    it('blocks a duplicate id inline (house form rule) instead of relying on the server 409', () => {
        const { c, save } = create();
        c.form.get('name')!.setValue('existing_rule');
        c.save();
        expect(save).not.toHaveBeenCalled();
        expect(c.form.get('name')!.hasError('duplicate')).toBe(true);
    });

    it('saves a named rule with its parameters and closes with it', () => {
        const { c, close, save } = create();
        c.form.get('name')!.setValue('high_error');
        c.save();
        expect(save).toHaveBeenCalledWith(
            expect.objectContaining({
                id: 'high_error',
                source: 'alerts',
                params: [expect.objectContaining({ name: 'levelValue', value: 'ERROR' })],
            }),
        );
        expect(close).toHaveBeenCalled();
    });

    it('has no a11y violations', async () => {
        const { f } = create();
        await expectNoA11yViolations(f.nativeElement);
    });
});
