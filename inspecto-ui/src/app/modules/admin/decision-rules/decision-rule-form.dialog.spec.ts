import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it } from 'vitest';
import { DecisionRule, DecisionRulesService } from 'app/inspecto/api';
import { consequenceInputSpec } from 'app/inspecto/decision';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DecisionRuleFormData, DecisionRuleFormDialog } from './decision-rule-form.dialog';

const RULE: DecisionRule = {
    name: 'route_emea_traffic',
    targetType: 'pipeline',
    target: 'cdr_ingest',
    when: {
        kind: 'group',
        op: 'AND',
        items: [{ kind: 'condition', field: 'tariff', operator: 'startsWith', value: 'EMEA_' }],
    },
    consequences: [{ action: 'route', destination: 'emea' }],
    priority: 10,
    enabled: true,
    lastSimulation: null,
    createdAt: 1,
    updatedAt: 1,
};

function create(data: DecisionRuleFormData) {
    TestBed.configureTestingModule({
        imports: [DecisionRuleFormDialog],
        providers: [
            provideNoopAnimations(),
            provideHttpClient(), // the autocomplete option loaders inject root HTTP services
            { provide: MatDialogRef, useValue: { close: () => {} } },
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: DecisionRulesService, useValue: {} },
            { provide: ToastrService, useValue: {} },
        ],
    });
    const fixture = TestBed.createComponent(DecisionRuleFormDialog);
    fixture.detectChanges();
    return fixture;
}

describe('DecisionRuleFormDialog', () => {
    it('create mode blocks a duplicate id inline (asked at the save step) and has no a11y violations', async () => {
        const fixture = create({ existingNames: ['route_emea_traffic'] });
        const c = fixture.componentInstance;
        c.schemaForm.form.patchValue({ target: 'cdr_ingest' });
        c.consequencesArray.at(0).patchValue({ detail: 'emea' }); // 'route' requires a branch
        c.save();
        expect(c.step()).toBe('save');
        const name = c.saveForm.get('name')!;
        name.setValue('route_emea_traffic');
        expect(name.hasError('duplicate')).toBe(true);
        name.setValue('fresh_rule');
        expect(name.hasError('duplicate')).toBe(false);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('detail requiredness follows the action (route/tag require, quarantine optional, drop none)', () => {
        expect(consequenceInputSpec('route')).toMatchObject({ show: true, label: 'Branch', required: true });
        expect(consequenceInputSpec('tag').required).toBe(true);
        expect(consequenceInputSpec('quarantine').required).toBe(false);
        expect(consequenceInputSpec('drop').show).toBe(false);

        const fixture = create({});
        const c = fixture.componentInstance;
        const g = c.consequencesArray.at(0);
        expect(g.get('action')!.value).toBe('route');
        expect(g.get('detail')!.hasError('required')).toBe(true); // route needs a branch
        g.get('action')!.setValue('drop');
        expect(g.get('detail')!.hasError('required')).toBe(false); // drop has no destination
    });

    it('edit mode stays on the config step, clones the when-tree, and loads the consequences', () => {
        const fixture = create({ rule: RULE });
        const c = fixture.componentInstance;
        expect(c.isEdit).toBe(true);
        expect(c.step()).toBe('config');
        expect(c.saveForm.get('name')!.value).toBe('route_emea_traffic');
        expect(c.consequencesArray.length).toBe(1);
        expect(c.consequencesArray.at(0).get('detail')!.value).toBe('emea');
        // the condition editor mutates in place — editing must not touch the original rule object
        c.when.items.push({ kind: 'condition', field: 'cost_usd', operator: '>', value: '5' });
        expect(RULE.when.items.length).toBe(1);
    });

    it('the last consequence row cannot be removed', () => {
        const fixture = create({});
        const c = fixture.componentInstance;
        c.removeConsequence(0);
        expect(c.consequencesArray.length).toBe(1);
        c.addConsequence();
        c.removeConsequence(1);
        expect(c.consequencesArray.length).toBe(1);
    });
});
